package com.sunyin.aodingagent.evaluation;

import com.sunyin.aodingagent.knowledge.VocalKnowledgeRetriever.RetrievalMetadata;
import org.springframework.ai.chat.model.ToolContext;

/** Explicit per-invocation channel, never static state/ThreadLocal and never serialized to the model. */
public final class RetrievalInvocationObservation {
    public static final String CONTEXT_KEY = RetrievalInvocationObservation.class.getName();
    private RetrievalMetadata metadata;

    public synchronized RetrievalMetadata metadata() { return metadata; }

    public static void record(ToolContext context, RetrievalMetadata metadata) {
        if (context == null) return;
        Object channel = context.getContext().get(CONTEXT_KEY);
        if (channel instanceof RetrievalInvocationObservation observation) {
            synchronized (observation) { observation.metadata = metadata; }
        }
    }
}
