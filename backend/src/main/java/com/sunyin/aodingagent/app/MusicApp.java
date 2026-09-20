package com.sunyin.aodingagent.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.advisor.MyLoggerAdvisor;
import com.sunyin.aodingagent.memory.RedisChatMemory;
import com.sunyin.aodingagent.metrics.LlmMetricsTracker;
import com.sunyin.aodingagent.knowledge.Citation;
import com.sunyin.aodingagent.knowledge.VectorStoreVocalKnowledgeRetriever;
import com.sunyin.aodingagent.research.AgentReference;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;
import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY;

@Component
@Slf4j
public class MusicApp {

    @Resource
    private VectorStore vectorStore;

//    @Resource
//    private Advisor musicAppRagCloudAdvisor;

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private LlmMetricsTracker metricsTracker;

    private final ChatModel configuredChatModel;

    /** 旁路采集读取本路由实际使用的模型默认配置，不调用模型。 */
    public ChatModel getConfiguredChatModel() { return configuredChatModel; }

    private final ChatClient chatClient;
    private final ChatClient conversationChatClient;
    private final VectorStoreVocalKnowledgeRetriever knowledgeRetriever;
    private final ObjectMapper objectMapper;

    @Value("${app.rag.top-k:5}")
    private int ragTopK;

    @Value("${app.rag.similarity-threshold:0.70}")
    private double ragSimilarityThreshold;

    private static final String SYSTEM_PROMPT = "扮演深耕流行演唱领域的专家，为用户解决流行演唱难题。" +
            "如果用户描述不清，引导用户详述自己所处困境、遇到的问题及自身想法，以便给出具体解决方案。" +
            "如果用户提供了自己录制的音频解析结果，结合常见的高音挤卡、声带疲劳、声音稳定性等问题，推测用户遇到的具体问题。" +
            "如果有内置的RAG知识库，优先结合检索给出相应问题的建议。" +
            "不要向用户透露“知识库、RAG、检索结果、上下文为空、内部资料”等系统实现细节。";


    record MusicReport(String title, List<String> suggestions) {
    }


    public MusicApp(
            ChatModel dashscopeChatModel,
            RedisChatMemory redisChatMemory,
            VectorStoreVocalKnowledgeRetriever knowledgeRetriever,
            ObjectMapper objectMapper
    ) {
        this.configuredChatModel = dashscopeChatModel;
        this.knowledgeRetriever = knowledgeRetriever;
        this.objectMapper = objectMapper;
        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        new MessageChatMemoryAdvisor(redisChatMemory),
                        new MyLoggerAdvisor()
                )
                .build();
        // 新版多会话由统一ConversationStore负责持久化，避免和旧MessageChatMemoryAdvisor重复保存。
        conversationChatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
    }

    public String doChat(String message, String chatId) {
        ChatResponse response = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    /**
     * 请求模型后生成指定格式的回复
     *
     * @param message 用户消息
     * @param chatId  会话id
     * @return 指定格式回复
     */
    public MusicReport doChatWithReport(String message, String chatId) {
        MusicReport music = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次对话后都要生成音乐学习分析，标题为{用户名}的学习报告，内容为建议列表")
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .call()
                .entity(MusicReport.class);
        log.info("musicReport: {}", music);
        return music;
    }

    /**
     * 调用本地rag知识库检索
     *
     * @param message 用户消息
     * @param chatId  会话id
     * @return 模型回答
     */
    public String doChatWithRag(String message, String chatId) {
        long startTime = System.currentTimeMillis();

        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                // 应用知识库问答
                .advisors(ragAdvisor())
                .call()
                .chatResponse();
        metricsTracker.trackLlmCall(chatResponse, startTime);

        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }


    /**
     * 与云RAG（检索增强生成）系统进行聊天交互的方法
     * 该方法接收用户消息和聊天ID，调用云服务生成响应
     *
     * @param message 用户输入的消息内容
     * @param chatId  聊天会话的唯一标识符
     * @return 返回AI生成的聊天响应内容
     */
    public String doChatWithCloudRag(String message, String chatId) {
        // 使用chatClient构建聊天请求，配置各种参数和顾问
        ChatResponse chatResponse = chatClient
                .prompt()  // 创建提示词构建器
                .user(message)  // 设置用户消息
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)  // 设置会话ID参数
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))  // 设置记忆检索大小参数
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                // 应用增强检索服务（云知识库服务）
//                .advisors(musicAppRagCloudAdvisor)  // 执行调用
                .call()  // 获取聊天响应对象
                // 从响应对象中提取文本内容
                .chatResponse();
        // 记录响应内容到日志
        String content = chatResponse.getResult().getOutput().getText();
        // 返回响应内容
        log.info("content: {}", content);
        return content;
    }


    /**
     * 使用工具进行聊天交互的方法
     *
     * @param message 用户输入的消息内容
     * @param chatId  聊天会话的唯一标识符
     * @return 返回AI生成的回复内容
     */
    public String doChatWithTools(String message, String chatId) {
        // 使用chatClient构建聊天请求
        ChatResponse response = chatClient
                .prompt() // 创建提示构建器
                .user(message) // 设置用户消息
                // 配置顾问参数，包括会话ID和记忆检索大小
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .tools(allTools)
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    /**
     * 使用流式方式与AI进行对话
     * 该方法通过chatClient发送用户消息，并获取流式响应
     *
     * @param message 用户输入的消息内容
     * @param chatId  聊天会话的唯一标识符，用于维护对话上下文
     * @return Flux<String> 返回一个包含流式响应字符串的Flux流
     */
    public Flux<String> doChatWithStream(String message, String chatId) {
        return doChatWithStreamEvents(message, chatId)
                .filter(event -> "message".equals(event.event()))
                .map(MusicStreamEvent::data);
    }

    /**
     * 流式返回回答，并在结束前输出 references SSE 事件。
     */
    public Flux<MusicStreamEvent> doChatWithStreamEvents(String message, String chatId) {
        return streamEvents(chatClient, message, chatId, List.of(), true);
    }

    /** 新版会话入口：历史已经过Token预算和压缩，不再写入旧chat:memory键空间。 */
    public Flux<MusicStreamEvent> doChatWithConversationContext(String message, List<Message> history) {
        return streamEvents(conversationChatClient, message, null, history, false);
    }

    private Flux<MusicStreamEvent> streamEvents(ChatClient client, String message, String chatId,
                                                List<Message> history, boolean legacyMemory) {
        AtomicReference<List<Document>> retrieved = new AtomicReference<>(List.of());
        // 使用chatClient的prompt方法创建对话提示
        // 调用user方法设置用户消息
        // 使用advisors方法配置对话参数，包括会话ID和记忆检索数量
        // stream()方法启用流式响应
        // content()方法提取响应内容
        ChatClient.ChatClientRequestSpec prompt = client.prompt().messages(history).user(message);
        if (legacyMemory) {
            prompt = prompt.advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                    .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10));
        }
        Flux<MusicStreamEvent> answerEvents = prompt
                // 应用知识库问答
                .advisors(ragAdvisor())
                .stream()
                .chatResponse()
                .handle((response, sink) -> {
                    List<Document> documents = retrievedDocuments(response);
                    if (!documents.isEmpty() && retrieved.get().isEmpty()) {
                        retrieved.set(documents);
                        log.info("RAG 检索命中 {} 个片段: {}", documents.size(), documents.stream()
                                .map(document -> "%s(%.3f)".formatted(
                                        document.getMetadata().getOrDefault("filename", "未知文档"),
                                        document.getScore() == null ? 0.0 : document.getScore()))
                                .toList());
                    }
                    if (response.getResult() != null && response.getResult().getOutput() != null) {
                        String content = response.getResult().getOutput().getText();
                        if (content != null && !content.isEmpty()) {
                            sink.next(new MusicStreamEvent("message", content));
                        }
                    }
                });

        return answerEvents.concatWith(Flux.defer(() -> retrieved.get().isEmpty()
                ? Flux.empty()
                : Flux.just(new MusicStreamEvent("references", formatReferences(retrieved.get())))));
    }

    private QuestionAnswerAdvisor ragAdvisor() {
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(ragTopK)
                .similarityThreshold(ragSimilarityThreshold)
                .build();
        return new QuestionAnswerAdvisor(vectorStore, searchRequest);
    }

    private List<Document> retrievedDocuments(ChatResponse response) {
        Object value = response.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(Document.class::isInstance).map(Document.class::cast).toList();
    }

    private String formatReferences(List<Document> documents) {
        // 同一份内部文档通常被切成多个 chunk 检索命中；前端展示时只保留每个文档得分最高的一条，
        // 避免同一文档重复出现在参考资料里。内部上下文仍保留全部命中，不影响回答质量。
        Map<String, Citation> bestPerDoc = new LinkedHashMap<>();
        for (Document document : documents) {
            Citation citation = knowledgeRetriever.toCitation(document);
            bestPerDoc.merge(citation.title(), citation, (existing, incoming) ->
                    relevance(incoming) > relevance(existing) ? incoming : existing);
        }

        List<AgentReference> references = bestPerDoc.values().stream().map(citation -> new AgentReference(
                citation.id(), citation.title(), citation.url(), citation.excerpt(),
                AgentReference.ReferenceStatus.INTERNAL_APPROVED, "内部知识库")).toList();
        try {
            return objectMapper.writeValueAsString(references);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化RAG参考资料", exception);
        }
    }

    private double relevance(Citation citation) {
        return citation.relevance() == null ? 0.0 : citation.relevance();
    }

    public record MusicStreamEvent(String event, String data) { }


}
