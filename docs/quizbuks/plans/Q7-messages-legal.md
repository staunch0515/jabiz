# Q7 消息与内容：邮件通知、条款与帮助、站内信与公告（计划，2026-10-18 确认）

依据：`03-plan.md`（Q7：U-60 – U-63、U-72 – U-74、M-60 – M-61、P-04 – P-05，"等 16a"）、`01-requirements-analysis.md` 2.5、`02-design.md` 3.5 / 6 / 7、
需求原文 3.7、3.8、4.7、4.8、5.2，以及 Q2、Q5、Q6、QP 计划中留给 Q7 的事项。基于 `origin/1.2/quizbuks` @ 834d042（`1.2/platform` 已全部合入）。
只改 `.jabiz-app-paths` 中的路径；平台需要的一项（16s）先在 `1.2/platform` 上做（根 CLAUDE.md 第 8 节）。

## 1. 目标与范围

| 需求 | 内容 | Q7 的做法 | 子阶段 |
|---|---|---|---|
| P-08 / M-72（部分）、设计 8 "转账结果" | 邮件通知、通知偏好 | 新增事务邮件模板（转账、充值到账、发布审核结果、下架、预算不足、已结束）；可退订的通知类按用户与模板退订（平台 D35）；账户页"邮件通知"开关 | Q7a |
| U-73 隐私政策；P-05 隐私政策、服务条款；Q2-6 条款版本 | 静态文本页，内容由后台维护；同意记录 | 条款与隐私政策**按版本发布**（只写一次），可预定生效时间；同意按版本记录；条款有须重新同意的新版时，App 与商家后台在登录后要求重新同意，资金与合约类写入由服务端再查 | Q7b |
| U-72 帮助中心；P-05 FAQ（账号 / 提现 / 答题规则） | 分类 FAQ、搜索、"仍需帮助"联系入口 | FAQ 条目（多语言 Markdown）由内容管理员在通用后台维护；匿名可读的公开模板；App 与商家后台各自的帮助页 | Q7b |
| U-74 App 信息与退出 | 版本号、更新日期、退出登录 | App 的"关于"页：构建时写入的版本与日期、条款与隐私链接、退出 | Q7b |
| U-60 – U-63；U-34 "同步生成消息" | 消息列表（系统 / 推广 / 奖励 / 提醒）、未读数、已读、搜索、详情、跳转发布 | 个人消息由事件生成（Q5 的 `qb.reward.*`、Q6 的 `qb.transfer.*`）；公告不按人复制；App 底部第 5 项"收件箱"带未读数 | Q7c |
| M-60 商家通知 | 标题、正文、目标地区、关联一个进行中的发布；草稿或定时发送 | 商家消息中心：草稿、定时、发送、撤回；只能关联本人的发布，地区为该发布地区的子集 | Q7c |
| M-61 效果统计 | 阅读数 / 阅读率、点击数 / 转化率 | 发送时记下匹配人数；阅读、点击按人记一次；转化 = 点击后开始该发布的人数 | Q7c |
| P-04 平台公告 | 全体、指定地区、指定用户；系统公告、个人通知、营销 | 后台扩展页 `/qb/broadcasts`：撰写、预览、定时、统计、撤回（含撤回商家通知） | Q7c |

**不做**：移动推送 / Web Push（需求未要求，16c-9 不做推送）；邮件群发公告（公告只在站内，见 Q7-12）；M-71 偏好（P2）；M-72 的"财务报表订阅"（见 Q7-12）；
M-73 API Key（G10）；个人信息的删除与匿名化（平台未开放，GDPR 风险照旧，分析 4.6）。

## 2. 现状（`origin/1.2/quizbuks`）

- **平台 16a（G3 事务邮件，D35）已合入**：`MailTemplate` Bean + 消息 `mail.<名>.subject|body`、`SendMail`、Outbox 投递与重试、按收件人语言、`NOTIFICATION` 可退订
  （签名链接、`SecUserMailPreference`、`GET/POST /api/auth/mail/preferences`）、`{entryUrl.<入口>}`。16b、16c（`@jabiz/ui` 积木、PWA）、16n（`MarkdownView`）、
  15（shadcn）、16k（逐项任务）、16l（`internal()`）也都已合入。公开访问（15、D17）在平台中已有，QuizBuks 尚未开启（`jabiz.public.enabled` 缺省关）。
- **已有的应用邮件**：`identity.AccountMails`——`qb.welcome`（NOTIFICATION）、`qb.sponsor.approved` / `qb.sponsor.rejected`（TRANSACTIONAL）；
  退订页借用后台的 `/admin/mail/unsubscribe`（`application.yml`）。
- **已为 Q7 发布的事件**：`qb.reward.settled`、`qb.reward.pending`（`AttemptFinishing`、`PendingSettlement`，载荷 `attemptId`、`userId`、`publicationId`、`reward`）、
  `qb.topup.paid`（`topUpId`、`sponsorId`、`amount`）、`qb.transfer.sent`、`qb.transfer.failed`（`QbPayouts`）。**发布的状态变化没有事件**。
- **条款**：商家入驻 `QB_SPONSOR_APPLY` 要求 `acceptTerms`，只记 `QbSponsor.termsAcceptedAt`（Q2-6："版本随 Q7，届时加 `termsVersion`"）；页面上的条款文字只是一句复选框文案，
  没有条款正文。**答题人注册不记任何同意**（平台 `SignUpForm` 的 `extra` 字段不发送到服务端）。隐私、条款、FAQ 页面都不存在；上线清单把"隐私政策、利用条款已发布"列为必需。
- **权限已预留**：`qb.broadcast.write`（商家）、`qb.admin.content.write`（内容管理员，"以后 FAQ、条款、公告"）、`qb.me`。
- **包** `messaging` 在命名约定中已预留，尚无代码。最新应用迁移 **V11**，下一个是 **V12**。
- 前端：App 底部导航 4 项（首页、历史、钱包、账户），QP Q-8 已定 Q7 加第 5 项"收件箱"；QP 计划的 PG-3（"`@jabiz/ui` 的收件偏好积木"）留给 Q7。
  `@jabiz/client` 没有收件偏好与邮件语言的封装（只有生成的类型），通用后台也没有偏好页（16a 写"留给 15c / 16c"，未做）。

## 3. 平台缺口

（平台编号 16r 与决策 D48 已被进行中的 `1.2/phase-16r-controls` 使用，本计划的平台阶段暂编 16s、可能的新决策暂编 D49，开工时以路线图为准。）

| # | 缺口 | 影响 | 处理 |
|---|---|---|---|
| P1 | 收件偏好没有积木与封装：`GET /api/auth/mail/preferences` 返回**全部** NOTIFICATION 模板（答题人会看到"发布预算不足"这类商家邮件），只有模板名、没有显示名；`@jabiz/client` 无 `mailPreferences` / `setMailPreference` / `setMailLocale`；切换界面语言不改邮件语言（`POST /api/auth/account/locale` 无人调用） | M-72 的偏好页、语言一致 | **平台 16s**（建议，见 Q7-13）：`MailTemplate.define(...).entries("app", …)` 声明适用的登录入口（缺省全部），偏好接口只列调用者入口的模板；显示名消息 `mail.<名>.name`（启动检查 `MAIL` 要求每种语言都有）；`@jabiz/client` 三个函数；`@jabiz/ui` 积木 `MailPreferences`（开关列表）；通用后台账户页用它；示范门户。约 1–1.5 天，分支 `1.2/phase-16s-mail-preferences`；是否需新决策：作为 D35 的实现说明（不取代），若评审认为"按入口过滤"属于约定则新增 D49 |
| P2 | 退订页只在通用后台（`/admin/mail/unsubscribe`） | 答题人点退订会看到后台外观的页面 | 不做：功能正常。以后可把 `MailUnsubscribePage` 移入 `@jabiz/ui` 并按入口给出退订路径 |
| P3 | 没有群发邮件的能力（百万收件人经 `SendMail` 逐条写 Outbox，SMTP 在投递事务中） | 条款变更若要邮件通知全体 | 不做：Q7-12 建议站内 + 登录时重新同意，不群发 |
| P4 | `SignUpForm` 的 `extra` 字段不发送 | 无法在注册的同一请求中记同意 | 不改平台：答题人在 `/welcome`（注册第二步，Google 注册同样经过）同意（Q7-3） |

不需要平台新能力的：公开模板与公开视图（15）、I18nText 与 Markdown（16、16n）、`actsOn`、逐项任务、事件订阅、`SendMail`、受控参数。

## 4. 拆分、分支与顺序

| 子阶段 | 分支 | 内容 | 迁移 | 预估 | 依赖 |
|---|---|---|---|---|---|
| 平台 16s | `1.2/phase-16s-mail-preferences` | P1 | 平台无表变化 | 1–1.5 天 | — |
| **Q7a** 邮件通知（MVP 的一部分） | `1.2/quizbuks-7a-mail` | 发布状态事件、9 个新邮件模板、提醒的节流、偏好页 | V12 | 1.5–2 天 | 16a（已合入）；偏好页需 16s 合入（先做后端，合并 16s 后补页面） |
| **Q7b** 条款、隐私与帮助（上线必需） | `1.2/quizbuks-7b-legal-help` | 版本化的法律文本、同意记录与重新同意、FAQ、公开模板、App 与商家后台的帮助与条款页、App"关于"页 | V13 | 2.5–3 天 | 无 |
| **Q7c** 站内信与公告 | `1.2/quizbuks-7c-inbox` | 个人消息、公告与商家通知、定时发送、已读与点击、统计；App 收件箱；商家消息中心；后台扩展的公告页 | V14 | 3.5–4 天 | Q7b（条款更新公告）可选 |

合计应用 7.5–9 天（`03-plan.md` 原估 4–5 天：原估未含版本化条款与重新同意、商家通知的限制与撤回、后台扩展页、统计与 e2e；见第 10 节），平台 1–1.5 天。
迁移号按**合并顺序**取下一个（上表按建议的顺序：Q7a → Q7b → Q7c；Q7b 可与 Q7a 并行，谁先合并谁用 V12）。

## 5. Q7a 邮件通知

### D-Q7-1 哪些邮件（需求方确认，见 Q7-12）

| 模板 | 类别 | 收件人 | 来源 | 参数 | 链接 |
|---|---|---|---|---|---|
| `qb.transfer.sent` | TRANSACTIONAL | 答题人 | 事件 `qb.transfer.sent` | `amount`、`fee`、`net`（"1,234 Kudos" 由流程格式化，D35 参数原样保存） | `{entryUrl.app}wallet` |
| `qb.transfer.failed` | TRANSACTIONAL | 答题人 | 事件 `qb.transfer.failed` | `amount`、`reason`（失败代码对应的说明，不含 Stripe 原文） | `{entryUrl.app}wallet/payout` |
| `qb.payout.action-needed` | TRANSACTIONAL | 答题人 | 新事件 `qb.payout-account.restricted`（`QB_PAYOUT_ACCOUNT_UPDATED` 在 `ENABLED → RESTRICTED` 时发布） | — | `{entryUrl.app}wallet/payout` |
| `qb.topup.paid` | TRANSACTIONAL | 商家 | 事件 `qb.topup.paid` | `amount` | `{entryUrl.sponsor}wallet`（收据在钱包页） |
| `qb.publication.approved` | TRANSACTIONAL | 商家 | 新事件 `qb.publication.status`（见 D-Q7-2） | `name`、`startAt` | `{entryUrl.sponsor}publications` |
| `qb.publication.rejected` | TRANSACTIONAL | 商家 | 同上 | `name`、`reason` | 同上 |
| `qb.publication.taken-down` | TRANSACTIONAL | 商家 | 同上（管理员暂停 / 结束；封禁时的暂停不发，封禁本身不发信，Q2-11） | `name`、`reason`（管理员的理由 `adminReason`，商家本来可见） | 同上 |
| `qb.publication.funds-short` | NOTIFICATION | 商家 | 同上（`LIVE → FUNDS_SHORT`） | `name`、`pendingCount`、`pendingAmount` | `{entryUrl.sponsor}wallet/top-up`、`{unsubscribeUrl}` |
| `qb.publication.ended` | NOTIFICATION | 商家 | 同上（任何原因结束：`EXPIRED` / `QUOTA` / `BUDGET`；管理员结束另见上） | `name`、`reason`、`completions` | 发布页、`{unsubscribeUrl}` |

- 不发邮件：奖励到账与待支付（每次答题一封太多，只在站内，Q7c）、公告与商家通知（只在站内）、充值退款与争议（财务待办已有，商家页可见；以后可加）。
- 模板与 Bean 在新包 `messaging.NotificationMails`（与 `identity.AccountMails` 同写法）；文案三语；16s 合入后各模板声明 `.entries("app")` 或 `.entries("sponsor")` 与 `mail.<名>.name`。

### D-Q7-2 发布状态事件

- `PublicationEditing.write` / `PublicationMoves` 在**值得通知的迁移**时发布 `qb.publication.status`（`publicationId`、`sponsorId`、`from`、`to`、`reason`、`name`）：
  审批通过（→ `SCHEDULED` / `LIVE`）、驳回（→ `DRAFT` 且有 `rejectReason`）、管理员暂停与结束、`LIVE → FUNDS_SHORT`、→ `ENDED`。暂停 / 恢复等商家自己的操作不发。
- 消费者 `qb.publication-notify` → `QB_PUBLICATION_NOTIFY`（`internal()`，权限 `qb.messaging.system` 不授予任何角色）：按 `to` 选模板，`SendMail.of(…, MailRecipient.user(sponsorId))`；
  没有邮箱即 `when` 跳过。热门发布的提交路径只在迁移的那一次多写一行 Outbox（`AttemptStatementBudgetIT` 的标准路径不变；为 `FUNDS_SHORT` 的路径另加断言）。
- **节流**：`FUNDS_SHORT` 可能随充值反复进出。普通表 `QbPublicationNotice`（`publicationId` + `kind` 主键、`lastSentAt`）：同一发布同一种通知 24 小时内只发一次（在 `qb.pub:<id>` 之外取 `qb.pub-notice:<id>` 锁）。

### D-Q7-3 偏好与语言（16s 合入后）

- App 与商家后台的账户页加"邮件通知"区块：平台积木 `MailPreferences`（只列本入口的 NOTIFICATION 模板：App 为 `qb.welcome`；商家为 `qb.publication.funds-short`、`qb.publication.ended`）。
  TRANSACTIONAL 不可退订，区块中说明。
- 两个 SPA 的 `LanguageSelect` 改变界面语言时同时调用 `setMailLocale`（邮件随之改语言）。

### Q7a 的迁移、流程、测试

- **V12** `qb_publication_notice`（普通表）。
- 流程：`QB_PUBLICATION_NOTIFY`、`QB_MAIL_FROM_EVENT`（转账、充值、收款账户：一个内部流程按事件类型选模板，或每种一个；以实现时更清楚者为准）。订阅：`qb.mail-transfer-sent`、`qb.mail-transfer-failed`、`qb.mail-payout-restricted`、`qb.mail-topup-paid`、`qb.publication-notify`。
- **IT** `NotificationMailIT`（GreenMail，照 `AccountSignUpIT`）：每种模板在其迁移后发一封、按收件人语言、主题与链接正确（`{entryUrl.sponsor}` 等）；`FUNDS_SHORT` 24 小时内只一封；
  退订后 NOTIFICATION 记 `SKIPPED`、TRANSACTIONAL 照发；没有邮箱的用户不报错；回滚不发；新事件只在声明的迁移上发布。`MessagesTest` 检查三语。
- 场景：`publishing.yml`、`payout.yml`、`topup.yml` 的快照中出现邮件消息（`SKIPPED`，测试中邮件关闭），逐项看过后更新。
- 前端：账户页 Vitest（偏好区块按入口、语言切换调用 `setMailLocale`）；e2e：Mailpit 收到充值到账与发布通过的邮件（已有 Mailpit 夹具）。

## 6. Q7b 条款、隐私与帮助

### D-Q7-4 法律文本按版本发布（Q7-1、Q7-4、Q7-5）

| 实体 | 类型 | 主要字段 | 说明 |
|---|---|---|---|
| `QbLegalDoc` | T | `docId`；`kind`（字典 `legal-kind`：`TERMS_APP` 答题人条款、`TERMS_SPONSOR` 商家条款、`PRIVACY` 隐私政策、`COMMERCE_NOTICE` 特定商取引法表记；不可变、唯一）；草稿 `title`（`I18nText.of(200)`）、`body`（`I18nText.markdown(100_000)`）、`changeSummary`（`I18nText.markdown(5_000)`）；`processOnly`：`latestVersionNo`、`editedAt` | 每种一行，是**草稿**：内容管理员在通用后台直接编辑（多语言标签页与 Markdown 预览由平台给出，16 §1），`eb.display("kind")` |
| `QbLegalVersion` | W（`writeOnce`） | `kind`、`versionNo`（`(kind, versionNo)` 唯一）、`title`、`body`、`changeSummary`、`requiresConsent`、`publishedAt`、`publishedBy`、`effectiveAt`、`contentHash`（平台 `ContentHash`） | 发布即冻结；同意引用它。只追加、`jabiz_protect_append_only`、另建只含主键的唯一索引（`writeOnce` 要求） |
| `QbConsent` | W | `userId`（文本）、`kind`、`versionId`、`versionNo`、`acceptedAt`、`entry`（`app` / `sponsor`）、`source`（`WELCOME` / `ONBOARDING` / `RECONSENT`） | 同意记录，只插入；索引 `(user_id, kind, version_no DESC)` |
| `QbFaqEntry` | T | `audience`（`APP` / `SPONSOR` / `ALL`）、`category`（字典 `faq-category`，见 Q7-7）、`question`（`I18nText.of(300).required("en")`，`display`）、`answer`（`I18nText.markdown(10_000).required("en")`）、`seq`、`published` | 内容管理员在通用后台直接编辑（默认视图写权限 `qb.admin.content.write`，平台审计） |

- **发布** `QB_LEGAL_PUBLISH`（`qb.admin.content.write`、`requiresMfa(ALWAYS)`、已验证邮箱、`actsOn(QbLegalDoc, docId)`）：输入 `docId`、`effectiveAt`（可空 = 立即；不早于现在）、`requiresConsent`、`baseRevision`。
  锁 `qb.legal:<kind>` → 必填语言齐全（Q7-4：`en`、`ja`；`zh` 缺时显示英文并注明以英文为准）→ 与最新版本哈希相同即 422 `QB_LEGAL_UNCHANGED` → 生效时间不早于最新版本 →
  已有未生效的版本即 422 `QB_LEGAL_UPCOMING_EXISTS`（不改已公布的预告）→ 写 `QbLegalVersion`。没有"当前版本"的状态行：**当前版本 = `effectiveAt ≤ 时刻` 的最大版本号**，
  页面与模板以参数 `at` 给出时刻（同 `qb.app.feed`），流程以服务端时钟（`ctx.opTime()`）判断；所以预定生效不需要定时任务。
- **须同意的最低版本**：某种文本的"须同意版本" = 已生效版本中 `requiresConsent` 的最大版本号（第一版总是须同意）；用户已同意的版本 ≥ 它即可，措辞修订（`requiresConsent = false`）不要求重新同意。
  规则写在纯 Java 的 `messaging.LegalRules`（jqwik：任意发布序列下的当前版本与须同意版本）。
- 没有任何已生效版本时不要求同意（开发、测试与新装）；正式模式下 `LiveModeDataCheck` 对缺少 `TERMS_APP`、`TERMS_SPONSOR`、`PRIVACY` 的已生效版本**告警**（不能报错：先要启动才能发布）。

### D-Q7-5 同意的记录与重新同意（Q7-2、Q7-3）

- **答题人**：`QB_PROFILE_SAVE` 在第一次保存（`/welcome`）时要求 `acceptTerms = true` 与 `termsVersionNo`、`privacyVersionNo`（页面显示的版本）：不同意 422 `QB_TERMS_REQUIRED`，
  版本已不是当前即 422 `QB_TERMS_OUTDATED`（页面重新载入）；写 `QbConsent`（`TERMS_APP`、`PRIVACY`，`WELCOME`）。Google 注册同样经过 `/welcome`。
  已有资料而未同意的用户（Q7 之前注册的）走重新同意。
- **商家**：`QB_SPONSOR_APPLY` 增加 `termsVersionNo` 与 `privacyVersionNo`，写 `QbConsent`（`TERMS_SPONSOR`、`PRIVACY`，`ONBOARDING`）；审批绑定的内容加 `termsVersionNo`（`SponsorApprovals.content`）。
  `termsAcceptedAt` 保留（第一次同意的时间，Q2 评审 6）。
- **重新同意** `QB_CONSENT_ACCEPT`（`qb.account.read`——答题人与商家都持有；已验证邮箱；`CallerChecks.refuseBanned`）：输入 `[{kind, versionNo}]`，只接受本入口的种类与当前版本，写 `QbConsent`（`RECONSENT`）。
- **服务端再查**（`CallerChecks.requireConsent(kind…)`，422 `QB_CONSENT_REQUIRED`，参数 `kind`、`versionNo`）：`QB_TOPUP_START`、`QB_PUBLICATION_SUBMIT`、`QB_SPONSOR_APPLY`（商家条款）、
  `QB_PAYOUT_ONBOARD`（答题人条款）。在锁外读，一条语句（模板 `qb.system.consent-state`）。答题的开始只由页面拦（见 Q7-2）。
- **页面**：两个 SPA 登录后读 `qb.me.consents`（`at`）——有须重新同意的即显示全屏的 `ConsentGate`（变更摘要、全文链接、"同意并继续"、"退出登录"）；未验证邮箱的答题人只显示提示（不能写入）。

### D-Q7-6 帮助中心与公开读取（Q7-7、Q7-8）

- `application.yml`：`jabiz.public.enabled: ${JABIZ_PUBLIC_ENABLED:true}`（只有下面的公开模板；没有公开的文件字段，因此没有公开文件）。运维手册与上线清单补一行。
- 公开视图（非默认、只读）：`public:QbLegalVersion`（`allRows()`，字段 `kind`、`versionNo`、`title`、`body`、`changeSummary`、`requiresConsent`、`effectiveAt`；不含 `publishedBy`、哈希）；
  `public:QbFaqEntry`（`fixed("published", true)`，不含 `published` 以外的管理字段）。
- 公开模板（`access: public`，`cacheSeconds: 300`）：`qb.public.legal`（`kind`、`at` → 当前版本；另给最近一个未生效版本的版本号与生效时间作预告）、`qb.public.legal-version`（`kind`、`versionNo` → 某一版本全文）、
  `qb.public.legal-history`（`kind` → 各版本的版本号、生效时间、变更摘要）、`qb.public.faq`（`audience`、`category?`、`q?` → 按 `seq`；`q` 在任一语言的问与答中按字面包含匹配，`jsonb_each_text` + `strpos`，同 `qb.app.feed` 的写法）。
  页面把 `at` 取整到分钟（缓存与 ETag 有效）。快照 `quizbuks/src/test/resources/public-queries.json`（`PublicQueriesSnapshotIT`，路径已在 `jabizApp` 中配置）。
- 已认证模板：`qb.me.consents`（`qb.account.read`；`at` → 本入口每种文本的当前版本、须同意版本、本人已同意的版本、是否须重新同意）、`qb.me.support`（`qb.account.read`；经 `now:SysParam` 读 `qb.support.email`）、
  `qb.admin.consents`（`qb.admin.users.read`；用户的同意历史，用于 P-01 用户汇总页）。
- 业务参数 `qb.support.email`（普通参数，不受控），`QB_SETUP` 建立（空值时页面不显示"仍需帮助"）。

### Q7b 的页面

- **App**（不需登录也能打开）：`/help`（按分类分组、搜索框、折叠的问答；底部"仍需帮助"→ `mailto:`，只在登录后显示）、`/legal/terms`、`/legal/privacy`（`MarkdownView`、版本与生效日期、预告的新版本与生效日、历史版本列表与查看）；
  注册页在表单下方显示条款与隐私链接（只是告知）；`/welcome` 加同意复选框与链接；`ConsentGate`；账户页加"帮助""条款""隐私""关于"入口；
  `/about`（U-74）：应用名、版本号与构建日期（`vite.config.ts` 的 `define` 自 `package.json` 版本与构建时间写入）、条款与隐私链接、退出登录。Service Worker 不缓存接口，离线时这些页面显示离线横幅（平台已有）。
- **商家后台**：侧栏"帮助"（`/help`，`audience = SPONSOR / ALL`）、页脚的条款、隐私与特定商取引法表记链接（`/legal/:kind`）、入驻页复选框链接到商家条款、`ConsentGate`。
- **通用后台**：`QbLegalDoc` 与 `QbFaqEntry` 由元数据生成的列表与表单（实体与字段名三语）；`QbLegalDoc` 行操作"发布"（`actsOn`）；`QbLegalVersion`、`QbConsent` 只读视图（`qb.admin.users.read`）。
  **后台扩展**：用户汇总页 `/qb/users/:userId` 加"同意记录"一节（`qb.admin.consents`）。

### Q7b 的迁移与测试

- **V13**：`qb_legal_doc_version`（时态，`(doc_id, version_no)` 唯一等时态表要求，`kind` 唯一索引）、`qb_legal_version` 与 `qb_consent`（只追加、只含主键的唯一索引、`jabiz_protect_append_only`、外键与 `process_seq_id` 索引）、
  `qb_faq_entry_version`（时态）。新只追加表有主键（`INTEGRITY` 检查）。
- **单元**：`LegalRulesTest`（当前版本、须同意版本、预告；jqwik 任意发布序列）。
- **IT** `LegalIT`：发布要求权限、二次验证、必填语言；相同内容 422；预定生效之前当前版本不变、到时（固定时钟前进）即变；已有预告再发布 422；两张只追加表没有 UPDATE / DELETE 且有触发器。
  `ConsentIT`：`/welcome` 无同意 422、版本过时 422、同意后记录两行；Q7 之前的用户与新的须同意版本 → `qb.me.consents` 要求重新同意、充值 / 提交发布 / 开通收款 422 `QB_CONSENT_REQUIRED`、重新同意后通过；措辞修订不要求重新同意；
  商家入驻记录同意且审批内容含版本；只能同意本入口的种类与当前版本；没有已生效版本时不要求同意。
  `PublicHelpIT`：匿名读到当前版本与已发布的 FAQ；草稿与未发布的 FAQ 永不出现（参数、筛选、计数三种途径）；未生效的版本不作为当前版本；公开开关关闭时 404；`PublicQueriesSnapshotIT`。
- **场景** `legal.yml`：发布 v1 → 答题人 `/welcome` 同意 → 发布须同意的 v2（7 天后生效）→ 第 8 天充值被拒 → 重新同意 → 通过；快照随提交。`setup.yml` 的快照随新参数更新。
- **Vitest**：帮助页（分组、搜索、未登录不显示联系）、条款页（回退语言加 `lang`、预告、历史）、`/welcome` 复选框、`ConsentGate`、`/about`；两个 SPA 的 `qb/` 保持相同。
- **e2e** `legal.spec.ts`：匿名打开帮助与条款；新用户在欢迎页同意；管理员（不要求二次验证的测试角色，同 Q9c）发布须同意的新版后，答题人登录即被要求重新同意；`a11y.spec.ts` 加帮助、条款、关于、同意弹窗（亮、暗）。

## 7. Q7c 站内信与公告

### D-Q7-7 实体（设计 3.5 的细化）

| 实体 | 类型 | 主要字段 | 说明 |
|---|---|---|---|
| `QbMessage` | P | `messageId`、`recipientId`（文本）、`type`（`SYSTEM` / `REWARD` / `REMINDER`）、`kind`（`REWARD_PAID`、`REWARD_PENDING`、`TRANSFER_SENT`、`TRANSFER_FAILED`、`PAYOUT_ACTION_NEEDED`、`PAYOUT_REMINDER`）、`title`（I18nText）、`body`（I18nText Markdown）、`publicationId?`、`sourceKey`（唯一，如 `reward-paid:<attemptId>`）、`createdAt`；`processOnly`：`readAt`、`clickedAt` | 个人消息。由事件生成，**三种语言在生成时渲染**（消息 `qb.msg.<kind>.title|body`，金额格式化为 Kudos），因此可按界面语言显示、可在 SQL 中搜索。设计中的 T 改为普通表：量大、内容不变，只有已读与点击会变。索引 `(recipient_id, created_at DESC)` |
| `QbBroadcast` | T | `senderKind`（`PLATFORM` / `SPONSOR`）、`senderId`、`audience`（`APP` / `SPONSOR`，商家只能 `APP`）、`type`（`SYSTEM` 系统公告 / `NOTICE` 个人通知 / `PROMO` 营销与商家通知）、`title`（`I18nText.of(120)`）、`body`（`I18nText.markdown(5_000)`；商家为纯文本，见 Q7-10）、`targetMode`（`ALL` / `REGIONS` / `USERS`）、`regions`（同国家的逗号写法）、`publicationId?`、`scheduledAt?`、`expiresAt`；`processOnly`：`status`（`DRAFT` / `SCHEDULED` / `SENT` / `CANCELLED` / `WITHDRAWN`，状态机）、`sentAt`、`audienceCount`、`withdrawnAt`、`withdrawnBy`、`withdrawReason`、`revision` | 公告与商家通知（P-04、M-60），不按人复制 |
| `QbBroadcastTarget` | P | `broadcastId`、`userId`（二者唯一） | 指定用户（`USERS`，每条至多 1 000 人） |
| `QbBroadcastFeed` | P | `broadcastId`（主键）、`audience`、`type`、`targetMode`、`regions`、`publicationId`、`senderKind`、`senderId`、`title`、`body`、`sentAt`、`expiresAt`、`live`（撤回即 false） | 发送时写入、撤回时改 `live`：收件箱只读这张普通表（时态表上按状态与时间筛选要读全部版本，同 `QbPublicationCard` 的理由） |
| `QbBroadcastSchedule` | P | `broadcastId`（主键）、`dueAt` | 定时发送的待办行（同 `QbPublicationSchedule`） |
| `QbBroadcastReceipt` | P | `userId`、`broadcastId`（唯一）、`readAt`、`clickedAt` | 阅读与点击（M-61），每人每条一行 |
| `QbInboxState` | P | `userId`（主键）、`readAllBefore`、`promoOff` | "全部标为已读"的水位线；关闭推广（Q7-11） |

### D-Q7-8 流程

| 流程 | 权限 | 要点 |
|---|---|---|
| `QB_MESSAGE_FROM_EVENT`（订阅 `qb.msg-reward-settled`、`qb.msg-reward-pending`、`qb.msg-transfer-sent`、`qb.msg-transfer-failed`、`qb.msg-payout-restricted`） | 系统（`internal()`，`qb.messaging.system`） | 按 `sourceKey` 只插入一次（重放与重复投递收敛）；新订阅会收到历史事件（平台 11 §2.3），已有的结算因此补上消息 |
| `QB_PAYOUT_REMINDER`（逐项任务 `qb.payout-reminder`，每日；Q7-16） | 系统 | 2120 余额 ≥ 转账门槛、没有 `ENABLED` 的收款账户、30 天内没有提醒 → `REMINDER` 消息（只在站内） |
| `QB_BROADCAST_SAVE` / `QB_ADMIN_BROADCAST_SAVE` | `qb.broadcast.write`（已入驻、未封禁）/ `qb.admin.content.write` | 只改草稿；锁 `qb.broadcast:<id>`；商家的范围以 `senderId = 调用者` 限定（视图 `sponsor:QbBroadcast`） |
| `QB_BROADCAST_SEND` | 同上 | 草稿 → 定时（`scheduledAt` 在未来）或立即发送（子流程 `QB_BROADCAST_DISPATCH`）；商家规则见 Q7-10（`BroadcastRules`，纯 Java，一次报告全部问题） |
| `QB_BROADCAST_CANCEL` | 同上 | `SCHEDULED → DRAFT` |
| `QB_BROADCAST_DISPATCH`（逐项任务 `qb.broadcast-dispatch`，每分钟；也作子流程） | 系统 | 锁 → 到期的 `SCHEDULED` → `SENT`：重查商家未封禁、发布仍 `SCHEDULED / LIVE`（否则 `CANCELLED` 并记原因）；以模板 `qb.system.broadcast-audience` 记 `audienceCount`；写 `QbBroadcastFeed`；删定时行 |
| `QB_BROADCAST_WITHDRAW` | 本人（商家）/ `qb.admin.content.write`（任何公告与商家通知，必填理由，`requiresMfa(ALWAYS)`） | `SENT → WITHDRAWN`，`QbBroadcastFeed.live = false`（从收件箱消失）。`QB_USER_BAN` 在同一事务中以子流程撤回该商家的已定时与已发送通知（同 Q10b 的封禁即暂停） |
| `QB_MESSAGE_MARK` | `qb.me`（已验证） | `{kind: MESSAGE / BROADCAST, id, action: READ / CLICK}`：个人消息只能是本人的；公告须在本人的收件箱中（同一匹配条件）；只记第一次；限流 `jabiz.process.rate-limits`（每用户每分钟 120） |
| `QB_INBOX_READ_ALL`、`QB_INBOX_SETTINGS_SAVE` | `qb.me`（已验证） | 水位线 = 现在（不写回执、不计入阅读统计）；推广开关 |
| `QB_LEGAL_PUBLISH`（Q7b）的增补 | — | `requiresConsent` 的版本可选 `announce = true`：同一事务建一条 `SYSTEM` 公告（"条款将于 X 日更新"），`audience` 按种类 |

### D-Q7-9 收件箱的匹配（Q7-9）

用户能看到的公告 = `QbBroadcastFeed` 中 `live`、`audience = APP`、`expiresAt > at`，且
（`ALL`）或（`REGIONS` 且地区与用户国家的地区有交集；没有国家的用户只看 `ALL` 与含 `GLOBAL` 的）或（`USERS` 且本人在 `QbBroadcastTarget` 中），
且（`type = SYSTEM / NOTICE`，或 `sentAt ≥ 本人资料的 createdAt`：新用户不收到注册前的推广），且（`type ≠ PROMO` 或本人未关闭推广）。
未读 = 没有回执的 `readAt`、且 `sentAt > readAllBefore`（个人消息同理用 `readAt` 与 `createdAt`）。国家取自本人资料（`me:QbProfile`），不取页面参数。

### D-Q7-10 统计（M-61，Q7-14）

`audienceCount` = 发送时匹配的人数（`USERS` 为名单人数）；阅读数 = 有 `readAt` 的回执数（打开详情或展开，不含"全部标为已读"）；阅读率 = 阅读数 / `audienceCount`；
点击数 = 有 `clickedAt` 的回执数（"查看 Quiz"）；点击率 = 点击 / 阅读；转化数 = 点击后在该发布开始答题（`QbAttempt.startedAt ≥ clickedAt`）的人数，转化率 = 转化 / 点击。
模板 `qb.sponsor.broadcasts`（本人的，带统计）、`qb.admin.broadcasts`（全部，带统计与发送方）；会话只经只含 `userId`、`publicationId`、`startedAt` 的只读视图读取（不带金额）。

### Q7c 的模板与页面

- 模板：`qb.app.inbox`（`qb.me`；参数 `at`、`type?`、`unread?`、`q?`、`from?`、`to?`；个人消息 ∪ 匹配的公告，按时间倒序；`q` 在界面语言与缺省语言的标题和正文中字面匹配）、
  `qb.app.inbox-unread`（未读数，分别计个人与公告，至多计到 99）、`qb.app.inbox-item`（详情）、`qb.sponsor.broadcasts`、`qb.sponsor.broadcast-publications`（可关联的本人发布）、`qb.admin.broadcasts`、`qb.system.broadcasts-due`、`qb.system.broadcast-audience`、`qb.system.payout-reminders`。
- **App**：底部导航第 5 项"收件箱"（未读角标，`aria-label` 带数字）；`/inbox`：类型筛选（全部 / 系统 / 推广 / 奖励 / 提醒）、未读筛选、关键字与日期、"全部标为已读"、推广开关；
  `/inbox/:kind/:id`：平台公告以 `MarkdownView` 显示，商家通知为纯文本，关联发布的显示"查看 Quiz"（记点击后到 `/p/<发布>`）。未读数在进入页面、回到前台（`visibilitychange`）与每 5 分钟时刷新。
- **商家后台**：侧栏"消息中心"（`/messages`）：列表（状态、发送时间、匹配人数、阅读 / 阅读率、点击 / 点击率、转化 / 转化率）、编辑（标题、正文、关联的发布、地区（发布地区的子集）、立即或定时）、
  App 中的样子预览、取消定时、撤回；被管理员撤回的显示理由。
- **后台扩展**：`/qb/broadcasts`（菜单"公告与通知"，`qb.admin.content.write`）：列表（发送方、类型、状态、统计）、撰写（多语言标题与正文的标签页、`MarkdownView` 预览、受众、地区多选、指定用户（按 `qb.admin.users` 搜索或粘贴编号，显示为 `UserName`）、定时）、撤回（含商家通知，必填理由）。

### Q7c 的迁移与测试

- **V14**：`qb_message`、`qb_broadcast_version`（时态）、`qb_broadcast_target`、`qb_broadcast_feed`、`qb_broadcast_schedule`、`qb_broadcast_receipt`、`qb_inbox_state`；
  索引：`qb_message (recipient_id, created_at DESC)`、`source_key` 唯一、`qb_broadcast_feed (audience, live, sent_at DESC)`、`qb_broadcast_receipt (user_id, broadcast_id)` 唯一、`qb_broadcast_target (user_id)`。
- **单元**：`BroadcastRulesTest`（商家的关联、地区子集、次数与间隔、时间）、`InboxMatchTest`（匹配条件的真值表，与模板同一组用例）。
- **IT** `InboxIT`：结算、待支付、转账结果各生成一条消息、重复事件只一条、历史事件补上；匹配（地区、无国家、指定用户、注册前的推广、关闭推广、过期、撤回即消失）；
  未读与"全部标为已读"；标记只能是本人的、不在收件箱中的公告 404；搜索与日期；限流。`BroadcastIT`：权限矩阵（答题人、未入驻商家、内容管理员、财务）；商家只能关联本人进行中的发布、地区子集、次数上限；
  定时发送由逐项任务完成、发布已结束即取消；撤回；封禁商家即撤回；统计（阅读、点击、转化）与 `audienceCount`；`QbBroadcast` 时态表无 UPDATE / DELETE。
- **语句数**：`AttemptStatementBudgetIT` 不变（消息在消费者中生成，不在提交路径上）。
- **场景** `messaging.yml`：商家发定时通知 → 任务发送 → 答题人读、点击、答题 → 统计；平台公告按地区；快照随提交。
- **Vitest**：收件箱（筛选、搜索、角标、全部已读、商家通知不渲染 Markdown）、商家消息中心（规则提示只取服务端输出、统计显示）、扩展页（撰写、预览、指定用户、撤回）。
- **e2e** `inbox.spec.ts`：答题后收件箱出现奖励消息与角标；商家定时通知 → 跑任务（e2e 的任务时刻配置同 Q9d）→ 答题人看到并点击 → 商家看到统计；管理员撤回；`a11y.spec.ts` 加收件箱、详情、消息中心、扩展页（亮、暗）。

## 8. 权限、参数与 `QB_SETUP`

- 新权限：`qb.messaging.system`（不授予任何角色）；其余沿用 `qb.me`、`qb.account.read`、`qb.broadcast.write`、`qb.admin.content.write`、`qb.admin.users.read`。
- 新参数（`QbParams`，`QB_SETUP` 补建）：`qb.support.email`（普通）、`qb.broadcast.sponsor.max-per-publication`（3）、`qb.broadcast.sponsor.min-interval-hours`（24）、`qb.broadcast.expires-days`（30）、`qb.payout.reminder-days`（30）。
  都不是金额或免审核开关，不受控（Q7-10 若选审核，另加受控开关 `qb.review.broadcast.auto`）。
- 限流（`jabiz.process.rate-limits`）：`QB_MESSAGE_MARK` 每用户每分钟 120、`QB_BROADCAST_SEND` 每用户每小时 10、`QB_CONSENT_ACCEPT` 每用户每分钟 10。
- 文案三语：实体与字段、字典（`legal-kind`、`faq-category`、`broadcast-type`、`broadcast-status`、`message-type`）、错误码、`qb.msg.*`、`mail.*`；`MessagesTest` 与 SPA 的键一致测试。
- 文档：`02-design.md` 3.5、5.2、6、7 与第 11 节；`backend/quizbuks/CLAUDE.md` 加"消息与内容"一条（个人消息只由事件生成、公告不复制、同意按版本、法律文本只经 `QB_LEGAL_PUBLISH`）；`operations.md`（`JABIZ_PUBLIC_ENABLED`、支持邮箱、发布条款的步骤）；`launch-checklist.md`（三种文本已生效、特定商取引法表记）；`03-plan.md` 状态。

## 9. 风险

- **法律文本不是开发能写的**：没有已生效的条款与隐私政策就不能上线（上线清单已列）；版本模型若与法务意见不符（例如要求逐条同意、或对不利变更须提前若干天公告——民法 548 条之 4 的定型约款变更），只改 `LegalRules` 与发布时的检查。
- **消费者负载**：每次结算多一个消息消费者（与 `qb.taker-wallet` 同一事件），每秒 50 次提交即每秒 50 次插入加审计行；不在锁内、不影响提交延迟，但会推迟 Outbox 中其他消费者。Q10 的目标规格重测时一并观察，必要时把奖励消息改为按需从会话生成（不存）。
- **未读数轮询**：百万用户每分钟轮询会成为最多的请求；所以只在进入、回到前台与每 5 分钟刷新，计数至多到 99。
- **公开接口**：开启 `/api/public/**` 增加匿名入口；只有 4 个模板、无文件，限流与缓存由平台给出；`at` 取整到分钟。
- **商家通知滥用**（广告、钓鱼）：纯文本、只能关联本人发布、次数与间隔上限、管理员撤回、封禁即撤回；仍可能需要事前审核（Q7-10）。
- **历史事件补发**：新订阅会为已发生的结算补生成消息；现在没有正式数据，无影响；已有正式数据时会一次生成大量 `REWARD` 消息（可接受，或部署时先关投递再分批）。
- **平台 16s 的进度**：Q7a 的偏好页依赖它；后端与邮件不依赖，可先合并。

## 10. 预估

| 子阶段 | 天 | 明细 |
|---|---|---|
| 平台 16s | 1–1.5 | 模板的入口与显示名、接口过滤与启动检查 0.5；`@jabiz/client` 与 `MailPreferences` 积木、后台账户页 0.5；示范、测试、文档 0.25–0.5 |
| Q7a | 1.5–2 | 事件与模板、节流、IT 1；页面与 e2e 0.5；文档与快照 0.25–0.5 |
| Q7b | 2.5–3 | 实体、发布与同意规则、公开模板、IT 1.25；两个 SPA 的帮助、条款、同意、关于 1；后台、e2e、文档 0.5–0.75 |
| Q7c | 3.5–4 | 消息与公告后端、任务、统计、IT 1.75；App 收件箱 0.75；商家消息中心 0.5；扩展页 0.5；e2e 与文档 0.25–0.5 |

## 11. 已确认（2026-10-18）

需求方接受全部建议（Q7-1 – Q7-17），并明确：
- Q7-4 法律文本由需求方或律师提供；我们只放明确标注的测试占位文本，正式文本上线前经后台载入。日文为准据语言，英文须一并提供，中文显示英文并附说明。
- Q7-10 商家通知不预先审核，管理员可撤回，封禁即撤回（数值为参数）。
- Q7-2 未重新同意时服务端只拦资金与合同类操作，不拦开始答题。

以下为原问题与建议（即结论）：

| 编号 | 问题 | 选项 | 建议 |
|---|---|---|---|
| Q7-1 | 条款与隐私政策是否按版本管理、变更时是否要求重新同意 | A 按版本发布（只写一次），每版标"须重新同意"与否，可预定生效日；B 只有一页可改的文本，只记同意时间；C 每次改动都要求重新同意 | **A**：措辞修订不打扰用户，实质变更由发布者标记；预定生效日即变更的事前公告 |
| Q7-2 | 未重新同意时服务端拦哪些操作 | A 资金与合约类：充值、提交发布、入驻申请、开通收款（页面在登录后全屏要求同意）；B 再加开始答题（热门路径锁外多一条语句，改语句预算）；C 只在页面拦 | **A** |
| Q7-3 | 答题人在哪一步同意 | A 欢迎页（注册第二步；Google 注册同样经过），注册页只显示链接；B 注册表单内（平台 `SignUpForm` 的附加字段不发送，需改平台） | **A** |
| Q7-4 | 文本种类、由谁起草、语言 | 种类：答题人条款、商家条款、隐私政策（共用）、特定商取引法表记（商家后台）；若法务认定 Kudos 属前払式支払手段，再加该法的表示事项（字典可扩展）。起草：需求方 / 法务提供，开发只放测试用的假文本，不预置正式文本。语言：`en`、`ja` 必填，`zh` 可缺（显示英文并注明以英文为准），正文写明以何种语言为准 | **如左**；请需求方确认由谁提供文本、以哪种语言为准 |
| Q7-5 | 谁能发布法律文本 | A 内容管理员 + 二次验证（审计留痕）；B 再加四眼（另一位管理员经平台审批批准，+0.5 天） | **A**；如法务要求留"批准人"再改 B |
| Q7-6 | 条款变更是否必须提前公告若干天 | A 允许预定生效日但不强制最短天数；B 须重新同意的版本至少提前 N 天（参数） | **A**（是否需要 N 天由法务定，改为 B 只是一条检查） |
| Q7-7 | FAQ 的维护与分类 | 通用后台直接编辑（多语言、Markdown 预览、`seq` 排序、"已发布"开关）；分类：答题人——账号、答题规则、奖励与转账、其他；商家——账号与入驻、内容与发布、充值与费用；条目可标适用 App / 商家 / 两者；匿名可读、可搜索 | **如左**（不做拖拽排序的专用页） |
| Q7-8 | "仍需帮助"的联系方式 | A `mailto:` 支持邮箱（参数 `qb.support.email`，登录后显示）；B 应用内联系表单 → 平台待办给内容管理员，人工邮件回复（+1 天） | **A** |
| Q7-9 | 平台公告的受众与可见规则 | 受众 App 或商家后台；目标：全体 / 多个地区 / 指定用户（每条 ≤ 1 000）；系统公告与个人通知所有匹配者可见到过期（缺省 30 天），营销只给发送前已注册的；没有国家的用户只收全体与含"全球"的 | **如左** |
| Q7-10 | 商家通知的限制与审核 | 必须关联本人 `SCHEDULED / LIVE` 的发布；地区为该发布地区的子集；**纯文本、不能带链接**（点击只到该发布）；每个发布至多 3 条、间隔 ≥ 24 小时；到发布结束即过期；A 不事前审核，管理员可撤回、封禁即撤回；B 像发布一样走审批（受控开关可自动通过，+1 天） | **A**，数值为参数 |
| Q7-11 | 用户能否关闭站内推广 | A 收件箱设置中可关闭"推广"（商家通知与营销），系统、奖励、提醒不可关；B 不可关闭 | **A** |
| Q7-12 | 发哪些邮件 | 见 D-Q7-1 的 9 个：答题人——转账已发出、转账失败、收款账户需补资料（不可退订）；商家——充值到账、发布通过 / 驳回 / 被下架（不可退订）、预算不足（24 小时内一封）与已结束（可退订）。不发：奖励到账（只在站内）、公告（只在站内）、条款变更（站内公告 + 登录时重新同意，不群发）；M-72 的"财务报表订阅"推迟（以后做每月一封带链接的通知） | **如左** |
| Q7-13 | 邮件偏好页的做法 | A 平台 16s：模板声明适用的入口与显示名、`MailPreferences` 积木、界面语言同步为邮件语言（通用后台也受益）；B 应用自己用平台的 `api` 与 `@jabiz/ui` 基础控件写，模板名单与名字写在 SPA 中 | **A** |
| Q7-14 | 统计口径 | 阅读 = 打开详情（"全部已读"不算）；阅读率 = 阅读 / 发送时匹配人数；点击 = 点"查看 Quiz"；点击率 = 点击 / 阅读；转化 = 点击后开始该发布答题的人数 / 点击 | **如左** |
| Q7-15 | 个人消息的生成 | A 三种语言在生成时渲染并保存（可搜索、按界面语言显示，改文案不影响旧消息）；B 只存文案键与参数、显示时渲染（文案改动会改变旧消息，不能在 SQL 中搜索） | **A** |
| Q7-16 | "提醒"类消息 | A 每日任务：余额达到转账门槛而没有可用的收款账户时提醒开通（30 天一次，只在站内）；B 暂不产生提醒类消息 | **A** |
| Q7-17 | 子阶段顺序 | Q7a（邮件，MVP）→ Q7b（条款与帮助，上线必需，可并行）→ Q7c（站内信与公告）；平台 16s 与 Q7a 并行 | **如左** |
