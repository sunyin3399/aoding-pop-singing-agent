package com.sunyin.aodingagent.metrics;

import lombok.Setter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Profile("stress") // 重点：仅在激活 stress 环境时生效
@Primary           // 重点：注入时优先使用它，无缝顶替掉原有的真实大模型 Bean
public class MockChatModel implements ChatModel {

    // 默认大模型延迟时间：3000ms（3秒），采用 volatile 保证多线程可见性
    @Setter
    private static volatile long llmDelayMs = 5000;

    /**
     * 模拟阻塞式调用
     * 返回没有工具调用的最终响应，避免触发真实工具执行
     */
    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            Thread.sleep(llmDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        boolean hasToolResponse = prompt.getInstructions().stream()
                .anyMatch(m -> m instanceof ToolResponseMessage);

        if (!hasToolResponse) {
            List<AssistantMessage.ToolCall> toolCalls = List.of(
                    new AssistantMessage.ToolCall(
                            UUID.randomUUID().toString(),
                            "function",
                            "searchWeb",
                            "{\"query\": \"虚拟线程性能优势\"}"
                    ),
                    new AssistantMessage.ToolCall(
                            UUID.randomUUID().toString(),
                            "function",
                            "webScrap",
                            "{\"link\": \"www.baidu.com\"}"
                    ),
                    new AssistantMessage.ToolCall(
                            UUID.randomUUID().toString(),
                            "function",
                            "pdfGeneration",
                            "{\"result\": \"已生成\"}"
                    )
            );

            AssistantMessage responseMessage = new AssistantMessage(
                    "我需要搜索相关信息来回答这个问题。",
                    Map.of(),
                    toolCalls
            );

            return new ChatResponse(List.of(new Generation(responseMessage)));
        } else {
            AssistantMessage responseMessage = new AssistantMessage(
                    "根据搜索结果，虚拟线程是JDK 21引入的轻量级线程，可以显著提高高并发场景下的性能。" +
                            "北京天气晴，28度。计算结果：100 + 200 = 300。" +
                            "任务已完成，虚拟线程可以轻松承载高并发。"
            );

            return new ChatResponse(List.of(new Generation(responseMessage)));
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("【Mock流式开始】：")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("通过")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("虚拟线程")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("承载高并发。"))))
        ).delayElements(Duration.ofMillis(llmDelayMs / 4)); // 均分延迟模拟打字机效果
    }

}