package com.codeloom.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 目录的递归删除。**只有一份。**
 *
 * <p>它是独立的一个类，因为它有不止一个调用方（用户在树里右键删一个目录，见
 * {@code WorkspaceFilesService}）—— 而"删一棵目录树"里有一件很容易漏掉的事
 * （见 {@link #deleteOne}），漏掉的表现是"删到一半失败"，那种 bug 抄第二遍的时候
 * 一定会漏。
 *
 * <p>不跟随符号链接：{@code walkFileTree} 默认的遍历方式就是把它当**文件**处理，
 * 于是删掉的是链接本身，而不是它指向的东西 —— 那是这里唯一正确的行为。
 */
public final class Directories {

    private Directories() {
    }

    /** 递归删掉一棵目录树。**本来就不在时什么都不做**（幂等）。 */
    public static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    deleteOne(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failure)
                        throws IOException {
                    deleteOne(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("删除目录失败：" + root, e);
        }
    }

    /**
     * 删一个文件或一个空目录；被只读位挡住时先摘掉它再试一次。
     *
     * <p>这不是防御性编程：<strong>git 会把 {@code .git} 下的对象文件设成只读</strong>，
     * 而 Windows 上删只读文件会被直接拒绝。少了这一步，"删除"在 Linux 上一切正常、
     * 在自己的开发机上却报一个 {@code AccessDeniedException} —— 那种最难查。
     */
    private static void deleteOne(Path path) throws IOException {
        try {
            Files.delete(path);
        } catch (AccessDeniedException e) {
            // 不看 setWritable 的返回值：下面这次删除会给出真正的答案
            path.toFile().setWritable(true);
            Files.delete(path);
        }
    }
}
