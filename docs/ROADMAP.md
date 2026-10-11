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
| 14 | 应用所需的通用业务能力（由 finance 提出，14a–14r） | 45–58 天 | ☑ 已合入（线 1.1） |
| 15 | 后台前端改用 shadcn/ui（线 1.2，不兼容，15a–15d，决策 D34） | 17–24 天 | ◐ 15a 已完成（PR #105）；15b-1、15b-2 已完成；15c–15d 计划中 |
| 16 | 应用所需的通用能力（由 QuizBuks 提出，16a–16g：事务邮件、登录入口与自助注册、应用前端库与 PWA、入站 Webhook、异步外部作业、OIDC 自动开户、会话列表） | 20–28 天 | ◐ 进行中：16h 已合入；16a 已合入；16i 已完成；16l 已完成；16d 已完成（PR 待创建）；16e-1 已完成（PR #113）；16e-2 已完成（PR #119）；决策 D35–D39 已确认 |

**版本线**（决策 D21、17 §1）：平台按不兼容版本分线，线号在 `.jabiz-platform-line`；各阶段在其所在线的平台分支上进行。

| 线 | 平台分支 | 包含的阶段 | 发布标签 | 应用分支 |
|---|---|---|---|---|
| 1.0 | `1.0/platform` | 1–13f | `platform-v1.0.0` | `1.0/culture`、`1.0/finance` |
| 1.1 | `1.1/platform`（从 `1.0/platform` 的 `platform-v1.0.0` 拉出，已开） | 14 | — | `1.1/finance`（14a–14c 合入后升级）；culture 需要时升级；finance 停在本线，需要时再升级 |
| 1.2 | `1.2/platform`（从 `1.1/platform` 拉出，已开） | 15– | — | `1.2/quizbuks`（新应用） |

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
14e 导入框架（已完成）；14f 审计与保留（已完成）；14g 安全增强（已完成）；14h 日期类型（F1 开始时发现，已完成）；14i 时态数据的规模（F1c 压测发现，已完成）；14j 单据（F3 的发票文件，已完成）；14k 纳税人号码遮蔽与生成文件存档（F4 的供应商与付款文件，已完成）；14l 封存校验在表多时不再停住（F10b 发现，已完成）；14o 业务流程的命名锁（F11 的过账与关账串行，已完成）；14p 流程中的读取不截断（F11c 压测发现，已完成）；14q 查询在负载下的行为（F11c 压测发现，已完成）；14m 前端无障碍检查（finance F11，已完成）；14n webhook（finance F11，进行中）。各子阶段开始前出计划。

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

**14b-2 要求**
1. core：`ApprovalSubject`（事实与类型）、`ApprovalCondition`（`all`/`any`、比较运算）、`ApprovalLevel`（权限、限额事实）、`ApprovalEvaluation`（按优先级取第一条）、
   `ContentHash`（规范文本的 SHA-256）、`SodRule`。
2. 时态实体 `SysApprovalRule`、`SysApprovalLimit`、`SysSodRule`、`SysControlChange`、`SysApprovalRequest`；只追加 `ApprovalDecision`、`ApprovalEvaluation`（迁移 V13）。
3. 步骤 `RequireApproval`（按业务时间评估、记录规则版本与事实、绑定内容哈希、内容变化即作废）、`WithdrawApproval`；流程 `APPROVAL_DECIDE`
   （层级权限、限额、准备人与重复判断的检查；事件 `jabiz.approval.requested` / `approved` / `rejected`）。
4. 四眼修改：`CONTROL_CHANGE_PROPOSE` / `CONTROL_CHANGE_PUBLISH` / `CONTROL_CHANGE_WITHDRAW`；影响预览 `POST /api/approvals/preview`。
5. 职责分离：`SecUserRole` / `SecRolePermission` 全部写入途径上的写入检查（`SOD_CONFLICT`）；流程 API 入口兜底（`*` 不拦）；冲突报告 `GET /api/sod/conflicts`。
6. 示范：供应商认证提交经 `RequireApproval`，以事件继续；场景步骤可指定操作人。文档：18 §3–§4、06、07、10、D23、CLAUDE.md。

**14b-2 验收标准**
- [x] 规则由一人提出、另一人发布；同一人发布被拒；错误一次报告；可撤回（`ApprovalIT`、场景 `certification_approval`）。
- [x] 不需审批的案件记录所评估的规则版本与事实；按业务时间取规则，预定生效的修改只影响其后的案件（`ApprovalIT`）。
- [x] 批准绑定内容：同内容找到同一请求，内容变化使原请求作废并建新请求；批准后同内容得到 `APPROVED`（`ApprovalIT`、`ContentHashTest`）。
- [x] 准备人不能审批；缺层级权限 403；限额不足（或无限额）被拒；同一人不能判断两个层级；驳回需理由；同一层级的并发判断只有一个成功（`ApprovalIT`）。
- [x] 判断与评估只 INSERT，触发器拒绝 UPDATE / DELETE（`ApprovalIT`）。
- [x] 影响预览列出结论不同的单据与评估总数（`ApprovalIT`）。
- [x] 授予角色或权限造成冲突被拒（两条途径）；`*` 不拦；规则发布前已存在的冲突在流程入口被拒；冲突报告列出用户、规则、角色与 `*` 持有者（`SodIT`）。
- [x] 示范：等待审批时手工审核被拒；批准事件使认证通过（场景 `certification_approval`）。
- [x] 现有全部检查照常通过。

**14b-3 要求**
1. 时态实体 `SysTask`（指派给用户或权限、标题为文案键 + 参数、来源键）；步骤 `CreateTask` / `CloseTasks`；新待办发布 `jabiz.task.created`（迁移 V14）。
2. 审批请求自动建待办，逐层传递，判断完成、作废、撤回时关闭或取消。
3. `GET /api/tasks/mine`；后台页面 `/tasks`、页头待办数；`@jabiz/admin` 导出 `ApprovalPanel`、`useMyTasks`。
4. 邮件：`SecUser.email`；`TASK_NOTIFY` 记录只追加的通知并在提交后发送、失败重试（尝试只追加）；`jabiz.mail.enabled` 缺省关闭；启动检查 `MAIL`；GreenMail 测试。
5. 文档：18 §5、10、12、D23、CLAUDE.md、应用扩展指南。

**14b-3 验收标准**
- [x] 用户看到指派给本人或本人所持权限的开放待办（`*` 看全部按权限指派的），关闭后不再出现；未认证 401（`TaskIT`）。
- [x] 审批请求有当前层级的待办，判断后传给下一层，最后一层后关闭；作废与撤回取消待办（`TaskIT`）。
- [x] 待办与通知只 INSERT（`TaskIT`）。
- [x] 开启邮件：指派用户收到一封，按权限只发给启用且明确持有的有邮箱用户；失败的尝试被记录并重试成功，已发送的不重发（`NotificationIT`）。
- [x] 开启邮件而缺少服务器或发件人，启动时报告（`TaskSpecAndMailChecksTest`）。
- [x] 前端：待办页、审批面板（批准、需理由的驳回、显示服务端的拒绝）、页头待办数（`ApprovalPanel.test`、`TasksPage.test`、e2e `tasks.spec`）。
- [x] 现有全部检查照常通过。

### 14c 账本增强（5–6 天）

设计见 11 §1 与决策 D24（在 14c 计划中确认）。分两个 PR：14c-1 科目层级、行备注、来源单据、分析维度、余额与明细；14c-2 多币种。

**14c-1 要求**
1. 科目 `parentId`、`summary`：汇总科目不能过账；层级写入检查（上级须为汇总、不成环、有分录不能变汇总、有下级须保持汇总）；层级修改与过账以 advisory lock 串行。
2. 分析维度：core `LedgerDimension`（位置 1–4、名称、字典或实体字段来源），分录 `dimension1`–`dimension4`，过账时校验取值；启动检查 `LEDGER`。
3. 分录 `memo`；交易 `sourceEntity` / `sourceId`（过账时校验存在）；冲正照原样复制。
4. 模板：`account_balances` 增加 `from`、`knownAt`、层级汇总；新增 `dimension_balances`、`account_activity`。
5. 示范：`app` 声明仓库与销售渠道两个维度，订单的销售过账带来源；场景 `ledger/dimensions_rollup`。文档：11 §1、D24、CLAUDE.md。

**14c-1 验收标准**
- [x] 汇总科目不能过账；汇总科目余额为其下级之和，可过账科目余额之和为零；上级非汇总、成环、有分录变汇总、有下级变可过账都被拒绝（`LedgerEnhancementsIT`）。
- [x] 未声明的维度、字典或实体中没有的取值一次报告；按维度汇总正确；冲正后各维度余额为零（`LedgerEnhancementsIT`、`LedgerDimensionTest`）。
- [x] 来源单据记在交易上，冲正照复制；不存在的实体或实例被拒绝（`LedgerEnhancementsIT`）。
- [x] 区间与 `knownAt` 的余额、科目明细的期初 / 滚动 / 期末余额正确（`LedgerEnhancementsIT`、场景 `dimensions_rollup`）。
- [x] 维度声明问题在启动时报告（`LedgerChecksTest`）；账本表仍只 INSERT（`LedgerIT`）。
- [x] 现有全部检查照常通过（`order_lifecycle` 的快照多出销售交易的来源与新字段的空值）。

**14c-2 要求**
1. core `ForeignAmount`（币种、交易金额、汇率；换算为本位币按账本小数位四舍五入）；`LedgerPosting` 的外币规则：币种、金额小数位、汇率、换算一致、每个外币各自平衡。
2. 分录 `currency`、`transactionAmount`、`exchangeRate`（迁移 V16，空即本位币）；`LEDGER_POST` 可省略外币行的本位币金额；冲正照原样复制；
   数据库触发器按币种兜底。
3. 模板 `currency_balances`；`account_activity` 增加外币列。
4. 示范：场景 `ledger/multi_currency`（外币开票、按另一汇率收款并记汇兑收益、冲正）。文档：11 §1.8、D24。

**14c-2 验收标准**
- [x] 省略本位币金额时按汇率换算；给出而不一致被拒（附应有金额）；四舍五入远离零（`ForeignCurrencyTest`、`LedgerMultiCurrencyIT`）。
- [x] 本位币平衡而某外币不平衡被拒；差额 0.01 的不平衡显示差额（`ForeignCurrencyTest`、`LedgerMultiCurrencyIT`）。
- [x] 属性测试：随机的外币分录及其冲正通过校验，每个币种与本位币合计为零（`ForeignCurrencyTest`）。
- [x] 绕过流程写入的外币不平衡分录被数据库拒绝（`LedgerMultiCurrencyIT`）。
- [x] 按币种的余额、结算后应收在两种币种上都为零、冲正后归零（`LedgerMultiCurrencyIT`、场景 `multi_currency`）。
- [x] 现有全部检查照常通过。

### 14d 时点查询、导出与报表存档（5–7 天）

设计见 19 与决策 D25（在 14d 计划中确认）。分三个 PR：14d-1 时点运行、模板目录与"报表"页面；14d-2 导出；14d-3 签发存档与重现。

**14d-1 要求**
1. 运行时点：`POST /api/queries/{id}` 的 `asOf` / `knownAt`；头部 `timeSlice` 映射参数；两者冲突、视图禁止时间旅行、公开模板声明时点均被拒；
   `RunTemplate.at`；场景 `query` 期望的 `asOf` / `knownAt`。账本四个模板映射 `knownAt`。
2. 模板版本 `AdvancedQueryDefinition.version()`（文件全文或 DSL 规范描述的 SHA-256）。
3. 头部 `report`（期间、横向）；消息 `query.<id>`、`query.<id>.<列>`，报表缺标题由启动检查 `MESSAGES` 报告。
4. 目录 `GET /api/meta/queries`（有权运行的模板：参数 schema、结果列、筛选排序、版本、时点、报表）。
5. 后台 `/reports` 与 `/reports/run?id=<模板>`；菜单"报表"。示范：账本模板与 `commerce.stock_availability` 声明 `report`。
6. 文档：19、05 §2.1、§3、§5.1、D25、CLAUDE.md。

**14d-1 验收标准**
- [x] 按请求的 `asOf` / `knownAt` 运行时，时态实体（含主数据）取该时点的版本；缺省行为不变（`TemplateTimeSliceIT`）。
- [x] 声明 `timeSlice` 的模板以参数为时点，请求再给时点被拒；禁止时间旅行的视图被拒；公开模板的 `timeSlice` 启动即报错（`TemplateTimeSliceIT`、core 测试）。
- [x] 倒签更正：试算表按两个记录时点运行，只在更正的两个科目上相差，科目名称按当时所知显示（场景 `ledger/as_known_on`）。
- [x] 模板版本随文件任何修改而变，换行风格不影响（core 测试）。
- [x] 目录只列有权运行的模板，内容与执行一致（`QueryCatalogIT`）。
- [x] 后台运行报表：参数表单、时点、结果表格；无权限者看不到（Vitest、Playwright）。
- [x] 现有全部检查照常通过。

**14d-2 要求**
1. core `ReportDocument`、`ReportColumn`、`ReportFormat`（区域格式、括号负数）、`CsvReportWriter`（按列的小数位、防公式注入）。
2. runtime `XlsxReportWriter`（fastexcel，数值与日期单元格）、`PdfReportWriter`（PDFBox，页眉页脚、页码、列宽、确定性输出）、`PdfFonts`
   （Noto Sans 随平台提供，OFL；`jabiz.reports.pdf.fonts` 为后备字体）、`ReportSettings`、`ReportExporter`、`QueryTexts`；启动检查 `REPORTS`（字体文件可读）。
3. `POST /api/queries/{id}/export?format=`；行数上限 `jabiz.reports.export.max-rows`，超过 422 `REPORT_TOO_LARGE`；`AdvancedQueryExecutor.all`；观测 `jabiz.query.export`。
4. 约定插件把 `jabizApp { region }` 写入 `jabiz.region`。
5. 后台报表页"导出"（Excel、PDF、CSV，按当前参数、时点、筛选、排序）。
6. 文档：19 §4、§6、13 §2、D25、CLAUDE.md。

**14d-2 验收标准**
- [x] Excel 中的金额是数值单元格，其和等于报表的合计（`XlsxReportWriterTest`、`ReportExportIT`）。
- [x] CSV 的值精确（按列的小数位，不分组、不舍入），以公式开头的文本不会被当作公式（`CsvReportWriterTest`、`ReportExportIT`）。
- [x] PDF 每页有公司、报表、期间、运行时间、页码（给出生效时点时也写明）；负数带括号；窄列不被长文本挤掉，列再多也不越出页面（`PdfReportWriterTest`）。
- [x] 同一次运行的 PDF 逐字节相同（`PdfReportWriterTest`、`ReportExportIT`）。
- [x] 导出按请求的时点读取，页脚显示记录时点（`ReportExportIT`）。
- [x] 超过上限被拒绝（422 `REPORT_TOO_LARGE`），不截断；权限同运行，未知格式 400（`ReportExportIT`）。
- [x] 后台导出当前的行（Vitest、Playwright 下载）。
- [x] 现有全部检查照常通过。

**14d-3 要求**
1. 迁移 V17：`sys_report_run`、`sys_report_run_supersede`（只追加）。
2. 流程 `REPORT_ISSUE`（`report.issue` 加模板权限）：未给出的时点固定在签发时刻（`At.pinned`）、规范化与内容哈希、可重算标记、取代、事件 `jabiz.report.issued`。
3. `GET /api/reports/runs`、`/{id}`、`/{id}/export`（由存档重现）、`POST /{id}/verify`（`report.archive.read` 加签发时的模板权限与数据范围）。
4. 后台：报表运行页"签发"；`/reports/archive`（保存、核对、已被取代）。
5. 文档：19 §5、§6、12 §6、D25、CLAUDE.md。

**14d-3 验收标准**
- [x] 签发后继续过账，再次重现得到逐字节相同的 PDF 与 CSV，哈希不变，核对为 `identical`；未给出的时点固定在签发时刻，其后生效的排定变更不影响核对（`ReportArchiveIT`）。
- [x] 模板版本改变时核对报告 `template_changed`；读取原地修改数据的报表被标为仅存档，数据改变后核对为 `differs`（`ReportArchiveIT`）。
- [x] 一次运行只能被同一模板的另一次运行取代一次（`ReportArchiveIT`）。
- [x] 读取需要存档权限、模板权限与签发人相同的数据范围，不可读的运行为 404；签发需要模板权限（`ReportArchiveIT`）。
- [x] 存档表上的 UPDATE / DELETE 被数据库拒绝（`ReportArchiveIT`）。
- [x] 存入读回的值与哈希不变（`ArchivedValuesTest`）。
- [x] 后台签发、存档列表、保存与核对（Vitest、Playwright）。
- [x] 现有全部检查照常通过。

### 14e 导入框架（4–5 天）

设计见 20 与决策 D26（在 14e 计划中确认）。分两个 PR：14e-1 文件类型、解析、导入定义、按行保存点的预览、查看与映射；14e-2 提交、去重、导入记录与报告、后台页面。

**14e-1 要求**
1. `MediaTypes` 增加导入专用类型 `TEXT`、`XLSX`（按整个文件判定）、`XML`；只能单独组成策略；原样保存，下载为附件，公开接口不提供。
2. core `imports`：`ImportFormat`（CSV、定宽、XLSX、XML、自定义）、`ImportParser` SPI 与平台解析器（XLSX 与 XML 自己读取，禁止 DTD，按实际字节限制解压）、
   `ParseLimits`、`ImportDefinition`（字段、按行或按组、外部引用、整个文件的检查、控制合计、权限、时区与日期格式）、`ImportValues`、`ImportMapping`、`ImportPreparation`。
3. runtime：`StorageEngine.inSavepoint`、`ExecutionOptions.DRY_RUN`；内部流程 `IMPORT_RUN`（按单元保存点执行子流程，自身再查权限）；
   `GET /api/meta/imports`、`POST /api/imports/{id}/inspect`、`/preview`；映射 `SysImportMapping`（迁移 V18）与 `IMPORT_MAPPING_SAVE` / `REMOVE`；
   启动检查 `IMPORT`；配置 `jabiz.imports.max-rows`；观测 `jabiz.import.run`；错误码与三种语言的消息。
4. 示范：`commerce.stock`（CSV，按行，外部引用跳过）、`commerce.prices`（XLSX）、`commerce.price-list`（XML）、`ledger.opening`（CSV，按组，借贷相等）。
5. 文档：20、14 §3、06 §4、D26、CLAUDE.md。

**14e-1 验收标准**
- [x] 预览执行全部行，报告全部问题（读取、重复、流程拒绝），且不留下业务数据、操作记录（`ImportIT`）。
- [x] 失败的单元只撤销自己，后面的单元照常执行并看到前面单元的写入（`ImportIT`）。
- [x] 按组导入每组一次流程；借贷不平的文件报告文件级问题（`ImportIT`）。
- [x] CSV、定宽、XLSX（日期、公式、15 位精度）、XML 读取正确；XXE、实体膨胀、ZIP 炸弹、超限被拒（core 测试、`ImportIT`）。
- [x] 伪装的 `.xlsm` / `.docx`、HTML/SVG 不被判定为导入类型；导入类型不能与其他类型混用（core 测试）。
- [x] 映射调整版式与列，保存的映射只追加（`ImportIT`）。
- [x] 缺导入权限、缺行流程权限、直接执行内部流程、别的策略的文件均被拒（`ImportIT`）。
- [x] 现有全部检查照常通过。

**14e-2 要求**
1. `IMPORT_RUN` 的提交：有问题整体回滚（422 `IMPORT_REJECTED`）并另行记录被拒的导入；成功则提交数据、导入记录、外部引用与事件 `jabiz.import.committed`。
2. 迁移：`sys_import_run`（只追加；同一导入同一文件只成功一次）、`sys_import_ref`（只追加；外部引用唯一）。
3. 导入记录列表与详情、报告导出（PDF、XLSX、CSV，复用 19 §4 的导出）、数据质量说明。
4. 后台导入向导（上传、映射、参数、预览、提交、报告）与导入历史。
5. 场景步骤 `import`（07 §3.1）与场景 `imports/opening_balances`、e2e、文档（20、07、12、D26）。

**14e-2 验收标准**
- [x] 一行出错时整份文件不写入任何数据，被拒的导入有记录（`ImportIT`、场景 `imports/opening_balances`）。
- [x] 同一文件再次提交 409，外部引用跳过或报错，并发提交同一文件只有一次成功（`ImportIT`）。
- [x] 导入表上没有 UPDATE / DELETE；各行的操作指向导入的操作；被撤销的工作的提交后步骤不执行（`ImportIT`、`ProcessEngineIT`）。
- [x] 导入记录的列表、详情、报告导出（PDF、XLSX、CSV），只对能运行该导入的人可见（`ImportIT`）。
- [x] 后台完成一次导入（Vitest、Playwright）。
- [x] 现有全部检查照常通过。

### 14f 审计、防篡改与保留（6–8 天）

设计见 21 与决策 D27。分三个 PR：14f-1（审计记录）、14f-2（防篡改）、14f-3（保留、保全与导出）。

**14f-1 要求**
1. 迁移 V20：`sys_audit_record`（只追加）；`DatasetEntityManager`（普通实体）与 `VersionAppender`（时态实体）在同一事务内记录每次写入的前后值、操作人、时间、原因。
2. 遮蔽：敏感字段只存 `***`（core `AuditDiff`）；值按规范形式保存与比较。
3. 接口 `GET /api/audit/records`（按实体、操作人、时间、流程、字段；`withApprovals` 一并列出审批）、`/{recordNo}`；`/api/audit/operations` 每项增加 `auditRecords`。
4. 后台 `/audit`、历史页与列表行的入口。
5. 文档：21、D27、11 §3、12 §6、CLAUDE.md。

**14f-1 验收标准**
- [x] 普通与时态实体的插入、更新、删除都有前后值，更新只记变化的字段，原因带出（`AuditRecordsIT`）。
- [x] 敏感字段只以 `***` 出现，数据库中也没有原值（`AuditRecordsIT`、`AuditDiffTest`）。
- [x] 审计表上 UPDATE / DELETE 被拒；接口需要 `audit.read`，非法参数 400（`AuditRecordsIT`）。
- [x] 单据的审计能列出审批人、结论与理由（`ApprovalIT`）。
- [x] 后台看到一次修改的前后值（Vitest、Playwright）。
- [x] 现有全部检查照常通过（场景快照不变）。

**14f-2 要求**
1. core：`MerkleRoot`、`SealedColumns`、`SealBlock`（HMAC 块哈希）、`SealChain`（链的检查）、`IntegrityKey`、`IntegrityProblem`。
2. 迁移 V21：`sys_integrity_seal`、`sys_integrity_item`、`sys_integrity_seal_table`、`sys_integrity_check`（都只追加）。
3. `INTEGRITY_SEAL` / `INTEGRITY_VERIFY` 与定时任务 `jabiz.integrity-seal` / `jabiz.integrity-verify`；只追加表从目录发现；启动检查 `INTEGRITY`（无主键的只追加表）。
4. 密钥 `JABIZ_INTEGRITY_KEY`（非 dev 缺少即启动失败）；`/api/integrity/head|seals|checks`；页面 `/integrity`。
5. 文档：21 §2、D27 第 4 条、CLAUDE.md；部署：CI、docker、快速上手。

**14f-2 验收标准**
- [x] 绕过触发器修改或删除一行、改动封存块或条目、关掉表的只追加保护，校验分别报告被改、缺失、断链与条目被改、未受保护（`IntegrityIT`、`SealChainTest`）。
- [x] 封存时尚未提交的行由下一次封存补上（`IntegrityIT`）；非 dev 缺少密钥启动失败（`IntegrityKeySettingsTest`）。
- [x] 迁移给只追加表加列后已有封存照常通过；改列清单来掩盖修改被发现（`IntegrityIT`）。
- [x] 块哈希是密钥的 HMAC、链到前一块；封存表不能 UPDATE / DELETE；接口与流程需要各自的权限（`IntegrityIT`）。
- [x] 后台查看最新块、运行校验、查看问题（Vitest、Playwright）。
- [x] 现有全部检查照常通过。
- 与计划的差别：不需要宽限期（见 21 §2.2）；"在应用之外插入的可疑行"无法由封存判定，改为报告未封存行数并在 21 §2.3 说明。

**14f-3 要求**
1. core：`RetentionPolicy`（按日期或会计年度末起算）、`LegalHold`、`OpenCsv`。
2. 删除拦截（`DeletionGuard`：普通实体的删除、时态实体的墓碑，422 `RETENTION_ACTIVE` / `LEGAL_HOLD`）；文件清理跳过受保留与保全的文件；启动检查 `RETENTION`。
3. `SysLegalHold`（迁移 V22）与 `LEGAL_HOLD_PLACE` / `LEGAL_HOLD_RELEASE`；到期报告 `GET /api/retention`。
4. `POST /api/exports/data`（CSV、`schema.json`、`manifest.json`、报表 PDF），操作记录 `DATA_EXPORT`。
5. 后台 `/retention`；示范：运费明细保留 7 年；文档 21 §3–§4、D27、14、12、CLAUDE.md。

**14f-3 验收标准**
- [x] 保留期内与受保全的记录（普通与时态实体、文件）删除被拒，解除保全后可删；文件清理跳过并报告（`RetentionIT`、`FileSweepIT`、`RetentionPolicyTest`）。
- [x] 保全只经其流程下达与解除，解除须写原因；到期报告的数量正确（`RetentionIT`）。
- [x] 导出的 ZIP 与清单一致，按权限过滤，敏感字段不导出；用导出的 CSV 重算试算表与系统一致（`DataExportIT`、`OpenCsvTest`）。
- [x] 后台查看到期报告、进入保全、导出（Vitest、Playwright）。
- [x] 现有全部检查照常通过。

### 14g 安全增强（6–7 天）

设计见 10 §9–§13 与决策 D28（在 14g 计划中确认）。分三个 PR：14g-1 二次验证、按操作要求二次验证、闲置锁定；14g-2 OIDC 单点登录；
14g-3 按权限显示明文、数据期限、访问审查。

**14g-1 要求**
1. core：`Totp`（RFC 6238）、`Base32`、`RecoveryCodes`、`MfaSecretCipher`（AES-256-GCM，附加数据为用户主键）、`MfaRequirement`；
   流程 `requiresMfa(…)`、数据视图 `writeRequiresMfa(…)`；`RequestContext.mfaAt`；登录结果 `MFA_REQUIRED` / `MFA_ENROLLMENT_REQUIRED` / `MFA_FAILED`。
2. 迁移 V23：`sec_user_mfa_version`（`SecUserMfa`）、`SecRole.requireMfa`、登录记录的 `factor` / `mfaStep`、刷新令牌的 `mfa_at`。
3. 流程 `SPONSOR_MFA_VERIFY`、`SEC_MFA_ENROLL_BEGIN` / `CONFIRM`、`SEC_MFA_RESET`；接口 `/api/auth/challenge/**`、`/api/auth/mfa/**`、`/api/auth/step-up`；
   密钥 `JABIZ_MFA_KEY`（非 dev 缺少即启动失败）。
4. 入口检查（流程 API、数据视图提交、实体 API、导入、撤销）；平台管理操作为 `ADMINISTRATION` 级（`jabiz.security.mfa.administration`）；示范：`PRICE_ADJUST` 总要求二次验证。
5. 闲置：刷新窗口 = 访问令牌有效期 + 闲置时长，启动检查 `SECURITY`；前端登录第二步、绑定、安全设置页、step-up 对话框、闲置锁定。
6. 文档：10 §9–§11、D28、07、12、13、CLAUDE.md；部署：CI、docker、快速上手。

**14g-1 验收标准**
- [x] 已绑定的用户须以密码与验证码登录；码错误与密码错误计入同一锁定；同一个码不能用两次；恢复码只能用一次；新登录使旧挑战失效（`MfaIT`、`TotpTest`、`LoginAttemptPolicyTest`）。
- [x] 角色要求二次验证时，未绑定的用户先绑定再登录；已有的会话在刷新时失效（`MfaIT`）。
- [x] 要求二次验证的操作在各入口拒绝没有或过旧的二次验证（403 `MFA_REQUIRED`），step-up 后通过；管理级可按配置关闭，`ALWAYS` 不受影响（`MfaIT`、`MfaAdministrationOffIT`）。
- [x] 密钥加密存放，不能挪给别的用户；操作记录中没有验证码与密钥；新表只插入（`MfaIT`、`MfaSecretCipherTest`）。
- [x] 闲置超过时长的会话不能刷新；访问令牌长于闲置时长时启动失败（`MfaIT`、`MfaSettingsTest`）。
- [x] 后台：登录第二步、绑定、安全设置、step-up、闲置锁定（Vitest；Playwright `mfa.spec.ts`）。
- [x] 现有全部检查照常通过。
- 与计划的差别：场景回放不经入口，不检查二次验证（与权限相同），因此场景文件不需要新增 `mfa` 键。

**14g-2 要求**
1. 配置 `jabiz.security.oidc.providers[i]`（`id`、`issuer`、`client-id`、`client-secret`（环境变量）、`redirect-uri`、`scopes`、`labels`、`mfa-amr`）与启动检查 `SECURITY`。
2. 发现文档与 JWKS（缓存、未知 `kid` 时重读）、授权码 + PKCE + nonce、ID 令牌校验（RS256 / ES256、iss、aud、azp、exp、iat、nonce、sub）；
   接口 `GET /api/auth/oidc/providers`、`POST /api/auth/oidc/{id}/start`、`POST /api/auth/oidc/callback`（公开）。
3. 迁移 V24：`sec_user_identity_version`（`SecUserIdentity`，管理员经数据视图关联，权限 `security.user.identity.write`，管理级二次验证）、
   `sec_oidc_state` / `sec_oidc_state_use`（发起时删除过期请求）、`sec_refresh_token.identity_id`。
4. 流程 `SPONSOR_OIDC_SIGN_IN`（登录记录 `factor = OIDC`）；`amr` 按配置视同二次验证，否则沿用 14g-1 的第二步；`SEC_USER_CREATE` 的密码可选。
5. 前端：登录页的提供方按钮、回调页 `/login/oidc`。文档 10 §12、D28、07、12、CLAUDE.md。

**14g-2 验收标准**
- [x] 关联的用户经提供方登录；未关联的主体被拒且不开户；没有密码的用户不能用密码登录，错误的密码也锁不住他（`OidcIT`）。
- [x] 回调必须带发起登录的浏览器的 binder（login CSRF）；解除关联后其会话不能再刷新；提供方的二次验证以 `auth_time` 计时（`OidcIT`）。
- [x] state 只能用一次、10 分钟过期；PKCE verifier 不符时提供方拒绝；nonce、受众、授权方、签发方、时间、主体不符与 HS256、`none`、别的密钥签名都被拒；密钥轮换后只重读一次 JWKS（`OidcIT`）。
- [x] `amr` 符合配置时会话带二次验证；否则已绑定的用户转入验证码，角色要求而未绑定时先绑定；锁定与禁用的用户被拒（`OidcIT`）。
- [x] 关联需要专门的权限并是管理操作（二次验证），一个主体只对应一个用户；除过期的登录请求外只插入，操作记录中没有 state 与授权码（`OidcIT`）。
- [x] 配置问题一次报告全部（`OidcProvidersTest`）；后台：提供方按钮、回调页、回调后的第二步（Vitest）。
- [x] 现有全部检查照常通过。
- 与计划的差别：对提供方的调用改用 JDK 的异步 HTTP 客户端而不是 WebClient（WebClient 在 R2DBC 线程上恢复链路上下文时触发 Micrometer 的断言）；
  回调不在路径中带提供方，由 state 记录的提供方决定；state 表改为可删除过期行（任何人都能发起登录，只追加会无限增长）；
  代码审查后增加 binder、`auth_time`、关联的专门权限与刷新时检查关联。端到端测试不接身份提供方（见计划）。

**14g-3 要求**
1. 遮蔽字段 `f.masked(权限, MaskStyle.LAST4 | ALL)`：读接口、历史、审计、导入报告、`input_summary` 中为遮蔽形式；`POST /api/datasets/{id}/reveal` 逐值显示明文，
   每次写只追加的 `sys_reveal_record`（迁移 V25）；SQL 模板在 `{{Entity}}` 中为无权限者投影遮蔽形式，持有权限者的运行、报表导出与数据导出留记录，签发的报表一律遮蔽；
   写入、筛选、排序需要权限，遮蔽形式不能写回（400 `MASKED_VALUE`）；遮蔽字段不能公开（启动检查）；`GET /api/audit/reveals`。
2. 数据期限：`SecUserRole.dataFrom` / `dataTo`（V25），`DataPeriod.hull`，令牌 `data_from` / `data_to`，`RequestContext.dataPeriod`，`/api/auth/me`；
   数据视图范围 `withinDataPeriod(字段)` 与经引用的 `withinDataPeriod(引用字段, 被引用字段)`（启动检查）；账本交易与分录；审计查询按记录时间截取。
3. 访问审查：报表模板 `jabiz.security.access_review`（`REPORT_ISSUE` 按期末时点签发）、`GET /api/security/access-reviews[/changes|/conflicts]`、
   流程 `ACCESS_REVIEW_SIGN_OFF`（`security.access-review.sign`，总要求二次验证）与只追加表 `sys_access_review`（V25）。
4. 前端：列表中的"显示"、遮蔽字段的筛选排序与表单权限、页头的数据期限、审计页"明文显示"页签、`/access-review`。示范：`Carrier.bankAccount`（app V12）。
5. 文档：10 §13、D28、02、03 §2.2、05、12、19、21、CLAUDE.md。

**14g-3 验收标准**
- [x] 遮蔽字段在读接口、历史、审计、操作记录与通用实体流程的结果中为遮蔽形式；显示明文需要数据视图与字段的权限，每次留记录，范围外 404（`MaskedFieldIT`）。
- [x] 无权限者写入被拒、不能按其筛选排序；遮蔽形式不能写回；持有权限者可以（`MaskedFieldIT`）。
- [x] 模板对无权限者在 SQL 中遮蔽（条件同样只见遮蔽值），持有权限者的运行与导出留记录；签发的报表一律遮蔽；数据导出同理（`MaskedFieldIT`、`AccessControlCompilerTest`）。
- [x] 遮蔽样式、审计形式、声明约束（文本、非敏感、非主键、非显示字段、非默认排序、不能公开）（`MaskedFieldTest`）。
- [x] 期限限定的审计师只看到期间内的交易、分录与报表数字；审计记录按记录时间；期限外写入被拒；期限来自角色分配（外包、不限期即不限、刷新保持）（`DataPeriodIT`、`DataPeriodTest`）。
- [x] 访问审查：按期末签发的报表、期间内的变更与冲突，签核保存引用与哈希；需要权限与二次验证、期间已结束、报表正确；表只插入（`AccessReviewIT`）。
- [x] 后台：显示明文、遮蔽字段的筛选与表单、数据期限标签、明文显示记录、访问审查页（Vitest）。
- [x] 现有全部检查照常通过。
- 与计划的差别：签发的报表一律遮蔽（计划未涉及；存档给别人阅读与核对，核对须得到相同的行）；访问审查不另存报表快照，改为 `REPORT_ISSUE` 签发并在签核中引用（D25、D28 第 9 条）；
  经引用声明期限的数据视图，受限者不能看历史、不能更新（默认拒绝）；写入时经引用的期限在时间所在的实体上检查。

### 14h 日期类型（1 天）

finance F1 开始时发现：过账日、单据日、到期日是日历日期（`LocalDate`），平台只有表示时刻的 `Temporal`。用时刻表示日期会随时区移到前一天或后一天，
用文本表示则失去比较、区间筛选、导出与导入的日期处理。这是通用能力，先在平台补齐（02 §1.1）。

**要求**
1. core：语义类型 `SemanticKind.Date`（`f.asDate()`，`LocalDate`，列 `date`）：值转换只接受 ISO 日期（带时刻的值拒绝）、比较运算符、元数据导出 `{"type":"date"}`、
   JSON Schema `format: date`、模板参数与结果（`CAST(:p AS date)`，类型兼容检查）、业务参数、报表期间参数（`timeSlice` 仍只能是时刻）、
   CSV（ISO）、报表格式（区域格式的日期）、导入（ISO 或导入声明的日期格式）。
2. runtime：XLSX 日期单元格、报表存档与重现、参数页眉、保留期自日期字段起算（启动检查、删除拦截与报告）。
3. 前端：日期控件（所选那天的 `YYYY-MM-DD`，不经 UTC 换算）、显示、列表的日期区间筛选、共享校验。
4. 示范：`app` 运费月结单的到期日 `FreightStatement.dueDate`（V13，月结时算出次月末）与模板 `logistics.freight_statements_due`（日期参数与结果）。
5. 文档：02 §1、12 §4、19 §2.2、21 §3.1、CLAUDE.md。

**验收标准**
- [x] 日期经数据视图写入、读出为 `YYYY-MM-DD`，按天比较、排序、区间筛选；不存在的日期、带时刻或其他格式的值 400（`DateKindIT`、`FieldValueCoercerTest`）。
- [x] 模板以日期为参数与结果，CSV 为 ISO 日期、XLSX 为日期单元格；带时刻的参数被拒（`DateKindIT`、`TemplateChecksTest`、`SqlTypeCompatibilityTest`、`ReportFormatTest`）。
- [x] 前后端共享校验用例（`spec/validation-cases.json` 的 `dueOn`）两边通过；前端控件、显示、筛选（Vitest）。
- [x] 保留期自日期字段起算：到期当天可删、次日的仍拒绝，报告按天计数（`RetentionIT`）。
- [x] 月结算出到期日（场景快照 `freight/monthly_close`）；现有全部检查照常通过。

### 14i 时态数据的规模（3–4 天）

finance F1c 的压测（约 200 万个分录版本，`1.1/finance` 的 `docs/finance/perf.md`）发现：当前版本子查询对整表 `DISTINCT ON`，科目查询 3.9 秒、试算表 28 秒；
唯一性检查同样取整表的当前版本，每次过账约 1 秒。按版本直接读分别为 49 毫秒与 0.1 毫秒。这是所有大时态表的问题，在平台解决（04 §5、决策 D29）。
分两个 PR：14i-a 后端，14i-b 后台与扩展的修正（F1c 发现的两处扩展问题、只写一次的实体在后台不显示修改与删除）。

**要求（14i-a）**
1. 唯一性检查与"之后仍被引用"检查先按值从索引取候选实例，再只看它们的当前与预定版本（04 §7）。
2. 查询条件中只涉及不可变字段的部分下推到当前版本子查询（实体查询、计数、显示字段；范围从不下推）。
3. 只写一次的时态实体 `t.writeOnce()`：读取不用 `DISTINCT ON`；更新、删除、取消、撤销 422 `WRITE_ONCE`；启动检查要求只含主键的唯一索引（错误）；
   唯一约束缺少支撑索引时启动检查告警。账本交易与分录声明为只写一次（V26 建唯一索引，并为审批与职责分离规则的代码建索引）。
4. 示范：入库记录 `StockReceipt`（只写一次，`STOCK_RECEIVE` 写入；V14 同时为价格的 SKU、订单行的单号与行号建索引）。
5. 文档：09 D29、04 §5.1/§5.3/§5.4/§7、02 §5、CLAUDE.md。

**验收标准（14i-a）**
- [x] 下推不改变结果：随机历史（墓碑、预定、补记、按时间点与按所知时间）上 300 次随机查询与手写的参照 SQL 一致；手工把可变字段误标为不可变时测试失败（`TemporalPushdownIT`、`TemporalPushdownCompilerTest`）。
- [x] 在约 20 万个版本上，唯一性检查、引用检查、按不可变字段的查询、只写一次实体的读取，计划中没有任何节点预计读取超过 1000 行；旧的整表读法在同一测试中失败（`TemporalScaleIT`）。
- [x] 只写一次：更新、删除、撤销都 422 `WRITE_ONCE`，历史只有一个版本；缺少主键唯一索引时启动检查报错（`WriteOnceIT`）；账本冲正照常（`LedgerIT`）。
- [x] 现有全部检查照常通过（唯一性、引用、撤销、账本、示范与场景）。
- [ ] finance 合并后在压测数据上：50 行过账 p95 ≤ 1 秒，一个月的科目查询 ≤ 2 秒（在 `1.1/finance` 上验收）。


**要求（14i-b）**
1. `frontend/tsconfig.extension.json` 解析 `react-router` 的类型（它只在 `exports` 中声明，按包目录的映射读不到）；应用扩展的 `tsconfig.json` 不再重复 `paths`。示范扩展用 `react-router` 的 `Link`。
2. 登录后没有要返回的页面时落在扩展的 `home`（与根路径一致），而不是固定的 `/data`。
3. 实体元数据导出 `writeOnce`；生成的列表对只写一次的实体只提供查看（不提供修改、删除），历史页不提供撤销。
4. 文档：12 §9、02 §8。

**验收标准（14i-b）**
- [x] 示范扩展引用 `react-router` 通过 `pnpm ext:check`；去掉映射即报 `TS2307`（手工验证）。扩展的链接指向入库记录的生成页面（Vitest）。
- [x] 声明了 `home` 的应用登录后落在 `home`，有要返回的页面时返回该页（`LoginPage.test.tsx`）。
- [x] `writeOnce` 随元数据导出（`TemporalDefinitionTest`）；入库记录的列表只有查看（e2e `extension.spec.ts`）。
- [x] 前端 lint、typecheck、测试与现有 e2e 照常通过。

### 14j 单据：版式、确定性 PDF、签发存档与发送（4.5–6 天）

finance F3（发票文件 FIN-AR-005、按发票日重印地址 FIN-AR-001 验收 2）提出；以后的付款通知、1099 收件人副本、对账单同样需要。
报表（14d）是一张表、重印时重新排版；邮件（14b-3）只发纯文本给系统用户。设计见 22 与决策 D30（在 14j 计划中确认，全部按推荐方案）。
分两个 PR：14j-1 版式、PDF、签发存档、重印与核对、预览、后台；14j-2 以附件发给外部收件人并记录投递。

**要求（14j-1）**
1. core `com.jabiz.document`：`DocumentLayout`（区块 `party` / `facts` / `table` / `totals` / `text` / `note`、单号、对象、权限、版本与规范描述）、
   `DocumentContent`、`DocumentLayoutProblems`；`TemplateSchemas` 合并多个模板的参数。
2. runtime `com.jabiz.runtime.document`：`DocumentLayoutRegistry`（启动检查 `DOCUMENTS` 与消息）、`Documents`（同一时点读取全部模板、单行检查、标签、内容哈希、排版）、
   `PdfDocumentWriter`（Letter / A4、并排区块、跨页重复表头、折行不截断、页脚与预览标记、确定性）、`DocumentSettings`、流程 `DOCUMENT_ISSUE`、`DocumentRuns`、`DocumentController`。
   错误码 `DOCUMENT_NOT_SINGLE`、`DOCUMENT_TOO_LARGE`；观测 `jabiz.document.render`。
3. 迁移 V27：`sys_document_run`（只追加，存 PDF 原样字节与内容）。
4. 接口：`GET /api/meta/documents`、`POST /api/documents/{layout}/preview`、`GET /api/documents/runs`、`/{id}`、`/{id}/pdf`、`POST /{id}/verify`。
5. 后台 `/documents`（菜单"单据"）；`@jabiz/admin` 导出 `DocumentPanel`。
6. 示范：版式 `commerce.order_confirmation`、模板 `commerce.order_document_header` / `_lines`、流程 `ORDER_CONFIRMATION_ISSUE`（`actsOn(SalesOrder)`，按下单时刻读取）。
7. 文档：22、09 D30、CLAUDE.md。

**验收标准（14j-1）**
- [x] 签发的 PDF 显示各区块（标题与单号、客户、键值、明细、合计、说明）；金额按区域格式、负数带括号；长明细跨页并重复表头，每页有"第 n 页，共 m 页"；长文本折行不截断（`PdfDocumentWriterTest`、`DocumentIssueIT`）。
- [x] 同一内容排版两次逐字节相同；重印返回存档原样字节，哈希与签发时相同（`PdfDocumentWriterTest`、`DocumentIssueIT`）。
- [x] 签发后改商品名与仓库名：重印不变，核对 `identical`；再次签发仍按下单时刻读到当时的名称；预览按现在读取、每页标"预览"、不保存（`DocumentIssueIT`）。
- [x] 版式或模板版本改变时核对报告 `layout_changed` / `template_changed`；存档字节被改动时 `copyIntact=false` 且下载被拒；数据被绕过平台改动时 `differs`（`DocumentIssueIT`）。
- [x] 单行区块的模板没有行时 422 `DOCUMENT_NOT_SINGLE` 且不保存；未知参数 400、未知版式 404、非应用语言 400（`DocumentIssueIT`）。
- [x] 签发需要版式与模板的权限（经业务流程也一样）；读取需要存档权限，缺版式权限的单据为 404 且不列出；预览需要签发权限；目录只列可签发的版式（`DocumentIssueIT`）。
- [x] 存档表上的 UPDATE / DELETE 被数据库拒绝（`DocumentIssueIT`）。
- [x] 启动检查一次报告：重复版式、缺模板、缺列、单行与表格混用、参数不一致、未知对象实体、缺文字、纸张配置错误（`PlatformCheckIT`、`DocumentLayoutProblemsTest`）。
- [x] 版式版本随描述变化（`DocumentLayoutTest`）；core 门禁类行覆盖率 ≥ 80%。
- [x] 前端：单据页（下载、核对、存档被改的提示）、`DocumentPanel`（列出、预览、签发、显示服务端的拒绝）（`DocumentsPage.test.tsx`、`DocumentPanel.test.tsx`）；端到端：订单行操作签发 → 单据页 → 按签发原样下载 → 核对（e2e `documents.spec.ts`，重复 3 次通过）。
- [x] 现有全部检查照常通过。

**要求（14j-2）**
1. core：版式的 `recipients(模板, 列)`（签发时读出地址并随单据存档）、`DocumentRecipients`（拆分、纯地址、大小写）、检查收件地址列为文本。
2. runtime：流程 `DOCUMENT_SEND`（权限 `document.send`；邮件关闭 422 `MAIL_DISABLED`；看不见的单据 404；非纯地址或超过 10 个 400；没有地址 422 `DOCUMENT_NO_RECIPIENT`；
   数据以外的地址需要 `document.send.any`，否则 422 `DOCUMENT_RECIPIENT_NOT_ALLOWED`）；事务内记录投递，提交后逐条发送（附件为存档字节、发送前核对哈希），
   尝试只追加、失败重试、已发送的不重发；`NotificationSender.send(MailMessage)`（带附件），`SmtpNotificationSender` 以 MIME 发送。
3. 迁移 V28：`sys_document_run.recipients`、`sys_document_delivery`、`sys_document_delivery_attempt`（只追加）。投递记在单据自己的表而不是 `sys_notification`（D30 第 6 条的细化，22 §5.2）。
4. 接口：单据详情附投递记录，摘要附缺省地址。
5. 后台：`DocumentPanel` 与单据页的"发送"对话框；单据页每行展开为投递记录。
6. 示范：订单头模板给出联系地址；流程 `ORDER_CONFIRMATION_SEND`（签发并发送）。
7. 文档：22 §5、D30、18 §5.4、CLAUDE.md。

**验收标准（14j-2）**
- [x] 发往单据数据中的地址：每个地址一封，附件的 SHA-256 等于存档的 `pdfHash`，主题含单号，发件人为 `jabiz.mail.from`；投递记录为 `SENT`（`DocumentSendIT`，GreenMail）。
- [x] 再次发送得到新的一封、同样的字节；已发送的不重发（`DocumentSendIT`）。
- [x] 失败的尝试被记录并重试成功；存档字节被改动时不发出、尝试记为失败（`DocumentSendIT`）。
- [x] 数据以外的地址没有 `document.send.any` 时 422 `DOCUMENT_RECIPIENT_NOT_ALLOWED` 且不记录；大小写不同算同一地址；有该权限时发出（`DocumentSendIT`）。
- [x] 含换行、带显示名或格式不对的地址 400；超过 10 个 400；没有 `document.send` 403；看不见的单据 404（`DocumentSendIT`）。
- [x] 邮件关闭时 422 `MAIL_DISABLED` 且不记录（`DocumentIssueIT`）。
- [x] 投递与尝试只 INSERT（`DocumentSendIT`）；待办邮件照旧（`NotificationIT` 不改动仍通过）。
- [x] 收件地址的拆分与校验、版式的收件人声明与检查（`DocumentRecipientsTest`、`DocumentLayoutTest`、`DocumentLayoutProblemsTest`）。
- [x] 前端：发送对话框（缺省地址、服务端的拒绝）、投递记录（Vitest）；e2e：单据页发送对话框给出单据中的地址并显示服务端的拒绝（`documents.spec.ts`，e2e 的应用不开邮件）。
- [x] 现有全部检查照常通过。

### 14k 纳税人号码的遮蔽样式与生成文件存档（1–2 天）

finance F4（应付）需要只让部分人看明文的供应商纳税人号码，以及原样保存交给银行与税务机关的文件（NACHA、正向支付、1099 申报）。
设计见 10 §13.1、14 §10 与决策 D31（在 F4 计划中确认）。

**要求**
1. core：`MaskStyle.TAX_ID`（Java 与 SQL 形式）；遮蔽形式的识别。
2. runtime：迁移 V29 `sys_generated_file`（只追加）、`sys_reveal_record.kind` 增加 `FILE`；子流程 `FILE_ARCHIVE`；`GET /api/generated-files/{id}`；
   权限 `file.generated.read`、`file.generated.archive`（不授予角色）；`jabiz.files.generated.max-bytes`。
3. 示范：app 的 `ORDER_PICK_LIST_ARCHIVE`（订单拣货单 CSV）。
4. 后台：遮蔽样式类型、显示记录种类的文字；OpenAPI 快照。
5. 文档：10 §13.1、14 §10、D31、CLAUDE.md。

**验收标准**
- [x] `TAX_ID` 的 Java 形式、SQL 形式与识别（`MaskedFieldTest`）；所有样式的 SQL 与 Java 形式逐值相同（`MaskedFieldIT`）。
- [x] 保存后原样下载，哈希一致，响应带哈希与 `nosniff`；每次下载记入 `sys_reveal_record`（`kind = FILE`）；`input_summary` 中没有内容（`GeneratedFileIT`）。
- [x] 没有 `file.generated.read` 403；缺任何一项保存时声明的权限、未知或格式不对的 id 404；读者不能直接运行 `FILE_ARCHIVE`（`GeneratedFileIT`）。
- [x] 经业务流程调用时，非文本类型、不合规的文件名、空文件、没有权限或权限为 `*` / 空白、对象过长、缺少输入都是 422，不保存；直接调用时输入先经校验（400）（`GeneratedFileIT`）。
- [x] 存档被改动时不返回、不记录下载；表上的 UPDATE / DELETE 被数据库拒绝（`GeneratedFileIT`）。
- [x] 业务流程（没有 `file.generated.archive` 的操作人）经子流程保存，CSV 防公式注入（`GeneratedFileIT`）。
- [x] 现有全部检查照常通过。

### 14l 封存校验在表多时不再停住（修复，0.5 天）

finance F10b（FIN-SCN-12）中 `INTEGRITY_VERIFY` 一直不返回：它边读"有封存行的表"的列表，边在同一连接上为每张表查询，
列表超过一次预取（32 行）时，列表等表的查询，表的查询等列表读完，事务停在 `idle in transaction`。示范应用的只追加表不到 32 张，没有暴露。

**要求**
1. runtime：`VerifySeals` 先读完表的列表（有封存行的表、只追加表）再逐表查询；`SealRows` 同样先读完只追加表的列表。
2. 测试：80 张只追加表的封存与校验（完好；改一行后只报该行）。

**验收标准**
- [x] 80 张只追加表封存、校验完好，改动的行被报告（`IntegrityIT.manyTablesAreSealedAndVerified`；修复前该测试超时）。
- [x] 现有全部检查照常通过。

### 14o 业务流程的命名锁（0.5 天）

finance F11 需要过账与关账按期间串行：过账读到期间开放后、提交前，关账可能已经提交，使已关期间多出一笔。
平台内部已有事务级咨询锁（账本科目层级、审批、导入），业务流程拿不到（业务不能写 SQL 与 `StepHandler`）。

**要求**
1. runtime：平台步骤 `HoldLock.shared(ctx -> 名称)` / `HoldLock.exclusive(ctx -> 名称)`，持有到根流程事务结束；名称为空不取；名称带前缀（06 §4.1）。
2. 等待有上限 `jabiz.process.lock-timeout`（缺省 30 秒）；超时与死锁（SQLSTATE `55P03`、`40P01`）由存储引擎译为 `ConcurrentUpdateException`（409）。
3. 测试：独占者使同名的共享与独占都等待，提交后依次进行；共享者并行而独占者等待；不同名称与空名称不等待；回滚即释放；子流程的锁持有到根流程提交；
   等待超时为 409。等待以 `pg_locks` 中未授予的咨询锁判断，不靠时间猜测。

**验收标准**
- [x] 以上六种情形（`HoldLockIT`；去掉锁时其中的等待断言失败）。
- [x] 现有全部检查照常通过。

### 14p 流程中的读取不截断（修复，0.5 天）

finance F11c 在 NF-001 的数据量（600 个科目）上关账时，应付账龄检查与控制科目对不上：流程中的 `RunTemplate` 按浏览分页读取，被模板各数据视图的
`maxQueryBatchSize`（账本 500）截断，而不报错地返回前 500 行。同样的截断也在 `QueryEntities`（按数据视图的一页）与 SQL 字典中。决策 D32（取代 D10 第 2 条中行数上限的一句）。

**要求**
1. runtime：`RunTemplate` 读取全部行（`AdvancedQueryExecutor.all`）；`QueryEntities` 返回查询自己的 `limit` 那么多行（`DatasetEntityManager.queryAll`，
   core `QueryCompiler.compile(..., capped)`）；SQL 字典读取全部条目；平台内部按输入取数的读取（业务参数、账本维度值、待办、职责分离规则）也不按一页截断。三者共用 `jabiz.process.max-read-rows`（缺省 100,000），超过即 422 `PROCESS_READ_TOO_LARGE`，
   不取一部分（消息三种语言），按实际行数而不是请求的 `limit` 判断；上限不合理时启动失败。场景回放的模板期望也读全部行。
2. 测试：每页 5 行的数据视图，流程中模板读到 7 行、8 行，实体查询按 `limit` 读到 7 行、8 行；上限 8 时模板 9 行被拒绝，`limit` 1000 而只有 1 行照常、有 9 行被拒绝。

**验收标准**
- [x] 以上情形（`TemplateReadsIT`；改回分页读取后，模板与实体查询的用例都失败）。
- [x] 字典、场景回放照常（`DictionaryRegistryIT`、`ScenarioTest`）；现有全部检查照常通过。（已合入）

### 14q 查询在负载下的行为（修复，0.5 天）

finance F11c 在 1/10 数据量上以 50 个用户压测时，大量请求 500、过账 p95 十几秒：查询超时只在应用一侧生效，超时后数据库中的查询仍跑完、占着 CPU 与连接
（连接带着未完成的查询回到池中，日志中大量 `request queue limit is exceeded`），过载因此越来越重；超时返回不带说明的 500；
模板与数据视图查询每页都另跑一遍 `count(*)`，重报表的成本翻倍。

**要求**
1. runtime：流程事务之外的读取在自己的短事务中以 `SET LOCAL statement_timeout` 执行，由数据库在时限中止；应用一侧的时限晚 1 秒兜底；
   数据库的取消（57014）同为超时。流程事务中的读取不变。
2. 超时返回 503 `QUERY_TIMEOUT`（消息三种语言），不记为错误日志；通用后台不自动重试 503。
3. 一页未取满（且不是越过末尾的空页）时总数即偏移量加本页行数，模板与数据视图查询都不再执行计数查询。
4. 文档：03 §1、§3，05 §5.1。

**验收标准**
- [x] 超过时限的模板查询在时限后不久得到 503 `QUERY_TIMEOUT`，数据库中不再有该语句在运行（`QueryLoadIT`；改回只在应用一侧计时，该用例失败）。
- [x] 模板与数据视图：页未取满时不执行计数语句，满页与越过末尾的空页照常计数，总数都正确（`QueryLoadIT`）。
- [x] 现有全部检查照常通过。
- 已知限制：流程事务中的读取在数据库中仍没有时限；每次事务外读取多三个往返；满页时计数在取页之后进行。

### 14m 前端无障碍检查（0.5 天）

finance F11（FIN-UI-009，WCAG 2.2 AA）需要通用后台的页面通过自动无障碍检查，并让应用的扩展能用同一工具检查自己的页面。

**要求**
1. frontend 开发依赖 `@axe-core/playwright`；e2e `a11y.spec.ts` 以 WCAG 2.2 A/AA 的规则检查主要页面，违规即失败，不设排除项。
2. 修复查出的问题：Ant Design 默认色的对比度（主色、错误色、说明与三级文字、预置标签）、展开行表格的展开按钮没有名称、表单项中 antd 下拉外层的 `aria-required`。
3. 文档：12 §11。

**验收标准**
- [x] 登录页与登录后的 15 个页面（数据目录、列表、新建表单、历史、流程目录、两个流程表单（含必填选择）、待办、报表目录与报表、导入目录、审计、封存、保留、安全设置）
  axe 检查无违规（`e2e/a11y.spec.ts`）；修复前查出对比度、`button-name`、`aria-allowed-attr` 三类问题。
- [x] 现有 Vitest 与全部 e2e（34 个）照常通过。
- [x] 补充（14m-2，finance F11e 的 axe 检查发现）：成功与警告文字（2.3、1.8:1）、表格以文字给出的空状态（1.8:1）的对比度。
- [x] 补充（14m-3）：14m-2 加深成功、警告色后，派生的标签背景随之变深（成功标签文字 2.7:1），背景与边框固定为默认的浅色。
- [x] 补充（14m-4）：警告标签的文字 `#ad6800` 在其浅色背景上只有 4.24:1，警告色再加深一档为 `#874d00`。
- 已知限制：自动检查不能代替审计（键盘顺序、焦点可见、屏幕阅读器朗读等需人工）；只检查了中文界面与浅色主题；表单校验失败后的状态未单独检查；
  `AccessibleSelect` 去掉 `aria-required` 后，必填只由标签的星号（CSS）与校验消息表达，没有程序可读的必填状态。

### 14n Webhook（1 天）

finance F11 的 FIN-DI-007（Should）：把发票过账等事件以 webhook 通知订阅的系统，失败重试。决策 D33，设计 11 §2.4。

**要求**
1. core `WebhookSignature`（HMAC-SHA256，`v1=` + hex，常量时间比较）。
2. runtime：`jabiz.webhooks`（白名单、超时、订阅：名称、事件类型、地址、来自环境变量的密钥）；每个订阅注册为一个 Outbox 消费者，执行内部流程
   `WEBHOOK_DELIVER`（只在平台投递中执行，事件从 Outbox 读；步骤 `SendWebhook`：异步 POST、签名请求头、整次交换的超时、2xx 送达、其他失败由 Outbox 重试，
   不跟随重定向）；Outbox 每个消费者的一轮在第一次失败处结束；观测 `jabiz.webhook.delivery`。
3. 启动检查 `WEBHOOK`：名字、事件类型、密钥长度、https（http 只限本机）、不带用户信息、主机在白名单中。
4. 文档：09 D33、11 §2.4、CLAUDE.md。

**验收标准**
- [x] 订阅的事件以签名的 POST 送达，每个订阅一次；接收方失败时该订阅的尝试被记录、稍后重送，另一个订阅不重复收到（`WebhookIT`）。
- [x] 管理员（全部权限）经接口执行 `WEBHOOK_DELIVER` 得到 403、接收方什么也没收到（`WebhookIT`）。
- [x] 不在白名单的主机、http 外部地址、带用户信息的地址、短密钥、重名都在启动检查中一次报告；密钥不出现在 `toString()`（`WebhookChecksTest`）。
- [x] 签名可由接收方验证，时间戳、正文、密钥任一不同即不符（`WebhookSignatureTest`）。
- [x] 现有全部检查照常通过。
- 已知限制：投递在消费流程的事务中进行（每次最长占用一个连接到超时 5 s）；不检查主机解析到的地址（白名单是唯一的控制）；
  新订阅会收到该类型的历史事件；订阅只能经配置与重启增减；遮蔽字段不会自动从载荷中遮蔽（由发布者负责）。

### 14r 显示用户名（0.5 天）

演示中发现：页头、待办标题、审计与历史显示用户编号。设计 10 §14、18 §5.2。

**要求**
1. `UserNames`（编号 → 显示名；多租户时只含本租户与无租户的用户）；`GET /api/users/names`（只要求已认证，至多 200 个）；`/api/auth/me` 返回本人的显示名（无则用户名）。
2. 审批待办的标题：对象用消息 `approval.subject.<名>`，单据用 `ApprovalCase.reference(…)`（迁移 V30：请求的 `reference` 列），各层级都用。
3. 前端：`UserName` / `useUserName`（合并请求，取不到时显示编号），经 `@jabiz/admin` 提供；页头、审计、访问审查、历史、操作详情、封存校验都用它。
4. 示范：`commerce.certification` 的对象名。

**验收标准**
- [x] 已登录用户按编号取得显示名，不认识的编号与非编号没有条目，没有显示名的用户不给出登录名，超过 200 个的部分忽略，未登录 401；
  `/api/auth/me` 带显示名，没有显示名时为本人用户名（`UserNamesIT`）。
- [x] 审批待办标题为"Approve IT payment PAY-… (level 1)"，第二级仍用同一单据标识；日文标题用对象的日文名（`TaskIT`）。
- [x] 同一页面的多个 `UserName` 只发一次请求、超过 200 个分批，系统操作人与失败时显示编号（`UserName.test.tsx`）；通知邮件的标题同样用对象名（`TaskTitles`）。
- [x] 现有全部检查照常通过。
- 已知限制：导出、PDF 与签发的报表仍是编号；元数据生成的数据页面中的用户编号字段（如审批请求的准备人）仍显示编号；没有显示名的用户对别人显示编号
  （不给出登录名）；改名后旧页面在刷新前显示旧名（名字按会话缓存）。

---

## 阶段 15：后台前端改用 shadcn/ui（线 1.2）

决策 D34。目标：通用后台不再依赖 Ant Design；平台前端拆出 `@jabiz/client`（无界面）与 `@jabiz/ui`（shadcn 组件与组合组件），供后台、扩展与应用自有 SPA 共用。
现状（迁移的范围）：`frontend/src` 中 62 个文件、约 6 700 行引用 antd / ProComponents / icons；`api/*`、`auth/AuthContext`、`useIdleLock`、`oidc`、`i18n/*`、
`lib/*`、`meta/*`（`columns.ts` 除外）已不依赖 antd。

| 子阶段 | 内容 | 预估 |
|---|---|---|
| 15a | 基础：工作区与两个包、Tailwind v4 与主题、shadcn 基础组件、组合组件（数据表格、表单字段、日期、金额、确认、提示）、页面外壳、step-up 对话框、检查脚本 | 5–7 天 |
| 15b | 元数据生成的页面（15b-1 / 15b-2）：登录与二次验证、单点登录回调、数据视图目录与列表、表单抽屉与字段、子实体列表、文件字段、历史、流程目录与表单、行操作 | 7–9 天 |
| 15c | 其余页面：待办、报表与存档、单据、导入、审计、访问审查、防篡改、保留与导出、账户安全；示范扩展 | 5–7 天 |
| 15d | 删除 antd、ProComponents、icons 与 React 19 补丁；e2e 与 a11y 选择器全部改为角色 / 名称；文档（12、`guide/admin-extension.md`、功能清单） | 2–3 天 |

### 15a 基础（5–7 天）

**要求**
1. **工作区**：`frontend/` 成为 pnpm 工作区（`pnpm-workspace.yaml`：`packages/*`），新增 `packages/client`（`@jabiz/client`）与 `packages/ui`（`@jabiz/ui`），
   均以源码导出（`package.json` 的 `exports` 指向 `src/index.ts`），平台前端以 `workspace:` 依赖它们。一个 lockfile 不变。
2. **`@jabiz/client`**：从 `src/` 迁入 `api/client.ts`、`session.ts`、`problem.ts`、`files.ts`、`auth/AuthContext.tsx`、`useIdleLock.ts`、`oidc.ts`、`i18n/*` 的初始化部分、
   `lib/calls.ts`（`runQuery` / `runProcess`）、`meta/format.ts`、`decimal.ts`（格式化）与 `UserName`；金额格式化接受应用给出的显示单位。
   平台前端原位置改为从包中引用；行为不变（现有测试随文件迁移并照常通过）。
3. **`@jabiz/ui`**：
   - Tailwind CSS v4（`@tailwindcss/vite`）；`theme.css` 定义 shadcn 语义 token（亮色与 `.dark`），对比度按 WCAG 2.2 AA 选定（沿用 `ACCESSIBLE_THEME` 的取值依据）；
     `cn()`（`clsx` + `tailwind-merge`）；`components.json`（shadcn CLI 只在此包中运行）。
   - shadcn 基础组件：button、input、textarea、label、checkbox、radio-group、switch、select、dialog、sheet、dropdown-menu、popover、tooltip、tabs、badge、card、alert、
     separator、skeleton、table、pagination、command（组合框）、calendar、form（react-hook-form）、sonner、sidebar、breadcrumb、avatar、scroll-area、chart（Recharts）。
   - 组合组件：`DataTable`（TanStack Table；远程分页 / 排序 / 筛选由调用方控制；可展开行带"展开行 / 收起行"名称的按钮；空状态文字对比度达标）、
     `ConfirmDialog`、`notify`（sonner 封装，取代 `App.useApp().message`）、`DateTimePicker` / `DatePicker`（react-day-picker，按界面语言与区域）、
     `DecimalInput` / `MoneyInput`（字符串十进制，复用 `meta/decimal`）、`ThemeToggle`（亮 / 暗 / 跟随系统，存于 `localStorage`，失败时退回跟随系统）、`PageHeader`、`AppShell` 积木。
   - 文案经 i18next（命名空间 `ui`，三语）。
4. **共存**：迁移期间 antd 页面照常工作。Tailwind 的 preflight 只作用于新组件的作用域（`@layer` 顺序 + 作用域类），15d 删除 antd 后改为全局；
   新组件不依赖 antd 的 `ConfigProvider`，antd 页面不受 Tailwind 影响（以视觉回归截图对比登录页、列表页、表单抽屉确认）。
5. **页面外壳**：`layout/AppLayout.tsx` 由 ProLayout 改为 `@jabiz/ui` 的侧栏外壳（菜单树、任务徽标、数据期限标签、语言与外观切换、账户菜单）；
   `auth/StepUp.tsx` 改为 `Dialog`。`extension/registry.ts` 不再返回 ProComponents 的 `MenuDataItem`，改为平台自己的菜单类型；`ExtensionMenuItem.icon` 改为 lucide 图标组件。
6. **`@jabiz/admin`**（D22）：在两个包之上重新导出；增加 `@jabiz/ui` 的组件（扩展只经 `@jabiz/admin` 引用，规则不变）；扩展 lint 不再允许直接引用 `antd`（15c 前示范扩展豁免）。
7. **应用 SPA 的检查脚本** `pnpm app:check <目录>`：比对应用 `package.json` / lockfile 中 `react`、`react-dom`、`tailwindcss`、`@tanstack/react-query`、`i18next`
   以及 `@jabiz/ui` 运行时依赖的版本与平台一致，不一致即失败；附测试。
8. **构建**：`BootAppPlugin` 的 `spaBuild` 输入加入 `packages/**` 与 `pnpm-workspace.yaml`；CI `frontend` 作业照旧（工作区由 `pnpm install` 一并安装）。

**改动**：`frontend/package.json`、`pnpm-workspace.yaml`、`packages/{client,ui}/**`、`vite.config.ts`（Tailwind 插件、dedupe）、`tsconfig*.json`（包路径）、`src/index.css`、
`src/App.tsx`（外观提供者、`Toaster`）、`src/layout/AppLayout.tsx`、`src/auth/StepUp.tsx`、`src/extension/registry.ts`、`src/lib/index.ts`、`scripts/extension-lint.mjs`、
`scripts/app-check.mjs`、`backend/build-logic/.../BootAppPlugin.kt`、`e2e/support.ts`（外壳的选择器）。无后端改动、无迁移。

**测试**
- `@jabiz/ui`：每个组合组件的 Vitest（渲染、键盘操作、名称与角色）；以 `vitest-axe` 对每个组件在亮、暗两种外观下检查，任何违规即失败。
- `DataTable`：远程分页 / 排序回调、展开行按钮的名称、空状态。`ThemeToggle`：三种取值、`localStorage` 不可用时的退回。
- `@jabiz/client`：迁入的现有测试全部通过（刷新、step-up、闲置锁定、OIDC、格式化）；金额显示单位。
- `app:check`：版本一致通过、任一不一致失败。
- 外壳：菜单树（服务端菜单 + 扩展 + 固定项按权限）、任务徽标、语言与外观切换、闲置锁定（替换 `AppLayout` 的现有测试）。
- e2e：现有全部 Playwright 用例通过；`a11y.spec.ts` 增加暗色外观下的同一组页面。

**风险**：Tailwind preflight 与 antd 样式冲突（以作用域 preflight 与截图对比控制）；共存期间包体积增大（15d 后回落，记录 15a 前后的产物大小）；
shadcn 组件是源码，升级需手工合入（记在 `packages/ui/README.md`）。

**验收标准**
- [x] `frontend/` 为 pnpm 工作区，`@jabiz/client`、`@jabiz/ui` 可被平台前端与示范扩展引用；`pnpm lint`、`typecheck`、`test`、`build`、`check:api`、`ext:check` 全部通过。
- [x] 后台外壳与 step-up 对话框已不依赖 antd；其余页面仍为 antd 且功能不变（现有 e2e 全部通过：35 个用例）。
- [x] 组合组件在亮、暗两种外观下 axe 无违规；后台主要页面在暗色外观下 axe 无违规（a11y.spec.ts 对同一组页面与外壳的菜单在暗色外观下再查一遍，整页无违规）。
- [x] `pnpm app:check` 能发现版本不一致。
- [x] `./gradlew check` 通过（含 `:app:bootJar` 的前端构建）。

**状态**：☑ 已完成（分支 `1.2/phase-15a-ui-foundation`，PR 待建）。

**与计划的出入**
- shadcn 组件没有经 CLI 生成：构建环境访问不到 shadcn 的 registry（ui.shadcn.com，代理拒绝），按其 new-york（Tailwind v4）源码在 `packages/ui` 中手写；
  `components.json` 已备好，今后在该目录用 CLI 合入，对上游的修改列在 `packages/ui/README.md`。另加了 `alert-dialog`（`ConfirmDialog` 的基础）。
- 共存的层顺序：除作用域 preflight 外，antd 的样式经 `StyleProvider layer` 放入 CSS 层 `antd`（`theme, antd, base, components, utilities`），
  否则 antd 的全局 `a { color }` 会盖过侧栏链接的颜色（暗色下对比度 3.1:1）。为此加了依赖 `@ant-design/cssinjs`（antd 已带的同一版本，15d 删除）。
- antd 页面所在的内容区固定为亮色 token（`light`，底色 `muted`，与 ProLayout 的灰底相近），暗色外观只作用于外壳与弹出层；`dark:` 变体与 `color-scheme` 都不作用于 `.light` 之内。
- preflight 的作用域：不是给内容区加 `.jabiz-ui`（那会重置 antd 页面里的普通元素，且 `base` 层排在 `antd` 层之后会盖过 antd 的组件样式），
  而是让 `@jabiz/ui` 组件画出的每个元素都带 `.jabiz-ui`，生成的选择器也匹配带该类的元素本身；扩展自己的普通 HTML 元素需放进 `UI_SCOPE` 容器（见扩展指南）。
- 侧栏收起时移出屏幕并设为 `inert`，不收成图标条（服务端菜单没有图标、分组在图标条中无法展开）。
- `setAmountUnit(币种, 标签)`：只改变该币种金额的显示（如 JPY 显示为 "Kudos"），不影响无币种的数字与其他币种；`app:check` 另查 `react-i18next`，以及使用 `@jabiz/client` 时其运行时依赖（`dayjs`、`openapi-fetch`）。
- `DropdownMenu` 默认非模态：模态菜单把页面其余部分设为 `aria-hidden` 而仍可聚焦，axe 报 `aria-hidden-focus`。
- 迁入 `@jabiz/client` 的模块在原位置保留只做重新导出的文件，原有页面与测试（含 `vi.mock` 的路径）不变；15b–15d 随页面迁移去掉。
- 版本：新依赖固定为发布至少数周的确切版本；`@tanstack/react-table` 用 8.21.3、`react-day-picker` 用 9.14.0（各自的新大版本发布不久，API 有变），
  `recharts` 需要的 `react-is` 固定为 19.3.0（与 React 一致）。
- Vitest 的单个用例时限放宽到 15 秒（页面测试在负载下接近默认的 5 秒）。
- 视觉回归以截图人工对比：登录页逐像素一致；列表页与表单抽屉中 antd 的部分外观不变，外框改为新侧栏（ProLayout 的面包屑随之没有了，见已知问题）。

**产物大小**（`vite build`，未压缩 / gzip）：15a 前 JS 2 651.0 kB / 819.2 kB、CSS 0.3 kB；15a 后 JS 2 735.9 kB / 846.6 kB、CSS 73.5 kB / 12.5 kB（共存期间的增量，15d 删除 antd 后回落）。

**已知问题**
- ProLayout 给 `PageContainer` 的面包屑（数据 / 承运商）在新外壳中不再显示；15b 以 `PageHeader` 的面包屑取代。
- 暗色外观下 antd 页面仍是亮色（内容区固定为亮色 token），15b、15c 迁移后才随外观变化。
- jsdom 不计算样式，组件测试中的 axe 不查颜色对比度；对比度由 `theme.test.ts`（token）与 e2e 的 a11y 检查（浏览器中）覆盖。
- step-up 对话框（Radix `Dialog`，模态）没有 e2e 的 axe 检查（需要开启二次验证的会话）；组件测试中的 axe 无违规。
- 内容区里的 `@jabiz/ui` 组件打开的弹出层（选择框、菜单、日期）渲染在 `body` 下，暗色外观时是暗色，而所在页面是亮色；15b、15c 页面迁移、取消亮色固定后消失。
- antd 组件若放进 `@jabiz/ui` 组件之内（如 `Dialog` 里放 antd 表单），会受 preflight 影响；迁移期间不要这样混用。

### 15b 元数据生成的页面（7–9 天，分 15b-1 / 15b-2 两个 PR）

依据：决策 D34；12 §5、§6、§11、§12；15a 的出入与已知问题。目标：元数据生成的页面不再用 antd、ProComponents 与 icons，行为不变；这些页面在暗色外观下随外观变化（取消对它们的亮色固定）；e2e 与 a11y 中涉及这些页面的 `.ant-*` 选择器改为角色、名称或稳定的 `data-*`。比原估 5–7 天多的部分：给 15c 共用的组合组件、可键入的日期控件、e2e 选择器的迁移。

**现状**（`frontend/src`，22 个文件、约 2 360 行）：`LoginPage`（`LoginForm`、`ProFormText`）、`MfaEnrollment`（15c 的账户安全页也用）、`OidcCallbackPage`、`DatasetCatalogPage` / `ProcessCatalogPage`（本地数据的 `ProTable`）、`DatasetListPage`（`ProTable` 远程分页 / 排序 / 筛选与自动查询表单、回看时间、`Popconfirm`）、`meta/columns.ts`（`ProColumns`；15c 的报表页也用）、`EntityFormDrawer` / `EntityField`（`DrawerForm`、`ProForm*`）、`SchemaInputs`（流程表单；15c 的报表与导入页也用）、`ReferenceSelect`（远程查找 `Select`）、`I18nTextInput`（`Tabs`）、`FileField`（`Upload`）、`FilePreview`（`Image`）、`MaskedValue`、`FieldErrors`（借用 antd 的类名）、`ChildEntityList`、`EntityHistoryPage` / `HistoryTimeline` / `OperationDrawer`、`ProcessFormPage`、`RowActions`。列表页、表单抽屉、子实体列表、流程表单、历史页、操作详情与两个目录页目前**没有**单元测试。e2e 中约 10 个规格用到这些页面的 `.ant-*` 选择器。本阶段所需依赖都已在 15a 的包中，**不加依赖**。

**拆分原则**：同一棵弹出层子树一次迁完——Radix 弹出层 `z-50`，antd 抽屉 / 对话框 `1000`，把新的下拉或确认框放进 antd 抽屉会被盖住。

**共用部分的归属**（15b 与 15c 并行时以此为准）：新的组合组件全部在 15b-1 加入，15c 在其后开工；`MfaEnrollment` 由 15b-1 迁移；新建 `SchemaForm`（15b-1），旧 `SchemaInputs` 由 15c 迁完报表与导入页后删除；`buildColumns` 的无界面部分移到 `meta/cells.ts`、新增 `meta/dataColumns.tsx`（15b-2），旧 `buildColumns` 由 15c 删除；`FilePreview`、`MaskedValue` 由 15b-2 迁移；审批面板、单据面板、`expandIcon`、`AccessibleSelect` 归 15c。

#### 15b-1（3.5–4.5 天）：组合组件、登录、单点登录回调、目录、流程表单、历史

**要求**
1. **`@jabiz/ui` 新组合组件**（通用，不认识元数据；元数据适配留在平台前端，经 `@jabiz/admin` 导出；只用 token；`ui` 命名空间三语；带 `UI_SCOPE`）：
   - `DatePicker` / `DateTimePicker` 可**键入**（本地时间 `YYYY-MM-DD HH:mm:ss` / `YYYY-MM-DD`，失焦或 Enter 解析，无效值不提交并设 `aria-invalid`），`DateTimePicker` 加 `disabledDays` / `toDate`，日历标题可按年月选择；输入框有 `id` / `aria-label`，`getByLabel(...).fill(...)` 照常可用。
   - `Combobox`（Popover + cmdk；静态或远程模式、清除；`role="combobox"`、`aria-expanded` / `required` / `invalid`，选项 `role="option"`）。
   - `FilterBar`（文本、选项、数值区间、时间区间、日期区间；区间为以标签命名的 `group`；"查询""重置"，Enter 提交）。
   - `FileUpload`（真实的 file 输入；上传前检查类型与大小；上传中状态；`role="alert"` 的错误）。
   - `Timeline`、`DescriptionList`、`TagsInput`、`CopyButton`、`PageState`（加载 / 不存在 / 出错 / 警告，取代 `Spin` 与 `Result`）、`Spinner`。
   - `DataTable`：数据行带 `data-slot="data-table-row"`；列可固定在右侧；列的 `className`。
2. **外壳**：路由 `handle: { ui: 'jabiz' }` 标出已迁移的页面，这些页面不再固定为亮色（15d 去掉此开关）；新增 `AdminPage`（`UI_SCOPE` + `PageHeader` + 面包屑），取代 `PageContainer`；面包屑：数据 › 实体（› 历史）、流程 › 流程名（修复 15a 已知问题）。
3. **登录与二次验证**：react-hook-form；占位符、`autoComplete`、三个步骤、闲置锁定提示、预填用户名、`from` 跳转、回调带来的步骤都不变；恢复码用 `CopyButton`；全部测试 id 保留；按钮文字"登录""验证"。
4. **单点登录回调**：`PageState` 加载与警告；授权码只送一次不变。
5. **两个目录**：`DataTable` 本地分页（每页 50，只有一页时不显示分页）；`CopyButton`、`Badge`；测试 id 与空状态文案不变。
6. **流程表单**（`ProcessFormPage` + 新 `SchemaForm`）：各种输入（密码、可键入的时间与日期、十进制 / 整数 / 数字、开关、枚举、标签、对象分组、可增删的列表、JSON）；校验只保留原有的必填与 `pattern`，写成纯函数 `checkInputs`，不引入新校验库；数字输入转为 JSON 数字；预填只读；`Idempotency-Key` 打开时生成、失败不变、成功更换；step-up 重试同键（由 `@jabiz/client` 完成）；违规列表与结果的测试 id、字段标签不变。
7. **历史**：两列布局；`Timeline`（动作标签颜色集中一处，预定与墓碑标记不变）；时间点用两个可清除的 `DateTimePicker` 并写入 URL；结果用 `DescriptionList`，与现在不同时标记；404 视为"那时不存在"；操作详情用 `Sheet`；撤销用 `Dialog`（原因必填）；按钮的出现条件与权限不变。

**15b-1 状态**：☑ 已完成（分支 `1.2/phase-15b1-ui-components`，PR 待建）。

**15b-1 与计划的出入**
- react-hook-form 由 `@jabiz/ui` 再导出（`useForm`、`useFieldArray`、`useWatch`、`Controller`，`src/form-state.ts`）：平台前端与扩展不另加依赖。
- 日期控件改为"文本框 + 打开日历的按钮"：`id` / `aria-*` / `data-testid` 落在文本框上，原来按钮上的名称移到文本框（扩展中用 `getByRole('button', …)` 找日期字段的要改）。
  Enter 读取键入的值且不提交外围的表单（与选择器确认一致），再按一次 Enter 才提交；`FilterBar` 中同样如此。
  日期以应用的区域格式显示与键入（D22 第 7 条；en-US 为 `MM/DD/YYYY, hh:mm:ss AM`，占位符为该格式），ISO 形式在任何区域都可键入且先读：以四位年和连字符开头即为年-月-日；
  区域格式按该区域年月日的顺序读，年须为四位。不是日期（或不允许的日子）的键入文字留在框中、`aria-invalid`、框下说明原因，并经 `onInvalidChange` 告知调用方：
  `FilterBar` 与 `SchemaForm`（`checkInputs` 的 `typedInvalid`）在有这样的字段时不提交；重置（`resetKey`）丢弃这些文字。
  弹出层中的时间框清空或只输入一半时时间不变，只给出时与分时保留秒，禁用的日子不能经时间框选中。
- `TagsInput`：输入法组字时的 Enter 不加标签；全角逗号 `，` 与顿号 `、` 也作分隔。
- 界面设置减少动态效果（`prefers-reduced-motion: reduce`）时，`@jabiz/ui` 的弹出层没有淡入与滑动；`a11y.spec` 在 axe 检查前等有限的动画结束（菜单淡入中的混合颜色曾使暗色检查偶发失败）。
- 流程表单：必填的布尔值默认"否"（原来未拨动的开关报 `REQUIRED`）；预填的行键为只读（`readonly`）而非禁用，`content.spec` 改为断言不可编辑；
  必填的标签输入为空数组时报 `REQUIRED`，不必填的空标签不发送。
- 历史页：两个时间点都清除后不再把当前状态显示为"时间点的状态"（原有的缺陷：查询键与当前状态相同）。
- `FilterBar` 的选项字段用 `Combobox`（可清除、可搜索），不用 `Select`。
- e2e：`a11y.spec` 的登录页检查拆为亮、暗两个用例（共 36 个用例），并加查必填提示、身份提供方的拒绝、历史页的日历 / 操作详情 / 撤销对话框、流程表单展开的选项。
- `SchemaForm` 已就绪；旧 `SchemaInputs` 仍供报表与导入页（15c 删除）。`processForm.toProcessInput` 同时接受 Dayjs（旧表单）与文本（新表单）。

**15b-1 产物大小**（`vite build`，未压缩 / gzip）：之前 JS 2 733.4 kB / 845.4 kB、CSS 74.6 kB / 12.5 kB；之后 JS 3 001.3 kB / 924.8 kB、CSS 79.0 kB / 13.2 kB。
增量主要是此前被摇树去掉、现在用到的 react-day-picker（含 date-fns）、cmdk、react-hook-form 与新组件；超过 3 000 kB 的提示只是警告，15d 删除 antd 后回落。

**15b-1 已知问题**
- 已迁移页面中由 15b-2 / 15c 负责的部分仍是 antd（列表页、表单抽屉、账户安全页内嵌的 `MfaEnrollment` 所在的 antd 页仍固定亮色）。
- jsdom 不计算样式，组件测试的 axe 不查对比度；对比度由 e2e 的 a11y 检查覆盖。

#### 15b-2（3.5–4.5 天）：列表页这棵子树

**要求**
8. **列表**（`DatasetListPage` + `meta/dataColumns.tsx`）：TanStack Query 保留上一页数据；请求体由 `buildFilters`、`buildSorts`、分页、`asOf` / `knownAt` 组成（`buildSorts` 增加 `SortingState` 重载，白名单检查不变）；每页 10 / 20 / 50 / 100；默认排序；查询与重置回到第 1 页；只有白名单内且可比较的列可排序；筛选栏只含白名单字段，不含敏感字段，遮蔽字段只给持有权限者；回看时只读并提示；操作列固定在右侧（编辑 / 查看、行操作、删除确认、历史、审计）；引用显示名、多语言回退、文件缩略图、遮蔽值"显示"仍经 `renderCell`。
9. **表单抽屉**（`EntityFormDrawer`、`EntityField`、`FieldErrors`）：右侧 `Sheet`；react-hook-form 保存值（`toFormValue` 改为返回字符串）；**校验仍只走 `meta/validation.ts`**（输入时查改动的字段；新建查全部、修改查改动的；什么都没改时提示）；服务端 400 / 422 违规回填到字段并带 `aria-describedby` 与规则码，409 提示冲突；各语义类型对应的控件（十进制不传小数位、不截断不取整，金额显示币种）；只读规则（`processOnly`、修改时不可变字段与主键、无权限的遮蔽字段、预填、只读模式）；时态实体的生效时间（不允许预定时不能选未来）与原因；回看时无操作与子实体。
10. **字段组件**：`ReferenceSelect` 用远程 `Combobox`（250 ms 防抖、当前值显示名、按语言回退）；`I18nTextInput` 用 `Tabs`（必填与出错的语言标记、Markdown 预览）；`FileField` 用 `FileUpload`（按策略上传、超限不上传、替换与移除）；`FilePreview` 点击用 `Dialog` 放大，非图片为下载按钮，`blob:` 卸载时释放；`MaskedValue` 每次点击请求服务端、明文只在组件状态。
11. **子实体列表与行操作**：`Card` + `DataTable`（每页 5、按父引用筛选、新建时预填引用、无写权限只读，嵌套 `Sheet`）；`RowActions` 用 `ConfirmDialog`，每次执行新的幂等键。
12. **`@jabiz/admin` 契约**：`EntityFormDrawer` 的 props 不变（现为 Radix 模态 `Sheet`，不再需要 antd 的 `App`）；`ReferenceSelect` 增加 `id` / `aria-*` / `placeholder`；`FieldErrors` 增加可选 `id`；新组合组件随 `@jabiz/ui` 一并导出；变化写入 `docs/guide/admin-extension.md`。
13. 文案：`ui` 命名空间新增筛选、组合框、上传、复制、标签、页面状态、日期的键（三语）；"查询""重置""确定""保存""执行"用词不变。

**改动**：`packages/ui` 新增 9 个组件（各配测试）并修改日期与表格组件；平台前端 22 个文件、新增 `SchemaForm`、`AdminPage`、`meta/cells.ts`、`meta/dataColumns.tsx`，修改 `meta/columns.ts`（只剩给 15c 的旧部分）、`kinds.ts`、`listQuery.ts`、`processForm.ts`、`i18n/resources.ts`、`lib/index.ts`、`App.tsx`、`AppLayout.tsx`；`e2e/support.ts` 新增按角色的辅助函数（对话框、数据行、确认、选项、区间、排序、页面已渲染），相关规格改用它们；文档 12 §5 / §6 / §11 / §12、扩展指南、ROADMAP。无后端改动、无迁移、无新依赖。

**测试**
- `@jabiz/ui`：每个新组件的 Vitest，并在亮、暗两种外观下 axe 检查；日期键入（有效、无效、范围、Enter、本地时间转 UTC、日期不经时区）；`DataTable` 的行标记与固定列。
- 平台前端：已有测试按新结构改写、断言的行为不变；新增两个目录页、`SchemaForm`、`ProcessFormPage`（幂等键、step-up 同键重发、违规、结果）、`EntityHistoryPage` / `OperationDrawer`、`DatasetListPage`（分页、只限白名单的排序与筛选、遮蔽字段筛选只给有权限者、查询回第 1 页、回看只读、删除后刷新、引用显示名、按权限的操作）、`EntityFormDrawer`（新建与修改发送的内容、清空发 `null`、时间按毫秒比较、只读规则、生效时间与原因、违规回填、409、只读与回看模式）、`EntityField`、`ChildEntityList`、`dataColumns` 的测试；`validation.test`、`validation.cases.test`、`entityForm.test`、`masked.test` 原样通过。
- 外壳：已迁移的路由不再固定亮色，其余照旧。
- e2e：现有 35 个用例全部通过；`a11y.spec.ts` 对本阶段的页面（含登录页）在亮、暗两种外观下检查，并在打开删除确认、引用下拉、日期弹出层后各查一次。

**风险**：弹出层层级（按子树拆分；15b-2 合入前 step-up 对话框弹在 antd 抽屉上仍会被盖住，15a 已如此）；修改 15a 的日期控件（区域、夏令时、秒与毫秒）；ProTable 的隐含行为容易漏掉（逐条写进列表页测试）；嵌套 `Sheet` 的焦点与 Escape（e2e 覆盖）；迁移期间两套实现并存（转换只有一份，旧的由 15c 删除，记入已知问题）；15b 与 15c 都改 `@jabiz/ui` 的导出与文案（组合组件先在 15b-1 合入）；产物大小在 15d 前继续增加（记录前后大小）。

**验收标准**
- [x] 22 个文件及新增的 `SchemaForm`、`AdminPage`、`cells`、`dataColumns` 不引用 antd 系包；`meta/columns.ts` 只剩给 15c 的旧部分并注明。
- [x] `@jabiz/ui` 新增 9 个组合组件，日期控件可键入并可限定范围；每个组件有 Vitest，亮、暗 axe 无违规。
- [x] "要保持的行为"逐条有 Vitest 覆盖并通过；`validation.cases.test` 不变且通过；不引入第二套校验库，没有新依赖。
- [x] 已迁移的页面在暗色外观下为暗色，未迁移的页面与扩展页仍为亮色；列表、历史、流程表单有面包屑。
- [x] e2e 35 个用例全部通过；涉及本阶段页面的地方不再使用 `.ant-*`。
- [x] `a11y.spec.ts`：本阶段页面在亮、暗外观下无违规，弹出层也检查过。
- [x] `@jabiz/admin` 导出的 `EntityFormDrawer`、`ReferenceSelect`、`FilePreview`、`FieldErrors` 不再依赖 antd，契约变化已写入扩展指南；`pnpm ext:check` 通过。
- [ ] 前端全部检查与 `./gradlew check` 通过；ROADMAP 记下产物大小与出入；12 已更新。

要保持的行为（逐条测试）：远程分页 / 排序 / 筛选只限白名单；回看写在 URL、回看时只读；遮蔽值每次请求服务端；多语言页签的必填、出错、回退与预览；文件按策略上传、预览释放、下载、移除为 `null`；引用显示名按页批量取；`processOnly` 只读不发送；生效时间与原因；只发送改动的字段；违规回填带规则码；step-up 后同键重试；二次验证绑定与恢复码；单点登录的授权码只送一次。

**15b-2 状态**：☑ 已完成（分支 `1.2/phase-15b2-list-pages`，PR 待建）。验收标准的勾选包括 15b-1 已满足的部分（9 个组合组件、登录与目录等页面）；
`./gradlew check` 未在本地完整运行（只运行了 `:app:bootJar`，含前端构建；本阶段没有后端改动），留给 CI。

**15b-2 与计划的出入**
- `MoneyInput` 的 `scale` 改为可选：表单中的金额"不传小数位、不截断不取整"，多出的小数位由规则报 `MONETARY_SCALE`，而原来的 `scale` 必填且会拒绝多键入的小数位。
- `DataTable` 的表头单元格加 `scope="col"`：没有它时 Playwright 把带排序按钮的 `th` 算作普通单元格，`getByRole('columnheader')` 找不到。
  列需要 `accessorFn` 才能排序（TanStack Table 只排序取值列），`dataColumns` 为每列给出；每列先升序（`sortDescFirst: false`），与原来 ProTable 相同。
- 时间点（`asOf` / `knownAt`）的两个 `DateTimePicker` 放在筛选栏上方（原来在表格工具栏），各有标签；页面标题由 `AdminPage` 的 `<h1 data-testid="page-title">` 给出。
- `FilePreview`：图片本身可点击放大，旁边另有"放大预览"按钮供键盘使用（图片放在按钮里时，按钮的内容不作为图片暴露，e2e 按 `img` 角色找不到）；放大图取最宽的变体，没有足够宽的变体时取（重新编码过的）原件。
- 子实体列表放在表单的 `<form>` 之外（同一个 `Sheet` 内），表单的提交事件另外 `stopPropagation`：React 的事件会穿过门户从子实体的 `Sheet` 冒泡到父表单。
- `MaskedValue` 若在请求明文期间值已改变，回来的明文仍会显示一次（评审第 7 条只处理已显示的明文）。
- 多语言文本的各语言输入框以"字段名（语言）"命名，字段标签命名整个 `group`；页签上出错的语言带 `data-invalid`。
- 路由表以 `appRoutes` 从 `App.tsx` 导出，供 `App.test.tsx` 检查哪些路由标为 `@jabiz/ui` 页面。
- e2e 辅助函数（`support.ts`）新增 `confirm`（确认对话框的"确定"）、`sortBy`（表头的排序按钮）、`entityForm`（`data-testid="entity-form"` 的表单 `Sheet`）；
  15b-1 已有的 `dataRows`、`option`、`rangeEnd`、`dialog`、`pageRendered` 照用。`a11y.spec` 为引用的选择先插入一个供应商（全新数据库中没有可选项）；登录后的两个检查标为 `test.slow()`（约 30 个页面与弹出层，在负载下超过默认的 1 分钟）。

**15b-2 评审后的修正**（各有测试，缺陷先写失败的测试）
1. 表单 `Sheet` 每次打开、或打开时换了另一条数据，都是新的表单（内层组件按"打开次数"取 key）：调用方保持挂载并切换行时，不会把上一条的值或空值当作改动发送；"取消"、Escape、关闭按钮走同一个关闭处理（保存中不关闭）。
2. 新的查询未返回前，`DataTable` 的旧行 `inert` 并 `aria-busy`（新属性 `stale`，列表与子实体列表取 `isPlaceholderData`）；e2e 中查询后先等到唯一的目标行（`searchedRow`）再点击。
3. 整数输入不再是 `type=number`（半截的 `12e` 被当作空、滚轮会改值）：文本框 `inputMode="numeric"`，只能键入数字与前导负号，只有负号时经 `onTypedInvalid` 阻止提交。
4. 嵌套 `Sheet`：Vitest 打开子实体、只关闭子实体（Escape）、焦点回到"编辑 / 查看"按钮、保存只提交子实体一次；e2e（`content.spec`）同样打开、Escape、保存子实体。
   为此列表与子实体列表在关闭表单后仍保持其挂载（关闭时也有滑出动画），表单记下打开它的按钮并在关闭时把焦点还给它。
5. 删除或行操作使触发按钮随行消失时，焦点移到表格（`DataTable` 的 `tableRef`，表格 `tabIndex=-1`）：`ConfirmDialog` 新增 `returnFocus`，确认后等列表重新读取再关闭；组合框与日期框的"清除"按钮消失后焦点留在该字段。
6. 删除了最后一页的最后一行时回到实际存在的最后一页。
7. `MaskedValue` 在数据、字段或遮蔽值改变时丢弃已显示的明文。
8. 文件上传中不能保存（保存按钮禁用并 `aria-busy`）；服务端对文件字段的违规使文件输入 `aria-invalid`（`FileUpload` 新增 `aria-invalid`）。
9. `useDictionaries`、`useReferenceLabels` 的结果在数据不变时是同一个对象（`useQueries` 的 `combine` + `useMemo`）。`DataTable` 把单元格函数当作组件渲染，
   列定义每次渲染都重建时，任何一次渲染都会重新挂载所有单元格（失去焦点、已显示的明文、预览）；列表页去掉了 `eslint-disable`。

**15b-2 产物大小**（`vite build`，未压缩 / gzip）：之前 JS 3 001.6 kB / 924.9 kB、CSS 79.4 kB / 13.3 kB；之后 JS 3 023.4 kB / 930.5 kB、CSS 79.8 kB / 13.4 kB
（antd 与 ProComponents 仍由 15c 的页面使用，摇树去不掉；15d 删除后回落）。

**15b-2 已知问题**
- 旧的 `meta/columns.ts`（ProTable 列）与 `SchemaInputs` 仍供报表与导入页使用，15c 迁完后删除；单元格的转换只有一份（`meta/cells.ts`）。
- 子实体列表的引用列不按显示名显示（与迁移前相同：子列表不取引用的显示名）。
- e2e 中待办、报表、导入、审计、封存、保留、安全设置与单据页仍用 `.ant-*` 选择器（15c / 15d）。

### 15c – 15d

详细计划在开工前给出（CLAUDE.md 第 7 节）。15c 在 15b-1 合入后开工（共用的组合组件在 15b-1）。15d 的验收：`frontend/package.json` 中没有 antd 系依赖；e2e 与 a11y 不再使用 `.ant-*` 选择器；
12 与扩展指南改写完毕。

## 阶段 16：应用所需的通用能力（由 QuizBuks 提出，线 1.2）

决策 D35–D39；来源：`1.2/quizbuks` 的 `docs/quizbuks/01-requirements-analysis.md` 第 3 节（G1–G12）。

| 子阶段 | 内容 | 决策 | 预估 | 依赖 |
|---|---|---|---|---|
| 16a | 事务邮件（☑ 已完成） | D35 | 3–4 天 | — |
| 16b | 登录入口、自助注册、邮箱验证、找回密码、`SignInGuard`、登录记录带来源（16b-1 ☑ 已完成；16b-2 ☑ 已完成） | D36 | 6–8 天 | 16a |
| 16c | 应用 SPA 的接入：`@jabiz/client` 带登录入口、注册与验证页面积木；`jabizApp` 支持多个应用 SPA 与 PWA（manifest、Service Worker 作用域、内容安全策略） | D34 | 3–4 天 | 15a、16b |
| 16d | 入站 Webhook（先实现 Stripe 签名）（☑ 已完成，PR 待创建） | D37 | 3.5–4 天 | — |
| 16e | 外部服务声明与异步外部作业（分 16e-1 / 16e-2；16e-1 ☑ 已完成；16e-2 ☑ 已完成，PR 待建） | D38 | 4.5–6 天 | — |
| 16f | OIDC 自动开户（Google、Apple）。要求：有了自助关联外部身份后，接管可预占的账号（10 §15.7）也须删除其 `SecUserIdentity` 关联 | D39 | 3–4 天 | 16b |
| 16g | 会话列表与吊销 | — | 1–2 天 | 16b |
| 16k | 定时任务逐项独立事务（`JobDefinition.forEach`、`HoldLock` 的最长等待、`BUSY`）；D42（☑ 已完成，PR 待建） | D42 | 2.5–3 天 | — |
| 16h | 账本维度接受身份与引用字段（QuizBuks 以用户编号为维度）；✅ 已完成 | D24 | 0.5 天 | — |
| 16i | 业务参数的四眼修改：受控的参数只经 `CONTROL_CHANGE_*` 修改；✅ 已完成 | D40 | 1 天 | — |
| 16j | 应用的快照路径设置生效（`jabizApp` 的 `openApiSnapshot` / `publicQueriesSnapshot`）；✅ 已完成 | — | 0.5 天 | — |
| 16l | 内部流程只允许系统身份（D41，0.5 天）：`internal()` / `entryOnly()` / `subProcessOnly()`；✅ 已完成 | D41 | 0.5 天 | — |

### 16a 事务邮件（3–4 天）

现状：邮件只有待办通知（`TASK_NOTIFY`，`AFTER_COMMIT` 进程内重试）与单据发送；`NotificationSender` 只发纯文本；`sys_notification` 必须关联待办；
没有 Markdown 渲染库；`SecUser` 没有语言字段；一次性令牌的工具 `SingleUseSecrets` 是 `security` 包内私有的。

**要求**
1. **模板** `MailTemplate`（core）：`MailTemplate.define(名称, t -> t.category(TRANSACTIONAL | NOTIFICATION).param("…")….token("verify", Duration))`；
   名称规则同事件名。标题与正文在消息 `mail.<名>.subject` / `mail.<名>.body`（应用所选的每种语言）；正文 Markdown，可用占位符：声明的参数、令牌、内置的 `{baseUrl}`、`{unsubscribeUrl}`（仅 NOTIFICATION）。
2. **渲染**（core，`MailRenderer`）：以 commonmark-java 把正文渲染为 HTML（转义原始 HTML、清理链接），参数在渲染后代入——HTML 部分按 HTML 转义，纯文本部分原样；标题为纯文本、去换行、截断 300。
3. **步骤** `SendMail.of(模板, 收件人函数, 参数函数)` / `SendMail.when(…)`（runtime，平台 I/O 步骤，模式同 `PublishEvent` / `CreateTask`）：
   收件人为用户（取其邮箱与语言）或地址加语言；在流程事务内写 `sys_mail_message`（遮蔽 `@Sensitive` 的参数），并 `PublishEvent("jabiz.mail.queued", {messageId})`。
   用户没有邮箱时登记违规 `MAIL_NO_ADDRESS`（流程可用 `when` 跳过）。`problems()` 检查模板存在、参数名与声明一致。
4. **投递**：平台消费者 `jabiz.mail` → 系统流程 `MAIL_SEND`：已有 `SENT` 尝试则跳过；`jabiz.mail.enabled=false` 或收件人已退订（NOTIFICATION）→ 记 `SKIPPED`；
   否则生成令牌（阻塞步骤取随机数，只存 SHA-256，同一消息的旧令牌作废）→ 渲染 → 经 `NotificationSender.send(MailMessage)` 发送（`MailMessage` 增加 `htmlBody`，
   `SmtpNotificationSender` 发送 multipart/alternative）→ 记 `SENT`。发送失败：在**独立事务**中记 `FAILED`（同 `OutboxDeliverer.recordFailure` 的做法）后使流程失败，
   由 Outbox 的退避重试（D14）重发。SMTP 设连接 / 读写超时（缺省 10 秒）。
5. **令牌的使用**：`MailTokens.consume(用途, 令牌)`（平台 I/O 步骤）：按哈希查找、检查用途、未过期、是该消息最新的令牌、未用过（`sys_mail_token_use` 主键保证只用一次），
   返回收件用户与地址；失败统一为 422 `TOKEN_INVALID`。供 D36 的验证邮箱与找回密码使用。
6. **语言**：`SecUser.locale`（可选，平台语言之一，用户可在账户设置中修改）；缺省为平台缺省语言。
7. **退订**：`SecUserMailPreference`（时态，用户 × 模板 → 是否接收）；NOTIFICATION 邮件带签名的退订链接（`JwtService` 新类型 `jabiz-unsub+jwt`，用户 + 模板，无过期）；
   `POST /api/auth/mail/unsubscribe`（匿名，令牌即凭证，幂等）；后台账户设置中可查看与修改订阅（`@jabiz/client` 提供接口，页面在 15c 或 16c 加入）。
8. **查询**：`sys_mail_message` 为平台实体 `MailMessage`（数据视图只读，权限 `mail.read`，`processOnlyWrites()`），附最近一次尝试的结果；场景回放可在快照中看到。
9. **示范**：`backend/app` 的一个流程（订单发货）给客户发 NOTIFICATION 邮件，带消息三语。
10. 不迁移 `TASK_NOTIFY` 与单据发送（行为不变）；以后另议。

**表与迁移**（`db/jabiz/V31__mail.sql`，并加入 `PlatformSchemaMigration.PLATFORM_TABLES`）：
`sec_user_version` 加 `locale varchar(10)`；`sys_mail_message`（只追加；消息号、模板、类别、收件用户、地址、语言、遮蔽后的参数 `jsonb`、`process_seq_id`、时间）；
`sys_mail_attempt`（只追加；主键（消息号, 次序），`SENT` / `FAILED` / `SKIPPED`、错误、时间）；`sys_mail_token`（只追加；主键 `token_hash char(64)`，消息号、用途、用户、地址、签发与过期时间）；
`sys_mail_token_use`（只追加；主键 `token_hash`，使用时间、`process_seq_id`）；`sec_user_mail_preference_version`（时态，唯一（用户, 模板））。全部 `jabiz_protect_append_only`，均有主键（D27 封存）。

**改动**：core `com.jabiz.mail`（`MailTemplate`、`MailCategory`、`MailRenderer`、占位符检查）；runtime `com.jabiz.runtime.mail`（`SendMail`、`MailProcesses`（`MAIL_SEND`、消费者）、
`MailTokens`、`MailChecks`（模板与消息、启用时的发送器，合并现有 `task/MailChecks` 的内容）、`MailEntities`、`MailUnsubscribeController`）；`task/MailMessage` 与 `SmtpNotificationSender`（HTML 部分）；
`security/SingleUseSecrets` 改为公开的工具类（移到 `com.jabiz.runtime.security.secret`）；`SecurityEntities`（`locale`、`SecUserMailPreference`）；`JwtService`（退订令牌类型）；
`runtime/build.gradle.kts`（`org.commonmark:commonmark`）；平台消息三语（`MAIL_NO_ADDRESS`、`TOKEN_INVALID`、实体与字段名）；设计 18 §5.6（新）、10、功能清单 §7。

**测试**
- core：模板声明的校验（名称、重复参数、令牌用途）；渲染（Markdown → HTML、原始 HTML 被转义、`javascript:` 链接被清理、参数在 HTML 中转义、纯文本部分）；占位符与声明的一致性（多余 / 缺少）。
- runtime 单元：`MailChecks`（缺消息的语言、未声明的占位符、启用而无发送器）。
- 集成（GreenMail，`backend/app`）：`MailIT`——流程提交后投递一次、主题与正文按收件人语言、HTML 与纯文本两部分；流程回滚则无消息无邮件；
  邮件关闭时记 `SKIPPED` 且不发送；首次发送失败记 `FAILED`、Outbox 重试后 `SENT`、不重复发送；令牌只存哈希（库中查不到原文）、重发后旧令牌无效、令牌只能用一次、过期无效、用途不符无效；
  退订后 NOTIFICATION 记 `SKIPPED`、TRANSACTIONAL 照发；退订令牌被篡改即拒绝；`sys_mail_*` 表上没有执行过 UPDATE / DELETE。
- 场景回放：示范流程的场景，快照中含邮件消息（`SKIPPED`，测试中邮件关闭）。
- `PlatformSchemaMigrationTest`、启动检查、ArchUnit 照常通过；OpenAPI 快照更新（退订接口）与 `pnpm gen:api`。

**风险**：投递流程在事务中调用 SMTP，慢服务器会占用连接（以超时与 `jabiz.events.delivery.batch-size` 控制，记录在 18 §5.6）；
commonmark 的 HTML 输出在各邮件客户端中的样式（只用基本元素，不做模板样式）。

**验收标准**
- [x] 应用以 `MailTemplate` Bean 与消息声明模板，流程中 `SendMail` 发出；缺消息、占位符不符在启动时报告（`MailChecks`，`MailChecksTest`）。
- [x] 邮件经 Outbox 投递：回滚不发、失败重试、已发送不重发；关闭时 `SKIPPED`（`MailIT`、`MailDisabledIT`、场景 `commerce/order_shipped_mail`）。
- [x] 一次性令牌只在发送时生成，库中只有哈希；只能用一次、过期与重发后失效（`MailIT`）。
- [x] 按收件人语言渲染；NOTIFICATION 可经签名链接退订，TRANSACTIONAL 不可（`MailIT`、`JwtServiceTest`）。
- [x] 示范应用有一个可运行的例子（`ORDER_SHIP` 的发货通知）；`./gradlew check` 通过。

**实现说明与偏离**（设计见 18 §5.6）
- 尝试记在 `sys_mail_attempt`（按本计划），而不是 D35 第 2 条所说的 `sys_notification_attempt`（后者的外键指向待办通知）；状态 `SENT` / `FAILED` / `SKIPPED`，
  `detail` 列放错误或跳过原因（`MAIL_DISABLED`、`UNSUBSCRIBED`）。已有 `SENT` **或 `SKIPPED`** 的消息不再处理。
- 令牌的声明是模板的 `.token(用途, 有效期)`（本计划的写法），即 D35 第 3 条的 `MailParam.oneTimeToken(用途, 有效期)`；占位符名即用途。
- "该消息最新的令牌"扩大为"同一收件人（用户，无用户时为地址）同一用途的最新令牌"（`issue_seq`）：重试与重发（D36 的"重新发送验证邮件"是新消息）都使旧令牌失效。
  失败的尝试中生成的令牌随投递事务回滚、从未入库。
- 消息号与尝试号为 `varchar(36)`（同 `sys_notification`，平台实体的生成键是文本）；参数存为 JSON 文本 `text`（同 `sys_task_version.title_params`），
  而不是 `jsonb`：实体的文本字段不能写入 `jsonb` 列，为此新增语义类型不值得。
- "附最近一次尝试的结果"：实体不能连接其他表，因此尝试是另一个只读平台实体 `MailAttempt`（`attempt_id` 为其键，另有主键（消息, 次序）），按 `messageId` 筛选的最新一行即状态。
- 记录 `FAILED` 用新增的 `StorageEngine.inNewTransaction`（`PROPAGATION_REQUIRES_NEW`）：失败发生在投递流程内部，而 `OutboxDeliverer.recordFailure` 在流程之外。
- commonmark 0.30.0 加在 core（`MailRenderer` 是纯 Java，属于核心层），而不是计划中的 `runtime/build.gradle.kts`；core 仍不依赖 Spring、Reactor、R2DBC。
- 通知（NOTIFICATION）只能发给用户：退订按用户与模板记录，发给裸地址时 `SendMail` 报错（编程错误，500）。参数名与令牌用途不含下划线（`[a-z][A-Za-z0-9]*`）。
- 账户设置的接口：`GET` / `POST /api/auth/mail/preferences`、`POST /api/auth/account/locale`（流程 `SEC_MAIL_PREFERENCE_SET`、`SEC_USER_SET_LOCALE`，
  权限 `auth.mail-preference`、`auth.account` 不授予角色，只能本人运行）；`SEC_USER_CREATE` 可带 `locale`。前端页面与 `@jabiz/client` 的封装留给 15c / 16c，
  本阶段只更新 OpenAPI 快照与生成的类型。
- 示范：新增客户主数据 `Customer`（管理员维护，可选 `userId`；应用迁移 `V15__customers.sql`），`ORDER_SHIP` 给其中有账号且有邮箱的客户发
  `commerce.order-shipped`；收件人不来自下单输入。
- 评审后的修正：参数原样保存并以此渲染（秘密参数在启动检查或以 `MAIL_PARAM_SECRET` 拒绝，超过 16 KiB 以 `MAIL_PARAMS_TOO_LARGE` 拒绝）；令牌在发送前、
  `SENT` 在发送后各自独立提交，之后的失败不会重发；SMTP 超时在发送器 Bean 创建时设置；通用后台增加退订页 `/mail/unsubscribe`（`MailUnsubscribePage`）；
  `MailAttempt` 的数据视图改为只读，投递日志不进审计（21 §1）；主题按码点截断；控制器共用 `ActingUser`。

**已知问题**
- 投递在事务中调用 SMTP：慢服务器会占用连接直到 `jabiz.mail.timeout`（缺省 10 秒）；`OutboxDeliverer` 依次处理各消费者，慢的邮件服务器会推迟其他消费者的投递
  （本阶段不改投递器）。令牌、`SENT`、`FAILED` 的独立事务各需另一个连接（连接池耗尽时会等待）。
- 至少一次：服务器收下了邮件却报告失败时会再发一次，前一封中的令牌已被取代；投递在 `SENT` 之后失败（提交失败）则不会重发。
- 发送失败且重试用尽时，这次未发出的令牌已入库，收件人此前邮件中的同用途令牌因此失效（需重新发送）。
- 退订链接无过期，轮换 `JABIZ_JWT_SECRET` 会使已发出的退订链接失效（用户仍可在账户设置中退订）。
- 退订接口匿名且未单独限流（每次只校验签名并至多写一条偏好）。
- 待办通知（`TASK_NOTIFY`）与单据发送不迁移（本计划第 10 条），仍是纯文本、进程内重试。

### 16b 登录入口、自助注册、邮箱验证、找回密码、`SignInGuard`、登录记录带来源（6–8 天，分 16b-1 / 16b-2 两个 PR）

决策 D36（含 G4）；依赖 16a（D35）。比阶段表中的 5–7 天多约 1 天：下面"实施细则"补了 D36 未写明、但必须做的几处（加入与重置时的账号预占、验证随邮箱改变而失效、不区分大小写的唯一性）。

现状（`1.2/platform`）
- **登录**：`AuthController` 的 `POST /api/auth/login`（`LoginRequest(userName, password)`）执行 `SPONSOR_SIGN_IN`：按 `userName` 查用户 → 读最新登录记录 → `AuthenticationStep`（BCrypt，名字不存在也比对哑哈希）→ `accessSteps`（角色分配、角色、权限，`Rbac.access`，无角色即 `NO_ROLE`）→ 二次验证 → `LoginRecordStep`；除 `SUCCESS` 外一律 401 `LOGIN_FAILED`。`SPONSOR_MFA_VERIFY`、`SPONSOR_OIDC_SIGN_IN` 共用 `accessSteps`，入口过滤与 `SignInGuard` 只需加在一处。
- **令牌**：访问令牌声明 `sub`、`tenant`、`roles`、`perms`、`mfa_at`、`data_from`、`data_to`；按 `typ` 区分访问令牌、挑战令牌（`jabiz-mfa+jwt`）、退订令牌（`jabiz-unsub+jwt`），`verify()` 只接受访问令牌。
- **刷新**：`RefreshTokenStore.rotate` + `RbacService.currentActor` 重读启用、锁定、角色与二次验证要求；`sec_refresh_token` 有 `mfa_at`、`identity_id`；`revokeUser` 已有。
- **身份对象**：`Actor`、`RequestContext` 有 `mfaAt`、`dataPeriod`，没有入口与邮箱验证状态。
- **step-up**：流程 `requiresMfa`、数据视图 `writeRequiresMfa`（只管写），在六个入口调用 `MfaPolicy.require`（`ProcessController`、`DatasetController`、`EntityController`、`ResolveEntityHandler`、`ImportAccess`、`RevertService`），系统身份与场景回放不查。
- **匿名接口**：`SecurityConfig.PUBLIC`；只有 `/api/public/**` 有按地址限流（`TokenBucketLimiter`）；客户端地址取连接地址或 Spring `forward-headers-strategy` 改写后的地址，没有"可信代理"；登录接口无限流。
- **用户**：`SecUser.email` 可选、**不唯一**；`locale`（16a）；没有验证时间；`passwordHash` 可空（只用 OIDC）。时态唯一检查（D6）区分大小写。
- **登录记录**：没有来源信息。**16a 的邮件部件**：`MailTemplate.token`、`SendMail`、`MailTokens.consume`（只认同一收件人同一用途的最新令牌）、`ActingUser`；链接只有全局 `{baseUrl}`。最新平台迁移 V31；示范应用 V15（`Customer.userId`）。
- **前端**：登录不传 `entry`（15a 后在 `packages/client`）。

#### 实施细则（D36 未写明之处的处理；写入 D36 的实现说明）

1. `SignInGuard` 是 core 的同步接口，所需的应用数据由它声明 `loads()`（数据视图 + 存用户编号的字段），平台预读后传入；多个守卫全部放行才放行，守卫抛异常即拒绝（fail closed）。
2. 只在密码正确之后才区分：守卫拒绝答 403 `SIGN_IN_REFUSED`，入口要求已验证而未验证答 403 `EMAIL_NOT_VERIFIED`（请求者已证明持有密码，不泄露账号存在）；入口不接受其角色仍答 401 `LOGIN_FAILED`（记 `NO_ROLE` 与入口）。
3. 刷新时守卫拒绝：不写登录记录（刷新不是流程），记日志与指标 `jabiz.auth.refresh.refused`，吊销该令牌族。
4. 新增 `verifiedEmail` 与 `emailVerifiedAt`（都 `processOnly`，只由验证类流程写）；"已验证" = `lower(verifiedEmail) = lower(email)`，管理员改邮箱即自动失效。
5. 证明邮箱所有权的三处都写验证字段：验证邮箱、找回密码的确认、"你已有账号"邮件的加入链接。
6. **账号预占**：已有账号**未验证**时，加入链接必须同时设置新密码并吊销全部令牌族；已验证的账号只追加角色。注册请求中的密码从不用于已有账号。
7. 不新增"待验证"状态：未验证即待验证，由入口的 `require-verified-email` 决定能否登录。
8. 数据视图的 `requiresVerifiedEmail()` 同二次验证只管写；读取的限制以后另议。
9. 缺省入口 `admin` 总是存在：未配置时接受全部角色，显式配置则以配置为准；请求不带 `entry` 即 `admin`，现有后台前端无需改动。
10. 新建 `ClientAddresses`（登录记录与公开接口限流共用），按 `jabiz.security.trusted-proxies` 取地址；与 `server.forward-headers-strategy` 同时配置时启动检查报错（避免转发头被处理两次而可伪造）；不配置时保持现有行为。
11. 不区分大小写的唯一：core `UniqueConstraint` 增加 `ignoreCase`，时态唯一检查按 `lower` 取锁与查找，索引建在 `lower(email)` 上；迁移遇到已有重复即明确失败，须先由管理员处理。
12. 自助注册的 `userName` 取邮箱小写，已被占用时加 `-` 与 6 位随机字符；按标识登录先精确匹配用户名，再按**已验证**的邮箱（不区分大小写）匹配。
13. 邮件链接为 `{baseUrl}/auth/<页>?entry=<入口>&token=<令牌>`，由通用后台的落地页处理（同 16a 的退订页），完成后跳到入口配置的 `app-path`；应用 SPA 自己的页面在 16c。

#### 16b-1 登录入口、令牌入口、`SignInGuard`、`requiresVerifiedEmail`、登录记录带来源（3–4 天）

改动所有登录的关键路径，单独评审并证明后台行为不变；不需要邮件。

**要求**
1. **入口配置** `jabiz.security.entries.<名>`（名字 `[a-z][a-z0-9-]{0,39}`）：`accepted-roles`（角色码，`*` 为全部）、`self-registration`（缺省否）、`registration-roles`、`require-verified-email`（缺省否）、`app-path`（缺省 `/`）。缺省入口见细则 9。启动检查 `SECURITY` 一次报告全部问题（名字非法、注册角色不在接受的角色中、允许注册却无注册角色、`app-path` 格式）；邮件未启用而允许注册、角色码不存在为告警。
2. **登录带入口**：`LoginRequest.entry`（可选，缺省 `admin`），`userName` 也可填已验证的邮箱；未知入口 401。`accessSteps` 先按入口过滤角色分配与角色再算权限，因此权限、`mfaRequired`、数据期限都只来自该入口接受的角色。
3. **令牌**：访问令牌加 `entry`、`email_verified`（旧令牌无 `entry` 视为 `admin`）；挑战令牌加 `entry`，`SPONSOR_MFA_VERIFY` 的入口取自挑战令牌而非请求；`Actor`、`RequestContext` 加 `entry`、`emailVerified`（保留旧构造器）；`/api/auth/me` 加 `entry`、`email`、`emailVerified`。
4. **刷新**：`sec_refresh_token.entry` 随族沿用；请求的入口与令牌不符即 401 且不消费；`currentActor` 按入口过滤、重读 `emailVerified` 并调用守卫。
5. **`SignInGuard`**（core）：`check(SignInAttempt)` + `loads()`；`SignInAttempt` 含用户、入口、过滤后的角色、`emailVerified`、因素（密码 / OIDC / TOTP / 刷新）、时间、预读数据。平台步骤 `SignInGuardStep` 放在角色检查之后、签发之前（三条登录路径与二次验证步骤都经过），刷新也调用。拒绝：`REFUSED`，写登录记录，不计失败。
6. **`requiresVerifiedEmail()`**：流程与数据视图（只管写）声明；`VerifiedEmailPolicy` 与 `MfaPolicy` 放在同样六个入口，403 `EMAIL_NOT_VERIFIED`；流程目录公开该要求；场景回放、子流程、事件消费者不查。
7. **邮箱**：`SecUser.email` 非空时不区分大小写唯一；新增 `emailVerifiedAt`、`verifiedEmail`（本 PR 尚无写入者）；入口要求已验证时未验证者登录为 `EMAIL_NOT_VERIFIED`（写记录、不计失败、403）；按已验证邮箱登录见细则 12。
8. **`LoginOutcome`** 增加 `REFUSED`、`EMAIL_NOT_VERIFIED`、`PASSWORD_RESET`（后者清零并解锁，供 16b-2）。
9. **登录记录带来源**：`entry`、`clientIp`、`userAgent`（截断 256、去控制字符）；`ClientAddresses` 只在连接来自可信代理时读 `X-Forwarded-For`，从右向左跳过可信地址取第一个不可信地址；格式错误的头忽略；`PublicAccessWebFilter` 改用它。
10. **OIDC**：`start` 可带 `entry`，存入 `sec_oidc_state.entry`，回调用存下的值。
11. 文档：10（令牌声明表、新 §15）、15 §6、07、D36 实现说明、CLAUDE.md 第 4 节。

**表与迁移**（`db/jabiz/V32__sign_in_entries.sql`，只加列与索引，不建新表）：`sec_user_version` 加 `email_verified_at`、`verified_email`，索引 `lower(email)`（非空），遇到已有不区分大小写的重复即 `RAISE EXCEPTION`；`sec_login_record_version` 加 `entry`、`client_ip`、`user_agent`；`sec_refresh_token`、`sec_oidc_state` 加 `entry`（旧行为空，读作 `admin`）。`MetaModelConsistencyChecker` 认可表达式索引。

**改动**：core（`SignInGuard` 等、`LoginOutcome`、`LoginAttemptPolicy`、`UniqueConstraint.ignoreCase`、流程与数据视图的声明、`RequestContext`、错误码与三语消息）；runtime security（`SignInEntries`、`ClientAddresses`、`SignInGuards` / `SignInGuardStep`、`VerifiedEmailPolicy`、`JwtService`、`RefreshTokenStore`、`RbacService`、`Rbac`、`AuthController`、`MfaController`、`OidcController`、`OidcStateStore`、`SecurityEntities`、`SecurityConfig`）；登录流程与 `LoginRecordStep`、`Actor`、认证过滤器、开发用请求头（`admin`）、`TemporalStore.checkUnique`、六个入口、`MetaModelController`、`ProblemStatuses`、`PublicAccessWebFilter`。前端不改代码，只更新 OpenAPI 快照与生成的类型。

**测试**
- 单元：`LoginAttemptPolicyTest`（新结果不计数，`PASSWORD_RESET` 清零解锁）；`SignInEntriesTest`（缺省、覆盖、错误一次报全）；`ClientAddressesTest`（不可信连接伪造 XFF 无效、可信链取最右侧不可信地址、全链可信取最左、格式错误、IPv6、CIDR 边界、与 `forward-headers-strategy` 同配报错）；`JwtServiceTest`（新声明往返、旧令牌为 `admin`、挑战令牌的入口、其他类型冒充访问令牌被拒、篡改入口签名失败）；`RbacTest`（按入口过滤权限、`mfaRequired`、数据期限）；不区分大小写唯一；`VerifiedEmailPolicyTest`。
- 集成（`backend/app`，本地 PostgreSQL；`SignInEntryIT`、`ClientAddressIT`）：
  - 后台不变：不带入口的登录、刷新、MFA、OIDC 与现在相同（`SignInIT`、`MfaIT`、`OidcIT` 原样通过）。
  - 入口隔离：兼有 `ADMIN` 与 `CUSTOMER` 的用户登录 `portal`，令牌只有 `CUSTOMER` 的权限、后台流程 403；只有 `ADMIN` 的用户登录 `portal` 得 401 并记 `NO_ROLE`；刷新保持入口，入口不符 401 且不消费；`portal` 的挑战只换得 `portal` 会话；OIDC 回调用 state 中的入口；`requireMfa` 角色只在接受它的入口要求二次验证。
  - 守卫：拒绝 403 并记 `REFUSED`，反复拒绝不锁定；封禁后下一次刷新 401 并吊销族；守卫异常即拒绝；`loads()` 的数据到达守卫。
  - 验证邮箱：未验证者在流程 API、数据视图写、实体 API、导入四处 403；系统身份与场景回放照常；入口要求已验证时拒绝登录、失败计数不变；`A@x.com` 与 `a@x.com` 冲突，并发只成功一个。
  - 来源：未配置可信代理时伪造 XFF 无效；可信代理链取正确地址；UA 截断；公开接口限流按同一地址。
  - 加列的表上没有 UPDATE / DELETE；OpenAPI 快照更新。
- 前端：`LoginPage.test.tsx`、`e2e/login.spec.ts`、`mfa.spec.ts` 不改即通过。

**风险**：`RequestContext` / `Actor` 构造处多（保留旧构造器）；守卫预读限为按用户编号的等值查询且有上限；已有重复邮箱时迁移中止（升级说明写明）；入口过滤改变 `mfaRequired` 的含义（有意，写入 10 §15）；升级前签发的令牌在有效期内按 `admin` 处理。

**验收标准**
- [x] 不带 `entry` 的登录、刷新、MFA、OIDC 行为不变，后台前端不改即可用。
- [x] 访问令牌带 `entry` 与 `email_verified`，只含该入口接受角色的权限；刷新保持入口，不符即拒且不消费；挑战与 OIDC 的入口不能被请求改写。
- [x] `SignInGuard` 在三条登录路径与刷新时调用；拒绝写记录、不计失败；异常即拒绝。
- [x] `requiresVerifiedEmail` 在各入口返回 403 `EMAIL_NOT_VERIFIED` 并在目录中可见；入口可要求已验证才能登录。
- [x] 邮箱不区分大小写唯一；已有重复时迁移明确失败。
- [x] 登录记录带 `entry`、`clientIp`、`userAgent`；转发头只在来自可信代理时采用。
- [x] 配置问题一次报全；`./gradlew :core:check :runtime:check :app:check` 通过；OpenAPI 快照与生成的类型已更新。

**完成情况（分支 `1.2/phase-16b1-sign-in-entries`）**：迁移 `V32__sign_in_entries.sql`；测试 `SignInEntryIT`（19）、`ClientAddressIT`（3）、`OidcEntryIT`（3），
runtime `SignInEntriesTest`、`ClientAddressesTest`、`SignInGuardsTest`，`JwtServiceTest` / `RbacTest` / core 测试补充；`SignInIT`、`MfaIT`、`OidcIT` 未改动即通过。
与计划的出入（都很小）：
- 数据视图的声明写作 `policy(p -> p.requiresVerifiedEmail())`，目录字段为 `writeRequiresVerifiedEmail`（与 `writeRequiresMfa` 对应）；`VerifiedEmailPolicy` 是无状态的静态检查（只读令牌声明），六个入口无需注入新 Bean。
- 守卫的测试夹具借用 `SecUserIdentity`（伪提供方 `it-blocked` / `it-guard-fails`）作为预读数据，示范应用的 `Customer.blocked` 守卫在 16b-2。
- 不区分大小写的查找新增 core `QueryPredicate.EqIgnoreCase`（`lower(列) = lower(:值)`；模板不支持）。
- OIDC 的入口以 `POST /api/auth/oidc/{id}/start?entry=…` 给出；挑战验证与 OIDC 回调的请求体中的 `entry` 一律忽略。
- Spring Security 防火墙拒绝含控制字符的请求头值：读取 `User-Agent` / `X-Forwarded-For` 时捕获并忽略（登录照常，不记 UA），否则登录会 500。
- 前端只改了一个 Vitest 夹具（`RowActions.test.tsx` 的流程目录项补 `requiresVerifiedEmail`，`Required<…>` 类型要求），业务代码未改。
- 代码评审后的修正（10 §15.5–§15.6）：转发头只解析走到的段、可带端口；不区分大小写的唯一检查由数据库 `lower()` 折叠（锁键与比较一致）；
  step-up 对守卫拒绝与未验证地址答 403（同登录）并记录地址与 UA；空白入口即 `admin`；刷新时已用过的令牌先于过期、闲置与入口判为重用；按名字登录不再查邮箱；
  `MetaModelConsistencyChecker` 精确匹配 `lower(列)`；`RefreshTokenStore.revokeSession` 合并为一个方法。

**已知问题**：升级前签发的访问令牌在有效期内按 `admin`、`email_verified=false` 处理；守卫在刷新时才再次生效（访问令牌到期前仍可用）；
按邮箱登录在名字不匹配时多一次查询（响应时间略有差别）；已有重复邮箱的库升级时迁移中止，须先由管理员处理。

#### 16b-2 自助注册、邮箱验证、找回密码、示范（3–4 天）

新增匿名写入接口，单独做安全审查。

**要求**
1. **平台邮件模板**（`TRANSACTIONAL`，三语）：`jabiz.auth.verify-email`（令牌 `verify`，缺省 24 小时）、`jabiz.auth.account-exists`（令牌 `join`，24 小时）、`jabiz.auth.password-reset`（令牌 `reset`，缺省 1 小时）；链接见细则 13。
2. **注册** `POST /api/auth/register {entry, email, password, displayName?, locale?}` → `SPONSOR_SIGN_UP`：入口不存在或不允许注册 403 `SIGN_UP_DISABLED`；格式与密码长度先校验；按 `lower(email)` 查用户；**两种情况都计算 BCrypt**；未被使用 → 建用户、授予注册角色、发验证邮件；已被使用 → 不建用户、不用提交的密码、给已有用户发"你已有账号"（禁用的不发）。两种情况都答 **202，状态、响应头、正文完全相同**。
3. **加入** `POST /api/auth/join {token, password?}` → `SPONSOR_SIGN_UP_JOIN`：消费 `join` 令牌，地址须等于用户当前邮箱；从该邮件的参数读出入口，追加其注册角色；写验证字段；原先未验证时必须设新密码并吊销全部令牌族（细则 6）。204。
4. **验证邮箱** `POST /api/auth/verify-email {token}`（匿名）→ `SPONSOR_EMAIL_VERIFY`；重发：匿名 `POST /api/auth/verify-email/resend {email, entry}`（只对未验证用户发，始终 202），已登录 `POST /api/auth/account/verify-email`。新邮件使旧令牌作废。
5. **找回密码** `POST /api/auth/password-reset/request {email, entry?}`（始终 202，只对启用的用户发信）；`confirm {token, password}`：消费令牌、地址等于当前邮箱、设密码（只用 OIDC 的用户也可）、写验证字段、吊销全部令牌族、写登录记录 `PASSWORD_RESET`（清零并解锁）。204，不自动登录。
6. **限流**：按 `ClientAddresses` 得出的地址与小写邮箱，超限 429 `RATE_LIMITED` 带 `Retry-After`；缺省每地址每小时 20 次、每邮箱每小时 3 次；对存在与不存在的邮箱一致。
7. 以上接口加入 `SecurityConfig.PUBLIC`（`/api/auth/account/verify-email` 需登录）；`GET /api/auth/entries/{name}`（匿名）返回 `selfRegistration`、`requireVerifiedEmail`、`appPath`。
8. **通用后台页面**：`/auth/verify-email`、`/auth/join`（未验证时要求新密码）、`/auth/password-reset`（申请与确认），登录页加"忘记密码"；三语，Vitest。用 `@jabiz/ui` 组件（15a 已合入时）。注册表单在 16c。
9. **示范**（`backend/app`）：入口 `portal`（接受并授予 `CUSTOMER`，允许注册，要求已验证）；应用迁移 `V16__customer_blocked.sql`；`PortalSignInGuard`（`blocked` 的客户被拒）；流程 `PORTAL_ORDER_PLACE`（`requiresVerifiedEmail()`，客户取自调用者而非输入，子流程调用 `ORDER_PLACE`）。

**改动**：runtime security（`SignUpController`、五个流程与 `AuthMailTemplates`、`SignUpRateLimits`、平台步骤 `LoadUserByEmail` 与 `MailMessageParams`、`RefreshTokenStore` 的吊销原因、错误码 `SIGN_UP_DISABLED`、三语消息）；前端三个页面与登录页链接；app 的入口配置、守卫、流程、消息、V16；文档 10 §15、D36 实现说明、07、功能清单（删去"不做自助注册"）、扩展指南。平台无新迁移。

**测试**（GreenMail，参照 `MailIT`；`SignUpIT`、`PasswordResetIT`、`PortalIT`）
- **不可枚举**：新邮箱与已有邮箱的注册响应逐字节相同，只是邮件不同；重发与找回对未知邮箱同样 202 且不发信；两条注册路径都经过哈希步骤；未验证的邮箱不能用于登录（同未知名字）。
- **令牌**：`verify` 令牌用于 reset / join 得 422 `TOKEN_INVALID`；访问令牌、挑战令牌当作邮件令牌无效；二次使用、过期、重发后的旧令牌、发信后邮箱被改都无效；库中查不到令牌原文。
- **流程**：注册后有注册角色、未验证、`portal` 拒绝登录；验证后可登录、令牌 `email_verified=true`、可运行 `PORTAL_ORDER_PLACE`；已有账号注册不追加角色，点击加入链接后才追加；`admin` 入口注册 403。
- **账号预占**：攻击者先以受害人邮箱注册，受害人注册后加入时须设新密码，完成后攻击者的密码失效、会话被吊销。
- **找回密码**：确认后旧刷新令牌 401、锁定解除、记录 `PASSWORD_RESET`；禁用用户收不到信；只用 OIDC 的用户可设密码。
- **限流**：超限 429，存在与不存在的邮箱一致；伪造 XFF 不能绕过。
- **守卫示范**：被封禁的客户登录 `portal` 得 403；封禁后下一次刷新失败。
- 涉及的表上没有 UPDATE / DELETE；OpenAPI 快照更新；场景 `security/sign_up.yml`、`commerce/portal_order.yml`；前端页面的 Vitest。

**风险**：匿名注册每次计算 BCrypt，可被用来耗 CPU（靠限流与线程池上限；限流按实例，同 15 的已知限制）；邮件关闭时注册的用户无法验证（启动告警）；找回密码的申请两种情况仍有极小的耗时差（多写一条邮件消息），记为已知限制；与 15b 改同一批前端文件，合并顺序需协调。

**验收标准**
- [x] 允许注册的入口可以注册；新旧邮箱响应完全相同；已有账号只有点击邮件链接后才追加角色，未验证的已有账号须同时设新密码。
- [x] 验证令牌只能用一次，过期、被取代、用途不符、邮箱已改都无效；验证后令牌带 `email_verified`。
- [x] 找回密码不泄露邮箱是否存在；确认后吊销全部令牌族、写登录记录、解除锁定。
- [x] 注册、重发、找回按地址与邮箱限流，伪造转发头不能绕过。
- [x] 示范 `portal` 的注册、验证、登录与下单可运行，被封禁的客户被拒。
- [x] 通用后台有验证、加入、找回密码页面与"忘记密码"链接；`./gradlew check` 与前端检查通过；D36、10、07、功能清单已更新。

**完成情况（分支 `1.2/phase-16b2-self-registration`）**：平台迁移 `V33__registered_email.sql`（评审后新增，只加列），示范应用 `V16__customer_blocked.sql`；
测试 `SignUpIT`（17）、`PasswordResetIT`（9）、`PortalIT`（4），runtime `SignUpRateLimitsTest`（4）、`SignUpProcessesTest`（3）、`SignInEntriesTest`、`MailChecksTest`、
`ScenarioParserTest` 补充，core `MailTemplateTest` 补充；场景 `security/sign_up.yml`、`commerce/portal_order.yml`；前端 `AuthPages.test.tsx`（13）、
`LoginPage.test.tsx` 与 `registry.test.ts` 各补一条。`./gradlew check` 与 `pnpm lint typecheck test check:api` 通过。

**评审后的修订**（安全评审 S1–S3、加固与代码评审 R1–R10，10 §15.7 与 D36 实现说明已同步）：
- S1 账号接管：找回密码与"你已有账号"只发给**可信的邮箱**（已验证，或可预占的账号的）；管理员设置或修改的邮箱从不可信（`registeredEmail` 只由注册写入），
  由本人登录后验证。重置不删除、不绕过二次验证（下次登录照常要求）。预占保护只用于**可预占的账号**（自助注册、从未验证、仍在注册邮箱）；加入其他账号只追加角色，密码不变。
  与评审意见的出入：加入其他账号时"你已有账号"本身就只发给可信的邮箱（否则改了别人邮箱的人可借加入链接把邮箱标为已验证、再找回密码）；可预占的账号经重置被接管时仍删除二次验证（同加入，评审 c）。
- S2 验证链接：可预占的账号验证时要当前密码或新密码（新密码即接管，吊销原因 `CLAIM`，新错误码 `PASSWORD_INCORRECT`）；匿名的重发只发给可预占的账号。
  验证页不再打开即提交：点击后先以试运行 `POST /api/auth/verify-email/check` 从令牌问清是否需要密码。
- S3 后台入口：示范配置 `jabiz.security.entries.admin.accepted-roles=${JABIZ_ADMIN_ROLES:ADMIN}`；任何入口允许注册时 `admin` 接受 `*` 或注册角色为启动错误。
  测试配置让后台接受全部角色并关闭门户的注册（各 IT 以自己的角色登录后台），注册相关的 IT 与场景自己打开注册并限定后台角色；CI 的 e2e 以 `JABIZ_ADMIN_ROLES=*`、
  `JABIZ_PORTAL_SELF_REGISTRATION=false` 运行。场景新增 `properties`（回放上下文的配置）。
- 加固：令牌用途改为 `jabiz.verify` / `jabiz.join` / `jabiz.reset`（core 允许令牌用途与占位符带一段命名空间），应用的模板使用 `jabiz.` 前缀为启动错误；
  落地页读出令牌后用 `history.replaceState` 去掉它，打开期间 `<meta name="referrer" content="no-referrer">`。
- R1 OpenAPI 标明 202 / 204；R2 并发注册只吞邮箱的唯一冲突并重试一次（后到者的用户收到"你已有账号"），有并发 IT；R3 `SIGN_UP_DISABLED` 一律 422，被拒的加入不消费令牌（IT）；
  R4 找回密码的二次验证用例；R5 加入与重置先检查链接再计算哈希；R6 加入页只把密码的问题放在密码栏下；R7 验证页失败后可重试；
  R8 客户端地址都取自同一计数器，限流在测试中使用可推进的时间（`FixedTimeLimits`），哈希的断言按提交的密码而非全局计数；R9 `EmailRequest.entry` 的说明；
  R10 `DictionaryRegistryIT` 的缓存竞争以种子港口字典上的通知作栅栏（单独提交）。

与计划的出入（都很小）：
- 流程是七个：找回密码的申请与确认各一个，验证另有试运行的 `check`，匿名与已登录的重发共用 `SPONSOR_EMAIL_VERIFY_SEND`。
- 平台有一个新迁移（V33，只加列），计划为"平台无新迁移"。
- 链接在用户已禁用时也无效（同地址已改）；加入时入口须仍允许注册，注册角色须存在且启用，否则 422 `SIGN_UP_DISABLED`（对任何邮箱相同）。
- 注册、重发、找回先检查请求的全部值再限流与运行流程；202 带 `Content-Length: 0`（openapi-fetch 据此不解析正文）。限流每类请求分开计数，使用链接也按地址限流。
- `HashPasswordStep` 与 `RevokeSessionsStep` 的元数据加可选条件 `when`；新的阻塞步骤 `CurrentPasswordStep`；`TokenBucketLimiter` 与 `SignUpRateLimits` 带时钟的构造器公开（测试）。
- 场景回放：步骤操作人的 `id` 可引用变量，操作人可声明 `mfa: true`，场景可带 `properties`，07 §3.1 已写明。
- `Customer.blocked` 可为空（空即未封禁）；另建 `user_id` 部分索引供守卫预读。
- `SignInEntryIT`、`OidcEntryIT` 用名为 `portal` 的测试入口，补了属性覆盖其注册设置；`PlatformCheckIT` 断言预期的一条警告（`CUSTOMER` 角色在空库中不存在）。示范的 `app-path` 为 `/`。
- 登录页的外框抽到 `components/SignInFrame.tsx`；`/auth`、`/mail` 加入扩展不能占用的平台路径。

**已知问题**：限流在内存中、按实例计数；找回密码与重发的两种情况仍有极小的耗时差（多写一条邮件消息），注册的新邮箱一方多写用户与角色分配；
邮件关闭时注册的用户无法验证（启动告警）；被接管的预占者在此之前签发的访问令牌在有效期内仍可用（同 10 §2）；用户名即小写邮箱，未验证的用户也能以它登录到不要求验证的入口；
**升级**：之前创建的账号（管理员设置的邮箱）在本人登录并验证邮箱之前不能找回密码、匿名重发也不发信，忘记密码时由管理员重设；
示范的 `CUSTOMER` 角色是数据，需由管理员或演示脚本创建（未创建时注册为 422 `SIGN_UP_DISABLED`，启动告警）；`V33` 与其他并行阶段的平台迁移编号须在合并时协调。
安全复审记下、暂不修改的（10 §15.6）：持有 `security.user.write` 的人可把受害人邮箱改成自己的，受害人若随后登录要求验证，攻击者即得到已验证的邮箱并可找回密码（二次验证保留；计划发送前向本人显示目标地址）；管理员改邮箱时 `verifiedEmail` 不清除，改回以前验证过的地址即重新算作已验证；16f 的自助关联外部身份实现时，接管须同时删除 `SecUserIdentity` 关联（已写入 16f）；`CurrentPasswordStep` 的错误密码只按客户端地址限流。

**16b 修复（分支 `1.2/fix-16b-guard-binding`，QuizBuks Q2 平台缺口 15、16）**：守卫的预读把用户编号一律按 UUID 绑定，预读文本字段时读取失败、所有人在所有入口被拒；
数据视图范围值（`fromContext` 的文本）按原样绑定，与 `uuid` 列（引用时态实体）比较时 500，写入时填入的值同样出错。现在两者都经 `QueryCompiler.fieldValue`
（与查询条件相同的转换：语义类型的规范类型，主键与对时态实体的引用为 UUID；`FieldValueCoercer` 的文本也接受 UUID）：预读以文本给出用户编号，
范围值在 SQL（数据视图与模板）中、写入前（`QueryCompiler.scopeValues`）都转换，转换不了为 400 `INVALID_VALUE`。启动检查新增：固定范围值不符合字段类型、
预读字段放不下用户编号（UUID）→ 错误。测试：`ValueBindingIT`（7，修复前 6 个失败）、`PlatformCheckIT` 补一条，runtime `DatasetRegistryTest` 补一条，core `AccessControlCompilerTest`（2）、`FieldValueCoercerTest` 补充。
复审后另修：`admin` 入口自己允许注册为启动错误（`SignInEntriesTest`）。

**不在本阶段**：注册表单与应用 SPA 的页面积木（16c）、OIDC 自动开户（16f）、会话列表（16g）、按 IP 的大致位置、图形验证码。

### 16d 入站 Webhook（3.5–4 天）

决策 D37；无硬依赖。限流用的客户端地址取自 16b-1 的 `ClientAddresses`（已合入）。

**现状（`1.2/platform`）**
- **Outbox**：`Outbox.append(engine, 类型, entityType, entityId, payloadJson)` 在调用方的事务中写 `sys_outbox_event`（`process_seq_id` 可空）。`OutboxDeliverer` 对每个 `EventSubscription` 以系统身份运行流程，`beforeSteps` 写消费标记，退避重试（D14）。事件名为 `[A-Za-z0-9._:-]{1,100}`。
- **出站 Webhook（D33，11 §2.4）**：
  - `WebhookProperties`：配置、密钥以占位符取自环境变量、`toString` 遮蔽。
  - `WebhookChecks`：类别 `WEBHOOK`，一次报全。
  - core `WebhookSignature`：只有 `sign` / `matches`，没有时间容差和请求头解析。
  - `WebhookIT`：以 JDK `HttpServer` 作本地接收方。
- **匿名入口**：`SecurityConfig.PUBLIC`（登录、刷新、退订、OIDC）和 `PUBLIC_READ`。`PublicAccessWebFilter`（`HIGHEST_PRECEDENCE + 10`）在安全链之前为 `/api/public/**` 做开关、限方法和 `TokenBucketLimiter` 限流。16b-1 新增 `ClientAddresses`（`clientOf(ServerHttpRequest)`，可信代理）和迁移 V32。
- **正文读取**：没有读原始字节的接口；`FileUploadService` 有"按上限读、超限中止"的写法；`PayloadTooLargeException` 映射为 413。
- **平台只追加表的先例（16a）**：`sys_mail_message`（`varchar(36)` 主键，`process_seq_id` 外键指向 `op_process`，`jabiz_protect_append_only`，列入 `PlatformSchemaMigration.PLATFORM_TABLES`）。`MailAttempt` 是平台直接插入、不进审计的日志，以只读数据视图展示。
- **保留**（21 §3）：只能以实体的 `RetentionPolicy` 声明，并出现在到期报告中；只追加表不能删除。
- **功能清单**：`docs/guide/platform-features.md` 第 252、447 行写着"不做入站 Webhook"。

**实施细则（D37 未写明之处，写入 D37 的实现说明）**
1. **接收是平台内部流程** `INBOUND_RECEIVE`（`internal()`，权限 `inbound.receive`，只有系统身份能运行，同 `SendWebhook` 的检查）。控制器在流程之外完成验签与提取，然后以 `RequestContext.system` 执行流程。流程有两个步骤：`RecordInboundEvent`（直接插入，不经 `ChangeSet`，不进审计，同 `MailAttempt`）和 `PublishEvent`。这样操作记录、Outbox 的操作号和观测与其他平台写入一致。输入 record 的 `body` 组件标 `@Sensitive`，不进 `input_summary`。
2. **事件载荷只带引用**：`{inboundEventId, provider, externalEventId, eventType, receivedTime}`，不含正文。消费者流程用平台步骤 `LoadInboundEvent.of(ctx -> 编号, 键)` 读取，并解析为 `Map`（数值用 `BigDecimal`）。理由：正文不复制进 `sys_outbox_event`（那里不受 `inbound.read` 保护，还可能被出站订阅转发）；而 `EventSubscription` 的输入函数不能做 I/O。（待确认 Q1）
3. **事件类型**是 `jabiz.inbound.<提供方>.<外部类型>`。启动检查对白名单中的每个类型拼出全名，检查规则和 100 字符的上限。
4. **提取**：
   - `stripe`：取正文 JSON 顶层的 `id`、`type`，并记下 `livemode`。
   - `hmac-sha256`：取请求头 `X-Jabiz-Event-Id`、`X-Jabiz-Event-Type`，与 D33 的出站格式对称，jabiz 实例之间可以互连。
   - 提取在验签之后进行。事件号最长 255 字符，不合格即拒绝。
5. **签名**：
   - `stripe`：签名内容是 `t + "." + 原样正文`。头中可以有多个 `v1`，其中任一个与任一配置的密钥匹配即通过；`v0` 和未知方案忽略；`t` 缺失、重复或不是数字即拒绝。
   - 容差：`|Clock 时间 − t| ≤ tolerance`（缺省 5 分钟，上限 1 小时，同时拒绝过旧和过于靠后的时间戳，Q6）。
   - 密钥换用：`secrets` 列表（每个至少 32 字符）。
6. **响应**：
   - 签名错、过期、超过大小、不是 UTF-8 或 JSON、缺少事件号：一律 400，用同一个 problem `INBOUND_REJECTED`（Q3）。具体原因只进日志与观测标签 `reason`，不存储。
   - 未知提供方 404；非 POST 405；超限 429，带 `Retry-After`。
   - 接受、重复、白名单外：都在提交后答 200 `{"received":true}`。
7. **大小**：先看 `Content-Length`，超过即 400、不读正文；再以 `DataBufferUtils.join(body, max)` 读取（分块传输超限时中止）。`max-body-size` 缺省且最大为 1 MB，提供方只能调低。正文必须是严格合法的 UTF-8，以 `text` 原样保存，另存 `body_sha256`（Q2）。
8. **去重**：`UNIQUE (provider, external_event_id)`。并发的重复在唯一键上等待，之后以 `UniqueKeyViolationException`（按约束名识别）失败并整体回滚，答 200。白名单外的类型答 200、不存储，观测 `outcome=ignored`。
9. **限流**：`InboundWebhookWebFilter` 与 `PublicAccessWebFilter` 同样的位置和写法，每个提供方一个 `TokenBucketLimiter`，键为 `ClientAddresses.clientOf`。缺省每地址每分钟 120（Q4），不受 `jabiz.public.enabled` 影响。
10. **读取与保留**：平台实体 `InboundEvent` 有只读数据视图 `urn:jabiz:dataset:platform:InboundEvent`（读取权限 `inbound.read`，`processOnlyWrites`），列表不含正文，详情含正文。应用可以声明 `RetentionPolicy.of("InboundEvent")…from("receivedTime")`，进入到期报告；物理删除不在本阶段（Q5）。正文不进日志、操作摘要和观测。

**表与迁移**（`db/jabiz/V34__inbound_webhooks.sql`；与 16e 按合并顺序编号；加入 `PlatformSchemaMigration.PLATFORM_TABLES`）

`sys_inbound_event`，只追加：

| 列 | 定义 |
|---|---|
| `inbound_event_id` | `varchar(36)` 主键 |
| `provider` | `varchar(40)` |
| `external_event_id` | `varchar(255)` |
| `event_type` | `varchar(100)` |
| `livemode` | `boolean`，可空 |
| `signed_time` | `timestamptz` |
| `received_time` | `timestamptz` |
| `content_type` | `varchar(100)` |
| `body` | `text`，非空 |
| `body_sha256` | `char(64)` |
| `process_seq_id` | 外键指向 `op_process` |

- 约束：`UNIQUE (provider, external_event_id)`。
- 索引：`(provider, event_type, received_time)`、`(process_seq_id)`。
- 执行 `SELECT jabiz_protect_append_only('sys_inbound_event')`。
- 被拒的请求不建表，只记日志与指标（D37 第 2 条）。

**改动**
- **core `com.jabiz.inbound`**（纯 Java，没有 JSON 库）：
  - `InboundSignatureScheme`：`verify(请求头, 正文字节, 密钥列表, 时刻, 容差) → InboundVerification`。
  - `InboundVerification`：通过，或原因 `MISSING_HEADER` / `MALFORMED` / `NO_MATCH` / `TOO_OLD` / `TOO_NEW`。
  - `StripeSignature`、`HmacSha256Signature`、`InboundSchemes.of(名)`。
  - `WebhookSignature` 增加 `matchesAny`。
  - `core/build.gradle.kts` 的覆盖率门禁加入 `com.jabiz.inbound.*`。
- **runtime `com.jabiz.runtime.inbound`**：
  - `InboundWebhookProperties`：`@ConfigurationProperties("jabiz.inbound-webhooks")`，`providers.<名>` 下有 `scheme`、`secrets`、`event-types`、`tolerance`、`max-body-size`、`rate-limit.per-minute|max-clients`；`toString` 遮蔽密钥。
  - `InboundWebhookChecks`：类别 `INBOUND`，一次报全。检查名字 `[a-z][a-z0-9-]{0,39}`、未知方案、缺少或过短的密钥、白名单为空、拼出的类型非法、容差和大小越界。
  - `InboundWebhookWebFilter`、`InboundWebhookController`（WebFlux，读原始正文）。
  - `InboundReceiver`：控制器与场景回放共用的接收逻辑。
  - `InboundEventExtractor`（Jackson）、`InboundProcesses`（`INBOUND_RECEIVE` 和 Bean）、`RecordInboundEvent`。
  - `LoadInboundEvent`：公开的平台步骤。
  - `InboundEvents`：事件名、`InboundEventRef` 和 `input(DomainEvent)`。
  - `InboundEntities`、`InboundPermissions`（`inbound.read`、`inbound.write`、`inbound.receive`）、`InboundRejectedException`。
- **runtime 其他**：
  - `SecurityConfig.PUBLIC` 加 `POST /api/inbound/*`，它同时不经认证转换器，带过期令牌也不会得到 401。
  - `PlatformObservations.INBOUND = "jabiz.inbound.receive"`，标签只有 `provider`、`outcome`、`reason`。
  - `PlatformErrorCodes.INBOUND_REJECTED` 与三语消息；`ProblemStatuses` 映射为 400。
  - 平台权限目录（同 `mail.read` 的登记处）。
- **场景回放**：步骤 `inbound: {provider, body, at?}`，用配置的密钥签名后调用 `InboundReceiver`，然后接 `deliverEvents: true`。
- **app**：
  - `application-dev.yml` 与测试配置中的提供方 `demo-pay`（`scheme: stripe`，`secrets: [${JABIZ_DEMO_PAY_WEBHOOK_SECRET:…dev}]`）；`application.yml` 中不放。
  - `commerce.CommercePayments`：订阅 `commerce.payment-received`，事件 `jabiz.inbound.demo-pay.payment_intent.succeeded`。
  - 流程 `ORDER_RECORD_PAYMENT`：`LoadInboundEvent` → 计算出 `data.object.metadata.orderNo`、`amount_received`、`id` → `LoadEntity` 订单 → 写 `paidTime`、`paymentRef`（`processOnly`）。金额不符或找不到订单时，`CreateTask` 交给持有 `commerce.order.write` 的人，而不是拒绝（拒绝会让消费者无限重试）。
  - 应用迁移 `V17__order_payment.sql`（按合并顺序编号），三语消息。
  - 可选的手工演示脚本 `tools/demo/inbound-demo.sh`：用 openssl 签名后 curl。
- **文档**：
  - 11 新增 §2.5 入站 Webhook；10 的匿名接口清单；15 §6 限流同样适用；13 观测；21 §1（入站事件不进审计，同 `MailAttempt`）。
  - 07 场景步骤 `inbound`；D37 的实现说明；`platform-features.md` 删去"不做"并新增一节；CLAUDE.md 第 4 节的引用不变。
  - OpenAPI 快照与 `pnpm gen:api`。

**测试**
- **core 单元**：
  - `StripeSignatureTest`：固定向量，在注释中给出 openssl 计算方式；多个 `v1` 和换密钥；忽略 `v0`；空白与顺序；缺 `t`、重复 `t`、`t` 非数字；容差边界（正好等于、超过 1 秒、未来）；错密钥；改一个字节。
  - `HmacSha256SignatureTest`：与 `WebhookSignature.sign` 往返。
  - jqwik：任意正文和密钥，签名后验证通过；任意单字节变化后验证失败。
- **runtime 单元**：
  - `InboundWebhookChecksTest`：所有问题一次报出。
  - `InboundEventExtractorTest`：缺 `id` 或 `type`、正文不是对象、`id` 过长、通用格式的请求头。
  - `InboundWebhookPropertiesTest`：`toString` 中没有密钥。
- **集成 `InboundWebhookIT`**（`backend/app`，真实 PostgreSQL，`WebTestClient`）：
  - 正确签名 → 200；存档正文的 SHA-256 与发送的字节相同；Outbox 事件类型正确，载荷中没有正文；`deliverPending` 后示范流程只执行一次，订单已付款。
  - 同一事件号再次到达（正文相同或不同）→ 200，仍只有一行、一个事件；4 路并发的重复 → 一行。
  - 签名错、正文被改、密钥错、超出容差（过旧、过新）、缺头、不是 UTF-8、不是 JSON → 400 且响应正文相同；`sys_inbound_event`、Outbox、`op_process` 中都没有新行。
  - 大于 1 MB 的正文，带 `Content-Length` 和分块两种 → 400，不存储。
  - 白名单外的类型 → 200，不存储、无事件。
  - 未知提供方 404；GET 405；带垃圾 `Authorization` 头照常处理（不是 401）。
  - 超限 → 429 带 `Retry-After`；伪造的 XFF 不能绕过（16b-1 合入后）。
  - 第二个密钥（换用中）签名的请求被接受。
  - 消费者第一次失败 → Outbox 退避重试，效果只产生一次。
  - 管理员经 `/api/processes/INBOUND_RECEIVE` 调用被拒。
  - 没有 `inbound.read` 读数据视图 403；列表不含正文，详情含正文。
  - `op_process.input_summary` 中没有正文。
  - 通用 `hmac-sha256` 提供方：平台自己的出站 Webhook 投到本机的入站接口被接受，验证 D33 与 D37 格式对称。
  - `sys_inbound_event` 上没有执行过 UPDATE / DELETE。
- 场景 `commerce/order_payment.yml`。`PlatformSchemaMigrationTest`、启动检查、`ArchitectureTest` 照常通过；BlockHound 开启（HMAC 只占 CPU，在事件循环上执行可以接受）。

**风险**
- 每个请求最多 1 MB 留在内存中，由限流、`Content-Length` 预检和 Netty 的连接数限制。
- Stripe 的投递来自少数地址，按地址限流可能挡住突发；被挡的事件由 Stripe 重试（最长 3 天）。缺省值可按提供方配置。
- 测试与场景使用固定的 `Clock`，签名时间必须取同一个时钟。
- 原样正文含个人信息，按 D5 只追加、不能删除（Q5）。
- 消费者重试用尽后由运维处理，没有重新投递的工具（Q8）。
- 依赖唯一键冲突时约束名能被正确识别。
- 与 16b-1（`ClientAddresses`、V32）的合并顺序。

**验收标准**
- [x] `stripe` 与 `hmac-sha256` 的验签是 core 中的纯函数：多个 `v1`、换密钥、时间容差，常量时间比较（`StripeSignatureTest`、属性测试）。
- [x] 验签失败、超出容差、超过 1 MB 一律 400 且不存储；白名单外 200 不存储；重复（含并发）200 且只有一行（`InboundWebhookIT`）。
- [x] 接受的事件原样存档在只追加的 `sys_inbound_event`，经 Outbox 以 `jabiz.inbound.<提供方>.<类型>` 交给流程，只处理一次、失败重试；请求中不执行业务流程。
- [x] 正文只有持有 `inbound.read` 才能读，不进操作摘要、日志和 Outbox 载荷；应用可声明保留策略。
- [x] `/api/inbound/**` 匿名但按地址限流；配置问题在启动时一次报全（`INBOUND`）。
- [x] 示范 `demo-pay` → `ORDER_RECORD_PAYMENT` 可运行，场景 `commerce/order_payment` 通过；没有任何对 Stripe 的网络调用。
- [x] 11 §2.5、10、15、21、07、D37 实现说明、功能清单已更新；`./gradlew check`、OpenAPI 快照与生成的类型已更新。

**不在本阶段**：Stripe 以外的提供方方案（如 Slack、GitHub 的签名方式，需要时加 core 方案即可）、重新投递的工具、入站事件的物理删除或匿名化、来源 IP 白名单（Q4）。

**确认**（2026-10-11）：计划与其中的待确认点（Q1–Q8）全部按计划中的缺省处理。即：
Q1 事件载荷只带引用，正文以 `LoadInboundEvent` 读取；Q2 正文按严格 UTF-8 存 `text` 并存 SHA-256；Q3 各种 400 用同一个 `INBOUND_REJECTED`，未知提供方 404；
Q4 限流按提供方设置，缺省每地址每分钟 120，不做来源 IP 白名单；Q5 原样正文只追加保存，应用可声明保留策略进入到期报告，物理删除另议；
Q6 时间容差同时拒绝过旧与过于靠后的时间戳；Q7 示范在 `SalesOrder` 上记 `paidTime` / `paymentRef`，提供方配置只在 dev 与测试配置中；Q8 不做重新处理的工具。

**完成情况（分支 `1.2/phase-16d-inbound-webhooks`）**：平台迁移 `V34__inbound_webhooks.sql`，示范应用 `V17__order_payment.sql`；
测试 core `StripeSignatureTest`（9，openssl 计算的固定向量）、`HmacSha256SignatureTest`（5）、属性测试 `InboundSignatureProperties`（2 × 300 次）；
runtime `InboundEventExtractorTest`（6）、`InboundWebhookChecksTest`（4）、`InboundWebhookPropertiesTest`（2）、`InboundReceiverTest`（7）、`InboundSubscriptionChecksTest`（2）、
`LoadInboundEventTest`（2）、`ScenarioParserTest` 补一条；app `CommercePaymentsTest`（5）、`InboundWebhookIT`（18，真实 PostgreSQL，服务监听真实端口）；
场景 `commerce/order_payment.yml`。`./gradlew check` 与 `pnpm lint typecheck test check:api` 通过。
验收标准逐条对应：验签与属性测试见 core；400 / 不存储（含响应正文逐字节相同，`sys_inbound_event`、Outbox、`op_process` 均无新行）、1 MB（带长度与分块）、
白名单外、重复与 4 路并发见 `InboundWebhookIT`；只处理一次与失败重试见其出站→入站用例（消费者第一次失败）；`input_summary` 中正文为 `***`、Outbox 载荷只有引用、
日志中没有正文（`OutputCaptureExtension`）、表上没有 UPDATE / DELETE（SQL 记录与触发器）；`inbound.read` 与保留策略（示范声明 7 年）；限流 429 与伪造的 `X-Forwarded-For`；
启动检查 `INBOUND`；示范与场景不访问网络。

与计划的出入：
- **事件号与类型只取已签名的正文**：`hmac-sha256` 取 D33 正文的 `eventId` / `eventType`，而不是计划中的请求头 `X-Jabiz-Event-Id` / `X-Jabiz-Event-Type`；
  这两个头不在 D33 的签名范围内（只签时间戳与正文），若取自请求头，截获者可在容差内重放同一正文并换掉事件号（绕过去重）或类型（换处理流程）。头仍可出现，但必须与正文一致，否则 400。
- **正文不是 `InboundEvent` 的字段**（评审后另放在 `sys_inbound_event_body`）：数据视图的列表接口返回实体的全部字段（不只列表列），为使"列表不含正文"成立，`InboundEvent` 只有 `bodySha256`，
  正文以新接口 `GET /api/inbound-events/{id}/body`（`inbound.read`，`no-store`）逐个读取。数据视图为只读（`readOnly`，同 `MailAttempt`），不是计划写的 `processOnlyWrites`。
- **流程只有一个步骤**：`RecordInboundEvent` 插入行后直接经 `EventPublisher` 发布，因为 `PublishEvent` 的事件类型在定义时固定，而这里的类型取决于提供方与外部类型。
- 输入组件名为 `rawBody` 而不是 `body`：`@Sensitive` 的组件名在所有 JSON 中遮蔽，`body` 会误伤其他流程与事件中同名的属性。
- `InboundEvents.ref(event)`（计划写作 `input`）返回 `InboundEventRef`；`LoadInboundEvent.of(提供方, 类型, 编号, 键)` 给出 `ReceivedEvent`（`at("a.b.c")` 取路径值）；步骤只允许系统身份或持有 `inbound.read` 的执行者。
- 观测 `jabiz.inbound.receive` 的标签为 `provider`、`result`（`ACCEPTED` / `DUPLICATE` / `IGNORED` / `REJECTED` / `FAILED`）、`reason`，外加所有平台观测共有的
  `outcome` / `status`（`outcome` 已被平台占用，故结果放在 `result`）；另有 `jabiz.inbound.rate_limited`。
- 示范的复核待办交给 `commerce.order.place`（订单数据视图的写权限；示范中没有 `commerce.order.write`）；`ORDER_RECORD_PAYMENT` 声明不授予任何人的 `commerce.order.pay`，只由消费者以系统身份运行。
- 接收前先查已有的行（重复投递不占操作与序号），并发的重复仍以唯一键 `sys_inbound_event_external_key` 识别。`Stripe-Signature` 重复或多于 16 项即拒绝；`livemode` 不是布尔即拒绝；
  JSON 不允许重复键与尾随内容；`Content-Type` 只保留前 100 个可见 ASCII 字符。
- 场景步骤多一个可选的 `outcome`（缺省 `ACCEPTED`，可写 `DUPLICATE` / `IGNORED` / `REJECTED`）。

**评审后的修订**（安全 S1–S4、代码评审 R1–R10，11 §2.5 与 D37 实现说明已同步；每项先写失败的测试）：
- S1 示范消费者不再信任事件内容：`LoadInboundEvent.of(提供方, 类型, …)` 必须声明它处理的提供方与类型，不符即失败；`ORDER_RECORD_PAYMENT` 先拒绝系统以外的执行者
  （`internal()` 只是不进目录），核对模式（`commerce.payments.accept-test-mode`，示范为 true）与币种 `jpy`，不符登记待办；Javadoc 写明 Stripe 金额以最小货币单位表示。
  测试：`CommercePaymentsTest`（5），IT 中管理员经流程接口调用 403、`it-peer` 的消费者读 `demo-pay` 的事件失败。
- S2 正文移到单独的只追加表 `sys_inbound_event_body`（同一事务写入，修改了尚未合并的 V34，加入 `PLATFORM_TABLES`）；`LoadInboundEvent` 与正文接口改为联表读取。
  IT 断言 `sys_inbound_event` 没有 `body` 列，两张表都没有执行过 UPDATE / DELETE。
- S3 数值的十进制指数绝对值超过 1000：接收时 400（原因 `number_out_of_range`），`LoadInboundEvent` 解析时也拒绝（`InboundEventExtractorTest`、`LoadInboundEventTest`）。
- S4 11 §2.5、15 §6 与 D37 写明：在未配置为可信代理的代理之后，所有发送方共用一个限流桶。
- R2 新的启动检查 `InboundSubscriptionChecks`：订阅了已配置提供方未列出的类型为错误，列出却无人订阅为警告（`InboundSubscriptionChecksTest`）。
- R3 `InboundReceiverTest`（Mockito 替身）：唯一键 `EXTERNAL_KEY` → `DUPLICATE`，其他约束名照常抛出；IT 以 `pg_constraint` 断言约束名。
- R4 出站→入站的重试用例先排空（`deliverPending()` 为 0，至多 50 轮）再设失败，`HANDLED` 只看本用例的事件号。
- R5 限流与 1 MB 的用例经真实端口（`WebTestClient.bindToServer`）：伪造的 `X-Forwarded-For` 来自不可信的 127.0.0.1 不能绕过，带长度与分块的超限都 400，正好 1 MB 被接受；
  本类开启 Netty 的 PARANOID 泄漏检测并断言没有服务器端读取的缓冲区泄漏（测试自己的 HTTP 客户端的报告不计）。
- R6 观测 `result` 改为与 `OutboxDeliverer` 一样的大写（`ACCEPTED` / `DUPLICATE` / `IGNORED` / `REJECTED`），意外错误为 `FAILED` 与 `reason=error`；
  以 `TestObservationRegistry` 测试（runtime 新增测试依赖 `micrometer-observation-test`）。
- R7 / R8 复核待办的标题含金额、币种与原因代码，空值显示 `-`、发送方的值只显示可打印的前 40 个字符；找到订单时指向订单；来源键 `payment:<提供方>:<外部事件号>`
  （过长时用其 SHA-256）；付款编号（`[A-Za-z0-9_-]{1,255}`）与订单号（订单号规则）格式不对也登记待办而不是失败。场景快照随之更新。
- R9 补测：`LoadInboundEvent` 的 403 与 404（IT）、小数为 `BigDecimal`（`LoadInboundEventTest`）、`InboundEvent` 出现在到期报告中（IT）、`Content-Type` 的处理（`InboundReceiverTest`）。
- R10 `secrets` / `event-types` 中的空项不再在绑定时抛出 NPE，而由 `INBOUND` 检查报告（空白类型同样）；测试的 `freePort` 绑定失败时重试。

**已知问题**：限流在内存中、按实例计数，所有经 `WebTestClient` 绑定上下文的测试请求共用地址 `unknown`；每个请求最多 1 MB 在内存中，HMAC 在事件循环上计算；
原样正文只追加、不能删除，到期只进报告（物理删除另议）；消费者重试用尽后没有重新投递的工具；通用后台没有显示正文的页面（只有接口）；
既有场景的 `SalesOrder` 快照未重写（新字段为空，比较时不受影响）；`V34` / `V17` 须与并行的 16e（V35 / V18）按合并顺序协调。
**将另行提议（平台全局，需用户决定，本阶段不实现）**：`internal()` 的流程默认拒绝系统身份以外的调用者。目前 `internal()` 只使流程不进目录，权限仍照常检查，
因此每个内部流程（如 `INBOUND_RECEIVE`、`WEBHOOK_DELIVER`、示范的 `ORDER_RECORD_PAYMENT`）都要在自己的步骤中拒绝其他调用者。


### 16e 外部服务声明与异步外部作业（4.5–6 天，分 16e-1 / 16e-2 两个 PR）

决策 D38。比阶段表的 3–4 天多 1.5–2 天：领取、租约、取消的并发正确性，用量记录，以及完成流程的回调都需要单独测试。

**现状（`1.2/platform`）**
- **`BlockingStep`**（core）：由 `ProcessExecutor.dispatch` 在 `boundedElastic`（虚拟线程）上执行，约定不访问数据库；对出站调用没有任何限制或观测。平台唯一的出站 HTTP 是 `SendWebhook`（JDK `HttpClient` 异步、`Redirect.NEVER`，`WebhookChecks.refusal` 只允许 https、http 只限本机、主机须在白名单中）。
- **`AFTER_COMMIT`**：进程内按 `RetryPolicy` 重试，记录在 `op_process_after_commit`，不做持久化重试（06 §4）。
- **定时任务与事件**：`JobDefinition` 只有 cron，`JobRunner` 用 ShedLock；Outbox 消费者在事务中运行流程（D38 已放弃用它做长调用）。
- **可复用的部件**：`FindOrphanFiles` 中的 `FOR UPDATE SKIP LOCKED`、`StorageEngine.inTransaction / inNewTransaction`、`ProcessExecutor.executeChild`、`Outbox.append`、`PlatformObservations`、16a 的 `CheckedStep` 与只读平台实体的写法。
- **`ArchitectureTest`（app）**：只禁止业务代码使用 Reactor 和 `StepHandler`、读系统时钟，对网络没有规则。
- 没有任何用量记录。

#### 16e-1 外部服务声明、阻塞步骤中的调用（1.5–2 天）

**16e-1 状态**：☑ 已完成（分支 `1.2/phase-16e1-external-services`，PR 待建）。完成说明见本节末尾。

**要求**
1. **声明**（core `com.jabiz.external.ExternalService`，Bean）：
   ```java
   ExternalService.define("demo-ai", s -> s.baseUrl("https://ai.example.com/v1").allowedHosts("ai.example.com")
       .secret("apiKey", "JABIZ_DEMO_AI_KEY").connectTimeout(ofSeconds(5)).requestTimeout(ofSeconds(30))
       .idempotencyHeader("Idempotency-Key").maxResponseSize(4 * 1024 * 1024))
   ```
   - 名字 `[a-z][a-z0-9-]{0,39}`；环境变量名 `[A-Z][A-Z0-9_]*`。
   - 配置 `jabiz.external-services.<名>.base-url | allowed-hosts` 可以按环境覆盖，测试和开发指向本地桩服务（Q10）。
2. **调用**（core 接口，runtime 实现）：
   - `ExternalServices#http(服务)` 返回同步的 `ExternalHttp`；`Request` 有方法、相对路径或白名单内的绝对地址、请求头、正文、不超过服务上限的超时、幂等键；`Response` 有状态、请求头、字节或文本。`ExternalServices#secret(服务, 名)` 取密钥。
   - runtime 的 `JdkExternalHttp`：
     - 每个服务一个 JDK `HttpClient`（`Redirect.NEVER`，3xx 原样返回）。
     - 每次请求都检查主机（同 `WebhookChecks.refusal` 的规则，抽出为共用的 `OutboundAddresses`）。
     - 超时、响应大小上限，观测 `jabiz.external.call`（标签只有 `service` 与 `outcome`：2xx/4xx/5xx/timeout/refused）。
     - 在非阻塞线程上调用即抛 `IllegalStateException`（只能在 `BlockingStep` 或作业处理器中调用）。
3. **调用范围**：`ProcessExecutor.dispatch` 在执行 `BlockingStep` 时，在该虚拟线程上设置 `ExternalCallScope`（ThreadLocal，执行完即清除），内容为 `processSeqId`、步骤名、是否试运行。
   - 试运行中缺省拒绝外部调用，服务声明 `.allowInDryRun()`（只读、幂等的调用）才放行（Q15）。
   - `ExternalServices.idempotencyKey(后缀)` 返回 `jabiz-<processSeqId>-<后缀>`：`AFTER_COMMIT` 重试时不变，可交给提供方去重。
4. **密钥**：启动时经 `Environment` 读取，只保存在内存中，不进日志、`toString` 和异常消息。缺失时报错；`dev` 下是告警，调用时才失败。
5. **启动检查 `EXTERNAL`**：名字唯一且合法、白名单非空、基础地址的主机在白名单中且为 https（http 只限本机）、超时为正且有上限、环境变量缺失。一次报全。
6. **ArchUnit**（`app` 的 `ArchitectureTest`）：业务包不得依赖 `java.net.http..`、`java.net.Socket`、`java.net.URLConnection` / `HttpURLConnection`、`URL#openConnection|openStream`、`javax.net..`、`org.springframework.web.reactive.function.client..`、`org.springframework.web.client..`。使用官方 SDK 的方式见 Q9；应用分支怎样得到这条规则见 Q21。
7. 文档：06 新增 §2.x"外部调用"（D38 第 1、2 条，`AFTER_COMMIT` 或幂等键）；CLAUDE.md 第 4 节已写明，不改。

**16e-1 完成说明**
- 实现：core `com.jabiz.external`（`ExternalService`、`ExternalHttp`（`Request` / `Response`）、`ExternalServices`、`ExternalCallException`，覆盖率门禁已加入）；
  runtime `com.jabiz.runtime.external`（`ExternalServiceRegistry`、`ExternalChecks`（类别 `EXTERNAL`）、`JdkExternalHttp`、`ExternalServicesImpl`、`ExternalCallScope`）；
  `web/OutboundAddresses`（`WebhookChecks.refusal` 抽出，两处共用）；`ProcessExecutor.dispatch` 设置 `ExternalCallScope`，试运行经 Reactor 上下文传给子流程的阻塞步骤；
  `PlatformObservations.EXTERNAL_CALL`；共用 ArchUnit 规则 `PlatformArchRules.noDirectNetworkAccess`（runtime 测试夹具，Q21）。06 §2.3、07、11 §2.4、13、D38 实现说明、功能清单与教程已更新。
- 示范：`demo-ai`（`ModerateText.DEMO_AI`，`allowInDryRun`）；新流程 `PRODUCT_RENAME`（`actsOn Product`，权限 `commerce.product.write`）以阻塞步骤 `ModerateText`
  审核新名称，被判为不当即 422 `COMMERCE_TEXT_INAPPROPRIATE`。没有迁移。手工演示的桩服务 `tools/demo/ai-stub/AiStub.java`（Q20，16e-2 再加 `/v1/generate`）。
- 测试：core `ExternalServiceTest`、`ExternalHttpTest`；runtime `OutboundAddressesTest`、`ExternalChecksTest`、`JdkExternalHttpTest`（本地 `HttpServer`）；
  app `ExternalServiceIT`（`StubAiServer`）与 `ArchitectureTest` 的正例与反例（`archfixture.DirectNetworkCalls`）。
- 与计划不同之处：
  - 失败为 core 的 `ExternalCallException`，API 502 `EXTERNAL_CALL_FAILED`（三语；参数为服务名与种类），步骤也可用它报告不可用的回答（`BAD_RESPONSE`）。
  - 在阻塞步骤之外（没有 `ExternalCallScope`）调用同样拒绝，不只是非阻塞线程；`secret` 与 `idempotencyKey` 也只在阻塞步骤中可用。16e-2 的作业处理器将设置自己的范围。
  - D38 第 1 条的"重试策略"不在服务声明中：按 Q22 不自动重试，需要重试的调用放提交后步骤。
  - `platformCheck` 不检查密钥（`PlatformCheckLauncher` 传 `jabiz.external.missing-secrets=ignore`：静态校验没有部署的环境）；`dev` 中为告警。
    因此非 `dev` 启动示范应用还需要 `JABIZ_DEMO_AI_KEY`（任意值）：CI 的 e2e 作业随机生成，`docker/app-entrypoint.sh` 缺省生成占位值，CLAUDE.md 第 6 节与快速上手已注明。
  - 示范流程是新的 `PRODUCT_RENAME`（计划只写了 `ModerateText`；16e-2 的商品文案流程另加）。
- 评审后的修改（代码评审与安全评审）：
  - 请求头：值只允许可见 ASCII、空格与制表符，客户端自己设置的头（`Host`、`Content-Length`、`Transfer-Encoding`、`Proxy-*` 等）不能设置，名字不区分大小写；
    JDK 客户端自己的拒绝变为被拒的调用（502），不带含值的 JDK 消息。绝对地址须与基础地址的协议、端口相同。`Response.text()` 遇非法字符集名按 UTF-8。
    `Request` / `ExternalService` 的 `toString` 不含查询与第一段之后的路径。
  - `ExternalCallScope` 不能伪造：只有 `ProcessExecutor` 能打开（`open` 检查调用方），新的共用规则 `PlatformArchRules.noPlatformInternals` 禁止业务包依赖
    `com.jabiz.runtime.external`；`noDirectNetworkAccess` 增加 Spring 的 `http.client`、Netty / Reactor Netty、`UrlResource`、`URL#getContent`、
    XML 解析器的 `parse(String uri)`（护栏，不是沙箱）。
  - 导入：行的外部调用失败是该行的问题 `EXTERNAL_CALL_FAILED`，预览（试运行）与提交都不再失败为 500。
  - `EXTERNAL` 一次报全：重复的声明报告自己的问题；`jabiz.external-services` 以文本读入，转换失败是 `EXTERNAL` 的问题而不是启动失败；`missing-secrets` 增加 `ignore`，消息改写。
  - 失败种类的文字三语（消息 `EXTERNAL_CALL_FAILED.kind.<种类>`；`MessageCatalog` 的通用规则：参数值有 `<代码>.<参数>.<值>` 的消息即显示其文字。
    评审建议的键名为 `external.kind.*`，改用这一通用规则，以便导入问题的参数仍是原值）。
  - 测试桩：通用的 `StubHttpServer` 移到 runtime 测试夹具（应用分支可复用），`app` 的 `StubAiServer` 只保留审核的缺省应答。
  - Docker Compose 增加服务 `ai-stub`（`eclipse-temurin:21-jdk` 直接运行 `tools/demo/ai-stub`，与 `app` 共用网络，`app` 以 `http://localhost:8091/v1` 调用）；
    入口脚本在提供了真实密钥而地址仍是示例地址时拒绝启动。CLAUDE.md 第 6 节本地开发一行补上 `JABIZ_DEMO_AI_KEY`。
  - 示范的说法改为"只有改名流程审核"：通用表单与数据视图 API 写入的商品名不经审核（未改为 `processOnly()`）。
  - 测试补充：在计算步骤中调用得到的具体 `IllegalStateException`、试运行传到子流程、步骤失败后范围已清除、经整个流程的超时（502）。
- 已知限制：主机白名单按名字比较，不解析 IP（DNS 指向内网地址不在检查之内，同 Webhook）；超时依靠 JDK 客户端取消请求，卡住的连接最迟随客户端关闭释放；
  JDK 客户端沿用 JVM 的代理设置（`https.proxyHost` 等）；官方 SDK 不在本阶段（Q9）。请求头的值只能是 ASCII，非 ASCII 的值须由调用方编码。
  示范门户允许自助注册而邮件未开启，`platformCheck` 有一条 `SECURITY` 告警（开启邮件需要 SMTP 配置，演示与 CI 没有，故保留）。
  本环境没有 Docker 守护进程：Compose 的改动只经 `docker compose config` 校验，`ai-stub` 只在本机以 `java tools/demo/ai-stub/AiStub.java` 验证过；
  CI 的 compose 作业只启动 `app`，不受影响。

#### 16e-2 异步外部作业（3–4 天）

**16e-2 状态**：☑ 已完成（分支 `1.2/phase-16e2-external-jobs`，PR 待建）。完成说明见本节"验收标准"之前。

**要求**
1. **作业类型**（core `ExternalJobType`，Bean）：
   ```java
   ExternalJobType.define("commerce.product-copy", ProductCopyInput.class, ProductCopyResult.class, t -> t
       .handler(ProductCopyHandler.class).services("demo-ai").timeout(ofSeconds(90))
       .retry(3, ofSeconds(10), ofMinutes(10)).maxConcurrency(2)
       .onCompleted("PRODUCT_COPY_APPLY").onFailed("PRODUCT_COPY_FAILED"))
   ```
   - 处理器 `ExternalJobHandler<I, R>`（core，同步）：`ExternalJobResult<R> run(I input, ExternalJobContext ctx)`。
   - `ExternalJobContext`：`jobId`、`attempt`、`idempotencyKey()`（固定为 `jabiz-job-<jobId>`，各次尝试相同）、`deadline`、`http(服务)` 与 `secret(…)`（只限声明的服务）。处理器不访问数据库，与阻塞步骤同样的约束。
   - `ExternalJobResult.of(值).usage("promptTokens", 812)`：用量名为 `[a-zA-Z][A-Za-z0-9._-]{0,63}`，数量为非负整数。
   - `ExternalJobException.permanent(码, 消息)` 不重试；其他异常和超时按可重试处理。
2. **登记**：步骤 `StartExternalJob.of(类型, ctx -> 输入).reference(ctx -> …).outputKey("jobId")` / `.when(…)`（runtime，平台 I/O 步骤）在流程事务内插入 `sys_external_job`（请求人为根操作的操作人，带 `process_seq_id`），作业号写入上下文。
   - 输入含 `@Sensitive` 值 → 违规 `EXTERNAL_JOB_INPUT_SECRET`；JSON 超过 64 KiB → `EXTERNAL_JOB_INPUT_TOO_LARGE`。
   - 回滚或试运行即没有作业。流程带 `Idempotency-Key` 时，重放返回同一个作业号。
3. **执行器** `ExternalJobExecutor`：
   - 配置：`jabiz.external-jobs.executor.enabled`（测试关闭）、`poll-interval` 1 s、每实例的 `max-in-flight` 8。测试调用 `runPending()`。
   - **领取**（短事务）：
     - 对每个类型先 `pg_advisory_xact_lock(hashtext('jabiz.external-job:'||类型))`，统计有效领取数（有领取行、无尝试结果、租约未到期），得到剩余名额（Q11）。
     - 选出到期的作业：没有最终结果、没有取消、没有有效领取、首次执行或上次可重试且已过退避（`initial × 2^(n−1)`，不超过上限，时间取 `Clock`）。`FOR UPDATE OF j SKIP LOCKED LIMIT 名额`。
     - 插入 `sys_external_job_claim (job_id, attempt_no)`，租约到期 = 超时 + 30 秒。真正的保证是主键：竞争失败者在插入时冲突后跳过。
     - 租约过期而没有结果的领取，由下一次领取补记一次尝试 `LEASE_EXPIRED`（可重试）。
   - **执行**：处理器在 `boundedElastic` 上运行，带 `Mono.timeout`（尽力中断），**没有任何数据库事务或连接被占用**。
   - **记录**（独立事务）：
     - 插入 `sys_external_job_attempt`（`SUCCEEDED` / `FAILED` / `TIMED_OUT`、是否可重试、错误码、遮蔽后的说明、耗时）和 `sys_external_job_usage`（失败的尝试也记录已消耗的用量）。
     - 成功、不可重试或重试用尽、或执行中已被取消时，插入 `sys_external_job_outcome`（主键 `job_id`，只有一个最终结果）：状态，结果 JSON 不超过 256 KiB（超过即 `FAILED`，码 `RESULT_TOO_LARGE`），并 `Outbox.append("jabiz.external-job.finished", {jobId, jobType, status})`。
     - 迟到的僵尸实例在主键冲突后丢弃自己的结果。
4. **完成**：平台订阅 `jabiz.external-jobs` 接收 `jabiz.external-job.finished`，运行内部流程 `EXTERNAL_JOB_COMPLETE`（系统身份）。其步骤 `RunExternalJobCallback` 读取作业和结果，以 `executeChild` 调用声明的流程：
   - 成功：输入 `ExternalJobCompletion(jobId, jobType, requestedBy, reference, result（按声明的类型反序列化）, usage, attempts, finishedTime)`。
   - 失败：输入 `ExternalJobFailure(…, errorCode, errorMessage)`。
   - `CANCELLED` 不回调（Q13）。只调用一次、失败重试都沿用 D14。
   - 完成与失败流程必须是 `internal()`（否则任何人都能伪造结果），由它们以 `CreateTask` / `SendMail` 通知请求人。
5. **取消**：`POST /api/external-jobs/{id}/cancel` → `EXTERNAL_JOB_CANCEL`。请求人本人，或持有 `external-job.cancel` 的人；他人 404。先对作业行 `FOR UPDATE`，与领取串行。
   - 插入 `sys_external_job_cancel`；没有有效领取则同时写最终结果 `CANCELLED`。
   - 执行中被取消的，执行器完成后写 `CANCELLED` 并丢弃结果。
   - 已结束的作业 → 422 `EXTERNAL_JOB_FINISHED`。
6. **查看**：`GET /api/external-jobs/{id}`（请求人本人或 `external-job.read`）返回状态（`QUEUED` / `RUNNING` / `WAITING_RETRY` / `SUCCEEDED` / `FAILED` / `CANCELLED`）、尝试次数、时间、用量合计、`reference`，不含输入与结果（Q14）。
   - 只读平台实体 `ExternalJob`、`ExternalJobAttempt`、`ExternalJobUsage` 的数据视图（`external-job.read`）供后台查看。
   - 报表模板 `jabiz.external_job_usage`：按类型、用量名、月份汇总。平台不计费。
7. **观测**：`jabiz.external.job`（标签 `job_type`、`outcome`）；计数器 `jabiz.external.usage`（标签 `job_type`、`metric`），加上数量。
8. **启动检查 `EXTERNAL`（扩充）**：
   - 类型名唯一且合法；处理器恰好一个 Bean；服务已声明。
   - 完成与失败流程存在、是 `internal()`、输入类型正确。
   - 超时、重试、并发为正；输入与结果是 record，输入中没有 `@Sensitive` 组件。
   - `StartExternalJob` 引用的类型已声明（`CheckedStep`）。
9. **场景回放**：
   - `runExternalJobs: true`：执行到没有到期的作业为止。
   - `completeExternalJob: {jobType, reference?, result, usage, status?}`：不调用处理器，经同一记录路径写入结果，使应用的场景不依赖外部服务（Q17）。

**表与迁移**（`db/jabiz/V37__external_jobs.sql`，16k 先合并（V36），按合并顺序编为 V37；全部只追加并执行 `jabiz_protect_append_only`，都有主键（D27 封存），加入 `PLATFORM_TABLES`）

- `sys_external_job`：
  - 列：`job_id uuid` 主键、`job_seq bigint GENERATED ALWAYS AS IDENTITY UNIQUE`、`job_type varchar(100)`、`input text`、`reference varchar(200)`、`requested_by text`、`process_seq_id`（外键指向 `op_process`）、`created_time`。
  - 索引：`(job_type, job_seq)`、`(requested_by, created_time)`、`(reference)`。
- `sys_external_job_claim`：`(job_id, attempt_no)` 主键，`instance_id varchar(100)`、`claimed_time`、`lease_until`。
- `sys_external_job_attempt`：`(job_id, attempt_no)` 主键，外键指向领取表；`outcome`（CHECK：`SUCCEEDED` / `FAILED` / `TIMED_OUT` / `LEASE_EXPIRED`）、`retryable`、`error_code varchar(100)`、`error_detail varchar(2000)`、`duration_ms`、`finished_time`。
- `sys_external_job_usage`：`(job_id, attempt_no, metric)` 主键，`quantity bigint`。
- `sys_external_job_cancel`：`job_id` 主键，`cancelled_by`、`cancelled_time`、`process_seq_id`。
- `sys_external_job_outcome`：`job_id` 主键，`status`（CHECK：`SUCCEEDED` / `FAILED` / `CANCELLED`）、`result text`、`error_code`、`error_detail`、`attempts`、`finished_time`。

`SELECT … FOR UPDATE` 只加行锁，不触发只追加表的 UPDATE 触发器。

**改动**
- **core**：
  - `com.jabiz.external`：`ExternalService`、`ExternalHttp`（`Request` / `Response`）、`ExternalServices`、`ExternalJobType`、`ExternalJobHandler`、`ExternalJobContext`、`ExternalJobResult`、`ExternalJobException`、`ExternalJobCompletion`、`ExternalJobFailure`。
  - 错误码 `EXTERNAL_JOB_INPUT_SECRET`、`EXTERNAL_JOB_INPUT_TOO_LARGE`、`EXTERNAL_JOB_FINISHED` 与三语消息；覆盖率门禁加入 `com.jabiz.external.*`。
- **runtime `com.jabiz.runtime.external`**：
  - `ExternalServiceRegistry`（`PlatformCheck`）、`ExternalServicesImpl`、`JdkExternalHttp`、`ExternalCallScope`、`ExternalChecks`、`ExternalProperties`。
  - `StartExternalJob`、`ExternalJobExecutor`、`ExternalJobRecorder`、`ExternalJobProcesses`（`EXTERNAL_JOB_COMPLETE`、`EXTERNAL_JOB_CANCEL`、订阅）、`RunExternalJobCallback`。
  - `ExternalJobController`、`ExternalJobEntities`、`ExternalJobPermissions`（`external-job.read` / `cancel` / `write` / `complete`）。
- **runtime 其他**：
  - `ProcessExecutor.dispatch` 设置 `ExternalCallScope`。
  - `webhook/WebhookChecks.refusal` 抽出为 `web/OutboundAddresses`，两处共用。
  - `PlatformObservations`（`EXTERNAL_CALL`、`EXTERNAL_JOB`）、`PlatformSchemaMigration`、场景回放的两个新步骤。
- **app**：
  - 服务 `demo-ai`：`application.yml` 中是 https 的示例主机；`application-dev.yml` 与测试配置覆盖为本机桩服务。
  - 阻塞步骤 `ModerateText`：调用 `/v1/moderate`，声明 `allowInDryRun`，被判为不当即违规。
  - 流程：`PRODUCT_COPY_REQUEST`（`actsOn Product`，权限 `commerce.product.write`）、`PRODUCT_COPY_APPLY`（internal，写 `Product.marketingCopy`（`processOnly`）并给请求人 `CreateTask`）、`PRODUCT_COPY_FAILED`（internal，给请求人 `CreateTask`）。
  - `ProductCopyHandler`：POST `/v1/generate`，响应为 `{text, usage:{promptTokens, completionTokens}}`，不绑定任何提供方。
  - 应用迁移 `V18__product_marketing_copy.sql`（按合并顺序编号）；三语消息。
  - 测试夹具 `StubAiServer`（JDK `HttpServer`）：可编排成功、5xx、慢响应、4xx，并记录 `Idempotency-Key`。
  - 手工演示用 `tools/demo/ai-stub`（Q20）。
  - `ArchitectureTest` 的新规则，加一个反例测试。
- **文档**：06 §2.x；11 新增 §4a 异步外部作业；13；07（`runExternalJobs`、`completeExternalJob`）；D38 的实现说明；`platform-features.md`；扩展指南（怎样加外部服务与作业类型）；OpenAPI 快照与 `pnpm gen:api`。

**测试**
- **core 单元**：`ExternalServiceTest`（名字、主机、环境变量名、超时）、`ExternalJobTypeTest`、`ExternalJobResultTest`（用量名、负数）；退避计算的 jqwik 属性测试（单调、不超过上限）。
- **runtime 单元**：
  - `ExternalChecksTest`：一次报全。缺少环境变量（dev 下为告警）、主机不在白名单、http 指向非本机、完成流程不是 internal、输入类型不符、未声明的服务、并发为 0。
  - `JdkExternalHttpTest`（本地 `HttpServer`）：拒绝白名单外的主机和带用户信息的地址；3xx 不跟随；响应超过上限即失败；超时；在非阻塞线程上调用即拒绝；试运行中拒绝（除非 `allowInDryRun`）；密钥不出现在 `toString` 和异常中。
- **集成 `ExternalServiceIT`**：阻塞步骤中的审核调用被不当时，流程 422；调用未声明的主机失败；观测已记录。
- **集成 `ExternalJobIT`**（真实 PostgreSQL 和 `StubAiServer`）：
  - 请求立即返回作业号，桩服务尚未被调用；流程因后续违规回滚时没有作业。
  - `runPending` 后处理器只调用一次，带 `Idempotency-Key: jabiz-job-<id>`；结果为 `SUCCEEDED`，有用量行和事件；`deliverPending` 后完成流程以系统身份运行一次，商品被更新，请求人收到待办；再次执行和再次投递都没有效果。
  - 5xx 两次后成功：退避按时钟生效；三次尝试的幂等键相同；三次尝试的用量都有记录。
  - 处理器抛 `permanent` → 不重试，失败流程收到错误码；重试用尽 → `FAILED`，失败流程只调用一次。
  - 慢响应超过超时 → `TIMED_OUT` 后重试。
  - 两个执行器、`maxConcurrency=1`、5 个作业 → 任何时刻桩服务至多一个并发请求，每个作业只处理一次。
  - 两个执行器争同一作业 → 只有一个领取。
  - 租约过期（模拟崩溃）→ 补记 `LEASE_EXPIRED` 后重新领取。
  - 僵尸实例迟到 → 仍只有一个最终结果，完成流程只调用一次。
  - 取消：排队中的 → `CANCELLED`、不执行、不回调；执行中的 → 结果丢弃、`CANCELLED`；他人取消 404；已结束 422。
  - 状态接口：请求人可见，他人 404，持有 `external-job.read` 的可见。
  - 完成流程不能经 `/api/processes` 调用；输入含 `@Sensitive` 值或过大被拒。
  - 处理器运行期间，应用没有处于 "idle in transaction" 的连接（查 `pg_stat_activity`）。
  - `sys_external_job_*` 上没有执行过 UPDATE / DELETE。
- 场景 `commerce/product_copy.yml`（`completeExternalJob`）。`ArchitectureTest` 的正例与反例；BlockHound 开启；`PlatformSchemaMigrationTest`；OpenAPI 快照。

**风险**
- **超时无法强行停止卡住的处理器或 SDK**：中断只是尽力而为，靠请求超时与租约兜底。
- **至少一次**：执行完成后、记录之前崩溃，会以同一个幂等键再调用一次。不支持幂等键的提供方（如生成类接口）可能计费两次；用量按尝试记录，可以统计。
- **轮询开销**：每个类型每次轮询一次查询（`NOT EXISTS` 加索引）。作业很多时以后可以加水位线。
- **时间**：advisory 锁的哈希冲突只会让两个类型串行领取，无害。退避与租约都取 `Clock`（同 `OutboxDeliverer`，便于测试），多实例的时钟偏差靠租约余量吸收。
- **SDK 的出站不受主机白名单约束**（Q9）；二进制结果不支持（Q16）。
- **完成流程持续 422**：Outbox 重试用尽后由运维处理。
- **合并顺序**：迁移编号与 16b-1、16d 的合并顺序；`ProcessExecutor` 的改动（`ExternalCallScope`）会经过每个阻塞步骤。

**16e-2 完成说明**
- 实现：core `ExternalJobType`、`ExternalJobHandler`、`ExternalJobContext`、`ExternalJobResult`、`ExternalJobException`、`ExternalJobCompletion`、
  `ExternalJobFailure` 与错误码（三语）；core `StepSource`（见下）。runtime `com.jabiz.runtime.external`：`ExternalJobRegistry`、`ExternalJobChecks`（类别 `EXTERNAL`）、
  `ExternalJobRecorder`（全部 SQL）、`ExternalJobExecutor`、`ExternalJobProcesses`（`EXTERNAL_JOB_COMPLETE`、`EXTERNAL_JOB_CANCEL`、消费者 `jabiz.external-jobs`）、
  `RunExternalJobCallback`、`CancelExternalJob`、`ExternalJobController`、`ExternalJobEntities`、`ExternalJobPermissions`；`ExternalCallScope.openJob`（执行器加入允许的调用方）；
  `process.steps.StartExternalJob`；迁移 `V37__external_jobs.sql`（16k 先合并占了 V36，故改为 V37）；报表模板 `jabiz.external_job_usage`；`PlatformObservations.EXTERNAL_JOB` / `EXTERNAL_USAGE` 与 `monoOutcome`；
  场景步骤 `runExternalJobs`、`completeExternalJob`；共用 ArchUnit 规则 `handlersStayOutOfTheDatabase`；`StubHttpServer.maxConcurrent()`。
- 示范：`ProductCopy`（`PRODUCT_COPY_REQUEST` / `APPLY` / `FAILED`、作业类型 `commerce.product-copy`）、`ProductCopyHandler`、`Product.marketingCopy`（`processOnly`，
  应用迁移 `V18__product_marketing_copy.sql`）、三语消息、`StubAiServer` 与 `tools/demo/ai-stub` 的 `/v1/generate`。
- 测试：core `ExternalJobTypeTest`、`ExternalJobResultTest`、`ExternalJobBackoffProperties`（jqwik）；runtime `ExternalJobChecksTest`、`JdkExternalHttpTest`
  （作业范围只限类型的服务）；app `ExternalJobIT`（评审后 41 个，含两个执行器与 `maxConcurrency=1`、争同一作业、租约过期、迟到实例、执行中取消、
  `pg_stat_activity` 无 idle in transaction、只追加）、runtime `ExternalJobExecutorTest`、`StartExternalJobTest`、`ArchitectureTest` 的新规则与两个反例、场景 `commerce/product_copy.yml`；OpenAPI 快照与 `pnpm gen:api`。
- 与计划不同之处：
  - **D41 待定**：`internal()` 只是不进目录。`EXTERNAL_JOB_COMPLETE` 的步骤、示范的 `PRODUCT_COPY_APPLY` / `PRODUCT_COPY_FAILED` 与测试的回调流程都在步骤中拒绝
    系统以外的操作人（403，同 16d 的 `RecordInboundEvent` 与示范消费者）；D41 决定后改为平台统一的做法。指南要求应用的回调流程照做。
  - `StartExternalJob` 在 `com.jabiz.runtime.process.steps` 而不是 `runtime.external`：16e-1 的共用规则禁止业务包依赖 `runtime.external`。
    其流式写法（`.reference(…).outputKey(…)`）需要 core 新增的 `StepSource`：`ProcessDefinitionBuilder.step` / `afterCommit` 接受它，`StepSpec` 实现它，既有声明不变。
  - `job_id` 为 `varchar(36)`（计划为 `uuid`），与其他平台实体的标识一致；尝试与用量表另有唯一的 `attempt_id` / `usage_id` 作实体主键（同 `MailAttempt`）。
    `requested_by` / `cancelled_by` 为 `varchar(100)`（同其他操作人列）。
  - 没有 `ExternalProperties` 类：执行器以 `jabiz.external-jobs.executor.*`（`enabled`、`poll-interval`、`max-in-flight`、`instance-id`）配置。
  - `ExternalJobContext.recordUsage`：处理器边做边记用量，失败的尝试也保留（计划只有结果上的用量）。`HANDLER_ERROR` 只记异常类名（"遮蔽后的说明"）；
    外部调用失败为 `EXTERNAL_CALL_FAILED`，被拒的调用不重试。
  - 状态接口另含 `requestedBy`、首次领取时间与最近的错误码；已取消而尝试仍在执行的作业显示 `CANCELLED`；租约过期待补记的显示 `WAITING_RETRY`。
  - 取消接口回答取消后的状态；`EXTERNAL_JOB_CANCEL` 的权限 `external-job.cancel` 只用于流程 API，入口自己判断请求人。
  - `demo-ai` 的请求超时改为 60 s（给生成文案），审核的请求自己限 10 s。
  - 新增的共用 ArchUnit 规则 `handlersStayOutOfTheDatabase` 同时约束阻塞步骤。
- 评审后的修改（安全评审与代码评审；每个缺陷先写失败的测试）：
  - **开始的限制**（M1）：`ExternalJobType.maxOpenPerRequester(n)`（缺省 10）与 `oneOpenPerReference()`；`StartExternalJob` 在类型 + 请求人、类型 + 引用的
    advisory 锁下数未结束（无最终结果、未取消）的作业，超过即 422 `EXTERNAL_JOB_LIMIT`（三语，`scope` 的文字按 `EXTERNAL_JOB_LIMIT.scope.<值>`）。
    示范每个商品一个（`oneOpenPerReference`）。V35 增加 `(job_type, requested_by)`、`(job_type, reference)` 索引（取代 `(reference)`）。
    测试：请求人上限、四个并发开始同一引用只有一个成功、取消后可再开始、示范的第二次请求 422。
  - **操作记录不含结果**（M2）：`SensitiveDataMasker.summary` 对 `ExternalJobCompletion` 只记 `resultBytes`，对 `ExternalJobFailure` 只记 `errorMessageLength`；
    `ExternalJobFailure.toString` 遮蔽消息；`ExternalJobType.problems()` 也拒绝结果类型中的 `@Sensitive` 组件。测试读取 `op_process.input_summary`。
  - **D41**（M3）：`1.2/phase-16l-internal-processes` 合入之前保留各回调流程的 `requireSystem` 检查；启动检查已要求完成与失败流程为 `internal()`；
    没有 `ProcessController` 特例。16l 先合入时再合并平台。
  - 低严重度：取消先在不加锁的行上授权再锁行；`completeWithoutHandler` 只在 `jabiz.external-jobs.simulated-results=true`（只有测试配置）时可用；
    启动检查要求作业超时长于所用服务的请求超时（测试类型因此调整：用 `demo-ai` 的为 90 s，1 s 的 `it.quick` 不调用服务）；
    遮蔽字段的值不按名字查（理由写在 11 §4a 与 `StartExternalJob`）；21 §3.1 与 11 §4a 写明输入与结果的个人信息与 `RetentionPolicy`。
  - 代码评审：
    1. 处理器返回 null 原先没有任何记录：现在为永久的 `NO_RESULT`（`switchIfEmpty`）。
    2. 执行器是 `SmartLifecycle`：停止时不再领取，等正在执行的尝试记录完，至多 `jabiz.external-jobs.executor.shutdown-grace`（30 s）；
       记录在数据库失败时退避重试 3 次。测试：停止等到尝试记录完且不再领取；超过宽限即返回。
    3. 结果写成 JSON 后再读回结果类型，读不回为永久的 `BAD_RESULT`；完成时保存的结果读不回则以 `RESULT_UNREADABLE` 调用失败流程。
       测试 `RESULT_TOO_LARGE`、`BAD_RESULT`、`RESULT_UNREADABLE`。
    4. 输入中的秘密只按 `@Sensitive` 声明反射查找（含嵌套 record、集合、Map、数组与 `Object` 类型的值），不再按名字片段：`maxTokens` 可以用。
    5. 轮询开销：每个类型的水位线（最小的无最终结果的 `job_seq`），三个查询都以它过滤；每隔 `watermark-refresh`（1 min）一次全量扫描并刷新
       （晚提交而编号较小的作业最多晚一个间隔）；`sys_external_job_claim (lease_until)` 索引（V35 未合入，直接改）。测试：水位线以下的作业不被普通轮询领取、
       由全量扫描领取；`EXPLAIN`（关闭顺序扫描）使用各索引。
    6. 卡住的作业：尝试次数已达类型现在的上限而无最终结果的，领取时记 `FAILED` `ATTEMPTS_EXHAUSTED`（已取消的记 `CANCELLED`）；
       已不再声明的类型的作业在全量扫描时记 `FAILED` `TYPE_REMOVED`，其完成不调用流程也不失败；输入读不出或找不到处理器为永久的 `BAD_INPUT`。
    7. `max-in-flight` 不再让一个类型独占：每次轮询从上次取得名额的类型的下一个开始；实例已满时也对每个类型补记过期租约、结算不能再尝试的作业。
       空位在执行结束、通知调用方之前释放（原先 `doFinally` 晚于完成，紧接着的 `runPending` 看不到空位）。
    8. 退避由 Java 按 `backoffAfter` 算成毫秒数组交给 SQL（`ExternalJobExecutorTest`），属性测试覆盖生产所用的计算。
    9. `StubHttpServer` 在写出响应之前就离开"正在应答"，并发断言不再偶发失败。
    10. 取得类型的锁之后才读时钟；用量计数器在提交之后增加；补充测试：租约过期未补记时取消、`max-in-flight` 限制单个实例；
        "处理器运行时没有空闲事务"在处理器停在闸门上时多次采样。
- 已知限制：超时后仍在运行的旧处理器线程不计入并发（中断尽力而为）；迟到被丢弃的尝试不记用量；晚提交的作业最多晚一个 `watermark-refresh`；
  开始的限制在流程事务结束前一直持有 advisory 锁，同一请求人同一类型的开始因此串行。

**验收标准**
- [x] 外部服务以 `ExternalService` Bean 声明；阻塞步骤只经 `ExternalServices` 发出请求，只发往白名单主机，有超时、观测、试运行保护；业务代码直接使用网络客户端时 ArchUnit 失败；配置问题一次报全（`EXTERNAL`）。（16e-1：`ExternalServiceTest`、`ExternalChecksTest`、`JdkExternalHttpTest`、`ExternalServiceIT`、`ArchitectureTest`）
- [x] `StartExternalJob` 在流程中登记作业并立即返回作业号；回滚则没有作业。（`ExternalJobIT`）
- [x] 作业在事务之外执行，多实例只领取一次，按类型限制并发，超时与退避重试按类型声明，各次尝试的幂等键相同（`ExternalJobIT`）。
- [x] 完成后以系统身份调用完成流程（输入为作业号、结果、用量）只一次；失败用尽后调用失败流程；请求人可以收到通知。（`ExternalJobIT`、场景 `product_copy`）
- [x] 用量按尝试记录，可以汇总；请求人可以取消作业、查看状态。（`ExternalJobIT`、`jabiz.external_job_usage`）
- [x] 示范 `demo-ai`（本地桩服务）的商品文案作业和审核短调用可运行，场景通过；`./gradlew check` 通过；06、11、13、07、D38 实现说明、功能清单已更新。

**不在本阶段**：工作流编排、流式结果、作业之间的依赖（D38 第 6 条）；二进制结果；计费；SDK 的主机强制（Q9）。

**确认**（2026-10-11）：计划与其中的待确认点（Q9–Q22）全部按计划中的缺省处理，分 16e-1 / 16e-2 两个 PR。即：
Q9 只允许经 JDK HTTP 的 `ExternalHttp`（官方 SDK 不在本阶段，主机白名单管住全部出站）；Q10 服务在代码中声明，`jabiz.external-services.<名>.*` 可按环境覆盖地址与主机；
Q11 按作业类型的并发上限为全局（advisory 锁）；Q12 完成与失败流程的输入为平台的 `ExternalJobCompletion` / `ExternalJobFailure`，结果按声明的类型反序列化，流程必须是 `internal()`；
Q13 取消后不调用回调；Q14 状态接口不返回输入与结果；Q15 试运行中缺省拒绝外部调用，`allowInDryRun()` 才放行；Q16 结果只支持不超过 256 KiB 的 JSON（二进制结果如 AI 封面图到 Q8 再定，例如处理器存为文件、结果只放编号）；
Q17 场景用 `completeExternalJob` 模拟结果；Q18 不做 `dedupKey`；Q19 分两个 PR；Q20 手工演示的桩服务放在 `tools/demo/ai-stub`；
Q21 平台提供共用的 ArchUnit 规则，应用的 `ArchitectureTest` 引用它；Q22 `ExternalHttp` 在步骤内不自动重试。

### 16k 定时任务逐项独立事务（2.5–3 天）

由 QuizBuks 提出（Q4 平台缺口 6、7、10；Q5 D-Q5-9，待确认 Q5-2 已按缺省确认）：Q5b 的自动提交任务 `qb.attempt-expire` 依赖本阶段，Q4 的 `qb.publication-tick` 也将迁过来。
补充 D14 第 5、6 条（任务只调用流程、同一计划时刻只执行一次），需新增决策 **D42**（见"待确认 K-1"），不取代任何决策。
分支 `1.2/phase-16k-job-items`，从 `1.2/platform` 拉出。

**16k 状态**：☑ 已完成（分支 `1.2/phase-16k-job-items`，PR 待建）。完成说明见本节末尾。

**现状（`origin/1.2/platform`，`abf6f7e`）**
- **声明**：core `JobDefinition<I>(name, cron, zone, process, input: Instant -> I, lockAtMostFor)`，只有 `cron(…)` 一种工厂；名称同时是 ShedLock 锁名。
- **执行**：`JobRunner.run(job, scheduledTime)`（阻塞）在 ShedLock（`jabiz_shedlock`，`lockAtMostFor` 缺省 10 分钟，`jabiz.jobs.lock-at-least-for` 缺省 30 s，真实时间）下以系统身份
  执行**一个根流程**，幂等键 `job:<名称>:<计划时刻>`；写一行只追加的 `sys_job_run`（`SUCCEEDED` / `REPLAYED` / `FAILED`，`process_seq_id`、`error`）。观测 `jabiz.job.run`（标签 `job`、结果）。
  `JobScheduler` 的线程池缺省 2 个线程。
- **一批多项**只能写成根流程 + `CallProcess.forEach`：子流程与根流程同一事务（06 §5）。于是
  1. 一项失败，整批回滚（Q4 已知问题：持续失败的一项每分钟挡住同批全部发布）；
  2. 锁持有到整批提交：任务持有发布 A 与商家钱包 S 后再取发布 B，而在线提交持有 B 等 S，即死锁（`40P01`），整批回滚（Q5 D-Q5-9）；热门发布上的在线请求要等完整批；
  3. `HoldLock` 只有全局等待 `jabiz.process.lock-timeout`（缺省 30 s）：任务里的一项会为一把被占用的锁等满 30 s。
- **可复用的模式**：`OutboxDeliverer` —— 每项一个流程、一个事务；失败追加到只追加表（`sys_outbox_attempt`），待处理的查询按"失败次数 + 上次失败时刻"做指数退避；
  并发重复由主键挡住。`ProcessExecutor.run` 带幂等键时先重放、并发竞争时再重放（`isIdempotencyRace`）。
- **模板**：`AdvancedQueryExecutor` 的外层包装按 `list.defaultSort` + `list.key` 排序并加 `LIMIT :__limit`（05 §5），模板自己不写外层 `LIMIT` / `ORDER BY`。
- **场景回放**：`runJob: {job, at, outcome}` 直接调 Spring 中的 `JobRunner`；`lock-at-least-for` 30 s 是真实时间，同一任务在一个场景中运行两次，第二次得到 `LOCKED`（缺口 7，Q4 以场景 `properties` 绕行）。
- **`WithdrawApproval`** 只有 `of(subject, entityId)`，没有 `when`（缺口 10，Q4 以不存在的单据标识绕行）。
- **D41**：`internal()` 流程只允许系统身份运行；任务与事件消费者以系统身份运行。
- **迁移**：平台最新 `V34__inbound_webhooks.sql`，16e-2 占 `V35`；示范应用最新 `V17`，16e-2 占 `V18`。

**要求**

1. **声明逐项任务**（core `com.jabiz.job`）：
   ```java
   JobDefinition.forEach("qb.attempt-expire", "0 * * * * *", ZONE,
           JobItems.template("qb.job.expired-attempts",           // 选取模板（须有 list.defaultSort 与 list.key）
               scheduledTime -> Map.of("now", scheduledTime),     // 模板参数（同步、无 I/O）
               "attempt_id"),                                     // 项键所在的结果列
           QB_ATTEMPT_EXPIRE,                                     // 每项流程（internal()）
           (scheduledTime, row) -> new ExpireInput(Rows.uuid(row.get("attempt_id"))))
       .maxItems(2000)                       // 每次至多处理的项数，缺省 500，上限 10 000
       .parallelism(1)                       // 同时执行的项数，缺省 1，上限 8
       .itemLockWait(Duration.ofSeconds(2))  // 每项中 HoldLock 的最长等待，缺省 2 s，上限为全局 lock-timeout
       .itemRetry(Duration.ofMinutes(1), Duration.ofHours(1))  // 连续失败的退避：起点、上限（见要求 5）
       .maxRunTime(Duration.ofMinutes(5))    // 超过即不再开始新项，缺省 lockAtMostFor 的一半
       .lockAtMostFor(Duration.ofMinutes(10));
   ```
   - `JobDefinition` 改为带种类（`SINGLE` / `PER_ITEM`）的不可变类型；`cron(…)` 工厂、现有访问器与语义不变（应用的单流程任务不用改）。
   - 选取只用 SQL 模板（不用"选取流程"）：模板是声明、可启动检查、按系统身份运行时范围不受限，且不产生操作记录；需要业务参数时由每项流程自己读（`LoadParams`）。
   - 每项输入由 `(计划时刻, 行) -> 输入` 构造（同步、无 I/O，与现有 `input` 同样的约束）。
2. **一次运行**（`JobRunner`，仍在 ShedLock 下、仍阻塞调用）：
   1. **选取**：以系统身份、在只读短事务中运行选取模板，`__limit = maxItems + 退避中的项数`（后者上限 1 000，见 5），按模板的 `defaultSort` + `key` 排序；过滤掉退避中的项后取前 `maxItems` 项。
      选取失败（模板错误、超时）→ 运行 `FAILED`，不处理任何项。项键为空或同一次选取中重复 → 该项记为失败（`JOB_ITEM_KEY_INVALID`），其余照常。
   2. **逐项执行**：每项以系统身份作为**根流程**执行每项流程，各自一个事务、各自的 `op_process`；幂等键 `job:<名称>:<计划时刻的 epoch 毫秒>:<项键>`
      （项键 1–36 个字符，`[A-Za-z0-9._:-]`，UUID 即可；名称至多 64，总长 ≤ 119，在 128 之内）。请求标识 `job-<名称>-<毫秒>-<项键>`。并发至多 `parallelism`。
   3. **每项的结果**：

      | 结果 | 条件 | 记录 |
      |---|---|---|
      | `SUCCEEDED` | 提交 | 只计数；该项此前有未恢复的失败时追加一行 `RECOVERED` |
      | `REPLAYED` | 同一计划时刻已处理过（幂等键重放） | 只计数 |
      | `BUSY` | `ConcurrentUpdateException`（锁等待超过 `itemLockWait`（`55P03`）、死锁（`40P01`）、版本冲突） | 只计数，**不算失败、不退避**；仍然到期的项由下一次运行重新选取 |
      | `FAILED` | 其他异常（422、500 等） | 追加 `sys_job_item_failure`（`FAILED`）、warn 日志（连续第 5 次起 error） |
      | `DEFERRED` | 退避中（选取后过滤掉），或超过 `maxRunTime` 未开始 | 只计数 |

      一项的任何结果都不影响其他项；`FAILED` 的项事务已回滚，没有留下任何效果。
   4. **运行记录**：`sys_job_run` 照旧一行，增加各结果的计数与 `items_selected`、`items_more`（选取到的超过 `maxItems`）。逐项任务的运行结果：
      没有 `FAILED` 项 → `SUCCEEDED`；有 → `PARTIAL`；选取失败 → `FAILED`。（`REPLAYED` 只属于单流程任务。）
3. **`HoldLock` 的逐步等待**：
   - `HoldLock.exclusive(ctx -> 名称, Duration 最长等待)` / `HoldLock.shared(…, Duration)`（新增重载；原工厂不变）；
   - `ExecutionOptions.lockWait(Duration)`（平台内部，经 Reactor 上下文传给 `HoldLock`，子流程同样适用）：逐项任务以 `itemLockWait` 设置；
   - 一条取锁语句的 `lock_timeout` = min(步骤声明的等待, 执行选项的等待, `jabiz.process.lock-timeout`)；超时仍是 409（`ConcurrentUpdateException`），请求中即"快速失败"，任务项中即 `BUSY`（跳过到下一次）；
   - 只约束 `HoldLock`：平台自己的锁（审批、账本科目层级、编号等）与行锁照旧用全局时限（写入 06 §4.1）。
4. **"忙"的处理**：任务项遇到锁被占用，等至多 `itemLockWait` 后放弃本项（`BUSY`），不失败、不退避、不中止运行；由于每项一个事务，项只持有自己的锁，任务不会与在线请求形成 Q5 D-Q5-9 那样的环。
   同一次运行中两项争同一把锁时（`parallelism > 1`），后者可能 `BUSY`，下一次运行再处理（写入文档；Q4/Q5 用缺省 `parallelism = 1`）。
5. **失败与重试**（逐项、持久化，同 Outbox 的做法）：
   - 不在一次运行中重试；失败的项若仍被选中，由之后的运行重试。
   - 连续失败次数 = 该（任务, 项键）最近一次 `RECOVERED` 之后的 `FAILED` 行数。第 1 次失败后下一次运行即可重试；第 n 次（n ≥ 2）后等待 `min(起点 × 2^(n−2), 上限)`（缺省 1 min 起、1 h 封顶），期间 `DEFERRED`。
   - 不放弃：用尽也按上限每小时重试一次并写 error 日志（不需要另做"重新启用"的运维接口；见 K-5）。
   - 退避中的项另外放宽选取的 `__limit`，避免"毒项"占满 `maxItems` 使后面的项饿死。
6. **ShedLock 与集群**：
   - 一次运行（选取 + 全部项）在该任务的集群锁下，同一时刻只有一个实例运行该任务；项在该实例内按 `parallelism` 并发，不在实例间分发（见 K-6）。
   - `maxRunTime` 必须小于 `lockAtMostFor`（声明时检查）：到时不再开始新项（计为 `DEFERRED`），进行中的项照常结束，锁不会在运行中过期。即使过期、另一实例开始同一计划时刻，
     每项的幂等键保证同一项在同一计划时刻至多执行一次（并发时一方等待唯一约束后重放）。
   - 运行超过 cron 间隔时，下一时刻得到 `LOCKED`（不补跑）；逐项任务按状态选取，下一次运行自然接上。
   - 计划时刻相同的重跑（锁过期、场景中重复 `at`）：重新选取，已处理且仍被选中的项 `REPLAYED`，失败的项按退避处理。
   - 停机（`@PreDestroy`）：不再开始新项，进行中的项结束或回滚。
7. **D41**：每项流程必须是 `internal()`（启动检查 `JOB` 报错，见 K-4），其输入来自系统的选取，人不应能直接调用；单流程任务不受此限（兼容）。
8. **启动检查 `JOB`**（一次报全）：选取模板存在、不是公开模板、声明了 `list.defaultSort` 与 `list.key`、参数都被提供；项键列是模板的结果列；每项流程已注册且 `internal()`；
   `maxItems`、`parallelism`、`itemLockWait`、`maxRunTime < lockAtMostFor` 在范围内；`parallelism` 不超过 R2DBC 连接池上限的一半（告警）。
9. **观测**（13，D16）：运行照旧 `jabiz.job.run`（标签 `job`、`result`，结果增加 `PARTIAL`）；每项 `jabiz.job.item`（标签只有 `job` 与 `result`：`SUCCEEDED` / `REPLAYED` / `BUSY` / `FAILED`），
   不放项键、主键或输入；每项流程照常有 `jabiz.process` 观测，嵌在项观测之下。选取以 `jabiz.query.template` 记录。
10. **接口**：`GET /api/jobs`（`job.read`）每个任务增加 `kind`；最近 10 次运行带各结果计数；逐项任务另有"连续失败中的项"（项键、连续次数、最近错误（截至 200 字符）、下次可重试时刻，至多 50 条）。
    更新 OpenAPI 快照与 `pnpm gen:api`；通用后台若已有任务页，只在表格中加计数列。
11. **场景回放**（07 §3.1）：
    - `runJob: {job, at, outcome, items}`：逐项任务的 `outcome` 缺省 `SUCCEEDED`（可写 `PARTIAL`）；可选 `items: {succeeded, replayed, busy, failed, deferred}`，只比较写出的键。单流程任务不变。
    - **缺口 7**：`ScenarioContexts` 缺省设置 `jabiz.jobs.lock-at-least-for=0s`（场景的 `properties` 仍可覆盖）；同一任务在一个场景中连续运行不再 `LOCKED`。Q4/Q5 场景中的绕行可随后删除。
12. **`WithdrawApproval.when(条件, 审批对象, ctx -> 单据标识)`**（缺口 10）：条件不成立时不执行（与 `CallProcess.when`、`RequireApproval.when` 同样写法）。
13. **示范**（`app`）：任务 `commerce.unpaid-order-expire`（每天 03:10 东京时间）：选取模板 `commerce.job.unpaid-orders`（`PLACED`、未付款、下单超过 `app.commerce.unpaid-order-ttl`，缺省 14 天），
    每项执行新的内部流程 `ORDER_EXPIRE {orderId}`：取订单锁 `commerce.order:<id>`（`itemLockWait`）、重新读取，仍未付款才以子流程 `ORDER_CANCEL` 取消；否则什么也不做。
    `ORDER_RECORD_PAYMENT`（16d）同样取该订单锁，使"刚付款"与"过期取消"不会交错（见 K-7）。
14. **文档**：09 新增 D42；11 §4 新增 §4.1"逐项任务"与 §6 测试表；06 §2.1 表（`HoldLock` 的等待参数、`WithdrawApproval.when`）、§4.1（逐步等待只约束 `HoldLock`）、§5（`CallProcess.forEach` 同一事务，批量的独立事务用逐项任务）；
    07 §3.1（`runJob` 的 `items`、缺省 `lock-at-least-for=0s`）；13 观测表；CLAUDE.md 第 4 节"账本、事件、定时任务"加一句：
    "需要逐项提交的批量任务用 `JobDefinition.forEach`（每项一个事务、每项流程为 `internal()`），不用 `CallProcess.forEach` 做批量"；ROADMAP 阶段表加 16k 行与本节。

**表与迁移**

平台 `db/jabiz/V36__job_items.sql`（16e-2 占 V35；若 16k 先合并则互换，按合并顺序编号）：
```sql
-- 逐项任务的计数（单流程任务为空）；加列不执行 UPDATE，只追加的保护与封存的列清单（21 §2）照常。
ALTER TABLE sys_job_run ADD COLUMN items_selected  integer;
ALTER TABLE sys_job_run ADD COLUMN items_succeeded integer;
ALTER TABLE sys_job_run ADD COLUMN items_replayed  integer;
ALTER TABLE sys_job_run ADD COLUMN items_busy      integer;
ALTER TABLE sys_job_run ADD COLUMN items_failed    integer;
ALTER TABLE sys_job_run ADD COLUMN items_deferred  integer;
ALTER TABLE sys_job_run ADD COLUMN items_more      boolean;
ALTER TABLE sys_job_run DROP CONSTRAINT sys_job_run_outcome_check;
ALTER TABLE sys_job_run ADD CONSTRAINT sys_job_run_outcome_check
    CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'REPLAYED', 'PARTIAL'));

-- 逐项的失败与恢复：只追加（D5）。没有指向 sys_job_run 的外键：运行行在运行结束时才写，失败行随发生即写。
CREATE TABLE sys_job_item_failure (
    failure_id     uuid         PRIMARY KEY,
    job_name       varchar(80)  NOT NULL,
    item_key       varchar(36)  NOT NULL,
    kind           varchar(10)  NOT NULL CHECK (kind IN ('FAILED', 'RECOVERED')),
    run_id         uuid         NOT NULL,
    scheduled_time timestamptz  NOT NULL,
    attempt        integer      NOT NULL,   -- 连续第几次失败（RECOVERED 行为 0）
    error          text,                    -- 至多 2 000 字符，同 sys_job_run
    process_seq_id bigint       REFERENCES op_process (process_seq_id),  -- RECOVERED 行的成功操作
    recorded_time  timestamptz  NOT NULL    -- 取自 Clock
);
CREATE INDEX sys_job_item_failure_key_idx ON sys_job_item_failure (job_name, item_key, recorded_time DESC);
SELECT jabiz_protect_append_only('sys_job_item_failure');
```
加入 `PlatformSchemaMigration.PLATFORM_TABLES`；有主键，`INTEGRITY` 检查与封存照常（实现时先确认 `sys_job_run` 加列后封存校验通过，21 §2 的列清单处理）。
示范应用 `db/migration/V19__unpaid_order_expiry_idx.sql`（16e-2 占 V18）：若选取需要，按（状态、下单时间）在未付款订单上建部分索引；不需要则不加迁移。

**改动**

| 模块 | 类 | 改动 |
|---|---|---|
| core | `JobDefinition` | 种类 `SINGLE` / `PER_ITEM`；`forEach(…)` 工厂与 `maxItems`、`parallelism`、`itemLockWait`、`itemRetry`、`maxRunTime`；声明时校验范围 |
| core | `JobItems`（新） | 选取：模板 id、参数函数、项键列；`ItemRetry` record |
| core | `JobDefinitionTest` | 新工厂、范围校验、`maxRunTime < lockAtMostFor`、幂等键长度 |
| runtime | `JobRunner` | 分出 `runSingle` / `runItems`；选取（`AdvancedQueryExecutor`，只读事务、系统身份）；逐项执行（`Flux.flatMap(…, parallelism)` 后 `block`）；结果分类、失败与恢复记录、退避过滤、`maxRunTime`、停机；新增 `execute(job, time)` 返回 `JobRun`（结果 + 计数），`run` 仍返回 `Outcome`（增加 `PARTIAL`） |
| runtime | `JobItemFailures`（新） | 查询退避中的项（`DISTINCT ON (item_key)` 最近一行为 `FAILED` 的项及连续次数）、追加失败与恢复 |
| runtime | `JobChecks` | 要求 8 |
| runtime | `JobController` | 要求 10 |
| runtime | `ExecutionOptions`、`ProcessExecutor` | `lockWait` 选项，写入 Reactor 上下文 |
| runtime | `HoldLock` | `Metadata` 增加 `waitAtMost`；取 min 后设置 `lock_timeout` |
| runtime | `WithdrawApproval` | `Metadata` 增加条件，`when(…)` |
| runtime | `PlatformObservations` | `JOB_ITEM = "jabiz.job.item"` |
| runtime 测试夹具 | `Scenario`、`ScenarioRunner`、`ScenarioContexts` | `runJob.items`；缺省 `lock-at-least-for=0s` |
| runtime | `V36__job_items.sql`、`PlatformSchemaMigration` | 见上 |
| app | `commerce`：`OrderExpiry`（任务、`ORDER_EXPIRE`）、`queries/commerce/job_unpaid_orders.sql`、`ORDER_RECORD_PAYMENT` 加订单锁、消息（三语） | 示范 |
| docs | 09、11、06、07、13、CLAUDE.md、ROADMAP | 要求 14 |

**测试**

- core：`JobDefinitionTest`（工厂、范围、幂等键格式与长度）；覆盖率门禁照常。
- runtime：`JobChecksTest`（模板缺失、无 `list.key`、项键列不存在、公开模板、每项流程非 `internal()`、`maxRunTime ≥ lockAtMostFor`，一次报全）；`ScenarioParserTest`（`items`）。
- app 集成（真实 PostgreSQL，测试专用的任务与流程，在 `app` 测试源中）：`JobItemsIT`
  1. **一项失败不影响其他项**：5 项中第 3 项抛 422 → 4 项提交、1 项 `FAILED`，`sys_job_item_failure` 一行，运行 `PARTIAL`，计数正确；失败项没有留下任何写入与 `op_process`。
  2. **重跑幂等**：同一计划时刻再运行 → 已成功且仍被选中的项 `REPLAYED`、效果只有一次；失败项按退避 `DEFERRED`；拨动时钟越过退避后成功 → 追加 `RECOVERED`，连续次数归零。
  3. **退避与不饿死**：一个持续失败的项排在最前且 `maxItems = 2`，其他项仍在后续运行中被处理；退避时刻按 1 min、2 min、4 min… 封顶。
  4. **忙即跳过**：另一事务持有某项的 `HoldLock` 不释放 → 该项在 `itemLockWait` 内放弃（计时断言 < 全局时限），结果 `BUSY`、无失败行、不退避；锁释放后下一次运行处理它。
  5. **避免死锁**（Q5 的形状）：每项按"发布锁 → 钱包锁"取锁；并发的"在线请求"持有发布 B 后等钱包 S，任务第 1 项持 A 与 S、第 2 项要 B。断言：无 `40P01`，在线请求在任务第 1 项提交后即取得 S（不等整批）；
     对照组用 `CallProcess.forEach` 的旧写法复现死锁或整批回滚（作为文档化的反例）。
  6. **并发**：`parallelism = 4`、200 项，全部恰好一次；两个"实例"（各自连接池与锁提供者，同 `JobIT`）同时运行同一时刻 → 一个运行、一个 `LOCKED`；锁过期（`lockAtLeastFor = 0`、手动释放）后另一实例重跑同一时刻 → 全部 `REPLAYED`，无重复效果。
  7. **`maxRunTime`**：慢项使运行到时停止开始新项，余下计为 `DEFERRED`、`items_more` 正确。
  8. **D41**：每项流程以系统身份运行；人直接调用该内部流程 403 `PROCESS_INTERNAL`。
  9. **只追加**：`sys_job_run`、`sys_job_item_failure` 上没有 UPDATE / DELETE（触发器 + 断言）。
- `HoldLockIT`：步骤声明的 `waitAtMost` 与执行选项的等待都生效（取较小），超时 409；之后事务的 `lock_timeout` 恢复。
- `ApprovalIT`（或现有审批测试类）：`WithdrawApproval.when` 条件不成立时请求仍待审批，成立时撤回。
- `JobIT`：单流程任务的行为不变（回归）。
- `OpenApiSnapshotIT` 更新快照；`pnpm check:api`。
- 场景：`scenarios/commerce/unpaid_order_expiry.yml`（下单、付款一单、拨钟、`runJob` 两次（相隔数秒，不写 `properties`，覆盖缺口 7）、断言计数与订单状态）；`freight/monthly_close.yml` 快照不变。
- `./gradlew check`（含 `platformCheck`、ArchUnit、BlockHound）通过；涉及锁与系统身份，完成后 `/code-review` 与 `/security-review`。

**风险**

- **项的语义由应用保证**：平台只保证"同一计划时刻同一项至多一次"；跨计划时刻的重复处理（上一分钟成功、这一分钟仍被选中）要靠每项流程在锁内重新读取、已处理即不动（Q4 的 `QB_PUBLICATION_ADVANCE` 已如此）。写入 11 §4.1 与 Javadoc。
- **`op_process` 的增长**：每项一个根操作（Q5 峰值每分钟数千行），以前是每次运行一行加若干子操作，总行数相近，但幂等键的 `op_process_result` 每项一行。Q10 压测时评估；必要时 K-8 的选项。
- **`BUSY` 掩盖持续冲突**：一项每次都忙（锁长期被占）不会报警。以 `jabiz.job.item{result=BUSY}` 的观测和运行计数可见；不加退避。
- **选取与执行之间状态变化**：选取在只读事务中，执行时项可能已不再到期，每项流程须重新判断（同上）。
- **`parallelism > 1` 与连接池**：每个并发项占一个连接，加上在线请求可能耗尽连接池（Q5 平台缺口 15）。缺省 1，上限 8，启动检查告警。
- **`sys_job_run` 加列与封存**：列清单变化须被 21 §2 的封存校验接受（V17 已有先例）；实现第一步先验证。
- **与 16e-2 的迁移编号**：V35/V36、应用 V18/V19 按合并顺序协调。
- **QuizBuks 衔接**：Q4 的 `qb.publication-tick` 要改写为逐项任务（选取模板 + `QB_PUBLICATION_ADVANCE` 改为 `internal()`），在 Q5b 中做，不在本阶段。

**验收标准**

- [x] `JobDefinition.forEach(…)` 声明的任务每项以系统身份、各自一个事务执行；一项失败只回滚该项，其余提交，运行记为 `PARTIAL` 并有各结果计数。
- [x] 每项幂等键 `job:<名称>:<计划时刻毫秒>:<项键>`：同一计划时刻重跑或两个实例在锁过期后各跑一次，同一项只产生一次效果。
- [x] 失败逐项记入只追加的 `sys_job_item_failure`，按连续次数指数退避，成功后记 `RECOVERED`；持续失败的项不使其他项饿死。
- [x] `HoldLock` 支持逐步的最长等待；任务项在 `itemLockWait` 内取不到锁即 `BUSY`、跳过到下一次运行，不算失败；请求中超时为 409。
- [x] 测试复现 Q5 的锁形状，逐项任务下无死锁、在线请求不等整批。
- [x] `maxItems`、`parallelism`、`maxRunTime < lockAtMostFor` 生效；ShedLock 下同一时刻只有一个实例运行。
- [x] 观测 `jabiz.job.item` 只带 `job` 与 `result`；`GET /api/jobs` 显示计数与连续失败中的项，OpenAPI 快照与生成的类型已核对（响应为映射，快照不变）。
- [x] 场景 `runJob` 可断言 `items`；场景回放缺省 `lock-at-least-for=0s`，同一任务连续运行不再 `LOCKED`。
- [x] `WithdrawApproval.when` 可用并有测试。
- [x] 启动检查 `JOB` 覆盖要求 8；单流程任务（`FreightBilling`）行为与快照不变。
- [x] 示范 `commerce.unpaid-order-expire` 与场景；文档（D42、06、07、11、13、CLAUDE.md、ROADMAP）已更新；`./gradlew check` 通过。

**预估**

| 工作 | 天数 |
|---|---|
| core 声明、启动检查、迁移 | 0.5 |
| `JobRunner` 逐项执行、失败与退避、`maxRunTime`、观测、接口 | 1 |
| `HoldLock` 逐步等待、`WithdrawApproval.when`、场景（`items`、缺口 7） | 0.5 |
| 集成测试（并发、死锁、幂等、退避）与示范、文档 | 0.5–1 |
| **合计** | **2.5–3** |

**不在本阶段**：项在实例间分发（`SKIP LOCKED` 领取）；运行中逐项重试；失败项的人工重试 / 放弃接口；任务的暂停开关；缺口 9（测试基类的时钟）；Q4/Q5 自身的迁移（Q5b）。

**待确认**

| 编号 | 问题 | 缺省 |
|---|---|---|
| K-1 | 新增决策 D42（补充 D14 第 5、6 条：逐项任务每项一个系统身份的根流程与事务，"至多一次"细化到"每计划时刻每项"） | 新增 D42，不取代 D14 |
| K-2 | 选取用什么表达？ | SQL 模板（须有 `list.defaultSort` 与 `list.key`，平台加 `LIMIT`）。另一做法：一个只读的"选取流程"返回输入列表（更灵活，但每次运行多一条操作记录，且要防止它写入） |
| K-3 | "忙"（锁等待超时、死锁、版本冲突）怎么算？ | `BUSY`：跳过到下一次运行，不算失败、不退避、不记失败行，只计数与观测 |
| K-4 | 每项流程必须 `internal()` 吗？ | 必须（启动检查报错）；单流程任务不要求 |
| K-5 | 持续失败的项最终怎样？ | 不放弃：退避封顶 1 h 后每小时重试并写 error 日志；不做人工重试 / 放弃接口 |
| K-6 | 项是否在多个实例间分发？ | 不分发：一次运行在一个实例上（集群锁），实例内 `parallelism`（缺省 1，上限 8）；Q5 估算单实例每分钟 3 000 项以上已够 |
| K-7 | 示范放在哪里？ | `app` 的 `commerce.unpaid-order-expire`（未付款 14 天自动取消，`ORDER_RECORD_PAYMENT` 加订单锁）。另一做法：只在测试源中声明测试任务，不加示范业务 |
| K-8 | 每项流程的输出是否保存（`op_process_result`，为了重放）？ | 保存（与现有幂等键一致，重放需要）。若 Q10 发现增长过快，再加"不保存输出、重放只返回已处理"的选项 |
| K-9 | 逐项任务的缺省值 | `maxItems` 500（上限 10 000）、`parallelism` 1、`itemLockWait` 2 s、退避 1 min 起 1 h 封顶、`maxRunTime` = `lockAtMostFor` / 2 |
| K-10 | 一次运行的结果 | 无失败项 `SUCCEEDED`、有失败项 `PARTIAL`（新增值）、选取失败 `FAILED`；`REPLAYED` 只属于单流程任务 |

**确认**（2026-10-11）：计划与待确认点 K-1 – K-10 全部按缺省处理（新增决策 D42）。

**16k 完成说明**
- 第一步的确认：`sys_job_run` 加列被封存接受——封存按块记下各表的列清单（`sys_integrity_seal_table.columns`）并按它校验，加列后旧块照常通过
  （`IntegrityIT.aColumnAddedLaterLeavesTheSealsIntactButTheirColumnListsCannotBeChanged` 已覆盖；`JobItemsIT.itemRecordsAreAppendOnly` 另以带新列的运行与
  `sys_job_item_failure` 封存并校验）。设计不需改变。
- 实现：core `JobDefinition`（不可变类，种类 `SINGLE` / `PER_ITEM`，`forEach` 与 `maxItems`、`parallelism`、`itemLockWait`、`itemRetry`、`maxRunTime`，声明时检查范围与
  `maxRunTime < lockAtMostFor`；`itemIdempotencyKey`；`ItemRetry.delayAfter`）、`JobItems`；runtime `JobRunner`（`runSingle` / `runItems`，`execute` 返回 `JobRun`（结果与 `ItemCounts`），
  `Outcome.PARTIAL`，`ItemOutcome`，`stop()`）、`JobItemFailures`、`JobChecks`（要求 8）、`JobController`（`kind`、运行的 `items`、`failingItems`）、`JobScheduler.stop` 先停止开始新项；
  `ExecutionOptions.lockWait` / `withLockWait`（经 Reactor 上下文 `ExecutionOptions.LockWait` 传给 `HoldLock`，子流程同样适用）；`HoldLock` 的 `waitAtMost` 重载；
  `WithdrawApproval.when`；`PlatformObservations.JOB_ITEM`；场景 `runJob.items` 与缺省 `lock-at-least-for=0s`（缺口 7）。平台迁移 `V36__job_items.sql`（V35 留给 16e-2）。
- 示范：`app` 的 `OrderExpiry`（任务 `commerce.unpaid-order-expire`、内部流程 `ORDER_EXPIRE`、模板 `commerce.job.unpaid-orders`、`app.commerce.unpaid-order-ttl`）；
  `ORDER_RECORD_PAYMENT` 找到订单后取订单锁并重新读取；消息三语；场景 `commerce/unpaid_order_expiry.yml`。选取不需要新索引（`sales_order_version` 已有 `ordered_time` 索引），没有应用迁移。
- 测试：core `JobDefinitionTest`；runtime `JobChecksTest`、`HoldLockTimeoutTest`、`ScenarioParserTest`；app `JobItemsIT`（12 个：一项失败、重跑与恢复、退避不饿死、退避封顶、`BUSY`、
  Q5 锁形状无死锁与 `CallProcess.forEach` 的死锁对照、`parallelism = 4` 的 200 项与两个实例、运行中锁过期的两个实例并行、`maxRunTime`、选取失败、只追加与封存）、
  `JobIT`（单流程不变、接口的 `kind`、计数与连续失败中的项）、`HoldLockIT`（步骤与执行选项的等待，取较小）、`ApprovalIT`（`WithdrawApproval.when`）；
  `freight/monthly_close`、`commerce/order_payment` 快照不变。OpenAPI 快照不变（`/api/jobs` 的响应是映射），`pnpm gen:api` 不需要；通用后台没有任务页。
- 与计划不同之处：
  - `sys_job_item_failure` 增加 `entry_no`（`GENERATED ALWAYS AS IDENTITY`）定序，索引为 `(job_name, item_key, entry_no DESC)`：`recorded_time` 来自注入的时钟，
    固定时钟下 `FAILED` 与 `RECOVERED` 可能同一时刻。连续次数取该项最近一行的 `attempt`（与"最近 `RECOVERED` 之后的 `FAILED` 行数"相同，不必再数）。
  - 项键无效或重复的项计为 `FAILED`、写 error 日志，但不写失败行（没有可作为键的值）。
  - 选取以遮蔽方式运行模板（`AdvancedQueryExecutor.all(..., masked = true)`）：只读事务中不能写显示记录，选取也不需要明文。
  - `ExecutionOptions` 的方法名为 `withLockWait(Duration)`（访问器 `lockWait()`），与 `withBeforeSteps` 一致。
  - `maxRunTime` 用真实时间（与锁同为基础设施时间）；`JobRunner` 增加带模板注册表与模板执行器的构造器，旧构造器只能运行单流程任务。
  - 并行的重放首次反序列化同一输出类型时会争用 Jackson 的 `DeserializerCache` 锁（`ProcessExecutor.replay` 原有，逐项任务的并行重放首次暴露）；
    评审后改为启动时预建（见下），不再放行。
  - `GET /api/jobs` 的"连续失败中的项"另带 `lastFailureTime`。
- 未完成与已知问题：
  - D41 的执行（人调用 `internal()` 流程即 403 `PROCESS_INTERNAL`）在 16l 中实现；本阶段只有启动检查 `JOB` 要求每项流程声明 `internal()`，
    要求 8 的"人直接调用该内部流程 403"的测试待 16l 合并后补上（16l 的测试覆盖）。
  - 项的观测 `jabiz.job.item` 不嵌在运行的 `jabiz.job.run` 之下（运行是阻塞式观测，项在 Reactor 中执行）；每项流程的 `jabiz.process` 嵌在项之下。
  - 退避的查询对该任务的全部失败行做 `DISTINCT ON`；失败行只追加、长期增长，量大时可加按时间的条件（Q10 压测时评估）。
  - Q4/Q5 场景中缺口 7 的绕行、`qb.publication-tick` 的改写在 Q5b 中做。
- 评审后的修改：
  1. **`BUSY` 只限真正的"忙"**：`ConcurrentUpdateException.busy()`；`R2dbcStorageEngine.translate` 把 `55P03` / `40P01` 译为新的 `LockNotAvailableException`（`sqlState()`、`deadlock()`），
     版本过期用 `ConcurrentUpdateException.stale(…)`（`DatasetEntityManager`、`TemporalWriter`）；"已存在"、撤销与变基、导入等冲突为 `FAILED`。
     `BUSY` 记 info，同一项连续 5 次起 warn（实例内计数）。测试：`anItemChosenAsADeadlockVictimIsBusy`（40P01）、`aStaleVersionIsBusy`、`aRowThatExistsAlreadyIsAFailure`、
     `ProcessLockTimeoutTest`；`CallProcess.forEach` 对照组断言 `LockNotAvailableException.deadlock()`。
  2. **整个流程事务的锁等待有上限**：`ProcessExecutor` 在每个根流程事务开始时 `set_config('lock_timeout', …, true)`：`jabiz.process.lock-timeout`，
     任务项为 `itemLockWait`（较短时）。没有选择只改文档：以前无限的等待改为 30 秒后 409，对在线请求是可取的（不长期占用连接，可重试）；风险写入 06 §4.1。
     测试 `HoldLockIT.everyTransactionBoundsItsLockWaitsAndAStepsWaitIsPutBack`（流程内读 `current_setting('lock_timeout')`：`4s` / `2s`，`HoldLock` 的 300 ms 之后恢复）。
  3. **退避**：每个选中项按自己的失败记录（`failing(keys)`）判断是否仍在退避，不依赖 1 000 的上限（`jabiz.jobs.max-in-backoff`）；`inBackoff` 只读最近"最长退避"时段
     （`recorded_time > :now − 上限`，V36 增加索引 `(job_name, recorded_time)`）；`GET /api/jobs` 的连续失败中的项只列 7 天内失败过的。
     测试 `itemsInBackoffBeyondTheCapAreNotRetriedEarly`（上限降为 3）。
  4. **没有有效项键时的 NPE**（`Map.of().get(null)`）已修复：这些项计为 `FAILED`、运行 `PARTIAL`（`itemsWithoutUsableKeysFail`）。启动检查 `JOB`：项键列来自遮蔽字段为错误，
     模板中出现遮蔽字段为告警（`JobChecksTest.theSelectionReadsMaskedFieldsMasked`）。
  5. **停机**：`jabiz.jobs.scheduler.shutdown-grace`（缺省 30 秒）为等待进行中任务的时限；停机开始后出错的项记为 `DEFERRED`、不写失败行（`stoppingDefersWhatIsLeft`）；注释已更正。
  6. **D41**：`JobChecks` 以 `internal()` 检查——16l 中 `internal()` 即 `ProcessAccess.INTERNAL`（只允许系统），`subProcessOnly()` / `entryOnly()` 的每项流程因此报错；
     16l 合并前 `ORDER_EXPIRE` 以步骤拒绝系统以外的调用者，并在锁内重新检查期限（`expireProcess(ttl)`）。V36 不变（合并顺序 16e-2 → 16k）。
  7. 新测试：`OrderExpiryIT`（付款先排队 → 记录付款、过期不取消；过期先排队 → 取消、付款转为待核对；只有系统能过期、未到期不取消）、上述 NPE、超过上限的退避、逐项的 40P01。
  8. **不稳定**：`expireLock` 改为 `- interval '1 hour'`；`afterMaxRunTimeNoItemStarts` 以闸门把第一项留到 `maxRunTime` 之后（结果确定：1 项成功、5 项推迟）；
     `concurrentInstancesAfterAnExpiredLock…` 以闸门让第二个实例确实在等第一个实例未提交的同一项。各测试只数自己锁名上的等待者（`pg_locks` 为数据库服务器共有，其他 fork 的测试类会干扰）。
  9. **BlockHound 放行已删除**：`ProcessExecutor` 在启动时（`SmartInitializingSingleton`）为每个已注册流程的输出预建 `ObjectReader`，重放用它，不再在事件循环上构建反序列化器。
  10. 11 §4.1：项键必须是代理键（不变、不含个人信息），其余见上。
- 合并 16l（D41）后：`JobDefinition` 带 `alsoRunByHand` / `manualToo()`（只限单流程任务；逐项任务调用即报错）；启动检查 `JOB` 对单流程任务用 16l 的
  `accessProblems`，对逐项任务要求 `ProcessAccess.INTERNAL`（普通流程同样是错误，不是告警）；`ExecutionOptions` 同时有 `entry`（`atEntryOf`）与 `lockWait`；
  `ProcessExecutor` 先 `requireStartable`，再在事务中设置 `lock_timeout`。测试夹具的 `CallProcess.forEach` 对照流程 `IT_JOB_BATCH` 改为 `internal()`（16l 的调用检查要求），
  测试迁移改为 `V1011__it_job_items.sql`（16l 占 V1010）。


### 16h 账本维度接受身份与引用字段（0.5 天）

现状：维度的实体来源只能是文本或代码字段；QuizBuks 以用户编号为维度（`d.entity("SecUser", "userId")`）被启动检查 `LEDGER` 拒绝。

**要求**
1. 实体来源可以是文本、代码、**身份**（只限来源实体的主键）或**引用**字段；分录上照旧存文本。身份与引用来源的编号须为 UUID（时态实体），
   取值必须是现存实例的完整 UUID（去空白、大小写不限，存小写；校验与存储同用 `LedgerDimension.canonicalId`）；不是 UUID 或不存在即 `LEDGER_DIMENSION_INVALID`。
2. 其他规则不变（位置 1–4、字典来源、取值须为当前实例等）；启动检查：身份字段不是主键、编号不是 UUID（非时态实体）即报错；
   时态实体的来源字段可变即告警（任何种类；按不可变字段才能缩小读取的版本，D29）。哪些维度取编号在启动时算一次。
3. 按身份查找走主键（时态实体的当前版本索引），不全表扫描。
4. 示范：`backend/app` 的维度 3 `supplier`（`Supplier.supplierId`）。

**验收标准**
- [x] 时态实体的身份与引用来源通过启动检查，非时态实体的编号、其他种类被拒绝，可变字段告警（`LedgerChecksTest`）；规范形式（`LedgerDimensionTest`）与存储（`CheckPostingTest`）。
- [x] 过账时现存的编号通过（大写也存为小写），不存在、格式不对的被拒绝；维度余额模板按它汇总（`LedgerEnhancementsIT`）。
- [x] 11 §1.5、功能清单 §8 已更新；`./gradlew :core:check :runtime:check :app:check` 通过。

### 16i 业务参数的四眼修改（1 天）

现状：受控变更（D23，18 §3.5）只能改审批规则、限额与职责分离规则；业务参数（`SysParam`，04 §9）任何持有 `platform.param.write` 的人都可以一个人改。
QuizBuks 的创作者分成比例、提现门槛等要求只经受控变更修改（提出人与发布人不同）。

**要求**（决策 D40）
1. 应用以 Bean `ControlledParams.of(键…)`（core）事先声明受控的参数键；是否受控只在代码中，数据里没有可改的标记。
2. 受控键的一切写入（`PARAM_CREATE` / `SET` / `SCHEDULE` / `CANCEL_SCHEDULED`、数据视图、通用实体流程、业务流程的 `ChangeSet`、撤销操作）一律 422 `PARAM_CONTROLLED`（三语），
   只有发布已批准变更时那一次带许可（`ChangeSet` 的 `grant`）的写入能通过（平台在 `VersionAppender` 中检查，覆盖所有时态写入途径；不凭流程名）。
3. `CONTROL_CHANGE_PROPOSE` 可以 `targetEntity: SysParam`：`values` 为 `paramKey`、`value`、可选 `description`（新建时另加 `valueKind`），`effectiveTime` 为预定时间；
   `delete: true` 加 `effectiveTime` 取消该时间的预定值（没有预定值即 422 `NOT_SCHEDULED`）。提出与发布时都按参数的类型检查值，并做写入时同样的字段校验；
   提案记下所基于的参数，发布时已不是它即 422 `CONTROL_TARGET_CHANGED`（新增错误码）；发布人不能是提出人。
   受控变更的目标统一为接口 `ControlTarget`（`ApprovalControlTarget`、`ParamControlTarget`）。
4. 启动检查 `PARAM`：声明的键格式不对即报错（全部一次报告）；尚不存在的键不是问题。
5. 示范：`backend/app` 把 `logistics.fuel-surcharge-rate` 声明为受控；场景 `freight/monthly_close` 改为经受控变更新建与预定，并演示直接 `PARAM_SET` 被拒。

**改动**：core `com.jabiz.param.ControlledParams`、`PlatformErrorCodes.PARAM_CONTROLLED` 与三语消息；runtime `param`（`ControlledParamRegistry`、`ControlledParamGuard`、`ParamChecks`、
受控变更的参数目标 `ParamControlTarget`、写入许可 `ParamWriteGrant`）、`temporal`（`TemporalWriteGuard` 接口，`VersionAppender` 调用）、`approval`（`ControlTarget` 接口、`ApprovalControlTarget`、`ControlChanges`、`LoadControlTarget`）、core `ChangeSet` 的 `grant`；
`app` 的声明与场景；文档 09 D40、04 §9、18 §3.5、功能清单、CLAUDE.md 第 4 节"审批与职责分离"。没有迁移。

**测试**
- core：`ControlledParamsTest`（键格式、合并、问题列表）。
- runtime 单元：`ControlledParamsChecksTest`（格式错误一次全部报告、不存在的键不报；写入守卫只放行 `CONTROL_CHANGE_PUBLISH`）。
- 集成 `ControlledParamIT`（本地库）：直接修改被拒（四个流程、数据视图、通用实体流程）；提出 + 发布立即生效与预定生效（历史、审计照常）；取消预定（无预定时提出即拒、发布时再查）；
  同一人不能发布；提出时值不合类型、说明过长被拒；两个新建提案竞争时后发布者 `CONTROL_TARGET_CHANGED`；以子流程发布的业务流程自己写受控键被拒；
  不能经数据视图删除再新建、改键等方式解除受控；未受控的键不能用受控变更。
- 场景回放：`freight/monthly_close` 与 `ScenarioAcceptanceIT` 的内联场景改用受控变更，快照更新。

**风险**：受控变更的发布要求二次验证（`ADMINISTRATION`），参数修改因此也要求；示范与 QuizBuks 均可接受。

**验收标准**
- [x] 受控键的直接修改在所有写入途径上被拒（422 `PARAM_CONTROLLED`），一个人无法解除受控（`ControlledParamIT`、`ControlledParamsChecksTest`）。
- [x] 经提出与另一人发布修改受控参数，立即与预定生效、可取消预定，值按类型检查，历史与审计照常（`ControlledParamIT`）。
- [x] 声明格式错误在启动时一次报告（`ControlledParamsTest`、`ControlledParamsChecksTest`）；示范场景 `freight/monthly_close` 通过；`./gradlew check` 通过。

### 16j 应用的快照路径设置生效（0.5 天）

现状：约定插件 `jabiz.boot-app` 在应用插件时就读取 `jabizApp` 的 `openApiSnapshot`、`publicQueriesSnapshot`（`backend/build.gradle.kts` 的 `tasks.withType<Test> { }`
使 `test` 任务一创建就配置），这时应用的 `jabizApp { }` 还没有执行，设置被忽略：QuizBuks 以 `-Dopenapi.update-snapshot=true` 运行快照测试时写入的是平台的
`frontend/openapi/openapi.json`，而应用分支不能改平台目录。

**要求**
1. 两个快照路径在 `test` 任务运行时才读取（`jvmArgumentProviders`，路径照旧是任务输入）；缺省仍为 `frontend/openapi/openapi.json` 与 `public-queries.json`。
   插件中其他 `jabizApp` 属性（`mainClass`、`languages`、`region`、`spa`）已是延迟读取或在调用时注册，不变。
2. `backend/build-logic` 有自己的测试（Gradle TestKit，夹具项目像 `backend` 一样预先配置 Test 任务），并随 `./gradlew check` 运行。

**验收标准**
- [x] 应用设置的两个路径到达 `test` 任务，未设置时为平台前端的快照（`BootAppPluginSnapshotPathsTest`；修复前第一条失败）。
- [x] `backend/app` 的 `OpenApiSnapshotIT` 照旧对比 `frontend/openapi/openapi.json`；`./gradlew check` 通过（含 build-logic 的测试）。

### 16l 内部流程只允许系统身份（D41，0.5 天）

现状：`ProcessDefinitionBuilder.internal()` 只把流程从目录中隐去，权限照常检查：持有全部权限的初始管理员仍能经 `/api/processes/{码}` 直接调用
入站事件的消费流程、发信流程等接续点，16b-2、16d、Q4 的评审各自以不授予任何角色的权限或在步骤中拒绝非系统身份补救。
同一个 `internal()` 还被平台自己的入口（登录、通用实体接口、上传、导入、自助注册、账户设置）以真实调用者运行的流程和以子流程调用的 `FILE_ARCHIVE` 使用。

分支 `1.2/phase-16l-internal-processes`，从 `1.2/platform` 拉出。

**要求**（决策 D41，修订后的三种声明）
1. core：`ProcessAccess`（`OPEN` / `INTERNAL` / `ENTRY_ONLY` / `SUB_PROCESS_ONLY`）代替 `ProcessDefinition` 的布尔 `internal`；构建器 `internal()`、`entryOnly()`、`subProcessOnly()`，
   `ProcessDefinition.withAccess` / `asInternal`、`listed()`。三种都不进目录、不作行操作。
2. runtime：执行器在启动根流程（`requireStartable`）与子流程（`requireCallable`）时检查，不符即 403 `PROCESS_INTERNAL`（`ProcessInternalException`，三语，`ProblemStatuses` 中映射）；
   流程接口在转换输入前先查。`ExecutionOptions.entry`（`atEntryOf(流程)`）与 `ProcessExecutor.executeAtEntry` 由平台入口使用。
3. 平台流程按用途改声明：`entryOnly()`——`SPONSOR_SIGN_IN`、`SPONSOR_MFA_VERIFY`、`SPONSOR_OIDC_SIGN_IN`、`ADD/UPDATE/DELETE_ENTITY`、`FILE_REGISTER`、`IMPORT_RUN`、
   `IMPORT_MAPPING_SAVE/REMOVE`、七个自助注册与找回密码流程、`SEC_MFA_ENROLL_BEGIN/CONFIRM`、`SEC_USER_SET_LOCALE`、`SEC_MAIL_PREFERENCE_SET`；
   `subProcessOnly()`——`FILE_ARCHIVE`；`internal()` 不变——`MAIL_SEND`、`INBOUND_RECEIVE`、`WEBHOOK_DELIVER`、`SEC_BOOTSTRAP_ADMIN`、示范 `ORDER_RECORD_PAYMENT`。
   各自的入口（`AuthController`、`MfaController`、`OidcController`、`EntityController`、`FileUploadService`、`ImportService`、`SignUpController`、`AccountController`、`MailUnsubscribeController`）声明自己是入口。
   已有的补救（`RecordInboundEvent`、`CommercePayments` 的系统身份检查、不授予角色的权限）保留。
4. 启动检查 `PROCESS`：不进目录的流程声明 `actsOn` 即报错；`CallProcess` 调用 `entryOnly()` 流程即报错。
5. 场景回放：调用 `internal()` 流程的步骤以 `actor: {id: system}` 运行；`subProcessOnly()` 流程不能作为步骤；`entryOnly()` 流程的 `process` 步骤代表其平台入口，
   `import` 步骤以入口运行文件登记与导入。场景格式不变（07 §3）。

**改动**：core `process`（`ProcessAccess`、`ProcessDefinition`、构建器）、`PlatformErrorCodes.PROCESS_INTERNAL` 与三语消息；runtime `process`（`ProcessExecutor`、`ExecutionOptions`、
`ProcessInternalException`、`ProcessChecks`）、`web`（`ProcessController`、`MetaModelController`、`ProblemStatuses`）与上列入口、testFixtures `ScenarioRunner`；
示范场景 `commerce/order_payment` 加三个步骤；测试夹具的消费与任务流程（`IT_LOG_EVENT`、`IT_TICK`）改为 `internal()`；文档 06 §8–§9、07 §3、11、12、CLAUDE.md 第 3 节、功能清单。
没有迁移，OpenAPI 不变。

**测试**
- core：`ProcessDefinitionBuilderTest`（三种声明与复制后保留）。
- runtime 单元：`ProcessChecksTest`（三种声明带 `actsOn` 报错；调用 `entryOnly()` 报错、调用 `subProcessOnly()` 不报）。
- 集成 `ProcessAccessIT`：持有全部权限的管理员经流程接口调用 `internal()` / `entryOnly()` / `subProcessOnly()` 流程一律 403 `PROCESS_INTERNAL`，它们也不在目录中；
  实体接口以真实调用者运行 `ADD_ENTITY`（`op_process` 与审计）；`entryOnly()` 只认自己的入口选项；非系统根流程调用 `internal()` 子流程 403 且什么都不留，系统身份可以；
  `subProcessOnly()` 系统身份也不能单独运行。`GeneratedFileIT`：`FILE_ARCHIVE` 直接调用 403，在 `ORDER_PICK_LIST` 与测试的中继流程下照常。
  `OutboxIT`、`JobIT`、`InboundWebhookIT`、`MailIT`：消费者与定时任务以系统身份照常运行 `internal()` 流程。
- 场景回放：`commerce/order_payment`（管理员调用 `ORDER_RECORD_PAYMENT` 403、系统身份调用 `FILE_ARCHIVE` 403、系统身份通过检查），其余场景中的 `ADD_ENTITY`、自助注册等步骤照常。

**验收标准**
- [x] 持有全部权限的管理员经流程接口调用 `internal()` 或 `entryOnly()` 流程得到 403 `PROCESS_INTERNAL`（`ProcessAccessIT`）。
- [x] 平台入口以真实的操作人运行 `entryOnly()` 流程，操作记录与审计照常（`ProcessAccessIT`，以及登录、注册、二次验证、上传、导入的已有集成测试）。
- [x] `FILE_ARCHIVE` 作为根流程被拒，在 `ORDER_PICK_LIST` 下照常（`GeneratedFileIT`）；非系统根流程调用 `internal()` 子流程 403（`ProcessAccessIT`）。
- [x] 事件消费者、定时任务、入站接收照常（`OutboxIT`、`JobIT`、`InboundWebhookIT`）；`actsOn` 的启动错误（`ProcessChecksTest`）；场景回放通过；`./gradlew check` 通过。

**完成说明**：真实用户原先调用的 `internal()` 流程即上面改为 `entryOnly()` 的二十一个（全部只经平台入口），以及经 `ORDER_PICK_LIST` 调用的 `FILE_ARCHIVE`；
它们的入口与行为不变。管理员经流程接口直接调用它们的途径（之前仅以权限拦截）现在一律 403。

**评审后的补充（同一分支）**
- 启动检查 `PROCESS`：不是 `internal()` 的流程（普通、`entryOnly()`、`subProcessOnly()`）以 `CallProcess` 调用 `internal()` 流程即错误（静态不知道普通流程的根是否以系统身份运行，只认 `internal()` 的调用方）。
  执行器的子流程检查保留（`ProcessAccessRulesTest`）。
- `EVENT` / `JOB`：消费者与任务的流程为 `entryOnly()` / `subProcessOnly()` 即错误，为普通流程即警告；也要手工运行的以 `EventSubscription…manualToo()` / `JobDefinition…manualToo()` 声明
  （`INTEGRITY_SEAL`、`INTEGRITY_VERIFY`、`FILE_PURGE_ORPHANS` 与示范的 `FREIGHT_MONTH_CLOSE`）。`TASK_NOTIFY`、示范 `CERTIFICATION_APPROVAL_RESULT`、`FREIGHT_POST_REVENUE` 改为 `internal()`。
- `IMPORT`：目标流程为 `internal()` / `entryOnly()` 即错误（20 §6）。
- `PlatformArchRules.noProcessMachinery`：业务包不引用 `ProcessExecutor`、`ExecutionOptions`、`ActingUser`、`RequestContext.system`（示范 `ArchitectureTest` 带反例）。
- 保留的操作人 `system`、`anonymous`：开发请求头（`DevHeaderActorResolver`）与访问令牌（`JwtService`）都拒绝；流程接口对不进目录的流程一律拒绝，不看操作人。
- 流程接口的回答：仍为 403 `PROCESS_INTERNAL`（不改 404：流程存在，目录已隐去它），消息不再说"只由平台执行"，而按种类说明如何运行。
- 补回覆盖：经入口 `PUT /api/imports/nothing.here/mappings/x` 为 404；通用实体流程自己检查数据视图写权限（`ProcessAccessIT`）。
- 文档：09 D41 第 3 条更正事实（执行器不检查权限，自己检查权限的步骤须明确放行系统身份）；09 索引补 D42 行；07 §3、20 §6–§7、11、06 §8–§9、CLAUDE.md 第 3 节、`docs/guide/version-lines.md`。

**升级说明**（应用合并 16l 时）：重新归类应用的 `internal()` 流程——事件消费、定时任务、回调保留 `internal()`；被用户运行的流程以子流程调用的改为 `subProcessOnly()`；
事件与任务的普通流程改为 `internal()` 或声明 `manualToo()`；业务代码去掉对上述流程机制的引用（见 `docs/guide/version-lines.md`）。
已知：`1.2/quizbuks` 的 `QB_QUIZ_RELEASE_FILES`（`internal()`，由用户运行的 `QB_QUIZ_DELETE` 调用）须改为 `subProcessOnly()`，在合并到应用分支时修改。
