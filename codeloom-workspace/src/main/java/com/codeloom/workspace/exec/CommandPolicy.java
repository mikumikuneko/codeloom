package com.codeloom.workspace.exec;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 命令白名单：只回答"这条命令要不要先问人"，不回答"它能不能跑"。
 *
 * <p>若拿它当准入，需要审批的命令就永远跑不起来（不在白名单等价于被拒绝），点多少次
 * 批准都一样。能不能跑由人决定，能碰到什么由工作区约束，环境变量由
 * {@link ProcessRunner} 清洗。见
 * {@code docs/decisions/architecture/2026-09-30-approval-is-scoped-to-the-project.md}。
 *
 * <p>名单按可执行文件名比对，不是完整路径：对比时去掉目录和 {@code .exe}、{@code .cmd}、
 * {@code .bat} 扩展名。但模型写的词自带目录时一律不放行 —— 那指的是另一个程序，
 * 而不是 PATH 上的这个；命令原样交给 shell，带路径就会真的执行那个文件。
 *
 * <p>类不可变：名单在 {@link #allowList} 构造时归一并去重，之后只读，可被多线程共享。
 * 归一化程序名的静态方法也供 {@link CommandPathScope} 使用，保证两处口径一致。
 */
public final class CommandPolicy {

    /** 名单本体：构造时已归一并去重，不可变，可被多线程同时读取。 */
    private final Set<String> allowed;

    private CommandPolicy(Set<String> allowed) {
        this.allowed = Set.copyOf(allowed);
    }

    /**
     * @param executableName 命令的第一个词
     * @return 程序名在名单里且不带目录；带路径分隔符的一律 false，见类注释
     */
    public boolean isAllowed(String executableName) {
        return !hasDirectory(executableName) && allowed.contains(bareExecutableName(executableName));
    }

    /**
     * 只允许列出的这些可执行文件。
     *
     * @param executables 可执行文件名，可带目录与扩展名，构造时归一化
     * @return 不可变白名单
     */
    public static CommandPolicy allowList(String... executables) {
        Set<String> names = new LinkedHashSet<>();
        Arrays.stream(executables).map(CommandPolicy::bareExecutableName).forEach(names::add);
        return new CommandPolicy(names);
    }

    /** 这个词自己带了目录吗；带目录的不在名单的语义里，见类注释。 */
    static boolean hasDirectory(String raw) {
        if (raw == null) {
            return false;
        }
        String name = raw.strip().replace('\\', '/');
        return name.indexOf('/') >= 0;
    }

    /**
     * 去掉目录并保留扩展名；它是 {@link #bareExecutableName} 的第一步。
     *
     * <pre>
     *   /usr/bin/mvn      → mvn
     *   C:\tools\mvn.exe  → mvn.exe
     *   C:/x/npm.CMD      → npm.CMD
     * </pre>
     *
     * @param raw 原始词
     * @return 最后一段路径；null 或空白返回空串
     */
    static String baseName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String name = raw.strip().replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        return lastSlash >= 0 ? name.substring(lastSlash + 1) : name;
    }

    /**
     * 把任意路径样式归一成裸可执行文件名：去掉目录，再去掉扩展名；名单两边都按它比对。
     *
     * <pre>
     *   /usr/bin/mvn            → mvn
     *   C:\tools\mvn.exe        → mvn
     *   node                    → node
     *   C:/x/npm.CMD            → npm
     * </pre>
     *
     * @param raw 原始词
     * @return 归一化后的程序名；无法归一化时返回空串
     */
    static String bareExecutableName(String raw) {
        String name = baseName(raw);
        String lower = name.toLowerCase(Locale.ROOT);
        // 认这几种后缀就够了：Windows 上能被执行的就是它们（.ps1 要 powershell 才能跑，
        // 而解释器本身不该进白名单）
        for (String suffix : new String[]{".exe", ".cmd", ".bat"}) {
            if (lower.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }
}
