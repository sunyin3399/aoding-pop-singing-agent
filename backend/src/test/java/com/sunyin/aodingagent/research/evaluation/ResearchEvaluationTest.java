package com.sunyin.aodingagent.research.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchEvaluationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void runsOfflineJsonlEvaluationAndWritesRepeatableMetrics() throws Exception {
        List<ResearchMetrics.Outcome> cases = loadCases();
        ResearchMetrics.Report first = ResearchMetrics.calculate(cases);
        ResearchMetrics.Report second = ResearchMetrics.calculate(loadCases());

        assertEquals(first, second);
        assertEquals(8, cases.size());
        assertTrue(first.citationValidityRate() < 1.0);
        assertTrue(first.scrapeRecoveryRate() > 0.0);

        Path output = Path.of("target/evaluation/research-metrics.json");
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), first);
        assertTrue(Files.readString(output).contains("researchSuccessRate"));
        assertTrue(Files.readString(output).contains("averageRecoveryRounds"));
    }

    private List<ResearchMetrics.Outcome> loadCases() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/evaluation/research-cases.jsonl");
        if (stream == null) throw new IllegalStateException("缺少 research-cases.jsonl");
        List<ResearchMetrics.Outcome> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (!line.isBlank()) cases.add(objectMapper.readValue(line, ResearchMetrics.Outcome.class));
            }
        }
        return cases;
    }
}
