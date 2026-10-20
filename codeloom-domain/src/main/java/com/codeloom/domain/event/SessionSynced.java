package com.codeloom.domain.event;

/**
 * 把主干同步进了这条会话的工作区 —— 合并的反方向。
 *
 * <p>它让 agent 在动手前读得到对方已进主干的改动，有机会自己发现重复的类，比合并时的审查更早。
 * 见 {@code docs/decisions/architecture/2026-09-29-sync-before-merge}。
 *
 * <p>必须落库：同步会推进会话 HEAD（合并提交或快进），{@code session.head_commit} 必须与磁盘
 * 一致，否则回滚、checkpoint 和下一次合并都会按错的位置算。事件与列在同一事务里写入，见
 * {@code docs/decisions/architecture/2026-09-24-one-event-log-as-the-source-of-truth}。
 *
 * <p>不进上下文：模型需要知道的是代码变了，而那读文件就看得见。
 *
 * @param fromHead 同步前的 HEAD
 * @param toHead   同步后的 HEAD。快进时是主干那个新提交，产生合并提交时是那个提交本身
 * @throws IllegalArgumentException 任一 HEAD 为空，或两者相同（没有变化就不该落事件）
 */
public record SessionSynced(String fromHead, String toHead) implements PersistentEvent {

    public SessionSynced {
        if (fromHead == null || fromHead.isBlank()) {
            throw new IllegalArgumentException("同步前的 HEAD 不能为空");
        }
        if (toHead == null || toHead.isBlank()) {
            throw new IllegalArgumentException("同步后的 HEAD 不能为空");
        }
        if (fromHead.equals(toHead)) {
            // HEAD 没动就不该有这条事件 —— 没有变化的事实用不着记
            throw new IllegalArgumentException(
                    "同步前后的 HEAD 相同（" + fromHead + "），这一轮什么都没发生，不该落事件");
        }
    }
}
