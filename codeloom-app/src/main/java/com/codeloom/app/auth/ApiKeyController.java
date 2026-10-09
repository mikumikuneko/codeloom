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
 * 用户在**各家模型服务**上配置自己的 API Key（BYOK）。
 *
 * <h2>按"哪一家"管，不按用户管</h2>
 * 因为这个项目让用户自由选模型，而每家的密钥是各自独立的。只按用户存会有两个后果，
 * 第二个是安全问题：配第二家会覆盖第一家；而只配了一家却切到另一家时，
 * **那一家的凭据会被发到这一家的服务器上**。
 *
 * <h2>请求里没有地址</h2>
 * 用户填的是**哪一家**（{@code provider}）和密钥，地址由服务端从 {@link Providers} 取
 * —— 他不知道、也不需要知道我们往哪儿发请求。于是"同一个服务两种写法"这件事连同它
 * 需要的那套归一一起不存在了；而地址改了也不会让任何已配的密钥失联（密钥绑的是 id）。
 *
 * <pre>
 *   PUT    /api/auth/api-key   {"provider":"deepseek","apiKey":"sk-…"}
 *   GET    /api/auth/api-key   → [{"provider":"deepseek","hint":"sk-aaaa1*****4444","configuredAt":"…"}]
 *   DELETE /api/auth/api-key?provider=deepseek
 * </pre>
 *
 * <h2>为什么只露一个"提示"而不是把 key 读回去</h2>
 * 配完之后前端需要显示"配好了"，有时还要显示"配的是哪一把"—— 而这两件事都不需要
 * 把密钥再送出去一次。所以 {@code GET} 返回的是掩码：头八尾四、中间固定五个星
 * （{@code sk-aaaa1*****4444}），够人认出"那是我换上去的那把"，不足以还原。
 *
 * <p>把密钥回传出去这件事，收益是零（客户端本来就有），风险是把一次泄漏机会
 * 主动送到每个响应里。这一条没有取舍余地。
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
     * <p>门槛比"头 + 尾"再多留 4 位：刚好等于 12 的话，露头露尾就等于把整把给出去了。
     */
    private static final int MIN_LENGTH_TO_MASK_PARTIALLY =
            VISIBLE_PREFIX + VISIBLE_SUFFIX + 4;

    /** 中间那一串星。**固定个数，不跟着 key 的长度走** —— 长度也是信息。 */
    private static final String MASK_GAP = "*****";

    private final CurrentUser currentUser;
    private final ApiKeyStore keys;
    /** 解析"这一家的请求地址" —— 全应用唯一一处，见那个类的注释 */
    private final ProviderEndpoints endpoints;
    /** 只为解析服务商回的 JSON —— 那个响应的形状不归我们定 */
    private final ObjectMapper mapper;

    public ApiKeyController(CurrentUser currentUser, ApiKeyStore keys,
                            ProviderEndpoints endpoints, ObjectMapper mapper) {
        this.currentUser = currentUser;
        this.keys = keys;
        this.endpoints = endpoints;
        this.mapper = mapper;
    }

    /**
     * 配置或更换**某一家**的密钥。
     *
     * <p>用 PUT 而不是 POST：一个用户在同一个端点上只有一把，语义上是"替换成这个"。
     *
     * <h2>地址：预设的不用给，自定义的必须给</h2>
     * 预设那几家的地址在 {@link Providers} 里，请求里给了也**不采纳**（那等于允许客户端
     * 在服务端不知情的情况下把它指到别处）。认不出来的 id 则是**自定义 provider**：
     * 那时地址只能由调用方给，因为没有第二个地方会有它 —— 给了之后它住在这条记录里，
     * 仍然是"这一家的属性"，不是身份，所以什么归一都不需要。
     *
     * <p>界面上不出现请求地址（见类注释）；这一支是给直接调 API 的自定义 provider
     * 留的，它不改变主链路。
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
            // 认不出来的 id 又没给地址 → 配了也用不了，而"用不了"要等到发请求时才炸出来，
            // 那对用户是最难查的一种。所以在这里就拒掉，并把认识的那几家列出来
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

    /** 配过哪几家，各自一个掩码。**没有任何地方会把完整密钥读回来。** */
    @GetMapping
    public List<ConfiguredKeyView> list(Principal principal) {
        User me = currentUser.require(principal);
        return keys.listConfigured(me.id()).stream()
                .map(configured -> new ConfiguredKeyView(
                        configured.provider().value(),
                        // 名字可以留空 —— 界面上用预设名兜底。**不给默认值**：
                        // 自动编一个"供应商 1"之类的名字，比空着更让人困惑
                        configured.name() == null
                                ? Providers.displayNameOf(configured.provider())
                                : configured.name(),
                        // 这里要解密一次就为了做个掩码 —— 那是刻意的：
                        // 不这样就得再存一份"掩码"字段，而两份数据迟早会不一致
                        keys.find(me.id(), configured.provider())
                                .map(ApiKeyController::mask)
                                .orElse(null),
                        configured.configuredAt()))
                .toList();
    }

    /**
     * 这一家现在有哪些模型 —— **问服务商本人**。
     *
     * <h2>为什么这一步值得单独一个接口</h2>
     * 因为"我配好了密钥"之后紧接着的问题就是"那我可以用哪些模型"，而那个问题的答案
     * 只有服务商知道（新模型每周都在上，一份内置的表从写下那天起就在过期）。
     *
     * <p>用查询参数而不是路径变量：路径段里放 id 现在也行（id 是 slug，没有转义问题），
     * 但换形状要连着改一次客户端，而不换没有任何代价。
     *
     * <p><strong>这是密钥被解开的第二个地方</strong>（第一个是发聊天请求）。边界是：
     * 它只发给**这把密钥自己的归属方** —— 用的是这一家的地址，也就是它自己的地址。
     *
     * @return 模型 id 列表。**拉不到就报错，不返回空列表** —— 空列表在界面上长得和
     *         "这一家一个模型都没有"一样，而那是完全不同的一件事
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
            // 502：我们替用户去问了一个上游，它没给出我们要的东西。
            // **消息原样带出去** —— 它写清了是哪一种失败（密钥不对 / 没有这个路径 / 连不上），
            // 而那三种的处理方式完全不同
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    /**
     * 删掉**某一家**的密钥。
     *
     * <p>用查询参数而不换路径，理由同 {@link #models}。id 就是 id —— 传什么就是什么。
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
     * <p>头 8 位够人认出"是我换上去的那把"，尾 4 位够对上自己手里的那份；
     * 中间**固定五个星**，不跟长度走。
     */
    private static String mask(String apiKey) {
        if (apiKey.length() < MIN_LENGTH_TO_MASK_PARTIALLY) {
            // 短到露头露尾就等于把整把给出去了，那还不如全遮住
            return "****";
        }
        return apiKey.substring(0, VISIBLE_PREFIX)
                + MASK_GAP
                + apiKey.substring(apiKey.length() - VISIBLE_SUFFIX);
    }

    /**
     * @param provider 哪一家，**也是删除和拉模型时要回传的那个值**
     * @param name     显示名；没起过名字时回落到预设名
     * @param hint     掩码（头八尾四）；读不出来时为 null
     * @param configuredAt 最后一次更换的时间
     */
    public record ConfiguredKeyView(String provider, String name, String hint,
                                    Instant configuredAt) {
    }

    /**
     * @param provider 哪一家
     * @param baseUrl  请求地址。**预设的那几家不用给**（给了也不采纳）；认不出来的 id
     *                 必须给 —— 那是自定义 provider，而地址是它唯一的来源
     * @param name     显示名，可以留空（界面上用预设名兜底）
     * @param apiKey   明文密钥。它只出现在这个入参里，落库前就会被加密
     */
    public record ConfigureRequest(String provider, String baseUrl, String name, String apiKey) {
    }

    /** 这一家上可用的模型。 */
    public record ModelListView(List<String> models) {
    }
}
