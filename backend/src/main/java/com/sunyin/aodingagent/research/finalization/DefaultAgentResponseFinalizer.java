package com.sunyin.aodingagent.research.finalization;

import com.sunyin.aodingagent.research.ResearchModels;
import com.sunyin.aodingagent.research.ResearchModels.DeliverableType;
import com.sunyin.aodingagent.research.ResearchTaskPlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.sunyin.aodingagent.research.AgentReference;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Set;

/**
 * 组织“生成一次、Java 验收、失败时修订一次、稳定渲染”的最终回复流程。
 * <p>
 * 只有“需要结构化产物”的交付物（歌曲/教程/资源/练习清单）才跑生成器；开放问答
 * （RESEARCH_SUMMARY / COMPARISON_TABLE）直接返回 Agent 草稿，避免额外 ~30s 的结构化重排。
 */
@Component
public final class DefaultAgentResponseFinalizer implements AgentResponseFinalizer {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAgentResponseFinalizer.class);
    static final int MIN_DIRECT_DRAFT_CHARS = 180;

    /** 这些交付物需要固定格式的结构化产物；其余（RESEARCH_SUMMARY/COMPARISON_TABLE）走对话式草稿。 */
    private static final Set<DeliverableType> STRUCTURED_ARTIFACT_TYPES = Set.of(
            DeliverableType.SONG_RECOMMENDATION,
            DeliverableType.TUTORIAL_LIST,
            DeliverableType.RESOURCE_LIST,
            DeliverableType.EXERCISE_GUIDE
    );

    private static final String SAFE_FAILURE = "暂时无法安全整理回答，请稍后重试。";
    private static final Pattern INLINE_LINK = Pattern.compile("!?\\[([^\\]\\r\\n]*)\\]\\(([^)\\r\\n]*)\\)");
    private static final Pattern LINK_DEFINITION = Pattern.compile("(?m)^ {0,3}\\[[^\\]\\r\\n]+\\]:[^\\r\\n]*$");
    private static final Pattern SOURCE_SECTION = Pattern.compile("(?ms)^\\s*(?:---\\s*\\r?\\n)?\\*\\*资料来源[:：]\\*\\*.*?(?:\\r?\\n---\\s*)?(?=\\z)");
    private static final Pattern URL = Pattern.compile("(?i)(?:[a-z][a-z0-9+.-]*://|www\\.)[^\\s<>\\[\\]()\\\"']+|(?:javascript|data|vbscript):[^\\s<>\\[\\]()\\\"']*");
    private static final Pattern CITATION = Pattern.compile("\\[(source-[^\\]\\s]+)\\]");

    /** Deterministic output boundary; never retry with raw text if cleanup itself fails. */
    private FinalizedAgentResponse cleanResponse(String text, ResearchDeliverable deliverable,
            EvidenceBundle evidence, List<ResearchModels.ValidationIssue> issues) {
        try {
            List<AgentReference> allowed = evidence.references().stream()
                    .filter(reference -> reference.status() == AgentReference.ReferenceStatus.SCRAPED
                            || reference.status() == AgentReference.ReferenceStatus.INTERNAL_APPROVED)
                    .filter(reference -> reference.id() != null && !reference.id().isBlank())
                    .filter(reference -> reference.url() != null
                            && reference.url().matches("(?i)(https?://|knowledge://|/api/ai/knowledge/documents/)[^\\s<>\\\"']+"))
                    .toList();
            Set<String> urls = allowed.stream().map(AgentReference::url).collect(Collectors.toSet());
            Set<String> ids = allowed.stream().map(AgentReference::id).collect(Collectors.toSet());
            String clean = text == null ? "" : text;
            // Reference-style definitions are not needed: verified sources travel via SSE.
            clean = LINK_DEFINITION.matcher(clean).replaceAll("");
            clean = SOURCE_SECTION.matcher(clean).replaceAll("");
            clean = INLINE_LINK.matcher(clean).replaceAll(match -> Matcher.quoteReplacement(
                    urls.contains(match.group(2)) ? match.group() : match.group(1)));
            clean = URL.matcher(clean).replaceAll(match -> Matcher.quoteReplacement(
                    urls.contains(match.group()) ? match.group() : "[未核验链接已移除]"));
            clean = CITATION.matcher(clean).replaceAll(match -> Matcher.quoteReplacement(
                    ids.contains(match.group(1)) ? match.group() : ""));
            clean = clean.replace("<", "&lt;").replace(">", "&gt;").trim();
            return new FinalizedAgentResponse(clean, issues.isEmpty() ? deliverable : null, allowed, issues);
        } catch (RuntimeException exception) {
            logger.error("最终回答清理失败，返回固定安全提示", exception);
            return new FinalizedAgentResponse(SAFE_FAILURE, null, List.of(), List.of());
        }
    }

    private final ResearchTaskPlanner planner;
    private final ResearchDeliverableGenerator generator;
    private final ResearchDeliverableValidator validator = new ResearchDeliverableValidator();
    private final ResearchDeliverableRenderer renderer = new ResearchDeliverableRenderer();

    public DefaultAgentResponseFinalizer(ResearchTaskPlanner planner, ResearchDeliverableGenerator generator) {
        this.planner = planner;
        this.generator = generator;
    }

    @Override
    public FinalizedAgentResponse finalizeResponse(String request, String draftAnswer, EvidenceBundle evidence) {
        FinalizedAgentResponse candidate;
        try {
            candidate = finalizeCandidate(request, draftAnswer, evidence);
        } catch (RuntimeException exception) {
            logger.warn("最终交付物判定失败，清理草稿后降级", exception);
            return cleanResponse(draftAnswer, null, evidence, List.of());
        }
        return cleanResponse(candidate.markdown(), candidate.deliverable(), evidence, candidate.validationIssues());
    }

    // Every exit, including fast paths and exceptions, passes through cleanResponse.
    private FinalizedAgentResponse finalizeCandidate(String request, String draftAnswer, EvidenceBundle evidence) {
        ResearchModels.ResearchTaskContract contract = evidence.contract() == null ? planner.plan(request) : evidence.contract();
        // 开放问答不需要结构化整理器：直接保留 Agent 已生成的对话草稿（引用照常随 SSE 发送）。
        if (contract == null || !STRUCTURED_ARTIFACT_TYPES.contains(contract.deliverable())) {
            return new FinalizedAgentResponse(draftAnswer, null, evidence.references(), List.of());
        }
        ResearchModels.ResearchStatus status = evidence.researchStatus() == null
                ? ResearchModels.ResearchStatus.SUCCESS : evidence.researchStatus();
        EvidenceBundle completeEvidence = new EvidenceBundle(evidence.references(), contract, status);
        // 草稿若已基本满足该交付物的结构要求，直接放行草稿，避免把 LLM 输出重喂一遍再生成。
        if (draftAnswer != null && draftAnswer.trim().length() >= MIN_DIRECT_DRAFT_CHARS
                && DraftConformance.conformant(contract.deliverable(), draftAnswer)) {
            logger.info("交付物草稿已基本达标，直接采用 Agent 草稿，跳过结构化重排: deliverable={}",
                    contract.deliverable());
            return new FinalizedAgentResponse(draftAnswer, null, completeEvidence.references(), List.of());
        }
        logger.info("交付物草稿未完全达标，执行整理器补齐缺失: deliverable={}", contract.deliverable());
        try {
            ResearchDeliverable deliverable = generator.generate(request, draftAnswer, completeEvidence);
            List<ResearchModels.ValidationIssue> issues = validator.validate(deliverable, completeEvidence);
            if (!issues.isEmpty()) {
                logger.warn("最终交付物首次验收失败，允许修订一次: {}", issues);
                deliverable = generator.revise(request, draftAnswer, completeEvidence, deliverable, issues);
                issues = validator.validate(deliverable, completeEvidence);
            }
            if (!issues.isEmpty()) {
                logger.warn("最终交付物修订后仍未完全达标，保留原始草稿并输出验收问题: {}", issues);
                return new FinalizedAgentResponse(draftAnswer, deliverable, completeEvidence.references(), issues);
            }
            return new FinalizedAgentResponse(renderer.render(deliverable), deliverable,
                    completeEvidence.references(), List.of());
        } catch (RuntimeException exception) {
            // 收尾模块失败不能吞掉 Agent 已经生成的可用回答；引用仍按原 SSE 合同发送。
            logger.error("最终交付物生成失败，回退到 Agent 原始回答", exception);
            return new FinalizedAgentResponse(draftAnswer, null, completeEvidence.references(), List.of());
        }
    }
}
