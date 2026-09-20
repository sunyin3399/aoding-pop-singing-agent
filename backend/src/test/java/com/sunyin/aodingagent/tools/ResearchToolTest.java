package com.sunyin.aodingagent.tools;

import com.sunyin.aodingagent.research.ResearchGateway;
import com.sunyin.aodingagent.research.ResearchModels;
import com.sunyin.aodingagent.research.ResearchResultValidator;
import com.sunyin.aodingagent.research.ResearchTaskPlanner;
import com.sunyin.aodingagent.research.ResearchWorkflow;
import com.sunyin.aodingagent.research.WebScrapeResult;
import com.sunyin.aodingagent.research.WebSearchResult;
import com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgePostProcessor;
import com.sunyin.aodingagent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResearchToolTest {

    @Test
    void candidateFailureDoesNotChangeSuccessfulMainResearchResult() {
        ResearchModels.ResearchTaskContract contract = new ResearchModels.ResearchTaskContract(
                ResearchModels.ResearchTaskType.GENERAL_RESEARCH, "系统训练",
                ResearchModels.DeliverableType.EXERCISE_GUIDE, 1, 1,
                List.of(new ResearchModels.ResearchCategory("训练", 1)), List.of(), true, 1, 1);
        ResearchGateway gateway = new ResearchGateway() {
            public WebSearchResult search(String query) {
                return WebSearchResult.success(query, List.of(new WebSearchResult.SearchItem(
                        "训练资料", "https://example.com/training", "摘要")));
            }
            public WebScrapeResult scrape(String url) {
                return WebScrapeResult.success("训练资料", url, "正文");
            }
        };
        CandidateKnowledgePostProcessor processor = mock(CandidateKnowledgePostProcessor.class);
        when(processor.process(any(), any())).thenThrow(new IllegalStateException("candidate unavailable"));
        ResearchWorkflow workflow = new ResearchWorkflow(gateway, new ResearchResultValidator(),
                (ignoredContract, session, issues) -> new ResearchModels.RecoveryPlan(List.of()),
                ResearchModels.ResearchBudget.defaults(), Clock.systemUTC());

        ToolResult<ResearchModels.AgentResearchResult> result = new ResearchTool(request -> contract, workflow, processor)
                .researchExternal("系统训练");

        assertThat(result.success()).isTrue();
        assertThat(result.code()).isEqualTo("RESEARCH_SUCCESS");
        assertThat(result.data().candidateKnowledge().status())
                .isEqualTo(ResearchModels.CandidateKnowledgeStatus.GENERATION_FAILED);
    }

    @Test
    void returnsDegradedResultWithExpectedAndActualCountsInsteadOfPretendingSuccess() {
        ResearchTaskPlanner planner = request -> ResearchTaskPlanner.defaultResourceListContract("练声", 5);
        ResearchGateway gateway = new ResearchGateway() {
            @Override
            public WebSearchResult search(String query) {
                return query.contains("系统学习")
                        ? WebSearchResult.success(query, List.of(new WebSearchResult.SearchItem(
                        "唯一资源", "https://example.com/only", "摘要")))
                        : WebSearchResult.success(query, List.of());
            }

            @Override
            public WebScrapeResult scrape(String url) {
                return WebScrapeResult.success("唯一资源", url, "正文");
            }
        };
        ResearchWorkflow workflow = new ResearchWorkflow(gateway, new ResearchResultValidator(),
                (contract, session, issues) -> new ResearchModels.RecoveryPlan(List.of()),
                ResearchModels.ResearchBudget.defaults(), Clock.systemUTC());

        ToolResult<ResearchModels.AgentResearchResult> result = new ResearchTool(planner, workflow)
                .researchExternal("整理练声资源清单");

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("RESEARCH_DEGRADED");
        assertThat(result.data().status()).isEqualTo(ResearchModels.ResearchStatus.DEGRADED);
        assertThat(result.error()).contains("目标 3 个来源", "实际 1 个");
        assertThat(result.data().sources().getFirst().snippet()).isEqualTo("正文");
    }

    @Test
    void returnsCompactSourceSummariesWithoutFullScrapedPages() {
        ResearchTaskPlanner planner = request -> ResearchTaskPlanner.defaultResourceListContract("练声", 1);
        String longPage = "正文 ".repeat(1_000);
        ResearchGateway gateway = new ResearchGateway() {
            @Override
            public WebSearchResult search(String query) {
                return WebSearchResult.success(query, List.of(new WebSearchResult.SearchItem(
                        "资源", "https://example.com/resource", "搜索摘要")));
            }

            @Override
            public WebScrapeResult scrape(String url) {
                return WebScrapeResult.success("资源", url, longPage);
            }
        };
        ResearchWorkflow workflow = new ResearchWorkflow(gateway, new ResearchResultValidator(),
                (contract, session, issues) -> new ResearchModels.RecoveryPlan(List.of()),
                ResearchModels.ResearchBudget.defaults(), Clock.systemUTC());

        ToolResult<ResearchModels.AgentResearchResult> result = new ResearchTool(planner, workflow)
                .researchExternal("整理练声资源");

        assertThat(result.data().sources().getFirst().snippet())
                .hasSizeLessThanOrEqualTo(ResearchTool.MAX_SOURCE_SUMMARY_CHARS + 1)
                .endsWith("…");
    }
}
