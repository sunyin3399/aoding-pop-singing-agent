package com.sunyin.aodingagent.research;

/**
 * 外部研究工作流访问搜索引擎和网页抓取能力的统一接口。
 * <p>
 * 工作流只依赖结构化的搜索和抓取结果，不依赖具体搜索供应商，便于切换实现和离线测试。
 */
public interface ResearchGateway {
    /** 搜索关键词并返回候选网页列表。 */
    WebSearchResult search(String query);

    /** 抓取一个网页的正文，并明确返回成功、错误类型和错误是否值得重试。 */
    WebScrapeResult scrape(String url);
}
