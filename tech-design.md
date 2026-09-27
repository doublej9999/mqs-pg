# MQ → PostgreSQL 配置化数据同步系统 技术设计文档

**文档版本：** v1.0
**文档状态：** Draft
**上游文档：** `prd.md`（v1.0 Draft）
**目标 JDK：** Java 21 (LTS)
**编写日期：** 2026-09-25

---

# 0. 文档说明

## 0.1 范围

本文档定义 MQ → PostgreSQL 配置化数据同步系统的整体技术架构，覆盖：

* 技术选型与版本矩阵
* 模块划分与代码结构
* MQS 接入抽象（SPI）
* 消费、批处理、转换、写入、重试、留存全链路设计
* 数据模型与 DDL
* 一致性与幂等性论证
* 失败模式与测试策略

## 0.2 读者

后端开发、架构评审、DBA、测试。

## 0.3 术语

| 术语 | 含义 |
| --- | --- |
| Route | 一条 Topic + Tag → PG Table 的同步配置单元 |
| Config Version | Route 配置的不可变版本快照 |
| Batch | 一次 Flush 产生的记录集合 |
| Upsert Key | 冲突判定键，默认 `id`，由配置指定 |
| 折叠（Fold） | 同一 Batch 内按 Upsert Key 保序归并为一个最终记录 |
| 单调守卫 | `EXCLUDED.update_time > target.update_time` 的更新判据 |
| MQS | 平台消息队列服务（非 RocketMQ） |
| DLQ | 最终失败记录（Dead Letter） |

## 0.4 与 PRD 的偏差清单

本设计有一处**有意偏差**，需评审确认：

| 编号 | PRD 条款 | PRD 表述 | 本设计 | 理由 |
| --- | --- | --- | --- | --- |
| D-01 | §22 / §23 | 数据处理错误**不 ACK**，靠 MQ 重投重试 | 数据处理错误先**持久化到重试表**，随后 ACK；重试由应用侧调度器驱动 | 已确认决策：最大重试次数在应用侧、走 retry 表。语义等价性见 §17.1 论证 |
| D-02 | §7 | `ON CONFLICT ... DO UPDATE` | `MERGE ... RETURNING merge_action()` | 需要逐行 `INSERTED/UPDATED` 判定（§11）。`ON CONFLICT` 无法区分插入与更新 |

除上述两项，其余条款均按 PRD 实现。

---

# 1. 设计目标与约束

## 1.1 目标

1. 配置驱动：新增一个同步链路**零代码**，仅在 Web Console 配置。
2. 幂等：任意重复投递不改变 PG 最终状态。
3. 收敛：任意乱序到达，PG 最终收敛到业务 `update_time` 最新的记录。
4. 不丢：At-Least-Once，任何环节崩溃后消息仍可被重新处理。
5. 不阻塞：坏数据不阻塞好数据；Raw 落盘不阻塞主链路；PG 故障不空转。

## 1.2 硬约束

| 约束 | 来源 | 影响 |
| --- | --- | --- |
| 目标表必须有 `id` 与 `update_time` | PRD §5 | 配置发布校验必须检查 |
| 1 Message → 1 PG Row | PRD §4.1 | 不做拆分/多表 |
| Upsert Key 由配置指定，默认 `id` | PRD §5 | SQL 动态生成 |
| 重试用指数退避，默认最大 10 次 | PRD §23 / §26 / §54 | 重试表调度策略 |
| 数据处理错误默认不 ACK | PRD §22 | 见偏差 D-01 |
| PG 故障暂停消费，恢复后继续 | PRD §24 / §50 | Consumer 状态机 + 健康门控 |
| Raw Message 异步落盘、按时间分区、自动过期 | PRD §29–§33 | 分区表 + DETACH/DROP |
| Config Version 在**接收消息时**绑定 | PRD §18 | 缓冲消息需携带版本号 |
| 同 ID 同 `update_time` 以 MQ 消费顺序为准 | PRD §8 | 折叠算法的 tie-break 规则 |
| 同批同 Upsert Key 允许出现 | PRD §10 | 折叠算法 |
| V1 单 Route 单 Consumer，不做水平扩展 | PRD §3 / §41 | 简化并发模型 |

## 1.3 非目标

与 PRD §3 一致：一条消息多表、多目标表事务一致性、跨 Topic 全局顺序、Exactly-Once、Consumer 水平扩展。

---

# 2. 关键技术决策（ADR）

## ADR-01：MQS 通过 SPI 抽象，平台实现后置

**决策**：定义 `MqsConsumer` / `MqsMessage` / `MqsDeadLetterSink` 三个接口，运行时由 Spring 自动配置按 `mqs.vendor` 选择实现。V1 只提供接口 + 一个内存 Mock 实现用于测试。

**理由**：平台 MQS 的具体协议未知；抽象层隔离变更，且让核心链路的单元测试不依赖真实 MQ。

**已确认的 MQS 能力**：支持批量拉取；支持显式 ACK。

## ADR-02：重试下沉应用侧（重试表 + 调度器）

**决策**：不依赖 MQ 重投做重试。数据处理错误先写入 `rt_retry_task`（同库事务提交），再 ACK；由 `@Scheduled` 调度器按指数退避捞取重试；超过最大次数写入终态错误记录。

**理由**：已确认「最大重试次数在应用侧」；避免内存重试阻塞消费线程；退避曲线、次数、可观测性完全可控。

**代价**：ACK 语义从「写入 PG 成功后确认」变为「已持久化接管后确认」。正确性论证见 §17.1。

## ADR-03：不引入 Apache Camel 作为主干框架 ★

**决策**：运行时不依赖 Camel。转换所需的 JSONPath / JSLT 直接以库形式调用。

**理由（逐条对应 Camel 被期望承担的四项职责）**：

| 期望由 Camel 承担 | 实际情况 | 结论 |
| --- | --- | --- |
| 消费（RocketMQ 等组件） | 使用平台 MQS，**不存在对应 Camel 组件** | 自研 |
| 聚合（`aggregate` EIP 做双触发 Batch） | Camel 聚合器在缓冲时即返回，push 语义下会导致**写入前 ACK**，且无法按写入结果对单条消息分别 ACK | 自研 BatchManager |
| 重试 / DLQ（`errorHandler` / `deadLetterChannel`） | Camel 重投为**同线程 `Thread.sleep` 阻塞**；事务型 Exchange 下 `asyncDelayedRedelivery` 会被强制降级为同步。且已决策重试下沉应用侧 | 自研重试调度 |
| 写入（`camel-sql` 批量 Upsert） | 批量模式不返回逐行状态，无法产出 `INSERTED/UPDATED/SKIPPED_OLD_VERSION` | 自研 PG Writer |

**残留价值评估**：Camel 仅剩 `camel-jsonpath` / `camel-jslt` / `wireTap` 三项，前两项是库的薄封装，第三项用有界队列 + 独立线程池约 30 行代码即可实现。**引入成本（框架复杂度、Jackson 2/3 双轨依赖管理、团队学习曲线）高于收益。**

**适用 Camel 的前提条件**（若未来满足，可重评估）：
1. 改用 RocketMQ / Kafka 等有官方 Camel 组件的 MQ；
2. 接受聚合即持久化（outbox 模式）的 ACK 语义；
3. 团队已标准化 Camel 技术栈。

**备选方案（若组织要求使用 Camel）**：仅将 Camel 用于「转换段」，由自研 Consumer 通过 `ProducerTemplate.send("direct:transform", exchange)` 驱动；必须同时设置 `errorHandler(defaultErrorHandler().maximumRedeliveries(0))`，且**禁止**使用 `aggregate`、`camel-sql` 批量、`camel-rocketmq`。

## ADR-04：写入语义采用 `MERGE ... RETURNING merge_action()`

**决策**：要求 **PostgreSQL 17 及以上**。使用 `MERGE INTO ... USING ... WHEN MATCHED AND <guard> THEN UPDATE ... WHEN NOT MATCHED THEN INSERT ... RETURNING <key>, merge_action()`。

**理由**：
* 单语句原子完成「判新旧 + 插入 / 更新」，无先读后写竞态。
* `merge_action()` 官方返回 `INSERT` / `UPDATE`，直接满足 PRD §11 的逐行状态。
* 被守卫挡下（`incoming.update_time <= existing.update_time`）的行不进入 `RETURNING`，据此判定 `SKIPPED_OLD_VERSION`。

**版本要求**：`MERGE ... RETURNING` 为 PG 17 引入（PG 15/16 的 `MERGE` 无 `RETURNING`）。

**并发注意**：PG 官方文档指出 `MERGE` 与 `INSERT ... ON CONFLICT`「并非可互换」，后者才能在并发 `INSERT` 发生时可靠地转为 `UPDATE`。V1 采用「单 Route 单 Consumer + 串行写入」，同 Key 不并发，因此 `MERGE` 的并发短板不构成风险。**V2 若引入多实例并行消费同一 Route，必须重新评估**：届时需捕获 `40001`（serialization_failure）/ `23505`（unique_violation）并重试整批。

**降级方案（PG < 17）**：`INSERT ... ON CONFLICT (key) DO UPDATE SET ... WHERE EXCLUDED.update_time > t.update_time RETURNING key, (xmax = 0) AS inserted`。该方案依赖 `xmax` 内部行为，不推荐，仅在无法升级 PG 时使用。

## ADR-05：同批同 Key 必须应用层折叠

**决策**：进入 SQL 前，按 Upsert Key 折叠为唯一记录。

**理由**：PostgreSQL 官方文档对 `MERGE` 有明确要求：

> You should ensure that the join produces at most one candidate change row for each target row. In other words, a target row shouldn't join to more than one data source row. If it does, then only one of the candidate change rows will be used to modify the target row; later attempts to modify the row will cause an error. If the repeated action is an `INSERT`, this will cause a uniqueness violation, while a repeated `UPDATE` or `DELETE` will cause a cardinality violation.

`INSERT ... ON CONFLICT DO UPDATE` 同样不允许同一冲突键在一条语句中出现多次。

因此 PRD §10 描述的「A→B→C 三次操作」**无法用单条多值 SQL 表达**，必须在应用层先归并。折叠规则见 §7.3。

## ADR-06：配置版本采用不可变 JSONB 快照

**决策**：`cfg_version.content` 以 JSONB 保存完整配置快照，运行时只读该快照；不为 Mapping 建独立运行期表。

**理由**：保证版本不可变与原子发布；避免多表关联读到不一致的中间状态。PRD §47 的逻辑模型（Route / Target / Mapping / ConfigVersion）在物理上映射为快照内的嵌套结构，逻辑划分保持不变。

## ADR-07：Raw Message 使用 PG 原生声明式分区

**决策**：`raw_message` 按 `receive_time` 做 RANGE 日分区；过期用 `DETACH PARTITION` + `DROP TABLE`。

**理由**：直接对应 PRD §33，避免 `DELETE` 引发的 WAL / Vacuum / IO 压力；不引入对象存储，降低 V1 组件数。

## ADR-08：单应用多模块包结构，不拆分微服务

**决策**：一个 Spring Boot 可部署单元，内部以包边界隔离；不引入服务间调用。

**理由**：PRD §41 明确 V1 不做水平扩展；微服务化只会放大量序与配置同步成本。预留按包拆分的演进路径。

---

# 3. 技术选型

## 3.1 版本矩阵

| 层次 | 组件 | 版本 | 说明 |
| --- | --- | --- | --- |
| 语言 | Java (Temurin) | **21 LTS** | 虚拟线程、模式匹配、Record |
| 构建 | Maven | 3.9.x | |
| 框架 | Spring Boot | **4.1.1** | OSS 支持至 2027-07；同时管理 Jackson 2.21.5 与 3.1.5 |
| Web | `spring-boot-starter-webmvc` | 4.1.1 | Boot 4 中 `-web` 更名 |
| 持久化 | `spring-boot-starter-jdbc` + `NamedParameterJdbcTemplate` | 4.1.1 | 动态 SQL 场景比 ORM 可控 |
| 连接池 | HikariCP | Boot 管理 | |
| PG 驱动 | `org.postgresql:postgresql` | **42.7.13** | |
| 数据库 | PostgreSQL | **17+（推荐 18）** | `MERGE ... RETURNING` 硬要求 |
| 迁移 | Flyway（`flyway-core` + `flyway-database-postgresql`） | Boot 管理 | PG 需独立 database 模块 |
| JSONPath | `com.jayway.jsonpath:json-path` | **2.10.0** | ⚠ 3.0.0 需 Jackson 3，与 JSLT 冲突 |
| JSLT | `com.schibsted.spt.data:jslt` | **0.1.15** | 需显式提升其传递的 Jackson 2 |
| 表达式 | `com.googlecode.aviator:aviator` | **5.4.4** | ⚠ LGPL，商用需评估（见 §3.3） |
| 指标 | Micrometer + Prometheus + Actuator | Boot 管理 | |
| 调度 | Spring `@Scheduled` / `TaskScheduler` | Boot 管理 | 重试轮询、分区维护、健康探测 |
| 前端 | React 19 + TypeScript + Vite 8 | | |
| UI | Ant Design 6.6.5 | | Mapping 表格编辑器 |
| 图表 | Apache ECharts 6.1.0 | | Dashboard |
| 测试 | JUnit 5 + Testcontainers(postgresql 1.21.4) | | 核心语义必须在真实 PG 上验证 |

> **版本提示**：Spring Boot 3.5.x 的开源支持窗口已于 2026-06 结束，新项目不建议选用。Spring Boot 4.1.1 支持 Java 17–26，含 Java 21。

## 3.2 依赖冲突处理（关键）

`com.schibsted.spt.data:jslt:0.1.15` 的 POM 将 `jackson-databind:2.13.4.2` 声明为 `runtime` 作用域，存在覆盖 Spring Boot 管理版本的风险。必须在 `dependencyManagement` 中显式锁定 Jackson 2 线：

```xml
<properties>
    <jackson-2-bom.version>2.21.5</jackson-2-bom.version>
</properties>
```

并在构建中校验：

```bash
mvn dependency:tree -Dincludes=com.fasterxml.jackson.core:jackson-databind
```

**验收标准**：`jackson-databind` 只有一个版本，且为 `2.21.5`。

另：`json-path` 必须锁 **2.10.0**。其 3.0.0 版本要求 Java 17+ 且基于 Jackson 3，与 JSLT 的 Jackson 2 无法共存于同一 `JsonProvider` 路径。

## 3.3 待决策：表达式引擎

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| Aviator 5.4.4 | 功能完整、可编译缓存、性能好 | **LGPL 许可证**，闭源商用需法务确认 |
| 受限自研 DSL | 许可证干净、可白名单化、UI 易做下拉配置 | 需自研约 200 行 |

PRD §13.9 仅要求 `add / subtract / multiply / divide`，§13.5 示例为 `amount * 100` 与 `concat(...)`，**能力需求很小**。若法务对 LGPL 有顾虑，建议走受限自研 DSL。

> 本设计**默认采用 Aviator**，并将表达式求值封装在 `ExpressionEvaluator` 接口后，便于替换。

---

# 4. 总体架构

## 4.1 逻辑架构

```text
┌──────────────────────────────────────────────────────────────┐
│                        Web Console (React + AntD)            │
└───────────────────────────┬──────────────────────────────────┘
                            │ REST
┌───────────────────────────▼──────────────────────────────────┐
│  Console API  │  Config Service  │  Dry Run Engine           │
└───────────────────────────┬──────────────────────────────────┘
                            │ 配置读写 / 发布
┌───────────────────────────▼──────────────────────────────────┐
│                     Config Registry (内存快照)                │
│         routeId → ImmutableRouteConfig(version)               │
└───────────────────────────┬──────────────────────────────────┘
                            │ 只读
┌──────────┐   批量拉取   ┌──▼───────────────────────────────┐
│   MQS    │─────────────→│  MqsConsumer (SPI 实现)          │
└──────────┘              └──┬───────────────────────────────┘
                             │ List<MqsMessage>
                             │ ①接收时绑定 Config Version
              ┌──────────────┴───────────────┐
              │                              │
              ▼ (异步, 不阻塞)                ▼
     ┌──────────────────┐          ┌──────────────────────┐
     │  Raw Writer      │          │  Batch Manager       │
     │  有界队列 + 线程池 │          │  条数 + 时间 双触发    │
     └────────┬─────────┘          └──────────┬───────────┘
              │                               │ 按 version 分组
              ▼                               ▼
     ┌──────────────────┐          ┌──────────────────────┐
     │ raw_message      │          │  Transform Engine    │
     │ (日分区)         │          │  JSLT→JSONPath→Map   │
     └──────────────────┘          └──────────┬───────────┘
                                              │ Valid / Invalid
                              ┌───────────────┴──────────────┐
                              ▼                              ▼
                   ┌──────────────────────┐      ┌────────────────────┐
                   │ Fold by Upsert Key   │      │ Retry Service      │
                   │ 同 key 保序折叠        │      │ rt_retry_task      │
                   └──────────┬───────────┘      └─────────┬──────────┘
                              ▼                            │
                   ┌──────────────────────┐                │
                   │  PG Writer           │                │
                   │  MERGE ... RETURNING │                │
                   └──────────┬───────────┘                │
                              │ 逐行结果                    │
                              ▼                            ▼
                   ┌──────────────────────┐      ┌────────────────────┐
                   │  ACK 决策中心         │◄─────│ Retry Scheduler    │
                   │  ack / 不 ack / 暂停  │      │ 指数退避 + 租约     │
                   └──────────┬───────────┘      └─────────┬──────────┘
                              │                            ▼
                              ▼                  ┌────────────────────┐
                        MQS.ack()                │ rt_error_record    │
                                                 │ (终态 DLQ)          │
                                                 └────────────────────┘
```

**核心原则**：**ACK 只在「ACK 决策中心」发生**，且必须发生在「消息已被系统持久接管」或「PG 已写入成功」之后。

## 4.2 代码结构

单 Maven 工程，1 个可部署单元：

```text
mqs-pg/
├── pom.xml                          # 父 POM, dependencyManagement
├── mqs-pg-app/                      # 启动模块(唯一可部署单元)
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/            # Flyway 脚本
├── mqs-pg-common/                   # 领域模型、错误码、工具
│   └── .../common/
│       ├── error/                   # ErrorCode, ErrorStage, ProcessingException
│       └── model/                   # RouteConfig, MappingDef, TargetRow 等
├── mqs-pg-config/                   # 配置域
│   └── .../config/
│       ├── repository/              # cfg_* 表访问
│       ├── registry/                # ConfigRegistry 内存快照 + 热更新
│       ├── service/                 # 草稿/发布/激活/回滚
│       └── dryrun/                  # Dry Run 引擎
├── mqs-pg-mqs/                      # MQ 接入层
│   └── .../mqs/
│       ├── spi/                     # MqsConsumer, MqsMessage, MqsDeadLetterSink
│       ├── mock/                    # 内存实现(测试)
│       └── consumer/                # 消费循环、状态机、健康门控
├── mqs-pg-transform/                # 转换引擎
│   └── .../transform/
│       ├── jslt/                    # JSLT 编译缓存 + 执行
│       ├── jsonpath/                # JSONPath 提取 + 编译缓存
│       ├── mapping/                 # 字段映射、自动映射策略
│       ├── convert/                 # 类型转换器注册表
│       └── expr/                    # ExpressionEvaluator (Aviator)
├── mqs-pg-writer/                   # 批处理与写入
│   └── .../writer/
│       ├── batch/                   # BatchManager, Buffer, 双触发
│       ├── fold/                    # 同 key 保序折叠
│       ├── sql/                     # MERGE SQL 生成、多行 VALUES 拼装
│       ├── result/                  # 逐行结果解析与归类
│       └── datasource/              # 多数据源注册表
├── mqs-pg-raw/                      # Raw Message 存储
│   └── .../raw/
│       ├── writer/                  # 有界队列 + 异步落盘
│       └── partition/               # 分区预建 / TTL 清理
├── mqs-pg-retry/                    # 重试与 DLQ
│   └── .../retry/
│       ├── repository/              # rt_retry_task 抢占(租约)
│       ├── scheduler/               # 轮询 + 指数退避
│       └── service/                 # 入队 / 终态处理
└── mqs-pg-console/                  # 控制台后端 API
    └── .../console/
        ├── api/                     # REST Controller
        ├── dto/                     # 请求/响应模型
        └── security/                # 认证鉴权

mqs-pg-web/                          # 前端独立工程(React + Vite)
```

**包边界规则**（可用 ArchUnit 强制）：
* `writer` 不得依赖 `console`
* `transform` 不得依赖 `writer` / `retry`
* `mqs` 不得依赖 `transform` / `writer`（只通过领域模型交互）
* 仅 `app` 允许依赖全部模块

## 4.3 部署形态

```text
┌─────────────────────────────────────────────┐
│           单 JVM 进程 (mqs-pg-app)           │
│  Web Console API │ Runtime(消费+写入)        │
└───────┬─────────────────────┬───────────────┘
        │                     │
        ▼                     ▼
   平台 MQS             PostgreSQL
                       ├── cfg_*   配置域
                       ├── rt_*    运行域
                       └── raw_*   留存域
```

**配置库与业务目标库的关系**：配置域/运行域/留存域共用一个 PG 实例（独立 schema 或同库不同前缀）。业务目标表可以是**其他 PG 实例**（多数据源）。

## 4.4 线程模型

| 线程池 | 用途 | 规模 | 说明 |
| --- | --- | --- | --- |
| 消费线程 | 每 Route 1 个（V1 串行拉取） | route 数 | 不做并发，保证同 Route 内次序 |
| Transform/写线程 | Batch Flush 执行 | 每 Route 1 个 | 与消费线程同一线程或独立单线程 |
| Raw Writer 池 | 异步落盘 | 2–4 | 有界队列，队满丢弃并计数 |
| Retry Scheduler | 重试轮询 | 1–2 | `@Scheduled` |
| 分区维护 | 预建/清理 | 1 | `@Scheduled` |
| 健康探测 | PG 探活 | 1 | `@Scheduled` |

V1 **明确不做**同一 Route 内的并行消费（PRD §41）。消费线程串行 → 同 Route 内无并发写冲突 → 简化 ADR-04 的并发考量。

---

# 5. MQS 接入层

## 5.1 SPI 定义

```java
package com.example.mqspg.mqs.spi;

/** 平台 MQS 消费者抽象。实现必须线程安全。 */
public interface MqsConsumer extends AutoCloseable {

    /** 批量拉取。无消息时阻塞至多 awaitDuration 后返回空列表。 */
    List<MqsMessage> receive(int maxNum, Duration invisibleDuration);

    /** 确认消息。仅允许对本次 receive 返回且未确认的消息调用。 */
    void ack(MqsMessage message);

    /** 延长不可见时间，用于退避后重新投递（不确认）。 */
    void changeInvisibleDuration(MqsMessage message, Duration duration);

    @Override
    void close();
}

/** 单条消息视图。不得暴露具体 MQ 的协议细节。 */
public interface MqsMessage {
    String messageId();       // 全局唯一，用于幂等与去重
    String topic();
    String tag();
    byte[] body();
    Instant bornTime();       // 消息产生时间
    int deliveryAttempt();    // 已投递次数，从 1 开始
    String receiptHandle();   // 平台确认句柄，对上层不透明
}

/** 终态失败投递（可选能力）。 */
public interface MqsDeadLetterSink {
    void send(MqsMessage original, FailureMeta meta);
}
```

## 5.2 契约与语义

| 契约 | 要求 |
| --- | --- |
| ACK 幂等 | 重复 `ack` 不得导致消息丢失；实现层需容忍或抛出可忽略异常 |
| ACK 失败语义 | `ack` 抛异常时，消息可能被重新投递 → 必须保证重复处理安全 |
| 可见时间 | `invisibleDuration` 必须**大于**预期批处理耗时，否则并发重复投递 |
| 消息顺序 | 不承诺跨分区顺序；不得作为一致性依据（PRD §52） |
| `messageId` 稳定性 | 同一条消息多次投递必须返回相同 `messageId`（重试表去重依赖） |

> **`deliveryAttempt` 的定位**：V1 的重试次数由应用侧重试表控制，**不依赖 `deliveryAttempt`**。该字段保留用于可观测性与后续「MQ 重投 + 应用退避」混合模式。

## 5.3 平台实现接入点

新增实现类并注册自动配置即可，**不需要改动任何核心链路代码**：

```java
@AutoConfiguration
@ConditionalOnProperty(name = "mqs.vendor", havingValue = "platform")
public class PlatformMqsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MqsConsumerFactory platformMqsConsumerFactory(PlatformMqsProperties props) {
        return new PlatformMqsConsumerFactory(props);
    }
}
```

`MqsConsumerFactory` 负责按 `(topic, tag, consumerGroup)` 创建消费者实例；每个 Route 一个。

## 5.4 消费循环

```java
void consumeLoop(RouteRuntime rt) {
    while (rt.isRunning()) {
        // 1. PG 健康门控：不健康则暂停拉取
        if (!pgHealthGate.isOpen(rt.target())) {
            rt.transitionTo(PAUSED, "PostgreSQL unavailable");
            pgHealthGate.awaitOpen();          // 阻塞至恢复
            rt.transitionTo(RECOVERING);
            continue;
        }
        // 2. 批量拉取
        List<MqsMessage> batch;
        try {
            batch = consumer.receive(rt.pullSize(), rt.invisibleDuration());
        } catch (Exception e) {
            metrics.consumerError(rt); log.warn("receive failed", e); sleepQuietly(1s); continue;
        }
        if (batch.isEmpty()) continue;
        metrics.consumerMessages(rt, batch.size());
        // 3. 绑定版本 + 入缓冲（Raw 异步落盘在此触发）
        for (MqsMessage m : batch) {
            int version = configRegistry.activeVersion(rt.routeId());  // §18 接收时绑定
            rawWriter.submitAsync(m, rt.routeId(), version);           // §29 异步、不阻塞
            batchManager.append(new BufferedMessage(m, version, Instant.now()));
        }
        // 4. 双触发判断
        batchManager.flushIfNeeded(rt);
    }
}
```

---

# 6. 消费与生命周期

## 6.1 Consumer 状态机

```text
           ┌──────────────────────────────────────┐
           │                                      │
           ▼                                      │
      ┌─────────┐   PG 连续失败 N 次   ┌──────────┴──┐
      │ RUNNING │─────────────────────→│   PAUSED    │
      └────┬────┘                      └──────┬──────┘
           │                                  │ PG 探活连续成功 M 次
           │ 不可恢复异常                       ▼
           │                          ┌──────────────┐
           │                          │  RECOVERING  │
           │                          └──────┬───────┘
           │                                 │ 初始化完成
           │                                 └──────→ RUNNING
           ▼
      ┌─────────┐
      │  ERROR  │   需人工介入(配置缺失/表结构不匹配)
      └─────────┘
```

| 状态 | 含义 | 拉取 | 已缓冲消息 |
| --- | --- | --- | --- |
| RUNNING | 正常消费 | ✅ | 正常处理 |
| PAUSED | PG 故障，暂停拉取 | ❌ | 整批丢弃，不 ACK（MQ 会重投） |
| RECOVERING | 恢复中，做初始化与校验 | ❌ | — |
| ERROR | 不可恢复，需人工 | ❌ | 丢弃，不 ACK |

**默认阈值**：连续失败 3 次进入 PAUSED；连续探活成功 2 次进入 RECOVERING（PRD §50 示例）。

**PAUSED 时的缓冲处理**：整批不 ACK 并清空内存缓冲。理由：MQ 会重新投递，无需自行保存；且此刻 PG 不可用，重试表也写不进去（§9.5）。

## 6.2 PG 健康门控

```java
public interface PgHealthGate {
    boolean isOpen(TargetRef target);      // 快速判断,读内存标志
    void recordSuccess(TargetRef t);
    void recordFailure(TargetRef t, Throwable cause);
    void awaitOpen();                      // PAUSED 时阻塞等待
}
```

实现要点：
* 探活 SQL：`SELECT 1`，超时 3s。
* 状态为内存标志 + 落 `rt_consumer_state` 表供 Console 展示。
* **判定为「PG 级故障」的异常**：`SQLTransientConnectionException`、`ConnectException`、`SocketTimeoutException`、`SQLState 08xxx`、`53xxx`（资源不足）、`57P03`（cannot connect now）。
* **不触发暂停的异常**（属数据问题，走重试）：`23xxx`（完整性约束）、`22xxx`（数据异常）、`22003`（numeric overflow）。

## 6.3 消息生命周期

```text
receive
  │
  ├─→ [异步] raw_message 落盘 (失败仅计数,不影响主链路)
  │
  ├─ 绑定 Config Version
  │
  ├─ 入 Batch 缓冲
  │
  ▼ Flush
按 version 分组
  │
  ├─→ Transform
  │     ├─ 成功 → Valid
  │     └─ 失败 → Invalid ─→ 写 rt_retry_task → **ACK**
  │
  ▼ Fold by Upsert Key
  │
  ▼ PG MERGE ... RETURNING
  │
  ├─ 语句成功
  │     ├─ INSERTED        → ACK
  │     ├─ UPDATED         → ACK
  │     └─ SKIPPED_OLD     → ACK
  │
  └─ 语句失败
        ├─ PG 级故障 → PAUSED,整批不 ACK
        └─ 数据级故障 → 降级隔离(§9.3),好行 ACK,坏行进重试表
```

---

# 7. Batch Manager

## 7.1 双触发

```java
public void flushIfNeeded(RouteRuntime rt) {
    BatchConfig cfg = rt.batchConfig();
    // 条件一:条数
    if (buffer.size() >= cfg.batchSize()) { flush(rt, "SIZE"); return; }
    // 条件二:时间(由独立调度器触发)
}

@Scheduled(fixedDelay = 200)
public void flushByTime() {
    for (RouteRuntime rt : runningRoutes()) {
        BatchConfig cfg = rt.batchConfig();
        if (!buffer.isEmpty() && buffer.oldestWait() >= cfg.flushInterval()) {
            flush(rt, "TIME");
        }
    }
}
```

**默认值**（PRD §9）：`batchSize = 1000`，`flushInterval = 1s`。两者均可按 Route 配置。

## 7.2 缓冲与版本绑定

```java
public record BufferedMessage(
    MqsMessage handle,
    int        configVersion,   // 接收时刻绑定(PRD §18)
    Instant    receivedAt
) {}
```

**Flush 时按 `configVersion` 分组**：一个 Batch 内可能混有不同版本的消息（配置在缓冲窗口内发生发布）。必须分组后各自用对应版本快照处理，保证 PRD §18「10:03 flush 仍用 V1」的语义。

```java
Map<Integer, List<BufferedMessage>> byVersion =
    snapshot.stream().collect(groupingBy(BufferedMessage::configVersion));
for (var entry : byVersion.entrySet()) {
    RouteConfig cfg = configRegistry.get(routeId, entry.getKey());  // 版本快照
    processGroup(cfg, entry.getValue());
}
```

## 7.3 同 Key 保序折叠算法 ★

**需求**（PRD §10 + §8）：同批允许同 Key；按 MQ 消费顺序依次作用；`update_time` 大的胜出；`update_time` 相同时以**最后出现的**为准。

**算法**：

```java
/**
 * 按 Upsert Key 折叠,保证每个 Key 只产出一条记录。
 * 输入顺序 = MQ 消费顺序(必须保持)。
 */
public List<FoldedRecord> fold(List<CandidateRecord> candidates, List<String> upsertKeys) {

    // LinkedHashMap 保持首次出现顺序,便于日志与调试
    Map<KeyTuple, FoldedRecord> acc = new LinkedHashMap<>();

    for (CandidateRecord c : candidates) {
        KeyTuple key = KeyTuple.of(c.row(), upsertKeys);
        FoldedRecord prev = acc.get(key);

        if (prev == null) {
            acc.put(key, FoldedRecord.first(key, c));
            continue;
        }

        int cmp = compareUpdateTime(c.row(), prev.winnerRow(), cfg.updateTimeField());

        if (cmp > 0) {
            // 新记录更新 → 替换 winner,但必须继承已收集的 sources
            acc.put(key, prev.withWinner(c));
        } else if (cmp == 0) {
            // PRD §8:update_time 相同 → 以 MQ 消费顺序(最后出现)为准
            acc.put(key, prev.withWinner(c));
        } else {
            // 更旧 → 保留 winner,仅记录来源(用于 ACK)
            acc.put(key, prev.addSource(c));
        }
    }
    return new ArrayList<>(acc.values());
}

/** update_time 比较。null 处理:null 视为最小。 */
private int compareUpdateTime(TargetRow a, TargetRow b, String field) {
    Instant ta = a.getInstant(field), tb = b.getInstant(field);
    if (ta == null && tb == null) return 0;
    if (ta == null) return -1;
    if (tb == null) return 1;
    return ta.compareTo(tb);
}
```

**关键数据结构**：

```java
/**
 * winnerRow     最终进入 SQL 的那一行
 * sources       该 Key 下所有原始消息(含被淘汰的),用于 ACK 归属
 */
public record FoldedRecord(
    KeyTuple        key,
    TargetRow       winnerRow,
    List<MessageRef> sources
) {
    FoldedRecord withWinner(CandidateRecord c) {
        var merged = new ArrayList<>(sources);
        merged.add(c.ref());
        return new FoldedRecord(key, c.row(), List.copyOf(merged));
    }
    FoldedRecord addSource(CandidateRecord c) {
        var merged = new ArrayList<>(sources);
        merged.add(c.ref());
        return new FoldedRecord(key, winnerRow, List.copyOf(merged));
    }
    static FoldedRecord first(KeyTuple key, CandidateRecord c) {
        return new FoldedRecord(key, c.row(), List.of(c.ref()));
    }
}
```

> **易错点（务必注意）**：折叠会把 N 条消息合并为 1 行写入，但 **N 条消息都必须被 ACK**。
> 若只 ACK `winner` 对应的那条，其余 `sources` 会因未确认而被 MQ 无限重投。
> 因此 `FoldedRecord.sources` 必须完整收集，ACK 时按 Key 的最终结果统一确认（见 §7.4）。

**复杂度**：O(n)，单次哈希查找。1000 条批次的折叠耗时可忽略。

## 7.4 结果到 ACK 的映射

```java
void handleResults(RouteConfig cfg, List<FoldedRecord> folded, MergeResult result) {
    if (result.isStatementFailure()) {
        handleStatementFailure(cfg, folded, result);   // §9.3
        return;
    }
    for (FoldedRecord f : folded) {
        MergeAction action = result.actionOf(f.key());  // 缺失 → SKIPPED_OLD_VERSION
        switch (action) {
            case INSERT -> { metrics.pgInsert(cfg, 1); ackAll(f); }
            case UPDATE -> { metrics.pgUpdate(cfg, 1); ackAll(f); }
            case SKIPPED_OLD_VERSION -> { metrics.pgSkipOld(cfg, 1); ackAll(f); }
        }
    }
}

private void ackAll(FoldedRecord f) {
    for (MessageRef ref : f.sources()) {
        ackSafely(ref.handle());
        metrics.consumerAck(ref.routeId());
    }
}
```

**`MergeAction` 判定规则**：

| 情况 | 判定 |
| --- | --- |
| `RETURNING` 返回该 Key，`merge_action() = 'INSERT'` | `INSERTED` |
| `RETURNING` 返回该 Key，`merge_action() = 'UPDATE'` | `UPDATED` |
| `RETURNING` **未返回**该 Key 且语句成功 | `SKIPPED_OLD_VERSION` |

> 由于折叠已保证 Key 唯一，`RETURNING` 结果的 Key 集与输入 Key 集可直接做差集，判定无歧义。

---

# 8. Transform Engine

## 8.1 处理链

```text
raw bytes
   │
   ▼ JSON Parse (Jackson)          → JSON_PARSE_ERROR
   │
   ▼ JSLT (可选)                   → JSLT_ERROR
   │   JSON → Normalized JSON
   ▼ JSONPath 提取 (每字段)         → PATH_ERROR
   │
   ▼ 类型转换 / 常量 / 默认值        → TYPE_CONVERSION_ERROR
   │
   ▼ 枚举映射 / 时间格式 / 字符串    → ENUM_MAPPING_ERROR / DATETIME_FORMAT_ERROR
   │
   ▼ 表达式 (可选)                 → EXPRESSION_ERROR
   │
   ▼ 必填校验                      → MISSING_REQUIRED_FIELD
   │
   ▼ TargetRow
```

## 8.2 JSLT

* 每个 `(routeId, version)` 编译一次 `Expression`，缓存在 `ConcurrentHashMap`，随版本失效淘汰。
* JSLT 与 Mapping 职责分离（PRD §13.11）：JSLT 做 JSON→JSON 结构规整，Mapping 做规范化 JSON→PG Row。
* 编译失败在**配置发布阶段**拦截（§12.5），运行期不应出现编译异常。

```java
public final class JsltCache {
    private final ConcurrentMap<CacheKey, Expression> cache = new ConcurrentHashMap<>();

    public Expression get(long routeId, int version, String source) {
        return cache.computeIfAbsent(new CacheKey(routeId, version),
            k -> compile(source));   // 编译异常 → 配置发布失败
    }
}
```

## 8.3 JSONPath

* 使用 **JsonPath 2.10.0**，`Configuration` 启用 `Option.SUPPRESS_EXCEPTIONS` 关闭，保持缺省路径抛错以便区分「缺失」与「null」。
* 路径预编译并缓存（`JsonPath.compile`）。
* 缺省路径 + 配置了 `defaultValue` → 使用默认值；无 `defaultValue` 且 `required=true` → `MISSING_REQUIRED_FIELD`。

## 8.4 Mapping 与类型转换

**自动映射策略**（PRD §14 / §15）：

| 策略 | 规则 | 默认 |
| --- | --- | --- |
| `EXACT` | JSON 字段名 == 列名 | ✅ |
| `CAMEL_TO_SNAKE` | `orderId` → `order_id` | 可选 |

**类型转换注册表**（可扩展）：

| 声明类型 | 目标 Java 类型 | PG 类型 | 失败错误码 |
| --- | --- | --- | --- |
| `long` | `Long` | int8 | `TYPE_CONVERSION_ERROR` |
| `integer` | `Integer` | int4 | 同上 |
| `decimal` | `BigDecimal` | numeric | 同上 |
| `string` | `String` | text/varchar | 同上 |
| `boolean` | `Boolean` | bool | 同上 |
| `timestamp` | `Instant` | timestamptz | 同上 |
| `enum` | 映射值 | 任意 | `ENUM_MAPPING_ERROR` |

**时间格式**：配置 `pattern` + `zone`；无 `pattern` 时按 ISO-8601 解析；输出统一为 `Instant`。格式不匹配 → `DATETIME_FORMAT_ERROR`。

**数值精度**：`decimal` 一律使用 `BigDecimal`，禁止 `double` 中转，避免精度丢失。

## 8.5 表达式

```java
public interface ExpressionEvaluator {
    CompiledExpression compile(String expr);      // 发布期校验
    Object evaluate(CompiledExpression e, EvalContext ctx);
    void validate(String expr) throws ExpressionException;
}
```

* Aviator 实现使用 `AviatorEvaluatorInstance` 编译缓存；表达式变量来自规范化 JSON 顶层字段。
* **安全**：禁用反射与类加载相关功能，通过 `AviatorEvaluator` 的选项白名单限制可用函数（仅算术 + 字符串函数）。
* 求值异常 → `EXPRESSION_ERROR`。

## 8.6 错误分类

```java
public enum ErrorStage { RECEIVE, PARSE, JSLT, PATH, CONVERT, EXPRESSION, VALIDATE, WRITE, RETRY }

public enum ErrorCode {
    JSON_PARSE_ERROR, JSLT_ERROR, PATH_ERROR, TYPE_CONVERSION_ERROR,
    ENUM_MAPPING_ERROR, DATETIME_FORMAT_ERROR, EXPRESSION_ERROR,
    MISSING_REQUIRED_FIELD,
    PG_CONSTRAINT_VIOLATION, PG_TYPE_ERROR, PG_CONNECTION_ERROR, PG_TIMEOUT, PG_UNKNOWN
}
```

**错误严重级别**（决定去向）：

| 级别 | 含义 | 去向 |
| --- | --- | --- |
| `DATA` | 数据本身问题，重试无意义 | 直接终态 DLQ（不占重试次数） |
| `TRANSIENT` | 可能自愈 | 进重试表 |
| `FATAL` | PG 级故障 | 暂停消费，不 ACK |

> **设计要点**：`JSON_PARSE_ERROR`、`MISSING_REQUIRED_FIELD`、`ENUM_MAPPING_ERROR` 属于 `DATA`，重试 10 次必然失败，纯属浪费。建议**默认直接进 DLQ**，可在 Route 配置中开启「数据错误也重试」以兼容 PRD §23 的字面要求。此项需产品确认（见附录 C）。

---

# 9. PG Writer

## 9.1 生成 SQL

以 `orders(id, name, amount, update_time)`、Upsert Key = `id` 为例：

```sql
MERGE INTO public.orders AS tgt
USING (
    VALUES
        ($1::int8,       $2::text,    $3::numeric,  $4::timestamptz),
        ($5::int8,       $6::text,    $7::numeric,  $8::timestamptz)
) AS src (id, name, amount, update_time)
ON tgt.id = src.id
WHEN MATCHED AND src.update_time > tgt.update_time THEN
    UPDATE SET name = src.name,
               amount = src.amount,
               update_time = src.update_time
WHEN NOT MATCHED THEN
    INSERT (id, name, amount, update_time)
    VALUES (src.id, src.name, src.amount, src.update_time)
RETURNING tgt.id AS __key, merge_action() AS __action;
```

**生成规则**：

1. `USING (VALUES ...)` 行数 = 折叠后记录数。
2. 列顺序固定，参数类型显式 `::` 转换，避免驱动推断偏差。
3. `ON` 子句按 Upsert Key 逐列等值连接（支持复合键）。
4. `WHEN MATCHED AND` 的守卫条件：`src.<update_time_field> > tgt.<update_time_field>`（**严格大于**，PRD §7）。
5. **`update_time` 字段本身必须在 `UPDATE SET` 中**，否则旧值无法推进。
6. Upsert Key 列**不参与** `UPDATE SET`（主键不变）。
7. 禁止将 Upsert Key 与 `update_time` 之外的列作为 `ON` 条件（由配置校验拦截）。

**分块**：PG 单语句参数上限 65535。按 `列数 × 行数 ≤ 30000` 计算分块大小，避免超限：

```java
int maxRowsPerStatement = Math.max(1, 30000 / columnCount);
```

**PG 官方约束（必须在实现与发布校验中体现）**：

| 约束 | 说明 |
| --- | --- |
| 目标表不得是物化视图、外部表，或定义了 rule 的表 | `MERGE` 不支持，发布校验需拒绝 |
| 需具备目标列的 `UPDATE` 权限与表的 `INSERT` 权限 | `MERGE` 无独立权限；连接账号权限不足会在运行期才暴露，建议发布校验时探活 |
| `WHEN NOT MATCHED` 的条件与表达式只能引用**源表**列 | 只能引用 `src.*`，不可引用 `tgt.*` |
| `WHEN MATCHED` 的条件可同时引用源表与目标表 | 单调守卫依赖此能力 |
| 源行生成顺序**默认不确定** | 大批次可用 `source_query` 指定 `ORDER BY` 以避免并发死锁；V1 单消费者无此需求 |
| 每个目标行最多被一个源行匹配 | 由 §7.3 折叠保证，见 ADR-05 |

## 9.2 逐行结果判定

```java
public MergeResult merge(RouteConfig cfg, List<FoldedRecord> folded) {
    // 1. 分块
    for (List<FoldedRecord> chunk : partition(folded, maxRowsPerStatement)) {
        String sql = sqlBuilder.build(cfg, chunk);
        Map<String, Object> params = paramBinder.bind(cfg, chunk);
        // 2. 执行
        List<RowResult> rows = jdbc.query(sql, params, rs -> {
            List<RowResult> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new RowResult(
                    keyCodec.decode(cfg.upsertKeys(), rs, "__key"),
                    MergeAction.valueOf(rs.getString("__action"))));
            }
            return out;
        });
        // 3. 差集 → SKIPPED
        Set<KeyTuple> returned = rows.stream().map(RowResult::key).collect(toSet());
        for (FoldedRecord f : chunk) {
            if (!returned.contains(f.key())) {
                rows.add(new RowResult(f.key(), MergeAction.SKIPPED_OLD_VERSION));
            }
        }
        // 4. 累积
    }
}
```

**关键实现细节**：

* `RETURNING tgt.id` 对复合主键需返回多列，通过 `KeyCodec` 编码为单值或使用多列 `RETURNING`。
* 若 Upsert Key 不是主键，需确保存在**唯一索引**；否则 `MERGE` 的 `ON` 匹配会退化为全表扫描，且并发下可能重复插入。发布校验必须检查唯一约束（PRD §21）。
* **`merge_action()` 的返回值**（官方定义）：类型为 `text`，取值为 `'INSERT'` / `'UPDATE'` / `'DELETE'`。该函数**只能出现在 `MERGE` 的 `RETURNING` 列表中**，在查询的其他位置使用会报错。
* **`SKIPPED_OLD_VERSION` 的判定依据（官方确认）**：PG 文档明确说明 ——

  > If no final reachable clause is specified of either kind, it is possible that no action will be taken for a candidate change row.

  本设计的 `WHEN MATCHED` 带 `AND src.<update_time> > tgt.<update_time>` 守卫，且**刻意不提供无守卫的 `WHEN MATCHED` 兜底子句**，因此守卫为假的行不执行任何动作、不进入 `RETURNING`。据此把「输入 Key 集 − 返回 Key 集」判定为 `SKIPPED_OLD_VERSION`。

> ⚠ **实现红线（最易被改错的地方）**：不要为了让 SQL「看起来完整」而补上无守卫的 `WHEN MATCHED THEN UPDATE ...` 兜底子句 —— 那会让所有匹配行都执行更新，**直接破坏单调守卫**，并使 `SKIPPED_OLD_VERSION` 永远无法产生。
> 注意 PG 官方文档的 `MERGE` 示例正是这种「有守卫 + 无守卫」两段式写法（用于演示 clause 求值顺序），**照搬该示例会破坏本设计**。
> 补 `WHEN MATCHED THEN DO NOTHING` 虽不产生 `RETURNING` 输出、不破坏判定，但属冗余，同样不建议。

## 9.3 批次失败降级隔离 ★

单条 `MERGE` 是语句级原子：**一条坏数据会让整批失败**。PRD §49 要求「Invalid Record 不应该阻塞 Valid Record」，因此在语句失败时需降级隔离。

```java
void handleStatementFailure(RouteConfig cfg, List<FoldedRecord> folded, MergeResult result) {
    Throwable cause = result.cause();

    // 情况一:PG 级故障 → 暂停,整批不 ACK
    if (isPgLevelFailure(cause)) {
        pgHealthGate.recordFailure(cfg.target(), cause);
        // 不 ACK;内存缓冲已清空,MQ 会重投
        return;
    }

    // 情况二:数据级故障 → 降级隔离
    if (isDataLevelFailure(cause)) {
        if (folded.size() == 1) {
            // 单条即坏 → 直接判坏
            enqueueToRetry(cfg, folded.get(0), cause);
            return;
        }
        // 二分,递归定位坏行
        int mid = folded.size() / 2;
        for (List<FoldedRecord> half : List.of(folded.subList(0, mid),
                                               folded.subList(mid, folded.size()))) {
            MergeResult r = merge(cfg, half);
            if (r.isStatementFailure()) handleStatementFailure(cfg, half, r);
            else handleResults(cfg, half, r);
        }
    }
}
```

**二分隔离的成本**：最坏 `O(log n)` 次额外语句；1000 条批次定位 1 条坏数据约需 10 次重试，代价可接受。

**判定为数据级故障的 SQLState**：

| SQLState | 含义 |
| --- | --- |
| `22001` | string_data_right_truncation |
| `22003` | numeric_value_out_of_range |
| `22P02` | invalid_text_representation |
| `23502` | not_null_violation |
| `23503` | foreign_key_violation |
| `23505` | unique_violation（非 Upsert Key 的其他唯一约束） |
| `23514` | check_violation |

> `23505` 需要区分：若冲突在 Upsert Key 上则说明折叠有 bug（应告警），否则按数据错误处理。

## 9.4 多数据源

```java
@Component
public class TargetDataSourceRegistry {
    private final Map<Long, JdbcTemplate> byTargetId = new ConcurrentHashMap<>();

    public JdbcTemplate forTarget(TargetRef ref) {
        return byTargetId.computeIfAbsent(ref.targetId(), id -> create(id));
    }
}
```

* 每个 `cfg_datasource` 一个 HikariCP 池，池大小按配置。
* 密码以 AES-GCM 加密存储，密钥来自环境变量 / KMS，**不落配置文件**。
* 目标表结构元数据（列名、类型、可空性）启动时读取并缓存，配置发布时刷新。

## 9.5 事务边界

| 操作 | 事务 |
| --- | --- |
| `MERGE` 批次写入 | 单事务，全部成功或全部回滚 |
| `rt_retry_task` 入队 | 独立短事务，**在 ACK 之前提交** |
| `rt_error_record` 终态写入 | 独立短事务 |
| `raw_message` 落盘 | 独立事务（异步线程），失败不回滚主链路 |

**为什么重试入队必须在 ACK 之前提交**：这是 D-01 偏差成立的前提。若先 ACK 再入队，进程在两者之间崩溃会导致消息永久丢失（违反 At-Least-Once）。顺序必须为：

```text
BEGIN → INSERT rt_retry_task → COMMIT → ack(message)
```

若 `COMMIT` 失败（PG 不可用）→ 不 ACK + 暂停消费，与 PG 故障路径一致。
若 `ack` 失败 → 消息重投 → 重试表可能产生重复行，由 §10.2 的幂等去重处理。

---

# 10. 重试与 DLQ

## 10.1 触发条件

| 场景 | 是否进重试表 | 说明 |
| --- | --- | --- |
| 数据错误（`TRANSIENT`） | ✅ | 类型转换失败、JSLT 错误等 |
| 数据错误（`DATA`） | ⚙ 可配置 | 默认直接 DLQ（见 §8.6） |
| 批次降级隔离出的坏行 | ✅ | §9.3 |
| PG 级故障 | ❌ | 暂停消费，不 ACK，靠 MQ 重投 |
| Raw 落盘失败 | ❌ | 仅计数告警（PRD §29） |

## 10.2 表结构

```sql
CREATE TABLE rt_retry_task (
    id              BIGSERIAL    PRIMARY KEY,
    route_id        BIGINT       NOT NULL,
    config_version  INTEGER      NOT NULL,
    message_id      VARCHAR(128) NOT NULL,
    topic           VARCHAR(256) NOT NULL,
    tag             VARCHAR(256),
    payload         BYTEA,                    -- 原始消息体(用于独立重放)
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

-- 到期任务扫描(部分索引,只覆盖待处理)
CREATE INDEX idx_retry_due
    ON rt_retry_task (next_retry_at)
    WHERE status = 'PENDING';

-- 幂等去重:同一消息同一 Route 不重复入队(仅约束活跃状态)
CREATE UNIQUE INDEX uk_retry_active
    ON rt_retry_task (route_id, message_id)
    WHERE status IN ('PENDING', 'RUNNING');

-- 租约超时回收
CREATE INDEX idx_retry_lease
    ON rt_retry_task (lease_until)
    WHERE status = 'RUNNING';
```

**幂等入队**：

```sql
INSERT INTO rt_retry_task (route_id, config_version, message_id, topic, tag, payload,
                           error_stage, error_code, error_message, max_attempt, next_retry_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
ON CONFLICT (route_id, message_id) WHERE status IN ('PENDING','RUNNING') DO NOTHING;
```

> 部分唯一索引的 `ON CONFLICT` 需带相同的 `WHERE` 谓词才能正确推断冲突目标。若目标 PG 版本对此支持不佳，退化为「先查后插」并接受极小概率重复（重复重试是幂等的，仅浪费算力）。

## 10.3 状态机

```text
                  enqueue
                     │
                     ▼
                ┌─────────┐
     ┌─────────→│ PENDING │
     │          └────┬────┘
     │               │ 调度器抢占(租约)
     │               ▼
     │          ┌─────────┐
     │  退避     │ RUNNING │
     │  attempt++└────┬────┘
     │               │
     │      ┌────────┼─────────┐
     │      ▼        ▼         ▼
     │  成功     可重试失败   不可重试/超限
     │      │        │         │
     │      ▼        │         ▼
     │ ┌─────────┐   │   ┌──────────┐
     └─│SUCCEEDED│   └───│   DLQ    │
       └─────────┘       └──────────┘
```

| 状态 | 含义 |
| --- | --- |
| PENDING | 待处理，等待 `next_retry_at` 到期 |
| RUNNING | 已被某实例抢占（持有租约） |
| SUCCEEDED | 重试成功 |
| DLQ | 终态失败，已写入 `rt_error_record` |
| CANCELLED | 人工取消 |

## 10.4 指数退避

```java
public Instant nextRetryAt(int attempt, RetryPolicy p) {
    // attempt 从 1 开始:1s, 2s, 4s, 8s, 16s, ...
    long delayMs = (long) (p.initialDelayMs() * Math.pow(p.multiplier(), attempt - 1));
    delayMs = Math.min(delayMs, p.maxDelayMs());
    // 抖动,避免大量任务同时到期(thundering herd)
    delayMs = jitter(delayMs, p.jitterRatio());
    return Instant.now().plusMillis(delayMs);
}
```

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `initialDelayMs` | 1000 | PRD §26 示例起点 |
| `multiplier` | 2.0 | 1s → 2s → 4s → 8s → 16s |
| `maxDelayMs` | 60000 | 最大等待时间可配置（PRD §26） |
| `jitterRatio` | 0.15 | ±15% 抖动 |
| `maxAttempt` | 10 | PRD §23 / §54 |

## 10.5 调度与租约

```java
@Scheduled(fixedDelayString = "${mqs-pg.retry.poll-interval:1000}")
public void poll() {
    if (!pgHealthGate.isOpenAll()) return;          // PG 故障时不空转
    List<RetryTask> due = retryRepo.claimDue(200, Duration.ofMinutes(5));
    for (RetryTask t : due) {
        try {
            retryService.reprocess(t);               // 走完整 Transform + 单条写入
            retryRepo.markSucceeded(t.id());
        } catch (PgLevelException e) {
            retryRepo.release(t.id());               // 归还,不改 attempt
            pgHealthGate.recordFailure(...);
            return;
        } catch (RetryableException e) {
            retryRepo.reschedule(t.id(), e);         // attempt++, 计算 next_retry_at
        } catch (NonRetryableException e) {
            retryRepo.moveToDlq(t.id(), e);          // 写 rt_error_record
        }
    }
}
```

**抢占 SQL（`SKIP LOCKED`，为 V2 多实例预留）**：

```sql
UPDATE rt_retry_task t
SET status = 'RUNNING',
    lease_until = now() + ($2 || ' seconds')::interval,
    updated_at = now()
WHERE t.id IN (
    SELECT id FROM rt_retry_task
    WHERE status = 'PENDING' AND next_retry_at <= now()
    ORDER BY next_retry_at
    LIMIT $1
    FOR UPDATE SKIP LOCKED
)
RETURNING t.*;
```

**租约回收**：独立定时任务把 `status='RUNNING' AND lease_until < now()` 的任务重置为 `PENDING`，用于实例崩溃后的自愈。

## 10.6 终态 DLQ

```sql
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
    is_final        BOOLEAN      NOT NULL DEFAULT false,   -- true = DLQ 终态
    replayed_at     TIMESTAMPTZ,                            -- 人工重放时间
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_error_route_time ON rt_error_record (route_id, error_time DESC);
CREATE INDEX idx_error_message    ON rt_error_record (message_id);
CREATE INDEX idx_error_final      ON rt_error_record (route_id) WHERE is_final;
```

该表同时满足 PRD §31（Error Metadata）与 §23（DLQ / Final Failure）。字段与 PRD §31 逐项对应，并增设 `is_final` 与 `replayed_at` 支持人工重放。

**人工重放**：Console 提供「重放」操作，将 `rt_error_record` 按当前 active 版本重新构造 `rt_retry_task`，`attempt` 归零。重放天然幂等（PG 单调守卫）。

## 10.7 与 PRD 的偏差说明（D-01）

| 维度 | PRD 原意 | 本设计 | 影响 |
| --- | --- | --- | --- |
| 重试载体 | MQ 重投 | 应用侧重试表 | 退避与次数可控 |
| ACK 时机 | 最终失败后才 ACK | 入队后立即 ACK | ACK 语义变为「已持久接管」 |
| 崩溃安全 | 由 MQ 保证 | 由 `rt_retry_task` 事务保证（提交先于 ACK） | 等价 |
| 最终结果 | DLQ 后 ACK | DLQ 记录写入（已 ACK 过） | 等价 |

**结论**：偏差不改变可靠性语义与最终状态，仅改变重试的承载位置。§17.1 给出论证。

---

# 11. Raw Message 存储

## 11.1 表与分区

```sql
CREATE TABLE raw_message (
    id              BIGSERIAL    NOT NULL,
    message_id      VARCHAR(128) NOT NULL,
    route_id        BIGINT       NOT NULL,
    topic           VARCHAR(256) NOT NULL,
    tag             VARCHAR(256),
    receive_time    TIMESTAMPTZ  NOT NULL,
    config_version  INTEGER,
    payload         JSONB,
    PRIMARY KEY (id, receive_time)          -- 分区键必须包含在主键内
) PARTITION BY RANGE (receive_time);

CREATE INDEX idx_raw_message_id ON raw_message (message_id);
CREATE INDEX idx_raw_route_time ON raw_message (route_id, receive_time DESC);
```

**日分区**（PRD §32）：

```sql
CREATE TABLE raw_message_p20260925 PARTITION OF raw_message
    FOR VALUES FROM ('2026-09-25 00:00:00+08') TO ('2026-09-26 00:00:00+08');
```

> **注意**：分区表的主键必须包含分区键，因此主键为 `(id, receive_time)`。`id` 由序列生成，全局唯一性由序列保证，但**不能声明为独立主键**。

**payload 存储**：`JSONB` 便于查询与重放；若消息体可能非 JSON（解析失败场景），使用 `JSONB` 会写入失败 —— 此时降级存 `payload_text TEXT`，或不加 `jsonb` 约束。建议：`payload JSONB` + `payload_raw TEXT`（原始字节的 Base64 或文本），解析成功时写 JSONB，失败时只写 raw。

## 11.2 异步落盘

```java
@Component
public class RawWriter {
    private final BlockingQueue<RawEvent> queue = new ArrayBlockingQueue<>(10_000);
    private final ExecutorService pool = Executors.newFixedThreadPool(2, ...);

    /** 主链路调用:绝不阻塞、绝不抛异常 */
    public void submitAsync(MqsMessage msg, long routeId, int version) {
        RawEvent e = new RawEvent(msg.messageId(), msg.topic(), msg.tag(),
                                  Instant.now(), version, msg.body());
        if (!queue.offer(e)) {
            // 队满:丢弃 + 计数(PRD §29:不阻塞主流程)
            metrics.rawWriteError(routeId, "QUEUE_FULL");
            log.warn("raw queue full, dropped messageId={}", msg.messageId());
        }
    }

    @PostConstruct
    void start() {
        for (int i = 0; i < 2; i++) {
            pool.submit(this::drainLoop);
        }
    }

    private void drainLoop() {
        List<RawEvent> buf = new ArrayList<>(200);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                RawEvent first = queue.poll(500, MILLISECONDS);
                if (first == null) continue;
                buf.add(first);
                queue.drainTo(buf, 199);
                batchInsert(buf);                  // 单事务批量插入
                metrics.rawWriteSuccess(buf.size());
                buf.clear();
            } catch (Exception e) {
                metrics.rawWriteError(null, "WRITE_FAILED");
                log.warn("raw write failed", e);
                buf.clear();                       // 丢弃,Raw 不是一致性必要环节
            }
        }
    }
}
```

**设计要点**：
* 有界队列 + 非阻塞 `offer`，满足 PRD §29「Raw Writer 异常不应阻塞 PG 主流程」。
* Raw 与主链路**无事务耦合**：Raw 写失败不影响 PG 写入与 ACK。
* 队满策略为**丢弃 + 计数**。若业务要求 Raw 不丢，需改为「背压」或落本地文件，属 V2 议题。

## 11.3 TTL 与分区维护

```java
@Scheduled(cron = "${mqs-pg.raw.maintenance-cron:0 30 0 * * *}")
public void maintainPartitions() {
    LocalDate today = LocalDate.now(zone);
    // 1. 预建未来 3 天分区(避免插入落到默认分区)
    for (int i = 0; i <= 3; i++) createPartitionIfAbsent(today.plusDays(i));
    // 2. 清理超期分区
    LocalDate cutoff = today.minusDays(retentionDays);   // 默认 7 天(PRD §33)
    for (String part : listPartitionsOlderThan(cutoff)) {
        jdbc.execute("ALTER TABLE raw_message DETACH PARTITION " + part);
        jdbc.execute("DROP TABLE " + part);              // 物理删除
    }
}
```

* **禁止** `DELETE FROM raw_message WHERE receive_time < ...`（PRD §33）。
* 分区名从 `pg_class` 读取，**不得**由外部输入拼接（防 SQL 注入）。
* 建议额外创建一个 `DEFAULT` 分区兜底，防止分区缺失导致插入失败。
* ⚠ **DEFAULT 分区与预建分区的冲突**：若 `DEFAULT` 分区中已存在属于待建日期范围的行，`CREATE TABLE ... PARTITION OF` 会因分区约束冲突而失败。因此预建任务必须**先检查 `DEFAULT` 分区是否为空**。V1 策略：预建未来 3 天分区；每次维护时校验 `DEFAULT` 分区为空，非空则**告警并暂停清理**，由人工把行迁出后再建分区。这比自动迁移更安全。

---

# 12. 配置管理

## 12.1 逻辑模型

对应 PRD §47：

```text
DataSource  →  物理 PG 连接
Target      →  目标表 + Upsert Key + update_time 字段
Route       →  Topic + Tag + Target 绑定
ConfigVersion → 不可变配置快照(含 Mapping / Transform)
```

## 12.2 物理模型

```sql
CREATE TABLE cfg_datasource (
    id            BIGSERIAL     PRIMARY KEY,
    name          VARCHAR(64)   NOT NULL UNIQUE,
    jdbc_url      VARCHAR(1024) NOT NULL,
    username      VARCHAR(128)  NOT NULL,
    password_enc  TEXT          NOT NULL,          -- AES-GCM
    pool_config   JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE cfg_target (
    id                BIGSERIAL   PRIMARY KEY,
    name              VARCHAR(128) NOT NULL UNIQUE,
    datasource_id     BIGINT      NOT NULL REFERENCES cfg_datasource(id),
    schema_name       VARCHAR(64) NOT NULL DEFAULT 'public',
    table_name        VARCHAR(64) NOT NULL,
    upsert_keys       JSONB       NOT NULL,         -- ["id"]
    update_time_field VARCHAR(64) NOT NULL DEFAULT 'update_time',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (datasource_id, schema_name, table_name)
);

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

CREATE TABLE cfg_version (
    id           BIGSERIAL   PRIMARY KEY,
    route_id     BIGINT      NOT NULL REFERENCES cfg_route(id) ON DELETE CASCADE,
    version      INTEGER     NOT NULL,
    status       VARCHAR(16) NOT NULL,
    content      JSONB       NOT NULL,        -- 不可变快照
    change_note  TEXT,
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    UNIQUE (route_id, version)
);
CREATE INDEX idx_cfg_version_status ON cfg_version (route_id, status);
```

`cfg_version.content` 结构示例：

```json
{
  "mappingStrategy": "EXACT",
  "jslt": null,
  "mappings": [
    { "target": "id",     "source": "$.id",     "transform": {"type": "long"},
      "required": true,  "defaultValue": null, "constant": null, "expression": null },
    { "target": "amount", "source": "$.amount", "transform": {"type": "decimal"},
      "required": true,  "defaultValue": "0" },
    { "target": "status", "source": "$.status",
      "transform": {"type": "enum", "mapping": {"CREATED": 1, "PAID": 2, "CANCELLED": 3}},
      "required": false, "defaultValue": "UNKNOWN" },
    { "target": "source", "constant": "ORDER_SYSTEM" }
  ]
}
```

**运行状态表**：

```sql
CREATE TABLE rt_consumer_state (
    route_id       BIGINT      PRIMARY KEY REFERENCES cfg_route(id),
    status         VARCHAR(16) NOT NULL DEFAULT 'PAUSED',
    reason         TEXT,
    bind_version   INTEGER,
    last_error_at  TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

## 12.3 版本生命周期

```text
DRAFT ──提交校验──→ VALIDATING ──通过──→ VALID ──发布──→ PUBLISHED ──激活──→ ACTIVE
  │                     │                                                        │
  │                     └──失败──→ DRAFT(带校验错误)                             │
  └──────────────────────────────────────────────────────────────────────────────┘
                                                                        INACTIVE ←┘
```

| 状态 | 可编辑 | 可被消费使用 |
| --- | --- | --- |
| DRAFT | ✅ | ❌ |
| VALIDATING | ❌ | ❌ |
| VALID | ✅（编辑后回 DRAFT） | ❌ |
| PUBLISHED | ❌ | 仅历史消息绑定 |
| ACTIVE | ❌ | ✅ 新消息绑定 |
| INACTIVE | ❌ | 仅被已绑定消息使用 |

**PRD §19「历史版本不可被运行中的消息动态替换」**：通过不可变快照 + Registry 保留最近 N 个版本实现。已绑定消息持有的版本对象在内存中不会被修改或回收，直到其 Batch 处理完毕。

## 12.4 版本绑定

```java
public interface ConfigRegistry {
    /** 读取当前 ACTIVE 版本号,接收消息时调用(PRD §18) */
    int activeVersion(long routeId);

    /** 按版本读取不可变快照,凭 version 而非 routeId 取 */
    RouteConfig get(long routeId, int version);
}
```

**内存结构**：

```java
ConcurrentHashMap<Long, AtomicReference<RouteRuntimeConfig>> active;   // routeId → ACTIVE
ConcurrentHashMap<VersionKey, RouteConfig> snapshots;                   // (routeId, version) → 快照
```

* 发布/激活时原子替换 `active`，并写入 `snapshots`。
* `snapshots` 采用 LRU 上限（如保留最近 20 个版本），但**被引用中的版本不得淘汰**（用引用计数或弱引用 + 到期延迟清理）。
* 消费循环每轮读取一次 `activeVersion`，不缓存跨轮次，保证发布即时生效。

## 12.5 Dry Run 发布验证

对应 PRD §20 / §21。全程在**事务中执行并回滚**。

```java
public DryRunResult dryRun(long routeId, String sampleJson) {
    var cfg = draftConfig(routeId);
    var result = new DryRunResult();

    // MQ 校验
    result.add(validateTopicTag(cfg));                       // §21 MQ

    // PG 校验(读元数据,不写)
    var meta = targetMetaReader.read(cfg.target());
    result.add(validateTable(meta, cfg));
    result.add(validateColumns(meta, cfg));
    result.add(validatePrimaryKey(meta, cfg));
    result.add(validateUpsertKeyUnique(meta, cfg));          // 必须有唯一索引
    result.add(validateUpdateTimeField(meta, cfg));
    result.add(validateNotNullCoverage(meta, cfg));          // §16 必填字段

    // 转换校验
    result.add(validateJslt(cfg));                           // 编译
    result.add(validateJsonPath(cfg));                       // 编译
    result.add(validateExpressions(cfg));                    // 编译
    result.add(validateTypeConversions(cfg));

    if (result.hasError()) return result;

    // 端到端 Dry Run
    var normalized = jslt.apply(sampleJson);
    var row = mappingEngine.map(normalized, cfg);
    result.setNormalizedJson(normalized);
    result.setTargetRow(row);
    result.setSql(sqlBuilder.build(cfg, List.of(FoldedRecord.of(row))));

    // 真实事务 + 回滚
    TransactionTemplate tx = new TransactionTemplate(tm);
    tx.setPropagationBehavior(PROPAGATION_REQUIRES_NEW);
    tx.executeWithoutResult(status -> {
        mergeExecutor.execute(cfg, List.of(row));
        status.setRollbackOnly();                            // §20 ROLLBACK
    });
    result.markPgDryRunPassed();
    return result;
}
```

**PRD §16 必填字段校验规则**：目标列若为 `NOT NULL`，且无 DB 默认值，则必须满足「有 Mapping 或有 Constant 或有 Default」之一，否则**配置发布失败**。

## 12.6 配置缓存失效

```text
发布/激活 → 更新 cfg_* 表 → 刷新 ConfigRegistry 内存快照 → 通知 RouteRuntime
```

* V1 单实例，直接内存刷新即可，无需分布式通知。
* V2 多实例时引入 PG `LISTEN/NOTIFY` 或版本号轮询（`SELECT max(updated_at)` 每 5s）触发刷新。

---

# 13. Web Console

## 13.1 页面与 API

| 页面（PRD §34） | 关键 API |
| --- | --- |
| Dashboard | `GET /api/dashboard/summary` |
| Data Routes | `GET/POST/PUT/DELETE /api/routes` |
| Configuration | `GET/PUT /api/routes/{id}/draft` |
| Config Versions | `GET /api/routes/{id}/versions`、`POST .../publish`、`POST .../activate`、`POST .../rollback` |
| Message Errors | `GET /api/errors`、`POST /api/errors/{id}/replay` |
| Raw Messages | `GET /api/raw-messages` |
| Runtime Status | `GET /api/runtime/routes`、`POST /api/runtime/routes/{id}/pause|resume` |
| Metrics | `GET /api/metrics/summary`（Prometheus 由 `/actuator/prometheus` 提供） |

**Mapping UI**（PRD §36）：

| 能力 | 实现 |
| --- | --- |
| 自动生成 | `POST /api/routes/{id}/draft/mapping/auto-generate`（读取目标表列 + 同名匹配） |
| 字段搜索 | 前端本地过滤 + 后端分页 |
| Preview | `POST /api/routes/{id}/draft/preview`（返回 Normalized JSON / Target Row / SQL，PRD §37） |
| 类型展示 | 读目标表元数据 |

## 13.2 权限

V1 建议两级：`ADMIN`（配置发布、激活、重放）与 `VIEWER`（只读）。接入现有 SSO / 网关鉴权，本系统只做角色映射。

---

# 14. 可观测性

## 14.1 指标

严格采用 PRD §39 的指标名：

| 指标名 | 类型 | 标签 |
| --- | --- | --- |
| `consumer_messages_total` | Counter | `route`, `topic`, `tag` |
| `consumer_ack_total` | Counter | `route` |
| `consumer_retry_total` | Counter | `route`, `error_code` |
| `consumer_error_total` | Counter | `route`, `stage` |
| `transform_success_total` | Counter | `route`, `version` |
| `transform_error_total` | Counter | `route`, `error_code` |
| `pg_insert_total` | Counter | `route`, `table` |
| `pg_update_total` | Counter | `route`, `table` |
| `pg_skip_old_version_total` | Counter | `route`, `table` |
| `pg_write_error_total` | Counter | `route`, `sqlstate` |
| `pg_batch_size` | DistributionSummary | `route` |
| `pg_batch_flush_total` | Counter | `route`, `trigger`（SIZE/TIME） |
| `pg_write_latency` | Timer | `route` |
| `raw_write_success_total` | Counter | — |
| `raw_write_error_total` | Counter | `reason` |

**补充建议指标**：`retry_backlog`（PENDING 数量）、`retry_oldest_age_seconds`、`dlq_total`、`consumer_state`（Gauge，0/1/2/3）、`raw_queue_depth`。

> **标签基数控制**：`route` 标签使用 `routeId` 或 `routeName`（数量有限），**禁止**使用 `messageId`、`topic+tag` 组合以外的高基数值。

## 14.2 日志

* 结构化 JSON 日志，关键字段：`traceId`、`routeId`、`version`、`messageId`、`batchId`、`stage`、`errorCode`。
* 单条消息全链路可追踪：`messageId` 贯穿 receive → transform → write → ack。
* 批处理日志：Flush 时记录 `batchId`、条数、折叠前后条数、耗时、逐类结果计数。
* 敏感数据（payload）默认不打印。

## 14.3 运行状态

`rt_consumer_state` 表 + `GET /api/runtime/routes` 展示（PRD §40）：

```json
{
  "routeId": 1, "routeName": "order-sync",
  "topic": "order-topic", "tag": "order.updated",
  "status": "PAUSED",
  "reason": "PostgreSQL connection refused",
  "activeVersion": 3, "bindVersion": 3,
  "lastErrorAt": "2026-09-25T10:12:33Z",
  "bufferSize": 0,
  "pendingRetry": 42
}
```

---

# 15. 关键时序

## 15.1 正常批处理

```text
MQS        Consumer      BatchMgr     Transform    PG Writer     MQS
 │            │             │            │            │           │
 │─receive───→│             │            │            │           │
 │            │─bind ver───→│            │            │           │
 │            │─raw async──→│(旁路)      │            │           │
 │            │─append─────→│            │            │           │
 │            │             │─size>=1000→│            │           │
 │            │             │─transform─→│            │           │
 │            │             │←──rows─────│            │           │
 │            │             │─fold──────→│            │           │
 │            │             │─MERGE──────────────────→│           │
 │            │             │←─INSERTED/UPDATED/SKIP──│           │
 │            │←─ack all────┴─────────────────────────┴──────────→│
```

## 15.2 数据错误 → 重试表 → DLQ

```text
Consumer     Transform      RetrySvc        PG(配置库)      MQS
   │            │              │                │            │
   │─transform─→│              │                │            │
   │←─error─────│              │                │            │
   │───────────enqueue────────→│                │            │
   │                           │─INSERT task───→│            │
   │                           │←──COMMIT───────│            │
   │←──────────────────────────┴───ack─────────────────────→│
   │                                                         │
   │        (退避到期后,RetryScheduler 接管)                  │
   │                           │─reprocess─────→ PG 目标库    │
   │                           │  attempt >= 10 → rt_error_record(DLQ)
```

## 15.3 PG 故障 → 暂停 → 恢复

```text
Consumer        PgHealthGate      MQS         Console
   │                 │             │            │
   │─MERGE fail─────→│             │            │
   │                 │ recordFailure            │
   │←─isOpen=false───│             │            │
   │─PAUSED (停止拉取)│             │            │
   │                 │─探活 SELECT 1 (周期)     │
   │                 │ 连续成功 2 次 → RECOVERING
   │←─isOpen=true────│             │            │
   │─RUNNING────────→│             │            │
   │─receive──────────────────────→│            │
```

## 15.4 ACK 失败

```text
PG Write SUCCESS → ack() 抛异常 → 消息未被确认
                → MQ 重新投递 → 重复处理
                → MERGE 命中 SKIPPED_OLD_VERSION 或 UPDATED
                → 最终状态不变(§17.2 论证)
```

---

# 16. 一致性与正确性论证

## 16.1 At-Least-Once 论证（含 D-01）

**命题**：任意环节崩溃，消息都不会被永久丢失。

需要证明每个「ACK 点」之前都存在一个持久化动作：

| 路径 | ACK 前的持久化动作 | 崩溃后果 |
| --- | --- | --- |
| PG 写入成功 | PG 事务提交 | 无（已落库） |
| 数据错误 → 重试表 | `INSERT rt_retry_task` + `COMMIT` | COMMIT 前崩溃 → 未 ACK → MQ 重投 |
| 降级隔离的坏行 | 同上 | 同上 |
| PG 级故障 | **不 ACK** | MQ 重投 |

**关键不变量**：

```text
∀ message: ack(message) ⟹ (PG 已提交) ∨ (∃ rt_retry_task 持久行)
```

该不变量由 §9.5「重试入队必须早于 ACK」保证。因此 D-01 的偏差**不破坏 At-Least-Once**。

**反向风险（至少一次 → 可能重复）**：允许重复，由 §16.2 消化。

## 16.2 幂等性论证

**命题**：同一消息处理任意多次，`update_time` 单调守卫保证最终状态不变。

写入语句对同一 Key 的执行语义：

```text
incoming.update_time >  existing.update_time  →  UPDATE (状态推进)
incoming.update_time <= existing.update_time  →  无操作(SKIPPED)
```

* 第一次执行：可能 INSERT 或 UPDATE。
* 第二次执行同一条消息：`incoming.update_time == existing.update_time` → 落入 `<=` 分支 → **SKIPPED**。

因此重复投递不改变状态。**注意守卫必须是严格大于 `>`**（PRD §7），若误写为 `>=`，重复消息会反复 UPDATE，虽不改变数据值但会放大 WAL 与触发器副作用。

**折叠场景的幂等**：折叠只影响批次内计算，不影响最终写入值（折叠结果 = 该 Key 的 `update_time` 最大者）。重复批次产生相同折叠结果。

## 16.3 乱序收敛论证

**命题**（PRD §7 示例）：A(10:00) → B(10:02) → C(10:01)，最终为 10:02。

```text
初始: 无记录
A(10:00) → NOT MATCHED        → INSERT, t=10:00
B(10:02) → 10:02 > 10:00      → UPDATE, t=10:02
C(10:01) → 10:01 <= 10:02     → SKIPPED, t=10:02
最终: t=10:02  ✅
```

**归纳论证**：设目标行当前值为 `T_cur`，其更新历史为已处理的 `update_time` 集合 `S`。不变量：

```text
T_cur = max(S)
```

每次处理 `t`：若 `t > T_cur` 则 `T_cur := t`，新 `max(S ∪ {t}) = t = T_cur` ✅
若 `t <= T_cur` 则不变，`max(S ∪ {t}) = max(S) = T_cur` ✅

不变量对任意到达顺序成立，故最终收敛到 `max(全部 update_time)`。**与消费顺序无关**。

## 16.4 批次内同 Key 折叠的正确性

**命题**：折叠后的写入结果与「逐条按 MQ 顺序执行」等价。

逐条执行时，同一 Key 的最终值 = `max(组内 update_time)`；`update_time` 相同时取最后出现的（PRD §8）。
折叠算法（§7.3）恰好计算该值：遍历中 `cmp > 0` 或 `cmp == 0` 都替换 winner，`cmp < 0` 保留。
* `cmp == 0` 时替换 → 等价于「最后出现的胜出」✅
* `cmp > 0` 时替换 → 等价于取最大 ✅
* `cmp < 0` 保留 → 更旧的不影响结果 ✅

由于折叠与逐条执行对同一 Key 产生相同最终值，且 PG 守卫保证跨批次也收敛，故等价成立。✅

**前提**：折叠必须**保持输入顺序**（`LinkedHashMap` / 数组顺序），否则 `cmp == 0` 的 tie-break 会失真。

---

# 17. 失败模式与应对

| 失败 | 检测 | 应对 | 数据风险 |
| --- | --- | --- | --- |
| MQS 拉取异常 | 异常 | 退避 1s 重试拉取 | 无 |
| 消息体非 JSON | 解析异常 | 重试表 / DLQ（`DATA`） | 无 |
| 必填字段缺失 | 校验失败 | 重试表 / DLQ | 无 |
| 类型转换失败 | 转换异常 | 重试表 / DLQ | 无 |
| JSLT 运行异常 | 异常 | 重试表 | 无 |
| 批次内个别坏行 | MERGE 语句失败 | 二分隔离 → 好行 ACK，坏行进重试表 | 无 |
| PG 连接失败 | SQLState 08xxx | PAUSED，整批不 ACK | 无（MQ 重投） |
| PG 约束冲突 | SQLState 23xxx | 降级隔离 | 无 |
| PG 死锁 | SQLState 40P01 | 整批重试一次 | 无 |
| 同批同 Key 重复 | 折叠保证 | 不会到达 SQL | 无 |
| ACK 失败 | 异常 | 允许重投（幂等） | 无 |
| 重试表写入失败 | 异常 | 不 ACK + PAUSED | 无 |
| 进程崩溃（已 ACK） | 重启 | 重试表接管（已持久化） | 无 |
| 进程崩溃（未 ACK） | 重启 | MQ 重投 | 无 |
| Raw 落盘失败/队满 | 计数 | 丢弃 + 告警（非一致性环节） | Raw 缺失 |
| 分区缺失 | 插入失败 | DEFAULT 分区兜底 + 告警 | 无 |
| 配置发布错误 | 发布校验 | 拦截，不允许 PUBLISHED | 无 |
| 目标表结构变更 | 写入失败 | 降级隔离 + Console 告警 | 无 |

---

# 18. 测试策略

## 18.1 核心语义测试（必须用真实 PG）

使用 Testcontainers `postgresql:17`，**不可用 H2 替代**（`MERGE ... RETURNING` 与分区语法是 PG 专有）。

| 用例 | 断言 |
| --- | --- |
| 乱序收敛 | §16.3 场景，最终 `update_time = 10:02` |
| 幂等重复 | 同一消息写 3 次，结果与写 1 次一致；第 2、3 次全部 SKIPPED |
| 同批同 Key 折叠 | A(10:00)/B(10:02)/C(10:01) 一批 → 1 次写入，action=INSERT，最终 10:02 |
| tie-break | 同 Key 同 `update_time` 两条 → 以最后出现的为准 |
| 结果判定 | INSERT / UPDATE / SKIP 三种 action 正确区分 |
| 折叠 ACK 完整性 | 3 条同 Key 消息 → **3 条全部 ACK** |
| 批次降级隔离 | 10 条含 1 条 numeric 溢出 → 9 条成功写入且 ACK，1 条进重试表 |
| 必填字段发布校验 | NOT NULL 无来源 → 发布失败 |
| PG 故障暂停 | 关闭容器 → 状态 PAUSED，无 ACK |
| PG 恢复 | 重启容器 → RECOVERING → RUNNING |
| 分区 TTL | 建 8 天分区 + retention 7 → 最旧分区被 DETACH + DROP |
| Dry Run 回滚 | 执行后目标表行数不变 |

## 18.2 集成测试

* MQS 使用内存 Mock 实现（`mqs.vendor=mock`），可编程注入延迟、重复、乱序。
* 端到端：Mock MQS → 全链路 → 断言 PG 最终状态 + ACK 集合。

## 18.3 并发与边界

| 场景 | 关注点 |
| --- | --- |
| batchSize 边界 | 999 / 1000 / 1001 条 |
| 空批次 | 不产生空 SQL |
| 超大 payload | 超过 `max_allowed_packet` 等价限制时的行为 |
| 参数上限 | 列数 × 行数接近 65535 |
| 复合 Upsert Key | 多列 ON 条件 |
| `update_time` 为 null | 明确拒绝或按最小处理（需产品确认，见附录 C） |
| 多数据源 | 两个 Target 指向不同 PG 实例 |

## 18.4 性能基线

| 指标 | 目标 |
| --- | --- |
| 单批 1000 条端到端 P99 | < 500ms（不含 MQ 拉取） |
| 折叠 1000 条 | < 5ms |
| 吞吐 | ≥ 5000 msg/s（单 Route，需实测校准） |
| Raw 异步落盘 | 不进入主链路关键路径 |

---

# 19. 演进路线

## V1（本文档范围）

单实例、单 Route 单 Consumer、无水平扩展。

## V2 预留点

| 能力 | 预留设计 |
| --- | --- |
| Consumer 水平扩展 | `rt_retry_task` 已用 `FOR UPDATE SKIP LOCKED` + 租约；消费侧需引入分区分配 |
| 多实例配置同步 | `ConfigRegistry` 已抽象接口，替换为 `LISTEN/NOTIFY` 实现 |
| 多 MQ 厂商 | `MqsConsumerFactory` 按 `vendor` 扩展 |
| Raw 不丢 | 队满策略由「丢弃」改为「本地落盘 / 背压」 |
| 一条消息多表 | 需重新设计折叠与 ACK 映射（当前模型假设 1:1） |
| 表达式引擎替换 | `ExpressionEvaluator` 接口已隔离 |

---

# 附录 A：完整 DDL

```sql
-- ============================================================
-- 配置域
-- ============================================================

CREATE TABLE cfg_datasource (
    id            BIGSERIAL     PRIMARY KEY,
    name          VARCHAR(64)   NOT NULL UNIQUE,
    jdbc_url      VARCHAR(1024) NOT NULL,
    username      VARCHAR(128)  NOT NULL,
    password_enc  TEXT          NOT NULL,
    pool_config   JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE cfg_target (
    id                BIGSERIAL    PRIMARY KEY,
    name              VARCHAR(128) NOT NULL UNIQUE,
    datasource_id     BIGINT       NOT NULL REFERENCES cfg_datasource(id),
    schema_name       VARCHAR(64)  NOT NULL DEFAULT 'public',
    table_name        VARCHAR(64)  NOT NULL,
    upsert_keys       JSONB        NOT NULL,
    update_time_field VARCHAR(64)  NOT NULL DEFAULT 'update_time',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (datasource_id, schema_name, table_name)
);

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

CREATE TABLE cfg_version (
    id           BIGSERIAL   PRIMARY KEY,
    route_id     BIGINT      NOT NULL REFERENCES cfg_route(id) ON DELETE CASCADE,
    version      INTEGER     NOT NULL,
    status       VARCHAR(16) NOT NULL,
    content      JSONB       NOT NULL,
    change_note  TEXT,
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    UNIQUE (route_id, version)
);
CREATE INDEX idx_cfg_version_status ON cfg_version (route_id, status);

-- ============================================================
-- 运行域
-- ============================================================

CREATE TABLE rt_consumer_state (
    route_id        BIGINT      PRIMARY KEY REFERENCES cfg_route(id),
    status          VARCHAR(16) NOT NULL DEFAULT 'PAUSED',
    reason          TEXT,
    bind_version    INTEGER,
    last_error_at   TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

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
CREATE INDEX idx_retry_due ON rt_retry_task (next_retry_at) WHERE status = 'PENDING';
CREATE UNIQUE INDEX uk_retry_active ON rt_retry_task (route_id, message_id)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX idx_retry_lease ON rt_retry_task (lease_until) WHERE status = 'RUNNING';

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
CREATE INDEX idx_error_route_time ON rt_error_record (route_id, error_time DESC);
CREATE INDEX idx_error_message    ON rt_error_record (message_id);
CREATE INDEX idx_error_final      ON rt_error_record (route_id) WHERE is_final;

-- ============================================================
-- 留存域
-- ============================================================

CREATE TABLE raw_message (
    id              BIGSERIAL    NOT NULL,
    message_id      VARCHAR(128) NOT NULL,
    route_id        BIGINT       NOT NULL,
    topic           VARCHAR(256) NOT NULL,
    tag             VARCHAR(256),
    receive_time    TIMESTAMPTZ  NOT NULL,
    config_version  INTEGER,
    payload         JSONB,
    payload_raw     TEXT,
    PRIMARY KEY (id, receive_time)
) PARTITION BY RANGE (receive_time);

CREATE INDEX idx_raw_message_id ON raw_message (message_id);
CREATE INDEX idx_raw_route_time ON raw_message (route_id, receive_time DESC);

CREATE TABLE raw_message_default PARTITION OF raw_message DEFAULT;
```

---

# 附录 B：配置示例

```yaml
# cfg_route + cfg_target + cfg_version.content 的组合视图
route:
  name: order-sync
  topic: order-topic
  tag: order.updated

target:
  datasource: order-pg
  schema: public
  table: orders
  upsertKeys: [id]
  updateTimeField: update_time

batch:
  size: 1000
  flushInterval: 1s
  pullSize: 500
  invisibleDuration: 30s

retry:
  maxAttempt: 10
  initialDelay: 1s
  multiplier: 2
  maxDelay: 60s

content:
  mappingStrategy: EXACT        # 或 CAMEL_TO_SNAKE
  jslt: null
  mappings:
    - target: id
      source: $.id
      transform: { type: long }
      required: true
    - target: name
      source: $.name
      transform: { type: string }
    - target: amount
      source: $.amount
      transform: { type: decimal }
      required: true
      defaultValue: "0"
    - target: status
      source: $.status
      transform:
        type: enum
        mapping: { CREATED: 1, PAID: 2, CANCELLED: 3 }
      defaultValue: UNKNOWN
    - target: update_time
      source: $.updatedAt
      transform: { type: timestamp, pattern: "yyyy-MM-dd'T'HH:mm:ssXXX", zone: Asia/Shanghai }
      required: true
    - target: source
      constant: ORDER_SYSTEM
```

---

# 附录 C：待确认事项

| 编号 | 事项 | 影响 | 建议 |
| --- | --- | --- | --- |
| C-01 | `DATA` 类错误（JSON 解析失败、必填缺失）是否也要重试 10 次？ | 重试表容量与无效算力 | 建议直接 DLQ，可配置 |
| C-02 | 表达式引擎是否接受 LGPL（Aviator）？ | 法务 | 不接受则走受限自研 DSL |
| C-03 | `update_time` 为空时如何处理？ | 写入语义 | 建议拒绝（`MISSING_REQUIRED_FIELD`） |
| C-04 | 生产 PG 版本是否 ≥ 17？ | ADR-04 可行性 | 若为 16，需改用 `ON CONFLICT` + `xmax` 降级方案 |
| C-05 | Raw Message 队满允许丢弃还是必须不丢？ | Raw Writer 实现复杂度 | 建议 V1 允许丢弃 + 告警 |
| C-06 | 目标表 Upsert Key 是否保证有唯一索引？ | `MERGE` 正确性与性能 | 建议发布校验强制要求 |
| C-07 | 平台 MQS 的 `messageId` 在多次投递间是否稳定？ | 重试表去重有效性 | 必须确认；不稳定则退化为允许重复重试 |
| C-08 | Console 鉴权是否接入统一 SSO？ | 权限实现 | V1 可先做角色映射 |

---

# 附录 D：参考

* PRD：`prd.md`
* PostgreSQL 18 `MERGE`：https://www.postgresql.org/docs/18/sql-merge.html
* PostgreSQL 17 `MERGE ... RETURNING`：https://www.postgresql.org/docs/17/sql-merge.html
* PostgreSQL `INSERT ... ON CONFLICT`：https://www.postgresql.org/docs/18/sql-insert.html
* Spring Boot 支持周期：https://endoflife.date/spring-boot
* Jayway JsonPath（3.0.0 基线说明）：https://github.com/jayway/JsonPath
* JSLT：https://github.com/schibsted/jslt

---

# 附录 E：阶段一实测结论（JDK 21 / Spring Boot 4.1.1 / PostgreSQL 18.1）

以下四条均为**实际编码—启动—测试中撞到并已修复**的问题，不是推演。除 E.4 外，
它们都不会在编译期报错，而是以「静默不生效」或「运行时才炸」的形式出现，故记录在案。

## E.1 Spring Boot 4 的自动配置已模块化：Flyway 必须用 starter

**现象**：引入 `org.flywaydb:flyway-core` + `flyway-database-postgresql` 后，
应用**正常启动**，日志中**没有任何 Flyway 输出**，迁移 SQL 一条也没执行。
直到 MyBatis 第一次查询才以 `relation "cfg_route" does not exist` 暴露。

**根因**：Spring Boot 4 把原先集中在 `spring-boot-autoconfigure` 里的自动配置
拆成了独立模块（`spring-boot-flyway`、`spring-boot-jdbc` 等）。
`flyway-core` 只是 Flyway 自身的库，**不再携带 Spring Boot 自动配置**。

**结论**：必须使用 `org.springframework.boot:spring-boot-starter-flyway`。
同一规则适用于 Boot 4 下其他「只引第三方库不引 starter」的用法，
排查此类问题时先确认对应 `spring-boot-*` 模块是否存在。

## E.2 Jackson 2 与 Jackson 3 的边界

**现象**：REST 接口返回的 `content` 字段不是 JSON 对象，而是一堆
`array` / `bigDecimal` / `nodeType` / `containerNode` 之类的 bean 属性。

**根因**：Spring Boot 4 的 Web 层默认使用 **Jackson 3**（`tools.jackson`），
而 JSLT、jayway json-path 以及本项目的 JSONB 类型处理器基于 **Jackson 2**
（`com.fasterxml.jackson`）。Jackson 3 不认识 Jackson 2 的 `JsonNode`，
退化为 bean 内省。

**结论**（已固化为编码规则）：
- Jackson 2 类型**只允许**出现在持久化层与转换引擎内部；
- 跨越 Web 边界前必须经 `com.mqspg.common.persistence.JsonNodes#toPlain`
  转为纯 Java 结构（Map / List / 标量）；
- REST DTO 中不得出现 `com.fasterxml.jackson.databind.JsonNode`。

## E.3 pgjdbc 的 `currentSchema` 决定 `search_path`

**现象**：Flyway 配置了 `default-schema: mqs_pg` 且迁移成功，
但 MyBatis 生成的无限定 SQL（`SELECT ... FROM cfg_route`）仍报表不存在。

**根因**：Flyway 的 `default-schema` 只作用于 Flyway 自身；
应用连接的 `search_path` 仍是默认的 `public`。

**结论**：JDBC URL 必须显式声明 `?currentSchema=mqs_pg,public`。
`currentSchema` 由 pgjdbc 转换为该连接的 `search_path`。
保留 `public` 是为了让扩展函数等对象仍可解析。

## E.4 MERGE 源 `VALUES` 中的参数必须显式 cast

`USING (VALUES (?, ?))` 中的裸参数，PostgreSQL 无法推断类型，
会报 `could not determine data type of parameter $1`。
必须写成 `?::<pg类型>`。类型字面量直接取自
`format_type(atttypid, atttypmod)`（如 `bigint`、`numeric(18,2)`、
`timestamp with time zone`），可直接用于 cast，无需自行映射。
见 `MergeSqlBuilder` 与 `TargetMetadataReader`。

## E.5 阶段一验证结果

| 验证项 | 结果 |
| --- | --- |
| Flyway 迁移（V1 配置域 DDL + V900 demo 种子） | 通过，`mqs_pg` 至 v900 |
| 配置注册表装配（`cfg_version.content` → `RouteConfig`） | 通过，路由 1 装配出 6 条 mapping |
| REST API（`/api/routes`、`/api/routes/{id}`、`/actuator/health`） | 通过，UTF-8 正确 |
| 折叠算法（同 Key 保序、尾零规范化、来源全收集） | 7 个单测通过 |
| MERGE 单调守卫（INSERTED → 同刻 SKIP → 更旧 SKIP → UPDATED） | 4 个集成测试通过 |
| 二分隔离（数据级错误定位到行，不阻塞同批次好行） | 通过，实测 SQLSTATE 22P02 |

**尚未实现**（后续阶段）：MQS 平台实现与 mock 消费者、Transform Engine（JSONPath/JSLT/类型转换）、
Batch Manager、重试表调度器与 DLQ、Raw Message 分区留存、Console 前端。

## E.6 待办：与正文的偏差

- §3.5 提到的 Testcontainers 版本需按 Boot 4.1.1 的实际管理版本校正
  （Boot 4.1.1 管理 2.0.3，正文写作时的 1.21.4 已过时）。
- §4.2 需补记：V1 采用**单 Maven 模块 + 包边界**，而非 9 个 Maven 模块；
  包结构严格保持 `common / config / mqs / transform / writer / retry / raw / console`，
  拆分留待 V2。当前 `PgWriterTest` 直接依赖本地 docker PG，
  后续应迁移到 Testcontainers。

## E.7 阶段二/三实现补记（Transform Engine、消费循环、Batch Manager）

### E.7.1 ACK 决策不是布尔值

实现阶段最重要的一个修正：**「是否 ACK」在实现里是四种去向，而不是 true/false**。
正文 §7.4 只写了「成功 ACK、失败不 ACK」，落到代码里必须区分：

| 去向 | 含义 | 何时产生 |
| --- | --- | --- |
| `ACK` | 正常写入成功 | `INSERTED` / `UPDATED` / `SKIPPED_OLD_VERSION` |
| `ACK_DEFERRED` | 已落重试表，可以 ACK | 行级数据错误、可重试的转换错误 |
| `ACK_DLQ` | 已写终态错误流水，可以 ACK | 不可重试的 DATA 类错误 |
| `NO_ACK` | **不得 ACK**，等 MQ 重投 | PG 级故障；或重试表/错误流水**落库失败** |

最后一行是最容易写错的地方：落库失败时如果仍然 ACK，这条消息就**永久消失**了。
因此 `BatchManager` 里每一处 `ACK_DEFERRED` / `ACK_DLQ` 都被包在
`try { 落库 } catch { 降级为 NO_ACK }` 中。宁可重复投递，也不能丢。

另一条设计约束：`BatchManager` **只产出结论，不执行 ACK**，ACK 由
`RouteConsumer#applyDispositions` 单点执行。这样「该不该 ACK」可以纯逻辑测试，
而「ACK 有没有真的发出去」只有一处需要审计。

### E.7.2 错误严重级别的修正

正文 §8.5 把取值/转换类错误定为 `TRANSIENT`，实现时改为 `DATA`：

- `PATH_ERROR`、`TYPE_CONVERSION_ERROR`、`EXPRESSION_ERROR` → `DATA`

理由：这些错误完全由**消息内容**决定，重试一百次结果相同，除了堆积重试队列没有别的作用。
`ENUM_MAPPING_ERROR`、`DATETIME_FORMAT_ERROR`、`MISSING_REQUIRED_FIELD`、`JSON_PARSE_ERROR`
原本就是 `DATA`，现在同一类错误有了统一的级别。

保留的逃生口：`mqs-pg.retry.retry-data-errors`（对应附录 C-01）。
若上游是最终一致的（先发事件、后补字段），打开它即可让 DATA 类错误也进重试队列。

### E.7.3 表达式引擎：自研替代 Aviator

附录 C-02 提出的许可证问题在实现阶段做了决断：**不用 Aviator**，
改为自研受限表达式求值器 `SimpleExpressionEvaluator`（递归下降，约 500 行）。

支撑这个决定的事实是：映射表达式实际只需要
四则运算、比较、逻辑、三元与少量字符串函数，且表达式**只来自配置库**。
自研实现完全没有注入面（无属性赋值、无索引写入、无方法调用），
也就不需要为「沙箱第三方脚本引擎」再引入一层安全评审。

支持的语法：`+ - * / %`、`== != < <= > >=`、`&& || !`、`? :`、
`upper lower trim length concat coalesce abs round floor ceil int long decimal string now`。
`+` 在两侧均可数值化时做加法，否则做字符串拼接（与 JS 一致）。

### E.7.4 类型转换的两个实测坑

1. **`java.sql.Date` 是 `java.util.Date` 的子类，但它的 `toInstant()` 会抛
   `UnsupportedOperationException`**。因此时间转换必须先判 `java.sql.Timestamp`
   与 `java.sql.Date`，再判 `java.util.Date`，否则从 PG 读回的时间值一进转换器就炸。

2. **数值一律经 `BigDecimal` 再 `longValueExact()`**。
   直接 `Long.parseLong` 或 `((Number) v).longValue()` 会把 `1.9` 静默截断成 `1`，
   这类错误落库后极难发现。宁可报 `TYPE_CONVERSION_ERROR`。

### E.7.5 行级失败触发条件实测

`PgWriter` 的二分隔离在端到端测试里用真实约束验证过：

| 制造手法 | 实测 SQLSTATE | 分类 | 结果 |
| --- | --- | --- | --- |
| `status` 传入非整数文本 | `22P02` | 数据级 | 隔离到行，进重试表 |
| 20 位整数写入 `NUMERIC(18,2)` | `22003` | 数据级 | 隔离到行，进重试表 |

`NUMERIC(18,2)` 的溢出用例（`99999999999999999999`）尤其有价值：
它证明**转换阶段通过、写入阶段才失败**的路径也能被正确隔离 ——
这正是「好行照写、坏行落重试表后 ACK」这条不变量的真实检验。

### E.7.6 阶段二/三验证结果

| 验证项 | 结果 |
| --- | --- |
| 同批次同 Key 乱序折叠（4 条消息，最旧者最后到） | 通过，落库为 `update_time` 最大者 |
| 旧版本后到不覆盖新数据 | 通过，被判定 `SKIPPED_OLD_VERSION` 且仍被 ACK |
| `update_time` 平局由消费顺序决定（PRD §8） | 通过，后到者胜 |
| 重复投递幂等（ACK 丢失场景） | 通过，第二次写入不影响行值 |
| 必填字段缺失 → DLQ + ACK，且不阻塞同批好行 | 通过，`MISSING_REQUIRED_FIELD` / `is_final=true` |
| 非法 JSON → DLQ + ACK | 通过，`JSON_PARSE_ERROR` |
| 枚举值未知 → DLQ + ACK | 通过，`ENUM_MAPPING_ERROR` |
| 行级 PG 数据错误 → 重试表 + ACK（ACK 不变量） | 通过，`rt_retry_task.status='PENDING'` 已落库 |
| 真实消费循环（自动拉起、条数触发、时间触发） | 通过，3 个集成测试 |
| 端到端 HTTP 试跑（mock 投递 → 落库） | 通过，乱序 3 条折叠为 12:00 那条 |

`mvn test`：**23 个用例全部通过**。

### E.7.7 测试隔离要求（重要）

`mqs-pg.consumer.auto-start` 默认为 `true`，应用启动后会自动为所有 ACTIVE 路由
拉起消费线程。这在集成测试里是**有害**的：后台线程会与测试争抢同一批消息，
断言变得不确定。

因此 `PgWriterTest`、`PipelineIntegrationTest`、`RouteConsumerIntegrationTest`
都显式设置 `mqs-pg.consumer.auto-start=false`。
`RouteConsumerIntegrationTest` 则自行构造 `RouteConsumer` 并控制其生命周期，
以便在确定的时间点断言。

### E.7.8 PowerShell 下的 Maven 参数

`mvn spring-boot:run -Dspring-boot.run.profiles=dev` 在 PowerShell 中会被拆成
`-Dspring-boot` 与 `.run.profiles=dev`，Maven 报
`Unknown lifecycle phase ".run.profiles=dev"`。改用环境变量：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'dev'; mvn spring-boot:run
```

---

## E.8 阶段六/七/八实现补记（重试调度、原始留存、前端）

### E.8.1 MyBatis-Plus `updateById` 会忽略 null —— 租约清不掉

重试任务的状态迁移最初写成 `task.setLeaseUntil(null); mapper.updateById(task);`。
测试立刻发现 `lease_until` **仍然有值**。

原因：MyBatis-Plus 的 `updateById` 默认策略是 `FieldStrategy.NOT_NULL`，
**null 字段不进入 SET 子句**。而「释放租约」的语义恰恰就是写入 NULL。

这不只是显示问题：`reschedule` 把任务置回 `PENDING` 却留着旧租约，
语义上是一行「没有在跑、却占着租约」的任务，排查时极易误导。

**规则**：凡是需要**显式写入 NULL** 的状态迁移，一律用 `UpdateWrapper`：

```java
retryTaskMapper.update(null, new LambdaUpdateWrapper<RtRetryTask>()
        .eq(RtRetryTask::getId, task.getId())
        .set(RtRetryTask::getStatus, "PENDING")
        .set(RtRetryTask::getLeaseUntil, null)   // 显式写 NULL
        .set(RtRetryTask::getUpdatedAt, now));
```

设计上顺带确立了：`RetryService` 的每个状态迁移都通过一个私有 `transition(id)`
构造 wrapper，集中在一处，便于审计「哪些字段会被写」。

### E.8.2 DEFAULT 分区会让分区创建**永久**卡死

这是阶段五最值得记录的一个坑，因为它同时是「首次上线必定踩中」的场景。

现象：`raw_message_p20260927`（当天）建不出来，而未来 3 天的分区都建好了。

链路是：

1. 应用刚启动、维护任务还没跑，此时**没有任何日分区**；
2. 消息到达 → 插入 `raw_message` → PostgreSQL 把它放进 `DEFAULT` 分区兜底（不报错）；
3. 维护任务开始建当天分区 → 报
   `updated partition constraint for default partition would be violated`
   —— 因为 DEFAULT 里已经有落在该区间的行；
4. 若此时「告警并跳过」，那么**当天分区永远建不出来**：
   只要 DEFAULT 里那几行不清掉，每次重试都会以同样理由失败。

真正麻烦的是第 4 步的自我强化：分区建不出来 → 新数据继续落 DEFAULT →
DEFAULT 更不可能清空。首次上线、维护任务未跑、或某天在 00:30 之前就有流量，
都会进入这个状态。

**解法**（PostgreSQL 官方推荐的搬移流程），整个过程放在一个事务里：

```sql
ALTER TABLE raw_message DETACH PARTITION raw_message_default;   -- 1. 解除约束
CREATE TABLE raw_message_p<d> PARTITION OF raw_message FOR VALUES FROM (d) TO (d+1);  -- 2. 建分区
INSERT INTO raw_message_p<d> SELECT ... FROM raw_message_default WHERE receive_time >= d AND < d+1;  -- 3. 搬数据
DELETE FROM raw_message_default WHERE receive_time >= d AND < d+1;  -- 4. 清原位
ALTER TABLE raw_message ATTACH PARTITION raw_message_default DEFAULT;  -- 5. 挂回兜底
```

代价是 DETACH 会对父表加 `ACCESS EXCLUSIVE` 锁，期间写入短暂阻塞。
相对于「永久卡死」，这个代价在每日 00:30 的维护窗口里完全可以接受。
关键是把整段放进事务：中途失败会整体回滚，DEFAULT 不会停留在「已分离」的危险状态。

回归测试 `repairsDefaultPartitionInsteadOfGettingStuck` 除了断言分区被建出来，
还断言**搬移不丢行**（DEFAULT 归零且新分区里行数为 1）——
只验证「分区存在」而不验证数据迁移完整，会漏掉搬移过程中 `WHERE` 写错的整类 bug。

### E.8.3 分区过期判定不要依赖 `pg_get_expr` 的文本渲染

`pg_get_expr(relpartbound, oid)` 返回的边界文本是**按服务端时区渲染**的，
例如 `TO ('2026-09-28 16:00:00+00')`；而「保留线」是用 `now()::date` 在
**会话时区**里算出来的。两者时区不同，靠正则从文本里抠日期再比较，
在时区差距大时（如会话 +14 / 服务端 -10）会早删一天。

改为**从分区名解析**：名即 `raw_message_p<yyyyMMdd>`，是建分区时按名义日期写死的，
与时区渲染无关。

```java
private static final Pattern PARTITION_NAME = Pattern.compile("^raw_message_p(\\d{8})$");
```

命名不符合约定的分区一律跳过（可能是人工建的），宁可少删也不误删。
过期条件也取得保守：只有分区**整个区间**都早于保留线才回收。

### E.8.4 分区边界与会话时区绑定

「今天」取自 `SELECT to_char(now()::date, ...)`，边界字面量按同一会话时区解释，
二者始终自洽——这一点在实测中被 `pg_get_expr` 印证：
JDBC 会话（JVM 默认 +08）建出的 `raw_message_p20260928` 边界是
`2026-09-27 16:00:00+00` ~ `2026-09-28 16:00:00+00`，即本地 09-28 全天。

但 pgjdbc 的会话时区来自 JVM 默认时区，因此**部署之间若改动 JVM 时区**，
新旧分区的绝对边界会错位，CREATE 会报区间重叠。
代码在这种情况下记录明确诊断（提示核对时区与边界）而不是抛栈，
让维护任务能跳过该日期继续处理其它日期。

排查时注意：`psql` 直连的会话时区（本项目容器为 `Etc/UTC`）与应用不同，
手工执行同样的 `CREATE ... FOR VALUES FROM ('2026-09-27')` 会得到
`would overlap partition` 的**假报错**——那是 psql 会话把字面量解释成了另一个瞬间。

### E.8.5 留存是旁路：队列满时必须丢弃

`RawStore` 的写入队列是**有界且非阻塞**（`ArrayBlockingQueue.offer`）。
队列满时直接丢弃并计数告警，绝不阻塞消费线程。

这是刻意的设计取舍：留存的用途是排障参考，而「消息不丢」由 ACK 不变量保证
（`ack(m) ⟹ PG 已提交 ∨ 重试任务已持久化`），**不依赖留存**。
反过来，如果这里改成阻塞式入队，一次 PG 写入变慢就会通过队列把消费拖停，
把「旁路」变成「单点」——那才是真正的可用性事故。

同理，`BatchManager` 在批次入口调用 `rawStore.offerAll(...)` 后不等待、不检查结果。
留存失败只体现在 `droppedCount` 与日志上。

### E.8.6 旁路写入会污染无关测试

因为 `BatchManager` 现在会写原始留存，所有驱动 `BatchManager` 的测试
（`PipelineIntegrationTest`、`RouteConsumerIntegrationTest`）都开始往
`raw_message` 写数据。而它们并没有建分区，于是数据落进 `DEFAULT`，
进而触发 E.8.2 的建分区死局——表现是**另一个测试类**（`RawRetentionTest`）失败。

这类「测试 A 的副作用让测试 B 失败」的耦合很难从失败信息上看出来。
处理方式是在不关心留存的测试里显式关闭：

```java
@TestPropertySource(properties = {
        "mqs-pg.consumer.auto-start=false",
        "mqs-pg.raw.enabled=false"     // 旁路，与断言语义无关
})
```

另有一个异步测试卫生问题：`RawStore` 的写入在后台线程，只等
「队列排空」仍可能在 `@AfterEach` 删除数据**之后**才 flush 完成，导致脏数据残留到下一轮。
正确做法是等写入计数稳定：

```java
do { last = rawStore.writtenCount(); sleep(100); } while (rawStore.writtenCount() != last);
```

### E.8.7 DLQ 终态与人工重放的语义

次数耗尽时 `markExhausted` 做两件事：把任务置为 `DLQ`（清租约），
并写一条 `rt_error_record(is_final=true)`。前者让调度器不再领取，
后者是 V1 的「DLQ」本体——数据库即队列，控制台可查、可人工重放。

人工重放（`makeDueNow`）对终态任务的处理是**追加一轮额度**：
`max_attempt = attempt + 配置的 maxAttempt`，同时把状态置回 `PENDING`。
不重置 `attempt` 是为了保留审计线索（这条消息一共试了多少次）。

重放成功的判定包含一种特殊情况：若重放的消息比库里已有的数据更旧，
单调守卫会让它 `SKIPPED_OLD_VERSION`。这**算成功**——
「有更新的数据胜出」正是正确终态，把它当失败会让任务在重试队列里空转。

### E.8.8 阶段六/七/八验证结果

| 验证项 | 结果 |
| --- | --- |
| 重放成功 → `SUCCEEDED`，且租约被清空 | 通过（E.8.1 的回归点） |
| 持续数据错误 → `reschedule` 且递增 `attempt`、释放租约 | 通过 |
| 次数耗尽 → `DLQ` + `rt_error_record(is_final=true)` | 通过 |
| 缺配置 → `releaseForRetry`，**不消耗**次数 | 通过 |
| 重放早于库中数据 → 记为成功而非失败 | 通过 |
| 租约过期回收 → 任务可被重新领取 | 通过 |
| `claimDue` 只领取到期且 `PENDING` 的任务 | 通过 |
| 人工重放 DLQ 任务 → 追加额度并回到 `PENDING` | 通过 |
| 真实调度器端到端（溢出消息） | 通过：`attempt` 依 1→2→4→8 指数退避增长，最终 10/10 转 `DLQ`，并写出 `retryCount=10` 的终态错误流水 |
| 分区提前创建幂等 | 通过 |
| 过期分区 `DETACH + DROP`，保留期内不误删 | 通过 |
| DEFAULT 数据搬移不丢行 | 通过 |
| 合法 JSON → `payload(JSONB)`；非法 JSON → `payload_raw` | 通过 |
| 端到端留存落进正确日分区 | 通过：`tableoid::regclass = raw_message_p20260927`（非 DEFAULT） |
| 前端构建（`pnpm build`，1691 模块） | 通过 |
| 前后端联调（Vite 代理 → 后端 6 个接口） | 通过 |

`mvn test`：**37 个用例全部通过**（管线集成 9 + 消费循环 3 + 原始留存 6 +
重试调度 8 + 折叠 7 + PG Writer 4）。

### E.8.9 前端：pnpm 12 会拦截 postinstall

`pnpm install` 在 pnpm 12 下默认**不执行**依赖的构建脚本，
`esbuild` 拿不到平台二进制，Vite 随之无法启动。报错是
`ERR_PNPM_IGNORED_BUILDS`，提示 `pnpm approve-builds`（交互式）。

非交互的做法是写进 `pnpm-workspace.yaml`（pnpm 10+ 已把该设置从
`package.json` 的 `pnpm` 字段移出，写在 `package.json` 里会被忽略并告警）：

```yaml
allowBuilds:
  esbuild: true
  vue-demi: true
```

### E.8.10 前端与 vue-admin-template 的对应关系

原项目是 Vue 2 + Element UI + Vuex，本项目按既定选型用 Vue 3 + Element Plus，
因此只沿用其**组织方式**而非代码：

| vue-admin-template | 本项目 |
| --- | --- |
| `src/utils/request.js` 拦截器 | `src/api/request.js`，按 `{success,code,message,data}` 信封解包后直接 resolve `data` |
| 路由 `meta.title/icon` 驱动侧边栏 | 同，`Sidebar.vue` 从路由表推导菜单结构 |
| `permission.js` + `store/modules/user` | `router/index.js` 前置守卫 + Pinia `store/user.js` |
| `layout/` 三件套 | `layout/index.vue` + `Sidebar/Navbar/AppMain` |

控制台未接入鉴权（PRD 未要求），登录页任意非空凭据放行，
但 token / roles 的位置保留，将来接真实登录只需替换 `userStore.login()`。



