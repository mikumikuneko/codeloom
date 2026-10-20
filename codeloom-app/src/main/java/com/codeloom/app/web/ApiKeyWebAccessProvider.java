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
 * 判断这条会话现在能否联网：能则给出 {@link WebAccess}，否则返回空。
 *
 * <p>两个条件缺一不可：会话使用的是 DeepSeek（搜索/抓取接口只有它提供，密钥和计费都记在
 * 该账号上，换别家时提供会让账单落在 DeepSeek）；该用户配置了 DeepSeek 密钥（没密钥给了
 * 工具也只会每次失败）。
 *
 * <p>只有这里提到具体供应商：{@code WebAccess} 不认识 provider，把"哪家提供此能力"收在一处，
 * 以后增加供应商只改这个判断。
 *
 * <p>理由见 {@code docs/decisions/feature/2026-10-17-web-search-and-fetch.md}。
 */
@Component
public class ApiKeyWebAccessProvider {

    /**
     * 用预设的 id，不在本类重复写字符串：两份一旦分叉，这里会静默判定所有会话都没有联网能力，
     * 而聊天路径完全正常。
     */
    private static final ProviderId DEEPSEEK = Providers.DEEPSEEK.id();

    private final ApiKeyStore keys;
    private final ProviderEndpoints endpoints;
    private final ObjectMapper mapper;

    /** 全类共用一个：HttpClient 内含连接池，每次新建等于丢弃连接池。 */
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
     * @param ownerId 会话所有者，也是密钥归属人
     * @param model   会话的模型配置；这里只看供应商
     * @return 可用的联网能力；供应商不是 DeepSeek 或没有密钥时为空
     */
    public Optional<WebAccess> find(UserId ownerId, ModelConfig model) {
        if (!DEEPSEEK.equals(model.provider())) {
            return Optional.empty();
        }
        // 先查密钥再建客户端：密钥刚被删掉时，已建出的对象仍然"能用"
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
