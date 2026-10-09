package com.codeloom.workspace.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/**
 * 在测试里直接跑 git：**摆状态**用它，**断言状态**也用它。
 *
 * <h2>为什么断言不借生产那个包装</h2>
 * 用被测代码去断言被测代码，等于自己给自己作证：包装写错的时候，
 * 那条断言会跟着一起错，然后一起绿。所以断言一律走这里，直接问 git 本身。
 *
 * <h2>两种失败方式</h2>
 * {@link #run} 非零就抛（摆状态时这个就是错）；{@link #output} 不抛（问"这条分支
 * 不存在吧"时，非零正是答案）。
 */
public final class TestGit {

    private TestGit() {
    }

    /**
     * @return git 的输出（标准输出与标准错误合并）
     * @throws IllegalStateException 退出码非零 —— 消息里带上 git 自己的输出，
     *                               否则失败现场只有一句"退出码 1"
     */
    public static String run(Path workingDir, String... args) throws IOException {
        Outcome outcome = execute(workingDir, args);
        if (outcome.exitCode() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args)
                    + " 退出码 " + outcome.exitCode() + "：\n" + outcome.output());
        }
        return outcome.output();
    }

    /** 同样跑一条 git，但**退出码非零不算错** —— 断言"它不存在"时要用它。 */
    public static String output(Path workingDir, String... args) throws IOException {
        return execute(workingDir, args).output();
    }

    private static Outcome execute(Path workingDir, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        try {
            return new Outcome(process.waitFor(), new String(output, StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等 git 时被中断", e);
        }
    }

    private record Outcome(int exitCode, String output) {
    }
}
