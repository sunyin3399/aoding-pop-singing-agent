package com.sunyin.aodingagent.knowledge.candidate;

import java.time.Instant;
import java.util.List;

/**
 * 保存外部候选知识审核流程使用的数据结构。
 * <p>
 * 候选知识与正式 RAG 文档分开建模，目的是让外部资料在管理员批准前始终保持明确的待审核状态。
 */
public final class CandidateKnowledgeModels {

    private CandidateKnowledgeModels() {
    }

    /** Agent 研究时实际使用的一个外部网页来源。 */
    public record ExternalSource(String title, String url) {
    }

    /**
     * 一份完整候选知识，保存正文、来源、内容哈希、审核状态和最终发布出的向量文档 ID。
     */
    public record CandidateKnowledge(
            String id,
            String originalQuery,
            String title,
            String markdownContent,
            List<ExternalSource> sources,
            String contentHash,
            CandidateStatus status,
            Instant createdAt,
            Instant reviewedAt,
            String reviewNote,
            List<String> publishedDocumentIds
    ) {
        public CandidateKnowledge {
            sources = sources == null ? List.of() : List.copyOf(sources);
            publishedDocumentIds = publishedDocumentIds == null ? List.of() : List.copyOf(publishedDocumentIds);
        }
    }

    /** 候选知识在人审流程中的状态。 */
    public enum CandidateStatus {
        /** 等待管理员审核，不能进入正式 RAG。 */
        PENDING,
        /** 审核通过且已经成功发布到正式 RAG。 */
        APPROVED,
        /** 审核未通过，不会发布。 */
        REJECTED
    }
}
