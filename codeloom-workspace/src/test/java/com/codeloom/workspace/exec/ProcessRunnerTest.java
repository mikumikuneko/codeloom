package com.codeloom.workspace.exec;

import com.codeloom.domain.port.CancellationToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解码规则是纯函数，可以直接验证；环境清洗发生在子进程一侧，必须真起进程。
 *
 * <p>规则见 {@link ProcessRunner#decode(byte[], Charset)} 与
 * {@link ProcessRunner#scrubbed(java.util.Map)}。
 */
class ProcessRunnerTest {

    /** 中文 Windows 上子进程实际用的编码。写死是为了让断言在两台机器上有同样的含义。 */
    private static final Charset GBK = Charset.forName("GBK");

    @Test
    @DisplayName("UTF-8 的字节照常解出来 —— 绝大多数工具走这条")
    void decodesUtf8() {
        byte[] bytes = "中文 abc".getBytes(StandardCharsets.UTF_8);

        assertThat(ProcessRunner.decode(bytes, GBK)).isEqualTo("中文 abc");
    }

    @Test
    @DisplayName("【核心】GBK 的字节按兜底字符集解出来 —— 不再是一串 U+FFFD")
    void decodesNativeEncodingWhenItIsNotUtf8() {
        byte[] bytes = "中文 abc".getBytes(GBK);

        assertThat(bytes)
                .as("前提：这两个字符的 GBK 字节确实不构成合法 UTF-8，否则这条测试是空转")
                .isNotEqualTo("中文 abc".getBytes(StandardCharsets.UTF_8));
        assertThat(ProcessRunner.decode(bytes, GBK)).isEqualTo("中文 abc");
    }

    @Test
    @DisplayName("纯 ASCII 两条路结果相同 —— 命令行的绝大部分内容其实都是 ASCII")
    void asciiDecodesTheSameEitherWay() {
        byte[] bytes = "Tests run: 12, Failures: 0".getBytes(StandardCharsets.US_ASCII);

        assertThat(ProcessRunner.decode(bytes, GBK)).isEqualTo("Tests run: 12, Failures: 0");
        assertThat(ProcessRunner.decode(bytes, StandardCharsets.UTF_8))
                .isEqualTo("Tests run: 12, Failures: 0");
    }

    @Test
    @DisplayName("【截断边界】结尾那半截字，**不该**让整段退回兜底字符集")
    void aTrailingPartialCharacterDoesNotTriggerTheFallback() {
        // 进程被杀在写到一半时，最后一个字只剩半截。若判成"不是 UTF-8"，
        // 整段本来正确的中文会改用 GBK 解而变成乱码
        byte[] full = "构建完成".getBytes(StandardCharsets.UTF_8);
        byte[] cut = Arrays.copyOf(full, full.length - 1);

        assertThat(ProcessRunner.decode(cut, GBK)).isEqualTo("构建完");
    }

    @Test
    @DisplayName("【保尾】超上限时留下的是结尾 —— 命令的结论在那儿")
    void keepsTheTail() {
        ProcessRunner.Captured captured = ProcessRunner.capture(
                new ByteArrayInputStream("0123456789ABCDEFGHIJ".getBytes(StandardCharsets.UTF_8)),
                10, GBK);

        assertThat(captured.text()).isEqualTo("ABCDEFGHIJ");
        assertThat(captured.truncated()).isTrue();
    }

    @Test
    @DisplayName("【保尾·边界】开头切在多字节字符中间，**不该**整段退回兜底字符集")
    void aLeadingPartialCharacterDoesNotTriggerTheFallback() {
        // 切的是开头，第一个字节可能只是某个汉字的中间一截（0xBB 是「建」的续字节）。
        // 不挪到字符边界时严格解码会失败，整段改用 GBK 解而变成乱码
        byte[] bytes = "构建完成".getBytes(StandardCharsets.UTF_8);

        ProcessRunner.Captured captured = ProcessRunner.capture(
                new ByteArrayInputStream(bytes), 2, GBK);

        assertThat(captured.text())
                .as("每个汉字 3 字节：上限 2 字符 → 字节窗口 8 字节，正好从「建」的中间开始")
                .isEqualTo("完成");
        assertThat(captured.truncated()).isTrue();
    }

    @Test
    @DisplayName("正好等于上限时不算截断 —— 少切一刀")
    void doesNotTruncateExactlyAtTheLimit() {
        ProcessRunner.Captured captured = ProcessRunner.capture(
                new ByteArrayInputStream("ABCDEFGHIJ".getBytes(StandardCharsets.UTF_8)), 10, GBK);

        assertThat(captured.text()).isEqualTo("ABCDEFGHIJ");
        assertThat(captured.truncated()).isFalse();
    }

    @Test
    @DisplayName("空字节流解出空串，不报错")
    void decodesEmptyInput() {
        assertThat(ProcessRunner.decode(new byte[0], GBK)).isEmpty();
    }

    @Test
    @DisplayName("【已知局限】这段 GBK 恰好也是合法 UTF-8，会被判成 UTF-8 —— 规则不是万无一失")
    void anAmbiguousSequenceGetsTheWrongAnswer() {
        // 「一」的 GBK 是 D2 BB，而 D2 BB 本身是合法的 UTF-8 双字节序列（U+04BB）。
        // 两种解码都说得通的输入无法靠字节判断；这条测试固定该局限
        byte[] ambiguous = "一".getBytes(GBK);

        assertThat(ProcessRunner.decode(ambiguous, GBK))
                .as("长句子撞不上，但很短的输出理论上会解错")
                .isNotEqualTo("一");
    }

    @Test
    @DisplayName("nativeCharset 拿的是本机子进程实际用的那个，不是恒为 UTF-8 的 defaultCharset")
    void nativeCharsetFollowsThePlatformNotTheJvmDefault() {
        // JDK 18 起 Charset.defaultCharset() 恒为 UTF-8（JEP 400），不能当兜底；
        // 系统属性有值时必须使用它
        String declared = System.getProperty("native.encoding");

        if (declared != null && Charset.isSupported(declared)) {
            assertThat(ProcessRunner.nativeCharset()).isEqualTo(Charset.forName(declared));
        } else {
            assertThat(ProcessRunner.nativeCharset()).isNotNull();
        }
    }

    // ------------------------------------------------------------------
    // 子进程环境
    // ------------------------------------------------------------------

    /**
     * 规则本身。用构造好的环境断言，而不是本机碰巧有的那份：后者在没有密钥的机器上会
     * 让断言空转。
     */
    @Test
    @DisplayName("凭据形状的名字和整片 CODELOOM_ 命名空间被丢掉，PATH 这类照旧")
    void scrubbingDropsCredentialShapedNames() {
        Map<String, String> parent = new LinkedHashMap<>();
        parent.put("PATH", "/usr/bin");
        parent.put("HOME", "/home/someone");
        parent.put("CODELOOM_COMMAND_WHITELIST", "ls,mvn");
        parent.put("CODELOOM_SECRET_KEY", "example-not-a-real-key");
        parent.put("AWS_SECRET_ACCESS_KEY", "example-not-a-real-key");
        parent.put("NPM_TOKEN", "example-not-a-real-token");
        // 大小写不敏感：Windows 环境名不区分大小写，codeloom_* 也必须被清掉
        parent.put("codeloom_db_password", "example-not-a-real-password");

        Map<String, String> kept = ProcessRunner.scrubbed(parent);

        assertThat(kept).containsOnlyKeys("PATH", "HOME");
        assertThat(kept.get("PATH")).isEqualTo("/usr/bin");
    }

    @Test
    @DisplayName("【已知代价】带这几个字样的普通变量也会被丢掉，比如 TOKENIZERS_PARALLELISM")
    void scrubbingAlsoDropsInnocentNamesThatMatchTheShape() {
        // 按形状匹配能自动覆盖新增变量，代价就是这类误伤；固定该行为，
        // 避免把"工具读不到自己的变量"误判为异常
        Map<String, String> parent = Map.of("TOKENIZERS_PARALLELISM", "false");

        assertThat(ProcessRunner.scrubbed(parent)).isEmpty();
    }

    @Test
    @DisplayName("【真跑】子进程的环境里没有凭据形状的名字，而 PATH 还在")
    void aRealChildGetsAScrubbedEnvironment() {
        List<String> names = probeEnvironment(Map.of());

        assertThat(names)
                .as("凭据形状的名字一个都不该在子进程里出现")
                .noneMatch(ProcessRunnerTest::looksSensitive);
        assertThat(names)
                .as("PATH 必须还在，否则子进程找不到任何程序")
                .contains("PATH");
    }

    @Test
    @DisplayName("【真跑】显式传进去的变量照样到得了子进程 —— 清洗在前，合并在后")
    void explicitlyPassedVariablesSurviveTheScrub() {
        // 固定的是顺序而非规则：extraEnvironment 若在清洗前合并，
        // GitClient 自己的配置会被清掉，git 会读不到设置
        List<String> names = probeEnvironment(Map.of("NPM_TOKEN", "example-not-a-real-token"));

        assertThat(names).contains("NPM_TOKEN");
    }

    /**
     * 起一个真进程，读它自己的环境变量名。
     *
     * <p>用 {@code java} 当子进程：跑测试的机器上必然有它。探针只用 JDK，
     * classpath 指向它自己的目录即可。
     *
     * @param extraEnvironment 显式传给子进程的额外变量
     * @return 子进程环境变量名，统一大写
     */
    private static List<String> probeEnvironment(Map<String, String> extraEnvironment) {
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", javaExecutableName()).toString(),
                "-cp", classesDirectoryOfProbe(),
                EnvironmentProbe.class.getName());

        ProcessOutcome outcome = ProcessRunner.run(command, null, extraEnvironment,
                Duration.ofSeconds(60), 100_000, ProcessRunner.nativeCharset(), CancellationToken.none());

        assertThat(outcome.succeeded())
                .as("探针本身没跑起来，下面的断言没有意义：%s", outcome.combinedOutput())
                .isTrue();
        // 比对上统一大写：Windows 的环境名不区分大小写，PATH 在那边叫 Path
        return outcome.stdout().lines()
                .map(line -> line.strip().toUpperCase(Locale.ROOT))
                .filter(line -> !line.isEmpty())
                .toList();
    }

    /** 与 {@code ProcessRunner.SENSITIVE_ENV_NAME} 同一套判据，写成断言可用的形式。 */
    private static boolean looksSensitive(String name) {
        return name.contains("KEY") || name.contains("PASSWORD") || name.contains("SECRET")
                || name.contains("TOKEN") || name.startsWith("CODELOOM_");
    }

    private static String javaExecutableName() {
        return isWindows() ? "java.exe" : "java";
    }

    private static String classesDirectoryOfProbe() {
        try {
            return Path.of(EnvironmentProbe.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
        } catch (Exception e) {
            throw new IllegalStateException("取不到测试类的目录，探针起不来", e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
