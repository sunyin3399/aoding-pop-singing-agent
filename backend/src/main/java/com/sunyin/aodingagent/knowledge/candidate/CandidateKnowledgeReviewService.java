package com.sunyin.aodingagent.knowledge.candidate;

import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateKnowledge;

/**
 * 管理员审核候选知识的业务接口。
 * <p>
 * Controller 只负责接收管理员操作，批准后的向量发布、状态更新和重复操作处理统一由实现类完成。
 */
public interface CandidateKnowledgeReviewService {

    /** 批准并发布指定候选知识；发布失败时不能把记录标记为已批准。 */
    CandidateKnowledge approve(String id, String reviewNote);

    /** 拒绝指定候选知识，拒绝内容不会进入正式 RAG。 */
    CandidateKnowledge reject(String id, String reviewNote);
}
