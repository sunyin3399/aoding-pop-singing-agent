package com.sunyin.aodingagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将 Markdown 按标题语义切片，再对过长章节做 token 级二次切分。 */
@Component
public class MarkdownKnowledgeDocumentFactory {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    private final TokenTextSplitter tokenTextSplitter;
    private final int chunkSize;

    public MarkdownKnowledgeDocumentFactory(
            @Value("${app.rag.chunk-size:500}") int chunkSize,
            @Value("${app.rag.min-chunk-size-chars:200}") int minChunkSizeChars
    ) {
        this.chunkSize = chunkSize;
        this.tokenTextSplitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(minChunkSizeChars)
                .withMinChunkLengthToEmbed(10)
                .withMaxNumChunks(1000)
                .withKeepSeparator(true)
                .build();
    }

    public List<Document> splitMarkdown(String fileName, String markdown, Map<String, Object> metadata) {
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n').trim();
        String fullContentHash = sha256(normalized);
        List<SemanticSection> sections = parseSections(fileName, normalized);
        List<Document> result = new ArrayList<>();

        for (SemanticSection section : sections) {
            Map<String, Object> sectionMetadata = new LinkedHashMap<>(metadata);
            sectionMetadata.put("filename", fileName);
            sectionMetadata.put("documentTitle", section.documentTitle());
            sectionMetadata.put("sectionPath", section.sectionPath());
            sectionMetadata.put("sectionTitle", section.sectionTitle());
            sectionMetadata.put("headingLevel", section.headingLevel());

            Document bodyDocument = Document.builder()
                    .text(section.body())
                    .metadata(sectionMetadata)
                    .build();
            // 短章节保持为一个语义块；仅对超长正文二次切分，避免标题和正文被拆开。
            List<Document> parts = section.body().length() <= chunkSize
                    ? List.of(bodyDocument)
                    : tokenTextSplitter.apply(List.of(bodyDocument));
            for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
                Document partWithContext = Document.builder()
                        .text(section.embeddingText(parts.get(partIndex).getText()))
                        .metadata(parts.get(partIndex).getMetadata())
                        .build();
                result.add(stableDocument(fileName, fullContentHash, result.size(), partIndex,
                        parts.size(), section, partWithContext, metadata));
            }
        }
        return List.copyOf(result);
    }

    private List<SemanticSection> parseSections(String fileName, String markdown) {
        List<SemanticSection> sections = new ArrayList<>();
        String[] hierarchy = new String[6];
        String documentTitle = stripExtension(fileName);
        Heading current = null;
        StringBuilder body = new StringBuilder();

        for (String line : markdown.split("\n", -1)) {
            Matcher matcher = HEADING.matcher(line);
            if (!matcher.matches()) {
                body.append(line).append('\n');
                continue;
            }

            if (current != null || !body.toString().isBlank()) {
                addSection(sections, documentTitle, hierarchy, current, body.toString());
            }

            int level = matcher.group(1).length();
            String title = matcher.group(2).trim();
            if (level == 1) {
                documentTitle = title;
            }
            hierarchy[level - 1] = title;
            for (int index = level; index < hierarchy.length; index++) {
                hierarchy[index] = null;
            }
            current = new Heading(level, title);
            body.setLength(0);
        }
        if (current != null || !body.toString().isBlank()) {
            addSection(sections, documentTitle, hierarchy, current, body.toString());
        }
        if (sections.isEmpty()) {
            sections.add(new SemanticSection(documentTitle, documentTitle, documentTitle, 1, markdown));
        }
        return sections;
    }

    private void addSection(List<SemanticSection> sections, String documentTitle, String[] hierarchy,
                            Heading heading, String body) {
        String normalizedBody = body.trim();
        if (normalizedBody.isBlank()) {
            return;
        }
        int level = heading == null ? 1 : heading.level();
        String sectionTitle = heading == null ? documentTitle : heading.title();
        List<String> path = new ArrayList<>();
        for (int index = 0; index < level; index++) {
            if (hierarchy[index] != null && !hierarchy[index].equals(documentTitle)) {
                path.add(hierarchy[index]);
            }
        }
        String sectionPath = path.isEmpty() ? documentTitle : String.join(" > ", path);
        sections.add(new SemanticSection(documentTitle, sectionPath, sectionTitle, level, normalizedBody));
    }

    private Document stableDocument(String fileName, String fullHash, int chunkIndex, int partIndex,
                                    int partCount, SemanticSection section, Document chunk,
                                    Map<String, Object> metadata) {
        String stableKey = metadata.containsKey("candidateId")
                ? metadata.get("candidateId") + ":" + fullHash + ":" + section.sectionPath() + ":" + partIndex
                : fileName + ":" + fullHash + ":" + section.sectionPath() + ":" + partIndex;
        return Document.builder()
                .id(UUID.nameUUIDFromBytes(stableKey.getBytes(StandardCharsets.UTF_8)).toString())
                .text(chunk.getText())
                .metadata(chunk.getMetadata())
                .metadata("filename", fileName)
                .metadata("chunkIndex", chunkIndex)
                .metadata("partIndex", partIndex)
                .metadata("partCount", partCount)
                .metadata("contentHash", fullHash)
                .build();
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private record Heading(int level, String title) {
    }

    private record SemanticSection(String documentTitle, String sectionPath, String sectionTitle,
                                   int headingLevel, String body) {
        String embeddingText(String partBody) {
            StringBuilder text = new StringBuilder();
            text.append("文档：").append(documentTitle).append('\n');
            if (!sectionPath.equals(documentTitle)) {
                text.append("章节：").append(sectionPath).append('\n');
            }
            text.append('\n').append(partBody);
            return text.toString();
        }
    }
}
