# QuizBuks — 生产运维手册（Q10d）

`deploy/quizbuks/README.md` 讲镜像、compose 与各阶段部署后要做的事；本手册是生产环境的操作手册：密钥清单、数据库与备份、首次启动、切换到正式模式、监控、容量与常见故障。
上线前逐条核对 `launch-checklist.md`。计划：`plans/Q10-launch.md` §6。

## 1. 环境变量与密钥

放在部署平台的密钥库（或服务器上不提交的 `deploy/quizbuks/.env`，权限 600）。密钥类的值都用 `openssl rand -base64 48` 生成（Base64、≥ 32 字节）。
"缺少时"一栏写的是**没设置**时发生什么；"轮换"写的是换值的代价。

### 1.1 平台与数据库

| 变量 | 必需 | 来源 | 轮换 | 缺少时 |
|---|---|---|---|---|
| `JABIZ_JWT_SECRET` | 是 | 生成，只放密钥库 | 随时可换：已签发的访问令牌（15 分钟）失效、所有人重新登录（刷新令牌不受影响，平台 10 §15.9）。泄露时立即换 | 启动失败 |
| `JABIZ_INTEGRITY_KEY` | 是 | 生成；**另存离线副本**（与数据库备份分开保管） | 不能随便换：已有的封存要用原密钥核验（平台 21 §2）。只在泄露时按平台的密钥轮换步骤换，并保留旧密钥 | 启动失败；丢失 = 已有封存无法核验 |
| `JABIZ_MFA_KEY` | 是 | 生成；**另存离线副本** | 不能随便换：已绑定的 TOTP 密钥以它加密，换了全部用户要重新绑定（`SEC_MFA_RESET`） | 启动失败；丢失 = 所有人的二次验证失效 |
| `JABIZ_BOOTSTRAP_ADMIN_USER` / `_PASSWORD` | 只首次启动 | 运维 | 首个管理员建好后从环境中**删去**（它只在库中没有用户时起作用） | 没有用户时无人能登录 |
| `JABIZ_FILES_LOCAL_ROOT` | 是 | 镜像已设 `/var/lib/jabiz/files`（卷） | — | 启动失败；卷丢失 = 上传的图片、PDF 丢失（备份见 §2.3） |
| `SPRING_R2DBC_URL` / `_USERNAME`、`SPRING_FLYWAY_URL` / `_USER`、`DB_PASSWORD`（或 `SPRING_R2DBC_PASSWORD` / `SPRING_FLYWAY_PASSWORD`） | 是 | 数据库 | 换密码：先在库中加新密码可用的窗口（`ALTER ROLE`），改环境变量、滚动重启 | 连不上库，启动失败 |
| `JABIZ_TRUSTED_PROXIES` | 在代理之后必需 | 代理的地址或网段 | 代理变化时 | 登录记录的 IP 都是代理的；入站 Webhook 按地址的限流把 Stripe 全部投递算成一个客户端（每分钟 120 次） |
| `JABIZ_REPORTS_PDF_FONTS` | 镜像已设 | 镜像中的 IPA Gothic、Droid Sans Fallback | — | 镜像外运行时收据与月报的中日文为 `?`；设了读不到的文件即启动失败 |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | 建议 `ecs` | — | — | 文本日志 |
| `JABIZ_STORAGE_WRITE_BATCHING_ENABLED` | **不设** | — | — | 缺省 `true`：平台在事务中缓冲写入（决策 D46，16p-2）。应急开关：只在怀疑写缓冲有缺陷时设 `false` 并重启，行为与保证不变，热门发布的容量约降 30 %（`perf/Q10a-load-test.md` §11） |
| `QB_ADMIN_ROLES` | 否 | 缺省 `ADMIN,QB_ADMIN_SUPER,QB_ADMIN_CONTENT,QB_ADMIN_FINANCE` | — | 用缺省。**永远不要**加 `*`、`QB_TAKER`、`QB_SPONSOR`（自己注册的人会进入管理后台）。启动检查只在相应入口开放注册时拒绝（`SignInEntries`）：`*` 在 App 或商家任一入口开放注册时、`QB_TAKER` 在 App 开放注册时（`QB_APP_SELF_REGISTRATION`）、`QB_SPONSOR` 在商家开放注册时（`QB_SPONSOR_SELF_REGISTRATION`）启动失败；关掉注册后不再拦，但仍不要这样配置。也不得含测试的 `QB_E2E_*`（正式模式下启动失败） |
| `QB_APP_SELF_REGISTRATION` / `QB_SPONSOR_SELF_REGISTRATION` | 否 | 缺省 `true` | — | — |
| `JABIZ_PUBLIC_ENABLED` | 否 | 缺省 `true`（Q7b） | — | 设 `false` 时 `/api/public/**` 一律 404：帮助中心与条款、隐私政策页（App 与商家后台，也给未登录的人看）都打不开。只有四个公开模板 `qb.public.*`（已发布的法律文本版本与已发布的 FAQ），没有公开的文件；按客户端地址限流（平台缺省每分钟 300 次），结果缓存 5 分钟 |

### 1.2 邮件与登录

| 变量 | 必需 | 来源 | 轮换 | 缺少时 |
|---|---|---|---|---|
| `JABIZ_MAIL_ENABLED` | 是（`true`） | — | — | 邮件只记为 `SKIPPED`：注册的人永远不能验证邮箱、找回密码不可用（启动告警） |
| `JABIZ_MAIL_FROM` | 是 | 发件域（配好 SPF / DKIM / DMARC） | — | 邮件发不出 |
| `JABIZ_MAIL_BASE_URL` | 是 | 主机根地址 `https://<主机>`（不带 `/admin`） | 换域名时 | 邮件中的链接错误；也是商家后台地址的缺省来源 |
| `SPRING_MAIL_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | 是 | 邮件服务商（SMTP） | 按服务商；改环境变量、重启 | 投递失败 |
| `SPRING_PROFILES_ACTIVE` | 否 | `google` 开启 App 的 Google 登录。**绝不能含 `dev`**（`dev` 的签名密钥与操作人请求头是公开的） | — | 无 Google 登录 |
| `JABIZ_OIDC_GOOGLE_CLIENT_ID` / `_CLIENT_SECRET` | 开 Google 时 | Google Cloud 控制台 | 在控制台建新密钥、改环境变量、重启、删旧密钥 | Google 登录启动失败 |
| `QB_OIDC_REDIRECT_APP` | 开 Google 时 | `https://<主机>/login/oidc`（与控制台登记的完全相同） | 换域名时 | Google 拒绝回调 |
| `JABIZ_SECURITY_MFA_ADMINISTRATION` | **不设** | — | — | 缺省 `true`：管理操作要求二次验证。`false` 只给本机演示、e2e 与 Stripe 测试模式的本机运行 |

**应用的邮件**（Q7a，`plans/Q7-messages-legal.md` D-Q7-1）：除注册、验证、找回密码与入驻结果外，QuizBuks 在以下事件后发信（都由事件的消费者以系统身份发出，不在答题与支付的提交路径上）：

| 收件人 | 邮件（模板） | 可退订 |
|---|---|---|
| 答题人 | 转账已发出 `qb.transfer.sent`、转账失败 `qb.transfer.failed`（只在转账仍是该状态时发）、收款账户需补资料 `qb.payout.action-needed`（每人 24 小时内至多一封） | 否 |
| 商家 | 充值到账 `qb.topup.paid`、发布通过 / 驳回 / 被下架 `qb.publication.approved` / `.rejected` / `.taken-down` | 否 |
| 商家 | 发布预算不足 `qb.publication.funds-short`（每个商家 24 小时内至多一封，列出当时全部预算不足的发布；窗口内的再次不足记为待发，由每小时 11 分的任务 `qb.mail-funds-short-due` 在窗口过后仍不足时补发）、发布已结束 `qb.publication.ended`（下架后或商家被封禁时不发） | 是（邮件中的链接，或商家后台"账户 → 邮件"） |

邮件中的时刻按日本时间、标为 `JST`（取自 `jabiz.fiscal-year-zone`）。`JABIZ_MAIL_ENABLED` 未开时这些邮件同样只记为 `SKIPPED`（`sys_mail_message` / `sys_mail_attempt`），不会补发。事件太晚才被消费（投递积压、或新消费者收到历史事件）时不发信：通知（可退订的两种）超过 3 天、事务邮件（转账、收款账户、充值、发布的审核结果与下架）超过 14 天（`NotificationMails.MAX_AGE` / `TRANSACTIONAL_MAX_AGE`）。补发任务的时间可以 `quizbuks.mail.funds-short-due-cron`（Spring 的六段 cron，缺省 `0 11 * * * *`，UTC）改；它只读待发的行（`qb_mail_notice` 的部分索引）。

**退订链接**：可退订邮件中的退订链接由平台生成，指向后台入口的页面 `/admin/mail/unsubscribe`（平台 D49：退订页只在通用后台中）。所以即使只给答题人与商家开放 App 与商家后台，反向代理也必须让 `/admin/` 下的这个路径可达（无需登录：链接中的令牌即凭证）；若要完全不对外开放 `/admin/`，需先由平台的后续阶段提供与入口无关的退订页。

### 1.3 Stripe 与资金（`deploy/quizbuks/README.md`"充值与核对""收款账户与自动转账"）

| 变量 | 必需 | 来源 | 轮换 | 缺少时 |
|---|---|---|---|---|
| `STRIPE_API_KEY` | 是 | Stripe 后台 → 开发者 → API 密钥：**受限密钥**（`rk_live_…`），只开 Checkout Sessions 写、PaymentIntents 读、Transfers 读写、Accounts 与 Account Links 写 | 在后台"滚动"受限密钥（可设旧密钥的过期时间），改环境变量、重启，再让旧密钥过期。泄露时立即滚动 | 启动失败（模式不符也失败，`StripeModeCheck`） |
| `STRIPE_WEBHOOK_SECRET` | 是 | 账户端点 `/api/inbound/stripe` 的签名密钥 | 后台滚动：新旧同时有效一段时间，期间配 `JABIZ_INBOUNDWEBHOOKS_PROVIDERS_STRIPE_SECRETS_0`（新）与 `_1`（旧），过期后删旧的 | 启动失败 |
| `STRIPE_CONNECT_WEBHOOK_SECRET` | 是 | Connect 端点 `/api/inbound/stripe-connect` 的签名密钥 | 同上，但提供方的名字带连字符（`stripe-connect`），环境变量写不出这个键：滚动期间以 `SPRING_APPLICATION_JSON='{"jabiz":{"inbound-webhooks":{"providers":{"stripe-connect":{"secrets":["<新>","<旧>"]}}}}}'` 配两个（同样只放在密钥库），过期后删去 | 启动失败 |
| `QB_STRIPE_LIVEMODE` | 正式时 `true` | — | — | 测试模式 |
| `QB_STRIPE_API_VERSION` | 否 | 缺省 `2025-03-31.basil`，**与两个端点的 API 版本相同** | 升级版本是代码改动（字段可能变化），不是运维操作 | — |
| `QB_SPONSOR_LINK` | 否（建议设） | `https://<主机>/sponsor/`；不设时取 `JABIZ_MAIL_BASE_URL` 的 `/sponsor/`（§1.2） | 换域名时 | 用 `JABIZ_MAIL_BASE_URL` + `/sponsor/`；两者都没有时充值一律被拒（启动告警） |
| `QB_APP_LINK` | 是 | `https://<主机>/`（没有缺省来源） | 换域名时 | 开通收款账户一律被拒（启动告警） |
| `QB_STRIPE_CONNECT_COUNTRY` | 否 | 缺省 `JP` | — | — |
| `QB_STRIPE_SETTLEMENT_CURRENCY` | 否 | 缺省 `jpy`，与 Stripe 账户的结算币种相同 | — | — |
| `QB_STRIPE_CHECKOUT_TTL` | 否 | 缺省 `PT1H` | — | — |
| `QB_STRIPE_PLACEHOLDERS` | 服务器上 `false` | compose 缺省 `true`（只在测试模式填占位） | — | 用本仓库 compose 而没设 `false` 时，测试模式缺的密钥被填成占位（告警）而不是启动失败 |
| `QB_STRIPE_ALLOW_LIVE_WITH_TEST_DATA` | **不设** | — | — | 缺省 `false`：库中有测试数据时正式模式启动失败（§4） |
| `OPENAI_API_KEY` | Q8 | OpenAI | — | Q8 尚未实施，当前不读取 |

### 1.4 只给测试的开关（生产不设）

正式模式（或正式密钥）下设了即**启动失败**的：

| 开关 | 用途 | 由谁拒绝 |
|---|---|---|
| `QB_TRANSFER_RUN_CRON`、`QB_RECONCILE_RUN_CRON` | e2e 与 Stripe 测试模式：每分钟转账、每分钟核对 | `TransferScheduleCheck` |
| `QB_ADMIN_ROLES` 中的 `QB_E2E_*` | e2e 的无二次验证的测试角色 | `TransferScheduleCheck` |
| 开发入账 `quizbuks.wallet.dev-credit.enabled`（`dev` 配置开启） | 不付款给商家钱包入账 | `StripeModeCheck` |
| `QB_STRIPE_CHECKOUT_URL_PREFIX` / `QB_STRIPE_CONNECT_URL_PREFIX` 不是 Stripe 的地址 | 指向本地桩 | `StripeSettings` / `PayoutSettings`（启动检查） |

不会被拒、但生产**绝不能**设的：`JABIZ_SECURITY_MFA_ADMINISTRATION=false`、`JABIZ_SECURITY_SIGN_UP_RATE_LIMIT_PER_ADDRESS`（e2e 放宽的注册限流）、`JABIZ_SECURITY_SIGN_IN_RATE_LIMIT_PER_USER_ADDRESS` / `JABIZ_SECURITY_SIGN_IN_RATE_LIMIT_USER_BURST`（e2e 放宽的登录限流）、`QB_STRIPE_API_URL` / `QB_STRIPE_API_HOSTS`（指向桩）、`SPRING_PROFILES_ACTIVE` 含 `dev`、`QB_STM_PUBLISHER` / `QB_DEMO_PUBLISHER` 等演示与测试的角色。上线检查清单逐项核对。

### 1.5 可观测性

| 变量 | 说明 |
|---|---|
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | 追踪（如 `http://otel-collector:4318/v1/traces`） |
| `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true`、`MANAGEMENT_OTLP_METRICS_EXPORT_URL` | 指标（`…/v1/metrics`） |
| `MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT` | 日志 |

遥测缺省不外发、只经 OTLP 推送；**不开放**匿名的指标端点（应用端口上的 `/actuator/metrics` 不要暴露，平台 13）。

## 2. 数据库

### 2.1 规格

- PostgreSQL 16，与应用分开的主机（或托管服务），SSD。起步建议 4 vCPU / 16 GB / 100 GB，按增长调整：每次答题约写会话一行、分录一笔（3 行）、审计若干行；只追加表（审计、分录、时态版本）不删。
- 应用的连接池缺省 10（`spring.r2dbc.pool.max-size`）、准入 `max-active` 为其一半；`max_connections` ≥ 实例数 × 池 + 迁移、备份与运维的余量（如 2 实例 → 至少 40）。
- 压测的结论（`perf/Q10a-load-test.md` §7 第 3 条）：每条语句约 0.8 ms 的往返决定热门发布的容量，**数据库与应用之间的网络往返越短越好**（同一可用区）；独立部署时每条语句多 0.1–0.3 ms，容量约降 10–20 %。
- 参数起点：`shared_buffers` 约内存的 25 %、`effective_cache_size` 约 70 %、`work_mem` 16–32 MB、`random_page_cost 1.1`、`track_io_timing on`；`idle_in_transaction_session_timeout` 不要短于分录导出允许的事务（`quizbuks.journal.max-open-transaction`）。

### 2.2 `pg_stat_statements`

```sql
-- postgresql.conf：shared_preload_libraries = 'pg_stat_statements'（需要重启），pg_stat_statements.track = top
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;   -- 在 quizbuks 库中
SELECT calls, round(mean_exec_time::numeric, 2) AS ms, rows, left(query, 120)
FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 20;
```

用来看最耗时的语句与新版本上线后的变化；压测工具 `loadTest` 可选地读取它（只重置应用库）。每次大版本上线后看一次，与上线前比较。

### 2.3 备份与时间点恢复

- **连续归档 + 定期基础备份**：WAL 归档（`archive_mode on`，用 pgBackRest、WAL-G，或托管服务的时间点恢复），基础备份每天一次，保留至少 14 天的时间点恢复窗口；另按会计要求保留月末的全量备份（凭证与分录是 10 年，L-4）。
- **一起备份的**：上传文件卷（`/var/lib/jabiz/files`：与库同一时刻的快照或每日同步）、`JABIZ_INTEGRITY_KEY` 与 `JABIZ_MFA_KEY` 的离线副本（**不与备份放在一起**：拿到备份的人不应同时拿到密钥）。生成的文件（导出 CSV、收据 PDF）在库中（`sys_generated_file`、`sys_document_run`），随库备份。
- 备份加密、异地（另一区域或账户）、只追加或带对象锁，防误删与勒索。
- 监控：最近一次成功的基础备份与 WAL 归档的延迟（§5）。

### 2.4 恢复演练（每季度，上线前一次）

在**隔离的环境**中做（不能连 Stripe 正式环境、不能发邮件，否则恢复出的实例会转账、发信）：

1. 选一个时刻 T（如昨天 12:00 UTC），把备份恢复到新的 PostgreSQL 实例（时间点恢复到 T），记录用时（RTO）与 T 到最后一条 WAL 的差（RPO）。
2. 以同一版本的镜像启动应用，指向恢复的库，并且：`JABIZ_INTEGRITY_KEY`、`JABIZ_MFA_KEY` 用生产的值（从离线副本取）；`QB_STRIPE_LIVEMODE=false`、`STRIPE_API_KEY=sk_test_restore_drill`、两个签名密钥随机生成；
   `JABIZ_MAIL_ENABLED=false`；关掉后台工作：`JABIZ_JOBS_SCHEDULER_ENABLED=false`、`JABIZ_EVENTS_DELIVERY_ENABLED=false`、`JABIZ_EXTERNALJOBS_EXECUTOR_ENABLED=false`；网络上禁止对外连接。
   （正式数据在测试模式下启动不被拒绝；反过来"正式模式 + 测试数据"才被拒。）
3. 核对：启动检查通过（没有 `Platform check failed`）；管理员能登录并通过二次验证（`JABIZ_MFA_KEY` 正确）；运行 `INTEGRITY_VERIFY`（封存链完整，`JABIZ_INTEGRITY_KEY` 正确）；
   报表 `qb.admin.balances`（T 所在月）借贷平衡、各方钱包不透支，2110 合计与 `qb.admin.wallets` 的商家余额合计相等；抽查几张收据 PDF 与上传的图片能打开（文件卷与库一致）。
4. 与生产在 T 时的数字比较（T 之前已完成的每日核对 `qb.admin.recon-runs`、月报）。
5. 记录在演练记录中（日期、T、RTO、RPO、问题），删除隔离环境。

## 3. 首次启动

1. 准备 §1 的全部变量（`QB_STRIPE_PLACEHOLDERS=false`）、HTTPS 的反向代理（`JABIZ_TRUSTED_PROXIES`）、邮件。先以**测试模式**启动（§4 之前），确认 `docker compose … logs app` 中没有 `Platform check failed`。
2. 以 `JABIZ_BOOTSTRAP_ADMIN_USER` 登录 `/admin/`，在"安全设置"中**开启两步验证**（TOTP），保存恢复码。随后从环境中删去 `JABIZ_BOOTSTRAP_ADMIN_*`。
3. 执行 `QB_SETUP`（流程页面；要求二次验证）：建角色、账本科目、国家、业务参数，并把受控参数与审批规则作为**提案**提出。
4. 首个管理员建**财务管理员**（`QB_ADMIN_FINANCE`，持有 `control.publish`）与**内容管理员**（`QB_ADMIN_CONTENT`）：`SEC_USER_CREATE` 并以 `SEC_USER_ROLE_ASSIGN` 分配角色；本人登录、开启两步验证。
   给管理员角色设"要求二次验证"（`SecRole.requireMfa`），使未绑定的管理员无法登录。各自一人一号、各自二次验证；不共用账号。
   这两种角色不持有改权限的权限（`security.user-role.write` / `security.role.write`），平台不把它们算作管理员（D51），建立期内的分配当即生效。
5. 财务管理员在后台**发布** `QB_SETUP` 的提案（`CONTROL_CHANGE_PUBLISH`，四眼：提出人不能发布自己的），**包括两条职责分离规则**。发布之前商家入驻与提交发布一律被拒。
   职责分离规则须在建立期结束（下一步）之前发布：之后任命内容或财务管理员要由一位超级管理员审批，而超级管理员不持有、也不能持有 `approval.decide` 与 `control.publish`，
   平台只按**已发布的** `qb.sod.security-administration` 免去对这两项的覆盖要求（D51 修订 H1）。
   然后建**两位超级管理员**（`QB_ADMIN_SUPER`，各自一人一号、各自二次验证）：首个管理员建第一位（此后首个管理员退役，不能再做受保护的变更，403 `BOOTSTRAP_ADMIN_RETIRED`），
   **第一位超级管理员建第二位**（仍在建立期，当即生效）。第二位出现后建立期结束（平台 16u，D51），此后的受保护变更（见本节末"受保护的授权"）由一位超级管理员申请、另一位审批。
   `QB_SETUP` 必须在第二位超级管理员出现**之前**执行过（第 3 步）：超级管理员的角色由它得到审批受保护变更的 `security.protected-change.decide`，这项权限本身受保护，建立期结束后再给需要审批，而那时无人能批。
6. 由超级管理员提出、财务管理员发布（`CONTROL_CHANGE_PROPOSE` → `CONTROL_CHANGE_PUBLISH`，目标 `SysParam`）：
   - **收据开具方** `qb.issuer.name`、`qb.issuer.address`、`qb.issuer.registration-no`（登记号与税的措辞须财务、法务确认，上线检查清单）；未设名称时商家开不出收据；
   - **科目映射** `qb.journal.account.1110 … 5110`（会计系统中的科目代码；后台"QuizBuks 财务 → 分录导出 → 科目映射"只读显示，修改走受控变更）；没映射的科目使分录导出被拒；
   - 金额参数按业务确认：`qb.creator-fee-rate`、`qb.transfer.threshold`、`qb.transfer.review-above`、`qb.transfer.fee`、`qb.topup.min` / `max`；
   - `qb.transfer.enabled` **保持 false**，直到取得法务意见（§4）；
   - 法律文本的最长预告期 `qb.legal.max-announce-days`（缺省 180 天；未发布时同样按 180 天）。
7. 在测试模式下做一次 Stripe 测试模式全流程（`stripe-test-mode.md`），并按 §2.4 做一次恢复演练。
8. 每次部署新版本后：再执行一次 `QB_SETUP`，发布它的新提案（README 各阶段的"部署后"）。
9. **条款、隐私政策与帮助**（Q7b）：`QB_SETUP` 为四种法律文本（答题人条款、商家条款、隐私政策、特定商取引法表记）各建一份**明确标注 `[TEST PLACEHOLDER]` 的草稿**，什么也不发布。
   正式文本由需求方或律师提供（日文为准据文本，英文须一并提供；中文可缺，页面显示英文并注明）。内容管理员在后台的"法律文本（草稿）"中把草稿换成正式文本（标题、正文，第二版起还有"改定的概要"，日英必填），
   再在后台扩展的"QuizBuks → 规约类"页（`/qb/legal`）预览草稿并"发布"（`QB_LEGAL_PUBLISH`，每次要求二次验证，留审计；发布的是页面读到的草稿版本，期间被改即拒绝）：生效时间留空即立即生效，或填以后的时间作预告（至多 `qb.legal.max-announce-days` 天；预告期间不能再发布同一种文本）；
   预告有误时在同一页的版本列表中填理由"撤回"（`QB_LEGAL_WITHDRAW`，只能在生效前；撤回的版本永不生效、不要求同意，版本号不再使用，公开的历史中标为"已撤回"）；
   实质性变更勾选"须重新同意"，措辞修订不勾。已发布的版本不可修改，改错只能发布新版本。正式模式下缺少已生效的答题人条款、商家条款或隐私政策时**启动告警**（`LiveModeDataCheck`）。
   帮助中心的问答在后台"帮助中心问答"中维护（英语必填、`seq` 排序、"公开中"才出现在页面）。支持邮箱 `qb.support.email`（普通参数，空即不显示"仍需帮助"的联系方式）在后台参数中设。

**受保护的授权（平台 16r / 16u，决策 D48、D51；10 §16）**：分配或增加 `security.user-role.write`、`security.role.write`、`security.protected-change.decide`、`approval.decide`、`control.publish`、`ledger.*`、
`platform.param.write`、`*`，以及对持有它们的用户重设密码、二次验证、邮箱或关联外部账号，平台要求**另一位管理员审批**——但只在建立期结束之后。
平台按管理员的权限（`security.user-role.write`、`security.role.write`，`administrator-permissions` 的缺省值）数管理员：QuizBuks 的管理员就是 `QB_ADMIN_SUPER` 的持有人。
首个管理员之外出现一位，首个管理员即退役；出现**两位**，建立期即结束（永不回退）。所以 QuizBuks 需要**至少两位超级管理员**，按上面第 4–5 步的顺序建立，并始终保持两位在职
（只剩一位时没有人能批准第二位；出路同平台 10 §16.3：运维以 `JABIZ_SECURITY_PROTECTEDGRANTS_ENABLED=false` 启动一次、建好后改回，记录在变更记录中）。
- **审批**：待审批的变更出现在后台"待办"中（平台的 `ApprovalPanel`，`APPROVAL_DECIDE`），由另一位超级管理员以 `security.protected-change.decide` 决定（要求近期二次验证；申请人与所针对的用户不能批；
  `approval.read` 让超级管理员读到请求的事实）。超级管理员不持有 `approval.decide`，不能决定业务审批（入驻、发布、大额转账），这些仍由内容与财务管理员决定。
- **任命内容与财务管理员**：给出的 `approval.decide`、`control.publish` 不要求审批人持有，因为已发布的职责分离规则 `qb.sod.security-administration` 禁止超级管理员持有它们（D51 修订 H1）；
  该规则被撤下或停用时这类任命无法批准。
- **以后的 `QB_SETUP`**：它直接写入角色的权限。建立期结束后，若新版本给已有持有人的角色增加受保护的权限，平台以 422 `PROTECTED_CHANGE_REQUIRES_APPROVAL` 拒绝整个 `QB_SETUP`；
  这时由一位超级管理员以 `SEC_ROLE_PERMISSION_ADD` 申请、另一位审批，之后再执行 `QB_SETUP`（它把已存在的授权只记下，不再写）。迄今各阶段新增的权限中只有 16u 的 `security.protected-change.decide` 受保护。
- **已有的安装**（16u 之前建立、已有两位以上超级管理员；16r 按 `*` 计管理员，它们一直处于建立期）：升级之后建立期立刻结束，`QB_SETUP` 给超级管理员的角色加 `security.protected-change.decide` 需要审批而无人能批（整个 `QB_SETUP` 被拒）。
  所以**升级前**，在旧版本上由超级管理员给角色 `QB_ADMIN_SUPER` 直接加上 `security.protected-change.decide` 与 `approval.read`（`SEC_ROLE_PERMISSION_ADD`：那时仍在建立期、它还只是普通的权限码，当即生效），
  升级后再执行 `QB_SETUP`（它只记下已有的授权）。来不及时，以 `JABIZ_SECURITY_PROTECTEDGRANTS_ENABLED=false` 启动一次、执行 `QB_SETUP`、再改回（关闭的时间留在操作记录中）。
  检测性控制照旧：季度访问审查 `ACCESS_REVIEW_SIGN_OFF`、审计中逐条复核角色变更（Q10b M-c）。

**会计年度的时区**：保留期自会计年度末起算，日期按日本时间判断（`application.yml` 的 `jabiz.fiscal-year-zone: Asia/Tokyo`，平台 D48 第 2 条；Q10b 评审 L7），不需另设。
1 月 1 日 00:00–08:59（日本时间）记录的数据因此属于新的会计年度。

## 4. 切换到正式模式

应用在启动与 `platformCheck` 中检查（类别 `QUIZBUKS`），任何一条不成立即**启动失败**：

| 检查 | 规则 |
|---|---|
| `StripeModeCheck` | `QB_STRIPE_LIVEMODE=true` 须正式密钥（`sk_live_` / `rk_live_`），测试模式须测试密钥；开发入账（`dev`）不能与正式模式或正式密钥同时开启；支付页与开通页的地址前缀在正式模式只能是 Stripe 自己的；Stripe 设置本身（API 版本格式、Checkout 有效期、结算币种）有效；缺商家后台或 App 地址只告警（充值、开通被拒） |
| `TransferScheduleCheck` | 正式模式或正式密钥下，每日转账与每日核对必须是缺省的每日时间（不能设 `QB_TRANSFER_RUN_CRON`、`QB_RECONCILE_RUN_CRON`），后台入口不能接受 `QB_E2E_*` 角色 |
| `LiveModeDataCheck`（`quizbuks.stripe.allow-live-with-test-data`） | 正式模式下库中有测试模式的充值（`livemode=false` 或 `cs_test_` 会话），或 2110（商家钱包合计）不为 0，即失败：测试的 Kudos 不能在正式环境中付出真实奖励 |
| 入站 Webhook | 两个签名密钥都必须有 |
| `LiveModeDataCheck`（Q7b，告警） | 正式模式下没有已生效的答题人条款、商家条款或隐私政策即告警（不能报错：要先启动才能发布）；上线前必须消除 |

**检查清单**（按顺序）：

- [ ] 法务意见已取得（资金決済法：Q6b 的转账），收据措辞、隐私政策与条款已确认（`launch-checklist.md`）
- [ ] Stripe 正式账户（日本）已通过审核，Connect 平台资料与 Express 设置完成，打款银行账户与打款计划已设
- [ ] **新的数据库**（不沿用测试库；确需沿用时先核清测试数据，见 README"正式上线"）
- [ ] 正式的受限密钥（§1.3 的权限）放进 `STRIPE_API_KEY`；正式模式的两个 Webhook 端点（事件同测试，API 版本 `2025-03-31.basil`），两个签名密钥放进环境
- [ ] `QB_STRIPE_LIVEMODE=true`；§1.4 的测试开关一个都没有；`JABIZ_SECURITY_MFA_ADMINISTRATION` 未设；`SPRING_PROFILES_ACTIVE` 不含 `dev`
- [ ] `QB_SPONSOR_LINK`、`QB_APP_LINK`、`JABIZ_MAIL_BASE_URL` 都是正式的 `https` 地址；正式域名写进 `tools/quizbuks/stripe-test-mode/production-hosts`
- [ ] `./gradlew :quizbuks:platformCheck` 以正式的设置（不含密钥值的同形假值）跑一遍；启动日志中没有 `Platform check failed`
- [ ] 首次启动（§3）完成：两位管理员都有二次验证，提案已发布，开具方与科目映射已设
- [ ] 小额实测：一位内部商家用真实卡充值最小额（`qb.topup.min`），确认到账、手续费分录、收据；在 Stripe 后台全额退款，确认扣回；次日核对为 `OK`
- [ ] 资金调度：充值在 Stripe 上几天后才变为可用余额，转账从可用余额转出；定好打开转账前的余额与打款计划
- [ ] 法务意见允许后，才提出并发布 `qb.transfer.enabled=true`；第一次每日转账（01:07 UTC）后核对每一笔

## 5. 监控与告警

按 §1.5 开启 OTLP（追踪、指标、日志）到你的观测后端（如 Grafana LGTM）。平台的观测名与标签见平台设计 13（标签只有名字与结果，没有主键与操作人）。
指标在 OTLP 中的名字随后端而定（计时器通常带 `_seconds` / `_milliseconds` 后缀与 `_count`、`_sum`、`_bucket`）。建议的告警：

| 关注点 | 指标 / 来源 | 建议告警 |
|---|---|---|
| 健康 | `/actuator/health`（只在内网探测） | 连续 3 次失败 |
| 答题过载（准入） | `jabiz.process.admission{family="qb.pub"|"qb.pub-start", result}`；仪表 `jabiz.process.admission.waiting` / `.active` | `result` 为 `QUEUE_FULL` 或 `WAIT_TIMEOUT` 的比率 5 分钟内 > 1 %；`waiting` 持续 > 25（队列 50 的一半）；`active` 持续等于 `max-active` |
| 提交被拒（已知的上线风险，§7.6） | `jabiz.process{process="QB_ATTEMPT_SUBMIT", status="503"}` 与 `QB_ATTEMPT_EXPIRE` 的数量 | 提交的 503 比率 > 1 %；超时结束的会话数突然上升 |
| 每次运行的语句数 | `jabiz.process.statements{process}`（分布） | 新版本后 `QB_ATTEMPT_START` / `QB_ATTEMPT_SUBMIT` 的平均超过语句预算（`AttemptStatementBudgetIT`：开始共 10、付奖的提交共 13，指标不含 `COMMIT`，即 9 与 12；写缓冲的应急开关关时为 11 与 19）的 1.2 倍——锁内语句变多会直接降低热门发布的容量 |
| 流程错误 | `jabiz.process{outcome="error", status!="503"}` | 任何流程 5 分钟内 > 0（错误不是业务拒绝）。`outcome="error"` 包括全部 5xx（`PlatformObservations`），其中 503 是过载而不是缺陷，这里除去：排队未获准入（`PROCESS_BUSY`）由"答题过载""提交被拒"两行告警；查询超时（`QUERY_TIMEOUT`）另以 `jabiz.process{status="503"}` 的比率告警（如 5 分钟内 > 1 %） |
| 核对差异 | 每日核对 `qb.reconcile`：`jabiz.job.run{job="qb.reconcile"}` 的结果；差异本身在 `qb.admin.recon-runs`（`DIFF`）与待办（权限 `qb.reconcile`） | 任务 `FAILED`；核对状态 `DIFF`（用只读账号查询 `qb_recon_run_version` 最新一行的状态，或后台待办的数量）；连续 26 小时没有新的核对 |
| 转账失败 | `jabiz.external.job{job_type="qb.stripe.transfer"|"qb.stripe.transfer-check", outcome}`；`jabiz.job.item{job="qb.transfer-run"|"qb.transfer-stale", result}` | `outcome` 为 `FAILED` / `DISCARDED` > 0；`qb.transfer-stale` 的项连续失败；处于 `CHECK` 的转账超过 2 小时（后台财务页"转账"按状态筛选） |
| Webhook 被拒 | `jabiz.inbound.receive{provider="stripe"|"stripe-connect", result, reason}`；`jabiz.inbound.rate_limited` | `REJECTED`（`no_match` 签名不符、`too_old`、`malformed` 等）5 分钟内 > 0；`FAILED` > 0；限流 > 0。Stripe 后台的端点"失败的投递"同时会发邮件 |
| 事件消费 | `jabiz.outbox.delivery{result="FAILED"}` | > 0 持续 15 分钟 |
| 分录导出被拒 | `jabiz.process{process="QB_JOURNAL_EXPORT", outcome="rejected"}` | 每月导出日 > 0（看错误码，§7.3） |
| 外部调用 | `jabiz.external.call{service="stripe", outcome}` | `5xx`、`timeout`、`refused` 比率 > 5 % |
| 任务 | `jabiz.job.run{result="FAILED"|"LOCKED"}` | 任一每日任务 `FAILED`；`LOCKED` 持续（另一实例卡住） |
| 邮件 | `jabiz.job.item{job="qb.mail-funds-short-due", result}`；`jabiz.outbox.delivery{result="FAILED"}` | 补发任务的项连续失败；`sys_mail_message` 中 `FAILED` 增多（看发件服务商） |
| 数据库 | 连接数、复制与 WAL 归档延迟、最近一次基础备份、磁盘、`pg_stat_statements` 的前几名、死锁（`pg_stat_database.deadlocks`） | 归档延迟 > 15 分钟；备份超过 26 小时；磁盘 > 80 %；死锁 > 0 |
| 主机 | CPU、内存、JVM 堆 | 应用或数据库的 CPU 在业务高峰持续 > 70 %（§6） |

日志：结构化 JSON（`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`）；`quizbuks: WARNING`、`Platform check failed`、`Inbound webhook request of … rejected` 作为日志告警。日志与追踪中不应出现密钥、令牌与原始卡信息（平台已遮蔽；发现即视为缺陷）。

## 6. 容量

来自 `perf/Q10a-load-test.md`（本机 4 vCPU、应用与数据库同机，绝对值偏低，适合前后比较）：平台 16p-2 之后单个热门发布约 **45–54 次完成 / 秒**（§11，本机安静时），每秒 50 次在本机达到（提交 p95 158–1048 ms），但**没有 1.3 倍的余量**：上线前必须在目标规格上重测。

- **2 ÷ 许可持有**：每个发布每族 2 个许可，热门发布的容量 ≈ 2 ÷ 每次完成的许可持有时间（`jabiz.process` 减去 `jabiz.process.admission` 的排队，饱和时测得），应高于预期峰值的约 **1.3 倍**；或直接用锁内时间：容量 ≈ 1 ÷ 提交在发布锁内的时间（报告 §9.4、§10.3）。
- **`max-active` ≥ 同时热门的发布数 × 4 + 1**：一个热门发布至多占 4 个许可（开始 2 + 完成 2），否则其他发布的答题在队列里等许可；提高 `max-active`（`jabiz.process.admission.max-active`）时把连接池设为它的两倍（读取另需一半连接）。
- **CPU < 60–70 %**：在预期读取量下，应用与数据库的 CPU 保持在约 60–70 % 以下；88 % 时许可持有翻倍、吞吐减半（§8.3 第 4 条）。
- **上线前在目标硬件上重测**：压测程序放在另一台主机，读者按预期的读取量限速（`LOAD_READ_RATE`），对打包的 jar 运行 `./gradlew :quizbuks:loadTest`（`LOAD_*` 参数，`LOAD_METRICS=yes` 从回环的管理端口读许可持有与语句数）；确认峰值低于饱和点并有 1.3 倍余量，结果写进报告。
- 单实例：准入的队列与限流按实例（D45 的已知限制）；多实例时热门发布的锁仍在数据库中串行，加实例不提高单个发布的容量。

## 7. 常见故障

### 7.1 Webhook 签名失败

- **现象**：`jabiz.inbound.receive{result="REJECTED", reason="no_match"}`；Stripe 后台该端点的投递 400；充值停在"处理中"、收款账户停在"待开通"。
- **原因**：签名密钥与端点不符（换了端点或滚动了密钥而没改环境；账户端点与 Connect 端点的密钥配反）；`too_old`：服务器时钟偏差或代理延迟太久。
- **处理**：在后台复制该端点的签名密钥，与环境中的比较（只比较末尾几位）；滚动期间按 §1.3 同时配新旧两个；修正后在 Stripe 后台对失败的事件"重新发送"（Stripe 自动重试最多 3 天）。
  已过期而未到账的充值由每小时的 `qb.topup-stale` 在支付页过期一小时后向 Stripe 核实入账，不需要手工入账。检查 NTP。

### 7.2 核对差异

- **现象**：每日核对 `DIFF`，后台待办（`qb.reconcile`），`qb.admin.recon-items` 列出差异项（钱包与账本、待支付、2110 合计、账本平衡、充值与分录）。
- **处理**：按差异项的种类在后台读对应的记录与分录；**不直接改表**（只追加表有触发器，普通表的修改会让核对与审计更难对上）。账本只经冲正修正（`LEDGER_REVERSE`，由对应的业务流程）；
  确属缺陷时开问题、写复现测试再修。差异消除后的下一次核对为 `OK`，分录导出才能继续（它要求月末之后最近一次核对为 `OK`）。
- 1110（Stripe 余额）与 Stripe 实际余额的出入（争议手续费、不退还的手续费、测试资金）不在核对中，由财务按 Stripe 报表调整。

### 7.3 分录导出被拒

导出前一次报告全部问题（`QB_JOURNAL_EXPORT` 422）：

| 错误码 | 处理 |
|---|---|
| `QB_JOURNAL_PERIOD_OPEN` | 该月（日本日期）还没结束 |
| `QB_JOURNAL_PERIOD_NOT_NEXT` | 按顺序导出：先导出更早的月份 |
| `QB_JOURNAL_RECON_NOT_OK` | 月末之后最近一次核对不是 `OK`：先处理 §7.2 |
| `QB_JOURNAL_ACCOUNT_UNMAPPED` / `_INVALID` | 科目映射缺失或代码不合格（`[A-Za-z0-9][A-Za-z0-9._-]{0,19}`）：受控变更 `qb.journal.account.<科目>` |
| `QB_JOURNAL_UNBALANCED` | 借贷不等：缺陷，开问题 |
| `QB_JOURNAL_TRANSACTION_OPEN` | 库中有打开太久的事务：查 `pg_stat_activity` 中 `xact_start` 最早的会话（多半是手工的 `psql` 或卡住的批处理），结束它后重试 |
| `QB_JOURNAL_TOO_LARGE` | 一笔交易超过半段：缺陷或异常数据，开问题 |
| `QB_JOURNAL_NOTHING_POSTED` | 该期没有分录（正常） |

### 7.4 卡住的转账

- **现象**：转账停在 `SENDING` 或 `CHECK`；待办"转账结果不明"；`jabiz.external.job{job_type="qb.stripe.transfer"}` 失败。
- **机制**：结果不明（超时、429、5xx、租约丢失）时进入 `CHECK`、**不冲回**，每小时的 `qb.transfer-stale` 按 `transfer_group = qb-<转账号>` 向 Stripe 查询定论（只有晚于发送结束至少 10 分钟的"没有"才冲正）。
- **处理**：等下一次 `qb.transfer-stale`（每小时 47 分）；在 Stripe 后台按转账组 `qb-<转账号>` 搜索确认；看 `GET /api/external-jobs/{作业号}`（`external-job.read`；没有列表接口）中该作业的状态、尝试次数与最后的错误码。作业号以只读账号按转账号查：`SELECT job_id, job_type, created_time FROM sys_external_job WHERE reference = '<转账号>' ORDER BY job_seq`（发送 `qb.stripe.transfer` 与核实 `qb.stripe.transfer-check` 各有作业）。
  **不要**在 Stripe 后台手工再转一笔或在库中改状态：重复转账只能事后追回。用户因 3 次失败或被撤回而暂停时，财务确认原因后 `QB_TRANSFER_HOLD_RELEASE` 放行。
- 余额不足（`balance_insufficient`）不是卡住：该笔立即冲正，次日以新转账重试；持续出现说明资金调度不够（§4 最后两条）。

### 7.5 退款与争议的差额

- **现象**：待办 `REFUND_SHORTFALL` / 争议差额，充值的 `refundShortfall` / `disputeShortfall` 不为 0。
- **机制**：退款与败诉的争议只能从商家可用余额扣回（Kudos 已作为奖励付出的部分扣不回），余下记为差额、开待办；**记下后不再自动扣回**。
- **处理**：财务线下向商家追收（或核销），在待办中写明处理结果并关闭。退款只能在 Stripe 后台发起（QuizBuks 不提供退款流程），发起前先看商家的可用余额以预估差额。
  待办 `UNKNOWN_PAYMENT`（退款晚于充值确认且充值当时未入账）须在充值入账后人工对照（README"充值与核对"）。

### 7.6 提交时过载（503）——已知的上线风险

- **现象**：热门发布上 `QB_ATTEMPT_SUBMIT` 返回 503 `PROCESS_BUSY`（`jabiz.process.admission{family="qb.pub", result="QUEUE_FULL"|"WAIT_TIMEOUT"}`），App 显示"人多，正在重试"；截止前按下的提交可能排队、重试到截止加宽限之后，以 `EXPIRED` 结束（超时任务以已保存的答案结束，可能少了最后不到一秒的改动）。
- **原因**：锁拆分之后开始一侧有余量，人人进入答题，过载落在提交上（`perf/Q10a-load-test.md` §10.3 第 3 条）；单个发布的容量本机约 45–54 次 / 秒（16p-2 之后，报告 §11）。
- **处理**：
  1. 看是否单个发布：后台发布页的完成人数与 `jabiz.process.admission.waiting` 按族；
  2. 机器 CPU 是否 > 70 %（读取量是否异常：爬虫、首页刷新）→ 扩 CPU、拦截异常流量；
  3. 必要时内容管理员**暂停**该发布（`QB_PUBLICATION_PAUSE`：立即停止新的开始，进行中的会话照常提交），队列消化后恢复（`QB_PUBLICATION_RESUME`）；
  4. 事后：用户投诉 `EXPIRED` 时按会话的已保存答案与结果解释；评估开始一侧的背压（报告 §10.3 的可选缓解）与报告 §11.4 的其他选项。
- **上线前**：在目标硬件上确认预期峰值低于饱和点（§6）。

### 7.7 其他

- **邮件发不出**：`jabiz.mail` 的失败、发件服务商的退信；用户可在登录页重新请求验证邮件。
- **启动失败**：日志中的 `Platform check failed` 后列出全部问题（`类别 | 定位 | 描述`），逐项修正环境；`QUIZBUKS` 类别是本应用的检查（§4）。
- **证书、域名更换**：同时改 `JABIZ_MAIL_BASE_URL`、`QB_SPONSOR_LINK`、`QB_APP_LINK`、`QB_OIDC_REDIRECT_APP`（与 Google 控制台）与两个 Stripe 端点的地址。
