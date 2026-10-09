package com.codeloom.domain.port;

import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import java.util.List;
import java.util.Optional;

/** 项目仓储。实现在 {@code codeloom-workspace}（项目就是一个 git 仓库）。 */
public interface ProjectRepository {

    void save(Project project);

    Optional<Project> findById(ProjectId id);

    /**
     * 把这一行连同它的成员行一起抹掉。
     *
     * <h2>它只在一条路上被调用：最后一个人退出</h2>
     * 项目没有"删除"这个动作 —— 它是**人走空的副产品**。成员一个个退出时走的是
     * {@link #save}（少一个成员），而最后那个人退出时，这一行连同它指向的一切
     * （仓库、工作区、对话、聊天）一起消失。
     */
    void delete(ProjectId id);

    List<Project> findAll();

    /**
     * 某人参与的项目，**分页**。用于「我的项目」列表。
     *
     * <h2>为什么这个列表必须分页</h2>
     * 它没有上限：一个用户参与的项目的数量不受任何约束，而这个接口是"一打开页面就要拉"
     * 的那种。不分页的话，某个用户攒够几千个项目之后，那一次请求就会把整张表扫出来、
     * 全部序列化、全部塞给浏览器 —— 而**在他之前没有任何人会碰到这个路径**。
     *
     * <p>用 {@code LIMIT/OFFSET} 而不是游标：排序键是 {@code (name, id)}，不是自增序号，
     * 而"我的项目"是低频、小规模的列表 —— 游标能防的"翻页时插入导致漏读"在这里
     * 不值得为它换一套更绕的接口。事件流和聊天记录用的是游标，因为那两个是**只追加**
     * 且会持续增长的，情况不一样。
     *
     * <p>退出的项目**不在这张单子上**：退出做的是"删掉他那一行成员关系"，
     * 所以它自然就不在这条 JOIN 的结果里。没有另一种"还在但看不见"的状态。
     *
     * @param limit  最多返回多少条，必须为正
     * @param offset 跳过多少条，非负
     */
    List<Project> findByMember(UserId userId, int limit, int offset);

    /**
     * 在项目上取得**独占权**，给那些会改动共享主干的操作（合并）用。它一直有效到
     * 调用方的**工作单元**结束，之后自动释放。
     *
     * <h2>为什么合并需要一把跟会话无关的锁</h2>
     * 会话的执行租约保护的是**一条会话**，而合并改的是**主干工作区** ——
     * 那是所有会话共享的一处。两条会话同时往主干合（或者一个人点了两次），
     * git 的合并状态（{@code MERGE_HEAD}、索引里的冲突标记）会互相踩，
     * 留下一个谁也说不清的中间状态。
     *
     * <p><strong>调用方必须在一个工作单元（本项目里就是事务）里调它。</strong>
     * 这也是"独占权"这个说法比"锁"更准的原因：它的寿命**不是由这个调用决定的**，
     * 而是由外面那个边界决定的。不在边界里调的话，独占权会在语句结束时就没了，
     * 什么也没保住 —— <b>而那看起来完全正常</b>。
     *
     * <h2>为什么落在项目行上，而不是再来一把带 TTL 的分布式锁</h2>
     * 这一处天然要跨实例、而且合并是用户点一下的低频操作，所以拿存储里已有的
     * 那个行来做互斥是划算的：不用为它多维护一套锁的过期与续期。代价是这次操作
     * 期间那一行被占着（几秒量级）—— 拿它做互斥的都是"点一下"的低频操作。
     *
     * <p>（本项目的实现见 {@code MyBatisProjectRepository}：它用行级排它读来提供
     * 这个语义，并把"必须在工作单元里"变成运行时强制。这些是**实现的选择**，
     * 换一种存储可以换一种手段 —— 端口要的只是"这段时间里没有第二个写者"。）
     */
    void lock(ProjectId id);
}
