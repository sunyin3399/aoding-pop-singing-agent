package com.sunyin.aodingagent.conversation;

import org.springframework.stereotype.Component;

/** 根据首条用户消息生成稳定标题，不额外消耗一次LLM请求。 */
@Component
public final class ConversationTitleGenerator {

    private static final int MAX_TITLE_LENGTH = 24;

    public String generate(String message) {
        if (message == null || message.isBlank()) return "新会话";
        String title = message.replaceFirst("^\\[测试用户：[^]]+]\\s*", "")
                .replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").trim();
        if (title.isBlank()) return "新会话";
        return title.length() <= MAX_TITLE_LENGTH ? title : title.substring(0, MAX_TITLE_LENGTH) + "…";
    }
}
