package com.sunyin.aodingagent.evaluation;

import com.fasterxml.jackson.databind.*;
import com.sunyin.aodingagent.agent.ToolCallAgent;
import com.sunyin.aodingagent.knowledge.*;
import com.sunyin.aodingagent.metrics.LlmMetricsTracker;
import com.sunyin.aodingagent.stream.*;
import com.sunyin.aodingagent.tools.VocalKnowledgeTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.*;
import org.springframework.ai.vectorstore.*;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DailyAgentRetrievalCaptureTest {
    @TempDir Path dir;
    final ObjectMapper json = new ObjectMapper();
    DailyAgentCapture capture() {
        return new DailyAgentCapture(new MockEnvironment().withProperty("app.agent-eval.enabled", "true")
                .withProperty("app.agent-eval.dir", dir.toString()), Runnable::run);
    }
    JsonNode row() throws Exception {
        try (var files = Files.list(dir.resolve("raw"))) { return json.readTree(files.findFirst().orElseThrow().toFile()); }
    }
    @Test void cancellationDuringAnotherRetrievalDoesNotReportEarlierCandidatesAsComplete() throws Exception {
        new StreamSessionRegistry().getOrStart("cancel-in-flight", session -> {
            capture().attach(session, "q", "manus/chat");
            session.emit("eval_retrieval", "{\"toolName\":\"searchVocalKnowledge\",\"callId\":\"one\",\"retrievalCandidateIds\":[\"old\"]}");
            session.emit("tool_start", "{\"toolName\":\"searchVocalKnowledge\",\"callId\":\"two\"}");
            session.cancel();
            session.emit("eval_retrieval", "{\"toolName\":\"searchVocalKnowledge\",\"callId\":\"two\",\"retrievalCandidateIds\":[\"late\"]}");
        });
        assertEquals("unavailable", row().path("retrievalCandidateIds").asText());
        assertEquals("CANCELLED", row().path("terminalStatus").asText());
    }

    @Test void unionsRemainSeparatedAndReconnectDoesNotCollectAgain() throws Exception {
        var registry = new StreamSessionRegistry();
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        registry.getOrStart("one", session -> {
            starts.incrementAndGet(); capture().attach(session, "q", "manus/chat");
            session.emit("eval_retrieval", "{\"toolName\":\"searchVocalKnowledge\",\"callId\":\"one\",\"retrievalCandidateIds\":[\"candidate\",\"shared\"]}");
            session.emit("eval_retrieval", "{\"toolName\":\"searchVocalKnowledge\",\"callId\":\"two\",\"retrievalCandidateIds\":[\"shared\",\"second\"]}");
            session.emit("eval_prompt_documents", "{\"promptDocumentIds\":[\"shared\"]}");
            session.emit("eval_prompt_documents", "{\"promptDocumentIds\":[]}");
            session.emit("references", "[{\"id\":\"reference-only\"}]");
            session.emit("final", "answer"); session.complete();
        });
        registry.getOrStart("one", session -> starts.incrementAndGet());
        assertEquals(1, starts.get());
        var r = row();
        assertEquals(List.of("candidate", "shared", "second"), json.convertValue(r.path("retrievalCandidateIds"), List.class));
        assertEquals(List.of("shared"), json.convertValue(r.path("promptDocumentIds"), List.class));
        assertEquals(List.of("reference-only"), json.convertValue(r.path("referenceIds"), List.class));
    }

    @Test void unknownAndOversizedObservationsAreStickyAndDoNotCopyUnsafeReasonText() throws Exception {
        new StreamSessionRegistry().getOrStart("invalid", session -> {
            capture().attach(session, "q", "manus/chat");
            session.emit("eval_retrieval", "{\"toolName\":\"searchVocalKnowledge\",\"callId\":\"one\",\"retrievalCandidateIds\":\"unavailable\",\"reason\":\"private sk-fixture\"}");
            session.emit("eval_retrieval", "{\"toolName\":\"searchVocalKnowledge\",\"callId\":\"two\",\"retrievalCandidateIds\":[]}");
            try { session.emit("eval_prompt_documents", json.writeValueAsString(Map.of("promptDocumentIds",
                    java.util.stream.IntStream.range(0, 129).mapToObj(i -> "doc-" + i).toList()))); }
            catch (Exception e) { throw new AssertionError(e); }
            session.emit("eval_prompt_documents", "{\"promptDocumentIds\":[]}");
            session.emit("final", "answer"); session.complete();
        });
        var r = row();
        for (String field : List.of("retrievalCandidateIds", "promptDocumentIds")) {
            assertEquals("unavailable", r.path(field).asText());
            assertTrue(r.path("unavailableReasons").has(field));
            assertFalse(r.path("availableDimensions").toString().contains(field));
        }
        assertFalse(r.toString().contains("private sk-fixture"));
    }

    @Test void concurrentSessionsDoNotShareDocumentSets() throws Exception {
        var recorder = capture();
        var registry = new StreamSessionRegistry();
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (String id : List.of("a", "b")) tasks.add(pool.submit(() -> registry.getOrStart(id, session -> {
                recorder.attach(session, id, "manus/chat");
                try { barrier.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError(e); }
                session.emit("eval_retrieval", "{\"toolName\":\"searchVocalKnowledge\",\"callId\":\"" + id + "\",\"retrievalCandidateIds\":[\"" + id + "\"]}");
                session.emit("eval_prompt_documents", "{\"promptDocumentIds\":[\"" + id + "\"]}");
                session.emit("final", "answer"); session.complete();
            })));
            for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        try (var files = Files.list(dir.resolve("raw"))) {
            var paths = files.toList(); assertEquals(2, paths.size());
            for (var path : paths) {
                var r = json.readTree(path.toFile());
                for (String field : List.of("retrievalCandidateIds", "promptDocumentIds"))
                    assertEquals(List.of(r.path("question").asText()), json.convertValue(r.path(field), List.class));
            }
        }
    }

    @Test void musicAppStaysExplicitlyUnobserved() throws Exception {
        new StreamSessionRegistry().getOrStart("music", session -> {
            capture().attach(session, "q", "music_app/chat/sse");
            session.emit("final", "answer"); session.complete();
        });
        var r = row();
        for (String field : List.of("retrievalCandidateIds", "promptDocumentIds")) {
            assertEquals("unavailable", r.path(field).asText());
            assertTrue(r.path("unavailableReasons").path(field).asText().contains("MusicApp"));
        }
    }

    @Test void realRetrieverToolManagerAndModelDispatchReachTheSameCapture() throws Exception {
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(
                List.of(Document.builder().id("overview").text("歌曲推荐《甲》《乙》").score(.9).build(),
                        Document.builder().id("rejected-first").text("weak").score(.1).build()),
                List.of(Document.builder().id("detail").text("《甲》练习").score(.8).build(),
                        Document.builder().id("rejected-detail").text("weak").score(.1).build()));
        var callbacks = new org.springframework.ai.tool.ToolCallback[] {new VocalKnowledgeTool(new VectorStoreVocalKnowledgeRetriever(store, 2, 4, .55, null)).callback()};
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));
        new StreamSessionRegistry().getOrStart("integration", session -> {
            capture().attach(session, "q", "manus/chat");
            var agent = new ToolCallAgent(callbacks, mock(LlmMetricsTracker.class)) {
                @Override protected void emitLiveEvent(String name, String data) { session.emit(name, data); }
            };
            agent.setSystemPrompt("system"); agent.setChatClient(ChatClient.builder(model).build());
            var call = new AssistantMessage("", Map.of(), List.of(new AssistantMessage.ToolCall("p1", "function", "searchVocalKnowledge", "{\"query\":\"q\"}")));
            agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(call))));
            agent.act(); agent.think(); session.emit("final", "answer"); session.complete();
        });
        var r = row();
        assertTrue(r.path("retrievalCandidateIds").isArray(), "candidate capture remains unavailable");
        assertTrue(r.path("promptDocumentIds").isArray(), "prompt capture remains unavailable");
        assertEquals(List.of("overview", "rejected-first", "detail", "rejected-detail"),
                json.convertValue(r.path("retrievalCandidateIds"), List.class));
        assertEquals(List.of("detail", "overview"), json.convertValue(r.path("promptDocumentIds"), List.class));
        assertEquals("unavailable", r.path("referenceIds").asText(), "no reference SSE was emitted by this fixture");
        assertEquals("PARTIAL", r.path("traceCompleteness").asText());
        assertFalse(r.path("events").toString().contains("eval_retrieval"));
        assertFalse(r.path("events").toString().contains("歌曲推荐"));
    }
}
