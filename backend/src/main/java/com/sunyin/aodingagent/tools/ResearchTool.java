package com.sunyin.aodingagent.tools;

import com.sunyin.aodingagent.research.ResearchModels;
import com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgePostProcessor;
import com.sunyin.aodingagent.research.ResearchTaskPlanner;
import com.sunyin.aodingagent.research.ResearchWorkflow;
import com.sunyin.aodingagent.tool.ToolResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 流行演唱 Agent 进行外部研究时唯一可以直接调用的高层工具。
 * <p>
 * Agent 不能直接调用原始搜索和抓取工具，而是把完整问题交给这里。这个工具先规划研究合同，
 * 再执行受预算控制的研究流程，最后返回成功、降级或失败状态。这样可以防止 Agent 绕过次数
 * 限制，也能避免它把“只找到一部分资料”描述成完整成功。
 */
@Component
public class ResearchTool {

    private static final Logger logger = LoggerFactory.getLogger(ResearchTool.class);
    static final int MAX_SOURCE_SUMMARY_CHARS = 700;

    private final ResearchTaskPlanner planner;
    private final ResearchWorkflow workflow;
    private final CandidateKnowledgePostProcessor candidatePostProcessor;

    public ResearchTool(ResearchTaskPlanner planner, ResearchWorkflow workflow) {
        this(planner, workflow, null);
    }

    @Autowired
    public ResearchTool(ResearchTaskPlanner planner, ResearchWorkflow workflow,
                        CandidateKnowledgePostProcessor candidatePostProcessor) {
        this.planner = planner;
        this.workflow = workflow;
        this.candidatePostProcessor = candidatePostProcessor;
    }

    /**
     * 对用户问题执行一次受控的外部研究。
     *
     * @param request 用户完整原始请求，必须保留数量、分类和输出要求
     * @return 精简后的证据来源、调用次数、缺口和明确完成状态
     */
    @Tool(description = "对流行演唱资源清单、资料整理或对比请求执行有次数上限的外部研究，并返回结构化来源与完成状态")
    public ToolResult<ResearchModels.AgentResearchResult> researchExternal(
            @ToolParam(description = "用户的完整原始研究请求，不要省略数量、分类或输出要求") String request
    ) {
        ResearchModels.ResearchTaskContract contract = planner.plan(request);
        ResearchModels.ResearchResult result = workflow.research(request, contract);
        ResearchModels.CandidateKnowledgeOutcome candidateOutcome = candidateOutcome(request, result);
        // 完整网页正文只在工作流内部使用，返回 Agent 前压缩为摘要，避免上下文被网页内容撑满。
        ResearchModels.AgentResearchResult agentResult = toAgentResult(result, candidateOutcome);
        long actualSources = result.sources().stream()
                .filter(source -> source.scrapeStatus() == ResearchModels.ScrapeStatus.SCRAPED)
                .count();
        return switch (result.status()) {
            case SUCCESS -> ToolResult.success("RESEARCH_SUCCESS", agentResult);
            case DEGRADED -> new ToolResult<>(false, "RESEARCH_DEGRADED", agentResult,
                    "研究证据未完全达标：目标 " + contract.minimumSources() + " 个来源，实际 " + actualSources
                            + " 个。请基于现有已核验证据诚实回答并说明证据缺口。", false);
            case FAILED -> new ToolResult<>(false, "RESEARCH_FAILED", agentResult,
                    "没有获得可信来源，不得编造资源名称或链接。", false);
        };
    }

    private ResearchModels.CandidateKnowledgeOutcome candidateOutcome(
            String request, ResearchModels.ResearchResult result
    ) {
        if (candidatePostProcessor == null) {
            return ResearchModels.CandidateKnowledgeOutcome.notEligible("候选知识后处理器未启用");
        }
        try {
            return candidatePostProcessor.process(request, result);
        } catch (RuntimeException exception) {
            logger.warn("候选知识后处理失败，不影响主研究结果: {}", exception.getMessage());
            return new ResearchModels.CandidateKnowledgeOutcome(
                    ResearchModels.CandidateKnowledgeStatus.GENERATION_FAILED, null, null,
                    "候选知识处理失败，主回答仍可继续");
        }
    }

    private ResearchModels.AgentResearchResult toAgentResult(
            ResearchModels.ResearchResult result,
            ResearchModels.CandidateKnowledgeOutcome candidateOutcome
    ) {
        var sources = result.sources().stream()
                .map(source -> new ResearchModels.AgentResearchSource(
                        source.id(), source.title(), source.url(), source.category(),
                        compactSummary(source), source.scrapeStatus(), source.qualityStatus()))
                .toList();
        return new ResearchModels.AgentResearchResult(
                result.status(), result.contract(), ResearchModels.answerGuidance(result.contract().deliverable()),
                sources, result.validationIssues(),
                result.searchCalls(), result.scrapeCalls(), result.recoveryRounds(), candidateOutcome);
    }

    private String compactSummary(ResearchModels.SourceCandidate source) {
        String value = source.extractedContent();
        if (value == null || value.isBlank()) value = source.snippet();
        if (value == null) return "";
        value = value.replaceAll("\\s+", " ").trim();
        if (value.length() <= MAX_SOURCE_SUMMARY_CHARS) return value;
        return value.substring(0, MAX_SOURCE_SUMMARY_CHARS) + "…";
    }
}
