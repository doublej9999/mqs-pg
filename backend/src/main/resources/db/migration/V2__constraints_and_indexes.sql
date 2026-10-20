-- =====================================================================
-- V2：约束与索引
--
-- 本迁移合并三件事（见 docs/optimization-plan.md §4 的合并策略）：
--   G4 —— 「每个路由至多一个草稿」补数据库约束
--   I7 —— 四个状态列补 CHECK 约束
--   J6 —— 错误流水的索引与查询谓词对齐
--
-- 刻意**不**改写 V1__init_schema.sql：Flyway 的校验和一旦变化，
-- 已经在运行的库会直接拒绝启动。所有变更都以新增迁移的方式叠加。
-- =====================================================================

-- ---------------------------------------------------------------------
-- G4：每个路由至多一个草稿
--
-- 应用层（createVersion / saveDraft 里的 findDraft 判空）只能挡住顺序调用：
-- 并发下两个请求都能通过判空，各插一行 DRAFT，此后 getDraft 命中哪一行不确定，
-- 页面会反复丢失刚编辑的内容。
--
-- 先清理存量重复草稿，否则建索引会失败。保留版本号最大（最新）的那一份，
-- 其余降级为 INACTIVE 并留痕 —— 直接删会丢用户的编辑。
-- ---------------------------------------------------------------------
WITH ranked AS (
    SELECT id,
           row_number() OVER (PARTITION BY route_id ORDER BY version DESC) AS rn
    FROM cfg_version
    WHERE status = 'DRAFT'
)
UPDATE cfg_version v
SET status      = 'INACTIVE',
    change_note = coalesce(v.change_note || ' ', '')
                  || '（V2 迁移：同一路由存在多个草稿，保留版本号最大的那一份，本行被降级）'
FROM ranked r
WHERE v.id = r.id
  AND r.rn > 1;

CREATE UNIQUE INDEX uk_version_draft ON cfg_version (route_id) WHERE status = 'DRAFT';

-- ---------------------------------------------------------------------
-- I7：状态列 CHECK 约束
--
-- 拼错的状态值不会报错，只会让按状态过滤的查询静默漏掉该行
-- （注册表加载、消费者调度都按 status 过滤），表现为「路由配了但不消费」。
-- 这是本项目最怕的静默失败类型，所以在库层拦住。
--
-- 用 NOT VALID + VALIDATE 两步：先加约束（对旧行不校验，只对之后的写入生效），
-- 再单独校验存量。这样迁移在存量数据有问题的库上也能完成第一步，
-- 失败信息指向具体的校验步骤，而不是一句「ALTER TABLE 失败」。
-- ---------------------------------------------------------------------
ALTER TABLE cfg_route
    ADD CONSTRAINT ck_cfg_route_status
        CHECK (status IN ('DRAFT', 'VALIDATING', 'VALID', 'PUBLISHED', 'ACTIVE', 'INACTIVE')) NOT VALID;
ALTER TABLE cfg_route VALIDATE CONSTRAINT ck_cfg_route_status;

ALTER TABLE cfg_version
    ADD CONSTRAINT ck_cfg_version_status
        CHECK (status IN ('DRAFT', 'VALID', 'PUBLISHED', 'ACTIVE', 'INACTIVE')) NOT VALID;
ALTER TABLE cfg_version VALIDATE CONSTRAINT ck_cfg_version_status;

ALTER TABLE rt_consumer_state
    ADD CONSTRAINT ck_rt_consumer_state_status
        CHECK (status IN ('RUNNING', 'PAUSED', 'RECOVERING', 'ERROR')) NOT VALID;
ALTER TABLE rt_consumer_state VALIDATE CONSTRAINT ck_rt_consumer_state_status;

ALTER TABLE rt_retry_task
    ADD CONSTRAINT ck_rt_retry_task_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'DLQ', 'CANCELLED')) NOT VALID;
ALTER TABLE rt_retry_task VALIDATE CONSTRAINT ck_rt_retry_task_status;

-- ---------------------------------------------------------------------
-- J6：错误流水的索引与查询谓词对齐
--
-- V1 只建了 (route_id, error_time DESC)、(message_id)、(route_id) WHERE is_final。
-- 而控制台的查询是「is_final / error_stage / message_id 三个条件 + ORDER BY id DESC」，
-- 现有的 is_final 索引带 route_id 前缀，形状不匹配，用不上。
-- ---------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_error_final_id ON rt_error_record (is_final, id DESC);
CREATE INDEX IF NOT EXISTS idx_error_stage_id ON rt_error_record (error_stage, id DESC);

COMMENT ON INDEX uk_version_draft IS '每个路由至多一个草稿（G4）';
