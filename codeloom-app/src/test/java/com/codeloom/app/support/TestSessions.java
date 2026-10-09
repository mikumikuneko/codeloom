package com.codeloom.app.support;

import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.port.WorkspaceFence;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import com.codeloom.workspace.LocalWorkspaceManager;

import java.nio.file.Path;

/**
 * 落一条真会话**和它那棵树**。**几乎每个连库的测试都需要先有它们**。
 *
 * <h2>为什么是两行，不是一行</h2>
 * 代码位置（分支、worktree 路径、HEAD）挂在**工作区**上，而 fencing token 也是对工作区
 * 发号的（见 {@code MyBatisWorkspaceFence#issue}）—— 所以事件必须先有号才能写。
 * 只落会话不落树的话，后面任何一次写入都会以"工作区不存在，无法发号"收场。
 *
 * <p>id 由调用方给：需要 workspace 路径的测试要拿它去拼目录，所以不能在方法里生成。
 */
public final class TestSessions {

    /**
     * 落库时用的默认模型配置。需要别的值（比如空提示词、另一家）的测试自己构造，
     * 但**默认值只写这一处**（曾散在四个测试里）。
     */
    public static final ModelConfig DEFAULT_MODEL = new ModelConfig(
            ProviderId.of("deepseek"), "deepseek-chat", "你是协作开发助手。");

    private TestSessions() {
    }

    /** 落一条会话；它那棵树记在 {@code worktreePath}，HEAD 为空（项目还是空目录起步的状态）。 */
    public static Session persist(SessionRepository sessions, WorkspaceRepository worktrees,
                                  SessionId id, ProjectId projectId, UserId owner,
                                  String worktreePath) {
        return persist(sessions, worktrees, id, projectId, owner, worktreePath, null);
    }

    /**
     * 同上，但显式给一个 baseCommit —— 需要"这棵树已经落在一个具体提交上"的测试用它
     * （比如检查点、回滚那几组）。
     */
    public static Session persist(SessionRepository sessions, WorkspaceRepository worktrees,
                                  SessionId id, ProjectId projectId, UserId owner,
                                  String worktreePath, String baseCommit) {
        Session session = Session.create(id, projectId, owner, DEFAULT_MODEL);
        sessions.save(session);
        worktrees.save(treeOf(session, worktreePath, baseCommit));
        return session;
    }

    /**
     * 这条会话所在的那棵树 —— 分支名走 {@link LocalWorkspaceManager#branchName}，
     * 也就是**和真正建树时同一个函数**。在这里另写一遍字符串的话，
     * 改名之后测试用的就不是真的那条分支了。
     */
    public static Workspace treeOf(Session session, String worktreePath, String baseCommit) {
        return new Workspace(session.workspaceId(), Path.of(worktreePath),
                LocalWorkspaceManager.branchName(session.workspaceId()), baseCommit);
    }

    /**
     * 造一个测试用的 {@link LeaseToken}：拿这棵树发一个真号。
     *
     * <p>收在这一处，是因为"token 得同时带会话和树"这件事在测试里出现过十来次 ——
     * 每处各拼一遍的话，某处漏掉 {@code sessionId} 只会让
     * {@code MyBatisEventStore} 那道"拿 A 的 token 写 B"的校验变得**永远不触发**，
     * 而那不是测试失败，是那道校验在测试里被悄悄拆掉了。
     */
    public static LeaseToken mint(Session session, WorkspaceFence fence) {
        return new LeaseToken(session.id(), session.workspaceId(),
                fence.issue(session.workspaceId()), "test-instance");
    }
}
