package com.codeloom.workspace.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/**
 * 在测试里直接跑 git：摆状态和断言状态都走它。
 *
 * <p>断言不使用生产代码的包装，因为包装写错时，用它做的断言会一起错、一起绿。
 * {@link #run} 在退出码非零时抛异常；{@link #output} 不抛，用于"它不存在"这类
 * 以非零为答案的断言。
 */
public final class TestGit {

    private TestGit() {
    }

    /**
     * @param workingDir 工作目录
     * @param args git 参数
     * @return git 的输出，标准输出与标准错误合并
     * @throws IllegalStateException 退出码非零；消息带上 git 输出，避免只剩一句"退出码 1"
     * @throws IOException 无法启动 git 或等待时被中断
     */
    public static String run(Path workingDir, String... args) throws IOException {
        Outcome outcome = execute(workingDir, args);
        if (outcome.exitCode() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args)
                    + " 退出码 " + outcome.exitCode() + "：\n" + outcome.output());
        }
        return outcome.output();
    }

    /**
     * 同样跑一条 git，但退出码非零不算错，用于断言"它不存在"。
     *
     * @param workingDir 工作目录
     * @param args git 参数
     * @return git 的输出
     * @throws IOException 无法启动 git 或等待时被中断
     */
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
