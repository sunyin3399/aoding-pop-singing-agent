package com.sunyin.aodingagent.audio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发声行为推断器。
 * <p>
 * 原则：<b>按音区分别推断，不做整首平均</b>。不同音区的人声机制可能不同（例如"高音偏弱混、
 * 中低偏实"），因此对每个有效音区，用该音区自己的指标独立给出发声倾向。
 *
 * <p>判读以 <b>H1-H2</b>（phonation/气声↔闭合的主征，Toles 2025 等）为核心，按音区拆分：
 * <ul>
 *   <li>H1-H2 很高 → 偏气声/空气感（气息偏多）；</li>
 *   <li>H1-H2 中高 → 偏弱混/轻混（轻、带空气、声区偏弱机能）；</li>
 *   <li>H1-H2 中低 → 偏实（闭合较好）；</li>
 *   <li>H1-H2 很低 → 偏实/偏胸（闭合紧、音色偏低沉厚）。</li>
 * </ul>
 * 唯一例外是<b>高音区且足够响亮</b>：这时即使 H1-H2 偏高也多是"强力/强混高音"，而非轻气声。
 *
 * <p>齿音用独立门槛：齿音(5-8kHz)占整体能量超过 1% 即在听感上明显，提示"注意唇齿音"。
 * 所有值仅用于演唱练习辅助，不做医学诊断。
 */
public final class VocalBehaviorClassifier {

    private static final int MIN_VOICED_FRAMES = 60;

    // H1-H2 分档阈值（占位，待样本校准）
    private static final double AIRY_H1H2 = 10.0;     // >= 此：偏气声/空气感
    private static final double WEAK_H1H2 = 5.0;      // >= 此：偏弱混/轻混
    private static final double SOLID_H1H2 = 1.5;     // >= 此：偏实；< 此：偏实/偏胸
    // 高音区"强力"需足够响亮（dBFS），避免把轻柔的弱混高音误判成强力
    private static final double POWER_HIGH_DBFS = -11.0;
    // 齿音门槛：>1% 即提示（听感已明显）
    private static final double SIBILANCE_RATIO = 0.01;
    private static final double NORMAL_SINGER_FORMANT_DBFS = -35.0;

    private VocalBehaviorClassifier() {
        // 工具类
    }

    /** 把特征转成音区分析记录（保留数值，供前端展示证据）。 */
    public static List<VocalProductionResult.RegisterAnalysis> buildRegisters(VocalFeatureAnalyzer.VocalFeatures features) {
        List<VocalProductionResult.RegisterAnalysis> out = new ArrayList<>();
        for (VocalFeatureAnalyzer.RegisterStats stats : features.registers().values()) {
            if (stats.voicedFrameCount() == 0) continue;
            out.add(new VocalProductionResult.RegisterAnalysis(
                    stats.register,
                    stats.voicedFrameCount(),
                    stats.medianPitchHz(),
                    stats.minPitchHz(),
                    stats.maxPitchHz(),
                    round(stats.pitchStabilityCents(), 1),
                    round(stats.medianDbfs(), 1),
                    round(stats.medianH1h2(), 1),
                    round(stats.medianSlope(), 1),
                    round(stats.medianSingerFormant(), 1),
                    round(stats.medianAirRatio(), 4),
                    round(stats.medianSibilanceRatio(), 4),
                    round(stats.medianNasalRatio(), 3),
                    null));
        }
        return out;
    }

    /** 依据特征推断发声行为：按音区分别推断。 */
    public static List<VocalProductionResult.BehaviorInference> inferBehaviors(
            VocalFeatureAnalyzer.VocalFeatures features) {
        List<VocalProductionResult.BehaviorInference> out = new ArrayList<>();
        boolean anyFlag = false;

        // 齿音：整体占比 > 1% 即提示（听感已明显）
        if (features.overallSibilanceRatio() > SIBILANCE_RATIO) {
            anyFlag = true;
            double confidence = clamp(0.5 + (features.overallSibilanceRatio() - SIBILANCE_RATIO) / 0.03, 0.5, 0.85);
            out.add(new VocalProductionResult.BehaviorInference(
                    "唇齿音偏多",
                    round(confidence, 2),
                    "齿音能量占比 %.1f%%，/s/、/sh/ 等唇齿音成分较明显，吐字时齿音略重".formatted(features.overallSibilanceRatio() * 100),
                    "整体"));
        }

        // 按音区分别推断（不做整首平均）
        for (VocalFeatureAnalyzer.RegisterStats stats : features.registers().values()) {
            if (stats.voicedFrameCount() < MIN_VOICED_FRAMES) continue;
            VocalProductionResult.BehaviorInference inference = classifyRegister(features, stats);
            out.add(inference);
            anyFlag = true;
        }

        if (!anyFlag && hasSingerFormant(features)) {
            out.add(new VocalProductionResult.BehaviorInference(
                    "未检出明显偏区", round(0.5, 2),
                    "未检测到足够的主音区样本或无明显极端特征", "整体"));
        }
        return out;
    }

    /** 对单个音区用其自身指标给出发声倾向。 */
    private static VocalProductionResult.BehaviorInference classifyRegister(
            VocalFeatureAnalyzer.VocalFeatures features,
            VocalFeatureAnalyzer.RegisterStats stats) {
        boolean high = VocalFeatureAnalyzer.RegisterStats.HIGH.equals(stats.register);
        Double h1h2v = stats.medianH1h2();
        double h = h1h2v == null ? 0 : h1h2v;
        Double dbfsV = stats.medianDbfs();
        double dbfs = dbfsV == null ? 0 : dbfsV;
        Double slopeV = stats.medianSlope();
        double slope = slopeV == null ? 0 : slopeV;
        Double sfV = stats.medianSingerFormant();
        double sf = sfV == null ? -999 : sfV;

        // 高音区且足够响亮：即使 H1-H2 偏高，也多为强力/强混高音，而非轻气声
        if (high && dbfs > POWER_HIGH_DBFS) {
            return new VocalProductionResult.BehaviorInference(
                    "强力高音（强混）", round(0.62, 2),
                    "高音相当响亮、声音有力量，属胸声/强混顶出的有力高音，而非轻柔的气声",
                    stats.register);
        }

        String label;
        String tone;
        double confidence;
        if (h >= AIRY_H1H2) {
            label = "偏气声/空气感";
            tone = "声区偏松、气息较多、带明显空气感（作为空灵/松弛的气声风格时是一种表现手段）";
            confidence = 0.5 + clamp((h - AIRY_H1H2) / 6.0, 0, 0.3);
        } else if (h >= WEAK_H1H2) {
            label = "偏弱混/轻混";
            tone = "声区较轻、带一点空气感，偏弱混/轻混机能的运用";
            confidence = 0.55;
        } else if (h >= SOLID_H1H2) {
            label = "偏实";
            tone = "声带闭合较好、发声较扎实，气息适中";
            confidence = 0.5;
        } else {
            label = "偏实/偏胸";
            tone = "声带闭合较紧、音色偏低沉/厚，偏向胸声/实唱";
            confidence = 0.5 + clamp((SOLID_H1H2 - h) / 3.0, 0, 0.25);
        }
        String ev = "%s：%s".formatted(label, tone);
        return new VocalProductionResult.BehaviorInference(
                label, round(clamp(confidence, 0.45, 0.8), 2), ev, stats.register);
    }

    /** 生成整段录音的综合结论：把"频段音色主调(第1层)"与"逐音区发声倾向(第2层)"合并成一段可读判断。 */
    public static String buildSummary(
            VocalFeatureAnalyzer.VocalFeatures features,
            List<VocalProductionResult.RegisterAnalysis> registers,
            List<VocalProductionResult.BehaviorInference> behaviors) {
        if (registers.isEmpty()) {
            return "未能从录音中检测到足够的有效音高片段，无法对发声行为做可靠推断。";
        }

        // 第1层：从频段能量占比判断整体音色主调
        String tone = dominantTone(features.bandEnergies());

        // 第2层：逐音区发声倾向
        Map<String, String> byRegister = new LinkedHashMap<>();
        for (String key : List.of("低音区", "中音区", "高音区")) {
            for (VocalProductionResult.BehaviorInference b : behaviors) {
                if (key.equals(b.register())) { byRegister.put(key, b.label()); break; }
            }
        }
        StringBuilder regions = new StringBuilder();
        if (!byRegister.isEmpty()) {
            byRegister.forEach((register, label) -> {
                if (regions.length() > 0) regions.append("、");
                regions.append(register).append(label.replaceFirst("^偏", "偏").trim());
            });
        }
        String regionClause = regions.length() == 0 ? "各音区未检出明显偏区"
                : "发声上：".concat(regions.toString());
        String scope = "结果用于演唱练习辅助，不构成医学诊断。";
        return "整体听感：".concat(tone)
                .concat("。").concat(regionClause).concat("。").concat(scope);
    }

    /** 由占比最高的感知频段推断整体音色主调（第 1 层的可读结论）。 */
    private static String dominantTone(List<VocalProductionResult.BandEnergy> bands) {
        VocalProductionResult.BandEnergy top = null;
        VocalProductionResult.BandEnergy second = null;
        for (VocalProductionResult.BandEnergy band : bands) {
            if ("低切".equals(band.name())) continue;
            if (top == null || band.ratio() > top.ratio()) {
                second = top;
                top = band;
            } else if (second == null || band.ratio() > second.ratio()) {
                second = band;
            }
        }
        if (top == null) return "各频段较均衡";
        String result = "能量集中在「%s」频段，%s".formatted(top.name(), toneShort(top));
        if (second != null && second.ratio() >= top.ratio() * 0.5) {
            result += "，其次「%s」频段，%s".formatted(second.name(), toneShort(second));
        }
        return result;
    }

    private static String toneShort(VocalProductionResult.BandEnergy band) {
        return switch (band.name()) {
            case "胸腔" -> "偏低沉浑厚、胸腔感足";
            case "温暖·鼻" -> "偏温暖饱满";
            case "中频存在感" -> "偏明亮、声音靠前";
            case "歌手共振峰" -> "带穿透力/金属感";
            case "齿音" -> "高频齿音偏亮";
            case "空气感" -> "空气感明显、开阔通透";
            default -> "占比偏高";
        };
    }

    private static boolean hasSingerFormant(VocalFeatureAnalyzer.VocalFeatures features) {
        for (VocalFeatureAnalyzer.RegisterStats stats : features.registers().values()) {
            Double sf = stats.medianSingerFormant();
            if (sf != null && sf > NORMAL_SINGER_FORMANT_DBFS) return true;
        }
        return false;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value, int digits) {
        if (!Double.isFinite(value)) return 0;
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }
}
