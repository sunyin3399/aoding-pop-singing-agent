package com.sunyin.aodingagent.tools;

import com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus;
import com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeRepository;
import com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeWorkflow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateKnowledge;
import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.ExternalSource;
import static org.assertj.core.api.Assertions.assertThat;

class CandidateKnowledgeToolTest {

    @Test
    void savesAValidStructuredDraftAndRejectsMissingSources() {
        MemoryRepository repository = new MemoryRepository();
        CandidateKnowledgeTool tool = new CandidateKnowledgeTool(new CandidateKnowledgeWorkflow(repository));

        var valid = tool.saveCandidateKnowledge(new CandidateKnowledgeTool.SaveCandidateRequest(
                "如何练鼻音", "鼻音训练", markdown(),
                List.of(new ExternalSource("来源", "https://example.org/vocal"))));
        var invalid = tool.saveCandidateKnowledge(new CandidateKnowledgeTool.SaveCandidateRequest(
                "如何练鼻音", "鼻音训练", markdown(), List.of()));

        assertThat(valid.success()).isTrue();
        assertThat(valid.code()).isEqualTo("CANDIDATE_PENDING_REVIEW");
        assertThat(repository.values).hasSize(1);
        assertThat(invalid.success()).isFalse();
        assertThat(invalid.code()).isEqualTo("INVALID_CANDIDATE");
    }

    private String markdown() {
        return """
                # 鼻音训练
                ## 原理
                区分正常共鸣。
                ## 训练动作
                - 练习 5 分钟。
                ## 常见错误
                - 不捏鼻。
                ## 适用范围
                日常训练。
                ## 安全提醒
                疼痛时停止。
                ## 资料来源
                - [来源](https://example.org/vocal)
                """;
    }

    private static final class MemoryRepository implements CandidateKnowledgeRepository {
        private final List<CandidateKnowledge> values = new ArrayList<>();
        @Override public CandidateKnowledge save(CandidateKnowledge candidate) { values.add(candidate); return candidate; }
        @Override public Optional<CandidateKnowledge> findById(String id) { return values.stream().filter(value -> value.id().equals(id)).findFirst(); }
        @Override public List<CandidateKnowledge> findAll(CandidateStatus status) { return List.copyOf(values); }
    }
}
