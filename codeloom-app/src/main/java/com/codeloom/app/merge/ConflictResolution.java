package com.codeloom.app.merge;

/**
 * 人对一个冲突做的裁决。
 *
 * <p>人总共有**三个**选择，而它们收敛成两种形态：取一侧（有两个方向），或者给出自己的内容。
 *
 * <h2>为什么"取我这侧 / 取你那侧"不够</h2>
 * 缺的那个选择恰恰是**最常见的**：
 *
 * <pre>
 *   基点：    class Main { }
 *   A 加了：  void printHello() { … }
 *   B 加了：  void printHi() { … }
 * </pre>
 *
 * 正确答案是两份都留 —— 而取任何一侧都会丢掉另一个人的方法。
 * 同理，两个人把同一行改成不同东西时，答案常常也不是其中任何一份原样，而是各取一半。
 *
 * <p>所以第三种是"人直接给出他要的那份内容"。这不是把责任推给人：
 * 这两类冲突**本来就判不了**（见 {@code MergeResult} 的类注释：
 * 代码不是富文本，自动合并会产出语义上错误的代码），
 * 我们能做的是让人表达得出来，而不是逼他在两个都不对的选项里挑一个。
 *
 * <p>做成 sealed 的用处在这里很直接：处理它的那个 switch 必须写全三种 ——
 * 将来再加一种裁决方式（比如"按行选"），编译器会逼着人回来处理。
 */
public sealed interface ConflictResolution {

    /**
     * 整份取一侧。
     *
     * <p>参数是**语义上的哪一侧**，不是 git 的 {@code ours}/{@code theirs} ——
     * 那两个词随合并方向反转，而"会话侧 / 主干侧"永远指同一份东西。
     *
     * <p>方向到 {@code ours} 的映射收在 {@code ProjectMerger.resolve} 里，
     * 因为只有那里知道这次合并是哪个方向。这条规矩立在接口上，是因为它就是
     * {@link MergeDirection} 那条：**git 的词汇只活在适配层**。
     */
    record KeepSide(Side side) implements ConflictResolution {

        /** 取哪一侧。这两个值不随合并方向变。 */
        public enum Side {

            /** 这条**会话的工作区**那一侧。 */
            SESSION,

            /** **主干**那一侧（也就是别人已经合进去的）。 */
            MAIN
        }
    }

    /** 人给出裁决之后的完整文件内容。两个方法都留、各取一半，都靠它。 */
    record WithContent(String content) implements ConflictResolution {
    }
}
