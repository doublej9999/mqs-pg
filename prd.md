# MQ → PostgreSQL 配置化数据同步系统 PRD

**文档版本：** v1.0
**文档状态：** Draft
**目标：** 构建一个配置驱动的 MQ → PostgreSQL 数据同步系统，通过 Web Console 完成数据路由、字段映射及数据转换配置，实现 MQ 消息自动转换并批量写入 PostgreSQL，同时提供幂等、乱序保护、失败重试、错误记录及原始消息留存能力。

---

# 1. 产品概述

## 1.1 背景

目前消费平台中的 MQ 消息需要同步到 PostgreSQL 数据库。

不同业务消息具有不同的：

* Topic
* Tag
* JSON 数据结构
* PG 目标表
* 字段名称
* 字段类型
* 数据格式

如果每新增一种消息都通过开发代码实现 MQ Consumer 和 PG Writer，会产生较高的开发及维护成本。

因此建设一个**配置化 MQ → PostgreSQL 数据同步平台**：

```text
MQ Topic + Tag
      ↓
配置路由
      ↓
JSON Transform
      ↓
字段映射
      ↓
数据校验
      ↓
Batch
      ↓
PostgreSQL Upsert
      ↓
ACK
```

业务人员/开发人员主要通过 Web Console 配置数据同步规则，不需要针对每个 Topic 单独开发 Consumer。

---

# 2. 产品目标

## 2.1 核心目标

实现：

> **通过配置定义 MQ Topic + Tag 到 PostgreSQL Table 的映射关系，并通过统一 Runtime 自动完成消息消费、转换、批量写入及 ACK。**

---

## 2.2 核心能力

系统需要支持：

1. MQ Topic + Tag 路由
2. Topic + Tag → PG Table 配置
3. JSON 消息解析
4. JSONPath 数据提取
5. JSLT 数据转换
6. 字段自动映射
7. 字段重命名
8. 类型转换
9. 默认值
10. 常量
11. 表达式
12. 枚举转换
13. 时间格式转换
14. 字符串处理
15. 数值计算
16. 条件表达式
17. 必填字段校验
18. PostgreSQL Batch 写入
19. PostgreSQL Upsert
20. 基于 `update_time` 的单调更新
21. MQ 消息重试
22. 最大重试次数配置
23. DLQ / 最终失败处理
24. PG 故障自动暂停消费
25. PG 恢复后继续消费
26. Raw Message 异步落盘
27. Error Metadata 记录
28. Raw Message 按时间分区
29. Raw Message 自动过期
30. 配置版本管理
31. 消费时绑定 Config Version
32. 配置发布前 Sample JSON 验证
33. Web Console 管理
34. 运行监控及统计

---

# 3. 非目标

V1 暂不考虑：

* 一条 MQ 消息拆成多张 PG 表
* 一条 MQ 消息写入多个目标表
* 一个 Topic + Tag 同时路由多个目标表
* 一个消息产生多条 PG Record
* 多目标数据库事务一致性
* Consumer 多实例水平扩展
* 跨 Topic 全局顺序保证
* 基于业务 Version 的排序
* Exactly-Once MQ 消费语义

---

# 4. 核心业务规则

## 4.1 Topic + Tag 路由

V1：

```text
Topic + Tag
     ↓
一个 Target Table
```

一条 MQ 消息：

```text
1 Message
    ↓
1 PG Row
```

---

# 5. 数据模型约束

所有目标 PostgreSQL 表必须满足：

```sql
id
update_time
```

其中：

### id

* PostgreSQL Primary Key
* 系统 Upsert Key 默认使用 `id`
* Upsert Key 最终由配置明确指定

### update_time

* 必须存在
* 用于判断消息的新旧
* 用于防止乱序消息覆盖新数据

---

# 6. Upsert 规则

系统使用 PostgreSQL：

```sql
INSERT ... ON CONFLICT (...) DO UPDATE
```

实现幂等写入。

典型 SQL：

```sql
INSERT INTO target_table (
    id,
    field_a,
    field_b,
    update_time
)
VALUES (
    $1,
    $2,
    $3,
    $4
)
ON CONFLICT (id)
DO UPDATE SET
    field_a = EXCLUDED.field_a,
    field_b = EXCLUDED.field_b,
    update_time = EXCLUDED.update_time
WHERE EXCLUDED.update_time > target_table.update_time;
```

---

# 7. 最终一致性模型

系统不依赖 Consumer 保证同一个 ID 的全局消费顺序。

最终一致性由 PostgreSQL 保证。

例如：

```text
Message A
id=100
update_time=10:00

Message B
id=100
update_time=10:02

Message C
id=100
update_time=10:01
```

即使消费顺序：

```text
A → B → C
```

最终：

```text
id=100
update_time=10:02
```

C 不允许覆盖 B。

因此：

> **PostgreSQL 是最终状态裁判。**

核心规则：

```text
incoming.update_time > existing.update_time
        ↓
UPDATE

incoming.update_time <= existing.update_time
        ↓
SKIP
```

---

# 8. 相同 ID + 相同 update_time

如果：

```text
id = 100
update_time = 10:00
```

出现多条消息：

```text
A
B
```

系统没有第二排序字段。

最终以 MQ 消费顺序为准。

系统不保证：

```text
id + update_time
```

相同情况下的跨实例确定性顺序。

---

# 9. Batch 写入

MQ 支持批量拉取。

系统采用：

> **条数 + 时间双触发 Batch**

例如：

```text
Batch Size = 1000
Flush Interval = 1s
```

满足任意条件即可 Flush：

```text
records >= 1000
OR
oldest_record_wait_time >= 1s
```

---

# 10. Batch 内相同 Upsert Key

同一个 Batch 中允许出现相同 `id`：

```text
A: id=100 update_time=10:00
B: id=100 update_time=10:02
C: id=100 update_time=10:01
```

处理逻辑：

```text
A → B → C
```

按照 MQ 消费顺序处理。

最终通过 PG：

```text
10:00 → 写入
10:02 → 更新
10:01 → Skip
```

最终状态：

```text
update_time = 10:02
```

---

# 11. PG Write Result

PG Writer 不应只返回 Success / Failed。

至少需要区分：

```text
INSERTED
UPDATED
SKIPPED_OLD_VERSION
FAILED
```

定义：

| 状态                  | 含义                  | ACK   |
| ------------------- | ------------------- | ----- |
| INSERTED            | 新记录插入               | ACK   |
| UPDATED             | 新版本更新               | ACK   |
| SKIPPED_OLD_VERSION | 消息已处理，但数据比 PG 当前版本旧 | ACK   |
| FAILED              | PG 写入失败             | 不 ACK |

---

# 12. Transform Engine

Transform Engine 负责：

```text
MQ JSON
   ↓
解析
   ↓
JSONPath / JSLT
   ↓
字段 Mapping
   ↓
类型转换
   ↓
业务转换
   ↓
Target Row
```

---

# 13. Transform 能力

系统需要支持：

## 13.1 字段重命名

```text
source:
orderId

target:
order_id
```

---

## 13.2 类型转换

例如：

```text
String → Integer
String → Long
String → Decimal
String → Boolean
String → Timestamp
```

转换失败则产生 Transform Error。

---

## 13.3 默认值

例如：

```text
source:
$.status

default:
UNKNOWN
```

---

## 13.4 常量

例如：

```text
source:
无

constant:
ORDER
```

---

## 13.5 表达式

例如：

```text
amount * 100
```

或者：

```text
concat(first_name, ' ', last_name)
```

---

## 13.6 枚举转换

例如：

```text
CREATED → 1
PAID → 2
CANCELLED → 3
```

---

## 13.7 时间格式转换

例如：

```text
2026-09-25 10:30:00
```

转换：

```text
2026-09-25T10:30:00Z
```

---

## 13.8 字符串处理

支持：

* trim
* upper
* lower
* substring
* concat
* replace

---

## 13.9 数值计算

支持：

* add
* subtract
* multiply
* divide

---

## 13.10 JSONPath

用于从原始 JSON 提取字段。

例如：

```text
$.user.id
$.order.amount
```

---

## 13.11 JSLT

JSLT 用于：

> JSON → JSON 的结构转换。

推荐架构：

```text
Raw JSON
   ↓
JSLT
   ↓
Normalized JSON
   ↓
Mapping Engine
   ↓
PG Row
```

JSLT 与 PG Mapping 在职责上分离。

---

# 14. 自动字段映射

默认采用：

> **同名精确匹配**

例如：

```text
JSON:

{
    "id": 1,
    "name": "Tom",
    "amount": 100
}
```

PG：

```text
id
name
amount
```

自动映射：

```text
id     → id
name   → name
amount → amount
```

---

# 15. CamelCase → snake_case

作为可选 Mapping Strategy。

例如：

```text
orderId
```

自动匹配：

```text
order_id
```

默认不启用。

默认规则仍然是：

```text
exact match
```

---

# 16. 必填字段

如果目标表存在：

```sql
NOT NULL
```

字段，并且：

* 没有 Mapping
* 没有 Constant
* 没有 Default
* 没有 DB Default

则：

> 配置发布失败。

---

# 17. 消息处理流程

完整流程：

```text
MQ Batch Pull
      ↓
逐消息绑定 Config Version
      ↓
Raw Message Async Persist
      ↓
JSON Parse
      ↓
JSLT / JSONPath
      ↓
Field Mapping
      ↓
Transform
      ↓
Validation
      ↓
Batch PG Writer
      ↓
PG Upsert
      ↓
ACK
```

---

# 18. Config Version

消息在：

> **Consumer 接收到消息时**

绑定 Config Version。

例如：

```text
10:00
Config V1 Active

10:01
Message A received
→ Config V1

10:02
Config V2 published

10:03
Message A flush
→ 仍然使用 Config V1
```

这样可以避免 Batch 等待期间配置变化导致同一消息被不同配置处理。

---

# 19. 配置生命周期

配置状态：

```text
DRAFT
  ↓
VALIDATING
  ↓
VALID
  ↓
PUBLISHED
  ↓
ACTIVE
  ↓
INACTIVE
```

配置采用 Version 管理：

```text
order-sync
├── V1
├── V2
└── V3 ← ACTIVE
```

历史版本不可被运行中的消息动态替换。

---

# 20. 配置发布验证

Web Console 提供：

> Sample JSON

用户输入一条真实或模拟 JSON。

系统执行完整 Dry Run：

```text
Sample JSON
    ↓
Route
    ↓
JSLT
    ↓
Mapping
    ↓
Transform
    ↓
Schema Validation
    ↓
Generate SQL
    ↓
PG Transaction
    ↓
ROLLBACK
```

验证成功后允许 Publish。

---

# 21. 发布验证内容

至少验证：

### MQ

* Topic 是否存在
* Tag 是否合法

### PG

* 数据库连接
* Table 是否存在
* Column 是否存在
* Primary Key 是否存在
* Configured Upsert Key 是否有效
* `update_time` 是否存在

### Mapping

* Source JSONPath 合法
* Target Column 存在
* 类型转换合法
* 必填字段满足要求

### Expression

* Expression 可以编译
* Sample JSON 可以正常计算

### PG Write

Sample JSON 能够生成合法 PG Row。

---

# 22. 错误处理

系统区分：

## 22.1 数据处理错误

包括：

```text
JSON Parse Error
Missing Required Field
Transform Error
Type Conversion Error
Expression Error
JSLT Error
```

根据当前产品规则：

> **这些错误都不 ACK。**

---

# 23. Retry

数据处理错误：

```text
Transform
   ↓
Error
   ↓
Retry
```

最大重试次数可配置。

默认：

```text
10 次
```

例如：

```text
retry_count < 10
    ↓
Retry

retry_count >= 10
    ↓
进入最终失败处理
```

---

# 24. PG 错误

例如：

```text
PG Connection Error
PG Timeout
PG unavailable
```

处理：

```text
PG Error
   ↓
暂停 MQ Consumer
   ↓
等待 PG 恢复
   ↓
恢复消费
```

消息不 ACK。

消息继续堆积在 MQ。

---

# 25. PG 恢复

Consumer 应具备：

```text
RUNNING
PAUSED
RECOVERING
```

状态。

正常：

```text
RUNNING
```

PG 连续失败：

```text
PAUSED
```

周期性检测 PG：

```text
Health Check
```

PG 恢复：

```text
RECOVERING
```

完成必要初始化后：

```text
RUNNING
```

---

# 26. 重试退避

系统支持指数退避。

例如：

```text
1s
2s
4s
8s
16s
...
```

最大等待时间可配置。

---

# 27. ACK 语义

当前系统采用：

> **PG 写入成功后 ACK。**

以下情况 ACK：

```text
INSERTED
UPDATED
SKIPPED_OLD_VERSION
```

以下情况不 ACK：

```text
PG Failure
Transform Error
JSON Parse Error
Missing Required Field
```

当消息达到最大重试次数后，需要进入最终失败处理机制后再 ACK，避免 Poison Message 无限阻塞。

---

# 28. ACK 失败

如果：

```text
PG Write SUCCESS
      ↓
ACK FAILED
```

消息可能再次被 MQ 投递。

系统允许重复处理。

由于 PG 使用：

```text
ON CONFLICT
+
update_time monotonic guard
```

因此重复消息：

```text
INSERT
→ UPDATE / SKIP
```

不会导致错误的最终状态。

这就是系统的：

> **At-Least-Once + Idempotent Upsert + Eventual Consistency**

模型。

---

# 29. Raw Message

Raw Message 与 PG 主写链路解耦。

采用：

> **异步落盘**

即：

```text
MQ Consumer
    ├──────────────→ Raw Writer Async
    │
    ↓
Transform
    ↓
PG
    ↓
ACK
```

Raw Storage 不是 MQ → PG 一致性的必要环节。

Raw Writer 异常不应阻塞 PG 主流程。

---

# 30. Raw Message 数据

建议保存：

```text
message_id
topic
tag
receive_time
config_version
payload
```

对于失败消息，同时保存 Error Metadata。

---

# 31. Error Metadata

保存：

```text
message_id
topic
tag
config_version
error_time
error_stage
error_code
error_message
retry_count
```

例如：

```json
{
  "message_id": "abc123",
  "error_stage": "TRANSFORM",
  "error_code": "TYPE_CONVERSION_ERROR",
  "error_message": "amount cannot convert to numeric",
  "retry_count": 3
}
```

---

# 32. Raw Message 分区

Raw Message 按时间分区。

例如：

```text
raw_message_20260925
raw_message_20260926
raw_message_20260927
```

推荐按照：

```text
receive_time
```

进行日分区。

---

# 33. Raw Message TTL

支持自动过期。

例如：

```text
Retention = 7 days
```

过期流程：

```text
发现过期分区
      ↓
DETACH PARTITION
      ↓
DROP 物理表
```

不推荐使用：

```sql
DELETE FROM raw_message
WHERE receive_time < ...
```

避免大量 DELETE 导致：

* WAL 增长
* Vacuum 压力
* IO 压力

---

# 34. Web Console

Web Console 是系统主要管理入口。

核心页面：

```text
Dashboard
Data Routes
Configuration
Config Versions
Message Errors
Raw Messages
Runtime Status
Metrics
```

---

# 35. Route 配置页面

配置：

```text
MQ
├── Topic
└── Tag

Target
├── PG Connection
├── Table
├── Upsert Key
└── update_time

Transform
└── JSLT / Mapping
```

---

# 36. Mapping UI

字段列表：

| Source   | Target | Transform | Required | Default |
| -------- | ------ | --------- | -------- | ------- |
| $.id     | id     | Long      | Yes      | -       |
| $.name   | name   | String    | No       | -       |
| $.amount | amount | Decimal   | Yes      | 0       |
| $.status | status | Enum      | No       | UNKNOWN |

支持：

* 自动生成
* 手动修改
* 删除 Mapping
* 字段搜索
* 类型展示
* Preview

---

# 37. Preview

用户输入 Sample JSON：

```json
{
  "id": 100,
  "name": "Tom",
  "amount": "100.50"
}
```

点击：

```text
Preview
```

展示：

### Normalized JSON

```json
{
  "id": 100,
  "name": "Tom",
  "amount": "100.50"
}
```

### Target Row

```text
id          = 100
name        = Tom
amount      = 100.50
update_time = ...
```

### SQL

展示最终生成的 Upsert SQL 模板。

---

# 38. Runtime Dashboard

核心指标：

### MQ

```text
Messages Consumed
Messages ACKed
Messages Retry
Messages Failed
MQ Lag
```

### Transform

```text
Transform Success
Transform Error
JSON Parse Error
Missing Required Field
```

### PG

```text
PG Inserted
PG Updated
PG Skipped
PG Failed
PG Latency
Batch Size
Batch Flush Count
```

### Raw

```text
Raw Write Success
Raw Write Failed
Raw Storage Size
```

---

# 39. 重要监控指标

建议至少提供：

```text
consumer_messages_total
consumer_ack_total
consumer_retry_total
consumer_error_total

transform_success_total
transform_error_total

pg_insert_total
pg_update_total
pg_skip_old_version_total
pg_write_error_total

pg_batch_size
pg_batch_flush_total
pg_write_latency

raw_write_success_total
raw_write_error_total
```

---

# 40. Consumer 状态

每个 Topic + Tag Route 维护状态：

```text
RUNNING
PAUSED
RECOVERING
ERROR
```

例如：

```text
order-topic / order.updated
        ↓
PAUSED
Reason:
PostgreSQL connection refused
```

---

# 41. 第一版并发模型

V1：

```text
一个 Topic + Tag
        ↓
一个 Consumer
```

即：

```text
Route
  ↓
Consumer
  ↓
Batch
  ↓
PG
```

暂不进行 Consumer Instance 水平扩展。

架构设计应为未来扩展预留：

```text
Topic + Tag
      ↓
Consumer Group
   ┌──┼──┐
   ↓  ↓  ↓
  C1 C2 C3
```

---

# 42. 一致性模型

系统不追求 MQ Exactly-Once。

采用：

```text
MQ:
At-Least-Once

Consumer:
At-Least-Once Processing

PG:
Idempotent Upsert

Ordering:
Business update_time

Final State:
Eventual Consistency
```

---

# 43. 一致性示例

初始：

```text
PG:
id=1
update_time=10:00
amount=100
```

收到：

```text
Message A
update_time=10:02
amount=200
```

执行：

```text
UPDATE
```

之后收到旧消息：

```text
Message B
update_time=10:01
amount=150
```

执行：

```text
SKIP
```

最终：

```text
id=1
update_time=10:02
amount=200
```

---

# 44. 系统总体架构

```text
                         ┌─────────────────┐
                         │   Web Console   │
                         └────────┬────────┘
                                  │
                                  ▼
                         ┌─────────────────┐
                         │ Config Service  │
                         │ Version / Draft │
                         └────────┬────────┘
                                  │
                                  ▼
┌──────────┐              ┌─────────────────┐
│   MQ     │─────────────→│ MQ Consumer     │
└──────────┘              └────────┬────────┘
                                   │
                      Config Version Binding
                                   │
                   ┌───────────────┴──────────────┐
                   │                              │
                   ▼                              ▼
          ┌─────────────────┐            ┌─────────────────┐
          │ Transform Engine│            │ Raw Writer      │
          │ JSLT / Mapping  │            │ Async           │
          └────────┬────────┘            └─────────────────┘
                   │
              Valid / Error
                   │
                   ▼
          ┌─────────────────┐
          │ Batch Manager   │
          └────────┬────────┘
                   │
                   ▼
          ┌─────────────────┐
          │ PG Writer       │
          │ Batch Upsert    │
          └────────┬────────┘
                   │
                   ▼
          ┌─────────────────┐
          │ PostgreSQL      │
          │ update_time     │
          │ monotonic guard │
          └────────┬────────┘
                   │
                   ▼
                  ACK
```

---

# 45. 消息生命周期

```text
                MQ Batch Pull
                     │
                     ▼
              Bind Config Version
                     │
                     ▼
              Async Raw Persist
                     │
                     ▼
                Parse JSON
                     │
                ┌────┴────┐
                │         │
              Valid      Error
                │         │
                ▼         ▼
            Transform   Retry
                │
          ┌─────┴─────┐
          │           │
        Valid        Error
          │           │
          ▼           ▼
       PG Batch     Retry
          │
      ┌───┴────┐
      │        │
   Success    Fail
      │        │
      ▼        ▼
     ACK     Retry
```

---

# 46. 失败状态机

```text
              ┌──────────────┐
              │   Consumed   │
              └──────┬───────┘
                     │
                     ▼
                 Transform
                     │
              ┌──────┴───────┐
              │              │
            Success         Error
              │              │
              ▼              ▼
           PG Batch        Retry
              │              │
        ┌─────┴─────┐        │
        │           │        │
     Success       Fail      │
        │           │        │
        ▼           ▼        │
       ACK         Retry ─────┘
                         │
                  retry >= max
                         │
                         ▼
                       DLQ
                         │
                         ▼
                        ACK
```

---

# 47. 配置数据模型

建议逻辑上拆成：

```text
DataSource
Route
Target
Mapping
Transform
ConfigVersion
```

---

## 47.1 Route

```text
route_id
name
topic
tag
target_id
active_version
status
```

---

## 47.2 Target

```text
target_id
pg_datasource
schema
table
upsert_keys
update_time_field
```

---

## 47.3 Mapping

```text
mapping_id
config_version
source_path
target_column
transform_type
transform_config
required
default_value
constant_value
expression
```

---

## 47.4 Config Version

```text
route_id
version
status
config_content
created_by
created_at
published_at
```

---

# 48. 配置示例

```yaml
route:
  topic: order-topic
  tag: order.updated

target:
  datasource: order-pg
  schema: public
  table: orders

  upsertKeys:
    - id

  updateTimeField: update_time

mapping:
  - target: id
    source: $.id
    transform: long

  - target: name
    source: $.name
    transform: string

  - target: amount
    source: $.amount
    transform: decimal

  - target: status
    source: $.status
    transform:
      type: enum
      mapping:
        CREATED: 1
        PAID: 2
        CANCELLED: 3

  - target: source
    constant: ORDER_SYSTEM
```

---

# 49. Batch 设计原则

Batch Manager 负责：

```text
collect
buffer
flush
```

Flush 条件：

```text
batch_size >= configured_size
OR
flush_interval reached
```

同一个 Batch：

```text
Valid Records
+
Invalid Records
```

Invalid Record 不应该阻塞 Valid Record。

Valid Record：

```text
→ PG Batch
```

Invalid Record：

```text
→ Retry
```

---

# 50. PG 故障保护

PG Writer 连续出现连接/写入失败时：

```text
PG failure
   ↓
Consumer Pause
```

停止继续拉取 MQ。

PG Health Check：

```text
failure
failure
failure
...
```

恢复：

```text
success
success
```

之后：

```text
Consumer Resume
```

---

# 51. 可靠性目标

系统采用：

> **At-Least-Once**

不承诺：

> Exactly-Once Message Processing

但是通过：

```text
Primary Key
+
update_time
+
ON CONFLICT DO UPDATE
+
update_time guard
```

实现：

> **业务最终状态幂等。**

---

# 52. 数据一致性原则

核心原则：

> **MQ 是事件来源，PostgreSQL 是最终状态存储。**

Consumer 不维护业务状态。

Consumer 不依赖：

* Redis
* 本地状态
* 全局顺序
* Consumer Instance 间协调

来判断消息新旧。

判断逻辑统一下沉至 PostgreSQL：

```text
Incoming update_time
        ↓
PG existing update_time
        ↓
incoming > existing ?
     /       \
   YES        NO
    ↓          ↓
 UPDATE       SKIP
```

---

# 53. V1 验收标准

## 配置

* [ ] 能创建 Topic + Tag Route
* [ ] 能配置 PG Table
* [ ] 能配置 Upsert Key
* [ ] 能配置 Mapping
* [ ] 支持自动同名映射
* [ ] 支持可选 camelCase → snake_case
* [ ] 支持 Config Version

## Transform

* [ ] JSONPath
* [ ] JSLT
* [ ] Rename
* [ ] Type Conversion
* [ ] Default
* [ ] Constant
* [ ] Expression
* [ ] Enum
* [ ] DateTime
* [ ] String
* [ ] Number
* [ ] Condition

## Validation

* [ ] Sample JSON
* [ ] Schema Validation
* [ ] Mapping Validation
* [ ] Transform Validation
* [ ] PG Dry Run

## Runtime

* [ ] MQ Batch Pull
* [ ] Config Version Binding
* [ ] Batch Flush
* [ ] PG Batch Upsert
* [ ] update_time 单调更新
* [ ] Old Message Skip
* [ ] ACK

## Reliability

* [ ] PG Failure Pause
* [ ] PG Recovery Resume
* [ ] Retry
* [ ] Exponential Backoff
* [ ] Configurable Max Retry
* [ ] DLQ / Final Failure
* [ ] ACK Failure 可安全重复处理

## Raw

* [ ] Async Raw Storage
* [ ] Time Partition
* [ ] TTL
* [ ] Detach Partition
* [ ] Physical Drop
* [ ] Error Metadata

## Console

* [ ] Route Management
* [ ] Config Version
* [ ] Preview
* [ ] Publish
* [ ] Error Query
* [ ] Runtime Status
* [ ] Metrics

---

# 54. 关键设计决策总结

| 问题                     | 决策                        |
| ---------------------- | ------------------------- |
| Topic + Tag → Table    | 一对一                       |
| Message → Row          | 一对一                       |
| Upsert Key             | 配置指定                      |
| 默认业务 Key               | PG `id`                   |
| 必备字段                   | `id` + `update_time`      |
| 数据最终顺序                 | `update_time`             |
| 相同 id + update_time    | MQ 顺序                     |
| MQ 顺序保证                | 不作为最终一致性依据                |
| PG 一致性                 | `update_time` 单调更新        |
| MQ Delivery            | At-Least-Once             |
| PG Write               | Batch                     |
| Batch Flush            | 条数 + 时间                   |
| PG 故障                  | 暂停消费                      |
| PG 恢复                  | 恢复消费                      |
| JSON Parse Error       | 不 ACK                     |
| Missing Required Field | 不 ACK                     |
| Transform Error        | 不 ACK                     |
| Retry                  | 指数退避                      |
| 默认最大重试                 | 10                        |
| 最终失败                   | DLQ / Final Failure 后 ACK |
| ACK 失败                 | 允许重复消费                    |
| Raw Message            | 异步落盘                      |
| Raw Partition          | 按时间                       |
| Raw TTL                | 自动过期                      |
| Config Version         | 接收消息时绑定                   |
| Mapping 默认             | 同名精确匹配                    |
| camelCase              | 可选                        |
| 多 Consumer             | V1 暂不支持                   |
| 一个 Consumer            | 一个 Topic + Tag            |
| 一条消息多表                 | 不支持                       |
| 一条消息拆多行                | 不支持                       |

---

# 55. 产品核心原则

整个系统最终可以浓缩为五句话：

### ① 配置驱动

```text
MQ Topic + Tag
        ↓
Config
        ↓
PG Table
```

### ② 转换可配置

```text
JSON
 ↓
JSLT / JSONPath
 ↓
Mapping / Transform
 ↓
PG Row
```

### ③ 批量写入

```text
MQ Batch
 ↓
Transform
 ↓
PG Batch Upsert
```

### ④ 数据最终一致

```text
Primary Key
+
update_time
+
Upsert
+
Monotonic Update
```

### ⑤ MQ 至少一次 + PG 幂等

```text
PG Success
    ↓
ACK

ACK Failure
    ↓
Duplicate Delivery
    ↓
Idempotent Upsert
    ↓
Final State Unchanged
```

因此系统的核心不是追求：

> “一条消息只执行一次”

而是：

> **“一条消息可以执行多次，但无论如何重试、重复、乱序，PG 最终状态都收敛到业务 `update_time` 最新的数据。”**
