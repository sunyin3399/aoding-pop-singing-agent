package com.sunyin.aodingagent.research;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchWorkflowTest {

    @Test
    void performsOneDeterministicRemediationAndOneRecoveryPlanBeforeSuccess() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.searchResults.put("恢复热声", WebSearchResult.success("恢复热声", List.of(
                item("热声 A", "https://example.com/a"), item("热声 B", "https://example.com/b"))));
        AtomicInteger recoveryCalls = new AtomicInteger();
        ResearchRecoveryPlanner recoveryPlanner = (contract, session, issues) -> {
            recoveryCalls.incrementAndGet();
            return new ResearchModels.RecoveryPlan(List.of(
                    new ResearchModels.RecoveryQuery("热声", "恢复热声")));
        };
        ResearchWorkflow workflow = new ResearchWorkflow(gateway, new ResearchResultValidator(),
                recoveryPlanner, ResearchModels.ResearchBudget.defaults(), Clock.systemUTC());

        ResearchModels.ResearchResult result = workflow.research("练声资源", contract(2));

        assertThat(result.status()).isEqualTo(ResearchModels.ResearchStatus.SUCCESS);
        assertThat(result.recoveryRounds()).isEqualTo(2);
        assertThat(recoveryCalls).hasValue(1);
        assertThat(result.sources()).filteredOn(source ->
                source.scrapeStatus() == ResearchModels.ScrapeStatus.SCRAPED).hasSize(2);
    }

    @Test
    void stopsAfterFinalValidationEvenWhenSearchBudgetRemains() {
        RecordingGateway gateway = new RecordingGateway();
        AtomicInteger recoveryCalls = new AtomicInteger();
        ResearchWorkflow workflow = new ResearchWorkflow(gateway, new ResearchResultValidator(),
                (contract, session, issues) -> {
                    recoveryCalls.incrementAndGet();
                    return new ResearchModels.RecoveryPlan(List.of(
                            new ResearchModels.RecoveryQuery("热声", "仍然没有结果")));
                }, ResearchModels.ResearchBudget.defaults(), Clock.systemUTC());

        ResearchModels.ResearchResult result = workflow.research("练声资源", contract(5));

        assertThat(result.status()).isEqualTo(ResearchModels.ResearchStatus.FAILED);
        assertThat(result.recoveryRounds()).isEqualTo(2);
        assertThat(result.searchCalls()).isLessThan(6);
        assertThat(recoveryCalls).hasValue(1);
    }

    @Test
    void duplicateRemediationQueriesDoNotConsumeSearchBudget() {
        RecordingGateway gateway = new RecordingGateway();
        ResearchWorkflow workflow = new ResearchWorkflow(gateway, new ResearchResultValidator(),
                (contract, session, issues) -> new ResearchModels.RecoveryPlan(List.of()),
                ResearchModels.ResearchBudget.defaults(), Clock.systemUTC());

        ResearchModels.ResearchResult result = workflow.research("练声资源", contract(2));

        assertThat(result.searchCalls()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(ResearchModels.ResearchStatus.FAILED);
    }

    @Test
    void stopsScrapingAsSoonAsCoreRequirementsAreMet() {
        RecordingGateway gateway = new RecordingGateway();
        String query = "练声资源 热声";
        gateway.searchResults.put(query, WebSearchResult.success(query, List.of(
                item("足够的来源", "https://example.com/enough"),
                item("不应抓取", "https://example.com/unused"))));
        ResearchWorkflow workflow = new ResearchWorkflow(gateway, new ResearchResultValidator(),
                (contract, session, issues) -> new ResearchModels.RecoveryPlan(List.of()),
                ResearchModels.ResearchBudget.defaults(), Clock.systemUTC());

        ResearchModels.ResearchResult result = workflow.research("练声资源", contract(1));

        assertThat(result.status()).isEqualTo(ResearchModels.ResearchStatus.SUCCESS);
        assertThat(result.searchCalls()).isEqualTo(1);
        assertThat(result.scrapeCalls()).isEqualTo(1);
        assertThat(result.sources()).filteredOn(source ->
                source.scrapeStatus() == ResearchModels.ScrapeStatus.NOT_ATTEMPTED).hasSize(1);
    }

    private ResearchModels.ResearchTaskContract contract(int minimum) {
        return new ResearchModels.ResearchTaskContract(
                ResearchModels.ResearchTaskType.RESOURCE_RESEARCH, "练声",
                ResearchModels.DeliverableType.RESOURCE_LIST, minimum, minimum,
                List.of(new ResearchModels.ResearchCategory("热声", 1)),
                List.of("name", "url"), true, 6, 12);
    }

    private WebSearchResult.SearchItem item(String title, String url) {
        return new WebSearchResult.SearchItem(title, url, "摘要");
    }

    private static final class RecordingGateway implements ResearchGateway {
        private final Map<String, WebSearchResult> searchResults = new HashMap<>();

        @Override
        public WebSearchResult search(String query) {
            return searchResults.getOrDefault(query, WebSearchResult.success(query, List.of()));
        }

        @Override
        public WebScrapeResult scrape(String url) {
            return WebScrapeResult.success("已抓取", url, "完整网页正文");
        }
    }
}
