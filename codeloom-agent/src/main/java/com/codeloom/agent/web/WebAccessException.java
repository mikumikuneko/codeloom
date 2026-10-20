package com.codeloom.agent.web;

/**
 * 一次联网请求失败。消息会原样进入工具结果给模型看，应说明原因和下一步能做什么。
 */
public final class WebAccessException extends RuntimeException {

    /**
     * @param message 给模型看的失败说明
     */
    public WebAccessException(String message) {
        super(message);
    }

    /**
     * @param message 给模型看的失败说明
     * @param cause   底层异常
     */
    public WebAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
