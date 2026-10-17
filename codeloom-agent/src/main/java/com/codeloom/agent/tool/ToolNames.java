package com.codeloom.agent.tool;

/**
 * 工具名与它们约定俗成的参数键。
 *
 * <h2>为什么它存在</h2>
 * 工具类当然知道自己的名字，但**别处也会引用它**：压缩的可清名单、读文件记账、
 * 提示词里告诉模型"用哪个工具"。那些地方从前各写一份字符串 ——
 * 工具改名时编译器不会报错，症状只是"压缩不清理了""读过记账失效了"，
 * 而它们看起来都不像 bug。
 *
 * <p>这里是**唯一一份**：工具类自己也返回这里的常量。
 */
public final class ToolNames {

    public static final String READ_FILE = "read_file";
    public static final String WRITE_FILE = "write_file";
    public static final String EDIT_FILE = "edit_file";
    public static final String GREP = "grep";
    public static final String GLOB = "glob";
    public static final String RUN_COMMAND = "run_command";
    public static final String TODO_WRITE = "todo_write";
    public static final String WEB_SEARCH = "web_search";
    public static final String WEB_FETCH = "web_fetch";

    /**
     * 文件类工具用来指定"哪一个文件"的参数键。
     *
     * <p>它不是所有工具通用的：`grep` 的主语是 pattern、`run_command` 的是 command。
     * 放在这里是因为读文件记账要知道"这个工具是不是按路径读的"（见 {@code ReadPathTracker}）。
     */
    public static final String PATH_ARG = "path";

    private ToolNames() {
    }
}
