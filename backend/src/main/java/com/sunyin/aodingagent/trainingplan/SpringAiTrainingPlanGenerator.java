package com.sunyin.aodingagent.trainingplan;

import com.sunyin.aodingagent.advisor.MyLoggerAdvisor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.knowledge.Citation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlan;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanRequest;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ValidationIssue;

@Component
public class SpringAiTrainingPlanGenerator implements TrainingPlanGenerator {

    private static final String SYSTEM_PROMPT = """
            OUTPUT DESIGN: Generate phase-level plans, not one detailed plan per day. Put exercises inside each phase and leave the top-level days array empty. Each phase covers a continuous day range and contains WARM_UP, MAIN and COOL_DOWN exercises. Repeating exercises within one phase is acceptable; make different phases meaningfully progressive when appropriate.
            你是流行演唱训练计划生成器。只返回符合请求结构的 JSON，不要输出 Markdown 或额外解释。
            每天必须包含 WARM_UP、MAIN、COOL_DOWN；总时长不得超过限制且至少达到限制的 60%。
            阶段必须从第 1 天连续覆盖最后一天，每项训练必须有操作说明和停止条件。
            每项训练至少给出 3 条具体、可执行的操作说明，覆盖准备姿势或起始动作、练习步骤与次数/时长、正确感觉或常见错误修正。相邻天数如果训练内容、时长、组数和停止条件完全相同，直接复用相同训练内容，不要为重复内容改写或增加新说明；但每个输出的训练项仍必须保留完整的 3 条 instructions。
            请先根据用户目标、当前水平、训练周期、每日时长和嗓音状态设计最合理、可执行的计划，再填入规定结构。尽量让不同阶段体现清晰的训练重点或递进变化：如果计划包含多个阶段，阶段之间应尽量在训练动作、发声重点、音高/音域、负荷、难度或应用方式上有明显区别；同一阶段内部允许根据巩固、恢复或建立基础的需要重复部分训练，不要为了形式上的差异强行修改每天内容。
            优先将参考资料中的具体方法、距离、次数、正确感觉和常见错误编入 instructions，不要只写“进行练习”之类空泛句子。
            citationIds 只能使用给定参考资料中的 id。不要进行医学诊断，出现疼痛或嘶哑应建议停止。
            vocalCondition 为 MILD_PAIN_OR_HOARSENESS 时降低发声强度、增加休息，并反复给出停止条件。
            vocalCondition 为 SIGNIFICANT_DISCOMFORT 时只生成最低强度恢复计划，避免高音、强声和长时间连续发声，优先休息、补水、轻柔呼吸和非强制发声内容，并提醒用户酌情就医。
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public SpringAiTrainingPlanGenerator(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public TrainingPlan generate(TrainingPlanRequest request, List<Citation> citations) {
        String context = "根据以下请求和参考资料生成计划。\n请求: " + json(request)
                + "\n参考资料: " + json(citations);
        return chatClient.prompt()
                .user(spec -> spec.text("{context}").param("context", context))
                .call()
                .entity(TrainingPlan.class);
    }

    @Override
    public TrainingPlan repair(
            TrainingPlanRequest request,
            TrainingPlan draft,
            List<ValidationIssue> issues,
            List<Citation> citations
    ) {
        String context = "只修复列出的问题，保留其他有效内容并返回完整 JSON。\n请求: " + json(request)
                + "\n初稿: " + json(draft)
                + "\n校验问题: " + json(issues)
                + "\n允许引用: " + json(citations);
        return chatClient.prompt()
                .user(spec -> spec.text("{context}").param("context", context))
                .call()
                .entity(TrainingPlan.class);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法序列化训练计划上下文", exception);
        }
    }
}
