package com.sunyin.aodingagent.conversation;

import com.sunyin.aodingagent.conversation.ConversationModels.*;
import com.sunyin.aodingagent.research.AgentReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** 为两条聊天链路统一准备会话身份、模型上下文，并在成功后保存完整可展示结果。 */
@Service
public final class ConversationTurnCoordinator {

    private final RedisConversationStore store;
    private final ConversationContextManager contextManager;

    public ConversationTurnCoordinator(RedisConversationStore store, ConversationContextManager contextManager) {
        this.store = store;
        this.contextManager = contextManager;
    }

    public ConversationTurn begin(String userId, ConversationMode mode,
                                  String conversationId, String userMessage) {
        if (conversationId == null || conversationId.isBlank()) {
            ConversationStarted started = store.prepareNew(userId, mode, userMessage);
            PreparedConversationContext context = contextManager.prepare(userId, mode, null);
            return new ConversationTurn(started, null, context);
        }
        ConversationDetail existing = store.get(userId, mode, conversationId);
        ConversationStarted identity = new ConversationStarted(existing.metadata().conversationId(),
                existing.metadata().title(), existing.metadata().createdAt());
        return new ConversationTurn(identity, conversationId,
                contextManager.prepare(userId, mode, conversationId));
    }

    public ConversationMetadata complete(ConversationTurn turn, String userId, ConversationMode mode,
                                         String userMessage, String assistantMessage,
                                         List<AgentReference> references) {
        return store.appendTurn(userId, mode, turn.started(), turn.existingConversationId(),
                userMessage, assistantMessage, references);
    }

    public ConversationMetadata completeWithArtifact(ConversationTurn turn, String userId, ConversationMode mode,
                                                       String userMessage, String assistantMessage,
                                                       List<AgentReference> references, String artifactType,
                                                       JsonNode artifactPayload, String artifactSummary) {
        return store.appendTurn(userId, mode, turn.started(), turn.existingConversationId(),
                userMessage, assistantMessage, references, artifactType, artifactPayload, artifactSummary);
    }

    public record ConversationTurn(ConversationStarted started, String existingConversationId,
                                   PreparedConversationContext context) {
        public String conversationId() { return started.conversationId(); }
    }
}
