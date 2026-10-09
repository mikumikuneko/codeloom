package com.codeloom.domain.port;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.workspace.WorkspaceId;

import java.util.List;
import java.util.Optional;

/**
 * 工作区那一行的读写 —— 谁在哪个项目里的那棵树现在停在哪儿。
 *
 * <h2>它和 {@link WorkspaceManager} 的分工</h2>
 * 管理器动的是**磁盘和 git**（建 worktree、提交、合并、回滚），仓储动的是**库里的那一行**。
 * 两者不是一件事，也不该合成一个接口：管理器那一侧的每个方法都要 fork 一个 git 进程，
 * 而"读一下这棵树记着的 HEAD"是一次主键查询。混在一起会让人以为它们代价相当。
 *
 * <h2>为什么它没有 fencing token 的入参</h2>
 * 和 {@code SessionRepository.save} 是同一个道理，而且这里更强：那一列**只由
 * {@code WorkspaceFence} 推进**，本仓储的任何写入都不碰它。见 {@code WorkspaceRow}
 * 的类注释 —— 列清单里刻意不含它。
 */
public interface WorkspaceRepository {

    /**
     * 库里记着的这棵树：**有那一行就返回它，没有就是空**。
     *
     * <p>**空只说明库里没有这一行** —— 和"磁盘上有没有那个目录"无关，那是
     * {@link WorkspaceManager#find} 回答的另一个问题。两个都叫 {@code find}，
     * 判据不同：一个查库，一个看磁盘。
     *
     * <p>拿到的是行里记着的那个位置，也就是这棵树**上一次被写下来**的样子；
     * 它此刻真跑到哪儿了要问管理器（见 {@link Workspace} 的两副面孔）。
     * {@code headCommit} **可能是 null**（空项目起步那种，见建表时那一列的注释）——
     * 别把它当成一定有个 sha。
     */
    Optional<Workspace> find(WorkspaceId workspaceId);

    /**
     * 这棵树，找不到就抛。
     *
     * <p>做成 default 方法而不是让每个调用方抄一遍 {@code orElseThrow}：跳过的那些调用点
     * （同步、回滚、合并）都建立在同一个前提上 —— **有会话就一定有它的树**，
     * 因为建会话时就写好了这一行。所以"找不到"永远是服务端状态不对（500），
     * 不是客户端传错了，而这条判断不该在每个调用点重新表述一次。
     */
    default Workspace require(WorkspaceId workspaceId) {
        return find(workspaceId).orElseThrow(() -> new IllegalStateException(
                "工作区不存在：" + workspaceId
                        + "。会话建出来时就该有这一行 —— 现在没有，只可能是它被删过。"));
    }

    /**
     * 这个项目下**所有人**的工作区。
     *
     * <p>存在的理由很具体：会话列表要给每条会话带上"它的代码现在在哪个 commit"，
     * 而那是树的属性。按会话逐个查就是 N+1，而这个 N 的上限是成员数（≤2）——
     * 一次查回来再按 id 索引，比省这一次查询省下的多。
     */
    List<Workspace> findByProject(ProjectId projectId);

    void save(Workspace workspace);

    /**
     * 删掉**一棵**树的那一行 —— 「退出项目」的一步：退出只没一个人、一棵树。
     *
     * <p>最后一个人退出时走的也是它（那时候他这棵就是仅剩的那棵），
     * 项目下所有人一起删这种情况**不存在**：别人早就各自把自己的带走了。
     *
     * <p>它只删库里的行，磁盘上的目录由 {@code WorkspaceManager.removeWorkspace} 收尾 ——
     * 两者不能互相替代：行删了目录还在的话，下一次 {@code create} 会发现
     * "目录已经在那儿"而直接复用它（那条幂等分支是对的，前提是"目录在"
     * 真的意味着"这棵树已经建好了"）。
     */
    void delete(WorkspaceId workspaceId);
}
