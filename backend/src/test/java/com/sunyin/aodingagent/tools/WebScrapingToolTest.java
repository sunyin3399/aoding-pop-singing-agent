package com.sunyin.aodingagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.research.WebScrapeResult;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static com.sunyin.aodingagent.research.ResearchModels.ScrapeErrorCode.HTTP_FORBIDDEN;
import static com.sunyin.aodingagent.research.ResearchModels.ScrapeErrorCode.CHALLENGE_PAGE;
import static com.sunyin.aodingagent.research.ResearchModels.ScrapeErrorCode.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

class WebScrapingToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsStructuredPageContent() throws Exception {
        WebScrapingTool tool = new WebScrapingTool(url -> Jsoup.parse(
                "<html><head><title>热声</title></head><body><article>" + "练习内容".repeat(60)
                        + "</article></body></html>", url));

        WebScrapeResult result = read(tool.scrapeWebPage("https://example.com/warm-up"));

        assertThat(result.success()).isTrue();
        assertThat(result.title()).isEqualTo("热声");
        assertThat(result.content()).contains("练习内容");
    }

    @Test
    void classifiesTimeoutAsRetryableAndForbiddenAsNonRetryable() throws Exception {
        WebScrapingTool timeout = new WebScrapingTool(url -> { throw new SocketTimeoutException("slow"); });
        WebScrapingTool forbidden = new WebScrapingTool(url -> {
            throw new HttpStatusException("blocked", 403, url);
        });

        assertThat(read(timeout.scrapeWebPage("https://example.com/a")))
                .extracting(WebScrapeResult::errorCode, WebScrapeResult::retryable)
                .containsExactly(TIMEOUT, true);
        assertThat(read(forbidden.scrapeWebPage("https://example.com/b")))
                .extracting(WebScrapeResult::errorCode, WebScrapeResult::retryable)
                .containsExactly(HTTP_FORBIDDEN, false);
    }

    @Test
    void rejectsSecurityChallengePagesAsEvidence() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        WebScrapingTool tool = new WebScrapingTool(url -> {
            fetches.incrementAndGet();
            return Jsoup.parse(
                    "<html><head><title>百度安全验证</title></head><body>网络不给力，请稍后重试 返回首页</body></html>",
                    url);
        });

        WebScrapeResult result = read(tool.scrapeWebPage("https://baijiahao.baidu.com/example"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(CHALLENGE_PAGE);
        assertThat(result.retryable()).isFalse();
        assertThat(result.content()).isEmpty();

        WebScrapeResult cooledDown = read(tool.scrapeWebPage("https://baijiahao.baidu.com/another"));
        assertThat(cooledDown.errorCode()).isEqualTo(CHALLENGE_PAGE);
        assertThat(fetches).hasValue(1);
    }

    private WebScrapeResult read(String json) throws Exception {
        return objectMapper.readValue(json, WebScrapeResult.class);
    }
}
