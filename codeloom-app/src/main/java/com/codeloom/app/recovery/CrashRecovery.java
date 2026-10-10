package com.codeloom.app.recovery;

import com.codeloom.app.turn.SessionWriter;
import com.codeloom.domain.event.Event;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.session.ToolCallLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 进程起来之后，收拾上一次没能收场的会话。
 *
 * <pre>
 *   1. 扫出所有**非终态**的会话
 *   2. 逐个抢租约 —— 抢不到说明那个实例还活着，跳过
 *   3. 抢到了：把"执行到一半的工具调用"补写成 {@code ToolInterrupted}
 * </pre>
 *
 * <h2>为什么到第 3 步就停</h2>
 * 补写 {@code ToolInterrupted} 是为了**防止重放**：那个调用可能已经改了文件、跑过构建，
 * 重新执行一遍就是二次修改。而只要这条事实在事件流里，模型下次看到它就会先去确认现状
 * （{@code ContextAssembler} 把它投影成"不要直接重试，先用 read_file 确认"）——
 * 安全性已经拿到了。
 *
 * <p>**不做自动接着跑**：用户说一句"接着写"就够了 —— 他看到的事实，正是那条"上次那个
 * 调用没完成"，模型会照着先去确认现状。自动续跑只在**无人值守的服务**里有价值
 * （容器 / systemd 自动重启，那边没人说"继续"），而本项目的部署形态是宿主机 + 人在键盘前。
 *
 * <h2>为什么不需要另建一套心跳存活检测</h2>
 * 第 2 步抢租约就是那个检测。租约过期本身就意味着"持有它的那个实例已经不在了" ——
 * 这正是 {@code ExecutionLease} 把它写进设计里的原因。
 */
@Component
public class CrashRecovery {

    private static final Logger log = LoggerFactory.getLogger(CrashRecovery.class);

    private final SessionRepository sessions;
    private final EventStore events;
    private final ExecutionLease leases;
    private final SessionWriter writer;

    public CrashRecovery(SessionRepository sessions,
                         EventStore events,
                         ExecutionLease leases,
                         SessionWriter writer) {
        this.sessions = sessions;
        this.events = events;
        this.leases = leases;
        this.writer = writer;
    }

    /** 应用就绪之后跑一次。放在就绪之后而不是更早：那时数据库、Redis 都已经可用。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverUnfinishedSessions() {
        // 扫全部会话，**不做状态过滤**："这条要不要恢复"的判断在 recover() 里
        // （看有没有执行到一半的工具调用），一条空闲的会话对它来说是空操作。
        //
        // 不用"列出那几个可能未完成的状态"的白名单：新增状态时会静默漏掉它，
        // 而漏掉恢复是危险的方向 —— 一个执行到一半的工具调用会被重新执行一遍。
        List<Session> candidates = sessions.findAll();
        if (candidates.isEmpty()) {
            return;
        }
        log.info("启动检查 {} 条会话，逐个确认有没有需要恢复的", candidates.size());
        for (Session session : candidates) {
            try {
                recover(session);
            } catch (RuntimeException e) {
                // 一条恢复失败不该拖住其他的 —— 剩下的还能救
                log.warn("恢复会话 {} 时出错，跳过它继续", session.id(), e);
            }
        }
    }

    /**
     * 恢复一条会话。
     *
     * <p>包级可见是为了能单独测它 —— 上面那个扫描会遍历整个库，测试里不该用它。
     */
    void recover(Session session) {
        Optional<LeaseToken> acquired = leases.tryAcquire(session);
        if (acquired.isEmpty()) {
            // 拿不到租约 = 有实例正持有它 = 那棵树有人管，不用我们操心。
            // 注意"有人管"可能是**同一个人的另一条会话**：它们共用一棵树
            return;
        }
        LeaseToken token = acquired.get();
        List<String> interrupted;
        try {
            interrupted = unfinishedToolCalls(session.id());
            if (!interrupted.isEmpty()) {
                writer.interruptUnfinished(session, interrupted, token);
            }
            if (!interrupted.isEmpty()) {
                log.info("会话 {} 有 {} 个执行到一半的工具调用，已补写成 ToolInterrupted",
                        session.id(), interrupted.size());
            }
        } finally {
            leases.release(token);
        }
    }

    /**
     * 执行到一半的调用：有 {@code ToolCallRequested}，却没有对应的结束事实。
     *
     * <p>这里的 switch **用了 default**，和 {@code EventCodec} 里那种"必须逐条列出"不同。
     * 区别在于这个方法是**筛选**而不是**映射**：漏掉一个类型，结果就是"不选它" ——
     * 而它本来也不在这份名单里。映射漏了会算错，筛选漏了只是选得少一点。
     *
     * <h2>{@code ToolApprovalRequested} 绝不能进这份名单</h2>
     * 进了的话，<strong>"正等人批"的调用和"崩溃时执行到一半"的调用长得一模一样</strong>
     *（都是"有 requested、没有收尾"）：
     * 模型请求跑一条白名单外的命令 → 挂起进 {@code AWAITING_APPROVAL}（它**刻意不是终态**，
     * 就是等重启之后还能被看见）→ 进程重启时被当成"执行到一半"补写 {@code ToolInterrupted}
     * → 那条事件在 {@code TurnStates} 里映成 {@code THINKING}，而
     * {@code AWAITING_APPROVAL → THINKING} 是<b>非法迁移</b> → 抛异常 →
     * 会话被打成 {@code FAILED}。<b>也就是重启一次，那个等着你点的批准就变成了一个错误。</b>
     *
     * <p>所以名单里要的是 {@code ToolApprovalResolved}，不是 {@code ToolApprovalRequested}
     * —— 见下面那两条 case。
     */
    private List<String> unfinishedToolCalls(SessionId sessionId) {
        Set<String> pending = new LinkedHashSet<>();
        for (StoredEvent stored : events.readAll(sessionId)) {
            Event event = stored.event();
            // **"这条事件把那次调用推到哪一步"由域层那一处说**（见 ToolCallLifecycle）——
            // 这里只管"那一步对这个名单意味着什么"。哪些事件算终局、为什么，
            // 都写在那边；漏掉一种在那里编译不过
            ToolCallLifecycle.openedBy(event).ifPresent(pending::add);
            ToolCallLifecycle.closedBy(event).ifPresent(pending::remove);

            // 挂起等人批：这个调用**还没跑**，它在等一个人 —— 那不是"执行到一半"。
            // 会话状态停在 AWAITING_APPROVAL，重启之后那条批准还应该点得动
            ToolCallLifecycle.approvalAskedBy(event).ifPresent(pending::remove);

            // 答复到了：**批了就得跑**，所以它又回到名单里。
            //
            // 这一条不是对称的补充，是必须的：少了它，进程死在上一条和
            // "批准之后那次真正执行"之间的缝里时，这个调用会被安静地漏掉 ——
            // 模型永远不会知道它批准过的那个调用压根没跑（比"跑了一半"更糟，
            // 因为它看起来像已经做完了）。拒了就是这件事结束了，不该进名单
            ToolCallLifecycle.approvalGrantedBy(event).ifPresent(pending::add);
            ToolCallLifecycle.approvalRefusedBy(event).ifPresent(pending::remove);
        }
        return List.copyOf(pending);
    }
}
