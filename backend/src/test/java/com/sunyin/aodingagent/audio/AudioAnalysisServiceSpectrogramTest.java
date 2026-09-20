package com.sunyin.aodingagent.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AudioAnalysisServiceSpectrogramTest {

    private static final float SAMPLE_RATE = 48_000;
    private static final double LOG_BUCKET_RATIO = Math.pow(24_000.0 / 20.0, 1.0 / 240.0);
    private final AudioAnalysisService service = new AudioAnalysisService();

    @Test
    void exposesStableSpectrogramResponseShape() {
        AudioSpectrogramResult result = new AudioSpectrogramResult(
                "tone.wav", 1.0, 48_000, 24_000, 8192, 0.1,
                20, 24_000, -120, 0,
                List.of(20.0, 21.0), List.of(-20.0, -30.0),
                List.of(new AudioSpectrogramResult.SpectrogramFrame(0.0, List.of(-20.0, -30.0))));

        assertThat(result.fftSize()).isEqualTo(8192);
        assertThat(result.frames().getFirst().dbfs()).hasSize(2);
    }

    @Test
    void localizesTonesAndReturnsStableFiniteFramesAtOneHundredMillisecondHops() {
        for (double toneHz : List.of(100.0, 1_000.0, 4_000.0)) {
            AudioSpectrogramResult result = service.analyzeSpectrogramSamples(
                    "tone.wav", sineWave(toneHz, 0.5), SAMPLE_RATE, 1);

            int peakBucket = indexOfMaximum(result.peakDbfs());
            assertThat(result.frequenciesHz().get(peakBucket))
                    .isBetween(toneHz / LOG_BUCKET_RATIO, toneHz * LOG_BUCKET_RATIO);
            assertThat(result.frequenciesHz()).hasSize(240);
            assertThat(result.frames()).allSatisfy(frame -> {
                assertThat(frame.dbfs()).hasSize(240);
                assertThat(frame.dbfs()).allSatisfy(value ->
                        assertThat(value).isFinite().isBetween(-120.0, 0.0));
            });
            assertThat(result.frames().get(1).timeSeconds() - result.frames().get(0).timeSeconds())
                    .isCloseTo(0.1, within(0.01));
        }
    }

    @Test
    void analyzesAShortTailWithoutProducingNonFiniteValues() {
        AudioSpectrogramResult result = service.analyzeSpectrogramSamples(
                "tail.wav", sineWave(1_000, 0.205), SAMPLE_RATE, 1);

        assertThat(result.frames()).hasSize(3);
        assertThat(result.frames().getLast().timeSeconds()).isEqualTo(0.2);
        assertThat(result.frames()).allSatisfy(frame ->
                assertThat(frame.dbfs()).allSatisfy(value -> assertThat(value).isFinite()));
        assertThat(result.peakDbfs()).allSatisfy(value -> assertThat(value).isFinite());
    }

    @Test
    void returnsOnlyTheFloorForSilence() {
        AudioSpectrogramResult result = service.analyzeSpectrogramSamples(
                "silence.wav", new double[(int) SAMPLE_RATE / 2], SAMPLE_RATE, 1);

        assertThat(result.frames()).isNotEmpty().allSatisfy(frame ->
                assertThat(frame.dbfs()).containsOnly(-120.0));
        assertThat(result.peakDbfs()).containsOnly(-120.0);
    }

    @Test
    void keepsTheExactNyquistToneFiniteAndInTheLastBucket() {
        double[] samples = new double[(int) (SAMPLE_RATE * 0.34)];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = index % 2 == 0 ? 0.25 : -0.25;
        }

        AudioSpectrogramResult result = service.analyzeSpectrogramSamples(
                "nyquist.wav", samples, SAMPLE_RATE, 1);

        double lastBucket = result.peakDbfs().getLast();
        assertThat(lastBucket).isFinite();
        assertThat(lastBucket).isCloseTo(-12.0, within(0.1));
        assertThat(indexOfMaximum(result.peakDbfs())).isEqualTo(239);
    }

    @Test
    void skipsACEnergyWhoseHannMainLobeEndsBelowTwentyHertz() {
        float sampleRate = 12_047.059f;
        double[] samples = new double[2_048];
        double frequencyHz = sampleRate * 2 / (samples.length - 1);
        for (int index = 0; index < samples.length; index++) {
            samples[index] = 0.75 * Math.sin(2 * Math.PI * frequencyHz * index / sampleRate);
        }

        AudioSpectrogramResult result = service.analyzeSpectrogramSamples(
                "below-cutoff.wav", samples, sampleRate, 1);

        assertThat(result.frequenciesHz()).allMatch(frequency -> frequency >= 20.0);
        assertThat(result.peakDbfs().get(6)).isCloseTo(-15.1, within(0.2));
        assertThat(indexOfMaximum(result.peakDbfs())).isEqualTo(6);
    }

    @Test
    void rejectsOutOfRangeSampleRatesBeforeSizingFftArrays() {
        assertThatThrownBy(() -> service.analyzeSpectrogramSamples(
                "hostile.wav", new double[]{0.25}, 1_000_000, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("音频采样率必须在 8000 Hz 到 192000 Hz 之间");
    }

    private double[] sineWave(double frequencyHz, double durationSeconds) {
        double[] samples = new double[(int) Math.round(SAMPLE_RATE * durationSeconds)];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = Math.sin(2 * Math.PI * frequencyHz * index / SAMPLE_RATE);
        }
        return samples;
    }

    private int indexOfMaximum(List<Double> values) {
        int maximumIndex = 0;
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index) > values.get(maximumIndex)) {
                maximumIndex = index;
            }
        }
        return maximumIndex;
    }

}
