package com.sunyin.aodingagent.research.finalization;

import java.util.List;

/**
 * 把验收后的结构化交付物转换成前端支持的 Markdown。
 * <p>
 * 引用链接仍通过独立 SSE references 事件发送，本类不会重新拼接 URL，避免渲染阶段引入未经核验的链接。
 */
public final class ResearchDeliverableRenderer {

    public String render(ResearchDeliverable deliverable) {
        StringBuilder markdown = new StringBuilder();
        appendParagraph(markdown, deliverable.summary());
        for (ResearchDeliverable.DeliverableSection section : deliverable.sections()) {
            if (section.title() != null && !section.title().isBlank()) {
                markdown.append("\n## ").append(section.title().trim()).append("\n\n");
            }
            appendParagraph(markdown, section.content());
            appendItems(markdown, section.items());
        }
        if (!deliverable.items().isEmpty()) {
            if (!deliverable.sections().isEmpty()) markdown.append("\n");
            appendItems(markdown, deliverable.items());
        }
        if (!deliverable.cautions().isEmpty()) {
            markdown.append("\n## 注意事项\n\n");
            for (String caution : deliverable.cautions()) {
                if (caution != null && !caution.isBlank()) markdown.append("- ").append(caution.trim()).append("\n");
            }
        }
        return markdown.toString().trim();
    }

    private void appendItems(StringBuilder markdown, List<ResearchDeliverable.DeliverableItem> items) {
        int number = 1;
        for (ResearchDeliverable.DeliverableItem item : items) {
            if (item.name() == null || item.name().isBlank()) continue;
            markdown.append(number++).append(". **").append(item.name().trim()).append("**\n");
            appendField(markdown, "说明", item.description());
            appendField(markdown, "推荐理由", item.recommendationReason());
            appendField(markdown, "练习重点", item.practiceFocus());
            appendField(markdown, "适合程度", item.suitability());
            markdown.append("\n");
        }
    }

    private void appendField(StringBuilder markdown, String label, String value) {
        if (value != null && !value.isBlank()) markdown.append("   - **").append(label).append("**：").append(value.trim()).append("\n");
    }

    private void appendParagraph(StringBuilder markdown, String value) {
        if (value != null && !value.isBlank()) markdown.append(value.trim()).append("\n\n");
    }
}
