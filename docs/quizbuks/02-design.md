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
  src/main/resources/db/migration/          应用迁移（V1000+，避开平台与示范应用）
  src/test/resources/scenarios/qb/**.yml    场景回放
quizbuks-web/                应用前端（pnpm 工作区，`@jabiz/ui` 与 `@jabiz/client`，与平台同一工具链，D34）
  app/                       答题人 PWA（挂在 /）
  sponsor/                   商家后台（挂在 /sponsor/）
deploy/quizbuks/             docker-compose 与部署说明
docs/quizbuks/               本文件等
```

一个可部署的 jar 提供三个前端与接口：

| 路径 | 前端 | 登录入口（G1） | 接受的角色 |
|---|---|---|---|
| `/` | `quizbuks-web/app`（PWA） | `app` | `QB_TAKER` |
| `/sponsor/` | `quizbuks-web/sponsor` | `sponsor` | `QB_SPONSOR`（且入驻已通过才能发布） |
| `/admin/` | 平台通用后台 + QuizBuks 扩展页 | `admin` | `QB_ADMIN_SUPER` / `QB_ADMIN_CONTENT` / `QB_ADMIN_FINANCE` |

三个入口**分开登录**：入口决定接受哪些角色，签发的令牌只带这些角色的权限（同一邮箱既是答题人又是商家时，在 App 中拿不到商家权限，反之亦然）。
入口的机制属于平台（G1），本应用只声明三个入口与各自的角色。

## 2. 角色与权限

| 角色 | 权限（应用自定义的权限码，前缀 `qb.`） |
|---|---|
| `QB_TAKER` | `qb.play`（浏览发布、答题）、`qb.me`（本人资料、钱包、流水、消息）、`qb.payout.onboard`（开通收款账户） |
| `QB_SPONSOR` | `qb.sponsor.me`、`qb.content.write`、`qb.ai.use`、`qb.publication.write`、`qb.topup`、`qb.sponsor.finance.read`、`qb.broadcast.write` |
| `QB_ADMIN_CONTENT` | `qb.admin.users.read`、`qb.review.sponsor`、`qb.review.publication`、`qb.admin.content.write`（FAQ、条款、公告）、`approval.decide`、`task.read` |
| `QB_ADMIN_FINANCE` | `qb.admin.users.read`、`qb.admin.finance.read`、`qb.payout.review`、`qb.reconcile`、`ledger.read`、`report.issue`、`approval.decide`、`task.read` |
| `QB_ADMIN_SUPER` | 上述全部 + `qb.admin.users.ban`、`platform.param.read/write`、`control.propose`、平台安全管理权限 |

- 行范围：答题人的数据视图以 `userId = 当前用户` 限定；商家的以 `sponsorId = 当前用户` 限定（平台 `fromContext("…", actorId)`）。
- 职责分离：大额转账的审核人不能是被转账人本人（平台 SoD 规则）；修改抽成比例、转账门槛等参数走四眼（`CONTROL_CHANGE_*`）。
- 二次验证：管理员角色要求二次验证（`SecRole.requireMfa`）；商家可自行开启。

## 3. 领域模型

记号：**T** = 时态实体（只追加，有历史）；**W** = 只写一次（`t.writeOnce()`）；**P** = 普通表（可更新，平台的非时态实体，用于高频计数与工作状态）。
金额字段一律 `Monetary(JPY, scale 0)`，界面显示为 "Kudos"（见 7.4）。时间一律来自注入的 `Clock`。

### 3.1 账户与资料

| 实体 | 类型 | 主要字段 | 说明 |
|---|---|---|---|
| `QbProfile` | T | `userId`（唯一）、`displayName`、`countryCode`（字典）、`city`、`preferredCurrency`（USD/EUR/GBP/JPY/CNY/INR，只用于显示参考）、`status`（ACTIVE / BANNED，`processOnly`）、`banReason` | 答题人与商家共用的资料；激活状态由平台（G1）管 |
| `QbSponsor` | T | `userId`（唯一）、`company`、`intro`（Markdown）、`avatar`（文件，公开）、`reviewStatus`（PENDING / APPROVED / REJECTED，`processOnly`）、`termsAcceptedAt` | 商家入驻；发布人的公开信息来自这里（U-10 发布人名称 / 头像） |
| `QbCountry` | T（字典） | `code`（ISO 3166-1）、`name`（多语言）、`regions`（多值：GLOBAL、JP、CN、US、EUROPE、ASIA、NORTH_AMERICA、SOUTH_AMERICA） | 【缺省 Q7】国家到地区的映射 |
| `QbPayoutAccount` | T | `userId`（唯一）、`provider`（STRIPE_CONNECT）、`externalAccountId`（`f.masked(qb.admin.finance.read, LAST4)`）、`payoutsEnabled`（`processOnly`，由 Stripe 通知更新） | 用户的收款账户；银行信息由 Stripe 保存，平台不存银行账号 |

### 3.2 内容：模版、版本、题目、资料

| 实体 | 类型 | 主要字段 | 说明 |
|---|---|---|---|
| `QbQuiz` | T | `ownerId`、`title`、`intro`（Markdown）、`cover`（文件）、`timeLimitSec`、`status`（DRAFT / VERSIONED）、`aiGenerated`、`latestVersionNo`（`processOnly`） | 模版。只有拥有者可改（数据视图范围 + 流程检查） |
| `QbMaterial` | T | `quizId`、`seq`、`kind`（ARTICLE / LINK / VIDEO_LINK / PDF / IMAGES / AUDIO）、`title`、`description`、`body`（Markdown，ARTICLE）、`url`（LINK / VIDEO_LINK）、`file`（PDF / AUDIO） | 参考资料；视频只存链接（已确认）；图片组的每张图为子实体 `QbMaterialImage`（`materialId`、`seq`、`image`） |
| `QbQuestion` | T | `quizId`、`seq`、`stem`、`image`（可选）、`points`（≥ 1） | |
| `QbOption` | T | `questionId`、`seq`、`text`、`image`（可选）、`correct` | 2–6 个、至少 1 个正确：服务端规则，在"保存版本"与"提交审核"时检查（草稿允许不完整，M-22） |
| `QbQuizVersion` | W | `quizId`、`versionNo`（1、2…，显示为 v1.0、v1.1…）、`content`（jsonb：题目、选项、资料的完整快照）、`contentHash`、`questionCount`、`fullScore` | **整体快照**（分析 4.2）。发布引用版本，答题与结算只读快照 |
| `QbVersionFile` | W | `versionId`、`file`（文件字段） | 快照引用的每个文件一行，使平台的文件引用检查与孤儿清扫看得到 jsonb 中的文件（否则模版删除后文件会被清扫） |
| `QbAiJob` | T | `sponsorId`、`quizId`、`kind`（COVER / QUESTIONS）、`input`（资料、题数、难度）、`status`（G7 的状态）、`model`、`promptTokens`、`completionTokens`、`result`（jsonb：候选图片文件或候选题目） | AI 生成请求；候选题目经商家校对后由 `QB_AI_ACCEPT` 写入题目（M-26 "必须人工校对"） |

### 3.3 发布与答题

| 实体 | 类型 | 主要字段 | 说明 |
|---|---|---|---|
| `QbPublication` | T | `sponsorId`、`versionId`、`name`、`regions`（多值）、`startAt`、`endAt`、`timeLimitSec`、`fullScore`（取自版本）、`passScore`、`budgetCap`（可选）、`maxWinners`（可选）、`status`（`processOnly`，见 5.1）、`rejectReason`、`maxReward`（档位最高额，`processOnly`）、`copiedFrom` | 一次投放。审核用平台审批（`ApprovalSubject`） |
| `QbRewardTier` | T | `publicationId`、`fromScore`、`toScore`、`reward` | 区间不重叠、覆盖 0 到满分（服务端规则）；可从 0 分起 |
| `QbPublicationCounter` | P | `publicationId`（主键）、`starts`、`completions`、`passes`、`winners`、`reserved`、`spent` | 高频计数与预算占用，只在发布锁内更新（4.3）；不进时态（否则每次答题都给发布追加一个版本） |
| `QbAttempt` | P → 结束后封存 | `userId`、`publicationId`（二者唯一，6 一人一次）、`startedAt`、`deadline`、`status`（IN_PROGRESS / SUBMITTED / EXPIRED）、`optionOrder`（jsonb，打乱后的顺序【缺省 Q8】）、`currentIndex`、`submittedAt`、`score`、`passed`、`tierId`、`reward`、`rewardStatus`（NONE / PAID / PENDING）、`rewardPaidAt` | 答题会话。进行中可改（保存进度，U-41），提交后不再变（流程拒绝，审计照常） |
| `QbAttemptAnswer` | P | `attemptId`、`questionNo`、`selected`（选项号数组）、`correct` | 逐题答案；`correct` 在提交时才写 |
| `QbPublicationView` | P | `userId`、`publicationId`（唯一）、`firstViewedAt` | 漏斗的"浏览"：每人每发布只记第一次 |

### 3.4 钱包与资金

钱包**就是平台账本**（分析 4.1）。另有一张余额表供锁内快速判断，并每日与账本核对（F-06）。

| 实体 | 类型 | 主要字段 | 说明 |
|---|---|---|---|
| `QbWallet` | P | `ownerId`（主键）、`kind`（SPONSOR / TAKER）、`balance`、`pendingOut`（转账中） | 余额缓存，只在钱包锁内由记账流程同步更新；真相在账本 |
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

维度"当事人"（`LedgerDimension`，取值为用户编号）。每笔分录带来源（`sourceEntity` / `sourceId`）：充值、答题会话、转账。

### 4.2 分录

| 事件 | 流程 | 借 | 贷 |
|---|---|---|---|
| 充值到账（Stripe 通知） | `QB_TOPUP_CONFIRM` | 1110 Stripe 余额（总额） | 2110 商家预收款（商家） |
| Stripe 手续费确定 | `QB_TOPUP_CONFIRM`（或对账） | 5110 支付手续费 | 1110 Stripe 余额 |
| 答题奖励结算 | `QB_ATTEMPT_SUBMIT` / `QB_PENDING_SETTLE` | 2110 商家预收款（商家）：奖励 | 2120 应付用户奖励（用户）：奖励 − 抽成；4110：抽成（当前 0，为 0 时不出该行） |
| 转账发出 | `QB_TRANSFER_SEND` | 2120 应付用户奖励（用户） | 2130 转账中（用户） |
| 转账成功（Stripe 通知） | `QB_TRANSFER_RESULT` | 2130 转账中（用户） | 1110 Stripe 余额 |
| 转账失败 / 被撤回 | `QB_TRANSFER_RESULT` | 2130 转账中（用户） | 2120 应付用户奖励（用户） |
| Stripe 打款到银行 | `QB_STRIPE_PAYOUT_RECORD` | 1120 银行存款 | 1110 Stripe 余额 |

- 待支付奖励**不入账**（需求 7.1）：只在 `QbAttempt.rewardStatus = PENDING` 记录。
- 更正一律冲正（平台 `LEDGER_REVERSE`）。
- 余额核对（F-06，每日任务）：`QbWallet.balance` = 账本维度余额；2110 合计 = 商家钱包合计；2120 合计 = 用户钱包合计；不等则建待办给财务并停止当日导出。

### 4.3 并发与锁

| 锁（`HoldLock.exclusive`） | 谁持有 | 保护什么 |
|---|---|---|
| `qb.pub:<发布>` | 开始答题、提交、暂停 / 恢复、到期结束 | `QbPublicationCounter`（名额、预算占用）、发布状态 |
| `qb.wallet:<所有者>` | 结算、补发待支付、充值到账、转账发出与结果 | `QbWallet` 与该当事人的账本分录 |

- 加锁顺序固定：**先发布锁、后钱包锁**；只持钱包锁的流程（充值、补发、转账）不再取发布锁（补发不改发布计数，计数在提交时已算入），因此不会死锁。
- 【缺省 Q11】单个发布的提交在发布锁上串行。每次结算是一个短事务（读快照、写答案、两行余额、一笔 2–3 行的分录），预计每秒数十次。
  如压测不足，把"商家钱包"拆为按发布预留：发布上架时从商家钱包转入发布预算，结算只取发布锁（以后的优化，不在 MVP）。

## 5. 状态机与流程

### 5.1 发布状态

```
DRAFT ──提交审核──► PENDING_REVIEW ──批准──► SCHEDULED ──到开始时间──► LIVE ⇄ PAUSED
  ▲                      │                 （开始时间已过则直接 LIVE）    │ ▲
  └──────驳回（附原因）────┘                                           ▼ │充值后补发完
                                                          FUNDS_SHORT（商家余额不足）
LIVE / PAUSED / FUNDS_SHORT ──到结束时间或名额 / 预算用完──► ENDED
```

- 只有 `LIVE` 接受新的开始答题；`PAUSED`、`FUNDS_SHORT`、`ENDED` 拒绝新的开始，已开始的会话照常作答与提交（需求 5.3-2、M-33）。
- `FUNDS_SHORT`：某次结算余额不足即进入；充值后补发完该商家全部待支付奖励时回到 `LIVE`（若未到期）。
- 审核：`QbPublication` 是 `ApprovalSubject`；"自动通过"即审批规则对该对象不要求审批（平台已有，由业务参数切换）。商家入驻同理。

### 5.2 业务流程

| 流程 | 权限 | 要点 |
|---|---|---|
| `QB_PROFILE_SAVE` | `qb.me` | 注册第二步与资料修改（U-02、U-70）；国家来自字典 |
| `QB_SPONSOR_APPLY` | `qb.sponsor.me` | 商家入驻资料 + 同意条款 → 审批（自动或人工） |
| `QB_QUIZ_SAVE` / `QB_QUIZ_DELETE` / `QB_QUIZ_CLONE` | `qb.content.write` | 只有拥有者；已被发布引用的版本不受删除影响（快照独立） |
| `QB_QUIZ_PUBLISH_VERSION` | `qb.content.write` | 校验完整性（M-31）→ 生成 `QbQuizVersion` 快照与 `QbVersionFile` |
| `QB_AI_REQUEST` / `QB_AI_ACCEPT` | `qb.ai.use` | 经 G7 异步调用 OpenAI（模型来自业务参数）；结果为候选，商家选择 / 校对后才写入 |
| `QB_PUBLICATION_SAVE` / `QB_PUBLICATION_SUBMIT` / `QB_PUBLICATION_COPY` | `qb.publication.write` | 向导三步对应一次保存 + 一次提交；提交时校验档位、时间、商家已入驻；`RequireApproval` |
| `QB_PUBLICATION_PAUSE` / `QB_PUBLICATION_RESUME` | `qb.publication.write` | 取发布锁 |
| `QB_VIEW` | `qb.play` | 记第一次浏览（重复忽略） |
| `QB_ATTEMPT_START` | `qb.play` | 发布锁内：发布 `LIVE`、用户可见（地区、时间）、用户 `ACTIVE` 且已激活、未参加过、名额与预算（`spent + reserved + maxReward ≤ budgetCap`）→ 建会话（`deadline = now + timeLimit`）、`reserved += maxReward`、`starts += 1`。返回题目（**不含正确答案**）与打乱后的选项顺序 |
| `QB_ATTEMPT_SAVE` | `qb.play` | 保存当前题号与已选答案；超过 `deadline` 拒绝 |
| `QB_ATTEMPT_SUBMIT` | `qb.play` | 发布锁 → 超时（`now > deadline + 宽限 5 秒`）422 `QB_ATTEMPT_EXPIRED`；评分 → 档位 → `reserved -= maxReward`、`spent += reward`、计数 → 钱包锁 → 余额够则记分录、`PAID`，否则 `PENDING` 并把发布置 `FUNDS_SHORT` → 站内信。幂等键防重复提交 |
| `QB_ATTEMPT_EXPIRE`（任务） | 系统 | 每分钟：过了 `deadline` 仍 IN_PROGRESS 的会话按已保存答案自动提交（"到时自动提交"的服务端兜底） |
| `QB_TOPUP_START` | `qb.topup` | 建 `QbTopUp` 与 Stripe Checkout Session（`BlockingStep`，Stripe 幂等键 = 充值编号），返回支付页地址 |
| `QB_TOPUP_CONFIRM` | 系统（G9） | Stripe `checkout.session.completed`：钱包锁 → 记分录、`PAID` → 子流程 `QB_PENDING_SETTLE` |
| `QB_PENDING_SETTLE` | 系统 | 钱包锁内按提交时间顺序补发该商家的待支付奖励，直到余额不足；补完的发布从 `FUNDS_SHORT` 回到 `LIVE` |
| `QB_PAYOUT_ONBOARD` | `qb.payout.onboard` | 创建 Stripe Express 账户（首次）与开通链接，返回地址；结果由 `account.updated` 通知更新 `payoutsEnabled` |
| `QB_TRANSFER_RUN`（任务） | 系统 | 每日：余额 ≥ 门槛、`payoutsEnabled`、未封禁的用户各建一笔转账（全额）；超过人工审核额的进 REVIEW（审批） |
| `QB_TRANSFER_SEND` | 系统 | 钱包锁 → 分录（2120 → 2130）→ 提交后（`AFTER_COMMIT`）调用 Stripe Transfer（幂等键 = 转账编号） |
| `QB_TRANSFER_RESULT` | 系统（G9） | 成功：2130 → 1110；失败 / 撤回：2130 → 2120，站内信 |
| `QB_USER_BAN` / `QB_USER_UNBAN` | `qb.admin.users.ban` | 封禁：不能登录（G1 登录前检查）、不进转账 |
| `QB_BROADCAST_SAVE` / `QB_BROADCAST_DISPATCH`（任务） | `qb.broadcast.write` / 系统 | 定时发送：到时置 SENT；商家通知只能关联自己的进行中发布 |
| `QB_MESSAGE_MARK` | `qb.me` | 已读 / 点击 |
| `QB_PUBLICATION_TICK`（任务） | 系统 | 每分钟：SCHEDULED → LIVE、到期 → ENDED |
| `QB_FX_FETCH`（任务） | 系统 | 每日取 ECB 参考汇率（`BlockingStep`），手动覆盖优先 |
| `QB_RECONCILE`（任务） | 系统 | 每日余额核对（4.2）、与 Stripe 余额交易报告核对（以后，F-05） |

所有流程的业务规则违规以 422 与错误码返回（`QB_*`，三语文案）。

## 6. 查询与报表（SQL 模板，`queries/qb/`）

| 模板 | 用途 | 权限 |
|---|---|---|
| `qb.app.feed` | 首页卡片：可见性（LIVE + 地区交集 + 时间内 + 未满额）、关键字、筛选、排序（U-10 – U-14） | `qb.play` |
| `qb.app.publication` | 详情（U-20 – U-22）：基本信息、档位、资料、发布人、完成人数（1 小时 / 24 小时 / 周 / 月 / 累计） | `qb.play` |
| `qb.app.history` / `qb.app.ledger` / `qb.app.wallet` | 答题记录、交易流水、余额概览（U-40 – U-52） | `qb.me` |
| `qb.app.inbox` | 个人消息 ∪ 匹配的公告，带未读 | `qb.me` |
| `qb.sponsor.dashboard` / `qb.sponsor.publications` / `qb.sponsor.analytics.*` / `qb.sponsor.transactions` | M-10 – M-11、M-34、M-40 – M-43、M-50 – M-52 | 各自权限 |
| `qb.admin.users` / `qb.admin.transfers` / `qb.admin.monthly`（报表）/ `qb.admin.balances`（报表）/ `qb.admin.journal-export` | P-01、P-03、F-06、F-08、F-02 – F-03（CSV 汇总凭证） | 管理权限 |

- 完成人数与分析按索引直接统计（`QbAttempt (publicationId, submittedAt)`），模板缓存 10 分钟（需求 6.1 允许）；不另建汇总表。
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
- 未激活的用户不能开始答题；封禁的不能登录、不能收款。
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
