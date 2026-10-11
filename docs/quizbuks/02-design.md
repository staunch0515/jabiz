# QuizBuks 设计

依据：`01-requirements-analysis.md`（含第 0 节已确认的决定）与需求原文。本文件是 QuizBuks 的应用设计；
它依赖的平台能力（第 10 节，G*）在 `1.2/platform` 上另写平台设计与决策（`docs/design/`、`09-decisions.md`），不在本文件中展开。

未回答的问题按下列**缺省**设计，回答后再改（文中以【缺省 Qn】标出）：

| 问题 | 缺省 |
|---|---|
| Q0 产品名 | 显示名 QuizBuks（消息资源中一处可改） |
| Q7 地区 | 国家字典给出每个国家所属的地区（一个国家可属多个：日本 ∈ 日本、亚洲、全球）；发布可选多个地区；用户可见 ⇔ 用户国家的地区与发布地区有交集 |
| Q8 多选题 | 有多个正确选项即多选题；全对才得该题分数，无部分得分；选项顺序按答题会话打乱 |
| Q9 定位 | 浏览器定位（需授权）只用于预填国家 / 城市，用户可改；登录审计只记 IP 与 User-Agent，不做 IP 地理定位 |
| Q11 规模 | 单个发布峰值 50 次提交 / 秒、同时进行的发布 ≤ 1 000、用户 ≤ 100 万；按此选锁粒度（4.3） |
| Q12 会话 | "5 分钟无活动"指**答题会话**之外的后台会话：商家与管理员后台闲置 30 分钟锁定，App 闲置不锁（刷新令牌 30 天）；答题会话按时限结束 |
| Q13 会计系统 | 先做通用 CSV 凭证导出；推送 API 以后按选定的系统加 |
| Q14 商家退款 | 不做（以后加"商家退款"交易与分录） |
| Q15 数据迁移 | 从零开始，不迁移原型数据 |
| Q16 语言 | App：英 / 中 / 日；商家后台：中 / 英；Quiz 内容单一语言（商家用什么语言写就是什么语言） |
| Q18 Stripe（已答） | 当前为 Stripe 测试模式；正式时每个部署（美国、日本）各用本国的 Stripe 账户，配置随部署。转账先实现 Stripe Connect（Express 账户），仍经接口 `PayoutGateway` 调用 |
| Q19 AI 费用 | 不收费，只记用量（按商家、按月汇总） |

---

## 1. 总体结构

```
backend/quizbuks/            Gradle 模块（jabiz.boot-app 插件），com.jabiz.quizbuks
  src/main/java/…/identity    注册入口的应用部分（资料、商家入驻）
  …/content                   模版、版本、题目、资料、AI
  …/publishing                发布、审核、状态机
  …/play                      答题会话、评分、结算
  …/wallet                    钱包、充值、转账、账本分录、对账
  …/messaging                 站内信、公告、帮助内容
  …/stats                     统计与分析模板
  src/main/resources/queries/qb/**.sql      SQL 模板与报表
  src/main/resources/db/migration/          应用迁移（`V<n>__quizbuks_<名>.sql`，自 V1 起；平台迁移有自己的历史表，互不冲突）
  src/test/resources/scenarios/qb/**.yml    场景回放
quizbuks-web/                应用前端（pnpm 工作区，`@jabiz/ui` 与 `@jabiz/client`，与平台同一工具链，D34）
  app/                       答题人 PWA（挂在 /）
  sponsor/                   商家后台（挂在 /sponsor/）
deploy/quizbuks/             docker-compose 与部署说明
docs/quizbuks/               本文件等
```

一个可部署的 jar 提供三个前端与接口：

| 路径 | 前端 | 登录入口（G1） | 接受的角色 | 自助注册（授予） | 要求已验证才能登录 |
|---|---|---|---|---|---|
| `/` | `quizbuks-web/app`（PWA） | `app` | `QB_TAKER` | 是（`QB_TAKER`，`QB_APP_SELF_REGISTRATION`） | 否：未验证可登录浏览，QuizBuks 的写入一律要求已验证 |
| `/sponsor/` | `quizbuks-web/sponsor` | `sponsor` | `QB_SPONSOR`（且入驻已通过才能发布） | 是（`QB_SPONSOR`，`QB_SPONSOR_SELF_REGISTRATION`） | 是 |
| `/admin/` | 平台通用后台 + QuizBuks 扩展页 | `admin` | `ADMIN`（首个管理员）/ `QB_ADMIN_SUPER` / `QB_ADMIN_CONTENT` / `QB_ADMIN_FINANCE`（`QB_ADMIN_ROLES` 可改） | 否 | 否 |

三个入口**分开登录**：入口决定接受哪些角色，签发的令牌只带这些角色的权限（同一邮箱既是答题人又是商家时，在 App 中拿不到商家权限，反之亦然）。
入口的机制属于平台（G1），本应用只声明三个入口与各自的角色（`application.yml`，Q2 D-Q2-1）。"待激活"即邮箱未验证（不另设状态）。

## 2. 角色与权限

| 角色 | 权限（应用自定义的权限码，前缀 `qb.`） |
|---|---|
| `QB_TAKER` | `qb.play`（浏览发布、答题）、`qb.me`（本人资料、钱包、流水、消息）、`qb.payout.onboard`（开通收款账户）、`qb.account.read`（本人资料与登录记录，Q2）、`qb.sponsor.avatar.read`（发布人头像，Q2） |
| `QB_SPONSOR` | `qb.sponsor.me`、`qb.content.write`、`qb.content.file.read`（读内容文件，Q3；Q5 再授予答题人）、`qb.ai.use`、`qb.publication.write`、`qb.topup`、`qb.sponsor.finance.read`、`qb.broadcast.write`、`qb.account.read`、`qb.sponsor.avatar.read` |
| `QB_ADMIN_CONTENT` | `qb.admin.users.read`、`qb.admin.quiz.read`（只读全部模版与版本，Q3）、`qb.content.file.read`、`qb.review.sponsor`、`qb.review.publication`、`qb.admin.content.write`（FAQ、条款、公告）、`qb.sponsor.avatar.read`、`approval.decide`、`task.read` |
| `QB_ADMIN_FINANCE` | `qb.admin.users.read`、`qb.admin.finance.read`、`qb.payout.review`、`qb.reconcile`、`qb.sponsor.avatar.read`、`ledger.read`、`report.issue`、`approval.decide`、`task.read`、`control.publish`（发布受控变更，超级管理员不持有）|
| `QB_ADMIN_SUPER` | 上述全部 + `qb.admin.users.ban`、`platform.param.read`、`control.propose`、平台安全管理权限（不再有 `platform.param.write`，见下） |

- 另有两个公用权限（Q1）：`qb.country.read`（读国家字典，五个角色都有）、`qb.setup`（执行 `QB_SETUP`，超级管理员；平台的 `ADMIN` 角色持有全部权限）。
- 不授予任何角色的权限：`qb.content.view.write`（数据视图的写权限，Q3、Q4）、`qb.content.system`（`QB_QUIZ_RELEASE_FILES`，只由 `QB_QUIZ_DELETE` 作为子流程运行）、`qb.publication.system`（Q4 的系统流程）、`qb.sponsor.system`（`QB_SPONSOR_APPROVAL_RESULT`，Q2）。
  平台 D41（16l）已合入：`QB_SPONSOR_APPROVAL_RESULT` 与 Q4 的系统流程声明 `internal()`（只允许系统身份运行），`QB_QUIZ_RELEASE_FILES` 声明 `subProcessOnly()`；这些系统权限随之只起声明作用。
- 文件策略只能声明一个读取权限，所以商家头像的读取（商家本人、答题人、管理员）共用 `qb.sponsor.avatar.read`（Q2）。
- 行范围：答题人的数据视图以 `userId = 当前用户` 限定；商家的以 `sponsorId = 当前用户` 限定（平台 `fromContext("…", actorId)`）。
- 职责分离：大额转账的审核人不能是被转账人本人（平台 SoD 规则）；修改抽成比例、转账门槛等参数走四眼（`CONTROL_CHANGE_*`）：
  平台决策 D40，`QbParams.CONTROLLED`（抽成比例、转账门槛、人工审核额、转账开关、两个免审核开关）以 `ControlledParams` 声明，一切直接写入 422 `PARAM_CONTROLLED`。
  `QB_SETUP` 以受控变更提出它们的首个值，由另一位管理员发布。没有角色持有 `platform.param.write`；`control.publish` 只授予 `QB_ADMIN_FINANCE`（2026-10-10 确认）：超级管理员提议、财务管理员发布，没有角色同时持有两者。新装系统只有一位管理员时，受控参数在第二位（财务）管理员发布前没有值（已接受）。
- 二次验证：管理员角色要求二次验证（`SecRole.requireMfa`）；商家可自行开启。

## 3. 领域模型

记号：**T** = 时态实体（只追加，有历史）；**W** = 只写一次（`t.writeOnce()`）；**P** = 普通表（可更新，平台的非时态实体，用于高频计数与工作状态）。
金额字段一律 `Monetary(JPY, scale 0)`，界面显示为 "Kudos"（见 7.4）。时间一律来自注入的 `Clock`。

### 3.1 账户与资料

| 实体 | 类型 | 主要字段 | 说明 |
|---|---|---|---|
| `QbProfile` | T | 主键 `profileId`；`userId`（文本，不可变、唯一、有索引）、`displayName`、`countryCode`（字典，可空）、`city`、`preferredCurrency`（USD/EUR/GBP/JPY/CNY/INR，只用于显示参考，可空）；`processOnly`：`createdAt`（资料第一次保存的时间；管理员页面的"注册时间"即此，商家入驻建立的只有名字的资料也算）、`editedAt`、`revision` | 答题人与商家共用的资料（商家只有名字，由入驻写入）；激活即邮箱已验证，由平台（G1）管；封禁另见 `QbUserBan` |
| `QbSponsor` | T | 主键 `onboardingId`；`userId`（文本，不可变、唯一）、`contactName`（商家的名字，随入驻审核）、`company`、`intro`（Markdown）、`avatar`（文件，策略 `qb.sponsor-avatar`）；`processOnly`：`reviewStatus`（PENDING / APPROVED / REJECTED，状态机）、`termsAcceptedAt`、`approvalRequestId`、`submittedAt`、`approvedAt`、`rejectReason`、`rejectedAt`、`revision`、`editedAt` | 商家入驻（审批对象 `qb.sponsor`）；发布人的公开信息来自这里（U-10 发布人名称 / 头像） |
| `QbUserBan` | T | 主键 `banId`；`userId`（引用平台用户，不可变、唯一）；`processOnly`：`banned`、`reason`、`bannedAt` / `bannedBy`、`liftedAt` / `liftedBy` / `liftNote`、`revision` | 封禁（Q2 D-Q2-4）：与资料分开，没有资料的人也能封禁；历史即封禁与解封的记录 |
| `QbCountry` | T（字典） | `code`（ISO 3166-1）、`name`（多语言）、`regions`（多值：GLOBAL、JP、CN、US、EUROPE、ASIA、NORTH_AMERICA、SOUTH_AMERICA） | 【缺省 Q7】国家到地区的映射。平台没有多值字段：`regions` 存为按固定顺序、以逗号连接的地区代码（`GLOBAL,JP,ASIA`），总含 GLOBAL（Q1）。只经流程写入（`QB_SETUP` 导入） |
| `QbPayoutAccount` | T | `userId`（唯一）、`provider`（STRIPE_CONNECT）、`externalAccountId`（`f.masked(qb.admin.finance.read, LAST4)`）、`payoutsEnabled`（`processOnly`，由 Stripe 通知更新） | 用户的收款账户；银行信息由 Stripe 保存，平台不存银行账号 |

### 3.2 内容：模版、版本、题目、资料

| 实体 | 类型 | 主要字段 | 说明 |
|---|---|---|---|
| `QbQuiz` | T | `ownerId`、`title`、`intro`（Markdown）、`cover`（文件）、`timeLimitSec`、`status`（DRAFT / VERSIONED）、`removed`、`aiGenerated`、`latestVersionNo`、`versionLabel`、`revision`、`editedAt`、`changedSinceVersion`、`questionCount`、`materialCount`（以上状态与计数 `processOnly`）、`clonedFrom` | 模版。只有拥有者可改（商家数据视图以 `ownerId` 限定 + 流程经该视图读取，不是自己的即 404）。"删除"即移除：`removed = true`，商家看不到，版本保留（Q3 D-Q3-6） |
| `QbMaterial` | T | `quizId`、`ownerId`、`seq`、`kind`（ARTICLE / LINK / VIDEO_LINK / PDF / IMAGES / AUDIO，不可改）、`title`、`description`、`body`（Markdown，ARTICLE）、`url`（LINK / VIDEO_LINK）、`pdf`、`audio` | 参考资料；视频只存链接（已确认）；只填其类型的内容字段；图片组的每张图为子实体 `QbMaterialImage`（`materialId`、`seq`、`image`、`caption`） |
| `QbQuestion` | T | `quizId`、`ownerId`、`seq`、`stem`、`image`（可选）、`points`（1–1 000） | |
| `QbOption` | T | `questionId`、`quizId`、`ownerId`、`seq`、`text`、`image`（可选）、`correct` | 2–6 个、至少 1 个正确：在"保存版本"时检查（`QuizCompleteness`；草稿允许不完整，M-22），提交审核时（Q4）再查 |
| `QbQuizVersion` | W | `quizId`、`ownerId`、`versionNumber`（1、2…，`label` 为 v1.0、v1.1…）、`title`、`content`（规范 JSON 文本：题目、选项、资料的完整快照，题与选项按 1 起的序号）、`contentHash`（平台 `ContentHash`）、`questionCount`、`materialCount`、`fullScore`、`versionedAt` | **整体快照**（分析 4.2），由"保存版本"（`QB_QUIZ_PUBLISH_VERSION`）显式生成，草稿的保存不生成版本（Q3 D-Q3-1）。没有文件字段。发布引用版本，答题与结算只读快照。没有商家数据视图（内容含正确答案、版本比模版活得久）：商家只经模板读取，模板只连接本人未移除的模版 |
| `QbVersionFile` | T | `quizId`、`ownerId`、`firstVersionNo`、`image` / `pdf` / `audio`（各对应一个文件策略，恰好一个非空） | 快照引用的每个文件按"模版 + 文件"一行，使平台的文件引用检查与孤儿清扫看得到 JSON 中的文件；普通时态实体（不是只写一次）：移除模版时写墓碑，被发布引用的版本的文件除外（Q4） |
| `QbAiJob` | T | `sponsorId`、`quizId`、`kind`（COVER / QUESTIONS）、`input`（资料、题数、难度）、`status`（G7 的状态）、`model`、`promptTokens`、`completionTokens`、`result`（jsonb：候选图片文件或候选题目） | AI 生成请求；候选题目经商家校对后由 `QB_AI_ACCEPT` 写入题目（M-26 "必须人工校对"） |

### 3.3 发布与答题

| 实体 | 类型 | 主要字段 | 说明 |
|---|---|---|---|
| `QbPublication` | T | `sponsorId`、`quizId`（随版本冗余存放）、`versionId`、`versionLabel`、`name`、`regions`（多值，`Regions.TARGETS`：非空子集，不强制含 GLOBAL）、`startAt`、`endAt`、`timeLimitSec`、`fullScore`（取自版本）、`passScore`、`budgetCap`（可选）、`maxWinners`（可选）、`maxReward`（档位最高额）、`maxSpend`（预计最大支出）、`tierCount`、`status`（见 5.1）、`rejectReason`、`rejectCode`（如 `QB_PUBLICATION_EXPIRED_IN_REVIEW`）、`approvalRequestId`、`submittedAt`、`approvedAt`、`pausedAt`、`endedAt`、`endReason`（`EXPIRED` / `QUOTA` / `BUDGET`）、`revision`、`editedAt`（以上派生值与状态 `processOnly`）、`copiedFrom`（源的主键，不作引用） | 一次投放（Q4，`plans/Q4-publishing.md`）。只有草稿可改，提交即冻结内容与全部策略；审核用平台审批（`ApprovalSubject` `qb.publication`） |
| `QbRewardTier` | T | `publicationId`、`sponsorId`、`seq`、`fromScore`、`toScore`、`reward` | 每个发布至多 20 档；区间不重叠、覆盖 0 到满分、至少一档奖励大于 0（提交时 `TierCoverage` 检查）；可从 0 分起 |
| `QbPublicationSchedule` | P | `publicationId`（主键）、`quizId`、`versionId`、`nextDueAt` | 已提交且未退回草稿的发布：定时任务按 `nextDueAt` 取到期的，移除模版时按 `quizId` 找出要保留文件的版本（都是普通索引；时态表上按可变字段查找要扫全部历史） |
| `QbPublicationCounter` | P | `publicationId`（主键）、`starts`、`completions`、`passes`、`winners`、`reserved`、`spent` | 高频计数与预算占用，上架时插入全 0 的一行（Q4），只在发布锁内更新（4.3，Q5）；不进时态（否则每次答题都给发布追加一个版本） |
| `QbPublicationCard` | P | `publicationId`（主键）、`sponsorId`、`status`、`regions`、`startAt`、`endAt`、`name`、`quizTitle`、`cover`、`versionId`、`maxReward`、`fullScore`、`passScore`、`timeLimitSec`、`questionCount`、`budgetCap`、`maxWinners`、`tiers`（档位的规范 JSON）、`sponsorCountry`、`wentOnAt` | 上架时随计数行插入（Q5）。除状态外全部在提交时冻结，状态由发布的流程在发布锁内同步。答题人的首页、详情、档位与答题流程只读它（时态表上按状态、时间、地区筛选要读全部版本） |
| `QbAttempt` | P → 结束后流程拒绝修改 | `userId`、`publicationId`（二者唯一，一人一次）、`sponsorId`、`versionId`、`startedAt`、`deadline`、`status`（IN_PROGRESS / SUBMITTED / EXPIRED）、`currentIndex`、`optionOrder`、`answers`、`result`（三者都是规范 JSON 文本）、`submittedAt`、`score`、`fullScore`、`passed`、`tierId`、`reward`、`fee`、`rewardStatus`（NONE / PAID / PENDING）、`rewardPaidAt`、`ledgerTransactionId` | 答题会话（Q5）。进行中只有 `answers`、`currentIndex` 会变（保存进度，U-41）；`result`（含每题正确选项）在完成时才写。不建逐题答案表：每次保存只写一行，题目分析（Q9）用 `jsonb_array_elements` 展开 |
| `QbPublicationView` | P | `userId`、`publicationId`（唯一）、`firstViewedAt` | 漏斗的"浏览"：每人每发布只记第一次（`QB_VIEW`） |
| `QbPublicationStats` | P | `publicationId`（主键）、`last1h`、`last24h`、`last7d`、`last30d`、`refreshedAt` | 近期完成人数，由任务 `qb.publication-stats` 每 10 分钟分块写入（只写变化的行；运行时间记在 `QbStatsRun`）；已认证的模板没有缓存；累计完成数取计数行，实时 |
| `QbPublicationOwner` | 视图 | `publicationId`、`sponsorId`（卡片的） | 只读，经限本人的数据视图读取：答题人的模板据此排除本人的发布（不能开始） |

### 3.4 钱包与资金

钱包**就是平台账本**（分析 4.1）。另有一张余额表供锁内快速判断，并每日与账本核对（F-06）。

| 实体 | 类型 | 主要字段 | 说明 |
|---|---|---|---|
| `QbWallet` | P | `walletId`、`ownerId` + `kind`（SPONSOR / TAKER，二者唯一）、`balance`（`>= 0` 检查约束）、`pendingCount`、`pendingAmount`（待支付）、`pendingOut`（转账中，Q6） | 余额缓存，只在钱包锁内由记账流程同步更新；真相在账本。Q5 只为商家建（第一次入账或记待支付时在锁内建立，没有行即余额 0）；用户余额按账本维度汇总，用户钱包与其锁由 Q6 定 |
| `QbTopUp` | T | `sponsorId`、`amount`、`checkoutSessionId`（唯一）、`paymentIntentId`、`status`（CREATED / PAID / EXPIRED / FAILED）、`paidAt`、`stripeFee`、`presentmentCurrency`、`presentmentAmount`（F-04 原币信息，若 Stripe 给出） | 充值 |
| `QbTransfer` | T | `userId`、`amount`、`status`（REVIEW / SENDING / SENT / FAILED / REVERSED）、`externalTransferId`、`failureReason` | 自动转账（4.7）。大额先进 REVIEW 走审批 |
| `QbFxRate` | T | `currency`、`rateDate`、`perJpy`、`source`（ECB / MANUAL） | 显示用参考汇率（"约合 X USD"），可手动覆盖；不参与记账 |
| `QbStripeEvent` | — | — | 不建：Stripe 通知由平台入站 Webhook（G9）原样存档与去重 |

### 3.5 消息与内容

| 实体 | 类型 | 主要字段 | 说明 |
|---|---|---|---|
| `QbMessage` | T | `recipientId`、`type`（SYSTEM / PROMO / REWARD / REMINDER）、`title`、`body`（Markdown）、`publicationId`（可选） | 个人消息（奖励到账、转账结果等） |
| `QbBroadcast` | T | `senderKind`（SPONSOR / PLATFORM）、`senderId`、`type`、`title`、`body`、`regions`（多值，可空 = 全体）、`userIds`（可选，指定用户）、`publicationId`（可选，须进行中）、`scheduledAt`、`status`（DRAFT / SCHEDULED / SENT） | 公告与商家通知：**不按人复制**，收件箱查询时按地区与注册时间匹配 |
| `QbMessageReceipt` | P | `userId`、`messageKind`（MESSAGE / BROADCAST）、`messageId`、`readAt`、`clickedAt` | 已读与点击（M-61 统计） |
| `QbContentPage` | T | `slug`（FAQ 分类、`privacy`、`terms`）、`kind`（FAQ / PAGE）、`category`、`title`（多语言）、`body`（多语言 Markdown）、`seq` | 帮助中心、隐私政策、条款；公开只读（平台公开模板） |

## 4. 账本

### 4.1 科目（本位币 JPY，小数位 0）

| 代码 | 名称 | 类型 | 维度 |
|---|---|---|---|
| 1110 | Stripe 余额 | 资产 | — |
| 1120 | 银行存款 | 资产 | — |
| 2110 | 商家预收款（商家钱包） | 负债 | 当事人（商家） |
| 2120 | 应付用户奖励（用户钱包） | 负债 | 当事人（用户） |
| 2130 | 转账中 | 负债 | 当事人（用户） |
| 4110 | 平台服务收入（抽成） | 收入 | — |
| 5110 | 支付手续费（Stripe） | 费用 | — |

维度"当事人"（`LedgerDimension` 位置 1，取值为用户编号）与"发布"（位置 2，`QbPublication.publicationId`，Q5：商家花费与汇总凭证按发布从账本汇总）。每笔分录带来源（`sourceEntity` / `sourceId`）：充值、答题会话、转账。

### 4.2 分录

| 事件 | 流程 | 借 | 贷 |
|---|---|---|---|
| 充值到账（Stripe 通知） | `QB_TOPUP_CONFIRM` | 1110 Stripe 余额（总额） | 2110 商家预收款（商家） |
| Stripe 手续费确定 | `QB_TOPUP_CONFIRM`（或对账） | 5110 支付手续费 | 1110 Stripe 余额 |
| 答题奖励结算 | `QB_ATTEMPT_SUBMIT` / `QB_ATTEMPT_EXPIRE` / `QB_PENDING_PAY` | 2110 商家预收款（商家，发布）：奖励 | 2120 应付用户奖励（用户，发布）：奖励 − 抽成；4110（发布）：抽成（当前 0，为 0 时不出该行） |
| 转账发出 | `QB_TRANSFER_SEND` | 2120 应付用户奖励（用户） | 2130 转账中（用户） |
| 转账成功（Stripe 通知） | `QB_TRANSFER_RESULT` | 2130 转账中（用户） | 1110 Stripe 余额 |
| 转账失败 / 被撤回 | `QB_TRANSFER_RESULT` | 2130 转账中（用户） | 2120 应付用户奖励（用户） |
| Stripe 打款到银行 | `QB_STRIPE_PAYOUT_RECORD` | 1120 银行存款 | 1110 Stripe 余额 |

- 待支付奖励**不入账**（需求 7.1）：只在 `QbAttempt.rewardStatus = PENDING` 与钱包的待支付计数中记录。
- **抽成按结算时**生效的受控参数 `qb.creator-fee-rate` 计算（补发时用补发时的比例），向下取整到整数 Kudos（`RewardSplit`）；来源单据为答题会话（`QbAttempt`），记账时间为完成（或补发）时间。
- 更正一律冲正（平台 `LEDGER_REVERSE`）。
- 余额核对（F-06，每日任务）：`QbWallet.balance` = 账本维度余额；2110 合计 = 商家钱包合计；2120 合计 = 用户钱包合计；不等则建待办给财务并停止当日导出。

### 4.3 并发与锁

| 锁（`HoldLock.exclusive`） | 谁持有 | 保护什么 |
|---|---|---|
| `qb.pub:<发布>` | 开始答题、提交、暂停 / 恢复、到期结束 | `QbPublicationCounter`（名额、预算占用）、发布状态 |
| `qb.wallet:<种类>:<所有者>`（如 `qb.wallet:SPONSOR:<用户>`） | 结算、补发待支付、开发入账（Q5）、充值到账、转账发出与结果 | `QbWallet` 与该当事人的账本分录 |
| `qb.account:<用户>` | 资料、入驻、商家资料、审批结果、封禁 / 解封（Q2） | 该用户的 `QbProfile`、`QbSponsor`、`QbUserBan` |

- 加锁顺序固定：**（账户锁 `qb.account:<用户>` →）模版锁 `qb.quiz:<模版>`（提交发布时共享）→ 发布锁 → 钱包锁**（Q4，D-Q4-5；Q2 D-Q2-10）。账户锁目前不与其他锁同时持有（提交发布只读入驻与封禁，不取账户锁）；只有提交发布同时取模版锁与发布锁，移除模版只取模版锁（独占），两者因此不会交错。只持钱包锁的流程（充值、补发、转账）不再取发布锁（补发不改发布计数，计数在提交时已算入），因此不会死锁。
- **晚取锁**（Q5）：提交先在锁外读会话、版本与抽成比例，锁内只重读会话、计数、发布与卡片，评分后（奖励大于 0 时）再取钱包锁、过账与写入；
  开始只取发布锁（不取钱包锁：商家余额是软检查，并发多出的人完成后记为待支付）；继续答题与保存进度不取锁（会话行的乐观版本）。
  恢复预算不足的发布（`QB_PUBLICATION_FUNDS_RESTORED`）由补发清空后发布的事件在另一事务中进行，只取发布锁，因此锁序不成环。
- 【缺省 Q11】单个发布的提交在发布锁上串行。Q5 的基线（`PlayThroughputIT`，见 `plans/Q5-answering.md` 的实施记录）由 Q10 压测对照；退路依次为降低过账成本、按发布预留预算、分批过账。每次结算是一个短事务（读快照、写答案、两行余额、一笔 2–3 行的分录），预计每秒数十次。
  如压测不足，把"商家钱包"拆为按发布预留：发布上架时从商家钱包转入发布预算，结算只取发布锁（以后的优化，不在 MVP）。

## 5. 状态机与流程

### 5.1 发布状态

```
DRAFT ──提交──► PENDING_REVIEW ──批准──► SCHEDULED ──到开始时间──► LIVE ⇄ PAUSED
  ▲  └─无需审批（自动通过）─► SCHEDULED / LIVE          │          │ ▲
  └──驳回（附原因）/ 撤回 / 审核时已过结束时间──┘   SCHEDULED ⇄ PAUSED  ▼ │（Q5/Q6）
                                                            FUNDS_SHORT
SCHEDULED / LIVE / PAUSED / FUNDS_SHORT ──到结束时间（Q4）、名额或预算上限用完（Q5）──► ENDED
```

声明的迁移（`eb.stateTransitions`，`initial(DRAFT)`；Q4 D-Q4-1）：`DRAFT → PENDING_REVIEW / SCHEDULED / LIVE`；`PENDING_REVIEW → DRAFT / SCHEDULED / LIVE`；
`SCHEDULED → LIVE / PAUSED / ENDED`；`LIVE → PAUSED / FUNDS_SHORT / ENDED`；`PAUSED → SCHEDULED / LIVE / ENDED`；`FUNDS_SHORT → LIVE / PAUSED / ENDED`。非法迁移 422。

- **状态只表达人与钱的开关**（审核、暂停、预算不足、结束），开始与结束由时间字段本身表达。"现在接受新的答题吗"只由
  `PublicationRules.acceptsNewAttempts(status, startAt, endAt, now)` 判断：`LIVE` 且 `startAt ≤ now < endAt`。Q5 的开始答题（在发布锁内）与首页模板用同一条件；
  因此定时任务晚一分钟不影响正确性。`PAUSED`、`FUNDS_SHORT`、`ENDED` 拒绝新的开始，已开始的会话照常作答与提交（需求 5.3-2、M-33）。
- **定时上下架**（`QB_PUBLICATION_TICK`，每分钟）：按 `QbPublicationSchedule.nextDueAt` 找出全部到期的发布（停机期间错过的时刻由下一次运行补上），每次至多 50 条，逐条在发布锁内按
  `PublicationRules.next` 迁移：`SCHEDULED` 到开始时间 → `LIVE`；`SCHEDULED / LIVE / PAUSED / FUNDS_SHORT` 到结束时间 → `ENDED`（`endReason = EXPIRED`）。
- **暂停**：可从 `SCHEDULED`、`LIVE`、`FUNDS_SHORT`（Q5）暂停；恢复按时间回到 `SCHEDULED`（未到开始）、`LIVE`（进行中）或 `ENDED`（已过结束）。
- **预算不足**：发布的预算上限或名额用完即 `ENDED`（`BUDGET` / `QUOTA`，Q5；进行中的会话预占名额与最高奖励，因此不会超额）；商家钱包付不起一笔奖励即 `FUNDS_SHORT`（Q5）。
  补发（`QB_PENDING_SETTLE`）清空该商家的待支付后发布事件 `qb.sponsor.funds-restored`，`QB_PUBLICATION_FUNDS_RESTORED` 按编号逐个在发布锁内把 `FUNDS_SHORT` 的发布放回 `LIVE`（已过结束时间的直接 `ENDED`）；
  `FUNDS_SHORT` 可以暂停，恢复照旧按时间。已结束的发布不因补发而改变。
- **提交即冻结**：只有 `DRAFT` 可改；提交后内容（只写一次的版本）与全部策略不再改变，要改就复制（M-36）。待审核的可撤回。
- **审核**：`QbPublication` 是审批对象 `qb.publication`（事实 `autoApprove`、`maxReward`、`maxSpend`、`sponsorId`、`regions`；批准绑定版本内容哈希与全部策略）。
  规则 `qb.publication.review`（`autoApprove = false` 时需要 `qb.review.publication` 审批）由 `QB_SETUP` 以受控变更提出；"自动通过"即受控参数
  `qb.review.publication.auto` 为真，作为事实写进每次评估。规则不存在或停用而开关为假时，提交被拒（`QB_REVIEW_NOT_CONFIGURED`），不放行。
  批准由订阅审批事件的 `QB_PUBLICATION_APPROVAL_RESULT` 继续：上架（建计数行），审核期间已过结束时间则退回草稿（`rejectCode`）；驳回退回草稿并写理由。商家入驻（Q2）同理。

### 5.2 业务流程

| 流程 | 权限 | 要点 |
|---|---|---|
| `QB_PROFILE_SAVE` | `qb.me`，要求已验证 | 注册第二步与资料修改（U-02、U-70）；国家来自字典；第一次给出国家时发 `qb.welcome` |
| `QB_SPONSOR_APPLY` | `qb.sponsor.me`，要求已验证 | 商家入驻资料 + 同意条款 → 审批（受控参数 `qb.review.sponsor.auto` 为真即自动通过；无规则时拒绝 `QB_REVIEW_NOT_CONFIGURED`）；待审核时再申请即取代旧请求；关闭"重新提交"待办 |
| `QB_SPONSOR_PROFILE_SAVE` | `qb.sponsor.me`，要求已验证 | 已通过的商家修改名字、公司、简介、头像，不再审核 |
| `QB_SPONSOR_APPROVAL_RESULT` | 系统（审批事件；`qb.sponsor.system`，不授予任何角色） | 只认入驻自己的请求，且请求绑定的内容等于当前内容：批准 → `APPROVED` 并发邮件；驳回（理由取自审批人的决定）→ `REJECTED`、邮件、给商家"重新提交"待办 |
| `QB_QUIZ_SAVE` / `QB_QUIZ_DELETE` / `QB_QUIZ_CLONE` | `qb.content.write` | 只有拥有者（锁 `qb.quiz:<id>` 后经商家视图读取）；删除即移除（`removed`，子项写墓碑），版本保留，已提交过的发布所用版本的文件保留（Q4）；克隆可取草稿或指定版本 |
| `QB_QUESTION_SAVE` / `_DELETE` / `_REORDER`、`QB_MATERIAL_SAVE` / `_DELETE` / `_REORDER` | `qb.content.write` | 一道题（连同选项）或一条资料（连同图片组）各自保存，只写改变的行；可带 `baseRevision`（过期即 `QB_QUIZ_CHANGED`）；上限见 `content.ContentLimits` |
| `QB_QUIZ_PUBLISH_VERSION` | `qb.content.write` | "保存版本"：校验完整性（M-31 中属于内容的部分，一次报告全部）→ 与最新版本哈希相同即拒绝 → 生成 `QbQuizVersion` 快照（只写一次）并补登 `QbVersionFile` |
| `QB_AI_REQUEST` / `QB_AI_ACCEPT` | `qb.ai.use` | 经 G7 异步调用 OpenAI（模型来自业务参数）；结果为候选，商家选择 / 校对后才写入 |
| `QB_PUBLICATION_SAVE` / `QB_PUBLICATION_DELETE` / `QB_PUBLICATION_COPY` | `qb.publication.write` | 向导第一、二步保存草稿（可不完整），输出提交时会报告的全部问题与预计最大支出（第三步）；删除只限草稿；复制任意状态的本人发布为新草稿，可换版本或时间（Q4） |
| `QB_PUBLICATION_SUBMIT` / `QB_PUBLICATION_WITHDRAW` | `qb.publication.write` | 模版锁（共享）→ 发布锁；`PublicationReadiness` 一次报告全部违规（档位、时间、合格分、预算、以快照重查内容、商家已入驻）；`RequireApproval`；撤回待审核的请求 |
| `QB_PUBLICATION_PAUSE` / `QB_PUBLICATION_RESUME` | `qb.publication.write` | 取发布锁；恢复按时间回到相应状态 |
| `QB_PUBLICATION_APPROVAL_RESULT` | 系统（审批事件；`qb.publication.system`，不授予任何角色） | 只认发布自己的请求（`approvalRequestId`）：批准且内容未变 → 上架；驳回（以存储的请求为准）→ 草稿附理由 |
| `QB_VIEW` | `qb.play`，要求已验证 | 记第一次浏览（重复忽略）；发布须对本人可见，否则 404 |
| `QB_ATTEMPT_START` | `qb.play`，要求已验证 | 本人进行中的会话直接继续（不取锁）；否则发布锁内：`LIVE` 且在时间内、地区有交集、不是自己的发布、名额与预算（进行中的会话各预占一个名额与最高奖励）、商家可支付（软检查）→ 建会话（`deadline = now + 时限`，选项按会话打乱）、`starts + 1`、`reserved + maxReward`。返回题目（**不含正确答案**） |
| `QB_ATTEMPT_SAVE` | `qb.play`，要求已验证 | 保存当前题号与已选答案（不取锁）；超过 `deadline` 拒绝；非法题号或选项一次报告全部 |
| `QB_ATTEMPT_SUBMIT` | `qb.play`，要求已验证 | 晚取锁：发布锁 → 超时（`now > deadline + 宽限 5 秒`）422 `QB_ATTEMPT_EXPIRED`（本次答案不用）；评分 → 档位 → 计数 →（奖励大于 0）钱包锁 → 无待支付且余额够则子流程 `LEDGER_POST`、`PAID`，否则 `PENDING` 并把 `LIVE` 的发布置 `FUNDS_SHORT`；名额或预算用完即 `ENDED`；发布事件 `qb.reward.settled` / `qb.reward.pending`（Q7 的站内信）。幂等键与会话状态防重复提交 |
| `QB_ATTEMPT_EXPIRE` | 系统（`qb.play.system`） | 过了时限与宽限仍 IN_PROGRESS 的会话按已保存答案完成（`EXPIRED`），与提交同一套步骤；已完成或未到期的什么也不做。选取它们的任务 `qb.attempt-expire` 在 Q5b（平台 16k 之后） |
| `QB_TOPUP_START` | `qb.topup` | 建 `QbTopUp` 与 Stripe Checkout Session（`BlockingStep`，Stripe 幂等键 = 充值编号），返回支付页地址 |
| `QB_TOPUP_CONFIRM` | 系统（G9） | Stripe `checkout.session.completed`：钱包锁 → 记分录、`PAID` → 子流程 `QB_PENDING_SETTLE` |
| `QB_PENDING_SETTLE` / `QB_PENDING_PAY` | 系统（`qb.play.system`） | 只取钱包锁：按提交顺序取至多 50 条待支付，逐条（`QB_PENDING_PAY`，按结算时的抽成）补发，遇到付不起的即停止（不跳过）；还能付下一条时发布事件在新事务中续接；清空后发布 `qb.sponsor.funds-restored` |
| `QB_PUBLICATION_FUNDS_RESTORED` / `QB_PUBLICATION_FUNDED` | 系统（`qb.publication.system`） | 订阅 `qb.sponsor.funds-restored`：按编号逐个在发布锁内把该商家 `FUNDS_SHORT` 的发布放回 `LIVE`（已过结束时间即 `ENDED`）；不取钱包锁 |
| `QB_PUBLICATION_STATS_REFRESH`（任务 `qb.publication-stats`） | 系统（`qb.play.system`） | 每 10 分钟：进行中及 31 天内结束的发布的近 1 小时、24 小时、7 天、30 天完成数写入 `qb_publication_stats`（只写变化的行） |
| `QB_WALLET_DEV_CREDIT` | `qb.wallet.dev-credit`（不授予任何角色），只在 `quizbuks.wallet.dev-credit.enabled=true` 时注册 | 开发与测试用的入账（Q6 的充值之前）：钱包锁 → 借 1110 / 贷 2110 → 子流程 `QB_PENDING_SETTLE`。生产不开 |
| `QB_PAYOUT_ONBOARD` | `qb.payout.onboard` | 创建 Stripe Express 账户（首次）与开通链接，返回地址；结果由 `account.updated` 通知更新 `payoutsEnabled` |
| `QB_TRANSFER_RUN`（任务） | 系统 | 每日：余额 ≥ 门槛、`payoutsEnabled`、未封禁的用户各建一笔转账（全额）；超过人工审核额的进 REVIEW（审批） |
| `QB_TRANSFER_SEND` | 系统 | 钱包锁 → 分录（2120 → 2130）→ 提交后（`AFTER_COMMIT`）调用 Stripe Transfer（幂等键 = 转账编号） |
| `QB_TRANSFER_RESULT` | 系统（G9） | 成功：2130 → 1110；失败 / 撤回：2130 → 2120，站内信 |
| `QB_USER_BAN` / `QB_USER_UNBAN` | `qb.admin.users.ban` | 封禁（`QbUserBan`）：三个入口都不能登录（`QbSignInGuard`），下一次刷新令牌即失效；有价值的动作在流程中再查（提交发布已接上；答题、转账随 Q5、Q6）；不能封禁自己与管理员 |
| `QB_BROADCAST_SAVE` / `QB_BROADCAST_DISPATCH`（任务） | `qb.broadcast.write` / 系统 | 定时发送：到时置 SENT；商家通知只能关联自己的进行中发布 |
| `QB_MESSAGE_MARK` | `qb.me` | 已读 / 点击 |
| `QB_PUBLICATION_TICK`（任务）/ `QB_PUBLICATION_ADVANCE` | 系统（`qb.publication.system`，不授予任何角色） | 每分钟：按日程表找出到期的发布（至多 50 条），逐条在发布锁内 SCHEDULED → LIVE、到期 → ENDED、待审核而到期 → DRAFT |
| `QB_FX_FETCH`（任务） | 系统 | 每日取 ECB 参考汇率（`BlockingStep`），手动覆盖优先 |
| `QB_RECONCILE`（任务） | 系统 | 每日余额核对（4.2）、与 Stripe 余额交易报告核对（以后，F-05） |

所有流程的业务规则违规以 422 与错误码返回（`QB_*`，三语文案）。

## 6. 查询与报表（SQL 模板，`queries/qb/`）

| 模板 | 用途 | 权限 |
|---|---|---|
| `qb.app.feed` | 首页卡片：可见性（LIVE + 时间内 + 地区交集 + 名额与预算未满 + 商家可支付）、关键字、筛选（发布人国家与名称、金额、完成人数、开始日期）、排序（U-10 – U-14）。只读卡片、计数、钱包（与较小的国家、入驻）；"现在"（`at`）与国家（`country`）由调用方传，开始答题在服务端再判断 | `qb.play` |
| `qb.app.publication` / `qb.app.publication-tiers` | 详情（U-20 – U-23）：卡片、简介与资料（版本快照中，不含题目）、发布人（入驻已通过的）、完成人数（近期窗口取统计表，累计取计数行）、本人的会话；档位取卡片 | `qb.play` |
| `qb.app.history` | 本人的答题记录（U-40 – U-42）：已完成（合格 / 不合格）、进行中、已过时限待自动提交；关键字、日期、金额、状态筛选 | `qb.me` |
| `qb.app.ledger` / `qb.app.wallet` | 交易流水、余额概览（U-50 – U-52，Q6） | `qb.me` |
| `qb.app.inbox` | 个人消息 ∪ 匹配的公告，带未读 | `qb.me` |
| `qb.sponsor.dashboard` / `qb.sponsor.publications` / `qb.sponsor.analytics.*` / `qb.sponsor.transactions` | M-10 – M-11、M-34、M-40 – M-43、M-50 – M-52 | 各自权限 |
| `qb.admin.attempts` / `qb.admin.wallets` | 全部会话（P-01 的答题历史、支持查询）；商家钱包的余额与待支付（Q5） | `qb.admin.users.read` / `qb.admin.finance.read` |
| `qb.admin.users` / `qb.admin.transfers` / `qb.admin.monthly`（报表）/ `qb.admin.balances`（报表）/ `qb.admin.journal-export` | P-01、P-03、F-06、F-08、F-02 – F-03（CSV 汇总凭证） | 管理权限 |

- 完成人数：已认证的模板没有缓存，所以近期窗口由任务 `qb.publication-stats` 每 10 分钟按索引 `QbAttempt (publicationId, submittedAt)` 统计到 `qb_publication_stats`（需求 6.1 允许 10 分钟），累计取计数行；分析（Q9）同样按索引统计。
- 导出（M-52）用平台导出（CSV / XLSX / PDF）。

## 7. 前端

### 7.1 答题人 PWA（`quizbuks-web/app`）

- React 19 + TypeScript + Vite + `@jabiz/ui`（shadcn/ui + Tailwind CSS v4，与平台阶段 15 相同，决策 D34）+ TanStack Query + React Router + i18next；类型由 OpenAPI 生成。
- PWA：`manifest.webmanifest`、图标、Service Worker 只缓存应用外壳与静态资源（不离线缓存数据，不做推送）。
- 宽度 ≤ 430px 设计，底部 5 个导航；卡片紧凑，一屏 ≥ 6 张（U-10）。
- 认证、令牌刷新、API 客户端来自平台提供的应用前端库（G12）。

### 7.2 商家后台（`quizbuks-web/sponsor`）

- 同一工具链与 `@jabiz/ui`；左侧 7 项导航；中 / 英；深色模式（`@jabiz/ui` 的外观切换）；图表用平台选定的图表库（G11）。
- 内容工坊是最重的页面：题目与选项的编辑、排序、图片、版本列表；发布向导三步。

### 7.3 管理员后台（`/admin/`）

- 平台通用后台：用户与资料、商家、字典、内容页、业务参数、审批与待办、审计、账本、报表都由元数据生成。
- QuizBuks 扩展页（`backend/quizbuks/admin-extension`）：用户详情汇总（资料 + 答题历史 + 资金流水，P-01）、转账监控、对账结果。

### 7.4 "Kudos"

账本与接口中的币种是 JPY；三个前端都把金额显示为 `1,234 Kudos`（格式化函数一处），参考汇率只用于"约合 X USD"。平台通用后台对管理员仍显示 JPY（管理员需要知道真实币种）。

## 8. 外部系统

| 系统 | 用途 | 接入方式 | 配置 |
|---|---|---|---|
| Stripe | 充值（Checkout）、用户收款账户（Connect Express）、转账（Transfers） | 出站：官方 Java SDK，在 `BlockingStep` 中调用（有副作用的放 `AFTER_COMMIT` 或用 Stripe 幂等键）；入站：平台入站 Webhook（G9），按 Stripe 签名校验 | `STRIPE_API_KEY`、`STRIPE_WEBHOOK_SECRET`（环境变量） |
| OpenAI | AI 生成题目与封面 | 平台异步外部作业（G7）+ 官方 Java SDK；模型名是业务参数 | `OPENAI_API_KEY` |
| ECB | 参考汇率 | 定时任务中的 `BlockingStep` | — |
| 邮件 | 激活、欢迎、转账结果 | 平台事务邮件（G3） | `spring.mail.*` |

所有外部密钥只来自环境变量；外部调用都有超时、重试与平台的观测（`PlatformObservations`）。

## 9. 安全与反作弊

- 正确答案只在提交后返回：答题人的数据视图与模板都不含 `correct`；结果页由提交流程的输出给出。
- 选项按会话打乱（`optionOrder`），题目顺序不打乱。
- 一人一次：`QbAttempt (userId, publicationId)` 唯一。
- 未激活（邮箱未验证）的用户不能写入任何 QuizBuks 数据：每个写入流程声明 `requiresVerifiedEmail()`（入口检查，403 `EMAIL_NOT_VERIFIED`）；商家入口不接受未验证的登录。
  因此预占他人邮箱注册的人留不下应用数据（平台接管时不清理应用数据）。
- 封禁的不能登录（`QbSignInGuard`，登录与刷新时检查）；已签发的访问令牌在到期前仍可用，所以提交发布（`SponsorChecks`）、开始答题与提交（Q5）、转账（Q6）在流程中再查 `QbUserBan`（422 `QB_USER_BANNED`）。
- 一个 Stripe 收款账户只能属于一个用户（`externalAccountId` 唯一），减少多账号刷奖后套现。
- 限流：开始答题、提交、AI 请求、充值按用户限流（平台限流能力只覆盖公开接口与上传，若不够在 G1 中补"按用户的接口限流"——计划时评估）。

## 10. 依赖的平台能力（`1.2/platform`）

| 编号 | 能力 | QuizBuks 的用处 | 必须在哪个应用阶段前完成 |
|---|---|---|---|
| 阶段 15 | 后台前端改用 shadcn/ui（D34） | 三个前端同一套组件与外观；扩展页 | 商家后台、管理员扩展页 |
| G1 ★ | 登录入口（入口 → 角色、令牌只含入口角色的权限）、自助注册、邮箱验证、按邮箱登录、登录前应用检查 | 三个入口分开登录、注册、激活、封禁 | 账户阶段 |
| G2 ★ | OIDC 自动开户（Google、Apple） | U-02 社交注册 | 账户阶段（可晚于邮箱注册） |
| G3 ★ | 事务邮件（模板、Outbox、重试、偏好） | 激活、欢迎、转账结果 | 账户阶段 |
| G4 | 登录记录带 IP 与 User-Agent | M-02 | 账户阶段 |
| G5 | 会话列表与吊销 | M-70 | 后期 |
| G7 | 异步外部作业（长时间外部调用、结果回写、用量） | AI 生成 | 内容阶段的 AI 部分 |
| G9 ★ | 入站 Webhook（Stripe 签名、存档、去重、交给流程） | 充值到账、转账结果、收款账户状态 | 钱包阶段 |
| G11 | 图表库 | 仪表盘、漏斗、趋势 | 分析阶段 |
| G12 | 应用前端库（认证、令牌刷新、step-up、API 客户端、格式化），以及 SPA 构建对 PWA（manifest、Service Worker 作用域与 CSP）的支持 | App 与商家后台 | 第一个前端阶段 |
| G10 | API Key | M-73（P2） | 以后 |

★ = 改变已有决策，须先在 `09-decisions.md` 新增决策并确认。分析中的 G6（视频）已取消；G8（账本余额下限）改由应用在钱包锁内判断（4.3），不再需要。

G1（16b）、G3（16a）、G4（16b-1）已合入并在 Q2 中使用；G2（16f）合入后追加社交登录（Q2 D-Q2-9）。Q2 发现的新平台缺口见 `plans/Q2-accounts.md` 的平台缺口表（第 15–20 项）。

## 11. 需求对照

| 需求 | 设计位置 |
|---|---|
| U-01 – U-07 | 1（入口）、3.1、5.2、G1 – G3 |
| U-10 – U-14、U-20 – U-23 | 3.3、6（`qb.app.feed` / `publication`）、5.2 `QB_VIEW` / `QB_ATTEMPT_START` |
| U-30 – U-34 | 5.2 `QB_ATTEMPT_*`、4.2、4.3、9 |
| U-40 – U-42、U-50 – U-53 | 6、3.4、5.2 转账（提现改为自动转账，已确认） |
| U-60 – U-63、U-70 – U-74 | 3.5、6 `qb.app.inbox`、3.1 |
| M-01 – M-04 | 3.1、5.2 `QB_SPONSOR_APPLY` / `QB_TOPUP_*` |
| M-10 – M-11、M-40 – M-43、M-50 – M-52 | 6、G11 |
| M-20 – M-28 | 3.2、5.2、G7 |
| M-30 – M-36 | 3.3、5.1、5.2 |
| M-60 – M-61、P-04 | 3.5 |
| M-70 – M-73 | G5、G3、G10 |
| P-01 – P-09 | 2、7.3、5.2、平台审批与业务参数 |
| 5.3 规则 1 – 14 | 3.2 快照（1）、5.1（2）、6（3）、3.2（4）、5.2（5、6）、4.2（7、8、9）、2（10）、G1（11）、4.1（12）、4.2（13）、5.2 转账（14） |
| F-01 – F-09 | 4、6、平台报表与单据 |
