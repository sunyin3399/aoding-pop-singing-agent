package com.sunyin.aodingagent.research.finalization;

import com.sunyin.aodingagent.research.AgentReference;
import com.sunyin.aodingagent.research.ResearchModels;

import java.util.List;

/**
 * 保存一次 Agent 回复真正允许使用的证据。
 * <p>
 * 内部 RAG 和外部研究会在不同步骤返回资料。最终回复前把它们汇总到这里，可以让后续生成器
 * 同时使用两类证据，也能让校验器建立引用白名单，阻止大模型自行编造链接。
 */
public record EvidenceBundle(
        List<AgentReference> references,
        ResearchModels.ResearchTaskContract contract,
        ResearchModels.ResearchStatus researchStatus
) {
    public EvidenceBundle {
        references = references == null ? List.of() : List.copyOf(references);
    }
}
