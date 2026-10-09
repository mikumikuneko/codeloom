package com.codeloom.app.turn;

import com.codeloom.app.session.CommitIdentities;
import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.workspace.FileChange;
import com.codeloom.domain.workspace.WorkspaceId;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 一轮的工作区：**拿到它，然后提交它**。
 *
 * <h2>为什么这两件事属于同一个类</h2>
 * 它们共用同一个前提 —— "这条会话的树在哪"。而提交的署名也必须来自
 * **会话所有者**（见 {@link #commit}），所以两件事用的是同一份身份来源，
 * 拆开只会让"身份从哪来"这件事出现第二个说法。
 *
 * <h2>为什么从 TurnExecutor 里拿出来</h2>
 * 执行器要的是"给我一个能跑的工作区"和"把这一轮的改动落成一笔提交"，
 * 不需要知道 worktree 是怎么建的、署名是怎么解析的。
 */
@Component
public class TurnWorkspace {

    private final WorkspaceManager workspaces;
    private final WorkspaceRepository stored;
    private final CommitIdentities identities;

    public TurnWorkspace(WorkspaceManager workspaces,
                         WorkspaceRepository stored,
                         CommitIdentities identities) {
        this.workspaces = workspaces;
        this.stored = stored;
        this.identities = identities;
    }

    /**
     * 取这条会话那棵树的工作区，没有就建一个。
     *
     * <h2>为什么"建"要带上库里记着的位置</h2>
     * 常见路径（目录还在）根本不碰库，一次 {@code git rev-parse} 就返回了。走到建那一步
     * 意味着**目录被人从磁盘上删掉了** —— 这时候仍然要按它记录里的位置重建。
     * 不带 {@code baseCommit} 的话，重建出来的树会停在主干上，而这棵树本来已经往前走了，
     * 于是**这种"修一下"会静默地把进度退回去**。
     */
    Workspace ensure(Session session, Project project) {
        WorkspaceId id = session.workspaceId();
        Optional<Workspace> live = workspaces.find(id);
        if (live.isPresent()) {
            return live.get();
        }
        Workspace recorded = stored.find(id).orElseThrow(() -> new IllegalStateException(
                "工作区 " + id + " 既不在磁盘上也不在库里 —— 那是服务端状态不对，不是客户端传错了"));
        return workspaces.create(id, Path.of(project.repoPath()), recorded.headCommit());
    }

    /**
     * 把这一轮在工作区里的改动提交成一个 commit，返回它的 sha。
     *
     * <h2>为什么必须提交</h2>
     * 不提交的话，agent 写出来的文件对 git 来说就是**未跟踪的**，而下游三件事
     * 全都是按"分支上的提交"来看的：
     * <ul>
     *   <li><b>合并</b>：合的是分支。分支上没有那一笔提交，于是"合并成功"却什么都没落地
     *   <li><b>回滚</b>：{@code reset --hard} 不碰未跟踪文件
     *   <li><b>看 diff</b>：同理
     * </ul>
     * 三个洞的根因是同一个：**事件流里的"我说我改了"和 git 里的"真的改了"之间
     * 缺了一笔提交**。补在这里 —— 一轮结束、收尾之前，恰好是所有下游都要用的那个时刻。
     *
     * <p>署名必须是**会话所有者**：git log 里要能看出每笔提交是哪位用户产出的。
     * 同步会把主干的提交**原样带进**这棵树，于是同一条分支上混着两个人的提交 ——
     * 署名是事后分辨"这笔是谁做的"的唯一依据。
     *
     * <h2>为什么还要交回"提交前的位置"</h2>
     * 工作区没有改动时提交返回的是**当前 HEAD**、不报错 —— 一个只说话不动手的轮次也该有个
     * 位置可回。于是"没动过手"和"真的产出了代码"交出来的 sha **一模一样**，而下游有地方要按
     * "这一轮做了什么"来算：拿同一个 sha 去算 {@code <sha>^!}，算出来的是**上一个提交**的改动，
     * 同一个文件会被每一轮反复报一遍。前后两个 sha 一起交出去，那个问题由 {@link Commit#created}
     * 回答，调用方不必自己从 sha 相同去猜。
     *
     * <p>{@code before} 走 {@link WorkspaceManager#find} **现读**，不用传进来的那个
     * {@code workspace}：{@link Workspace} 有两副面孔（见它的类注释），库里那份在事务提交之前
     * 不会跟着 git 动，拿它比大小会一直判成"造出了新提交"。
     */
    Commit commit(Session session, Workspace workspace) {
        // 提交信息里是**正在跑的这一轮**的号：会话行上那个数数的是"已经跑完几轮"
        //（见 CheckpointCreated 的注释），所以正在跑的这轮是它 +1。
        // 挂起和批准之后的续跑会各提交一次、两次的号一样 —— 它们本来就是同一轮
        String before = workspaces.find(workspace.workspaceId())
                .map(Workspace::headCommit)
                .orElse(workspace.headCommit());
        String sha = workspaces.commit(workspace,
                "turn " + (session.turnIndex() + 1) + " 的产出",
                identities.of(session.ownerId()));
        return new Commit(sha, before);
    }

    /**
     * 一轮的提交结果。
     *
     * @param sha          这一轮结束时这棵树的位置；没有改动时它就是 {@code previousHead}
     * @param previousHead 提交**之前**现读到的 HEAD
     */
    record Commit(String sha, String previousHead) {

        /**
         * 这一轮真的造出了新提交吗。
         *
         * <p>没有改动时两个 sha 相同 —— 那时"这一轮引入的改动"是**空**，
         * 而不是"这个提交引入的改动"（那是上一轮的东西）。
         */
        boolean created() {
            return !sha.equals(previousHead);
        }
    }

}
