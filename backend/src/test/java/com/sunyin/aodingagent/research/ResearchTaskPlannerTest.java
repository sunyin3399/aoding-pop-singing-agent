package com.sunyin.aodingagent.research;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchTaskPlannerTest {

    @Test
    void usesDeterministicLightweightContractWithoutCallingFullPlanner() {
        AtomicInteger fullCalls = new AtomicInteger();
        ResearchTaskPlanner planner = ResearchTaskPlanner.preferLightweight(request -> {
            fullCalls.incrementAndGet();
            return ResearchTaskPlanner.researchSummaryContract(request);
        });

        ResearchModels.ResearchTaskContract technique = planner.plan("《Shallow》演唱技巧");
        ResearchModels.ResearchTaskContract video = planner.plan("找《她说》的教学视频");

        assertThat(fullCalls).hasValue(0);
        assertThat(technique.deliverable()).isEqualTo(ResearchModels.DeliverableType.EXERCISE_GUIDE);
        assertThat(technique.maxSearchCalls()).isEqualTo(2);
        assertThat(technique.maxScrapeCalls()).isEqualTo(4);
        assertThat(video.deliverable()).isEqualTo(ResearchModels.DeliverableType.TUTORIAL_LIST);
        assertThat(video.minimumSources()).isEqualTo(1);
    }

    @Test
    void keepsSystematicTrainingOnFullPlanner() {
        AtomicInteger fullCalls = new AtomicInteger();
        ResearchTaskPlanner planner = ResearchTaskPlanner.preferLightweight(request -> {
            fullCalls.incrementAndGet();
            return ResearchTaskPlanner.researchSummaryContract(request);
        });

        ResearchModels.ResearchTaskContract contract = planner.plan("制定一个月的系统训练计划");

        assertThat(fullCalls).hasValue(1);
        assertThat(contract.deliverable()).isEqualTo(ResearchModels.DeliverableType.RESEARCH_SUMMARY);
        assertThat(contract.maxSearchCalls()).isEqualTo(5);
    }

    @Test
    void keepsBroadVideoResearchOnFullPlanner() {
        AtomicInteger fullCalls = new AtomicInteger();
        ResearchTaskPlanner planner = ResearchTaskPlanner.preferLightweight(request -> {
            fullCalls.incrementAndGet();
            return ResearchTaskPlanner.defaultResourceListContract(request, 5);
        });

        planner.plan("整理一批适合初学者的教学视频");

        assertThat(fullCalls).hasValue(1);
    }

    @Test
    void usesResourceListDefaultsWhenStructuredPlanningFails() {
        ResearchTaskPlanner planner = ResearchTaskPlanner.withGenerator(request -> {
            throw new IllegalArgumentException("malformed model output");
        });

        ResearchModels.ResearchTaskContract contract = planner.plan("整理一份练声资源清单");

        assertThat(contract.minimumItems()).isEqualTo(5);
        assertThat(contract.categories()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(contract.requiredFields())
                .containsExactly("name", "url", "purpose", "usageAdvice");
        assertThat(contract.maxSearchCalls()).isEqualTo(6);
        assertThat(contract.maxScrapeCalls()).isEqualTo(12);
    }

    @Test
    void preservesAnExplicitUserMinimumAboveTheModelSuggestion() {
        ResearchTaskPlanner planner = ResearchTaskPlanner.withGenerator(request ->
                ResearchTaskPlanner.defaultResourceListContract("练声", 3));

        ResearchModels.ResearchTaskContract contract = planner.plan("请至少整理 8 个练声资源");

        assertThat(contract.minimumItems()).isEqualTo(8);
    }

    @Test
    void fallsBackToSongRecommendationsInsteadOfWebResourcesForSongRequests() {
        ResearchTaskPlanner planner = ResearchTaskPlanner.withGenerator(request -> {
            throw new IllegalArgumentException("malformed model output");
        });

        ResearchModels.ResearchTaskContract contract = planner.plan("调研适合初学者的歌曲，我是王力宏粉丝");

        assertThat(contract.deliverable()).isEqualTo(ResearchModels.DeliverableType.SONG_RECOMMENDATION);
        assertThat(contract.minimumItems()).isEqualTo(5);
        assertThat(contract.minimumSources()).isEqualTo(2);
        assertThat(contract.requiredFields()).contains("songName", "artist", "practiceFocus", "sourceIds");
        assertThat(contract.requiredFields()).doesNotContain("url", "purpose", "usageAdvice");
        assertThat(contract.categories()).extracting(ResearchModels.ResearchCategory::name)
                .containsExactly("推荐歌曲", "暂缓歌曲与难点", "教学辅助");
        assertThat(contract.requiredFields()).contains("recommendationLevel", "cautions", "tutorialResourceIds");
    }

    @Test
    void keepsTutorialLinksAndExerciseStepsScenarioSpecific() {
        ResearchModels.ResearchTaskContract tutorials = ResearchTaskPlanner.fallbackContract(
                "找一些换声区教学视频", 5);
        ResearchModels.ResearchTaskContract guide = ResearchTaskPlanner.fallbackContract(
                "换声区应该怎么练", 5);

        assertThat(tutorials.deliverable()).isEqualTo(ResearchModels.DeliverableType.TUTORIAL_LIST);
        assertThat(tutorials.requiredFields()).contains("url", "teachingFocus", "advice");
        assertThat(tutorials.requiredFields()).doesNotContain("usageAdvice");
        assertThat(guide.deliverable()).isEqualTo(ResearchModels.DeliverableType.EXERCISE_GUIDE);
        assertThat(guide.requiredFields()).contains("steps", "durationOrRepetitions", "safetyNotes");
    }

    @Test
    void routesComparisonAndOpenQuestionsAwayFromResourceLists() {
        ResearchModels.ResearchTaskContract comparison = ResearchTaskPlanner.fallbackContract(
                "胸声和混声有什么区别，哪个更适合高音", 2);
        ResearchModels.ResearchTaskContract summary = ResearchTaskPlanner.fallbackContract(
                "为什么换声区容易破音", 1);

        assertThat(comparison.deliverable()).isEqualTo(ResearchModels.DeliverableType.COMPARISON_TABLE);
        assertThat(comparison.requiredFields()).contains("strengths", "limitations", "suitableFor");
        assertThat(summary.deliverable()).isEqualTo(ResearchModels.DeliverableType.RESEARCH_SUMMARY);
        assertThat(summary.requiredFields()).contains("conclusion", "evidence", "limitations");
    }

    @Test
    void normalizesModelFieldsForKnownDeliverables() {
        ResearchTaskPlanner planner = ResearchTaskPlanner.withGenerator(request ->
                new ResearchModels.ResearchTaskContract(
                        ResearchModels.ResearchTaskType.RESOURCE_RESEARCH, request,
                        ResearchModels.DeliverableType.SONG_RECOMMENDATION, 5, 5,
                        java.util.List.of(), java.util.List.of("name", "url", "purpose", "usageAdvice"),
                        false, 6, 12));

        ResearchModels.ResearchTaskContract contract = planner.plan("适合初学者的歌曲");

        assertThat(contract.requiredFields()).contains("songName", "artist", "sourceIds");
        assertThat(contract.requiredFields()).doesNotContain("url", "purpose");
        assertThat(contract.minimumSources()).isEqualTo(2);
    }
}
