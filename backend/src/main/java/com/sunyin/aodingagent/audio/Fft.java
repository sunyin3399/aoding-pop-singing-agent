package com.sunyin.aodingagent.audio;

/**
 * 就地基-2 快速傅里叶变换工具。
 * <p>
 * 纯静态、无状态，供音频分析链路上的多个模块共用（频谱、声谱图、人声发声分析等），
 * 避免各模块重复实现同一份 FFT。要求输入的 {@code real}/{@code imaginary} 长度相等，
 * 且为 2 的幂；算法为标准的 Cooley-Tukey 蝶形实现。
 */
public final class Fft {

    private Fft() {
        // 工具类：不实例化
    }

    /**
     * 就地计算 DFT，结果写回 {@code real} 与 {@code imaginary}。
     *
     * @param real       实部（输入时放采样/信号值），长度必须是 2 的幂
     * @param imaginary  虚部（输入时通常全 0）
     */
    public static void fft(double[] real, double[] imaginary) {
        int size = real.length;
        // 位反转重排：把输入序列按二进制位反转顺序排列
        for (int index = 1, reversed = 0; index < size; index++) {
            int bit = size >> 1;
            while ((reversed & bit) != 0) {
                reversed ^= bit;
                bit >>= 1;
            }
            reversed ^= bit;
            if (index < reversed) {
                double realValue = real[index];
                real[index] = real[reversed];
                real[reversed] = realValue;
                double imaginaryValue = imaginary[index];
                imaginary[index] = imaginary[reversed];
                imaginary[reversed] = imaginaryValue;
            }
        }
        // 蝶形合并：从 2 点蝶形逐级放大到整段
        for (int length = 2; length <= size; length <<= 1) {
            double angle = -2 * Math.PI / length;
            double phaseReal = Math.cos(angle);
            double phaseImaginary = Math.sin(angle);
            for (int start = 0; start < size; start += length) {
                double currentReal = 1;
                double currentImaginary = 0;
                for (int offset = 0; offset < length / 2; offset++) {
                    int left = start + offset;
                    int right = left + length / 2;
                    double rightReal = real[right] * currentReal - imaginary[right] * currentImaginary;
                    double rightImaginary = real[right] * currentImaginary + imaginary[right] * currentReal;
                    real[right] = real[left] - rightReal;
                    imaginary[right] = imaginary[left] - rightImaginary;
                    real[left] += rightReal;
                    imaginary[left] += rightImaginary;
                    double nextReal = currentReal * phaseReal - currentImaginary * phaseImaginary;
                    currentImaginary = currentReal * phaseImaginary + currentImaginary * phaseReal;
                    currentReal = nextReal;
                }
            }
        }
    }
}
