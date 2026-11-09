# QuizBuks — 生产运维手册（Q10d）

`deploy/quizbuks/README.md` 讲镜像、compose 与各阶段部署后要做的事；本手册是生产环境的操作手册：密钥清单、数据库与备份、首次启动、切换到正式模式、监控、容量与常见故障。
上线前逐条核对 `launch-checklist.md`。计划：`plans/Q10-launch.md` §6。

## 1. 环境变量与密钥

放在部署平台的密钥库（或服务器上不提交的 `deploy/quizbuks/.env`，权限 600）。密钥类的值都用 `openssl rand -base64 48` 生成（Base64、≥ 32 字节）。
"缺少时"一栏写的是**没设置**时发生什么；"轮换"写的是换值的代价。

### 1.1 平台与数据库

| 变量 | 必需 | 来源 | 轮换 | 缺少时 |
|---|---|---|---|---|
| `JABIZ_JWT_SECRET` | 是 | 生成，只放密钥库 | 可以轮换，不必重新登录（平台 D78，`docs/guide/key-rotation.md`）：旧值放进 `JABIZ_JWT_SECRET_PREVIOUS`（逗号分隔），新值设为当前值，滚动重启；旧值在保留期（`jabiz.security.jwt.previous-retention`，缺省 365 天，覆盖旧邮件中的退订链接）之后删去。泄露时立即换且不保留旧值（所有人重新登录） | 启动失败 |
| `JABIZ_INTEGRITY_KEY` | 是 | 生成；**另存离线副本**（与数据库备份分开保管） | 可以轮换（D78）：旧值放进 `JABIZ_INTEGRITY_KEY_PREVIOUS`，**永久保留**（封存块从不重签，旧块要一直能核验）；新的封存块用新密钥。每次轮换后更新离线副本（新旧密钥都在） | 启动失败；丢失 = 已有封存无法核验 |
| `JABIZ_MFA_KEY` | 是 | 生成；**另存离线副本** | 可以轮换（D78）：旧值放进 `JABIZ_MFA_KEY_PREVIOUS`，任务 `jabiz.key-rotation`（每 10 分钟）把 TOTP 密钥重新加密完（输出 `mfaSecrets` 为 0、`more=false`）后删去旧值并重启；用户不必重新绑定。密钥与库一同泄露时全员重新绑定（`SEC_MFA_REENROLL_ALL_REQUEST` / `_APPROVE`，见手册"泄露时"）。每次轮换后更新离线副本 | 启动失败；丢失 = 所有人的二次验证失效 |
| `JABIZ_SEAL_KEY` | 是（Q29） | 生成；**另存离线副本** | 可以轮换（D78）：旧值放进 `JABIZ_SEAL_KEY_PREVIOUS`，任务 `jabiz.key-rotation` 把 `SealedFields` 声明的密封值（发起方邮件服务器的密码、Shopify 折扣码等，F-F14）重新加密完（`sealedValues` 为 0、`more=false`、`unreadable` 为 0）后删去旧值并重启；没有声明的密封值在撤下旧值后解不开。**丢失**（没有旧值可用）时全部发起方要在「邮件服务器」页重新输入密码（在此之前邀请投递失败、平台自己的邮件不受影响）。每次轮换后更新离线副本 | 启动失败（应用声明了 `MailSenderSource`） |
| `JABIZ_BOOTSTRAP_ADMIN_USER` / `_PASSWORD` | 只首次启动 | 运维 | 首个管理员建好后从环境中**删去**（它只在库中没有用户时起作用） | 没有用户时无人能登录 |
| `JABIZ_FILES_LOCAL_ROOT` | 是 | 镜像已设 `/var/lib/jabiz/files`（卷） | — | 启动失败；卷丢失 = 上传的图片、PDF 丢失（备份见 §2.3） |
| `SPRING_R2DBC_URL` / `_USERNAME`、`SPRING_FLYWAY_URL` / `_USER`、`DB_PASSWORD`（或 `SPRING_R2DBC_PASSWORD` / `SPRING_FLYWAY_PASSWORD`） | 是 | 数据库 | 换密码：先在库中加新密码可用的窗口（`ALTER ROLE`），改环境变量、滚动重启 | 连不上库，启动失败 |
| `JABIZ_ERASURE_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | 开启账号删除时（平台 17i：账号删除的申请入口 `account-deletion`）；否则可不设 | `deploy/quizbuks/server/create-maintenance-login.sh <库名>` 的输出（`DEPLOY.md`"1a"）：**R2DBC** URL（`r2dbc:postgresql://…`，与 `SPRING_R2DBC_URL` 相同，**不是** Flyway 的 JDBC URL）与一个单独的维护登录（属于角色 `jabiz_maintenance`；应用自己的登录绝不能属于它） | 再运行一次脚本即给同一登录换新密码，改 `.env` 后重新部署 | 不设：抹除账号照常完成，个人信息的清除 422 `ERASURE_PURGE_UNAVAILABLE`，有入口开启账号删除时启动失败。写成 JDBC 格式：启动失败，检查报一行 `ERASURE \| jabiz.erasure.datasource.url`（平台 17q 之前是一大段堆栈，部署回滚） |
| `QB_APP_ACCOUNT_DELETION` / `QB_SPONSOR_ACCOUNT_DELETION` | 学者的设为 `true` 是上架 App Store 与 Google Play 的前提（Q71）；发起方的可选（Q72） | 设为 `true` 即开启该入口的账号删除：App 与发起方页面的账户页出现"删除账号"，学者另有不装 App 的申请页 `/taker/delete-account` | — | 缺省 `false`：没有删除入口。开启而没有上一行的维护连接：启动失败 |
| `JABIZ_GEOIP_DATABASE` | 正式模式必需（F-S11） | IP 地址库文件的路径（DB-IP "IP to Country Lite" 的 CSV，可为 `.gz`；服务器上放在卷 `geoip`，`/var/lib/jabiz/geoip/…`，§7.24） | 每月换文件（同一路径覆盖即可，平台一分钟内读入新文件，无需重启；新文件读不懂即继续用旧的） | 正式模式下启动失败（`QUIZBUKS \| jabiz.geoip.database`），其他模式警告：所有地址查不到、按 `qb.region.unknown-ip-country`（缺省中国大陆）处理，含境外视频的发布谁也不能答。设了而读不了：启动失败（`GEOIP`）。文件旧于 `JABIZ_GEOIP_MAX_AGE`（缺省 45 天）：启动检查与每日检查告警 |
| `JABIZ_TRUSTED_PROXIES` | 在代理之后必需 | 代理的地址或网段 | 代理变化时 | 登录记录的 IP 都是代理的；入站 Webhook 按地址的限流把 Stripe 全部投递算成一个客户端（每分钟 120 次） |
| `JABIZ_REPORTS_PDF_FONTS` | 镜像已设 | 镜像中的 IPA Gothic、Droid Sans Fallback | — | 镜像外运行时收据与月报的中日文为 `?`；设了读不到的文件即启动失败 |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | 建议 `ecs` | — | — | 文本日志 |
| `JABIZ_STORAGE_WRITE_BATCHING_ENABLED` | **不设** | — | — | 缺省 `true`：平台在事务中缓冲写入（决策 D46，16p-2）。应急开关：只在怀疑写缓冲有缺陷时设 `false` 并重启，行为与保证不变，热门发布的容量约降 30 %（`perf/Q10a-load-test.md` §11） |
| `QB_ADMIN_ROLES` | 否 | 缺省 `ADMIN,QB_ADMIN_SUPER,QB_ADMIN_CONTENT,QB_ADMIN_FINANCE` | — | 用缺省。**永远不要**加 `*`、`QB_TAKER`、`QB_SPONSOR`（自己注册的人会进入平台管理系统）。启动检查只在相应入口开放注册时拒绝（`SignInEntries`）：`*` 在 App 或发起方任一入口开放注册时、`QB_TAKER` 在 App 开放注册时（`QB_APP_SELF_REGISTRATION`）、`QB_SPONSOR` 在发起方开放注册时（`QB_SPONSOR_SELF_REGISTRATION`）启动失败；关掉注册后不再拦，但仍不要这样配置。也不得含测试的 `QB_E2E_*`（正式模式下启动失败） |
| `QB_APP_SELF_REGISTRATION` / `QB_SPONSOR_SELF_REGISTRATION` | 否 | 缺省 `true` | — | — |
| `QB_APP_EMAIL_LINK` | 否 | 缺省 `true` | — | App 以邮件中的链接登录（Q15 的分享链接，平台 D54）；关掉后分享页仍显示，但只能用密码登录 |
| `JABIZ_PUBLIC_ENABLED` | 否 | 缺省 `true`（Q7b） | — | 设 `false` 时 `/api/public/**` 一律 404：帮助中心与条款、隐私政策页（App 与发起方页面，也给未登录的人看）都打不开。只有四个公开模板 `qb.public.*`（已发布的法律文本版本与已发布的 FAQ），没有公开的文件；按客户端地址限流（平台缺省每分钟 300 次），结果缓存 5 分钟 |

- `JABIZ_WEB_ENVIRONMENT`（平台 16zd）：页面上的系统标志。只有正式环境设 `production`；其他值（`staging`、`local`，不设即 `test`）让 App、发起方页面与平台管理系统的
  **每一页**（含登录页）顶部显示警示色横条 "<环境名> · 测试环境，不是正式版"、标题前加 `[<环境名>]`。设置来自各环境的 `.env`（`deploy/quizbuks/DEPLOY.md`），
  deploy workflow 部署前检查 production 有这一行、staging 没有写 production。

### 1.2 邮件与登录

| 变量 | 必需 | 来源 | 轮换 | 缺少时 |
|---|---|---|---|---|
| `JABIZ_MAIL_ENABLED` | 是（`true`） | — | — | 邮件只记为 `SKIPPED`：注册的人永远不能验证邮箱、找回密码不可用（启动告警） |
| `JABIZ_MAIL_FROM` | 是 | 发件域（配好 SPF / DKIM / DMARC） | — | 邮件发不出 |
| `JABIZ_MAIL_BASE_URL` | 是 | 主机根地址 `https://<主机>`（不带 `/admin`） | 换域名时 | 邮件中的链接错误；也是发起方页面地址的缺省来源 |
| `SPRING_MAIL_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | 是 | 邮件服务商（SMTP） | 按服务商；改环境变量、重启 | 投递失败 |
| `SPRING_PROFILES_ACTIVE` | 否 | `google` 开启 App 与发起方页面的 Google 登录，`social` 开启 Google、Facebook 与 LINE（Q40），`apple` 开启这三家与 Apple（平台 17j），只开一个。**绝不能含 `dev`**（`dev` 的签名密钥与操作人请求头是公开的） | — | 无 Google 登录 |
| `JABIZ_OIDC_GOOGLE_CLIENT_ID` / `_CLIENT_SECRET` | 开 Google 时 | Google Cloud 控制台 | 在控制台建新密钥、改环境变量、重启、删旧密钥 | Google 登录启动失败 |
| `JABIZ_OIDC_FACEBOOK_CLIENT_ID` / `_CLIENT_SECRET` | 开 `social` 时 | Meta for Developers | 在控制台重置应用密钥、改环境变量、重启（重置即旧密钥失效，换的那一刻 Facebook 登录失败） | 启动失败 |
| `JABIZ_OIDC_LINE_CLIENT_ID` / `_CLIENT_SECRET` | 开 `social` 时 | LINE Developers | 在控制台签发新 Channel secret、改环境变量、重启（旧的立即失效；它也是 ID 令牌的签名密钥） | 启动失败（不足 32 字节同样） |
| `JABIZ_OIDC_APPLE_CLIENT_ID` / `_TEAM_ID` / `_KEY_ID` / `_PRIVATE_KEY` | 开 `apple` 时 | Apple Developer（Services ID；Keys 中勾选 Sign in with Apple 的密钥，`.p8` 只能下载一次） | 建一把新密钥、改 `_KEY_ID` 与 `_PRIVATE_KEY`、重启，确认 Apple 登录可用后撤销旧密钥（客户端密钥由平台每小时以它签出，换的那一刻不中断） | 部署前的检查停止部署；格式不对即启动失败 |
| `QB_OIDC_APPLE_FORM_POST` | 开 `apple` 时 | `https://<主机>/api/auth/oidc/apple/form-post`（与 Services ID 的 Return URL 完全相同） | 换域名时（Apple 中同时改） | Apple 拒绝回传 |
| `JABIZ_PUSH_PUBLIC_KEY` / `_PRIVATE_KEY` / `_SUBJECT` | 开 `push` 时（平台 17k） | 生成一次（README"推送通知"）；私钥**另存离线副本** | 不要换：换了密钥对，学者在各设备上要重新开启推送（旧订阅随之失效） | 部署前的检查停止部署；两把不是一对即启动失败 |
| `JABIZ_APNS_TEAM_ID` / `_KEY_ID` / `_PRIVATE_KEY` | 开 `apns` 时（平台 17k-b） | Apple Developer 的 Keys（勾选 APNs 的密钥，`.p8` 只能下载一次） | 建一把新密钥、改 `_KEY_ID` 与 `_PRIVATE_KEY`、重启，确认 iPhone 收到推送后撤销旧密钥（设备令牌不受影响） | 部署前的检查停止部署；格式不对即启动失败；Apple 拒绝令牌时只记警告、推送稍后重试 |
| `QB_OIDC_REDIRECT_APP` | 开 Google 时 | `https://<主机>/taker/login/oidc`（与控制台登记的完全相同；Q30 之前没有 `/taker`） | 换域名时 | Google 拒绝回调 |
| `QB_OIDC_REDIRECT_SPONSOR` | 开 Google 时 | `https://<主机>/sponsor/login/oidc`（Q37；与控制台登记的完全相同） | 换域名时 | Google 拒绝回调；缺少即不能启动 |
| `JABIZ_WEB_ROOT_REDIRECT` | 否 | 裸域名转到的路径，缺省 `/biz/`（招商网站，Q30）；`QB_APP_LINK` 须以 `/taker/` 结尾 | — | 裸域名打开招商站 |
| `JABIZ_SECURITY_MFA_ADMINISTRATION` | **不设** | — | — | 缺省 `true`：管理操作要求二次验证。`false` 只给本机演示、e2e 与 Stripe 测试模式的本机运行 |

**应用的邮件**（Q7a，`plans/Q7-messages-legal.md` D-Q7-1）：除注册、验证、找回密码与入驻结果外，QuizBuks 在以下事件后发信（都由事件的消费者以系统身份发出，不在答题与支付的提交路径上）：

| 收件人 | 邮件（模板） | 可退订 |
|---|---|---|
| 学者 | 转账已发出 `qb.transfer.sent`、转账失败 `qb.transfer.failed`（只在转账仍是该状态时发）、收款账户需补资料 `qb.payout.action-needed`（每人 24 小时内至多一封） | 否 |
| 发起方 | 充值到账 `qb.topup.paid`、发布通过 / 驳回 / 被下架 `qb.publication.approved` / `.rejected` / `.taken-down` | 否 |
| 发起方 | 发布预算不足 `qb.publication.funds-short`（每个发起方 24 小时内至多一封，列出当时全部预算不足的发布；窗口内的再次不足记为待发，由每小时 11 分的任务 `qb.mail-funds-short-due` 在窗口过后仍不足时补发）、发布已结束 `qb.publication.ended`（下架后或发起方被封禁时不发） | 是（邮件中的链接，或发起方页面"账户 → 邮件"） |

邮件中的时刻按日本时间、标为 `JST`（取自 `jabiz.fiscal-year-zone`）。`JABIZ_MAIL_ENABLED` 未开时这些邮件同样只记为 `SKIPPED`（`sys_mail_message` / `sys_mail_attempt`），不会补发。事件太晚才被消费（投递积压、或新消费者收到历史事件）时不发信：通知（可退订的两种）超过 3 天、事务邮件（转账、收款账户、充值、发布的审核结果与下架）超过 14 天（`NotificationMails.MAX_AGE` / `TRANSACTIONAL_MAX_AGE`）。补发任务的时间可以 `quizbuks.mail.funds-short-due-cron`（Spring 的六段 cron，缺省 `0 11 * * * *`，UTC）改；它只读待发的行（`qb_mail_notice` 的部分索引）。

**退订链接**：可退订邮件中的退订链接由平台生成，指向邮件所属入口自己的前端（平台 D52 第 3 条，`jabiz.mail.unsubscribe-paths`）：发起方的两种通知（`.entries("sponsor")`）指向 `/sponsor/mail/unsubscribe`，欢迎邮件（`.entries("app")`）指向 `/mail/unsubscribe`，两个 SPA 都挂载平台的退订页（无需登录：链接中的令牌即凭证）。学者与发起方因此不需要打开 `/admin/`；平台管理系统的旧页 `/admin/mail/unsubscribe` 保留，此前发出的链接照常有效（`/admin/` 是否对外开放只取决于管理员从哪里登录）。

### 1.3 Stripe 与资金（`deploy/quizbuks/README.md`"充值与核对""收款账户与自动转账"）

| 变量 | 必需 | 来源 | 轮换 | 缺少时 |
|---|---|---|---|---|
| `STRIPE_API_KEY` | 是 | Stripe Dashboard → 开发者 → API 密钥：**受限密钥**（`rk_live_…`），只开 Checkout Sessions 写、PaymentIntents 读、Transfers 读写、Accounts 与 Account Links 写 | 在 Stripe Dashboard "滚动"受限密钥（可设旧密钥的过期时间），改环境变量、重启，再让旧密钥过期。泄露时立即滚动 | 启动失败（模式不符也失败，`StripeModeCheck`） |
| `STRIPE_WEBHOOK_SECRET` | 是 | 账户端点 `/api/inbound/stripe` 的签名密钥 | 在 Stripe Dashboard 滚动：新旧同时有效一段时间，期间配 `JABIZ_INBOUNDWEBHOOKS_PROVIDERS_STRIPE_SECRETS_0`（新）与 `_1`（旧），过期后删旧的 | 启动失败 |
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
| `SHOPIFY_API_KEY` / `SHOPIFY_API_SECRET` | 是（S1，QuizBuks for Shopify） | Shopify Partner → 应用 → 客户端凭据（Client ID / Client secret）；staging 与正式各用自己的应用 | 在 Partner Dashboard 轮换密钥：新密钥生效后会话令牌与 webhook 都以它签名，先改环境变量并重启（平台的提供方与入站 webhook 都只认一把，S1 不支持两把并存，轮换时有短暂的拒绝） | 启动失败（SECURITY / INBOUND）；还没有 Shopify 应用时设占位（Shopify 从不以它签名，所以不会有店铺登录或 webhook） |

### 1.3a 银行转账充值（Q13a，`plans/Q13-bank-transfer.md`）

不需要环境变量。开启的步骤（都在平台管理系统，四眼）：

1. 新版本部署后运行一次 `QB_SETUP`：给角色加上 `qb.topup.bank.record` / `qb.topup.bank.approve`，并提出审批规则 `qb.topup-bank.review` 与新的受控参数；**另一位管理员发布这些提议**（审批规则生效之前，财务登记一律被拒 `QB_TOPUP_BANK_NO_REVIEW`）。
2. 以受控变更设收款账户：`qb.bank.receiving.bank`、`.branch`（名称带代码）、`.account-type`、`.account-number`、`.account-holder`（片假名），以及 `qb.topup.bank.min` / `max`。五项都设了之前，发起方的银行转账充值一律被拒（`QB_TOPUP_BANK_UNAVAILABLE`）。
3. 日常：发起方报告汇款后财务会收到待办；在平台管理系统"银行转账充值"中对照银行流水登记实收、到账日与流水号；另一位管理员（内容或财务）在同一页审批后才入账。登记人不能审批自己的登记。驳回会回到登记前的状态，可重新登记。
4. 30 天没有到账的申请由每日任务（19:23 UTC）置为过期；之后才到的钱仍可在"已过期"的申请上登记。

### 1.3b 自动转账的开启与"立即转账"（Q14）

自动转账缺省关闭。开启：以受控变更设 `qb.transfer.enabled = true`（以及门槛、手续费、审核额），另一位管理员发布。
收款人须已在 App 中开通 Stripe 收款账户并为 `ENABLED`。平台管理系统用户页的"收款状态"给出此刻不转的原因（开关关闭、没有账户、账户未启用、低于门槛、暂停等）；
持有 `qb.transfer.now` 的财务可以"立即转账"（同样的检查，不能转给自己；超过审核额的由每日任务提出审批）。

### 1.3c 银行提现（Q13b）

不需要环境变量。开启：部署后运行 `QB_SETUP`（财务角色加 `qb.payout.bank.reveal` / `qb.withdrawal.pay`，并提出 `qb.withdrawal.bank.enabled` / `.fee` / `.cooldown-hours`），另一位管理员发布；
再以受控变更设 `qb.withdrawal.bank.enabled = true` 与手续费。转账审核规则 `qb.transfer.review` 须生效（每笔银行提现都要审核）。

日常：学者申请后，持有 `qb.payout.review` 的财务（不是本人）在转账审核中批准；批准后有"请打款"待办。在平台管理系统"银行提现"中点"显示账号"（每次记录），
在网银中打款，再点"已打款"填流水日与流水号；被银行退回时点"退回"填原因，Kudos 回到学者余额。**不要**在库中改状态。

### 1.4 只给测试的开关（生产不设）

正式模式（或正式密钥）下设了即**启动失败**的：

| 开关 | 用途 | 由谁拒绝 |
|---|---|---|
| `QB_TRANSFER_RUN_CRON`、`QB_RECONCILE_RUN_CRON` | e2e 与 Stripe 测试模式：每分钟转账、每分钟核对 | `TransferScheduleCheck` |
| `QB_ANALYTICS_RUN_CRON` | e2e：每分钟运行发起方分析（Q9e2）。**隐私控制**：概览是任务的快照，频率决定能把一个学者的作答定位到多细；缺省（每 10 分钟）以外的值**任何环境**都要同时设 `QB_E2E_ENABLED=true`，否则启动失败；**比每分钟更频繁的值一律拒绝**（评审 M1） | `TransferScheduleCheck` |
| `QB_E2E_ENABLED`（`quizbuks.e2e.enabled`） | e2e 的开关（Q9e2 评审 M1）：允许改分析任务的时间；正式模式或正式密钥下设了即启动失败 | `TransferScheduleCheck` |
| `QB_ADMIN_ROLES` 中的 `QB_E2E_*` | e2e 的无二次验证的测试角色 | `TransferScheduleCheck` |
| 开发入账 `quizbuks.wallet.dev-credit.enabled`（`dev` 配置开启） | 不付款给发起方钱包入账 | `StripeModeCheck` |
| `QB_STRIPE_CHECKOUT_URL_PREFIX` / `QB_STRIPE_CONNECT_URL_PREFIX` 不是 Stripe 的地址 | 指向本地桩 | `StripeSettings` / `PayoutSettings`（启动检查） |

不会被拒、但生产**绝不能**设的：`JABIZ_MAIL_SENDERS_TRUST_ALL_FOR_TESTS=true`（e2e：接受本机上任何端口与证书的发起方邮件服务器；非 dev 启动告警，Q29）、`JABIZ_SECURITY_MFA_ADMINISTRATION=false`、`JABIZ_SECURITY_SIGN_UP_RATE_LIMIT_PER_ADDRESS`（e2e 放宽的注册限流）、`JABIZ_SECURITY_SIGN_IN_RATE_LIMIT_PER_USER_ADDRESS` / `JABIZ_SECURITY_SIGN_IN_RATE_LIMIT_USER_BURST`（e2e 放宽的登录限流）、`QB_STRIPE_API_URL` / `QB_STRIPE_API_HOSTS`（指向桩）、`SPRING_PROFILES_ACTIVE` 含 `dev`、`QB_STM_PUBLISHER` / `QB_DEMO_PUBLISHER` 等演示与测试的角色。上线检查清单逐项核对。

### 1.5 可观测性

| 变量 | 说明 |
|---|---|
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | 追踪（如 `http://otel-collector:4318/v1/traces`） |
| `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true`、`MANAGEMENT_OTLP_METRICS_EXPORT_URL` | 指标（`…/v1/metrics`） |
| `MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT` | 日志 |
| `COMPOSE_PROFILES=observability` | 启动本环境的 LGTM（`deploy/quizbuks/server/compose.yaml` 的 `lgtm`）；上面三项即 `http://lgtm:4318/v1/traces`、`…/v1/metrics`、`…/v1/logs` |
| `JABIZ_ALERT_EMAIL`、`GF_SMTP_ENABLED=true`、`GF_SMTP_HOST`、`GF_SMTP_USER`、`GF_SMTP_PASSWORD`、`GF_SMTP_FROM_ADDRESS` | 告警的收件人（值班的人，几个以 `;` 分隔）与 Grafana 发信用的 SMTP（§5） |
| `JABIZ_ALERT_*` 的阈值 | 缺省见 compose（平台 13 §7）；QuizBuks 的 `JABIZ_ALERT_QB_AI_HELD_MAX`（§5"AI 作业暂停"）、`JABIZ_ALERT_QB_HELP_USAGE_PERCENT`（§5"AI 问题解答的用量"，缺省 80） |
| `QB_GRAFANA_PORT` | Grafana 在服务器 `127.0.0.1` 上的端口（缺省 3000；staging 与 production 在同一台服务器上时各用一个），经 SSH 隧道访问 |

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

**配置与操作见 `deploy/quizbuks/DEPLOY.md`"备份"（pgBackRest + Cloudflare R2，WAL 归档；上传文件 restic；死人开关）。** 下面只是要求：

- **连续归档 + 定期基础备份**：WAL 归档（`archive_mode on`，用 pgBackRest、WAL-G，或托管服务的时间点恢复），基础备份每天一次，保留至少 14 天的时间点恢复窗口；另按会计要求保留月末的全量备份（凭证与分录是 10 年，L-4）。
- **一起备份的**：上传文件卷（`/var/lib/jabiz/files`：与库同一时刻的快照或每日同步）、`JABIZ_INTEGRITY_KEY`、`JABIZ_MFA_KEY` 与 `JABIZ_SEAL_KEY` 的离线副本（**不与备份放在一起**：拿到备份的人不应同时拿到密钥）。生成的文件（导出 CSV、收据 PDF）在库中（`sys_generated_file`、`sys_document_run`），随库备份。
- 备份加密、异地（另一区域或账户）、只追加或带对象锁，防误删与勒索。
- 监控：最近一次成功的基础备份与 WAL 归档的延迟（§5）。
- **采用的做法**（需求方 2026-11-07 决定）：pgBackRest + Cloudflare R2（两个仓库：14 天时间点恢复、月末全量 10 年，都由 pgBackRest 加密），上传文件以 restic 每小时备份到同一个桶；
  失败发邮件给值班，另可接死人开关服务。设置、首次启用、回退与恢复演练的步骤见 `deploy/quizbuks/DEPLOY.md`"备份的做法"。

### 2.4 恢复演练（每季度，上线前一次）

在**隔离的环境**中做（不能连 Stripe 正式环境、不能发邮件，否则恢复出的实例会转账、发信）。第 2、3 步由平台的脚本 `tools/ops/restore-drill.sh` 完成（平台 13 §8：
应用以核对模式启动一次，邮件、推送、定时任务、事件投递与外部作业一律关闭，只监听本机回环地址，核对完即停止，并写出演练记录）。

1. 选一个时刻 T（如昨天 12:00 UTC），按 `deploy/quizbuks/DEPLOY.md`"备份"中的"C. 恢复演练"把备份恢复到新的 PostgreSQL 实例（时间点恢复到 T），文件卷恢复到同一时刻；记录用时。
   恢复命令可以交给脚本计时（`DRILL_RESTORE_COMMAND`，即 DEPLOY.md"C. 恢复演练"中的 pgBackRest 恢复命令）。
2. 准备副本的设置文件 `DRILL_ENV_FILE`：指向恢复的库（`SPRING_R2DBC_*`、`SPRING_FLYWAY_*`）与恢复的文件目录（`JABIZ_FILES_LOCAL_ROOT`）；
   `JABIZ_JWT_SECRET`、`JABIZ_INTEGRITY_KEY`、`JABIZ_MFA_KEY`、`JABIZ_SEAL_KEY` 用生产的值（从离线副本取）；让副本远离线上服务：`QB_STRIPE_LIVEMODE=false`、
   `STRIPE_API_KEY=sk_test_restore_drill`、两个签名密钥随机生成、`JABIZ_MAIL_ENABLED=false`（正式数据在测试模式下启动不被拒绝；反过来"正式模式 + 测试数据"才被拒）。
   `DRILL_DOCKER_ARGS` 给出只通向恢复的库的内部网络（网络上禁止对外连接）。
3. 以生产同版本的镜像运行脚本；**上线前的那一次加 `DRILL_ROTATE=true`**（在同一副本上演练密钥轮换：新密钥 + 生产密钥作旧密钥并重新加密到底，再以新密钥单独核对；D78，`docs/guide/key-rotation.md`）：

   ```sh
   DRILL_TARGET_TIME=2026-11-06T12:00:00Z DRILL_ENV_FILE=drill.env \
   DRILL_IMAGE=ghcr.io/staunch0515/jabiz-quizbuks:<生产的提交> DRILL_DOCKER_ARGS="--network drill-internal" \
   DRILL_TEMPLATES=qb.admin.verify-unbalanced-periods,qb.admin.verify-overdrawn-wallets,qb.system.recon-totals \
   DRILL_ROTATE=true tools/ops/restore-drill.sh
   ```

   平台的核对：启动检查通过、最后的操作不晚于 T（恢复点）、`INTEGRITY_VERIFY`（`JABIZ_INTEGRITY_KEY` 正确）、每个二次验证能解开（`JABIZ_MFA_KEY` 正确）、
   声明的密封值能解开（`JABIZ_SEAL_KEY` 正确，F-F14）、每个登记的文件都在（文件卷与库一致）。QuizBuks 自己的核对是 `DRILL_TEMPLATES` 中的模板，**只在有问题时返回行**：

   | 模板 | 返回的行 |
   |---|---|
   | `qb.admin.verify-unbalanced-periods` | 借贷不相等的月份（按记账时间，UTC） |
   | `qb.admin.verify-overdrawn-wallets` | 账本中余额为负的钱包（2110、2120、2130、2160 按对象） |
   | `qb.system.recon-totals` | 2110 合计与发起方钱包余额合计之差（`TOTAL_2110`）、2160 与现金账户（`TOTAL_2160`）、账本合计不为 0（`LEDGER_ZERO`）；与每日核对的同一模板 |

   脚本的退出码为 0 即全部通过；它写出的 Markdown（`restore-drill-<日期>.md`：T、恢复与核对的用时即 RTO、恢复点即 RPO、每项核对的结果）就是演练记录。
4. 与生产在 T 时的数字比较（T 之前已完成的每日核对 `qb.admin.recon-runs`、T 所在月的 `qb.admin.balances`）：生产上以 `knownAt` = T 运行月报；
   副本上以第 2 步的设置另加 `JABIZ_JOBS_SCHEDULER_ENABLED=false`、`JABIZ_EVENTS_DELIVERY_ENABLED=false`、`JABIZ_EXTERNALJOBS_EXECUTOR_ENABLED=false` 以普通模式启动（同样隔离）运行同一报表
   （演练过轮换的副本用新密钥）。差异记入演练记录的"Problems and follow-ups"。
5. 演练记录存档（日期、T、RTO、RPO、问题），删除隔离环境。

## 3. 首次启动

1. 准备 §1 的全部变量（`QB_STRIPE_PLACEHOLDERS=false`）、HTTPS 的反向代理（`JABIZ_TRUSTED_PROXIES`）、邮件。先以**测试模式**启动（§4 之前），确认 `docker compose … logs app` 中没有 `Platform check failed`。
2. 以 `JABIZ_BOOTSTRAP_ADMIN_USER` 登录 `/admin/`，在"安全设置"中**开启两步验证**（TOTP），保存恢复码。随后从环境中删去 `JABIZ_BOOTSTRAP_ADMIN_USER` 与 `JABIZ_BOOTSTRAP_ADMIN_PASSWORD` 并设 `QB_BOOTSTRAP_ADMIN_DONE=true`（密码与这一行都没有时镜像的入口脚本拒绝启动；共用服务器上的步骤见 `deploy/quizbuks/DEPLOY.md`）。
3. 执行 `QB_SETUP`（流程页面；要求二次验证）：建角色、账本科目、国家、业务参数，并把受控参数与审批规则作为**提案**提出。
4. 首个管理员建**财务管理员**（`QB_ADMIN_FINANCE`，持有 `control.publish`）与**内容管理员**（`QB_ADMIN_CONTENT`）：`SEC_USER_CREATE` 时填用户名与本人的邮箱、**勾选 `invite`、不填密码**，
   再以 `SEC_USER_ROLE_ASSIGN` 分配角色；本人按邀请邮件中的链接（72 小时内有效）设定密码，用邮箱登录、开启两步验证。链接过期或邮件没收到时，在平台管理系统用户列表对该行执行"发送邀请"（`SEC_USER_INVITE`）再寄一封。
   给管理员角色设"要求二次验证"（`SecRole.requireMfa`），使未绑定的管理员无法登录。各自一人一号、各自二次验证；不共用账号。
   这两种角色不持有改权限的权限（`security.user-role.write` / `security.role.write`），平台不把它们算作管理员（D51），建立期内的分配当即生效。
   **建管理员账号时不设密码，用邀请**（平台 17o，D63）：管理员填写的邮箱在本人证明之前不收"忘记密码"的邮件，所以不能靠"忘记密码"；邀请的链接同时证明邮箱、设定本人的密码。
   建立期结束后，平台不把受保护的权限给一个由别人设过密码的账号（批准后生效时 `TARGET_CREDENTIALS_NOT_OWN`，本人之后自己改过也不算；平台 10 §16.2），以后给这些角色增加受保护的权限也会因此失败。
   邀请要求邮件已开启（`JABIZ_MAIL_ENABLED=true`，否则 422 `MAIL_DISABLED`），只寄到建号时填写、之后没改过的邮箱：邮箱填错时重新建号。
   已用临时密码建好、尚未经邮件设定密码的管理员账号，同样对其执行"发送邀请"（替换已有密码另需 `security.user.password`，首个管理员持有）。
5. 财务管理员在平台管理系统**发布** `QB_SETUP` 的提案（`CONTROL_CHANGE_PUBLISH`，四眼：提出人不能发布自己的），**包括两条职责分离规则**。发布之前发起方入驻与提交发布一律被拒。
   操作：平台管理系统"数据 → SysControlChange"列出提案，在状态为待发布（`PROPOSED`）的一行执行行操作 `CONTROL_CHANGE_PUBLISH`。财务角色以 `approval.read` 读这个视图；
   这项权限在 2026-10-29 才加入财务角色，此前建好的系统要由超级管理员再执行一次 `QB_SETUP` 补上（不是受保护的权限，当即生效），财务管理员重新登录后才看得到。
   职责分离规则须在建立期结束（下一步）之前发布：之后任命内容或财务管理员要由一位超级管理员审批，而超级管理员不持有、也不能持有 `approval.decide` 与 `control.publish`，
   平台只按**已发布的** `qb.sod.security-administration` 免去对这两项的覆盖要求（D51 修订 H1）。
   然后建**两位超级管理员**（`QB_ADMIN_SUPER`，各自一人一号、各自二次验证）：首个管理员建第一位（此后首个管理员退役，不能再做受保护的变更，403 `BOOTSTRAP_ADMIN_RETIRED`），
   **第一位超级管理员建第二位**（仍在建立期，当即生效）。第二位出现后建立期结束（平台 16u，D51），此后的受保护变更（见本节末"受保护的授权"）由一位超级管理员申请、另一位审批。
   `QB_SETUP` 必须在第二位超级管理员出现**之前**执行过（第 3 步）：超级管理员的角色由它得到审批受保护变更的 `security.protected-change.decide`，这项权限本身受保护，建立期结束后再给需要审批，而那时无人能批。
   **默认运营商**（F-S1）：提案发布后，超级管理员（已验证邮箱，持 `qb.admin.operators.write`）在 答题拼好价平台管理系统中以 `QB_OPERATOR_SAVE` 建第一个运营商并设为默认（`defaultForNew: true`），同时指定负责人（已验证邮箱的账号，同一事务成为它的直营发起方）。没有默认运营商时发起方入驻一律被拒（`QB_OPERATOR_DEFAULT_MISSING`），每日核对 `qb.operator-check` 给平台一个待办（`NO_DEFAULT_OPERATOR`）。以后把另一个运营商设为默认即取代原来的（至多一个；默认的运营商不能停用，也不能直接取消默认）。
6. 由超级管理员提出、财务管理员发布（`CONTROL_CHANGE_PROPOSE` → `CONTROL_CHANGE_PUBLISH`，目标 `SysParam`）：
   - **收据开具方** `qb.issuer.name`、`qb.issuer.address`、`qb.issuer.registration-no`（登记号与税的措辞须财务、法务确认，上线检查清单）；未设名称时发起方开不出收据；
   - **科目映射** `qb.journal.account.1110 … 5110`（会计系统中的科目代码；平台管理系统"QuizBuks 财务 → 分录导出 → 科目映射"只读显示，修改走受控变更）；没映射的科目使分录导出被拒；
   - 金额参数按业务确认：`qb.creator-fee-rate`、`qb.transfer.threshold`、`qb.transfer.review-above`、`qb.transfer.fee`、`qb.topup.min` / `max`；
   - `qb.transfer.enabled` **保持 false**，直到取得法务意见（§4）；
   - **奖励的缺省**（Q28）：`qb.reward.default-methods`（没有单独设定的发起方可用的支付方式，逗号分隔；声明值 `PLATFORM`，推广期发布 `OFFLINE_REDEEM`，见下文"阶段一的设置"）与 `qb.reward.max-per-attempt`（每次答题可得的最高奖励，Kudos；`0` 不限；景品表示法的上限由法务给出）；
   - 法律文本的最长预告期 `qb.legal.max-announce-days`（缺省 180 天；未发布时同样按 180 天）。
   - `qb.ai.enabled` 由 `QB_SETUP` 提出 `false`，**照样发布**（AI 在上线检查清单的条件满足后才开启）。发布之前参数不存在：启动检查 `EXTERNAL` 对 `job qb.ai.questions` 与 `job qb.ai.cover` 各报一条**警告**
     `enabledBy: parameter qb.ai.enabled has no value in effect now; the type is paused …`，执行器第一次轮询时也记一条 WARN。这在新安装上是预期的（参数缺失与 `false` 一样：请求被拒、没有作业），
     发布 `false` 之后不再出现；已发布之后仍出现即参数被删或读不出来，应调查（§5"AI 作业暂停"）。
7. 在测试模式下做一次 Stripe 测试模式全流程（`stripe-test-mode.md`），并按 §2.4 做一次恢复演练。
8. 每次部署新版本后：再执行一次 `QB_SETUP`，发布它的新提案（README 各阶段的"部署后"）。忘了的话，启动日志与 `platformCheck` 对每个没有生效值的业务参数各报一条警告 `QUIZBUKS | parameter <键>`（从未执行过设置的数据库只报一条 `QB_SETUP has not run yet`）；读这些参数的流程在那之前一律失败（`PARAM_NOT_FOUND`）。
9. **条款、隐私政策与帮助**（Q7b）：`QB_SETUP` 为四种法律文本（学者条款、发起方条款、隐私政策、特定商取引法表记）各建一份**明确标注 `[TEST PLACEHOLDER]` 的草稿**，什么也不发布。
   正式文本由需求方或律师提供（日文为准据文本，英文须一并提供；中文可缺，页面显示英文并注明）。内容管理员在平台管理系统的"法律文本（草稿）"中把草稿换成正式文本（标题、正文，第二版起还有"改定的概要"，日英必填），
   再在管理系统扩展的"QuizBuks → 规约类"页（`/qb/legal`）预览草稿并"发布"（`QB_LEGAL_PUBLISH`，每次要求二次验证，留审计；发布的是页面读到的草稿版本，期间被改即拒绝）：生效时间留空即立即生效，或填以后的时间作预告（至多 `qb.legal.max-announce-days` 天；预告期间不能再发布同一种文本）；
   预告有误时在同一页的版本列表中填理由"撤回"（`QB_LEGAL_WITHDRAW`，只能在生效前；撤回的版本永不生效、不要求同意，版本号不再使用，公开的历史中标为"已撤回"）；
   实质性变更勾选"须重新同意"，措辞修订不勾。已发布的版本不可修改，改错只能发布新版本。正式模式下缺少已生效的学者条款、发起方条款或隐私政策时**启动告警**（`LiveModeDataCheck`）。
   帮助中心的问答在平台管理系统"帮助中心问答"中维护（英语必填、`seq` 排序、"公开中"才出现在页面）。支持邮箱 `qb.support.email`（普通参数，空即不显示"仍需帮助"的联系方式）在平台管理系统参数中设；Q41 起 `QB_SETUP` 新建它时为 `quizbuks@gmail.com`，已建过的（空）不会被改，要在平台管理系统参数中改一次。
   发布条款时可勾选"在收件箱中公告"：一分钟内（任务 `qb.broadcast-dispatch`）给相关的人发一条系统公告（学者条款 → App，隐私政策 → App 与发起方页面，其余 → 发起方页面），有效 30 天。
10. **站内信与公告**（Q7c）：个人消息（奖励、转账、收款账户）由事件的消费者写入，不需设置；公告在管理系统扩展的"QuizBuks → 公告与通知"（`/qb/broadcasts`）编写与发送（发送要求二次验证，撤回每次二次验证并填理由，发起方可见）。
   普通参数（`QB_SETUP` 建立，可在平台管理系统参数中直接改）：发起方每个发布的通知上限 `qb.broadcast.sponsor.max-per-publication`（3）、两条之间的最短间隔 `qb.broadcast.sponsor.min-interval-hours`（24）、
   公告的缺省有效期 `qb.broadcast.expires-days`（30）、收款提醒的间隔 `qb.payout.reminder-days`（30）。发起方通知不预审（Q7-10）：内容有问题时由内容管理员撤回；封禁发起方即撤回其全部通知。
   任务：`qb.broadcast-dispatch`（每分钟，定时发送；`quizbuks.messaging.dispatch-cron`）、`qb.broadcast-expire`（每分钟第 30 秒，把过期的与发布已结束的通知移出收件箱；`quizbuks.messaging.expire-cron`；停了它，过期的公告与已结束发布的通知会留在收件箱中直到它恢复，读取也随之变慢；恢复后一次补做）、
   `qb.broadcast-stats`（每日 04:47 UTC，发起方通知统计的快照；`quizbuks.messaging.stats-cron`）、`qb.payout-reminder`（每日 03:17 UTC；`quizbuks.messaging.payout-reminder-cron`）。
   发起方看到的统计的下限 `qb.broadcast.stats.min-audience`（50，普通参数）：到达不足它时不显示点击与开始答题（评审 M2）。
   限流（`application.yml` 的 `jabiz.process.rate-limits`）：已读与点击每人每分钟 120 次、全部已读与推广开关各 30 次、发起方保存通知 60 次、发送每小时 10 次。
   对话（Q82，`plans/Q82-conversations.md`）：任务 `qb.conversation-expire`（每日 03:41 UTC，关闭 `qb.conversation.close-days`（90，普通参数）天无消息的对话；`quizbuks.conversations.expire-cron`；停了它只是安静的对话不自动关闭，恢复后一次补做）；每侧每对话每日条数 `qb.conversation.daily-cap`（20，普通参数）；限流：发送每人每小时 30 次、开启 10 次。发起方的回复经消费者 `qb.msg-conversation-reply` 写站内信并推送。对话内容的纠纷由内容管理员（与运营商，限其发起方）在管理系统扩展的举报页经“查看对话”只读查看。

**阶段一（推广期）的设置（Q28，`plans/Q28-phase1-defaults.md`）**：推广期只有线下奖品、发起方免费、不经手资金（招商网站 §7.11 的承诺）。由超级管理员提出、财务管理员发布（都是受控参数，四眼），按顺序：

- [ ] `qb.reward.default-methods` = `OFFLINE_REDEEM`：没有单独设定的发起方只能以线下奖品作奖励；发起方页面随之不显示钱包与充值。要让某个发起方付 Kudos 或现金，在平台管理系统"发起方审核"页单独允许（`QB_SPONSOR_REWARD_METHODS_SET`，单个管理员）。
- [ ] `qb.reward.max-per-attempt` = 法务给出的上限（Kudos 等值）。按答对与否给奖是景品表示法的"懸賞"而不是"総付"：与购买或到店挂钩的一般懸賞，单个景品至多交易额的 20 倍（交易额不足 5,000 日元）或 10 万日元（5,000 日元以上），总额不超过预计销售额的 2%；不要求购买的开放式懸賞没有上限（填 `0`）。线下兑换是否算与购买挂钩由法务认定。高于它的奖励档在保存时报 `QB_REWARD_ABOVE_MAX`、提交被拒。
- [ ] `qb.review.sponsor.auto` = `true`、`qb.review.publication.auto` = **`false`**：发起方的入驻在推广期不逐个人工审核（线下奖品不经手资金；封禁照常可用），**发布一律在发布前审核**（需求方 2026-10-28，商店 App 的上架要求，`plans/Q49d-store.md` D-Q49d-7）。
  审核员拒绝以酒类、烟草或毒品为题材或奖品的问卷（商店的内容分级按"没有受管制物质"申报，`docs/quizbuks/store/play-content-rating.md`）。
- [ ] `qb.transfer.enabled` 保持 `false`、`qb.ai.enabled` 按上线检查清单。
- [ ] 开始收费（招商网站价格页的"计划中"项）前：把 `qb.reward.default-methods` 改为所售套餐的方式，发布 `qb.creator-fee-rate`，并在网站去掉"计划中"。

**受保护的授权（平台 16r / 16u，决策 D48、D51；10 §16）**：分配或增加 `security.user-role.write`、`security.role.write`、`security.protected-change.decide`、`approval.decide`、`control.publish`、`ledger.*`、
`platform.param.write`、`*`，以及对持有它们的用户重设密码、二次验证、邮箱或关联外部账号，平台要求**另一位管理员审批**——但只在建立期结束之后。
平台按管理员的权限（`security.user-role.write`、`security.role.write`，`administrator-permissions` 的缺省值）数管理员：QuizBuks 的管理员就是 `QB_ADMIN_SUPER` 的持有人。
首个管理员之外出现一位，首个管理员即退役；出现**两位**，建立期即结束（永不回退）。所以 QuizBuks 需要**至少两位超级管理员**，按上面第 4–5 步的顺序建立，并始终保持两位在职
（只剩一位时没有人能批准第二位；出路同平台 10 §16.3：运维以 `JABIZ_SECURITY_PROTECTEDGRANTS_ENABLED=false` 启动一次、建好后改回，记录在变更记录中）。
- **审批**：待审批的变更出现在平台管理系统"待办"中（平台的 `ApprovalPanel`，`APPROVAL_DECIDE`），由另一位超级管理员以 `security.protected-change.decide` 决定（要求近期二次验证；申请人与所针对的用户不能批；
  `approval.read` 让超级管理员读到请求的事实）。超级管理员不持有 `approval.decide`，不能决定业务审批（入驻、发布、大额转账），这些仍由内容与财务管理员决定。
- **新的管理员账号**：建立期结束后任命的人，其账号的密码须由本人设定（建号时勾选 `invite`、不设密码，本人经邀请邮件设定），否则批准后不生效（`TARGET_CREDENTIALS_NOT_OWN`）。
  建立期结束后不能邀请已持有受保护权限的人（422 `PROTECTED_CHANGE_REQUIRES_APPROVAL`；寄出后才得到这类权限的，链接作废）：先邀请、本人设好密码，再申请授权。
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
| `StripeModeCheck` | `QB_STRIPE_LIVEMODE=true` 须正式密钥（`sk_live_` / `rk_live_`），测试模式须测试密钥；开发入账（`dev`）不能与正式模式或正式密钥同时开启；支付页与开通页的地址前缀在正式模式只能是 Stripe 自己的；Stripe 设置本身（API 版本格式、Checkout 有效期、结算币种）有效；缺发起方页面或 App 地址只告警（充值、开通被拒） |
| `TransferScheduleCheck` | 正式模式或正式密钥下，每日转账与每日核对必须是缺省的每日时间（不能设 `QB_TRANSFER_RUN_CRON`、`QB_RECONCILE_RUN_CRON`），平台管理系统入口不能接受 `QB_E2E_*` 角色 |
| `LiveModeDataCheck`（`quizbuks.stripe.allow-live-with-test-data`） | 正式模式下库中有测试模式的充值（`livemode=false` 或 `cs_test_` 会话），或 2110（发起方钱包合计）不为 0，即失败：测试的 Kudos 不能在正式环境中付出真实奖励 |
| 入站 Webhook | 两个签名密钥都必须有 |
| `AiPdfLimitsCheck`（Q8b） | `QB_AI_PDF_MAX_PAGES` × 5 000 加上最大的文字输入与输出须放得进模型的上下文窗口（100 万 Token：至多约 179 页），且不超过提供方每次请求的 600 页（§7.8；不论模式都查） |
| `LiveModeDataCheck`（Q7b，告警） | 正式模式下没有已生效的学者条款、发起方条款或隐私政策即告警（不能报错：要先启动才能发布）；上线前必须消除 |

**检查清单**（按顺序）：

- [ ] 法务意见已取得（资金決済法：Q6b 的转账），收据措辞、隐私政策与条款已确认（`launch-checklist.md`）
- [ ] Stripe 正式账户（日本）已通过审核，Connect 平台资料与 Express 设置完成，打款银行账户与打款计划已设
- [ ] **新的数据库**（不沿用测试库；确需沿用时先核清测试数据，见 README"正式上线"）
- [ ] 正式的受限密钥（§1.3 的权限）放进 `STRIPE_API_KEY`；正式模式的两个 Webhook 端点（事件同测试，API 版本 `2025-03-31.basil`），两个签名密钥放进环境
- [ ] `QB_STRIPE_LIVEMODE=true`；§1.4 的测试开关一个都没有；`JABIZ_SECURITY_MFA_ADMINISTRATION` 未设；`SPRING_PROFILES_ACTIVE` 不含 `dev`
- [ ] `QB_SPONSOR_LINK`、`QB_APP_LINK`、`JABIZ_MAIL_BASE_URL` 都是正式的 `https` 地址；正式域名写进 `tools/quizbuks/stripe-test-mode/production-hosts`
- [ ] `./gradlew :quizbuks:platformCheck` 以正式的设置（不含密钥值的同形假值）跑一遍；启动日志中没有 `Platform check failed`
- [ ] 首次启动（§3）完成：两位管理员都有二次验证，提案已发布，开具方与科目映射已设
- [ ] 小额实测：一位内部发起方用真实卡充值最小额（`qb.topup.min`），确认到账、手续费分录、收据；在 Stripe Dashboard 全额退款，确认扣回；次日核对为 `OK`
- [ ] 资金调度：充值在 Stripe 上几天后才变为可用余额，转账从可用余额转出；定好打开转账前的余额与打款计划
- [ ] 法务意见允许后，才提出并发布 `qb.transfer.enabled=true`；第一次每日转账（01:07 UTC）后核对每一笔

## 5. 监控与告警

按 §1.5 开启 OTLP（追踪、指标、日志），推到本环境自己的 LGTM（`deploy/quizbuks/server/compose.yaml` 的 `lgtm`，`.env` 设 `COMPOSE_PROFILES=observability`，`DEPLOY.md`"监控与告警"）。
平台的观测名与标签见平台设计 13（标签只有名字与结果，没有主键与操作人）；指标名在 Prometheus 中：计时器 `<名>_milliseconds_count`（点换成下划线），仪表不带后缀。

告警规则都是文件，不在 Grafana 界面上手工建，全部经平台的联系点 `jabiz-oncall`（收件人 `JABIZ_ALERT_EMAIL`，Grafana 的 SMTP `GF_SMTP_*`）送到值班的人：

- **平台的规则**：`docker/grafana/alerting/jabiz-alerts.yaml`（文件夹 `jabiz`；规则、阈值的设置与处理见平台 13 §7）。其中已有刷新令牌重用宽限的两条（F-F6，D73）、地址库的两条（P5，D74）与应用测量的磁盘用量（F-F13）。
- **QuizBuks 的规则**：`deploy/quizbuks/alerting/quizbuks-alerts.yaml`（文件夹 `quizbuks`，只有规则组，没有联系点与路由：一个组织只有一棵路由树，另写一份会盖掉平台的）。
  部署时与平台的文件一起上传到服务器的 `alerting/`，容器启动时由平台的 `render.sh` 填入 `$JABIZ_ALERT_*`（测试：`tools/quizbuks/test/alerts.test.sh`）。
- **试发**：平台的 `tools/ops/alert-fire-test.sh`（只对 staging）：`rules`（规则载入且求值无误；脚本只看文件夹 `jabiz`，QuizBuks 的规则在 Grafana 的"Alerting → Alert rules"文件夹 `quizbuks` 中看健康状态）、
  `notify`（经 `jabiz-oncall` 发一封测试告警）、`fire`（平台规则的假数据）、`disk <目录>`（把文件系统填到阈值以上）。

| 关注点 | 告警（uid） | 说明 |
|---|---|---|
| 健康 | 平台 `jabiz-metrics-absent`、`jabiz-platform-check-failed` | 主机另可探测 `/actuator/health`（只在内网） |
| 答题过载（准入） | 平台 `jabiz-admission-overload` | 仪表 `jabiz.process.admission.waiting` / `.active` 只在仪表盘上看：`waiting` 持续 > 25（队列 50 的一半）、`active` 持续等于 `max-active` 时考虑扩容 |
| 提交被拒（已知的上线风险，§7.6） | QuizBuks `qb-attempt-submit-busy`（`QB_ATTEMPT_SUBMIT` 的 503 比率 5 分钟 > 1 %） | 超时结束的会话数（`QB_ATTEMPT_EXPIRE`）在仪表盘上看 |
| 每次运行的语句数 | 没有告警，发布新版本后看仪表盘 | `jabiz.process.statements{process}`：`QB_ATTEMPT_START` / `QB_ATTEMPT_SUBMIT` 的平均超过语句预算（`AttemptStatementBudgetIT`：开始共 10、付奖的提交共 13，指标不含 `COMMIT`，即 9 与 12；写缓冲的应急开关关时为 11 与 19）的 1.2 倍即查原因——锁内语句变多会直接降低热门发布的容量 |
| 流程错误 | 平台 `jabiz-process-errors` | 503 以外的错误；查询超时（`QUERY_TIMEOUT`）的 503 在仪表盘上看 |
| 核对差异（§7.2） | QuizBuks `qb-reconcile-diff`（仪表 `qb.reconcile.differences`，本实例最近一次核对的差异数）、`qb-reconcile-missing`（26 小时没有成功的核对）；平台 `jabiz-job-failed` | 差异本身在 `qb.admin.recon-runs` 与待办（权限 `qb.reconcile`）；仪表在重启后为 0，直到下一次核对 |
| 转账失败（§7.4） | QuizBuks `qb-transfer-failed`（`qb.stripe.transfer` / `transfer-check` 的作业 `FAILED` / `DISCARDED`）；平台 `jabiz-job-item-failed`（`qb.transfer-run`、`qb.transfer-stale` 的项） | 处于 `CHECK` 超过 2 小时的转账：平台管理系统财务页"转账"按状态筛选（没有指标） |
| Stripe（§7.1） | QuizBuks `qb-stripe-webhooks`（`stripe` / `stripe-connect` 的 Webhook 被拒或失败）、`qb-stripe-calls`（调用失败 > 5 %）；平台 `jabiz-inbound-rejected`、`jabiz-external-call-errors` | 资金相关，QuizBuks 的两条为 `page`；Stripe Dashboard 的端点"失败的投递"同时会发邮件 |
| 事件消费 | 平台 `jabiz-outbox-failed` | |
| 分录导出被拒（§7.3） | QuizBuks `qb-journal-export-rejected` | 看错误码 |
| AI 作业暂停（§7.8） | QuizBuks `qb-ai-paused`（仪表 `jabiz.external.job.held{job_type=~"qb.ai.*"}`，不含 AI 问题解答的 `qb.ai.help`） | 缺省 `JABIZ_ALERT_QB_AI_HELD_MAX=1`：AI 有意关闭时不告警；开启 `qb.ai.enabled` 后在 `.env` 设为 `0` 并重启 LGTM，此后暂停超过 5 分钟即告警 |
| AI 问题解答的用量（§7.34） | QuizBuks `qb-help-usage-high`（仪表 `qb.help.usage.percent`：本月已用 `qb.help.global-monthly-tokens` 的百分比，作业结局与每日任务 `qb.help-usage` 更新） | 缺省 `JABIZ_ALERT_QB_HELP_USAGE_PERCENT=80`；到 100 % 时新的提问被拒（可直接转人工），到月底或经受控变更调高上限 |
| 任务 | 平台 `jabiz-job-failed`（`FAILED` / `PARTIAL`）、`jabiz-job-locked` | 包括 `qb.analytics`、`qb.usage-snapshot`、`qb.fee-*`、`qb.broadcast-*` 等每个任务 |
| 站内信、平台费、邮件补发 | 平台 `jabiz-job-item-failed`、`jabiz-outbox-failed` | 平台费的核对项 `FEES_RECEIVABLE` 属于 `qb-reconcile-diff`；`sys_mail_message` 中 `FAILED` 增多看发件服务商；徽标模板 `qb.app.inbox-unread` 的耗时（目标 < 5 ms，`perf/Q7c-inbox.md`）在仪表盘上看 |
| 发起方分析（§7.7）、资源占用的计量（§7.22） | 平台 `jabiz-job-failed` | 30 分钟内没有成功的分析、当天缺少计量快照（`SELECT max(snapshot_day) FROM qb_usage_day` 早于今天）：仪表盘与只读查询 |
| 邮件发送（T15） | 平台 `jabiz-outbox-failed`，标签 `consumer=jabiz.mail`（每次发信失败都算一次失败的投递；持续 15 分钟即告警，标题是"Event delivery failing"） | 邮件服务器连不上或认证失败：`sys_mail_attempt` 的 `detail`、`troubleshooting.md` T15、`DEPLOY.md`"2b"。告警邮件本身经 Grafana 的 `GF_SMTP_*` 发出：与应用的发信走同一条路时一起断，上线后让它们分开 |
| 安全 | 平台 `jabiz-refresh-reuse-granted`、`jabiz-refresh-reuse-revoked`（F-F6） | |
| 地址库 | 平台 `jabiz-geoip-stale`、`jabiz-geoip-empty`（P5） | |
| 境外视频（F-S11，§7.24） | 平台 `jabiz-job-failed`（`qb.region-check`、`qb.region-refusals`）；地址库的待办 `qb.region-check`（权限 `qb.admin.region.monitor`，只有总部）；被拒次数在 `/admin/qb/region` | 地址库的待办出现（缺失、读不了或过旧）；某天被拒次数突然上升（地址库误判，或发布方大量用了境外视频；`jabiz.process{process="QB_ATTEMPT_START", outcome="rejected"}` 在仪表盘上看） |
| 知识结构图（F-S2，§7.27） | 平台 `jabiz-job-failed`（`qb.tree-check`、`qb.tree-recheck`、`qb.tree-review-remind`）；每日核对的待办 `qb.tree-health`（权限 `qb.admin.trees.read`）；审核提醒的待办 `qb.tree-review` | `UNLIT_PASS` 连续两天出现（点亮的事件消费或 `QB_TREE_EVALUATE` 一直失败）；`ORPHAN_LIT` 出现；`REVIEW_OVERDUE` 持续增加（审核方人手不足）；`GATE_INVALID` 出现（门槛参数指不到可点亮的节点，功能对所有人关闭） |
| 题库（F-S6，§7.37） | 平台 `jabiz-job-failed`（`qb.content-check`）；每日核对的待办 `qb.content-health`（权限 `qb.admin.quiz.read`） | `SNAPSHOT_NEAR_LIMIT` 出现（某个版本的快照超过上限的 80 %，题库再增长即不能保存下一个版本） |
| 应用的磁盘 | 平台 `jabiz-disk-usage`（`JABIZ_ALERT_DISK_PERCENT`，缺省 80，F-F13）；应用停止时没有这个指标，由 `jabiz-metrics-absent` 告警 | 应用测量两个文件系统：工作目录 `/app`（在容器的文件系统上，也就是服务器上存放 Docker 镜像与容器日志的那块盘，T8 满的就是它）与上传文件的卷。处理：`df -h /`、`docker system df` 看占用；旧镜像部署时自动只留最近几个（`DEPLOY.md`"旧镜像的清理"，`QB_DEPLOY_KEEP_IMAGES`），仍不够时删不用的镜像与已停止的容器（`docker image prune`、`docker container prune`，只删没有容器在用的），再看日志与上传文件；扩容。试发：`tools/ops/alert-fire-test.sh disk <目录>` |
| 数据库主机 | **托管方或主机监控的告警**（不由应用测量；pgBackRest 的备份与 WAL 归档见 `deploy/quizbuks/DEPLOY.md`"备份"，失败由死人开关与邮件通知） | WAL 归档延迟 > 15 分钟、最近的基础备份超过 26 小时、数据库所在磁盘 > 80 %、连接数、死锁 > 0、`pg_stat_statements` 的前几名 |
| 主机 | 主机监控 | 应用或数据库的 CPU 在业务高峰持续 > 70 %（§6）、内存、JVM 堆 |

日志：结构化 JSON（`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`）；`Platform check failed` 是平台的规则 `jabiz-platform-check-failed`（Loki）；`quizbuks: WARNING`、`Inbound webhook request of … rejected` 在 Loki 中查看（Webhook 被拒另有指标上的告警）。日志与追踪中不应出现密钥、令牌与原始卡信息（平台已遮蔽；发现即视为缺陷）。

## 6. 容量

来自 `perf/Q10a-load-test.md`（本机 4 vCPU、应用与数据库同机，绝对值偏低，适合前后比较）：平台 16p-2 之后单个热门发布约 **45–54 次完成 / 秒**（§11，本机安静时），每秒 50 次在本机达到（提交 p95 158–1048 ms），但**没有 1.3 倍的余量**：上线前必须在目标规格上重测。

- **2 ÷ 许可持有**：每个发布每族 2 个许可，热门发布的容量 ≈ 2 ÷ 每次完成的许可持有时间（`jabiz.process` 减去 `jabiz.process.admission` 的排队，饱和时测得），应高于预期峰值的约 **1.3 倍**；或直接用锁内时间：容量 ≈ 1 ÷ 提交在发布锁内的时间（报告 §9.4、§10.3）。
- **`max-active` ≥ 同时热门的发布数 × 4 + 1**：一个热门发布至多占 4 个许可（开始 2 + 完成 2），否则其他发布的答题在队列里等许可；提高 `max-active`（`jabiz.process.admission.max-active`）时把连接池设为它的两倍（读取另需一半连接）。
- **CPU < 60–70 %**：在预期读取量下，应用与数据库的 CPU 保持在约 60–70 % 以下；88 % 时许可持有翻倍、吞吐减半（§8.3 第 4 条）。
- **上线前在目标硬件上重测**：压测程序放在另一台主机，读者按预期的读取量限速（`LOAD_READ_RATE`），对打包的 jar 运行 `./gradlew :quizbuks:loadTest`（`LOAD_*` 参数，`LOAD_METRICS=yes` 从回环的管理端口读许可持有与语句数）；确认峰值低于饱和点并有 1.3 倍余量，结果写进报告。
- **大题库与继续答题**（上线清单"性能"）：`LOAD_QUESTIONS`（题数，缺省 2）使版本快照变大，开始、继续与提交都随之变重；`LOAD_CONTINUE=yes` 让每个学者在开始之后再打开一次答题（`QB_ATTEMPT_START` 对进行中的答题即继续），单列一行。
  本机对比（60 人、16 并发，2026-11-08，应用与数据库同机、CPU 饱和，只看相对值）：2 题 24.5 次完成 / 秒，开始 p95 249 ms、提交 p95 566 ms；100 题 15.9 次 / 秒，开始 p95 444 ms、继续 p95 437 ms、提交 p95 656 ms。
  题库的题数现在是参数 `qb.content.max-questions`（缺省 1000，F-S6，§7.37）；抽题与乱序（F-S7）合入后，以 1000 题、每人抽 20 题重测（届时给压测程序加上抽题的参数）。
- 单实例：准入的队列与限流按实例（D45 的已知限制）；多实例时热门发布的锁仍在数据库中串行，加实例不提高单个发布的容量。

## 7. 常见故障

使用者（学者、发起方、店员、运营商）遇到的问题与给他们的解决办法记在 `docs/quizbuks/troubleshooting.md`（同时作为帮助页 FAQ 的底稿）；本节只写运维的故障。

### 7.1 Webhook 签名失败

- **现象**：`jabiz.inbound.receive{result="REJECTED", reason="no_match"}`； Stripe Dashboard 该端点的投递 400；充值停在"处理中"、收款账户停在"待开通"。
- **原因**：签名密钥与端点不符（换了端点或滚动了密钥而没改环境；账户端点与 Connect 端点的密钥配反）；`too_old`：服务器时钟偏差或代理延迟太久。
- **处理**：在 Stripe Dashboard 复制该端点的签名密钥，与环境中的比较（只比较末尾几位）；滚动期间按 §1.3 同时配新旧两个；修正后在 Stripe Dashboard 对失败的事件"重新发送"（Stripe 自动重试最多 3 天）。
  已过期而未到账的充值由每小时的 `qb.topup-stale` 在支付页过期一小时后向 Stripe 核实入账，不需要手工入账。检查 NTP。

### 7.2 核对差异

- **现象**：每日核对 `DIFF`，平台管理系统待办（`qb.reconcile`），`qb.admin.recon-items` 列出差异项（钱包与账本、待支付、2110 合计、账本平衡、充值与分录）。
- **处理**：按差异项的种类在平台管理系统读对应的记录与分录；**不直接改表**（只追加表有触发器，普通表的修改会让核对与审计更难对上）。账本只经冲正修正（`LEDGER_REVERSE`，由对应的业务流程）；
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
- **处理**：等下一次 `qb.transfer-stale`（每小时 47 分）；在 Stripe Dashboard 按转账组 `qb-<转账号>` 搜索确认；看 `GET /api/external-jobs/{作业号}`（`external-job.read`；没有列表接口）中该作业的状态、尝试次数与最后的错误码。作业号以只读账号按转账号查：`SELECT job_id, job_type, created_time FROM sys_external_job WHERE reference = '<转账号>' ORDER BY job_seq`（发送 `qb.stripe.transfer` 与核实 `qb.stripe.transfer-check` 各有作业）。
  **不要**在 Stripe Dashboard 手工再转一笔或在库中改状态：重复转账只能事后追回。用户因 3 次失败或被撤回而暂停时，财务确认原因后 `QB_TRANSFER_HOLD_RELEASE` 放行。
- 余额不足（`balance_insufficient`）不是卡住：该笔立即冲正，次日以新转账重试；持续出现说明资金调度不够（§4 最后两条）。

### 7.5 退款与争议的差额

- **现象**：待办 `REFUND_SHORTFALL` / 争议差额，充值的 `refundShortfall` / `disputeShortfall` 不为 0。
- **机制**：退款与败诉的争议只能从发起方可用余额扣回（Kudos 已作为奖励付出的部分扣不回），余下记为差额、开待办；**记下后不再自动扣回**。
- **处理**：财务线下向发起方追收（或核销），在待办中写明处理结果并关闭。退款只能在 Stripe Dashboard 发起（QuizBuks 不提供退款流程），发起前先看发起方的可用余额以预估差额。
  待办 `UNKNOWN_PAYMENT`（退款晚于充值确认且充值当时未入账）须在充值入账后人工对照（README"充值与核对"）。

### 7.6 提交时过载（503）——已知的上线风险

- **现象**：热门发布上 `QB_ATTEMPT_SUBMIT` 返回 503 `PROCESS_BUSY`（`jabiz.process.admission{family="qb.pub", result="QUEUE_FULL"|"WAIT_TIMEOUT"}`），App 显示"人多，正在重试"；截止前按下的提交可能排队、重试到截止加宽限之后，以 `EXPIRED` 结束（超时任务以已保存的答案结束，可能少了最后不到一秒的改动）。
- **原因**：锁拆分之后开始一侧有余量，人人进入答题，过载落在提交上（`perf/Q10a-load-test.md` §10.3 第 3 条）；单个发布的容量本机约 45–54 次 / 秒（16p-2 之后，报告 §11）。
- **处理**：
  1. 看是否单个发布：平台管理系统发布页的完成人数与 `jabiz.process.admission.waiting` 按族；
  2. 机器 CPU 是否 > 70 %（读取量是否异常：爬虫、首页刷新）→ 扩 CPU、拦截异常流量；
  3. 必要时内容管理员**暂停**该发布（`QB_PUBLICATION_PAUSE`：立即停止新的开始，进行中的会话照常提交），队列消化后恢复（`QB_PUBLICATION_RESUME`）；
  4. 事后：用户投诉 `EXPIRED` 时按会话的已保存答案与结果解释；评估开始一侧的背压（报告 §10.3 的可选缓解）与报告 §11.4 的其他选项。
- **上线前**：在目标硬件上确认预期峰值低于饱和点（§6）。

### 7.7 发起方分析的数据（Q9e）

- **任务** `qb.analytics`（每 10 分钟，:00、:10 …；逐项任务，每项一个事务的 `QB_ANALYTICS_ITEM`）：读进行中、暂停、预算不足、1 天内结束与结束后尚未定稿的发布，按发布编号分块（每块一项），把计数的增量写进按小时的桶，从水位线读已完成的会话（滞后 5 分钟）；结束 30 分钟后每个发布一项整体重算并定稿；最后一项写运行记录并删除 400 天前的小时桶（每次至多 2 000 行）。不取业务锁，不影响答题。
  **一项失败不影响其他项**：其他项照常提交，运行记为 `PARTIAL`，失败的项由平台退避后在以后的运行中重试（平台的任务项记录中有错误），其间该块的发布的数字停在上次。
  发起方页面上的"每 10 分钟更新，最近更新 hh:mm"就是这个任务最近一次运行的时刻（`qb_stats_run` 中 `job = 'qb.analytics'` 的行，`zone` 是它按哪个时区分日：`jabiz.fiscal-year-zone`，日本部署 `Asia/Tokyo`）。
- **每题的"被抽到"**（F-S7，`QbQuestionStat.drawn`）：已结束（完成或超时）的答题中被给出这一题的次数，**完成后才计入**：进行中的答题不算，结束后由下一次运行计入（总部 2026-11-09 确认）。与其他每题数字一起按最小分组发表；今天它与完成数相同。发起方的题目统计在这一栏旁注明"完成后才计入"。
- **参数** `qb.stats.min-group`（普通参数，缺省 5，任何值都至少按 3）：分组小于它时每题正确率、平均用时与超时人数不显示，且这些数字只在新增至少这么多个完成后才变化。调大立即生效（模板按现值隐藏）；调小只在下次新增够数时显示新值。
- **配置**（`application.yml` 的 `quizbuks.analytics.*`，通常不改）：`chunk-size`（每块的发布数，缺省 100，至多 200）、`max-attempts-per-chunk`（每块每次至多读的已完成会话，缺省 50 000，至多 200 000；热门发布每 10 分钟约 3 万个，`perf/Q9e-analytics.md`）、`max-recount`（结束后整体重算的发布至多有多少个完成，缺省 1 000 000，使重算在读取时限 2 分钟之内；更大的发布在逐块读完之后不重算即定稿）。
  部署时区 `jabiz.fiscal-year-zone` 只能是 `UTC` 或地区名（如 `Asia/Tokyo`）：`+09:00`、`GMT+9`、`Etc/GMT-9` 这类偏移写法在 PostgreSQL 中方向相反，启动检查拒绝（类别 `QUIZBUKS`）。
- **停机与补跑**：任务停了之后的第一次运行把期间的增量全部记在停机前最后一次运行的那一小时，并标 `gap`（趋势注明），累计数字始终正确；积压的会话按块上限分几次读完。不需要手工补跑；同一计划时刻重复运行不会重复计数。
- **告警**：`jabiz.job.run{job="qb.analytics", result="PARTIAL"}` 连续出现（某一项一直失败：看任务项的错误，常见为读取超时或约束）；最近一次运行早于 30 分钟（发起方页面的"最近更新"随之变旧）。运行时间见 `jabiz.process{process="QB_ANALYTICS_ITEM"}`，热门发布积压时变长。
- 日志中的 `Analytics: the counters of publication … went down` 表示计数被别的方式改动（计数只增不减），该发布本次不写；`… recounted … finalized anyway` 表示结束一天后整体重算与计数仍不一致，按重算的值定稿：两者都应调查。

### 7.8 AI 生成题目（Q8a、Q8b；封面 Q98a 撤销）

- **开关与限额**（平台管理系统 `/qb/ai`，受控参数）：`qb.ai.enabled`（缺省 false）、`qb.ai.model`、`qb.ai.effort`、`qb.ai.sponsor.daily-jobs`（20）、`qb.ai.sponsor.monthly-tokens`（200 万）、`qb.ai.global.monthly-tokens`（5 000 万）；普通参数 `qb.ai.candidates.expire-days`（7）与只用于估算的价格 `qb.ai.price.*`。限额在 `qb.ai:<发起方>` → `qb.ai:*` 锁内按已记录的用量与未结束作业的预估检查（全体上限也加上所有发起方未结束作业的预估）；一个作业的预估是 3 次尝试各自完整的输入与所要的全部输出（`max_tokens` 随题数：4 000 + 600 / 题，至多 16 000；effort `high` 时一次至多 10 题）。
- **作业** `qb.ai.questions`：每次尝试至多 5 分钟（请求本身至多 `QB_AI_REQUEST_TIMEOUT`，缺省也是上限 2 分钟）、3 次尝试（429、408、5xx、529、超时、断线与回退忙时的拒答重试，退避 10 秒起、至多 2 分钟；其他拒答、截断、其他 4xx 立即失败）、全实例并发 `QB_AI_MAX_CONCURRENCY`（缺省 4）。**这个并发是每种作业各自的**：题目 `qb.ai.questions` 与封面 `qb.ai.cover` 各至多这么多，所以同时打到提供方的调用至多是两者之和（缺省 4 + 4 = 8）；按提供方的速率限制与堆（见下面"PDF 资料"）取值时按和计。提供方不按幂等键去重：重试可能重复计费，用量按每次尝试记录，也计入限额；**没有得到回答的尝试**（超时、断线、回答过大）按预估计入（带 PDF 的请求按提供方数出的输入，其他按文字的估计，加所要的全部输出），因为提供方可能已为没人读到的回答计费。
  **用量与预留**：带 PDF 的请求不带提供方的回退（`fallbacks`；复审 M-a：回退会在另一个模型上把输入再算一次，计费就会超过计数），所以每次付费的 PDF 调用正好是计过数的请求，**每次计过数的调用的输入不超过预留**；带 PDF 的请求被拒答时由下一次尝试重试（再计一次数）。
  只有文字的请求不计数、只按文字估计，并照常带回退：它的实际输入可能超过预留（回退时约为两倍）。某次尝试的实际用量超过请求为它预留的会记一条告警日志 `AI usage of one attempt above its reservation (job type …)`（只有作业种类，不含编号，D16）：偶尔出现可以接受，经常出现时调高预估或月上限。
  **价格**：`costUsd` 按每次调用计价。较大的两个模型不论提示多长都是同一价格；最小的模型在一次调用的输入超过 10 万 Token 时整个调用按较高的长提示价格计（`AiCost`），所以按每次尝试分别计入，不按作业平均。
- **任务** `qb.ai-expire`（每日 18:43 UTC）：把过期未校对的候选从作业中移除（之后不能再加入；提供方的原始回答仍在平台只追加的 `sys_external_job` 结果中），逐项一个事务。
- **提供方故障**：平台管理系统 `/qb/ai` 的"最近的请求"按失败原因看（`QB_AI_RATE_LIMITED`、`QB_AI_PROVIDER_UNAVAILABLE`、`QB_AI_AUTH`：密钥错或被删，`EXTERNAL_CALL_FAILED`：没有回答）；长时间故障时以受控变更关闭开关（发起方页面显示"暂时不可用"，手动编辑不受影响；排队与重试中的作业随之暂停、不再用掉尝试次数，见下面第 2 步）。不自动切换到其他提供方。
- **事故时的紧急停止**（花费异常、密钥泄露），按快慢：
  1. **最快：在 Anthropic Console 吊销（删除）密钥**。立即生效、不需要部署或第二个人；之后每次尝试得到 401，作业以 `QB_AI_AUTH` 立即失败、不再重试（排队与重试中的作业都一样），发起方看到"暂时不可用"。恢复时新建密钥、改 `ANTHROPIC_API_KEY`、重启。
  2. **以四眼关闭开关 `qb.ai.enabled`**：受控参数，**需要两位管理员**（一位在 `/qb/ai` 提出、另一位发布），所以不是一个人能立刻做到的。发布之后：新的请求一律 `QB_AI_UNAVAILABLE`；
     两种作业类型声明了 `enabledBy("qb.ai.enabled")`（平台 16v-2，D52 第 4 条），**每个实例在约一个轮询间隔（`poll-interval`，缺省 1 秒）之内**不再领取 `qb.ai.questions` 与 `qb.ai.cover` 的作业：
     排队的与等待重试的作业**原地等待**，不调用提供方、不用掉尝试次数、不失败；**正在运行的尝试照常进行到结束**（处理器看不到开关），其用量照常计入。参数读过之后才关闭时，那一次轮询仍可能多领取一轮。
  3. **逐个取消作业**：持有 `external-job.cancel` 的管理员（平台的 `ADMIN` 角色）对作业调用 `POST /api/external-jobs/{作业号}/cancel`（作业号在 `/qb/ai` 的"最近的请求"与 `qb.admin.ai-jobs` 中）：没在运行的（包括第 2 步暂停中等待的）立即结束、正在运行的在本次尝试后结束、不再重试；作业结束后平台调用两种作业类型的取消结局 `QB_AI_JOB_CANCELLED`（平台 D52 第 2 条），它关闭本地作业（`QB_AI_STOPPED`），没有尝试运行过的不计费。
  **没有 Console 权限、也找不到第二位管理员时**（只有基础设施的权限）：把 `ANTHROPIC_API_KEY` 改为占位值（如 `revoked-placeholder`）并重启全部实例。之后每次尝试得到 401，作业以 `QB_AI_AUTH` 立即失败、不再重试（排队与重试中的也一样，与第 1 步相同；正在运行的尝试照常结束），
  新的请求仍会被接受并随即失败。能联系上 Console 的负责人或第二位管理员后，按第 1、2 步处理，再换回真实密钥并重启。
  **不要**为此设 `JABIZ_EXTERNALJOBS_EXECUTOR_ENABLED=false`（`jabiz.external-jobs.executor.enabled`）：它停掉本实例的**全部**外部作业，包括 Stripe 的转账与核对（`qb.stripe.*`），资金流程随之停住。
  **暂停期间的作业**（第 2 步之后）：仍是未结束的作业，所以**照常计入发起方同时未结束的上限**（每发起方 2 个、每模版每种类 1 个）与月 Token 的预留；发起方在编辑器中看到它仍在排队（`QUEUED`，面板注明 AI 暂停、请求会等待、可以取消），可以自己取消（不计费）。
  重新打开（同样四眼）后的下一次轮询即恢复，积压的作业按并发上限与退避逐步执行；长时间暂停后，不再需要的作业由发起方或第 3 步取消。日志在每个实例上只在变化时记一条 `External job type qb.ai.… is paused: parameter qb.ai.enabled …`（INFO）与恢复时的一条；量规 `jabiz.external.job.held{job_type}` 暂停时为 1。
  此前的本实例开关 `QB_AI_HALTED`（需要重启、让排队与重试中的作业以 `QB_AI_HALTED` 永久失败）已删去：第 2 步做到了它的事而不需要重启、也不让作业失败。
- **拒答**：请求带服务端回退（`fallbacks: "default"`，Haiku 无），回退后仍拒答即 `QB_AI_REFUSED`；`/qb/ai` 的"实际回答"列显示回退所用的模型。
- **取消怎样计费**（平台 16v-1 之后）：发起方取消（`QB_AI_CANCEL`）与运维停止（上面第 3 条）都取消平台的作业：排队中的不再调用提供方；正在运行的尝试照常进行到结束，回答被丢弃。作业结束后 `QB_AI_JOB_CANCELLED` 只运行一次，按平台记下的**实际用量**（全部尝试，含被取消的那次：处理器在每次调用后记下的输入、输出、回退模型与长提示的部分，没有得到回答的调用按预估）计入作业与月用量，美元估算同失败的作业（`AiCost`，不知道回答的模型时回退部分按回退模型的价格）；**没有任何尝试运行过的作业不计费**（不计入用量行）。
  此前的做法：发起方取消只改本地状态，平台的作业照常运行到完成（排队中的也会调用一次提供方），由完成或失败流程按实际用量计入；运维停止由 `QB_AI_JOB_STOPPED`（订阅 `jabiz.external-job.finished`）按**完整预估**计入。两者都已删去，同一作业只经取消结局计费一次。取消的作业计入用量行的作业数，但**不计为失败**（`failedJobs` 与平台管理系统 `/qb/ai` 的失败率只算失败的作业）。
  **没有记下用量的尝试**（平台 16w-1 之后，D52 第 5 条）：处理器在每次调用之后（没有回答的调用在放弃时按预估）就记下用量；实例在尝试中途崩溃或卡住、租约过期（或超时）而什么也没记下的尝试，平台在完成、失败与取消的结局中报告其**个数**，
  `QB_AI_JOB_COMPLETED`、`QB_AI_JOB_FAILED` 与 `QB_AI_JOB_CANCELLED` 对每一次都按没有回答的调用同一依据计入：作业为一次尝试预留的输入（作业参数 `reservedInputTokens`：文字的估计，带 PDF 的再加页数上限）加所要的全部输出（题目 `maxTokens(题数)`，封面按风格 `AiCoverJob.maxTokens`：图形 8 000、插画 12 000），美元按 `AiCost`（长提示的规则同记下的调用）。
  因此不再有计为零的尝试。迟到的实例（租约过期后才回来）的用量由平台记到那次尝试上；结局流程运行之前回来的，那次尝试不再算"没有记下"，按它实际报告的用量计入而不是按估计；之后回来的只出现在平台的用量报表（`jabiz.external_job_usage`）中，QuizBuks 不再改动已计的用量（按估计计入的那次可能偏高或偏低）。
  **有意偏高**：实例在到达提供方之前就死掉的尝试（计数 `count_tokens`、读文件或画图时）同样按完整的估计计入；带 PDF 的作业即页数上限（`QB_AI_PDF_MAX_PAGES` × 5 000，缺省约 50 万 Token）。平台只报告个数、不知道尝试走到了哪一步，所以按估计宁高勿低（D52 第 5 条）。
  **已知的偏低**：有回退模型的请求（只有不带文档的文字请求，Haiku 除外）没有得到回答时只计一次输入，而提供方的回退会把输入再运行一次、可能计两次；这一缺口是接受的，由提供方的支出上限兜底。
  提供方仍可能对重试重复计费，**提供方控制台上的支出上限仍是最后的防线**（上线前设好，§1.3）。
- **告警**：`jabiz.external_job` 中 `qb.ai.questions` 与 `qb.ai.cover` 的失败率；某发起方或全体本月用量接近上限（`qb.admin.ai-usage`）。
- **封面**：AI 生成封面已撤销（Q98a，`plans/Q98-covers.md`）。作业类型 `qb.ai.cover` 仍登记，只为让撤销前排队的作业结束：处理器不调用 Claude，以 `QB_AI_COVER_WITHDRAWN` 失败（不计费），之后的版本删去。
  **作业完成迟到的风险**：画好的图在作业结束时已登记为文件，到 QuizBuks 的完成流程（订阅作业结束事件）把它们写进作业的三列之前没有任何引用。事件投递停住（实例长时间停机、投递故障）超过 `orphan-after` 时，清扫可能先删掉这些图，之后的完成流程记下的是已删除的文件：作业显示为就绪，但图片显示不出、选定被拒绝（发起方丢弃后重新请求即可，用量照常计入）。恢复投递后不要手动重放早于 `orphan-after` 的封面完成；长时间停机后先确认事件已投递，再看 `/qb/ai` 中这段时间结束的封面作业。
- **PDF 资料（Q8b）**：所选的 PDF 资料作为文件本身发送（平台的文件输入，D50：登记时检查发起方能读该文件）：每个至多 `QB_AI_PDF_MAX_BYTES`（缺省 10 MiB，上限 20 MiB），一次请求的全部 PDF 合计至多 20 MiB，按至多 `QB_AI_PDF_MAX_PAGES` 页（缺省 100）预留。
  页数上限必须放得进模型的上下文窗口（三个可选模型都是 100 万 Token）：页数 × 5 000 加上最大的文字输入与输出不得超过它（约 179 页），也不得超过提供方每次请求的 600 页；否则启动检查 `AiPdfLimitsCheck`（类别 `QUIZBUKS`）拒绝启动。
  **服务器不解析 PDF**（Q8b 评审 H1、H2）：发起方的文件可以构造成在解析器的内存中无限展开（对象流炸弹，使 JVM 内存耗尽），也可以在页树中谎报页数，所以本地数页既不安全也不可信。作业在付费调用之前，以完全相同的请求调用提供方免费的 `POST /v1/messages/count_tokens`（同一外部服务 `anthropic`、同样的请求头；计数有自己的速率限制），以**提供方数出的输入 Token** 为准：
  超过请求为一次尝试预留的输入即 `QB_AI_PDF_TOO_LONG`（永久、不发送、不计费）；提供方以 400 说请求过长（超过上下文窗口或每次请求的 PDF 页数）同样是 `QB_AI_PDF_TOO_LONG`；提供方说某个 PDF 读不了（无效、有密码、加密、损坏）即 `QB_AI_PDF_UNREADABLE`（都永久，400 不计费）。
  这些文字 API 文档没有给出，按常见的说法宽松匹配（`ClaudeErrors`）；其他 400，尤其是以字段路径开头的校验错误（如 `messages.0.content.0.document…`），一律是 `QB_AI_REQUEST_INVALID`：那是我们自己的请求有错，运维应当看到并调查。
  计数本身断线、超时、429 或 5xx 照常重试、不计费，但**会用掉作业 3 次尝试中的一次**（复审 L-d）：计数接连失败的作业可能在发送之前就用完尝试而失败（不计费，发起方可重新请求）。之后的付费调用没有回答时按这个数计入。本地只查字节上限（存储的文件大小）。
  请求时还不知道页数，所以**按上限预留**：每页按 5 000 Token（文字与页面图像），3 次尝试，即缺省一个带 PDF 的请求预留约 150 万 Token，直到作业结束才按实际用量计；计数超过预留的作业在发送前即失败，带 PDF 的调用又不带回退，所以带 PDF 的调用计入的输入不超过预留。取消的 PDF 作业按实际用量计入（排队中被取消的不计费，预留随之释放）。发起方的月上限（缺省 200 万）因此只容得下一个进行中的 PDF 请求；常用 PDF 时提高 `qb.ai.sponsor.monthly-tokens` 或降低 `QB_AI_PDF_MAX_PAGES`（预留随之按比例减少）。
  作业输入上限 256 KiB（文字资料；Q8a 为 60 KiB），作业输入照旧永久存于平台的 `sys_external_job`，PDF 只记文件编号与摘要（内容随 `sys_file` 删除而删除）。
  **堆**：一次带 PDF 的尝试在内存中同时持有文件的字节、Base64 一份（只编码一次，计数与调用共用）、请求体的字节与平台发送时的副本，约为该请求 PDF 合计的 5–6 倍，即 20 MiB 的上限下每个运行中的题目作业约 120 MiB（封面作业不带 PDF）。JVM 堆至少按"基础 512 MiB + 120 MiB × `QB_AI_MAX_CONCURRENCY`"给出（缺省并发 4 时约 1 GiB 以上）；容器内存紧张时降低 `QB_AI_MAX_CONCURRENCY` 或 `QB_AI_PDF_MAX_BYTES`。

### 7.9 其他

- **邮件发不出**：`jabiz.mail` 的失败、发件服务商的退信；用户可在登录页重新请求验证邮件。
- **启动失败**：日志中的 `Platform check failed` 后列出全部问题（`类别 | 定位 | 描述`），逐项修正环境；`QUIZBUKS` 类别是本应用的检查（§4）。
- **证书、域名更换**：同时改 `JABIZ_MAIL_BASE_URL`、`QB_SPONSOR_LINK`、`QB_APP_LINK`、`QB_OIDC_REDIRECT_APP` 与 `QB_OIDC_REDIRECT_SPONSOR`（与 Google 控制台）与两个 Stripe 端点的地址。
### 7.10 投资人指标（Q27）

平台管理系统「QuizBuks 财务 → 投资人指标」（`/admin/qb/finance/investor-metrics`，`qb.admin.finance.read`）按 UTC 月给出发起方、学者、答题、奖品、邀请与资金的数字，起止月在地址中（缺省最近六个月，至多 36 个月；直接调用模板时超过 36 个月的部分不出现）。给投资人的数字每月初**签发**一次（页面上的「签发」，`REPORT_ISSUE`，带内容哈希存档），之后发出去的都取存档的那一份；临时查看用导出（CSV / XLSX / PDF）即可。数字按发生时间归月（发起方按审核通过、答题按开始、完成按提交、奖品按获得与领取、充值与奖励按支付），与 `qb.admin.monthly`（按过账时间）的资金列可能差几笔月边上的记录，口径见 `plans/Q27-investor-metrics.md`。

### 7.11 招商网站（Q31）

`/biz/` 是静态站点（`quizbuks-web/biz`），随 jar 一起构建与发布，不读服务器的数据。要改文案改 `src/content/{en,zh,ja}.mjs`（三种语言结构必须一致，`pnpm test` 检查）；
首页的"试答"区在 `src/content/try-quizzes.json` 里填每种语言的分享链接（`https://quizbuks.com/taker/p/<发布>`）与二维码图片（放到 `public/`，填相对路径），为空即显示"即将推出"。
价格页的数字是推广期估价，标"计划中"的套餐与费用尚未开售、尚未计费，改动前先在 `docs/quizbuks/plans/Q31-biz-site.md` 记下新值。链接只接受 `https://`、二维码只接受 `public/` 下的 PNG/SVG，否则构建失败。页面的 `canonical`、`hreflang` 与 `sitemap.xml` 用完整地址，源自构建时的 `SITE_URL`（缺省 `https://quizbuks.com`，换域名时在构建环境设置）。裸域名 302 到 `/biz/`；中文与日文在 `/biz/zh/…`、`/biz/ja/…`，从站外首次访问按浏览器语言转向（`localStorage` 记住切换；站内点击的语言从不被改）。

### 7.12 发起方的邮件服务器（Q29）

邀请邮件只经发起方在发起方页面「邮件服务器」页验证过的 SMTP 服务器、以发起方的名义发出（平台决策 D56）；平台自己的邮件（验证、重置、欢迎、给发起方的通知）仍经 `SPRING_MAIL_*`。
保存即经该服务器给发起方自己的地址发测试邮件：寄出即"已验证"，否则页面显示服务器或连接的回答（`qb_sponsor_mail_server.last_error`），邀请在验证之前被拒（422 `QB_INVITATION_NO_MAIL_SERVER`）。
平台拒绝的设置（主机不是公开域名、端口不在 25 / 465 / 587 / 2525、没有 TLS）在保存时按字段报错，不会保存。

- **发起方说邀请没寄到**：先看页面是否"已验证"；验证过的服务器在投递时拒收，是平台邮件尝试的 `FAILED`（`sys_mail_attempt.detail` 是服务器的回答，`sys_mail_message.sender_key` 是发起方的用户编号），Outbox 照常重试，绝不改经平台的服务器；邀请行此时已是 `SENT`。让发起方重新保存（再发一封测试邮件）或检查服务商的限制（发信配额、SPF / DKIM）。
- **`JABIZ_SEAL_KEY` 换了或丢了**：已存的密码打不开，投递记 `FAILED`（"cannot be opened"）；每个发起方重新输入密码并保存即可（页面照常），不需要运维介入。
- **发起方的服务器很慢**：投递事务等到平台的超时（`jabiz.mail.timeout`）；一次失败只让该轮投递停在它那里，下一轮继续其他邮件。

### 7.13 运营商（Q48a、F-S1）

每个发起方都由一个运营商负责：审核（入驻、发布、发起方主页与商品）、举报、兑换与数字奖品争议、奖励方式、获客（~~与认证~~已由 F-S2c2b 删去，D1）都在运营商管理系统 `/operator/` 中由运营商的成员做，审核即终审；平台只做财务、抽查、全局规则与应急，并在平台管理系统中**只读**查看全部运营商的这些列表（可按运营商筛选）（`plans/Q48-operators.md`、`plans/impl/F-S1.md`）。

**第一个运营商（上线前必做）**：没有默认运营商时，谁也不能申请成为发起方（入驻 422 `QB_OPERATOR_DEFAULT_MISSING`），平台管理系统首页（`/admin/` 登录后的"首页"）以红色提示"请先建默认运营商"。

1. 超级管理员先在「用户」中为负责人建账户（`invite`，本人经邮件设定密码），确认邮箱已验证；
2. 在「运营商」新建运营商：名称、联系人、联系邮箱、**负责人**（必填，按姓名或邮箱搜索）；第一个运营商自动成为默认运营商（"新发起方归此"），之后新建的可勾选"设为默认"；
3. 负责人的账号在同一事务中成为该运营商的**直营发起方**（已通过的入驻、不能改派的归属）。负责人本人登录 `/sponsor/` 并"加入"即取得发起方角色（平台的加入入口，流程不授予角色）；
4. 按下面"签下一个运营商"的第 2–5 步加第二位能审核的成员：直营发起方的发布由同一运营商的**另一位**成员审核（平台的"准备人不能审批"），只有负责人一人时每日核对报 `FEW_REVIEWERS`。

**签下一个运营商**：

1. 同上第 1–2 步建运营商（不勾"设为默认"）；
2. 在「用户」中为它的每位成员建账户（成员本人经邮件找回设置密码，管理员知道的密码不能接受受保护的授权），确认邮箱已验证；
3. 在运营商页面添加成员（按姓名或邮箱搜索；可勾选负责人。运营商已有直营发起方时，新的负责人只是负责人）；
4. 在「用户与角色」中给成员分配角色 `QB_OPERATOR`：这是受保护的授权（含 `approval.decide`），由另一位超级管理员审批后生效；运营商页面的成员表在授予之前标"尚无角色"；
5. 成员登录 `/operator/`，第一次即要求绑定二次验证（角色要求）。

- **默认运营商**：至多一个。要换，在另一个运营商的页面"设为默认"（确认后取代原来的）；默认的不能停用、不能直接取消。默认只决定以后自己注册的发起方归谁，已有的不动。
- **新发起方归谁**：经运营商的开拓链接（`?src=` 的键）来的归该运营商（审核也由它做，Q48e-1），否则归默认运营商。运营商的语言只作展示与获客，不决定归属。提交入驻申请时即写入归属；发起方在发起方页面"发起方资料 → 概况"的"你的运营商"卡片上看到运营商的名称与联系方式（帮助底稿 `help/your-operator.md`）。
- **改派**（`QB_SPONSOR_OPERATOR_ASSIGN`，超级管理员在运营商页面的发起方列表中选目标运营商并写原因，历史在审计中）：目标须在运营、接收发起方；**直营发起方不能改派**（422 `QB_DIRECT_SPONSOR_FIXED`）。改派时：发起方（作为准备人）待审的审批请求与其待办经平台的 `APPROVAL_SCOPE_MOVE` 一起移到新运营商（调用者另需 `approval.scope.move`，只有超级管理员持有），已批准的不动；一次至多移动 `jabiz.approval.scope-move.max-items`（部署配置，缺省 500）个，超过即 422 `QB_SPONSOR_REVIEW_PENDING`，不分批：先请原运营商处理掉一部分，或请运维调高这个上限后重启；举报与数字奖品争议的待办在旧运营商处取消、在新运营商处重建；发起方收到站内信。已上线发布的收入仍归上线时的运营商。
- **成员离职**：负责人在运营商首页移除，或平台在运营商页面移除；下一次动作与下一次刷新会话即被拒。角色另在「用户与角色」中收回。离开的是负责人时，直营发起方仍是原账号（换负责人时的移交未定，见规格 F-S1 的未决）。
- **合同结束**：先把它的发起方逐个改派到其他运营商（直营发起方除外），再把运营商设为"已停用"：全部成员立即不能登录或操作。默认运营商要先把默认交给另一个才能停用。
- **每日核对**（任务 `qb.operator-check`，模板 `qb.admin.operator-health`）：没有默认运营商（`NO_DEFAULT_OPERATOR`）、没有运营商负责的发起方（`NO_OPERATOR`）、直营发起方不是恰好一个的运营商（`DIRECT_SPONSOR`）、能审核的成员不足两位的运营商（`FEW_REVIEWERS`）。有问题即给持有 `qb.admin.operators.read` 的人一个待办（每天一个），平台管理系统首页同样列出，并链到出问题的运营商或发起方。处理：建或指定默认运营商；为 `NO_OPERATOR` 的发起方改派；为 `FEW_REVIEWERS` 的运营商加一位持有 `QB_OPERATOR` 的成员。
- **审核积压**：平台管理系统首页的"各运营商的审核"列出每个运营商现在待处理的审核与最近 30 天决定的审核（模板 `qb.admin.operator-workload`），按列排序看谁积压；积压时联系该运营商的负责人，必要时改派发起方。
- **运营商看到什么**（Q59c-2、Q48e）：只有自己发起方的内容、发布、奖励与审核；兑换与电子奖品的列表含学者的显示名（处理兑换与争议需要）；发起方改派后，新运营商看得到它以前的发布；内容与头像的文件、国家与参数是公共的参考数据。运营商不读用户资料、财务、法律文本、参数与角色。
- **抽查**：「运营商审核记录」缺省只列未抽查的决定；有问题时记下说明，再由平台自己下架或封禁（运营商不能恢复平台的下架、不能解除平台的封禁）。
- **现金奖励的争议**（F-S1）：平台不处理。学者提出争议只通知发起方；双方解决后由发起方再次"标记已支付"（确认期限重新起算），或学者直接确认。平台的现金奖励列表只读、可按运营商筛选，没有裁决。
- **已有安装的授权**（F-S1）：`QB_SETUP` 只补、不收回。F-S1 之前已执行过 `QB_SETUP` 的安装，在「用户与角色」中从角色 `QB_ADMIN_CONTENT` 去掉 `qb.review.sponsor`、`qb.review.publication`、`qb.admin.reward-methods`、`qb.redemption.void`、`qb.digital.resolve`、`qb.prospect.use`、`qb.certification.manage`（`approval.decide` 保留，财务四眼要它），并去掉已删除的 `qb.cash.resolve`（任何角色上都去掉）；超级管理员角色补上 `approval.scope.move`（不是受保护的权限：再执行一次 `QB_SETUP` 即补）。原来由内容管理员做审核的人，改为某个运营商（通常是默认运营商）的成员并取得 `QB_OPERATOR`。测试系统直接重置数据库。
- **方案**（Q48e-2）：五种方案（每月固定费、平台分成；受控参数 `qb.operator.plan.<1..5>.fee|share`，`QB_SETUP` 提出 0/50%、20,000/40%、40,000/30%、60,000/20%、100,000/10%）。新运营商为方案 1；总部在运营商页面提出从以后某月起的方案，另一位管理员（`qb.topup.bank.approve`）在待办中批准后生效。
- **月结算**（Q48b、Q48e-2）：每月 2 日 01:13（日本时间）任务 `qb.operator-settlement` 为每个运营商结算上个月：其发起方发布的抽成 ×（1 − 当月方案的平台分成），减去当月方案的固定费（停用的运营商不收）与获客费用，记入运营商的账户（不够扣的结转到下月），并可签发结算单。前提：`qb.creator-fee-rate` 大于 0、分录导出的 `qb.journal.account.2140` / `5130` 已设。
- **提现**（Q48e-2）：钱都在平台。运营商负责人在运营商管理系统「账户」设银行账户（改后 `qb.operator.withdrawal.cooldown-hours` 小时内不能提现）、申请提现（至少 `qb.operator.withdrawal.min`，至多余额，同时一笔）；财务在「运营商提现」银行付款后点"登记付款"，另一位管理员在待办中批准后才过账（2140 / 1120），驳回即回到"已申请"；财务也可驳回申请并写理由。手续费由平台承担。Q48e-2 之前签发的结算单照旧逐张付款。

### 7.14 AI 获客（Q48c）

AI 为运营商找发起方（F-S1：所有者一律是运营商；平台不自己获客，在平台管理系统中选一个运营商只读查看它的获客）、调研、写体验问卷与邮件，人批准后从所有者自己的邮件服务器发出（`plans/Q48-operators.md` §3、§7 Q48c）。开始之前：

1. 平台的超级管理员在平台管理系统「AI 获客 → 设置」的**平台开关**一栏（F-F8）勾选"运行 AI 获客"、选好模型（如 `claude-sonnet-5-5`）与强度、填理由后"提出"（只为改动了的参数各提一个受控变更），另一位持有 `control.publish` 的管理员在"受控变更"中发布（缺一个即两种作业都不被领取，`EXTERNAL` 启动警告）。该栏同时显示三个参数的现值、待发布的提案和搜索的运行时刻；所有者勾了"每天获客"而开关是关的时显示警告。`ANTHROPIC_API_KEY` 与 AI 出题共用；
2. 所有者（运营商的成员）在运营商管理系统的「AI 获客 → 设置」填发件人名称、住所与联系邮箱（法律要求的表示），登记并验证邮件服务器（建议独立的发件子域名与 Google Workspace / Zoho 等的 SMTP，不与平台的发件域混用），添加搜索方向，打开"每天获客"。

- **任务**：`qb.prospect-find`（每天 23:17 UTC，日本 08:17）每个启用的方向搜索一次（错过的时刻不补跑，设置页按读者的时区显示这个时刻）；`qb.prospect-research`（每小时 :23）调研找到的发起方，每天至多每日邮件数的两倍；`qb.prospect-send`（每 10 分钟）只在日本时间 9–18 点发出已批准的，每天至多每日邮件数。都是逐项一个事务。
- **跟进**（Q48d）：`qb.prospect-follow-up`（每小时 :37）为 7 天前发出、没有带来发起方的邮件发一次跟进，计入每日上限。
- **运营商的费用**（Q48d）：运营商每月的获客费用另受受控参数 `qb.prospect.operator-monthly-cap-usd`（美元，缺省 100）约束；月结算按 `qb.prospect.usd-jpy`（缺省 150）把当月费用折成日元从分成中扣除，扣不完的结转下月。汇率按需以四眼更新。
- **配置**：`quizbuks.prospect.max-concurrency`（每种作业的全实例并发，缺省 4）。
- **费用**：每次搜索 0.01 美元加 tokens（按所选模型的标价）；平台管理系统或运营商管理系统的「设置」页按月显示。达到所有者的每月上限后不再开新的找与研究，已批准的邮件照常发出。
- **事故**：Console 吊销密钥 → 在同一栏取消勾选并提出、另一位管理员发布（四眼关闭 `qb.prospect.enabled`）（排队的作业等待，不用掉尝试）→ 必要时平台的 `EXTERNAL_JOB_CANCEL` 逐个取消（类型 `qb.prospect.find` / `qb.prospect.research`）。投诉或要求停止时：平台管理系统「AI 获客」丢弃并勾选"屏蔽"，或 `QB_PROSPECT_BLOCK_ADD`（地址或整个域名，全平台不再发送）。

### 7.15 按规则计费（Q47c）

客户匹配的规则是平台的产品，运营商自定价格（`plans/Q47c-audience-billing.md`）。Q47c-1 只给出价目与报价，**不扣款**（扣款与退回在 Q87 之后的 Q47c-2）。

- **规则目录**：内容管理员（`qb.audience.rules.write`）在平台管理系统「受众规则」决定每种规则是否开放给发起方、是否计费、排序；没改过的规则即开放、计费。关闭的规则发起方不能再选，已提交的发布照旧。新增一种规则要开发。
- **价目表**：每个所有者（直营 `direct` 与每个运营商）一张：每种规则的日价与每次最低使用费，理由必填，历史在审计中。直营与任何运营商的由财务（`qb.audience.prices.write`）在平台管理系统改，运营商的负责人在 `/operator/` 改自己的。没有价目表的直营即免费；运营商没有价目表时按受控参数 `qb.audience.price.fallback`（`DIRECT` 用直营的价，`FREE` 免费）。
- **费用** = max（最低使用费，Σ 所用计费规则的日价 × 天数），天数为发布期间按 24 小时向上取整；没用计费规则的不收。
- **开关**（受控参数，四眼）：`qb.audience.billing.enabled`（缺省关：报价为 0）、`qb.audience.price.fallback`、`qb.audience.fee.refund-unused-days`（Q47c-2 才起作用）。打开之前先设好价目表；**Q47c-2 上线之前不要打开**（打开后发起方看到费用，但提交时还不扣款）。部署后执行一次 `QB_SETUP` 提出这三个参数，由另一位管理员发布。

### 7.16 ~~协议答题（Q86）~~

~~三种协议理解的认证（`TERMS_APP_UNDERSTOOD`、`CONTEST_RULES_UNDERSTOOD`、`TERMS_SPONSOR_UNDERSTOOD`）、由官方账号出题并登记为认证的一轮（`QB_CERTIFICATION_ROUND_ADD` / `_CLOSE`，权限 `qb.certification.manage` / `qb.certification.admin`）、启动检查 `CERTIFICATION_LEGAL`~~已由 F-S2c2 删去（D3：答题卡；D1：认证、轮与记录，F-S2c2b）。同意的流程一字不改。以后在学者的平台图上建"了解条款"的节点（F-S3 的看图显示）。

- ~~**发起方的前置**（向导"客户匹配"一步要求先持有认证）~~已由 F-S2c2 删去（D4）。
- **已有安装**：已删除的权限 `qb.certification.manage`、`qb.certification.admin` 由 `QB_SETUP` 自动从所有角色（不只 QuizBuks 的角色）上去掉（输出 `permissionsRemoved`，审计中记为删除）：部署后执行一次即可，不用手工改「用户与角色」。排程在以后才开始的这类授予，在开始之后的下一次执行时去掉。

### 7.17 现金账户（Q87a）

发起方除学分钱包外有现金账户（科目 2160），平台上的钱，需要时一比一换成学分（`plans/Q87-fee-account.md`）。部署后执行一次 `QB_SETUP`（开立 2160、提出新参数），由另一位管理员发布。

| 受控参数 | 首个值 | 含义 |
|---|---|---|
| `qb.topup.targets` | `KUDOS,CASH` | 充值可以进入的地方；只给一个时发起方不用选 |
| `qb.topup.default-target` | `KUDOS` | 发起方不选时的去向（须在上一项之内） |
| `qb.kudos.buy.enabled` | 开 | 现金换学分 |
| `qb.kudos.sell.enabled` | 关 | 学分换回现金（不越过待支付的奖励） |
| `qb.transfer.fee-rate` | 0 | 转账按比例的手续费，与固定的 `qb.transfer.fee` 相加，向下取整 |
| `qb.journal.account.2160` | 空 | 分录导出 2160 的科目代码：现金账户有过账后，导出前必须设好 |

- 发布之前一切同 Q87 之前（只进学分钱包、不能兑换）。
- 进现金账户的卡充值被退款或争议败诉时从现金账户扣回；每日核对多两项（`CASH_ACCOUNT`、`TOTAL_2160`）。
- 月报多一列"2160 发起方现金账户"与六类（现金充值、银行转账进现金、现金退款、现金争议、现金换学分、学分换回现金）。

### 7.18 平台费（Q87b）

平台费记在发起方的现金账户上（§7.17）：**学分只有三个来源**（充值直接成为学分、现金换学分、手工加币），费用从现金账户付。缺省 = 现状：不收月费、预付。

- **开关都是受控参数**（`QB_SETUP` 提出缺省值，另一位管理员发布；之后改任何一个都是四眼）：`qb.fee.monthly`（每发起方每月的平台费，`0` = 不收，也不出账单）、`qb.fee.tax-rate`（消费税率，`0.10`）、
  `qb.fee.default-billing-mode`（`PREPAID`）、`qb.fee.overdue.block-submit`（有未付账单时拦提交与换学分，`true`）；普通参数 `qb.fee.due-days`（账单到期天数，`30`）。
  开关未发布时同 Q87 之前（不拦、不记费），与 §7.17 的读法一样。抽成 `qb.creator-fee-rate` 两处都保留（结算、提现）：需求方决定上线时设为 `0`（受控变更），什么时候收、收多少都只改参数。
- **部署后**：运行一次 `QB_SETUP`（新科目 1130 / 4120 / 2150、新权限 `qb.fee.manage` / `qb.fee.billing`、四个受控参数与审批规则 `qb.fee-request.review` 的提案），发布提案；科目映射多三项 `qb.journal.account.1130 / 4120 / 2150`（不设即下个月的分录导出被拒 `QB_JOURNAL_ACCOUNT_UNMAPPED`）；
  账单 PDF 的开具方沿用收据的 `qb.issuer.*`（没设名称时发起方下载账单被拒 `QB_FEE_PARAMS`）。
- **预付 / 后付**：缺省全平台预付；运营商（或总部财务）在「运营商 → 发起方」给单个发起方设后付与信用额（理由必填，`QB_OPERATOR_BILLING_SET`，审计中可查）。预付的发起方记费时立即从现金扣、不够即一张 `OPEN` 账单；后付的发起方每月 2 日 01:17（日本时间）出上月的账单。
  **有未付账单的发起方不能换学分、不能提交新发布**（预付：任何未付；后付：未付合计超过信用额；开关 `qb.fee.overdue.block-submit`），其他一切照常；不自动停用。
  后付的发起方可以在月底之前**提前付清**尚未开账单的费用（钱包页的"现在用现金账户付清"，`QB_FEE_PAY_EARLY`）：每笔费用各开一张已付的账单，月初的账单只合成剩下的。运营商在「运营商 → 发起方的计费方式」看到每个发起方的计费方式、未付、逾期与尚未开账单的金额，并在那里改。
- **任务**：`qb.fee-monthly`（每月 2 日 01:07 日本时间，记当月月费）、`qb.fee-invoice`（每月 2 日 01:17，后付的账单）、`qb.fee-overdue`（每日 02:37，过期的账单 → `OVERDUE`，提醒邮件 7 天一封至多三封，待办给 `qb.fee.manage` 与发起方的运营商）。都是逐项任务、每项一个事务，可补跑（已记过的月份不再记）。
- **手工记费与作废**：平台管理系统「QuizBuks 财务 → 平台费」：财务（`qb.fee.manage`）登记手工记费（发起方、金额、理由）或作废账单（理由），另一位持有 `qb.topup.bank.approve` 的管理员在待办中批准；作废 = 冲正该账单的分录，已付的退回发起方的现金账户。更正一律这样做，不改记录。
- **核对**（每日 `qb.reconcile`）多一项 `FEES_RECEIVABLE`（1130 ≠ 未付账单 + 尚未开账单的后付记费）；处理同 §7.2。
- **月报** `qb.admin.monthly` 多三类：`FEE_CHARGED`、`FEE_PAID`、`FEE_VOIDED`，多三列 1130 / 4120 / 2150；运营商的结算单多一行"平台费"（并入分成的基数）。
- **收据与邮件**：进现金账户的充值，收据的"计入"一行与邮件（`qb.topup.paid-cash`）写明进了现金账户，不说存入学分。
- **告警**：`jabiz.job.item{job="qb.fee-monthly"|"qb.fee-invoice"|"qb.fee-overdue", result="FAILED"}`；`qb.fee.invoice-overdue` 的待办堆积（发起方长期不付）。

### 7.19 ~~发起方自己的认证（Q88a）~~

~~发起方定义自己的认证、把发布登记为它的轮、0 奖励的轮、受控开关 `qb.certification.sponsor-rounds.enabled`~~已由 F-S2c2 删去（D6，需求方 2026-11-08 确认）。以后的门槛与证书都按知识结构图的节点（§7.27、F-S5）。

- **留下的**：之前定义的认证、学者持有的记录与签发的证书照旧有效（D8）：App 的"我的认证"、公开核实页与学者公开页照常写明"由 <发起方> 颁发"，持有到有效期为止。这些行只读，没有人能再建立、修改或停用。
- **之前登记的轮不再算**：合格不再记入（认领不再补记），发布卡片与发布页不再显示"合格授予"；草稿删除时连同它的轮一起删除；各档位都是 0 学分的发布不能提交（绑定节点的除外，F-S2b1）。
- 发起方页面资源库的「认证」标签、发布向导的"合格授予的认证"、发布页的"关闭这一轮"与平台管理系统「认证」页的"发起方自己的认证"一节都已去掉；旧的 `/certifications` 地址转到资源库。

### 7.20 报名费（Q88b）

发起方可以给测验设报名费（`plans/Q88-sponsor-certifications-entry-fee.md` §11）：学者开始时从**可转出的学分**中付（近期与争议中的奖励、审核中的转账所占的不能用；`qb.transfer.hold-days` 不合格时一律不收），一人一次、不退；钱先记在 2170 报名费待入账，每日由任务入账到发起方的现金账户。缺省 = 现状：不开放。

- **开关是受控参数** `qb.play.entry-fee.enabled`（缺省 `false`，`QB_SETUP` 提出，另一位管理员发布）。它只管**能不能设**：关着时保存带报名费的草稿被拒（`QB_ENTRY_FEE_OFF`），已上架的照收（要停就由运营商下架或发起方结束）。问卷不能设（`QB_ENTRY_FEE_NOT_ALLOWED`），店面的发布没有报名费。
- **部署后**：运行一次 `QB_SETUP`（开出科目 2170、提出参数 `qb.play.entry-fee.enabled` 与科目映射 `qb.journal.account.2170`）并发布提案；**上线前给 2170 配好会计科目代码**（`JournalMappingCheck` 否则告警，分录导出拒绝未映射的科目）。
- **任务** `qb.entry-fee-settle`：每天 18:07 UTC（03:07 日本），每个 2170 有余额的发起方一个事务（发起方钱包锁内 2170 → 2160 并更新现金缓存）；`quizbuks.entry-fee.settle-cron`（`QB_ENTRY_FEE_SETTLE_CRON`）一般不改（e2e 也不改：测试以补跑 `POST /api/jobs/qb.entry-fee-settle/runs` 当即入账，F-F13）。失败的发起方下次照常入账（按余额，不按日期）。
- **核对**：每日核对多一项 `ENTRY_FEES_UNSETTLED`（2170 按发起方不为负）；2120 = 学者缓存、2160 = 现金缓存照旧。月报多两类 `ENTRY_FEE`（付报名费）与 `ENTRY_FEE_SETTLED`（入账）；财务总览的"钱包"一节多"报名费待入账（2170）"。
- **答题路径**：只有收报名费的开始多取用户的钱包锁（在开始锁之前）、多读钱包行与可转出额、多一笔分录；普通的开始一条语句也不多（`AttemptStatementBudgetIT`）。
- **争议**：报名费不退（超时、放弃、下架都不退）；学者投诉时由运营商以手工加币处理。报名费不是平台收入，不进月结算与运营商分成。

### 7.21 发起方的资源库（F-C1）

发起方把常用的图片、文件、链接、文章、文字与奖品卡片存进自己的资源库（`docs/quizbuks/plans/impl/F-C1.md`），在 Quiz 编辑器与发布向导中"从库里选"（复制进去）与"存入资源库"。两张普通表：资源 `QbAsset` 与"用在哪里" `QbAssetUse`；写入只经 `QB_ASSET_SAVE` / `QB_ASSET_DELETE` / `QB_ASSET_COLLECT`（发起方本人，锁 `qb.assets:<发起方>`）与四个内容保存流程登记的使用记录。答题路径不读资源。给发起方的帮助文案在 `docs/quizbuks/help/asset-library.md`（由内容管理员发布为 FAQ）。

- **参数**（普通参数，不受控：不涉及钱与审核；`QB_SETUP` 提出缺省值，之后以平台的 `PARAM_SET` 修改，对之后的保存立即生效）：

  | 参数 | 缺省 | 作用 | 调整的影响 |
  |---|---|---|---|
  | `qb.assets.max-count` | 1000 | 每个发起方的资源个数上限（锁内检查，`QB_ASSET_LIMIT`；收集超出即整批拒绝） | 调低不删已有的资源，只是不能再新建或收集，已有的照常修改 |
  | `qb.assets.max-tags` | 10 | 每个资源的标签个数 | 每次保存都检查：调低以后，标签多于上限的资源要先删掉几个标签才能保存 |
  | `qb.assets.tag-length` | 30 | 每个标签的字数 | 同上 |

  一次收集至多 200 项（`AssetRules.MAX_COLLECT`，代码中的常数）；名称、说明与文字的长度沿用要复制到的那一栏的上限。
- **部署后**：运行一次 `QB_SETUP`（建上面三个参数）；没有新任务、新科目、新告警、新环境变量。
- **客服能看到什么**：平台管理系统中发起方详情页（`/admin/qb/reviews/sponsors/<用户>`，从发起方的链接进入）的"资源库"一栏，持有 `qb.admin.publication.read` 的人（客服、内容与财务管理员）看得到：名称与说明、种类（文字带用途）、标签、修订号、修改时间、用在几处；"用在哪里"列出每个使用处（种类、编号、复制时的修订号与时间），复制在资源最近一次修改之前的标"用的是旧的内容"；Quiz 的封面与简介链到 Quiz 详情，发布的各处链到发布页；资料、题目、选项只有编号（在 Quiz 详情中找）。运营商的成员在运营商管理系统中同样看得到（`/operator/qb/reviews/sponsors/<用户>`），但只限辖下的发起方（`OperatorScopes`；别的发起方读不到，显示为空）。
  **只读**：客服与运营商不能代为修改、删除、恢复或收集；文件的内容在发起方页面上看。要改只能由发起方自己做。
- **"资源不见了"的排查**：
  1. 先确认看的是哪个账号：资源属于发起方本人的账号（`sponsorId` 是用户编号），店员或另一个账号登录看不到。在发起方详情的"资源库"一栏按种类筛选查找。
  2. 不在列表中：请持有 `audit.read` 的管理员查审计（平台的 `/admin/audit` 页，或 `GET /api/audit/records`）：
     - `entityType=QbAsset&processName=QB_ASSET_DELETE&actorId=<发起方的用户编号>`：这个人删了哪些资源、何时删的（审计中有删除前的名称与内容）；知道资源编号时用 `entityType=QbAsset&entityId=<编号>` 看它的全部历史（建立、每次修改、删除）；
     - 删除只能由发起方本人做（`QB_ASSET_DELETE`），平台没有别的删除途径（除删除账号，见第 4 步）。
  3. 资源在、但"用在哪里"少了：使用记录（`QbAssetUse`）在以下情况随之删掉：资源被删除；使用处被删除（删资料、删题目或选项、移除 Quiz、删除发布草稿）；同一处改选了另一个资源（一处只记一个来源）。审计中查 `entityType=QbAssetUse`。发起方之后在那一处手工改了内容，记录不会删（"用在哪里"只是提示）。复制、提交、版本切换出来的新草稿不带使用记录。
  4. 删除账号：账号删除（`ACCOUNT_ERASE`）时两张表随之删除（`SponsorErasure`），之后 `ERASURE_PURGE` 覆盖审计中这些行的个人内容，只剩编号与时间。删除账号之后无法恢复资源。
  5. 告诉发起方：已复制到 Quiz 与发布中的内容不受资源删除的影响；丢了的资源可以用"从我的内容收集"从已有的内容重新收进来，或重新"存入资源库"。平台不替发起方恢复（没有恢复流程，也不直接改表）。
- **"用的是旧的内容"不是故障**：资源改过以后，之前复制的地方仍是旧内容（选了就复制，已提交的发布更不能变）；要用新内容就在那一处重新"从库里选"并保存。
- **文件**：资源的图片、PDF、音频按种类分三列引用平台的文件（不复制文件），被资源或任何使用处引用的文件不会被平台的孤儿文件清扫删掉；资源删除后，没有其他引用的文件由清扫照常删除。

### 7.22 资源占用的计量（F-C2）

每天记下每个发起方占用的存储与内容（`docs/quizbuks/plans/impl/F-C2.md`），显示在发起方的资源库顶部与平台管理系统 / 运营商管理系统的"资源用量"页（`/admin/qb/usage`、`/operator/qb/usage`，模板 `qb.admin.usage`，可导出 CSV / Excel）。**首次上线只计量、显示和提示，不拒绝上传、不收费。** 给发起方的帮助文案在 `docs/quizbuks/help/storage-usage.md`。

- **计量项**只在代码 `usage.UsageMeters` 一处列出：`storage.bytes`（上传的原文件字节，不含缩略图等变体）、`storage.files`、`assets.count`、`assets.text-bytes`、`content.quizzes`、`content.versions`、`content.materials`、`ai.tokens`（当月该发起方 AI 作业的输入 + 输出 token，来自 `QbAiUsage`；AI 问题解答不在其中）。文件按上传者（平台文件的 `uploadedBy`）归属，一个文件一行，不论被几处引用只算一次；外部视频链接只计个数。快照模板 `qb.system.usage-measure` 与这张清单不一致时启动失败（`UsageMetersCheck`）。
- **任务** `qb.usage-snapshot`：每天一次，时刻是部署配置 `quizbuks.usage.snapshot-time`（环境变量 `QB_USAGE_SNAPSHOT_TIME`，`HH:mm`，缺省 `03:17`），按 `jabiz.fiscal-year-zone`（东京）的时刻，不是写死的 UTC；改了要重启，格式不对启动失败（需求方 2026-11-07）。逐项任务，每个发起方（有入驻记录、账号未删除）一项 `QB_USAGE_SNAPSHOT_ITEM`（`internal()`，不取业务锁，一个事务），写普通表 `QbUsageDay`：每个发起方每天一行，全部计量项在 `meters` 列的 JSON 对象中（`{"storage.bytes": …, …}`，审计因此每人每天一条），日期是计划时刻在部署时区的日期。同一天再跑一次只覆盖当天的那一行（数值没变不写），不会重复。只读查询某一项：`SELECT snapshot_day, CAST(meters AS jsonb) ->> 'storage.bytes' FROM qb_usage_day WHERE sponsor_id = …`。答题、发布、上传的路径一条语句也不加。
- **参数**：

  | 参数 | 缺省 | 作用 | 调整的影响 |
  |---|---|---|---|
  | `qb.usage.storage.free-bytes` | 524288000（500 MB） | 免费额度，**受控参数**（四眼：`CONTROL_CHANGE_PROPOSE` 后由另一位管理员发布），只用于提示 | 之后的快照按新额度判断"超过"；调低可能让很多发起方同时收到一条站内信。没有已发布的值时不提示、不发信（页面显示已用而不显示额度） |
  | `qb.usage.retention-days` | 800 | 快照的保留天数（普通参数，`PARAM_SET`） | 之后的每次快照删掉该发起方更早的行；调短会删掉月度统计要用的旧行 |

- **超过免费额度**：只提示。快照项发现 `storage.bytes` 从额度以下（或没有前一次快照）变成超过时发事件 `qb.usage.free-exceeded`，消费者 `qb.msg-usage-over` 以 `QB_MESSAGE_FROM_EVENT` 写一条站内信（种类 `USAGE_OVER_FREE`，链到资源库）；同一次越过只一条（`sourceKey` = `usage-over-free:<发起方>|<日期>`，重跑与重投都不重复），降到额度以下后再超过另发一条；消费者重读最新快照，已不超过即不发。
- **部署后**：运行一次 `QB_SETUP`（提出两个参数，其中免费额度是受控的，由另一位管理员发布提案）；迁移 `V79`（合并前重编号）建表 `qb_usage_day`；需要时设 `QB_USAGE_SNAPSHOT_TIME`。没有新科目、新权限给角色（`qb.usage.system` 不授予任何角色）。
- **告警**：见 §5 的"资源占用的计量"一行：任务 `FAILED` / `PARTIAL`、当天在快照时刻后 2 小时内没有成功的运行、`qb_usage_day` 最新日期早于今天。失败项的错误在 `GET /api/jobs`（`job.read`）的"连续失败中的项"中。
- **补跑**：快照记的是运行那一刻的占用，**只能补跑当天**（过去的某一天无法事后补出当时的数字；月度统计按有快照的天数平均，"天数"一列看得出缺了哪几天）。
  1. 先看 `GET /api/jobs` 中 `qb.usage-snapshot` 最近的运行与失败的项，排除原因（多为数据库超时或约束；修好再跑）。
  2. 在平台管理系统的「定时任务」页补跑（平台 17zs，决策 D77；需要 `job.run` 与近期二次验证，写原因）：任务 `qb.usage-snapshot`，计划时刻选**当天**的那一次（部署时区的 `QB_USAGE_SNAPSHOT_TIME`）。已成功的项照旧（重放），失败或没跑到的项照常测量。**不要选过去某天的时刻**：快照记的是运行那一刻的占用，选过去的时刻会把今天的数字记到那一天。当天的行被覆盖，不会重复；越过额度的站内信不会重复。
  3. 开发与测试中直接以 `JobRunner.run(qbUsageSnapshotJob, 当天的某一时刻)` 重跑当天（`UsageSnapshotIT` 照此）。同一计划时刻再跑一次只会重放（`REPLAYED`），要用当天另一个时刻。
  4. 单个发起方的项失败时，平台在之后的运行中按退避重试；重试记的是重试那一天的快照，失败的那一天仍然缺（照第 2 步当天补跑）。
- **自动收尾**：超过保留期的行由每天的快照删除（每项删本人的旧行）；删除账号时随 `SponsorErasure` 删除。没有需要手工清理的表。
- **谁看得到**：发起方本人（资源库顶部，`qb.sponsor.usage`；每个资源的大小与使用处 `qb.sponsor.asset-stats`）；持有 `qb.admin.publication.read` 的管理员（客服、内容与财务管理员）看全部发起方；运营商的成员只看辖下的发起方（`OperatorScopes`）。发起方详情页的"资源库"一栏有"用量"链接，打开该发起方的用量。
- **收费不在首次上线**：月度的平均与峰值（`qb.admin.usage`）是以后收费的依据；上传上限、变体字节、下载流量都不在本条。

### 7.23 发起方的商品上限（F-C11）

- **参数** `qb.products.max`（普通参数，不受控，缺省 50；`QB_SETUP` 建立，之后以平台的 `PARAM_SET` 修改，对之后的保存与资料包检查立即生效）：每个发起方的商品件数上限。保存新商品时已满即 422 `QB_PRODUCT_LIMIT`；资料包里的商品多于上限即整包拒绝（同一错误码）；商品页从模板 `qb.sponsor.product-limit` 读同一个值。
- **调整的影响**：调低不删已有的商品，只是不能再新增，已有的照常修改、上下架与删除；多于新上限的发起方导出的资料包要先删掉几件才能再导入。
- **部署后**：运行一次 `QB_SETUP`（建这个参数；缺少时启动检查警告，流程按缺省值 50）。没有新任务、新科目、新环境变量。

### 7.24 含境外视频的发布与中国大陆（F-S11）

含有中国大陆无法播放的视频（YouTube 等）的发布，不能从中国大陆的 IP 地址作答（`docs/quizbuks/plans/impl/F-S11.md`）。判断在开始答题时由服务端做（422 `QB_PUBLICATION_REGION_VIDEO`），
首页、发起方主页与分享页只是按页面问到的地区隐藏或说明。地区只按请求的 IP 地址（平台 D74 的 `ClientCountry`，可信代理 `JABIZ_TRUSTED_PROXIES` 之后的地址），与资料中的国家无关；地区定向、人数估算与受众规则费仍按资料中的国家（A3-19）。
给用户的帮助文案在 `docs/quizbuks/help/mainland-china.md`（由内容管理员发布为 FAQ）。

- **IP 地址库**（DB-IP "IP to Country Lite"，CSV，许可 CC BY 4.0）：
  1. 下载：https://db-ip.com/db/download/ip-to-country-lite 的 CSV（`dbip-country-lite-YYYY-MM.csv.gz`，每月初更新）。不需要账号。下载后核对文件不是空的（`zcat … | wc -l`，约 60 万行）。
  2. 放到服务器的卷 `geoip`（`server/compose.yaml` 挂在 `/var/lib/jabiz/geoip`）：复制成临时名，再改名为固定的 `dbip-country-lite.csv.gz`（改名是原子的，平台不会读到一半的文件）：
     `docker cp dbip-country-lite-2026-12.csv.gz <APP_NAME>-app:/var/lib/jabiz/geoip/.next.csv.gz && docker exec <APP_NAME>-app mv /var/lib/jabiz/geoip/.next.csv.gz /var/lib/jabiz/geoip/dbip-country-lite.csv.gz`
  3. 第一次：在环境的 `.env` 中设 `JABIZ_GEOIP_DATABASE=/var/lib/jabiz/geoip/dbip-country-lite.csv.gz`，重新部署。之后每月只做第 2 步：平台一分钟内读入新文件（日志 `Address database …: N ranges`），无需重启。
  4. 核对：用中国大陆与日本的网络（或可信代理之后的测试请求）打开 `/api/client/region`，`country` 分别为 `CN`、`JP`。
  5. **署名**（CC BY 4.0 的要求）：隐私政策写明了使用 DB-IP 的数据（第二条第3项，`docs/quizbuks/legal/`），App 的"关于本 App"（`/about`）"第三方数据"一节也列出并链接 db-ip.com；不要删去这两处。改用别的地址库（如 MaxMind 的 CSV 转换）时同步改隐私政策与本节。
  6. **隐私政策的新版本须重新同意**（需求方 2026-11-07）：加入"按 IP 判断所在地区"与 DB-IP 署名的隐私政策新版本，在"QuizBuks → 规约类"（`/qb/legal`，§3 第 9 步）发布时**勾选"须重新同意"**（是实质性变更，不按措辞修订处理）；三语一起换好再发布，可以填以后的生效时间作预告（建议在部署 F-S11 之前或同时生效）。
     发布时同时勾选"在收件箱中公告"（隐私政策 → App 与发起方页面）。发布后核对：用一个已同意旧版的测试账号登录 App 与发起方页面，应要求同意新版。
  - 过旧（`JABIZ_GEOIP_MAX_AGE`，缺省 45 天）或缺失：启动检查（`GEOIP`；正式模式下没有配置为 `QUIZBUKS` 错误）与每日任务 `qb.region-check`（每天 19:23 UTC）给 `qb.admin.region.monitor`（总部的内容管理员，运营商没有）一个待办（每天一个，链到 `/admin/qb/region`）。处理：照第 1–2 步更新，待办在下一次检查后不再出现，旧的待办手工关闭（`task.resolve`）。
  - 测试系统（staging）可以不放地址库：所有地址查不到，按参数处理（缺省中国大陆，含境外视频的发布在那里不能答）。要在 staging 试答，放地址库，或把 `qb.region.unknown-ip-country` 设为别的国家（见下）。
- **参数**：

  | 参数 | 缺省 | 受控 | 作用 |
  |---|---|---|---|
  | `qb.video.mainland-accessible-sites` | `bilibili,youku,tencent` | 是（提出与发布两人） | 中国大陆可以播放的视频网站（`youtube`、`vimeo`、`bilibili`、`niconico`、`dailymotion`、`youku`、`tencent`）。不在其中的网站与认不出的网站都算"境外"。只影响以后上线与切换版本的发布（卡片在那时冻结）；已上线的卡片不变 |
  | `qb.region.unknown-ip-country` | `CN` | 否（不授予任何角色，由首个管理员以 `PARAM_SET` 改） | 地址库查不到的地址（内网、没有地址库）按哪个国家处理；空 = 不按任何国家（放行）。e2e 设为 `JP` |

  部署 F-S11 后运行一次 `QB_SETUP` 并由另一位管理员发布它提出的 `qb.video.mainland-accessible-sites`；发布之前按缺省值。
- **例外**（A3-15）："中国大陆"页（平台管理系统 `/admin/qb/region`，运营商管理系统 `/operator/qb/region`；读 `qb.admin.region.read`，新增、改到期日、撤销要 `qb.admin.region.write`）。按发布或按发起方（其全部发布，含以后的），**必须写原因**，到期日可选（UTC 的日期，当天有效，过了自动失效，不需要任务）。撤销后不能再改，要恢复就新增一个。
  **谁来设**（需求方 2026-11-07）：总部的内容管理员可以为所有发起方设、看到全部；运营商（角色 `QB_OPERATOR`）只为自己的发起方设、只看到自己发起方的（入口范围；按发布的例外按该发布的发起方归属，记在 `ownerId`）。别的运营商的发起方或发布：新增、修改、撤销一律 404（与不存在相同）。平台管理系统的列表带"运营商"一栏，可按运营商筛选（F-S1b）。发起方改派到别的运营商后，它的例外随之归新运营商。
  每次新增、修改、撤销都经平台审计（`/admin/audit`，`entityType=QbRegionException`；记下是谁做的，运营商的成员同样）。例外只放开本规则，不改变地区定向等其他条件。
- **被拒次数**：答题被本规则拒绝时只计数（不记是谁、不记地址），在内存中按 UTC 日累计，任务 `qb.region-refusals` 每小时写入 `QbRegionRefusalDay`；平台管理系统的"中国大陆"页显示最近 30 天。这是全平台的合计（不分发起方），只给总部看（`qb.admin.region.monitor`），运营商管理系统的同一页没有这一栏。实例停止时最近一小时内未写出的次数会丢（这只是统计）。
- **"在中国大陆不能答"的排查**：
  1. 问用户所在的网络：VPN、公司网络、漫游的手机线路都可能被判为别的地区（地址库按看到的地址判断，需求方接受）。
  2. 平台管理系统的发布详情：卡片的"含中国大陆无法播放的视频"为是（或空：上线于 F-S11 之前且回填没有找到版本；按"是"处理）。看资料中的视频链接是哪个网站。
  3. 是否有生效的例外（"中国大陆"页，"生效中"；运营商只看到自己发起方的）。
  4. 用户说不在中国大陆却被拒：请他打开 `/api/client/region` 看 `country`；是 `CN` 即地址库的判断（可等下月更新，或经例外放开该发布）；`known` 为 `false` 即地址查不到，按参数处理（检查 `JABIZ_TRUSTED_PROXIES` 是否包括反向代理：不包括时所有人都是代理的内网地址）。

### 7.25 竖屏短视频与竖屏模式（F-S10）

App 首页在参数给出的位置插入一排竖屏小卡（"短视频"），点开进入全屏的竖屏模式，一个一个滑动（`docs/quizbuks/plans/impl/F-S10.md`）。给用户的帮助文案在 `docs/quizbuks/help/shorts.md`（由内容管理员发布为 FAQ）。

- **哪些算竖屏**：卡片视频（看完即可答题的视频，否则第一个能播放的视频）是 YouTube 的 Shorts 链接，或发起方在视频资料上勾选了"竖屏视频"。上线与切换版本时冻结到卡片 `videoVertical`（平台管理系统发布卡片的"竖屏视频"）；已上线的卡片不随资料的修改而变，要变就保存新版本并切换。迁移 `V85`（合并前按最大号 + 1 重编）按版本快照回填了已上线卡片中的 Shorts 链接。
- **参数**（普通参数，`PARAM_SET`，不受控；部署后运行一次 `QB_SETUP` 建立）：

  | 参数 | 缺省 | 作用 | 调整的影响 |
  |---|---|---|---|
  | `qb.feed.shorts.positions` | `3,12` | 在第几张卡之后放一排（以逗号分隔，第一张卡为 1；空 = 不放） | 第 n 个位置放第 n 组短视频；卡片不够时第一排放在列表末尾。App 下次打开首页时生效 |
  | `qb.feed.shorts.per-shelf` | `10` | 每排的个数（1–50） | 全屏模式可以滑过全部读到的短视频（至多 50 个） |

- **数据**：短视频由首页模板 `qb.app.feed` 加参数 `shorts=true` 读取，条件与首页相同（地区、客户匹配、语言、搜索与筛选、F-S11 的中国大陆、以后 F-S8 的屏蔽）；没有竖屏发布时不显示这一排。答题路径一条语句也不加。
- **打开数**：每次打开竖屏模式计入平台的公开计数器 `qb.shorts-open`（按打开时的发布 `publicationId`，每个访客每个发布每天一次，日期按 `jabiz.fiscal-year-zone`），写入 `sys_public_counter_day`。只读查询：`SELECT day, sum(hits) FROM sys_public_counter_day WHERE counter_name = 'qb.shorts-open' GROUP BY day ORDER BY day DESC`。发起方经 `me:PublicCounterDay` 只看到自己发布的数字；显示页面随 F-S13（推送与曝光的计数）一起做。
- **"短视频不出现"的排查**：
  1. 发布卡片的"竖屏视频"是否为是（平台管理系统的发布卡片；审核页的"竖屏"提示）。不是：卡片视频不是 Shorts 链接也没有勾选，或者"看完即可答题"的视频是另一个横屏视频。
  2. 学者在首页能否看到这张卡（短视频与首页同样的条件）；看不到就按首页的排查（地区、客户匹配、中国大陆 §7.24 等）。
  3. 参数 `qb.feed.shorts.positions` 是否为空或位置太靠后（卡片不够且还有下一页时，那一排要等滚到那里才出现）。
  4. 全屏模式中只有一个播放器：YouTube、Vimeo 由 App 的播放器播放并可以开声音；其他网站点播放器本身开声音（A3-18）。系统设为"减少动态效果"时不自动播放，要点"播放"。

### 7.26 主题词的词表（F-S9）

Quiz 与发布有主题词（平台词表中的词与自定义词），学者可以选喜欢的主题并打开"只看喜欢的主题"（`docs/quizbuks/plans/impl/F-S9.md`）。给用户的帮助文案在 `docs/quizbuks/help/topics.md`（由内容管理员发布为 FAQ）。**词表由总部维护**：平台管理系统的"主题词"页（`/qb/topics`，权限 `qb.admin.topic.read` / `.write`，内容管理员与超级管理员有），新增词不改代码、不用部署。

- **一个词**：代码（`topic_id`，小写字母与数字，单词之间一个连字符，2–40 字，以后不能改，Quiz、发布与学者的选择都记代码）与三语名称（中文、日文、英文都必填）。
- **维护**（都在平台的流程页上做，每次修改由平台留审计记录，`GET /api/audit/records?entityType=QbTopic`）：

  | 操作 | 流程 | 效果 |
  |---|---|---|
  | 新增 | `QB_TOPIC_ADD` | 填代码与三语名称；代码已被用时拒绝（`QB_TOPIC_CODE_TAKEN`） |
  | 改名 | `QB_TOPIC_RENAME` | 三语名称一起改；已选的地方随之显示新名称 |
  | 停用 / 重新启用 | `QB_TOPIC_DEACTIVATE` / `QB_TOPIC_REACTIVATE` | 停用后不能再选；已选的 Quiz、发布与学者照常显示（标为"已不再提供"），"只看喜欢的主题"与统计照常算它 |
  | 合并 | `QB_TOPIC_MERGE`（词、合并到哪个词） | 被合并的词停用，以后在"只看喜欢的主题"与统计中算作目标词，显示目标词的名称；以前合并到它的词一起改为目标词（合并总是一步）。只能合并到可选的词；合并后不能再改、不能撤销（要撤销就新建一个词） |

  不删除词。名称写错了改名；不再用的停用；与另一个词重复的合并。
- **启动检查**：词表中有词缺少某种语言的名称时（只可能来自手工改库或迁移，流程都要求三语），启动检查与 `platformCheck` 报错（类别 `QUIZBUKS`，定位 `topic:<代码>`），**正式模式下应用起不来**。处理：用 `QB_TOPIC_RENAME` 补全名称后重启（应用起不来时先在数据库中补上 `name` 的 JSON，再重启并用流程改一次名留下审计）。
- **参数**（普通参数，`PARAM_SET`，不受控；部署后运行一次 `QB_SETUP` 建立）：

  | 参数 | 缺省 | 作用 | 调整的影响 |
  |---|---|---|---|
  | `qb.topic.max-per-quiz` | `10` | 一个 Quiz 或发布最多的主题词（词表中的词与自定义词合计，最多 30） | 只在保存主题词时检查；调低后已有的不受影响，下次修改时要先减到上限以内 |
  | `qb.topic.custom-max-length` | `30` | 一个自定义词最多的字数（最多 60） | 同上 |

- **数据**：Quiz `qb_quiz_version.topics` / `custom_topics`（`QB_QUIZ_TOPICS_SET`）；版本保存时复制到 `qb_quiz_version_version`；发布的增删 `qb_publication_version.topic_changes`（`QB_PUBLICATION_TOPICS_SET`，只在草稿）；上线与切换版本时冻结到卡片 `qb_publication_card.topics` / `custom_topics`；学者的 `qb_user_settings.liked_topics` / `strict_topics`（删除账号时随设置一起删除，审计中遮蔽）。迁移 `V87`（临时编号，合并前按最大号 + 1 重编）之前上线的卡片没有主题词：在"只看喜欢的主题"中不出现，直到切换版本。
- **"只看喜欢的主题"**：首页模板 `qb.app.feed`（含短视频一排）读学者自己的设置，只列卡片的词表中的词（合并的词按目标词）与喜欢的主题有交集的发布；自定义词、没有主题词的发布不列。指定邮箱的邀请（不进首页）、对话不受影响。推送（F-S13）做时套用同一条件。答题路径不读主题词，`AttemptStatementBudgetIT` 不变。
- **统计**：同一页显示每个词的发布数（全部、进行中）与喜欢的人数（其中打开"只看喜欢的主题"的人数）；人数只在达到最小分组（`qb.stats.min-group`，缺省 5、下限 3）时显示，否则"少于 k"。合并的词的数字算在目标词上。模板 `qb.admin.topics`。
- **以后**：自定义词的搜索在 F-S6（题库与搜索）合入后接上（卡片已保存自定义词）；推送在 F-S13。
- **"学者说看不到发布"的排查**（打开了"只看喜欢的主题"时）：
  1. 学者的设置：`SELECT liked_topics, strict_topics FROM qb_user_settings WHERE user_id = '<学者>'`。
  2. 发布卡片的主题词：`SELECT topics, custom_topics FROM qb_publication_card WHERE publication_id = '<发布>'`；只有自定义词或为空时不会列出（发起方在新版本中加词表中的词后切换版本）。
  3. 合并：`SELECT topic_id, merged_into, active FROM qb_topic WHERE topic_id IN (…)`。
  4. 其余按首页的排查（地区、客户匹配、中国大陆 §7.24 等）；关掉"只看喜欢的主题"后能看到即是主题词的原因。

### 7.27 知识结构图：审核、点亮与撤销（F-S2）

知识结构图的定义、审核与点亮（`docs/quizbuks/plans/impl/F-S2.md`）。编辑与看图的页面在 F-S3；在那之前经流程页面（`QB_TREE_*`）操作。

- **审核**：发起方的图由其运营商的另一位成员审（规则 `qb.tree.review`，持 `qb.review.tree`），运营商的副本由同一运营商的另一位成员审（`qb.tree.copy.review`），平台模板由另一位持 `qb.tree.platform.write` 的平台管理员审（`qb.tree.platform.review`）。三条规则与撤销点亮的规则 `qb.tree.lit-revoke.review` 由 `QB_SETUP` 提出、另一位管理员发布；没有规则时提交被拒（`QB_REVIEW_NOT_CONFIGURED`），从不免审。审核超过 `qb.tree.review.remind-after` 天（普通参数，缺省 3）由任务 `qb.tree-review-remind`（每天 00:43 UTC）给审核方一个提醒待办（每个版本一次，审核结束、撤回或停用时关闭），并计入每日核对的 `REVIEW_OVERDUE`。
- **新版本与停用**：新版本不能去掉仍有进行中发布（排程、进行中、暂停、预算不足）绑定的节点（`QB_TREE_NODE_IN_USE`，列出这些发布）：保留节点，或等发布结束。停用整张图时其节点失效，绑定的进行中发布在同一事务中结束（结束原因 `TREE`，发起方照结束通知收到邮件）。
- **点亮**：学者在绑定节点的发布上合格后，事件 `qb.tree.passed` 由 `QB_TREE_EVALUATE` 在答题之外判断并写只追加的点亮记录，再逐层连带判断依赖它的节点（每层一个事务，至多 `qb.tree.max-depth` 层）。点亮永久；已点亮的再合格不写新记录。
- **节点对一个人的三态与不可见**（F-S2d）：节点对每个学者是三态之一：**未满足前置**（答题前须点亮的节点还有没点亮的）、**可挑战**（前置都已点亮，含挑战中）、**已点亮**。未满足前置时，绑定该节点的发布对这个人**等同于不存在**：首页不列出，详情与档位读不到，查看（`QB_VIEW`）、开始答题、推荐链接的生成与领取都 404，收件箱中发起方关于它的通知与推广不显示、不计入未读（系统公告照旧）；本人已有答题记录的发布照旧能看自己的结果。公开页（不登录）只列没有答题前条件的绑定发布。前置点亮后发布即出现，发布页写明节点名与"合格即点亮"，合格并点亮后写"已点亮"。点亮一律按**图与节点两者**核对（节点编号只在一张图内唯一，副本沿用模板的编号）：条件中的节点在版本生效时解析到一张图（本图优先，否则可引用的生效图中唯一有它的一张）；两张以上都有即提交时拒绝（`QB_TREE_AMBIGUOUS_NODE`，启动检查 `QB_TREE` 也报告）。学者问"为什么看不到某个发布"：多半是还没点亮它要求的前置节点。
- **撤销点亮**：运营商成员（本运营商的发起方的图与副本）或平台（任何图）以 `QB_TREE_LIT_REVOKE` 填写原因申请（持 `qb.tree.lit.revoke`；本人的点亮不能自己申请），另一位成员或管理员在审批中批准后才写撤销记录（只追加），学者收到站内信（`TREE_LIT_REVOKED`，含原因）；驳回则点亮照旧、记下驳回理由。撤销后，原来那次合格不再点亮它；之后新的合格照常点亮（新的一条记录）。撤销**不连带下游**：已经因它点亮的节点照旧点亮；前置因此不再满足的发布按上面的规则对这个人不可见（已有答题记录的仍能看自己的结果）。节点证书随之作废与扣回赠送的部分在 F-S5、F-M。
- **每日核对**（任务 `qb.tree-check`，每天 19:23 UTC，模板 `qb.admin.tree-health`）：合格了却没有点亮的（`UNLIT_PASS`：事件丢失或判断失败）、点亮记录指向不存在的节点（`ORPHAN_LIT`：绕过流程写入的数据）、等待审核过久的版本（`REVIEW_OVERDUE`）、门槛参数指不到可点亮的节点（`GATE_INVALID`，见下）、生效的节点引用了没有唯一一张图的节点（`BEFORE_ANSWER_UNRESOLVED`：这个引用永远不算点亮，绑定的发布对所有人不可见）。有问题即给持 `qb.admin.trees.read` 的人一个待办（每天一个）。`UNLIT_PASS` 由随后的任务 `qb.tree-recheck`（19:33 UTC，逐项）自动重新判断；次日仍有即查 `QB_TREE_EVALUATE` 的失败记录（平台的操作记录）。`ORPHAN_LIT` 不自动处理：查是谁写入的，按撤销点亮的流程更正。`BEFORE_ANSWER_UNRESOLVED` 不自动处理：引用的节点所在的图停用、去掉了该节点或出现了同编号的另一张图；改图后重新提交审核。
- **指定门槛节点**（F-S2c）：点亮平台图上指定的节点即开放的平台功能，由四个受控参数各指一个节点：`qb.tree.gate.referrer-node`（推荐朋友）、`qb.tree.gate.benefit-provider-node`（提供权益）、`qb.tree.gate.benefit-sponsor-node`（赞助权益）、`qb.tree.gate.sponsor-apply-node`（在 App 中申请成为发起方，F-S2c2b）。**缺省没有参数 = 关闭**（谁也不能推荐、不能上架或赞助权益，学者不能从 App 申请成为发起方；在发起方页面注册照旧），`QB_SETUP` 不建立它们。
  - 指定：内容管理员先建好、审核通过一张**平台图、点亮者为学者**，节点要求合格（`bindable`），并请运营商的直营发起方发布一个绑定该节点的发布（可以不设奖励档位）；然后一位管理员以受控变更提出 `SysParam`：`paramKey` 为上述键、`value` 为 `<图编号>/<节点编号>`（小写，如管理系统的知识结构图页所示）、第一次另给 `valueKind` `{type: text, maxLength: 73}`，另一位管理员发布。改指另一个节点、关闭（删除该参数）照样是受控变更。
  - 判断（`tree.TreeGates`，流程与发起方页面的模板同一规则）：参数指向生效的、点亮者为学者的平台图中生效的节点，且本人此刻点亮了它（点亮记录没有撤销）。撤销点亮即关闭；图停用、节点去掉或参数写错即对所有人关闭，并由每日核对报 `GATE_INVALID`：改正参数或节点。已生成的推荐链接照旧有效。
  - 申请成为发起方由平台的注册守卫判断（`tree.SponsorApplyGuard`，同一规则；平台决策 D80：按参数的值预读节点与图、本人的点亮与撤销）：只管已登录学者加入发起方入口；运营商自己的发起方（其第一位负责人）照旧不受门槛限制。被拒时 App 显示原因"先点亮……的节点"。
  - 证书暂停签发（D8）：`QB_CERTIFICATE_ISSUE` 一律 422 `QB_CERTIFICATE_PAUSED`，已签发的证书照常核实与下载，F-S5 改为节点证书。
  - 已签发证书在"我的认证"中列到有效期为止（F-S2c2b）：之前记下的撤销照旧使它失效。认证本身、轮与人工授予、收回、认领都已删去（D1），管理系统扩展的「认证」页随之去掉。


### 7.28 共享视频的审核与下架（F-S12、F-C3）

发起方可以把资源库中的视频链接共享给其他发起方，其他发起方在发布向导中从共享目录选用（`docs/quizbuks/plans/impl/F-S12.md`；资源库一侧与运营闭合见 `docs/quizbuks/plans/impl/F-C3.md`）。目录不列发起方本人的视频（自己的视频从资料中选；草稿以前选过自己的，照常保留）。给发起方的帮助文案在 `docs/quizbuks/help/shared-videos.md`（由内容管理员发布为 FAQ）。

- **部署**：迁移 `V90`建 `qb_shared_video`，发布与卡片各加列。部署后运行一次 `QB_SETUP`：补上新权限 `qb.admin.shared-video.read` / `.write`（内容管理员与运营商），并**提出审批规则 `qb.shared-video.review`，由另一位管理员发布**。发布之前，共享一律被拒（`QB_REVIEW_NOT_CONFIGURED`）。
- **审核**（提供方所属的运营商，F-S1 的审核去向）：共享的视频出现在运营商管理系统的待办与"共享视频"页（`/operator/qb/shared-videos`，缺省列出待审核的）。打开一个视频：三语标题、在视频网站上观看的链接、时长、竖横、主题词；用平台的审批面板通过或驳回（驳回写理由，提供方看得到）。标准是娱乐内容：合法、不侵权、适合所有学者。**通过一次即可**：其他发起方使用时只审核他们的发布，不再审视频。审批人要持 `qb.review.publication`（运营商角色 `QB_OPERATOR` 有）。每个发起方都有运营商（F-S1），所以没有"直营"的单独队列：总部的审核人员（不带入口范围的会话）照平台的审批规则也能看到并决定任何运营商的案件。审核的待办随共享开立，决定、撤回或封禁撤回时关闭。驳回时提供方收到站内信（带理由，链到资源库）。
- **平台**：平台管理系统的同一页（`/admin/qb/shared-videos`）列出所有运营商的共享视频，带"运营商"一栏，可按运营商筛选；平台不审核（规则的审批人是运营商的审核人员），只在紧急时下架。
- **下架**（违法、侵权）：视频的页面上"下架"（平台的流程页 `QB_SHARED_VIDEO_TAKEDOWN`，理由必填，总是要求二次验证）。运营商只能下架自己发起方的视频（别的 404），平台可以下架任何一个。只能下架"共享中"或"已撤回"的（已撤回的视频仍可能被已提交的发布使用）；待审核的请驳回。下架不能撤销。
  1. 提供方立即收到邮件 `qb.shared-video.taken-down`（带理由）与一条站内信（F-C3，链到资源库；没有邮箱的也收到站内信），"我共享的视频"与资源库该行显示"被下架"与理由。
  2. 用到它的发布由每分钟的任务 `qb.shared-video-ends` 逐个结束（结束原因"所用的共享视频已下架"，`end_reason = SHARED_VIDEO`），进行中的答题照常完成；每个使用方收到邮件 `qb.publication.shared-video-ended`。这**不是**下架发布：不算使用方违约，可以复制，同一 Quiz 以后的发布照常审核。
  3. 费用（曝光费的结算等）随 F-M8，本项不收不退。
  4. 核对：视频的页面下方"用过它的发布"（F-C3，模板 `qb.admin.shared-video-uses`）列出用这个视频上架过的发布，带发起方、状态、结束原因与时间；一分钟后应全部为"已结束 / 所用的共享视频已下架"（页面顶部的"使用中的发布"随之变为 0）。运营商只看到自己发起方的发布，总部看到全部。也可直接查：`SELECT c.publication_id, c.status FROM qb_publication_card c WHERE c.shared_video_id = '<编号>'`。没有结束的：查任务的执行（`/admin/jobs`，`qb.shared-video-ends`）与失败的项。
- **撤回**：提供方自己在资源库中撤回；只影响以后的新发布（已提交的照常用到结束）。撤回的视频仍可下架。提供方的账号删除（Q72）时，其待审核与共享中的视频自动撤回（`QB_SHARED_VIDEO_ERASED_WITHDRAW`，总控 2026-11-08）。**封禁**提供方（总部的 `QB_USER_BAN` 或运营商的 `QB_OPERATOR_SPONSOR_BAN`）时同样在封禁的事务中撤回（F-C3；审核中的连同审批撤回，已驳回、已下架的不动，已提交的发布照常进行）；解封不恢复共享，提供方需要时重新共享（重新审核）。
- **审核期间被下架**：发布审核通过、要上架时再查一次；所用的共享视频已下架即不上架，退回草稿（`reject_code = QB_SHARED_VIDEO_TAKEN_DOWN`），发起方收到"发布被退回"的邮件并在发布页看到原因。
- **使用数**：运营商看到的"使用中的发布"只含自己发起方的发布；平台看到全部。共享视频的主题词只用于目录搜索，不并入发布的主题词。
- **审计**：共享、审核结果、撤回、下架都经平台的写入路径（`GET /api/audit/records?entityType=QbSharedVideo`）；发布的结束在发布的历史中。
- **"选不到某个共享视频"的排查**：视频的状态（平台管理系统的"共享视频"页；只有"共享中"的在目录中）；提供方的入驻记录被删除（账号删除）时目录不列它。
- **数据**：`qb_shared_video`（共享的内容不变；状态、理由、时间由流程写）；发布 `qb_publication_version.shared_video_id`（草稿时 `QB_PUBLICATION_SHARED_VIDEO_SET`，提交即冻结）；卡片 `qb_publication_card.shared_video_id`、`video_provider_id`（上线时冻结，供以后的屏蔽 F-S8 与统计）。卡片的视频即共享视频，除非 Quiz 有"看完才能答题"的视频；App 的开始答题页以卡片的共享视频开头（Quiz 自己的视频仍在资料中）；共享视频的链接计入"中国大陆看不到"（§7.24）与竖屏（§7.25）。答题路径不变。

### 7.29 任意网址的视频（F-S15）

视频资料可以是任何 https 地址（`docs/quizbuks/plans/impl/F-S15.md`）：七个已知网站照旧用各自的播放器；**视频文件**（路径以 `.mp4`、`.webm`、`.mov` 结尾，查询串与片段不算）用 App 自己的 `<video>`，可以设为"看完才能答"；**其他网页**原样嵌入（iframe，沙箱中不能跳转、弹窗、提交表单）。给用户的帮助文案在 `docs/quizbuks/help/any-video.md`，常见问题见 `troubleshooting.md` T16、T17。没有新的环境变量、参数或部署设置。

- **放视频的服务器的要求**（平台自己的服务器：正式环境 `videos.quizbuks.com`，测试环境 `videos.staging.quizbuks.com`；需求方 2026-11-08 确认）：
  1. https（http 的地址不算视频）；用标准端口（地址中不能写端口）。
  2. 支持 HTTP Range 请求（`Accept-Ranges: bytes`、`206 Partial Content`）：浏览器拖动进度条、iPhone 播放都要靠它。
  3. 正确的 `Content-Type`（`video/mp4`、`video/webm`、`video/quicktime`），不要 `application/octet-stream`。
  4. mp4 的 moov 放在文件开头（faststart，如 `ffmpeg -i in.mp4 -c copy -movflags +faststart out.mp4`）：否则要下载完才开始播放。最好用 H.264 + AAC；`.webm` 在部分 iPhone 上放不了。
  5. 普通播放与读时长都不需要 CORS（`<video>` 不带 `crossorigin`）；不需要 Cookie。
  6. 要用签名地址时，签名的有效期要长于发布的期间：地址随版本冻结，过期即打不开（App 改为封面与链接）。
- **中国大陆**：程序只保证**不拦截**这些视频；能不能看到、多久开始播放取决于服务器与线路（海外机房或 Cloudflare 在中国大陆可能很慢或打不开）。要让中国大陆的学者能答含这类视频的发布：
  1. 确认服务器从中国大陆能访问（上线检查清单"从中国大陆访问"一行，含开始播放的等待时间）；
  2. 总部经受控变更（四眼，`CONTROL_CHANGE_PROPOSE` / `_PUBLISH`）把主机名加进 `qb.video.mainland-accessible-sites`（主机名小写、不带协议与路径）：**正式环境只登记 `videos.quizbuks.com`**（值如 `bilibili,youku,tencent,videos.quizbuks.com`），**测试环境只登记 `videos.staging.quizbuks.com`**（值如 `bilibili,youku,tencent,videos.staging.quizbuks.com`）。这两个主机**不写在 `QB_SETUP` 或代码中**：`QB_SETUP` 提出的初值仍只有三个网站的键，主机由总部在各环境按上述各登记一个。**参数值至多 200 个字符**（参数定义 `maxLength` 200，已有安装的定义不随代码改变），只够登记几个主机：主机多了先合并到少数几个域名。之后上线或切换版本的发布按新值冻结，**已上线的卡片不变**（要变就切换版本）；
  3. 只对个别发布或发起方放行时，用 §7.24 的例外（总部或运营商设）。
- **审核**：审核页对所审的版本标出"非已知网站的嵌入页"并逐个列出地址：嵌入页的内容由对方网站控制，审核人照内容标准检查；上线后内容被改，照常经举报下架（Q10b）。
- **安全**：学者 App 与发起方页面的内容安全策略是 `frame-src https:`、`media-src 'self' https:`（`application.yml`，`QuizbuksAppIT` 检查）；其他网页的 iframe 有固定的沙箱与 `referrerpolicy="no-referrer"`。不要为某个网站放宽沙箱。
- **没有做的**：服务器端探测（判断地址是不是视频、能不能嵌入）要发往任意主机，须先改平台决策 D38；HLS（`.m3u8`）；发起方把视频上传到平台的服务器（需求方 2026-11-08 定：只放平台自己的视频，发起方贴链接使用）。
- **共享视频（F-S12）**：共享的视频链接同样可以是三类中的任何一类（共享时按同一规则判断"QuizBuks 能播放"）；卡片上共享视频照旧优先（除非 Quiz 有"看完才能答"的视频），即使它是其他网页；审核运营商对其他网页的共享视频同样照内容标准检查。

### 7.30 屏蔽（F-S8）

学者可以屏蔽一个发布（"不想看"）、一个 Quiz 的所有期（含以后新发的）、一个发起方，或者共享视频的制作方（`docs/quizbuks/plans/impl/F-S8.md`）。给用户的帮助文案在 `docs/quizbuks/help/blocks.md`（由内容管理员发布为 FAQ）。**硬屏蔽是平台原则：付多少钱都越不过**（奖励、推广、受众规则，以后的付费曝光同样不能越过）。

- **部署**：迁移 `V95`建 `qb_block`，卡片加列 `quiz_id` 并按版本回填（一条 `UPDATE`，卡片多时几秒）。部署后运行一次 `QB_SETUP`：建两个普通参数，给内容管理员补上 `qb.admin.block.read`。没有任务，没有新的环境变量。
- **生效的地方**：App 的首页、"短视频"一排、竖屏模式、搜索、发布的页面（显示"你已屏蔽"与解除，不显示内容；已经开始或答过的照常可以看结果、继续答）、收件箱中发起方的通知与带发布的推广（未读数一致）；开始答题 422 `QB_PUBLICATION_BLOCKED`。系统公告照常；发送时的触达人数不减去屏蔽的人（否则发起方能推算出屏蔽人数）。
- **学者自己**：卡片、竖屏模式与发布页面的菜单（⋮）屏蔽，提示中可以撤销；"账户 → App 设置 → 屏蔽"列出全部，逐个解除。
- **参数**（普通参数，`PARAM_SET`，不受控）：

  | 参数 | 缺省 | 作用 | 调整的影响 |
  |---|---|---|---|
  | `qb.block.max-per-user` | `1000` | 一个学者最多的屏蔽数（四种合计，最多 10000） | 满了的学者再屏蔽被拒（`QB_BLOCK_LIMIT`）；调低后已有的照常生效 |
  | `qb.block.count-min-group` | `20` | 屏蔽人数少于它时发起方只看到"少于 N"（下限 5） | 达到时向下取整到 10 的倍数（不小于 N）；调低会让人数更早出现，不要低于 20 除非需求方同意 |

- **发起方**：发起方页面首页的"屏蔽你的学者"一格（模板 `qb.sponsor.blockers`）：屏蔽了它、它的 Quiz 或发布、或它作为视频制作方的不同学者，取整；看不到是谁、屏蔽了什么。
- **总部**：平台管理系统的"屏蔽"页（`/qb/blocks`，平台组，`qb.admin.block.read`，内容管理员与超级管理员有，运营商没有）：按种类的屏蔽数与学者数、有屏蔽的学者总数、被屏蔽最多的发起方（取整，少于 N 不列）。**只有汇总**：屏蔽表的数据视图没有角色能读；排查个别学者时只在数据库中查（下面），不对发起方透露。
- **删除账号**：学者的屏蔽随之删除（`PersonalDataEraser`）；被屏蔽的发起方删除账号时，别人屏蔽它的行保留（只有编号）。
- **审计**：屏蔽与解除都经平台的写入路径（`GET /api/audit/records?entityType=QbBlock`，只有持 `audit.read` 的人）。
- **"学者说看不到某个发布"的排查**：
  1. 学者的屏蔽：`SELECT kind, object_id, created_at FROM qb_block WHERE user_id = '<学者>'`。
  2. 卡片的对象：`SELECT publication_id, quiz_id, publisher_id, video_provider_id FROM qb_publication_card WHERE publication_id = '<发布>'`；四者之一出现在第 1 步即被屏蔽（请学者在"App 设置 → 屏蔽"中解除）。
  3. 其余按首页的排查（地区、客户匹配、中国大陆 §7.24、只看喜欢的主题 §7.26）。
- **"发起方问谁屏蔽了我"**：不回答。屏蔽只属于学者本人，发起方只看到取整的人数。

### 7.31 曝光计数与争议（F-S13）

用了**其他发起方共享视频**（F-S12，卡片的提供方不是发布方）的发布，学者看得够久即计一次曝光（`docs/quizbuks/plans/impl/F-S13.md`）：竖视频在 App 的竖屏模式中（这类发布在那里作为"推送"每隔几屏出现一个，几家发起方轮流，打开过的不再推送给这个人），横视频在开始答题页（视频在画面上、页面在前台）；首页的预览与自动播放从不计。每位学者每个发布至多一次，每天至多 `qb.exposure.daily-cap-per-user` 次（日界按 `jabiz.fiscal-year-zone`，即日本时间的午夜，每日核对同）；发布方、提供方本人及两边的店员（Q39）不计；用自己视频的发布不计、不推送。给用户的帮助文案在 `docs/quizbuks/help/exposure.md`。屏蔽（§7.30）与"只看喜欢的主题"（§7.26）同样作用于推送：付多少钱都越不过。

- **部署**：迁移 `V97`建 `qb_exposure`，发布（只追加表，只加列）与卡片加列 `exposure_purchased`。部署后运行一次 `QB_SETUP`：建九个普通参数（下表），给内容管理员补上 `qb.admin.exposure.read` / `.monitor`、给运营商补上 `qb.admin.exposure.read`。两个任务：`qb.exposure-ends`（每分钟，逐项）、`qb.exposure-check`（每天 UTC 19:47）。没有新的环境变量。
- **已购次数**（总控决定 A）：发布的 `exposurePurchased`，空即不限（永不因曝光结束）。只由 F-M8 的购买流程经子流程 `QB_EXPOSURE_PURCHASED_SET` 写入；**本项没有任何页面能改它**（F-M8 之前所有发布都是"不限"）。
- **用完即停**：每分钟的 `qb.exposure-ends` 找出成功次数 ≥ 已购的进行中发布，每个以 `QB_PUBLICATION_EXPOSURE_END` 结束（结束原因"曝光已用完" `EXPOSURE`，不是下架，可以复制）；进行中的答题照常完成，发起方收到"发布已结束"的邮件。补跑用平台的"定时任务"页（`job.run`）。
- **超出不计费**：上报不取发布锁，任务每分钟一次，所以成功次数可能略超已购；超出的照记，**计费次数 = min（成功，已购）**（F-M8 按它结算）。发起方页面的发布页"共享视频的曝光"一节显示已购（或"不限"）、已成功、计费、超出、推送被打开、去答题。
- **参数**（普通参数，`PARAM_SET`，不受控）：

  | 参数 | 缺省 | 作用 | 调整的影响 |
  |---|---|---|---|
  | `qb.exposure.seconds` | `10` | 连续观看多少秒算一次（视频更短时看完即算；1–600） | 调高后难计；App 在下次读设置时（约 10 分钟内）照新值计时 |
  | `qb.exposure.daily-cap-per-user` | `200` | 每位学者每天至多计多少次（1–10000） | 调低会让更多账号出现在可疑刷量页 |
  | `qb.exposure.push.every` | `5` | 竖屏模式中每几屏一个推送（1–50） | 调低推送更密，影响学者体验 |
  | `qb.exposure.report.batch` | `20` | App 攒多少条报告后上报（1–50） | 调低请求更多（限流每人每分钟 30 次） |
  | `qb.exposure.report.interval-sec` | `30` | App 至多攒多少秒后上报（5–600） | 调高时数字更新得慢（切到别的应用、锁屏或离开页面时会先发，未发出的下一个页面补发） |
  | `qb.exposure.report.max-per-call` | `50` | App 一次调用至多上报几条（1–200） | 超过的调用被拒（`QB_EXPOSURE_BATCH_TOO_LARGE`）；App 读到新值后照分批 |
  | `qb.exposure.reconcile.min-tolerance` | `20` | 每日核对允许成功超出已购的最少次数 | 调低会让并发造成的小超出也成为差异 |
  | `qb.exposure.reconcile.tolerance-percent` | `5` | 或超出已购的百分比（向上取整），取较大者（0–100） | 同上 |
  | `qb.exposure.reconcile.end-grace-sec` | `60` | 结束后多少秒内的计数不算差异（途中的报告；0–86400） | 调低会让结束时途中的报告成为差异 |

- **限流**：`QB_EXPOSURE_REPORT` 每人每分钟 30 次（`application.yml` 的 `jabiz.process.rate-limits`）。
- **可疑刷量**：平台管理系统的"曝光计数"页（`/qb/exposure`，两边共用的组，`qb.admin.exposure.read`）按账号列出被每日上限拒绝过的学者（命中次数：一个发布一天（日本时间）记一次；被拒的发布数；最近一次；已成功次数），总部看全部，运营商只看自己发起方的发布上的。命中多的账号先看它的成功集中在哪些发布（下面的查询），确认刷量时按封禁流程处理（`QB_USER_BAN`）；已记的成功不删除（计费按 min（成功，已购），F-M8 的争议在那里处理）。
- **每日核对**（`qb.exposure-check`）：成功次数超过已购 + 容差（max（`min-tolerance`，⌈已购 × `tolerance-percent`%⌉）），或已结束的发布在结束 `end-grace-sec` 秒之后还有成功，即给内容管理员（`qb.admin.exposure.monitor`）一个待办（一天一个）。差异不改钱（超出本来就不计费）；常见原因：任务停了（看"定时任务"页的 `qb.exposure-ends`）、迁移或手工改了数据。
- **删除账号**：曝光行只有编号，保留（同答题与浏览，平台决策 D62 第 4 条）。
- **"发起方说曝光数不对"的排查**：
  1. 发布的数字：`SELECT count(*) FILTER (WHERE counted_at IS NOT NULL) AS successes, count(*) FILTER (WHERE opened_at IS NOT NULL) AS opens FROM qb_exposure WHERE publication_id = '<发布>'`；已购：`SELECT exposure_purchased, status, ended_at FROM qb_publication_card WHERE publication_id = '<发布>'`。
  2. 按天与地方：`SELECT date_trunc('day', counted_at) AS day, surface, timer, count(*) FROM qb_exposure WHERE publication_id = '<发布>' AND counted_at IS NOT NULL GROUP BY 1, 2, 3 ORDER BY 1`。
  3. 集中的账号：`SELECT user_id, capped_days, last_capped_on FROM qb_exposure WHERE publication_id = '<发布>' ORDER BY capped_days DESC LIMIT 20`。
  4. 不计的常见原因：用自己的视频、方向不对（竖视频只在竖屏模式计，横视频只在开始页计）、学者没验证邮箱、店员、到了每日上限、发布已结束、系统设为"减少动态效果"时视频不自动播放（学者点播放才开始计）。
- **"学者说竖屏模式里总是同一家"**：推送按发起方编号轮流（服务器按编号排轮次，一轮中谁先每天换一次），同一家的新的在前；打开过的不再推送。看 `qb.exposure.push.every` 与有几家在用共享视频。

### 7.32 共享视频的使用台账（F-S14）

用了**其他发起方共享视频**的发布（F-S12，卡片的提供方不是发布方），使用方（发布的发起方）在发布页"共享视频的台账"中记下与提供方的线下约定：期间、计价方式（固定价、每千次曝光成功、每位去答题的学者）与价格，提交给提供方；提供方在资源页"我的视频的使用台账"中确认，或写原因拒绝，使用方更正后重新提交（`docs/quizbuks/plans/impl/F-S14.md`）。**平台不代收代付**，台账不进平台的会计账本。给用户的帮助文案在 `docs/quizbuks/help/video-uses.md`。

- **部署**：迁移 `V99`建只追加表 `qb_video_use_version` 与普通表 `qb_video_use_figures`。部署后运行一次 `QB_SETUP`：建两个普通参数（下表），给内容管理员与运营商补上 `qb.admin.video-use.read`。三个任务都每天一次：`qb.video-use-figures`（UTC 19:53，逐项）、`qb.video-use-check`（UTC 20:07）、`qb.video-use-remind`（UTC 00:47，逐项）。没有新的环境变量。
- **版本**：每次保存、提交、确认、拒绝都是 `qb_video_use_version` 的新版本（不改旧行，平台的审计 `GET /api/audit/records` 有前后值）。**版本记录**在双方的页面上（"查看版本记录"）与管理页（每行的"版本记录"）：每个版本的操作（填写、更正、提交、确认、拒绝）、谁（发起方页面上为哪一方，管理页为平台用户）、时间、当时的计价与期间、拒绝原因；读的是只读视图 `qb_video_use_history`（版本行 + 平台的操作记录 `op_process`）。等待提供方确认时使用方不能改；已确认的更正后回到草稿，须重新确认（确认记下所确认的版本号）。
- **数字**：打开、曝光成功、去答题的人数、合格人数（去答题的人中合格的），每天由 `qb.video-use-figures` 从计数表 `qb_exposure` 与 `qb_attempt` 重算一次（截止为运行时刻），发布结束后的第一次运行**定稿**（截止为卡片的 `ended_at`），之后不再写。两边与运营商的页面上，少于统计的最小分组 `qb.stats.min-group`（Q9e，不小于 3）的数字不显示，所依据的数字不显示时参考金额也不显示。
- **核对**（`qb.video-use-check`）：每天把定稿的数字按同一截止从计数表重算比较，不一致即给内容管理员（`qb.admin.exposure.monitor`）一个待办（一天一个）。计数表的行只设置一次，正常情况下不会不一致；常见原因是手工改了 `qb_exposure` 或迁移补了旧时间的行。
- **提醒**：等待提供方确认超过 `qb.video-use.remind-days` 天，给双方各一个待办（每个提交的版本一次，有邮件时随待办通知），答复后关闭。
- **参数**（普通参数，`PARAM_SET`，不受控）：

  | 参数 | 缺省 | 作用 | 调整的影响 |
  |---|---|---|---|
  | `qb.video-use.remind-days` | `7` | 等待提供方确认超过多少天提醒双方（1–90） | 调低提醒更早；每个提交的版本只提醒一次 |
  | `qb.video-use.max-period-days` | `3660` | 一条台账的期间至多多少天（1–36600） | 只约束以后的保存；已保存的台账不受影响 |

- **管理页**：平台管理系统的"视频使用台账"页（`/qb/video-uses`，两边共用的组，`qb.admin.video-use.read`，**只读**）：每条台账的使用方、提供方、状态、计价、期间、数字与参考金额；总部看全部；运营商看自己发起方的台账，不论它是使用方（经 `qb.admin.video-uses`）还是提供方（经 `qb.admin.video-uses-provided`，只含已提交过的台账；读路径为 `urn:jabiz:dataset:operator-provider:*`），页面把两条路径合并，每条台账一行。双方的争议由运营商联系双方处理，平台不改台账。
- **删除账号**：台账保留（双方的记录），使用方的备注与提供方的拒绝原因由抹除流程覆盖（`SponsorErasure`）。
- **"数字不对"的排查**：
  1. 定稿与截止：`SELECT opens, successes, goes, qualified, cutoff_at, finalised_at FROM qb_video_use_figures WHERE publication_id = '<发布>'`。
  2. 按截止重算：`SELECT count(*) FILTER (WHERE counted_at <= '<截止>') AS successes, count(*) FILTER (WHERE went_to_answer_at <= '<截止>') AS goes FROM qb_exposure WHERE publication_id = '<发布>'`。
  3. 台账的全部版本：`SELECT version_no, status, pricing, price, period_from, period_to, reject_reason, created_time FROM qb_video_use_version WHERE publication_id = '<发布>' ORDER BY version_no`。

### 7.33 发起方包与测试期的导回（F-C4）

发起方包（`docs/quizbuks/plans/impl/F-C4.md`）把一个发起方的资料、Quiz（含没结束的发布用到的版本）、没结束的发布与知识结构图写进一个文件（格式 `quizbuks.sponsor-package` 第 2 版，第 1 版照旧能读），再导入另一个账户或环境。只带内容与设置，不带状态、记录、钱、店员、密码、码与邀请名单、资源库。导入以后一切照常审核（入驻、主页、图、发布），没有免审。给用户的帮助文案在 `docs/quizbuks/help/sponsor-package.md`。

- **部署**：迁移 `V102` 建普通表 `qb_package_import`。部署后运行一次 `QB_SETUP`：建两个普通参数（下表）。任务 `qb.package-import-expire`（每天 UTC 18:53，逐项）。没有新的环境变量、新角色权限。
- **参数**（普通参数，`PARAM_SET`）：

  | 参数 | 缺省 | 作用 |
  |---|---|---|
  | `qb.package.confirm-bytes` | `104857600`（100 MB） | 发起方包与 Quiz 文件的导出超过它即先问发起方再保存 |
  | `qb.package.import-retention-days` | `90` | 导入记录在最后一次更新后保留的天数；过期即由任务删除（未完成的也删除，已生成的草稿留下，成为普通草稿） |

  大题库按 `qb.content.import.batch-size` 分批导入、总数受 `qb.content.max-questions` 限制（§7.37）；一次导入开始时记下当时的每批题数，继续时沿用。
- **限流**（`application.yml`）：`QB_PACKAGE_IMPORT_START` 每人每小时 30 次、`QB_PACKAGE_IMPORT_STEP` 3000 次、`QB_PACKAGE_IMPORT_ABANDON` 30 次；检查页逐个检查包里的每个 Quiz 文件，`QB_QUIZ_IMPORT_CHECK` 因此放宽到每人每小时 300 次。
- **导入记录**：每次导入（包括"只导入资料部分"）记一行：发起方、包的 SHA-256、方式（`FULL` / `PROFILE`）、状态（`RUNNING` 未完成 / `DONE` 完成 / `ABANDONED` 已放弃）、已完成的步数与每一步生成的编号。每个发起方同时只有一个未完成的导入。每一步与记录在同一个事务中提交，所以继续不会重做、不会生成两份。总部与运营商在平台管理系统的发起方详情页（"发起方包的导入记录"，`qb.admin.publication.read`）只读查看，运营商只看到自己的发起方；删除账号时一并删除。
- **排查**：
  1. 发起方说"导入停在某一步"：看详情页的"最后完成的一步"与状态；页面上显示的拒绝原因就是那一步的流程的原文（例如入场费开关关着时 `QB_ENTRY_FEE_OFF`、图超过上限 `QB_TREE_LIMIT`）。排除原因后请发起方选同一个文件"继续"；也可以"放弃"。
  2. "另一个包的导入还没有完成"（`QB_PACKAGE_IMPORT_OPEN`）：同一个账户有未完成的导入，先在概况页继续完成或放弃它。
  3. "只能导入资料部分"（`QB_PACKAGE_SUBMITTED`）：账户提交过发布（有发布不是草稿或有提交时间）。整个包只能导入新账户或从未提交过发布的账户。
  4. 记录的细节：`SELECT status, mode, steps_done, steps_total, last_step, started_at, updated_at, done FROM qb_package_import WHERE sponsor_id = '<用户编号>' ORDER BY started_at DESC`（`done` 只有编号，没有内容与秘密）。
- **放弃**删除这次导入生成的草稿（图以退役代替删除，仍是草稿的发布删除，没有被已提交的发布用着的 Quiz 移除），资料部分不还原。审计由各现有流程照常记录（`GET /api/audit/records`）。
- **测试期重置数据库之后的导回**（需求方 2026-11-05：测试期反复重置数据库）：
  1. 重置**之前**：每个要保留的发起方登录发起方页面，在"概况"页"导出发起方包"，把文件存好（每个发起方一个文件；文件含图片，可能较大）。运营商的直营发起方（样板间）同样导出。
  2. 重置数据库并部署（§2），运行 `QB_SETUP` 并由另一位管理员发布受控参数的首个值（§1）。
  3. 重新建立账户：发起方重新注册（或由运营商为直营发起方建立），验证邮箱。
  4. 每个发起方在"概况"页"导入发起方包"，选第 1 步的文件：检查页列出全部问题与将要生成的内容，确认导入。邮件服务器需要重新输入密码（包里从不带密码）。
  5. 入驻还没通过时，基本信息填进入驻申请表：打开申请表，同意条款后提交，由运营商审核。
  6. 导入完成后点"提交全部草稿"：知识结构图进入运营商另一位成员的审核；绑定在图上的发布要等图生效后再点一次。不绑定的发布照常进入审核（资金不足的会列出原因，先充值）。
  7. 共享视频在新库中要由提供方重新共享并审核通过；列为"请重新选择"的发布在共享通过后到发布向导中重新选。

### 7.34 AI 问题解答（F-C10）

学者（App"我的 → 帮助"）与发起方（页头"？"）可以问 AI 使用方法；没解决的可以**转人工**：发起方的转给其运营商（待办在运营商的范围内），直营发起方与学者的转给总部（内容管理员）。依据只有公开的 FAQ、条款与使用手册（a 是后端，b 是资料快照与可编辑的手册，页面在 c：`docs/quizbuks/plans/impl/F-C10.md`）。**对用户免费**，用量记在 `qb_help_usage`（按用户 × 月，另有全平台一行 `*`），**不计入发起方的 AI 用量、AI 费与 AI 限额**。

- **部署**：迁移 `V104` 建三张普通表 `qb_help_thread`、`qb_help_message`、`qb_help_usage`。部署后运行一次 `QB_SETUP`：建十二个普通参数、提出两个受控参数（另一位管理员发布），给学者与发起方补上 `qb.help.use`，给内容管理员与运营商补上 `qb.admin.help`。四个任务：`qb.help-retention`（UTC 19:13，逐项）、`qb.help-auto-close`（UTC 19:23，逐项）、`qb.help-remind`（UTC 00:37，逐项）、`qb.help-usage`（UTC 23:53）。外部服务沿用 `anthropic`（`ANTHROPIC_API_KEY`）；并发 `quizbuks.help.max-concurrency`（缺省 4）。
- **开关**：`qb.help.enabled`（受控，缺省关）。**两份使用手册发布、快照生成之前不要打开**（没有资料时 AI 只会说"不确定"；见下面的"资料与使用手册"）。关掉以后：新的提问 422 `QB_HELP_DISABLED`（页面提示并可直接转人工），排队中的作业暂停（平台 D52 第 4 条，不用掉尝试），转人工与客服回复照常。
- **限额**：每人每天 `qb.help.user-daily` 次（在本人的锁内数当天的提问；一个对话同时只问一个）；全平台每月 `qb.help.global-monthly-tokens`（按已记的用量判断，**不取全平台锁**，并发下可能略超）；入口限流在 `application.yml` 的 `jabiz.process.rate-limits`（`QB_HELP_ASK` 每人、每地址）。没有回答的尝试按预估计入（预留的输入 + 最大输出），作业的完成、失败、取消都计入。
- **发给 AI 的**：问题、同一对话最近 `qb.help.history-turns` 轮、白名单中的页面名（`help.HelpPages`；答题页没有入口）与匿名键（`AiUserKeys`）。不发送题目、选项、答案与版本内容，也不发送本人或他人的数据。回答中只保留本站的链接；模型标了"被要求答题"即换成固定的拒绝（尽力而为）。
- **转人工**：待办 `qb.help.escalated`（来源键 `qb.help:<对话>`），客服在管理系统的帮助页回复（`QB_HELP_REPLY`，运营商只能回复转给自己的），用户收到站内信 `HELP_REPLY`。用户标"解决了"、关闭对话、或客服回复后 `qb.help.auto-close-days` 天没有再问（自动关闭）时待办关闭。等待客服超过 `qb.help.escalation-remind-days` 天，给处理人再建一个提醒待办 `qb.help.remind`（每等一次一个）。
- **参数**：

  | 参数 | 缺省 | 受控 | 作用 |
  |---|---|---|---|
  | `qb.help.enabled` | `false` | 是 | 总开关 |
  | `qb.help.model` | `claude-haiku-5-5` | 否 | 只能是平台允许的三个模型 |
  | `qb.help.user-daily` | `20` | 否 | 每人每天的提问次数 |
  | `qb.help.global-monthly-tokens` | `20000000` | 是 | 全平台每月的令牌数 |
  | `qb.help.history-turns` | `6` | 否 | 一起发送的最近轮数（至多 20） |
  | `qb.help.retention-days` | `180` | 否 | 对话在最后一次活动后保留的天数。**改它必须同时改**隐私政策第8条（三语，`legal/privacy-policy.*.md`，重新发布）与商店的数据申报（`store/play-data-safety.md` 的"其他用户生成的内容"、`store/appstore-privacy.md` 的"客户支持"，并在 Play Console 与 App Store Connect 中更新），三处都写着 180 日 |
  | `qb.help.manual-max-bytes` | `204800` | 否 | 一个入口 × 语言的资料快照（手册 + FAQ + 条款）的上限，字节；发布超过的手册被拒，生成的快照超过即不保存并且启动检查报错（`QB_HELP_MATERIALS`）。无论参数多大，快照至多 800 KiB |
  | `qb.help.poll-seconds` / `qb.help.poll-max-minutes` | `3` / `3` | 否 | 页面查询回答的间隔与最长等待 |
  | `qb.help.escalation-remind-days` | `2` | 否 | 转人工后多少天没人处理即提醒 |
  | `qb.help.auto-close-days` | `14` | 否 | 客服回复后多少天没再问即自动关闭 |
  | `qb.help.question-max-length` | `2000` | 否 | 提问（及转给客服的内容）的最长字数（100–4000） |
  | `qb.help.answer-max-length` | `8000` | 否 | 保存的 AI 回答的最长字数（500–8000，超过截断） |
  | `qb.help.max-links` | `5` | 否 | 一个回答最多保留的本站链接（0–10） |
  | `qb.help.cache-read-weight` | `0.10` | 是 | 从提示缓存读取的输入令牌计入 `qb.help.global-monthly-tokens` 的权重（0–1，向上取整；提供方按约十分之一的价格计费）。其他输入与输出令牌照常全额计入；`qb.admin.help-usage` 的已用百分比与告警仪表同样按它计（F-C10b） |

  部署配置（改后重启；超出范围即启动失败）：`quizbuks.help.max-concurrency`（缺省 4）、`quizbuks.help.attempts`（每个提问的尝试次数，缺省 2，1–5）、`quizbuks.help.attempt-timeout`（每次尝试的时限，缺省 `PT3M`，30 秒–10 分钟）。

- **统计**：每月"没解决"与转人工的提问 `qb.admin.help-gaps`（内容管理员据此补 FAQ 与手册；专用权限 `qb.admin.help.gaps`，只有总部内容管理员有，运营商没有；月份按 `jabiz.fiscal-year-zone`）；帮助用量 `qb.admin.help-usage`（总部 AI 页，与发起方的 AI 用量分开）。
- **资料与使用手册（F-C10b）**：AI 只依据**快照**回答：每个入口（学者 `app` / 发起方 `sponsor`）× 语言（中日英）一份，内容是已发布的使用手册、已发布的 FAQ（该入口与"两者"）和当前生效的法律文本（学者：用户条款、隐私政策、有奖活动规则、AI 使用说明；发起方：发起方条款、隐私政策、特定商取引法标示、AI 使用说明）。快照存成文件（策略 `qb.help.materials`），每次提问作为作业的文件交给 AI，并经提供方的提示缓存（5 分钟）复用。
  - **部署**：迁移 `V105` 建 `qb_help_manual_version`（手册，只追加）与 `qb_help_material`（快照），`qb_help_usage` 加缓存的两列。部署后运行一次 `QB_SETUP`：建两份手册的草稿（仓库中的初稿，**不发布**）。新任务 `qb.help-materials`（每小时第 41 分，UTC）；新外部作业 `qb.help.materials`（只把文本存成文件，不调用外部服务）。
  - **编辑与发布手册**：平台管理系统 → "AI 问题解答使用手册"（`QbHelpManual`），编辑草稿（三语 Markdown，写本站路径如 `/taker/help`、`/sponsor/publications`，让回答能附链接；不要写外部网址，回答中的外部链接会被去掉）；行操作"发布"（`QB_HELP_PUBLISH`，内容管理员）。三语缺一、超过上限即 422，什么都不改。发布后几秒内生成新快照（事件），下一次提问就用新内容。快照的状态在"AI 问题解答资料"（`QbHelpMaterial`：在用的大小、生成时间，最近一次生成的大小，保存中）。
  - **改了 FAQ 或法律文本**：每小时的任务会把它们放进快照；要立刻生效，不选手册运行 `QB_HELP_PUBLISH`（只重新生成快照）。以后生效的法律文本在生效后的一小时内进入快照。
  - **功能变化后的手册**：开发者在功能的 PR 中改仓库的初稿（`backend/quizbuks/src/main/resources/quizbuks/help/manual-*.md`）。部署后启动检查 `QB_HELP_MANUAL_SOURCE` 警告"仓库的使用手册不是草稿最近载入的"：内容管理员在手册的行上运行 `QB_HELP_MANUAL_LOAD`。草稿在管理系统中没改过就直接换成新文本；改过则拒绝（`QB_HELP_MANUAL_EDITED`），这时对照 PR 的改动手工合进草稿，再以"保留草稿"（`keepDraft`）记为已合并，或以"覆盖"（`replace`）放弃管理系统中的修改。然后检查并发布。
  - **大小超过上限**（启动检查 `QB_HELP_MATERIALS` 报错，或发布被拒 `QB_HELP_MATERIALS_TOO_LARGE`）：错误中写着入口、语言与字节数。缩短手册或删减 FAQ（多半是 FAQ 增多），或调高 `qb.help.manual-max-bytes`（每次提问的费用随之增加；预留的令牌按快照大小每 3 字节一个计）。超过上限的新快照不会保存，在用的旧快照照旧，所以服务不中断；改好后发布或等每小时的任务，检查在下次启动时通过。
  - **快照一直"保存中"**：看平台的作业 `qb.help.materials`（`sys_external_job`）；失败或 30 分钟没完成时下一次生成会重试。
  - **缓存的用量**：`qb.admin.help-usage` 显示缓存写入与读取的令牌（已包含在输入令牌中）；读取多说明缓存在起作用。全平台月上限中，缓存读取按 `qb.help.cache-read-weight`（缺省 0.1）计。美元估算按标价计（偏高）并加上缓存写入多出的部分。
- **事故处理**：回答明显有误或被诱导（例如帮人答题）：先关 `qb.help.enabled`（受控，另一位管理员发布），再查 `qb_help_message`（`kind = 'ANSWER'`）与平台的作业记录（`sys_external_job` 的 `qb.ai.help`），补资料或手册后再开。提供方故障：作业按平台规则重试后失败，用户看到失败说明并可转人工。用量突增：看 `qb.admin.help-usage` 与 `qb-help-usage-high`，必要时调低 `qb.help.user-daily`。
- **改派运营商**：发起方改派时，其转人工、由原运营商回答的对话与待办（含等待的提醒）随之移到新运营商（平台的 `APPROVAL_SCOPE_MOVE`，与待审的审批同一上限，超过即 422 `QB_SPONSOR_HELP_PENDING`：先关闭一些对话）；总部回答的不动。
- **删除账号**：三张表中本人的行由抹除流程删除（`help.HelpConfig` 的 `PersonalDataEraser`），作业的输入与结果由平台按请求人清除；全平台的用量行保留（不含个人）。

### 7.35 通行密钥（Q97）

指纹、面容登录（平台 D71）在 App、发起方页面、平台管理系统与运营商管理系统开启（`QB_PASSKEYS`，缺省开；`docs/quizbuks/plans/Q97-passkeys.md`）。

- **依赖方**是部署自己的主机（取 `JABIZ_MAIL_BASE_URL`，或 `QB_PASSKEY_RP_ID` / `QB_PASSKEY_ORIGINS`）。**换域名或改依赖方会使全部已绑定的通行密钥失效**：用户只能改用密码或邮件登录后重新绑定。换之前先发公告。
- 用户在"账户 → 登录与安全"中添加、改名、删除；没有密码的用户（邮件链接、Google / Facebook / LINE 登录）先经确认邮件（`auth/passkey-confirm`），所以需要邮件已开启。
- 用户丢了设备：本人在另一台设备上删除；管理员可代为删除（平台的 `SEC_PASSKEY_REMOVE_ADMIN`，要求二次验证），不能代为绑定。
- 通行密钥登录算两个因素：要求二次验证的管理员与运营商可以只用它登录。封禁与运营商成员关系的检查照旧。
- 商店 App（iOS / Android 外壳）暂未开启：外壳中的网页暂时不能用通行密钥（需要 `apple-app-site-association` 的 `webcredentials` 与 `assetlinks.json` 的登录凭据授权，另立阶段）。

### 7.36 资料包中的链接图片（F-C6）

发起方导入的资料包里，图片可以写成 `{"url": "https://…"}`。导入时由发起方页面请服务器**逐张下载一次**（平台能力 D75：`POST /api/files/fetch`，不在导入的事务中；
下载得到的是发起方自己的文件，与上传同一条流水线：按内容判断类型、重新编码、去掉 EXIF 与位置信息）。链接不保存，以后不再访问原网站。

- **没有新的参数、迁移或定时任务**。下载的限制是平台的 `jabiz.files.fetch.*`（缺省：只 https/443、至多 3 跳、连接 5 秒、读 20 秒、整个下载 60 秒、每人同时 2 个、每实例同时 16 个）
  与 `jabiz.process.rate-limits.FILE_FETCH`（缺省每人每分钟 30、每主机 60、每实例 300；整体替换缺省），见 `docs/design/14-files.md` §12。QuizBuks 没有改它们。
- **导入不改变审核**：下载失败不会让导入失败。确认页逐张显示结果；没取到的图片可以重试、换成发起方自己上传的图片，或勾选"保持现状"后导入（现有的图片留着）。
- **发起方常见的失败原因**（确认页显示服务器的原文，三语）：

| 错误码 | 意思 | 发起方可以做的 |
|---|---|---|
| `FILE_FETCH_NOT_HTTPS` / `FILE_FETCH_LINK_INVALID` | 不是 https，或链接写法不对（含用户名密码、奇怪的主机或端口） | 改链接，或自己上传 |
| `FILE_FETCH_ADDRESS_NOT_PUBLIC` | 链接指向内网、本机或云的元数据地址 | 自己上传（这是有意的限制，不会放开） |
| `FILE_FETCH_TOO_MANY_REDIRECTS` | 跳转超过缺省 3 次 | 用最终的图片地址 |
| `FILE_FETCH_NOT_IMAGE` | 不是可接受的图片（只收 JPEG、PNG） | 换成图片的直接地址 |
| `FILE_FETCH_TOO_LARGE` | 超过该文件策略的大小上限（封面与商品图 5 MB，头像 2 MB） | 缩小后上传 |
| `FILE_FETCH_TIMEOUT` / `FILE_FETCH_FAILED`（`reason`：找不到主机、连不上、证书、状态码、空） | 对方网站慢、关闭或拒绝 | 稍后重试，或自己上传 |
| `RATE_LIMITED` / `PROCESS_BUSY` | 下载太频繁，或本实例的下载名额满了 | 稍后重试 |

- **观测**：平台的 `jabiz.file.fetch`（标签只有结果，失败的原因按错误码计数），告警照平台（13 §7）。看到某个发起方大量失败时，通常是他的网站拒绝机器访问（robots 之外的防护），请他改为自己上传。
- **测试**：后端 `ProfileLinkImagesIT`（本机 HTTPS 桩，测试配置以 `@Primary` 的 `RemoteFetchNetwork` 替换网络；下载 → 导入、没有文件时保留现有图片、别人的文件被拒），页面 `transfer.test.tsx`，
  e2e `profile-links.spec.ts` 只测失败路径（e2e 没有公网图片源，D75 没有"只在测试中放行"的属性，有意如此）。

### 7.37 题库的上限与每日核对（F-S6）

- **参数**（普通参数，`QB_SETUP` 建立，平台管理系统的参数页可以改，改动即时生效）：
  - `qb.content.max-questions`（缺省 1000）：一个 Quiz（题库）至多几题。至多 2000（代码的技术上限 `ContentLimits.MAX_QUESTIONS_TECH`），填得更大也按 2000。调低后已有的大题库照常读取、修改、删除，只是不能再加题（`QB_TOO_MANY_QUESTIONS`，提示中带上限）。
  - `qb.play.max-questions-per-attempt`（缺省 100）：一次答题至多几题，题库更大时发布必须抽题。至多 200（`PlayLimits.MAX_PER_ATTEMPT_TECH`，答题记录的列宽按它）。在 F-S7 合入前，发布只按 200 检查（`QB_PUBLICATION_PICK_REQUIRED` / `QB_PUBLICATION_PICK_TOO_MANY`）。
  - `qb.content.import.batch-size`（缺省 200）：往已有的 Quiz 中导入时每批的题数（分批导入随 F-S6 的编辑器一起提供）。
  - 部署后没有重跑 `QB_SETUP` 时，三个参数都按缺省值。
- **每日核对**：任务 `qb.content-check`，每天 19:13 UTC，模板 `qb.admin.content-health`。
  - `SNAPSHOT_NEAR_LIMIT`：版本的快照超过上限（6 MB）的 80 %。
  - 有问题即给持 `qb.admin.quiz.read` 的人一个待办，每天一个。
  - 处理：
    - 模板 `qb.admin.large-versions`（参数 `overBytes`，每日核对用的是 5033164，即 6 MB 的 80 %）列出是哪些 Quiz 的哪个版本、多大；
    - 请发起方把题库拆成两个 Quiz，或删去不用的题和翻译，之后才能保存新版本；
    - 已有的版本照常可用。
- **耗时**：千题、6 个选项、三语的题库实测见 `docs/quizbuks/perf/F-S6-large-bank.md`。

### 7.38 可支付平台费的账户（F-M1）

每个发起方有一个"可支付平台费的账户"（`docs/quizbuks/plans/impl/F-M.md` §M1）：**赠送额度**（平台费 Kudos，科目 2190，钱包种类 `FEE_CREDIT`，按笔 `qb_fee_credit_grant`，各有到期）与**现金**（2160，钱包种类 `CASH`，按进账一笔一行 `qb_cash_lot`，来源 `CARD` / `BANK` / `OPERATOR` / `OTHER`）。平台费一律经子流程 `QB_FEE_SETTLE` 支付：先用赠送额度（最早到期的先用，同时到期的先发放的），再按进账先后用现金（不超过未被争议冻结的部分）；账户有多少付多少，其余留在账单上（状态 `PARTIAL`，`paid_amount`）。每次付款一笔分录（借 2190 / 2160、贷 1130，来源为费用或账单），每用到一笔记一行 `qb_fee_payment_version`（只追加）；作废账单（`QB_FEE_REQUEST` 的作废）冲正这些分录，并以 `QB_FEE_REFUND` 把每一部分退回原来的那一笔（F-M1 之前已付的账单没有分摊，退回为一笔新的现金 `OTHER`）。学分钱包（2110）从不用来付平台费。

- **部署**：迁移（开 PR 时定号）建三张表、允许钱包种类 `FEE_CREDIT` 与账单状态 `PARTIAL`、给账单加 `paid_amount`（已付的账单记为全额），并把每个发起方**此刻的 2160 余额写成一笔** `OTHER`（`source_entity = 'MIGRATION'`，进账时刻 = 迁移时；它排在以后的进账之前，总控答复 c3）。之后：
  1. 运行一次 `QB_SETUP`：建科目 2190 与受控参数 `qb.journal.account.2190`（首个值为空，作为提案）。
  2. **另一位管理员发布** `qb.journal.account.2190` 的提案（受控变更，四眼）：值是会计系统中"平台费 Kudos（赠送额度）"的科目代码。没有发布之前分录导出会被拒（`JournalMappingCheck` 与导出的"所动科目未映射"），一旦 F-M2 开始赠送，2190 就会出现在分录里。
  3. 没有新的环境变量与任务；F-M1 本身不赠送（赠送是 F-M2），所以上线时所有发起方的赠送额度为 0，行为与之前相同，只是付款记分摊、账单可以部分付。
- **页面**：发起方页面的钱包页"平台费账户"一块（合计、赠送额度、现金；剩余的各笔按使用顺序与到期；每笔付款的分摊）；平台管理系统的发起方页（财务，`qb.admin.finance.read`）与运营商管理系统的发起方页（只限自己的发起方）只读同样内容；财务总览"平台费账户（按子账户）"给出全平台的余额、各笔剩余与本月付款。
- **每日核对**（`qb.reconcile`，模板 `qb.system.recon-fee-account`）：`FEE_CREDIT`（某发起方的 2190 = 其 `FEE_CREDIT` 钱包 = 各笔赠送剩余之和）、`CASH_LOTS`（2160 = 各笔现金剩余之和）、`FEE_PAYMENTS`（费用与账单的付款分录从 2160 / 2190 取走的 = 分摊之和；F-M1 之前的付款没有分摊，不核）。差异照旧给 `qb.reconcile` 待办。常见原因：有人绕过流程直接改了表；一笔现金被取走时各笔加起来不够（买学分、扣回从不因此被拒，差异由 `CASH_LOTS` 报出）。
- **月报**（`qb.admin.monthly`）：付款仍是 `FEE_PAID`，新列 `feeCredit`（2190）与 `sponsorCash`（2160）分别是两种来源付的（借方为正）；作废的冲正在 `FEE_VOIDED`。余额表（`qb.admin.balances`）多 2190 一行，检查 `SPONSOR_WALLETS` 与 `NO_OVERDRAWN_WALLET` 含 2190。
- **"发起方说平台费付得不对"的排查**：
  1. 账户：`SELECT kind, balance, held FROM qb_wallet WHERE owner_id = '<发起方>' AND kind IN ('FEE_CREDIT', 'CASH')`；各笔：`SELECT grant_id, source, remaining, expires_at FROM qb_fee_credit_grant WHERE sponsor_id = '<发起方>' AND remaining > 0 ORDER BY expires_at, granted_at`，`SELECT lot_id, source, remaining, arrived_at FROM qb_cash_lot WHERE sponsor_id = '<发起方>' AND remaining > 0 ORDER BY arrived_at`。
  2. 一张账单的付款：`SELECT paid_at, sub_account, coalesce(grant_id, cash_lot_id) AS lot, amount, transaction_id FROM qb_fee_payment_version WHERE target_id IN ('<账单>', '<账单的 charge_id>') ORDER BY process_seq_id, row_id`。
  3. 部分付款的账单：`SELECT status, total, paid_amount, due_on FROM qb_fee_invoice WHERE invoice_id = '<账单>'`；剩余 = `total - paid_amount`，到期后照旧变为 `OVERDUE` 并提醒。

## 8. 升级到线 1.3（S0 的迁移 V29、V30）

S0（`shopify/S0-publisher.md`）给发布、档位、卡片、会话、分析、现金 / 兑换 / 电子奖品与码池、邀请加上 `publisher_kind`（已有的行全部为 `SPONSOR`），并把普通表的 `sponsor_id` 列改名为 `publisher_id`。
五张只追加表（发布、档位、现金、兑换、电子奖品的 `*_version`）由 V29 改名、V30 改回 `sponsor_id`：它们的行已被完整性封存，封存过的列不能改名（设计 21 §2.1，S0 §10）。
迁移只有 DDL、不改写行，但旧 jar 不能在新表上运行、新 jar 不能在旧表上运行（一步到位，同 V10 的锁拆分），所以**停机升级**：

1. 停止应用（`docker compose stop app` 或服务器的 `deploy-app.sh` 之前先停旧容器）。
2. 确认 Outbox 已投递完：`SELECT count(*) FROM sys_outbox WHERE delivered_time IS NULL`（表名以平台 `db/jabiz` 为准）为 0。
   事件 `qb.publication.status` 的载荷从 `sponsorId` 改为 `publisherKind` + `publisherId`：升级前未投递的这类事件，新版本的消费者读不到发布方，**什么也不做**（最坏丢一封发布状态的通知邮件，不影响数据）；钱包事件的载荷不变。
3. 启动新 jar：Flyway 依次执行 V29 与 V30（**一起执行，不要停在 V29**：只停在 V29 时一旦封存运行，V30 之后这些块会报告被改）。V29 做 `ALTER TABLE … RENAME COLUMN`、`ADD COLUMN … DEFAULT 'SPONSOR'` 再 `DROP DEFAULT`、`CHECK`、索引改名、重建视图 `qb_publication_owner`；V30 把五张只追加表的列名改回。`qb_attempt` 很大时 `CHECK` 的校验扫一遍表，秒级。
4. 升级后运行一次 `INTEGRITY_VERIFY`（或等每日的校验）：应当完好，升级前封存的行不报告 `MODIFIED`。
5. 启动检查无新告警；待审核的发布照常（审批绑定的内容不含发布方）；平台管理系统「发布」列表多出"发布方类型"一列。
6. 不需要再执行 `QB_SETUP`（没有新参数、新权限、新科目）。

回滚：恢复升级前的备份（§2.3）并启动旧 jar；没有反向迁移。
