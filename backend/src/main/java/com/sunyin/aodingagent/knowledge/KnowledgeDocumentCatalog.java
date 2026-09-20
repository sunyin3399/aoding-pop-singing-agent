package com.sunyin.aodingagent.knowledge;

import com.sunyin.aodingagent.rag.MusicAppDocumentLoader;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 保存“稳定文档 ID 到可阅读知识正文”的映射，供引用链接打开原始知识内容。
 * <p>
 * 前端只能通过文档 ID 查找已经批准的知识，不能传入任意文件路径读取服务器文件。内置知识
 * 在启动时注册，管理员新批准的候选知识也会在发布后加入目录。
 */
@Component
public class KnowledgeDocumentCatalog {

    private final Map<String, KnowledgeDocumentView> documents;

    @Autowired
    public KnowledgeDocumentCatalog(MusicAppDocumentLoader loader) {
        this(loader.loadMarkdowns());
    }

    public KnowledgeDocumentCatalog(List<Document> initialDocuments) {
        this.documents = new ConcurrentHashMap<>();
        registerApproved(initialDocuments);
    }

    /** 根据稳定文档 ID 查询已批准知识，不存在或未注册时返回空。 */
    public Optional<KnowledgeDocumentView> findApproved(String documentId) {
        if (documentId == null || documentId.isBlank()) return Optional.empty();
        return Optional.ofNullable(documents.get(documentId));
    }

    /** 将一批已批准文档加入可阅读目录，未批准文档会被忽略。 */
    public void registerApproved(List<Document> approvedDocuments) {
        for (Document document : approvedDocuments) {
            if (isApproved(document)) documents.put(document.getId(), toView(document));
        }
    }

    private boolean isApproved(Document document) {
        Object status = document.getMetadata().get("approvalStatus");
        return status == null || "APPROVED".equals(status.toString());
    }

    private KnowledgeDocumentView toView(Document document) {
        return new KnowledgeDocumentView(
                document.getId(),
                String.valueOf(document.getMetadata().getOrDefault("filename", "未知文档")),
                document.getText() == null ? "" : document.getText(),
                String.valueOf(document.getMetadata().getOrDefault("version", "builtin")),
                Map.copyOf(document.getMetadata())
        );
    }

    /** 返回给只读接口的知识文档视图。 */
    public record KnowledgeDocumentView(
            String id,
            String title,
            String markdownContent,
            String version,
            Map<String, Object> metadata
    ) {
    }
}
