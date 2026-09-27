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
`RetrySchedulerTest` / `RawRetentionTest` / `ConfigAdminTest`）依赖本地 PostgreSQL 与
dev 种子数据；后续会迁移到 Testcontainers。它们都设置 `mqs-pg.consumer.auto-start=false`，
避免后台消费线程与测试争抢同一批消息。

其中驱动 `BatchManager` 的两个测试额外设置 `mqs-pg.raw.enabled=false`：
原始留存是**旁路**，开着会往 `raw_message` 写数据，与这两个测试的断言无关。

端到端验收（需要后端已启动）：

```powershell
pwsh -File scripts/e2e-config-api.ps1     # Windows PowerShell 5.1 亦可
```

这个脚本只用页面真正会调的接口，从「建数据源」一路走到「消息落库」，
自带建表与清理，可反复运行。它覆盖的正是**配置化闭环**：
新建数据源 → 试连 → 探查 schema/表/列 → 建目标表 → 建路由 → 存草稿 →
校验被拦下 → 发布 → 激活 → 投递 → 落库（含枚举 / 时间戳 / 常量 / JSONPath 四种转换）→ ACK →
删除保护 → 级联清理。

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

## 实现须知：七个已踩过的坑

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

阶段 0–8 的验证结果见 tech-design.md 附录 E，阶段 9 见附录 F。
当前 `mvn test`：**51 个用例全部通过**（其中 14 个是阶段 9 新增的配置域写操作测试），
`scripts/e2e-config-api.ps1` 端到端断言全部通过。

### 仍待接入的部分

* **平台 MQS 实现**。`MqsConsumerFactory` SPI 已定稿，仓库内只有内存 mock
  （`MockMqsBroker`）。接入真实平台时新增一个实现类即可，消费循环、重试、留存均无需改动。
* **控制台鉴权**。REST API 目前无认证，前端只保留了 token / 角色的结构位置。
* **集成测试容器化**。当前测试直连本地 PostgreSQL，计划迁移到 Testcontainers。
* **发布前的试运行（Dry Run）**。目前发布只做静态校验（列覆盖、唯一索引、表达式可解析），
  不会真拿一条样本消息试写一次；`POST /routes/{id}/draft/preview` 的位置已经留好。
* **Topic 仍是自由输入**。`MockMqsBroker` 无法枚举 Topic，平台 MQS 的枚举接口也还没接，
  所以这一项没法做成下拉框；接好平台实现后可无缝换成下拉。
* **界面化的 JSLT 编辑器**。映射编辑器目前只覆盖 `mappings`，
  `jslt` 与 `mappingStrategy` 会原样保留但需要在库里或脚本里改。
