package com.codeloom.agent.llm;

import java.util.Optional;

/**
 * 模型调用失败。
 *
 * <p>{@link Kind} 决定了上层的反应，**这比 HTTP 状态码本身更有用**：
 * <ul>
 *   <li>参数问题 → 回灌给模型让它自修，或提示用户改配置</li>
 *   <li>认证/余额 → 立刻停，重试一万次也没用</li>
 *   <li>限流/服务商故障 → 退避后重试</li>
 * </ul>
 */
public class LlmCallException extends RuntimeException {

    public enum Kind {
        /** 请求体不合法（400 / 422）。重试无意义。 */
        INVALID_REQUEST,
        /**
         * **上下文装不下了** —— provider 明确拒绝了这次请求。
         *
         * <h2>为什么不和 {@link #INVALID_REQUEST} 合成一个</h2>
         * 两者都是 400/422，但对上层的意思**正相反**：那个是"你这个请求写错了"
         *（该让模型换个写法），这个是"这次装不下了"（该压缩，而不是改请求）。
         * 合成一个的后果很具体：**用户看到的是一句"请求不合法"，而真正该做的是压缩**。
         *
         * <p>deepseek-harness 把它归一成一个**专门的码**（而不是混进"请求不合法"里），
         * 并且说清了理由：溢出这条路**不需要容量元数据** —— provider 刚给过答案了。
         *
         * <p>归类发生在**适配器**里（只有它知道自己在跟谁说话），
         * 恢复策略那一层绝不去解析文本。
         */
        CONTEXT_EXCEEDED,
        /** 密钥无效（401）。**要人去换一把 key** —— 重试一万次也没用。 */
        AUTH,
        /** 余额不足（402）。**要人去充值** —— 同上。 */
        INSUFFICIENT_BALANCE,
        /** 触发限流（429）。重试有意义，但要退避。 */
        RATE_LIMITED,
        /** 服务商故障（5xx）。重试有意义。 */
        SERVER_ERROR,
        /** 连不上或读超时。重试有意义。 */
        NETWORK,
        /**
         * 用户取消。**不是失败** —— 上层不该重试，也不该当错误报给用户。
         *
         * <p>单独成一类，是为了让调用方能区分"网络挂了"和"用户不想跑了" ——
         * 归进 {@link #NETWORK} 就分不出这两件事。
         */
        CANCELLED
    }

    private final transient Kind kind;

    /**
     * 服务商让我们等多久（毫秒）。null = 它没说。
     *
     * <h2>为什么它是一个字段，不是"拼进消息里"</h2>
     * 因为它要**参与计算**：退避等多久由它覆盖（服务商说 5 秒就等 5 秒，不要用我们的
     * 指数退避猜）。拼进消息里就只能靠解析文本拿回来 —— 而那是"恢复策略不许解析文本"
     * 这条规矩明确要避免的。
     *
     * <p>Claude Code 和 deepseek-harness 都把它当成一个结构化的事实，而不是拼进给界面看的文案里。
     *
     * <h2>它从哪来</h2>
     * HTTP 的 {@code Retry-After} 头，两种写法都要认：**秒数**（{@code 120}）和
     * **HTTP 日期**（{@code Wed, 21 Oct 2026 07:28:00 GMT}）。解析在适配器里做
     *（只有它拿得到响应头），见 {@code OpenAiCompatibleClient}。
     */
    private final transient Long retryAfterMs;

    /**
     * @param message 给人看的那句话。**HTTP 状态码和服务商的原文都拼在里面** ——
     *                它们不再各存一个字段、各开一个访问器（那些口子生产代码没人用，
     *                只有测试在读）。消息里已经有的事，不该再存一份
     */
    public LlmCallException(Kind kind, String message, Throwable cause) {
        this(kind, message, cause, null);
    }

    /**
     * @param retryAfterMs 服务商要求等多久；null = 它没说，由重试策略自己决定
     */
    public LlmCallException(Kind kind, String message, Throwable cause, Long retryAfterMs) {
        super(message, cause);
        this.kind = kind;
        this.retryAfterMs = retryAfterMs;
    }

    /** 服务商让我们等多久；空 = 它没说。 */
    public Optional<Long> retryAfterMs() {
        return Optional.ofNullable(retryAfterMs);
    }

    /**
     * 重试有没有意义。
     *
     * <p>注意 {@link Kind#INVALID_REQUEST} 是**不可重试**的 —— 同样的请求重发多少次
     * 都是同样的 400。这类失败应该回灌给模型让它换个做法，而不是烧掉重试预算。
     */
    public boolean retryable() {
        return switch (kind) {
            case RATE_LIMITED, SERVER_ERROR, NETWORK -> true;
            case INVALID_REQUEST, CONTEXT_EXCEEDED, AUTH, INSUFFICIENT_BALANCE, CANCELLED -> false;
        };
    }

    /**
     * 是不是**上下文装不下**。
     *
     * <p>注意它和 {@link #retryable()} 不冲突，虽然两个都是 false：
     * 回答的是两个问题。"同样这个请求再发一次有意义吗" —— 没有；
     * "有没有一条**换了状态之后**可以再来的路" —— 有（压缩之后再发）。
     */
    public boolean isContextExceeded() {
        return kind == Kind.CONTEXT_EXCEEDED;
    }

    /** 是不是用户主动取消的。 */
    public boolean isCancelled() {
        return kind == Kind.CANCELLED;
    }

    /**
     * 一句话说清是**哪一类**失败 —— 给人和界面看的。
     *
     * <p>为什么不直接显示 {@link Kind}：{@code RATE_LIMITED} 是给代码看的标识符，
     * 端到界面上就成了英文常量名。也不该把服务商的原文端上去 —— 那是英文的、
     * 还带着请求 id，而界面上要的是一个**稳定的标签**（可以拿它做颜色、做统计）。
     */
    public String shortReason() {
        return switch (kind) {
            case RATE_LIMITED -> "限流";
            case SERVER_ERROR -> "服务端故障";
            case NETWORK -> "网络错误";
            case CONTEXT_EXCEEDED -> "上下文超长";
            case AUTH -> "密钥无效";
            case INSUFFICIENT_BALANCE -> "余额不足";
            case INVALID_REQUEST -> "请求不合法";
            case CANCELLED -> "已取消";
        };
    }
}
