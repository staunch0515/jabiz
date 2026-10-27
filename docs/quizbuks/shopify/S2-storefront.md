# S2：店面答题领折扣码（计划已确认；S2a 已实施）

依据：`00-overview.md` §4 的 S2 行与 §5 的问题清单；平台能力来自 17d（应用代理入口与店面限流，决策 D60）。线 1.3（`1.3/quizbuks`）。
需求方 2026-10-27 已定（"默认建议"）：只做商品页的 App block；每个访客在每家店、每个答题只领一次；每店每日最多发 100 个码；题目先手工录入，AI 以后再做；
折扣为百分比、全店通用、有效 7 天。

分两个 PR：**S2a** 做后端（引擎的店铺流程、活动、码池、店面接口、顾客数据的合规），**S2b** 做界面（内嵌后台的活动编辑、Theme App Extension）。

**确认（需求方 2026-10-27，"默认建议，开始"）**：§7 的五个问题都按"建议"一栏；S2a 实施中与计划不同之处见 §8。

## 1. 引擎边界：答题归引擎，发码归 Shopify 模块

调查结论：引擎今天的每个商户流程都以操作人为发布方（数据视图固定 `publisherKind = SPONSOR`、`ownerId = actorId`、提交须经商户审核与条款），
答题流程以操作人为答题人、要求已验证邮箱。店面的调用者是代理身份 `app-proxy:shopify`（17d，所有店铺的访客共用），所以引擎要有**以显式发布方与答题人运行**的流程，只作子流程。

| # | 决定 |
|---|---|
| S2-1 | 引擎新增 `subProcessOnly()` 的店铺流程（`:quizbuks` 的 `publishing.ChannelProcesses` 与 `play.ChannelPlay`），输入里显式给出 `publisherKind = SHOPIFY_SHOP`、`publisherId = <店铺域名>` 与答题人键；只有 Shopify 模块的流程调用。普通商户的流程、数据视图、审核与钱包**一行不改** |
| S2-2 | 店铺的发布**不经审核、不经钱包**：店主在自己的店面上展示自己的内容，平台不代付任何钱。新的奖励方式 `CHANNEL`（"由发布方的渠道发放"）：引擎只判分、记下是否通过，不结算；`CHANNEL` 只允许 `SHOPIFY_SHOP` 的发布使用（引擎检查） |
| S2-3 | 内容（题目、视频链接）照用引擎的实体，`ownerId = shopify:<店铺域名>`；店铺的内容流程同为子流程，复用 `QuestionInput` 等校验。视频只认 YouTube / Vimeo 链接（与引擎相同，不存视频文件） |
| S2-4 | 答题人键：已登录的顾客为 `qbs:<店铺>:c:<顾客编号>`（来自签名的 `logged_in_customer_id`），匿名访客为 `qbs:<店铺>:v:<访客键>`（店面组件在 `localStorage` 中生成的随机键）。引擎的 `UNIQUE(user_id, publication_id)` 即"每个访客、每家店、每个答题只领一次"。**访客键可以随意换**：真正的上限是每店每日 100 个码与限流（§3） |

## 2. Shopify 模块：活动与码池（S2a）

- **活动**（`QbsCampaign`，普通表）：店铺、引擎的发布编号、折扣百分比（1–90 的整数）、状态 `DRAFT` / `LIVE` / `PAUSED` / `ENDED`。**每店同时至多一个 `LIVE`**（部分唯一索引）：
  商品页的 App block 不用选活动，零配置。流程 `QBS_CAMPAIGN_SAVE` / `_PUBLISH` / `_PAUSE` / `_END`（店铺成员，`VerifyExternalToken` 核对会话令牌，取店铺锁）。
- **折扣码池**（`QbsDiscountBatch`、`QbsDiscountCode`，普通表）：
  - 一批 = Shopify 的一个折扣（`discountCodeBasicCreate`：百分比、全店、`appliesOncePerCustomer`、`endsAt` = 建批时刻 + 14 天）加至多 250 个码（`discountRedeemCodeBulkAdd`）。
  - 码由我们生成（`QB-` + 10 位不含易混字符的随机码），以 `SealSecret`（上下文为店铺域名）密封保存，同引擎码池。
  - 批量添加是异步的：先存为 `PENDING` 并记下 Shopify 的批量作业编号，补码任务下一轮查询完成情况，成功的码转为 `AVAILABLE`、失败的丢弃。
  - **只发还剩至少 7 天的码**：活动的有效期就是"领到后至少 7 天"。一批过了 `endsAt − 7 天` 即不再发，剩下的码由补码任务从 Shopify 删除（`discountCodeDelete` 删整个折扣）。
  - 补码任务 `qbs.discount-refill`（`JobDefinition.forEach`，每家有 `LIVE` 活动的店一项，`internal()`），每 10 分钟一次：可用码少于 50 即建新批。外部调用经 S1 的访问令牌与刷新（同一锁内）。
  - 改折扣百分比 = 暂停旧批、建新批（Shopify 上的折扣不改，避免已发出的码改价）。
- **每日上限**：`QbsDailyIssue`（店铺、UTC 日期、已发个数），在发码的同一事务中按店铺锁加一；到 100 即拒绝（店面显示"今天的奖品发完了"）。上限是 `QBS_DAILY_CODE_CAP`，列入 `ControlledParams`。
- **卸载**：`app/uninstalled` 把活动置为 `PAUSED`，补码任务跳过；Shopify 会删除应用建的折扣，所以不另去删。

## 3. 店面接口（S2a，17d 的应用代理）

代理 `jabiz.app-proxies.shopify`（密钥 `SHOPIFY_API_SECRET`，租户 `*.myshopify.com`）；Shopify 的 App Proxy 设为 `/apps/quizbuks` → `https://<地址>/api/proxy/shopify`。

| 流程（`appProxy("shopify")`） | 做什么 |
|---|---|
| `QBS_SF_CAMPAIGN`（GET） | 本店 `LIVE` 的活动：视频播放地址、题目数、时限、折扣百分比、今天是否还有码；没有活动即空（组件不显示） |
| `QBS_SF_START`（POST） | 开始或继续答题（引擎 `ChannelPlay` 的开始），返回题目与选项（不含答案） |
| `QBS_SF_SUBMIT`（POST） | 交卷 → 引擎判分 → 通过且今天未满 100 且池中有码即发一个码（店铺锁内：每日计数加一、码转 `ISSUED` 并记答题编号），答复中**直接给出码**与到期日；同一答题再交即返回同一个码（幂等） |

- 限流（`jabiz.process.rate-limits`）：`QBS_SF_START` 每访客 10 次/小时、每店 600 次/分钟；`QBS_SF_SUBMIT` 每访客 10 次/小时、每店 300 次/分钟（数字以 S2a 的压测与开发店铺的流量再调）。
  按地址的限流不做：17d 未核实 Shopify 是否转发顾客地址（10 §15.13）。
- 防机器人：不接验证码服务。现有的是答题时限下限（服务端拒绝开始后 3 秒内的交卷）、每日上限与限流；损失上限即每天 100 个折扣码。
- 语言：店面按顾客的语言（代理请求带 `Accept-Language`，三种语言），后台按店主。

## 4. 顾客数据的合规（S2a）

S2 起有了顾客数据：答题记录中的顾客编号与访客键。

- `customers/data_request`：登记该顾客（`customer.id`）在本店的答题与领到的码（结论 `REPORTED`，内容供店主在后台查看，S2 不另发邮件）。
- `customers/redact`：把该顾客的答题人键改写为不可逆的 `qbs:<店铺>:r:<随机>`（引擎的答题是普通表，经子流程改写），码保留但不再关联顾客。结论 `REDACTED`。
- `shop/redact`：除 S1 已删除的，再删本店的活动、码池、每日计数；引擎中本店的答题记录按上条匿名化。**发布与题目是只追加表，删不掉**，见 §7 Q-S2-2。

## 5. 界面（S2b）

- **内嵌后台**（`quizbuks-web/shopify`）：活动页（视频链接、题目与选项、及格分、时限、折扣百分比），发布 / 暂停 / 结束；今日已发、池中可用、近 7 天领取数。只用 `@jabiz/ui`（Polaris 风格在 S4）。
- **Theme App Extension**（`quizbuks-web/shopify-extension/`，Shopify CLI 的工程结构，`shopify app deploy` 由需求方以自己的 Partner 账号执行）：
  - App block `quiz`（只限商品页模板），Liquid 只输出一个占位元素与按钮；脚本为原生 Web Component（不用 React），压缩后 ≤ 15 KB、`defer` 加载。
  - 视频为点击后才加载的外观图（不在首屏加载 YouTube / Vimeo 的播放器），不影响 Lighthouse。
  - 流程：看视频 → 答题（计时）→ 结果；通过即显示码、"复制"与"去使用"（`/discount/<码>?redirect=<当前商品页>`）。领到的码存在 `localStorage`，再次打开直接显示。
- 测试：后台 Vitest；组件以 Vitest + jsdom 测流程与三语文字，Playwright 以本地桩页面加载组件过 axe。真实店面的联调在开发店铺上手工进行（清单写进 S2b 的 PR）。

## 6. 测试（S2a）

- 引擎：`ChannelPlay` 的单元与集成测试（显式答题人、`CHANNEL` 不结算、普通商户的流程看不到店铺的发布）。
- `ShopifyStorefrontIT`（真实 PostgreSQL、本地桩回答 Shopify 的 GraphQL）：签名的店面请求开始、交卷、领码；同一访客再交得到同一个码；同一访客再开始被拒；
  每日第 101 个被拒；池空时通过但不发码；只发还剩 ≥7 天的码；补码（建批、异步完成、失败丢弃、过期批删除）；卸载后活动暂停；三个合规主题；限流 429。
- 场景回放 `scenarios/qbs/storefront.yml`：活动、答题、发码、每日上限（以场景的代理步骤运行，17d 已支持）。

## 7. 待需求方确认

| # | 问题 | 建议 |
|---|---|---|
| Q-S2-1 | §1–§5 的整体方案（引擎只判分、发码在 Shopify 模块；每店一个进行中的活动） | 按此做 |
| Q-S2-2 | `shop/redact` 时引擎里本店的**发布与题目**（只追加表）删不掉：只能保留、或以墓碑标为已删除 | 标为已删除并在内容上写墓碑（题目文字清空的新版本）；真正抹去与 S1 留下的"平台账户删除"一起作为平台阶段再议 |
| Q-S2-3 | "有效 7 天"实现为"领到后**至少** 7 天、至多 14 天"（预生成池的码共用一个折扣的结束时刻） | 同意；要精确 7 天只能领取时现建折扣，是 §6 明确不做的 |
| Q-S2-4 | Shopify 批量码的 `usageLimit` 是否按每个码计，要在开发店铺上核实（shopify.dev 在本环境不可达）；若按整个折扣计，改为每批 `usageLimit = 码数` 加 `appliesOncePerCustomer` | 联调时核实，S2a 先按"每个码各一次"写，结论写进实施说明 |
| Q-S2-5 | 联调需要：开发店铺（有商品）、Partner 账号下的应用、公网地址；App Proxy 子路径 `/apps/quizbuks` | 请准备；S2a 不需要它们就能完成并通过测试 |

估计：S2a 约 2.5 天，S2b 约 2 天。

## 8. S2a 的实施（与计划不同之处）

| # | 计划 | 实施 | 为什么 |
|---|---|---|---|
| I-1 | Shopify 模块"以流程名调用"引擎的流程 | 新模块 **`backend/quizbuks-channel`**（`com.jabiz.quizbuks.channel.Channel`）只放四个流程的名字、输入输出记录与常量；引擎实现（`publishing.ChannelPublishing`、`play.ChannelPlay`，`subProcessOnly()`），Shopify 模块以这些记录调用。路径已加入 `.jabiz-app-paths` | 平台的 `CallProcess` 要求输入是被调用流程自己的类型；Shopify 模块仍不依赖引擎（编译期保证不碰钱包与普通商户表） |
| I-2 | 匿名访客为 `qbs:<店铺>:v:<访客键>`，顾客为 `qbs:<店铺>:c:<顾客编号>` | 匿名访客：`qbs:v:` + SHA-256(店铺地址, 访客键) 的 base64url，**不建行**；已登录顾客：`QbsTaker` 一行（店铺、顾客编号），引擎只知道 `qbs:<行的编号>` | 引擎的答题人不可改（`userId` immutable）：`customers/redact` 只删这一行，引擎里剩下的键就不再指向任何人；访客不会有 Shopify 的数据请求，也不需要行（`shop/redact` 不必删海量访客行） |
| I-3 | 每日上限一张表 `QbsDailyIssue` | 不另建表：在店铺的码锁 `qbs.shop-codes:<店铺>` 内数当天（UTC）已发的码（模板 `qbs.system.pool-stats`） | 少一张表，计数就是事实本身 |
| I-4 | 码的状态 PENDING / AVAILABLE / ISSUED / FAILED / RETIRED | 码只有 AVAILABLE / ISSUED / FAILED；能否发由**批次**决定（`READY` 且剩余 > 7 天）；批次到期后连同它的码从 Shopify 与本地删除（批次行留作 `DELETED`） | 补码时不必逐行改 250 个码；一家店只留约两周的码，`shop/redact` 一次删得完 |
| I-5 | 引擎新奖励方式 `CHANNEL` | 照做；`RewardMethod.ALL`（商户可被允许的）不含它，实体与字典用 `RewardMethod.CODES`；迁移 V33 只改 CHECK | 管理员不能把 `CHANNEL` 授给普通商户 |
| I-6 | — | 店铺的发布**一建即 `LIVE`**：`QbPublication` 的初始状态改为 `DRAFT` 或 `LIVE`；结束原因新增 `PUBLISHER`（发布方结束，不发通知） | 没有草稿与审核；普通商户的流程照旧从 `DRAFT` 开始 |
| I-7 | — | 店面交卷不早于开始后 3 秒（`PlayLimits.CHANNEL_MIN_ANSWER`，422 `QB_ATTEMPT_TOO_FAST`）在引擎的 `QB_CHANNEL_ATTEMPT_SUBMIT` 中检查 | 计划 §3 的"防机器人"一条，放在读到开始时刻的地方 |
| I-8 | `QBS_DAILY_CODE_CAP` 列入受控参数 | 参数键 `qbs.storefront.daily-codes`，列入受控参数（`ControlledParams`，改动须四眼），但**不由 `QB_SETUP` 建立**：店面以可选参数读取，未建立时按 100；要改时管理员以受控变更建立它（平台允许受控参数以后再建，`ParamChecks`） | `QB_SETUP` 多一个参数会使它之后发出的全部编号后移，所有场景的选项顺序随之改变 |
| I-9 | `shop/redact` 时把发布与题目"标为已删除并写墓碑" | 本店的活动、顾客行、批次与码全部删除；仍在进行（卸载时已暂停）的发布在引擎中**结束**。引擎的题目版本是只写一次的只追加表（`writeOnce`），不能再写新版本，所以**没有墓碑**；题目原文留在版本中，与 S1 的"平台账户删除"一起作为平台阶段再议 | 平台规则：只写一次的实体不能更新 |
| I-10 | — | 店面流程**不声明排队**（`admission`）：每店的限流（`per-tenant`）已限住并发；引擎的子流程照常取发布锁与开始锁。锁顺序：店铺锁 → 令牌锁 → 引擎发布锁（开始锁）→ 店铺码锁 | 根流程的排队族名须与其最外层锁一致，而最外层锁在引擎的子流程中；压测后再定 |
| I-11 | 补码任务处理"建批、异步完成、失败丢弃、过期批删除" | 照做，外加：批次剩 ≤ 7 天即 `RETIRED`（不再发其中的码）；店铺卸载或未连接时，过期批次只在本地删除 | 已发出的码在批次结束前仍要能用，所以退役不删折扣，过期后才删 |

**Shopify 的 GraphQL**（`ShopifyApi.createBatch` / `bulkStatus` / `deleteDiscount`）按 Admin API 的折扣文档写成（`discountCodeBasicCreate` 的 `context: {all: ALL}`、`customerGets`、`appliesOncePerCustomer`、`usageLimit: 1`；`discountRedeemCodeBulkAdd`；`discountRedeemCodeBulkCreation`；`discountCodeDelete`）。本环境访问不到 shopify.dev，**第一次在开发店铺上联调时核实**（Q-S2-4 与字段名）；测试中由本机桩回答。
应用配置模板 `tools/quizbuks/shopify/shopify.app.toml` 加了 `read_discounts,write_discounts` 与 App Proxy（`/apps/quizbuks` → `https://<地址>/api/proxy/shopify`）。

**已知问题**：
- 店铺的发布在引擎中至多进行 366 天（`PublishingLimits.MAX_RUNNING`）；到期后引擎把它结束，活动行仍显示 `LIVE`，店面开始答题得 422 `QB_PUBLICATION_NOT_OPEN`。商户结束它再发布一个即可；S2b 的后台会显示引擎中的状态。
- 店面请求中查询参数 `visitor`（按访客限流用）与正文的访客键可以不同：换查询参数绕得过按访客的限流，绕不过按店铺的限流与每日上限（17d 已记）。

**测试**：`ShopifyStorefrontIT`（8 例：发码与只发一次、未通过与交得太快、每日上限与次日恢复、已登录顾客与访客键、跨店与未签名、商户的活动管理、补码的退役与删除、卸载暂停与三个合规主题）；`StorefrontKeysTest`；S1 的 `ShopifyIT` 照旧通过。场景回放不另加：场景执行器不能以会话令牌登录，建不了店铺成员与活动（同 S1）。
