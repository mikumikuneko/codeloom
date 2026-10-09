package com.codeloom.domain.port;

import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.user.UserId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 用户在各家模型服务上的 API Key。**这是领域里唯一一个碰得到密钥的端口。**
 *
 * <h2>为什么键是「用户 + 哪一家」而不是「用户」</h2>
 * 因为这个项目支持用户自由选择模型，而**每家的密钥是各自独立的**。一条 key 配到用户身上
 * 会同时错两件事：
 *
 * <ul>
 *   <li>用户配了 DeepSeek 的 key 再配 Kimi 的，后者把前者覆盖掉 —— 换个模型就得重配一次；
 *   <li>更严重的是**会把 A 家的凭据发到 B 家的服务器上**。那不是功能问题，
 *       是把一个第三方服务的密钥交给了另一个第三方。
 * </ul>
 *
 * <p>而第二维是 {@link ProviderId}（"哪一家"），**不是地址**。地址是这一家的属性
 * （见 {@code Providers}），只在一个地方出现；密钥认的是 id。于是"同一个服务的两种写法
 * 各配了一把 key"这件事从根上不存在 —— 也不需要任何归一。
 *
 * <h2>为什么没有 exists</h2>
 * 它的每一个可能用途都等价于 {@code find(...).isPresent()}，区别只是省一次解密。
 * 而这个端口只有一个调用方（造模型客户端的那一个），它需要的是**密钥本身**，
 * 不是"有没有" —— 于是 exists 只会变成第二处表达"这一家有没有可用的 key"的地方，
 * 而那个判断必须和取值走同一条路径。少一个方法，就少一次两边走岔的机会。
 *
 * <h2>已知限制：一家人只能配一把</h2>
 * 想用两家 DeepSeek 密钥（比如一个生产一个测试）现在界面上表达不了 ——
 * 但**模型已经支持了**：id 是自由形状的，"哪一家"这一维想开几条就几条
 * （加一条 {@code deepseek-test} 即可，连地址都不用重复写）。
 * 缺的只是界面上的入口，以及"同一个地址的两条 id"这件事要不要允许。
 *
 * <h2>为什么没有"列出所有明文密钥"这样的方法</h2>
 * 没有任何用例需要它。多一个能一次拿到多把明文的方法，就多一种把整个系统的密钥
 * 一次性泄漏出去的方式。要列出"配过哪几家"是有的（{@link #listConfigured}），
 * 但它**不带密钥**。
 */
public interface ApiKeyStore {

    /**
     * 存下（或替换）这个用户在**某一家**上的密钥。实现必须加密之后再落库。
     *
     * <p>顺带记下**这一家的显示名**和**地址** —— 密钥只是"能连上"，而这两样是"这是什么"。
     * 界面上要能认出"哪个是我的 DeepSeek"；地址对预设的那几家是冗余的（{@code Providers}
     * 里已经有一份），它是给**自定义 provider** 留的位置。
     *
     * @param baseUrl 这一家的请求地址；预设的给 null（从 {@code Providers} 取）
     * @param name    显示名；null 表示没起（界面上用预设名或 id 兜底）
     */
    void put(UserId owner, ProviderId provider, String baseUrl, String name, String apiKey);

    /**
     * 取出**明文**密钥。
     *
     * <p>调用点应当是"马上要发请求"的那一刻 —— 明文只在那一段里存在。
     */
    Optional<String> find(UserId owner, ProviderId provider);

    void remove(UserId owner, ProviderId provider);

    /** 这个用户配过哪几家。**不含密钥**，只给界面显示"都配了哪些"。 */
    List<ConfiguredProvider> listConfigured(UserId owner);

    /**
     * 配过的一家。
     *
     * <p>它住在端口里，是因为**只有 {@link #listConfigured} 会产出它** —— 没有独立生命
     * （同 {@code Map.Entry}）。而它说的是领域的语言：标识是 {@link ProviderId}，
     * 和上面几个方法吃的是同一个东西。"库里那一列是 VARCHAR"是适配器的事，
     * 换算在那一边做（见 {@code ApiKeyRow}）。
     *
     * @param provider     哪一家
     * @param baseUrl      这一家的请求地址；预设的那几家为 null（地址在 {@code Providers} 里）
     * @param name         用户给它起的显示名，可空
     * @param configuredAt 最后一次更换密钥的时间 —— 「什么时候换过」是排障时第一个要问的
     */
    record ConfiguredProvider(ProviderId provider, String baseUrl, String name,
                              Instant configuredAt) {
    }
}
