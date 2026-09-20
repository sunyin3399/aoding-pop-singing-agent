package com.sunyin.aodingagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolCallAgentTraceTest {
    static class ObservedAgent extends ToolCallAgent {
        final List<String> names = new ArrayList<>();
        final List<JsonNode> events = new ArrayList<>();
        ObservedAgent(ToolCallback callback) { super(new ToolCallback[]{callback}, null); }
        @Override protected void emitLiveEvent(String event, String data) {
            try { names.add(event); events.add(new ObjectMapper().readTree(data)); }
            catch (Exception e) { throw new AssertionError(e); }
        }
    }
    ToolCallback callback(java.util.function.Supplier<String> output) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("fixtureTool").description("fixture").inputSchema("{}").build();
            }
            public String call(String input) { return output.get(); }
        };
    }
    @Test void actUsesObservedCallbacksWithTheRealToolManager() {
        var agent = new ObservedAgent(callback(() -> "ok"));
        var output = new org.springframework.ai.chat.messages.AssistantMessage("", Map.of(),
                List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("provider-id", "function", "fixtureTool", "{}")));
        agent.setToolCallChatResponse(new org.springframework.ai.chat.model.ChatResponse(
                List.of(new org.springframework.ai.chat.model.Generation(output))));
        agent.act();
        assertEquals(List.of("tool_start", "tool_result"), agent.names);
        assertTrue(agent.events.get(1).path("success").asBoolean());
    }

    @Test void observesRealInvocationWithoutCopyingArgumentsOrOutput() {
        var agent = new ObservedAgent(callback(() -> "private output C:/private sk-fixture"));
        assertEquals("private output C:/private sk-fixture", agent.getAvailableTools()[0].call("private input"));
        assertEquals(List.of("tool_start", "tool_result"), agent.names);
        var start = agent.events.get(0); var result = agent.events.get(1);
        assertEquals(start.path("callId"), result.path("callId"));
        assertFalse(start.path("callId").asText().isBlank());
        assertEquals("fixtureTool", result.path("toolName").asText());
        assertTrue(result.path("success").asBoolean());
        assertTrue(result.path("elapsedMs").asLong(-1) >= 0);
        assertFalse(result.path("outputTruncated").asBoolean(true));
        assertFalse(agent.events.toString().contains("private"));
        assertFalse(agent.events.toString().contains("sk-fixture"));
    }
}
