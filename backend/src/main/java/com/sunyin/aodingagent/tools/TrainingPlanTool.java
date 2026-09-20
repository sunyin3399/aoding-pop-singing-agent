package com.sunyin.aodingagent.tools;

import com.sunyin.aodingagent.tool.ToolResult;
import com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanRequest;
import com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanResult;
import com.sunyin.aodingagent.trainingplan.TrainingPlanWorkflow;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.SUCCESS;

/**
 * 把训练计划工作流包装成流行演唱 Agent 可以调用的工具。
 * <p>
 * Agent 不自己拼接训练计划，而是把用户表单交给这个工具。工具再复用页面接口使用的同一套
 * {@link TrainingPlanWorkflow}，避免聊天入口和训练计划页面产生两套不同的业务规则。
 */
@Component
public class TrainingPlanTool {

    private final TrainingPlanWorkflow workflow;

    public TrainingPlanTool(TrainingPlanWorkflow workflow) {
        this.workflow = workflow;
    }

    /**
     * 调用受校验保护的训练计划流程，并把业务结果转换成统一的工具返回格式。
     *
     * @param request Agent 从用户需求中整理出的完整训练计划表单
     * @return 包含计划状态和详细结果的工具响应；只有状态为 SUCCESS 时 success 才为 true
     */
    @Tool(description = "根据完整用户表单生成经过校验的流行演唱训练计划；信息不足时返回需要补充的字段")
    public ToolResult<TrainingPlanRequest> prepareTrainingPlanForm(
            @ToolParam(description = "训练计划表单，包含目标、水平、周期、每日时长、当前问题和嗓音风险")
            TrainingPlanRequest request
    ) {
        return new ToolResult<>(true, "TRAINING_PLAN_FORM", request, null, false);
    }

    public ToolResult<TrainingPlanResult> createTrainingPlan(TrainingPlanRequest request) {
        TrainingPlanResult result = workflow.create(request);
        boolean success = result.status() == SUCCESS;
        return new ToolResult<>(
                success,
                result.status().name(),
                result,
                success ? null : result.issues().stream()
                        .map(issue -> issue.message()).collect(java.util.stream.Collectors.joining("；")),
                false
        );
    }
}
