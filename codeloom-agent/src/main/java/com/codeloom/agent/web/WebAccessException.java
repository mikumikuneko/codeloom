package com.codeloom.agent.web;

/**
 * 一次联网请求失败。
 *
 * <p>消息是**给模型看的**：工具会把它原样放进工具结果里，所以写清"为什么失败、
 * 接下来能做什么"，别写成给人排障用的那种。
 */
public final class WebAccessException extends RuntimeException {

    public WebAccessException(String message) {
        super(message);
    }

    public WebAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
