package com.sunyin.aodingagent.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorStoreVocalKnowledgeRetrieverTest {

    @Test
    void mapsDocumentsToClickableInternalCitationsAndForwardsSearchOptions() {
        RecordingVectorStore store = new RecordingVectorStore(List.of(Document.builder()
                .id("doc-1")
                .text("换声区训练摘要")
                .metadata("filename", "如何解决换声区.md")
                .score(0.88)
                .build()));
        VectorStoreVocalKnowledgeRetriever retriever = new VectorStoreVocalKnowledgeRetriever(store, 4, 0.72);

        VocalKnowledgeRetriever.KnowledgeSearchResult result = retriever.search("换声区怎么练");

        assertEquals(VocalKnowledgeRetriever.KnowledgeSearchStatus.FOUND, result.status());
        assertEquals("doc-1", result.citations().getFirst().id());
        assertEquals("/api/ai/knowledge/documents/doc-1", result.citations().getFirst().url());
        assertEquals(12, store.lastRequest.getTopK());
        assertEquals(0.0, store.lastRequest.getSimilarityThreshold());
        assertEquals("换声区怎么练", store.lastRequest.getQuery());
    }

    @Test
    void returnsAnExplicitNoHitResult() {
        VectorStoreVocalKnowledgeRetriever retriever = new VectorStoreVocalKnowledgeRetriever(
                new RecordingVectorStore(List.of()), 5, 0.7);

        VocalKnowledgeRetriever.KnowledgeSearchResult result = retriever.search("不存在的主题");

        assertEquals(VocalKnowledgeRetriever.KnowledgeSearchStatus.NO_KNOWLEDGE_HIT, result.status());
        assertTrue(result.citations().isEmpty());
    }

    @Test
    void filtersCandidatesInTheApplicationAfterCollectingDiagnosticScores() {
        RecordingVectorStore store = new RecordingVectorStore(List.of(
                Document.builder().id("accepted").text("相关内容").score(0.61).build(),
                Document.builder().id("rejected").text("弱相关内容").score(0.49).build()));
        VectorStoreVocalKnowledgeRetriever retriever = new VectorStoreVocalKnowledgeRetriever(store, 5, 0.55);

        VocalKnowledgeRetriever.KnowledgeSearchResult result = retriever.search("进阶歌曲");

        assertEquals(List.of("accepted"), result.citations().stream().map(Citation::id).toList());
        assertEquals(0.0, store.lastRequest.getSimilarityThreshold());
    }

    @Test
    void keepsEnoughKnowledgeTextForConcreteExerciseGuidance() {
        String content = "呼吸训练".repeat(200);
        VectorStoreVocalKnowledgeRetriever retriever = new VectorStoreVocalKnowledgeRetriever(
                new RecordingVectorStore(List.of()), 5, 0.7);

        Citation citation = retriever.toCitation(Document.builder().id("doc-1").text(content).score(0.9).build());

        assertEquals(600, citation.excerpt().length());
        assertTrue(citation.excerpt().endsWith("..."));
    }

    @Test
    void performsOneBatchDetailSearchForARecommendedSongList() {
        SequencedVectorStore store = new SequencedVectorStore(
                List.of(Document.builder().id("overview")
                        .text("建议学习顺序：先练《好久不见》《当你》《晴天》。")
                        .score(0.8).build()),
                List.of(Document.builder().id("detail")
                        .text("《好久不见》推荐原因：主歌接近说话。主要练习：自然咬字。")
                        .score(0.50).build()));
        VectorStoreVocalKnowledgeRetriever retriever = new VectorStoreVocalKnowledgeRetriever(store, 5, 0.55);

        VocalKnowledgeRetriever.KnowledgeSearchResult result = retriever.search("适合初学者的歌曲");

        assertEquals(2, store.requests.size());
        assertTrue(store.requests.get(1).getQuery().contains("好久不见 当你 晴天"));
        assertEquals(List.of("detail", "overview"), result.citations().stream().map(Citation::id).toList());
    }

    @Test
    void expandsChunksWhenOneSourceDominatesTheRelevantResults() {
        List<Document> chunks = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            chunks.add(Document.builder().id("breath-" + index)
                    .text("气息支撑章节 " + index + "：练法、时长、常见错误与安全提醒")
                    .metadata("filename", "如何强化气息支撑.md")
                    .metadata("contentHash", "breath-support-document")
                    .metadata("chunkIndex", index)
                    .score(index < 3 ? 0.72 - index * 0.04 : 0.53 - index * 0.01).build());
        }
        RecordingVectorStore store = new RecordingVectorStore(chunks);
        VectorStoreVocalKnowledgeRetriever retriever = new VectorStoreVocalKnowledgeRetriever(store, 5, 0.55);

        VocalKnowledgeRetriever.KnowledgeSearchResult result = retriever.search("气息支撑怎么练");

        // Dominant-source expansion keeps up to 2*topK chunks so a precise document is not cut too early.
        assertEquals(List.of("breath-0", "breath-1", "breath-2", "breath-3", "breath-4", "breath-5", "breath-6"),
                result.citations().stream().map(Citation::id).toList());
        assertTrue(result.citations().stream().anyMatch(citation -> citation.relevance() < 0.55));
        assertEquals(1, store.requestCount);
    }

    @Test
    void doesNotExpandWhenRelevantResultsComeFromMultipleSources() {
        List<Document> candidates = List.of(
                sourceDocument("a-1", "source-a", 0.72),
                sourceDocument("b-1", "source-b", 0.70),
                sourceDocument("a-2", "source-a", 0.68),
                sourceDocument("b-2", "source-b", 0.66),
                sourceDocument("a-low", "source-a", 0.40));
        RecordingVectorStore store = new RecordingVectorStore(candidates);
        VectorStoreVocalKnowledgeRetriever retriever = new VectorStoreVocalKnowledgeRetriever(store, 5, 0.55);

        VocalKnowledgeRetriever.KnowledgeSearchResult result = retriever.search("初学者怎么练声");

        assertEquals(List.of("a-1", "b-1", "a-2", "b-2"),
                result.citations().stream().map(Citation::id).toList());
        assertEquals(1, store.requestCount);
    }

    private static Document sourceDocument(String id, String source, double score) {
        return Document.builder().id(id).text("普通练声内容")
                .metadata("filename", source + ".md")
                .metadata("contentHash", source)
                .score(score).build();
    }

    private static final class RecordingVectorStore implements VectorStore {
        private final List<Document> result;
        private SearchRequest lastRequest;
        private int requestCount;

        private RecordingVectorStore(List<Document> result) {
            this.result = result;
        }

        @Override
        public void add(List<Document> documents) {
        }

        @Override
        public void delete(List<String> idList) {
        }

        @Override
        public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            requestCount++;
            lastRequest = request;
            return new ArrayList<>(result);
        }
    }

    private static final class SequencedVectorStore implements VectorStore {
        private final ArrayDeque<List<Document>> results;
        private final List<SearchRequest> requests = new ArrayList<>();

        @SafeVarargs
        private SequencedVectorStore(List<Document>... results) {
            this.results = new ArrayDeque<>(List.of(results));
        }

        @Override
        public void add(List<Document> documents) {
        }

        @Override
        public void delete(List<String> idList) {
        }

        @Override
        public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            requests.add(request);
            return new ArrayList<>(results.removeFirst());
        }
    }
}
