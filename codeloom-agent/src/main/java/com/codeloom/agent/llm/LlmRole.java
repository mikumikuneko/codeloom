package com.codeloom.agent.llm;

/** 消息角色。这是**中间表示**的一部分，与任何具体 provider 的命名无关。 */
public enum LlmRole {
    SYSTEM,
    USER,
    ASSISTANT,
    /** 工具执行结果回灌给模型。 */
    TOOL
}
