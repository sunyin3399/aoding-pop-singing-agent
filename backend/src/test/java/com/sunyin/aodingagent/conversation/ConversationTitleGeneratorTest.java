package com.sunyin.aodingagent.conversation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTitleGeneratorTest {

    private final ConversationTitleGenerator generator = new ConversationTitleGenerator();

    @Test
    void buildsStableTitleWithoutTestUserPrefixOrLlm() {
        assertThat(generator.generate("[测试用户：user-001]\n  调研适合初学者的歌曲  "))
                .isEqualTo("调研适合初学者的歌曲");
        assertThat(generator.generate("这是一个非常长的会话标题，需要在二十四个字符以后安全省略并保持列表整洁"))
                .endsWith("…").hasSize(25);
    }
}
