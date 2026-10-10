package com.codeloom.workspace.exec;

import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommandTermination;

import java.io.ByteArrayOutputStream;
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
 * 跑一个子进程并把它管住：读输出、限时、杀树。
 *
 * <p>抽出来是因为**有两处都要跑进程**：{@code GitClient}（参数由我们构造，可信）
 * 和 {@code LocalCommandExecutor}（参数来自模型，不可信）。两者的管控逻辑一样，
 * 只是权限策略不同 —— 那部分由调用方负责，这里只管"把进程跑干净"。
 *
 * <h2>子进程的环境是**重建**出来的，不是原样继承下去的</h2>
 * 凭据形状的名字（见 {@link #SENSITIVE_ENV_NAME}）和整片 {@code CODELOOM_} 命名空间都不递给子进程，
 * 其余照旧（{@code PATH}、{@code HOME}、locale、代理变量都在）。因为 {@code run_command}
 * 的参数来自模型，而这台机器上的 JVM 环境里有 {@code CODELOOM_SECRET_KEY} ——
 * 那是解开库里用户 API Key 的主密钥，一条 {@code echo} 就能读走。
 *
 * <p>清完之后**才**合并调用方显式给的 {@code extraEnvironment}，所以"这个程序确实需要某个变量"
 * 另有路可走；默认那条路是干净的。反过来把顺序写反，显式给的变量会被清掉，
 * 表现是 git 忽然读不到自己那份配置。
 *
 * <h2>四个容易写错的地方</h2>
 * <ol>
 *   <li><b>必须在等进程结束之前就开始读输出</b>。管道缓冲区只有几十 KB，
 *       写满之后子进程会阻塞在写操作上，而父进程正阻塞在 waitFor —— 双方死锁。
 *       所以两个流各起一个虚拟线程去读。</li>
 *   <li><b>超时要杀整棵进程树</b>，不是只杀父进程。{@code mvn} 会拉起子 JVM，
 *       只杀父进程的话子 JVM 会变成孤儿继续跑、继续占着文件锁，
 *       下一个 turn 的构建就会莫名其妙地失败。</li>
 *   <li><b>输出要截断</b>。一次构建可能刷出几万行，不截断的话会同时打爆
 *       上下文窗口和数据库。</li>
 *   <li><b>要关掉子进程的标准输入</b>。它默认是一条开着的管道：任何去读输入的命令
 *       都会一直等下去 —— 一条不带参数的 {@code cat} 就能把线程挂到超时为止。
 *       见 {@link #run} 里那句 {@code close()}。</li>
 * </ol>
 */
public final class ProcessRunner {

    /** 等待进程时每段睡多久。决定超时判定和取消响应的粒度。 */
    private static final long POLL_MILLIS = 100;

    /** 杀完之后最多等它多久才去读退出码。见 {@link #exitCodeOf}。 */
    private static final long KILL_SETTLE_MILLIS = 2_000;

    /**
     * 等不到退出码时报的那个数。
     *
     * <p>它只是个"不是 0"的占位，**不代表这条命令的结论** —— 结论在
     * {@link ProcessOutcome#termination()} 里。选 1 是因为它是"进程没成功"最常见的码。
     */
    private static final int FALLBACK_EXIT_CODE = 1;

    /**
     * 子进程环境里**不许带过去**的名字。
     *
     * <p>凭据的名字就那么几种形状，按形状匹配比维护一张名单可靠：名单会漏，而且每加一个新变量
     * 都得记得回来补；模式匹配自动盖住它。deepseek-harness 用同一套规则清它的每一个子进程。
     *
     * <p>代价是误伤：{@code TOKENIZERS_PARALLELISM} 这种带 TOKEN 字样的普通变量也进不去。
     * 那比漏掉一把真密钥轻得多。
     */
    private static final Pattern SENSITIVE_ENV_NAME =
            Pattern.compile("KEY|PASSWORD|SECRET|TOKEN", Pattern.CASE_INSENSITIVE);

    /**
     * 我们自己的配置命名空间，整片丢掉。
     *
     * <p>它里面除了口令，还有 {@code CODELOOM_COMMAND_WHITELIST} 这类**不该让模型看见的调参** ——
     * 让 agent 读到自己的审批白名单，等于把"哪条命令不会被人看见"告诉它。
     *
     * <p>比对上要 {@code toUpperCase}：Windows 的环境名不区分大小写，父进程里一个
     * {@code codeloom_*} 不这样做就会原样漏过去。
     */
    private static final String OWN_ENV_PREFIX = "CODELOOM_";

    private ProcessRunner() {
    }

    /**
     * 同步执行，直到进程结束、超时、或被中断。
     *
     * @param workingDirectory 工作目录，null 表示继承当前目录
     * @param extraEnvironment 显式给子进程的环境变量。**在清洗之后合并**，所以调用方给的
     *                         一定到得了（见类注释），同名项以这里为准
     * @param timeout          时限；到点强杀整棵进程树
     * @param maxOutputChars   单个流（stdout / stderr 各自）的字符上限
     * @param charset          **兜底**字符集：解出来不是合法 UTF-8 时用它，
     *                         见 {@link #decode(byte[], Charset)}。不是"就用它解"
     * @param cancellation     取消信号；{@link CancellationToken#none()} 表示不取消
     * @throws ProcessException 启动失败 / 等待时被中断。**超时和取消不在这里** ——
     *                         它们是有结果的返回值，见 {@link CommandTermination}
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

        // 提前把最常见的一种启动失败查出来。不查的话，ProcessBuilder 只会抛一句
        // "Cannot run program ... CreateProcess error=267"，很难定位到是工作目录压根不存在。
        if (workingDirectory != null && !Files.isDirectory(workingDirectory)) {
            throw new ProcessException(command, ProcessException.Reason.START_FAILED,
                    "工作目录不存在或不是目录: " + workingDirectory);
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        // 顺序就是契约，见类注释：先清掉 ProcessBuilder 默认继承的那一份，再放"清过的父环境"，
        // 最后才合并调用方显式给的
        builder.environment().clear();
        builder.environment().putAll(childEnvironment());
        builder.environment().putAll(extraEnvironment);

        long startedAt = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ProcessException(command, ProcessException.Reason.START_FAILED,
                    "无法启动进程: " + String.join(" ", command), e);
        }

        // **立刻关掉子进程的标准输入。** 不关的话那条管道会一直开着，谁去读它谁就挂在那儿
        // 等一个永远不来的字节 —— 一条 `cat` 能把线程挂到超时为止，而用户看到的是
        // "命令卡住了"，查不出原因。关掉写端等于递过去一个 EOF："没有输入，往下走"。
        // （Claude Code 和 deepseek-harness 都默认这么做：它们的子进程都是 stdin: 'ignore'。）
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // 关不上不该让这条命令失败：进程已经起来了，输出照样收得到
        }

        // 虚拟线程在这里是白送的：读两个流本来就是阻塞 IO，不需要真线程
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
     * 清洗规则本身，**纯函数**。
     *
     * <p>抽出来是为了能拿一份**构造好的**环境去断言它 —— 本进程的环境里有没有可清的东西
     * 取决于这台机器，靠它验不出规则对不对。
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
     * 分段等待，以便在等待期间兼顾两件事：超时，和**用户的取消信号**。
     *
     * <p>不能直接 {@code waitFor(timeout)} 一觉睡到底 —— 那样用户按 Esc 也没人理，
     * 只能等命令自己跑完。所以按 {@value #POLL_MILLIS} 毫秒一段地等，
     * 每段醒来先看取消信号。取消的响应粒度就是这 100 毫秒，
     * 对"按下按钮立刻停下"来说完全够用。
     *
     * <h2>它返回"怎么结束的"，而不是"抛不抛异常"</h2>
     * 被杀掉的那两种**是结果，不是失败**：进程确实结束了（而且往往已经打过不少输出），
     * 只是不是它自己结束的。抛异常会把那些输出一起扔掉 —— 而它们正是要看的东西。
     *
     * @return 进程是怎么结束的。只有"等的时候线程被中断"才抛，因为那时整个 JVM 正在往下走
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
                throw new ProcessException(command, ProcessException.Reason.INTERRUPTED,
                        "等待进程时线程被中断，已终止进程树");
            }

            if (finished) {
                return CommandTermination.COMPLETED;
            }
            if (cancellation.isCancelled()) {
                // 注意这里是【先杀树再返回】：光发信号不管用，
                // 必须等进程真的死掉，否则会留下僵尸进程和半成品文件
                killTree(process);
                return CommandTermination.CANCELLED;
            }
        }
    }

    /**
     * 读退出码，**杀掉的那条路要等它真的死掉**。
     *
     * <p>{@link #killTree} 是异步的（{@code destroyForcibly} 只是发信号），
     * 立刻问 {@code exitValue()} 会撞上 {@code IllegalThreadStateException}。
     * 正常路径上其实不用等 —— 两个读流的线程已经读到 EOF 了 —— 但那是巧合，
     * 不是保证，所以这里显式地等一小会儿。
     *
     * <p>等不到就报 1：那只是个"不是 0"的占位。**这条命令到底算不算成功，
     * 判据在 {@link ProcessOutcome#termination()} 里，不在这个数字里。**
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
     * 强杀整棵进程树。
     *
     * <p>用 {@link ProcessHandle#descendants()} 而不是 {@code taskkill /T}：
     * 前者跨平台、不用再起一个进程，语义上也是同一件事 —— 先杀子孙再杀自己，
     * 反过来会出现"父进程死了、子进程被系统重新挂到 init 下"从而逃出遍历范围的窗口。
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
            throw new ProcessException(List.of(), ProcessException.Reason.INTERRUPTED, "读取进程输出时被中断");
        } catch (ExecutionException e) {
            throw new UncheckedIOException(new IOException("读取进程输出失败", e.getCause()));
        }
    }

    /**
     * 把流读到结尾或达到上限。
     *
     * <p><strong>先收字节、读完再解码</strong>，而不是一边读一边按固定字符集解。
     * 理由是解码字符集事先不知道：同一台机器上，{@code node} 吐 UTF-8 而裸 JVM 吐 GBK，
     * 只有拿到（一段）字节之后才判断得了。判断规则见 {@link #decode(byte[], Charset)}。
     *
     * <p>注意进程被杀时流会异常关闭，那是**预期路径**，不当错误处理。
     */
    private static Captured capture(InputStream in, int limit, Charset fallback) {
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        int byteCap = byteCapFor(limit);
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        try {
            int read;
            // 一直读到结尾：不读完的话子进程会阻塞在写满的管道上，而父进程正等着它退出
            while ((read = in.read(buffer)) != -1) {
                int room = byteCap - kept.size();
                if (room > 0) {
                    kept.write(buffer, 0, Math.min(read, room));
                }
                if (read > room) {
                    truncated = true;
                }
            }
        } catch (IOException ignored) {
            // 进程被杀时流被强制关闭，这是预期路径
        }

        String text = decode(kept.toByteArray(), fallback);
        if (text.length() > limit) {
            return new Captured(text.substring(0, limit), true);
        }
        return new Captured(text, truncated);
    }

    /**
     * 字节上限 = 字符上限 × 单字符最多占的字节数。
     *
     * <p>留满余量（UTF-8 一个字符最多 4 字节），免得"字节先到顶"把本来留得下的字符切掉。
     * 上限仍然是有界的：一次构建刷出几百兆也不会把内存撑爆。
     */
    private static int byteCapFor(int charLimit) {
        return charLimit > Integer.MAX_VALUE / MAX_BYTES_PER_CHAR
                ? Integer.MAX_VALUE
                : charLimit * MAX_BYTES_PER_CHAR;
    }

    /** UTF-8 里一个字符最多占的字节数。 */
    private static final int MAX_BYTES_PER_CHAR = 4;

    /**
     * 子进程默认使用的编码 —— 也就是 {@code native.encoding} 那个系统属性。
     *
     * <p>为什么不用 {@code Charset.defaultCharset()}：从 JDK 18 起它恒为 UTF-8（JEP 400），
     * 和"本机子进程实际吐什么"已经没关系了。中文 Windows 上子进程（裸 JVM、python）
     * 跟随控制台代码页，这里是 <strong>GBK</strong>，而 {@code defaultCharset()} 会说 UTF-8 ——
     * 用它当兜底就等于没兜。
     */
    public static Charset nativeCharset() {
        String name = System.getProperty("native.encoding");
        if (name != null && !name.isBlank() && Charset.isSupported(name)) {
            return Charset.forName(name);
        }
        return Charset.defaultCharset();
    }

    /**
     * 字节 → 文本：<strong>先按 UTF-8 解，解不出来再按兜底字符集解</strong>。
     *
     * <h2>为什么不能固定一个字符集</h2>
     * 同一台机器上不同工具吐的编码不一样，实测（Windows 11 + JDK 21）：
     *
     * <pre>
     *   node                                  → UTF-8
     *   mvn（含它 fork 出的测试 JVM）           → UTF-8
     *   ProcessBuilder 直接拉起的裸 JVM        → GBK（跟随 native.encoding）
     * </pre>
     *
     * 固定 UTF-8 会让后一类乱码（得到一串 U+FFFD），固定 GBK 又会让前两类乱码。
     * 而"是不是合法 UTF-8"是个**能算出来**的判据，比猜工具名可靠。
     *
     * <h2>已知的局限（说清楚，别以为它万无一失）</h2>
     * 如果一段 GBK 字节**恰好**整体构成合法 UTF-8，这里会判成 UTF-8 而解错。
     * 对一整句中文来说这几乎不可能，但很短的输出理论上撞得上。
     * 真要根治只能改子进程那边（注入 {@code JAVA_TOOL_OPTIONS} 之类强制它说 UTF-8），
     * 但那会给每个 JVM 的 stderr 塞一行 "Picked up JAVA_TOOL_OPTIONS" 噪声，
     * 而且只覆盖得了我们认得出来的那几种工具 —— 不如这条规则通用。
     */
    static String decode(byte[] bytes, Charset fallback) {
        String utf8 = decodeOrNull(bytes, StandardCharsets.UTF_8);
        return utf8 != null ? utf8 : new String(bytes, fallback);
    }

    /** 严格解码；一旦遇到非法字节序列就返回 null（而不是塞 U+FFFD 蒙混过去）。 */
    private static String decodeOrNull(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer decoded = CharBuffer.allocate(Math.max(1, bytes.length));
        try {
            // endOfInput=false：结尾那个**不完整**的多字节序列不算错误，只是留在缓冲区里
            // 没人消费。这正是要的 —— 输出被字节上限切断时最后一个字常常只剩半截，
            // 那不该让它整段退回去按兜底字符集解
            CoderResult result = decoder.decode(ByteBuffer.wrap(bytes), decoded, false);
            if (result.isError()) {
                result.throwException();
            }
            return decoded.flip().toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private record Captured(String text, boolean truncated) {
    }
}
