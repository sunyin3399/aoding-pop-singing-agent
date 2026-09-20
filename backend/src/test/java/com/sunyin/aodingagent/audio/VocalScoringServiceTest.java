package com.sunyin.aodingagent.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VocalScoringServiceTest {

    private final VocalScoringService service = new VocalScoringService();

    @Test
    void givesHighScoreToStableContinuousCleanLongTone() {
        AudioAnalysisResult input = result(10, -18, -3, 0.05, 440.0, 20.0, 0.95);

        VocalScoreResult score = service.score(input);

        assertThat(score.totalScore()).isGreaterThanOrEqualTo(90);
        assertThat(score.confidence()).isGreaterThanOrEqualTo(0.9);
        assertThat(score.dimensions()).extracting(VocalScoreResult.ScoreDimension::key)
                .containsExactly("pitchControl", "continuity", "recording");
    }

    @Test
    void lowersScoreAndConfidenceWhenPitchEvidenceIsMissing() {
        AudioAnalysisResult input = result(2, -42, -40, 0.32, null, null, 0.08);

        VocalScoreResult score = service.score(input);

        assertThat(score.totalScore()).isLessThan(55);
        assertThat(score.confidence()).isLessThan(0.4);
        assertThat(score.scoreScope()).contains("不评价歌曲音准或节奏准确率");
    }

    private AudioAnalysisResult result(double duration, double loudness, double peak,
                                       double zcr, Double pitch, Double stability, double voicedRatio) {
        return new AudioAnalysisResult("test.wav", duration, 16000, 1, loudness, peak, zcr,
                pitch, pitch, pitch, stability, voicedRatio, List.of());
    }
}
