package com.sunyin.aodingagent.trainingplan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.OutputFormat.PAGE;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanRequest;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.NEEDS_CLARIFICATION;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.SAFETY_BLOCKED;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.VocalCondition.MILD_PAIN_OR_HOARSENESS;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.VocalCondition.SIGNIFICANT_DISCOMFORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingPlanInputValidatorTest {

    private final TrainingPlanInputValidator validator = new TrainingPlanInputValidator();

    @Test
    void acceptsACompleteSafeFormWithoutClarification() {
        assertNull(validator.classify(validRequest(false)));
    }

    @Test
    void returnsEveryMissingFieldInOneClarificationResult() {
        TrainingPlanModels.TrainingPlanResult result = validator.classify(new TrainingPlanRequest(
                "c1", " ", null, null, null, List.of(), null, null, null, PAGE
        ));

        assertEquals(NEEDS_CLARIFICATION, result.status());
        assertEquals(Set.of("goal", "level", "durationDays", "minutesPerDay", "currentProblems", "vocalCondition"),
                result.issues().stream().map(TrainingPlanModels.ValidationIssue::path).collect(Collectors.toSet()));
        assertTrue(result.questions().size() <= 3);
    }

    @Test
    void rejectsDurationAndDailyMinutesOutsideSupportedRanges() {
        TrainingPlanRequest request = new TrainingPlanRequest(
                "c1", "改善换声", "初学者", 31, 4, List.of("换声不稳定"), null, null, false, PAGE
        );

        TrainingPlanModels.TrainingPlanResult result = validator.classify(request);

        assertEquals(NEEDS_CLARIFICATION, result.status());
        assertEquals(Set.of("durationDays", "minutesPerDay"),
                result.issues().stream().map(TrainingPlanModels.ValidationIssue::path).collect(Collectors.toSet()));
    }

    @Test
    void blocksTrainingWhenTheUserReportsPainOrHoarseness() {
        TrainingPlanModels.TrainingPlanResult result = validator.classify(validRequest(true));

        assertEquals(SAFETY_BLOCKED, result.status());
        assertNull(result.plan());
        assertTrue(result.issues().stream().anyMatch(issue -> "VOCAL_HEALTH_RISK".equals(issue.code())));
    }

    @Test
    void allowsMildDiscomfortAndRequiresConfirmationForSignificantDiscomfort() {
        assertNull(validator.classify(request(MILD_PAIN_OR_HOARSENESS, false)));
        assertEquals(SAFETY_BLOCKED, validator.classify(request(SIGNIFICANT_DISCOMFORT, false)).status());
        assertNull(validator.classify(request(SIGNIFICANT_DISCOMFORT, true)));
    }

    private TrainingPlanRequest validRequest(boolean risk) {
        return new TrainingPlanRequest(
                "c1", "平稳通过换声区", "初学者", 7, 20,
                List.of("高音挤卡"), "后来", "C3-G4", risk, PAGE
        );
    }


    private TrainingPlanRequest request(TrainingPlanModels.VocalCondition condition, boolean proceed) {
        return new TrainingPlanRequest("c1", "平稳通过换声区", "初学者", 7, 30,
                List.of("高音挤卡"), null, null, null, PAGE, condition, proceed);
    }
}
