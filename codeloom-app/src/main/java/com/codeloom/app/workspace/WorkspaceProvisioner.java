package com.codeloom.app.workspace;

import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 「确保这个人在这个项目里的那棵树存在」—— 只有这一个地方建树。
 *
 * <h2>它什么时候被调用</h2>
 * 在**一个人获得这个项目的访问权**的那两个时刻：
 * <ul>
 *   <li>他创建了项目；</li>
 *   <li>他接受了邀请。</li>
 * </ul>
 *
 * <h2>为什么不是"建会话的时候"</h2>
 * 建会话时才建树，前提是一个错误的模型：**要看到自己的代码，先得建一条会话**。
 * 现在的模型是「点进项目就是工作区，会话要等你发第一句话才有」—— 在那个模型下，
 * 一个刚打开项目的人需要的是**他的树**，而那和"他有没有会话"毫无关系。
 *
 * <p>症状很具体：页面上左边那棵树要读 {@code GET /projects/{id}/files}，
 * 而树还没建 —— 那里只会以 500 收场。
 *
 * <h2>为什么不做成"打开项目时顺手建"</h2>
 * 那需要让一个 {@code GET} 产生副作用。而且"他有没有树"这件事的答案，
 * 取决于"他有没有这个项目的访问权"，不取决于"他今天点进来没有"。
 *
 * <p>创建 worktree 要跑几条 git 命令（几百毫秒），所以它会让"创建项目"和"接受邀请"
 * 这两个请求慢一点。那是值得的：那两个动作本来就各只有一次。
 */
@Component
public class WorkspaceProvisioner {

    private final WorkspaceManager workspaces;
    private final WorkspaceRepository stored;

    public WorkspaceProvisioner(WorkspaceManager workspaces, WorkspaceRepository stored) {
        this.workspaces = workspaces;
        this.stored = stored;
    }

    /**
     * 确保这棵树存在，返回它。
     *
     * <p><strong>幂等</strong>：建过了就直接返回现状。所以调用方不需要先查一次 ——
     * 而"先查一次再决定建不建"正是会让两处判断走岔的那种写法。
     */
    public Workspace ensure(UserId owner, Project project) {
        WorkspaceId id = WorkspaceId.of(owner, project.id());
        Path repoPath = Path.of(project.repoPath());

        Optional<Workspace> recorded = stored.find(id);
        if (recorded.isEmpty()) {
            Workspace created = workspaces.create(id, repoPath, null);
            stored.save(created);
            return created;
        }
        // 库里有了。但目录可能被人从磁盘上删掉了 —— 那就按**记录里的位置**重建：
        // 不带那个位置的话，重建出来的树会停在主干上，而这棵树本来已经往前走了
        return workspaces.find(id).orElseGet(() ->
                workspaces.create(id, repoPath, recorded.get().headCommit()));
    }
}
