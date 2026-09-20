package com.sunyin.aodingagent.tools;

import com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateKnowledge;
import com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.ExternalSource;
import com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeWorkflow;
import com.sunyin.aodingagent.tool.ToolResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CandidateKnowledgeTool {

    private final CandidateKnowledgeWorkflow workflow;

    public CandidateKnowledgeTool(CandidateKnowledgeWorkflow workflow) {
        this.workflow = workflow;
    }

    @Tool(description = "仅在内部声乐知识无命中且已取得外部 HTTP(S) 来源时，保存待管理员审核的 Markdown 候选知识")
    public ToolResult<CandidateKnowledge> saveCandidateKnowledge(
            @ToolParam(description = "原始问题、候选标题、完整 Markdown 正文及外部来源")
            SaveCandidateRequest request
    ) {
        try {
            CandidateKnowledge saved = workflow.saveDraft(
                    request.originalQuery(), request.title(), request.markdownContent(), request.sources());
            return ToolResult.success("CANDIDATE_PENDING_REVIEW", saved);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure("INVALID_CANDIDATE", exception.getMessage(), false);
        } catch (RuntimeException exception) {
            return ToolResult.failure("CANDIDATE_SAVE_FAILED", exception.getMessage(), true);
        }
    }

    public record SaveCandidateRequest(
            String originalQuery,
            String title,
            String markdownContent,
            List<ExternalSource> sources
    ) {
        public SaveCandidateRequest {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }
}
