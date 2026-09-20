package com.sunyin.aodingagent.research;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public record WebSearchResult(
        boolean success,
        String query,
        List<SearchItem> items,
        String errorCode,
        String error
) {
    public WebSearchResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static WebSearchResult success(String query, List<SearchItem> items) {
        return new WebSearchResult(true, query, items, null, null);
    }

    public static WebSearchResult failure(String query, String errorCode, String error) {
        return new WebSearchResult(false, query, List.of(), errorCode, error);
    }

    public List<AgentReference> toReferences() {
        return items.stream()
                .filter(item -> validHttpUrl(item.url()) && item.title() != null && !item.title().isBlank())
                .map(item -> new AgentReference(
                        UUID.nameUUIDFromBytes(item.url().getBytes(StandardCharsets.UTF_8)).toString(),
                        item.title(), item.url(), item.snippet(),
                        AgentReference.ReferenceStatus.SEARCH_ONLY, URI.create(item.url()).getHost()))
                .toList();
    }

    private boolean validHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public record SearchItem(String title, String url, String snippet) {
    }
}
