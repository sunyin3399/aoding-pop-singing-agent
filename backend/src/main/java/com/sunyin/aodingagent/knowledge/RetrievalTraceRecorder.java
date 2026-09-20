package com.sunyin.aodingagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 检索痕迹记录器：把每次内部知识检索的关键信息追加到 JSONL 文件。
 * <p>
 * 目的是<b>平时测试/对话时就自动积累评估数据</b>：每一条记录包含查询、检索返回的 Top-K
 * （id / 文件名 / chunk 序号 / 相似度 / 排名）以及最终被回答"实际用到"的 chunk id
 * （usedIds，当前留空，供后续端到端接线填充）。
 * 攒够后可用 {@link RagRetrievalEvaluator} 跑出 Recall@K / Precision@K / MRR 等指标。
 *
 * <p>通过配置 <code>app.rag.trace-dir</code> 启用（默认为空 = 关闭），按天写入
 * <code>&lt;trace-dir&gt;/yyyy-MM-dd.jsonl</code>。
 */
@Component
public class RetrievalTraceRecorder {

    private static final Logger logger = LoggerFactory.getLogger(RetrievalTraceRecorder.class);
    // 需注册 java.time 模块，否则无法序列化 Instant 时间戳
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final boolean enabled;
    private final Path traceDir;

    @Autowired
    public RetrievalTraceRecorder(@Value("${app.rag.trace-dir:}") String traceDir) {
        this(traceDir == null || traceDir.isBlank() ? null : Path.of(traceDir));
    }

    RetrievalTraceRecorder(Path traceDir) {
        this.enabled = traceDir != null;
        this.traceDir = traceDir;
    }

    /** 生成一个默认关闭的记录器，便于没有启用追踪时安全注入。 */
    static RetrievalTraceRecorder disabled() {
        return new RetrievalTraceRecorder((Path) null);
    }

    boolean isEnabled() {
        return enabled;
    }

    /** 追加一条检索痕迹；未启用时直接忽略。 */
    public void record(RetrievalTrace trace) {
        if (!enabled || trace == null) return;
        Path daily = traceDir.resolve(LocalDate.now(ZoneId.systemDefault()).toString() + ".jsonl");
        try {
            Files.createDirectories(traceDir);
            Files.writeString(daily, MAPPER.writeValueAsString(trace) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            logger.warn("写入 RAG 检索痕迹失败: path={}, reason={}", daily, exception.getMessage());
        }
    }

    /** 单条检索痕迹。 */
    public record RetrievalTrace(
            String query,
            Instant timestamp,
            int candidateTopK,
            double threshold,
            List<Hit> retrieved,
            List<String> usedIds
    ) { }

    /** 检索到的一个候选 chunk 及其相似度、排名。 */
    public record Hit(
            String id,
            String filename,
            Integer chunkIndex,
            Double score,
            int rank
    ) { }
}
