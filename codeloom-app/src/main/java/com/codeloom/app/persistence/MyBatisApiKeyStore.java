package com.codeloom.app.persistence;

import com.codeloom.app.auth.SecretCipher;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.port.ApiKeyStore;
import com.codeloom.domain.user.UserId;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link ApiKeyStore} 的实现：加密与解密都收在这里。
 *
 * <p>加解密不交给调用方，让“存进去的一定是密文”成为结构事实：{@link ApiKeyMapper}
 * 只接受密文，而它只被这个类调用。
 */
@Repository
public class MyBatisApiKeyStore implements ApiKeyStore {

    private final ApiKeyMapper mapper;
    private final SecretCipher cipher;

    public MyBatisApiKeyStore(ApiKeyMapper mapper, SecretCipher cipher) {
        this.mapper = mapper;
        this.cipher = cipher;
    }

    /**
     * 加密后保存某一家的密钥；已存在则替换。
     *
     * @param owner    密钥所属用户
     * @param provider 服务商标识
     * @param baseUrl  自定义请求地址；预设服务商为 null
     * @param name     显示名，可以为 null
     * @param apiKey   明文密钥
     */
    @Override
    public void put(UserId owner, ProviderId provider, String baseUrl, String name, String apiKey) {
        mapper.save(owner.value(), provider.value(), name, baseUrl,
                cipher.encrypt(apiKey), Instant.now());
    }

    /**
     * 读取并解密某一家的密钥。
     *
     * @param owner    密钥所属用户
     * @param provider 服务商标识
     * @return 明文密钥；没有配置时为空
     */
    @Override
    public Optional<String> find(UserId owner, ProviderId provider) {
        // 明文只在这个方法里存在一小会儿。
        return Optional.ofNullable(mapper.findSealedKey(owner.value(), provider.value()))
                .map(cipher::decrypt);
    }

    /**
     * 删除某一家的密钥。
     *
     * @param owner    密钥所属用户
     * @param provider 服务商标识
     */
    @Override
    public void remove(UserId owner, ProviderId provider) {
        mapper.delete(owner.value(), provider.value());
    }

    /**
     * 列出这个用户配过密钥的服务商，不含密钥本身。
     *
     * @param owner 用户
     * @return 已配置的服务商
     */
    @Override
    public List<ConfiguredProvider> listConfigured(UserId owner) {
        // 行 → 领域：字符串标识在这里换成 ProviderId（见 ApiKeyRow）。
        return mapper.listConfigured(owner.value()).stream()
                .map(ApiKeyRow::toDomain)
                .toList();
    }
}
