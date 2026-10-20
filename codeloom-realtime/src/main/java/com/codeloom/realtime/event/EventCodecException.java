package com.codeloom.realtime.event;

/**
 * 事件编解码失败。
 *
 * <p>反序列化失败通常只有两种原因：payload 被手工改过，或者事件字段被改名或删除。
 * 对 append-only 日志来说后者是破坏性操作，会让历史行读不出来。使用专门的异常类型，
 * 是为了让这种情况在日志里能被一眼认出，而不是混在笼统的运行时异常中。
 */
public class EventCodecException extends RuntimeException {

    public EventCodecException(String message) {
        super(message);
    }

    public EventCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
