package com.sunyin.aodingagent.tools;

import com.sunyin.aodingagent.knowledge.VocalKnowledgeRetriever;
import com.sunyin.aodingagent.tool.ToolResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import static com.sunyin.aodingagent.knowledge.VocalKnowledgeRetriever.KnowledgeSearchStatus.FOUND;

@Component
public class VocalKnowledgeTool {

    private final VocalKnowledgeRetriever retriever;

    public VocalKnowledgeTool(VocalKnowledgeRetriever retriever) {
        this.retriever = retriever;
    }

    @Tool(description = "优先检索已审核的内部流行演唱知识库；命中专门主题文档时保留具体练习细节，内部来源由界面统一展示，无命中时明确返回 NO_KNOWLEDGE_HIT")
    public ToolResult<VocalKnowledgeRetriever.KnowledgeSearchResult> searchVocalKnowledge(
            @ToolParam(description = "用户的流行演唱问题或训练目标") String query) {
        return searchVocalKnowledge(query, null);
    }

    /** M6 does not omit ToolContext from generated schemas; use the public method's schema. */
    public org.springframework.ai.tool.ToolCallback callback() {
        try {
            var definition = org.springframework.ai.tool.ToolCallbacks.from(this)[0].getToolDefinition();
            return org.springframework.ai.tool.method.MethodToolCallback.builder()
                    .toolDefinition(definition)
                    .toolMethod(VocalKnowledgeTool.class.getMethod("searchVocalKnowledge", String.class,
                            org.springframework.ai.chat.model.ToolContext.class))
                    .toolObject(this).build();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Knowledge callback method unavailable", e);
        }
    }

    public ToolResult<VocalKnowledgeRetriever.KnowledgeSearchResult> searchVocalKnowledge(
            @ToolParam(description = "用户的流行演唱问题或训练目标") String query,
            org.springframework.ai.chat.model.ToolContext context
    ) {
        VocalKnowledgeRetriever.KnowledgeSearchResult result = retriever.search(query);
        com.sunyin.aodingagent.evaluation.RetrievalInvocationObservation.record(context, result.retrievalMetadata());
        boolean success = result.status() == FOUND;
        return new ToolResult<>(success, result.status().name(), result,
                success ? null : "内部知识库未命中", false);
    }
}
