package com.codeloom.agent.context;

import com.codeloom.domain.event.SessionRewound;
import com.codeloom.domain.event.StoredEvent;

/**
 * 投影里一份跟着事件走的派生事实：消息之外、投影需要额外记住的状态。
 *
 * <p>实现必须能接收每条事件（不产出消息的事件也要），并在遇到 {@code SessionRewound} 时
 * 自行决定退到哪一步。推倒重来之前由投影统一调用 {@link #clear()}；这一义务由
 * {@link ContextAssembler.Projection} 的遍历保证，现有事实对重复喂入的产出恰好相同，无法靠测试区分。
 * 见 {@code docs/decisions/bug-fix/2026-10-10-a-rewind-takes-the-derived-facts-with-it.md}。
 */
interface Derived {

    /**
     * @param stored 要消化的事件；同一条只会喂一遍
     */
    void accept(StoredEvent stored);

    /** 回到“一条都还没处理”的样子，推倒重来之前调用。 */
    void clear();

    /**
     * 回滚的切点：seq 不超过它的事件仍然作数。
     *
     * <p>切点为空（旧数据没有该字段）时取最小值，整段作废；拿不准时宁可多丢。
     *
     * @param rewound 回滚事件
     * @return 切点 seq；{@link Long#MIN_VALUE} 表示全部作废
     */
    static long cutOf(SessionRewound rewound) {
        return rewound.toCheckpointSeq() == null ? Long.MIN_VALUE : rewound.toCheckpointSeq();
    }
}
