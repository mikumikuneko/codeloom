package com.codeloom.domain.event;

/**
 * 把主干**同步进了这条会话的工作区** —— 合并的反方向。
 *
 * <h2>为什么要有这个动作</h2>
 * 每条会话的工作区是**从主干分出来的**，之后两条线各走各的：对方合进主干的东西，
 * 我这边不会自动有。于是两个人会**盲撞** —— 各造一个类，谁也不知道对方也造了。
 *
 * <p>同步就是把对方已经进主干的改动**拉进我的工作区**。它的价值不只是"少点冲突"：
 * 那些代码进了我的工作区之后，**我的 agent 读得到** —— 它就有机会自己发现"已经有个
 * 同名/同职责的类了"。而这一点是任何"事后审查"都替代不了的：审查发生在合的那一刻，
 * 而同步发生在**动手之前**。
 *
 * <h2>为什么它必须落库</h2>
 * 同步会让会话的 HEAD 前进（产生一个合并提交，或者快进）。而 {@code session.head_commit}
 * 那一列必须和磁盘一致 —— 不然回滚、checkpoint、下一次合并全都会按一个错的位置去算。
 *
 * <p>而按这个项目的规矩，**状态变化要落事件**（事件流是唯一事实来源）。所以同步不能
 * 只是"改一下那一列"，它得像 {@code finishTurn} 那样：事件和列在同一个事务里一起动。
 *
 * <h2>它不进上下文</h2>
 * 模型不需要知道"执行了一次同步" —— 它需要知道的是**代码变了**，而那件事它读文件就看得见。
 * 把这条塞进上下文只会让每一轮的消息前缀多一段和它的判断无关的东西。
 *
 * @param fromHead 同步前的 HEAD
 * @param toHead   同步后的 HEAD。**快进时它是主干那个新提交，产生合并提交时它是那个提交本身**
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
