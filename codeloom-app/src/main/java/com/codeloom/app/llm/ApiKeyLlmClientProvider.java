package com.codeloom.app.llm;

import com.codeloom.agent.llm.LlmClient;
import com.codeloom.agent.llm.LlmClientProvider;
import com.codeloom.agent.llm.openai.OpenAiCompatibleClient;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.port.ApiKeyStore;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.user.UserId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 按「谁 + 哪一家」造一个 {@link LlmClient}，用的是**这个人在这一家上自己配的 key**（BYOK）。
 *
 * <h2>密钥只有一处来源：界面上配的那把</h2>
 * 用户密钥按 (userId, provider) 加密存在库里，由用户在界面上配置 ——
 * **后端不持有密钥，也不从配置里读密钥**。所以"这一家有没有可用的密钥"只有
 * {@link #keyFor} 一条查找路径，{@code findClient} 里不会冒出第二个判断。
 *
 * <p>不给"后端也能配 key"留口子，是为了不让"这份凭据算谁的"变得含糊：
 * 按账号算的额度、限流、审计全都跟着含糊。
 *
 * <h2>密钥怎么进来的：每次发请求现取</h2>
 * {@code OpenAiCompatibleClient} 收的是一个 {@code Supplier<String>} ——
 * 那不是随便设的接口，它就是为了这件事：**解密后即用、用完即弃**。
 * 所以下面那个缓存里存的是**客户端**，不是密钥；密钥在每一次 HTTP 请求发生的
 * 那一刻才从库里解密出来，请求结束就没人再引用它。
 *
 * <p>副产物是一个很好的性质：用户刚换完 key，**下一个请求就用新的**，不用重启、
 * 也不用清任何缓存 —— 因为缓存里压根没有 key。
 *
 * <h2>缓存键是「用户 + 哪一家」</h2>
 * 必须是「用户」这一维：不同的人不能复用同一个客户端（它绑着一个取密钥的闭包）。
 * 而另一维是**哪一家**，不是地址 —— 地址是这一家的属性，一家只有一个
 * （见 {@link ProviderEndpoints}）。
 *
 * <p>把密钥放进缓存键（哪怕只是它的哈希）则等于把明文留在一个活得更久的地方，
 * 和上面那件事正好相反。
 */
@Component
public class ApiKeyLlmClientProvider implements LlmClientProvider {

    /**
     * 缓存上限。到顶之后淘汰最久未用的那个。
     *
     * <p>这个数不需要精算，它只是**防止无界**：真实的量是"在用的人数 × 每人配过的家数"，
     * 正常情况下远小于它。
     */
    private static final int MAX_CACHED_CLIENTS = 64;

    /**
     * 发往模型服务的**协议报文**用这个 mapper —— 刻意不用容器里那个：
     * 那个是给 Web 层响应序列化配置的（见 {@code llm/openai} 那边的同类说明）。
     *
     * <p>但它是**无状态**的：配置好之后读写都是线程安全的，所以全局一个就够 ——
     * 每个客户端各持一个，只会让一个能省的构造成本乘以缓存条目数。
     */
    private static final ObjectMapper PROTOCOL_MAPPER = new ObjectMapper();

    /**
     * 每个「用户 + 哪一家」一个客户端，**有界**。
     *
     * <p>不每次新建的原因是实际的：{@code OpenAiCompatibleClient} 内部持有一个
     * {@code HttpClient}，而它自带连接池 —— 每次调用新建一个等于每次重新建池、重新握手。
     *
     * <p>必须有界的原因是安全的：这一维现在的来源是预设表 + （将来的）用户自定义，
     * 而后者由用户决定能加多少。不设上限的话缓存基数就由用户决定了，而每个条目里
     * 是一个带连接池的 HttpClient —— 条目只增不减就是一条长期泄漏。
     *
     * <p>淘汰只是丢掉引用，不去关那个连接池：一个刚被淘汰的客户端可能还有正在飞行的请求。
     * 上限的意义是让"最坏情况"是个常数，而不是让资源立刻回收。
     *
     * <p>用带访问顺序的 {@code LinkedHashMap} 而不是 {@code ConcurrentHashMap}：
     * 前者自带 LRU（{@link LinkedHashMap#removeEldestEntry}），代价是必须自己加锁 ——
     * 而这里的访问频率是"每一轮一次"，锁的开销可以忽略。
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

    @Override
    public Optional<LlmClient> findClient(UserId ownerId, ModelConfig model) {
        ProviderId provider = model.provider();
        // 先看密钥、再看缓存，顺序不能反：反过来的话，用户**刚删掉**密钥之后
        // 只要客户端还在缓存里就仍然"能用"，而这个判断正是建会话时那个提前拒绝的依据
        if (keyFor(ownerId, provider).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(clientFor(ownerId, provider));
    }

    /**
     * 这个人在这一家上的密钥 —— **全类唯一的查找路径**，也是唯一的来源：库里那条，
     * 由用户在界面上配（见类注释）。
     */
    private Optional<String> keyFor(UserId ownerId, ProviderId provider) {
        return keys.find(ownerId, provider);
    }

    private LlmClient clientFor(UserId ownerId, ProviderId provider) {
        ClientKey key = new ClientKey(ownerId, provider);
        synchronized (clients) {
            return clients.computeIfAbsent(key, ignored -> {
                // 地址在这里解析**一次**：它是一家一个的常量（见 ProviderEndpoints），
                // 而客户端正是按它拼 /chat/completions 的
                String baseUrl = endpoints.baseUrlOf(ownerId, provider);
                return new OpenAiCompatibleClient(PROTOCOL_MAPPER, baseUrl,
                        () -> keyFor(ownerId, provider).orElseThrow(() -> new IllegalStateException(
                                "这一家（" + provider.value() + "）的 API Key 现在取不到了 —— "
                                        + "它多半是在这一轮跑起来之后被删掉或换掉了。"
                                        + "重新配置之后重新发一条消息即可。")));
            });
        }
    }

    /**
     * 缓存身份。用 record 而不是手拼字符串（{@code ownerId + "|" + provider}）：
     * 拼接的键得靠人去确认"那两个值里会不会出现分隔符"，而 record 的
     * {@code equals/hashCode} 是编译器保证的。
     */
    private record ClientKey(UserId owner, ProviderId provider) {
    }
}
