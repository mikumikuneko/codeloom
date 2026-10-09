package com.codeloom.agent.llm;

/**
 * 服务商返回的内容不符合预期协议（帧不是合法 JSON、字段缺失等）。
 *
 * <p>和"模型调用失败"区分开：这个是**我们的解析器和对方实现对不上**，
 * 通常意味着对方换了格式或我们支持的协议版本过旧，重试没有意义。
 */
public class LlmProtocolException extends RuntimeException {

    public LlmProtocolException(String message) {
        super(message);
    }

    public LlmProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
