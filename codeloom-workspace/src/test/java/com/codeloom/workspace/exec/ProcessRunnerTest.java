package com.codeloom.workspace.exec;

import com.codeloom.domain.port.CancellationToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * 两类东西：解码规则是**纯函数**，不用真起进程就能验透；**环境清洗**只能真跑 ——
 * 它发生在子进程那一侧，在父进程里断言等于没验。
 *
 * <p>规则本身见 {@link ProcessRunner#decode(byte[], Charset)} 与
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
    @DisplayName("【截断边界】结尾正好切在多字节字符中间，**不该**整段退回兜底字符集")
    void aTrailingPartialCharacterDoesNotTriggerTheFallback() {
        // 输出被字节上限切断时，最后一个字常常只剩半截。要是那半截被判成"不是 UTF-8"，
        // 整段（本来完全正确的中文）就会改用 GBK 解 —— 满屏乱码，
        // 而原因只是最后一字节不完整
        byte[] full = "构建完成".getBytes(StandardCharsets.UTF_8);
        byte[] cut = Arrays.copyOf(full, full.length - 1);

        assertThat(ProcessRunner.decode(cut, GBK)).isEqualTo("构建完");
    }

    @Test
    @DisplayName("空字节流解出空串，不报错")
    void decodesEmptyInput() {
        assertThat(ProcessRunner.decode(new byte[0], GBK)).isEmpty();
    }

    @Test
    @DisplayName("【已知局限】这段 GBK 恰好也是合法 UTF-8，会被判成 UTF-8 —— 规则不是万无一失")
    void anAmbiguousSequenceGetsTheWrongAnswer() {
        // 「一」的 GBK 是 D2 BB，而 D2 BB 本身就是一个合法的 UTF-8 双字节序列（U+04BB）。
        // 这种"两种解码都说得通"的输入无法靠字节判断 —— 测试把它钉在这里，
        // 免得以后有人以为这条规则是完备的
        byte[] ambiguous = "一".getBytes(GBK);

        assertThat(ProcessRunner.decode(ambiguous, GBK))
                .as("长句子撞不上，但很短的输出理论上会解错")
                .isNotEqualTo("一");
    }

    @Test
    @DisplayName("nativeCharset 拿的是本机子进程实际用的那个，不是恒为 UTF-8 的 defaultCharset")
    void nativeCharsetFollowsThePlatformNotTheJvmDefault() {
        // JDK 18 起 Charset.defaultCharset() 恒为 UTF-8（JEP 400），拿它当兜底等于没兜。
        // 这条在哪个平台跑都成立：系统属性有值就必须用它
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
     * 规则本身。拿一份**构造好的**环境去验，而不是这台机器上碰巧有的那份 ——
     * 后者在没有密钥的机器上会让整条断言空转。
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
        // 大小写不敏感：Windows 的环境名本来就不区分大小写，父进程里一个 codeloom_* 不能漏过去
        parent.put("codeloom_db_password", "example-not-a-real-password");

        Map<String, String> kept = ProcessRunner.scrubbed(parent);

        assertThat(kept).containsOnlyKeys("PATH", "HOME");
        assertThat(kept.get("PATH")).isEqualTo("/usr/bin");
    }

    @Test
    @DisplayName("【已知代价】带这几个字样的普通变量也会被丢掉，比如 TOKENIZERS_PARALLELISM")
    void scrubbingAlsoDropsInnocentNamesThatMatchTheShape() {
        // 按形状匹配换来的正是"新加的环境变量自动被盖住"，代价就是这类误伤。
        // 钉在这里，免得以后有人看到某个工具读不到自己的变量时以为见了鬼
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
        // 这条钉住的是顺序，不是规则：extraEnvironment 要是被合并到清洗之前，
        // GitClient 那份自己的配置就会被清掉，表现是 git 忽然读不到设置
        List<String> names = probeEnvironment(Map.of("NPM_TOKEN", "example-not-a-real-token"));

        assertThat(names).contains("NPM_TOKEN");
    }

    /**
     * 起一个真进程，读它**自己**的环境变量名。
     *
     * <p>用 {@code java} 当那个子进程：跑测试的机器上必然有它，不用再引入一个环境依赖；
     * 而探针自己只用 JDK，所以 classpath 上有它自己那个目录就够了。
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

    /** 和 {@link ProcessRunner#SENSITIVE_ENV_NAME} 同一套判据，写成断言里能用的样子。 */
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
