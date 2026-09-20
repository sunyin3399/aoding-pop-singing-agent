package com.sunyin.aodingagent.audio;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 根据基础声学分析给长音或单音练习打分。
 * <p>
 * 总分由音高可控性、连续发声和录音质量三部分组成，目的是帮助用户在相似录音条件下比较
 * 多次练习的变化。因为输入中没有参考旋律和节拍，这个分数不是歌曲音准分，也不能评价
 * 用户是否唱对了歌词对应的音符或节奏。
 */
@Service
public class VocalScoringService {

    private static final double PITCH_WEIGHT = 0.45;
    private static final double CONTINUITY_WEIGHT = 0.30;
    private static final double RECORDING_WEIGHT = 0.25;

    /**
     * 计算基础练习分数、可信度、各维度证据和针对最弱维度的建议。
     *
     * @param analysis {@link AudioAnalysisService} 生成的综合声学分析
     * @return 0 到 100 的辅助分数及其适用范围，不能作为专业声乐或医学结论
     */
    public VocalScoreResult score(AudioAnalysisResult analysis) {
        int pitchScore = pitchControlScore(analysis.pitchStabilityCents());
        int continuityScore = clamp((int) Math.round(20 + analysis.voicedRatio() * 80));
        int recordingScore = recordingQualityScore(analysis);
        int total = clamp((int) Math.round(
                pitchScore * PITCH_WEIGHT
                        + continuityScore * CONTINUITY_WEIGHT
                        + recordingScore * RECORDING_WEIGHT));

        List<VocalScoreResult.ScoreDimension> dimensions = List.of(
                new VocalScoreResult.ScoreDimension(
                        "pitchControl", "音高可控性", pitchScore, PITCH_WEIGHT,
                        analysis.pitchStabilityCents() == null
                                ? "未获得足够的稳定音高窗口"
                                : "音高波动标准差为 %.1f cents".formatted(analysis.pitchStabilityCents())),
                new VocalScoreResult.ScoreDimension(
                        "continuity", "连续发声", continuityScore, CONTINUITY_WEIGHT,
                        "%.1f%% 的分析窗口可稳定追踪音高".formatted(analysis.voicedRatio() * 100)),
                new VocalScoreResult.ScoreDimension(
                        "recording", "录音质量", recordingScore, RECORDING_WEIGHT,
                        "整体响度 %.1f dBFS，峰值 %.1f dBFS，过零率 %.3f"
                                .formatted(analysis.loudnessDbfs(), analysis.peakDbfs(), analysis.zeroCrossingRate()))
        );

        return new VocalScoreResult(
                total,
                grade(total),
                confidence(analysis),
                "基础长音/单音练习评分",
                "仅评估音高稳定性、连续发声和录音质量；没有参考旋律与节拍时，不评价歌曲音准或节奏准确率。",
                dimensions,
                feedback(dimensions, analysis),
                analysis
        );
    }

    private int pitchControlScore(Double stabilityCents) {
        if (stabilityCents == null) return 15;
        if (stabilityCents <= 25) return 100;
        if (stabilityCents >= 250) return 25;
        return clamp((int) Math.round(100 - (stabilityCents - 25) * 75 / 225));
    }

    private int recordingQualityScore(AudioAnalysisResult analysis) {
        double score = 100;
        if (analysis.loudnessDbfs() < -30) score -= Math.min(40, (-30 - analysis.loudnessDbfs()) * 2.5);
        if (analysis.loudnessDbfs() > -10) score -= Math.min(25, (analysis.loudnessDbfs() + 10) * 3);
        if (analysis.peakDbfs() > -0.2) score -= 35;
        else if (analysis.peakDbfs() > -1.0) score -= 18;
        if (analysis.zeroCrossingRate() > 0.25) score -= Math.min(20, (analysis.zeroCrossingRate() - 0.25) * 80);
        return clamp((int) Math.round(score));
    }

    private double confidence(AudioAnalysisResult analysis) {
        double durationFactor = Math.min(1, analysis.durationSeconds() / 8.0);
        double pitchFactor = analysis.medianPitchHz() == null ? 0 : 1;
        double value = durationFactor * 0.25
                + Math.min(1, analysis.voicedRatio()) * 0.50
                + pitchFactor * 0.25;
        return Math.round(value * 1000) / 1000.0;
    }

    private List<String> feedback(List<VocalScoreResult.ScoreDimension> dimensions, AudioAnalysisResult analysis) {
        List<String> feedback = new ArrayList<>();
        VocalScoreResult.ScoreDimension weakest = dimensions.stream()
                .min(Comparator.comparingInt(VocalScoreResult.ScoreDimension::score))
                .orElse(dimensions.get(0));
        switch (weakest.key()) {
            case "pitchControl" -> feedback.add("先用舒适音区做 5～8 秒直音长音，减少滑音和颤音后复测音高稳定性。");
            case "continuity" -> feedback.add("录制时减少长停顿，保持均匀发声；若使用气声唱法，应单独选择对应评测模式。");
            case "recording" -> feedback.add("保持麦克风距离稳定，避免削波或过低电平后再进行演唱表现比较。");
            default -> { }
        }
        if (analysis.durationSeconds() < 5) feedback.add("录音较短，建议至少录制 5～10 秒以提高评分可信度。");
        if (analysis.pitchStabilityCents() != null && analysis.pitchStabilityCents() > 120) {
            feedback.add("若本次上传的是完整歌曲，旋律、转音和颤音会放大音高波动，本分数不应作为歌曲音准分。");
        }
        feedback.add("训练后使用相同麦克风距离、相似音高和相似音量复测，分数变化才具有可比性。");
        return List.copyOf(feedback);
    }

    private String grade(int score) {
        if (score >= 90) return "优秀";
        if (score >= 80) return "良好";
        if (score >= 70) return "稳定";
        if (score >= 60) return "继续练习";
        return "建议调整后复测";
    }

    private int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }
}
