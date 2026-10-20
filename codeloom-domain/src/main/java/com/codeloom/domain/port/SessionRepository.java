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
 * <p>会话归 workspace 而不是 agent：会话最关键的字段（worktree 路径、分支名、head
 * commit）本身就是工作区的东西，而会话状态和工作区状态必须在同一次事务里更新（打完
 * commit 要同时记下 head_commit），所以放在同一个模块里。这也保住了「agent 是纯逻辑
 * 模块、不碰数据库」，于是它能被纯单测覆盖。
 */
public interface SessionRepository {

    /**
     * 新建或整体更新。更新会话状态时必须与追加 {@code SessionStateChanged} 事件同事务。
     *
     * @param session 会话
     */
    void save(Session session);

    /**
     * 按 id 读会话；不存在就是空。
     *
     * @param id 会话
     * @return 会话
     */
    Optional<Session> findById(SessionId id);

    /**
     * 某个项目下的会话，分页。排序键是 {@code id}（稳定，不会随写入变动）。
     *
     * <p>分页的理由和 {@link ProjectRepository#findByMember} 一样：列表接口没有上限，
     * 而它是一打开页面就要拉的；用 {@code LIMIT/OFFSET} 而非游标也见那一条。
     *
     * @param projectId 项目
     * @param limit     最多返回多少条，必须为正
     * @param offset    跳过多少条，非负
     * @return 会话列表
     */
    List<Session> findByProject(ProjectId projectId, int limit, int offset);

    /**
     * 全部会话 —— 崩溃恢复的入口。
     *
     * <p>不过滤状态：状态机里没有终态（见 {@code SessionState}），所以「可能需要恢复的」
     * 就是「全部」；恢复逻辑自己按状态和未完成的工具调用判断，空闲会话对它来说是空操作。
     * 进程重启后扫描它，把上次执行到一半的工具调用补写成 {@code ToolInterrupted} 事件，
     * 然后停下，不自动续跑；后续由用户发话或批准答复触发。补写是为了防止重放：已产生
     * 副作用的调用不能重新执行，否则会造成二次修改。租约过期本身就是「实例已死」的检测，
     * 所以这里不需要
     * 额外的心跳存活判断。见
     * {@code docs/decisions/architecture/2026-09-28-crash-recovery-writes-facts-not-resumes.md}。
     *
     * @return 全部会话
     */
    List<Session> findAll();

    /**
     * 把这条会话那一行删掉 —— 「丢弃这条对话」。
     *
     * <p>它只是这件事的三分之一：一条会话还留下事件流和幂等键，分别归另外两个端口。
     * 三个一起删才是「丢弃」，别单独调它 —— 走 {@code SessionService.discard}，那里把
     * 三件事收在一个事务里，而且先确认了这条会话没有在跑。工作区不在这里，也不该在
     * 这里：树属于「人 + 项目」（见 {@code WorkspaceId}），丢掉一条对话不该让代码消失。
     * 见 {@code docs/decisions/architecture/2026-09-30-discard-really-deletes.md}。
     *
     * @param id 会话
     */
    void discard(SessionId id);

    /**
     * 把这个人在这个项目里所有会话的那一行删掉 —— 「退出项目」的一步。
     *
     * <p>和 {@link #discard} 是同一件事换个范围，上面的说明同样成立；「最后一个人退出」
     * 也走这一条（那时他的会话就是全部）。必须跑在所有靠会话定位的删除之后：事件和
     * 幂等键都是先按 {@code session} 查出属于这个人的行再删的，会话行没了它们就一条都
     * 匹配不上 —— 而 DELETE 影响 0 行不会报错。顺序收在应用层的
     * {@code ProjectDeparture} 里。见
     * {@code docs/decisions/architecture/2026-09-29-project-departure-not-tombstones.md}。
     *
     * @param projectId 项目
     * @param ownerId   要删掉其会话的人
     */
    void discardByProjectAndOwner(ProjectId projectId, UserId ownerId);
}
