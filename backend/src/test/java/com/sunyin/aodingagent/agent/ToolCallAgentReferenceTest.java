package com.sunyin.aodingagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.research.AgentReference;
import com.sunyin.aodingagent.research.ResearchModels;
import com.sunyin.aodingagent.research.WebSearchResult;
import com.sunyin.aodingagent.research.finalization.FinalizedAgentResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallAgentReferenceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void repeatedSearchToolResponsesAccumulateIntoStructuredSsePayload() throws Exception {
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0], null);
        agent.collectSearchReferences(json("first", "资源 A", "https://example.com/a"));
        agent.collectSearchReferences(json("second", "资源 B", "https://example.com/b"));

        List<?> payload = objectMapper.readValue(agent.referencesPayload(), List.class);

        assertThat(agent.getReferences().snapshot()).extracting(AgentReference::title)
                .containsExactly("资源 A", "资源 B");
        assertThat(payload).hasSize(2);
    }

    @Test
    void collectsOnlyScrapedSourcesFromResearchToolResults() {
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0], null);
        agent.collectResearchReferences("""
                {"success":false,"code":"RESEARCH_DEGRADED","data":{"sources":[
                  {"id":"verified","title":"已核验","url":"https://example.com/a","snippet":"摘要","scrapeStatus":"SCRAPED","extractedContent":"正文"},
                  {"id":"failed","title":"失败来源","url":"https://example.com/b","snippet":"摘要","scrapeStatus":"FAILED","extractedContent":""}
                ]}}
                """);

        assertThat(agent.getReferences().snapshot()).singleElement()
                .extracting(AgentReference::title, AgentReference::status)
                .containsExactly("已核验", AgentReference.ReferenceStatus.SCRAPED);
    }

    @Test
    void collectsInternalRagCitationsForFinalizationAndReferencesEvent() {
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0], null);

        agent.collectInternalKnowledgeReferences("""
                {"success":true,"data":{"citations":[
                  {"id":"knowledge-1","title":"初学者歌曲资料","url":"/api/ai/knowledge/documents/knowledge-1","excerpt":"红豆适合练习连贯气息"}
                ]}}
                """);

        assertThat(agent.getReferences().snapshot()).singleElement()
                .extracting(AgentReference::id, AgentReference::status)
                .containsExactly("knowledge-1", AgentReference.ReferenceStatus.INTERNAL_APPROVED);
        assertThat(agent.referencesPayload()).contains("初学者歌曲资料");
    }

    @Test
    void keepsInternalRagAnswerWithoutCallingTheLlmFinalizer() {
        AtomicInteger finalizerCalls = new AtomicInteger();
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0], null, (request, draft, evidence) -> {
            finalizerCalls.incrementAndGet();
            throw new AssertionError("internal RAG must not be rewritten");
        });
        agent.collectInternalKnowledgeReferences("""
                {"success":true,"data":{"citations":[
                  {"id":"knowledge-1","title":"歌曲资料","url":"/api/ai/knowledge/documents/knowledge-1","excerpt":"逐曲建议"}
                ]}}
                """);

        assertThat(agent.finalizeAnswer("保留精确的原始回答")).isEqualTo("保留精确的原始回答");
        assertThat(finalizerCalls).hasValue(0);
        assertThat(agent.getReferences().snapshot()).hasSize(1);
    }

    @Test
    void keepsDetailedInternalSongRecommendationWhenKnowledgeAlreadyCoversRequest() {
        AtomicInteger finalizerCalls = new AtomicInteger();
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0], null, (request, draft, evidence) -> {
            finalizerCalls.incrementAndGet();
            throw new AssertionError("a fully covered internal recommendation must not enter external finalization");
        });
        agent.collectInternalKnowledgeReferences("""
                {"success":true,"data":{"citations":[
                  {"id":"song-a","title":"内部歌曲推荐","url":"/api/ai/knowledge/documents/song-a","excerpt":"《歌曲A》：适合平滑起音；《歌曲B》：适合长句气息；《歌曲C》：适合自然咬字；《歌曲D》：适合轻声练习；《歌曲E》：适合分段练习"}
                ]}}
                """);

        String answer = """
                推荐歌曲：
                1. 《歌曲A》：练习平滑起音，避免喉部挤压。
                2. 《歌曲B》：练习长句气息，先分句演唱。
                3. 《歌曲C》：练习自然咬字，保持轻声进入。
                4. 《歌曲D》：练习轻声和放松感。
                5. 《歌曲E》：练习分段控制和稳定换气。
                安全提醒：出现疼痛或持续嘶哑时立即停止。
                """;

        assertThat(agent.finalizeAnswer(answer)).isEqualTo(answer);
        assertThat(finalizerCalls).hasValue(0);
        assertThat(answer).contains("《歌曲A》", "《歌曲B》", "《歌曲C》", "《歌曲D》", "《歌曲E》")
                .contains("平滑起音", "长句气息", "安全提醒");
        assertThat(agent.getReferences().snapshot()).singleElement()
                .extracting(AgentReference::status)
                .isEqualTo(AgentReference.ReferenceStatus.INTERNAL_APPROVED);
    }

    @Test
    void stillFinalizesAnExternalResearchContract() {
        AtomicInteger finalizerCalls = new AtomicInteger();
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0], null, (request, draft, evidence) -> {
            finalizerCalls.incrementAndGet();
            return new FinalizedAgentResponse("结构化外部研究结果", null, evidence.references(), List.of());
        });
        agent.setResearchContract(new ResearchModels.ResearchTaskContract(
                ResearchModels.ResearchTaskType.RESOURCE_RESEARCH, "练声资源",
                ResearchModels.DeliverableType.RESOURCE_LIST, 5, 3,
                List.of(), List.of(), true, 6, 12));

        assertThat(agent.finalizeAnswer("外部研究草稿")).isEqualTo("结构化外部研究结果");
        assertThat(finalizerCalls).hasValue(1);
    }

    private String json(String query, String title, String url) throws Exception {
        return objectMapper.writeValueAsString(WebSearchResult.success(query,
                List.of(new WebSearchResult.SearchItem(title, url, "摘要"))));
    }
}
