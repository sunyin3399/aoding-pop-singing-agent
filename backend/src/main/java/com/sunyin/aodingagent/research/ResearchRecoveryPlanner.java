package com.sunyin.aodingagent.research;

import java.util.List;

/**
 * 在普通补救仍无法满足研究合同时，负责根据剩余缺口生成最后一组搜索词。
 * <p>
 * 它只提出“还应该搜索什么”，不直接搜索网页，也不能修改原合同或突破会话预算。
 */
@FunctionalInterface
public interface ResearchRecoveryPlanner {
    /**
     * 根据当前来源和未满足项制定一次恢复查询计划。
     *
     * @param contract 原始研究目标
     * @param session 当前研究台账
     * @param issues 仍未满足的数量或分类要求
     * @return 建议执行的补充查询；空计划表示没有可用补救方案
     */
    ResearchModels.RecoveryPlan recover(
            ResearchModels.ResearchTaskContract contract,
            ResearchSession session,
            List<ResearchModels.ValidationIssue> issues
    );
}
