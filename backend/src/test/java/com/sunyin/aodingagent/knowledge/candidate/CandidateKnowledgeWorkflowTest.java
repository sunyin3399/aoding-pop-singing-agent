package com.sunyin.aodingagent.knowledge.candidate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus.PENDING;
import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.ExternalSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateKnowledgeWorkflowTest {

    @TempDir
    Path tempDir;

    @Test
    void savesPendingDraftWithDeterministicHashAndReloadsItFromDisk() {
        FileCandidateKnowledgeRepository repository = repository();
        CandidateKnowledgeWorkflow workflow = new CandidateKnowledgeWorkflow(repository);

        var saved = workflow.saveDraft(
                "如何减少鼻音",
                "减少流行演唱中过重的鼻音",
                validMarkdown(),
                List.of(new ExternalSource("Voice Foundation", "https://example.org/nasal-voice")));

        assertThat(saved.status()).isEqualTo(PENDING);
        assertThat(saved.contentHash()).isEqualTo(
                "f49e03016ea6619e67b3bc74a6757a54efc610f27fa0ecf4119d46229827242c");
        assertThat(saved.createdAt()).isNotNull();
        assertThat(repository().findById(saved.id())).contains(saved);
    }

    @Test
    void refusesDraftWithoutAValidHttpSource() {
        CandidateKnowledgeWorkflow workflow = new CandidateKnowledgeWorkflow(repository());

        assertThatThrownBy(() -> workflow.saveDraft(
                "鼻音", "鼻音训练", validMarkdown(),
                List.of(new ExternalSource("本地文件", "file:///tmp/source.md"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP");
    }

    @Test
    void refusesMarkdownThatCannotBeReviewedAsACompleteKnowledgeDocument() {
        CandidateKnowledgeWorkflow workflow = new CandidateKnowledgeWorkflow(repository());

        assertThatThrownBy(() -> workflow.saveDraft(
                "鼻音", "鼻音训练", "一段没有结构的搜索摘要",
                List.of(new ExternalSource("来源", "https://example.org/source"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Markdown");
    }

    private FileCandidateKnowledgeRepository repository() {
        return new FileCandidateKnowledgeRepository(tempDir, new ObjectMapper().findAndRegisterModules());
    }

    private String validMarkdown() {
        return """
                # 减少流行演唱中过重的鼻音

                ## 原理
                先区分正常的鼻腔共鸣与过度鼻音。

                ## 训练动作
                ### 元音对比
                - 练习 5 分钟，交替发 Ah 与 Mm。

                ## 常见错误
                - 不要捏鼻强行改变音色。

                ## 适用范围
                适用于无疼痛的日常音色训练。

                ## 安全提醒
                出现疼痛或持续嘶哑时停止训练并就医。

                ## 资料来源
                - [Voice Foundation](https://example.org/nasal-voice)
                """;
    }
}
