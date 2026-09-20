package com.sunyin.aodingagent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RetrievalTraceRecorder} 的单元测试。
 */
class RetrievalTraceRecorderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private final RetrievalTraceRecorder.RetrievalTrace sampleTrace = new RetrievalTraceRecorder.RetrievalTrace(
            "训练混声怎么练",
            Instant.parse("2026-08-19T08:00:00Z"),
            15,
            0.55,
            List.of(
                    new RetrievalTraceRecorder.Hit("doc-1", "混声训练.md", 2, 0.72, 1),
                    new RetrievalTraceRecorder.Hit("doc-2", "混声训练.md", 5, 0.68, 2)),
            List.of("doc-1"));

    @Test
    void disabledRecorderWritesNothing() {
        RetrievalTraceRecorder recorder = RetrievalTraceRecorder.disabled();
        recorder.record(sampleTrace);

        assertFalse(recorder.isEnabled());
        try (var stream = Files.list(tempDir)) {
            assertEquals(0, stream.count());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void writesAppendableJsonlByDay() throws Exception {
        RetrievalTraceRecorder recorder = new RetrievalTraceRecorder(tempDir);
        recorder.record(sampleTrace);
        recorder.record(sampleTrace);

        Path daily = tempDir.resolve(java.time.LocalDate.now(java.time.ZoneId.systemDefault()) + ".jsonl");
        assertTrue(Files.exists(daily));

        List<String> lines = Files.readAllLines(daily);
        assertEquals(2, lines.size(), "两条痕迹应追加写入同一日文件");

        JsonNode first = MAPPER.readTree(lines.get(0));
        assertEquals("训练混声怎么练", first.get("query").asText());
        assertEquals(0.55, first.get("threshold").asDouble(), 1e-6);
        assertEquals(2, first.get("retrieved").size());
        assertEquals("doc-1", first.get("retrieved").get(0).get("id").asText());
        assertEquals(1, first.get("retrieved").get(0).get("rank").asInt());
        assertEquals("doc-1", first.get("usedIds").get(0).asText());
    }
}
