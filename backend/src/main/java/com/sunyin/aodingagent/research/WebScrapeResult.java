package com.sunyin.aodingagent.research;

public record WebScrapeResult(
        boolean success,
        String title,
        String url,
        String content,
        ResearchModels.ScrapeErrorCode errorCode,
        boolean retryable,
        String error
) {
    public static WebScrapeResult success(String title, String url, String content) {
        return new WebScrapeResult(true, title, url, content, null, false, null);
    }

    public static WebScrapeResult failure(String url, ResearchModels.ScrapeErrorCode code,
                                          boolean retryable, String error) {
        return new WebScrapeResult(false, "", url, "", code, retryable, error);
    }
}
