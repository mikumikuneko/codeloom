# 中间件：MySQL 8 + Redis 7

两个服务跑在 Docker Compose 里。下面三步做完，应用就能连上；从「连接信息」开始是对照与配置理由，装完再看也行。

## 前置

一台能跑 Docker 的机器（本项目用的是「宿主机 + 另一台机器」的布局，所以下面写的是 `scp` 到远端；本机部署把第 1 步换成 `docker compose up -d` 即可），以及 `docker compose` 插件、`scp` / `ssh`。

下文出现的 `<VM_IP>` 是占位符，换成你那台机器的地址。

## 1. 拷过去，起来

本目录的结构刻意镜像目标机器上的 `/etc/docker/`，所以部署就是一次目录拷贝；compose 里的 bind mount 用的是相对路径（`./mysql/my.cnf`），在 `/etc/docker/` 下执行正好解析到 `/etc/docker/mysql/my.cnf`。

```sh
scp -r docs/deploy/middleware/* root@<VM_IP>:/etc/docker/
ssh root@<VM_IP>
cd /etc/docker
docker compose up -d
```

## 2. 确认起来了

```sh
docker compose ps          # 两个都该是 healthy

# 从宿主机看端口通不通（MySQL 那个）
curl -s -o /dev/null -w "mysql:%{http_code}\n" -m 3 telnet://<VM_IP>:3306

# 连进容器看细节
docker exec mysql mysql -uroot -proot \
  -e "select version(); show databases; show variables like 'character_set_server';"
docker exec redis redis-cli -a root --no-auth-warning config get maxmemory-policy
```

## 3. 让应用连上

1. 执行表结构：`mysql -h <VM_IP> -uroot -proot codeloom < docs/sql/schema.sql`（库 `codeloom` 由 `MYSQL_DATABASE` 在第一次启动时自动建）。
2. 在 `codeloom-app/src/main/resources/application-local.yml` 里填：

```yaml
codeloom:
  db-host: <VM_IP>
  redis-host: <VM_IP>
spring:
  datasource:
    password: root
  data:
    redis:
      password: root
```

完整示例见 `codeloom-app/src/main/resources/application-local.yml.example`。

## 连接信息

| | 地址 | 用户 | 密码 |
|---|---|---|---|
| MySQL | `<VM_IP>:3306` | `root` | `root` |
| Redis | `<VM_IP>:6379` | （无 ACL 用户） | `root` |

口令写死在配置文件里（本地开发用）。对外部署前先改掉 —— 见最后一节「换一台机器要改什么」。

## 三条关键配置

### `innodb_flush_log_at_trx_commit = 1`

每次事务提交都把 redo log 刷到磁盘。事件溯源里"提交了却丢了"不可接受，这条决定了崩溃恢复能不能信。（1 是 MySQL 的默认值，显式写出来是为了它不能被悄悄改掉。）

### `maxmemory-policy noeviction`

Redis 里放的是执行租约（带 TTL 的锁）、Pub/Sub 消息、流式增量 —— 都是易失数据。但正因为如此不能让它淘汰 key：**淘汰掉一把锁，等于两个实例同时执行同一条会话，同一个 git worktree 被并发写**。所以宁可写满时报错，也不静默淘汰。

这是本项目里唯一与正确性相关的 Redis 配置。

### `character-set-server = utf8mb4`

`utf8` 实际上是 utf8mb3，存不了 emoji 和部分汉字组合，而 agent 的输出里这些并不少见。

## Redis 的持久化不是正确性的依靠

RDB + AOF 都开了（AOF `everysec`），但要说清楚：它们不是正确性的依靠。Redis 里的数据全丢也不会丢任何重要东西 —— 完整回复早就写进 MySQL 了。

正确性来自写入侧的 fencing token：锁因为任何原因失效（GC 停顿、主从切换、Redis 重启）时，单调递增的 token 校验会拒绝过期的持有者。**锁只提供"大多数时候的互斥"，正确性由写入前的那次校验保证。**

## 换一台机器要改什么

- **两处口令**：`docker-compose.yml` 的 `MYSQL_ROOT_PASSWORD`（还有 mysql 的 healthcheck）、`redis/redis.conf` 的 `requirepass`（还有 compose 里 redis 的 healthcheck）
- `mysql/my.cnf` 的 `innodb_buffer_pool_size`、`redis/redis.conf` 的 `maxmemory`：按那台机器的内存调（见文件内注释）
- 地址：文档里的 `<VM_IP>` 是占位符，照着换
