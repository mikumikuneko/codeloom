package com.codeloom.domain.session;

/**
 * 非法的会话状态迁移。抛异常而不是记日志放过：状态机出错意味着并发或恢复逻辑写错了，
 * 放过只会让会话卡在某个状态、错误推迟到更难排查的地方；早失败时堆栈还留着现场。
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final transient SessionState from;
    private final transient SessionState to;

    /**
     * 记录一次被拒绝的迁移。
     *
     * @param from 迁移前状态
     * @param to   被拒绝的目标状态
     */
    public IllegalStateTransitionException(SessionState from, SessionState to) {
        super("非法的会话状态迁移: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    /**
     * 迁移前所处的状态。
     *
     * @return 迁移前状态
     */
    public SessionState from() {
        return from;
    }

    /**
     * 被拒绝的目标状态。
     *
     * @return 未能迁到的状态
     */
    public SessionState to() {
        return to;
    }
}
