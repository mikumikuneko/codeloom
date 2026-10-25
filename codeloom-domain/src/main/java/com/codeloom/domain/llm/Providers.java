package com.codeloom.domain.llm;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 认识的那几家模型服务——一张写死的表。
 *
 * <p>界面只让用户填 key，请求地址从这里取。地址只有这一处是这张表最重要的性质：会话和
 * 密钥都记 {@link ProviderId}，所以改地址不会让任何密钥失联。它只是便利、不是限制：
 * 自定义 provider 走同一条链路，区别只在"地址从哪儿来"多一个分支。
 *
 * <p>请求地址不发给前端：{@code GET /api/providers} 只回 id、显示名和官网；
 * {@link Preset#officialUrl()} 是给人点过去的网址，不是发请求的地址。
 */
public final class Providers {

    /**
     * 预设的一家。
     *
     * @param id          标识，同时是密钥和会话记的值。类型就是 {@link ProviderId}，免得
     *                    这里又开一条未经归一化的路径
     * @param displayName 界面上显示的名字
     * @param officialUrl 官网，只用来给人点过去
     * @param baseUrl                 请求地址；OpenAI 兼容端点的根，客户端在它后面拼
     *                                {@code /chat/completions}
     * @param supportsReasoningEffort 这一家是否支持 reasoning_effort；界面据此决定要不要
     *                                显示思考强度
     */
    public record Preset(ProviderId id, String displayName, String officialUrl, String baseUrl,
                         boolean supportsReasoningEffort) {
    }

    public static final Preset DEEPSEEK = new Preset(
            ProviderId.of("deepseek"), "DeepSeek",
            "https://platform.deepseek.com", "https://api.deepseek.com", true);

    private static final List<Preset> ALL = List.of(DEEPSEEK);

    private static final Map<ProviderId, Preset> BY_ID = ALL.stream()
            .collect(Collectors.toUnmodifiableMap(Preset::id, Function.identity()));

    /**
     * 界面上列出来的那些（预设卡片）。顺序就是显示顺序。
     *
     * @return 全部预设，按显示顺序
     */
    public static List<Preset> all() {
        return ALL;
    }

    /**
     * 这个 id 是不是我们认识的一家。地址只有这一处来源；见
     * {@code docs/decisions/architecture/2026-09-26-keys-and-identity-are-per-provider.md}。
     *
     * @param id 待查的 provider 标识
     * @return 对应预设；取不到表示它没有可用的请求地址
     */
    public static Optional<Preset> find(ProviderId id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /**
     * 这一家叫什么——给人看的错误文案用它。认不出来就回落成 id 本身：认不出来正是
     * "没有地址可用"这件事，文案带上 id 是最有用的信息。
     *
     * @param id provider 标识
     * @return 显示名；不在预设表里时返回 id 文本
     */
    public static String displayNameOf(ProviderId id) {
        return find(id).map(Preset::displayName).orElse(id.value());
    }

    private Providers() {
    }
}
