package com.sunyin.aodingagent.metrics;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("stress") // 仅在压测环境生效
@Primary           // 压测时优先注入，平替 DashScopeEmbeddingModel
public class MockEmbeddingModel implements EmbeddingModel {

    // 🚨 CRITICAL 架构注意点：
    // 这里的维度（dimension）必须与你 PostgreSQL 中 vector_store 表建表时的向量维度完全一致！
    // 如果你之前用的是阿里 DashScope 的 text-embedding-v1/v2，默认是 1536 维。
    // 如果使用的是 OpenAI 或 BGE-large，可能是 1536 或 1024 维。请根据你实际的数据库字段长度修改此处。
    private static final int DIMENSION = 1536;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        int index = 0;

        for (String text : request.getInstructions()) {
            float[] mockVector = new float[DIMENSION];
            embeddings.add(new Embedding(mockVector, index++));
        }

        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return new float[DIMENSION];
    }
}