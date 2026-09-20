package com.sunyin.aodingagent.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sunyin.aodingagent.conversation.ConversationModels.*;
import com.sunyin.aodingagent.research.AgentReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 新版多会话的Redis存储。
 * <p>
 * Sorted Set只负责按用户和模式列出会话；正文、摘要和画像使用独立JSON键。所有公开方法都验证
 * userId和mode，禁止通过猜测conversationId读取其他测试用户的数据。
 */
@Component
public final class RedisConversationStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisConversationStore.class);
    private static final String INDEX_PREFIX = "conversation:index:";
    private static final String DATA_PREFIX = "conversation:data:";
    private static final String CONTEXT_PREFIX = "conversation:context:";
    private static final String PROFILE_PREFIX = "user:profile:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ConversationProperties properties;
    private final ConversationTitleGenerator titleGenerator;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public RedisConversationStore(StringRedisTemplate redis, ObjectMapper objectMapper,
                                  ConversationProperties properties, ConversationTitleGenerator titleGenerator) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.titleGenerator = titleGenerator;
    }

    /** 生成尚未写入Redis的新会话身份；只有成功完成首轮回答后才调用appendTurn持久化。 */
    public ConversationStarted prepareNew(String userId, ConversationMode mode, String firstMessage) {
        validateIdentity(userId, mode);
        return new ConversationStarted(UUID.randomUUID().toString(), titleGenerator.generate(firstMessage), Instant.now());
    }

    public List<ConversationMetadata> list(String userId, ConversationMode mode) {
        validateIdentity(userId, mode);
        String indexKey = indexKey(userId, mode);
        Set<String> ids = redis.opsForZSet().reverseRange(indexKey, 0, -1);
        if (ids == null || ids.isEmpty()) return List.of();
        List<ConversationMetadata> result = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (String id : ids) {
            ConversationDocument document = readDocument(id);
            if (document == null || !ownedBy(document, userId, mode)) stale.add(id);
            else {
                Long ttl = redis.getExpire(dataKey(id));
                result.add(withTtl(document.metadata(), ttl == null ? -1 : ttl));
            }
        }
        if (!stale.isEmpty()) redis.opsForZSet().remove(indexKey, stale.toArray());
        refreshIndex(indexKey);
        return result.stream().sorted(Comparator.comparing(ConversationMetadata::lastActiveAt).reversed())
                .limit(properties.getMaxSessionsPerUserMode()).toList();
    }

    public ConversationDetail get(String userId, ConversationMode mode, String conversationId) {
        ConversationDocument document = requireOwned(userId, mode, conversationId);
        return new ConversationDetail(document.metadata(), document.messages());
    }

    public void delete(String userId, ConversationMode mode, String conversationId) {
        requireOwned(userId, mode, conversationId);
        redis.delete(List.of(dataKey(conversationId), contextKey(conversationId)));
        redis.opsForZSet().remove(indexKey(userId, mode), conversationId);
    }

    public ConversationDocument getDocument(String userId, ConversationMode mode, String conversationId) {
        return requireOwned(userId, mode, conversationId);
    }

    /**
     * 原子边界集中在同一服务方法中。当前进程内锁适合单实例；多实例部署应升级为Lua脚本。
     */
    public ConversationMetadata appendTurn(String userId, ConversationMode mode,
                                               ConversationStarted started, String conversationId,
                                               String userMessage, String assistantMessage,
                                               List<AgentReference> references) {
        return appendTurn(userId, mode, started, conversationId, userMessage, assistantMessage,
                references, null, null, null);
    }

    public ConversationMetadata appendTurn(String userId, ConversationMode mode,
                                               ConversationStarted started, String conversationId,
                                               String userMessage, String assistantMessage,
                                               List<AgentReference> references, String artifactType,
                                               JsonNode artifactPayload, String artifactSummary) {
        validateIdentity(userId, mode);
        String id = conversationId == null || conversationId.isBlank() ? started.conversationId() : conversationId;
        Object lock = locks.computeIfAbsent(id, ignored -> new Object());
        synchronized (lock) {
            ConversationDocument existing = readDocument(id);
            Instant now = Instant.now();
            ConversationMetadata metadata;
            List<ConversationMessage> messages = new ArrayList<>();
            if (existing == null) {
                if (started == null || !id.equals(started.conversationId())) throw new ConversationNotFoundException();
                metadata = new ConversationMetadata(id, userId, mode, started.title(),
                        started.createdAt(), now, 0);
            } else {
                if (!ownedBy(existing, userId, mode)) throw new ConversationNotFoundException();
                metadata = existing.metadata();
                messages.addAll(existing.messages());
            }
            long nextSequence = messages.isEmpty() ? 1 : messages.getLast().sequence() + 1;
            messages.add(new ConversationMessage(UUID.randomUUID().toString(), MessageRole.USER,
                    limit(userMessage), List.of(), now, nextSequence));
            messages.add(new ConversationMessage(UUID.randomUUID().toString(), MessageRole.ASSISTANT,
                    limit(assistantMessage), references, now, nextSequence + 1,
                    artifactType, artifactPayload, limit(artifactSummary)));
            ConversationMetadata updated = new ConversationMetadata(id, userId, mode, metadata.title(),
                    metadata.createdAt(), now, messages.size());
            write(dataKey(id), new ConversationDocument(updated, messages), conversationTtl());
            String indexKey = indexKey(userId, mode);
            redis.opsForZSet().add(indexKey, id, now.toEpochMilli());
            refreshIndex(indexKey);
            evictOldest(userId, mode);
            logger.info("会话已保存: conversationId={}, userId={}, mode={}, messageCount={}",
                    id, userId, mode, messages.size());
            return updated;
        }
    }

    public ConversationContextSnapshot getSnapshot(String userId, ConversationMode mode, String conversationId) {
        requireOwned(userId, mode, conversationId);
        return read(contextKey(conversationId), ConversationContextSnapshot.class);
    }

    public void saveSnapshot(String userId, ConversationMode mode, ConversationContextSnapshot snapshot) {
        requireOwned(userId, mode, snapshot.conversationId());
        write(contextKey(snapshot.conversationId()), snapshot, conversationTtl());
    }

    public UserProfile getProfile(String userId) {
        validateUserId(userId);
        UserProfile profile = read(profileKey(userId), UserProfile.class);
        if (profile == null) return UserProfile.empty(userId);
        redis.expire(profileKey(userId), profileTtl());
        return profile;
    }

    public void saveProfile(UserProfile profile) {
        validateUserId(profile.userId());
        write(profileKey(profile.userId()), profile, profileTtl());
    }

    public void clearProfile(String userId) {
        validateUserId(userId);
        redis.delete(profileKey(userId));
    }

    private void evictOldest(String userId, ConversationMode mode) {
        String indexKey = indexKey(userId, mode);
        Long size = redis.opsForZSet().size(indexKey);
        int maximum = properties.getMaxSessionsPerUserMode();
        if (size == null || size <= maximum) return;
        Set<String> oldest = redis.opsForZSet().range(indexKey, 0, size - maximum - 1);
        if (oldest == null) return;
        for (String id : oldest) {
            redis.delete(List.of(dataKey(id), contextKey(id)));
            redis.opsForZSet().remove(indexKey, id);
            logger.info("会话达到上限，已淘汰最旧会话: conversationId={}, userId={}, mode={}", id, userId, mode);
        }
    }

    private ConversationDocument requireOwned(String userId, ConversationMode mode, String conversationId) {
        validateIdentity(userId, mode);
        if (conversationId == null || conversationId.isBlank()) throw new ConversationNotFoundException();
        ConversationDocument document = readDocument(conversationId);
        if (document == null || !ownedBy(document, userId, mode)) throw new ConversationNotFoundException();
        return document;
    }

    private boolean ownedBy(ConversationDocument document, String userId, ConversationMode mode) {
        return document.metadata() != null && userId.equals(document.metadata().userId())
                && mode == document.metadata().mode();
    }

    private ConversationMetadata withTtl(ConversationMetadata metadata, long ttl) {
        return new ConversationMetadata(metadata.conversationId(), metadata.userId(), metadata.mode(), metadata.title(),
                metadata.createdAt(), metadata.lastActiveAt(), metadata.messageCount(), ttl);
    }

    private void refreshIndex(String key) {
        redis.expire(key, conversationTtl());
    }

    private ConversationDocument readDocument(String id) {
        return read(dataKey(id), ConversationDocument.class);
    }

    private <T> T read(String key, Class<T> type) {
        String json = redis.opsForValue().get(key);
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            logger.warn("Redis会话数据无法解析，已删除损坏键: key={}", key, exception);
            redis.delete(key);
            return null;
        }
    }

    private void write(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("会话数据序列化失败", exception);
        }
    }

    private String limit(String value) {
        String safe = value == null ? "" : value;
        return safe.length() <= properties.getMaxMessageChars()
                ? safe : safe.substring(0, properties.getMaxMessageChars());
    }

    private void validateIdentity(String userId, ConversationMode mode) {
        validateUserId(userId);
        if (mode == null) throw new IllegalArgumentException("mode不能为空");
    }

    private void validateUserId(String userId) {
        if (userId == null || !userId.matches("user-00[1-3]")) {
            throw new IllegalArgumentException("不支持的用户ID");
        }
    }

    private Duration conversationTtl() { return Duration.ofDays(properties.getRetentionDays()); }
    private Duration profileTtl() { return Duration.ofDays(properties.getProfileRetentionDays()); }
    private String indexKey(String userId, ConversationMode mode) { return INDEX_PREFIX + userId + ":" + mode.name(); }
    private String dataKey(String id) { return DATA_PREFIX + id; }
    private String contextKey(String id) { return CONTEXT_PREFIX + id; }
    private String profileKey(String userId) { return PROFILE_PREFIX + userId; }
}
