package com.codeloom.domain.port;

/**
 * 一条命令是怎么结束的。超时和取消必须作为返回值，而不是异常：异常会把已经产生的输出
 * 一起扔掉，而超时构建已经打出来的行正是模型最需要的信息。拿不到结果的只剩「启动
 * 失败」和「等待的线程被中断」，那两种仍然抛异常。事实有两个消费方，措辞不同：
 * {@code RunCommandTool} 说「超时了，这次调用失败了」；{@code VerificationRunner} 说
 * 「这次没验成」，且必须与「验证未通过」分开 —— 混成后者，模型会去修一个不存在的问题。
 * 见 {@code docs/decisions/architecture/2026-10-06-cancel-is-a-signal-timeout-is-a-result.md}。
 */
public enum CommandTermination {

    /** 进程自己跑完了。退出码由调用方按各自命令的语义解释。 */
    COMPLETED,

    /** 超过时限，进程树已被我们强杀。它可能已经改过文件。 */
    TIMED_OUT,

    /** 用户取消了（按 Esc），进程树已被我们强杀。同上，它可能已经改过文件。 */
    CANCELLED
}
