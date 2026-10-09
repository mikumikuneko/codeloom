package com.codeloom.domain.llm;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 认识的那几家模型服务 —— **一张写死的表**。
 *
 * <h2>为什么要有一张表</h2>
 * 用户不该为了用 DeepSeek 先去抄一遍它的地址：地址是我们知道的事，不是他的事。
 * 所以界面上只让他填 key，地址从这里取。
 *
 * <p>地址写在**一处**（就是这里）是这张表最重要的性质：会话和密钥记的都是
 * {@link ProviderId}，而"这一家的请求打到哪儿"只有这一个答案。于是**改地址不会让任何
 * 密钥失联** —— 密钥绑的是 id，不是那串字符。
 *
 * <h2>它和"不绑定某一家"不冲突</h2>
 * 这张表是**便利**，不是限制：{@link ProviderId} 只校验形状，不要求必须在这里。
 * 自定义 provider（用户填自己的地址、地址存在他那条密钥记录上）以后加到界面上时，
 * 走的仍然是同一条链路 —— 只是"地址从哪儿来"多一个分支。
 *
 * <h2>为什么请求地址不发给前端</h2>
 * {@code GET /api/providers} 只回 id、显示名和官网。{@link Preset#officialUrl} 是给人点过去的
 * 那个网址，**不是**我们发请求的地址；请求地址留在服务端。前端拿到它没有用处（请求不由它发），
 * 而露出来只会让人以为那是可以改的。
 */
public final class Providers {

    /**
     * 预设的一家。
     *
     * @param id          标识，同时是密钥和会话记的那个值。**类型就是 {@link ProviderId}** ——
     *                    表里写的 id 和库里存的、会话上记的必须是同一个东西，
     *                    写成裸字符串等于在这里又开了一条"还没归一"的口子
     * @param displayName 界面上显示的名字
     * @param officialUrl 官网，**只用来给人点过去**
     * @param baseUrl     请求地址。OpenAI 兼容端点的根，客户端在它后面拼 {@code /chat/completions}
     */
    public record Preset(ProviderId id, String displayName, String officialUrl, String baseUrl) {
    }

    public static final Preset DEEPSEEK = new Preset(
            ProviderId.of("deepseek"), "DeepSeek",
            "https://platform.deepseek.com", "https://api.deepseek.com");

    private static final List<Preset> ALL = List.of(DEEPSEEK);

    private static final Map<ProviderId, Preset> BY_ID = ALL.stream()
            .collect(Collectors.toUnmodifiableMap(Preset::id, Function.identity()));

    /** 界面上列出来的那些（预设卡片）。**顺序就是显示顺序**。 */
    public static List<Preset> all() {
        return ALL;
    }

    /** 这个 id 是不是我们认识的一家。取不到 = 它没有可用的请求地址。 */
    public static Optional<Preset> find(ProviderId id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /**
     * 这一家叫什么 —— 给人看的错误文案用它（"你还没有配置 DeepSeek 的 API Key"）。
     *
     * <p>认不出来就回落成 id 本身：那时没有更好的名字，而**不能因此不报错** ——
     * 认不出来恰恰是"它没有地址可用"这件事，那句话里带上 id 是最有用的信息。
     */
    public static String displayNameOf(ProviderId id) {
        return find(id).map(Preset::displayName).orElse(id.value());
    }

    private Providers() {
    }
}
