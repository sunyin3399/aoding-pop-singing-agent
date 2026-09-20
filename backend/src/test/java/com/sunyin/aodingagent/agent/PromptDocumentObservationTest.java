package com.sunyin.aodingagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.metrics.LlmMetricsTracker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromptDocumentObservationTest {
    static class Agent extends ToolCallAgent {
        final Map<String, JsonNode> observed = new LinkedHashMap<>();
        Agent() { super(new ToolCallback[0], mock(LlmMetricsTracker.class)); setSystemPrompt("system"); }
        @Override protected void emitLiveEvent(String name, String data) {
            try { observed.put(name, new ObjectMapper().readTree(data)); } catch (Exception e) { throw new AssertionError(e); }
        }
    }
    static ToolResponseMessage tool(String id, String data) {
        return new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id, "searchVocalKnowledge", data)));
    }
    static String result(String id) {
        return "{\"success\":true,\"data\":{\"status\":\"FOUND\",\"citations\":[{\"id\":\"" + id + "\",\"excerpt\":\"private evidence\"}]}}";
    }
    @Test void invalidTruncatedOrFailedToolPayloadIsUnknownNeverGuessedFromVisibleIds() {
        var inputs = List.of(
                com.sunyin.aodingagent.tools.ToolOutputLimiter.limit(result("visible").replace("private evidence", "x".repeat(13000))),
                result("visible") + " trailing",
                result("visible").replace("true", "false"),
                "{\"data\":{\"status\":\"NO_KNOWLEDGE_HIT\",\"citations\":[]}}",
                result("sk-secret"));
        for (int i = 0; i < inputs.size(); i++) {
            var agent = runWithContent(inputs.get(i));
            assertEquals("unavailable", agent.observed.get("eval_prompt_documents").path("promptDocumentIds").asText(), "invalid payload index " + i);
        }
    }
    static Agent runWithContent(String content) {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));
        var agent = new Agent();
        agent.setChatClient(ChatClient.builder(model).build());
        agent.setMessageList(new ArrayList<>(List.of(tool("call", content))));
        agent.think();
        verify(model).call(any(Prompt.class));
        return agent;
    }

    @Test void explicitNoHitHasNoPromptDocumentsAndObserverFailuresDoNotPreventModelCall() {
        var agent = runWithContent("{\"success\":false,\"data\":{\"status\":\"NO_KNOWLEDGE_HIT\",\"citations\":[]}}");
        assertTrue(agent.observed.get("eval_prompt_documents").path("promptDocumentIds").isArray());
        assertTrue(agent.observed.get("eval_prompt_documents").path("promptDocumentIds").isEmpty());
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));
        ChatClient.builder(model).build().prompt("q").advisors(
                new com.sunyin.aodingagent.evaluation.PromptDocumentObservationAdvisor((name, data) -> { throw new IllegalStateException(); }))
                .call().chatResponse();
        verify(model).call(any(Prompt.class));
    }

    @Test void laterMutatingAdvisorMakesObservationUnknownRatherThanRecordingRemovedIds() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));
        CallAroundAdvisor late = new CallAroundAdvisor() {
            public String getName() { return "late prune"; }
            public int getOrder() { return Integer.MAX_VALUE - 1; }
            public AdvisedResponse aroundCall(AdvisedRequest r, CallAroundAdvisorChain chain) {
                return chain.nextAroundCall(AdvisedRequest.from(r).userText("q").messages(List.of()).build());
            }
        };
        var agent = new Agent();
        agent.setChatClient(ChatClient.builder(model).defaultAdvisors(late).build());
        agent.setMessageList(new ArrayList<>(List.of(tool("call", result("removed")))));
        agent.think();
        assertEquals("unavailable", agent.observed.get("eval_prompt_documents").path("promptDocumentIds").asText());
    }

    @Test void observesOnlyFinalToolMessagesAfterContextPruningAtModelDispatch() throws Exception {
        var model = mock(ChatModel.class);
        var sent = new ArrayList<Prompt>();
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            sent.add(call.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));
        });
        CallAroundAdvisor prune = new CallAroundAdvisor() {
            public String getName() { return "test context pruning"; }
            public int getOrder() { return 200; }
            public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
                return chain.nextAroundCall(AdvisedRequest.from(request).messages(List.of(tool("kept-call", result("kept")))).build());
            }
        };
        var agent = new Agent();
        agent.setChatClient(ChatClient.builder(model).defaultAdvisors(prune).build());
        agent.setMessageList(new ArrayList<>(List.of(tool("removed-call", result("removed")), tool("kept-call", result("kept")))));
        agent.think();
        assertEquals(1, sent.size());
        assertFalse(sent.getFirst().toString().contains("removed"));
        assertTrue(agent.observed.containsKey("eval_prompt_documents"), "final model dispatch observation missing");
        assertEquals(List.of("kept"), new ObjectMapper().convertValue(
                agent.observed.get("eval_prompt_documents").path("promptDocumentIds"), List.class));
        assertFalse(agent.observed.toString().contains("private evidence"));
    }
}
