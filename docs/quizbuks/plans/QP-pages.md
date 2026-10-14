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
- **App**：登录、注册、邮件链接页、找回密码、OIDC 回调、step-up 都是平台积木；登录后先读本人资料，**邮箱已验证**且没有国家即到 `/welcome`（资料表单：名字取注册时的显示名，
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

### 评审后的修正（2026-10-14）

- **未验证的答题人照常浏览**（设计 D-Q2-1：App 接受未验证的用户登录浏览）：只有**邮箱已验证且资料没有国家**时才进 `/welcome`；未验证的人留在首页等页面并看到
  `VerifyEmailBanner`（底部导航照常），`/welcome` 仍可打开，但验证之前不能保存。首页以资料的名字、否则注册的名字问候。
- 表单字段的服务端错误以 `aria-describedby` 关联到字段（与提示一起）并设 `aria-invalid`：资料的全部字段、入驻的全部字段（名字、公司、简介、头像、条款）。
- 账户页在入口不再提供身份提供方、但本人已关联外部账号时也显示"外部账号"一节，可查看与解除。
- 已登录时打开 `/signup`、`/password-reset` 即回首页（两个 SPA）。
- CI：工作流只读权限（`permissions: contents: read`）；`package` 作业检查 `/`、`/welcome`、`/sponsor/`、`/admin/` 的 CSP 头。部署说明写明设备上的刷新令牌对同源页面可读（D43 的已知限制）。


## 实施记录（QP2，2026-10-14）

分支 `1.2/quizbuks-5p-play-pages`（从 `1.2/quizbuks` @ d6cb5b0 拉出，已含 PG-1）。只改 `.jabiz-app-paths` 中的路径（`quizbuks-web/**`、`backend/quizbuks/CLAUDE.md`、`docs/quizbuks/**`），
后端行为不变：第 4 节 "App /" 表中的流程、模板与数据视图都已存在并照用（`QB_VIEW`、`QB_ATTEMPT_START` / `SAVE` / `SUBMIT`、`qb.app.feed`、`qb.app.publication`、
`qb.app.publication-tiers`、`qb.app.history`、`urn:jabiz:dataset:taker:QbAttempt`）。

- **共用目录 `src/qb/`**（两份相同）：`ids.ts` 加答题的名字（`PLAY`、`CONTENT_IMAGE_VARIANTS`、`CODE` 的四个答题错误码）；新文件 `play.ts`（对照 `AttemptProcesses`、`AttemptFinishing`
  与 `queries/qb/app/*.sql` 手写的类型与调用，模板的数值列按数字或十进制文本读取）与 `play.test.ts`。商家后台不用它们，只为保持两份相同。
- **首页 `/`**：`qb.app.feed`（`at` 为打开页面的时刻、国家取自资料）；紧凑卡片（封面缩略图 `w320` 与商家头像按 IntersectionObserver 懒加载、名称、发布人、最高奖励、完成人数），
  一屏多于 6 张（390 × 844）；按名称与标题搜索，六种排序（U-14），底部抽屉中的筛选：发布人（公司名，按原文包含）、发布人国家、最高奖励区间、最少完成人数（U-13）；"显示更多"分页。
- **详情 `/p/:id`**：标题下方是资料（文章以 `MarkdownView`，链接与视频链接在新标签页打开（Q-3），PDF 以 `FilePreview` 下载，图片组以 `FilePreview` 缩略图与说明，音频以
  `useFileUrl` 的 `<audio>`），然后是简介（Markdown）、概要、奖励档位表、完成人数（1 小时 / 24 小时 / 7 天 / 30 天 / 累计与统计时刻）、商家简介；底部固定的大按钮：
  开始、继续（剩 N 分钟）、已参加即"查看结果"、未验证邮箱即说明、否则不可用。已验证的答题人打开时 `QB_VIEW`（失败不影响页面）。
- **答题 `/play/:attemptId`**：开始或继续的输出由详情、历史页交给答题页（不再调用）；重新加载时以本标签页保存的开始输出、否则以本人的会话记录找到发布，再以 `QB_ATTEMPT_START`
  继续（恢复题号与已选答案）；已结束即到结果页，时间已过即说明正在提交。一页一题，进度"第 n / N 题"，选项按会话的顺序显示为 A、B、C…；**选项一律为复选框**（偏离计划第 6 节的
  "radio-group / checkbox"：开始的输出不说哪些题有多个正确选项，说了即泄露答案的一部分；提示"选出所有正确的选项"）。作答后 0.8 秒、翻页时立即 `QB_ATTEMPT_SAVE`
  （`play/saver.ts`：一次一个、只发自上次成功以来改过的题、失败保留并 5 秒后重试、会话结束即停止）。倒计时按 `deadline` 与 `serverTime` 的偏移（`play/countdown.ts`），
  `role="timer"`，只在剩 1 分钟与 10 秒时由礼貌的 live 区域朗读；翻页后焦点移到题目标题。最后一题"提交答案"先确认（说明未答的题数）；到 0:00 不确认即提交；
  提交超时或已被系统结束即到结果页；提交因其他原因失败时留在本页、重新保存全部答案、可再次提交（同一个 `Idempotency-Key`）。答题时不显示底部导航。
- **结果 `/result/:attemptId`**（A-3 按缺省）：刚提交时用提交的输出与本标签页的开始输出，显示得分、合格分、是否合格、奖励（已到账 / 待支付及服务端的说明 / 无）与逐题的
  "我的答案 vs 正确答案"（选项字母与文字）；之后（其他标签页、关闭后）读本人的会话记录（`result` 列）与 `qb.app.publication`（名称、合格分），只显示分数与逐题对错并说明题目内容
  只在刚完成时显示。超时自动提交（本页提交或状态 `EXPIRED`）另有说明；时间已过而尚未结束的会话每 5 秒再读，直到系统以已保存的答案结束它。退出登录时清除本标签页保存的答题数据。
- **历史 `/history`**：`qb.app.history`（`at` 为打开的时刻），搜索与"全部 / 已完成 / 未完成"（`category` 筛选）；每行：名称、类别（合格、不合格、进行中、已超时）、开始时间、
  得分与奖励（已获得 / 待支付）；进行中且在时限内为"继续答题（剩 N 分钟）"→ `QB_ATTEMPT_START`（恢复）；已完成与已超时链接到结果页。
- **底部导航**：首页、记录、账户（钱包随 QP4）；详情页属于首页，答题与结果属于记录。
- **文案**：`app` 命名空间加 `feed`、`detail`、`play`、`result`、`history` 与 `nav.history`，三语齐全；计数一律写成各数通用的形式（"已完成：3"），因为键一致的测试要求三种语言键相同
  而中文、日文没有复数形式。金额只经 `formatKudos`。
- **测试**：Vitest 新增 `play/countdown.test.tsx`（服务器时间偏移、格式、阈值、假定时器下的到期一次）、`play/saver.test.ts`（防抖、翻页立即保存、串行、失败保留重发、致命错误停止、
  提交前停止）、`qb/play.test.ts`、`routes.test.ts`、`Play.test.tsx`（首页、详情的 Markdown 与链接与 `QB_VIEW`、未验证不能开始、完整答题与确认提交与结果、提交失败后重试、
  重新加载恢复、只有会话编号时恢复、到时自动提交、服务端说时间已过、之后读到的结果、等待系统结束、历史的筛选与继续）。e2e `play.spec.ts`：经接口准备（商家入驻并经 Stripe 桩充值、
  内容与版本、发布与管理员审批），亮、暗两种外观各一次的完整流程（首页搜索、详情、开始的输出不含 `correct`、重新加载恢复、另一答题人用完预算后本人提交得"待支付"、
  历史、不能再次开始），以及 30 秒时限的一次（从历史继续，到时自动提交并说明）；每个新页面都过 axe。
- **未做**：首页的"发布日期区间"筛选与历史的日期、金额区间筛选（U-13、U-42 的一部分，P1）；发布人筛选只按公司名、区分大小写（模板的外层 `LIKE`）。
- **CI**：`quizbuks.yml` 的 `e2e` 作业加 `QUIZBUKS_STRIPE_CHECKOUTURLPREFIX`：`application.yml` 没有把 `QB_STRIPE_CHECKOUT_URL_PREFIX` 映射到
  `quizbuks.stripe.checkout-url-prefix`（只有 `application-dev.yml` 映射），打包的 jar 因此只接受 Stripe 自己的地址、桩的 Checkout 被判 `OPEN_MISMATCH`；
  `play.spec` 经桩充值，所以以 Spring 的宽松绑定直接设置该属性。是否在 `application.yml` 中补上映射（与 `connect-url-prefix` 一致）留给 QP4 决定，本阶段不改后端。
- **发现**：平台的 `ProblemDetail` 违规只带 `field`、`ruleCode`、`message`，不带 `params`，所以 `QB_ATTEMPT_ALREADY_TAKEN` 的 `attemptId` 到不了前端：答题页对它转到本会话的结果页，
  详情与历史页收到它（或 `QB_ATTEMPT_EXPIRED`）时重新读取，再提供"查看结果"。
- **本机验证**：`./gradlew :quizbuks:check` 通过；两个 SPA 的 lint / typecheck / test、`app:check`、`diff -r src/qb`、e2e 类型检查通过；e2e 全部 16 项（含 `play.spec` 的 3 项）
  对打包的 jar、Mailpit 与 Stripe 桩通过。

## 实施记录（QP3a，2026-10-14）

分支 `1.2/quizbuks-3p-content-pages`（从 `1.2/quizbuks` @ d6cb5b0 拉出，已含 QP1 与 PG-1）。只改 `.jabiz-app-paths` 中的路径，`tools/check-app-paths.sh` 通过；后端未改（所用的
流程、模板、数据视图、字典与文件策略都已在 Q3 / Q4 中存在，逐一核对过），`src/qb/` 未改。

- **页面**（`quizbuks-web/sponsor/src/content/`，左侧导航加"Quiz"）：
  - `/quizzes`（M-20）：模板 `qb.sponsor.quizzes`，`DataTable` 由服务端分页、排序（修改时间、标题、题数），按标题搜索（防抖）；列：封面缩略图、标题（链接到编辑页）、
    版本号与"之后有修改"、状态（字典 `quiz-status` 的服务端文案）与"已发布"、题数、资料数、最后修改；"新建 Quiz"只要标题（`QB_QUIZ_SAVE`），建好即进入编辑页。
  - `/quizzes/:id`：读取经商家数据视图（模版按主键 `GET …/entities/{id}`，不是本人的或已移除即 404，页面显示"不存在"；子项按 `quizId` 分页读全），四个标签页：
    - **Quiz**（M-21、M-22）：标题、简介（Markdown，`MarkdownView` 预览）、封面（`qb.content.image`）、时限（30–7 200 秒，非整数先在页面拒绝），一起 `QB_QUIZ_SAVE`。
    - **题目**（M-24）：按顺序的卡片（题干、配图、分值、选项与正确标记）；对话框中编辑一道题连同全部选项（2–6 个的提示、文字、配图、正确、上下移、移除），`QB_QUESTION_SAVE`；
      上移 / 下移以完整顺序 `QB_QUESTION_REORDER`，读回服务端的顺序后焦点回到被移动的题、`role="status"` 报告新位置；删除经确认 `QB_QUESTION_DELETE`。
    - **资料**（M-23）：同样的卡片与操作；新建时以单选组选类型（字典 `material-kind`，以后不可改），只发送该类型的内容字段：文章（Markdown 与预览）、链接与视频链接
      （只把 http(s) 地址做成链接，新标签页、`noopener noreferrer`；Q-3 不内嵌）、PDF（`qb.content.pdf`）、音频（`qb.content.audio`，下载）、图片组（`qb.content.image`，
      每张一条说明即替代文本，可排序，≤ 20）。
    - **版本**（M-27、M-28）：模板 `qb.sponsor.quiz-versions`（分页读全）；"查看"以 `qb.sponsor.quiz-version` 只读显示快照（含正确标记）；"复制"以 `QB_QUIZ_CLONE`
      （带 `versionNo`）生成新草稿并进入其编辑页（即"恢复旧版本"）。
    - 页头：状态、版本号、修改时间；"保存版本"（`QB_QUIZ_PUBLISH_VERSION`，被拒时一次列出服务端给出的全部原因，之后任何编辑都清除这份列表）、"复制"（草稿，可改标题）、
      "删除"（确认后 `QB_QUIZ_DELETE`，回到列表）。
  - **并发**：每次编辑都发送页面所见的 `revision` 作 `baseRevision`。`QB_QUIZ_CHANGED`、`QB_ORDER_MISMATCH`，或读回的 revision 比本页自己的编辑更新（别处改过、本页的表单没有它），
    都显示"已在别处修改"（取得焦点）并停用全部编辑按钮，"重新加载"后表单从新内容重新开始。
  - **文件**：上传只经 `@jabiz/client` 的 `uploadFile(策略)`；`FileUpload` 先按类型与大小挡一次（只为省往返），服务端按内容判断并重新编码图片；服务端的拒绝显示在该字段下。
    图片经 `FilePreview` 显示（缩略图、放大），PDF 与音频为下载按钮。
- **偏离计划**：
  - 内容工坊的名字与手写类型放在 `sponsor/src/content/ids.ts`、`api.ts`，不放共用的 `src/qb/`（只有商家后台用；也避免与 QP2 同时改共用目录）。
    `backend/quizbuks/CLAUDE.md` 第 5 节随之写明"只有一个 SPA 用到的功能"的放法。文案仍在命名空间 `sponsor` 中（`content/messages.ts` 由 `src/messages.ts` 合入）。
  - **图片的变体按文件自己的列表选**（`ContentImage`，先读 `GET /api/files/{id}`）：平台不生成比原图宽的变体，按策略声明的宽度取 `w320` 会对小图得 404。
    这是平台 `FilePreview` 的一般问题（`FilePreviewField.variants` 来自字段元数据而不是文件），可以以后在平台上改为按文件的变体；QuizBuks 现在自己先读一次。
  - 页面加了 `Toaster`（`notify` 的成功与失败提示）。
- **测试**：Vitest `content/api.test.ts`（草稿按顺序组装、移动、只接受 http(s) 的链接、过期判断、类型对应的内容字段、路径）与 `content/content.test.tsx`（列表与搜索、新建、
  标题保存带 `baseRevision`、Markdown 预览、时限的本地检查、冲突 → 重新加载 → 以新 revision 继续、题目顺序与移动、新题连同选项的顺序、资料只发送其类型的字段与安全链接、
  保存版本被拒的全部原因、不存在的 Quiz），共 16 个（商家后台合计 42 个）。
  e2e `content.spec.ts`（3 个：建 Quiz、简介预览、封面、被内容拒绝的假 PNG、两道带图题目、移动与焦点、四类资料（文章、图片组、PDF、链接）、v1.0 / v1.1、
  未改动再保存被拒、查看 v1.0、列表搜索；复制版本 → 删除副本 → 不存在、复制草稿；别处修改 → 提示 → 重新加载 → 继续保存、别人的 Quiz 不存在），
  数据准备 `content.ts`（`png()`、`PDF`、`sponsor()`、`upload()`、`query()`、`completeQuiz()`，QP3b 复用）；`a11y.spec.ts` 加"Quiz 页面"（列表、新建、编辑页四个标签、
  题目与资料对话框、版本查看、被拒与冲突、390 px），亮、暗都无违规。
- **未做**：发布（QP3b）；AI 生成（Q8，列表只显示标记）；音频不在页面中播放（默认 CSP 没有 `media-src blob:`，以下载代替）。

## 实施记录（QP4，2026-10-14）

分支 `1.2/quizbuks-6p-payment-pages`（从 `1.2/quizbuks` @ 8d2e42c 拉出，已含 QP1、QP2、QP3a）。只改 `.jabiz-app-paths` 中的路径；`src/qb/` 未改（两份仍相同）。
第 4 节 QP4 各行所用的流程、模板、数据视图与字典都已存在并照用：`QB_TOPUP_START`、`qb.sponsor.wallet`、`qb.sponsor.topups`、`urn:jabiz:dataset:sponsor:QbTopUp`、
字典 `topup-status`；`QB_PAYOUT_ONBOARD`、`qb.app.wallet`、`qb.app.ledger`、`qb.app.payout-account`、字典 `payout-account-status`。

- **功能目录**（照 QP3a 的放法，不进 `qb/`）：`sponsor/src/wallet/` 与 `app/src/wallet/`，各有 `ids.ts`、`api.ts`（手写类型与调用）、`messages.ts`（并入各自的命名空间
  `sponsor` / `app`，由页面自己引入，`src/messages.ts` 不改）；两处相同的 `poll.ts`（有界轮询）与 `stripePage.ts`（跳转前检查地址）各一份。
- **商家 `/wallet`**（M-04）：可用于奖励的金额（余额 − 争议冻结，同 `QbWallets.available`）、等待充值的奖励（提示按顺序补发）、余额、累计充值、已付奖励、退款与扣回；
  充值记录 `DataTable`（服务端分页，按开始时间、金额、到账时间排序；状态用服务端字典；可支付的一行为"继续支付"）。"充值"对话框只收 Kudos 的正整数，
  范围等由服务端检查并把 `QB_TOPUP_AMOUNT_RANGE` 等文案显示在字段下；同一金额的重试用同一个 `Idempotency-Key`。成功后进入充值页。左侧导航加"钱包"（在"商家资料"之后）。
- **商家 `/wallet/topups/:id`**：按主键读商家自己的 `QbTopUp`（`GET …/entities/{id}`；不是自己的即"不存在"）。
  - 刚在本标签页发起的（`sessionStorage` 中的一次性标记）：读到 `OPEN` 即跳转 Checkout，只一次；从列表或浏览器"后退"打开时显示"继续支付"按钮，不自动离开（避免后退循环）。
  - `?checkout=paid`：读到 `PAID` / `EXPIRED` / `FAILED` 为止，期间说明"等待 Stripe 确认"，不显示再次支付；**浏览器跳回不入账、不调用任何流程**。
  - `?checkout=cancelled`：Checkout 在到期前仍可支付，显示期限与"继续支付"；桩的"过期"在服务端记下 `EXPIRED` 后显示"已过期，未扣款"。
  - 轮询有界（`poll.ts`：等待 1、1、2、2、3 秒后每 5 秒；创建 60 秒、确认 90 秒、取消 30 秒），超时说明"稍后自动更新"并提供"再次查看"；离开页面即停止。
- **App `/wallet`**（U-50 – U-52）：Kudos、累计获得、等待商家支付（笔数与金额，说明扣除手续费）、转出中；收款账户的状态与入口；交易明细（`qb.app.ledger`，按 Quiz 搜索，
  全部 / 奖励 / 转出，分页"显示更多"，失败或撤回的转出说明金额已退回）。底部导航加回"钱包"（首页、记录、钱包、账户），收款页属于钱包。
- **App `/wallet/payout`**（U-53）：状态用服务端字典；"在 Stripe 开通 / 继续"先读当前的链接，再 `QB_PAYOUT_ONBOARD`，读到**新的**链接（与之前的地址或期限不同：
  Stripe 的链接只能用一次）后跳转；`?onboarding=done` 读到 Stripe 的报告（`ENABLED` / `RESTRICTED` / `DEAUTHORIZED`）为止，返回本身不改变任何状态；
  `?onboarding=refresh` 说明链接已过期或已用并提供新的；已解除关联的不能在此重新开通。
- **跳转地址的检查**（`stripePage.ts`）：只跟随绝对的 `https:` 地址（不含用户信息，≤ 2048 字符）；本地桩（开发与 e2e）的 `http:` 只在页面本身也在本机（`localhost`、
  `127.0.0.1`、`[::1]`）时接受，与服务端 `StripeSettings` / `PayoutSettings` 对前缀的规则一致。地址本来只来自本人的记录，且服务端写入前已按前缀核对；
  页面再查一次，任何读到的值都不能使它成为开放重定向或执行脚本。客户端没有任何密钥或桩的配置。
- **后端（唯一改动）**：`application.yml` 加 `quizbuks.stripe.checkout-url-prefix: ${QB_STRIPE_CHECKOUT_URL_PREFIX:https://checkout.stripe.com/}`，与 `connect-url-prefix`
  一致（QP2 记下的缺口）。缺省即 Stripe 自己的地址，行为不变；正式模式下 `StripeSettings.problems()` 仍拒绝 Stripe 以外的前缀，测试模式只接受 https 或本机地址。
  测试 `StripeAddressesConfigTest` 读 `application.yml`，断言两个前缀缺省为 Stripe、可由环境变量改指桩。`quizbuks.yml` 的 e2e 作业因此删去
  `QUIZBUKS_STRIPE_CHECKOUTURLPREFIX`（只留 `QB_STRIPE_CHECKOUT_URL_PREFIX`）；`deploy/quizbuks/README.md` 的变量表加上该变量。
- **测试**：Vitest 商家后台新增 `wallet/api.test.ts`（地址检查、金额解析、返回参数、可支付判断、轮询的退避、超时、失败重读与中止）、`wallet/wallet.test.tsx`
  （钱包的金额与列表、对话框的本地与服务端拒绝、发起 → 等待打开 → 只跳转一次、不安全的地址不跳转、再次打开只给按钮、返回"paid"只读不调用流程、超时后停止轮询、
  取消后可再支付、经浏览器缓存返回时重新给出按钮、过期、别人的充值）与 `wallet/messages.test.ts`，共 22 个（商家后台合计 68 个）；App 新增 `wallet/stripe.test.ts` 与 `wallet/wallet.test.tsx`
  （钱包与交易、筛选与搜索的请求、等待新的开通链接再跳转、不安全的链接、服务端拒绝、返回"done"等 Stripe 的报告、refresh、已解除），共 17 个（App 合计 82 个）。
  e2e `topup.spec.ts`（亮、暗各一次：钱包 → 充值对话框 → 桩的 Checkout → 支付 → 返回页显示已到账 → 钱包 1,500 Kudos；另一项：服务端拒绝金额、
  未支付即以"paid"返回不入账、从列表再次打开只给按钮、桩的"过期"→ 返回显示已过期、余额仍为 0）与 `payout.spec.ts`（亮、暗各一次：经接口得到一笔奖励 →
  钱包与交易 → 收款页 → 桩的开通页 → 启用 → 返回显示已开通；另一项：refresh → 新链接、未完成 → 再取新链接），数据准备在 `e2e/tests/wallet.ts`；每个新页面都过 axe（App 另查 1280 px）。
- **偏离计划**：
  - 充值页按主键读取（`GET …/entities/{id}`），不按 `topUpId` 筛选：`QbTopUp` 的列表视图不允许按主键筛选（平台 `FILTER_NOT_ALLOWED`），按主键读同样只给本人的记录。
  - "已配置的桩前缀"在客户端不另设配置：本机地址只在页面也在本机时接受（见上），生产构建与 e2e 构建相同。
  - 交易明细的金额以 +/− 区分奖励与转出；商家的交易明细（A-4）仍属 Q9。
- **本机验证**：`./gradlew :quizbuks:check` 通过；两个 SPA 的 lint / typecheck / test、`app:check`、`diff -r src/qb`、e2e 类型检查通过；e2e 全部 27 项
  （含 `topup.spec` 3 项、`payout.spec` 3 项）对打包的 jar、Mailpit 与 Stripe 桩通过，环境中不再设置 `QUIZBUKS_STRIPE_CHECKOUTURLPREFIX`。

### 评审后的修正（QP4，2026-10-14）

独立的安全评审没有严重或高的问题；四项低的问题已改：
- 刚发起充值的一次性标记在充值页挂载时读入状态并立即删除（不再等到读到非 `OPENING` 才删）：创建中离开、超时或不存在之后，从列表再打开只给按钮。
- 经浏览器缓存（bfcache）返回时重新读取：充值页开始新一轮轮询，收款页使收款账户的查询失效并重新等 Stripe 的报告。
- `poll.ts`（两份相同）：每次读取与剩余时间竞速（`within`，离开页面即放弃），挂起的读取不再拖过期限；`defaultSleep` 计时结束时移除中止监听。
- 充值对话框的"取消"与其他关闭方式走同一个重置（清空金额、错误与幂等键）。
测试：商家后台 Vitest 合计 73 个、App 合计 85 个（新增上述各项的用例）；e2e 全部 27 项再次通过。
