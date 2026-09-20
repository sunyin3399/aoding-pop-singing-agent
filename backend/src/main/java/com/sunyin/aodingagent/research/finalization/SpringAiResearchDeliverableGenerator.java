package com.sunyin.aodingagent.research.finalization;

import com.sunyin.aodingagent.advisor.MyLoggerAdvisor;
import com.sunyin.aodingagent.research.AgentReference;
import com.sunyin.aodingagent.research.ResearchModels;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;

/**
 * 使用 Spring AI 把 Agent 草稿整理为 {@link ResearchDeliverable}。
 * <p>
 * 模型只允许引用 EvidenceBundle 中已经出现的 ID；真正的白名单检查仍由 Java Validator 完成，
 * 因此 Prompt 写得再严格也不会成为唯一安全边界。
 */
@Component
public final class SpringAiResearchDeliverableGenerator implements ResearchDeliverableGenerator {

    static final int MAX_EVIDENCE_SUMMARY_CHARS = 500;

    private static final String SYSTEM_PROMPT = """
            你是 Agent 最终交付物整理器。根据用户原始请求、Agent 草稿、任务合同和已核验证据，
            输出 ResearchDeliverable 结构，不要回答结构以外的内容。
            保留用户真正需要的信息，不要把所有场景固定成“目的/使用建议”。
            SONG_RECOMMENDATION 的条目应填写 name、recommendationReason、practiceFocus、suitability；
            TUTORIAL_LIST 和 RESOURCE_LIST 应填写 name、description、suitability，并引用真实 referenceIds；
            EXERCISE_GUIDE 可优先使用 sections，包含时长或次数、常见错误和安全停止条件。
            Agent 草稿通常已覆盖大部分答案。请尽量忠实保留草稿已有的内容、结构与措辞，只把它整理进
            ResearchDeliverable 结构并补齐缺失的必需字段；不要改写、精简或重写草稿已覆盖的部分，
            不要添加草稿与证据之外的新内容。
            只能使用证据列表中存在的引用 ID，不得生成 URL，不得把网页标题直接当作歌曲名。
            外部研究证据不足时允许给出无引用的稳定通用建议，但必须在内容中说明未逐项联网核验。
            """;

    private final ChatClient chatClient;

    public SpringAiResearchDeliverableGenerator(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
    }

    @Override
    public ResearchDeliverable generate(String request, String draftAnswer, EvidenceBundle evidence) {
        return chatClient.prompt().user(prompt(request, draftAnswer, evidence, null, List.of()))
                .call().entity(ResearchDeliverable.class);
    }

    @Override
    public ResearchDeliverable revise(String request, String draftAnswer, EvidenceBundle evidence,
                                       ResearchDeliverable invalid,
                                       List<ResearchModels.ValidationIssue> issues) {
        return chatClient.prompt().user(prompt(request, draftAnswer, evidence, invalid, issues))
                .call().entity(ResearchDeliverable.class);
    }

    static String prompt(String request, String draftAnswer, EvidenceBundle evidence,
                         ResearchDeliverable invalid,
                         List<ResearchModels.ValidationIssue> issues) {
        return "用户原始请求：\n" + request
                + "\n\n研究合同：\n" + evidence.contract()
                + "\n\n外部研究状态：\n" + evidence.researchStatus()
                + "\n\n允许使用的已核验证据摘要：\n" + compactEvidence(evidence.references())
                + "\n\nAgent 草稿：\n" + draftAnswer
                + (invalid == null ? "" : "\n\n未通过验收的结构化结果：\n" + invalid)
                + (issues.isEmpty() ? "" : "\n\n必须修复的验收问题：\n" + issues);
    }

    private static String compactEvidence(List<AgentReference> references) {
        LinkedHashMap<String, AgentReference> verified = new LinkedHashMap<>();
        for (AgentReference reference : references) {
            if (reference == null || reference.id() == null || reference.id().isBlank()) continue;
            if (reference.status() != AgentReference.ReferenceStatus.SCRAPED
                    && reference.status() != AgentReference.ReferenceStatus.INTERNAL_APPROVED) continue;
            verified.putIfAbsent(reference.id(), reference);
        }
        return verified.values().stream().map(reference -> "id=" + reference.id()
                        + ", title=" + reference.title()
                        + ", url=" + reference.url()
                        + ", summary=" + compact(reference.excerpt()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("无");
    }

    private static String compact(String value) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= MAX_EVIDENCE_SUMMARY_CHARS
                ? compact : compact.substring(0, MAX_EVIDENCE_SUMMARY_CHARS) + "…";
    }
}
