package com.sunyin.aodingagent.trainingplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.conversation.ConversationModels.ConversationMode;
import com.sunyin.aodingagent.conversation.ConversationTurnCoordinator;
import com.sunyin.aodingagent.conversation.ConversationNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanRequest;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanResult;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.SUCCESS;

@RestController
@RequestMapping("/ai/training-plans")
public class TrainingPlanController {

    private static final Logger logger = LoggerFactory.getLogger(TrainingPlanController.class);

    private final TrainingPlanWorkflow workflow;
    private final ConversationTurnCoordinator conversations;
    private final ObjectMapper objectMapper;

    @Autowired
    public TrainingPlanController(TrainingPlanWorkflow workflow, ConversationTurnCoordinator conversations,
                                  ObjectMapper objectMapper) {
        this.workflow = workflow;
        this.conversations = conversations;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public TrainingPlanResult create(@RequestBody TrainingPlanRequest request) {
        return workflow.create(request);
    }

    TrainingPlanController(TrainingPlanWorkflow workflow) {
        this(workflow, null, null);
    }

    @PostMapping("/conversation")
    public ConversationTrainingPlanResult createInConversation(@RequestBody ConversationTrainingPlanRequest command) {
        TrainingPlanResult result = workflow.create(command.request());
        if (result.status() != SUCCESS) return new ConversationTrainingPlanResult(command.conversationId(), result);
        try {
        var turn = conversations.begin(command.userId(), command.mode(), command.conversationId(), command.userMessage());
        conversations.completeWithArtifact(turn, command.userId(), command.mode(), command.userMessage(),
                "训练计划已生成，可展开查看详细安排。", java.util.List.of(), "TRAINING_PLAN",
                objectMapper.valueToTree(result), summary(result));
        return new ConversationTrainingPlanResult(turn.conversationId(), result);
        } catch (ConversationNotFoundException exception) {
            logger.warn("训练计划已生成，但会话保存失败，会话可能已过期: conversationId={}", command.conversationId());
            return new ConversationTrainingPlanResult(command.conversationId(), result);
        }
    }

    private String summary(TrainingPlanResult result) {
        var plan = result.plan();
        return "训练计划：目标=" + plan.goal() + "；周期=" + plan.durationDays() + "天；每天="
                + plan.minutesPerDay() + "分钟；阶段=" + plan.phases().stream()
                .map(phase -> phase.name() + "(" + phase.goal() + ")").toList()
                + "；安全提示=" + plan.safetyNotices();
    }

    public record ConversationTrainingPlanRequest(String userId, ConversationMode mode, String conversationId,
                                                   String userMessage, TrainingPlanRequest request) { }
    public record ConversationTrainingPlanResult(String conversationId, TrainingPlanResult result) { }
}
