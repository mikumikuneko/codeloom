package com.codeloom.agent.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 路径守卫：把模型给的路径解析到工作区**内部**，越界一律拒绝。
 *
 * <h2>为什么这是工具集里最要紧的一个类</h2>
 * 工具参数全部来自模型输出。没有这道守卫，模型只要传一个
 * {@code ../../../../Users/x/.ssh/id_rsa}，{@code read_file} 就变成了任意文件读取；
 * {@code edit_file} 就变成了任意文件写入。
 *
 * <p>这不是"模型不会那么坏"的问题 —— 模型可能只是**被工作区里的内容带偏**
 * （比如它读到了一个写着 {@code ../../} 的配置文件），或者是用户在提示词里
 * 无意地引导了它。工具侧的边界不能靠对模型行为的期待来维持。
 *
 * <h2>做法</h2>
 * <ol>
 *   <li>拒绝绝对路径 —— 绝对路径没有"相对工作区"的语义，一律不接受</li>
 *   <li>{@code normalize()} 消掉 {@code ..} 和 {@code .}，再做前缀检查。
 *       顺序不能反：先检查再 normalize 的话，{@code src/../../etc} 能骗过检查</li>
 *   <li>文件已存在时，再用 {@code toRealPath()} 复核一遍，堵住符号链接绕过</li>
 * </ol>
 *
 * <h2>第 3 条<b>没有测试</b>，这件事得写下来</h2>
 * 上面三条里，前两条有测试钉着，第三条**一条都没有** —— 建符号链接在 Windows 上要开
 * 开发者模式（或者管理员），而本项目的开发环境刻意不开。所以：
 *
 * <p><b>"它能堵住符号链接绕过"这句话在这个项目里是推理出来的，不是实测出来的。</b>
 * 代码留着（它是纵深防御，成本是每个已存在的路径多一次 {@code toRealPath}），
 * 但**别把这句话当成已验证的事实** —— 换一台 Linux 机器、或者哪天有人开了开发者模式，
 * 第一件事应该是给它补一条测试把它钉住。
 *
 * <p>（这条注释本身就是"技术断言要实测"的一个落点：写代码的时候它读起来天经地义，
 * 而"读起来天经地义"和"测过"是两件事。）
 */
public final class WorkspacePathGuard {

    private WorkspacePathGuard() {
    }

    /**
     * @param rawPath 模型给的路径，应当是工作区内的相对路径
     * @return 解析后的绝对路径，保证落在工作区内部
     * @throws PathEscapeException 越界、绝对路径、或路径非法
     */
    public static Path resolve(Path worktree, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new PathEscapeException("路径不能为空");
        }

        Path candidate;
        try {
            candidate = Path.of(rawPath);
        } catch (InvalidPathException e) {
            throw new PathEscapeException("路径非法: " + rawPath);
        }

        if (candidate.isAbsolute()) {
            throw new PathEscapeException(
                    "不接受绝对路径（工作区内的路径要写成相对路径）: " + rawPath);
        }

        Path root = worktree.toAbsolutePath().normalize();
        Path resolved = root.resolve(candidate).normalize();

        // 先 normalize 再比较 —— 否则 src/../../etc 会骗过前缀检查
        if (!resolved.startsWith(root)) {
            throw new PathEscapeException(
                    "路径越出了工作区: " + rawPath + "（解析后为 " + resolved + "）");
        }

        // 已存在的路径再复核一次真实路径，堵住"工作区内有个符号链接指向外面"这种绕过
        if (Files.exists(resolved)) {
            try {
                Path real = resolved.toRealPath();
                Path realRoot = root.toRealPath();
                if (!real.startsWith(realRoot)) {
                    throw new PathEscapeException(
                            "路径经符号链接指向了工作区之外: " + rawPath);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("无法解析真实路径: " + resolved, e);
            }
        }

        return resolved;
    }

    /** 把路径转成相对工作区的展示形式，放在工具输出里（模型看到绝对路径没意义，还泄漏环境信息）。 */
    public static String relativize(Path worktree, Path path) {
        Path root = worktree.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root)
                ? root.relativize(normalized).toString().replace('\\', '/')
                : normalized.toString();
    }

    /** 路径越界。属于"模型做了一次它不该做的尝试"，应当作为工具失败回灌给它。 */
    public static class PathEscapeException extends RuntimeException {
        public PathEscapeException(String message) {
            super(message);
        }
    }
}
