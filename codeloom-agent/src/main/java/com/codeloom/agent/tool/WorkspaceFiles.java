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
 * 工作区遍历的共享实现，供 {@link GrepTool} 与 {@link GlobTool} 使用。
 *
 * <p>在目录层直接 {@code SKIP_SUBTREE}，不把噪声目录完整列出再过滤；两个工具共用
 * 同一份跳过清单，避免它们看到的目录树不一致。
 */
final class WorkspaceFiles {

    /** 不参与遍历的目录名。 */
    static final Set<String> SKIP_DIRS =
            Set.of(".git", "target", "build", "out", "node_modules", ".idea", ".gradle", "dist", ".venv");

    /** 单个文件大小上限；超过则跳过。 */
    static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private WorkspaceFiles() {
    }

    /**
     * 遍历 {@code root}，对每个普通文件调用 {@code onFile}。自动跳过 {@link #SKIP_DIRS}
     * 中的目录和超大文件。
     *
     * @param root   遍历根目录
     * @param onFile 返回 false 时立即停止遍历
     * @throws IOException 遍历失败
     */
    static void walk(Path root, Predicate<Path> onFile) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // 在目录层剪枝；排除 root 本身，用户可能指定在 target/ 内搜索。
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
                // 单个文件不可读不应让整次搜索失败。
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
