package com.sunyin.aodingagent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.stream.StreamSessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyAgentCaptureV2Test {
    @TempDir Path dir;
    final ObjectMapper json = new ObjectMapper();
    DailyAgentCapture capture() {
        return new DailyAgentCapture(new MockEnvironment().withProperty("app.agent-eval.enabled", "true")
                .withProperty("app.agent-eval.dir", dir.toString()), Runnable::run);
    }
    JsonNode row() throws Exception {
        try (var files = Files.list(dir.resolve("raw"))) {
            return json.readTree(files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow().toFile());
        }
    }
    @Test void scenarioAndTurnOrderSurviveCaptureRestartWithoutRawIdentifiers() throws Exception {
        var registry = new StreamSessionRegistry();
        for (int i = 0; i < 3; i++) {
            int turn = i;
            var recorder = capture();
            registry.getOrStart("request-" + i, s -> {
                recorder.attach(s, "turn-" + turn, "manus/chat", "private-conversation", "GENERAL_AGENT");
                s.emit("final", "answer"); s.complete();
            });
        }
        try (var files = Files.list(dir.resolve("raw"))) {
            var rows = files.map(p -> { try { return json.readTree(p.toFile()); } catch (Exception e) { throw new RuntimeException(e); } }).toList();
            assertEquals(3, rows.size());
            assertEquals(1, rows.stream().map(r -> r.path("scenarioKey").asText()).distinct().count());
            assertEquals(java.util.Set.of(1,2,3), rows.stream().map(r -> r.path("turnIndex").asInt()).collect(java.util.stream.Collectors.toSet()));
            assertTrue(rows.stream().allMatch(r -> r.path("scenarioKey").asText().matches("[0-9a-f]{64}")));
            assertFalse(rows.toString().contains("private-conversation"));
        }
    }
    @Test void businessTimeoutHasSanitizedSummaryAndRealTerminalStatus() throws Exception {
        new StreamSessionRegistry().getOrStart("timeout", s -> {
            capture().attach(s, "q", "music_app/chat/sse");
            s.fail(new java.util.concurrent.TimeoutException("password=fixture timeout"));
        });
        var r = row();
        assertEquals("TIMEOUT", r.path("terminalStatus").asText());
        assertEquals("java.util.concurrent.TimeoutException", r.path("errorType").asText());
        assertTrue(r.path("errorSummary").asText().contains("[REDACTED]"));
        assertFalse(r.toString().contains("fixture"));
    }
    @Test void shutdownFlushesQueuedWritesAndPersistsRejectedSnapshotCounts() throws Exception {
        var rejected = new DailyAgentCapture(new MockEnvironment().withProperty("app.agent-eval.enabled", "true")
                .withProperty("app.agent-eval.dir", dir.toString()), task -> { throw new java.util.concurrent.RejectedExecutionException(); });
        new StreamSessionRegistry().getOrStart("rejected", s -> {
            rejected.attach(s, "q", "manus/chat"); s.emit("final", "answer"); s.complete();
        });
        // Invoke Spring's shutdown callback reflectively so RED tests missing behavior, not compilation.
        var close = java.util.Arrays.stream(DailyAgentCapture.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(jakarta.annotation.PreDestroy.class)).findFirst();
        assertTrue(close.isPresent(), "capture needs a bounded shutdown flush callback");
        close.get().setAccessible(true); close.get().invoke(rejected);
        var stats = json.readTree(dir.resolve("diagnostics.json").toFile()).path("metadata");
        assertEquals(2, stats.path("dropped").asInt());
        assertEquals(2, stats.path("failed").asInt());
        assertEquals(0, stats.path("captured").asInt());

        var recorder = new DailyAgentCapture(new MockEnvironment().withProperty("app.agent-eval.enabled", "true")
                .withProperty("app.agent-eval.dir", dir.resolve("flush").toString()));
        new StreamSessionRegistry().getOrStart("flush", s -> {
            recorder.attach(s, "q", "manus/chat"); s.emit("final", "answer"); s.complete();
        });
        close.get().invoke(recorder);
        try (var files = Files.list(dir.resolve("flush/raw"))) {
            assertEquals("SUCCESS", json.readTree(files.findFirst().orElseThrow().toFile()).path("terminalStatus").asText());
        }
    }
    @Test void errorSummaryTruncationAndUnobservedDefaultsMustBeHonest() throws Exception {
        new StreamSessionRegistry().getOrStart("large-error", s -> {
            capture().attach(s, "q", "manus/chat");
            s.fail(new IllegalStateException("x".repeat(2000)));
        });
        var r = row();
        assertTrue(r.path("truncation").path("any").asBoolean());
        assertEquals(1024, r.path("errorSummary").asText().length());
        assertEquals("unavailable", r.path("configuredParameters").path("app.rag.top-k").asText());
    }
    @Test void writesPrivateV2RecordWithHonestUnavailableMetadata() throws Exception {
        new StreamSessionRegistry().getOrStart("v2", s -> {
            capture().attach(s,"question","manus/chat"); s.emit("final","answer"); s.complete();
        });
        assertTrue(Files.isDirectory(dir.resolve("raw")), "v2 raw records live under raw/");
        var row = row();
        assertEquals(2,row.path("schemaVersion").asInt());
        assertEquals(2,row.path("captureSchemaVersion").asInt());
        for (String field : new String[]{"runId","route","agentType","mode","question","finalAnswer","terminalStatus",
                "startedAt","finishedAt","elapsedMs","truncation","errorType","errorSummary","model","provider",
                "inferenceParameters","promptVersion","buildVersion","tokenUsage","scenarioKey","turnIndex"})
            assertTrue(row.has(field), field);
        assertEquals("answer",row.path("finalAnswer").asText());
        assertFalse(row.path("privacyReviewed").asBoolean(true));
        assertEquals("unavailable",row.path("tokenUsage").asText());
        assertEquals("PARTIAL",row.path("traceCompleteness").asText());
        for (String field : new String[]{"retrievalCandidateIds","promptDocumentIds","referenceIds"})
            assertEquals("unavailable",row.path(field).asText());
        var diagnostics = json.readTree(dir.resolve("diagnostics.json").toFile());
        assertEquals(1, diagnostics.path("metadata").path("captured").asInt());
        assertEquals(0, diagnostics.path("metadata").path("failed").asInt());
    }
}
