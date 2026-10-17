# mqs-pg 优化方案

> 状态：**已确认，实施中**
> 制定日期：2026-10-17
> 确认日期：2026-10-17
> 关联文档：`README.md`、`tech-design.md`、`prd.md`

**已确认的决策**
1. **A + B 合并**为第一阶段交付（仍从 A1 开始按序实施）。
2. **E3**：必须有可交互的 Swagger UI；若 springdoc 无 Boot 4 兼容版本则**跳过 E3**。
3. **E5（JaCoCo）**：推迟，本次不做。
4. **C4**：待 **D1 完成之后**再做。

本方案基于对后端核心链路（`BatchManager` / `PgWriter` / `RouteConsumer` / `RetryScheduler` /
`RawStore` / 配置域与校验）、DDL、测试与构建配置、前端骨架的通读，列出**在现有架构内**
可分步落地的优化项。核心不变量（ACK 顺序、单调守卫、同批折叠、不可变版本快照）不做改变。

---

## 1. 目标与原则

1. **稳定性优先**：先消除会导致消息活锁 / 积压的正确性问题，再谈性能与体验。
2. **可观测可运维**：让系统"跑起来能被看见"——业务指标、健康探针、可关联日志。
3. **不破坏既有语义**：所有改动必须通过现有集成测试与 `scripts/e2e-config-api.ps1`。
4. **小步交付**：每个任务独立可验证，逐条记录到 `evolution.md` 并同步 `README.md`。
5. **每步可回滚**：避免大范围重构，优先局部增强。

---

## 2. 范围

### 2.1 纳入范围

| 编号 | 方向 | 任务 |
| --- | --- | --- |
| A1 | 稳定性 | 重试入队幂等，消除"重投 ↔ 活跃重试任务"唯一索引冲突导致的活锁 |
| B1 | 可观测 | 接入 Micrometer 业务指标（接收/ACK/丢弃/积压/健康/批次耗时） |
| B2 | 可观测 | 自定义 Actuator 健康指示器（数据源闸门、重试积压、raw 丢弃） |
| B3 | 可观测 | 消费/重试入口注入 MDC（routeId / messageId / version），统一日志 |
| C1 | 性能 | 控制台列表接口消除 N+1 查询 |
| C2 | 性能 | `nextVersionNumber` 改为 `SELECT max(version)` |
| C3 | 性能 | Raw 留存避免重复 JSON 解析 + 超大报文保护 |
| C4 | 性能 | 重试路径攒批写入 + 提高调度吞吐 |
| D1 | 交付 | 集成测试迁移到 Testcontainers，去掉对本机 PG / 固定容器名的依赖 |
| D2 | 交付 | 引入 CI（构建 + 测试 + 前端 build） |
| D3 | 交付 | `e2e-config-api.ps1` 参数化（容器名 / 连接串），适配容器化环境 |
| E1 | 工程化 | Java 格式化/静态检查（Spotless）+ 前端 ESLint/Prettier，并接入 CI |
| E2 | 工程化 | Dockerfile + docker-compose（PG + 后端 + 前端一键起） |
| E3 | 工程化 | OpenAPI/接口文档（springdoc，需确认 Boot 4 兼容版本） |
| E4 | 工程化 | 生产配置样例 `application-prod.yml.example` + 环境变量清单 |
| F1 | 前端 | 引入 ESLint/Prettier |
| F2 | 前端 | 抽取轮询 / 异步表格 / 错误处理 composable，消除各视图重复 |
| F3 | 前端（可选） | TypeScript 渐进迁移评估 |

### 2.2 排除范围（本次不做，及原因）

| 排除项 | 原因 |
| --- | --- |
| 纯逻辑单元测试（`MergeSqlBuilder`/`TypeConverter`/`JsonPaths`/`ExpressionEvaluator`/`ConfigValidator`/`PasswordCodec` 等） | 用户指定排除"单元测试"类工作 |
| 控制台鉴权 / 前端 token 占位落地 | 用户指定排除"鉴权相关" |
| Actuator 端点暴露面收敛 | 与鉴权方案强相关，一并留待鉴权专题 |
| `PasswordCodec` 缺密钥时的明文回落（`{noop}`） | 用户指定排除"存明文" |
| `E5` JaCoCo 覆盖率报告与门槛 | 本次决策推迟，暂不纳入 |
| 平台 MQS 实现、Dry Run 预览、Topic 下拉、JSLT 编辑器 | README 已列为独立待接入功能，不属于"优化" |

> 说明：集成测试 / e2e / Testcontainers 属于**验证方式**建设，不在"单元测试"排除范围内，
> 予以保留（D1/D2/D3）。

---

## 3. 里程碑总览

| 里程碑 | 主题 | 任务 | 依赖 | 建议顺序 |
| --- | --- | --- | --- | --- |
| **A** | 稳定性 | A1 | 无 | 1 |
| **B** | 可观测性 | B1 → B2 → B3 | 无 | 2 |
| **C** | 性能 | C1 → C2 → C3 → C4 | 无（C4 建议在 D1 之后验证更充分） | 3 |
| **D** | 可回归与交付 | D1 → D3 → D2 | D1 先于 D2 | 4 |
| **E** | 工程化 | E2 → E1 → E4 → E3 → E5 | E1 与 D2 协同 | 5 |
| **F** | 前端 | F1 → F2 → F3 | 无 | 6 |

> 里程碑 A 规模很小（单任务），已确认与 **里程碑 B 合并**为第一阶段（A1 → B1 → B2 → B3）。

---

## 4. 任务明细

### 里程碑 A：稳定性

#### A1 重试入队幂等（消除重投活锁）

- **现状 / 证据**
  - `backend/src/main/resources/db/migration/V1__init_schema.sql`：唯一索引
    `uk_retry_active (route_id, message_id) WHERE status IN ('PENDING','RUNNING')`。
  - `backend/.../retry/RetryService.java#scheduleDeferred`：**无条件 `insert`**。
  - `backend/.../writer/batch/BatchManager.java`：`persistThenAck` / `handleRowFailure`
    在 `scheduleDeferred` 抛异常时降级为 `MessageDisposition.noAck`（不 ACK → MQ 重投）。
- **问题**：当 ACK 丢失导致同一条消息被重投、而其重试任务仍在 `PENDING/RUNNING` 时，
  `insert` 撞唯一索引 → 异常 → 不 ACK → 再次重投，形成活锁。
- **方案**
  1. 让入队幂等：`INSERT ... ON CONFLICT (route_id, message_id) WHERE status IN ('PENDING','RUNNING') DO UPDATE`
     刷新 `next_retry_at` / `error_*` / `updated_at`（保持同一任务行，不消耗额外次数）；
  2. 或等价地在 `RetryService.scheduleDeferred` 内先查活跃任务，存在则更新并返回其 id。
  3. 明确"重复投递"语义：已存在活跃任务即视为"已登记"，调用方照常产出 `ACK_DEFERRED`。
- **验收**
  - 新增**集成用例**（非单元测试，可选）：同 `message_id` 连续两次入队，断言只有一条活跃任务且不抛异常；
  - `scripts/e2e-config-api.ps1` 全绿；
  - 手工：制造行级失败 → 观察重试表仅一行活跃任务。
- **风险**：低。改动集中在 `RetryService` + 可能的一条 mapper SQL；不影响 ACK 顺序。

---

### 里程碑 B：可观测性

#### B1 Micrometer 业务指标

- **现状 / 证据**：`pom.xml` 已引 `micrometer-registry-prometheus`，但全仓无
  `MeterRegistry|Counter|Timer|Gauge` 使用；README 宣称有 Prometheus 指标名不副实。
- **方案**：新增 `MeterBinder`（或直接注入 `MeterRegistry`），暴露：
  - `mqspg.messages.received{route}` / `acked{route}` / `no_ack{route}`（来自 `RouteConsumer` 原子计数）；
  - `mqspg.raw.dropped` / `written` / `queue.depth`（来自 `RawStore`）；
  - `mqspg.pg.health.open{datasource}`（来自 `PgHealthGate`）；
  - `mqspg.retry.backlog{status}`（复用 `RetryService.summary()`）；
  - `mqspg.batch.duration` / `mqspg.write.isolated`（批次耗时、二分隔离次数）。
- **验收**：`curl /actuator/prometheus | grep mqspg` 可见；指标随消费变化。
- **风险**：低。仅新增只读采集。

#### B2 自定义健康指示器

- **方案**：实现 `HealthIndicator`，聚合：任一数据源闸门关闭 → `DOWN`；重试积压超阈值 → `OUT_OF_SERVICE`/`DOWN`；raw 丢弃率超阈值 → `DOWN`（阈值走配置）。
- **验收**：`/actuator/health` 在闸门关闭 / 积压超限时状态变化。
- **风险**：低。注意不要因瞬时抖动误报（沿用连续计数阈值思路）。

#### B3 MDC 关联日志

- **现状 / 证据**：`RouteConsumer` / `BatchManager` / `RetryScheduler` 日志靠字符串拼接
  `route=... message=...`，无法被采集器按字段索引。
- **方案**：在消费批次与重试任务入口注入 MDC（`routeId`/`messageId`/`configVersion`），
  在 `finally` 清理；`logging.pattern` 增加字段。避免长期驻留（线程复用时清理）。
- **验收**：日志中携带关联字段；无 MDC 泄漏（虚拟线程复用场景重点检查）。
- **风险**：低。

---

### 里程碑 C：性能

#### C1 控制台列表接口消除 N+1

- **现状 / 证据**
  - `config/service/ConfigService.java#listRoutes` → 每条路由 `toSummary` 内 2 次查询；
  - `config/service/ConfigAdminService.java#listDatasources` 每个数据源一次 count、
    `listTargets` 每条一次 datasource 查询；
  - `console/controller/ConsoleController.java#overview` 直接调用 `listRoutes`。
- **方案**：改用批量装配——一次性 `selectBatchIds` / JOIN 查询目标表、数据源、消费状态，
  在内存组装 DTO；`ConfigAdminService` 与 `ConfigService` 的 `toSummary` 重复实现合并到一处。
- **验收**：列表接口 SQL 次数从 O(n) 降为常数；页面数据不变。
- **风险**：低。

#### C2 `nextVersionNumber` 改为聚合查询

- **现状 / 证据**：`ConfigAdminService#nextVersionNumber` `selectList` 后在 Java 取 max。
- **方案**：`SELECT max(version) FROM cfg_version WHERE route_id = ?`；并发冲突仍由
  `UNIQUE(route_id, version)` 兜底，并把冲突错误转换为可读提示。
- **验收**：新建版本行为不变；SQL 从全量加载变为单行聚合。
- **风险**：低。

#### C3 Raw 留存避免重复解析 JSON

- **现状 / 证据**：`raw/RawStore.java#toRecord` 对每条消息 `mapper().readTree`，与
  `TransformEngine` 的解析重复；无体积上限。
- **方案**：仅在留存启用且报文小于阈值（可配置，如 1MB）时解析；超限直接存 `payload_raw`；
  评估复用转换阶段的解析结果（受限于"留存先于转换"的顺序，可先做阈值保护）。
- **验收**：留存行为对合法 JSON 不变；超大/非法报文不拖慢主链路。
- **风险**：低。

#### C4 重试路径攒批 + 提高调度吞吐

- **现状 / 证据**
  - `retry/RetryReplayer.java#replay` 每条任务调用 `writer.write(cfg, List.of(单行))`（逐条 MERGE）；
  - `retry/RetryScheduler.java` 用 `@Scheduled`（默认单线程）顺序处理最多 `batch-size=200` 条。
- **方案**
  1. 调度器按 `(routeId, configVersion)` 分组领取到的任务，组内复用
     `BatchFolder` + `PgWriter` 一次写入多条；
  2. 单条结果（成功 / 行失败 / 数据错误 / PG 故障）映射回各自任务的状态迁移；
  3. 视压测结果决定是否用虚拟线程执行器并发组（需考虑租约与目标库连接池上限）。
- **验收**：大量到期任务时，MERGE 次数显著下降、积压消化时间下降；语义（重放用绑定版本、次数与 DLQ 规则）不变。
- **风险**：中。重放路径的批量合并需谨慎保持"每条任务独立计数 / 独立 DLQ"的语义。

---

### 里程碑 D：可回归与交付

#### D1 集成测试迁移到 Testcontainers

- **现状 / 证据**：`PgWriterTest` / `PipelineIntegrationTest` / `RouteConsumerIntegrationTest` /
  `RetrySchedulerTest` / `RawRetentionTest` / `ConfigAdminTest` 直连本机 PG + dev 种子。
- **方案**：引入 `spring-boot-testcontainers` + `org.testcontainers:postgresql`，
  镜像固定 `postgres:18`（要求 17+）；用 `@ServiceConnection` / `@DynamicPropertySource`
  注入连接串；保留 `dev` 种子迁移（`db/demo`）供集成测试使用。
- **验收**：本机未启动 PG 时 `mvn test` 仍可通过（有 Docker 即可）；不改变断言语义。
- **风险**：中。首次拉镜像较慢；需保证容器版本 ≥17。

#### D2 引入 CI

- **现状 / 证据**：仓库无 `.github/`；且当前目录**尚未初始化 git**（无 `.git`）。
- **方案**：新增 GitHub Actions workflow：
  - 后端：`mvn -B verify`（D1 完成后可在 Docker runner 上跑 Testcontainers）；
  - 前端：`pnpm install --frozen-lockfile && pnpm build`；
  - 接入 E1 的格式/静态检查。
- **验收**：本地按 workflow 步骤可复现通过；推送远端后 CI 生效。
- **风险**：低（但需注意"未初始化 git"——先落地配置，接远端后生效）。

#### D3 e2e 脚本参数化

- **现状 / 证据**：`scripts/e2e-config-api.ps1` 通过 `docker exec postgres psql` 操作数据库，
  硬编码容器名 `postgres`。
- **方案**：把容器名 / 连接方式提为参数（已有 `-DbContainer` 雏形，扩展支持直连 psql / 连接串），
  与 D1 的容器化测试环境对齐。
- **验收**：本机与容器环境下均可运行；默认行为保持兼容。
- **风险**：低。

---

### 里程碑 E：工程化

#### E1 格式化 / 静态检查

- 后端：引入 **Spotless**（`google-java-format` 或 eclipse 风格需与现有代码保持一致，建议先跑
  `spotless:apply` 观察差异规模再定），接入 CI；
- 前端：引入 **ESLint + Prettier**（Vue 3 规则），接入 CI；
- 验收：`mvn spotless:check`、`pnpm lint` 通过；不产生大范围无关 diff（必要时分两次提交：仅格式化 + 功能）。
- 风险：低—中（格式化可能产生大 diff，需单独提交）。

#### E2 Dockerfile + docker-compose

- 后端多阶段构建（Maven 构建 → JRE 21 运行），前端 `pnpm build` + 静态托管；
- compose 编排 PG（`postgres:18`）+ 后端 + 前端，替代 README 的手工步骤；
- 验收：`docker compose up` 后 `/actuator/health` 与前端可访问。
- 风险：低。

#### E3 OpenAPI / 接口文档

- 引入 springdoc，暴露 `/swagger-ui`；需**先确认 Boot 4 兼容版本**（springdoc 2.x 面向 Boot 3，
  Boot 4 支持情况需核实，若不可用则退化为静态 Markdown 接口清单）。
- 验收：接口清单可浏览，与 `ApiResponse` 信封一致。
- 风险：中（版本兼容不确定，作为可选项）。

#### E4 生产配置样例 + 环境变量清单

- 提供 `backend/src/main/resources/application-prod.yml.example`（真实密钥仍不入库），
  在 README 增加环境变量表：`MQS_PG_DB_URL/USER/PASSWORD`、`mqs-pg.crypto.key`、
  `mqs-pg.mqs.vendor` 等。
- 验收：按样例 + 环境变量可启动。
- 风险：低。

#### E5 JaCoCo（可选）

- 引入 JaCoCo 生成覆盖率报告；门槛先设为"仅报告不阻断"，稳定后再收紧。
- 风险：低。

---

### 里程碑 F：前端

#### F1 ESLint + Prettier

- 见 E1，前端部分独立列出便于排期；`package.json` 增加 `lint` / `format` 脚本。
- 风险：低。

#### F2 抽取复用 composable

- **现状 / 证据**：各视图（dashboard / retry / errors / raw / routes / datasources）各自处理
  loading、轮询 `setInterval`、错误提示；`api/request.js` 已统一信封与错误弹窗。
- **方案**：抽出 `usePolling` / `useAsyncTable`（分页、筛选、加载态）composable，
  统一到既有 axios 约定，减少重复与泄漏（组件卸载时清理定时器）。
- **验收**：页面行为不变；重复代码下降。
- **风险**：低—中（需回归各页面）。

#### F3 TypeScript 渐进迁移（可选/评估）

- 先评估迁移成本与收益，可仅对 `api/`、`store/` 加类型，不强推全量。
- 风险：中。

---

## 5. 协作约定

### 5.1 `evolution.md` 记录规范

在仓库根新增 `evolution.md`，**每完成一个任务追加一节**（倒序或正序皆可，建议正序）：

```markdown
## YYYY-MM-DD · <任务编号> <标题>
- 背景：为什么要做（现象 / 风险）
- 改动：
  - `path/to/file`：做了什么
- 验证：执行的命令 / 观察到的结果
- 影响与回滚：对现有语义的影响、如何回退
- README 更新：对应更新了哪些小节
```

### 5.2 `README.md` 更新点

- 命令变化（如新增 `pnpm lint`、`docker compose up`、CI 说明）同步到"快速开始 / 测试"；
- 指标 / 健康探针：更新"可观测"一节，列出 `mqspg.*` 指标与健康项；
- 测试：Testcontainers 落地后，更新"测试"一节的先决条件描述；
- 生产配置：补充环境变量与 `application-prod.yml.example` 说明；
- 每完成一个里程碑，在"当前进度"表中补充对应行。

---

## 6. 里程碑 A 详细设计（待确认后实施）

**目标**：`RetryService.scheduleDeferred` 幂等，消除重复投递触发的唯一冲突活锁。

**改动点**
1. `retry/mapper/RtRetryTaskMapper`（+ XML 或注解）新增 `upsertActive` SQL：
   `INSERT ... ON CONFLICT (route_id, message_id) WHERE status IN ('PENDING','RUNNING') DO UPDATE SET
   next_retry_at = EXCLUDED.next_retry_at, error_* = EXCLUDED.error_*, attempt = EXCLUDED.attempt,
   updated_at = EXCLUDED.updated_at RETURNING id`；
2. `RetryService#scheduleDeferred` 改走该语句，返回任务 id；
3. 保持 `BatchManager` 的 ACK 语义不变（登记成功即 `ACK_DEFERRED`）。

**验证**
- `mvn test`（D1 前依赖本机 PG）+ `scripts/e2e-config-api.ps1`；
- 手工构造：同一 `messageId` 连续两次入队 → 活跃任务仍为 1 条、无异常。

**回滚**：仅需还原 `RetryService` 与 mapper SQL，无 schema 变更（复用既有唯一索引）。

---

## 7. 排期与依赖

| 阶段 | 内容 | 预估 | 前置 |
| --- | --- | --- | --- |
| 1 | A1 + B1/B2/B3（已合并） | 2–3 天 | 无 |
| 2 | B（B1、B2、B3） | 2–3 天 | 无 |
| 3 | C（C1–C4） | 2–3 天 | 无 |
| 4 | D（D1、D3、D2） | 2–3 天 | C4 建议在 D1 后验证 |
| 5 | E（E2、E1、E4、E3、E5） | 2–3 天 | E1 与 D2 协同 |
| 6 | F（F1、F2、F3） | 1–2 天 | 无 |

> 排期为粗略估计，实际以每步验证通过为准。

---

## 8. 风险与回滚总则

1. **不改变核心不变量**：ACK 顺序、单调守卫、折叠、版本快照；
2. **每任务独立可回滚**：优先局部改动，避免跨模块大重构；
3. **格式化类改动单独提交**：避免与功能改动混在同一 diff；
4. **观测先行**：B 里程碑落地后，后续性能改动（C）才有量化依据；
5. **容器化先行再 CI**：D1 完成后再上 D2，避免 CI 依赖本机环境。

---

## 9. 决策记录（已确认）

1. **A + B 合并**为第一阶段交付；实施仍从 A1 开始，按 A1 → B1 → B2 → B3 顺序推进，逐条记录到 `evolution.md`。
2. **E3（OpenAPI）**：要求可交互 Swagger UI；若 springdoc 无 Boot 4 兼容版本，则**跳过 E3**（不做静态清单替代）。
3. **E5（JaCoCo）**：推迟，本次不做。
4. **C4（重试攒批）**：风险中等，**待 D1 完成后**再实施。
