package com.codeloom.app.merge;

/**
 * 一个冲突文件及两侧全文，界面上的并排 diff 用它。
 *
 * <p>字段不用 git 的 {@code ours}/{@code theirs}：那两个词相对当前分支，方向一变就反过来。
 * 这里按语义命名，{@code sessionSide} 永远是会话工作区那侧、{@code mainSide} 永远是
 * 主干那侧，前端不需要知道合并方向。
 *
 * @param path        项目路径
 * @param sessionSide 会话工作区那一侧的全文
 * @param mainSide    主干那一侧的全文
 */
public record ConflictView(String path, String sessionSide, String mainSide) {
}
