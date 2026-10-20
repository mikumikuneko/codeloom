package com.codeloom.app.llm;

import com.codeloom.agent.llm.LlmClient;
import com.codeloom.agent.llm.LlmClientProvider;
import com.codeloom.agent.llm.openai.OpenAiCompatibleClient;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.llm.Providers;
import com.codeloom.domain.port.ApiKeyStore;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.user.UserId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 按“用户 + 服务商”创建 {@link LlmClient}，使用这个人在该服务商上自己配置的 key（BYOK）。
 *
 * <p>密钥只有一处来源：用户在界面上配置、按（用户, 服务商）加密存库；后端不持有密钥，
 * 也不从配置里读。客户端在每次 HTTP 请求发生时才解密取钥、用完即弃，所以换 key 之后
 * 下一个请求立即生效，无需重启或清缓存。见
 * {@code docs/decisions/architecture/2026-09-26-keys-and-identity-are-per-provider.md}
 * 与 {@code docs/decisions/architecture/2026-10-06-no-server-side-dev-key.md}。
 *
 * <p>缓存键是（用户, 服务商），不是地址：不同人不能复用同一个客户端，而地址是服务商
 * 的属性。缓存有界，淘汰最久未用的条目；淘汰只丢引用，不关连接池。
 */
@Component
public class ApiKeyLlmClientProvider implements LlmClientProvider {

    /**
     * 缓存上限，到顶之后淘汰最久未用的那个。这个数只需防止无界：真实量是
     * “在用人数 × 每人配过的服务商数”，正常远小于它。
     */
    private static final int MAX_CACHED_CLIENTS = 64;

    /**
     * 发往模型服务的协议报文用这个 mapper，不用容器里那个（后者按 Web 响应配置）。
     *
     * <p>它配置好后无状态、线程安全，全局一个即可，省下按缓存条目重复构造。
     */
    private static final ObjectMapper PROTOCOL_MAPPER = new ObjectMapper();

    /**
     * 每个（用户, 服务商）一个客户端，有界。
     *
     * <p>不每次新建：{@code OpenAiCompatibleClient} 内部持有带连接池的 {@code HttpClient}，
     * 每次新建等于重新建池、重新握手。必须有界：服务商维度可由用户扩展，缓存基数
     * 不能由用户决定，否则条目只增不减就是长期泄漏。
     *
     * <p>淘汰只丢引用，不关连接池：刚被淘汰的客户端可能还有正在飞行的请求。
     * 用带访问顺序的 {@code LinkedHashMap} 自带 LRU，代价是自己加锁；访问频率是
     * 每轮一次，锁开销可以忽略。
     */
    private final Map<ClientKey, LlmClient> clients =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ClientKey, LlmClient> eldest) {
                    return size() > MAX_CACHED_CLIENTS;
                }
            };

    private final ApiKeyStore keys;
    private final ProviderEndpoints endpoints;

    public ApiKeyLlmClientProvider(ApiKeyStore keys, ProviderEndpoints endpoints) {
        this.keys = keys;
        this.endpoints = endpoints;
    }

    /**
     * 取一个能用的客户端。
     *
     * @param ownerId 密钥所属用户
     * @param model   会话模型，提供要用的服务商
     * @return 客户端；这个人没有配过这一家的密钥时为空
     */
    @Override
    public Optional<LlmClient> findClient(UserId ownerId, ModelConfig model) {
        ProviderId provider = model.provider();
        // 先看密钥、再看缓存，顺序不能反：反过来的话，用户刚删掉密钥之后只要客户端
        // 还在缓存里就仍然“能用”，而这个判断正是建会话时提前拒绝的依据。
        if (keyFor(ownerId, provider).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(clientFor(ownerId, provider));
    }

    /**
     * 这个人在这一家上的密钥，全类唯一的查找路径。见类注释。
     */
    private Optional<String> keyFor(UserId ownerId, ProviderId provider) {
        return keys.find(ownerId, provider);
    }

    private LlmClient clientFor(UserId ownerId, ProviderId provider) {
        ClientKey key = new ClientKey(ownerId, provider);
        synchronized (clients) {
            return clients.computeIfAbsent(key, ignored -> {
                // 地址只解析一次：它是一家一个的常量，客户端按它拼 /chat/completions。
                String baseUrl = endpoints.baseUrlOf(ownerId, provider);
                return new OpenAiCompatibleClient(PROTOCOL_MAPPER, baseUrl,
                        () -> keyFor(ownerId, provider).orElseThrow(() ->
                                new IllegalStateException(ApiKeyError.goneMidTurn(
                                        Providers.displayNameOf(provider)))));
            });
        }
    }

    /**
     * 缓存身份。用 record 而不是手拼字符串：{@code equals/hashCode} 由编译器保证，
     * 不必确认值里会不会出现分隔符。
     */
    private record ClientKey(UserId owner, ProviderId provider) {
    }
}
