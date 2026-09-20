package com.sunyin.aodingagent.research;

import com.sunyin.aodingagent.advisor.MyLoggerAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * 使用 Spring AI 调用大模型，把用户问题转换成 {@link ResearchModels.ResearchTaskContract}。
 * <p>
 * 大模型在这里只负责理解“用户真正想得到什么”，例如想要歌曲、教程还是练习步骤，不负责
 * 搜索资料或回答问题。生成的合同还会经过 {@link ResearchTaskPlanner} 的普通 Java 规则保护，
 * 因此模型解析失败或降低用户明确数量时，系统仍能得到一份安全的默认合同。
 */
@Component
public final class SpringAiResearchTaskPlanner implements ResearchTaskPlanner {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiResearchTaskPlanner.class);

    private static final String SYSTEM_PROMPT = """
            你是流行演唱研究任务规划器。只把用户请求转换为结构化任务契约，不回答问题。
            先识别用户真正想得到的交付物，而不是把所有请求都规划成网页资源清单：
            - 想知道“哪些歌曲适合”时使用 SONG_RECOMMENDATION；最终条目是歌曲，不是网页，URL 不是歌曲必填字段。
            - 想找教程、课程、跟练视频时使用 TUTORIAL_LIST；最终条目是可直接学习的资源，URL 必填。
            - 想知道具体怎么练时使用 EXERCISE_GUIDE；最终交付物是含时长/次数和安全边界的练习步骤。
            - 只有用户确实需要网站、工具、课程等资源时才使用 RESOURCE_LIST。
            - 对比请求使用 COMPARISON_TABLE，其余开放研究使用 RESEARCH_SUMMARY。
            minimumItems 表示最终交付条目数量；minimumSources 表示最低证据来源数量，两者不得混淆。
            用户明确提出的最少条目数量不得降低。歌曲推荐应同时研究推荐歌曲、暂缓歌曲及难点、可直接帮助练习的教学辅助。
            歌曲字段使用 songName、artist、recommendationLevel、suitabilityReason、practiceFocus、difficulty、cautions、tutorialResourceIds、sourceIds。
            教学辅助只接受分句教学、教师示范、降调伴奏、跟练或难点解析；普通 MV、试听页不算教学辅助。
            教程字段使用 name、url、teachingFocus、suitableFor、advice。对用户展示统一使用“建议”，不要输出“使用建议”。不要无差别要求 purpose/advice。
            搜索最多6次，抓取最多12次；涉及发声训练或嗓音健康时必须包含安全提醒。
            """;

    private final ResearchTaskPlanner delegate;

    public SpringAiResearchTaskPlanner(ChatModel chatModel) {
        ChatClient client = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.delegate = ResearchTaskPlanner.preferLightweight(
                ResearchTaskPlanner.withGenerator(request -> client.prompt()
                        .user(request)
                        .call()
                        .entity(ResearchModels.ResearchTaskContract.class)));
    }

    /**
     * 规划研究目标并记录最终采用的合同，便于排查后续搜索为什么按某些分类执行。
     */
    @Override
    public ResearchModels.ResearchTaskContract plan(String request) {
        ResearchModels.ResearchTaskContract contract = delegate.plan(request);
        logger.info("研究任务契约: {}", contract);
        return contract;
    }
}
