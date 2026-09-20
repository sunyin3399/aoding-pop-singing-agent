package com.sunyin.aodingagent.research;

public record AgentReference(
        String id,
        String title,
        String url,
        String excerpt,
        ReferenceStatus status,
        String domain
) {
    public enum ReferenceStatus {
        INTERNAL_APPROVED,
        SCRAPED,
        SEARCH_ONLY
    }
}
