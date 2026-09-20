package com.sunyin.aodingagent.trainingplan;

import com.sunyin.aodingagent.knowledge.Citation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.Exercise;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ExerciseCategory.COOL_DOWN;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ExerciseCategory.MAIN;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ExerciseCategory.WARM_UP;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.OutputFormat.PAGE;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingDay;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPhase;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlan;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingPlanValidatorTest {

    private final TrainingPlanValidator validator = new TrainingPlanValidator();

    @Test
    void acceptsACompletePlan() {
        assertTrue(validator.validate(request(), validPlan(), citations()).isEmpty());
    }

    @Test
    void rejectsMissingAndDuplicateDays() {
        TrainingPlan plan = copy(validPlan(), List.of(validDay(1), validDay(1)), validPlan().phases(), List.of("doc-1"));

        assertCodes(plan, "DUPLICATE_DAY", "MISSING_DAY");
    }

    @Test
    void rejectsDailyDurationOutsideTheAllowedBand() {
        TrainingDay tooShort = new TrainingDay(1, "短", List.of(
                exercise(WARM_UP, 2), exercise(MAIN, 4), exercise(COOL_DOWN, 2)), "复测");
        TrainingDay tooLong = new TrainingDay(2, "长", List.of(
                exercise(WARM_UP, 5), exercise(MAIN, 15), exercise(COOL_DOWN, 5)), "复测");
        TrainingPlan plan = copy(validPlan(), List.of(tooShort, tooLong), validPlan().phases(), List.of("doc-1"));

        assertCodes(plan, "DAILY_DURATION_TOO_SHORT", "DAILY_DURATION_EXCEEDED");
    }

    @Test
    void requiresWarmUpMainAndCoolDownEveryDay() {
        TrainingDay missingCoolDown = new TrainingDay(1, "目标",
                List.of(exercise(WARM_UP, 5), exercise(MAIN, 10)), "复测");
        TrainingPlan plan = copy(validPlan(), List.of(missingCoolDown, validDay(2)), validPlan().phases(), List.of("doc-1"));

        assertCodes(plan, "MISSING_EXERCISE_CATEGORY");
    }

    @Test
    void requiresPhasesToCoverThePlanWithoutGapsOrOverlap() {
        List<TrainingPhase> phases = List.of(
                new TrainingPhase("阶段一", "目标", 1, 1, List.of("完成")),
                new TrainingPhase("阶段二", "目标", 3, 3, List.of("完成")));

        assertCodes(copy(validPlan(), validPlan().days(), phases, List.of("doc-1")), "PHASE_COVERAGE");
    }

    @Test
    void requiresPhaseAcceptanceCriteria() {
        List<TrainingPhase> phases = List.of(new TrainingPhase("阶段", "目标", 1, 2, List.of()));

        assertCodes(copy(validPlan(), validPlan().days(), phases, List.of("doc-1")), "MISSING_ACCEPTANCE_CRITERIA");
    }

    @Test
    void rejectsIncompleteExerciseInstructionsAndStopConditions() {
        Exercise incomplete = new Exercise(MAIN, "", 0, 0, List.of(), List.of());
        TrainingDay day = new TrainingDay(1, "目标",
                List.of(exercise(WARM_UP, 5), incomplete, exercise(COOL_DOWN, 3)), "复测");

        assertCodes(copy(validPlan(), List.of(day, validDay(2)), validPlan().phases(), List.of("doc-1")),
                "INVALID_EXERCISE_NAME", "INVALID_EXERCISE_MINUTES", "INVALID_EXERCISE_SETS",
                "INSUFFICIENT_EXERCISE_INSTRUCTIONS", "MISSING_STOP_CONDITIONS");
    }

    @Test
    void requiresThreeConcreteInstructionsPerExercise() {
        Exercise sparse = new Exercise(MAIN, "长音", 10, 2,
                List.of("保持轻声"), List.of("疼痛时停止"));
        TrainingDay day = new TrainingDay(1, "目标",
                List.of(exercise(WARM_UP, 5), sparse, exercise(COOL_DOWN, 3)), "复测");

        assertCodes(copy(validPlan(), List.of(day, validDay(2)), validPlan().phases(), List.of("doc-1")),
                "INSUFFICIENT_EXERCISE_INSTRUCTIONS");
    }

    @Test
    void rejectsCitationOutsideThisRetrieval() {
        assertCodes(copy(validPlan(), validPlan().days(), validPlan().phases(), List.of("invented")), "INVALID_CITATION");
    }

    @Test
    void exposesConstraintCountsForEvaluation() {
        TrainingPlanValidator.ConstraintScore score = validator.score(request(), validPlan(), citations());

        assertTrue(score.total() > 0);
        assertEquals(score.total(), score.satisfied());
    }

    private void assertCodes(TrainingPlan plan, String... expectedCodes) {
        List<String> actual = validator.validate(request(), plan, citations()).stream()
                .map(TrainingPlanModels.ValidationIssue::code).toList();
        for (String expected : expectedCodes) {
            assertTrue(actual.contains(expected), () -> "缺少校验错误 " + expected + "，实际为 " + actual);
        }
    }

    private TrainingPlanRequest request() {
        return new TrainingPlanRequest("c1", "改善换声", "初学者", 2, 20,
                List.of("高音挤卡"), null, null, false, PAGE);
    }

    private TrainingPlan validPlan() {
        return new TrainingPlan("两日计划", "改善换声", 2, 20,
                List.of(new TrainingPhase("基础", "建立协调", 1, 2, List.of("轻声完成练习"))),
                List.of(validDay(1), validDay(2)),
                List.of("出现疼痛立即停止"), List.of("doc-1"));
    }

    private TrainingDay validDay(int day) {
        return new TrainingDay(day, "协调发声", List.of(
                exercise(WARM_UP, 5), exercise(MAIN, 10), exercise(COOL_DOWN, 3)), "录制同音高复测");
    }

    private Exercise exercise(TrainingPlanModels.ExerciseCategory category, int minutes) {
        return new Exercise(category, category.name(), minutes, 2,
                List.of("站立或坐直，肩膀自然放松", "保持轻声并均匀呼吸，按计划完成每组",
                        "留意腰腹自然扩张，避免耸肩和推挤"),
                List.of("疼痛或明显嘶哑时停止"));
    }

    private List<Citation> citations() {
        return List.of(new Citation("doc-1", Citation.CitationType.INTERNAL_KNOWLEDGE,
                "换声区", "/api/ai/knowledge/documents/doc-1", "摘要", 0.9));
    }

    private TrainingPlan copy(TrainingPlan source, List<TrainingDay> days,
                              List<TrainingPhase> phases, List<String> citationIds) {
        return new TrainingPlan(source.title(), source.goal(), source.durationDays(), source.minutesPerDay(),
                new ArrayList<>(phases), new ArrayList<>(days), source.safetyNotices(), citationIds);
    }
}
