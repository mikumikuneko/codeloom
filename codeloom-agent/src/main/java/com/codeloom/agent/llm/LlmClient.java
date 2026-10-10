package com.codeloom.agent.llm;

import com.codeloom.domain.port.CancellationToken;

import java.util.function.Consumer;

/**
 * 模型调用的抽象。**这是 agent 循环唯一认识的模型接口。**
 *
 * <p>实现按协议分。换 provider 只要换实现，
 * 循环、上下文组装、工具调度全都不用动 —— 前提是大家都吐 {@link StreamEvent} 这一套。
 */
public interface LlmClient {

    /**
     * 发起一次流式调用，事件按到达顺序回调给 {@code listener}。
     *
     * <p>这是**阻塞**调用，调用点必须在虚拟线程上（agent 循环本来就在虚拟线程里跑）。
     *
     * <p>取消的检查点在**帧边界**上 —— 每收到一帧看一眼取消信号。这是刻意的：
     * 与"中断只发生在工具返回边界"同一套思路，避免在读取中途掐断导致
     * 状态不一致。
     *
     * @param request    请求（IR 层，不含密钥）
     * @param listener   实时回调；传 {@code e -> {}} 表示只要最终结果
     * @param cancellation 取消信号
     * @return 完整结果
     * @throws LlmCallException 调用失败。看 {@link LlmCallException.Kind} 决定是重试、
     *                          回灌给模型自修、还是报给用户
     */
    LlmResult stream(LlmRequest request, Consumer<StreamEvent> listener, CancellationToken cancellation);
}
