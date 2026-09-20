package com.sunyin.aodingagent.research;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.sunyin.aodingagent.research.ResearchModels.ScrapeErrorCode.TIMEOUT;
import static com.sunyin.aodingagent.research.ResearchModels.ScrapeErrorCode.CHALLENGE_PAGE;
import static com.sunyin.aodingagent.research.ResearchModels.ScrapeStatus.FAILED;
import static org.assertj.core.api.Assertions.assertThat;

class ResearchSessionTest {

    @Test
    void enforcesDefaultCallAndRecoveryLimitsWhileKeepingFailedSources() {
        ResearchModels.ResearchBudget budget = ResearchModels.ResearchBudget.defaults();
        ResearchSession session = new ResearchSession("练声资源", contract(), budget,
                Clock.fixed(Instant.parse("2026-08-11T10:00:00Z"), ZoneOffset.UTC));

        for (int index = 0; index < 6; index++) assertThat(session.tryRecordSearch("query-" + index)).isTrue();
        assertThat(session.tryRecordSearch("query-over-limit")).isFalse();

        session.addSearchResult(new WebSearchResult.SearchItem(
                "资源 A", "https://example.com/a?utm_source=test", "摘要"), "热声", "query-0");
        session.recordScrapeFailure("https://example.com/a", TIMEOUT);
        session.recordScrapeFailure("https://example.com/a", TIMEOUT);

        assertThat(session.sources()).singleElement()
                .satisfies(source -> {
                    assertThat(source.scrapeStatus()).isEqualTo(FAILED);
                    assertThat(source.scrapeAttempts()).isEqualTo(2);
                    assertThat(source.scrapeErrorCode()).isEqualTo(TIMEOUT);
                });
        assertThat(session.tryStartDeterministicRemediation()).isTrue();
        assertThat(session.tryStartDeterministicRemediation()).isFalse();
        assertThat(session.tryStartRecoveryPlanner()).isTrue();
        assertThat(session.tryStartRecoveryPlanner()).isFalse();
    }

    @Test
    void stopsScrapingAHostAfterItReturnsAChallengePage() {
        ResearchSession session = new ResearchSession("歌曲推荐", contract(), ResearchModels.ResearchBudget.defaults(),
                Clock.fixed(Instant.parse("2026-08-11T10:00:00Z"), ZoneOffset.UTC));
        session.addSearchResult(new WebSearchResult.SearchItem(
                "页面 A", "https://zhidao.baidu.com/a", "摘要"), "热声", "query");
        session.addSearchResult(new WebSearchResult.SearchItem(
                "页面 B", "https://zhidao.baidu.com/b", "摘要"), "热声", "query");

        assertThat(session.tryRecordScrape("https://zhidao.baidu.com/a")).isTrue();
        session.recordScrapeFailure("https://zhidao.baidu.com/a", CHALLENGE_PAGE);

        assertThat(session.isDomainBlocked("https://zhidao.baidu.com/b")).isTrue();
        assertThat(session.tryRecordScrape("https://zhidao.baidu.com/b")).isFalse();
    }

    private ResearchModels.ResearchTaskContract contract() {
        return new ResearchModels.ResearchTaskContract(
                ResearchModels.ResearchTaskType.RESOURCE_RESEARCH,
                "练声资源",
                ResearchModels.DeliverableType.RESOURCE_LIST,
                5,
                5,
                List.of(new ResearchModels.ResearchCategory("热声", 2)),
                List.of("name", "url", "purpose", "usageAdvice"),
                true,
                6,
                12);
    }
}
