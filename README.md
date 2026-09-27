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
| 可观测 | Actuator + Prometheus 指标 |

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

`dev` profile 会额外加载 `db/demo` 下的种子数据：路由 1（`order-topic` / `order.updated`）
及其目标表 `biz_demo.orders`，用于本地联调。

连接参数可用环境变量覆盖：`MQS_PG_DB_URL`、`MQS_PG_DB_USER`、`MQS_PG_DB_PASSWORD`。

### 3. 验证

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/routes
curl http://localhost:8080/api/routes/1
```

### 4. 测试

```bash
cd backend
mvn test
```

`PgWriterTest` 是集成测试，需要本地 PostgreSQL 与 dev 种子数据可用。
后续会迁移到 Testcontainers。

---

## 目录结构

```text
mqs-pg/
├── prd.md                        # 需求文档
├── tech-design.md                # 技术设计（含 ADR 与实测结论）
└── backend/
    ├── pom.xml
    └── src/
        ├── main/
        │   ├── java/com/mqspg/
        │   │   ├── MqsPgApplication.java
        │   │   ├── common/       # 领域模型（TargetRow/KeyTuple/MergeAction）、错误体系、JSONB、加解密
        │   │   ├── config/       # 配置域：实体、Mapper、注册表、服务、DTO
        │   │   ├── mqs/          # MQS SPI（平台实现待接入，见 ADR-01）
        │   │   ├── writer/       # 折叠、SQL 生成、PG 写入、表元数据、多数据源池
        │   │   └── console/      # 控制台 REST API
        │   └── resources/
        │       ├── application.yml
        │       ├── application-dev.yml
        │       ├── db/migration/ # Flyway：配置域 DDL
        │       ├── db/demo/      # Flyway：dev 种子数据
        │       └── mapper/       # MyBatis XML
        └── test/java/com/mqspg/
```

---

## 实现须知：四个已踩过的坑

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
| 3 MQS SPI 平台实现与 mock 消费者、消费状态机 | ⏳ |
| 4 Transform Engine（JSONPath / JSLT / 类型转换 / 错误分类） | ⏳ |
| 5 Batch Manager（双触发、版本绑定、结果 → ACK 映射） | ⏳ |
| 6 重试表调度器（指数退避）+ DLQ | ⏳ |
| 7 Raw Message 分区留存 + TTL 维护 | ⏳ |
| 8 前端（Vue 3 + Vite + Element Plus）+ 端到端联调 | ⏳ |

阶段 0–2 的验证结果见 tech-design.md 附录 E.5。
