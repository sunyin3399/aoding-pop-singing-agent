package com.sunyin.aodingagent.trainingplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.knowledge.Citation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

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
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ValidationIssue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiTrainingPlanGeneratorTest {

    @Test
    void convertsStructuredModelJsonAndConstrainsThePrompt() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingModel model = new RecordingModel(objectMapper.writeValueAsString(plan()));
        SpringAiTrainingPlanGenerator generator = new SpringAiTrainingPlanGenerator(model, objectMapper);

        TrainingPlan generated = generator.generate(request(), List.of(citation()));

        assertEquals("两日计划", generated.title());
        assertTrue(model.lastPrompt.contains("doc-1"));
        assertTrue(model.lastPrompt.contains("WARM_UP"));
        assertTrue(model.lastPrompt.contains("不要输出 Markdown"));
    }

    @Test
    void repairPromptContainsTheExactValidationIssue() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingModel model = new RecordingModel(objectMapper.writeValueAsString(plan()));
        SpringAiTrainingPlanGenerator generator = new SpringAiTrainingPlanGenerator(model, objectMapper);

        generator.repair(request(), plan(),
                List.of(new ValidationIssue("MISSING_DAY", "days[2]", "缺少第 2 天训练")),
                List.of(citation()));

        assertTrue(model.lastPrompt.contains("MISSING_DAY"));
        assertTrue(model.lastPrompt.contains("缺少第 2 天训练"));
        assertTrue(model.lastPrompt.contains("只修复列出的问题"));
    }

    private TrainingPlanRequest request() {
        return new TrainingPlanRequest("c1", "改善换声", "初学者", 2, 20,
                List.of("高音挤卡"), null, null, false, PAGE);
    }

    private TrainingPlan plan() {
        return new TrainingPlan("两日计划", "改善换声", 2, 20,
                List.of(new TrainingPhase("基础", "协调", 1, 2, List.of("轻声完成"))),
                List.of(day(1), day(2)), List.of("疼痛时停止"), List.of("doc-1"));
    }

    private TrainingDay day(int day) {
        return new TrainingDay(day, "协调", List.of(
                exercise(WARM_UP, 5), exercise(MAIN, 10), exercise(COOL_DOWN, 3)), "复测");
    }

    private Exercise exercise(TrainingPlanModels.ExerciseCategory category, int minutes) {
        return new Exercise(category, category.name(), minutes, 2,
                List.of("保持轻声"), List.of("疼痛时停止"));
    }

    private Citation citation() {
        return new Citation("doc-1", Citation.CitationType.INTERNAL_KNOWLEDGE,
                "换声区", "/api/ai/knowledge/documents/doc-1", "摘要", 0.9);
    }

    private static final class RecordingModel implements ChatModel {
        private final String response;
        private String lastPrompt;

        private RecordingModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            lastPrompt = prompt.getInstructions().stream().map(message -> message.getText())
                    .reduce("", (left, right) -> left + "\n" + right);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        }
    }
}
