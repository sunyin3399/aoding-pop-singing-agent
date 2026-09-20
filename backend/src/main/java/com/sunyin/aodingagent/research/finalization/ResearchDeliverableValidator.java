package com.sunyin.aodingagent.research.finalization;

import com.sunyin.aodingagent.research.AgentReference;
import com.sunyin.aodingagent.research.ResearchModels;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 在最终回复返回用户前检查结构化交付物。
 * <p>
 * 现有 {@code ResearchResultValidator} 检查“证据够不够”，本类检查“最终答案有没有把证据正确交付”。
 * 它重点防止条目丢失、必填字段缺失、状态夸大以及模型编造引用 ID。
 */
public final class ResearchDeliverableValidator {

    /**
     * 按任务合同和本轮真实证据进行验收。
     *
     * @return 所有未满足项；空列表表示可以交给 Renderer 输出
     */
    public List<ResearchModels.ValidationIssue> validate(ResearchDeliverable deliverable,
                                                          EvidenceBundle evidence) {
        List<ResearchModels.ValidationIssue> issues = new ArrayList<>();
        if (deliverable == null) {
            issues.add(issue("DELIVERABLE_MISSING", "deliverable", "结构化交付物为空", 1, 0));
            return List.copyOf(issues);
        }
        ResearchModels.ResearchTaskContract contract = evidence.contract();
        List<ResearchDeliverable.DeliverableItem> items = allItems(deliverable);
        if (contract != null && items.size() < contract.minimumItems()
                && contract.deliverable() != ResearchModels.DeliverableType.RESEARCH_SUMMARY
                && contract.deliverable() != ResearchModels.DeliverableType.EXERCISE_GUIDE) {
            issues.add(issue("MINIMUM_DELIVERABLE_ITEMS_NOT_MET", "items", "最终交付条目数量不足",
                    contract.minimumItems(), items.size()));
        }
        if (contract != null && deliverable.deliverableType() != contract.deliverable()) {
            issues.add(issue("DELIVERABLE_TYPE_MISMATCH", "deliverableType", "最终交付类型与研究合同不一致", 1, 0));
        }
        if (evidence.researchStatus() != null && deliverable.status() == ResearchModels.ResearchStatus.SUCCESS
                && evidence.researchStatus() != ResearchModels.ResearchStatus.SUCCESS) {
            issues.add(issue("STATUS_OVERSTATED", "status", "证据未完全达标，最终结果不能声明完整成功", 1, 0));
        }
        validateRequiredFields(contract, items, issues);
        validateReferences(deliverable, items, evidence.references(), issues);
        validateDuplicates(items, issues);
        if (contract != null && contract.safetySectionRequired()
                && deliverable.cautions().stream().noneMatch(value -> value != null && !value.isBlank())) {
            issues.add(issue("SAFETY_SECTION_MISSING", "cautions", "发声训练任务缺少安全提醒", 1, 0));
        }
        return List.copyOf(issues);
    }

    private void validateRequiredFields(ResearchModels.ResearchTaskContract contract,
                                        List<ResearchDeliverable.DeliverableItem> items,
                                        List<ResearchModels.ValidationIssue> issues) {
        if (contract == null) return;
        for (int index = 0; index < items.size(); index++) {
            ResearchDeliverable.DeliverableItem item = items.get(index);
            String field = "items[" + index + "]";
            if (blank(item.name())) issues.add(issue("ITEM_NAME_MISSING", field + ".name", "条目名称不能为空", 1, 0));
            if (contract.deliverable() == ResearchModels.DeliverableType.SONG_RECOMMENDATION) {
                if (blank(item.recommendationReason())) issues.add(issue("REASON_MISSING", field + ".recommendationReason", "歌曲缺少推荐理由", 1, 0));
                if (blank(item.practiceFocus())) issues.add(issue("PRACTICE_FOCUS_MISSING", field + ".practiceFocus", "歌曲缺少练习重点", 1, 0));
            }
            if ((contract.deliverable() == ResearchModels.DeliverableType.TUTORIAL_LIST
                    || contract.deliverable() == ResearchModels.DeliverableType.RESOURCE_LIST)
                    && item.referenceIds().isEmpty()) {
                issues.add(issue("RESOURCE_REFERENCE_MISSING", field + ".referenceIds", "资源条目必须引用已核验来源", 1, 0));
            }
        }
    }

    private void validateReferences(ResearchDeliverable deliverable,
                                    List<ResearchDeliverable.DeliverableItem> items,
                                    List<AgentReference> references,
                                    List<ResearchModels.ValidationIssue> issues) {
        Set<String> allowed = references.stream()
                .filter(reference -> reference.status() == AgentReference.ReferenceStatus.INTERNAL_APPROVED
                        || reference.status() == AgentReference.ReferenceStatus.SCRAPED)
                .map(AgentReference::id).filter(id -> id != null && !id.isBlank()).collect(java.util.stream.Collectors.toSet());
        Set<String> used = new HashSet<>(deliverable.referenceIds());
        items.forEach(item -> used.addAll(item.referenceIds()));
        for (String id : used) {
            if (id == null || id.isBlank() || !allowed.contains(id)) {
                issues.add(issue("REFERENCE_NOT_ALLOWED", "referenceIds", "引用不在本轮已核验证据白名单中: " + id, 1, 0));
            }
        }
    }

    private void validateDuplicates(List<ResearchDeliverable.DeliverableItem> items,
                                    List<ResearchModels.ValidationIssue> issues) {
        Set<String> names = new HashSet<>();
        for (ResearchDeliverable.DeliverableItem item : items) {
            if (blank(item.name())) continue;
            String normalized = item.name().replaceAll("[《》<>\\s]", "").toLowerCase(Locale.ROOT);
            if (!names.add(normalized)) {
                issues.add(issue("DUPLICATE_ITEM", "items", "最终结果包含重复条目: " + item.name(), 1, 2));
            }
        }
    }

    static List<ResearchDeliverable.DeliverableItem> allItems(ResearchDeliverable deliverable) {
        return Stream.concat(deliverable.items().stream(),
                        deliverable.sections().stream().flatMap(section -> section.items().stream()))
                .toList();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ResearchModels.ValidationIssue issue(String code, String field, String message, int expected, int actual) {
        return new ResearchModels.ValidationIssue(code, field, message, expected, actual);
    }
}
