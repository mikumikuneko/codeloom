package com.codeloom.domain.event;

/**
 * 模型输出的**正文**流式增量片段。
 *
 * <p><strong>不落库。</strong> 一条回复会产生成百上千个 delta，落库的是
 * {@link AssistantMessage}（一轮结束后的完整文本）；投递保证见 {@link EphemeralEvent}。
 *
 * <p>思考过程走的是另一个类型 {@link ReasoningDelta}，见那里的注释。
 */
public record AssistantDelta(String text) implements EphemeralEvent {
}
