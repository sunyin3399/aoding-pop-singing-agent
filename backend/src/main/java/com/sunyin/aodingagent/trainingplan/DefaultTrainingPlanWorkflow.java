package com.sunyin.aodingagent.trainingplan;

import com.sunyin.aodingagent.knowledge.Citation;
import com.sunyin.aodingagent.knowledge.VocalKnowledgeRetriever;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlan;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanRequest;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanResult;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.FAILED;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.SUCCESS;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.VALIDATION_FAILED;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ValidationIssue;

/**
 * 负责生成训练计划的完整流程，可以把它理解成训练计划的“总调度员”。
 * <p>
 * 如果直接让大模型生成计划，可能出现信息不全也继续生成、每天训练时间超出用户要求、
 * 没有覆盖完整周期的训练日，或者引用了本次没有查到的资料等问题。这个类把生成过程拆成
 * 固定的几个步骤，并让专门的校验类检查结果，避免把大模型返回的内容直接交给用户。
 * <p>
 * 完整流程如下：
 * <ol>
 *     <li>检查用户信息是否填写完整，以及当前嗓音状态是否适合训练；</li>
 *     <li>从已经审核过的内部声乐知识库中查找相关资料；</li>
 *     <li>让大模型根据用户需求和内部资料生成训练计划；</li>
 *     <li>检查计划天数、每日时长、训练结构、停止条件和引用是否符合要求；</li>
 *     <li>第一次检查失败时让大模型修改一次，第二次仍不合格就返回失败。</li>
 * </ol>
 * 大模型负责“写计划”，普通 Java 代码负责“检查计划是否合格”，两者分工可以让结果更稳定。
 */
@Service
public class DefaultTrainingPlanWorkflow implements TrainingPlanWorkflow {

    private final TrainingPlanInputValidator inputValidator;
    private final TrainingPlanValidator planValidator;
    private final VocalKnowledgeRetriever knowledgeRetriever;
    private final TrainingPlanGenerator generator;

    public DefaultTrainingPlanWorkflow(
            TrainingPlanInputValidator inputValidator,
            TrainingPlanValidator planValidator,
            VocalKnowledgeRetriever knowledgeRetriever,
            TrainingPlanGenerator generator
    ) {
        this.inputValidator = inputValidator;
        this.planValidator = planValidator;
        this.knowledgeRetriever = knowledgeRetriever;
        this.generator = generator;
    }

    /**
     * 根据用户填写的信息生成一份经过检查的训练计划。
     * <p>
     * 这个方法是整个训练计划功能的入口。它不会保证每次都返回计划，而是根据实际情况返回：
     * <ul>
     *     <li>信息缺失：返回需要用户补充的问题；</li>
     *     <li>用户存在疼痛或持续嘶哑：停止生成并返回安全提醒；</li>
     *     <li>计划生成并检查通过：返回可以展示给用户的计划；</li>
     *     <li>计划修改一次后仍不合格：返回具体的校验问题；</li>
     *     <li>调用大模型发生异常：返回生成失败信息。</li>
     * </ul>
     * 因此，调用方应该先查看返回结果中的 {@code status}，不能假设 {@code plan} 一定有值。
     *
     * @param request 用户填写的训练目标、当前水平、训练天数、每日时长、当前问题和嗓音状态
     * @return 本次处理结果，其中包含状态；成功时包含计划，失败时包含需要补充或修正的原因
     */
    @Override
    public TrainingPlanResult create(TrainingPlanRequest request) {
        // 第一步先检查输入。返回值不为 null，说明信息不完整或存在嗓音风险，不需要继续调用大模型。
        TrainingPlanResult terminal = inputValidator.classify(request);
        if (terminal != null) return terminal;
        request = applySafetyLimits(request);

        // 第二步从已审核的内部知识库查资料。后面只允许计划引用这次实际查到的资料。
        List<Citation> citations = knowledgeRetriever.search(searchQuery(request)).citations();
        try {
            // 第三步让大模型生成初稿，再由普通 Java 校验器逐项检查，不能只相信模型自己说“已完成”。
            TrainingPlan draft = applySafetyNotices(request, generator.generate(request, citations));
            List<ValidationIssue> issues = planValidator.validate(request, draft, citations);
            if (issues.isEmpty()) return success(request, draft, citations);

            // 初稿不合格时，把具体问题交给大模型修改一次。只修改一次可以避免反复调用模型而无法结束。
            TrainingPlan repaired = applySafetyNotices(request, generator.repair(request, draft, issues, citations));
            List<ValidationIssue> repairedIssues = planValidator.validate(request, repaired, citations);
            if (repairedIssues.isEmpty()) return success(request, repaired, citations);

            // 修改后的计划仍不合格时，不把有问题的计划返回给用户，而是返回未通过的原因。
            return new TrainingPlanResult(
                    VALIDATION_FAILED, null, List.of(), repairedIssues, citations);
        } catch (Exception exception) {
            // 将模型调用或解析异常转换成统一的业务结果，避免异常直接中断接口响应。
            ValidationIssue failure = new ValidationIssue(
                    "GENERATION_FAILED", "plan", safeMessage(exception));
            return new TrainingPlanResult(FAILED, null, List.of(), List.of(failure), citations);
        }
    }

    private TrainingPlanResult success(TrainingPlanRequest request, TrainingPlan plan, List<Citation> citations) {
        return new TrainingPlanResult(SUCCESS, plan, List.of(), List.of(), citations,
                request.resolvedVocalCondition());
    }

    private TrainingPlanRequest applySafetyLimits(TrainingPlanRequest request) {
        int limit = switch (request.resolvedVocalCondition()) {
            case MILD_PAIN_OR_HOARSENESS -> 15;
            case SIGNIFICANT_DISCOMFORT -> 10;
            case NORMAL -> request.minutesPerDay();
        };
        int minutes = Math.min(request.minutesPerDay(), limit);
        return new TrainingPlanRequest(request.conversationId(), request.goal(), request.level(),
                request.durationDays(), minutes, request.currentProblems(), request.targetSong(),
                request.comfortableRange(), request.hasPainOrHoarseness(), request.outputFormat(),
                request.resolvedVocalCondition(), request.proceedDespiteDiscomfort());
    }

    private TrainingPlan applySafetyNotices(TrainingPlanRequest request, TrainingPlan plan) {
        if (plan == null || request.resolvedVocalCondition() == TrainingPlanModels.VocalCondition.NORMAL) return plan;
        java.util.LinkedHashSet<String> notices = new java.util.LinkedHashSet<>(plan.safetyNotices());
        notices.add("练习期间优先休息；出现疼痛、嘶哑加重或发声困难时立即停止。");
        if (request.resolvedVocalCondition() == TrainingPlanModels.VocalCondition.SIGNIFICANT_DISCOMFORT) {
            notices.add("当前计划仅用于最低强度恢复；症状持续或加重时，请暂停练习并酌情寻求专业医疗帮助。");
        }
        return new TrainingPlan(plan.title(), plan.goal(), plan.durationDays(), plan.minutesPerDay(),
                plan.phases(), plan.days(), List.copyOf(notices), plan.citationIds());
    }

    private String searchQuery(TrainingPlanRequest request) {
        return java.util.stream.Stream.of(
                        request.goal(), request.level(), String.join(" ", request.currentProblems()),
                        request.targetSong(), request.comfortableRange(), request.resolvedVocalCondition().name())
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
