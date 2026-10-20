package com.codeloom.domain.event;

/**
 * 一次改动验证的结论 —— 审计证据的一部分。
 *
 * <p>和 {@link ToolResult} 不重复：后者说"那个工具跑完了"，面向 UI 工具卡片；它说"这次改动是否
 * 可信"，面向审计与自修循环（失败则回灌模型重试，上限 3 次）。一次验证通常对应一条
 * {@link ToolResult} 加一条本事件。
 *
 * <p>{@code summary} 是验证命令自己的输出末尾一段（stdout + stderr），不是模型写的；失败信息和
 * 编译错误都在末尾，取头部会截掉最该看的几行。开头是"验证命令未能执行："时例外 —— 那句是
 * 平台写的，命令没跑起来（没有 shell、超时、取消）。模型看到的失败输出是另一份更长的截取，
 * 不从这里读（见 {@code VerificationRunner}）。
 *
 * @param callId   对应的工具调用
 * @param command  实际执行的命令（如 {@code mvn -q test}），由平台从改动的文件推断，不问模型
 * @param passed   结论；"命令没跑成"也是 {@code false}
 * @param exitCode 退出码；命令没跑起来时为 null —— "没跑成"与"跑完但非零"是两种结论
 * @param summary  命令输出的末尾一段，见上
 */
public record VerificationResult(String callId,
                                 String command,
                                 boolean passed,
                                 Integer exitCode,
                                 String summary) implements PersistentEvent {
}
