# mqs-pg 优化方案（第二轮）

> 状态：**待确认**（确认后按里程碑顺序实施）
> 制定日期：2026-10-20
> 上一轮：A1 / B1–B3 / C1–C3 / D1 / D2 已交付，D3 已实现（见 `evolution.md`）
> 关联文档：`README.md`、`tech-design.md`、`prd.md`、`AGENTS.md`

本文件同时是**第一轮方案的归档**（§3 记录已交付与遗留项）与**第二轮的施工图**（§5 起）。
第一轮的任务明细已无施工价值，只保留结论与遗留；历史改动逐条在 `evolution.md`。

---

## 0. 本轮结论先行

第一轮做的是「让它跑起来能被看见」：幂等入队、指标、健康指示器、MDC、N+1、Testcontainers、CI。
这些都已落地。本轮通读全仓后，**真正会伤人的问题集中在四类**，且都不是性能问题：

1. **控制台页面正在显示错误的信息**（前端字段名与后端返回体不一致），运维照它做判断会误判；
2. **配置域的并发写没有保护**：同时点两次「激活」可以得到两个 ACTIVE 版本；
3. **重试任务会因为版本/路由被删而永久卡死**——不消耗次数、进不了 DLQ，只增不减；
4. **失败路径会把内部细节直接吐给调用方**（SQL、连接串、堆栈消息），而服务本身无认证。

因此本轮的优先级是 **正确性 > 可自愈 > 校验前置 > 性能 > 工程化 > 前端体验**，
与第一轮「观测先行」的顺序相反：观测已经有了，现在缺的是被观测到的那些坑。

**建议先做的三件事**（按下表顺序 K1 → G1 → G3）：

| 顺序 | 任务 | 理由 |
| --- | --- | --- |
| 1 | **K1** 前端契约对齐 | 半天工作量，直接消除「总览页永远是 0」这类会让人误判的假象 |
| 2 | **G1** 激活加行锁 + 状态条件更新 | 数据模型层面的错，越晚修代价越大（多实例部署后无法靠重启纠正） |
| 3 | **G3** 重试任务断头路 | 唯一会「稳定泄漏资源」的缺陷：任务永久 PENDING，任务表只增不减 |

---

## 1. 目标与原则

1. **先修「错的」，再修「慢的」**：本轮不追求吞吐提升，除非它能消除积压泄漏。
2. **失败必须可自愈**：任何「等下一次重启就好」的设计都算缺陷（第一轮已踩过：`refreshAll` 只在启动期跑）。
3. **错误信息分层**：对外可读且不含内部细节，对内（日志）保留完整上下文与追踪 ID。
4. **不破坏既有不变量**（见 §6），所有改动必须能被现有 52 个用例（含 6 个 Testcontainers 集成类）回归。
5. **小步交付**：一个任务一次提交，逐条写 `evolution.md`（背景 / 改动 / 验证 / 影响与回滚）。
6. **不引入不可回退的重构**：前端拆文件、SQL 复用等，保持在「改坏了能单独 revert」的粒度。

---

## 2. 范围

### 2.1 纳入范围

| 里程碑 | 主题 | 任务数 | 说明 |
| --- | --- | --- | --- |
| **G** | 正确性（并发、状态机、生命周期） | 7 | 会产生错数据或永久泄漏的缺陷 |
| **H** | 可观测性与自愈 | 7 | 失败后能自己恢复，且不再泄露内部信息 |
| **I** | 校验前置与配置安全 | 8 | 把运行时才炸的错误挪到发布前 |
| **J** | 性能与容量 | 7 | 沿用第一轮 C4，并补上分页/索引/热路径分配 |
| **K** | 前端 | 8 | 契约错位、刷新语义、构建体积、可维护性 |
| **L** | 工程化与交付 | 6 | 第一轮 E1/E2/E3/E4 的遗留 |
| **M** | 测试补充（集成，非单元） | 5 | 让 G 里程碑的修复有回归网 |

### 2.2 排除范围（沿用第一轮决策，及原因）

| 排除项 | 原因 |
| --- | --- |
| 纯逻辑单元测试（`MergeSqlBuilder` / `TypeConverter` / `JsonPaths` / `ExpressionEvaluator` / `PasswordCodec`） | 用户明确排除「单元测试」类工作 |
| 控制台鉴权 / 前端 token 落地 | 用户明确排除「鉴权相关」 |
| Actuator 端点暴露面收敛 | 与鉴权方案强绑定，留待鉴权专题 |
| `PasswordCodec` 缺密钥时的明文回落（`{noop}`） | 用户明确排除「存明文」 |
| `E5` JaCoCo 覆盖率 | 第一轮已决策推迟 |
| 平台 MQS 实现、Dry Run、Topic 下拉、界面化 JSLT 编辑器 | README 已列为独立待接入功能，不属于「优化」 |

> **与安全评审的边界**：本轮通读确认了「全站无认证」「`/api/datasources/test` 接受任意 jdbcUrl」
> 这两条 P0 风险（详见 §9 决策记录 Q1/Q2）。它们落在被排除的鉴权范围内，
> 因此**本方案只记录不实施**；若用户希望现在处理，可按 §9 的最小缓解方案单独排期。

---

## 3. 第一轮交付与遗留（不再重做）

| 任务 | 结论 | 证据 |
| --- | --- | --- |
| A1 重试入队幂等 | ✅ 已交付 | `RtRetryTaskMapper.xml#upsertActive`，`RetryService.java:121` |
| B1 Micrometer 业务指标 | ✅ 已交付 | `metrics/MqsMetrics.java`，各组件 `@PostConstruct` 注册 gauge |
| B2 自定义健康指示器 | ✅ 已交付 | `metrics/MqsPgHealthIndicator.java`（组件名 `mqsPg`） |
| B3 MDC 关联日志 | ✅ 已交付 | `common/logging/MdcScope.java` + `application.yml:69-72` 的 pattern |
| C1 列表接口消除 N+1 | ✅ 已交付 | `ConfigService.java:71-82`、`ConfigAdminService.java:178-188` |
| C2 `nextVersionNumber` 聚合 | ✅ 已交付 | `ConfigAdminService.java:580-588`（`max(version)`） |
| C3 留存超大报文跳过解析 | ✅ 已交付 | `RawStore.java:110-124`（`payloadParseMaxBytes`，默认 1 MiB） |
| **C4 重试路径攒批** | ❌ **未做**（第一轮约定「D1 之后」） | 本轮 **J1** |
| D1 集成测试 Testcontainers | ✅ 已交付 | `support/AbstractPgIntegrationTest.java`（单例 `postgres:18`） |
| D2 CI | ✅ 已交付 | `.github/workflows/ci.yml`（`mvn verify` + `pnpm build`） |
| D3 e2e 脚本参数化 | ✅ 已交付（脚本参数化 + 运行隔离，见 `evolution.md`） | 未在本机跑通：Docker 未启动，运行期行为待补验证 |
| E1 Spotless / ESLint | ❌ 未做 | 本轮 **L1** |
| E2 Dockerfile / compose | ❌ 未做 | 本轮 **L2** |
| E3 OpenAPI | ❌ 未做（条件：Boot 4 兼容） | 本轮 **L4** |
| E4 生产配置样例 | ❌ 未做 | 本轮 **L3** |
| E5 JaCoCo | ⏸ 已决策推迟 | — |
| F1 前端 ESLint/Prettier | ❌ 未做 | 合并进 **L1** |
| F2 前端 composable 抽取 | ❌ 未做 | 本轮 **K5** |
| F3 TypeScript 渐进迁移 | ⏸ 本轮只做 `jsconfig.json`（**L5**） | — |

---

## 4. 里程碑总览

| 里程碑 | 任务顺序 | 依赖 | 建议阶段 |
| --- | --- | --- | --- |
| **G** | G1 → G3 → G2 → G4 → G5 → G6 → G7 | G4 需 `V2` 迁移（与 I7、J6 索引同一文件） | 1 |
| **H** | H1 → H2 → H4 → H3 → H5 → H6 → H7 | H1 独立，H3 依赖 H2 的 `cachedSummary` | 1（与 G 并行） |
| **I** | I1 → I2 → I3 → I4 → I5 → I6 → I7 → I8 | I7 需 `V2` 迁移；其余互不依赖 | 2 |
| **K** | K1 → K3 → K2 → K4 → K7 → K5 → K6 → K8 | K1 独立，可最先做；K5 必须排在 K2/K4 之后 | 2（与 I 并行） |
| **J** | J1 → J2 → J3 → J5 → J6 → J4 → J7 | J2 必须紧跟 J1；J6 需 `V2` | 3 |
| **M** | M1/M2/M3/M4/M5 | 跟随对应 G/I/H 任务落地 | 随阶段 |
| **L** | L5 → L1 → L2 → L3 → L4 → L6 | L1 需先补 `lint`/`format` 脚本 | 4 |

> **`V2` 迁移的合并策略**：G4（草稿部分唯一索引）、I7（四条 status CHECK）、J6（两个错误流水索引）
> 互不冲突，建议合成一个 `V2__constraints_and_indexes.sql` 一次交付，减少一次 Flyway 往返与一次
> 「迁移失败要回滚版本号」的运维成本。若阶段间隔较久，也可拆成 `V2`/`V3` —— 但**不要**改写已应用的迁移。

---

## 5. 任务明细

### 里程碑 G：正确性

#### G1 并发「激活」会产生两个 ACTIVE 版本

- **现状 / 证据**：`config/service/ConfigService.java:150-189`。`activate` 先读 `route.activeVersion()`
  （:160）作为 `previous`，再把 `previous` 降级为 INACTIVE（:164-168）、把目标版本置 ACTIVE（:170-174）、
  最后写回 `cfg_route.active_version`（:176-180）。全程没有行锁，`cfg_version` 也没有
  「每个 route 至多一行 ACTIVE」的约束（`V1__init_schema.sql:60-69` 只有 `UNIQUE(route_id, version)`，
  `:74` 的 `idx_cfg_version_status` 是普通索引）。
- **为什么是问题**：两个请求同时激活 v2 / v3，都读到 `previous=1`。T1 降级 v1 并置 v2 ACTIVE，
  T2 同样降级 v1 并置 v3 ACTIVE。结果是 `cfg_version` 里 v2、v3 **同时为 ACTIVE**，
  而 `cfg_route.active_version` 只保留最后写入者。`deleteVersion` 的 ACTIVE 保护
  （`ConfigAdminService.java:484-487`）从此有一个版本保护不到。
- **方案**
  1. `activate` 开头 `SELECT ... FROM cfg_route WHERE id = ? FOR UPDATE`（新增 `CfgRouteMapper#selectForUpdate`），
     把「读 previous → 降级 → 激活 → 写回」整体串行化；
  2. 降级语句加 `eq(CfgVersion::getStatus, "ACTIVE")` 条件（兜住历史脏数据）；
  3. 激活语句加 `eq(CfgVersion::getStatus, v.getStatus())` 条件并校验影响行数 = 1，
     非 1 行抛「状态已被并发修改，请刷新后重试」；
  4. **顺带修 `rollback` 的同类窗口**：`ConfigAdminService.java:504-515` 先 `selectList` 挑候选、
     再调 `activate`，两步之间候选可能被删除或改状态（结果是「配置版本不存在」，看起来像用户点错了）。
     行锁落在 `activate` 里之后，把候选查询也挪到同一锁内（或改为按 `version` 单行
     `SELECT ... FOR UPDATE` 后再判状态）。
- **验收**：新增集成用例（**M1**）——两线程同时 `activate(v2)` / `activate(v3)`，
  断言 `SELECT count(*) FROM cfg_version WHERE route_id=? AND status='ACTIVE'` 恒为 1。
- **风险 / 回滚**：低。加锁只影响写路径，不改变 ACK 与写入链路；回滚即去掉 `selectForUpdate` 与条件。

#### G2 发布/保存的状态更新不加条件，会互相覆盖

- **现状 / 证据**
  - `ConfigAdminService.java:464-467`：publish 用 `eq(CfgVersion::getId)` 更新为 PUBLISHED，不看当前状态；
  - `ConfigAdminService.java:398-411`：saveDraft 无条件 `patch.setStatus("DRAFT")`；
  - `ConfigAdminService.java:452-467`：先读 `v`、再按 `v.getContent()` 校验、最后按 id 更新 —— 中间没有行锁。
- **为什么是问题**：① activate 把某版本置 ACTIVE 之后，一次迟到的 publish 会把它改回 PUBLISHED，
  于是「现役版本状态不是 ACTIVE」，`deleteVersion` 的保护失效；② 校验与更新之间可被并发 saveDraft 改写
  content，等于**把未校验的内容置为已发布**。
- **方案**：publish 的更新改为 `.eq(id).eq(status, 原状态)` 并断言影响行数 = 1（否则抛并发冲突）；
  saveDraft 只在确知 `status=DRAFT` 时写状态；`requireVersion` 在 publish 路径改用 `FOR UPDATE` 读。
- **验收**：新增集成用例（**M2**）——并发 publish + saveDraft，断言「PUBLISHED 版本的 content 必等于被校验的那份」；
  手工：版本处于 ACTIVE 时调用 publish，返回 400 而非静默改状态。
- **风险 / 回滚**：低。纯写路径条件化。

#### G3 版本/路由被删后，重试任务永久卡死（不消耗次数、不进 DLQ）

- **现状 / 证据**
  - `deleteVersion` 只拦 ACTIVE 与当前生效版本（`ConfigAdminService.java:477-490`），不查重试任务；
    `deleteRoute(force)` 只查 PENDING/RUNNING 数量（:325-342），不阻止删除；
  - 重放按**任务绑定的版本**取配置（`retry/RetryReplayer.java:40-51`），取不到返回 `configMissing`；
  - 调度器把它当系统级问题（`retry/RetryScheduler.java:88-92` → `releaseForRetry`），
    而 `releaseForRetry` **不递增 attempt**（`retry/RetryService.java:207-219`）。
- **为什么是问题**：一条绑定 v3 的任务，v3 被删后每轮重放都失败、`next_retry_at` 只推后
  `3 × pollInterval`，永远不消耗次数、永远进不了 DLQ。任务表只增不减，且每分钟刷一条 warn。
  这是本轮唯一「稳定泄漏资源」的缺陷。
- **方案**
  1. `ReplayOutcome.Kind` 增加 `CONFIG_GONE`（语义：绑定的配置**永久**不可用），与 `PG_FAILURE`
     （临时性）区分：`RetryReplayer` 在 `registry.get(...) == null`（而非抛异常）时返回 `CONFIG_GONE`；
  2. 调度器对 `CONFIG_GONE` 走**有限次**缓冲：允许连续 N 次（配置项，默认 3）不消耗次数，
     超过即 `markExhausted` 进 DLQ 并记 `rt_error_record`（`error_code=CONFIG_GONE`），
     缓冲计数落在 `rt_retry_task.error_message` 前缀或新增 `config_missing_count` 列；
  3. `deleteVersion` / `deleteRoute(force)` 增加预检：存在引用该版本的活跃重试任务时给出计数提示，
     `force=true` 才继续。
- **验收**：新增集成用例（**M3**）——入队一条重试 → 删除其绑定版本 → 断言在 N 轮内进入 DLQ
  （而非无限 PENDING），且 `rt_error_record` 有一条 `is_final=true`。
- **风险 / 中**。改变了「配置缺失」的语义边界：必须确保**临时**的注册表读失败（DB 抖动）
  仍走 `PG_FAILURE`/`releaseForRetry`，否则数据库维护窗口会把消息推进 DLQ。
  → 实施时把「读失败」与「确实不存在」用两次判定区分（先 `registry.get`，为 null 再查一次 `cfg_version`）。
- **回滚**：`RetryScheduler` 的分支恢复为单一 `CONFIG_MISSING → releaseForRetry`。

#### G4 「每个路由至多一个草稿」只有代码约束，没有数据库约束

- **现状 / 证据**：`ConfigAdminService.java:416-422`（`createVersion` 里 `findDraft` 判空后插入）、
  `:389-393`（`saveDraft` 同样先判空）、`:590-595`（`findDraft` 无 order/limit，取 `drafts.get(0)`）；
  DDL 无部分唯一索引（`V1__init_schema.sql:60-74` 只有 `UNIQUE(route_id, version)` 与普通索引）。
- **为什么是问题**：并发 `saveDraft` / `createVersion` 都能通过判空，各插一行 DRAFT；
  此后 `getDraft` 命中哪一行不确定，页面可能反复丢失编辑内容。
- **方案**：新增迁移（合入 §4 所述 `V2`）：
  `CREATE UNIQUE INDEX uk_version_draft ON cfg_version (route_id) WHERE status = 'DRAFT';`
  `findDraft` 补 `orderByDesc(version).last("LIMIT 1")`；插入撞索引时转换为可读提示「该路由已有草稿」。
- **验收**：迁移在 Testcontainers 上通过（Flyway 空库顺序执行 V1→V2）；
  并发 saveDraft 用例断言 DRAFT 行数 ≤ 1（**M2** 顺带覆盖）。
- **风险 / 低**。若存量库已有重复 DRAFT，迁移会失败 → 迁移前先用一条 `DO` 块或人工清理
  （在 `V2` 里先把重复 DRAFT 降级为 INACTIVE 并留 `change_note`，再建索引）。
- **回滚**：`DROP INDEX uk_version_draft`。

#### G5 `ensureConsumerState` 是读后写，且从不更新 `bind_version`

- **现状 / 证据**：`ConfigService.java:180-191`。`selectById` 判空后 `insert`；`existing != null` 时
  什么也不做。调用点两处：`ConfigAdminService.java:470`（publish）、`ConfigService.java:182`（activate）。
- **为什么是问题**：① 并发 publish 同路由会撞 `rt_consumer_state` 主键（`V1__init_schema.sql:84`），
  异常经 `GlobalExceptionHandler` 变成一句数据库原文（叠加上 H4 之前，用户看不懂发生了什么）；
  ② 字段叫 `bind_version` 但只有首次插入时写入（`:187`），之后再激活新版本不会更新，
  与注释 `:186`「等待 Consumer 启动」所暗示的语义不符。
- **方案**：改为 `INSERT ... ON CONFLICT (route_id) DO UPDATE SET bind_version = EXCLUDED.bind_version, updated_at = now()`
  （新增 mapper 方法）；并在 publish 里去掉这次调用，只在 activate 落运行态（publish 不改变运行状态）。
- **验收**：`ConfigAdminTest` 现有发布/激活用例仍绿；新增一条断言：激活 v2 后
  `rt_consumer_state.bind_version = 2`。
- **风险 / 低**。
- **备注**：`RtConsumerState` 的 `bind_version` 每次状态迁移都会被 `registry.activeVersion` 覆盖
  （`ConsumerStateRepository.java:70-99`，调用点 `RouteConsumer.java:237,255`），
  所以本任务要同时**明确字段含义**：定为「最近一次状态变更时所处的 active 版本」，
  并把类注释与 DDL 注释改成一致表述（避免下一个人再误读成「消息绑定版本」）。

#### G6 在事务内关闭目标库连接池，会打断在途 MERGE

- **现状 / 证据**：`ConfigAdminService.java:154`（`updateDatasource` 内）、`:170`（`deleteDatasource` 内）
  调用 `dataSources.invalidate(id)`；实现 `writer/datasource/TargetDataSourceRegistry.java:64-70` 直接 `ds.close()`。
- **为什么是问题**：事务尚未提交就把池关掉，正在该池上执行 MERGE 的消费线程会收到连接中断。
  虽然消息会被重投、不会丢数据，但会产生一批本可避免的 `PG_CONNECTION_ERROR` 与闸门抖动；
  若事务随后回滚，池已销毁而配置没变，要等下一次惰性重建。
- **方案**：把失效动作挪到提交之后 —— 复用已有的 `@TransactionalEventListener(AFTER_COMMIT)` 机制
  （新增 `DatasourceChangedEvent`，由 `ConfigRefreshListener` 或独立监听器处理），
  或就地 `TransactionSynchronizationManager.registerSynchronization(afterCommit -> invalidate(id))`。
- **验收**：手工：在消费持续写入的同时更新数据源，日志中不再出现连接中断；
  `ConfigAdminTest` 的数据源用例仍绿。
- **风险 / 低**。注意 `deleteDatasource` 删除后 `buildPool` 会因 `cfg == null` 抛 `CONFIG_MISSING`，
  这是期望行为（有引用时已被预检拦住）。

#### G7 注册表不校验路由状态，且删除版本后快照不驱逐

- **现状 / 证据**
  - `DbConfigRegistry.java:124-130`（`loadActive`）：只判断 `route == null` 与 `activeVersion == null`，
    **没有判断 `route.status`**。`refreshAll` 过滤了 ACTIVE（:109-110），但 `refresh(routeId)`
    （由 `ROUTE_UPSERTED` 事件触发）没有；
  - `DbConfigRegistry.java:47-48, 94-105`：`snapshots` 只增不删，`refresh` 也不清理。
- **为什么是问题**：① 路由被停用（status 改为非 ACTIVE）后，注册表仍持有其 ACTIVE 快照，
  `activeVersion()` 继续返回版本号，而消费者已被 `ConsumerManager.restart` 停掉 ——
  「注册表认为在跑、实际没在跑」；② 版本删除后旧快照仍驻留，`RetryReplayer` 有时能重放成功、
  有时 `configMissing`，行为不一致（与 G3 叠加后尤其难查）。
- **方案**：`loadActive` 增加 `!"ACTIVE".equals(route.getStatus()) → return null`；
  新增 `ConfigRegistry#evict(routeId, version)`，在 `deleteVersion` / `deleteRoute` 的事务提交后调用
  （与 G6 同一个 AFTER_COMMIT 通道）。
- **验收**：手工：停用路由后 `GET /api/overview` 的 `activeRoutes` 不再计入；
  删除非生效版本后重放该版本的任务应在 G3 的 N 轮内进 DLQ。
- **风险 / 低**。

---

### 里程碑 H：可观测性与自愈

#### H1 `refreshAll` 只在启动期执行，刷新失败后永不自愈

- **现状 / 证据**：`ConfigRefreshListener.java:49-52` 的 catch 注释写「下一次 refreshAll 会纠正」，
  但全仓 `refreshAll()` 的调用点只有 `DbConfigRegistry.java:55`（`@PostConstruct`）与
  `ConsumerManager.java:66`（`ApplicationReadyEvent`）—— 运行期**没有任何周期性刷新**
  （`@Scheduled` 只有 `RawPartitionMaintenance`、`RetryService` 指标刷新、`RetryScheduler`）。
- **为什么是问题**：一次 DB 抖动或 target 被删导致 AFTER_COMMIT 处理失败后，内存注册表与本进程消费者
  会一直停在旧状态直到重启。注释承诺的兜底并不存在 —— 这是典型的「注释撒谎」缺陷。
- **方案**：新增 `@Scheduled(fixedDelayString = "${mqs-pg.registry.refresh-interval:60s}")` 的
  `reconcile()`：轻量对账（比对 `cfg_route` 里 ACTIVE 的 `(id, active_version)` 与内存快照，
  不一致才 `refresh`），默认 60s，可用配置关闭（`0` 或负值视为禁用）。
- **验收**：手工：手工改库把某路由 active_version 改掉（模拟事件丢失），断言 60s 内注册表跟上；
  日志出现一条 `注册表对账: 修正 N 条`。
- **风险 / 低**。为免每 60s 打一次全表，用一条 `SELECT id, active_version FROM cfg_route WHERE status='ACTIVE'`
  的轻查询即可，不要调用 `refreshAll`（它会逐条读 version + target）。

#### H2 健康探针每次都在打数据库

- **现状 / 证据**：`metrics/MqsPgHealthIndicator.java:86-89` 直接调 `retryService.summary()`，
  而后者是一次 `GROUP BY status`（`RetryService.java:296-313`）。
- **为什么是问题**：K8s / 负载均衡通常每 5–10s 探一次 `/actuator/health`，每次探测都产生一次聚合查询；
  更严重的是 DB 不可用时 health 会变慢甚至挂住（虽有 catch，但超时不可控），
  而 `RetryService` 已经有一套 15s 刷新的内存 gauge（`:51`、`:60-81`）—— 明明有缓存却没用。
- **方案**：`RetryService` 暴露只读的 `cachedSummary()`（返回 `backlogGauges` 的当前值 + 最近一次刷新时间），
  健康指示器改用该值；积压数据过期超过阈值（如 3 × refresh-interval）时把 `retryBacklog` 明细标为
  `stale=true`，但**不因此判 DOWN**。
- **验收**：`/actuator/health` 响应时间与 DB 负载不再随探测频率上升；DB 断开时 health 仍秒回。
- **风险 / 低**。健康判定从「实时」变「最多 15s 陈旧」，对本项目可接受（阈值是万级积压）。

#### H3 `/api/pg/health` 探的是配置库，且把异常原文回吐

- **现状 / 证据**：`console/controller/ConsoleController.java:82-93` 用配置库 `JdbcTemplate` 执行
  `SELECT version()` / `SELECT now()`，`catch` 后把 `e.getMessage()` 塞进 `detail`。
- **为什么是问题**：① 控制台这张卡叫「PG 健康」，运维默认理解为**目标库**，实际探的是配置库 ——
  目标库挂了这张卡仍是「正常」；② 与 `PgHealthGate` 的真实状态（各数据源闸门）完全脱节；
  ③ 异常原文（可能含连接串）直接返回前端。
- **方案**：改为聚合视图：`configDb`（配置库探活）+ `targets`（`healthGate.snapshot()` 各数据源闸门）
  + `healthy`（两者皆正常才 true）。失败原因统一为固定文案 + 日志留全量（追踪 ID 见 H4）。
  保留 `ApiResponse.ok` 包装（这是「探测结果」而非「接口失败」），并在前端契约里写清。
- **验收**：停掉目标库后该接口 `healthy=false` 且 `targets` 显示具体数据源；配置库挂掉时接口仍返回结构。
- **风险 / 低**。前端需同步（**K1** 一起改）。

#### H4 异常映射：泄露内部信息、FATAL 也返回 400、缺 400 分支

- **现状 / 证据**：`console/GlobalExceptionHandler.java`
  - :45-50 兜底分支返回 `e.getMessage()`（可能含 SQL / 连接串 / 类名）；
  - :39-42 把 `getMostSpecificCause().getMessage()`（含约束名与 Key 值）返回前端；
  - :17-22 所有 `ProcessingException` 一律 400，而 `ErrorCode.CONFIG_MISSING` / `PG_CONNECTION_ERROR`
    的严重度是 FATAL（`common/error/ErrorCode.java:33,38`）；
  - 缺 `HttpMessageNotReadableException`（非法 JSON）与 `MethodArgumentTypeMismatchException`
    （`/api/routes/abc`）分支 → 都落 500 + Jackson 原文。
- **方案**
  1. 对外统一 `message` = 可读中文 + `traceId`（新字段或 `code` 后缀），内部细节只进日志（`log.error` 带同一 traceId）；
  2. **先拆错误码，再按码映射状态码**。现状 `CONFIG_MISSING` 严重度是 FATAL（`ErrorCode.java:38`），
     但它被当成「万能入参错误」用了几十处（「路由不存在」「名称已存在」「不可编辑」……），
     直接按 FATAL→503 会让所有表单校验变成 503。因此先新增 `NOT_FOUND` / `INVALID_INPUT`
     两个码（`ErrorSeverity.DATA`），把校验类调用点迁移过去；之后再映射：
     `PG_CONNECTION_ERROR` / `PG_TIMEOUT`（真 FATAL）→ 503，`INVALID_INPUT`/`DATA` → 400，
     `NOT_FOUND` → 404；
  3. 补两个 handler 返回 400 + 中文（「请求体不是合法 JSON」「路径参数类型错误」）；
  4. `RetryController` 的 `NOT_FOUND` 不再用 200（`RetryController.java:55-62,70-78,80-89`），
     统一抛 `ProcessingException(NOT_FOUND)`。
- **验收**：手工用非法 JSON、非法路径参数、PG 断开三种场景调用，返回体中文可读且不含 SQL；
  日志里能按 traceId 找到完整堆栈。
- **风险 / 低**。会改动前端依赖的提示文案 → 与 **K3**（保留 code）一起上线，避免前端靠中文子串判断。

#### H5 拿不到目标数据源时用空 `JdbcTemplate` 探测，且 `datasourceId` 可能为 null

- **现状 / 证据**：`consumer/ConsumerManager.java:100-113`（`probeJdbc = new JdbcTemplate()`，无 DataSource）；
  `consumer/RouteConsumer.java:229-246`（`probeJdbc.queryForObject`）、`:259-262`
  （`datasourceId()` 在 `registry.active(routeId) == null` 时返回 null）；
  `consumer/PgHealthGate.java:45-48, 62-63`（`ConcurrentHashMap` 的 key 为 null 会抛 NPE）。
- **为什么是问题**：`tryRecover` 的 catch 里调用 `healthGate.recordFailure(datasourceId(), e)`，
  一旦 `datasourceId()` 为 null，`states.computeIfAbsent(null, ...)` 抛 NPE；
  该 NPE 发生在 catch 块内、会逃出 `tryRecover`，被外层 `catch (Throwable)`（`RouteConsumer.java:135-137`）捕获 →
  **消费线程永久退出**并只留一条 ERROR。概率低，但后果是「静默停一个路由」。
- **方案**：① `startRoute` 拿不到 cfg 或 dataSource 时**不提交消费者**，只把状态置 PAUSED 并记录原因，
  由 H1 的对账 + 闸门恢复后重新拉起；② `RouteConsumer.datasourceId()` 返回 `long`（未知用 `-1`），
  `PgHealthGate` 显式拒绝 null（`Objects.requireNonNull` + 文档）；
  ③ 删掉空 `JdbcTemplate` 这个「必然失败的探测器」。
- **验收**：手工把 `cfg_datasource` 的 URL 改成不可达，重启后路由状态为 PAUSED 且原因可读，
  不会出现线程消失；恢复 URL 后由闸门探测自动 RUNNING。
- **风险 / 低**。改变「数据源不可用时也提交消费者」的行为，需确认没有测试依赖它。

#### H6 关键失败路径的日志不足以排障

- **现状 / 证据**
  - `ConfigAdminService.java:460-461`：发布校验失败只记「问题数」，不记 `issues` 内容；
  - `RetryService.java:77-80`：指标刷新失败是 `log.debug`（生产默认 INFO 下等于静默）；
  - `ConfigRefreshListener.java:49-52`：只记 routeId 与 change，不记失败发生在哪一步。
- **方案**：校验失败把前 5 条 issues 的 `field + message` 一并记录（`warn`）；
  指标刷新失败提到 `warn` 并计入一个新计数 `mqspg.metrics.refresh.failed`；
  AFTER_COMMIT 失败按 `change` 类型拆日志（refresh 失败 / stop 失败 / restart 失败）。
- **验收**：构造一次校验失败与一次刷新失败，日志能直接定位到列与步骤。
- **风险 / 低**。

#### H7 没有 liveness / readiness 分组

- **现状 / 证据**：`application.yml:53-60` 暴露了 `health,info,metrics,prometheus`，
  但没有 `management.endpoint.health.group.*` —— 只有单一 `/actuator/health`。
- **为什么是问题**：`mqsPg` 指示器当前把「消费闸门关闭」「积压超限」都算进同一个 `health` 状态
  （`MqsPgHealthIndicator.java:120-123`，闸门关闭 → `DOWN`，积压超限 → `OUT_OF_SERVICE`）。
  若 K8s 用同一个端点做 liveness，一次 PG 抖动就会让**整个 Pod 被重启**（重启并不能修 PG，反而丢掉内存缓冲）。
- **方案**：加两个分组：`liveness` 只含 `ping`/`diskSpace`（进程存活），`readiness` 含 `db` + `mqsPg`
  （能否接流量）。这不涉及端点暴露面收敛，不与被排除的鉴权工作绑定。
- **验收**：`/actuator/health/liveness` 与 `/readiness` 分别返回，闸门关闭时只有 readiness 变化。
- **风险 / 低**。

---

### 里程碑 I：校验前置与配置安全

#### I1 引入了 `starter-validation` 却完全没有使用

- **现状 / 证据**：`backend/pom.xml:38-41` 有 `spring-boot-starter-validation`；
  全仓 `@Valid|@Validated|@NotNull|@Size|@Positive` **零命中**；
  `config/dto/RouteRequest.java:11-12`、`TargetRequest.java:6-12`、`DatasourceRequest.java:11-16` 全是裸 record；
  控制器均无 `@Valid`（如 `RouteController.java:59-62`）。长度校验全靠手工 `requireXxxFields`
  （`ConfigAdminService.java:770-820` 的三个 `requireRouteFields/requireTargetFields/requireDatasourceFields`），
  而这些方法只判空。
- **为什么是问题**：`topic` 超 256、`datasource.name` 超 64 会直撞 `VARCHAR(256)` / `VARCHAR(64)`
  （`V1__init_schema.sql:14,47`），用户看到的是 SQLSTATE 22001 原文（叠加上 H4 之前还会 500）。
- **方案**：DTO 加 `@NotBlank/@Size/@Positive/@Pattern`，控制器加 `@Valid`；
  `GlobalExceptionHandler` 增加 `MethodArgumentNotValidException` → 400 + 字段级中文提示
  （`errors` 列表，前端可在表单下方展示）；手工 `require*` 只保留跨字段/跨表校验。
- **验收**：超长/缺字段请求返回 400 且 message 指明字段；现有 `ConfigAdminTest` 全绿。
- **风险 / 低**。注意**不要**因为加注解把原本允许为空的字段变成必填（逐个对照 DDL 可空性）。

#### I2 `validateJslt` 是死代码，JSLT 语法错误只能等第一次消费

- **现状 / 证据**：`transform/TransformEngine.java:139-149` 定义了 `validateJslt`（注释就写着
  「供配置发布时做 JSLT 语法预校验」），但全仓**没有任何调用者**；
  `config/service/ConfigValidator.java:89-132` 的 `validate()` 只校验策略、映射、Upsert Key、
  `updateTimeField`、NOT NULL 覆盖，从不编译 JSLT。
- **为什么是问题**：这类错误只在第一条消息到达时以 `JSLT_ERROR` 进 DLQ ——
  「发布前拦截」的设计目标（`ConfigValidator.java:32-46` 注释）没达成，而且坏配置已经上线。
- **方案**：在 `validate()` 里读 `content.jslt`，非空则调用 `transformEngine.validateJslt`，
  异常转 `ValidationIssueDto.error("jslt", ...)`；`ConfigValidator` 注入 `TransformEngine`（已是 Spring bean，
  注意不要形成循环依赖 —— 若形成，改为注入 `JsltCompiler` 之类的窄接口）。
- **验收**：故意写坏 jslt 后 publish 返回 valid=false 且 issue 指向 `jslt`；现有用例全绿。
- **风险 / 低**。JSLT 编译有一定开销（毫秒级），只在 publish/validate 路径调用，不进热路径。

#### I3 部分唯一索引被误判为满足 MERGE 的唯一性要求

- **现状 / 证据**：`writer/metadata/TargetMetadataReader.java:49-59` 的 `INDEXES_SQL`
  只筛 `i.indisprimary OR i.indisunique`，**未排除 `i.indpred IS NOT NULL`**（部分索引）；
  判定 `writer/metadata/TargetTableMeta.java:54-57`（列集合相等即通过）；使用点 `ConfigValidator.java:246-250`。
- **为什么是问题**：目标表上 `CREATE UNIQUE INDEX ... WHERE deleted = false` 时，校验会放行；
  但运行期 MERGE 的 ON 条件不带该谓词，可能匹配多行 → SQLSTATE 21000 cardinality violation →
  整块进二分隔离。这正是发布校验要防的错误，却被校验放过了。
- **方案**：SQL 增 `AND i.indpred IS NULL`；对只存在部分唯一索引的情况单独报
  `ValidationIssueDto.warn("upsertKeys", "仅存在部分唯一索引，不构成 MERGE 的唯一性保证")`，
  让用户知道为什么不能发布（error 或 warn 二选一，建议 error 并说明原因）。
- **验收**：新增集成用例（**M4**）——目标表只建部分唯一索引时 publish 必须失败。
- **风险 / 低**。可能让某些「以前能发布」的配置失败 —— 这正是目的，需在 `evolution.md` 写明。

#### I4 `schema` / `table` 标识符不做白名单校验，直接拼进 MERGE 文本

- **现状 / 证据**：写入侧只 `trim` / lowercase（`ConfigAdminService.java:206`、`:236` 调 `normalizeSchema`，
  定义在 `:877-879`；`table_name` 甚至只做 `trim`，`normalizeSchema` 之外没有别的规范化）；
  使用侧直接拼字符串
  （`writer/sql/MergeSqlBuilder.java:54`、`:68`、`:73`、`:82`、`:95`、`:102`、`:107-114`、`:120`）。
  对比：同类防护在结构探查里是有的（`IntrospectionService.java:197-205` 的 `requireIdentifying`，
  调用点 `:98`、`:103-104`），但它只覆盖探查入参，不覆盖落库路径。
- **为什么是问题**：`createTarget` 不校验表是否存在，可存下含引号/空格/分号的 schema/table；
  一旦该表真实存在（元数据查询走参数绑定，不会拦住名字本身），MERGE 的 SQL 文本即被污染。
  注入面被「列必须真实存在」部分收窄，但 `schema.table` 位置没有这层保护 ——
  而 `requireIdentifying` 自己的注释（`:193-195`）已经说明了这个位置为什么危险。
- **方案**：新增 `common/sql/Identifiers#requireValid(String)`：`[A-Za-z_][A-Za-z0-9_$]*` 且长度 ≤ 63；
  在 `createTarget/updateTarget` 与 `MergeSqlBuilder.build`（防守式）双端调用。
- **验收**：`schema = "public;DROP"` 在创建时返回 400；现有写入用例全绿。
- **风险 / 低**。可能拒绝合法的引号标识符（PG 允许 `"my table"`）——本项目不支持，需在文档写明。

#### I5 `poolConfig` 是裸 `Object`，拼错的键静默失效

- **现状 / 证据**：`config/dto/DatasourceRequest.java:16`（`Object poolConfig`，整个 record 只有 5 个字段）→
  `ConfigAdminService.java:148-150` 直接 `JsonNodes.toJsonNode` 落库 →
  `writer/datasource/TargetDataSourceRegistry.java:94-115` 的 `applyPoolConfig` 运行时才
  `log.warn("忽略未知的连接池参数")`；
  且 `poolConfig == null` 时 `updateById` 跳过字段（`:151`），**无法把 poolConfig 清空回 `{}`**。
- **方案**：定义 `PoolConfig` record（`Integer maximumPoolSize/minimumIdle`、`Long connectionTimeout/idleTimeout/maxLifetime`，
  全部可空）做反序列化校验，非法值在写接口返回 400；清空语义用显式 `{}` 表达
  （`LambdaUpdateWrapper.set` 写 JSONB 需用实体 + `updateById`，见 AGENTS.md 的 JSONB 陷阱）。
- **验收**：写入未知键 `poolconfig`（拼错）返回 400 并列出允许的键；清空后 `cfg_datasource.pool_config = {}`。
- **风险 / 低**。需保留 `applyPoolConfig` 作为最后一道防线（存量脏数据）。

#### I6 `updateTimeField` 默认值把错误推迟到 publish

- **现状 / 证据**：`ConfigAdminService.java:209`、`:239`（`defaultIfBlank(req.updateTimeField(), "update_time")`）；
  目标表没有该列时创建静默成功，直到 `ConfigValidator.java:259-262` 才报错。
- **方案**：`createTarget/updateTarget` 内顺带做一次元数据校验（复用 `metadataReader.get(ref)`，
  它本来就被 `updateTarget` 用于失效），列不存在直接 400 并列出可用列名。
- **验收**：创建目标表时给一个不存在的 `updateTimeField`，立即 400。
- **风险 / 低**。代价是创建时多一次元数据查询（会缓存，不影响热路径）。

#### I7 状态列没有 CHECK 约束，拼错状态会静默漏掉整行

- **现状 / 证据**：`V1__init_schema.sql:51`（`cfg_route.status`，注释声明 `DRAFT/VALIDATING/VALID/PUBLISHED/ACTIVE/INACTIVE`）、
  `:64`（`cfg_version.status`）、`:111`（`rt_retry_task.status`）、`:85`（`rt_consumer_state.status`）均无 CHECK；
  字面量散落在 `ConfigService.java:55-56`（`ACTIVATABLE` 白名单）、`ConfigAdminService.java:281,406,562` 与 `:603-605`、
  `RetryService.java:176-189`、`ConsumerStateRepository.java:70-99` 等处。
- **为什么是问题**：拼错的状态值不会报错，只会让按状态过滤的查询静默漏掉该行
  （`DbConfigRegistry.java:109-110`、`ConsumerManager.java:72-73,128`）——
  表现就是「路由配了但不消费」，正是本项目最怕的静默失败类型。
- **方案**：新增迁移 `V2`（与 G4 同一文件）加四个 `CHECK (status IN (...))`；
  Java 侧抽 `Statuses` 常量类（不引入 enum 列映射，避免一次性大改），至少让字面量只有一处定义。
- **验收**：直接 `UPDATE ... SET status='RUNNIG'` 被数据库拒绝；现有用例全绿。
- **风险 / 低**。迁移前需确认存量值都在集合内（`V2` 里先 `SELECT` 校验失败则中断）。

#### I8 Flyway `baseline-on-migrate: true` 会在非空库上静默跳过 V1

- **现状 / 证据**：`application.yml:34`。
- **为什么是问题**：指向「已有表但没有 `flyway_schema_history`」的库时，Flyway 会把现状 baseline 为 V1
  而**不执行** `V1__init_schema.sql`，随后表缺失在首次查询才暴露 —— 与 AGENTS.md 记录的
  「Flyway 静默不建表」是同一类失败。
- **方案**：生产 profile 置 `false`（写进 L3 的 `application-prod.yml.example`）；
  本地/dev 保留 `true` 以免影响现有开发流程；若确实要支持存量库，改为显式
  `baseline-version` + 手工建表脚本，不用自动 baseline。
- **验收**：对空库正常建表；对「有表无 history」的库，生产配置下启动直接失败并给出可读提示。
- **风险 / 低**（仅配置样例，不改默认值）。

---

### 里程碑 J：性能与容量

#### J1 重试路径攒批（第一轮 C4 遗留）

- **现状 / 证据**：`retry/RetryReplayer.java:70-72` 每条任务调用
  `writer.write(cfg, List.of(new FoldedRecord(...)))`（逐条 MERGE，每次重新拼 SQL）；
  `retry/RetryScheduler.java:57-59` 顺序处理最多 `batch-size=200` 条。
- **为什么是问题**：积压上千条时，同样的 MERGE 语句被拼 1000 次、执行 1000 次，
  每次一个往返；`PgWriter` 的分块能力（`write-chunk-size: 200`）完全没用上。
- **方案**
  1. 调度器把本轮领取的任务按 `(routeId, configVersion)` 分组；
  2. 组内逐条 `transformEngine.transform` 得到 `TargetRow`，复用 `BatchFolder` 折叠后**一次** `writer.write`；
  3. 用 `MergeResult` 把每条任务映射回结果：`SUCCESS`（含 `SKIPPED_OLD_VERSION`）/
     `ROW_FAILURE`（该 key 在 failures 里）/ `DATA_ERROR`（转换就失败，不进 MERGE）/
     `PG_FAILURE`（整批 aborted → 全部 `releaseForRetry`）；
  4. 组间顺序执行（不并发），避免目标库连接池（`maximumPoolSize=4`）被重试抢占。
- **验收**：积压 200 条时 MERGE 次数从 200 降到 ≤ 2（按 200 块）；
  **语义不变**：每条任务的 `attempt` 与 DLQ 阈值独立（用 `M3` 与 `RetrySchedulerTest` 现有 8 个用例回归）。
- **风险 / 中**。折叠只允许发生在**同一路由同一版本**内（否则会用错配置解释旧数据）；
  `FoldedRecord.sources()` 为空是重放路径的前提（见 `RetryReplayer.java:28-29` 注释），
  改成批量后仍需保证「每条任务独立计数」—— 建议保留 `FoldedRecord` 的 `sources` 为空，
  另用 `Map<KeyTuple, List<RtRetryTask>>` 维持任务与 key 的对应关系。
- **回滚**：恢复逐条 `replay` 分支。

#### J2 批量领取的租约时长与批量大小不匹配

- **现状 / 证据**：`RetryScheduler.java:50-51`
  `claimDue(batchSize, Math.max(1, props.getLeaseDuration().toSeconds()))`；
  `lease-duration: 5m`（`application.yml:103`），`batch-size: 200`。
- **为什么是问题**：领取时给**整批**打上同一个 5 分钟租约，然后顺序处理 200 条。
  若目标库慢（连接超时 5s/条），200 条最坏超过 1000s ≫ 300s 租约：
  `reclaimExpiredLeases`（`RetryScheduler.java:45-48`）会把仍在处理的任务重新放回 PENDING，
  同一任务被**重复重放**（`uk_retry_active` 只约束 PENDING/RUNNING 的并存，不能防重放）。
- **方案**：① 租约改为「按批大小动态计算」：`max(leaseDuration, batchSize × 预估单条耗时)`，
  预估单条耗时给一个保守配置（默认 2s）；② 更稳的做法是**每处理完 n 条续租一次**
  （`UPDATE rt_retry_task SET lease_until = now() + ... WHERE id IN (...) AND status='RUNNING'`），
  续租失败即停止本轮。
- **验收**：手工把目标库连接超时压到极小 + 造 200 条积压，断言日志中不再出现
  「回收了 N 个租约过期的重试任务」与同一 `message_id` 的重复重放。
- **风险 / 中**。续租涉及一次额外批量 UPDATE；必须保证只在 `status='RUNNING'` 上续租，
  否则会把已完成的任意行误标。
- **建议**：**J1 与 J2 同批实施**，否则 J1 让单轮耗时变长会放大本问题。

#### J3 每个 chunk 重新拼一遍 MERGE SQL，没有语句复用

- **现状 / 证据**
  - `writer/sql/MergeSqlBuilder.java:44-120`：按 `chunk.size()` 生成
    `USING (VALUES (?,?),(?,?),...)`（`:52-64` 双重循环拼文本 + `:48` 每块新建 `ArrayList`），
    行数不同则 SQL 文本不同；
  - `writer/PgWriter.java:101-123`（`executeChunk`）每块都重新 `sqlBuilder.build(...)`，
    `:118` 再 `compiled.params().toArray()` 全量拷一份参数。
- **为什么是问题**：200 行 × N 列的语句文本每次都要拼一遍（字符串与 `ArrayList` 分配），
  且 PG 侧每个不同文本都是一份新的解析/规划（pgjdbc 的 `PreparedStatement` 缓存按 SQL 文本命中，
  行数一变就 miss）。这是写入热路径上最大的一处无效开销。
- **方案（按性价比排序，建议先做 1）**
  1. **按行数缓存 SQL 模板**：`(target, columns, upsertKeys, updateTimeField, rowCount)` → SQL 文本，
     用 `ConcurrentHashMap` 缓存（行数通常只有 200 与尾块几种），参数仍按块绑定；
  2. 或改用 `unnest(?::type[], ?::type[], ...)` 单行源：SQL 文本**固定**，
     参数是数组（`createArrayOf`），彻底消除「行数决定文本」的问题，但需要改参数绑定与类型转换，
     风险更高；
  3. 顺带把 `TargetRow#asMap()` 的不可变拷贝从每行多次调用改为 chunk 级取一次列清单
     （`MergeSqlBuilder.build` 里 `sample.asMap().keySet()` 只调一次）。
- **验收**：微基准或日志耗时：同规模批次（如 5 万条）写入总耗时下降；SQL 文本缓存命中率可观测
  （新增 gauge `mqspg.write.sql.cache.size`）。
- **风险 / 中**。缓存 key 必须包含**全部**影响 SQL 文本的要素（列顺序、类型、守卫字段、行数），
  漏一个就会产生错误 SQL —— 建议 key 用 `record` 而不是拼接字符串。
- **红线**：只允许带单调守卫的 `WHEN MATCHED`（见 §6），改 SQL 形态时不能顺手"简化"。

#### J4 写入热路径的重复分配

- **现状 / 证据**：`writer/PgWriter.java:110-113`（RETURNING 结果逐行 `new ArrayList<>(keyCount)`）、
  `:118`（`compiled.params().toArray()` 每块一次全量拷贝）、
  `writer/sql/MergeSqlBuilder.java:48`（每块新建参数 `ArrayList`）。
- **为什么是问题**：单条消息路径上已经有 3–4 次可避免的拷贝；在 1000 条/批的规模下是纯浪费，
  且会加重 GC（虚拟线程下 GC 停顿影响所有路由）。
- **方案**：`toArray()` 改为 `params.toArray(Object[]::new)` 一次成型；RETURNING 解析改用
  定长数组 + `KeyTuple` 的重载（避免每行一个 `ArrayList`）；参数 `ArrayList` 按块大小预分配（已是）。
- **验收**：同 J3 的耗时对比；`PgWriterTest` 4 个用例全绿。
- **风险 / 低**（纯局部微优化，不改语义）。

#### J5 列表接口无分页 / 在内存里排序

- **现状 / 证据**：`ConfigService.java:106-119`（`listVersions` 全量查回后在 Java 里
  `sort(Comparator...)`）、`:71-82`（`listRoutes` 全量）、
  `ConsoleController.java:59-63`（`rt_consumer_state` 全表）。
- **为什么是问题**：`cfg_version` 随每次发版线性增长，`getRoute`（`ConfigService.java:91`）
  每次都把全部版本读进内存；路由/版本量到几百后列表接口明显变慢。
- **方案**：`listVersions` 改为 SQL `ORDER BY version DESC` + 可选 `limit/offset`
  （前端版本抽屉默认显示最近 50 个，其余按需加载）；`listRoutes` 加 `limit/offset`（默认 100，
  但首页总览仍需要全量计数 → 用一条 `count(*)` 代替「全量查回再 size()」）。
- **验收**：1000 个版本时 `GET /api/routes/{id}` 响应时间不再随版本数线性增长。
- **风险 / 低**。前端要配合「加载更多」（**K5** 版本抽屉改造时一起做）。

#### J6 错误流水的索引与深分页

- **现状 / 证据**：`ConsoleController.java:108-121`（`is_final` / `error_stage` / `message_id`
  三条件 + `ORDER BY id DESC` + OFFSET）；DDL `V1__init_schema.sql:151-153` 只有
  `(route_id, error_time DESC)`、`(message_id)`、`(route_id) WHERE is_final`。
- **为什么是问题**：`error_stage` 无索引；`is_final` 的索引形状（带 route_id）与查询谓词不匹配；
  深分页每页都要扫过前 N 页。另外 `offset = Math.max(0, page - 1) * size`
  （`ConsoleController.java:105-106`、`:137-138`）在 `page = Integer.MAX_VALUE` 时整数溢出为负 →
  SQL 报错 500。
- **方案**：`V2` 迁移补 `(is_final, id DESC)`、`(error_stage, id DESC)`；
  分页改 keyset（`WHERE id < ?cursor ORDER BY id DESC LIMIT ?`，前端传上一页最后一条的 id）；
  offset 计算改 `Math.multiplyExact` 或先 clamp `page`。
- **验收**：100 万行错误流水下，翻页耗时稳定；`page=2147483647` 返回 400 而非 500。
- **风险 / 低**（keyset 分页需要前端配合，可先做索引 + 溢出修复，keyset 放后）。

#### J7 JSLT / 表达式缓存无上限

- **现状 / 证据**：`transform/TransformEngine.java:41` 的 `jsltCache` 注释已声明「不淘汰」。
- **为什么是问题**：key 是脚本全文，每改一次草稿的 jslt 就新增一项；长期运行 + 频繁改配置会缓慢增长。
- **方案**：给缓存加容量上限（手写 LRU 即可，或引入 Caffeine —— 需评估新增依赖，倾向手写
  `LinkedHashMap` + `removeEldestEntry`，上限 512）。
- **验收**：连续写入 1000 个不同 jslt 后，缓存 size 不超过 512 且最旧的被淘汰。
- **风险 / 低**。

---

### 里程碑 K：前端

> 前端所有结论均已在 `frontend/src` 全量核对，并与后端 DTO / 枚举交叉验证。
> 构建体积数据来自本地 `frontend/dist/assets`（未入库，仅作量级参考）。

#### K1 页面显示的字段与后端返回体不一致（P0）

- **现状 / 证据**
  1. `views/dashboard/index.vue:12-15,20-21,28` 用 `summary.pending/running/dlq/succeeded`（小写驼峰），
     后端 `retry/RetryService.java:302-311` 返回的是大写键 `PENDING/RUNNING/SUCCEEDED/DLQ/CANCELLED`
     → 总览页四张卡片**恒为 0**，哪怕队列已积压；
  2. `views/dashboard/index.vue:58-61` 的表格列 `receivedTotal/ackedTotal/failedTotal/lastError`
     在 `console/controller/ConsoleController.java:65-75` 的返回体里**不存在**
     （实际是 `routeId/state/reason/bindVersion/lastErrorAt/lastSuccessAt/updatedAt`）→ 四列恒空白；
  3. `views/errors/index.vue:61` 写死 `['CONSUME','TRANSFORM','WRITE','RETRY','ACK']`，
     而 `common/error/ErrorStage.java:5-13` 是 `RECEIVE/PARSE/JSLT/PATH/CONVERT/EXPRESSION/VALIDATE/WRITE/RETRY`
     → 5 个筛选项里 3 个永远查不到数据，而 `RECEIVE/PARSE/JSLT` 这些高发阶段反而无法筛选。
- **为什么是问题**：这是一块运维看板。显示「0 积压」而非真实积压，比报错更危险 ——
  没有任何信号提示它在撒谎。
- **方案**
  1. dashboard 统一在 `api/` 层做归一化（把 `summary` 的键转小写），页面只认一种形状；
  2. 表格列改成后端真实字段：状态、原因（`reason`）、`bindVersion`、`lastErrorAt`、`lastSuccessAt`；
     「已接收/已 ACK/失败」若确实要展示，由后端在 `/api/consumer/state` 补字段
     （`RouteConsumer` 已有 `receivedCount/ackedCount/noAckCount`，`MockController.java:71-73` 已在用）；
  3. errors 的阶段下拉改为后端真实枚举；更稳妥是新增只读接口 `GET /api/meta/error-stages`
     返回枚举（**需后端小改**），前端不再硬编码；
  4. 顺带修正 `stateTagType`（`dashboard/index.vue:84-89`）的状态集合 ——
     它认 `PAUSED/BACKOFF/STOPPED`，而后端真实状态是 `RUNNING/PAUSED/RECOVERING/ERROR`
     （`V1__init_schema.sql:94` 的注释）；`ERROR` 会落到 `danger` 是巧合正确，
     `BACKOFF`/`STOPPED` 则根本不存在。`routes/index.vue:514-519` 的 `consumerTagType` 才是对的。
- **验收**：造一条 PENDING 重试任务，总览页「待重试」显示 1；错误页按 `PARSE` 能筛出数据。
- **风险 / 低**。第 2 条若选择「后端补计数」，需注意计数在内存里，重启归零 → 建议同时标注为「本次启动以来」。

#### K2 `keep-alive` 让所有页面只在首次进入时取数

- **现状 / 证据**：`layout/components/AppMain.vue:4-6`（`<keep-alive :max="10">`）；
  6 个视图全部只在 `onMounted` 拉一次数据（`dashboard:109`、`routes:993`、`datasources:278`、
  `retry:184`、`errors:90`、`raw:110`），全仓无 `onActivated` / `onDeactivated`，
  且 `setInterval|setTimeout|onUnmounted` **零命中**。
- **为什么是问题**：从「总览」切到「重试队列」再切回，看到的是首次进入时的快照。
  对运维面板是实质性缺陷（用户以为看到的是现在）。
- **方案**：关键页（总览、重试、错误、原始留存）改用 `onActivated` 取数（它首次挂载也会触发，
  因此可删掉重复的 `onMounted`）；总览页加**可选**轮询（30s，开关在页面右上角），
  定时器在 `onActivated` 启动、`onDeactivated` + `onUnmounted` 双清理（缓存组件不会卸载，
  只写 `onUnmounted` 会漏）。
- **验收**：切走再切回，总览数据是新的；离开页面后不再发请求（Network 面板确认）。
- **风险 / 低**。注意轮询与 `loading` 的竞态：轮询刷新不应把整页打成 loading 骨架。

#### K3 确认框未 catch 取消，且错误码被丢弃导致靠中文子串判断

- **现状 / 证据**
  - 全仓 9 处 `await ElMessageBox.confirm(...)`：7 处**直接裸调**且未 `.catch()` ——
    `routes/index.vue:699,755,768,780`、`retry/index.vue:167,178`、`datasources/index.vue:263`
    → 点「取消」时拒绝沿点击处理器冒泡，Vue 记一条 unhandled rejection
    （唯一被保护的是 `routes:714`，它套在 `try { … } catch { return }` 里；`api/request.js:48` 属 401 分支，另行处理）；
  - `api/request.js:40-41` 业务失败只抛 `new Error(message)`，丢掉 `code`；
    于是 `routes/index.vue:710` 用中文子串 `String(e?.message).includes('重试任务')`
    判断是否走「强制删除」二次确认 —— 后端文案一改（`ConfigAdminService.java:333-335`），流程静默失效。
- **方案**：封装 `confirmAction(message, title)`（内部 catch 后返回 false）；
  `request.js` 抛错时带上 `err.code = body.code`、`err.payload = body`；
  `routes/index.vue` 改判错误码（后端为「存在未完成重试任务」定义专用 code，
  与 **H4** 的 code 规范一起定）。
- **验收**：点「取消」后控制台无 unhandled rejection；改后端文案不影响强制删除流程。
- **风险 / 低**。

#### K4 映射编辑器与路由对话框共用 `columns`，且先开框后取数

- **现状 / 证据**：`routes/index.vue:440`（单个 `columns` ref）被 `loadColumns()`（:558-575，
  路由表单）与 `loadColumnsFor()`（:869-875，映射编辑器）同时写入；
  两个 `el-dialog` 可同时打开，后写者覆盖前者 → 映射编辑器里出现**另一张表的列**，
  进而可能写出非法 target。另外 `openMappings`（:847-867）在 :850 先 `mappingVisible = true` 再 await
  （:852 `getRoute`、:859 `getDraft` 都在显示之后），任一请求失败就留下一个空对话框，
  且 `getDraft` 失败没有兜底（`loadColumnsFor` 的失败反倒被 :856 吃掉了）。
- **方案**：拆成 `formColumns` / `mappingColumns` 两个 ref；`openMappings` 改为「取数成功后再显示」，
  失败时提示并保持关闭；映射对话框内容加独立 loading。
- **验收**：两框同开时各自的列互不污染；断开后端后点「映射」不会留下空框。
- **风险 / 低**。

#### K5 `routes/index.vue` 近千行，是 K4 这类竞态的温床

- **现状 / 证据**：单文件 997 行，承载路由列表、新建/编辑对话框、版本抽屉、映射编辑器（7 列表格 +
  9 种 `transformTypes` 下拉，仅 `timestamp`/`enum` 有专属参数区）与 3 个 API 模块的编排
  （`api/routes`、`api/datasources`、`api/targets`，共 19 个函数）；且「草稿 → 编辑器状态」的水合逻辑在
  `:859-866`（`openMappings`）与 `:902-909`（`onAutoGenerate`）逐字重复，
  `:931-936`（`persistDraft` 保存后回写）是第三次近似赋值。
- **方案**：按职责拆成 `RoutesTable.vue` / `RouteFormDialog.vue` / `VersionDrawer.vue` /
  `MappingEditor.vue` + `composables/useRouteDraft.js`（水合 + 保存）；
  抽 `applyDraft(d)` 消除三处重复；顺带把状态→标签色的映射收敛到 `constants/status.js`
  （`dashboard:84-89` 的 `stateTagType`、`routes:514-526` 的 `consumerTagType`/`versionTagType`、
  `retry:132-145` 的 `tagType`，共 4 个函数）。
- **验收**：功能不变（逐页对照手测）；单文件不超过 ~300 行。
- **风险 / 中**（纯前端重构，无自动化测试兜底 —— 必须手测全部 CRUD 路径；
  建议 `pnpm build` 与逐页手测通过后再提交，且单独一次提交便于 revert）。
- **建议顺序**：K1/K3/K4 先做（都是小改），K5 放最后，避免在重构期间叠加功能修改。

#### K6 首屏体积 ~1.2 MB JS / ~364 KB CSS，未拆包

- **现状 / 证据**：`main.js:3` 全量 `import ElementPlus`、`:7` 全量样式、`:16-18` 遍历
  `ElementPlusIconsVue` 注册**全部**图标（注释自认「图标用量不大」）；
  `vite.config.js:5-22` 无 `manualChunks`、无 `sourcemap`/`drop` 配置、无 `base`。
- **方案**
  1. 图标改为显式 import（现有页面已这么写，说明全量注册无必要）；
  2. 引入 `unplugin-vue-components` + `unplugin-auto-import` 做 Element Plus 按需引入
     （**需先 `pnpm add -D`**，属新增依赖，需在 `pnpm-workspace.yaml` 的 `allowBuilds` 里确认无新 postinstall）；
  3. `build.rollupOptions.manualChunks` 拆 `vue` / `element` 两个 vendor chunk；
  4. `build: { sourcemap: false, reportCompressedSize: true }`，生产 `esbuild.drop: ['console','debugger']`；
  5. 加 `server.strictPort: true`（现在是静默换端口，与文档里 5173 的假设不符）。
- **验收**：`pnpm build` 产物体积下降（记录改前/改后数值到 `evolution.md`）；
  页面功能不变。
- **风险 / 中**（按需引入可能导致某些组件/指令样式缺失，需逐页目测；
  建议保留一次「全量引入」的回滚点）。

#### K7 请求失败时页面静默显示旧数据

- **现状 / 证据**：`errors/index.vue:68-83`、`raw/index.vue:84-98`、`routes/index.vue:501-508`、
  `datasources/index.vue:145-152` 的 `load()` 只有 `try/finally`，**没有 catch**；
  `retry/index.vue:150-158` 用 `Promise.all`，任一失败会丢掉已成功的列表数据。
- **为什么是问题**：后端不可达时，页面表现为「没有数据」而不是「加载失败」——
  与 K1 同类：让人误判。
- **方案**：统一 `load()` 的错误分支置 `error` ref 并用 `<el-alert>` 明示；
  `retry` 改 `Promise.allSettled`（与 `dashboard:94` 已有写法一致）；
  筛选条件变化时重置 `query.page = 1`（`errors:66`、`raw:68` 现在不重置，
  在第 3 页切筛选常常得到空表）。
- **验收**：停掉后端后每页都有明确失败提示，不展示上一次的数据。
- **风险 / 低**。

#### K8 其余小项（一次性清理）

| 项 | 证据 | 处理 |
| --- | --- | --- |
| PG 健康三态错误 | `dashboard/index.vue:81`（`healthy !== false`，请求失败也显示「正常」） | 改为「未知/正常/异常」三态，随 K1 的 H3 新返回体一起改 |
| 版本抽屉「刷新」按钮语义错 | `routes/index.vue:196` 复用 `openVersions(current)` | 改 `@click="reloadVersions"`（该函数已存在，:733-740） |
| `limit` 输入框改了不生效 | `retry/index.vue:8`（无 `@change`，同栏 `status` 有） | 补 `@change`，或改成固定档位下拉 |
| 死代码 | `api/targets.js:5`（`listTargets`）与 `:17`（`deleteTarget`）全仓零调用；`api/datasources.js:9`（`getDatasource`）同样零调用；`store/user.js:16`（`isLoggedIn`）零调用 | 删除或标注「预留」；注意 `createTarget`/`updateTarget` **是在用的**（`routes/index.vue:413`），别一起删 |
| 前端「登录」是空壳 | `store/user.js:18-30`（任意非空凭据即放行、token 为 `local-${Date.now()}`）、`router/index.js:102-111`（只看 localStorage 有没有 token）、`request.js:16-25`（带了后端也不读的 `X-Token`） | 保留现状即可（与 §9 Q1 的鉴权专题一起做），但在 README / 本文件写明「这不是鉴权，只是界面占位」，避免被当成安全边界 |
| API 文件按域划分错误 | `api/overview.js:17-24` 混装 raw / errors | 拆 `raw.js` / `errors.js` |
| `index.html` 缺 favicon / description | `index.html:1-12`，无 `public/` | 补齐；同时确认 `lang="zh-CN"`（已有） |
| 空表头 | `routes/index.vue:373-379`（`label=""`） | 补「操作」或 `aria-label` |

---

### 里程碑 L：工程化与交付

#### L1 格式与静态检查接入 CI（第一轮 E1 + F1）

- **现状 / 证据**：`backend/pom.xml` 无 spotless/checkstyle；`frontend/package.json:7-11` 只有
  `dev`/`build`/`preview`（共 3 个，不含 lint）；全仓无 `eslint.config.*` / `.prettierrc*` / `.editorconfig`；
  `.github/workflows/ci.yml:44-69` 前端只跑 `pnpm install --frozen-lockfile && pnpm build`。
- **方案**
  1. 后端：`spotless-maven-plugin`（`palantir-java-format` 或 `google-java-format`，二选一）
     + `mvn spotless:apply` 一次性格式化，再在 `verify` 阶段加 `spotless:check`；
  2. 前端：`pnpm add -D eslint eslint-plugin-vue prettier` 并**新增** `"lint": "eslint src --max-warnings 0"`
     与 `"format": "prettier --write src"` 脚本（当前不存在，文档里不要写成已有命令）；
  3. CI 增加 `pnpm lint` 步骤。
- **验收**：`mvn verify` 与 `pnpm lint` 全绿；故意写一个格式错误能被拦下。
- **风险 / 低**，但**格式化会产生巨大 diff**：必须**单独一次提交**（只含格式化），
  且先把 `.git-blame-ignore-revs` 配好，否则 `git blame` 全废。
- **顺序**：在这一轮其它后端改动**之前**做，可以避免后续每次改动都夹带格式噪声。

#### L2 容器化一键起（第一轮 E2）

- **现状 / 证据**：仓库无 `Dockerfile`、无 `docker-compose.yml`。
- **方案**：`backend/Dockerfile`（多阶段：`maven:3.9-eclipse-temurin-21` 构建 → `eclipse-temurin:21-jre` 运行，
  非 root 用户，`JAVA_OPTS` 可注入）；`frontend/Dockerfile`（`node:22` 构建 → `nginx:alpine` 静态托管 +
  反代 `/api`）；根目录 `docker-compose.yml`（`postgres:18` + backend + frontend，健康检查与 `depends_on`）。
- **验收**：`docker compose up` 后 `http://localhost:8080/api/overview` 与前端页面可用；
  e2e 脚本用 `-TargetJdbcUrl jdbc:postgresql://postgres:5432/postgres`（后端容器视角的服务名）跑通（D3 已支持）；
- **风险 / 中**。注意后端容器内 `localhost` 不是 PG（必须用服务名），这正是 D3 参数化的由来；
  另需设置 `MQS_PG_DB_URL` 指向 `postgres` 服务。

#### L3 生产配置样例 + 环境变量清单（第一轮 E4）

- **现状 / 证据**：只有 `application.yml`（默认值面向本地 Docker，密码默认 `123456`，
  `logging.level.com.mqspg: DEBUG`，`management.endpoint.health.show-details: always`）。
- **方案**：新增 `backend/src/main/resources/application-prod.yml.example`：
  `logging.level.root: INFO`（去掉 `com.mqspg: DEBUG`）、`show-details: when-authorized`、
  `flyway.baseline-on-migrate: false`（**I8**）、`mqs-pg.crypto.key: ${MQS_PG_CRYPTO_KEY}`（无默认值）、
  数据源全部走环境变量；README 增加「生产必需环境变量」表。
- **验收**：用该样例启动（本地改成本机 PG）无警告缺失；README 表格与样例键名一一对应。
- **风险 / 低**。样例文件不参与打包（`*.example` 需确认不被 `resources` 过滤规则误打进去）。

#### L4 OpenAPI（第一轮 E3，条件性）

- **现状 / 证据**：`pom.xml` 无 springdoc；Boot 4 生态兼容性未验证。
- **方案**：先用 `pnpm`/`mvn` 侧一条命令确认 `springdoc-openapi-starter-webmvc-ui` 是否有 Boot 4 兼容版本
  （第一轮决策：**没有就跳过，不做静态清单替代**）。若有：加依赖 + `@Operation`/`@Schema`
  少量注解 + `/swagger-ui.html` 可达。
- **验收**：Swagger UI 能列出全部 `/api` 端点并可试调。
- **风险 / 低**（若跳过则无风险，但需在本文档标注「已决策跳过」）。

#### L5 编辑器路径跳转 + 构建产物守卫

- **现状 / 证据**：无 `jsconfig.json` / `tsconfig.json`；`vite.config.js:8-10` 的 `@/` 别名只对 Vite 生效；
  CI 无体积阈值。
- **方案**：加 `frontend/jsconfig.json`（`compilerOptions.paths: {"@/*": ["src/*"]}`，零构建改动）；
  CI 在 `pnpm build` 后断言 `dist/assets/*.js` 总体积不超过阈值（配合 K6 记录的新基线，
  先用脚本打印、稳定后再改成失败阈值）。
- **验收**：VS Code 里 `@/api/routes` 可 ctrl+click；超阈值时 CI 失败。
- **风险 / 低**。

#### L6 文档与代码同步（本轮必须做，别留烂账）

- **现状 / 证据**
  - 已在**本次方案提交中一并修掉**的两处过时表述（留档以免被重新引入；行号为修复前的旧值）：
    `README.md:349` 的「集成测试容器化。当前测试直连本地 PostgreSQL，计划迁移到 Testcontainers」
    （D1 早已完成），以及 `:331` 的进度表「A、B、C 已交付，D1 完成」（与 `:340-342` 的列表重复且不含 D2）；
  - 仍然存在的一致性风险：`README.md` / `AGENTS.md` / 本文件三处都描述了「测试怎么跑、用例多少个、
    哪些里程碑完成」，任何一处更新都可能落下另两处（`AGENTS.md` 已经因为 D1 与 README 出现过矛盾）。
- **方案**
  1. **单一事实来源**：用例数与通过情况只写在 `evolution.md`（带命令与输出），
     README 只说「怎么跑」和「最近记录在哪」；里程碑完成状态只写在 README 的「当前进度」表，
     本文件 §3 只做归档、不重复维护进度。
  2. 每次改动仍在 `evolution.md` 追加条目（背景 / 改动 / 验证 / 影响与回滚），
     并在提交前对照 §11.2 的清单检查 README 是否需要同步。
- **验收**：README / AGENTS / 本文件三处对「测试怎么跑」与「哪些里程碑完成」的表述不再互相矛盾。
- **风险 / 无**。

---

### 里程碑 M：测试补充（集成测试，不是单元测试）

第一轮已排除「纯逻辑单元测试」，但**并发与生命周期**这类缺陷只能靠集成测试兜住，
且它们正好是本轮 G 里程碑的验收手段。每个用例都应能在 Testcontainers 上独立跑。

| 编号 | 覆盖 | 用例要点 | 对应任务 |
| --- | --- | --- | --- |
| **M1** | 并发激活 | 两线程同时 `activate(v2)/activate(v3)` → `count(status='ACTIVE')` 恒为 1 | G1 |
| **M2** | 并发草稿 + 发布 | 两线程 `saveDraft` → DRAFT 行数 ≤ 1；并发 publish 不得发布未校验内容 | G2、G4 |
| **M3** | 重试断层 | 入队重试 → 删其绑定版本 → N 轮内进 DLQ 且留 `rt_error_record(is_final)` | G3 |
| **M4** | 部分唯一索引 | 目标表只建 `CREATE UNIQUE INDEX ... WHERE ...` → publish 必须失败 | I3 |
| **M5** | 自愈 | 手工改 `cfg_route.active_version` 模拟事件丢失 → 对账任务在 1 个周期内跟上 | H1 |

> 注意：这些用例都必须设 `mqs-pg.consumer.auto-start=false`（沿用 `AbstractPgIntegrationTest` 的模式），
> 避免后台线程与断言抢消息。

---

## 6. 不可触碰的红线

改任何一处都要先读 `tech-design.md` 的对应 ADR，以下六条**任何优化都不能破坏**：

1. **ACK 顺序**：`BEGIN → INSERT 重试任务 → COMMIT → ACK`；`ack(m) ⟹ (PG 已提交) ∨ (重试行已存在)`。
2. **单调守卫**：`MergeSqlBuilder` 中只允许存在**带单调守卫**的 `WHEN MATCHED`，
   绝不能补无守卫的兜底子句（J3 改 SQL 形态时尤其注意）。
3. **版本绑定在接收时刻**：`RouteConsumer.receive` 之后立刻取 ACTIVE 版本并绑到消息上，
   之后发布新版本不影响本批（`RouteConsumer.java:116-121`）。
4. **配置快照不可变**：`cfg_version.content` 只增不改；配置只能走 API `草稿 → 发布 → 激活`。
5. **留存是旁路**：`RawStore` 队列满即丢弃并计数，绝不阻塞消费主链路（`RawStore.java:110-124`）。
6. **分区修复必须一个事务**：DEFAULT 冲突的 detach → create → move → attach
   必须在同一事务内（`RawPartitionMaintenance#repairAndCreate`）。

---

## 7. 排期与依赖

| 阶段 | 内容 | 预估 | 前置 |
| --- | --- | --- | --- |
| 1 | **K1 + K3**（前端契约与错误码，半天见效） | 1 天 | 无 |
| 1 | **G1 + G3**（两个真正的正确性缺陷） | 2–3 天 | 无 |
| 1 | **H1 + H2 + H4**（自愈与不再泄露） | 2 天 | 无 |
| 2 | G2 + G4 + G5 + G6 + G7（含 `V2` 迁移） | 3 天 | G1 |
| 2 | I1 + I2 + I3 + I4（校验前置） | 3 天 | I3 改的是 `TargetMetadataReader` 的元数据 SQL |
| 2 | K2 + K4 + K7（刷新语义与竞态） | 2 天 | K1 |
| 3 | J1 + J2（重试攒批 + 租约，必须同批） | 3 天 | G3、M3 |
| 3 | J3 + J4 + J5 + J6 + J7 | 3–4 天 | 无 |
| 3 | K5 + K6（拆分与体积） | 3 天 | K2/K4 稳定后 |
| 4 | L5 + L1（先路径跳转，再格式化门禁） | 2 天 | 建议在阶段 1 之前做 L1，减少格式噪声 |
| 4 | L2 + L3 + L4 + L6 | 2–3 天 | L1 |
| 随阶段 | M1–M5 | 与对应任务同批 | 对应 G/I/H |

> 排期为粗略估计。**L1（格式化）建议提前到阶段 0**：它会产生一次全仓 diff，
> 越晚做越容易与功能改动混在一起。

---

## 8. 风险与回滚总则

1. **一次提交一件事**：`V2` 迁移、SQL 形态改动（J3）、前端重构（K5）各自独立提交，便于单点 revert。
2. **迁移只增不改**：`V2` 之后不改 `V1`；每个迁移都要能在空库上从 `V1` 顺序执行成功
   （Testcontainers 会验证）。
3. **改语义的改动必须写进 `evolution.md` 的「影响」段**：G3（配置缺失进 DLQ）、I3（部分索引拒绝发布）
   、H5（数据源不可用不再提交消费者）都属于会改变可观察行为的改动，需要明确告知。
4. **可观测改动先上，性能改动后上**：J 系列必须能在 `/actuator/prometheus` 上看到前后差异再判断收益。
5. **验证环境**：本机当前 **Docker Desktop 未运行**，`mvn test`（Testcontainers）与
   `scripts/e2e-config-api.ps1` 都无法执行 —— 起 Docker 后按 §10 跑一遍再合并。
6. **回滚粒度**：配置类改动（I8/L3）可直接改回；数据类改动（`V2` 里的索引与 CHECK）需要显式
   `DROP INDEX` / `ALTER TABLE … DROP CONSTRAINT` 的回滚迁移（写进同一次提交的注释里）。
   注意 `V2` 若已应用到任何环境，**不要**删改文件本身，只追加 `V3` 撤销。

---

## 9. 决策记录与待确认问题

**已沿用（第一轮确认）**

1. A + B 合并交付 —— 已落地，无需再决策。
2. **E3（OpenAPI）**：必须有可交互 Swagger UI；若 springdoc 无 Boot 4 兼容版本则**跳过**（不做静态清单替代）。
3. **E5（JaCoCo）**：推迟。
4. **C4（重试攒批）**：D1 完成后实施 —— D1 已完成，本轮作为 **J1** 排入。

**本轮需用户确认（默认按「不做」处理）**

> 编号用 **Q** 前缀，避免与第一轮的里程碑 D1/D2/D3 混淆。

| 编号 | 问题 | 默认 | 若要做的最小方案 |
| --- | --- | --- | --- |
| **Q1** | 全站无认证，任何匿名请求都能激活配置、改数据源、删路由（`pom.xml` 无 security starter；`MqsPgApplication.java:23-25` 未注册任何过滤器）。是否本轮处理？ | **不做**（落在排除的「鉴权」范围） | 加 `spring-boot-starter-security` + HTTP Basic + 写接口按角色收敛；`/api/mock/**` 在非 dev profile 关闭 |
| **Q2** | `POST /api/datasources/test` 接受任意 `jdbcUrl`，无认证下等于 SSRF / 任意 JDBC 连接器（`DatasourceController.java:70-74` → `IntrospectionService.testCandidate`，每次新建连接池）。是否本轮处理？ | **不做**（同上） | 只允许对已保存数据源试连；候选试连加目标地址白名单 + 并发信号量 + 速率限制 |
| **Q3** | `PasswordCodec.encode` 在未配置密钥时回落 `{noop}` 明文（`common/crypto/PasswordCodec.java:72-81` 打 warn 后返回 `NOOP + plain`；默认 `mqs-pg.crypto.key: ""`，`application.yml:83-84`）。是否改为非 dev 启动即失败？ | **不做**（落在排除的「存明文」范围） | 只加「非 dev 且 key 为空 → 启动失败」，不改存量 `{noop}` 行为；**这属于排除边界，需用户明确点头才做** |
| **Q4** | K1 中「已接收 / 已 ACK / 失败」三列：后端补字段（内存计数，重启归零）还是前端直接删列？ | **后端补字段并标注「本次启动以来」** | 复用 `RouteConsumer` 的 `receivedCount/ackedCount/noAckCount`（`MockController.java:71-73` 已在用） |
| **Q5** | J3 是否采用 `unnest` 方案（SQL 文本完全固定，收益最大但要改参数绑定）？ | **先用「按行数缓存 SQL 模板」**，`unnest` 留作后续评估 | 见 J3 方案 1 vs 2 |

---

## 10. 验证清单（每阶段结束跑一遍）

前置：**Docker Desktop 已启动**（Testcontainers 需要）；本地无需预装 PostgreSQL。

```powershell
# 后端：编译 + 全部集成测试（Testcontainers postgres:18）
cd backend
mvn test

# 单个测试类 / 方法（PowerShell 必须给 -D 参数加引号）
mvn test "-Dtest=PgWriterTest"
mvn test "-Dtest=ConfigAdminTest#并发激活只留一个ACTIVE"

# 前端：构建（当前只有 dev/build/preview 三个脚本，没有 lint / test）
cd frontend
pnpm install
pnpm build

# 端到端：需先启动后端（dev profile 带种子数据）
$env:SPRING_PROFILES_ACTIVE='dev'; mvn spring-boot:run
pwsh -File scripts/e2e-config-api.ps1
```

阶段验收口径：

| 阶段 | 必须全绿 |
| --- | --- |
| 每个任务 | `mvn test`（52 用例，新增 M1–M5 后应只增不减）、`pnpm build` |
| G/H/I 任一阶段结束 | 上面的 + `scripts/e2e-config-api.ps1` |
| J 阶段结束 | 上面的 + `/actuator/prometheus` 中批次耗时/写入指标有可比的前后数据 |
| K 阶段结束 | `pnpm build` + 逐页手测（总览/路由/数据源/重试/错误/留存/mock） |
| L 阶段结束 | `mvn verify`（含 spotless:check）、`pnpm lint`、`docker compose up` 全链路 |

---

## 11. 协作约定

### 11.1 `evolution.md` 记录规范

每完成一个任务，在 `evolution.md` **追加**一节。文件是**倒序**（最新在最上面，紧跟在文件头的说明
与第一条 `---` 之后）；追加意味着把新条目插在顶部，不是追加到文件末尾。

```markdown
## YYYY-MM-DD · <任务编号> <标题>

- **背景**：为什么要做（现象 / 风险 / 证据）
- **改动**：逐文件说明做了什么（`path/to/file`：……）
- **验证**：执行的命令与观察到的结果（不能只写「已测试」）
- **影响与回滚**：对既有语义的影响，如何回退
- **README 更新**：改了哪些小节
```

硬性要求：**「验证」一栏必须写真实执行过的命令**。本机跑不了（如 Docker 未启动）就明确写
「未执行 + 原因 + 待补」，不要凭空写「全部通过」——第一轮的 D1/D2 条目就是这么写的（含命令与输出），
这是本项目排障时最有价值的部分。

### 11.2 `README.md` 更新点

- 命令变化（新增 `pnpm lint`、`docker compose up`、spotless）同步到「快速开始 / 测试」；
- 指标与健康：新增 `mqspg.*` 指标、health 分组（H7）后更新「可观测」一节；
- 测试先决条件：Testcontainers 已落地（**L6** 已在本次提交中删掉那句过时的「集成测试容器化」描述）；
- 生产配置：补环境变量表与 `application-prod.yml.example` 说明（L3）；
- 每完成一个里程碑，更新「当前进度」表并指向本文件。

### 11.3 提交粒度

- **一个任务一次提交**，提交信息用中文 + 任务编号（如 `G1 激活加行锁与状态条件更新`）；
- 格式化（L1）、`V2` 迁移、前端重构（K5）必须各自独立提交，便于单点 revert；
- 改语义的提交（G3 / I3 / H5）在提交信息里写明行为变化，别让下一个人在 `git log` 里猜。
