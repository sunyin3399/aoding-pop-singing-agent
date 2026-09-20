package com.sunyin.aodingagent.controller;

import com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateKnowledge;
import com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus;
import com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeRepository;
import com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus.APPROVED;
import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus.PENDING;
import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus.REJECTED;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeCandidateAdminControllerTest {

    @Test
    void listsAndFiltersCandidatesWithPaginationAndReturnsDetails() throws Exception {
        MemoryStore store = new MemoryStore(candidate("pending-id", PENDING), candidate("approved-id", APPROVED));
        MockMvc mvc = mvc(store);

        mvc.perform(get("/admin/knowledge/candidates").param("status", "PENDING").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("pending-id"))
                .andExpect(jsonPath("$.total").value(1));

        mvc.perform(get("/admin/knowledge/candidates/pending-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markdownContent").value("# 可审核知识\n\n## 训练动作\n- 练习 5 分钟。\n"));
        mvc.perform(get("/admin/knowledge/candidates/missing")).andExpect(status().isNotFound());
    }

    @Test
    void approvesAndRejectsWithReviewNotes() throws Exception {
        MemoryStore store = new MemoryStore(candidate("approve-id", PENDING), candidate("reject-id", PENDING));
        MockMvc mvc = mvc(store);

        mvc.perform(post("/admin/knowledge/candidates/approve-id/approve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reviewNote\":\"来源已核验\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewNote").value("来源已核验"));
        mvc.perform(post("/admin/knowledge/candidates/reject-id/reject")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reviewNote\":\"证据不足\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    private MockMvc mvc(MemoryStore store) {
        CandidateKnowledgeReviewService reviews = new CandidateKnowledgeReviewService() {
            @Override
            public CandidateKnowledge approve(String id, String note) {
                return store.review(id, APPROVED, note);
            }

            @Override
            public CandidateKnowledge reject(String id, String note) {
                return store.review(id, REJECTED, note);
            }
        };
        return MockMvcBuilders.standaloneSetup(new KnowledgeCandidateAdminController(store, reviews)).build();
    }

    private CandidateKnowledge candidate(String id, CandidateStatus status) {
        return new CandidateKnowledge(id, "如何练声", "可审核知识", "# 可审核知识\n\n## 训练动作\n- 练习 5 分钟。\n",
                List.of(), "hash", status, Instant.parse("2026-08-11T06:00:00Z"), null, null, List.of());
    }

    private static final class MemoryStore implements CandidateKnowledgeRepository {
        private final Map<String, CandidateKnowledge> values = new LinkedHashMap<>();

        private MemoryStore(CandidateKnowledge... candidates) {
            for (CandidateKnowledge candidate : candidates) values.put(candidate.id(), candidate);
        }

        CandidateKnowledge review(String id, CandidateStatus status, String note) {
            CandidateKnowledge current = values.get(id);
            CandidateKnowledge reviewed = new CandidateKnowledge(current.id(), current.originalQuery(), current.title(),
                    current.markdownContent(), current.sources(), current.contentHash(), status, current.createdAt(),
                    Instant.parse("2026-08-11T07:00:00Z"), note, current.publishedDocumentIds());
            return save(reviewed);
        }

        @Override public CandidateKnowledge save(CandidateKnowledge candidate) { values.put(candidate.id(), candidate); return candidate; }
        @Override public Optional<CandidateKnowledge> findById(String id) { return Optional.ofNullable(values.get(id)); }
        @Override public List<CandidateKnowledge> findAll(CandidateStatus status) {
            return values.values().stream().filter(value -> status == null || value.status() == status).toList();
        }
    }
}
