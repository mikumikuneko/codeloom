package com.codeloom.domain.event;

/**
 * 模型思考过程的流式增量片段。
 *
 * <p>不落库，理由同 {@link AssistantDelta}：一段思考有成百上千个片段，一轮结束时落库的是
 * {@link AssistantMessage} 里的 {@code reasoning}。不与正文合并：界面正文和思考是两个区域，
 * 合成一个再靠负载标志位区分，等于把判别字段藏进负载。它同样不保证送达、不补发，完整内容
 * 随后会随 {@link AssistantMessage} 落库。
 *
 * @param text 这次增量携带的思考片段
 */
public record ReasoningDelta(String text) implements EphemeralEvent {
}
