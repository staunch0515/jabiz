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

## 实施记录（Q7b，2026-10-18，分支 `1.2/quizbuks-7b-legal-help`）

基于 `origin/1.2/quizbuks-7-plan`（`1.2/quizbuks` @ 834d042 + 本计划）。只改 `.jabiz-app-paths` 中的路径。需求方确认（§11）：全部建议（Q7-1 – Q7-17）；法律文本由需求方或律师提供，
只放明确标注的测试占位文本；日文为准据语言、英文一并提供、中文显示英文并注明；重新同意只拦资金与合约类操作；答题人在 `/welcome` 同意；内容管理员以二次验证发布并留审计。

**迁移号**：计划假定 Q7a 先合并、Q7b 用 V13；实际 Q7b 先做，`1.2/quizbuks` 上下一个空号是 **V12**（`V12__quizbuks_legal_help.sql`）。之后的 Q7a、Q7c 依次取 V13、V14（按合并顺序，§4）。

### 做了什么

- **实体**（`messaging.QbLegal`）：`QbLegalDoc`（时态，每种一份草稿，`kind` 不可变、在当前版本中唯一，`processOnly` 的 `latestVersionNo`、`lastPublishedAt`）、`QbLegalVersion`（`writeOnce`，`(kind, versionNumber)` 唯一）、
  `QbConsent`（`writeOnce`，索引 `(user_id, kind, legal_version_no DESC)`）、`QbFaqEntry`（时态）；入驻 `QbSponsor` 加 `termsVersionNo`（`processOnly`，审批绑定的内容）。字典 `legal-kind`、`faq-category`、`faq-audience`、`consent-source`。
- **规则**（`messaging.LegalRules`，纯 Java）：当前版本、预告、须同意版本（已生效的须同意的最大版本号，第一版总是）、是否须重新同意、发布的问题（生效时间不早于现在、已有预告即拒绝、与最新版本内容相同即拒绝）、缺少的语言。
- **流程**：`QB_LEGAL_PUBLISH`（`qb.admin.content.write`、`requiresMfa(ALWAYS)`、已验证邮箱、`actsOn(QbLegalDoc, docId)`、锁 `qb.legal:<docId>`；标题与正文日英必填，第二版起变更摘要日英必填；内容哈希只覆盖标题与正文；第一版总是须同意）；
  `QB_CONSENT_ACCEPT`（`qb.account.read`、已验证邮箱、账户锁、查封禁；只接受本入口的种类与当前版本，已同意的不再记）；`QB_PROFILE_SAVE` 完成资料时、`QB_SPONSOR_APPLY` 申请时同意页面所示版本（`QB_TERMS_REQUIRED` / `QB_TERMS_OUTDATED`）；
  `ConsentChecks.sponsor()` 加在 `QB_TOPUP_START`、`QB_PUBLICATION_SUBMIT` 之前，`ConsentChecks.taker()` 加在 `QB_PAYOUT_ONBOARD` 之前（锁外一条语句 `qb.system.consent-state`，422 `QB_CONSENT_REQUIRED`）；`QB_ATTEMPT_START` 不查。
- **模板**：公开 `qb.public.legal`、`qb.public.legal-version`、`qb.public.legal-history`、`qb.public.faq`（`cacheSeconds: 300`、`timeoutMs: 2000`）；已认证 `qb.me.consents`、`qb.me.support`、`qb.admin.consents`；系统 `qb.system.consent-state`（权限 `qb.messaging.system`，不授予任何角色）。
  公开视图 `public:QbLegalVersion`（`allRows`，不含发布人、发布时间与哈希）、`public:QbFaqEntry`（`fixed("published", true)`）。目录快照 `backend/quizbuks/src/test/resources/public-queries.json`。
- **配置**：`jabiz.public.enabled: ${JABIZ_PUBLIC_ENABLED:true}`；限流 `QB_CONSENT_ACCEPT` 每用户每分钟 10；参数 `qb.support.email`（普通，空）。
- **`QB_SETUP`**：为四种文本各建一份草稿（`messaging.LegalDrafts`：三语、标题与正文以 `[TEST PLACEHOLDER]` 开头并写明"不是法律文本、上线前由正式文本替换"），不发布；输出加 `legalDraftsCreated`、`legalDraftIds`。
- **启动检查**：`LiveModeDataCheck` 在正式模式下对没有已生效版本的 `TERMS_APP`、`TERMS_SPONSOR`、`PRIVACY` 各报一条告警（位置 `legal:<kind>`，按应用时钟读）。
- **App**：`/help`、`/legal/terms|privacy|sponsor-terms|commerce`、`/about`（版本与构建时间由 `vite.config.ts` 的 `define` 写入，`package.json` 版本改为 1.0.0）不需登录，有自己的页框与页脚链接；注册页显示条款与隐私链接；
  `/welcome` 的同意（链接在新标签页打开，未勾选不能保存）；资料完整后须重新同意时整页的 `ConsentGate`（未验证邮箱只显示提示条）；账户页"帮助与信息"。
- **商家后台**：侧栏"帮助"、页脚的商家条款、隐私政策与特定商取引法表记；入驻页的同意（发送版本号）；已申请后须重新同意时 `ConsentGate`；未登录也能读帮助与条款。
- **后台扩展**：用户汇总页的"同意的规约"（`qb.admin.consents`，种类与来源按字典显示）。通用后台：`QbLegalDoc`、`QbFaqEntry` 由元数据生成的表单（多语言标签页与 Markdown 预览），`QbLegalDoc` 行操作"发布"。
- **文档**：`02-design.md` 3.5、5.2、6、7.1 – 7.3；`03-plan.md` 状态；`backend/quizbuks/CLAUDE.md`（条款与帮助的约定、前端、e2e）；`operations.md`（`JABIZ_PUBLIC_ENABLED`、首次启动第 9 步、正式模式的告警）；`launch-checklist.md`；CI 的 e2e 作业加 `QB_E2E_CONTENT`。

### 与计划不同之处（均为实现细节，未改变已确认的结论）

1. **迁移 V12** 而不是 V13（见上）。
2. **表名照命名约定**（`qb_<名>_version`）：`qb_legal_version_version`、`qb_consent_version`（计划写作 `qb_legal_version`、`qb_consent`）。实体的版本号字段叫 `versionNumber`（列 `legal_version_no`）：`versionNo` 是平台给每个时态实体的行版本（同 `QbQuizVersion`）；流程输入与页面仍叫 `versionNo` / `termsVersionNo`。
3. 字段名用计划的 `requiresConsent`（任务说明中的 `requiresReconsent` 即此）。草稿的 `processOnly` 字段是 `latestVersionNo` 与 `lastPublishedAt`（编辑由平台审计记录，不另设 `editedAt`）；`baseRevision` 即草稿的行版本号。
4. 发布锁按草稿 `qb.legal:<docId>`（与种类一一对应）。
5. **哪些种类要同意**：App 为答题人条款与隐私政策，商家后台为商家条款与隐私政策；特定商取引法表记只是告知。资金与合约类写入按各自的一组再查。`QB_SPONSOR_APPLY` 的再查就是它自己的同意：版本须为当前版本，否则 `QB_TERMS_OUTDATED`。
6. `QB_CONSENT_ACCEPT` 的"本入口"取自令牌的入口（`app` / `sponsor`）；不是这两个入口的令牌（测试与工具用的后台令牌）按所持角色的权限（`qb.play` → App 的，`qb.sponsor.me` → 商家的）。`qb.me.consents` 由页面给出种类（只是显示，判断在服务端）。
7. **预告的版本**自发布起即公开全文（`qb.public.legal-version`、历史与预告条）：预告变更就是公开新文本与生效日（Q7-6）；它在生效之前从不作为当前版本，也不能提前同意。
8. 第二版起变更摘要日英必填（同意提示与历史要显示）；`QbFaqEntry` 只要求英语（计划如此），页面按界面语言 → 英语 → 日语回退并加 `lang`。
9. FAQ 分类：答题人 `ACCOUNT`、`QUIZ_RULES`、`REWARDS`、`OTHER`；商家 `ONBOARDING`、`PUBLISHING`、`BILLING`（商家页也显示适用于两者的 `ACCOUNT`、`OTHER`）。未登录的帮助页说"登录后可与我们联系"，不显示地址。
10. 页面读 `qb.me.consents` 用此刻（不取整到分钟）：刚生效的版本即服务端要求的版本；公开模板仍取整到分钟以便缓存。
11. **场景**：`QB_SETUP` 多建的行使确定性 ID 后移，选项的显示位置随之改变：`play.yml`、`topup.yml`、`payout.yml` 的答案按新位置改写（得分、奖励不变），`accounts`、`publishing` 的审批内容哈希因 `termsVersionNo` 改变，`setup` 多一个参数与四份草稿；逐项看过后更新快照。新场景 `legal.yml`。
12. **e2e**：发布需要二次验证，测试建自己的内容管理员（已验证邮箱、测试角色 `QB_E2E_CONTENT`，加入 CI 的 `QB_ADMIN_ROLES`），经 API 绑定 TOTP 并 step-up（`tests/legal.ts`）。`prepareQuizbuks` 在没有已生效的三种文本时发布第一版（同上线前），
    `support.ts` 的 `run` 像页面一样带上版本号、并在资金与合约类流程之前接受到期的新版本；经页面完成欢迎页的测试勾选同意。

### 测试与结果

- 新增：`LegalRulesTest`（含 jqwik：任意发布序列下的当前版本、须同意版本与预告；入口与角色决定的种类；内容哈希）、`LegalIT`（权限、二次验证、已验证邮箱、日英必填、相同内容、预告与到时生效、草稿被改、只追加与触发器）、
  `ConsentIT`（按顺序：没有生效版本时不要求；欢迎页无同意 / 版本过时 422、同意记两行；Q7 之前的用户充值、提交发布、开通收款 422 `QB_CONSENT_REQUIRED`、重新同意后通过，**开始答题不拦**；入驻记录同意且入驻行与审批内容含版本；
  措辞修订不要求、预告不能提前同意、生效后要求；只能同意本入口的种类与当前版本）、`PublicHelpIT`（匿名读取、未发布的 FAQ 与草稿从不出现（参数、筛选、计数）、未生效的不是当前版本、无发布人与哈希、缓存头、只有公开模板匿名、不写操作记录）、
  `PublicSwitchedOffIT`、`PublicQueriesSnapshotIT`；场景 `legal.yml`。改动：`SetupIT`、`TopUpIT`（正式模式的条款告警）、`SponsorApprovalsTest`、三个答题场景的答案位置与五份快照。
- `./gradlew :quizbuks:check`（本机 PostgreSQL 16）：568 项测试全部通过，`platformCheck` 0 错误（69 条告警均为测试配置下已知的）。
- 前端：`quizbuks-web/app` 与 `sponsor` 各自 `pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test`（118 / 123 项通过；新增 `Legal.test.tsx`、`qb/legal.test.ts`、商家后台的同意与帮助测试），两份 `src/qb/` 相同；
  后台扩展 `JABIZ_ADMIN_EXTENSION=../backend/quizbuks/admin-extension pnpm ext:check`（66 项通过）。
- e2e（打包的 jar、独立端口与数据库、Mailpit 与 Stripe 桩，照 CI 的 `e2e` 作业）：46 项；新增 `legal.spec.ts` 3 项与 `a11y.spec.ts` 的帮助、条款、关于与同意提示（亮、暗）。
  第一次全量运行发现入驻页提交后同意提示读到旧的同意状态而一闪替换了表单（先刷新入驻再刷新同意），修正为先刷新同意（App 的欢迎页同样），并加了 Vitest；修正后全量 44 项通过、2 项失败——
  `a11y` 的发布页（Quiz 选择框的 `aria-required-children`）与 `content.spec` 的"另处修改后要求重新载入"（焦点），都与本阶段无关，单独重跑通过（后者连续 3 次），第一次全量运行中也都通过，记为既有的偶发问题。

### 自查（对抗性审查）

- **匿名的公开接口**：匿名只能执行四个 `access: public` 模板（其余 404，含 `qb.me.*`、`qb.admin.*`、`qb.system.*`；写方法 405），它们只能读两个公开视图：法律文本的已发布版本（不含发布人、发布时间与哈希）与 `published = true` 的 FAQ。
  草稿是另一实体、没有公开视图；同意记录与用户数据没有公开视图。参数全部绑定，`kind`、`audience`、`category` 是带固定值的代码（未知值 400），`q` 以 `strpos` 字面匹配，`limit` ≤ 100，按地址限流、缓存 5 分钟。
  `PublicHelpIT` 证明未发布的问答不经参数、外层筛选或计数出现，草稿与其后的编辑从不出现，响应中没有发布人；开关关闭时 404（`PublicSwitchedOffIT`）。剩余：`at` 由调用者给出，传未来时刻可看到预告版本被当作"当前"——预告本已公开，不是泄露。
- **同意的门能否绕过**：页面的 `ConsentGate` 只是界面；服务端在充值、提交发布、开通收款与入驻申请中再查（读在锁外，生效时刻恰在读与提交之间的毫秒级窗口可接受，计划如此）。
  只能同意当前版本（不能用旧版本或预告版本满足"须同意版本"）；只能同意本入口的种类（App 的会话不能替商家条款同意；以 App 入口登录的会话本来也没有充值等商家权限）；未验证邮箱与被封禁的不能同意，也不能做被拦的操作。
  欢迎页：第一次保存必然完成资料（国家必填），因此必然要求同意；在 Q7b 之前已完成资料的人走重新同意。**未拦的**（按需求方的 Q7-2，记为已知）：开始答题；已开通收款账户的人的自动转账照常进行；发布的暂停、恢复、复制不算新合约。
- **Markdown**：只经平台的 `MarkdownView`（`skipHtml`，无 `rehype-raw`，react-markdown 的 URL 过滤去掉 `javascript:`）；标题、问题、种类名都作纯文本；Vitest 断言条款中的 `<script>` 与 FAQ 答案中带 `onerror` 的 `<img>` 不出现在 DOM 中。法律文本的链接在新标签页打开（`rel="noopener"`，同源）。

### 已知问题与后续

- **正式文本**：仓库中只有标注 `[TEST PLACEHOLDER]` 的草稿；需求方或律师提供日文（准据）与英文正文后，由内容管理员载入并发布（`operations.md` §3 第 9 步、上线检查清单）。正式模式在三种文本生效之前启动告警。
- ~~预告一经发布不能撤回或替换~~：评审后加了撤回与最长预告期（见下节 M1）。
- 民法 548 条之 4 的事前公告期间不强制（Q7-6 A）；需要时只在 `LegalRules.publishProblems` 加一条检查。
- 删除草稿（通用后台允许）之后，`QB_SETUP` 不会再建（它记得建过）；需要时在后台新建同一种类的草稿即可（唯一性只看当前版本）。
- 条款变更的站内公告（`announce = true`）属于 Q7c。

## 评审后的修正（2026-10-18，同一分支）

独立评审（2a708f7）没有发现匿名数据泄露或绕过同意之门的服务端途径；以下各项按"先写会失败的测试、再修"处理。V12 尚未合并，直接在其中补表。

- **M1 预告无法撤回**：新实体 `QbLegalWithdrawal`（只写一次，`legalVersionId` 唯一；V12 中的 `qb_legal_withdrawal_version`）与流程 `QB_LEGAL_WITHDRAW`（`qb.admin.content.write`、每次二次验证、已验证邮箱、必填理由，`actsOn(QbLegalVersion)`；
  与发布共用锁 `qb.legal:<种类>`；只在 `effectiveAt > opTime` 时允许，否则 422 `QB_LEGAL_WITHDRAW_IN_EFFECT`，重复 422 `QB_LEGAL_WITHDRAWN`）。`LegalRules` 的当前、预告、须同意与"相同内容"都不看撤回的版本，
  下一个版本号仍是全部版本中最大的号加一（号不再使用）；第一版须同意与第二版起须写变更摘要都按"未撤回的版本"判断。`qb.system.consent-state`、`qb.me.consents`、`qb.public.legal` 排除撤回的版本，
  `qb.public.legal-history` 与 `qb.public.legal-version` 多一列 `withdrawn`（公开视图 `public:QbLegalWithdrawal` 只有种类、版本号与撤回时间，不含撤回人与理由）；`LiveModeDataCheck` 也不把撤回的版本算作已生效。
  **最长预告期**：受控参数 `qb.legal.max-announce-days`（`QB_SETUP` 提出 180；未发布时同样按 180，只是收紧），超过即 422 `QB_LEGAL_EFFECTIVE_TOO_LATE`——`9999-01-01` 与 `+300000-01-01` 都在写入之前被拒（后者原来是 500）。
  读取参数用 `QueryEntities`（与版本、撤回同一条语句），不用 `LoadParams`（它在参数未发布时整个流程失败）。发布的输出加 `versionId`（场景与后台用它撤回）。
  后台扩展新页"QuizBuks → 规约类"（`/qb/legal`，`qb.admin.content.write`）：每种文本的草稿预览（日、英）、发布（带读到的草稿版本、生效时间、是否须重新同意，先确认）、版本列表（模板 `qb.admin.legal-versions`：状态、发布人、撤回人与理由），
  未生效的预告行可填理由撤回（先确认）；通用后台 `QbLegalVersion` 的行操作同样有"撤回"。App 与商家后台的条款历史把撤回的版本标为"已撤回"，打开它时注明"施行前已取下、从未适用"。
- **M2 页面按设备时钟判断**：模板不能引用平台保留的 `:__asOf`（模板检查只认声明的参数），也没有"服务端此刻"的参数种类，所以采用后备方案：`qb/legal.ts` 由 `qb.me.consents` 应答的 `Date` 头（整秒）估计设备时钟的偏差，
  偏差超过 2 秒加半个往返即改正并立刻按服务端时刻重读一次，之后的读取（含公开页面取整到分钟的时刻）都用改正后的时刻；公开模板的应答可能来自缓存，不用来学时钟。
  同意提示收到 `QB_TERMS_OUTDATED` 时重读（已有），并且只认用户对**当前显示的版本**的勾选：版本变了即取消勾选、显示"已再次更新"，用户读后再同意新版本，不会循环。测试：设备快一小时、慢一天各读一次，以及收到过时后的再同意。
- **M3 服务端拒绝后提示不出现**：`qb/problems.ts` 的 `consentRequired`；两个 SPA 的 QueryClient 改用 `qb/ConsentGate.tsx` 的 `newQbQueryClient()`（查询与变更的缓存出错时检查 `QB_CONSENT_REQUIRED` 并使 `CONSENTS_QUERY` 失效），
  不经 React Query 的开通收款（`PayoutPage`）与提交发布（`PublicationWizard`）自己调 `noticeConsentProblem`。测试：Vitest（App 的开通收款、商家的充值、QueryClient 本身）与 e2e（页面已读过同意状况后点"开通"，提示随即出现）。
- **L1**：`PublishInput.baseRevision` 必填（`@NotNull`，缺即 400）；后台页带上读到的草稿版本，e2e 与场景同样带上。
- **L2**：`SponsorApprovals.content` 只在 `termsVersionNo` 不为空时放入该键：没有版本时内容与 V12 之前相同，之前待审的申请仍能批准（单元测试比较旧内容的哈希，IT 比较请求中保存的哈希并批准）。
- **L3**：锁名改为 `qb.legal:<种类>`；发布先读草稿（种类不可变）再取锁，锁内一条语句读该种类的版本、撤回与参数；草稿在锁前读，按版本的条件写回与必填的 `baseRevision` 保证它没有被改过。
- **L4**：`LiveModeDataCheck` 把读取法律文本失败单独报告为告警（"Live mode cannot check which legal texts are in effect"），不再借用充值的消息；测试 `LiveModeDataCheckTest`。
- **L5**："关于"页的构建时间取 `SOURCE_DATE_EPOCH`，否则取所构建提交的时间（`quizbuks-web/app/build-time.ts`，Vitest），都没有时显示"—"；不再是构建机器的此刻。
- **L6**：同意提示的"阅读全文"链接带 `?v=<版本号>`。
- **测试缺口**：删除的 FAQ 不再公开（`PublicHelpIT`）；极端日期 422（`LegalWithdrawIT`）；时钟偏差两个方向（`qb/legal.test.ts`）；V12 之前待审的申请仍可批准（`SponsorApprovalsTest`、`SponsorOnboardingIT`）。
- **偶发的 e2e 的根因**：（1）发布向导的 Quiz 选择框在每次搜索时把列表换成加载圈（`loading={quizzes.isFetching}`），打开的 `listbox` 里暂时没有 `option`，axe 恰在此时运行即报 `aria-required-children`。
  改为 `placeholderData: keepPreviousData` 且只在第一次加载时显示加载圈（`isPending`），e2e 在运行 axe 之前等待所输入的搜索的应答；Vitest 断言搜索在途时列表仍是原来的选项（旧代码失败）。
  平台的 `Combobox` 在加载时把加载圈放进 `listbox` 本身也会触发同样的规则：应在平台改为 `aria-busy` 且加载圈放在列表之外——记为平台的后续，不在本分支改。
  （2）内容工坊"另处修改后要求重新载入"在 `requestAnimationFrame` 中聚焦提示，繁忙时这一帧早于 React 提交提示，焦点落空。改为提交之后在 effect 中聚焦（提示已在时直接聚焦）；Vitest 让下一帧立即执行来复现（旧代码失败）。

### 评审修正的测试与结果

- 新增或扩充：`LegalWithdrawIT`（撤回的权限、二次验证、理由；已生效不能撤回、不能重复；撤回后公开页、历史、`qb.system.consent-state` 与 `qb.me.consents` 都不再算它，时间过后仍不生效、不能同意；
  同一文本可再发布、得下一个号；后台模板只给内容管理员；超过最长预告期——含 `9999-01-01` 与 `+300000-01-01`——422，恰在 180 天可以）、`LegalRulesTest`（撤回、最长预告期，jqwik 的发布序列中随机撤回）、
  `LegalIT`（缺 `baseRevision` 即 400）、`PublicHelpIT`（删除的 FAQ）、`SponsorApprovalsTest` 与 `SponsorOnboardingIT`（L2）、`LiveModeDataCheckTest`（L4）、`SetupIT`（新参数）；场景 `legal.yml` 加撤回一段，
  七个场景多发布一个提案（`p28`：新参数排在受控参数的最后，审批与职责分离规则后移一位），三个答题场景的答案位置再次按新的显示位置改写（得分、奖励不变），更新快照。
- `./gradlew :quizbuks:check :quizbuks:bootJar`（本机 PostgreSQL 16）：575 项测试全部通过，`platformCheck` 0 错误（69 条告警同前）。
- 前端：App 133 项、商家后台 132 项 Vitest 通过（lint、typecheck 通过，两份 `src/qb/` 相同，`app:check` 两个都通过）；后台扩展 `ext:check` 70 项通过（新增 `legal.test.tsx` 4 项）。
- e2e（打包的 jar，独立端口与数据库，`retries: 0`）：第一次全量 45 项通过、2 项失败——是新加的等待写错了（请求体是流，Playwright 的 `postData()` 为空，等不到），改为在首批选项显示后等下一次应答；
  修正后**全量 47 项一次全部通过，没有重跑**（新增撤回一项；`legal.spec` 的重新同意改为在已读过同意状况的页面上点"开通"，由服务端的拒绝带出提示）。
- 之后 `origin/1.2/quizbuks` 前进（平台 16r 受保护授权、16s 收件偏好，只改平台目录，无冲突），合并后重跑：`:quizbuks:check` 575 项通过、`platformCheck` 0 错误（72 条告警，多出的是平台新检查在测试配置下的告警），
  两个 SPA 的 lint、typecheck、Vitest（133 / 132）与 `app:check`、`ext:check`（70）通过，e2e 全量 47 项再次一次全部通过。

### 评审修正后的已知问题

- 平台：模板的时间参数超出 PostgreSQL 的范围（如公开的 `qb.public.legal?p.at=+300000-01-01T00:00:00Z`）仍是 500（`22008 timestamp out of range`）。属于平台参数绑定，QuizBuks 自己的写入路径已在写入前拒绝；应在平台的参数校验中限定 `temporal` 的范围。
- 平台：`Combobox` 加载时 `listbox` 中只有加载圈（见上），应改为 `aria-busy`。
- 设备时钟的改正以 `Date` 头为准（整秒）：在新版本生效前后约 2 秒内页面与服务端可能不一致，服务端仍以自己的时钟判断，页面在拒绝后重读。


## 12. 实施记录（Q7a 邮件通知，2026-10-18，`1.2/quizbuks-7a-mail`）

基于 `origin/1.2/quizbuks-7-plan` @ c4a5cf7（`1.2/quizbuks` 没有更新的提交）。

### 12.1 合并平台（16r、16s）

- `origin/1.2/platform` @ bc029b6 以合并提交并入（无冲突）：16s（D49：`MailTemplate.entries(...)`、`mail.<名>.name`、`MailPreferences` 积木与 `@jabiz/client` 的四个函数、`offerMailLanguage`）、
  16r（D48：受保护授权的四眼与建立期、`jabiz.fiscal-year-zone`、`privateMetadata()` 文件的随机编号）。合并后、改动之前 `./gradlew :quizbuks:check` 通过。
- **16r 对 QuizBuks 的影响**：
  - 平台按 `*` 数管理员，QuizBuks 的角色都不持有 `*`，所以安装一直处于建立期，受保护的授权当即生效：`QB_SETUP` 的角色与职责分离提案、e2e（`QB_E2E_PUBLISHER` 持有
    `control.publish`、`QB_E2E_FINANCE`）与本机演示（`QB_DEMO_PUBLISHER`）都不建第二位 `*` 管理员，**不需改动**；本阶段的 e2e 与本机演示照常通过（12.4）。
  - 首个管理员不会退役（没有另一位 `*` 管理员）。
  - 平台建议（10 §16.4）细分权限的部署设 `always=true`，但在 QuizBuks 中审批受保护授权的人只能是 `*` 的持有者（审批人须持有 `*`，或同时持有 `security.user-role.write`
    与 `approval.decide`，而 `qb.sod.security-administration` 正好禁止后者），空库上开 `always` 时首个管理员也无法独自建立第二位管理员。
    **需求方决定以平台 16u 解决**（按安全管理的权限计管理员、以专门的权限审批受保护的变更；之后 QuizBuks 需要两位 `QB_ADMIN_SUPER`），在 16u 之后另做；本分支不改设置，`operations.md` §3 已说明。
  - 新的启动告警：没有（注册角色 `QB_TAKER`、`QB_SPONSOR` 不含受保护权限；`ProtectedGrantChecks` 不告警）。16s 带来的告警 `mail.qb.welcome.name` 缺失已补。
- `application.yml`：`jabiz.fiscal-year-zone: Asia/Tokyo`（Q10b 评审 L7）；`operations.md` §3 说明。`RetentionIT` 照常通过（其日期都不在日本与 UTC 的跨日之间）。

### 12.2 实现

| 项 | 做法 |
|---|---|
| 事件 `qb.publication.status` | `publishing.PublicationNotices`：`PublicationEditing.write` 写入后按 `kindOf(from, values)` 记下通知（`APPROVED` 审核中 → 定时 / 上架；`REJECTED` 审核中 → 草稿且有审核人的理由；`TAKEN_DOWN` 管理员暂停（含商家已暂停的）或结束，封禁的暂停（`QB_ACCOUNT_SUSPENDED`）不算；`FUNDS_SHORT` 上架 → 预算不足；`ENDED` 因时间、名额或预算结束）。只有审批结果、`ADVANCE`、完成时的移动、`FUNDED` 与管理员的暂停 / 结束 / 恢复在流程末尾 `PublishEvent`；商家自己的提交、撤回、暂停、恢复与封禁的暂停不发布。载荷 `publicationId`、`sponsorId`、`from`、`to`、`kind`、`reason`、`name`、`startAt` |
| 事件 `qb.payout-account.restricted` | `QB_PAYOUT_ACCOUNT_UPDATED` 在 `ENABLED → RESTRICTED` 时发布（`PENDING → RESTRICTED` 是开通过程，不发） |
| 模板 | `messaging.NotificationMails` 的 9 个（D-Q7-1）；可退订的只有 `qb.publication.funds-short`、`qb.publication.ended`，声明 `.entries("sponsor")`；`qb.welcome` 改为 `.entries("app")`。事务类不声明入口（D49 第 5 条：对它们无意义且告警）。三语的 `mail.<名>.subject|body|name`（含 `qb.welcome.name`） |
| 流程 | `messaging.MailProcesses`：`QB_MAIL_TRANSFER`（`qb.transfer.sent` / `.failed` 两个订阅）、`QB_MAIL_PAYOUT_ACCOUNT`、`QB_MAIL_TOPUP`、`QB_PUBLICATION_NOTIFY`；都 `internal()`，权限 `qb.messaging.system` 不授予任何角色。各自读记录本身（转账、收款账户、充值、发布）与收件人（`SecUser` 的邮箱与语言），金额格式化为 "1,234 Kudos"、时刻按 `jabiz.fiscal-year-zone`（日本时间）并标出时区的简称 `JST`（`MailValues`，时区不写在代码中）；转账失败的理由与发布结束的原因按收件人的语言取消息（`qb.mail.transfer-failure.ACCOUNT|OTHER`、`qb.mail.end-reason.*`，收件人以同一语言交给 `SendMail`）。没有邮箱、记录不在、超过 3 天的事件（`NotificationMails.MAX_AGE`：新订阅收到历史事件、投递积压）、充值未到账、收款账户已不再受限、发布已不再预算不足、发布的商家与事件不符：都不发信、不失败 |
| 节流 | `QbSponsorNotice`（普通表 `qb_sponsor_notice`，迁移 **V13**），按（商家，种类）记最近一次；锁 `qb.sponsor-notice:<商家>`（自成一族：从不取发布锁，答题与支付的流程也从不取它）；24 小时内第二次什么也不发；一封列出发信时该商家全部预算不足的发布（至多 10 个，多的以「…」表示）；退订而跳过的同样记下 |
| 页面 | 两个 SPA 的账户页（共用的 `qb/AccountSections`）加"邮件"区块：平台积木 `MailPreferences`（只列本入口的可退订模板与邮件语言），下方说明哪些邮件总会发送（App / 商家各一句）；共用的 `qb/LanguageSelect` 改变界面语言后调用 `offerMailLanguage`；App 加 `Toaster`（询问以通知显示） |
| 文档 | 后端 `CLAUDE.md`（邮件只由事件的消费者发）、`operations.md`（应用的邮件一览、受保护授权、会计年度的时区）、`03-plan.md` |

**与计划的出入**：

- 迁移号 **V13**（不是 V12）：Q7b（`1.2/quizbuks-7b-legal-help`，评审中）用 V12。一个安装不能先跑 V13 再跑 V12（Flyway 拒绝晚到的低版本），所以 **Q7b 须先于 Q7a 合并、部署**；
  两者都合并后的新库按顺序迁移。现在没有正式安装。
- "审批通过"只在**审核人**批准时发（审核中 → 上架）；提交时不需审核或沿用旧批准而直接上架的，商家当场就在页面上看到，不发信。
- 发布的通知读发布本身（收件人与名字取自记录，与事件的商家不符即不发），理由取自事件（管理员恢复时会改写 `adminReason`）。
- 预算不足的节流按**商家**（需求方 2026-10-18 决定，取代计划的 `(publicationId, kind)`）：钱包用尽时几个发布同时预算不足，只发一封，列出发信时全部预算不足的发布。
- 邮件中的时刻按**日本时间、标 `JST`**（需求方 2026-10-18 决定；时区取自 `jabiz.fiscal-year-zone`）。
- 转账失败的 `reason` 不用 Stripe 的原文，只分两类：收款账户的问题（`STRIPE_DECLINED`、`STRIPE_NOT_FOUND`）与其他。

### 12.3 测试

- `NotificationMailIT`（GreenMail，`jabiz.mail.enabled=true`）：转账发出（日文）与失败（英文、说明与 `{entryUrl.app}wallet/payout`）；收款账户只在 `ENABLED → RESTRICTED` 时、`PENDING → RESTRICTED` 不发；
  充值到账与 `/sponsor/wallet` 链接、同一事件重投只一封；审核通过（日本时间的开始时间，标 JST）与驳回（理由）、撤回与商家自己的暂停 / 恢复不发布事件；管理员暂停与结束各一封、封禁的暂停不发布；
  两个发布同时预算不足只发一封并列出两者（不列仍上架的）、同日再次不发（事件照常发布）、25 小时后再发一封且只列当时预算不足的、通知行只有一行；退订"已结束"后记 `SKIPPED` 而管理员下架照发、事务类不能退订（422）；发布结束（中文：名额、完成数；到期由每分钟任务）；
  偏好按入口列出（商家后台两项、日文显示名；App 只有 `qb.welcome`；App 设置商家模板 422）；超过 3 天的事件、不存在的记录与用户不发信；四个流程对任何调用者都是 403 `PROCESS_INTERNAL`。
- 单元：`PublicationNoticesTest`（哪些迁移发布、哪些不）、`MailValuesTest`（金额、名字列表、日本时间与 JST、跨 UTC 日界、9 个模板的类别与入口）；`QbRolesTest`（`qb.messaging.system` 不授予任何角色）；`ProcessAccessIT`（四个内部流程）；`MessagesTest`（三语）。
- **语句数**：`AttemptStatementBudgetIT` 写缓冲开时不变（开始 10 / 3、付奖的提交 13 / 7、使发布结束的完成 18 / 12：事件随其他写入一起写出）；应急开关关时使发布结束的完成 29 / 23 → **30 / 24**（事件一条）。
  新增"使发布预算不足的完成"：16 / 10（开关关时 24 / 18），并断言该事务中没有写邮件。
- 场景：`topup.yml` 的快照只有一处变化——分录明细 CSV 的 `sha256`（明细含交易编号，场景中的编号按顺序生成，充值到账的邮件记录多占了编号）；逐项看过后更新。其他场景不变（快照不含邮件表）。
- 前端：App 与商家后台的 `App.test.tsx` 各加两项（账户页的邮件区块按入口列出并保存开关；切换界面语言后询问邮件语言，接受才保存 / 拒绝不保存）。
- e2e：`topup.spec.ts`（Mailpit 收到充值到账、链接到 `/sponsor/wallet`；商家账户页的两项偏好，亮暗两种外观的 axe）、`review.spec.ts`（驳回的邮件含理由与 `/sponsor/publications`，通过的邮件）。

### 12.4 验证

- `./gradlew :quizbuks:check`（PostgreSQL 16，`JABIZ_TEST_DB_*`）：563 项测试全部通过，`platformCheck` 通过（`MAIL` 检查不再告警）。
- 两个 SPA：`pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test`（App 106 项、商家后台 117 项）；`pnpm app:check` 两者都通过；两份 `src/qb/` 相同（`diff -r`）。
- 后台扩展：`JABIZ_ADMIN_EXTENSION=../backend/quizbuks/admin-extension pnpm ext:check`（65 项）。
- 工具：`tools/quizbuks/test/entrypoint.test.sh`、`stripe-test-mode.test.sh`、`tools/test/check-app-paths.test.sh`、`tools/check-app-paths.sh`（基准 `origin/1.2/platform`）都通过。
- e2e（打包的 jar、本机的 Mailpit 与 Stripe 桩、独立的库与端口）：41 项中 40 项通过；`content.spec.ts` 的"他处改过的 Quiz 要求重新加载"一次因焦点断言失败，单独重跑该文件 3 项全过（与本阶段无关的偶发）。
- 本机演示 `tools/quizbuks/local-demo.sh start --seed`（独立的库与端口，复用本机的 Mailpit）：首次设置（5 个角色、27 个提案由演示发布人发布）与演示数据照常完成，然后 `stop`。
- **需求方的两项决定之后**（2026-10-18：预算不足按商家每天一封、邮件时刻按日本时间标 JST）并**合并 `origin/1.2/quizbuks`（Q7b，#176，V12）之后**再验证一次：
  合并的冲突在消息资源、商家后台的文案与测试、本计划（两段实施记录都保留）与 `topup` 场景快照；`QbPermissions.MESSAGING_SYSTEM` 两边各加了同值的常量，留一处；
  场景只有 `topup` 的分录明细 `sha256` 再次变化（编号随 Q7b 与 Q7a 的记录顺延），看过后更新。V12（Q7b）在前、V13（Q7a）在后。
  `./gradlew :quizbuks:check` 594 项全部通过；两个 SPA 的 lint、typecheck、test（App 135 项、商家后台 134 项；商家后台在本机负载高时有交易页与钱包页的测试偶发超时，单独与重跑全过，与本阶段无关）；
  `app:check`、`ext:check`（70 项）、工具测试与 `check-app-paths.sh` 通过；e2e 47 项全部通过。
