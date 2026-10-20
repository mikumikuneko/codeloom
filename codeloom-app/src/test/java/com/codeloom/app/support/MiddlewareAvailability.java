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
 * 连中间件的测试的开关：目标可达才运行，否则跳过。
 *
 * <p>绝大多数测试用 {@code @TempDir} 起 git 仓库、跑假模型客户端，不碰中间件，因此
 * {@code mvn test} 在任何机器上都能全绿。中间件测试是例外，且不该把例外扩散：
 * 没装测试虚拟机上的 MySQL 和 Redis 时应看到跳过，而不是一片红。
 *
 * <p>跳过是一把双刃剑：跳过与通过只差构建输出里的一个数字，{@code BUILD SUCCESS} 相同。
 * 判据读错来源会让一百多个测试静默不跑而构建仍绿，比一片红更危险。因此配置来源必须与
 * 真实配置一致：环境变量优先，其次 classpath 上的 {@code application-local.yml}，
 * 最后是这里对应 {@code application.yml} 的默认值。读本地 yml 是因为中间件地址可能只写
 * 在那里，不读就会在只跑 {@code mvn test} 时静默跳过。
 *
 * <p>不能直接问 Spring 容器：{@code @EnabledIf} 在上下文启动前求值，那时既没有
 * {@code DataSource} 也没有 {@code RedisConnectionFactory}。
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
     * 供只碰数据库的测试用。连上后还要查一次表："MySQL 活着"与"codeloom 库建好"是两件事；
     * 只测连通性会让表未建的机器跑出一堆 "Table doesn't exist" 失败，而不是跳过。
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
     * 读取 {@code application-local.yml}，读不到当作空。
     *
     * <p>从 classpath 读取而不是拼文件路径：文件在 {@code src/main/resources} 下，
     * 测试 classpath 有它的编译副本，因此与进程工作目录无关。
     *
     * <p>读失败不抛异常：本类只决定是否跳过，自身出错不应让整个测试运行崩溃，
     * 否则配置问题会被伪装成一片编译或运行失败。
     */
    private static PropertySource<?> loadLocalConfig() {
        ClassPathResource resource = new ClassPathResource("application-local.yml");
        if (!resource.exists()) {
            // 这是正常情况：别人 clone 下来没有该文件，此时走默认值
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
