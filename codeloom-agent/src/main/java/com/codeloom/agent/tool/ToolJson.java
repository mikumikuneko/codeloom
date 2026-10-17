package com.codeloom.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工具这边解析参数/结果共用的 {@link ObjectMapper}。
 *
 * <p>它**只服务工具与上下文整理**（参数校验、读文件记账、联网结果的 JSON）。
 * 模型协议那边（{@code AgentTurn}、LLM 客户端）**刻意另外持有**一个 ——
 * 那两份的配置方向不同，混用会让"改协议序列化"顺手改掉工具解析。
 *
 * <p>其余几处各 new 一个没有任何理由：配置相同、生命周期相同，只是多出几个
 * 要同步维护的对象。
 */
public final class ToolJson {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolJson() {
    }
}
