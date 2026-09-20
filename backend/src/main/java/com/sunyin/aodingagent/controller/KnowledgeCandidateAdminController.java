package com.sunyin.aodingagent.controller;

import com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateKnowledge;
import com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus;
import com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeRepository;
import com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/knowledge/candidates")
public class KnowledgeCandidateAdminController {

    private final CandidateKnowledgeRepository repository;
    private final CandidateKnowledgeReviewService reviews;

    public KnowledgeCandidateAdminController(
            CandidateKnowledgeRepository repository,
            CandidateKnowledgeReviewService reviews
    ) {
        this.repository = repository;
        this.reviews = reviews;
    }

    @GetMapping
    public CandidatePage list(
            @RequestParam(required = false) CandidateStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        List<CandidateKnowledge> all = repository.findAll(status);
        int from = Math.min(safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        int totalPages = all.isEmpty() ? 0 : (int) Math.ceil((double) all.size() / safeSize);
        return new CandidatePage(all.subList(from, to), safePage, safeSize, all.size(), totalPages);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CandidateKnowledge> get(@PathVariable String id) {
        return repository.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<CandidateKnowledge> approve(@PathVariable String id, @RequestBody ReviewRequest request) {
        if (repository.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(reviews.approve(id, request.reviewNote()));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<CandidateKnowledge> reject(@PathVariable String id, @RequestBody ReviewRequest request) {
        if (repository.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(reviews.reject(id, request.reviewNote()));
    }

    public record ReviewRequest(String reviewNote) {
    }

    public record CandidatePage(
            List<CandidateKnowledge> items,
            int page,
            int size,
            long total,
            int totalPages
    ) {
    }
}
