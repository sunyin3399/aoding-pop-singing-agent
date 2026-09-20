package com.sunyin.aodingagent.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.ContextEntry;
import com.sunyin.aodingagent.research.AgentReference;
import com.sunyin.aodingagent.stream.AgentEventSink;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测用 {@link AgentEventSink}：收集一次 agent 运行的最终回答与它实际引用的上下文。
 * <p>
 * 监听 "final" 事件（最终回答）和 "references" 事件（{@link AgentReference} 列表 JSON），
 * 把引用解析成 {@link ContextEntry}(id, 正文)，供 {@link VocalAgentJudge} 判断忠实度。
 * 这样评测用的上下文是"agent 实际引用/用到的内容"，而非笼统的"检索器返回了什么"。
 */
public class CollectingSink implements AgentEventSink {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String finalAnswer;
    private List<ContextEntry> context = List.of();
    private volatile boolean cancelled;
    private Throwable error;
    private boolean completed;

    @Override
    public void emit(String event, String data) {
        if (event == null) return;
        if ("final".equals(event)) {
            this.finalAnswer = data;
        } else if ("references".equals(event)) {
            this.context = parseReferences(data);
        } else if ("error".equals(event)) {
            this.error = new IllegalStateException(data);
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void complete() {
        this.completed = true;
    }

    @Override
    public void fail(Throwable throwable) {
        this.error = throwable;
        this.completed = true;
    }

    /** 最终回答；未产生 final 事件时为 null。 */
    public String finalAnswer() {
        return finalAnswer;
    }

    /** agent 实际引用的上下文（id + 标题/正文）。 */
    public List<ContextEntry> context() {
        return context;
    }

    /** agent 运行中的错误；无错误时为 null。 */
    public Throwable error() {
        return error;
    }

    /** 解析 references 事件载荷（AgentReference JSON 数组）为上下文条目。 */
    static List<ContextEntry> parseReferences(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            AgentReference[] refs = MAPPER.readValue(json, AgentReference[].class);
            List<ContextEntry> entries = new ArrayList<>(refs.length);
            for (AgentReference reference : refs) {
                String id = (reference.id() == null || reference.id().isBlank())
                        ? reference.title() : reference.id();
                String text = (reference.title() == null ? "" : reference.title())
                        + (reference.excerpt() == null || reference.excerpt().isBlank()
                        ? "" : " " + reference.excerpt());
                entries.add(new ContextEntry(id, text.trim()));
            }
            return entries;
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }
}
