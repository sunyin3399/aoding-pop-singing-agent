package com.sunyin.aodingagent.audio;

import java.util.List;

/**
 * 可解释的基础声学分析结果。所有分贝值均为相对于数字满刻度的 dBFS，
 * 不代表真实声压级，也不用于医疗诊断。
 * <p>
 * 这个结果同时服务于评分和前端图表。median/min/maxPitchHz 表示检测到的基础音高统计，
 * voicedRatio 表示有多少分析窗口能够可靠跟踪音高，visualization 保存绘图所需的压缩数据。
 */
public record AudioAnalysisResult(
        String fileName,
        double durationSeconds,
        float sampleRate,
        int channels,
        double loudnessDbfs,
        double peakDbfs,
        double zeroCrossingRate,
        Double medianPitchHz,
        Double minPitchHz,
        Double maxPitchHz,
        Double pitchStabilityCents,
        double voicedRatio,
        List<String> observations,
        AudioVisualizationData visualization
) {
    public AudioAnalysisResult(
            String fileName, double durationSeconds, float sampleRate, int channels,
            double loudnessDbfs, double peakDbfs, double zeroCrossingRate,
            Double medianPitchHz, Double minPitchHz, Double maxPitchHz,
            Double pitchStabilityCents, double voicedRatio, List<String> observations
    ) {
        this(fileName, durationSeconds, sampleRate, channels, loudnessDbfs, peakDbfs,
                zeroCrossingRate, medianPitchHz, minPitchHz, maxPitchHz,
                pitchStabilityCents, voicedRatio, observations,
                new AudioVisualizationData(List.of(), List.of(), List.of(),
                        MelSpectrogramData.empty(), SpectralFeatureSummary.empty()));
    }

    /** 前端绘制波形、响度包络、音高轨迹、Mel 声谱图和频谱摘要所需的数据。 */
    public record AudioVisualizationData(
            List<WaveformPoint> waveform,
            List<TimeValuePoint> rmsEnvelope,
            List<TimeValuePoint> pitchTrack,
            MelSpectrogramData melSpectrogram,
            SpectralFeatureSummary spectralFeatures
    ) { }

    /**
     * 量化后的梅尔声谱图。levels 按时间帧排列，每个值位于 0..255，避免在 JSON
     * 中传输庞大的浮点矩阵；前端再依据 minDb/maxDb 还原颜色含义。
     */
    public record MelSpectrogramData(
            int fftSize,
            int hopSize,
            double minDb,
            double maxDb,
            List<Double> frequenciesHz,
            List<Double> timesSeconds,
            List<List<Integer>> levels
    ) {
        public static MelSpectrogramData empty() {
            return new MelSpectrogramData(0, 0, -80, 0, List.of(), List.of(), List.of());
        }
    }

    /** 整段录音的频谱质心、带宽、滚降点和平坦度等摘要特征。 */
    public record SpectralFeatureSummary(
            Double centroidHz,
            Double bandwidthHz,
            Double rolloffHz,
            Double flatness
    ) {
        public static SpectralFeatureSummary empty() {
            return new SpectralFeatureSummary(null, null, null, null);
        }
    }

    /** 一个压缩波形区间的时间、最小振幅和最大振幅。 */
    public record WaveformPoint(double timeSeconds, double min, double max) { }

    /** 用于绘制随时间变化曲线的通用数据点。 */
    public record TimeValuePoint(double timeSeconds, Double value) { }
}
