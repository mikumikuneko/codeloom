package com.codeloom.agent.tool;

/**
 * 工具名与约定参数键的集中定义。压缩、读文件记账和工具实现按名字匹配时引用这里，
 * 避免工具改名后字符串静默失配；给模型看的提示文本仍直接写名字，改名时要一起搜。
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
     * 文件类工具指定文件路径的参数键；不是所有工具通用。
     * 读文件记账据此判断是否按路径读取（见 {@code ReadPathTracker}）。
     */
    public static final String PATH_ARG = "path";

    private ToolNames() {
    }
}
