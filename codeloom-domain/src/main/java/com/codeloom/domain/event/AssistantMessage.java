package com.codeloom.domain.event;

/**
 * 一轮模型的完整回复(正文 + 思考过程)。落库的是这个，不是 {@link AssistantDelta}。
 *
 * <p>有了完整文本，回放时就不需要把成百上千个 delta 拼起来 —— 这正是 delta 可以不落库的前提。
 *
 * <h2>为什么带模型名</h2>
 * 因为<strong>一轮里用的可能不是同一个模型</strong>：用户可以在会话中途切换，
 * 而我们的模型本来就可以自由配置。把模型名记在**消息上**（而不是只记在轮次上），
 * 才能回答"**这句话是哪个模型说的**"—— 界面上要显示它，用户也要靠它判断
 * "刚才那句是不是换了模型之后说的"。
 *
 * <p>记的是<strong>服务商回报的</strong>模型名，不是请求里写的那个：两者可能不同
 * （别名、路由），而用户该看到的是真正干活的那个。这一条对任何 provider 都成立 ——
 * 它由 provider 的响应决定，不是某一家的特殊行为。
 *
 * <p>可空：这个字段是后加的，库里已有的旧消息没有它；测试里不关心模型的场合也传 null。
 *
 * <h2>思考过程为什么挂在这儿</h2>
 * 它是**那一次回复的副产品**，不是独立发生的事 —— 挂在一起，两者的关联是天然的，
 * 分开存成两条例证反而要靠"seq 挨着"去猜哪条配哪条。
 *
 * <p>而且它**只用于展示**。回不回传给模型是 provider 适配层的事，而且各家要求相反：
 * OpenAI 兼容的那几家明确要求多轮里**不要**回传，Anthropic 的 thinking block 带签名、
 * **必须**回传否则 400。这一层只保管，不做判断。
 *
 * <p>**有长度上限**（见 {@code AgentTurn}）：事件表要长期保存，而思考过程可能比正文
 * 长好几倍。展示用途，截断是可接受的。
 *
 * @param text      这一轮的完整正文
 * @param model     服务商回报的模型名。空 = 旧数据，或者产生它的那方不关心
 * @param reasoning 这一轮的思考过程。空 = 这个模型不产生思考，或者旧数据
 */
public record AssistantMessage(String text, String model, String reasoning)
        implements PersistentEvent {

    /** 不关心模型名和思考过程的场合（测试、旧数据）。 */
    public AssistantMessage(String text) {
        this(text, null, null);
    }

    /** 只带正文和模型名 —— 不产生思考的模型就走这个。 */
    public AssistantMessage(String text, String model) {
        this(text, model, null);
    }
}
