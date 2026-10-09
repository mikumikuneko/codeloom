package com.codeloom.workspace.persistence;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * {@link SessionRepository} 的持久化实现。
 *
 * <h2>关于 {@code save} 没有 fencing token 参数</h2>
 * {@code EventStore} 的每个写方法都强制要求 {@code LeaseToken}，因为「如果一个写入路径
 * 可以不带 token，那它就是一个漏洞」。这里却没有 —— 那么僵尸写入者会不会绕开 fence，
 * 直接用一个过期的会话来覆盖持有者的状态？
 *
 * <p>不会，但理由是**外部的**，必须写下来。{@code save} 在生产代码里有两条路径，
 * 分别对应「更新」和「插入」，而它们安全的原因**不同**：
 *
 * <ol>
 *   <li><b>更新</b>（{@code SessionWriter} 里每一次 {@code sessions.save}）—— 都发生在
 *       「追加一条带 token 的事件，并同事务更新会话行」里。fence 校验在那次追加里，
 *       失败会让整个事务回滚，本行的写入跟着一起没了。也就是说，
 *       **它受的保护是那次追加所在的事务传递过来的**，不是自己带的。
 *   <li><b>插入</b>（{@code SessionService.create} 的一处）—— 它确实不在任何带 token
 *       的事务里。安全的理由是**这个 id 此刻还没有别人知道**：会话 id 是刚生成的 UUID，
 *       没有并发写者能来争它；紧接着的 {@code appendStarted} 才去抢租约、发出第一个
 *       fencing token。换句话说它的安全性来自"还不存在"，而不是来自 fence 校验。
 * </ol>
 *
 * <p>推论：**对一条已存在的会话，任何绕开事件追加、单独调用 {@code save} 的路径都会
 * 开出一个洞。** 这不是警告，是设计约束 —— 它和 {@code Session} 类注释里「每追加一条
 * {@code SessionStateChanged} 都要在同一次事务里保存这个聚合」是同一句话的两面。
 */
@Repository
public class MyBatisSessionRepository implements SessionRepository {

    private final SessionMapper mapper;

    public MyBatisSessionRepository(SessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(Session session) {
        mapper.save(SessionRow.of(session));
    }

    @Override
    public Optional<Session> findById(SessionId id) {
        return Optional.ofNullable(mapper.findById(id.value())).map(SessionRow::toDomain);
    }

    @Override
    public List<Session> findByProject(ProjectId projectId, int limit, int offset) {
        return mapper.findByProject(projectId.value(), limit, offset).stream()
                .map(SessionRow::toDomain)
                .toList();
    }

    @Override
    public List<Session> findAll() {
        return mapper.findAll().stream()
                .map(SessionRow::toDomain)
                .toList();
    }

    @Override
    public void discard(SessionId id) {
        // 刻意**不**检查影响行数：删一条本来就不该在的会话不是错误
        //（重试、或者两个标签页各自点了一次），而"没删到"和"删到了"对调用方是一回事。
        // 真正的失败（连接断了之类）会以异常形式出来
        mapper.deleteById(id.value());
    }

    @Override
    public void discardByProjectAndOwner(ProjectId projectId, UserId ownerId) {
        // 同样不看影响行数：一个从没建过会话的人删出 0 行是正常结果
        mapper.deleteByProjectAndOwner(projectId.value(), ownerId.value());
    }
}
