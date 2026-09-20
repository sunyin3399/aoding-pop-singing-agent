package com.sunyin.aodingagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** 请求专属回调包装：不使用 ThreadLocal，不记录参数、返回正文、异常文本或本地路径。 */
final class ObservedToolCallback implements ToolCallback {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ToolCallback delegate;
    private final BiConsumer<String, String> observer;

    ObservedToolCallback(ToolCallback delegate, BiConsumer<String, String> observer) {
        this.delegate = delegate;
        this.observer = observer;
    }
    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
    @Override public String call(String input) { return invoke(input, null, false); }
    @Override public String call(String input, ToolContext context) { return invoke(input, context, true); }

    private String invoke(String input, ToolContext context, boolean withContext) {
        String callId = UUID.randomUUID().toString(); // 本地执行 ID，不宣称是提供商响应 ID。
        long started = System.nanoTime();
        Map<String, Object> trace = new LinkedHashMap<>();
        String name = getToolDefinition().name();
        trace.put("toolName", name.matches("[A-Za-z0-9_-]{1,128}") ? name : "unavailable");
        trace.put("callId", callId);
        trace.put("callIdSource", "local_tool_invocation");
        trace.put("success", "unavailable");
        trace.put("elapsedMs", 0);
        trace.put("outputTruncated", "unavailable");
        trace.put("successSemantics", "callback_returned_without_exception_and_no_explicit_success_false");
        emit("tool_start", trace);
        var retrieval = new com.sunyin.aodingagent.evaluation.RetrievalInvocationObservation();
        boolean knowledge = "searchVocalKnowledge".equals(name);
        try {
            String result;
            if (knowledge) {
                Map<String, Object> values = new LinkedHashMap<>();
                if (context != null) values.putAll(context.getContext());
                values.put(com.sunyin.aodingagent.evaluation.RetrievalInvocationObservation.CONTEXT_KEY, retrieval);
                result = delegate.call(input, new ToolContext(values));
            } else {
                result = withContext ? delegate.call(input, context) : delegate.call(input);
            }
            trace.put("success", true);
            try {
                var value = JSON.readTree(result);
                if (value != null && value.path("success").isBoolean())
                    trace.put("success", value.path("success").asBoolean());
            } catch (Exception ignored) { /* 非 JSON 工具只能证明正常返回，不能断言业务语义正确。 */ }
            trace.put("outputTruncated", result == null ? "unavailable"
                    : result.matches("(?s).*\\n\\n\\[工具输出已截断，省略 [0-9]+ 个字符\\]$"));
            trace.put("outputTruncatedSource", "ToolOutputLimiter_suffix_only; upstream truncation unobserved");
            return result;
        } catch (RuntimeException | Error error) {
            trace.put("success", false);
            throw error;
        } finally {
            trace.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            emit("tool_result", trace);
            if (knowledge) {
                Map<String, Object> ids = new LinkedHashMap<>();
                ids.put("callId", callId);
                ids.put("toolName", name);
                var metadata = retrieval.metadata();
                boolean valid = metadata != null && metadata.candidateIds().size() <= 128
                        && metadata.candidateIds().stream().allMatch(id -> id != null
                        && id.matches("[\\p{L}\\p{N}_.:#-]{1,256}") && !id.startsWith("sk-"));
                ids.put("retrievalCandidateIds", valid ? metadata.candidateIds() : "unavailable");
                if (!valid) ids.put("reason", "missing_invalid_or_oversized_request_metadata_or_tool_failed");
                emit("eval_retrieval", ids);
            }
        }
    }
    private void emit(String event, Map<String, Object> trace) {
        try { observer.accept(event, JSON.writeValueAsString(trace)); }
        catch (Exception ignored) { /* 旁路观测失败不能改变工具执行。 */ }
    }
}
