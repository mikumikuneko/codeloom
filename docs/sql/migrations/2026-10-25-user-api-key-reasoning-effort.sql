-- 给供应商配置加思考强度（DeepSeek 的 reasoning_effort）。
--
-- 这张表手工维护，没有迁移工具；已有库要先执行这一句，再启动新版本。新库直接执行
-- docs/sql/schema.sql 即可，那里已经带上这一列。

ALTER TABLE user_api_key
    ADD COLUMN reasoning_effort VARCHAR(16) NULL
        COMMENT '思考强度 none/low/high/max；NULL = 不发送，由服务商决定默认。只有支持 reasoning_effort 的 provider 才有意义'
        AFTER base_url;
