package com.sunyin.aodingagent.research.finalization;

import com.sunyin.aodingagent.research.ResearchModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchDeliverableRendererTest {

    @Test
    void rendersNaturalMarkdownWithoutPuttingUrlsIntoAnswerBody() {
        ResearchDeliverable deliverable = new ResearchDeliverable(
                ResearchModels.ResearchStatus.SUCCESS,
                ResearchModels.DeliverableType.SONG_RECOMMENDATION,
                "先从音域舒适的歌曲开始。",
                List.of(new ResearchDeliverable.DeliverableSection("推荐歌曲", null,
                        List.of(new ResearchDeliverable.DeliverableItem(
                                "红豆", null, "旋律平稳", "练习连贯气息", null, List.of("source-1"))))),
                List.of(), List.of("嗓音不适时立即停止"), List.of("source-1"));

        String markdown = new ResearchDeliverableRenderer().render(deliverable);

        assertTrue(markdown.contains("## 推荐歌曲"));
        assertTrue(markdown.contains("1. **红豆**"));
        assertTrue(markdown.contains("**练习重点**：练习连贯气息"));
        assertTrue(markdown.contains("## 注意事项"));
        assertFalse(markdown.contains("http"));
        assertFalse(markdown.contains("source-1"));
    }
}
