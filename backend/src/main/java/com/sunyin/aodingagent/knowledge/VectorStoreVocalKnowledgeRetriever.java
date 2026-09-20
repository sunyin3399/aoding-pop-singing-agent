package com.sunyin.aodingagent.knowledge;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用 Spring AI 的 {@link VectorStore} 从 PGVector 中检索正式声乐知识。
 * <p>
 * 它通过 topK 限制最多返回多少条，通过相似度阈值过滤关联较弱的内容，并把向量库 Document
 * 转成前端和 Agent 都能使用的 {@link Citation}。候选知识只有管理员批准并发布后才会进入这里。
 */
@Component
public class VectorStoreVocalKnowledgeRetriever implements VocalKnowledgeRetriever {

    private static final Logger logger = LoggerFactory.getLogger(VectorStoreVocalKnowledgeRetriever.class);
    private static final Pattern SONG_TITLE = Pattern.compile("《([^》]{1,40})》");
    private final VectorStore vectorStore;
    private final int topK;
    private final int candidateTopK;
    private final double similarityThreshold;
    private final RetrievalTraceRecorder traceRecorder;

    @Autowired
    public VectorStoreVocalKnowledgeRetriever(
            VectorStore vectorStore,
            @Value("${app.rag.top-k:5}") int topK,
            @Value("${app.rag.candidate-top-k:15}") int candidateTopK,
            @Value("${app.rag.similarity-threshold:0.55}") double similarityThreshold,
            RetrievalTraceRecorder traceRecorder
    ) {
        this.vectorStore = vectorStore;
        this.topK = topK;
        this.candidateTopK = Math.max(topK, candidateTopK);
        this.similarityThreshold = similarityThreshold;
        this.traceRecorder = traceRecorder == null ? RetrievalTraceRecorder.disabled() : traceRecorder;
    }

    VectorStoreVocalKnowledgeRetriever(VectorStore vectorStore, int topK, double similarityThreshold) {
        this(vectorStore, topK, Math.max(10, topK * 3), similarityThreshold, RetrievalTraceRecorder.disabled());
    }

    /** 使用语义相似度搜索内部知识，并将命中文档转换为引用。 */
    @Override
    public KnowledgeSearchResult search(String query) {
        if (query == null || query.isBlank()) return KnowledgeSearchResult.of(query, List.of())
                .withRetrievalMetadata(new RetrievalMetadata(List.of()));
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(candidateTopK)
                .similarityThreshold(0.0)
                .build();
        List<Document> candidates = vectorStore.similaritySearch(request);
        if (candidates.isEmpty()) {
            logger.warn("内部知识检索没有返回任何候选: query='{}', candidateTopK={}, threshold=0.0。请检查向量表、Embedding 模型和数据源连接。",
                    query, candidateTopK);
        } else {
            logger.info("内部知识检索候选: query='{}', candidateCount={}, candidateTopK={}, acceptanceThreshold={}",
                    query, candidates.size(), candidateTopK, similarityThreshold);
            for (int index = 0; index < Math.min(candidates.size(), 10); index++) {
                Document candidate = candidates.get(index);
                logger.info("内部知识候选 #{}: score={}, filename={}, chunkIndex={}, documentId={}",
                        index + 1, score(candidate),
                        candidate.getMetadata().getOrDefault("filename", "未知文档"),
                        candidate.getMetadata().getOrDefault("chunkIndex", "未知"), candidate.getId());
            }
        }
        Document dominantSource = dominantSource(candidates);
        List<Document> accepted = dominantSource != null
                ? candidates.stream().filter(document -> sameSource(dominantSource, document))
                        .limit(topK * 2L).toList()
                : candidates.stream().filter(document -> score(document) >= similarityThreshold).limit(topK).toList();
        if (dominantSource != null) {
            logger.info("内部知识优势来源扩展: query='{}', filename={}, expandedChunks={}", query,
                    dominantSource.getMetadata().getOrDefault("filename", "未知文档"), accepted.size());
        }
        DetailLookup detailLookup = dominantSource != null ? null : detailLookup(accepted);
        List<Document> detailCandidates = detailLookup == null ? List.of() : vectorStore.similaritySearch(SearchRequest.builder()
                .query(detailLookup.query())
                .topK(candidateTopK)
                .similarityThreshold(0.0)
                .build());
        if (detailLookup != null) logCandidates("内部知识详情候选", detailCandidates);
        List<Document> details = detailCandidates.stream()
                .filter(document -> score(document) >= similarityThreshold || matchesAnyTitle(document, detailLookup.titles()))
                .limit(topK)
                .toList();
        if (detailLookup != null) {
            logger.info("内部知识详情补查完成: query='{}', accepted={}", detailLookup.query(), details.size());
        }
        LinkedHashMap<String, Document> merged = new LinkedHashMap<>();
        details.forEach(document -> merged.put(document.getId(), document));
        accepted.forEach(document -> merged.putIfAbsent(document.getId(), document));
        List<Citation> citations = merged.values().stream().limit(topK * 2L).map(this::toCitation).toList();
        logger.info("内部知识检索过滤完成: query='{}', threshold={}, firstStageAccepted={}, detailAccepted={}, returned={}",
                query, similarityThreshold, accepted.size(), details.size(), citations.size());
        traceRecorder.record(toTrace(query, candidates, citations));
        List<String> candidateIds = java.util.stream.Stream.concat(candidates.stream(), detailCandidates.stream())
                .map(Document::getId).distinct().toList();
        return KnowledgeSearchResult.of(query, citations).withRetrievalMetadata(new RetrievalMetadata(candidateIds));
    }

    /** 把一次检索的关键信息整理成痕迹记录（用于离线评估与积累 golden set）。 */
    private RetrievalTraceRecorder.RetrievalTrace toTrace(
            String query, List<Document> candidates, List<Citation> citations) {
        int limit = Math.min(candidates.size(), candidateTopK);
        List<RetrievalTraceRecorder.Hit> hits = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            Document document = candidates.get(index);
            Object rawChunk = document.getMetadata().get("chunkIndex");
            Integer chunkIndex = rawChunk instanceof Number number ? number.intValue() : null;
            hits.add(new RetrievalTraceRecorder.Hit(
                    document.getId(),
                    String.valueOf(document.getMetadata().getOrDefault("filename", "")),
                    chunkIndex,
                    document.getScore(),
                    index + 1));
        }
        return new RetrievalTraceRecorder.RetrievalTrace(
                query, Instant.now(), candidateTopK, similarityThreshold,
                List.copyOf(hits),
                citations.stream().map(Citation::id).toList());
    }

    private DetailLookup detailLookup(List<Document> accepted) {
        String combined = accepted.stream().map(Document::getText)
                .filter(text -> text != null && !text.isBlank()).reduce("", (left, right) -> left + " " + right);
        if (!(combined.contains("学习顺序") || combined.contains("歌曲推荐")
                || combined.contains("推荐歌曲") || combined.contains("歌单"))) return null;
        Matcher matcher = SONG_TITLE.matcher(combined);
        LinkedHashMap<String, Boolean> titles = new LinkedHashMap<>();
        while (matcher.find() && titles.size() < topK) titles.put(matcher.group(1).trim(), Boolean.TRUE);
        if (titles.size() < 2) return null;
        return new DetailLookup(String.join(" ", titles.keySet()) + " 推荐原因 主要练习 初学者建议",
                List.copyOf(titles.keySet()));
    }

    private Document dominantSource(List<Document> candidates) {
        List<Document> accepted = candidates.stream()
                .filter(document -> score(document) >= similarityThreshold)
                .limit(topK)
                .toList();
        if (accepted.size() < 3) return null;

        LinkedHashMap<String, Integer> sourceCounts = new LinkedHashMap<>();
        accepted.forEach(document -> sourceCounts.merge(sourceKey(document), 1, Integer::sum));
        var dominant = sourceCounts.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .orElseThrow();
        if (dominant.getValue() < 3 || dominant.getValue() * 5 < accepted.size() * 3) return null;
        if (!dominant.getKey().equals(sourceKey(accepted.getFirst()))) return null;
        return accepted.getFirst();
    }

    private boolean sameSource(Document expected, Document candidate) {
        return sourceKey(expected).equals(sourceKey(candidate));
    }

    private String sourceKey(Document document) {
        Object contentHash = document.getMetadata().get("contentHash");
        if (contentHash != null) return "hash:" + contentHash;
        return "file:" + document.getMetadata().getOrDefault("filename", "");
    }

    private boolean matchesAnyTitle(Document document, List<String> titles) {
        String content = String.valueOf(document.getMetadata().getOrDefault("filename", ""))
                + " " + (document.getText() == null ? "" : document.getText());
        return titles.stream().anyMatch(content::contains);
    }

    private void logCandidates(String label, List<Document> candidates) {
        for (int index = 0; index < Math.min(candidates.size(), 10); index++) {
            Document candidate = candidates.get(index);
            logger.info("{} #{}: score={}, filename={}, chunkIndex={}, documentId={}", label, index + 1,
                    score(candidate), candidate.getMetadata().getOrDefault("filename", "未知文档"),
                    candidate.getMetadata().getOrDefault("chunkIndex", "未知"), candidate.getId());
        }
    }

    private record DetailLookup(String query, List<String> titles) {}

    private double score(Document document) {
        return document.getScore() == null ? 0.0 : document.getScore();
    }

    /**
     * 将向量库文档转换为安全的引用摘要，正文最多保留 600 个字符。
     */
    public Citation toCitation(Document document) {
        String title = String.valueOf(document.getMetadata().getOrDefault("filename", "未知文档"));
        String excerpt = document.getText() == null ? "" : document.getText().replaceAll("\\s+", " ").trim();
        if (excerpt.length() > 600) excerpt = excerpt.substring(0, 597) + "...";
        return new Citation(
                document.getId(),
                Citation.CitationType.INTERNAL_KNOWLEDGE,
                title,
                "/api/ai/knowledge/documents/" + document.getId(),
                excerpt,
                document.getScore()
        );
    }
}
