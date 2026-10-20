package com.codeloom.app.merge;

/**
 * 人对一个冲突的裁决：整份取某一侧，或给出自己的完整内容。
 *
 * <p>只提供「取一侧」不够：两个人各往同一文件加一个方法时，正确答案是两份都留，
 * 取任何一侧都会丢掉另一个人的方法。这类语义冲突机器判不了，所以必须让人直接
 * 表达结果，而不是逼人在两个都不对的选项里挑一个。
 *
 * <p>sealed 让处理它的 switch 必须写全所有形态；新增一种裁决方式时编译不过。
 */
public sealed interface ConflictResolution {

    /**
     * 整份取一侧。
     *
     * <p>{@code side} 是语义上的哪一侧，不是 git 的 {@code ours}/{@code theirs}：
     * 后者随合并方向反转，而会话侧 / 主干侧永远指同一份东西。方向到 {@code ours}
     * 的映射在 {@code ProjectMerger.resolve}，见 {@link MergeDirection}。
     *
     * @param side 取哪一侧；会话侧或主干侧
     */
    record KeepSide(Side side) implements ConflictResolution {

        /** 取哪一侧；这两个值不随合并方向变。 */
        public enum Side {

            /** 这条会话的工作区那一侧。 */
            SESSION,

            /** 主干那一侧，也就是别人已经合进去的内容。 */
            MAIN
        }
    }

    /**
     * 人给出的裁决后完整文件内容；保留两侧改动或各取一半都靠它。
     *
     * @param content 裁决后的完整内容，原样写入冲突文件
     */
    record WithContent(String content) implements ConflictResolution {
    }
}
