package com.sunyin.aodingagent.evaluation;

import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentEvalCaseResult;
import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentEvalInput;
import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentEvalMetrics;
import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentEvalReport;
import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentJudgeVerdict;

import java.util.List;

/**
 * 声乐 agent 生成层评测器：对一批 {@link AgentEvalInput} 调用 {@link VocalAgentJudge}，
 * 汇总成 {@link AgentEvalMetrics}，并保留逐条结果。
 *
 * <p>输出里每条 {@link AgentJudgeVerdict#usedDocIds()} 是"回答实际引用的检索文档 id"，
 * 可以回填到检索层 golden set（被引用的文档视为该问题的相关候选），把两层评测闭环起来。
 */
public final class VocalAgentEvaluator {

    private VocalAgentEvaluator() {
        // 工具类：不实例化
    }

    /**
     * 对全部输入调用判官并汇总。
     *
     * @param judge  判官（LLM 或测试用假实现）
     * @param inputs 评测样本
     */
    public static AgentEvalReport evaluate(VocalAgentJudge judge, List<AgentEvalInput> inputs) {
        List<AgentEvalCaseResult> results = inputs.stream()
                .map(input -> new AgentEvalCaseResult(input.question(), judge.judge(input)))
                .toList();
        return new AgentEvalReport(results, aggregate(results));
    }

    /** 汇总一批逐条结果为平均指标。 */
    public static AgentEvalMetrics aggregate(List<AgentEvalCaseResult> results) {
        if (results.isEmpty()) return new AgentEvalMetrics(0, 0, 0, 0);
        double f = 0, r = 0, rc = 0, used = 0;
        for (AgentEvalCaseResult result : results) {
            AgentJudgeVerdict verdict = result.verdict();
            f += clamp01(verdict.faithfulness());
            r += clamp01(verdict.answerRelevance());
            rc += clamp01(verdict.refusalCorrectness());
            // contextUtilization：该案例是否实际引用了至少一条检索上下文（说明用上了知识，而非空谈）
            if (!verdict.usedDocIds().isEmpty()) used += 1;
        }
        int n = results.size();
        return new AgentEvalMetrics(round(f / n), round(r / n), round(rc / n), round(used / n));
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
