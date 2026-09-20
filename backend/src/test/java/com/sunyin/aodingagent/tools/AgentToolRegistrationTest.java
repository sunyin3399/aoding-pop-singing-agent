package com.sunyin.aodingagent.tools;

import com.sunyin.aodingagent.knowledge.VocalKnowledgeRetriever;
import com.sunyin.aodingagent.research.ResearchGateway;
import com.sunyin.aodingagent.research.ResearchModels;
import com.sunyin.aodingagent.research.ResearchRecoveryPlanner;
import com.sunyin.aodingagent.research.ResearchResultValidator;
import com.sunyin.aodingagent.research.ResearchTaskPlanner;
import com.sunyin.aodingagent.research.ResearchWorkflow;
import com.sunyin.aodingagent.trainingplan.TrainingPlanWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.time.Clock;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentToolRegistrationTest {

    @Test
    void exposesOnlyTheApprovedAgentToolSet() {
        TrainingPlanWorkflow workflow = request -> null;
        VocalKnowledgeRetriever retriever = query -> VocalKnowledgeRetriever.KnowledgeSearchResult.of(query, java.util.List.of());
        ToolRegistration registration = new ToolRegistration();
        ResearchGateway gateway = new ResearchGateway() {
            @Override public com.sunyin.aodingagent.research.WebSearchResult search(String query) { return null; }
            @Override public com.sunyin.aodingagent.research.WebScrapeResult scrape(String url) { return null; }
        };
        ResearchTaskPlanner planner = request -> ResearchTaskPlanner.defaultResourceListContract(request, 5);
        ResearchRecoveryPlanner recovery = (contract, session, issues) -> new ResearchModels.RecoveryPlan(List.of());
        ResearchTool researchTool = new ResearchTool(planner, new ResearchWorkflow(
                gateway, new ResearchResultValidator(), recovery,
                ResearchModels.ResearchBudget.defaults(), Clock.systemUTC()));

        ToolCallback[] callbacks = registration.allTools(
                new TrainingPlanTool(workflow), new VocalKnowledgeTool(retriever),
                researchTool);

        var knowledge = Arrays.stream(callbacks)
                .filter(callback -> "searchVocalKnowledge".equals(callback.getToolDefinition().name()))
                .findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertFalse(knowledge.getToolDefinition().inputSchema().contains("context"));
        Set<String> names = Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "prepareTrainingPlanForm",
                "searchVocalKnowledge",
                "researchExternal",
                "generatePDF",
                "doTerminate"
        ), names);
    }
}
