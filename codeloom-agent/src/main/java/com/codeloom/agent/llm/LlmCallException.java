package com.codeloom.agent.llm;

import java.util.Optional;

/**
 * 模型调用失败。{@link Kind} 决定上层反应：限流、服务商故障和网络错误退避后重试；
 * 上下文超长先尝试压缩修复一次；参数非法、认证和余额问题不重试，最终让整轮失败；
 * 用户取消不是失败，按取消收尾。以上都不会回灌给模型。
 */
public class LlmCallException extends RuntimeException {

    public enum Kind {
        /** 请求体不合法（400 / 422）；重试无意义。 */
        INVALID_REQUEST,
        /**
         * provider 明确拒绝，因为上下文装不下。它和 {@link #INVALID_REQUEST} 都是 400/422，
         * 但前者该压缩、后者该改请求；归类在适配器里完成，恢复策略不解析文本。
         */
        CONTEXT_EXCEEDED,
        /** 密钥无效（401）；需要换 key，重试无意义。 */
        AUTH,
        /** 余额不足（402）；需要充值，重试无意义。 */
        INSUFFICIENT_BALANCE,
        /** 触发限流（429）；退避后重试有意义。 */
        RATE_LIMITED,
        /** 服务商故障（5xx）；重试有意义。 */
        SERVER_ERROR,
        /** 连不上或读超时；重试有意义。 */
        NETWORK,
        /** 用户取消；不是失败，不重试，也不应作为错误报给用户。 */
        CANCELLED
    }

    private final transient Kind kind;

    /**
     * 服务商要求的等待时间（毫秒）；null = 它没说。它参与退避计算，必须保持结构化，
     * 不能让恢复策略解析文本。来源是 HTTP {@code Retry-After} 头（秒数或 HTTP 日期），
     * 解析在适配器里完成，见 {@code OpenAiCompatibleClient}。
     */
    private final transient Long retryAfterMs;

    /**
     * @param kind    失败分类
     * @param message 给人看的说明，包含 HTTP 状态码和服务商原文
     * @param cause   底层异常
     */
    public LlmCallException(Kind kind, String message, Throwable cause) {
        this(kind, message, cause, null);
    }

    /**
     * @param kind         失败分类
     * @param message      给人看的说明
     * @param cause        底层异常
     * @param retryAfterMs 服务商要求的等待时间；null = 它没说，由重试策略决定
     */
    public LlmCallException(Kind kind, String message, Throwable cause, Long retryAfterMs) {
        super(message, cause);
        this.kind = kind;
        this.retryAfterMs = retryAfterMs;
    }

    /** 服务商要求的等待时间；空 = 它没说。 */
    public Optional<Long> retryAfterMs() {
        return Optional.ofNullable(retryAfterMs);
    }

    /**
     * 原样重发这个请求是否有意义。
     *
     * @return 限流、服务商故障和网络错误为 true；参数、认证、余额、上下文超限和取消为 false
     */
    public boolean retryable() {
        return switch (kind) {
            case RATE_LIMITED, SERVER_ERROR, NETWORK -> true;
            case INVALID_REQUEST, CONTEXT_EXCEEDED, AUTH, INSUFFICIENT_BALANCE, CANCELLED -> false;
        };
    }

    /**
     * 是否是上下文装不下。它不可原样重试，但压缩改变状态后可以再发。
     *
     * @return 分类为 {@link Kind#CONTEXT_EXCEEDED} 时为 true
     */
    public boolean isContextExceeded() {
        return kind == Kind.CONTEXT_EXCEEDED;
    }

    /**
     * @return 是否是用户主动取消
     */
    public boolean isCancelled() {
        return kind == Kind.CANCELLED;
    }

    /**
     * 失败类别的中文短标签，供界面显示、着色和统计；不暴露枚举名或服务商原文。
     *
     * @return 稳定标签
     */
    public String shortReason() {
        return switch (kind) {
            case RATE_LIMITED -> "限流";
            case SERVER_ERROR -> "服务商故障";
            case NETWORK -> "网络错误";
            case CONTEXT_EXCEEDED -> "上下文超长";
            case AUTH -> "密钥无效";
            case INSUFFICIENT_BALANCE -> "余额不足";
            case INVALID_REQUEST -> "请求不合法";
            case CANCELLED -> "已取消";
        };
    }
}
