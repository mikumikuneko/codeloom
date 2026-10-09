package com.codeloom.agent.llm;

import java.util.List;
import java.util.Objects;

/**
 * 对话里的一条消息 —— **中间表示**（IR）。
 *
 * <p>它刻意是 provider 无关的：没有 {@code tool_call_id} 该叫什么的纠结、
 * 没有 {@code content} 该是字符串还是数组的纠结，那些都由 adapter 去处理。
 * agent 循环只跟这个类型打交道，所以换 provider 不用改循环。
 *
 * <p>用一条 record 覆盖四种角色而不是四个子类，是因为它们的载荷差异很小
 * （都是文本 + 可选的工具信息），拆开反而要在每个处理点写四路分支。
 *
 * @param content    正文。{@link ChatRole#TOOL} 消息里是工具的执行结果
 * @param toolCalls  仅 {@link ChatRole#ASSISTANT} 可能有：模型发起的工具调用
 * @param toolCallId 仅 {@link ChatRole#TOOL} 需要：回应的是哪一次调用
 * @param reasoning  仅 {@link ChatRole#ASSISTANT} 可能有：这条回复**当时**的思考过程
 * @param model      **产出这条回复的模型**。仅 {@link ChatRole#ASSISTANT} 有。
 *                   它存在的唯一理由是：{@code reasoning} 是**只对产生它的那个模型成立**的东西，
 *                   换了模型之后不能原样发回去（见 {@code OpenAiCompatibleClient#wireMessage}）。
 *                   null = **来源未知**（老事件、测试），那时维持旧行为：照发
 */
public record ChatMessage(ChatRole role,
                          String content,
                          List<ToolCall> toolCalls,
                          String toolCallId,
                          String reasoning,
                          String model) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (reasoning != null && reasoning.isBlank()) {
            // 空串和"没有"是两件事，而带上游看着一样。统一成 null ——
            // 下游（wire 那层）靠"是不是 null"决定发不发那个字段
            reasoning = null;
        }
        if (model != null && model.isBlank()) {
            model = null;
        }
        if (role == ChatRole.TOOL && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("TOOL 消息必须带 toolCallId，否则模型对不上是哪次调用");
        }
        if (role != ChatRole.TOOL && toolCallId != null) {
            throw new IllegalArgumentException("只有 TOOL 消息能带 toolCallId，收到角色 " + role);
        }
        if (role != ChatRole.ASSISTANT && reasoning != null) {
            throw new IllegalArgumentException("只有 ASSISTANT 消息能带 reasoning，收到角色 " + role);
        }
        if (role != ChatRole.ASSISTANT && model != null) {
            throw new IllegalArgumentException("只有 ASSISTANT 消息能带 model，收到角色 " + role);
        }
    }

    public static ChatMessage system(String text) {
        return new ChatMessage(ChatRole.SYSTEM, text, List.of(), null, null, null);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(ChatRole.USER, text, List.of(), null, null, null);
    }

    /**
     * 模型说的话。
     *
     * <h2>为什么正文可以为空、思考却要一路带着</h2>
     * 模型有一轮可能**只调工具、一个字不说**，但它的思考是有的。
     * 而 OpenAI 兼容阵营里的推理模型（DeepSeek 的思维链模式就是一例）
     * 要求：**带 {@code tool_calls} 的那条 assistant 消息，必须把它当时的
     * {@code reasoning_content} 原样带回来** —— 不带就 400。
     * 所以这条消息的 {@code reasoning} 不是"附赠的展示信息"，它是**协议的一部分**。
     *
     * <h2>而"必须带回来"只对**产生它的那个模型**成立</h2>
     * 那句话的另一半是：换了模型之后，这份思考**不再属于**新模型 —— 它是别人产的。
     * 所以 {@code model} 不是"审计用的备注"，它是 {@code reasoning} 能不能发出去的依据。
     * 谁来做这个判断、依据什么，见 {@code OpenAiCompatibleClient#wireMessage}。
     *
     * <p>它也是**中性名**：具体在 wire 上叫什么（{@code reasoning_content}、
     * 还是别的），由各家的 adapter 决定。
     *
     * @param model **产出这条回复的模型**。null = 不知道（老事件、测试）
     */
    public static ChatMessage assistant(String text, String model, String reasoning) {
        return new ChatMessage(ChatRole.ASSISTANT, text, List.of(), null, reasoning, model);
    }

    public static ChatMessage assistantWithToolCalls(String text, List<ToolCall> toolCalls,
                                                     String model, String reasoning) {
        return new ChatMessage(ChatRole.ASSISTANT, text, toolCalls, null, reasoning, model);
    }

    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage(ChatRole.TOOL, content, List.of(), toolCallId, null, null);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** 正文可能为 null（只调工具不说话），统一取成空串省得到处判空。 */
    public String textOrEmpty() {
        return content == null ? "" : content;
    }
}
