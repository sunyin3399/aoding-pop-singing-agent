package com.sunyin.aodingagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.knowledge.*;
import com.sunyin.aodingagent.tools.VocalKnowledgeTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.document.Document;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetrievalCallbackObservationTest {
    @Test void sharedToolConcurrentRequestsKeepSeparateMetadataAndPreserveCallerContext() throws Exception {
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenAnswer(call -> {
            var request = (SearchRequest) call.getArgument(0);
            barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return List.of(Document.builder().id(request.getQuery()).text("private evidence").score(.9).build());
        });
        var delegate = new VocalKnowledgeTool(new VectorStoreVocalKnowledgeRetriever(store, 2, 4, .55, null)).callback();
        assertFalse(delegate.getToolDefinition().inputSchema().contains("context"), delegate.getToolDefinition().inputSchema());
        var source = new org.springframework.ai.chat.model.ToolContext(Map.of("existing", "preserved"));
        var first = new java.util.concurrent.ConcurrentHashMap<String, JsonNode>();
        var second = new java.util.concurrent.ConcurrentHashMap<String, JsonNode>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> observed(delegate, first).call("{\"query\":\"request-a\"}", source));
            var b = pool.submit(() -> observed(delegate, second).call("{\"query\":\"request-b\"}", source));
            assertTrue(a.get(10, java.util.concurrent.TimeUnit.SECONDS).contains("request-a"));
            assertTrue(b.get(10, java.util.concurrent.TimeUnit.SECONDS).contains("request-b"));
        }
        assertEquals("request-a", first.get("eval_retrieval").path("retrievalCandidateIds").get(0).asText());
        assertEquals("request-b", second.get("eval_retrieval").path("retrievalCandidateIds").get(0).asText());
        assertNotEquals(first.get("eval_retrieval").path("callId"), second.get("eval_retrieval").path("callId"));
        assertEquals(Map.of("existing", "preserved"), source.getContext());
    }

    @Test void noHitIsObservedEmptyButLegacyMissingMetadataAndExceptionsStayUnknown() {
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        var events = new LinkedHashMap<String, JsonNode>();
        observed(new VocalKnowledgeTool(new VectorStoreVocalKnowledgeRetriever(store, 2, 4, .55, null)).callback(), events)
                .call("{\"query\":\"q\"}");
        assertTrue(events.get("eval_retrieval").path("retrievalCandidateIds").isEmpty());
        assertTrue(events.get("eval_retrieval").path("retrievalCandidateIds").isArray());
        assertFalse(events.get("tool_result").path("success").asBoolean(true));
        var legacy = new VocalKnowledgeTool(q -> VocalKnowledgeRetriever.KnowledgeSearchResult.of(q, List.of()));
        observed(legacy.callback(), events).call("{\"query\":\"q\"}");
        assertEquals("unavailable", events.get("eval_retrieval").path("retrievalCandidateIds").asText());
        var failure = new VocalKnowledgeTool(q -> { throw new IllegalStateException("private error sk-secret"); });
        assertThrows(RuntimeException.class, () -> observed(failure.callback(), events).call("{\"query\":\"q\"}"));
        assertEquals("unavailable", events.get("eval_retrieval").path("retrievalCandidateIds").asText());
        assertFalse(events.toString().contains("private error"));
        assertFalse(events.get("tool_result").path("success").asBoolean(true));
    }

    static ObservedToolCallback observed(org.springframework.ai.tool.ToolCallback delegate, Map<String, JsonNode> events) {
        return new ObservedToolCallback(delegate, (name, data) -> {
            try { events.put(name, new ObjectMapper().readTree(data)); } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test void realToolCallbackTransfersAllCandidatesButNotIntoModelPayload() throws Exception {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(
                List.of(Document.builder().id("accepted").text("private evidence").score(.8).build(),
                        Document.builder().id("rejected").text("private rejected").score(.1).build()));
        var tool = new VocalKnowledgeTool(new VectorStoreVocalKnowledgeRetriever(store, 2, 4, .55, null));
        Map<String, JsonNode> events = new LinkedHashMap<>();
        var callback = new ObservedToolCallback(tool.callback(), (name, data) -> {
            try { events.put(name, new ObjectMapper().readTree(data)); } catch (Exception e) { throw new AssertionError(e); }
        });
        String result = callback.call("{\"query\":\"q\"}");
        assertTrue(events.containsKey("eval_retrieval"), "explicit callback retrieval event missing");
        assertEquals(List.of("accepted", "rejected"), new ObjectMapper().convertValue(
                events.get("eval_retrieval").path("retrievalCandidateIds"), List.class));
        assertFalse(result.contains("rejected"));
        assertFalse(result.contains("retrievalMetadata"));
        assertFalse(events.toString().contains("private"));
        assertFalse(events.get("eval_retrieval").has("promptDocumentIds"), "a callback return is not a sent prompt");
    }
}
