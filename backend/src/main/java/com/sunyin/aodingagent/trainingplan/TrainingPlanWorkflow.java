package com.sunyin.aodingagent.trainingplan;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanRequest;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanResult;

/**
 * 训练计划功能对 Controller 和 Agent 工具提供的统一入口。
 * <p>
 * 页面接口和聊天 Agent 都调用同一个 workflow，确保两种入口执行相同的输入校验、安全阻断、
 * 知识检索和计划质检规则。
 */
public interface TrainingPlanWorkflow {

    /** 根据用户请求执行完整训练计划流程，调用方应根据返回状态判断是否生成成功。 */
    TrainingPlanResult create(TrainingPlanRequest request);
}
