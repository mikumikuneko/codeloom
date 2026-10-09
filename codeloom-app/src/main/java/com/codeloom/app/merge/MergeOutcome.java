package com.codeloom.app.merge;

import com.codeloom.domain.event.VerificationResult;

import java.util.List;

/**
 * 一次合并（或一次裁决）之后的全部结论。
 *
 * <p>合并和裁决**共用这一个形状**，因为它们对客户端是同一件事的两个阶段：
 * "把这条会话的产出合进主干" —— 中间可能被冲突打断，然后接着走完。
 *
 * @param status            {@code MERGED} / {@code FAST_FORWARD} / {@code UP_TO_DATE}，
 *                          或者 {@code CONFLICT_PENDING}（还没裁完）
 * @param mergeCommitSha    合并落地之后的提交 sha；还没落地时为 null
 * @param verification      **主干上的验证结论**。为 null 有两种情况：没有可认的构建文件
 *                          （不硬跑一条可能错的命令），或者**这次收尾的合并根本不在主干上**
 *                          （一次同步的冲突裁决 —— 它动的是会话的工作区，主干没被碰过）
 * @param remainingConflicts 还没裁决的文件
 */
public record MergeOutcome(String status,
                           String mergeCommitSha,
                           VerificationResult verification,
                           List<String> remainingConflicts) {

    public MergeOutcome {
        remainingConflicts = List.copyOf(remainingConflicts);
    }

    public static MergeOutcome of(String status, String mergeCommitSha,
                                 VerificationResult verification, List<String> remainingConflicts) {
        return new MergeOutcome(status, mergeCommitSha, verification, remainingConflicts);
    }
}
