package com.codeloom.agent.support;

import com.codeloom.agent.llm.ChatRequest;
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
 * 按脚本返回模型结果的假客户端 —— "模型"在这一整套测试里的唯一替身。
 *
 * <h2>为什么只有一个类，而不是每个测试类各写一份</h2>
 * 三份各自演化的副本，会让"脚本用完之后怎么办"出现三种不同的行为，连抛出的错误信息
 * 也不一样，而没有任何东西在提醒这件事 —— 其中两份曾分别重复第一个和最后一个结果。
 *
 * <p>它做两件事：按脚本给结果，以及**把真实收到的请求记下来** ——
 * 后者常常才是断言的对象（"模型到底看到了什么"）。
 *
 * <h2>三种脚本模式</h2>
 * <ul>
 *   <li>{@link #ScriptedLlm(LlmResult...)}：按序给，给完再要就抛。多出来的调用
 *       通常意味着循环比预期多转了一圈，而那正是测试想发现的事。
 *   <li>{@link #repeating(LlmResult...)}：给完之后**重复最后一个**。
 *       注意是最后一个、不是第一个：常见场景是"先调一次工具触发状态变化，
 *       之后一直给同一个回答"，重复第一个会让前面那步永远重放。
 *   <li>{@link #always(LlmResult)}：无论被问多少次都是它。
 * </ul>
 */
public final class ScriptedLlm implements LlmClient {

    private final Deque<LlmResult> script = new ArrayDeque<>();
    private final List<ChatRequest> requests = new ArrayList<>();

    private boolean repeatLast;
    private LlmResult last;

    /**
     * 每次被调用**之前**跑一下。默认什么都不做。
     *
     * <p>它是为"用户在这一轮跑着的时候按了 Esc"准备的：那件事发生在调用**期间**，
     * 而不是调 {@code send} 之前 —— 挂在调用前才能测到那条路。
     */
    private Runnable onCall = () -> {
    };

    /** 空脚本。配 {@link #script} 用（app 那边的测试是"每个用例先换一份脚本"的写法）。 */
    public ScriptedLlm() {
    }

    public ScriptedLlm(LlmResult... results) {
        script(results);
    }

    /** 脚本用完之后重复最后一个，见类注释。 */
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
     * 换一份新脚本。**挂在调用前的那个钩子也跟着清掉。**
     *
     * <p>钩子和脚本是同一份"这一轮剧本"的两半 —— 后者说"模型答什么"，前者说
     * "调用期间发生什么"。不清的话，某个测试挂上去的、会抛异常的钩子（"模型调用失败"
     * 那条路要的就是它）会留给后面每一个测试，让**后面所有**轮次跟着失败。
     *
     * <p>所以约定是：要挂钩子的测试在 {@link #script} **之后**调 {@link #onCall}。
     */
    public void script(LlmResult... results) {
        script.clear();
        script.addAll(List.of(results));
        requests.clear();
        last = null;
        onCall(null);
    }

    /** 真实发出去过的请求，按顺序。返回的是快照，调用方改不了它。 */
    public List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    /** 见 {@link #onCall} 那段。传 null 等于取消挂钩子。 */
    public void onCall(Runnable hook) {
        this.onCall = hook == null ? () -> {
        } : hook;
    }

    @Override
    public LlmResult stream(ChatRequest request, Consumer<StreamEvent> listener,
                            CancellationToken cancellation) {
        requests.add(request);
        onCall.run();

        // **取消要看一眼** —— 真实实现在流式读取的每一步都在看（见
        // OpenAiStreamAccumulator），"用户按 Esc"正是从那儿冒出来的：
        // 它不是一个异常的返回值，而是一路上都在检查的那件事。
        // 假客户端不看的话，那条路根本测不到
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
        // 把结果**逐帧推给 listener**，而不是只返回它 —— 真实实现在流式过程中
        // 就会回调，而循环对易失事件的广播正是挂在这个回调上的
        //
        // 思考排在正文之前：真实流里推理模型也是先想再答（这条顺序对下游的
        // 分区渲染有影响，所以 fixture 要跟真实的一致）
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
    // 结果工厂。写在这里，{@code LlmResult} / {@code TokenUsage} 的构造签名变了
    // 只有这一处要改
    // ------------------------------------------------------------------

    /**
     * 这些脚本响应共用的用量。
     *
     * <p>数字是随手定的，这些测试要的是"有一次能读的用量"，不是那个具体的值。
     * **思考那一项是 0**：脚本响应压根没有 usage 那一层，它就是"provider 没报"的样子。
     */
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
     * 只调工具、**一个字不说**、但想过的一轮。
     *
     * <p>这是推理模型在思维链模式下最常见的形状，也是必须单独有一条的：这种消息没有正文，
     * 而它**带 tool_calls** —— 协议要求把当时的思考一起带回去，缺了它下一次请求就是 400。
     *
     * <p>单独一个工厂而不是给 {@link #toolCall} 加参数：加一个可选参数的话，
     * 所有已有的测试都还在用默认值，而"默认值"永远走不到这条路上 ——
     * 那正是当初漏掉它的方式。
     */
    public static LlmResult toolCallWithReasoning(String reasoning, String id, String name,
                                                  String argumentsJson) {
        return new LlmResult("deepseek-reasoner", "tool_calls", "",
                List.of(new ToolCall(id, name, argumentsJson)),
                USAGE, reasoning);
    }

    /**
     * 一次响应里要求**多个**工具调用。
     *
     * <p>和 {@link #toolCall} 分开是有原因的："模型一次要了几个工具"不是细节 ——
     * 循环会把其中**连续的只读调用合成一批并发跑**，而一个写调用会把批切开。
     * 换句话说，它决定了事件流的形状。
     */
    public static LlmResult toolCalls(ToolCall... calls) {
        return new LlmResult("deepseek-flash", "tool_calls", "",
                List.of(calls), USAGE);
    }

    /** 造一个调用，省得每个测试都写一遍 {@code new ToolCall}(...)。 */
    public static ToolCall call(String id, String name, String argumentsJson) {
        return new ToolCall(id, name, argumentsJson);
    }

    /** 被长度上限截断的一轮：{@code finishReason} 是 {@code length}，正文只写了一半。 */
    public static LlmResult truncated(String partialText) {
        return new LlmResult("deepseek-flash", "length", partialText, List.of(),
                USAGE);
    }
}
