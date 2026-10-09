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
 * {@link ApiKeyStore} 的实现：**加密在这里、解密也在这里**，两边都不越界。
 *
 * <p>把加解密收在仓储里（而不是让调用方自己加密完再交进来）是为了让"存进去的一定是密文"
 * 成为一个**结构上的事实**而不是一句约定：{@link ApiKeyMapper} 唯一接受的入参就是密文，
 * 而它只被这一个类调用。
 */
@Repository
public class MyBatisApiKeyStore implements ApiKeyStore {

    private final ApiKeyMapper mapper;
    private final SecretCipher cipher;

    public MyBatisApiKeyStore(ApiKeyMapper mapper, SecretCipher cipher) {
        this.mapper = mapper;
        this.cipher = cipher;
    }

    @Override
    public void put(UserId owner, ProviderId provider, String baseUrl, String name, String apiKey) {
        mapper.save(owner.value(), provider.value(), name, baseUrl,
                cipher.encrypt(apiKey), Instant.now());
    }

    @Override
    public Optional<String> find(UserId owner, ProviderId provider) {
        // 取出来的是密文，解开才还给它 —— 明文只在这个方法里存在一小会儿
        return Optional.ofNullable(mapper.findSealedKey(owner.value(), provider.value()))
                .map(cipher::decrypt);
    }

    @Override
    public void remove(UserId owner, ProviderId provider) {
        mapper.delete(owner.value(), provider.value());
    }

    @Override
    public List<ConfiguredProvider> listConfigured(UserId owner) {
        // 行 → 领域：那一层的字符串标识在这儿换成 ProviderId（见 ApiKeyRow）
        return mapper.listConfigured(owner.value()).stream()
                .map(ApiKeyRow::toDomain)
                .toList();
    }
}
