package com.codeloom.domain.event;

/**
 * 模型请求调用一个工具。
 *
 * <p>{@code argumentsJson} 存原始 JSON 而不是解析后的对象：它要原样回灌给模型，解析再序列化可能
 * 改变字段顺序或数字精度，而模型对这两者敏感。
 *
 * @param callId        模型给出的调用 id，用于和 {@link ToolResult} 配对
 * @param toolName      工具名
 * @param argumentsJson 模型给出的原始参数 JSON
 */
public record ToolCallRequested(String callId, String toolName, String argumentsJson) implements PersistentEvent {
}
