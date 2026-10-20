package com.codeloom.domain.event;

import com.codeloom.domain.session.SessionState;

/**
 * 状态机推进。
 *
 * <p>状态变化也作为事件落库，当前状态同时冗余在 session 表里：事件让"会话怎么一步步走到 FAILED"
 * 可回放，列避免每次判断都扫事件流。
 *
 * @param from   迁移前状态
 * @param to     迁移后状态
 * @param reason 人类可读的原因，用于排障；正常流转时可为 null
 */
public record SessionStateChanged(SessionState from, SessionState to, String reason) implements PersistentEvent {
}
