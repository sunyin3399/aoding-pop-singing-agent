package com.sunyin.aodingagent.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchResultTest {

    @Test
    void serializesSearchItemsAsStructuredReferences() throws Exception {
        WebSearchResult result = WebSearchResult.success("练声", List.of(
                new WebSearchResult.SearchItem("NATS 热声", "https://www.nats.org/warm-up", "练习摘要"),
                new WebSearchResult.SearchItem("无链接", "", "忽略")));

        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(result);
        WebSearchResult restored = objectMapper.readValue(json, WebSearchResult.class);

        assertThat(restored.query()).isEqualTo("练声");
        assertThat(restored.items()).hasSize(2);
        assertThat(restored.toReferences()).singleElement()
                .extracting(AgentReference::title, AgentReference::url, AgentReference::status)
                .containsExactly("NATS 热声", "https://www.nats.org/warm-up",
                        AgentReference.ReferenceStatus.SEARCH_ONLY);
    }
}
