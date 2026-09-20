package com.sunyin.aodingagent.research;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 收集 Agent 回答中需要展示的参考资料，并负责清洗 URL 和去重。
 * <p>
 * 同一个网页可能在多次搜索中以不同形式出现，例如带有 utm 参数、默认端口或末尾斜杠。
 * 这个类会把它们统一成同一个地址，并按第一次发现的顺序保存，避免前端显示重复链接。
 */
public final class ReferenceAccumulator {

    private static final Set<String> TRACKING_PARAMETERS = Set.of("spm", "from");
    private final Map<String, AgentReference> references = new LinkedHashMap<>();

    /** 添加一批引用。方法加锁是为了避免流式 Agent 的并发步骤同时修改内部集合。 */
    public synchronized void addAll(List<AgentReference> additions) {
        if (additions == null) return;
        for (AgentReference addition : additions) add(addition);
    }

    public synchronized List<AgentReference> snapshot() {
        return List.copyOf(references.values());
    }

    public synchronized void clear() {
        references.clear();
    }

    private void add(AgentReference value) {
        if (value == null || value.title() == null || value.title().isBlank()) return;
        String normalizedUrl = value.status() == AgentReference.ReferenceStatus.INTERNAL_APPROVED
                ? normalizeInternalKnowledgeUrl(value.url()) : normalizeUrl(value.url());
        if (normalizedUrl == null) return;
        URI uri = URI.create(normalizedUrl);
        AgentReference normalized = new AgentReference(
                value.id(), value.title().strip(), normalizedUrl,
                value.excerpt() == null ? "" : value.excerpt().strip(),
                value.status() == null ? AgentReference.ReferenceStatus.SEARCH_ONLY : value.status(),
                value.domain() == null || value.domain().isBlank()
                        ? (uri.getHost() == null ? "内部知识库" : uri.getHost()) : value.domain());
        String key = normalized.status() == AgentReference.ReferenceStatus.INTERNAL_APPROVED
                ? "internal:" + normalized.title().toLowerCase(Locale.ROOT) : normalizedUrl;
        references.merge(key, normalized, this::merge);
    }

    /**
     * 内部知识引用由本系统生成站内地址，不应被当成外部 HTTP 网页。
     * 这里只允许固定的只读文档接口，其他相对路径、目录跳转和完整外站地址仍会被拒绝。
     */
    private static String normalizeInternalKnowledgeUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = new URI(raw.strip()).normalize();
            String path = uri.getPath();
            if (uri.isAbsolute() || uri.getHost() != null || uri.getQuery() != null || uri.getFragment() != null
                    || path == null || !path.startsWith("/api/ai/knowledge/documents/") || path.contains("..")) {
                return null;
            }
            return path;
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private AgentReference merge(AgentReference existing, AgentReference incoming) {
        boolean incomingPreferred = priority(incoming.status()) > priority(existing.status());
        String title = incomingPreferred || incoming.title().length() > existing.title().length()
                ? incoming.title() : existing.title();
        String excerpt = incomingPreferred || existing.excerpt().isBlank()
                ? incoming.excerpt() : existing.excerpt();
        AgentReference.ReferenceStatus status = incomingPreferred ? incoming.status() : existing.status();
        return new AgentReference(existing.id(), title, existing.url(), excerpt, status, existing.domain());
    }

    private int priority(AgentReference.ReferenceStatus status) {
        return switch (status) {
            case INTERNAL_APPROVED -> 3;
            case SCRAPED -> 2;
            case SEARCH_ONLY -> 1;
        };
    }

    /**
     * 将 URL 统一为可比较的格式，并删除常见跟踪参数和片段标识。
     *
     * @return 规范化 URL；不是有效 HTTP(S) 地址时返回 null
     */
    static String normalizeUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI source = new URI(raw.strip());
            String scheme = source.getScheme() == null ? null : source.getScheme().toLowerCase(Locale.ROOT);
            String host = source.getHost() == null ? null : source.getHost().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || host == null) return null;
            int port = source.getPort();
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) port = -1;
            String path = source.getPath();
            if (path == null || path.isBlank()) path = "";
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String query = normalizeQuery(source.getRawQuery());
            return new URI(scheme, source.getUserInfo(), host, port, path, query, null).toASCIIString();
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private static String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return null;
        List<String> kept = new ArrayList<>();
        for (String part : rawQuery.split("&")) {
            String name = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
            if (name.startsWith("utm_") || TRACKING_PARAMETERS.contains(name)) continue;
            kept.add(part);
        }
        return kept.isEmpty() ? null : String.join("&", kept);
    }
}
