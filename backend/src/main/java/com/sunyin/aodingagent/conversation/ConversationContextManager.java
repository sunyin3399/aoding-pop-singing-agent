package com.sunyin.aodingagent.conversation;

import com.sunyin.aodingagent.conversation.ConversationModels.*;
import com.sunyin.aodingagent.research.AgentReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 从完整Redis原文构建受Token预算约束的模型上下文。
 * <p>
 * 它优先使用旧摘要和最近完整轮次，达到阈值时增量压缩；任何压缩异常都会退回最近窗口，
 * 因而记忆增强不能成为聊天可用性的单点故障。
 */
@Service
public final class ConversationContextManager {

    private static final Logger logger = LoggerFactory.getLogger(ConversationContextManager.class);
    private final RedisConversationStore store;
    private final ConversationProperties properties;
    private final ConversationTokenEstimator estimator;
    private final ConversationCompactor compactor;
    private final UserProfileService profileService;

    public ConversationContextManager(RedisConversationStore store, ConversationProperties properties,
                                      ConversationTokenEstimator estimator, ConversationCompactor compactor,
                                      UserProfileService profileService) {
        this.store = store;
        this.properties = properties;
        this.estimator = estimator;
        this.compactor = compactor;
        this.profileService = profileService;
    }

    public PreparedConversationContext prepare(String userId, ConversationMode mode, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return new PreparedConversationContext(profileMessages(userId), 0, false);
        }
        ConversationDocument document = store.getDocument(userId, mode, conversationId);
        ConversationContextSnapshot snapshot = store.getSnapshot(userId, mode, conversationId);
        StructuredConversationSummary summary = snapshot == null
                ? StructuredConversationSummary.empty() : snapshot.summary();
        long covered = snapshot == null ? 0 : snapshot.coveredThroughSequence();
        List<ConversationMessage> uncovered = document.messages().stream()
                .filter(message -> message.sequence() > covered).toList();
        int estimated = estimateMessages(uncovered) + estimator.estimate(summaryText(summary));
        boolean compacted = false;

        List<ConversationMessage> recentTarget = lastMessages(uncovered,
                properties.getContext().getRecentRawTurns() * 2);
        List<ConversationMessage> compressible = uncovered.subList(0, uncovered.size() - recentTarget.size());
        if (estimated >= properties.getContext().getCompactTriggerTokens()
                && estimateMessages(compressible) >= properties.getContext().getSummaryTargetTokens()) {
            try {
                ContextCompactionResult result = compactor.compact(summary, projectArtifacts(compressible),
                        properties.getContext().getSummaryTargetTokens());
                summary = sanitize(result.summary());
                long coveredThrough = compressible.getLast().sequence();
                store.saveSnapshot(userId, mode, new ConversationContextSnapshot(
                        conversationId, coveredThrough, summary, Instant.now(), snapshot == null ? 1 : snapshot.version() + 1));
                profileService.merge(userId, conversationId, compressible, result.profileCandidates());
                uncovered = document.messages().stream().filter(message -> message.sequence() > coveredThrough).toList();
                compacted = true;
                logger.info("会话上下文压缩完成: conversationId={}, beforeTokens={}, coveredMessages={}",
                        conversationId, estimated, compressible.size());
            } catch (RuntimeException exception) {
                logger.warn("会话上下文压缩失败，回退最近窗口: conversationId={}", conversationId, exception);
            }
        }

        List<Message> context = new ArrayList<>(profileMessages(userId));
        if (!blankSummary(summary)) {
            // 摘要对象包含多个列表字段，不能只限制conversationSummary字段；注入模型前再次按整体预算截断。
            String summaryForModel = truncateToTokenBudget(summaryText(summary),
                    properties.getContext().getSummaryTargetTokens());
            context.add(new SystemMessage("较早会话的结构化摘要：\n" + summaryForModel));
        }
        List<ConversationMessage> selected = selectRecent(uncovered);
        for (ConversationMessage message : selected) {
            String content = contextContent(message);
            if (message.role() == MessageRole.USER) context.add(new UserMessage(content));
            else context.add(new AssistantMessage(content));
        }
        appendRecentReferences(context, selected);
        return new PreparedConversationContext(context, estimateAiMessages(context), compacted);
    }

    private List<Message> profileMessages(String userId) {
        UserProfile profile = profileService.get(userId);
        if (profile.facts().isEmpty()) return new ArrayList<>();
        // 总预算中先为摘要、最近三轮原文和引用留出固定空间，剩余部分才允许画像使用。
        int profileBudget = Math.max(0, properties.getContext().getMaxHistoryTokens()
                - properties.getContext().getSummaryTargetTokens()
                - properties.getContext().getRecentRawTokenBudget()
                - properties.getContext().getReferenceTokenBudget());
        if (profileBudget == 0) return new ArrayList<>();
        String profileText = truncateToTokenBudget(profile.facts().toString(), profileBudget);
        return new ArrayList<>(List.of(new SystemMessage(
                "用户明确表达并保存的画像（若与当前消息冲突，以当前消息为准）：\n" + profileText)));
    }

    private List<ConversationMessage> selectRecent(List<ConversationMessage> messages) {
        List<ConversationMessage> selectedReverse = new ArrayList<>();
        int tokens = 0;
        int limit = Math.min(properties.getContext().getMaxHistoryMessages(),
                properties.getContext().getRecentRawTurns() * 2);
        for (int index = messages.size() - 1; index >= 0 && selectedReverse.size() < limit; index--) {
            ConversationMessage message = messages.get(index);
            int itemTokens = estimator.estimate(contextContent(message));
            if (!selectedReverse.isEmpty() && tokens + itemTokens > properties.getContext().getRecentRawTokenBudget()) break;
            if (selectedReverse.isEmpty() && itemTokens > properties.getContext().getRecentRawTokenBudget()) {
                message = new ConversationMessage(message.messageId(), message.role(),
                        truncateToTokenBudget(contextContent(message), properties.getContext().getRecentRawTokenBudget()),
                        message.references(), message.createdAt(), message.sequence(), message.artifactType(),
                        message.artifactPayload(), message.artifactSummary());
                itemTokens = estimator.estimate(message.content());
            }
            selectedReverse.add(message);
            tokens += itemTokens;
        }
        Collections.reverse(selectedReverse);
        // 避免以孤立Assistant消息开头；完整轮次优先于多保留一条消息。
        if (!selectedReverse.isEmpty() && selectedReverse.getFirst().role() == MessageRole.ASSISTANT) {
            selectedReverse.removeFirst();
        }
        return List.copyOf(selectedReverse);
    }

    private void appendRecentReferences(List<Message> context, List<ConversationMessage> selected) {
        ConversationMessage latestAssistant = null;
        for (int index = selected.size() - 1; index >= 0; index--) {
            if (selected.get(index).role() == MessageRole.ASSISTANT) {
                latestAssistant = selected.get(index);
                break;
            }
        }
        if (latestAssistant == null || latestAssistant.references().isEmpty()) return;
        StringBuilder value = new StringBuilder("最近一轮回答使用的参考资料，仅用于理解用户对链接的追问：\n");
        int budget = properties.getContext().getReferenceTokenBudget();
        for (AgentReference reference : latestAssistant.references()) {
            String line = "- " + reference.title() + " | " + reference.url() + "\n";
            if (estimator.estimate(value + line) > budget) break;
            value.append(line);
        }
        if (value.indexOf("- ") >= 0) context.add(new SystemMessage(value.toString()));
    }

    private StructuredConversationSummary sanitize(StructuredConversationSummary value) {
        if (value == null) return StructuredConversationSummary.empty();
        return new StructuredConversationSummary(limitList(value.goals()), limitList(value.confirmedFacts()),
                limitList(value.decisions()), limitList(value.constraints()), limitList(value.openQuestions()),
                truncateToTokenBudget(value.conversationSummary(), properties.getContext().getSummaryTargetTokens()));
    }

    private List<String> limitList(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.length() <= 240 ? value : value.substring(0, 240)).limit(12).toList();
    }

    private String truncateToTokenBudget(String text, int tokenBudget) {
        if (text == null) return "";
        if (estimator.estimate(text) <= tokenBudget) return text;
        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (estimator.estimate(text.substring(0, middle)) <= tokenBudget) low = middle;
            else high = middle - 1;
        }
        return text.substring(0, low) + "\n[较早历史内容已按Token预算截断]";
    }

    private int estimateMessages(List<ConversationMessage> messages) {
        return messages.stream().mapToInt(message -> estimator.estimate(contextContent(message))).sum();
    }

    private int estimateAiMessages(List<Message> messages) {
        return messages.stream().mapToInt(message -> estimator.estimate(message.getText())).sum();
    }

    private List<ConversationMessage> lastMessages(List<ConversationMessage> messages, int count) {
        return messages.subList(Math.max(0, messages.size() - count), messages.size());
    }

    private List<ConversationMessage> projectArtifacts(List<ConversationMessage> messages) {
        return messages.stream().map(message -> {
            if (message.artifactSummary() == null || message.artifactSummary().isBlank()) return message;
            return new ConversationMessage(message.messageId(), message.role(), message.artifactSummary(),
                    message.references(), message.createdAt(), message.sequence());
        }).toList();
    }

    private String contextContent(ConversationMessage message) {
        return message.artifactSummary() == null || message.artifactSummary().isBlank()
                ? message.content() : message.artifactSummary();
    }

    private boolean blankSummary(StructuredConversationSummary summary) {
        return summary.conversationSummary().isBlank() && summary.goals().isEmpty()
                && summary.confirmedFacts().isEmpty() && summary.decisions().isEmpty()
                && summary.constraints().isEmpty() && summary.openQuestions().isEmpty();
    }

    private String summaryText(StructuredConversationSummary summary) {
        // 先放自然语言总览，Token紧张时仍能保住最重要的连续语义；随后再补结构化字段。
        return "总览：" + summary.conversationSummary()
                + "\n目标：" + summary.goals()
                + "\n已确认事实：" + summary.confirmedFacts()
                + "\n已作决定：" + summary.decisions()
                + "\n约束：" + summary.constraints()
                + "\n待解决问题：" + summary.openQuestions();
    }
}
