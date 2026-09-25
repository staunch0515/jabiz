# CLAUDE.md — jabiz 平台开发规则

本文件是在本仓库工作的长期规则。开始任何任务前，先读完本文件、`docs/design/` 下全部文档和 `docs/ROADMAP.md`。
设计文档是约定，代码是实现：两者冲突时，**先提出并修改设计文档，再改代码**，不要在代码里悄悄偏离设计。

`docs/design/09-decisions.md` 中的决策（D1–D8 …）**具有约束力**。任何实现不得违反；确需改变时，
先在该文件新增一条决策（注明取代哪一条）并获得确认，再改代码。

## 1. 平台是什么

jabiz 是一个**元数据驱动的业务应用平台**：开发者声明实体、语义类型、规则、状态机、数据视图、SQL 模板和流程，
平台负责数据正确性、安全、事务、校验、历史追溯、页面生成和测试。详见 `docs/design/00-overview.md`。

## 2. 技术栈（已决定，不要更换）

| 层 | 技术 |
|---|---|
| 语言 / 运行时 | JDK 21 |
| 后端框架 | Spring Boot 3.3+，**Spring WebFlux** |
| 数据访问 | **R2DBC**（请求路径）；Flyway 迁移和 `platformCheck` 静态校验允许使用 JDBC（不在请求路径上） |
| 数据库 | PostgreSQL 16 |
| 阻塞调用兜底 | Reactor `boundedElastic`，开启 `reactor.schedulers.defaultBoundedElasticOnVirtualThreads=true` |
| 前端 | React 19、TypeScript、Vite、Ant Design 5 + ProComponents、TanStack Query |
| 测试 | JUnit 5、Reactor Test、ArchUnit、BlockHound、jqwik、PostgreSQL（Testcontainers 或本地实例） |

选择 WebFlux 的前提是：**业务开发者不写响应式代码**。所有业务扩展点必须是同步接口（见第 3 节）。

## 3. 分层纪律（最重要，违反即视为缺陷）

详见 `docs/design/01-core-vs-runtime.md`。

1. **核心层（`jabiz-core`）是纯 Java**：元模型、语义类型、类型转换、校验、规则、状态机、查询编译、SQL 模板渲染。
   **禁止引用 `reactor.*`、`org.springframework.r2dbc.*`、`io.r2dbc.*`、`org.springframework.web.*`。**
2. **运行时层（`jabiz-runtime`）很薄**：只负责执行（存储、事务、流程执行、Web 接入）。
3. **业务扩展点全部同步**：字段规则（`RulePredicate`）、迁移守卫、流程计算步骤（`ComputeStep`）、阻塞步骤（`BlockingStep`）。
   平台内部的 `StepHandler`（返回 `Mono`）只供平台自己的 I/O 步骤使用，不对业务开放。
4. 业务模块（`app` 及以后的业务模块）**禁止引用 `reactor.*`**。以上规则由 ArchUnit 测试强制执行。
5. 请求路径上禁止阻塞调用；测试环境启用 BlockHound 检测。

## 4. 编码约定

- **SQL**：所有值必须参数绑定；表名、列名只能来自元数据，并经过 `SqlIdentifiers.require` 校验。禁止字符串拼接用户输入。
- **默认拒绝**：未实现、未配置的安全检查一律报错，绝不放行（参考现有 `AuthenticationHandler` 的写法）。
- **时间**：只能来自注入的 `java.time.Clock`，禁止 `Instant.now()` / `System.currentTimeMillis()` / 数据库 `now()` 作为业务时间。
- **金额**：只用 `BigDecimal`，由 `SemanticKind.Monetary` 声明币种和小数位。禁止 `double` / `float` 表示金额。
- **标识**：新实体主键使用 UUIDv7；`process_seq_id` 来自数据库序列。
- **不可变数据**：优先使用 `record` 和不可变集合（`List.copyOf` / `Map.copyOf`）。
- **错误**：领域错误使用现有异常体系，经 `GlobalExceptionHandler` 转为 `ProblemDetail`：
  400 校验失败（附 `violations`）、404 不存在、409 并发冲突、422 业务规则拒绝。错误码可多语言（见设计文档）。
- **敏感信息**：密码、令牌等字段在 `toString()`、日志、`op_process.input_summary` 中必须遮蔽。
- **启动即失败**：元数据、数据视图、流程、SQL 模板、表结构的不一致，必须在启动时一次性全部报告，而不是等到请求触发。
- **注释**：解释"为什么"，不复述代码。公开类型写简洁 Javadoc。
- **不做的事**：不引入微服务、Kafka、GraphQL、事件溯源框架、Kubernetes；MVP 阶段不引入 Redis。
  URN 资源寻址、多存储引擎、读写分离、H3 空间编码保持现状，不扩展（H3 与物理量将移出核心，见路线图）。

## 5. 测试要求

- **没有测试的功能不算完成。**
- 核心层：单元测试，目标行覆盖率 ≥ 80%。
- 运行时层：连接真实 PostgreSQL 的集成测试（不使用 H2 等替代数据库）。
- 时态（只追加）实体：必须断言对应表上**没有执行过 UPDATE / DELETE**。
- 业务流程：用场景回放测试（固定时钟 + 快照对比），见 `docs/design/07-quality.md`。
- 修 bug 时先写能复现的失败测试，再修复。

## 6. 构建与运行

Gradle 9（wrapper）多模块工程，根目录为 `backend/`（模块：`core`、`app`）。以下命令都在 `backend/` 下执行。

- 构建：`./gradlew build`（含测试、覆盖率门禁、前端构建和 `bootJar`；前端目前编译失败，阶段 10 重建前端）。
  只编译和测试：`./gradlew check`（CI 执行的就是这个）
- 全部测试：`./gradlew test`；只跑核心层单元测试：`./gradlew :core:test`；单个类：`./gradlew :app:test --tests '*DatasetEntityManagerIT'`
- 覆盖率：`./gradlew :core:jacocoTestReport` → `core/build/reports/jacoco/test/html/index.html`；
  `check` 包含 `:core:jacocoTestCoverageVerification`（阶段 1 列出的核心类行覆盖率 ≥ 80%）
- 集成测试数据库：默认 Testcontainers `postgres:16`（需要 Docker）；无 Docker 时设置
  `JABIZ_TEST_DB_URL`（JDBC URL，如 `jdbc:postgresql://localhost:5432/jabiz_test`）、`JABIZ_TEST_DB_USER`、`JABIZ_TEST_DB_PASSWORD`。
  每个测试类使用独立 schema，结束后删除
- 静态校验：`platformCheck`（阶段 5 引入）
- 本地启动：仓库根目录 `docker compose up -d`（数据库，端口 5436；pgAdmin 5050）→ `backend/` 下 `./gradlew :app:bootRun`（后端 8080，
  启动时 Flyway 迁移）→ `frontend/` 下 `npm install && npm run dev`（5173，`/api` 代理到 8080）
- 数据库：PostgreSQL 16，连接信息通过环境变量提供，**不得写入仓库**。

## 7. 每个阶段的交付方式

1. 读 `docs/ROADMAP.md` 中对应阶段的目标、要求、验收标准。
2. **先给出实施计划并等待确认**，确认后再写代码。计划需列出：要改的模块和类、新增的表和迁移、测试清单、风险。
3. 在分支 `phase-<N>-<简短名>` 上实现。一个阶段过大时拆成多个 PR（`phase-<N>a`、`phase-<N>b` …）。
4. 完成后：运行全部测试和静态校验 → 用 `/code-review` 自查 → 涉及认证、权限、SQL 的阶段额外运行 `/security-review`。
5. 创建 PR，描述中**逐条对照验收标准**说明如何满足，并列出未完成项和已知问题。
6. 如果实现中改变了约定，同步更新本文件和 `docs/design/`。
7. 在 `docs/ROADMAP.md` 中更新该阶段的状态。
