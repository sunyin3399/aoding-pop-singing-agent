package com.sunyin.aodingagent.evaluation;

import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentEvalInput;
import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentJudgeVerdict;

/**
 * 声乐 agent 生成层判官：给定（问题、agent 拿到的检索上下文、agent 的回答），
 * 判定回答是否忠实、切题、以及该拒答时是否正确拒答。
 *
 * <p>实现可以是 LLM（{@link SpringAiVocalAgentJudge}），也可以是测试用的假实现。
 */
public interface VocalAgentJudge {

    /**
     * 判定一条 agent 回答。
     *
     * @param input 问题 + 检索上下文 + 回答（含可选的期望拒答标记）
     * @return 忠实度 / 切题度 / 拒答正确度 / 实际引用的文档 id
     */
    AgentJudgeVerdict judge(AgentEvalInput input);
}
