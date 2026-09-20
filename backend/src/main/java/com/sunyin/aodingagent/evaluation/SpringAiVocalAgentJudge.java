package com.sunyin.aodingagent.evaluation;

import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentEvalInput;
import com.sunyin.aodingagent.evaluation.VocalAgentEvaluationModels.AgentJudgeVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * 用大模型实现 {@link VocalAgentJudge}。
 * <p>
 * 通过 Spring AI 的 ChatClient 结构化输出，让 LLM 按 rubric 对一条 agent 回答打分，
 * 返回 {@link AgentJudgeVerdict}。用于对声乐 agent 的生成结果做端到端质量评测。
 *
 * <p><b>边界</b>：判官打分是<b>近似且可能不稳</b>的启发式评估，不是金标准；建议对 LLM 判官
 * 的少数样本做人工抽检核对。若模型调用失败，返回默认的中性判定（各项 0.5、空引用），
 * 避免一条坏样本拖垮整批指标。
 */
@Component
public final class SpringAiVocalAgentJudge implements VocalAgentJudge {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiVocalAgentJudge.class);

    private static final String SYSTEM_PROMPT = """
            你是流行演唱 agent 的回答质量判官。给定"用户问题、agent 可用的检索上下文、agent 的回答"，
            输出三个 0..1 的分数和一个引用列表，全部字段必须给出：
            - faithfulness 忠实度：回答是否被给定的检索上下文所支撑、有没有编造上下文之外的事实。
              完全被支撑=1；大量编造=0；部分=0.5 左右。
            - answerRelevance 切题度：回答是否真正回答了用户的问题，而非答非所问。完全切题=1；离题=0。
            - refusalCorrectness 拒答正确度：当检索上下文不足以回答、而 agent 明确说明"没有足够资料"
              而非硬编，则=1；当上下文充足、agent 正常作答，则=1；若该作答却拒答、或该拒答却硬编，则=0。
            - usedDocIds：回答实际引用/依赖的上下文条目 id（只列真正被用到、支撑了回答的那些）。
            只依据给定内容判断，不要自行补充领域知识。
            """;

    private final ChatClient client;

    public SpringAiVocalAgentJudge(ChatModel chatModel) {
        this.client = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    @Override
    public AgentJudgeVerdict judge(AgentEvalInput input) {
        String userContent = buildUserContent(input);
        try {
            return client.prompt().user(userContent).call().entity(AgentJudgeVerdict.class);
        } catch (RuntimeException exception) {
            logger.warn("LLM 判官调用失败，返回中性判定: reason={}", exception.getMessage());
            return new AgentJudgeVerdict(0.5, 0.5, 0.5, java.util.List.of());
        }
    }

    private String buildUserContent(AgentEvalInput input) {
        StringBuilder content = new StringBuilder();
        content.append("用户问题：").append(input.question()).append("\n\n");
        content.append("agent 可用检索上下文：\n");
        for (VocalAgentEvaluationModels.ContextEntry entry : input.context()) {
            content.append("- [").append(entry.id()).append("] ").append(entry.text()).append("\n");
        }
        content.append("\nagent 回答：").append(input.answer()).append("\n");
        if (input.expectedRefusal() != null) {
            content.append("期望拒答（供参考）：").append(input.expectedRefusal() ? "是" : "否").append("\n");
        }
        return content.toString();
    }
}
