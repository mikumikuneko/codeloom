package com.codeloom.app.project;

import com.codeloom.domain.port.Workspace;

import java.nio.file.Path;

/**
 * **项目根在哪** —— 一个项目只有这一个答案，所以它写在一个地方。
 *
 * <h2>为什么要有一个"项目根"，而不是直接拿工作区当项目</h2>
 * 因为这两件事不是一回事：
 * <ul>
 *   <li><b>工作区</b>是一棵 git 树（{@code worktree}）—— 合并、回滚、checkpoint
 *       全在它这一层发生，它的根底下是我们自己要用的东西。</li>
 *   <li><b>项目</b>是用户看见的那一层 —— 他的源码、他的目录结构。</li>
 * </ul>
 *
 * <p>两层不分时，界面里每一行都顶着一个 {@code untitled/} 的前缀（那个名字对用户毫无意义），
 * 而<strong>模型也分不清哪边是项目</strong> —— 它有时候写进 {@code untitled/}，
 * 有时候直接写在根上，因为**没有任何东西告诉它哪边是项目**。
 *
 * <p>现在和 IDEA 对齐：**先有一个项目目录，文件都在它里面**。
 * 工作区根下只有它一个，它**就是**项目根。
 *
 * <h2>谁该用它、谁不该</h2>
 * <ul>
 *   <li><b>该用</b>：agent 的工具（相对路径的基准）、{@code run_command} 的工作目录、
 *       文件接口、文件树、自动验证探测构建文件的位置。</li>
 *   <li><b>不该用</b>：所有 git 操作。树、分支、checkpoint、合并、回滚都属于
 *       <b>工作区</b>那一层，它们要的是 {@link Workspace#path()}。
 *       拿项目根去做 git 操作会退到子目录上，而 git 的分支不会被它带走。</li>
 * </ul>
 */
public final class ProjectLayout {

    /**
     * 新项目自带那个目录的名字。**它同时就是项目根。**
     *
     * <p>叫 {@code untitled} 是因为项目刚建出来时还没有名字 —— 和"新建文档"是同一个意思。
     * 它是一个**默认的落脚处**，用户想叫什么自己改就是了；而改一个目录的名字，
     * 比让一个名字在各种文件系统上都写不进去容易得多。
     */
    public static final String DIRECTORY = "untitled";

    private ProjectLayout() {
    }

    /** 这棵树的项目根。**所有"项目里的路径"都以它为准。** */
    public static Path rootOf(Workspace workspace) {
        return rootBelow(workspace.path());
    }

    /** 从工作区根推出来 —— 给手上有 {@code Path} 而不是 {@link Workspace} 的地方用。 */
    public static Path rootBelow(Path worktree) {
        return worktree.resolve(DIRECTORY);
    }

    /**
     * git 报出来的路径 → 项目里的路径。**落在项目外面的是 null。**
     *
     * <h2>为什么必须翻这一道</h2>
     * git 只知道仓库：它说 {@code untitled/a.txt}。而界面说的是项目：
     * 它说 {@code a.txt}。让 git 的路径直接漏到界面上，会有两个后果 ——
     * 用户看见一个他从没建过的 {@code untitled/} 前缀（他建的是"一个项目"，
     * 不是"一个叫 untitled 的文件夹"），而那个路径拿去问文件接口会指向
     * {@code <项目根>/untitled/a.txt}，**不存在的地方**。
     *
     * <p>返回 null 的那些是**不在那个项目目录里的东西**（工作区根上的 {@code .codeloom/}
     * 之类）—— 它们不是用户项目的一部分，不该出现在冲突清单或者"这一轮改了什么"里。
     */
    public static String toProjectPath(String gitPath) {
        String prefix = DIRECTORY + "/";
        return gitPath != null && gitPath.startsWith(prefix)
                ? gitPath.substring(prefix.length())
                : null;
    }

    /** 项目的路径 → git 的路径。给"客户端给了一个路径、要拿去问 git"的地方用。 */
    public static String toGitPath(String projectPath) {
        return DIRECTORY + "/" + projectPath;
    }
}
