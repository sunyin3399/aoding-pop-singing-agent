package com.sunyin.aodingagent.controller;

import com.sunyin.aodingagent.rag.MusicAppDocumentLoader;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeAdminControllerTest {

    @Test
    void replacesExistingFileChunksBeforeBatchInsert() {
        VectorStore vectorStore = mock(VectorStore.class);
        MusicAppDocumentLoader loader = mock(MusicAppDocumentLoader.class);
        when(loader.loadMarkdowns()).thenReturn(List.of(
                document("气息片段 1", "气息.md"),
                document("气息片段 2", "气息.md"),
                document("换声区片段", "换声区.md")));
        KnowledgeAdminController controller = new KnowledgeAdminController(vectorStore, loader);

        String result = controller.initKnowledgeBase();

        assertThat(result).isEqualTo("知识库初始化成功");
        verify(vectorStore).delete("filename == '气息.md'");
        verify(vectorStore).delete("filename == '换声区.md'");
        verify(vectorStore).add(anyList());
    }

    private Document document(String text, String filename) {
        return new Document(UUID.randomUUID().toString(), text, Map.of("filename", filename));
    }
}
