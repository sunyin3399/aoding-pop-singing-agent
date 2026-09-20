package com.sunyin.aodingagent.research.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.research.ResearchGateway;
import com.sunyin.aodingagent.research.ResearchModels;
import com.sunyin.aodingagent.research.ResearchResultValidator;
import com.sunyin.aodingagent.research.ResearchWorkflow;
import com.sunyin.aodingagent.research.WebScrapeResult;
import com.sunyin.aodingagent.research.WebSearchResult;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真实执行研究工作流的离线回归测试。
 * <p>
 * Golden case 只固定输入、外部服务返回和期望结果，不把一次运行后的 Outcome 当成输入。
 * 因此工作流的搜索、抓取、补救、预算或状态判断发生退化时，本测试能够真实发现差异。
 */
class ResearchWorkflowGoldenTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executesWorkflowAndWritesBaselineCandidateDiff() throws Exception {
        List<GoldenCase> cases = loadCases();
        List<CaseResult> results = new ArrayList<>();
        for (GoldenCase goldenCase : cases) results.add(runCase(goldenCase));

        EvaluationReport candidate = report(results);
        EvaluationReport baseline = objectMapper.readValue(
                getClass().getResourceAsStream("/evaluation/baselines/research-workflow-v1.json"),
                EvaluationReport.class);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("baseline", baseline);
        diff.put("candidate", candidate);
        diff.put("changed", !baseline.equals(candidate));
        diff.put("cases", results);
        Path output = Path.of("target/evaluation/research-workflow-diff.json");
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), diff);

        // 这些断言就是第一版 regression gate：核心指标低于 baseline 时构建立即失败。
        assertEquals(baseline, candidate, "研究工作流指标发生变化，查看 target/evaluation/research-workflow-diff.json");
    }

    private CaseResult runCase(GoldenCase goldenCase) {
        ResearchModels.DeliverableType deliverable = goldenCase.id().contains("tutorial")
                ? ResearchModels.DeliverableType.TUTORIAL_LIST
                : ResearchModels.DeliverableType.RESEARCH_SUMMARY;
        ResearchModels.ResearchTaskContract contract = new ResearchModels.ResearchTaskContract(
                ResearchModels.ResearchTaskType.GENERAL_RESEARCH, goldenCase.request(), deliverable,
                1, goldenCase.minimumSources(), goldenCase.categories(), List.of(), false, 4, 6);
        ResearchModels.ResearchBudget budget = new ResearchModels.ResearchBudget(4, 6, 1, 1, 0, 30);
        ResearchWorkflow workflow = new ResearchWorkflow(
                new RecordedGateway(goldenCase.scenario()), new ResearchResultValidator(),
                (ignoredContract, ignoredSession, ignoredIssues) -> new ResearchModels.RecoveryPlan(List.of()),
                budget, Clock.systemUTC());

        ResearchModels.ResearchResult actual = workflow.research(goldenCase.request(), contract);
        long validSources = actual.sources().stream()
                .filter(source -> source.scrapeStatus() == ResearchModels.ScrapeStatus.SCRAPED).count();
        boolean withinBudget = actual.searchCalls() <= budget.maxSearchCalls()
                && actual.scrapeCalls() <= budget.maxScrapeCalls();
        return new CaseResult(goldenCase.id(), goldenCase.expectedStatus(), actual.status(),
                goldenCase.expectedMinimumValidSources(), validSources, withinBudget);
    }

    private EvaluationReport report(List<CaseResult> results) {
        double count = results.size();
        double statusAccuracy = results.stream().filter(result -> result.expectedStatus() == result.actualStatus()).count() / count;
        double coverage = results.stream().filter(result -> result.actualValidSources() >= result.expectedMinimumValidSources()).count() / count;
        int budgetViolations = (int) results.stream().filter(result -> !result.withinBudget()).count();
        return new EvaluationReport(results.size(), statusAccuracy, coverage, budgetViolations);
    }

    private List<GoldenCase> loadCases() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/evaluation/research-workflow-golden-cases.jsonl");
        if (stream == null) throw new IllegalStateException("缺少 research-workflow-golden-cases.jsonl");
        List<GoldenCase> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (!line.isBlank()) cases.add(objectMapper.readValue(line, GoldenCase.class));
            }
        }
        return cases;
    }

    /** 模拟可重复的搜索和抓取供应商，避免 CI 依赖百度、Tavily 或网络额度。 */
    private static final class RecordedGateway implements ResearchGateway {
        private final String scenario;
        private int searchNumber;

        private RecordedGateway(String scenario) {
            this.scenario = scenario;
        }

        @Override
        public WebSearchResult search(String query) {
            searchNumber++;
            if ("SEARCH_FAILS".equals(scenario)) return WebSearchResult.failure(query, "PROVIDER_UNAVAILABLE", "模拟搜索失败");
            String url = "https://evidence.example/case-" + searchNumber;
            return WebSearchResult.success(query, List.of(new WebSearchResult.SearchItem("证据 " + searchNumber, url, "与问题相关的摘要")));
        }

        @Override
        public WebScrapeResult scrape(String url) {
            if ("SECOND_CATEGORY_FAILS".equals(scenario) && url.endsWith("case-2")) {
                return WebScrapeResult.failure(url, ResearchModels.ScrapeErrorCode.CHALLENGE_PAGE, false, "模拟安全验证");
            }
            return WebScrapeResult.success("已核验证据", url, "这是一段足够长且与用户问题直接相关的正文内容，用于离线评测研究流程。");
        }
    }

    private record GoldenCase(String id, String request, int minimumSources,
                              List<ResearchModels.ResearchCategory> categories, String scenario,
                              ResearchModels.ResearchStatus expectedStatus, int expectedMinimumValidSources) {
    }

    private record CaseResult(String id, ResearchModels.ResearchStatus expectedStatus,
                              ResearchModels.ResearchStatus actualStatus, int expectedMinimumValidSources,
                              long actualValidSources, boolean withinBudget) {
    }

    private record EvaluationReport(int caseCount, double statusAccuracy,
                                    double sourceCoverageRate, int budgetViolationCount) {
    }
}
