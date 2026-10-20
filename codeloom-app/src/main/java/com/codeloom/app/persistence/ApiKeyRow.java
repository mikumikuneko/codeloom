package com.codeloom.app.persistence;

import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.port.ApiKeyStore;

import java.time.Instant;

/**
 * {@code user_api_key} 的一行，只有“配过哪几家”需要的列。
 *
 * <p>这里用字符串而不是 {@link ProviderId}，因为这一层的形状由表决定，MyBatis 只认识
 * 字符串、数字、时间；换算成领域类型在 {@link #toDomain()} 做，存储的形状留在适配器里。
 * 形状不合法的值让 {@code ProviderId} 构造器立刻失败，不把脏数据放进领域；构造器只
 * 校验形状，自定义 provider 仍会通过。
 *
 * <p>不带 {@code sealed_key}：这里没有理由碰密文。
 */
record ApiKeyRow(String provider, String baseUrl, String name, Instant configuredAt) {

    ApiKeyStore.ConfiguredProvider toDomain() {
        return new ApiKeyStore.ConfiguredProvider(
                ProviderId.of(provider), baseUrl, name, configuredAt);
    }
}
