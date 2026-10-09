package com.codeloom.agent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型能力表。
 *
 * <h2>为什么值得单独钉住</h2>
 * 这张表里的每个数都**直接改变行为**：窗口决定什么时候压缩、输出上限决定请求体里的
 * {@code max_tokens}。而它们的出处是"官方文档写着的，或者我们实测出来的"——
 * 既然是查来的，就得有一条断言把查到的值固定在那儿，否则某次手滑把 384_000 写成 384
 * 不会有任何人发现（压缩水位照样算，只是请求被服务商拒）。
 */
class ModelCapabilitiesResolverTest {

    @Test
    @DisplayName("认得的模型给查证过的值：窗口 1M、输出上限 38.4 万")
    void knownModelsGetTheVerifiedValues() {
        for (String modelId : new String[]{
                "deepseek-flash", "deepseek-chat", "deepseek-reasoner", "deepseek-v4-pro"}) {
            ModelCapabilities capabilities = ModelCapabilitiesResolver.resolve(modelId);

            assertThat(capabilities.contextWindow()).as("%s 的窗口", modelId).isEqualTo(1_000_000);
            // 官方 pricing 页写的是 MAXIMUM: 384K —— 这个字段的语义就是"上限"，
            // 所以取最大值，而不是"我们希望它写多长"
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

        // 窗口估大了会静默超限；输出上限估大了会被服务商直接拒。两个方向的代价都不对称，
        // 所以都往小的取 —— 这就是"保守"在这里的具体含义
        assertThat(capabilities.contextWindow()).isEqualTo(ModelCapabilities.DEFAULT_CONTEXT_WINDOW);
        assertThat(capabilities.maxOutputTokens())
                .isEqualTo(ModelCapabilities.DEFAULT_MAX_OUTPUT_TOKENS);
        // 但**假定支持工具**：不支持工具调用的模型在这个 agent 里根本没法用，
        // "未知"该退化成"能用、但可能不灵"，而不是"拒绝一个也许完全正常的模型"
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
