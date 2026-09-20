package com.sunyin.aodingagent.research.finalization;

import com.sunyin.aodingagent.research.AgentReference;
import com.sunyin.aodingagent.research.ResearchModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchDeliverableValidatorTest {

    private final ResearchDeliverableValidator validator = new ResearchDeliverableValidator();

    @Test
    void rejectsMissingItemsOverstatedStatusAndInventedReferences() {
        ResearchModels.ResearchTaskContract contract = songContract(3);
        EvidenceBundle evidence = new EvidenceBundle(
                List.of(reference("source-1", AgentReference.ReferenceStatus.SCRAPED)),
                contract, ResearchModels.ResearchStatus.DEGRADED);
        ResearchDeliverable deliverable = new ResearchDeliverable(
                ResearchModels.ResearchStatus.SUCCESS,
                ResearchModels.DeliverableType.SONG_RECOMMENDATION,
                "测试摘要", List.of(),
                List.of(item("红豆", "source-invented")), List.of(), List.of());

        List<String> codes = validator.validate(deliverable, evidence).stream()
                .map(ResearchModels.ValidationIssue::code).toList();

        assertTrue(codes.contains("MINIMUM_DELIVERABLE_ITEMS_NOT_MET"));
        assertTrue(codes.contains("STATUS_OVERSTATED"));
        assertTrue(codes.contains("REFERENCE_NOT_ALLOWED"));
    }

    @Test
    void acceptsCompleteSongRecommendationWithVerifiedReferences() {
        ResearchModels.ResearchTaskContract contract = songContract(2);
        EvidenceBundle evidence = new EvidenceBundle(
                List.of(reference("source-1", AgentReference.ReferenceStatus.INTERNAL_APPROVED)),
                contract, ResearchModels.ResearchStatus.SUCCESS);
        ResearchDeliverable deliverable = new ResearchDeliverable(
                ResearchModels.ResearchStatus.SUCCESS,
                ResearchModels.DeliverableType.SONG_RECOMMENDATION,
                "下面按难度推荐。", List.of(),
                List.of(item("红豆", "source-1"), item("小幸运", "source-1")), List.of(), List.of("source-1"));

        assertEquals(List.of(), validator.validate(deliverable, evidence));
    }

    private ResearchModels.ResearchTaskContract songContract(int minimumItems) {
        return new ResearchModels.ResearchTaskContract(
                ResearchModels.ResearchTaskType.RECOMMENDATION, "初学者歌曲",
                ResearchModels.DeliverableType.SONG_RECOMMENDATION,
                minimumItems, 1, List.of(), List.of("推荐理由", "练习重点"), false, 3, 3);
    }

    private ResearchDeliverable.DeliverableItem item(String name, String referenceId) {
        return new ResearchDeliverable.DeliverableItem(
                name, null, "旋律和节奏相对平稳", "保持自然气息", "适合初学者", List.of(referenceId));
    }

    private AgentReference reference(String id, AgentReference.ReferenceStatus status) {
        return new AgentReference(id, "资料标题", "https://example.com/" + id, "摘要", status, null);
    }
}
