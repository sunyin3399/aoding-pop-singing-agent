package com.sunyin.aodingagent.conversation;

import com.sunyin.aodingagent.conversation.ConversationModels.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProfileServiceTest {

    @Test
    void acceptsOnlyExplicitWhitelistedFactsFromRealUserMessages() {
        RedisConversationStore store = mock(RedisConversationStore.class);
        when(store.getProfile("user-001")).thenReturn(UserProfile.empty("user-001"));
        UserProfileService service = new UserProfileService(store);
        ConversationMessage user = new ConversationMessage("m1", MessageRole.USER,
                "我是初学者，喜欢林俊杰", List.of(), Instant.now(), 1);
        ConversationMessage assistant = new ConversationMessage("m2", MessageRole.ASSISTANT,
                "你可能每天能练一小时", List.of(), Instant.now(), 2);

        UserProfile result = service.merge("user-001", "c1", List.of(user, assistant), List.of(
                new ProfileCandidate("singingLevel", "初学者", "我是初学者", "m1", true, false),
                new ProfileCandidate("dailyPracticeMinutes", 60, "每天能练一小时", "m2", true, false),
                new ProfileCandidate("unknownField", "值", "我是初学者", "m1", true, false)));

        assertThat(result.facts()).containsOnlyKeys("singingLevel");
        assertThat(result.facts().get("singingLevel").sourceMessageId()).isEqualTo("m1");
        verify(store).saveProfile(result);
    }
}
