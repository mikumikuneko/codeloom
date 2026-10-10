package com.codeloom.domain.workspace;

import java.util.Objects;

/**
 * 一个文件在某一步里改了什么 —— 一段统一的 diff 正文。
 *
 * <p>它是 {@link FileChange} 的另一种粒度：那个说"改了哪些、各增删多少行"，
 * 这个说"那一个文件到底变成了什么样"。两者问的是**同一个区间**，
 * 所以它们不会对不上（界面上说改了 3 行、点开却有 40 行，是很难查的一种不一致）。
 *
 * @param text      diff 正文。**可能是被截断的**，见 {@link #truncated}
 * @param truncated 是不是截断了。**必须如实传出去** —— 一份被截断的 diff 与
 *                  "只改了这么多"长得一模一样，而那是两件事。跟增删行数那边同一个道理
 */
public record FileDiff(String text, boolean truncated) {

    public FileDiff {
        Objects.requireNonNull(text, "text");
    }
}
