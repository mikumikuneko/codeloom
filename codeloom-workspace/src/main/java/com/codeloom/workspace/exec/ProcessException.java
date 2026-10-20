package com.codeloom.workspace.exec;

/**
 * 进程连结果都拿不到：启动失败，或等待期间线程被中断。
 *
 * <p>"退出码非零"和"被我们杀掉"（超时或取消）都是 {@link ProcessOutcome} 的返回值，
 * 不是异常：被超时杀掉的构建仍有已产生的输出，抛异常会把它们一起扔掉。启动失败则
 * 确实什么都没有；线程被中断时 JVM 正在退出，返回结果会误导调用方。
 */
public class ProcessException extends RuntimeException {

    public ProcessException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProcessException(String message) {
        super(message);
    }
}
