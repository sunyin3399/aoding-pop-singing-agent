package com.sunyin.aodingagent.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class AudioAnalysisServicePitchTrackTest {

    private final AudioAnalysisService service = new AudioAnalysisService();

    @Test
    void identifiesA3AndA4AndBuildsCompactSummary() {
        int sampleRate = 16_000;
        double[] samples = concatenate(
                tone(220, 1.0, sampleRate, 0.6),
                new double[sampleRate / 4],
                tone(440, 1.0, sampleRate, 0.6));

        AudioPitchTrackResult result = service.analyzePitchTrackSamples("range.wav", samples, sampleRate, 1);

        assertThat(result.points()).isNotEmpty();
        assertThat(result.points()).extracting(AudioPitchTrackResult.PitchPoint::timeSeconds).isSorted();
        assertThat(result.points()).anySatisfy(point -> {
            assertThat(point.note()).isEqualTo("A3");
            assertThat(point.midi()).isCloseTo(57, offset(0.35));
        });
        assertThat(result.points()).anySatisfy(point -> {
            assertThat(point.note()).isEqualTo("A4");
            assertThat(point.midi()).isCloseTo(69, offset(0.35));
        });
        assertThat(result.summary().lowestNote()).isEqualTo("A3");
        assertThat(result.summary().highestNote()).isEqualTo("A4");
        assertThat(result.summary().robustLowestNote()).isEqualTo("A3");
        assertThat(result.summary().robustHighestNote()).isEqualTo("A4");
        assertThat(result.summary().highNoteThresholdMidi()).isNotNull();
        assertThat(result.llmSummary()).contains("A3", "A4", "无参考旋律");
        assertThat(result.llmSummary()).doesNotContain("跨度", "有效发声比例", "声带紧张", "确定破音");
        assertThat(result.llmSummary().length()).isLessThan(1200);
    }

    @Test
    void marksSilenceAsUnvoicedAndKeepsAllNumbersFinite() {
        int sampleRate = 16_000;
        double[] samples = concatenate(
                tone(330, 0.6, sampleRate, 0.5),
                new double[(int) (sampleRate * 0.4)],
                tone(330, 0.6, sampleRate, 0.5));

        AudioPitchTrackResult result = service.analyzePitchTrackSamples("gap.wav", samples, sampleRate, 1);

        assertThat(result.points()).anyMatch(point -> !point.voiced());
        assertThat(result.points()).filteredOn(AudioPitchTrackResult.PitchPoint::voiced).allSatisfy(point -> {
            assertThat(point.frequencyHz()).isFinite();
            assertThat(point.midi()).isFinite();
            assertThat(point.confidence()).isBetween(0.0, 1.0);
            assertThat(point.rmsDbfs()).isFinite();
        });
        assertThat(result.summary().pitchBreakCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.analysisScope()).contains("不评价歌曲音准", "节奏");
        assertThat(result.highNoteHighlights()).allSatisfy(highlight -> {
            assertThat(highlight.endSeconds() - highlight.startSeconds()).isLessThanOrEqualTo(5.01);
            assertThat(highlight.representativeNote()).isNotBlank();
        });
    }

    private double[] tone(double frequency, double seconds, int sampleRate, double amplitude) {
        int size = (int) Math.round(seconds * sampleRate);
        double[] samples = new double[size];
        for (int index = 0; index < size; index++) {
            samples[index] = amplitude * Math.sin(2 * Math.PI * frequency * index / sampleRate);
        }
        return samples;
    }

    private double[] concatenate(double[]... parts) {
        int length = java.util.Arrays.stream(parts).mapToInt(part -> part.length).sum();
        double[] result = new double[length];
        int offset = 0;
        for (double[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
