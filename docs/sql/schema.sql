-- ============================================================================
--  codeloom 表结构
--
--  表结构走手工执行，没有迁移工具 —— 为什么这么定、代价是什么，见
--  docs/decisions/process/2026-09-24-keep-the-dependency-list-short.md。
--  后果：加列要自己补 ALTER，这是明知的选择。
--
--  ---------------------------------------------------------------------------
--  表与模块的归属（和四个能力模块一一对应，便于讲清依赖方向）
--
--      user                        -> codeloom-app      （认证是 app 的职责）
--      project / project_member    -> codeloom-workspace（项目就是一个 git 仓库）
--      workspace                   -> codeloom-workspace（worktree/分支/HEAD 与 fencing 都在这一行）
--      session                     -> codeloom-workspace
--      event / chat_message        -> codeloom-realtime （同属「消息流」）
--
--  ---------------------------------------------------------------------------
--  两条贯穿全表的约定
--
--  1) 列与领域记录一一对应。
--     domain 的 record 就是权威，表里不额外加领域造不出来的列。原因见 Session.java
--     的类注释：冗余字段的代价是必须同步，而同步一旦漏掉，两份状态就对不上了。
--     唯一例外是 workspace.fencing_token，它不是领域状态而是并发控制的落点，见下方说明。
--
--  2) 不用外键，也不用级联。
--     遵循阿里巴巴 Java 开发手册的强制约定：一切外键概念在应用层解决。这里的理由是
--     外键会在在线 DDL（加列要锁子表）和将来分库分表时变成障碍，而它换来的完整性
--     检查，在「一切都走仓储、没有第二条写入路径」的前提下价值有限。
--
--  ---------------------------------------------------------------------------
--  关于时间列统一用 DATETIME(3)
--
--  DATETIME 不带时区，所以它和 Instant 之间的换算完全取决于 JDBC 连接参数。
--  application.yml 里已经钉死了 serverTimezone=Asia/Shanghai，和 Jackson 的
--  time-zone 一致，所以读写是确定的。毫秒精度是因为事件表里同一个会话可能
--  在同一毫秒内落多条事件，秒级精度排不出先后。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS codeloom
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE codeloom;


-- ---------------------------------------------------------------------------
--  user —— 平台用户
--
--  表名一律加反引号 —— 图的是全表一个写法，而不是"不加就解析不了"。实测（MySQL 8.0.46）：
--  不带反引号的 `CREATE TABLE user (…)` 和 `SELECT id FROM user` 都能过（user 不是保留字，
--  虽然它同时是个内置函数名，看起来容易误会，统一加更清楚）。
--
--  密码存哈希不存明文，但注意这不是「密钥」—— 它是不可逆的，所以允许出现在领域对象
--  里（User 只是特意重写了 toString() 不带它进日志）。真正的密钥（模型 API Key）
--  不在这张表，见下面 session 表的说明。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
    id            CHAR(36)     NOT NULL COMMENT 'UserId，UUID 在领域侧生成，不等存储分配',
    username      VARCHAR(64)  NOT NULL COMMENT '账号，全局唯一',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希（当前输出 60 字符，留余量）。不可逆，不是密钥',
    display_name  VARCHAR(64)  NOT NULL COMMENT '用户名（用户在界面上看到的那个名字）；领域构造器在空值时回落成账号',
    created_at    DATETIME(3)  NOT NULL COMMENT '注册时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    -- 用户名也唯一。它**不是身份**（事件里记的是 id，见 SessionRewound 的注释），
    -- 但两个人都叫「小明」时，"这句话是谁说的"在界面上根本答不了 —— 注册重名直接拒。
    -- 比对按列的排序规则（utf8mb4_0900_ai_ci）：大小写与重音不敏感，和账号那条一样
    UNIQUE KEY uk_user_display_name (display_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '平台用户；UserRepository 实现在 codeloom-app';


-- ---------------------------------------------------------------------------
--  project —— 项目 = 一个 git 仓库
--
--  ---------------------------------------------------------------------------
--  owner_id 和成员表并存，它们回答的不是同一个问题
--
--  owner_id 是「现在谁是房主」，project_member 是「现在谁在里面」。
--  把房主表达成"成员里的某一个"做不到 —— 成员是个集合，没有先后、没有主次，
--  而这两个都会变（有人退出）。
--
--  房主的唯一职责是**退出时把它交出去**：他还剩别人时转给那个人，他是最后一个时
--  项目跟着他一起消失。所以它必须是成员之一 —— 一个"房主不在成员里"的项目
--  会变成一个谁也接不了手的死结。那条不变量写在 Project 的紧凑构造器里。
--
--  ---------------------------------------------------------------------------
--  项目不会"被删除" —— 它是**人走空的副产品**
--
--  没有可以单独触发的删除动作，也没有墓碑：成员一个个退出（各自带走自己的会话、
--  工作区、磁盘上那棵树），最后一个人退出时，这一行连同它指向的一切才消失。
--
--  这样就不存在"我的一个动作把对方脚下的地抽走"—— 两个人里任何一个都只能
--  决定自己走不走。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS project (
    id        CHAR(36)      NOT NULL COMMENT 'ProjectId',
    owner_id  CHAR(36)      NOT NULL COMMENT '房主 UserId；不变量：他必须在 project_member 里',
    name      VARCHAR(128)  NOT NULL COMMENT '项目名；不加唯一约束，领域也没这个不变量',
    repo_path VARCHAR(1024) NOT NULL COMMENT '仓库在宿主机上的绝对路径',
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '项目；ProjectRepository 实现在 codeloom-workspace';


-- ---------------------------------------------------------------------------
--  project_invitation —— 邀请链接
--
--  一行 = 一张发出去的邀请。**token 是主键**，因为那张链接里唯一有信息量的部分就是它。
--
--  ---------------------------------------------------------------------------
--  为什么不是「输入对方账号直接加成员」
--
--  那个做法要求邀请人先知道对方的账号，而账号是注册时自己起的、对方多半记不住；
--  更别扭的是它把一次协作变成了"你先去注册，然后把账号发给我" ——
--  而真实场景是"我建了个项目，一起来"，中间那句话根本不该出现。
--
--  ---------------------------------------------------------------------------
--  为什么 token 不是 UUID
--
--  它是一张**能直接换到项目权限**的凭据：拿到它等于拿到成员身份。UUIDv4 只有 122 位随机，
--  而这里存的是 32 字节（256 位）SecureRandom 的 base64url 编码 —— 43 个字符。
--  列宽按 43 定，宽了浪费、窄了会静默截断（表现是"部分链接能用、部分不能用"）。
--
--  这个 43 在 Java 那边也有一份（ProjectInvitation.TOKEN_LENGTH，它拿去做构造期的长度校验）。
--  两边**没有人替你对齐** —— 改这里要记得改那边，反之亦然。
--
--  ---------------------------------------------------------------------------
--  「还能不能用」是三个列算出来的，不存成一个状态列
--
--  可用 = accepted_by IS NULL AND revoked_at IS NULL AND expires_at > now()
--
--  存一个 status 列的话，它和那三个时间戳就有了两个真相，而它们迟早会不一致
--  （比如过期的那一刻没有任何人去改 status）。判断收在
--  ProjectInvitation.isUsableAt 一处，算出来的东西就不该再存一份。
--
--  ---------------------------------------------------------------------------
--  过期的行【不删】
--
--  删了的话，"我明明发过一张链接"就没有了对证，而人遇到这种情况第一反应是
--  "是不是我没发出去"。留着它，界面上显示"已过期"是诚实的。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS project_invitation (
    token       CHAR(43)    NOT NULL COMMENT '邀请凭据，32 字节随机数的 base64url（无填充）。**它就是那张链接的全部机密**',
    project_id  CHAR(36)    NOT NULL COMMENT '邀请进哪个项目',
    created_by  CHAR(36)    NOT NULL COMMENT '谁生成的 —— 出问题时要知道是谁把链接放出去了',
    created_at  DATETIME(3) NOT NULL COMMENT '生成时间',
    expires_at  DATETIME(3) NOT NULL COMMENT '过期时间；过了就作废，不管有没有人用过',
    accepted_by CHAR(36)    NULL COMMENT '谁接受的；NULL = 还没被接受',
    accepted_at DATETIME(3) NULL COMMENT '接受时间；和 accepted_by 同时有值或同时为空',
    revoked_at  DATETIME(3) NULL COMMENT '撤销时间；NULL = 没被撤销',
    PRIMARY KEY (token),
    KEY idx_invitation_project (project_id, created_at) COMMENT '支撑 findByProject（列出这个项目还没被接受的邀请）'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '邀请链接；ProjectInvitationRepository 实现在 codeloom-workspace';


-- ---------------------------------------------------------------------------
--  project_member —— 项目成员（多对多）
--
--  只有两列，没有 role 也没有 joined_at：Project.members 就是一个 Set<UserId>，
--  领域模型里没有角色、也没有加入时间。要加角色时先改 domain，再改这里。
--
--  「一个项目最多 2 人」是产品前提（Project.MAX_MEMBERS），但**不在数据库里约束**：
--  它需要在「加入成员」这个业务动作上做检查，而那是应用层的事。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS project_member (
    project_id CHAR(36) NOT NULL COMMENT 'ProjectId',
    user_id    CHAR(36) NOT NULL COMMENT 'UserId',
    PRIMARY KEY (project_id, user_id),
    KEY idx_project_member_user (user_id) COMMENT '支撑 ProjectRepository.findByMember（我的项目列表）'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '项目成员';


-- ---------------------------------------------------------------------------
--  workspace —— 一棵工作区：**谁**在**哪个项目**里的那份代码
--
--  一行 = 一个 git worktree + 一条分支 + 一个 HEAD，再加上并发控制要用的 fencing_token。
--
--  ---------------------------------------------------------------------------
--  主键为什么是 (owner_id, project_id)，而不是「一条会话一行」
--
--  最初它挂在会话上（每会话一个 worktree + 一条 session/<id> 分支）。那个模型有个
--  说不通的地方：**同一个人在同一项目里开第二段对话，就找不到上一轮的改动了** ——
--  代码明明是他的，却因为"换了条会话"而隔在另一棵树里。更别扭的是，这个人的两条会话线
--  竟然要为「合并」付一次代价，而它们本来就是同一个人的同一份工作。
--
--  所以树挂在「人 + 项目」上：一个人在一个项目里有且只有一棵树，他的所有会话都在那棵树里
--  干活。换会话只换对话，不换代码。键本身是个天然复合键，造一个合成 id 出来只会多一列
--  没人读的数据，再加一次"拿 id 反查是谁"的查询。
--
--  ---------------------------------------------------------------------------
--  它同时是**并发控制的粒度**，这一点必须说清
--
--  执行租约锁的是一棵树（见 ExecutionLease），而不只是"一条会话" —— 因为同一个人的两条
--  会话现在共用一份目录，谁也不能在另一个人正写的时候插进去。锁的键和树的键必须是同一个，
--  否则两条会话能同时写一份 worktree，而那正是 worktree 隔离本来要防的事。
--
--  （两个**不同**的人各有自己的树，所以照旧能同时跑、互不干扰 —— 隔离没有消失，
--  只是从「每个会话」提到了「每个人」。）
--
--  ---------------------------------------------------------------------------
--  fencing_token —— 唯一一个不属于领域状态的列
--
--  它是「执行租约」的确定性那一半。锁本身在 Redis（SET NX PX + Lua 续约），
--  锁只负责挡住并发，挡不住这种情况：执行者 A 发生长时间 GC 停顿 → 锁过期 →
--  执行者 B 接管 → A 从停顿中醒来，**它并不知道自己已经失去执行权**，继续写入。
--
--  这个缺口修不掉（锁的过期和持有者的知情之间必然有时延），所以改成校验写入：
--      · 每次抢到锁，得到一个单调递增的 fencing token
--      · 所有写入都带上它，本行做守门：
--            UPDATE workspace SET ..., fencing_token = ?
--             WHERE owner_id = ? AND project_id = ? AND fencing_token <= ?
--        影响行数为 0 就说明 token 已失效，抛 StaleLeaseException 立刻中止
--
--  校验落在 MySQL 而不是 Redis，是因为它必须是**确定性且持久**的：
--  Redis 被清空导致计数器回退时，僵尸写入者仍然会被这一列挡住。
--
--  **它必须挂在这张表上，不能留在 session 上。** 反例：会话 A 拿到树锁、拿到号 5；
--  A 停顿，锁过期；同一个人的另一条会话 B 拿到树锁，它那一行自己的计数才到 1；
--  A 醒来拿号 5 去写 —— 校验的是 A 自己那一行，号没变过，于是照写不误。
--  锁的键和号的键不是同一个，这道门就等于不存在。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workspace (
    owner_id      CHAR(36)      NOT NULL COMMENT 'UserId —— 这个人',
    project_id    CHAR(36)      NOT NULL COMMENT 'ProjectId —— 在这个项目里',
    branch        VARCHAR(255)  NOT NULL COMMENT '这棵树的固定分支名（workspace/<ownerId>）',
    worktree_path VARCHAR(1024) NOT NULL COMMENT '这棵树的 git worktree 绝对路径；每人每项目一个，互不干扰',
    head_commit   VARCHAR(64)   NULL COMMENT '这棵树的 HEAD；空项目起步时为 NULL，charset 留 64 是为了兼容 SHA-256 仓库',

    -- 并发控制（非领域状态）
    fencing_token BIGINT        NOT NULL DEFAULT 0 COMMENT '已受理的最大 fencing token；写入时用它守门，见上方长注释',

    PRIMARY KEY (owner_id, project_id),
    KEY idx_workspace_project (project_id) COMMENT '支撑 WorkspaceRepository.findByProject（会话列表要带上每条会话的代码位置）'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '工作区 = 一棵 git worktree；键是「人 × 项目」，WorkspaceRepository 实现在 codeloom-workspace';


-- ---------------------------------------------------------------------------
--  session —— 会话聚合根
--
--  一行 = 一条会话 = 一次对话 + 一份模型配置。
--
--  **它没有分支、没有 worktree 路径、没有 HEAD** —— 那三样是上面 workspace 的列。
--  抄一份到这里就会有两份状态，而两份状态迟早有一份是旧的。
--
--  state / turn_index / head_commit 都是能从事件流里放出来的，这里冗余存储。
--  不冗余的话，每次判断「这条会话能不能执行」都要把事件流从头放一遍。
--  代价是：追加 SessionStateChanged 时必须同事务更新本行 —— 这个约束落在
--  SessionWriter（那个"唯一实现点"）里。
--
--  ---------------------------------------------------------------------------
--  模型配置为什么内联成 3 列（provider / model_id / system_prompt）而不是拆一张表
--
--  每条会话有且只有一份配置，没有独立生命周期，也没有「多份配置里选一份」的查询 ——
--  拆表只会换来一次 JOIN。会话中途换模型是**改这一行**、并同事务落一条 ModelChanged
--  （见 SessionWriter 里换模型那一处），不是新开一份配置。
--
--  ---------------------------------------------------------------------------
--  为什么这里【不该】有任何 API Key 列，且永远不该有
--
--  ModelConfig 会跟着 Session 被序列化、被日志打印、被 toString() 带出去。
--  密钥一旦进来就会顺着这些路径漏到某个日志文件或某条历史事件里。
--  密钥单独加密存储（见下方 user_api_key 表，按 (user_id, provider) 关联），
--  只在真正发 HTTP 请求的那一刻取出来注入请求头 —— 它不进入任何领域对象，也不进入这两张表。
--
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS session (
    id            CHAR(36)      NOT NULL COMMENT 'SessionId，UUID 在领域侧生成',
    project_id    CHAR(36)      NOT NULL COMMENT '所属项目',
    owner_id      CHAR(36)      NOT NULL COMMENT '会话拥有者（协作的两个人各有一条会话）',
    state         VARCHAR(32)   NOT NULL COMMENT 'SessionState 枚举名，取值见 SessionState.java',
    turn_index    INT           NOT NULL DEFAULT 0 COMMENT '当前第几轮；回滚时按它把代码位置和对话位置对上',

    -- 模型配置（ModelConfig 内联）
    --
    -- 这里曾经有 temperature 和 max_tokens 两列，都**不再属于会话**：
    --   · temperature —— 删了。我们根本不发这个参数（发了就得挑一个值，而挑值没有依据），
    --     请求体里不出现它，服务端的默认值说了算。
    --   · max_tokens —— 搬走了。它是**模型的属性**（不同模型的上限差着数量级），
    --     现在住在 codeloom-agent 的 ModelCapabilities 里，发请求时从那儿取。
    provider      VARCHAR(32)   NOT NULL COMMENT '用哪一家，如 deepseek。**地址不在这里** —— 它是那一家的属性，见 Providers',
    model_id      VARCHAR(128)  NOT NULL COMMENT '模型标识。单次输出上限按它查 ModelCapabilities，不在本表',
    system_prompt TEXT          NULL COMMENT '会话级系统提示词；NULL 表示不加。里面不能放每次都变的内容，否则每轮都吃不到 prompt 缓存',

    PRIMARY KEY (id),
    KEY idx_session_project (project_id) COMMENT '支撑 SessionRepository.findByProject'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '会话 = 一次对话；代码位置在 workspace 上。SessionRepository 实现在 codeloom-workspace。findAll 走全表扫描，会话数量级很小，不为此建低区分度索引';


-- ---------------------------------------------------------------------------
--  event —— 事件流，系统的事实来源
--
--  append-only：永不 UPDATE、永不 DELETE。回放、断线补齐、审计、崩溃恢复全部建立在它上面。
--
--  ---------------------------------------------------------------------------
--  为什么 id 就是 seq，而不是「每会话一个序号」
--
--  StoredEvent.seq 是**全局单调递增**的，由本表的自增主键提供。这样「按 seq 之后拉取」
--  这一个查询同时满足三件事：
--      · 断线重连补齐（readAfter）
--      · checkpoint 定位 / 聊天室锚点引用（全局唯一，不必再带 session_id 就能指到唯一一条）
--      · 回放（readAll）
--  如果 seq 改成会话内递增，上面三件事就都要额外带上 session_id，聊天室锚点也会变得有歧义。
--
--  ---------------------------------------------------------------------------
--  seq「无洞」依赖执行租约，这一点必须说清
--
--  InnoDB 的自增值在**分配**上单调，但和**提交**顺序不一定一致：并发事务 A 先拿到 10、
--  B 后拿到 11，完全可能是 B 先提交。此时一个正在追流的读者把游标推过了 10，就会漏掉它。
--
--  这个洞在这里不会出现，因为**同一会话的写入被执行租约串行化了**：租约锁的是一棵**工作区**，
--  而一棵树覆盖它名下所有会话（同一个人的会话共用一棵树，见 workspace 表），
--  所以任一时刻只有一个执行者在写某条会话，该会话内的分配顺序就是提交顺序。
--  跨会话不存在这个问题，因为消费端永远按 session 分别追。
--  这正是「一棵树一个执行者」除了防写冲突之外的第二个作用。
--
--  ---------------------------------------------------------------------------
--  关于 payload 用原生 JSON 类型
--
--  事件类型是 sealed 层级的编译期概念，落库的是它的 JSON 表示，由 EventCodec 用穷尽 switch
--  手写编解码（新增事件类型漏处理则编译不过）。用 MySQL 的 JSON 类型而不是 TEXT：
--  存储更省、写入时校验收紧、需要时能在 DB 层直接查。
--  注意 MySQL 的 JSON 列会规范化对象键的顺序，所以别指望读回来的字符串和写进去的逐字符相同 ——
--  我们都是反序列化成 record，不受影响。
--
--  type 列的值对应 EventType 枚举名，那是**持久化格式的一部分**，发布后不能改名
--  （历史数据里存的就是这些字符串）。不落库的只有 ASSISTANT_DELTA 和 REASONING_DELTA：
--  逐 token 落库会让本表每轮膨胀几千行，它们只走实时通道。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `event` (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '即 StoredEvent.seq，全局单调递增，从 1 开始',
    session_id  CHAR(36)    NOT NULL COMMENT '事件归属哪条会话',
    `type`      VARCHAR(64) NOT NULL COMMENT 'EventType 枚举名，持久化格式的一部分，发布后不可改名',
    payload     JSON        NOT NULL COMMENT '业务事实，EventCodec 用穷尽 switch 编解码',
    occurred_at DATETIME(3) NOT NULL COMMENT '落库时间，不是模型生成时间',
    PRIMARY KEY (id),
    KEY idx_event_session (session_id, id) COMMENT '支撑 readAfter / readAll / lastSeq 三个按会话顺序读的查询'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '事件流，append-only；EventStore 实现在 codeloom-realtime';


-- ---------------------------------------------------------------------------
--  user_api_key —— 用户自己的模型 API Key（BYOK）
--
--  ---------------------------------------------------------------------------
--  这是**唯一一张领域模型里没有对应记录的表**，而且是刻意的
--
--  密钥不进入任何领域对象：ModelConfig 会跟着 Session 被序列化、被日志打印、
--  被 toString() 带出去，密钥一旦进去就会顺着这些路径漏到某个日志文件里。
--  所以它在这里单独存、单独取，只在拼 HTTP 请求头的那一瞬间以明文形式存在。
--
--  ---------------------------------------------------------------------------
--  存的是密文，不是明文，也不是哈希
--
--  哈希不行：我们**必须能还原出原文**才能拿去发请求，而哈希是单向的。
--  所以是 AES-GCM 加密，主密钥来自配置（codeloom.secret-key），绝不进版本库。
--  密文里带着 IV 和认证标签（GCM 是 AEAD：改一个字节就解不开，
--  这比"加密后再补一个哈希校验"少一层要同步的东西）。
--
--  一列装下 IV + 密文（base64 拼起来），而不是拆两列：它们永远是成对读写的，
--  拆开只会多一个"只更新了其中一个"的机会。
--
--  ---------------------------------------------------------------------------
--  主键是 (user_id, endpoint_host)，**不是 user_id**
--
--  因为这个项目支持用户自由选择模型，而每家的密钥是各自独立的。只按用户存会同时错两件事：
--    1) 配了第二家的 key 会覆盖第一家的 —— 换个模型就得重配一次；
--    2) 更严重的是**会把 A 家的凭据发到 B 家的服务器上**。那不是功能问题，
--       是把一个第三方服务的密钥交给了另一个第三方。
--
--  存 host 而不是完整 baseUrl：{@code https://api.deepseek.com/v1} 和
--  {@code https://api.deepseek.com} 是同一个服务、同一把 key，
--  换个版本路径不该让人重新填一遍密钥。
--
--  "这个列里一定是归一后的值"在代码里由 domain 的 EndpointHost 值对象保证
--  （归一是它的构造的一部分，不是调用方的约定）—— 也就是说这个不变量不靠这张表的
--  注释来维持，注释只是把它写下来。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_api_key (
    user_id       CHAR(36)      NOT NULL COMMENT 'UserId',
    provider      VARCHAR(32)   NOT NULL COMMENT '用哪一家，如 deepseek。**不是地址** —— 地址是那一家的属性（Providers 或这一行的 base_url）。主键的一半：「一家一把 key」',
    -- 下面两列是"这是什么"，不是"怎么连上"。密钥只是让请求能发出去，而界面上要让人
    -- 认出"哪个是我的 DeepSeek"。两列都可空：不填就用预设名 / id 兜底。
    name          VARCHAR(64)   NULL COMMENT '用户给这一家起的显示名，如「DeepSeek 官方」。NULL = 用预设名或 id 兜底',
    base_url      VARCHAR(512)  NULL COMMENT '这一家的请求地址。预设的那几家为 NULL（地址在 Providers 里）；自定义 provider 的地址住在这儿 —— 那是它唯一的位置',
    sealed_key    VARCHAR(1024) NOT NULL COMMENT 'base64(IV):base64(密文+认证标签)。**永远不要存明文**',
    created_at    DATETIME(3)   NOT NULL COMMENT '第一次在这个端点上配置的时间',
    updated_at

        DATETIME(3)   NOT NULL COMMENT '最后一次更换的时间 —— 「什么时候换过 key」是排障时第一个要问的',
    PRIMARY KEY (user_id, provider)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'BYOK 密钥，**按端点各一把**；实现在 codeloom-app（与 UserRepository 同属认证职责）';


-- ---------------------------------------------------------------------------
--  chat_message —— 项目内聊天室
--
--  纯人类通道，agent 看不见它。想把某句话给 agent 看只能手动分享 —— 不做自动注入，
--  理由是闲聊信噪比太低，自动灌进上下文既烧窗口又带偏模型。
--
--  id 由存储层分配（本表自增）：聊天要按全库单调顺序拉取，所以落库前领域里根本
--  构造不出一个 id 完整的 ChatMessage —— 这也是 ChatMessageRepository.append 返回
--  完整对象而不是接收一个 ChatMessage 的原因。
--
--  anchor_event_seq 是「引用回复」：A 可以对着 B 的某个工具调用或 diff 精确地回一句
--  「你这段不对」。因为 event 的 seq 全局唯一，只存一个数就够了，不需要再带 session_id。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_message (
    id               BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'ChatMessageId，存储层分配，从 1 开始',
    project_id       CHAR(36)    NOT NULL COMMENT '所属项目（聊天室是项目级的，不是会话级的）',
    author_id        CHAR(36)    NOT NULL COMMENT '发送者',
    `text`           TEXT        NOT NULL COMMENT '消息正文，非空由领域侧保证',
    anchor_event_seq BIGINT      NULL COMMENT '可选锚点：指向 event.id；NULL 表示普通消息',
    --  摘要抄一份存下来，而不是每次去查那条事件：被引用的事件属于**对方的 agent 流**，
    --  聊天室这一侧看不到；而且事件流会随会话清理而变样。引用该留住的是
    --  「当时指的是什么」——那是已经说过的话的一部分，不是一个能随时查的指针。
    anchor_text      VARCHAR(300) NULL COMMENT '锚点那一步的一句话摘要；和 anchor_event_seq 同生共死',
    created_at       DATETIME(3) NOT NULL COMMENT '发送时间',
    PRIMARY KEY (id),
    KEY idx_chat_project (project_id, id) COMMENT '支撑 findAfter / findRecent 两个按项目顺序拉的查询'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '项目内聊天室；ChatMessageRepository 实现在 codeloom-realtime';

-- ---------------------------------------------------------------------------
--  turn_request —— 入站请求的幂等键
--
--  POST /messages 是**阻塞**的（等这一轮跑完才返回，可能几十秒）。阻塞意味着
--  客户端超时重试是大概率事件，用户双击「发送」同理 —— 而重试会**真跑第二轮**：
--  花两次钱，工作区被改两遍。
--
--  所以每个请求带一个客户端生成的 client_message_id，这里 (session_id, id) 唯一。
--  插入成功 = 第一次；主键冲突 = 重复，直接拒掉，不落用户消息、也不跑那一轮。
--
--  为什么不是给 event 表加一列：那是「请求的标识」，不是「发生过的事实」的一部分。
--  混进事件流会让每一条 UserMessage 都拖着一个可空、且对回放毫无用处的字段。
--
--  为什么不用 Redis 的 SETNX：它给不出「这个请求已经处理过了」的**持久**答案
--  （重启就没了），而这个判断恰恰要在重启之后仍然成立 —— 用户重试往往就是因为
--  服务端刚抖了一下。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS turn_request (
    session_id        CHAR(36)    NOT NULL COMMENT '哪条会话',
    client_message_id VARCHAR(64) NOT NULL COMMENT '客户端生成的请求标识（uuid）',
    created_at        DATETIME(3) NOT NULL COMMENT '第一次处理它的时间',
    PRIMARY KEY (session_id, client_message_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '入站消息的幂等键；TurnRequestRepository 实现在 codeloom-app';



