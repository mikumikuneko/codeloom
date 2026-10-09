package com.codeloom.workspace.exec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解码规则是**纯函数**，所以不用真起进程就能把它验透 —— 而这正是它值得被抽出来的原因。
 *
 * <p>规则本身见 {@link ProcessRunner#decode(byte[], Charset)}。
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
}
