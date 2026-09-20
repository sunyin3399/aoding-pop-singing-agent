package com.sunyin.aodingagent.audio;

import java.util.List;

public record AudioSpectrumResult(
        String fileName,
        double durationSeconds,
        float sampleRate,
        double nyquistHz,
        BandDefinitions bands,
        SpectrumSummary overall,
        List<SpectrumSegment> segments
) {
    public enum DominantBand { LOW, MID, HIGH, SILENCE }

    public record BandDefinition(double startHz, double endHz) { }

    public record BandDefinitions(
            BandDefinition low,
            BandDefinition mid,
            BandDefinition high
    ) { }

    public record SpectrumSummary(
            double lowRatio,
            double midRatio,
            double highRatio,
            DominantBand dominantBand
    ) { }

    public record SpectrumSegment(
            double startSeconds,
            double endSeconds,
            double lowRatio,
            double midRatio,
            double highRatio,
            double lowDbfs,
            double midDbfs,
            double highDbfs,
            Double spectralCentroidHz,
            DominantBand dominantBand
    ) { }
}
