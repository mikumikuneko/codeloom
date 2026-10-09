package com.codeloom.domain.port;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消信号。用户按 Esc（或点中断按钮）时触发，由执行侧观察。
 *
 * <h2>取消点必须落在工具返回边界上</h2>
 * 中断一个正在跑的 {@code mvn test} 不能只挥挥手 —— 正确顺序是：
 * <ol>
 *   <li>给正在执行的进程发取消信号</li>
 *   <li>**等进程树被杀干净**</li>
 *   <li>把这次调用记为 {@code ToolCancelled} 事件</li>
 *   <li>再回到用户</li>
 * </ol>
 *
 * <p>否则会留下僵尸进程和半成品文件，而下一个 turn 的构建会莫名失败。
 * 这是本项目里"取消"与"中断"两个词的区别所在：前者是信号，后者是等它真的停下。
 *
 * <p>只有会话所有者能取消自己的会话；观察者（另一个用户）不能打断，这是硬边界 ——
 * 一旦模糊，会话归属和事件归属都会失效。
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 触发取消。幂等。 */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 一个永远不取消的令牌 —— 给**不可中断的路径**用：git 那几条调用、合并之后的强制验证、
     * 以及"上层没给取消信号"时的缺省值。它们要么本来就该跑完，要么没人能打断它们。
     *
     * <p>测试也在用（图省事），但那不是它的用途 —— 它服务的是上面那几条会走到它的生产路径。
     */
    public static CancellationToken none() {
        return new CancellationToken();
    }
}
