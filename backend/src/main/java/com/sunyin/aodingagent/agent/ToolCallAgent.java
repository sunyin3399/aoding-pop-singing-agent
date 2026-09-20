package com.sunyin.aodingagent.agent;

import cn.hutool.core.collection.CollUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.openai.OpenAiChatOptions;
import com.sunyin.aodingagent.agent.model.AgentState;
import com.sunyin.aodingagent.metrics.LlmMetricsTracker;
import com.sunyin.aodingagent.research.WebSearchResult;
import com.sunyin.aodingagent.research.AgentReference;
import com.sunyin.aodingagent.research.ResearchModels;
import com.sunyin.aodingagent.research.finalization.AgentResponseFinalizer;
import com.sunyin.aodingagent.research.finalization.EvidenceBundle;
import com.sunyin.aodingagent.research.finalization.FinalizedAgentResponse;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * 处理工具调用的基础代理类，具体实现了 think 和 act 方法，可以用作创建实例的父类
 * <p>
 * 这个类解决的是“大模型如何安全地使用 Java 工具”的问题。think 方法把聊天历史和可用工具
 * 交给模型，由模型选择工具；act 方法真正执行工具，并把结构化结果追加回上下文。外部研究
 * 只收集抓取成功的引用，工具输出会先压缩再进入执行轨迹，候选知识格式失败最多提示修正一次。
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    private static final ObjectMapper TOOL_RESULT_MAPPER = new ObjectMapper();

    private final ToolCallback[] availableTools;

    private ChatResponse toolCallChatResponse;

    private ToolCallingManager toolCallingManager;

    private final ChatOptions chatOptions;

    private final LlmMetricsTracker metricsTracker;

    private AgentResponseFinalizer responseFinalizer;
    private ResearchModels.ResearchTaskContract researchContract;
    private ResearchModels.ResearchStatus researchStatus;
    private boolean internalKnowledgeSearchUsed;

    // Kept only for binary/source compatibility with the legacy helper; the save tool is no longer agent-exposed.
    private int candidateRepairAttempts;

    public ToolCallAgent(ToolCallback[] availableTools, LlmMetricsTracker metricsTracker) {
        super();
        this.availableTools = java.util.Arrays.stream(availableTools)
                .map(tool -> new ObservedToolCallback(tool, this::emitLiveEvent))
                .toArray(ToolCallback[]::new);
        this.metricsTracker = metricsTracker;
        this.toolCallingManager = ToolCallingManager.builder().build();
        // 关闭 spring-ai 的“模型内部自动执行工具”，让 think() 只返回模型选定的 tool call，
        // 由 act() 手动逐步执行 —— 这样才能维持 ReAct 一步步推进并逐条推送执行轨迹。
        this.chatOptions = OpenAiChatOptions.builder()
                .toolCallbacks(this.availableTools)
                .internalToolExecutionEnabled(false)
                .build();
    }

    public ToolCallAgent(ToolCallback[] availableTools, LlmMetricsTracker metricsTracker,
                         AgentResponseFinalizer responseFinalizer) {
        this(availableTools, metricsTracker);
        this.responseFinalizer = responseFinalizer;
    }

    @Override
    public java.util.concurrent.Future<?> runToSink(String userPrompt, com.sunyin.aodingagent.stream.AgentEventSink sink) {
        // 声明实际安装的回调边界，允许区分“已观测但未调用”和“没有接线”。
        // 下游研究/检索内部调用仍不可见，所以整体不能标 COMPLETE。
        try { sink.emit("trace_capabilities", "{\"toolTraceScope\":\"ToolCallAgent callback boundary only\"}"); }
        catch (RuntimeException ignored) { /* 旁路事件失败不阻止业务运行。 */ }
        return super.runToSink(userPrompt, sink);
    }

    public void setToolCallingManager(ToolCallingManager toolCallingManager) {
        this.toolCallingManager = toolCallingManager;
    }

    /**
     * 处理当前状态并决定下一步行动
     * 该方法通过分析当前消息列表，生成适当的提示并调用聊天客户端获取响应。
     * <p>
     * 根据响应内容决定是否需要调用工具，并记录相关信息。
     * 返回 true 只表示模型选择了工具，并不表示工具已经执行成功。
     *
     * @return 是否需要执行行动  （是否需要调用工具）
     */
    @Override
    public boolean think() {
        // 如果存在下一步提示，则将其添加到消息列表中
        if (getNextStepPrompt() != null && !getNextStepPrompt().isEmpty()) {
            UserMessage userMessage = new UserMessage(getNextStepPrompt());
            getMessageList().add(userMessage);
            setNextStepPrompt(null);
        }
        
        // 如果处于总结状态，添加总结提示
        if (getState() == AgentState.SUMMARIZING) {
            getMessageList().add(new UserMessage("任务已完成，不要继续调用工具，总结整个对话并给出最终回复。"));
        }
        
        // 获取当前消息列表并创建提示对象
        List<Message> messageList = getMessageList();
        Prompt prompt = new Prompt(messageList, chatOptions);
        try {
            long startTime = System.currentTimeMillis();
            // 获取带工具选项的响应
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(getSystemPrompt())
                    .tools(toolsForCurrentStep())
                    .advisors(new com.sunyin.aodingagent.evaluation.PromptDocumentObservationAdvisor(this::emitLiveEvent))
                    .call()
                    .chatResponse();
            metricsTracker.trackLlmCall(chatResponse, startTime);
            // 记录响应，用于 Act
            this.toolCallChatResponse = chatResponse;
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            // 输出提示信息
            String result = assistantMessage.getText();
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            String selectedToolNames = toolCallList.stream()
                    .map(AssistantMessage.ToolCall::name)
                    .collect(Collectors.joining("、"));
            if (result == null || result.isBlank()) {
                if (!toolCallList.isEmpty()) {
                    log.info("{} 本轮未返回可见文本，模型选择了以下 {} 个工具：{}",
                            getName(), toolCallList.size(), selectedToolNames);
                } else {
                    log.info("{} 本轮返回空内容", getName());
                }
            } else {
                log.info("{} 返回文本: {}", getName(), result);
                if (!toolCallList.isEmpty()) {
                    log.info("{} 模型选择了以下 {} 个工具：{}",
                            getName(), toolCallList.size(), selectedToolNames);
                }
            }
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s",
                            toolCall.name(),
                            toolCall.arguments())
                    )
                    .collect(Collectors.joining("\n"));
            log.info(toolCallInfo);
            if (toolCallList.isEmpty() || getState() == AgentState.SUMMARIZING) {
                // 不调用工具时或总结状态下，记录助手消息
                getMessageList().add(assistantMessage);
                return false;
            } else {
                // 需要调用工具时，无需记录助手消息，因为调用工具时会自动记录；
                // “开始执行工具 X”的轨迹由 act() 在执行前实时推送，避免长工具阻塞期间无反馈。
                return true;
            }
        } catch (Exception e) {
            log.error(getName() + "的思考过程遇到了问题: " + e.getMessage());
            emitLiveEvent("model_error", e.getClass().getName());
            getMessageList().add(
                    new AssistantMessage("处理时遇到错误: " + e.getMessage()));
            return false;
        }
    }

    private ToolCallback[] toolsForCurrentStep() {
        if (!internalKnowledgeSearchUsed) return availableTools;
        return java.util.Arrays.stream(availableTools)
                .filter(tool -> !"searchVocalKnowledge".equals(tool.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
    }


    /**
     * 执行工具调用并处理结果
     * 该方法检查是否有工具调用，如果有则执行工具调用，处理执行结果，并返回结果字符串
     * <p>
     * 工具返回值会作为新的对话消息保存，供下一轮 think 判断是否继续。引用、下载文件名和
     * 不同工具对应的后续提示也在这里统一提取。
     *
     * @return 执行结果  ，包含工具调用完成的信息和结果数据
     */
    @Override
    public String act() {
        // 检查是否存在工具调用，如果没有则返回提示信息
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具调用";
        }
        // 长耗时工具（如外部研究）阻塞执行期间没有中间事件；先实时推送“开始执行/进行中”占位，
        // 让前端立即有反馈而不是一直 loading。
        AssistantMessage toolCallMessage = toolCallChatResponse.getResult() == null
                ? null : toolCallChatResponse.getResult().getOutput();
        if (toolCallMessage != null) {
            for (AssistantMessage.ToolCall toolCall : toolCallMessage.getToolCalls()) {
                reportProgress(startLineForTool(toolCall.name()));
            }
        }
        // 调用工具
        Prompt prompt = new Prompt(getMessageList(), chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);

        // 记录消息上下文，conversationHistory 已经包含了助手消息和工具调用返回的结果
        setMessageList(toolExecutionResult.conversationHistory());

        // 当前工具调用的结果
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> summarizeToolExecution(response.name(), response.responseData()))
                .collect(Collectors.joining("\n"));

        // 判断是否调用了终止工具
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> "doTerminate".equals(response.name()));
        if (terminateToolCalled) {
            setState(AgentState.SUMMARIZING);
        }
        
        // 根据执行的工具动态拼接nextStepPrompt
        // 根据工具类型补充下一轮提示，例如要求 Agent 尊重研究的成功、降级或失败状态。
        StringBuilder nextStepPromptBuilder = new StringBuilder();
        for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
            String toolName = response.name();
            String responseData = response.responseData();
            traceStep(toolTraceLine(toolName, responseData));
            if (toolName.startsWith("searchWeb")) {
                collectSearchReferences(responseData);
            }
            if ("researchExternal".equals(toolName)) {
                collectResearchReferences(responseData);
            }
            if ("searchVocalKnowledge".equals(toolName)) {
                internalKnowledgeSearchUsed = true;
                collectInternalKnowledgeReferences(responseData);
            }
            if ("generatePDF".equals(toolName)) {
                int fileNameStart = responseData.indexOf("文件名: ");
                if (fileNameStart != -1) {
                    int fileNameEnd = responseData.indexOf("，", fileNameStart);
                    if (fileNameEnd != -1) {
                        String fileName = responseData.substring(fileNameStart + 5, fileNameEnd).trim();
                        setDownloadFileName(fileName);
                    }
                }
            }
            if ("prepareTrainingPlanForm".equals(toolName)) {
                setTrainingPlanFormPayload(responseData);
            }
            String toolPrompt = getToolSpecificPrompt(toolName, responseData);
            if (toolPrompt != null) {
                nextStepPromptBuilder.append(toolPrompt).append("\n\n");
            }
        }
        if (nextStepPromptBuilder.length() > 0) {
            setNextStepPrompt(nextStepPromptBuilder.toString().trim());
        }
        
        log.info(results);
        return results;

    }

    /** 工具开始执行前实时推送的“进行中”占位文案。 */
    private String startLineForTool(String toolName) {
        if ("researchExternal".equals(toolName)) {
            return "外部研究进行中：正在联网搜索并核验来源，可能耗时较长，请稍候…";
        }
        if ("searchVocalKnowledge".equals(toolName)) {
            return "正在调用内部知识库检索…";
        }
        return "正在执行工具：" + toolName + "…";
    }

    /** 把单个工具的执行结果压成一条简洁轨迹；本地知识库检索特别强调“命中/未命中”。 */
    private String toolTraceLine(String toolName, String responseData) {
        if (responseData == null || responseData.isBlank()) {
            return "工具 " + toolName + " 执行完成";
        }
        if ("searchVocalKnowledge".equals(toolName)) {
            try {
                JsonNode root = TOOL_RESULT_MAPPER.readTree(responseData);
                JsonNode citations = root.path("data").path("citations");
                if (citations.isArray() && citations.size() > 0) {
                    return "本地知识库命中 " + citations.size() + " 条，已纳入上下文";
                }
                return "本地知识库未命中，转外部检索";
            } catch (Exception exception) {
                return "调用本地知识库检索完成";
            }
        }
        return summarizeToolExecution(toolName, responseData);
    }

    private String summarizeToolExecution(String toolName, String responseData) {
        if ("researchExternal".equals(toolName)) {
            try {
                JsonNode data = TOOL_RESULT_MAPPER.readTree(responseData).path("data");
                long verified = 0;
                if (data.path("sources").isArray()) {
                    for (JsonNode source : data.path("sources")) {
                        if ("SCRAPED".equals(source.path("scrapeStatus").asText())) verified++;
                    }
                }
                return "外部研究完成：" + data.path("status").asText("UNKNOWN")
                        + "，已核验 " + verified + " 个来源，搜索 "
                        + data.path("searchCalls").asInt() + " 次，抓取 "
                        + data.path("scrapeCalls").asInt() + " 次";
            } catch (Exception exception) {
                return "外部研究已完成，结果摘要解析失败";
            }
        }
        String compact = responseData == null ? "" : responseData.replaceAll("\\s+", " ").trim();
        if (compact.length() > 240) compact = compact.substring(0, 240) + "…";
        return "工具 " + toolName + " 执行完成" + (compact.isBlank() ? "" : "：" + compact);
    }

    void collectSearchReferences(String responseData) {
        try {
            WebSearchResult result = TOOL_RESULT_MAPPER.readValue(responseData, WebSearchResult.class);
            if (result.success()) getReferences().addAll(result.toReferences());
        } catch (Exception exception) {
            log.warn("忽略无法解析的结构化搜索结果: {}", exception.getMessage());
        }
    }

    void collectResearchReferences(String responseData) {
        try {
            JsonNode data = TOOL_RESULT_MAPPER.readTree(responseData).path("data");
            JsonNode sources = data.path("sources");
            if (!data.path("contract").isMissingNode()) {
                researchContract = TOOL_RESULT_MAPPER.treeToValue(data.path("contract"), ResearchModels.ResearchTaskContract.class);
            }
            if (!data.path("status").isMissingNode()) {
                researchStatus = ResearchModels.ResearchStatus.valueOf(data.path("status").asText());
            }
            List<AgentReference> collected = new ArrayList<>();
            if (sources.isArray()) {
                for (JsonNode source : sources) {
                    if (!"SCRAPED".equals(source.path("scrapeStatus").asText())) continue;
                    collected.add(new AgentReference(
                            source.path("id").asText(), source.path("title").asText(),
                            source.path("url").asText(), source.path("snippet").asText(),
                            AgentReference.ReferenceStatus.SCRAPED, null));
                }
            }
            getReferences().addAll(collected);
            // 候选知识已生成并进入待审：向前端推一条独立事件，便于“知识审核”入口给出可见提示。
            JsonNode candidate = data.path("candidateKnowledge");
            if (candidate.isMissingNode()) {
                candidate = TOOL_RESULT_MAPPER.readTree(responseData).path("candidateKnowledge");
            }
            if (!candidate.isMissingNode()
                    && "PENDING_REVIEW".equals(candidate.path("status").asText())) {
                emitLiveEvent("candidateKnowledge", TOOL_RESULT_MAPPER.writeValueAsString(candidate));
            }
        } catch (Exception exception) {
            log.warn("忽略无法解析的研究结果引用: {}", exception.getMessage());
        }
    }

    /**
     * 收集内部 RAG 返回的引用。内部文档链接继续由原 references SSE 事件发送，
     * 同时进入最终交付物的引用白名单，避免结构化收尾阶段把内部证据丢掉。
     */
    void collectInternalKnowledgeReferences(String responseData) {
        try {
            JsonNode citations = TOOL_RESULT_MAPPER.readTree(responseData).path("data").path("citations");
            List<AgentReference> collected = new ArrayList<>();
            if (citations.isArray()) {
                for (JsonNode citation : citations) {
                    collected.add(new AgentReference(
                            citation.path("id").asText(), citation.path("title").asText(),
                            citation.path("url").asText(), citation.path("excerpt").asText(),
                            AgentReference.ReferenceStatus.INTERNAL_APPROVED, null));
                }
            }
            getReferences().addAll(collected);
        } catch (Exception exception) {
            log.warn("忽略无法解析的内部知识引用: {}", exception.getMessage());
        }
    }

    /** 只验收外部研究交付物；内部 RAG 回答保留 Agent 原文与独立引用。 */
    @Override
    protected String finalizeAnswer(String answer) {
        if (responseFinalizer == null || researchContract == null) return answer;
        FinalizedAgentResponse finalized = responseFinalizer.finalizeResponse(
                getOriginalUserPrompt(), answer,
                new EvidenceBundle(getReferences().snapshot(), researchContract, researchStatus));
        getReferences().clear();
        getReferences().addAll(finalized.references());
        return finalized.markdown();
    }

    private String getToolSpecificPrompt(String toolName, String responseData) {
        return switch (toolName) {
            case "searchWeb" -> "Web search finished.\n\nEvaluate results,If some search results are irrelevant to the user's query, do not include them in the context.\n\nIf the filtered search results are insufficient, execute the searchWeb tool again.\n\nIf the search results are highly irrelevant to the user's needs, use searchWeb tool again with a more precise description.\n\nThe retry process will terminate after a maximum of 3 attempts.\n\nIf the searchWeb tool is unavailable, try to use fallback web search tool.";
            case "scrapeWebPage" -> "Web scraping finished.\n\nAnalyze the scraped content.\n\nExtract content from at least three webpages and integrate them,If sufficient, prepare final answer.\nOtherwise use another tool.";
            case "researchExternal" -> researchAnswerPrompt(responseData);
            case "executeTerminalCommand" -> "Terminal command executed.\n\nReview the command output.\n\nIf sufficient, prepare final answer.\nOtherwise use another tool.";
            case "readFile" -> "File read completed.\n\nAnalyze the file content.\n\nIf sufficient, prepare final answer.\nOtherwise use another tool.";
            case "writeFile" -> "File write completed.\n\nVerify the file was written correctly.\n\nIf sufficient, prepare final answer.\nOtherwise use another tool.";
            case "downloadResource" -> "Resource downloaded.\n\nVerify the downloaded content.\n\nIf sufficient, prepare final answer.\nOtherwise use another tool.";
            case "generatePDF" -> "PDF generated.\n\nConfirm the PDF generation.\n\nIf sufficient, prepare final answer.\nOtherwise use another tool.";
            default -> null;
        };
    }

    private String researchAnswerPrompt(String responseData) {
        try {
            JsonNode root = TOOL_RESULT_MAPPER.readTree(responseData);
            JsonNode data = root.path("data");
            String deliverable = data.path("contract").path("deliverable").asText("RESEARCH_SUMMARY");
            int minimumItems = data.path("contract").path("minimumItems").asInt(1);
            String guidance = data.path("answerGuidance").asText("");
            JsonNode candidate = data.path("candidateKnowledge");
            String candidateStatus = candidate.path("status").asText("NOT_ELIGIBLE");
            String candidateId = candidate.path("candidateId").asText("");
            String candidateMessage = candidate.path("message").asText("");
            return "外部研究已完成。交付物类型为 " + deliverable + "，最终交付条目不少于 " + minimumItems + " 项。" + guidance
                    + " 只使用返回的 SCRAPED 来源作为证据；来源与最终条目是两层数据，不要把网页标题直接当成答案条目。"
                    + " 若状态为 DEGRADED，说明证据缺口但仍优先回答用户问题。歌曲推荐允许以稳定的通用声乐知识补足数量，并明确标注未逐项联网核验；不得为补充内容虚构链接。若为 FAILED，不得编造来源或链接。"
                    + " 候选知识真实处理状态=" + candidateStatus
                    + (candidateId.isBlank() ? "" : "，candidateId=" + candidateId)
                    + (candidateMessage.isBlank() ? "" : "，说明=" + candidateMessage)
                    + "。只有 PENDING_REVIEW 才能说已提交审核；其他状态不得说已提交或将提交。";
        } catch (Exception exception) {
            return "外部研究已完成。只使用已核验来源回答，根据用户真正需要的交付物自然组织内容，不要机械套用固定字段。";
        }
    }

    private String candidateRepairPrompt(String responseData) {
        try {
            JsonNode result = TOOL_RESULT_MAPPER.readTree(responseData);
            // 候选知识结构不合格时只允许提醒模型修正一次，避免反复保存和消耗 token。
            if ("INVALID_CANDIDATE".equals(result.path("code").asText()) && candidateRepairAttempts < 1) {
                candidateRepairAttempts++;
                return "候选知识格式校验未通过。根据 error 修正文档结构并且只重试一次；若仍失败，停止保存并向用户说明。";
            }
        } catch (Exception exception) {
            log.warn("忽略无法解析的候选知识结果: {}", exception.getMessage());
        }
        return null;
    }

}
