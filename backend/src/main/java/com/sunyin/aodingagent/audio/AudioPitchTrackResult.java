package com.sunyin.aodingagent.audio;

import java.util.List;

/**
 * 音高轨迹接口返回给前端的完整结果。
 * <p>
 * 除了每个时间点的音高，还包含用于画图的波形、稳健音域摘要、高音重点片段、给 LLM 的精简
 * 描述和明确的分析范围。稳健音域会排除两端少量异常点，不等同于录音中出现过的绝对最低/最高音。
 */
public record AudioPitchTrackResult(
        String fileName,
        double durationSeconds,
        double sampleRate,
        double frameIntervalSeconds,
        double minMidi,
        double maxMidi,
        List<PitchPoint> points,
        List<WaveformPoint> waveform,
        PitchSummary summary,
        List<HighNoteHighlight> highNoteHighlights,
        String llmSummary,
        String analysisScope
) {
    /** 某个分析时间点的音高、音名、偏差、可信度和是否检测到有效发声。 */
    public record PitchPoint(
            double timeSeconds,
            Double frequencyHz,
            Double midi,
            String note,
            Double centsOffset,
            double confidence,
            boolean voiced,
            double rmsDbfs
    ) { }

    /** 为前端压缩后的一个波形区间，保存该区间最小和最大振幅。 */
    public record WaveformPoint(double timeSeconds, double min, double max) { }

    /**
     * 整段录音的音高摘要。robust 字段是排除少量异常点后的可靠范围，tessitura 是主要使用音区。
     */
    public record PitchSummary(
            String lowestNote,
            String highestNote,
            String robustLowestNote,
            String robustHighestNote,
            String medianNote,
            String tessitura,
            Double pitchStabilityCents,
            int pitchBreakCount,
            Double highNoteThresholdMidi
    ) { }

    /** 一个相对高音重点片段及其连续性、音高变化、频谱、观察和推测。 */
    public record HighNoteHighlight(
            double startSeconds,
            double endSeconds,
            String representativeNote,
            Double representativeMidi,
            Double medianCentsFromRepresentative,
            Double belowRepresentativeRatio,
            Double pitchStabilityCents,
            Double maxAbruptJumpCents,
            Double longestContinuousSeconds,
            int interruptionCount,
            Double lowRatio,
            Double midRatio,
            Double highRatio,
            Double spectralCentroidHz,
            Double harmonicBalance,
            List<HighlightObservation> observations,
            List<HighlightInference> inferences
    ) { }

    /** 可以直接由音频数据计算出的客观观察。 */
    public record HighlightObservation(String label, String value, String evidence) { }

    /** 根据多个观察推测的可能现象，必须同时提供置信度、证据和保守建议。 */
    public record HighlightInference(
            String type, String label, String confidence, String evidence, String advice
    ) { }
}
