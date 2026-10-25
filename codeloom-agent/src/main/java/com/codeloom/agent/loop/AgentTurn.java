package com.codeloom.agent.loop;

import com.codeloom.agent.context.ContextAssembler;
import com.codeloom.agent.llm.LlmMessage;
import com.codeloom.agent.llm.LlmRequest;
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
import com.codeloom.domain.workspace.WorkspacePathGuard;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * 一轮 agent 执行：模型说话、执行它请求的工具、回灌结果，直到模型不再请求工具。
 *
 * <p>本类不碰存储：输入是投影，输出是事件；调用方负责落库、更新会话状态和广播。
 * 因此它可以用假的模型客户端和临时目录完整测试。
 *
 * <p>三道防自旋：工具轮次上限；token 预算超限时注入收尾指令而不是掐断连接；
 * 用户取消在工具边界生效。
 *
 * <p>两条输出通道：{@link #run(TurnInput, ToLongFunction)} 的 {@code onEvent} 接收持久事件，
 * 调用方必须立刻落库并返回真实 seq；构造器的 {@code liveListener} 接收不落库的流式事件，
 * 包括正文与思考增量、工具调用完成和结束标记。
 */
public final class AgentTurn {

    private static final Logger log = LoggerFactory.getLogger(AgentTurn.class);

    /** 上一次发给模型的请求；只用于诊断日志，不参与判断。 */
    private List<LlmMessage> previousRequest = List.of();

    /** 一轮内最多执行多少轮工具调用；超过视为打转，停下交给人。 */
    private static final int MAX_TOOL_ROUNDS = 25;

    /**
     * 一次模型调用最多尝试几次（含第一次）。3 是工程判断：限流和抖动通常一两次过去，
     * 每次退避都让界面前的用户干等；第三次仍失败就应报告。
     */
    private static final int MAX_ATTEMPTS = 3;

    /** 第一次退避的基数；之后翻倍。 */
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);

    /** 退避上限；服务商要求的等待超过它就不重试（见 {@link #delayBeforeRetry}）。 */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(8);

    /** 退避抖动比例，避免多条会话同时重试。 */
    private static final double JITTER_RATIO = 0.25;

    /** 等退避时检查取消信号的间隔。 */
    private static final long RETRY_POLL_MILLIS = 100;

    /**
     * 一批并发安全调用同时最多跑几个；超出者排队。上限约束的是模型一次请求造成的并发，
     * 避免共享机器被他人的轮次挤占。见
     * {@code docs/decisions/architecture/2026-10-06-tool-parallelism-capped-at-eight.md}。
     *
     * <p>包级可见供测试断言上限。
     */
    static final int MAX_PARALLEL_TOOLS = 8;

    /** 验证失败后最多让模型自修几次；超过就如实以未通过收场。 */
    private static final int MAX_VERIFY_ATTEMPTS = 3;

    /**
     * 一轮内送给模型的工具输出总量上限（字符）。单个工具各有上限，但一轮多次往返会累加，
     * 且压缩要到下一轮才运行，救不了当前轮次。见
     * {@code docs/decisions/architecture/2026-10-04-tool-output-spills-to-the-workspace.md}。
     */
    private static final int MAX_TOOL_OUTPUT_CHARS_PER_TURN = 200_000;

    /** 越过总量上限后，新结果保留的字符数（头尾各一半）。 */
    private static final int CAPPED_TOOL_OUTPUT_CHARS = 2_000;

    /** 落盘工具输出的保留期。它们是证据而不是缓存，可能仍被历史引用。 */
    private static final Duration TOOL_OUTPUT_RETENTION = Duration.ofDays(30);

    /**
     * 思考过程最多保留的字符数，落库和实时推送共用。超长时保留开头。
     * 公开是因为两处必须使用同一个上限，否则实时与刷新后的内容会不一致。
     */
    public static final int MAX_REASONING_CHARS = 16_000;

    /**
     * 按 {@link #MAX_REASONING_CHARS} 截断思考过程。
     *
     * @param reasoning 原始思考
     * @return 截断后的思考；未超限时原样返回
     */
    private static String abbreviateReasoning(String reasoning) {
        if (reasoning == null || reasoning.length() <= MAX_REASONING_CHARS) {
            return reasoning;
        }
        return reasoning.substring(0, MAX_REASONING_CHARS)
                + "\n…（思考过程过长，已截断）";
    }

    /** 输出被长度上限截断后最多重来几次；用尽后以截断状态收场。 */
    private static final int MAX_TRUNCATION_RETRIES = 2;

    /** 输出被截断时注入的指令；要求把步子拆小，继续写只会再次截断。 */
    private static final String TRUNCATED_INSTRUCTION =
            "你上一次的输出因为长度上限被截断了（不是你写完了）。"
                    + "请把刚才那一步**拆成更小的几步**重做：比如写文件时先写一小段，"
                    + "剩下的用 edit_file 分几次补上。不要道歉，也不要复述已经说过的内容。";

    /** 解析工具参数用的 mapper；无状态，静态共享以免每轮重复构造。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Consumer<StreamEvent> liveListener;

    /** 上下文装不下时的修复入口；默认不修，直接如实失败。见 {@link ContextRepair}。 */
    private final ContextRepair contextRepair;

    public AgentTurn() {
        this(event -> {
        });
    }

    /**
     * @param liveListener 实时事件回调；测试和不关心中间过程时传空实现
     */
    public AgentTurn(Consumer<StreamEvent> liveListener) {
        this(liveListener, NO_REPAIR);
    }

    /**
     * @param liveListener  实时事件回调
     * @param contextRepair 上下文装不下时的修复；只属于这条会话，不进 {@code TurnInput}
     */
    public AgentTurn(Consumer<StreamEvent> liveListener, ContextRepair contextRepair) {
        this.liveListener = Objects.requireNonNull(liveListener, "liveListener");
        this.contextRepair = Objects.requireNonNull(contextRepair, "contextRepair");
    }

    /** 不修复：上下文装不下就直接失败，原样报告服务商说明。 */
    private static final ContextRepair NO_REPAIR = () -> false;

    /**
     * 这条会话读过的文件，用于“读过才许覆盖”的判断。只活一轮；跨轮的账由
     * {@link #seedReadLedger} 每轮从投影补入。见 {@link ReadLedger}。
     */
    private final ReadLedger reads = new ReadLedger();

    /**
     * 删除落盘目录中修改时间早于 {@code cutoff} 的文件，返回删除数量。清理失败一律吞掉，
     * 不影响本轮执行。
     *
     * @param dir    落盘目录
     * @param cutoff 早于此时间的文件视为过期
     * @return 删除的文件数
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
     * 把这段对话此前读过的文件补进账里。范围是整段对话而不是这一轮；无法解析的路径跳过，
     * 最坏结果是模型多读一次，不会写错文件。
     *
     * @param input 本轮输入
     */
    private void seedReadLedger(TurnInput input) {
        // 读过哪些文件是投影维护的派生事实，不必每轮重扫事件流。
        for (String path : input.projection().readPaths()) {
            try {
                // 投影只有路径、没有版本，因此记为“版本未知”（见 ReadLedger 的 reconstructed）。
                reads.markReconstructed(WorkspacePathGuard.resolve(input.worktree(), path));
            } catch (RuntimeException e) {
                // 单个路径解析不了只跳过它：跳过一次，不跳过错
            }
        }
    }

    /**
     * 不关心中间过程时使用：事件最终都在 {@code TurnOutcome} 里。
     *
     * @param input 本轮输入
     * @return 本轮产出
     */
    public TurnOutcome run(TurnInput input) {
        // 没有 appender：事件不落库，本轮使用本地序号（见 Workbench.append）。
        return run(input, null);
    }

    /**
     * 跑一轮，每条持久事件在产生时立即回调给 {@code onEvent}。
     *
     * <p>不能等一轮结束再交出：崩溃恢复要求 {@code ToolCallRequested} 在工具开始执行前已经落库；
     * 实时观战也要求工具事件在长时间构建期间可见。回调必须立刻落库并返回该事件的真实 seq，
     * 循环用它作为投影坐标；{@code null} 表示不落库，改用本地自增号。
     *
     * <p>回调抛异常会中止整轮，因为剩余产出已不可信。回调顺序与事件产生顺序一致，
     * 且与 {@code TurnOutcome.newEvents()} 是同一批事件。
     *
     * @param input   本轮输入
     * @param onEvent 持久事件落库回调；返回事件真实 seq，null 表示不落库
     * @return 本轮产出
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

        // 上一次批准的调用必须在进入模型循环之前补跑（见 settleApprovedCall）。
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
            // 两笔账：累计用量是账单，最后一次调用的用量是收尾时的上下文大小（见 TurnOutcome#lastCallUsage）。
            usage = usage.plus(result.usage());
            bench.rememberCall(result.usage());

            // 有正文或有思考才落库：带 tool_calls 的消息缺了 reasoning_content，下一次请求
            // 会返回 400（见 LlmMessage#assistant）；只调工具且无思考的一轮不产出消息。
            if (!result.text().isEmpty() || result.hasReasoning()) {
                // 记录产出这条消息时的模型名；一轮内模型可能被切换。
                bench.append(new AssistantMessage(result.text(), model,
                        abbreviateReasoning(result.reasoning())));
            }

            if (result.isTruncated()) {
                // 半截输出不能当正常结束；先注入“拆小”指令给它自纠机会。
                if (++truncationRetries > MAX_TRUNCATION_RETRIES) {
                    return bench.outcome(model, finishReason, usage, TurnOutcome.Status.TRUNCATED);
                }
                // 平台指令使用独立事件类型，审计时与用户发言区分。
                bench.append(new PlatformInstruction(TRUNCATED_INSTRUCTION, "output-truncated"));
                continue;
            }

            if (!result.hasToolCalls()) {
                // 模型声称完成；只要动过工作区，平台就自己验证，不把“代码可信”交给模型判断。
                if (!input.verification().isEnabled() || !bench.hasMutated()) {
                    return bench.outcome(model, finishReason, usage, TurnOutcome.Status.COMPLETED);
                }

                verifyAttempt++;
                VerificationOutcome verification = verify(input, bench, verifyAttempt);
                if (verification.passed()) {
                    return bench.outcome(model, finishReason, usage, TurnOutcome.Status.COMPLETED);
                }
                if (verifyAttempt >= MAX_VERIFY_ATTEMPTS) {
                    // 自修次数用尽；继续喂下去只会空转，应如实报告验证未通过。
                    return bench.outcome(model, finishReason, usage,
                            TurnOutcome.Status.VERIFICATION_FAILED);
                }
                // 失败必须作为 PlatformInstruction 事件落库：崩溃重放才能复现同一上下文，
                // 审计时也能与用户发言区分。
                bench.append(new PlatformInstruction(verification.failurePrompt(), "verification-failed"));
                continue;
            }

            if (wrapUpRequested) {
                // 已要求收尾却仍请求工具，必须硬停。
                return bench.outcome(model, finishReason, usage, TurnOutcome.Status.BUDGET_EXHAUSTED);
            }

            if (!executeTools(input, bench, result.toolCalls())) {
                return bench.outcome(model, finishReason, usage, TurnOutcome.Status.CANCELLED);
            }
            if (bench.approvalPending()) {
                return bench.outcome(model, finishReason, usage,
                        TurnOutcome.Status.AWAITING_APPROVAL);
            }

            // 预算只按本轮计算：BYOK 下费用属于用户，不存在平台累计限额（见 TokenBudget）。
            if (input.budget().shouldWrapUp(usage)) {
                wrapUpRequested = true;
            }
        }

        return bench.outcome(model, finishReason, usage, TurnOutcome.Status.MAX_ITERATIONS);
    }

    /**
     * 能力门控：不支持工具调用的模型配上工具时，在开始前就失败，而不是在每次工具往返里静默失败。
     *
     * @param input 本轮输入
     * @throws LlmCallException 模型不支持工具调用时抛出 {@code INVALID_REQUEST}
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
        List<LlmMessage> messages = new ArrayList<>();
        bench.assembleMessages(messages);
        // 时钟已随用户消息进入投影（见 MessageFold）；这里再补一行会破坏前缀缓存。
        if (wrapUpRequested) {
            messages.add(LlmMessage.user(TokenBudget.WRAP_UP_INSTRUCTION));
        }

        LlmRequest request = new LlmRequest(
                bench.currentModelId(),
                messages,
                input.tools().definitions(),
                input.capabilities().maxOutputTokens());

        // 诊断用：本次请求是否是上一次的严格延伸；只在 DEBUG 时计算，不记录正文。
        boolean extendsPrevious = log.isDebugEnabled()
                && messages.size() >= previousRequest.size()
                && messages.subList(0, previousRequest.size()).equals(previousRequest);

        // 重试放在这一层：它知道轮次上下文、有事件通道和取消信号；客户端只知道单次请求。
        for (int attempt = 1; ; attempt++) {
            try {
                LlmResult result = input.client().stream(request, liveListener, input.cancellation());
                if (log.isDebugEnabled()) {
                    TokenUsage used = result.usage();
                    log.debug("模型调用 session={} messages={} input={} cached={} output={} extendsPrevious={}",
                            input.sessionId(), messages.size(), used.inputTokens(),
                            used.cachedInputTokens(), used.outputTokens(), extendsPrevious);
                    previousRequest = List.copyOf(messages);
                }
                return result;
            } catch (LlmCallException e) {
                if (!e.retryable() || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                Optional<Duration> delay = delayBeforeRetry(attempt, e);
                if (delay.isEmpty()) {
                    // 服务商要求的等待超过上限（见 delayBeforeRetry）。
                    throw e;
                }
                // 先落事件再等待，让重试期间用户看得见。
                bench.append(new LlmRetryScheduled(attempt, MAX_ATTEMPTS,
                        delay.get().toMillis(), e.shortReason()));
                sleepBeforeRetry(delay.get(), input.cancellation());
            }
        }
    }

    /**
     * 这次失败后等待多久再试；空表示不再试。服务商给了 {@code Retry-After} 就按它等；
     * 若超过 {@link #MAX_BACKOFF} 则放弃，提前重试只会再次触发限流。
     *
     * @param attempt 当前尝试次数
     * @param failure 失败
     * @return 等待时间；空表示报告原始失败
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
     * 分段等待，每段检查一次取消信号，避免用户按 Esc 后仍要等完整个退避。
     *
     * @param delay        等待时长
     * @param cancellation 取消信号
     * @throws LlmCallException 等待期间被取消或被中断
     */
    private static void sleepBeforeRetry(Duration delay, CancellationToken cancellation) {
        long deadline = System.nanoTime() + delay.toNanos();
        while (true) {
            if (cancellation.isCancelled()) {
                // 报成 CANCELLED，让上层走取消收尾而不是记一次失败。
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
     * 上下文装不下时修复一次，然后重发一次。只修一次：压缩能把前缀换成摘要，若仍装不下，
     * 问题多半在尚未压缩的尾部，再压无益。修不动就报告原始错误。
     *
     * @param input           本轮输入
     * @param bench           工作台
     * @param wrapUpRequested 是否已要求收尾
     * @param failure         原始失败
     * @return 重发结果
     * @throws LlmCallException 不是上下文超限、修复未改变状态，或重发仍失败
     */
    private LlmResult retryAfterOverflow(TurnInput input, Workbench bench,
                                         boolean wrapUpRequested, LlmCallException failure) {
        if (!failure.isContextExceeded() || !contextRepair.afterContextOverflow()) {
            // 不是上下文问题，或修复没有改变模型可见状态（见 ContextRepair），都不重试。
            throw failure;
        }
        return callModel(input, bench, wrapUpRequested);
    }

    /**
     * 真正执行用户刚批准的那次调用。挂起路径只落 {@code ToolApprovalRequested}、不落结果，
     * 批准答复到达后没有别处会执行它。
     *
     * <p>执行所需的工作区、工具注册表、执行器、取消信号和预算都在 {@link TurnInput} 里，
     * 因此放在本类；只有批准答复是最后一步时才补跑（见 {@link #pendingApprovedCall}），
     * 新用户消息不会触发上一轮遗留命令的重放。
     *
     * @param input 本轮输入
     * @param bench 工作台
     */
    private void settleApprovedCall(TurnInput input, Workbench bench) {
        ToolCall call = pendingApprovedCall(input).orElse(null);
        if (call == null) {
            return;
        }
        // 不再落 ToolCallRequested：上次已经落过，重复会让投影读成两次调用。
        // 只对这条命令放行，否则原判据会再次拦下它，形成反复挂起。
        TurnInput granted = input.withApprovalGrantedFor(commandOf(input, call));
        ToolOutcome outcome = bench.withinTurnBudget(call, executeOne(granted, call));
        if (outcome.needsApproval()) {
            // 已放行仍要求审批说明判据不一致；报成失败，不能再挂起一次。
            recordOutcome(bench, call, ToolOutcome.failed(
                    "这条命令已经批准过，但执行时仍被判定需要审批。请换一种写法。"));
            return;
        }
        recordOutcome(bench, call, outcome);
    }

    /**
     * 从调用参数里取出命令，依据工具声明的 {@link ToolSurface.Shape#EXECUTE} 和主语键。
     * 不能写死参数名：对不上会让被批准的命令与补跑的命令不一致。
     *
     * @param input 本轮输入
     * @param call  调用
     * @return 命令行；参数不合法或没有命令时为空
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
     * 找出已批准、但还没有结局的那次调用。
     *
     * <p>批准答复必须是尾部最后一条改变对话形状的事件，且历史里没有该调用的任何收尾记录。
     * 后者让它幂等：补跑结果落库后，尾部不再是批准答复。
     *
     * @param input 本轮输入
     * @return 待补跑的调用；没有就是空
     */
    private static Optional<ToolCall> pendingApprovedCall(TurnInput input) {
        // 判据见 ContextAssembler.Projection#pendingApprovedCall 上那段
        return input.projection().pendingApprovedCall();
    }

    /**
     * 执行模型请求的工具批次。
     *
     * @param input 本轮输入
     * @param bench 工作台
     * @param calls 模型请求的调用
     * @return false 表示中途被取消
     */
    private boolean executeTools(TurnInput input, Workbench bench, List<ToolCall> calls) {
        for (List<ToolCall> batch : batchesOf(calls, input.tools())) {
            if (input.cancellation().isCancelled()) {
                return false;
            }
            // 先落库整批请求再执行：崩溃恢复依赖“工具开始前请求已在库里”。
            for (ToolCall call : batch) {
                bench.append(new ToolCallRequested(call.id(), call.name(), call.argumentsJson()));
            }

            List<ToolOutcome> outcomes = runBatch(input, batch);

            for (int i = 0; i < batch.size(); i++) {
                ToolCall call = batch.get(i);
                ToolOutcome outcome = bench.withinTurnBudget(call, outcomes.get(i));

                if (outcome.needsApproval()) {
                    // 落审批请求后整轮停下，不落 ToolResult（它还没执行）。
                    // 本批后续调用不再执行；已落库的请求由投影的配对兜底说明。
                    // outcome 的正文在这里就是问人的理由。
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
     * 把一次执行的结局落成事件。正常执行与批准补跑共用，确保“取消落成 ToolCancelled”
     * 这条规则不会在某一处走样。
     *
     * @param bench   工作台
     * @param call    调用
     * @param outcome 执行结果
     */
    private static void recordOutcome(Workbench bench, ToolCall call, ToolOutcome outcome) {
        if (outcome.isCancelled()) {
            // 取消落成独立事件，不落失败结果；否则重放后模型会重试用户刚取消的操作。
            bench.append(new ToolCancelled(call.id()));
        } else {
            bench.append(new ToolResult(call.id(), outcome.success(), outcome.output(),
                    outcome.truncated(), outcome.exitCode(), outcome.durationMs(), outcome.mutated(),
                    outcome.presentation()));
        }

        // 以工具回报的 mutated 为准，不能按 success 推断：失败的格式化器或编译也可能已改文件。
        if (outcome.mutated()) {
            bench.markMutated();
        }

        // 工具只回报领域事实（见 ToolOutcome#todoUpdate），事件由这一层在结果之后写入。
        if (outcome.todoUpdate() != null) {
            bench.append(outcome.todoUpdate());
        }
    }

    /**
     * 把调用切成批：连续的可并发调用合为一批，其余各自成批。保持连续性才能让落库顺序
     * 等于模型请求顺序。
     *
     * @param calls 调用列表
     * @param tools 工具注册表
     * @return 批次
     */
    private static List<List<ToolCall>> batchesOf(List<ToolCall> calls, ToolRegistry tools) {
        List<List<ToolCall>> batches = new ArrayList<>();
        List<ToolCall> current = new ArrayList<>();
        for (ToolCall call : calls) {
            // 未知工具不并发：它会被 executeOne 报成“没有这个工具”，结果仍需按序落库。
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
     * 跑一批：单个直接执行，多个用虚拟线程并发。
     *
     * <p>结果按提交顺序取回，并发只影响完成时间，不影响事件顺序。
     *
     * @param input 本轮输入
     * @param batch 这一批调用
     * @return 与 {@code batch} 同序的结果
     */
    private List<ToolOutcome> runBatch(TurnInput input, List<ToolCall> batch) {
        if (batch.size() == 1) {
            return List.of(executeOne(input, batch.getFirst()));
        }
        // 池每批创建、用完即关；工具调用不是高频路径。
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // 并发上限由平台决定，避免一条消息里的多个全树 grep 挤占共享机器（见 MAX_PARALLEL_TOOLS）。
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
            // executeOne 已吞掉 RuntimeException；到这里基本只剩 Error，本轮结果不可信。
            throw new IllegalStateException("并发执行工具时出错", e.getCause());
        }
    }

    // ------------------------------------------------------------------
    // 自动验证
    // ------------------------------------------------------------------

    private VerificationOutcome verify(TurnInput input, Workbench bench, int attempt) {
        VerificationPlan plan = input.verification();
        // 平台验证用可辨认的 verify-N id，避免与模型的 call_xxx 混淆。
        String callId = "verify-" + attempt;

        // 截断、常量与“命令没跑起来”的措辞统一由 VerificationRunner 提供。
        VerificationRunner.VerificationRun run = VerificationRunner.run(plan, callId,
                input.worktree(), input.commandExecutor(), input.cancellation());
        bench.append(run.evidence());
        return new VerificationOutcome(run.evidence().passed(), run.outputForModel(),
                run.evidence().command(), run.evidence().exitCode());
    }

    /** 验证结论与注入给模型的失败提示。 */
    private record VerificationOutcome(boolean passed, String output,
                                       String command, Integer exitCode) {

        /** 来源标记由投影层添加，这里不重复写。 */
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
            // 附带可用清单，模型常是记错名字。
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

        // schema 不符时当场说明；否则工具会静默兜底，报出难以自修的错。
        Optional<String> invalid = input.tools().validateArguments(call.name(), arguments);
        if (invalid.isPresent()) {
            return ToolOutcome.failed(invalid.get());
        }

        ToolContext context = new ToolContext(input.worktree(), input.commandExecutor(),
                input.cancellation(), input.requiresApproval(), reads, input.web());
        long startedAt = System.nanoTime();
        try {
            return tool.get().execute(context, arguments)
                    .withDuration((System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException e) {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            // 异常路径上也要识别取消。
            if (input.cancellation().isCancelled()) {
                return new ToolOutcome(false, "执行被用户取消", false, null, durationMs,
                        ToolOutcome.Failure.CANCELLED, false);
            }
            // 工具不该抛异常；万一抛出，也转成可自修的失败而不是让整轮崩溃。
            return ToolOutcome.failed("工具 " + call.name() + " 执行时出错: " + e.getMessage())
                    .withDuration(durationMs);
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500) + "…";
    }

    // ------------------------------------------------------------------

    /**
     * 循环内部工作台：累积本轮事件，并维护喂给投影的事件流。
     *
     * <p>没有 appender 时使用本地自增 seq，从投影已处理的位置继续编号，保证顺序和压缩边界正确。
     */
    private static final class Workbench {

        private final TurnInput input;
        private final ToLongFunction<PersistentEvent> onEvent;
        private final List<PersistentEvent> produced = new ArrayList<>();
        /** 本轮自己产生的带 seq 事件，投影靠它们推进（见 {@link #assembleMessages}）。 */
        private final List<StoredEvent> fresh = new ArrayList<>();
        private final ContextAssembler.Projection projection;
        /** 没有 appender（单测、批处理）时的本地序号，见 {@link #append}。 */
        private long localSeq;
        private boolean mutated;
        /** 本轮已交给模型的工具输出字符总数，见 {@link #withinTurnBudget}。 */
        private long toolOutputChars;
        /** 是否有调用挂起等待批准，见 {@link #markApprovalPending}。 */
        private boolean approvalPending;
        /** 最后一次模型调用的用量；没有调用过模型时为 {@link TokenUsage#UNKNOWN}。 */
        private TokenUsage lastCallUsage = TokenUsage.UNKNOWN;

        /** 本轮是否卡在等待批准，决定收尾状态。 */
        boolean approvalPending() {
            return approvalPending;
        }

        void markApprovalPending() {
            approvalPending = true;
        }

        /** 本轮是否可能改动过工作区；保守判断，失败或被杀的 run_command 也算。 */
        boolean hasMutated() {
            return mutated;
        }

        void markMutated() {
            mutated = true;
        }

        /**
         * 对单次工具输出应用本轮总量上限。超出时不丢弃全文：落盘后只把头尾各一半和路径交给模型，
         * 已经交出的上下文无法回收，只能从当前结果起止损。见
         * {@code docs/decisions/architecture/2026-10-04-tool-output-spills-to-the-workspace.md}。
         *
         * @param call    调用；其 id 用作落盘文件名
         * @param outcome 原始结果
         * @return 可能改写输出后的结果
         */
        ToolOutcome withinTurnBudget(ToolCall call, ToolOutcome outcome) {
            String output = outcome.output();

            // 自带真实上界的工具不走这道限制（见 Tool#selfBounded），但仍计入总量；
            // 其累积占用由压缩处理。
            if (input.tools().find(call.name()).map(Tool::selfBounded).orElse(false)) {
                toolOutputChars += output.length();
                return outcome;
            }

            if (toolOutputChars + output.length() <= MAX_TOOL_OUTPUT_CHARS_PER_TURN) {
                toolOutputChars += output.length();
                return outcome;
            }
            String retained = headAndTail(output);
            // 提示里带上字符数，模型才能判断值不值得读回。
            String body = persist(call.id(), output)
                    .map(path -> "（这一轮的工具输出总量已达上限。存到 " + path + " 的那一份有 "
                            + output.length() + " 字符，可以用 read_file 读回）\n\n"
                            + "开头和结尾如下：\n" + retained)
                    .orElse("（这一轮的工具输出总量已达上限，且落盘失败，只留下头尾）\n\n" + retained);
            toolOutputChars += body.length();
            // 用 withOutput 保留 todoUpdate / presentation，不能自行拼 record。
            return outcome.withOutput(body, true);
        }

        /**
         * 保留头尾各一半，中间注明省略的字符数；不注明会让模型误以为两段相连。
         *
         * @param output 完整输出
         * @return 截断后的文本
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
         * 把完整输出写进工作区内的落盘目录。目录已被 git 忽略，模型可用普通相对路径读回；
         * 写入前清理过期文件，避免目录无限增长。
         *
         * @param callId  调用 id，用作文件名
         * @param content 完整输出
         * @return 成功时给出工作区相对路径；失败返回空，调用方降级为只留头尾
         */
        private Optional<String> persist(String callId, String content) {
            try {
                Path dir = input.worktree().resolve(Workspace.TOOL_OUTPUT_DIR);
                Files.createDirectories(dir);
                sweepOldSpills(dir, Instant.now().minus(TOOL_OUTPUT_RETENTION));
                String fileName = callId + ".txt";
                // 独占创建（CREATE_NEW）：callId 可能重复，覆盖会让旧路径指向别的内容且不报错。
                // 冲突时退回截断，宁可少留一份也不留假的。
                Files.writeString(dir.resolve(fileName), content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return Optional.of(Workspace.TOOL_OUTPUT_DIR + "/" + fileName);
            } catch (IOException | RuntimeException e) {
                // 落盘失败只降级为截断，不能让整轮失败。
                return Optional.empty();
            }
        }

        private Workbench(TurnInput input, ToLongFunction<PersistentEvent> onEvent) {
            this.input = input;
            this.onEvent = onEvent;
            // 投影在跨轮之间存活，本轮只是继续往上折。
            this.projection = input.projection();
            // 本地编号必须从投影已处理的位置继续，否则新事件会被当成旧的跳过。
            this.localSeq = projection.foldedUpTo();
        }

        /**
         * 记录一条新事件。有 appender 时必须使用它返回的真实 seq，否则下一轮增量补读会重复或漏掉。
         * 没有 appender 时使用本地自增号，该路径上投影只活一轮。
         *
         * @param event 新事件
         */
        void append(PersistentEvent event) {
            long seq = onEvent == null ? ++localSeq : onEvent.applyAsLong(event);
            produced.add(event);
            // 投影会用这个时间生成用户时钟和任务清单快照，不能用固定值：
            // 本轮内下一次装配会把它读成 1970 年
            fresh.add(new StoredEvent(input.sessionId(), seq, Instant.now(), event));
        }

        /**
         * 装配这一步要发给模型的上下文。只折本轮新产生的事件，投影里已有此前的全部事件。
         *
         * @param sink 输出列表
         */
        void assembleMessages(List<LlmMessage> sink) {
            projection.advance(fresh, sink);
        }

        String currentModelId() {
            return input.model().modelId();
        }

        /**
         * 记录最近一次模型调用的用量，它同时代表收尾时的上下文大小；累计用量是另一笔账。
         *
         * @param callUsage 本次调用的用量
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
