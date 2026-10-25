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
| `QB_APP_EMAIL_LINK` | 否 | 缺省 `true` | — | App 以邮件中的链接登录（Q15 的分享链接，平台 D54）；关掉后分享页仍显示，但只能用密码登录 |
| `JABIZ_PUBLIC_ENABLED` | 否 | 缺省 `true`（Q7b） | — | 设 `false` 时 `/api/public/**` 一律 404：帮助中心与条款、隐私政策页（App 与商家后台，也给未登录的人看）都打不开。只有四个公开模板 `qb.public.*`（已发布的法律文本版本与已发布的 FAQ），没有公开的文件；按客户端地址限流（平台缺省每分钟 300 次），结果缓存 5 分钟 |

- `JABIZ_WEB_ENVIRONMENT`（平台 16zd）：页面上的系统标志。只有正式环境设 `production`；其他值（`staging`、`local`，不设即 `test`）让 App、商家后台与管理后台的
  **每一页**（含登录页）顶部显示警示色横条 "<环境名> · 测试环境，不是正式版"、标题前加 `[<环境名>]`。设置来自各环境的 `.env`（`deploy/quizbuks/DEPLOY.md`），
  deploy workflow 部署前检查 production 有这一行、staging 没有写 production。

### 1.2 邮件与登录

| 变量 | 必需 | 来源 | 轮换 | 缺少时 |
|---|---|---|---|---|
| `JABIZ_MAIL_ENABLED` | 是（`true`） | — | — | 邮件只记为 `SKIPPED`：注册的人永远不能验证邮箱、找回密码不可用（启动告警） |
| `JABIZ_MAIL_FROM` | 是 | 发件域（配好 SPF / DKIM / DMARC） | — | 邮件发不出 |
| `JABIZ_MAIL_BASE_URL` | 是 | 主机根地址 `https://<主机>`（不带 `/admin`） | 换域名时 | 邮件中的链接错误；也是商家后台地址的缺省来源 |
| `SPRING_MAIL_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | 是 | 邮件服务商（SMTP） | 按服务商；改环境变量、重启 | 投递失败 |
| `SPRING_PROFILES_ACTIVE` | 否 | `google` 开启 App 的 Google 登录。**绝不能含 `dev`**（`dev` 的签名密钥与操作人请求头是公开的） | — | 无 Google 登录 |
| `JABIZ_OIDC_GOOGLE_CLIENT_ID` / `_CLIENT_SECRET` | 开 Google 时 | Google Cloud 控制台 | 在控制台建新密钥、改环境变量、重启、删旧密钥 | Google 登录启动失败 |
| `QB_OIDC_REDIRECT_APP` | 开 Google 时 | `https://<主机>/taker/login/oidc`（与控制台登记的完全相同；Q30 之前没有 `/taker`） | 换域名时 | Google 拒绝回调 |
| `JABIZ_WEB_ROOT_REDIRECT` | 否 | 裸域名转到的路径，缺省 `/biz/`（招商网站，Q30）；`QB_APP_LINK` 须以 `/taker/` 结尾 | — | 裸域名打开招商站 |
| `JABIZ_SECURITY_MFA_ADMINISTRATION` | **不设** | — | — | 缺省 `true`：管理操作要求二次验证。`false` 只给本机演示、e2e 与 Stripe 测试模式的本机运行 |

**应用的邮件**（Q7a，`plans/Q7-messages-legal.md` D-Q7-1）：除注册、验证、找回密码与入驻结果外，QuizBuks 在以下事件后发信（都由事件的消费者以系统身份发出，不在答题与支付的提交路径上）：

| 收件人 | 邮件（模板） | 可退订 |
|---|---|---|
| 答题人 | 转账已发出 `qb.transfer.sent`、转账失败 `qb.transfer.failed`（只在转账仍是该状态时发）、收款账户需补资料 `qb.payout.action-needed`（每人 24 小时内至多一封） | 否 |
| 商家 | 充值到账 `qb.topup.paid`、发布通过 / 驳回 / 被下架 `qb.publication.approved` / `.rejected` / `.taken-down` | 否 |
| 商家 | 发布预算不足 `qb.publication.funds-short`（每个商家 24 小时内至多一封，列出当时全部预算不足的发布；窗口内的再次不足记为待发，由每小时 11 分的任务 `qb.mail-funds-short-due` 在窗口过后仍不足时补发）、发布已结束 `qb.publication.ended`（下架后或商家被封禁时不发） | 是（邮件中的链接，或商家后台"账户 → 邮件"） |

邮件中的时刻按日本时间、标为 `JST`（取自 `jabiz.fiscal-year-zone`）。`JABIZ_MAIL_ENABLED` 未开时这些邮件同样只记为 `SKIPPED`（`sys_mail_message` / `sys_mail_attempt`），不会补发。事件太晚才被消费（投递积压、或新消费者收到历史事件）时不发信：通知（可退订的两种）超过 3 天、事务邮件（转账、收款账户、充值、发布的审核结果与下架）超过 14 天（`NotificationMails.MAX_AGE` / `TRANSACTIONAL_MAX_AGE`）。补发任务的时间可以 `quizbuks.mail.funds-short-due-cron`（Spring 的六段 cron，缺省 `0 11 * * * *`，UTC）改；它只读待发的行（`qb_mail_notice` 的部分索引）。

**退订链接**：可退订邮件中的退订链接由平台生成，指向邮件所属入口自己的前端（平台 D52 第 3 条，`jabiz.mail.unsubscribe-paths`）：商家的两种通知（`.entries("sponsor")`）指向 `/sponsor/mail/unsubscribe`，欢迎邮件（`.entries("app")`）指向 `/mail/unsubscribe`，两个 SPA 都挂载平台的退订页（无需登录：链接中的令牌即凭证）。答题人与商家因此不需要打开 `/admin/`；后台的旧页 `/admin/mail/unsubscribe` 保留，此前发出的链接照常有效（`/admin/` 是否对外开放只取决于管理员从哪里登录）。

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
| `ANTHROPIC_API_KEY` | 是（Q8a；Q8b 的封面与 PDF 资料同一把） | Anthropic Console → API Keys：为本部署新建的密钥（建议单独的工作区，在 Console 设工作区的月支出上限作为最后一道防线） | Console 新建密钥、改环境变量、重启，再删除旧密钥。泄露时立即删除，并先以受控变更关闭 `qb.ai.enabled` | 启动失败（EXTERNAL）；没有密钥时设占位，AI 保持关闭 |

### 1.3a 银行转账充值（Q13a，`plans/Q13-bank-transfer.md`）

不需要环境变量。开启的步骤（都在后台，四眼）：

1. 新版本部署后运行一次 `QB_SETUP`：给角色加上 `qb.topup.bank.record` / `qb.topup.bank.approve`，并提出审批规则 `qb.topup-bank.review` 与新的受控参数；**另一位管理员发布这些提议**（审批规则生效之前，财务登记一律被拒 `QB_TOPUP_BANK_NO_REVIEW`）。
2. 以受控变更设收款账户：`qb.bank.receiving.bank`、`.branch`（名称带代码）、`.account-type`、`.account-number`、`.account-holder`（片假名），以及 `qb.topup.bank.min` / `max`。五项都设了之前，商家的银行转账充值一律被拒（`QB_TOPUP_BANK_UNAVAILABLE`）。
3. 日常：商家报告汇款后财务会收到待办；在后台"银行转账充值"中对照银行流水登记实收、到账日与流水号；另一位管理员（内容或财务）在同一页审批后才入账。登记人不能审批自己的登记。驳回会回到登记前的状态，可重新登记。
4. 30 天没有到账的申请由每日任务（19:23 UTC）置为过期；之后才到的钱仍可在"已过期"的申请上登记。

### 1.3b 自动转账的开启与"立即转账"（Q14）

自动转账缺省关闭。开启：以受控变更设 `qb.transfer.enabled = true`（以及门槛、手续费、审核额），另一位管理员发布。
收款人须已在 App 中开通 Stripe 收款账户并为 `ENABLED`。后台用户页的"收款状态"给出此刻不转的原因（开关关闭、没有账户、账户未启用、低于门槛、暂停等）；
持有 `qb.transfer.now` 的财务可以"立即转账"（同样的检查，不能转给自己；超过审核额的由每日任务提出审批）。

### 1.3c 银行提现（Q13b）

不需要环境变量。开启：部署后运行 `QB_SETUP`（财务角色加 `qb.payout.bank.reveal` / `qb.withdrawal.pay`，并提出 `qb.withdrawal.bank.enabled` / `.fee` / `.cooldown-hours`），另一位管理员发布；
再以受控变更设 `qb.withdrawal.bank.enabled = true` 与手续费。转账审核规则 `qb.transfer.review` 须生效（每笔银行提现都要审核）。

日常：答题人申请后，持有 `qb.payout.review` 的财务（不是本人）在转账审核中批准；批准后有"请打款"待办。在后台"银行提现"中点"显示账号"（每次记录），
在网银中打款，再点"已打款"填流水日与流水号；被银行退回时点"退回"填原因，Kudos 回到答题人余额。**不要**在库中改状态。

### 1.4 只给测试的开关（生产不设）

正式模式（或正式密钥）下设了即**启动失败**的：

| 开关 | 用途 | 由谁拒绝 |
|---|---|---|
| `QB_TRANSFER_RUN_CRON`、`QB_RECONCILE_RUN_CRON` | e2e 与 Stripe 测试模式：每分钟转账、每分钟核对 | `TransferScheduleCheck` |
| `QB_ANALYTICS_RUN_CRON` | e2e：每分钟运行商家分析（Q9e2）。**隐私控制**：概览是任务的快照，频率决定能把一个答题人的作答定位到多细；缺省（每 10 分钟）以外的值**任何环境**都要同时设 `QB_E2E_ENABLED=true`，否则启动失败；**比每分钟更频繁的值一律拒绝**（评审 M1） | `TransferScheduleCheck` |
| `QB_E2E_ENABLED`（`quizbuks.e2e.enabled`） | e2e 的开关（Q9e2 评审 M1）：允许改分析任务的时间；正式模式或正式密钥下设了即启动失败 | `TransferScheduleCheck` |
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
2. 以 `JABIZ_BOOTSTRAP_ADMIN_USER` 登录 `/admin/`，在"安全设置"中**开启两步验证**（TOTP），保存恢复码。随后从环境中删去 `JABIZ_BOOTSTRAP_ADMIN_PASSWORD` 并设 `QB_BOOTSTRAP_ADMIN_DONE=true`（两者都没有时镜像的入口脚本拒绝启动；共用服务器上的步骤见 `deploy/quizbuks/DEPLOY.md`）。
3. 执行 `QB_SETUP`（流程页面；要求二次验证）：建角色、账本科目、国家、业务参数，并把受控参数与审批规则作为**提案**提出。
4. 首个管理员建**财务管理员**（`QB_ADMIN_FINANCE`，持有 `control.publish`）与**内容管理员**（`QB_ADMIN_CONTENT`）：`SEC_USER_CREATE` 并以 `SEC_USER_ROLE_ASSIGN` 分配角色；本人登录、开启两步验证。
   给管理员角色设"要求二次验证"（`SecRole.requireMfa`），使未绑定的管理员无法登录。各自一人一号、各自二次验证；不共用账号。
   这两种角色不持有改权限的权限（`security.user-role.write` / `security.role.write`），平台不把它们算作管理员（D51），建立期内的分配当即生效。
   **建管理员账号时不设密码**（`SEC_USER_CREATE` 只填用户名与邮箱），由本人经邮件链接（"忘记密码"）设定：建立期结束后，平台不把受保护的权限给一个由别人设过密码的账号（批准后生效时 `TARGET_CREDENTIALS_NOT_OWN`，本人之后自己改过也不算；平台 10 §16.2），以后给这些角色增加受保护的权限也会因此失败。
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
   - `qb.ai.enabled` 由 `QB_SETUP` 提出 `false`，**照样发布**（AI 在上线检查清单的条件满足后才开启）。发布之前参数不存在：启动检查 `EXTERNAL` 对 `job qb.ai.questions` 与 `job qb.ai.cover` 各报一条**警告**
     `enabledBy: parameter qb.ai.enabled has no value in effect now; the type is paused …`，执行器第一次轮询时也记一条 WARN。这在新安装上是预期的（参数缺失与 `false` 一样：请求被拒、没有作业），
     发布 `false` 之后不再出现；已发布之后仍出现即参数被删或读不出来，应调查（§5"AI 作业暂停"）。
7. 在测试模式下做一次 Stripe 测试模式全流程（`stripe-test-mode.md`），并按 §2.4 做一次恢复演练。
8. 每次部署新版本后：再执行一次 `QB_SETUP`，发布它的新提案（README 各阶段的"部署后"）。忘了的话，启动日志与 `platformCheck` 对每个没有生效值的业务参数各报一条警告 `QUIZBUKS | parameter <键>`（从未执行过设置的数据库只报一条 `QB_SETUP has not run yet`）；读这些参数的流程在那之前一律失败（`PARAM_NOT_FOUND`）。
9. **条款、隐私政策与帮助**（Q7b）：`QB_SETUP` 为四种法律文本（答题人条款、商家条款、隐私政策、特定商取引法表记）各建一份**明确标注 `[TEST PLACEHOLDER]` 的草稿**，什么也不发布。
   正式文本由需求方或律师提供（日文为准据文本，英文须一并提供；中文可缺，页面显示英文并注明）。内容管理员在后台的"法律文本（草稿）"中把草稿换成正式文本（标题、正文，第二版起还有"改定的概要"，日英必填），
   再在后台扩展的"QuizBuks → 规约类"页（`/qb/legal`）预览草稿并"发布"（`QB_LEGAL_PUBLISH`，每次要求二次验证，留审计；发布的是页面读到的草稿版本，期间被改即拒绝）：生效时间留空即立即生效，或填以后的时间作预告（至多 `qb.legal.max-announce-days` 天；预告期间不能再发布同一种文本）；
   预告有误时在同一页的版本列表中填理由"撤回"（`QB_LEGAL_WITHDRAW`，只能在生效前；撤回的版本永不生效、不要求同意，版本号不再使用，公开的历史中标为"已撤回"）；
   实质性变更勾选"须重新同意"，措辞修订不勾。已发布的版本不可修改，改错只能发布新版本。正式模式下缺少已生效的答题人条款、商家条款或隐私政策时**启动告警**（`LiveModeDataCheck`）。
   帮助中心的问答在后台"帮助中心问答"中维护（英语必填、`seq` 排序、"公开中"才出现在页面）。支持邮箱 `qb.support.email`（普通参数，空即不显示"仍需帮助"的联系方式）在后台参数中设。
   发布条款时可勾选"在收件箱中公告"：一分钟内（任务 `qb.broadcast-dispatch`）给相关的人发一条系统公告（答题人条款 → App，隐私政策 → App 与商家后台，其余 → 商家后台），有效 30 天。
10. **站内信与公告**（Q7c）：个人消息（奖励、转账、收款账户）由事件的消费者写入，不需设置；公告在后台扩展的"QuizBuks → 公告与通知"（`/qb/broadcasts`）编写与发送（发送要求二次验证，撤回每次二次验证并填理由，商家可见）。
   普通参数（`QB_SETUP` 建立，可在后台参数中直接改）：商家每个发布的通知上限 `qb.broadcast.sponsor.max-per-publication`（3）、两条之间的最短间隔 `qb.broadcast.sponsor.min-interval-hours`（24）、
   公告的缺省有效期 `qb.broadcast.expires-days`（30）、收款提醒的间隔 `qb.payout.reminder-days`（30）。商家通知不预审（Q7-10）：内容有问题时由内容管理员撤回；封禁商家即撤回其全部通知。
   任务：`qb.broadcast-dispatch`（每分钟，定时发送；`quizbuks.messaging.dispatch-cron`）、`qb.broadcast-expire`（每分钟第 30 秒，把过期的与发布已结束的通知移出收件箱；`quizbuks.messaging.expire-cron`；停了它，过期的公告与已结束发布的通知会留在收件箱中直到它恢复，读取也随之变慢；恢复后一次补做）、
   `qb.broadcast-stats`（每日 04:47 UTC，商家通知统计的快照；`quizbuks.messaging.stats-cron`）、`qb.payout-reminder`（每日 03:17 UTC；`quizbuks.messaging.payout-reminder-cron`）。
   商家看到的统计的下限 `qb.broadcast.stats.min-audience`（50，普通参数）：到达不足它时不显示点击与开始答题（评审 M2）。
   限流（`application.yml` 的 `jabiz.process.rate-limits`）：已读与点击每人每分钟 120 次、全部已读与推广开关各 30 次、商家保存通知 60 次、发送每小时 10 次。

**受保护的授权（平台 16r / 16u，决策 D48、D51；10 §16）**：分配或增加 `security.user-role.write`、`security.role.write`、`security.protected-change.decide`、`approval.decide`、`control.publish`、`ledger.*`、
`platform.param.write`、`*`，以及对持有它们的用户重设密码、二次验证、邮箱或关联外部账号，平台要求**另一位管理员审批**——但只在建立期结束之后。
平台按管理员的权限（`security.user-role.write`、`security.role.write`，`administrator-permissions` 的缺省值）数管理员：QuizBuks 的管理员就是 `QB_ADMIN_SUPER` 的持有人。
首个管理员之外出现一位，首个管理员即退役；出现**两位**，建立期即结束（永不回退）。所以 QuizBuks 需要**至少两位超级管理员**，按上面第 4–5 步的顺序建立，并始终保持两位在职
（只剩一位时没有人能批准第二位；出路同平台 10 §16.3：运维以 `JABIZ_SECURITY_PROTECTEDGRANTS_ENABLED=false` 启动一次、建好后改回，记录在变更记录中）。
- **审批**：待审批的变更出现在后台"待办"中（平台的 `ApprovalPanel`，`APPROVAL_DECIDE`），由另一位超级管理员以 `security.protected-change.decide` 决定（要求近期二次验证；申请人与所针对的用户不能批；
  `approval.read` 让超级管理员读到请求的事实）。超级管理员不持有 `approval.decide`，不能决定业务审批（入驻、发布、大额转账），这些仍由内容与财务管理员决定。
- **新的管理员账号**：建立期结束后任命的人，其账号的密码须由本人设定（建号时不设密码、本人经邮件链接设定），否则批准后不生效（`TARGET_CREDENTIALS_NOT_OWN`）。
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
| `AiPdfLimitsCheck`（Q8b） | `QB_AI_PDF_MAX_PAGES` × 5 000 加上最大的文字输入与输出须放得进模型的上下文窗口（100 万 Token：至多约 179 页），且不超过提供方每次请求的 600 页（§7.8；不论模式都查） |
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
| AI 作业暂停（§7.8） | 量规 `jabiz.external.job.held{job_type="qb.ai.questions"|"qb.ai.cover"}`（`qb.ai.enabled` 不是 `true` 时为 1） | 不是有意关闭时（变更记录中没有关闭）为 1 超过 5 分钟：参数被删或读不出来，作业都在等待 |
| 任务 | `jabiz.job.run{result="FAILED"|"LOCKED"}` | 任一每日任务 `FAILED`；`LOCKED` 持续（另一实例卡住） |
| 站内信与公告（Q7c） | `jabiz.job.item{job="qb.broadcast-dispatch"|"qb.broadcast-expire"|"qb.broadcast-stats"|"qb.payout-reminder", result}`；`jabiz.query.template{template="qb.app.inbox-unread"}` 的耗时（徽标，每个在线用户每 5 分钟一次；目标 < 5 ms，`docs/quizbuks/perf/Q7c-inbox.md`）；`jabiz.outbox.delivery{result="FAILED"}`（消费者 `qb.msg-*`） | 定时发送的项连续失败（公告迟迟不出）；消费者失败持续 15 分钟 |
| 商家分析（Q9e，§7.7） | `jabiz.job.run{job="qb.analytics"}`；`jabiz.process{process="QB_ANALYTICS_ITEM"}` 的时间 | `PARTIAL` 连续 3 次（同一项一直失败）；30 分钟内没有成功的运行；运行时间持续超过 2 分钟（积压） |
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

### 7.7 商家分析的数据（Q9e）

- **任务** `qb.analytics`（每 10 分钟，:00、:10 …；逐项任务，每项一个事务的 `QB_ANALYTICS_ITEM`）：读进行中、暂停、预算不足、1 天内结束与结束后尚未定稿的发布，按发布编号分块（每块一项），把计数的增量写进按小时的桶，从水位线读已完成的会话（滞后 5 分钟）；结束 30 分钟后每个发布一项整体重算并定稿；最后一项写运行记录并删除 400 天前的小时桶（每次至多 2 000 行）。不取业务锁，不影响答题。
  **一项失败不影响其他项**：其他项照常提交，运行记为 `PARTIAL`，失败的项由平台退避后在以后的运行中重试（平台的任务项记录中有错误），其间该块的发布的数字停在上次。
  商家页面上的"每 10 分钟更新，最近更新 hh:mm"就是这个任务最近一次运行的时刻（`qb_stats_run` 中 `job = 'qb.analytics'` 的行，`zone` 是它按哪个时区分日：`jabiz.fiscal-year-zone`，日本部署 `Asia/Tokyo`）。
- **参数** `qb.stats.min-group`（普通参数，缺省 5，任何值都至少按 3）：分组小于它时每题正确率、平均用时与超时人数不显示，且这些数字只在新增至少这么多个完成后才变化。调大立即生效（模板按现值隐藏）；调小只在下次新增够数时显示新值。
- **配置**（`application.yml` 的 `quizbuks.analytics.*`，通常不改）：`chunk-size`（每块的发布数，缺省 100，至多 200）、`max-attempts-per-chunk`（每块每次至多读的已完成会话，缺省 50 000，至多 200 000；热门发布每 10 分钟约 3 万个，`perf/Q9e-analytics.md`）、`max-recount`（结束后整体重算的发布至多有多少个完成，缺省 1 000 000，使重算在读取时限 2 分钟之内；更大的发布在逐块读完之后不重算即定稿）。
  部署时区 `jabiz.fiscal-year-zone` 只能是 `UTC` 或地区名（如 `Asia/Tokyo`）：`+09:00`、`GMT+9`、`Etc/GMT-9` 这类偏移写法在 PostgreSQL 中方向相反，启动检查拒绝（类别 `QUIZBUKS`）。
- **停机与补跑**：任务停了之后的第一次运行把期间的增量全部记在停机前最后一次运行的那一小时，并标 `gap`（趋势注明），累计数字始终正确；积压的会话按块上限分几次读完。不需要手工补跑；同一计划时刻重复运行不会重复计数。
- **告警**：`jabiz.job.run{job="qb.analytics", result="PARTIAL"}` 连续出现（某一项一直失败：看任务项的错误，常见为读取超时或约束）；最近一次运行早于 30 分钟（商家页面的"最近更新"随之变旧）。运行时间见 `jabiz.process{process="QB_ANALYTICS_ITEM"}`，热门发布积压时变长。
- 日志中的 `Analytics: the counters of publication … went down` 表示计数被别的方式改动（计数只增不减），该发布本次不写；`… recounted … finalized anyway` 表示结束一天后整体重算与计数仍不一致，按重算的值定稿：两者都应调查。

### 7.8 AI 生成题目与封面（Q8a、Q8b）

- **开关与限额**（后台 `/qb/ai`，受控参数）：`qb.ai.enabled`（缺省 false）、`qb.ai.model`、`qb.ai.effort`、`qb.ai.sponsor.daily-jobs`（20）、`qb.ai.sponsor.monthly-tokens`（200 万）、`qb.ai.global.monthly-tokens`（5 000 万）；普通参数 `qb.ai.candidates.expire-days`（7）与只用于估算的价格 `qb.ai.price.*`。限额在 `qb.ai:<商家>` → `qb.ai:*` 锁内按已记录的用量与未结束作业的预估检查（全体上限也加上所有商家未结束作业的预估）；一个作业的预估是 3 次尝试各自完整的输入与所要的全部输出（`max_tokens` 随题数：4 000 + 600 / 题，至多 16 000；effort `high` 时一次至多 10 题）。
- **作业** `qb.ai.questions`：每次尝试至多 5 分钟（请求本身至多 `QB_AI_REQUEST_TIMEOUT`，缺省也是上限 2 分钟）、3 次尝试（429、408、5xx、529、超时、断线与回退忙时的拒答重试，退避 10 秒起、至多 2 分钟；其他拒答、截断、其他 4xx 立即失败）、全实例并发 `QB_AI_MAX_CONCURRENCY`（缺省 4）。**这个并发是每种作业各自的**：题目 `qb.ai.questions` 与封面 `qb.ai.cover` 各至多这么多，所以同时打到提供方的调用至多是两者之和（缺省 4 + 4 = 8）；按提供方的速率限制与堆（见下面"PDF 资料"）取值时按和计。提供方不按幂等键去重：重试可能重复计费，用量按每次尝试记录，也计入限额；**没有得到回答的尝试**（超时、断线、回答过大）按预估计入（带 PDF 的请求按提供方数出的输入，其他按文字的估计，加所要的全部输出），因为提供方可能已为没人读到的回答计费。
  **用量与预留**：带 PDF 的请求不带提供方的回退（`fallbacks`；复审 M-a：回退会在另一个模型上把输入再算一次，计费就会超过计数），所以每次付费的 PDF 调用正好是计过数的请求，**每次计过数的调用的输入不超过预留**；带 PDF 的请求被拒答时由下一次尝试重试（再计一次数）。
  只有文字的请求不计数、只按文字估计，并照常带回退：它的实际输入可能超过预留（回退时约为两倍）。某次尝试的实际用量超过请求为它预留的会记一条告警日志 `AI usage of one attempt above its reservation (job type …)`（只有作业种类，不含编号，D16）：偶尔出现可以接受，经常出现时调高预估或月上限。
  **价格**：`costUsd` 按每次调用计价。较大的两个模型不论提示多长都是同一价格；最小的模型在一次调用的输入超过 10 万 Token 时整个调用按较高的长提示价格计（`AiCost`），所以按每次尝试分别计入，不按作业平均。
- **任务** `qb.ai-expire`（每日 18:43 UTC）：把过期未校对的候选从作业中移除（之后不能再加入；提供方的原始回答仍在平台只追加的 `sys_external_job` 结果中），逐项一个事务。
- **提供方故障**：后台 `/qb/ai` 的"最近的请求"按失败原因看（`QB_AI_RATE_LIMITED`、`QB_AI_PROVIDER_UNAVAILABLE`、`QB_AI_AUTH`：密钥错或被删，`EXTERNAL_CALL_FAILED`：没有回答）；长时间故障时以受控变更关闭开关（商家页面显示"暂时不可用"，手动编辑不受影响；排队与重试中的作业随之暂停、不再用掉尝试次数，见下面第 2 步）。不自动切换到其他提供方。
- **事故时的紧急停止**（花费异常、密钥泄露），按快慢：
  1. **最快：在 Anthropic Console 吊销（删除）密钥**。立即生效、不需要部署或第二个人；之后每次尝试得到 401，作业以 `QB_AI_AUTH` 立即失败、不再重试（排队与重试中的作业都一样），商家看到"暂时不可用"。恢复时新建密钥、改 `ANTHROPIC_API_KEY`、重启。
  2. **以四眼关闭开关 `qb.ai.enabled`**：受控参数，**需要两位管理员**（一位在 `/qb/ai` 提出、另一位发布），所以不是一个人能立刻做到的。发布之后：新的请求一律 `QB_AI_UNAVAILABLE`；
     两种作业类型声明了 `enabledBy("qb.ai.enabled")`（平台 16v-2，D52 第 4 条），**每个实例在约一个轮询间隔（`poll-interval`，缺省 1 秒）之内**不再领取 `qb.ai.questions` 与 `qb.ai.cover` 的作业：
     排队的与等待重试的作业**原地等待**，不调用提供方、不用掉尝试次数、不失败；**正在运行的尝试照常进行到结束**（处理器看不到开关），其用量照常计入。参数读过之后才关闭时，那一次轮询仍可能多领取一轮。
  3. **逐个取消作业**：持有 `external-job.cancel` 的管理员（平台的 `ADMIN` 角色）对作业调用 `POST /api/external-jobs/{作业号}/cancel`（作业号在 `/qb/ai` 的"最近的请求"与 `qb.admin.ai-jobs` 中）：没在运行的（包括第 2 步暂停中等待的）立即结束、正在运行的在本次尝试后结束、不再重试；作业结束后平台调用两种作业类型的取消结局 `QB_AI_JOB_CANCELLED`（平台 D52 第 2 条），它关闭本地作业（`QB_AI_STOPPED`），没有尝试运行过的不计费。
  **没有 Console 权限、也找不到第二位管理员时**（只有基础设施的权限）：把 `ANTHROPIC_API_KEY` 改为占位值（如 `revoked-placeholder`）并重启全部实例。之后每次尝试得到 401，作业以 `QB_AI_AUTH` 立即失败、不再重试（排队与重试中的也一样，与第 1 步相同；正在运行的尝试照常结束），
  新的请求仍会被接受并随即失败。能联系上 Console 的负责人或第二位管理员后，按第 1、2 步处理，再换回真实密钥并重启。
  **不要**为此设 `JABIZ_EXTERNALJOBS_EXECUTOR_ENABLED=false`（`jabiz.external-jobs.executor.enabled`）：它停掉本实例的**全部**外部作业，包括 Stripe 的转账与核对（`qb.stripe.*`），资金流程随之停住。
  **暂停期间的作业**（第 2 步之后）：仍是未结束的作业，所以**照常计入商家同时未结束的上限**（每商家 2 个、每模版每种类 1 个）与月 Token 的预留；商家在编辑器中看到它仍在排队（`QUEUED`，面板注明 AI 暂停、请求会等待、可以取消），可以自己取消（不计费）。
  重新打开（同样四眼）后的下一次轮询即恢复，积压的作业按并发上限与退避逐步执行；长时间暂停后，不再需要的作业由商家或第 3 步取消。日志在每个实例上只在变化时记一条 `External job type qb.ai.… is paused: parameter qb.ai.enabled …`（INFO）与恢复时的一条；量规 `jabiz.external.job.held{job_type}` 暂停时为 1。
  此前的本实例开关 `QB_AI_HALTED`（需要重启、让排队与重试中的作业以 `QB_AI_HALTED` 永久失败）已删去：第 2 步做到了它的事而不需要重启、也不让作业失败。
- **拒答**：请求带服务端回退（`fallbacks: "default"`，Haiku 无），回退后仍拒答即 `QB_AI_REFUSED`；`/qb/ai` 的"实际回答"列显示回退所用的模型。
- **取消怎样计费**（平台 16v-1 之后）：商家取消（`QB_AI_CANCEL`）与运维停止（上面第 3 条）都取消平台的作业：排队中的不再调用提供方；正在运行的尝试照常进行到结束，回答被丢弃。作业结束后 `QB_AI_JOB_CANCELLED` 只运行一次，按平台记下的**实际用量**（全部尝试，含被取消的那次：处理器在每次调用后记下的输入、输出、回退模型与长提示的部分，没有得到回答的调用按预估）计入作业与月用量，美元估算同失败的作业（`AiCost`，不知道回答的模型时回退部分按回退模型的价格）；**没有任何尝试运行过的作业不计费**（不计入用量行）。
  此前的做法：商家取消只改本地状态，平台的作业照常运行到完成（排队中的也会调用一次提供方），由完成或失败流程按实际用量计入；运维停止由 `QB_AI_JOB_STOPPED`（订阅 `jabiz.external-job.finished`）按**完整预估**计入。两者都已删去，同一作业只经取消结局计费一次。取消的作业计入用量行的作业数，但**不计为失败**（`failedJobs` 与后台 `/qb/ai` 的失败率只算失败的作业）。
  **没有记下用量的尝试**（平台 16w-1 之后，D52 第 5 条）：处理器在每次调用之后（没有回答的调用在放弃时按预估）就记下用量；实例在尝试中途崩溃或卡住、租约过期（或超时）而什么也没记下的尝试，平台在完成、失败与取消的结局中报告其**个数**，
  `QB_AI_JOB_COMPLETED`、`QB_AI_JOB_FAILED` 与 `QB_AI_JOB_CANCELLED` 对每一次都按没有回答的调用同一依据计入：作业为一次尝试预留的输入（作业参数 `reservedInputTokens`：文字的估计，带 PDF 的再加页数上限）加所要的全部输出（题目 `maxTokens(题数)`，封面 `AiCoverJob.MAX_TOKENS`（8 000）），美元按 `AiCost`（长提示的规则同记下的调用）。
  因此不再有计为零的尝试。迟到的实例（租约过期后才回来）的用量由平台记到那次尝试上；结局流程运行之前回来的，那次尝试不再算"没有记下"，按它实际报告的用量计入而不是按估计；之后回来的只出现在平台的用量报表（`jabiz.external_job_usage`）中，QuizBuks 不再改动已计的用量（按估计计入的那次可能偏高或偏低）。
  **有意偏高**：实例在到达提供方之前就死掉的尝试（计数 `count_tokens`、读文件或画图时）同样按完整的估计计入；带 PDF 的作业即页数上限（`QB_AI_PDF_MAX_PAGES` × 5 000，缺省约 50 万 Token）。平台只报告个数、不知道尝试走到了哪一步，所以按估计宁高勿低（D52 第 5 条）。
  **已知的偏低**：有回退模型的请求（只有不带文档的文字请求，Haiku 除外）没有得到回答时只计一次输入，而提供方的回退会把输入再运行一次、可能计两次；这一缺口是接受的，由提供方的支出上限兜底。
  提供方仍可能对重试重复计费，**提供方控制台上的支出上限仍是最后的防线**（上线前设好，§1.3）。
- **告警**：`jabiz.external_job` 中 `qb.ai.questions` 与 `qb.ai.cover` 的失败率；某商家或全体本月用量接近上限（`qb.admin.ai-usage`）。
- **封面（Q8b）**：作业 `qb.ai.cover`（与题目同样的尝试、重试、并发配置与限额，`/qb/ai` 的"种类"列区分）：Claude 只从固定的图库（8 种配色、4 种版式、7 种图形）中选出 3 个设计并写封面文字（≤ 40 字），由服务器以 Java2D 画成 1200 × 630 的 PNG，经平台的文件输出（D50）按 `qb.content.image` 策略以商家为上传者登记（按内容判定类型、重新编码）。
  中日文字体取 `JABIZ_REPORTS_PDF_FONTS`（镜像已设）：缺少时含中日文的标题在调用提供方之前即以 `QB_AI_COVER_FONT` 失败（不计费）。候选图在作业 `READY` 期间由作业的三列引用、不被清扫；商家选定（`QB_AI_COVER_ACCEPT`，经模版编辑写入 `cover`、`aiGenerated`）、丢弃或过期后，未选的图不再被引用，由平台的 `FILE_SWEEP` 在 `jabiz.files.orphan-after`（24 小时）后删除。
  **作业完成迟到的风险**：画好的图在作业结束时已登记为文件，到 QuizBuks 的完成流程（订阅作业结束事件）把它们写进作业的三列之前没有任何引用。事件投递停住（实例长时间停机、投递故障）超过 `orphan-after` 时，清扫可能先删掉这些图，之后的完成流程记下的是已删除的文件：作业显示为就绪，但图片显示不出、选定被拒绝（商家丢弃后重新请求即可，用量照常计入）。恢复投递后不要手动重放早于 `orphan-after` 的封面完成；长时间停机后先确认事件已投递，再看 `/qb/ai` 中这段时间结束的封面作业。
- **PDF 资料（Q8b）**：所选的 PDF 资料作为文件本身发送（平台的文件输入，D50：登记时检查商家能读该文件）：每个至多 `QB_AI_PDF_MAX_BYTES`（缺省 10 MiB，上限 20 MiB），一次请求的全部 PDF 合计至多 20 MiB，按至多 `QB_AI_PDF_MAX_PAGES` 页（缺省 100）预留。
  页数上限必须放得进模型的上下文窗口（三个可选模型都是 100 万 Token）：页数 × 5 000 加上最大的文字输入与输出不得超过它（约 179 页），也不得超过提供方每次请求的 600 页；否则启动检查 `AiPdfLimitsCheck`（类别 `QUIZBUKS`）拒绝启动。
  **服务器不解析 PDF**（Q8b 评审 H1、H2）：商家的文件可以构造成在解析器的内存中无限展开（对象流炸弹，使 JVM 内存耗尽），也可以在页树中谎报页数，所以本地数页既不安全也不可信。作业在付费调用之前，以完全相同的请求调用提供方免费的 `POST /v1/messages/count_tokens`（同一外部服务 `anthropic`、同样的请求头；计数有自己的速率限制），以**提供方数出的输入 Token** 为准：
  超过请求为一次尝试预留的输入即 `QB_AI_PDF_TOO_LONG`（永久、不发送、不计费）；提供方以 400 说请求过长（超过上下文窗口或每次请求的 PDF 页数）同样是 `QB_AI_PDF_TOO_LONG`；提供方说某个 PDF 读不了（无效、有密码、加密、损坏）即 `QB_AI_PDF_UNREADABLE`（都永久，400 不计费）。
  这些文字 API 文档没有给出，按常见的说法宽松匹配（`ClaudeErrors`）；其他 400，尤其是以字段路径开头的校验错误（如 `messages.0.content.0.document…`），一律是 `QB_AI_REQUEST_INVALID`：那是我们自己的请求有错，运维应当看到并调查。
  计数本身断线、超时、429 或 5xx 照常重试、不计费，但**会用掉作业 3 次尝试中的一次**（复审 L-d）：计数接连失败的作业可能在发送之前就用完尝试而失败（不计费，商家可重新请求）。之后的付费调用没有回答时按这个数计入。本地只查字节上限（存储的文件大小）。
  请求时还不知道页数，所以**按上限预留**：每页按 5 000 Token（文字与页面图像），3 次尝试，即缺省一个带 PDF 的请求预留约 150 万 Token，直到作业结束才按实际用量计；计数超过预留的作业在发送前即失败，带 PDF 的调用又不带回退，所以带 PDF 的调用计入的输入不超过预留。取消的 PDF 作业按实际用量计入（排队中被取消的不计费，预留随之释放）。商家的月上限（缺省 200 万）因此只容得下一个进行中的 PDF 请求；常用 PDF 时提高 `qb.ai.sponsor.monthly-tokens` 或降低 `QB_AI_PDF_MAX_PAGES`（预留随之按比例减少）。
  作业输入上限 256 KiB（文字资料；Q8a 为 60 KiB），作业输入照旧永久存于平台的 `sys_external_job`，PDF 只记文件编号与摘要（内容随 `sys_file` 删除而删除）。
  **堆**：一次带 PDF 的尝试在内存中同时持有文件的字节、Base64 一份（只编码一次，计数与调用共用）、请求体的字节与平台发送时的副本，约为该请求 PDF 合计的 5–6 倍，即 20 MiB 的上限下每个运行中的题目作业约 120 MiB（封面作业不带 PDF）。JVM 堆至少按"基础 512 MiB + 120 MiB × `QB_AI_MAX_CONCURRENCY`"给出（缺省并发 4 时约 1 GiB 以上）；容器内存紧张时降低 `QB_AI_MAX_CONCURRENCY` 或 `QB_AI_PDF_MAX_BYTES`。

### 7.9 其他

- **邮件发不出**：`jabiz.mail` 的失败、发件服务商的退信；用户可在登录页重新请求验证邮件。
- **启动失败**：日志中的 `Platform check failed` 后列出全部问题（`类别 | 定位 | 描述`），逐项修正环境；`QUIZBUKS` 类别是本应用的检查（§4）。
- **证书、域名更换**：同时改 `JABIZ_MAIL_BASE_URL`、`QB_SPONSOR_LINK`、`QB_APP_LINK`、`QB_OIDC_REDIRECT_APP`（与 Google 控制台）与两个 Stripe 端点的地址。
### 7.10 招商网站（Q31）

`/biz/` 是静态站点（`quizbuks-web/biz`），随 jar 一起构建与发布，没有后台数据。要改文案改 `src/content/{en,zh,ja}.mjs`（三种语言结构必须一致，`pnpm test` 检查）；
首页的"试答"区在 `src/content/try-quizzes.json` 里填每种语言的分享链接（`https://quizbuks.com/taker/p/<发布>`）与二维码图片（放到 `public/`，填相对路径），为空即显示"即将推出"。
价格页的数字是推广期估价，标"计划中"的套餐与费用尚未开售、尚未计费，改动前先在 `docs/quizbuks/plans/Q31-biz-site.md` 记下新值。链接只接受 `https://`、二维码只接受 `public/` 下的 PNG/SVG，否则构建失败。页面的 `canonical`、`hreflang` 与 `sitemap.xml` 用完整地址，源自构建时的 `SITE_URL`（缺省 `https://quizbuks.com`，换域名时在构建环境设置）。裸域名 302 到 `/biz/`；中文与日文在 `/biz/zh/…`、`/biz/ja/…`，从站外首次访问按浏览器语言转向（`localStorage` 记住切换；站内点击的语言从不被改）。

