# QuizBuks — 部署

一个镜像（`Dockerfile`，两段式：构建 jar 并打包三个前端，再放进 JRE 镜像）加 PostgreSQL 16（`docker-compose.yml`）。
命令都在仓库根目录执行。

```sh
docker compose -f deploy/quizbuks/docker-compose.yml up -d --build      # 数据库 127.0.0.1:5440，应用 127.0.0.1:8080
docker compose -f deploy/quizbuks/docker-compose.yml logs app           # 首次启动打印管理员密码
```

- 管理员密码只在首次启动时打印一次；之后在仓库根目录用
  `docker compose -f deploy/quizbuks/docker-compose.yml exec app cat /var/lib/jabiz/secrets/admin-password` 读取
  （入口脚本提示中的 `docker compose exec app …` 省略了 `-f`；应用服务因此命名为 `app`）。
- 三个前端（见下节"前端"）：答题 App <http://localhost:8080/>、商家后台 <http://localhost:8080/sponsor/>、管理员后台 <http://localhost:8080/admin/>。
- 首次登录后执行一次流程 `QB_SETUP`（建角色、账本科目、国家、业务参数；可重复执行，只补从未存在过的，不加回已删除的）。它要求二次验证：
  先在后台的"安全设置"中开启两步验证（TOTP）；只在本机演示时可设 `JABIZ_SECURITY_MFA_ADMINISTRATION=false` 跳过。
- `QB_SETUP` 把受控参数（金额、免审核开关）与审批规则（发布审核 `qb.publication.review`，Q4 起；商家入驻审核 `qb.sponsor.review`，Q2 起）
  作为受控变更**提出**，须由另一位持有 `control.publish` 的管理员（财务管理员）在后台发布（`CONTROL_CHANGE_PUBLISH`）。**发布之前，商家的入驻申请与提交发布一律被拒**
  （`PARAM_NOT_FOUND` 或 `QB_REVIEW_NOT_CONFIGURED`），不会未经审核通过。新版本部署后再执行一次 `QB_SETUP`，并发布新的提案（Q2 部署后：新权限、第二条规则）。

## 本机演示（一条命令）

只为在本机试用：`tools/quizbuks/local-demo.sh` 构建并启动打包的 jar（三个前端在其中），附 Stripe 桩与 Mailpit（不连 Stripe，邮件不出本机），
并完成首次设置（`QB_SETUP`，再由演示用的第二位管理员发布它提出的受控变更）。需要 JDK 21、python3、curl、openssl；
PostgreSQL 与 Mailpit 没有在运行时用 Docker 启动（数据库缺省为 compose 的 `localhost:5440`，`QB_DEMO_DB_*` 可改）。

```sh
tools/quizbuks/local-demo.sh start --seed   # 首次构建要几分钟；--seed 加演示数据
tools/quizbuks/local-demo.sh stop           # 停止它启动的进程与容器（数据保留）
tools/quizbuks/local-demo.sh status | logs | seed
```

- 结束时打印地址与账号：后台 `admin`（密码随机生成，存在 `deploy/quizbuks/.local-demo/env`）；`--seed` 另建已通过并充值 10 000 Kudos、
  上架了 Quiz "World capitals" 的商家 `sponsor@quizbuks.local`，与在日本的答题人 `taker@quizbuks.local`（密码都是 `quizbuks-demo-1`）。
  在 Stripe 桩的支付页上点 Pay 即完成充值；邮件在 <http://localhost:8025>。
- 可重复执行：已有的设置与数据保持不变。密钥只生成一次（完整性与二次验证的密钥必须与数据库一起保留）。
- **只用于本机**：管理操作不要求二次验证，演示用的第二位管理员（角色 `QB_DEMO_PUBLISHER`，只有 `control.publish`）没有二次验证，账号密码固定。
  不要让它对外可达，也不要把这些设置用于任何部署。CI 作业 `local-demo` 照此运行一遍。

## 前端（QP1）

- **三个前端在同一个 jar 中**（`docs/quizbuks/plans/QP-pages.md`）：答题 App 在 `/`（`quizbuks-web/app`，PWA，入口 `app`）、商家后台在 `/sponsor/`
  （`quizbuks-web/sponsor`，入口 `sponsor`）、管理员后台在 `/admin/`（平台通用后台，入口 `admin`）。三者分开登录：一个入口的会话不会登录另一个。
- **会话**：App 的会话保存在设备上（关闭浏览器后仍登录，30 天，不按闲置锁定）；商家后台与管理员后台只在当前标签页中，闲置 30 分钟锁定。
- **已知限制（D43）**：App 的刷新令牌存在设备上（`localStorage` 与 IndexedDB），而三个前端同源：同源的任何页面（含被注入的脚本）都能读到它；
  它只能刷新入口 `app` 的会话（只有 `QB_TAKER` 的权限），缓解是轮换与重用即吊销、不允许内联脚本的 CSP，以及后台与商家后台不用设备保存。
- **生产必须是 HTTPS**：PWA（Service Worker、安装到主屏幕）只在安全来源上工作（本机 `localhost` 除外）；Stripe 支付与开通后跳回的地址（`QB_SPONSOR_LINK`、`QB_APP_LINK`）同样用 HTTPS。
- **PWA 的作用域是整个主机**（`/`）：App 的 Service Worker 只缓存 App 的外壳，放过 `/sponsor/`、`/admin/`、`/api/`；要撤下 PWA，先以 `Pwa.REMOVE` 发布一个版本
  （注销已安装的 Service Worker），至少一个发布周期后再改为 `OFF`（平台指南 `docs/guide/app-spa.md` 第 3 节）。
- **邮件链接**按入口指向各自的前端：App 的验证、找回密码链接在 `/auth/…`，商家后台的在 `/sponsor/auth/…`。`JABIZ_MAIL_BASE_URL` 必须是主机的根地址。
- **Google 登录（只给 App）**：在 Google Cloud 控制台建 OAuth 客户端（Web 应用），把 `https://<主机>/login/oidc` 登记为已授权的重定向 URI，然后设置
  `SPRING_PROFILES_ACTIVE=google`、`JABIZ_OIDC_GOOGLE_CLIENT_ID`、`JABIZ_OIDC_GOOGLE_CLIENT_SECRET` 与 `QB_OIDC_REDIRECT_APP`（同一地址）。
  首次用 Google 登录且邮箱未被使用时自动开户（答题人）；邮箱已被使用的人先用原来的方式登录，再在 App 的账户页关联 Google。商家后台不提供 Google 登录。
- **Stripe 的返回地址**由配置决定，不在 Stripe 后台设置：充值跳回 `QB_SPONSOR_LINK` 下的 `wallet/topups/<编号>`，开通收款账户跳回 `QB_APP_LINK` 下的 `wallet/payout`。
- **本机注册**：`docker compose -f deploy/quizbuks/docker-compose.yml --profile tools up -d` 另起 Mailpit（<http://localhost:8025> 查看邮件）；
  在 `.env` 中设 `JABIZ_MAIL_ENABLED=true`、`JABIZ_MAIL_FROM=noreply@quizbuks.local`、`SPRING_MAIL_HOST=mailpit`、`SPRING_MAIL_PORT=1025`。

## 账户（Q2）

- **三个登录入口**：后台 `admin`（只接受管理员角色，`QB_ADMIN_ROLES`）、答题 App `app`（`QB_TAKER`，允许自助注册，未验证邮箱也能登录浏览）、商家后台 `sponsor`
  （`QB_SPONSOR`，允许自助注册，**要求已验证邮箱**）。注册的角色由 `QB_SETUP` 建立：执行之前注册一律 422 `SIGN_UP_DISABLED`。
  升级说明：此前在后台登录的商家、答题人的测试账号改用各自的入口（App `/login`、商家后台 `/sponsor/login`）。
- **邮件必须开启**才能激活：`JABIZ_MAIL_ENABLED=true` 并配置发件人与邮件服务器（`JABIZ_MAIL_FROM`、`SPRING_MAIL_*`）；
  否则注册的用户收不到验证邮件，永远"待激活"（启动告警）。`JABIZ_MAIL_BASE_URL` 是**主机的根地址**（如 `https://quizbuks.example.com`，不带 `/admin`）：
  平台 16c 之后，验证、找回密码等链接指向各入口自己的前端（App `/auth/…`、商家后台 `/sponsor/auth/…`、后台 `/admin/auth/…`，D43），
  退订页在后台（`/admin/mail/unsubscribe`）；此前发出的 `/admin/auth/…` 旧链接仍可用。升级说明：把 `JABIZ_MAIL_BASE_URL` 从 `…/admin` 改为主机的根地址。
  商家邮件（入驻通过、驳回）中的商家后台链接同样取自它（`{entryUrl.sponsor}`，即该主机上的 `/sponsor/`），不再取 `QB_SPONSOR_LINK`。
- **在反向代理之后**运行时设置 `JABIZ_TRUSTED_PROXIES`（代理的地址或网段），否则登录记录中的 IP 都是代理的地址。不要同时设置 `server.forward-headers-strategy`（启动错误）。
- **商家入驻**：商家注册、验证邮箱后在商家后台申请入驻，由内容管理员在 `/tasks` 的审批面板中审核；通过后才能提交发布。
  Q2 之前由管理员手工建的商家账号没有入驻记录，须申请一次（`QB_SPONSOR_APPLY`）。
- **封禁**：超级管理员在后台对用户执行 `QB_USER_BAN`；被封禁的人三个入口都不能登录，已登录的会话在下一次刷新（至多 15 分钟）后失效，期间提交发布等动作被拒。
- 只起数据库做本地开发：`docker compose -f deploy/quizbuks/docker-compose.yml up -d db`，再在 `backend/` 下
  `./gradlew :quizbuks:bootRun --args='--spring.profiles.active=dev'`。

## 答题（Q5）

- **部署后再执行一次 `QB_SETUP`**：答题人（`QB_TAKER`）新增 `qb.content.file.read`（读题目的封面、图片与资料文件）。Q5 没有新的受控参数或审批规则。
- **开发入账在生产中关闭**：`QB_WALLET_DEV_CREDIT`（不付款就给商家钱包入账，供开发与测试使用；Q6a 起有了充值，生产不需要它）只在
  `quizbuks.wallet.dev-credit.enabled=true` 时注册（只有 `dev` 配置与测试开启）。**生产不得开启**：开启时若为正式模式或 `STRIPE_API_KEY` 是正式密钥
  （`sk_live_…` / `rk_live_…`），启动即失败。其权限 `qb.wallet.dev-credit` 不授予任何角色，但持有 `*` 的首个管理员可以调用。
- **结算与待支付**：答题奖励在提交的同一事务中从商家钱包（科目 2110）记到用户（2120）；商家余额不足时记为待支付、发布进入预算不足，
  有余额后（Q5 只有开发入账，Q6 起为充值）按提交顺序补发，补完后发布自动回到进行中。
- **完成人数**：任务 `qb.publication-stats` 每 10 分钟统计各发布近 1 小时、24 小时、7 天、30 天的完成数（集群中只有一个实例执行）。

## 充值与核对（Q6a）

- **部署后再执行一次 `QB_SETUP`，并由另一位管理员发布新的提案**：受控参数 `qb.topup.min`（1 000）、`qb.topup.max`（1 000 000）；
  财务管理员新增 `inbound.read`（查看 Stripe 的原样事件，可能含付款人姓名与邮箱）与 `external-job.read`（查看调用 Stripe 的作业）。
  发布之前发起充值一律被拒（`PARAM_NOT_FOUND`）。
- **当前只走测试模式**：`QB_STRIPE_LIVEMODE` 缺省 `false`，`STRIPE_API_KEY` 必须是测试密钥（`sk_test_…`，建议受限密钥 `rk_test_…`）；
  正式模式须用正式密钥，且**开发入账必须关闭**，否则启动失败。正式开放之前须取得法务意见（`docs/quizbuks/03-plan.md` 第 4 节）。
  模式与配置不同的 Stripe 事件不入账，只开待办。
- **受限密钥**（Stripe 后台 → 开发者 → API 密钥 → 创建受限密钥）只开：Checkout Sessions 写、PaymentIntents 读（读手续费），
  Q6b 再加 Transfers 写与读、Accounts 与 Account Links 写。密钥只放在环境变量中，不进仓库与日志。
- **Webhook 端点**（Stripe 后台 → 开发者 → Webhook → 添加端点）：
  - 账户端点 `https://<主机>/api/inbound/stripe`，事件只选 `checkout.session.completed`、`checkout.session.expired`、`charge.refunded`、
    `charge.dispute.created`、`charge.dispute.closed`；把该端点的签名密钥（`whsec_…`）放进 `STRIPE_WEBHOOK_SECRET`。**缺少即启动失败**。
  - **API 版本**：端点的 API 版本必须与 `QB_STRIPE_API_VERSION`（缺省 `2025-03-31.basil`）相同。应用以 `Stripe-Version` 固定自己请求的版本，
    但事件正文的结构由端点的版本决定；不一致时字段对不上，事件只会开待办。
  - Q6b 另加 Connect 端点（`/api/inbound/stripe-connect`，另一个签名密钥）。
  - 换签名密钥：Stripe 后台"滚动"密钥时新旧两个同时有效一段时间；期间把两者都配上——环境变量
    `JABIZ_INBOUNDWEBHOOKS_PROVIDERS_STRIPE_SECRETS_0`（新）与 `JABIZ_INBOUNDWEBHOOKS_PROVIDERS_STRIPE_SECRETS_1`（旧），过期后删去旧的。
- **在反向代理之后**必须设置 `JABIZ_TRUSTED_PROXIES`：入站 Webhook 按客户端地址限流，否则 Stripe 的全部投递共用一个桶（每分钟 120 次）。
- **商家后台的地址**（`QB_SPONSOR_LINK`，或 `JABIZ_MAIL_BASE_URL` 主机上的 `/sponsor/`）也是 Stripe 支付后跳回的地址；两者都没有时充值一律被拒（启动告警）。
- **到账只以 Stripe 的签名通知（或向 Stripe 核实的结果）为准**，按服务端记录的金额入账，浏览器跳回不入账。丢失通知的充值由每小时的任务
  `qb.topup-stale` 在支付页过期一小时后向 Stripe 核实。退款与争议只能在 Stripe 后台发生：按可用余额（余额减去冻结）能扣多少扣多少，
  余下的记为差额（`refund_shortfall` / `dispute_shortfall`）并开待办由财务线下处理；**差额记下后不再自动扣回**，以后的退款只扣新增的部分。
  争议一开始就把争议金额（可用余额以内）冻结在商家钱包中（`held`，不是账本分录：余额与 2110 不变，只是不能再付奖励，奖励因此待支付）；
  胜诉释放并支付待付奖励，败诉从冻结中扣回。
- **退款晚于充值确认 30 分钟以上**才到、而充值当时尚未入账的（极少：确认丢失且核实也失败），退款事件只开待办 `UNKNOWN_PAYMENT`，
  须财务在充值入账后人工对照扣回（应用不会再次处理这条事件）。
- **1110（Stripe 余额）在 F-05 之前会与 Stripe 的实际余额有出入**：争议手续费、退款时 Stripe 不退还的手续费等不经 QuizBuks 入账；
  每日核对不比较 Stripe 的余额，由财务按 Stripe 报表调整。
- **正式上线（go-live）**：`QB_STRIPE_LIVEMODE=true` 时，只要数据库中还有测试模式的充值（`livemode=false` 或 `cs_test_` 会话），
  或 2110（商家钱包合计）不为 0，**启动即失败**（`QUIZBUKS | quizbuks.stripe.allow-live-with-test-data`，启动检查直接读数据库）：
  测试的 Kudos 不能在正式环境中付出真实奖励。正式环境用新的数据库；确需沿用时，核清测试数据后显式设置
  `QB_STRIPE_ALLOW_LIVE_WITH_TEST_DATA=true`（只告警），上线后再删去。
- **Adaptive Pricing 已关闭**：创建支付页时发送 `adaptive_pricing[enabled]=false`，只收日元（D-Q6-5）；若 Stripe 仍报告了买方币种，照常记录。
- **每日核对** `qb.reconcile`（UTC 18:37，即东京 03:37）比较商家钱包、待支付、2110 合计、账本平衡与每笔充值的分录；有差异时开待办（`qb.reconcile`
  权限）并记录在 `qb.admin.recon-runs` / `qb.admin.recon-items`。
- **Stripe 的原样事件保留 7 年**（与会计凭证相同，`RetentionPolicy`），只有财务管理员能读。
- **本地开发**：`java tools/quizbuks/stripe-stub/StripeStub.java` 起桩服务（端口 8093），`dev` 配置已指向它（API、支付页、开发用签名密钥）；
  支付页上的按钮把签名的事件发回 `http://localhost:8080/api/inbound/stripe`。改用 Stripe 的真实测试模式：设置 `STRIPE_API_KEY`、
  `QB_STRIPE_API_URL=https://api.stripe.com`、`QB_STRIPE_API_HOSTS=api.stripe.com`、`QB_STRIPE_CHECKOUT_URL_PREFIX=https://checkout.stripe.com/`，
  并用 Stripe CLI 转发事件：`stripe listen --forward-to localhost:8080/api/inbound/stripe`（把它打印的 `whsec_…` 放进 `STRIPE_WEBHOOK_SECRET`）。
- **本机的 compose** 不再缺省可启动：先在 `deploy/quizbuks/.env` 中放 `STRIPE_API_KEY`（测试密钥）、`STRIPE_WEBHOOK_SECRET` 与 `STRIPE_CONNECT_WEBHOOK_SECRET`
  （Q6b；只在本机：`echo "STRIPE_WEBHOOK_SECRET=whsec_$(openssl rand -hex 24)" >> deploy/quizbuks/.env`，Connect 的同样）。仓库中不放任何签名密钥：
  知道签名密钥的人可以伪造到账通知。`dev` 配置中固定的开发用签名密钥是公开的：**绝不能**让以 `dev` 配置启动的实例对外可达，
  也不能在任何可访问的环境中使用该密钥。

## 收款账户与自动转账（Q6b）

- **部署后再执行一次 `QB_SETUP`，并由另一位管理员发布新的提案**：受控参数 `qb.transfer.fee`（转账手续费，缺省 0 = 不收）与审批规则 `qb.transfer.review`
  （超过 `qb.transfer.review-above` 的转账由持有 `qb.payout.review` 的财务审核；收款人不能审核自己的转账）。规则未发布或停用时，大额转账跳过（不写记录，用户看不到失败），每天开一个待办。
- **自动转账缺省关闭**（受控参数 `qb.transfer.enabled = false`，四眼）。**正式开放转账（以及正式模式）之前必须先取得法务意见**（资金決済法，`docs/quizbuks/03-plan.md` 第 4 节）；
  测试模式下可以打开以演示。门槛 `qb.transfer.threshold`、审核额 `qb.transfer.review-above`、手续费 `qb.transfer.fee` 都是受控参数。
- **Connect 端点**（Stripe 后台 → 开发者 → Webhook → 添加端点，选择"已连接的账户"）：`https://<主机>/api/inbound/stripe-connect`，事件只选
  `account.updated`、`account.application.deauthorized`；把该端点的签名密钥放进 `STRIPE_CONNECT_WEBHOOK_SECRET`（**缺少即启动失败**），API 版本同账户端点。
  账户端点另加事件 `transfer.reversed`、`payout.paid`、`payout.failed`。
- **受限密钥**另开：Accounts 与 Account Links 写、Transfers 写与读。
- **App 的地址**（`QB_APP_LINK`，如 `https://quizbuks.example.com/`）是 Stripe 开通页面完成后跳回的地址（`…/wallet/payout?onboarding=done|refresh`）；没有它开通一律被拒（启动告警）。
  收款账户只在 `QB_STRIPE_CONNECT_COUNTRY`（缺省 `JP`）开通，资料国家不同即拒绝（不跨境）。平台不保存银行信息，由 Stripe 的页面收集。
- **转账只转一次**：每人至多一笔未结束的转账（数据库部分唯一索引）；发出作业先按 `transfer_group = qb-<转账号>` 查询，已有即作为结果，否则以固定的幂等键
  `qb-transfer-<转账号>` 创建。Stripe 明确拒绝创建时冲正、钱回到用户余额；结果不明（超时、429、5xx、租约丢失）时进入 `CHECK` 并开待办，**不冲回**，
  由每小时的 `qb.transfer-stale` 按组查询定论（只有晚于发送结束至少 10 分钟的查询说"没有"才冲正）。同一用户因账户连续 3 次失败后不再自动转账，开待办等财务处理。
- **暂停与放行**：转账被审核驳回、或被 Stripe 撤回（含部分）后，该用户不再自动转账，直到财务（`qb.payout.review`）执行 `QB_TRANSFER_HOLD_RELEASE` 放行。
- **Stripe 的可用余额**：充值几天后才变为可用余额，余额不足时 Stripe 拒绝创建并把拒绝保存在幂等键下：该转账立即冲正，次日以新的转账（新键）再试，不计入用户的连续失败。上线前要定资金调度。
- 被 Stripe 撤回的转账（`transfer.reversed`）按累计金额退回用户余额（手续费不退），并开待办。打款到银行（`payout.paid`）记借 1120 / 贷 1110，失败冲正。

## 环境变量

放在 `deploy/quizbuks/.env`（不提交，已在 `.gitignore` 中）或部署平台的密钥库中。密钥都是 Base64、至少 32 字节（`openssl rand -base64 48`）。

| 变量 | 必需 | 说明 |
|---|---|---|
| `DB_PASSWORD` | 服务器上必需 | 数据库用户 `quizbuks` 的密码（缺省 `quizbuks` 只用于本机） |
| `JABIZ_JWT_SECRET` | 是 | 访问令牌签名密钥。本机首次启动时入口脚本自动生成到 secrets 卷 |
| `JABIZ_INTEGRITY_KEY` | 是 | 只追加表的封存密钥（平台 21 §2）。**保留副本**：没有它无法核验已有封存 |
| `JABIZ_MFA_KEY` | 是 | TOTP 密钥的加密密钥（平台 10 §9）。**保留副本**：没有它已绑定的用户无法二次验证 |
| `JABIZ_BOOTSTRAP_ADMIN_USER` / `JABIZ_BOOTSTRAP_ADMIN_PASSWORD` | 首次启动 | 第一个管理员（拥有全部权限）；本机未给密码时自动生成并打印 |
| `JABIZ_FILES_LOCAL_ROOT` | 是 | 上传文件目录；镜像中已设为卷 `/var/lib/jabiz/files` |
| `SPRING_R2DBC_URL` / `SPRING_R2DBC_USERNAME` / `SPRING_R2DBC_PASSWORD`、`SPRING_FLYWAY_URL` / `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` | compose 已给 | 数据库连接（请求路径用 R2DBC，迁移用 JDBC） |
| `JABIZ_SECURITY_MFA_ADMINISTRATION` | 否 | `false` 时平台管理操作（含 `QB_SETUP`）不要求二次验证；只用于本机演示与 e2e |
| `QUIZBUKS_PORT` / `QUIZBUKS_DB_PORT` | 否 | 本机端口，缺省 8080 / 5440 |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | 否 | `ecs` 输出 JSON 日志（compose 已设） |
| `QB_ADMIN_ROLES` | 否 | 后台入口接受的角色，逗号分隔；缺省 `ADMIN,QB_ADMIN_SUPER,QB_ADMIN_CONTENT,QB_ADMIN_FINANCE`。**不得**含 `*`、`QB_TAKER`、`QB_SPONSOR`（启动错误） |
| `QB_APP_SELF_REGISTRATION` / `QB_SPONSOR_SELF_REGISTRATION` | 否 | 答题 App、商家后台是否允许自助注册，缺省 `true` |
| `JABIZ_MAIL_ENABLED` | 注册时必需 | `true` 开启邮件（缺省关闭：消息只记录为 `SKIPPED`） |
| `JABIZ_MAIL_FROM` | 开启邮件时 | 发件人地址 |
| `JABIZ_MAIL_BASE_URL` | 开启邮件时 | 主机的根地址，如 `https://quizbuks.example.com`（链接按入口指向各自的前端，退订页在 `/admin/`） |
| `SPRING_MAIL_HOST` / `SPRING_MAIL_PORT` / `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD` | 开启邮件时 | 邮件服务器（SMTP） |
| `QB_SPONSOR_LINK` | 否 | 商家后台的绝对地址，Stripe 支付后跳回（`…/wallet/topups/…`）；缺省为 `JABIZ_MAIL_BASE_URL` 主机上的 `/sponsor/` |
| `SPRING_PROFILES_ACTIVE` | 否 | `google` 开启 App 的 Google 登录（`application-google.yml`，见"前端"） |
| `JABIZ_OIDC_GOOGLE_CLIENT_ID` / `JABIZ_OIDC_GOOGLE_CLIENT_SECRET` | 开启 Google 时 | Google OAuth 客户端（Web 应用）；密钥只放在环境变量中 |
| `QB_OIDC_REDIRECT_APP` | 开启 Google 时 | App 的回调地址 `https://<主机>/login/oidc`，与 Google 控制台中登记的完全相同 |
| `JABIZ_TRUSTED_PROXIES` | 在代理之后 | 可信代理的地址或网段（逗号分隔），登录记录据此取客户端地址 |

外部服务（Q6a 起 Stripe 为必需；见上节"充值与核对"）：

| 变量 | 必需 | 说明 |
|---|---|---|
| `STRIPE_API_KEY` | 是 | Stripe 密钥，模式须与 `QB_STRIPE_LIVEMODE` 相同（测试模式 `sk_test_…` / `rk_test_…`，建议受限密钥）；每个部署（美国、日本）用本国的 Stripe 账户 |
| `STRIPE_WEBHOOK_SECRET` | 是 | 账户端点 `/api/inbound/stripe` 的签名密钥（`whsec_…`，至少 32 字符）；缺少即启动失败 |
| `QB_STRIPE_LIVEMODE` | 否 | 缺省 `false`（测试模式）；`true` 时须正式密钥、开发入账关闭 |
| `QB_STRIPE_API_VERSION` | 否 | 缺省 `2025-03-31.basil`，须与 Stripe 后台端点的 API 版本相同 |
| `QB_STRIPE_CHECKOUT_TTL` | 否 | 支付页的有效期，缺省 `PT1H`（40 分钟到 23 小时） |
| `QB_STRIPE_SETTLEMENT_CURRENCY` | 否 | Stripe 账户余额的币种（手续费与净额的币种），缺省 `jpy`；收款与退款始终是日元，非日元的结算手续费不自动过账，开待办 |
| `QB_STRIPE_ALLOW_LIVE_WITH_TEST_DATA` | 否 | 缺省 `false`；`true` 时允许在留有测试模式数据的数据库上以正式模式启动（只告警），只在有意为之时设置 |
| `QB_STRIPE_CHECKOUT_URL_PREFIX` | 否 | 支付页地址必须的前缀，缺省 `https://checkout.stripe.com/`（只在开发与端到端测试中改指桩服务；正式模式只接受缺省值）；须为以 `/` 结尾的完整地址 `https://主机[:端口]/…/`（不含用户信息、查询与片段；本机桩 `localhost`、`127.0.0.1`、`[::1]` 可用 `http`） |
| `QB_STRIPE_API_URL` / `QB_STRIPE_API_HOSTS` | 否 | Stripe API 的地址与允许的主机，缺省 `https://api.stripe.com` / `api.stripe.com`（只在开发中改指桩服务） |
| `STRIPE_CONNECT_WEBHOOK_SECRET` | 是（Q6b） | Connect 端点 `/api/inbound/stripe-connect` 的签名密钥（`whsec_…`）；缺少即启动失败 |
| `QB_APP_LINK` | 收款时 | App 的地址，Stripe 开通收款账户后跳回（缺省空：开通被拒） |
| `QB_STRIPE_CONNECT_COUNTRY` | 否 | 收款账户的国家，缺省 `JP` |
| `QB_STRIPE_CONNECT_URL_PREFIX` | 否 | 开通链接必须的前缀，缺省 `https://connect.stripe.com/`（只在开发与端到端测试中改指桩服务；正式模式只接受缺省值）；格式同上 |
| `OPENAI_API_KEY` | Q8 | AI 生成题目与封面 |

可观测性（OTLP）与单点登录的变量同平台：见 `docs/guide/quickstart.md`。
