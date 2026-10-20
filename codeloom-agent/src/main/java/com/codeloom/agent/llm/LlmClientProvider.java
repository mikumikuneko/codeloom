package com.codeloom.agent.llm;

import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.user.UserId;
import java.util.Optional;

/**
 * 造一个“谁 + 用哪个模型”的 {@link LlmClient}。
 *
 * <p>返回空表示该用户在该端点上没有可用密钥；建会话时的提前拒绝和运行一轮共用这一判据。
 * 密钥按用户存储，因此签名必须带 {@code ownerId}；它不在参数或领域对象里，由实现按请求现取。
 */
public interface LlmClientProvider {

    /**
     * 拿到一个能在 {@code model} 的端点上发请求的客户端。
     *
     * <p>返回空表示这个人在该端点上没有可用密钥。调用方各自表达：建会话时拒绝并说明缺哪个端点，
     * 运行中则是一次失败的轮次。
     *
     * @param ownerId 会话所有者，也就是密钥归属人
     * @param model   端点与模型标识，不含密钥
     * @return 可用客户端；密钥缺失时为空
     */
    Optional<LlmClient> findClient(UserId ownerId, ModelConfig model);
}
