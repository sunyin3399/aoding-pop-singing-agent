package com.sunyin.aodingagent.knowledge.candidate;

import com.sunyin.aodingagent.research.ResearchModels;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateKnowledgePostProcessorTest {

    @Test
    void doesNotCallGeneratorForTransientRequests() {
        InMemoryRepository repository = new InMemoryRepository();
        AtomicInteger generatorCalls = new AtomicInteger();
        CandidateKnowledgeGenerator generator = (request, result) -> {
            generatorCalls.incrementAndGet();
            return new CandidateKnowledgeGenerator.GeneratedCandidate(false, "不应调用", "", "");
        };
        CandidateKnowledgePostProcessor processor = new CandidateKnowledgePostProcessor(
                generator, new CandidateKnowledgeWorkflow(repository));

        ResearchModels.CandidateKnowledgeOutcome song = processor.process("推荐几首歌曲",
                researchResult(ResearchModels.DeliverableType.SONG_RECOMMENDATION));
        ResearchModels.CandidateKnowledgeOutcome technique = processor.process("《Shallow》怎么唱",
                researchResult(ResearchModels.DeliverableType.EXERCISE_GUIDE));
        ResearchModels.CandidateKnowledgeOutcome tutorial = processor.process("找《她说》的教学视频",
                researchResult(ResearchModels.DeliverableType.TUTORIAL_LIST));

        assertThat(List.of(song, technique, tutorial)).allSatisfy(outcome ->
                assertThat(outcome.status()).isEqualTo(ResearchModels.CandidateKnowledgeStatus.NOT_ELIGIBLE));
        assertThat(generatorCalls).hasValue(0);
        assertThat(repository.values).isEmpty();
    }

    @Test
    void generatesValidatesAndPersistsReusableKnowledge() {
        InMemoryRepository repository = new InMemoryRepository();
        CandidateKnowledgeGenerator generator = (request, result) ->
                new CandidateKnowledgeGenerator.GeneratedCandidate(true, "可复用训练方法", "气息训练", validMarkdown());
        CandidateKnowledgePostProcessor processor = new CandidateKnowledgePostProcessor(
                generator, new CandidateKnowledgeWorkflow(repository));

        ResearchModels.CandidateKnowledgeOutcome outcome = processor.process("制定一个月的系统训练计划",
                researchResult(ResearchModels.DeliverableType.EXERCISE_GUIDE));

        assertThat(outcome.status()).isEqualTo(ResearchModels.CandidateKnowledgeStatus.PENDING_REVIEW);
        assertThat(outcome.candidateId()).isNotBlank();
        assertThat(repository.values).singleElement()
                .satisfies(candidate -> assertThat(candidate.status())
                        .isEqualTo(CandidateKnowledgeModels.CandidateStatus.PENDING));
    }

    private ResearchModels.ResearchResult researchResult(ResearchModels.DeliverableType deliverable) {
        ResearchModels.ResearchTaskContract contract = new ResearchModels.ResearchTaskContract(
                ResearchModels.ResearchTaskType.GENERAL_RESEARCH, "气息训练",
                deliverable, 1, 1,
                List.of(), List.of(), true, 3, 3);
        ResearchModels.SourceCandidate source = new ResearchModels.SourceCandidate(
                "source-1", "专业资料", "https://example.org/breath", "https://example.org/breath",
                "训练方法", "气息训练", "摘要", ResearchModels.ScrapeStatus.SCRAPED, 1,
                null, "包含可复用的气息训练原理和练习步骤。", ResearchModels.SourceQualityStatus.RELEVANT);
        return new ResearchModels.ResearchResult(ResearchModels.ResearchStatus.SUCCESS, contract,
                List.of(source), List.of(), 1, 1, 0);
    }

    private static String validMarkdown() {
        return """
                # 气息训练

                ## 原理
                保持稳定而不过度的呼气压力。

                ## 训练动作
                每次练习 5 分钟，共 3 组。

                ## 常见错误
                不要耸肩或憋气。

                ## 适用范围
                适用于无疼痛的基础发声训练。

                ## 安全提醒
                出现头晕、疼痛或持续沙哑时停止练习。

                ## 资料来源
                - [专业资料](https://example.org/breath)
                """;
    }

    private static final class InMemoryRepository implements CandidateKnowledgeRepository {
        private final List<CandidateKnowledgeModels.CandidateKnowledge> values = new ArrayList<>();

        @Override
        public CandidateKnowledgeModels.CandidateKnowledge save(CandidateKnowledgeModels.CandidateKnowledge candidate) {
            values.add(candidate);
            return candidate;
        }

        @Override
        public Optional<CandidateKnowledgeModels.CandidateKnowledge> findById(String id) {
            return values.stream().filter(candidate -> candidate.id().equals(id)).findFirst();
        }

        @Override
        public List<CandidateKnowledgeModels.CandidateKnowledge> findAll(
                CandidateKnowledgeModels.CandidateStatus status
        ) {
            return values.stream().filter(candidate -> status == null || candidate.status() == status).toList();
        }
    }
}
