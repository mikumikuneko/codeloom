package com.codeloom.domain.port;

import com.codeloom.domain.workspace.WorkspaceId;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 一棵工作区 —— 本质上是一个 git worktree 加它所在的分支和 HEAD。
 *
 * <p>它的键是 {@link WorkspaceId}（用户 × 项目），而**不是会话**：同一个人在同一项目里的
 * 所有会话共用这一棵树，换会话不换代码。理由见 {@code WorkspaceId} 的类注释。
 *
 * <p>隔离的粒度因此是「每个人」而不是「每条会话」：两个人各有各的树，各写各的，
 * **零冲突**；等汇合时才走三方合并。这和当下 agent 工具的通行做法是同一个思路
 * （Claude Code 也有 worktree 隔离），只是这里把"一个写入者"从会话提升到了人。
 *
 * <h2>它有两副面孔，别混起来</h2>
 * <ul>
 *   <li><b>磁盘上那一刻的真相</b> —— {@link WorkspaceManager#find} 返回的就是这个，
 *       {@code headCommit} 现读 {@code git rev-parse HEAD}。
 *   <li><b>库里记着的位置</b> —— {@link WorkspaceRepository} 存的就是这个，
 *       它和事件在同一个事务里更新（见 {@code SessionWriter}）。
 * </ul>
 * 两者绝大多数时候相等。不相等的那一刻（比如裁决收尾推进了 HEAD 而事件还没落）
 * 正是"事务"存在的意义，所以调用方必须清楚自己拿的是哪一份 ——
 * 需要"和事件一致"就用仓储，需要"磁盘上现在是什么"就用管理器。
 *
 * @param workspaceId 谁在哪个项目里的那一份
 * @param path        工作区根目录（绝对路径）
 * @param branch      这棵树的固定分支名
 * @param headCommit  当前 HEAD；空项目（尚无提交）时为 null
 */
public record Workspace(WorkspaceId workspaceId, Path path, String branch, String headCommit) {

    /**
     * 平台自己那个目录（相对工作区根）。工具输出落在它下面。
     *
     * <p>为什么这个常量住在 domain：往里写的是 agent 模块，把它在 git 那边忽略掉、在文件树里
     * 藏起来的是别的模块，而它们**互不依赖**（那条边是刻意删掉的）——
     * 定义在这儿是它们唯一都能看见它的地方。复制成两份的话，某次改名就会错开，
     * 而错开的后果是"落盘的东西全进了版本库"或者"它出现在文件树里"。
     *
     * <p>放在**工作区内部**是刻意的：那样模型用普通的 {@code read_file} 就能读回来，
     * 不必给路径守卫（{@code WorkspacePathGuard}）开任何口子。
     */
    public static final String PLATFORM_DIR = ".codeloom";

    /**
     * agent 存放**完整**工具输出的目录（相对工作区根）。
     *
     * <p>从 {@link #PLATFORM_DIR} 拼出来，不另写一遍：文件树那边藏的是**父目录**
     *（整个 {@code .codeloom}），两边各写各的话，哪天把工具输出挪到别处去，
     * 它就会一边被忽略、一边出现在文件树里。
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
     * <p>用它的地方只有"同步"和"回滚"两处，而且都在事务里、和一条事件一起落 ——
     * 事件和这一列必须同时成立，否则"发生过什么"和"代码在哪"就对不上了。
     */
    public Workspace withHeadCommit(String headCommit) {
        return new Workspace(workspaceId, path, branch, headCommit);
    }
}
