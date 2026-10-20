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
 * 一轮的工作区：取到它，然后提交它。
 *
 * <p>两件事同属一个类，因为共用"这条会话的树在哪"这一前提，且提交署名必须来自会话所有者
 * （见 {@link #commit}），拆开会给身份来源第二个说法。执行器只需要一个能跑的工作区和一笔
 * 提交，不必知道 worktree 如何创建、署名如何解析。
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
     * 取这条会话那棵树的工作区；没有就按库里的记录重建。
     *
     * <p>常见路径（目录仍在）不碰库，一次 {@code git rev-parse} 即返回。走到重建意味着目录被
     * 人从磁盘删掉，此时仍要按记录的位置重建；不带 {@code baseCommit} 会停在主干上，
     * 静默把这棵树的进度退回去。
     *
     * @param session 会话
     * @param project 会话所属项目
     * @return 可运行的工作区
     * @throws IllegalStateException 工作区既不在磁盘上也不在库里
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
     * 把本轮在工作区里的改动提交成一个 commit。
     *
     * <p>必须提交：不提交时 agent 写出的文件对 git 是未跟踪的，而合并只看分支上的提交，
     * {@code reset --hard} 和 diff 也不碰未跟踪文件 —— 事件流里的"我改了"与 git 里的"真的改了"
     * 之间缺一笔提交。提交点在一轮结束、收尾之前，正是所有下游都要用的时刻。
     *
     * <p>署名必须是会话所有者：同步会把主干的提交原样带进这棵树，同一条分支会混着两人的提交，
     * 署名是事后分辨归属的唯一依据。
     *
     * <p>同时交回提交前的 HEAD：没有改动时提交返回当前 HEAD 且不报错，"没动手"和"产出了代码"
     * 的 sha 相同；下游若拿同一个 sha 算 {@code SHA^!}，得到的是上一个提交的改动。
     * 由 {@link Commit#created()} 回答，调用方不必比较 sha 去猜。
     *
     * <p>{@code before} 用 {@link WorkspaceManager#find} 现读，不用传入的 {@code workspace}：
     * 库里那份在事务提交前不会跟着 git 动，拿它比较会一直判成造出了新提交。
     *
     * @param session   会话
     * @param workspace 本轮工作区
     * @return 提交位置及是否真的造出了新提交
     */
    Commit commit(Session session, Workspace workspace) {
        // 提交信息用正在跑的这一轮的号：会话行上的数表示已经跑完几轮
        //（见 CheckpointCreated），所以本轮是它加一。挂起与批准后的续跑各提交一次、号相同 ——
        // 它们本来就是同一轮
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
     * @param sha          本轮结束时这棵树的位置；没有改动时等于 {@code previousHead}
     * @param previousHead 提交之前现读到的 HEAD
     */
    record Commit(String sha, String previousHead) {

        /**
         * 本轮是否真的造出了新提交。两个 sha 相同时本轮引入的改动是空的，
         * 而不是该提交引入的改动（那是上一轮的内容）。
         *
         * @return sha 与提交前 HEAD 不同时为 true
         */
        boolean created() {
            return !sha.equals(previousHead);
        }
    }

}
