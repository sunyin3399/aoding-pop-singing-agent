package com.sunyin.aodingagent.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetrievalObservationTest {
    @Test void metadataIncludesRejectedCandidatesFromBothStagesWithoutChangingCitations() throws Exception {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(
                List.of(doc("overview", "歌曲推荐《甲》《乙》", .9), doc("first-rejected", "weak", .1)),
                List.of(doc("detail", "《甲》练习", .8), doc("detail-rejected", "weak", .1)));
        var result = new VectorStoreVocalKnowledgeRetriever(store, 2, 4, .55, null).search("songs");
        assertEquals(List.of("detail", "overview"), result.citations().stream().map(Citation::id).toList());
        // Reflection lets RED exercise the real result rather than fail compilation on a new API.
        var accessor = java.util.Arrays.stream(result.getClass().getMethods())
                .filter(m -> m.getName().equals("retrievalMetadata")).findFirst();
        assertTrue(accessor.isPresent(), "request-local retrieval metadata missing");
        Object metadata = accessor.get().invoke(result);
        assertNotNull(metadata);
        assertEquals(List.of("overview", "first-rejected", "detail", "detail-rejected"),
                metadata.getClass().getMethod("candidateIds").invoke(metadata));
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result).contains("first-rejected"),
                "eval metadata must not alter the model/user tool payload");
    }
    @Test void emptyAndBlankQueriesHaveObservedEmptyCandidatesButLegacyResultsRemainUnknown() {
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        var retriever = new VectorStoreVocalKnowledgeRetriever(store, 2, 4, .55, null);
        for (String query : List.of("no-hit", " ")) {
            var result = retriever.search(query);
            assertEquals(VocalKnowledgeRetriever.KnowledgeSearchStatus.NO_KNOWLEDGE_HIT, result.status());
            assertTrue(result.citations().isEmpty());
            assertNotNull(result.retrievalMetadata(), "blank query is observed, not missing instrumentation");
            assertEquals(List.of(), result.retrievalMetadata().candidateIds());
        }
        verify(store, times(1)).similaritySearch(any(SearchRequest.class));
        assertNull(VocalKnowledgeRetriever.KnowledgeSearchResult.of("legacy", List.of()).retrievalMetadata());
        assertNull(new VocalKnowledgeRetriever.KnowledgeSearchResult(
                VocalKnowledgeRetriever.KnowledgeSearchStatus.NO_KNOWLEDGE_HIT, "legacy", List.of()).retrievalMetadata());
    }

    static Document doc(String id, String text, double score) {
        return Document.builder().id(id).text(text).score(score).build();
    }
}
