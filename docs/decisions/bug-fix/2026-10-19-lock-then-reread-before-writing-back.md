# 决策：拿到锁之后重新读，别把请求开始时的快照写回去

## 是什么问题

`project_invitation`、`project`、`session` 的写入都是 `INSERT ... ON DUPLICATE KEY UPDATE`，一次把一组可变态列写回去。有几条路径在**请求开始时**读实体，之后才去拿项目锁或 Redis 执行租约，最后拿那份旧快照算新值并保存。锁只挡住后来的写者，**不会让手里那份快照变新**。

具体看到的交错有三组：

- **邀请**：`accept` 先读邀请、再锁项目；`revoke` 根本不进项目锁。撤销提交之后，`accept` 会用旧快照里的 `revoked_at = NULL` 把撤销覆盖掉；两个 `accept` 在 REPEATABLE READ 下还会各自按旧成员集合做容量判断，后写者的 `save` 会删掉先加入的人。
- **退出项目**：`ProjectDeparture.leave` 用的是控制器传入的 `Project` 快照，没有项目锁。两个人同时退出时，后写者会把先退出的人重新写回成员表；对方刚接受邀请时，它还可能按"最后一人"把整个项目删掉。
- **会话**：`TurnExecutor`、`switchModel`、`rewind`、审批都在拿租约之前读 `Session`，之后整行 `save`。等锁期间另一轮已经推进了 `state`/`turn_index`/模型时，旧快照会把它们写回去。

## 定了什么

状态变更统一按 **先锁 → 锁定读 → 改 → 写** 的顺序：

- `ProjectRepository.lockAndFind` 用 `SELECT ... FOR UPDATE` 一次读项目和成员行，返回**拿到锁之后**的聚合。接受邀请、退出项目改用它。
- `ProjectInvitationRepository.findByTokenForUpdate` 锁定读邀请。`accept`/`revoke` 在项目锁之后调用它，不再用锁前的快照判断状态。
- 邀请的写者统一按「项目 → 邀请」的顺序加锁；`issue` 先锁项目，再读和写邀请。
- `TurnExecutor` 拿到租约后重读 `Session`；`switchModel`、`rewind` 和审批路径也在租约后重读，再交给 `SessionWriter`。
- 邀请的 upsert 改成条件更新：`revoked_at` 只能从 `NULL` 变成时间，不能被写回 `NULL`；`accepted_by`/`accepted_at` 不能被撤销或第二个接受者替换。先到的状态生效。
- `INSERT` 的列名继续显式写死：`ProjectInvitationRow.COLUMNS` 是带 `AS` 别名的 `SELECT` 投影，放进 `INSERT` 的括号里是语法错误；写路径和 `SessionMapper`/`WorkspaceMapper` 一样显式列列名。

## 还考虑过什么，为什么没选

- **只改 SQL 条件更新**：能挡住邀请状态回退，但挡不住项目成员集合和 `Session` 的 `state`/`turn_index` 被整个写回；那些不是单行条件能表达的。
- **全局改成 READ COMMITTED**：锁后普通读能看到最新，但会改变整个应用的隔离级别，影响面远大于这几个低频操作；而且它只解决读，不解决锁顺序。
- **加版本号做乐观锁**：要改表、改所有写路径，当前没有迁移框架，代价和收益不匹配。项目已经有 `ProjectRepository.lock` 和 `WorkspaceFence` 这套行锁语言，沿用更小。
- **只靠锁、不改 SQL**：能覆盖现在的调用方，但以后有人绕开锁直接 `save`，这个洞会重新打开；条件更新是最后一道防线。
- **只靠 SQL、不加锁**：邀请能自保，但两个 `accept` 仍会基于旧成员集合做容量判断；项目和会话的问题也不在邀请表上。

## 代价与换来的

- 代价：每次状态变更多一次锁定读；项目锁会多持有读成员行的时间，退出项目现在也会和接受邀请、合并串行。
- 换来：旧快照不能再覆盖并发状态；撤销/接受只前进；`turn_index`/`state`/模型不会回退。这些不变量在锁和 SQL 两层都有落点。

## 什么情况下该重新考虑

- 如果项目成员变更变成高频操作，项目行锁会成为瓶颈；那时再考虑版本号或按字段条件更新。
- 如果以后真的改全局隔离级别，锁定读仍然正确，但可以回头检查哪些重读是多余的；不要只改隔离级别而不检查锁顺序。
