# S0 — 引擎的"发布方"抽象（实施计划，待确认）

依据：`00-overview.md` §2（两个商户系统、一个答题引擎）与 §4（S0 行）。线 1.3（`1.3/quizbuks`），工作分支 `1.3/quizbuks-S0-publisher`，
PR 合回 `1.3/quizbuks`。**S0 不需要平台改动**，不动 `1.3/platform`；只改 `.jabiz-app-paths` 列出的路径。

## 1. 缘由

今天引擎里"谁发布的"写死为商户账户：发布、档位、卡片、答题会话等都带 `sponsorId`（商户的平台用户编号），商户的数据视图以
`sponsorId = 当前用户` 限定范围。Shopify 商户不是平台用户（店铺即身份，S1），要让同一套发布与答题流程服务两种商户，
发布必须挂在一个中性的"发布方"上：**编号 + 类型**（`SPONSOR` / `SHOPIFY_SHOP`）。这是不兼容的改动（字段改名、迁移），也是开 1.3 线的理由。

## 2. 决定（待确认）

| # | 决定 |
|---|---|
| D-S0-1 | **发布方 = 一对字段**：`publisherKind`（代码，字典 `urn:jabiz:dict:quizbuks:publisher-kind`，取值 `SPONSOR` / `SHOPIFY_SHOP`，纯 Java 的 `publishing.PublisherKind` 给出常量与清单，`StaticDictionary` 在 `PublishingConfig` 注册、三语标签）与 `publisherId`（文本 128）。两者都 `immutable`、`required`。`SPONSOR` 的 `publisherId` 就是今天的 `sponsorId`（商户的用户编号），值不变。**不**另建"发布方"实体：发布方的资料各在自己的系统里（`QbSponsor` / S1 的 `QbsShop`），引擎只需要一个能分辨的键 |
| D-S0-2 | **改名的范围由一条规则决定**：凡是"**发布的所有者的副本**"的字段（随发布、卡片、会话、统计复制下来的那个 `sponsorId`）改为发布方；凡是"**商户账户自己的**"记录（充值、收据、邮件服务器、邀请配额、领奖码、AI 作业与用量、允许的奖励方式）保持 `sponsorId` / `userId`，因为它们本来就只属于普通商户系统。按此规则改名的实体见 §3 |
| D-S0-3 | **商户的数据视图**（`urn:jabiz:dataset:sponsor:*`、`urn:jabiz:dataset:me:QbPublicationOwner`、分析的商户视图）范围改为 `fromContext("publisherId", actorId)` **且** `fixed("publisherKind", SPONSOR)`。Shopify 模块（S1）为店铺另建自己的数据视图，不复用商户视图 |
| D-S0-4 | **答题人与公开读取只看 `SPONSOR` 的发布**：公开卡片视图 `urn:jabiz:dataset:public:QbPublicationCard` 的固定范围加 `publisherKind = SPONSOR`；`qb.app.feed`、`qb.app.publication` 加同一条件。店面流量在 S2 用 Shopify 模块自己的模板与匿名接口，不进 App 的首页。现在没有店铺的行，所以行为不变，只是把边界先画出来（默认拒绝） |
| D-S0-5 | **迁移一步到位**（同 V10 的 LS-6：单实例、停机升级，旧 jar 不能跑新表、新 jar 不能跑旧表）：V29 对 §3 的表 `RENAME COLUMN sponsor_id TO publisher_id`、`ADD COLUMN publisher_kind … NOT NULL DEFAULT 'SPONSOR'` 再 `DROP DEFAULT`（之后每条写入都必须显式给出类型）并加 `CHECK (publisher_kind IN ('SPONSOR', 'SHOPIFY_SHOP'))`；索引随列改名；视图 `qb_publication_owner` 重建为三列。只追加表的触发器只拦 UPDATE / DELETE / TRUNCATE，不拦 DDL；行不被改写。PostgreSQL 16 对带常量缺省的 `ADD COLUMN NOT NULL` 只改目录、不重写表，`qb_attempt` 再大也只是短暂的排他锁 |
| D-S0-6 | **审批事实**改为 `publisherKind` + `publisherId`（`ApprovalSubject` 的声明与 `PublicationApprovals.facts`）。审批**绑定的内容**（`content()`）从来不含 `sponsorId`，所以升级时待审核的请求的哈希不变、照常生效；`QB_SETUP` 的审批规则不引用 `sponsorId` |
| D-S0-7 | **事件 `qb.publication.status`** 的载荷 `sponsorId` 改为 `publisherKind` + `publisherId`；消费者 `QB_MAIL_PUBLICATION` 只对 `SPONSOR` 发邮件（店铺的通知在 S1 另定）。钱包事件 `qb.sponsor.settle-pending`、`qb.sponsor.funds-restored` 是商户账户自己的，载荷不变 |
| D-S0-8 | **对外名字不变**：三个前端的界面文字仍叫"商家 / Sponsor"，后台扩展地址中的筛选参数仍是 `?sponsor=`（映射到 `publisherId`）；`UserName` 只对 `SPONSOR` 显示名字，其他类型显示编号（S1 决定店铺怎样显示）。`userKey = md5(发布方编号 : 用户编号)` 的值对现有数据不变 |
| D-S0-9 | **内容（`QbQuiz.ownerId` 等）与 AI（`QbAiJob.sponsorId`、`QbAiUsage.sponsorId`）在 S0 不改**：它们记的是**操作人**（谁建的模版、谁请求的 AI），不是发布的所有者。店铺怎样成为操作人（每个店铺一个平台用户，还是别的）由 S1 决定；AI 按套餐限次由 S3 决定。见 §8 Q-S0-3 |

## 3. 受影响的实体

### 3.1 改为发布方（`sponsorId` → `publisherKind` + `publisherId`）

| 实体 | 表 | 类型 | 为什么是"发布的所有者的副本" | 另要改的 |
|---|---|---|---|---|
| `QbPublication` | `qb_publication_version` | T | 发布本身 | 列表视图的列与筛选；索引 `…_sponsor_idx` → `…_publisher_idx` |
| `QbRewardTier` | `qb_reward_tier_version` | T | 随发布写入，商户视图不连接 | 索引改名 |
| `QbPublicationCard` | `qb_publication_card` | P | 上架时从发布冻结 | 公开白名单 `sponsorId` → `publisherId`（D-S0-4）；索引 `qb_publication_card_sponsor_idx` |
| `QbPublicationOwner` | 视图 `qb_publication_owner` | 视图 | 卡片的两列 | `DROP VIEW` + `CREATE VIEW`（三列） |
| `QbAttempt` | `qb_attempt` | P | 开始时从卡片复制 | 部分索引 `qb_attempt_pending_idx (sponsor_id, …)` 随列改名；`AttemptRules.Card`、`PlaySteps`、`AttemptFinishing`、`PendingSettlement.queue`（按 `publisherId` 取待支付） |
| `QbAnalyticsHour` / `QbAnalyticsCursor` / `QbQuestionStat` | `qb_analytics_*`、`qb_question_stat` | P | 任务从卡片复制（看板统计是共用引擎） | `AnalyticsJob`、`AnalyticsConfig` 的商户视图（D-S0-3）、模板 `qb.system.analytics-state`、`qb.sponsor.dashboard` 等 |
| `QbDigitalPrize` / `QbDigitalCode` | `qb_digital_prize_version`、`qb_digital_code` | T / P | 从卡片与发布复制；**码池就是 S2 折扣码预生成池的雏形**（`POOL` 投递、末 4 位提示、中奖人打开） | `DigitalCodeProcesses`、`DigitalPrizeProcesses`、`RewardConfig` 的商户视图、`OpenDigitalPrize` |
| `QbCashReward` / `QbRedemption` | `qb_cash_reward_version`、`qb_redemption_version` | T | 结算时从会话复制 | `AttemptFinishing.external`、`CashRewardProcesses`、`RedemptionProcesses`、`RewardConfig` |
| `QbInvitation` | `qb_invitation` | P | 随发布写入 | `InvitationProcesses`、`InvitationConfig` 的商户视图 |

### 3.2 保持不变（商户账户自己的）

`QbTopUp`、`QbTopUpReceipt`、`QbSponsorMailServer`、`QbInvitationQuota`、`QbRedeemToken`（商户的领奖码）、`QbAiJob`、`QbAiUsage`（含全体行 `*`）、
`QbSponsorRewardMethods.userId`、钱包、账本维度、分录导出、公告的 `senderId`、Stripe 元数据中的 `sponsorId`（`CheckoutRequest`、`StripeStub`、`stm.py`）。
它们的模板、消息键、后台页面与测试都不动。

### 3.3 最小方案（备选 A）

只改 `QbPublication`、`QbRewardTier`、`QbPublicationCard`、`QbPublicationOwner`、`QbAttempt`（发布链），其余留给 S1–S2 遇到时再改。
省约 1 天，但 S2 复用码池、S3 做看板时各要再来一次同样性质的改名与迁移，且规则不再是一句话能说清的。**建议按 §3.1（方案 B）做**。

## 4. 改动清单

### 4.1 后端 `backend/quizbuks`

- **新**：`publishing.PublisherKind`（常量、`ALL`、`DICTIONARY`；纯 Java）；`PublishingConfig` 注册字典。
- **实体定义**：§3.1 的 `QbPublications`、`QbPlay`、`QbAnalytics`、`QbDigitalPrizes`、`QbRewards`、`QbInvitations`：字段改名、加 `publisherKind`、列表视图的 `columns` / `filters`、
  公开白名单；`QbPublications.approvalSubject()`（D-S0-6）。
- **写入处**（每处把 `sponsorId` 换成一对值；新建时 `publisherKind = SPONSOR`、`publisherId = actorId`）：`PublicationProcesses`（保存、复制、档位）、`PublicationEditing`（卡片、按商户查发布、取账户锁用 `publisherId`）、
  `PublicationLifecycle`、`PublicationAdmin`、`PublicationFunds`（按商户找 `FUNDS_SHORT` 的发布）、`PublicationNotices`（D-S0-7）、`AttemptProcesses` / `PlaySteps` / `AttemptFinishing` / `AttemptRules` / `AttemptResults` / `PendingSettlement`、
  `AnalyticsJob`、`DigitalCodeProcesses` / `DigitalPrizeProcesses` / `OpenDigitalPrize`、`CashRewardProcesses` / `RedemptionProcesses`、`InvitationProcesses`、`MailProcesses`（`PublicationMailInput`、D-S0-7）、`BanProcesses`（按商户暂停发布的查询）。
- **数据视图**（D-S0-3、D-S0-4）：`PublishingConfig`、`PlayConfig`、`AnalyticsConfig`、`RewardConfig`、`InvitationConfig`。
- **SQL 模板**（`queries/qb/**`，占位符 `{{实体.sponsorId}}` → `{{实体.publisherId}}`；结果列 `sponsorId` → `publisherId`，标识发布方的行加结果列 `publisherKind`）：
  `app/feed`、`app/publication`、`app/wallet`、`app/cash-rewards`、`app/redemptions`、`public/publication`、`sponsor/publications`、`sponsor/dashboard`、`sponsor/analytics-*`（4）、`sponsor/transactions`、`sponsor/transaction-summary`、
  `admin/publications`、`admin/publication-funds`、`admin/quiz-takedowns`（参数 `sponsorId` → `publisherId`）、`admin/attempts`、`admin/attempt-rewards`、`admin/cash-rewards`、`admin/redemptions`、`admin/digital-prizes`、`admin/investor-sponsors`、`admin/investor-metrics`、
  `system/analytics-state`、`system/recon-sponsor-pending`、`system/mail-funds-short-due`、`wallet/taker-transferable`。约 27 个文件；连接商户、钱包的条件只换占位符，不加 `publisherKind` 条件（商户视图已固定类型，店铺的发布不过账、不进 App）。
- **消息资源**（三语）：`entity.<实体>.sponsorId` → `.publisherId`，新增 `entity.<实体>.publisherKind`；`query.<id>.sponsorId` → `.publisherId`（+ `.publisherKind`）；字典标签在 `StaticDictionary` 中。
- **迁移** `V29__quizbuks_publisher.sql`（D-S0-5）：12 张表 + 1 个视图 + 索引改名。
- **文档**：`docs/quizbuks/02-design.md`（§2 行范围、§3.3 各行、§5.1 审核事实）、`backend/quizbuks/CLAUDE.md`（§2 改为线 1.3；§4 发布加一条"发布方"约定：哪些实体带它、商户视图固定类型、新写入处必须显式给类型）、
  `00-overview.md` §4 的 S0 状态、`docs/quizbuks/operations.md`（升级 1.2 → 1.3 的步骤：停应用、待 Outbox 投递完、迁移、启动）。

### 4.2 前端

- 商家后台 `quizbuks-web/sponsor`：模板 `qb.sponsor.*` 不返回 `sponsorId`，页面代码不变；测试夹具 `publishing.test.tsx` 中发布行的 `sponsorId` 改名。`MailServerOutput.sponsorId` 不变（§3.2）。
- 答题人 App：不读该字段，不变。
- 后台扩展 `backend/quizbuks/admin-extension`：`api.ts`（`PublicationRow`、`Publication`、`readTakedowns`）、`overview-api.ts`（发布列表的筛选字段 `publisherId`，地址参数仍是 `sponsor`）、`reward-api.ts`（现金、兑换、电子奖品三种行）、
  `finance-api.ts` 中 `InvestorSponsor`、`PublicationsPage` / `PublicationReviewPage` / `QuizDetailPage` / `ReviewsPage` / `CashRewardsPage` / `RedemptionsPage` / `DigitalPrizesPage` / `InvestorMetricsPage`（按类型决定显示 `UserName` 还是编号，D-S0-8），
  以及 `pages.test.tsx`、`overview.test.tsx`、`reward.test.tsx`、`finance.test.tsx` 的夹具。充值、银行转账、手工加币、AI、分录各页不变。
- e2e（`quizbuks-web/e2e`）：没有读过该字段，不改；全量回归。
- `quizbuks/src/test/resources/public-queries.json`（公开模板目录快照）：白名单字段改名后重生成。

### 4.3 工具与部署

`tools/quizbuks/*`、`deploy/quizbuks/*`、`.github/workflows/quizbuks*.yml` 不改（`quizbuks.yml` 已按通配符对 `1.3/quizbuks` 的推送与 PR 运行；`quizbuks-deploy.yml` 只部署 1.2，符合 §4"生产继续跑 1.2"）。

## 5. 测试清单

| 测试 | 内容 |
|---|---|
| **新** `PublisherMigrationIT`（同 `CounterSplitMigrationIT` 的写法：自己的 schema，Flyway 迁到 V28、插入各表的行、再迁 V29） | 旧行的 `publisher_kind` 全为 `SPONSOR`、`publisher_id` 等于原 `sponsor_id`；`qb_publication_owner` 三列；`DROP DEFAULT` 之后不带类型的插入被 NOT NULL 拒绝；CHECK 拒绝未知类型 |
| **新** `PublisherKindTest`（core 风格单元测试） | 清单与字典一致；`PublicationApprovals.facts` 含两个事实（扩展 `PublicationApprovalsTest`） |
| 现有集成测试（约 35 个类提到 `sponsorId`，如 `PublicationIT`、`PlayOwnerIT`、`PublicationOwnerIT`、`TakedownIT`、`AdminReviewAccessIT`、`AnalyticsJobIT`、`DigitalPrizeIT`、`RedemptionIT`、`InvitationIT`、`ShareLinkIT`、`InvestorMetricsIT`） | 只改断言中的键名；行为断言原样通过 |
| `AttemptStatementBudgetIT`、`AttemptStatementBudgetWithoutHeldWritesIT`、`QuotaBudgetIT` | **数字不变**（多一列不多一条语句）；所取的锁不变 |
| `AnswerSecrecyIT`、`PlayConcurrencyIT`、`PlayThroughputIT`（perf，不在 check 中） | 不变 |
| 场景回放（12 个场景） | `publishing.yml` 的期望值改键名；快照只允许两类差异：`"sponsorId"` → `"publisherId"`，以及新增的 `"publisherKind" : "SPONSOR"` 行。验证方法：对新快照做 `publisherId → sponsorId` 替换并删去 `publisherKind` 行后与旧快照 `diff` 为空（PR 描述中附命令与结果） |
| `./gradlew :quizbuks:check`（含 `platformCheck`） | 元数据与表结构一致、模板引用的列存在、无新增警告 |
| 前端 | `pnpm app:check ../quizbuks-web/app` 与 `sponsor`；两个 SPA 的 `lint` / `typecheck` / `test`；`JABIZ_ADMIN_EXTENSION=../backend/quizbuks/admin-extension pnpm ext:check`；`diff -r` 两个 `src/qb/` |
| e2e（`quizbuks.yml` 的 `e2e`、`package`、`local-demo`、`deploy` 作业） | 原样通过 |
| `tools/check-app-paths.sh` | 只有应用路径 |
| 自查 | `/code-review`；本阶段改了数据视图范围与 SQL 模板，再跑 `/security-review` |

## 6. 验收对照（`00-overview.md` §2）

| 验收 | 怎样满足 |
|---|---|
| 现有全部场景回放原样通过 | §5 第 6 行：快照差异只有改名与新增的类型行，由替换后的 `diff` 证明 |
| 集成测试原样通过 | §5 第 3–5 行 |
| 端到端原样通过 | e2e 不读该字段；CI 全绿 |
| 快照除字段改名外不变 | 严格说快照多出 `publisherKind: SPONSOR` 这一行（新字段必然出现在实体读取中）；除此之外只有改名。请确认这一点在验收之内 |

## 7. 风险

| 风险 | 应对 |
|---|---|
| 升级运行中的 1.2 数据库时 Outbox 里尚有未投递的 `qb.publication.status` 事件（旧载荷键），新消费者读不到 `publisherId` | 消费者对缺键的事件什么也不做并记日志（不写兼容读取）；升级步骤写进 `operations.md`：停应用 → 确认 `sys_outbox` 已投递完 → 迁移 → 启动。该事件只用于通知邮件，最坏丢一封 |
| 迁移改动 12 张表，其中 `qb_attempt` 可能很大 | D-S0-5：改名与带常量缺省的加列都只改目录；`DROP DEFAULT`、加 CHECK 需要扫表验证一次（`CHECK` 对已有行逐行检查，`qb_attempt` 百万行约秒级）；停机窗口内完成；`PublisherMigrationIT` 覆盖 |
| 快照出现改名以外的差异 | 即为意外的行为变化，按缺陷处理（不是更新快照了事） |
| 改名遗漏在模板的 YAML 头、消息键或后台扩展中 | `platformCheck` 报模板列不存在；三语消息键一致性由启动检查与前端测试报；`ext:check` 的类型检查报前端 |
| 后台扩展对非 `SPONSOR` 的发布方显示 | 现在没有这类行；S1 决定店铺的显示与链接 |
| 本环境没有 Docker | 本机以 PostgreSQL 16（已装）起一个实例并设 `JABIZ_TEST_DB_*` 跑集成测试与回放；CI 为准 |
| 范围蔓延到内容与 AI | D-S0-9 明确不改，列为 S1 / S3 的问题 |

## 8. 未决问题（请回答）

| # | 问题 | 建议 |
|---|---|---|
| Q-S0-1 | 范围按 §3.1（方案 B，约 2.5–3 天）还是 §3.3（方案 A，约 1.5–2 天）？ | **B**：一条规则说清，S2 复用码池、S3 做看板不再二次改名 |
| Q-S0-2 | `SHOPIFY_SHOP` 的 `publisherId` 是什么：店铺记录（S1 的 `QbsShop`）的主键，还是为每个店铺建的平台用户的编号？S0 的代码不依赖答案，但它决定 S1 怎样做店铺的数据视图 | **店铺记录的主键**：店铺可以换店主、可以卸载重装，用户编号会变而店铺不变；Shopify 模块的数据视图自己把操作人映射到店铺 |
| Q-S0-3 | 内容的 `ownerId` 与 AI 的 `sponsorId`（操作人）是否也在 S0 改为发布方？ | **不改**（D-S0-9）。若 S1 决定"每个店铺一个平台用户"，操作人就是那个用户，内容无需再改；若不是，S1 再改内容，范围清楚 |
| Q-S0-4 | 迁移一步到位、停机升级（D-S0-5），还是做成兼容两版的扩展迁移？ | **一步到位**：与 V10 相同的前提（单实例、停机），升级步骤写进 `operations.md` |
| Q-S0-5 | 快照中新增的 `publisherKind: SPONSOR` 行是否算在"除字段改名外不变"之内？ | 是：它是新字段的必然体现，由替换后的 `diff` 证明没有别的差异 |
| Q-S0-6 | 工作分支名用 `1.3/quizbuks-S0-publisher`（按您给的格式 `1.3/quizbuks-S0-…`）；`tools/check-app-paths.sh` 与 CI 的通配符 `!*/*-[0-9]*` 不把它当长期分支，没有问题。确认即可 | — |

## 9. 工作量

方案 B：实体与写入处 1 天，模板与消息 0.5 天，迁移与 `PublisherMigrationIT` 0.5 天，后台扩展与测试夹具 0.5 天，回放核对、文档、自查与 PR 0.5 天，合计 **2.5–3 天**；方案 A 约 1.5–2 天。
