package com.sunyin.aodingagent.stream;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 按 requestId 保存当前实例中的流会话。
 * <p>
 * 前端因为网络波动重复请求同一个 requestId 时，这里会返回已有会话而不是再次启动模型任务。
 * 已结束会话保留 10 分钟供用户重连，之后自动清理，避免内存一直增长。
 */
@Component
public class StreamSessionRegistry {

    private static final Duration COMPLETED_SESSION_TTL = Duration.ofMinutes(10);
    private final Map<String, StreamSession> sessions = new ConcurrentHashMap<>();

    /** 获取已有会话；不存在时创建，并且只执行一次 starter。 */
    public StreamSession getOrStart(String requestId, Consumer<StreamSession> starter) {
        cleanupExpired();
        StreamSession session = sessions.computeIfAbsent(requestId, StreamSession::new);
        if (session.markStarted()) {
            try { starter.accept(session); }
            catch (RuntimeException error) { session.fail(error); }
        }
        return session;
    }

    /** 根据 requestId 取消会话；找不到时返回 false。 */
    public boolean cancel(String requestId) {
        StreamSession session = sessions.get(requestId);
        if (session == null) return false;
        session.cancel();
        return true;
    }

    private void cleanupExpired() {
        Instant cutoff = Instant.now().minus(COMPLETED_SESSION_TTL);
        sessions.entrySet().removeIf(entry -> {
            Instant finishedAt = entry.getValue().finishedAt();
            return finishedAt != null && finishedAt.isBefore(cutoff);
        });
    }
}
