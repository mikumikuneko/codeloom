package com.codeloom.domain.session;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 会话状态机，崩溃恢复、中断语义与并发执行租约都以它为落点。
 *
 * <p>正常路径是 IDLE → THINKING → EXECUTING_TOOL → THINKING → WAITING_USER；
 * EXECUTING_TOOL 也可转 AWAITING_APPROVAL，答复后回 WAITING_USER；用户说下一句时
 * WAITING_USER 回到 THINKING。任意状态都可进入 FAILED，FAILED 只能回到 IDLE。
 *
 * <p>没有 DONE：收尾状态一律是 {@link #WAITING_USER}，目前没有代码能进入"正常结束"的终态。
 * 别加它——它到不了，却会拖着终态判定与崩溃恢复的 SQL 判据空转；真要支持时再加回状态与迁移。
 */
public enum SessionState {

    /** 空闲：没有正在执行的 turn。 */
    IDLE,

    /** 模型正在推理（可能正在流式输出）。 */
    THINKING,

    /** 正在执行工具调用（改文件、跑构建/测试）。 */
    EXECUTING_TOOL,

    /** 一轮已结束，等用户下一步指示。会话停在这里就是常态，不是异常。 */
    WAITING_USER,

    /**
     * 卡在一个需要人来批的工具调用上，等答复。
     *
     * <p>它既不是"正在跑"（那一轮已经结束），也不是"跑完了"（还没收尾），答复到了才进
     * {@link #WAITING_USER}。它不是终态：崩溃恢复要能扫到它。
     */
    AWAITING_APPROVAL,

    /** 不可恢复错误。可由用户重试回到 IDLE。 */
    FAILED;

    /** 显式列出的合法迁移；不含"任何状态 → FAILED"这条额外规则，见 {@link #canTransitionTo}。 */
    private static final Map<SessionState, Set<SessionState>> EXPLICIT;

    static {
        Map<SessionState, Set<SessionState>> m = new EnumMap<>(SessionState.class);
        m.put(IDLE, EnumSet.of(THINKING));
        m.put(THINKING, EnumSet.of(EXECUTING_TOOL, WAITING_USER));
        // 挂起只发生在工具阶段：模型已请求调用（→ EXECUTING_TOOL），平台判定要人批；
        // THINKING 没有可批的调用，不会直接挂起
        m.put(EXECUTING_TOOL, EnumSet.of(THINKING, AWAITING_APPROVAL));
        m.put(WAITING_USER, EnumSet.of(THINKING));
        m.put(AWAITING_APPROVAL, EnumSet.of(WAITING_USER));
        m.put(FAILED, EnumSet.of(IDLE));
        EXPLICIT = Map.copyOf(m);
    }

    /**
     * 是否允许迁移到 {@code next}。
     *
     * <p>除显式表外，任何状态都可以进入 FAILED：模型调用超时、进程被杀、租约丢失等异常
     * 不该被状态机拦住，否则错误只能被吞掉。
     *
     * @param next 目标状态
     * @return 允许迁移时为 true；自迁移一律为 false
     */
    public boolean canTransitionTo(SessionState next) {
        if (next == null || next == this) {
            // 自迁移一律拒绝，否则下面那条 FAILED 规则会放行 FAILED → FAILED
            return false;
        }
        if (next == FAILED) {
            return true;
        }
        return EXPLICIT.get(this).contains(next);
    }

    /**
     * 执行迁移。
     *
     * @param next 目标状态
     * @return {@code next}，便于链式写法
     * @throws IllegalStateTransitionException 迁移不合法时
     */
    public SessionState transitionTo(SessionState next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateTransitionException(this, next);
        }
        return next;
    }
}
