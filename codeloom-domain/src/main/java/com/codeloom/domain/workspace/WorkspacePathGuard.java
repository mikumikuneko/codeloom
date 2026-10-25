package com.codeloom.domain.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 路径守卫：把调用方给的路径解析到工作区内部，越界一律拒绝。
 *
 * <p>调用方包括模型工具参数、文件接口和冲突落盘：这些路径都来自外部内容，没有这道
 * 守卫，读写就会变成任意文件访问。工具、应用层和工作区实现共用这一份，别各写一遍。
 *
 * <p>拒绝绝对路径；先 {@code normalize()} 再做前缀检查（顺序反了可被 {@code ..} 绕过）；
 * 路径已存在时再用 {@code toRealPath()} 复核，防止符号链接指向外部。
 *
 * <p>符号链接复核在本项目的开发环境中没有测试：Windows 建符号链接需要开发者模式或管理员权限。
 * 它是推理出的纵深防御，不是已验证的事实。
 */
public final class WorkspacePathGuard {

    private WorkspacePathGuard() {
    }

    /**
     * @param worktree 工作区根目录
     * @param rawPath  模型给的路径，应为工作区内相对路径
     * @return 解析后的绝对路径，保证落在工作区内
     * @throws PathEscapeException 路径为空、非法、绝对或越界
     * @throws java.io.UncheckedIOException 无法解析已有路径的真实路径
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

        // 必须先 normalize 再比较，否则 src/../../etc 能绕过前缀检查。
        if (!resolved.startsWith(root)) {
            throw new PathEscapeException(
                    "路径越出了工作区: " + rawPath + "（解析后为 " + resolved + "）");
        }

        // 已存在路径再复核真实路径，防止工作区内的符号链接指向外部。
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

    /**
     * 把路径转成相对工作区的展示形式；绝对路径会泄露环境信息。
     *
     * @param worktree 工作区根目录
     * @param path     绝对路径
     * @return 相对路径，使用正斜杠；不在工作区内时返回规范化绝对路径
     */
    public static String relativize(Path worktree, Path path) {
        Path root = worktree.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root)
                ? root.relativize(normalized).toString().replace('\\', '/')
                : normalized.toString();
    }

    /** 路径越界；应作为工具失败回灌给模型。 */
    public static class PathEscapeException extends RuntimeException {
        public PathEscapeException(String message) {
            super(message);
        }
    }
}
