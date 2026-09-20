package com.sunyin.aodingagent.tools;

public final class ToolOutputLimiter {

    public static final int DEFAULT_MAX_CHARS = 12_000;

    private ToolOutputLimiter() {
    }

    public static String limit(String output) {
        if (output == null || output.length() <= DEFAULT_MAX_CHARS) return output;
        int removed = output.length() - DEFAULT_MAX_CHARS;
        return output.substring(0, DEFAULT_MAX_CHARS)
                + "\n\n[工具输出已截断，省略 " + removed + " 个字符]";
    }
}
