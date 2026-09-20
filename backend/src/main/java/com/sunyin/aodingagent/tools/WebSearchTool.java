package com.sunyin.aodingagent.tools;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.research.WebSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WebSearchTool 类是一个用于网络搜索的工具类
 * 提供了通过百度搜索引擎搜索信息的功能
 */
public class WebSearchTool {

    private static final Logger logger = LoggerFactory.getLogger(WebSearchTool.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // SearchAPI 的搜索接口地址
    private static final String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";

    @Value("${search-api.api-key}")
    private final String apiKey; // 存储API密钥的成员变量
    private final String searxngBaseUrl;

    /**
     * WebSearchTool 的构造函数
     *
     * @param apiKey 用于认证的API密钥
     */
    public WebSearchTool(String apiKey) {
        this(apiKey, "http://localhost:8080");
    }

    public WebSearchTool(String apiKey, String searxngBaseUrl) {
        this.apiKey = apiKey;
        this.searxngBaseUrl = searxngBaseUrl.replaceAll("/+$", "");
    }

    /**
     * 使用百度搜索引擎进行网络搜索的方法
     *
     * @param query 搜索关键词
     * @return 返回搜索结果的JSON字符串
     */
    @Tool(description = "Search using SearchAPI paid service (fallback when SearXNG is unavailable)")
    public String searchWeb(@ToolParam(description = "Search query keyword") String query) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("q", query);
        paramMap.put("api_key", apiKey);
        paramMap.put("engine", "baidu");
        try {
            String response = HttpUtil.get(SEARCH_API_URL, paramMap);
            return parseSearchApiResponse(query, response);
        } catch (Exception e) {
            return json(WebSearchResult.failure(query, "SEARCH_API_ERROR", e.getMessage()));
        }
    }

    @Tool(description = "Search the web using local SearXNG (free, fast, preferred for general queries)")
    public String searchWebWithSearXNG(@ToolParam(description = "search query") String query) {
        try {
            String url = searxngBaseUrl + "/search"
                    + "?q=" + URLEncoder.encode(query, "UTF-8")
                    + "&format=json"
                    + "&language=auto"
                    + "&safesearch=0";

            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json, text/plain, */*");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            headers.put("Accept-Encoding", "gzip, deflate");
            headers.put("Connection", "keep-alive");
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.put("Referer", "http://localhost:8080/");

            String response = HttpUtil.createGet(url).addHeaders(headers).execute().body();

            return parseSearxResponse(query, response);

        } catch (Exception e) {
            return json(WebSearchResult.failure(query, "SEARXNG_ERROR", e.getMessage()));
        }
    }

    static String parseSearchApiResponse(String query, String response) {
        JSONObject jsonObject = JSONUtil.parseObj(response);
        JSONArray organicResults = jsonObject.getJSONArray("organic_results");
        if (organicResults != null && !organicResults.isEmpty()) {
            List<WebSearchResult.SearchItem> items = new ArrayList<>();
            int limit = Math.min(3, organicResults.size());
            for (int i = 0; i < limit; i++) {
                JSONObject result = organicResults.getJSONObject(i);
                items.add(new WebSearchResult.SearchItem(
                        result.getStr("title", ""), result.getStr("link", ""),
                        result.getStr("snippet", "")));
            }
            logger.info("SearchAPI returned {} organic results", items.size());
            return json(WebSearchResult.success(query, items));
        }
        String upstreamError = firstNonBlank(
                jsonObject.getStr("error"),
                jsonObject.getStr("message"),
                jsonObject.getStr("error_message"),
                jsonObject.getByPath("search_metadata.status", String.class));
        if (upstreamError != null && !"success".equalsIgnoreCase(upstreamError)) {
            return json(WebSearchResult.failure(query, classifySearchApiError(upstreamError), upstreamError));
        }
        return json(WebSearchResult.failure(query, "NO_RESULTS", "没有找到可引用的自然搜索结果"));
    }

    private static String classifySearchApiError(String error) {
        String normalized = error.toLowerCase();
        if (normalized.contains("credit") || normalized.contains("quota")
                || normalized.contains("allowance") || normalized.contains("payment")) {
            return "QUOTA_EXHAUSTED";
        }
        if (normalized.contains("api key") || normalized.contains("unauthorized")
                || normalized.contains("authentication") || normalized.contains("forbidden")) {
            return "AUTH_ERROR";
        }
        if (normalized.contains("rate limit") || normalized.contains("too many requests")) {
            return "RATE_LIMITED";
        }
        return "UPSTREAM_ERROR";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    static String parseSearxResponse(String query, String response) {
        JSONObject jsonObject = JSONUtil.parseObj(response);
        JSONArray results = jsonObject.getJSONArray("results");
        if (results == null || results.isEmpty()) {
            return json(WebSearchResult.failure(query, "NO_RESULTS", "没有找到可引用的搜索结果"));
        }
        List<WebSearchResult.SearchItem> items = new ArrayList<>();
        int limit = Math.min(5, results.size());
        for (int i = 0; i < limit; i++) {
            JSONObject result = results.getJSONObject(i);
            items.add(new WebSearchResult.SearchItem(
                    result.getStr("title", ""), result.getStr("url", ""),
                    result.getStr("content", "")));
        }
        return json(WebSearchResult.success(query, items));
    }

    private static String json(WebSearchResult result) {
        try {
            return ToolOutputLimiter.limit(OBJECT_MAPPER.writeValueAsString(result));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化网页搜索结果", exception);
        }
    }
}
