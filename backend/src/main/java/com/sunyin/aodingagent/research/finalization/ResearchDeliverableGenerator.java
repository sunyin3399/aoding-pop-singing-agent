package com.sunyin.aodingagent.research.finalization;

import com.sunyin.aodingagent.research.ResearchModels;

import java.util.List;

/**
 * 使用用户请求、Agent 草稿和已核验证据生成结构化交付物。
 * <p>
 * 接口与 Spring AI 解耦，测试可以提供固定生成结果而不访问真实大模型。
 */
public interface ResearchDeliverableGenerator {

    ResearchDeliverable generate(String request, String draftAnswer, EvidenceBundle evidence);

    /** 第一次验收失败时只修订一次，避免无限调用模型。 */
    ResearchDeliverable revise(String request, String draftAnswer, EvidenceBundle evidence,
                                ResearchDeliverable invalid,
                                List<ResearchModels.ValidationIssue> issues);
}
