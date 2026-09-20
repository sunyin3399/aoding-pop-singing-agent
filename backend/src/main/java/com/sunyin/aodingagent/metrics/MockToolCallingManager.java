package com.sunyin.aodingagent.metrics;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("stress")
@Primary
public class MockToolCallingManager implements ToolCallingManager {

    @Override
    public List<ToolDefinition> resolveToolDefinitions(org.springframework.ai.model.tool.ToolCallingChatOptions toolCallingChatOptions) {
        return List.of();
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();

        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            String mockData = switch (toolCall.name()) {
                case "searchWeb" -> "搜索结果：虚拟线程是JDK 21引入的轻量级线程，可显著提高高并发场景下的性能。参考资料: [虚拟线程官方文档]";
                case "webScrap" -> "网页抓取结果：北京晴，28度，适合户外活动。";
                case "pdfGeneration" -> "已生成pdf文件";
                default -> "工具执行成功，返回模拟数据。";
            };

            ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(
                    toolCall.id(),
                    toolCall.name(),
                    mockData
            );
            toolResponses.add(response);
        }

        ToolResponseMessage toolResponseMessage = new ToolResponseMessage(toolResponses);

        List<Message> conversationHistory = new ArrayList<>(prompt.getInstructions());
        conversationHistory.add(assistantMessage);
        conversationHistory.add(toolResponseMessage);

        return DefaultToolExecutionResult.builder()
                .conversationHistory(conversationHistory)
                .build();
    }
}
