package com.sunyin.aodingagent.audio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从整段音高轨迹中挑选最多三个值得重点查看的高音片段，并生成可解释的观察和推测。
 * <p>
 * 它先根据用户自己的稳健音域计算“相对高音”阈值，再把时间上相邻的高音点聚成片段，挑选
 * 分数较高且彼此不重叠的窗口。输出会严格区分：直接从数据算出的 observation（观察）和
 * 根据多个指标推测的 inference（推测），避免把“可能偏虚”等判断说成确定事实。
 */
final class HighNoteHighlightAnalyzer {
    private static final double WINDOW_SECONDS = 5;
    private static final double CLUSTER_GAP_SECONDS = 0.4;

    /** 根据当前录音自己的音域确定相对高音，而不是给所有人使用同一个固定音高。 */
    Double highNoteThresholdMidi(double robustLow, double robustHigh) {
        double span = Math.max(0, robustHigh - robustLow);
        return span < 5 ? robustHigh - 1 : robustLow + span * 0.8;
    }

    /**
     * 查找高音候选片段，并为每个片段计算连续性、音高变化和频谱特征。
     */
    List<AudioPitchTrackResult.HighNoteHighlight> analyze(
            List<AudioPitchTrackResult.PitchPoint> points,
            double[] samples,
            float sampleRate,
            double durationSeconds,
            double robustLow,
            double robustHigh
    ) {
        double threshold = highNoteThresholdMidi(robustLow, robustHigh);
        List<List<AudioPitchTrackResult.PitchPoint>> clusters = cluster(points.stream()
                .filter(point -> point.voiced() && point.midi() != null && point.midi() >= threshold)
                .toList());
        List<Candidate> candidates = clusters.stream()
                .filter(cluster -> cluster.size() >= 3)
                .map(cluster -> new Candidate(cluster,
                        cluster.stream().mapToDouble(AudioPitchTrackResult.PitchPoint::midi).average().orElse(0)
                                + Math.min(2, cluster.size() / 20.0)))
                .sorted(Comparator.comparingDouble(Candidate::score).reversed())
                .toList();
        // 相距不足 3 秒的候选通常属于同一个高音区域，只保留得分更高的一段。
        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : candidates) {
            double center = candidate.center();
            if (selected.stream().anyMatch(existing -> Math.abs(existing.center() - center) < 3)) continue;
            selected.add(candidate);
            if (selected.size() == 3) break;
        }
        return selected.stream().sorted(Comparator.comparingDouble(Candidate::center))
                .map(candidate -> analyzeWindow(candidate, points, samples, sampleRate, durationSeconds, threshold))
                .toList();
    }

    private List<List<AudioPitchTrackResult.PitchPoint>> cluster(List<AudioPitchTrackResult.PitchPoint> points) {
        List<List<AudioPitchTrackResult.PitchPoint>> clusters = new ArrayList<>();
        List<AudioPitchTrackResult.PitchPoint> current = new ArrayList<>();
        for (AudioPitchTrackResult.PitchPoint point : points) {
            if (!current.isEmpty()
                    && point.timeSeconds() - current.getLast().timeSeconds() > CLUSTER_GAP_SECONDS) {
                clusters.add(current);
                current = new ArrayList<>();
            }
            current.add(point);
        }
        if (!current.isEmpty()) clusters.add(current);
        return clusters;
    }

    private AudioPitchTrackResult.HighNoteHighlight analyzeWindow(
            Candidate candidate,
            List<AudioPitchTrackResult.PitchPoint> allPoints,
            double[] samples,
            float sampleRate,
            double duration,
            double highThreshold
    ) {
        double start = Math.max(0, Math.min(duration - WINDOW_SECONDS, candidate.center() - WINDOW_SECONDS / 2));
        double end = Math.min(duration, start + WINDOW_SECONDS);
        List<AudioPitchTrackResult.PitchPoint> points = allPoints.stream()
                .filter(point -> point.timeSeconds() >= start && point.timeSeconds() <= end)
                .toList();
        List<AudioPitchTrackResult.PitchPoint> voiced = points.stream()
                .filter(point -> point.voiced() && point.midi() != null).toList();
        // “代表性音”只在该窗口内真正≥高音阈的歌唱帧里取，而不是整窗(含低频伴奏)的众数——
        // 否则窗口被拉宽后，周围大量低频伴奏帧会把代表音拉成 F2/F#2 这类假“低高音”。
        List<AudioPitchTrackResult.PitchPoint> highVoiced = voiced.stream()
                .filter(point -> point.midi() >= highThreshold).toList();
        int representative = representativeMidi(highVoiced.isEmpty() ? voiced : highVoiced);
        List<Double> offsets = voiced.stream().map(point -> (point.midi() - representative) * 100).sorted().toList();
        List<Double> changes = adjacentChanges(voiced);
        double belowRatio = voiced.isEmpty() ? 0 : voiced.stream()
                .filter(point -> point.midi() < representative - 0.3).count() / (double) voiced.size();
        double longest = longestRun(voiced);
        int interruptions = interruptions(points);
        Spectrum spectrum = spectrum(samples, sampleRate, start, end, representative);
        Double stability = changes.isEmpty() ? null : round(percentile(changes, .5), 1);
        Double maxJump = changes.isEmpty() ? null : round(changes.stream().mapToDouble(Double::doubleValue).max().orElse(0), 1);
        Double medianOffset = offsets.isEmpty() ? null : round(percentile(offsets, .5), 1);
        List<AudioPitchTrackResult.HighlightObservation> observations = new ArrayList<>();
        observations.add(new AudioPitchTrackResult.HighlightObservation(
                "长音连续性", "%.1f 秒".formatted(longest), "窗口内最长连续可靠音高"));
        if (stability != null) observations.add(new AudioPitchTrackResult.HighlightObservation(
                "音高波动", "%.1f cents".formatted(stability), "相邻可靠点变化中位数"));
        List<AudioPitchTrackResult.HighlightInference> inferences = infer(
                medianOffset, belowRatio, stability, maxJump, longest, interruptions, spectrum);
        return new AudioPitchTrackResult.HighNoteHighlight(
                round(start, 2), round(end, 2), noteName(representative), (double) representative,
                medianOffset, round(belowRatio, 3), stability, maxJump, round(longest, 2), interruptions,
                spectrum.lowRatio, spectrum.midRatio, spectrum.highRatio, spectrum.centroid, spectrum.harmonicBalance,
                List.copyOf(observations), inferences);
    }

    /**
     * 只有多个指标同时满足条件时才生成推测，并附带置信等级、证据和练习建议。
     */
    private List<AudioPitchTrackResult.HighlightInference> infer(
            Double medianOffset, double belowRatio, Double stability, Double maxJump,
            double longest, int interruptions, Spectrum spectrum) {
        List<AudioPitchTrackResult.HighlightInference> result = new ArrayList<>();
        if (longest >= 1.5 && stability != null && stability <= 28 && interruptions <= 1) {
            result.add(inference("STABLE_HIGH_NOTE", "高音长音较稳定", "HIGH",
                    "最长连续 %.1f 秒，相邻变化中位数 %.1f cents".formatted(longest, stability),
                    "保留当前气息与共鸣感觉，作为后续练习参照。"));
        }
        if (medianOffset != null && medianOffset <= -25 && belowRatio >= .55 && longest >= .8) {
            result.add(inference("POSSIBLE_REACHING_DIFFICULTY", "可能存在高音到位困难", "MEDIUM",
                    "相对推测主要音中位偏差 %.1f cents，%.0f%% 可靠点持续偏低".formatted(medianOffset, belowRatio * 100),
                    "降低音量练习滑音接近该音，避免用力顶高；结合原音确认。"));
        }
        if (maxJump != null && maxJump >= 300 && stability != null && stability <= 80 && interruptions > 0) {
            result.add(inference("POSSIBLE_VOICE_BREAK", "可能出现瞬时失稳", "MEDIUM",
                    "短时跳变 %.0f cents，并伴随 %d 次轨迹中断".formatted(maxJump, interruptions),
                    "单独复听此处，使用较轻音量检查换声衔接。"));
        }
        if (longest >= .8 && spectrum.harmonicBalance != null && spectrum.harmonicBalance < .35) {
            result.add(inference("POSSIBLE_LIGHT_OR_FALSETTO_DOMINANCE", "音色可能偏轻或偏虚", "LOW",
                    "低次谐波相对高次谐波较弱；频谱也会受麦克风、距离和伴奏影响",
                    "结合原音听感确认，不依据单一频段占比判断真假声。"));
        }
        return List.copyOf(result);
    }

    private AudioPitchTrackResult.HighlightInference inference(
            String type, String label, String confidence, String evidence, String advice) {
        return new AudioPitchTrackResult.HighlightInference(type, label, confidence, evidence, advice);
    }

    private int representativeMidi(List<AudioPitchTrackResult.PitchPoint> points) {
        return points.stream().map(point -> (int) Math.round(point.midi()))
                .collect(java.util.stream.Collectors.groupingBy(value -> value, java.util.stream.Collectors.counting()))
                .entrySet().stream().max(Comparator.<java.util.Map.Entry<Integer, Long>>comparingLong(java.util.Map.Entry::getValue)
                        .thenComparingInt(java.util.Map.Entry::getKey)).map(java.util.Map.Entry::getKey).orElse(69);
    }

    private List<Double> adjacentChanges(List<AudioPitchTrackResult.PitchPoint> points) {
        List<Double> values = new ArrayList<>();
        for (int index = 1; index < points.size(); index++) {
            if (points.get(index).timeSeconds() - points.get(index - 1).timeSeconds() <= .2) {
                values.add(Math.abs(points.get(index).midi() - points.get(index - 1).midi()) * 100);
            }
        }
        values.sort(Double::compareTo);
        return values;
    }

    private double longestRun(List<AudioPitchTrackResult.PitchPoint> points) {
        if (points.isEmpty()) return 0;
        double longest = 0, start = points.getFirst().timeSeconds(), previous = start;
        for (AudioPitchTrackResult.PitchPoint point : points.subList(1, points.size())) {
            if (point.timeSeconds() - previous > .2) start = point.timeSeconds();
            previous = point.timeSeconds();
            longest = Math.max(longest, previous - start + .1);
        }
        return Math.max(longest, .1);
    }

    private int interruptions(List<AudioPitchTrackResult.PitchPoint> points) {
        int count = 0; boolean voiced = false;
        for (AudioPitchTrackResult.PitchPoint point : points) {
            if (!point.voiced() && voiced) count++;
            voiced = point.voiced();
        }
        return count;
    }

    private Spectrum spectrum(double[] samples, float sampleRate, double start, double end, int midi) {
        int windowStart = Math.max(0, (int) (start * sampleRate));
        int windowEnd = Math.min(samples.length, (int) (end * sampleRate));
        int available = Math.max(0, windowEnd - windowStart);
        if (available < 64) return Spectrum.empty();
        int size = 1;
        while (size * 2 <= available && size < 8192) size *= 2;
        int center = windowStart + available / 2;
        int from = Math.max(windowStart, Math.min(windowEnd - size, center - size / 2));
        double[] real = new double[size], imaginary = new double[size];
        for (int i = 0; i < size; i++) real[i] = samples[from + i] * (.5 - .5 * Math.cos(2 * Math.PI * i / (size - 1)));
        fft(real, imaginary);
        double low = 0, mid = 0, high = 0, weighted = 0, total = 0;
        double fundamental = 440 * Math.pow(2, (midi - 69) / 12.0), lowHarmonics = 0, highHarmonics = 0;
        for (int bin = 1; bin <= size / 2; bin++) {
            double hz = bin * sampleRate / size;
            double power = real[bin] * real[bin] + imaginary[bin] * imaginary[bin];
            if (hz < 250) low += power; else if (hz < 2000) mid += power; else if (hz <= 8000) high += power;
            total += power; weighted += hz * power;
            if (nearHarmonic(hz, fundamental, 1, 3)) lowHarmonics += power;
            if (nearHarmonic(hz, fundamental, 4, 8)) highHarmonics += power;
        }
        double covered = low + mid + high;
        return new Spectrum(round(low / Math.max(covered, 1e-20), 4),
                round(mid / Math.max(covered, 1e-20), 4), round(high / Math.max(covered, 1e-20), 4),
                round(weighted / Math.max(total, 1e-20), 1),
                round(lowHarmonics / Math.max(lowHarmonics + highHarmonics, 1e-20), 3));
    }

    private boolean nearHarmonic(double hz, double fundamental, int from, int to) {
        for (int harmonic = from; harmonic <= to; harmonic++) {
            if (Math.abs(hz - fundamental * harmonic) <= fundamental * .08) return true;
        }
        return false;
    }

    private void fft(double[] real, double[] imaginary) {
        int n = real.length;
        for (int i = 1, j = 0; i < n; i++) { int bit = n >> 1; while ((j & bit) != 0) { j ^= bit; bit >>= 1; } j ^= bit;
            if (i < j) { double value = real[i]; real[i] = real[j]; real[j] = value; } }
        for (int length = 2; length <= n; length <<= 1) {
            double angle = -2 * Math.PI / length;
            for (int start = 0; start < n; start += length) for (int offset = 0; offset < length / 2; offset++) {
                double phase = angle * offset, cosine = Math.cos(phase), sine = Math.sin(phase);
                int left = start + offset, right = left + length / 2;
                double rr = real[right] * cosine - imaginary[right] * sine;
                double ri = real[right] * sine + imaginary[right] * cosine;
                real[right] = real[left] - rr; imaginary[right] = imaginary[left] - ri;
                real[left] += rr; imaginary[left] += ri;
            }
        }
    }

    private double percentile(List<Double> sorted, double percentile) {
        double index = percentile * (sorted.size() - 1); int low = (int) Math.floor(index), high = (int) Math.ceil(index);
        return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * (index - low);
    }

    private String noteName(int midi) {
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        return names[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1);
    }

    private double round(double value, int digits) { double scale = Math.pow(10, digits); return Math.round(value * scale) / scale; }

    private record Candidate(List<AudioPitchTrackResult.PitchPoint> points, double score) {
        double center() { return points.stream().mapToDouble(AudioPitchTrackResult.PitchPoint::timeSeconds).average().orElse(0); }
    }
    private record Spectrum(Double lowRatio, Double midRatio, Double highRatio, Double centroid, Double harmonicBalance) {
        static Spectrum empty() { return new Spectrum(null, null, null, null, null); }
    }
}
