package com.sunyin.aodingagent.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.tools.WebScrapingTool;
import com.sunyin.aodingagent.tools.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.net.URI;

/**
 * 把底层搜索和网页抓取工具适配成研究工作流认识的统一接口。
 * <p>
 * 搜索源带“失败自适应调级”：本地 SearXNG 连续失败若干次后，自动改用付费 SearchAPI 优先，
 * 冷却期结束再重新探测 SearXNG，避免一个不可用的搜索源每次都空转一次。
 */
public final class ToolResearchGateway implements ResearchGateway {

    private static final Logger logger = LoggerFactory.getLogger(ToolResearchGateway.class);

    private final WebSearchTool searchTool;
    private final WebScrapingTool scrapingTool;
    private final ObjectMapper objectMapper;

    /** SearXNG 连续失败多少次后切到 SearchAPI 优先。 */
    private final int failoverThreshold;
    /** 切换后多久没有再尝试 SearXNG，就允许重新探测它（毫秒）。 */
    private final long failoverCooldownMillis;

    private final AtomicInteger searxngFailures = new AtomicInteger();
    private final AtomicLong lastSearxngFailureAt = new AtomicLong(Long.MIN_VALUE);

    public ToolResearchGateway(WebSearchTool searchTool, WebScrapingTool scrapingTool, ObjectMapper objectMapper) {
        this(searchTool, scrapingTool, objectMapper, 2, 300_000L);
    }

    public ToolResearchGateway(WebSearchTool searchTool, WebScrapingTool scrapingTool, ObjectMapper objectMapper,
                               int failoverThreshold, long failoverCooldownMillis) {
        this.searchTool = searchTool;
        this.scrapingTool = scrapingTool;
        this.objectMapper = objectMapper;
        this.failoverThreshold = Math.max(1, failoverThreshold);
        this.failoverCooldownMillis = Math.max(1_000, failoverCooldownMillis);
    }

    @Override
    public WebSearchResult search(String query) {
        if (!preferSearchApi()) {
            // 正常路径：本地免费 SearXNG 优先，失败才回退 SearchAPI。
            WebSearchResult searxng = searxng(query);
            if (searxng.success()) {
                noteSearxngSuccess();
                logger.info("外部搜索成功: source=SearXNG, resultCount={}, query='{}'", searxng.items().size(), query);
                return searxng;
            }
            noteSearxngFailure();
            logger.warn("外部搜索失败: source=SearXNG, code={}, reason={}, query='{}'",
                    searxng.errorCode(), compact(searxng.error()), query);

            WebSearchResult api = searchApi(query);
            if (api.success()) {
                logger.info("外部搜索成功: source=SearchAPI, resultCount={}, query='{}'", api.items().size(), query);
                return api;
            }
            logger.warn("外部搜索失败: source=SearchAPI, code={}, reason={}, query='{}'",
                    api.errorCode(), compact(api.error()), query);
            return combinedFail(query, searxng, api);
        }

        // 自适应降级路径：SearXNG 近期连续失败过多，SearchAPI 优先。
        WebSearchResult api = searchApi(query);
        if (api.success()) {
            logger.info("外部搜索成功: source=SearchAPI(自适应优先), resultCount={}, query='{}'", api.items().size(), query);
            return api;
        }
        logger.warn("外部搜索失败: source=SearchAPI(自适应优先), code={}, reason={}, query='{}'",
                api.errorCode(), compact(api.error()), query);

        // SearchAPI 也失败时，仍尝试一次 SearXNG 作为兜底（不浪费主路径，仅当两者都不可用时）。
        WebSearchResult searxng = searxng(query);
        if (searxng.success()) {
            noteSearxngSuccess();
            logger.info("外部搜索成功: source=SearXNG(兜底), resultCount={}, query='{}'", searxng.items().size(), query);
            return searxng;
        }
        noteSearxngFailure();
        logger.warn("外部搜索失败: source=SearXNG(兜底), code={}, reason={}, query='{}'",
                searxng.errorCode(), compact(searxng.error()), query);
        return combinedFail(query, searxng, api);
    }

    /** 是否切到 SearchAPI 优先：SearXNG 连续失败达到阈值，且还没过冷却期。 */
    private boolean preferSearchApi() {
        if (searxngFailures.get() < failoverThreshold) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastSearxngFailureAt.get() >= failoverCooldownMillis) {
            // 冷却结束：允许重新探测 SearXNG，先清零，避免它恢复后仍被长期冷落。
            searxngFailures.set(0);
            return false;
        }
        return true;
    }

    private void noteSearxngSuccess() {
        searxngFailures.set(0);
    }

    private void noteSearxngFailure() {
        searxngFailures.incrementAndGet();
        lastSearxngFailureAt.set(System.currentTimeMillis());
    }

    private WebSearchResult searxng(String query) {
        return filterObviousInvalidSources(read(searchTool.searchWebWithSearXNG(query), WebSearchResult.class));
    }

    private WebSearchResult searchApi(String query) {
        // 百度 SearchAPI 对 SearXNG 风格的 -site: 排除语法兼容性不稳定，回退时移除。
        String cleanQuery = query.replaceAll("\\s+-site:\\S+", "").replaceAll("\\s+", " ").trim();
        return filterObviousInvalidSources(read(searchTool.searchWeb(cleanQuery), WebSearchResult.class));
    }

    private WebSearchResult filterObviousInvalidSources(WebSearchResult result) {
        if (!result.success()) return result;
        return WebSearchResult.success(result.query(), result.items().stream()
                .filter(item -> isUsableSourceUrl(item.url()))
                .toList());
    }

    static boolean isUsableSourceUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return false;
            }
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
            String lowerHost = host.toLowerCase();
            if (path.matches(".*/(?:login|signin)(?:/.*)?")) return false;
            boolean searchEngine = lowerHost.matches("(?:www\\.)?(?:google\\.[a-z.]+|bing\\.com|baidu\\.com|sogou\\.com|so\\.com)");
            return !searchEngine || !(path.equals("/search") || path.equals("/url") || path.equals("/link"));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private WebSearchResult combinedFail(String query, WebSearchResult searxng, WebSearchResult api) {
        return new WebSearchResult(false, query, api.items(),
                api.errorCode(), "SearXNG: " + searxng.error() + "; SearchAPI: " + api.error());
    }

    @Override
    public WebScrapeResult scrape(String url) {
        return read(scrapingTool.scrapeWebPage(url), WebScrapeResult.class);
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("研究工具返回了无法解析的结构化结果", exception);
        }
    }

    private String compact(String value) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 200 ? compact : compact.substring(0, 200) + "…";
    }
}
