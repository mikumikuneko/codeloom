package com.codeloom.app.auth;

import com.codeloom.agent.llm.openai.ModelCatalog;
import com.codeloom.app.llm.ProviderEndpoints;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.llm.Providers;
import com.codeloom.domain.port.ApiKeyStore;
import com.codeloom.domain.user.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

/**
 * 用户在各模型服务商上配置自己的 API Key（BYOK）。
 *
 * <p>密钥按（用户, {@code provider}）存，不按用户存：项目让用户自由选模型，而每家的密钥
 * 各自独立。只按用户存会互相覆盖，并在切换模型时把一家的凭据发到另一家的服务器上。
 * 请求里只给 provider 和密钥，地址由服务端从 {@link Providers} 取；只有自定义 provider
 * 才随请求带地址。地址改了不会让已配的密钥失联，因为密钥绑的是 id。
 *
 * <p>{@code GET} 只返回掩码（头 8 尾 4，中间固定五个星；短于阈值的整把显示
 * {@code ****}），不把密钥读回客户端：
 * 回传的收益为零，只多一次泄漏机会。按服务商分开存的原因见
 * {@code docs/decisions/architecture/2026-09-26-keys-and-identity-are-per-provider.md}。
 */
@RestController
@RequestMapping("/api/auth/api-key")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiKeyController {

    /** 掩码里露的头几位。 */
    private static final int VISIBLE_PREFIX = 8;
    /** 掩码里露的尾几位。 */
    private static final int VISIBLE_SUFFIX = 4;
    /**
     * 短于这个长度就整把遮住。
     *
     * <p>门槛比“头 + 尾”再多留 4 位：刚好等于 12 时，露头露尾就等于给出整把。
     */
    private static final int MIN_LENGTH_TO_MASK_PARTIALLY =
            VISIBLE_PREFIX + VISIBLE_SUFFIX + 4;

    /** 中间那一串星。固定个数，不跟着 key 的长度走 —— 长度也是信息。 */
    private static final String MASK_GAP = "*****";

    private final CurrentUser currentUser;
    private final ApiKeyStore keys;
    /** 解析服务商的请求地址；全应用唯一一处，见 {@link ProviderEndpoints}。 */
    private final ProviderEndpoints endpoints;
    /** 只用于解析服务商返回的 JSON，那个响应的形状不归我们定。 */
    private final ObjectMapper mapper;

    public ApiKeyController(CurrentUser currentUser, ApiKeyStore keys,
                            ProviderEndpoints endpoints, ObjectMapper mapper) {
        this.currentUser = currentUser;
        this.keys = keys;
        this.endpoints = endpoints;
        this.mapper = mapper;
    }

    /**
     * 配置或更换某一家服务商的密钥。
     *
     * <p>用 PUT 而不是 POST：一个用户在同一个端点上只有一把，语义是“替换成这个”。
     * 预设服务商的地址以 {@link Providers} 为准，请求里给了也不采纳；认不出来的 id
     * 视为自定义 provider，此时地址必须由调用方给，那是它唯一的来源。
     *
     * @param principal 当前身份
     * @param request   服务商标识、可选地址、可选显示名与明文密钥
     * @throws ResponseStatusException 400：密钥为空，或自定义 provider 未给地址
     */
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void configure(Principal principal, @RequestBody ConfigureRequest request) {
        User me = currentUser.require(principal);
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            throw new IllegalArgumentException("apiKey 不能为空");
        }
        ProviderId provider = ProviderId.of(request.provider());
        String baseUrl = blankToNull(request.baseUrl());
        if (Providers.find(provider).isEmpty() && baseUrl == null) {
            // 认不出来的 id 又没给地址，配了也用不了；那种错要等到发请求才炸，最难查。
            // 所以在这里拒掉，并把认识的那几家列出来。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "还不认识这一家：" + provider.value() + "。现在支持的是 "
                            + Providers.all().stream().map(preset -> preset.id().value()).toList()
                            + "；要接别的服务，请连请求地址一起给出");
        }
        // 预设的地址以 Providers 为准，这里不抄第二份（传了也不采纳）
        keys.put(me.id(), provider,
                Providers.find(provider).isPresent() ? null : baseUrl,
                blankToNull(request.name()), request.apiKey().strip());
    }

    /**
     * 配过哪几家，各自一个掩码。
     *
     * <p>完整密钥不会返回给客户端；服务端只为生成掩码临时解密一次。
     *
     * @param principal 当前身份
     * @return 已配置的服务商；显示名未设置时回落到预设名，密钥读不出来时掩码为 null
     */
    @GetMapping
    public List<ConfiguredKeyView> list(Principal principal) {
        User me = currentUser.require(principal);
        return keys.listConfigured(me.id()).stream()
                .map(configured -> new ConfiguredKeyView(
                        configured.provider().value(),
                        // 名字留空由预设名兜底；不自动编一个“服务商 1”之类的名字。
                        configured.name() == null
                                ? Providers.displayNameOf(configured.provider())
                                : configured.name(),
                        // 解密一次只为做掩码：再存一份掩码字段，两份数据迟早会不一致。
                        keys.find(me.id(), configured.provider())
                                .map(ApiKeyController::mask)
                                .orElse(null),
                        configured.configuredAt()))
                .toList();
    }

    /**
     * 这家服务商现在有哪些模型，向服务商本人查询。
     *
     * <p>只有服务商知道答案，内置模型表从写下那天起就在过期。密钥在这里被解开，
     * 但只发往这把密钥自己的归属方，也就是这一家的地址。
     *
     * @param principal 当前身份
     * @param provider  服务商标识
     * @return 模型 id 列表；拉取失败时抛错，不用空列表冒充失败。服务商自己返回空
     *         {@code data} 时结果就是空列表 —— 那表示这一家确实没给出任何模型
     * @throws ResponseStatusException 404：没有配过这一家，或密钥读不出来；
     *                                 502：无法从服务商取得模型列表
     */
    @GetMapping("/models")
    public ModelListView models(Principal principal, @RequestParam String provider) {
        User me = currentUser.require(principal);
        ProviderId id = ProviderId.of(provider);
        boolean configured = keys.listConfigured(me.id()).stream()
                .anyMatch(c -> c.provider().equals(id));
        if (!configured) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有配过这一家");
        }
        String apiKey = keys.find(me.id(), id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "这一家的密钥读不出来，重新配一次"));
        String baseUrl = endpoints.baseUrlOf(me.id(), id);

        try {
            return new ModelListView(ModelCatalog.list(mapper, baseUrl, apiKey));
        } catch (ModelCatalog.ModelCatalogException e) {
            // 502：我们替用户问了一个上游，它没给出我们要的东西。消息原样带出去，
            // 因为它写清了是哪一种失败（密钥不对 / 没有这个路径 / 连不上），
            // 三种失败的处理方式完全不同。
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    /**
     * 删掉某一家服务商的密钥。
     *
     * @param principal 当前身份
     * @param provider  服务商标识；传什么就删什么
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Principal principal, @RequestParam String provider) {
        User current = currentUser.require(principal);
        keys.remove(current.id(), ProviderId.of(provider));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 密钥掩码：{@code sk-aaaa1*****4444}。
     *
     * <p>头 8 位够人认出“是我换上去的那把”，尾 4 位够对上自己手里的那份；
     * 中间固定五个星，不跟长度走。短于阈值的整把遮住。
     */
    private static String mask(String apiKey) {
        if (apiKey.length() < MIN_LENGTH_TO_MASK_PARTIALLY) {
            return "****";
        }
        return apiKey.substring(0, VISIBLE_PREFIX)
                + MASK_GAP
                + apiKey.substring(apiKey.length() - VISIBLE_SUFFIX);
    }

    /**
     * @param provider     服务商标识，也是删除密钥和拉取模型时要回传的值
     * @param name         显示名；没起过名字时回落到预设名
     * @param hint         掩码（头 8 尾 4；短 key 整把为 {@code ****}）；读不出来时为 null
     * @param configuredAt 最后一次更换的时间
     */
    public record ConfiguredKeyView(String provider, String name, String hint,
                                    Instant configuredAt) {
    }

    /**
     * @param provider 服务商标识
     * @param baseUrl  请求地址。预设服务商不用给，给了也不采纳；认不出来的 id 必须给，
     *                 那是自定义 provider，而地址是它唯一的来源
     * @param name     显示名，可以留空（界面上用预设名兜底）
     * @param apiKey   明文密钥；只出现在这个入参里，落库前就会被加密
     */
    public record ConfigureRequest(String provider, String baseUrl, String name, String apiKey) {

        /**
         * 打印时不带密钥。
         *
         * <p>record 默认的 {@code toString} 会写出每个字段，密钥也在内。少了这层覆盖，
         * 一句带占位符的日志或一次异常消息就会把明文密钥落进日志。
         *
         * <p>遮的是打印，不是取值：{@link #apiKey()} 仍照常返回明文。
         */
        @Override
        public String toString() {
            return "ConfigureRequest[provider=" + provider + ", baseUrl=" + baseUrl
                    + ", name=" + name + ", apiKey=[已隐去]]";
        }
    }

    /** 这一家上可用的模型。 */
    public record ModelListView(List<String> models) {
    }
}
