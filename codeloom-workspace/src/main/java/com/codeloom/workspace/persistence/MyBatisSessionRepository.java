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
 * <p>{@code save} 没有 fencing token 参数。更新路径都发生在"追加一条带 token 的事件并
 * 同事务保存会话行"里，fence 校验由那次追加承担，失败会回滚整个事务；但调用方必须在
 * 拿到租约后重新读取会话，不能用请求开始时的快照把并发推进的 {@code state} 和
 * {@code turn_index} 写回旧值。插入路径没有并发写者，因为会话 id 刚生成、尚未公开。
 *
 * <p>因此，对一条已存在的会话，任何绕开事件追加、单独调用 {@code save} 的路径都是缺陷。
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
        // 不检查影响行数：删一条本来就不存在的会话不是错误（重试或重复点击），
        // 真正的失败会以异常形式出现
        mapper.deleteById(id.value());
    }

    @Override
    public void discardByProjectAndOwner(ProjectId projectId, UserId ownerId) {
        // 同样不看影响行数：从没建过会话的人删出 0 行是正常结果
        mapper.deleteByProjectAndOwner(projectId.value(), ownerId.value());
    }
}
