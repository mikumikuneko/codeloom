package com.codeloom.agent.context;

import com.codeloom.agent.llm.LlmMessage;
import com.codeloom.agent.llm.ToolCall;
import com.codeloom.domain.event.ContextCompacted;
import com.codeloom.domain.event.SessionRewound;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.TodoListUpdated;
import com.codeloom.domain.event.ToolResultsCleared;
import com.codeloom.domain.user.UserId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 把会话的事件流投影成发给模型的消息列表。
 *
 * <p>投影必须是确定性的：同一份事件流在任何时刻装配出的消息逐字符相同，否则模型服务商的
 * 前缀缓存会失效。因此它不读当前时间（时钟取事件自带的 {@code occurredAt}）、不用随机数；
 * 事件通常只向尾部追加消息；压缩、清理旧工具结果和回滚会改写已经投出去的消息，
 * 审批答复也会在原地替换占位工具消息。
 *
 * <p>{@link Projection} 负责跨轮推进，{@link MessageFold} 负责把一批事件折成消息。
 * 状态变化、checkpoint、验证结论和用量等事件不进上下文，但仍然要落库。
 */
public final class ContextAssembler {

    /**
     * 按 id 查用户名。只在投影别人捎来的留言时调用；增量推进不会重算已折进消息的名字，
     * 但整条流重建时会按当时用户名重新折入。查不到时由 {@code MessageFold} 用「协作者」兜底。
     */
    private final Function<UserId, String> displayNameOf;

    /**
     * @param displayNameOf 按 id 查用户名；不要名字的调用方明写 {@code id -> null}。
     *                      没有默认缺省：漏掉这一层不会报错，只会让模型分不清用户和协作者
     */
    public ContextAssembler(Function<UserId, String> displayNameOf) {
        this.displayNameOf = Objects.requireNonNull(displayNameOf, "displayNameOf");
    }

    /**
     * 把整条事件流装配成消息列表。
     *
     * @param events       按 seq 升序的完整事件流
     * @param systemPrompt 会话级系统提示词；不能放每次都变的内容（如当前时间戳），否则每轮缓存未命中
     * @return 不可变的消息列表
     */
    public List<LlmMessage> assemble(List<StoredEvent> events, String systemPrompt) {
        List<LlmMessage> messages = new ArrayList<>();
        assembleInto(events, systemPrompt, messages);
        // 返回值不可变是这个类的契约
        return List.copyOf(messages);
    }

    /**
     * 把整条事件流装进调用方给的列表，先清空该列表。
     *
     * @param events       按 seq 升序的完整事件流
     * @param systemPrompt 会话级系统提示词
     * @param messages     输出列表
     */
    public void assembleInto(List<StoredEvent> events, String systemPrompt,
                             List<LlmMessage> messages) {
        projection(systemPrompt).advance(events, messages);
    }

    /**
     * 开一条可以跨轮推进的投影：只折 seq 比上次大的事件，已经投出的消息留在内存里。
     *
     * <p>压缩、清理旧工具结果和回滚会改写已投出的消息，无法增量折叠；遇到它们时从整条流重建。
     * 审批答复替换占位工具消息不在此列，它可以在原地增量处理。
     * 推进的坐标是 seq，不是“第几条”。
     *
     * @param systemPrompt 会话级系统提示词
     * @return 新投影
     */
    public Projection projection(String systemPrompt) {
        return new Projection(systemPrompt, displayNameOf, null);
    }

    /**
     * 同上，并给重建时取整条流的入口。分页喂事件的调用方必须用它：重建时需要整条流，
     * 手里那片会让前面的事件静默消失。见 {@link Projection#fold}。
     *
     * @param systemPrompt 会话级系统提示词
     * @param wholeStream  每次重建调用一次，必须返回当时最新的整条流，而不是事先拍下的快照
     * @return 新投影
     */
    public Projection projection(String systemPrompt, Supplier<List<StoredEvent>> wholeStream) {
        return new Projection(systemPrompt, displayNameOf,
                Objects.requireNonNull(wholeStream, "wholeStream"));
    }

    /** UTF-16 字符按最坏情况占 2 字节，用于内存占用上界。 */
    private static final int BYTES_PER_CHAR_WORST_CASE = 2;

    /**
     * 一串消息的字符量：正文加工具调用参数。压缩按 token、内存淘汰按字节，
     * 但两边统计的字段集合以这里为唯一口径。
     *
     * @param messages 要统计的消息
     * @return 字符数
     */
    public static long charactersOf(List<LlmMessage> messages) {
        long chars = 0;
        for (LlmMessage message : messages) {
            if (message.content() != null) {
                chars += message.content().length();
            }
            for (ToolCall call : message.toolCalls()) {
                chars += call.argumentsJson().length();
            }
        }
        return chars;
    }

    /** 一条可以往前推进的投影，见 {@link #projection(String)}。 */
    public static final class Projection {

        private final String systemPrompt;

        /** 与 {@link ContextAssembler#displayNameOf} 相同；静态嵌套类各持一份引用。 */
        private final Function<UserId, String> displayNameOf;

        /** 重建时取整条流的来源；null 表示调用方保证每次递进来的都是整条流或它的前缀。 */
        private final Supplier<List<StoredEvent>> wholeStream;
        /** 已处理事件的最大 seq；0 = 尚未处理（seq 从 1 开始，见 {@code EventEnvelope}）。 */
        private long foldedUpTo;
        /** 把事件折成消息；重建时替换为新实例（见 {@link #begin}）。 */
        private MessageFold messageFold;
        /** 投影产出，只向尾部追加；前缀逐字节不变是前缀缓存的前提。 */
        private final List<LlmMessage> messages = new ArrayList<>();
        /** 压缩水位线：seq 不超过它的事件已被摘要替换，不再投影；0 = 未压缩。 */
        private long watermark;
        /** 这条对话里 {@code read_file} 读过哪些文件，见 {@link #readPaths()}。 */
        private final ReadPathTracker reads = new ReadPathTracker();
        /** 尾部那次已批准、但还没补跑的调用，见 {@link #pendingApprovedCall()}。 */
        private final PendingCallTracker approvals = new PendingCallTracker();

        /**
         * 全部派生事实。折事件与重建都只遍历这一份清单，新增一份事实时不会漏掉其中一处；
         * 各事实之间没有依赖，顺序无关。
         */
        private final List<Derived> derived = List.of(reads, approvals);

        private Projection(String systemPrompt, Function<UserId, String> displayNameOf,
                           Supplier<List<StoredEvent>> wholeStream) {
            this.systemPrompt = systemPrompt;
            this.displayNameOf = displayNameOf;
            this.wholeStream = wholeStream;
        }

        /**
         * 投影已处理事件的最大 seq；0 = 尚未处理。自己给事件编号的调用方（单测、批处理）
         * 必须从这里接着编，否则新事件会被 {@link #fold(List)} 当成旧的跳过。
         * 它表示“喂到哪儿了”，不产出消息的事件也算处理过。
         *
         * @return 已处理的最大 seq
         */
        public long foldedUpTo() {
            return foldedUpTo;
        }

        /**
         * 这串消息大致占多少字节，用于给内存占用画上界，不求精确。按每字符 2 字节的最坏情况估算：
         * 高估只会导致早淘汰一次，低估会让内存没有边界。
         *
         * @return 字节数上界
         */
        public long approximateSizeBytes() {
            return charactersOf(messages) * BYTES_PER_CHAR_WORST_CASE;
        }

        /**
         * 这条对话里读过哪些文件（工作区相对路径）。它是“读过才许覆盖”那道门的输入。
         *
         * @return 工作区相对路径集合
         */
        public Set<String> readPaths() {
            return reads.paths();
        }

        /**
         * 上一轮里已批准、但还没补跑的那次调用；没有就是空。
         * 判据见 {@link PendingCallTracker}。
         *
         * @return 待补跑的调用
         */
        public Optional<ToolCall> pendingApprovedCall() {
            return approvals.pendingApprovedCall();
        }

        /**
         * 折入新事件，把当前上下文写进 {@code sink}（先清空）。
         *
         * @param events 同一条不断变长的事件流；只处理 seq 比上次大的
         * @param sink   输出列表
         */
        public void advance(List<StoredEvent> events, List<LlmMessage> sink) {
            fold(events);

            sink.clear();
            sink.addAll(messages);
        }

        /**
         * 只折入新事件，不交出当前上下文；之后要发给模型时再调用 {@link #advance}。
         *
         * <p>{@code events} 可以只是一片。遇到改写类事件需要重建时，有整条流来源就用来源重新取，
         * 没有就只能在 {@code events} 上重建——后者只在“递进来的就是整条流”时成立。见
         * {@code docs/decisions/bug-fix/2026-10-10-projection-rebuild-wants-the-whole-stream.md}。
         *
         * @param events 新事件，或分页递进来的一片
         */
        public void fold(List<StoredEvent> events) {
            List<StoredEvent> fresh = new ArrayList<>();
            for (StoredEvent stored : events) {
                if (stored.seq() > foldedUpTo) {
                    fresh.add(stored);
                }
            }
            List<StoredEvent> baseline = null;
            if (rewritesHistory(fresh)) {
                startOver();
                // 重建必须重折整条流；分页递进来的这一片不是整条流，直接拿它会静默丢掉前面的段。
                baseline = wholeStream != null ? wholeStream.get() : events;
                fresh = baseline;
            }
            if (messageFold == null) {
                // 重建时必须拿整条流（baseline）；第一次建立投影时先把递进来的这一段当起点，
                // 后续遇到压缩/清理会再触发重建，那时才向 wholeStream 要全量
                begin(baseline != null ? baseline : events);
            }

            for (StoredEvent stored : fresh) {
                // 派生事实接收所有事件，回滚由各自消化（见 Derived）。
                for (Derived fact : derived) {
                    fact.accept(stored);
                }
                // 水位线之前的清单不在这里折：begin 已把最新那份带到摘要之后，更早的由摘要代表。
                if (stored.seq() > watermark) {
                    messageFold.accept(stored);
                }
                foldedUpTo = stored.seq();
            }
            messageFold.flush();
            messageFold.closeOpenCalls();
        }

        /** 压缩、清理旧工具结果和回滚会改写已投出的消息，无法增量折叠；审批答复的替换可以。 */
        private static boolean rewritesHistory(List<StoredEvent> fresh) {
            return fresh.stream().anyMatch(stored -> stored.event() instanceof ContextCompacted
                    || stored.event() instanceof ToolResultsCleared
                    || stored.event() instanceof SessionRewound);
        }

        /**
         * 把投影退回“一条都还没处理”。清完之后下一次喂进来的必须是整条流；
         * {@link #fold(List)} 会在清完立刻重建。
         */
        private void startOver() {
            messages.clear();
            messageFold = null;
            foldedUpTo = 0;
            watermark = 0;
            derived.forEach(Derived::clear);
        }

        /**
         * 从头建立投影：系统提示词、压缩摘要、水位线和正文被清过的调用集合在这里确定。
         * 派生事实不在这里折，由 {@link #fold(List)} 统一遍历。
         *
         * @param events 整条事件流
         */
        private void begin(List<StoredEvent> events) {
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(LlmMessage.system(systemPrompt));
            }
            // 被清过正文的调用必须在遍历之前扫出：清理事件排在被清结果之后，边遍历边应用会回头改已投出的消息。
            messageFold = new MessageFold(messages, clearedCallIds(events), displayNameOf);

            // 压缩位置决定从哪个 seq 开始投影，同样必须在遍历之前确定。
            Compaction compaction = lastCompaction(events);
            if (compaction != null) {
                messageFold.acceptSummary(compaction.summary());
                watermark = compaction.droppedUpToSeq();
                // 水位线前的旧快照由摘要代表，只带最新一份到摘要之后。
                latestTodoAtOrBefore(events, watermark).ifPresent(messageFold::accept);
            }
        }

        /**
         * 水位线之前（含）最后一份清单快照。
         *
         * @param events    事件流
         * @param watermark 压缩水位线
         * @return 最新一份清单快照；没有就是空
         */
        private static Optional<StoredEvent> latestTodoAtOrBefore(List<StoredEvent> events, long watermark) {
            StoredEvent latest = null;
            for (StoredEvent stored : events) {
                if (stored.seq() > watermark) {
                    break;
                }
                if (stored.event() instanceof TodoListUpdated) {
                    latest = stored;
                }
            }
            return Optional.ofNullable(latest);
        }

        /**
         * 从事件流里找出最后一次压缩。后一条的摘要已经涵盖前一次，用早期那条会丢掉后来的内容。
         *
         * @param events 事件流
         * @return 最后一次压缩；没有就是 null
         */
        private static Compaction lastCompaction(List<StoredEvent> events) {
            Compaction latest = null;
            for (StoredEvent stored : events) {
                if (stored.event() instanceof ContextCompacted(long droppedUpToSeq, String summary)) {
                    latest = new Compaction(droppedUpToSeq, summary);
                }
            }
            return latest;
        }

        private record Compaction(long droppedUpToSeq, String summary) {
        }

        /**
         * 扫出所有被清过正文的调用 id。
         *
         * @param events 事件流
         * @return 调用 id 集合
         */
        private static Set<String> clearedCallIds(List<StoredEvent> events) {
            Set<String> cleared = new HashSet<>();
            for (StoredEvent stored : events) {
                if (stored.event() instanceof ToolResultsCleared(List<String> callIds)) {
                    cleared.addAll(callIds);
                }
            }
            return cleared;
        }
    }
}
