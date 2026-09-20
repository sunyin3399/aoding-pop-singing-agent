package com.sunyin.aodingagent.trainingplan;

import com.sunyin.aodingagent.knowledge.Citation;
import com.sunyin.aodingagent.knowledge.VocalKnowledgeRetriever;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.Exercise;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ExerciseCategory.COOL_DOWN;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ExerciseCategory.MAIN;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ExerciseCategory.WARM_UP;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.OutputFormat.PAGE;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingDay;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPhase;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlan;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanRequest;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.FAILED;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.NEEDS_CLARIFICATION;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.SAFETY_BLOCKED;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.SUCCESS;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.VALIDATION_FAILED;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.VocalCondition.MILD_PAIN_OR_HOARSENESS;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.VocalCondition.SIGNIFICANT_DISCOMFORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingPlanWorkflowTest {

    @Test
    void clarificationDoesNotCallRetrievalOrGeneration() {
        RecordingRetriever retriever = new RecordingRetriever(List.of());
        RecordingGenerator generator = new RecordingGenerator();
        TrainingPlanWorkflow workflow = workflow(retriever, generator);

        TrainingPlanModels.TrainingPlanResult result = workflow.create(new TrainingPlanRequest(
                "c1", "", null, null, null, List.of(), null, null, null, PAGE));

        assertEquals(NEEDS_CLARIFICATION, result.status());
        assertEquals(0, retriever.calls);
        assertEquals(0, generator.generateCalls);
    }

    @Test
    void safetyBlockDoesNotCallRetrievalOrGeneration() {
        RecordingRetriever retriever = new RecordingRetriever(List.of());
        RecordingGenerator generator = new RecordingGenerator();
        TrainingPlanWorkflow workflow = workflow(retriever, generator);

        TrainingPlanModels.TrainingPlanResult result = workflow.create(request(true));

        assertEquals(SAFETY_BLOCKED, result.status());
        assertEquals(0, retriever.calls);
        assertEquals(0, generator.generateCalls);
    }

    @Test
    void returnsAValidFirstDraftWithItsRetrievedCitations() {
        Citation citation = citation();
        RecordingGenerator generator = new RecordingGenerator(validPlan());

        TrainingPlanModels.TrainingPlanResult result = workflow(
                new RecordingRetriever(List.of(citation)), generator).create(request(false));

        assertEquals(SUCCESS, result.status());
        assertEquals(List.of(citation), result.citations());
        assertEquals(1, generator.generateCalls);
        assertEquals(0, generator.repairCalls);
    }

    @Test
    void capsDiscomfortPlansBeforeCallingTheModel() {
        RecordingGenerator mildGenerator = new RecordingGenerator(planWithMinutes(15, 3, 6, 3));
        TrainingPlanModels.TrainingPlanResult mild = workflow(new RecordingRetriever(List.of()), mildGenerator)
                .create(request(MILD_PAIN_OR_HOARSENESS, false));
        assertEquals(SUCCESS, mild.status());
        assertEquals(15, mildGenerator.lastRequest.minutesPerDay());
        assertEquals(MILD_PAIN_OR_HOARSENESS, mild.vocalCondition());

        RecordingGenerator significantGenerator = new RecordingGenerator(planWithMinutes(10, 2, 4, 2));
        TrainingPlanModels.TrainingPlanResult significant = workflow(new RecordingRetriever(List.of()), significantGenerator)
                .create(request(SIGNIFICANT_DISCOMFORT, true));
        assertEquals(SUCCESS, significant.status());
        assertEquals(10, significantGenerator.lastRequest.minutesPerDay());
        assertEquals(SIGNIFICANT_DISCOMFORT, significant.vocalCondition());
    }

    @Test
    void addsFixedRiskNoticesWithoutASecondModelCall() {
        TrainingPlan source = planWithMinutes(15, 3, 6, 3);
        TrainingPlan withoutNotices = new TrainingPlan(source.title(), source.goal(), source.durationDays(),
                source.minutesPerDay(), source.phases(), source.days(), List.of(), source.citationIds());
        RecordingGenerator generator = new RecordingGenerator(withoutNotices);

        TrainingPlanModels.TrainingPlanResult result = workflow(new RecordingRetriever(List.of()), generator)
                .create(request(MILD_PAIN_OR_HOARSENESS, false));

        assertEquals(SUCCESS, result.status());
        assertEquals(0, generator.repairCalls);
        assertTrue(result.plan().safetyNotices().stream().anyMatch(notice -> notice.contains("休息") && notice.contains("停止")));
    }

    @Test
    void repairsAnInvalidDraftExactlyOnce() {
        RecordingGenerator generator = new RecordingGenerator(invalidPlan(), validPlan());

        TrainingPlanModels.TrainingPlanResult result = workflow(
                new RecordingRetriever(List.of(citation())), generator).create(request(false));

        assertEquals(SUCCESS, result.status());
        assertEquals(1, generator.generateCalls);
        assertEquals(1, generator.repairCalls);
        assertTrue(generator.lastRepairIssues.stream().anyMatch(issue -> "MISSING_DAY".equals(issue.code())));
    }

    @Test
    void returnsValidationFailedAfterOneInvalidRepair() {
        RecordingGenerator generator = new RecordingGenerator(invalidPlan(), invalidPlan());

        TrainingPlanModels.TrainingPlanResult result = workflow(
                new RecordingRetriever(List.of(citation())), generator).create(request(false));

        assertEquals(VALIDATION_FAILED, result.status());
        assertEquals(1, generator.repairCalls);
        assertTrue(result.plan() == null);
        assertTrue(!result.issues().isEmpty());
    }

    @Test
    void convertsGeneratorFailuresToAFailedResult() {
        TrainingPlanGenerator generator = new TrainingPlanGenerator() {
            @Override
            public TrainingPlan generate(TrainingPlanRequest request, List<Citation> citations) {
                throw new IllegalStateException("model unavailable");
            }

            @Override
            public TrainingPlan repair(TrainingPlanRequest request, TrainingPlan draft,
                                       List<TrainingPlanModels.ValidationIssue> issues, List<Citation> citations) {
                throw new AssertionError("repair must not run");
            }
        };

        TrainingPlanModels.TrainingPlanResult result = workflow(
                new RecordingRetriever(List.of()), generator).create(request(false));

        assertEquals(FAILED, result.status());
        assertTrue(result.issues().getFirst().message().contains("model unavailable"));
    }

    private TrainingPlanWorkflow workflow(VocalKnowledgeRetriever retriever, TrainingPlanGenerator generator) {
        return new DefaultTrainingPlanWorkflow(
                new TrainingPlanInputValidator(), new TrainingPlanValidator(), retriever, generator);
    }

    private TrainingPlanRequest request(boolean risk) {
        return new TrainingPlanRequest("c1", "改善换声", "初学者", 2, 20,
                List.of("高音挤卡"), null, null, risk, PAGE);
    }

    private TrainingPlanRequest request(TrainingPlanModels.VocalCondition condition, boolean proceed) {
        return new TrainingPlanRequest("c1", "改善换声", "初学者", 2, 30,
                List.of("高音挤卡"), null, null, null, PAGE, condition, proceed);
    }

    private TrainingPlan validPlan() {
        return new TrainingPlan("两日计划", "改善换声", 2, 20,
                List.of(new TrainingPhase("基础", "协调", 1, 2, List.of("轻声完成"))),
                List.of(day(1), day(2)), List.of("疼痛时停止"), List.of("doc-1"));
    }

    private TrainingPlan invalidPlan() {
        TrainingPlan valid = validPlan();
        return new TrainingPlan(valid.title(), valid.goal(), valid.durationDays(), valid.minutesPerDay(),
                valid.phases(), List.of(day(1)), valid.safetyNotices(), valid.citationIds());
    }

    private TrainingPlan planWithMinutes(int dailyMinutes, int warmup, int main, int cooldown) {
        List<TrainingDay> days = List.of(
                new TrainingDay(1, "恢复", List.of(exercise(WARM_UP, warmup), exercise(MAIN, main), exercise(COOL_DOWN, cooldown)), "休息复测"),
                new TrainingDay(2, "恢复", List.of(exercise(WARM_UP, warmup), exercise(MAIN, main), exercise(COOL_DOWN, cooldown)), "休息复测"));
        return new TrainingPlan("低强度计划", "恢复", 2, dailyMinutes,
                List.of(new TrainingPhase("恢复", "充分休息", 1, 2, List.of("无不适"))), days,
                List.of("出现疼痛立即停止并休息"), List.of());
    }

    private TrainingDay day(int value) {
        return new TrainingDay(value, "协调", List.of(
                exercise(WARM_UP, 5), exercise(MAIN, 10), exercise(COOL_DOWN, 3)), "录音复测");
    }

    private Exercise exercise(TrainingPlanModels.ExerciseCategory category, int minutes) {
        return new Exercise(category, category.name(), minutes, 2,
                List.of("保持轻声", "按计划完成每组", "避免耸肩或推挤"),
                List.of("疼痛时停止"));
    }

    private Citation citation() {
        return new Citation("doc-1", Citation.CitationType.INTERNAL_KNOWLEDGE, "换声区",
                "/api/ai/knowledge/documents/doc-1", "摘要", 0.9);
    }

    private static final class RecordingRetriever implements VocalKnowledgeRetriever {
        private final List<Citation> citations;
        private int calls;

        private RecordingRetriever(List<Citation> citations) {
            this.citations = citations;
        }

        @Override
        public KnowledgeSearchResult search(String query) {
            calls++;
            return KnowledgeSearchResult.of(query, citations);
        }
    }

    private static final class RecordingGenerator implements TrainingPlanGenerator {
        private final Deque<TrainingPlan> plans = new ArrayDeque<>();
        private int generateCalls;
        private int repairCalls;
        private List<TrainingPlanModels.ValidationIssue> lastRepairIssues = List.of();
        private TrainingPlanRequest lastRequest;

        private RecordingGenerator(TrainingPlan... plans) {
            this.plans.addAll(List.of(plans));
        }

        @Override
        public TrainingPlan generate(TrainingPlanRequest request, List<Citation> citations) {
            generateCalls++;
            lastRequest = request;
            return plans.removeFirst();
        }

        @Override
        public TrainingPlan repair(TrainingPlanRequest request, TrainingPlan draft,
                                   List<TrainingPlanModels.ValidationIssue> issues, List<Citation> citations) {
            repairCalls++;
            lastRepairIssues = List.copyOf(issues);
            return plans.removeFirst();
        }
    }
}
