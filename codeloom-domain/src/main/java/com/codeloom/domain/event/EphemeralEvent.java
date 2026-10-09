package com.codeloom.domain.event;

/**
 * 不落库的事件，只走实时通道。
 *
 * <p>目前的两个成员都是流式增量：{@link AssistantDelta}（正文）和 {@link ReasoningDelta}
 * （思考过程）。一条回复会产生成百上千个片段，落库毫无意义 ——
 * 而回放需要的信息已经被一轮结束时的 {@code AssistantMessage}（完整正文 + 完整思考）覆盖了。
 *
 * <h2>不补发</h2>
 * 它**只走实时通道**（Redis Pub/Sub 扇出到各实例，再由那里推给 SSE），
 * <strong>不保证送达、也不补发</strong>。断线期间正在生成的那半截会丢，
 * 重连后能补齐的是已经落库的那些（{@code Last-Event-ID} 的语义）；
 * 那种"打字过程"的丢失是可接受的 —— 这一轮的完整内容随后会落库，
 * 重连时它就会作为历史被拉回来。
 *
 * <p>刻意把它做成一个独立的 sealed 接口：{@code EventStore.append(...)} 的形参类型是
 * {@link PersistentEvent}，所以**把 delta 传给存储层是编译错误**，不需要靠代码评审或注释去拦。
 */
public sealed interface EphemeralEvent extends Event permits AssistantDelta, ReasoningDelta {
}
