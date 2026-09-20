package com.sunyin.aodingagent.conversation;

import com.sunyin.aodingagent.conversation.ConversationModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationContextManagerTest {

    @Test
    void restoresRecentOriginalTurnsWithoutToolTrajectory() {
        RedisConversationStore store = mock(RedisConversationStore.class);
        UserProfileService profiles = mock(UserProfileService.class);
        when(profiles.get("user-001")).thenReturn(UserProfile.empty("user-001"));
        when(store.getDocument("user-001", ConversationMode.GENERAL_AGENT, "c1"))
                .thenReturn(document(shortMessages()));
        when(store.getSnapshot("user-001", ConversationMode.GENERAL_AGENT, "c1")).thenReturn(null);
        ConversationContextManager manager = manager(store, profiles, (old, messages, target) -> null, 6_000);

        PreparedConversationContext result = manager.prepare("user-001", ConversationMode.GENERAL_AGENT, "c1");

        assertThat(result.messages()).hasSize(2);
        assertThat(result.messages().get(0)).isInstanceOf(UserMessage.class);
        assertThat(result.messages().get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(result.messages()).extracting(message -> message.getText()).doesNotContain("工具轨迹");
    }

    @Test
    void compactsOlderMessagesAndKeepsRecentThreeTurns() {
        RedisConversationStore store = mock(RedisConversationStore.class);
        UserProfileService profiles = mock(UserProfileService.class);
        when(profiles.get("user-001")).thenReturn(UserProfile.empty("user-001"));
        List<ConversationMessage> messages = longMessages();
        when(store.getDocument("user-001", ConversationMode.GENERAL_AGENT, "c1")).thenReturn(document(messages));
        when(store.getSnapshot("user-001", ConversationMode.GENERAL_AGENT, "c1")).thenReturn(null);
        ConversationCompactor compactor = (old, input, target) -> new ContextCompactionResult(
                new StructuredConversationSummary(List.of("练好高音"), List.of(), List.of(), List.of(), List.of(), "较早对话摘要"),
                List.of());
        ConversationContextManager manager = manager(store, profiles, compactor, 100);

        PreparedConversationContext result = manager.prepare("user-001", ConversationMode.GENERAL_AGENT, "c1");

        assertThat(result.compacted()).isTrue();
        assertThat(result.messages()).anyMatch(message -> message.getText().contains("较早对话摘要"));
        verify(store).saveSnapshot(eq("user-001"), eq(ConversationMode.GENERAL_AGENT), any());
        verify(profiles).merge(eq("user-001"), eq("c1"), any(), any());
    }

    private ConversationContextManager manager(RedisConversationStore store, UserProfileService profiles,
                                               ConversationCompactor compactor, int threshold) {
        ConversationProperties properties = new ConversationProperties();
        properties.getContext().setCompactTriggerTokens(threshold);
        properties.getContext().setSummaryTargetTokens(20);
        properties.getContext().setRecentRawTokenBudget(4_000);
        return new ConversationContextManager(store, properties, new ConversationTokenEstimator(), compactor, profiles);
    }

    private ConversationDocument document(List<ConversationMessage> messages) {
        return new ConversationDocument(new ConversationMetadata("c1", "user-001",
                ConversationMode.GENERAL_AGENT, "标题", Instant.now(), Instant.now(), messages.size()), messages);
    }

    private List<ConversationMessage> shortMessages() {
        return List.of(message("u1", MessageRole.USER, "高音怎么练", 1),
                message("a1", MessageRole.ASSISTANT, "先稳定气息", 2));
    }

    private List<ConversationMessage> longMessages() {
        List<ConversationMessage> result = new ArrayList<>();
        for (int turn = 1; turn <= 6; turn++) {
            result.add(message("u" + turn, MessageRole.USER, "用户问题" + "练习".repeat(30), turn * 2L - 1));
            result.add(message("a" + turn, MessageRole.ASSISTANT, "回答内容" + "气息".repeat(30), turn * 2L));
        }
        return result;
    }

    private ConversationMessage message(String id, MessageRole role, String content, long sequence) {
        return new ConversationMessage(id, role, content, List.of(), Instant.now(), sequence);
    }
}
