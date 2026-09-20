package com.sunyin.aodingagent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.conversation.ConversationModels.*;
import com.sunyin.aodingagent.stream.AgentEventSink;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationPersistenceSinkTest {

    @Test
    void persistsOnlyFinalAnswerAndReferencesNotStepTrajectory() {
        AgentEventSink delegate = mock(AgentEventSink.class);
        when(delegate.isCancelled()).thenReturn(false);
        ConversationTurnCoordinator coordinator = mock(ConversationTurnCoordinator.class);
        ConversationTurnCoordinator.ConversationTurn turn = new ConversationTurnCoordinator.ConversationTurn(
                new ConversationStarted("c1", "标题", Instant.now()), null,
                new PreparedConversationContext(List.of(), 0, false));
        ConversationPersistenceSink sink = new ConversationPersistenceSink(delegate, coordinator, turn,
                new ObjectMapper(), "user-001", ConversationMode.GENERAL_AGENT, "用户问题");

        sink.emit("step", "工具轨迹");
        sink.emit("final", "最终回复");
        sink.emit("references", "[]");
        sink.complete();

        verify(coordinator).complete(eq(turn), eq("user-001"), eq(ConversationMode.GENERAL_AGENT),
                eq("用户问题"), eq("最终回复"), eq(List.of()));
        verify(delegate).emit("step", "工具轨迹");
        verify(delegate).complete();
    }
}
