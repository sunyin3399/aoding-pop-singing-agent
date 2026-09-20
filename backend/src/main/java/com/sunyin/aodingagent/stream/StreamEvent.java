package com.sunyin.aodingagent.stream;

/**
 * 一条可以通过 SSE 发送和重放的 Agent 事件。
 *
 * @param id 当前流会话内单调递增的事件序号，前端断线重连时用它续传
 * @param event 事件类型，例如 step、final、references、error 或 complete
 * @param data 事件携带的文本或 JSON 数据
 */
public record StreamEvent(long id, String event, String data) {
}
