package com.codeloom.agent.loop;

import com.codeloom.agent.context.ContextAssembler;
import com.codeloom.agent.llm.ChatMessage;
import com.codeloom.agent.llm.ChatRequest;
import com.codeloom.agent.llm.LlmCallException;
import com.codeloom.agent.llm.LlmResult;
import com.codeloom.agent.llm.StreamEvent;
import com.codeloom.agent.llm.TokenUsage;
import com.codeloom.agent.llm.ToolCall;
import com.codeloom.agent.tool.Tool;
import com.codeloom.agent.tool.ToolContext;
import com.codeloom.agent.tool.ToolOutcome;
import com.codeloom.agent.tool.ReadLedger;
import com.codeloom.agent.tool.ToolRegistry;
import com.codeloom.agent.tool.ToolSurface;
import com.codeloom.agent.tool.WorkspacePathGuard;
import com.codeloom.domain.event.AssistantMessage;
import com.codeloom.domain.event.LlmRetryScheduled;
import com.codeloom.domain.event.PersistentEvent;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.PlatformInstruction;
import com.codeloom.domain.event.ToolApprovalRequested;
import com.codeloom.domain.event.ToolCallRequested;
import com.codeloom.domain.event.ToolCancelled;
import com.codeloom.domain.event.ToolResult;
import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.Workspace;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

/**
 * 一轮 agent 执行 —— ReAct 循环：让模型说话、执行它要的工具、把结果喂回去，直到它不再要工具。
 *
 * <h2>这个类不碰存储</h2>
 * 它拿事件流当输入、把新事件当输出。落库、更新会话状态、广播 SSE 都是调用方的事。
 * 于是它成了一个**纯函数**：{@code (历史, 配置) → 新事件}。最容易长 bug 的那部分代码，
 * 恰好是最好测的那部分 —— 塞个假客户端就能跑完整条路径。
 *
 * <h2>三道防自旋</h2>
 * <ol>
 *   <li><b>轮次上限</b>：模型可能陷进"读文件→改文件→读文件"的死循环，到上限就停</li>
 *   <li><b>token 预算</b>：超限时**注入收尾指令让模型自己收束**，而不是掐断连接 ——
 *       掐断会留下半个 diff 和半句话</li>
 *   <li><b>取消信号</b>：用户按 Esc，在工具边界停下</li>
 * </ol>
 *
 * <h2>两条输出通道，别混</h2>
 * <ul>
 *   <li>{@link #run(TurnInput, ToLongFunction)} 的 {@code onEvent} —— **持久事件**，产生的那一刻
 *       就交给调用方：它立刻落库并广播，再把这条事件真实的 seq 返回。</li>
 *   <li>构造器里的 {@code liveListener} —— **流式增量**（{@code StreamEvent}），逐 token 的
 *       文本片段。它不落库，只是让正在看的人看到"模型正在打字"。</li>
 * </ul>
 */
public final class AgentTurn {

    /** 一轮之内最多让模型调多少轮工具。超过就是它在打转，停下来交给人。 */
    private static final int MAX_TOOL_ROUNDS = 25;

    /**
     * 一次模型调用最多试几次（含第一次）。
     *
     * <p>**3 是个判断，不是算出来的**：限流和抖动通常一两次就过去，而每次退避都在让用户干等
     * （500ms → 1s → 2s）；第三次还不行，多半意味着"这次就是不行" ——
     * 那时候把它报出去，比在这里耗着有用。
     *
     * <p>（Claude Code 是 10 次，deepseek-harness 把它做成每个 provider 各自配。
     * 我们小得多，因为我们是**服务端**：一次调用背后有一个坐在界面前等的人，
     * 而那个 10 是给本地 CLI 的无人值守场景调的。）
     */
    private static final int MAX_ATTEMPTS = 3;

    /** 第一次退避的基数。之后翻倍，封顶见下。 */
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);

    /** 退避的封顶。服务商说的等待时间超过它就不试了（见 {@link #delayBeforeRetry}）。 */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(8);

    /** 抖动比例：正负这个幅度。同时重试的几条会话不该踩着同一个节拍。 */
    private static final double JITTER_RATIO = 0.25;

    /** 等退避时多久看一眼取消信号。和 {@code ProcessRunner} 等子进程用的是同一个数。 */
    private static final long RETRY_POLL_MILLIS = 100;

    /**
     * 一批（同一条消息里连续的那些并发安全调用）**同时**最多跑几个。
     *
     * <p>超出的排队等，不是拒绝 —— 顺序和结果都不受影响（结果本来就按提交顺序取回）。
     *
     * <p>取 8 而不是 Claude Code 那个 10：我们的读类工具里有 {@code grep} / {@code glob}，
     * 它们是**扫整棵工作区**的递归 IO，比"读一个文件"重。这个数不是测出来的，
     * 是个工程判断 —— 它管的是最坏情况（模型一条消息里塞几十个调用），
     * 而正常一条消息就三五条，到不了这儿。
     *
     * <p>包级可见是留给测试的（同 {@code LocalCommandExecutor.clamp}）：
     * 要验"上限真的生效"，测试得知道上限是多少。
     */
    static final int MAX_PARALLEL_TOOLS = 8;

    /** 验证失败后最多让模型自修几次。超过就停 —— 说明模型能力不够或任务本身有歧义。 */
    private static final int MAX_VERIFY_ATTEMPTS = 3;

    /**
     * 一轮之内送给模型的工具输出总量上限（字符）。
     *
     * <p>每个工具**各自**已经有上限（run_command 十万字符、read/grep 也各自截断），
     * 但那是单个的。一轮最多 {@value #MAX_TOOL_ROUNDS} 次工具往返、每次可能好几个调用 ——
     * 累加起来足够撑爆任何上下文窗口，而且是在**压缩察觉到之前**：
     * 压缩要等到下一轮开始才跑，救不了正在撑爆的这一轮。
     *
     * <p>所以这道总量上限管的是"这一轮别把自己撑死"。
     */
    private static final int MAX_TOOL_OUTPUT_CHARS_PER_TURN = 200_000;

    /** 越过上面这个总量上限之后，新来的结果一共只留这么长（头尾各一半，见下面的留法）。 */
    private static final int CAPPED_TOOL_OUTPUT_CHARS = 2_000;

    /**
     * 落盘的工具输出留多久。
     *
     * <p>它们不是缓存，是**证据** —— 一条跑了好几天的会话，回溯到某一轮的落盘文件时，
     * 那条路径可能还被 transcript 引用着。所以保留期按"天"给，而不是用完就删。
     */
    private static final Duration TOOL_OUTPUT_RETENTION = Duration.ofDays(30);

    /**
     * 思考过程最长留这么多字符。**落库和实时推送共用这一个上限。**
     *
     * <p>它是**展示**用途（人要能看见模型想了什么），而事件表要长期保存 ——
     * 推理模型的思考可能比正文长好几倍，原样留下会让每一条消息都很重。
     * 超长时保**开头**：思考是从方向开始展开的，开头那段最能说明它在想什么。
     *
     * <p>实时通道（{@code TurnExecutor} 推的 {@code ReasoningDelta}）用的是**同一个上限**，
     * 否则会出现"正在打字时看到三万字、刷新后只剩一万六"的前后不一致。
     * 所以这个常量是 public 的：它是两处共同的事实，不是某一处的私有细节。
     */
    public static final int MAX_REASONING_CHARS = 16_000;

    /** 太长的思考过程截断后再落库，见 {@link #MAX_REASONING_CHARS}。 */
    private static String abbreviateReasoning(String reasoning) {
        if (reasoning == null || reasoning.length() <= MAX_REASONING_CHARS) {
            return reasoning;
        }
        return reasoning.substring(0, MAX_REASONING_CHARS)
                + "\n…（思考过程过长，已截断）";
    }

    /**
     * 输出被长度上限截断后，最多叫它重来几次。
     *
     * <p>和 {@link #MAX_VERIFY_ATTEMPTS} 同一个思路：可恢复的失败先给机会自纠，
     * 用尽次数才认输。区别是这里靠**注入一句"拆小点"**而不是验证结果。
     */
    private static final int MAX_TRUNCATION_RETRIES = 2;

    /**
     * 输出被截断时注入的话。
     *
     * <p>截断几乎总是"一次想干太多"（尤其它正打算一次性写出一个大文件）。
     * 所以要说的不是"继续写"，而是**把步子迈小** —— 继续写只会再次触发同一个截断。
     */
    private static final String TRUNCATED_INSTRUCTION =
            "你上一次的输出因为长度上限被截断了（不是你写完了）。"
                    + "请把刚才那一步**拆成更小的几步**重做：比如写文件时先写一小段，"
                    + "剩下的用 edit_file 分几次补上。不要道歉，也不要复述已经说过的内容。";

    /**
     * 解析工具参数用的 mapper。**静态共享一个就够** ——
     * 它是无状态的（配好之后读写都线程安全），而构造它要做模块扫描与序列化器注册，
     * 比它承担的 {@code readTree} 贵得多。这个类每轮都会被 new 一个，
     * 所以写成实例字段等于把这个构造成本按轮付。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Consumer<StreamEvent> liveListener;

    /**
     * 上下文装不下时找谁修。见 {@link ContextRepair}。
     *
     * <p>默认是"不修" —— 那正是测试、以及任何不关心恢复的调用方想要的形状：
     * 那两种情况下的行为就是"如实失败"，而**如实失败是这套东西的兜底**。
     */
    private final ContextRepair contextRepair;

    public AgentTurn() {
        this(event -> {
        });
    }

    /**
     * @param liveListener 实时事件回调 —— 接了它就等于接了 SSE。
     *                     测试和不关心中间过程时传空实现
     */
    public AgentTurn(Consumer<StreamEvent> liveListener) {
        this(liveListener, NO_REPAIR);
    }

    /**
     * @param contextRepair 上下文装不下时的补救。它和 {@link #reads} 是同一条理由：
     *                      只属于这条会话，不进 {@code TurnInput}（为什么见那个字段）
     */
    public AgentTurn(Consumer<StreamEvent> liveListener, ContextRepair contextRepair) {
        this.liveListener = Objects.requireNonNull(liveListener, "liveListener");
        this.contextRepair = Objects.requireNonNull(contextRepair, "contextRepair");
    }

    /** 不修的那种：上下文装不下就直接失败，provider 那句话原样报出去。 */
    private static final ContextRepair NO_REPAIR = () -> false;

    /**
     * 这条会话里读过哪些文件。见 {@link ReadLedger} —— 判断能不能覆盖已读文件时用它。
     *
     * <p>挂在这儿而不是塞进 {@code TurnInput}：**一条会话一个 {@code AgentTurn}**
     *（{@code TurnExecutor} 每轮 new 一个），所以这个字段天然就是"这条会话"的范围；
     * 而 {@code TurnInput} 是个 record，加一个组件要改十几处构造点。
     */
    private final ReadLedger reads = new ReadLedger();

    /**
     * 扫掉落盘目录里超过保留期的文件，返回删掉了几个。
     *
     * <h2>为什么挂在"写入"这一步，而不是建工作区那一步</h2>
     * 工作区刚建出来时这个目录必然是空的（它跟着 worktree 一起生、一起灭），扫了没东西可扫；
     * 而文件只在**这一次落盘**时产生 —— 所以"写之前顺手扫一遍"是唯一真正有事可做的那一步，
     * 而且它天然只在用得着这个功能的会话里跑。
     *
     * <p>失败一律吞掉：这只是清理，不是功能。删不掉的表现只是这棵树多占几 KB，
     * 为它让一轮执行失败就本末倒置了（{@code LocalWorkspaceManager#excludeToolOutput}
     * 那边是同一个取舍）。
     *
     * <p>收 {@code cutoff} 而不是自己算 {@code now()}：那条"30 天"的时间线只有真跑一遍
     * 才验得出来，而真跑要等到 30 天后 —— 把判据拿出来，它今天就能被断言（见 {@code AgentTurnTest}）
     */
    static int sweepOldSpills(Path dir, Instant cutoff) {
        int removed = 0;
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                try {
                    if (Files.getLastModifiedTime(entry).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(entry);
                        removed++;
                    }
                } catch (IOException ignored) {
                    // 单个文件删不掉（被别的进程占着、权限不对）：跳过它，别影响这一轮
                }
            }
        } catch (IOException ignored) {
            // 目录压根读不了：同上。下一轮落盘时会再试一次
        }
        return removed;
    }

    /**
     * 把**这段对话此前**读过的文件补进账里。
     *
     * <p>范围是整段对话而不是这一轮：模型完全可能第一轮把文件读了、第二轮才决定覆盖它
     *（Claude Code 的说法也是"read in the current conversation"）。
     * 不补的话它得为了覆盖再读一遍 —— 那不至于出事，但白花一次调用。
     *
     * <p>认不出来的调用就当没读过：那只会让它多读一次，**不会让它写错东西**。
     */
    private void seedReadLedger(TurnInput input) {
        // 读过哪些文件是**派生事实**：它跟着投影一路走（见 ContextAssembler.Projection#readPaths），
        // 不是每轮把整条事件流重扫一遍
        for (String path : input.projection().readPaths()) {
            try {
                // `markReconstructed` 而不是 `mark`：投影里只有一组路径，
                // **没有版本**（那要读事件的正文才拿得到）。见 ReadLedger 类注释"重启之后"
                reads.markReconstructed(WorkspacePathGuard.resolve(input.worktree(), path));
            } catch (RuntimeException e) {
                // 单个路径解析不了只跳过它：跳过一次，不跳过错
            }
        }
    }

    /** 不关心中间过程时用（单测、批处理）：事件最终都在 {@code TurnOutcome} 里。 */
    public TurnOutcome run(TurnInput input) {
        // 没有 appender：事件不落库，本轮用本地序号（见 Workbench.append）
        return run(input, null);
    }

    /**
     * 跑一轮，**每条持久事件在它产生的那一刻**回调给 {@code onEvent}。
     *
     * <h2>为什么不能等一轮跑完再一次性交出去</h2>
     * <ol>
     *   <li><b>崩溃恢复依赖它。</b> {@code ToolInterrupted} 的意义是"进程重启后把上次
     *       执行到一半的调用补写成事件，防止重新执行"。而它要能补写，前提是
     *       {@code ToolCallRequested} 在工具**开始执行之前**就已经落库了。
     *       攒到最后一起写的话，进程在工具中途被杀 → 事件流里什么都没有，
     *       而工作区的文件改动是已经发生了的 —— 那正是这个设计要防的场景。
     *   <li><b>实时观战依赖它。</b> 模型那个流式文本走 {@code liveListener}，
     *       但工具调用和它的结果不是流式的。一个跑 30 秒构建的工具调用，
     *       如果事件要等整轮结束才出去，对方在这 30 秒里看不到任何东西。
     * </ol>
     *
     * <p>调用方应当在回调里**立刻落库并广播**（带 fencing token 的那次追加），
     * 而不是攒起来。回调抛异常（比如 fencing token 已失效）会中止整轮 —— 这是对的，
     * 那时候本轮剩下的产出已经不可信了。
     *
     * <p>回调顺序与事件产生的顺序一致；{@code TurnOutcome.newEvents()} 是同一批事件的
     * 事后记录，一个不多一个不少。
     *
     * <h2>它必须**把真实 seq 还回来**</h2>
     * 桩（appender）写库之后返回那条事件的 seq。循环拿它当投影的坐标 —— 假的坐标会让
     * 下一轮的增量补读判错，而那种错不报错，只是消息重复或凭空少一截（见
     * {@code Workbench.append}）。{@code null} 表示不落库（单测、批处理），
     * 那时候用本地自增号。
     */
    public TurnOutcome run(TurnInput input, ToLongFunction<PersistentEvent> onEvent) {
        Objects.requireNonNull(input, "input");
        requireToolSupport(input);
        seedReadLedger(input);

        Workbench bench = new Workbench(input, onEvent);
        TokenUsage usage = TokenUsage.UNKNOWN;
        String model = input.model().modelId();
        String finishReason = null;
        boolean wrapUpRequested = false;
        int verifyAttempt = 0;
        int truncationRetries = 0;

        // ★ 上一次批准的那次调用，在**进模型循环之前**补跑掉 —— 见 settleApprovedCall
        settleApprovedCall(input, bench);

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            if (input.cancellation().isCancelled()) {
                return bench.outcome(model, finishReason, usage, TurnOutcome.Status.CANCELLED);
            }

            LlmResult result;
            try {
                result = callModel(input, bench, wrapUpRequested);
            } catch (LlmCallException e) {
                result = retryAfterOverflow(input, bench, wrapUpRequested, e);
            }
            model = result.model() == null ? model : result.model();
            finishReason = result.finishReason();
            // 两次都记：**累计的**是这一轮花了多少（账单），**最后一次的**是
            // 收尾时上下文有多大（见 TurnOutcome#lastCallUsage）。别再想着从一个推另一个
            usage = usage.plus(result.usage());
            bench.rememberCall(result.usage());

            // **只调工具、一个字不说**的那一轮也要落库 —— 落的是它的思考。
            // 这一轮正是带 tool_calls 的那一条，而 OpenAI 兼容阵营的推理模型
            // （DeepSeek 的思维链模式）要求它必须把当时的 reasoning_content 带回来：
            // 少存了这条消息，下一次请求发出去就是一个 400（见 ChatMessage#assistant）。
            if (!result.text().isEmpty() || result.hasReasoning()) {
                // 带上**这一刻**的模型名：一轮里模型可能被换过（用户切了、或者降级了），
                // 而这一条消息要说得清是谁写的
                bench.append(new AssistantMessage(result.text(), model,
                        abbreviateReasoning(result.reasoning())));
            }

            if (result.isTruncated()) {
                // 半截输出不能当正常结束 —— 后续推理会建立在残缺内容上。
                // 但也不必就此判死：叫它拆小了重来一次，比整轮失败好得多
                // （尤其是它正打算写一个大文件的时候）
                if (++truncationRetries > MAX_TRUNCATION_RETRIES) {
                    return bench.outcome(model, finishReason, usage, TurnOutcome.Status.TRUNCATED);
                }
                // 和验证失败一样，这是**平台说的话**，用独立事件类型落下 ——
                // 审计时要能和"用户说的"分开
                bench.append(new PlatformInstruction(TRUNCATED_INSTRUCTION, "output-truncated"));
                continue;
            }

            if (!result.hasToolCalls()) {
                // 模型说它做完了。但如果这轮动过工作区，**平台自己再验一次** ——
                // 让模型自己决定要不要验证，等于把"代码可不可信"交给它自己判断。
                if (!input.verification().isEnabled() || !bench.hasMutated()) {
                    return bench.outcome(model, finishReason, usage, TurnOutcome.Status.COMPLETED);
                }

                verifyAttempt++;
                VerificationOutcome verification = verify(input, bench, verifyAttempt);
                if (verification.passed()) {
                    return bench.outcome(model, finishReason, usage, TurnOutcome.Status.COMPLETED);
                }
                if (verifyAttempt >= MAX_VERIFY_ATTEMPTS) {
                    // 自修次数用尽：不再注入。继续喂下去只会让模型无休止空转，
                    // 而以 VERIFICATION_FAILED 收场才是诚实的 —— 代码确实还没过验证。
                    return bench.outcome(model, finishReason, usage,
                            TurnOutcome.Status.VERIFICATION_FAILED);
                }
                // 注入失败让它自修。两点：
                // 1) 这是**事件**，要落库 —— 不落库的话崩溃重放时组装出的上下文和当时不一致
                // 2) 用 PlatformInstruction 而不是 UserMessage —— 它不是用户说的。
                //    混用会让审计流里分不清"用户说的"和"平台说的"，
                //    而回滚恰恰是按 turn 对齐代码与对话的
                bench.append(new PlatformInstruction(verification.failurePrompt(), "verification-failed"));
                continue;
            }

            if (wrapUpRequested) {
                // 已经叫它收尾了，它还要调工具 —— 说明它没听，这时必须硬停
                return bench.outcome(model, finishReason, usage, TurnOutcome.Status.BUDGET_EXHAUSTED);
            }

            if (!executeTools(input, bench, result.toolCalls())) {
                return bench.outcome(model, finishReason, usage, TurnOutcome.Status.CANCELLED);
            }
            if (bench.approvalPending()) {
                return bench.outcome(model, finishReason, usage,
                        TurnOutcome.Status.AWAITING_APPROVAL);
            }

            // 预算只看本轮 —— **这是有意的，不是缺口**：BYOK 下钱是用户自己的，
            // 没有"平台累计超限"这个主体。见 TokenBudget 的类注释
            if (input.budget().shouldWrapUp(usage)) {
                wrapUpRequested = true;
            }
        }

        return bench.outcome(model, finishReason, usage, TurnOutcome.Status.MAX_ITERATIONS);
    }

    /**
     * 能力门控：**不支持工具调用的模型，配上工具要在开始前就失败**。
     *
     * <p>不门控的话，这类模型会在每一次工具往返上安静地失败或忽略工具，
     * 最后表现成"agent 转了半天什么也没干"，排查起来毫无头绪。
     *
     * <p>这是 {@code ModelCapabilities} 这一层存在的意义：用户的模型可以自由配置，
     * 而各家能力差异很大 —— 差异必须收敛成一份显式描述、由策略读它，
     * 而不是散落成各处对"当前用的是哪个模型"的硬编码判断。
     */
    private static void requireToolSupport(TurnInput input) {
        if (input.tools().definitions().isEmpty() || input.capabilities().supportsTools()) {
            return;
        }
        throw new LlmCallException(LlmCallException.Kind.INVALID_REQUEST,
                "模型 " + input.model().modelId() + " 不支持工具调用，无法执行需要动手的任务。"
                        + "请换一个支持工具调用的模型，或去掉工具配置。", null);
    }

    // ------------------------------------------------------------------

    private LlmResult callModel(TurnInput input, Workbench bench, boolean wrapUpRequested) {
        // 装进自己的 list（下一步就发给模型，不需要不可变兜底）
        List<ChatMessage> messages = new ArrayList<>();
        bench.assembleMessages(messages);
        if (wrapUpRequested) {
            messages.add(ChatMessage.user(TokenBudget.WRAP_UP_INSTRUCTION));
        }

        ChatRequest request = new ChatRequest(
                bench.currentModelId(),
                messages,
                input.tools().definitions(),
                input.capabilities().maxOutputTokens());

        // **重试在这一层，不在 provider 客户端里。**
        //
        // 客户端拿不到"值不值得再来一次"要看的上下文（第几轮了、用户还在不在等、
        // 这一轮烧了多少），也没有事件通道 —— 藏在下面的重试界面上看不见。
        // 这一层有事件流（能说"我在重试"）、有取消信号（用户按 Esc 不用等完退避），
        // 也知道这是第几次尝试。
        for (int attempt = 1; ; attempt++) {
            try {
                return input.client().stream(request, liveListener, input.cancellation());
            } catch (LlmCallException e) {
                if (!e.retryable() || attempt == MAX_ATTEMPTS) {
                    throw e;      // 不该重试，或者试完了 —— 把最后那个失败报出去
                }
                Optional<Duration> delay = delayBeforeRetry(attempt, e);
                if (delay.isEmpty()) {
                    // 服务商给的等待时间比我们愿意等的还久 —— 见 delayBeforeRetry
                    throw e;
                }
                // **先落事件，再睡。** 那几秒的静默必须看得见，理由见 LlmRetryScheduled
                bench.append(new LlmRetryScheduled(attempt, MAX_ATTEMPTS,
                        delay.get().toMillis(), e.shortReason()));
                sleepBeforeRetry(delay.get(), input.cancellation());
            }
        }
    }

    /**
     * 这次失败之后等多久再试；**空 = 别试了**。
     *
     * <p>服务商给了 {@code Retry-After} 就听它的 —— 它说 5 秒就等 5 秒，
     * 别用我们的指数退避去猜它想要多久。
     *
     * <p><b>但它要是说得太久，就不试了。</b> 提前重试等于违反它刚给的指示，
     * 多半换来又一个 429；而老老实实等下去，用户还在界面前坐着。
     * deepseek-harness 在这一处的选择一样：超过上限的延迟**委派**，而不是提前重试。
     * （Claude Code 反而会一直等下去 —— 我们跟着更谨慎的那一个。）
     *
     * @return 空 = 这一轮的这次调用到此为止，把原始失败报出去
     */
    private static Optional<Duration> delayBeforeRetry(int attempt, LlmCallException failure) {
        Optional<Long> told = failure.retryAfterMs();
        if (told.isPresent()) {
            long millis = told.get();
            return millis > MAX_BACKOFF.toMillis()
                    ? Optional.empty()
                    : Optional.of(Duration.ofMillis(millis));
        }
        long base = Math.min(INITIAL_BACKOFF.toMillis() << (attempt - 1), MAX_BACKOFF.toMillis());
        // 抖动：同时有好几条会话在重试时，不该踩着同一个节拍一起撞上去
        long jitter = (long) (base * JITTER_RATIO * ThreadLocalRandom.current().nextDouble());
        return Optional.of(Duration.ofMillis(base + jitter));
    }

    /**
     * 分段睡，**每段看一眼取消信号**。
     *
     * <p>粒度是 {@value #RETRY_POLL_MILLIS} 毫秒 —— 整段睡的话，用户按了 Esc 要等退避
     * 睡完（最长 8 秒）才发现，而界面上那几秒没有任何反馈。和 {@code ProcessRunner}
     * 等子进程用的是同一个数、同一个理由。
     */
    private static void sleepBeforeRetry(Duration delay, CancellationToken cancellation) {
        long deadline = System.nanoTime() + delay.toNanos();
        while (true) {
            if (cancellation.isCancelled()) {
                // 报成 CANCELLED 而不是 NETWORK：上层据此走"用户取消了"那条收尾，
                // 而不是把它当一次失败写进审计
                throw new LlmCallException(LlmCallException.Kind.CANCELLED,
                        "等待重试时被用户取消", null);
            }
            long remaining = (deadline - System.nanoTime()) / 1_000_000;
            if (remaining <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.min(remaining, RETRY_POLL_MILLIS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmCallException(LlmCallException.Kind.NETWORK,
                        "等待重试时被中断", e);
            }
        }
    }

    /**
     * provider 说"上下文装不下" → 修一次 → **再发一次**。见 {@link ContextRepair}。
     *
     * <h2>为什么最多一次</h2>
     * 这个方法是**直接**再调一次 {@code callModel}，不走循环 —— 所以第二次失败会照常
     * 抛出去，不存在"修了再修"的螺旋。修一次是够的：压缩是"把前缀换成摘要"，
     * 那一下收得最多；一次还装不下的话，装不下的多半是**尾巴本身**
     *（最近那些还没轮到压的消息），那不是压缩能修的，再压几次也一样。
     *
     * <p>这个限制和 Claude Code、deepseek-harness 一致：它们那边也是单发 —— 各自还都记着一个"补救走过
     * 没有"的标记，并且都写明了"最多一次"的理由。
     *
     * <h2>修不动就报原始那个错误</h2>
     * 不编一个"压缩失败"：provvder 说的那句话才是真相，而且它可能已经指出了真实原因
     *（比如"你的 max_tokens 设太大了"这种和上下文长度一起算的失败）。
     */
    private LlmResult retryAfterOverflow(TurnInput input, Workbench bench,
                                         boolean wrapUpRequested, LlmCallException failure) {
        if (!failure.isContextExceeded() || !contextRepair.afterContextOverflow()) {
            // 不是上下文的问题，或者修了但模型可见的状态没变（见 ContextRepair）——
            // 两种都不该重试，把原来那个错误原样抛出去
            throw failure;
        }
        return callModel(input, bench, wrapUpRequested);
    }

    /**
     * 用户刚批过的那次调用，**把它真正跑掉**。
     *
     * <h2>为什么必须补跑</h2>
     * 需要审批的调用走的是"整轮停下"这条路：{@link #executeTools} 落一条
     * {@code ToolApprovalRequested} 就返回，**不落 ToolResult**（那时它确实还没跑）。
     * 答复到了之后，{@code ApprovalService} 落一条 {@code ToolApprovalResolved} 再 resume ——
     * 但**没有任何一步去执行它**。于是"批准"的全部效果，就只是让模型在上下文里看到一句
     * 「（用户批准了这次调用，可以执行）」，命令一个字节都没跑 —— 表现为反复拿
     * {@code pwd} / {@code ls} / {@code touch} 试探都拿不到回显，模型甚至误以为这个环境的
     * run_command 是坏的。
     *
     * <h2>为什么放在这里，而不是放进入口那一层</h2>
     * 执行需要工作区、工具注册表、命令执行器、取消信号、预算 —— 它们全都已经在
     * {@link TurnInput} 里，而且执行本来就归这个类管。放进 {@code ApprovalService}
     * 等于把那套装配从头再写一份，两份迟早不一致。
     *
     * <h2>什么时候才补跑</h2>
     * 只有那条答复是**这件事的最后一步**时才补，判据见 {@link #pendingApprovedCall}。
     * 它同时挡住了另一种情况：一条新用户消息进来时，那条消息已经落在答复之后了 ——
     * 于是不会去补跑上一轮遗留的命令。（少了这一条，往一个失败过的会话里再发一句话，
     * 会把它之前批准过的 {@code rm -rf} 重新执行一遍。）
     */
    private void settleApprovedCall(TurnInput input, Workbench bench) {
        ToolCall call = pendingApprovedCall(input).orElse(null);
        if (call == null) {
            return;
        }
        // **不再落一条 ToolCallRequested** —— 它上次就落过了。
        // 重复的那条会让投影把它读成两次不同的调用，而模型只请求过一次
        // 判据按**这一条命令**放行 —— 它已经批过了，而"批过"这件事不在白名单里，
        // 拿着原判据去跑会被第二次拦下，于是又挂起、又等人点，永远跑不掉
        TurnInput granted = input.withApprovalGrantedFor(commandOf(input, call));
        ToolOutcome outcome = bench.withinTurnBudget(call, executeOne(granted, call));
        if (outcome.needsApproval()) {
            // 放行了还要求审批，说明这条命令和免审批判据对不上（正常到不了这里）。
            // 报成一次失败，而不是**再挂一次** —— 再挂一次就是无限循环等人点批准
            recordOutcome(bench, call, ToolOutcome.failed(
                    "这条命令已经批准过，但执行时仍被判定需要审批。请换一种写法。"));
            return;
        }
        recordOutcome(bench, call, outcome);
    }

    /**
     * 从一次调用的参数里取那行命令 —— **靠工具自己声明的形状**，不靠这里猜。
     *
     * <p>判据是工具声明的 {@link ToolSurface.Shape#EXECUTE} 和它声明的主语键，
     * 而不是写死某个参数名：参数名一旦对不上，被批准的命令和补跑的那条就不是同一条 ——
     * 安全边界上的走样。
     */
    private String commandOf(TurnInput input, ToolCall call) {
        JsonNode arguments;
        try {
            arguments = MAPPER.readTree(call.argumentsJson());
        } catch (JsonProcessingException e) {
            // 参数不是合法 JSON：执行那一步会自己报这个错，这里不抢着说
            return null;
        }
        return input.tools().surfaceOf(call.name())
                .filter(surface -> surface.shape() == ToolSurface.Shape.EXECUTE)
                .flatMap(surface -> surface.subjectIn(arguments))
                .orElse(null);
    }

    /**
     * 找出"已经批准了、但还没有结局"的那次调用；没有就是 empty。
     *
     * <p>两条判据，缺一不可：
     * <ol>
     *   <li><b>那条答复是最后一步。</b>从历史尾部往前走，跳过不改变对话形状的事件
     *       （状态迁移、用量、checkpoint、压缩），第一件事必须是
     *       {@code ToolApprovalResolved(approved = true)}。</li>
     *   <li><b>它确实没有结局。</b>整段历史里没有对应的
     *       {@code ToolResult} / {@code ToolCancelled} / {@code ToolInterrupted}。</li>
     * </ol>
     * 第二条让它**幂等**：补跑过一次，结果就落库了，下一轮的尾部不再是那条答复。
     */
    private static Optional<ToolCall> pendingApprovedCall(TurnInput input) {
        // 判据见 ContextAssembler.Projection#pendingApprovedCall 上那段
        return input.projection().pendingApprovedCall();
    }

    /** @return false 表示中途被取消 */
    private boolean executeTools(TurnInput input, Workbench bench, List<ToolCall> calls) {
        for (List<ToolCall> batch : batchesOf(calls, input.tools())) {
            if (input.cancellation().isCancelled()) {
                return false;
            }
            // **先把这一批的请求全部落库，再开始跑** —— 顺序不能反。崩溃恢复靠的就是
            // "工具开始跑之前 ToolCallRequested 已经在库里"；等并发跑完再补写请求，
            // 那段窗口里进程被杀就什么都查不到了
            for (ToolCall call : batch) {
                bench.append(new ToolCallRequested(call.id(), call.name(), call.argumentsJson()));
            }

            List<ToolOutcome> outcomes = runBatch(input, batch);

            for (int i = 0; i < batch.size(); i++) {
                ToolCall call = batch.get(i);
                // 按"这一轮总量"限制处理，见 withinTurnBudget
                ToolOutcome outcome = bench.withinTurnBudget(call, outcomes.get(i));

                if (outcome.needsApproval()) {
                    // 落一条"这次调用要人批"，然后**整轮就此停下**。
                    // 不落 ToolResult —— 那是"执行完了"的事实，而它压根还没跑；
                    // 它的"结果"要等用户答复时那条 ToolApprovalResolved。
                    //
                    // 本批里排在它后面的调用也不再执行：这一轮已经不可信了，
                    // 让模型在拿到答复之后重新决定更安全。那些已经落库的
                    // ToolCallRequested 由投影的配对兜底补上说明
                    //
                    // 理由取自 outcome 的正文：等着人批这个结局**没有别的内容** ——
                    // 这一轮不会落 ToolResult，模型也看不到这句话，它唯一的作用就是进这条事件
                    bench.append(new ToolApprovalRequested(call.id(), outcome.output()));
                    bench.markApprovalPending();
                    return true;
                }

                recordOutcome(bench, call, outcome);
            }
        }
        return true;
    }

    /**
     * 把一次执行的结局落成事件。
     *
     * <p>抽出来是为了让**两条路共用同一套写法**：正常执行的那条，和"上次批准了、
     * 现在补跑"的那条（见 {@link #settleApprovedCall}）。各写一遍的话，
     * "用户取消要落成 ToolCancelled、不能落成失败的 ToolResult"这条迟早会在其中一处走样 ——
     * 而那正是崩溃重放时会让模型重试用户刚刚取消掉的操作的那条。
     */
    private static void recordOutcome(Workbench bench, ToolCall call, ToolOutcome outcome) {
        if (outcome.isCancelled()) {
            // 用户取消落成【独立事件】，不是 ToolResult(success=false)。
            // 后者在崩溃重放后会读成"某个工具失败了"，模型会理直气壮地
            // 重试用户刚刚取消掉的操作；审计上也分不清"构建挂了"和"用户不想跑了"。
            bench.append(new ToolCancelled(call.id()));
        } else {
            bench.append(new ToolResult(call.id(), outcome.success(), outcome.output(),
                    outcome.truncated(), outcome.exitCode(), outcome.durationMs(), outcome.mutated()));
        }

        // 用工具**回报的事实**，不是"能力声明 && 执行成功"的布尔运算。
        // 退出码非零的命令完全可能已经改了文件（格式化器、编译中途产物），
        // 按 success 判断会让这些改动逃过强制验证。
        if (outcome.mutated()) {
            bench.markMutated();
        }

        // 工具带回来的**领域事实**也在这儿落（目前只有任务清单）。和上面那条同一个道理：
        // 工具只回报事实，事件由这一层写 —— 它才知道怎么落库、怎么广播给看的人。
        // 位置在工具结果**之后**：读事件流的人先看到"调用跑完了"，再看到"它把清单改成了什么样"
        if (outcome.todoUpdate() != null) {
            bench.append(outcome.todoUpdate());
        }
    }

    /**
     * 把这一轮的调用切成批：**连续的**可并发调用合成一批，其余各自单独一批。
     *
     * <p>为什么按「连续」而不是「把所有可并发的挑出来放一起」：后者会打乱顺序。
     * 模型给的是 {@code [读A, 写B, 读C]}，把两个读并到一起、写夹在中间，落库的顺序
     * 就和模型请求的顺序对不上了 —— 于是投影出来的历史不再是模型说过的话。
     */
    private static List<List<ToolCall>> batchesOf(List<ToolCall> calls, ToolRegistry tools) {
        List<List<ToolCall>> batches = new ArrayList<>();
        List<ToolCall> current = new ArrayList<>();
        for (ToolCall call : calls) {
            // 认不出的工具名不参与并发：它马上会被 executeOne 报成"没有这个工具"，
            // 而那是一条要按顺序落进结果里的话
            boolean safe = tools.find(call.name()).map(Tool::concurrencySafe).orElse(false);
            if (safe) {
                current.add(call);
                continue;
            }
            if (!current.isEmpty()) {
                batches.add(current);
                current = new ArrayList<>();
            }
            batches.add(List.of(call));
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    /**
     * 跑一批。只有一个就直说；多个用虚拟线程并发。
     *
     * <p>结果**按提交顺序**取回，所以调用方拿到的顺序永远等于模型请求的顺序 ——
     * 并发只影响"什么时候跑完"，不影响"事件怎么写"。这正是 Claude Code 那边
     * "靠缓冲换顺序"的同一个做法（它按加入顺序遍历已完成的工具）。
     */
    private List<ToolOutcome> runBatch(TurnInput input, List<ToolCall> batch) {
        if (batch.size() == 1) {
            return List.of(executeOne(input, batch.getFirst()));
        }
        // 这一批全是只读的 IO 操作，正是虚拟线程擅长的负载。
        // 池**每批一个、用完就关**：工具调用不是高频路径，没必要长期攥着一个池
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // **给一批里的并发数封顶。**
            //
            // Claude Code 也有这么一个数（默认 10，还留了个环境变量能调）。但我们更需要它，
            // 理由和它不一样：它是**本地 CLI**，一个用户自己造成的扇出只影响他自己；
            // **我们是服务端**，同一台机器上还跑着别人的轮次 —— 而我们的 grep / glob
            // 是**扫整棵工作区**的递归 IO，模型在一条消息里要 20 个 grep，
            // 那就是 20 份全树扫描乘在并发轮次上。
            //
            // 上限的意义是把"一条消息能造成多大的并发"从一个**模型说了算**的数，
            // 变成平台说了算的数。虚拟线程让"开很多线程"变得便宜，但那不是这里要管的事 ——
            // 要管的是磁盘和 CPU。
            Semaphore slots = new Semaphore(MAX_PARALLEL_TOOLS);
            List<Future<ToolOutcome>> futures = batch.stream()
                    .map(call -> pool.submit(() -> {
                        slots.acquire();
                        try {
                            return executeOne(input, call);
                        } finally {
                            slots.release();
                        }
                    }))
                    .toList();
            List<ToolOutcome> outcomes = new ArrayList<>(batch.size());
            for (Future<ToolOutcome> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发工具返回时被打断", e);
        } catch (ExecutionException e) {
            // executeOne 自己吞掉了 RuntimeException，所以能到这里基本只剩 Error ——
            // 那已经不是"某个工具失败"，而是这一轮的结果不可信了
            throw new IllegalStateException("并发执行工具时出错", e.getCause());
        }
    }

    // ------------------------------------------------------------------
    // 自动验证
    // ------------------------------------------------------------------

    private VerificationOutcome verify(TurnInput input, Workbench bench, int attempt) {
        VerificationPlan plan = input.verification();
        // 平台发起的验证没有对应的模型调用，用 verify-N 这种可辨认的 id。
        // 它不会和模型的 call_xxx 混淆，审计时一眼能看出"这次是平台干的"
        String callId = "verify-" + attempt;

        // 跑验证这件事（含常量、截断、"命令没跑起来"的说法）收在 VerificationRunner 里 ——
        // 合并之后那条路径用的是同一个实现
        VerificationRunner.VerificationRun run = VerificationRunner.run(plan, callId,
                input.worktree(), input.commandExecutor(), input.cancellation());
        bench.append(run.evidence());
        return new VerificationOutcome(run.evidence().passed(), run.outputForModel(),
                run.evidence().command(), run.evidence().exitCode());
    }

    /** 验证的结论 + 注入给模型的提示词。 */
    private record VerificationOutcome(boolean passed, String output,
                                       String command, Integer exitCode) {

        /** 「平台指令」这个来源标记由 {@code ContextAssembler} 在投影时加上，这里不重复写。 */
        String failurePrompt() {
            return "你声明完成后，平台自动跑了一次验证，结果是【失败】。\n\n"
                    + "命令：" + command + "\n"
                    + "退出码：" + exitCode + "\n"
                    + "输出：\n" + output + "\n\n"
                    + "请根据这个结果修正问题。注意：不要只是重跑一次验证 —— 要真正改动代码。";
        }
    }

    private ToolOutcome executeOne(TurnInput input, ToolCall call) {
        Optional<Tool> tool = input.tools().find(call.name());
        if (tool.isEmpty()) {
            // 把可用清单一起给它 —— 模型经常是记错了名字，看到清单就能自己纠正
            return ToolOutcome.failed("没有名为 " + call.name() + " 的工具。可用的工具是: "
                    + String.join(", ", input.tools().names()));
        }

        JsonNode arguments;
        try {
            arguments = MAPPER.readTree(call.argumentsJson());
        } catch (JsonProcessingException e) {
            return ToolOutcome.failed("调用参数不是合法 JSON，无法解析。你给出的内容是："
                    + abbreviate(call.argumentsJson()));
        }

        // schema 对不上就当场说清楚。不挡的话，各工具会用 asText("") / asInt(默认值)
        // 静默兜底，最后报出「文件不存在: 」这种让模型无从下手的错 —— 白烧一轮
        Optional<String> invalid = input.tools().validateArguments(call.name(), arguments);
        if (invalid.isPresent()) {
            return ToolOutcome.failed(invalid.get());
        }

        ToolContext context = new ToolContext(input.worktree(), input.commandExecutor(),
                input.cancellation(), input.requiresApproval(), reads);
        long startedAt = System.nanoTime();
        try {
            return tool.get().execute(context, arguments)
                    .withDuration((System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException e) {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            // 取消在异常路径上也要能被识别出来
            if (input.cancellation().isCancelled()) {
                return new ToolOutcome(false, "执行被用户取消", false, null, durationMs,
                        ToolOutcome.Failure.CANCELLED, false);
            }
            // 工具实现不该抛异常，但万一抛了也要变成一次可自修的失败，
            // 而不是让整个 turn 崩掉
            return ToolOutcome.failed("工具 " + call.name() + " 执行时出错: " + e.getMessage())
                    .withDuration(durationMs);
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500) + "…";
    }

    // ------------------------------------------------------------------

    /**
     * 循环内部的工作台：累积产出的事件，并维护一份**带占位 seq 的**事件流给上下文组装用。
     *
     * <p>为什么要占位 seq：真实 seq 由存储层分配，而循环必须在下一次调用模型之前
     * 就能把"刚刚发生的事"组装进上下文。占位值从已有历史的最后一个 seq 往后递增，
     * 所以顺序是对的，压缩边界（按 seq 过滤）也不会错。
     */
    private static final class Workbench {

        private final TurnInput input;
        private final ToLongFunction<PersistentEvent> onEvent;
        private final List<PersistentEvent> produced = new ArrayList<>();
        /** 这一轮**自己产生**的事件（带 seq）—— 投影靠它们往前推进，见 {@link #assembleMessages}。 */
        private final List<StoredEvent> fresh = new ArrayList<>();
        private final ContextAssembler.Projection projection;
        /** 没有 appender（单测、批处理）时的本地序号，见 {@link #append}。 */
        private long localSeq;
        private boolean mutated;
        /** 这一轮已经交给模型的工具输出总量，见 {@link #withinTurnBudget}。 */
        private long toolOutputChars;
        /** 有调用挂起等人批了，见 {@link #markApprovalPending}。 */
        private boolean approvalPending;
        /** 最后一次模型调用的用量。**没调用过模型时是 UNKNOWN**（比如一上来就被取消）。 */
        private TokenUsage lastCallUsage = TokenUsage.UNKNOWN;

        /** 这一轮是不是卡在等人批。决定收尾时进哪个状态。 */
        boolean approvalPending() {
            return approvalPending;
        }

        void markApprovalPending() {
            approvalPending = true;
        }

        /** 这一轮有没有真的改动过工作区。只有动过才值得跑验证 —— 纯问答不必跑构建。 */
        boolean hasMutated() {
            return mutated;
        }

        void markMutated() {
            mutated = true;
        }

        /**
         * 给这一轮的工具输出总量设上限。
         *
         * <p>超了**不直接扔掉**，而是把全文落盘、只把一头一尾和位置交给模型 ——
         * 截断丢掉的很可能正是它要的那一行，而"别丢信息"正是这道上限想要的。
         *
         * <p>留**头尾各一半**，不是只留开头：工具输出这种东西，哪一头要紧是**猜不出来**的
         * —— 构建日志的结论在尾巴，文件转储的结构在开头，而目录列表两头都没什么用。
         * 各留一半是**对冲**：赌对了那一头会少看一半，但赌错的那一头不再是全 0。
         * （{@code VerificationRunner} 那边保尾，理由不同且明确：构建工具的失败信息在结尾。）
         *
         * <p>砍**新来**的而不是回头砍旧的：已经交出去的上下文收不回来，
         * 能做的只有从这一刻起止损。
         *
         * @param call 这次调用。它的 id 用来给落盘文件命名 —— 用 id 而不是序号，
         *             是为了让"这是哪次调用的输出"一眼对得上
         */
        ToolOutcome withinTurnBudget(ToolCall call, ToolOutcome outcome) {
            String output = outcome.output();

            // **自己就有界的工具不进这道总量限制**（见 {@link Tool#selfBounded()}）。
            //
            // 放行而不是跳过记账：它照样把上下文占掉了（所以 local 计数照加），
            // 只是"怎么处理"这一层不归它管 —— 对它的落盘提示是循环指令，见 {@link Tool#selfBounded()}。
            //
            // 它占掉的那一份由**压缩**兜（read_file 在 ContextCompactor 的可清名单里）。
            // 这也是 Claude Code 的分工：它的聚合预算同样把读文件排除，
            // 注释说"Read 自己的上限就是它的界，不是这层包装"
            if (input.tools().find(call.name()).map(Tool::selfBounded).orElse(false)) {
                toolOutputChars += output.length();
                return outcome;
            }

            if (toolOutputChars + output.length() <= MAX_TOOL_OUTPUT_CHARS_PER_TURN) {
                toolOutputChars += output.length();
                return outcome;
            }
            String retained = headAndTail(output);
            // 提示语里带上**多大**：模型得先知道值不值得读回来。只说"已存到 X"的话，
            // 它要么白读一遍（才几行），要么以为很大而放弃（其实就几十行）
            String body = persist(call.id(), output)
                    .map(path -> "（这一轮的工具输出总量已达上限。完整内容（共 " + output.length()
                            + " 字符）已存到 " + path + "，可以用 read_file 读回）\n\n"
                            + "开头和结尾如下：\n" + retained)
                    .orElse("（这一轮的工具输出总量已达上限，且落盘失败，只留下头尾）\n\n" + retained);
            toolOutputChars += body.length();
            return new ToolOutcome(outcome.success(), body, true, outcome.exitCode(),
                    outcome.durationMs(), outcome.failure(), outcome.mutated());
        }

        /**
         * 留头尾各一半，中间一句说明省了多少 —— 见 {@code withinTurnBudget} 里"为什么是各一半"。
         *
         * <p>中间那句必须**带上省掉的量**：不带的话，模型会以为这两段是连着的，
         * 于是把"第二段紧跟在第一段后面"当真 —— 那比明说省略更容易骗到它。
         */
        private static String headAndTail(String output) {
            if (output.length() <= CAPPED_TOOL_OUTPUT_CHARS) {
                return output;
            }
            int half = CAPPED_TOOL_OUTPUT_CHARS / 2;
            int omitted = output.length() - 2 * half;
            return output.substring(0, half)
                    + "\n……（中间省略 " + omitted + " 字符）……\n"
                    + output.substring(output.length() - half);
        }

        /**
         * 把完整输出写进工作区里的落盘目录。
         *
         * <p>目录在**工作区内部**，而且已经被 git 忽略（那份忽略由
         * {@code LocalWorkspaceManager} 建 worktree 时写下）—— 于是模型用普通的
         * read_file、给一个普通相对路径就能读回来，不需要给路径守卫开任何口子。
         *
         * <p>写之前先扫一遍旧文件：这个目录**只增不删**，而它被 git 忽略，
         * 回滚的 {@code clean -fd} 也删不掉它 —— 没人扫的话它就跟着这棵树一直长下去。
         *
         * @return 读得回来时给出**相对工作区的路径**（模型可以直接照着用）；
         *         写不成就返回空，调用方降级成"只留头尾"
         */
        private Optional<String> persist(String callId, String content) {
            try {
                Path dir = input.worktree().resolve(Workspace.TOOL_OUTPUT_DIR);
                Files.createDirectories(dir);
                sweepOldSpills(dir, Instant.now().minus(TOOL_OUTPUT_RETENTION));
                String fileName = callId + ".txt";
                // **存在就失败（CREATE_NEW），不覆盖。**
                //
                // 文件名来自模型给的 callId，同一条会话里通常不会重名 —— 但"通常"不够：
                // 模型重复用一个 id 完全可能，而覆盖的后果是**前面那条路径指向了别的内容**，
                // 模型照着提示读回来会拿到一份不相干的文本，而且没有任何地方会报错。
                // Claude Code 和 deepseek-harness 落的都是**独占创建**（存在就失败，落盘的文件还要限属主可读）。
                // 撞上了就退回截断（下面那个 catch 接得住）—— 宁可少留一份，也不留一份假的
                Files.writeString(dir.resolve(fileName), content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return Optional.of(Workspace.TOOL_OUTPUT_DIR + "/" + fileName);
            } catch (IOException | RuntimeException e) {
                // 落盘只是"尽量别丢信息"。它失败不该让这一轮垮掉 —— 退回截断就是了
                return Optional.empty();
            }
        }

        private Workbench(TurnInput input, ToLongFunction<PersistentEvent> onEvent) {
            this.input = input;
            this.onEvent = onEvent;
            // 投影是**调用方递进来的那条活的**：它跨轮活着（见 SessionProjections），
            // 这一轮只是接着往它上面折
            this.projection = input.projection();
            // 没有 appender 时自己编号：**得从投影已经折到的地方接着数** ——
            // 从 1 重新数的话，新事件会落在已经折过的范围里，被当成旧的跳过
            this.localSeq = projection.foldedUpTo();
        }

        /**
         * 记下一条新事件。
         *
         * <h2>为什么非要**真实**的 seq</h2>
         * 投影是自己记着"折到哪一条"的（那就是它的坐标）。喂进去的是本地占位号的话，
         * 下一轮按真实 seq 补读时就会判错 —— 同一个事件被折两遍（消息重复），
         * 或者整批被当成"已经折过"而跳过（消息凭空少一截）。两种都不报错。
         *
         * <p>没有 appender 的场合（单测、批处理）退回本地自增号：那条路上投影只活一轮，
         * 序号只要单调就够。
         *
         * <p>写入**先于**记录：seq 是写库之后才有的。从前是先记进本地、再回调落库 ——
         * 那时候本地号是假的，顺序无所谓；现在它得是真的了。
         */
        void append(PersistentEvent event) {
            long seq = onEvent == null ? ++localSeq : onEvent.applyAsLong(event);
            produced.add(event);
            // occurredAt 用固定值：上下文组装器不看它，而用 now() 会让投影带上时间依赖
            fresh.add(new StoredEvent(input.sessionId(), seq, Instant.EPOCH, event));
        }

        /**
         * 装配这一步要发给模型的上下文。
         *
         * <h2>为什么一轮里只折新来的</h2>
         * 这个循环最多迭代 25 次（每批工具跑完一次），而每迭代一次都要重新装配一遍。
         * 从前每次装配都把**整条事件流**从头投影一遍 —— 也就是说这一轮里，
         * 前面那 24 次做过的事被重做了 24 遍，而且会话越长这份白工越大。
         *
         * <p>现在折的是**这一轮自己产生的事件**（投影里已经有此前所有事件了）。
         */
        void assembleMessages(List<ChatMessage> sink) {
            projection.advance(fresh, sink);
        }

        String currentModelId() {
            return input.model().modelId();
        }

        /**
         * 记下**这一次**模型调用发出去的时候上下文有多大。
         *
         * <p>它和循环里那个累计的 {@code usage} 是两笔账，所以分开存：
         * 前者是"这次请求送进去多少"（也就是当时的上下文），后者是"这一轮一共花了多少"。
         * 一次调用就完成的一轮里两者恰好相等 —— 那正是它们最容易被混为一谈的地方。
         */
        void rememberCall(TokenUsage callUsage) {
            lastCallUsage = callUsage;
        }

        TurnOutcome outcome(String model, String finishReason, TokenUsage usage,
                            TurnOutcome.Status status) {
            return new TurnOutcome(produced, model, finishReason, usage, lastCallUsage, status);
        }
    }
}
