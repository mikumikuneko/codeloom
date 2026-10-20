package com.codeloom.domain.port;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.workspace.WorkspaceId;

import java.util.List;
import java.util.Optional;

/**
 * 工作区那一行的读写 —— 谁在哪个项目里的那棵树现在停在哪儿。
 *
 * <p>与 {@link WorkspaceManager} 分工：管理器动磁盘和 git（建 worktree、提交、合并、
 * 回滚），仓储动库里的那一行；不合并是因为管理器每个方法都要 fork git 进程，而读一下
 * 这棵树记着的 HEAD 是一次主键查询，混在一起会让人以为它们代价相当。它没有 fencing
 * token 的入参：那一列只由 {@code WorkspaceFence} 推进，本仓储的任何写入都不碰它
 * （见 {@code WorkspaceRow} 的列清单）。
 */
public interface WorkspaceRepository {

    /**
     * 库里记着的这棵树：有那一行就返回它，没有就是空。
     *
     * <p>空只说明库里没有这一行，和「磁盘上有没有那个目录」无关 —— 那是
     * {@link WorkspaceManager#find} 回答的另一个问题。拿到的是行里记着的位置，也就是
     * 这棵树上一次被写下来的样子；它此刻实际在哪个 commit 要问管理器（见
     * {@link Workspace} 的两种读法）。{@code headCommit} 可能是 null（空项目起步），
     * 别把它当成一定有个 sha。
     *
     * @param workspaceId 谁在哪个项目里的那一份
     * @return 库里的工作区行；不存在时为空
     */
    Optional<Workspace> find(WorkspaceId workspaceId);

    /**
     * 这棵树，找不到就抛。
     *
     * <p>做成 default 方法而不是让调用方各抄一遍 {@code orElseThrow}：跳过的那些调用点
     * （同步、回滚、合并）都建立在同一个前提上 —— 有会话就一定有它的树，因为建会话时
     * 就写好了这一行。所以「找不到」永远是服务端状态不对（500），不是客户端传错了。
     *
     * @param workspaceId 哪棵树
     * @return 工作区行
     * @throws IllegalStateException 库里没有这一行
     */
    default Workspace require(WorkspaceId workspaceId) {
        return find(workspaceId).orElseThrow(() -> new IllegalStateException(
                "工作区不存在：" + workspaceId
                        + "。会话建出来时就该有这一行 —— 现在没有，只可能是它被删过。"));
    }

    /**
     * 这个项目下所有人的工作区。
     *
     * <p>会话列表要给每条会话带上「它的代码现在在哪个 commit」，而那是树的属性。按会话
     * 逐个查就是 N+1，而这个 N 的上限是成员数（≤2）；一次查回来再按 id 索引更划算。
     *
     * @param projectId 项目
     * @return 该项目下全部工作区行
     */
    List<Workspace> findByProject(ProjectId projectId);

    /**
     * 新建或整体更新这一行。
     *
     * @param workspace 工作区值
     */
    void save(Workspace workspace);

    /**
     * 删掉一棵树的那一行 —— 「退出项目」的一步：退出只带走一个人、一棵树。
     *
     * <p>最后一个人退出时走的也是它（那时他这棵就是仅剩的那棵）；「项目下所有人一起删」
     * 这种情况不存在：别人早就各自删掉了自己的那一行。它只删库里的行，磁盘上的目录由
     * {@code WorkspaceManager.removeWorkspace} 删除 —— 两者不能互相替代：行删了目录还在
     * 的话，下一次 {@code create} 会发现「目录已经在那儿」而直接复用它（那条幂等分支
     * 是对的，前提是「目录在」真的意味着「这棵树已经建好了」）。
     *
     * @param workspaceId 要删的树
     */
    void delete(WorkspaceId workspaceId);
}
