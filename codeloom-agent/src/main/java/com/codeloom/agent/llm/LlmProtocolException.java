package com.codeloom.agent.llm;

/**
 * 服务商返回的内容不符合预期协议（帧不是合法 JSON、字段缺失等）。
 *
 * <p>它表示解析器与对方实现不一致，重试没有意义；与 {@link LlmCallException} 的调用失败区分开。
 */
public class LlmProtocolException extends RuntimeException {

    public LlmProtocolException(String message) {
        super(message);
    }

    public LlmProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
