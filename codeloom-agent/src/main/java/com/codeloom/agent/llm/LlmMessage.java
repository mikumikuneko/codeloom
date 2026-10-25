package com.codeloom.agent.llm;

import java.util.List;
import java.util.Objects;

/**
 * 对话里的一条消息，provider 无关的中间表示（IR）。协议差异（字段名、content 形状）
 * 由适配器处理，agent 循环只与这个类型打交道。
 *
 * @param role       这条消息是谁说的
 * @param content    正文；{@link LlmRole#TOOL} 消息里是工具执行结果
 * @param toolCalls  仅 {@link LlmRole#ASSISTANT}：模型发起的工具调用
 * @param toolCallId 仅 {@link LlmRole#TOOL}：回应的是哪一次调用
 * @param reasoning  仅 {@link LlmRole#ASSISTANT}：这条回复当时的思考过程
 * @param model      产出这条回复的模型。{@code reasoning} 只对产生它的模型成立，
 *                   是否回传据此判断（见 {@code OpenAiCompatibleClient#wireMessage}）；
 *                   null = 来源未知（老事件、测试），按旧行为发送
 */
public record LlmMessage(LlmRole role,
                          String content,
                          List<ToolCall> toolCalls,
                          String toolCallId,
                          String reasoning,
                          String model) {

    public LlmMessage {
        Objects.requireNonNull(role, "role");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (reasoning != null && reasoning.isBlank()) {
            // 空串与“没有”在上游看起来一样；下游靠 null 决定是否发送该字段。
            reasoning = null;
        }
        if (model != null && model.isBlank()) {
            model = null;
        }
        if (role == LlmRole.TOOL && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("TOOL 消息必须带 toolCallId，否则模型对不上是哪次调用");
        }
        if (role != LlmRole.TOOL && toolCallId != null) {
            throw new IllegalArgumentException("只有 TOOL 消息能带 toolCallId，收到角色 " + role);
        }
        if (role != LlmRole.ASSISTANT && reasoning != null) {
            throw new IllegalArgumentException("只有 ASSISTANT 消息能带 reasoning，收到角色 " + role);
        }
        if (role != LlmRole.ASSISTANT && model != null) {
            throw new IllegalArgumentException("只有 ASSISTANT 消息能带 model，收到角色 " + role);
        }
    }

    public static LlmMessage system(String text) {
        return new LlmMessage(LlmRole.SYSTEM, text, List.of(), null, null, null);
    }

    public static LlmMessage user(String text) {
        return new LlmMessage(LlmRole.USER, text, List.of(), null, null, null);
    }

    /**
     * 模型说的话。正文可以为空（只调工具），但思考和来源模型要一路带着：
     * 带 {@code tool_calls} 的 assistant 消息必须回传当时的 {@code reasoning_content}，
     * 且该义务只对产生它的模型成立。适配器据此决定是否发送。
     *
     * @param text      正文，可为空
     * @param model     产出这条回复的模型；null = 来源未知（老事件、测试）
     * @param reasoning 这条回复的思考过程，可为 null
     * @return assistant 消息
     */
    public static LlmMessage assistant(String text, String model, String reasoning) {
        return new LlmMessage(LlmRole.ASSISTANT, text, List.of(), null, reasoning, model);
    }

    public static LlmMessage assistantWithToolCalls(String text, List<ToolCall> toolCalls,
                                                     String model, String reasoning) {
        return new LlmMessage(LlmRole.ASSISTANT, text, toolCalls, null, reasoning, model);
    }

    public static LlmMessage toolResult(String toolCallId, String content) {
        return new LlmMessage(LlmRole.TOOL, content, List.of(), toolCallId, null, null);
    }

    /**
     * @return 是否带工具调用
     */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * 正文可能为 null（只调工具不说话）；统一取空串。
     *
     * @return 正文，永不为 null
     */
    public String textOrEmpty() {
        return content == null ? "" : content;
    }
}
