package com.codeloom.app.merge;

/**
 * 一次合并的**方向** —— 也就是"谁往谁里合"。
 *
 * <h2>为什么这个概念必须显式存在</h2>
 * git 报冲突时用的是 {@code ours} / {@code theirs}，而这两个词是**相对当前分支**的：
 * 同一份冲突，方向反过来，"ours"指的东西就反过来了。
 *
 * <pre>
 *   INTO_MAIN     （会话 → 主干）：ours = 主干侧，theirs = 会话侧
 *   INTO_SESSION  （主干 → 会话）：ours = 会话侧，theirs = 主干侧   ← 反的
 * </pre>
 *
 * <p>把它暴露到接口上的理由很直接：**界面要能说人话**。用户在同步冲突里想看的是
 * "我的工作区"和"主干"，而不是"ours 和 theirs"。如果后端只给 git 的词汇，
 * 那个翻转的规则就得由前端再实现一遍 —— 而那是错一次就静默吃掉一边代码的地方。
 *
 * <p>所以约定是：**接口层一律用"会话侧 / 主干侧"，{@code ours}/{@code theirs} 只活在
 * git 适配那一层。** 这一条在 {@link ConflictView} 和 {@link MergeController.ResolveRequest ResolveRequest} 上各落一次。
 */
public enum MergeDirection {

    /** 把**会话的产出合进主干**。冲突留在主干的工作区里（待裁决期间主干被占住）。 */
    INTO_MAIN,

    /** 把**主干合进会话的工作区**（同步）。冲突留在会话的工作区里，主干保持干净。 */
    INTO_SESSION
}
