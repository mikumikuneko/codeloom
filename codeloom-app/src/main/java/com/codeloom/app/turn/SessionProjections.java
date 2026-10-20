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
 * 每条会话的活投影，住在进程内存里。
 *
 * <p>循环只需要已投出的上下文、读过哪些文件、有没有"批准了却还没跑"的调用；
 * 这些都能跟着事件折叠，不必每次迭代都重读整条事件流（见
 * {@link ContextAssembler.Projection}）。它只是派生缓存：丢失、落后或进程重启都只会多算
 * 一次，不会算错。
 *
 * <p>每次交给调用方前先 {@code readAfter(上次的 seq)} 补读到最新，因此其他实例写入的事件
 * （同步、合并、别人的操作）也接得住；压缩、清理旧结果、回滚这类会回头改投影的事件会让它
 * 推倒重来。
 *
 * <p>上限有两条，取先到者：条数（{@code codeloom.live-projections}）和大致字节数
 * （{@code codeloom.live-projections-bytes}）。只看条数没有内存边界：500 轮的会话与
 * 2 轮的相差几个数量级。淘汰只是让下次使用变慢（会从事件流重建），
 * 不需要"正在跑的不许踢"：一轮持有的是投影对象本身，条目被移除不影响它。
 */
@Component
public class SessionProjections {

    /** 一次补读最多拉这么多条，拉满再拉一轮；否则一条长会话要一次全读进来。 */
    private static final int PAGE = 500;

    /**
     * 投影器。全项目只有一个（自身无状态），在装配处定死。
     *
     * <p>投影里唯一按 id 查用户名的是别人捎来的留言（那段话要写明来源，见
     * {@link ContextAssembler}）。查询发生在折进去的那一刻：折好的消息不再变（前缀必须
     * 逐字节稳定），所以改过用户名后模型看到的仍是旧名字，而界面每次渲染现查、显示新名字。
     */
    private final ContextAssembler assembler;

    private final EventStore events;

    /** 常驻条数上限。 */
    private final int maxLive;

    /** 常驻大致字节上限，见 {@link Live#sizeBytes()}。 */
    private final long maxLiveBytes;

    /**
     * 按访问顺序排列：每次取用挪到末尾，淘汰从最前开始。
     *
     * <p>{@code LinkedHashMap} 无法无锁维护，所以只在操作这张表的两小段里加锁；
     * 补读是 I/O，绝不能放在锁内，否则所有会话的 acquire 会互相排队。
     */
    private final Map<SessionId, Live> live = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * @throws IllegalArgumentException 任一上限不是正数
     */
    public SessionProjections(EventStore events, ContextAssembler assembler,
                              @Value("${codeloom.live-projections}") int maxLive,
                              @Value("${codeloom.live-projections-bytes}") DataSize maxLiveBytes) {
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
     * 这条会话的投影，已经折到库里最新的事件。
     *
     * <p>返回的对象是共享且活的，调用方会继续往上折本轮产生的事件。同一会话同时只有一轮
     * 在跑（执行租约按工作区发放），因此不需要比"一会话一条"更重的同步，补读也一样。
     *
     * @param session 会话
     * @return 已补读到最新的活投影
     */
    public ContextAssembler.Projection acquire(Session session) {
        Live entry = liveEntry(session);
        // 补读在锁外：它是数据库往返，而这张表由所有会话共用
        entry.catchUp(session.model().systemPrompt());
        evictBeyondBudget(entry);
        return entry.projection;
    }

    /**
     * 会话没了（被丢弃）时放下它的投影。它只是缓存，不承担事实责任。
     *
     * @param sessionId 已消失的会话
     */
    public void forget(SessionId sessionId) {
        synchronized (live) {
            live.remove(sessionId);
        }
    }

    /**
     * 这张表的当前状态，只用于诊断（见 {@code LiveProjectionsInfo}）：常驻数贴近上限就该调高，
     * 长期只有个位数就该调低；没有这个数，两个上限只能凭感觉定。
     *
     * @return 常驻条数、大致字节数和两个上限
     */
    public LiveReport liveReport() {
        synchronized (live) {
            return new LiveReport(live.size(), totalBytes(), maxLive, maxLiveBytes);
        }
    }

    /**
     * @param count           常驻条数
     * @param approximateBytes 大致占用字节
     * @param maxCount        条数上限
     * @param maxBytes        字节上限
     */
    public record LiveReport(int count, long approximateBytes, int maxCount, long maxBytes) {
    }

    /** 常驻投影大致占多少字节；调用方必须已经持有 {@code live} 的锁。 */
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
     * 淘汰到两条上限之内，最久未用的先走。刚用过的那条虽然已被挪到末尾，仍显式跳过，
     * 避免上限被设成怪值时它把自己踢掉、让本轮拿到已不存在的投影。
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

        /** 上次算出的体积；负数表示尚未计算或又折过事件。 */
        private long sizeBytes = -1;

        private Live(Session session) {
            this.sessionId = session.id();
            this.systemPrompt = session.model().systemPrompt();
            this.projection = newProjection(systemPrompt);
        }

        /**
         * 建投影，并告诉它重建时去哪里取整条流。必须提供：本类按页喂事件，
         * 而投影遇到压缩、清理旧结果、回滚需要整条流重来，手里那一片不够，
         * 缺失的前段会静默消失。
         */
        private ContextAssembler.Projection newProjection(String prompt) {
            return assembler.projection(prompt, () -> events.readAll(sessionId));
        }

        /** 大致占多少字节；算一次就缓存，直到再次折入事件。 */
        private long sizeBytes() {
            if (sizeBytes < 0) {
                sizeBytes = projection.approximateSizeBytes();
            }
            return sizeBytes;
        }

        /**
         * 把库里尚未折入的事件补齐。分页拉取，拉满再拉一轮；漏掉一页会让模型看不见中间
         * 那一段，且不会报错。
         *
         * <p>系统提示词通常是投影的第一条消息，也是缓存前缀的起点。它一旦变化（改配置、
         * 换模型带入新提示词），缓存的首条消息就陈旧了，所以这里比较后直接作废重建。
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
