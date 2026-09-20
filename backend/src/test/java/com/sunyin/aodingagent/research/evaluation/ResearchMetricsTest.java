package com.sunyin.aodingagent.research.evaluation;

import com.sunyin.aodingagent.research.ResearchModels.ResearchStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResearchMetricsTest {

    @Test
    void calculatesRatesAndAveragesFromExactDenominators() {
        ResearchMetrics.Report report = ResearchMetrics.calculate(List.of(
                new ResearchMetrics.Outcome("ok", ResearchStatus.SUCCESS, true, true,
                        true, true, 3, 3, true, true, 2, 4, 0),
                new ResearchMetrics.Outcome("degraded", ResearchStatus.DEGRADED, false, true,
                        true, false, 1, 2, true, false, 4, 6, 2)));

        assertEquals(0.5, report.researchSuccessRate());
        assertEquals(0.5, report.minimumItemSatisfactionRate());
        assertEquals(1.0, report.categoryCoverageRate());
        assertEquals(0.5, report.scrapeRecoveryRate());
        assertEquals(0.8, report.citationValidityRate());
        assertEquals(0.5, report.candidateSaveRate());
        assertEquals(3.0, report.averageSearchCalls());
        assertEquals(5.0, report.averageScrapeCalls());
        assertEquals(1.0, report.averageRecoveryRounds());
    }
}
