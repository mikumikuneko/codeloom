package com.codeloom.app.workspace;

import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.domain.workspace.WorkspaceId;

/**
 * 取磁盘上的那棵树；找不到就是服务端状态错误，不是客户端传错了。
 *
 * <p>树在获得项目访问权或建会话时建好（见 {@link WorkspaceProvisioner#ensure}）。
 * 这里问的是 {@link WorkspaceManager}（磁盘），不是库里的行。几处入口共用这一份，
 * 免得提示文案和异常类型各写一遍。
 */
public final class WorkspaceLookup {

    private WorkspaceLookup() {
    }

    /**
     * @param workspaces 磁盘上的工作区
     * @param id         要取的那棵树
     * @return 工作区
     * @throws IllegalStateException 目录不在磁盘上（被手工删掉了）
     */
    public static Workspace requireOnDisk(WorkspaceManager workspaces, WorkspaceId id) {
        return workspaces.find(id).orElseThrow(() -> new IllegalStateException(
                "工作区 " + id + " 的目录不在磁盘上。它本该在获得项目访问权或建会话时建好 —— "
                        + "现在找不到，只可能是被手工删掉了。"));
    }
}
