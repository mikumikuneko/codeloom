package com.codeloom.domain.port;

import com.codeloom.domain.workspace.WorkspaceId;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 一棵工作区 —— 一个 git worktree 加它所在的分支和 HEAD。键是 {@link WorkspaceId}
 * （用户 × 项目），不是会话：同一人在同一项目里的所有会话共用这一棵树，换会话不换
 * 代码；隔离粒度是「每个人」，两个人各写各的、汇合时才走三方合并。
 * {@link WorkspaceManager#find} 返回磁盘上那一刻的真相（HEAD 现读
 * {@code git rev-parse HEAD}）；{@link WorkspaceRepository} 存库里记着的位置，与事件
 * 同事务更新。两者不相等时调用方必须清楚拿的是哪一份：要事件一致用仓储，要磁盘现状
 * 用管理器。构造时路径必须是绝对路径。见
 * {@code docs/decisions/architecture/2026-09-24-workspace-per-person-merge-to-converge.md}。
 *
 * @param workspaceId 谁在哪个项目里的那一份
 * @param path        工作区根目录（绝对路径）
 * @param branch      这棵树的固定分支名
 * @param headCommit  当前 HEAD；正常建项目路径会先造基点提交，不会为 null，历史数据可能为空
 */
public record Workspace(WorkspaceId workspaceId, Path path, String branch, String headCommit) {

    /**
     * 平台自己那个目录（相对项目根）。工具输出落在它下面。
     *
     * <p>定义在 domain 是因为往里写的是 agent 模块，把它在 git 那边忽略掉、在文件树里
     * 藏起来的是别的模块，而它们互不依赖 —— 这里是唯一都能看见它的地方；复制成两份
     * 的话，某次改名就会错开，后果是「落盘的东西全进了版本库」或「它出现在文件树里」。
     * 放在项目目录内部是刻意的：模型用普通的 {@code read_file} 就能读回来，不必给路径
     * 守卫（{@code WorkspacePathGuard}）开任何口子。见
     * {@code docs/decisions/architecture/2026-10-04-tool-output-spills-to-the-workspace.md}。
     */
    public static final String PLATFORM_DIR = ".codeloom";

    /**
     * agent 存放完整工具输出的目录（相对项目根）。
     *
     * <p>从 {@link #PLATFORM_DIR} 拼出来，不另写一遍：文件树那边藏的是父目录（整个
     * {@code .codeloom}）。两边各写各的话，哪天把工具输出挪到别处，它就会一边被忽略、
     * 一边出现在文件树里。
     */
    public static final String TOOL_OUTPUT_DIR = PLATFORM_DIR + "/tool-output";

    public Workspace {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(branch, "branch");
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("工作区路径必须是绝对路径：" + path);
        }
    }

    /**
     * 把这棵树记着的位置挪到另一个 commit。
     *
     * <p>调用点在收尾的 checkpoint、同步和回滚，都在事务里、和一条事件一起落 —— 事件和
     * 这一列必须同时成立，否则「发生过什么」和「代码在哪」就对不上。见
     * {@code docs/decisions/architecture/2026-09-29-rewind-the-whole-tree.md} 与
     * {@code docs/decisions/architecture/2026-09-29-sync-before-merge.md}。
     *
     * @param headCommit 新的 HEAD 提交
     * @return 更新了 HEAD 的工作区值
     */
    public Workspace withHeadCommit(String headCommit) {
        return new Workspace(workspaceId, path, branch, headCommit);
    }
}
