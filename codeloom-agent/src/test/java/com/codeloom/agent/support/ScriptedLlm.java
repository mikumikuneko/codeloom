package com.codeloom.agent.support;

import com.codeloom.agent.llm.LlmRequest;
import com.codeloom.agent.llm.LlmCallException;
import com.codeloom.agent.llm.LlmClient;
import com.codeloom.agent.llm.LlmResult;
import com.codeloom.agent.llm.StreamEvent;
import com.codeloom.agent.llm.TokenUsage;
import com.codeloom.agent.llm.ToolCall;
import com.codeloom.domain.port.CancellationToken;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * 按脚本返回模型结果的假客户端，是测试中模型的唯一替身。它按脚本返回结果，
 * 并记录真实收到的请求，供断言“模型到底看到了什么”。
 *
 * <p>三种模式：按序返回且用尽即抛（暴露多余的循环）；{@link #repeating} 用尽后重复最后一个；
 * {@link #always} 始终返回同一结果。用尽即抛能发现循环比预期多转了一圈。
 */
public final class ScriptedLlm implements LlmClient {

    private final Deque<LlmResult> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    private boolean repeatLast;
    private LlmResult last;

    /** 每次调用前执行的钩子；默认无操作，用于模拟调用期间发生的取消或失败。 */
    private Runnable onCall = () -> {
    };

    /** 空脚本，随后用 {@link #script} 设置。 */
    public ScriptedLlm() {
    }

    public ScriptedLlm(LlmResult... results) {
        script(results);
    }

    /** 脚本用尽后重复最后一个结果，见类注释。 */
    public static ScriptedLlm repeating(LlmResult... results) {
        ScriptedLlm client = new ScriptedLlm(results);
        client.repeatLast = true;
        return client;
    }

    /** 无论被问多少次都是这一个结果。 */
    public static ScriptedLlm always(LlmResult result) {
        return repeating(result);
    }

    /**
     * 换新脚本，并清除调用前钩子。钩子必须在 {@link #script} 之后用 {@link #onCall(Runnable)} 设置，
     * 否则会残留并影响后续测试。
     *
     * @param results 新脚本
     */
    public void script(LlmResult... results) {
        script.clear();
        script.addAll(List.of(results));
        requests.clear();
        last = null;
        onCall(null);
    }

    /** 真实发出过的请求，按顺序；返回快照。 */
    public List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    /** 设置调用前钩子；null 表示清除。 */
    public void onCall(Runnable hook) {
        this.onCall = hook == null ? () -> {
        } : hook;
    }

    @Override
    public LlmResult stream(LlmRequest request, Consumer<StreamEvent> listener,
                            CancellationToken cancellation) {
        requests.add(request);
        onCall.run();

        // 真实实现在每个流式帧检查取消；假客户端也必须检查，否则那条路径测不到。
        if (cancellation.isCancelled()) {
            throw new LlmCallException(LlmCallException.Kind.CANCELLED,
                    "模型调用被用户取消", null);
        }

        LlmResult next = script.poll();
        if (next == null && repeatLast) {
            next = last;
        }
        if (next == null) {
            throw new IllegalStateException(
                    "模型脚本用完了，但循环还在要结果（第 " + requests.size() + " 次调用）");
        }
        last = next;
        // 逐帧推给 listener，与真实流式实现一致；思考在正文之前，与推理模型的实际顺序一致。
        if (next.hasReasoning()) {
            listener.accept(new StreamEvent.ReasoningDelta(next.reasoning()));
        }
        if (!next.text().isEmpty()) {
            listener.accept(new StreamEvent.TextDelta(next.text()));
        }
        next.toolCalls().forEach(call -> listener.accept(new StreamEvent.ToolCallCompleted(call)));
        listener.accept(new StreamEvent.Finished(next.model(), next.finishReason(), next.usage()));
        return next;
    }

    // ------------------------------------------------------------------
    // 结果工厂。常用脚本形状集中在这里；特殊用例仍可直接构造 LlmResult / TokenUsage。
    // ------------------------------------------------------------------

    /** 脚本响应共用的用量；数值仅用于让用量可读，思考项为 0 表示 provider 未报。 */
    private static final TokenUsage USAGE = new TokenUsage(100, 20, 120, 0, 0);

    /** 模型答完了一轮（没有工具调用）。 */
    public static LlmResult answer(String text) {
        return new LlmResult("deepseek-flash", "stop", text, List.of(),
                USAGE);
    }

    /** 带思考过程的一轮 —— 推理模型的常见形态。 */
    public static LlmResult answerWithReasoning(String reasoning, String text) {
        return new LlmResult("deepseek-reasoner", "stop", text, List.of(),
                USAGE, reasoning);
    }

    public static LlmResult toolCall(String id, String name, String argumentsJson) {
        return new LlmResult("deepseek-flash", "tool_calls", "",
                List.of(new ToolCall(id, name, argumentsJson)),
                USAGE);
    }

    /**
     * 只调工具、没有正文、但有思考的一轮。带 tool_calls 的消息必须回传思考，
     * 因此该形状需要独立工厂，不能依赖可选参数的默认值。
     *
     * @param reasoning     思考过程
     * @param id            调用 id
     * @param name          工具名
     * @param argumentsJson 参数 JSON
     * @return 脚本结果
     */
    public static LlmResult toolCallWithReasoning(String reasoning, String id, String name,
                                                  String argumentsJson) {
        return new LlmResult("deepseek-reasoner", "tool_calls", "",
                List.of(new ToolCall(id, name, argumentsJson)),
                USAGE, reasoning);
    }

    /**
     * 一次响应请求多个工具调用；调用数量与可并发性决定分批方式，从而决定事件流形状。
     *
     * @param calls 工具调用
     * @return 脚本结果
     */
    public static LlmResult toolCalls(ToolCall... calls) {
        return new LlmResult("deepseek-flash", "tool_calls", "",
                List.of(calls), USAGE);
    }

    /** 构造一个工具调用的便捷方法。 */
    public static ToolCall call(String id, String name, String argumentsJson) {
        return new ToolCall(id, name, argumentsJson);
    }

    /** 被长度上限截断的一轮：结束原因为 {@code length}。 */
    public static LlmResult truncated(String partialText) {
        return new LlmResult("deepseek-flash", "length", partialText, List.of(),
                USAGE);
    }
}
