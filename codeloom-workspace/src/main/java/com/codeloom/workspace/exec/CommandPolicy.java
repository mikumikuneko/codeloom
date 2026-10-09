package com.codeloom.workspace.exec;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 命令白名单 —— 它只回答一件事：**这条命令要不要先问人。**
 *
 * <h2>它**不是**"准入"</h2>
 * 它不能同时决定"要不要问"和"跑不跑得起来"：那两个条件一旦是同一个，
 * 合起来就是一句自相矛盾的话 —— **需要审批的命令，一定跑不起来**，
 * 点多少次批准都一样。真实会话里的形态：用户批准 {@code javac -version}，
 * 回来的却是「命令被拒绝: javac，允许的可执行文件 [python, node, mvn, …]」。
 *
 * <p>所以名单只有一个身份。能不能跑由**人**决定（审批那一层把命令和理由摆出来），
 * 能碰到什么由**工作区**决定。见 {@link LocalCommandExecutor} 的类注释。
 *
 * <h2>所以它该收什么</h2>
 * 收**高频且无害**的：看目录、构建、跑测试这些一条命令一件事的操作。
 * 把它们收进来，是为了让人不必为"看一眼文件"点一次批准 ——
 * 而不是因为它们"安全"。名单判断不了参数，{@code mvn} 和
 * {@code mvn -f ../../outside/pom.xml} 在它眼里一样。
 *
 * <h2>比的是「可执行文件名」，不是完整路径 —— 带路径的一律不算</h2>
 * 名单说的是"**PATH 上的那个程序**"：{@code /usr/bin/mvn} 和
 * {@code C:\tools\mvn.exe} 都归一到 {@code mvn}，配在 yml 里的 {@code mvn.cmd}
 * 也归一到 {@code mvn}（同一个程序的不同写法）。
 *
 * <p>但**模型写的那个词带路径时，一律不算**：{@code ./gradlew}、{@code bin/mvn}、
 * {@code C:\evil\mvn.exe} 都要先问人。理由是名单的本意 —— 那说的是系统从 PATH 找到的
 * 那个程序，而 {@code /tmp/evil/mvn} 是**另一个程序**。这条在收整串之后变得更要紧：
 * argv[0] 不会被剥掉目录再执行，那一行原样交给 shell，带路径就真的跑那个文件。
 *
 * <h2>为什么是类，不是接口</h2>
 * 它没有第二个实现，也没有一处 lambda —— 只有一种造法（{@link #allowList}：私有构造器
 * 加静态工厂），唯一的调用点是 {@code TurnInputFactory}。所以"可替换的策略"这层抽象并不存在，
 * 现在它就是普通的不可变类：名单在造出来的时候定下，{@link #isAllowed} 只读它。
 *
 * <p>下面那三个 static 也不是"给实现方用的工具"—— 它们不是这个类的对外契约，
 * 而是这一族共用的**词汇表**：{@link #allowList} 自己用，
 * {@link CommandPathScope} 判断 mv/rm 这一族时也用同一个口径归一程序名。
 * 两边必须一致，所以放在一起；散开就会出现两套口径。
 */
public final class CommandPolicy {

    /** 名单本体。造的时候已经归一并去重，且不可变 —— 多轮对话的线程同时读它。 */
    private final Set<String> allowed;

    private CommandPolicy(Set<String> allowed) {
        this.allowed = Set.copyOf(allowed);
    }

    /**
     * @param executableName 命令的第一个词。**带路径分隔符的一律返回 false** ——
     *                       见类注释，那说的是另一个程序，不是名单里的这个
     */
    public boolean isAllowed(String executableName) {
        return !hasDirectory(executableName) && allowed.contains(bareExecutableName(executableName));
    }

    /** 只允许列出的这些可执行文件。 */
    public static CommandPolicy allowList(String... executables) {
        Set<String> names = new LinkedHashSet<>();
        Arrays.stream(executables).map(CommandPolicy::bareExecutableName).forEach(names::add);
        return new CommandPolicy(names);
    }

    /** 这个词自己带了目录吗。见类注释 —— 带目录的不在名单的语义里。 */
    static boolean hasDirectory(String raw) {
        if (raw == null) {
            return false;
        }
        String name = raw.strip().replace('\\', '/');
        return name.indexOf('/') >= 0;
    }

    /**
     * 去掉目录，**保留扩展名**。它是 {@link #bareExecutableName} 的第一步。
     *
     * <pre>
     *   /usr/bin/mvn      → mvn
     *   C:\tools\mvn.exe  → mvn.exe     ← 扩展名留着
     *   C:/x/npm.CMD      → npm.CMD
     * </pre>
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
     * 把任意路径样式归一成裸可执行文件名：去掉目录，再去掉扩展名。名单两边都按它比对。
     *
     * <pre>
     *   /usr/bin/mvn            → mvn
     *   C:\tools\mvn.exe        → mvn
     *   node                    → node
     *   C:/x/npm.CMD            → npm
     * </pre>
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
