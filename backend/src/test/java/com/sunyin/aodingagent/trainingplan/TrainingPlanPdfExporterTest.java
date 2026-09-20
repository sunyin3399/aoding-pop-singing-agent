package com.sunyin.aodingagent.trainingplan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.Exercise;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ExerciseCategory.COOL_DOWN;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ExerciseCategory.MAIN;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.ExerciseCategory.WARM_UP;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingDay;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPhase;
import static org.assertj.core.api.Assertions.assertThat;

class TrainingPlanPdfExporterTest {

    @Test
    void rendersOnlyPlanContentWithoutReferencesOrLinks() {
        var plan = new TrainingPlanModels.TrainingPlan(
                "七天稳定发声计划", "稳定完成目标歌曲", 1, 30,
                List.of(new TrainingPhase("建立基础", "稳定气息", 1, 1, List.of("无挤压完成"),
                        List.of(exercise(WARM_UP, "阶段唇颤音")))),
                List.of(new TrainingDay(1, "建立气息支撑", List.of(
                        exercise(WARM_UP, "唇颤音"),
                        exercise(MAIN, "元音连贯"),
                        exercise(COOL_DOWN, "轻哼鸣")
                ), "记录紧张程度")),
                List.of("出现疼痛立即停止"),
                List.of("doc-secret", "https://example.com/secret"));

        String body = TrainingPlanPdfExporter.renderBody(plan);

        assertThat(body).contains("建立基础", "第 1 天", "阶段唇颤音", "阶段动作", "做法：\n1. 轻松完成", "停止条件：\n1. 疼痛时停止", "出现疼痛立即停止");
        assertThat(body).doesNotContain("参考资料", "doc-secret", "http://", "https://");
    }

    private Exercise exercise(TrainingPlanModels.ExerciseCategory category, String name) {
        return new Exercise(category, name, 10, 1, List.of("轻松完成"), List.of("疼痛时停止"));
    }
}
