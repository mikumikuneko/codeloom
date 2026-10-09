package com.codeloom.realtime.persistence;

import java.time.Instant;

/**
 * 插入一条事件用的参数对象。可变的原因见 {@link GeneratedKey}（一句话：自增主键要回填进来）。
 *
 * <p>{@code type} 和 {@code payload} 是 {@code EventCodec} 编出来的两条信息，
 * 分开存两列而不是把类型塞进 JSON 里：按类型查事件（"这条会话都发生过哪些验证失败"）
 * 是审计里很自然会有的需求，塞进 JSON 就只能全表扫。
 */
public class EventInsert extends GeneratedKey {

    private final String sessionId;
    private final String type;
    private final String payload;
    private final Instant occurredAt;

    public EventInsert(String sessionId, String type, String payload, Instant occurredAt) {
        this.sessionId = sessionId;
        this.type = type;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
