package com.codeloom.domain.event;

/**
 * 会话的模型被换掉了。
 *
 * <h2>为什么它必须是事件，而不是只改会话表的一个字段</h2>
 * 换模型是**发生过的事实**，不是"当前配置长什么样"。只改字段的话答不上这些问题：
 * 第 3 轮那句话是哪个模型说的？这次账单为什么贵了一截？是谁在什么时候换的？
 *
 * <p>和 {@link SessionStateChanged} 同一个模式：**事件 + 会话行上的一个列，同一个事务写**。
 * 列保证读取快（每轮都要读模型），事件保证可审计、可重放。
 *
 * <h2>生效时机是下一轮</h2>
 * 当前正在跑的那一轮已经拿到配置快照了，不会被改靶 —— 中途换靶会让"这一轮用的是
 * 哪个模型"变得说不清。
 *
 * <h2>刻意只记模型名</h2>
 * 端点（{@code baseUrl}）变了意味着**换了 provider**，那是另一件事（要重新配密钥、
 * 能力可能整套不同）。把它塞进这个事件里，会让"我换了个模型"和"我换了一家公司"
 * 在审计里长得一样。
 *
 * @param fromModel 换之前用的模型名
 * @param toModel   换之后用的模型名
 */
public record ModelChanged(String fromModel, String toModel) implements PersistentEvent {

    public ModelChanged {
        if (toModel == null || toModel.isBlank()) {
            throw new IllegalArgumentException("换到哪个模型是必填的");
        }
    }
}
