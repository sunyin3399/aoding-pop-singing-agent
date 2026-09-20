package com.sunyin.aodingagent.advisor;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

public class MyLoggerAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    private static final Logger logger = LoggerFactory.getLogger(MyLoggerAdvisor.class);
    private static final int MAX_LOG_CHARS = 8_000;

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {

        advisedRequest = before(advisedRequest);

        AdvisedResponse advisedResponse = chain.nextAroundCall(advisedRequest);

        observeAfter(advisedResponse);

        return advisedResponse;
    }

    private AdvisedRequest before(AdvisedRequest request) {
        String systemText = compact(request.systemText());
        String userText = compact(request.userText());
        if (!systemText.isBlank()) logger.info("LLM 请求 system: {}", systemText);
        if (!userText.isBlank()) logger.info("LLM 请求 user: {}", userText);
        if (request.messages() != null && !request.messages().isEmpty()) {
            String messages = request.messages().stream()
                    .map(this::formatMessage)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
            logger.info("LLM 请求历史消息（{} 条）: {}", request.messages().size(), compact(messages));
        }
        if (request.functionNames() != null && !request.functionNames().isEmpty()) {
            logger.info("LLM 请求可用工具（{} 个）: {}",
                    request.functionNames().size(), String.join("、", request.functionNames()));
        }
        return request;
    }

    private String formatMessage(Message message) {
        String text = message.getText();
        return message.getMessageType() + ": " + (text == null ? "" : text);
    }

    private void observeAfter(AdvisedResponse advisedResponse) {
        AssistantMessage output = advisedResponse.response().getResult().getOutput();
        String text = compact(output.getText());
        if (!text.isBlank()) logger.info("LLM 返回文本: {}", text);
        if (!output.getToolCalls().isEmpty()) {
            String calls = output.getToolCalls().stream()
                    .map(call -> call.name() + "(" + compact(call.arguments()) + ")")
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            logger.info("LLM 返回工具调用: {}", calls);
        }
        if (text.isBlank() && output.getToolCalls().isEmpty()) logger.info("LLM 返回空内容");
    }

    private String compact(String value) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        if (compact.length() <= MAX_LOG_CHARS) return compact;
        return compact.substring(0, MAX_LOG_CHARS) + "…[日志已截断]";
    }

    @Override
    public String getName() {
        return "LLM response logger";
    }


    @Override
    public int getOrder() {
        // 值越小优先级越高，越先执行
        return 100;
    }


    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain) {
        advisedRequest = before(advisedRequest);

        Flux<AdvisedResponse> advisedResponses = chain.nextAroundStream(advisedRequest);

        return new MessageAggregator().aggregateAdvisedResponse(advisedResponses, this::observeAfter);
    }

    // 实现方法...
}
