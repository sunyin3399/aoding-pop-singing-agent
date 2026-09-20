package com.sunyin.aodingagent.research.evaluation;

import com.sunyin.aodingagent.research.ResearchModels.ResearchStatus;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class ResearchMetrics {

    static Report calculate(List<Outcome> outcomes) {
        int total = outcomes.size();
        Map<ResearchStatus, Long> statuses = new EnumMap<>(ResearchStatus.class);
        for (ResearchStatus status : ResearchStatus.values()) {
            statuses.put(status, outcomes.stream().filter(value -> value.status() == status).count());
        }
        return new Report(
                ratio(outcomes.stream().filter(value -> value.status() == ResearchStatus.SUCCESS).count(), total),
                ratio(outcomes.stream().filter(Outcome::minimumItemsSatisfied).count(), total),
                ratio(outcomes.stream().filter(Outcome::categoriesCovered).count(), total),
                ratio(outcomes.stream().filter(Outcome::scrapeRecovered).count(),
                        outcomes.stream().filter(Outcome::scrapeRecoveryApplicable).count()),
                ratio(outcomes.stream().mapToLong(Outcome::validCitations).sum(),
                        outcomes.stream().mapToLong(Outcome::totalCitations).sum()),
                ratio(outcomes.stream().filter(Outcome::candidateSaved).count(),
                        outcomes.stream().filter(Outcome::candidateApplicable).count()),
                average(outcomes.stream().mapToInt(Outcome::searchCalls).sum(), total),
                average(outcomes.stream().mapToInt(Outcome::scrapeCalls).sum(), total),
                average(outcomes.stream().mapToInt(Outcome::recoveryRounds).sum(), total),
                statuses);
    }

    private static double ratio(long value, long total) {
        return total == 0 ? 1.0 : (double) value / total;
    }

    private static double average(long value, long total) {
        return total == 0 ? 0.0 : (double) value / total;
    }

    record Outcome(String id, ResearchStatus status, boolean minimumItemsSatisfied,
                   boolean categoriesCovered, boolean scrapeRecoveryApplicable, boolean scrapeRecovered,
                   int validCitations, int totalCitations,
                   boolean candidateApplicable, boolean candidateSaved,
                   int searchCalls, int scrapeCalls, int recoveryRounds) {
    }

    record Report(double researchSuccessRate, double minimumItemSatisfactionRate,
                  double categoryCoverageRate, double scrapeRecoveryRate,
                  double citationValidityRate, double candidateSaveRate,
                  double averageSearchCalls, double averageScrapeCalls,
                  double averageRecoveryRounds, Map<ResearchStatus, Long> statusCounts) {
    }
}
