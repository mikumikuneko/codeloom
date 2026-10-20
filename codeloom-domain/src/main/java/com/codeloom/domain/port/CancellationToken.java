package com.codeloom.domain.port;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消信号。用户按 Esc（或点中断按钮）时触发，由执行侧观察。取消点必须落在工具返回
 * 边界上：先给正在执行的进程发取消信号，等进程树被杀干净，再把这次调用记为
 * {@code ToolCancelled} 事件，最后才回到用户。否则会留下僵尸进程和半成品文件，下一个
 * turn 的构建会莫名失败；发了信号不等于已取消。只有会话所有者能取消自己的会话，观察者
 * （另一个用户）不能打断：一旦模糊，会话归属和事件归属都会失效。见
 * {@code docs/decisions/architecture/2026-10-06-cancel-is-a-signal-timeout-is-a-result.md}。
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 触发取消。幂等。 */
    public void cancel() {
        cancelled.set(true);
    }

    /** 取消是否已经触发。它只回答信号，不回答进程树是否已经杀干净。 */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 一个永远不取消的令牌，给不可中断的路径用：git 那几条调用、合并之后的强制验证，
     * 以及上层没给取消信号时的缺省值。这些路径要么本来就该跑完，要么没人能打断它们。
     *
     * @return 新令牌，永不取消
     */
    public static CancellationToken none() {
        return new CancellationToken();
    }
}
