package com.codeloom.domain.event;

/**
 * 一次改动验证的结论 —— 审计证据的一部分。
 *
 * <p>和 {@link ToolResult} 不是重复：{@code ToolResult} 说的是「那个工具跑完了」，
 * 面向 UI 的工具卡片；{@code VerificationResult} 说的是「这次改动**是否可信**」，
 * 面向审计与自修循环（失败则回灌模型重试，上限 3 次）。
 * 一次验证通常对应一个 {@code ToolResult} 加一个 {@code VerificationResult}。
 *
 * <h2>{@code summary} 是那次命令自己的输出，**不是模型写的**</h2>
 * 它是验证命令（构建 / 测试工具）的 stdout 与 stderr 的**末尾**一段，超长也只留这么多 ——
 * 失败信息和编译错误都堆在末尾，取头部会把最该看的那几行截掉。界面上"验证没过"后面
 * 念的就是它，所以它得能自己说明问题。
 *
 * <p>开头带「验证命令未能执行：」的那种是例外：**那句话是平台写的** —— 命令压根没跑起来
 *（没有 shell、超时、被取消），后面接的半截输出才是命令自己的。
 *
 * <p>模型看到的失败输出是**同一段东西的另一份更长的截取**，不从这儿读
 *（见 {@code VerificationRunner} 的 {@code VerificationRun}）。
 *
 * @param callId   对应的工具调用
 * @param command  实际执行的命令，比如 {@code mvn -q test}。**平台自己选的** —— 从被改的
 *                 文件里认出构建方式，不问模型要不要验（见 {@code VerificationPlan}）
 * @param passed   结论。**"命令没跑成"也是 {@code false}**
 * @param exitCode 退出码。**可空** —— 命令没跑起来时是 null，而"没跑成"和"跑完了但退出码
 *                 非零"是两种结论，靠它分开
 * @param summary  那次命令输出的末尾一段，见上
 */
public record VerificationResult(String callId,
                                 String command,
                                 boolean passed,
                                 Integer exitCode,
                                 String summary) implements PersistentEvent {
}
