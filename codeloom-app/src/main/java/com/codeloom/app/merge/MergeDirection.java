package com.codeloom.app.merge;

/**
 * 合并方向：谁往谁里合。
 *
 * <p>git 的 {@code ours}/{@code theirs} 相对当前分支，方向一反过来含义就反了：
 * {@code INTO_MAIN} 时 ours 是主干侧、theirs 是会话侧，{@code INTO_SESSION} 时相反。
 * 接口层一律用"会话侧 / 主干侧"，这两个 git 词汇只活在 git 适配层；否则前端要自己
 * 再实现一次翻转，错一次就会静默丢掉一侧的代码。
 *
 * @see ConflictView
 * @see MergeController.ResolveRequest
 */
public enum MergeDirection {

    /** 把会话的产出合进主干。冲突留在主干的工作区里，待裁决期间主干被占住。 */
    INTO_MAIN,

    /** 把主干合进会话的工作区（同步）。冲突留在会话的工作区里，主干保持干净。 */
    INTO_SESSION
}
