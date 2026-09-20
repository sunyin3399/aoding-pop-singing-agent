package com.sunyin.aodingagent.audio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人声"发声机制"特征提取器。
 * <p>
 * 它逐帧计算与音高相关的频谱指标，再按低/中/高音区聚合，供 {@link VocalBehaviorClassifier}
 * 推断发声行为。核心设计原则：<b>频段要相对音高(F0)看</b>——H1-H2、频谱斜率等都在 F0 的
 * 位置上计算，避免"男声唱到高音后 200-300Hz 天然没能量"这类绝对频段的误判。
 *
 * <p>各指标含义（详见项目内 VOCAL_PRODUCTION_REFERENCE.md）：
 * <ul>
 *   <li>H1-H2：第一、第二谐波幅值差(dB)。越大越偏气声，越小越偏用力。</li>
 *   <li>频谱斜率：dB/octave，越陡高频掉得越快、越偏假/弱。</li>
 *   <li>歌手共振峰：2.5-3.5kHz 能量，代表存在感/穿透力。</li>
 *   <li>空气感：8-15kHz 能量占比，气声/漏气时偏高。</li>
 *   <li>齿音：5-8kHz 能量占比及其爆发段，对应 /s/ /sh/ /x/ 摩擦声。</li>
 *   <li>鼻音倾向：200-400Hz 相对 80-200Hz 的能量比（简化启发式，低置信）。</li>
 * </ul>
 *
 * <p><b>发声门限</b>：输入常是带伴奏的录音。只有同时满足
 * (1) 有可靠基频、(2) 帧内含明显高频成分(>1.5kHz 峰值非纯低频伴奏/器乐)、
 * (3) 属于 ≥ 一定长度的连续有声段 的帧，才被计入音区聚合与发声推断；
 * 前奏/尾奏、静音、纯低频器乐帧会被挡在"人声发声"分析之外。
 */
public final class VocalFeatureAnalyzer {

    /** 静音门限（与 AudioAnalysisService 一致）。 */
    static final double SILENCE_RMS = 0.01;
    /** 音高追踪范围。 */
    static final double MIN_PITCH_HZ = 70.0;
    static final double MAX_PITCH_HZ = 1000.0;

    /** 音区划分边界（Hz）。男声换声区约 E4-F4(330-349Hz)：
     *  低 &lt; LOW(220≈A3) &lt; 中 &lt; MID(349≈F4) &lt; 高。F4/G4(349-392) 是典型男声高音，须归高音区。
     *  注：固定边界按男声校准；换到女声/童声需上移。 */
    static final double LOW_REGION_UP_TO_HZ = 220.0;
    static final double MID_REGION_UP_TO_HZ = 349.0;

    // —— 破音检测阈值（占位，待样本校准）——
    /** 相邻帧音高降到 ≤ 该比值(≈近一个八度)才视为可能的断裂/次谐波塌陷。 */
    private static final double BREAK_HALVE_RATIO = 0.62;
    /** 塌陷后回升到 ≥ 塌陷值×该比值，才判定为"破一下又顶回来"。 */
    private static final double BREAK_RECOVER_RATIO = 1.6;
    /** 回升需发生在塌陷后多少帧以内（约 0.1s @16ms 帧）。 */
    private static final int BREAK_RETURN_FRAMES = 6;

    /** 发声门限：1.5kHz 以上相对基频峰值需达到的比例，过滤纯低频伴奏/器乐帧。 */
    private static final double HIGH_CONTENT_MIN_RATIO = 0.03;
    /** 发声门限：一个"有声段"至少要这么多连续帧（约 0.28s @46ms 帧），丢弃孤立/瞬时帧。 */
    private static final int MIN_SUNG_RUN_FRAMES = 6;

    // 感知频段定义（混音/EQ 视角）
    private record Band(String name, double start, double end) { }
    private static final List<Band> BANDS = List.of(
            new Band("低切", 0, 80),
            new Band("胸腔", 80, 200),
            new Band("温暖·鼻", 200, 400),
            new Band("中频存在感", 400, 2000),
            new Band("歌手共振峰", 2500, 3500),
            new Band("齿音", 5000, 8000),
            new Band("空气感", 8000, 15000)
    );

    /** 单帧候选（通过高频成分门限、有可靠基频）的暂存，先收集、判定连续段后再聚合。 */
    private record VoicedFrame(
            double timeSeconds, double f0, double h1h2, double slope,
            double singerDbfs, double airRatio, double sibilanceRatio, double nasalRatio, double rmsDbfs
    ) { }

    /** 一次检测到的"疑似破音/瞬间断裂"（有声段内 F0 上跳后很快回落）。 */
    record BreakOccurrence(double timeSeconds, double pitchHz) { }

    private VocalFeatureAnalyzer() {
        // 工具类：不实例化
    }

    /**
     * 分析整段采样，返回频段分布与按音区聚合的发声指标。
     *
     * @param samples    单声道采样（-1..1）
     * @param sampleRate 采样率，需覆盖到 16kHz（Nyquist 8kHz）以上以支持齿音/空气感频段
     */
    public static VocalFeatures analyze(double[] samples, float sampleRate) {
        if (samples.length == 0) {
            throw new IllegalArgumentException("音频中没有可分析的采样数据");
        }
        // 静音帧也统计频段能量（摩擦音等无声调信号仍属于齿音信号），但有音高的帧才进音区聚合
        double[] bandPower = new double[BANDS.size()];
        double totalPower = 0;
        double normSum = 0;
        int analyzedFrames = 0;
        int sibilanceBursts = 0;
        double sibilancePeakRatio = 0;

        // 收集"候选歌唱帧"：有可靠基频且含高频成分（非纯低频伴奏）
        List<VoicedFrame> candidates = new ArrayList<>();

        Map<String, RegisterStats> registers = new LinkedHashMap<>();
        registers.put(RegisterStats.LOW, new RegisterStats(RegisterStats.LOW));
        registers.put(RegisterStats.MID, new RegisterStats(RegisterStats.MID));
        registers.put(RegisterStats.HIGH, new RegisterStats(RegisterStats.HIGH));

        int frameSize = Math.max(1024, Integer.highestOneBit((int) (sampleRate * 0.046)));
        int hopSize = frameSize / 2;
        int minLag = Math.max(1, (int) (sampleRate / MAX_PITCH_HZ));
        int maxLag = Math.min(frameSize / 2, (int) (sampleRate / MIN_PITCH_HZ));
        int fftSize = frameSize;
        double[] window = hannWindow(fftSize);

        for (int start = 0; start + frameSize <= samples.length; start += hopSize) {
            double mean = mean(samples, start, start + frameSize);
            double energy = 0;
            for (int i = start; i < start + frameSize; i++) {
                double centered = samples[i] - mean;
                energy += centered * centered;
            }
            double rms = Math.sqrt(energy / frameSize);
            if (rms < SILENCE_RMS) {
                continue; // 静音帧不参与统计
            }

            double[] real = new double[fftSize];
            double[] imaginary = new double[fftSize];
            double windowSquareSum = 0;
            for (int i = 0; i < frameSize; i++) {
                real[i] = (samples[start + i] - mean) * window[i];
                windowSquareSum += window[i] * window[i];
            }
            Fft.fft(real, imaginary);
            analyzedFrames++;
            normSum += fftSize * windowSquareSum;

            // 各频段能量
            double[] frameBandPower = new double[BANDS.size()];
            double frameTotal = 0;
            int bins = fftSize / 2 + 1;
            double[] magnitude = new double[bins];
            for (int bin = 0; bin < bins; bin++) {
                double power = (real[bin] * real[bin] + imaginary[bin] * imaginary[bin]);
                if (bin > 0 && bin < fftSize / 2) power *= 2;
                magnitude[bin] = Math.sqrt(power);
                double freq = bin * sampleRate / fftSize;
                for (int b = 0; b < BANDS.size(); b++) {
                    Band band = BANDS.get(b);
                    if (freq >= band.start && freq < band.end) {
                        frameBandPower[b] += power;
                        break;
                    }
                }
                frameTotal += power;
            }
            for (int b = 0; b < BANDS.size(); b++) {
                bandPower[b] += frameBandPower[b];
            }
            totalPower += frameTotal;

            // 齿音爆发检测：5-8kHz 占比冲高，且帧不静音（清擦音无音高，用占比识别）
            double sibRatio = frameTotal <= 0 ? 0 : frameBandPower[bandIndex("齿音")] / frameTotal;
            if (sibRatio > 0.3) {
                sibilanceBursts++;
                sibilancePeakRatio = Math.max(sibilancePeakRatio, sibRatio);
            }

            // 音高追踪（自相关）
            Double f0 = estimateF0(samples, start, frameSize, minLag, maxLag, mean, sampleRate);
            if (f0 == null) {
                continue; // 无可靠音高的清辅音/噪声帧只进频段统计
            }

            // 发声门限：帧内必须有明显高频成分，否则视为纯低频伴奏/器乐帧而非人声。
            double f0Peak = peakMagnitudeNear(magnitude, f0, sampleRate, fftSize);
            double highPeak = magnitudePeakInBand(magnitude, sampleRate, fftSize, 1500, 6000);
            if (f0Peak > 1e-12 && highPeak < f0Peak * HIGH_CONTENT_MIN_RATIO) {
                continue;
            }

            // 相对音高的发声指标
            Double h1h2 = h1h2Db(magnitude, f0, sampleRate, fftSize);
            double slope = spectralSlopeDbPerOctave(magnitude, sampleRate, fftSize);
            double singerDbfs = 20 * Math.log10(magnitudePeakInBand(magnitude, sampleRate, fftSize, 2500, 3500));
            double airRatio = frameTotal <= 0 ? 0 : frameBandPower[bandIndex("空气感")] / frameTotal;
            double nasalRatio = frameBandPower[bandIndex("温暖·鼻")] / Math.max(frameBandPower[bandIndex("胸腔")], 1e-12);
            double rmsDbfs = 20 * Math.log10(rms);

            candidates.add(new VoicedFrame((double) start / sampleRate, f0,
                    h1h2 == null ? Double.NaN : h1h2, slope, singerDbfs, airRatio, sibRatio, nasalRatio, rmsDbfs));
        }

        // 连续有声段判定：把时间相邻的候选帧连成段，只保留长度足够的段（丢弃孤立/瞬时帧）。
        List<List<VoicedFrame>> runs = groupIntoRuns(candidates, frameSize / sampleRate);
        for (List<VoicedFrame> run : runs) {
            if (run.size() < MIN_SUNG_RUN_FRAMES) continue;
            for (VoicedFrame frame : run) {
                if (Double.isNaN(frame.h1h2())) continue;
                RegisterStats stats = registers.get(classifyRegister(frame.f0()));
                stats.pitchHz.add(frame.f0());
                stats.h1h2.add(frame.h1h2());
                stats.slope.add(frame.slope());
                stats.dbfs.add(frame.rmsDbfs());
                stats.singerFormant.add(frame.singerDbfs());
                stats.airRatio.add(frame.airRatio());
                stats.sibilanceRatio.add(frame.sibilanceRatio());
                stats.nasalRatio.add(frame.nasalRatio());
            }
        }

        // 破音检测：在每个足够长的连续有声段内，找"F0 突然上跳后很快塌回"的疑似断裂。
        List<BreakOccurrence> breaks = new ArrayList<>();
        for (List<VoicedFrame> run : runs) {
            if (run.size() < MIN_SUNG_RUN_FRAMES) continue;
            detectBreaks(run, breaks);
        }

        // 频段分布（第 1 层）
        List<VocalProductionResult.BandEnergy> bandEnergies = new ArrayList<>(BANDS.size());
        for (int b = 0; b < BANDS.size(); b++) {
            Band band = BANDS.get(b);
            double ratio = totalPower <= 0 ? 0 : bandPower[b] / totalPower;
            double dbfs = analyzedFrames == 0 || normSum <= 0
                    ? -120.0
                    : 20 * Math.log10(Math.sqrt(bandPower[b] / normSum));
            bandEnergies.add(new VocalProductionResult.BandEnergy(
                    band.name(), band.start(), band.end(),
                    round(ratio, 4), round(dbfs, 2)));
        }
        double overallSibilance = totalPower <= 0 ? 0 : bandPower[bandIndex("齿音")] / totalPower;
        return new VocalFeatures(
                List.copyOf(bandEnergies),
                Map.copyOf(registers),
                round(overallSibilance, 4),
                sibilanceBursts,
                round(sibilancePeakRatio, 4),
                List.copyOf(breaks));
    }

    /** 把按时间有序的候选帧连成段；同一段内相邻帧时间差不超过一帧 hop 的 2 倍。 */
    private static List<List<VoicedFrame>> groupIntoRuns(List<VoicedFrame> frames, double hopSeconds) {
        List<List<VoicedFrame>> runs = new ArrayList<>();
        List<VoicedFrame> current = new ArrayList<>();
        for (VoicedFrame frame : frames) {
            if (!current.isEmpty()
                    && frame.timeSeconds() - current.getLast().timeSeconds() > hopSeconds * 2) {
                runs.add(current);
                current = new ArrayList<>();
            }
            current.add(frame);
        }
        if (!current.isEmpty()) runs.add(current);
        return runs;
    }

    /** 在一个连续有声段内检测"疑似破音/断裂"。
     * 真实破音常表现为瞬间的次谐波/音高近八度塌陷（而非旋律跳进），因此：
     * 只认"相邻帧音高突降到约一半（ratio≤0.62）后，几帧内又明显回升"的形态；
     * 正常歌曲里的旋律性上行/倚音/滑音不会触发，从而避免把旋律当破音。 */
    private static void detectBreaks(List<VoicedFrame> run, List<BreakOccurrence> out) {
        for (int i = 0; i + 1 < run.size(); i++) {
            double a = run.get(i).f0();
            double b = run.get(i + 1).f0();
            double ratio = b / a;
            if (ratio > BREAK_HALVE_RATIO) continue; // 非近八度塌陷(降半或更多)，跳过
            // 塌陷后需在短窗口内明显回升，才更像"破一下又顶回来"
            double recoverFloor = b * BREAK_RECOVER_RATIO;
            int end = Math.min(run.size(), i + 2 + BREAK_RETURN_FRAMES);
            boolean recovered = false;
            for (int k = i + 2; k < end; k++) {
                if (run.get(k).f0() >= recoverFloor) { recovered = true; break; }
            }
            if (recovered) {
                out.add(new BreakOccurrence(run.get(i).timeSeconds(), a));
                i += 1; // 跳过该塌陷，避免同一处重复计数
            }
        }
    }

    /** 结果载体：频段分布 + 按音区聚合 + 齿音整体指标 + 疑似破音。 */
    public record VocalFeatures(
            List<VocalProductionResult.BandEnergy> bandEnergies,
            Map<String, RegisterStats> registers,
            double overallSibilanceRatio,
            int sibilanceBurstCount,
            double sibilancePeakRatio,
            List<BreakOccurrence> breaks
    ) { }

    /** 某一个音区内的发声指标样本集合，提供中位数访问。 */
    public static final class RegisterStats {
        static final String LOW = "低音区";
        static final String MID = "中音区";
        static final String HIGH = "高音区";

        public final String register;
        final List<Double> pitchHz = new ArrayList<>();
        final List<Double> h1h2 = new ArrayList<>();
        final List<Double> slope = new ArrayList<>();
        final List<Double> dbfs = new ArrayList<>();
        final List<Double> singerFormant = new ArrayList<>();
        final List<Double> airRatio = new ArrayList<>();
        final List<Double> sibilanceRatio = new ArrayList<>();
        final List<Double> nasalRatio = new ArrayList<>();

        RegisterStats(String register) {
            this.register = register;
        }

        int voicedFrameCount() { return pitchHz.size(); }

        Double medianPitchHz() { return median(pitchHz); }
        Double minPitchHz() { return percentile(pitchHz, 0.05); }
        Double maxPitchHz() { return percentile(pitchHz, 0.95); }
        Double medianH1h2() { return median(h1h2); }
        Double medianSlope() { return median(slope); }
        Double medianDbfs() { return median(dbfs); }
        Double medianSingerFormant() { return median(singerFormant); }
        Double medianAirRatio() { return median(airRatio); }
        Double medianSibilanceRatio() { return median(sibilanceRatio); }
        Double medianNasalRatio() { return median(nasalRatio); }

        /** 该音区音高稳定度（cents 标准差，越大越不稳）。 */
        Double pitchStabilityCents() {
            Double med = medianPitchHz();
            if (med == null || pitchHz.size() < 2) return null;
            double sum = 0;
            for (double pitch : pitchHz) {
                double cents = 1200 * Math.log(pitch / med) / Math.log(2);
                sum += cents * cents;
            }
            return Math.sqrt(sum / pitchHz.size());
        }
    }

    private static String classifyRegister(double f0) {
        if (f0 < LOW_REGION_UP_TO_HZ) return RegisterStats.LOW;
        if (f0 < MID_REGION_UP_TO_HZ) return RegisterStats.MID;
        return RegisterStats.HIGH;
    }

    private static int bandIndex(String name) {
        for (int i = 0; i < BANDS.size(); i++) {
            if (BANDS.get(i).name().equals(name)) return i;
        }
        throw new IllegalArgumentException("未知频段: " + name);
    }

    private static double[] hannWindow(int size) {
        double[] window = new double[size];
        for (int i = 0; i < size; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (size - 1));
        }
        return window;
    }

    private static double mean(double[] samples, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += samples[i];
        return sum / (to - from);
    }

    /** 自相关法估计基频；无可靠周期返回 null。 */
    private static Double estimateF0(
            double[] samples, int start, int frameSize, int minLag, int maxLag, double mean, float sampleRate) {
        double[] correlations = new double[maxLag + 1];
        for (int lag = minLag; lag <= maxLag; lag++) {
            double numerator = 0;
            double leftEnergy = 0;
            double rightEnergy = 0;
            for (int i = 0; i < frameSize - lag; i++) {
                double left = samples[start + i] - mean;
                double right = samples[start + i + lag] - mean;
                numerator += left * right;
                leftEnergy += left * left;
                rightEnergy += right * right;
            }
            double denominator = Math.sqrt(leftEnergy * rightEnergy);
            correlations[lag] = denominator == 0 ? 0 : numerator / denominator;
        }
        for (int lag = minLag + 1; lag < maxLag; lag++) {
            if (correlations[lag] >= 0.65
                    && correlations[lag] >= correlations[lag - 1]
                    && correlations[lag] > correlations[lag + 1]) {
                return (double) sampleRate / lag;
            }
        }
        return null;
    }

    /** 计算 H1-H2：第一、第二谐波峰值幅值差(dB)。F0 落在 nyquist 外或缺失时返回 null。 */
    private static Double h1h2Db(double[] magnitude, double f0, float sampleRate, int fftSize) {
        double nyquist = sampleRate / 2.0;
        if (2 * f0 > nyquist) return null;
        double h1 = peakMagnitudeNear(magnitude, f0, sampleRate, fftSize);
        double h2 = peakMagnitudeNear(magnitude, 2 * f0, sampleRate, fftSize);
        if (h1 <= 1e-12 || h2 <= 1e-12) return null;
        return 20 * Math.log10(h1 / h2);
    }

    /** 在目标频率附近 ±15% 内找最大幅值（比直接取单个 bin 更稳，抗谐波泄漏）。 */
    private static double peakMagnitudeNear(double[] magnitude, double targetHz, float sampleRate, int fftSize) {
        int lowBin = Math.max(1, (int) ((targetHz * 0.85) / sampleRate * fftSize));
        int highBin = Math.min(magnitude.length - 1, (int) ((targetHz * 1.15) / sampleRate * fftSize));
        double peak = 0;
        for (int bin = lowBin; bin <= highBin; bin++) {
            peak = Math.max(peak, magnitude[bin]);
        }
        return peak;
    }

    /** 计算某频带内的最大幅值（用于歌手共振峰 dB、高频成分门限）。 */
    private static double magnitudePeakInBand(
            double[] magnitude, float sampleRate, int fftSize, double startHz, double endHz) {
        int lowBin = Math.max(1, (int) (startHz / sampleRate * fftSize));
        int highBin = Math.min(magnitude.length - 1, (int) (endHz / sampleRate * fftSize));
        double peak = 0;
        for (int bin = lowBin; bin <= highBin; bin++) {
            peak = Math.max(peak, magnitude[bin]);
        }
        return peak;
    }

    /** 频谱斜率（dB/octave）：对 (log2 f, dB) 在 150Hz..4kHz 区间做线性回归。 */
    private static double spectralSlopeDbPerOctave(double[] magnitude, float sampleRate, int fftSize) {
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        int count = 0;
        for (int bin = 1; bin < magnitude.length; bin++) {
            double freq = bin * sampleRate / fftSize;
            if (freq < 150 || freq > 4000) continue;
            double x = Math.log(freq) / Math.log(2);   // octave
            double y = 20 * Math.log10(Math.max(magnitude[bin], 1e-12));
            sumX += x; sumY += y; sumXY += x * y; sumXX += x * x;
            count++;
        }
        if (count < 3) return 0;
        double denominator = count * sumXX - sumX * sumX;
        if (Math.abs(denominator) < 1e-12) return 0;
        return (count * sumXY - sumX * sumY) / denominator; // dB/octave
    }

    private static Double median(List<Double> values) {
        if (values.isEmpty()) return null;
        return percentile(values, 0.5);
    }

    private static Double percentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) return null;
        List<Double> copy = new ArrayList<>(sorted);
        copy.sort(Double::compareTo);
        int index = (int) Math.round((copy.size() - 1) * percentile);
        return round(copy.get(index), 2);
    }

    private static double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }
}
