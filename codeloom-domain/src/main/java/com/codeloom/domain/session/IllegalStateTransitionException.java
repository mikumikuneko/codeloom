package com.codeloom.domain.session;

/**
 * 非法的会话状态迁移。
 *
 * <p>选择抛异常而不是记日志放过：状态机出错意味着**有一处并发或恢复逻辑写错了**，
 * 放过它只会让错误推迟到更难排查的地方（比如某个会话永远卡在 THINKING）。
 * 早失败，堆栈里还留着肇事现场。
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final transient SessionState from;
    private final transient SessionState to;

    public IllegalStateTransitionException(SessionState from, SessionState to) {
        super("非法的会话状态迁移: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public SessionState from() {
        return from;
    }

    public SessionState to() {
        return to;
    }
}
