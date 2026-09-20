package com.sunyin.aodingagent.audio;

import java.util.List;

public record VocalScoreResult(
        int totalScore,
        String grade,
        double confidence,
        String assessmentType,
        String scoreScope,
        List<ScoreDimension> dimensions,
        List<String> feedback,
        AudioAnalysisResult analysis
) {
    public record ScoreDimension(
            String key,
            String label,
            int score,
            double weight,
            String evidence
    ) {
    }
}
