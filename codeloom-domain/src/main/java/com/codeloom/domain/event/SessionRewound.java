package com.codeloom.domain.event;

import com.codeloom.domain.user.UserId;

/**
 * 会话被回滚到某个 checkpoint。
 *
 * <p>回滚本身也要留痕 —— 否则回放时会看到一段「代码突然退回旧版本」，而没有任何解释。
 *
 * <h2>定位键是「事件序号」，不是 {@code toCommitSha}</h2>
 * 拿 sha 找位置会退到**最早**那个同 sha 的地方（一轮什么都没改时 sha 会和上一条重复，
 * 见 {@link CheckpointCreated}）—— 用户选的是"退到第三句之前"，得到的是整个对话清空。
 * 序号全局唯一，所以定位用 {@code toCheckpointSeq}。sha 仍然留着：它是回滚之后的
 * git 位置，审计要用它，只是不再承担定位。
 *
 * <h2>为什么只有这三样</h2>
 * **别再加「退到第几轮」这类字段**：轮次看着像个位置，却答不了"退到哪句话之前"，
 * 而一条没人读的字段会把人引向错的定位方式 ——
 * 一轮 = 用户说一句话、模型答完，而批准之后**续跑**的那一轮会再落一个 checkpoint
 * 却没有新的用户消息，于是后端的轮次号和界面上的消息块数就此分开（界面数的是**块数**）。
 *
 * <p>轮次并没有丢：它写在会话行上（回滚之后从哪儿继续数就靠它），要还原的话，
 * 从 {@code toCheckpointSeq} 指向的那条 checkpoint 里就能读出来 —— 它自己带 {@code turnIndex}。
 *
 * <h2>为什么留着「谁退的」，以及为什么记的是 id 而不是用户名</h2>
 * 两个人共用一个仓库，回滚影响的是**对方的工作基础** —— 事后必须查得出是谁、什么时候下的手。
 * 回滚提示也念它（"root 回滚到「……」之前"）。
 *
 * <p>记 id 而不是用户名：**用户名会变**。唯一性（库里账号、用户名各一条唯一键）管的是
 * "此刻没有两个人同名"，管不了"这句话是三年前谁说的" —— 改过名之后，旧事件里那个名字
 * 就对不上人了。用户名是**读的那一侧**的事：读的人手里有成员表，按 id 现查，
 * 于是显示的永远是**现在**的名字。
 *
 * <p>{@code toCheckpointSeq} 用包装类型而不是 {@code long}：字段缺了会被读成 {@code 0}，
 * 而 0 是个**看着像位置**的值（seq 从 1 开始，它其实指不到任何一条 checkpoint）。
 *
 * <p>真的碰上没记位置的事件（序号为空），投影只能保守处理：**当作"查不到边界"**，
 * 退得比该退的多一点 —— 那比"退到一个假位置"安全（见 {@code ContextAssembler}）。
 *
 * @param toCommitSha     退回到的 commit —— 这次回滚之后的**代码位置**。
 *                        审计和 diff 记账用它，定位不用它（理由见上）
 * @param toCheckpointSeq 退回到的那条 checkpoint 在事件流里的序号 —— **定位就用它**，
 *                        理由见上
 * @param byUserId        谁发起的回滚。**记 id**（理由见上）；用户名由读的那一侧按它去查
 */
public record SessionRewound(String toCommitSha, Long toCheckpointSeq, UserId byUserId)
        implements PersistentEvent {

    public SessionRewound {
        if (byUserId == null) {
            throw new IllegalArgumentException("回滚必须记清是谁下的手 —— 它动的是共享的那棵树");
        }
    }
}
