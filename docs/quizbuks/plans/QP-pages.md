# QP 页面（Q2、Q5、Q6 的前端，约 18.5 天 + 平台 1 天）— 实施计划

依据：`02-design.md` §1 / §7、`03-plan.md`、`plans/Q2-accounts.md`、`plans/Q5-answering.md`、`plans/Q6-payments.md`；平台 12 §12、17 §3 / §4.1、
指南 `docs/guide/app-spa.md`、决策 D34、D36、D43。示范 SPA 是平台的客户门户 `backend/app/portal`。

基准：`origin/1.2/quizbuks` @ 5f02c8d（已含平台 15d 与全部 16* 阶段）。

## 0. 现状（已核实）

- **后端已就绪**：Q2、Q3、Q4、Q5a / b、Q6a / b 的后端都已合入。用户可见的调用全部是平台的通用接口：`/api/processes/{name}`、`/api/queries/{id}`、
  `/api/datasets/{id}/query` 与 `/api/files`。QuizBuks 没有 `@RestController`，所以 SPA 用 `@jabiz/client` 的 `runProcess` / `runQuery` / `api`，
  不需要应用自己的 OpenAPI 快照。
- **目前只接了通用后台**：`build.gradle.kts` 只有 `spa("/admin", "../../frontend")`；`application.yml` 写了 `jabiz.web.spa: [{path: /admin}]`，
  它整体替换 jar 中的 SPA 缺省（17 §3.2），新的 SPA 会被它挡住。`quizbuks-web/` 尚不存在（`.jabiz-app-paths` 已预留）。
- **入口**：`admin`、`app`、`sponsor` 已配置（`app-path` 为 `/admin/`、`/`、`/sponsor/`），还没有 `session.*`（平台 16m 已提供）。
- **邮件链接**：平台邮件指向 `{entryUrl}auth/verify-email|join|password-reset`，即 `/auth/…` 与 `/sponsor/auth/…`，这些页面尚不存在。
  QuizBuks 自己的商家邮件以参数 `link`（`quizbuks.links.sponsor`）给出商家后台的地址。
- **Stripe 的返回地址写在代码中**，SPA 的路由必须与之一致：充值 `<sponsor>/wallet/topups/<topUpId>?checkout=paid|cancelled`（`CheckoutRequest`）；
  开通收款 `<app>/wallet/payout?onboarding=done|refresh`（`PayoutSettings`）。

## 1. 几个 SPA（建议：每个非后台入口一个）

| 路径 | 目录 | `spa(...)` | 入口 | PWA |
|---|---|---|---|---|
| `/` | `quizbuks-web/app` | `spa("/", "../../quizbuks-web/app", entry = "app", pwa = Pwa.ON)` | `app` | 是 |
| `/sponsor` | `quizbuks-web/sponsor` | `spa("/sponsor", "../../quizbuks-web/sponsor", entry = "sponsor")` | `sponsor` | 否 |
| `/admin` | `../../frontend` | 不变，补上 `entry = "admin"` | `admin` | 否 |

- 理由：会话按入口分开（D43 第 1 条），一个 SPA 服务两个入口要在运行时两次 `configureClient`，平台不支持；PWA、`device` 会话与 30 天的刷新令牌只属于答题 App；
  商家后台的编辑器、向导与表格不进入手机端的包。
- **不用 pnpm 工作区**（`02-design.md` §1 原写"pnpm 工作区"，随本计划改正）：`app-check.mjs` 读 SPA 目录自己的 `pnpm-lock.yaml` 的根导入项，
  工作区的 lockfile 会使检查退回 `package.json` 中的版本；`BootAppPlugin` 只把 SPA 自己的 `src`、`public` 等与平台的 `frontend/packages` 作为构建输入，
  共享的兄弟目录或 `link:` 包不是输入，Gradle 可能打出过时的 jar。
- **共享代码**：两个 SPA 各有一份相同的 `src/qb/`（Kudos 格式化 `setAmountUnit('JPY', 'Kudos')` 与 `formatKudos`、流程 / 模板 / 数据视图的编号、
  对照 Java record 手写的 TS 类型、共用的组件与文案）。CI 以 `diff -r quizbuks-web/app/src/qb quizbuks-web/sponsor/src/qb` 保证两份一致。
- **端到端工程** `quizbuks-web/e2e`：第三个 pnpm 工程，只有 `@playwright/test` 与 `@axe-core/playwright`（版本同平台 `frontend/`），不是 SPA，不做 `app:check`。

## 2. 构建与提供

- `build.gradle.kts`：`import com.jabiz.gradle.Pwa`，加上面两行 `spa(...)`。由此得到 `spaCheckRoot`、`spaCheckSponsor`（挂在 `check` 上）；
  根 SPA 的 Service Worker 经 `VITE_JABIZ_SPA_PREFIXES` 放过 `/admin/`、`/sponsor/`。
- `application.yml`：删去 `jabiz.web.spa`，用 jar 中的缺省（指南 §2），否则 `/` 与 `/sponsor/` 不被提供、启动检查 `WEB` 告警。保留缺省的 CSP（含 `img-src blob:`，
  显示需登录读取的图片）；Stripe Checkout 与 Connect 是整页跳转，不需改 CSP；视频资料以外链打开（Q-3），不需 `frame-src`。
  入口加 `session`：`app` 为 `persistence: device`、`refresh-token-ttl: P30D`、`idle-timeout: 0`（设计缺省 Q12）；`sponsor` 为 `idle-timeout: PT30M`、`persistence: tab`；
  `admin` 为 `idle-timeout: PT30M`（不能用 `device`，10 §15.1）。
- 每个 SPA：自己的 `package.json`、`pnpm-lock.yaml`、`vite.config.ts`（`jabizSpa`，开发端口 5175 / 5176，`/api` 代理到 8080）、带指南 §4 `paths` 的 `tsconfig*.json`、
  `eslint.config.js`、`src/test/setup.ts`、`index.css`（`@import '@jabiz/ui/theme.css'`）；依赖版本照抄示范门户。App 另有 `public/` 中的三个 PNG 图标与 manifest（名字 QuizBuks）。
- **邮件链接（A-2，可在 QP1 中做）**：商家邮件的参数 `link` 改为 `{entryUrl.sponsor}`（D43 第 4 条）；`quizbuks.links.sponsor` 保留给 Checkout 的返回地址。
- **Google 登录（A-2，可选）**：`application-google.yml`（由部署开启的 Spring profile）声明提供方 `google`（`kind: google`、`auto-provision: true`、
  `redirect-uris.app: ${QB_OIDC_REDIRECT_APP}`；不给商家入口，Q2-16），客户端编号与密钥只来自环境变量；放在 profile 中，没有 Google 密钥的部署照常启动。

## 3. 各入口的认证流程（全部用 `@jabiz/ui` 的积木，QuizBuks 不写认证代码）

| 流程 | App `/` | 商家后台 `/sponsor/` |
|---|---|---|
| 登录 | `/login`：`SignInForm`（密码、二次验证、入口有提供方时的 `OidcButtons`） | `/login`；未验证者得 403 `EMAIL_NOT_VERIFIED` 与"重发"（`require-verified-email: true`） |
| 注册 | `/signup`：`SignUpForm` → `SignUpSent`；验证后补资料（`QB_PROFILE_SAVE`，D-Q2-2，页面 `/welcome`） | `/signup` → 验证 → 入驻（`QB_SPONSOR_APPLY`，页面 `/onboarding`） |
| 邮件链接 | `/auth/verify-email`、`/auth/join`、`/auth/password-reset`：`MailLinkPage` | `/sponsor/` 下的同样路径 |
| 找回密码 | `/password-reset`：`PasswordResetRequest` | 同左 |
| OIDC | `/login/oidc`：`OidcCallback`；Google 自动开户（16f-1） | 缺省不提供（Q2-16） |
| 账户 | `/account`：资料、`ChangePasswordForm`、`MfaSettings`（可选）、`SessionList`、`LinkedIdentities`、登录记录（`qb.me.sign-ins`） | `/account`：同样的积木；没有提供方时不显示 `LinkedIdentities` |
| 横幅 | `VerifyEmailBanner`（可浏览，写入都要已验证）、`OfflineBanner`、`PwaUpdatePrompt` | `OfflineBanner` |

封禁：`QbSignInGuard` 在登录时拒绝；封禁后的写入得 422 `QB_USER_BANNED`，由共用的问题 → 文案函数显示。`QB_*` 的文案都由服务端按 `Accept-Language` 翻译，SPA 显示其 `detail` / 违规文案。

## 4. 页面与所用的后端（均已核实存在）

**App `/`**（底部导航：首页、历史、钱包、账户；按 ≤ 430 px 设计）

| 路由 | 后端 | 需求 |
|---|---|---|
| `/`（首页） | 模板 `qb.app.feed`；`urn:jabiz:dataset:me:QbProfile`；封面经 `fetchFileContent` + `previewVariant` | U-10 – U-14 |
| `/p/:id`（详情） | `qb.app.publication`、`qb.app.publication-tiers`；打开时 `QB_VIEW`；简介与资料的 Markdown 需要 **PG-1**；开始 → `QB_ATTEMPT_START` | U-20 – U-23 |
| `/play/:attemptId`（一页一题） | `QB_ATTEMPT_START` 的输出（无答案、`deadline`、`serverTime`、`resumed`）；作答与翻页时 `QB_ATTEMPT_SAVE`（防抖）；倒计时按服务器时间偏移；`QB_ATTEMPT_SUBMIT` | U-30 – U-32、U-41 |
| `/result/:attemptId` | `QB_ATTEMPT_SUBMIT` 的输出；之后读 `urn:jabiz:dataset:taker:QbAttempt` 的 `result` 列（D-Q5-2）；`QB_REWARD_PENDING` | U-33、U-34 |
| `/history` | `qb.app.history`；"继续"→ `QB_ATTEMPT_START`（恢复） | U-40 – U-42 |
| `/wallet` | `qb.app.wallet`、`qb.app.ledger` | U-50 – U-52 |
| `/wallet/payout` | `qb.app.payout-account`；`QB_PAYOUT_ONBOARD` → 轮询至出现 `onboardingUrl` → 跳转；处理 `?onboarding=done|refresh` | U-53 |
| `/account`、`/welcome` | `QB_PROFILE_SAVE`；国家 `urn:jabiz:dataset:default:QbCountry`；币种字典；国家按浏览器的语言区域与时区预填（Q2-9） | U-02 – U-04、U-70、U-71 |

**商家后台 `/sponsor/`**（`AppShell`；左侧导航：概览（占位）、Quiz、发布、钱包、账户）

| 路由 | 后端 | 需求 |
|---|---|---|
| `/onboarding` | `urn:jabiz:dataset:sponsor:QbSponsor`（状态、原因）；`QB_SPONSOR_APPLY`；头像 `uploadFile('qb.sponsor-avatar')`；通过后 `QB_SPONSOR_PROFILE_SAVE` | M-01、P-09 |
| `/quizzes`、`/quizzes/:id` | `qb.sponsor.quizzes` 与商家的内容数据视图；`QB_QUIZ_*`、`QB_QUESTION_*`、`QB_MATERIAL_*`（带 `baseRevision`）；版本模板 | M-20 – M-24、M-27、M-28 |
| `/publications`… | `qb.sponsor.publications`；三步向导（`Steps`）：`QB_PUBLICATION_SAVE` → `QB_PUBLICATION_SUBMIT`；撤回、暂停、恢复、复制、删除 | M-30 – M-36 |
| `/wallet` | `qb.sponsor.wallet`、`qb.sponsor.topups`；充值 → `QB_TOPUP_START` | M-04 |
| `/wallet/topups/:id` | 轮询 `QbTopUp` 至 `OPEN` → 跳转 Checkout；返回后轮询至 `PAID` / `EXPIRED` / `FAILED`，超时提示"稍后更新"；浏览器跳回从不入账 | M-04 |
| `/account` | 同 App；登录记录 `qb.me.sign-ins` | M-02、M-03、M-70 |

**通用后台 `/admin/`**：已由元数据生成的页面、`qb.admin.*` 模板、待办与审批、流程页覆盖，不另做；扩展页（P-01 用户汇总、转账监控）推后（Q-5）。

## 5. 缺口

- **应用**（`1.2/quizbuks-*`，都不阻塞 QP1）：A-1 删 `jabiz.web.spa`、加入口的 `session.*`（QP1）；A-2 商家邮件改 `{entryUrl.sponsor}` 与 `application-google.yml`（QP1，可选）；
  A-3 完成后的回看没有题目文字（`result` 只有数字），缺省以 `sessionStorage` 保存开始的输出（不含答案）供刚完成的结果页用，历史只显示分数与逐题对错（Q-2）；
  A-4 商家交易明细（`qb.sponsor.transactions`，M-50 – M-52）属 Q9；A-5 `deploy/quizbuks` 的改动（第 8 节）。
- **平台**（先在 `1.2/platform` 上做，带平台自己的测试与 `backend/app` 中的示范，再合并到 `1.2/quizbuks`）：
  - **PG-1**（阻塞 QP2 的详情与 QP3a 的资料）：`MarkdownView`、`FilePreview` 移入 `@jabiz/ui`（现在只在 `frontend/src/components`，经 `@jabiz/admin` 导出，应用 SPA 不得引用 `@jabiz/admin`）；
    `react-markdown` 成为 `@jabiz/ui` 的运行时依赖；`@jabiz/admin` 照旧再导出；门户示范 `MarkdownView`。分支 `1.2/phase-16n-ui-content-view`，约 1 天。
  - PG-2（可选）：按 SPA 的界面语言。缺省不提（Q-4）。
  - PG-3（以后，Q7）：`@jabiz/ui` 的收件偏好积木。现在不需要。

## 6. 多语言、无障碍、主题

- 语言 `en`、`zh`、`ja`；SPA 文案以 `addMessages(命名空间, …)` 写在 `src/messages.ts` 与 `src/qb/messages.ts`，测试检查三种语言的键一致；服务端文案（`QB_*`、字典、`app.name`）来自接口。
  金额一律经唯一的 `formatKudos` 显示为 `1,234 Kudos`，不出现 "JPY"。
- 无障碍：Playwright + axe（wcag2a/aa、21a/aa、22aa，照 `frontend/e2e/a11y.spec.ts`），每个页面亮、暗两种外观都检查；倒计时有标签、只在阈值时 `aria-live="polite"`，
  选项是 `radio-group` / `checkbox`，翻页时焦点移到题目标题；430 px 的布局在 1280 px 下也检查。
- 主题：只用 `@jabiz/ui` 的组件与主题 token，TSX 中不写十六进制颜色；品牌色只在 SPA 的 `index.css` 中覆盖 `--primary` / `--ring`（亮、暗都要过 axe）；PWA 的 `themeColor` / `backgroundColor` 是 manifest 的值。

## 7. 测试与 CI

- **Vitest**（每个 SPA）：路由与重定向（未登录 → `/login`；没有资料 → `/welcome`）、倒计时（服务器时间偏移、假定时器）、防抖保存、充值与开通的轮询及超时、Kudos 格式、文案键一致；
  `fetch` 照 `portal/src/App.test.tsx` 模拟。
- **Playwright**（`quizbuks-web/e2e`，对打包的 jar）：`auth.spec`（App 注册 → 邮件 → 验证 → 资料；商家注册 → 验证 → 入驻；邮件链接在各入口自己的路径下打开）、
  `play.spec`、`sponsor.spec`、`topup.spec`（经 Stripe 桩至 `PAID`）、`payout.spec`（至 `ENABLED`）、`pwa.spec`（manifest、`sw.js` 只在 `/sw.js`、更新提示）、`a11y.spec`（亮、暗）。
  测试只增数据，可对同一数据库重复运行。
- **`.github/workflows/quizbuks.yml`** 新增作业 `web`（安装平台前端、`pnpm app:check ../quizbuks-web/{app,sponsor}`、每个 SPA 的 `install --frozen-lockfile` / `lint` / `typecheck` / `test`、
  `diff -r src/qb`）与 `e2e`（服务 `postgres:16` 与 `axllent/mailpit`（SMTP 1025、API 8025）；构建 `:quizbuks:bootJar`；以一次性的密钥启动 Stripe 桩
  `java tools/quizbuks/stripe-stub/StripeStub.java 8093 …`；启动 jar（邮件指向 mailpit、Stripe 指向桩、`QB_SPONSOR_LINK` / `QB_APP_LINK` 为本机）；
  在 `quizbuks-web/e2e` 中 `pnpm e2e`，失败时上传报告）。现有作业 `package` 另以 curl 检查 `/`、`/sponsor/`、`/sponsor/wallet`（路由回退）、`/manifest.webmanifest`、`/sw.js`。

## 8. 部署（`deploy/quizbuks`）

- `Dockerfile`：Gradle 构建前 `COPY quizbuks-web quizbuks-web`；`Dockerfile.dockerignore` 已忽略 `**/node_modules`，另加 `quizbuks-web/e2e` 的报告目录。
- `docker-compose.yml`：加 `QB_APP_LINK`、`QB_OIDC_REDIRECT_APP`、`JABIZ_OIDC_GOOGLE_CLIENT_ID` / `_SECRET`、`SPRING_PROFILES_ACTIVE`（可选 `google`）；
  `--profile tools` 下可选的 `mailpit`，供本机注册。
- `README.md`：三个前端；生产的 PWA 与 Stripe 返回需要 HTTPS；`JABIZ_MAIL_BASE_URL` 是主机的根；在 Google 控制台登记 `https://<主机>/login/oidc`；
  Stripe 的返回地址由 `QB_SPONSOR_LINK` / `QB_APP_LINK` 决定。

## 9. 分阶段（分支按根 CLAUDE.md 第 8 节与已有的 `1.2/quizbuks-<N>[x]-<名>`）

| 阶段 | 分支 | 内容 | 天 | 依赖 |
|---|---|---|---|---|
| PG-1（平台） | `1.2/phase-16n-ui-content-view` | `MarkdownView`、`FilePreview` 移入 `@jabiz/ui`，门户示范，测试 | 1 | — |
| QP1 | `1.2/quizbuks-2p-spa-accounts` | 两个 SPA 的骨架；A-1、A-2；第 3 节的全部认证流程；App 的 `/welcome`、`/account`；商家的 `/onboarding`、`/account`；PWA 图标；Google profile；CI 作业 `web`；e2e 骨架（mailpit、桩、管理员准备）与 `auth.spec`、`pwa.spec`、`a11y.spec`；部署改动；更新 `02-design.md` §1 / §7 与 `backend/quizbuks/CLAUDE.md`（新的一节：前端） | 5 | — |
| QP2 | `1.2/quizbuks-5p-play-pages` | 首页、详情、答题、结果、历史；`play.spec` | 4 | QP1、PG-1 |
| QP3a | `1.2/quizbuks-3p-content-pages` | 商家的 Quiz 列表与编辑（题目与带图选项、资料、排序、版本、克隆） | 3.5 | QP1、PG-1 |
| QP3b | `1.2/quizbuks-4p-publishing-pages` | 发布列表、三步向导与档位、操作；`sponsor.spec` | 2.5 | QP3a |
| QP4 | `1.2/quizbuks-6p-payment-pages` | 商家钱包与充值返回页；App 钱包、流水与开通收款；`topup.spec`、`payout.spec` | 3.5 | QP1（e2e 的完整闭环另需 QP3b） |

合计约 18.5 天应用工作加 1 天平台工作。QP2 与 QP3a 可在 QP1、PG-1 之后并行；QP4 只依赖 QP1，但其 e2e 用 QP3b 的数据准备；QP2 合入之前，答题的 e2e 经接口准备发布。
每个 PR：`./gradlew :quizbuks:check` 与 SPA 的检查、`/code-review`（QP1（认证）与 QP4（支付）另做 `/security-review`）、逐条对照验收标准、更新 `03-plan.md` 的状态。

## 10. 风险

- **e2e 的四眼准备**：`QB_SETUP` 只**提出**受控参数与审批规则，须另一位管理员发布；`QB_ADMIN_FINANCE` 要求二次验证。
  应对：e2e 的准备代码建一个只有 `control.publish` 的角色（不要求二次验证）与第二个管理员，经接口发布；只用于测试，写明。
- **手写的流程类型与 Java record 可能不一致**（流程的输入输出没有 OpenAPI 类型）：e2e 覆盖 SPA 调用的每个流程；类型集中在 `src/qb/api.ts`。
- **根 SPA 的 PWA 作用域是 `/`**，Service Worker 的问题可能影响 `/sponsor/`、`/admin/`：平台的 worker 已经按 `VITE_JABIZ_SPA_PREFIXES` 放过其他前缀；
  `pwa.spec` 断言 `/admin/`、`/sponsor/` 的导航不由根 worker 回答；回退用 `Pwa.REMOVE`。
- **倒计时与时钟偏差**：只按 `deadline` 与 `serverTime` 倒数；服务端以 5 秒宽限与 `qb.attempt-expire` 兜底。
- **图片开销**：首页每张卡片一次需登录的图片读取。应用：`previewVariant` 缩略图、IntersectionObserver 懒加载、卸载时释放 blob URL。
- **Stripe 轮询的延迟**（`OPEN` 需 1–3 秒）：有界的退避轮询与"处理较久"的状态；浏览器从不入账。
- **法务意见**仍未取得（`03-plan.md` 第 4 节）：页面只在测试模式下发布。

## 11. 验收标准

1. `./gradlew :quizbuks:check` 通过（含 `spaCheckRoot`、`spaCheckSponsor`）。jar 提供 `/`（PWA：manifest 与 `/sw.js`）、`/sponsor/`（无 `sw.js`：带 `Service-Worker: script` 得 404）
   与 `/admin/`，各自有客户端路由回退。
2. 会话按入口分开：登录 App 不会登录商家后台；App 的会话在关闭并重开浏览器后仍在（`device`）；商家后台闲置 30 分钟锁定。
3. 邮件中的验证与重置链接在 `/auth/…` 与 `/sponsor/auth/…` 打开并完成。配置了 Google 时，只为答题人开户。
4. 邮箱验证之前写入一律拒绝，横幅说明原因；被封禁的人不能登录，已有令牌得 `QB_USER_BANNED`。
5. 答题全流程：提交前看不到答案；重新加载恢复题目与答案；超时显示为自动提交；结果显示逐题对错与奖励或"待支付"。
6. 商家能入驻、编辑、保存版本、发布、经 Checkout（桩）充值并看到 `PAID`；答题人能开通收款账户（桩）并看到 `ENABLED`。
7. 金额一律显示为 Kudos；en、zh、ja 完整（键一致的测试）。
8. axe 在每个 SPA 页面的亮、暗外观下都没有违规；lint、typecheck 与 Vitest 通过，`quizbuks.yml` 的 e2e 作业通过。
9. `tools/check-app-paths.sh` 通过：只改 `.jabiz-app-paths` 中的路径；PG-1 经 `1.2/platform` 合入。

## 12. 待确认问题（括号内为缺省）

| 编号 | 问题 | 缺省 |
|---|---|---|
| Q-1 | 每个入口一个 SPA，还是两个入口共用一个 | 每个入口一个，两个独立的工程（第 1 节） |
| Q-2 | 完成后能否带题目文字回看 | 现在不改后端：刚完成时用 `sessionStorage`，之后只显示分数与逐题对错；需求方要求时再做 A-3 |
| Q-3 | 视频资料是否内嵌 | 不内嵌：新标签页打开外链，保持缺省 CSP。以后内嵌需要 `/` 的 `frame-src`，并在 `application.yml` 中写出完整的 `jabiz.web.spa` |
| Q-4 | 商家后台只要中 / 英（设计 Q16） | 照常提供 en / zh / ja（文案都已有），不做 PG-2 |
| Q-5 | 通用后台的扩展页（P-01 用户汇总、转账监控） | 不在这些阶段；生成的页面与 `qb.admin.*` 模板覆盖运营；与 Q9 一起做 `1.2/quizbuks-9-admin-extension` |
| Q-6 | 商家是否可用 Google 登录（Q2-16） | 只给 App |
| Q-7 | 商家是否要求二次验证 | 可选，经 `MfaSettings` 自助开启；不强制 |
| Q-8 | App 导航中的消息、收件箱、FAQ | 到 Q7 之前不留位置；底部导航现在 4 项，Q7 加第 5 项（收件箱） |
| Q-9 | 参考金额"约合 X USD"（Q6-19） | 不显示，只显示 Kudos |

## 确认：2026-10-14，待确认项全部按缺省

2026-10-14：计划与待确认问题 Q-1 – Q-9 全部按缺省处理。先做 QP1（本分支 `1.2/quizbuks-2p-spa-accounts`）；平台 PG-1 在 `1.2/platform` 上并行（QP1 不需要它）。

## 实施记录（QP1，2026-10-14）

分支 `1.2/quizbuks-2p-spa-accounts`（从 `1.2/quizbuks` @ 5f02c8d 拉出）。只改 `.jabiz-app-paths` 中的路径（`quizbuks-web/**`、`backend/quizbuks/**`、`docs/quizbuks/**`、
`deploy/quizbuks/**`、`.github/workflows/quizbuks.yml`），`tools/check-app-paths.sh origin/1.2/platform` 通过。

- **两个 SPA**：`quizbuks-web/app`（`/`，入口 `app`，PWA，图标为生成的 PNG）与 `quizbuks-web/sponsor`（`/sponsor/`，入口 `sponsor`），各自的 `package.json` 与 lockfile，
  依赖版本照抄示范门户（`pnpm app:check` 通过）。共用的 `src/qb/`：`ids.ts`、`api.ts`（手写的流程类型与调用）、`kudos.ts`、`router.tsx`、`messages.ts`（命名空间 `qb`）、
  `problems.ts`、`device.ts`、`session.ts`（闲置锁定）、`AccountSections.tsx`、`FileImage.tsx`、`LanguageSelect.tsx`、`messageKeys.ts`，两份相同。
- **App**：登录、注册、邮件链接页、找回密码、OIDC 回调、step-up 都是平台积木；登录后先读本人资料，没有国家即到 `/welcome`（资料表单：名字取注册时的显示名，
  国家按浏览器语言区域与时区预填（`country.ts`），参考币种按国家预填），保存后到首页；首页在 QP2 之前只是问候与空状态。账户页：资料、邮箱（不可改）、改密码、两步验证、
  （有提供方时）外部账号、会话、最近登录（`qb.me.sign-ins`，设备由 User-Agent 解析）、界面语言。底部导航只有首页与账户（历史、钱包随 QP2、QP4）。
- **商家后台**：`AppShell` 与左侧导航（概览、商家资料、账户）；`/` 按入驻状态到 `/onboarding` 或 `/dashboard`；入驻页显示状态（含驳回原因），未通过时 `QB_SPONSOR_APPLY`
  （须勾选条款），通过后 `QB_SPONSOR_PROFILE_SAVE`；头像经 `uploadFile('qb.sponsor-avatar')` 上传、以 `FileImage` 显示。闲置锁定后登录页提示原因。
- **A-1**：`build.gradle.kts` 加两行 `spa(...)`（`/admin` 补 `entry = "admin"`）；`application.yml` 删去 `jabiz.web.spa`，入口加 `session.*`。`QuizbuksAppIT` 断言三个 SPA 来自 jar 与两个入口的会话保存方式。
- **A-2**：商家邮件改为 `[…]({entryUrl.sponsor})`，模板去掉参数 `link`；`AccountLinksCheck` 删除（平台的 `MAIL` 检查已要求有链接的模板配置 `jabiz.mail.base-url`）；
  `AccountLinks` 只留给 Stripe 的返回地址。`application-google.yml`（profile `google`）只给入口 `app` 回调，`GoogleProfileIT` 断言只对 App 提供。
- **CI**：`quizbuks.yml` 新增 `web`（平台前端安装、两个 `app:check`、`diff -r src/qb`、两个 SPA 的 lint / typecheck / test、e2e 工程的类型检查）与 `e2e`（postgres、mailpit、
  Stripe 桩、打包的 jar、`pnpm e2e`）；`package` 作业另以 curl 检查三个前端、路由回退、manifest 与 `sw.js`。
- **e2e**（`quizbuks-web/e2e`）：`auth.spec`（App 注册 → 邮件 → 验证（自助注册的账号验证时要输入密码）→ 资料 → 欢迎邮件 → 设备上的会话；找回密码；商家注册 → 未验证不能登录 →
  验证 → 入驻 → 管理员批准 → 通过邮件链接到 `/sponsor/`；后台不接受答题人）、`pwa.spec`（manifest 与唯一的 `sw.js`、根 worker 放过 `/sponsor/`、`/admin/`、`/api/`，离线外壳，更新提示）、
  `a11y.spec`（两个前端的全部页面，亮 / 暗，App 另查 390 px 与 1280 px）。准备数据：管理员执行 `QB_SETUP`，测试自己的角色 `QB_E2E_PUBLISHER`（只有 `control.publish`）发布其提案。
- **偏离计划**：
  - 产品名在 SPA 文案中另写一份（`qb.brand`、manifest）：登录前的页面读不到服务端的 `app.name`。
  - 国家字典按页读取（数据视图每次至多 100 行）。
  - 资料表单在邮箱验证之前不能保存（按钮禁用并说明，可"我已验证"重新取令牌），而不是提交后显示 403。
  - 页面在身份（`/api/auth/me`）返回之前显示加载中：表单以注册时的名字预填，避免竞争。
  - e2e 作业已启动 Stripe 桩（QP1 的测试不用，QP4 用）。
- **未做**（按计划属后续阶段）：QP2 – QP4 的页面；PG-1。

