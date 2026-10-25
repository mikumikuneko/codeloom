package com.codeloom.domain.event;

/**
 * 一轮模型的完整回复（正文 + 思考过程）。落库的是它而不是 {@link AssistantDelta}：回放不必
 * 拼接成百上千个片段，这也是 delta 可以不落库的前提。
 *
 * <p>{@code model} 记服务商回报的模型名（别名、路由会让它与请求里的不同），因为一轮里可能换
 * 模型，所以记在消息上；可空：旧消息没有它。{@code reasoning} 是那次回复的副产品，挂在这里
 * 而不单独存事件，免得靠 seq 相邻猜配对；它只用于展示，回传与否由 provider 适配层决定，见
 * {@code docs/decisions/architecture/2026-10-07-reasoning-is-a-neutral-field}。它有长度上限
 * （见 {@code AgentTurn}）：事件表长期保存，而思考可能比正文长好几倍，展示用途下截断可接受。
 *
 * @param text      这一轮的完整正文
 * @param model     服务商回报的模型名。空 = 旧数据，或者产生它的那方不关心
 * @param reasoning 这一轮的思考过程。空 = 这个模型不产生思考，或者旧数据
 */
public record AssistantMessage(String text, String model, String reasoning)
        implements PersistentEvent {

    /**
     * 测试与便捷用的重载：思考过程固定为 null。产品路径总是显式传第三个参数 —— "有没有思考"
     * 是那次调用的事实，不能靠重载去猜。
     */
    public AssistantMessage(String text, String model) {
        this(text, model, null);
    }
}
