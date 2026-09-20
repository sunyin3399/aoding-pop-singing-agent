package com.sunyin.aodingagent.knowledge.candidate;

import com.sunyin.aodingagent.advisor.MyLoggerAdvisor;
import com.sunyin.aodingagent.research.ResearchModels;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class SpringAiCandidateKnowledgeGenerator implements CandidateKnowledgeGenerator {

    private static final int MAX_SOURCE_CHARS = 1800;
    private final ChatClient chatClient;

    public SpringAiCandidateKnowledgeGenerator(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
    }

    @Override
    public GeneratedCandidate generate(String originalRequest, ResearchModels.ResearchResult result) {
        String evidence = result.sources().stream()
                .filter(source -> source.scrapeStatus() == ResearchModels.ScrapeStatus.SCRAPED)
                .map(source -> "来源标题：" + source.title() + "\nURL：" + source.url()
                        + "\n正文摘要：" + compact(source.extractedContent()))
                .collect(Collectors.joining("\n\n"));
        return chatClient.prompt()
                .system("""
                        你负责判断外部研究是否值得沉淀为可复用的流行声乐候选知识，并在值得时生成文档。
                        单纯的歌曲推荐、歌单、临时资源清单、个人偏好、时效性搜索结果不值得沉淀，reusable=false。
                        只有能形成跨用户复用的训练原则、可执行练习、常见错误、适用边界和安全提醒时，reusable=true。
                        不得添加证据中没有的来源，不得把普通 MV 或试听页描述成教学证据。
                        reusable=true 时，markdownContent 必须严格包含：
                        # 标题
                        ## 原理
                        ## 训练动作（包含明确的分钟、秒、次或组）
                        ## 常见错误
                        ## 适用范围
                        ## 安全提醒
                        ## 资料来源（列出输入中的真实标题和 URL）
                        reusable=false 时只填写 reason，title 和 markdownContent 留空。
                        """)
                .user("原始问题：" + originalRequest + "\n交付类型：" + result.contract().deliverable()
                        + "\n\n已核验资料：\n" + evidence)
                .call()
                .entity(GeneratedCandidate.class);
    }

    private String compact(String value) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= MAX_SOURCE_CHARS ? compact : compact.substring(0, MAX_SOURCE_CHARS) + "…";
    }
}
