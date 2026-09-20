package com.sunyin.aodingagent.stream;

/**
 * Agent 输出事件的统一接收接口。
 * <p>
 * Agent 只负责发送步骤、最终回答和引用，不需要知道事件最终通过 SSE、测试替身还是其他通道
 * 传给前端。实现类还负责告诉 Agent 用户是否已经取消任务。
 */
public interface AgentEventSink {
    /** 发送一个带事件类型的文本数据。 */
    void emit(String event, String data);

    /** 返回用户是否已经取消任务，Agent 应在耗时步骤之间检查它。 */
    boolean isCancelled();

    /** 正常结束事件流。 */
    void complete();

    /** 发送错误并结束事件流。 */
    void fail(Throwable error);
}
