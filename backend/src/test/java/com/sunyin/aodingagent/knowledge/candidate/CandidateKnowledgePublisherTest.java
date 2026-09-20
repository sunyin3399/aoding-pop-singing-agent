package com.sunyin.aodingagent.knowledge.candidate;

import com.sunyin.aodingagent.knowledge.KnowledgeDocumentCatalog;
import com.sunyin.aodingagent.rag.MarkdownKnowledgeDocumentFactory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateKnowledge;
import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus;
import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus.APPROVED;
import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus.PENDING;
import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus.REJECTED;
import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.ExternalSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateKnowledgePublisherTest {

    @Test
    void approvesOnlyAfterPublishingStableDocumentsAndMakesThemReadable() {
        MemoryRepository repository = new MemoryRepository(candidate());
        RecordingVectorStore vectorStore = new RecordingVectorStore(false);
        KnowledgeDocumentCatalog catalog = new KnowledgeDocumentCatalog(List.of());
        CandidateKnowledgePublisher publisher = publisher(repository, vectorStore, catalog);

        CandidateKnowledge approved = publisher.approve("candidate-1", "内容与来源已核验");

        assertThat(approved.status()).isEqualTo(APPROVED);
        assertThat(approved.publishedDocumentIds()).isNotEmpty();
        assertThat(vectorStore.added).extracting(Document::getId)
                .containsExactlyElementsOf(approved.publishedDocumentIds());
        assertThat(vectorStore.added).allSatisfy(document -> {
            assertThat(document.getMetadata()).containsEntry("candidateId", "candidate-1");
            assertThat(document.getMetadata()).containsEntry("approvalStatus", "APPROVED");
        });
        assertThat(catalog.findApproved(approved.publishedDocumentIds().getFirst())).isPresent();

        CandidateKnowledge repeated = publisher.approve("candidate-1", "重复请求");
        assertThat(repeated).isEqualTo(approved);
        assertThat(vectorStore.addCalls).isEqualTo(1);
    }

    @Test
    void leavesCandidatePendingWhenVectorPublicationFails() {
        MemoryRepository repository = new MemoryRepository(candidate());
        CandidateKnowledgePublisher publisher = publisher(
                repository, new RecordingVectorStore(true), new KnowledgeDocumentCatalog(List.of()));

        assertThatThrownBy(() -> publisher.approve("candidate-1", "批准"))
                .isInstanceOf(CandidateKnowledgePublisher.PublicationException.class);
        assertThat(repository.findById("candidate-1")).get().extracting(CandidateKnowledge::status).isEqualTo(PENDING);
    }

    @Test
    void rejectsWithoutWritingVectorsAndKeepsReviewIdempotent() {
        MemoryRepository repository = new MemoryRepository(candidate());
        RecordingVectorStore vectorStore = new RecordingVectorStore(false);
        CandidateKnowledgePublisher publisher = publisher(
                repository, vectorStore, new KnowledgeDocumentCatalog(List.of()));

        CandidateKnowledge rejected = publisher.reject("candidate-1", "来源不足");

        assertThat(rejected.status()).isEqualTo(REJECTED);
        assertThat(rejected.reviewNote()).isEqualTo("来源不足");
        assertThat(publisher.reject("candidate-1", "重复拒绝")).isEqualTo(rejected);
        assertThat(vectorStore.addCalls).isZero();
    }

    private CandidateKnowledgePublisher publisher(
            CandidateKnowledgeRepository repository,
            VectorStore vectorStore,
            KnowledgeDocumentCatalog catalog
    ) {
        return new CandidateKnowledgePublisher(
                repository, vectorStore, new MarkdownKnowledgeDocumentFactory(500, 10), catalog);
    }

    private CandidateKnowledge candidate() {
        return new CandidateKnowledge(
                "candidate-1", "如何减少鼻音", "减少过重鼻音", markdown(),
                List.of(new ExternalSource("Voice Foundation", "https://example.org/nasal")),
                "content-hash", PENDING, Instant.parse("2026-08-11T06:00:00Z"),
                null, null, List.of());
    }

    private String markdown() {
        return """
                # 减少过重鼻音

                ## 原理
                区分正常共鸣与过重鼻音。

                ## 训练动作
                - 轻声练习 5 分钟。

                ## 常见错误
                - 不捏鼻发声。

                ## 适用范围
                日常音色训练。

                ## 安全提醒
                疼痛时停止。

                ## 资料来源
                - [Voice Foundation](https://example.org/nasal)
                """;
    }

    private static final class MemoryRepository implements CandidateKnowledgeRepository {
        private final Map<String, CandidateKnowledge> candidates = new LinkedHashMap<>();

        private MemoryRepository(CandidateKnowledge candidate) {
            candidates.put(candidate.id(), candidate);
        }

        @Override
        public CandidateKnowledge save(CandidateKnowledge candidate) {
            candidates.put(candidate.id(), candidate);
            return candidate;
        }

        @Override
        public Optional<CandidateKnowledge> findById(String id) {
            return Optional.ofNullable(candidates.get(id));
        }

        @Override
        public List<CandidateKnowledge> findAll(CandidateStatus status) {
            return candidates.values().stream().filter(value -> status == null || value.status() == status).toList();
        }
    }

    private static final class RecordingVectorStore implements VectorStore {
        private final boolean fail;
        private final List<Document> added = new ArrayList<>();
        private int addCalls;

        private RecordingVectorStore(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void add(List<Document> documents) {
            addCalls++;
            if (fail) throw new IllegalStateException("vector unavailable");
            added.addAll(documents);
        }

        @Override
        public void delete(List<String> idList) {
        }

        @Override
        public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return List.of();
        }
    }
}
