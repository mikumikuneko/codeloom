package com.codeloom.domain.port;

import java.util.List;
import java.util.Objects;

/**
 * 两条会话分支汇合的结果。冲突一律升级给人，不做自动合并：代码不是富文本，OT / CRDT
 * 能解决「合到一起」，却可能产出语义上错误的代码 —— 两个分支各自合法，合并后编译不过。
 * 所以只做三方合并，出现冲突就停下，把文件清单交给用户裁决。构造时校验状态与冲突清单
 * 一致：{@code CONFLICT} 当且仅当清单非空。见
 * {@code docs/decisions/architecture/2026-09-25-merge-model-without-pull-requests.md}。
 *
 * @param status           合并结果状态
 * @param conflictingPaths 有冲突的文件路径；无冲突时为空列表
 * @param headCommit       这次合并尝试之后工作区的 HEAD。有冲突时它没有动（仍指向合并前
 *                         的提交），那正是「停下来等人裁决」。调用方用它告诉用户合到了
 *                         哪个提交，并把验证结论绑到具体那个 commit 上
 */
public record MergeResult(Status status, List<String> conflictingPaths, String headCommit) {

    public MergeResult {
        Objects.requireNonNull(status, "status");
        conflictingPaths = List.copyOf(Objects.requireNonNull(conflictingPaths, "conflictingPaths"));
        if ((status == Status.CONFLICT) == conflictingPaths.isEmpty()) {
            throw new IllegalArgumentException(
                    "状态与冲突清单不一致：" + status + " / " + conflictingPaths.size() + " 个冲突文件");
        }
    }

    public enum Status {
        /** 当前分支是对方的祖先，直接快进。 */
        FAST_FORWARD,
        /** 产生了合并提交，无冲突。 */
        MERGED,
        /** 有冲突，已停下，等人工裁决。 */
        CONFLICT,
        /** 两边已经一致，无需操作。 */
        UP_TO_DATE
    }
}
