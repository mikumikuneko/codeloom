package com.codeloom.domain.event;

/**
 * 模型**思考过程**的流式增量片段。
 *
 * <p><strong>不落库</strong>，理由和 {@link AssistantDelta} 完全一样：一段思考会产生成百上千个
 * 片段，逐条落库毫无意义。它只走实时通道（SSE 下行），轮到结束时落库的是 {@code AssistantMessage}
 * 里那段完整的 {@code reasoning}。
 *
 * <h2>为什么不复用 {@link AssistantDelta}（比如加个 boolean）</h2>
 * 因为两者在**界面上是两个不同的东西**：正文是回复，思考是"它是怎么想到这个回复的"，
 * 通常折叠在另一个面板里。合成一个类型再靠一个标志位区分，等于把一个判别字段藏进了
 * 负载里 —— 而类型系统本来是免费的判别器。分开之后，前端拿到哪个类型就渲染到哪个面板，
 * 不需要先解包再判断。
 *
 * <p>和正文增量一样，它也**不保证送达、不补发**：丢掉的只是"思考过程"，完整的那段随后
 * 会随 {@code AssistantMessage} 落库，重连后能补齐。
 */
public record ReasoningDelta(String text) implements EphemeralEvent {
}
