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
 * 落一条真实会话及其工作区，几乎每个连库测试都需要先有这两者。
 *
 * <p>代码位置（分支、worktree 路径、HEAD）挂在工作区上，而 fencing token 也对工作区发号
 *（见 {@code MyBatisWorkspaceFence#issue}），事件必须先有号才能写；只落会话不落树，
 * 后续任何写入都会以"工作区不存在，无法发号"失败。
 *
 * <p>id 由调用方提供：需要 workspace 路径的测试要用它拼目录。
 */
public final class TestSessions {

    /**
     * 落库用的默认模型配置。需要其他值（空提示词、其他供应商）的测试自行构造，
     * 默认值只保留这一处。
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
     * 这条会话所在的那棵树。分支名走 {@link LocalWorkspaceManager#branchName}，
     * 与真正建树使用同一函数；另写字符串会在改名后指向不存在的分支。
     */
    public static Workspace treeOf(Session session, String worktreePath, String baseCommit) {
        return new Workspace(session.workspaceId(), Path.of(worktreePath),
                LocalWorkspaceManager.branchName(session.workspaceId()), baseCommit);
    }

    /**
     * 造一个测试用 {@link LeaseToken}，对工作区发一个真号。
     *
     * <p>集中一处，因为"token 必须同时带会话和树"在测试里反复出现；各处自行拼装时若漏掉
     * {@code sessionId}，{@code MyBatisEventStore} 中"拿 A 的 token 写 B"的校验就永远不会
     * 触发 —— 这不是测试失败，而是校验在测试里被悄悄拆掉了。
     */
    public static LeaseToken mint(Session session, WorkspaceFence fence) {
        return new LeaseToken(session.id(), session.workspaceId(),
                fence.issue(session.workspaceId()), "test-instance");
    }
}
