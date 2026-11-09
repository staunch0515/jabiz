# F-S8 屏蔽系统：实施计划

依据：`docs/quizbuks/first-launch-spec.md` F-S8（以它为准）；`Q112-video-push.md` §0-6、§8.1 与 `Q94-first-launch.md` §3 第 14 项只作参考。
会话 S3，分支 `1.3/quizbuks-fs8-blocks`，从最新的 `1.3/quizbuks` 拉出（已含 F-S9 主题词与严格模式、F-S10 短视频、F-S11 境外视频、F-S12 共享视频的 `QbPublicationCard.videoProviderId`、F-S15 任意网址的视频）。

## 0. 范围

一个 PR 做完 F-S8。

- **搜索（F-S6，S1，未合入）**：现在的搜索只有首页的 `q`（`qb.app.feed` 按发布名与 Quiz 标题找），屏蔽的条件写在同一模板里，搜索自然照此过滤。**F-S6 做完整搜索时必须套用同一段条件**（`block.BlockRules.blocks` 是唯一定义，SQL 照抄 `qb.app.feed` 中标着 F-S8 的那段，并把 F-S6 的模板加进 `BlockIT` 的比较测试）。
- **收件箱的推广**：现有指向发布的推广是**发起方的通知**（`QbBroadcastFeed.senderKind = SPONSOR`，总带一个发布）与带发布的推广（`type = PROMO`）。屏蔽在 `qb.app.inbox` 与未读数 `qb.app.inbox-unread` 中都生效（两者条件一致，未读数不算看不到的条目）。平台的公告现在不带发布；以后若带，系统公告（`SYSTEM`）照常显示，只有推广（`PROMO`）照屏蔽隐藏。发送时的触达人数（`qb.system.broadcast-audience`）**不减去**屏蔽的人：否则发起方可从触达人数推算出屏蔽人数，违背"只给取整后的人数"。
- **推送（F-S13，未开始）**：做时把同一段条件照抄进推送的选取，加进比较测试。
- **"我的 → 偏好"**：App 的账户中最接近的是"App 设置"（`/account/preferences`，语言、音效、安装）。全部屏蔽列在那一页的新一节"屏蔽"（`blocks/BlockList.tsx`），菜单的提示加"屏蔽"。F-F4（Q108 的框架）换页面时整节搬过去。
- **不做**：屏蔽主题词（规格：由"严格按喜欢的主题"代替）。Shopify 店铺的店面答题（S2）没有学者身份，不在本条范围；店铺的发布本来不进 App。

**删除的功能：无。** 没有屏蔽的学者，首页、短视频、详情、收件箱与开始答题都与以前完全相同。

## 1. 规则（一处定义：`block.BlockRules`）

- **对象**：四种，`PUBLICATION`（一个发布）、`QUIZ`（一个 Quiz，含它以后新发的发布）、`SPONSOR`（一个发起方的全部发布）、`PROVIDER`（视频的制作方：凡是卡片冻结了它的共享视频的发布，F-S12 的 `videoProviderId`）。屏蔽表 `QbBlock` 是普通表，本人一行一个对象（`user_id, kind, object_id` 唯一）。
- **从卡片屏蔽**：学者只给出发布与种类（`QB_BLOCK_ADD {publicationId, kind}`），服务器从卡片取对象：发布即卡片的发布，Quiz 即卡片的 `quizId`（新列，见第 2 节），发起方即卡片的 `publisherId`，制作方即卡片的 `videoProviderId`（没有共享视频的卡片不能屏蔽制作方，`QB_BLOCK_NO_PROVIDER`）。所以对象总是存在的、学者看得到的东西，客户端不能写任意编号。不能屏蔽自己的发布、自己或自己作为制作方（`QB_BLOCK_OWN`）。已屏蔽的再屏蔽一次照样成功（返回原来那一行）。
- **一个发布被屏蔽**：本人的屏蔽中有一行的 `(kind, object_id)` 是 `(PUBLICATION, 发布)`、`(QUIZ, 卡片的 quizId)`、`(SPONSOR, 卡片的 publisherId)` 或 `(PROVIDER, 卡片的 videoProviderId)` 之一（`BlockRules.blocks`）。卡片冻结 Quiz 与制作方，所以"屏蔽 Quiz 包括以后新发的发布"、"屏蔽制作方后凡是用到它视频的发布都看不到"都由这一条得出。
- **硬屏蔽**：付多少钱都越不过。条件写在首页（含短视频一排、竖屏模式与搜索）、详情、收件箱推广的模板与开始答题的检查里，**不看奖励、推广费、受众规则或任何付费项**；以后的付费曝光（F-S13、F-M）同样不得越过。这是平台原则：写进 `backend/quizbuks/CLAUDE.md`、帮助与规格。
- **详情**：打开被屏蔽的发布的链接时，页面只显示"你已屏蔽"（屏蔽的是发布、Quiz、发起方还是制作方）与逐个"解除"；没有答题记录时不显示发布的内容。已经答过或正在答的，页面照常（可以看结果、继续答），上方提示已屏蔽并可以解除。
- **开始答题**：新的开始被屏蔽时 422 `QB_PUBLICATION_BLOCKED`；进行中的答题可以继续（与 F-S2b1 的节点门槛一样只查新的开始，学者自己开始的事不半途打断）。
- **上限**：一人至多 `qb.block.max-per-user`（缺省 1000）行，满了 422 `QB_BLOCK_LIMIT`（先解除一些）。
- **取整的人数**：发起方的屏蔽人数是屏蔽了该发起方、它的 Quiz 或发布、或它作为制作方的**不同学者**的人数；少于 `qb.block.count-min-group`（缺省 20，下限 5）时只说"少于 N"，否则向下取整到 10 的倍数、但不小于 N（`BlockRules.shownCount`，模板写同一算式）。发起方看不到是谁、屏蔽了什么。
- **私密**：屏蔽只属于本人。别人（发起方、运营商、总部）读不到哪一行；总部的管理页只有汇总（第 5 节）。

## 2. 改动

| 位置 | 改动 |
|---|---|
| `block`（新包） | `QbBlocks`（实体、字典、数据视图名、锁名）、`BlockRules`（纯 Java：种类、对象、`blocks`、`matching`、上限、取整）、`BlockCodes`、`BlockProcesses`（`QB_BLOCK_ADD`、`QB_BLOCK_REMOVE`）、`BlockStart`（开始答题的读取与拒绝）、`BlockConfig` |
| `publishing.QbPublications` / `PublicationEditing.cardOf` | 卡片新列 `quizId`（不可变，上线时写版本的 Quiz） |
| 迁移 `V97__quizbuks_blocks.sql`（临时编号：V93–V96 已被 S1、S2 占用，合并前按最大号 + 1 重编） | 新表 `qb_block`（普通表；唯一 `(user_id, kind, object_id)` 兼作按人的索引，`(kind, object_id)` 索引供发起方的人数与汇总）；卡片加列 `quiz_id`，按版本回填，加索引（列可空：只有手工做的、找不到版本的卡片为空） |
| 模板 | `qb.app.feed`：返回 `quizId`、`videoProviderId`、`videoProviderName`，F-S8 的条件；`qb.app.publication`：返回同上与 `blocks`（本人覆盖它的屏蔽，JSON）、`blocked`，`canStart` 加上没有被屏蔽；`qb.app.inbox`、`qb.app.inbox-unread`：发起方的通知与带发布的推广照同一条件；`qb.app.blocks`（新，`qb.play`）：本人的全部屏蔽与对象的名称；`qb.sponsor.blockers`（新，`qb.sponsor.me`）：取整的人数；`qb.admin.blocks`、`qb.admin.blocked-sponsors`（新，`qb.admin.block.read`）：汇总 |
| `play.AttemptProcesses.start` | 第一条读取多读"本人有没有屏蔽"（同一条语句）；有屏蔽且是新的开始时，锁外再读覆盖这张卡片的屏蔽（一条），被屏蔽即 422 |
| `identity.TakerErasure` | `ErasedEntity.delete(QbBlock, "userId")` |
| `setup.QbParams` | `qb.block.max-per-user`（1000）、`qb.block.count-min-group`（20），不受控，加在最后 |
| `QbPermissions` / `QbRoles` | `qb.admin.block.read`（内容管理员；超级管理员经内容管理员的列表也有）；运营商没有。学者用已有的 `qb.play` |
| 消息 | 三语：错误码、实体与字段、字典、流程、参数、模板的列 |
| App | `blocks/`：`api.ts`（流程与模板、`coveredBy` 客户端判断）、`BlockMenu.tsx`（卡片、详情、竖屏模式共用的菜单："不想看"、屏蔽这个 Quiz、屏蔽发起方、屏蔽视频制作方；屏蔽后提示并可以撤销）、`BlockedNotice.tsx`（详情的"你已屏蔽"）、`BlockList.tsx`（"App 设置"中的一节，逐个解除）；`FeedCardView` 多一个可选的 `menu`（共用的 `src/qb`，两个 SPA 一致）；`FeedPage`、`ShortsViewer`、`PublicationPage` 挂上；`qb/play.ts` 的卡片与详情多 `quizId`、`videoProviderId`、`videoProviderName`、`blocks` |
| 发起方页面 | 首页的"屏蔽人数"一格（`blocks/BlockersMetric.tsx`，少于 N 显示"少于 N 人"） |
| 管理系统扩展 | 新页 `/qb/blocks`（菜单"屏蔽"，平台管理系统的平台组，`qb.admin.block.read`）：按种类的屏蔽数与人数、屏蔽人数最多的发起方（取整，少于 N 不列） |
| 文档 | 本计划；帮助 `docs/quizbuks/help/blocks.md`（三语）；`operations.md` §7.30；`backend/quizbuks/CLAUDE.md` 一条；上线检查清单一行；规格 F-S8 补"平台原则"一句；进度表 |

锁：屏蔽与解除取本人的锁 `qb.block:<用户>`（只在屏蔽的两个流程中），锁内读本人的屏蔽（至多上限 + 1 行，一条语句）再写。不取发布、开始或钱包的锁，答题的锁序不变。

## 3. 语句数

- **开始答题，没有屏蔽**：不变（`AttemptStatementBudgetIT` 原有的数）。"本人有没有屏蔽"（`user_id` 上至多一行）加在开始的第一条读取里（`QueryEntities.all`，同一条语句）。
- **开始答题，有屏蔽**：锁外多 1 条（覆盖这张卡片的屏蔽：本人、四个 `(kind, object_id)` 之一，唯一索引），锁内不变。`AttemptStatementBudgetIT.aStartWithBlocks` 断言确切数（总数 `START_TOTAL + 1`，锁内 `START_LOCKED`）。继续进行中的答题不读。
- 首页、详情、收件箱的模板各多一个相关子查询（本人的屏蔽，唯一索引上至多四次探查）；不是答题路径。
- 上线与版本切换：卡片多一列由原有的写入带上，不多语句。

## 4. 测试

- 单元：`BlockRulesTest`（四种对象的覆盖、没有制作方、取整与最小分组、上限与参数的读法、种类）。
- `BlockIT`：
  - **比较测试**：两个发起方、两个 Quiz、一个制作方的共享视频用在两个发布上；对一组屏蔽组合（无、发布、Quiz、发起方、制作方、几种一起，共七组），首页、短视频一排（`shorts`）、搜索（`q`）、详情的 `blocked` 与 `canStart`、收件箱的发起方通知与未读数、开始答题（422 或成功）逐一与 `BlockRules.blocks` 相同；
  - 屏蔽 Quiz 后新上线的同一 Quiz 的发布也看不到；屏蔽制作方后两个发布都看不到；解除后恢复；
  - 流程：从卡片取对象、没有制作方、自己的、重复屏蔽返回原行、上限（参数调低）、别人的屏蔽解除 404、不存在的卡片 404、审计；
  - 取整的人数（少于 N、取整、只算本人的对象、不同学者只算一次）；总部的汇总与权限（发起方、运营商读不到）；
- `AttemptStatementBudgetIT.aStartWithBlocks`（见第 3 节）。
- `TakerErasureIT.anErasedTakersBlocksGo`：删除账号后屏蔽行没有了，别人的照旧。
- `SetupIT` 的参数表、场景 `setup` 的 `paramsCreated`；场景快照按 `-Dscenario.update-snapshots=true` 更新并核对差异。
- 前端：App `blocks/blocks.test.tsx`（菜单的四项、没有制作方时没有那一项、屏蔽后撤销、详情的"你已屏蔽"与解除、"App 设置"中的列表与逐个解除、`coveredBy` 与服务器同一组用例、axe 亮暗）；发起方页面 `blocks/blockers.test.tsx`（取整、少于 N、axe）；管理系统扩展 `blocks.test.tsx`（汇总、少于 N 不列、axe）。
- 端到端 `quizbuks-web/e2e/tests/blocks.spec.ts`：一个提供方共享视频，另一个发起方用它上线两个发布；学者从首页卡片的菜单屏蔽视频制作方，两个都不见了，在"App 设置"中解除后都回来；屏蔽一个发布（"不想看"），打开它的链接显示"你已屏蔽"，解除后可以开始答题；亮暗都过 axe。

## 5. 运维

见 `operations.md` §7.30。部署后运行一次 `QB_SETUP`（建两个普通参数，补上 `qb.admin.block.read`）。总部的"屏蔽"页只有汇总：按种类的屏蔽数与人数，屏蔽人数（取整）最多的发起方。没有任务，没有新的环境变量。

## 6. 决定（需转达总控 / 需求方，可改）

1. **"不想看" = 屏蔽这个发布**（种类 `PUBLICATION`），提示中有"撤销"（即解除刚才那一行）。菜单另有"屏蔽这个 Quiz""屏蔽发起方 <名称>""屏蔽视频制作方 <名称>"（卡片有共享视频时）。如果"不想看"要另成一种（只影响推荐、不算屏蔽），需另加种类。
2. **从卡片屏蔽**：客户端只给发布与种类，对象由服务器从卡片取（不接受任意编号）。为此卡片加冻结的 `quizId`（迁移按版本回填）。
3. **进行中的答题可以继续**，只拒绝新的开始；已答过的仍可看结果。详情页对它们照常显示并提示已屏蔽。
4. **发起方的屏蔽人数**：屏蔽了它、它的 Quiz 或发布、或它作为视频制作方的不同学者；向下取整到 10 的倍数（不小于 N），少于 `qb.block.count-min-group` 时"少于 N"（参数下限 5）。只在发起方页面的首页显示一个数（不按发布分）。
5. **总部的管理页**：按种类的屏蔽数与人数（全平台的数，不含任何人）与屏蔽人数最多的 20 个发起方（取整，少于 N 不列）。运营商没有这一页。
6. **收件箱**：发起方的通知与带发布的推广照屏蔽隐藏（未读数一致）；系统公告照常；触达人数不减（防止推算屏蔽人数）。
7. **删除账号**：学者的屏蔽随之删除；被屏蔽的发起方删除账号时，别人屏蔽它的行保留（只有编号，卡片仍在）。
8. **偏好的位置**：App 没有"我的 → 偏好"，放在"账户 → App 设置"（语言、音效的同一页）的新一节"屏蔽"。
9. **屏蔽需要已验证的邮箱**（与学者的其他写入一样，D-Q40-4，`VerifiedEmailWritesIT`）：没有验证邮箱的账号不显示菜单（反正也不能答题）。
10. **不能屏蔽自己的**（自己的发布、自己、自己作为制作方）：避免发起方把自己算进屏蔽人数。

## 7. 以后

- F-S6：完整搜索套用同一条件（第 0 节）。
- F-S13：推送套用同一条件；付费曝光不得越过屏蔽。
- F-F4：屏蔽一节搬到新框架的偏好页；卡片菜单随卡片搬过去。
- 屏蔽的统计按发布分、随时间的变化：需求方提出时再做（同样取整、最小分组）。

## 8. 风险

- 屏蔽很多（上限 1000）的学者，首页每张卡片多一次索引探查（唯一索引，至多四个条件）；`AnalyticsPerfIT` 一类的规模测试不受影响（没有屏蔽时子查询立即为空）。
- 调低 `qb.block.max-per-user` 后，已超过的学者不能再加，先解除一些；已有的照常生效。
- 卡片的 `quiz_id` 由迁移按版本回填：上线必有版本，所以每张卡片都有；列仍可空，只为不让手工做的卡片（迁移测试中有）挡住迁移，这样的卡片不能按 Quiz 屏蔽。
