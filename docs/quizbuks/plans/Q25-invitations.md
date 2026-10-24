# Q25 只开放给指定邮箱的发布（计划，2026-10-24 确认）

需求方（2026-10-24）：给发布增加一个标志——是否只许**指定的电子邮件**回答。设为指定邮箱的发布，"范围"等选项改成一份邮箱清单；
给清单中的每个地址发一封带发布链接的邮件，点链接直接进入答题页；提交答案时检查提交人在清单中；清单中的地址可以不是平台用户。
每封邮件要记录，答题后与答题的交易关联：能看到这个地址是否答了、什么时间答、得了多少分。清单可以不断补充；没有答题的可以删除。
确认（2026-10-24）：链接选**方案 B**（一封邮件、一次点击：邮件本身带一次性登录链接，平台 16ze / 决策 D55）；上限按提议（每个发布 5 000 个地址、每个商家每天 2 000 次发送）。

## 1. 已确认的决定

| # | 决定 |
|---|---|
| D-Q25-1 | **受众是发布的属性**：`QbPublication.audience` = `PUBLIC`（缺省，地区照旧）\| `INVITED`（只许清单中的地址）。向导中选定，上架时冻结到卡片 `QbPublicationCard.audience`。指定邮箱的发布不要求地区（`PublicationReadiness` 不再要求 `regions`），答题时不按地区过滤 |
| D-Q25-2 | **清单** `QbInvitation`（普通表 `qb_invitation`，只经流程写入）：发布 × 地址（小写、去空白）唯一；`addedAt`、`sentCount`、`lastSentAt`、`status`（`ADDED` / `QUEUED` / `SENT` / `STARTED` / `ANSWERED`）；认领后 `userId`，开始后 `attemptId`、`startedAt`，答题后 `answeredAt`、`score`、`reward`。草稿即可维护清单（上架前准备好）。实现中去掉了计划的 `lastMessageId`（平台的邮件编号对商家没有用处） |
| D-Q25-3 | **邀请邮件**是应用的 `NOTIFICATION` 模板 `qb.invitation`（`signInLink(30 天)`，决策 D55）：发给地址本身（`MailRecipient.address`），正文有 `{entryUrl}auth/email-link?entry=app&token={jabiz.signIn}&to=/p/<发布>` 与 `{unsubscribeUrl}`（按地址退订，退订的地址留在清单中、不再寄）。打开链接即 D54 的流程：已有账号登录、无人使用的地址开已验证的账号，然后回到 `/p/<发布>`；没有国家的答题人先到欢迎页的简短一步（同 Q15） |
| D-Q25-4 | **发送**只对 `LIVE` 的发布（`QB_INVITATION_SEND`；排期中的页面对受邀者也还不显示，寄出的链接会落到"不可用"）：把未寄过（`ADDED`）的全部标为 `QUEUED` 并计入配额，任务 `qb.invitation-mail`（每分钟）对每一行运行内部流程 `QB_INVITATION_MAIL`（各自一个事务，D42）寄出并标 `SENT`；寄出前在列表锁内重读行与卡片：行已删即无事可做，发布不再 `LIVE`（暂停、结束、下架）即不寄、行退回 `ADDED`。退订的地址照常标 `SENT`（平台的投递记为跳过，商家看不出；本阶段接受）。每个商家每天至多 `quizbuks.invitations.sends-per-day`（缺省 2 000）封（`QbInvitationQuota`：商家 × 日（`jabiz.fiscal-year-zone`）计数，超过即整体拒绝 422 `QB_INVITATION_QUOTA`）；每个发布至多 `quizbuks.invitations.max-per-publication`（缺省 5 000）个地址。两者是应用配置而不是业务参数（新增 `QB_SETUP` 的参数会改变全部场景快照的编号；需要运行时可改时再改为受控参数）。重发（寄给已 `SENT` 的）本阶段不做：商家可删除未开始的邀请再添加 |
| D-Q25-5 | **认领**没有单独的流程（实现时简化）：`QB_VIEW` 与 `QB_ATTEMPT_START` 对指定邮箱的卡片多读两条（调用者的账号与邀请行：按 `userId` 或账号的**已验证**邮箱（小写）；公开的卡片一条也不读，`Invitations.loadForTaker`），匹配到即把 `userId` 记到那一行；没有即 404（`QB_VIEW`）/ 422 `QB_NOT_INVITED`（开始）。App 的 `/p/<发布>` 记录浏览后重读页面，页面（`qb.app.publication` 连 `me:QbInvitation`）从此对受邀者显示。开始在开始锁内把邀请行改为 `STARTED` 并记 `attemptId`；之后邮箱改了也不影响已认领的邀请。被别的账号认领的邀请对本人不算 |
| D-Q25-6 | **关联**：会话完成（提交或超时）的同一事务里按 `attemptId` 读邀请行（只在卡片是 `INVITED` 时，一条语句）并改为 `ANSWERED`，记 `answeredAt`、`score`、`reward`。不经事件：公开发布的语句数不变（`AttemptStatementBudgetIT`） |
| D-Q25-7 | **清单维护**：`QB_INVITATION_ADD`（一次至多 500 个地址，前端按换行、逗号、分号、空白拆分；服务端规范化（小写、去空白）、同一调用内去重、已在清单中的跳过并计数；有一个不是地址即整体拒绝 422 `QB_INVITATION_EMAIL`，超过上限整体拒绝 `QB_INVITATION_LIMIT`）、`QB_INVITATION_REMOVE`（`STARTED` / `ANSWERED` 的拒绝，422 `QB_INVITATION_STATE`；删除 `QUEUED` 的，任务在列表锁内重读发现行已不在即不寄）。商家只能操作自己发布的清单（经商家视图读发布与邀请，别人的 404）；三个流程都在列表锁 `qb.invitation-list:<发布>` 内 |
| D-Q25-8 | **可见性**：指定邮箱的发布不出现在 App 首页（`qb.app.feed` 只取 `audience = PUBLIC`）、不在公开模板 `qb.public.publication`（公开视图固定范围加 `audience = PUBLIC`）；`qb.app.publication` 对指定邮箱的发布只在调用者有邀请行时返回。管理员的发布页显示受众与清单的人数（已加、已寄、已开始、已答，模板 `qb.admin.invitation-summary`），**不显示地址**；地址只有商家看得到。指定邮箱的发布也不能作商家通告的对象（`broadcast-publications.sql`、`BroadcastProcesses.publication`：其卡片的 `GLOBAL` 会让通告发给所有人） |
| D-Q25-9 | **商家看到的**：模板 `qb.sponsor.invitations`（地址、状态、加入、最后寄出与次数、答题时间、分数、奖励；按状态与文字筛选）、`qb.sponsor.invitation-summary`（四个人数）；导出经平台 `POST /api/queries/{id}/export` |

## 2. 改动

| 部分 | 改动 |
|---|---|
| 迁移 V26 | `qb_publication_version.audience`、`qb_publication_card.audience`（缺省 `PUBLIC`）；表 `qb_invitation`（唯一 `(publication_id, email)`、索引 `(sponsor_id)`、`(publication_id, user_id)`）；表 `qb_invitation_quota`（商家 × 日唯一） |
| 平台 | 16ze（D55）：`MailTemplate.signInLink`、`MAIL_SIGN_IN_LINK`、按地址退订 |
| 发布 | `QbPublications`（字段与字典 `publication-audience`）、`PublicationProcesses.SaveInput.audience`、`PublicationReadiness`（`INVITED` 不要求地区）、`PublicationEditing.card`（冻结 `audience`）、审批内容带 `audience` |
| 邀请 | 包 `invitation`：`QbInvitations`（实体、数据视图、状态与代码）、`InvitationProcesses`（`QB_INVITATION_ADD` / `REMOVE` / `SEND`，内部 `QB_INVITATION_MAIL` 与任务 `qb.invitation-mail`）、`Invitations`（答题流程读调用者的邀请）、`InvitationMails`（模板 `qb.invitation`）、`InvitationConfig`（含配置 `Limits`） |
| 答题 | `AttemptProcesses.start`（指定邮箱：读邀请行，`QB_NOT_INVITED`；不按地区）、`AttemptFinishing`（读邀请行、写 `ANSWERED`）、`AttemptRules.startRefusal`（`INVITED` 跳过地区） |
| 模板 | `qb.app.feed`、`qb.app.publication`、`qb.public.publication`（受众）；`qb.sponsor.publications`、`qb.admin.publications`（受众）；新 `qb.sponsor.invitations`、`qb.sponsor.invitation-summary`、`qb.admin.invitation-summary`（只有人数）、`qb.system.invitations-queued`（任务的项）；`qb.app.publication-tiers`、`qb.sponsor.broadcast-publications`（受众） |
| 前端 | 商家后台：向导第二步"受众"（公开 / 指定邮箱；指定时隐藏地区）、发布页的"邀请"区块（人数、添加（粘贴）、寄给未寄的、删除、按状态与文字筛选；导出与上传文件、重发留给以后）；App：`/p/<id>` 记录浏览后重读（浏览认领了邀请），页面注明"仅限邀请"，读不到时的提示加一句"受邀请的请打开邮件中的链接"，开始被拒时显示服务端的 `QB_NOT_INVITED`；后台扩展：审核页显示受众与清单的人数（不显示地址） |
| 消息 | `entity.QbInvitation.*`、`process.QB_INVITATION_*`、`code.QB_NOT_INVITED` 等、`query.qb.sponsor.invitations.*`、`mail.qb.invitation.*`（三语） |
| 配置 | `quizbuks.invitations.max-per-publication=5000`、`.sends-per-day=2000`；`jabiz.mail.unsubscribe-paths.app` 已有（D52） |
| 测试 | `InvitationIT`（添加 / 重复 / 格式 / 上限、寄送与每日配额（跨发布、次日恢复）、任务寄出带登录与退订链接的邮件、链接开户与登录（GreenMail）、浏览认领、开始与答题后的关联（分数、奖励）、删除已开始的被拒、删除后链接登录但不能答题、首页与公开模板不含、他人的 404、公开发布没有清单）；`ProcessAccessIT`；全部场景快照（卡片多出 `audience`）；Vitest（商家、App、扩展）；e2e `invitation.spec.ts`（商家建指定邮箱的发布、添加地址、寄出 → Mailpit 中的链接 → 答题 → 商家看到已答与分数） |

## 3. 风险

- 批量邮件的合规由商家负责（取得同意）；平台提供按地址退订（D55）。退订的地址留在清单中、状态照旧，只是不再寄。
- 登录链接有效 30 天：能读到邮件的人在此期间都能以该地址登录（同找回密码）；每个发布每人一次、奖励保留期照旧。
- 清单里的地址与账号的邮箱不同（别名、大小写）：按小写比较；用了别的邮箱登录的人看到 `QB_NOT_INVITED`，页面说明"用收到邀请的邮箱"。
