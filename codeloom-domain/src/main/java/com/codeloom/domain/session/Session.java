package com.codeloom.domain.session;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;
import java.util.Objects;

/**
 * 会话聚合根。**不可变** —— 每次变更返回新实例。
 *
 * <p>选择不可变而不是 {@code setState()} 之类的写法，是为了和事件溯源的气质一致：
 * 状态变化在系统里本来就是一个"新事实"，而不是某个对象被就地改掉。
 *
 * <h2>它没有分支、没有 worktree 路径、没有 HEAD</h2>
 * 那三样是**工作区**的属性，而工作区挂在「用户 × 项目」上（见 {@link WorkspaceId}）：
 * 同一个人在这个项目里的所有会话共用一棵树。所以一条会话"在哪儿写代码"这件事，
 * 由 {@link #workspaceId()} 推出来即可，不需要（也不该）在每个会话行上再抄一份 ——
 * 抄了就有两份状态，而两份状态迟早会有一份是旧的。
 *
 * <p>推论是：**换会话不换代码**。开一段新对话不会看不到上一轮的改动，
 * 也不会出现"同一个人的两条会话线要互相合并"这种别扭的事。
 *
 * <h2>关于冗余字段</h2>
 * {@code state} 和 {@code turnIndex} 都能从事件流推出来，这里是**冗余存储**。理由是不冗余的话，
 * 每次判断「这条会话能不能执行」都得把事件流从头放一遍。
 *
 * <p>冗余的代价是必须同步：**每追加一条 {@link com.codeloom.domain.event.SessionStateChanged}，
 * 都要在同一次事务里保存这个聚合**，否则两者会对不上。这个约束由 {@code codeloom-app}
 * 里的执行器负责（它同时拿着 EventStore、SessionRepository 和租约，是唯一能在一个事务里
 * 办完这两件事的地方），不在本类的能力范围内 —— 这是不可变聚合根换来的好处与代价。
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

    /** 新建一条会话：状态 IDLE、turnIndex 0。 */
    public static Session create(SessionId id, ProjectId projectId, UserId ownerId,
                                 ModelConfig model) {
        return new Session(id, projectId, ownerId, SessionState.IDLE, 0, model);
    }

    /**
     * 这条会话在**哪棵工作区**里跑。
     *
     * <p>推导而不是存储：会话的拥有者和项目都是不可变的，所以这棵树的身份从建会话那一刻起
     * 就定死了。做成一个方法而不是让每个调用方自己拼 {@code ownerId + projectId}，
     * 是因为拼错了不会报错 —— 只会让两条会话落到两棵树上，而那看起来一切正常。
     */
    public WorkspaceId workspaceId() {
        return WorkspaceId.of(ownerId, projectId);
    }

    /**
     * 推进状态机。非法迁移会在这里抛 {@link IllegalStateTransitionException} ——
     * 聚合根自己守住不变量，调用方绕不过去。
     */
    public Session withState(SessionState next) {
        return new Session(id, projectId, ownerId, state.transitionTo(next), turnIndex, model);
    }

    /**
     * 换模型。
     *
     * <p>没有像 {@link #withState} 那样的校验 —— 换模型没有"合法不合法"，
     * 只有"从什么换成什么"。唯一该留意的是别换成它自己，而那由调用方判断：
     * 换成同一个模型是空操作，不值得落一条事件。
     *
     * <p>**它不推进状态机**：换靶只影响下一轮用谁，而会话该停在哪还停在哪。
     */
    public Session withModel(ModelConfig model) {
        return new Session(id, projectId, ownerId, state, turnIndex, model);
    }

    /** 进入下一轮对话。 */
    public Session nextTurn() {
        return new Session(id, projectId, ownerId, state, turnIndex + 1, model);
    }

    /**
     * 把轮次号拨回去，供**回滚**使用。
     *
     * <p>为什么回滚要连它一起改：{@code turnIndex} 存在的理由就是把"代码在哪个位置"和
     * "对话在哪个位置"对上，而回滚恰恰是同时退这两样。只 reset 代码、留着轮次号，
     * 那个对齐关系就断了 —— 而它断了之后不会报错，只会让 checkpoint 与对话的对应
     * 悄悄错位，等到某天按 turn 找代码时才发现。
     */
    public Session withTurnIndex(int turnIndex) {
        if (turnIndex < 0) {
            throw new IllegalArgumentException("turnIndex 不能为负，收到 " + turnIndex);
        }
        return new Session(id, projectId, ownerId, state, turnIndex, model);
    }
}
