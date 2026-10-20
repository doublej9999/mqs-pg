# mqs-pg

配置驱动的 **MQ → PostgreSQL** 数据同步系统。

从消息队列消费业务变更事件，按**配置**（而非代码）转换为 PostgreSQL 目标行，
以幂等 Upsert 落库，在网络抖动、消息重复、乱序到达的前提下保证最终一致。

* 需求文档：[prd.md](prd.md)
* 技术设计：[tech-design.md](tech-design.md)（含 ADR-01 ~ ADR-08、正确性论证、实测结论）

---

## 核心特性

| 能力 | 说明 |
| --- | --- |
| 配置驱动 | `Topic + Tag → 一张表`；映射规则以**不可变 JSONB 快照**存于 `cfg_version.content`，运行中的消息绑定接收时刻的版本 |
| 幂等 Upsert | `MERGE ... RETURNING merge_action()`，**逐行**返回 `INSERTED` / `UPDATED` / `SKIPPED_OLD_VERSION` |
| 乱序保护 | `update_time` 单调守卫（严格 `>`）。最终状态与消息到达顺序无关，收敛到最大版本 |
| 同批次保序折叠 | 应用层按 Upsert Key 归并，既避免 PG 的重复冲突键错误，又保证被淘汰的消息仍会被 ACK |
| 行级错误隔离 | 语句级失败做**二分隔离**定位到具体行，坏行进重试表，不阻塞同批次好行 |
| 可靠 ACK | 不变量：`ack(m) ⟹ (PG 已提交) ∨ (存在重试表记录)`；顺序固定为 `BEGIN → INSERT 重试任务 → COMMIT → ACK` |
| 持久化重试 | 重试任务落 `rt_retry_task` 而非留在内存，带指数退避 + 抖动 + 租约；进程重启不丢任务，多实例用 `FOR UPDATE SKIP LOCKED` 抢任务 |
| DLQ 与人工重放 | 次数耗尽转 `DLQ` 终态并写 `rt_error_record(is_final=true)`；控制台可按原绑定版本重放，被单调守卫拦下的「过期重放」视为成功 |
| 原始留存 | 异步攒批写 `raw_message`，原生 RANGE 日分区，TTL 靠 `DETACH + DROP` 回收；留存是**旁路**，队列满时丢弃也不阻塞消费 |
| 可观测 | Actuator + Prometheus 指标 + 控制台（总览 / 路由版本 / 重试队列 / 错误与 DLQ / 原始留存） |

## 技术栈

| 层 | 选型 |
| --- | --- |
| 语言 | Java 21 |
| 框架 | Spring Boot 4.1.1 |
| 持久化 | MyBatis-Plus 3.5.17（`mybatis-plus-spring-boot4-starter`） |
| 数据库 | PostgreSQL（`MERGE ... RETURNING` 需 **17+**；本地实测 18.1） |
| 迁移 | Flyway（**必须用 `spring-boot-starter-flyway`**，见下） |
| 转换 | Jayway JsonPath 2.10.0 + JSLT 0.1.15（基于 Jackson 2） |
| 前端 | Vue 3 + Vite + Element Plus（沿用 vue-admin-template 的布局 / 权限 / axios 组织方式） |

## 快速开始

### 1. 准备 PostgreSQL

```bash
docker run -d --name postgres -e POSTGRES_PASSWORD=123456 -p 5432:5432 postgres:18
```

只需一个可连接的库即可；schema `mqs_pg` 与全部表由 Flyway 自动创建。

### 2. 启动后端

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

PowerShell 下 `-Dkey=value` 会被拆坏（Maven 报 `Unknown lifecycle phase '.run.profiles=dev'`），
改用环境变量：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'dev'; mvn spring-boot:run
```

`dev` profile 会额外加载 `db/demo` 下的种子数据：路由 1（`order-topic` / `order.updated`）
及其目标表 `biz_demo.orders`，用于本地联调。

连接参数可用环境变量覆盖：`MQS_PG_DB_URL`、`MQS_PG_DB_USER`、`MQS_PG_DB_PASSWORD`。

### 3. 验证

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/routes
curl http://localhost:8080/api/routes/1
```

**接口文档（Swagger UI）**：`dev` profile 下打开
<http://localhost:8080/swagger-ui/index.html>（OpenAPI JSON 在 `/v3/api-docs`）。
默认（非 dev）**关闭** —— 它会把完整接口清单暴露给任何能访问 8080 的人，
生产要开就显式设 `springdoc.api-docs.enabled=true` / `springdoc.swagger-ui.enabled=true`。

### 4. 端到端试跑（mock MQ）

`mqs-pg.mqs.vendor=mock`（默认）时会启用进程内消息代理，因此**不必等平台 MQS**
就能把整条链路跑通：投递 → 消费 → 转换 → 折叠 → MERGE → ACK。

```bash
# 投递三条同 id、乱序 update_time 的消息
for p in \
  '{"id":970001,"name":"first","amount":10.00,"status":"CREATED","updatedAt":"2026-01-01T10:00:00+08:00"}' \
  '{"id":970001,"name":"second","amount":20.00,"status":"PAID","updatedAt":"2026-01-01T12:00:00+08:00"}' \
  '{"id":970001,"name":"stale","amount":5.00,"status":"CREATED","updatedAt":"2026-01-01T09:00:00+08:00"}' ; do
  curl -s -X POST http://localhost:8080/api/mock/publish \
    -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg b "$p" '{topic:"order-topic",tag:"order.updated",body:$b}')"
done

# 观察消费计数
curl -s http://localhost:8080/api/mock/status/1

# 期望：写入的是 12:00 那条（20.00 / status=2），09:00 的旧版本被单调守卫拦住
```

注意 `body` 是**字符串**形式的 JSON 报文，而不是嵌套对象——这样可以直接粘贴原始
报文，避免被 Spring 重新序列化而改变字段顺序或数值精度。

### 5. 启动前端控制台

```bash
cd frontend
pnpm install
pnpm dev          # http://localhost:5173
```

Vite 已把 `/api` 代理到 `http://localhost:8080`，因此不必处理跨域。
登录页任意非空用户名密码即可进入（控制台未接入鉴权）。

> **pnpm 12 会拦截依赖的 postinstall 脚本**，其中 `esbuild` 必须放行才能启动 Vite。
> 仓库里的 `frontend/pnpm-workspace.yaml` 已把 `esbuild`、`vue-demi` 加入
> `allowBuilds`；若换成 npm/yarn 则无此问题。

页面：运行总览、路由与版本、数据源、重试队列（可人工重放 / 取消）、错误与 DLQ、原始留存、模拟投递。

「路由与版本」页已经能完成**整条链路的配置**：新建路由时依次选「数据源 → schema → 表 →
Upsert Key → 单调守卫字段」，下拉框内容全部来自目标库的真实结构（不是手填标识符）；
随后编辑字段映射（或点「按同名列自动生成」）→ 发布 → 设为生效。
发布前会静态校验 NOT NULL 列是否都有来源、Upsert Key 是否命中唯一索引；
校验不通过会列出**具体是哪一列**，而不是笼统报错。

### 6. 测试

后端单测 / 集成测试：

```bash
cd backend
mvn test
```

集成测试（`PgWriterTest` / `PipelineIntegrationTest` / `RouteConsumerIntegrationTest` /
`RetrySchedulerTest` / `RawRetentionTest` / `ConfigAdminTest`）跑在 **Testcontainers** 起的
`postgres:18` 容器上：只要本机装了 Docker，**不必**事先起 PostgreSQL，也不必手工灌种子 ——
Flyway 会在空容器里建 schema（`db/migration` + dev 的 `db/demo`）并写入 demo 种子。
容器密码固定 `123456`，与种子里目标数据源的 `{noop}123456` 保持一致。
六个测试类都继承 `AbstractPgIntegrationTest`，它负责启动容器并把连接串注入 Spring；
它们都设置 `mqs-pg.consumer.auto-start=false`，避免后台消费线程与测试争抢同一批消息。

其中驱动 `BatchManager` 的两个测试额外设置 `mqs-pg.raw.enabled=false`：
原始留存是**旁路**，开着会往 `raw_message` 写数据，与这两个测试的断言无关。

> 踩过的坑：`PostgreSQLContainer#getJdbcUrl()` 返回的串**已带** `?loggerLevel=OFF`，
> 再拼一个 `?currentSchema=...` 会拼出两个 `?`，整个查询串失效 —— MyBatis 的无限定 SQL
> 落到 `public`，报 `relation "cfg_route" does not exist`。连接串拼装见 `AbstractPgIntegrationTest`。

端到端验收（需要后端已启动）：

```powershell
pwsh -File scripts/e2e-config-api.ps1     # Windows PowerShell 5.1 亦可
```

脚本的 SQL 访问默认是 `docker exec <容器名> psql`（容器名默认 `postgres`，用 `-DbContainer` 覆盖）；
没有 docker CLI 时可用 `-SqlMode psql` 直连（配 `-PgHost/-PgPort/-PgUser/-PgPassword/-PgDatabase`）。
脚本经 API 新建的数据源必须让**后端进程**连得上，由 `-TargetJdbcUrl/-TargetUser/-TargetPassword`
指定（后端也跑在容器里时就填服务名）：

```powershell
pwsh -File scripts/e2e-config-api.ps1 -DbContainer pg-min
pwsh -File scripts/e2e-config-api.ps1 -SqlMode psql -PgPassword 123456
```

这个脚本只用页面真正会调的接口，从「建数据源」一路走到「消息落库」，
自带建表与清理，可反复运行。它覆盖的正是**配置化闭环**：
新建数据源 → 试连 → 探查 schema/表/列 → 建目标表 → 建路由 → 存草稿 →
校验被拦下 → 发布 → 激活 → 投递 → 落库（含枚举 / 时间戳 / 常量 / JSONPath 四种转换）→ ACK →
删除保护 → 级联清理。

**持续集成**：`.github/workflows/ci.yml` 在 push 到 `main` 及 PR 时跑两个 job ——

| job | 命令 | 说明 |
| --- | --- | --- |
| `backend` | `mvn -B verify` | 集成测试用 runner 自带的 Docker 起 Testcontainers，无需外部 PG |
| `frontend` | `pnpm install --frozen-lockfile && pnpm build` | 校验锁文件一致并产出 `dist/` |

---

## 可观测

Actuator 端点默认暴露 `health,info,metrics,prometheus`。

**业务指标**（`/actuator/prometheus`，前缀 `mqspg_`）：

| 指标 | 标签 | 含义 |
| --- | --- | --- |
| `mqspg_messages_received_total` | `route` | 拉取到的消息数 |
| `mqspg_messages_acked_total` | `route` | 已 ACK 的消息数 |
| `mqspg_messages_no_ack_total` | `route` | 未 ACK（等待 MQ 重投）的消息数 |
| `mqspg_batches_total` / `_aborted` / `_failed` | `route` | 批次数 / PG 级故障中止 / 未预期异常 |
| `mqspg_batch_duration_seconds` | `route` | 批次处理耗时 |
| `mqspg_write_rows_isolated_total` | `target` | 二分隔离出的坏行数 |
| `mqspg_raw_messages_written_total` / `_dropped_total` | — | 原始留存写入 / 队列满丢弃 |
| `mqspg_raw_queue_depth` | — | 留存队列当前深度 |
| `mqspg_retry_backlog` | `status` | 重试积压（PENDING / RUNNING / DLQ），每 15s 刷新 |
| `mqspg_pg_health_open` | `datasource` | PG 健康闸门是否放行（1/0） |
| `mqspg_pg_gate_closed_total` / `_opened_total` | `datasource` | 闸门关闭 / 恢复次数 |

**业务健康**（`/actuator/health` 中的 `mqsPg` 组件）：

- 任一数据源闸门关闭 → `DOWN`（消费已停）；
- 重试积压（`PENDING + RUNNING`）超过 `mqs-pg.health.retry-backlog-threshold`（默认 10000）→ `OUT_OF_SERVICE`；
- 原始留存丢弃率超过 `mqs-pg.health.raw-drop-ratio-threshold`（默认 0.1，
  且样本 ≥ `mqs-pg.health.raw-drop-sample-min`，默认 1000）→ `OUT_OF_SERVICE`。

**关联日志**：消费与重试入口把 `routeId` / `messageId` / `configVersion` 写入 MDC，
控制台日志 pattern 输出 `[route= msg= ver=]`，采集器可按字段检索。

---

## 目录结构

```text
mqs-pg/
├── prd.md                        # 需求文档
├── tech-design.md                # 技术设计（含 ADR 与实测结论）
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/mqspg/
│       │   │   ├── MqsPgApplication.java
│       │   │   ├── common/       # 领域模型（TargetRow/KeyTuple/MergeAction）、错误体系、JSONB、加解密
│       │   │   ├── config/       # 配置域：实体、Mapper、注册表、服务、DTO
│       │   │   ├── mqs/          # MQS SPI + mock 实现（平台实现待接入，见 ADR-01）
│       │   │   ├── transform/    # 转换引擎：JSONPath、JSLT、表达式求值、类型转换
│       │   │   ├── writer/       # 折叠、SQL 生成、PG 写入、表元数据、批次管理、多数据源池
│       │   │   ├── consumer/     # 消费循环、PG 健康闸门、消费状态机
│       │   │   ├── retry/        # 重试任务持久化、指数退避、调度器、DLQ 重放
│       │   │   ├── raw/          # 原始留存（异步攒批）与日分区创建 / 回收
│       │   │   └── console/      # 控制台 REST API（含 mock 投递入口）
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── application-dev.yml
│       │       ├── db/migration/ # Flyway：配置域 DDL
│       │       ├── db/demo/      # Flyway：dev 种子数据
│       │       └── mapper/       # MyBatis XML
│       └── test/java/com/mqspg/
├── scripts/
│   └── e2e-config-api.ps1        # 配置化闭环的端到端验收（自带建表与清理）
└── frontend/                     # Vue 3 + Vite + Element Plus 控制台
    ├── package.json
    ├── pnpm-workspace.yaml       # pnpm 12 构建脚本白名单
    ├── vite.config.js            # /api 代理到 :8080
    └── src/
        ├── api/                  # axios 封装 + 各领域接口
        ├── router/               # 路由表（meta.title/icon 驱动侧边栏与面包屑）
        ├── store/                # Pinia 用户态
        ├── layout/               # 侧边栏 / 顶栏 / 内容区
        ├── styles/
        └── views/                # dashboard、routes、datasources、retry、errors、raw、mock、login
```

---

## 实现须知：八个已踩过的坑

这几条都不会在**编译期**报错，而是以「静默不生效」或「运行时才炸」的形式出现。
完整说明见 tech-design.md 附录 E。

1. **Spring Boot 4 的自动配置已模块化**。只引 `flyway-core` 不会启用 Flyway——
   应用照常启动、日志里一行 Flyway 都没有、表也不会被创建，
   直到第一次查询才报 `relation does not exist`。必须用 `spring-boot-starter-flyway`。

2. **Jackson 2 与 Jackson 3 的边界**。Boot 4 的 Web 层默认 Jackson 3（`tools.jackson`），
   而 JSLT / json-path / JSONB 类型处理器基于 Jackson 2（`com.fasterxml.jackson`）。
   Jackson 2 的 `JsonNode` **不得**出现在 REST DTO 中，否则会被序列化成一堆
   `nodeType` / `bigDecimal` 之类的 bean 属性。跨 Web 边界前须经
   `JsonNodes#toPlain` 转为纯 Java 结构。

3. **pgjdbc 的 `currentSchema` 决定 `search_path`**。Flyway 的 `default-schema`
   只管 Flyway 自己；应用连接的 `search_path` 默认仍是 `public`，
   导致 MyBatis 的无限定 SQL 找不到表。JDBC URL 必须带
   `?currentSchema=mqs_pg,public`。

4. **MERGE 源 `VALUES` 中的参数必须显式 cast**（`?::bigint`）。
   裸参数 PostgreSQL 无法推断类型，会报
   `could not determine data type of parameter $1`。

5. **MyBatis-Plus 的 `updateById` 会忽略 null 字段**（默认 `FieldStrategy.NOT_NULL`）。
   重试任务「释放租约」的语义恰恰是把 `lease_until` 置为 `NULL`，
   用 `updateById` 会静默地什么都不做，留下一行 `PENDING` 却带着过期租约。
   凡是需要**写入 NULL** 的状态迁移，都必须用 `UpdateWrapper` 显式 `set(field, null)`。

6. **PostgreSQL 的 DEFAULT 分区会让分区创建永久卡死**。不允许创建与 DEFAULT
   分区中已有行冲突的新分区（报 `updated partition constraint for default partition
   would be violated`）；而 DEFAULT 里之所以有数据，恰恰是因为那一刻分区还没建
   （首次上线、维护任务未跑）。若只是「告警并跳过」，这个区间就**永远**建不出分区。
   正确做法是在一个事务里 `DETACH DEFAULT → 建分区 → 搬数据 → ATTACH 回来`，
   见 `RawPartitionMaintenance#repairAndCreate`。

7. **`LambdaUpdateWrapper.set(字段, jsonNode)` 会绕过实体上声明的类型处理器**。
   `content` / `upsert_keys` / `pool_config` 都是 JSONB 列，靠 `@TableField(typeHandler = JsonbTypeHandler.class)`
   才能正确绑定；`set()` 把裸 `JsonNode` 直接交给 JDBC 驱动，PostgreSQL 无法推断其
   SQL 类型，运行时报「无法推测实例 `ObjectNode` 的 SQL 类型」。
   凡是这些字段的更新都要用**实体 + `updateById`**（`insert` 一直是对的，所以只有更新路径会炸）。
   注意它与第 5 条正好相反：第 5 条是 `updateById` 跳过 null 让人吃亏，
   这里则是必须借它的类型处理器 —— 所以**要写 NULL 用 `UpdateWrapper`，
   要写 JSONB 用 `updateById`**。

8. **重试表的「活跃任务」部分唯一索引会与 MQ 重投语义冲突**。
   `uk_retry_active (route_id, message_id) WHERE status IN ('PENDING','RUNNING')`
   只允许同一消息有一条活跃任务；但 MQ 的至少一次投递意味着同一条消息可能**再次到达**
   （典型是 ACK 丢失）。若此时用普通 `insert` 登记重试，会撞唯一索引 → 调用方按约定
   「不 ACK」→ MQ 重投 → 再撞，形成**活锁**。因此登记必须**幂等**：
   用 `INSERT ... ON CONFLICT (route_id, message_id) WHERE status IN (...) DO UPDATE`
   刷新既有的活跃任务（见 `RtRetryTaskMapper#upsertActive`）。
   刷新时**不要**覆盖 `attempt` / `max_attempt`，否则重复投递会不断重置次数、永远到不了 DLQ。

另有一条**实现红线**：`MergeSqlBuilder` 中只允许存在**带单调守卫**的
`WHEN MATCHED`，绝不能补一个无守卫的兜底子句——那会让旧版本数据覆盖新数据，
且 PostgreSQL 官方文档中 MERGE 的示例恰好就是这个反例，不可照抄。

---

## 当前进度

| 阶段 | 状态 |
| --- | --- |
| 0 后端骨架（Boot 4.1.1 + MyBatis-Plus + Flyway，可启动、可迁移） | ✅ |
| 1 配置域（实体 / Mapper / 注册表 / 服务 / REST API） | ✅ |
| 2 PG Writer（MERGE RETURNING + 折叠 + 二分隔离）+ 测试 | ✅ |
| 3 MQS SPI + mock 消费者、PG 健康闸门、消费状态机、消费循环 | ✅ |
| 4 Transform Engine（JSONPath / JSLT / 表达式求值 / 类型转换 / 错误分类） | ✅ |
| 5 Batch Manager（双触发、版本绑定、结果 → ACK 映射） | ✅ |
| 6 重试表调度器（指数退避 + 租约）+ DLQ 重放 | ✅ |
| 7 Raw Message 分区留存 + TTL 维护 | ✅ |
| 8 前端（Vue 3 + Vite + Element Plus）+ 端到端联调 | ✅ |
| 9 页面配置化：路由/数据源/目标表增删改、映射编辑、发布与回滚 | ✅ |
| 10 优化第一轮：A 稳定性 / B 可观测性 / C 性能 / D 交付 | ✅ A1、B1–B3、C1–C3、D1–D3 已交付 |
| 11 优化第二轮：G 正确性 / H 自愈 / I 校验前置 / J 性能 / K 前端 / L 工程化 / M 集成测试 | 🚧 进行中：**G、H、I、J 全部交付，K 已交付 K1–K4、K6–K8**（见 `docs/optimization-plan.md` §5），L / M 其余待排期 |

阶段 0–8 的验证结果见 tech-design.md 附录 E，阶段 9 见附录 F。

测试怎么跑、最近一次全量结果、各类用例分布，**只写在 [`evolution.md`](evolution.md)**
（各里程碑条目的「验证」小节，带命令与实际输出）—— 这里不再重复具体数字。
单一事实来源的目的是：任何一处更新不会落空成另外两处的过时表述。
用例口径仍是 `@Test` 计数（无 `@ParameterizedTest` / `@Disabled`）。
其中跑在 **Testcontainers** 的 `postgres:18` 上的那些集成测试类
（配置域、约束、注册表、管线、消费、重试、留存、写入、健康分组）**本机不启 PostgreSQL、
仅需 Docker**；其余纯内存 / mock 测试无需 Docker。
用 `mvn test` 跑全部，用 `mvn test "-Dtest=PgWriterTest"` 跑单个类（PowerShell 下 `-D` 参数必须加引号）。
Flyway 依次执行 `V1__init_schema.sql` → `V2__constraints_and_indexes.sql`（V2 加草稿唯一索引、
四个状态 CHECK 与错误流水索引）。
健康检查分 `liveness`（`ping,diskSpace`）与 `readiness`（`db,mqsPg`）两组：
前者失败才该重启 Pod，后者失败只该摘流量。

优化工作的整体方案见 [`docs/optimization-plan.md`](docs/optimization-plan.md)（第一轮 A–F 的交付情况归档在 §3，
第二轮 G–M 的施工图在 §5），每项改动的背景 / 改动 / 验证 / 影响记录见 [`evolution.md`](evolution.md)。
第一轮已实现：**A1 重试入队幂等、B1 Micrometer 业务指标、B2 自定义健康指示器、B3 MDC 关联日志、
C1 列表接口消除 N+1、C2 `nextVersionNumber` 聚合查询、C3 留存超大报文跳过解析、
D1 集成测试迁移 Testcontainers、D2 CI、D3 e2e 脚本参数化**（详见 evolution.md）。
第二轮已实现：**K1 前端字段契约（含 `/api/meta/*` 枚举下发）、K3 确认框与错误码传递、
K2 运维页 onActivated 取数 + 可选轮询、K4 映射编辑器列隔离、
K6 前端按需引入与拆包（首屏 JS 1218→642 KB）、K7 加载失败显式报错、K8 小项清理、
G1 激活并发行锁、G2 发布/保存条件更新、G3 重试任务终态出口、G4 草稿唯一索引、
G5 运行状态行 upsert、G6 连接池提交后失效、G7 注册表状态与快照驱逐、
I1–I8 校验前置（标识符白名单、池配置、目标表部分唯一索引拦截、JSLT 预校验等）、
I7 状态列 CHECK、
H1 注册表周期性对账、H2 健康探针读缓存、H3 PG 健康聚合视图、
H4 异常映射（`NOT_FOUND` / `INVALID_INPUT` + traceId）、
H5 数据源缺失边界、H6 失败路径日志、H7 liveness / readiness 分组、
J1 重试攒批折叠、J2 租约按批放大并续租、J3 MERGE 文本缓存、J4 热路径去分配、
J5 版本列表下推排序限量、J6 分页 offset 溢出修复、J7 JSLT 编译缓存上限**。

### 仍待接入的部分

* **平台 MQS 实现**。`MqsConsumerFactory` SPI 已定稿，仓库内只有内存 mock
  （`MockMqsBroker`）。接入真实平台时新增一个实现类即可，消费循环、重试、留存均无需改动。
* **控制台鉴权**。REST API 目前无认证，前端只保留了 token / 角色的结构位置。
  这条与「`/api/datasources/test` 可对任意 `jdbcUrl` 试连」一起记在优化方案 §9 的决策记录里，
  属于已知风险，等鉴权专题一起处理。
* **发布前的试运行（Dry Run）**。目前发布只做静态校验（列覆盖、唯一索引、表达式可解析），
  不会真拿一条样本消息试写一次；`POST /routes/{id}/draft/preview` 的位置已经留好。
* **Topic 仍是自由输入**。`MockMqsBroker` 无法枚举 Topic，平台 MQS 的枚举接口也还没接，
  所以这一项没法做成下拉框；接好平台实现后可无缝换成下拉。
* **界面化的 JSLT 编辑器**。映射编辑器目前只覆盖 `mappings`，
  `jslt` 与 `mappingStrategy` 会原样保留但需要在库里或脚本里改。
