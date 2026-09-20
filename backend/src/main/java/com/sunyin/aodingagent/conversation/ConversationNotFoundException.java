package com.sunyin.aodingagent.conversation;

/** 会话不存在、已过期或不属于当前用户时统一返回，不泄露其他用户数据是否存在。 */
public final class ConversationNotFoundException extends RuntimeException {
    public ConversationNotFoundException() {
        super("会话不存在或已过期");
    }
}
