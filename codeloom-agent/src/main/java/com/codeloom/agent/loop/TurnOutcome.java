package com.codeloom.agent.loop;

import com.codeloom.agent.llm.TokenUsage;
import com.codeloom.domain.event.PersistentEvent;

import java.util.List;
import java.util.Objects;

/**
 * 一轮的产出。{@code newEvents} 是待落库的事实，按产生顺序排列，不含真实 seq。
 *
 * @param newEvents     本轮产生的事件
 * @param model         服务商实际使用的模型，可能因别名映射与请求值不同
 * @param finishReason  最后一次模型调用的结束原因
 * @param usage         本轮累计用量
 * @param lastCallUsage 最后一次模型调用的用量；其 inputTokens 即收尾时的上下文大小，
 *                      与累计用量是两个不同的量
 * @param status        停止原因
 */
public record TurnOutcome(List<PersistentEvent> newEvents,
                          String model,
                          String finishReason,
                          TokenUsage usage,
                          TokenUsage lastCallUsage,
                          Status status) {

    public enum Status {
        /** 模型不再请求工具，正常结束。 */
        COMPLETED,
        /** 达到单轮工具轮次上限，视为模型在打转。 */
        MAX_ITERATIONS,
        /** 输出被长度上限截断；不能继续基于半截内容推理。 */
        TRUNCATED,
        /** 用户取消。 */
        CANCELLED,
        /** token 预算耗尽，已注入收尾指令。 */
        BUDGET_EXHAUSTED,
        /** 模型声称完成，但平台验证未通过且自修次数用尽；不能混进 COMPLETED。 */
        VERIFICATION_FAILED,
        /** 有调用等待批准，本轮挂起；答复到达后继续。 */
        AWAITING_APPROVAL
    }

    public TurnOutcome {
        newEvents = List.copyOf(Objects.requireNonNull(newEvents, "newEvents"));
        usage = usage == null ? TokenUsage.UNKNOWN : usage;
        lastCallUsage = lastCallUsage == null ? TokenUsage.UNKNOWN : lastCallUsage;
    }
}
