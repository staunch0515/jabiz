# F-C3 共享视频（资源库一侧）：实施计划

依据：`docs/quizbuks/first-launch-spec.md` F-C3（以它为准，含"总控 2026-11-08 定"五条）；F-S12（`F-S12.md`）已做完共享、审核、目录、撤回、下架与卡片冻结，本项只补资源库一侧与运营闭合，规则以 F-S12 为准。
依赖：F-S12（#545，包 `sharedvideo`）、F-C1（资源库）、F-S1（每个发起方都有运营商）、Q7（站内信 `messaging`）、Q2 / Q48a（封禁）。
会话 S3，分支 `1.3/quizbuks-fc3-shared-catalog`（基于含 #545 的 `1.3/quizbuks`）。

## 0. 范围

一个 PR 做完规格 F-C3 中 F-S12 没有的部分：目录排除本人的视频、管理系统详情页列出用过它的发布、驳回与下架的站内信、封禁即撤回、资源页的共享状态，以及规格与文档的对齐（流程名、直营的审核、下架的处理）。
费用（曝光费）仍随 F-M8 与 Q112a 的其他部分，不在本项。

**删除的功能：无。** 已有的行为只多几点：目录不再列调用者本人的共享视频（按编号查草稿所选的照常）；封禁多撤回共享视频；提供方多收到站内信；共享在提供方的共享锁内（而不是锁前）检查封禁。

**迁移：无。** 站内信的种类是 `qb_message.kind`（`varchar(30)`，无检查约束）与字典，新模板只读已有的列与部分索引。

## 1. 规则（总控 2026-11-08 的五条）

1. **目录不列本人的视频**：`qb.sponsor.shared-videos` 不带 `sharedVideoId` 时只列 `APPROVED` 且提供方不是调用者的视频。调用者由 `me:QbProfile`（只含调用者本人的资料；发起方在入驻申请时写入）认出：`NOT EXISTS (SELECT 1 FROM me:QbProfile WHERE userId = providerId)`。按编号查（草稿已选的那一个）照旧不论状态与提供方，以前选了自己视频的草稿照常显示它变成了什么（不破坏已有草稿；提交照 F-S12 只查状态）。模板只能经 `me:` 数据视图知道调用者（平台模板没有调用者参数，数据视图的范围也没有"不等于"），`QbProfile` 是现成的、每个发起方都有的一行，不新建数据视图。
2. **下架照 F-S12 自动结束**（任务 `qb.shared-video-ends`，原因 `SHARED_VIDEO`，不算违约，双方邮件），规格中"列给运营商、由运营商以暂停流程处理"的写法改掉。另加**用过它的发布**：新模板 `qb.admin.shared-video-uses`（`qb.admin.shared-video.read`），按卡片冻结的 `sharedVideoId`（部分索引）列出发布、发布方、当前状态、结束原因与时间；卡片与发布的缺省数据视图都按 `OperatorScopes` 限定，所以运营商只看到自己发起方的发布（不论视频是谁提供的），总部看到全部。管理系统扩展的 `SharedVideoPage` 多一节"用过它的发布"（名称链到发布的审核页、`PublisherLink`、状态、结束原因与时间）。
3. **站内信**：`QB_SHARED_VIDEO_APPROVAL_RESULT` 驳回时、`QB_SHARED_VIDEO_TAKEDOWN` 下架时在同一事务中发应用事件 `qb.shared-video.rejected` / `qb.shared-video.taken-down`（载荷 `sharedVideoId`、`providerId`）；消费者 `qb.msg-shared-video-rejected` / `qb.msg-shared-video-taken-down` 以 `QB_MESSAGE_FROM_EVENT` 做提供方的站内信（种类 `SHARED_VIDEO_REJECTED` / `SHARED_VIDEO_TAKEN_DOWN`，按视频的当前状态再查一次，标题按语言取视频的标题，正文带理由，动作 `ASSETS` 链到资源库；来源键按视频，一条视频只一条）。下架照旧另发邮件 `qb.shared-video.taken-down`。
4. **封禁即撤回**：`QB_USER_BAN` 与 `QB_OPERATOR_SPONSOR_BAN`（同一个定义）在"撤回其通知"之后、封禁成立时：取提供方的共享锁 `qb.shared-videos:<提供方>`，读其 `PENDING` / `APPROVED` 的共享，按编号升序以子流程 `QB_SHARED_VIDEO_ERASED_WITHDRAW` 逐个撤回（待审核的连同审批）。子流程沿用（不改名：它是只作子流程的流程，名字记在操作记录里；改名对已有记录不兼容，收益只是名字），说明改为"删除账号或封禁"。锁序与删除账号相同：账户 → 发布 → 共享锁 → 视频锁 → 审批。已提交的发布照常进行；已驳回、已下架的不动；解封不恢复。
   **竞态**：共享原先在取共享锁之前查封禁；改为在共享锁之内查（`CallerChecks.refuseBanned` 移到锁后）。封禁先取同一把锁再读共享：两者谁先拿到锁，另一方都看到对方的结果（共享看到封禁而被拒，或封禁读到新的共享而撤回它）。
5. **按规格补齐**：
   - 资源页（`AssetsPage`）视频链接一行多一栏"共享"：按 `qb.sponsor.my-shared-videos` 取该资源**最近一次**共享（`sharedAt` 最大，`latestShares`）的状态，用 `SharedStatus` 徽章（没有共享时"未共享"）；"共享"按钮只在没有"审核中或已共享"的共享时出现（被驳回、已撤回、被下架后可以再共享；列表没读到时照旧显示，服务器本来就拒绝第二个）。
   - 直营：F-S1 让每个发起方都有运营商（`SponsorProcesses.assignOnFirstApplication`），总部（不带入口范围的会话）照平台的审批规则能看到并决定任何运营商范围内的案件（`ApprovalCase.scope`）：不改代码，写进规格，IT 断言总部决定、审核待办（平台 `ApprovalTasks`，来源键 `approval:<请求>`）随共享开立、决定或撤回时关闭。
   - 规格的流程名改为代码中的：`QB_SHARED_VIDEO_SHARE`、`QB_SHARED_VIDEO_WITHDRAW`、平台 `APPROVAL_DECIDE` + `QB_SHARED_VIDEO_APPROVAL_RESULT`、`QB_SHARED_VIDEO_TAKEDOWN`、`QB_SHARED_VIDEO_ERASED_WITHDRAW`。
   - 被封禁的提供方的视频不在目录中：撤回后即不是 `APPROVED`（IT 断言）。
   - 发布向导的目录对话框说明"这里不列出您自己的视频"。

## 2. 改动

| 位置 | 改动 |
|---|---|
| 模板 `qb.sponsor.shared-videos` | 实体多 `QbProfile`（`me:`）；不带编号时排除调用者本人的视频 |
| 模板 `qb.admin.shared-video-uses`（新） | 用过一个共享视频的发布（卡片 + 发布的当前版本），`qb.admin.shared-video.read`，运营商范围 |
| `sharedvideo.SharedVideoProcesses` | 事件 `REJECTED_EVENT` / `TAKEN_DOWN_EVENT`（审批结果驳回时、下架时，同一事务）；共享在锁内查封禁；`ERASED_WITHDRAW` 的说明含封禁 |
| `identity.BanProcesses` | 封禁成立时取共享锁、读共享、`CallProcess.forEach(ERASED_WITHDRAW)` |
| `messaging.QbInbox` / `InboxConfig` / `MessageProcesses` | 种类 `SHARED_VIDEO_REJECTED`、`SHARED_VIDEO_TAKEN_DOWN`（字典三语）；两个订阅；`QB_MESSAGE_FROM_EVENT` 多读一个共享视频（同一条 `QueryEntities.all`，其他种类是空的 `IN ()`）、`sharedVideoMessage` |
| 消息 `messages_{zh,ja,en}` | `qb.msg.SHARED_VIDEO_*.title|body`、`query.qb.admin.shared-video-uses.*`、`process.QB_SHARED_VIDEO_ERASED_WITHDRAW` 改为"删除或封禁" |
| 发起方页面 | `assets/AssetsPage.tsx`（共享一栏、按钮条件）；`sharedvideos/api.ts`（`latestShares`）；`sharedvideos/messages.ts`（`column`、`columnNone`、`picker.ownHint`，三语）；`SharedVideoField` 的目录说明 |
| 管理系统扩展 | `SharedVideoPage`（"用过它的发布"一节）、`shared-video-api.ts`（`readSharedVideoUses`）、`ids.ts`（模板、结束原因字典）、`shared-video-messages.ts`（三语） |
| 文档 | 规格 F-C3（先改）；本计划；`operations.md` §7.28；帮助 `help/shared-videos.md`（三语：资源页的状态、站内信、目录不列本人的、封禁）；`backend/quizbuks/CLAUDE.md` 共享视频一条；进度表 |

## 3. 语句数

答题路径一条语句也不加（开始与提交不读共享视频；`AttemptStatementBudgetIT` 不变，已跑）。
封禁多三步：取共享锁（一条）、读共享（一条）、每个站着的共享一个子流程（锁、读、写，审核中的另撤回审批）；封禁被拒时锁名为空、查询是空的 `IN ()`。共享多不了语句（查封禁只是挪到锁后）。审批结果驳回与下架各多写一条 Outbox（随事务写出）。站内信的流程在既有的一条 `QueryEntities.all` 中多一个子查询。

## 4. 测试

- `SharedVideoIT`（F-S12 的类，加断言与一个测试）：
  - `aProviderSharesAVideoItsOperatorReviewsItOnceAndOthersPickIt`：审核待办随共享 `OPEN`、总部的审核人决定后 `DONE`（驳回同样）；提供方自己的目录不列自己的视频，按编号仍查得到；驳回的站内信（种类、动作 `ASSETS`、三语标题、理由），`qb.sponsor.inbox` 列出它；通过不发站内信。
  - `aTakedownEndsEveryPublicationUsingItAndTellsBothSides`：提供方的下架站内信（理由）；`qb.admin.shared-video-uses` 列出两个被结束的发布（`ENDED` / `SHARED_VIDEO`、结束时间、发布方），不给编号即空。
  - `anOperatorReviewsAndTakesDownItsOwnSponsorsVideosOnlyThePlatformSeesAll`：使用列表总部看到、运营商只在使用方是自己的发起方之后看到、别的运营商看不到、发起方 403。
  - `aBannedProvidersVideosAreWithdrawnWhatWasSubmittedKeepsThem`（新）：总部的 `QB_USER_BAN` 与运营商的 `QB_OPERATOR_SPONSOR_BAN` 各一遍：审核中（审批撤回、待办关闭）与已共享的撤回，已驳回、已下架的不动，子流程在封禁之内（每个站着的共享一个），目录不再列出，已提交的发布照常 `LIVE`，新草稿选不了。
- 前端：发起方页面 `assets.test.tsx`（资源行的状态按最近一次共享，未共享 / 审核中 / 已共享 / 被驳回 / 被下架 / 撤回后再共享；按钮只在没有站着的共享时出现；非视频链接没有；axe 亮暗）；`sharedvideos.test.tsx`（目录说明不列本人的）；管理系统扩展 `sharedVideos.test.tsx`（"用过它的发布"：链接、结束原因与时间、下架后使用中为 0，空列表的说明，axe）。
- e2e `quizbuks-web/e2e/tests/shared-video.spec.ts`（本机跑过）：在 F-S12 的流程中加：资源行"审核中"且没有"共享"；通过后提供方的目录不列、使用方的列；撤回后资源行"已撤回"且可再共享；下架后资源行"被下架"、提供方的消息中心有带理由的站内信并链到资源库（亮、暗 axe）；平台管理系统详情页列出被结束的发布（`SHARED_VIDEO`）。
- 场景快照：无变化（`QB_SETUP` 不变）。

## 5. 运维

- 部署后不需要 `QB_SETUP`（没有新权限、参数、规则）；没有迁移。
- `operations.md` §7.28：直营由总部也能审核、审核待办的开关、驳回与下架的站内信、"用过它的发布"一节代替手查 SQL、封禁即撤回与解封不恢复。

## 6. 决定

- 总控 2026-11-08 的五条见 §1。
- 本项自己的决定（不涉及需求）：
  1. 调用者经 `me:QbProfile` 认出（不新建数据视图）；万一某个发起方没有资料行，目录照旧列出他自己的视频（只是方便上的放宽，提交与审核的规则不变）。
  2. `QB_SHARED_VIDEO_ERASED_WITHDRAW` 不改名，说明改为"删除账号或封禁"。
  3. 共享改为在共享锁内查封禁，以关闭"封禁与共享同时进行"的竞态。
  4. 资源页"共享"按钮在共享列表读失败时照旧显示（不减少已有功能；服务器拒绝第二个）。
  5. 使用列表按卡片（上架过的发布）列出：审核期间被下架而退回草稿的发布没有用过它，不列。

## 7. 以后

- F-M8 / Q112a：下架结束的发布的费用；被使用的次数与统计（F-S14 的台账）。
- 解封后是否自动恢复被撤回的共享：规格没有要求，现在不恢复（提供方重新共享、重新审核）。
