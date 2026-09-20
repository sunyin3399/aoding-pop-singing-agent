package com.sunyin.aodingagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.agent.AodingManus;
import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentEvalInput;
import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentEvalReport;
import com.sunyin.aodingagent.metrics.LlmMetricsTracker;
import com.sunyin.aodingagent.research.finalization.AgentResponseFinalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 声乐 agent 生成层的离线评测执行器。
 * <p>
 * 它读一份 golden 案例（问题 + 期望拒答标记），对每个问题通过真实的 {@link AodingManus}
 * agent（与线上 {@code /ai/manus/chat} 同款构造）执行 {@code runToSink}，用
 * {@link CollectingSink} 收集最终回答与 agent 实际引用的上下文，再交 {@link VocalAgentJudge}
 * 打分并汇总成 {@link AgentEvalReport}。
 *
 * <p><b>作用边界</b>：这是"执行离线测试集"的那块胶水，需要活 ChatModel + 工具 + 向量库，
 * 无法离线单测；单测只覆盖 {@link CollectingSink} 的解析与 {@link VocalAgentEvaluator} 的汇总。
 * finalizer 与线上一致传给 agent，是否应用由 agent 内部按场景决定（仅外部研究的结构化交付才走
 * {@code researchContract != null} 分支）。
 */
@Component
public class VocalAgentEvalRunner {

    private static final Logger logger = LoggerFactory.getLogger(VocalAgentEvalRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 单条 golden 案例最多等待 agent 执行多久。 */
    private static final long CASE_TIMEOUT_SECONDS = 120;

    private final ToolCallback[] allTools;
    private final ChatModel chatModel;
    private final LlmMetricsTracker metricsTracker;
    private final AgentResponseFinalizer responseFinalizer;
    private final VocalAgentJudge judge;

    @Autowired
    public VocalAgentEvalRunner(
            ToolCallback[] allTools,
            ChatModel chatModel,
            LlmMetricsTracker metricsTracker,
            AgentResponseFinalizer responseFinalizer,
            VocalAgentJudge judge) {
        this.allTools = allTools;
        this.chatModel = chatModel;
        this.metricsTracker = metricsTracker;
        this.responseFinalizer = responseFinalizer;
        this.judge = judge;
    }

    /** 一条 golden 案例：问题 + 期望拒答标记（可空）。 */
    public record GoldenCase(String question, Boolean expectedRefusal) { }

    /**
     * 对 golden 文件中的每个案例执行一次真实 agent，判分并汇总。
     *
     * @param goldenFile JSONL，每行 {"question":"...","expectedRefusal":true|false}
     */
    public AgentEvalReport runGolden(Path goldenFile) throws IOException {
        return runCases(loadGolden(goldenFile));
    }

    /** 对给定案例逐个跑 agent、判分、汇总。 */
    public AgentEvalReport runCases(List<GoldenCase> cases) {
        List<AgentEvalInput> inputs = new ArrayList<>(cases.size());
        for (GoldenCase goldenCase : cases) {
            CollectingSink sink = runAgent(goldenCase.question());
            inputs.add(assembleInput(goldenCase, sink));
        }
        return VocalAgentEvaluator.evaluate(judge, inputs);
    }

    /** 执行一次 agent 并等待其结束，收集最终回答与引用上下文。 */
    private CollectingSink runAgent(String question) {
        CollectingSink sink = new CollectingSink();
        AodingManus agent = new AodingManus(allTools, chatModel, metricsTracker, responseFinalizer);
        try {
            Future<?> task = agent.runToSink(question, sink);
            task.get(CASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            logger.warn("agent 评测超时: question='{}', 跳过该案例", question);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("agent 评测被中断: question='{}'", question);
        } catch (ExecutionException exception) {
            logger.warn("agent 评测执行异常: question='{}', reason={}", question, exception.getCause());
        }
        return sink;
    }

    /** 把一次 agent 运行的结果组装成判官输入（可单测的纯逻辑）。 */
    static AgentEvalInput assembleInput(GoldenCase goldenCase, CollectingSink sink) {
        return new AgentEvalInput(
                goldenCase.question(),
                sink.context(),
                sink.finalAnswer() == null ? "" : sink.finalAnswer(),
                goldenCase.expectedRefusal());
    }

    private List<GoldenCase> loadGolden(Path file) throws IOException {
        try (var lines = Files.lines(file)) {
            return lines.filter(line -> !line.isBlank())
                    .map(line -> {
                        try {
                            return MAPPER.readValue(line, GoldenCase.class);
                        } catch (IOException exception) {
                            throw new IllegalArgumentException("golden 案例无法解析: " + line, exception);
                        }
                    })
                    .toList();
        }
    }
}
