package com.codeloom.app.persistence;

import com.codeloom.app.support.AbstractPersistenceTest;
import com.codeloom.domain.port.ProjectInvitationRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.project.ProjectInvitation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code project_invitation} 表的真库测试。
 *
 * <p>重点不是"写进去读出来还是同一个对象"（那是 Row 转换的事），而是
 * **拿着旧快照的 {@code save} 不能把已经发生的状态改回去**：
 * 撤销不能被写回 {@code NULL}，接受不能被撤销或被第二个接受者替换。
 * 服务层用项目锁串行化这些写入；这里的条件更新是最后一道防线。
 */
class ProjectInvitationPersistenceTest extends AbstractPersistenceTest {

    /** 每个测试一张邀请，项目行不用真的存在 —— 这张表没有外键。 */
    private static final ProjectId PROJECT_ID = ProjectId.generate();

    @Autowired
    private ProjectInvitationRepository invitations;

    @Test
    @DisplayName("【锁定读】findByTokenForUpdate 在事务里读得到最新一行")
    void lockingReadReturnsTheCurrentRow() {
        ProjectInvitation open = ProjectInvitation.issue(PROJECT_ID, OWNER, now());
        invitations.save(open);

        // MANDATORY 事务由 AbstractPersistenceTest 提供；这里验的是
        // SELECT ... FOR UPDATE 真的能跑，且列别名仍能装配成 record
        assertThat(invitations.findByTokenForUpdate(open.token())).contains(open);
    }

    @Test
    @DisplayName("【撤销】旧快照的 save 不能把 revoked_at 写回 NULL")
    void aStaleOpenRowCannotReviveARevokedInvitation() {
        Instant issuedAt = now();
        ProjectInvitation open = ProjectInvitation.issue(PROJECT_ID, OWNER, issuedAt);
        invitations.save(open);

        ProjectInvitation revoked = open.revokedAt(issuedAt.plusSeconds(1));
        invitations.save(revoked);

        // 模拟 accept 在撤销提交之前读到的那份旧快照：它的 revokedAt 还是 NULL
        invitations.save(open);

        ProjectInvitation reloaded = invitations.findByToken(open.token()).orElseThrow();
        assertThat(reloaded.revokedAt()).isEqualTo(revoked.revokedAt());
        assertThat(reloaded.acceptedBy()).isNull();
    }

    @Test
    @DisplayName("【接受】第一个接受者定下来，旧快照和后来的接受者都改不了")
    void aStaleOpenRowCannotUndoOrReplaceAcceptance() {
        Instant issuedAt = now();
        ProjectInvitation open = ProjectInvitation.issue(PROJECT_ID, OWNER, issuedAt);
        invitations.save(open);

        ProjectInvitation accepted = open.acceptedBy(ALICE, issuedAt.plusSeconds(1));
        invitations.save(accepted);

        // 一份旧快照，和另一份"另一个用户接受了"的写入
        invitations.save(open);
        invitations.save(open.acceptedBy(BOB, issuedAt.plusSeconds(2)));

        ProjectInvitation reloaded = invitations.findByToken(open.token()).orElseThrow();
        assertThat(reloaded.acceptedBy()).isEqualTo(ALICE);
        assertThat(reloaded.acceptedAt()).isEqualTo(accepted.acceptedAt());
        assertThat(reloaded.revokedAt()).isNull();
    }

    @Test
    @DisplayName("【互斥】被撤销之后，旧快照也不能把它改成已接受")
    void aRevokedInvitationCannotBeAcceptedByAStaleWriter() {
        Instant issuedAt = now();
        ProjectInvitation open = ProjectInvitation.issue(PROJECT_ID, OWNER, issuedAt);
        invitations.save(open);

        ProjectInvitation revoked = open.revokedAt(issuedAt.plusSeconds(1));
        invitations.save(revoked);

        invitations.save(open.acceptedBy(ALICE, issuedAt.plusSeconds(2)));

        ProjectInvitation reloaded = invitations.findByToken(open.token()).orElseThrow();
        assertThat(reloaded.revokedAt()).isEqualTo(revoked.revokedAt());
        assertThat(reloaded.acceptedBy()).isNull();
        assertThat(reloaded.acceptedAt()).isNull();
    }

    /** 两列都是 {@code DATETIME(3)}：比较前先截到毫秒，免得纳秒被库吃掉后测试假红。 */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
