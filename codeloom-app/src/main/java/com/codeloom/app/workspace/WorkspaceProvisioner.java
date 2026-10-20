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
 * 确保一个人在一个项目里的那棵树存在。
 *
 * <p>不是唯一的建树入口：执行器那一侧在目录被人从磁盘删掉、但库里还有记录时，会按记录
 * 重建（见 {@code TurnWorkspace}）。这里负责的是获得项目访问权那一刻的创建与补齐。
 *
 * <p>调用时机是获得项目访问权的那一刻：创建项目、接受邀请。不在建会话时建，因为"点进项目
 * 就是工作区"，能否看代码与有没有会话无关；否则文件树接口会因为没有树而以 500 收场。
 * 也不在打开项目时顺手建：GET 不应产生副作用，而树是否存在取决于访问权，不取决于是否点进来。
 *
 * <p>创建 worktree 需要几条 git 命令（几百毫秒），只让创建项目和接受邀请这两个一次性动作
 * 变慢一点。
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
     * 确保这棵树存在并返回它。幂等：已建好时直接返回现状，调用方不需要先查一次 ——
     * "先查再决定建不建"正是会让两处判断走岔的写法。
     *
     * @param owner   项目成员
     * @param project 项目
     * @return 该成员在这棵树上的工作区
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
        // 库里有了，但目录可能被人从磁盘删掉；按记录里的位置重建。
        // 不带位置会停在主干上，把这棵树的进度静默退回去
        return workspaces.find(id).orElseGet(() ->
                workspaces.create(id, repoPath, recorded.get().headCommit()));
    }
}
