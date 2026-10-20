package com.codeloom.app.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 用脚本化的假客户端替换真实模型：真模型不可重复、要钱、要网络。
 *
 * <p>集中一处避免各测试类各抄一份，否则更换 stub 类型时可能漏改，那个测试会真的发出模型请求，
 * 失败时却像是业务逻辑错误。
 *
 * <p>用法：在测试类上加 {@code @Import(StubModelConfig.class)}。
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubModelConfig {

    @Bean
    @Primary
    public ScriptedLlmClientProvider scriptedLlmClients() {
        return new ScriptedLlmClientProvider();
    }
}
