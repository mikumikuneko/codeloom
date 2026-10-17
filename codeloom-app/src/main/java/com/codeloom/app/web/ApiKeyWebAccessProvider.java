package com.codeloom.app.web;

import com.codeloom.agent.web.WebAccess;
import com.codeloom.agent.web.WebAccessException;
import com.codeloom.app.llm.ApiKeyError;
import com.codeloom.app.llm.ProviderEndpoints;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.llm.Providers;
import com.codeloom.domain.port.ApiKeyStore;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.user.UserId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

/**
 * 「这条会话现在能不能联网」—— 能就给一个 {@link WebAccess}，不能就 {@code empty}。
 *
 * <h2>判据是两条，缺一不可</h2>
 * <ol>
 *   <li><b>这条会话用的是 DeepSeek</b> —— 这两个接口（{@code /v1/tools/search}、
 *       {@code /v1/tools/fetch}）目前只有它提供，密钥、计费都记在它那个账号上。
 *       所以模型换成别家时不提供联网：否则用户以为在用别家，账单却落在 DeepSeek 上。</li>
 *   <li><b>这个人配过 DeepSeek 的密钥</b> —— 没密钥就取不到资料，给了工具也只是每次失败。</li>
 * </ol>
 *
 * <h2>为什么只有这一处提到具体那一家</h2>
 * 工具的接口（{@code WebAccess}）不认识 provider。把"哪一家提供这个能力"收在这里，
 * 以后多一家就改这一个判断 —— 而不是散进工具、提示词和界面里。
 */
@Component
public class ApiKeyWebAccessProvider {

    /** 用预设那一份 id，不在这里再写一遍字符串 —— 两份 id 一旦分叉，
     *  这里会静默判定"所有会话都没有联网能力"，而聊天那条路完全正常。 */
    private static final ProviderId DEEPSEEK = Providers.DEEPSEEK.id();

    private final ApiKeyStore keys;
    private final ProviderEndpoints endpoints;
    private final ObjectMapper mapper;

    /** 全过程共用一个 —— 它内部有连接池，每次 new 一个等于把池丢了。 */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ApiKeyWebAccessProvider(ApiKeyStore keys, ProviderEndpoints endpoints, ObjectMapper mapper) {
        this.keys = keys;
        this.endpoints = endpoints;
        this.mapper = mapper;
    }

    /**
     * @param ownerId 这条会话的所有者 —— 密钥的归属人
     * @param model   这条会话的模型配置；这里只看它是哪一家
     */
    public Optional<WebAccess> find(UserId ownerId, ModelConfig model) {
        if (!DEEPSEEK.equals(model.provider())) {
            return Optional.empty();
        }
        // 先看密钥再建客户端，顺序不能反：刚删掉密钥之后，手里那个对象仍然"能用"
        if (keys.find(ownerId, DEEPSEEK).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DeepSeekWebAccess(mapper, http,
                endpoints.baseUrlOf(ownerId, DEEPSEEK),
                () -> keys.find(ownerId, DEEPSEEK).orElseThrow(() ->
                        new WebAccessException(ApiKeyError.goneMidTurn(
                                Providers.displayNameOf(DEEPSEEK))))));
    }
}
