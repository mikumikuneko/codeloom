package com.codeloom.agent.loop;

import com.codeloom.agent.llm.TokenUsage;
import com.codeloom.domain.event.PersistentEvent;

import java.util.List;
import java.util.Objects;

/**
 * 一轮的产出。
 *
 * <p>{@code newEvents} 是**要落库的事实**，由调用方按顺序追加。循环自己不知道
 * 它们的真实 seq（那是存储层分配的），所以这里只给事件本身、不给 seq。
 *
 * @param model   服务商**实际使用**的模型。可能和请求时传的不同 —— 旧别名会被映射，
 *                审计要记这个值
 * @param usage   本轮累计用量
 * @param lastCallUsage **最后一次**模型调用的用量（不是累计的那一份）。
 *                它的 {@code inputTokens} 就是**那一轮结束时上下文有多大** ——
 *                一次请求的输入包含系统提示词、整段对话和工具定义，
 *                所以那个数正是我们要的、由服务商亲口报出来的上下文大小。
 *                累计的 {@code usage} 回答不了这个问题：它是一轮里好几次调用的**和**，
 *                和"现在上下文多大"是两个量
 * @param status  为什么停下来。这个字段决定了上层怎么反应，比"成功/失败"有用得多
 */
public record TurnOutcome(List<PersistentEvent> newEvents,
                          String model,
                          String finishReason,
                          TokenUsage usage,
                          TokenUsage lastCallUsage,
                          Status status) {

    public enum Status {
        /** 模型不再要求调用工具，这一轮正常结束。 */
        COMPLETED,
        /** 达到单轮的工具调用轮次上限 —— 说明模型在打转。 */
        MAX_ITERATIONS,
        /** 输出被 max_tokens 截断。当成半成品处理，不要接着往下推理。 */
        TRUNCATED,
        /** 用户取消。 */
        CANCELLED,
        /** token 预算耗尽，已注入收尾指令让模型收束。 */
        BUDGET_EXHAUSTED,
        /**
         * 模型说完成了，但**平台的自动验证没过**，而且自修次数已用尽。
         *
         * <p>这个状态必须单独存在，不能混进 COMPLETED —— 否则界面上会出现
         * "模型说改好了"配着一个失败的 VerificationResult，用户不知道该信谁。
         * 它就是"这段代码还没可信"的明确信号。
         */
        VERIFICATION_FAILED,
        /**
         * 有个调用需要人来批，这一轮挂起等答复。
         *
         * <p>它和别的"停下来"都不一样：不是出错、不是被取消、也不是做完了 ——
         * 是**卡在等人点头**。会话随它进 {@code AWAITING_APPROVAL}，答复到了再续跑。
         */
        AWAITING_APPROVAL
    }

    public TurnOutcome {
        newEvents = List.copyOf(Objects.requireNonNull(newEvents, "newEvents"));
        usage = usage == null ? TokenUsage.UNKNOWN : usage;
        lastCallUsage = lastCallUsage == null ? TokenUsage.UNKNOWN : lastCallUsage;
    }
}
