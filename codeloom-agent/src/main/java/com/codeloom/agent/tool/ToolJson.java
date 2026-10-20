package com.codeloom.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工具与上下文整理共用的 {@link ObjectMapper}。模型协议侧刻意另持一个，
 * 避免协议序列化配置影响工具解析。
 */
public final class ToolJson {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolJson() {
    }
}
