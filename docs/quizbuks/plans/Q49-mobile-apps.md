# Q49 上架 Google Play 与 App Store（规划，2026-10-28，待确认）

需求方（2026-10-28）：进入手机应用市场是最原始的需求；App Store 一定要上。部署已决定切到线 1.3，以后的开发与测试都在 1.3 上。
只上架**答题者的 App**（`/taker/`）；商家后台、招商网站、管理后台留在网页上。商家充值不能出现在 App 里（两个商店的内购规则）。

本文是规划：分哪些阶段、各在哪个分支、由哪个 session 做、先后与依赖。每个阶段开工前照常另写实施计划，经确认后实施（根目录 `CLAUDE.md` 第 7 节）。

## 1. 两个商店都要求的（先做）

| 要求 | 现状 | 由谁做 |
|---|---|---|
| **App 内删除账号**，并有一个不装 App 也能申请删除的**网页**（Apple 规则 5.1.1(v)；Google Play 的账号删除政策） | 没有；平台的数据只追加（D5） | 平台 17i + QuizBuks Q49a |
| 隐私政策的网址、Apple 的隐私标签、Google 的数据安全表 | 政策草稿已有（`legal/`），未发布 | 需求方（填占位、发布）+ Q49d |
| 有奖活动的**正式规则**，写明 Apple / Google 不是主办方（Apple 规则 5.3；Google 的竞赛政策） | 没有专门的规则页 | Q49a |
| 用户生成内容的**举报入口**（Apple 规则 1.2） | 管理员审核发布已有，App 中没有举报 | Q49a |
| 审核用的测试账号与一份能答的问卷 | — | 需求方 + Q49d |

## 2. 只有 App Store 要求的

| 要求 | 做法 | 由谁做 |
|---|---|---|
| 不能只是网站的外壳（规则 4.2） | 原生外壳加**推送通知**（新问卷、中奖、审核结果） | 平台 17k + Q49c |
| 有第三方登录就要有 **Sign in with Apple**（规则 4.8） | 平台 OIDC 加提供方 `kind: apple` | 平台 17j + Q49c |
| Google 禁止在 App 内嵌网页中登录（`disallowed_useragent`） | 第三方登录改用系统浏览器（`ASWebAuthenticationSession`），以 Universal Links 回到 App | 平台 17l + Q49c |
| 邮件中的链接（验证、邮件登录）直接打开 App | 域名上的 `apple-app-site-association`（Android 为 `assetlinks.json`） | 平台 17l（或 Caddy 站点）+ Q49b/c |

Android（TWA）运行的是真正的 Chrome：登录照常可用，不需要 Apple 登录与推送也能上架，所以 Android 可以先上。

## 3. 阶段

### 第 0 步：部署切到 1.3（先于一切，约半天）
`1.3/quizbuks` 上的 `.github/workflows/quizbuks-deploy.yml` 仍只认 `1.2/quizbuks` / `1.2/quizbuks-release`：
- 改为 `1.3/quizbuks`（staging）与 `1.3/quizbuks-release`（production）；
- 在 1.2 上停掉自动部署（只留手动），1.2 只作备份；
- 03-plan 写明"修复只做在 1.3"；
- staging 的 `.env` 按 1.3 的 `operations.md` 补齐变量（`SHOPIFY_API_KEY` / `SHOPIFY_API_SECRET` 等）；
- 第一次部署 1.3 前重置 staging（DEPLOY.md"重置 staging"）。1.3 迁移过的数据库不能再跑 1.2。

### 平台（`1.3/platform`，各自写进 ROADMAP 并有决策）

| 阶段 | 内容 | 估计 |
|---|---|---|
| **17i 账号删除与个人信息抹除**（新决策，涉及 D5） | 本人申请删除（App 内、以及凭邮件确认的网页申请）→ 冷静期（例如 14 天，期间登录即撤销）→ 定时任务执行：吊销会话、删除二次验证与外部账号关联、账号停用；个人字段按声明抹除（只追加表中的个人字段由平台专用路径改写为抹除标记，审计中只留"已抹除"，封存链重新封存）；法定保存的记录（账本、收据、单据）只保留编号、不留姓名；应用以 `PersonalDataEraser` Bean 抹除自己的表；删除后同一邮箱可以重新注册 | 6–8 天 |
| **17j Sign in with Apple** | OIDC 提供方 `kind: apple`：客户端密钥为以私钥签名的 JWT（ES256，私钥只来自环境变量）、`form_post` 回传、名字与邮箱只在首次给出、接受 Apple 的转发邮箱；开户照 D39 / D61 | 2–3 天 |
| **17k 推送通知** | 设备登记（本人登录后登记设备令牌，退出与删除账号时清除）；APNs（iOS，令牌认证，密钥只来自环境变量）与 FCM 或 Web Push（Android / 网页）；以通知积木发送（与收件箱、待办通知同一入口），收件偏好可关闭；投递失败的令牌自动失效 | 4–5 天 |
| **17l 原生 App 的接入** | 单页应用声明关联的原生 App（`jabiz.web.spa[i].native-apps`），平台在域名根提供 `/.well-known/apple-app-site-association` 与 `/.well-known/assetlinks.json`；OIDC 回调以 Universal Link 回到 App（系统浏览器登录）；`Pwa` 在原生外壳中不显示安装提示 | 2 天 |

### QuizBuks（`1.3/quizbuks`）

| 阶段 | 内容 | 依赖 | 估计 |
|---|---|---|---|
| **Q49a 删除账号与商店规则所需的页面** | App 账户页"删除账号"；网页申请页；QuizBuks 的 `PersonalDataEraser`（资料、"关于我"、收款账户、邀请邮箱、店员关系等；余额为正时先提示提现或放弃）；有奖活动的规则页（三语）；发布页的"举报"；隐私政策补"删除账号"一节 | 17i | 3–4 天 |
| **Q49b Android（TWA）** | 新目录 `quizbuks-mobile/android`（Bubblewrap 配置、签名用 Play App Signing、`assetlinks.json`）；在 App 中隐藏 Q38 的安装提示；构建与上传的 workflow | 17l（或先以 Caddy 提供 `assetlinks.json`） | 2–3 天 |
| **Q49c iOS（Capacitor）** | 新目录 `quizbuks-mobile/ios`：外壳加载 `/taker/`、推送登记、Universal Links、系统浏览器登录、Apple 登录按钮；GitHub Actions 的 macOS 运行器构建并以 App Store Connect API 密钥上传到 TestFlight（不需要本地 Mac） | 17j、17k、17l | 5–7 天 |
| **Q49d 上架资料** | 三语的商店介绍、截图、图标；Apple 隐私标签与 Google 数据安全表（按隐私政策逐项）；内容分级；审核账号与说明 | Q49a–c | 2 天 + 需求方 |

### 需求方要做的（可以现在就开始）

1. **Google Play Console**（$25）：新的个人开发者账号须先做**封闭测试：至少 12 名测试者、连续 14 天**，之后才能申请正式上架。现在就找测试者。
2. **Apple Developer Program**（每年 $99）：以个人注册，商店的卖家显示本名；以公司注册需要法人与 D-U-N-S 编号。
3. 发布隐私政策：填好【】占位，在后台发布 1.3 的版本。
4. 在 Apple 后台建 App ID、"Sign in with Apple"的 Services ID 与密钥、APNs 密钥；在 Firebase 建项目（如果用 FCM）。

## 4. 顺序与时间（约 5–6 周）

```
第 1 周   第0步部署切换 │ 17i 账号删除 ───────────────┐
第 2 周              │ 17i（续）→ Q49a ─────────────┤   Q49b Android（并行，先用 Caddy 的 assetlinks）
第 3 周   17j Apple 登录 → 17l 原生接入 │               └→ Android 封闭测试 14 天开始
第 4 周   17k 推送                     │  Q49c iOS 外壳（并行）
第 5 周   Q49c 完成 → TestFlight → Q49d 上架资料
第 6 周   提交审核（Apple 与 Google），按审核意见修改
```

Android 可以在第 3 周左右提交封闭测试，14 天后正式上架；iOS 约在第 6 周提交审核。

## 5. 由哪个 session 做

两条线可以并行，按目录分开，避免互相冲突：

| Session | 负责 | 分支 | 理由 |
|---|---|---|---|
| **A（平台与后端，建议本 session 或一个新的 session 接手）** | 第 0 步、17i → 17j → 17l → 17k（都在 `1.3/platform`，各自同步到 `1.3/quizbuks`），以及依赖它们的 Q49a | `1.3/phase-17i-…`、`1.3/quizbuks-49a-…` | 平台的认证、OIDC（16zk 的 Facebook/LINE 就是这样做的）、只追加与审计封存都在这条线，改动要和决策一起推进，一次只做一个平台阶段 |
| **B（原生外壳与上架，新的 session）** | Q49b、Q49c、Q49d：新目录 `quizbuks-mobile/`，以及 App 前端中与外壳相关的少量改动 | `1.3/quizbuks-49b-android`、`1.3/quizbuks-49c-ios`、`1.3/quizbuks-49d-store` | 几乎都是新文件，与 A 的平台工作不冲突；Android 不依赖平台可以先做；iOS 等 17j、17k、17l 合入后接上 |

协调规则：
- 平台阶段合入 `1.3/platform` 后，A 立即同步到 `1.3/quizbuks`，B 在自己的分支上合并新的 `1.3/quizbuks`。只合并，不变基、不拣选。
- `quizbuks-mobile/**` 加入 `.jabiz-app-paths`（B 的第一个 PR）。
- App 前端 `quizbuks-web/app` 两边都可能改：A 改账户页的删除入口，B 改外壳检测与安装提示。改动保持在各自的文件里；同一文件有冲突时以先合入的为准，后合入的照两边的意图合起来。

## 6. 风险

- **账号删除与只追加（D5）**：最大的设计决定。需要明确哪些字段算个人信息、抹除后审计与封存怎样保持可验证、法定记录保留什么。17i 的计划先给出决策草案，经确认后实施。
- **Apple 审核的不确定性**：规则 4.2（只是网站的外壳）与 5.3（有奖活动）可能被要求补充说明或修改；预留一到两轮审核往返。
- **iOS 的 Web Push 与原生推送**：外壳中用原生 APNs，不依赖 iOS 的 Web Push。
- **中国大陆不上架**（需求方 2026-10-28，`03-plan.md` 第 6 节）：中国大陆只用网站与 PWA；App Store 的销售地区排除中国大陆（在那里上架须有 ICP 备案号），不上中国的应用商店；Google Play 在中国大陆本来就不可用。
- 品牌与运营商模式（Q48）不在本规划内；白标暂不做。
