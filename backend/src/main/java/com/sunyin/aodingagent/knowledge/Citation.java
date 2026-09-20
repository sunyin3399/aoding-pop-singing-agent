package com.sunyin.aodingagent.knowledge;

public record Citation(
        String id,
        CitationType type,
        String title,
        String url,
        String excerpt,
        Double relevance
) {
    public enum CitationType {
        INTERNAL_KNOWLEDGE,
        EXTERNAL_WEB
    }
}
