package com.codeloom.app.project;

import com.codeloom.domain.port.Workspace;

import java.nio.file.Path;

/**
 * 项目根的位置：每棵工作区根下的 {@code untitled/} 就是项目根。
 *
 * <p>工作区是一棵 git 树，合并、回滚、checkpoint 都在那一层；项目是用户看见的源码
 * 与目录结构。两层不分时，界面每一行都带着 {@code untitled/} 前缀，模型也分不清
 * 哪边是项目。现在与 IDEA 对齐：工作区根下用户可见的目录只有这一个，它本身就是项目根；
 * 平台自己的输出目录在项目根下（见 {@link Workspace#TOOL_OUTPUT_DIR}），文件树按名字
 * 隐藏它。
 *
 * <p>agent 工具的相对路径、{@code run_command} 的工作目录、文件接口、文件树和验证探测
 * 都以项目根为基准。git 操作不属于这里：它们要的是 {@link Workspace#path()}，
 * 拿项目根去做会退到子目录上，git 的分支不会跟着走。
 *
 * <p>项目根为什么长这样见
 * {@code docs/decisions/architecture/2026-09-29-untitled-seed-and-empty-readme.md}。
 */
public final class ProjectLayout {

    /**
     * 项目根的目录名，也是新项目自带的顶层目录。叫 {@code untitled} 是因为创建时还没有
     * 可用的目录名；新项目里会放一个空的 README（git 存不了空目录），理由见类注释。
     */
    public static final String DIRECTORY = "untitled";

    private ProjectLayout() {
    }

    /**
     * @param workspace 一棵工作区
     * @return 这棵树的项目根；所有"项目里的路径"都以它为准
     */
    public static Path rootOf(Workspace workspace) {
        return rootBelow(workspace.path());
    }

    /**
     * @param worktree 工作区根
     * @return 项目根；给手上有 {@code Path} 而不是 {@link Workspace} 的地方用
     */
    public static Path rootBelow(Path worktree) {
        return worktree.resolve(DIRECTORY);
    }

    /**
     * git 路径转为项目路径；落在项目外面时为 null。
     *
     * <p>git 只知道仓库，报的是 {@code untitled/a.txt}；界面说的是 {@code a.txt}。
     * 让 git 路径直接漏出去，用户会看见从没建过的 {@code untitled/} 前缀，而且那个路径
     * 拿去问文件接口会指向不存在的 {@code 项目根/untitled/a.txt}。
     *
     * <p>返回 null 的是不在项目目录里的东西（例如工作区根上的 {@code .git}，或任何
     * 建在 {@code untitled/} 之外的路径），它们不属于用户项目，不该出现在冲突清单或
     * "这一轮改了什么"里。
     *
     * @param gitPath 仓库相对路径
     * @return 项目相对路径；不在项目目录下时为 null
     */
    public static String toProjectPath(String gitPath) {
        String prefix = DIRECTORY + "/";
        return gitPath != null && gitPath.startsWith(prefix)
                ? gitPath.substring(prefix.length())
                : null;
    }

    /**
     * @param projectPath 项目相对路径
     * @return 仓库相对路径，供 git 使用
     */
    public static String toGitPath(String projectPath) {
        return DIRECTORY + "/" + projectPath;
    }
}
