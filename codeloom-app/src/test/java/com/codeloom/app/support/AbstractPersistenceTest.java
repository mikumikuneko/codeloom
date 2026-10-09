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
 * 连库测试的共同前提：起真 Spring 上下文、真连 MySQL、跑完回滚。
 *
 * <h2>为什么起的是完整上下文，而不是手搭一个 SqlSessionFactory</h2>
 * 手搭的话测试用的 MyBatis 配置和生产的就不是一份 —— 比如
 * {@code map-underscore-to-camel-case} 这个默认值两边可能不同，
 * 于是「测试过了但线上映射错位」这种事就有了发生的余地。
 * 起完整上下文等于顺便验了 {@code @MapperScan} 有没有生效、事务代理有没有装上。
 *
 * <h2>为什么 {@code webEnvironment = NONE}</h2>
 * 测的是持久化，不需要 servlet 容器。副作用是 Spring Security 的自动配置
 * 因为没有 web 上下文而不参与，省掉一堆和本测试无关的过滤器链。
 *
 * <h2>为什么整个类 {@code @Transactional}</h2>
 * 跑完自动回滚，不往开发库里留垃圾。所以断言写的是
 * {@code contains / doesNotContain} 而不是精确相等 —— 库里可能有别人手工造的会话。
 * 需要精确断言的测试（比如聊天室的「最近 N 条」）改用**每次生成新 projectId** 来隔离，
 * 那样这个项目下的消息必然只有本测试自己插的。
 *
 * <p>连不上库（或表没建）时整个类跳过，见 {@link MiddlewareAvailability}。
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
