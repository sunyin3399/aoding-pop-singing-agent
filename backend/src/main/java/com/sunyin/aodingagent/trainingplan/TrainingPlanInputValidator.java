package com.sunyin.aodingagent.trainingplan;

import java.util.ArrayList;
import java.util.List;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ClarificationQuestion;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanRequest;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanResult;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.NEEDS_CLARIFICATION;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.SAFETY_BLOCKED;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ValidationIssue;

/**
 * 在生成训练计划之前检查用户输入，相当于训练计划流程的“第一道门”。
 * <p>
 * 这个类解决两个问题：一是信息不完整时，大模型只能猜测用户需求；二是用户已经出现
 * 疼痛或持续嘶哑时，继续生成强化训练可能带来风险。因此它会先收集缺失字段，并在必要时
 * 直接阻止后续模型调用。
 */
@org.springframework.stereotype.Component
public class TrainingPlanInputValidator {

    private static final int MAX_QUESTIONS = 3;

    /**
     * 判断请求可以继续生成计划，还是应该提前结束。
     *
     * @param request 用户填写的训练计划表单
     * @return 信息缺失或存在嗓音风险时返回对应结果；返回 {@code null} 表示可以继续生成计划
     */
    public TrainingPlanResult classify(TrainingPlanRequest request) {
        List<ValidationIssue> issues = validateRequest(request);
        if (!issues.isEmpty()) {
            List<ClarificationQuestion> questions = issues.stream()
                    .limit(MAX_QUESTIONS)
                    .map(issue -> new ClarificationQuestion(issue.path(), questionFor(issue.path())))
                    .toList();
            return TrainingPlanResult.terminal(NEEDS_CLARIFICATION, questions, issues);
        }
        if (request.resolvedVocalCondition() == TrainingPlanModels.VocalCondition.SIGNIFICANT_DISCOMFORT
                && !Boolean.TRUE.equals(request.proceedDespiteDiscomfort())) {
            // 安全判断放在大模型之前，不能让模型自行决定是否忽略疼痛或嘶哑风险。
            ValidationIssue risk = new ValidationIssue(
                    "VOCAL_HEALTH_RISK",
                    "vocalCondition",
                    "当前嗓音较为不适，建议优先休息并酌情就医；确认仍要继续后，只生成最低强度恢复计划。"
            );
            return TrainingPlanResult.terminal(SAFETY_BLOCKED, List.of(), List.of(risk));
        }
        return null;
    }

    private List<ValidationIssue> validateRequest(TrainingPlanRequest request) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (request == null) {
            issues.add(new ValidationIssue("REQUIRED", "request", "请求不能为空"));
            return issues;
        }
        requiredText(issues, "goal", request.goal());
        requiredText(issues, "level", request.level());
        requiredRange(issues, "durationDays", request.durationDays(), 1, 30);
        requiredRange(issues, "minutesPerDay", request.minutesPerDay(), 5, 120);
        if (request.currentProblems().isEmpty()
                || request.currentProblems().stream().allMatch(this::blank)) {
            issues.add(new ValidationIssue("REQUIRED", "currentProblems", "请至少填写一个当前问题"));
        }
        if (request.resolvedVocalCondition() == null) {
            issues.add(new ValidationIssue("REQUIRED", "vocalCondition", "请选择当前嗓音状态"));
        }
        return List.copyOf(issues);
    }

    private void requiredText(List<ValidationIssue> issues, String field, String value) {
        if (blank(value)) issues.add(new ValidationIssue("REQUIRED", field, field + " 不能为空"));
    }

    private void requiredRange(List<ValidationIssue> issues, String field, Integer value, int min, int max) {
        if (value == null) {
            issues.add(new ValidationIssue("REQUIRED", field, field + " 不能为空"));
        } else if (value < min || value > max) {
            issues.add(new ValidationIssue("OUT_OF_RANGE", field, field + " 必须在 " + min + " 到 " + max + " 之间"));
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String questionFor(String field) {
        return switch (field) {
            case "goal" -> "你的主要训练目标是什么？";
            case "level" -> "请选择当前演唱水平。";
            case "durationDays" -> "请选择 1～30 天的训练周期。";
            case "minutesPerDay" -> "请选择每天 5～120 分钟的训练时长。";
            case "currentProblems" -> "请至少选择一个当前遇到的问题。";
            case "vocalCondition" -> "请选择当前嗓音状态。";
            default -> "请补充或修正 " + field + "。";
        };
    }
}
