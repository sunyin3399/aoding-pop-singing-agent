package com.sunyin.aodingagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
@Slf4j
public class MusicAppDocumentLoader {

    private final ResourcePatternResolver resourcePatternResolver;
    private final MarkdownKnowledgeDocumentFactory documentFactory;

    MusicAppDocumentLoader(
            ResourcePatternResolver resourcePatternResolver,
            MarkdownKnowledgeDocumentFactory documentFactory
    ) {
        this.resourcePatternResolver = resourcePatternResolver;
        this.documentFactory = documentFactory;
    }

    public List<Document> loadMarkdowns() {
        List<Document> allDocuments = new ArrayList<>();
        try {
            // 这里可以修改为你要加载的多个 Markdown 文件的路径模式
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                String markdown = resource.getContentAsString(StandardCharsets.UTF_8);
                allDocuments.addAll(documentFactory.splitMarkdown(fileName, markdown, Map.of()));
            }
        } catch (IOException e) {
            log.error("Markdown 文档加载失败", e);
        }
        return allDocuments;
    }

}
