package com.sunyin.aodingagent.research.finalization;

import com.sunyin.aodingagent.research.AgentReference;
import com.sunyin.aodingagent.research.ResearchModels;

import java.util.List;

/** Agent 收尾模块返回的最终 Markdown、结构化结果和引用。 */
public record FinalizedAgentResponse(
        String markdown,
        ResearchDeliverable deliverable,
        List<AgentReference> references,
        List<ResearchModels.ValidationIssue> validationIssues
) {
    public FinalizedAgentResponse {
        references = references == null ? List.of() : List.copyOf(references);
        validationIssues = validationIssues == null ? List.of() : List.copyOf(validationIssues);
    }
}
