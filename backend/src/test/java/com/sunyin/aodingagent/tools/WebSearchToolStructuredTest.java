package com.sunyin.aodingagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.research.WebSearchResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchToolStructuredTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void convertsSearchApiOrganicResultsToStructuredJson() throws Exception {
        String payload = """
                {"organic_results":[
                  {"title":"资源 A","snippet":"摘要 A","link":"https://example.com/a"},
                  {"title":"资源 B","snippet":"摘要 B","link":"https://example.com/b"}
                ]}
                """;

        WebSearchResult result = objectMapper.readValue(
                WebSearchTool.parseSearchApiResponse("练声", payload), WebSearchResult.class);

        assertThat(result.success()).isTrue();
        assertThat(result.items()).extracting(WebSearchResult.SearchItem::title)
                .containsExactly("资源 A", "资源 B");
    }

    @Test
    void convertsSearxResultsToStructuredJson() throws Exception {
        String payload = """
                {"results":[{"title":"资源 C","content":"摘要 C","url":"https://example.org/c"}]}
                """;

        WebSearchResult result = objectMapper.readValue(
                WebSearchTool.parseSearxResponse("热声", payload), WebSearchResult.class);

        assertThat(result.items()).singleElement()
                .extracting(WebSearchResult.SearchItem::url)
                .isEqualTo("https://example.org/c");
    }

    @Test
    void exposesQuotaExhaustionInsteadOfReportingNoResults() throws Exception {
        String payload = "{\"error\":\"Monthly search credits exhausted\"}";

        WebSearchResult result = objectMapper.readValue(
                WebSearchTool.parseSearchApiResponse("练声", payload), WebSearchResult.class);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("QUOTA_EXHAUSTED");
        assertThat(result.error()).contains("credits exhausted");
    }
}
