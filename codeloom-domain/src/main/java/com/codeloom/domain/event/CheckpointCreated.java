package com.codeloom.domain.event;

/**
 * 打了一个 checkpoint：**每一轮结束时**自动打，建会话时也有一条（还没跑完任何一轮的那个位置）。
 *
 * <p>回滚 = 代码 reset 到这个 commit sha + 对话历史截断到这儿，**两者绑死**：
 * 不提供「只回滚其中一个」。
 *
 * <p>这是刻意收紧的：只回代码不截对话，模型会对着一个已经不存在的现状继续推理；
 * 只截对话不回代码，磁盘上的改动则找不到任何解释。
 *
 * <h2>{@code commitSha} 不唯一，别拿它当定位键</h2>
 * 一轮什么都没改时（问一句、答一句，没动任何文件），{@code commit} 返回的还是上一个
 * HEAD，于是这条 checkpoint 和上一条**sha 一模一样**。所以同一个 sha 在一条会话里
 * 出现好几次是常态，它只能回答"代码此刻在哪"，回答不了"你指的是这两次里的哪一次"。
 *
 * <p>要指出**具体哪一条** checkpoint（回滚就是这么用的），用它在事件流里的序号 ——
 * 序号全局唯一，见 {@link SessionRewound#toCheckpointSeq()}。
 *
 * @param commitSha 会话分支上的一个 commit
 * @param turnIndex **到这儿为止，这条会话完成了几次交互**（0 = 一次都还没跑完），
 *                  和会话行上的 {@code turn_index} 是同一个数、同一个意思。
 *                  它和 {@code commitSha} 一起把「代码位置」和「对话位置」对上，
 *                  也是回滚界面上给人看的那个编号。
 *
 *                  <p>一轮 = 用户说一句话、模型答完。所以**挂着等人批的那一轮不推它**：
 *                  那次交互还没答完（见 {@code SessionWriter.completes}）。它写下的
 *                  checkpoint 于是和上一条同号 —— 要区分这两条，同样得看它们在事件流里
 *                  的先后，光看这个数不够。
 */
public record CheckpointCreated(String commitSha, int turnIndex) implements PersistentEvent {
}
