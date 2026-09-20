package com.sunyin.aodingagent.agent;

import com.sunyin.aodingagent.metrics.LlmMetricsTracker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalKnowledgeSongRecommendationFlowTest {

    @Test
    void usesInternalKnowledgeOnceAndDoesNotStartExternalResearchWhenCoverageIsSufficient() {
        AtomicInteger internalCalls = new AtomicInteger();
        AtomicInteger externalCalls = new AtomicInteger();
        ToolCallback internal = callback("searchVocalKnowledge", () -> {
            internalCalls.incrementAndGet();
            return """
                    {"success":true,"data":{"citations":[
                      {"id":"songs","title":"初学者歌曲推荐","url":"/api/ai/knowledge/documents/songs","excerpt":"《歌曲A》适合平滑起音；《歌曲B》适合长句气息；《歌曲C》适合自然咬字；《歌曲D》适合轻声练习；《歌曲E》适合分段练习。出现疼痛或持续嘶哑应立即停止。"}
                    ]}}
                    """;
        });
        ToolCallback external = callback("researchExternal", () -> {
            externalCalls.incrementAndGet();
            return "unexpected external research";
        });

        ChatModel model = mock(ChatModel.class);
        AssistantMessage internalCall = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call-1", "function", "searchVocalKnowledge", "{\"query\":\"初学者歌曲推荐\"}")));
        AssistantMessage detailedAnswer = new AssistantMessage("""
                推荐歌曲：
                1. 《歌曲A》：练习平滑起音。
                2. 《歌曲B》：练习长句气息。
                3. 《歌曲C》：练习自然咬字。
                4. 《歌曲D》：练习轻声和放松。
                5. 《歌曲E》：练习分段换气。
                安全提醒：出现疼痛或持续嘶哑应立即停止。
                """);
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(internalCall))),
                new ChatResponse(List.of(new Generation(detailedAnswer))));

        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[]{internal, external}, mock(LlmMetricsTracker.class));
        agent.setSystemPrompt("只在内部资料不足时调用外部研究");
        agent.setChatClient(ChatClient.builder(model).build());
        agent.setOriginalUserPrompt("帮我找适合当前训练计划的 5 首歌曲");
        agent.getMessageList().add(new UserMessage(agent.getOriginalUserPrompt()));

        assertThat(agent.step()).contains("searchVocalKnowledge");
        assertThat(agent.step()).contains("任务结束");

        assertThat(internalCalls).hasValue(1);
        assertThat(externalCalls).hasValue(0);
        assertThat(agent.getFinalAnswer())
                .contains("《歌曲A》", "《歌曲B》", "《歌曲C》", "《歌曲D》", "《歌曲E》")
                .contains("平滑起音", "长句气息", "自然咬字", "安全提醒");
    }

    @Test
    void startsExternalResearchWhenInternalKnowledgeDoesNotCoverTheRequest() {
        AtomicInteger internalCalls = new AtomicInteger();
        AtomicInteger externalCalls = new AtomicInteger();
        ToolCallback internal = callback("searchVocalKnowledge", () -> {
            internalCalls.incrementAndGet();
            return "{\"success\":true,\"data\":{\"citations\":[{\"id\":\"one-song\",\"title\":\"单首歌曲资料\",\"url\":\"/api/ai/knowledge/documents/one-song\",\"excerpt\":\"只有《歌曲A》，没有足够的推荐数量或暂缓歌曲资料。\"}]}}";
        });
        ToolCallback external = callback("researchExternal", () -> {
            externalCalls.incrementAndGet();
            return "{\"success\":true,\"data\":{\"status\":\"SUCCESS\",\"sources\":[]}}";
        });

        ChatModel model = mock(ChatModel.class);
        AssistantMessage internalCall = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call-1", "function", "searchVocalKnowledge", "{\"query\":\"初学者歌曲推荐\"}")));
        AssistantMessage externalCall = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call-2", "function", "researchExternal", "{\"request\":\"补充至少 5 首歌曲及暂缓歌曲\"}")));
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(internalCall))),
                new ChatResponse(List.of(new Generation(externalCall))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("外部研究结果待整理")))));

        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[]{internal, external}, mock(LlmMetricsTracker.class));
        agent.setSystemPrompt("内部证据不足时调用外部研究");
        agent.setChatClient(ChatClient.builder(model).build());
        agent.setOriginalUserPrompt("帮我找至少 5 首歌曲，并列出不适合作为第一首练习曲的歌曲");
        agent.getMessageList().add(new UserMessage(agent.getOriginalUserPrompt()));

        agent.step();
        agent.step();
        agent.step();

        assertThat(internalCalls).hasValue(1);
        assertThat(externalCalls).hasValue(1);
        assertThat(agent.getFinalAnswer()).isEqualTo("外部研究结果待整理");
    }

    private ToolCallback callback(String name, java.util.function.Supplier<String> output) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                return output.get();
            }

            @Override
            public String call(String input, ToolContext context) {
                return output.get();
            }
        };
    }
}
