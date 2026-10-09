package com.codeloom.domain.project;

import com.codeloom.domain.user.UserId;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * 一张**邀请链接**：拿着它的人点一下就能加入这个项目。
 *
 * <h2>为什么是链接，而不是"输入对方账号直接加"</h2>
 * 那样邀请人得先问出对方的账号，而账号是注册时自己起的、对方多半记不住；一次协作就被
 * 逼成"你先去注册，再把账号发我"。链接没有这一步：**谁能拿到链接，谁就是被邀请的人**。
 *
 * <h2>{@code token} 不是 UUID，这一点是刻意的</h2>
 * 它是一张**能直接换到项目权限**的凭据 —— 拿到它就等于拿到成员身份。
 * {@code UUID.randomUUID()} 只有 122 位随机，而这里用 32 字节（256 位）的
 * {@link SecureRandom}，编码成 URL 安全的文本。
 *
 * <p>长一点没有代价：它是给人复制粘贴的，不是给人念出来的。
 *
 * <h2>"还能不能用"是一个判断，不是三个字段</h2>
 * 见 {@link #isUsableAt}：被接受、被撤销、过期，三种作废各有各的原因，
 * 但对"能不能用"这个问题来说答案是同一个。把那条判断收在一个方法里，
 * 好过让每个调用方自己拼 {@code if} —— 拼漏一个就是一张不该生效的链接。
 *
 * @param token      邀请凭据。**它就是那张链接里唯一有信息量的部分**
 * @param projectId  邀请进哪个项目
 * @param createdBy  谁生成的（审计用：出问题时要知道是谁把链接放出去了）
 * @param createdAt  生成时间
 * @param expiresAt  过期时间。过了就作废，不管有没有人用过
 * @param acceptedBy 谁接受了；{@code null} 表示还没被接受
 * @param acceptedAt 接受时间；{@code null} 表示还没被接受
 * @param revokedAt  被撤销的时间；{@code null} 表示没被撤销
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
     * 链接默认活多久。
     *
     * <p>24 小时：够对方看到消息、切个账号、注册完再点回来。它反正是**一次性**的凭据 ——
     * 过期时间兜的只是"发出去没人用"的那部分，所以不该短到"隔天想起来就失效"。
     */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * 凭据的长度（编码之后）：32 字节按 base64 无填充编码正好 43 个字符
     * （30 字节出 40 个，剩下 2 字节出 3 个）。
     *
     * <p>它在这儿的用处是**构造器里的一道不变量**：长度不对的凭据在**造出来的那一刻**就炸，
     * 而不是变成一张"看着像链接、却永远匹配不上任何一行"的邀请。
     *
     * <p>长度本身要紧，是因为库那边是 {@code CHAR(43)}：短了会静默截断，
     * 而截断的表现是"部分链接能用、部分不能用"，那是最难查的一类。
     *
     * <p><b>这个数有三份，而且没有人替你对齐</b>：这里、{@code sql/schema.sql} 里的
     * {@code CHAR(43)}、以及测试里那串手敲的假凭据。改这里**不会**改列宽，列宽变了这个
     * 也不会有反应 —— 改的时候三个一起改。
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
        // 接受和撤销要么都有时间、要么都没有 —— 只有一个的中间状态是坏数据
        if ((acceptedBy == null) != (acceptedAt == null)) {
            throw new IllegalArgumentException("接受人和接受时间必须同时有值或同时为空");
        }
    }

    /** 新生成一张要发出去的邀请。 */
    public static ProjectInvitation issue(ProjectId projectId, UserId createdBy, Instant now) {
        return issue(projectId, createdBy, now, DEFAULT_TTL);
    }

    public static ProjectInvitation issue(ProjectId projectId, UserId createdBy, Instant now,
                                          Duration ttl) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("邀请的有效期必须是正数，收到 " + ttl);
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        // 无填充的 URL 安全编码：它要放进 URL 路径里，`+`、`/`、`=` 三个字符
        // 分别会在路径、查询串、以及某些客户端上出问题，一个都不留
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new ProjectInvitation(token, projectId, createdBy, now, now.plus(ttl),
                null, null, null);
    }

    /**
     * 这张邀请现在能不能用。
     *
     * <p>三个作废原因（已被接受、已撤销、已过期）各有各的说法，但对这个问题的答案是一样的。
     * 注意**过期用的是调用方给的"现在"**，不是 {@code Instant.now()} ——
     * 时间从外面传进来，这个方法才是可测的、而且一次请求里用的是同一个时刻。
     */
    public boolean isUsableAt(Instant now) {
        return acceptedBy == null && revokedAt == null && now.isBefore(expiresAt);
    }

    /** 记下"被谁接受了"。调用方要先确认 {@link #isUsableAt}。 */
    public ProjectInvitation acceptedBy(UserId userId, Instant at) {
        return new ProjectInvitation(token, projectId, createdBy, createdAt, expiresAt,
                userId, at, revokedAt);
    }

    /** 撤销。已经接受的邀请撤销不了（那个人已经是成员了，要移出得走别的路）。 */
    public ProjectInvitation revokedAt(Instant at) {
        if (acceptedBy != null) {
            throw new IllegalStateException("这张邀请已经被接受了，撤销它没有意义");
        }
        return new ProjectInvitation(token, projectId, createdBy, createdAt, expiresAt,
                null, null, at);
    }
}
