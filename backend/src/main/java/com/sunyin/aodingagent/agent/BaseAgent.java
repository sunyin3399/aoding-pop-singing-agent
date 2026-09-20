package com.sunyin.aodingagent.agent;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import com.sunyin.aodingagent.agent.model.AgentState;
import com.sunyin.aodingagent.research.ReferenceAccumulator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.sunyin.aodingagent.stream.AgentEventSink;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 抽象基础代理类，用于管理代理状态和执行流程。
 * <p>
 * 提供状态转换、内存管理和基于步骤的执行循环的基础功能。
 * 子类必须实现step方法。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    private static final ObjectMapper REFERENCE_MAPPER = new ObjectMapper();

    // 核心属性  
    private String name;

    // 提示  
    private String systemPrompt;
    private String nextStepPrompt;

    // 状态  
    private AgentState state = AgentState.IDLE;

    // 执行控制  
    private int maxSteps = 10;
    private int currentStep = 0;

    // 最终答案
    private String finalAnswer;

    // 本轮最初的用户请求；最终回复验收需要保留数量、对象和交付要求。
    private String originalUserPrompt;

    // 参考资料
    private ReferenceAccumulator references = new ReferenceAccumulator();

    // 下载文件名
    private String downloadFileName;

    private String trainingPlanFormPayload;

    // LLM  
    private ChatClient chatClient;

    // Memory（需要自主维护会话上下文）  
    private List<Message> messageList = new ArrayList<>();

    private ExecutorService llmRequestExecutor;

    /**
     * 单次 step() 执行期间产生的高清轨迹条目（例如“决定调用工具 X”“工具 X 返回：命中 N 条”）。
     * 由 think/act 在 step() 过程中追加，step() 返回后由执行循环逐条发成独立的 step 事件，
     * 前端据此渲染干净的“Agent 执行轨迹”，而不是把整个思考+行动揉成一条长摘要。
     */
    private List<String> stepTrace = new ArrayList<>();

    public void setLlmRequestExecutor(ExecutorService llmRequestExecutor) {
        this.llmRequestExecutor = llmRequestExecutor;
    }

    /** 每个 step 开始时清空轨迹缓冲。 */
    protected void clearStepTrace() {
        stepTrace = new ArrayList<>();
    }

    /** 在本次 step 内追加一条轨迹条目（原文存入，前端负责编号与排版）。 */
    protected void traceStep(String line) {
        stepTrace.add(line);
    }

    /**
     * 当前 runToSink 正在写入的 sink；供耗时工具在阻塞执行期间把“进行中”事件实时推给前端，
     * 避免用户在长任务（如外部研究）期间只看到 loading。
     */
    private AgentEventSink activeSink;

    /** 在耗时步骤阻塞期间，直接把一条“进行中”轨迹实时推给当前 sink；无 sink 时退回 stepTrace。 */
    protected void reportProgress(String line) {
        if (activeSink != null && !activeSink.isCancelled()) {
            activeSink.emit("step", line);
        } else {
            traceStep(line);
        }
    }

    /** 在步骤执行中把一条独立类型的实时事件推给当前 sink（如 candidateKnowledge）。 */
    protected void emitLiveEvent(String eventName, String data) {
        if (activeSink != null && !activeSink.isCancelled()) {
            activeSink.emit(eventName, data);
        }
    }

    /** 取出本次 step 的轨迹条目并清空；供执行循环逐条发射。 */
    protected List<String> drainStepTrace() {
        List<String> drained = List.copyOf(stepTrace);
        stepTrace = new ArrayList<>();
        return drained;
    }

    /**
     * 运行代理
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public SseEmitter runStream(String userPrompt) {
        SseEmitter emitter = new SseEmitter(600000L);

        CompletableFuture.runAsync(() -> {
            try {
                if (this.state != AgentState.IDLE) {
                    safeSend(emitter, "不能在: " + this.state + "状态下执行");
                    emitter.complete();
                    return;
                }
                if (StrUtil.isBlank(userPrompt)) {
                    safeSend(emitter, "user prompt为空，不能执行");
                    emitter.complete();
                    return;
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }

            // 更改状态
            state = AgentState.RUNNING;
            originalUserPrompt = userPrompt;
            // 记录消息上下文
            messageList.add(new UserMessage(userPrompt));
            try {
                for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                    int stepNumber = i + 1;
                    currentStep = stepNumber;
                    log.info("执行步骤 {}/{}", stepNumber, maxSteps);
                    step();
                    boolean sendOk = true;
                    for (String traceLine : drainStepTrace()) {
                        if (!safeSendStep(emitter, traceLine)) {
                            sendOk = false;
                            break;
                        }
                    }
                    if (!sendOk) {
                        log.info("SSE连接已关闭，停止发送");
                        break;
                    }
                }
                if (currentStep >= maxSteps && state != AgentState.FINISHED) {
                    state = AgentState.FINISHED;
                    safeSendStep(emitter, "模型执行超过最大步数： (" + maxSteps + ")，调用终止");
                }
                if (finalAnswer != null) {
                    safeSendFinal(emitter, finalAnswer);
                }
                if (!references.snapshot().isEmpty()) {
                    safeSendReferences(emitter, referencesPayload());
                }
                if (downloadFileName != null && !downloadFileName.isEmpty()) {
                    safeSendFileDownload(emitter, downloadFileName);
                }
                emitter.complete();
            } catch (Exception e) {
                safeSendStep(emitter, "agent执行异常" + e.getMessage());
                emitter.complete();
            } finally {
                this.cleanup();
            }
        }, llmRequestExecutor);

        emitter.onTimeout(() -> {
            this.state = AgentState.FINISHED;
            this.cleanup();
            log.warn("SSE 连接超时");
        });

        emitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
                this.cleanup();
                log.info("SSE 连接完成");
            }
        });

        return emitter;
    }

    /**
     * 将 Agent 事件写入可重放的流会话。返回的 Future 可被显式取消，取消时会
     * 中断当前虚拟线程，并阻止后续 Agent 步骤和事件继续执行。
     */
    public Future<?> runToSink(String userPrompt, AgentEventSink sink) {
        return llmRequestExecutor.submit(() -> {
            activeSink = sink;
            try {
                if (state != AgentState.IDLE || StrUtil.isBlank(userPrompt)) {
                    sink.emit("error", "Agent 状态异常或用户输入为空");
                    sink.complete();
                    return;
                }
                state = AgentState.RUNNING;
                originalUserPrompt = userPrompt;
                messageList.add(new UserMessage(userPrompt));
                for (int i = 0; i < maxSteps && state != AgentState.FINISHED && !sink.isCancelled(); i++) {
                    currentStep = i + 1;
                    step();
                    if (sink.isCancelled()) break;
                    for (String traceLine : drainStepTrace()) {
                        if (sink.isCancelled()) break;
                        sink.emit("step", traceLine);
                    }
                    if (trainingPlanFormPayload != null) {
                        sink.emit("trainingPlanForm", trainingPlanFormPayload);
                        state = AgentState.FINISHED;
                        break;
                    }
                }
                if (sink.isCancelled()) return;
                if (currentStep >= maxSteps && state != AgentState.FINISHED) {
                    state = AgentState.FINISHED;
                    sink.emit("step", "模型执行超过最大步数（" + maxSteps + "），已终止");
                }
                if (trainingPlanFormPayload == null && finalAnswer != null) sink.emit("final", finalAnswer);
                if (!references.snapshot().isEmpty()) sink.emit("references", referencesPayload());
                if (downloadFileName != null && !downloadFileName.isEmpty()) {
                    sink.emit("fileDownload", downloadFileName);
                }
                sink.complete();
            } catch (Exception exception) {
                if (!sink.isCancelled()) sink.fail(exception);
            } finally {
                state = AgentState.FINISHED;
                activeSink = null;
                cleanup();
            }
        });
    }

    /**
     * 执行单个步骤
     *
     * @return 步骤执行结果
     */
    public abstract String step();

    /**
     * 最终答案发送前的扩展点。普通 Agent 原样返回，通用工具 Agent 可以在这里做结构化验收。
     */
    protected String finalizeAnswer(String answer) {
        return answer;
    }

    /**
     * 清理资源
     */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源  
    }

    String referencesPayload() {
        try {
            return REFERENCE_MAPPER.writeValueAsString(references.snapshot());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化参考资料", exception);
        }
    }

    private boolean safeSend(SseEmitter emitter, String data) {
        try {
            emitter.send(data);
            return true;
        } catch (IllegalStateException e) {
            log.warn("SSE推送已结束，跳过以下推送信息: {}", data);
            return false;
        } catch (IOException e) {
            log.warn("SSE推送异常，已结束: {}", e.getMessage());
            return false;
        }
    }

    private boolean safeSendStep(SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().name("step").data(data));
            return true;
        } catch (IllegalStateException e) {
            log.warn("SSE推送已结束，跳过步骤推送: {}", data);
            return false;
        } catch (IOException e) {
            log.warn("SSE推送异常，已结束: {}", e.getMessage());
            return false;
        }
    }

    private boolean safeSendFinal(SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().name("final").data(data));
            return true;
        } catch (IllegalStateException e) {
            log.warn("SSE推送已结束，跳过最终回复推送: {}", data);
            return false;
        } catch (IOException e) {
            log.warn("SSE推送异常，已结束: {}", e.getMessage());
            return false;
        }
    }

    private boolean safeSendReferences(SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().name("references").data(data));
            return true;
        } catch (IllegalStateException e) {
            log.warn("SSE推送已结束，跳过参考资料推送: {}", data);
            return false;
        } catch (IOException e) {
            log.warn("SSE推送异常，已结束: {}", e.getMessage());
            return false;
        }
    }

    private boolean safeSendFileDownload(SseEmitter emitter, String fileName) {
        try {
            emitter.send(SseEmitter.event().name("fileDownload").data(fileName));
            return true;
        } catch (IllegalStateException e) {
            log.warn("SSE推送已结束，跳过文件下载推送: {}", fileName);
            return false;
        } catch (IOException e) {
            log.warn("SSE推送异常，已结束: {}", e.getMessage());
            return false;
        }
    }
}
