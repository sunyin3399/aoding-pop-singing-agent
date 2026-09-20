package com.sunyin.aodingagent.metrics;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LlmMetricsTracker {

    public void trackLlmCall(ChatResponse response, long startTime) {
        if (null != response) {
            long costTime = System.currentTimeMillis() - startTime;
            Usage usage = response.getMetadata().getUsage();

            if (usage != null) {
                log.info("[LLM 指标] 耗时: {}ms | Prompt 消耗 Tokens: {} | 生成 Tokens: {} | 总 Tokens: {}",
                        costTime, usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
            } else {
                log.info("[LLM 指标] 耗时: {}ms | 未获取到 Token 信息", costTime);
            }
        }
    }
}