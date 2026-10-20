package com.codeloom.domain.port;

import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import java.util.List;
import java.util.Optional;

/** 项目仓储。实现在 {@code codeloom-workspace}（项目就是一个 git 仓库）。 */
public interface ProjectRepository {

    /**
     * 新建或整体更新项目行，连同它的成员行。
     *
     * @param project 项目聚合
     */
    void save(Project project);

    /**
     * 按 id 读项目聚合（项目行 + 成员行）；不存在就是空。
     *
     * @param id 项目
     * @return 项目
     */
    Optional<Project> findById(ProjectId id);

    /**
     * 把这一行连同它的成员行一起抹掉。
     *
     * <p>它只在一条路上被调用：最后一个人退出。项目没有「删除」这个动作，它是所有成员
     * 都退出后的结果 —— 成员一个个退出走的是 {@link #save}（少一个成员），最后那个人
     * 退出时，这一行被删掉，仓库、工作区、对话、聊天也随该流程一起消失。见
     * {@code docs/decisions/architecture/2026-09-29-project-departure-not-tombstones.md}。
     *
     * @param id 项目
     */
    void delete(ProjectId id);

    /**
     * 某人参与的项目，分页。用于「我的项目」列表。
     *
     * <p>必须分页：参与的项目数量没有上限，而接口一打开页面就要拉；不分页的话，某个
     * 用户攒够几千个项目之后，那一次请求会扫出整张表、全部序列化、全部塞给浏览器。
     * 用 {@code LIMIT/OFFSET} 而不是游标：排序键是 {@code (name, id)}，不是自增序号，
     * 而这是低频、小规模的列表；事件流和聊天记录用游标，因为那两个只追加且持续增长。
     * 退出的项目不在单子上：退出做的是删掉他那一行成员关系，自然不在 JOIN 结果里；
     * 没有另一种「还在但看不见」的状态。见
     * {@code docs/decisions/architecture/2026-09-29-pagination-by-offset.md}。
     *
     * @param userId 用户
     * @param limit  最多返回多少条，必须为正
     * @param offset 跳过多少条，非负
     * @return 项目列表
     */
    List<Project> findByMember(UserId userId, int limit, int offset);

    /**
     * 在项目上取得独占权，给会改动共享主干的操作（合并）用。它一直有效到调用方的
     * 工作单元结束，之后自动释放。
     *
     * <p>需要一把跟会话无关的锁：执行租约锁的是一棵树（用户 × 项目），而合并改的是主干
     * 工作区 —— 所有会话共享的一处。两条会话同时往主干合（或一个人点了两次），git 的
     * 合并状态（{@code MERGE_HEAD}、索引里的冲突标记）会相互覆盖，留下说不清的中间
     * 状态。调用方必须在一个工作单元（本项目里是事务）里调它 —— 寿命由外面那个边界
     * 决定；不在边界里调，独占权会在语句结束时消失，什么也没保住，而看起来完全正常。
     * 落在项目行上而不是带 TTL 的分布式锁：这一处天然要跨实例，合并又是用户点一下的
     * 低频操作，用存储里已有的行做互斥不必多维护一套过期与续期。代价是操作期间那一行
     * 被占着（几秒量级）。实现手段可以替换，端口只要求「这段时间里没有第二个写者」。
     * 见 {@code docs/decisions/architecture/2026-09-25-merge-model-without-pull-requests.md}。
     *
     * @param id 项目
     * @throws IllegalStateException 项目不存在
     */
    void lock(ProjectId id);

    /**
     * 加锁并读出项目当前的完整聚合（项目行 + 成员行）。
     *
     * <p>{@link #lock} 只保证「接下来没有第二个写者」，不会让调用方手里的旧快照变新。
     * 一个事务里先普通读过项目、再 lock、然后再普通查询，REPEATABLE READ 会让后面的
     * 查询继续看到旧值 —— 基于旧成员集合算出的改动，会把别人刚加进来的人或刚退掉的
     * 成员写回去。这个方法用锁定读同时读项目行和成员行，返回拿到锁之后的状态；给
     * 「读到成员集合之后要做决定」的调用方用（接受邀请、退出项目）。和 {@link #lock}
     * 一样，调用方必须已经在事务里。见
     * {@code docs/decisions/bug-fix/2026-10-19-lock-then-reread-before-writing-back.md}。
     *
     * @param id 项目
     * @return 拿到锁之后的项目聚合；项目不存在时为空
     */
    Optional<Project> lockAndFind(ProjectId id);
}
