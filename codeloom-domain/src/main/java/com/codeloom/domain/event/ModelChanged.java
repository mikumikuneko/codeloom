package com.codeloom.domain.event;

/**
 * 会话的模型被换掉了。
 *
 * <p>必须是事件而不只改会话表字段：换模型是发生过的事实，只改字段答不出"第 3 轮那句话是哪个
 * 模型说的""谁在什么时候换的"。和 {@link SessionStateChanged} 同模式：事件与会话行上的列在
 * 同一事务写入，列供每轮快读，事件供审计与重放。下一轮生效，当前轮已拿到配置快照。见
 * {@code docs/decisions/architecture/2026-09-28-switching-model-is-an-event}。
 *
 * <p>只记模型名：端点（{@code baseUrl}）变化意味着换 provider（要重配密钥、能力可能整套不同），
 * 是另一件事。
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
