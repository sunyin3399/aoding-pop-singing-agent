package com.sunyin.aodingagent.trainingplan;

import com.sunyin.aodingagent.knowledge.Citation;

import java.util.List;

/**
 * 集中保存训练计划功能使用的数据结构。
 * <p>
 * 使用这些明确的 record 和枚举，是为了让前端、工作流、工具和大模型之间传递结构化数据，
 * 而不是依赖一段难以检查的自由文本。
 */
public final class TrainingPlanModels {

    private TrainingPlanModels() {
    }

    /** 用户在训练计划表单中提交的全部信息。 */
    public record TrainingPlanRequest(
            String conversationId,
            String goal,
            String level,
            Integer durationDays,
            Integer minutesPerDay,
            List<String> currentProblems,
            String targetSong,
            String comfortableRange,
            Boolean hasPainOrHoarseness,
            OutputFormat outputFormat,
            VocalCondition vocalCondition,
            Boolean proceedDespiteDiscomfort
    ) {
        public TrainingPlanRequest {
            currentProblems = currentProblems == null ? List.of() : List.copyOf(currentProblems);
            outputFormat = outputFormat == null ? OutputFormat.PAGE : outputFormat;
        }

        public TrainingPlanRequest(String conversationId, String goal, String level, Integer durationDays,
                                   Integer minutesPerDay, List<String> currentProblems, String targetSong,
                                   String comfortableRange, Boolean hasPainOrHoarseness, OutputFormat outputFormat) {
            this(conversationId, goal, level, durationDays, minutesPerDay, currentProblems, targetSong,
                    comfortableRange, hasPainOrHoarseness, outputFormat, null, null);
        }

        public VocalCondition resolvedVocalCondition() {
            if (vocalCondition != null) return vocalCondition;
            if (hasPainOrHoarseness == null) return null;
            return hasPainOrHoarseness ? VocalCondition.SIGNIFICANT_DISCOMFORT : VocalCondition.NORMAL;
        }
    }

    /**
     * 一次训练计划请求的统一返回结果。
     * 调用方应先查看 {@link #status()}，因为只有成功时 {@link #plan()} 才有值。
     */
    public record TrainingPlanResult(
            TrainingPlanStatus status,
            TrainingPlan plan,
            List<ClarificationQuestion> questions,
            List<ValidationIssue> issues,
            List<Citation> citations,
            VocalCondition vocalCondition
    ) {
        public TrainingPlanResult {
            questions = questions == null ? List.of() : List.copyOf(questions);
            issues = issues == null ? List.of() : List.copyOf(issues);
            citations = citations == null ? List.of() : List.copyOf(citations);
        }

        public TrainingPlanResult(TrainingPlanStatus status, TrainingPlan plan,
                                  List<ClarificationQuestion> questions, List<ValidationIssue> issues,
                                  List<Citation> citations) {
            this(status, plan, questions, issues, citations, null);
        }

        public static TrainingPlanResult terminal(
                TrainingPlanStatus status,
                List<ClarificationQuestion> questions,
                List<ValidationIssue> issues
        ) {
            return new TrainingPlanResult(status, null, questions, issues, List.of(), null);
        }
    }

    /** 已生成的完整计划，包含阶段安排、每日动作、安全提醒和引用编号。 */
    public record TrainingPlan(
            String title,
            String goal,
            int durationDays,
            int minutesPerDay,
            List<TrainingPhase> phases,
            List<TrainingDay> days,
            List<String> safetyNotices,
            List<String> citationIds
    ) {
        public TrainingPlan {
            phases = phases == null ? List.of() : List.copyOf(phases);
            days = days == null ? List.of() : List.copyOf(days);
            safetyNotices = safetyNotices == null ? List.of() : List.copyOf(safetyNotices);
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }
    }

    /** 一个连续训练阶段，例如基础适应阶段或强化阶段。 */
    public record TrainingPhase(
            String name,
            String goal,
            int startDay,
            int endDay,
            List<String> acceptanceCriteria,
            List<Exercise> exercises
    ) {
        public TrainingPhase {
            acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
            exercises = exercises == null ? List.of() : List.copyOf(exercises);
        }

        public TrainingPhase(String name, String goal, int startDay, int endDay, List<String> acceptanceCriteria) {
            this(name, goal, startDay, endDay, acceptanceCriteria, List.of());
        }
    }

    /** 某一天的训练目标、动作列表和完成后的检查点。 */
    public record TrainingDay(
            int day,
            String goal,
            List<Exercise> exercises,
            String checkpoint
    ) {
        public TrainingDay {
            exercises = exercises == null ? List.of() : List.copyOf(exercises);
        }
    }

    /** 一个可以实际执行的训练动作，包括时长、组数、操作步骤和停止条件。 */
    public record Exercise(
            ExerciseCategory category,
            String name,
            int minutes,
            int sets,
            List<String> instructions,
            List<String> stopConditions
    ) {
        public Exercise {
            instructions = instructions == null ? List.of() : List.copyOf(instructions);
            stopConditions = stopConditions == null ? List.of() : List.copyOf(stopConditions);
        }
    }

    /** 当表单信息不足时，告诉前端需要补充哪个字段以及如何询问用户。 */
    public record ClarificationQuestion(String field, String question) {
    }

    /** 描述计划或请求中的一个具体问题，path 用来定位出错字段。 */
    public record ValidationIssue(String code, String path, String message) {
    }

    /** 训练计划工作流可能返回的处理状态。 */
    public enum TrainingPlanStatus {
        /** 用户信息不足，需要补充后才能继续。 */
        NEEDS_CLARIFICATION,
        /** 计划已经生成并通过全部规则检查。 */
        SUCCESS,
        /** 计划修改一次后仍未通过规则检查。 */
        VALIDATION_FAILED,
        /** 调用模型、解析结果或其他生成过程发生异常。 */
        FAILED,
        /** 用户存在疼痛或持续嘶哑，出于安全原因不生成训练计划。 */
        SAFETY_BLOCKED
    }

    /** 每天训练动作在完整练习流程中的位置。 */
    public enum ExerciseCategory {
        /** 正式训练前的热身动作。 */
        WARM_UP,
        /** 针对用户目标的主要训练动作。 */
        MAIN,
        /** 训练结束后的放松动作。 */
        COOL_DOWN
    }

    /** 用户希望获得的计划输出形式。 */
    public enum OutputFormat {
        /** 只在页面展示。 */
        PAGE,
        /** 页面展示，同时允许导出 PDF。 */
        PAGE_AND_PDF
    }

    public enum VocalCondition {
        NORMAL,
        MILD_PAIN_OR_HOARSENESS,
        SIGNIFICANT_DISCOMFORT
    }
}
