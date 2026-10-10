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
 * 把会话的事件流**投影**成给模型的消息列表。
 *
 * <h2>这个类产出的就是"存事实、看投影"里那个投影</h2>
 * 存的是事件（当时发生了什么），这里产出的是消息数组（这次调用要发什么）。
 * 同一份事件流可以投影出多种视图，这是其中最重要的一种。
 *
 * <h2>为什么必须是确定性投影</h2>
 * 模型服务商的 prompt 缓存是**前缀匹配**的，命中价比未命中低得多。
 * 所以同一个事件流必须投影出**逐字符相同**的消息前缀 —— 这是纯函数，
 * 不依赖时间、不依赖随机数、不做任何"看起来更聪明"的润色。
 *
 * <p>推论：**每个事件只会往尾部追加消息，不会改动前面的消息** ——
 * 这条性质是上面那个缓存便宜的全部来源，动它之前先想清楚代价。
 *
 * <h2>这一块由三个类分担</h2>
 * {@link Projection} 是**跨轮往前推进**的那条；它把"折一遍"交给 {@link MessageFold}
 *（只管把一批事件变成消息），任务清单由 {@link TodoTracker} 单独跟着走。
 * 本文件只放门面和那条可推进的投影。
 *
 * <h2>不在上下文里的事件</h2>
 * 状态变化、checkpoint、验证结论这些都不进上下文 —— 它们要么是
 * 派生的（验证结论来自工具结果）、要么是给审计看的（checkpoint），模型不需要知道。
 * 但它们**都要落库**，只是投影时不选它们而已。
 */
public final class ContextAssembler {

    /**
     * 按 id 查用户名。
     *
     * <h2>投影里唯一需要它的地方</h2>
     * 别人捎来的留言 —— 消息格式里没有"第三方"这个角色，它只能以 user 的身份出现，
     * 所以必须标明来源（那句由 {@link MessageFold} 拼）。
     *
     * <p>**它是"读的时候查"的，不是写的时候抄下来的**：用户名会变，而事件里只记 id
     *（见 {@code AgentNoteDelivered}）。查不到时的兜底话由 {@link MessageFold#senderOf}
     * 负责 —— 宁可说"协作者"，也不编一个名字。
     *
     * <p>查出来的名字**会被折进那条消息**，之后就不动了：折好的前缀必须逐字节稳定
     *（见类注释），不能每轮重算。所以改过用户名之后，模型看到的仍是当时的那个名字；
     * 而**界面**不受这个约束 —— 它每次渲染都按 id 现查，显示的是现在的名字。
     */
    private final Function<UserId, String> displayNameOf;

    /**
     * <p><b>这一层必须由调用方给出来</b>（没有"默认不带名字"的构造）：装配出来的东西
     * 就是发给模型的那份输入，而"这句话是谁说的"在里面是**承重**的 ——
     * 一个悄悄漏掉这一层的装配器不会报错，只会让模型分不清自己用户的要求和别人的留言。
     * 不要名字的地方就明写 {@code id -> null}（那时{@code MessageFold} 会回退成"协作者"）。
     */
    public ContextAssembler(Function<UserId, String> displayNameOf) {
        this.displayNameOf = Objects.requireNonNull(displayNameOf, "displayNameOf");
    }

    /**
     * @param events       按 seq 升序的完整事件流
     * @param systemPrompt 会话级系统提示词。**里面不能放每次都变的内容**
     *                     （比如当前时间戳），否则每一轮都会缓存未命中
     */
    public List<LlmMessage> assemble(List<StoredEvent> events, String systemPrompt) {
        List<LlmMessage> messages = new ArrayList<>();
        assembleInto(events, systemPrompt, messages);
        // 复制一份再交出去 —— 返回值不可变是这个类的契约
        return List.copyOf(messages);
    }

    /**
     * 装进调用方给的 list。
     *
     * <p>它就是"开一条新投影、把整条流喂进去"（见 {@link #projection}），
     * 所以只读一次历史的调用方走这条路，热路径走可推进的那条 —— **两条路是同一份代码**，
     * 不会各写一遍然后慢慢分叉。
     */
    public void assembleInto(List<StoredEvent> events, String systemPrompt,
                             List<LlmMessage> messages) {
        projection(systemPrompt).advance(events, messages);
    }

    /**
     * 开一条**可以往前推进**的投影。
     *
     * <p>一轮里模型循环每迭代一次都要重新装配一遍上下文，而每次从头投影整条流＝把前面的活重做一遍
     *（一轮最多 25 次）—— 会话越长这份白工越大，而它付在"模型下一步该看什么"这条最要紧的路上。
     * 所以推进的投影只处理**新到的**事件，内存里留着已经投出来的消息和那几张表。
     *
     * <p>**三种事件会回头改已经投出去的消息**（压缩把前缀换成摘要、清理旧工具结果把正文换掉、
     * 回滚砍掉尾部）—— 靠"只接着处理新到的事件"补不上，只能推倒重来。
     * 它们都很罕见，所以"重来"不心疼，"猜错了却看起来对"才心疼。
     *
     * <p>推进的判据是 **seq**（不是"第几条"）：调用方给的是同一条不断变长的流，
     * 而 seq 是它唯一稳定的坐标。
     */
    public Projection projection(String systemPrompt) {
        return new Projection(systemPrompt, displayNameOf, null);
    }

    /**
     * 同上，另给它一条**重新取到整条流**的路。
     *
     * <p>给"分页喂"的调用方用：那种调用方每次只递一片，而投影遇到改写类事件要推倒重来时，
     * 需要的是**整条流**而不是那一片 —— 没这条来源，它就只能拿那一片凑，
     * 结果是前面那一整段静默消失。见 {@link Projection#fold}。
     *
     * @param wholeStream 取当前整条事件流。**每次重来都会调一次**，所以它得是"去读最新的"，
     *                    不是一份事先拍下来的快照
     */
    public Projection projection(String systemPrompt, Supplier<List<StoredEvent>> wholeStream) {
        return new Projection(systemPrompt, displayNameOf,
                Objects.requireNonNull(wholeStream, "wholeStream"));
    }

    /** UTF-16 下一个字符最多占的字节数。见 {@code Projection#approximateSizeBytes}。 */
    private static final int BYTES_PER_CHAR_WORST_CASE = 2;

    /** 一条可以往前推进的投影，见 {@link #projection(String)}。 */
    public static final class Projection {

        private final String systemPrompt;

        /** 见 {@link ContextAssembler#displayNameOf} —— 投影是静态嵌套类，所以这里各持一份。 */
        private final Function<UserId, String> displayNameOf;

        /**
         * 推倒重来时去哪取**整条流**。null 表示调用方保证递进来的每次都是整条流（或它的前缀）——
         * 一次装配整条流的调用方就是这样，它们不用给。
         */
        private final Supplier<List<StoredEvent>> wholeStream;
        /** 已经处理过的事件里最大的那个 seq。0 = 一条都还没处理（seq 从 1 开始，见 EventEnvelope）。 */
        private long foldedUpTo;
        /** 把事件折成消息的那一个。推倒重来时会换一个新的（见 {@link #begin}）。 */
        private MessageFold messageFold;
        /** 投影的产出：交给模型的那串消息。**只往尾部追加** —— 前缀逐字节不变是缓存的前提（见类注释）。 */
        private final List<LlmMessage> messages = new ArrayList<>();
        /**
         * 压缩的水位线：**序号不超过它的事件不再投影** —— 它们已经被摘要替换掉了
         * （见 {@code ContextCompacted}）。0 = 没压过。
         */
        private long watermark;
        /** 清单现在的样子。**不受水位线约束**：它每一轮都被重新注入，"压缩之后计划还在"正是它的用处。 */
        private final TodoTracker todos = new TodoTracker();
        /** 这条对话里 {@code read_file} 读过哪些文件（工作区相对路径），见 {@link #readPaths()}。 */
        private final ReadPathTracker reads = new ReadPathTracker();
        /** 尾部那次批准了、但还没补跑的调用，见 {@link #pendingApprovedCall()}。 */
        private final PendingCallTracker approvals = new PendingCallTracker();

        /**
         * 上面那几份事实的**全部**。
         *
         * <p>它存在的理由只有一条：**"全部"只写在这里** —— 折事件时遍历它、推倒重来时也
         * 遍历它，于是那两份动作不可能漏掉某一份。一个一个点名（先前那种写法）迟早会有人
         * 添了一份、却只更新了这两处之一，**而那不会报错** —— 只会让它带着被退掉的那段
         * 时间的痕迹活下来。
         *
         * <p>顺序无所谓：几份事实之间没有依赖，各自看各自的事件。
         */
        private final List<Derived> derived = List.of(todos, reads, approvals);

        private Projection(String systemPrompt, Function<UserId, String> displayNameOf,
                           Supplier<List<StoredEvent>> wholeStream) {
            this.systemPrompt = systemPrompt;
            this.displayNameOf = displayNameOf;
            this.wholeStream = wholeStream;
        }

        /**
         * 投影**已经处理过的最后一条事件**在流里的序号（0 = 一条都还没处理）。
         *
         * <p>给"自己给事件编号"的调用方用（没有 appender 的场合：单测、批处理）。
         * 得从这儿接着往下编：从 1 重新编的话，新事件的序号会落在已经处理过的范围里，
         * 被 {@link #fold(List)} 当成旧的跳过 —— 表现是模型看不见这一轮刚发生的事。
         *
         * <p>这是"**喂到哪儿了**"，不是"产出到哪儿了"：不产出消息的事件（压缩水位线之前的那些）
         * 也算处理过。
         */
        public long foldedUpTo() {
            return foldedUpTo;
        }

        /**
         * 这串消息**大致**占多少字节 —— 只给"这张表最多占多少内存"画个界用，不求精确。
         *
         * <p>算正文与工具参数：它们是投影里唯一会长的部分。
         *
         * <p>**按最坏情况算**（一个字符两个字节）：中文在内存里就是两字节，
         * 而这是个上界 —— 宁可高估。高估的后果是早淘汰一次，低估的后果是内存没有边界。
         */
        public long approximateSizeBytes() {
            long bytes = 0;
            for (LlmMessage message : messages) {
                if (message.content() != null) {
                    bytes += message.content().length();
                }
                for (ToolCall call : message.toolCalls()) {
                    bytes += call.argumentsJson().length();
                }
            }
            return bytes * BYTES_PER_CHAR_WORST_CASE;
        }

        /**
         * 这条对话里读过哪些文件（工作区相对路径）。
         *
         * <p>"读过才许覆盖"这条规则就是拿它重建的，见 {@link ReadPathTracker}。
         */
        public Set<String> readPaths() {
            return reads.paths();
        }

        /**
         * 上一轮里**批准了、但还没补跑**的那次调用。
         *
         * <p>它就是"挂起等人批 → 用户点了批准 → 进程在那之后退出了"那条路的入口。
         * 判据见 {@link PendingCallTracker}。
         */
        public Optional<ToolCall> pendingApprovedCall() {
            return approvals.pendingApprovedCall();
        }

        /**
         * 把事件折到流的末尾，然后把当前的上下文交给 {@code sink}。
         *
         * <p>{@code events} 是**同一条不断变长的流**（调用方每轮往里追加），
         * 这里只折 seq 比上次大的那些。
         */
        public void advance(List<StoredEvent> events, List<LlmMessage> sink) {
            fold(events);

            sink.clear();
            sink.addAll(messages);

            // 任务清单**最后注入、只注入一份**，而且它**不进**上面那份消息列表
            // （进了的话每折一次就多一条）。它是"当前状态"不是"历史里的一段话"：
            // 模型每一轮都该在末尾看见它，而不是让它淹没在此前几千条消息里。
            // 这也正是它比"一条工具结果"值钱的地方 —— 工具结果会被压缩清掉，它不会
            TodoListUpdated current = todos.current();
            if (current != null && !current.items().isEmpty()) {
                sink.add(LlmMessage.user(TODO_HEADER + renderTodos(current.items())));
            }
        }

        /**
         * 只把新事件折进来，**不要**当前上下文。
         *
         * <p>给"养着一条跨轮投影"的调用方用：它每轮只需要让投影追上库里最新的一条，
         * 上下文等真要发给模型时再 {@link #advance} 要。
         *
         * <p>{@code events} **可以只是一片**（分页喂的调用方就是这么喂的）—— 但遇到
         * 改写类事件要推倒重来时，重来要的是**整条流**，所以那时会用构造时给的那条来源
         * 重新取一遍（见 {@link ContextAssembler#projection(String, Supplier)}）。
         * 没给来源的调用方只能在 {@code events} 上重来：那**只在"递进来的就是整条流"时**
         * 才成立。
         */
        public void fold(List<StoredEvent> events) {
            List<StoredEvent> fresh = new ArrayList<>();
            for (StoredEvent stored : events) {
                if (stored.seq() > foldedUpTo) {
                    fresh.add(stored);
                }
            }
            if (rewritesHistory(fresh)) {
                startOver();
                // ★ 重来就是**整条流**再来一遍，不是"接着折新来的那批" ——
                // 只折新的等于把前面全丢了。
                //
                // 而分页喂时递进来的这一片**不是整条流**：拿它当整条流，前面那一整段会静默消失
                //（实测：601 条事件里前 500 条直接没了），所以要回去问那条来源
                fresh = wholeStream != null ? wholeStream.get() : events;
            }
            if (messageFold == null) {
                begin(events);
            }

            for (StoredEvent stored : fresh) {
                // 派生事实看**所有**事件 —— 压缩的水位线管不着它们（"压缩之后计划还在"
                // 正是清单存在的理由）；而回滚管得着，那由它们各自消化（见 Derived）
                for (Derived fact : derived) {
                    fact.accept(stored);
                }
                if (stored.seq() > watermark) {
                    messageFold.accept(stored);
                }
                foldedUpTo = stored.seq();
            }
            messageFold.flush();
            // 收尾必做：把「请求了却没有结果」的调用配对上，见 closeOpenCalls。
            // 它幂等：第二次调用时 openCallIds 已经空了
            messageFold.closeOpenCalls();
        }

        /** 这三种事件回头改已经投出去的消息，增量折不出来，见 {@link #projection(String)}。 */
        private static boolean rewritesHistory(List<StoredEvent> fresh) {
            return fresh.stream().anyMatch(stored -> stored.event() instanceof ContextCompacted
                    || stored.event() instanceof ToolResultsCleared
                    || stored.event() instanceof SessionRewound);
        }

        /**
         * 把投影退回"一条都还没处理"：清完之后下一个喂进来的必须是**整条流**
         *（{@link #fold(List)} 就是这么用的 —— 清完立刻 {@link #begin(List)} 重建）。
         *
         * <p>那几份派生事实由 {@link #derived} 那一个遍历清掉：它们各自还留着上一次折的
         * 结果，不清的话这次重折会在旧账上再记一遍。**一份都不能漏**，所以这里不点名。
         */
        private void startOver() {
            messages.clear();
            messageFold = null;
            foldedUpTo = 0;
            watermark = 0;
            derived.forEach(Derived::clear);
        }

        /**
         * 从头开始那一下：系统提示词、压缩摘要、水位线、正文被清过的调用集合
         * 都在这一刻定下来。
         *
         * <p>派生状态（清单、读过哪些文件、尾部那只事件）**不在这里**折：
         * 由 {@link #fold(List)} 那趟遍历统一折，而且只有那一处 —— 走到这一支时，
         * 那趟遍历手里的 {@code fresh} 就是整条流。
         */
        private void begin(List<StoredEvent> events) {
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(LlmMessage.system(systemPrompt));
            }
            // 被清过正文的那些调用必须**在遍历之前**扫出来：清理事件排在它清的那些
            // 结果**后面**，边遍历边应用就得回头改已经投影出去的消息
            messageFold = new MessageFold(messages, clearedCallIds(events), displayNameOf);

            // 压到哪儿了决定"从哪个序号开始投影"，同样得在遍历之前定下来
            Compaction compaction = lastCompaction(events);
            if (compaction != null) {
                messageFold.acceptSummary(compaction.summary());
                watermark = compaction.droppedUpToSeq();
            }
        }

        /** 清单消息的开头。同样得点明"这是状态，不是谁刚说的一句话"。 */
        private static final String TODO_HEADER = "[当前任务清单 —— 你自己列的，用 todo_write 更新它]\n";

        /**
         * 清单写成几行字。
         *
         * <p>状态用**模型自己的词**（pending / in_progress / completed，和工具参数里那三个
         * 字符串一模一样）：它写进去、它再读回来，中间不该经过一次翻译。
         */
        private static String renderTodos(List<TodoListUpdated.Item> items) {
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < items.size(); index++) {
                TodoListUpdated.Item item = items.get(index);
                text.append(index + 1).append(". [").append(item.state().name().toLowerCase()).append("] ")
                        .append(item.content()).append('\n');
            }
            return text.toString();
        }

        /**
         * 从事件流里找出**最后一次**压缩。
         *
         * <p>取最后一条而不是第一条：压过两次的话，后一条的水位线更靠前、覆盖得更早，
         * 而且它的摘要里已经把前一次的摘要一起总结进去了
         * （见 {@link ContextCompacted} 的类注释）—— 用第一条会丢掉后来的那一段。
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

        /** 扫出所有被清过正文的调用 id。多个清理事件会累积到同一个集合里。 */
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
