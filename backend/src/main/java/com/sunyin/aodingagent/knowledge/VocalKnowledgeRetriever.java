package com.sunyin.aodingagent.knowledge;

import java.util.List;

/**
 * 查询已经审核通过的内部声乐知识。
 * <p>
 * 训练计划和 Agent 只依赖这个接口，不直接操作 PGVector。这样既可以更换底层向量库，也能在
 * 测试中使用假实现。外部网页和待审核候选知识不属于这个接口的查询范围。
 */
public interface VocalKnowledgeRetriever {

    /**
     * 根据用户问题查找相关内部知识。
     *
     * @return 查询状态和可点击引用；没有命中时返回空引用及 NO_KNOWLEDGE_HIT
     */
    KnowledgeSearchResult search(String query);

    /** 一次内部知识检索的结果。 */
    record KnowledgeSearchResult(
            KnowledgeSearchStatus status,
            String query,
            List<Citation> citations,
            @com.fasterxml.jackson.annotation.JsonIgnore RetrievalMetadata retrievalMetadata
    ) {
        public KnowledgeSearchResult {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }

        public KnowledgeSearchResult(KnowledgeSearchStatus status, String query, List<Citation> citations) {
            this(status, query, citations, null);
        }

        public KnowledgeSearchResult withRetrievalMetadata(RetrievalMetadata metadata) {
            return new KnowledgeSearchResult(status, query, citations, metadata);
        }

        public static KnowledgeSearchResult of(String query, List<Citation> citations) {
            List<Citation> safeCitations = citations == null ? List.of() : List.copyOf(citations);
            return new KnowledgeSearchResult(
                    safeCitations.isEmpty() ? KnowledgeSearchStatus.NO_KNOWLEDGE_HIT : KnowledgeSearchStatus.FOUND,
                    query,
                    safeCitations
            );
        }
    }

    /** IDs from both searches before acceptance/limit; null metadata means unobserved. Never model content. */
    record RetrievalMetadata(List<String> candidateIds) {
        public RetrievalMetadata { candidateIds = List.copyOf(candidateIds); }
    }

    /** 内部知识库是否找到了达到相似度要求的内容。 */
    enum KnowledgeSearchStatus {
        /** 找到了一个或多个已批准知识片段。 */
        FOUND,
        /** 没有命中，Agent 可以根据业务规则决定是否进行外部研究。 */
        NO_KNOWLEDGE_HIT
    }
}
