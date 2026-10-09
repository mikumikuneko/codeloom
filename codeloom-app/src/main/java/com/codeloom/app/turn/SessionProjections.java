package com.codeloom.app.turn;

import com.codeloom.agent.context.ContextAssembler;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.user.User;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * 每次交给调用方之前先补读一次：{@code readAfter(上次的 seq)}，一直补到读不出新的为止。
 * 所以"另一个实例往这条会话写了事件"（同步、合并、别人的操作）也接得住 ——
 * 补读到什么就折什么，包括那三种会**回头改**投影的事件（压缩、清理旧结果、回滚）：
 * 投影见到它们会自己推倒重来。
 *
 * <h2>为什么现在没有淘汰</h2>
 * 一个项目两个人、几条会话，常驻内存是几 MB 级。先把它做**对**；等会话数真的多到需要
 * 上限时再加 —— 到时候也只是给这张表加一个上限，正确性不依赖它（丢了就重建）。
 */
@Component
public class SessionProjections {

    /** 一次补读最多拉这么多条，拉满就再拉一轮。没有它，一条长会话要一次全读进来。 */
    private static final int PAGE = 500;

    /**
     * 投影器。一个实例够所有人用（它自己不持有状态）。
     *
     * <p>它带着"按 id 查用户名"这一层 —— 投影里唯一用它的是**别人捎来的留言**
     *（那段话要写明来源，见 {@link ContextAssembler}）。查发生在**折进去的那一刻**：
     * 折好的消息不再变（前缀必须逐字节稳定），所以改过用户名之后，
     * 模型看到的仍是折进去时的那个名字，而**界面**每次渲染都现查、显示的是新名字。
     */
    private final ContextAssembler assembler;

    private final EventStore events;
    private final Map<SessionId, Live> live = new ConcurrentHashMap<>();

    public SessionProjections(EventStore events, UserRepository users) {
        this.events = events;
        this.assembler = new ContextAssembler(
                id -> users.findById(id).map(User::displayName).orElse(null));
    }

    /**
     * 这条会话的投影，**已经折到库里最新的一条**。
     *
     * <p>拿到的对象是**共享的、活的**：调用方（这一轮）会继续往它上面折自己新产生的事件。
     * 同一时刻只有一轮在跑这条会话（执行租约是按工作区发的，见 {@code ExecutionLease}），
     * 所以这里不需要比"一个会话一条"更重的同步。
     */
    public ContextAssembler.Projection acquire(Session session) {
        Live entry = live.computeIfAbsent(session.id(), id -> new Live(session));
        entry.catchUp(session.model().systemPrompt());
        return entry.projection;
    }

    /** 现在常驻着几条（诊断用）。 */
    public int liveCount() {
        return live.size();
    }

    private final class Live {

        private final SessionId sessionId;
        private ContextAssembler.Projection projection;
        private String systemPrompt;
        private long lastSeq;

        private Live(Session session) {
            this.sessionId = session.id();
            this.systemPrompt = session.model().systemPrompt();
            this.projection = assembler.projection(systemPrompt);
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
                projection = assembler.projection(currentPrompt);
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
                if (more.size() < PAGE) {
                    return;
                }
            }
        }
    }
}
