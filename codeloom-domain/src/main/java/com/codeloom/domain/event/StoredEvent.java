package com.codeloom.domain.event;

import com.codeloom.domain.session.SessionId;

import java.time.Instant;

/**
 * 事件信封：把存储层分配的东西和业务事实分开。
 *
 * <p>{@code seq} 是全局单调的事件表自增主键，不是会话内序号。一个"按 seq 之后拉取"的查询同时
 * 满足断线重连补齐、checkpoint 定位和回放。见
 * {@code docs/decisions/architecture/2026-09-24-one-event-log-as-the-source-of-truth}。
 *
 * @param sessionId  事件归属于哪条会话
 * @param seq        全局单调序号，由存储层从 1 开始分配
 * @param occurredAt 落库时间，不是模型生成时间
 * @param event      业务事实
 * @throws IllegalArgumentException 任一必填字段为空，或 seq 不是正数
 */
public record StoredEvent(SessionId sessionId, long seq, Instant occurredAt, Event event) {

    public StoredEvent {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (seq <= 0) {
            throw new IllegalArgumentException("seq 由存储层从 1 开始分配，不能是 " + seq);
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt 不能为空");
        }
        if (event == null) {
            throw new IllegalArgumentException("event 不能为空");
        }
    }
}
