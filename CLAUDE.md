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
| 前端 | 通用后台：pnpm、React 19、TypeScript、Vite、Ant Design 5 + ProComponents、TanStack Query、React Router、i18next；类型由 OpenAPI 生成（见 12）。应用的公开前端工具链相同但不用 Ant Design（见 17 §4 与决策 D19） |
| 测试 | JUnit 5、Reactor Test、ArchUnit、BlockHound、jqwik、PostgreSQL（Testcontainers 或本地实例）；前端 Vitest、Playwright |

选择 WebFlux 的前提是：**业务开发者不写响应式代码**。所有业务扩展点必须是同步接口（见第 3 节）。

## 3. 分层纪律（最重要，违反即视为缺陷）

详见 `docs/design/01-core-vs-runtime.md`。

1. **核心层（`jabiz-core`）是纯 Java**：元模型、语义类型、类型转换、校验、规则、状态机、查询编译、SQL 模板渲染。
   **禁止引用 `reactor.*`、`org.springframework.r2dbc.*`、`io.r2dbc.*`、`org.springframework.web.*`。**
2. **运行时层（`jabiz-runtime`）很薄**：只负责执行（存储、事务、流程执行、Web 接入）。
3. **业务扩展点全部同步**：字段规则（`RulePredicate`）、迁移守卫、流程计算步骤（`ComputeStep`）、阻塞步骤（`BlockingStep`）。
   平台内部的 `StepHandler`（返回 `Mono`）只供平台自己的 I/O 步骤使用，不对业务开放。
   业务流程用平台步骤的工厂（`LoadEntity.by`、`QueryEntities.of`、`RunTemplate.of`、`SaveChanges.now`、`CallProcess.of`、`PublishEvent.of`）
   做 I/O（业务参数用 `LoadParams.of`，以业务发生时间读取，见 04 §9），用 `ctx.changes()` 登记变更、`ctx.reject(...)` 累积违规，由平台在流程结束时统一提交（见 06 与决策 D11）。每个流程必须声明权限。
4. 业务模块（`app` 及以后的业务模块）**禁止引用 `reactor.*`**。以上规则由 ArchUnit 测试强制执行。
5. 请求路径上禁止阻塞调用；测试环境启用 BlockHound 检测。

## 4. 编码约定

- **SQL**：所有值必须参数绑定；表名、列名只能来自元数据，并经过 `SqlIdentifiers.require` 校验。禁止字符串拼接用户输入。
- **默认拒绝**：未实现、未配置的安全检查一律报错，绝不放行（参考现有 `AuthenticationHandler` 的写法）。
- **时间**：只能来自注入的 `java.time.Clock`，禁止 `Instant.now()` / `System.currentTimeMillis()` / 数据库 `now()` 作为业务时间。
- **金额**：只用 `BigDecimal`，由 `SemanticKind.Monetary` 声明币种和小数位（小数位由平台自动校验，`MONETARY_SCALE`）。禁止 `double` / `float` 表示金额。
- **标识**：新实体主键使用 UUIDv7（默认 `EntityIdGenerator` 即 `UuidV7Generator`）；`process_seq_id` 来自数据库序列。
  业务单据号（不能缺号、不能重复）只经 `NumberSequence` Bean 与流程步骤 `AssignNumber` 取得（18 §2、决策 D23），不自己计数。
- **审批与职责分离**（见 18 §3–§4 与决策 D23）：需要审批的单据声明 `ApprovalSubject` Bean，流程中用 `RequireApproval` 取得结论（批准绑定内容哈希），
  以订阅 `jabiz.approval.approved` / `rejected` 继续；不自己写审批状态机或"准备人不能审批"之类的检查。审批规则、限额、职责分离规则只经
  `CONTROL_CHANGE_PROPOSE` / `CONTROL_CHANGE_PUBLISH`（四眼）修改。
- **待办与通知**（见 18 §5）：需要人去做的事用步骤 `CreateTask`（指派给用户或权限，带来源键）登记、`CloseTasks` 关闭；不另建待办表。
  邮件只经待办的通知（`jabiz.mail.enabled`，缺省关闭），不在流程中直接发邮件。
- **不可变数据**：优先使用 `record` 和不可变集合（`List.copyOf` / `Map.copyOf`）。
- **错误**：领域错误使用现有异常体系，经 `GlobalExceptionHandler` 转为 `ProblemDetail`：
  400 校验失败（附 `violations`）、404 不存在、409 并发冲突、422 业务规则拒绝。错误码可多语言（见设计文档）。
- **时态实体**（`eb.temporal()`，见 04 与决策 D9）：表只 INSERT；建表迁移中必须建 `UNIQUE(实体主键, version_no)`、
  `(实体主键, effect_start_time DESC, version_no DESC)` 与 `process_seq_id` 索引、指向 `op_process` / `entity_registry` 的外键，
  并执行 `SELECT jabiz_protect_append_only('<表>')` 安装禁止 UPDATE/DELETE/TRUNCATE 的触发器（启动自检检查）。
  测试中不能 `DELETE` 这类表：用各测试独有的数据（或写墓碑）隔离。
- **敏感信息**：密码、令牌等字段在 `toString()`、日志、`op_process.input_summary` 中必须遮蔽。实体字段用 `f.sensitive()`
  （读接口不返回、数据视图 API 不接受写入，只有专用流程能写）；流程输入输出 record 的秘密组件标 `@Sensitive` 并在 `toString()` 中遮蔽（见 10 §6）。
- **安全**（见 10 与决策 D12）：`/api/**` 默认要求认证（Bearer 访问令牌）；新的入口必须按元数据声明的权限码检查（`Permissions`），
  未声明即拒绝。唯一的例外是公开只读接口 `/api/public/**`（见下条）。密码只用 BCrypt，且在 `BlockingStep` 中计算。访问令牌签名密钥只来自环境变量 `JABIZ_JWT_SECRET`。
- **文件**（见 14 与决策 D18）：上传只经 `/api/files?policy=…`，类型按内容判定、图片一律重新编码（去掉 EXIF/GPS）；
  字段用 `f.kind(FileKind.of("策略"))` 引用文件（列 `uuid`，不加外键），策略（`FilePolicy` Bean）必须声明上传与读取权限。
  `sys_file` 是可删除的普通表，只经 `FILE_REGISTER` / `FILE_DELETE` / `FILE_PURGE_ORPHANS` 写入；业务流程删除文件前先清空引用并
  `SaveChanges.now`，再 `CallProcess.of("FILE_DELETE", …)`。文件名不进 `input_summary`；`FileStore` 返回 `Mono`/`Flux`，不对业务开放。
- **公开访问**（见 15 与决策 D17）：匿名只能 `GET/HEAD /api/public/queries/{id}` 与 `/api/public/files/{id}[/{变体}]`，总开关
  `jabiz.public.enabled` 默认关闭。公开的行与列只在数据视图上声明：`publicRead(p -> p.fields(...))`（固定值范围、非默认视图、不含敏感字段），
  模板渲染时投影到白名单；公开模板头部写 `access: public`（代替 `permissions`），`datasets` 必须指向公开视图。文件是否公开由公开行的白名单文件字段推导，
  不设标记；需要立即撤下时在流程的提交后步骤 `FileAccess.invalidate(...)`。公开读取不写操作记录。
- **启动即失败**：元数据、数据视图、流程、SQL 模板、表结构的不一致，必须在启动时一次性全部报告，而不是等到请求触发。
  新的检查实现 `PlatformCheck`（返回问题列表，不抛异常），启动与 `platformCheck` 共用。
- **账本、事件、定时任务**（见 11 与决策 D14）：账本交易只经 `LEDGER_POST` / `LEDGER_REVERSE` 写入，更正即冲正；
  需要跨实例规则保护的数据用视图策略 `processOnlyWrites()`。账本的科目层级、分析维度（`LedgerDimension` Bean）、行备注、来源单据与外币分录见 11 §1.4–§1.8 与决策 D24，
  子账单据过账时带上来源（`sourceEntity` / `sourceId`）。事件用 `PublishEvent`（流程事务内写 Outbox）或实体的 `eb.publishChanges()`；
  消费者（`EventSubscription`）与定时任务（`JobDefinition`）都只调用流程，不写 `@Scheduled` 方法。
- **内容编辑**（见 16 与决策 D20）：多语言内容用 `f.apply(I18nText.of(...))`（值为 `{语言: 文本}`，存 `jsonb`，语言即平台支持的语言）；
  被引用的实体用 `eb.display(字段)` 声明显示字段；状态、审核意见等只由流程改变的字段用 `f.processOnly()`（照常可读，数据视图 API 与通用实体流程不能写）；
  以某实体为对象的流程用 `actsOn(实体, 输入组件[, when])` 声明，后台据此显示行操作（`when` 只是显示提示）。Markdown 只在前端渲染，不允许原始 HTML。
- **规则**（见 02 §3 与决策 D15）：导出给前端的字段规则只用 `Rules` 工厂（`RANGE` `SCALE` `LENGTH` `PATTERN` `NOT_FUTURE` `REQUIRED`）；
  依赖服务端状态的判断写成仅服务端规则。新增规则种类或语义约束时，先在 `spec/validation-cases.json` 加用例，前后端都要通过。
- **前端**（见 12）：业务对象不写前端代码，页面由元数据生成；界面按目录与权限隐藏操作，但权限只由服务端判断。
  元数据表达不了的工作流，由应用在自己目录中的**扩展**写页面（12 §9、决策 D22）：只经 `@jabiz/admin` 引用平台、不自带依赖，路由不占平台路径。
  实体与字段的显示名写在消息资源（`entity.<实体>`、`entity.<实体>.<字段>`，三种语言；应用以 `jabizApp { languages(…) }` 只选部分语言时只写所选的，12 §10）。改动 Web 接口后更新 OpenAPI 快照并 `pnpm gen:api`。
- **可观测性**（见 13 与决策 D16）：平台新的工作单元用 `PlatformObservations` 包装；观测标签只放名称与结果（流程、视图、模板、任务名），
  绝不放主键、操作人、字段值。遥测默认不外发，只经 OTLP 推送，不开放匿名的指标端点。
- **SQL 模板**：放在 `queries/**/*.sql`（YAML 头 + SQL，见 05）；表、列只写占位符；列表参数写 `= ANY(:name)`；不写外层 `LIMIT`/`ORDER BY`。
- **报表**（见 19 与决策 D25）：报表就是头部声明了 `report` 的 SQL 模板（标题、列名在消息 `query.<id>`、`query.<id>.<列>`），不另建报表定义；按时点运行用请求的 `asOf` / `knownAt` 或头部 `timeSlice`（参数即时点），不在模板里自己拼"当前版本"；流程中用 `RunTemplate.at`。导出只经 `POST /api/queries/{id}/export?format=csv|xlsx|pdf`（超过 `jabiz.reports.export.max-rows` 即拒绝，不截断）；PDF 的中文、日文字体由 `jabiz.reports.pdf.fonts` 提供。
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

Gradle 9（wrapper）多模块工程，根目录为 `backend/`（模块：`core` = jabiz-core、`ext-geo`（地理/物理量扩展语义类型，只依赖 core）、`runtime` = jabiz-runtime、`app`（示范业务：物流、运费月结、订单与库存 `com.jabiz.app.commerce`））。
`backend/` 下其他带 `build.gradle.kts` 的直接子目录自动成为模块（应用分支的模块）；可部署应用用约定插件 `jabiz.boot-app`（`backend/build-logic`，
`jabizApp { mainClass = …; spa("/", "../../frontend") }`），它负责 Spring Boot、`platformCheck`、测试的快照属性和前端打包（见 17 §3）。
新增业务对象的步骤见 `docs/guide/new-business-object.md`。以下命令都在 `backend/` 下执行。

- 构建：`./gradlew build`（含测试、覆盖率门禁、前端构建（pnpm，经 node-gradle，每个 SPA 以 `VITE_BASE` 构建到 `app/build/spa/<名>`）和 `bootJar`，jar 同时提供页面与接口）。
  只编译和测试：`./gradlew check`（CI 执行的就是这个）
- 全部测试：`./gradlew test`；单个模块：`./gradlew :core:test`、`:ext-geo:test`、`:runtime:test`、`:app:test`；
  单个类：`./gradlew :app:test --tests '*DatasetEntityManagerIT'`
- 覆盖率：`./gradlew :core:jacocoTestReport` → `core/build/reports/jacoco/test/html/index.html`；
  `check` 包含 `:core:jacocoTestCoverageVerification`（门禁类的行覆盖率 ≥ 80%）
- 集成测试数据库：默认 Testcontainers `postgres:16`（需要 Docker）；无 Docker 时设置
  `JABIZ_TEST_DB_URL`（JDBC URL，如 `jdbc:postgresql://localhost:5432/jabiz_test`）、`JABIZ_TEST_DB_USER`、`JABIZ_TEST_DB_PASSWORD`。
  每个测试类使用独立 schema 与独立的文件存储临时目录（`filesRoot()`），结束后删除。测试中 BlockHound 始终开启
- 静态校验：`./gradlew :app:platformCheck`（启动检查全部跑一遍，输出 `类别 | 定位 | 描述`，有错误退出码非 0；数据库同集成测试；`check` 包含它）
- 一条命令启动整套系统：仓库根目录 `docker compose up -d --build`（`db` 5436、`app` 8080（后端内含前端）、`lgtm` Grafana 3000；
  首次启动生成的管理员密码见 `docker compose logs app`；pgAdmin 用 `--profile tools`）；演示数据 `JABIZ_PASSWORD=… tools/demo/seed.sh`。见 `docs/guide/quickstart.md`
- 本地开发：仓库根目录 `docker compose up -d db`（数据库，端口 5436）→ `backend/` 下 `./gradlew :app:bootRun`（后端 8080，
  启动时 Flyway 先迁移平台脚本 `db/jabiz`、再迁移业务脚本 `db/migration`）→ `frontend/` 下 `pnpm install && pnpm dev`（5173，`/api` 代理到 8080）。
  开发用操作人请求头：`--args='--spring.profiles.active=dev'`（见 01 §5）。非 dev 启动需要 `JABIZ_JWT_SECRET`（Base64，≥32 字节，
  如 `openssl rand -base64 48`）；首个管理员用 `JABIZ_BOOTSTRAP_ADMIN_USER` / `JABIZ_BOOTSTRAP_ADMIN_PASSWORD` 创建（见 10 §7）；
  上传文件的存储目录 `JABIZ_FILES_LOCAL_ROOT`（非 dev 必须设置，dev 默认 `backend/app/build/jabiz-files`；见 14 §6）
- 集成测试调用 HTTP API：`dev` profile 下用 `X-Jabiz-*` 请求头；非 dev 下用 `TestTokens.bearer(jwtService, actor, permissions…)` 签发真实令牌
  （测试配置 `config/application.properties` 提供固定测试密钥与 BCrypt 强度 4）
- 事件与定时任务：测试配置关闭后台投递与调度（`jabiz.events.delivery.enabled=false`、`jabiz.jobs.scheduler.enabled=false`），
  测试直接调用 `OutboxDeliverer.deliverPending()` / `JobRunner.run(job, 计划时刻)`，场景中用 `deliverEvents: true` / `runJob` 步骤
- 场景回放（07 §3）：场景放在各模块 `src/test/resources/scenarios/**/*.yml`，快照为同目录的 `<名>.snapshot.json`（随变更提交）；
  `./gradlew :app:test --tests '*ScenarioTest'` 回放全部场景；确认行为变化正确后用 `./gradlew :app:test --tests '*ScenarioTest' -Dscenario.update-snapshots=true` 更新快照。
  每次回放使用新的 schema 与应用上下文（数据库同集成测试）
- 前端（`frontend/` 下，见 12）：`pnpm lint`、`pnpm typecheck`、`pnpm test`（Vitest）、`pnpm build`；`pnpm check:api` 确认生成的类型与快照一致。
  应用扩展（12 §9）：`JABIZ_ADMIN_EXTENSION=<目录> pnpm ext:check`（类型、lint、测试；`app` 的示范为 `../backend/app/admin-extension`）
  挂在子路径下构建：`VITE_BASE=/admin/ pnpm build`；后端按 `jabiz.web.spa[i].path` / `.index` / `.content-security-policy` 提供多个 SPA（见 17 §3.2）
  接口变化后：`./gradlew :app:test --tests '*OpenApiSnapshotIT' -Dopenapi.update-snapshot=true`（写 `frontend/openapi/openapi.json`）→ `pnpm gen:api`，一起提交
- 公开模板目录快照（15 §7）：`./gradlew :app:test --tests '*PublicQueriesSnapshotIT' -Dpublic-queries.update-snapshot=true`
  （写 `frontend/openapi/public-queries.json`，路径由 `jabizApp.publicQueriesSnapshot` 配置），随变更提交
- 前后端共享校验用例 `spec/validation-cases.json`：core `ValidationCasesTest` 与前端 `validation.cases.test.ts` 都执行；
  字段元数据变化后 `./gradlew :core:test -Dvalidation-cases.update=true` 重写其中的 `fields`
- 端到端（Playwright）：先运行打包的应用（`./gradlew :app:bootJar`，以 `JABIZ_JWT_SECRET`、`JABIZ_BOOTSTRAP_ADMIN_USER/PASSWORD`、`JABIZ_FILES_LOCAL_ROOT` 与数据库环境变量
  `SPRING_R2DBC_*`、`SPRING_FLYWAY_*` 启动 `app/build/libs/*.jar`），再在 `frontend/` 下
  `E2E_ADMIN_USER=… E2E_ADMIN_PASSWORD=… pnpm e2e`（`E2E_BASE_URL` 默认 `http://localhost:8080`；`E2E_CHROMIUM` 可指定已安装的 Chromium）。
  测试只增不删数据，可对同一数据库重复运行；CI 的 `e2e` 作业即如此
- 可观测性（13）：OTLP 默认关闭；设置 `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`、`MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true` +
  `MANAGEMENT_OTLP_METRICS_EXPORT_URL`、`MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT` 开启；JSON 日志 `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`
- 压测（不在 `check` 中）：对运行中的应用 `LOAD_USER=admin LOAD_PASSWORD=… ./gradlew :app:loadTest`（`LOAD_PRODUCTS`、`LOAD_ORDERS`、
  `LOAD_CONCURRENCY`、`LOAD_DURATION`、`LOAD_REPORT` 等），报告见 `docs/perf/phase-11-load-test.md`
- 数据库：PostgreSQL 16，连接信息通过环境变量提供，**不得写入仓库**（`docker-compose.yml` 中只有本机演示用的账号）。

## 7. 每个阶段的交付方式

1. 读 `docs/ROADMAP.md` 中对应阶段的目标、要求、验收标准。
2. **先给出实施计划并等待确认**，确认后再写代码。计划需列出：要改的模块和类、新增的表和迁移、测试清单、风险。
3. 在分支 `<线>/phase-<N>-<简短名>` 上实现（`<线>` 是该阶段所在的平台版本线，见第 8 节）。一个阶段过大时拆成多个 PR（`phase-<N>a`、`phase-<N>b` …）。
4. 完成后：运行全部测试和静态校验 → 用 `/code-review` 自查 → 涉及认证、权限、SQL 的阶段额外运行 `/security-review`。
5. 创建 PR，描述中**逐条对照验收标准**说明如何满足，并列出未完成项和已知问题。
6. 如果实现中改变了约定，同步更新本文件和 `docs/design/`。
7. 在 `docs/ROADMAP.md` 中更新该阶段的状态。

## 8. 平台与应用的分支（见 17 与决策 D19、D21）

- **版本线**：平台的每个不兼容版本是一条线，平台分支 `<主>.<次>/platform`（如 `1.0/platform`），线号写在根目录的 `.jabiz-platform-line`。
  只有应用必须适配的不兼容改动才开新线（从上一条线的平台分支拉出，第一个提交改线号）；兼容的新增与修复留在当前线。发布打标签 `platform-v<主>.<次>.<修订>`。
- 平台分支只含平台（`core`、`runtime`、`ext-geo`）、示范应用 `app`、通用后台 `frontend/`、`spec/`、`docs/design/`、`docs/guide/`。
  平台工作分支 `<线>/phase-<N><x>-<名>` 从 `<线>/platform` 拉出并合回。
- 应用分支 `<线>/<应用>`（如 `1.0/culture`）= 该线的平台 + 应用专有目录（列在应用分支根目录的 `.jabiz-app-paths` 中）；应用工作分支 `<线>/<应用>-<N>-<名>`。
  **线内的合并方向只有 `<线>/platform` → `<线>/<应用>`**；应用分支不修改平台目录与 `.jabiz-platform-line`（CI 的 `app-paths` 作业运行 `tools/check-app-paths.sh`，
  以该线的平台分支为基准，并检查以线命名的分支与线号一致；其测试为 `tools/test/check-app-paths.test.sh`），平台需要的改动先在该线的平台分支上完成（带平台自己的测试与示范）。
- **修复向前合并**：做在最旧的受影响线上，再合并到更新的线，各线再合并到自己的应用；只合并，不变基、不拣选。
- **应用升级**：`<新线>/<应用>` 从 `<旧线>/<应用>` 拉出，再合并 `<新线>/platform` 并适配；旧线上的应用分支随之冻结。
- **迁移**：不是最新的线不增加迁移（平台与应用都不加）。操作步骤见 `docs/guide/version-lines.md`。
- 应用自己的规则写在应用目录内的 `CLAUDE.md` 与 `docs/<应用>/`，不改本文件。
