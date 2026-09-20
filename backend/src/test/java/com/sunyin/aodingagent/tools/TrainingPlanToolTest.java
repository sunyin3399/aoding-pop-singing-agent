package com.sunyin.aodingagent.tools;

import com.sunyin.aodingagent.tool.ToolResult;
import com.sunyin.aodingagent.trainingplan.TrainingPlanModels;
import com.sunyin.aodingagent.trainingplan.TrainingPlanWorkflow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.OutputFormat.PAGE;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanRequest;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanResult;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.NEEDS_CLARIFICATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingPlanToolTest {

    @Test
    void preparesAFormWithoutRunningTheWorkflow() {
        TrainingPlanRequest request = new TrainingPlanRequest("c1", "改善气息", "BEGINNER", 7, 20,
                List.of("气息不稳"), null, null, null, PAGE);
        TrainingPlanTool tool = new TrainingPlanTool(ignored -> {
            throw new AssertionError("表单准备阶段不得生成计划");
        });

        ToolResult<TrainingPlanRequest> result = tool.prepareTrainingPlanForm(request);

        assertTrue(result.success());
        assertEquals("TRAINING_PLAN_FORM", result.code());
        assertEquals(request, result.data());
    }

    @Test
    void exposesTheSameWorkflowResultToTheAgent() {
        TrainingPlanResult expected = TrainingPlanResult.terminal(
                NEEDS_CLARIFICATION,
                List.of(new TrainingPlanModels.ClarificationQuestion("goal", "请填写目标")),
                List.of(new TrainingPlanModels.ValidationIssue("REQUIRED", "goal", "目标不能为空")));
        TrainingPlanWorkflow workflow = request -> expected;

        ToolResult<TrainingPlanResult> result = new TrainingPlanTool(workflow).createTrainingPlan(
                new TrainingPlanRequest("c1", "", null, null, null, List.of(), null, null, null, PAGE));

        assertFalse(result.success());
        assertEquals("NEEDS_CLARIFICATION", result.code());
        assertEquals(expected, result.data());
    }
}
