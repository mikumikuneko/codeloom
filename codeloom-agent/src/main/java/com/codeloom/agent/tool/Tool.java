package com.codeloom.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一个可被模型调用的工具。
 *
 * <p>工具参数全部来自模型输出；文件类工具解析参数里的路径时必须经过
 * {@link WorkspacePathGuard}，直接解析会形成目录穿越漏洞。run_command 把整行命令交给
 * 执行器，子进程可访问工作区之外的文件和网络，不受路径守卫约束。搜索使用 grep/glob
 * 的精确匹配，不做 RAG。
 */
public interface Tool {

    /**
     * @return 工具名；受 provider 字符集约束（见 {@link com.codeloom.agent.llm.ToolDefinition}）
     */
    String name();

    /**
     * @return 给模型看的工具说明
     */
    String description();

    /**
     * @return 参数的 JSON Schema 文本
     */
    String parametersJsonSchema();

    /**
     * 执行一次调用。
     *
     * <p>失败要作为 {@link ToolOutcome} 返回给模型自修：未捕获的 {@code RuntimeException}
     * 虽会被循环兜底成失败结果，但除取消外会被归为 {@code INTERNAL}，丢掉准确的失败性质。
     *
     * @param context   执行上下文
     * @param arguments 已解析的参数
     * @return 执行结果
     */
    ToolOutcome execute(ToolContext context, JsonNode arguments);

    /**
     * 这次调用能否与同一批中的其他调用并发执行。默认 false（fail-closed，新工具自动串行）。
     *
     * <p>声明为 true 承诺两件事：不修改父级持有的状态；共享状态的并发写要么可交换、要么 fail-closed。
     * “并发安全”不等于“只读”：{@code read_file} 会写 {@link ReadLedger}，两次 add 可交换。
     * 声明不按参数决定；{@code run_command} 一律视为不安全。
     *
     * @return 可并发时为 true
     */
    default boolean concurrencySafe() {
        return false;
    }

    /**
     * 这个工具这类调用的呈现声明：形状、动作词和主语参数。默认 {@link ToolSurface#PLAIN}，
     * 不声明时界面退化为显示工具名和原始参数。
     *
     * <p>{@code subjectKey} 必须存在于 {@link #parametersJsonSchema()} 中；
     * {@code ToolRegistry} 在构造时核对。
     *
     * @return 呈现声明
     */
    default ToolSurface surface() {
        return ToolSurface.PLAIN;
    }

    /**
     * 工具产出是否自带真实上界，且模型能用该工具自己的参数取回被截住的部分。
     * 默认 false，产出会走每轮总量限制（落盘并回灌头尾）。
     *
     * <p>声明为 true 必须同时满足两条，否则模型拿到半截输出却无路可走。放行后占用仍计入总量，
     * 累积部分由压缩清理。见
     * {@code docs/decisions/architecture/2026-10-04-tool-output-spills-to-the-workspace.md}。
     *
     * @return 自带分段入口且产出有界时为 true
     */
    default boolean selfBounded() {
        return false;
    }
}
