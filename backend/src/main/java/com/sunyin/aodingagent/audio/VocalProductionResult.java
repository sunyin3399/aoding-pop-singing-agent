package com.sunyin.aodingagent.audio;

import java.util.List;

/**
 * 人声"发声行为"分析结果。
 * <p>
 * 按 {@link VocalFeatureAnalyzer} 输出的两层标准组织：
 * <ul>
 *   <li><b>频段分布</b>（混音/EQ 视角）：把频谱切成若干感知频段，各给能量占比与 dB，
 *       用于回答"这段声音主要能量落在哪、听感是闷还是亮"。</li>
 *   <li><b>按音区分组</b>（发声机制视角）：把有音高的帧按低/中/高音区聚合，逐区给出
 *       H1-H2、频谱斜率、歌手共振峰、齿音、空气感等相对音高的指标。</li>
 * </ul>
 * 最后由 {@link VocalBehaviorClassifier} 依据这些指标推断发声行为（气声/假声/用力/齿音/鼻音等），
 * 每条推断都带证据与置信度。所有值仅用于演唱练习辅助，不做医学诊断。
 */
public record VocalProductionResult(
        String fileName,
        double durationSeconds,
        float sampleRate,
        double nyquistHz,
        List<BandEnergy> bandEnergies,
        List<RegisterAnalysis> registers,
        List<BehaviorInference> behaviors,
        String summaryNote,
        String scope,
        List<RegisterControl> registerControls,
        List<BreakEvent> breakEvents,
        String controlNote
) {
    /** 一个感知频段的能量占比与电平。 */
    public record BandEnergy(
            String name,
            double startHz,
            double endHz,
            double ratio,
            double dbfs
    ) { }

    /** 某一个音区（低/中/高）内的发声机制指标汇总。 */
    public record RegisterAnalysis(
            String register,
            int voicedFrameCount,
            Double medianPitchHz,
            Double minPitchHz,
            Double maxPitchHz,
            Double pitchStabilityCents,
            Double medianDbfs,
            Double medianH1h2Db,
            Double spectralSlopeDbPerOctave,
            Double singerFormantDbfs,
            Double airRatio,
            Double sibilanceRatio,
            Double nasalRatio,
            Double chestness
    ) { }

    /** 一条发声行为推断。 */
    public record BehaviorInference(
            String label,
            double confidence,
            String evidence,
            String register
    ) { }

    /**
     * 某一音区"基准 vs 顶部"的发声控制指标（相对本人中下部基线，非绝对值），
     * 用于直接描述：随音高往上，漏气/响度/歌手共振峰/稳定度怎么变，是否出现破音。
     */
    public record RegisterControl(
            String register,
            int baselineFrames,
            int topFrames,
            Double baselinePitchHz,
            Double topPitchHz,
            Double baselineH1h2Db,
            Double topH1h2Db,
            Double baselineAirPct,
            Double topAirPct,
            Double baselineDbfs,
            Double topDbfs,
            Double baselineSingerDb,
            Double topSingerDb,
            Double h1h2PerOctaveDb,
            Double stabilityCents,
            int breakCount
    ) { }

    /** 一次"疑似破音/瞬间断裂"（参考性，无参考旋律无法完全确证）。 */
    public record BreakEvent(
            double timeSeconds,
            Double pitchHz,
            String register,
            String note
    ) { }
}
