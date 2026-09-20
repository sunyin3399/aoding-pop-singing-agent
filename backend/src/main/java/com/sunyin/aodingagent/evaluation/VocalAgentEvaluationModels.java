package com.sunyin.aodingagent.evaluation;

import java.util.List;

/**
 * 声乐 agent 生成层（端到端）评测的数据模型。
 * <p>
 * 检索层评测（{@link com.sunyin.aodingagent.knowledge.RagRetrievalMetrics}）回答"检索器有没有
 * 捞到对的内容"；本模型回答的是更上层的问题——"agent 给出的回答是否忠实、切题、该拒答时正确拒答"。
 * 每条样本由 {@link AgentEvalInput} 描述（问题 + agent 拿到的检索上下文 + agent 的回答），
 * 由 {@link VocalAgentJudge} 打出一份 {@link AgentJudgeVerdict}，再由
 * {@link VocalAgentEvaluator} 汇总成 {@link AgentEvalMetrics}。
 */
public final class VocalAgentEvaluationModels {

    private VocalAgentEvaluationModels() {
        // 仅承载嵌套记录
    }

    /** 一条评测输入：问题 + agent 拿到的检索上下文 + agent 的回答。 */
    public record AgentEvalInput(
            String question,
            List<ContextEntry> context,
            String answer,
            Boolean expectedRefusal
    ) { }

    /** agent 拿到的一段检索上下文（chunk 或外部来源片段）。 */
    public record ContextEntry(String id, String text) { }

    /** 判官对一条回答的判定。faithfulness/relevance/refusalCorrectness 均为 0..1。 */
    public record AgentJudgeVerdict(
            double faithfulness,
            double answerRelevance,
            double refusalCorrectness,
            List<String> usedDocIds
    ) { }

    /** 单条样本的结果。 */
    public record AgentEvalCaseResult(
            String question,
            AgentJudgeVerdict verdict
    ) { }

    /** 一批样本的平均指标。 */
    public record AgentEvalMetrics(
            double avgFaithfulness,
            double avgAnswerRelevance,
            double avgRefusalCorrectness,
            double avgContextUtilization
    ) { }

    /** 完整评测报告：逐条结果 + 汇总指标。 */
    public record AgentEvalReport(
            List<AgentEvalCaseResult> results,
            AgentEvalMetrics metrics
    ) { }
}
