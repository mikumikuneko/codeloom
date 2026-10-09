package com.codeloom.domain.event;

import com.codeloom.domain.session.SessionId;

import java.time.Instant;

/**
 * 事件信封：把「存储层分配的东西」和「业务事实」分开。
 *
 * <p>{@code seq} 是**全局单调递增**的（由 {@code event} 表的自增主键提供），
 * 不是会话内序号。全局单调让「按 seq 之后拉取」这一个查询同时满足三种需求：
 * 断线重连补齐、checkpoint 定位、回放。
 *
 * @param sessionId  事件归属于哪条会话
 * @param seq        全局单调序号，由存储层分配
 * @param occurredAt 落库时间（不是模型生成时间）
 * @param event      业务事实
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
