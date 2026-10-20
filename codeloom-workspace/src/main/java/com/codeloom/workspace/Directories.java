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
 * 目录树的递归删除，生产代码里的唯一实现。
 *
 * <p>不跟随符号链接：遍历把链接当普通文件，删除的是链接本身而不是它指向的内容。
 * 根目录本来就不存在时什么都不做。
 */
public final class Directories {

    private Directories() {
    }

    /**
     * 递归删掉一棵目录树；根目录本来就不存在时什么都不做。
     *
     * @param root 要删除的目录
     * @throws UncheckedIOException 删除过程中发生 I/O 失败
     */
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
     * 删一个文件或一个空目录；被只读位挡住时先去掉它再试一次。
     *
     * <p>git 会把 {@code .git} 下的对象文件设为只读，而 Windows 上删除只读文件会被
     * 拒绝；没有这一步，删除在 Linux 上正常、在 Windows 上抛 {@link AccessDeniedException}。
     *
     * @throws IOException 去掉只读位后仍无法删除
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
