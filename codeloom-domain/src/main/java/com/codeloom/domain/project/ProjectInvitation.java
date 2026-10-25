package com.codeloom.domain.project;

import com.codeloom.domain.user.UserId;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * 一张邀请链接：拿着它的人点一下就能加入这个项目。
 *
 * <p>用链接而不是"输入对方账号直接加"：账号是注册时自己起的，对方多半记不住；链接没有
 * 这一步，谁能拿到链接谁就是被邀请的人。
 *
 * <p>{@code token} 不是 UUID：它是能直接换到成员身份的凭据，这里用 32 字节
 * {@link SecureRandom}（256 位）做 URL 安全编码。"还能不能用"是一个判断、不是三个字段，
 * 统一由 {@link #isUsableAt} 回答，免得调用方各拼一遍 {@code if} 而漏掉一种作废原因。
 *
 * @param token      邀请凭据，就是链接里唯一有信息量的部分；见
 *                   {@code docs/decisions/architecture/2026-09-29-invite-links-not-username-invites.md}
 * @param projectId  邀请进哪个项目
 * @param createdBy  谁生成的（审计用：出问题时要知道是谁把链接放出去了）
 * @param createdAt  生成时间
 * @param expiresAt  过期时间；到达即作废，不管有没有人用过
 * @param acceptedBy 谁接受了；null 表示还没被接受
 * @param acceptedAt 接受时间；null 表示还没被接受
 * @param revokedAt  被撤销的时间；null 表示没被撤销
 */
public record ProjectInvitation(String token,
                                ProjectId projectId,
                                UserId createdBy,
                                Instant createdAt,
                                Instant expiresAt,
                                UserId acceptedBy,
                                Instant acceptedAt,
                                Instant revokedAt) {

    /**
     * 链接默认活多久。24 小时够对方看到消息、切账号、注册完再点回来；它是一次性凭据，
     * 过期时间只处理"发出去没人用"的那部分，不该短到隔天想起来就失效。
     */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * 凭据长度（编码后）：32 字节按 base64 无填充编码正好 43 个字符。
     *
     * <p>它是一道构造期不变量：长度不对的凭据当场失败，而不是变成一张"看着像链接、却匹配
     * 不上任何一行"的邀请。长度要紧是因为库里是 {@code CHAR(43)}：短了会静默截断，表现为
     * "部分链接能用、部分不能用"。这个数在 {@code docs/sql/schema.sql} 与这里各有一份，
     * 改的时候两处一起改。
     */
    public static final int TOKEN_LENGTH = 43;

    private static final SecureRandom RANDOM = new SecureRandom();

    public ProjectInvitation {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (token.length() != TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    "邀请凭据必须是 " + TOKEN_LENGTH + " 个字符，收到 " + token.length());
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("邀请的过期时间必须晚于生成时间");
        }
        // 接受人和接受时间要么都有、要么都没有——只有一个的中间状态是坏数据
        if ((acceptedBy == null) != (acceptedAt == null)) {
            throw new IllegalArgumentException("接受人和接受时间必须同时有值或同时为空");
        }
    }

    /**
     * 新生成一张要发出去的邀请，有效期为 {@link #DEFAULT_TTL}。
     *
     * @param projectId 邀请进哪个项目
     * @param createdBy 谁生成的
     * @param now       生成时刻；用它算过期时间
     * @return 一张尚未被接受、未被撤销的邀请
     */
    public static ProjectInvitation issue(ProjectId projectId, UserId createdBy, Instant now) {
        return issue(projectId, createdBy, now, DEFAULT_TTL);
    }

    /**
     * 新生成一张要发出去的邀请。
     *
     * @param projectId 邀请进哪个项目
     * @param createdBy 谁生成的
     * @param now       生成时刻；用它算过期时间
     * @param ttl       有效期，必须为正
     * @return 一张尚未被接受、未被撤销的邀请
     * @throws IllegalArgumentException 有效期为 0 或负数时
     */
    public static ProjectInvitation issue(ProjectId projectId, UserId createdBy, Instant now,
                                          Duration ttl) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("邀请的有效期必须是正数，收到 " + ttl);
        }
        // 两列都是 DATETIME(3)：生成时就截到毫秒，否则返回的邀请和之后查出来的时间不相等；
        // 与事件、聊天记录同一条规矩（见 MyBatisEventStore、MyBatisChatMessageRepository）。
        // expiresAt 也要截：ttl 带亚毫秒时（调用方可以传任意 Duration），只截 issuedAt
        // 会让过期时间落库后短一截，返回的邀请与查回来的不相等
        Instant issuedAt = now.truncatedTo(ChronoUnit.MILLIS);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        // 无填充的 URL 安全编码：它要放进 URL 路径里，+、/、= 三个字符
        // 分别会在路径、查询串、以及某些客户端上出问题，一个都不留
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = issuedAt.plus(ttl).truncatedTo(ChronoUnit.MILLIS);
        return new ProjectInvitation(token, projectId, createdBy, issuedAt, expiresAt,
                null, null, null);
    }

    /**
     * 这张邀请现在能不能用。三个作废原因（已被接受、已撤销、已过期）答案相同；过期用
     * 调用方给的"现在"而不是 {@code Instant.now()}，方法才可测，且一次请求里用同一时刻。
     *
     * @param now 判断所用的当前时刻
     * @return 还能用时为 true
     */
    public boolean isUsableAt(Instant now) {
        return unusableReasonAt(now).isEmpty();
    }

    /**
     * 这张邀请现在为什么不能用；还能用就是空。
     *
     * <p>"能不能用"与"为什么不能用"是同一件事的两面，判据只有这一个，{@link #isUsableAt}
     * 从它派生；各写一遍会得到"判定为不可用、却拼不出一句原因"。
     *
     * @param now 判断所用的当前时刻
     * @return 不能用的原因；能用时为空
     */
    public Optional<String> unusableReasonAt(Instant now) {
        if (revokedAt != null) {
            return Optional.of("这张邀请已经被撤销了");
        }
        if (acceptedBy != null) {
            return Optional.of("这张邀请已经被用过了 —— 邀请链接是一次性的");
        }
        if (!now.isBefore(expiresAt)) {
            return Optional.of("这张邀请已经过期了，让项目里的人重新发一张");
        }
        return Optional.empty();
    }

    /**
     * 记下被谁接受了。调用方要先确认 {@link #isUsableAt}。
     *
     * @param userId 接受者
     * @param at     接受时刻
     * @return 记录接受信息后的邀请
     */
    public ProjectInvitation acceptedBy(UserId userId, Instant at) {
        return new ProjectInvitation(token, projectId, createdBy, createdAt, expiresAt,
                userId, at, revokedAt);
    }

    /**
     * 撤销。已经接受的邀请撤销不了：那个人已经是成员了，要移出得走别的路。
     *
     * @param at 撤销时刻
     * @return 记录撤销时间后的邀请
     * @throws IllegalStateException 邀请已被接受时
     */
    public ProjectInvitation revokedAt(Instant at) {
        if (acceptedBy != null) {
            throw new IllegalStateException("这张邀请已经被接受了，撤销它没有意义");
        }
        return new ProjectInvitation(token, projectId, createdBy, createdAt, expiresAt,
                null, null, at);
    }
}
