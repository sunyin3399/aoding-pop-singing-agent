package com.sunyin.aodingagent.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.sunyin.aodingagent.research.AgentReference.ReferenceStatus.SCRAPED;
import static com.sunyin.aodingagent.research.AgentReference.ReferenceStatus.SEARCH_ONLY;
import static com.sunyin.aodingagent.research.AgentReference.ReferenceStatus.INTERNAL_APPROVED;
import static org.assertj.core.api.Assertions.assertThat;

class ReferenceAccumulatorTest {

    @Test
    void accumulatesSearchRoundsAndMergesNormalizedUrlsWithoutReordering() {
        ReferenceAccumulator accumulator = new ReferenceAccumulator();

        accumulator.addAll(List.of(
                reference("first", "来源 A", "HTTPS://Example.com/article/?utm_source=test#part", SEARCH_ONLY),
                reference("second", "来源 B", "https://example.org/guide", SEARCH_ONLY)));
        accumulator.addAll(List.of(
                reference("replacement-id", "来源 A 完整标题", "https://example.com/article", SCRAPED),
                reference("third", "来源 C", "https://example.net/resource?from=search", SEARCH_ONLY)));

        assertThat(accumulator.snapshot()).extracting(AgentReference::id)
                .containsExactly("first", "second", "third");
        assertThat(accumulator.snapshot().getFirst())
                .extracting(AgentReference::title, AgentReference::url, AgentReference::status)
                .containsExactly("来源 A 完整标题", "https://example.com/article", SCRAPED);
        assertThat(accumulator.snapshot().getLast().url())
                .isEqualTo("https://example.net/resource");
    }

    @Test
    void mergesInternalChunksBySourceTitle() {
        ReferenceAccumulator accumulator = new ReferenceAccumulator();

        accumulator.addAll(List.of(
                reference("chunk-1", "周杰伦-《晴天》演唱技巧.md", "/api/ai/knowledge/documents/chunk-1", INTERNAL_APPROVED),
                reference("chunk-2", "周杰伦-《晴天》演唱技巧.md", "/api/ai/knowledge/documents/chunk-2", INTERNAL_APPROVED)));

        assertThat(accumulator.snapshot()).singleElement()
                .extracting(AgentReference::id, AgentReference::title)
                .containsExactly("chunk-1", "周杰伦-《晴天》演唱技巧.md");
    }

    private AgentReference reference(String id, String title, String url,
                                     AgentReference.ReferenceStatus status) {
        return new AgentReference(id, title, url, "摘要", status, null);
    }
}
