package com.codeloom.workspace.exec;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 判断一条命令的参数是否会改动工作区之外的路径。
 *
 * <p>白名单按可执行文件名比对，看不出参数；{@code rm project/old} 与
 * {@code rm C:/Users/x/Documents} 在它眼里一样。免审批因此需要两半：程序在名单里，
 * 并且它碰的东西在工作区里。
 *
 * <p>输入是 {@link CommandLine} 已经切好的词，所以不会遇到变量展开、命令替换等让路径
 * 不可见的情况；切不开的命令根本走不到这里。只检查会改动文件系统的命令；读命令和
 * 构建工具不看参数。
 *
 * <p>只有明确落在树外（绝对路径在树外，或相对路径规范化后离开树）才返回 false；
 * 认不出来的按"在树内"处理，因为误判为"要问"比少问一次更烦人。这不是安全边界：
 * 软链接、程序内部的删除、通配符展开出的 {@code ..} 都拦不住。
 */
public final class CommandPathScope {

    /**
     * 会改动文件系统的命令，只有它们的参数按路径检查。
     *
     * <p>它们的参数就是路径。{@code mvn clean} 也会删东西，但参数是目标名而不是路径，
     * 按路径检查只会得到噪声。
     */
    private static final Set<String> MUTATING =
            Set.of("rm", "rmdir", "mv", "cp", "mkdir", "touch");

    private CommandPathScope() {
    }

    /**
     * @param worktree 工作区根目录（绝对路径）
     * @param words 已经切好的命令，第一个词是可执行文件，见 {@link CommandLine}
     * @return true 表示这次调用不会改动工作区外的路径，可以免审批
     */
    public static boolean staysInside(Path worktree, List<String> words) {
        if (words.isEmpty() || !MUTATING.contains(CommandPolicy.bareExecutableName(words.getFirst()))) {
            return true;
        }
        Path root = worktree.toAbsolutePath().normalize();
        for (String argument : words.subList(1, words.size())) {
            if (escapes(root, argument)) {
                return false;
            }
        }
        return true;
    }

    private static boolean escapes(Path root, String argument) {
        if (argument.isEmpty() || argument.startsWith("-")) {
            // 选项不是路径：{@code --} 分隔符、{@code -rf}、{@code --recursive} 都走这一条
            return false;
        }
        if (argument.startsWith("~")) {
            // shell 会把它展开成用户目录，落在树外；上游 CommandLine 已拦过词首的 ~
            return true;
        }
        try {
            Path resolved = root.resolve(argument).toAbsolutePath().normalize();
            // startsWith 按路径段比较：/a/b 属于 /a，/ab 不属于 —— 字符串前缀比较会出错
            return !resolved.startsWith(root);
        } catch (InvalidPathException e) {
            // 参数含路径不允许的字符（Windows 上的 *、?、| 等），交给命令自己报错
            return false;
        }
    }
}
