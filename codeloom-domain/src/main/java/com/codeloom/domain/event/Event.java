package com.codeloom.domain.event;

/**
 * 一条会话内发生的事实：只读、不可变、按序追加。
 *
 * <p>刻意 sealed：{@code EventCodec} 用穷尽 switch 在 Java 类型与 {@link EventType} 间双向映射，
 * 新增类型而漏处理会编译失败，见
 * {@code docs/decisions/architecture/2026-09-24-handwritten-exhaustive-switch-in-event-codec}。
 *
 * <p>事件只带特有业务字段；会话归属、全局 seq、发生时间由 {@link StoredEvent} 携带，并由存储层
 * 分配。两个子接口按是否落库互斥划分，使 {@code EventStore.append(...)} 在编译期拒绝易失事件：
 * 逐 token 的 {@link AssistantDelta} 误落库会让 event 表每轮膨胀几千行，而数据量小时难以发现。
 */
public sealed interface Event permits PersistentEvent, EphemeralEvent {
}

