package com.codeloom.domain.port;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.project.ProjectInvitation;

import java.util.List;
import java.util.Optional;

/**
 * 邀请链接的读写。实现在 {@code codeloom-workspace}（和项目同一张库、同一个模块）。
 *
 * <h2>为什么按 {@code projectId} 也能查</h2>
 * 「这个项目还有哪些邀请在外面飘着」是界面上要显示的东西 —— 生成过一张，
 * 它在谁手里、还能不能用，得让人看得见、能撤销。看不见的凭据是最危险的那种：
 * 你忘了发过，而它还在有效期内。
 */
public interface ProjectInvitationRepository {

    /** 新建或整体更新。接受、撤销都是"更新那一行"。 */
    void save(ProjectInvitation invitation);

    /** 按凭据查。**不存在和作废都返回空**是不行的 —— 调用方要能区分，所以这里返回原始行，由它自己判断。 */
    Optional<ProjectInvitation> findByToken(String token);

    /**
     * 这个项目下的**邀请行**：还在外面飘的、以及已经作废的，都在里面。
     *
     * <p>它回答的是**数据**问题："这个项目产生过哪些邀请"。至于其中哪些该出现在界面上，
     * 由调用方决定 —— 见 {@code InvitationService.pending}：它只把"现在还能用的"交出去，
     * 因为作废的既不能操作也没有信息量。
     */
    List<ProjectInvitation> findByProject(ProjectId projectId);

    /**
     * 删掉这个项目的**全部**邀请（没用掉的、过期的、撤销的，一起）—— 「删除项目」的一步。
     *
     * <p>整批删和上面那条"连作废的也查得出来"不矛盾：那条是**读**，它服务的是
     * "这张凭据怎么回事"；而项目都没了，任何凭据都不该再换得到成员身份 ——
     * 留着它反而多一个风险：一张有效期内的邀请仍然能兑现，而那个项目已经不存在了。
     */
    void deleteByProject(ProjectId projectId);
}
