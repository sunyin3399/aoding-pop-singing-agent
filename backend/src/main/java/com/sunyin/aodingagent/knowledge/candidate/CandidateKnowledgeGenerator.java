package com.sunyin.aodingagent.knowledge.candidate;

import com.sunyin.aodingagent.research.ResearchModels;

public interface CandidateKnowledgeGenerator {

    GeneratedCandidate generate(String originalRequest, ResearchModels.ResearchResult researchResult);

    record GeneratedCandidate(boolean reusable, String reason, String title, String markdownContent) {
    }
}
