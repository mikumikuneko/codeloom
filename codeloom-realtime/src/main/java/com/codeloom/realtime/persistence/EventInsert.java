package com.codeloom.realtime.persistence;

import java.time.Instant;

/**
 * 插入一条事件用的参数对象。可变的原因见 {@link GeneratedKey}：自增主键要回填进来。
 *
 * <p>{@code type} 与 {@code payload} 是 {@code EventCodec} 编出来的两条信息，分开存两列
 * 而不是把类型塞进 JSON：按类型查事件是审计里常见的需求，塞进 JSON 就只能全表扫。
 */
public class EventInsert extends GeneratedKey {

    private final String sessionId;
    private final String type;
    private final String payload;
    private final Instant occurredAt;

    /**
     * @param sessionId  事件所属会话
     * @param type       事件类型名
     * @param payload    事件本体的 JSON
     * @param occurredAt 落库时间
     */
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
