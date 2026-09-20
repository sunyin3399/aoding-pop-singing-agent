package com.sunyin.aodingagent.trainingplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanResult;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlanStatus.NEEDS_CLARIFICATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TrainingPlanControllerTest {

    @Test
    void exposesTheWorkflowAsJson() throws Exception {
        TrainingPlanWorkflow workflow = request -> TrainingPlanResult.terminal(
                NEEDS_CLARIFICATION,
                List.of(new TrainingPlanModels.ClarificationQuestion("goal", "请填写目标")),
                List.of(new TrainingPlanModels.ValidationIssue("REQUIRED", "goal", "目标不能为空")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TrainingPlanController(workflow)).build();

        mvc.perform(post("/ai/training-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"c1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.questions[0].field").value("goal"));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new TrainingPlanController(request -> { throw new AssertionError("must not run"); })).build();

        mvc.perform(post("/ai/training-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest());
    }
}
