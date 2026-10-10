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
 * 上下文压缩：历史太长时，把已有的一整段总结成一段话，之后不再原样送给模型。
 *
 * <h2>为什么必须先看清楚「值不值」</h2>
 * 压缩是**唯一**会破坏 prompt 缓存的常规操作。模型服务商的缓存按前缀匹配，
 * 命中价比未命中低得多 —— 而压缩把前缀整个换掉，下一次调用必然全价。
 * 所以它换来的好处（不被上下文上限卡死）必须大于这个代价。这也是为什么它得
 * 等到**真的快满了**才触发，而不是"看到有点长就压一压"。
 *
 * <h2>为什么要专门调一次模型</h2>
 * 摘要只能由模型生成，这是这一整套里唯一一次"为了记账而花的钱"。它花得值：
 * 不压缩的话，超出上限的那一轮**整个失败**，用户丢掉的是整段对话。
 *
 * <h2>触发看的是"估算和真实读数里较大的那个"</h2>
 * 这一轮要发的请求确实还没有真实数字，但**上一轮收尾时服务商亲口报过一个**
 * （{@code TurnTokensUsed.contextTokens}），而中间只隔了一条回复 —— 它是**手边就有的**。
 *
 * <p>这条读数还兼着一道**便宜的闸**：只要有值、又离水位还远，这一轮连历史都不用读
 * （见 {@link #compactIfNeeded}）。它会漏掉的只有"上一轮收尾之后新添的那点内容"，
 * 而压缩点在一轮开始、那时用户那句话**还没落库**。
 *
 * <p>两个参考实现（Claude Code、deepseek harness）都是这个方向，而且都不是"纯估算"：
 * Claude Code 的文档明说判断依据是**最近一次响应回报的 token 数**；
 * deepseek harness 是"真实用量当基准 + 只对增量用启发式估"，并且它自己的 README
 * 承认那个启发式低估中文。**按字符估单独用是会低估中文的**，
 * 详见 {@link #estimatedTokens}。
 */
@Component
public class ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    /** 估算用的字符/token 比。只是个判断"该不该压"的量级估计，不用于计费。 */
    private static final int CHARS_PER_TOKEN = 4;

    /**
     * 给模型的输出预留多少窗口。摘要本身也要发出去、也要占地方，所以判断阈值时
     * 得把它先扣掉，否则会在"压完还是装不下"的边缘反复触发。
     */
    private static final int RESERVED_OUTPUT_CAP = 20_000;

    /**
     * 完整压缩的水位：**窗口的八成**。deepseek-harness 的默认值，这里照用。
     *
     * <p>为什么不干脆贴着上限压：压完之后这一轮还要往里塞工具结果，而那些是
     * 预测不准的 —— 贴着上限压，很可能压完立刻又超上限。
     */
    private static final double FULL_COMPACT_TRIGGER_RATIO = 0.8;

    /**
     * 水位离上限至少要留多少。**这个数是 deepseek-harness 的原值**，
     * 而它是照 1M 的窗口定的：`窗口 × 0.8` 和 `窗口 − 预留 − 它` 取小的那个，
     * 大窗口下赢的总是前者，这一项只在窗口小到一定程度时才起作用。
     *
     * <p>照抄会踩到的坑：它对小窗口会算出**负数**（64k 的窗口减掉 65,536 就没了），
     * 于是"该不该压"永远为真 —— 每一轮都在压，比原来的毛病更糟。
     * 所以下面按窗口封顶（见 {@link #headroomOf}）。大窗口下它和 deepseek-harness 一模一样。
     */
    private static final int HEADROOM_TOKENS = 65_536;

    /** 余量最多占窗口的几分之一。见 {@link #HEADROOM_TOKENS} 里那个坑。 */
    private static final int HEADROOM_DIVISOR = 4;

    /** 生成摘要那一次调用的输出上限。摘要不该长到和原文差不多。 */
    private static final int SUMMARY_MAX_TOKENS = 4_096;

    /**
     * 低于这个比例**什么都不做**。两档之间的那一带才轮到微压缩。
     *
     * <p>取 0.6 是个折中：太低会频繁清内容（每次清都让缓存前缀失效），
     * 太高就来不及 —— 清完没多少余量，下一次又得完整压缩。
     */
    private static final double MICROCOMPACT_TRIGGER_RATIO = 0.6;

    /** 最近这些个结果**留着不动**：模型刚看过的内容，很可能马上还要用。 */
    private static final int KEEP_RECENT_RESULTS = 5;

    /**
     * 正文可以清的工具有哪些。
     *
     * <p>{@code edit_file} / {@code write_file} **刻意不在内**：它们的结果是
     * "我改了什么"的凭据，清掉之后模型会不记得自己动过哪些文件 ——
     * 而那正是它做下一步判断的依据。读来的东西可以再读一遍，改过的东西记不住就麻烦了。
     */
    private static final Set<String> CLEARABLE_TOOLS =
            Set.of("read_file", "grep", "glob", "run_command");

    /**
     * 连续失败到这个次数就不再试。
     *
     * <p>一直试一直败只会每轮多烧一次调用，而上下文该装不下还是会装不下 ——
     * 熔断省下的是纯浪费。Claude Code 那边给过数据：某天有 1279 个会话
     * 连续失败 50 次以上，全站每天白烧约 25 万次调用。
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /**
     * 摘要提示词。
     *
     * <p>两段式（{@code <analysis>} 草稿 + {@code <summary>} 结论）是从 Claude Code
     * 那边抄的：让模型先写一遍思考过程，质量明显更好，而**草稿随后被剥掉、不进上下文**。
     * 等于零成本地拿到一份更好的摘要。
     *
     * <p>"用户纠正过你什么"这一条单独列出来，是因为它最容易被漏掉，而漏掉它的后果
     * 恰恰最严重 —— 模型会重犯一个它已经被纠正过的错。
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
     * 装配历史那一个 —— **注入进来的**，不自己造一份。
     *
     * <p>它的配置（有没有"按 id 查用户名"那一层）只在装配它的地方定，见
     * {@code ContextAssemblyConfig}。这里自己造一份的话，那就是"同一个概念的第二套配置"
     * 的起点 —— 而它俩的差别**在代码里看不出来**：装出来的东西一个带着谁说的、一个带着
     * "协作者"，而后者会被写进摘要、**顶替整段历史**。
     */
    private final ContextAssembler assembler;

    /**
     * 每条会话连续失败了几次，见 {@link #compactIfNeeded}。
     *
     * <p>**只放在内存里**，重启就清零 —— 那没关系：熔断是"当下止损"，不是一笔账。
     * 为此建一张表不划算，而多实例下各算各的也完全可以接受（它本来就是近似的止损）。
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
     * 为什么要压。
     *
     * <p>两种触发的**证据来源不一样**，而这决定了要不要看阈值：
     * 一个是我们自己估的，一个是 provider 直接说的。
     */
    public enum Trigger {
        /** 按阈值判：估算值过了线才压。绝大多数时候是它。 */
        PRESSURE,

        /**
         * **provider 明确说这次装不下了。**
         *
         * <p>此时**不看阈值、也不走"先试便宜的那一档"** —— 直接做最狠的那一步（摘要）。
         * 两个理由：
         * <ol>
         *   <li>阈值是我们**估**的，而它刚刚**说**了。拿一个可能错的估算去否决一个
         *       确实的信号，是把两边的可靠性搞反了；</li>
         *   <li>这是失败之后的一次补救，只有一次机会 —— 省那一次摘要调用的钱，
         *       换来的可能是下一轮再撞一次墙。</li>
         * </ol>
         *
         * <p>deepseek-harness 的做法一样：溢出这条路"不需要容量元数据，
         * 也不走标量压力线和常规的保留预算"。
         */
        OVERFLOW
    }

    /**
     * 该压就压。
     *
     * <p>调用点在**一轮开始之前**：压缩会改变这一轮模型看到的历史，
     * 所以必须赶在组装上下文之前做完。
     *
     * @return 真压了的话返回这次写入；没压返回空
     */
    public Optional<SessionWriter.Written> compactIfNeeded(Session session,
                                                           Trigger trigger,
                                                           CancellationToken cancellation,
                                                           LeaseToken token) {
        // **先看一手读数，再决定要不要读整条流。** 绝大多数轮次到这儿就结束了 ——
        // 估算的另一个输入（按字符估）系统性低估中文，所以真正拍板的通常就是这个读数。
        //
        // 它会漏掉的只有"上一轮收尾之后新添的内容"：压缩点在一轮开始、用户那句话还没落库
        //（见 TurnExecutor 里那段"为什么压缩必须排在落库之前"），中间只隔着几条状态变更事件。
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

        // **阈值那一整套只在"按压力判"时看。**
        //
        // 溢出时不能看：provider 刚刚已经给过答案了，而我们的估算器恰恰是可能错的那一个
        //（我们错过一次，窗口常量差了 15 倍）。这时候再去问"到我算的线了吗"，
        // 等于拿一个已知会错的判据去否决一个已知正确的信号。
        if (trigger == Trigger.PRESSURE) {
            if (estimated < microThreshold(session)) {
                // "还早"是个正常状态，顺手把之前的失败计数清掉
                consecutiveFailures.remove(session.id());
                return Optional.empty();
            }
            // **先试便宜的那一档**：清旧结果不调模型、不花额外的钱，只是丢掉一些细节。
            // 它撑得住就不用付完整压缩那一次调用的钱
            if (estimated < fullThreshold) {
                return clearOldResults(session, history, token);
            }
            if (!shouldCompact(history, session)) {
                consecutiveFailures.remove(session.id());
                return Optional.empty();
            }
        }
        // 连续失败太多次就别再试了。压缩是个**优化**，不是必需品 ——
        // 一直试一直败只会每轮多烧一次调用，而上下文该装不下还是会装不下
        if (consecutiveFailures.getOrDefault(session.id(), 0) >= MAX_CONSECUTIVE_FAILURES) {
            return Optional.empty();
        }
        // 取不到客户端就压不了。这里**不报错**：这一轮本来也会在别处因为同一个原因
        // 失败，在那里说清楚就够了，不必让压缩也喊一嗓子
        Optional<LlmClient> client = clients.findClient(session.ownerId(), session.model());
        if (client.isEmpty()) {
            return Optional.empty();
        }

        try {
            String summary = summarize(client.get(), history, session, cancellation);
            // 水位线取**当前最后一条**：这一次是把已有历史整个总结掉，
            // 之后的新事件照常投影
            long watermark = history.getLast().seq();
            consecutiveFailures.remove(session.id());
            log.info("会话 {} 的上下文已压缩到 seq={}，摘要 {} 字",
                    session.id(), watermark, summary.length());
            return Optional.of(writer.append(session,
                    new ContextCompacted(watermark, summary), token));
        } catch (RuntimeException e) {
            // **压缩失败绝不能拖垮这一轮。** 它只是个优化，不压缩照样能跑；
            // 让异常穿出去的话，用户看到的是一次莫名其妙的失败，而真正的原因
            // （生成摘要那一次调用没成）和他们关心的事毫无关系
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
     * 完整压缩的阈值：**窗口的八成，但不许离上限近过那个余量** —— 两条件取严的。
     *
     * <p>算式照搬 deepseek-harness。窗口常量曾经写成 64,000、而 DeepSeek 实际是 1,000,000，
     * 于是压缩在 9% 的地方就触发了 —— 窗口以 {@link ModelCapabilitiesResolver} 为准。
     *
     * <p>静态查表，不是注入的 bean —— 模型能力是一份写死的注册表，
     * 和 {@code TurnExecutor} 组装 {@code TurnInput} 时用的是同一个入口。
     */
    static int fullThreshold(Session session) {
        ModelCapabilities capabilities = ModelCapabilitiesResolver.resolve(session.model().modelId());
        int window = capabilities.contextWindow();
        int reserved = Math.min(capabilities.maxOutputTokens(), RESERVED_OUTPUT_CAP);
        // 两条件取严的那一条：既不许超过窗口的八成，也不许离上限近过那个余量
        return Math.min((int) (window * FULL_COMPACT_TRIGGER_RATIO),
                window - reserved - headroomOf(window));
    }

    /** 余量：绝对值封顶到窗口的几分之一。见 {@link #HEADROOM_TOKENS}。 */
    private static int headroomOf(int window) {
        return Math.min(HEADROOM_TOKENS, window / HEADROOM_DIVISOR);
    }

    /** 微压缩的阈值：比完整压缩早一档，用来把那次花钱的调用往后推。 */
    private static int microThreshold(Session session) {
        ModelCapabilities model = ModelCapabilitiesResolver.resolve(session.model().modelId());
        return (int) (model.contextWindow() * MICROCOMPACT_TRIGGER_RATIO);
    }

    /**
     * 把**旧的**工具结果正文清掉。
     *
     * <p>不调模型、不生成摘要，所以它比完整压缩便宜得多 —— 代价是细节彻底没了。
     * 正因为它便宜，才排在完整压缩**前面**：能用它就别付那次调用的钱。
     */
    private Optional<SessionWriter.Written> clearOldResults(Session session,
                                                            List<StoredEvent> history,
                                                            LeaseToken token) {
        List<String> candidates = clearableCallIds(history);
        if (candidates.isEmpty()) {
            // 没有可清的（剩的全是最近几个，或者全是 edit/write 的结果）——
            // 那就只能等完整压缩，这里什么都不做
            return Optional.empty();
        }
        log.info("会话 {} 为省上下文清掉了 {} 条旧工具结果的正文",
                session.id(), candidates.size());
        return Optional.of(writer.append(session, new ToolResultsCleared(candidates), token));
    }

    /**
     * 哪些调用的结果可以清。
     *
     * <p>要**回溯工具名**：{@code ToolResult} 只带 callId，"当时请求的是什么工具"
     * 记在 {@code ToolCallRequested} 那一条里。这也正是不该就地改结果的原因之一 ——
     * 判断"能不能清"需要的信息根本不在那一条里。
     *
     * <p>这里的 switch 用了 {@code default}，和别处的"穷尽列出"不同：
     * 它是**筛选**而不是映射，漏掉一个类型的结果只是"不选它"，而它本来也不在名单上。
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
     * 当前上下文大约占多少 token。**两个数取较大的那个。**
     *
     * <h2>为什么要两个</h2>
     * 一个数是按字符估出来的（见 {@link #byCharacters}），一个是**上一轮收尾时服务商
     * 亲口报的**（见 {@link #lastMeasuredContext}）。
     *
     * <p>光有估算是不够的，而且**偏差方向是固定的**：{@code CHARS_PER_TOKEN} 那个
     * "四个字符一个 token"是照英文定的，而中文一个字差不多就是一个 token ——
     * 于是它系统性地**低估**中文会话。deepseek harness 也栽在这儿：它的 README 里自己写着
     * 这个比例 "underprices CJK text"，而它的解药正是下面这个真实值。
     *
     * <h2>为什么是取大的，不是用真实值替换估算</h2>
     * 因为那个真实值是**上一轮结束时**的，不是现在的：这一轮之后新增的内容（最后那条
     * 回复、新一轮的用户消息）它不知道。但它有一个很好的性质 ——
     * **内容只会往上加**（压缩会让它变小，而压缩之后那一轮收尾会重新报一个更小的数），
     * 所以它永远是当前规模的一个**下界**。
     *
     * <p>两个都拿上、取大的那个，得到的下界就是"永远不比现在更低估"：
     * 中文会话里真值把估算顶上去，估算正常时它也不吃亏。deepseek-harness 里那道守卫
     * （只在用量不小于估算时才采纳用量）是同一个意思 —— 宁可早压，不能晚压。
     *
     * <p>剩下的误差只有"上一轮结束之后新添的那点内容"，而它相对整段历史很小；
     * 下一轮收尾时又会被一个新的真值覆盖掉。
     */
    int estimatedTokens(List<StoredEvent> history, Session session) {
        return Math.max(byCharacters(history, session), lastMeasuredContext(history));
    }

    /**
     * 上一轮收尾时，服务商报的上下文有多大。找不到就是 0。
     *
     * <p>**从后往前找第一条有读数的**，而不是只看最后一条：一轮可能是被取消的、
     * 或者压根没调过模型 —— 那种轮次的用量事件里这个数是 {@code null}（没有读数）。
     * 跳过它、继续往前找，拿到的是一个更旧但仍然成立的下界 —— 总比没有强。
     *
     * <p>返回 {@code int} 而 0 表示"没有"，是因为这里只拿它跟估算值取大的
     * （见 {@code estimatedTokens}）：0 对那个比较是无害的，而真实读数不可能真是 0。
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
     * 按字符估：**先投影成消息，再数字符**。
     *
     * <p>做法和真实调用时一样，不直接数事件的 JSON —— 因为发出去的是投影的结果，
     * 那些不进上下文的事件（checkpoint、用量、状态变化）不该被算进来。
     * 工具调用的参数也要算：它们真的会发给模型。
     *
     * <p>它**系统性低估中文**（见 {@link #estimatedTokens}），所以从来不是单独用的那个数；
     * 单独用它只发生在会话的第一轮，那时还没有任何真实读数。
     */
    private int byCharacters(List<StoredEvent> history, Session session) {
        int chars = 0;
        for (LlmMessage message : assembler.assemble(history, session.model().systemPrompt())) {
            chars += message.content() == null ? 0 : message.content().length();
            for (ToolCall call : message.toolCalls()) {
                chars += call.argumentsJson().length();
            }
        }
        return chars / CHARS_PER_TOKEN;
    }

    /**
     * 让模型把这段历史总结掉。
     *
     * <p>用**同一个模型**，不另配一个：换模型意味着这份摘要的风格和口径都和后续
     * 对话对不上，而用户配的那把 key 也只对应这个端点。
     */
    private String summarize(LlmClient client, List<StoredEvent> history, Session session,
                             CancellationToken cancellation) {
        // 工具清单传空：摘要这件事不该动手，而且少传一份工具定义也少占一点上下文
        LlmRequest request = new LlmRequest(session.model().modelId(), summaryInput(history, session),
                List.of(), SUMMARY_MAX_TOKENS);

        LlmResult result = client.stream(request, event -> {
        }, cancellation);
        return stripDraft(result.text());
    }

    /**
     * 喂给摘要模型的那份输入：整段历史 + 摘要指令。
     *
     * <p>**它装出来的东西会顶替整段历史** —— 摘要落下去之后，之前那些事件就不再进上下文了。
     * 所以"这句话是谁说的"在这里就必须是对的：错一次，之后每一次调用都跟着错，
     * 而且看不出是从哪儿错起的。
     */
    List<LlmMessage> summaryInput(List<StoredEvent> history, Session session) {
        // 用 assembleInto 装进自己这个可变 list，**不要**用 assemble()：
        // 后者返回的是不可变快照（那是它对外承诺的契约），往上面 add 会当场抛
        List<LlmMessage> messages = new ArrayList<>();
        assembler.assembleInto(history, session.model().systemPrompt(), messages);
        messages.add(LlmMessage.user(SUMMARY_PROMPT));
        return messages;
    }

    /**
     * 剥掉 {@code <analysis>} 草稿块和 {@code <summary>} 标签，只留下摘要正文。
     *
     * <p>草稿是**故意让它写**的（想了再写质量更好），但它没有留下来的价值 ——
     * 留在摘要里等于把"我思考的过程"也塞进之后的每一次调用。
     */
    private static String stripDraft(String raw) {
        String withoutDraft = ANALYSIS_BLOCK.matcher(raw == null ? "" : raw).replaceAll("");
        String summary = withoutDraft
                .replace("<summary>", "")
                .replace("</summary>", "")
                .strip();
        // 模型没按格式来时（没有标签、或者只给了草稿），退回到原文 ——
        // 空摘要会让被压掉的那段对话彻底消失，那比格式不整齐严重得多
        return summary.isEmpty() ? (raw == null ? "" : raw.strip()) : summary;
    }
}
