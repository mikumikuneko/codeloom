package com.codeloom.app.web;

import com.codeloom.app.llm.ProviderEndpoints;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.port.ApiKeyStore;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.user.UserId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 联网能力的门控：**哪一家、有没有密钥**。
 *
 * <p>这两条是用户拍板的口径（见决策记录）：模型不是 DeepSeek 时不提供联网 ——
 * 否则用户以为在用别家，账单却落在 DeepSeek 账号上。
 */
class ApiKeyWebAccessProviderTest {

    private final FakeKeyStore keys = new FakeKeyStore();
    private final ApiKeyWebAccessProvider provider = new ApiKeyWebAccessProvider(
            keys, new ProviderEndpoints(keys), new ObjectMapper());

    private final UserId user = UserId.of("u-1");

    @Test
    @DisplayName("模型不是 DeepSeek：不给联网，哪怕他配过 DeepSeek 的密钥")
    void otherProvidersGetNothing() {
        keys.keys.put(ProviderId.of("deepseek"), "sk-deepseek");

        assertThat(provider.find(user, model("openai"))).isEmpty();
    }

    @Test
    @DisplayName("是 DeepSeek 但没配密钥：不给联网 —— 给了也每次都失败")
    void deepSeekWithoutAKeyGetsNothing() {
        assertThat(provider.find(user, model("deepseek"))).isEmpty();
    }

    @Test
    @DisplayName("是 DeepSeek 且配了密钥：给")
    void deepSeekWithAKeyGetsWebAccess() {
        keys.keys.put(ProviderId.of("deepseek"), "sk-deepseek");

        assertThat(provider.find(user, model("deepseek"))).isPresent();
    }

    private static ModelConfig model(String provider) {
        return new ModelConfig(ProviderId.of(provider), "some-model", null);
    }

    /** 只够这几条用例用的密钥存储：按 provider 记一个明文值。 */
    private static final class FakeKeyStore implements ApiKeyStore {

        final Map<ProviderId, String> keys = new HashMap<>();

        @Override
        public void put(UserId owner, ProviderId provider, String baseUrl, String name, String apiKey) {
            keys.put(provider, apiKey);
        }

        @Override
        public Optional<String> find(UserId owner, ProviderId provider) {
            return Optional.ofNullable(keys.get(provider));
        }

        @Override
        public void remove(UserId owner, ProviderId provider) {
            keys.remove(provider);
        }

        @Override
        public List<ConfiguredProvider> listConfigured(UserId owner) {
            return keys.keySet().stream()
                    .map(provider -> new ConfiguredProvider(provider, null, null, Instant.EPOCH))
                    .toList();
        }
    }
}
