package com.codeloom.domain.port;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;

import java.util.List;
import java.util.Optional;

/**
 * 会话仓储。实现在 {@code codeloom-workspace}。
 *
 * <p>为什么会话归 workspace 而不是 agent：会话里最关键的字段（worktree 路径、分支名、
 * head commit）本身就是工作区的东西，而**会话状态和工作区状态必须在同一次事务里更新**
 * （打完 commit 要同时记下 head_commit），住得越近越好。
 *
 * <p>另外这也保住了「agent 是纯逻辑模块、不碰数据库」这条 —— 于是它能被纯单测覆盖。
 */
public interface SessionRepository {

    /** 新建或整体更新。注意：更新会话状态时必须与追加 {@code SessionStateChanged} 事件同事务。 */
    void save(Session session);

    Optional<Session> findById(SessionId id);

    /**
     * 某个项目下的会话，**分页**。排序键是 {@code id}（稳定，不会随写入变动）。
     *
     * <p>分页的理由和 {@link ProjectRepository#findByMember} 一样：列表接口没有上限，
     * 而它是一打开页面就要拉的。用 {@code LIMIT/OFFSET} 而非游标也见那一条。
     *
     * @param limit  最多返回多少条，必须为正
     * @param offset 跳过多少条，非负
     */
    List<Session> findByProject(ProjectId projectId, int limit, int offset);

    /**
     * 全部会话 —— 崩溃恢复的入口。
     *
     * <p>不过滤状态：状态机里没有终态（见 {@code SessionState}），所以
     * "可能需要恢复的"就是"全部"；恢复逻辑自己按状态和未完成的工具调用来判断，
     * 一条空闲的会话对它来说是空操作。
     *
     * <p>进程重启后扫描它，把「上次执行到一半的工具调用」补写成
     * {@code ToolInterrupted} 事件，然后从断点续跑。**注意是续跑不是重跑**：
     * 已产生副作用的调用不能重新执行，否则会造成二次修改。
     *
     * <p>租约过期本身就是"实例已死"的检测，所以这里不需要额外的心跳存活判断。
     */
    List<Session> findAll();

    /**
     * 把这条会话那一行删掉 —— 「丢弃这条对话」。
     *
     * <p><strong>它只是这件事的三分之一</strong>：一条会话还留下了事件流和幂等键，
     * 而它们分别归另外两个端口。三个一起删才是"丢弃"，所以别单独调它 ——
     * 走 {@code SessionService.discard}，那里把三件事收在一个事务里，
     * 而且先确认了这条会话没有在跑。
     *
     * <p><strong>工作区不在这里，也不该在这里</strong>：树属于「人 + 项目」
     * （见 {@code WorkspaceId}），丢掉一条对话不该让代码消失。
     */
    void discard(SessionId id);

    /**
     * 把**这个人在这个项目里**所有会话的那一行删掉 —— 「退出项目」的一步。
     *
     * <p>它和 {@link #discard} 是同一件事换了个范围，上面那两段说明在这里同样成立。
     * 同样地，"最后一个人退出"也走这一条（那时候他的会话就是全部）。
     *
     * <p><strong>必须跑在所有"靠会话定位"的删除之后</strong>：事件和幂等键都是先按
     * {@code session} 查出属于这个人的行再删的，会话行没了它们就一条都匹配不上 ——
     * 而 DELETE 影响 0 行不会报错。顺序收在应用层的 {@code ProjectDeparture} 里。
     */
    void discardByProjectAndOwner(ProjectId projectId, UserId ownerId);
}
