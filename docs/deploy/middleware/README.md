# 中间件：MySQL 8 + Redis 7

两个服务跑在 Docker Compose 里。完成下面三步后应用即可连接；后面的「连接信息」用于对照和说明配置理由。

## 前置

一台能运行 Docker 的机器（本项目采用“宿主机 + 另一台机器”的布局，所以下面用 `scp` 部署到远端；本机部署把第 1 步换成 `docker compose up -d`），以及 `docker compose` 插件、`scp` / `ssh`。

下文出现的 `VM_IP` 是占位符，替换成目标机器地址。

## 1. 拷过去，起来

本目录的结构镜像目标机器上的 `/etc/docker/`，部署就是一次目录拷贝；compose 里的 bind mount 使用相对路径（`./mysql/my.cnf`），在 `/etc/docker/` 下执行会解析到 `/etc/docker/mysql/my.cnf`。

```sh
scp -r docs/deploy/middleware/* root@VM_IP:/etc/docker/
ssh root@VM_IP
cd /etc/docker
docker compose up -d
```

## 2. 确认起来了

```sh
docker compose ps          # 两个都该是 healthy

# 从宿主机看端口通不通（MySQL 那个）
curl -s -o /dev/null -w "mysql:%{http_code}\n" -m 3 telnet://VM_IP:3306

# 连进容器看细节
docker exec mysql mysql -uroot -proot \
  -e "select version(); show databases; show variables like 'character_set_server';"
docker exec redis redis-cli -a root --no-auth-warning config get maxmemory-policy
```

## 3. 让应用连上

1. 执行表结构：`mysql -h VM_IP -uroot -proot codeloom < docs/sql/schema.sql`（库 `codeloom` 由 `MYSQL_DATABASE` 在第一次启动时自动建）。
2. 在 `codeloom-app/src/main/resources/application-local.yml` 里填：

```yaml
codeloom:
  db-host: VM_IP
  redis-host: VM_IP
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
| MySQL | `VM_IP:3306` | `root` | `root` |
| Redis | `VM_IP:6379` | （无 ACL 用户） | `root` |

口令写死在配置文件里，仅供本地开发。对外部署前先改掉，见最后一节。

## 三条关键配置

### `innodb_flush_log_at_trx_commit = 1`

每次事务提交都把 redo log 刷到磁盘。事件溯源不能接受“提交了却丢了”，这条决定崩溃恢复是否可信。1 是 MySQL 的默认值，显式写出是为了它不被悄悄改掉。

### `maxmemory-policy noeviction`

Redis 里放的是执行租约（带 TTL 的锁）、Pub/Sub 消息和流式增量，都是易失数据。但正因如此不能让 Redis 淘汰 key：淘汰一把锁等于两个实例同时执行同一条会话，同一个 git worktree 被并发写。宁可写满时报错，也不静默淘汰。

这是本项目唯一与正确性相关的 Redis 配置。见 `docs/decisions/architecture/2026-09-25-redis-lock-needs-noeviction.md`。

### `character-set-server = utf8mb4`

`utf8` 实际是 utf8mb3，存不了 emoji 和部分汉字组合，而 agent 输出里这些并不少见。

## Redis 的持久化不是正确性的依靠

RDB 和 AOF 都已开启（AOF `everysec`），但它们不是正确性的依靠：Redis 数据全丢也不会丢重要内容，完整回复早已写进 MySQL。

正确性来自写入侧的 fencing token。锁因任何原因失效（GC 停顿、主从切换、Redis 重启）时，单调递增的 token 校验会拒绝过期的持有者。锁只提供大多数时候的互斥，正确性由写入前的那次校验保证。

## 换一台机器要改什么

- 两处口令：`docker-compose.yml` 的 `MYSQL_ROOT_PASSWORD`（以及 mysql 的 healthcheck）、`redis/redis.conf` 的 `requirepass`（以及 compose 里 redis 的 healthcheck）
- `mysql/my.cnf` 的 `innodb_buffer_pool_size`、`redis/redis.conf` 的 `maxmemory`：按机器内存调整，见文件内注释
- 地址：文档里的 `VM_IP` 是占位符，按实际地址替换
