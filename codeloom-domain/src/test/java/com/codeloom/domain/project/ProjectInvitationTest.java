package com.codeloom.domain.project;

import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectInvitationTest {

    private static final ProjectId PROJECT =
            ProjectId.of("11111111-1111-1111-1111-111111111111");
    private static final UserId OWNER =
            UserId.of("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("生成时就截到毫秒 —— 创建响应和之后查回来的必须是同一张邀请")
    void issuedAtIsTruncatedToMillis() {
        // 两列都是 DATETIME(3)：不在生成时截，返回的邀请和之后查出来的会差几微秒；
        // 与事件、聊天记录同一条规矩（见 MyBatisEventStore）。
        Instant withNanos = Instant.parse("2026-09-25T10:00:00.123456789Z");

        ProjectInvitation invitation = ProjectInvitation.issue(PROJECT, OWNER, withNanos);

        Instant truncated = Instant.parse("2026-09-25T10:00:00.123Z");
        assertThat(invitation.createdAt()).isEqualTo(truncated);
        assertThat(invitation.expiresAt()).isEqualTo(truncated.plus(ProjectInvitation.DEFAULT_TTL));
    }
}
