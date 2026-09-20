package com.sunyin.aodingagent.trainingplan;

import com.sunyin.aodingagent.knowledge.Citation;

import java.util.List;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlan;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanRequest;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ValidationIssue;

/**
 * 定义“如何让大模型生成或修改训练计划”的统一接口。
 * <p>
 * 业务工作流只依赖这个接口，不关心具体使用哪个模型或怎样编写提示词。这样测试时可以换成
 * 假实现，未来更换模型时也不需要改动训练计划的校验和安全流程。
 */
public interface TrainingPlanGenerator {

    /**
     * 根据用户需求和内部知识引用生成第一版计划。
     *
     * @param request 用户提交的完整训练需求
     * @param citations 本次检索到、可供模型参考的内部知识
     * @return 大模型生成的结构化计划初稿，返回后仍需经过 {@link TrainingPlanValidator} 检查
     */
    TrainingPlan generate(TrainingPlanRequest request, List<Citation> citations);

    /**
     * 根据校验器发现的问题修改一次计划。
     *
     * @param request 用户原始需求
     * @param draft 未通过检查的计划初稿
     * @param issues 初稿中需要修正的具体问题
     * @param citations 允许继续使用的内部知识引用
     * @return 修改后的计划，返回后必须再次经过校验器检查
     */
    TrainingPlan repair(
            TrainingPlanRequest request,
            TrainingPlan draft,
            List<ValidationIssue> issues,
            List<Citation> citations
    );
}
