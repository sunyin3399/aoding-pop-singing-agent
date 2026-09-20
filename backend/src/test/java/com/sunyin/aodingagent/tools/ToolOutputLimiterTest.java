package com.sunyin.aodingagent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolOutputLimiterTest {

    @Test
    void keepsShortOutputAndTruncatesLongOutput() {
        assertEquals("short", ToolOutputLimiter.limit("short"));

        String limited = ToolOutputLimiter.limit("x".repeat(ToolOutputLimiter.DEFAULT_MAX_CHARS + 100));
        assertTrue(limited.startsWith("x".repeat(100)));
        assertTrue(limited.contains("工具输出已截断"));
        assertTrue(limited.length() < ToolOutputLimiter.DEFAULT_MAX_CHARS + 100);
    }
}
