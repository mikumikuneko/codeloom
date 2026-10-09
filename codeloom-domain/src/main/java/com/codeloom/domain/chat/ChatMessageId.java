package com.codeloom.domain.chat;

/** 聊天室消息标识。 */
public record ChatMessageId(long value) {

    public ChatMessageId {
        if (value <= 0) {
            throw new IllegalArgumentException("chat message id 必须是正数，收到 " + value);
        }
    }

    public static ChatMessageId of(long value) {
        return new ChatMessageId(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
