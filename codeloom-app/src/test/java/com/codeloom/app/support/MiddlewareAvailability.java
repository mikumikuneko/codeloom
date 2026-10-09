package com.codeloom.app.support;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;

/**
 * 连中间件的测试的开关：目标**连得上**才跑，否则跳过。
 *
 * <h2>为什么要有这个东西</h2>
 * 这个项目里绝大多数测试都不碰中间件 —— 用 {@code @TempDir} 起 git 仓库、跑假模型客户端，
 * 所以 {@code mvn test} 在任何人机器上都能全绿。连中间件的测试是这个性质的例外，
 * 而它不该把例外扩散出去：别人 clone 下来没装那台虚拟机上的 MySQL 和 Redis，
 * 应该看到「跳过」，而不是一片红。
 *
 * <h2>跳过是一把双刃剑，这个类得为此负责</h2>
 * 「跳过」和「通过」在构建输出里只差一个数字，而 {@code BUILD SUCCESS} 是一样的。
 * 判据一旦读错了源，表现就是**一百多个测试静默不跑、构建绿的** —— 那比一片红危险得多，
 * 因为它不会有人去看。所以下面那几个值的来源必须和真正的配置**一致**。
 *
 * <h2>参数从哪儿来（优先级和 Spring 一致）</h2>
 * <ol>
 *   <li>环境变量 —— {@code @EnabledIf} 在网络、Redis 上都能覆盖所有情况，它最高；
 *   <li>{@code application-local.yml} —— 本地开发那份（classpath 上，不进 jar）；
 *   <li>写在这里的默认值 —— 对应 {@code application.yml} 里那几个 {@code :localhost}。
 * </ol>
 *
 * <p>为什么要读 {@code application-local.yml} 而不是只认环境变量、默认值靠手抄：
 * 中间件地址一旦只写在那份文件里，不带环境变量跑 {@code mvn test} 就会跟丢，
 * 把一百多个测试静默跳过。用 {@link YamlPropertySourceLoader} 读它，
 * 是把这个"记得回来改"变成"自动跟着走"。
 *
 * <p>为什么不能直接问 Spring 容器：{@code @EnabledIf} 在 Spring 上下文启动**之前**求值，
 * 那时候既没有 {@code DataSource} 也没有 {@code RedisConnectionFactory}。
 */
public final class MiddlewareAvailability {

    private static final PropertySource<?> LOCAL = loadLocalConfig();

    private static final String DB_HOST = value("codeloom.db-host", "CODELOOM_DB_HOST", "localhost");
    private static final String DB_PORT = value("codeloom.db-port", "CODELOOM_DB_PORT", "3306");
    private static final String DB_NAME = value("codeloom.db-name", "CODELOOM_DB_NAME", "codeloom");
    private static final String DB_USER = value("codeloom.db-user", "CODELOOM_DB_USER", "root");
    private static final String DB_PASSWORD = value("codeloom.db-password", "CODELOOM_DB_PASSWORD", "root");

    private static final String REDIS_HOST = value("codeloom.redis-host", "CODELOOM_REDIS_HOST", "localhost");
    private static final int REDIS_PORT =
            Integer.parseInt(value("codeloom.redis-port", "CODELOOM_REDIS_PORT", "6379"));
    private static final String REDIS_PASSWORD =
            value("codeloom.redis-password", "CODELOOM_REDIS_PASSWORD", "root");

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private MiddlewareAvailability() {
    }

    /**
     * 供只碰数据库的测试用。
     *
     * <p>连上了还要再查一次表：「MySQL 活着」和「codeloom 库建好了」是两件事。
     * 只测连通性的话，表没建的那台机器会跑出一堆「Table doesn't exist」的失败，
     * 而不是干脆跳过。
     */
    public static boolean isDatabaseReachable() {
        String url = "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=2000";
        try (Connection connection = DriverManager.getConnection(url, DB_USER, DB_PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet ignored = statement.executeQuery("SELECT 1 FROM session LIMIT 1")) {
            return true;
        } catch (SQLException e) {
            // 连不上、库不存在、表没建 —— 都当成「这台机器上没准备」，安静跳过
            return false;
        }
    }

    /** 供只碰 Redis 的测试用。 */
    public static boolean isRedisReachable() {
        RedisURI uri = RedisURI.Builder.redis(REDIS_HOST, REDIS_PORT)
                .withPassword(REDIS_PASSWORD.toCharArray())
                .withTimeout(PROBE_TIMEOUT)
                .build();
        RedisClient client = RedisClient.create(uri);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            return "PONG".equalsIgnoreCase(connection.sync().ping());
        } catch (RuntimeException e) {
            return false;
        } finally {
            client.shutdown(Duration.ofMillis(100), Duration.ofSeconds(1));
        }
    }

    /** 供同时需要两者的测试用（比如执行租约：锁在 Redis，号在 MySQL）。 */
    public static boolean isEverythingReachable() {
        return isDatabaseReachable() && isRedisReachable();
    }

    /**
     * 读 {@code application-local.yml}，读不到就当成空的。
     *
     * <p>从 **classpath** 读（而不是拼一个文件路径）：那份文件在
     * {@code src/main/resources} 下，测试的 classpath 上有它的编译产物副本，
     * 所以不管测试进程的工作目录是什么都能拿到。
     *
     * <p>读不出来**不抛异常**：这个类只决定"跳不跳过"，它自己出问题不该把整个测试跑挂掉 ——
     * 那会把一个配置问题伪装成一整片编译/运行失败。
     */
    private static PropertySource<?> loadLocalConfig() {
        ClassPathResource resource = new ClassPathResource("application-local.yml");
        if (!resource.exists()) {
            // **这是正常的**：别人 clone 下来没有这个文件，那时候就该走默认值
            return new MapPropertySource("application-local-absent", Map.of());
        }
        try {
            return new YamlPropertySourceLoader().load("application-local", resource).getFirst();
        } catch (IOException | RuntimeException e) {
            return new MapPropertySource("application-local-unreadable", Map.of());
        }
    }

    /** 环境变量 → application-local.yml → 默认值，顺序和 Spring 的优先级一致。 */
    private static String value(String property, String environmentVariable, String fallback) {
        String fromEnvironment = System.getenv(environmentVariable);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        Object fromLocal = LOCAL.getProperty(property);
        if (fromLocal != null && !fromLocal.toString().isBlank()) {
            return fromLocal.toString();
        }
        return fallback;
    }
}
