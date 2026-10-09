package com.codeloom.app.persistence;

import com.codeloom.app.support.AbstractPersistenceTest;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.session.SessionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code session} 表的真库测试。
 *
 * <p>和 {@code SessionRowTest} 的分工：那边测「行 ↔ 领域对象」的转换（纯函数，不碰库），
 * 这里测**被 MySQL 和 MyBatis 真实执行之后**的结果。有几件事只有跑真库才能验证：
 *
 * <ul>
 *   <li>{@code INSERT ... ON DUPLICATE KEY UPDATE ... AS new} 的写法真能跑，且
 *       「存在就更新」那一支真的走到
 *   <li>把 record 当结果类型，MyBatis 能不能按列别名把这 10 个值正确装配出来 ——
 *       纸面上最不确定的一点，因为 record 没有 {@code getId()}
 * </ul>
 *
 * <h2>这张表上已经没有 fencing_token 了</h2>
 * 那一列连同它的安全约束（普通写入绝不能把它写回旧值）搬到了 {@code workspace} 表 ——
 * 锁锁的是**一棵树**，号的键必须和锁的键是同一个。那两条断言现在住在
 * {@code WorkspacePersistenceTest}。
 */
class SessionPersistenceTest extends AbstractPersistenceTest {

    private static final ProjectId PROJECT_ID =
            ProjectId.of("99999999-9999-9999-9999-999999999999");

    @Autowired
    private SessionRepository sessions;

    @Test
    @DisplayName("会话写进去再读出来，还是同一个对象 —— 10 列的名字与类型都对得上")
    void roundTripsThroughRealMySQL() {
        Session session = thinkingSession();

        sessions.save(session);

        assertThat(sessions.findById(session.id())).contains(session);
    }

    @Test
    @DisplayName("upsert 的「存在就更新」这一支真的走到了")
    void saveUpdatesAnExistingSession() {
        Session session = idleSession();
        sessions.save(session);

        Session advanced = session.withState(SessionState.THINKING).nextTurn();
        sessions.save(advanced);

        Session reloaded = sessions.findById(session.id()).orElseThrow();
        assertThat(reloaded.state()).isEqualTo(SessionState.THINKING);
        assertThat(reloaded.turnIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("可空列真的落成 NULL，不是空串")
    void nullableColumnsStayNullInTheDatabase() {
        // systemPrompt 为 null 表示这个会话不加系统提示词。
        //
        // 盯住「NULL 而不是空串」是有原因的：ContextAssembler 正是按 null/blank
        // 来决定加不加那条 system 消息的，空串会把这两种情况合并成一种。
        ModelConfig noPrompt = new ModelConfig(
                ProviderId.of("kimi"), "some-model", null);
        Session session = Session.create(SessionId.generate(), PROJECT_ID, OWNER, noPrompt);

        sessions.save(session);

        assertThat(sessions.findById(session.id())).contains(session);
        assertThat(rawColumn(session.id(), "system_prompt", String.class)).isNull();
    }

    @Test
    @DisplayName("findAll 是全局查询：崩溃恢复靠它扫全库，存进去的会话都得读得到")
    void findAllReadsEverySession() {
        Session idle = idleSession();
        Session thinking = thinkingSession();
        sessions.save(idle);
        sessions.save(thinking);

        List<SessionId> all = sessions.findAll().stream().map(Session::id).toList();

        // 全局查询，库里可能有别人造的会话，所以只看我们自己这两条
        assertThat(all).contains(idle.id(), thinking.id());
    }

    // ------------------------------------------------------------------

    private Session idleSession() {
        return idleSession("你是协作开发助手。");
    }

    private Session idleSession(String systemPrompt) {
        ModelConfig model = new ModelConfig(
                ProviderId.of("deepseek"), "deepseek-chat", systemPrompt);
        return Session.create(SessionId.generate(), PROJECT_ID, OWNER, model);
    }

    private Session thinkingSession() {
        return idleSession().withState(SessionState.THINKING);
    }

    /** 绕过仓储直接读某一列 —— 仓储有意不暴露这些列。 */
    private <T> T rawColumn(SessionId id, String column, Class<T> type) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM session WHERE id = ?", type, id.value());
    }
}
