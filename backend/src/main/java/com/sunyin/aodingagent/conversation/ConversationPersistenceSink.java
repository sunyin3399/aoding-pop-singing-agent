package com.sunyin.aodingagent.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.conversation.ConversationModels.ConversationMode;
import com.sunyin.aodingagent.research.AgentReference;
import com.sunyin.aodingagent.stream.AgentEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 截获Agent最终正文和引用，在流正常完成时保存一轮对话。
 * step和工具事件只透传给前端，不进入Redis历史。
 */
public final class ConversationPersistenceSink implements AgentEventSink {

    private static final Logger logger = LoggerFactory.getLogger(ConversationPersistenceSink.class);
    private static final TypeReference<List<AgentReference>> REFERENCES = new TypeReference<>() { };

    private final AgentEventSink delegate;
    private final ConversationTurnCoordinator coordinator;
    private final ConversationTurnCoordinator.ConversationTurn turn;
    private final ObjectMapper objectMapper;
    private final String userId;
    private final ConversationMode mode;
    private final String userMessage;
    private String finalAnswer;
    private List<AgentReference> references = List.of();

    public ConversationPersistenceSink(AgentEventSink delegate, ConversationTurnCoordinator coordinator,
                                       ConversationTurnCoordinator.ConversationTurn turn, ObjectMapper objectMapper,
                                       String userId, ConversationMode mode, String userMessage) {
        this.delegate = delegate;
        this.coordinator = coordinator;
        this.turn = turn;
        this.objectMapper = objectMapper;
        this.userId = userId;
        this.mode = mode;
        this.userMessage = userMessage;
    }

    @Override
    public void emit(String event, String data) {
        if ("final".equals(event)) finalAnswer = data;
        if ("references".equals(event)) {
            try {
                references = objectMapper.readValue(data, REFERENCES);
            } catch (Exception exception) {
                logger.warn("无法解析本轮参考资料，历史正文仍将保存: conversationId={}", turn.conversationId());
            }
        }
        delegate.emit(event, data);
    }

    @Override
    public boolean isCancelled() { return delegate.isCancelled(); }

    @Override
    public void complete() {
        if (!isCancelled() && finalAnswer != null && !finalAnswer.isBlank()) {
            try {
                coordinator.complete(turn, userId, mode, userMessage, finalAnswer, references);
            } catch (RuntimeException exception) {
                // Redis历史失败不能把已经生成的回答改成聊天失败。
                logger.error("Agent回答已生成，但会话历史保存失败: conversationId={}", turn.conversationId(), exception);
            }
        }
        delegate.complete();
    }

    @Override
    public void fail(Throwable error) { delegate.fail(error); }
}
