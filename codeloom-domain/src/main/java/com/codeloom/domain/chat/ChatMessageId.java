package com.codeloom.domain.chat;

/**
 * 聊天室消息标识，值必须为正数。
 *
 * <p>由存储层的自增主键分配，保证全库单调递增；它不是项目内的消息序号。
 */
public record ChatMessageId(long value) {

    public ChatMessageId {
        if (value <= 0) {
            throw new IllegalArgumentException("chat message id 必须是正数，收到 " + value);
        }
    }

    /**
     * 从数值构造。
     *
     * @param value 正数消息标识
     * @return 消息标识
     */
    public static ChatMessageId of(long value) {
        return new ChatMessageId(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
