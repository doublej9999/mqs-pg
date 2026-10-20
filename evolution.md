# 演进记录

本文件按时间记录每一次优化的**背景 / 改动 / 验证 / 影响与回滚 / README 更新**。
整体方案见 [`docs/optimization-plan.md`](docs/optimization-plan.md)（§5.1 为记录规范）。

---

## 2026-10-20 · D3 e2e 脚本参数化（可在容器 / 无 docker CLI 环境跑）

- **背景**
  - `scripts/e2e-config-api.ps1` 之前硬编码三处环境假设：SQL 走 `docker exec postgres psql`、
    数据源 URL 固定 `jdbc:postgresql://localhost:5432/postgres`、测试表固定叫 `e2e_orders`。
  - 于是它只能在「本机有名为 `postgres` 的容器 + 后端也跑在本机」这一种环境下工作；
    后端跑进容器（compose / k8s）后 `localhost` 指向容器自身，脚本必然失败。
  - 另外 `CREATE TABLE IF NOT EXISTS` 第二次运行会产生 NOTICE，而 psql 把 NOTICE 写 stderr，
    在 `$ErrorActionPreference='Stop'` 下会被 PowerShell 当成终止错误 —— 与脚本
    「可反复运行」的承诺矛盾（首次运行没有 NOTICE，所以这个坑只在重跑时暴露）。

- **改动**
  - 新增参数：`-SqlMode docker|psql`、`-PgHost/-PgPort/-PgUser/-PgPassword/-PgDatabase`、
    `-DbContainer`、`-TargetJdbcUrl/-TargetUser/-TargetPassword`；`Invoke-Sql` 按 `SqlMode` 分流，
    psql 分支用 `PGPASSWORD` 环境变量传密码并在 `finally` 里立刻清除。
  - `Invoke-Sql` 内部临时放宽 `$ErrorActionPreference`，只看退出码；并把
    `NOTICE/WARNING/HINT/psql:` 行从返回值里剔除，避免污染 `SELECT` 的结果。
  - 资源名改为 `$runId`（Guid 前 12 位）：数据源/目标表/路由/Topic **以及测试表名**都不再共用，
    清理逻辑从「按名字前缀删」改成「按本次精确名删 + `DROP TABLE`」，
    这样并发跑两次也不会互相删数据。
  - 建表 SQL 改为单行拼接（先落变量再传参）：多行 SQL 在不同 shell / psql 客户端下换行可能被二次解析；
    同时避开 `Invoke-Sql ("a" -f $x) + 'b'` 被解析成 `(Invoke-Sql ...) + 'b'` 的坑。

- **验证**
  - **未执行**。本机 Docker Desktop 未运行，既起不了 `postgres` 容器，也没有本地 PG 可供 `-SqlMode psql` 直连；
    脚本需要后端已启动，因此本轮无法跑通。
  - 已做的静态检查：用 `[System.Management.Automation.Language.Parser]::ParseFile` 解析该脚本，
    **0 个语法错误**，且参数表确认为
    `Base, DbContainer, TimeoutSeconds, SqlMode, PgHost, PgPort, PgUser, PgPassword, PgDatabase, TargetJdbcUrl, TargetUser, TargetPassword`
    （旧版只有前三个）。运行期分支（`docker` vs `psql`、清理逻辑）不在语法检查覆盖范围内。
  - **待补**：起 Docker 后按 README「测试」一节分别用两种模式各跑一次，并把输出补进本节。

- **影响与回滚**
  - 只影响验收脚本，不影响运行时。默认行为（`docker` 模式 + 默认容器名）与旧版一致，
    但**测试表名不再复用** `e2e_orders`，而是每次新建并在结束时删除 —— 旧脚本写入的
    `e2e_orders` 残留数据不再被自动清理（需要时手工 `DROP TABLE biz_demo.e2e_orders`）。
  - 回滚：`git checkout -- scripts/e2e-config-api.ps1` 并删除本节。

---

## 2026-10-19 · RawStore 优雅停机排空

- **背景**
  - `RawStore.close()` 之前会中断留存工作线程；线程在 `queue.poll()` 处收到中断后直接返回，
    因此停机时队列中尚未写入的原始消息不会执行后续排空逻辑。

- **改动**
  - 工作线程在 `running=false` 后继续处理队列，直到队列为空再退出。
  - 关闭过程仍中断 worker 以唤醒队列轮询；worker 识别停机中断后继续消费，直到队列排空。
  - 中断和停机状态增加中文日志，便于识别仍有未写入记录的异常关闭。

- **验证**
  - `mvn -B -Dtest=RawRetentionTest test`：7 个集成用例通过（Testcontainers PostgreSQL 18）。
  - 该测试由项目基类启动独立临时数据库，未连接或修改本机 `localhost:5432` 数据库。

- **影响与回滚**
  - 正常停机可能等待一个 flush 间隔以及剩余批次写入时间；这是为了保证已入队留存不被静默丢弃。
  - 回滚：恢复 `RawStore` 原有的中断退出逻辑即可。

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

---

## 2026-10-17 · C1 控制台列表接口消除 N+1

- **背景**
  - `ConfigService#listRoutes` / `ConfigAdminService#listDatasources` / `#listTargets`
    都在循环里按行查关联表：路由列表是 O(n) 次「目标表 + 消费状态」查询，
    数据源列表每行一次 `count`，目标表列表每行一次数据源查询。
  - `ConsoleController#overview` 又直接调用 `listRoutes`，使问题暴露在首页。

- **改动**
  - 新增 `config/service/RouteSummaries`：把两处**逐字段相同**的 `toSummary` 合并为一个
    纯内存装配函数（不持有 Mapper，从结构上杜绝再次引入 N+1）。
  - `ConfigService#listRoutes`：一次 `selectBatchIds` 取回全部目标表与消费状态，内存组装；
    `#getRoute` 复用已加载的 target 与单次查询的 state，不再二次查。
  - `ConfigAdminService#listDatasources`：用 `group by datasource_id` 一次拿到各数据源目标表数量；
    `#listTargets`：一次 `selectBatchIds` 取回涉及的数据源。

- **验证**
  - 命令：`mvn test`。
  - 结果：**`Tests run: 51, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS**（页面数据不变）。

- **影响与回滚**
  - 纯查询装配优化，DTO 字段与语义不变；列表接口 SQL 次数由 O(n) 降为常数。
  - 回滚：还原三个列表方法即可（`RouteSummaries` 可保留）。

---

## 2026-10-17 · C2 `nextVersionNumber` 改为聚合查询

- **背景**
  - `ConfigAdminService#nextVersionNumber` 先 `selectList` 把该路由**所有版本**加载进内存再取 max。

- **改动**
  - 改为 `SELECT max(version) FROM cfg_version WHERE route_id = ?`（`selectMaps` 聚合）。
  - 空值保护：尚无版本时聚合行是 NULL，MyBatis 会映射成 `null`（甚至列表里的 `null` 元素），
    已按行判空后回退到 0。
  - `insertVersion` 捕获 `DuplicateKeyException`，把 SQLSTATE 23505 转成可读提示
    「版本号冲突（route=… version=…），请刷新后重试」（并发下仍由 `UNIQUE(route_id, version)` 兜底）。

- **验证**
  - 命令：`mvn test`。
  - 结果：**`Tests run: 51, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS**（新建版本/保存草稿行为不变）。

- **影响与回滚**
  - SQL 由全量加载变为单行聚合；行为与错误信息更友好。
  - 回滚：还原 `nextVersionNumber` 与 `insertVersion`。

---

## 2026-10-17 · C3 原始留存超大报文跳过 JSON 解析

- **背景**
  - `RawStore#toRecord` 对每条消息都 `mapper().readTree` 以判断能否存 JSONB；
    留存是旁路，对超大报文这次解析纯属浪费，且无体积上限。

- **改动**
  - `RawProperties` 新增 `payload-parse-max-bytes`（默认 1048576 = 1 MiB）。
  - `RawStore#toRecord`：报文超过阈值时**跳过解析**，直接存 `payload_raw`；
    未超阈值时行为不变（合法 JSON → JSONB，非法 JSON → 原文）。

- **验证**
  - 命令：`mvn test`（`RawRetentionTest` 覆盖合法/非法 JSON 的留存路径）。
  - 结果：**`Tests run: 51, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS**。

- **影响与回滚**
  - 超大报文的留存从 JSONB 退化为文本（仍可查原文），换取旁路线程不被拖慢。
  - 回滚：删除阈值判断即可。

---

## 2026-10-17 · D1 集成测试迁移到 Testcontainers

- **背景**
  - 六个集成测试（`PgWriterTest` / `PipelineIntegrationTest` / `RouteConsumerIntegrationTest` /
    `RetrySchedulerTest` / `RawRetentionTest` / `ConfigAdminTest`）直连本机 PostgreSQL +
    dev 种子：跑测试前必须先 `docker run` 一个 PG、再 `spring-boot:run` 灌种子，
    换台机器/CI 就无法回归；而且它们**隐式依赖本机库里的旧表**（见下）。

- **改动**
  - `pom.xml`（test scope）引入 `org.testcontainers:testcontainers-postgresql`
    （注意 2.x 的模块名不再是 `org.testcontainers:postgresql`），版本由 Boot 导入的
    `testcontainers-bom` 管理。
  - 新增 `com.mqspg.support.AbstractPgIntegrationTest`：
    - 静态块里启动**单例** `postgres:18`（`@Container` 会按测试类反复启停，太慢；
      单例由 Ryuk 在 JVM 退出时回收）；
    - `@DynamicPropertySource` 把 `spring.datasource.url/username/password` 指向容器；
    - 密码取 `123456`，与 dev 种子里目标数据源的 `{noop}123456` 一致，
      于是只需把种子里的 **URL** 改写到容器（`@BeforeEach` 一条 `UPDATE cfg_datasource`），
      密码与断言语义都不用动；
    - 六个测试类改为 `extends` 本类，仅保留各自的 `@TestPropertySource`。
  - `ConfigAdminTest` 里硬编码的 `jdbc:postgresql://localhost:5432/postgres`
    改为 `containerJdbcUrl()`（它自建的数据源要能被 `introspection` 真连上）。

- **踩到的坑（值得记一笔）**
  - Testcontainers 的 `PostgreSQLContainer#getJdbcUrl()` 返回的串**已经带了** `?loggerLevel=OFF`。
    直接 `getJdbcUrl() + "?currentSchema=mqs_pg,public"` 会拼出两个 `?`，
    整个查询串失效 → `search_path` 没设上 → MyBatis 的无限定 SQL（`FROM cfg_route`）
    落到 `public` → 启动报 `relation "cfg_route" does not exist`。
    修法：先剥掉原查询串再拼（`AbstractPgIntegrationTest`）。
  - 同一个报错**看起来**像"Flyway 还没跑完就查库"。实测加了
    `@DependsOnDatabaseInitialization` 也修不好，去掉后测试照过 ——
    因为 MyBatis 的 `SqlSessionFactory` 本身已被 Boot 接入数据库初始化依赖，
    `DbConfigRegistry`（依赖 mapper）自然排在 Flyway 之后。**不要**照症状去加依赖注解。

- **验证**
  - 命令：`mvn test`。
  - 结果：**`Tests run: 51, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS**。
  - 关键验收：`docker stop pg-min`（本机 5432 不可达，`Test-NetConnection` 返回 `False`）
    后再跑 `mvn test`，仍 **51/51 通过**；随后 `docker start pg-min` 复原。

- **影响与回滚**
  - 测试不再需要预置 PG 与种子，也不再受本机库残留数据影响；代价是首次拉 `postgres:18`
    镜像较慢、`mvn test` 需要 Docker。
  - 回滚：删除基类与依赖，恢复六个测试类的 `@SpringBootTest @ActiveProfiles("dev")`。

---

## 2026-10-17 · D2 引入 CI

- **背景**
  - 仓库此前没有 `.github/`，也没有 CI：改动是否可回归完全靠本地手动 `mvn test`。

- **改动**
  - 新增 `.github/workflows/ci.yml`，push 到 `main` 与 PR 时触发，含 `concurrency`
    （同分支旧运行自动取消）与 `workflow_dispatch`（可手动跑）：
    - `backend`：Temurin JDK 21 + Maven 缓存 → `mvn -B --no-transfer-progress verify`
      （`verify` 会跑测试并打 jar）；失败也上传 `surefire-reports` 便于定位。
      D1 之后集成测试用 Testcontainers，runner 自带 Docker，不需要外部 PG。
    - `frontend`：pnpm 12 + Node 22（缓存 `pnpm-lock.yaml`）→
      `pnpm install --frozen-lockfile` → `pnpm build`。
  - E1（Spotless / ESLint）尚未落地，workflow 里预留说明，待 E1 完成后再把格式检查接进来。

- **验证**
  - 本地按 workflow 原样跑：
    - `mvn -B --no-transfer-progress verify` → **51/51 通过 + `BUILD SUCCESS`**（产 jar）；
    - `pnpm install --frozen-lockfile && pnpm build` → 退出码 0，产出 `dist/`。
  - `pnpm dlx js-yaml .github/workflows/ci.yml` 能正常解析（结构为 2 个 job）。

- **影响与回滚**
  - 只新增文件，不影响运行时；推送后 GitHub 上即可看到检查状态。
  - 回滚：删除 `.github/workflows/ci.yml`。






