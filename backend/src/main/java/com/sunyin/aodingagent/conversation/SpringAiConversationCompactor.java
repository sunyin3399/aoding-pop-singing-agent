package com.sunyin.aodingagent.conversation;

import com.sunyin.aodingagent.advisor.MyLoggerAdvisor;
import com.sunyin.aodingagent.conversation.ConversationModels.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 使用一次结构化LLM调用完成增量摘要和画像候选提取。
 * Java校验器仍负责字段白名单、来源验证和合并，不能直接信任模型输出。
 */
@Component
public final class SpringAiConversationCompactor implements ConversationCompactor {

    private static final String SYSTEM_PROMPT = """
            你负责压缩声乐学习或流行演唱 Agent 的历史上下文。输出ContextCompactionResult结构。
            摘要保留目标、用户明确事实、已确认决定、约束和未解决问题，删除寒暄、重复表达和工具过程。
            profileCandidates只能来自role=USER的明确陈述，不能把Assistant推测、网页内容或工具结果当成画像。
            允许的画像key只有：singingLevel、preferredSingers、preferredStyles、comfortableRange、
            dailyPracticeMinutes、vocalProblems、trainingGoals、practicePreferences、safetyNotes。
            每个画像候选必须保留原sourceMessageId和简短evidence；不确定时不要输出候选。
            医疗信息只能描述为用户自述，不得诊断。摘要有损但不能改变原意。
            """;

    private final ChatClient chatClient;

    public SpringAiConversationCompactor(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
    }

    @Override
    public ContextCompactionResult compact(StructuredConversationSummary previous,
                                           List<ConversationMessage> messages,
                                           int targetTokens) {
        String prompt = "旧摘要：\n" + previous
                + "\n\n需要增量压缩的消息：\n" + messages
                + "\n\n摘要目标不超过约" + targetTokens + " Token。";
        return chatClient.prompt().user(prompt).call().entity(ContextCompactionResult.class);
    }
}
