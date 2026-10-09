package com.codeloom.app.llm;

import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.llm.Providers;
import com.codeloom.domain.port.ApiKeyStore;
import com.codeloom.domain.user.UserId;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 一家 → 它的请求地址。**全应用唯一一处解析地址的地方。**
 *
 * <h2>为什么要收成一处</h2>
 * "地址从哪儿来"有**两个来源**，而它们必须有一个确定的优先顺序：
 *
 * <ol>
 *   <li><b>{@link Providers} 那张预设表</b> —— 我们写的、一处，是权威；
 *   <li><b>他自己那条密钥记录里的 {@code baseUrl}</b> —— 给**自定义 provider** 留的位置。
 * </ol>
 *
 * <p>分开写的话，两处对"哪个优先"的判断迟早会不一样，而那种不一致的表现是
 * **请求发到其中一个地址、界面显示另一个**，且两边都不报错。
 *
 * <p>预设优先于记录：预设那几家的地址由我们定（记录里那一列对它们是空的），
 * 记录里那份只在"这一家不是预设"时才有意义。
 *
 * <p>解析不出来就**抛** —— 那意味着这一家既不在预设里、也没留下自己的地址，
 * 配了也用不了。认不出来恰恰是那件事本身，不能悄悄回落到一个默认地址上。
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
     * @throws IllegalStateException 两个来源都没有（认不出来的 id，且他也没填过地址）
     */
    public String baseUrlOf(UserId owner, ProviderId provider) {
        // 预设优先，见类注释。记录里那条要真读过库才拿得到，所以放在后面探
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
