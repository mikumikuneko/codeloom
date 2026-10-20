package com.codeloom.workspace;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据目录的唯一决定点：repos、workspaces、git sandbox 三条路径都从这里推导，
 * 别处不再拼路径。
 *
 * <p>自动推导的位置是项目根下的 {@code data/}。项目根是从工作目录向上第一个存在
 * {@code .git} 的目录；worktree 里的 {@code .git} 是文件，所以用存在性而不是目录判断。
 * 找不到时抛 {@link IllegalStateException} 并说明如何显式配置，绝不回退到别处 ——
 * 数据悄悄落到另一个位置比启动失败更难发现。理由见
 * {@code docs/decisions/architecture/2026-09-29-data-path-anchor-fails-loud.md}。
 */
public final class DataPaths {

    /**
     * 项目根的标记：这一层存在 {@code .git} 就说明是项目根。
     *
     * <p>不认 {@code pom.xml}：模块目录也有它，从模块目录启动与从项目根启动会得到两个
     * 答案。{@code .git} 是 git 自己找仓库根的记号，没有引入本项目独有的约定。
     */
    private static final String PROJECT_MARKER = ".git";

    /**
     * 数据目录名，位于项目根下，且在 {@code .gitignore} 里。
     *
     * <p>固定在项目根而不是某个模块，模块结构变化不影响它。
     */
    private static final String DATA_DIR = "data";

    private DataPaths() {
    }

    /**
     * 定位一条数据路径。
     *
     * @param configured 显式配置的值；null 或空白表示自动推导
     * @param underProjectRoot 自动推导时相对 {@code 项目根/data} 的后缀，例如 {@code repos}
     * @return 规范化后的绝对路径；显式配置的相对路径相对于进程工作目录解析
     * @throws IllegalStateException 自动推导时找不到项目根
     */
    public static Path locate(String configured, String underProjectRoot) {
        if (configured != null && !configured.isBlank()) {
            // 显式配置优先；相对路径按进程工作目录解析
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return projectRoot().resolve(DATA_DIR).resolve(underProjectRoot);
    }

    /** 从工作目录向上找到项目根；找不到就抛，见类注释。 */
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
