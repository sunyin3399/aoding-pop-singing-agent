package com.sunyin.aodingagent.controller;

import com.sunyin.aodingagent.knowledge.KnowledgeDocumentCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeDocumentControllerTest {

    @Test
    void returnsAnApprovedDocumentByOpaqueId() {
        KnowledgeDocumentCatalog catalog = new KnowledgeDocumentCatalog(List.of(Document.builder()
                .id("safe-id")
                .text("# 换声区\n正文")
                .metadata("filename", "换声区.md")
                .metadata("version", "v1")
                .build()));
        KnowledgeDocumentController controller = new KnowledgeDocumentController(catalog);

        ResponseEntity<KnowledgeDocumentCatalog.KnowledgeDocumentView> response = controller.get("safe-id");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("换声区.md", response.getBody().title());
        assertEquals("# 换声区\n正文", response.getBody().markdownContent());
    }

    @Test
    void doesNotTreatAnUnknownIdAsAFilePath() {
        KnowledgeDocumentController controller = new KnowledgeDocumentController(new KnowledgeDocumentCatalog(List.of()));

        ResponseEntity<KnowledgeDocumentCatalog.KnowledgeDocumentView> response = controller.get("../../application.yml");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
