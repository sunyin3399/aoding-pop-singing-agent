package com.sunyin.aodingagent.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.tools.WebScrapingTool;
import com.sunyin.aodingagent.tools.WebSearchTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResearchGatewayTest {

    @Test
    void filtersInvalidAndSearchRedirectUrlsButKeepsVideoSources() {
        assertThat(ToolResearchGateway.isUsableSourceUrl("javascript:alert(1)")).isFalse();
        assertThat(ToolResearchGateway.isUsableSourceUrl("https://www.google.com/search?q=shallow")).isFalse();
        assertThat(ToolResearchGateway.isUsableSourceUrl("https://example.com/login")).isFalse();
        assertThat(ToolResearchGateway.isUsableSourceUrl("https://www.bilibili.com/video/BV123")).isTrue();
    }

    @Test
    void fallsBackToSearchApiWhenSearxngFails() {
        WebSearchTool search = new WebSearchTool("key") {
            @Override public String searchWebWithSearXNG(String query) {
                return "{\"success\":false,\"query\":\"练声\",\"items\":[],\"errorCode\":\"SEARXNG_ERROR\",\"error\":\"offline\"}";
            }
            @Override public String searchWeb(String query) {
                return "{\"success\":true,\"query\":\"练声\",\"items\":[{\"title\":\"NATS\",\"url\":\"https://nats.org/a\",\"snippet\":\"warm up\"}]}";
            }
        };
        ToolResearchGateway gateway = new ToolResearchGateway(search, new WebScrapingTool(), new ObjectMapper());

        WebSearchResult result = gateway.search("练声");

        assertThat(result.success()).isTrue();
        assertThat(result.items()).extracting(WebSearchResult.SearchItem::title).containsExactly("NATS");
    }
}
