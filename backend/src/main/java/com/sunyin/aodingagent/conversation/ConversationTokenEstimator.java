package com.sunyin.aodingagent.conversation;

import org.springframework.stereotype.Component;

/**
 * 模型无关的保守Token估算器。
 * <p>
 * 中文按接近一字一Token、ASCII按约四字符一Token估算，并增加少量消息格式开销。它不是计费
 * Tokenizer，因此上下文构建器仍保留缓冲；以后可以在不改业务流程的情况下替换实现。
 */
@Component
public final class ConversationTokenEstimator {

    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0;
        int other = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN) cjk++;
            else other++;
        }
        return cjk + (int) Math.ceil(other / 4.0) + 4;
    }
}
