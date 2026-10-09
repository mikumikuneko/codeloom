package com.codeloom.agent.model;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 按模型名查出能力描述。
 *
 * <h2>上下文窗口为什么是一张表</h2>
 * 窗口真的被读（{@code ContextCompactor} 拿它算压缩阈值），而不同的模型差得很远，
 * 所以按模型名查表。
 *
 * <p>这里栽过一个实打实的跟头：{@code DEFAULT_CONTEXT_WINDOW} 是 64,000，被当成
 * **所有**模型的窗口用 —— 包括 DeepSeek 的，而 DeepSeek 的窗口实测是 **1,000,000**
 *（官方文档写着 1M，实测也没有静默截断）。于是**压缩在 9% 的地方就触发了**，
 * 每压一次白花一次模型调用、作废一次 prompt 缓存、丢掉一段细节。
 *
 * <p>参考实现（deepseek-harness）是**由 provider 侧声明窗口**的：它的 adapter 里
 * 写明默认 1,000,000，压缩那边直接读，**读不到就报错、不兜底**。这里照这个形状：
 * 认得的模型给查证过的真值，认不出的退回一个保守默认。
 *
 * <h2>往表里加一行要什么</h2>
 * <strong>出处。</strong>官方文档写着的，或者我们实测出来的 —— 两者都行，猜的不行。
 * 猜大的后果是这一轮直接超限失败、用户丢掉整段对话；猜小的后果只是多压缩几次。
 * 两者不对称，所以宁可不加。
 *
 * <h2>为什么不问服务商要</h2>
 * 各家的 {@code /models} 端点返回的信息既不一致也不完整，靠探测猜能力会得到一个
 * 时灵时不灵的循环。所以这里是一份内置表 + 保守默认。
 */
public final class ModelCapabilitiesResolver {

    /**
     * 查证过的模型窗口。
     *
     * <p>{@code deepseek-chat} 是个旧名，服务商会把它映射成 {@code deepseek-flash}
     * （响应里的 {@code model} 回的就是后者）—— 但请求时可以写旧名，所以两边都要列。
     */
    private static final Map<String, Integer> CONTEXT_WINDOWS = Map.of(
            "deepseek-flash", 1_000_000,
            "deepseek-chat", 1_000_000,
            "deepseek-reasoner", 1_000_000,
            "deepseek-v4-pro", 1_000_000);

    /**
     * 查证过的**单次输出上限**，也就是请求体里的 {@code max_tokens}。
     *
     * <p>取值就是官方文档写的那个**最大输出**（pricing 页：「MAXIMUM: 384K」），
     * 而不是"我们希望它写多长" —— 这个字段的语义本来就是**上限**：模型想收就收，
     * 这个数只负责在它不想收的时候把它拦住。
     *
     * <p>写死成一个很大的上限没有额外代价：它不预扣费用，压缩那边算余量时也会被
     * {@code RESERVED_OUTPUT_CAP} 夹住（见 {@code ContextCompactor.fullThreshold}），
     * 所以水位不跟着它走。
     *
     * <p>认不出的模型不给这个值，退回 {@code DEFAULT_MAX_OUTPUT_TOKENS} —— 那是保守的方向，
     * 理由见那个常量。
     */
    private static final Map<String, Integer> MAX_OUTPUT_TOKENS = Map.of(
            "deepseek-flash", 384_000,
            "deepseek-chat", 384_000,
            "deepseek-reasoner", 384_000,
            "deepseek-v4-pro", 384_000);

    /**
     * 不支持工具调用的模型。
     *
     * <p>空名单不是"没做"，是"当前的答案是全都能用"。要禁掉某个模型，
     * 往这里加个名字即可，而循环那边不用动一行。
     */
    private static final Set<String> WITHOUT_TOOL_SUPPORT = Set.of();

    private ModelCapabilitiesResolver() {
    }

    /**
     * @param modelId 模型标识，可为 null（那就当未知模型）
     * @return 能力描述，永远有值 —— 认不出来就给默认
     */
    public static ModelCapabilities resolve(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return ModelCapabilities.UNKNOWN;
        }
        String key = modelId.toLowerCase(Locale.ROOT);
        return new ModelCapabilities(
                !WITHOUT_TOOL_SUPPORT.contains(key),
                CONTEXT_WINDOWS.getOrDefault(key, ModelCapabilities.DEFAULT_CONTEXT_WINDOW),
                MAX_OUTPUT_TOKENS.getOrDefault(key, ModelCapabilities.DEFAULT_MAX_OUTPUT_TOKENS));
    }
}
