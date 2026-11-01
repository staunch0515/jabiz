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
| 前端 | 通用后台：pnpm、React 19、TypeScript、Vite、shadcn/ui（Radix Primitives + Tailwind CSS v4，组件源码只在平台的 `@jabiz/ui`）、TanStack Table、react-hook-form、Recharts、TanStack Query、React Router、i18next；类型由 OpenAPI 生成（见 12 与决策 D34）。应用自有前端（公开网站、需登录的应用前端）工具链相同，经 `@jabiz/client` / `@jabiz/ui` 复用平台（见 17 §4 与决策 D19、D34） |
| 测试 | JUnit 5、Reactor Test、ArchUnit、BlockHound、jqwik、PostgreSQL（Testcontainers 或本地实例）；前端 Vitest、Playwright |

选择 WebFlux 的前提是：**业务开发者不写响应式代码**。所有业务扩展点必须是同步接口（见第 3 节）。

## 3. 分层纪律（最重要，违反即视为缺陷）

详见 `docs/design/01-core-vs-runtime.md`。

1. **核心层（`jabiz-core`）是纯 Java**：元模型、语义类型、类型转换、校验、规则、状态机、查询编译、SQL 模板渲染。
   **禁止引用 `reactor.*`、`org.springframework.r2dbc.*`、`io.r2dbc.*`、`org.springframework.web.*`。**
2. **运行时层（`jabiz-runtime`）很薄**：只负责执行（存储、事务、流程执行、Web 接入）。
3. **业务扩展点全部同步**：字段规则（`RulePredicate`）、迁移守卫、流程计算步骤（`ComputeStep`）、阻塞步骤（`BlockingStep`）。
   平台内部的 `StepHandler`（返回 `Mono`）只供平台自己的 I/O 步骤使用，不对业务开放。
   业务流程用平台步骤的工厂（`LoadEntity.by`、`QueryEntities.of`（几个互不依赖的查询一条语句：`QueryEntities.all`）、`RunTemplate.of`、`SaveChanges.now`、`CallProcess.of`、`PublishEvent.of`；不能重叠的流程以 `HoldLock.shared/exclusive` 取命名锁，见 06 §4.1）
   做 I/O（业务参数用 `LoadParams.of`，以业务发生时间读取；可以尚未设置的用 `LoadParams.optional` 并以 `find` 读取，见 04 §9），用 `ctx.changes()` 登记变更、`ctx.reject(...)` 累积违规，由平台在流程结束时统一提交（见 06 与决策 D11）。每个流程必须声明权限。
   热门的命名锁在流程上以 `admission(族, 输入 -> 键)` 声明取锁前的排队（族名与键同该流程最外层 `HoldLock` 的名称，由同一辅助方法生成；06 §4.2、决策 D45），正确性仍由 `HoldLock` 保证；
   子流程从不排队；过载即 503 `PROCESS_BUSY`。流程入口的限流只在 `jabiz.process.rate-limits` 配置，不在业务代码中自己计数。
   不经流程接口的流程按用途声明（06 §8、决策 D41）：事件消费、定时任务、回调用 `internal()`（只由系统身份运行），平台入口自己运行的用 `entryOnly()`，只作子流程的用 `subProcessOnly()`，
   另一个平台转发并签名的访客请求（商店的店面经 `/apps/...`）用 `appProxy(代理)`（只经 `/api/proxy/{代理}/{流程}`，调用者是 `AppProxyCaller`，按 `per-tenant` / `per-visitor` 限流，正文不签名、当作不可信输入，10 §15.13、决策 D60）；依赖声明，由平台检查（步骤中对系统身份的检查只是已有处的多余的第二道防线，不再新加）。
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
  以订阅 `jabiz.approval.approved` / `rejected` 继续；不自己写审批状态机或"准备人不能审批"之类的检查。审批对象的显示名写在消息 `approval.subject.<名>`，`ApprovalCase.reference(…)` 给出人认得的单据标识（待办标题用它，18 §5.2）。审批规则、限额、职责分离规则与受控的业务参数（`ControlledParams` Bean，04 §9.1、决策 D40）只经
  `CONTROL_CHANGE_PROPOSE` / `CONTROL_CHANGE_PUBLISH`（四眼）修改。
  授予受保护的权限（`jabiz.security.protected-grants.permissions`，缺省含 `*`、改权限、审批、记账与改参数的权限）、对其持有人重设密码 / 二次验证 / 关联外部账号，
  须另一位管理员审批（10 §16、决策 D48）：只经 `SEC_USER_ROLE_ASSIGN`、`SEC_ROLE_PERMISSION_ADD`、`SEC_USER_IDENTITY_LINK`、`SEC_USER_SET_PASSWORD`、`SEC_MFA_RESET`、`SEC_USER_EMAIL_SET`（持有人的邮箱），
  平台在 `VersionAppender` 中拒绝其他途径；不在业务流程中自己授予这类权限；建立期（首个管理员之外不足两位 `*` 管理员）内当即生效，之后首个管理员永久退出受保护的操作。
  审批人须持有变更给出的全部受保护权限；获得者的凭据须是本人的（所有权链：管理员知道过的密码及由它改出的都不算，管理员建的账号先由本人经邮件找回密码）。
  以 `jabiz.` 开头的事件类型只由平台发出，应用的事件用应用自己的名字。
- **待办与通知**（见 18 §5）：需要人去做的事用步骤 `CreateTask`（指派给用户或权限，带来源键）登记、`CloseTasks` 关闭；不另建待办表。
  邮件只经待办的通知、单据的发送（`DOCUMENT_SEND`，22 §5）与事务邮件（流程步骤 `SendMail`，模板为 `MailTemplate` Bean 与消息 `mail.<名>.subject|body`，
  验证与重置链接用发送时才生成的一次性令牌：模板 `.token(用途, 有效期)`、流程中以 `MailTokens.consume` 使用，18 §5.6、决策 D35）；
  `jabiz.mail.enabled` 缺省关闭；不在流程中直接发邮件。要经应用自己登记的服务器发出的邮件用 `SendMail.via(ctx -> 键)`，服务器由应用的一个 `MailSenderSource` Bean 给出（18 §5.6、决策 D56），
  其密码只以 `SealedSecrets` 密封保存（步骤 `SealSecret`，密钥 `JABIZ_SEAL_KEY`），失败绝不改经平台的服务器。可退订的模板（`NOTIFICATION`）以 `.entries(入口…)` 声明给哪些登录入口的用户（缺省全部，偏好只列令牌入口的；只寄给不是账号的地址的通知另加 `.addressesOnly()`，不在任何人的偏好中，17r），
  显示名写在消息 `mail.<名>.name`；收件偏好与邮件语言只经平台接口与积木 `MailPreferences`，切换界面语言只询问、不静默改邮件语言（`offerMailLanguage`，18 §5.6、决策 D49）。
  应用的前端要有自己的退订页时，挂载积木 `MailUnsubscribe` 并配置 `jabiz.mail.unsubscribe-paths.<入口>`（相对该入口 `app-path`；声明了该入口的通知链接到它，D52 第 3 条），
  不自写退订页或拼退订链接。
- **不可变数据**：优先使用 `record` 和不可变集合（`List.copyOf` / `Map.copyOf`）。
- **错误**：领域错误使用现有异常体系，经 `GlobalExceptionHandler` 转为 `ProblemDetail`：
  400 校验失败（附 `violations`）、404 不存在、409 并发冲突、422 业务规则拒绝、503 查询超时（`QUERY_TIMEOUT`，由数据库按时限中止，03 §1）、503 热门锁的队列已满或排队超时（`PROCESS_BUSY` 带 `Retry-After`，06 §4.2）、429 限流（`RATE_LIMITED`）。错误码可多语言（见设计文档）。
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
  登录类接口限流（地址 + 名字、地址、IPv6 站点、实例）、口令哈希有并发上限（`jabiz.security.sign-in.rate-limit.*`、`jabiz.security.password-hashing.*`，10 §4.1–§4.2、决策 D47），由平台在入口处做，不另写；客户端地址只经 `jabiz.security.trusted-proxies`，不用 `server.forward-headers-strategy`。
  会话（刷新令牌族）的列出与吊销只经平台接口与流程（本人 `/api/auth/account/sessions/**`，管理员 `SEC_USER_SESSIONS_REVOKE`，10 §15.9），不自写会话表或吊销逻辑。
- **登录入口与自助注册**（见 10 与决策 D36）：前端各自的登录入口（`jabiz.security.entries`）决定接受的角色，令牌只含该入口角色的权限；
  自助注册、邮箱验证、找回密码、本人改密码只经平台接口与流程（`SPONSOR_SIGN_UP` 等、`SEC_ACCOUNT_PASSWORD_CHANGE`），不另写；
  管理员建的账号由本人经邀请设定密码（`SEC_USER_INVITE` 或建号时 `invite`，只寄到建号时填写、未验证的邮箱，前端 `MailLinkPage` 的 `invitation` 页，10 §15.14、决策 D63），不给管理员设的密码；需要已验证邮箱的操作声明 `requiresVerifiedEmail()`；封禁等应用规则写成 `SignInGuard`。
  已登录、邮箱已验证的用户加入另一个入口只经 `POST /api/auth/account/entries/{entry}/join`（前端 `joinEntry`，只加该入口的注册角色，受保护的照旧须审批）；给出注册角色之前的应用规则（每条途径都问，按 `SignUpAttempt.Path` 区分；注册时没有用户编号，不泄露地址）只写成 `SignUpGuard`（10 §15.15、决策 D66），不自己拦注册或加入的接口。
  只凭邮箱登录（寄出一次性链接）只经入口的 `email-link` 与平台接口（`/api/auth/email-link`，前端积木 `EmailLinkForm` 与 `MailLinkPage` 的 `email-link` 页；`admin` 与接受 `*` 的入口不能开启，10 §15.11、决策 D54），不自写"输入邮箱即登录"。
  被另一个平台内嵌、以其签发的短时令牌登录（如 Shopify 的会话令牌）只经入口的 `external-token` 与提供方 `jabiz.security.external-tokens.<名>`（密钥只取自环境变量）、平台接口 `/api/auth/external-token`（前端 `signInWithExternalToken`）；
  身份是租户中的一个人（`SecUserIdentity(ext:<名>, <租户>/<sub>)`），流程中核对店员递来的令牌只用步骤 `VerifyExternalToken`；页面要被该租户内嵌时 SPA 声明 `embedding`，不自己改 CSP 或 `X-Frame-Options`（10 §15.12、决策 D59）。
  会话的有效期、闲置时长与存放方式按入口配置（`jabiz.security.entries.<名>.session.*`，D43）；`admin` 入口不能用 `device`。
- **应用前端与 PWA**（见 17 §4.1、指南 `docs/guide/app-spa.md` 与决策 D43）：需要登录的应用前端放在应用目录中（自己的 lockfile，经 `link:` 用 `@jabiz/client` / `@jabiz/ui`，
  版本与平台一致：`app:check`），以 `jabizApp { spa(路径, 目录, entry = 入口, pwa = Pwa.ON) }` 构建、用预设 `@jabiz/client/vite`；页面用 `@jabiz/ui` 的登录与账户积木，
  会话按入口（`configureClient({ entry })`）。入口的 `app-path` 是该前端的根：邮件链接只用 `{entryUrl}` / `{entryUrl.<入口>}`，OIDC 回调为提供方的 `redirect-uris.<入口>`。
  Service Worker 只用平台的（只缓存外壳、不缓存接口、更新经用户同意，撤下用 `Pwa.REMOVE`），不引入 Workbox；不开 CORS、不用 Cookie。
- **二次验证**（见 10 §9–§11 与决策 D28）：只用 TOTP 与恢复码（`SecUserMfa`，密钥以 `JABIZ_MFA_KEY` 加密）；登录与 step-up 都是写登录记录的流程，不另写验证逻辑。
  需要二次验证的操作只声明：流程 `requiresMfa(…)`、数据视图 `writeRequiresMfa(…)`（平台管理为 `ADMINISTRATION`），由入口与权限一起检查；
  角色可要求二次验证（`SecRole.requireMfa`）。`@Sensitive` 组件名在整个 JSON 中遮蔽，不要用 `code` 这类通用名字（用 `mfaCode`）；
  只给调用者、不留记录的秘密标 `@Sensitive(returned = true)`（响应中原样返回，记录与事件中遮蔽，10 §6）。
- **单点登录**（见 10 §12 与决策 D28 第 6 条）：只做 OIDC（授权码 + PKCE + nonce，`jabiz.security.oidc.providers[i]`，客户端密钥只来自环境变量），
  平台自己校验 ID 令牌后签发自己的令牌；外部账号只经 `SecUserIdentity` 关联（管理员关联，或本人登录后经 `/api/auth/account/identities` 关联与解除：近期二次验证或当前密码，`SEC_IDENTITY_LINK_SELF` / `SEC_IDENTITY_UNLINK_SELF`，前端积木 `LinkedIdentities`）；
  缺省不自动开户，提供方开启 `auto-provision` 且入口允许注册时才为提供方担保（`kind: google` 只认 gmail 或 Workspace）、未被占用的邮箱开户（`SPONSOR_OIDC_SIGN_UP`，决策 D39），邮箱已被使用即 403 `OIDC_ACCOUNT_EXISTS`、绝不自动关联；
  接管可预占的账号时删除其关联；登录照常是写登录记录的流程（`SPONSOR_OIDC_SIGN_IN`）。不自己写关联或开户。
- **登录入口与登录前检查**（见 10 §15 与决策 D36）：不同前端接受哪些角色只在 `jabiz.security.entries.<名>` 声明（缺省入口 `admin` 接受全部角色）；令牌只含该入口所接受角色的权限，
  不在前端或业务代码里另做"这个前端不给某角色用"。应用要在登录与刷新时拒绝某些用户（封禁等）只实现 `SignInGuard`（core，同步，所需数据以 `loads()` 声明），不自己拦截登录接口。
  需要已验证邮箱的操作只声明：流程 `requiresVerifiedEmail()`、数据视图 `policy(p -> p.requiresVerifiedEmail())`（只管写）。客户端地址只经 `ClientAddresses`（可信代理 `jabiz.security.trusted-proxies`），不自己读转发头。
- **按权限显示明文**（见 10 §13.1 与决策 D28 第 7 条）：税号、账号等只让部分人看明文的字段用 `f.masked(权限, MaskStyle.LAST4|ALL|TAX_ID)`（文本字段；纳税人号码用 `TAX_ID`）；
  读接口、历史、审计、签发的报表中一律遮蔽，持有权限者经 `POST /api/datasets/{id}/reveal` 逐值显示（记入 `sys_reveal_record`），不另写"脱敏"或显示记录。
  模板与导出由平台在 SQL 中遮蔽并为持有权限者留记录；只有持有权限者能写入、筛选、排序。不要把遮蔽字段设为显示字段、默认排序或公开字段。
- **入口的范围**（见 10 §13.4 与决策 D64）：同一入口的不同会话只看到、只处理"自己那一份"（运营商只看到自己的商户）时，以 `EntryScopeResolver` Bean 给入口一个范围名并在登录与刷新时算出值，
  数据视图以 `scope(s -> s.withinEntryScope(名, 字段[, EntryScopeVia.of(对照实体, 键, 范围字段)]))` 声明，审批与待办以 `ApprovalCase.scope` / `TaskSpec.scope` 记范围（缺省为创建的会话的），
  流程中核对对象用步骤 `RequireScope.of(名, ctx -> 值)`；不在模板参数或前端传运营商编号，不自己过滤。未声明的数据视图不受范围约束，须逐个声明并测试。
- **数据期限**（见 10 §13.2 与决策 D28 第 8 条）：只能看某一期间数据的人以带 `dataFrom` / `dataTo` 的角色分配表达；受期限约束的数据视图明确声明
  `scope(s -> s.withinDataPeriod("时间字段"))`（或经引用：`withinDataPeriod("引用字段", "被引用的不可变时间字段")`）。没有期限即不受限制，未声明的数据视图不受期限影响。
- **访问审查**（见 10 §13.3 与决策 D28 第 9 条）：访问权限报表是模板 `jabiz.security.access_review`，以 `REPORT_ISSUE` 按期末签发；签核只经 `ACCESS_REVIEW_SIGN_OFF`。
- **文件**（见 14 与决策 D18）：上传只经 `/api/files?policy=…`（另一个入口只有外部作业的文件输出，走同一流水线、以请求人登记，14 §11、D50），类型按内容判定、图片一律重新编码（去掉 EXIF/GPS）；
  `privateMetadata()` 策略的文件编号是随机的 UUIDv4（主键约定的例外，D48），其余为 UUIDv7；
  字段用 `f.kind(FileKind.of("策略"))` 引用文件（列 `uuid`，不加外键），策略（`FilePolicy` Bean）必须声明上传与读取权限；原名与上传者不应让其他读者看到的策略声明 `privateMetadata()`（只有上传者与 `file.read` 看得到，下载名为中性名，14 §5.1、决策 D47）。
  `sys_file` 是可删除的普通表，只经 `FILE_REGISTER` / `FILE_DELETE` / `FILE_PURGE_ORPHANS` 写入；业务流程删除文件前先清空引用并
  `SaveChanges.now`，再 `CallProcess.of("FILE_DELETE", …)`。文件名不进 `input_summary`；`FileStore` 返回 `Mono`/`Flux`，不对业务开放。
  服务器生成、交给外部的文件（银行付款文件、申报文件）不进 `sys_file`：业务流程以子流程 `CallProcess.of("FILE_ARCHIVE", …)` 原样保存到只追加的 `sys_generated_file`
  （声明读取所需的权限），下载只经 `GET /api/generated-files/{id}`（记入 `sys_reveal_record`）；不自建存档表（14 §10、决策 D31）。
- **公开访问**（见 15 与决策 D17）：匿名只能 `GET/HEAD /api/public/queries/{id}` 与 `/api/public/files/{id}[/{变体}]`，总开关
  `jabiz.public.enabled` 默认关闭。公开的行与列只在数据视图上声明：`publicRead(p -> p.fields(...))`（固定值范围、非默认视图、不含敏感字段），
  模板渲染时投影到白名单；公开模板头部写 `access: public`（代替 `permissions`），`datasets` 必须指向公开视图。文件是否公开由公开行的白名单文件字段推导，
  不设标记；需要立即撤下时在流程的提交后步骤 `FileAccess.invalidate(...)`。公开读取不写操作记录。
  匿名访客的浏览与点击只经公开计数器（`PublicCounter` Bean，键来自公开视图的白名单字段，`owner(字段)` 记下键的所有人供应用按读者限定，所有人是用户账号时加 `ownerIsUser()`，删除账号时计数随之删除；前端 `countPublic`；`POST /api/public/counters/{计数器}/{键}`，
  每访客每天一次、只在内存中去重、按日聚合到 `sys_public_counter_day`，15 §10、决策 D65），不自写匿名写入的接口或逐次记录访问。
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
  需要逐项提交的批量任务用 `JobDefinition.forEach`（每项一个事务、每项流程为 `internal()`），不用 `CallProcess.forEach` 做批量（11 §4.1、决策 D42）。
  送给其他系统的事件用 webhook（11 §2.4 与决策 D33）：订阅只写在 `jabiz.webhooks.subscriptions`（密钥以占位符取自环境变量，主机须在 `allowed-hosts` 中；有订阅的事件不放遮蔽字段的值），
  由平台签名投递并重试。接收外部回调只经入站 Webhook（`/api/inbound/{提供方}`，验签、存档、去重后以事件交给流程，D37；只签正文的发送方用方案 `body-hmac-sha256`，
  其请求头中的事件号、主题与元数据不签名，只用于去重与路由，内容以签名的正文为准，D57），不自写接收接口。
  业务的出站调用只在 `BlockingStep` 中经声明的 `ExternalService` 进行（有副作用的放提交后或带幂等键）；耗时的调用用异步外部作业 `StartExternalJob`（决策 D38）。
  作业的输入上限按类型 `maxInputSize`（缺省 64 KiB，至多 1 MiB；输入永久保存，只放需要的内容）；作业要读文件，类型声明 `inputFiles`、流程以 `StartExternalJob…files(…)` 给出（按请求人的读取权限检查），
  处理器只经 `ExternalJobContext.files()` 读；要产生文件，类型声明 `outputFiles(策略, …)`、处理器以 `ExternalJobResult…file(键, 名称, 字节)` 返回，完成流程以 `ExternalJobCompletion.file(键)` 取得编号（D50）。处理器不接触 `com.jabiz.runtime.file`。
  被取消的作业要结算或通知时，类型声明 `onCancelled(流程)`（`internal()`，输入 `ExternalJobCancellation`，含全部尝试的用量与取消人），不自己订阅 `jabiz.external-job.finished`；
  流程中取消作业只用平台步骤 `CancelExternalJob.of(类型, ctx -> 作业号)[.ifOpen()]`（流程自己的权限；缺省只取消该类型、由操作人开始的作业，别人的与不存在同一个 404；
  代表已知所有人用 `.requestedBy(ctx -> 用户)`，运维流程取消任何人的作业须显式 `.anyRequester()`，D52 第 2 条）；一个操作可能结束几种作业时用一个步骤
  `CancelExternalJob.of(List.of(类型…), ctx -> 作业号)`，不按类型拆成子流程。
  以业务开关停下某类作业只在类型上声明 `enabledBy(参数键)`（布尔业务参数，列入 `ControlledParams`，否则启动检查警告；D52 第 4 条）：参数此刻不是 `true` 即暂停，
  作业等待而不用掉尝试、照常登记与取消；不在处理器或流程中自己判断开关。
  提供方不按幂等键去重时重试可能重复计费：在尝试中 `recordUsage`、没有回答的尝试按估计计费、在提供方设置支出上限（D52 第 1 条）。
  没有回答的尝试的个数只取结局输入的 `unrecordedAttempts`（`LEASE_EXPIRED` / `TIMED_OUT` 而没有用量；D52 第 5 条），不自己读平台的尝试表推算；迟到实例的用量由平台照记。
  `FAILED` 的尝试不计入个数：调用计费的提供方的处理器捕获 `ExternalCallException`（`TIMEOUT` 等没有回答的），先 `recordUsage(估计)` 再重新抛出。
- **内容编辑**（见 16 与决策 D20）：多语言内容用 `f.apply(I18nText.of(...))`（值为 `{语言: 文本}`，存 `jsonb`，语言即平台支持的语言）；
  被引用的实体用 `eb.display(字段)` 声明显示字段；状态、审核意见等只由流程改变的字段用 `f.processOnly()`（照常可读，数据视图 API 与通用实体流程不能写）；
  以某实体为对象的流程用 `actsOn(实体, 输入组件[, when])` 声明，后台据此显示行操作（`when` 只是显示提示）。Markdown 只在前端渲染，不允许原始 HTML。
- **规则**（见 02 §3 与决策 D15）：导出给前端的字段规则只用 `Rules` 工厂（`RANGE` `SCALE` `LENGTH` `PATTERN` `NOT_FUTURE` `REQUIRED`）；
  依赖服务端状态的判断写成仅服务端规则。新增规则种类或语义约束时，先在 `spec/validation-cases.json` 加用例，前后端都要通过。
- **前端**（见 12）：业务对象不写前端代码，页面由元数据生成；界面按目录与权限隐藏操作，但权限只由服务端判断。
  元数据表达不了的工作流，由应用在自己目录中的**扩展**写页面（12 §9、决策 D22）：只经 `@jabiz/admin` 引用平台（测试另有 `@jabiz/admin/testing`，只在测试中）、不自带依赖，路由不占平台路径；
  扩展的 lint 拒绝直接引用 `@jabiz/ui`、`@jabiz/client`。
  界面组件只用 `@jabiz/ui`（shadcn 组件源码只在平台一处，应用与扩展不自行生成或复制；需要新组件先加到平台）；颜色只用主题 token（第三方品牌标志按其指南用自己的颜色，D44），亮暗两种外观都过 axe（决策 D34）。
  axe 的检查只用 `@jabiz/ui/testing`（Vitest 的 `expectAccessible`；扩展经 `@jabiz/admin/testing`）与 `@jabiz/ui/testing/playwright`（`expectAccessible(page, 名称, { expect })`、`accessibilityViolations`、`animationsSettled`、`setAppearance`），不复制辅助函数（12 §11）。
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
  业务代码不自己写审计表或"修改日志"；新的写入路径必须经过这两处之一（普通表只经 `DatasetEntityManager` 写入：按已知版本的更新依赖于此，D46）。可读但不应留在审计中的值（如文件名）用 `f.auditMasked()`。查询只经 `GET /api/audit/records`（`audit.read`）。
  只追加表由平台逐行封存（21 §2：`INTEGRITY_SEAL` / `INTEGRITY_VERIFY`，HMAC 链，密钥只来自 `JABIZ_INTEGRITY_KEY`，非 dev 缺少即启动失败）；
  新的只追加表必须有主键（启动检查 `INTEGRITY`），不需要另外声明。迁移只能给只追加表加列，不改名、不删列、不改类型、不删表（封存过的列会被报告为被改，D58）：
  平台与每个应用以测试夹具 `SealedColumnsHistory` 检查自己的迁移，启动检查对封存过、已不存在的列给出警告。
- **账号删除与个人信息抹除**（见 10 §17、21 §2.5 与决策 D62）：申请删除只经入口的 `account-deletion` 与平台接口（本人 `/api/auth/account/deletion`，
  不装 App 的 `/api/auth/account-deletion` 邮件链接，管理员 `SEC_USER_DELETION_SCHEDULE`；冷静期 `jabiz.security.account-deletion.cooling-off`，
  到期由定时任务 `jabiz.account-deletion` 执行；前端积木 `AccountDeletion`、`AccountDeletionRequest` 与 `MailLinkPage` 的 `account-deletion` 页，10 §17.5），不自写删除申请或"注销"页。
  删除账号只经 `ACCOUNT_ERASE`（停用、清空、墓碑、结束会话，普通事务）与 `ERASURE_PURGE`
  （在维护连接上覆盖只追加表中的个人字段，`jabiz.erasure.datasource.*`，凭据只来自环境变量，登录属于 `jabiz_maintenance`；应用自己的登录绝不属于它），
  不自己写抹除 SQL、不关触发器、不删除只追加的行。应用保存个人信息的实体以 `PersonalDataEraser` Bean 声明（`ErasedEntity.delete` / `clear` / `keep`，`.documents(版式)`；同一实体可按不同的用户字段声明几次，D62 第 8 条），
  申请前的提示以 `notices` 与消息 `erasure.notice.<代码>` 给出。新增保存个人信息副本的平台表时，同时登记到 `ErasurePurger` 的清除范围，并让 `ErasureIT` 的全表搜索覆盖它。
- **保留、保全与导出**（见 21 §3–§4 与决策 D27）：保留期只以 `RetentionPolicy` Bean 声明（自时间字段或会计年度末 `jabiz.fiscal-year-end` 起算，日期按 `jabiz.fiscal-year-zone`，缺省 UTC，D48），法律保全只经
  `LEGAL_HOLD_PLACE` / `LEGAL_HOLD_RELEASE`；删除拦截由平台在删除路径中做（422 `RETENTION_ACTIVE` / `LEGAL_HOLD`），业务代码不自己判断。
  归档导出只经 `POST /api/exports/data`（CSV + `schema.json` + `manifest.json` + 报表 PDF），不另写导出。
  生成的文件与签发的单据的保留期同样以 `RetentionPolicy.of("GeneratedFile" | "DocumentRun")` 声明（只读平台实体，`audit.read`，不含内容与名称，21 §3.1）。
- **语句数**（见 06 §3.1、03 §3.1 与决策 D46）：平台在事务中缓冲写入（插入、按版本的更新、子流程的操作记录、事件、幂等结果），插入随下一条平台查询（取锁等）或在读写其表的语句、有时限的读取、模板、
  保存点、阻塞步骤与提交之前写出（读之前写出；应急开关 `jabiz.storage.write-batching.enabled`，缺省开）；引用检查复用本事务的读写，普通实体按本事务读到的版本更新；
  这些都在平台的写入路径中，业务代码不自己合并语句或缓存读到的实例，也不跨事务缓存科目、维度取值等可变数据。事务中的语句只经存储引擎发出（不直接用 `DatabaseClient`，ArchUnit 检查）。平台的标准写入路径（账本过账与冲正、示范的下单与发货、按已知版本更新、
  `HoldLock`、带幂等键的根流程）的语句数由 `StatementBudgetIT` 断言确切值，改变写入路径须同时改预算并说明原因。
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
  如 `openssl rand -base64 48`），示范应用另需外部服务 `demo-ai` 的密钥 `JABIZ_DEMO_AI_KEY`（任意值；D38）；首个管理员用 `JABIZ_BOOTSTRAP_ADMIN_USER` / `JABIZ_BOOTSTRAP_ADMIN_PASSWORD` 创建（见 10 §7）；
  上传文件的存储目录 `JABIZ_FILES_LOCAL_ROOT`（非 dev 必须设置，dev 默认 `backend/app/build/jabiz-files`；见 14 §6）
- 集成测试调用 HTTP API：`dev` profile 下用 `X-Jabiz-*` 请求头；非 dev 下用 `TestTokens.bearer(jwtService, actor, permissions…)` 签发真实令牌
  （测试配置 `config/application.properties` 提供固定测试密钥与 BCrypt 强度 4）
- 事件、定时任务与外部作业：测试配置关闭后台投递、调度与外部作业执行器（`jabiz.events.delivery.enabled=false`、`jabiz.jobs.scheduler.enabled=false`、
  `jabiz.external-jobs.executor.enabled=false`），测试直接调用 `OutboxDeliverer.deliverPending()` / `JobRunner.run(job, 计划时刻)` /
  `ExternalJobExecutor.runPending()`，场景中用 `deliverEvents: true` / `runJob` / `runExternalJobs: true` / `completeExternalJob`（不调用外部服务）步骤
  公开计数器（D65）同样不定时写入（`jabiz.public.counters.flush-interval=0s`），测试直接调用 `PublicCounters.flush()`
- 场景回放（07 §3）：场景放在各模块 `src/test/resources/scenarios/**/*.yml`，快照为同目录的 `<名>.snapshot.json`（随变更提交）；
  `./gradlew :app:test --tests '*ScenarioTest'` 回放全部场景；确认行为变化正确后用 `./gradlew :app:test --tests '*ScenarioTest' -Dscenario.update-snapshots=true` 更新快照。
  每次回放使用新的 schema 与应用上下文（数据库同集成测试）
- 前端（`frontend/` 下，见 12）：`pnpm lint`、`pnpm typecheck`、`pnpm test`（Vitest）、`pnpm build`；`pnpm check:api` 确认生成的类型与快照一致。
  应用扩展（12 §9）：`JABIZ_ADMIN_EXTENSION=<目录> pnpm ext:check`（类型、lint、测试；`app` 的示范为 `../backend/app/admin-extension`）
  应用自有 SPA（12 §12）：`pnpm app:check <目录>`（共用依赖的版本须与平台前端一致，不一致即失败；有 `@jabiz/ui/testing` 所用的测试工具时它们也须一致；Gradle 构建中为 `spaCheck<名>`，在 `check` 中）。
  示范门户 `backend/app/portal/`（`/portal/`，PWA）：`pnpm app:check ../backend/app/portal`，以及在该目录下 `pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test`
  挂在子路径下构建：`VITE_BASE=/admin/ pnpm build`；后端按 `jabiz.web.spa[i].path` / `.index` / `.content-security-policy` 提供多个 SPA（入口页随部署而变的公开值写 `content="{{jabiz:名}}"`、配置 `.index-values.<名>`，17g），裸域名可用 `jabiz.web.root-redirect` 转向到一个路径、搬走的前缀用 `jabiz.web.redirects` 保持旧链接有效（见 17 §3.2）
  接口变化后：`./gradlew :app:test --tests '*OpenApiSnapshotIT' -Dopenapi.update-snapshot=true`（写 `frontend/openapi/openapi.json`）→ `pnpm gen:api`，一起提交
- 公开模板目录快照（15 §7）：`./gradlew :app:test --tests '*PublicQueriesSnapshotIT' -Dpublic-queries.update-snapshot=true`
  （写 `frontend/openapi/public-queries.json`，路径由 `jabizApp.publicQueriesSnapshot` 配置），随变更提交
- 前后端共享校验用例 `spec/validation-cases.json`：core `ValidationCasesTest` 与前端 `validation.cases.test.ts` 都执行；
  字段元数据变化后 `./gradlew :core:test -Dvalidation-cases.update=true` 重写其中的 `fields`
- 端到端（Playwright）：先运行打包的应用（`./gradlew :app:bootJar`，以 `JABIZ_JWT_SECRET`、`JABIZ_INTEGRITY_KEY`、`JABIZ_MFA_KEY`、`JABIZ_DEMO_AI_KEY`（示范外部服务的密钥，任意值）、`JABIZ_SECURITY_MFA_ADMINISTRATION=false`（共用的管理员没有二次验证）、`JABIZ_ADMIN_ROLES=*` 与 `JABIZ_PORTAL_SELF_REGISTRATION=false`（测试以自己的角色登录后台，示范门户因此不开放注册）、`JABIZ_PORTAL_REQUIRE_VERIFIED_EMAIL=false`（不发邮件，门户的客户无法验证邮箱，仍可登录）、`JABIZ_SECURITY_SIGN_IN_RATE_LIMIT_PER_USER_ADDRESS=6000` 与 `JABIZ_SECURITY_SIGN_IN_RATE_LIMIT_USER_BURST=1000`（测试从同一地址频繁登录，与 CI 相同）、`JABIZ_SECURITY_SIGN_IN_RATE_LIMIT_PER_USER_ADDRESS=6000` 与 `JABIZ_SECURITY_SIGN_IN_RATE_LIMIT_USER_BURST=1000`（共用的管理员从本机登录数十次，D47 的"地址 + 名字"限流放宽）、`JABIZ_BOOTSTRAP_ADMIN_USER/PASSWORD`、`JABIZ_FILES_LOCAL_ROOT` 与数据库环境变量
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
