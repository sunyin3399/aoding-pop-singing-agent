package com.sunyin.aodingagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sunyin.aodingagent.agent.AodingManus;
import com.sunyin.aodingagent.app.MusicApp;
import com.sunyin.aodingagent.constant.FileConstant;
import com.sunyin.aodingagent.conversation.ConversationModels.ConversationMode;
import com.sunyin.aodingagent.conversation.ConversationPersistenceSink;
import com.sunyin.aodingagent.conversation.ConversationTurnCoordinator;
import com.sunyin.aodingagent.metrics.LlmMetricsTracker;
import com.sunyin.aodingagent.research.finalization.AgentResponseFinalizer;
import com.sunyin.aodingagent.research.AgentReference;
import com.sunyin.aodingagent.stream.StreamEvent;
import com.sunyin.aodingagent.stream.StreamSession;
import com.sunyin.aodingagent.stream.StreamSessionRegistry;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.messages.Message;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Future;

@RestController
@RequestMapping("/ai")
@Slf4j
public class AiController {

    @Resource
    private MusicApp musicApp;

    @Resource
    private ToolCallback[] allTools;

    @Autowired(required = false)
    private ToolCallingManager toolCallingManager;

    @Resource
    private ChatModel dashscopeChatModel;

    @Resource
    private LlmMetricsTracker metricsTracker;

    @Resource
    private StreamSessionRegistry streamSessionRegistry;

    @Resource
    private AgentResponseFinalizer agentResponseFinalizer;

    @Resource
    private ConversationTurnCoordinator conversationTurnCoordinator;

    @Resource
    private ObjectMapper objectMapper;
    // 可选旁路采集；未装配或禁用不影响日常聊天。
    @Autowired(required = false)
    private com.sunyin.aodingagent.evaluation.DailyAgentCapture dailyAgentCapture;

    @Resource(name = "llmRequestExecutor")
    private java.util.concurrent.ExecutorService llmRequestExecutor;

    /**
     * 处理与音乐应用聊天流式响应的接口
     * 使用GET方法，返回文本事件流(MediaType.TEXT_EVENT_STREAM_VALUE)
     *
     * @param message 用户发送的消息内容
     * @param chatId  聊天会话的唯一标识符
     * @return 返回一个Flux<String>类型的响应流，包含聊天内容的实时响应
     */
    @GetMapping(value = "/music_app/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithMusicAppStream(String message, String chatId) { // 定义处理音乐应用聊天流式响应的方法
        return musicApp.doChatWithStream(message, chatId); // 调用musicApp的doChatWithStream方法，传入消息内容和聊天ID，返回流式响应
    }

    /**
     * 处理与音乐应用的SSE（Server-Sent Events）聊天请求
     * 该接口返回一个Flux流，用于向客户端推送服务器发送的事件
     * <p>
     * requestId 用来找到同一次后台任务，lastEventId 表示前端最后收到的事件。连接中断后前端
     * 使用相同 requestId 重连，服务端会从 lastEventId 之后继续发送，而不是重新调用大模型。
     *
     * @param message 用户发送的消息内容
     * @param chatId  聊天会话的唯一标识符
     * @return 返回一个Flux流，包含服务器发送的事件对象，每个事件包含聊天消息块
     */
    @GetMapping(value = "/music_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> doChatWithMusicAppSSE(
            String message,
            @RequestParam(defaultValue = "") String chatId,
            @RequestParam(defaultValue = "user-001") String userId,
            @RequestParam(defaultValue = "VOCAL_COACH") ConversationMode mode,
            @RequestParam(defaultValue = "") String conversationId,
            @RequestParam(defaultValue = "") String requestId,
            @RequestParam(defaultValue = "0") long lastEventId) {
        if (mode != ConversationMode.VOCAL_COACH) {
            return Flux.error(new IllegalArgumentException("声乐教练只支持VOCAL_COACH模式"));
        }
        String stableRequestId = requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
        StreamSession session = streamSessionRegistry.getOrStart(stableRequestId, current -> {
            ConversationTurnCoordinator.ConversationTurn turn;
            try {
                turn = conversationTurnCoordinator.begin(userId, mode, conversationId, message);
            } catch (RuntimeException error) {
                if (dailyAgentCapture != null) dailyAgentCapture.attach(current, message,
                        "music_app/chat/sse", conversationId, mode.name(), musicApp.getConfiguredChatModel());
                throw error;
            }
            if (dailyAgentCapture != null) {
                dailyAgentCapture.attach(current, message, "music_app/chat/sse", turn.conversationId(), mode.name(), musicApp.getConfiguredChatModel());
            }
            try {
                current.emit("conversation", objectMapper.writeValueAsString(turn.started()));
            } catch (Exception exception) {
                current.fail(exception);
                return;
            }
            StringBuilder answer = new StringBuilder();
            AtomicReference<List<AgentReference>> references = new AtomicReference<>(List.of());
            Disposable subscription = musicApp.doChatWithConversationContext(
                            message, turn.context().messages())
                    .subscribe(event -> {
                        if ("message".equals(event.event())) answer.append(event.data());
                        if ("references".equals(event.event())) {
                            try {
                                references.set(objectMapper.readValue(event.data(), new TypeReference<>() { }));
                            } catch (Exception exception) {
                                log.warn("无法解析声乐教练参考资料: conversationId={}", turn.conversationId());
                            }
                        }
                        current.emit(event.event(), event.data());
                    },
                    current::fail,
                    () -> {
                        if (!current.isCancelled() && !answer.isEmpty()) {
                            try {
                                conversationTurnCoordinator.complete(turn, userId, mode, message,
                                        answer.toString(), references.get());
                            } catch (RuntimeException exception) {
                                log.error("声乐教练回答已生成，但历史保存失败: conversationId={}",
                                        turn.conversationId(), exception);
                            }
                        }
                        current.complete();
                    });
            current.attach(subscription);
        });
        return resumableEvents(session, lastEventId);
    }

    /**
     * 处理与MusicApp的SSE聊天请求
     *
     * @param message 用户发送的消息
     * @param chatId  聊天会话ID
     * @return SseEmitter 用于发送服务器发送事件(SSE)的发射器
     */
    @GetMapping("/music_app/chat/sse/emitter")
    public SseEmitter doChatWithLoveAppSseEmitter(String message, String chatId) {
        // 创建一个超时时间较长的 SseEmitter，设置为3分钟
        SseEmitter emitter = new SseEmitter(180000L); // 3分钟超时
        // 获取 Flux 数据流并直接订阅，用于处理聊天消息流
        musicApp.doChatWithStream(message, chatId)
                .subscribe(
                        // 处理每条消息，将接收到的数据块发送给客户端
                        chunk -> {
                            try {
                                emitter.send(chunk);
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        // 处理错误情况，将错误传递给发射器并终止连接
                        emitter::completeWithError,
                        // 处理完成情况，正常完成发射器
                        emitter::complete
                );
        // 返回emitter
        return emitter;
    }

    /**
     * 流式调用 Manus 超级智能体
     * <p>
     * 每个新的 requestId 创建一个 AodingManus 和后台 Future；重复 requestId 复用已有流会话。
     * Future 会附加到会话上，因此调用取消接口时可以中断尚未完成的 Agent 执行。
     *
     * @param message
     * @return
     */
    @GetMapping(value = "/manus/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> doChatWithManus(
            String message,
            @RequestParam(defaultValue = "user-001") String userId,
            @RequestParam(defaultValue = "GENERAL_AGENT") ConversationMode mode,
            @RequestParam(defaultValue = "") String conversationId,
            @RequestParam(defaultValue = "") String requestId,
            @RequestParam(defaultValue = "0") long lastEventId) {
        if (mode != ConversationMode.GENERAL_AGENT) {
            return Flux.error(new IllegalArgumentException("流行演唱 Agent 只支持 GENERAL_AGENT 模式"));
        }
        String stableRequestId = requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
        StreamSession session = streamSessionRegistry.getOrStart(stableRequestId, current -> {
            ConversationTurnCoordinator.ConversationTurn turn;
            try {
                turn = conversationTurnCoordinator.begin(userId, mode, conversationId, message);
            } catch (RuntimeException error) {
                if (dailyAgentCapture != null) dailyAgentCapture.attach(current, message,
                        "manus/chat", conversationId, mode.name(), dashscopeChatModel);
                throw error;
            }
            if (dailyAgentCapture != null) {
                dailyAgentCapture.attach(current, message, "manus/chat", turn.conversationId(), mode.name(), dashscopeChatModel);
            }
            try {
                current.emit("conversation", objectMapper.writeValueAsString(turn.started()));
            } catch (Exception exception) {
                current.fail(exception);
                return;
            }
            AodingManus aodingManus = new AodingManus(
                    allTools, dashscopeChatModel, metricsTracker, agentResponseFinalizer);
            if (toolCallingManager != null) aodingManus.setToolCallingManager(toolCallingManager);
            aodingManus.setLlmRequestExecutor(llmRequestExecutor);
            aodingManus.setMessageList(new java.util.ArrayList<>(turn.context().messages()));
            ConversationPersistenceSink persistenceSink = new ConversationPersistenceSink(
                    current, conversationTurnCoordinator, turn, objectMapper, userId, mode, message);
            Future<?> task = aodingManus.runToSink(message, persistenceSink);
            current.attach(task);
        });
        return resumableEvents(session, lastEventId);
    }

    /** 取消指定流会话及其后台模型或 Agent 任务。 */
    @DeleteMapping("/streams/{requestId}")
    public ResponseEntity<Void> cancelStream(@PathVariable String requestId) {
        return streamSessionRegistry.cancel(requestId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /**
     * 将内部事件转换为 SSE，并在任务执行期间每 15 秒发送心跳，避免中间网络设备关闭空闲连接。
     */
    private Flux<ServerSentEvent<String>> resumableEvents(StreamSession session, long lastEventId) {
        Flux<StreamEvent> source = session.eventsAfter(Math.max(0, lastEventId));
        return source.publish(shared -> {
            Flux<ServerSentEvent<String>> dataEvents = shared.map(event -> ServerSentEvent.<String>builder()
                    .id(Long.toString(event.id()))
                    .event(event.event())
                    .data(event.data())
                    .build());
            Flux<ServerSentEvent<String>> heartbeats = Flux.interval(Duration.ofSeconds(15))
                    .map(tick -> ServerSentEvent.<String>builder()
                            .event("heartbeat")
                            .data(Instant.now().toString())
                            .build())
                    .takeUntilOther(shared.ignoreElements());
            return Flux.merge(dataEvents, heartbeats);
        });
    }

    /**
     * 下载生成的PDF文件
     *
     * @param fileName 文件名
     * @return 文件资源
     */
    @GetMapping("/pdf/download/{fileName}")
    public ResponseEntity<org.springframework.core.io.Resource> downloadPdf(@PathVariable String fileName) {
        String filePath = FileConstant.FILE_SAVE_DIR + "/pdf/" + fileName;
        File file = new File(filePath);
        
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        
        org.springframework.core.io.Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(resource);
    }

}
