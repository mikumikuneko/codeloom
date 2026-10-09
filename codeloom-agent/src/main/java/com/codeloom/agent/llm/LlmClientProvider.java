package com.codeloom.agent.llm;

import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.user.UserId;
import java.util.Optional;

/**
 * 造一个「谁 + 用哪个模型」的 {@link LlmClient}。
 *
 * <h2>为什么只有一个方法</h2>
 * 这个接口要回答的其实是同一个问题的两种问法：
 * <ul>
 *   <li>「他**现在能不能**用这个模型？」—— 建会话时要据此提前拒绝；
 *   <li>「**给我**一个能用的客户端」—— 跑一轮时要用它。
 * </ul>
 *
 * 两者是同一个返回值：有客户端就是能，{@code empty} 就是不能 —— 一致性从"约定"变成了
 * "不可能不一致"。（拆成两个方法时，两处实现要各自判断密钥可不可用，靠人记得保持一致；
 * 一旦忘了，表现是"提前拒绝"变成随机的行为 —— 同一个模型有时能建有时不能。）
 *
 * <h2>为什么密钥不作为参数传进来</h2>
 * 它从库里（或配置里）取，而取密钥这件事**不该让 agent 循环知道**。
 * {@link LlmClient} 的实现收的是一个 {@code Supplier<String>}，每次发请求现取 ——
 * 这样密钥只在拼请求头的那一瞬间存在于内存里，也不进入任何领域对象。
 * 本接口是这个"现取"链条的起点。
 *
 * <h2>为什么形参里有 {@code ownerId}</h2>
 * 因为密钥是**按用户**存的（BYOK：用户自己带 key）。签名里带上它，
 * "密钥属于谁"这件事就不可能被忘掉一次 —— 而忘掉它的后果是
 * "所有人共用一把 key"，那是很难在测试里被偶然发现的一类问题。
 */
public interface LlmClientProvider {

    /**
     * 拿到一个能在 {@code model} 的端点上发请求的客户端。
     *
     * <p>返回 {@code empty} 的含义是明确的：**这个人现在用不了这个模型**，
     * 因为那个端点上没有可用的密钥（用户没配过，配置里的开发用 key 也不属于那个端点）。
     *
     * <p>调用方按各自的场合决定怎么表达这件事：建会话时是一个 400（并告诉他差哪个端点），
     * 跑到一半时是一次失败的轮次。
     *
     * @param ownerId 这条会话的所有者 —— 密钥的归属人
     * @param model   端点与模型标识（不含密钥，{@code ModelConfig} 里永远没有密钥）
     */
    Optional<LlmClient> findClient(UserId ownerId, ModelConfig model);
}
