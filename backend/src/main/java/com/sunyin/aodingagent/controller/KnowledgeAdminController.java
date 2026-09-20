package com.sunyin.aodingagent.controller;

import com.sunyin.aodingagent.rag.MusicAppDocumentLoader;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

@RestController
@RequestMapping("/admin/knowledge")
@Slf4j
public class KnowledgeAdminController {

    private final VectorStore vectorStore;
    private final MusicAppDocumentLoader musicAppDocumentLoader;

    public KnowledgeAdminController(VectorStore vectorStore, MusicAppDocumentLoader musicAppDocumentLoader) {
        this.vectorStore = vectorStore;
        this.musicAppDocumentLoader = musicAppDocumentLoader;
    }

    /**
     * 手动触发：解析本地 Markdown 并向量化入库
     */
    private static final int BATCH_SIZE = 25;

    @PostMapping("/init")
    public String initKnowledgeBase() {
        log.info("开始加载本地 Markdown 文档...");
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 读取文档
            List<Document> documents = musicAppDocumentLoader.loadMarkdowns();
            
            // 2. 高级做法：在这里你可以给 Document 加上时间戳或版本号元数据
            if (documents.isEmpty()) {
                log.warn("未找到任何 Markdown 文档");
                return "未找到任何 Markdown 文档";
            }
            
            documents.forEach(doc -> doc.getMetadata().put("version", "v1.0"));

            // 按文件替换旧片段，防止重复初始化产生副本，也清理文档缩短后的残留块。
            Set<String> fileNames = new LinkedHashSet<>();
            documents.forEach(document -> fileNames.add(document.getMetadata().get("filename").toString()));
            for (String fileName : fileNames) {
                vectorStore.delete("filename == '" + escapeFilterValue(fileName) + "'");
            }
            
            // 3. 写入外置 PGVector 数据库（会自动调用 Embedding 模型并将结果存入 PG）
            int total = documents.size();
            int batches = (total + BATCH_SIZE - 1) / BATCH_SIZE;
            log.info("共 {} 个文档块，将分 {} 批处理", total, batches);
            
            for (int i = 0; i < total; i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, total);
                List<Document> batch = documents.subList(i, end);
                vectorStore.add(batch);
                log.info("第 {}/{} 批处理完成，处理 {} 个文档块", (i / BATCH_SIZE) + 1, batches, batch.size());
            }
            
            long costTime = System.currentTimeMillis() - startTime;
            log.info("知识库向量化入库完成！耗时: {} ms，共处理文档块: {} 个", costTime, total);
            return "知识库初始化成功";
        } catch (Exception e) {
            log.error("知识库初始化失败", e);
            return "知识库初始化失败: " + e.getMessage();
        }
    }

    private String escapeFilterValue(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
