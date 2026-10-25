package com.codeloom.domain.port;

import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.llm.ReasoningEffort;
import com.codeloom.domain.user.UserId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 用户在各家模型服务上的 API Key，是领域里唯一碰得到密钥的端口。键是「用户 + 哪一家」而不是
 * 「用户」：各家的密钥彼此独立，用一条 key 会互相覆盖，更严重的是把 A 家的凭据发到 B 家的
 * 服务器。第二维是 {@link ProviderId} 而不是地址：密钥认 id，同一服务的两种写法不会各配一把
 * key。不提供 {@code exists}（唯一查询是取密钥本身）或「列出所有明文密钥」：多一个这样的
 * 入口，就多一种一次性泄漏全部密钥的方式；{@link #listConfigured} 只列配过哪几家。加解密由
 * 实现负责，调用方只接触明文。见
 * {@code docs/decisions/architecture/2026-09-26-keys-and-identity-are-per-provider.md}。
 */
public interface ApiKeyStore {

    /**
     * 存下（或替换）这个用户在某一家上的密钥。实现必须先加密再落库。
     *
     * <p>同时记下这一家的显示名和地址：密钥只说明「能连上」，这两样说明「这是什么」，
     * 界面上凭它们认出「哪个是我的 DeepSeek」。地址对预设的那几家是冗余的
     * （{@code Providers} 里已有一份），它是给自定义 provider 留的位置。
     *
     * @param owner           密钥的所有者
     * @param provider        哪一家模型服务
     * @param baseUrl         这一家的请求地址；预设的给 null（从 {@code Providers} 取）
     * @param name            显示名；null 表示没起（界面上显示预设名或 id）
     * @param apiKey          明文密钥；实现负责加密，落库的必须是密文
     * @param reasoningEffort 思考强度；null 表示不发送，由服务商决定默认值
     */
    void put(UserId owner, ProviderId provider, String baseUrl, String name, String apiKey,
             ReasoningEffort reasoningEffort);

    /**
     * 只改显示名、地址和思考强度，不动密钥。
     *
     * <p>用户改一个下拉不该被逼着重填整把 key。调用方必须先确认这一家已经配置过；
     * 没有密钥时这个方法不会创建出半条配置。
     *
     * @param owner           密钥的所有者
     * @param provider        哪一家模型服务
     * @param baseUrl         这一家的请求地址；预设的给 null
     * @param name            显示名；null 表示没起
     * @param reasoningEffort 思考强度；null 表示不发送
     */
    void updateSettings(UserId owner, ProviderId provider, String baseUrl, String name,
                        ReasoningEffort reasoningEffort);

    /**
     * 取出这一家的明文密钥；没配过就是空。
     *
     * <p>调用点应当在「马上要发请求」的那一刻 —— 明文只在那一段里存在。
     *
     * @param owner    密钥的所有者
     * @param provider 哪一家模型服务
     * @return 明文密钥；没配过时为空
     */
    Optional<String> find(UserId owner, ProviderId provider);

    /**
     * 这一家配置的思考强度；没配过或留空时为空。
     *
     * <p>每次发请求现取，用户改完下一轮立即生效，不需要重启或清客户端缓存。
     *
     * @param owner    密钥的所有者
     * @param provider 哪一家模型服务
     * @return 思考强度；没配置时为空
     */
    Optional<ReasoningEffort> reasoningEffort(UserId owner, ProviderId provider);

    /**
     * 删掉这个用户在这一家上的密钥。没配过时也成功。
     *
     * @param owner    密钥的所有者
     * @param provider 哪一家模型服务
     */
    void remove(UserId owner, ProviderId provider);

    /**
     * 这个用户配过哪几家。不含密钥，只给界面显示「都配了哪些」。
     *
     * @param owner 用户
     * @return 配过的 provider 信息
     */
    List<ConfiguredProvider> listConfigured(UserId owner);

    /**
     * 配过的一家。只由 {@link #listConfigured} 产出，没有独立生命周期（同 {@code Map.Entry}）。
     * 标识是领域语言里的 {@link ProviderId}；库里那一列是 VARCHAR，换算由适配器完成
     * （见 {@code ApiKeyRow}）。
     *
     * @param provider        哪一家
     * @param baseUrl         这一家的请求地址；预设的那几家为 null（地址在 {@code Providers} 里）
     * @param name            用户给它起的显示名，可空
     * @param reasoningEffort 思考强度，可空（不发送，由服务商决定）
     * @param configuredAt    最后一次更换密钥的时间
     */
    record ConfiguredProvider(ProviderId provider, String baseUrl, String name,
                              ReasoningEffort reasoningEffort, Instant configuredAt) {
    }
}
