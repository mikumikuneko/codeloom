package com.codeloom.realtime.persistence;

import java.time.Instant;

/**
 * {@code event} 表的一行，只用于读。写走 {@link EventInsert}。
 *
 * <p>{@code id} 就是 {@code StoredEvent.seq}，不是会话内序号，而是全表自增主键、全局单调。
 * 见 {@code schema.sql} 里 {@code event} 表的注释。
 *
 * <p>列名一律加反引号是为了全表统一写法。实测（MySQL 8.0.46）：{@code type} 和
 * {@code text} 都不是保留字，不加引号也能解析。
 *
 * @param id         即 {@code StoredEvent.seq}
 * @param sessionId  事件所属会话
 * @param type       事件类型名
 * @param payload    事件本体的 JSON
 * @param occurredAt 落库时间
 */
public record EventRow(long id,
                       String sessionId,
                       String type,
                       String payload,
                       Instant occurredAt) {

    static final String COLUMNS = """
            id, session_id AS sessionId, `type` AS type, payload,
            occurred_at AS occurredAt
            """;
}
