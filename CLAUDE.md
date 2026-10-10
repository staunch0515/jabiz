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
| 前端 | 通用后台：pnpm、React 19、TypeScript、Vite、shadcn/ui（Radix Primitives + Tailwind CSS v4，组件源码只在平台的 `@jabiz/ui`）、TanStack Table、react-hook-form、Recharts、TanStack Query、React Router、i18next；类型由 OpenAPI 生成（见 12 与决策 D34；阶段 15 迁移完成前与 Ant Design 5 共存）。应用自有前端（公开网站、需登录的应用前端）工具链相同，经 `@jabiz/client` / `@jabiz/ui` 复用平台（见 17 §4 与决策 D19、D34） |
| 测试 | JUnit 5、Reactor Test、ArchUnit、BlockHound、jqwik、PostgreSQL（Testcontainers 或本地实例）；前端 Vitest、Playwright |

选择 WebFlux 的前提是：**业务开发者不写响应式代码**。所有业务扩展点必须是同步接口（见第 3 节）。

## 3. 分层纪律（最重要，违反即视为缺陷）

详见 `docs/design/01-core-vs-runtime.md`。

1. **核心层（`jabiz-core`）是纯 Java**：元模型、语义类型、类型转换、校验、规则、状态机、查询编译、SQL 模板渲染。
   **禁止引用 `reactor.*`、`org.springframework.r2dbc.*`、`io.r2dbc.*`、`org.springframework.web.*`。**
2. **运行时层（`jabiz-runtime`）很薄**：只负责执行（存储、事务、流程执行、Web 接入）。
3. **业务扩展点全部同步**：字段规则（`RulePredicate`）、迁移守卫、流程计算步骤（`ComputeStep`）、阻塞步骤（`BlockingStep`）。
   平台内部的 `StepHandler`（返回 `Mono`）只供平台自己的 I/O 步骤使用，不对业务开放。
   业务流程用平台步骤的工厂（`LoadEntity.by`、`QueryEntities.of`、`RunTemplate.of`、`SaveChanges.now`、`CallProcess.of`、`PublishEvent.of`；不能重叠的流程以 `HoldLock.shared/exclusive` 取命名锁，见 06 §4.1）
   做 I/O（业务参数用 `LoadParams.of`，以业务发生时间读取，见 04 §9），用 `ctx.changes()` 登记变更、`ctx.reject(...)` 累积违规，由平台在流程结束时统一提交（见 06 与决策 D11）。每个流程必须声明权限。
4. 业务模块（`app` 及以后的业务模块）**禁止引用 `reactor.*`**。以上规则由 ArchUnit 测试强制执行。
5. 请求路径上禁止阻塞调用；测试环境启用 BlockHound 检测。

## 4. 编码约定

- **SQL**：所有值必须参数绑定；表名、列名只能来自元数据，并经过 `SqlIdentifiers.require` 校验。禁止字符串拼接用户输入。
- **默认拒绝**：未实现、未配置的安全检查一律报错，绝不放行（参考现有 `AuthenticationHandler` 的写法）。
- **时间**：只能来自注入的 `java.time.Clock`，禁止 `Instant.now()` / `System.currentTimeMillis()` / 数据库 `now()` 作为业务时间。
  指一天而不是一个时刻的值（过账日、到期日）用日期类型 `f.asDate()`（`LocalDate`，列 `date`，02 §1.1），不用时刻或文本表示。
- **金额**：只用 `BigDecimal`，由 `SemanticKind.Monetary` 声明币种和小数位（小数位由平台自动校验，`MONETARY_SCALE`）。禁止 `double` / `float` 表示金额。
- **标识**：新实体主键使用 UUIDv7（默认 `EntityIdGenerator` 即 `UuidV7Generator`）；`process_seq_id` 来自数据库序列。
  业务单据号（不能缺号、不能重复）只经 `NumberSequence` Bean 与流程步骤 `AssignNumber` 取得（18 §2、决策 D23），不自己计数。
- **审批与职责分离**（见 18 §3–§4 与决策 D23）：需要审批的单据声明 `ApprovalSubject` Bean，流程中用 `RequireApproval` 取得结论（批准绑定内容哈希），
  以订阅 `jabiz.approval.approved` / `rejected` 继续；不自己写审批状态机或"准备人不能审批"之类的检查。审批对象的显示名写在消息 `approval.subject.<名>`，`ApprovalCase.reference(…)` 给出人认得的单据标识（待办标题用它，18 §5.2）。审批规则、限额、职责分离规则只经
  `CONTROL_CHANGE_PROPOSE` / `CONTROL_CHANGE_PUBLISH`（四眼）修改。
- **待办与通知**（见 18 §5）：需要人去做的事用步骤 `CreateTask`（指派给用户或权限，带来源键）登记、`CloseTasks` 关闭；不另建待办表。
  邮件只经待办的通知、单据的发送（`DOCUMENT_SEND`，22 §5）与事务邮件（流程步骤 `SendMail`，模板为 `MailTemplate` Bean 与消息 `mail.<名>.subject|body`，
  验证与重置链接用发送时才生成的一次性令牌：模板 `.token(用途, 有效期)`、流程中以 `MailTokens.consume` 使用，18 §5.6、决策 D35）；
  `jabiz.mail.enabled` 缺省关闭；不在流程中直接发邮件。
- **不可变数据**：优先使用 `record` 和不可变集合（`List.copyOf` / `Map.copyOf`）。
- **错误**：领域错误使用现有异常体系，经 `GlobalExceptionHandler` 转为 `ProblemDetail`：
  400 校验失败（附 `violations`）、404 不存在、409 并发冲突、422 业务规则拒绝、503 查询超时（`QUERY_TIMEOUT`，由数据库按时限中止，03 §1）。错误码可多语言（见设计文档）。
- **时态实体**（`eb.temporal()`，见 04 与决策 D9）：表只 INSERT；建表迁移中必须建 `UNIQUE(实体主键, version_no)`、
  `(实体主键, effect_start_time DESC, version_no DESC)` 与 `process_seq_id` 索引、指向 `op_process` / `entity_registry` 的外键，
  并执行 `SELECT jabiz_protect_append_only('<表>')` 安装禁止 UPDATE/DELETE/TRUNCATE 的触发器（启动自检检查）。
  测试中不能 `DELETE` 这类表：用各测试独有的数据（或写墓碑）隔离。
  规模（04 §5.3–§5.4 与决策 D29）：按不可变字段查询、唯一约束的字段要建索引（唯一约束缺少支撑索引时启动检查告警）；
  只记一次、以冲正等新实例更正的实体（如账本）声明 `t.writeOnce()`，迁移中另建只含实体主键的唯一索引（缺少即启动失败），更新、删除、撤销一律 422 `WRITE_ONCE`。
- **敏感信息**：密码、令牌等字段在 `toString()`、日志、`op_process.input_summary` 中必须遮蔽。实体字段用 `f.sensitive()`
  （读接口不返回、数据视图 API 不接受写入，只有专用流程能写）；流程输入输出 record 的秘密组件标 `@Sensitive` 并在 `toString()` 中遮蔽（见 10 §6）。
- **安全**（见 10 与决策 D12）：`/api/**` 默认要求认证（Bearer 访问令牌）；新的入口必须按元数据声明的权限码检查（`Permissions`），
  未声明即拒绝。例外只有公开只读接口 `/api/public/**`（见下条）、入站 Webhook `/api/inbound/**`（必须验签，D37）与登录、注册等认证接口。密码只用 BCrypt，且在 `BlockingStep` 中计算。访问令牌签名密钥只来自环境变量 `JABIZ_JWT_SECRET`。
- **登录入口与自助注册**（见 10 与决策 D36）：前端各自的登录入口（`jabiz.security.entries`）决定接受的角色，令牌只含该入口角色的权限；
  自助注册、邮箱验证、找回密码只经平台接口与流程（`SPONSOR_SIGN_UP` 等），不另写；需要已验证邮箱的操作声明 `requiresVerifiedEmail()`；封禁等应用规则写成 `SignInGuard`。
- **二次验证**（见 10 §9–§11 与决策 D28）：只用 TOTP 与恢复码（`SecUserMfa`，密钥以 `JABIZ_MFA_KEY` 加密）；登录与 step-up 都是写登录记录的流程，不另写验证逻辑。
  需要二次验证的操作只声明：流程 `requiresMfa(…)`、数据视图 `writeRequiresMfa(…)`（平台管理为 `ADMINISTRATION`），由入口与权限一起检查；
  角色可要求二次验证（`SecRole.requireMfa`）。`@Sensitive` 组件名在整个 JSON 中遮蔽，不要用 `code` 这类通用名字（用 `mfaCode`）。
- **单点登录**（见 10 §12 与决策 D28 第 6 条）：只做 OIDC（授权码 + PKCE + nonce，`jabiz.security.oidc.providers[i]`，客户端密钥只来自环境变量），
  平台自己校验 ID 令牌后签发自己的令牌；外部账号只经 `SecUserIdentity` 关联（管理员关联，或本人登录后 `SEC_IDENTITY_LINK_SELF`）；缺省不自动开户，提供方开启 `auto-provision` 且入口允许注册时才为已验证、未被占用的邮箱开户（决策 D39）；登录照常是写登录记录的流程（`SPONSOR_OIDC_SIGN_IN`）。
- **登录入口与登录前检查**（见 10 §15 与决策 D36）：不同前端接受哪些角色只在 `jabiz.security.entries.<名>` 声明（缺省入口 `admin` 接受全部角色）；令牌只含该入口所接受角色的权限，
  不在前端或业务代码里另做"这个前端不给某角色用"。应用要在登录与刷新时拒绝某些用户（封禁等）只实现 `SignInGuard`（core，同步，所需数据以 `loads()` 声明），不自己拦截登录接口。
  需要已验证邮箱的操作只声明：流程 `requiresVerifiedEmail()`、数据视图 `policy(p -> p.requiresVerifiedEmail())`（只管写）。客户端地址只经 `ClientAddresses`（可信代理 `jabiz.security.trusted-proxies`），不自己读转发头。
- **按权限显示明文**（见 10 §13.1 与决策 D28 第 7 条）：税号、账号等只让部分人看明文的字段用 `f.masked(权限, MaskStyle.LAST4|ALL|TAX_ID)`（文本字段；纳税人号码用 `TAX_ID`）；
  读接口、历史、审计、签发的报表中一律遮蔽，持有权限者经 `POST /api/datasets/{id}/reveal` 逐值显示（记入 `sys_reveal_record`），不另写"脱敏"或显示记录。
  模板与导出由平台在 SQL 中遮蔽并为持有权限者留记录；只有持有权限者能写入、筛选、排序。不要把遮蔽字段设为显示字段、默认排序或公开字段。
- **数据期限**（见 10 §13.2 与决策 D28 第 8 条）：只能看某一期间数据的人以带 `dataFrom` / `dataTo` 的角色分配表达；受期限约束的数据视图明确声明
  `scope(s -> s.withinDataPeriod("时间字段"))`（或经引用：`withinDataPeriod("引用字段", "被引用的不可变时间字段")`）。没有期限即不受限制，未声明的数据视图不受期限影响。
- **访问审查**（见 10 §13.3 与决策 D28 第 9 条）：访问权限报表是模板 `jabiz.security.access_review`，以 `REPORT_ISSUE` 按期末签发；签核只经 `ACCESS_REVIEW_SIGN_OFF`。
- **文件**（见 14 与决策 D18）：上传只经 `/api/files?policy=…`，类型按内容判定、图片一律重新编码（去掉 EXIF/GPS）；
  字段用 `f.kind(FileKind.of("策略"))` 引用文件（列 `uuid`，不加外键），策略（`FilePolicy` Bean）必须声明上传与读取权限。
  `sys_file` 是可删除的普通表，只经 `FILE_REGISTER` / `FILE_DELETE` / `FILE_PURGE_ORPHANS` 写入；业务流程删除文件前先清空引用并
  `SaveChanges.now`，再 `CallProcess.of("FILE_DELETE", …)`。文件名不进 `input_summary`；`FileStore` 返回 `Mono`/`Flux`，不对业务开放。
  服务器生成、交给外部的文件（银行付款文件、申报文件）不进 `sys_file`：业务流程以子流程 `CallProcess.of("FILE_ARCHIVE", …)` 原样保存到只追加的 `sys_generated_file`
  （声明读取所需的权限），下载只经 `GET /api/generated-files/{id}`（记入 `sys_reveal_record`）；不自建存档表（14 §10、决策 D31）。
- **公开访问**（见 15 与决策 D17）：匿名只能 `GET/HEAD /api/public/queries/{id}` 与 `/api/public/files/{id}[/{变体}]`，总开关
  `jabiz.public.enabled` 默认关闭。公开的行与列只在数据视图上声明：`publicRead(p -> p.fields(...))`（固定值范围、非默认视图、不含敏感字段），
  模板渲染时投影到白名单；公开模板头部写 `access: public`（代替 `permissions`），`datasets` 必须指向公开视图。文件是否公开由公开行的白名单文件字段推导，
  不设标记；需要立即撤下时在流程的提交后步骤 `FileAccess.invalidate(...)`。公开读取不写操作记录。
- **启动即失败**：元数据、数据视图、流程、SQL 模板、表结构的不一致，必须在启动时一次性全部报告，而不是等到请求触发。
  新的检查实现 `PlatformCheck`（返回问题列表，不抛异常），启动与 `platformCheck` 共用。
- **单据**（见 22 与决策 D30）：发票、确认书这类单据用 `DocumentLayout` Bean 声明（区块显示普通 SQL 模板的列，模板不写 `timeSlice`），
  由业务流程以子流程 `DOCUMENT_ISSUE` 签发并给出业务时点（单据日期）；`sys_document_run` 只追加、保存 PDF 原样字节，重印与发送一律取存档字节，从不重新排版。
  单据上的发出方信息来自数据而不是配置；版式的标题与区块文字写在消息资源 `document.<版式>[.<键>]`。
  以邮件发出单据只经 `DOCUMENT_SEND`（附件为存档字节）；收件地址由版式的 `recipients(模板, 列)` 从数据给出，发往其他地址需要 `document.send.any`。
- **账本、事件、定时任务**（见 11 与决策 D14）：账本交易只经 `LEDGER_POST` / `LEDGER_REVERSE` 写入，更正即冲正；
  需要跨实例规则保护的数据用视图策略 `processOnlyWrites()`。账本的科目层级、分析维度（`LedgerDimension` Bean）、行备注、来源单据与外币分录见 11 §1.4–§1.8 与决策 D24，
  子账单据过账时带上来源（`sourceEntity` / `sourceId`）。事件用 `PublishEvent`（流程事务内写 Outbox）或实体的 `eb.publishChanges()`；
  消费者（`EventSubscription`）与定时任务（`JobDefinition`）都只调用流程，不写 `@Scheduled` 方法。
  送给其他系统的事件用 webhook（11 §2.4 与决策 D33）：订阅只写在 `jabiz.webhooks.subscriptions`（密钥以占位符取自环境变量，主机须在 `allowed-hosts` 中；有订阅的事件不放遮蔽字段的值），
  由平台签名投递并重试。接收外部回调只经入站 Webhook（`/api/inbound/{提供方}`，验签、存档、去重后以事件交给流程，D37），不自写接收接口。
  业务的出站调用只在 `BlockingStep` 中经声明的 `ExternalService` 进行（有副作用的放提交后或带幂等键）；耗时的调用用异步外部作业 `StartExternalJob`（决策 D38）。
- **内容编辑**（见 16 与决策 D20）：多语言内容用 `f.apply(I18nText.of(...))`（值为 `{语言: 文本}`，存 `jsonb`，语言即平台支持的语言）；
  被引用的实体用 `eb.display(字段)` 声明显示字段；状态、审核意见等只由流程改变的字段用 `f.processOnly()`（照常可读，数据视图 API 与通用实体流程不能写）；
  以某实体为对象的流程用 `actsOn(实体, 输入组件[, when])` 声明，后台据此显示行操作（`when` 只是显示提示）。Markdown 只在前端渲染，不允许原始 HTML。
- **规则**（见 02 §3 与决策 D15）：导出给前端的字段规则只用 `Rules` 工厂（`RANGE` `SCALE` `LENGTH` `PATTERN` `NOT_FUTURE` `REQUIRED`）；
  依赖服务端状态的判断写成仅服务端规则。新增规则种类或语义约束时，先在 `spec/validation-cases.json` 加用例，前后端都要通过。
- **前端**（见 12）：业务对象不写前端代码，页面由元数据生成；界面按目录与权限隐藏操作，但权限只由服务端判断。
  元数据表达不了的工作流，由应用在自己目录中的**扩展**写页面（12 §9、决策 D22）：只经 `@jabiz/admin` 引用平台、不自带依赖，路由不占平台路径。
  界面组件只用 `@jabiz/ui`（shadcn 组件源码只在平台一处，应用与扩展不自行生成或复制；需要新组件先加到平台）；颜色只用主题 token，亮暗两种外观都过 axe（决策 D34）。
  手写页面与扩展中的用户编号（准备人、审批人等）以 `UserName`（`@jabiz/admin`，10 §14）显示为名字，不显示编号。
  实体与字段的显示名写在消息资源（`entity.<实体>`、`entity.<实体>.<字段>`，三种语言；应用以 `jabizApp { languages(…) }` 只选部分语言时只写所选的，12 §10）。改动 Web 接口后更新 OpenAPI 快照并 `pnpm gen:api`。
- **可观测性**（见 13 与决策 D16）：平台新的工作单元用 `PlatformObservations` 包装；观测标签只放名称与结果（流程、视图、模板、任务名），
  绝不放主键、操作人、字段值。遥测默认不外发，只经 OTLP 推送，不开放匿名的指标端点。
- **SQL 模板**：放在 `queries/**/*.sql`（YAML 头 + SQL，见 05）；表、列只写占位符；列表参数写 `= ANY(:name)`；不写外层 `LIMIT`/`ORDER BY`。
- **报表**（见 19 与决策 D25）：报表就是头部声明了 `report` 的 SQL 模板（标题、列名在消息 `query.<id>`、`query.<id>.<列>`），不另建报表定义；按时点运行用请求的 `asOf` / `knownAt` 或头部 `timeSlice`（参数即时点），不在模板里自己拼"当前版本"；流程中用 `RunTemplate.at`。导出只经 `POST /api/queries/{id}/export?format=csv|xlsx|pdf`（超过 `jabiz.reports.export.max-rows` 即拒绝，不截断）；PDF 的中文、日文字体由 `jabiz.reports.pdf.fonts` 提供。需要原样重现的报表用流程 `REPORT_ISSUE` 签发（存档只追加、带内容哈希），不自己保存报表文件。
- **导入**（见 20 与决策 D26）：批量导入只经 `ImportDefinition` Bean（文件策略只允许导入类型 `TEXT` / `XLSX` / `XML`，原样保存、只作附件）；
  每行或每组交给手工录入所用的同一个业务流程，不在导入中另写业务规则或直接写表。其他格式实现 `ImportParser`（core，同步）。解析 XML 一律禁止 DTD。
  导入与字段的显示名在消息 `import.<id>`、`import.<id>.<字段>`。预览是试运行（`ExecutionOptions.DRY_RUN`），不自己做"先校验后写入"的两套逻辑；
  提交有任何问题即整体拒收（只留下 `sys_import_run` 的记录），同一文件、同一外部引用只导入一次。场景中用步骤 `import` 导入文件（07 §3.1）。
- **审计**（见 21 §1 与决策 D27）：实体写入的前后值由平台在 `DatasetEntityManager` / `VersionAppender` 中记入只追加的 `sys_audit_record`（敏感字段只存 `***`），
  业务代码不自己写审计表或"修改日志"；新的写入路径必须经过这两处之一。可读但不应留在审计中的值（如文件名）用 `f.auditMasked()`。查询只经 `GET /api/audit/records`（`audit.read`）。
  只追加表由平台逐行封存（21 §2：`INTEGRITY_SEAL` / `INTEGRITY_VERIFY`，HMAC 链，密钥只来自 `JABIZ_INTEGRITY_KEY`，非 dev 缺少即启动失败）；
  新的只追加表必须有主键（启动检查 `INTEGRITY`），不需要另外声明。
- **保留、保全与导出**（见 21 §3–§4 与决策 D27）：保留期只以 `RetentionPolicy` Bean 声明（自时间字段或会计年度末 `jabiz.fiscal-year-end` 起算），法律保全只经
  `LEGAL_HOLD_PLACE` / `LEGAL_HOLD_RELEASE`；删除拦截由平台在删除路径中做（422 `RETENTION_ACTIVE` / `LEGAL_HOLD`），业务代码不自己判断。
  归档导出只经 `POST /api/exports/data`（CSV + `schema.json` + `manifest.json` + 报表 PDF），不另写导出。
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
  开发用操作人请求头：`--args='--spring.profiles.active=dev'`（见 01 §5）。非 dev 启动需要 `JABIZ_JWT_SECRET`、`JABIZ_INTEGRITY_KEY` 与 `JABIZ_MFA_KEY`（都是 Base64，≥32 字节，
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
  应用自有 SPA（12 §12）：`pnpm app:check <目录>`（共用依赖的版本须与平台前端一致，不一致即失败）
  挂在子路径下构建：`VITE_BASE=/admin/ pnpm build`；后端按 `jabiz.web.spa[i].path` / `.index` / `.content-security-policy` 提供多个 SPA（见 17 §3.2）
  接口变化后：`./gradlew :app:test --tests '*OpenApiSnapshotIT' -Dopenapi.update-snapshot=true`（写 `frontend/openapi/openapi.json`）→ `pnpm gen:api`，一起提交
- 公开模板目录快照（15 §7）：`./gradlew :app:test --tests '*PublicQueriesSnapshotIT' -Dpublic-queries.update-snapshot=true`
  （写 `frontend/openapi/public-queries.json`，路径由 `jabizApp.publicQueriesSnapshot` 配置），随变更提交
- 前后端共享校验用例 `spec/validation-cases.json`：core `ValidationCasesTest` 与前端 `validation.cases.test.ts` 都执行；
  字段元数据变化后 `./gradlew :core:test -Dvalidation-cases.update=true` 重写其中的 `fields`
- 端到端（Playwright）：先运行打包的应用（`./gradlew :app:bootJar`，以 `JABIZ_JWT_SECRET`、`JABIZ_INTEGRITY_KEY`、`JABIZ_MFA_KEY`、`JABIZ_SECURITY_MFA_ADMINISTRATION=false`（共用的管理员没有二次验证）、`JABIZ_BOOTSTRAP_ADMIN_USER/PASSWORD`、`JABIZ_FILES_LOCAL_ROOT` 与数据库环境变量
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
