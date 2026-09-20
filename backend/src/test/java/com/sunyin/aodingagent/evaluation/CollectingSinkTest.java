package com.sunyin.aodingagent.evaluation;

import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentEvalInput;
import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.ContextEntry;
import com.sunyin.aodingagent.evaluation.VocalAgentEvalRunner.GoldenCase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CollectingSink} 与 {@link VocalAgentEvalRunner#assembleInput} 的单元测试。
 */
class CollectingSinkTest {

    @Test
    void collectsFinalAnswerAndParsesReferences() {
        CollectingSink sink = new CollectingSink();
        sink.emit("step", "执行步骤 1");
        sink.emit("final", "先用胸声打底，再逐步加入头声……");
        sink.emit("references",
                "[{\"id\":\"doc-1\",\"title\":\"混声训练\",\"excerpt\":\"先用胸声打底……\"},"
                        + "{\"id\":\"doc-2\",\"title\":\"换声区\",\"excerpt\":\"\"}]");

        assertEquals("先用胸声打底，再逐步加入头声……", sink.finalAnswer());
        assertEquals(2, sink.context().size());
        assertEquals("doc-1", sink.context().get(0).id());
        assertTrue(sink.context().get(0).text().contains("混声训练"));
        assertTrue(sink.context().get(0).text().contains("先用胸声打底"));
        assertNull(sink.error());
    }

    @Test
    void toleratesMissingOrMalformedReferences() {
        CollectingSink sink = new CollectingSink();
        assertTrue(sink.context().isEmpty());

        sink.emit("references", "not-json");
        assertTrue(sink.context().isEmpty(), "解析失败应回退为空");

        sink.emit("references", "");
        assertTrue(sink.context().isEmpty());
    }

    @Test
    void capturesErrorEvent() {
        CollectingSink sink = new CollectingSink();
        sink.emit("error", "Agent 状态异常");
        assertEquals(IllegalStateException.class, sink.error().getClass());
    }

    @Test
    void assemblesJudgeInputFromSink() {
        CollectingSink sink = new CollectingSink();
        sink.emit("final", "回答正文");
        sink.emit("references", "[{\"id\":\"doc-1\",\"title\":\"知识\",\"excerpt\":\"片段\"}]");

        AgentEvalInput input = VocalAgentEvalRunner.assembleInput(
                new GoldenCase("训练混声怎么练", true), sink);

        assertEquals("训练混声怎么练", input.question());
        assertEquals("回答正文", input.answer());
        assertEquals(true, input.expectedRefusal());
        assertEquals(1, input.context().size());
        assertEquals("doc-1", input.context().get(0).id());
    }

    @Test
    void defaultAnswerToEmptyWhenNoFinalEvent() {
        CollectingSink sink = new CollectingSink();
        AgentEvalInput input = VocalAgentEvalRunner.assembleInput(
                new GoldenCase("问题", false), sink);
        assertEquals("", input.answer());
        assertTrue(input.context().isEmpty());
    }
}
