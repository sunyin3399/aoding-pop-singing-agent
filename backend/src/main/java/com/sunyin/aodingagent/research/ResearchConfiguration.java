package com.sunyin.aodingagent.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunyin.aodingagent.tools.WebScrapingTool;
import com.sunyin.aodingagent.tools.WebSearchTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ResearchConfiguration {

    @Bean
    WebSearchTool researchWebSearchTool(
            @Value("${search-api.api-key:}") String apiKey,
            @Value("${search.searxng.base-url:http://localhost:8080}") String searxngBaseUrl
    ) {
        return new WebSearchTool(apiKey, searxngBaseUrl);
    }

    @Bean
    WebScrapingTool researchWebScrapingTool() {
        return new WebScrapingTool();
    }

    @Bean
    ResearchGateway researchGateway(WebSearchTool searchTool, WebScrapingTool scrapingTool,
                                     ObjectMapper objectMapper,
                                     @Value("${research.search.failover-threshold:2}") int failoverThreshold,
                                     @Value("${research.search.failover-cooldown-ms:300000}") long failoverCooldownMillis) {
        return new ToolResearchGateway(searchTool, scrapingTool, objectMapper,
                failoverThreshold, failoverCooldownMillis);
    }

    @Bean
    ResearchWorkflow researchWorkflow(ResearchGateway gateway, ResearchRecoveryPlanner recoveryPlanner) {
        return new ResearchWorkflow(gateway, new ResearchResultValidator(), recoveryPlanner,
                ResearchModels.ResearchBudget.defaults(), Clock.systemUTC());
    }
}
