package com.sunyin.aodingagent.knowledge.candidate;

import com.sunyin.aodingagent.knowledge.KnowledgeDocumentCatalog;
import com.sunyin.aodingagent.rag.MarkdownKnowledgeDocumentFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateKnowledge;
import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus.APPROVED;
import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus.PENDING;
import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus.REJECTED;

/**
 * 处理管理员对候选知识的批准和拒绝，并在批准后发布到正式向量知识库。
 * <p>
 * 批准时会先把 Markdown 切成向量文档并写入 VectorStore，成功后才把候选状态改成 APPROVED。
 * 如果向量写入失败，候选仍保持 PENDING，避免界面显示“已批准”但实际无法检索。重复审核同一
 * 记录时直接返回当前状态，防止重复发布。
 * <p>
 * 方法上的 {@code synchronized} 只能避免同一个 JVM 内同时审核，不能代替多实例分布式锁。
 */
@Service
public class CandidateKnowledgePublisher implements CandidateKnowledgeReviewService {

    private final CandidateKnowledgeRepository repository;
    private final VectorStore vectorStore;
    private final MarkdownKnowledgeDocumentFactory documentFactory;
    private final KnowledgeDocumentCatalog catalog;

    public CandidateKnowledgePublisher(
            CandidateKnowledgeRepository repository,
            VectorStore vectorStore,
            MarkdownKnowledgeDocumentFactory documentFactory,
            KnowledgeDocumentCatalog catalog
    ) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.documentFactory = documentFactory;
        this.catalog = catalog;
    }

    /**
     * 批准候选知识并发布到正式 RAG。
     *
     * @return 发布后的候选记录；记录不是 PENDING 时不重复发布
     */
    @Override
    public synchronized CandidateKnowledge approve(String id, String reviewNote) {
        CandidateKnowledge candidate = get(id);
        if (candidate.status() != PENDING) return candidate;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("candidateId", candidate.id());
        metadata.put("version", candidate.contentHash().substring(0, Math.min(12, candidate.contentHash().length())));
        metadata.put("approvalStatus", "APPROVED");
        metadata.put("sourceUrls", candidate.sources().stream().map(source -> source.url()).toList());
        List<Document> documents = documentFactory.splitMarkdown(
                "candidate-" + candidate.id() + ".md", candidate.markdownContent(), metadata);
        // 必须先确认向量写入成功，再更新审核状态和只读目录。
        try {
            vectorStore.add(documents);
        } catch (RuntimeException exception) {
            throw new PublicationException("候选知识向量发布失败", exception);
        }

        CandidateKnowledge approved = reviewed(candidate, APPROVED, reviewNote,
                documents.stream().map(Document::getId).toList());
        CandidateKnowledge saved = repository.save(approved);
        catalog.registerApproved(documents);
        return saved;
    }

    /** 拒绝候选知识。拒绝内容不会写入 VectorStore。 */
    @Override
    public synchronized CandidateKnowledge reject(String id, String reviewNote) {
        CandidateKnowledge candidate = get(id);
        if (candidate.status() != PENDING) return candidate;
        return repository.save(reviewed(candidate, REJECTED, reviewNote, List.of()));
    }

    private CandidateKnowledge get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("候选知识不存在: " + id));
    }

    private CandidateKnowledge reviewed(
            CandidateKnowledge candidate,
            CandidateKnowledgeModels.CandidateStatus status,
            String note,
            List<String> documentIds
    ) {
        return new CandidateKnowledge(
                candidate.id(), candidate.originalQuery(), candidate.title(), candidate.markdownContent(),
                candidate.sources(), candidate.contentHash(), status, candidate.createdAt(), Instant.now(),
                note == null ? "" : note.strip(), documentIds);
    }

    public static class PublicationException extends RuntimeException {
        public PublicationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
