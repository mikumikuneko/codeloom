package com.codeloom.domain.event;

/**
 * 打了一个 checkpoint：一轮收尾时有可回的位置就写一条 —— 正常结束、失败/取消、挂着等人批
 * 都写，提交失败或没有工作区时跳过。建会话时也有一条（第 0 轮之前的位置）。见
 * {@code docs/decisions/architecture/2026-09-26-every-turn-commits-and-the-zero-checkpoint}。
 *
 * <p>回滚 = 代码 reset 到这个 commit 并把对话截断到这里，两者绑死，不提供只回滚其中一个：只回
 * 代码，模型会对着已不存在的现状推理；只截对话，磁盘上的改动找不到解释。
 *
 * <p>{@code commitSha} 不唯一（一轮没改时它和上一个 HEAD 相同），不能当定位键；定位用
 * {@link SessionRewound#toCheckpointSeq()}，见
 * {@code docs/decisions/architecture/2026-09-29-rewind-the-whole-tree}。
 *
 * @param commitSha 工作区分支上的一个 commit
 * @param turnIndex 到这儿为止完成的交互数（0 = 一次都没跑完），与会话行的 {@code turn_index}
 *                  同值同义，也是回滚界面上显示的编号。挂着等人批的那一轮不推它（见
 *                  {@code SessionWriter.completes}），所以两条 checkpoint 可能同号；区分它们
 *                  要看事件流里的先后。
 */
public record CheckpointCreated(String commitSha, int turnIndex) implements PersistentEvent {
}
