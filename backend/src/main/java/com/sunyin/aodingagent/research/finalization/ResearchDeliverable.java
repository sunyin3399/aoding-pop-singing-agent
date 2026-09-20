package com.sunyin.aodingagent.research.finalization;

import com.sunyin.aodingagent.research.ResearchModels;

import java.util.List;

/**
 * Agent 最终交付内容的结构化表示。
 * <p>
 * 这个对象不是直接展示给用户的 JSON。它先让 Java 程序检查数量、字段、引用和状态，验收通过后
 * 再由 {@link ResearchDeliverableRenderer} 渲染成前端已经支持的 Markdown。
 */
public record ResearchDeliverable(
        ResearchModels.ResearchStatus status,
        ResearchModels.DeliverableType deliverableType,
        String summary,
        List<DeliverableSection> sections,
        List<DeliverableItem> items,
        List<String> cautions,
        List<String> referenceIds
) {
    public ResearchDeliverable {
        sections = sections == null ? List.of() : List.copyOf(sections);
        items = items == null ? List.of() : List.copyOf(items);
        cautions = cautions == null ? List.of() : List.copyOf(cautions);
        referenceIds = referenceIds == null ? List.of() : List.copyOf(referenceIds);
    }

    /** 一个自然内容分组，例如“推荐歌曲”“暂缓歌曲”或“练习方法”。 */
    public record DeliverableSection(String title, String content, List<DeliverableItem> items) {
        public DeliverableSection {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * 一个可交付条目。不同任务只使用需要的字段，避免所有回答都机械套用同一格式。
     */
    public record DeliverableItem(
            String name,
            String description,
            String recommendationReason,
            String practiceFocus,
            String suitability,
            List<String> referenceIds
    ) {
        public DeliverableItem {
            referenceIds = referenceIds == null ? List.of() : List.copyOf(referenceIds);
        }
    }
}
