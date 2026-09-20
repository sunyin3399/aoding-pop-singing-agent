package com.sunyin.aodingagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MusicAppDocumentLoaderTest {

    @Test
    void tokenizesMarkdownAndGeneratesStablePgVectorCompatibleIds() {
        MusicAppDocumentLoader loader = new MusicAppDocumentLoader(
                new PathMatchingResourcePatternResolver(),
                new MarkdownKnowledgeDocumentFactory(500, 200));

        List<Document> first = loader.loadMarkdowns();
        List<Document> second = loader.loadMarkdowns();

        assertThat(first).hasSizeGreaterThanOrEqualTo(5);
        assertThat(first).extracting(Document::getId)
                .containsExactlyElementsOf(second.stream().map(Document::getId).toList());
        assertThat(first).allSatisfy(document -> {
            assertThatCode(() -> UUID.fromString(document.getId()));
            assertThat(document.getMetadata())
                    .containsKeys("filename", "documentTitle", "sectionPath", "sectionTitle",
                            "headingLevel", "chunkIndex", "partIndex", "partCount", "contentHash");
            assertThat(document.getText()).isNotBlank();
        });
    }

    @Test
    void keepsHeadingContextInEverySemanticChunk() {
        MarkdownKnowledgeDocumentFactory factory = new MarkdownKnowledgeDocumentFactory(500, 10);

        List<Document> documents = factory.splitMarkdown("歌曲.md", """
                # 林俊杰歌曲
                ## 高难度
                ### 《黑夜问白天》
                难点包括连续高音、换声压力和情绪转换。
                ### 《新地球》
                难点包括密集高音和完整演唱耐力。
                """, java.util.Map.of());

        assertThat(documents).hasSize(2);
        assertThat(documents.get(0).getText())
                .contains("文档：林俊杰歌曲", "章节：高难度 > 《黑夜问白天》", "连续高音");
        assertThat(documents.get(0).getMetadata())
                .containsEntry("sectionPath", "高难度 > 《黑夜问白天》")
                .containsEntry("sectionTitle", "《黑夜问白天》");
        assertThat(documents.get(1).getMetadata())
                .containsEntry("sectionPath", "高难度 > 《新地球》");
    }

    private void assertThatCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        org.assertj.core.api.Assertions.assertThatCode(callable).doesNotThrowAnyException();
    }
}
