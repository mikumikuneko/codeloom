package com.codeloom.domain.port;

import java.util.List;
import java.util.Objects;

/**
 * 两条会话分支汇合的结果。
 *
 * <h2>冲突一律升级给人，不做自动合并</h2>
 * 代码不是富文本，OT / CRDT 那套能解决"合到一起"，却会产出**语义上错误的代码**：
 * 两个分支各自合法，合并后编译不过。所以这里只做三方合并，出现冲突就停下，
 * 把文件清单交给用户裁决。
 *
 * @param conflictingPaths 有冲突的文件路径；无冲突时为空列表
 * @param headCommit       这次合并尝试之后工作区的 HEAD。有冲突时它**没有动**
 *                         （仍然指向合并前那个提交）—— 那正是"停下来等人裁决"的体现。
 *                         调用方要它是因为两件事：告诉用户"合到了哪个提交"，
 *                         以及把验证结论绑到**具体那个 commit**上
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
        /** 对方是当前分支的祖先，直接快进。 */
        FAST_FORWARD,
        /** 产生了合并提交，无冲突。 */
        MERGED,
        /** 有冲突，已停下，等人工裁决。 */
        CONFLICT,
        /** 两边已经一致，无需操作。 */
        UP_TO_DATE
    }
}
