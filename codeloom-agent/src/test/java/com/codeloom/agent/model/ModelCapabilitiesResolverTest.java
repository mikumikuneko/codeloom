package com.codeloom.agent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型能力表。窗口决定压缩时机，输出上限进入请求的 {@code max_tokens}；
 * 这些值来自官方文档或实测，必须有断言固定，避免手误后静默改变行为。
 */
class ModelCapabilitiesResolverTest {

    @Test
    @DisplayName("认得的模型给查证过的值：窗口 1M、输出上限 38.4 万")
    void knownModelsGetTheVerifiedValues() {
        for (String modelId : new String[]{
                "deepseek-flash", "deepseek-chat", "deepseek-reasoner", "deepseek-v4-pro"}) {
            ModelCapabilities capabilities = ModelCapabilitiesResolver.resolve(modelId);

            assertThat(capabilities.contextWindow()).as("%s 的窗口", modelId).isEqualTo(1_000_000);
            // 字段语义是上限，取官方文档的最大输出值。
            assertThat(capabilities.maxOutputTokens()).as("%s 的输出上限", modelId).isEqualTo(384_000);
            assertThat(capabilities.supportsTools()).as("%s 支持工具", modelId).isTrue();
        }
    }

    @Test
    @DisplayName("名字大小写不敏感 —— 服务端回来的是小写，但请求里什么写法都可能")
    void modelIdsAreCaseInsensitive() {
        assertThat(ModelCapabilitiesResolver.resolve("DeepSeek-Flash").contextWindow())
                .isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("认不出的模型退回**保守**默认 —— 两个数都往小的取")
    void unknownModelsFallBackToTheConservativeDefaults() {
        ModelCapabilities capabilities = ModelCapabilitiesResolver.resolve("some-local-model");

        // 两个数都取小：估大分别导致静默超限或请求被拒。
        assertThat(capabilities.contextWindow()).isEqualTo(ModelCapabilities.DEFAULT_CONTEXT_WINDOW);
        assertThat(capabilities.maxOutputTokens())
                .isEqualTo(ModelCapabilities.DEFAULT_MAX_OUTPUT_TOKENS);
        // 工具能力假定支持：未知模型应退化为可用，而不是被直接拒绝。
        assertThat(capabilities.supportsTools()).isTrue();
    }

    @Test
    @DisplayName("null / 空名字按未知处理，不抛")
    void blankModelIdsAreUnknown() {
        assertThat(ModelCapabilitiesResolver.resolve(null)).isEqualTo(ModelCapabilities.UNKNOWN);
        assertThat(ModelCapabilitiesResolver.resolve("")).isEqualTo(ModelCapabilities.UNKNOWN);
        assertThat(ModelCapabilitiesResolver.resolve("   ")).isEqualTo(ModelCapabilities.UNKNOWN);
    }
}
