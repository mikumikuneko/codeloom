package com.codeloom.realtime.event;

/**
 * 事件编解码失败。
 *
 * <p>反序列化这一支几乎只有两种原因：{@code payload} 被人手工改过，或者某个事件类型的
 * 字段被改名/删除了 —— 对 append-only 的日志来说，后者是**破坏性操作**，
 * 因为它会让历史行读不出来。所以这里给一个专门的类型，而不是笼统的
 * {@code IllegalStateException}：它值得在日志里被一眼认出来。
 */
public class EventCodecException extends RuntimeException {

    public EventCodecException(String message) {
        super(message);
    }

    public EventCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
