package com.sunyin.aodingagent.controller;

import com.sunyin.aodingagent.knowledge.KnowledgeDocumentCatalog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/knowledge/documents")
public class KnowledgeDocumentController {

    private final KnowledgeDocumentCatalog catalog;

    public KnowledgeDocumentController(KnowledgeDocumentCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<KnowledgeDocumentCatalog.KnowledgeDocumentView> get(
            @PathVariable String documentId
    ) {
        return catalog.findApproved(documentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
