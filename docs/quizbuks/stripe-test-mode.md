# QuizBuks — Stripe 测试模式全流程（Q10c 手册）

用真实的 Stripe **测试**密钥（不是桩）走一遍资金的全流程，每一步之后经 API 核对应用的记录与账本，最后打印汇总：

充值（Checkout，测试卡 4242）→ Webhook 到达 → 答题得奖励 → Connect Express 开户（测试账户）→ 自动转账（转账任务）→ 退款一次 → 争议一次（测试争议卡）→ 打款到银行（`payout.paid`，测试模式允许时）。

- 计划：`plans/Q10-launch.md` §5 与决定 L-3（**脚本与手册由你在本机或测试服务器上运行**；CI 作业只在提供 secrets 后启用）。Stripe 的设计：`plans/Q6-payments.md`。
- 脚本：`tools/quizbuks/stripe-test-mode/run.sh`（入口与本机启动）、`stm.py`（API 与核对，只用 Python 标准库）、`browser.mjs`（无人值守时代替人操作 Stripe 页面，尽力而为）。
- 测试：`tools/quizbuks/test/stripe-test-mode.test.sh`（安全检查、参数、不打印密钥；以假的 Stripe 与应用覆盖真实模式的分支，`stm_test.py`；离线，CI 的 `deploy` 作业每次运行）。
- 本手册的命令都在仓库根目录执行。

## 1. 安全

脚本在发出任何请求之前检查（`run.sh guard` 只做这一步），任何一条不成立即拒绝（退出码 2，一次列出全部问题）：

| 检查 | 规则 |
|---|---|
| 密钥 | `STRIPE_TEST_SECRET_KEY` 必须是 `sk_test_…`；`sk_live_` / `rk_live_` 一律拒绝，受限密钥 `rk_test_` 也拒绝（脚本要退款、提交争议证据、打款，需要完整的测试密钥） |
| 目标 | 缺省 `http://localhost:8080`；只有字面的回环地址（`localhost`、`127.x`、`::1`，不查 DNS）算本机。其他主机须 `--allow-remote`（或 `STRIPE_TM_ALLOW_REMOTE=yes`）且必须是 `https`；地址不得带用户信息、查询或片段 |
| 生产主机 | `tools/quizbuks/stripe-test-mode/production-hosts`（每行一个，`*.域名` 含域名本身与子域；`STRIPE_TM_PRODUCTION_HOSTS_FILE` 可换一个文件，读不到即拒绝）与 `STRIPE_TM_PRODUCTION_HOSTS` 中的主机**永远拒绝**，`--allow-remote` 也不放行。**只比较名字**：生产主机的 IP 地址或别名（CNAME）认不出，用目标的正式名字，不要绕过。**正式域名一确定就写进该文件**（上线检查清单） |
| 正式模式开关 | 本环境中 `QB_STRIPE_LIVEMODE` / `QUIZBUKS_STRIPE_LIVEMODE` 不是 `false` 即拒绝 |

连上之后、**写入任何东西之前**（汇总中的 `preflight`，只读：健康检查、Stripe 的余额与账户、管理员登录与以下两项），确认应用是测试模式（应用没有公开"当前模式"的接口，所以从它的行为判断），不成立即拒绝（退出码 2），什么也不写：

- **测试模式的正面信号**：`GET /api/jobs` 中转账任务 `qb.transfer-run` 存在、且不是每日的计划（`0 7 1 * * *`）。应用在正式模式或正式密钥下以其他计划启动即失败（`TransferScheduleCheck`），所以运行中的应用有其他计划就是测试模式。serve 的实例与测试服务器都设 `QB_TRANSFER_RUN_CRON='0 * * * * *'`（步骤 6 本来也需要）。只有关掉启动检查（`jabiz.platform-check.on-startup=false`，任何部署都不应这样做）时这个信号才不成立；
- 应用中**有任何正式模式的充值**（`qb.admin.topups` 的 `livemode`）即拒绝；

之后在步骤中：

- 充值发起后、付款之前：支付页的会话必须是 `cs_test_…`、地址以 `https://checkout.stripe.com/` 开头，并且**用脚本的密钥能读到这个会话、`livemode` 为 false**——即应用与脚本用的是同一个 Stripe 账户的测试模式。读不到就拒绝（应用用的是另一个账户，事件也不会转发给它）；
- Stripe 回答余额时 `livemode` 必须为 false。

**不打印密钥**：每一行输出都经过遮蔽（已知的密钥、密码、令牌、TOTP 种子，以及任何形如 `sk_…`、`rk_…`、`whsec_…`、JWT 的文字）。开通链接（能登录到该账户的开通页）不打印，写进私有文件（见步骤 5）。
密钥与链接也不放在命令行上（本机的其他用户看得到）：开通与支付页的地址以环境变量 `STRIPE_TM_BROWSER_URL` 交给 `browser.mjs`，桩的签名密钥以 `STRIPE_STUB_WEBHOOK_SECRET` / `STRIPE_STUB_CONNECT_WEBHOOK_SECRET` 交给桩。
脚本的请求一律不跟随重定向（否则令牌与 Stripe 密钥会随 `Authorization` 头被带到重定向的目标）。
`run.sh serve` 的密钥、日志（`stripe listen` 的日志含签名密钥）与结果都在 `deploy/quizbuks/.stripe-test-mode/`（已在 `.gitignore` 中）：`serve`、`run`、`payout-check` 与脚本自己每次都以权限 700 建立或收紧它。

## 2. 前提

**Stripe 账户**（只用测试模式；以后的正式账户另建或另用，见 `operations.md`）：

1. **账户国家**：建议日本（JP）。QuizBuks 只收日元、收款账户只在 `QB_STRIPE_CONNECT_COUNTRY`（缺省 `JP`）开通、转账以日元从平台余额转出；其他国家的平台账户可能不能给 JP 的 Express 账户转账（跨境限制，`03-plan.md` 第 4 节）。
2. **测试密钥**：后台右上角切到"测试模式"→ 开发者 → API 密钥 → 密钥（`sk_test_…`）。脚本与应用用**同一个**账户的测试密钥（应用可用受限密钥 `rk_test_…`，见 `deploy/quizbuks/README.md`；脚本必须用完整密钥）。
3. **Connect**：后台 → Connect → 开始使用，选择平台（platform）模式并填写平台资料；开启 Express 账户。测试模式下不需要审核。
4. **API 版本**：应用固定 `2025-03-31.basil`（`QB_STRIPE_API_VERSION`）。事件正文的结构由端点（或 `stripe listen` 所用账户的缺省版本）决定，不一致时字段对不上、事件只开待办。在开发者 → 概览中确认账户的缺省 API 版本；在后台建端点时选同一版本。
5. **打款（步骤 9）**：测试模式 → 设置 → 打款：加一个测试银行账户（日本的测试账号见 Stripe 文档"Testing → Payouts"），并把打款计划改为**手动**。自动计划下 Stripe 拒绝手动打款，脚本把这一步记为 SKIP。
6. **Stripe CLI**（本机运行时）：<https://docs.stripe.com/stripe-cli>。不需要 `stripe login`：`run.sh serve` 以 `STRIPE_API_KEY` 环境变量把测试密钥交给它。

**本机**：JDK 21、python3、curl、openssl、psql；PostgreSQL（缺省 compose 的 `localhost:5440`：`docker compose -f deploy/quizbuks/compose.dev.yaml up -d db`）；Mailpit（在 1025 / 8025 没有时用 Docker 启动）。

## 3. 环境变量

| 变量 | 用于 | 说明 |
|---|---|---|
| `STRIPE_TEST_SECRET_KEY` | serve、run | 测试密钥 `sk_test_…`。serve 把它作为应用的 `STRIPE_API_KEY` 与 Stripe CLI 的密钥 |
| `STRIPE_TM_BASE_URL` | run | 应用的地址，缺省 `http://localhost:8080` |
| `STRIPE_TM_ALLOW_REMOTE` | run | `yes` 等同 `--allow-remote` |
| `STRIPE_TM_PRODUCTION_HOSTS` | run | 再加几个永远拒绝的主机（逗号分隔） |
| `STRIPE_TM_PRODUCTION_HOSTS_FILE` | run | 代替脚本旁的 `production-hosts`；文件不存在即拒绝 |
| `STRIPE_TM_ADMIN_USER` / `STRIPE_TM_ADMIN_PASSWORD` | run | 管理员（缺省 `admin`）；serve 启动的实例自动提供 |
| `STRIPE_TM_ADMIN_TOTP` | run | 管理员有二次验证时其 TOTP 种子（Base32，绑定时的 `secret`）；脚本按 RFC 6238 计算验证码 |
| `STRIPE_TM_PUBLISHER_USER` / `_PASSWORD` / `_TOTP` | run | 第二位管理员（持 `control.publish`）：发布 `QB_SETUP` 的提案与"开启转账"。本机 serve 的实例由脚本自建（`QB_STM_PUBLISHER`，只有 `control.publish`）：**只在**目标正是 serve 写在状态目录 `run.env` 中的地址、且 serve 的 `app.pid` 进程还在运行时；其他回环地址上的安装不自建，需要这两个变量 |
| `STRIPE_TM_MAILPIT_URL` | run | 有它时每次运行注册新的商家与答题人并经 Mailpit 验证邮箱（serve 自动提供） |
| `STRIPE_TM_SPONSOR_EMAIL` / `_PASSWORD`、`STRIPE_TM_TAKER_EMAIL` / `_PASSWORD` | run | 没有 Mailpit 时（测试服务器发真实邮件）用事先准备的、已验证邮箱的账号 |
| `STRIPE_TM_FUND_BALANCE` | run | `yes` 等同 `--fund-balance`（见步骤 6） |
| `STRIPE_TM_BROWSER` | run | 等同 `--browser`：无人值守（CI） |
| `STRIPE_TM_COUNTRY` | run | 答题人的国家，缺省 `JP`，须与应用的 `QB_STRIPE_CONNECT_COUNTRY` 相同 |
| `STRIPE_TM_DB_HOST` / `_PORT` / `_NAME` / `_USER` / `_PASSWORD` | serve | 缺省 `localhost:5440`、数据库 `quizbuks_stripe_test`（不存在就建）、`quizbuks` / `quizbuks`。**用自己的库**：完整性与二次验证的密钥属于库，不要与本机演示共用 |
| `STRIPE_TM_PORT`、`STRIPE_TM_STUB_PORT`、`STRIPE_TM_SMTP_PORT`、`STRIPE_TM_MAILPIT_PORT` | serve | 缺省 8080、8093、1025、8025 |
| `QB_STRIPE_API_VERSION` | run | 脚本调用 Stripe 时固定的版本，缺省同应用 |

金额（JPY = Kudos）的选项：`--topup`（10 000）、`--reward`（2 000，须不低于转账门槛 `qb.transfer.threshold`）、`--refund`（1 000）、`--dispute`（2 000）、`--payout`（1 000）；等待：`--wait-person`（等人在 Stripe 页面上操作，1 200 秒）、`--wait-event`（600）、`--wait-transfer`（150）、`--wait-payout`（900）。

## 4. 运行

### 4.1 本机（推荐）：`serve` + `stripe listen`

```sh
export STRIPE_TEST_SECRET_KEY=sk_test_…          # 不要写进任何文件；用 read -s 输入更好
docker compose -f deploy/quizbuks/compose.dev.yaml up -d db
tools/quizbuks/stripe-test-mode/run.sh serve      # 构建（首次几分钟）、启动应用与 stripe listen、Mailpit
tools/quizbuks/stripe-test-mode/run.sh run --fund-balance
tools/quizbuks/stripe-test-mode/run.sh stop
```

`serve` 做的事：检查（同上）；生成本机的平台密钥与管理员密码（只一次，存在状态目录）；建库；`stripe listen --print-secret` 取签名密钥，作为应用的 `STRIPE_WEBHOOK_SECRET` 与 `STRIPE_CONNECT_WEBHOOK_SECRET`（CLI 转发的两类事件用同一个密钥）；
后台运行 `stripe listen --events <QuizBuks 订阅的类型> --forward-to …/api/inbound/stripe --forward-connect-to …/api/inbound/stripe-connect`；以下列设置启动打包的 jar：
测试模式、回跳地址为 `http://localhost:8080/…`（测试模式允许 http）、邮件到 Mailpit、**转账任务与每日核对每分钟运行**（`QB_TRANSFER_RUN_CRON`、`QB_RECONCILE_RUN_CRON`，只在测试模式允许，`TransferScheduleCheck`）、管理操作不要求二次验证（只在本机）。

### 4.2 测试服务器（公网地址）

服务器以测试密钥运行（`QB_STRIPE_LIVEMODE=false`），在 Stripe 后台（测试模式）建两个端点（`deploy/quizbuks/README.md`"充值与核对""收款账户与自动转账"）：

- `https://<测试主机>/api/inbound/stripe`：`checkout.session.completed`、`checkout.session.expired`、`charge.refunded`、`charge.dispute.created`、`charge.dispute.closed`、`transfer.reversed`、`payout.paid`、`payout.failed`；
- `https://<测试主机>/api/inbound/stripe-connect`（"已连接的账户"）：`account.updated`、`account.application.deauthorized`。

两个签名密钥分别放进服务器的 `STRIPE_WEBHOOK_SECRET`、`STRIPE_CONNECT_WEBHOOK_SECRET`。也可以不建端点，在服务器上运行 `stripe listen --forward-to http://localhost:8080/api/inbound/stripe --forward-connect-to http://localhost:8080/api/inbound/stripe-connect`（同 4.1）。
**转账任务**缺省每天 01:07 UTC 运行：测试服务器上设 `QB_TRANSFER_RUN_CRON='0 * * * * *'`（只在测试模式允许），否则步骤 6 会说"下一次运行在…"并失败。

```sh
export STRIPE_TEST_SECRET_KEY=sk_test_… STRIPE_TM_ADMIN_PASSWORD=… STRIPE_TM_ADMIN_TOTP=… \
  STRIPE_TM_PUBLISHER_USER=… STRIPE_TM_PUBLISHER_PASSWORD=… STRIPE_TM_PUBLISHER_TOTP=… \
  STRIPE_TM_SPONSOR_EMAIL=… STRIPE_TM_SPONSOR_PASSWORD=… STRIPE_TM_TAKER_EMAIL=… STRIPE_TM_TAKER_PASSWORD=…
tools/quizbuks/stripe-test-mode/run.sh run --base-url https://test.quizbuks.example --allow-remote --fund-balance
```

事先准备的答题人若已开通收款账户，步骤 5 记为 SKIP；若它有余额，转账任务可能在脚本之前就把它转走（脚本只看本次运行之后建立的转账）。尽量每次用新的账号。

### 4.3 干跑（本地 Stripe 桩，不连 Stripe）

```sh
tools/quizbuks/stripe-test-mode/run.sh serve --stub
tools/quizbuks/stripe-test-mode/run.sh run --stub
tools/quizbuks/stripe-test-mode/run.sh stop
```

同一套步骤与核对，支付页与开通页由桩代替（脚本直接按桩的按钮）；退款是桩的整额退款；**争议与打款跳过**（桩没有）。用来先确认安装、设置与脚本本身，再花时间做真实的一轮。

## 5. 步骤、在 Stripe 后台看什么、预期结果

每一步打印 `-> PASS / FAIL / SKIP / PENDING / REFUSED` 与细节；第 1 步之前是只读的 `preflight`（第 1 节"连上之后"）。一步失败即停止（后面的都依赖它），汇总中其余为 `NOT RUN`。
任何错误（连不上、超时、意外的回答）都记为该步的 `FAIL`，汇总与结果文件照样写出。

| # | 步骤 | 脚本做什么 | 你做什么 | Stripe 后台（测试模式）中看 | 应用中核对（经 API） |
|---|---|---|---|---|---|
| 1 | setup | （`preflight` 已确认测试模式。）serve 的实例上自建发布人；`QB_SETUP`，由第二位管理员只发布**本次**调用返回的提案；若 `qb.transfer.enabled` 不是 true，提出并发布 true（四眼） | — | — | 参数：门槛、审核线、抽成、转账手续费 |
| 2 | sponsor | 注册并验证商家（或用给定账号），申请入驻，管理员批准 | — | — | 入驻 `APPROVED` |
| 3 | topup | `QB_TOPUP_START`（10 000）；核对会话 `cs_test_`、本账户可读、`livemode=false`；等到账 | 打开打印的支付页地址，用 **4242 4242 4242 4242**、任意未来有效期、任意 CVC 与姓名付款 | 支付 → 一笔 ¥10,000 成功；开发者 → 事件（或 `stripe listen` 的输出）中 `checkout.session.completed` 投递 200 | 充值 `PAID`、`livemode=false`、有 `pi_`；钱包 +10 000；分录"Top-up"借 1110 / 贷 2110；手续费记下后分录"Stripe fee"借 5110 / 贷 1110；Stripe 上 PaymentIntent `succeeded`、金额相同 |
| 4 | quiz | 建一题的 Quiz 与发布（及格档奖励 2 000）、管理员批准上架；答题人（JP）答对并提交 | — | — | 提交 `rewardStatus=PAID`；分录（来源 `QbAttempt`）借 2110 2 000 / 贷 2120（扣抽成）/ 贷 4110（抽成）；答题人余额增加，且不低于转账门槛 |
| 5 | onboarding | `QB_PAYOUT_ONBOARD`，把开通链接写进私有文件 `deploy/quizbuks/.stripe-test-mode/onboarding-<运行号>.url`（链接过期即换新）；等 `account.updated` | 在浏览器中打开文件中的链接，用 Stripe 的测试数据完成 Express 开通：测试手机号与验证码 `000000`、出生日期 1901-01-01、地址 `address_full_match`、身份证号 `000000000`、测试银行账号（见 Stripe 文档"Testing Connect"）；完成后被送回 App 的 `/wallet/payout?onboarding=done` | Connect → 账户：新的 Express 账户（JP），"已启用"打款 | 收款账户 `ENABLED`、`acct_…`、`livemode=false`；Stripe 上 `payouts_enabled` |
| 6 | transfer | 检查转账任务的下一次运行在等待时间内；等本次之后建立的转账（大额审核时由管理员批准）；等 `SENT` | — | 余额 → 转账：一笔到该 `acct_` 的转账；Connect → 该账户 → 余额中可见 | 转账 `SENT`、`tr_…`；分录"Transfer"借 2120 / 贷 2130（/ 贷 4110 手续费）与"Transfer settled"借 2130 / 贷 1110；答题人余额 0；Stripe 上金额与目的账户相同 |
| 7 | refund | 用 API 对步骤 3 的付款退款 1 000（等同在后台点"退款"；QuizBuks 自己不提供退款） | — | 支付 → 该付款"部分退款"；事件 `charge.refunded` 投递 200 | 充值 `refundedAmount` + `refundShortfall` = 1 000；钱包减去扣回的部分；分录借 2110 / 贷 1110；余额不够时差额开待办（财务） |
| 8 | dispute | 再发起一笔 2 000 的充值；到账后等争议；以 Stripe 的测试证据 `winning_evidence` 提交，等结束 | 用争议测试卡 **4000 0000 0000 0259** 付款 | 支付 → 争议：一笔"欺诈"争议，提交证据后数分钟内"胜诉" | 争议开始：`disputeStatus=OPEN`、冻结 2 000（钱包 `held`）；结束：`WON`、冻结释放 |
| 9 | payout | 用 API 发起一笔 1 000 的手动打款；等 `payout.paid`（至多 15 分钟） | — | 余额 → 打款：一笔 ¥1,000 到测试银行，状态"已支付" | `QbStripePayout` `PAID`；分录"Stripe payout"借 1120 / 贷 1110。测试模式下打款按到账日才"已支付"：超时记 `PENDING`，之后 `run.sh payout-check po_…` 再查 |

**可用余额（`--fund-balance`）**：卡付款的钱在 Stripe 上先是"待入账"，几天后才可用；转账、退款与打款要可用余额。步骤 4 之前（转账任务会在开通后立即取走答题人）、步骤 7、9 之前，
脚本查询可用的日元余额，不够且有 `--fund-balance` 时用 Stripe 的测试支付方式 `pm_card_bypassPending`（卡 4000 0000 0000 0077，直接进入可用余额）付一笔补足（多 1 000）。
这笔钱**不是 QuizBuks 的充值、不进账本**（每日核对不比较 Stripe 的余额，`deploy/quizbuks/README.md`），在 Stripe 后台显示为一笔"QuizBuks Stripe test-mode run …: balance for transfers and payouts"的付款。
没有 `--fund-balance` 而余额不够时，脚本在那一步之前失败并说明，不让转账失败（余额不足的转账被 Stripe 拒绝后冲正、次日再试）。

**汇总**（例）：

```
Summary of run 20261017-101500
  preflight   PASS     healthy, test mode, Stripe test key
  setup       PASS     QB_SETUP (3 proposal(s) published), transfers on (published qb.transfer.enabled=true); threshold 1000, …
  sponsor     PASS     sponsor approved
  topup       PASS     10,000 paid (PI pi_…), wallet +10,000, posted 1110/2110, Stripe fee 360 posted 5110/1110
  quiz        PASS     reward 2,000 paid; taker +2,000 (2120), commission 0; taker balance 2,000
  onboarding  PASS     Express account acct_… ENABLED (JP)
  transfer    PASS     2,000 sent as tr_… (2,000 net, fee 0) to acct_…; posted 2120/2130 and 2130/1110
  refund      PASS     1,000 refunded at Stripe: 1,000 taken back from the wallet (2110/1110), shortfall 0
  dispute     PASS     dispute du_… on a 2,000 top-up: 2,000 held while OPEN, closed WON …, hold released
  payout      PENDING  po_… (1,000) not recorded yet (…); later, run.sh payout-check po_…
  reconciliation: latest run …: OK (0 item(s) noted)
  results: deploy/quizbuks/.stripe-test-mode/results-20261017-101500.json
```

最后一行之前是每日核对最近一次的结果（serve 的实例每分钟核对一次；测试服务器上是最近的一次）。退款、争议之后核对应为 `OK`：它们都按记录入账。若打款的 `payout.paid` 晚于下一次核对，1110 照样一致（打款本身记 1120 / 1110）。

## 6. CI 作业

`.github/workflows/quizbuks.yml` 的 `stripe-test-mode`：**只在手动触发**（Actions → QuizBuks → Run workflow，勾选 `stripe-test-mode`）时运行；仓库没有 secret `STRIPE_TEST_SECRET_KEY` 时第一步说明并跳过其余步骤。
作业装 Stripe CLI、构建 jar，以 `run.sh serve`（PostgreSQL 与 Mailpit 是作业的服务）启动，再以 `run.sh run --fund-balance --browser "node tools/quizbuks/stripe-test-mode/browser.mjs"` 运行，失败时上传状态目录中的日志、结果与截图（不含 `env` 与 `run.env`）。

- secrets：`STRIPE_TEST_SECRET_KEY`（必需，`sk_test_…`，只给这个作业用的测试账户）。只给用它的步骤（检查有无密钥、`run.sh serve`、`run.sh run`），不给构建、测试与第三方 action。
- 作业自带并发组 `quizbuks-stripe-test-mode`（不取消进行中的）：同一时间只有一次运行使用这个测试账户。
- **无人值守的部分是尽力而为，未经验证**：`browser.mjs` 用 Playwright 在 Stripe 的支付页上填测试卡、在 Express 开通页上逐页点"使用测试数据"类按钮；Stripe 改页面时它会失败（截图在产物中）。人工运行（不带 `--browser`）始终可用。
  第一次启用时请看着它跑一次，并在下面的记录中写明。

## 7. 故障排查

| 现象 | 原因与处理 |
|---|---|
| 等"到账"超时；`stripe listen` 的输出中投递返回 **400** | 签名不符：应用的 `STRIPE_WEBHOOK_SECRET` 不是这次 `stripe listen` 的密钥（重启了 listen 而没重启应用，或用了另一个账户的 CLI）。`run.sh stop && run.sh serve` 一起重启。用后台端点时：密钥是**该端点**的 `whsec_`，两个端点各有各的；滚动密钥期间两个都配上（README"充值与核对"） |
| 投递返回 400 且应用日志中是 `too_old` | 事件的时间戳太旧（重放了很久以前的事件，或本机时钟不准）：校准时钟，不要用 `stripe events resend` 重放旧事件 |
| 投递 200 但充值不到账，后台待办有"模式不符""金额不符"等 | 事件被核对拒绝而只开了待办：常见是 API 版本不一致（第 2 节第 4 条）、应用与 CLI 用的不是同一个账户、或应用是正式模式。看待办与原样事件（财务管理员，`inbound.read`：`GET /api/inbound-events/{编号}/body`） |
| 本机：`port 8093 is in use: a stale Stripe stub?` | 上次的桩没停（它用别的签名密钥签名，应用会拒绝全部事件）。`run.sh stop`；不是本工具启动的就找到占用端口的进程停掉，或设 `STRIPE_TM_STUB_PORT` |
| 本机：应用起来了但 Stripe 调用全部失败 | 应用用的是本机 compose 的占位密钥（`sk_test_quizbuks_local_placeholder…`）或桩的地址：确认是 `run.sh serve`（不带 `--stub`）启动的，或在 `.env` 中放真实的测试密钥并去掉 `QB_STRIPE_API_URL` 等桩的设置 |
| 步骤 3 `this key cannot read the application's checkout` | 应用与脚本的密钥属于不同的账户（或应用是正式模式）：两边用同一个账户的测试密钥 |
| 步骤 3 支付后跳回的页面显示"处理中"很久 | 页面只等服务端的记录；记录要等 Webhook。看 `stripe listen` 是否在运行、投递是否 200 |
| 步骤 5 开通页说链接已过期或已使用 | Stripe 的开通链接几分钟有效、只能用一次：脚本过期即换新，重新打开文件中的链接；若跳回 `?onboarding=refresh`，同样重新打开文件 |
| 步骤 5 一直不是 `ENABLED` | Connect 端点（或 `--forward-connect-to`）没有收到 `account.updated`；或开通还缺资料（Connect → 账户 → 该账户的"需要的信息"）；或答题人的国家与 `QB_STRIPE_CONNECT_COUNTRY` 不同（开通被拒） |
| `preflight` `REFUSED: the application does not show test mode` | 转账任务是每日的计划（或没有）：测试安装设 `QB_TRANSFER_RUN_CRON='0 * * * * *'` 后重启（serve 已设）。脚本在此之前什么也没写 |
| `preflight` `FAIL: GET …/actuator/health: URLError …` | 应用没有在该地址运行（或还在启动）：`run.sh status`、`run.sh logs` |
| 步骤 6 `the next transfer run is at …` | 转账任务按日运行：测试安装设 `QB_TRANSFER_RUN_CRON='0 * * * * *'`（正式模式下启动即失败） |
| 步骤 6 转账 `REVERSED`，`failureCode` 为 `balance_insufficient` | 平台可用余额不够：用 `--fund-balance`，或等卡付款变为可用。该答题人次日自动重试，不计连续失败 |
| 步骤 6 转账 `CHECK` | 结果不明（超时、5xx）：每小时的 `qb.transfer-stale` 向 Stripe 核实后定论；或看 `operations.md`"卡住的转账" |
| 步骤 7 差额不为 0 | 商家的可用余额不够扣回（Kudos 已作为奖励付出）：这是设计（差额记下、开待办由财务处理），汇总中照样 PASS 并写出差额 |
| 步骤 8 一直 `OPEN` | 证据提交后 Stripe 需要几分钟；`--wait-event` 加长。后台 → 争议中看状态 |
| 步骤 9 `SKIP: Stripe refused a manual payout` | 打款计划是自动的，或没有测试银行账户：第 2 节第 5 条 |
| 步骤 9 `PENDING` | 测试模式的打款按到账日变为已支付；之后 `run.sh payout-check po_…` |
| 管理员登录 `MFA_REQUIRED` | 测试服务器开着管理的二次验证：设 `STRIPE_TM_ADMIN_TOTP`（与发布人的 `_TOTP`）。同一个码只能用一次：脚本会等下一个 30 秒 |

## 8. 没有覆盖的

- 正式模式（任何形式）；`charge.dispute.closed` 的败诉（扣回）分支、`payout.failed`、`transfer.reversed`（Stripe 测试模式下撤回转账须在后台手工操作；应用中由集成测试覆盖）、`account.application.deauthorized`。
- 本机 serve 只在回环地址上、管理不要求二次验证；测试服务器上的二次验证、反向代理、真实邮件只在 4.2 的运行中经过。
- Stripe 的费用、外币结算（`QB_STRIPE_SETTLEMENT_CURRENCY` 不是 `jpy` 的账户）。

## 9. 结果记录

每次运行（含干跑与 CI）在下面追加一条；`results-<运行号>.json` 留在本机的状态目录，不提交。

```markdown
### <日期> <运行号>（<本机 serve | 测试服务器 <主机> | CI 运行号 | 桩>）

- 执行人：
- 提交：`git rev-parse --short HEAD` =
- Stripe 账户：acct_…（国家 ，缺省 API 版本 ），Webhook：stripe listen | 后台端点
- 选项：--topup … --reward … --fund-balance …

| 步骤 | 结果 | Stripe 对象 | 备注 |
|---|---|---|---|
| setup | | | |
| sponsor | | | |
| topup | | pi_ / cs_test_ | |
| quiz | | | |
| onboarding | | acct_ | |
| transfer | | tr_ | |
| refund | | re_ | |
| dispute | | du_ | |
| payout | | po_ | |
| 核对 | | | |

- 问题与处理：
- 结论（可以 / 不可以作为"Stripe 测试模式全流程已完成"的依据）：
```

### 记录

（尚无。2026-10-17：脚本以桩干跑通过，见 `plans/Q10-launch.md` Q10c 的实施记录；真实的测试模式运行待做。）
