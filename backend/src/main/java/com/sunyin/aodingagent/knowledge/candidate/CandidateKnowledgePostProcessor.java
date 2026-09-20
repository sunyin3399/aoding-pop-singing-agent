package com.sunyin.aodingagent.knowledge.candidate;

import com.sunyin.aodingagent.research.ResearchModels;
import com.sunyin.aodingagent.research.ResearchTaskPlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.ExternalSource;

@Service
public class CandidateKnowledgePostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(CandidateKnowledgePostProcessor.class);
    private final CandidateKnowledgeGenerator generator;
    private final CandidateKnowledgeWorkflow workflow;

    public CandidateKnowledgePostProcessor(CandidateKnowledgeGenerator generator, CandidateKnowledgeWorkflow workflow) {
        this.generator = generator;
        this.workflow = workflow;
    }

    public ResearchModels.CandidateKnowledgeOutcome process(
            String originalRequest, ResearchModels.ResearchResult result
    ) {
        if (!isSystematicTraining(originalRequest, result)) {
            return ResearchModels.CandidateKnowledgeOutcome.notEligible(
                    "一次性推荐、单曲技巧或临时教程不沉淀为候选知识");
        }
        List<ResearchModels.SourceCandidate> verified = result.sources().stream()
                .filter(source -> source.scrapeStatus() == ResearchModels.ScrapeStatus.SCRAPED)
                .filter(source -> source.qualityStatus() == ResearchModels.SourceQualityStatus.RELEVANT)
                .toList();
        if (verified.isEmpty()) {
            return ResearchModels.CandidateKnowledgeOutcome.notEligible("没有已核验来源，不生成候选知识");
        }

        final CandidateKnowledgeGenerator.GeneratedCandidate draft;
        try {
            draft = generator.generate(originalRequest, result);
        } catch (RuntimeException exception) {
            logger.warn("候选知识生成失败: {}", exception.getMessage());
            return new ResearchModels.CandidateKnowledgeOutcome(
                    ResearchModels.CandidateKnowledgeStatus.GENERATION_FAILED, null, null,
                    "候选知识生成失败，未提交审核");
        }
        if (draft == null || !draft.reusable()) {
            String reason = draft == null || draft.reason() == null || draft.reason().isBlank()
                    ? "研究结果不属于可复用训练知识" : draft.reason();
            return ResearchModels.CandidateKnowledgeOutcome.notEligible(reason);
        }

        List<ExternalSource> sources = verified.stream()
                .map(source -> new ExternalSource(source.title(), source.url()))
                .toList();
        try {
            CandidateKnowledgeModels.CandidateKnowledge saved = workflow.saveDraft(
                    originalRequest, draft.title(), draft.markdownContent(), sources);
            ResearchModels.CandidateKnowledgeStatus status = switch (saved.status()) {
                case PENDING -> ResearchModels.CandidateKnowledgeStatus.PENDING_REVIEW;
                case APPROVED -> ResearchModels.CandidateKnowledgeStatus.ALREADY_APPROVED;
                case REJECTED -> ResearchModels.CandidateKnowledgeStatus.ALREADY_REJECTED;
            };
            logger.info("候选知识处理完成: status={}, id={}, title={}", status, saved.id(), saved.title());
            return new ResearchModels.CandidateKnowledgeOutcome(status, saved.id(), saved.title(),
                    status == ResearchModels.CandidateKnowledgeStatus.PENDING_REVIEW
                            ? "候选知识已保存并等待管理员审核" : "相同候选知识已存在");
        } catch (IllegalArgumentException exception) {
            logger.warn("候选知识校验失败，未保存: {}", exception.getMessage());
            return new ResearchModels.CandidateKnowledgeOutcome(
                    ResearchModels.CandidateKnowledgeStatus.GENERATION_FAILED, null, draft.title(),
                    "候选知识格式校验失败，未提交审核：" + exception.getMessage());
        } catch (RuntimeException exception) {
            logger.error("候选知识保存失败", exception);
            return new ResearchModels.CandidateKnowledgeOutcome(
                    ResearchModels.CandidateKnowledgeStatus.SAVE_FAILED, null, draft.title(),
                    "候选知识保存失败，未提交审核");
        }
    }

    static boolean isSystematicTraining(String request, ResearchModels.ResearchResult result) {
        if (result == null || result.contract() == null || request == null) return false;
        ResearchModels.DeliverableType deliverable = result.contract().deliverable();
        if (deliverable == ResearchModels.DeliverableType.SONG_RECOMMENDATION
                || deliverable == ResearchModels.DeliverableType.TUTORIAL_LIST) return false;
        return ResearchTaskPlanner.isSystematicTrainingRequest(request);
    }
}
