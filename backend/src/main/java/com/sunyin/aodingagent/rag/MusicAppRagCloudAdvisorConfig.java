package com.sunyin.aodingagent.rag;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetriever;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

//@Configuration
@Slf4j
public class MusicAppRagCloudAdvisorConfig {

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;


    /**
     * 创建一个名为musicAppRagCloudAdvisor的Bean，这是一个Spring配置方法，
     * 用于构建一个用于音乐知识检索增强的Advisor对象。
     *
     * @return Advisor 返回一个配置好的检索增强Advisor，用于知识库查询
     */
//    @Bean
    public Advisor musicAppRagCloudAdvisor() {
        // 创建DashScopeApi实例，使用配置的API密钥进行初始化
        DashScopeApi dashScopeApi = new DashScopeApi(dashScopeApiKey);
        // 定义知识库索引名称，用于指定检索的知识库
        final String KNOWLEDGE_INDEX = "流行演唱教学";
        // 创建文档检索器，配置知识库索引名称
        DashScopeDocumentRetriever documentRetriever = new DashScopeDocumentRetriever(dashScopeApi,
                DashScopeDocumentRetrieverOptions
                        .builder()
                        .withIndexName(KNOWLEDGE_INDEX)  // 设置知识库索引名称
                        .build());
        // 构建并返回检索增强Advisor，配置文档检索器
        return RetrievalAugmentationAdvisor.builder().documentRetriever(documentRetriever).build();
    }
}
