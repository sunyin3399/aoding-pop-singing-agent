package com.sunyin.aodingagent.evaluation;

import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentEvalCaseResult;
import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentEvalInput;
import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentEvalMetrics;
import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentEvalReport;
import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentJudgeVerdict;
import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.ContextEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link VocalAgentEvaluator} 的单元测试：用假判官验证逐条打分、汇总指标与 usedDocIds 采集。
 */
class VocalAgentEvaluatorTest {

    /** 测试用假判官：根据问题返回可预期的判定。 */
    private static final class FakeJudge implements VocalAgentJudge {
        @Override
        public AgentJudgeVerdict judge(AgentEvalInput input) {
            if (input.question().contains("编造")) {
                return new AgentJudgeVerdict(0.2, 0.9, 0.8, List.of());
            }
            return new AgentJudgeVerdict(0.9, 0.95, 1.0, List.of("doc-1"));
        }
    }

    @Test
    void aggregatesAveragesAndUsageRatio() {
        List<AgentEvalCaseResult> results = List.of(
                new AgentEvalCaseResult("q1", new AgentJudgeVerdict(0.9, 0.9, 1.0, List.of("doc-1"))),
                new AgentEvalCaseResult("q2", new AgentJudgeVerdict(0.5, 0.5, 0.5, List.of())),
                new AgentEvalCaseResult("q3", new AgentJudgeVerdict(0.7, 0.7, 0.7, List.of("doc-2"))));

        AgentEvalMetrics metrics = VocalAgentEvaluator.aggregate(results);

        assertEquals(0.7, metrics.avgFaithfulness(), 1e-6);
        assertEquals(0.7, metrics.avgAnswerRelevance(), 1e-6);
        assertEquals(2.2 / 3, metrics.avgRefusalCorrectness(), 1e-3);
        assertEquals(2.0 / 3, metrics.avgContextUtilization(), 1e-3, "两条实际引用了上下文");
    }

    @Test
    void evaluateCallsJudgePerInputAndKeepsPerCaseResults() {
        FakeJudge judge = new FakeJudge();
        List<AgentEvalInput> inputs = List.of(
                new AgentEvalInput("训练混声怎么练",
                        List.of(new ContextEntry("doc-1", "混声训练方法……")), "先用胸声打底……", false),
                new AgentEvalInput("某个不存在主题的编造回答",
                        List.of(new ContextEntry("doc-x", "无关内容")), "我编了一段……", null));

        AgentEvalReport report = VocalAgentEvaluator.evaluate(judge, inputs);

        assertEquals(2, report.results().size());
        // 第二条被判为低忠实度（0.2）
        AgentEvalCaseResult lowFaith = report.results().stream()
                .filter(r -> r.question().contains("编造")).findFirst().orElseThrow();
        assertEquals(0.2, lowFaith.verdict().faithfulness(), 1e-6);
        // 汇总：两条忠实度平均 (0.9+0.2)/2
        assertEquals(0.55, report.metrics().avgFaithfulness(), 1e-3);
    }
}
