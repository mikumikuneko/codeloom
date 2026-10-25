package com.codeloom.domain.port;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.project.ProjectInvitation;

import java.util.List;
import java.util.Optional;

/**
 * 邀请链接的读写。实现在 {@code codeloom-workspace}（和项目同一张库、同一个模块）。
 *
 * <p>支持按 {@code projectId} 查：界面上要显示「这个项目还有哪些邀请尚未使用」——
 * 它在谁手里、还能不能用，得让人看得见、能撤销。看不见的凭据最危险：你忘了发过，
 * 而它还在有效期内。见
 * {@code docs/decisions/architecture/2026-09-29-invite-links-not-username-invites.md}。
 */
public interface ProjectInvitationRepository {

    /**
     * 新建或整体更新。接受、撤销都是「更新那一行」。
     *
     * @param invitation 要保存的邀请
     */
    void save(ProjectInvitation invitation);

    /**
     * 按凭据查。不存在时返回空；已经作废的行照样返回 —— 调用方需要区分这两者。
     *
     * @param token 邀请凭据
     * @return 原始邀请行；不存在时为空
     */
    Optional<ProjectInvitation> findByToken(String token);

    /**
     * 加锁读一张邀请，给「读到之后要改它」的调用方用。
     *
     * <p>普通查询在 REPEATABLE READ 下读到的是事务开始时的快照，即使之后拿到了项目锁，
     * 再用普通查询重读仍然看到旧值。这里读最新已提交版本，并把这一行锁到事务结束。
     * 调用方必须已经在事务里，并且先拿项目锁：本表的写者都按「项目 → 邀请」的顺序
     * 加锁，反过来会和 {@code issue} 撞出死锁。见
     * {@code docs/decisions/bug-fix/2026-10-19-lock-then-reread-before-writing-back.md}。
     *
     * @param token 邀请凭据
     * @return 最新已提交的邀请行；不存在时为空
     */
    Optional<ProjectInvitation> findByTokenForUpdate(String token);

    /**
     * 这个项目下还没被接受的邀请，新的在前。
     *
     * <p>只过滤「已被接受」：已过期的和已撤销的仍返回，由调用方再按可用性过滤 ——
     * 见 {@code InvitationService.pending}，它只交出「现在还能用的」，因为作废的既不能
     * 操作也没有信息量。已接受的邀请已经兑现成成员身份，不再出现。
     *
     * @param projectId 项目
     * @return 待处理邀请，按创建时间倒序
     */
    List<ProjectInvitation> findByProject(ProjectId projectId);

    /**
     * 删掉这个项目的全部邀请（没用掉的、过期的、撤销的一起）—— 「删除项目」的一步。
     *
     * <p>整批删和上面「连作废的也查得出来」不矛盾：那条是读，服务的是「这张凭据怎么
     * 回事」；项目都没了，任何凭据都不该再换得到成员身份，留着反而多一个风险 —— 一张
     * 仍在有效期内的邀请仍能兑现，而项目已经不存在。
     *
     * @param projectId 项目
     */
    void deleteByProject(ProjectId projectId);
}
