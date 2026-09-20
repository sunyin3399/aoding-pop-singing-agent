package com.sunyin.aodingagent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.research.ResearchModels;
import com.sunyin.aodingagent.research.WebScrapeResult;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebScrapingTool 类，提供网页抓取功能
 * 使用Jsoup库来解析和获取网页内容
 */
public class WebScrapingTool {

    private static final Logger logger = LoggerFactory.getLogger(WebScrapingTool.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long CHALLENGE_COOLDOWN_MILLIS = Duration.ofMinutes(15).toMillis();
    private final PageFetcher pageFetcher;
    private final Map<String, Long> challengeBlockedUntil = new ConcurrentHashMap<>();

    public WebScrapingTool() {
        this(url -> Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(10000).get());
    }

    WebScrapingTool(PageFetcher pageFetcher) {
        this.pageFetcher = pageFetcher;
    }

    /**
     * 抓取指定URL的网页内容
     * @param url 要抓取的网页URL地址
     * @return 返回网页的HTML内容，如果发生错误则返回错误信息
     */
    @Tool(description = "Scrape the content of a web page")
    public String scrapeWebPage(@ToolParam(description = "URL of the web page to scrape") String url) {
        try {
            String host = host(url);
            Long blockedUntil = challengeBlockedUntil.get(host);
            if (blockedUntil != null && blockedUntil > System.currentTimeMillis()) {
                logger.info("跳过仍在安全验证冷却期内的站点: {}", host);
                return json(WebScrapeResult.failure(url, ResearchModels.ScrapeErrorCode.CHALLENGE_PAGE,
                        false, "该站点刚刚触发安全验证，已进入短期冷却"));
            }
            if (blockedUntil != null) challengeBlockedUntil.remove(host, blockedUntil);
            Document doc = pageFetcher.fetch(url);

            // 1. 去噪
            doc.select("script, style, nav, footer, header, aside, form, iframe, noscript")
                    .remove();

            String title = doc.title();

            // 2. 优先尝试“正文容器”
            String content = extractMainContent(doc);

            // 3. fallback：body text
            if (content == null || content.trim().isEmpty()) {
                content = doc.body() == null ? "" : doc.body().text();
            }

            // 4. 再 fallback：整页 text（极端情况）
            if (content == null || content.isBlank()) {
                content = doc.text();
            }

            // 5. 清洗空白
            content = content.replaceAll("\\s+", " ").trim();

            if (isChallengePage(title, content)) {
                challengeBlockedUntil.put(host, System.currentTimeMillis() + CHALLENGE_COOLDOWN_MILLIS);
                logger.warn("站点 {} 返回安全验证页，暂停抓取 15 分钟", host);
                return json(WebScrapeResult.failure(url, ResearchModels.ScrapeErrorCode.CHALLENGE_PAGE,
                        false, "页面要求安全验证或拒绝自动访问"));
            }

            // 6. 截断（必须）
            int maxLen = Math.min(6000, ToolOutputLimiter.DEFAULT_MAX_CHARS);
            if (content.length() > maxLen) {
                content = content.substring(0, maxLen) + "...(truncated)";
            }

            if (content.isBlank()) return json(WebScrapeResult.failure(
                    url, ResearchModels.ScrapeErrorCode.EMPTY_CONTENT, false, "网页正文为空"));
            return json(WebScrapeResult.success(title, url, content));
        } catch (SocketTimeoutException exception) {
            return json(WebScrapeResult.failure(url, ResearchModels.ScrapeErrorCode.TIMEOUT,
                    true, exception.getMessage()));
        } catch (HttpStatusException exception) {
            ResearchModels.ScrapeErrorCode code = exception.getStatusCode() == 403
                    ? ResearchModels.ScrapeErrorCode.HTTP_FORBIDDEN
                    : exception.getStatusCode() == 404
                    ? ResearchModels.ScrapeErrorCode.HTTP_NOT_FOUND
                    : ResearchModels.ScrapeErrorCode.UNKNOWN;
            return json(WebScrapeResult.failure(url, code, false, exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            return json(WebScrapeResult.failure(url, ResearchModels.ScrapeErrorCode.INVALID_URL,
                    false, exception.getMessage()));
        } catch (Exception exception) {
            return json(WebScrapeResult.failure(url, ResearchModels.ScrapeErrorCode.UNKNOWN,
                    exception instanceof IOException, exception.getMessage()));
        }
    }

    private String extractMainContent(Document doc) {

        // 🚀 1. 优先 article 标签（现代网站）
        Element article = doc.selectFirst("article");
        if (article != null) {
            return article.text();
        }

        // 🚀 2. 常见正文容器（百度/知乎/公众号/博客）
        String[] selectors = new String[] {
                "#content",
                ".content",
                ".article",
                ".article-content",
                ".post-content",
                ".main",
                "#main",
                ".container"
        };

        for (String selector : selectors) {
            Element el = doc.selectFirst(selector);
            if (el != null && el.text().length() > 200) {
                return el.text();
            }
        }

        // 🚀 3. fallback：所有 p 标签拼接（很重要）
        Elements ps = doc.select("p");
        if (ps != null && !ps.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Element p : ps) {
                String t = p.text().trim();
                if (t.length() > 10) {
                    sb.append(t).append("\n");
                }
            }
            return sb.toString();
        }

        return null;
    }

    static boolean isChallengePage(String title, String content) {
        String page = ((title == null ? "" : title) + " " + (content == null ? "" : content))
                .replaceAll("\\s+", " ");
        return page.contains("百度安全验证")
                || page.contains("网络不给力，请稍后重试")
                || page.contains("完成验证后即可继续访问")
                || page.contains("访问异常，请完成验证")
                || page.contains("请输入验证码")
                || page.toLowerCase().contains("captcha");
    }

    private String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private String json(WebScrapeResult result) {
        try {
            return ToolOutputLimiter.limit(OBJECT_MAPPER.writeValueAsString(result));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化网页抓取结果", exception);
        }
    }

    @FunctionalInterface
    interface PageFetcher {
        Document fetch(String url) throws IOException;
    }
}
