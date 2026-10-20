package com.codeloom.agent.llm;

import com.codeloom.domain.port.CancellationToken;

import java.util.function.Consumer;

/**
 * 模型调用的抽象，也是 agent 循环唯一认识的模型接口。
 *
 * <p>实现按协议分；循环、上下文组装和工具调度只依赖 {@link StreamEvent}，换服务商只需换实现。
 */
public interface LlmClient {

    /**
     * 发起一次流式调用，事件按到达顺序回调给 {@code listener}。
     *
     * <p>阻塞调用，调用点必须在虚拟线程上。取消只在帧边界检查，避免读取中途掐断。
     *
     * @param request      请求（IR 层，不含密钥）
     * @param listener     实时回调；传空实现表示只要最终结果
     * @param cancellation 取消信号
     * @return 完整结果
     * @throws LlmCallException 调用失败；由 {@link LlmCallException.Kind} 决定是否重试。
     *                         不可重试的错误由调用方按整轮失败处理，不会回灌给模型
     */
    LlmResult stream(LlmRequest request, Consumer<StreamEvent> listener, CancellationToken cancellation);
}
