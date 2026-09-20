package com.sunyin.aodingagent.audio;

/** 将发声控制卡片压缩为适合会话上下文的可读文本。 */
public final class VocalProductionMemoryFormatter {

    private VocalProductionMemoryFormatter() { }

    public static String format(VocalProductionResult result) {
        StringBuilder text = new StringBuilder("音频发声控制分析：\n");
        text.append("发声控制 · 按音区倾向：").append(result.controlNote()).append('\n');
        for (VocalProductionResult.RegisterControl row : result.registerControls()) {
            text.append(row.register()).append("：音高 ")
                    .append(pitch(row.baselinePitchHz(), row.topPitchHz()))
                    .append("；H1-H2 ").append(db(row.baselineH1h2Db(), row.topH1h2Db()))
                    .append("；歌手共振峰(亮芯) ").append(db(row.baselineSingerDb(), row.topSingerDb()))
                    .append('。').append('\n');
        }
        return text.toString().trim();
    }

    private static String pitch(Double baseline, Double top) {
        return value(baseline, "Hz") + " → " + value(top, "Hz");
    }

    private static String db(Double baseline, Double top) {
        return value(baseline, "dB") + " → " + value(top, "dB");
    }

    private static String value(Double value, String unit) {
        return value == null ? "无数据" : "%.1f%s".formatted(value, unit);
    }
}
