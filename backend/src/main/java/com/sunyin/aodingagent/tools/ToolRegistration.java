package com.sunyin.aodingagent.tools;

import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具注册配置类
 * 用于注册和管理各种工具实例
 */
@Configuration
public class ToolRegistration {

    /**
     * 从配置文件中注入搜索API的密钥
     * 配置项名称为: search-api.api-key
     */
    /**
     * 创建并配置所有工具实例的Bean
     *
     * @return ToolCallback[] 包含所有工具实例的回调数组
     */
    @Bean
    public ToolCallback[] allTools(
            TrainingPlanTool trainingPlanTool,
            VocalKnowledgeTool vocalKnowledgeTool,
            ResearchTool researchTool
    ) {
        // 创建资源下载工具实例
        // 创建PDF生成工具实例
        // 这里只注册允许流行演唱 Agent 直接调用的高层工具。WebSearchTool、WebScrapingTool 等底层能力
        // 没有加入数组，防止 Agent 绕过研究预算和校验。
        PDFGenerationTool pdfGenerationTool = new PDFGenerationTool();
        TerminateTool terminateTool = new TerminateTool();
        // 将所有工具实例转换为回调数组并返回
        var callbacks = ToolCallbacks.from(
                trainingPlanTool,
                vocalKnowledgeTool,
                researchTool,
                pdfGenerationTool,
                terminateTool
        );
        for (int i = 0; i < callbacks.length; i++) {
            if ("searchVocalKnowledge".equals(callbacks[i].getToolDefinition().name()))
                callbacks[i] = vocalKnowledgeTool.callback();
        }
        return callbacks;
    }

    @Bean
    public ToolCallingManager toolCallingManager() {
        return ToolCallingManager.builder().build();
    }
}
