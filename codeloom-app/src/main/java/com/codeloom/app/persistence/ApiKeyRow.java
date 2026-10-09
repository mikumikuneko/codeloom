package com.codeloom.app.persistence;

import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.port.ApiKeyStore;

import java.time.Instant;

/**
 * {@code user_api_key} 的一行 —— 只有"配过哪几家"要的那几列。
 *
 * <h2>为什么这里是字符串，而端口里说的是 {@link ProviderId}</h2>
 * 因为**这一层的形状由表决定**：那一列是 {@code VARCHAR}，MyBatis 直接往组件里填，
 * 而它只认识字符串、数字、时间这几种。换算成领域类型是下面 {@link #toDomain()} 的事 ——
 * 和 {@link com.codeloom.workspace.persistence.SessionRow SessionRow} 同一条路子：**存储的形状留在适配器里，端口对外说领域的语言**。
 *
 * <p>读到认不出的值（被手工改过的列之类）就让它在 {@code ProviderId} 的构造器里炸。
 * 这是刻意的，理由和 {@code SessionRow} 一样：**炸得越早越好**，别把一个"看着像真值"的
 * 脏数据放进领域。
 *
 * <p>它**不带 {@code sealed_key}**：这个方法没有任何理由碰密文（见端口那条注释）。
 */
record ApiKeyRow(String provider, String baseUrl, String name, Instant configuredAt) {

    ApiKeyStore.ConfiguredProvider toDomain() {
        return new ApiKeyStore.ConfiguredProvider(
                ProviderId.of(provider), baseUrl, name, configuredAt);
    }
}
