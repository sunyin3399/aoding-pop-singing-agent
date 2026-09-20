package com.sunyin.aodingagent.trainingplan.evaluation;

import com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus;

import java.util.List;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.NEEDS_CLARIFICATION;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.SAFETY_BLOCKED;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.SUCCESS;

final class TrainingPlanMetrics {

    private TrainingPlanMetrics() {
    }

    static MetricsReport calculate(List<EvaluationOutcome> outcomes) {
        int expectedSuccess = 0;
        int successful = 0;
        int correctClarificationClassification = 0;
        int expectedSafety = 0;
        int safetyBlocked = 0;
        int constraintsSatisfied = 0;
        int constraintsTotal = 0;
        int citationsValid = 0;
        int citationsTotal = 0;

        for (EvaluationOutcome outcome : outcomes) {
            if (outcome.expectedSuccess()) {
                expectedSuccess++;
                if (outcome.actualStatus() == SUCCESS) successful++;
            }
            boolean actualClarification = outcome.actualStatus() == NEEDS_CLARIFICATION;
            if (actualClarification == outcome.expectedClarification()) correctClarificationClassification++;
            if (outcome.expectedSafetyBlock()) {
                expectedSafety++;
                if (outcome.actualStatus() == SAFETY_BLOCKED) safetyBlocked++;
            }
            constraintsSatisfied += outcome.constraintsSatisfied();
            constraintsTotal += outcome.constraintsTotal();
            citationsValid += outcome.validCitations();
            citationsTotal += outcome.totalCitations();
        }

        return new MetricsReport(
                outcomes.size(),
                metric(successful, expectedSuccess),
                metric(correctClarificationClassification, outcomes.size()),
                metric(safetyBlocked, expectedSafety),
                metric(constraintsSatisfied, constraintsTotal),
                metric(citationsValid, citationsTotal)
        );
    }

    private static Metric metric(int passed, int applicable) {
        double ratio = applicable == 0 ? 1.0 : (double) passed / applicable;
        return new Metric(passed, applicable, ratio);
    }

    record EvaluationOutcome(
            String caseId,
            boolean expectedSuccess,
            TrainingPlanStatus actualStatus,
            boolean expectedClarification,
            boolean expectedSafetyBlock,
            int constraintsSatisfied,
            int constraintsTotal,
            int validCitations,
            int totalCitations
    ) {
    }

    record Metric(int passed, int applicable, double ratio) {
    }

    record MetricsReport(
            int totalCases,
            Metric successRate,
            Metric clarificationAccuracy,
            Metric safetyBlockRate,
            Metric constraintSatisfactionRate,
            Metric citationValidityRate
    ) {
    }
}
