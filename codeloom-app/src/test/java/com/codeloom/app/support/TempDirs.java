package com.codeloom.app.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 测试用的临时目录：创建、以及**能删干净**的递归删除。
 *
 * <h2>为什么删除不能只是一句 deleteIfExists</h2>
 * <b>Windows 上 git 的对象文件是只读的</b>（{@code .git/objects/...}），而
 * {@code Files.delete} 对只读文件直接抛 {@code AccessDeniedException} ——
 * 于是清理会在测试**全部通过之后**炸掉，看起来像测试失败。
 * 这个坑在 Linux 上不存在（那边只看目录的写权限），所以别按 Linux 的直觉删。
 *
 * <p>两份实现的话，其中一份漏掉只读处理不会有任何征兆 —— 它在别人的 Windows 机器上、
 * 且在测试通过之后才反应出来。
 */
public final class TempDirs {

    private TempDirs() {
    }

    /**
     * 建一个临时目录。
     *
     * <p>返回的是 {@code toRealPath()} 的结果：Windows 上系统临时目录可能是短路径名
     * （8.3 格式），而 git 返回的是长路径名 —— 不归一化的话，测试里按路径比较会对不上。
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
