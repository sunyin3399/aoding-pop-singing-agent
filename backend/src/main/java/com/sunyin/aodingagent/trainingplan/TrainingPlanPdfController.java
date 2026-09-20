package com.sunyin.aodingagent.trainingplan;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.sunyin.aodingagent.trainingplan.TrainingPlanModels.TrainingPlan;

@RestController
@RequestMapping("/ai/training-plans/pdf")
public class TrainingPlanPdfController {

    private final TrainingPlanPdfExporter exporter;

    public TrainingPlanPdfController(TrainingPlanPdfExporter exporter) {
        this.exporter = exporter;
    }

    @PostMapping
    public TrainingPlanPdfExporter.PdfArtifact export(@RequestBody TrainingPlan plan) {
        return exporter.export(plan);
    }
}
