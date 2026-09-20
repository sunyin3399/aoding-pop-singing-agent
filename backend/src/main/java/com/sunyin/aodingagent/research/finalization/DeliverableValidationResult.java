package com.sunyin.aodingagent.research.finalization;

import com.sunyin.aodingagent.research.ResearchModels;

import java.util.List;

/** 最终交付物的验收结果；issues 为空表示可以安全返回给用户。 */
public record DeliverableValidationResult(
        ResearchDeliverable deliverable,
        List<ResearchModels.ValidationIssue> issues
) {
    public DeliverableValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean valid() {
        return issues.isEmpty();
    }
}
