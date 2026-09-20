package com.sunyin.aodingagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.knowledge.Citation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesTypedSuccessWithoutAnError() throws Exception {
        ToolResult<Citation> result = ToolResult.success("KNOWLEDGE_FOUND", new Citation(
                "doc-1",
                Citation.CitationType.INTERNAL_KNOWLEDGE,
                "换声区",
                "/api/ai/knowledge/documents/doc-1",
                "摘要",
                0.91
        ));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(result));

        assertTrue(json.get("success").asBoolean());
        assertEquals("KNOWLEDGE_FOUND", json.get("code").asText());
        assertEquals("doc-1", json.at("/data/id").asText());
        assertTrue(json.get("error").isNull());
        assertFalse(json.get("retryable").asBoolean());
    }

    @Test
    void serializesFailureWithoutData() throws Exception {
        ToolResult<Citation> result = ToolResult.failure("SEARCH_TIMEOUT", "检索超时", true);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(result));

        assertFalse(json.get("success").asBoolean());
        assertTrue(json.get("data").isNull());
        assertEquals("检索超时", json.get("error").asText());
        assertTrue(json.get("retryable").asBoolean());
    }
}
