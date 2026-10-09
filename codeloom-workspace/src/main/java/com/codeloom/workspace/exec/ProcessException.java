package com.codeloom.workspace.exec;

import java.util.List;

/**
 * 进程**连结果都拿不到**（启动失败 / 等待期间线程被中断）。
 *
 * <p>注意它和下面两种情况都不是一回事：
 * <ul>
 *   <li>"进程跑完了但退出码非零" —— 那是 {@link ProcessOutcome} 的正常返回，
 *       由调用方按各自命令的语义解释；</li>
 *   <li>"**我们把它杀了**"（超时 / 取消）—— 那也是 {@link ProcessOutcome} 的正常返回，
 *       只是 {@link com.codeloom.domain.port.CommandTermination CommandTermination} 那一项不是 {@code COMPLETED}。</li>
 * </ul>
 *
 * <h2>那两种为什么是返回值，不是异常</h2>
 * 因为抛异常会把**结果一起扔掉**。一条跑了三分钟的构建被超时杀掉时，
 * 它已经打出来的那些行正是最该看的东西（跑到哪一步卡住的），
 * 而"抛异常"这个形状说的是"什么都没有"。
 *
 * <p>留着的那两种没有这个问题：起不来就真的什么都没有；线程被中断时整个 JVM
 * 正在往下走，这时候再返回一个"结果"只会让调用方以为还能继续。
 */
public class ProcessException extends RuntimeException {

    public enum Reason {
        /** 可执行文件不存在或无法启动。 */
        START_FAILED,
        /** 等待期间线程被中断。 */
        INTERRUPTED
    }

    private final transient List<String> command;
    private final transient Reason reason;

    public ProcessException(List<String> command, Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.command = List.copyOf(command);
        this.reason = reason;
    }

    public ProcessException(List<String> command, Reason reason, String message) {
        this(command, reason, message, null);
    }

    public List<String> command() {
        return command;
    }

    public Reason reason() {
        return reason;
    }
}
