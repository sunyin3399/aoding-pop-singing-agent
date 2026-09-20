package com.sunyin.aodingagent.research;

import java.util.List;

/**
 * 集中保存外部研究功能使用的合同、预算、来源和结果数据。
 * <p>
 * 这些结构把原本模糊的“帮我研究一下”拆成程序可以理解和检查的数据，例如要交付什么、
 * 至少需要多少条答案、至少需要多少个证据网页，以及搜索失败后还允许补救几次。
 */
public final class ResearchModels {

    private ResearchModels() {
    }

    /** 用户研究请求的大类，用来描述任务目的。 */
    public enum ResearchTaskType { RESOURCE_RESEARCH, RECOMMENDATION, COMPARISON, GENERAL_RESEARCH }

    /** Agent 最终应该交付给用户的内容类型，而不是搜索到的网页类型。 */
    public enum DeliverableType {
        /** 网站、工具、课程等可直接使用的资源。 */
        RESOURCE_LIST,
        /** 具体歌曲推荐；网页只是支撑推荐的证据。 */
        SONG_RECOMMENDATION,
        /** 带可点击地址的教程或跟练内容。 */
        TUTORIAL_LIST,
        /** 包含时长、次数、常见错误和安全边界的练习步骤。 */
        EXERCISE_GUIDE,
        /** 按固定维度比较多个对象。 */
        COMPARISON_TABLE,
        /** 围绕一个问题给出结论、证据和限制。 */
        RESEARCH_SUMMARY
    }

    /** 研究终态：SUCCESS 表示证据达标，DEGRADED 表示有证据但未完全达标，FAILED 表示没有可信证据。 */
    public enum ResearchStatus { SUCCESS, DEGRADED, FAILED }

    /** 单个网页当前的抓取状态。 */
    public enum ScrapeStatus { NOT_ATTEMPTED, SCRAPED, FAILED }

    /** 单个来源与用户问题的相关性判断。 */
    public enum SourceQualityStatus { PENDING, RELEVANT, IRRELEVANT }

    /** 网页抓取失败的分类，用来决定是否重试或阻断整个域名。 */
    public enum ScrapeErrorCode {
        TIMEOUT, HTTP_FORBIDDEN, HTTP_NOT_FOUND, EMPTY_CONTENT,
        UNSUPPORTED_DYNAMIC_PAGE, CHALLENGE_PAGE, INVALID_URL, UNKNOWN
    }

    /** 研究必须覆盖的一个证据分类及其最低来源数。 */
    public record ResearchCategory(String name, int minimumItems) {
        public ResearchCategory {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("分类名称不能为空");
            if (minimumItems < 0) throw new IllegalArgumentException("分类目标不能为负数");
        }
    }

    /**
     * 研究开始前确定的目标和硬限制。
     * <p>
     * {@code minimumItems} 指最终答案至少包含多少个条目，{@code minimumSources} 指至少需要
     * 多少个成功抓取的证据网页。歌曲推荐可能需要 10 首歌曲，但不需要为每首歌准备一个网页。
     */
    public record ResearchTaskContract(
            ResearchTaskType taskType,
            String topic,
            DeliverableType deliverable,
            int minimumItems,
            int minimumSources,
            List<ResearchCategory> categories,
            List<String> requiredFields,
            boolean safetySectionRequired,
            int maxSearchCalls,
            int maxScrapeCalls
    ) {
        public ResearchTaskContract {
            categories = categories == null ? List.of() : List.copyOf(categories);
            requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
            if (minimumItems < 1) throw new IllegalArgumentException("最少结果数必须为正数");
            if (minimumSources < 1) {
                minimumSources = deliverable == DeliverableType.SONG_RECOMMENDATION ? 2 : Math.min(3, minimumItems);
            }
        }
    }

    /** 系统级资源上限，防止搜索、抓取和恢复过程无限执行。 */
    public record ResearchBudget(
            int maxSearchCalls,
            int maxScrapeCalls,
            int maxScrapeAttemptsPerUrl,
            int maxDeterministicRemediations,
            int maxRecoveryPlannerCalls,
            int timeoutSeconds
    ) {
        public static ResearchBudget defaults() {
            return new ResearchBudget(6, 12, 2, 1, 1, 90);
        }
    }

    /**
     * 来源台账中的一个网页，保存它从“搜索结果”到“抓取成功或失败”的完整过程。
     */
    public record SourceCandidate(
            String id,
            String title,
            String url,
            String normalizedUrl,
            String category,
            String searchQuery,
            String snippet,
            ScrapeStatus scrapeStatus,
            int scrapeAttempts,
            ScrapeErrorCode scrapeErrorCode,
            String extractedContent,
            SourceQualityStatus qualityStatus
    ) {
    }

    /** 描述研究合同中一个未达到的目标，包括期望值和实际值。 */
    public record ValidationIssue(String code, String field, String message, int expected, int actual) {
    }

    /** 一条补救搜索词，以及它准备补充的证据分类。 */
    public record RecoveryQuery(String category, String query) {
    }

    /** 最后一次智能补救建议执行的查询列表。 */
    public record RecoveryPlan(List<RecoveryQuery> queries) {
        public RecoveryPlan {
            queries = queries == null ? List.of() : List.copyOf(queries);
        }
    }

    /** 研究工作流内部使用的完整结果，其中来源可能包含抓取后的网页正文。 */
    public record ResearchResult(
            ResearchStatus status,
            ResearchTaskContract contract,
            List<SourceCandidate> sources,
            List<ValidationIssue> validationIssues,
            int searchCalls,
            int scrapeCalls,
            int recoveryRounds
    ) {
        public ResearchResult {
            sources = sources == null ? List.of() : List.copyOf(sources);
            validationIssues = validationIssues == null ? List.of() : List.copyOf(validationIssues);
        }
    }

    /** Compact result exposed to the agent. Full scraped pages stay inside the workflow. */
    public record AgentResearchResult(
            ResearchStatus status,
            ResearchTaskContract contract,
            String answerGuidance,
            List<AgentResearchSource> sources,
            List<ValidationIssue> validationIssues,
            int searchCalls,
            int scrapeCalls,
            int recoveryRounds,
            CandidateKnowledgeOutcome candidateKnowledge
    ) {
        public AgentResearchResult {
            sources = sources == null ? List.of() : List.copyOf(sources);
            validationIssues = validationIssues == null ? List.of() : List.copyOf(validationIssues);
        }
    }

    public enum CandidateKnowledgeStatus {
        NOT_ELIGIBLE, PENDING_REVIEW, ALREADY_APPROVED, ALREADY_REJECTED, GENERATION_FAILED, SAVE_FAILED
    }

    public record CandidateKnowledgeOutcome(
            CandidateKnowledgeStatus status,
            String candidateId,
            String title,
            String message
    ) {
        public static CandidateKnowledgeOutcome notEligible(String message) {
            return new CandidateKnowledgeOutcome(CandidateKnowledgeStatus.NOT_ELIGIBLE, null, null, message);
        }
    }

    /** 返回给 Agent 的精简来源，只保留回答和展示需要的信息，不包含完整网页正文。 */
    public record AgentResearchSource(
            String id,
            String title,
            String url,
            String category,
            String snippet,
            ScrapeStatus scrapeStatus,
            SourceQualityStatus qualityStatus
    ) {
    }

    public static String answerGuidance(DeliverableType deliverable) {
        return switch (deliverable) {
            case SONG_RECOMMENDATION -> "从证据来源正文中提取具体歌曲并去重，最终至少给出合同 minimumItems 指定数量的推荐歌曲。证据不足时，可以用稳定的通用声乐知识补足歌曲候选，但必须明确标注“通用建议、未逐项联网核验”，不得为补充项虚构来源或链接。同一推荐区块使用一个连续有序列表，并将每首歌的说明缩进到对应条目下。先按难度或练习阶段推荐歌曲，说明适合原因和练习重点；再列出不建议作为练习曲的歌曲及具体原因；最后给出简短总结。内联教学链接的 URL 必须与返回的 SCRAPED 来源 URL 完全一致，可见标题必须原样使用该来源的 title，不得自行概括、翻译或改写标题；其他 URL 只作为参考资料引用。主动识别分句教学、教师示范、降调伴奏或难点解析等直接帮助练习的资源，可附在对应歌曲下；MV、泛歌曲页和普通试听链接不作为练习资源。不要把网页标题当作歌曲。";
            case TUTORIAL_LIST -> "教程本身就是交付条目。回答教学重点、适合人群和建议，并提供可点击链接；优先分步教学、教师示范和可跟练内容，弱化 MV、聚合页和纯宣传页。";
            case EXERCISE_GUIDE -> "综合多个证据来源形成可执行练习步骤，包含时长或次数、常见错误和安全停止条件；来源链接统一作为证据，不逐段重复。";
            case RESOURCE_LIST -> "资源本身就是交付条目。根据用户目标选择自然字段，不要机械重复“目的/建议”；链接应能直接支持学习或操作。";
            case COMPARISON_TABLE -> "按用户关心的维度比较对象，区分事实、推断和未知信息，并将来源作为证据引用。";
            case RESEARCH_SUMMARY -> "围绕用户问题先给结论，再给关键证据、适用边界和不确定性；不要把来源列表当作结论。";
        };
    }
}
