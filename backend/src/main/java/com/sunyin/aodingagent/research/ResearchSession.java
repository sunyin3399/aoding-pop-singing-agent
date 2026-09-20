package com.sunyin.aodingagent.research;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;

import static com.sunyin.aodingagent.research.ResearchModels.ScrapeStatus.FAILED;
import static com.sunyin.aodingagent.research.ResearchModels.ScrapeStatus.NOT_ATTEMPTED;
import static com.sunyin.aodingagent.research.ResearchModels.SourceQualityStatus.PENDING;

/**
 * 保存一次外部研究过程中的全部状态，相当于这次研究的“工作台账”。
 * <p>
 * 它记录已经搜索了什么、发现了哪些网页、每个网页抓取了几次、为什么失败，以及是否已经
 * 超过时间或次数上限。所有限制都集中在这里判断，可以避免研究工作流的不同分支绕过预算。
 * 这个对象只属于单次请求，不用于保存用户的长期记忆。
 */
public final class ResearchSession {

    private final String originalRequest;
    private final ResearchModels.ResearchTaskContract contract;
    private final ResearchModels.ResearchBudget budget;
    private final Clock clock;
    private final Instant deadline;
    private final Map<String, ResearchModels.SourceCandidate> sources = new LinkedHashMap<>();
    private final List<String> searchQueries = new ArrayList<>();
    private final Set<String> normalizedSearchQueries = new HashSet<>();
    private final Set<String> blockedDomains = new HashSet<>();
    private int searchCalls;
    private int scrapeCalls;
    private int deterministicRemediations;
    private int recoveryPlannerCalls;

    public ResearchSession(String originalRequest, ResearchModels.ResearchTaskContract contract,
                           ResearchModels.ResearchBudget budget, Clock clock) {
        this.originalRequest = originalRequest;
        this.contract = contract;
        this.budget = budget;
        this.clock = clock;
        this.deadline = clock.instant().plusSeconds(budget.timeoutSeconds());
    }

    /**
     * 尝试占用一次搜索额度。
     *
     * @return 仍有时间和次数预算时返回 true；返回 false 表示调用方必须停止继续搜索
     */
    public boolean tryRecordSearch(String query) {
        if (expired() || searchCalls >= Math.min(budget.maxSearchCalls(), contract.maxSearchCalls())) return false;
        String normalized = normalizeQuery(query);
        if (!normalizedSearchQueries.add(normalized)) return false;
        searchCalls++;
        searchQueries.add(query);
        return true;
    }

    /** 相同查询只执行一次，忽略大小写和多余空白。 */
    public boolean hasSearched(String query) {
        return normalizedSearchQueries.contains(normalizeQuery(query));
    }

    /**
     * 尝试占用一次网页抓取额度，同时检查总次数、单网址次数和被阻断域名。
     *
     * @return 本次允许抓取时返回 true，否则返回 false
     */
    public boolean tryRecordScrape(String url) {
        ResearchModels.SourceCandidate source = sources.get(ReferenceAccumulator.normalizeUrl(url));
        if (expired() || scrapeCalls >= Math.min(budget.maxScrapeCalls(), contract.maxScrapeCalls())) return false;
        if (blockedDomains.contains(domain(url))) return false;
        if (source != null && source.scrapeAttempts() >= budget.maxScrapeAttemptsPerUrl()) return false;
        scrapeCalls++;
        return true;
    }

    /**
     * 将搜索结果加入来源台账。URL 会先统一格式并去重，相同网页只保留第一次发现的位置。
     */
    public void addSearchResult(WebSearchResult.SearchItem item, String category, String query) {
        String normalized = ReferenceAccumulator.normalizeUrl(item.url());
        if (normalized == null) return;
        sources.computeIfAbsent(normalized, ignored -> new ResearchModels.SourceCandidate(
                UUID.nameUUIDFromBytes(normalized.getBytes(StandardCharsets.UTF_8)).toString(),
                item.title(), item.url(), normalized, category, query, item.snippet(),
                NOT_ATTEMPTED, 0, null, "", PENDING));
    }

    public void recordScrapeFailure(String url, ResearchModels.ScrapeErrorCode errorCode) {
        String normalized = ReferenceAccumulator.normalizeUrl(url);
        ResearchModels.SourceCandidate source = sources.get(normalized);
        if (source == null) return;
        // 遇到验证码或反爬挑战页后阻断整个域名，避免继续浪费抓取额度。
        if (errorCode == ResearchModels.ScrapeErrorCode.CHALLENGE_PAGE) blockedDomains.add(domain(url));
        sources.put(normalized, new ResearchModels.SourceCandidate(
                source.id(), source.title(), source.url(), source.normalizedUrl(), source.category(),
                source.searchQuery(), source.snippet(), FAILED, source.scrapeAttempts() + 1,
                errorCode, "", source.qualityStatus()));
    }

    public void recordScrapeSuccess(String url, String title, String content) {
        String normalized = ReferenceAccumulator.normalizeUrl(url);
        ResearchModels.SourceCandidate source = sources.get(normalized);
        if (source == null) return;
        sources.put(normalized, new ResearchModels.SourceCandidate(
                source.id(), title == null || title.isBlank() ? source.title() : title,
                source.url(), source.normalizedUrl(), source.category(), source.searchQuery(),
                source.snippet(), ResearchModels.ScrapeStatus.SCRAPED, source.scrapeAttempts() + 1,
                null, content, ResearchModels.SourceQualityStatus.RELEVANT));
    }

    /** 尝试开始一次固定规则补救，超过预算后返回 false。 */
    public boolean tryStartDeterministicRemediation() {
        if (deterministicRemediations >= budget.maxDeterministicRemediations()) return false;
        deterministicRemediations++;
        return true;
    }

    /** 尝试开始一次由大模型规划查询的智能补救，超过预算后返回 false。 */
    public boolean tryStartRecoveryPlanner() {
        if (recoveryPlannerCalls >= budget.maxRecoveryPlannerCalls()) return false;
        recoveryPlannerCalls++;
        return true;
    }

    public List<ResearchModels.SourceCandidate> sources() { return List.copyOf(sources.values()); }
    public String originalRequest() { return originalRequest; }
    public ResearchModels.ResearchTaskContract contract() { return contract; }
    public int searchCalls() { return searchCalls; }
    public List<String> searchQueries() { return List.copyOf(searchQueries); }
    public int scrapeCalls() { return scrapeCalls; }
    public int recoveryRounds() { return deterministicRemediations + recoveryPlannerCalls; }
    public boolean expired() { return !clock.instant().isBefore(deadline); }

    boolean isDomainBlocked(String url) { return blockedDomains.contains(domain(url)); }

    private String domain(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
