package com.sunyin.aodingagent.stream;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * 管理一个长耗时 Agent 请求的事件、重连和取消状态。
 * <p>
 * 普通 SSE 连接断开后，已经生成的内容容易丢失。这个类给每个事件分配递增 ID，并在内存中
 * 最多保留 2,000 条事件；前端带着最后收到的 ID 重连时，可以只补发后续事件。它还持有正在
 * 执行的模型订阅或 Agent Future，用户取消时会真正中断后台任务。
 * <p>
 * 当前实现保存在单个应用实例内存中，服务重启或请求落到另一实例时不能继续重放。
 */
public class StreamSession implements AgentEventSink {

    private static final int MAX_REPLAY_EVENTS = 2_000;

    private final String requestId;
    private final Sinks.Many<StreamEvent> events = Sinks.many().replay().limit(MAX_REPLAY_EVENTS);
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Disposable modelSubscription;
    private volatile Future<?> agentTask;
    private volatile Instant finishedAt;
    private BiConsumer<String, String> observer;
    private BiConsumer<String, Throwable> terminalObserver;

    /** 由单次 starter 注册轻量旁路观察者；观察者不能承担业务超时或取消职责。 */
    public synchronized void observe(BiConsumer<String, String> observer,
                                     BiConsumer<String, Throwable> terminalObserver) {
        this.observer = observer;
        this.terminalObserver = terminalObserver;
    }

    private void observedEnd(String status, Throwable error) {
        try {
            if (terminalObserver != null) terminalObserver.accept(status, error);
        } catch (RuntimeException ignored) {
            // 采集异常不可改变 SSE 终态。
        } finally {
            observer = null;
            terminalObserver = null;
        }
    }

    StreamSession(String requestId) {
        this.requestId = requestId;
    }

    public String requestId() {
        return requestId;
    }

    /** 确保同一个 requestId 对应的后台任务只启动一次。 */
    public boolean markStarted() {
        return started.compareAndSet(false, true);
    }

    @Override
    public synchronized void emit(String event, String data) {
        if (cancelled.get() || finishedAt != null) return;
        try {
            if (observer != null) observer.accept(event, data);
        } catch (RuntimeException ignored) {
            // 采集失败不阻断事件发送。
        }
        events.tryEmitNext(new StreamEvent(sequence.incrementAndGet(), event, data));
    }

    /** 返回指定事件 ID 之后的事件，用于 SSE 断线续传。 */
    public Flux<StreamEvent> eventsAfter(long lastEventId) {
        return events.asFlux().filter(event -> event.id() > lastEventId);
    }

    public void attach(Disposable subscription) {
        this.modelSubscription = subscription;
        if (cancelled.get()) subscription.dispose();
    }

    public void attach(Future<?> task) {
        this.agentTask = task;
        if (cancelled.get()) task.cancel(true);
    }

    @Override
    public synchronized void complete() {
        if (finishedAt != null) return;
        emit("complete", "done");
        finishedAt = Instant.now();
        observedEnd("SUCCESS", null);
        events.tryEmitComplete();
    }

    @Override
    public synchronized void fail(Throwable error) {
        if (finishedAt != null) return;
        emit("error", error.getMessage() == null ? "流式任务执行失败" : error.getMessage());
        finishedAt = Instant.now();
        observedEnd(error instanceof java.util.concurrent.TimeoutException ? "TIMEOUT" : "ERROR", error);
        events.tryEmitComplete();
    }

    /** 取消模型订阅或 Agent 任务，发送 cancelled 事件并关闭流。 */
    public synchronized void cancel() {
        if (finishedAt != null) return;
        if (!cancelled.compareAndSet(false, true)) return;
        Disposable subscription = modelSubscription;
        if (subscription != null) subscription.dispose();
        Future<?> task = agentTask;
        if (task != null) task.cancel(true);
        finishedAt = Instant.now();
        observedEnd("CANCELLED", null);
        events.tryEmitNext(new StreamEvent(sequence.incrementAndGet(), "cancelled", "任务已取消"));
        events.tryEmitComplete();
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    public Instant finishedAt() {
        return finishedAt;
    }
}
