# Q82 答题者与商户的对话（计划，2026-11-02；需求方 2026-11-02 确认 §9 全部按建议）

`05-console-requests.md` 第五项（"允许答题者和商户之间手工通信"）；需求方定下的顺序 Q78 → Q79 → 17zc / Q80 → Q81 → **Q82**。编号 Q82 是建议（下一个空号），确认时可改。
**这只是计划**：确认 §9 的决定后开发，一个 PR，工作分支 `1.3/quizbuks-82-conversations`。

## 0. 为什么

- 现在答题者→商户没有渠道：只有给平台的举报（Q49a）与现金 / 兑换 / 电子奖品的争议状态（没有自由文字）；商户→答题者只有通告（Q7c，群发、纯文本、节流）。
  领奖出了问题（没收到码、到店没人认、现金没到），答题者只能"争议"，商户只能看状态猜原因，最后都落到平台的客服。
- 05 的方案是**有上下文的对话，不是开放私信**：只从具体的事（答过的发布、领奖记录）开启，只有答题者能开启，两边都看不到邮箱与用户编号，不许带联系方式，平台与运营商可读以处理纠纷。
  这样把客服负担留在商户那里，同时不把人带出平台（商户的信息只在 Kudos 的交易里才有价值）。

## 1. 现状

- 站内信 `QbMessage`（Q7c）只由事件消费者 `QB_MESSAGE_FROM_EVENT` 写（`MessageProcesses`，`Draft` → `insert`，标题正文按三语渲染，`sourceKey` 唯一，`action` / `actionId` 指向 App 的页面），中奖另推送 `PRIZE_WON`；
  通告 `QbBroadcast` 商户→答过题的人，文字规则 `BroadcastRules.hasAddress`（NFKC、去不可见字符、还原 `[.]` 等，识别域名、邮箱、电话、`@handle`、`LINE ID`）、`SPONSOR_BODY_LENGTH = 1000`、条数与间隔是普通参数。
- 举报 `QbReport`（`publicationId` 必填、`reason ∈ REASONS`、`note`），只经 `QB_REPORT_SUBMIT` / `_RESOLVE` / `QB_OPERATOR_REPORT_RESOLVE`，默认数据视图经 `OperatorScopes`。
- 奖励记录（现金、兑换、电子奖品）都有 `attemptId`、`publicationId`、`userId` 与发布方那一对；推荐 `QbReferral` 有 `publicationId` 与推荐人。结果页按状态显示它们（`result-reward`）。
- 店员（Q39）在 App 里处理兑换，没有角色；商户后台的"消息"页是通告列表（Q80 后为 `DataTable`）；App 的收件箱有 `useUnread` 徽标与按 `action` 的跳转（`inbox/api.ts` 的 `targetOf`）。
- 删除账号：答题者的 `TakerErasure`、商户的 `SponsorErasure`（`PersonalDataEraser`：`delete` / `keep(字段…)`）。

## 2. 决定（草案）

| # | 决定 |
|---|---|
| D-Q82-1 | **一个答题者与一个发布之间至多一个对话** `QbConversation`（发布方那一对、`takerId`、`publicationId`、`attemptId`（有则记）、`status` OPEN / CLOSED、`openedAt`、`lastMessageAt`、`lastSender`、`takerReadAt`、`sponsorReadAt`、`closedAt`、`closedBy` TAKER / SPONSOR / SYSTEM；唯一约束 `(taker_id, publication_id)`）。上下文就是这个发布：结果页、现金 / 兑换 / 电子奖品、推荐都是它的事，商户在对话旁看到该答题者在这个发布上的奖励记录与状态。普通表，只经流程写入。 |
| D-Q82-2 | 消息 `QbConversationMessage`（`conversationId`、发布方那一对、`sender` TAKER / SPONSOR、`senderUserId`（`processOnly`：商户侧是商户本人，对答题者只显示公司名）、`body` 纯文本 ≤ 1000、`createdAt`）。只插入。 |
| D-Q82-3 | **只有答题者能开启**（`QB_CONVERSATION_OPEN`：在该发布上有**已完成**的会话，或是它的推荐人；发布方是 `SPONSOR`——店面的发布没有对话；没有屏蔽；双方都没被封禁），第一条消息随开启发出；再次开启同一发布返回已有对话（幂等）。商户不能向没有对话的人发起（已有通告）。 |
| D-Q82-4 | **回复**（`QB_CONVERSATION_SEND`：答题者本人，或商户本人；**店员第一步不回复**——店员在 App 中、没有商户后台，接入另议）。文字规则 `ConversationRules`（纯 Java）：非空、≤ 1000、不许链接 / 邮箱 / 电话 / 社交账号（复用 `BroadcastRules.hasAddress`，`QB_CONVERSATION_ADDRESS`）；每人每对话每日 ≤ `qb.conversation.daily-cap`（普通参数，缺省 20，`QB_CONVERSATION_DAILY_CAP`）；对话 `CLOSED` 即不能发（`QB_CONVERSATION_CLOSED`）；封禁者不能发。 |
| D-Q82-5 | **关闭与到期**：双方都可关闭（`QB_CONVERSATION_CLOSE`）；答题者可**重开**已关闭的（再次 `OPEN` 即重开，商户不能）；`qb.conversation.close-days`（普通参数，缺省 90）天没有消息由逐项任务 `qb.conversation-expire`（每天，`QB_CONVERSATION_EXPIRE` `internal()`）关闭，`closedBy = SYSTEM`。 |
| D-Q82-6 | **屏蔽**（04 §5-K 的一半）：答题者 `QB_CONVERSATION_BLOCK` / `_UNBLOCK` 一个商户（`QbConversationBlock`：`takerId` + 发布方那一对，唯一）：屏蔽即关闭与该商户的全部对话、商户不能再发、答题者不能再开（先解除）；通告不受影响（它另有退订）。App 账户页"屏蔽的商户"列表。 |
| D-Q82-7 | **举报**复用 `QB_REPORT_SUBMIT`：理由新增 `MESSAGE`，输入加可选 `conversationId`（`QbReport` 加列，迁移），只接受本人的对话；审核者在后台扩展的举报页链到对话的只读页。 |
| D-Q82-8 | **身份**：答题者看商户的公司名（入驻 `company`，经发布卡片 / 入驻视图），商户看答题者的资料显示名（Q9b 的做法）；两边都看不到邮箱、用户编号、是否店员。模板从不返回 `senderUserId`。 |
| D-Q82-9 | **通知**：商户的回复经事件 `qb.conversation.message` 由 `QB_MESSAGE_FROM_EVENT` 写一条站内信（种类 `CONVERSATION_REPLY`，`action = CONVERSATION`，`actionId` = 对话编号；`sourceKey = conversation:<编号>:<UTC 小时>`：每对话每小时至多一条）并推送（`PushTemplate` `qb.conversation-reply`，入口 `app`，与中奖同一开关）；答题者的消息**不给商户写站内信、不发邮件**：商户后台"消息"页的"对话"标签与菜单徽标（模板 `qb.sponsor.conversations-unread`）即通知。 |
| D-Q82-10 | **可读范围**：默认数据视图 `processOnlyWrites`，读权限 `qb.conversation.read`（内容管理员、运营商；运营商经 `OperatorScopes` 按 `publisherId`）；答题者与商户只经模板读自己的（答题者 `qb.app.conversations` / `qb.app.conversation`，商户 `qb.sponsor.conversations` / `qb.sponsor.conversation` / `qb.sponsor.conversations-unread`），模板按会话身份限定。审计照常。 |
| D-Q82-11 | **删除账号**：答题者 `keep(QbConversation, takerId)`、`keep(QbConversationMessage, senderUserId, body)`（文字清空、留行）、`delete(QbConversationBlock, takerId)`；商户 `keep(QbConversationMessage, senderUserId, body)`（商户自己发的）。没有保留期（随账号；对话不是账务）。 |
| D-Q82-12 | **锁**：`qb.conversation:<编号>`（发送、关闭、已读）；开启取 `qb.conversation-open:<答题者>:<发布>`（再读已有的，唯一约束兜底）；屏蔽取 `qb.account:<答题者>` 再逐个取 `qb.conversation:<编号>`（按编号升序）。不与发布锁、开始锁、钱包锁同时持有；**答题路径一条语句也不加**（`AttemptStatementBudgetIT` 不变）。限流 `QB_CONVERSATION_SEND` 每人每小时 30、`QB_CONVERSATION_OPEN` 每人每小时 10（`jabiz.process.rate-limits`）。 |
| D-Q82-13 | 不允许图片与附件；不显示"通常 x 天内回复"（不承诺）；商户可在对话旁看到该答题者在这个发布上的奖励记录（现金 / 兑换 / 电子奖品的状态与码的末 4 位），不看其他发布。 |

## 3. 后端（`backend/quizbuks`）

### 3.1 迁移 V63 `quizbuks_conversations`（Q77 用了 V61，Q81 用了 V62）

- `qb_conversation`（主键 `conversation_id`，`publisher_kind` / `publisher_id`、`taker_id`、`publication_id → entity_registry`、`attempt_id`、`status`、`opened_at`、`last_message_at`、`last_sender`、`taker_read_at`、`sponsor_read_at`、`closed_at`、`closed_by`、`version`；`UNIQUE (taker_id, publication_id)`；索引 `(publisher_id, status, last_message_at DESC)`（商户列表）、`(taker_id, last_message_at DESC)`（答题者列表）、`(status, last_message_at)`（到期任务）。
- `qb_conversation_message`（主键 `message_id`，`conversation_id → qb_conversation`、`publisher_kind` / `publisher_id`、`sender`、`sender_user_id`、`body varchar(1000)`、`created_at`、`version`；索引 `(conversation_id, created_at)`）。
- `qb_conversation_block`（主键 `block_id`，`taker_id`、`publisher_kind` / `publisher_id`、`created_at`、`version`；`UNIQUE (taker_id, publisher_kind, publisher_id)`）。
- `qb_report ADD COLUMN conversation_id varchar(36)`。

### 3.2 包 `conversation`

- `QbConversations`（三个实体，`PublisherKind.declare`，字典 `urn:jabiz:dict:quizbuks:conversation-status|sender`；默认数据视图 `processOnlyWrites`、读 `qb.conversation.read`；登记 `OperatorScopes`）。
- `ConversationRules`（纯 Java：文字、每日条数、能否开启 / 发送 / 关闭的判断）、`ConversationCodes`（`QB_CONVERSATION_NOT_ELIGIBLE`、`_BLOCKED`、`_CLOSED`、`_ADDRESS`、`_DAILY_CAP`、`_NOT_YOURS`、`_SPONSOR_ONLY`、`_LIMITS`）、`ConversationLimits`。
- `ConversationProcesses`：`QB_CONVERSATION_OPEN`（答题者：锁外读本人在该发布上的会话 / 推荐、卡片（发布方）、屏蔽、封禁；锁内读已有对话；插入对话 + 第一条消息；发布事件不需要（商户没有站内信））、`QB_CONVERSATION_SEND`（双方：锁内读对话、当天条数；插入；商户发的发布 `qb.conversation.message`）、`QB_CONVERSATION_CLOSE`、`QB_CONVERSATION_READ`（更新本侧 `*ReadAt`）、`QB_CONVERSATION_BLOCK` / `_UNBLOCK`、`QB_CONVERSATION_EXPIRE`（`internal()`）。
  每个流程 `requiresVerifiedEmail()`、`CallerChecks::refuseBanned`；对话的归属以模板 / 锁内读取核对（不是本人的一律 404 `QB_CONVERSATION_NOT_YOURS`）。
- `ConversationConfig`：Bean、任务 `qb.conversation-expire`（`JobDefinition.forEach`，模板 `qb.system.conversations-due`）、事件订阅（`QB_MESSAGE_FROM_EVENT` 加输入种类 `CONVERSATION_REPLY`）、推送模板。
- `QbParams`：`qb.conversation.daily-cap`（20）、`qb.conversation.close-days`（90），普通参数，加进 `ALL`。`QbRoles`：`qb.conversation.read` 给内容管理员与运营商（`OPERATOR_PERMISSIONS`，`QbRolesTest` 清单）；答题者与商户不需要新权限（流程用 `qb.play`/`qb.me` 与 `qb.publication.write`？——用现有的 `qb.play`（答题者）与 `qb.content.read`（商户），不加权限）。

### 3.3 其他包

- `messaging`：`QbInbox` 加 `CONVERSATION_REPLY`、`ACTION_CONVERSATION`；`MessageProcesses` 加该种类的读取（对话 + 卡片）、文案 `qb.msg.CONVERSATION_REPLY.title|body`（"{公司} 回复了你关于《{发布}》的留言"）、推送 `qb.conversation-reply`；`InboxMatchIT` 不变（个人消息）。
- `report`：`REASONS` 加 `MESSAGE`；`SubmitInput` 加 `conversationId`（可选，核对是本人的）；`qb.admin.reports` 返回 `conversationId`。
- `identity`：两个 `PersonalDataEraser` 加 D-Q82-11 的条目；`ErasureIT` 的全表搜索覆盖三张表。
- 模板：`qb.app.conversations`（本人的列表：发布名、公司、最后一条、未读）、`qb.app.conversation`（一个对话与消息，`sender`、`body`、`createdAt`；不返回 `senderUserId`）、`qb.app.conversation-blocks`；`qb.sponsor.conversations`（筛选 status / 未读，`DataTable` 卡片标注）、`qb.sponsor.conversation`（消息 + 该答题者在此发布上的奖励记录）、`qb.sponsor.conversations-unread`；`qb.system.conversations-due`。
- `application.yml`：两条限流。

### 3.4 文案

- 代码三语；`entity.QbConversation*`；`qb.msg.CONVERSATION_REPLY.*`；`push.qb.conversation-reply.*`；`param.qb.conversation.*`。

## 4. 前端

### 4.1 App（`quizbuks-web/app`）

- 结果页：有已完成会话且发布方是商户时显示"联系商户"（`result-contact-sponsor`）：对话框写第一条消息 → `QB_CONVERSATION_OPEN` → 跳到对话页；已有对话时按钮为"查看对话"。推荐页同。
- `/conversations`（列表，收件箱页加"对话"标签或入口；`useUnread` 的徽标合计对话未读）、`/conversations/<id>`（消息按时间、发送框、关闭、举报（理由 `MESSAGE`）、屏蔽）；进入即 `QB_CONVERSATION_READ`。收件箱的 `targetOf` 加 `CONVERSATION`。
- 账户页"屏蔽的商户"（列表 + 解除）。
- 文案三语（`conversation.*`）。

### 4.2 商户后台（`quizbuks-web/sponsor`）

- "消息"页改为 Tabs：通告（现页）| 对话（`DataTable`，`meta.card`：答题者为标题、发布为副标题、状态徽章、未读徽章、最后一条时间；筛选 全部 / 未回复 / 已关闭）；对话页 `/messages/conversations/<id>`：消息、发送框、关闭；右栏该答题者在此发布上的奖励记录（状态、码末 4 位、现金 / 兑换的操作链到现有页）。
- 菜单徽标：`qb.sponsor.conversations-unread`（进入、回到前台、每 5 分钟，照 App 的 `useUnread`）。
- 文案三语。

### 4.3 后台扩展

- 举报页：理由 `MESSAGE` 的举报链到 `/qb/conversations/<id>`（只读：双方消息、状态；用户编号以 `UserName` 显示）。

## 5. 测试

- core：`ConversationRulesTest`（文字、地址、每日条数、状态转移）。
- IT：`ConversationIT`（开启的条件：未完成不能开、店面发布不能开、屏蔽后不能开、幂等；发送的规则与每日条数；商户回复写站内信（每小时一条）与推送；关闭 / 重开；到期任务；屏蔽关闭全部对话；模板只给本人的、不返回 `senderUserId`；运营商按范围读；举报带对话编号；`AttemptStatementBudgetIT` 不变）、`ErasureIT` 覆盖、`QbRolesTest` 清单。
- 前端：App 对话页与结果页按钮、商户后台对话标签与徽标、后台扩展只读页；axe 各一页。
- e2e：答题者在结果页发起、商户在后台回复、答题者收件箱收到并打开（`conversations.spec.ts`）。

## 6. 文档

- `02-design.md` §7 一段、`backend/quizbuks/CLAUDE.md` 第 4 节"对话"一条、`05-console-requests.md` 第五项与 `03-plan.md` 的 Q82 行；本文件 §10 实施记录。
- **产品文本**（需求方 / 法务）：条款与隐私政策各补一条（对话内容平台可见、用于纠纷处理、不含联系方式；日文为准），由内容管理员经 `QB_LEGAL_PUBLISH` 发布，不在代码中。

## 7. 估计

| 部分 | 天 |
|---|---|
| 迁移、实体、规则、七个流程、任务、事件与推送、参数与权限 | 2.5 |
| 模板与文案 | 0.5 |
| App（结果页入口、列表、对话页、屏蔽） | 1.5 |
| 商户后台（标签、列表、对话页、徽标） | 1.5 |
| 后台扩展、举报 | 0.5 |
| 测试、e2e、文档 | 1 |
| **合计** | **7.5** |

## 8. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 中 | 骚扰与把人带出平台 | 只有答题者能开、纯文本、地址规则、每日条数、屏蔽、举报带对话；平台与运营商可读 |
| 中 | 商户不回复，答题者期待落空 | 不承诺时限；入口只在领奖相关处；未回复的在商户列表置顶并进徽标 |
| 低 | 运营商读取范围 | `OperatorScopes` 登记 + `OperatorIsolationIT` 加对话 |
| 低 | 店员不能回复（D-Q82-4） | 第一步只商户本人；店员的接入（App 中的"我的交付"旁）另议 |
| 低 | 两端新页面较多 | 列表复用 `DataTable`（Q80）、消息列表照收件箱写 |

## 9. 需要需求方确认的

1. D-Q82-1 / D-Q82-3：一个答题者与一个发布至多一个对话，只有答题者能开启（商户不能发起），店面的发布没有对话。
2. D-Q82-4：店员第一步不回复，只商户本人；文字规则同通告，每日条数 20。
3. D-Q82-5 / D-Q82-6：90 天无消息自动关闭；答题者可屏蔽商户（关闭全部对话，通告不受影响）。
4. D-Q82-9：商户的回复给答题者站内信 + 推送（每对话每小时一条）；答题者的消息只在商户后台显示，不发邮件。
5. D-Q82-13：不允许图片；不显示回复时限。
6. 条款与隐私政策的补充文本由需求方 / 法务给出。
7. 编号 Q82，一个 PR，7.5 天（05 的估计 5–6 天没算屏蔽、举报链接与后台只读页）。
