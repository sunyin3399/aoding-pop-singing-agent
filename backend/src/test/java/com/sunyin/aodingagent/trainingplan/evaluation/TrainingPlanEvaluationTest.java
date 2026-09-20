package com.sunyin.aodingagent.trainingplan.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sunyin.aodingagent.knowledge.Citation;
import com.sunyin.aodingagent.knowledge.VocalKnowledgeRetriever;
import com.sunyin.aodingagent.trainingplan.DefaultTrainingPlanWorkflow;
import com.sunyin.aodingagent.trainingplan.TrainingPlanGenerator;
import com.sunyin.aodingagent.trainingplan.TrainingPlanInputValidator;
import com.sunyin.aodingagent.trainingplan.TrainingPlanModels;
import com.sunyin.aodingagent.trainingplan.TrainingPlanValidator;
import com.sunyin.aodingagent.trainingplan.TrainingPlanWorkflow;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingPlanEvaluationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final TrainingPlanValidator validator = new TrainingPlanValidator();

    @Test
    void evaluatesTheFixedDatasetAndWritesAllFiveMetrics() throws Exception {
        List<EvaluationCase> cases = loadCases();
        List<TrainingPlanMetrics.EvaluationOutcome> outcomes = cases.stream().map(this::evaluate).toList();
        TrainingPlanMetrics.MetricsReport report = TrainingPlanMetrics.calculate(outcomes);

        Path output = Path.of("target/evaluation/training-plan-metrics.json");
        Files.createDirectories(output.getParent());
        objectMapper.writeValue(output.toFile(), report);

        assertEquals(10, report.totalCases());
        assertTrue(report.successRate().ratio() >= 0.99);
        assertTrue(report.clarificationAccuracy().ratio() >= 0.99);
        assertTrue(report.safetyBlockRate().ratio() >= 0.99);
        assertTrue(report.constraintSatisfactionRate().ratio() < 1.0);
        assertTrue(report.citationValidityRate().ratio() < 1.0);
        assertTrue(Files.readString(output).contains("citationValidityRate"));

        System.out.println("训练计划离线评测: " + objectMapper.writeValueAsString(report));
    }

    private TrainingPlanMetrics.EvaluationOutcome evaluate(EvaluationCase testCase) {
        TrainingPlanRequest request = request(testCase.requestVariant());
        TrainingPlan draft = plan(testCase.planVariant());
        List<Citation> citations = citations(testCase.citationsVariant());
        VocalKnowledgeRetriever retriever = query -> VocalKnowledgeRetriever.KnowledgeSearchResult.of(query, citations);
        TrainingPlanGenerator generator = new FixedGenerator(draft);
        TrainingPlanWorkflow workflow = new DefaultTrainingPlanWorkflow(
                new TrainingPlanInputValidator(), validator, retriever, generator);

        TrainingPlanResult result = workflow.create(request);
        TrainingPlanValidator.ConstraintScore score = draft == null
                ? new TrainingPlanValidator.ConstraintScore(0, 0)
                : validator.score(request, draft, citations);
        int totalCitations = draft == null ? 0 : draft.citationIds().size();
        int validCitations = draft == null ? 0 : (int) draft.citationIds().stream()
                .filter(id -> citations.stream().anyMatch(citation -> citation.id().equals(id)))
                .count();

        assertEquals(testCase.expectedStatus(), result.status().name(), testCase.id());
        return new TrainingPlanMetrics.EvaluationOutcome(
                testCase.id(), testCase.expectedSuccess(), result.status(), testCase.expectedClarification(),
                testCase.expectedSafetyBlock(), score.satisfied(), score.total(), validCitations, totalCitations);
    }

    private List<EvaluationCase> loadCases() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/evaluation/training-plan-cases.jsonl");
        if (stream == null) throw new IllegalStateException("缺少固定评测集 training-plan-cases.jsonl");
        List<EvaluationCase> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) cases.add(objectMapper.readValue(line, EvaluationCase.class));
            }
        }
        return cases;
    }

    private TrainingPlanRequest request(String variant) {
        if ("missing".equals(variant)) {
            return new TrainingPlanRequest("c1", "", null, null, null, List.of(), null, null, null, PAGE);
        }
        if ("conflict".equals(variant)) {
            return new TrainingPlanRequest("c1", "改善换声", "初学者", 31, 4,
                    List.of("挤卡"), null, null, false, PAGE);
        }
        return new TrainingPlanRequest("c1", "改善换声", "初学者", 2, 20,
                List.of("高音挤卡"), null, null, "risk".equals(variant), PAGE);
    }

    private TrainingPlan plan(String variant) {
        if ("none".equals(variant)) return null;
        List<TrainingDay> days = new ArrayList<>(List.of(day(1), day(2)));
        List<TrainingPhase> phases = new ArrayList<>(List.of(
                new TrainingPhase("基础", "协调", 1, 2, List.of("轻声完成"))));
        List<String> citationIds = new ArrayList<>(List.of("doc-1"));
        if ("missing-day".equals(variant)) days.removeLast();
        if ("overrun".equals(variant)) days.set(0, dayWithMainMinutes(1, 20));
        if ("phase-gap".equals(variant)) phases.set(0, new TrainingPhase("基础", "协调", 2, 2, List.of("完成")));
        if ("missing-category".equals(variant)) days.set(0, new TrainingDay(1, "协调",
                List.of(exercise(WARM_UP, 5), exercise(MAIN, 10)), "复测"));
        if ("no-citation".equals(variant)) citationIds.clear();
        if ("invented-citation".equals(variant)) citationIds = new ArrayList<>(List.of("invented"));
        return new TrainingPlan("两日计划", "改善换声", 2, 20, phases, days,
                List.of("疼痛时停止"), citationIds);
    }

    private List<Citation> citations(String variant) {
        if ("none".equals(variant)) return List.of();
        return List.of(new Citation("doc-1", Citation.CitationType.INTERNAL_KNOWLEDGE,
                "换声区", "/api/ai/knowledge/documents/doc-1", "摘要", 0.9));
    }

    private TrainingDay day(int day) {
        return new TrainingDay(day, "协调", List.of(
                exercise(WARM_UP, 5), exercise(MAIN, 10), exercise(COOL_DOWN, 3)), "复测");
    }

    private TrainingDay dayWithMainMinutes(int day, int mainMinutes) {
        return new TrainingDay(day, "协调", List.of(
                exercise(WARM_UP, 5), exercise(MAIN, mainMinutes), exercise(COOL_DOWN, 3)), "复测");
    }

    private Exercise exercise(TrainingPlanModels.ExerciseCategory category, int minutes) {
        return new Exercise(category, category.name(), minutes, 2,
                List.of("保持站立放松", "轻声完成两组", "避免抬下巴和挤卡"),
                List.of("疼痛时停止"));
    }

    private record EvaluationCase(
            String id,
            String requestVariant,
            String planVariant,
            String citationsVariant,
            String expectedStatus,
            boolean expectedSuccess,
            boolean expectedClarification,
            boolean expectedSafetyBlock
    ) {
    }

    private static final class FixedGenerator implements TrainingPlanGenerator {
        private final TrainingPlan plan;

        private FixedGenerator(TrainingPlan plan) {
            this.plan = plan;
        }

        @Override
        public TrainingPlan generate(TrainingPlanRequest request, List<Citation> citations) {
            return plan;
        }

        @Override
        public TrainingPlan repair(TrainingPlanRequest request, TrainingPlan draft,
                                   List<TrainingPlanModels.ValidationIssue> issues, List<Citation> citations) {
            return plan;
        }
    }
}
