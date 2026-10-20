package com.codeloom.app.context;

import com.codeloom.agent.context.ContextAssembler;
import com.codeloom.agent.llm.LlmMessage;
import com.codeloom.agent.llm.LlmRequest;
import com.codeloom.agent.llm.LlmClient;
import com.codeloom.agent.llm.LlmClientProvider;
import com.codeloom.agent.llm.LlmResult;
import com.codeloom.agent.llm.ToolCall;
import com.codeloom.agent.model.ModelCapabilities;
import com.codeloom.agent.model.ModelCapabilitiesResolver;
import com.codeloom.agent.tool.ToolNames;
import com.codeloom.app.turn.SessionWriter;
import com.codeloom.domain.event.ContextCompacted;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.ToolCallRequested;
import com.codeloom.domain.event.ToolResultsCleared;
import com.codeloom.domain.event.TurnTokensUsed;
import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 上下文压缩：历史过长时，把已有的一整段总结成一段话，之后不再原样送给模型。
 *
 * <p>压缩会换掉整个前缀，是唯一会破坏 prompt 缓存的常规操作，所以必须等真的快满了才触发。
 * 触发依据取“按字符估算”和“上一轮收尾时服务商报的读数”中较大的一个；按字符估算会
 * 系统性低估中文，真实读数补上这个偏差。服务商明确报溢出时不看阈值，直接做完整压缩。
 * 见 {@code docs/decisions/bug-fix/2026-09-30-context-window-comes-from-measurement.md}。
 *
 * <p>摘要只能由模型生成，这是整套流程里唯一一次为记账花的钱；不压缩的话，超出上限的
 * 那一轮会整体失败，用户丢掉整段对话。
 */
@Component
public class ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    /** 估算用的字符/token 比，只用于判断该不该压，不用于计费。 */
    private static final int CHARS_PER_TOKEN = 4;

    /**
     * 给模型的输出预留多少窗口。摘要本身也要发出去、也要占地方，不先扣掉它，
     * 就会在“压完还是装不下”的边缘反复触发。
     */
    private static final int RESERVED_OUTPUT_CAP = 20_000;

    /**
     * 完整压缩的水位：窗口的八成。
     *
     * <p>不贴着上限压：压完之后这一轮还要塞入预测不准的工具结果，贴着上限压很可能
     * 立刻又超限。
     */
    private static final double FULL_COMPACT_TRIGGER_RATIO = 0.8;

    /**
     * 水位离上限至少要留多少。这个数是 deepseek-harness 照 1M 窗口定的原值：
     * 完整压缩阈值取“窗口 × 0.8”与“窗口 − 预留输出 − 它”中较小的一个，大窗口下
     * 赢的总是前者。
     *
     * <p>照抄会对小窗口算出负数（64k 窗口减掉 65,536 就没了），于是“该不该压”永远为真，
     * 每一轮都在压。所以这里按窗口封顶，见 {@link #headroomOf}。
     */
    private static final int HEADROOM_TOKENS = 65_536;

    /** 余量最多占窗口的四分之一，防止 {@link #HEADROOM_TOKENS} 在小窗口上算出负数。 */
    private static final int HEADROOM_DIVISOR = 4;

    /** 生成摘要那一次调用的输出上限。摘要不该长到和原文差不多。 */
    private static final int SUMMARY_MAX_TOKENS = 4_096;

    /**
     * 微压缩的水位。低于它什么都不做；它和完整压缩阈值之间那一带才轮到清旧结果。
     *
     * <p>取 0.6 是折中：太低会频繁清内容，每次清都让缓存前缀失效；太高则清完没多少
     * 余量，下一次又得完整压缩。
     */
    private static final double MICROCOMPACT_TRIGGER_RATIO = 0.6;

    /** 最近这些个结果留着不动：模型刚看过的内容，很可能马上还要用。 */
    private static final int KEEP_RECENT_RESULTS = 5;

    /**
     * 正文可以清的工具有哪些。
     *
     * <p>{@code edit_file} / {@code write_file} 刻意不在内：它们的结果是“我改了什么”的
     * 凭据，清掉之后模型会忘记自己动过哪些文件，而那正是下一步判断的依据。
     * 读来的内容可以再读，改过的内容记不住就麻烦了。
     *
     * <p>联网的两个在内：抓回来的正文是最大的一类，而且可以再搜、再抓。清掉正文不等于
     * 丢掉事实，模型仍看得到自己搜过什么，只是内容要重取。
     */
    private static final Set<String> CLEARABLE_TOOLS = Set.of(
            ToolNames.READ_FILE, ToolNames.GREP, ToolNames.GLOB,
            ToolNames.RUN_COMMAND, ToolNames.WEB_SEARCH, ToolNames.WEB_FETCH);

    /**
     * 连续失败到这个次数就不再尝试。
     *
     * <p>压缩是优化，不是必需品；一直试一直败只会每轮多烧一次调用，而上下文该装不下
     * 还是装不下。熔断省下的是纯浪费。
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /**
     * 摘要提示词。
     *
     * <p>两段式（{@code <analysis>} 草稿 + {@code <summary>} 结论）：先写思考过程可以
     * 提高摘要质量，而草稿随后被剥掉、不进上下文。
     *
     * <p>“用户纠正过你什么”单独列出来，因为它最容易被漏掉，而漏掉它的后果最严重：
     * 模型会重犯一个已经被纠正过的错。
     */
    private static final String SUMMARY_PROMPT = """
            请把**上面那些消息**压缩成一份摘要，供之后接着干活时使用。

            注意：**这条指令本身不属于要总结的对话，不要把它写进摘要里。**
            （第一次拿真实模型验证时就这么干了，摘要结尾多出一句
            "当前要求：把以上对话压缩成一份摘要……"，纯属噪音。）

            先在一个 <analysis> 块里逐条梳理一遍（这部分之后会被丢掉，只是帮你想清楚）：
            用户提过哪些要求、做过哪些决定、改过哪些文件、遇到过什么错、怎么修的、
            以及**用户纠正过你什么**。

            然后在 <summary> 块里给出摘要，要写得具体，包含：
            1. 用户的目标与明确要求
            2. 涉及的文件路径、函数/方法签名、关键代码片段（别写"改了一个文件"这种）
            3. 遇到并修复过的错误
            4. 用户给过的纠正与反馈
            5. 还没做完的事
            6. 下一步该做什么

            只输出文本，**不要调用任何工具** —— 你需要的一切都在上面的对话里。
            """;

    /** 剥掉草稿块。{@code DOTALL} 是必须的：草稿里必然有换行。 */
    private static final Pattern ANALYSIS_BLOCK =
            Pattern.compile("<analysis>.*?</analysis>", Pattern.DOTALL);

    private final EventStore events;
    private final LlmClientProvider clients;
    private final SessionWriter writer;

    /**
     * 装配历史用注入的那一个，不自己造一份：它的配置只在
     * {@link ContextAssemblyConfig} 一处定。自己造一份会得到第二套配置，差别在代码里
     * 看不出来 —— 其中一份只会写“协作者”，而那份输入会被写进摘要、顶替整段历史。
     */
    private final ContextAssembler assembler;

    /**
     * 每条会话连续失败了几次，见 {@link #compactIfNeeded}。
     *
     * <p>只放在内存里，重启清零即可：熔断是当下的止损，不是一笔账；多实例各算各的
     * 也可以接受。
     */
    private final Map<SessionId, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    public ContextCompactor(EventStore events,
                            LlmClientProvider clients,
                            SessionWriter writer,
                            ContextAssembler assembler) {
        this.events = events;
        this.clients = clients;
        this.writer = writer;
        this.assembler = assembler;
    }

    /**
     * 压缩的触发来源。两者的证据来源不同，决定要不要看阈值：一个是我们估的，
     * 一个是服务商直接说的。
     */
    public enum Trigger {
        /** 按阈值判：估算值过了线才压。绝大多数时候是它。 */
        PRESSURE,

        /**
         * 服务商明确说这次装不下了。
         *
         * <p>此时不看阈值，也不先试清旧结果，直接做完整压缩。阈值是估算的，而服务商
         * 刚刚给出了确定的信号；拿可能错的估算去否决它，是把两边的可靠性搞反了。
         * 而且这是失败后的补救，只有一次机会，省那次调用可能换来下一轮再撞一次。
         */
        OVERFLOW
    }

    /**
     * 按触发条件压缩上下文；调用点必须在一轮开始之前、组装这一轮上下文之前。
     *
     * <p>压缩失败不会让这一轮失败：它只是优化，异常被记下并吞掉，连续失败达到
     * {@link #MAX_CONSECUTIVE_FAILURES} 后熔断。
     *
     * @param session      要压缩的会话
     * @param trigger      触发来源；{@link Trigger#OVERFLOW} 时跳过阈值判断
     * @param cancellation 取消信号
     * @param token        持有中的执行租约，用于写入压缩事件
     * @return 实际写入的压缩事件；没有压缩时为空
     */
    public Optional<SessionWriter.Written> compactIfNeeded(Session session,
                                                           Trigger trigger,
                                                           CancellationToken cancellation,
                                                           LeaseToken token) {
        // 先看一手读数，绝大多数轮次到这儿就结束。它会漏掉的只有“上一轮收尾之后
        // 新添的内容”：压缩点在一轮开始、用户那句话还没落库，中间只隔着几条状态事件。
        if (trigger == Trigger.PRESSURE) {
            OptionalInt measured = events.lastContextTokens(session.id());
            if (measured.isPresent() && measured.getAsInt() < microThreshold(session)) {
                consecutiveFailures.remove(session.id());
                return Optional.empty();
            }
        }

        List<StoredEvent> history = events.readAll(session.id());
        if (history.isEmpty()) {
            return Optional.empty();
        }
        int estimated = estimatedTokens(history, session);
        int fullThreshold = fullThreshold(session);
        log.debug("会话 {} 的上下文约 {} token，完整压缩阈值 {}（窗口 {}），触发：{}",
                session.id(), estimated, fullThreshold,
                ModelCapabilitiesResolver.resolve(session.model().modelId()).contextWindow(),
                trigger);

        // 阈值只在按压力判断时看。溢出时服务商已经给出确定信号，而估算器可能出错，
        // 不能拿估算去否决它。
        if (trigger == Trigger.PRESSURE) {
            if (estimated < microThreshold(session)) {
                // “还早”是正常状态，顺手清掉之前的失败计数
                consecutiveFailures.remove(session.id());
                return Optional.empty();
            }
            // 先试便宜的那一档：清旧结果不调模型、不花额外的钱。
            if (estimated < fullThreshold) {
                return clearOldResults(session, history, token);
            }
            if (!shouldCompact(history, session)) {
                consecutiveFailures.remove(session.id());
                return Optional.empty();
            }
        }
        if (consecutiveFailures.getOrDefault(session.id(), 0) >= MAX_CONSECUTIVE_FAILURES) {
            return Optional.empty();
        }
        // 取不到客户端就压不了。这里不报错：这一轮本来也会在别处因同一原因失败，
        // 在那里说清楚就够了。
        Optional<LlmClient> client = clients.findClient(session.ownerId(), session.model());
        if (client.isEmpty()) {
            return Optional.empty();
        }

        try {
            String summary = summarize(client.get(), history, session, cancellation);
            // 水位线取当前最后一条：这次把已有历史整个总结掉，之后的新事件照常投影。
            long watermark = history.getLast().seq();
            consecutiveFailures.remove(session.id());
            log.info("会话 {} 的上下文已压缩到 seq={}，摘要 {} 字",
                    session.id(), watermark, summary.length());
            return Optional.of(writer.append(session,
                    new ContextCompacted(watermark, summary), token));
        } catch (RuntimeException e) {
            // 压缩失败绝不能拖垮这一轮：不压缩照样能跑，异常穿出去只会让用户看到
            // 一次与真正原因无关的失败。
            consecutiveFailures.merge(session.id(), 1, Integer::sum);
            log.warn("会话 {} 的上下文压缩失败（第 {} 次），这一轮不压缩继续",
                    session.id(), consecutiveFailures.get(session.id()), e);
            return Optional.empty();
        }
    }

    private boolean shouldCompact(List<StoredEvent> history, Session session) {
        return estimatedTokens(history, session) >= fullThreshold(session);
    }

    /**
     * 完整压缩的阈值：窗口的八成，且不得离上限近过预留输出加余量，两者取严。
     *
     * <p>窗口以 {@link ModelCapabilitiesResolver} 为准；静态查表，不注入 bean。
     *
     * @param session 要判断的会话
     * @return 触发完整压缩的 token 数
     */
    static int fullThreshold(Session session) {
        ModelCapabilities capabilities = ModelCapabilitiesResolver.resolve(session.model().modelId());
        int window = capabilities.contextWindow();
        int reserved = Math.min(capabilities.maxOutputTokens(), RESERVED_OUTPUT_CAP);
        // 两条件取严的那一条：既不许超过窗口的八成，也不许离上限近过那个余量
        return Math.min((int) (window * FULL_COMPACT_TRIGGER_RATIO),
                window - reserved - headroomOf(window));
    }

    /** 余量：绝对值封顶到窗口的四分之一，见 {@link #HEADROOM_TOKENS}。 */
    private static int headroomOf(int window) {
        return Math.min(HEADROOM_TOKENS, window / HEADROOM_DIVISOR);
    }

    /** 微压缩的阈值：比完整压缩早一档，用来把那次花钱的调用往后推。 */
    private static int microThreshold(Session session) {
        ModelCapabilities model = ModelCapabilitiesResolver.resolve(session.model().modelId());
        return (int) (model.contextWindow() * MICROCOMPACT_TRIGGER_RATIO);
    }

    /**
     * 把旧的工具结果正文清掉。
     *
     * <p>不调模型、不生成摘要，比完整压缩便宜得多，代价是细节彻底没了。所以它排在
     * 完整压缩前面：能用它就不付那次调用的钱。
     *
     * @param session 会话
     * @param history 完整事件历史
     * @param token   执行租约
     * @return 清正文事件；没有可清的调用时为空
     */
    private Optional<SessionWriter.Written> clearOldResults(Session session,
                                                            List<StoredEvent> history,
                                                            LeaseToken token) {
        List<String> candidates = clearableCallIds(history);
        if (candidates.isEmpty()) {
            // 没有可清的（剩下的全是最近几个，或者全是 edit/write 的结果），
            // 只能等完整压缩。
            return Optional.empty();
        }
        log.info("会话 {} 为省上下文清掉了 {} 条旧工具结果的正文",
                session.id(), candidates.size());
        return Optional.of(writer.append(session, new ToolResultsCleared(candidates), token));
    }

    /**
     * 哪些调用的结果可以清。
     *
     * <p>要回溯工具名：{@code ToolResult} 只带 callId，当时请求的工具记在
     * {@code ToolCallRequested} 里。这里的 switch 用 {@code default} 是筛选而不是映射，
     * 漏掉一个类型只意味着不选它。
     *
     * @param history 完整事件历史
     * @return 可以清正文的 callId，按出现顺序，已排除最近保留的
     *         {@link #KEEP_RECENT_RESULTS} 个
     */
    private static List<String> clearableCallIds(List<StoredEvent> history) {
        Set<String> alreadyCleared = new HashSet<>();
        Map<String, String> toolByCall = new LinkedHashMap<>();
        for (StoredEvent stored : history) {
            switch (stored.event()) {
                case ToolCallRequested requested ->
                        toolByCall.put(requested.callId(), requested.toolName());
                case ToolResultsCleared cleared -> alreadyCleared.addAll(cleared.callIds());
                default -> {
                }
            }
        }

        List<String> clearable = toolByCall.entrySet().stream()
                .filter(entry -> CLEARABLE_TOOLS.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .filter(callId -> !alreadyCleared.contains(callId))
                .toList();
        // 最近的那几个留着：模型刚看过的内容，很可能马上还要用
        int keep = Math.min(KEEP_RECENT_RESULTS, clearable.size());
        return clearable.subList(0, clearable.size() - keep);
    }

    /**
     * 当前上下文大约占多少 token：按字符估算与上一轮真实读数取较大者。
     *
     * <p>“四字符一 token”照英文定，会系统性低估中文。真实读数在上一轮收尾时由服务商报出，
     * 不知道之后新增的内容；压缩会让它变小，但压缩后那一轮收尾会重新报一个更小的数。
     * 在两次读数之间内容只会往上加，所以它是当前规模的下界。取较大者，宁可早压，不能晚压。
     *
     * @param history 完整事件历史
     * @param session 会话
     * @return 估算的上下文 token 数
     */
    int estimatedTokens(List<StoredEvent> history, Session session) {
        return Math.max(byCharacters(history, session), lastMeasuredContext(history));
    }

    /**
     * 上一轮收尾时服务商报的上下文大小；找不到返回 0。
     *
     * <p>从后往前找第一条有读数的：被取消、或没调过模型的轮次，其用量事件里读数为
     * {@code null}，跳过它继续往前，拿到一个更旧但仍然成立的下界。
     * 0 表示“没有”，因为真实读数不可能真是 0，而 0 对与估算取大者无害。
     *
     * @param history 完整事件历史
     * @return 最近的上下文读数；没有时为 0
     */
    static int lastMeasuredContext(List<StoredEvent> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).event() instanceof TurnTokensUsed used && used.hasContext()) {
                return used.contextTokens();
            }
        }
        return 0;
    }

    /**
     * 按字符估：先投影成消息，再数字符。
     *
     * <p>不直接数事件 JSON：发出去的是投影结果，不进上下文的事件（checkpoint、用量、
     * 状态变化）不该算进来；工具调用参数要算，它们真的会发给模型。
     * 它系统性低估中文，只单独用于会话第一轮、还没有任何真实读数时。
     */
    private int byCharacters(List<StoredEvent> history, Session session) {
        // 数哪些字段只有一处（见 ContextAssembler.charactersOf），这里只把字符换成 token。
        long chars = ContextAssembler.charactersOf(
                assembler.assemble(history, session.model().systemPrompt()));
        return (int) Math.min(Integer.MAX_VALUE, chars / CHARS_PER_TOKEN);
    }

    /**
     * 让模型把这段历史总结掉。
     *
     * <p>用会话当前的同一个模型，不另配一个：换模型会让摘要的风格和口径与后续对话对不上，
     * 而用户配的那把 key 也只对应这个端点。
     */
    private String summarize(LlmClient client, List<StoredEvent> history, Session session,
                             CancellationToken cancellation) {
        // 工具清单传空：摘要不该动手，而且少传一份工具定义也少占上下文。
        LlmRequest request = new LlmRequest(session.model().modelId(), summaryInput(history, session),
                List.of(), SUMMARY_MAX_TOKENS);

        LlmResult result = client.stream(request, event -> {
        }, cancellation);
        return stripDraft(result.text());
    }

    /**
     * 喂给摘要模型的输入：整段历史加摘要指令。
     *
     * <p>它装出来的内容会顶替整段历史：摘要落下去之后，之前那些事件不再进上下文。
     * 所以“这句话是谁说的”必须正确，错一次之后每次调用都跟着错。
     *
     * @param history 完整事件历史
     * @param session 会话
     * @return 发给摘要模型的完整消息列表
     */
    List<LlmMessage> summaryInput(List<StoredEvent> history, Session session) {
        // 用 assembleInto 装进自己的可变 list；assemble() 返回不可变快照，add 会抛。
        List<LlmMessage> messages = new ArrayList<>();
        assembler.assembleInto(history, session.model().systemPrompt(), messages);
        messages.add(LlmMessage.user(SUMMARY_PROMPT));
        return messages;
    }

    /**
     * 剥掉 {@code <analysis>} 草稿块和 {@code <summary>} 标签，只留下摘要正文。
     *
     * <p>草稿只是用来让模型想清楚再写，留在摘要里会进入之后的每一次调用。
     * 模型没按格式返回（没有标签，或只有草稿）时退回原文：空摘要会让被压掉的对话
     * 彻底消失，比格式不整齐严重得多。
     */
    private static String stripDraft(String raw) {
        String withoutDraft = ANALYSIS_BLOCK.matcher(raw == null ? "" : raw).replaceAll("");
        String summary = withoutDraft
                .replace("<summary>", "")
                .replace("</summary>", "")
                .strip();
        return summary.isEmpty() ? (raw == null ? "" : raw.strip()) : summary;
    }
}
