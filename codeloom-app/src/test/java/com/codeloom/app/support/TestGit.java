package com.codeloom.app.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 在测试里直接跑 git，用来**摆出一个特定的仓库状态**（制造冲突、伪造别人的提交…）。
 *
 * <h2>只在测试里用</h2>
 * 生产代码调 git 必须走 {@code WorkspaceManager} 那个出口 —— 见它的类注释：
 * "git 参数由我们构造，所以是可信的"这条约束，成立的前提是**只有一个出口**。
 * 这个类存在的理由是测试需要摆出非法或不便通过那个出口构造的状态。
 *
 * <p>两份实现的话，失败方式还会不一样（断言 vs 抛异常），排查时要先确认自己看的是哪一份。
 */
public final class TestGit {

    private TestGit() {
    }

    /**
     * @return git 的输出（标准输出与标准错误合并）。调用方大多只关心它有没有失败，
     *         但有的断言要看输出
     * @throws IllegalStateException 退出码非零 —— 消息里带上 git 自己的输出，
     *                               否则失败现场只有一句"退出码 1"
     */
    public static String run(Path workingDir, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException(
                        "git " + String.join(" ", args) + " 退出码 " + exitCode + "：\n" + output);
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等 git 时被中断", e);
        }
    }
}
