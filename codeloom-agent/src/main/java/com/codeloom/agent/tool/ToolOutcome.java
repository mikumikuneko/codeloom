package com.codeloom.agent.tool;

import com.codeloom.domain.event.TodoListUpdated;

/**
 * 一次工具执行的结果。
 *
 * <p>名字刻意不叫 {@code ToolResult} —— 那个名字已经被领域事件
 * {@code com.codeloom.domain.event.ToolResult} 占了。这两个是不同的东西：
 * 这个是**执行层的返回**，那个是**要落库的事实**。
 *
 * @param output    给模型看的文本。失败时也要有内容 —— 模型靠它自修
 * @param truncated 是否发生了截断。截断了却不说，模型会以为输出就这么多
 * @param exitCode  命令类工具有退出码；纯内存工具为 null
 * @param failure   失败的性质。**不是所有失败都一样** —— 被用户取消和执行出错
 *                  在事件流里要落成不同的事件，否则模型会去重试用户刚取消掉的操作
 * @param mutated   这一次调用**实际**改动工作区了吗
 * @param todoUpdate 这一次调用**产出的领域事实**：它把任务清单改成什么样了。
 *                   为 null = 这次调用没改清单（哪些工具会改，权威在 {@code ToolRegistry}）。
 *
 *                   <p>它和 {@code mutated} 是同一类东西：**执行层回报事实，由调用方
 *                   决定落哪条事件**。这里带的是**事件本身**而不是原始数据，因为"清单被
 *                   更新了"已经是领域语言 —— 再翻译一次只会多一个会走样的形状。
 *                   工具自己**不写**事件流：那是知道会话是谁的那一层的事（见
 *                   {@code AgentTurn.recordOutcome}）。
 */
public record ToolOutcome(boolean success,
                          String output,
                          boolean truncated,
                          Integer exitCode,
                          long durationMs,
                          Failure failure,
                          boolean mutated,
                          TodoListUpdated todoUpdate) {

    /**
     * 失败的性质。
     *
     * <p>为什么不能让调用方从 message 字符串里猜：消息是人写的、会改，
     * 而且"用户取消"和"要人来批"在 UI 上该有完全不同的呈现。
     *
     * <h2>一个值只有真的被生产才留</h2>
     * 一个**没有任何地方生产**的枚举值会误导读代码的人：看到 {@code TIMEOUT} 会以为
     * "超时是被单独处理的"，然后基于那个前提做决定。所以 {@link #TIMEOUT} 是有了生产者
     *（{@code RunCommandTool}）才留下的 —— {@link com.codeloom.domain.port.CommandTermination}
     * 把"进程是怎么结束的"变成了 domain 里的事实，超时于是不再丢掉那半截输出
     *（从前是抛异常，跑到一半的构建日志跟着一起扔了）。{@code POLICY_DENIED} 没有回来：
     * 它的位置被 {@link #APPROVAL_REQUIRED} 占着。
     */
    public enum Failure {
        /** 没有失败。 */
        NONE,
        /** 用户取消。**要落成 ToolCancelled 事件，不是 ToolResult(success=false)**。 */
        CANCELLED,
        /**
         * 需要人来批，**还没有执行**。
         *
         * <p>它和真正的失败要分开：那个是"不行"，这个是"等一下"。
         * 上层对两者的处理完全相反 —— 一个让模型换个做法，另一个把整轮挂起等人点头。
         */
        APPROVAL_REQUIRED,
        /**
         * 超过时限，进程树已被我们强杀。
         *
         * <p>它必须和 {@link #INTERNAL} 分开。合在一起的后果很具体：模型拿到一句
         * "命令未能执行"，会以为是自己命令写错了，于是**把同一条命令原样再跑一遍**，
         * 再超时一次。而真相是这条命令本来就需要更久 —— 它该做的是把
         * {@code timeout_seconds} 调大，或者换个跑法。
         *
         * <p>它**不是**"用户取消"那种收尾：被杀掉之前它已经跑了一会儿、改过文件、
         * 打过输出。所以它落成一条 {@code ToolResult}，和 {@link #CANCELLED} 不同。
         */
        TIMEOUT,
        /** 其它内部错误（参数不是 JSON、路径越界、工具本身出错……）。 */
        INTERNAL
    }

    public ToolOutcome {
        output = output == null ? "" : output;
        failure = failure == null ? Failure.NONE : failure;
    }

    /**
     * 没带领域事实的那一种 —— 大多数工具都是它。
     *
     * <p>这不是"给个方便"的重载：{@code todoUpdate == null} 是**大多数工具的常态**，
     * 而不是某种残缺的形状。每个调用点都补一个 {@code null} 只会让人以为那里漏了什么。
     */
    public ToolOutcome(boolean success,
                       String output,
                       boolean truncated,
                       Integer exitCode,
                       long durationMs,
                       Failure failure,
                       boolean mutated) {
        this(success, output, truncated, exitCode, durationMs, failure, mutated, null);
    }

    public static ToolOutcome ok(String output) {
        return new ToolOutcome(true, output, false, null, 0, Failure.NONE, false, null);
    }

    /** 成功并且**改动过工作区** —— 循环据此决定要不要跑强制验证。 */
    public static ToolOutcome mutated(String output) {
        return new ToolOutcome(true, output, false, null, 0, Failure.NONE, true, null);
    }

    /**
     * 成功，**并且产出了一条要落的领域事实**（目前只有任务清单）。
     *
     * <p>它不算"改动过工作区"：清单不是文件，左边那棵树不该因为写了一次计划就重拉。
     */
    public static ToolOutcome produced(TodoListUpdated update, String output) {
        return new ToolOutcome(true, output, false, null, 0, Failure.NONE, false, update);
    }

    public static ToolOutcome failed(String reason) {
        return failure(Failure.INTERNAL, reason);
    }

    public static ToolOutcome failure(Failure failure, String reason) {
        return new ToolOutcome(false, reason, false, null, 0, failure, false, null);
    }

    /**
     * 需要人来批，还没执行 —— 这一轮会就此挂起。
     *
     * <p>这里的 {@code reason} 是**问人的理由**（"这个程序不在免审批名单里"、
     * "这条命令会动到工作区外面"），它会原样进 {@code ToolApprovalRequested} 事件、
     * 出现在人点"批准"的那个界面上。见 {@link CommandApproval}。
     *
     * <p>它装在 {@link #output()} 里：等着人批这个结局**没有别的内容可装**
     *（这一轮不会落 ToolResult，模型也看不到这句话）。
     */
    public static ToolOutcome approvalRequired(String reason) {
        return failure(Failure.APPROVAL_REQUIRED, reason);
    }

    public boolean isCancelled() {
        return failure == Failure.CANCELLED;
    }

    /** 这次调用卡在等人批，**没有执行**。 */
    public boolean needsApproval() {
        return failure == Failure.APPROVAL_REQUIRED;
    }

    /** 补上耗时。工具拿不到自己的总耗时（那是执行器测的），所以由循环补。 */
    public ToolOutcome withDuration(long millis) {
        // ★ 每一个字段都要原样带过来。漏掉一个（比如新加的 todoUpdate）的症状是
        // "工具明明报了清单，事件流里却没有"，而工具自己的测试是绿的
        //（它直接看 execute 的返回值，不经过这一层）
        return new ToolOutcome(success, output, truncated, exitCode, millis, failure, mutated,
                todoUpdate);
    }
}
