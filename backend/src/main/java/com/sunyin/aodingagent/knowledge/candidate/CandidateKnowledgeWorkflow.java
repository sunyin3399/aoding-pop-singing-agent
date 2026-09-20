package com.sunyin.aodingagent.knowledge.candidate;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateKnowledge;
import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus.PENDING;
import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.ExternalSource;

/**
 * 将外部研究整理出的知识保存为“待审核候选”，而不是直接写入正式 RAG。
 * <p>
 * 网上资料可能错误、过时或不适合当前声乐场景。如果 Agent 能自动把它们写进内部知识库，
 * 一次错误研究就会长期污染后续回答。这个类先检查来源地址和文档结构，再把内容保存为
 * {@code PENDING}，只有管理员审核通过后才允许发布。
 */
@Service
public class CandidateKnowledgeWorkflow {

    private final CandidateKnowledgeRepository repository;

    public CandidateKnowledgeWorkflow(CandidateKnowledgeRepository repository) {
        this.repository = repository;
    }

    /**
     * 校验并保存一份待审核知识草稿。
     *
     * @param originalQuery 产生这份候选知识的用户原始问题
     * @param title 候选知识标题
     * @param markdownContent 包含训练动作、常见错误、适用范围、安全提醒和来源的 Markdown
     * @param sources 至少一个带标题的 HTTP(S) 外部来源
     * @return 新保存的候选知识；相同正文已经存在时直接返回原记录
     */
    public CandidateKnowledge saveDraft(
            String originalQuery,
            String title,
            String markdownContent,
            List<ExternalSource> sources
    ) {
        requireText(originalQuery, "原始问题不能为空");
        requireText(title, "候选标题不能为空");
        validateMarkdown(markdownContent);
        validateSources(sources);

        String normalizedMarkdown = markdownContent.strip() + "\n";
        // 内容哈希用于识别完全相同的文档，避免 Agent 重复研究后生成多份相同候选。
        String contentHash = sha256(normalizedMarkdown);
        String id = UUID.nameUUIDFromBytes(contentHash.getBytes(StandardCharsets.UTF_8)).toString();
        CandidateKnowledge existing = repository.findById(id).orElse(null);
        if (existing != null) return existing;

        return repository.save(new CandidateKnowledge(
                id, originalQuery.strip(), title.strip(), normalizedMarkdown, sources,
                contentHash, PENDING, Instant.now(), null, null, List.of()));
    }

    private void validateSources(List<ExternalSource> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个 HTTP(S) 外部来源");
        }
        for (ExternalSource source : sources) {
            requireText(source.title(), "外部来源标题不能为空");
            try {
                URI uri = URI.create(source.url());
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null) {
                    throw new IllegalArgumentException("外部来源必须是有效 HTTP(S) URL");
                }
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("外部来源必须是有效 HTTP(S) URL", exception);
            }
        }
    }

    private void validateMarkdown(String markdown) {
        requireText(markdown, "Markdown 正文不能为空");
        boolean complete = markdown.stripLeading().startsWith("# ")
                && markdown.contains("\n## ")
                && markdown.contains("## 训练动作")
                && markdown.contains("## 常见错误")
                && markdown.contains("## 适用范围")
                && markdown.contains("## 安全提醒")
                && markdown.contains("## 资料来源")
                && markdown.matches("(?s).*(分钟|秒|次|组).*?");
        if (!complete) throw new IllegalArgumentException("Markdown 候选文档结构不完整");
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }
}
