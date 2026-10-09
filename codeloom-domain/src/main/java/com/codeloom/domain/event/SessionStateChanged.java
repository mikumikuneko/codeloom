package com.codeloom.domain.event;

import com.codeloom.domain.session.SessionState;

/**
 * 状态机推进。
 *
 * <p>把状态变化也作为事件落库（而不是只更新 session 表的当前状态），是为了让
 * 「这个会话当时是怎么一步步走到 FAILED 的」在回放里看得见。当前状态仍然冗余存在
 * session 表里，避免每次判断都要扫事件流。
 *
 * @param from   迁移前状态
 * @param to     迁移后状态
 * @param reason 人类可读的原因，用于排障；正常流转时可为 null
 */
public record SessionStateChanged(SessionState from, SessionState to, String reason) implements PersistentEvent {
}
