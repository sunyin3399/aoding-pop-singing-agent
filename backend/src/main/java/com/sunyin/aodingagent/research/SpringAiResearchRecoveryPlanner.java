package com.sunyin.aodingagent.research;

import com.sunyin.aodingagent.advisor.MyLoggerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 使用大模型为研究缺口生成最后一组补充搜索词。
 * <p>
 * 它只在固定规则补救仍不达标时调用，而且最多返回 3 条查询。模型调用失败时返回空计划，
 * 研究工作流随后会根据已有证据给出降级或失败结果，不会因为恢复失败而无限重试。
 */
@Component
public final class SpringAiResearchRecoveryPlanner implements ResearchRecoveryPlanner {

    private static final String SYSTEM_PROMPT = """
            你是研究恢复规划器。根据尚未满足的数量或分类约束，只生成补充搜索词。
            不回答用户问题，不编造来源，不重复已有搜索词，最多返回3个查询。
            """;

    private final ChatClient chatClient;

    public SpringAiResearchRecoveryPlanner(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
    }

    /** 根据未满足项和已用搜索词生成不重复的补充查询。 */
    @Override
    public ResearchModels.RecoveryPlan recover(ResearchModels.ResearchTaskContract contract,
                                                ResearchSession session,
                                                List<ResearchModels.ValidationIssue> issues) {
        try {
            ResearchModels.RecoveryPlan plan = chatClient.prompt()
                    .user("任务契约：" + contract + "\n未满足约束：" + issues
                            + "\n已搜索：" + session.searchQueries())
                    .call()
                    .entity(ResearchModels.RecoveryPlan.class);
            if (plan == null) return new ResearchModels.RecoveryPlan(List.of());
            List<ResearchModels.RecoveryQuery> queries = plan.queries().stream()
                    .filter(query -> query != null && query.query() != null && !query.query().isBlank())
                    .limit(3)
                    .toList();
            return new ResearchModels.RecoveryPlan(queries);
        } catch (RuntimeException exception) {
            return new ResearchModels.RecoveryPlan(List.of());
        }
    }
}
