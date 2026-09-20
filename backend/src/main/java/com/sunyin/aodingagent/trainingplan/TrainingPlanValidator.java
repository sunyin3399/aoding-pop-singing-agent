package com.sunyin.aodingagent.trainingplan;

import com.sunyin.aodingagent.knowledge.Citation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.Exercise;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ExerciseCategory;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingDay;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPhase;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlan;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanRequest;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ValidationIssue;

/**
 * 检查大模型生成的训练计划是否真的满足用户要求，可以把它理解成计划的“质检员”。
 * <p>
 * 大模型写出的内容看起来可能很合理，但仍可能漏掉某一天、超出每日训练时间、缺少热身或
 * 放松、没有停止条件，甚至引用本次没有检索到的资料。这个类不用大模型判断，而是用普通
 * Java 规则逐项检查，让相同的问题每次都得到相同的判断结果。
 */
@org.springframework.stereotype.Component
public class TrainingPlanValidator {

    /**
     * 对计划进行完整检查。
     *
     * @param request 用户原始要求，用来核对周期和每日时长
     * @param plan 大模型生成或修改后的计划
     * @param allowedCitations 本次从内部知识库实际查到、允许计划使用的引用
     * @return 发现的所有问题；列表为空表示计划通过检查
     */
    public List<ValidationIssue> validate(
            TrainingPlanRequest request,
            TrainingPlan plan,
            List<Citation> allowedCitations
    ) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (plan == null) {
            return List.of(issue("MISSING_PLAN", "plan", "训练计划不能为空"));
        }
        validateDeclaredConstraints(request, plan, issues);
        if (plan.days().isEmpty()) validatePhaseExercises(request, plan.phases(), issues);
        else validateDays(request, plan.days(), issues);
        validatePhases(request, plan.phases(), issues);
        validateVocalSafety(request, plan, issues);
        validateCitations(plan.citationIds(), allowedCitations, issues);
        return List.copyOf(issues);
    }

    /**
     * 计算计划满足了多少项可检查规则，主要供离线评测统计使用。
     *
     * @return 已满足规则数和规则总数
     */
    public ConstraintScore score(
            TrainingPlanRequest request,
            TrainingPlan plan,
            List<Citation> allowedCitations
    ) {
        int total = countConstraints(plan);
        int failed = validate(request, plan, allowedCitations).size();
        return new ConstraintScore(Math.max(0, total - failed), total);
    }

    private void validateDeclaredConstraints(
            TrainingPlanRequest request,
            TrainingPlan plan,
            List<ValidationIssue> issues
    ) {
        if (request.durationDays() == null || plan.durationDays() != request.durationDays()) {
            issues.add(issue("DURATION_MISMATCH", "durationDays", "计划周期必须与请求一致"));
        }
        if (request.minutesPerDay() == null || plan.minutesPerDay() != request.minutesPerDay()) {
            issues.add(issue("MINUTES_MISMATCH", "minutesPerDay", "每日时长必须与请求一致"));
        }
    }

    private void validateDays(
            TrainingPlanRequest request,
            List<TrainingDay> days,
            List<ValidationIssue> issues
    ) {
        // 同时记录已经出现的天数，用来发现重复日期和整个周期中缺失的日期。
        Set<Integer> seenDays = new HashSet<>();
        for (TrainingDay day : days) {
            if (!seenDays.add(day.day())) {
                issues.add(issue("DUPLICATE_DAY", "days[" + day.day() + "]", "训练日不能重复"));
            }
            validateDay(request, day, issues);
        }
        int expectedDays = request.durationDays() == null ? 0 : request.durationDays();
        for (int day = 1; day <= expectedDays; day++) {
            if (!seenDays.contains(day)) {
                issues.add(issue("MISSING_DAY", "days[" + day + "]", "缺少第 " + day + " 天训练"));
            }
        }
        if (days.size() != expectedDays && days.stream().map(TrainingDay::day).distinct().count() == expectedDays) {
            issues.add(issue("DAY_COUNT_MISMATCH", "days", "训练日数量必须与计划周期一致"));
        }
    }

    private void validateDay(
            TrainingPlanRequest request,
            TrainingDay day,
            List<ValidationIssue> issues
    ) {
        int totalMinutes = day.exercises().stream().mapToInt(Exercise::minutes).sum();
        int limit = request.minutesPerDay() == null ? 0 : request.minutesPerDay();
        if (totalMinutes > limit) {
            issues.add(issue("DAILY_DURATION_EXCEEDED", "days[" + day.day() + "].exercises",
                    "当日训练时长超过限制"));
        }
        if (totalMinutes < Math.ceil(limit * 0.6)) {
            issues.add(issue("DAILY_DURATION_TOO_SHORT", "days[" + day.day() + "].exercises",
                    "当日训练时长不足目标的 60%"));
        }

        // 每天必须同时包含热身、主要训练和放松，不能只给用户一组高强度动作。
        EnumSet<ExerciseCategory> categories = EnumSet.noneOf(ExerciseCategory.class);
        for (int index = 0; index < day.exercises().size(); index++) {
            Exercise exercise = day.exercises().get(index);
            if (exercise.category() != null) categories.add(exercise.category());
            validateExercise(String.valueOf(day.day()), index, exercise, issues);
        }
        for (ExerciseCategory required : ExerciseCategory.values()) {
            if (!categories.contains(required)) {
                issues.add(issue("MISSING_EXERCISE_CATEGORY", "days[" + day.day() + "].exercises",
                        "缺少训练类别 " + required));
            }
        }
    }

    private void validateExercise(
            String day,
            int index,
            Exercise exercise,
            List<ValidationIssue> issues
    ) {
        String path = "days[" + day + "].exercises[" + index + "]";
        if (blank(exercise.name())) issues.add(issue("INVALID_EXERCISE_NAME", path + ".name", "训练名称不能为空"));
        if (exercise.minutes() <= 0) issues.add(issue("INVALID_EXERCISE_MINUTES", path + ".minutes", "训练分钟数必须为正数"));
        if (exercise.sets() <= 0) issues.add(issue("INVALID_EXERCISE_SETS", path + ".sets", "训练组数必须为正数"));
        long instructionCount = exercise.instructions().stream().filter(instruction -> !blank(instruction)).count();
        if (instructionCount < 3) {
            issues.add(issue("INSUFFICIENT_EXERCISE_INSTRUCTIONS", path + ".instructions",
                    "每项训练至少需要 3 条具体操作说明"));
        }
        if (exercise.stopConditions().isEmpty() || exercise.stopConditions().stream().allMatch(this::blank)) {
            issues.add(issue("MISSING_STOP_CONDITIONS", path + ".stopConditions", "训练必须包含停止条件"));
        }
    }

    private void validatePhases(
            TrainingPlanRequest request,
            List<TrainingPhase> phases,
            List<ValidationIssue> issues
    ) {
        // 排序后逐段检查，确保训练阶段从第 1 天开始，连续且不重叠地覆盖整个周期。
        List<TrainingPhase> sorted = phases.stream()
                .sorted(Comparator.comparingInt(TrainingPhase::startDay))
                .toList();
        int expectedStart = 1;
        boolean coverageValid = !sorted.isEmpty();
        for (int index = 0; index < sorted.size(); index++) {
            TrainingPhase phase = sorted.get(index);
            if (phase.startDay() != expectedStart || phase.endDay() < phase.startDay()) coverageValid = false;
            expectedStart = phase.endDay() + 1;
            if (phase.acceptanceCriteria().isEmpty()
                    || phase.acceptanceCriteria().stream().allMatch(this::blank)) {
                issues.add(issue("MISSING_ACCEPTANCE_CRITERIA", "phases[" + index + "].acceptanceCriteria",
                        "每个阶段必须包含验收标准"));
            }
        }
        int duration = request.durationDays() == null ? 0 : request.durationDays();
        if (expectedStart != duration + 1) coverageValid = false;
        if (!coverageValid) {
            issues.add(issue("PHASE_COVERAGE", "phases", "阶段必须连续且无重叠地覆盖完整周期"));
        }
    }

    private void validateCitations(
            List<String> citationIds,
            List<Citation> allowedCitations,
            List<ValidationIssue> issues
    ) {
        // 只接受本次知识检索返回的 ID，防止模型凭空写出一个看似真实的引用编号。
        Set<String> allowedIds = allowedCitations == null ? Set.of() : allowedCitations.stream()
                .map(Citation::id).collect(java.util.stream.Collectors.toSet());
        for (int index = 0; index < citationIds.size(); index++) {
            if (!allowedIds.contains(citationIds.get(index))) {
                issues.add(issue("INVALID_CITATION", "citationIds[" + index + "]", "引用不属于本次检索结果"));
            }
        }
    }

    private void validatePhaseExercises(TrainingPlanRequest request, List<TrainingPhase> phases,
                                        List<ValidationIssue> issues) {
        int limit = request.minutesPerDay() == null ? 0 : request.minutesPerDay();
        for (int index = 0; index < phases.size(); index++) {
            TrainingPhase phase = phases.get(index);
            int total = phase.exercises().stream().mapToInt(Exercise::minutes).sum();
            String path = "phases[" + index + "].exercises";
            if (total > limit) issues.add(issue("PHASE_DURATION_EXCEEDED", path, "阶段每日训练时长超过限制"));
            if (total < Math.ceil(limit * 0.6)) issues.add(issue("PHASE_DURATION_TOO_SHORT", path, "阶段每日训练时长不足目标的60%"));
            EnumSet<ExerciseCategory> categories = EnumSet.noneOf(ExerciseCategory.class);
            for (int exerciseIndex = 0; exerciseIndex < phase.exercises().size(); exerciseIndex++) {
                Exercise exercise = phase.exercises().get(exerciseIndex);
                if (exercise.category() != null) categories.add(exercise.category());
                validateExercise("phase-" + index, exerciseIndex, exercise, issues);
            }
            for (ExerciseCategory required : ExerciseCategory.values()) {
                if (!categories.contains(required)) issues.add(issue("MISSING_EXERCISE_CATEGORY", path,
                        "阶段缺少训练类别 " + required));
            }
        }
    }

    private void validateVocalSafety(TrainingPlanRequest request, TrainingPlan plan, List<ValidationIssue> issues) {
        if (request.resolvedVocalCondition() == TrainingPlanModels.VocalCondition.NORMAL) return;
        String notices = String.join(" ", plan.safetyNotices());
        if (!notices.contains("休息") || !(notices.contains("停止") || notices.contains("暂停"))) {
            issues.add(issue("MISSING_VOCAL_SAFETY_NOTICE", "safetyNotices",
                    "嗓音不适计划必须明确休息和停止练习条件"));
        }
        if (request.resolvedVocalCondition() != TrainingPlanModels.VocalCondition.SIGNIFICANT_DISCOMFORT) return;
        List<Exercise> exercises = plan.days().stream().flatMap(day -> day.exercises().stream()).toList();
        if (exercises.isEmpty()) exercises = plan.phases().stream().flatMap(phase -> phase.exercises().stream()).toList();
        for (Exercise exercise : exercises) {
                if (exercise.name().matches(".*(高音|强声|冲刺|极限|大音量).*")) {
                    issues.add(issue("UNSAFE_EXERCISE_FOR_DISCOMFORT", "exercises",
                            "明显不适时只能生成最低强度恢复练习"));
                }
        }
    }

    private int countConstraints(TrainingPlan plan) {
        if (plan == null) return 1;
        int exerciseCount = plan.days().stream().mapToInt(day -> day.exercises().size()).sum();
        return 2
                + plan.durationDays()
                + plan.days().size() * 5
                + plan.phases().size()
                + 1
                + exerciseCount * 5
                + plan.citationIds().size();
    }

    private ValidationIssue issue(String code, String path, String message) {
        return new ValidationIssue(code, path, message);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 表示计划已满足的规则数量和本次一共检查的规则数量。 */
    public record ConstraintScore(int satisfied, int total) {
    }
}
