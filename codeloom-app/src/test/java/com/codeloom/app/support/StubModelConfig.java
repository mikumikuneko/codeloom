package com.codeloom.app.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 用脚本化的假客户端换掉真实模型 —— 真模型不可重复、要钱、要网络。
 *
 * <p>它曾是两个测试类里各抄一遍的（连注释都一样）：代价是以后换 stub 类型时会漏改一个，
 * 漏掉的那个就成了"某个测试里模型是真请求"，红起来像是业务逻辑错了。
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
