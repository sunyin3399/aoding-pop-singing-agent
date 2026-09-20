package com.sunyin.aodingagent.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RagRetrievalEvaluator} 的单元测试：用固定候选的假向量库验证
 * 离线评测能复用生产检索器并算出合理指标，且阈值扫描能反映召回变化。
 */
class RagRetrievalEvaluatorTest {

    private static Document doc(String id, String source, double score) {
        return Document.builder().id(id)
                .text("声乐训练内容")
                .metadata("filename", source + ".md")
                .metadata("contentHash", source)
                .score(score).build();
    }

    @Test
    void evaluatesRecallAndPrecisionAgainstGoldenSet() {
        // 候选：3 个同源相关(≥0.55) + 1 个低于阈值的相关 + 1 个噪声
        FixedVectorStore store = new FixedVectorStore(List.of(
                doc("rel-a", "混声训练", 0.72),
                doc("rel-b", "混声训练", 0.68),
                doc("rel-c", "混声训练", 0.62),
                doc("rel-d", "混声进阶", 0.30),   // 相关但低于阈值 + 不同来源 → 不会被召回
                doc("noise-1", "其他话题", 0.45)));
        List<RagRetrievalEvaluator.GoldenCase> golden = List.of(
                new RagRetrievalEvaluator.GoldenCase("训练混声怎么练",
                        List.of("rel-a", "rel-b", "rel-c", "rel-d")));

        RagRetrievalMetrics.Aggregated metrics =
                RagRetrievalEvaluator.evaluate(golden, store, 5, 0.55);

        assertEquals(0.75, metrics.recallAtK(), 1e-6, "3/4 个相关被召回");
        assertEquals(1.0, metrics.precisionAtK(), 1e-6);
        assertEquals(1.0, metrics.hitRateAtK(), 1e-6);
        assertEquals(1.0, metrics.mrr(), 1e-6);
    }

    @Test
    void thresholdSweepShowsRecallSensitivity() {
        FixedVectorStore store = new FixedVectorStore(List.of(
                doc("rel-a", "混声训练", 0.72),
                doc("rel-b", "混声训练", 0.68),
                doc("rel-c", "混声训练", 0.62),
                doc("rel-d", "混声进阶", 0.30)));
        List<RagRetrievalEvaluator.GoldenCase> golden = List.of(
                new RagRetrievalEvaluator.GoldenCase("训练混声怎么练",
                        List.of("rel-a", "rel-b", "rel-c", "rel-d")));

        List<RagRetrievalEvaluator.Result> sweep =
                RagRetrievalEvaluator.sweepThreshold(golden, store, 5, List.of(0.5, 0.66));

        assertEquals(2, sweep.size());
        assertEquals(0.5, sweep.get(0).threshold(), 1e-6);
        assertEquals(0.75, sweep.get(0).metrics().recallAtK(), 1e-6, "阈值 0.5 时 3/4 相关被召回");
        assertEquals(0.66, sweep.get(1).threshold(), 1e-6);
        assertEquals(0.5, sweep.get(1).metrics().recallAtK(), 1e-6, "阈值 0.66 时只剩 2/4，召回下降");
    }

    @Test
    void loadsGoldenSetFromJsonl() throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of(
                "src/test/resources/evaluation/rag-retrieval-golden-cases.jsonl");
        assertTrue(java.nio.file.Files.exists(path), "golden set 示例文件应存在");

        List<RagRetrievalEvaluator.GoldenCase> golden = RagRetrievalEvaluator.loadGoldenCases(path);
        assertEquals(5, golden.size());
        assertEquals("训练混声怎么练", golden.getFirst().query());
        assertTrue(golden.getFirst().relevantIds().contains("doc-mix-method-1"));
    }

    /** 返回固定候选的假向量库，让生产检索器的内部阈值过滤生效。 */
    private static final class FixedVectorStore implements VectorStore {
        private final List<Document> candidates;

        private FixedVectorStore(List<Document> candidates) {
            this.candidates = candidates;
        }

        @Override
        public void add(List<Document> documents) { }

        @Override
        public void delete(List<String> idList) { }

        @Override
        public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) { }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return new ArrayList<>(candidates);
        }
    }
}
