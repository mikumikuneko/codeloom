package com.codeloom.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一个可以被模型调用的工具。
 *
 * <h2>实现工具时最要紧的一条：路径必须过 {@link WorkspacePathGuard}</h2>
 * 工具拿到的参数**全部来自模型输出**，也就是不可信输入。任何直接
 * {@code worktree.resolve(模型给的路径)} 的写法都是目录穿越漏洞 ——
 * 模型可以传 {@code ../../} 去读写工作区之外的文件。
 *
 * <p>{@link ReadFileTool} / {@link GrepTool} / {@link GlobTool} 是本项目的搜索能力。
 * 我们**不做 RAG**，因为代码是精确匹配的、不是语义相似的 ——
 * grep 找 {@code OrderService} 比 embedding 准得多，而且不用维护索引。
 */
public interface Tool {

    /** 工具名。受 provider 的字符集约束（见 {@code ToolDefinition}）。 */
    String name();

    /** 给模型看的说明。写得好不好直接决定模型会不会正确地用它。 */
    String description();

    /** 参数的 JSON Schema 文本。 */
    String parametersJsonSchema();

    /**
     * 执行。
     *
     * <p>实现**不应该抛异常**：工具失败要作为结果返回给模型，让它自己决定怎么办。
     * 抛异常会把一次可自修的小错变成整轮失败。
     */
    ToolOutcome execute(ToolContext context, JsonNode arguments);

    /**
     * 这一次调用能不能和**同一批**里的其它调用并发跑。
     *
     * <p>默认 {@code false} —— fail-closed：新工具不声明就自动串行，那是安全的方向。
     *
     * <h2>声明它 = 承诺两件事</h2>
     * <ol>
     *   <li><b>不许改"父级持有"的状态</b> —— 会话级、轮次级的那些东西
     *       （任务清单、工作区、审批状态、这一轮累积的账）。要改就先别声明并发安全：
     *       {@code write_file} / {@code edit_file} / {@code run_command} / {@code todo_write}
     *       都是这样。</li>
     *   <li><b>共享状态必须能容忍并发</b>。注意这里说的**不是"只读"**，而是
     *       "就算写，竞争也要么**可交换**、要么 **fail-closed**"。
     *       {@code read_file} 就是照这条来的：它看着只读，实际上会往
     *       {@link ReadLedger} 记一笔"读过这个文件" —— 而那是并发集合上的一个 add，
     *       两次 add 谁先谁后结果一样（可交换），所以合法。</li>
     * </ol>
     *
     * <h2>声明它**不等于"只读"**</h2>
     * 判据不是"这个工具是不是只读"：read / grep / glob 都声明了 true，而 {@code read_file}
     * 确实在写东西（往 {@link ReadLedger} 记一笔）。只要满足上面两条 —— 不改父级持有状态、
     * 共享写可交换或 fail-closed —— 就可以声明。
     *
     * <p>为什么这里**不带参数**（对比"看这次调用的参数再决定"）：我们的工具里
     * 只读的那几个恒定只读，写的那几个恒定写。唯一"看参数才知道"的是
     * {@code run_command}（同一条命令可能是 {@code mvn test} 也可能是别的），
     * 而它保守地算不安全就够了 —— 为它引入一个按参数判定的协议，
     * 换来的是每个调用点都要重新回答一遍"这次安不安全"。
     * （Claude Code 那个版本带参数，所以它还得规定"参数解析失败或它抛异常时一律算不安全"；
     * 我们不带参数，那条就无从发生。）
     */
    default boolean concurrencySafe() {
        return false;
    }

    /**
     * 这个工具**这类调用**长什么样：用哪张卡片渲染、动作词是什么、
     * 参数里哪一项是它的主语。见 {@link ToolSurface}。
     *
     * <p>默认是 {@link ToolSurface#PLAIN} —— 和 {@link #concurrencySafe()} 一样是
     * **fail-closed 的方向**：不声明就退化成"显示工具名和原始参数"，人还看得懂，
     * 只是不体面。声明错了不会让界面崩，只会让它糙。
     *
     * <p>它必须与 {@link #parametersJsonSchema()} 对得上：{@code subjectKey} 写的那个键
     * 得真的在那个 schema 里。这两处对不上没有编译期检查（一个是文本、一个是代码），
     * 所以 {@code ToolRegistry} 在构造时**逐个对一遍** —— 那是唯一能在启动时发现它的地方。
     */
    default ToolSurface surface() {
        return ToolSurface.PLAIN;
    }

    /**
     * 这个工具的产出**自己就有界**，而且模型能靠它自己的参数拿到剩下那部分。
     *
     * <p>默认 {@code false} —— 和 {@link #concurrencySafe()} 一样是 fail-closed 的方向：
     * 不声明就按"它可能吐出任意大的东西"处理，于是它的产出会走那一轮的总量限制
     * （超了落盘、只回灌头尾，并告诉模型去哪儿读回来）。
     *
     * <h2>声明它 = 断言两件事</h2>
     * <ol>
     *   <li>这个工具的产出**有真正的上界**（不是"一般不会太大"）；</li>
     *   <li>被截住的那部分，模型**能用这个工具自己的参数**再取一次
     *       （{@code read_file} 的 {@code offset} / {@code limit}）。</li>
     * </ol>
     * 两条缺一不可。只有第一条成立的话，模型看着半截输出却无路可走 ——
     * 那还不如让总量限制把它落盘、给它一条路径。
     *
     * <h2>为什么要有这么一个声明</h2>
     * 因为总量限制对**读文件**那类工具会变成一句**循环指令**：它把结果落盘，
     * 提示是"可以用 read_file 读回来" —— 而被落盘的正是那条 read 的结果，
     * 读回来照样超、照样落盘、照样叫它读。两个参考实现在同一处都做了同一件事，
     * 而且理由写在它们的注释里：**把读文件的输出存成文件、再让模型用同一个工具读回来，
     * 那是循环的**。Claude Code 干脆不给读文件这一类设落盘阈值，deepseek-harness
     * 则是在那条"超限就落盘"的策略里直接跳过读文件这一类。
     *
     * <p>两边都按**工具名**排除，我们按**声明** —— {@link #surface()} 那一条讲过为什么。
     *
     * <h2>放行之后，它占掉的上下文谁来管</h2>
     * 放行意味着：一轮里读十份大文件，每份最多 {@code 100_000} 字符，各自整份进上下文。
     * 那不是漏掉的一环，是**分工** —— 累积的那一份归**压缩**管：
     * {@code ContextCompactor} 会把旧的可清工具结果正文清掉，而 {@code read_file}
     * 就在那份可清名单里。
     *
     * <p>Claude Code 是同一个分工，而且把理由写在了注释里：**读文件那类工具的界，
     * 由它自己的输出上限给，不由"每轮的总量"这层包装给。** 所以声明这一条的前提
     * 还是那一句：**它自己的界必须是真的**。
     */
    default boolean selfBounded() {
        return false;
    }
}
