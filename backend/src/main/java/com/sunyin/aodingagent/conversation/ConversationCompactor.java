package com.sunyin.aodingagent.conversation;

import com.sunyin.aodingagent.conversation.ConversationModels.*;

import java.util.List;

/** 把旧摘要和新增长历史压缩为结构化摘要，同时返回明确用户画像候选。 */
public interface ConversationCompactor {
    ContextCompactionResult compact(StructuredConversationSummary previous,
                                    List<ConversationMessage> messages,
                                    int targetTokens);
}
