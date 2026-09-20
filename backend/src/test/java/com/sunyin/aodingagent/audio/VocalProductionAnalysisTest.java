package com.sunyin.aodingagent.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人声"发声行为"分析的单元测试。
 * <p>
 * 用合成的"谐波叠加 + 高频噪声"信号验证：音区能正确划分、频段分布有 7 个感知频段、
 * 各音区能产出发声机制指标（H1-H2、频谱斜率等），且推断逻辑不会抛异常。
 */
class VocalProductionAnalysisTest {

    private static final float SAMPLE_RATE = 32_000;

    /** 生成一段由基频与若干谐波构成的合成"歌声"信号。 */
    private static double[] harmonicTone(double f0, double seconds, double[] harmonicAmp) {
        int length = (int) (SAMPLE_RATE * seconds);
        double[] samples = new double[length];
        for (int i = 0; i < length; i++) {
            double t = i / (double) SAMPLE_RATE;
            double value = 0;
            for (int h = 0; h < harmonicAmp.length; h++) {
                value += harmonicAmp[h] * Math.sin(2 * Math.PI * f0 * (h + 1) * t);
            }
            samples[i] = 0.3 * value;
        }
        return samples;
    }

    @Test
    void segmentsSignalIntoRegistersAndReportsBands() {
        AudioAnalysisService service = new AudioAnalysisService();

        // 前半段：150Hz 胸声（低音区）+ 前半段穿插 6kHz 齿音噪声
        double[] low = harmonicTone(150, 1.5, new double[]{1.0, 0.5, 0.3});
        double[] high = harmonicTone(500, 1.5, new double[]{1.0, 0.4, 0.2});

        double[] samples = new double[low.length + high.length];
        System.arraycopy(low, 0, samples, 0, low.length);
        System.arraycopy(high, 0, samples, low.length, high.length);

        // 在低音段中加入 5-8kHz 高频噪声，制造齿音信号
        java.util.Random random = new java.util.Random(42);
        for (int i = (int) (0.3 * SAMPLE_RATE); i < (int) (0.9 * SAMPLE_RATE); i++) {
            double noise = (random.nextDouble() * 2 - 1) * 0.15;
            double t = i / (double) SAMPLE_RATE;
            // 带通到 6kHz 附近：用 6kHz 载波调制噪声
            samples[i] += noise * Math.sin(2 * Math.PI * 6000 * t);
        }

        VocalProductionResult result = service.analyzeVocalProductionSamples("synthetic.wav", samples, SAMPLE_RATE);

        assertEquals(3.0, result.durationSeconds(), 0.01);
        assertEquals(7, result.bandEnergies().size(), "应有 7 个感知频段");

        // 音区应能同时识别到低音区与高音区
        List<VocalProductionResult.RegisterAnalysis> registers = result.registers();
        assertTrue(registers.stream().anyMatch(r -> "低音区".equals(r.register())),
                "应识别到低音区");
        assertTrue(registers.stream().anyMatch(r -> "高音区".equals(r.register())),
                "应识别到高音区");

        VocalProductionResult.RegisterAnalysis lowReg = registers.stream()
                .filter(r -> "低音区".equals(r.register())).findFirst().orElseThrow();
        VocalProductionResult.RegisterAnalysis highReg = registers.stream()
                .filter(r -> "高音区".equals(r.register())).findFirst().orElseThrow();

        assertNotNull(lowReg.medianPitchHz());
        assertTrue(Math.abs(lowReg.medianPitchHz() - 150) < 20, "低音区应接近 150Hz");
        assertTrue(Math.abs(highReg.medianPitchHz() - 500) < 40, "高音区应接近 500Hz");

        // H1-H2 与频谱斜率应有有限值
        assertTrue(Double.isFinite(lowReg.medianH1h2Db()));
        assertTrue(Double.isFinite(lowReg.spectralSlopeDbPerOctave()));

        // 行为推断不应为空且不抛异常
        assertNotNull(result.behaviors());

        // 发声控制块：每区产出相对指标、总结非空、破音事件列表存在
        assertNotNull(result.registerControls());
        assertTrue(result.registerControls().stream().anyMatch(r -> "低音区".equals(r.register())),
                "应有低音区的控制指标");
        VocalProductionResult.RegisterControl lowControl = result.registerControls().stream()
                .filter(r -> "低音区".equals(r.register())).findFirst().orElseThrow();
        assertNotNull(lowControl.baselinePitchHz());
        assertNotNull(lowControl.baselineH1h2Db());
        assertNotNull(result.controlNote());
        assertFalse(result.controlNote().isBlank(), "发声控制总结不应为空");
        assertNotNull(result.breakEvents());
    }
}
