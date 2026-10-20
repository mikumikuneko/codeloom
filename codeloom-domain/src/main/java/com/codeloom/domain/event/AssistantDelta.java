package com.codeloom.domain.event;

/**
 * 模型输出的正文流式增量片段。
 *
 * <p>不落库：一条回复会产生成百上千个片段，落库的是 {@link AssistantMessage}（一轮结束后的完整
 * 文本）。只走实时通道，投递保证见 {@link EphemeralEvent}。思考过程用 {@link ReasoningDelta}。
 *
 * @param text 这次增量携带的正文片段
 */
public record AssistantDelta(String text) implements EphemeralEvent {
}
