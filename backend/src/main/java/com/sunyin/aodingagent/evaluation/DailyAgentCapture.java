package com.sunyin.aodingagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.stream.StreamSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 日常请求的旁路采集器：观察真实 SSE 事件，不调用模型、不判定答案语义正确性。
 * 每个 StreamSession 由 Registry 的单次 starter 绑定，重连只重放，不重复采集。
 */
@Component
public final class DailyAgentCapture {
    private static final Logger LOG = LoggerFactory.getLogger(DailyAgentCapture.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TRACE_EVENTS = Set.of(
            "step", "references", "tool_start", "tool_result", "error", "model_error");

    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "agent-eval-timeout");
        thread.setDaemon(true);
        return thread;
    });

    static {
        // 已完成请求立即移除截止任务，避免保留整个采集对象直到原定超时时刻。
        TIMER.setRemoveOnCancelPolicy(true);
    }

    // 有界单写线程保证同一记录 RUNNING → 终态顺序；队列满时丢采集，不反压聊天线程。
    private static final java.util.concurrent.ThreadPoolExecutor WRITER = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(256), task -> {
                Thread thread = new Thread(task, "agent-eval-writer");
                thread.setDaemon(true);
                return thread;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    private final java.util.concurrent.Executor writer;
    private final Environment env;
    private final AtomicLong writeFailures = new AtomicLong();
    private final AtomicLong captured = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean diagnosticsPending = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean attached;
    private volatile boolean closing;

    /** Best-effort bounded barrier; a capture shutdown never terminates a business session. */
    @jakarta.annotation.PreDestroy
    public void close() {
        closing = true;
        if (!attached) return;
        try { awaitWrites(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) { LOG.warn("Agent eval shutdown flush incomplete (details omitted)"); }
        diagnostics();
    }
    private final AtomicLong dropped = new AtomicLong();
    private static final Object SCENARIO_LOCK = new Object();
    private Path root() { return Path.of(env.getProperty("app.agent-eval.dir", "output/agent-eval")); }

    private synchronized void diagnostics() {
        try {
            Files.createDirectories(root());
            Path temp = Files.createTempFile(root(), "diagnostics-", ".tmp");
            Files.writeString(temp, JSON.writeValueAsString(Map.of("metadata", Map.of(
                    "captured", captured.get(), "failed", writeFailures.get(), "dropped", dropped.get()),
                    "scope", "current capture instance; captured=terminal snapshots persisted; failed=write/attach failures; dropped=rejected snapshots")));
            Files.move(temp, root().resolve("diagnostics.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) { LOG.warn("Agent eval diagnostics unavailable (details omitted)"); }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DailyAgentCapture(Environment env) {
        this(env, WRITER);
    }

    DailyAgentCapture(Environment env, java.util.concurrent.Executor writer) {
        this.env = env;
        this.writer = writer;
    }

    /** 测试用写队列屏障，业务请求不等待磁盘。 */
    void awaitWrites() throws Exception {
        var barrier = new java.util.concurrent.CompletableFuture<Void>();
        writer.execute(() -> barrier.complete(null));
        barrier.get(10, TimeUnit.SECONDS);
    }

    public long writeFailures() {
        return writeFailures.get();
    }

    /** 配置错误也必须隔离，不能让可选采集改变聊天结果。 */
    public void attach(StreamSession session, String question, String route) {
        attach(session, question, route, null, "unavailable");
    }

    public void attach(StreamSession session, String question, String route, String conversationId, String mode) {
        attach(session, question, route, conversationId, mode, null);
    }

    public void attach(StreamSession session, String question, String route, String conversationId, String mode,
                       org.springframework.ai.chat.model.ChatModel model) {
        try {
            if (closing || !enabled()) return;
            attached = true;
            long timeout = Math.max(1, env.getProperty("app.agent-eval.timeout-seconds", Long.class, 600L));
            Run run = new Run(question, route, conversationId, mode, model);
            session.observe(run::event, run::finish);
            run.save("RUNNING", null);
            // 只结束采集窗口。绝不调用 session.fail/cancel 或取消模型订阅。
            run.scheduleDeadline(timeout);
        } catch (RuntimeException error) {
            failed();
        }
    }

    private boolean enabled() {
        String[] profiles = env.getActiveProfiles();
        boolean explicitlyLocal = profiles.length > 0
                && Arrays.stream(profiles).allMatch(profile -> profile.equals("local") || profile.equals("dev"));
        return env.getProperty("app.agent-eval.enabled", Boolean.class, explicitlyLocal);
    }

    private void failed() {
        writeFailures.incrementAndGet();
        // Coalesce diagnostic I/O on the separate timer, never on the chat thread.
        if (attached && !closing && diagnosticsPending.compareAndSet(false, true)) {
            TIMER.schedule(() -> {
                try { diagnostics(); } finally { diagnosticsPending.set(false); }
            }, 100, TimeUnit.MILLISECONDS);
        }
        // 异常文本、文件路径、提供商响应可能带秘密，不输出到日志。
        LOG.warn("Agent eval capture write failed (details omitted for privacy)");
    }

    /** 尽力脱敏而非匿名化；业务正文仍可能含个人信息，禁止据此自动公开原始数据。 */
    public static String sanitize(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1[REDACTED]")
                .replaceAll("sk-[A-Za-z0-9_-]+", "[REDACTED]")
                .replaceAll("(?i)(api[-_]?key|password|secret|token)([\\\"'\\s:=]+)[^\\s,;\\\"'}]+", "$1$2[REDACTED]")
                .replaceAll("(?i)data:audio/[^\\s]+", "[AUDIO_OMITTED]");
    }

    private static boolean safeIdentifier(String value) {
        return value != null && value.matches("[\\p{L}\\p{N}_.:#-]{1,256}")
                && !value.startsWith("sk-");
    }

    /** 单请求状态；同步保护终止、取消、模型回调与采集定时器之间的竞争。 */
    private final class Run {
        private final String id = UUID.randomUUID().toString();
        private final long started = System.nanoTime();
        private final Map<String, Object> row = new LinkedHashMap<>();
        private final List<Map<String, String>> events = new ArrayList<>();
        private final Path destination;
        private final Map<String, String> unavailableReasons = new LinkedHashMap<>();
        private final Set<String> availableDimensions = new java.util.LinkedHashSet<>();

        private final Map<String, Set<String>> documentIds = new LinkedHashMap<>();
        private final Set<String> invalidDocumentDimensions = new java.util.HashSet<>();
        private final Set<String> pendingRetrievalCalls = new java.util.HashSet<>();

        private void retrievalStarted(String data) {
            try {
                var tool = JSON.readTree(data);
                if (tool == null || !"searchVocalKnowledge".equals(tool.path("toolName").asText())) return;
                String callId = tool.path("callId").asText();
                if (!safeIdentifier(callId) || pendingRetrievalCalls.size() >= 128) {
                    invalidDocumentDimensions.add("retrievalCandidateIds");
                } else pendingRetrievalCalls.add(callId);
                row.put("retrievalCandidateIds", "unavailable");
                availableDimensions.remove("retrievalCandidateIds");
                unavailableReasons.put("retrievalCandidateIds", "Retrieval invocation still pending or unobservable at capture boundary");
            } catch (Exception ignored) { /* Existing malformed tool trace handling remains responsible. */ }
        }

        /** Request-local union; one unknown observation poisons completeness instead of silently becoming empty. */
        private void captureDocumentIds(String field, String data) {
            if (!"AodingManus".equals(row.get("agentType"))) return;
            try {
                var value = JSON.readTree(data);
                if (value == null || data.length() > 65_536) throw new IllegalArgumentException();
                if ("retrievalCandidateIds".equals(field) && (!"searchVocalKnowledge".equals(value.path("toolName").asText())
                        || !safeIdentifier(value.path("callId").asText()))) throw new IllegalArgumentException();
                var incoming = value.path(field);
                if (!incoming.isArray() || incoming.size() > 128) throw new IllegalArgumentException();
                Set<String> ids = new java.util.LinkedHashSet<>(documentIds.getOrDefault(field, Set.of()));
                for (var item : incoming) {
                    if (!item.isTextual() || !safeIdentifier(item.asText())) throw new IllegalArgumentException();
                    ids.add(item.asText());
                }
                if (ids.size() > 128) throw new IllegalArgumentException();
                if (invalidDocumentDimensions.contains(field)) return;
                documentIds.put(field, ids);
                if ("retrievalCandidateIds".equals(field)) {
                    pendingRetrievalCalls.remove(value.path("callId").asText());
                    if (!pendingRetrievalCalls.isEmpty()) return;
                }
                row.put(field, List.copyOf(ids));
                availableDimensions.add(field);
                unavailableReasons.remove(field);
            } catch (Exception ignored) {
                invalidDocumentDimensions.add(field);
                row.put(field, "unavailable");
                availableDimensions.remove(field);
                unavailableReasons.put(field, "At least one scoped observation missing, failed, invalid, truncated or oversized; not an empty set");
            }
        }

        /** 只认事件里的显式 ID；空数组可观测，未发送/损坏/缺 ID 不能冒充空命中。 */
        private void captureReferences(String data) {
            try {
                var refs = JSON.readTree(data);
                if (refs == null || !refs.isArray() || refs.size() > 128) throw new IllegalArgumentException();
                var ids = new java.util.LinkedHashSet<String>();
                for (var ref : refs) {
                    var id = ref.path("id");
                    if (!id.isTextual() || !safeIdentifier(id.asText())) throw new IllegalArgumentException();
                    ids.add(id.asText());
                }
                row.put("referenceIds", List.copyOf(ids));
                unavailableReasons.remove("referenceIds");
                availableDimensions.add("referenceIds");
            } catch (Exception ignored) {
                row.put("referenceIds", "unavailable");
                availableDimensions.remove("referenceIds");
                unavailableReasons.put("referenceIds", "Invalid, oversized or missing explicit reference IDs; no title fallback");
            }
        }
        private final List<Map<String, Object>> toolTrace = new ArrayList<>();

        /** 新事件仅落白名单；旧的展示字符串不猜测工具成功与否。 */
        private void captureTool(String event, String data) {
            try {
                var tool = JSON.readTree(data);
                if (tool == null || !tool.isObject() || toolTrace.size() >= 128
                        || !safeIdentifier(tool.path("toolName").asText())
                        || !safeIdentifier(tool.path("callId").asText())) throw new IllegalArgumentException();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("event", event);
                item.put("toolName", tool.path("toolName").asText());
                item.put("callId", tool.path("callId").asText());
                for (String field : List.of("success", "outputTruncated"))
                    item.put(field, tool.path(field).isBoolean() ? tool.path(field).asBoolean() : "unavailable");
                var elapsed = tool.path("elapsedMs");
                item.put("elapsedMs", elapsed.isIntegralNumber() && elapsed.canConvertToLong() && elapsed.asLong() >= 0
                        ? elapsed.asLong() : "unavailable");
                item.put("successSemantics", "callback return/explicit success flag, not verified business correctness");
                item.put("outputTruncatedSource", "ToolOutputLimiter suffix; upstream truncation unobserved");
                item.put("callIdSource", "local_tool_invocation");
                toolTrace.add(item);
                row.put("toolTrace", toolTrace);
                unavailableReasons.remove("toolTrace");
                availableDimensions.add("toolTrace");
            } catch (Exception ignored) {
                unavailableReasons.put("toolTrace", "Some tool events invalid or beyond capture limit; trace incomplete");
                truncated = true;
            }
        }

        private String answer = "";
        private boolean done;
        private int budget = 65_536;
        private boolean truncated;
        private boolean errorEvent;
        private boolean formRequired;
        private ScheduledFuture<?> timer;

        private String conversationId;
        private String scenarioKey;
        private long turnIndex;

        private Run(String question, String route, String conversationId, String mode,
                    org.springframework.ai.chat.model.ChatModel model) {
            this.conversationId = conversationId;
            destination = root().resolve("raw").resolve(id + ".json");
            row.put("schemaVersion", 2);
            row.put("captureSchemaVersion", 2);
            row.put("agentType", route.contains("music_app") ? "MusicApp" : "AodingManus");
            row.put("mode", sanitize(mode));
            row.put("privacyReviewed", false);
            row.put("traceCompleteness", "PARTIAL");
            row.put("toolTraceScope", route.contains("music_app")
                    ? "MusicApp SSE only; model-internal tools unobserved"
                    : "ToolCallAgent callback wiring not yet observed");
            unavailableReasons.put("retrievalCandidateIds", route.contains("music_app")
                    ? "MusicApp model-internal tools have no request-scoped retrieval observer"
                    : "No completed searchVocalKnowledge request metadata observed");
            unavailableReasons.put("promptDocumentIds", route.contains("music_app")
                    ? "MusicApp model-internal tool messages are not observed after pruning"
                    : "No final Manus ChatModel dispatch observed; references are not prompt IDs");
            row.put("retrievalCandidateScope", "Manus searchVocalKnowledge only; union of first-stage and detail candidates before filtering");
            row.put("promptDocumentScope", "Manus searchVocalKnowledge tool messages only; union at final ChatClient dispatch after pruning");
            row.put("promptDocumentSemantics", "SENT_TOOL_MESSAGE_DOCUMENT_IDS_NOT_VERIFIED_MODEL_USE_OR_PROVIDER_RECEIPT");
            unavailableReasons.put("referenceIds", "No references event observed");
            unavailableReasons.put("tokenUsage", "No response usage observation wiring");
            unavailableReasons.put("toolTrace", "No structured tool event observed");
            row.put("unavailableReasons", unavailableReasons);
            row.put("availableDimensions", availableDimensions);
            for (String key : List.of("tokenUsage", "retrievalCandidateIds", "promptDocumentIds", "referenceIds", "toolTrace"))
                row.put(key, "unavailable");
            for (String key : List.of("promptVersion", "buildVersion"))
                row.put(key, clip(env.getProperty("app.agent-eval." + key, "unavailable"), 256));
            row.put("versionProvenance", "app.agent-eval configured labels, not verified artifacts");
            row.putAll(CapturedModelConfiguration.snapshot(env, model));
            row.put("finishedAt", null);
            row.put("runId", id);
            row.put("startedAt", Instant.now().toString());
            row.put("route", route);
            row.put("question", clip(question, 8192));
            row.put("annotationStatus", "UNLABELED");
            row.put("judgeStatus", "NOT_JUDGED");
            row.put("referenceSemantics", "EMITTED_REFERENCES_NOT_VERIFIED_USED_IDS; excerpts only");
        }

        private synchronized void scheduleDeadline(long timeout) {
            if (!done) {
                timer = TIMER.schedule(() -> finish("CAPTURE_TIMEOUT", null), timeout, TimeUnit.SECONDS);
            }
        }

        private String clip(String value, int max) {
            String clean = sanitize(value);
            int length = Math.min(clean.length(), Math.min(max, Math.max(0, budget)));
            if (length < clean.length()) truncated = true;
            budget -= length;
            return clean.substring(0, length);
        }

        private synchronized void event(String event, String data) {
            if (done) return;
            if ("eval_retrieval".equals(event) || "eval_prompt_documents".equals(event)) {
                captureDocumentIds("eval_retrieval".equals(event) ? "retrievalCandidateIds" : "promptDocumentIds", data);
                return; // IDs only, no raw metadata, arguments, result body or arbitrary reason text.
            }
            if ("trace_capabilities".equals(event)) {
                try {
                    var capability = JSON.readTree(data);
                    if ("AodingManus".equals(row.get("agentType")) && capability != null
                            && "ToolCallAgent callback boundary only".equals(capability.path("toolTraceScope").asText())) {
                        row.put("toolTraceScope", "ToolCallAgent callback boundary only");
                        row.put("toolTrace", toolTrace);
                        availableDimensions.add("toolTrace");
                        unavailableReasons.remove("toolTrace");
                    }
                } catch (Exception ignored) { /* 缺失能力声明仍保持 unavailable。 */ }
                return;
            }
            if ("tool_start".equals(event) || "tool_result".equals(event)) {
                if ("tool_start".equals(event)) retrievalStarted(data);
                captureTool(event, data);
                return; // 原始参数/输出绝不重复进入通用 events。
            }
            if ("references".equals(event)) captureReferences(data);
            if ("error".equals(event) || "model_error".equals(event)) errorEvent = true;
            if ("trainingPlanForm".equals(event)) formRequired = true;
            if ("final".equals(event)) {
                answer = clip(data, 32_768);
            } else if ("message".equals(event)) {
                answer += clip(data, Math.max(0, 32_768 - answer.length()));
            } else if (TRACE_EVENTS.contains(event)) {
                if (events.size() < 128) {
                    events.add(Map.of("event", event, "data", clip(data, 8192)));
                } else {
                    truncated = true;
                }
            }
        }

        private synchronized void finish(String status, Throwable error) {
            if (done) return;
            done = true;
            if (timer != null) timer.cancel(false);
            if ("SUCCESS".equals(status)) {
                status = errorEvent ? "ERROR" : formRequired ? "FORM_REQUIRED"
                        : answer.isBlank() ? "NO_ANSWER" : "SUCCESS";
            }
            save(status, error);
        }

        /** 先写临时文件再替换，避免报告读到半截 JSON；失败不传播给业务。 */
        private synchronized void save(String status, Throwable error) {
            // 流式块可能在凭据中间切开，拼接后的答案在落盘前再次脱敏。
            row.put("answer", sanitize(answer)); // v1 alias retained for existing private readers
            row.put("finalAnswer", sanitize(answer));
            row.put("terminalStatus", status);
            row.put("errorSummary", error == null ? (errorEvent ? "Observed error event; see sanitized events" : null)
                    : clip(error.getMessage(), 1024));
            row.put("truncation", Map.of("any", truncated, "characterBudget", 65_536, "eventLimit", 128));
            row.put("events", events);
            row.put("status", status);
            row.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            row.put("truncated", truncated);
            row.put("errorType", error == null ? (errorEvent ? "unavailable" : null) : error.getClass().getName());
            // 采集截止不代表业务结束，报告必须把 CAPTURE_TIMEOUT 计入未完成。
            if (done) row.put("finishedAt", Instant.now().toString());
            try {
                // 在持锁期间生成不可变快照；后台写线程不读取会继续变化的 row/events。
                String snapshot = JSON.writeValueAsString(row);
                writer.execute(() -> writeSnapshot(snapshot, !"RUNNING".equals(status)));
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                dropped.incrementAndGet();
                failed();
            } catch (Exception errorWriting) {
                failed();
            }
        }

        // Only the writer performs filesystem work or retains a conversation identifier.
        // Persisted records never contain the source identifier or salt.
        private void assignScenario() throws Exception {
            if (scenarioKey != null && turnIndex > 0) return;
            synchronized (SCENARIO_LOCK) {
                Path saltFile = root().resolve(".scenario-salt");
                if (!Files.exists(saltFile)) {
                    byte[] salt = new byte[32]; new java.security.SecureRandom().nextBytes(salt);
                    Files.write(saltFile, salt, StandardOpenOption.CREATE_NEW);
                }
                var mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(Files.readAllBytes(saltFile), "HmacSHA256"));
                String source = conversationId == null || conversationId.isBlank() ? "run:" + id : "conversation:" + conversationId;
                scenarioKey = java.util.HexFormat.of().formatHex(mac.doFinal(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                // Retain the source only until the counter is safely persisted.
                // A private per-scenario counter survives restart and pruning of raw records.
                Path counters = root().resolve(".scenario-turns");
                Files.createDirectories(counters);
                Path counter = counters.resolve(scenarioKey);
                long nextTurn = Files.exists(counter) ? Long.parseLong(Files.readString(counter)) + 1 : 1;
                Path temp = Files.createTempFile(counters, "turn-", ".tmp");
                Files.writeString(temp, Long.toString(nextTurn));
                Files.move(temp, counter, StandardCopyOption.REPLACE_EXISTING);
                turnIndex = nextTurn;
                conversationId = null;
            }
        }

        private void writeSnapshot(String snapshot, boolean terminal) {
            Path temporary = destination.resolveSibling(id + ".tmp");
            try {
                Files.createDirectories(destination.getParent());
                assignScenario();
                var enriched = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(snapshot);
                enriched.put("scenarioKey", scenarioKey);
                enriched.put("turnIndex", turnIndex);
                Files.writeString(temporary, JSON.writeValueAsString(enriched),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException errorMoving) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                if (terminal) captured.incrementAndGet();
            } catch (Exception errorWriting) {
                failed();
            } finally {
                diagnostics();
            }
        }
    }
}
