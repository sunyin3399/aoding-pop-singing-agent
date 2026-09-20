package com.sunyin.aodingagent.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AodingManusPromptTest {

    @Test
    void resolvesAliasesSemanticallyAndRequiresInternalKnowledgeBeforeExternalResearch() {
        assertThat(AodingManus.SYSTEM_PROMPT)
                .contains("Resolve artist nicknames and aliases from conversation context")
                .contains("when it is genuinely ambiguous, ask one concise clarification question")
                .contains("MUST call searchVocalKnowledge first")
                .contains("Do not call researchExternal in the first step for a domain question")
                .contains("Allow researchExternal for a domain question after searchVocalKnowledge has run")
                .contains("no hit at all, or snippets present but only tangential")
                .contains("never add an internal source list or internal links")
                .contains("preserve its procedures, exercises, repetitions or durations, examples, common mistakes and safety boundaries")
                .contains("do not collapse it into a one-line summary")
                .contains("If a dedicated internal document does cover the question well, prefer answering from it and do not go external");
        assertThat(AodingManus.NEXT_STEP_PROMPT)
                .contains("Artist aliases do not bypass this rule");
    }
}
