package com.sunyin.aodingagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * RAG 检索的离线评测器。
 * <p>
 * 拿一份 golden set（{@code 查询 → 该问题真正相关的 chunk id}，只含 grade ≥ 2），
 * 通过<b>生产检索器</b>（{@link VectorStoreVocalKnowledgeRetriever}）逐条重放查询，
 * 用 {@link RagRetrievalMetrics} 计算 Recall@K / Precision@K / Hit rate@K / MRR，
 * 并可对相似度阈值做扫描，观察"阈值-召回"曲线来定阈值。
 *
 * <p>与线上链路解耦：评测在独立的测试/脚本里跑，只依赖 {@link VectorStore}，不依赖 LLM。
 */
public final class RagRetrievalEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RagRetrievalEvaluator() {
        // 工具类：不实例化
    }

    /** 一条 golden case：查询 + 该问题"真正相关"的 chunk id（grade ≥ 2 才列入）。 */
    public record GoldenCase(String query, List<String> relevantIds) { }

    /** 一个 (topK, threshold) 组合下的评测结果。 */
    public record Result(
            int topK,
            double threshold,
            RagRetrievalMetrics.Aggregated metrics,
            int caseCount
    ) { }

    /**
     * 在给定 topK 与阈值下，对全部 golden case 评测，返回平均指标。
     * 通过生产检索器执行（保留其优势来源扩展、详情补查等逻辑），保证与线上行为一致。
     */
    public static RagRetrievalMetrics.Aggregated evaluate(
            List<GoldenCase> golden, VectorStore store, int topK, double threshold) {
        List<RagRetrievalMetrics.RetrievalScores> scores = golden.stream()
                .map(caseItem -> RagRetrievalMetrics.compute(
                        caseItem.relevantIds(),
                        retrieveIds(store, topK, threshold, caseItem.query()),
                        topK))
                .toList();
        return RagRetrievalMetrics.aggregate(scores);
    }

    /** 用生产检索器对单个查询执行检索，返回命中的 citation id（按排名顺序）。 */
    public static List<String> retrieveIds(VectorStore store, int topK, double threshold, String query) {
        VectorStoreVocalKnowledgeRetriever retriever = new VectorStoreVocalKnowledgeRetriever(
                store, topK, Math.max(10, topK * 3), threshold, RetrievalTraceRecorder.disabled());
        return retriever.search(query).citations().stream().map(Citation::id).toList();
    }

    /** 对多个阈值做扫描，返回每个阈值的评测结果，便于观察"阈值-召回"曲线。 */
    public static List<Result> sweepThreshold(
            List<GoldenCase> golden, VectorStore store, int topK, List<Double> thresholds) {
        return thresholds.stream()
                .map(threshold -> new Result(topK, threshold,
                        evaluate(golden, store, topK, threshold), golden.size()))
                .toList();
    }

    /** 从 JSONL 文件读取 golden set（每行一个 GoldenCase）。 */
    public static List<GoldenCase> loadGoldenCases(Path file) throws IOException {
        try (var lines = Files.lines(file)) {
            return lines.filter(line -> !line.isBlank())
                    .map(line -> {
                        try {
                            return MAPPER.readValue(line, GoldenCase.class);
                        } catch (IOException exception) {
                            throw new IllegalArgumentException("golden set 行无法解析: " + line, exception);
                        }
                    })
                    .toList();
        }
    }
}
