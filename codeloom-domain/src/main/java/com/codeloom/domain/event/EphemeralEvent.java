package com.codeloom.domain.event;

/**
 * 不落库的事件，只走实时通道。
 *
 * <p>成员是流式增量（{@link AssistantDelta}、{@link ReasoningDelta}）：一条回复会产生成百上千个
 * 片段，落库没有意义，回放所需的信息已由一轮结束时的 {@link AssistantMessage} 覆盖。它经
 * Redis Pub/Sub 扇出到各实例再推 SSE，不保证送达、不补发；断线期间正在生成的片段会丢，重连后
 * 按 {@code Last-Event-ID} 补齐的是已落库的事件。
 *
 * <p>独立成 sealed 接口，是为了让 {@code EventStore.append(...)} 的形参类型是
 * {@link PersistentEvent}：把易失事件传给存储层会编译失败。见
 * {@code docs/decisions/architecture/2026-09-24-events-split-persistent-and-ephemeral}。
 */
public sealed interface EphemeralEvent extends Event permits AssistantDelta, ReasoningDelta {
}
