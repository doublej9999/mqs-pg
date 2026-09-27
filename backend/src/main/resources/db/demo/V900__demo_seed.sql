-- ============================================================================
-- DEMO 数据（仅 dev / local profile 加载）
-- Flyway 位置：classpath:db/demo
-- 版本号取 900+ 与主迁移序列隔离，且保证在所有 V1..Vn 之后执行
--
-- 作用：提供一个可端到端跑通的样板
--   1. 业务目标表 biz_demo.orders（模拟"被同步的目标库表"）
--   2. 一条指向它的 Route / Target / DataSource 配置
--   3. 一个 ACTIVE 的配置版本
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 模拟业务目标表（真实场景中由业务方维护，不在本系统迁移范围内）
-- ---------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS biz_demo;

CREATE TABLE IF NOT EXISTS biz_demo.orders (
    id          BIGINT       NOT NULL PRIMARY KEY,   -- Upsert Key
    name        TEXT,
    amount      NUMERIC(18,2),
    status      INTEGER,
    source      TEXT,
    update_time TIMESTAMPTZ  NOT NULL                -- 单调守卫字段
);

COMMENT ON TABLE biz_demo.orders IS 'DEMO 同步目标表';

-- ---------------------------------------------------------------------------
-- 2. 配置：数据源 / 目标表 / 路由
-- ---------------------------------------------------------------------------
INSERT INTO cfg_datasource (id, name, jdbc_url, username, password_enc, pool_config)
VALUES (
    1,
    'local-pg',
    'jdbc:postgresql://localhost:5432/postgres',
    'postgres',
    '{noop}123456',                 -- dev 专用明文前缀，生产必须使用 {aes}
    '{"maximumPoolSize":10,"minimumIdle":1}'::jsonb
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO cfg_target (id, name, datasource_id, schema_name, table_name, upsert_keys, update_time_field)
VALUES (
    1,
    'biz-demo-orders',
    1,
    'biz_demo',
    'orders',
    '["id"]'::jsonb,
    'update_time'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO cfg_route (id, name, topic, tag, target_id, active_version, status)
VALUES (1, 'order-sync', 'order-topic', 'order.updated', 1, 1, 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. 配置版本快照（ACTIVE）
-- ---------------------------------------------------------------------------
INSERT INTO cfg_version (id, route_id, version, status, content, change_note, created_by, published_at)
VALUES (
    1,
    1,
    1,
    'ACTIVE',
    $json$
    {
      "mappingStrategy": "EXACT",
      "jslt": null,
      "mappings": [
        { "target": "id",          "source": "$.id",        "transform": { "type": "long" },      "required": true },
        { "target": "name",        "source": "$.name",      "transform": { "type": "string" } },
        { "target": "amount",      "source": "$.amount",    "transform": { "type": "decimal" },   "required": true, "defaultValue": "0" },
        { "target": "status",      "source": "$.status",
          "transform": { "type": "enum", "mapping": { "CREATED": 1, "PAID": 2, "CANCELLED": 3 } },
          "defaultValue": "UNKNOWN" },
        { "target": "update_time", "source": "$.updatedAt",
          "transform": { "type": "timestamp", "pattern": "yyyy-MM-dd'T'HH:mm:ssXXX", "zone": "Asia/Shanghai" },
          "required": true },
        { "target": "source",      "constant": "ORDER_SYSTEM" }
      ]
    }
    $json$::jsonb,
    'DEMO 初始版本',
    'system',
    now()
)
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. 修正序列（显式插入 id 后必须同步）
-- ---------------------------------------------------------------------------
SELECT setval(pg_get_serial_sequence('cfg_datasource', 'id'), GREATEST((SELECT max(id) FROM cfg_datasource), 1));
SELECT setval(pg_get_serial_sequence('cfg_target',     'id'), GREATEST((SELECT max(id) FROM cfg_target),     1));
SELECT setval(pg_get_serial_sequence('cfg_route',      'id'), GREATEST((SELECT max(id) FROM cfg_route),      1));
SELECT setval(pg_get_serial_sequence('cfg_version',    'id'), GREATEST((SELECT max(id) FROM cfg_version),    1));

-- ---------------------------------------------------------------------------
-- 5. Consumer 初始状态
-- ---------------------------------------------------------------------------
INSERT INTO rt_consumer_state (route_id, status, reason, bind_version)
VALUES (1, 'PAUSED', 'DEMO 初始状态，等待 MQS 实现接入', 1)
ON CONFLICT (route_id) DO NOTHING;
