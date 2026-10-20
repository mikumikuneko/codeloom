package com.codeloom.app.approval;

import com.codeloom.app.turn.SessionWriter;
import com.codeloom.app.turn.TurnExecutor;
import com.codeloom.domain.event.ToolApprovalResolved;
import com.codeloom.domain.event.ToolRejected;
import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.session.SessionState;
import com.codeloom.domain.user.UserId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * 答复一次挂起的审批，然后让那条会话接着跑。
 *
 * <p>落 {@code ToolApprovalResolved} 是写事件流，而 {@code EventStore} 的写方法都要求
 * {@link LeaseToken}。答复请求是普通 HTTP、不持有执行权，所以临时抢一次租约：抢到、
 * 落事件、放掉，再由执行器自己抢着续跑。抢不到时告诉调用方它现在正忙（409），不硬写。
 *
 * <p>答复之后直接续跑，不让用户再说一句“继续”：那一轮本来就是为等人批准而停下的。
 * 例外是拒绝且没有留下理由 —— 此时不续跑，把这一轮停在等用户说话。
 *
 * <p>三次写入各自成一小段事务，不能包成一个：{@link TurnExecutor#resume} 同步跑完一整轮，
 * 包进去会让宣布挂在整轮提交上，晚发的旧事件还会被订阅端直接丢弃。代价是中间失败会留下
 * “答复记了、收尾没记”，由崩溃恢复补成终局。理由见
 * {@code docs/decisions/architecture/2026-10-10-announcing-is-a-property-of-writing.md}
 * 与 {@code docs/decisions/architecture/2026-09-28-crash-recovery-writes-facts-not-resumes.md}。
 */
@Component
public class ApprovalService {

    private final SessionRepository sessions;
    private final SessionWriter writer;
    private final ExecutionLease leases;
    private final TurnExecutor executor;

    public ApprovalService(SessionRepository sessions,
                           SessionWriter writer,
                           ExecutionLease leases,
                           TurnExecutor executor) {
        this.sessions = sessions;
        this.writer = writer;
        this.leases = leases;
        this.executor = executor;
    }

    /**
     * 记下答复，然后按情况续跑。
     *
     * <p>{@code resolvedByUserId} 必须由控制器从登录态取得，不能让调用方随意填写：
     * 那是“平等协作”下唯一能说清责任的东西。记 id 而不是用户名 —— 追责要的是身份，
     * 用户名会变（见 {@link ToolApprovalResolved}）。
     *
     * @param sessionId        要答复的会话
     * @param callId           挂起的工具调用
     * @param approved         是否批准
     * @param resolvedByUserId 做出答复的用户 id，由控制器从登录态取得
     * @param reason           拒绝时留下的理由；拒绝且为空白时不续跑这一轮
     * @throws ResponseStatusException 409：执行租约已被别的路径持有，会话尚在收尾
     */
    public void resolve(SessionId sessionId, String callId, boolean approved,
                        UserId resolvedByUserId, String reason) {
        // 租约按会话所在的那棵树抢（见 ExecutionLease）
        Session session = sessions.findById(sessionId).orElseThrow(
                () -> new IllegalArgumentException("会话不存在：" + sessionId));

        Optional<LeaseToken> acquired = leases.tryAcquire(session);
        if (acquired.isEmpty()) {
            // 409 而不是 500：挂起那一刻本身可能还在收尾（写 checkpoint、用量，再放锁），
            // 而审批卡片在请求到达时就已经画出。用户手快时会撞上这几毫秒，
            // 该做的是“还没停稳，再点一次”。
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "这一轮还在收尾，等它停下来再答复");
        }

        LeaseToken token = acquired.get();
        boolean stopHere;
        try {
            // 租约只挡住后来的写者，不会让请求开头那份快照变新。等锁期间另一轮
            // （或一次换模型）可能已经改过 state/model；append 会把整行写回去，
            // 所以先重读一次。见
            // {@code docs/decisions/bug-fix/2026-10-19-lock-then-reread-before-writing-back.md}。
            Session current = sessions.findById(sessionId).orElseThrow(
                    () -> new IllegalStateException("会话不存在：" + sessionId));
            SessionWriter.Written written = writer.append(
                    current, new ToolApprovalResolved(callId, approved, resolvedByUserId, reason), token);

            SessionWriter.Written latest = written;
            if (!approved) {
                // 拒绝是这次调用的终点，必须记一条收尾，否则界面上那一行永远停在
                // “正在跑”，事件流里也答不出“它后来怎么了”。哪些事件算终局见
                // ToolCallLifecycle。不带理由：理由属于上面的答复。
                latest = writer.append(written.session(), new ToolRejected(callId), token);
            }

            // 拒绝且没有留下指示时，这一轮不接着跑，而是停在“等用户说话”。
            // 用户叫停了这件事又没说下一步，让模型自己猜多半是浪费一轮调用。
            // 停下用 abortTurn：只把轮次号推一格，不打 checkpoint —— 这一轮没有
            // 新的产出位置，可回的点仍是挂起时那条。
            stopHere = !approved && blank(reason);
            if (stopHere) {
                // 用 latest.session()，不是请求开头那份快照：状态已经被 append 推到
                // WAITING_USER，用旧的收尾会再落一条一模一样的迁移事件。
                // commitSha 传 null：这一轮没有新的产出位置可打。
                writer.abortTurn(latest.session(), null,
                        SessionState.WAITING_USER, "REJECTED", token);
            }
        } finally {
            // 先放锁再叫执行器：它自己会去抢，我们持有时它只会拿到 Busy，
            // 于是这次答复静默地什么都没做。
            leases.release(token);
        }

        if (!stopHere) {
            executor.resume(sessionId);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
