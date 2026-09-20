package com.sunyin.aodingagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisChatMemoryTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisChatMemory memory;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        memory = new RedisChatMemory(redisTemplate, new ObjectMapper());
    }

    @Test
    void roundTripsConcreteMessageTypesThroughStableDto() {
        when(valueOperations.get(anyString())).thenReturn(null);
        memory.add("session-1", List.of(
                new UserMessage("练习高音", List.of(), Map.of("source", "web")),
                new AssistantMessage("先做气息练习")));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.eq("chat:memory:session-1"), json.capture(),
                org.mockito.ArgumentMatchers.eq(24L), org.mockito.ArgumentMatchers.eq(TimeUnit.HOURS));
        when(valueOperations.get("chat:memory:session-1")).thenReturn(json.getValue());

        List<Message> restored = memory.get("session-1", 10);

        assertThat(restored).hasSize(2);
        assertThat(restored.get(0)).isInstanceOf(UserMessage.class);
        assertThat(restored.get(0).getText()).isEqualTo("练习高音");
        assertThat(restored.get(0).getMetadata()).containsEntry("source", "web");
        assertThat(restored.get(1)).isInstanceOf(AssistantMessage.class);
    }

    @Test
    void migratesLegacyMessageTypeField() {
        when(valueOperations.get("chat:memory:legacy")).thenReturn("""
                [{"messageType":"USER","text":"旧问题","metadata":{}}]
                """);

        List<Message> restored = memory.get("legacy", 10);

        assertThat(restored).singleElement()
                .isInstanceOf(UserMessage.class)
                .extracting(Message::getText)
                .isEqualTo("旧问题");
    }

    @Test
    void deletesUnrecoverableLegacyValueOnlyForCurrentConversation() {
        when(valueOperations.get("chat:memory:broken")).thenReturn("[{\"text\":\"missing type\"}]");

        assertThat(memory.get("broken", 10)).isEmpty();

        verify(redisTemplate).delete("chat:memory:broken");
    }
}
