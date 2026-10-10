package com.codeloom.app.turn;

import com.codeloom.agent.context.ContextAssembler;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每条会话那条**活着的**投影 —— 会话的活状态，住在进程内存里。
 *
 * <h2>为什么要有它</h2>
 * 循环真正要的只有三样东西 —— 已经投出来的上下文、这条对话读过哪些文件、
 * 有没有一次"批准了却还没跑"的调用 —— 它们全都可以跟着事件一路走过去，
 * 不必每迭代一次就把整条事件流读回来从头投影（见 {@link ContextAssembler.Projection}）。
 *
 * <p>两个参考实现（deepseek harness、Claude Code）都是这个形状：会话在内存里是**活的**，
 * 磁盘/数据库只负责**落盘与重启恢复**。我们把活状态放在这里 —— 它**只是派生缓存**：
 * 丢了、落后了、进程重启了，都只是多算一次，不会算错（见下面的补读）。
 *
 * <h2>它读不到旧的</h2>
 * 每次交给调用方之前先补读一次：{@code readAfter(上次的 seq)}，一直补出新的为止。
 * 所以"另一个实例往这条会话写了事件"（同步、合并、别人的操作）也接得住 ——
 * 补读到什么就折什么，包括那三种会**回头改**投影的事件（压缩、清理旧结果、回滚）：
 * 投影见到它们会自己推倒重来。
 *
 * <h2>它有上限，超了就把最久没用过的那条放下</h2>
 * 这是**服务端**：会话数没有上限，而这张表按"跑过一轮的会话数"只增不减。
 * 上限有两条，**取先到的那条**：条数（{@code codeloom.live-projections}）和
 * 大致占用的字节（{@code codeloom.live-projections-bytes}）。为什么要字节那一条 ——
 * 一条 500 轮的会话和一条 2 轮的差几个数量级，只看条数等于没有内存边界。
 *
 * <p>放下一条**只是慢一次**：它下次要被用到时从事件流重建。所以淘汰不需要任何
 * "正在跑的不许踢"的机制 —— 一轮从头到尾握着的是投影对象本身，条目没了也影响不到它。
 */
@Component
public class SessionProjections {

    /** 一次补读最多拉这么多条，拉满就再拉一轮。没有它，一条长会话要一次全读进来。 */
    private static final int PAGE = 500;

    /**
     * 投影器。**全项目就一个**（它自己不持有状态），配置在装配它的那一处定死。
     *
     * <p>投影里唯一用"按 id 查用户名"的是**别人捎来的留言**（那段话要写明来源，
     * 见 {@link ContextAssembler}）。查发生在**折进去的那一刻**：折好的消息不再变
     *（前缀必须逐字节稳定），所以改过用户名之后，模型看到的仍是折进去时的那个名字，
     * 而**界面**每次渲染都现查、显示的是新名字。
     */
    private final ContextAssembler assembler;

    private final EventStore events;

    /** 常驻多少条。 */
    private final int maxLive;

    /** 常驻大致占多少字节。见 {@link Live#sizeBytes()} 里那个"大致"。 */
    private final long maxLiveBytes;

    /**
     * 按**访问顺序**排：每次拿到一条就把它挪到末尾，淘汰从最前面开始。
     *
     * <p>{@code LinkedHashMap} 一行都写不出来，加锁才写得出来（它读写都要动顺序），
     * 所以只在**碰这张表的那两小段**里加锁：补读是 I/O，绝不能锁在里面 ——
     * 那样所有会话的 acquire 会互相排队。
     */
    private final Map<SessionId, Live> live = new LinkedHashMap<>(16, 0.75f, true);

    public SessionProjections(EventStore events, ContextAssembler assembler,
                              @Value("${codeloom.live-projections:128}") int maxLive,
                              @Value("${codeloom.live-projections-bytes:32MB}") DataSize maxLiveBytes) {
        if (maxLive <= 0 || maxLiveBytes.toBytes() <= 0) {
            throw new IllegalArgumentException(
                    "常驻投影的上限必须是正数，收到 条数=" + maxLive + " 字节=" + maxLiveBytes);
        }
        this.events = events;
        this.maxLive = maxLive;
        this.maxLiveBytes = maxLiveBytes.toBytes();
        this.assembler = assembler;
    }

    /**
     * 这条会话的投影，**已经折到库里最新的一条**。
     *
     * <p>拿到的对象是**共享的、活的**：调用方（这一轮）会继续往它上面折自己新产生的事件。
     * 同一时刻只有一轮在跑这条会话（执行租约是按工作区发的，见 {@code ExecutionLease}），
     * 所以这里不需要比"一个会话一条"更重的同步 —— 包括补读那一段。
     */
    public ContextAssembler.Projection acquire(Session session) {
        Live entry = liveEntry(session);
        // 补读在锁**外面**：它是数据库往返，而这张表是所有会话共用的
        entry.catchUp(session.model().systemPrompt());
        evictBeyondBudget(entry);
        return entry.projection;
    }

    /** 这条会话没了（被丢弃）—— 把它的投影也放下。**放下而已**，它不承担任何事实责任。 */
    public void forget(SessionId sessionId) {
        synchronized (live) {
            live.remove(sessionId);
        }
    }

    /**
     * 这张表现在的样子：常驻几条、大致多少字节、上限是多少。
     *
     * <p>**只给诊断用**（见 {@code LiveProjectionsInfo}）。它回答的是"该不该调那两个上限"：
     * 常驻数一直贴着上限，就该往上调；常年是个位数，就该往下调。没有这个数，那两个值只能凭感觉定。
     */
    public LiveReport liveReport() {
        synchronized (live) {
            return new LiveReport(live.size(), totalBytes(), maxLive, maxLiveBytes);
        }
    }

    /** 见 {@link #liveReport()}。 */
    public record LiveReport(int count, long approximateBytes, int maxCount, long maxBytes) {
    }

    /** 常驻这些大致占多少字节。**调用方必须已经持有 {@code live} 的锁。** */
    private long totalBytes() {
        long bytes = 0;
        for (Live entry : live.values()) {
            bytes += entry.sizeBytes();
        }
        return bytes;
    }

    private Live liveEntry(Session session) {
        synchronized (live) {
            return live.computeIfAbsent(session.id(), id -> new Live(session));
        }
    }

    /**
     * 淘汰到两条上限之内，**最久没用过的先走**。
     *
     * <p>刚用过的那条不会是最旧的（它刚被挪到末尾），但还是显式跳过 ——
     * 免得哪天上限被设成 0 之类的怪值之后，它把自己踢掉、于是这一轮拿到一条已经不存在的投影。
     */
    private void evictBeyondBudget(Live justUsed) {
        synchronized (live) {
            long bytes = totalBytes();
            Iterator<Map.Entry<SessionId, Live>> oldestFirst = live.entrySet().iterator();
            while ((live.size() > maxLive || bytes > maxLiveBytes) && oldestFirst.hasNext()) {
                Map.Entry<SessionId, Live> eldest = oldestFirst.next();
                if (eldest.getValue() == justUsed) {
                    continue;
                }
                bytes -= eldest.getValue().sizeBytes();
                oldestFirst.remove();
            }
        }
    }

    private final class Live {

        private final SessionId sessionId;
        private ContextAssembler.Projection projection;
        private String systemPrompt;
        private long lastSeq;

        /** 上次算出来的体积；负数表示"还没算过，或者又折过事件了"。 */
        private long sizeBytes = -1;

        private Live(Session session) {
            this.sessionId = session.id();
            this.systemPrompt = session.model().systemPrompt();
            this.projection = newProjection(systemPrompt);
        }

        /**
         * 建一条投影，并告诉它**重来时去哪取整条流**。
         *
         * <p>**必须给**：这个类是**按页**喂的（见 {@link #catchUp}），而投影遇到压缩、清理旧结果、
         * 回滚时要拿整条流重来一遍 —— 手里那一片不够，前面那一整段会静默消失。
         */
        private ContextAssembler.Projection newProjection(String prompt) {
            return assembler.projection(prompt, () -> events.readAll(sessionId));
        }

        /** 大致占多少字节。算一次就留着，直到它又折了事件。 */
        private long sizeBytes() {
            if (sizeBytes < 0) {
                sizeBytes = projection.approximateSizeBytes();
            }
            return sizeBytes;
        }

        /**
         * 把库里还没折进来的补齐。
         *
         * <p>分页拉：一次拉满就再拉一轮。漏掉一页的后果是**模型看不见中间那一段**，
         * 而它不会报错。
         *
         * <p>系统提示词理论上跟着会话一辈子不变，但它是投影的**第一个消息**、
         * 即缓存前缀的起点。万一哪天它变了（改了配置、换了模型带新提示词），
         * 缓存里的第一个消息就成了旧的。所以这里顺手比一下，不一样就把这条投影作废重建。
         */
        private void catchUp(String currentPrompt) {
            if (!systemPrompt.equals(currentPrompt)) {
                projection = newProjection(currentPrompt);
                systemPrompt = currentPrompt;
                lastSeq = 0;
            }
            while (true) {
                List<StoredEvent> more = events.readAfter(sessionId, lastSeq, PAGE);
                if (more.isEmpty()) {
                    return;
                }
                projection.fold(more);
                lastSeq = more.getLast().seq();
                sizeBytes = -1;
                if (more.size() < PAGE) {
                    return;
                }
            }
        }
    }
}
