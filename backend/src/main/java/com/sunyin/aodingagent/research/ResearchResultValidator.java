package com.sunyin.aodingagent.research;

import java.util.ArrayList;
import java.util.List;

/**
 * 检查外部研究收集到的“证据来源”是否达到合同要求。
 * <p>
 * 只有抓取成功、被标记为相关、标题不为空且 URL 有效的网页才算有效来源。需要特别注意：
 * 这个类检查的是证据是否够用，并不检查 Agent 最终写出的歌曲、教程或练习步骤是否完整。
 */
public final class ResearchResultValidator {

    /**
     * 检查有效来源总数和每个分类的来源数。
     *
     * @return 未满足项列表；列表为空表示证据层达到合同要求
     */
    public List<ResearchModels.ValidationIssue> validate(
            ResearchModels.ResearchTaskContract contract,
            ResearchSession session
    ) {
        List<ResearchModels.SourceCandidate> valid = session.sources().stream()
                .filter(source -> source.scrapeStatus() == ResearchModels.ScrapeStatus.SCRAPED)
                .filter(source -> source.qualityStatus() == ResearchModels.SourceQualityStatus.RELEVANT)
                .filter(source -> source.title() != null && !source.title().isBlank())
                .filter(source -> ReferenceAccumulator.normalizeUrl(source.url()) != null)
                .toList();
        List<ResearchModels.ValidationIssue> issues = new ArrayList<>();
        if (valid.size() < contract.minimumSources()) {
            issues.add(new ResearchModels.ValidationIssue(
                    "MINIMUM_ITEMS_NOT_MET", "items", "有效来源数量不足",
                    contract.minimumSources(), valid.size()));
        }
        for (ResearchModels.ResearchCategory category : contract.categories()) {
            int actual = (int) valid.stream().filter(source -> category.name().equals(source.category())).count();
            if (actual < category.minimumItems()) {
                issues.add(new ResearchModels.ValidationIssue(
                        "CATEGORY_NOT_COVERED", category.name(), "分类来源数量不足",
                        category.minimumItems(), actual));
            }
        }
        return List.copyOf(issues);
    }
}
