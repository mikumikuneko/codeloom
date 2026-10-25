-- ============================================================================
--  codeloom 表结构
--
--  手工执行，没有迁移工具；加列需要自己补 ALTER。理由与代价见
--  docs/decisions/process/2026-09-24-keep-the-dependency-list-short.md。
--
--  表与模块的归属：
--
--      user                        -> codeloom-app      （认证是 app 的职责）
--      project / project_member    -> codeloom-workspace（项目就是一个 git 仓库）
--      workspace                   -> codeloom-workspace（worktree、分支、HEAD 与 fencing 都在这一行）
--      session                     -> codeloom-workspace
--      event / chat_message        -> codeloom-realtime （同属消息流）
--
--  两条贯穿全表的约定：
--
--  1) 列与领域记录一一对应。domain 的 record 是权威，表里不加领域造不出来的列；
--     冗余字段必须同步，漏掉一份就会对不上。唯一例外是 workspace.fencing_token，
--     它是并发控制的落点而不是领域状态。
--
--  2) 不用外键，也不用级联。外键概念在应用层解决：它会阻碍在线 DDL（加列要锁子表）
--     和将来的分库分表，而完整性检查在“所有写入都走仓储”的前提下价值有限。
--
--  时间列统一用 DATETIME(3)：DATETIME 不带时区，与 Instant 的换算取决于 JDBC 参数，
--  application.yml 已固定 serverTimezone=Asia/Shanghai，与 Jackson 的 time-zone 一致。
--  毫秒精度是因为同一条会话可能在同一毫秒内落多条事件，秒级精度排不出先后。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS codeloom
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE codeloom;


-- ---------------------------------------------------------------------------
--  user —— 平台用户
--
--  表名一律加反引号，统一写法。实测（MySQL 8.0.46）：user 不是保留字，
--  不加反引号的 CREATE TABLE 和 SELECT 都能执行，虽然它同时是内置函数名。
--
--  密码只存哈希，不存明文。哈希不可逆，所以允许出现在领域对象里；User 重写了
--  toString()，不把它带进日志。真正的密钥（模型 API Key）不在这张表，见下面的
--  session 与 user_api_key 说明。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
    id            CHAR(36)     NOT NULL COMMENT 'UserId，UUID 在领域侧生成，不等存储分配',
    username      VARCHAR(64)  NOT NULL COMMENT '账号，全局唯一',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希（当前输出 60 字符，留余量）。不可逆，不是密钥',
    display_name  VARCHAR(64)  NOT NULL COMMENT '用户名（用户在界面上看到的那个名字）；领域构造器在空值时回落成账号',
    created_at    DATETIME(3)  NOT NULL COMMENT '注册时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    -- 用户名也唯一。它不是身份（事件里记的是 id），但两个人都叫“小明”时，
    -- 界面上无法回答“这句话是谁说的”，所以注册重名直接拒绝。比对沿用列的排序规则
    -- utf8mb4_0900_ai_ci：大小写与重音不敏感，和账号那条一致。
    -- 见 docs/decisions/architecture/2026-10-07-display-name-is-unique.md。
    UNIQUE KEY uk_user_display_name (display_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '平台用户；UserRepository 实现在 codeloom-app';


-- ---------------------------------------------------------------------------
--  project —— 项目 = 一个 git 仓库
--
--  owner_id 是“现在谁是房主”，project_member 是“现在谁在里面”。两者不能互相表达：
--  成员是集合，没有先后主次，而且两者都会随人退出而变化。
--
--  房主的职责是退出时把项目交出去：还有别人时转给那个人，自己是最后一个时项目随之
--  消失。因此房主必须是成员之一；这条不变量写在 Project 的紧凑构造器里。
--
--  项目没有单独的删除动作，也没有墓碑，它是人走空的副产品：成员各自退出并带走自己的
--  会话、工作区和磁盘上的树，最后一人退出时这一行连同相关数据才消失。这样任何一个人
--  都只能决定自己走不走。见
--  docs/decisions/architecture/2026-09-29-project-departure-not-tombstones.md。
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
--  一行是一张已发出的邀请。token 是主键，因为链接里唯一有信息量的部分就是它。
--  加成员走邀请链接而不是按账号直接添加：邀请人不需要先知道对方账号，对方也不用
--  先把账号发过来。见
--  docs/decisions/architecture/2026-09-29-invite-links-not-username-invites.md。
--
--  token 不是 UUID：它是能直接换取项目权限的凭据。这里存 32 字节（256 位）
--  SecureRandom 的 base64url 编码，共 43 个字符。列宽按 43 定：宽了浪费，窄了会
--  静默截断（表现为部分链接能用、部分不能用）。Java 侧的 ProjectInvitation.TOKEN_LENGTH
--  也写死 43 并用于构造期校验，两边没有人替你对齐，改一处要同时改另一处。
--
--  “还能不能用”由三列算出，不存状态列：
--
--      可用 = accepted_by IS NULL AND revoked_at IS NULL AND expires_at > now()
--
--  存 status 会与三个时间戳形成两个真相，过期那一刻没人改 status 就会不一致。
--  判断收在 ProjectInvitation.isUsableAt 一处。
--
--  过期的行不删：删了之后“我明明发过一张链接”就没有对证。保留并显示“已过期”更诚实。
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
--  只有两列，没有 role 也没有 joined_at：Project.members 是 Set<UserId>，领域模型里
--  没有角色和加入时间。要加角色时先改 domain，再改这里。
--
--  “一个项目最多 2 人”是产品前提（Project.MAX_MEMBERS），不在数据库约束：它属于
--  “加入成员”这个业务动作，由应用层检查。
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
--  workspace —— 一棵工作区：“谁”在“哪个项目”里的那份代码
--
--  一行 = 一个 git worktree + 一条分支 + 一个 HEAD，外加并发控制用的 fencing_token。
--
--  主键是 (owner_id, project_id)，不是一条会话一行：同一个人在同一项目里的所有会话
--  共用一棵树，换会话只换对话、不换代码。若按会话分树，这个人开第二段对话就找不到
--  上一轮的改动，两条会话还要为合并付一次代价。复合键是天然的，造合成 id 只会多一列
--  没人读的数据和一次反查。见
--  docs/decisions/architecture/2026-09-24-workspace-per-person-merge-to-converge.md。
--
--  这棵树同时是并发控制的粒度：执行租约锁的是树（见 ExecutionLease），不只是会话。
--  同一人的多条会话共用一份目录，锁的键必须和树的键相同，否则它们能同时写同一份
--  worktree。不同的人各有自己的树，仍可同时运行，隔离从“每个会话”提升到“每个人”。
--
--  fencing_token 是唯一不属于领域状态的列，是执行租约的确定性那一半。Redis 锁
-- （SET NX PX + Lua 续约）只能挡住并发，挡不住执行者长时间停顿、租约过期、被接管后
-- 又醒来继续写入的情况。所以每次抢到锁都取得一个单调递增的 token，所有写入都带上它；
--  WorkspaceFence.assertValid 在加锁读里确认它仍是当前有效值，不是就抛
--  StaleLeaseException 立刻中止。加锁读把这一行锁到事务提交。
--
--  校验放在 MySQL 而不是 Redis，因为它必须确定且持久：Redis 被清空、计数器回退时，
--  这一列仍能挡住失效的写入者。它必须挂在这张表上，不能留在 session 上。反例：
--  会话 A 拿树锁和号 5，停顿后锁过期；同一人的会话 B 拿到树锁，B 自己那行计数才到 1；
--  A 醒来用号 5 写入，校验的是 A 自己那行，号没变，于是照写不误。锁的键和号的键
--  不是同一个，这道防线就不存在。见
--  docs/decisions/architecture/2026-09-24-lease-and-fencing-token.md。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workspace (
    owner_id      CHAR(36)      NOT NULL COMMENT 'UserId —— 这个人',
    project_id    CHAR(36)      NOT NULL COMMENT 'ProjectId —— 在这个项目里',
    branch        VARCHAR(255)  NOT NULL COMMENT '这棵树的固定分支名（workspace/ 加 ownerId）',
    worktree_path VARCHAR(1024) NOT NULL COMMENT '这棵树的 git worktree 绝对路径；每人每项目一个，互不干扰',
    head_commit   VARCHAR(64)   NULL COMMENT '这棵树的 HEAD；建项目时总会先造一笔基点提交，当前路径不会写入 NULL，charset 留 64 是为了兼容 SHA-256 仓库',

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
--  它没有分支、没有 worktree 路径、没有 HEAD，那三样在 workspace 表。抄一份到这里
--  就会有两份状态，迟早有一份是旧的。
--
--  state / turn_index 可以从事件流重新放出，这里冗余存储，避免每次判断
--  “这条会话能不能执行”都从头回放。代价是：追加 SessionStateChanged 时必须同事务更新
--  本行，这个约束落在 SessionWriter 这唯一的写入点。
--
--  模型配置内联成三列（provider / model_id / system_prompt）而不拆表：每条会话只有
--  一份配置，没有独立生命周期，也没有“多份配置中选一份”的查询，拆表只会多一次 JOIN。
--  会话中途换模型是改这一行并同事务落一条 ModelChanged，不是新开配置。
--
--  这里不该有任何 API Key 列，且永远不该有：ModelConfig 会跟着 Session 被序列化、
--  打印和 toString() 带出去，密钥一旦进来就会顺着这些路径泄漏。密钥单独加密存在
--  user_api_key（按 user_id + provider），只在拼 HTTP 请求头时取出，不进入任何领域对象。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS session (
    id            CHAR(36)      NOT NULL COMMENT 'SessionId，UUID 在领域侧生成',
    project_id    CHAR(36)      NOT NULL COMMENT '所属项目',
    owner_id      CHAR(36)      NOT NULL COMMENT '会话拥有者（协作的两个人各有一条会话）',
    state         VARCHAR(32)   NOT NULL COMMENT 'SessionState 枚举名，取值见 SessionState.java',
    turn_index    INT           NOT NULL DEFAULT 0 COMMENT '当前第几轮；回滚时按它把代码位置和对话位置对上',

    -- 模型配置（ModelConfig 内联）
    --
    -- temperature 不属于会话，已删除：请求体不发送这个参数，采用服务商的默认值。
    -- 见 docs/decisions/architecture/2026-10-04-temperature-is-not-sent.md。
    -- max_tokens 也不属于会话：它是模型的属性（不同模型上限差几个数量级），住在
    -- codeloom-agent 的 ModelCapabilities 里，发请求时从那里取。
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
--  append-only：永不 UPDATE；DELETE 只有整条会话一起删这一种（丢弃会话、退出项目），
--  逐条删除不做。回放、断线补齐、审计、崩溃恢复都建立在「写下的行不会再变」上。见
--  docs/decisions/architecture/2026-09-24-one-event-log-as-the-source-of-truth.md。
--
--  id 就是 StoredEvent.seq，由本表自增主键提供，全局单调递增。这样“按 seq 之后拉取”
--  同时满足断线重连补齐、checkpoint 定位 / 聊天室锚点引用（全局唯一，不必带 session_id）
--  和回放。若改成会话内递增，这三处都要额外带 session_id，锚点也会出现歧义。
--
--  追流不漏事件依赖执行租约：InnoDB 自增值在分配上单调，但不保证提交顺序——并发事务可能
--  后取号的先提交，追流读者把游标推过前一号就会漏事件。同一会话的写入被租约串行化：
--  租约锁的是一棵树，树覆盖其名下所有会话（同一人的会话共用一棵），因此任一时刻只有
--  一个执行者在写某条会话，该会话内的分配顺序就是提交顺序。跨会话没有这个问题，
--  消费端按 session 分别追。这是“一棵树一个执行者”除防写冲突之外的第二个作用。
--
--  payload 使用原生 JSON，而不是 TEXT：事件类型是 sealed 层级的编译期概念，落库的是
--  JSON 表示，由 EventCodec 用穷尽 switch 手写编解码。JSON 类型存储更省、写入校验更严、
--  需要时能在数据库层查询。MySQL 的 JSON 列会规范化对象键顺序，读回来的字符串不保证
--  与写入逐字符相同；这里都反序列化成 record，不受影响。
--
--  type 列的值是 EventType 枚举名，属于持久化格式的一部分，发布后不能改名：历史数据
--  存的就是这些字符串。只有 ASSISTANT_DELTA 和 REASONING_DELTA 不落库，逐 token
--  落库会让本表每轮膨胀几千行，它们只走实时通道。
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
  COMMENT = '事件流，append-only（整条会话可删）；EventStore 实现在 codeloom-realtime';


-- ---------------------------------------------------------------------------
--  user_api_key —— 用户自己的模型 API Key（BYOK）
--
--  这是仅有的两张领域模型里没有对应记录的表之一（另一张是 turn_request）。密钥不进入
--  任何领域对象：ModelConfig 会跟着
--  Session 被序列化、打印和 toString() 带出去，密钥一旦进去就会顺着这些路径泄漏。
--  因此它单独存、单独取，只在拼 HTTP 请求头时以明文形式存在。
--
--  存密文，不是明文，也不是哈希：必须能还原原文才能发请求。使用 AES-GCM 加密，主密钥
--  来自 codeloom.secret-key，绝不进版本库。GCM 是 AEAD，密文带 IV 和认证标签，改一个
--  字节就解不开，不需要再补一层哈希校验。
--
--  一列装下 IV 与密文（base64 拼接），不拆两列：它们永远成对读写，拆开只会多一个
--  “只更新其中一个”的机会。
--
--  主键是 (user_id, provider)，不是 user_id：每家密钥独立，只按用户存会让第二家覆盖
--  第一家；更严重的是会把 A 家的凭据发到 B 家的服务器上，那是把第三方密钥交给另一个
--  第三方。按 provider 而不是按地址：预设服务商的地址属于该服务商（住在 Providers 里），
--  同一家换 base path 不该让人重填密钥；自定义服务商的地址存在这一行的 base_url。
--  见 docs/decisions/architecture/2026-09-26-keys-and-identity-are-per-provider.md。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_api_key (
    user_id       CHAR(36)      NOT NULL COMMENT 'UserId',
    provider      VARCHAR(32)   NOT NULL COMMENT '用哪一家，如 deepseek。**不是地址** —— 地址是那一家的属性（Providers 或这一行的 base_url）。主键的一半：「一家一把 key」',
    -- 下面两列是"这是什么"，不是"怎么连上"。密钥只是让请求能发出去，而界面上要让人
    -- 认出"哪个是我的 DeepSeek"。两列都可空：不填就用预设名 / id 兜底。
    name          VARCHAR(64)   NULL COMMENT '用户给这一家起的显示名，如「DeepSeek 官方」。NULL = 用预设名或 id 兜底',
    base_url      VARCHAR(512)  NULL COMMENT '这一家的请求地址。预设的那几家为 NULL（地址在 Providers 里）；自定义 provider 的地址住在这儿 —— 那是它唯一的位置',
    reasoning_effort VARCHAR(16) NULL COMMENT '思考强度 none/low/high/max；NULL = 不发送，由服务商决定默认。只有支持 reasoning_effort 的 provider 才有意义',
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
--  纯人类通道，agent 看不见它。想把某句话给 agent 看只能手动分享，不做自动注入：
--  闲聊信噪比太低，自动灌进上下文既浪费窗口又带偏模型。
--
--  id 由存储层分配（本表自增）：聊天要按全库单调顺序拉取，落库前领域里构造不出 id
--  完整的 ChatMessage。因此 ChatMessageRepository.append 返回完整对象，而不是接收一个
--  ChatMessage。见
--  docs/decisions/architecture/2026-09-24-ids-storage-issued-vs-domain-issued.md。
--
--  anchor_event_seq 用于引用回复：可以对着对方的工具调用或 diff 精确回一句。event 的
--  seq 全局唯一，只存一个数就够，不需要再带 session_id。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_message (
    id               BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'ChatMessageId，存储层分配，从 1 开始',
    project_id       CHAR(36)    NOT NULL COMMENT '所属项目（聊天室是项目级的，不是会话级的）',
    author_id        CHAR(36)    NOT NULL COMMENT '发送者',
    `text`           TEXT        NOT NULL COMMENT '消息正文，非空由领域侧保证',
    anchor_event_seq BIGINT      NULL COMMENT '可选锚点：指向 event.id；NULL 表示普通消息',
    --  摘要抄一份存下来，而不是每次查事件：被引用的事件属于对方的 agent 流，聊天室这一侧
    --  看不到，而且事件流会随会话清理而变化。引用要留住的是“当时指的是什么”，那是已经
    --  说过的话的一部分，不是一个能随时查的指针。
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
--  POST /messages 是阻塞的（等这一轮跑完才返回，可能几十秒）。客户端超时重试和用户
--  双击“发送”都很常见，重试会真的再跑一轮：花两次钱，工作区被改两遍。
--
--  每个请求带一个客户端生成的 client_message_id，这里 (session_id, client_message_id)
--  唯一。插入成功表示第一次；主键冲突表示重复，直接拒绝，不落用户消息也不跑那一轮。
--
--  不给 event 表加一列：那是请求标识，不是发生过的事实。混进事件流会让每条 UserMessage
--  都拖着一个对回放毫无用处的可空字段。
--
--  不用 Redis SETNX：它给不出“这个请求已经处理过”的持久答案，重启就没了。这个判断
--  恰恰要在重启后仍成立，用户重试往往就是服务端刚抖动过。
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



