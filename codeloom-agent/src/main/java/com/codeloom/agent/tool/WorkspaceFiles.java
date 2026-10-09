package com.codeloom.agent.tool;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 工作区遍历的共享实现。{@link GrepTool} 与 {@link GlobTool} 都用它。
 *
 * <h2>为什么必须剪枝，而不是"列出来再过滤"</h2>
 * 用 {@code Files.walk(...).filter(isSkipped)} 会把 {@code node_modules/}、{@code target/}
 * 里每一个文件**完整列进来**再丢弃 99%。一个带 node_modules 的仓库动辄 10 万+ 文件，
 * 而 agent 一轮里可能调好几次搜索：单次从几十毫秒涨到几秒，内存也白白几百 MB。
 *
 * <p>这里改成 {@code walkFileTree} + {@code SKIP_SUBTREE}：噪声目录**根本不下去**。
 *
 * <h2>为什么这份必须只有一份</h2>
 * 两个工具各写一份 {@code SKIP_DIRS} 时，加一个忽略目录要改两处；漏改一处就会出现
 * "grep 跳过了、glob 还列出来"——模型在两个工具里看到的目录树不一致，而它无从知道。
 */
final class WorkspaceFiles {

    /** 明显不是源码、扫了只会淹没结果的地方。 */
    static final Set<String> SKIP_DIRS =
            Set.of(".git", "target", "build", "out", "node_modules", ".idea", ".gradle", "dist", ".venv");

    /** 单个文件的大小上限，超过就跳过（二进制、锁文件）。 */
    static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private WorkspaceFiles() {
    }

    /**
     * 遍历 {@code root}，对每个普通文件调用 {@code onFile}。
     *
     * <p>自动跳过 {@link #SKIP_DIRS} 里的目录（不进子树）和超大文件。
     *
     * @param onFile 返回 {@code false} 表示够了，**立即停止遍历** ——
     *               这让"只取前 N 条"的调用方不必先物化整棵树
     */
    static void walk(Path root, Predicate<Path> onFile) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // 在**目录**这一层就剪掉，而不是等走进去了再逐文件判断。
                // 注意排除 root 自己：用户可能就是想在 target/ 里搜。
                if (!dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || attrs.size() > MAX_FILE_BYTES) {
                    return FileVisitResult.CONTINUE;
                }
                return onFile.test(file) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // 单个文件读不了（权限、被占用）不该让整次搜索失败
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
