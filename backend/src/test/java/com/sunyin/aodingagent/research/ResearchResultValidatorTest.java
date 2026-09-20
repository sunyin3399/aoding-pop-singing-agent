package com.sunyin.aodingagent.research;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchResultValidatorTest {

    @Test
    void reportsMinimumAndCategoryGapsUsingOnlySuccessfullyScrapedSources() {
        ResearchModels.ResearchTaskContract contract = new ResearchModels.ResearchTaskContract(
                ResearchModels.ResearchTaskType.RESOURCE_RESEARCH, "练声",
                ResearchModels.DeliverableType.RESOURCE_LIST, 2, 2,
                List.of(new ResearchModels.ResearchCategory("热声", 1),
                        new ResearchModels.ResearchCategory("音准", 1)),
                List.of("name", "url"), true, 6, 12);
        ResearchSession session = new ResearchSession("练声", contract,
                ResearchModels.ResearchBudget.defaults(), Clock.systemUTC());
        session.addSearchResult(new WebSearchResult.SearchItem(
                "热声 A", "https://example.com/a", "摘要"), "热声", "热声查询");
        session.recordScrapeSuccess("https://example.com/a", "热声 A", "完整正文");
        session.addSearchResult(new WebSearchResult.SearchItem(
                "音准 B", "https://example.com/b", "摘要"), "音准", "音准查询");
        session.recordScrapeFailure("https://example.com/b", ResearchModels.ScrapeErrorCode.HTTP_FORBIDDEN);

        List<ResearchModels.ValidationIssue> issues = new ResearchResultValidator().validate(contract, session);

        assertThat(issues).extracting(ResearchModels.ValidationIssue::code)
                .containsExactlyInAnyOrder("MINIMUM_ITEMS_NOT_MET", "CATEGORY_NOT_COVERED");
    }
}
