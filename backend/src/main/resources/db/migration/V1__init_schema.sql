-- ============================================================================
-- MQ → PostgreSQL 配置化数据同步系统  —— 初始化 DDL
-- 目标库：PostgreSQL 17+（本项目验证环境为 18.1）
-- Schema：mqs_pg（由 spring.flyway.default-schema 指定，脚本内不限定 schema）
-- 来源：tech-design.md 附录 A
-- ============================================================================

-- ============================================================================
-- 一、配置域 cfg_*
-- ============================================================================

CREATE TABLE cfg_datasource (
    id            BIGSERIAL     PRIMARY KEY,
    name          VARCHAR(64)   NOT NULL UNIQUE,
    jdbc_url      VARCHAR(1024) NOT NULL,
    username      VARCHAR(128)  NOT NULL,
    -- 密码：{aes}<base64> 为加密存储；{noop}<plain> 仅允许 dev profile 使用
    password_enc  TEXT          NOT NULL,
    pool_config   JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

COMMENT ON TABLE  cfg_datasource            IS '目标 PostgreSQL 数据源';
COMMENT ON COLUMN cfg_datasource.password_enc IS '密码，{aes} 前缀为 AES-GCM 加密，{noop} 仅限 dev';

CREATE TABLE cfg_target (
    id                BIGSERIAL    PRIMARY KEY,
    name              VARCHAR(128) NOT NULL UNIQUE,
    datasource_id     BIGINT       NOT NULL REFERENCES cfg_datasource(id),
    schema_name       VARCHAR(64)  NOT NULL DEFAULT 'public',
    table_name        VARCHAR(64)  NOT NULL,
    -- Upsert Key 列名数组，如 ["id"]
    upsert_keys       JSONB        NOT NULL,
    update_time_field VARCHAR(64)  NOT NULL DEFAULT 'update_time',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (datasource_id, schema_name, table_name)
);

COMMENT ON TABLE  cfg_target            IS '同步目标表定义';
COMMENT ON COLUMN cfg_target.upsert_keys IS 'Upsert Key 列名数组，默认 ["id"]';

CREATE TABLE cfg_route (
    id             BIGSERIAL    PRIMARY KEY,
    name           VARCHAR(128) NOT NULL UNIQUE,
    topic          VARCHAR(256) NOT NULL,
    tag            VARCHAR(256) NOT NULL DEFAULT '',
    target_id      BIGINT       NOT NULL REFERENCES cfg_target(id),
    active_version INTEGER,
    status         VARCHAR(16)  NOT NULL DEFAULT 'INACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (topic, tag)
);

COMMENT ON TABLE  cfg_route        IS '同步路由：Topic + Tag → Target';
COMMENT ON COLUMN cfg_route.status IS 'DRAFT/VALIDATING/VALID/PUBLISHED/ACTIVE/INACTIVE';

CREATE TABLE cfg_version (
    id           BIGSERIAL   PRIMARY KEY,
    route_id     BIGINT      NOT NULL REFERENCES cfg_route(id) ON DELETE CASCADE,
    version      INTEGER     NOT NULL,
    status       VARCHAR(16) NOT NULL,
    -- 不可变配置快照（mappingStrategy / jslt / mappings）
    content      JSONB       NOT NULL,
    change_note  TEXT,
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    UNIQUE (route_id, version)
);

COMMENT ON TABLE  cfg_version         IS '路由配置版本（content 为不可变快照，运行时唯一事实来源）';
COMMENT ON COLUMN cfg_version.content IS '完整配置快照：{mappingStrategy, jslt, mappings[]}';

CREATE INDEX idx_cfg_version_status ON cfg_version (route_id, status);

-- ============================================================================
-- 二、运行域 rt_*
-- ============================================================================

CREATE TABLE rt_consumer_state (
    route_id        BIGINT      PRIMARY KEY REFERENCES cfg_route(id) ON DELETE CASCADE,
    status          VARCHAR(16) NOT NULL DEFAULT 'PAUSED',
    reason          TEXT,
    bind_version    INTEGER,
    last_error_at   TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  rt_consumer_state        IS 'Consumer 运行状态（tech-design §6.1）';
COMMENT ON COLUMN rt_consumer_state.status IS 'RUNNING/PAUSED/RECOVERING/ERROR';

-- 重试任务（ADR-02：重试下沉应用侧）
CREATE TABLE rt_retry_task (
    id              BIGSERIAL    PRIMARY KEY,
    route_id        BIGINT       NOT NULL,
    config_version  INTEGER      NOT NULL,
    message_id      VARCHAR(128) NOT NULL,
    topic           VARCHAR(256) NOT NULL,
    tag             VARCHAR(256),
    payload         BYTEA,
    error_stage     VARCHAR(32)  NOT NULL,
    error_code      VARCHAR(64)  NOT NULL,
    error_message   TEXT,
    attempt         INTEGER      NOT NULL DEFAULT 0,
    max_attempt     INTEGER      NOT NULL DEFAULT 10,
    next_retry_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    lease_until     TIMESTAMPTZ,
    last_error_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  rt_retry_task        IS '重试任务（应用侧重试的唯一持久载体）';
COMMENT ON COLUMN rt_retry_task.status IS 'PENDING/RUNNING/SUCCEEDED/DLQ/CANCELLED';

-- 到期扫描：仅覆盖待处理，索引体积小
CREATE INDEX idx_retry_due ON rt_retry_task (next_retry_at) WHERE status = 'PENDING';
-- 幂等去重：同一 route 下同一 message 不重复入队（仅约束活跃状态）
CREATE UNIQUE INDEX uk_retry_active ON rt_retry_task (route_id, message_id)
    WHERE status IN ('PENDING', 'RUNNING');
-- 租约超时回收
CREATE INDEX idx_retry_lease ON rt_retry_task (lease_until) WHERE status = 'RUNNING';

-- 错误记录 / DLQ 终态（PRD §31 + §23）
CREATE TABLE rt_error_record (
    id              BIGSERIAL    PRIMARY KEY,
    route_id        BIGINT       NOT NULL,
    config_version  INTEGER,
    message_id      VARCHAR(128) NOT NULL,
    topic           VARCHAR(256),
    tag             VARCHAR(256),
    payload         BYTEA,
    error_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    error_stage     VARCHAR(32)  NOT NULL,
    error_code      VARCHAR(64)  NOT NULL,
    error_message   TEXT,
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    is_final        BOOLEAN      NOT NULL DEFAULT false,
    replayed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  rt_error_record          IS '错误元数据；is_final = true 表示 DLQ 终态';
COMMENT ON COLUMN rt_error_record.is_final IS 'true = 已达最大重试次数，进入 DLQ';

CREATE INDEX idx_error_route_time ON rt_error_record (route_id, error_time DESC);
CREATE INDEX idx_error_message    ON rt_error_record (message_id);
CREATE INDEX idx_error_final      ON rt_error_record (route_id) WHERE is_final;

-- ============================================================================
-- 三、留存域 raw_*
-- ============================================================================

-- 按 receive_time 日分区（PRD §32）
-- 注意：分区表主键必须包含分区键，故主键为 (id, receive_time)
CREATE TABLE raw_message (
    id              BIGSERIAL    NOT NULL,
    message_id      VARCHAR(128) NOT NULL,
    route_id        BIGINT       NOT NULL,
    topic           VARCHAR(256) NOT NULL,
    tag             VARCHAR(256),
    receive_time    TIMESTAMPTZ  NOT NULL,
    config_version  INTEGER,
    -- 解析成功写 payload(JSONB)，失败时只写 payload_raw
    payload         JSONB,
    payload_raw     TEXT,
    PRIMARY KEY (id, receive_time)
) PARTITION BY RANGE (receive_time);

COMMENT ON TABLE raw_message IS '原始消息留存，按 receive_time 日分区，TTL 通过 DETACH+DROP 实现';

CREATE INDEX idx_raw_message_id ON raw_message (message_id);
CREATE INDEX idx_raw_route_time ON raw_message (route_id, receive_time DESC);

-- DEFAULT 分区兜底，防止分区缺失导致插入失败
CREATE TABLE raw_message_default PARTITION OF raw_message DEFAULT;

-- ============================================================================
-- 四、运行期辅助视图
-- ============================================================================

CREATE VIEW v_retry_backlog AS
SELECT route_id,
       status,
       count(*)                       AS task_count,
       min(next_retry_at)             AS oldest_due,
       max(attempt)                   AS max_attempt_seen
FROM rt_retry_task
WHERE status IN ('PENDING', 'RUNNING')
GROUP BY route_id, status;

COMMENT ON VIEW v_retry_backlog IS '重试积压概览，供 Console Dashboard 使用';
