package com.sunyin.aodingagent.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import java.util.*;
import java.util.function.BiConsumer;

/** Last application advisor: observes the post-pruning tool content sent to ChatModel.call.
 * Does not assert provider receipt, model attention, or downstream use of documents.
 */
public final class PromptDocumentObservationAdvisor implements CallAroundAdvisor {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final BiConsumer<String, String> observer;
    public PromptDocumentObservationAdvisor(BiConsumer<String, String> observer) { this.observer = observer; }
    @Override public String getName() { return "eval final tool-message IDs"; }
    @Override public int getOrder() { return Integer.MAX_VALUE - 1; }

    @Override public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        Map<String, Object> event = new LinkedHashMap<>();
        try {
            // M6's terminal CallAroundAdvisor (MAX_VALUE) only invokes toPrompt then ChatModel.call.
            // Refuse an ambiguous observation if another application advisor can still mutate messages.
            if (request.advisors().stream().anyMatch(a -> a != this && a instanceof CallAroundAdvisor
                    && a.getOrder() >= getOrder()
                    && !a.getClass().getName().equals("org.springframework.ai.chat.client.DefaultChatClient$DefaultChatClientRequestSpec$1")))
                throw new IllegalArgumentException();
            Set<String> ids = new LinkedHashSet<>();
            for (var message : request.toPrompt().getInstructions()) {
                if (!(message instanceof ToolResponseMessage tools)) continue;
                for (var response : tools.getResponses()) {
                    if (!"searchVocalKnowledge".equals(response.name())) continue;
                    var root = JSON.readTree(response.responseData());
                    if (root == null) throw new IllegalArgumentException();
                    var data = root.path("data");
                    var citations = data.path("citations");
                    String status = data.path("status").asText();
                    boolean noHit = "NO_KNOWLEDGE_HIT".equals(status);
                    if (!root.path("success").isBoolean() || !citations.isArray() || citations.size() > 128
                            || (noHit && root.path("success").asBoolean())
                            || (!noHit && !("FOUND".equals(status) && root.path("success").isBoolean()
                            && root.path("success").asBoolean()))
                            || (noHit && !citations.isEmpty())) throw new IllegalArgumentException();
                    for (var citation : citations) {
                        var id = citation.path("id");
                        if (!id.isTextual() || !id.asText().matches("[\\p{L}\\p{N}_.:#-]{1,256}")
                                || id.asText().startsWith("sk-")) throw new IllegalArgumentException();
                        ids.add(id.asText());
                    }
                    if (ids.size() > 128) throw new IllegalArgumentException();
                }
            }
            event.put("promptDocumentIds", List.copyOf(ids));
        } catch (Exception ignored) {
            event.put("promptDocumentIds", "unavailable");
            event.put("reason", "invalid_truncated_failed_tool_content_or_later_advisor");
        }
        try { observer.accept("eval_prompt_documents", JSON.writeValueAsString(event)); }
        catch (Exception ignored) { /* Observation must not affect the business request. */ }
        return chain.nextAroundCall(request);
    }
}
