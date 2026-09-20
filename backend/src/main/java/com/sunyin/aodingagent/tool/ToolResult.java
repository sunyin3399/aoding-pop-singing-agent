package com.sunyin.aodingagent.tool;

public record ToolResult<T>(
        boolean success,
        String code,
        T data,
        String error,
        boolean retryable
) {
    public static <T> ToolResult<T> success(String code, T data) {
        return new ToolResult<>(true, code, data, null, false);
    }

    public static <T> ToolResult<T> failure(String code, String error, boolean retryable) {
        return new ToolResult<>(false, code, null, error, retryable);
    }
}
