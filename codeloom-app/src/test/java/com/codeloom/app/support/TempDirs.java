package com.codeloom.app.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 测试用临时目录：创建，以及能删干净的递归删除。
 *
 * <p>删除不能只调用 {@code deleteIfExists}：Windows 上 git 对象文件是只读的
 *（{@code .git/objects/...}），{@code Files.delete} 对只读文件会抛
 * {@code AccessDeniedException}，清理在测试全部通过之后才炸。Linux 只看目录写权限，
 * 不会有这个问题，因此不能按 Linux 的直觉实现。
 *
 * <p>集中一份实现，否则漏掉只读处理没有任何征兆，且只在别人的 Windows 机器上、
 * 测试通过之后才暴露。
 */
public final class TempDirs {

    private TempDirs() {
    }

    /**
     * 建一个临时目录，返回 {@code toRealPath()} 的结果：Windows 的系统临时目录可能是
     * 8.3 短路径，而 git 返回长路径，不归一化会导致测试里路径比较对不上。
     */
    public static Path create(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("建临时目录失败", e);
        }
    }

    /** 递归删除。目录不存在就什么都不做（幂等，可以直接放进 {@code @AfterAll}）。 */
    public static void deleteRecursively(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            // 先删深的再删浅的 —— Files.walk 是自顶向下的，反序才是"子先于父"
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                deleteForcibly(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("清理 " + root + " 失败", e);
        }
    }

    private static void deleteForcibly(Path path) throws IOException {
        if (!Files.isWritable(path)) {
            path.toFile().setWritable(true);
        }
        Files.deleteIfExists(path);
    }
}
