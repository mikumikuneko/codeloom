package com.codeloom.app.merge;

import com.codeloom.domain.event.VerificationResult;

import java.util.List;

/**
 * 一次合并或裁决之后的全部结论。
 *
 * <p>合并和裁决共用这个形状，它们是客户端眼里同一件事的两个阶段。
 *
 * @param status             {@code MERGED}、{@code FAST_FORWARD}、{@code UP_TO_DATE}，
 *                           或 {@code CONFLICT_PENDING}（还没裁完）
 * @param mergeCommitSha     合并落地后的提交 sha；还没落地时为 null
 * @param verification       主干上的验证结论。为 null 有两种情况：没有可识别的构建文件
 *                           （不硬跑可能错的命令），或收尾的合并不在主干上（同步的裁决）
 * @param remainingConflicts 还没裁决的文件
 */
public record MergeOutcome(String status,
                           String mergeCommitSha,
                           VerificationResult verification,
                           List<String> remainingConflicts) {

    public MergeOutcome {
        remainingConflicts = List.copyOf(remainingConflicts);
    }

    /**
     * @param status             合并状态
     * @param mergeCommitSha     合并提交；未落地时为 null
     * @param verification       主干验证结论；未验证时为 null
     * @param remainingConflicts 剩余冲突文件
     * @return 合并结论
     */
    public static MergeOutcome of(String status, String mergeCommitSha,
                                 VerificationResult verification, List<String> remainingConflicts) {
        return new MergeOutcome(status, mergeCommitSha, verification, remainingConflicts);
    }
}
