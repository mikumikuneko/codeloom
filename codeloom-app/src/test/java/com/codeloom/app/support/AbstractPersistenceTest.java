package com.codeloom.app.support;

import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * 连库测试的共同前提：启动真 Spring 上下文、真连 MySQL、跑完回滚。
 *
 * <p>必须启动完整上下文而不是手搭 SqlSessionFactory：手搭的 MyBatis 配置可能与生产不同
 *（例如 {@code map-underscore-to-camel-case} 的默认值），会出现"测试通过但线上映射错位"；
 * 完整上下文也顺带验证 {@code @MapperScan} 和事务代理确实生效。
 *
 * <p>{@code webEnvironment = NONE}：测试持久化不需要 servlet 容器，附带好处是
 * Spring Security 自动配置不参与，省掉无关的过滤器链。
 *
 * <p>整个类 {@code @Transactional}：跑完回滚，不往开发库留数据。因此断言使用
 * {@code contains}/{@code doesNotContain} 而非精确相等 —— 库里可能有别人手工造的数据。
 * 需要精确断言的测试改用每次生成新 projectId 来隔离。
 *
 * <p>连不上库或表未建时整个类跳过，见 {@link MiddlewareAvailability}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIf("com.codeloom.app.support.MiddlewareAvailability#isDatabaseReachable")
@Transactional
public abstract class AbstractPersistenceTest {

    // 定义在 TestUsers 里（不继承本类的测试也要用同一批 id），这里只是给子类省一次 import
    protected static final UserId OWNER = TestUsers.OWNER;
    protected static final UserId ALICE = TestUsers.ALICE;
    protected static final UserId BOB = TestUsers.BOB;
    protected static final UserId CAROL = TestUsers.CAROL;

    /**
     * 用来干 mapper 接口故意不提供的事：直接读原始列、直接改 {@code fencing_token}
     * 这类「只有绕过仓储才看得到」的状态。
     */
    @Autowired
    protected JdbcTemplate jdbc;

    /** 房主固定取第一个成员 —— 测试里大多不在意谁是房主，但它必须是成员之一。 */
    protected static Project newProject(String name, UserId... members) {
        return new Project(ProjectId.generate(), members[0], name,
                "D:/repos/" + UUID.randomUUID(), Set.of(members));
    }
}
