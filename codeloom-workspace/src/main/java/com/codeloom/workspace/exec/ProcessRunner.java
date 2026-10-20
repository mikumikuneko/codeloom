package com.codeloom.workspace.exec;

import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommandTermination;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 执行子进程并接管它的生命周期：读输出、限时、超时杀整棵进程树。
 *
 * <p>{@code GitClient} 与 {@link LocalCommandExecutor} 共用这一层；权限策略由调用方
 * 负责。子进程的环境是重建的：名字匹配 {@link #SENSITIVE_ENV_NAME} 的变量和整片
 * {@code CODELOOM_} 命名空间都不传递，之后再合并调用方显式给出的
 * {@code extraEnvironment}。理由见
 * {@code docs/decisions/bug-fix/2026-10-10-subprocess-environment-is-scrubbed.md}。
 *
 * <p>四个实现约束：必须在等待进程结束前开始读两路输出（否则管道写满会死锁）；
 * 超时必须杀整棵进程树；输出必须限长；启动后立即关闭子进程的 stdin。
 */
public final class ProcessRunner {

    /** 等待进程时每段睡眠的毫秒数，决定超时判定和取消响应的粒度。 */
    private static final long POLL_MILLIS = 100;

    /** 强杀后等待进程退出的上限，见 {@link #exitCodeOf}。 */
    private static final long KILL_SETTLE_MILLIS = 2_000;

    /**
     * 等不到退出码时返回的占位值。
     *
     * <p>它只表示"不是 0"，不是命令结论；结论在 {@link ProcessOutcome#termination()}。
     */
    private static final int FALLBACK_EXIT_CODE = 1;

    /**
     * 子进程环境里不许带过去的名字，按形状匹配。
     *
     * <p>按形状匹配能自动覆盖新增变量；维护名单会漏。代价是误伤，例如
     * {@code TOKENIZERS_PARALLELISM} 这类普通变量也进不去，但比漏掉真密钥轻。
     */
    private static final Pattern SENSITIVE_ENV_NAME =
            Pattern.compile("KEY|PASSWORD|SECRET|TOKEN", Pattern.CASE_INSENSITIVE);

    /**
     * 我们自己的配置命名空间，整片丢掉。
     *
     * <p>其中除口令外还有 {@code CODELOOM_COMMAND_WHITELIST} 这类调参，不能让模型知道
     * 自己的审批白名单。比较前转大写：Windows 的环境名不区分大小写。
     */
    private static final String OWN_ENV_PREFIX = "CODELOOM_";

    private ProcessRunner() {
    }

    /**
     * 同步执行，直到进程结束、超时或被中断。
     *
     * @param command 要执行的 argv，不能为空
     * @param workingDirectory 工作目录，null 表示继承当前目录
     * @param extraEnvironment 显式给子进程的环境变量；在清洗之后合并，同名项以这里为准
     * @param timeout 时限；到点强杀整棵进程树
     * @param maxOutputChars 单个流（stdout、stderr 各自）的字符上限
     * @param charset 兜底字符集，UTF-8 解码失败时使用，见 {@link #decode(byte[], Charset)}
     * @param cancellation 取消信号；{@link CancellationToken#none()} 表示不取消
     * @return 进程结果；超时和取消通过 {@link CommandTermination} 报告
     * @throws ProcessException 启动失败或等待时线程被中断
     */
    public static ProcessOutcome run(List<String> command,
                                     Path workingDirectory,
                                     Map<String, String> extraEnvironment,
                                     Duration timeout,
                                     int maxOutputChars,
                                     Charset charset,
                                     CancellationToken cancellation) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(cancellation, "cancellation");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("命令不能为空");
        }

        // 提前查最常见的一种启动失败：否则 ProcessBuilder 只报 CreateProcess error=267，
        // 很难看出是工作目录不存在
        if (workingDirectory != null && !Files.isDirectory(workingDirectory)) {
            throw new ProcessException("工作目录不存在或不是目录: " + workingDirectory);
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        // 顺序是契约：先清掉默认继承的环境，再放清洗后的父环境，最后合并显式变量
        builder.environment().clear();
        builder.environment().putAll(childEnvironment());
        builder.environment().putAll(extraEnvironment);

        long startedAt = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ProcessException("无法启动进程: " + String.join(" ", command), e);
        }

        // 立刻关闭子进程的标准输入：不关的话，读输入的命令会一直等一个不会来的字节，
        // 例如不带参数的 cat 会把线程挂到超时。关闭写端等于递过去 EOF。
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // 关不上不该让这条命令失败：进程已经起来了，输出照样收得到
        }

        // 读两个流是阻塞 IO，虚拟线程足够
        try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Captured> outFuture = readers.submit(() -> capture(process.getInputStream(), maxOutputChars, charset));
            Future<Captured> errFuture = readers.submit(() -> capture(process.getErrorStream(), maxOutputChars, charset));

            CommandTermination termination = awaitCompletion(process, command, timeout, cancellation);

            Captured out = await(outFuture);
            Captured err = await(errFuture);
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

            return new ProcessOutcome(exitCodeOf(process), out.text(), err.text(),
                    out.truncated() || err.truncated(), durationMs, termination);
        }
    }

    /** 交给子进程的那份环境：本进程的环境，清掉见不得的那些。 */
    static Map<String, String> childEnvironment() {
        return scrubbed(System.getenv());
    }

    /**
     * 清洗规则本身，纯函数。
     *
     * <p>抽出来是为了能拿一份构造好的环境断言规则；本机环境里有没有可清的东西取决于
     * 这台机器。
     *
     * @param parentEnvironment 父进程环境
     * @return 保留下来的变量，按原顺序
     */
    static Map<String, String> scrubbed(Map<String, String> parentEnvironment) {
        Map<String, String> kept = new LinkedHashMap<>();
        parentEnvironment.forEach((name, value) -> {
            if (SENSITIVE_ENV_NAME.matcher(name).find()
                    || name.toUpperCase(Locale.ROOT).startsWith(OWN_ENV_PREFIX)) {
                return;
            }
            kept.put(name, value);
        });
        return kept;
    }

    /**
     * 分段等待进程结束，同时检查超时和用户的取消信号。
     *
     * <p>不能直接 {@code waitFor(timeout)}：那样用户按 Esc 也无人处理。按
     * {@value #POLL_MILLIS} 毫秒一段等待，每段醒来先看取消信号。被杀掉是结果而不是
     * 失败，抛异常会把已产生的输出一起扔掉。
     *
     * @return 进程是怎么结束的
     * @throws ProcessException 等待时线程被中断，已终止进程树
     */
    private static CommandTermination awaitCompletion(Process process, List<String> command,
                                                      Duration timeout,
                                                      CancellationToken cancellation) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remainingMillis = (deadline - System.nanoTime()) / 1_000_000;
            if (remainingMillis <= 0) {
                // 先杀树，再照常把结果收回来 —— 见类注释第 2 条：必须杀整棵树
                killTree(process);
                return CommandTermination.TIMED_OUT;
            }

            boolean finished;
            try {
                finished = process.waitFor(Math.min(remainingMillis, POLL_MILLIS), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                killTree(process);
                throw new ProcessException("等待进程时线程被中断，已终止进程树");
            }

            if (finished) {
                return CommandTermination.COMPLETED;
            }
            if (cancellation.isCancelled()) {
                // 先杀树再返回：必须等进程真的死亡，否则会留下僵尸进程和半成品文件
                killTree(process);
                return CommandTermination.CANCELLED;
            }
        }
    }

    /**
     * 读退出码；被杀掉的路径要先等进程真的退出。
     *
     * <p>{@link #killTree} 是异步的，立刻问 {@code exitValue()} 会抛
     * {@code IllegalThreadStateException}。等不到就返回 {@link #FALLBACK_EXIT_CODE}，
     * 它不代表成功与否，结论在 {@link ProcessOutcome#termination()}。
     *
     * @param process 已启动的进程
     * @return 退出码，或占位值
     */
    private static int exitCodeOf(Process process) {
        try {
            return process.waitFor(KILL_SETTLE_MILLIS, TimeUnit.MILLISECONDS)
                    ? process.exitValue()
                    : FALLBACK_EXIT_CODE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FALLBACK_EXIT_CODE;
        }
    }

    /**
     * 强杀整棵进程树：先杀子孙，再杀自己。
     *
     * <p>用 {@link ProcessHandle#descendants()} 而不是 {@code taskkill /T}：跨平台且
     * 不需要再起进程。顺序反了的话，父进程死亡后子进程可能被重新挂到 init，
     * 从而逃出遍历范围。
     *
     * @param process 要终止的进程
     */
    public static void killTree(Process process) {
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(ProcessHandle::destroyForcibly);
        handle.destroyForcibly();
    }

    private static Captured await(Future<Captured> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessException("读取进程输出时被中断");
        } catch (ExecutionException e) {
            throw new UncheckedIOException(new IOException("读取进程输出失败", e.getCause()));
        }
    }

    /**
     * 把流读到结尾，只留最后 {@code limit} 个字符。
     *
     * <p>先收字节、读完再解码：输出编码事先不知道，只有拿到字节后才能判断，见
     * {@link #decode(byte[], Charset)}。保留尾部而不是开头，因为命令的结论（报错、
     * 堆栈、构建失败）在结尾；开头被丢弃由 {@link ProcessOutcome#truncated()} 报告。
     * 进程被杀时流异常关闭是预期路径。
     *
     * <p>包级可见是为了让测试能拿构造好的字节流断言它。
     *
     * @param in 输入流
     * @param limit 保留的字符上限；非正数时只读完流并返回空串
     * @param fallback UTF-8 解码失败时的兜底字符集
     * @return 保留的文本及是否丢弃了开头
     */
    static Captured capture(InputStream in, int limit, Charset fallback) {
        if (limit <= 0) {
            // 上限为 0 表示不要内容，但流仍必须读到结尾，
            // 否则子进程会阻塞在写满的管道上
            drain(in);
            return new Captured("", true);
        }
        int byteCap = byteCapFor(limit);
        // 环形缓冲：第 p 个字节住在 ring[p % byteCap]，最后 byteCap 个字节总是齐的
        byte[] ring = new byte[byteCap];
        long total = 0;
        byte[] buffer = new byte[8192];
        try {
            int read;
            // 一直读到结尾：不读完的话子进程会阻塞在写满的管道上
            while ((read = in.read(buffer)) != -1) {
                // 单次读也可能比整个环还长 —— 那就只留它自己的尾巴
                for (int i = Math.max(0, read - byteCap); i < read; i++) {
                    ring[(int) ((total + i) % byteCap)] = buffer[i];
                }
                total += read;
            }
        } catch (IOException ignored) {
            // 进程被杀时流被强制关闭，这是预期路径
        }

        boolean truncated = total > byteCap;
        byte[] bytes = retained(ring, total, byteCap);
        // 切的是开头，所以第一个字节可能落在某个多字节字符的中间 —— 见 utf8Boundary
        int boundary = truncated ? utf8Boundary(bytes) : 0;
        String text = decode(Arrays.copyOfRange(bytes, boundary, bytes.length), fallback);
        if (text.length() > limit) {
            return new Captured(tailChars(text, limit), true);
        }
        return new Captured(text, truncated);
    }

    /** 读到结尾后丢弃；上限为 0 时用，流仍然必须被读完。 */
    private static void drain(InputStream in) {
        byte[] buffer = new byte[8192];
        try {
            while (in.read(buffer) != -1) {
                // 读完就行，内容不要
            }
        } catch (IOException ignored) {
            // 进程被杀时流被强制关闭，这是预期路径
        }
    }

    /** 从环里按顺序取出保留下来的那一段。 */
    private static byte[] retained(byte[] ring, long total, int byteCap) {
        int size = (int) Math.min(total, byteCap);
        // 环转满时，最早那个保留下来的字节在 total % byteCap 上；没转满就是从 0 开始
        int start = size == byteCap ? (int) (total % byteCap) : 0;
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            out[i] = ring[(start + i) % byteCap];
        }
        return out;
    }

    /**
     * 头被切掉后，第一个字节可能只是某个多字节字符的中间一截；挪到字符边界上。
     *
     * <p>否则严格解码会失败，整段退回兜底字符集，本来正确的 UTF-8 中文会变成乱码。
     * UTF-8 续字节形如 {@code 10xxxxxx}，最多前进 3 个字节。
     *
     * @param bytes 保留下来的字节
     * @return 第一个完整字符的起始下标
     */
    private static int utf8Boundary(byte[] bytes) {
        int at = 0;
        while (at < bytes.length && (bytes[at] & 0xC0) == 0x80) {
            at++;
        }
        return at;
    }

    /**
     * 留最后 {@code limit} 个字符。
     *
     * <p>切点落在低代理上时再前进一个：高代理已被切掉，剩下半对只会变成孤立的
     * {@code \ud83d}。
     *
     * @param text 完整文本
     * @param limit 保留的字符数
     * @return 尾部文本
     */
    private static String tailChars(String text, int limit) {
        int from = text.length() - limit;
        if (Character.isLowSurrogate(text.charAt(from))) {
            from++;
        }
        return text.substring(from);
    }

    /**
     * 字节上限 = 字符上限 × 单字符最多占的字节数（UTF-8 为 4）。
     *
     * <p>留满余量，免得字节先到顶、把本来留得下的字符切掉；上限仍然有界。
     *
     * @param charLimit 字符上限
     * @return 对应的字节上限
     */
    private static int byteCapFor(int charLimit) {
        return charLimit > Integer.MAX_VALUE / MAX_BYTES_PER_CHAR
                ? Integer.MAX_VALUE
                : charLimit * MAX_BYTES_PER_CHAR;
    }

    /** UTF-8 里一个字符最多占的字节数。 */
    private static final int MAX_BYTES_PER_CHAR = 4;

    /**
     * 本机子进程默认使用的编码，即 {@code native.encoding} 系统属性。
     *
     * <p>不能用 {@link Charset#defaultCharset()}：从 JDK 18 起它恒为 UTF-8（JEP 400），
     * 与子进程实际输出无关。中文 Windows 上裸 JVM 等子进程跟随控制台代码页（GBK）。
     *
     * @return 本机编码；属性缺失、空白或不受支持时退回 JVM 默认字符集
     */
    public static Charset nativeCharset() {
        String name = System.getProperty("native.encoding");
        if (name != null && !name.isBlank() && Charset.isSupported(name)) {
            return Charset.forName(name);
        }
        return Charset.defaultCharset();
    }

    /**
     * 字节转文本：先严格按 UTF-8 解，失败后按 {@code fallback} 解。
     *
     * <p>同一台机器上不同工具的输出编码不同（实测 Windows 11 + JDK 21：node 与 mvn 是
     * UTF-8，ProcessBuilder 直接拉起的裸 JVM 是 GBK），固定任一种都会让另一类乱码。
     *
     * <p>已知局限：一段 GBK 字节若整体恰好是合法 UTF-8，会被判成 UTF-8 而解错；短输出
     * 理论上可能撞上。
     *
     * @param bytes 原始字节
     * @param fallback UTF-8 解码失败时使用的字符集
     * @return 解码后的文本
     */
    static String decode(byte[] bytes, Charset fallback) {
        String utf8 = decodeOrNull(bytes, StandardCharsets.UTF_8);
        return utf8 != null ? utf8 : new String(bytes, fallback);
    }

    /** 严格解码；遇到非法字节序列时返回 null，而不是塞入 U+FFFD。 */
    private static String decodeOrNull(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer decoded = CharBuffer.allocate(Math.max(1, bytes.length));
        try {
            // endOfInput=false：结尾不完整的多字节序列不算错误，留在缓冲区里即可 ——
            // 输出被切断时最后一个字常常只剩半截，不应因此整段退回兜底字符集
            CoderResult result = decoder.decode(ByteBuffer.wrap(bytes), decoded, false);
            if (result.isError()) {
                result.throwException();
            }
            return decoded.flip().toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * 一个流收下来的那一段。
     *
     * @param text 解码后的文本，保留尾部，见 {@link #capture}
     * @param truncated 开头是否被丢弃
     */
    record Captured(String text, boolean truncated) {
    }
}
