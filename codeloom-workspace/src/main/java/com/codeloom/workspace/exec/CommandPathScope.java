package com.codeloom.workspace.exec;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 一条命令的参数，会不会动到**工作区外面**的东西。
 *
 * <h2>它补的是免审批名单缺的那一半</h2>
 * 名单是按**可执行文件名**比对的，看不出参数 ——
 * {@code rm -rf node_modules} 和 {@code rm -rf C:\Users\…\Documents}
 * 在它眼里一模一样。
 *
 * <p>而"**项目树内的操作是自由的**"这句话，落到 {@code rm} / {@code mv} 上时
 * 说的其实是**路径** —— 同一个 {@code rm}，动工作区里的文件天经地义，
 * 动外面的就不是"项目树内"了。所以免审批的条件是两半：
 * <b>程序在名单里</b>，**并且**它碰的东西在工作区里。
 *
 * <h2>输入是已经切好的词，而切词那一步在 {@link CommandLine}</h2>
 * 这里收到的 {@code words} 是 {@code CommandLine.simpleWords} 切出来的结果 ——
 * 也就是说：**切不开的命令根本走不到这里**，调用方已经直接按"要问"处理了。
 * 于是这个类只面对"一个词就是一个词、没有展开"的简单命令，
 * {@code $HOME}、{@code ~}、命令替换这些让路径变得看不见的东西，它不用操心。
 * （{@code ~} 仍然在这里兜一道：它是"会跑到树外"的一条，哪怕上游漏了也该拦。）
 *
 * <h2>只管"会改文件系统"的那几个命令</h2>
 * 读命令（{@code ls} / {@code cat} / {@code grep}）不在其列：看一眼外面的文件
 * 不改任何东西，而把它们也框住只会让人为"看一眼"点一次批准 ——
 * 那正是这套东西最烦人的失败方式。
 *
 * <h2>认不出来的一律当作"在工作区内"（不问）</h2>
 * 这条是**刻意**的，不是漏了。误判成"要问"的代价是让人为一个正常的项目操作
 * 点一下批准；误判成"不问"的代价是少问一次。两边的代价不对称，所以这里
 * 只在**明确**跑到外面时才说不：绝对路径落在树外、或者相对路径规范化之后落在树外
 *（{@code ..}、{@code ../..}）。
 *
 * <p>看得出不来的：软链接指向外面、{@code node -e "…rmSync('/')"} 这种把删除
 * 藏在程序内部的写法、以及通配符展开出来的 {@code ..}（见 {@link CommandLine} 的
 * 类注释）。这些拦不住，也不该假装能拦住 ——
 * 名单本身从来不是安全边界，它只是"哪些不用问"。
 */
public final class CommandPathScope {

    /**
     * 会改动文件系统的命令。**只有它们的参数按路径看。**
     *
     * <p>这几个是刻意选的：它们的参数就是路径，而且它们会**删掉或者搬走**东西。
     * {@code mvn} 也能删东西（{@code clean}），但它的参数是目标名不是路径，
     * 按路径去看它只会得到一堆噪声。
     */
    private static final Set<String> MUTATING =
            Set.of("rm", "rmdir", "mv", "cp", "mkdir", "touch");

    private CommandPathScope() {
    }

    /**
     * @param worktree 工作区根目录（绝对路径）
     * @param words    已经切好的命令（第一个词是可执行文件），见 {@link CommandLine}
     * @return true 表示这次调用**碰不到工作区外面**，可以免审批
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
            // 选项不是路径。`--` 分隔符、`-rf`、`--recursive` 都走这一条
            return false;
        }
        if (argument.startsWith("~")) {
            // shell 会把它展开成用户目录 —— 那是树外，而且离得很远。
            // 上游（CommandLine）已经拦过词首的 ~，这里再兜一道
            return true;
        }
        try {
            Path resolved = root.resolve(argument).toAbsolutePath().normalize();
            // startsWith 是按路径**段**比的，所以 /a/b 算在 /a 里，/ab 不算 ——
            // 字符串前缀比对会在这里出错
            return !resolved.startsWith(root);
        } catch (InvalidPathException e) {
            // 参数里有路径不允许的字符（Windows 上 `*`、`?`、`|` 之类）。
            // 它不可能是"一条通往树外的路径"，交给命令自己去报错
            return false;
        }
    }
}
