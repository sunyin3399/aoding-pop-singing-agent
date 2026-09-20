package com.sunyin.aodingagent.research.finalization;

/** 在 Agent 工具循环完成后，对有证据支撑的研究型回复进行验收和稳定渲染。 */
public interface AgentResponseFinalizer {
    FinalizedAgentResponse finalizeResponse(String request, String draftAnswer, EvidenceBundle evidence);
}
