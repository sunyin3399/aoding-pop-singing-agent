package com.sunyin.aodingagent.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HighNoteHighlightAnalyzerTest {

    private final HighNoteHighlightAnalyzer analyzer = new HighNoteHighlightAnalyzer();

    @Test
    void selectsAtMostThreeSeparatedFiveSecondRelativeHighWindows() {
        List<AudioPitchTrackResult.PitchPoint> points = new ArrayList<>();
        addTone(points, 1, 2, 60, -12);
        addTone(points, 5, 6, 70, -10);
        addTone(points, 12, 13, 71, -10);
        addTone(points, 20, 21, 72, -9);
        addTone(points, 28, 29, 73, -9);

        List<AudioPitchTrackResult.HighNoteHighlight> highlights = analyzer.analyze(
                points, sine(32, 16_000, 440), 16_000, 32, 60, 73);

        assertEquals(3, highlights.size());
        assertTrue(highlights.stream().allMatch(item -> item.endSeconds() - item.startSeconds() <= 5.01));
        assertTrue(highlights.get(0).startSeconds() < highlights.get(1).startSeconds());
        assertTrue(highlights.get(1).startSeconds() < highlights.get(2).startSeconds());
    }

    @Test
    void reportsStableContinuityAndNormalizedSpectrumWithoutDiagnosing() {
        List<AudioPitchTrackResult.PitchPoint> points = new ArrayList<>();
        addTone(points, 2, 7, 69, -8);

        AudioPitchTrackResult.HighNoteHighlight highlight = analyzer.analyze(
                points, sine(9, 16_000, 440), 16_000, 9, 60, 69).getFirst();

        assertEquals("A4", highlight.representativeNote());
        assertTrue(highlight.longestContinuousSeconds() >= 4.8);
        assertEquals(1, highlight.lowRatio() + highlight.midRatio() + highlight.highRatio(), 0.001);
        assertTrue(highlight.inferences().stream().anyMatch(item -> item.type().equals("STABLE_HIGH_NOTE")));
        assertTrue(highlight.inferences().stream().noneMatch(item -> item.evidence().contains("确定")));
    }

    @Test
    void leavesUnreliableMetricsEmptyInsteadOfInventingEvidence() {
        List<AudioPitchTrackResult.PitchPoint> points = List.of(point(1, null, false, -80));
        assertTrue(analyzer.analyze(points, new double[16_000], 16_000, 1, 60, 72).isEmpty());
    }

    private void addTone(List<AudioPitchTrackResult.PitchPoint> points,
                         double start, double end, double midi, double rms) {
        for (double time = start; time <= end; time += 0.1) points.add(point(time, midi, true, rms));
    }

    private AudioPitchTrackResult.PitchPoint point(double time, Double midi, boolean voiced, double rms) {
        Double frequency = midi == null ? null : 440 * Math.pow(2, (midi - 69) / 12);
        return new AudioPitchTrackResult.PitchPoint(
                time, frequency, midi, midi == null ? null : "A4", 0.0, voiced ? 0.95 : 0, voiced, rms);
    }

    private double[] sine(double seconds, int sampleRate, double frequency) {
        double[] samples = new double[(int) (seconds * sampleRate)];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = 0.4 * Math.sin(2 * Math.PI * frequency * index / sampleRate);
        }
        return samples;
    }
}
