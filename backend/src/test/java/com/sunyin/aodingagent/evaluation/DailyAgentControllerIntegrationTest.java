package com.sunyin.aodingagent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.app.MusicApp;
import com.sunyin.aodingagent.controller.AiController;
import com.sunyin.aodingagent.conversation.ConversationModels.ConversationMode;
import com.sunyin.aodingagent.conversation.ConversationModels.ConversationStarted;
import com.sunyin.aodingagent.conversation.ConversationModels.PreparedConversationContext;
import com.sunyin.aodingagent.conversation.ConversationTurnCoordinator;
import com.sunyin.aodingagent.metrics.LlmMetricsTracker;
import com.sunyin.aodingagent.stream.StreamSessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实控制器/Registry/Manus执行链，外部模型与持久化边界为假实现；不调用收费服务。 */
class DailyAgentControllerIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AiController controller = new AiController();
    private final ChatModel model = mock(ChatModel.class);
    private final MusicApp music = mock(MusicApp.class);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private Path directory;
    private DailyAgentCapture capture;

    @BeforeEach
    void setUp() throws Exception {
        directory = Path.of("target/agent-eval-integration", UUID.randomUUID().toString());
        var coordinator = mock(ConversationTurnCoordinator.class);
        when(coordinator.begin(anyString(), any(), anyString(), anyString())).thenReturn(
                new ConversationTurnCoordinator.ConversationTurn(
                        new ConversationStarted("test-conversation", "fixture", Instant.now()), null,
                        new PreparedConversationContext(List.of(), 0, false)));
        when(music.getConfiguredChatModel()).thenReturn(model);
        when(model.getDefaultOptions()).thenReturn(ChatOptions.builder().model("fake-boundary").build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("Boundary-generated answer")))));
        when(music.doChatWithConversationContext(anyString(), anyList())).thenReturn(
                Flux.just(new MusicApp.MusicStreamEvent("message", "coach answer")));
        capture = captureTo(directory);
        Map<String, Object> fields = Map.of(
                "objectMapper", mapper, "streamSessionRegistry", new StreamSessionRegistry(),
                "conversationTurnCoordinator", coordinator, "dashscopeChatModel", model,
                "allTools", new ToolCallback[0], "metricsTracker", mock(LlmMetricsTracker.class),
                "musicApp", music, "llmRequestExecutor", executor, "dailyAgentCapture", capture);
        fields.forEach((key, value) -> ReflectionTestUtils.setField(controller, key, value));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private DailyAgentCapture captureTo(Path path) {
        return new DailyAgentCapture(new MockEnvironment().withProperty("app.agent-eval.enabled", "true")
                .withProperty("app.agent-eval.dir", path.toString()));
    }

    private Flux<ServerSentEvent<String>> manus(String id) {
        return controller.doChatWithManus("test question", "test-user", ConversationMode.GENERAL_AGENT, "", id, 0);
    }

    private Flux<ServerSentEvent<String>> coach(String id) {
        return controller.doChatWithMusicAppSSE("coach question", "", "test-user", ConversationMode.VOCAL_COACH, "", id, 0);
    }

    private List<ServerSentEvent<String>> finish(Flux<ServerSentEvent<String>> stream) {
        return stream.collectList().block(Duration.ofSeconds(10));
    }

    private List<JsonNode> rows() throws Exception {
        capture.awaitWrites();
        try (var files = Files.list(directory.resolve("raw"))) {
            return files.filter(path -> path.toString().endsWith(".json")).map(path -> {
                try { return mapper.readTree(path.toFile()); }
                catch (Exception error) { throw new AssertionError(error); }
            }).toList();
        }
    }

    @Test
    void bothDailyRoutesCaptureOnceAcrossReconnect() throws Exception {
        finish(manus("m1"));
        finish(coach("c1"));
        finish(manus("m1"));
        finish(coach("c1"));
        var records = rows();
        assertEquals(2, records.size());
        assertEquals(java.util.Set.of("manus/chat", "music_app/chat/sse"),
                records.stream().map(row -> row.path("route").asText()).collect(java.util.stream.Collectors.toSet()));
        for (var row : records) {
            assertEquals("SUCCESS", row.path("terminalStatus").asText());
            assertFalse(row.path("finalAnswer").asText().isBlank());
            assertEquals("fake-boundary", row.path("model").asText());
            assertEquals("PARTIAL", row.path("traceCompleteness").asText());
            if (row.path("agentType").asText().equals("AodingManus")) {
                assertTrue(row.path("toolTrace").isArray());
                assertEquals(0, row.path("toolTrace").size());
                assertEquals("ToolCallAgent callback boundary only", row.path("toolTraceScope").asText());
            } else {
                assertEquals("unavailable", row.path("toolTrace").asText());
                assertTrue(row.path("toolTraceScope").asText().contains("unobserved"));
            }
        }
        assertEquals(1, records.stream().map(r -> r.path("scenarioKey").asText()).distinct().count());
        assertEquals(java.util.Set.of(1, 2), records.stream().map(r -> r.path("turnIndex").asInt()).collect(java.util.stream.Collectors.toSet()));
        for (var record : records) {
            assertEquals(2, record.path("captureSchemaVersion").asInt());
            assertFalse(record.toString().contains("test-user"));
            assertFalse(record.toString().contains("test-conversation"));
            assertNotEquals("unavailable", record.path("mode").asText());
        }
        verify(model, times(1)).call(any(Prompt.class));
        verify(music, times(1)).doChatWithConversationContext(anyString(), anyList());
        System.out.println("FAKE_BOUNDARY_CAPTURE_DIR=" + directory.toAbsolutePath());
    }

    @Test
    void bothRoutesRecordModelFailuresIncludingSwallowedManusError() throws Exception {
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("provider down"));
        when(music.doChatWithConversationContext(anyString(), anyList())).thenReturn(Flux.error(new IllegalStateException("provider down")));
        finish(manus("m-error"));
        finish(coach("c-error"));
        var records = rows();
        assertEquals(2, records.size());
        assertTrue(records.stream().allMatch(row -> "ERROR".equals(row.path("terminalStatus").asText())));
        assertTrue(records.stream().allMatch(row -> !row.path("errorType").isNull()));
    }

    @Test
    void cancellationOfBothRoutesIsRecordedWithoutDuplicateTerminalRows() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("late answer"))));
        });
        when(music.doChatWithConversationContext(anyString(), anyList())).thenReturn(Flux.never());
        var manusStream = manus("m-cancel");
        var coachStream = coach("c-cancel");
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(204, controller.cancelStream("m-cancel").getStatusCode().value());
            assertEquals(204, controller.cancelStream("c-cancel").getStatusCode().value());
            assertTrue(finish(manusStream).stream().anyMatch(event -> "cancelled".equals(event.event())));
            assertTrue(finish(coachStream).stream().anyMatch(event -> "cancelled".equals(event.event())));
        } finally {
            release.countDown();
        }
        var records = rows();
        assertEquals(2, records.size());
        assertTrue(records.stream().allMatch(row -> "CANCELLED".equals(row.path("terminalStatus").asText())));
    }

    @Test
    void diskFailureDoesNotChangeEitherRouteResponse() throws Exception {
        Files.createDirectories(directory);
        Path notDirectory = directory.resolve("not-a-directory");
        Files.writeString(notDirectory, "fixture");
        capture = captureTo(notDirectory);
        ReflectionTestUtils.setField(controller, "dailyAgentCapture", capture);
        assertTrue(finish(manus("m-io")).stream().anyMatch(event -> "final".equals(event.event())));
        assertTrue(finish(coach("c-io")).stream().anyMatch(event -> "message".equals(event.event())));
        capture.awaitWrites();
        assertTrue(capture.writeFailures() >= 2);
    }
}
