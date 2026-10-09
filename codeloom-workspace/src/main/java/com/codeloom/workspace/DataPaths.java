package com.codeloom.workspace;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据落在磁盘上的哪儿 —— **这一个类回答它，别处不许再拼路径**。
 *
 * <h2>它为什么存在</h2>
 * 数据目录若写成各写各的相对路径，就会相对于**进程的工作目录** —— 于是"数据放哪儿"
 * 取决于你怎么启动：在 {@code codeloom-app/} 下跑得到 {@code codeloom-app/repos}，
 * 在项目根下跑（IDEA 就是这样）得到 {@code <项目根>/repos}。同一份配置两种结果，
 * 而库里的 {@code repo_path} 是绝对路径、两边都"能用" —— 界面上完全看不出来，
 * 直到某天删掉其中一个目录。所以三条路径（repos / workspaces / git sandbox）现在
 * 都从这里出来，**只有一个答案** —— 各写各的正是"漏掉一条"的根因。
 *
 * <p>推导出来的位置是 {@code <项目根>/data/} 下 —— **在项目内**，所以
 * "这个项目的数据在哪"有一个直观的答案，清理起来也就是删一个目录。
 * 别人的 clone 得到的是他们自己的 {@code <项目根>/data/}，不会互相干扰。
 *
 * <h2>锚点：项目根 = 有 {@code .git} 的那一层</h2>
 * 从工作目录往上找，找到就停。于是不管从项目根启动、从模块目录启动、还是从
 * 更深的子目录启动，都得到**同一个**项目根 —— 锚点由代码定，不由启动方式定。
 *
 * <p>用 {@code Files.exists} 而不是 {@code isDirectory}：**git worktree 里的 `.git`
 * 是一个文件**（内容是 {@code gitdir: …}），只认目录的话，从 worktree 里启动就会
 * 一路找到磁盘根。
 *
 * <h2>找不到就报错，不换地方</h2>
 * 没有回退。悄悄换一个位置放数据，正是这个类要消灭的那种行为 —— 那时候用户唯一
 * 能看到的线索是"我的项目怎么不见了"。报错信息里写着怎么解决（显式配一个路径），
 * 那比一个正确但没人知道的默认值有用。
 *
 * <p>什么时候会找不到：把应用打包成 jar 拿到别处跑（那里没有 `.git`）。
 * 那种部署形态本来就该显式配数据目录 —— 数据放哪儿是运维决定，不该猜。
 */
public final class DataPaths {

    /**
     * 项目根的标记：这一层有它就说明是项目根。
     *
     * <p>认 {@code .git} 而不是"第一个 pom.xml"：模块目录下也有 pom.xml，
     * 那样从模块目录启动会停在模块目录、从项目根启动会停在项目根 —— **又变成两个答案**。
     *
     * <p>选 {@code .git} 还有第二个理由：它是"项目根"这件事**本来就有的、通用的记号** ——
     * git 自己就是这么找仓库根的。于是这里没有引入任何本项目独有的约定。
     */
    private static final String PROJECT_MARKER = ".git";

    /**
     * 数据放在项目根下的哪个目录。
     *
     * <p>放在**项目根**而不是某个模块里（比如 {@code codeloom-app/}）：数据是运行产物，
     * 而"它在项目的哪一层"只该有一个答案。写成一个固定的目录名之后，代码里就
     * 只剩 {@code .git} 一个约定，模块结构怎么变都不影响它。
     *
     * <p>它在 {@code .gitignore} 里 —— 运行产物不进版本库。
     */
    private static final String DATA_DIR = "data";

    private DataPaths() {
    }

    /**
     * 定位一条数据路径。
     *
     * @param configured 显式配置的值（{@code codeloom.repos-root} 之类）。**空表示自动推导**
     * @param underProjectRoot 自动推导时，相对 {@code <项目根>/data} 的那个后缀，比如 {@code repos}
     */
    public static Path locate(String configured, String underProjectRoot) {
        if (configured != null && !configured.isBlank()) {
            // 显式配了就照它来。相对路径也接受 —— 那时候是"相对于工作目录"，
            // 而调用方既然写了一个相对路径，就是明确要那个语义
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return projectRoot().resolve(DATA_DIR).resolve(underProjectRoot);
    }

    /** 从工作目录往上找到项目根。找不到就抛 —— 见类注释里"不换地方"那段。 */
    private static Path projectRoot() {
        Path start = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve(PROJECT_MARKER))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "从 " + start + " 往上都没找到 " + PROJECT_MARKER + "，所以不知道该把数据放在哪儿。"
                        + "要么在项目目录里启动（IDEA 里直接 Run 就行），"
                        + "要么显式指定位置：codeloom.repos-root / codeloom.workspaces-root"
                        + "（或者环境变量 CODELOOM_REPOS_ROOT / CODELOOM_WORKSPACES_ROOT）。"
                        + "——刻意不给默认的回退位置：那样数据会悄悄落到别处，"
                        + "而那是比启动失败难查得多的问题。");
    }
}
