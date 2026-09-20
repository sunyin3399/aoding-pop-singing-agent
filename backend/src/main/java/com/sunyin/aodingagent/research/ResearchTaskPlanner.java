package com.sunyin.aodingagent.research;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把用户的自然语言要求整理成一份可以由程序检查的研究合同。
 * <p>
 * 例如“推荐至少 8 首适合初学者的歌曲”不能只理解成搜索 8 个网页：最终交付物是歌曲，
 * 网页只是证据。Planner 会明确交付物类型、最低条目数、最低证据来源数、需要覆盖的分类和
 * 最大调用次数。它只是一次结构化规划调用，不是拥有独立目标和记忆的另一个 Agent。
 */
@FunctionalInterface
public interface ResearchTaskPlanner {

    Pattern EXPLICIT_MINIMUM = Pattern.compile("(?:至少|最少|不少于)(?:整理|提供|给出|列出)?\\s*(\\d+)\\s*(?:个|项|条)?");

    /**
     * 将用户请求转换为研究合同。
     *
     * @param request 用户完整原始请求，不能丢失明确数量和分类要求
     * @return 经过规范化的研究合同
     */
    ResearchModels.ResearchTaskContract plan(String request);

    /**
     * 为大模型合同生成器增加确定性保护：保留用户明确数量，并在模型失败时使用默认合同。
     */
    static ResearchTaskPlanner withGenerator(ContractGenerator generator) {
        return request -> {
            int explicitMinimum = explicitMinimum(request);
            try {
                ResearchModels.ResearchTaskContract generated = generator.generate(request);
                if (generated == null) throw new IllegalArgumentException("任务契约为空");
                int enforcedMinimum = Math.max(generated.minimumItems(), explicitMinimum);
                ResearchModels.ResearchTaskContract contract = new ResearchModels.ResearchTaskContract(
                        generated.taskType(), generated.topic(), generated.deliverable(), enforcedMinimum,
                        generated.minimumSources(),
                        generated.categories(), generated.requiredFields(), generated.safetySectionRequired(),
                        generated.maxSearchCalls(), generated.maxScrapeCalls());
                return normalizeContract(contract);
            } catch (RuntimeException exception) {
                // 规划失败不能让整个研究入口不可用，回退合同仍保留最少数量和调用预算。
                return fallbackContract(request, Math.max(5, explicitMinimum));
            }
        };
    }

    /** Obvious one-song and video requests do not need an LLM just to choose a contract. */
    static ResearchTaskPlanner preferLightweight(ResearchTaskPlanner fullPlanner) {
        return request -> lightweightContract(request).orElseGet(() -> fullPlanner.plan(request));
    }

    static Optional<ResearchModels.ResearchTaskContract> lightweightContract(String request) {
        String value = request == null ? "" : request;
        if (isSystematicTrainingRequest(value) || !isSingleSongRequest(value)) {
            return Optional.empty();
        }
        if (containsAny(value, "教学视频", "视频教学", "跟练视频", "教程视频")) {
            return Optional.of(new ResearchModels.ResearchTaskContract(
                    ResearchModels.ResearchTaskType.RESOURCE_RESEARCH, value,
                    ResearchModels.DeliverableType.TUTORIAL_LIST, 1, 1,
                    List.of(new ResearchModels.ResearchCategory("视频教学", 1)),
                    List.of("name", "url", "teachingFocus", "suitableFor", "advice"),
                    true, 2, 4));
        }
        if (containsAny(value, "怎么唱", "如何唱", "演唱技巧", "单曲技巧", "歌曲技巧", "唱法分析")) {
            return Optional.of(new ResearchModels.ResearchTaskContract(
                    ResearchModels.ResearchTaskType.GENERAL_RESEARCH, value,
                    ResearchModels.DeliverableType.EXERCISE_GUIDE, 1, 2,
                    List.of(new ResearchModels.ResearchCategory("演唱技巧", 1)),
                    List.of("goal", "steps", "durationOrRepetitions", "commonMistakes", "safetyNotes", "sourceIds"),
                    true, 2, 4));
        }
        return Optional.empty();
    }

    static boolean isSystematicTrainingRequest(String value) {
        if (value == null) return false;
        return containsAny(value, "系统训练", "系统性训练", "训练体系", "训练计划", "长期训练",
                "周期训练", "阶段训练", "周计划", "月计划", "完整课程")
                || value.matches("(?s).*(?:\\d+|一|两|三|四|五|六|七|八|九|十)\\s*(?:天|周|个月|月).*(?:训练|练习).*");
    }

    private static boolean isSingleSongRequest(String value) {
        return (value.contains("《") && value.contains("》"))
                || containsAny(value, "这首歌", "这首歌曲", "单曲");
    }

    static ResearchModels.ResearchTaskContract defaultResourceListContract(String topic, int minimumItems) {
        return new ResearchModels.ResearchTaskContract(
                ResearchModels.ResearchTaskType.RESOURCE_RESEARCH,
                topic,
                ResearchModels.DeliverableType.RESOURCE_LIST,
                Math.max(5, minimumItems),
                3,
                List.of(
                        new ResearchModels.ResearchCategory("系统学习", 1),
                        new ResearchModels.ResearchCategory("跟练资源", 1),
                        new ResearchModels.ResearchCategory("音准或听音工具", 1),
                        new ResearchModels.ResearchCategory("嗓音健康", 1)),
                List.of("name", "url", "purpose", "usageAdvice"),
                true,
                6,
                12);
    }

    static ResearchModels.ResearchTaskContract fallbackContract(String request, int minimumItems) {
        String normalized = request == null ? "" : request;
        if (containsAny(normalized, "歌曲", "歌单", "曲目", "选歌")) {
            return songRecommendationContract(normalized, minimumItems);
        }
        if (containsAny(normalized, "教程", "教学视频", "跟练视频", "课程")) {
            return tutorialListContract(normalized, minimumItems);
        }
        if (containsAny(normalized, "怎么练", "如何练", "练习方法", "训练方法")) {
            return exerciseGuideContract(normalized);
        }
        if (containsAny(normalized, "对比", "比较", "区别", "哪个好", "怎么选")) {
            return comparisonContract(normalized, minimumItems);
        }
        if (containsAny(normalized, "资源", "网站", "工具", "APP", "App", "软件", "资料", "清单")) {
            return defaultResourceListContract(normalized, minimumItems);
        }
        return researchSummaryContract(normalized);
    }

    static ResearchModels.ResearchTaskContract songRecommendationContract(String topic, int minimumItems) {
        return new ResearchModels.ResearchTaskContract(
                ResearchModels.ResearchTaskType.RECOMMENDATION, topic,
                ResearchModels.DeliverableType.SONG_RECOMMENDATION, Math.max(5, minimumItems), 2,
                List.of(
                        new ResearchModels.ResearchCategory("推荐歌曲", 1),
                        new ResearchModels.ResearchCategory("暂缓歌曲与难点", 1),
                        new ResearchModels.ResearchCategory("教学辅助", 0)),
                List.of("songName", "artist", "recommendationLevel", "suitabilityReason",
                        "practiceFocus", "difficulty", "cautions", "tutorialResourceIds", "sourceIds"),
                true, 6, 12);
    }

    static ResearchModels.ResearchTaskContract tutorialListContract(String topic, int minimumItems) {
        return new ResearchModels.ResearchTaskContract(
                ResearchModels.ResearchTaskType.RESOURCE_RESEARCH, topic,
                ResearchModels.DeliverableType.TUTORIAL_LIST, Math.max(5, minimumItems), 3,
                List.of(new ResearchModels.ResearchCategory("系统教学", 1),
                        new ResearchModels.ResearchCategory("分步跟练", 1)),
                List.of("name", "url", "teachingFocus", "suitableFor", "advice"),
                true, 6, 12);
    }

    static ResearchModels.ResearchTaskContract exerciseGuideContract(String topic) {
        return new ResearchModels.ResearchTaskContract(
                ResearchModels.ResearchTaskType.GENERAL_RESEARCH, topic,
                ResearchModels.DeliverableType.EXERCISE_GUIDE, 1, 2,
                List.of(new ResearchModels.ResearchCategory("原理与方法", 1),
                        new ResearchModels.ResearchCategory("安全边界", 1)),
                List.of("goal", "steps", "durationOrRepetitions", "commonMistakes", "safetyNotes", "sourceIds"),
                true, 5, 10);
    }

    static ResearchModels.ResearchTaskContract comparisonContract(String topic, int minimumItems) {
        return new ResearchModels.ResearchTaskContract(
                ResearchModels.ResearchTaskType.COMPARISON, topic,
                ResearchModels.DeliverableType.COMPARISON_TABLE, Math.max(2, minimumItems), 2,
                List.of(new ResearchModels.ResearchCategory("核心差异", 1),
                        new ResearchModels.ResearchCategory("适用场景", 1)),
                List.of("option", "comparisonDimensions", "strengths", "limitations", "suitableFor", "sourceIds"),
                false, 5, 10);
    }

    static ResearchModels.ResearchTaskContract researchSummaryContract(String topic) {
        return new ResearchModels.ResearchTaskContract(
                ResearchModels.ResearchTaskType.GENERAL_RESEARCH, topic,
                ResearchModels.DeliverableType.RESEARCH_SUMMARY, 1, 2,
                List.of(new ResearchModels.ResearchCategory("核心结论", 1),
                        new ResearchModels.ResearchCategory("适用边界", 1)),
                List.of("conclusion", "evidence", "limitations", "sourceIds"),
                false, 5, 10);
    }

    private static ResearchModels.ResearchTaskContract normalizeContract(
            ResearchModels.ResearchTaskContract generated
    ) {
        return switch (generated.deliverable()) {
            case SONG_RECOMMENDATION -> songRecommendationContract(generated.topic(), generated.minimumItems());
            case TUTORIAL_LIST -> tutorialListContract(generated.topic(), generated.minimumItems());
            case EXERCISE_GUIDE -> exerciseGuideContract(generated.topic());
            case COMPARISON_TABLE -> comparisonContract(generated.topic(), generated.minimumItems());
            case RESEARCH_SUMMARY -> researchSummaryContract(generated.topic());
            case RESOURCE_LIST -> generated;
        };
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private static int explicitMinimum(String request) {
        Matcher matcher = EXPLICIT_MINIMUM.matcher(request == null ? "" : request);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    @FunctionalInterface
    interface ContractGenerator {
        ResearchModels.ResearchTaskContract generate(String request);
    }
}
