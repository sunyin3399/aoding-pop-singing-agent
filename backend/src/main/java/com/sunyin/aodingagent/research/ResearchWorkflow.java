package com.sunyin.aodingagent.research;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 负责执行一次完整的外部资料研究，可以把它理解成研究任务的“流程控制器”。
 * <p>
 * 如果让 Agent 自己不断搜索网页，它可能遇到一个失败页面就停止，也可能为了凑数量反复搜索，
 * 最后仍把不完整的结果说成成功。这个类把流程固定为：首次搜索、一次规则补救、一次智能补救，
 * 并由预算限制搜索次数、抓取次数和总时间，保证研究任务一定能够结束。
 */
public final class ResearchWorkflow {

    private final ResearchGateway gateway;
    private final ResearchResultValidator validator;
    private final ResearchRecoveryPlanner recoveryPlanner;
    private final ResearchModels.ResearchBudget budget;
    private final Clock clock;

    public ResearchWorkflow(ResearchGateway gateway, ResearchResultValidator validator,
                            ResearchRecoveryPlanner recoveryPlanner,
                            ResearchModels.ResearchBudget budget, Clock clock) {
        this.gateway = gateway;
        this.validator = validator;
        this.recoveryPlanner = recoveryPlanner;
        this.budget = budget;
        this.clock = clock;
    }

    /**
     * 按任务合同执行研究，并给出成功、降级或失败三种明确结果。
     *
     * @param request 用户最初提出的完整问题
     * @param contract 研究前确定的目标，包括分类、最低来源数和调用上限
     * @return 研究结果及全部来源台账；降级表示已有可用证据，但没有完全达到合同要求
     */
    public ResearchModels.ResearchResult research(
            String request,
            ResearchModels.ResearchTaskContract contract
    ) {
        // 每次请求创建独立会话，用它统一记录来源、次数、失败原因和剩余时间。
        ResearchSession session = new ResearchSession(request, contract, budget, clock);
        List<ResearchModels.RecoveryQuery> initialQueries = initialQueries(request, contract);
        executeQueries(session, initialQueries);
        List<ResearchModels.ValidationIssue> issues = validator.validate(contract, session);
        if (issues.isEmpty()) return result(ResearchModels.ResearchStatus.SUCCESS, session, issues);

        // 第一次不达标时，先用固定规则补齐缺少的分类，不额外依赖大模型判断。
        if (session.tryStartDeterministicRemediation()) {
            executeQueries(session, remediationQueries(contract, session, issues));
            issues = validator.validate(contract, session);
        }
        if (issues.isEmpty()) return result(ResearchModels.ResearchStatus.SUCCESS, session, issues);

        // 固定规则仍无法补齐时，最后允许恢复规划器根据缺口生成一组新查询。
        if (session.tryStartRecoveryPlanner()) {
            ResearchModels.RecoveryPlan plan = recoveryPlanner.recover(contract, session, issues);
            executeQueries(session, plan == null ? List.of() : plan.queries());
            issues = validator.validate(contract, session);
        }

        long validSources = session.sources().stream()
                .filter(source -> source.scrapeStatus() == ResearchModels.ScrapeStatus.SCRAPED)
                .count();
        // 有部分有效来源但未达标属于 DEGRADED；一个有效来源都没有才是 FAILED。
        ResearchModels.ResearchStatus status = issues.isEmpty()
                ? ResearchModels.ResearchStatus.SUCCESS
                : validSources > 0 ? ResearchModels.ResearchStatus.DEGRADED : ResearchModels.ResearchStatus.FAILED;
        return result(status, session, issues);
    }

    private List<ResearchModels.RecoveryQuery> initialQueries(
            String request, ResearchModels.ResearchTaskContract contract
    ) {
        return contract.categories().stream()
                .map(category -> new ResearchModels.RecoveryQuery(
                        category.name(), searchQuery(request, contract.deliverable(), category.name())))
                .toList();
    }

    private String searchQuery(String request, ResearchModels.DeliverableType deliverable, String category) {
        return switch (deliverable) {
            case SONG_RECOMMENDATION -> songSearchQuery(request, category);
            case TUTORIAL_LIST -> request + " " + category + " 分步教学 跟练 示范";
            case EXERCISE_GUIDE -> request + " " + category + " 练习步骤 时长 常见错误";
            case COMPARISON_TABLE -> request + " " + category + " 对比 区别 适用场景";
            case RESEARCH_SUMMARY -> request + " " + category + " 专业解释 证据";
            case RESOURCE_LIST -> request + " " + category;
        };
    }

    private String songSearchQuery(String request, String category) {
        String diverseSources = " -site:baijiahao.baidu.com -site:zhidao.baidu.com";
        return switch (category) {
            case "暂缓歌曲与难点" -> request + " 不适合初学者 难唱歌曲 高音 换声 气息 难点" + diverseSources;
            case "教学辅助" -> request + " 分句教学 教唱 降调伴奏 发声难点 示范视频 bilibili" + diverseSources;
            default -> request + " 推荐歌曲 歌曲名 歌手 适合初学者 音域 节奏 难度" + diverseSources;
        };
    }

    private List<ResearchModels.RecoveryQuery> remediationQueries(
            ResearchModels.ResearchTaskContract contract,
            ResearchSession session,
            List<ResearchModels.ValidationIssue> issues
    ) {
        List<ResearchModels.RecoveryQuery> queries = new ArrayList<>();
        for (ResearchModels.ValidationIssue issue : issues) {
            if ("CATEGORY_NOT_COVERED".equals(issue.code())) {
                queries.add(new ResearchModels.RecoveryQuery(
                        issue.field(), searchQuery(session.originalRequest(), contract.deliverable(), issue.field())));
            }
        }
        if (queries.isEmpty()) {
            String category = contract.categories().isEmpty() ? "综合" : contract.categories().getFirst().name();
            queries.add(new ResearchModels.RecoveryQuery(
                    category, searchQuery(session.originalRequest(), contract.deliverable(), category)));
        }
        return queries;
    }

    private void executeQueries(ResearchSession session, List<ResearchModels.RecoveryQuery> queries) {
        for (ResearchModels.RecoveryQuery query : queries) {
            if (validator.validate(session.contract(), session).isEmpty()) return;
            if (session.hasSearched(query.query())) continue;
            if (!session.tryRecordSearch(query.query())) return;
            WebSearchResult search = gateway.search(query.query());
            if (search != null && search.success()) {
                for (WebSearchResult.SearchItem item : search.items()) {
                    session.addSearchResult(item, query.category(), query.query());
                }
            }
            if (scrapePending(session)) return;
        }
    }

    private boolean scrapePending(ResearchSession session) {
        for (ResearchModels.SourceCandidate source : session.sources()) {
            if (source.scrapeStatus() == ResearchModels.ScrapeStatus.SCRAPED) continue;
            // 是否重试由会话预算和错误类型共同决定，不能对同一失败网页无限重试。
            while (session.tryRecordScrape(source.url())) {
                WebScrapeResult scrape = gateway.scrape(source.normalizedUrl());
                if (scrape.success()) {
                    session.recordScrapeSuccess(source.url(), scrape.title(), scrape.content());
                    if (validator.validate(session.contract(), session).isEmpty()) return true;
                    break;
                }
                session.recordScrapeFailure(source.url(), scrape.errorCode());
                if (!scrape.retryable()) break;
            }
        }
        return false;
    }

    private ResearchModels.ResearchResult result(
            ResearchModels.ResearchStatus status,
            ResearchSession session,
            List<ResearchModels.ValidationIssue> issues
    ) {
        return new ResearchModels.ResearchResult(status, session.contract(), session.sources(), issues,
                session.searchCalls(), session.scrapeCalls(), session.recoveryRounds());
    }
}
