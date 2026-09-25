# ROADMAP

执行方式见 `CLAUDE.md` 第 7 节：每个阶段先出计划、经确认后实现；一个阶段一个分支（过大时拆成多个 PR）；PR 逐条对照本文件的验收标准。

阶段顺序有依赖关系：**阶段 4（时态模型）改变写入流程的根基，必须在阶段 6（流程引擎）、9（账本）、10（前端）之前完成。**

| 阶段 | 名称 | 预估 | 状态 |
|---|---|---|---|
| 1 | 基线与测试 | 2–3 天 | ☑ 已完成（PR 待合并） |
| 2 | 分层与横切基础 | 3–4 天 | ☑ 已完成（PR 待合并） |
| 3 | 元模型增强 | 4–5 天 | ☐ 未开始 |
| 4 | 只追加的双时态模型 | 5–7 天 | ☐ 未开始 |
| 5 | SQL 模板与静态校验 | 3–4 天 | ☐ 未开始 |
| 6 | 流程引擎 | 4–5 天 | ☐ 未开始 |
| 7 | 安全 | 3–4 天 | ☐ 未开始 |
| 8 | 业务参数与场景回放 | 3–4 天 | ☐ 未开始 |
| 9 | 账本、事件、审计、定时任务 | 4–5 天 | ☐ 未开始 |
| 10 | 前端 | 7–10 天 | ☐ 未开始 |
| 11 | 示范业务与收尾 | 4–5 天 | ☐ 未开始 |
| 12 | 对 AI 友好（以后） | — | ☐ 未开始 |

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
- [ ] 每项要求都有单元或集成测试。
- [ ] 会员视图（范围 = 当前操作人）无法读取或写入他人数据；范围值缺失时请求被拒绝。
- [ ] 对 `Code` 字段使用 `>` 被拒绝；对 `Text` 使用 `LIKE` 可用。
- [ ] `ext-geo` 移除后核心模块仍能编译和通过测试。

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
- [ ] 并发：同一版本的两个更新只有一个成功，另一个 409。
- [ ] 预定：到期前后（推进可控时钟）查询结果正确，无定时任务参与。
- [ ] 变基：预定存在时修改其他字段，到期后两者的修改都保留；修改同一字段被拒绝；多个预定依次变基；墓碑保持墓碑；追溯更正会变基其后的已生效版本。
- [ ] 更正：相同生效时间、更晚记录时间的版本胜出；`knownAt` 早于更正时得到更正前的值。
- [ ] 撤销：成功时状态恢复；后续修改了相同字段时被拒绝；后续只改无关字段时不阻塞；撤销插入得到墓碑；重做可用；子操作一并撤销。
- [ ] 唯一性：并发插入相同值只有一个成功；与预定版本中的值重复被拒绝。
- [ ] 范围：实体移出范围后按当前时间不可见，也不会显示旧版本（实体查询、计数、SQL 模板三种途径）。
- [ ] 时态表与操作类表上的 UPDATE、DELETE、TRUNCATE 均被触发器拒绝；正常流程的 SQL 日志中没有这些语句；非维护角色在清除模式下仍被拒绝。
- [ ] 同一操作写入的所有行 `created_time` 相同，且都能通过 `op_process_item` 找到。

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
- [ ] 故意把模板中的一个字段名写错、一个结果列类型不兼容、一个参数未声明：`platformCheck` 分别指出文件和问题，退出码非 0。
- [ ] 修改实体的物理列名后，模板无需修改即可正确执行。
- [ ] 手写模板无法读到数据视图范围外的数据、已删除的数据、非当前版本（时态实体）。
- [ ] 外层筛选使用非白名单字段被拒绝。

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
- [ ] 业务示例模块中没有任何 `reactor` 引用（ArchUnit）。
- [ ] 子流程失败时父流程整体回滚，`op_process` 中不留记录。
- [ ] `BlockingStep` 在虚拟线程上执行（测试断言线程类型），BlockHound 无报警。
- [ ] 同一 `Idempotency-Key` 的重复请求返回相同结果且只执行一次。
- [ ] 多个步骤各自添加违规时，响应一次性返回全部违规。

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
- [ ] 未认证访问受保护接口返回 401；无权限返回 403（覆盖数据视图、模板、流程）。
- [ ] 连续登录失败达到阈值后账号锁定。
- [ ] `/security-review` 审查通过，结论写入 PR。
- [ ] 日志和 `op_process.input_summary` 中不出现密码。

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
- [ ] 示范场景连续运行 10 次结果一致。
- [ ] 预定一个参数变更后回放，到期前后规则计算结果按预期不同。
- [ ] 修改一个参数的值后，快照差异准确显示受影响的数据。

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
- [ ] jqwik 属性测试：任意交易序列后，每笔交易借贷平衡、全部科目余额之和为零。
- [ ] 两个实例同时运行时，同一任务只执行一次。
- [ ] 事务回滚时不产生事件；投递失败会重试，消费端不会重复处理。

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
- [ ] 新增一个实体定义和数据视图后，不写任何前端代码即可得到可用的列表页和表单页。
- [ ] 前端校验与后端校验一致（同一非法输入，前端提示与后端 `ruleCode` 一致）。
- [ ] 任一时态实体可以查看历史并回看任意时间点。

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
- [ ] 一条命令启动整套系统并可登录使用。
- [ ] 有开发用时记录和压测报告。
- [ ] 按教程，一个未参与开发的人能在一天内新增一个业务对象。

---

## 阶段 12（以后）：对 AI 友好

- 完善全部声明（实体、数据视图、SQL 模板头、流程输入）的 JSON Schema。
- MCP 服务器：向 AI 工具暴露元模型、字典、SQL 模板目录、流程目录（只读）。
- 目标闭环：自然语言需求 → AI 生成声明 → `platformCheck` + 场景回放自动验证 → 人工审查差异 → 合并。
