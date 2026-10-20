package com.codeloom.app.llm;

import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.llm.Providers;
import com.codeloom.domain.port.ApiKeyStore;
import com.codeloom.domain.user.UserId;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 服务商到请求地址的解析，全应用唯一一处。
 *
 * <p>地址有两个来源，优先级固定：{@link Providers} 的预设表优先，其次才是用户密钥记录里的
 * {@code baseUrl}（只对自定义 provider 有意义）。分开写会让两处对优先级的判断走岔，
 * 表现为请求发到一个地址、界面显示另一个，且都不报错。
 *
 * <p>解析不出来就抛错，不悄悄回落到默认地址：那意味着这一家既不是预设、也没留下地址，
 * 配了也用不了。
 */
@Component
public class ProviderEndpoints {

    private final ApiKeyStore keys;

    public ProviderEndpoints(ApiKeyStore keys) {
        this.keys = keys;
    }

    /**
     * 这一家的请求地址。
     *
     * @param owner    密钥所属用户
     * @param provider 服务商
     * @return 预设地址或用户自定义地址
     * @throws IllegalStateException 两个来源都没有：认不出来的 id，且用户也没填过地址
     */
    public String baseUrlOf(UserId owner, ProviderId provider) {
        // 预设优先，见类注释；记录里那条要读库，所以放在后面。
        return Providers.find(provider)
                .map(Providers.Preset::baseUrl)
                .or(() -> fromRecord(owner, provider))
                .orElseThrow(() -> new IllegalStateException(
                        "这一家没有可用的请求地址：" + provider.value()
                                + "。它既不在预设里，他也没有为它留下地址"));
    }

    private Optional<String> fromRecord(UserId owner, ProviderId provider) {
        return keys.listConfigured(owner).stream()
                .filter(configured -> configured.provider().equals(provider))
                .map(ApiKeyStore.ConfiguredProvider::baseUrl)
                .filter(baseUrl -> baseUrl != null && !baseUrl.isBlank())
                .findFirst();
    }
}
