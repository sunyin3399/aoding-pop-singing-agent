package com.sunyin.aodingagent.conversation;

import com.sunyin.aodingagent.research.AgentReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 会话历史、上下文摘要和用户画像使用的数据结构。
 * <p>
 * Redis 保存完整可展示消息；模型上下文摘要和画像是可重新生成的派生数据，不能覆盖原文。
 */
public final class ConversationModels {

    private ConversationModels() {
    }

    public enum ConversationMode { VOCAL_COACH, GENERAL_AGENT }

    public enum MessageRole { USER, ASSISTANT }

    public record ConversationMetadata(
            String conversationId, String userId, ConversationMode mode, String title,
            Instant createdAt, Instant lastActiveAt, int messageCount, long remainingSeconds
    ) {
        public ConversationMetadata(String conversationId, String userId, ConversationMode mode, String title,
                                    Instant createdAt, Instant lastActiveAt, int messageCount) {
            this(conversationId, userId, mode, title, createdAt, lastActiveAt, messageCount, -1);
        }
    }

    public record ConversationMessage(
            String messageId, MessageRole role, String content,
            List<AgentReference> references, Instant createdAt, long sequence,
            String artifactType, JsonNode artifactPayload, String artifactSummary
    ) {
        public ConversationMessage {
            references = references == null ? List.of() : List.copyOf(references);
        }

        public ConversationMessage(String messageId, MessageRole role, String content,
                                   List<AgentReference> references, Instant createdAt, long sequence) {
            this(messageId, role, content, references, createdAt, sequence, null, null, null);
        }
    }

    public record ConversationDocument(
            ConversationMetadata metadata, List<ConversationMessage> messages
    ) {
        public ConversationDocument {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    public record ConversationDetail(
            ConversationMetadata metadata, List<ConversationMessage> messages
    ) {
        public ConversationDetail {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    public record StructuredConversationSummary(
            List<String> goals, List<String> confirmedFacts, List<String> decisions,
            List<String> constraints, List<String> openQuestions, String conversationSummary
    ) {
        public StructuredConversationSummary {
            goals = safe(goals);
            confirmedFacts = safe(confirmedFacts);
            decisions = safe(decisions);
            constraints = safe(constraints);
            openQuestions = safe(openQuestions);
            conversationSummary = conversationSummary == null ? "" : conversationSummary;
        }

        public static StructuredConversationSummary empty() {
            return new StructuredConversationSummary(List.of(), List.of(), List.of(), List.of(), List.of(), "");
        }

        private static List<String> safe(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }

    public record ConversationContextSnapshot(
            String conversationId, long coveredThroughSequence,
            StructuredConversationSummary summary, Instant updatedAt, int version
    ) {
    }

    public enum ProfileSourceType { USER_EXPLICIT }

    public record ProfileFact(
            Object value, String evidence, String sourceConversationId, String sourceMessageId,
            Instant updatedAt, ProfileSourceType sourceType
    ) {
    }

    public record UserProfile(
            String userId, int version, Map<String, ProfileFact> facts, Instant updatedAt
    ) {
        public UserProfile {
            facts = facts == null ? Map.of() : Map.copyOf(facts);
        }

        public static UserProfile empty(String userId) {
            return new UserProfile(userId, 0, Map.of(), Instant.EPOCH);
        }
    }

    public record ProfileCandidate(
            String key, Object value, String evidence, String sourceMessageId,
            boolean explicitlyStated, boolean remove
    ) {
    }

    public record ContextCompactionResult(
            StructuredConversationSummary summary, List<ProfileCandidate> profileCandidates
    ) {
        public ContextCompactionResult {
            summary = summary == null ? StructuredConversationSummary.empty() : summary;
            profileCandidates = profileCandidates == null ? List.of() : List.copyOf(profileCandidates);
        }
    }

    public record PreparedConversationContext(
            List<org.springframework.ai.chat.messages.Message> messages,
            int estimatedTokens, boolean compacted
    ) {
        public PreparedConversationContext {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    public record ConversationStarted(
            String conversationId, String title, Instant createdAt
    ) {
    }
}
