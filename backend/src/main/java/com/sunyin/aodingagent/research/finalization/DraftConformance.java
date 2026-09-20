package com.sunyin.aodingagent.research.finalization;

import com.sunyin.aodingagent.research.ResearchModels.DeliverableType;

import java.util.regex.Pattern;

/**
 * 判断 Agent 草稿是否已“基本满足”某类交付物的结构要求。
 * <p>
 * 这是确定性启发式（零额外 LLM 调用）：草稿已含对应交付物该有的结构标记就判定达标，
 * 由 {@link DefaultAgentResponseFinalizer} 直接放行草稿、跳过整篇重排，避免把 LLM 自己的
 * 输出再喂回 LLM 生成一遍。判断保守——拿不准就返回 false，走原来的生成器补齐，安全优先。
 */
final class DraftConformance {

    private DraftConformance() {
    }

    private static final Pattern SONGS = Pattern.compile("《[^》]{1,30}》");

    private static boolean has(String text, String... markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 草稿是否已基本满足该交付物的结构要求。 */
    static boolean conformant(DeliverableType deliverable, String draft) {
        if (draft == null || draft.isBlank()) {
            return false;
        }
        return switch (deliverable) {
            case EXERCISE_GUIDE -> has(draft, "分钟", "秒", "次", "组", "每组", "每天")
                    && has(draft, "常见错误", "误区", "注意")
                    && has(draft, "停止", "安全", "不适", "刺痛", "沙哑", "嘶哑", "立即", "受伤");
            case SONG_RECOMMENDATION -> {
                long songs = SONGS.matcher(draft).results().count();
                yield songs >= 2 && has(draft, "适合", "练习", "建议")
                        && has(draft, "不建议", "避免", "暂缓", "不要");
            }
            case TUTORIAL_LIST -> draft.contains("http")
                    && has(draft, "教学", "示范", "跟练", "课程", "教程", "干货");
            case RESOURCE_LIST -> draft.contains("http")
                    && has(draft, "建议", "适合", "帮助", "学习", "课程");
            // 其余交付物（RESEARCH_SUMMARY / COMPARISON_TABLE 等）已在上层直接放行草稿，不会走到这里。
            default -> false;
        };
    }
}
