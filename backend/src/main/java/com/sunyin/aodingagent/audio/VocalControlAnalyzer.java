package com.sunyin.aodingagent.audio;

import com.sunyin.aodingagent.audio.VocalFeatureAnalyzer.RegisterStats;
import com.sunyin.aodingagent.audio.VocalFeatureAnalyzer.VocalFeatures;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发声控制 / 按音区倾向叙述（v3 校准版）。
 * <p>
 * 目标：像声乐老师一样按音区读出发声倾向（实/均衡/偏轻），并给出由实际音区结论<b>动态</b>
 * 拼出的整体句。判据<b>分音区</b>：
 * <ul>
 *   <li><b>低/中音区</b>用 H1-H2（闭合/气息的声学代理，低=实、高=偏气），此时有效。</li>
 *   <li><b>高音区不用 H1-H2</b>：男声 F0 一高、基波天然极强且谐波稀疏，H1-H2 饱和在高位，
 *       结实高音与偏弱高音都会高，无法区分。改用<b>发声力度</b>——高音区相对中音区的
 *       响度增量(ΔdB)，增量大=发力"顶上去"(实/有力)，增量小=力度克制(偏轻/偏混)。</li>
 * </ul>
 * 整体句由各音区的实/轻倾向统计动态生成，不再写死"以实声为主/高音混声头声处理"等固定台词。
 * <p>所有阈值均为占位，已用真实清唱素材定方向，待更多样本校准。
 */
public final class VocalControlAnalyzer {

    // —— 阈值（方向已按真实素材定，数值待细校）——
    /** 一个音区至少这么多帧才进入叙述（防止稀疏片段误判；传奇高音区仅约 24 帧即被剔除）。 */
    private static final int MIN_REGISTER_FRAMES = 30;
    /** 低音区 H1-H2(dB)：低于此=发实饱满，高于此=偏轻偏气。 */
    private static final double LOW_CHEST_DB = 2.0;
    private static final double LOW_LIGHT_DB = 8.0;
    /** 中音区 H1-H2(dB)：低于此=发实，高于此=偏轻偏气。 */
    private static final double MID_CHEST_DB = 3.0;
    /** 中音区"顶部组 − 基准组"H1-H2 增量(dB)：达到则读"随音高上升渐趋轻盈/混声增多"。 */
    private static final double MID_MIX_RISE_DB = 3.5;
    /** 高音区相对中音区响度增量(dB)：≥此=发力顶上(实/有力)，≥此档下界=适中。 */
    private static final double HIGH_STRONG_LIFT_DB = 3.2;
    private static final double HIGH_EVEN_LIFT_DB = 2.0;
    /** 齿音(5-8kHz)占整体能量超过该比例才提示（听感已明显，中性措辞）。 */
    private static final double SIBILANCE_RATIO = 0.01;

    // 权重档
    private static final int CHEST = 1;   // 实
    private static final int EVEN = 0;    // 均衡
    private static final int LIGHT = -1;  // 偏轻

    private VocalControlAnalyzer() {
        // 工具类
    }

    /** 控制结果：每音区的数值明细(供"数据"折叠) + 倾向叙述(主内容)。破音不可靠，当前不输出。 */
    public record ControlResult(
            List<VocalProductionResult.RegisterControl> registerControls,
            List<VocalProductionResult.BreakEvent> breakEvents,
            String controlNote
    ) { }

    public static ControlResult analyze(VocalFeatures features) {
        List<VocalProductionResult.RegisterControl> controls = new ArrayList<>();
        Map<String, RegisterStats> present = new LinkedHashMap<>();
        for (String key : List.of(RegisterStats.LOW, RegisterStats.MID, RegisterStats.HIGH)) {
            RegisterStats stats = features.registers().get(key);
            if (stats == null || stats.voicedFrameCount() < MIN_REGISTER_FRAMES) continue;
            controls.add(buildRegisterControl(stats));
            present.put(key, stats);
        }
        return new ControlResult(controls, List.of(), buildNote(present, controls, features));
    }

    private static VocalProductionResult.RegisterControl buildRegisterControl(RegisterStats stats) {
        List<Double> pitch = stats.pitchHz;
        int n = pitch.size();
        List<Double> sortedPitch = new ArrayList<>(pitch);
        sortedPitch.sort(Double::compareTo);
        double baseThreshold = sortedPitch.get(Math.max(0, (int) Math.round((n - 1) * 0.50)));
        double topThreshold = sortedPitch.get(Math.min(n - 1, (int) Math.round((n - 1) * 0.75)));

        List<Double> baseH = new ArrayList<>(), topH = new ArrayList<>();
        List<Double> baseS = new ArrayList<>(), topS = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double p = pitch.get(i);
            if (Double.isNaN(stats.h1h2.get(i))) continue;
            if (p <= baseThreshold) {
                baseH.add(stats.h1h2.get(i)); baseS.add(stats.singerFormant.get(i));
            } else if (p >= topThreshold) {
                topH.add(stats.h1h2.get(i)); topS.add(stats.singerFormant.get(i));
            }
        }
        boolean enoughTop = topH.size() >= 12;
        double p50 = sortedPitch.get((int) Math.round((n - 1) * 0.50));
        double p75 = sortedPitch.get((int) Math.round((n - 1) * 0.75));
        return new VocalProductionResult.RegisterControl(
                stats.register,
                baseH.size(), topH.size(),
                round1(p50), enoughTop ? round1(p75) : null,
                round1(median(baseH)), enoughTop ? round1(median(topH)) : null,
                null, null,
                round1(stats.medianDbfs()), null,
                round1(stats.medianSingerFormant()), enoughTop ? round1(median(topS)) : null,
                null,
                null,
                0);
    }

    // —— 单一音区的档位与倾向句 ——
    private record RegionRead(int weight, String phrase) { }

    /** 生成按音区倾向叙述 + 动态整体句 +（可选）唇齿音说明。 */
    private static String buildNote(
            Map<String, RegisterStats> present,
            List<VocalProductionResult.RegisterControl> controls,
            VocalFeatures features) {
        if (present.isEmpty()) {
            return "未能从录音中检测到足够的有效音高片段，无法做发声倾向推断。仅供练习参考，非诊断。";
        }
        Map<String, RegisterStats> statsByReg = new LinkedHashMap<>();
        for (VocalProductionResult.RegisterControl c : controls) {
            statsByReg.put(c.register(), present.get(c.register()));
        }
        List<String> phrases = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        RegionRead low = readLow(statsByReg.get(RegisterStats.LOW));
        if (low != null) { phrases.add(low.phrase); weights.add(low.weight); }
        RegionRead mid = readMid(statsByReg.get(RegisterStats.MID), statsByReg.get(RegisterStats.LOW));
        if (mid != null) { phrases.add(mid.phrase); weights.add(mid.weight); }
        RegionRead high = readHigh(statsByReg.get(RegisterStats.HIGH), statsByReg.get(RegisterStats.MID));
        if (high != null) { phrases.add(high.phrase); weights.add(high.weight); }

        StringBuilder sb = new StringBuilder();
        if (!phrases.isEmpty()) sb.append(String.join(" ", phrases)).append(' ');
        sb.append(overallRead(weights, high != null));
        // 唇齿音：全曲级量，仅当占比超阈值才提示（中性，供咬字参考）
        double sib = features.overallSibilanceRatio();
        if (sib > SIBILANCE_RATIO) {
            sb.append(' ').append("唇齿音方面：齿音(5-8kHz)能量约占整体 ")
                    .append(String.format("%.1f%%", sib * 100))
                    .append("，/s/、/sh/、/x/ 等唇齿音成分较明显——占比越高通常意味着齿音越重，可能影响音色的柔和度与听感平衡，建议适度收敛，供咬字吐字参考。");
        }
        sb.append(' ').append("以上为基于本段声学特征的倾向推断（阈值待校准），仅供演唱练习参考，非诊断。");
        return sb.toString();
    }

    private static RegionRead readLow(RegisterStats s) {
        if (s == null) return null;
        double hh = s.medianH1h2() == null ? Double.NaN : s.medianH1h2();
        if (hh <= LOW_CHEST_DB) return new RegionRead(CHEST,
                "低音区发声较实、胸声感较足，音色偏沉实。");
        if (hh <= LOW_LIGHT_DB) return new RegionRead(EVEN,
                "低音区发声均衡、偏实，不算虚也不特别厚重。");
        return new RegionRead(LIGHT,
                "低音区发声偏轻、偏气息/空气支撑，声线不够沉实。");
    }

    private static RegionRead readMid(RegisterStats s, RegisterStats lowRef) {
        if (s == null) return null;
        double hh = s.medianH1h2() == null ? Double.NaN : s.medianH1h2();
        // 中音区"向高处的混声过渡"：需要基准(低半段)偏实、且随音高 H1-H2 明显抬升(变轻/混)
        // 用该音区自己的 P50 为基准、P75 为顶部（RegisterControl 已算好 baseline/top）。
        // 这里沿用中位值做兜底；精确过渡量依赖 buildRegisterControl 的 base/top，重算一次。
        double[] bt = baseTopH1h2(s);
        double base = bt[0], top = bt[1];
        if (!Double.isNaN(base) && !Double.isNaN(top)
                && base <= MID_CHEST_DB && (top - base) >= MID_MIX_RISE_DB) {
            return new RegionRead(EVEN,
                    "中音区发声偏实，随音高上升渐趋轻盈、混声/空气成分增多，呈向高处的混合过渡。");
        }
        if (hh <= MID_CHEST_DB) return new RegionRead(CHEST,
                "中音区发声较实、偏平稳，实声感持续。");
        return new RegionRead(LIGHT,
                "中音区发声偏轻、偏空气/气息支撑。");
    }

    private static RegionRead readHigh(RegisterStats s, RegisterStats midRef) {
        if (s == null) return null;
        double highDbfs = s.medianDbfs() == null ? Double.NaN : s.medianDbfs();
        double midDbfs = midRef != null && midRef.medianDbfs() != null ? midRef.medianDbfs() : Double.NaN;
        double lift = (!Double.isNaN(highDbfs) && !Double.isNaN(midDbfs)) ? highDbfs - midDbfs : Double.NaN;

        // 力度增量：有力=相对中音区明显加力顶上去；克制=几乎不加力(偏轻/偏混上探)。
        // 响度增量对录音整体增益不敏感，比绝对响度更稳（不同素材音量可能不同）。
        if (!Double.isNaN(lift) && lift >= HIGH_STRONG_LIFT_DB) {
            double sg = s.medianSingerFormant() == null ? Double.NaN : s.medianSingerFormant();
            String ring = (!Double.isNaN(sg) && sg > -24)
                    ? "，并带着较明显的歌手共振峰/亮芯(偏亮、有穿透)"
                    : "";
            return new RegionRead(CHEST,
                    "高音区发声偏有力、较结实（相对中音区力度明显增大，像以更强的气息把实声/胸声顶上去）" + ring + "。");
        }
        if (!Double.isNaN(lift) && lift >= HIGH_EVEN_LIFT_DB) {
            return new RegionRead(EVEN,
                    "高音区发声适中、以偏混声的方式过渡，力度比中音区略有增强。");
        }
        return new RegionRead(LIGHT,
                "高音区力度相对克制（相对中音区未明显加力），以偏混声/头声、偏轻的方式上探。");
    }

    /** 返回该音区 低半段(P50)/高半段(P75) 的 H1-H2 中位。 */
    private static double[] baseTopH1h2(RegisterStats s) {
        List<Double> pitch = s.pitchHz;
        int n = pitch.size();
        if (n == 0) return new double[]{Double.NaN, Double.NaN};
        List<Double> sortedPitch = new ArrayList<>(pitch);
        sortedPitch.sort(Double::compareTo);
        double baseT = sortedPitch.get((int) Math.round((n - 1) * 0.50));
        double topT = sortedPitch.get(Math.min(n - 1, (int) Math.round((n - 1) * 0.75)));
        List<Double> b = new ArrayList<>(), t = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(s.h1h2.get(i))) continue;
            double p = pitch.get(i);
            if (p <= baseT) b.add(s.h1h2.get(i));
            else if (p >= topT) t.add(s.h1h2.get(i));
        }
        Double bm = median(b), tm = t.size() >= 12 ? median(t) : null;
        return new double[]{bm == null ? Double.NaN : bm, tm == null ? Double.NaN : tm};
    }

    /** 由各音区档位动态生成整体句（无固定台词）。 */
    private static String overallRead(List<Integer> weights, boolean hasHigh) {
        if (weights.isEmpty()) return "";
        if (weights.size() == 1) return "（基于录音中该音区的特征）。";
        int chest = 0, light = 0, even = 0;
        for (int w : weights) {
            if (w == CHEST) chest++;
            else if (w == LIGHT) light++;
            else even++;
        }
        if (chest == 0 && light > 0) {
            return "整体以轻、柔的偏弱处理为主：力度相对克制、声线偏清透，靠气息与共鸣支撑。";
        }
        boolean highStrong = hasHigh && !weights.isEmpty() && weights.get(weights.size() - 1) == CHEST;
        if (light == 0 && highStrong) {
            return "整体发声较扎实：以实声/胸声为基底并贯通到较高音区，力度较充沛、能顶得住。";
        }
        if (light == 0) {
            return "整体发声以实声/胸声为基底、较扎实，力度较充沛。";
        }
        return "整体以实声为基底、力度均衡，随音区与乐句在实声与偏轻/混声之间自然切换。";
    }

    private static Double median(List<Double> values) {
        if (values.isEmpty()) return null;
        List<Double> copy = new ArrayList<>(values);
        copy.sort(Double::compareTo);
        int mid = copy.size() / 2;
        return copy.size() % 2 == 1 ? copy.get(mid) : (copy.get(mid - 1) + copy.get(mid)) / 2.0;
    }

    private static Double round1(Double value) {
        if (value == null) return null;
        return Math.round(value * 10) / 10.0;
    }
}
