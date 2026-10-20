package com.codeloom.agent.model;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 按模型名查能力描述。上下文窗口直接决定压缩阈值，不同模型差异很大，因此用内置表；
 * 认不出的模型退回保守默认，不向服务商探测（各家的 {@code /models} 信息不一致）。
 *
 * <p>表里每一行都必须有出处：官方文档或实测，不能猜。见
 * {@code docs/decisions/bug-fix/2026-09-30-context-window-comes-from-measurement.md}。
 */
public final class ModelCapabilitiesResolver {

    /**
     * 一个已知模型的两项能力，放在同一条记录里以确保同时填写。
     *
     * @param contextWindow   上下文窗口
     * @param maxOutputTokens 单次输出上限，取官方文档的最大输出值
     */
    private record KnownModel(int contextWindow, int maxOutputTokens) {
    }

    /**
     * 查证过的模型。{@code deepseek-chat} 是旧名，服务商会映射为 {@code deepseek-flash}，
     * 但请求仍可写旧名，因此两者都列出。
     */
    private static final Map<String, KnownModel> KNOWN = Map.of(
            "deepseek-flash", new KnownModel(1_000_000, 384_000),
            "deepseek-chat", new KnownModel(1_000_000, 384_000),
            "deepseek-reasoner", new KnownModel(1_000_000, 384_000),
            "deepseek-v4-pro", new KnownModel(1_000_000, 384_000));

    /** 不支持工具调用的模型名单；当前为空，表示已知模型都支持。 */
    private static final Set<String> WITHOUT_TOOL_SUPPORT = Set.of();

    private ModelCapabilitiesResolver() {
    }

    /**
     * @param modelId 模型标识，可为 null（按未知模型处理）
     * @return 能力描述；永远有值，认不出来时返回保守默认
     */
    public static ModelCapabilities resolve(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return ModelCapabilities.UNKNOWN;
        }
        String key = modelId.toLowerCase(Locale.ROOT);
        KnownModel known = KNOWN.get(key);
        return new ModelCapabilities(
                !WITHOUT_TOOL_SUPPORT.contains(key),
                known == null ? ModelCapabilities.DEFAULT_CONTEXT_WINDOW : known.contextWindow(),
                known == null ? ModelCapabilities.DEFAULT_MAX_OUTPUT_TOKENS : known.maxOutputTokens());
    }
}
