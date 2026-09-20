package com.sunyin.aodingagent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.stream.StreamSessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyAgentTraceTest {
    @TempDir Path dir;
    final ObjectMapper json = new ObjectMapper();
    JsonNode capture(String route, String references) throws Exception {
        var recorder = new DailyAgentCapture(new MockEnvironment()
                .withProperty("app.agent-eval.enabled", "true")
                .withProperty("app.agent-eval.dir", dir.toString()), Runnable::run);
        new StreamSessionRegistry().getOrStart("test", s -> {
            recorder.attach(s, "q", route);
            if (references != null) s.emit("references", references);
            s.emit("final", "answer"); s.complete();
        });
        try (var files = Files.list(dir.resolve("raw"))) {
            return json.readTree(files.findFirst().orElseThrow().toFile());
        }
    }
    @Test void consumesStructuredToolsAndDoesNotTurnUnknownIntoEmpty() throws Exception {
        var recorder = new DailyAgentCapture(new MockEnvironment()
                .withProperty("app.agent-eval.enabled", "true")
                .withProperty("app.agent-eval.dir", dir.toString()), Runnable::run);
        new StreamSessionRegistry().getOrStart("trace", s -> {
            recorder.attach(s, "q", "manus/chat");
            s.emit("tool_start", "{\"toolName\":\"fixtureTool\",\"callId\":\"local-1\",\"success\":\"unavailable\",\"elapsedMs\":0,\"outputTruncated\":\"unavailable\",\"input\":\"C:/private\"}");
            s.emit("tool_result", "{\"toolName\":\"fixtureTool\",\"callId\":\"local-1\",\"success\":false,\"elapsedMs\":12,\"outputTruncated\":true,\"output\":\"sk-secret\"}");
            s.emit("final", "answer"); s.complete();
        });
        try (var files = Files.list(dir.resolve("raw"))) {
            var row = json.readTree(files.findFirst().orElseThrow().toFile());
            assertEquals(2, row.path("toolTrace").size());
            assertEquals("unavailable", row.path("toolTrace").get(0).path("success").asText());
            assertFalse(row.path("toolTrace").get(1).path("success").asBoolean(true));
            assertTrue(row.path("toolTrace").get(1).path("outputTruncated").asBoolean());
            assertEquals(12, row.path("toolTrace").get(1).path("elapsedMs").asInt());
            assertFalse(row.toString().contains("C:/private"));
            assertFalse(row.toString().contains("sk-secret"));
        }
    }
    @Test void modelMetadataComesFromTheActualChatModelNotEvalLabels() throws Exception {
        var env = new MockEnvironment().withProperty("app.agent-eval.enabled", "true")
                .withProperty("app.agent-eval.dir", dir.toString())
                .withProperty("app.agent-eval.model", "invented-label")
                .withProperty("app.agent-eval.provider", "invented-provider")
                .withProperty("spring.ai.openai.api-key", "sk-do-not-copy")
                .withProperty("spring.ai.openai.chat.options.model", "configured-fallback")
                .withProperty("spring.ai.openai.chat.options.temperature", "0.3");
        var recorder = new DailyAgentCapture(env, Runnable::run);
        var model = org.mockito.Mockito.mock(org.springframework.ai.openai.OpenAiChatModel.class);
        org.mockito.Mockito.when(model.getDefaultOptions()).thenReturn(org.springframework.ai.openai.OpenAiChatOptions.builder()
                .model("actual-bean-default").temperature(0.4).maxTokens(777).build());
        new StreamSessionRegistry().getOrStart("configured", s -> {
            // 用反射保持 RED 在缺少能力时是断言失败，而非测试无法编译。
            var attach = java.util.Arrays.stream(DailyAgentCapture.class.getMethods())
                    .filter(m -> m.getName().equals("attach") && m.getParameterCount() == 6).findFirst();
            assertTrue(attach.isPresent(), "capture must accept the route's actual ChatModel explicitly");
            try { attach.get().invoke(recorder, s, "q", "manus/chat", null, "GENERAL_AGENT", model); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            s.emit("final", "answer"); s.complete();
        });
        try (var files = Files.list(dir.resolve("raw"))) {
            var row = json.readTree(files.findFirst().orElseThrow().toFile());
            assertEquals("actual-bean-default", row.path("model").asText());
            assertEquals("OpenAI-compatible", row.path("provider").asText());
            assertTrue(row.path("metadataProvenance").asText().contains("not response"));
            assertEquals(0.4, row.path("inferenceParameters").path("temperature").asDouble());
            assertEquals(777, row.path("inferenceParameters").path("max-tokens").asInt());
            assertEquals("configured-fallback", row.path("configuredParameters").path("spring.ai.openai.chat.options.model").asText());
            assertFalse(row.toString().contains("invented"));
            assertFalse(row.toString().contains("sk-do-not-copy"));
        }
        org.mockito.Mockito.verify(model, org.mockito.Mockito.never()).call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test void actualReferenceIdsAreNotTitlesOrUsedDocumentIds() throws Exception {
        var row = capture("manus/chat", "[{\"id\":\"doc-1\",\"title\":\"private title\"},{\"id\":\"doc-1\"}]");
        assertEquals(json.readTree("[\"doc-1\"]"), row.path("referenceIds"));
        assertEquals("unavailable", row.path("promptDocumentIds").asText());
        assertEquals("unavailable", row.path("retrievalCandidateIds").asText());
        assertTrue(row.path("unavailableReasons").has("retrievalCandidateIds"));
    }
}
