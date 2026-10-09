package com.codeloom.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **这一轮对话里读过哪些文件，读到的是什么版本。**
 *
 * <h2>它为什么存在：把"不许覆盖"换成"读过才许覆盖"</h2>
 * {@link WriteFileTool} 从前是**只在文件不存在时工作**。那条规矩的理由没错
 *（放开覆盖等于把 {@code edit_file} 的保护全绕过去，模型会图省事全用覆盖），
 * 但它有一个没算到的代价：**空文件谁也写不进去** —— `write_file` 说"已经存在"、
 * `edit_file` 要求 `old_string` 非空且唯一，而空文件里没有东西可匹配。
 *
 * <p>参考实现（Claude Code）没有这个问题，因为它的 {@code Write} 就是"**创建或覆盖**"，
 * 门槛是另一条：**这个文件在当前对话里读过**。读过才敢覆盖 —— 那句话的用意是
 * "别盲改你没看过的东西"，而不是"别改已经存在的东西"。
 *
 * <h2>后来加了版本 —— 因为"读过"这件事会过期</h2>
 * 只有"路径在不在账上"这一条时，有一个洞：**读完之后文件被别人改了**。
 * 我们这里是**共享工作区**（同一个人的多条会话共用一棵树，人也可能自己动手），
 * 所以那不是理论风险 —— 拿一份过期的内容去覆盖，等于**静默吃掉别人的改动**，
 * 而且没有任何地方会报错。
 *
 * <p>两个参考实现在这一处都带版本：Claude Code 在写之前比 mtime，
 * 完整读过的还再比一次内容（注释里说 Windows 上 mtime 会被云同步、杀软动到）；
 * deepseek-harness 更彻底 —— 版本由 provider 在**原子变更的临界区里**做比较交换，
 * 它的原话是"策略自己 stat 再比对，检查和写入之间有个 TOCTOU 窗口，那是个假保证"。
 *
 * <p>我们走前一条：写入是 {@code Files.write}，没有带锁的 provider 可供 CAS。
 * 代价是老实话 —— **检查和写入之间那道缝关不死**（另一个人的 agent 正好在这几微秒里
 * 写同一个文件）。能做的只有两件：把检查贴着写入放（Java 这边本来就是同步的，
 * 不存在 Claude Code 那个"两次检查之间不许有 await"的问题），以及**承认缝在那儿**，
 * 不假装它是原子的。
 *
 * <h2>为什么只看时间和大小，不比对内容</h2>
 * Claude Code 在这里会**再比一次内容**，理由它写在注释里：**在 Windows 上，时间戳会在
 * 内容没变的情况下变**（云同步、杀软），所以它只在"整份读过"时拿内容做兜底比对，免得误报。
 * 那是一条真实的经验，我们**没有照做**，理由是代价不对等：
 *
 * <p>要比内容，就得在标记和校验时各拿一次文件的全部字节。而 {@code read_file}
 * 是**流式读的**（大文件不全量读入，见它的注释）—— 为了一个指纹把整份文件再读一遍，
 * 换来的只是"少一次误报"：误报的后果是模型被要求重读一遍，而我们的提示里
 * 正好写着该怎么做。
 *
 * <p>所以：**先不换**。哪天真在用户那边看到"明明没人改却说被改过"，再按 Claude Code
 * 那条路加内容指纹 —— 那时它就不是猜测，是一个有案底的问题。
 *
 * <h2>重启之后：版本是未知的</h2>
 * 账是从历史投影重建的（{@code AgentTurn.seedReadLedger}），而投影里只有一组路径 ——
 * **没有版本**。那时 {@link Observation#modifiedAt()} 是 null，"被改过没有"这个问题
 * 就答不上来，于是**一律算没改过**。
 *
 * <p>为什么宽而不是严：严格的方向（版本未知就判"改过"）会让**每次重启之后
 * 谁都写不进去**，而用户看到的是一句莫名其妙的"文件被改过了"。宽方向的代价是
 * 重启后第一次覆盖少了一层保护 —— 那一层本来也不是安全边界，是"别盲改"的提醒。
 *
 * <p>范围是"这条会话"，不是"这一次工具调用"：{@code ToolContext} 每次调用都新造一个，
 * 所以账不能挂在它身上（见 {@code AgentTurn}）。
 */
public final class ReadLedger {

    /**
     * 一条"读过"的记录：**读的是哪个版本**，以及**看到的是不是整份**。
     *
     * @param modifiedAt 读的那一刻这个文件的修改时间。**null = 不知道版本**
     *                   （从历史重建的那种，见类注释）
     * @param size       同上，字节数。和 mtime 一起用：光看时间在粗粒度的文件系统上
     *                   会漏（一秒内改两次），光看大小则会漏掉等长的改动
     * @param whole      读的时候拿到的是**整份**吗。{@code read_file} 指定了
     *                   {@code offset} / {@code limit}、或者被截断了，都算不是
     */
    public record Observation(FileTime modifiedAt, long size, boolean whole) {

        /**
         * 从历史重建的那一种：知道"读过"，**不知道版本，也不知道是不是整份**。
         *
         * <p>{@code whole} 取 {@code true} 是宽的方向，理由同类注释：取 {@code false}
         * 会让重启之后的每一次整份覆盖都被拦下来，而那是个正常操作。
         */
        public static Observation reconstructed() {
            return new Observation(null, 0, true);
        }
    }

    /**
     * 存**绝对且规范化**的路径。
     *
     * <p>不归一的话，同一个文件用 {@code A.java}、{@code ./A.java}、
     * {@code sub/../A.java} 三种写法读进来会变成三个不同的键 ——
     * 于是"读过了"这条判据会在最不该失灵的时候失灵。
     *
     * <p>值也是并发安全的：{@link Observation} 是不可变 record，放进去就是安全发布。
     *
     * <p>用**并发集合**还有一个具体的理由：一条全是只读调用的批是并发跑的
     *（见 {@code AgentTurn.runBatch}），几个 {@code read_file} 会同时往这里记。
     * 普通 {@code HashMap} 在那种并发写下来会坏掉，而坏掉的方式是
     * "偶尔说没读过" —— 最难查的那一种。
     */
    private final Map<Path, Observation> read = new ConcurrentHashMap<>();

    /** 读到了，记一笔 —— 版本按**现在**的取。 */
    public void mark(Path file, boolean whole) {
        read.put(key(file), observe(file, whole));
    }

    /**
     * 从历史重建的那一种 —— 知道读过，不知道版本。
     *
     * <p>见类注释"重启之后"那段。
     */
    public void markReconstructed(Path file) {
        read.put(key(file), Observation.reconstructed());
    }

    /** 这个文件在当前对话里读过吗；读过的话，读到的是什么版本。 */
    public Optional<Observation> observationOf(Path file) {
        return Optional.ofNullable(read.get(key(file)));
    }

    /**
     * 从记下那一刻到现在，这个文件被改过吗。
     *
     * <p>**版本未知时返回 false（没改过）** —— 见类注释"重启之后"那段：
     * 那是有意的宽方向。
     *
     * <p>问不出来（文件刚没了、权限不对）也返回 false：真要写不进去，
     * 写入那一步自己会报，不在这里替它下结论。
     */
    public static boolean changedSince(Path file, Observation observed) {
        if (observed.modifiedAt() == null) {
            return false;
        }
        try {
            return !Files.getLastModifiedTime(file).equals(observed.modifiedAt())
                    || Files.size(file) != observed.size();
        } catch (IOException e) {
            return false;
        }
    }

    private static Observation observe(Path file, boolean whole) {
        try {
            return new Observation(Files.getLastModifiedTime(file), Files.size(file), whole);
        } catch (IOException e) {
            // 取不到时间和大小：记成"版本未知"，但**它确实是读过的** ——
            // 记成"没读过"会让模型读一个刚被别的进程碰过的文件然后被拒
            return new Observation(null, 0, whole);
        }
    }

    private static Path key(Path file) {
        return file.toAbsolutePath().normalize();
    }
}
