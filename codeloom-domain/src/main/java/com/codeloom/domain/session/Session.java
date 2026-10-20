package com.codeloom.domain.session;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;
import java.util.Objects;

/**
 * 会话聚合根，不可变——每次变更返回新实例，与事件溯源"状态变化是一条新事实"一致。
 *
 * <p>它不持有分支、worktree 路径或 HEAD：那三样属于工作区，而工作区挂在「用户 × 项目」上
 * （见 {@link WorkspaceId}）。同一个人的所有会话共用一棵树，因此 {@link #workspaceId()} 从
 * ownerId 与 projectId 推导，不在会话行上另存一份。
 *
 * <p>{@code state} 和 {@code turnIndex} 是事件流的冗余投影，免得每次判断都重放事件流；每
 * 追加一条 {@link com.codeloom.domain.event.SessionStateChanged}，应用层执行器必须在同一事务里保存本聚合，否则两者对不上（这不在本类的能力范围内）。
 *
 * @param id        会话标识
 * @param projectId 所属项目
 * @param ownerId   会话所有者，工作区由它与项目共同确定
 * @param state     当前状态，是事件流的冗余投影
 * @param turnIndex 当前轮次，是事件流的冗余投影
 * @param model     本会话使用的模型配置
 */
public record Session(SessionId id,
                      ProjectId projectId,
                      UserId ownerId,
                      SessionState state,
                      int turnIndex,
                      ModelConfig model) {

    public Session {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(model, "model");
        if (turnIndex < 0) {
            throw new IllegalArgumentException("turnIndex 不能为负，收到 " + turnIndex);
        }
    }

    /**
     * 新建一条会话。
     *
     * @param id        会话标识
     * @param projectId 所属项目
     * @param ownerId   会话所有者
     * @param model     模型配置
     * @return 状态为 {@link SessionState#IDLE}、{@code turnIndex} 为 0 的会话
     */
    public static Session create(SessionId id, ProjectId projectId, UserId ownerId,
                                 ModelConfig model) {
        return new Session(id, projectId, ownerId, SessionState.IDLE, 0, model);
    }

    /**
     * 这条会话在哪棵工作区里跑。
     *
     * <p>推导而不是存储：会话的拥有者和项目都不可变，树的身份从建会话那一刻起就定死了。
     * 做成方法而不是让调用方自己拼 {@code ownerId + projectId}：拼错了不会报错，只会让
     * 两条会话落到两棵树上，而那看起来一切正常。
     *
     * @return 这个人与这个项目共有的工作区标识
     */
    public WorkspaceId workspaceId() {
        return WorkspaceId.of(ownerId, projectId);
    }

    /**
     * 推进状态机。聚合根自己守住不变量，调用方绕不过去。
     *
     * @param next 目标状态
     * @return 状态已迁移的新会话
     * @throws IllegalStateTransitionException 迁移不合法时
     */
    public Session withState(SessionState next) {
        return new Session(id, projectId, ownerId, state.transitionTo(next), turnIndex, model);
    }

    /**
     * 换模型。它不推进状态机，只影响下一轮用谁；换模型没有合法与否之分，因此不像
     * {@link #withState} 那样校验。调用方应避免换成同一个模型——那是空操作，不值得落事件。
     * 见 {@code docs/decisions/architecture/2026-09-28-switching-model-is-an-event.md}。
     *
     * @param model 新模型配置
     * @return 换了模型的新会话
     */
    public Session withModel(ModelConfig model) {
        return new Session(id, projectId, ownerId, state, turnIndex, model);
    }

    /**
     * 进入下一轮对话。
     *
     * @return 轮次号加一的新会话
     */
    public Session nextTurn() {
        return new Session(id, projectId, ownerId, state, turnIndex + 1, model);
    }

    /**
     * 把轮次号拨回去，供回滚使用。
     *
     * <p>{@code turnIndex} 让"代码在哪个位置"与"对话在哪个位置"对上，回滚同时退这两样；
     * 只 reset 代码、留着轮次号会让 checkpoint 与对话的对应悄悄错位，且不会报错。见
     * {@code docs/decisions/architecture/2026-09-29-rewind-the-whole-tree.md}。
     *
     * @param turnIndex 新轮次号，不能为负
     * @return 轮次号被改写的新会话
     * @throws IllegalArgumentException 轮次号为负时
     */
    public Session withTurnIndex(int turnIndex) {
        if (turnIndex < 0) {
            throw new IllegalArgumentException("turnIndex 不能为负，收到 " + turnIndex);
        }
        return new Session(id, projectId, ownerId, state, turnIndex, model);
    }
}
