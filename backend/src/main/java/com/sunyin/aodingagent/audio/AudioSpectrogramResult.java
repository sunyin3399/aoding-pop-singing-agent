package com.sunyin.aodingagent.audio;

import java.util.List;

public record AudioSpectrogramResult(
        String fileName,
        double durationSeconds,
        double sampleRate,
        double nyquistHz,
        int fftSize,
        double hopSeconds,
        double minFrequencyHz,
        double maxFrequencyHz,
        double minDbfs,
        double maxDbfs,
        List<Double> frequenciesHz,
        List<Double> peakDbfs,
        List<SpectrogramFrame> frames
) {
    public record SpectrogramFrame(double timeSeconds, List<Double> dbfs) { }
}
