# 演进记录

本文件按时间记录每一次优化的**背景 / 改动 / 验证 / 影响与回滚 / README 更新**。
整体方案见 [`docs/optimization-plan.md`](docs/optimization-plan.md)（§11.1 为记录规范）。
文件按 **倒序** 排列：最新的一条紧跟在分隔线之后，**新增条目插在顶部**，不是追加到文件末尾。

---

#
## 2026-10-21 · 里程碑 K / L / M 收尾（K2、K4、K6–K8；L1、L2、L4–L6；M 全部覆盖）

这一批里有两处「计划说要跳过、实测发现不该跳」（L4）和两处「计划要做的、
本机条件不具备」（L2 镜像构建、L1 前端 lint）。都如实记了。

### K：前端体验

- **K2 运维页只在首次进入时取数（工作量最大、收益最直接的一条）**
  - 背景：视图被 `<keep-alive :max="10">` 缓存，6 个视图全部只在 `onMounted`
    拉一次数据，全仓 `setInterval|setTimeout|onUnmounted` **零命中**。
    从「总览」切到「重试队列」再切回，看到的是首次进入时的快照 ——
    对运维面板这是实质性缺陷：用户以为看到的是现在。
  - 改动：dashboard / retry / errors / raw 四个「以实时性为职责」的视图改用
    `onActivated`。它在首次挂载时也会触发，因此可以直接替代 `onMounted`，
    不会漏掉首屏加载。总览页加可选 30s 轮询，开关在右上角，**默认关闭**。
  - 定时器 `onDeactivated` + `onUnmounted` **双清理**：缓存组件不会被卸载，
    只写 `onUnmounted` 会漏，人离开页面后仍在发请求 —— 验收恰好是
    「离开后 Network 面板不再有新请求」，只写一个必然漏。
  - 轮询走 `quietRefresh`（不置 loading 骨架），否则每 30 秒整页闪一下；
    上一轮未完成时跳过本次，避免请求叠加。
  - **刻意不改 datasources / routes**：用户正在编辑表单时被一次「自动重取」
    把输入冲掉，比看到旧数据更糟。

- **K4 两个对话框共用一个 `columns` ref** —— 这是本次唯一一个「会让人看到
  错误数据」的前端缺陷：路由表单的 `loadColumns()` 与映射编辑器的
  `loadColumnsFor()` 写同一个 ref，两框同开时后写者覆盖前者，
  映射编辑器里出现**另一张表的列**，进而可能选出目标表上不存在的列。
  拆成 `mappingColumns` 后物理上不可能互相污染。
  另外 `openMappings` 原先先置 `visible=true` 再 await —— 任一请求失败就留下
  空对话框，而「映射为空」会被理解成配置丢了。改成全部就绪再显示；
  取列失败不阻断（编辑器允许手工填列名）但会 warning 说明，
  免得用户分不清「没取到」和「表没列」。

- **K6 按需引入（唯一的量化收益：首屏 JS 1218 → 642 KB，CSS 364 → 152 KB）**
  - 图标：原来 `main.js` 把 `@element-plus/icons-vue` **全部**注册成全局组件，
    注释理由是「图标用量不大」—— 但所有页面本来就是显式 import 的。
    删之前先给 Navbar（`UserFilled`/`ArrowDown`）与 login（`User`/`Lock`）
    补 import，那两处确实依赖全局注册，不补就是空标签。
  - `unplugin-vue-components` 按需引入；去掉 `app.use(ElementPlus)`，
    中文 locale 改由 `App.vue` 的 `<el-config-provider>` 提供
    （缺了它分页/日期选择器的内置文案会退回英文）。
  - 页面里 `from 'element-plus'` 全部改深路径 —— barrel 会 re-export 全部组件，
    从 barrel 取一个 `ElMessage` 就等于把整套拉回包里。
  - `manualChunks` 只拆 vue，**不**强制归并 element-plus：
    强制归并会把按需效果掩盖掉，观测到的体积不是真的
    （第一次就是被这个骗了，改完体积几乎没变）。
  - **按需引入最容易踩的两个坑，都验过**：① 函数式 API 的样式插件解析不到
    （`ElMessage`/`ElMessageBox` 不出现在模板里），必须手动 import 它们的
    `style/css`；② **指令**不在 resolver 范围 —— `v-loading` 全仓 7 处，
    不显式 `app.directive('loading', ElLoading.directive)` 就**不显示加载遮罩
    且不报错**，属于最难发现的回归。`ElLoading` 也走深路径。
  - 产物校验是脚本核对而非目测：实际用到的组件类名在 CSS 中全在
    （`el-option` 的真实类名是 `el-select-dropdown__item`，8 处，正常）；
    未使用的 `el-radio`/`el-empty`/`el-tabs`/`el-date-picker` 产物里也没有 ——
    这正是按需引入应有的样子。

- **K7 失败静默成空表**：五个 `load()` 只有 `try/finally` 没有 `catch`，
  后端不可达时表现为「没有数据」而不是「加载失败」，与「确实没有流水 /
  没有路由」长得一模一样。补 `catch` + 顶部 `el-alert`，并**清空**列表
  （留着上一次结果会让人误以为是当前状态）。retry 页改 `Promise.allSettled`：
  列表失败时清空报错，但摘要仍显示 —— 摘要取到了没理由一起丢。

- **K8 小项**：版本抽屉「刷新」改用已存在的 `reloadVersions`（原来复用
  `openVersions` 会让抽屉重开一遍）；limit 输入框补 `@change`；
  操作列空表头补「操作」；三个零调用 API 标「预留」而**不是**删
  （`createTarget`/`updateTarget` 是在用的，一刀切很容易误伤）；
  `api/overview.js` 拆出 `api/raw.js` 与 `api/errors.js`；
  `index.html` 补 description 与内联 SVG favicon（不引入二进制资源）。
  `store/user.js` 写清「这不是鉴权，只是界面占位，绝不能当安全边界」——
  按 §9 Q1 保留现状，但把话说白，而不是假装它是安全的。

- **K5 只做了前半，理由要说清**：抽掉三处逐字重复的草稿水合
  （`persistDraft` **刻意不改** —— 保存后回写与「加载草稿」语义不同，
  三处看着像重复其实只有两处是同一件事），状态色收敛到
  `constants/status.js`。后者修掉了一个**实际不一致**：同一个 `PAUSED`，
  总览页是黄色、路由页是红色，运维只会以为是两个状态。
  拆成 RoutesTable / RouteFormDialog / VersionDrawer / MappingEditor
  四个组件（计划 K5 的主体）**没做**：纯结构重构、前端零测试兜底，
  必须逐页手测，当前条件不具备；单独一次提交更便于 revert。

### L：工程化

- **L4 计划标「条件性跳过」，实测发现不该跳**（详见该提交）：
  springdoc 2.8.6 的父 POM 面向 Boot 3.4.x，看着不兼容；实际起服务验过
  可用（33 个端点全列出、Swagger UI 200）。默认关闭、dev 打开。
  **顺带撞出一个既有缺陷**：`/v3/api-docs` 在关闭时返回 500 而不是 404 ——
  根因是 Spring 6.1 起未匹配请求抛 `NoResourceFoundException`，
  落到 `Exception` 兜底分支就成了「500 + 服务内部错误 + error 级堆栈」，
  即**任何拼错的 URL 都会往日志里刷一条内部错误**。加专门处理器映射 404。

- **L5**：`jsconfig.json`（只做路径跳转，`checkJs: false` 不做类型检查）；
  体积守卫 `scripts/check-bundle-size.js` + `pnpm size` + CI 步骤。
  阈值取 K6 实测基线 + 约 15% 余量（642/152/160 → 760/190/200）。
  刻意用**原始字节**而非 gzip：gzip 对「某组件被意外全量引入」不敏感。
  失败时打印常见原因，而不是只丢一句「超了」。

- **L1 只做了后端**：spotless 绑到 `verify` 的 `check`，**只 check 不 apply**
  （构建过程自动改源码是隐式副作用，CI 上尤其危险）。
  规则克制到只做 import 顺序 / 尾随空格 / 末尾换行 —— 按 L1 要求
  「格式化必须单独一次提交 + 先配 `.git-blame-ignore-revs`」分两步走。
  **前端 lint 没做，是有意的**：AGENTS.md 明确写着「仍无 lint/format/typecheck
  工具，不要为它发明命令」，而引入 eslint+prettier 会带三个新依赖、一套配置
  和一波需逐页目测的 diff，前端零测试兜底。文档里也不写不存在的命令。

- **L2 容器化**：backend/frontend Dockerfile + nginx 反代 + compose + .dockerignore。
  **镜像构建未能在本机验证**：Docker Hub 不可达
  （registry-1.docker.io / auth.docker.io 探测 False，npm registry 正常），
  本地也没有任何 jdk/node/nginx 基础镜像可复用，
  `docker build` 停在 `FROM eclipse-temurin:21-jre`。
  已做的是静态交叉核对：`docker compose config` 通过、nginx 的
  `proxy_pass` 服务名 = compose 服务名、三个端口互不冲突、
  `.dockerignore` 未误排 `nginx.conf`、jar 名与 COPY 通配匹配、
  pnpm 12 与 lockfileVersion 9.0 对得上。
  **完整的 `docker compose up` 没有跑过**，这一点写进了提交信息与 README。

- **L6 文档单一事实来源**：README 不再重复写用例数（原来写死「83」，
  实际已 102，这类数字必定过时）。用例数与通过情况只写在 evolution.md，
  里程碑状态只写在 README 进度表。AGENTS.md 的「只有三个脚本」也同步成四个。

### M：集成测试

**五项全部已有覆盖，无需新增**（逐条核对过，不是推断）：
- M1 并发激活 → `ConfigAdminTest#concurrentActivateKeepsSingleActive`（G1 时写的）
- M2 并发草稿 + 发布 → `ConfigAdminTest#concurrentPublishAndSaveDraftKeepsValidatedContent`
- M3 重试断层 → `RetrySchedulerTest#missingConfigEventuallyGoesToDlq`（G3 时写的）
- M4 部分唯一索引 → `ConfigAdminTest`「Upsert Key 上只有部分唯一索引时发布失败（I3 / M4）」
- M5 自愈对账 → `ConfigRegistryTest#reconcileRepairsDrift`（H1 时写的）

### 验证与回滚

- `mvn test` **102 passed**（101 → 102：新增 `unknownPathIs404`）；
  `mvn verify -DskipTests` 通过，spotless「138 files clean」。
- `pnpm build` + `pnpm size` 通过（JS 642/760、CSS 152/190、最大 160/200）。
- 前端四条改动（K2/K4/K6/K7）**没有自动化测试兜底**（项目无前端测试框架），
  验收方式是逐页手测：切走再切回数据是否新、两框同开列是否串、
  停后端是否有明确失败提示、`v-loading` 遮罩是否还在。
  K6 的按需引入尤其要逐页目测样式（这是它唯一的真实风险）。
- 回滚点：K6 可整体 revert 回「全量引入」；L1 拆成两个提交，
  格式化与检查可分别回滚；L2/L4 都是新增文件/依赖，移除即回滚。
- 全程未触及 §6 红线（`WHEN MATCHED` 单调守卫、ACK 语义、版本绑定、
  `cfg_version.content` 不可变、留存非阻塞、DEFAULT 分区修法）。

---

## 2026-10-21 · 里程碑 J 全部交付（J1–J7 重试与写入热路径）

主题是「积压与热路径上的放大效应」：重试逐条写、租约覆盖不住整批、
MERGE 文本每块重拼、热路径逐行分配。逐条记录，含三次被测试纠正的判断错误。

- **J2 租约覆盖不住整批**（先做，因为 J1 会放大它）
  - 背景：领取时给**整批**打同一个 5 分钟租约，然后顺序处理 200 条。目标库慢时
    （每条一个 5s 连接超时）200 条最坏 1000s ≫ 300s，`reclaimExpiredLeases`
    会把**仍在处理**的任务放回 PENDING —— 同一任务被重复重放。
    `uk_retry_active` 只约束 PENDING/RUNNING 并存，防不了重放。
  - 改动：两道防线。① 初始租约 `max(leaseDuration, batchSize × estimatedPerTask)`，
    新增 `estimated-per-task`（默认 2s）；② 真正的兜底是 `renewLease`：
    每 `renew-lease-every`（默认 20）条续租一次。
  - **续租 SQL 必须带 `status='RUNNING'`**：先处理完的任务已是 SUCCESS/PENDING，
    不带这个条件会把它们连同租约一起「复活」。受影响的正是这一条 ——
    返回行数与传入 id 数不符即说明有任务被回收，**立即停止本轮**。

- **J1 重试逐条写**
  - 背景：每条任务单独 `writer.write(cfg, List.of(one))`，积压 200 条就是
    200 次 MERGE、200 次拼文本、200 次往返。积压本身就是异常态，重试路径把它放大得最厉害。
  - 改动：`RetryReplayer#prepareGroup` 读一次绑定版本配置 → 逐条 transform →
    `BatchFolder#foldRows` → `PreparedGroup`；`RetryScheduler#processBatch`
    按 `(routeId, configVersion)` 分组，**组间顺序**执行（目标库连接池只有 4–10 条，
    并发会和正常消费抢连接，把一次积压放大成全场抖动），每组一次 `write`，
    再用 `MergeResult` 逐条映射结论。
  - 单条与批量**共用 `applyOutcome`**：这套状态机有四处易错分叉
    （CONFIG_MISSING/PG_FAILURE 不消耗次数、CONFIG_GONE 有限轮缓冲、attempt 独立推进），
    两份拷贝一旦漂移就会出现「单条重试进 DLQ、批量重试不进」这种只在积压时暴露的不一致。
  - 新增 `CandidateRow` + `foldRows`：重放路径消息早已 ACK，**不能**构造
    `CandidateRecord`（它要求 `MqsMessage` 才能生成 `MessageRef`），否则
    `FoldedRecord.sources()` 非空，而重放路径恰恰依赖它为空。两条折叠路径共用
    同一个 `compareUpdateTime` —— 规则漂移会让同一条消息在实时与重试路径得到不同的胜出者。
  - 分组键用 record 而不是拼字符串：后者加第三个维度时很容易只改一处而静默串组。

- **J3 每块重拼 MERGE 文本**
  - 背景：`USING (VALUES (?,?),(?,?),...)` 按行数生成，行数一变文本就变；
    PG 侧每个新文本都是一份新的解析/规划（pgjdbc 缓存按文本命中，行数一变就 miss）。
  - 改动：拆成「渲染」与「绑定」。`render` 只依赖 (表, 列, Upsert Key, 守卫字段,
    列类型, 行数)，结果进 `ConcurrentHashMap<SqlKey, String>`；参数按块另绑。
    key 用 record 而不是拼串：**少一个要素就会产生错误的 SQL**，而错误 SQL 的症状
    （列不存在、类型不匹配）离根因很远。类型串也进 key —— `?::numeric` 本就是文本的一部分。
    上限 256，满了整体清空而不做 LRU（表结构变更会产生新 key，无上限就是泄漏；
    「几种行数 × 几张表」远小于上限，清空代价可忽略，换来不必为线程安全写更复杂的结构）。
    新增 gauge `mqspg.write.sql.cache.size`。
  - **被测试纠正**：第一版用例断言「写一次单行批次后缓存条目数变大」，失败 ——
    单行文本早已被其它用例写进同一个 Spring 单例缓存。改成「先跑到基线，再验证
    重复写入不增长」，语义反而更贴近真正关心的东西（命中率）。

- **J4 热路径逐行分配**
  - 改动：`KeyTuple#ofNormalized(Object[])`（先规整再 `Arrays.asList` →
    `List.copyOf` 正好一次拷贝）；列标签 `__keyN` 按块算一次，块内所有行共用。
  - **被测试纠正（20 条全红）**：把 `jdbc.query` 第三个参数从
    `compiled.params().toArray()` 改成 `compiled.params()`，以为能省一次数组转换。
    PG 报 SQLSTATE 07006。原因是 `JdbcTemplate.query(String, ResultSetExtractor, List)`
    与 `(String, ResultSetExtractor, Object...)` 是**两个语义不同的重载**：
    后者把数组展开成 N 个绑定参数，前者把整个 List 当**一个**参数绑上去。
    最终改回数组（封装成 `CompiledMerge#paramsArray()` 并把这个坑写进注释）。
    本条真正省下的是「每行一个 ArrayList + 每行拼标签」，数组转换那处保留原样。

- **J7 JSLT 编译缓存无上限**
  - 背景：`jsltCache` 是「不淘汰」的 ConcurrentHashMap，注释理由是「配置版本数有限」。
    这话只在「每个版本都发布上线」时成立 —— 而草稿校验 `validateJslt` 也走这个缓存，
    **每改一次草稿就新增一项**，一下午调脚本就能攒出上千条永不释放的编译产物。
  - 改动：手写 LRU（`LinkedHashMap(accessOrder=true)` + `removeEldestEntry`，上限 512）。
    不引入 Caffeine：只有一处用法、一个上限数字。并发上直接 `synchronized` ——
    命中是 O(1) 查找，未命中是编译一整段脚本，锁竞争完全淹没在昂贵的路径里。
    抽出 `compileJslt` 让 `applyJslt` 与 `validateJslt` 共用一处缓存入口。

- **J5 列表接口无分页 / 内存排序**
  - 背景：`getRoute` 每次都把全部版本读进内存再 `sort`；版本数到几百后详情页明显变慢。
  - 改动：`listVersions(routeId, limit)` 把 `ORDER BY version DESC` 下推到 SQL 并
    追加 `LIMIT`；`listVersions(routeId)` 委托给 `RECENT_VERSIONS = 50`（改为 public）。
    刻意**不**返回版本总数：详情页只要「最近改过什么」，多一次 `count(*)` 让每次刷新
    都多一趟往返，换来的信息没人用。
  - 索引事实按查证结果写：`cfg_version` 有 `UNIQUE (route_id, version)`，
    「过滤 + 倒序 + LIMIT」可走索引倒序扫描。第一版注释凭印象说「没有索引」，已纠正。
  - **被测试纠正三次**：① `admin.createVersion` 造第二个版本就报「已有草稿版本」——
    `uk_version_draft (route_id) WHERE status='DRAFT'` 兜着，每路由至多一个草稿；
    ② 改用 SQL 插行，先用 DRAFT 仍撞 `uk_version_draft`（route_id 唯一），必须用 PUBLISHED；
    ③ 版本号固定从 1000 起仍撞唯一键，得从 `MAX(version)` 之后才开始。

- **J6 分页 offset 溢出**
  - 两处 `int offset = Math.max(0, page - 1) * size` 在 `page = Integer.MAX_VALUE`
    时溢出为负 → PG 报错 → 500。改为 long 计算 + 夹到 int 范围。
  - 索引那半（`idx_error_final_id`、`idx_error_stage_id`）已在 V2 先行交付。
    keyset 分页按计划「放后」，未做。

- **影响与回滚**
  - J1 改变了重试路径的写入批次形状，但**每条任务的 attempt / DLQ 语义不变**
    （有 12 条 `RetrySchedulerTest` 覆盖，含原有 10 条逐条路径）。
  - J2 新增两个配置项，默认值即可用；`renew-lease-every=0` 可退回「只靠初始租约」。
  - J3 的缓存对语义完全透明，出问题时清空缓存或把上限设为 0 都不影响正确性。
  - J5 **会让详情页只显示最近 50 个版本** —— 前端版本抽屉需要配合「加载更多」，
    属于 K5 的范围；`listVersions(routeId, 0)` 仍可取全量。
  - 全部不触及 ACK 与 MERGE 的单调守卫（§6 红线），`WHEN MATCHED` 逐字未动。

- **验证汇总**：`mvn test` 101 passed（I 收尾 94 → J 新增 7：
  RetrySchedulerTest +3、PgWriterTest +1、JsltCacheTest +2、ConfigAdminTest +1）。

---

## 2026-10-21 · 里程碑 I 全部交付（I1–I8 校验前置）

主题是「校验本该在保存/发布时就拦下，却全都推迟到运行期」。
逐条记录，含两次被测试纠正的判断错误。

- **I1 starter-validation 引入了却零使用**
  - 背景：`pom.xml` 有 `spring-boot-starter-validation`，全仓
    `@Valid|@NotNull|@Size` **零命中**；长度校验全靠三个只判空的
    `requireXxxFields`。于是 `topic` 超 256 直接撞 `VARCHAR(256)`，
    用户看到 SQLSTATE 22001 原文。
  - 改动：三个请求 DTO 加注解，**上限逐个照抄 DDL**（name≤128/64、
    topic≤256、tag≤256、schema/table/updateTimeField≤64、jdbcUrl≤1024、
    username≤128）；5 个写接口加 `@Valid`；`GlobalExceptionHandler`
    新增 `MethodArgumentNotValidException` → 400 + `field: message` 拼接。
  - **空值规则必须逐个对照 DDL**：`tag` 有 `DEFAULT ''` 所以只限长度不加
    `@NotBlank`；`password` 允许为空（修改时「留空 = 保留原密码」），
    加了 `@NotBlank` 就直接废掉改其他字段的接口。
  - 手工 `require*Fields` 保留 —— 它们还要管跨表校验（名称重复、
    同数据源同表、数据源被引用），注解表达不了。

- **I2 `validateJslt` 是死代码**
  - 背景：`TransformEngine#validateJslt` 注释就写着「供配置发布时做 JSLT
    语法预校验」，但**全仓零调用者**。坏脚本能通过发布、上线，直到第一条
    真实消息到达才以 `JSLT_ERROR` 进 DLQ。
  - 改动：`ConfigValidator` 注入 `TransformEngine`（已核对无循环依赖）
    并在 `validate()` 里调 `validateJslt`。编译失败报 `ERROR/field=jslt`；
    其它 `RuntimeException` 降级 `WARN`（本类「宁漏不误」原则：不断言是
    语法错误，但也不放行）。

- **I3 部分唯一索引被误判**
  - 背景：`INDEXES_SQL` 只筛 `indisprimary OR indisunique`，**没排除
    `indpred IS NOT NULL`**。所以 `CREATE UNIQUE INDEX ... WHERE deleted = false`
    会被当成普通唯一索引放行；而 MERGE 的 ON 条件不带该谓词，谓词外的行会
    同时匹配 → SQLSTATE 21000 → 整块进二分隔离。
  - 改动：SQL 多取 `is_partial`，结果分流到 `uniqueIndexes` /
    `partialUniqueIndexes`；`TargetTableMeta` 增加该组件与
    `hasPartialUniqueIndexOn`（保留 4 参构造兼容旧调用方）；
    校验器对「只有部分索引」给出**指名道姓**的提示。
  - 为什么不能只说「没有唯一索引」：用户明明建了索引，那样说他只会以为
    索引没建成功，反复折腾。

- **I4 schema / 表名拼进 SQL 文本**
  - 背景：写入侧只 trim/lowercase（`table_name` 甚至只 trim），而这两个值
    被**直接拼进 MERGE 的 SQL 文本**。`public;DROP` 能存进 `cfg_target`。
    `IntrospectionService.requireIdentifying` 的注释早说明了这个位置的危险，
    但它只覆盖探查入参，不覆盖落库路径。
  - 改动：新增 `common/sql/Identifiers`（`^[A-Za-z_][A-Za-z0-9_$]*$`，≤63）；
    `ConfigAdminService.requireTargetFields` 与 `MergeSqlBuilder.build`
    双端调用。后者抛 `IllegalArgumentException` 而非 `ProcessingException`：
    走到那里说明配置本不该存在（存量脏数据），不该被当成消息处理失败去重试。
  - 错误信息不回显原值全文 —— 可能是精心构造的注入串，回显没有排障价值。

- **I5 poolConfig 是裸 Object**
  - 背景：拼错的键（`poolconfig` / `maxPoolSize`）能一路通过接口，直到运行期
    `applyPoolConfig` 一句 warn 忽略。还顺带一个改不回去的死角：
    `updateDatasource` 判断 `poolConfig != null` 才赋值，而空对象与「没配置」
    等价 —— 用户没有办法把参数改回 Hikari 默认值。
  - 改动：新增 `PoolConfig` record（字段全可空，compact 构造器做范围校验，
    下限对齐 Hikari：connectionTimeout≥250ms、idleTimeout≥10s 或 0、
    maxLifetime≥30s 或 0）；`PoolConfig.parse(Object)` 先自己查未知键并列出
    可选键名，再走 Jackson 2 转 record；`toPoolJson` **只写用户显式给出的键**
    （直接序列化 record 会把 null 也写出来，运行期再逐个
    `parseInt("null")` warn 一遍）；传 `{}` 即清空。
  - **DTO 里仍保持 Object 而不是声明成 PoolConfig**：Web 层是 Jackson 3，
    声明强类型后未知键会在**反序列化阶段**失败，落到
    `HttpMessageNotReadableException` 通用分支，提示只剩一句
    「请求体不是合法 JSON」—— 对「键名拼错」毫无帮助。这一版是先写成强类型
    再退回来的。
  - `applyPoolConfig` 原样保留，作为存量脏数据的最后一道防线。

- **I6 `updateTimeField` 把错误拖到 publish**
  - 背景：缺省成 `update_time`，目标表没有该列时**创建静默成功**，
    几十分钟后 publish 才报错，中间用户以为配好了。Upsert Key 拼错同理。
  - 改动：新增 `requireUsableTargetTable`，`createTarget`/`updateTarget`
    都调用，读一次 `metadataReader.get`（有缓存）并**列出可用列名**
    （只报「列不存在」用户得自己去查表结构，往往来回好几次）。
  - 读不到表结构时**不报错**（log.debug 后放过）：目标库可能暂时连不上，
    那不属于「用户填错了」，拦下来只会让人卡在创建这一步。

- **I7 状态列无 CHECK**（已在 V2 迁移内交付，见上一条记录）。

- **I8 Flyway baseline-on-migrate**
  - 背景：`baseline-on-migrate: true`。指向「有表但没有
    `flyway_schema_history`」的库时，Flyway 把现状 baseline 成 V1 而
    **不执行** V1__init_schema.sql —— 与 AGENTS.md 记的「Flyway 静默不建表」
    同一类失败。
  - 改动：新增 `application-prod.yml.example`（`.example` 后缀不参与默认启动），
    `baseline-on-migrate: false` + `baseline-version: 0`；主配置保留 true
    并补注释说明生产请用样例。
  - **被测试纠正的判断**：第一版测试固定 `baselineVersion("0")`，
    结果 V1 照常执行、断言失败。原因是 Flyway 在 baseline 时把现状记成
    该版本号，**只执行比它新的迁移**，默认值是 1 —— 默认配置下 V1 恰好在
    被跳过的那一侧。改成不设（=1）才复现出跳过；`0` 则作为双保险保留在样例里
    （万一有人误开 baseline，V1 仍会执行），并单独写了一条用例钉住这个差异。

- **影响与回滚**
  - I1/I4/I6 **会让以前能保存的配置被拒**（超长名称、带引号/空格的标识符、
    不存在的 update_time 列）—— 这正是目的，需在使用说明里提示。
  - I3 **会让某些「以前能发布」的配置失败**：只建了部分唯一索引的那些。
    它们本来就该在运行期炸，现在提前到发布。
  - I5 会拒绝拼错键名的存量配置（那些键本来也没生效，改正后行为只更符合预期）。
  - I8 只动配置样例，不改默认值，本地开发流程不受影响。
  - 全部不触及 ACK 与 MERGE 写入链路，§6 红线不受影响。
  - 回滚：I1 去掉注解与 `@Valid`；I2 删掉 `validateJslt` 调用；
    I3 恢复 `INDEXES_SQL` 与 `hasUniqueIndexOn`；I4 删掉两处 `Identifiers` 调用；
    I5 恢复 `JsonNodes.toJsonNode(req.poolConfig())`；I6 删掉
    `requireUsableTargetTable` 调用；I8 删除样例文件。

- **验证汇总**：`mvn test` 94 passed（H 收尾 83 → I 新增 11：
  IdentifiersTest 3、FlywayBaselineTest 2、GlobalExceptionHandlerTest 增 1、
  ConfigAdminTest 增 5）。`pnpm build` 未受 I 的影响（无前端改动）。

---

## 2026-10-21 · 里程碑 H 全部交付（H1–H7 可观测性与自愈）

七条的共同主题是「注释/接口承诺了某种兜底，但兜底其实不存在」。
逐条记录，含踩过的坑。

- **H1 注册表周期性对账**
  - 背景：`ConfigRefreshListener` 的 catch 里写着「下一次 refreshAll 会纠正」，
    但运行期**没有任何周期性刷新** —— `refreshAll()` 只在 `@PostConstruct`
    与 `ApplicationReadyEvent` 调过。一次 DB 抖动后内存注册表会停在旧状态直到重启。
  - 改动：`DbConfigRegistry#reconcile`，只跑一条
    `SELECT id, active_version FROM cfg_route WHERE status='ACTIVE'` 做比对，
    仅在不一致时 refresh；库里已不 ACTIVE 而内存还挂着的也摘掉。
    刻意不调 `refreshAll`（它要对每条路由再读 version 与 target）。
  - 开关拆两层：`@Scheduled` 挂在 `scheduledReconcile()` 上受
    `mqs-pg.registry.reconcile-enabled` 控制，`reconcile()` 永远可手动调用。
    **这个拆分是被测试逼出来的**：第一版把开关放进 `reconcile()`，
    测试里手动触发也被挡掉，跑出来的是「开关生效」而不是「对账有效」。
  - 关闭用 `reconcile-enabled=false` 而不是把间隔设 0 —— Spring 的 `fixedDelay`
    要求正数，设 0 会在启动阶段抛异常，而不是安静地不跑。
  - 集成测试基类默认关掉它：后台线程改内存注册表会与
    「手工改库再断言注册表状态」的用例竞态。

- **H2 健康探针改读内存缓存**
  - 背景：`MqsPgHealthIndicator` 直接调 `retryService.summary()`（一次 `GROUP BY status`），
    而 K8s 每 5–10s 探一次 `/actuator/health` —— 等于把聚合查询挂到探针频率上；
    DB 不可用时 health 还会跟着变慢。而 `RetryService` 本来就有 15s 刷新的内存 gauge。
  - 改动：`cachedSummary(Duration staleAfter)` 只读 `backlogGauges`，返回计数 +
    `stale` + `refreshedAt`；**过期只标注 stale，不判 DOWN** ——
    探针要回答「进程能否服务」，不是「指标是否新鲜」，判 DOWN 会让一次刷新失败
    直接导致实例被摘。
  - 验证：`MqsPgHealthIndicatorTest` 用 `verify(retryService, never()).summary()` 咬住。

- **H3 `/api/pg/health` 改为聚合视图**
  - 背景：这张卡叫「PG 健康」，运维理解为**目标库**，实际探的是配置库 ——
    目标库挂了它仍显示正常；且与 `PgHealthGate` 完全脱节；还把
    `e.getMessage()` 回吐前端（可能带连接串）。
  - 改动：返回 `configDb` + `targets`（各数据源闸门）+ `healthy`；
    失败原因固定文案，细节只进日志。仍用 `ApiResponse.ok` 包装 ——
    这是「探测结果」而非「接口失败」，配置库挂掉时接口本身也要能返回结构。
  - 前端 `describePgHealth()` 同步：两个库都说清楚；闸门全关时列出具体
    `datasourceId`；样本为空时显示「目标库暂无写入样本」而不是「全部正常」。

- **H4 异常映射**
  - 背景：兜底分支返回 `e.getMessage()`（可能含 SQL/连接串/类名）；约束冲突
    连约束名与 Key 值一起回吐；所有 `ProcessingException` 一律 400，而
    `CONFIG_MISSING` 严重度是 FATAL；缺非法 JSON 与路径参数类型的分支 → 500。
  - **先拆码再映射，顺序不能反**：`CONFIG_MISSING` 被当成万能入参错误用了 38 处，
    直接按严重度映射成 503 会让所有表单校验变成 503。
    新增 `NOT_FOUND` / `INVALID_INPUT`（均 DATA），只迁移**控制台入参路径**；
    消费侧（`TargetMetadataReader`、`TargetTableMeta`、`ReplayOutcome`、
    `ConfigRefreshListener`）保持 `CONFIG_MISSING`，那里的 FATAL 语义是对的。
  - 已核对 `loadSnapshot` 改码不改变重放语义：`RetryReplayer.replay` 对
    `registry.get` 抛出的任何 `RuntimeException` 都走「不确定 → 二次确认」。
  - 处理器一律 `ResponseEntity` + 12 位 `traceId`；5xx 记 error、其余记 warn；
    新增非法 JSON / 类型错误 / 缺参数三个 400 分支；约束冲突改为
    「数据冲突，请刷新后重试」。
  - `RetryController` 三个接口任务不存在时原来 `return ApiResponse.fail(...)`
    HTTP 仍 200，改为抛 `NOT_FOUND` → 404。
  - **踩坑**：Spring Framework 7 移除了 `ResponseEntity#getStatus()`，只有
    `getStatusCode()`，且返回 `HttpStatusCode` 而非 `HttpStatus` ——
    断言要比 `.value()`，否则 JUnit 报「expected: <400> but was: <400 BAD_REQUEST>」
    这种看着像通过的错误。

- **H5 目标数据源缺失时的边界**
  - 背景：一条隐蔽故障链 —— `datasourceId()` 返回 null → `PgHealthGate` 内部
    `ConcurrentHashMap.computeIfAbsent` 抛 NPE → NPE 发生在 `tryRecover` 的
    **catch 块内**、逃出去被消费循环最外层的 `catch (Throwable)` 接住 →
    **消费线程永久退出**，只留一条 ERROR。概率低，后果是「静默停一个路由」。
  - 改动：`datasourceId()` 返回原始 `long`，未知用 `-1`（闸门对未知默认放行）；
    `PgHealthGate` 全部签名收紧为 `long`，null 编译期就传不进来；
    `BatchManager` 同一处拆箱风险一并兜住。
  - `ConsumerManager.startRoute` 拿不到配置或数据源时**不提交消费者**，
    只置 PAUSED + 可读原因，交给 H1 的对账重新拉起；删掉那个
    `new JdbcTemplate()`（无 DataSource）—— 它是「必然失败的探测器」，
    每次 tryRecover 都抛异常，闸门永远恢复不了、线程还白占着。

- **H6 关键失败路径的日志**
  - 发布校验失败只记「问题数」→ 补前 5 条 `field: message`；
  - 指标刷新失败是 `log.debug`（生产 INFO 下等于静默）→ 提到 warn 并计入
    `mqspg.metrics.refresh.failed`；
  - AFTER_COMMIT 监听器整段一个 try、日志只有 routeId 与 change →
    拆成 `refreshThen(routeId, change, action)`，refresh 单独捕获且
    **失败就不再动消费者**（否则拉起一个注定空转的线程），消费者动作再单独捕获。
  - **踩坑**：`refreshThen` 第三个参数写成 `Runnable`，但
    `consumerManager.restart/ensureStarted` 返回值不是 void，lambda 里
    `return null` 报「lambda 表达式的返回类型不兼容」，改成表达式 lambda。

- **H7 liveness / readiness 分组**
  - 背景：只有单一 `/actuator/health`，而 `mqsPg` 把「闸门关闭」「积压超限」
    都算进同一个状态。K8s 拿它当 liveness 时，一次 PG 抖动就会重启整个 Pod ——
    重启修不好 PG，反而丢掉内存缓冲、放大故障。
  - 改动：`liveness: ping,diskSpace`（进程存活）、`readiness: db,mqsPg`（能否接流量）。
    不涉及端点暴露面收敛，不与已排除的鉴权工作绑定。
  - **踩坑**：Boot 4 的 `HealthEndpoint#healthForPath` 返回 `HealthDescriptor`
    而非 `Health`，取 components 要转 `CompositeHealthDescriptor` 用 `getComponents()`。

- **影响与回滚**
  - 全部为可观测性、失败隔离与边界收紧，不触及 ACK 与 MERGE 写入链路，§6 红线不受影响。
  - H5 改变了「数据源不可用时也提交消费者」的行为（现在不提交），
    已确认没有测试依赖旧行为。
  - H4 会改变前端看到的提示文案，但前端只按 `err.code` 分支（K3 已就位），
    不依赖中文子串。
  - 回滚：H1 关 `reconcile-enabled`；H2 恢复调 `summary()`；H3 恢复旧响应体（需同步前端）；
    H4 把 `NOT_FOUND`/`INVALID_INPUT` 换回 `CONFIG_MISSING` 并恢复单一 400 映射；
    H5 恢复空 `JdbcTemplate` 兜底；H7 删掉 `group` 配置。

- **验证汇总**：`mvn test` 83 passed（G 收尾时 65 → H 新增
  MqsPgHealthIndicatorTest 4、GlobalExceptionHandlerTest 5、HealthGroupsTest 2、
  PgHealthGateTest 2、ConfigRefreshListenerTest 4，加 ConfigRegistryTest 增 1）。
  `pnpm build` 通过。

---

## 2026-10-20 · G5 / G6 / G7 运行状态行、连接池失效时机、注册表生命周期

三条都属「内存与库不同步」这一类，症状都很隐蔽，合在一起记。

- **G5 运行状态行改为 upsert，bind_version 跟得上实际生效版本**
  - 背景：`ensureConsumerState` 是「`selectById` 判空 → `insert`」，
    并发下两个请求都看到「不存在」然后各插一行，后者撞 `rt_consumer_state` 主键，
    异常变成一句数据库原文。而且 `bind_version` 只在首次插入时写，
    之后激活新版本永远不动 —— 字段名与内容长期不一致。
  - 改动：`RtConsumerStateMapper#upsertBindVersion`
    （`INSERT ... ON CONFLICT (route_id) DO UPDATE SET bind_version = EXCLUDED.bind_version`）；
    `publishVersion` 不再调 `ensureConsumerState`（发布不改变运行态，只有 activate 决定跑哪一版）。
    字段语义在 javadoc 里定死：**最近一次状态变更时所处的 active 版本**，
    不是「消息绑定版本」。
  - 验证：`ConfigAdminTest.activateUpdatesBindVersion` —— 激活 v1 后为 1，再激活 v2 后为 2。

- **G6 数据源变更后等到提交再关闭旧连接池**
  - 背景：`updateDatasource` / `deleteDatasource` 在**事务内**调
    `dataSources.invalidate(id)`，而它直接 `ds.close()`。事务未提交就关池，
    正在该池上跑 MERGE 的消费线程收到连接中断 —— 消息不丢，但会产生一批本可避免的
    `PG_CONNECTION_ERROR` 与闸门抖动；若事务回滚，池已销毁而配置没变，还得等惰性重建。
  - 改动：新增 `DatasourceChangedEvent` + `DatasourceRefreshListener`
    （`AFTER_COMMIT` → `invalidate`）。刻意不复用 `ConfigChangedEvent`：
    那个动的是「注册表 + 消费者」，这个动的是连接池。
    `ConfigAdminService` 去掉不再使用的 `TargetDataSourceRegistry` 字段。
  - 验证：`mvn test` 全绿；`ConfigAdminTest` 的数据源增改删用例行为不变
    （失效时机变了，但对测试可见的结果没变）。

- **G7 注册表校验路由状态，删除版本后驱逐内存快照**
  - 背景：`DbConfigRegistry#loadActive` 不判 `route.status`。
    `refreshAll` 过滤了 ACTIVE，但 `refresh(routeId)` 是事件驱动的 ——
    路由刚被停用时注册表仍持有它的快照，`activeVersion()` 照样有值，
    而消费者已被停掉：**注册表认为在跑、实际没在跑**。
    另外 `snapshots` 只增不删（这是 PRD §19 要求的），但「版本已被删除」
    与「版本还在、只是不常用」是两件事，前者留着会让重放时而有配置时而没有。
  - 改动：`loadActive` 增加状态判断；新增 `ConfigRegistry#evict(routeId, version)`，
    `deleteVersion` 提交后由 AFTER_COMMIT 监听器调用
    （新增 `ConfigChangedEvent.Change.VERSION_DELETED`，带版本号 ——
    这是 record 加字段而不是复用 `ROUTE_REMOVED` 的原因：
    后者会停掉消费者，而删一个非生效版本不该动消费者）。
  - 验证：新增 `ConfigRegistryTest` 两条。`deletingVersionEvictsSnapshot`
    先断言「激活后快照确实在缓存里」再断言「删除后为 null」——
    不先断言存在的话，测的是「本来就没有」。`deactivatedRouteDropsOutOfRegistry`
    把路由置 INACTIVE 后 `refresh`，断言 `active()` 为 null 且不在 `activeRouteIds()` 里。
  - `mvn test`：65 passed（62 + 3 条）。

- **影响与回滚**
  - 三条都只改内存/状态行的更新时机与条件，不触及 ACK 与 MERGE 写入链路，§6 红线不受影响。
  - G7 的 `evict` 只删非生效版本的快照，生效版本的删除在 `deleteVersion` 里就被拦住了。
  - 回滚：G5 恢复 `selectById` + `insert`；G6 把两处改回直接 `invalidate`；
    G7 去掉状态判断与 `evict` 调用（可保留接口，未使用即无副作用）。

---

## 2026-10-20 · V2 迁移：草稿唯一索引 + 四个状态 CHECK + 错误流水索引（G4 / I7 / J6）

- **背景**
  - **G4**：「每个路由至多一个草稿」只有应用层约束（`findDraft` 判空后插入）。
    并发 `saveDraft` / `createVersion` 都能通过判空、各插一行 DRAFT，
    此后 `getDraft` 命中哪一行不确定 —— 页面会反复丢失刚编辑的内容。
  - **I7**：四个状态列（`cfg_route.status` / `cfg_version.status` /
    `rt_consumer_state.status` / `rt_retry_task.status`）都没有 CHECK。拼错的状态值不报错，
    只让按 status 过滤的查询静默漏掉该行（注册表加载、消费者调度都按 status 过滤），
    表现为「路由配了但不消费」—— 本项目最怕的静默失败类型。
  - **J6**：控制台查错误流水是「`is_final` / `error_stage` / `message_id` + `ORDER BY id DESC`」，
    而 V1 只建了 `(route_id) WHERE is_final`（前缀形状不匹配，用不上）与 `(message_id)`。

- **改动**
  - 新增 `V2__constraints_and_indexes.sql`（**刻意不改 V1**：校验和一变，
    已经在跑的库会直接拒绝启动）。三个任务按 §4 的合并策略放进同一个迁移。
  - G4：先用 `row_number() OVER (PARTITION BY route_id ORDER BY version DESC)` 把存量
    重复草稿清理成一份 —— 保留版本号最大的，其余降级为 `INACTIVE` 并在 `change_note`
    留痕（不直接删，那是在删用户的编辑）；再建部分唯一索引 `uk_version_draft`。
  - I7：四个 `CHECK (status IN (...))`，用 `NOT VALID` + `VALIDATE` 两步 ——
    先加约束只对之后的写入生效，再单独校验存量，失败信息能指向具体步骤。
  - J6：补 `(is_final, id DESC)` 与 `(error_stage, id DESC)`。

- **验证**
  - 新增 `SchemaConstraintsTest`（4 条，直接用裸 SQL 打库 —— 服务层本来就写不错，
    用服务调用测不出约束）：路由状态拼错被 `ck_cfg_route_status` 拒绝；
    版本/重试任务/消费状态三列同样被拒；同路由两个 DRAFT 撞 `uk_version_draft`
    而多个非 DRAFT 可以共存；两个新索引存在。
  - `mvn test`：62 passed（58 + 4），Flyway 在 Testcontainers 空库上顺序执行 V1→V2 通过。
  - 期间踩到两次，都记在这里：① `cfg_version` **没有** `updated_at` 列（V1 里只有
    `created_at` / `published_at`），迁移里的 `SET updated_at = now()` 直接让 51 个用例失败；
    ② `UPDATE` 影响 0 行时**不会**触发 CHECK，所以约束用例必须自建能命中的行，
    否则测出来的是「什么也没发生」—— 第一版就写成 `WHERE id = -1` 了。

- **影响与回滚**
  - 迁移是向前兼容的（只加约束与索引，不改列）。存量库若存在 CHECK 集合外的状态值，
    `VALIDATE CONSTRAINT` 会失败并指名是哪张表 —— 这是刻意的：宁可迁移失败，
    也不要让脏状态继续静默存在。
  - 部分唯一索引会让并发的第二次建草稿得到 23505；`insertVersion` 已把它翻成
    「该路由已有草稿版本（并发创建），请刷新后再试」，而不是「版本号冲突」。
  - 回滚：`DROP INDEX uk_version_draft; ALTER TABLE ... DROP CONSTRAINT ck_*;
    DROP INDEX idx_error_final_id, idx_error_stage_id;`（建议写成 V3 而不是删 V2 文件）。

---

## 2026-10-20 · G2 发布/保存的状态更新不加条件，会互相覆盖

- **背景**
  - `publishVersion` 先读版本、按读到的 `content` 校验，最后按 id 无条件写 `PUBLISHED`；
    `saveDraft` 用 `updateById` 无条件把状态写成 `DRAFT`。
  - 两种后果：① `activate` 之后一次迟到的 `publish` 会把现役版本从 `ACTIVE`
    改回 `PUBLISHED`，于是「现役版本状态不是 ACTIVE」，`deleteVersion` 的 ACTIVE 保护失效；
    ② 校验与更新之间可被并发 `saveDraft` 改写 content，
    等于**把没校验过的内容标成已发布**。

- **改动**
  - `CfgVersionMapper#lockForUpdate`：按 (routeId, version) `SELECT id, status ... FOR UPDATE`。
  - `publishVersion`：先加行锁确认状态与 id，再用 `requireVersion` 取带 content 的完整实体
    （JSONB 必须走实体上的 `JsonbTypeHandler`），校验通过后按 `eq(id).eq(status)` 条件更新
    并断言影响行数 = 1；非 1 行抛 `CONFLICT`。
  - `saveDraft`：先用一条 `eq(id).eq(status)` 的 UPDATE 锁住该行并确认状态没被并发改掉，
    再用 `updateById` 单独写 content（JSONB 仍然只能走实体）。
  - `CONFLICT` 错误码复用 G3 引入的那个（语义就是「当前状态下不允许该操作」）。

- **验证**
  - 新增 `publishCannotDowngradeActiveVersion`：v1 激活后再 publish 被拒绝，
    且状态仍为 ACTIVE。
  - 新增 `concurrentPublishAndSaveDraftKeepsValidatedContent`：并发 publish + saveDraft，
    断言「若最终是 PUBLISHED，其 change_note 必不是 saveDraft 写的那个」。
  - `mvn test`：58 passed（56 + 2 条）。
  - **实现中踩到的坑**：`lockForUpdate` 最初写成 `SELECT *`，JSONB 的 `content`
    在注解查询的自动映射下取到 null，导致 publish 报「内容为空」，
    并连带让 6 个既有用例（激活、回滚、并发激活…）一起变红。
    注解查询不走实体的 `JsonbTypeHandler` —— 这条已写进 mapper 的 javadoc。

- **影响与回滚**
  - 写路径变「串行一点」：同一版本的 publish 与 saveDraft 现在会互相排队，
    并发时后者拿到 `CONFLICT` 而非静默覆盖。
  - 回滚：去掉 `lockForUpdate` 与两处 `eq(status)` 条件。

---

## 2026-10-20 · G3 版本删除后重试任务永久卡死（补终态出口）

- **背景**
  - 重放按**任务绑定的版本**取配置（`RetryReplayer`）。取不到时返回 `configMissing`，
    调度器把它归入「系统级问题」交给 `releaseForRetry` —— 而那个方法**不递增 attempt**，
    只把 `next_retry_at` 往后推 `3 × pollInterval`。
  - 于是删掉一个还有重试任务绑定的版本后：任务每轮都「暂时失败」，永远不消耗次数、
    永远进不了 DLQ，`rt_retry_task` 只增不减，且每分钟刷一条 warn。
    这是本轮唯一「稳定泄漏资源」的缺陷。

- **改动**
  - `ReplayOutcome.Kind` 拆出 `CONFIG_GONE`（永久）与保留的 `CONFIG_MISSING`（临时）。
  - `RetryReplayer` 增「二次确认」：注册表拿不到快照时，直接查一次 `cfg_version`
    确认这个版本号是不是真的没了。查询本身抛异常一律按「不确定」处理（走 `CONFIG_MISSING`）——
    宁可多等几轮，也不在数据库抖动时把消息误推进 DLQ。这条是 §6 之外最容易做错的地方。
  - 新增 `ErrorCode.CONFIG_GONE`（DATA 级）：不回退 MQ 重投、不暂停消费，
    只决定这条重试任务能撑几轮。
  - `RetryProperties.configGoneBuffer`（默认 3）：允许缓冲几轮等运维恢复版本，超过才进 DLQ。
    不是 0 是因为运维可能正要重新发布同一版本号，直接丢消息比多留几分钟更糟。
  - 缓冲计数写在 `error_message` 前缀 `[configGone=N]`，不新增列 ——
    它纯属运行时诊断信息，加列要走迁移，而这个字段本来就只给人看。
  - `RetryService.holdForConfig` 负责写前缀；`markGoneTerminal` 是「不消耗次数」的终态入口
    （原 `markExhausted` 会把 `attempt` 写成 `maxAttempt`，让人误以为试满 10 次才放弃）。
  - `deleteVersion` 增加预检：还有未完成重试任务绑在该版本上时抛 `CONFLICT`，
    `force=true` 才放行；`RouteController` 的 DELETE 增 `force` 参数，前端补二次确认。
  - 前端 `api/routes.js` 的 `deleteVersion` 增 `force`；`routes/index.vue` 改判 `CONFLICT`。

- **验证**
  - `RetrySchedulerTest.missingConfigEventuallyGoesToDlq`（新增）：连跑 4 轮，
    前 3 轮断言仍 PENDING，第 4 轮断言 DLQ 且 `rt_error_record` 有一条 `is_final=true`，
    同时断言 `attempt` 未被改写。
  - `missingConfigDoesNotConsumeAttempt`（改写）：断言第一轮记下 `[configGone=1]` 前缀。
  - `ConfigAdminTest.deleteVersionBlockedWhenRetryTasksBound`（新增）：有绑定任务时
    抛 `CONFLICT`，`force=true` 放行。
  - 实现中两次被这两个用例抓住：
    ① `substring(CONFIG_GONE_PREFIX.length() + 1, end)` 切出空串 → 计数恒为 0 →
    任务永远进不了 DLQ（症状与被修的缺陷一模一样）；② `markExhausted` 把 attempt 写成 10。
  - `mvn test`：56 passed（54 + 本次 2 条）；`pnpm build` 通过。

- **影响与回滚**
  - 「配置缺失」的语义边界变清晰：临时抖动仍无限缓冲，确认版本没了才有限缓冲。
    代价是每次配置缺失多一次单行查询 —— 只在失败路径上，可忽略。
  - 删版本从「静默让任务烂掉」变成「先问一次」，与路由删除的 force 语义一致。
  - 回滚：`RetryScheduler` 的 `CONFIG_GONE` 分支并回 `CONFIG_MISSING → releaseForRetry`，
    去掉 `deleteVersion` 的预检与 `force` 参数。

---

## 2026-10-20 · K1 前端字段契约：补「本次启动以来」计数，枚举改由 /api/meta 下发

- **背景**
  - 仪表盘的「已接收 / 已 ACK / 未 ACK / 批次」四列一直渲染成空：后端
    `/api/consumer/state` 只返回 `route_id` 与状态，前端却按 `receivedTotal` 等字段读取。
    这类错位不会报错，只会让运维以为「跑了一整天，一条都没收到」。
  - 错误页的 `stage` 下拉、各处状态色（`RUNNING`/`BACKOFF`/`STOPPED`…）都在前端硬编码，
    后端加一个 `ErrorStage` 就得同步改前端；其中 `BACKOFF` 与 `STOPPED` 两个状态
    **后端根本不存在**，永远不会命中，等于两个死分支。
  - PG 健康卡只有 `true`/`false` 两态：请求被后端拒绝（比如配置库连不上）时也走 `false`，
    页面显示「异常」，无法区分「探到了故障」和「压根没探成」。

- **改动**
  - `ConsoleController#consumerStates`：注入 `ConsumerManager`，对每条路由取活着的消费者，
    补齐 `receivedTotal` / `ackedTotal` / `failedTotal` / `batchTotal` / `pauseReason`。
    **没有在跑的消费者时这些字段返回 `null` 而不是 `0`** —— `0` 会被读成
    「消费者在跑但没收到消息」，`null` 才能表达「本次启动以来没有这个消费者」，页面显示 `-`。
    javadoc 里写明这是**内存计数、随进程重启归零**，不是历史累计。
  - 新增 `MetaController`：`GET /api/meta/error-stages` 返回 `{stages, codeStages}`，
    `GET /api/meta/statuses` 返回 `consumerStates` 与 `retryStatuses`，值全部取自
    `ErrorStage` / `ErrorCode` / `ConsumerStateRepository` 的枚举常量，不再有第二份清单。
  - 前端 `api/meta.js` 对应两个方法；`errors/index.vue` 的 `stages` 从 `ref([...])` 改成
    启动时拉取，拉取失败退化为空列表（页面仍可用，不阻塞其它查询）。
  - `dashboard/index.vue`：`pgHealthy` 改为三态（`null` = 未知），`pgHealth` computed 返回
    `{type, label, detail}`，未知用 `info` 色；`stateTagType` 去掉两个不存在的状态，
    改成 `RUNNING→success`、`PAUSED|RECOVERING→warning`、`ERROR→danger`、其余 `info`。
    表格列改为 路由 ID / 状态 / 已接收 / 已 ACK / 未 ACK / 绑定版本 / 最近成功 / 最近错误 / 更新时间，
    全部 `?? '-'`。页脚加一行说明，明确计数是内存值。
  - `api/retry.js` 新增 `retrySummaryByState()`，**在 API 层**把 key 统一转小写：
    原来页面各处 `summary.value.PENDING ?? summary.value.pending` 各写各的。
  - `errors/index.vue` 增 `onFilterChange()`：筛选变化时重置 `page = 1` 再查，
    否则在第 3 页上改筛选会看到空表。绑到两个下拉、回车与查询按钮。

- **验证**
  - `mvn test`：54 passed（K1 只动查询侧，未触及写入与 ACK 链路）。
  - `pnpm build`：`✓ built`，exit 0。
  - 手工：仪表盘三列有值；后端拒绝时健康卡显示「未知」（`info` 灰）而非「异常」。
  - 未验证：真实 MQ 下的计数递增（本机只跑 mock broker，计数值靠集成测试的
    `ConsumerManager` 状态断言间接覆盖）。

- **影响与回滚**
  - 只加字段与枚举接口，未改任何既有字段的名字或语义，前端旧代码不会被这次提交打断。
  - `null` vs `0` 的取舍会让「消费者不存在」在页面上显示 `-`，
    如果有下游脚本依赖这三个字段为数字，需要按 `null` 判空。
  - 回滚：还原 `ConsoleController#consumerStates`、删除 `MetaController` 与 `api/meta.js`，
    前端两个页面恢复硬编码 —— 无数据迁移。

---

## 2026-10-20 · K3 确认框取消不再抛未处理拒绝，错误码不再靠中文子串传递

- **背景**
  - 全仓 7 处 `await ElMessageBox.confirm(...)` 都是裸调。用户点「取消」时它以 reject
    结束，而调用方都在 `async` 点击处理器里直接 `await` —— 每次取消都在控制台记一条
    unhandled rejection，更麻烦的是「用户取消」与「真的失败」混进同一个 `catch`。
  - 删除路由时若存在未完成的重试任务，后端拒绝并要求二次确认，而前端用
    `String(e?.message).includes('重试任务')` 判断。后端那句提示文案一改
    （`ConfigAdminService.deleteRoute`），「仍然删除」的流程就静默失效 —— 表现为点了没反应，
    且没有任何报错。

- **改动**
  - 新增 `utils/confirm.js`：`confirmAction(message, title, options)` 内部吃掉取消，
    明确返回 `true`/`false`，调用方只写 `if (!(await confirmAction(...))) return`。
    `routes` / `retry` / `datasources` 三处共 7 个确认框改用它；顺带补上 `retry` 与
    `datasources` 里原本缺失的失败 `catch`（原来请求失败会一路冒泡）。
  - 新增 `ErrorCode.CONFLICT`（`ErrorSeverity.DATA`），`deleteRoute` 的前置检查改抛它。
    **刻意不复用 `CONFIG_MISSING`**：那个码的严重度是 `FATAL`，
    且已被几十处「路由不存在」「名称已存在」当万能入参错误用（见 H4），
    再往里塞语义只会让后续按码映射状态码更乱。
  - `api/request.js` 的失败分支把 `body.code` 与完整信封挂到 `error` 上
    （HTTP 层失败同样挂），不再只抛一个裸 `Error`。
  - `routes/index.vue` 的强制删除分支改判 `e?.code === 'CONFLICT'`。

- **验证**
  - `pnpm build` 通过；`mvn test` 54 passed（后端只多了一个枚举常量与一处抛码）。
  - 手工：点「取消」后控制台无红字；删除有未完成重试任务的路由仍会弹「仍然删除」。

- **影响与回滚**
  - 前端错误对象多挂两个字段（`code` / `payload`），不影响既有 `message` 用法。
  - 依赖 `message` 子串判断的写法在本仓已无残留（`grep 'includes(' ...` 确认）。
  - 回滚：还原 `request.js` 与三处视图，`CONFLICT` 可留（未使用即无副作用）。

---
## 2026-10-20 · G1 并发激活留下两个 ACTIVE 版本（加行锁 + 条件更新）

- **背景**
  - `ConfigService.activate` 先读 `cfg_route.active_version` 当 `previous`，再降级 `previous`、
    把目标版本置 `ACTIVE`、写回 `active_version`，全程没有行锁。
  - 两个管理员同时点「激活」（或一个页面重试 + 一个手点）激活 v2 / v3 时，两边都读到
    `previous=1`：各自把 v1 降级、各自把自己的版本置 ACTIVE。结果 `cfg_version` 里
    **v2 与 v3 同时是 ACTIVE**，而 `cfg_route.active_version` 只保留后写者 ——
    另一个成了 `deleteVersion` 的 ACTIVE 保护看不见的幽灵版本，可被直接删掉。

- **改动**
  - `CfgRouteMapper#selectByIdForUpdate`（`SELECT ... FOR UPDATE`）：`activate` 开头先锁路由行
    再读版本。锁粒度是单条路由，不同路由的激活互不阻塞。
  - 降级语句加 `status='ACTIVE'` 条件：兜住历史脏数据，只降级真正在 ACTIVE 的那一行，
    不误伤已发布但从未激活的版本。
  - 激活语句加 `status=<读到的状态>` 条件并校验影响行数；非 1 行抛
    「版本状态已被并发修改，请刷新后重试」，而不是静默覆盖别人的状态。
  - `rollback` 的同类窗口一并修掉：原来先 `selectList` 挑候选、再交给 `activate`，
    两步之间候选可能被删或改状态，最终报「配置版本不存在」（看着像用户点错了版本）。
    候选查询挪进同一把行锁内。
  - `ensureConsumerState` 的读后写竞态：并发激活可能都读到「不存在」然后各自 insert，
    后者主键冲突会把整个激活打挂。改为吞掉 `DuplicateKeyException` ——
    状态行是否存在不影响激活本身的正确性。

- **验证**
  - 新增 `ConfigAdminTest.concurrentActivateKeepsSingleActive`：两线程同时激活 v2 / v3，
    断言 `SELECT count(*) ... status='ACTIVE'` 恒为 1，且 `active_version` 指向的那行确实是 ACTIVE。
    临时去掉 `FOR UPDATE` 该用例立刻变红（`expected: <1> but was: <2>`），确认它真的守着这个缺陷。
  - 新增 `activateRejectsStatusChangedConcurrently`：版本在读取后被改动时报错而非静默激活。
  - `mvn test`：54 passed（52 + 本次 2 条）。
  - 注：用 `Copy-Item` 还原被临时改过的源文件后，Maven 增量编译会因 mtime 倒退继续用旧 class，
    需要 `(Get-Item $p).LastWriteTime = Get-Date` 触发重编 —— 这次踩到了，写在这里备忘。

- **影响与回滚**
  - 加锁只影响写路径（激活 / 回滚），不触及 ACK 与 MERGE 写入链路，§6 的六条红线不受影响。
  - 同一路由的并发激活从「都成功但状态错乱」变成「串行成功」，第二个请求可能拿到
    「状态已被并发修改」提示 —— 这是预期的收敛行为。
  - 回滚：去掉 `selectByIdForUpdate` 与两处 `eq(status)` 条件即可，无数据迁移。

---

## 2026-10-20 · 第二轮优化方案（重写 docs/optimization-plan.md）

- **背景**
  - 第一轮方案（A–F）里的 A1、B1–B3、C1–C3、D1、D2 已交付（D3 见上一条）；
    文档的任务明细只对「还没做的部分」仍有施工价值，继续在其上追加会越来越难读。
  - 通读全仓后确认：剩余问题的主线不是性能，而是**并发写保护、重试任务生命周期、失败信息泄露**，
    与第一轮「观测先行」的顺序正好相反。

- **改动**
  - 重写 `docs/optimization-plan.md`：§3 归档第一轮交付与遗留（明确「不再重做」），
    §5 给出新的七个里程碑 —— G（正确性）/ H（可观测与自愈）/ I（校验前置）/ J（性能）/
    K（前端）/ L（工程化）/ M（集成测试补充），每条含现状证据（文件:行）、方案、验收、风险与回滚。
  - 新增 §0「结论先行」与「建议先做的三件事」：K1 前端字段契约、G1 激活并发、G3 重试断头路。
  - 新增 §9 决策记录：安全评审里的三条 P0（全站无认证、`/api/datasources/test` 任意 jdbcUrl、
    密码明文回落）按第一轮的排除范围**只记录不实施**，并给出若要做的最小方案，等确认后再排期。
  - 新增 §6 六条不可触碰红线与 §10 验证清单（含「本机 Docker 未启动时跑不了集成测试」的说明），
    §11 收纳原来散落在 §5.1/§5.2 的协作约定（`evolution.md` 记录规范、README 更新点、提交粒度）。
  - `README.md`「当前进度」：把阶段 10 拆成「第一轮已完成 / 第二轮待排期」两行，
    删除已过时的「集成测试容器化。当前测试直连本地 PostgreSQL」条目（D1 早已完成），
    用例数改为按 `@Test` 实测的 52，并补上 D2 CI。
  - `AGENTS.md`：开头的「no CI」改为「CI 跑 `mvn verify` + `pnpm build`，但仍无 lint/format/typecheck」，
    `docs/optimization-plan.md` 的说明改为「第一轮 A–F 归档于 §3，第二轮 G–M 在 §5」。

- **验证**
  - 纯文档改动，未触碰代码与配置；`mvn -B --no-transfer-progress -DskipTests compile` 仍 BUILD SUCCESS。
  - 用例数为实测计数：`Select-String '@Test'` 得 14+9+3+8+7+4+7 = 52（无 `@ParameterizedTest`、
    无 `@Disabled`、无 `@Nested`）。其中 51 是 D1/D2 那次全量跑的数字，
    第 52 个是 `b4e8495`（RawStore 停机排空）往 `RawRetentionTest` 里加的那条，
    该提交只重跑了那一个类 —— 所以 README 里写成「52 个用例，最近一次全量记录见 D1/D2（当时 51 全绿）」，
    既更正了数字，也没有声称本轮跑过全量。
  - 本轮**未**执行 `mvn test` 与 `scripts/e2e-config-api.ps1`：本机 Docker Desktop 未运行，
    Testcontainers 拉不起 `postgres:18`（见新方案 §8 第 5 条与 §10 的前置说明）——
    因此 README 里改成「最近一次全绿记录见 D1/D2 条目」，没有声称本轮跑过。

- **影响与回滚**
  - 无运行时影响，不影响构建与测试。
  - 回滚：`git checkout -- docs/optimization-plan.md`，并删除本节。

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






