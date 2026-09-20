package com.sunyin.aodingagent.trainingplan.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.NEEDS_CLARIFICATION;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.SAFETY_BLOCKED;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.SUCCESS;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.VALIDATION_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TrainingPlanMetricsTest {

    @Test
    void calculatesTheFiveMetricsFromLiteralOutcomes() {
        List<TrainingPlanMetrics.EvaluationOutcome> outcomes = List.of(
                new TrainingPlanMetrics.EvaluationOutcome("success", true, SUCCESS, false, false, 8, 10, 1, 1),
                new TrainingPlanMetrics.EvaluationOutcome("invalid", true, VALIDATION_FAILED, false, false, 6, 10, 0, 1),
                new TrainingPlanMetrics.EvaluationOutcome("clarify", false, NEEDS_CLARIFICATION, true, false, 0, 0, 0, 0),
                new TrainingPlanMetrics.EvaluationOutcome("safe", false, SAFETY_BLOCKED, false, true, 0, 0, 0, 0)
        );

        TrainingPlanMetrics.MetricsReport report = TrainingPlanMetrics.calculate(outcomes);

        assertEquals(0.5, report.successRate().ratio());
        assertEquals(1.0, report.clarificationAccuracy().ratio());
        assertEquals(1.0, report.safetyBlockRate().ratio());
        assertEquals(0.7, report.constraintSatisfactionRate().ratio());
        assertEquals(0.5, report.citationValidityRate().ratio());
        assertEquals(4, report.totalCases());
    }

    @Test
    void usesOneForMetricsWithNoApplicableCasesAndRecordsZeroDenominator() {
        TrainingPlanMetrics.MetricsReport report = TrainingPlanMetrics.calculate(List.of());

        assertEquals(1.0, report.successRate().ratio());
        assertEquals(0, report.successRate().applicable());
        assertEquals(1.0, report.safetyBlockRate().ratio());
        assertEquals(0, report.safetyBlockRate().applicable());
    }
}
