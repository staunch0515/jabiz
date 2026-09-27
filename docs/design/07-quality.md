# 07 质量保障

平台的核心承诺是"大规模修改业务定义也安全"。靠两层保障：**静态校验**（改错了立刻知道哪里错）和**场景回放**（改完了行为是否符合预期）。

## 1. 启动自检

应用启动时（所有 Bean 创建后、开始接收请求前）执行全部检查，**汇总所有问题一次性报告**，任何一项失败则拒绝启动。

实现：每项检查是一个 `PlatformCheck` Bean（runtime `com.jabiz.runtime.check`，返回 `CheckProblem(级别, 类别, 定位, 描述)` 列表，不抛异常）；
`PlatformCheckRunner` 在启动时运行全部检查，警告写日志，有错误时抛出 `PlatformCheckFailedException` 列出全部错误
（`jabiz.platform-check.on-startup=false` 跳过，`platformCheck` 即如此后自行运行）。注册表（如 `DatasetRegistry`、`SqlTemplateRegistry`）
在构造时只收集问题，同样经此报告，因此不同来源的问题一次性出现。

| 检查项 | 内容 |
|---|---|
| 元模型与表结构 | 每个物理表存在；每个物理列存在（现有 `MetaModelConsistencyChecker`）；列的数据库类型与语义类型兼容 |
| 时态实体 | 系统列、`UNIQUE(entity_id, version_no)`、查询索引、禁止更新的触发器 |
| 唯一性 | 普通实体的唯一索引存在 |
| 语义类型 | 所有 `Custom` 类型的 `kindId` 已注册 SPI |
| 字典 | `Code` 字段引用的 `dictUrn` 有提供者 |
| 错误码 | 每个规则代码、业务错误码在全部语言资源中都有文案 |
| 数据视图 | 见 03 第 4 节 |
| SQL 模板 | 见 05 第 6 节（预编译） |
| 流程 | 见 06 第 9 节 |
| 事件与定时任务 | 消费者名、任务名唯一；cron 合法；引用的流程已注册（11 §2.3、§4） |
| 权限 | 数据视图、SQL 模板、流程都声明了权限（开发环境可降级为警告）；SQL 模板不读取敏感字段 |
| 安全配置 | 访问令牌的签名密钥已配置且足够长（缺失时 Bean 创建失败即拒绝启动，`dev` 除外；见 10 §2） |
| 单页应用 | `jabiz.web.spa[i]` 的前缀合法、不重复、不在 `/api` `/actuator` 下；`index` 是 `.html` 的绝对路径；CSP 非空且为单行（17 §3.2） |

## 2. `platformCheck`（CI 静态校验）

- 构建任务 `platformCheck`：启动一个不开放端口的最小上下文，连接执行过 Flyway 迁移的测试数据库，运行第 1 节全部检查后退出。
  实现为 `:app:platformCheck`（`JavaExec`）：`PlatformCheckLauncher`（runtime testFixtures）在测试数据库中建一个新 schema
  （`JABIZ_TEST_DB_URL`，否则 Testcontainers），`PlatformCheckMain` 以 `WebApplicationType.NONE` 启动应用（Flyway 先迁移），
  调用 `PlatformCheckRunner.runAll()`，结束后删除 schema。`check` 依赖它。
- 输出格式：每行一个问题，`类别 | 定位（文件:行号、实体.字段、数据视图）| 描述`，警告行以 `WARNING` 开头；最后一行为汇总；
  存在错误时退出码非 0。上下文本身无法启动时输出一行 `CONTEXT | - | 原因`。
  类别：`METAMODEL` `SEMANTIC_KIND` `DICTIONARY` `MESSAGES` `RELATIONSHIP` `DATASET` `SQL_TEMPLATE` `PROCESS` `EVENT` `JOB` `WEB`（`CHECK` 为检查本身失败）。
- CI 中必须运行（`./gradlew check` 包含它）；PR 不允许在 `platformCheck` 失败时合并。
- 元模型导出 JSON Schema（`/api/meta/schema/*` 与构建产物），SQL 模板头部按 JSON Schema 校验。

## 3. 场景回放测试

目标：用数据描述"按顺序执行哪些流程、输入什么、期望什么"，可以回放跨天、跨月的业务；结果与快照对比。

### 3.1 场景文件

位置：`src/test/resources/scenarios/**/*.yml`

```yaml
name: 订单跨月结算
clock: 2026-01-31T09:00:00Z          # 初始时间
actor: { id: admin, permissions: ["*"] }
steps:
  - process: ORDER_CREATE@latest
    input: { customerId: C001, items: [{ sku: S1, qty: 2 }] }
    save: { orderId: $.orderId }      # 从输出中提取，供后续步骤引用
  - advanceClock: P1D                 # 推进时钟（ISO-8601 时长）
  - process: MONTH_END_SETTLE@1
    input: { month: "2026-01" }
  - expect:
      query: order.by_customer          # SQL 模板 id
      params: { customerId: C001 }
      rows: 1
  - expectError:
      process: ORDER_CANCEL@latest
      input: { orderId: ${orderId} }
      status: 422
      ruleCode: ORDER_ALREADY_SETTLED
snapshot:
  entities: [Order, OrderLine, LedgerEntry]
  asOf: end                            # 回放结束时的状态；也可指定时间点
```

实现（阶段 8，【D13】）：runtime testFixtures 的 `com.jabiz.runtime.test.scenario`（`Scenario`、`ScenarioRunner`、`ScenarioReplay`、
`SnapshotStore`）；业务模块用 `ScenarioReplay.resources("scenarios")` + `ScenarioReplay.verify(App.class, 资源)` 生成动态测试（示范：`app` 的 `ScenarioTest`）。

- 步骤（每步可带说明 `note`）：`process: 名称@版本|latest`（`input`、`save`、可选 `expectOutput` 子集匹配）；`advanceClock`（ISO-8601 时长 `PT2H` 或期间 `P1D`、`P1M`）；
  `setClock`；`runJob: 任务名` 或 `{job, at, outcome}`（按计划时刻 `at`（缺省为当前时钟）执行定时任务，结果缺省须为 `SUCCEEDED`）；
  `deliverEvents: true`（把到期的 Outbox 事件投递给消费者，直到没有到期的事件）（阶段 9，11 §2.3、§4）；`expect` 三种：`{query, params, rows, values}`（SQL 模板的行数与逐行子集匹配）、`{entity, id, asOf, fields}`、`{param, asOf, value}`；
  `expectError: {process, input, status, ruleCode, field}`。未知键即报错（拼错的期望不会静默通过）。
- 变量：`save: {名: $.a.b[0]}` 从输出（遮蔽后的 JSON）中取值，`$` 为整个输出；`${名}` 整串引用保留类型，嵌在字符串中时插值；未定义即报错。
- 比较按含义：数值按大小（`0.1` 与 `0.1000` 相等）、时间按时刻（不论偏移）、映射按包含、列表逐项。
- 流程经 `ProcessExecutor` 以场景的操作人（显式 `RequestContext`）执行，不经 HTTP，入口权限不检查（D11 第 2 条），流程内部的检查照常；
  输入转换与 Bean Validation 与流程 API 相同（`ProcessInputs`），失败的状态码与违规与 API 相同（`ProblemStatuses`）。
- 隔离：每次回放在**新 schema + 新应用上下文**中执行（迁移与集成测试相同），因此操作号、行号从头开始；主键由确定性的 UUIDv7 生成器按时钟与计数生成。

### 3.2 可控时钟

- 测试注入 `MutableClock`（实现 `java.time.Clock`），`advanceClock` 推进它；平台所有业务时间都来自 `Clock`，因此回放完全确定。
- 预定生效、月末任务等都通过推进时钟来触发（定时任务在测试中由场景显式调用）。

### 3.3 快照对比

- 快照：经各实体的默认数据视图导出当前（或指定时间点，时态实体）版本，不含敏感字段，按主键排序；UUID 规范化为 `#uuid:N`（按首次出现编号，
  同一 UUID 同一编号，引用保持可辨认），`processSeqId` 规范化为 `#op:N`（按操作顺序编号）；时间全部来自可控时钟，是确定的且有业务含义，
  **保留**为 ISO-8601【D13】；小数保留为文本（显示小数位）。
- 快照文件与场景文件同目录：`<场景名>.snapshot.json`。
- 首次运行生成快照（写入源目录，路径由 Gradle 以 `scenario.resources-dir` 传入）；环境变量 `CI` 存在时缺少快照即失败，快照必须随变更提交。
  之后不一致时测试失败并输出可读的差异，每行一处：`~ 实体[#uuid:3].字段: 旧 → 新`、`+ 实体[…] {…}`、`- 实体[…] {…}`。
- 确认变更正确后，用 `-Dscenario.update-snapshots=true` 一键更新。
- 快照纳入代码审查：规则调整的 PR 中，快照差异就是"业务行为变化"的直接证据。

## 4. 架构规则（ArchUnit）

- `jabiz-core` 不依赖 `reactor..`、`io.r2dbc..`、`org.springframework.r2dbc..`、`org.springframework.web..`、`jabiz-runtime`。
- 业务模块不依赖 `reactor..`。
- 公开的业务扩展接口（`ComputeStep`、`BlockingStep`、`RulePredicate`、`TransitionGuard`、`DictionaryProvider`、`CustomKindSupport`）的方法不返回 `Mono` / `Flux`。
- 禁止在 `jabiz-core` 和业务模块中调用 `Instant.now()`、`LocalDateTime.now()`、`System.currentTimeMillis()`。
- 禁止使用 `double` / `float` 类型的字段表示金额（检查 `Monetary` 字段对应的 record 组件类型）。

## 5. BlockHound

- 测试环境通过 `BlockHound.install()` 启用（JUnit 扩展或测试初始化器）。
- 平台自身合法的阻塞点（例如启动自检中的阻塞等待）通过 `BlockHoundIntegration` 显式放行，并附注释说明原因。

## 6. 其他测试手段

| 手段 | 用于 |
|---|---|
| jqwik 属性测试 | 账本借贷平衡、时态版本不变式（当前版本唯一、版本号连续）、类型转换的往返一致性 |
| 真实 PostgreSQL 集成测试 | 运行时层全部功能；**不使用 H2** |
| 前端单元测试（Vitest）与端到端测试（Playwright） | 元数据渲染、关键后台流程（阶段 10 起，12 §8） |
| 前后端共享校验用例（`spec/validation-cases.json`） | 同一输入两端报出相同的错误码（core `ValidationCasesTest` 与前端 `validation.cases.test.ts`，D15） |
| OpenAPI 快照（`frontend/openapi/openapi.json`） | 前端生成的类型与接口一致（`OpenApiSnapshotIT`、`pnpm check:api`） |
| 观测（`ObservabilityIT`） | 平台观测的名称、嵌套、结果标签，且标签中没有主键、操作人与值（13 §6） |
| 压测（`:app:loadTest`，不在 `check` 中） | 典型查询与写入流程的吞吐与延迟、并发下单不超卖（`docs/perf/phase-11-load-test.md`） |

## 7. 数据库测试环境

- 默认使用 Testcontainers（`postgres:16`）。
- 无 Docker 的环境（如部分云端开发环境）：设置 `JABIZ_TEST_DB_URL`（JDBC URL）、`JABIZ_TEST_DB_USER`、`JABIZ_TEST_DB_PASSWORD`，
  测试改连本地 PostgreSQL；每个测试类使用独立 schema，结束后删除。
- 实现：`runtime` 的 `testFixtures` 源集中的 `PostgresTestDatabase` / `PostgresIntegrationTest` / `MutableClock`；
  其他模块通过 `testImplementation(testFixtures(project(":runtime")))` 使用。
- BlockHound 同样由该源集引入（`blockhound-junit-platform` 自动安装）；放行清单为 `JabizBlockHoundIntegration`。

## 8. 覆盖率与门禁

- `jabiz-core` 行覆盖率 ≥ 80%；运行时层关键路径（写入流程、时态、查询编译、流程执行）必须有集成测试。
- CI 门禁：编译、全部测试、`platformCheck`、ArchUnit、覆盖率阈值；前端的 lint、类型检查、生成类型是否最新、Vitest、构建；端到端测试（Playwright，对打包后的应用）。
