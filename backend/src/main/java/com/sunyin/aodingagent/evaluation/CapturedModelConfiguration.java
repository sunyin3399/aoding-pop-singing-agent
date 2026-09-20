package com.sunyin.aodingagent.evaluation;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.env.Environment;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** 只取实际路由 ChatModel 的默认选项和配置白名单，不序列化 Bean、headers、API key、URL。 */
final class CapturedModelConfiguration {
    private CapturedModelConfiguration() {}

    static Map<String, Object> snapshot(Environment env, ChatModel model) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> configured = new LinkedHashMap<>();
        for (String key : List.of("spring.ai.openai.chat.options.model", "spring.ai.dashscope.chat.options.model",
                "spring.ai.openai.chat.options.temperature", "spring.ai.openai.chat.options.top-p",
                "spring.ai.openai.chat.options.max-tokens", "spring.ai.openai.chat.options.max-completion-tokens",
                "spring.ai.openai.chat.options.seed", "spring.ai.openai.chat.options.reasoning-effort",
                "app.rag.top-k", "app.rag.candidate-top-k", "app.rag.similarity-threshold")) {
            String value = env.getProperty(key);
            configured.put(key, safeLabel(value));
        }
        ChatOptions options = null;
        try { if (model != null) options = model.getDefaultOptions(); }
        catch (RuntimeException ignored) { /* 配置不可读也不干扰业务。 */ }
        String modelName = options == null ? "unavailable" : safeLabel(options.getModel());
        result.put("model", modelName);
        // 实现类只证明协议适配器，不证明实际上游公司；绝不按模型名称或私有 URL 猜厂商。
        result.put("provider", model instanceof OpenAiChatModel ? "OpenAI-compatible" : "unavailable");
        result.put("modelSource", options == null ? "unavailable" : "route ChatModel.getDefaultOptions().model");
        result.put("providerSource", model instanceof OpenAiChatModel ? "OpenAiChatModel adapter; upstream provider unobserved" : "unavailable");
        result.put("metadataProvenance", "configured Environment whitelist + route ChatModel defaults; not response metadata; request overrides unobserved");
        Map<String, Object> inference = new LinkedHashMap<>();
        inference.put("temperature", value(options == null ? null : options.getTemperature()));
        inference.put("top-p", value(options == null ? null : options.getTopP()));
        inference.put("max-tokens", value(options == null ? null : options.getMaxTokens()));
        inference.put("frequency-penalty", value(options == null ? null : options.getFrequencyPenalty()));
        inference.put("presence-penalty", value(options == null ? null : options.getPresencePenalty()));
        OpenAiChatOptions openai = options instanceof OpenAiChatOptions o ? o : null;
        inference.put("seed", value(openai == null ? null : openai.getSeed()));
        inference.put("max-completion-tokens", value(openai == null ? null : openai.getMaxCompletionTokens()));
        inference.put("reasoning-effort", openai == null ? "unavailable" : safeLabel(openai.getReasoningEffort()));
        configured.put("chatModel.defaultOptions.model", modelName);
        configured.put("chatModel.defaultOptions.inference", inference);
        result.put("configuredParameters", configured);
        result.put("inferenceParameters", inference);
        return result;
    }
    private static Object value(Number value) { return value == null ? "unavailable" : value; }
    private static String safeLabel(String value) {
        if (value == null) return "unavailable";
        if (value.isEmpty()) return ""; // 显式空值与未知配置分开。
        return value.matches("[A-Za-z0-9_.:+-]{1,256}") && !value.startsWith("sk-") ? value : "unavailable";
    }
}
