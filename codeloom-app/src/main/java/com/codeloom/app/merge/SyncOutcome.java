package com.codeloom.app.merge;

/**
 * 一次同步的结果。
 *
 * @param status   {@code UP_TO_DATE}（主干没前进，什么都没做）、{@code FAST_FORWARD}
 *                 （会话 HEAD 直接跟上主干）或 {@code MERGED}（产生了合并提交）
 * @param fromHead 同步前的 HEAD；{@code UP_TO_DATE} 时为 null
 * @param toHead   同步后的 HEAD；{@code UP_TO_DATE} 时为 null
 */
public record SyncOutcome(String status, String fromHead, String toHead) {

    /**
     * 主干没动，这次同步什么都没做；不落事件。"没有变化的事实"不进事件流，
     * 且 {@code SessionSynced} 的构造器本就拒绝前后 HEAD 相同。
     *
     * @return 状态为 {@code UP_TO_DATE}、两个 HEAD 都为 null 的结果
     */
    static SyncOutcome upToDate() {
        return new SyncOutcome("UP_TO_DATE", null, null);
    }
}
