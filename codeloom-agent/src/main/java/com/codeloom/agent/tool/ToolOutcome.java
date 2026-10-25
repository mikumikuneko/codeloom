package com.codeloom.agent.tool;

import com.codeloom.domain.event.TodoListUpdated;

/**
 * 一次工具执行的结果，是执行层返回值，不是要落库的领域事件
 * （后者是 {@code com.codeloom.domain.event.ToolResult}）。
 *
 * @param success      这次调用是否成功；取消与执行错误都是 false
 * @param output       给模型看的文本；失败时也必须有内容
 * @param truncated    是否发生截断；截断未说明会让模型误以为输出完整
 * @param exitCode     命令类工具的退出码；纯内存工具为 null
 * @param durationMs   执行耗时（毫秒）
 * @param failure      失败性质；取消与执行错误会落成不同事件
 * @param mutated      这次调用是否可能改动过工作区（保守判断，失败或被杀的 run_command 也算）；
 *                     调用方据此决定是否运行强制验证
 * @param todoUpdate   这次调用产出的领域事实（任务清单），没有则为 null；由调用方决定落哪条事件
 * @param presentation 只给界面看的结构化 JSON，可为 null；不进入模型上下文
 */
public record ToolOutcome(boolean success,
                          String output,
                          boolean truncated,
                          Integer exitCode,
                          long durationMs,
                          Failure failure,
                          boolean mutated,
                          TodoListUpdated todoUpdate,
                          String presentation) {

    /**
     * 失败性质。调用方必须据此决定后续行为，不能从消息文本猜测；
     * 只保留确实有生产者、且行为不同的值。
     */
    public enum Failure {
        /** 没有失败。 */
        NONE,
        /** 用户取消；必须落成 ToolCancelled 事件，而不是失败结果。 */
        CANCELLED,
        /** 需要人工批准，尚未执行；与失败不同，会挂起整轮。 */
        APPROVAL_REQUIRED,
        /**
         * 超过时限、进程树已被终止。必须与 {@link #INTERNAL} 分开：
         * 它已经产生过输出并可能改过文件，应落成 ToolResult，且要让模型知道需要调整超时或跑法。
         */
        TIMEOUT,
        /** 其他内部错误（参数非法、路径越界、工具异常等）。 */
        INTERNAL
    }

    public ToolOutcome {
        output = output == null ? "" : output;
        failure = failure == null ? Failure.NONE : failure;
    }

    /** 不带领域事实的构造；大多数工具都是这种形态。 */
    public ToolOutcome(boolean success,
                       String output,
                       boolean truncated,
                       Integer exitCode,
                       long durationMs,
                       Failure failure,
                       boolean mutated) {
        this(success, output, truncated, exitCode, durationMs, failure, mutated, null, null);
    }

    /**
     * @param output 给模型看的文本
     * @return 成功结果
     */
    public static ToolOutcome ok(String output) {
        return new ToolOutcome(true, output, false, null, 0, Failure.NONE, false, null, null);
    }

    /**
     * 成功且改动过工作区；循环据此决定是否运行强制验证。
     *
     * @param output 给模型看的文本
     * @return 成功结果
     */
    public static ToolOutcome mutated(String output) {
        return new ToolOutcome(true, output, false, null, 0, Failure.NONE, true, null, null);
    }

    /**
     * 成功并产出一条领域事实；不算改动工作区。
     *
     * @param update 领域事实
     * @param output 给模型看的文本
     * @return 成功结果
     */
    public static ToolOutcome produced(TodoListUpdated update, String output) {
        return new ToolOutcome(true, output, false, null, 0, Failure.NONE, false, update, null);
    }

    /**
     * @param reason 给模型看的失败说明
     * @return 内部错误结果
     */
    public static ToolOutcome failed(String reason) {
        return failure(Failure.INTERNAL, reason);
    }

    /**
     * @param failure 失败性质
     * @param reason  给模型看的失败说明
     * @return 失败结果
     */
    public static ToolOutcome failure(Failure failure, String reason) {
        return new ToolOutcome(false, reason, false, null, 0, failure, false, null, null);
    }

    /**
     * 需要人工批准且尚未执行，本轮会挂起。{@code reason} 会进入审批事件并显示在确认界面上，
     * 因此装在 {@link #output()} 里。
     *
     * @param reason 需要批准的理由
     * @return 挂起结果
     */
    public static ToolOutcome approvalRequired(String reason) {
        return failure(Failure.APPROVAL_REQUIRED, reason);
    }

    /**
     * @return 是否因用户取消而结束
     */
    public boolean isCancelled() {
        return failure == Failure.CANCELLED;
    }

    /**
     * 这次调用等待批准，尚未执行。
     *
     * @return 是否挂起等待批准
     */
    public boolean needsApproval() {
        return failure == Failure.APPROVAL_REQUIRED;
    }

    /**
     * 补上耗时。必须原样保留其余字段（包括领域事实），漏字段不会报错。
     *
     * @param millis 耗时毫秒
     * @return 带耗时的副本
     */
    public ToolOutcome withDuration(long millis) {
        return new ToolOutcome(success, output, truncated, exitCode, millis, failure, mutated,
                todoUpdate, presentation);
    }

    /**
     * 替换给模型看的文本，其余字段原样保留。
     *
     * @param text 新文本
     * @return 副本
     */
    public ToolOutcome withOutput(String text) {
        return withOutput(text, truncated);
    }

    /**
     * 替换给模型看的文本，同时设置截断标记，并保留其余字段。
     *
     * @param text      新文本
     * @param truncated 是否已截断
     * @return 副本
     */
    public ToolOutcome withOutput(String text, boolean truncated) {
        return new ToolOutcome(success, text, truncated, exitCode, durationMs, failure, mutated,
                todoUpdate, presentation);
    }

    /**
     * 附加只给界面看的结构化 JSON。它不进模型上下文，也不是领域事实。
     *
     * @param presentationJson 结构化结果
     * @return 副本
     */
    public ToolOutcome withPresentation(String presentationJson) {
        return new ToolOutcome(success, output, truncated, exitCode, durationMs, failure, mutated,
                todoUpdate, presentationJson);
    }
}
