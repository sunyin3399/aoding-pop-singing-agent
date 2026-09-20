package com.sunyin.aodingagent.trainingplan;

import com.sunyin.aodingagent.tools.PDFGenerationTool;
import org.springframework.stereotype.Service;

import java.util.UUID;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.Exercise;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingDay;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPhase;
import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlan;

@Service
public class TrainingPlanPdfExporter {

    private final PDFGenerationTool pdfTool;

    public TrainingPlanPdfExporter() {
        this(new PDFGenerationTool());
    }

    TrainingPlanPdfExporter(PDFGenerationTool pdfTool) {
        this.pdfTool = pdfTool;
    }

    public PdfArtifact export(TrainingPlan plan) {
        String fileName = "training-plan-" + UUID.randomUUID() + ".pdf";
        String result = pdfTool.generatePDF(fileName, renderBody(plan), plan.title());
        if (!result.startsWith("PDF生成成功")) {
            throw new IllegalStateException(result);
        }
        return new PdfArtifact(fileName, "/api/ai/pdf/download/" + fileName);
    }

    static String renderBody(TrainingPlan plan) {
        StringBuilder body = new StringBuilder();
        line(body, "训练目标：" + plan.goal());
        line(body, "训练周期：" + plan.durationDays() + " 天，每天 " + plan.minutesPerDay() + " 分钟");

        line(body, "\n阶段目标");
        for (TrainingPhase phase : plan.phases()) {
            line(body, "");
            line(body, phase.name() + "（第 " + phase.startDay() + "-" + phase.endDay() + " 天）");
            line(body, "目标：" + phase.goal());
            numberedLines(body, "验收：", phase.acceptanceCriteria());
            if (!phase.exercises().isEmpty()) {
                line(body, "阶段动作");
                for (Exercise exercise : phase.exercises()) {
                    line(body, categoryName(exercise) + "：" + exercise.name()
                            + "，" + exercise.minutes() + " 分钟，" + exercise.sets() + " 组");
                    numberedLines(body, "做法：", exercise.instructions());
                    numberedLines(body, "停止条件：", exercise.stopConditions());
                }
            }
        }

        line(body, "\n每日训练项");
        for (TrainingDay day : plan.days()) {
            line(body, "\n第 " + day.day() + " 天：" + day.goal());
            for (Exercise exercise : day.exercises()) {
                line(body, categoryName(exercise) + "：" + exercise.name()
                        + "，" + exercise.minutes() + " 分钟，" + exercise.sets() + " 组");
                numberedLines(body, "做法：", exercise.instructions());
                numberedLines(body, "停止条件：", exercise.stopConditions());
            }
            if (day.checkpoint() != null && !day.checkpoint().isBlank()) {
                line(body, "当日检查：" + day.checkpoint());
            }
        }

        if (!plan.safetyNotices().isEmpty()) {
            line(body, "\n安全提示");
            for (String notice : plan.safetyNotices()) line(body, notice);
        }
        return body.toString();
    }

    private static String categoryName(Exercise exercise) {
        return switch (exercise.category()) {
            case WARM_UP -> "热身";
            case MAIN -> "核心训练";
            case COOL_DOWN -> "放松";
        };
    }

    private static void line(StringBuilder body, String text) {
        if (text != null && !text.isBlank()) body.append(text).append('\n');
    }

    private static void numberedLines(StringBuilder body, String label, java.util.List<String> items) {
        if (items == null || items.isEmpty()) return;
        line(body, label);
        for (int index = 0; index < items.size(); index++) {
            line(body, (index + 1) + ". " + items.get(index));
        }
    }

    public record PdfArtifact(String fileName, String downloadUrl) {
    }
}
