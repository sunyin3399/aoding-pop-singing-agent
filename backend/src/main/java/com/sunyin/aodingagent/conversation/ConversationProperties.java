package com.sunyin.aodingagent.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 可调整的会话留存和模型上下文预算，避免把面试演示参数散落在业务代码中。 */
@Component
@ConfigurationProperties(prefix = "app.conversation")
public class ConversationProperties {

    private int retentionDays = 7;
    private int maxSessionsPerUserMode = 10;
    private int maxMessageChars = 30_000;
    private int profileRetentionDays = 30;
    private Context context = new Context();

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    public int getMaxSessionsPerUserMode() { return maxSessionsPerUserMode; }
    public void setMaxSessionsPerUserMode(int value) { this.maxSessionsPerUserMode = value; }
    public int getMaxMessageChars() { return maxMessageChars; }
    public void setMaxMessageChars(int maxMessageChars) { this.maxMessageChars = maxMessageChars; }
    public int getProfileRetentionDays() { return profileRetentionDays; }
    public void setProfileRetentionDays(int profileRetentionDays) { this.profileRetentionDays = profileRetentionDays; }
    public Context getContext() { return context; }
    public void setContext(Context context) { this.context = context == null ? new Context() : context; }

    public static class Context {
        private int maxHistoryTokens = 6_000;
        private int compactTriggerTokens = 6_000;
        private int summaryTargetTokens = 1_000;
        private int recentRawTurns = 3;
        private int recentRawTokenBudget = 4_000;
        private int referenceTokenBudget = 500;
        private int maxHistoryMessages = 16;

        public int getMaxHistoryTokens() { return maxHistoryTokens; }
        public void setMaxHistoryTokens(int value) { this.maxHistoryTokens = value; }
        public int getCompactTriggerTokens() { return compactTriggerTokens; }
        public void setCompactTriggerTokens(int value) { this.compactTriggerTokens = value; }
        public int getSummaryTargetTokens() { return summaryTargetTokens; }
        public void setSummaryTargetTokens(int value) { this.summaryTargetTokens = value; }
        public int getRecentRawTurns() { return recentRawTurns; }
        public void setRecentRawTurns(int value) { this.recentRawTurns = value; }
        public int getRecentRawTokenBudget() { return recentRawTokenBudget; }
        public void setRecentRawTokenBudget(int value) { this.recentRawTokenBudget = value; }
        public int getReferenceTokenBudget() { return referenceTokenBudget; }
        public void setReferenceTokenBudget(int value) { this.referenceTokenBudget = value; }
        public int getMaxHistoryMessages() { return maxHistoryMessages; }
        public void setMaxHistoryMessages(int value) { this.maxHistoryMessages = value; }
    }
}
