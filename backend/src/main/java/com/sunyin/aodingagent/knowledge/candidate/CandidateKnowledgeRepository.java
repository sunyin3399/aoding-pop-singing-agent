package com.sunyin.aodingagent.knowledge.candidate;

import java.util.List;
import java.util.Optional;

import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateKnowledge;
import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus;

public interface CandidateKnowledgeRepository {

    CandidateKnowledge save(CandidateKnowledge candidate);

    Optional<CandidateKnowledge> findById(String id);

    List<CandidateKnowledge> findAll(CandidateStatus status);
}
