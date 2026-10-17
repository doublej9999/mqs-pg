# 演进记录

本文件按时间记录每一次优化的**背景 / 改动 / 验证 / 影响与回滚 / README 更新**。
整体方案见 [`docs/optimization-plan.md`](docs/optimization-plan.md)（§5.1 为记录规范）。

---

## 2026-10-17 · A1 重试入队幂等（消除重投活锁）

- **背景**
  - `rt_retry_task` 上有部分唯一索引
    `uk_retry_active (route_id, message_id) WHERE status IN ('PENDING','RUNNING')`
    （`db/migration/V1__init_schema.sql`）。
  - `RetryService#scheduleDeferred` 之前是**无条件 `insert`**；而 `BatchManager` 在登记失败时
    会降级为 `MessageDisposition.noAck`（不 ACK → MQ 重投）。
  - 后果：当 ACK 丢失导致同一条消息被重投、且它的重试任务仍活跃时，`insert` 撞唯一索引 →
    异常 → 不 ACK → 再次重投，形成**活锁**。

- **改动**
  - `backend/src/main/java/com/mqspg/retry/mapper/RtRetryTaskMapper.java`：
    新增幂等方法 `upsertActive(RtRetryTask)`。
  - `backend/src/main/resources/mapper/RtRetryTaskMapper.xml`：
    新增 `upsertActive` 语句，用
    `INSERT ... ON CONFLICT (route_id, message_id) WHERE status IN ('PENDING','RUNNING') DO UPDATE`
    命中活跃任务时刷新 `next_retry_at` 与错误快照并 `RETURNING id`。
    刻意**不覆盖** `attempt` / `max_attempt` / `status` / `lease_until`
    —— 重复投递不重置重试次数账本，也不把正在被调度器处理（`RUNNING`）的任务打回 `PENDING`。
  - `backend/src/main/java/com/mqspg/retry/RetryService.java`：
    `scheduleDeferred` 改走 `upsertActive`，返回（新建或复用的）任务主键。

- **验证**
  - 命令：`mvn test`（本机 PG `postgres:17-alpine`，dev 种子数据源密码 `123456`）。
  - 结果：**`Tests run: 51, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS**。
  - 该路径被现有集成测试覆盖：`PipelineIntegrationTest`
    「行级 PG 数据错误被隔离……坏行落重试表后 ACK」会真实执行 `scheduleDeferred`；
    `RetrySchedulerTest` 覆盖重放与状态迁移。

- **影响与回滚**
  - 无 schema 变更，复用既有部分唯一索引；仅改变"同名活跃任务"的入队行为（由抛错变为刷新）。
  - 对 ACK 不变量无影响：登记成功即 `ACK_DEFERRED` 的约定不变。
  - 回滚：还原 `RetryService#scheduleDeferred` 与 mapper 的 `upsertActive` 即可。

- **README 更新**
  - 「实现须知」新增第 8 条（部分唯一索引与 MQ 重投语义冲突，入队必须幂等）。
  - 「当前进度」补充优化里程碑说明，并指向 `docs/optimization-plan.md` 与本文件。

---

## 2026-10-17 · B1 接入 Micrometer 业务指标

- **背景**
  - `pom.xml` 已引 `micrometer-registry-prometheus`，但全仓**零** `MeterRegistry|Counter|Timer|Gauge`
    使用，`/actuator/prometheus` 只有 JVM / HTTP 默认指标，看不出消费、重试、留存是否健康；
    README 宣称的"Prometheus 业务指标"名不副实。

- **改动**
  - 新增 `com.mqspg.metrics.MqsMetrics`：唯一依赖 `MeterRegistry` 的指标封装，提供
    `count` / `record(Duration)` / `gauge(state, ToDoubleFunction)`。
    **刻意只依赖 `MeterRegistry`**：各组件自行注册 gauge，避免"指标组件反向依赖被观测组件"造成的
    Spring bean 循环依赖；gauge 一律 `strongReference(true)`（否则内联 lambda 被 GC 后变 NaN）。
  - `RouteConsumer`：接收 / ACK / 不 ACK / 批次 / 中止 / 批次失败计数，`mqspg.batch.duration` 计时；
    `route` 标签值构造时算一次，避免热路径反复 `String.valueOf`。
  - `ConsumerManager`：注入并透传 `MqsMetrics`。
  - `RawStore`：`mqspg.raw.messages.written` / `dropped` 计数；`mqspg.raw.queue.depth` gauge。
  - `PgWriter`：二分隔离出坏行时 `mqspg.write.rows.isolated{target}` 计数。
  - `PgHealthGate`：闸门关闭/恢复 `mqspg.pg.gate.closed|opened{datasource}` 计数；
    `mqspg.pg.health.open{datasource}` gauge（首次见到某数据源时注册）。
  - `RetryService`：`mqspg.retry.backlog{status=PENDING|RUNNING|DLQ}` gauge；
    由 `@Scheduled(fixedDelayString="${mqs-pg.metrics.refresh-interval:15s}")` 定时把
    `summary()` 结果写入 `AtomicLong`，**抓取时不打 DB**。

- **指标清单（`/actuator/prometheus` 中前缀 `mqspg_`）**
  - 消费：`messages.received{route}` / `messages.acked{route}` / `messages.no_ack{route}`
  - 批次：`batches.total{route}` / `batches.aborted{route}` / `batches.failed{route}` / `batch.duration{route}`
  - 写入：`write.rows.isolated{target}`
  - 留存：`raw.messages.written` / `raw.messages.dropped` / `raw.queue.depth`
  - 重试：`retry.backlog{status}`
  - 健康：`pg.health.open{datasource}` / `pg.gate.closed{datasource}` / `pg.gate.opened{datasource}`

- **验证**
  - 命令：`mvn test`（回归，确认新增依赖注入未破坏上下文启动）。
  - 结果：**`Tests run: 51, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS**
    （含修正 `RouteConsumerIntegrationTest` 的手工构造器签名）。
  - 手工：`curl /actuator/prometheus | grep mqspg_` 可见业务指标，且随消费/重试变化。

- **影响与回滚**
  - 仅新增只读采集；无 schema / 协议变更；热路径每消息多几次 `ConcurrentHashMap` 命中。
  - 回滚：删除 `MqsMetrics` 并还原各组件字段与调用即可（各文件改动均为追加式）。

---

## 2026-10-17 · B2 自定义健康指示器

- **背景**
  - 默认 `/actuator/health` 只回答"进程活着"，无法回答运维真正关心的问题：
    消费还转不转？重试是不是堆爆了？留存还在丢吗？

- **改动**
  - `PgHealthGate` 新增 `snapshot()`（datasourceId → 是否放行），供健康检查聚合。
  - 新增 `com.mqspg.metrics.MqsPgHealthIndicator`（bean 名 `mqsPg`，健康组件 id `mqsPg`），聚合三个信号：
    - `pgGate`：任一已知数据源闸门关闭 → **DOWN**（消费已停，硬故障）；
    - `retryBacklog`：`PENDING + RUNNING` 超过 `mqs-pg.health.retry-backlog-threshold`（默认 10000）
      → **OUT_OF_SERVICE**；
    - `rawDrop`：`dropped / (written + dropped)` 超过 `mqs-pg.health.raw-drop-ratio-threshold`（默认 0.1）
      且样本量 ≥`mqs-pg.health.raw-drop-sample-min`（默认 1000）→ **OUT_OF_SERVICE**。
  - 降级态刻意用 `OUT_OF_SERVICE` 而非 `DOWN`：留存丢弃是旁路、积压是恶化中，都不该让只看 UP/DOWN
    的探针把实例摘掉。读取积压失败时记录 `retryBacklogError` 并降级，而不是让 health 端点抛 500。

- **注意（Boot 4 迁移点）**
  - Spring Boot 4 把健康 API 从 `org.springframework.boot.actuate.health` 迁到了
    **`org.springframework.boot.health.contributor`**（模块 `spring-boot-health`）。
    沿用旧包名会编译失败（`程序包 ... 不存在`）。

- **验证**
  - 命令：`mvn test`。
  - 结果：**`Tests run: 51, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS**（应用上下文可正常装配该 bean）。
  - 手工：`curl /actuator/health` 可见 `mqsPg` 组件与其三段明细；闸门关闭 / 积压超限时状态变化。

- **影响与回滚**
  - 只读聚合，无副作用；health 端点被调用时才查一次重试积压。
  - 回滚：删除 `MqsPgHealthIndicator` 与 `PgHealthGate#snapshot` 即可。

---

## 2026-10-17 · B3 MDC 关联日志

- **背景**
  - `RouteConsumer` / `BatchManager` / `RetryScheduler` 的日志靠字符串拼接
    `route=... message=...`，采集器无法按字段索引，排障只能全文搜。

- **改动**
  - 新增 `com.mqspg.common.logging.MdcScope`：try-with-resources 的作用域，
    「保存旧值 → 写入 MDC → 关闭时恢复」，支持嵌套；`configVersion` 用 `Object`
    接收以避免 `null` 时的重载歧义。
  - `RouteConsumer#run`：消费线程专属单一路由，整条线程生命周期内把 `routeId` 放进 MDC，
    `finally` 移除；`applyDispositions` 在输出「不 ACK / ACK 失败」日志时叠加 `messageId`。
  - `BatchManager`：逐条转换（含失败处置）与行级失败登记重试时叠加 `messageId` / `configVersion`。
  - `RetryScheduler#processOne`：进入重放前把 `routeId` / `messageId` / `configVersion` 放进 MDC，
    覆盖 `RetryReplayer` / `RetryService` 的日志。
  - `application.yml`：`logging.pattern.console` 增加 `[route=%X{routeId:-} msg=%X{messageId:-} ver=%X{configVersion:-}]`。

- **注意（虚拟线程）**
  - 项目启用了虚拟线程，MDC 底层仍是 ThreadLocal，载体线程会被复用；
    所有作用域都必须 `close()`（try-with-resources），否则 `messageId` 会泄漏到无关日志。

- **验证**
  - 命令：`mvn test`。
  - 结果：**`Tests run: 51, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS**。
  - 手工：消费/重试日志中出现 `[route=1 msg=it-xxx ver=1]`；无关联字段的日志该段为空。

- **影响与回滚**
  - 仅日志格式与 ThreadLocal 读写，无业务语义变更。
  - 回滚：还原 `logging.pattern.console` 并移除各入口的 `MdcScope` 使用即可。



