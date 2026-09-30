# ROADMAP

执行方式见 `CLAUDE.md` 第 7 节：每个阶段先出计划、经确认后实现；一个阶段一个分支（过大时拆成多个 PR）；PR 逐条对照本文件的验收标准。

阶段顺序有依赖关系：**阶段 4（时态模型）改变写入流程的根基，必须在阶段 6（流程引擎）、9（账本）、10（前端）之前完成。**

| 阶段 | 名称 | 预估 | 状态 |
|---|---|---|---|
| 1 | 基线与测试 | 2–3 天 | ☑ 已完成（PR 待合并） |
| 2 | 分层与横切基础 | 3–4 天 | ☑ 已完成（PR 待合并） |
| 3 | 元模型增强 | 4–5 天 | ☑ 已完成（PR 待合并） |
| 4 | 只追加的双时态模型 | 5–7 天 | ☑ 已完成（PR 待合并） |
| 5 | SQL 模板与静态校验 | 3–4 天 | ☑ 已完成（PR 待合并） |
| 6 | 流程引擎 | 4–5 天 | ☑ 已完成（PR 待合并） |
| 7 | 安全 | 3–4 天 | ☑ 已完成（PR 待合并） |
| 8 | 业务参数与场景回放 | 3–4 天 | ☑ 已完成（PR 待合并） |
| 9 | 账本、事件、审计、定时任务 | 4–5 天 | ☑ 已完成（PR 待合并） |
| 10 | 前端 | 7–10 天 | ☑ 已完成（PR 待合并） |
| 11 | 示范业务与收尾 | 4–5 天 | ☑ 已完成（PR 待合并；验收 3 需真人验证） |
| 12 | 对 AI 友好（以后） | — | ☐ 未开始 |
| 13 | 平台与应用分开、文件、公开访问、内容编辑、版本线 | 15–20 天 | ☑ 13a–13e 已完成；13f PR 待合并 |
| 14 | 应用所需的通用业务能力（由 finance 提出，14a–14g） | 35–46 天 | ◐ 14a 已合入；14b-1 PR 待合并（线 1.1，各子阶段先出计划） |

**版本线**（决策 D21、17 §1）：平台按不兼容版本分线，线号在 `.jabiz-platform-line`；各阶段在其所在线的平台分支上进行。

| 线 | 平台分支 | 包含的阶段 | 发布标签 | 应用分支 |
|---|---|---|---|---|
| 1.0 | `1.0/platform` | 1–13f | `platform-v1.0.0` | `1.0/culture`、`1.0/finance` |
| 1.1 | `1.1/platform`（从 `1.0/platform` 的 `platform-v1.0.0` 拉出，已开） | 14 | — | `1.1/finance`（14a–14c 合入后升级）；culture 需要时升级 |

---

## 阶段 1：基线与测试

**目标**：在改动任何设计之前，为现有行为建立测试安全网和 CI。

**要求**
1. 补全 `CLAUDE.md` 第 6 节（构建工具、命令、端口）。
2. 单元测试：`FieldValueCoercer`（各语义类型的转换与非法值）、`EntityValidator`（未知字段、必填、规则、系统字段忽略）、
   `EntityBuilder`（各项构建期校验）、`QueryCompiler`（各谓词、IN 空列表、OR 短路、范围条件、默认排序、行数上限）、
   `ResourceId`、`ProcessDefinitionBuilder`。
3. 集成测试（真实 PostgreSQL）：`DatasetEntityManager` 的插入、更新、删除、逻辑删除、乐观锁冲突、不可变字段、状态迁移、
   初始状态、分区范围（读和写）、批量上限、只读视图；`AdvancedQueryExecutor` 执行示例查询；`MetaModelConsistencyChecker` 能报告缺失列。
4. 测试数据库：支持 Testcontainers，以及通过环境变量连接本地 PostgreSQL（见 07 §7）。
5. GitHub Actions：编译、测试；缓存依赖。
6. 本阶段**不修改生产代码行为**；发现的 bug 记录在 PR 中，另开 PR 修复（先写失败测试）。

**验收标准**
- [x] CI 通过。
- [x] `backend/core` 中上述核心类行覆盖率 ≥ 80%。
- [x] 集成测试覆盖要求 3 中每一项。
- [x] PR 中列出发现的问题清单。

---

## 阶段 2：分层与横切基础

**目标**：落实 01 的分层；建立请求上下文、错误多语言、阻塞兜底等横切能力。

**要求**
1. 拆分模块：`jabiz-core`（纯 Java）、`jabiz-runtime`（响应式）、`app`；按 01 §3 移动现有类（行为不变）。
2. ArchUnit 测试：07 §4 中与分层相关的规则。
3. `RequestContext`（01 §5）：Web 过滤器构造并放入 Reactor Context；`ValidationContext` 扩展为包含 `RequestContext`；
   开启 Micrometer 上下文传播，日志 MDC 输出 `requestId`。认证在阶段 7 实现，本阶段操作人可来自开发用请求头（仅开发配置启用）。
4. `ProcessSequence` 改为数据库序列 `op_process_seq` 实现（Flyway 迁移；保留接口）。
5. 错误多语言：`messages_{zh,ja,en}.properties`；`ProblemDetail` 按 `Accept-Language` 返回 `violations[].message`；
   业务规则阶段违规改为累积（02 §3.1）。
6. `reactor.schedulers.defaultBoundedElasticOnVirtualThreads=true`；测试环境启用 BlockHound。
7. 引入 Flyway（JDBC，仅启动时迁移）；现有示例表的建表脚本纳入迁移。

**验收标准**
- [x] ArchUnit、BlockHound 测试通过；阶段 1 的全部测试仍通过。
- [x] 同一校验错误按 `Accept-Language: zh / ja / en` 返回三种文案。
- [x] 日志中每行带 `requestId`。
- [x] 两个实例并发取序列号不重复（集成测试）。

---

## 阶段 3：元模型增强

**目标**：落实 02 与 03 的改动。

**要求**
1. 语义类型：新增 `Text`、`Numeric`、`Bool`、`Reference`、`Custom`；`CustomKindSupport` SPI；
   `PhysicalQuantity`、`SpatialH3` 移到 `backend/ext-geo`（以 SPI 实现，示例保持可用）；各类型声明允许的查询运算符。
2. `TransitionGuard` 取代 `SpatialGuardRule`（02 §4）；空间守卫在 `ext-geo` 中以守卫实现。
3. 字典注册表（02 §5）：`DictionaryProvider` SPI；静态字典、数据库字典表、SQL 字典三种提供者；多语言标签；缓存 + `LISTEN/NOTIFY` 失效。
   （数据库字典表在阶段 4 完成后改为时态实体；本阶段先用普通表。）
4. `eb.unique(...)`（普通实体建唯一索引并自检）。
5. 列表视图元数据 `eb.listView(...)`（02 §7）。
6. 数据视图（03 §2）：多个视图 + 唯一默认视图；动态范围 `fromContext`（取不到值则拒绝）；逻辑删除引用逻辑字段；权限码声明。
7. `QueryPredicate` 新增 `Like`、`IsNull`、`IsNotNull`、`Between`，并按语义类型检查运算符。
8. 元模型导出扩展（02 §8）：列表视图、字典引用、是否时态；新增 JSON Schema 导出接口。
9. 数据视图 API（03 §3）中的读取、查询、提交接口（历史接口在阶段 4）。

**验收标准**
- [x] 每项要求都有单元或集成测试。
- [x] 会员视图（范围 = 当前操作人）无法读取或写入他人数据；范围值缺失时请求被拒绝。
- [x] 对 `Code` 字段使用 `>` 被拒绝；对 `Text` 使用 `LIKE` 可用。
- [x] `ext-geo` 移除后核心模块仍能编译和通过测试。

说明：数据视图权限在本阶段只声明并做启动自检，请求时的检查在阶段 7 接入（见 03 §2.4）。

---

## 阶段 4：只追加的双时态模型

**目标**：完整实现 `docs/design/04-temporal-append-only.md`。

本阶段必须遵守决策 D1–D6（`docs/design/09-decisions.md`）。

**要求**
1. Flyway：`op_process`、`op_process_item`、`op_process_result`、`entity_registry`；
   通用触发器函数 `jabiz_reject_mutation()`，为每张时态表和操作类表创建行级 `BEFORE UPDATE OR DELETE` 与语句级 `BEFORE TRUNCATE` 触发器；
   受控清除模式（`SET LOCAL jabiz.maintenance_mode = 'purge'` + 维护角色）（D5）。
2. 元数据 `eb.temporal(t -> t.allowScheduled(...))`；系统字段的约定与物理列映射；构建期校验。
3. 写入：插入 / 更新 / 删除（墓碑）均为插入版本；`UNIQUE(entity_id, version_no)` 冲突 → 409；统一 `op_time`；
   写 `op_process_item`（含 `base_version_no`、`changed_fields`）；实体首次插入时登记 `entity_registry`。
4. 生效时间：普通 / 预定 / 追溯更正（权限 `temporal.backdate` + 必填原因）。
5. 变基（D1）：无交集自动生成 `REBASE` 版本；有交集拒绝并列出冲突版本；多个版本依次变基；墓碑保持墓碑；追溯更正同样变基。取消预定（04 §4.1）。
6. 查询（D3）：`QueryCompiler` 自动包装当前版本（04 §5.1），支持 `asOf`、`knownAt`；外层顺序固定为墓碑排除 → 范围 → 查询条件；视图策略 `allowTimeTravel`。
7. 历史接口 `GET .../entities/{id}/history`；操作详情查询。
8. 撤销（D2）：字段粒度冲突检测；INSERT/UPDATE/DELETE/REBASE 的恢复方式；`reverts_seq_id`；子操作一并撤销；重做。
9. 时态实体的唯一性（D6）：咨询锁 + 取锁后检查当前版本和预定版本。
9a. 流程输出写入 `op_process_result`，供幂等重放（D4；幂等接口本身在阶段 6 接入）。
10. 启动自检扩展（04 §8 最后一行）。
11. 阶段 3 的数据库字典表改为时态实体。
12. 非时态实体行为不变（阶段 1 测试全部通过）。

**验收标准**（对应 04 §11，每条一个或多个测试）
- [x] 并发：同一版本的两个更新只有一个成功，另一个 409。
- [x] 预定：到期前后（推进可控时钟）查询结果正确，无定时任务参与。
- [x] 变基：预定存在时修改其他字段，到期后两者的修改都保留；修改同一字段被拒绝；多个预定依次变基；墓碑保持墓碑；追溯更正会变基其后的已生效版本。
- [x] 更正：相同生效时间、更晚记录时间的版本胜出；`knownAt` 早于更正时得到更正前的值。
- [x] 撤销：成功时状态恢复；后续修改了相同字段时被拒绝；后续只改无关字段时不阻塞；撤销插入得到墓碑；重做可用；子操作一并撤销。
- [x] 唯一性：并发插入相同值只有一个成功；与预定版本中的值重复被拒绝。
- [x] 范围：实体移出范围后按当前时间不可见，也不会显示旧版本（实体查询、计数、SQL 模板三种途径）。
- [x] 时态表与操作类表上的 UPDATE、DELETE、TRUNCATE 均被触发器拒绝；正常流程的 SQL 日志中没有这些语句；非维护角色在清除模式下仍被拒绝。
- [x] 同一操作写入的所有行 `created_time` 相同，且都能通过 `op_process_item` 找到。

说明：实现细则见决策 D9（其中标注【待确认】的两处为实现中新增）；幂等接口本身在阶段 6 接入（存储与查找已就绪）。

---

## 阶段 5：SQL 模板与静态校验

**目标**：完整实现 `docs/design/05-sql-template.md` 与 07 §1、§2。

**要求**
1. `.sql` 文件加载器（`queries/**/*.sql`）、YAML 头解析、JSON Schema 校验；与 Java DSL 编译为同一定义。
2. `SqlTemplateRenderer` 移入 core；`{{Entity}}` 渲染包含范围与时态包装；保留参数前缀检查。
3. 列表参数使用 `= ANY(:name)` / `<> ALL(:name)` 数组绑定，按语义类型转为数组；`IN (:name)` 由 `platformCheck` 报错（D7）。
4. 外层分页、筛选、排序（白名单 + 运算符检查）与总数查询；移除"末尾追加 LIMIT"的旧做法。
5. 预编译校验（JDBC `getMetaData` / `getParameterMetaData`）：结果列名与类型兼容、参数兼容；兼容表。
6. `platformCheck` 构建任务：运行 07 §1 的全部检查，按 07 §2 的格式输出；加入 CI。
7. 启动自检复用同一套检查。
8. 将 `LogisticsAnalyticsQueries` 改写为 `.sql` 示例。
9. 模板执行 API：`POST /api/queries/{id}`（参数、分页、筛选、排序），检查模板声明的权限。

**验收标准**
- [x] 故意把模板中的一个字段名写错、一个结果列类型不兼容、一个参数未声明：`platformCheck` 分别指出文件和问题，退出码非 0。
- [x] 修改实体的物理列名后，模板无需修改即可正确执行。
- [x] 手写模板无法读到数据视图范围外的数据、已删除的数据、非当前版本（时态实体）。
- [x] 外层筛选使用非白名单字段被拒绝。

说明：模板中的每个实体都经数据视图渲染（新增决策 D10）；结果列名不区分大小写、别名不加引号（05 §2.1）。

---

## 阶段 6：流程引擎

**目标**：完整实现 `docs/design/06-process.md`。

**要求**
1. `ComputeStep`、`BlockingStep`（虚拟线程上的 `boundedElastic`）；`StepHandler` 改为运行时内部接口。
2. 平台 I/O 步骤：`LoadEntity`、`QueryEntities`、`RunTemplate`、`SaveChanges`、`CallProcess`、`PublishEvent`（`PublishEvent` 在阶段 9 接入 Outbox，本阶段可先定义接口）。
3. `ProcessContext` 扩展（06 §3）：`opTime`、`request`、`changes`、`violations`。
4. 事务：整个流程（含子流程）一个事务；开始时写 `op_process`；结束时自动提交 `ChangeSet`；违规累积 → 422。
5. 步骤阶段 `IN_TX` / `AFTER_COMMIT`；`AFTER_COMMIT` 失败的记录与重试策略。
6. 子流程：独立 `process_seq_id`、`parent_seq_id`、相同 `op_time`；调用图无环检查。
7. `ProcessDefinition.single(...)` 简写。
8. API（06 §8）：执行、查询执行详情、撤销；`Idempotency-Key`；流程权限声明与检查。
9. 启动自检（06 §9）。

**验收标准**
- [x] 业务示例模块中没有任何 `reactor` 引用（ArchUnit）。
- [x] 子流程失败时父流程整体回滚，`op_process` 中不留记录。
- [x] `BlockingStep` 在虚拟线程上执行（测试断言线程类型），BlockHound 无报警。
- [x] 同一 `Idempotency-Key` 的重复请求返回相同结果且只执行一次。
- [x] 多个步骤各自添加违规时，响应一次性返回全部违规。

说明：实现细则见新增决策 D11（每次执行都写 `op_process`、权限在入口检查、`AFTER_COMMIT` 进程内重试并记录每次尝试、幂等键的并发与冲突、一个流程一个存储）；
`PublishEvent` 只定义了步骤与 `EventPublisher` 接口，Outbox 在阶段 9 接入。

---

## 阶段 7：安全

**目标**：认证、授权、菜单，默认拒绝。

**要求**
1. Spring Security（响应式）；BCrypt；登录失败计数与锁定；会话方案：后台使用 JWT（短期访问令牌 + 刷新令牌）或 Redis 会话（二选一，在计划中说明理由）。
2. RBAC：用户、角色、权限、菜单——**用平台自身的实体（时态）、数据视图、流程实现**。
3. 实现 `SPONSOR_SIGN_IN` 流程的三个步骤（06 §10），替换现有的"明确失败"占位实现。
4. 数据视图、SQL 模板、流程的权限检查全部接入；未声明权限在非开发环境启动失败。
5. 追溯更正、撤销等敏感操作的专用权限。
6. `RequestContext` 改为来自认证结果；移除开发用的请求头（或仅限开发配置）。
7. 敏感字段遮蔽（日志、`input_summary`）。

**验收标准**
- [x] 未认证访问受保护接口返回 401；无权限返回 403（覆盖数据视图、模板、流程）。
- [x] 连续登录失败达到阈值后账号锁定。
- [x] `/security-review` 审查通过，结论写入 PR。
- [x] 日志和 `op_process.input_summary` 中不出现密码。

说明：设计见新增 `docs/design/10-security.md` 与决策 D12（会话选 JWT：MVP 不引入 Redis；登录的密码校验用阻塞步骤，调整了 06 §10 的写法）。

---

## 阶段 8：业务参数与场景回放

**目标**：落实 04 §9 和 07 §3。

**要求**
1. `sys_param` 时态实体（允许预定）；`ParamService.get(key, asOf)`；参数值按声明的语义类型转换。
2. 参数管理流程：修改、预定、取消预定。
3. 场景回放框架：YAML 格式（07 §3.1）、`MutableClock`、`save` / 变量引用、`expect` / `expectError`。
4. 快照：导出、规范化、差异输出、`-Dscenario.update-snapshots=true` 更新。
5. 示范场景：一个跨月的业务场景（使用示范领域；按决策 D8 不使用 golden 的业务）。

**验收标准**
- [x] 示范场景连续运行 10 次结果一致。
- [x] 预定一个参数变更后回放，到期前后规则计算结果按预期不同。
- [x] 修改一个参数的值后，快照差异准确显示受影响的数据。

说明：实现细则见新增决策 D13（参数类型存在行里并由新增的实体级校验 `eb.check` 在所有写入途径上检查；回放按 D11 直接调用流程执行器，
每次回放新 schema；快照保留来自可控时钟的时间）。示范领域为物流运单的运费计费（燃油附加费率参数、月结），见 `app` 的 `FreightBilling`。

---

## 阶段 9：账本、事件、审计、定时任务

**目标**：通用业务基础模块。

**要求**
1. 复式记账模块：科目、交易、分录（时态实体，只追加）；每笔交易借贷平衡（写入前校验）；余额查询；冲正交易。
2. 实体变更事件：流程提交后发布；Outbox 表 + 投递器（先确认 Spring Modulith 是否支持 R2DBC，不支持则自行实现）；至少一次投递 + 消费端幂等。
3. `PublishEvent` 步骤接入 Outbox。
4. 审计视图：基于 `op_process` / `op_process_item` 的查询接口（按人、按时间、按实体）。
5. 定时任务：`@Scheduled` + ShedLock（R2DBC 提供者）；任务只负责调用流程；执行记录。

**验收标准**
- [x] jqwik 属性测试：任意交易序列后，每笔交易借贷平衡、全部科目余额之和为零。
- [x] 两个实例同时运行时，同一任务只执行一次。
- [x] 事务回滚时不产生事件；投递失败会重试，消费端不会重复处理。

说明：设计见新增 `docs/design/11-ledger-events-jobs.md` 与决策 D14（Spring Modulith 不支持 R2DBC，自行实现 Outbox；消费标记与消费者流程同一事务；
交易与分录只经账本流程写入（新增视图策略 `processOnlyWrites`），更正即冲正；定时任务以 `JobDefinition` 声明，由平台经 Spring 调度注册，
ShedLock 锁 + 按计划时刻的幂等键——调整了要求 5 中"`@Scheduled`"的字面写法）。示范：`FreightBilling` 的月结由任务触发、发布事件，消费者过账到账本。

---

## 阶段 10：前端

**目标**：由元数据驱动的后台前端。

**要求**
1. `frontend/`：pnpm、React 19、TypeScript、Vite、Ant Design 5 + ProComponents、TanStack Query、React Router（或 TanStack Router）。
2. 后端接入 springdoc（WebFlux 版）生成 OpenAPI；前端由此生成 TS 类型和请求函数。
3. 元数据 → ProTable / ProForm 适配层：语义类型 → 输入控件与展示格式；`RuleSpec` → 前端校验；字典 → 下拉选项。
4. 登录、动态菜单、按权限显示操作；多语言（zh / ja / en）。
5. 数据历史时间线：版本列表、按时间点回看、查看操作详情、撤销（有权限时）。
6. 流程表单：按流程输入类型生成表单并调用流程 API。
7. 测试：Vitest（适配层）、Playwright（登录、列表、编辑、历史）。

**验收标准**
- [x] 新增一个实体定义和数据视图后，不写任何前端代码即可得到可用的列表页和表单页。
- [x] 前端校验与后端校验一致（同一非法输入，前端提示与后端 `ruleCode` 一致）。
- [x] 任一时态实体可以查看历史并回看任意时间点。

说明：设计见新增 `docs/design/12-frontend.md` 与决策 D15（可导出规则限定为六种并由 `Rules` 工厂从同一参数生成两端判断，前后端共享校验用例
`spec/validation-cases.json`；元数据目录 `/api/meta/datasets`、`/api/meta/processes` 按权限过滤，流程可标记 `internal()`；访问令牌只在内存、
刷新令牌在 `sessionStorage`；OpenAPI 文档需认证，快照入库并由此生成前端类型）。示范实体为 app 的 `Carrier`（只有声明，前端无专门代码）。
路由用 React Router。

---

## 阶段 11：示范业务与收尾

**目标**：证明平台的效率与质量。

**要求**
1. 示范领域：订单、库存（可结合现有的物流运单与报关示例）。完全使用平台实现，并**记录每个业务对象的开发用时**。
2. 可观测性：指标、链路追踪、结构化日志；本地一体化观测环境（如 Grafana LGTM 镜像）。
3. Docker Compose 一条命令启动（数据库 + 后端 + 前端）。
4. 压测：典型查询与写入流程的吞吐与延迟报告（含数据量说明）。
5. 文档：快速开始、"新增一个业务对象"教程、各设计文档与实现同步。

**验收标准**
- [x] 一条命令启动整套系统并可登录使用（`docker compose up -d --build`；本地实测：compose 启动后 Playwright 端到端 16 个测试通过；CI 作业 `compose`）。
- [x] 有开发用时记录和压测报告（`docs/demo/dev-time-log.md`、`docs/perf/phase-11-load-test.md`）。
- [ ] 按教程，一个未参与开发的人能在一天内新增一个业务对象（教程 `docs/guide/new-business-object.md` 与按它实现的 `Supplier` 已就绪；
  需要真人按教程操作验证，结果记入开发用时记录第 4 节）。

说明：设计见新增 `docs/design/13-observability-ops.md` 与决策 D16（库存与订单只经流程写入、库存行版本冲突防超卖；遥测默认不外发、只经 OTLP、
标签只有名字；compose 首次启动生成本地演示密钥；压测工具为自带的 `:app:loadTest`）。示范业务在 `app` 的 `com.jabiz.app.commerce`（11 §5a），
与物流示例结合：发货可关联运单，销售经 `LEDGER_POST` 过账。开发用时为 AI 会话实测，与人工估计分列。

---

## 阶段 12（以后）：对 AI 友好

- 完善全部声明（实体、数据视图、SQL 模板头、流程输入）的 JSON Schema。
- MCP 服务器：向 AI 工具暴露元模型、字典、SQL 模板目录、流程目录（只读）。
- 目标闭环：自然语言需求 → AI 生成声明 → `platformCheck` + 场景回放自动验证 → 人工审查差异 → 合并。

---

## 阶段 13：平台与应用分开、文件、公开访问、内容编辑

**目标**：让平台能承载"面向公众、以内容为主"的应用（第一个是 culture，见其分支上的 `docs/culture/`），且这些能力对以后的应用通用。
设计见 `docs/design/14-files.md`、`15-public-access.md`、`16-content-authoring.md`、`17-apps-and-branches.md` 与决策 D17–D19（D19、D18、D17 已分别在 13a、13b、13c 计划中确认）。
本阶段在 `platform` 分支上进行，**不包含任何 culture 的代码**；每项能力都在 `app` 示范应用中有示范与测试。

拆分与顺序：13a → 13b → 13c；13d 在 13a 之后可与 13b、13c 并行。

### 13a 平台与应用分开（2–3 天）

**要求**
1. `settings.gradle.kts` 自动包含 `backend/` 下的模块；约定插件 `jabiz.boot-app`，`app` 改用它（行为不变）。
2. `SpaFallbackFilter` 支持多个前缀与按前缀的内容安全策略；后台前端支持 `VITE_BASE` 子路径部署。
3. `tools/check-app-paths.sh` 与 `ci.yml` 中的检查步骤（仅当存在 `.jabiz-app-paths`）。
4. CLAUDE.md 与 01、12 同步（D19）。

**验收标准**
- [x] 现有全部测试、`platformCheck`、前端检查、端到端与 compose 作业照常通过（端到端另加：任何 CSP 违规即失败）。
- [x] 同一 jar 可在 `/` 与 `/admin/` 提供两个 SPA，各自回退、各自带 CSP（集成测试 `MultiSpaIT`）。
- [x] 路径检查脚本对"只改应用目录 / 改了平台目录"两种分支给出通过 / 失败（脚本测试 `tools/test/check-app-paths.test.sh`）。

说明：约定插件在 `backend/build-logic`（包 `com.jabiz.gradle`），每个 SPA 以 `VITE_BASE` 构建到本模块的 `build/spa/<名>`；
SPA 配置由启动检查 `WEB` 报告；未配置 CSP 的 SPA 取后台的缺省策略，因此现有后台从本阶段起带 CSP；路径检查的模式不得覆盖平台文件；
CI 对推送到任何分支运行（应用分支不能改 `ci.yml`）。端到端测试仍只对根路径部署运行，子路径部署由构建检查与 `MultiSpaIT` 覆盖。

### 13b 文件存储（4–5 天）

**要求**
1. `SysFile`、`FilePolicy`、`FileStore`（本地目录）、`ImageProcessor`、`jabiz.file` 类型、上传与读取接口、`FILE_REGISTER` / `FILE_DELETE` / `FILE_SWEEP`
   （任务 `FILE_SWEEP` 调用流程 `FILE_PURGE_ORPHANS`）。
2. 后台上传控件与预览。
3. 示范：`app` 中一个实体带图片字段与 PDF 字段。

**验收标准**
- [x] 带 GPS 的图片上传后，原件与全部变体都不含 EXIF / GPS（`FileUploadIT`，PNG 的文本块同样去掉）。
- [x] 伪装扩展名的 HTML / SVG 被拒绝；超限上传在读完请求体之前被中止（`FileUploadIT`、`FileLimitsIT`）。
- [x] 被引用的文件不能删除；孤儿行与孤儿对象被清扫，被引用的不受影响（`FileDeleteIT`、`FileSweepIT`）。
- [x] 上传与删除都有操作记录，`input_summary` 中没有文件名；BlockHound 通过（`FileDeleteIT`；全部集成测试均在 BlockHound 下运行）。

说明：D18 在计划确认时一并确认，实现细则写入 D18 第 7 条与 14：存储键只由 `fileId`（UUIDv7）推导，孤儿对象的年龄取其时间戳；
文件列不加外键；存储目录无默认值（有策略而未配置即启动失败，检查类别 `FILE`）；`jabiz.file` 的转换在 core，导出中的策略细节由 runtime 补全；
写入检查是平台内部的 `FieldWriteCheck`，覆盖全部写入途径；业务流程删除文件前先 `SaveChanges.now`（示范 `SUPPLIER_CONTRACT_REMOVE`）。
新增错误码 `FILE_*` 与 `RATE_LIMITED`（413 / 429 的问题响应）。示范：`Product.imageFileId`（`commerce.image`）与 `Supplier.contractFileId`
（`commerce.document`）。计划中的场景回放未做：上传经 HTTP 而不是流程，清扫改由 `FileSweepIT` 以可控时钟与 `JobRunner.run` 覆盖。

### 13c 公开只读访问（4–5 天）

状态：☑ 已完成（PR 待合并，分支 `phase-13c-public`）。设计见 15、03 §2.7、05 §2 与决策 D17。

**要求**
1. `publicRead(...)` 公开数据视图（投影渲染）、`access: public` 公开模板与启动检查、`/api/public/queries/{id}`、`/api/public/files/{id}`。
2. 匿名上下文、缓存头与 ETag、限流、总开关；公开模板目录快照。
3. 示范：`app` 中一个公开模板（例如已上架的商品目录）。

**验收标准**
- [x] 范围外的行在模板、外层筛选、计数三种途径下都不出现；白名单外的列即使写物理列名也读不到（`PublicAccessIT`）。
- [x] 文件只在被公开行的白名单字段引用时可匿名获取；下线并失效缓存后 404（`PublicAccessIT`，下线流程 `PRODUCT_WITHDRAW`）。
- [x] 非公开模板 404、写方法 405、过期令牌不影响公开读取、限流 429、开关关闭 404（`PublicAccessIT`、`PublicRateLimitIT`、`PublicSwitchedOffIT`）。
- [x] 启动检查报告公开视图与模板的全部违规（一次性）（`PlatformCheckIT`，类别 `PUBLIC`）。

说明：D17 在计划确认时一并确认，实现细则写入 D17 第 8–10 条与 15：已认证用户经 `/api/queries` 执行公开模板不需要权限；
公开视图的行数上限以 `maxQueryBatchSize ≤ jabiz.public.max-limit` 检查，接口同时截断；公开文件的下载名不用原始文件名；
判定缓存的失效是提交后步骤 `FileAccess.invalidate(...)`，只清本实例（多实例时在判定有效期内失效，已知限制），`FILE_DELETE` 也会失效；
`/api/public/**` 不进入 OpenAPI 文档。示范：公开视图 `urn:jabiz:dataset:public:Product`（`active = true`）、模板 `commerce.public.catalog`、
流程 `PRODUCT_WITHDRAW`（行操作）与场景 `public_catalog`；`app` 以 `JABIZ_PUBLIC_ENABLED=true` 打开公开访问（默认关闭）。
公开前端（culture）不在本阶段。

### 13d 内容编辑能力（4–5 天）

状态：☑ 已完成（PR 待合并，分支 `phase-13d-content`）。设计见 16、02、03、12 与决策 D20。

**要求**
1. `jabiz.i18n-text` 类型、校验与共享用例、后台多语言控件与 Markdown 预览。
2. `eb.display(...)`、`lookup` / `labels` 接口、引用下拉与引用列标签。
3. `pb.actsOn(...)` 与后台行操作；详情中的子实体列表。
4. 字段级 `f.processOnly()`（16 §5）。
5. 示范：`app` 中的多语言字段、只经流程写入的状态字段与行操作（`SupplierCertification`，16 §9）。

**验收标准**
- [x] 新增的两种错误码在前后端一致（共享用例）。
- [x] 不写前端代码，示范实体得到多语言编辑、引用下拉、行操作与子实体列表（Playwright）。
- [x] Markdown 预览不渲染原始 HTML（Vitest）。
- [x] `processOnly` 字段经数据视图 API 与通用实体流程都写不进去，经流程可写（集成测试）。

### 13e 显式初始状态、逐项调用子流程（0.5–1 天）

状态：☑ 已完成（PR 待合并，分支 `phase-13e-initial-state`）。由 culture 的工作流发现（审核退回回到草稿、发布与下线往返；抹除个人数据时删除数量不定的文件）。

**要求**
1. `st.initial(...)` 显式声明状态机的初始状态；声明后以声明为准，未声明时照旧推导（02 §4）。
2. 构建期校验：声明的状态属于状态字典、有出边、不重复。
3. `CallProcess.forEach(...)`：对运行时才知道数量的输入逐个调用子流程（同一事务、依次执行），06 §2。
4. 修正：计算步骤累积违规后遇到 `SaveChanges.now`，同一违规被报告两次（现在每条只报告一次）。

**验收标准**
- [x] 回到起点的状态机（`DRAFT → PUBLISHED → DRAFT`）插入时自动取初始状态、往返迁移合法（`ContentAuthoringIT`）。
- [x] 构建期校验与推导行为不变（`EntityBuilderTest`）。
- [x] 累积的违规在 `SaveChanges.now` 前后只报告一次（`ProcessEngineIT`，先写复现的失败测试）。
- [x] `forEach` 每个输入一个子操作（`parent_seq_id` 指向调用方、顺序与输出一致）；空列表不调用；任一失败全部回滚（`ProcessEngineIT`）。

### 13f 平台版本线（0.5–1 天）

状态：☑ 已完成（PR 待合并，分支 `1.0/phase-13f-version-lines`）。设计见 17 §1、§2 与决策 D21；由 finance 需要阶段 14 的不兼容改动、而 culture 不需要而提出。

**要求**
1. 线号文件 `.jabiz-platform-line`（`1.0`）；分支 `<线>/platform`、`<线>/<应用>`、`<线>/phase-…`、`<线>/<应用>-…`；发布标签 `platform-v<主>.<次>.<修订>`。
2. `tools/check-app-paths.sh` 以 `origin/<线>/platform` 为基准；以线命名的分支必须与线号一致；线号格式不对时无法检查（退出码 2）；
   CI 的 `app-paths` 作业取该线的平台分支，并以 `APP_PATHS_BRANCH` 告知分支名。
3. 决策 D21；17、00、根目录 `CLAUDE.md` 第 7、8 节；操作指南 `docs/guide/version-lines.md`（开新线、应用升级、修复向前合并、发布）。

**验收标准**
- [x] 原有 12 个路径检查用例照常通过；新增用例覆盖：基准取自线号、线的工作分支、应用改线号、未合并新线的平台、升级后按新线检查、
  修复向前合并、新线的平台工作分支未改线号、线号格式不对、新线的平台分支未取得、`APP_PATHS_BRANCH` 与游离的 HEAD、没有线号的旧分支
  （`tools/test/check-app-paths.test.sh`，共 24 个）。
- [x] CI 步骤的命令在本地对 1.0 线上的应用分支（游离 HEAD）运行通过。
- [x] 建立 `1.0/platform`、`1.0/culture`、`1.0/finance`；合入后打标签 `platform-v1.0.0`，开 `1.1/platform`。

---

## 阶段 14：应用所需的通用业务能力（线 1.1）

由 finance 提出（见其分支上的 `docs/finance-work/00-development-plan.md` §3.1），每项能力都是通用的，并在 `app` 中有示范与测试，不含任何财务代码。
14a 应用自有后台页面、语言子集、区域格式、金额小数位；14b 编号、审批、职责分离、任务与通知；14c 账本增强；14d 时点查询、导出、报表存档；
14e 导入框架；14f 审计与保留；14g 安全增强。各子阶段开始前出计划。

### 14a 应用自有后台页面、语言子集、区域格式、金额小数位（5–7 天）

设计见 12 §9 与决策 D22（在 14a 计划中确认）。分两个 PR：14a-1（扩展机制与示范）、14a-2（语言子集、区域格式、金额小数位）。

**14a-1 要求**
1. 构建时扩展：`defineExtension({routes, menu, messages, home})`、`virtual:jabiz-extension`、`@jabiz/admin`（`frontend/src/lib/index.ts`）、
   `runQuery` / `runProcess`；扩展的检查（启动时一次报告全部问题）、类型检查纳入 `pnpm build`、`pnpm ext:*` 与扩展的 lint 规则。
2. 约定插件：`spa(path, sourceDir, extension = …)`（`JABIZ_ADMIN_EXTENSION`、扩展目录为构建输入）。
3. 示范：`backend/app/admin-extension/`"库存概览"（模板 `commerce.stock_availability`、流程 `STOCK_RECEIVE`）。
4. 决策 D22；12 §9、17 §3.1、CLAUDE.md、`docs/guide/admin-extension.md`。

**14a-1 验收标准**
- [x] 不指定扩展时产物不含任何扩展代码；指定时扩展页面编入且只有一份 React / antd（构建对比）。
- [x] 扩展的路由问题（占用平台路径、相对路径、重复、index 路由）与菜单问题一次报告全部（`registry.test.ts`）；深层引用被 lint 拒绝（`extension-lint.test.ts`）。
- [x] 扩展的类型错误使 `pnpm build`（因而 jar 构建）失败。
- [x] 示范页面：从菜单进入、读取模板、执行流程；无权限用户看不到菜单项，直接打开时服务端返回 403（Vitest 3 个、Playwright 2 个）。
- [x] 现有全部检查照常通过：前端 lint / typecheck / test，端到端 23 个，`./gradlew check`。

**14a-2 要求**
1. 界面语言子集：`jabizApp { languages(…) }` → jar 中的 `META-INF/jabiz-app.properties` 与前端构建变量；消息目录与文案检查只用所选语言；启动检查 `I18N`；
   前端只列所选语言。
2. 区域：`jabizApp { region = … }`；`formatDateTime`、`formatDate`、`formatAmount`（括号负数），经 `@jabiz/admin` 导出；列表与详情按区域显示。
3. 金额小数位：`Monetary` 的小数位由 `EntityValidator` 与前端同样检查（`MONETARY_SCALE`，共享用例）；字段自己的 `SCALE` 规则优先，且不得比币种宽松。
4. 文档：D22 第 7 条、02 §2、12 §1、§5.1、§8、§10、17 §3.1、CLAUDE.md。

**14a-2 验收标准**
- [x] 只选英语的应用：请求日语、中文时都以英语回答，元数据也是英语；缺日、中文案不报错，缺英文案报错（`LanguageSubsetIT`、`LanguageSubsetTest`）。
- [x] 非平台语言、空列表、缺省语言不在其中：启动检查一次报告全部（`LanguageSubsetTest`）；前端构建对错误的语言或区域失败（`app-settings.test.ts`）。
- [x] `en-US` 下日期为 `MM/DD/YYYY`、金额有千分位与固定小数位、报表负数为 `(2,000.00)`，大金额不丢位数（`format.test.ts`）。
- [x] 金额小数位：共享用例（新增字段 `fee` 的 9 个用例）前后端都通过；自己的 `SCALE` 规则优先；比币种宽松的规则在构建期被拒绝（`KindConstraintValidationTest`）。
- [x] 插件：声明 `languages("en")`、`region = "en-US"` 时生成的属性与前端构建都带上它们，未声明时为全部语言（手工验证，见 PR）。
- [x] 现有全部检查照常通过。

### 14b 编号、审批、职责分离、任务与通知（7–9 天）

设计见 18 与决策 D23（在 14b 计划中确认）。分三个 PR：14b-1 编号；14b-2 审批与职责分离；14b-3 任务与通知。

**14b-1 要求**
1. `NumberSequence`（core：格式 `{n}` / `{n:宽度}` / `{scope}`、按范围计数、起始值）；启动检查 `NUMBERING`。
2. 流程步骤 `AssignNumber.of / when`：在流程事务内取号（`sys_number_counter` 的行锁），回滚归还；`sys_number_assignment` 只追加，平台实体 `NumberAssignment` 只读视图。
3. 示范：`ORDER_PLACE` 不带订单号时取 `SO-{scope}-{n:6}`。
4. 文档：18 §2、06 §2.1、D23、CLAUDE.md。

**14b-1 验收标准**
- [x] 按范围连续编号，格式正确；未按范围的序列从起始值开始（`NumberingIT`、`NumberSequenceTest`）。
- [x] 取号后失败的流程归还号码，下一个成功的流程取到它；条件不成立不取号（`NumberingIT`、`CommerceIT`）。
- [x] 20 个并发流程（其中 4 个取号后失败）：16 个号码，1…16 无缺无重（`NumberingIT`）。
- [x] 号码记录只 INSERT，触发器拒绝 UPDATE / DELETE；每个号码记录取号的操作（`NumberingIT`）。
- [x] 序列名重复、步骤引用未声明的序列或范围用法不对，启动时报告（`NumberingChecksTest`）。
- [x] 现有全部检查照常通过。

