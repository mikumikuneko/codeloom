package com.codeloom.domain.event;

/**
 * 一条会话内发生的一个事实。事件是只读的、不可变的、按序追加的。
 *
 * <p>刻意做成 {@code sealed}：{@code codeloom-realtime} 里的 {@code EventCodec}
 * 用穷尽 switch 在 Java 类型与 {@link EventType} 之间做双向映射。新增一个事件类型
 * 而忘了在 Codec 里处理，那两处 switch 会直接编译不过 —— 与「模块边界靠 Maven
 * 依赖强制」是同一套思路：让编译器替人记住容易忘的约束。
 *
 * <p>事件本身只带该事件特有的业务字段；归属会话、全局 seq、发生时间由
 * {@link StoredEvent} 这个信封携带 —— 那三样是存储层分配的，不该由业务构造。
 *
 * <h2>为什么还要再分两个子接口</h2>
 * 事件按「要不要落库」分成互斥的两类，见 {@link PersistentEvent} 与
 * {@link EphemeralEvent}。拆开的目的不是分类好看，而是让
 * {@code EventStore.append(...)} **在编译期就拒绝**易失事件 ——
 * 逐 token 的 {@link AssistantDelta} 一旦误落库，event 表每轮会膨胀几千行，
 * 而这种错误在测试里未必立刻显形（数据量小了看不出来）。
 */
public sealed interface Event permits PersistentEvent, EphemeralEvent {
}

