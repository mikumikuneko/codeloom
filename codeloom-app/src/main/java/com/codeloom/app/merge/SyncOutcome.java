package com.codeloom.app.merge;

/**
 * 一次同步的结果。
 *
 * @param status   {@code UP_TO_DATE}（主干没前进，什么都没做）/ {@code FAST_FORWARD}
 *                 （这条会话的 HEAD 直接跟上了主干）/ {@code MERGED}（产生了一个合并提交）
 * @param fromHead 同步前的 HEAD。{@code UP_TO_DATE} 时为 null —— 没发生的事不编数字
 * @param toHead   同步后的 HEAD。同样，{@code UP_TO_DATE} 时为 null
 */
public record SyncOutcome(String status, String fromHead, String toHead) {

    /**
     * 主干没动，这次同步什么都没做。
     *
     * <p><strong>不落事件</strong> —— "没有变化的事实"不该进事件流。而且 {@code SessionSynced}
     * 的构造器本来就拒绝前后 HEAD 相同，那条不变量把这条规矩钉在了类型上。
     */
    static SyncOutcome upToDate() {
        return new SyncOutcome("UP_TO_DATE", null, null);
    }
}
