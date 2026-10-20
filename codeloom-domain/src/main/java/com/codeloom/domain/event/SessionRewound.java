package com.codeloom.domain.event;

import com.codeloom.domain.user.UserId;

/**
 * 会话被回滚到某个 checkpoint，回滚本身也要记录，否则回放时只能看到代码退回而无解释。
 *
 * <p>定位键是事件序号，不是 {@code toCommitSha}：一轮没改时 sha 会重复，按 sha 找最早匹配会把
 * 整段对话清空；序号全局唯一。sha 仍保留，作为回滚后的 git 位置供审计与 diff 使用。不要再加
 * "退到第几轮"字段 —— 轮次答不了"退到哪句话之前"，而轮次仍可从 checkpoint 的
 * {@code turnIndex} 读出。见
 * {@code docs/decisions/architecture/2026-09-29-rewind-the-whole-tree}。
 *
 * @param toCommitSha     回滚之后的代码位置；审计和 diff 用它，定位不用它
 * @param toCheckpointSeq 退回到的那条 checkpoint 的事件序号 —— 定位用它。用包装类型：缺失读成
 *                        0，而 0 看着像位置；投影遇到空值宁可多退一点（见
 *                        {@code docs/decisions/architecture/2026-10-04-nullable-only-when-zero-is-a-lie}）
 * @param byUserId        谁发起的回滚。回滚影响共享工作树，必须记 id；用户名由读的一侧现查
 *                        （见
 *                        {@code docs/decisions/architecture/2026-09-26-keys-and-identity-are-per-provider}）
 */
public record SessionRewound(String toCommitSha, Long toCheckpointSeq, UserId byUserId)
        implements PersistentEvent {

    public SessionRewound {
        if (byUserId == null) {
            throw new IllegalArgumentException("回滚必须记清是谁下的手 —— 它动的是共享的那棵树");
        }
    }
}
