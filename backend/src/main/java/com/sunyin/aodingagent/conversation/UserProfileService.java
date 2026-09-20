package com.sunyin.aodingagent.conversation;

import com.sunyin.aodingagent.conversation.ConversationModels.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 对LLM生成的画像候选做来源、字段和长度校验，再确定性地合并进Redis画像。 */
@Service
public final class UserProfileService {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "singingLevel", "preferredSingers", "preferredStyles", "comfortableRange",
            "dailyPracticeMinutes", "vocalProblems", "trainingGoals", "practicePreferences", "safetyNotes");
    private static final Set<String> COLLECTION_KEYS = Set.of(
            "preferredSingers", "preferredStyles", "vocalProblems", "trainingGoals", "practicePreferences", "safetyNotes");

    private final RedisConversationStore store;

    public UserProfileService(RedisConversationStore store) {
        this.store = store;
    }

    public UserProfile get(String userId) {
        return store.getProfile(userId);
    }

    public void clear(String userId) {
        store.clearProfile(userId);
    }

    public UserProfile merge(String userId, String conversationId,
                             List<ConversationMessage> sourceMessages,
                             List<ProfileCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return store.getProfile(userId);
        Map<String, ConversationMessage> usersById = new LinkedHashMap<>();
        sourceMessages.stream().filter(message -> message.role() == MessageRole.USER)
                .forEach(message -> usersById.put(message.messageId(), message));
        UserProfile current = store.getProfile(userId);
        Map<String, ProfileFact> facts = new LinkedHashMap<>(current.facts());
        int accepted = 0;
        for (ProfileCandidate candidate : candidates) {
            if (!valid(candidate, usersById)) continue;
            if (candidate.remove()) {
                facts.remove(candidate.key());
                accepted++;
                continue;
            }
            Object value = COLLECTION_KEYS.contains(candidate.key())
                    ? mergeCollection(facts.get(candidate.key()), candidate.value()) : candidate.value();
            facts.put(candidate.key(), new ProfileFact(value, limit(candidate.evidence(), 240),
                    conversationId, candidate.sourceMessageId(), Instant.now(), ProfileSourceType.USER_EXPLICIT));
            accepted++;
        }
        if (accepted == 0) return current;
        UserProfile updated = new UserProfile(userId, current.version() + 1, facts, Instant.now());
        store.saveProfile(updated);
        return updated;
    }

    private boolean valid(ProfileCandidate candidate, Map<String, ConversationMessage> usersById) {
        if (candidate == null || !candidate.explicitlyStated() || !ALLOWED_KEYS.contains(candidate.key())) return false;
        ConversationMessage source = usersById.get(candidate.sourceMessageId());
        if (source == null || candidate.evidence() == null || candidate.evidence().isBlank()
                || candidate.evidence().length() > 240) return false;
        if (!source.content().contains(candidate.evidence()) && candidate.evidence().length() > 20) return false;
        return candidate.remove() || candidate.value() != null;
    }

    private Object mergeCollection(ProfileFact existing, Object incoming) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (existing != null) addValues(values, existing.value());
        addValues(values, incoming);
        return new ArrayList<>(values).stream().limit(20).toList();
    }

    private void addValues(Set<String> target, Object value) {
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) if (item != null && !item.toString().isBlank()) target.add(limit(item.toString(), 80));
        } else if (value != null && !value.toString().isBlank()) {
            target.add(limit(value.toString(), 80));
        }
    }

    private String limit(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
