package com.sunyin.aodingagent.research.finalization;

import com.sunyin.aodingagent.research.AgentReference;
import com.sunyin.aodingagent.research.ResearchModels;
import com.sunyin.aodingagent.research.ResearchTaskPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAgentResponseFinalizerTest {

    @Test
    void reusesExternalContractAndRepairsInvalidFirstDraftOnce() {
        ResearchModels.ResearchTaskContract contract = contract();
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger revisions = new AtomicInteger();
        ResearchTaskPlanner planner = request -> {
            plannerCalls.incrementAndGet();
            return contract;
        };
        ResearchDeliverableGenerator generator = new ResearchDeliverableGenerator() {
            @Override
            public ResearchDeliverable generate(String request, String draftAnswer, EvidenceBundle evidence) {
                return deliverable(List.of(item("红豆")));
            }

            @Override
            public ResearchDeliverable revise(String request, String draftAnswer, EvidenceBundle evidence,
                                               ResearchDeliverable previous,
                                               List<ResearchModels.ValidationIssue> issues) {
                revisions.incrementAndGet();
                return deliverable(List.of(item("红豆"), item("小幸运")));
            }
        };
        DefaultAgentResponseFinalizer finalizer = new DefaultAgentResponseFinalizer(planner, generator);
        EvidenceBundle evidence = new EvidenceBundle(List.of(reference()), contract, ResearchModels.ResearchStatus.SUCCESS);

        FinalizedAgentResponse result = finalizer.finalizeResponse("推荐两首歌", "旧草稿", evidence);

        assertEquals(0, plannerCalls.get(), "外部研究已经生成合同，不应再次做意图规划");
        assertEquals(1, revisions.get());
        assertTrue(result.markdown().contains("小幸运"));
        assertEquals(List.of(), result.validationIssues());
        assertEquals(evidence.references(), result.references());
        assertSafe(result);
    }

    @Test
    void modelFailureStillCleansDraftAndFiltersReferences() {
        DefaultAgentResponseFinalizer finalizer = new DefaultAgentResponseFinalizer(request -> contract(),
                failingGenerator());
        AgentReference unverified = new AgentReference("source-9", "未核验", "https://unverified.example", "",
                null, null);
        EvidenceBundle evidence = new EvidenceBundle(List.of(reference(), unverified), contract(),
                ResearchModels.ResearchStatus.SUCCESS);
        FinalizedAgentResponse result = finalizer.finalizeResponse("推荐", unsafeDraft(), evidence);
        assertSafe(result);
        assertEquals(List.of(reference()), result.references());
    }

    @Test
    void keepsInternalHitAndRemovesDuplicateSourceSection() {
        DefaultAgentResponseFinalizer finalizer = new DefaultAgentResponseFinalizer(request -> contract(),
                failingGenerator());
        AgentReference internal = new AgentReference("internal-1", "内部知识", "/api/ai/knowledge/documents/internal-1", "命中摘要",
                AgentReference.ReferenceStatus.INTERNAL_APPROVED, null);
        String draft = "回答正文\n\n---\n**资料来源：**\n* [外部资料](https://example.com/source)\n\n---";

        FinalizedAgentResponse result = finalizer.finalizeResponse("问题", draft,
                new EvidenceBundle(List.of(internal), contract(), ResearchModels.ResearchStatus.SUCCESS));

        assertEquals(List.of(internal), result.references());
        assertTrue(result.markdown().contains("回答正文"));
        assertTrue(!result.markdown().contains("资料来源"));
        assertTrue(!result.markdown().contains("---"));
    }

    @Test
    void plannerFailureStillCleansDraft() {
        DefaultAgentResponseFinalizer finalizer = new DefaultAgentResponseFinalizer(request -> {
            throw new IllegalStateException("planner unavailable");
        }, failingGenerator());
        assertSafe(finalizer.finalizeResponse("推荐", unsafeDraft(),
                new EvidenceBundle(List.of(reference()), null, null)));
    }

    @Test
    void cleanupFailureReturnsFixedMessageInsteadOfRawDraft() {
        DefaultAgentResponseFinalizer finalizer = new DefaultAgentResponseFinalizer(request -> contract(),
                failingGenerator());
        FinalizedAgentResponse result = finalizer.finalizeResponse("推荐", unsafeDraft(), null);
        assertEquals("暂时无法安全整理回答，请稍后重试。", result.markdown());
        assertEquals(List.of(), result.references());
    }

    @Test
    void failedValidationAndRevisionFailureStillCleanDraft() {
        for (boolean throwOnRevision : List.of(false, true)) {
            ResearchDeliverableGenerator generator = new ResearchDeliverableGenerator() {
                public ResearchDeliverable generate(String request, String draft, EvidenceBundle evidence) {
                    return deliverable(List.of(new ResearchDeliverable.DeliverableItem(
                            "一首", null, "理由", "练习", null, List.of("source-9"))));
                }
                public ResearchDeliverable revise(String request, String draft, EvidenceBundle evidence,
                        ResearchDeliverable previous, List<ResearchModels.ValidationIssue> issues) {
                    if (throwOnRevision) throw new IllegalStateException("revision unavailable");
                    return previous;
                }
            };
            FinalizedAgentResponse result = new DefaultAgentResponseFinalizer(request -> contract(), generator)
                    .finalizeResponse("推荐", unsafeDraft(), new EvidenceBundle(List.of(reference()), contract(), null));
            assertSafe(result);
            if (!throwOnRevision) assertTrue(!result.validationIssues().isEmpty());
        }
    }

    @Test
    void fastPathsStillCleanDraftWithoutCallingModel() {
        DefaultAgentResponseFinalizer finalizer = new DefaultAgentResponseFinalizer(request -> null, failingGenerator());
        assertSafe(finalizer.finalizeResponse("问答", unsafeDraft(), new EvidenceBundle(List.of(reference()), null, null)));
        assertSafe(finalizer.finalizeResponse("推荐", unsafeDraft() + " 《红豆》《小幸运》适合练习，避免过度。",
                new EvidenceBundle(List.of(reference()), contract(), null)));
    }

    @Test
    void doesNotDirectlyReleaseAnOverlyShortExternalDraft() {
        AtomicInteger generations = new AtomicInteger();
        ResearchDeliverableGenerator generator = new ResearchDeliverableGenerator() {
            public ResearchDeliverable generate(String request, String draft, EvidenceBundle evidence) {
                generations.incrementAndGet();
                return deliverable(List.of(item("红豆"), item("小幸运")));
            }
            public ResearchDeliverable revise(String request, String draft, EvidenceBundle evidence,
                                               ResearchDeliverable previous,
                                               List<ResearchModels.ValidationIssue> issues) {
                return previous;
            }
        };
        DefaultAgentResponseFinalizer finalizer = new DefaultAgentResponseFinalizer(request -> contract(), generator);

        finalizer.finalizeResponse("推荐", "《红豆》《小幸运》适合练习，但暂缓高音歌曲。",
                new EvidenceBundle(List.of(reference()), contract(), ResearchModels.ResearchStatus.SUCCESS));

        assertEquals(1, generations.get());
    }

    @Test
    void directlyReleasesDetailedConformantDraft() {
        AtomicInteger generations = new AtomicInteger();
        ResearchDeliverableGenerator generator = new ResearchDeliverableGenerator() {
            public ResearchDeliverable generate(String request, String draft, EvidenceBundle evidence) {
                generations.incrementAndGet();
                return deliverable(List.of(item("红豆"), item("小幸运")));
            }
            public ResearchDeliverable revise(String request, String draft, EvidenceBundle evidence,
                                               ResearchDeliverable previous,
                                               List<ResearchModels.ValidationIssue> issues) {
                return previous;
            }
        };
        String draft = ("《红豆》适合练习气息，《小幸运》适合练习稳定音准。"
                + "建议分别用慢速哼鸣、歌词朗读和原速演唱三步练习；高音吃力时不要硬顶，应暂缓并降低调性。"
                + "每天练习十分钟，出现疼痛、持续沙哑或明显不适时立即停止。\n").repeat(3);

        new DefaultAgentResponseFinalizer(request -> contract(), generator).finalizeResponse("推荐", draft,
                new EvidenceBundle(List.of(reference()), contract(), ResearchModels.ResearchStatus.SUCCESS));

        assertEquals(0, generations.get());
    }

    @Test
    void finalPromptKeepsOriginalRequestAndOnlyCompactVerifiedEvidence() {
        String request = "请保留这个完整原始要求";
        String longExcerpt = "有效摘要 ".repeat(200);
        AgentReference verified = new AgentReference("source-1", "已核验", "https://example.com/a", longExcerpt,
                AgentReference.ReferenceStatus.SCRAPED, null);
        AgentReference unverified = new AgentReference("source-2", "未核验", "https://example.com/b", "不要出现",
                AgentReference.ReferenceStatus.SEARCH_ONLY, null);

        String prompt = SpringAiResearchDeliverableGenerator.prompt(request, "草稿正文",
                new EvidenceBundle(List.of(verified, verified, unverified), contract(),
                        ResearchModels.ResearchStatus.SUCCESS), null, List.of());

        assertTrue(prompt.contains(request));
        assertTrue(prompt.contains("source-1"));
        assertTrue(!prompt.contains("source-2"));
        assertTrue(!prompt.contains("不要出现"));
        assertTrue(prompt.length() < longExcerpt.length());
        assertEquals(prompt.indexOf("source-1"), prompt.lastIndexOf("source-1"));
    }

    private String unsafeDraft() {
        return "保留建议 [可信](knowledge://source-1) [伪造](https://evil.example) [source-9] <script>bad()</script>";
    }

    private void assertSafe(FinalizedAgentResponse result) {
        assertTrue(result.markdown().contains("保留建议"));
        assertTrue(result.markdown().contains("knowledge://source-1"));
        assertTrue(!result.markdown().contains("evil.example"));
        assertTrue(!result.markdown().contains("source-9"));
        assertTrue(!result.markdown().contains("<script>"));
    }

    private ResearchDeliverableGenerator failingGenerator() {
        return new ResearchDeliverableGenerator() {
            public ResearchDeliverable generate(String request, String draftAnswer, EvidenceBundle evidence) {
                throw new IllegalStateException("model unavailable");
            }
            public ResearchDeliverable revise(String request, String draftAnswer, EvidenceBundle evidence,
                                               ResearchDeliverable previous, List<ResearchModels.ValidationIssue> issues) {
                throw new IllegalStateException("revision unavailable");
            }
        };
    }

    private ResearchModels.ResearchTaskContract contract() {
        return new ResearchModels.ResearchTaskContract(
                ResearchModels.ResearchTaskType.RECOMMENDATION, "初学者歌曲",
                ResearchModels.DeliverableType.SONG_RECOMMENDATION,
                2, 1, List.of(), List.of(), false, 3, 3);
    }

    private ResearchDeliverable deliverable(List<ResearchDeliverable.DeliverableItem> items) {
        return new ResearchDeliverable(ResearchModels.ResearchStatus.SUCCESS,
                ResearchModels.DeliverableType.SONG_RECOMMENDATION, unsafeDraft(), List.of(), items, List.of(), List.of());
    }

    private ResearchDeliverable.DeliverableItem item(String name) {
        return new ResearchDeliverable.DeliverableItem(
                name, null, "旋律稳定", "练习气息", null, List.of("source-1"));
    }

    private AgentReference reference() {
        return new AgentReference("source-1", "内部资料", "knowledge://source-1", "摘要",
                AgentReference.ReferenceStatus.SCRAPED, null);
    }
}
