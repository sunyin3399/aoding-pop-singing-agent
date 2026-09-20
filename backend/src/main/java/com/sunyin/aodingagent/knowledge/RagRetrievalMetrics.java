package com.sunyin.aodingagent.knowledge;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 检索指标的纯计算工具。
 * <p>
 * 只负责把"该问题真正相关的 chunk id"（golden set 标签）和"检索器实际返回的 Top-K id"
 * 换算成标准指标，不依赖任何 Spring / 存储实现，便于单测。
 *
 * <p>指标含义（详见项目内 VOCAL_PRODUCTION_REFERENCE.md 与相关讨论）：
 * <ul>
 *   <li>Recall@K 召回率：相关 chunk 里有多少进了前 K。分子最多 K 个，因此被 K 封顶
 *       （相关数 &gt; K 时理论最高 = K/相关数）。</li>
 *   <li>Precision@K 精确率：前 K 条里有多少是相关的（夹带的噪声越少越高）。</li>
 *   <li>Hit rate@K 命中率：前 K 里有没有至少一个相关（只看"有没有捞到"）。</li>
 *   <li>MRR：第一个相关结果的排名的倒数，衡量"第一个有效命中来得快不快"。</li>
 * </ul>
 */
public final class RagRetrievalMetrics {

    private RagRetrievalMetrics() {
        // 工具类：不实例化
    }

    /** 单条查询在某个 K 下的四个检索指标。 */
    public record RetrievalScores(
            double recallAtK,
            double precisionAtK,
            double hitAtK,
            double mrr
    ) { }

    /** 一批查询在某个 K 下的平均指标。 */
    public record Aggregated(
            double recallAtK,
            double precisionAtK,
            double hitRateAtK,
            double mrr
    ) { }

    /**
     * 计算单条查询的检索指标。
     *
     * @param relevantIds   golden set 标出的、该问题真正相关的 chunk id（只含 grade ≥ 2）
     * @param retrievedIds  检索器实际返回的 Top-K chunk id（按排名顺序）
     * @param k             截取前 K 条
     */
    public static RetrievalScores compute(List<String> relevantIds, List<String> retrievedIds, int k) {
        List<String> top = retrievedIds.size() <= k ? retrievedIds : retrievedIds.subList(0, k);
        Set<String> relevant = new HashSet<>(relevantIds);
        long hits = top.stream().filter(relevant::contains).count();

        double recall = relevantIds.isEmpty() ? 0.0 : hits / (double) relevantIds.size();
        double precision = top.isEmpty() ? 0.0 : hits / (double) top.size();
        double hit = hits > 0 ? 1.0 : 0.0;

        double mrr = 0.0;
        for (int index = 0; index < top.size(); index++) {
            if (relevant.contains(top.get(index))) {
                mrr = 1.0 / (index + 1);
                break;
            }
        }
        return new RetrievalScores(round(recall, 4), round(precision, 4), round(hit, 4), round(mrr, 4));
    }

    /** 对一批查询的指标取平均。 */
    public static Aggregated aggregate(List<RetrievalScores> scores) {
        if (scores.isEmpty()) return new Aggregated(0, 0, 0, 0);
        double r = 0, p = 0, h = 0, m = 0;
        for (RetrievalScores s : scores) {
            r += s.recallAtK();
            p += s.precisionAtK();
            h += s.hitAtK();
            m += s.mrr();
        }
        int n = scores.size();
        return new Aggregated(round(r / n, 4), round(p / n, 4), round(h / n, 4), round(m / n, 4));
    }

    private static double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }
}
