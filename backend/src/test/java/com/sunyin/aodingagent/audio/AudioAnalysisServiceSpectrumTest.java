package com.sunyin.aodingagent.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AudioAnalysisServiceSpectrumTest {

    private final AudioAnalysisService service = new AudioAnalysisService();

    @ParameterizedTest
    @CsvSource({"100,LOW", "1000,MID", "4000,HIGH"})
    void classifiesPureToneInExpectedBand(
            double frequency,
            AudioSpectrumResult.DominantBand expectedBand
    ) {
        float sampleRate = 16_000;

        AudioSpectrumResult result = service.analyzeSpectrumSamples(
                "tone.wav",
                sine(frequency, 1.0, sampleRate, 0.5),
                sampleRate,
                1
        );

        AudioSpectrumResult.SpectrumSegment segment = result.segments().getFirst();
        assertThat(segment.dominantBand()).isEqualTo(expectedBand);
        assertThat(ratioFor(segment, expectedBand)).isGreaterThan(0.95);
        assertThat(segment.spectralCentroidHz()).isCloseTo(frequency, within(1.0));
    }

    @Test
    void reportsSilenceWithoutInventingAFrequencyBand() {
        AudioSpectrumResult result = service.analyzeSpectrumSamples(
                "silence.wav", new double[16_000], 16_000, 1);

        AudioSpectrumResult.SpectrumSegment segment = result.segments().getFirst();
        assertThat(segment.dominantBand()).isEqualTo(AudioSpectrumResult.DominantBand.SILENCE);
        assertThat(segment.spectralCentroidHz()).isNull();
        assertThat(segment.lowRatio()).isZero();
        assertThat(segment.midRatio()).isZero();
        assertThat(segment.highRatio()).isZero();
        assertThat(segment.lowDbfs()).isEqualTo(-120.0);
        assertThat(segment.midDbfs()).isEqualTo(-120.0);
        assertThat(segment.highDbfs()).isEqualTo(-120.0);
    }

    @Test
    void reportsEachSecondInTimeOrder() {
        float sampleRate = 16_000;
        double[] samples = concatenate(
                sine(100, 1.0, sampleRate, 0.5),
                sine(4_000, 1.0, sampleRate, 0.5)
        );

        AudioSpectrumResult result = service.analyzeSpectrumSamples(
                "two-seconds.wav", samples, sampleRate, 1);

        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments()).extracting(AudioSpectrumResult.SpectrumSegment::dominantBand)
                .containsExactly(AudioSpectrumResult.DominantBand.LOW, AudioSpectrumResult.DominantBand.HIGH);
        assertThat(result.segments()).extracting(AudioSpectrumResult.SpectrumSegment::startSeconds)
                .containsExactly(0.0, 1.0);
        assertThat(result.segments()).extracting(AudioSpectrumResult.SpectrumSegment::endSeconds)
                .containsExactly(1.0, 2.0);
    }

    @Test
    void includesTheFinalPartialSecond() {
        float sampleRate = 16_000;

        AudioSpectrumResult result = service.analyzeSpectrumSamples(
                "partial.wav", sine(1_000, 1.25, sampleRate, 0.5), sampleRate, 1);

        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().get(1).startSeconds()).isEqualTo(1.0);
        assertThat(result.segments().get(1).endSeconds()).isEqualTo(1.25);
    }

    @Test
    void weightsOverallBandByAccumulatedEnergy() {
        float sampleRate = 16_000;
        double[] samples = concatenate(
                sine(4_000, 1.0, sampleRate, 0.05),
                sine(100, 0.25, sampleRate, 0.5)
        );

        AudioSpectrumResult result = service.analyzeSpectrumSamples(
                "weighted.wav", samples, sampleRate, 1);

        assertThat(result.overall().dominantBand()).isEqualTo(AudioSpectrumResult.DominantBand.LOW);
        assertThat(result.overall().lowRatio()).isGreaterThan(0.95);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void treatsTinyFinalSegmentWithoutUsableSpectrumAsSilence(int tailSampleCount) {
        float sampleRate = 16_000;
        double[] tail = new double[tailSampleCount];
        for (int index = 0; index < tail.length; index++) {
            tail[index] = index % 2 == 0 ? 0.5 : -0.5;
        }

        AudioSpectrumResult result = service.analyzeSpectrumSamples(
                "tiny-tail.wav",
                concatenate(sine(1_000, 1.0, sampleRate, 0.5), tail),
                sampleRate,
                1
        );

        AudioSpectrumResult.SpectrumSegment tailSegment = result.segments().get(1);
        assertThat(tailSegment.dominantBand()).isEqualTo(AudioSpectrumResult.DominantBand.SILENCE);
        assertThat(tailSegment.spectralCentroidHz()).isNull();
        assertThat(tailSegment.lowRatio()).isZero();
        assertThat(tailSegment.midRatio()).isZero();
        assertThat(tailSegment.highRatio()).isZero();
        assertThat(tailSegment.lowDbfs()).isEqualTo(-120.0);
        assertThat(tailSegment.midDbfs()).isEqualTo(-120.0);
        assertThat(tailSegment.highDbfs()).isEqualTo(-120.0);
    }

    @Test
    void doesNotDoubleNyquistBinEnergy() {
        float sampleRate = 16_000;
        double[] samples = new double[(int) sampleRate];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = index % 2 == 0 ? 0.5 : -0.5;
        }

        AudioSpectrumResult result = service.analyzeSpectrumSamples(
                "nyquist.wav", samples, sampleRate, 1);

        AudioSpectrumResult.SpectrumSegment segment = result.segments().getFirst();
        assertThat(segment.dominantBand()).isEqualTo(AudioSpectrumResult.DominantBand.HIGH);
        assertThat(segment.highRatio()).isGreaterThan(0.99);
        assertThat(segment.highDbfs()).isCloseTo(-6.02, within(0.05));
    }

    private double ratioFor(
            AudioSpectrumResult.SpectrumSegment segment,
            AudioSpectrumResult.DominantBand band
    ) {
        return switch (band) {
            case LOW -> segment.lowRatio();
            case MID -> segment.midRatio();
            case HIGH -> segment.highRatio();
            case SILENCE -> 0;
        };
    }

    private double[] sine(double frequencyHz, double seconds, float sampleRate, double amplitude) {
        int sampleCount = (int) Math.round(seconds * sampleRate);
        double[] samples = new double[sampleCount];
        for (int index = 0; index < sampleCount; index++) {
            samples[index] = amplitude * Math.sin(2 * Math.PI * frequencyHz * index / sampleRate);
        }
        return samples;
    }

    private double[] concatenate(double[] first, double[] second) {
        double[] combined = new double[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }
}
