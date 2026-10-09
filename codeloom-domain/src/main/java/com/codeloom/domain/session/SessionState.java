package com.codeloom.domain.session;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 会话状态机。
 *
 * <p>崩溃恢复、中断语义、并发执行租约三件事都以这个状态机为落点。
 *
 * <pre>
 *   IDLE ──→ THINKING ──→ EXECUTING_TOOL ──→ THINKING ──→ WAITING_USER
 *                            │                                 ↑
 *                            └──→ AWAITING_APPROVAL ───────────┘
 *
 *   任意状态 ──→ FAILED ──→ IDLE（用户重试）
 * </pre>
 *
 * <h2>为什么没有 DONE</h2>
 * 会话的收尾状态一律是 {@link #WAITING_USER}（等用户下一句话），没有任何代码能进入
 * 一个"正常结束"的终态。别加这样一个状态 —— 它到不了，却会拖着一串东西一起空转：
 * 终态判定、崩溃恢复的 SQL 判据、以及各自的测试。
 *
 * <p>真要支持"显式结束一条会话"时，把状态和它的迁移一起加回来 —— 那时它才有生产者。
 */
public enum SessionState {

    /** 空闲：没有正在执行的 turn。 */
    IDLE,

    /** 模型正在推理（可能正在流式输出）。 */
    THINKING,

    /** 正在执行工具调用（改文件、跑构建/测试）。 */
    EXECUTING_TOOL,

    /** 一轮已结束，等用户下一步指示。**会话停在这里就是常态**，不是异常。 */
    WAITING_USER,

    /**
     * 卡在一个**需要人来批**的工具调用上，等答复。
     *
     * <p>它既不是"正在跑"（那一轮已经结束了），也不是"跑完了"（还没收尾）——
     * 答复到了才进 {@link #WAITING_USER}。
     *
     * <p>它**不是终态**：会话真的还挂在那儿等，崩溃恢复要能扫到它。
     */
    AWAITING_APPROVAL,

    /** 不可恢复错误。可由用户重试回到 IDLE。 */
    FAILED;

    /** 显式列出的合法迁移；不包含「任何状态 → FAILED」这条兜底规则，见 {@link #canTransitionTo}。 */
    private static final Map<SessionState, Set<SessionState>> EXPLICIT;

    static {
        Map<SessionState, Set<SessionState>> m = new EnumMap<>(SessionState.class);
        m.put(IDLE, EnumSet.of(THINKING));
        m.put(THINKING, EnumSet.of(EXECUTING_TOOL, WAITING_USER));
        // 挂起总是发生在**工具阶段**：模型请求了调用（→ EXECUTING_TOOL），
        // 平台判定它要人批，于是从那里挂起。THINKING 直接挂起的情况不存在 ——
        // 那样的话就没有"要批的调用"可言了
        m.put(EXECUTING_TOOL, EnumSet.of(THINKING, AWAITING_APPROVAL));
        m.put(WAITING_USER, EnumSet.of(THINKING));
        m.put(AWAITING_APPROVAL, EnumSet.of(WAITING_USER));
        m.put(FAILED, EnumSet.of(IDLE));
        EXPLICIT = Map.copyOf(m);
    }

    /**
     * 是否允许迁移到 {@code next}。
     *
     * <p>除了显式表，还有一条兜底规则：**任何状态都可以进入 FAILED**。这是刻意的 ——
     * 模型调用超时、进程被杀、租约丢失等异常场景不该被状态机拦住，否则错误只能被吞掉。
     */
    public boolean canTransitionTo(SessionState next) {
        if (next == null || next == this) {
            // 自迁移没有意义（FAILED → FAILED 同理），一律拒绝 —— 否则兜底规则会把它放行
            return false;
        }
        if (next == FAILED) {
            return true;
        }
        return EXPLICIT.get(this).contains(next);
    }

    /**
     * 执行迁移，非法则抛 {@link IllegalStateTransitionException}。
     *
     * @return {@code next}，便于链式写法
     */
    public SessionState transitionTo(SessionState next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateTransitionException(this, next);
        }
        return next;
    }
}
