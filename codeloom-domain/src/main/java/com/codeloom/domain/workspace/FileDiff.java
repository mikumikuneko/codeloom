package com.codeloom.domain.workspace;

import java.util.Objects;

/**
 * 一个文件在某一步里的统一 diff 正文，是 {@link FileChange} 的另一种粒度；两者看同一
 * 区间，所以不会出现"说改了 3 行、点开却有 40 行"。
 *
 * <p>{@code truncated} 必须如实传出：被截断的 diff 与"只改了这么多"长得一样，而那是
 * 两件事。见 {@code docs/decisions/feature/2026-10-10-file-diff-is-fetched-on-demand.md}。
 *
 * @param text      diff 正文，可能被截断，见 {@link #truncated}
 * @param truncated 是否截断
 */
public record FileDiff(String text, boolean truncated) {

    public FileDiff {
        Objects.requireNonNull(text, "text");
    }
}
