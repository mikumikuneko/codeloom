package com.codeloom.app.merge;

/**
 * 一个冲突文件，连同**两侧的全文** —— 界面上的并排 diff 用它。
 *
 * <h2>为什么字段叫 sessionSide / mainSide，而不是 ours / theirs</h2>
 * 因为 {@code ours} / {@code theirs} 是**相对当前分支**的，方向一变就反过来：
 * 合回主干时 {@code ours} 是主干侧，同步时 {@code ours} 是会话侧。
 *
 * <p>那两个词留在 git 适配那一层就够了。到了接口上，两侧是哪两边**永远是同一件事**：
 * 一边是**这条会话的工作区**，另一边是**主干**。所以这里按"是哪边"命名，前端不需要
 * 知道这次是哪个方向、也不需要做任何翻转 —— 它只要把 {@code sessionSide} 标成
 * "我的工作区"、{@code mainSide} 标成"主干"，两种方向下都对。
 *
 * @param path        相对工作区的路径
 * @param sessionSide **会话工作区**那一侧的全文
 * @param mainSide    **主干**那一侧的全文
 */
public record ConflictView(String path, String sessionSide, String mainSide) {
}
