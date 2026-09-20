package com.sunyin.aodingagent.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link RagRetrievalMetrics} 的单元测试。
 */
class RagRetrievalMetricsTest {

    @Test
    void computesAllMetricsForACleanHit() {
        RagRetrievalMetrics.RetrievalScores scores = RagRetrievalMetrics.compute(
                List.of("a", "b", "c"), List.of("a", "x", "b", "y", "c"), 3);

        assertEquals(2.0 / 3, scores.recallAtK(), 1e-3);
        assertEquals(2.0 / 3, scores.precisionAtK(), 1e-3);
        assertEquals(1.0, scores.hitAtK(), 1e-6);
        assertEquals(1.0, scores.mrr(), 1e-6, "第一个相关 a 排在第 1");
    }

    @Test
    void returnsZeroWhenNothingRelevantIsRetrieved() {
        RagRetrievalMetrics.RetrievalScores scores = RagRetrievalMetrics.compute(
                List.of("a", "b", "c"), List.of("x", "y", "z", "w", "q"), 5);

        assertEquals(0.0, scores.recallAtK(), 1e-6);
        assertEquals(0.0, scores.precisionAtK(), 1e-6);
        assertEquals(0.0, scores.hitAtK(), 1e-6);
        assertEquals(0.0, scores.mrr(), 1e-6);
    }

    @Test
    void recallIsCappedByKWhenRelevantExceedsK() {
        // 5 个相关、只取前 K=3，理论最高 Recall@3 = 3/5 = 0.6
        RagRetrievalMetrics.RetrievalScores scores = RagRetrievalMetrics.compute(
                List.of("a", "b", "c", "d", "e"), List.of("a", "b", "c"), 3);

        assertEquals(0.6, scores.recallAtK(), 1e-6, "Recall@K 被 K 封顶");
        assertEquals(1.0, scores.precisionAtK(), 1e-6);
    }

    @Test
    void mrrReflectsRankOfFirstRelevantHit() {
        RagRetrievalMetrics.RetrievalScores scores = RagRetrievalMetrics.compute(
                List.of("c"), List.of("x", "c", "y", "z"), 5);

        assertEquals(0.5, scores.mrr(), 1e-6, "第一个相关排第 2，MRR=1/2");
        assertEquals(1.0, scores.hitAtK(), 1e-6);
    }

    @Test
    void aggregatesAveragesAcrossCases() {
        RagRetrievalMetrics.Aggregated aggregated = RagRetrievalMetrics.aggregate(List.of(
                RagRetrievalMetrics.compute(List.of("a"), List.of("a", "x"), 5),   // recall 1, mrr 1
                RagRetrievalMetrics.compute(List.of("a"), List.of("x", "y"), 5),   // recall 0, mrr 0
                RagRetrievalMetrics.compute(List.of("a"), List.of("a", "b"), 5))); // recall 1, mrr 1

        assertEquals(2.0 / 3, aggregated.recallAtK(), 1e-3);
        assertEquals(2.0 / 3, aggregated.hitRateAtK(), 1e-3, "只有两条有命中");
        assertEquals(2.0 / 3, aggregated.mrr(), 1e-3);
    }
}
