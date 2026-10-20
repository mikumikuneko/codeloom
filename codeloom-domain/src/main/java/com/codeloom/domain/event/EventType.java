package com.codeloom.domain.event;

/**
 * 写入 {@code event.type} 列的值，同时也是 SSE 帧里的判别字段。
 *
 * <p>这是持久化格式的一部分，与 {@link Event} 的 sealed 层级一一对应。名字一旦发布就不能改
 * （历史数据存的是字符串），所以用显式枚举而不是从类名推导。见
 * {@code docs/decisions/architecture/2026-09-24-handwritten-exhaustive-switch-in-event-codec}。
 *
 * <p>{@link #ASSISTANT_DELTA} 和 {@link #REASONING_DELTA} 不落库：逐 token 落库会让 event 表
 * 每轮膨胀几千行。它们只走实时通道，投递保证见 {@link EphemeralEvent}。
 */
public enum EventType {

    /** 会话开起来、工作区就位。事件流头一条，让回放有起点。不进上下文。 */
    SESSION_STARTED,

    /**
     * 状态机推进（从 {@code from} 到 {@code to}）。不进上下文 —— 它回答"会话当时怎么一步步
     * 走到 FAILED"，那是回放与排障看的，模型不需要。
     */
    SESSION_STATE_CHANGED,

    /** 用户发给 agent 的话。一轮从这里开始；回滚界面列的每一行背后都是它。 */
    USER_MESSAGE,

    /**
     * 从别的会话送来的留言。必须和 {@link #USER_MESSAGE} 分开：投影都是 user 角色，
     * 但"这话是谁说的"决定模型该不该照做。
     */
    AGENT_NOTE_DELIVERED,

    /**
     * 平台注入的指令（如"自动验证失败，请修正"）。必须和 {@link #USER_MESSAGE} 分开：混用会让
     * 审计分不清"用户说的"和"平台说的"，而回滚按 turn 对齐代码与对话。
     */
    PLATFORM_INSTRUCTION,

    /** 正文的流式增量。不落库，见类注释。 */
    ASSISTANT_DELTA,

    /**
     * 思考过程的流式增量。不落库，见类注释。必须和 {@link #ASSISTANT_DELTA} 分开：界面正文和
     * 思考是两个区域，合成一个再靠负载标志位区分，前端得先解包才知道往哪儿放。
     */
    REASONING_DELTA,

    /** 一轮完整回复。落库的是这个，不是上面两个。 */
    ASSISTANT_MESSAGE,

    /**
     * 模型请求调用一个工具。"模型想借工具做点什么"由它表达 —— 所以下面那条审批不进上下文：
     * 两条都进，模型会以为有两回事。
     */
    TOOL_CALL_REQUESTED,

    /** 工具跑完了（无论成败），作为上面那次调用的答复回灌给模型。 */
    TOOL_RESULT,

    /**
     * 清掉若干旧工具结果的正文。不进上下文 —— 它改的是那些结果在投影里的样子，
     * 不是"现在发生了什么"。
     */
    TOOL_RESULTS_CLEARED,

    /**
     * 用户把正在跑的工具掐了。进上下文，替那次调用作答 —— 模型在等答复，"被用户取消"就是
     * 答复本身。
     */
    TOOL_CANCELLED,

    /**
     * 崩溃恢复时补写：某次调用执行到一半就没了。必须进上下文，防止重放 —— 让模型知道那一步
     * 没跑完、先确认现场，而不是让恢复逻辑重跑（那可能造成二次修改）。
     */
    TOOL_INTERRUPTED,

    /**
     * 某次调用需要用户来批。只走审计，不进上下文 —— 模型请求它这件事由
     * {@link #TOOL_CALL_REQUESTED} 表达。
     */
    TOOL_APPROVAL_REQUESTED,

    /** 那次挂起的调用有了答复。进上下文，充当它的 tool 结果（配对靠它）。 */
    TOOL_APPROVAL_RESOLVED,

    /**
     * 那次挂起的调用被拒绝了：没执行，也不会再有结果。它和
     * {@link #TOOL_APPROVAL_RESOLVED} 是两个事实（决定 vs 收尾）。这条不进上下文 —— 模型要
     * 听的那句话由上面那条答复表达，再进一条只会让它以为有两回事。终局判据见
     * {@link com.codeloom.domain.session.ToolCallLifecycle}。
     */
    TOOL_REJECTED,

    /**
     * 打了一个 checkpoint：一轮收尾时有可回的位置就写一条（正常结束、失败/取消、挂着等人批
     * 都写；提交失败或没有工作区时跳过），建会话也有一条。不进上下文，但回滚靠它定位：按事件
     * 序号查它当时记下的消息下标（见 {@link SessionRewound#toCheckpointSeq()}），不用
     * {@code commitSha} —— 一轮没改时那个 sha 与上一个 HEAD 相同。
     */
    CHECKPOINT_CREATED,

    /**
     * 这一轮改了哪些文件、各增删多少行。写时算一次，之后不再依赖 git。不进上下文 —— 模型不
     * 需要知道"我刚才改了哪几个文件"；它只在造出新提交且改动非空时才写，进去会让前缀缓存
     * 时不时失效。为什么不在读时算，见 {@code WorkspaceChanges}。
     */
    WORKSPACE_CHANGES,

    /**
     * 一次模型调用失败了，我们决定等一下再试。不进上下文，而且必须不进：这是"我们这边在
     * 重试"，模型知道只会以为自己该说点什么；每重试一次多一条，进去还会让前缀缓存失效。
     * 它给人看，见 {@code LlmRetryScheduled}。
     */
    LLM_RETRY_SCHEDULED,

    /**
     * 把主干同步进了会话的工作区（合并的反方向）。不进上下文 —— 模型需要知道的是代码变了，
     * 而那读文件就看得见。
     */
    SESSION_SYNCED,

    /**
     * 会话被回滚到某个 checkpoint。它进上下文的方式是"删"：按自己的
     * {@code toCheckpointSeq} 找回下标，把后面的对话整段截掉，自身不产出消息。
     */
    SESSION_REWOUND,

    /**
     * 一次改动验证的结论（{@code mvn test} 跑没跑过之类）。不进上下文 —— 它是派生的：证据在
     * 那次调用的 {@link #TOOL_RESULT} 里，而"失败要重试"是平台的决定，走
     * {@link #PLATFORM_INSTRUCTION} 告诉模型。
     */
    VERIFICATION_RESULT,

    /**
     * 一次上下文压缩。它改的是投影（从某个序号起不再送给模型），不是删事件，详见
     * {@code ContextCompacted}。
     */
    CONTEXT_COMPACTED,

    /**
     * 任务清单被整份写了一遍。它进上下文的方式是"追加快照"：每次变化投影成一条消息，同一轮里
     * 旧的留在历史里、最新的在最后（各带时刻和"以最后一份为准"；空清单追加"已经清空"）；压缩
     * 时水位线之前的旧快照由摘要代表，只把最新一份带到之后。回滚由消息截断负责。投影行为见
     * {@code ContextAssembler}，理由见
     * {@code docs/decisions/feature/2026-10-18-clock-and-todo-live-in-the-projection}。
     */
    TODO_LIST_UPDATED,

    /**
     * 会话换了模型。不进上下文 —— 模型不需要知道"我被换掉了"，它写的每条消息上已经带着
     * 模型名（见 {@code AssistantMessage}）。
     */
    MODEL_CHANGED,

    /** 一轮的用量结算，只有正常收尾才写（失败、取消的那一轮没有）。不进上下文 —— 进去会让前缀缓存失效。 */
    TURN_TOKENS_USED,
}
