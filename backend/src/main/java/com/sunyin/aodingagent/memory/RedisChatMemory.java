package com.sunyin.aodingagent.memory;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class RedisChatMemory implements ChatMemory {

    private static final String PREFIX = "chat:memory:";
    private static final long TTL_HOURS = 24;
    private static final TypeReference<List<StoredMessage>> STORED_MESSAGE_LIST = new TypeReference<>() { };

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatMemory(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        String key = key(conversationId);
        List<Message> existingMessages = get(conversationId, Integer.MAX_VALUE);
        existingMessages.addAll(messages);
        try {
            List<StoredMessage> storedMessages = existingMessages.stream()
                    .map(StoredMessage::fromMessage)
                    .toList();
            stringRedisTemplate.opsForValue().set(
                    key, objectMapper.writeValueAsString(storedMessages), TTL_HOURS, TimeUnit.HOURS);
            log.debug("对话记忆已保存到 Redis: {}", conversationId);
        } catch (JsonProcessingException exception) {
            log.error("序列化消息失败: conversationId={}", conversationId, exception);
            throw new IllegalStateException("序列化消息失败", exception);
        }
    }

    @Override
    public List<Message> get(String conversationId, int size) {
        if (size <= 0) return new ArrayList<>();
        String key = key(conversationId);
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) return new ArrayList<>();

        try {
            List<Message> allMessages = objectMapper.readValue(json, STORED_MESSAGE_LIST).stream()
                    .map(StoredMessage::toMessage)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            int fromIndex = Math.max(0, allMessages.size() - size);
            return new ArrayList<>(allMessages.subList(fromIndex, allMessages.size()));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            // 旧版本把抽象 Message 直接写入 Redis，某些数据无法安全迁移。
            // 删除当前会话的损坏记录，避免后续每轮都重复反序列化失败。
            stringRedisTemplate.delete(key);
            log.warn("已删除无法迁移的旧版对话记忆: conversationId={}", conversationId, exception);
            return new ArrayList<>();
        }
    }

    @Override
    public void clear(String conversationId) {
        stringRedisTemplate.delete(key(conversationId));
        log.debug("对话记忆已清除: {}", conversationId);
    }

    public List<String> getAllConversationIds() {
        Set<String> keys = stringRedisTemplate.keys(PREFIX + "*");
        if (keys == null) return List.of();
        return keys.stream().map(key -> key.substring(PREFIX.length())).toList();
    }

    private String key(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        return PREFIX + conversationId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredMessage(
            @JsonAlias("messageType") String type,
            String text,
            Map<String, Object> metadata,
            List<StoredToolCall> toolCalls,
            List<StoredToolResponse> toolResponses
    ) {
        static StoredMessage fromMessage(Message message) {
            List<StoredToolCall> calls = message instanceof AssistantMessage assistant
                    ? assistant.getToolCalls().stream().map(StoredToolCall::fromToolCall).toList()
                    : List.of();
            List<StoredToolResponse> responses = message instanceof ToolResponseMessage tool
                    ? tool.getResponses().stream().map(StoredToolResponse::fromToolResponse).toList()
                    : List.of();
            return new StoredMessage(
                    message.getMessageType().name(), message.getText(), message.getMetadata(), calls, responses);
        }

        Message toMessage() {
            if (type == null || type.isBlank()) throw new IllegalArgumentException("缺少消息类型");
            MessageType messageType = MessageType.valueOf(type.toUpperCase(java.util.Locale.ROOT));
            Map<String, Object> safeMetadata = metadata == null ? Map.of() : metadata;
            String safeText = text == null ? "" : text;
            return switch (messageType) {
                case USER -> new UserMessage(safeText, List.of(), safeMetadata);
                case ASSISTANT -> new AssistantMessage(safeText, safeMetadata,
                        safeList(toolCalls).stream().map(StoredToolCall::toToolCall).toList());
                case SYSTEM -> new SystemMessage(safeText);
                case TOOL -> new ToolResponseMessage(
                        safeList(toolResponses).stream().map(StoredToolResponse::toToolResponse).toList(),
                        safeMetadata);
            };
        }

        private static <T> List<T> safeList(List<T> values) {
            return values == null ? List.of() : values;
        }
    }

    record StoredToolCall(String id, String type, String name, String arguments) {
        static StoredToolCall fromToolCall(AssistantMessage.ToolCall call) {
            return new StoredToolCall(call.id(), call.type(), call.name(), call.arguments());
        }

        AssistantMessage.ToolCall toToolCall() {
            return new AssistantMessage.ToolCall(id, type, name, arguments);
        }
    }

    record StoredToolResponse(String id, String name, String responseData) {
        static StoredToolResponse fromToolResponse(ToolResponseMessage.ToolResponse response) {
            return new StoredToolResponse(response.id(), response.name(), response.responseData());
        }

        ToolResponseMessage.ToolResponse toToolResponse() {
            return new ToolResponseMessage.ToolResponse(id, name, responseData);
        }
    }
}
