# 07 质量保障

平台的核心承诺是"大规模修改业务定义也安全"。靠两层保障：**静态校验**（改错了立刻知道哪里错）和**场景回放**（改完了行为是否符合预期）。

## 1. 启动自检

应用启动时（所有 Bean 创建后、开始接收请求前）执行全部检查，**汇总所有问题一次性报告**，任何一项失败则拒绝启动。

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
| 权限 | 数据视图、SQL 模板、流程都声明了权限（开发环境可降级为警告） |

## 2. `platformCheck`（CI 静态校验）

- 构建任务 `platformCheck`：启动一个不开放端口的最小上下文，连接执行过 Flyway 迁移的测试数据库，运行第 1 节全部检查后退出。
- 输出格式：每行一个问题，`类别 | 定位（文件或实体.字段）| 描述`；存在问题时退出码非 0。
- CI 中必须运行；PR 不允许在 `platformCheck` 失败时合并。
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

### 3.2 可控时钟

- 测试注入 `MutableClock`（实现 `java.time.Clock`），`advanceClock` 推进它；平台所有业务时间都来自 `Clock`，因此回放完全确定。
- 预定生效、月末任务等都通过推进时钟来触发（定时任务在测试中由场景显式调用）。

### 3.3 快照对比

- 快照：按实体导出当前（或指定时间点）版本，按主键排序；UUID、`process_seq_id`、时间戳等不确定值规范化为序号占位。
- 快照文件与场景文件同目录：`<场景名>.snapshot.json`。
- 首次运行生成快照；之后不一致时测试失败并输出可读的差异。
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
| 前端单元测试（Vitest）与端到端测试（Playwright） | 元数据渲染、关键后台流程（阶段 10 起） |

## 7. 数据库测试环境

- 默认使用 Testcontainers（`postgres:16`）。
- 无 Docker 的环境（如部分云端开发环境）：设置 `JABIZ_TEST_DB_URL`（JDBC URL）、`JABIZ_TEST_DB_USER`、`JABIZ_TEST_DB_PASSWORD`，
  测试改连本地 PostgreSQL；每个测试类使用独立 schema，结束后删除。
- 实现：`app` 测试源中的 `PostgresTestDatabase` / `PostgresIntegrationTest`（阶段 2 拆模块后随运行时层迁移）。

## 8. 覆盖率与门禁

- `jabiz-core` 行覆盖率 ≥ 80%；运行时层关键路径（写入流程、时态、查询编译、流程执行）必须有集成测试。
- CI 门禁：编译、全部测试、`platformCheck`、ArchUnit、覆盖率阈值。
