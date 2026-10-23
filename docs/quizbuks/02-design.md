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
quizbuks-web/                应用前端（`@jabiz/ui` 与 `@jabiz/client` 经 `link:`，与平台同一工具链，D34、D43；`plans/QP-pages.md`）
  app/                       答题人 PWA（挂在 /，入口 app）：独立的 pnpm 工程，自己的 lockfile
  sponsor/                   商家后台（挂在 /sponsor/，入口 sponsor）：同上
  */src/qb/                  两个 SPA 共用的代码（Kudos 格式化、流程与模板编号、手写的流程类型、共用组件与文案），两份相同（CI 比对）
  e2e/                       Playwright 端到端测试（对打包的 jar；Mailpit 收信、Stripe 桩）
deploy/quizbuks/             docker-compose 与部署说明
docs/quizbuks/               本文件等
```

一个可部署的 jar 提供三个前端与接口：

| 路径 | 前端 | 登录入口（G1） | 接受的角色 | 自助注册（授予） | 要求已验证才能登录 |
|---|---|---|---|---|---|
| `/` | `quizbuks-web/app`（PWA） | `app` | `QB_TAKER` | 是（`QB_TAKER`，`QB_APP_SELF_REGISTRATION`） | 否：未验证可登录浏览，QuizBuks 的写入一律要求已验证 |
| `/sponsor/` | `quizbuks-web/sponsor` | `sponsor` | `QB_SPONSOR`（且入驻已通过才能发布） | 是（`QB_SPONSOR`，`QB_SPONSOR_SELF_REGISTRATION`） | 是 |
| `/admin/` | 平台通用后台 + QuizBuks 扩展页 | `admin` | `ADMIN`（首个管理员）/ `QB_ADMIN_SUPER` / `QB_ADMIN_CONTENT` / `QB_ADMIN_FINANCE`（`QB_ADMIN_ROLES` 可改） | 否 | 否 |

不用 pnpm 工作区：`pnpm app:check` 读每个 SPA 目录自己的 lockfile，Gradle 只把 SPA 自己的目录与平台的包作为构建输入（`plans/QP-pages.md` 第 1 节）。
`jabizApp` 以 `spa("/", "../../quizbuks-web/app", entry = "app", pwa = Pwa.ON)`、`spa("/sponsor", "../../quizbuks-web/sponsor", entry = "sponsor")`
与 `spa("/admin", "../../frontend", entry = "admin")` 构建三者；`application.yml` 不写 `jabiz.web.spa`（用 jar 中的缺省）。

三个入口**分开登录**：入口决定接受哪些角色，签发的令牌只带这些角色的权限（同一邮箱既是答题人又是商家时，在 App 中拿不到商家权限，反之亦然）。
入口的机制属于平台（G1），本应用只声明三个入口与各自的角色（`application.yml`，Q2 D-Q2-1）。"待激活"即邮箱未验证（不另设状态）。

## 2. 角色与权限

| 角色 | 权限（应用自定义的权限码，前缀 `qb.`） |
|---|---|
| `QB_TAKER` | `qb.play`（浏览发布、答题）、`qb.me`（本人资料、钱包、流水、消息）、`qb.payout.onboard`（开通收款账户）、`qb.account.read`（本人资料与登录记录，Q2）、`qb.sponsor.avatar.read`（发布人头像，Q2） |
| `QB_SPONSOR` | `qb.sponsor.me`、`qb.content.write`、`qb.content.file.read`（读内容文件，Q3；Q5 再授予答题人）、`qb.ai.use`、`qb.publication.write`、`qb.topup`、`qb.sponsor.finance.read`、`qb.broadcast.write`、`qb.account.read`、`qb.sponsor.avatar.read`、`document.archive.read`（Q9d：下载本人签发的充值收据）、`qb.stats.read`（Q9e：本人发布的分析，只有汇总；管理员角色不持有，E-10） |
| `QB_ADMIN_CONTENT` | `qb.admin.users.read`、`qb.admin.quiz.read`（只读全部模版与版本，Q3）、`qb.content.file.read`、`qb.review.sponsor`、`qb.review.publication`、`qb.admin.content.write`（下架发布：管理员暂停、恢复、结束，Q10b；以后 FAQ、条款、公告）、`qb.sponsor.avatar.read`、`approval.decide`、`task.read`、`qb.topup.bank.approve`（Q13a：审批财务登记的银行转账充值） |
| `QB_ADMIN_FINANCE` | `qb.admin.users.read`、`qb.admin.finance.read`、`qb.payout.review`、`qb.reconcile`、`qb.sponsor.avatar.read`、`ledger.read`、`report.issue`、`report.archive.read`（Q9c：读已签发的月报）、`approval.decide`、`task.read`、`control.publish`（发布受控变更，超级管理员不持有）、`qb.journal.export`、`qb.journal.confirm`、`file.generated.read`（Q9d：分录导出、记录外部凭证号、下载存档的 CSV）、`qb.topup.bank.record`、`qb.topup.bank.approve`（Q13a：登记银行转账的到账；审批另一位登记的）|
| `QB_ADMIN_SUPER` | 上述全部（`control.publish`、`inbound.read` 与 `approval.decide` 除外：Q10b 的职责分离 `QbSodRules`）+ `qb.admin.users.ban`、`platform.param.read`、`control.propose`、平台安全管理权限、`security.protected-change.decide` 与 `approval.read`（平台 16u，D51：两位超级管理员互为受保护授权的四眼，一位申请、另一位审批；不是 `approval.decide`）（不再有 `platform.param.write`，见下） |

- 另有两个公用权限（Q1）：`qb.country.read`（读国家字典，五个角色都有）、`qb.setup`（执行 `QB_SETUP`，超级管理员；平台的 `ADMIN` 角色持有全部权限）。
- 不授予任何角色的权限：`qb.content.view.write`（数据视图的写权限，Q3、Q4）、`qb.content.system`（`QB_QUIZ_RELEASE_FILES`，只由 `QB_QUIZ_DELETE` 作为子流程运行）、`qb.publication.system`（Q4 的系统流程）、`qb.sponsor.system`（`QB_SPONSOR_APPROVAL_RESULT`，Q2）、`qb.stats.system`（Q9e 的逐项分析任务的每项流程 `QB_ANALYTICS_ITEM` 与其模板）。
  平台 D41（16l）已合入：`QB_SPONSOR_APPROVAL_RESULT` 与 Q4 的系统流程声明 `internal()`（只允许系统身份运行），`QB_QUIZ_RELEASE_FILES` 声明 `subProcessOnly()`；这些系统权限随之只起声明作用。
- 文件策略只能声明一个读取权限，所以商家头像的读取（商家本人、答题人、管理员）共用 `qb.sponsor.avatar.read`（Q2）。
- 行范围：答题人的数据视图以 `userId = 当前用户` 限定；商家的以 `sponsorId = 当前用户` 限定（平台 `fromContext("…", actorId)`）。
- 职责分离：大额转账的审核人不能是被转账人本人（平台 SoD 规则）；修改抽成比例、转账门槛等参数走四眼（`CONTROL_CHANGE_*`）：
  平台决策 D40，`QbParams.CONTROLLED`（抽成比例、转账门槛、人工审核额、转账开关、两个免审核开关；Q9d 加科目映射 `qb.journal.account.<科目>` 与收据开具方 `qb.issuer.*`）以 `ControlledParams` 声明，一切直接写入 422 `PARAM_CONTROLLED`。
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
| `QbPayoutAccount` | P（Q6b） | `userId`（唯一）、`provider`（STRIPE_CONNECT）、`externalAccountId`（唯一，`f.masked(qb.admin.finance.read, LAST4)`）、`country`、`status`（PENDING / ENABLED / RESTRICTED / DEAUTHORIZED）、`payoutsEnabled`、`detailsSubmitted`、`requirementsDue`、`stripeUpdatedAt`（以上 `processOnly`，由 Stripe 的 Connect 通知更新，只取更新的报告） | 用户的收款账户；银行信息由 Stripe 保存，平台不存银行账号。普通表（不是计划中的时态表）：通知按 Stripe 账户号查找，且只有表的唯一约束能保证一个 Stripe 账户只属于一个用户；历史由平台审计保留。开通链接另存普通表 `QbPayoutOnboarding`（每人一行、覆盖，只有本人的数据视图给出） |

### 3.2 内容：模版、版本、题目、资料

| 实体 | 类型 | 主要字段 | 说明 |
|---|---|---|---|
| `QbQuiz` | T | `ownerId`、`kind`（Q12：QUIZ / SURVEY，创建时选定、不可改）、`title`、`intro`（Markdown）、`cover`（文件）、`timeLimitSec`、`status`（DRAFT / VERSIONED）、`removed`、`aiGenerated`、`latestVersionNo`、`versionLabel`、`revision`、`editedAt`、`changedSinceVersion`、`questionCount`、`materialCount`（以上状态与计数 `processOnly`）、`clonedFrom` | 模版。只有拥有者可改（商家数据视图以 `ownerId` 限定 + 流程经该视图读取，不是自己的即 404）。"删除"即移除：`removed = true`，商家看不到，版本保留（Q3 D-Q3-6） |
| `QbMaterial` | T | `quizId`、`ownerId`、`seq`、`kind`（ARTICLE / LINK / VIDEO_LINK / PDF / IMAGES / AUDIO，不可改）、`title`、`description`、`body`（Markdown，ARTICLE）、`url`（LINK / VIDEO_LINK）、`pdf`、`audio` | 参考资料；视频只存链接（已确认）；只填其类型的内容字段；图片组的每张图为子实体 `QbMaterialImage`（`materialId`、`seq`、`image`、`caption`） |
| `QbQuestion` | T | `quizId`、`ownerId`、`seq`、`stem`、`image`（可选）、`points`（1–1 000；问卷为 1）、`selection`（Q12：只有问卷的题，SINGLE / MULTIPLE）、`aiJobId`（Q8a：来自哪个 AI 作业，可空） | |
| `QbOption` | T | `questionId`、`quizId`、`ownerId`、`seq`、`text`、`image`（可选）、`correct` | 2–6 个、至少 1 个正确（问卷没有正确项、`correct` 一律 false，Q12）：在"保存版本"时检查（`QuizCompleteness`；草稿允许不完整，M-22），提交审核时（Q4）再查 |
| `QbQuizVersion` | W | `quizId`、`ownerId`、`versionNumber`（1、2…，`label` 为 v1.0、v1.1…）、`title`、`content`（规范 JSON 文本：题目、选项、资料的完整快照，题与选项按 1 起的序号）、`contentHash`（平台 `ContentHash`）、`questionCount`、`materialCount`、`fullScore`、`versionedAt` | **整体快照**（分析 4.2），由"保存版本"（`QB_QUIZ_PUBLISH_VERSION`）显式生成，草稿的保存不生成版本（Q3 D-Q3-1）。没有文件字段。发布引用版本，答题与结算只读快照。没有商家数据视图（内容含正确答案、版本比模版活得久）：商家只经模板读取，模板只连接本人未移除的模版 |
| `QbVersionFile` | T | `quizId`、`ownerId`、`firstVersionNo`、`image` / `pdf` / `audio`（各对应一个文件策略，恰好一个非空） | 快照引用的每个文件按"模版 + 文件"一行，使平台的文件引用检查与孤儿清扫看得到 JSON 中的文件；普通时态实体（不是只写一次）：移除模版时写墓碑，被发布引用的版本的文件除外（Q4） |
| `QbAiJob` | P | `externalJobId`（平台作业号，唯一）、`sponsorId`、`quizId`、`kind`（QUESTIONS / COVER）、`params`（规范 JSON：题数、难度、语言、资料）、`model` 与 `servedModel`（拒答回退时不同，只给管理员）、`status`（QUEUED / READY / FAILED / CANCELLED / ACCEPTED / DISCARDED / EXPIRED）、`candidates`（规范 JSON，只在 READY 时）、候选 / 不合格 / 接受的题数、`inputBytes`、`estimatedTokens`（3 次尝试各自完整的输入与输出）、`inputTokens`、`outputTokens`、`fallbackInputTokens` / `fallbackOutputTokens`（回退模型所用的部分）、`costUsd`（按各模型标价的估算）、`zone`（请求时的部署时区）、`attempts`、`failureCode` / `failureMessage`（系统的说明）、`requestedAt` / `finishedAt` / `expiresAt` / `closedAt`；Q8b（V18）`cover1` – `cover3`（封面作业的候选图，`qb.content.image` 的文件，只在 READY 时，使清扫保留它们） | AI 生成请求（Q8a，`plans/Q8-ai.md` D-Q8-3）：普通表（状态在几分钟内连续变化、按作业号查找，同 `QbTopUp`），历史由平台审计保留；候选从不自动入库，经商家逐题校对后由 `QB_AI_ACCEPT` 写入题目（M-26 "必须人工校对"），7 天后过期，候选从作业中移除（提供方的原始回答仍在平台的作业结果中；`candidates` 不进审计） |
| `QbAiUsage` | P | `sponsorId`（全体一行为 `*`）、`month`（部署时区的年月）、`jobs`、`failedJobs`、`inputTokens`、`outputTokens` | 用量（M-10）：唯一 `(sponsorId, month)`，只在 `qb.ai:<商家>` → `qb.ai:*` 锁内由完成与失败流程累加；限额按它与未结束作业的预估计 |

### 3.3 发布与答题

| 实体 | 类型 | 主要字段 | 说明 |
|---|---|---|---|
| `QbPublication` | T | `sponsorId`、`quizId`（随版本冗余存放）、`versionId`、`versionLabel`、`name`、`regions`（多值，`Regions.TARGETS`：非空子集，不强制含 GLOBAL）、`startAt`、`endAt`、`timeLimitSec`、`fullScore`（取自版本）、`passScore`、`budgetCap`（可选）、`maxWinners`（可选）、`maxReward`（档位最高额）、`maxSpend`（预计最大支出）、`tierCount`、`status`（见 5.1）、`rejectReason`、`rejectCode`（如 `QB_PUBLICATION_EXPIRED_IN_REVIEW`）、`approvalRequestId`、`submittedAt`、`approvedAt`、`pausedAt`、`endedAt`、`endReason`（`EXPIRED` / `QUOTA` / `BUDGET`）、`revision`、`editedAt`（以上派生值与状态 `processOnly`）、`copiedFrom`（源的主键，不作引用） | 一次投放（Q4，`plans/Q4-publishing.md`）。只有草稿可改，提交即冻结内容与全部策略；审核用平台审批（`ApprovalSubject` `qb.publication`） |
| `QbRewardTier` | T | `publicationId`、`sponsorId`、`seq`、`fromScore`、`toScore`、`reward` | 每个发布至多 20 档；区间不重叠、覆盖 0 到满分、至少一档奖励大于 0（提交时 `TierCoverage` 检查）；可从 0 分起 |
| `QbPublicationSchedule` | P | `publicationId`（主键）、`quizId`、`versionId`、`nextDueAt` | 已提交且未退回草稿的发布：定时任务按 `nextDueAt` 取到期的，移除模版时按 `quizId` 找出要保留文件的版本（都是普通索引；时态表上按可变字段查找要扫全部历史） |
| `QbPublicationCounter` | P | `publicationId`（主键）、`completions`、`passes`、`winners`、`released`（历来每个完成释放的预占之和）、`spent` | 计数的**完成一侧**（Q10a4 锁拆分，`plans/Q10-lock-split.md`）：上架时与开始一侧在同一事务插入全 0 的一行（Q4），只由完成（提交、超时）在发布锁 `qb.pub` 内更新（4.3）；不进时态（否则每次答题都给发布追加一个版本） |
| `QbPublicationStartCounter` | P | `publicationId`（主键）、`starts`、`reservedTotal`（历来每个开始预占的最高奖励之和） | 计数的**开始一侧**（Q10a4）：上架时插入全 0 的一行，只由开始在开始锁 `qb.pub-start` 内更新。派生量只由两侧一起算：进行中 = `starts − completions`、预占 = `reservedTotal − released`；读两侧的地方（开始的锁内重读、首页、详情、商家与管理员的发布列表）一律**在一条语句中**读（同一快照：不会出现负的进行中） |
| `QbPublicationCard` | P | `publicationId`（主键）、`sponsorId`、`status`、`regions`、`startAt`、`endAt`、`name`、`quizTitle`、`cover`、`versionId`、`maxReward`、`fullScore`、`passScore`、`timeLimitSec`、`questionCount`、`budgetCap`、`maxWinners`、`tiers`（档位的规范 JSON）、`sponsorCountry`、`wentOnAt`、`kind`（Q12：QUIZ / SURVEY，取自版本的快照） | 上架时随计数行插入（Q5）。除状态外全部在提交时冻结，状态由发布的流程在发布锁内同步。答题人的首页、详情、档位与答题流程只读它（时态表上按状态、时间、地区筛选要读全部版本） |
| `QbAttempt` | P → 结束后流程拒绝修改 | `userId`、`publicationId`（二者唯一，一人一次）、`sponsorId`、`versionId`、`startedAt`、`deadline`、`status`（IN_PROGRESS / SUBMITTED / EXPIRED）、`currentIndex`、`optionOrder`、`answers`、`result`（三者都是规范 JSON 文本）、`submittedAt`、`score`、`fullScore`、`passed`、`tierId`、`reward`、`fee`、`rewardStatus`（NONE / PAID / PENDING）、`rewardPaidAt`、`ledgerTransactionId` | 答题会话（Q5）。进行中只有 `answers`、`currentIndex` 会变（保存进度，U-41）；`result`（含每题正确选项；问卷为空列表，Q12）在完成时才写。问卷的提交须回答全部题目（单选恰 1 项、多选至少 1 项），完整即满分、得唯一一档的奖励；超时按已保存的答案评分，不完整即不付（`plans/Q12-survey.md`）。不建逐题答案表：每次保存只写一行，题目分析（Q9）用 `jsonb_array_elements` 展开。一人一次按发布：版本是模板，发布定义范围，用户可参加满足条件的任何发布，不论是否参加过同一版本的其他发布（需求方 2026-10-11 确认）。数据视图（Q9c，C-2）：缺省视图不给任何角色读取（数据视图不能去掉字段，而会话带奖励与抽成），财务读只读视图 `finance:QbAttempt`，支持人员经不含金额的 `qb.admin.attempts` 读取 |
| `QbPublicationView` | P | `userId`、`publicationId`（唯一）、`firstViewedAt` | 漏斗的"浏览"：每人每发布只记第一次（`QB_VIEW`） |
| `QbPublicationStats` | P | `publicationId`（主键）、`last1h`、`last24h`、`last7d`、`last30d`、`refreshedAt` | 近期完成人数，由任务 `qb.publication-stats` 每 10 分钟分块写入（只写变化的行；运行时间记在 `QbStatsRun`）；已认证的模板没有缓存；累计完成数取计数行，实时 |
| `QbAnalyticsHour` | P | `publicationId`、`sponsorId`、`hourStart`（UTC 整点）、`views`、`starts`、`completions`、`passes`、`winners`、`spent`、`gap` | 商家分析（Q9e，`plans/Q9e-analytics.md`）的按小时的桶：任务 `qb.analytics` 把计数自上次以来的增量加进一个小时（本次运行的小时；跨过整点后的第一次运行加进上一次运行的小时）；没有增量的小时没有行；`gap` = 含任务停机期间的增量或首次运行之前的历史。唯一 `(publicationId, hourStart)`，保留 400 天（任务删除更早的） |
| `QbAnalyticsCursor` | P | `publicationId`（主键）、`sponsorId`、上次变化时的六个累计值、`viewsUntil`、水位线 `watermarkAt` / `watermarkId`、已分析的完成数与按时提交 / 超时 / 用时合计、各题合计 `questions`（规范 JSON）、公开的同一组值 `shown*` / `shownAt`、`finalizedAt` | 任务对每个发布的进度。当前的合计只给任务与管理员；商家只经模板看到 `shown*`，它只在新增至少 k 个完成后更新（E-4） |
| `QbQuestionStat` | P | `publicationId`、`sponsorId`、`questionNo`（唯一二者）、`completions`、`correct`、`shownAt` | 商家看到的每题作答数与答对数（得分 > 0；多选题全对），只在公开值变化时写 |
| `QbPublicationOwner` | 视图 | `publicationId`、`sponsorId`（卡片的） | 只读，经限本人的数据视图读取：答题人的模板据此排除本人的发布（不能开始） |

### 3.4 钱包与资金

钱包**就是平台账本**（分析 4.1）。另有一张余额表供锁内快速判断，并每日与账本核对（F-06）。

| 实体 | 类型 | 主要字段 | 说明 |
|---|---|---|---|
| `QbWallet` | P | `walletId`、`ownerId` + `kind`（SPONSOR / TAKER，二者唯一）、`balance`（`>= 0` 检查约束）、`pendingCount`、`pendingAmount`（待支付）、`pendingOut`（转账中，Q6b）、`held`（开立中的争议冻结，`0 ≤ held ≤ balance`，Q6a；不是账本分录，可用余额 = 余额 − 冻结） | 余额缓存，只在钱包锁内由记账流程同步更新；真相在账本，每日核对（F-06）。Q5 只为商家建（第一次入账或记待支付时在锁内建立，没有行即余额 0）；充值到账、退款扣回同样在商家钱包锁内改（Q6a）。用户（`TAKER`）的行由 Q6b 按账本重算维护；在此之前用户余额按账本维度汇总（`qb.app.wallet`） |
| `QbTopUp` | P（Q6a） | `topUpId`、`sponsorId`、`method`（STRIPE / BANK，Q13a，不可变）、`amount`（`> 0`；银行转账登记时改为实收）、`status`（OPENING / OPEN / PAID / EXPIRED / FAILED；银行转账为 AWAITING / REPORTED / REVIEW / PAID / EXPIRED / CANCELLED，状态机）、`createdAt`；支付页：`checkoutSessionId`（唯一）、`checkoutUrl`、`expiresAt`、`failureCode`；到账：`paymentIntentId`（唯一）、`chargeId`、`balanceTransactionId`、`livemode`、`paidAt`、`confirmedBy`（入站事件号或 `job:<核实作业>`）、`ledgerTransactionId`（唯一）；手续费：`stripeFee`、`netAmount`、`feeTransactionId`；原币（F-04）：`presentmentCurrency`、`presentmentAmount`、`fxRate`；退款与争议：`refundedAmount`、`refundShortfall`、`disputeId`、`disputeStatus`、`disputeAmount`、`disputeClawback`、`disputeShortfall`、`disputeHeld`（开立中的争议冻结的部分）；`checkReason`、`checkRequestedAt`；银行转账（Q13a）：`transferCode`（唯一）、`requestedAmount`、`payerName`、`transferDate`、`reportedAt`、`receivedOn`、`bankReference`（与 `receivedOn` 一起唯一）、`recordedBy`、`recordedAt`、`reviewFrom`、`approvalRequestId` | 充值（`plans/Q6-payments.md` D-Q6-2；银行转账 `plans/Q13-bank-transfer.md`）。普通表：通知按后写入的会话与付款编号查找、状态在几分钟内连续变化；历史由平台审计保留。除金额外都是 `processOnly`，只在商家钱包锁内由流程改；数据库兜底：`PAID` 必有分录、扣回不超过金额 |
| `QbReconRun` / `QbReconItem` | W（Q6a） | 运行：`scheduledTime`（唯一）、`status`（OK / DIFF）、`checkedAt`、`itemCount`、`summary`（各项差异数的规范 JSON）；差异：`runId`、`check`（字典 `recon-check`）、`partyId`、`reference`、`expected`、`actual`、`difference` | 每日核对（F-06）的结果，只写一次 |
| `QbJournalExport` / `QbJournalExportConfirmation` | W（Q9d） | 导出：`periodFrom`、`periodTo`（日本日期，含两端）、`granularity`（MONTH）、`voucherNo`（`QBJ-<年>-00001`，唯一）、`periodEnd`（期末时刻，下一次导出以它为上一期末）、`knownAt`（截止的记录时刻）、`previousExportId`（唯一：导出成链）、`lineCount`、`transactionCount`、`debitTotal` = `creditTotal`、`fileId` / `fileSha256`（导入用的凭证 CSV）、`detailPartCount`（明细的文件数；各文件为 `QbJournalExportPart`：`exportId`、`partNo`、`fileId`、`sha256`、`rowCount`）、`exportedBy`；确认：`exportId`（唯一）、`externalVoucherNo`、`confirmedBy` | 分录导出（F-01 – F-03，`plans/Q9d-journal-receipts.md`）：一月一张汇总凭证，`(periodFrom, granularity)` 唯一，CSV 原样存于平台 `sys_generated_file`；只写一次、只插入 |
| `QbTopUpReceipt` | W（Q9d） | `topUpId`（唯一）、`sponsorId`、`runId`（平台单据 `sys_document_run`）、`documentNo`、`language` | 充值收据（F-09）：每笔已到账的充值至多一张，PDF 原样存于平台单据存档 |
| `QbTransfer` | P（Q6b） | `userId`、`externalAccountId`（遮蔽）、`amount`、`fee`、`net`（`amount = fee + net`、`net > 0`）、`status`（REVIEW / SENDING / SENT / FAILED / CHECK / REJECTED / REVERSED）、`approvalRequestId`、`stripeTransferId`（唯一）、`sendTransactionId`（唯一）、`settleTransactionId`、`reversalTransactionId`、`reversedAmount`、`failureCode`、`failureMessage`（只有系统的说明，审核人的意见留在审批中）、`sendEndedAt`、`releasedBy` / `releasedAt`、时间 | 自动转账（4.7）。每人至多一笔未结束的（REVIEW / SENDING / CHECK，部分唯一索引）。大额先进 REVIEW 走审批（准备人 = 收款人）。被驳回或被 Stripe 撤回（含部分）的转账使用户暂停，直到财务放行（`QB_TRANSFER_HOLD_RELEASE`）。`QbStripePayout`（P）记平台余额打款到银行（`payoutId` 唯一） |
| `QbFxRate` | T | `currency`、`rateDate`、`perJpy`、`source`（ECB / MANUAL） | 显示用参考汇率（"约合 X USD"），可手动覆盖；不参与记账 |
| `QbStripeEvent` | — | — | 不建：Stripe 通知由平台入站 Webhook（G9）原样存档与去重 |

### 3.5 消息与内容

| 实体 | 类型 | 主要字段 | 说明 |
|---|---|---|---|
| `QbMessage`（Q7c） | P | `recipientId`、`type`（SYSTEM / REWARD / REMINDER）、`kind`（REWARD_PAID / REWARD_PENDING / TRANSFER_SENT / TRANSFER_FAILED / PAYOUT_ACTION_NEEDED / PAYOUT_REMINDER）、`title`、`body`（三语，创建时按消息资源渲染，Q7-15）、`publicationId`、`action` / `actionId`（RESULT / WALLET / PAYOUT）、`sourceKey`（唯一：同一事件只成一条）、`createdAt`；`processOnly`：`readAt`、`clickedAt` | 个人消息：只由事件的消费者（`QB_MESSAGE_FROM_EVENT`）与提醒任务写入，不在答题与支付的锁内写；普通表（已读要改行），不含他人的数据 |
| `QbBroadcast`（Q7c） | T | `senderKind`（SPONSOR / PLATFORM）、`senderId`、`audience`（APP / SPONSOR）、`type`（SYSTEM / NOTICE / PROMO）、`title`、`body`（三语，商家通知为纯文本、不得含链接）、`targetMode`（ALL / REGIONS / USERS）、`regions`、`targetCount`、`publicationId`（商家通知必填，须为本人的、可接受新答题或已排期的发布）、`scheduledAt`、`expiresAt`、`status`（DRAFT / SCHEDULED / SENT / CANCELLED / WITHDRAWN）、`sentAt`、`audienceCount`、`cancelReason`、`withdrawnAt` / `withdrawnBy`（种类：SENDER / ADMIN / SYSTEM，评审 L3）/ `withdrawReason`、`sourceKey`（法律文本的公告为 `legal:<版本>`）、`revision` | 公告与商家通知的编辑历史：**不按人复制**（读时匹配） |
| `QbBroadcastTarget` | P | `broadcastId`、`userId` | 指定的用户（至多 1,000） |
| `QbBroadcastFeed` | P | 发送时冻结的副本（发送方、受众、类型、范围、地区、发布、标题、正文、`sentAt`、`expiresAt`）与 `live` | 收件箱只读 `live` 的行（部分索引 `(audience, expires_at) WHERE live`）；撤回、过期与发布结束（每分钟的任务 `qb.broadcast-expire`）即 `live = false`（评审 H1） |
| `QbBroadcastStats` | P | `broadcastId`、`reads`、`clicks`、`conversions`、`snapshotAt` | 商家通知统计的每日快照（任务 `qb.broadcast-stats`）：商家只读它（评审 M2） |
| `QbBroadcastSchedule` | P | `broadcastId`、`dueAt` | 定时发送的到期表（同 `qb_publication_schedule`），逐项任务 `qb.broadcast-dispatch` 读它 |
| `QbBroadcastReceipt` | P | `userId`、`broadcastId`（两者唯一）、`readAt`、`clickedAt` | 公告的已读与点击（M-61 统计）；个人消息的在消息行上 |
| `QbInboxState` | P | `userId`、`readAllBefore`（"全部已读"）、`readThrough`（之前的公告都已逐条读过：徽标从它之后数起，评审 H1）、`promoOff`（不收推广） | 一人一行；推广只能退订（Q7-11） |
| `QbLegalDoc` | T | `kind`（`TERMS_APP` / `TERMS_SPONSOR` / `PRIVACY` / `COMMERCE_NOTICE`，不可变、唯一）、草稿 `title`、`body`、`changeSummary`（多语言 Markdown）；`processOnly`：`latestVersionNo`、`lastPublishedAt` | 法律文本的草稿（Q7b）：内容管理员在通用后台直接编辑；`QB_SETUP` 建立四份明确标注的测试占位草稿，不发布 |
| `QbLegalVersion` | W（`writeOnce`） | `kind`、`versionNumber`（与 `kind` 唯一）、`title`、`body`、`changeSummary`、`requiresConsent`、`publishedAt`、`publishedBy`、`effectiveAt`、`contentHash` | 只经 `QB_LEGAL_PUBLISH`（二次验证）写入；当前版本 = `effectiveAt` 已到、未撤回的最大版本号（`messaging.LegalRules`），预定生效不需要任务；公开只读（公开视图不含发布人与哈希） |
| `QbLegalWithdrawal`（Q7b 评审 M1） | W（`writeOnce`） | `legalVersionId`（唯一）、`kind`、`versionNumber`、`withdrawnAt`、`withdrawnBy`、`reason` | 只经 `QB_LEGAL_WITHDRAW` 写入：撤回尚未生效的预告版本；撤回的版本永不生效、不要求同意，版本号不再使用；公开视图只有种类、版本号与时间 |
| `QbConsent` | W | `userId`、`kind`、`legalVersionId`、`versionNumber`、`acceptedAt`、`entry`、`source`（`WELCOME` / `ONBOARDING` / `RECONSENT`） | 同意记录；须同意版本 = 已生效版本中须同意的最大版本号（第一版总是），已同意的版本不低于它即可 |
| `QbFaqEntry` | T | `audience`（APP / SPONSOR / ALL）、`category`、`question`、`answer`（多语言，英语必填）、`seq`、`published` | 帮助中心（Q7b）；内容管理员在通用后台直接编辑，公开视图固定为已发布 |

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
| 充值到账（Stripe 通知或核实） | `QB_TOPUP_CONFIRM` / `QB_TOPUP_CHECKED` | 1110 Stripe 余额（记录的金额） | 2110 商家预收款（商家） |
| 银行转账充值获批（Q13a：财务登记实收、另一位管理员批准；记账时间为批准时刻） | `QB_TOPUP_BANK_APPROVAL_RESULT` | 1120 银行存款（实收） | 2110 商家预收款（商家） |
| Stripe 手续费确定（作业读取余额流水） | `QB_TOPUP_FEE_RECORD` | 5110 支付手续费 | 1110 Stripe 余额（为 0 时不过账） |
| 退款 / 争议败诉（Stripe 后台发生；扣回可用余额以内的部分，余下为差额待办） | `QB_TOPUP_REFUNDED` / `QB_TOPUP_DISPUTE_CLOSED` | 2110 商家预收款（商家） | 1110 Stripe 余额 |
| 答题奖励结算 | `QB_ATTEMPT_SUBMIT` / `QB_ATTEMPT_EXPIRE` / `QB_PENDING_PAY` | 2110 商家预收款（商家，发布）：奖励 | 2120 应付用户奖励（用户，发布）：奖励 − 抽成；4110（发布）：抽成（当前 0，为 0 时不出该行） |
| 转账发出（Q6b） | `QB_TRANSFER_CREATE` / `QB_TRANSFER_APPROVAL_RESULT` | 2120 应付用户奖励（用户）：金额 | 2130 转账中（用户）：实付；4110：手续费（为 0 时不出该行） |
| 转账成功（作业结果或核实） | `QB_TRANSFER_SENT` / `QB_TRANSFER_CHECKED` | 2130 转账中（用户） | 1110 Stripe 余额 |
| 转账确定失败（Stripe 拒绝创建，或核实确定没有） | `QB_TRANSFER_SEND_FAILED` / `QB_TRANSFER_CHECKED` | `LEDGER_REVERSE` 发出交易（2130 与 4110 回到 2120） | |
| 转账被撤回（`transfer.reversed`，按累计金额） | `QB_TRANSFER_REVERSED` | 1110 Stripe 余额 | 2120 应付用户奖励（用户）（手续费不退） |
| Stripe 打款到银行（`payout.paid`；`payout.failed` 冲正） | `QB_STRIPE_PAYOUT_RECORD` / `QB_STRIPE_PAYOUT_FAILED` | 1120 银行存款 | 1110 Stripe 余额 |

- 待支付奖励**不入账**（需求 7.1）：只在 `QbAttempt.rewardStatus = PENDING` 与钱包的待支付计数中记录。
- **抽成按结算时**生效的受控参数 `qb.creator-fee-rate` 计算（补发时用补发时的比例），向下取整到整数 Kudos（`RewardSplit`）；来源单据为答题会话（`QbAttempt`），记账时间为完成（或补发）时间。
- 更正一律冲正（平台 `LEDGER_REVERSE`）。
- 充值、手续费、退款的分录来源都是 `QbTopUp`；到账的记账时间是 Stripe 事件的 `created`（付款发生的时间），手续费的是到账时间。
- 转账的分录来源都是 `QbTransfer`；结果不确定（超时、租约丢失、5xx、查询失败）**不冲回**，进入 `CHECK`，由每小时的核实按 `transfer_group` 向 Stripe 查询后定论（Q6b）。
- 余额核对（F-06，每日任务 `qb.reconcile`，Q6a）：每个商家的 `QbWallet.balance` = 2110 按当事人的余额；待支付笔数与金额 = `PENDING` 会话；2110 合计 = 商家钱包合计；
  全部分录借贷平衡；每笔充值的到账、扣回与手续费分录与记录相符；Q6b 另加：用户钱包 = 2120 / 2130 按当事人的余额（缓存由事件同步，只有连续两次运行都不符才算差异，
  其间发布 `qb.taker-wallet.resync`），每笔转账的 2120 与 2130 分录与其状态相符。每项比较是一条语句（一个快照），模板只返回差异行；
  有差异则记 `QbReconRun` / `QbReconItem`、建待办给财务、发布 `qb.reconcile.diff`（Q9 的导出据此停止）。

### 4.3 并发与锁

| 锁（`HoldLock.exclusive`） | 谁持有 | 保护什么 |
|---|---|---|
| `qb.pub:<发布>`（**发布锁**） | 提交、超时自动提交、完成时的移动子流程；发布的全部编辑、审核与状态流程 | `QbPublicationCounter`（完成一侧）、发布、卡片、排程行与状态 |
| `qb.pub-start:<发布>`（**开始锁**，Q10a4） | 开始答题（新的开始，含被拒绝的）；改变已上架发布状态的流程（暂停、恢复、定时上下架的每项、预算恢复的每个发布），在发布锁之后 | `QbPublicationStartCounter`（开始一侧）；"状态改变提交之后没有新的开始" |
| `qb.wallet:<种类>:<所有者>`（如 `qb.wallet:SPONSOR:<用户>`） | 结算、补发待支付、开发入账（Q5）、充值到账、转账发出与结果 | `QbWallet` 与该当事人的账本分录 |
| `qb.account:<用户>` | 资料、入驻、商家资料、审批结果、封禁 / 解封（Q2）；收款账户的开通结果与 Stripe 通知（Q6b）；发布的审批结果与管理员恢复（取商家的，在发布锁之前，Q10b 评审 L1 与核查） | 该用户的 `QbProfile`、`QbSponsor`、`QbUserBan`、`QbPayoutAccount`；"封禁与发布获批依次进行" |
| `qb.wallet:TAKER:<用户>` | 用户钱包同步、转账的全部流程（Q6b） | 用户的 `QbWallet`（`TAKER`）、`QbTransfer` 与其 2120 / 2130 分录 |
| `qb.topup:<商家>`（Q10b） | 只有充值开始 `QB_TOPUP_START`，单独持有、之后不取别的锁 | 新商家累计上限：同一商家的开始依次进行，未结的充值计入上限 |

- Q6a 的充值流程（开始除外）、到账、过期、核实、手续费、退款与争议，以及 Q13a 银行转账的报告、取消、登记、审批结果与过期，**都只取 `qb.wallet:SPONSOR:<充值的商家>`**（银行转账的开始与卡支付的开始同取 `qb.topup:<商家>`）（锁名取自服务端的记录，不取自 Stripe 的元数据），
  先在锁外按编号找到充值、再取锁、锁内重读；每日核对不取业务锁。所以不会与"发布锁 → 钱包锁"成环。
- Q6b：用户钱包锁只由同步与转账流程持有，它们不取其他锁（读收款账户与封禁不取账户锁）；结算奖励不取用户钱包锁（缓存由事件跟上）。
  重算多个用户的 `QB_TAKER_WALLET_RESYNC` 按用户编号排序依次取锁，其他流程至多持一把，因此不成环。
- **锁拆分**（Q10a4，`plans/Q10-lock-split.md`，需求方 2026-10-17 确认 LS-1 至 LS-8）：开始只取开始锁、完成只取发布锁，两段并行；它们只在计数上相遇，所以计数拆成两张表（3.3），各自只有一个写者。
  - **代数**（计划 2.3）：记最高奖励 `m`、名额上限 `W`、预算上限 `B̂`，开始检查名额量 `Q = winners + (starts − completions)` 与预算量 `B = spent + (reservedTotal − released)`：`Q < W` 且 `B + m ≤ B̂` 才放行（`AttemptRules.limitRefusal` 不变）。
    每次完成使 `Q` 变化 `(奖励 > 0 ? 1 : 0) − 1 ≤ 0`、`B` 变化 `奖励 − m ≤ 0`，所以开始读到较旧的完成一侧只会**更严格**；开始一侧在开始锁内是确切的。因此在每个提交点 `Q ≤ W`、`B ≤ B̂`，中奖人数与已花从不超出。
    代价：接近上限时开始可能看不见正在提交的完成而多拒一次（LS-8，从不多放；首页本来就有同样的时差）。
  - **结束（`ENDED`）只看完成一侧**：完成在发布锁内读确切的 `winners`、`spent`，`PublicationRules.endsBy` 与 `PublicationMoves.moveAfter` 照旧，状态只从 `RUNNING` 迁到 `ENDED` 一次。
  - **完成时的移动子流程不取开始锁**（LS-5）：结束之后限额检查对任何开始都拒绝（上一条的代数），预算不足本是软检查（开始在锁外读钱包，同时被放行的人完成后记为待支付）。
    **这两点是前提**：若改成在开始时硬性预占商家余额，或改变限额的检查，必须重新论证；且不能在持钱包锁时取开始锁（违反下面的锁序）。
  - **改变已上架发布状态的流程持两把锁**（LS-4）：`QB_PUBLICATION_PAUSE`、`RESUME`、`ADVANCE`（逐项任务的每项）、`FUNDED`（`FUNDS_RESTORED` 在一个事务中按编号升序逐个调用）在发布锁之后、读取之前取开始锁（`PublicationEditing.lockStarts`），
    所以暂停等提交之后没有新的开始、之前开始的都已计入。编辑、删除、提交审核、撤回、审批结果（草稿与待审核的发布没有卡片，开始一律 404；审批结果插入卡片与两行计数同一事务）、提交、超时与完成移动不取开始锁。
  - **读计数的地方**：开始的锁内重读是一个 `QueryEntities.all`（本人会话、卡片、开始一侧、完成一侧，一条语句）；提交与超时只读完成一侧（不读开始一侧）；模板 `qb.app.feed`、`qb.app.publication`、`qb.sponsor.publications`、`qb.admin.publications` 在一条 SQL 中 JOIN 两表；
    通用后台的两个默认数据视图只显示各自的列。
- 加锁顺序固定：**（账户锁 `qb.account:<用户>` →）模版锁 `qb.quiz:<模版>`（提交发布时共享）→ 按发布编号升序：[发布锁 `qb.pub:<发布>` → 开始锁 `qb.pub-start:<发布>`] → 平台审批的案件锁 `jabiz.approval:*` → 钱包锁 `qb.wallet:SPONSOR:<商家>`**（Q4，D-Q4-5；Q2 D-Q2-10；Q10a4）。
  开始只持开始锁；状态流程在开始锁之后只可能再取审批锁：`ADVANCE` 撤回过期的审核（`WithdrawApproval.when`，只在审核过期时取；那时发布还在待审核，没有卡片，也没有开始在等），其余不再取锁（`FUNDS_RESTORED` 之后取的是编号更大的发布的一对锁）；
  持审批锁的流程（提交审核、撤回、审批结果）从不再取开始锁；持钱包锁之后只再取同一发布的发布锁（完成移动，本事务已持有，重入不等待），没有流程持钱包锁去等开始锁。
  `qb.wallet:TAKER:<用户>`、`qb.journal`、收据与转账的锁照旧独立。账户锁总在最前：封禁持它按发布编号升序以子流程暂停商家进行中的发布（各取发布锁与开始锁），
  发布的审批结果与管理员恢复（`QB_PUBLICATION_ADMIN_RESUME`）先在锁外读发布（商家不可变）、取商家的账户锁、再取发布锁（`PublicationEditing.lockSponsorAccount`；Q10b，评审 L1 与核查：封禁与获批、恢复依次进行，它们看得到封禁，封禁看得到已上架的发布）；提交发布只读入驻与封禁，不取账户锁；只有提交发布同时取模版锁与发布锁，移除模版只取模版锁（独占），两者因此不会交错。只持钱包锁的流程（充值、补发、转账）不再取发布锁（补发不改发布计数，计数在提交时已算入），因此不会死锁。
- **晚取锁**（Q5）：提交先在锁外读会话、版本与抽成比例，锁内只重读会话、计数与卡片（发布与排程行只在这次完成使它结束或预算不足时才读，子流程 `QB_PUBLICATION_COMPLETION_MOVE`，Q10a），评分后（奖励大于 0 时）再取钱包锁、过账与写入；
  开始只取开始锁（Q10a4；不取钱包锁：商家余额是软检查，并发多出的人完成后记为待支付）；继续答题与保存进度不取锁（会话行的乐观版本）。
  **锁内的语句**（Q10a3，平台 16p-1、决策 D46）：锁内互不依赖的重读合成一条（`QueryEntities.all`：开始为本人会话、卡片与计数的两侧，提交为会话、完成一侧与卡片）；
  开始在锁外与会话、卡片同一条语句读发布，新会话对发布与版本的引用检查复用本事务的读取（只有草稿可删，草稿没有卡片）；会话、计数与钱包按本事务读到的版本更新（版本 CAS 兜底），审计随更新同一条语句写出。
  平台 16p-2（事务内写缓冲，决策 D46）之后锁内的写入在 `COMMIT` 之前合成一条（子流程的操作记录由下一条取锁的语句带出）：开始锁内 3 条、付奖的提交在发布锁内 7 条、使发布结束的那次完成 12 条、暂停在发布锁内 7 条（含开始锁 1 条）；
  应急开关 `jabiz.storage.write-batching.enabled=false` 时为 16p-1 的 5、14、23、9 条（Q10a 为 12 与 48；锁拆分不变）。由 `AttemptStatementBudgetIT` 与 `AttemptStatementBudgetWithoutHeldWritesIT` 断言（并断言各自取哪几把锁）；实测见 `perf/Q10a-load-test.md` §9、§10、§11。
  恢复预算不足的发布（`QB_PUBLICATION_FUNDS_RESTORED`）由补发清空后发布的事件在另一事务中进行，只取发布锁与开始锁（不取钱包锁），因此锁序不成环。
- **取锁前排队**（Q10a2，平台 16o、决策 D45；Q10a4 分为两族）：`QB_ATTEMPT_START`（键为输入的发布）声明准入 `admission("qb.pub-start", …)`（`PublicationMoves.START_LOCK_FAMILY`，键 = 开始锁名 `qb.pub-start:<发布>`）；
  `QB_ATTEMPT_SUBMIT`（键为 App 带上的 `publicationId`）与逐项任务的 `QB_ATTEMPT_EXPIRE`（键为选取模板 `qb.system.attempts-due` 给出的发布）声明 `admission("qb.pub", …)`（`PublicationMoves.LOCK_FAMILY`，键 = 发布锁名）。
  族名与锁名由同一个方法生成（平台不告警）；开始锁用连字符而不是 `qb.pub:start:…`：平台按"锁名以 `族名:` 开头"归族，那样开始会与提交挤同一个队列。
  它们在借连接、开事务**之前**按族与发布在进程内排队：每族每个发布每个实例同时至多 2 个在事务中（一个持锁、一个在锁上等），
  队列 50、至多等 2 秒（`application.yml` 的 `jabiz.process.admission.families[qb.pub]` 与 `[qb.pub-start]`），超出即 503 `PROCESS_BUSY`（带 `Retry-After`），不占连接；
  所有排队的流程合计至多用 `max-active` 个许可（缺省半个连接池），首页与详情总有连接可用；拆分后一个热门发布至多占 4 个许可（开始 2 + 完成 2），池与 `max-active` 的建议见 `perf/Q10a-load-test.md` §10。继续答题的开始同样排队（16o A-7，每个约 12 ms）。
  **正确性仍只靠两把锁**：准入只是进程内的准入控制，多实例时各自排队；提交在锁前核对输入的发布与会话的发布一致，否则 422 `QB_ATTEMPT_PUBLICATION_MISMATCH`（排错了队的请求不会取另一把锁）；
  提交的 `publicationId` 必填（缺即 400；QuizBuks 未上线，没有旧客户端要照顾：不排队的提交会在连接上等锁，重新造成池耗尽）。逐项任务的项被准入拒绝记为 `BUSY`，下一分钟的运行再选到它；
  其间迟到的提交照样按截止加宽限判断（EXPIRED），不因任务忙而放行。
  **副作用**：`opTime` 在取得许可之后才取，所以排队（至多 2 秒）与 App 的重试会吃掉提交的 5 秒宽限：截止前不久按下的提交可能以 EXPIRED 结束，由超时任务以**已保存的**答案结束。
  因此 App 提交前先保存未存的答案（保存不取发布锁、不排队），截止前 2 秒再保存一次；过了截止加宽限即停止重试、去结果页。截止前最后不到一秒的改动可能不计入（与原来断线时相同）。发布的状态流程（暂停、恢复、到期上下架、预算恢复）不声明（少而短）。
  App 收到 503 `PROCESS_BUSY` 时显示"人多，正在重试"，按 `Retry-After` 与加倍的退避（1、2、4、8 秒，至多 6 次、等待合计至多 60 秒，离开页面即停）以**同一个** `Idempotency-Key` 重试（`app/src/play/busy.ts`），
  一次提交至多结算一次（会话只结束一次；同键重试得到第一次的回答）。入口限流：开始与提交各每人每分钟 60 次（`jabiz.process.rate-limits`，429 `RATE_LIMITED`），远高于真人的节奏，只限制脚本。
- 【缺省 Q11】单个发布的提交在发布锁上串行（开始在开始锁上串行，两者并行，Q10a4）。Q5 的基线（`PlayThroughputIT`，见 `plans/Q5-answering.md` 的实施记录）由 Q10 压测对照；退路依次为降低过账成本、按发布预留预算、分批过账。每次结算是一个短事务（读快照、写答案、两行余额、一笔 2–3 行的分录），预计每秒数十次。
  如压测不足，把"商家钱包"拆为按发布预留：发布上架时从商家钱包转入发布预算，结算只取发布锁（以后的优化，不在 MVP）。

### 4.4 奖励的保留（Q10b，评审 M3 与 M-b）

转账只转 2120 余额中**未保留**的部分（`TransferRules.transferable`）。保留中的奖励由模板 `qb.wallet.taker-transferable`（转账，在用户钱包锁内）与
`qb.app.wallet`（App 钱包页）在 SQL 中算出，模型写在 `wallet.RewardHolds`（纯 Java，jqwik 断言闭式与逐笔分配一致）：

- 用户的 2120 分录按入账时刻、再按流程的先后（`processSeqId`）、同一时刻先贷后借排序，每行归为四类：**争议冻结**（来源为答题的奖励，其商家有未结争议，且入账不早于被争议付款的 `paidAt`）、
  **按时间保留**（其余奖励中最近 `qb.transfer.hold-days` 天入账的）、**自由**（其他贷方：较旧的奖励、转账冲正等）与**借方**（转出）。
- 钱是同一笔余额，哪一笔借方带走了哪一笔贷方只能约定。约定与转账的做法一致：**每笔借方只取它之前入账的，先取自由的、再取按时间保留的、最后取争议冻结的**。
  因此在争议之前已转出的奖励不再计入（它随那次转账走了），其他商家的奖励只在余额不够同时留下两者时才被留下；转账发生时已冻结的奖励不会被当作已转出。
- 闭式：记各行处的累计借方 `D(t)`、累计自由 `F(t)`、累计按时间保留 `R(t)`，借方取走的保留部分 `c = max(0, max_t(D − F))`、其中取走的争议冻结部分
  `c' = max(0, max_t(D − F − R))`；保留 = 保留类贷方之和 − `c`，争议冻结 = 争议类贷方之和 − `c'`，再限在 0 与余额之间（`held ≤ balance`，可转 = 余额 − 保留 ≥ 0）。
- `heldOpen` 是争议冻结的部分，`heldUntil` 是最后一笔按时间保留的奖励到期之时（只在仍有按时间保留的部分时给出）。两模板的参数视图不允许时间旅行，参数按服务端时钟读取（评审 L9）。
- **转账退回的钱**（核查修正）：发送失败的冲正与 Stripe 撤回的部分把钱记回 2120（来源 `QbTransfer`，`sourceId` 同原转账）。它们是该转账自己的钱，不是自由的钱：
  在该转账的借方处与之相抵（借方减去退回之和，不低于 0），退回的行本身不再计入，如同转账只发出了没有退回的部分（`RewardHolds.netted`）；否则原借方仍会"取走"争议冻结的奖励，发送失败后被冻结的奖励就会再被转出。
  App 的累计收入不计退回的钱。一笔转账在 2120 上只有一行借方；找不到借方的退回按自由的贷方计。
- 已知的近似：同一时刻、同一流程序号的行之间的先后由交易编号决定；参数 `hold-days` 改小时，按新值重新分类（不追溯当时的值）。

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
- **定时上下架**（逐项任务 `qb.publication-tick`，每分钟，平台决策 D42）：模板 `qb.system.publications-due` 按 `QbPublicationSchedule.nextDueAt` 找出全部到期的发布（停机期间错过的时刻由下一次运行补上），每次至多 2 000 条，每条以 `QB_PUBLICATION_ADVANCE` 在自己的事务中、在发布锁内按
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
| `QB_AI_QUESTIONS_REQUEST` / `QB_AI_ACCEPT` / `QB_AI_DISCARD` / `QB_AI_CANCEL` | `qb.ai.use` | Q8a（`plans/Q8-ai.md` D-Q8-4）：请求先查入驻、封禁、AI 说明（新种类 `AI_NOTICE`，请求时同意）、总开关与参数清单，经商家视图读模版与所选资料、构造并度量输入（≤ 60 KiB，超出即拒绝、不截断），再在 `qb.ai:<商家>` 锁内查限额（同时未结束 2 个、每模版 1 个、每日次数、每月 Token 含未结束作业的预估、全局月上限），之后经异步外部作业 `qb.ai.questions` 调用 Claude；接受取 `qb.quiz:<模版>` → `qb.ai:<商家>`，只写每题 `reviewed = true` 的、商家校对后的内容，模版 `aiGenerated`；丢弃只改作业；取消改作业并在同一事务中取消平台的作业（一个平台步骤 `CancelExternalJob` 声明两种作业类型，平台 16v-1、16w-1），排队中的不再调用提供方 |
| `QB_AI_COVER_REQUEST` / `QB_AI_COVER_ACCEPT` | `qb.ai.use` | Q8b（M-25，`plans/Q8-ai.md` 第 6 节与实施记录）：请求与题目同样的检查、锁与限额（每模版每种类 1 个），只发送标题与简介，作业 `qb.ai.cover` 由 Claude 从固定图库设计 3 个封面、服务器画成 PNG、经平台的文件输出以商家为上传者登记；选定取 `qb.quiz:<模版>` → `qb.ai:<商家>`，经模版编辑把所选图写入 `cover`、`aiGenerated`，作业关闭后未选的图由清扫删除。PDF 资料（Q8b）作为作业的文件输入发送原文件（每个与合计的大小在请求时查；服务器不解析 PDF，作业在付费调用前以提供方免费的 `count_tokens` 数输入，超过按页数上限的预留即失败、不计费），作业输入上限 256 KiB |
| `QB_AI_JOB_COMPLETED` / `QB_AI_JOB_FAILED` / `QB_AI_EXPIRE` / `QB_AI_JOB_CANCELLED` | `qb.ai.system`（不授予） | `internal()`：`qb.ai:<商家>` → `qb.ai:*` 内累加每次尝试的用量（商家与全体），候选按内容规则再查后 READY；失败记原因；每日任务 `qb.ai-expire` 逐项清除过期的候选；取消的作业（商家的 `QB_AI_CANCEL` 或运维的 `EXTERNAL_JOB_CANCEL`）结束后由两种作业类型的 `onCancelled`（`QB_AI_JOB_CANCELLED`，平台 D52 第 2 条）关闭并按实际用量计入，没有尝试运行过的不计费；三者对每次没有记下用量的尝试（平台的 `unrecordedAttempts`，D52 第 5 条）按预留的输入加 `maxTokens` 计入（平台 16w-1） |
| `QB_QUIZ_IMPORT_CHECK` / `QB_QUIZ_IMPORT`（Q16） | `qb.content.write`，要求已验证 | Quiz 文件（`quizbuks.quiz`，`formatVersion`，`QuizFileFormat` 逐版升级到当前版本）：检查版本、内容的限制与每个文件的类型和大小，说明需要上传的文件；导入以调用者按内容策略上传的文件在一个事务中写出新草稿（同复制），有问题即不写。导出在商家后台读草稿与文件完成（D-Q16-1，`plans/Q16-quiz-transfer.md`） |
| `QB_PUBLICATION_SAVE` / `QB_PUBLICATION_DELETE` / `QB_PUBLICATION_COPY` | `qb.publication.write` | 向导第一、二步保存草稿（可不完整），输出提交时会报告的全部问题与预计最大支出（第三步）；删除只限草稿；复制任意状态的本人发布为新草稿，可换版本或时间（Q4） |
| `QB_PUBLICATION_SUBMIT` / `QB_PUBLICATION_WITHDRAW` | `qb.publication.write` | 模版锁（共享）→ 发布锁；`PublicationReadiness` 一次报告全部违规（档位、时间、合格分、预算、以快照重查内容、商家已入驻）；`RequireApproval`；撤回待审核的请求 |
| `QB_PUBLICATION_PAUSE` / `QB_PUBLICATION_RESUME` | `qb.publication.write` | 取发布锁；恢复按时间回到相应状态；管理员暂停的（`pausedBy = ADMIN`）商家不能恢复（422 `QB_PUBLICATION_ADMIN_PAUSED`，Q10b） |
| `QB_PUBLICATION_ADMIN_PAUSE` / `_RESUME` / `_END` | `qb.admin.content.write`，总是要求二次验证（Q10b，评审 M2） | 管理员下架：必填理由（`adminReason`，商家可见），发布锁 → 开始锁；暂停后只有管理员能恢复，结束的 `endReason = ADMIN`。`actsOn(QbPublication)`：后台的行操作与扩展的发布页给出入口。封禁商家（`QB_USER_BAN`）在同一事务中以子流程逐个暂停其进行中的发布（账户锁 → 按编号升序的发布锁对） |
| `QB_PUBLICATION_APPROVAL_RESULT` | 系统（审批事件；`qb.publication.system`，不授予任何角色） | 只认发布自己的请求（`approvalRequestId`）：批准且内容未变 → 上架；驳回（以存储的请求为准）→ 草稿附理由 |
| `QB_VIEW` | `qb.play`，要求已验证 | 记第一次浏览（重复忽略）；发布须对本人可见，否则 404 |
| `QB_ATTEMPT_START` | `qb.play`，要求已验证 | 本人进行中的会话直接继续（不取锁）；否则发布锁内：`LIVE` 且在时间内、地区有交集、不是自己的发布、名额与预算（进行中的会话各预占一个名额与最高奖励）、商家可支付（软检查，余额在取锁前读，Q10a）→ 建会话（`deadline = now + 时限`，选项按会话打乱，**编号为本次显示的位置 1..k**，Q10b）、`starts + 1`、`reserved + maxReward`。返回题目（**不含正确答案**） |
| `QB_ATTEMPT_SAVE` | `qb.play`，要求已验证 | 保存当前题号与已选答案（按位置；不取锁）；超过 `deadline` 拒绝；非法题号或位置一次报告全部 |
| `QB_ATTEMPT_SUBMIT` | `qb.play`，要求已验证 | 晚取锁：发布锁 → 超时（`now > deadline + 宽限 5 秒`）422 `QB_ATTEMPT_EXPIRED`（本次答案不用）；评分 → 档位 → 计数 →（奖励大于 0）钱包锁 → 无待支付且余额够则子流程 `LEDGER_POST`、`PAID`，否则 `PENDING` 并把 `LIVE` 的发布置 `FUNDS_SHORT`；名额或预算用完即 `ENDED`；发布事件 `qb.reward.settled` / `qb.reward.pending`（Q7 的站内信）。幂等键与会话状态防重复提交 |
| `QB_ATTEMPT_EXPIRE` | 系统（`qb.play.system`） | 过了时限与宽限仍 IN_PROGRESS 的会话按已保存答案完成（`EXPIRED`），与提交同一套步骤；已完成或未到期的什么也不做。由逐项任务 `qb.attempt-expire`（每分钟，平台决策 D42）对模板 `qb.system.attempts-due` 选出的会话（`deadline + 宽限 < 计划时刻`，按截止时间，至多 2 000 个）逐个执行，各自一个事务（Q5b） |
| `QB_TOPUP_START` | `qb.topup`，要求已验证 | 未封禁、入驻已通过；金额为受控参数 `qb.topup.min` – `max` 之间的整数 Kudos；未完成的至多 3 笔 → 插入 `OPENING`，登记作业 `qb.stripe.checkout-create`（会话的金额、引用与返回地址只来自服务端记录与配置）。页面轮询到 `OPEN` 后跳转 |
| `QB_TOPUP_OPENED` / `QB_TOPUP_OPEN_FAILED` | 系统（作业完成 / 失败，`qb.wallet.system`，`internal()`） | 商家钱包锁：核对会话（编号、模式、地址前缀、引用、金额）后 `OPEN`，不符或作业失败即 `FAILED`（不符另开待办） |
| `QB_TOPUP_CONFIRM` | 系统（`checkout.session.completed`，`internal()`） | 商家钱包锁 → `TopUpChecks` 逐项核对 → 同一事务中过账、钱包增加、`PAID`，发布 `qb.sponsor.settle-pending`（补发在另一事务中）与 `qb.topup.paid`，登记手续费作业；不符即待办、不入账 |
| `QB_TOPUP_EXPIRE` | 系统（`checkout.session.expired`） | 未付的充值 `EXPIRED`；已付的开待办 |
| `QB_TOPUP_BANK_START` / `_REPORT` / `_CANCEL`（Q13a） | `qb.topup`，要求已验证 | 开始：未封禁、入驻已通过、已同意条款，金额在 `qb.topup.bank.min` – `max`、收款账户 `qb.bank.receiving.*` 都已设、未结的银行转账至多 3 笔 → `AWAITING` 与 7 位识别码（`NumberSequence`）；报告：本人的、等待中的 → `REPORTED`（汇款人名称、汇款日不晚于今天），第一次报告给财务一个待办；取消：等待中的 → `CANCELLED` |
| `QB_TOPUP_BANK_RECORD`（Q13a） | `qb.topup.bank.record`，要求已验证 | 商家钱包锁：银行转账、可登记（等待中、过期或已取消）、不是本人的、日期不晚于今天、流水的这一笔没有登记过、审批规则 `qb.topup-bank.review` 已生效 → `RequireApproval`（准备人 = 登记人，内容为金额、到账日与流水号）→ `REVIEW`，金额改为实收；规则不适用即整体拒绝（`QB_TOPUP_BANK_NO_REVIEW`），绝不由一人入账 |
| `QB_TOPUP_BANK_APPROVAL_RESULT` / `QB_TOPUP_BANK_EXPIRE`（Q13a） | 系统（审批事件 / 每日 19:23 UTC 的逐项任务） | 只认充值自己的请求：批准即过账 1120 / 2110、钱包增加、`PAID`，发布 `qb.sponsor.settle-pending` 与 `qb.topup.paid`；驳回即回到登记前的状态、金额恢复为申请金额；30 天仍在等待的 `EXPIRED`（之后到的钱仍可登记） |
| `QB_TOPUP_CHECK` / `QB_TOPUP_CHECKED` / `QB_TOPUP_CHECK_FAILED` | 系统（逐项任务 `qb.topup-stale` 每小时 / 作业） | 支付页过期一小时仍无通知的充值向 Stripe 核实（6 小时内不重复），按回答与通知同一套步骤入账或过期 |
| `QB_TOPUP_FEE_RECORD` / `QB_TOPUP_FEE_FAILED` | 系统（作业） | Stripe 手续费：核对币种与 `fee + net = 金额` 后过账 5110 / 1110 并记流水号；不符或失败即待办 |
| `QB_TOPUP_REFUNDED` / `QB_TOPUP_DISPUTE_OPENED` / `QB_TOPUP_DISPUTE_CLOSED` | 系统（`charge.refunded`、`charge.dispute.*`） | 按累计退款额只处理一次（`RefundMath`），扣回可用余额以内的部分、余下记差额并开待办（差额不再自动扣回）；争议开立即待办并冻结争议金额（钱包 `held`，不付奖励），败诉同退款（先从冻结扣），胜诉释放并结算待付奖励；付款尚未确认的退款先失败重试 30 分钟 |
| `QB_RECONCILE_RUN`（任务 `qb.reconcile`） | 系统（`qb.wallet.system`） | 每日核对（4.2），同一计划时刻只一次 |
| `QB_PENDING_SETTLE` / `QB_PENDING_PAY` | 系统（`qb.play.system`） | 只取钱包锁：按提交顺序取至多 50 条待支付，逐条（`QB_PENDING_PAY`，按结算时的抽成）补发，遇到付不起的即停止（不跳过）；还能付下一条时发布事件在新事务中续接；清空后发布 `qb.sponsor.funds-restored` |
| `QB_PUBLICATION_FUNDS_RESTORED` / `QB_PUBLICATION_FUNDED` | 系统（`qb.publication.system`） | 订阅 `qb.sponsor.funds-restored`：按编号逐个在发布锁内把该商家 `FUNDS_SHORT` 的发布放回 `LIVE`（已过结束时间即 `ENDED`）；不取钱包锁 |
| `QB_PUBLICATION_STATS_REFRESH`（任务 `qb.publication-stats`） | 系统（`qb.play.system`） | 每 10 分钟：进行中及 31 天内结束的发布的近 1 小时、24 小时、7 天、30 天完成数写入 `qb_publication_stats`（只写变化的行） |
| `QB_WALLET_DEV_CREDIT` | `qb.wallet.dev-credit`（不授予任何角色），只在 `quizbuks.wallet.dev-credit.enabled=true` 时注册 | 开发与测试用的入账：钱包锁 → 借 1110 / 贷 2110 → 发布 `qb.sponsor.settle-pending`。生产不开：与正式模式或正式密钥同时开启即启动失败（`StripeModeCheck`） |
| `QB_PAYOUT_ONBOARD` | `qb.payout.onboard`，要求已验证 | 未封禁、资料国家 = `quizbuks.stripe.connect-country`（缺省 JP）→ 作业 `qb.stripe.connect-onboard`：首次创建 Express 账户（幂等键 `qb-connect-<用户>`），再建开通链接；完成流程 `QB_PAYOUT_ONBOARD_READY`（账户锁）记账户 `PENDING` 与链接，一个 Stripe 账户只给一个用户（否则待办） |
| `QB_PAYOUT_ACCOUNT_UPDATED` / `_DEAUTHORIZED` | 系统（`stripe-connect` 的 `account.updated` / `account.application.deauthorized`） | 账户锁：按 Stripe 账户号找到（尚未记录的重试 30 分钟后待办），核对模式与元数据中的用户，只取更新的报告；`ENABLED` ⇔ 可收款且 `transfers` 能力为 `active`；解除关联为终态 |
| `QB_TAKER_WALLET_SYNC` / `_RESYNC` | 系统（`qb.reward.settled`、`qb.transfer.changed`、`qb.taker-wallet.resync`） | 用户钱包锁：按账本重算 2120 / 2130 并覆盖写入缓存（重复、乱序收敛） |
| `QB_TRANSFER_CREATE`（逐项任务 `qb.transfer-run`，每日 UTC 01:07） | 系统（`qb.payout.system`） | 用户钱包锁：开关开、余额（账本）≥ 门槛、账户 `ENABLED`、未封禁、无未结束转账、未暂停、最近未因账户连续 3 次失败（平台自身的失败不计）→ 全额转账；超过审核额进 `REVIEW`（审批，准备人 = 收款人；没有生效的规则即跳过、不写任何记录，每天一个待办），否则发出：分录、作业 `qb.stripe.transfer`（先按 `transfer_group` 查询再创建，幂等键 `qb-transfer-<转账号>`） |
| `QB_TRANSFER_PREVIEW` / `QB_TRANSFER_NOW`（Q14） | 财务（`qb.admin.finance.read` / `qb.transfer.now`） | 预览：同一组读取与规则，只返回会不会转与跳过码，不写；立即：与 `QB_TRANSFER_CREATE` 同样的步骤，不能转给自己（`QB_TRANSFER_NOW_OWN`），超过审核额时不写、留给每日任务提出审批（`REVIEW_NOT_NOW`） |
| `QB_BANK_ACCOUNT_SAVE` / `QB_PAYOUT_METHOD_SET` / `QB_WITHDRAWAL_REQUEST`（Q13b） | 答题人（`qb.payout.onboard`），要求已验证 | 银行账户（遮蔽账号、一户一人、变更后冷却期并发邮件）；收款方式（无未结束转账时才能改）；提现：用户钱包锁、门槛 ≤ 金额 ≤ 可转余额、固定手续费，`QbTransfer`（`method = BANK`，账户快照）进 `REVIEW`，准备人 = 本人 |
| `QB_WITHDRAWAL_PAID` / `QB_WITHDRAWAL_RETURNED`（Q13b） | 财务（`qb.withdrawal.pay`），要求已验证 | 只对已批准（`SENDING`）的银行提现、不能是本人：已打款借 2130 / 贷 1120（流水日与流水号，每条流水一次）；退回冲正发出的交易（`FAILED`，`BANK_RETURNED`） |
| `QB_TRANSFER_APPROVAL_RESULT` | 系统（审批事件） | 只认转账自己的请求：批准后重查开关、封禁、账户与余额再发出，否则驳回并待办；驳回即 `REJECTED`（未过账，`REVIEW_REJECTED`，意见不写入用户可读的记录） |
| `QB_JOURNAL_PREVIEW` | `qb.journal.export`（财务） | 下一次导出现在会是什么：月份、凭证行（带外部科目代码）、阻止导出的原因（月未结束、月末之后的核对未通过、未映射或代码不合格的科目、借贷不平）；不写入（Q9d） |
| `QB_JOURNAL_EXPORT` | `qb.journal.export`（财务），二次验证（`ADMINISTRATION`），要求已验证 | 命名锁 `qb.journal` → 下一期（上一期之后的月份，第一次为最早过账的月份；输入的月份必须是它）→ 检查（月已结束、最近一次核对在月末之后且 OK、所动科目都已映射、借贷相等，一次报告全部）→ `AssignNumber`（`qb.journal`，按年）→ 两个 CSV（凭证与明细；UTF-8 带 BOM，单元格全部加引号、以 `= + - @` 开头的加 `'`）→ 子流程 `FILE_ARCHIVE` 两次 → 记 `QbJournalExport`。截止时刻 = 导出时刻 − 10 分钟（`JournalPeriods.SETTLE`） |
| `QB_JOURNAL_EXPORT_CONFIRM` | `qb.journal.confirm`（财务），要求已验证 | 记外部系统的凭证号，每次导出一次（`QB_JOURNAL_ALREADY_CONFIRMED`） |
| `QB_TOPUP_RECEIPT_ISSUE` | `qb.topup`（本人），要求已验证 | 锁 `qb.receipt:<充值>` → 经本人数据视图读充值（他人的 404）→ 已有收据即返回它；否则须 `PAID`（`QB_RECEIPT_NOT_PAID`）且开具方名称已设（`QB_RECEIPT_NO_ISSUER`）→ 子流程 `DOCUMENT_ISSUE`（版式 `qb.topup_receipt`，按签发时刻读取，商家所选语言）→ 记 `QbTopUpReceipt` |
| `QB_TRANSFER_HOLD_RELEASE` | `qb.payout.review`（财务），要求已验证 | 用户钱包锁：放行因转账被驳回或撤回而暂停的用户（记下放行人与时间）；没有暂停即 `QB_TRANSFER_NOT_HELD`；不能放行自己（`QB_TRANSFER_RELEASE_OWN`） |
| `QB_TRANSFER_SENT` / `QB_TRANSFER_SEND_FAILED` | 系统（作业） | 结果与记录相符即 `SENT`（2130 → 1110）；Stripe 拒绝创建（确定：400、401、404、余额不足，Stripe 把拒绝保存在幂等键下）即冲正 `FAILED`；其他（不确定：超时、429、5xx——5xx 同样保存在键下，不再重试）`CHECK` 并待办，不冲回；记下发送结束的时间 |
| `QB_TRANSFER_CHECK` / `QB_TRANSFER_CHECKED` / `QB_TRANSFER_CHECK_FAILED`（逐项任务 `qb.transfer-stale`，每小时） | 系统 | `CHECK` 至少 10 分钟、或 `SENDING` 超过 1 小时的转账按 `transfer_group` 查询：有即 `SENT`；`CHECK` 而没有、且查询晚于发送结束至少 10 分钟（作业结果带查询时刻的下界）才冲正，否则留待下次；仍在发送的不动 |
| `QB_TRANSFER_REVERSED` | 系统（`transfer.reversed`） | 只认记录中的 Stripe 转账号；按累计撤回额只记一次（1110 → 2120），全额即 `REVERSED`；待办 |
| `QB_STRIPE_PAYOUT_RECORD` / `_FAILED` | 系统（`payout.paid` / `payout.failed`） | 每笔打款只记一次（1120 / 1110），失败即冲正；先到的失败使迟到的到账什么也不做 |
| `QB_USER_BAN` / `QB_USER_UNBAN` | `qb.admin.users.ban` | 封禁（`QbUserBan`）：三个入口都不能登录（`QbSignInGuard`），下一次刷新令牌即失效；有价值的动作在流程中再查（提交发布已接上；答题、转账随 Q5、Q6）；不能封禁自己与管理员 |
| `QB_BROADCAST_SAVE` / `QB_BROADCAST_DISPATCH`（任务） | `qb.broadcast.write` / 系统 | 定时发送：到时置 SENT；商家通知只能关联自己的进行中发布 |
| `QB_MESSAGE_MARK` | `qb.me` | 已读 / 点击 |
| `QB_PUBLICATION_ADVANCE`（逐项任务 `qb.publication-tick` 的每项） | 系统（`qb.publication.system`，不授予任何角色，`internal()`） | 每分钟：模板 `qb.system.publications-due` 按日程表选出到期的发布（至多 2 000 条），每条一个事务，在发布锁内 SCHEDULED → LIVE、到期 → ENDED、待审核而到期 → DRAFT；一条失败只影响它自己（退避重试），锁被占用则下一分钟再做 |
| `QB_FX_FETCH`（任务） | 系统 | 每日取 ECB 参考汇率（`BlockingStep`），手动覆盖优先 |
| （以后，F-05） | 系统 | 与 Stripe 余额交易报告逐笔核对、银行流水导入 |

| `QB_LEGAL_PUBLISH`（Q7b） | `qb.admin.content.write`，每次二次验证，已验证邮箱 | 把一种法律文本的草稿发布为下一版本（只写一次）：必须带页面读到的草稿版本 `baseRevision`、日英必填、与最新（未撤回）版本内容相同即拒绝、生效时间不早于现在且不晚于 `qb.legal.max-announce-days`（受控参数，缺省 180 天）、已有未生效的预告即拒绝；与撤回共用命名锁 `qb.legal:<种类>` |
| `QB_LEGAL_WITHDRAW`（Q7b 评审 M1） | 同上，必填理由 | 撤回尚未生效的预告版本（已生效即 422 `QB_LEGAL_WITHDRAW_IN_EFFECT`，已撤回即 `QB_LEGAL_WITHDRAWN`）；之后可再发布，版本号接着最大的号 |
| `QB_CONSENT_ACCEPT`（Q7b） | `qb.account.read`，已验证邮箱 | 登录后的重新同意：只接受本入口的种类（App：答题人条款与隐私政策；商家后台：商家条款与隐私政策）与当前版本 |

| `QB_MESSAGE_FROM_EVENT`（Q7c） | `qb.messaging.system`，`internal()` | 订阅 `qb.reward.settled` / `qb.reward.pending` / `qb.transfer.sent` / `qb.transfer.failed` / `qb.payout.restricted`：重读记录、仍成立才写一条三语消息（`sourceKey` 去重），7 天以前的事件跳过（重放不补发） |
| `QB_PAYOUT_REMINDER`（Q7c，Q7-16） | `qb.messaging.system`，`internal()` | 逐项任务 `qb.payout-reminder`（每日）：余额达到转账门槛而未开通收款的用户，每 `qb.payout.reminder-days`（30）天至多一条 |
| `QB_BROADCAST_SAVE` / `_SEND` / `_CANCEL` / `_WITHDRAW`（Q7c） | `qb.broadcast.write`，已验证，查封禁 | 商家通知：账户锁 → 通知锁 `qb.broadcast:<编号>`；保存给出发送会遇到的全部问题；只给本人可接受新答题的发布、纯文本无链接、每个发布至多 `qb.broadcast.sponsor.max-per-publication`（3）条、间隔 `qb.broadcast.sponsor.min-interval-hours`（24）小时；类型是推广（可退订，只给发送前注册的人，Q7-11）；不预审（Q7-10），发送即到达该发布地区内的答题人 |
| `QB_ADMIN_BROADCAST_SAVE` / `_SEND` / `_CANCEL` / `_WITHDRAW`（Q7c） | `qb.admin.content.write`，已验证；发送 ADMINISTRATION 二次验证，撤回每次二次验证并必填理由 | 平台公告（App 或商家后台；全体、地区或至多 1,000 名指定用户；立即或定时；有效期缺省 `qb.broadcast.expires-days` 天）；撤回可用于任何公告与商家通知 |
| `QB_BROADCAST_DISPATCH`（Q7c） | `qb.messaging.system`，`internal()` | 逐项任务 `qb.broadcast-dispatch`（每分钟）：到期的定时发送；商家已封禁或发布已不在进行中即取消 |
| `QB_BROADCAST_BAN_WITHDRAW`（Q7c） | `subProcessOnly()` | `QB_USER_BAN` 在同一事务中撤回该商家已发出与已排期的通知（理由 `QB_ACCOUNT_SUSPENDED`） |
| `QB_MESSAGE_MARK` / `QB_INBOX_READ_ALL` / `QB_INBOX_SETTINGS_SAVE`（Q7c） | `qb.account.read`，已验证 | 已读与点击（只记第一次；只能标本人收件箱中的；读后推进 `readThrough`）、全部已读、推广开关；锁 `qb.inbox:<用户>` |
| `QB_BROADCAST_EXPIRE` / `QB_BROADCAST_STATS`（Q7c 评审） | `qb.messaging.system`，`internal()` | 每分钟：过期的与发布已结束的通知关掉 `live`；每日：商家通知的统计快照（只写变化的） |

**同意（Q7b）**：答题人在 `/welcome`（`QB_PROFILE_SAVE` 第一次完成资料）、商家在入驻申请（`QB_SPONSOR_APPLY`，审批内容含条款版本）同意页面所示的当前版本；
充值、提交发布、开通收款在锁外以一条语句（`qb.system.consent-state`）再查，未同意须同意版本即 422 `QB_CONSENT_REQUIRED`；开始答题不查（Q7-2）。没有已生效的版本时什么也不要求。

所有流程的业务规则违规以 422 与错误码返回（`QB_*`，三语文案）。

## 6. 查询与报表（SQL 模板，`queries/qb/`）

| 模板 | 用途 | 权限 |
|---|---|---|
| `qb.app.feed` | 首页卡片：可见性（LIVE + 时间内 + 地区交集 + 名额与预算未满 + 商家可支付）、关键字、筛选（发布人国家与名称、金额、完成人数、开始日期）、排序（U-10 – U-14）。只读卡片、计数、钱包（与较小的国家、入驻）；"现在"（`at`）与国家（`country`）由调用方传，开始答题在服务端再判断 | `qb.play` |
| `qb.app.publication` / `qb.app.publication-tiers` | 详情（U-20 – U-23）：卡片、简介与资料（版本快照中，不含题目）、发布人（入驻已通过的）、完成人数（近期窗口取统计表，累计取计数行）、本人的会话；档位取卡片 | `qb.play` |
| `qb.app.history` | 本人的答题记录（U-40 – U-42）：已完成（合格 / 不合格）、进行中、已过时限待自动提交；关键字、日期、金额、状态筛选 | `qb.me` |
| `qb.app.wallet` / `qb.app.ledger` | 余额概览（可用 = 本人 2120、累计收入、待支付、转账中 = 2130；带 `at` 时另给保留中的奖励 `held`、其中争议冻结的 `heldOpen` 与预计可转时刻 `heldUntil`，规则同 `qb.wallet.taker-transferable`，Q10b）与流水（奖励：已到账 / 处理中；Q6b 加转账），只读本人的分录（数据视图 `taker:LedgerEntry`，按 `dimension1` = 调用者）与会话（U-50 – U-52，Q6a） | `qb.me` |
| `qb.sponsor.wallet` / `qb.sponsor.topups` | 商家钱包概览（余额、待支付、累计充值与退款、累计支出）与本人充值记录（支付页地址只在 `OPEN` 且未过期时给出）（M-04，Q6a） | `qb.sponsor.finance.read` |
| `qb.sponsor.topup-limit` | 每笔充值的范围与新商家的累计上限、剩余额度、期限（Q10b，评审 M3；判断仍在 `QB_TOPUP_START`） | `qb.sponsor.finance.read` |
| `qb.wallet.taker-transferable` | 转账流程在用户钱包锁内读：2120 余额与保留中的奖励（最近 `qb.transfer.hold-days` 天入账的，与有未结争议的商家在被争议付款之后入账的，按 2120 贷方行的发布维度经卡片找到商家；只计尚在余额中的部分，模型见 4.4；Q10b，评审 M3、M-b） | `qb.payout.system` |
| `qb.admin.topups` / `qb.admin.recon-runs` / `qb.admin.recon-items` | 全部充值（不含支付页地址）；核对结果与差异（F-06，Q6a） | `qb.admin.finance.read` / `qb.reconcile` |
| `qb.app.inbox` / `qb.app.inbox-unread`（Q7c） | 个人消息 ∪ 匹配的公告（`live`、未过期、受众 APP，范围：全体 / 本人国家的地区 / 指定本人；商家通知只在其发布排期中或上架时（暂停与预算不足时隐藏，评审 M1）、给发布地区内的答题人（不给商家本人）；不收页面的时间（评审 L1）；推广只给发送前注册、未退订的人），带已读；未读数至多 99 | `qb.me` |
| `qb.sponsor.inbox`（Q7c） | 商家后台收件箱：受众 SPONSOR 的公告 | `qb.sponsor.me` |
| `qb.sponsor.broadcasts` / `qb.sponsor.broadcast-publications`（Q7c） | 本人的通知与每日快照的统计（到达人数向下取整、不足 10 不显示；阅读不足 5 不显示；到达不足 50 时不显示点击与开始答题，Q7-14、评审 M2）；可选的发布 | `qb.broadcast.write` |
| `qb.admin.broadcasts`（Q7c） | 全部公告与通知，统计为确切的人数（从不给出是谁） | `qb.admin.content.write` |
| `qb.system.broadcast-audience` / `qb.system.broadcasts-due` / `qb.system.payout-reminders` / `qb.system.broadcasts-expired` / `qb.system.broadcast-stats`（Q7c） | 发送时的到达人数（同收件箱的条件）；到期的定时发送；该提醒的用户；该关掉的行；统计变化了的通知 | `qb.messaging.system` |
| `qb.sponsor.transactions` / `qb.sponsor.transaction-summary` | 商家交易明细与汇总（M-50 – M-52，Q9b，`plans/Q9b-sponsor-transactions.md`）：本人发布的奖励（已支付 = 已完成、待支付 = 处理中；完成但无奖励的不是交易）∪ 本人的充值（已支付 = 已完成、开立中 = 处理中、过期或失败 = 失败）。答题人只以资料的显示名出现，不读作答与结果、不给邮箱与用户编号（按用户筛选用 `userKey` = md5(商家编号 : 用户编号)：不是用户编号，同一答题人在每个商家处的键不同；它不是秘密，知道两个编号的人都能算出）；筛选全部是参数（类型、状态、发布、用户、期间、关键字），汇总只多一层聚合、条件相同，列表、汇总与导出因此一致。汇总按类型分开：奖励（支出）的已支付与待支付，充值（收入）的已到账（扣除退款）、退款或扣回、处理中与失败；两者的金额从不相加，只有笔数是总计 | `qb.sponsor.finance.read` |
| `qb.sponsor.publications` | M-34 | `qb.publication.write` |
| `qb.sponsor.dashboard` / `qb.sponsor.analytics.trend` / `.trend-totals` / `.top` / `.overview` / `.questions` | 商家分析（Q9e；`.trend-totals` 是同样参数下趋势的合计（Q9e2 评审 L1，页面不自己加），趋势与概览都带 `zone`（Q9e2 评审 M2）；`plans/Q9e-analytics.md`，口径 D-Q9e-1）：仪表盘一行（各状态的发布数、正在答题、今日支出、可用余额与待支付、有上限的进行中发布的剩余预算；实时取计数，今日 = 部署时区 `jabiz.fiscal-year-zone` 的今天；今日支出 = 任务最近一次运行之后的增量（计数 − 游标）加今天的桶，任务没见过的发布只计今天上架的）；按小时（UTC）或按日（部署时区）的趋势，缺的桶补零、至多 400 个；近 7 天完成数排行；一个发布的概览（累计人数取任务的快照、随任务每 10 分钟变化而不是随每次答题，Q9e1 评审 M1；完成率、漏斗与流失最大的一段、平均用时与超时人数、App 的四个窗口）；每题的正确率与难度（≥ 80 % 易、≥ 50 % 中、其余难）。全部只经限本人的卡片视图 `sponsor:QbPublicationCard`（普通表、按 `sponsor_id` 索引读，不扫时态的发布历史，Q9e1 评审 M2）；可推出个人作答的数字（每题、平均用时、超时）只在至少 k（`qb.stats.min-group`，缺省 5、下限 3，SQL 中 `greatest(3, …)`）个完成时给出、只在新增至少 k 个后变化；人数不受 k 约束（E-4）。导出经平台 `POST /api/queries/{id}/export`（只有汇总） | `qb.stats.read` |
| `qb.system.analytics-items` / `-state` / `-attempts` / `-question-stats` | 分析任务的取数（Q9e）：逐项任务的项（按发布编号的块、结束后的重算、最后一项运行记录）；每块一条语句读两侧计数、游标、新增浏览与要加的小时桶；水位线之后、滞后 5 分钟的已完成会话按题汇总（每块至多 `quizbuks.analytics.max-attempts-per-chunk` 个；结束 30 分钟且无进行中时整体重算）；公开的题目统计 | `qb.stats.system` |
| `qb.admin.attempts` / `qb.admin.wallets` | 全部会话（P-01 的答题历史、支持查询，不含金额）；商家钱包的余额与待支付（Q5） | `qb.admin.users.read` / `qb.admin.finance.read` |
| `qb.admin.attempt-rewards` / `qb.admin.publication-funds` | 会话的奖励、抽成与分录（P-01 的资金部分）；发布的商家资金（预算不足、待支付）。Q9a 从上两个模板中分出，资金只给财务 | `qb.admin.finance.read` |
| `qb.admin.users` / `qb.admin.transfers` | P-01；P-03（Q9c 加筛选 `transferId`、`stale`（`CHECK`，或 `SENDING` 早于参数 `staleBefore`）与 `held`（被驳回或撤回、尚未放行）） | `qb.admin.users.read` / `qb.admin.finance.read` |
| `qb.admin.monthly`（报表）/ `qb.admin.balances`（报表） | F-08（Q9c，`plans/Q9c-admin-finance.md`）：只读账本、按过账时间，参数 `from`、`to`（月初与下月初，UTC）与可选的 `knownAt`（`timeSlice` 只映射它，平台 19 §2.2）。月报按交易类型（来源单据与所动科目：充值到账、退款、争议扣回、Stripe 手续费、奖励、转账发出 / 成功 / 失败冲正 / 被撤回、打款到银行 / 失败、其他）给出笔数、金额与七个科目各自的借减贷，每类都有一行；余额表给出各科目的期初、借、贷、期末，以及四项账本对自身的核对（2110、2120 + 2130 的每一行都记有户、没有任何户的钱包科目余额为借方、借贷相等）。月报的每一类须来源、冲正、所动科目与方向全都符合，否则为"其他"导出只经平台 `POST /api/queries/{id}/export`，签发只经 `REPORT_ISSUE`（只读时态实体：可重算） | `qb.admin.finance.read` |
| `qb.admin.finance-summary` / `qb.admin.recon-summary` | 财务总览的一行（Q9c）：钱包合计、待审批 / 待核实的转账、暂停的用户；另一模板为最近一次核对与未处理的资金待办（指派给 `qb.reconcile` 的平台待办） | `qb.admin.finance.read` / `qb.reconcile` + `task.read` |
| `qb.admin.journal-exports` / `qb.admin.journal-mapping` | 分录导出的列表（期间、凭证号、行数、借贷合计、文件、外部凭证号）；科目映射（每个可过账科目与现行的外部科目代码，参数是否已发布）（Q9d） | `qb.admin.finance.read` |
| `qb.system.journal-first` / `qb.system.journal-summary` / `qb.system.journal-parts` / `qb.system.journal-detail` | 导出的取数（Q9d，D-Q9d-3）：最早的过账时间（第一期）；本次所取的分录（过账于期末之前、记录于截止时刻之前、且不在以前的导出中：`bookingTime < to AND createdTime <= knownAt AND (bookingTime >= previousTo OR createdTime > previousKnownAt)`）——凭证行由数据库按科目、借贷（2110 加商家）汇总；明细按交易区间分段（每段在平台读取与文件上限的一半之内），逐段按交易、科目、借贷读取 | `qb.journal.export` |
| `qb.system.journal-open-transactions` | 导出前检查同一数据库中打开已久的事务（`pg_stat_activity`，评审 M1） | `qb.journal.export` |
| `qb.sponsor.ai-jobs` / `qb.sponsor.ai-usage` | 本人某模版的 AI 作业与待校对的候选（不含回答的模型；Q8b 可按种类 `kind` 读，封面的候选含图的文件编号）；本月用量（输入 / 输出 Token、次数、今日次数）、未结束作业的预估与剩余额度、是否开启（经 `now:SysParam`；月与日按任务记下的部署时区）（Q8a） | `qb.ai.use` |
| `qb.admin.ai-usage` / `qb.admin.ai-jobs` / `qb.admin.ai-settings` | 按商家与月份的用量、失败数与按价格参数的美元估算；最近的作业（请求的模型与实际回答的模型、尝试次数、失败原因，不含候选）；AI 参数的现行值（Q8a） | `qb.admin.ai.read` |
| `qb.system.ai-open` | 所有商家未结束作业的预估合计（请求在 `qb.ai:*` 锁内读，全体月上限计入正在运行的作业）（Q8a 评审） | `qb.ai.system` |
| `qb.system.ai-expired` | 过期任务的取数：READY 且已过期的作业（Q8a） | `qb.ai.system` |
| `qb.sponsor.topup-receipt` / `qb.sponsor.receipt-issuer` | 收据（Q9d，F-09）的两个区块：本人一笔已到账的充值（公司、联系人、到账时间、支付号末尾、金额）；开具方（受控参数 `qb.issuer.*`，总是一行） | `qb.topup` |

- **公开模板**（Q7b，平台 15、D17；`jabiz.public.enabled` 由 `JABIZ_PUBLIC_ENABLED` 打开，缺省开）：`qb.public.legal`（某种文本在时刻 `at` 的当前版本与下一个预告）、
  `qb.public.legal-version`、`qb.public.legal-history`、`qb.public.faq`（`audience`、`category`、`q` 在任一语言中字面匹配）。只读公开视图：法律文本的已发布版本（全部，含预告）与已发布的 FAQ；草稿没有公开视图。
  `qb.public.publication`（Q15，发布的分享链接）：进行中（`LIVE`）的发布的概要与商家公司名；公开视图是 `LIVE` 的卡片（白名单，不含地区、预算、奖励档）与已批准商家的公司名。App 入口开启邮件链接登录（平台 D54），分享页只要邮箱（`plans/Q15-share-link.md`）。
  已认证：`qb.me.consents`（本人对各文本的状态）、`qb.me.support`（支持邮箱）、`qb.admin.consents`（P-01 的同意记录）。
- 完成人数：已认证的模板没有缓存，所以近期窗口由任务 `qb.publication-stats` 每 10 分钟按索引 `QbAttempt (publicationId, submittedAt)` 统计到 `qb_publication_stats`（需求 6.1 允许 10 分钟），累计取计数行；分析（Q9e）由任务 `qb.analytics` 每 10 分钟把计数的增量写进按小时的桶，按同一索引从水位线读新完成的会话汇总每题（答题路径不写任何分析数据，D-Q9e-2）。
  **残余（Q9e1 评审 M1 ④，已知、接受）**：概览的人数是任务的快照，但仪表盘的"正在答题"与今日支出是实时的合计，商家只有一个进行中的发布时，两次查看之差就是该发布在这段时间内的变化；Q4 的 `qb.sponsor.publications` 同样实时给出每个发布的计数。两者都只是人数与金额（E-4 不受 k 约束），推不出逐题作答。
- 导出（M-52）只经平台导出 `POST /api/queries/qb.sponsor.transactions/export?format=…`（与列表同样的参数与排序，超过平台上限即整体拒绝 `REPORT_TOO_LARGE`）；商家后台提供 CSV 与 XLSX。

## 7. 前端

### 7.1 答题人 PWA（`quizbuks-web/app`）

- React 19 + TypeScript + Vite + `@jabiz/ui`（shadcn/ui + Tailwind CSS v4，与平台阶段 15 相同，决策 D34）+ TanStack Query + i18next；
  路由是示范门户那样的几十行小路由（`src/qb/router.tsx`，平台的登录积木不依赖路由）。QuizBuks 没有自己的接口（全部经平台的流程、模板、数据视图与文件接口），
  所以没有 OpenAPI 生成的类型：流程的输入输出对照 Java record 手写在 `src/qb/api.ts`，由 e2e 覆盖。
- PWA：`manifest.webmanifest`、图标、Service Worker 只缓存应用外壳与静态资源（不离线缓存数据，不做推送），由平台预设 `jabizSpa({ pwa })` 生成；放过 `/sponsor/`、`/admin/`。
- 会话保存在设备上（入口 `app`：`persistence: device`、30 天、不按闲置锁定，缺省 Q12）。
- 宽度 ≤ 430px 设计，底部导航（QP1：首页、账户；QP2 加历史，QP4 加钱包，Q7 加收件箱，Q-8）；卡片紧凑，一屏 ≥ 6 张（U-10）。
- 登录、注册、邮件链接（`/auth/…`）、找回密码、Google 登录的回调（`/login/oidc`）、step-up、账户页的改密码、两步验证、会话与外部账号都用平台的积木（G12）；
  注册的第二步是 `/welcome`（资料，国家按浏览器的语言区域与时区预填，Q2-9）。
- 页面分阶段：QP1 账户与欢迎页，QP2 首页、详情、答题、结果、历史，QP4 钱包与开通收款（`plans/QP-pages.md`）。
- 条款与帮助（Q7b）：`/help`、`/legal/terms|privacy`、`/about` 不需登录；欢迎页同意条款与隐私政策；资料完整而须重新同意时，登录后整页显示同意提示（未验证邮箱只显示提示条）。
  法律文本按界面语言显示，日文为准据文本：其他语言注明并可切换日文原文，中文缺失时显示英文并注明。
- 收件箱（Q7c）：底部导航第五项 `/inbox`，徽标为未读数（进入、回到 App、每 5 分钟重读）；分类（全部 / 系统 / 推广 / 奖励 / 提醒）、未读、关键字；
  打开即已读，按钮（去结果、钱包、收款或发布）记点击；全部已读；推广开关。

### 7.2 商家后台（`quizbuks-web/sponsor`）

- 同一工具链与 `@jabiz/ui`；`AppShell` 的左侧导航（QP1：概览、商家资料、账户；QP3 加 Quiz 与发布，QP4 加钱包，Q9b 在钱包之后加交易（`/transactions`，可带 `?publication=`），Q9 的分析在概览中）；
  英 / 中 / 日（设计缺省 Q16 是中 / 英，按 Q-4 照常提供三种，平台不支持按 SPA 的语言）；深色模式（`@jabiz/ui` 的外观切换）；图表用平台选定的图表库（G11）。
- 会话只在标签页中（`persistence: tab`），闲置 30 分钟锁定（缺省 Q12）；不提供 Google 登录（Q2-16）。未入驻通过的商家进入 `/onboarding`。
- 内容工坊是最重的页面：题目与选项的编辑、排序、图片、版本列表；发布向导三步。
- 钱包页（Q9d）：已到账的充值行有"收据"：首次以当前界面语言签发（`QB_TOPUP_RECEIPT_ISSUE`），之后下载同一份存档 PDF。
- 分析（Q9e2，`sponsor/src/analytics/`）：概览（`/dashboard`，入驻通过后）显示指标卡（各状态的发布数、正在答题、今日支出、可用余额与待支付、剩余预算、本月 AI 用量（Q8a））、近 7 天的趋势（完成人数折线与支出柱，上下两个小图，不用双 Y 轴）与近 7 天热门发布前 5；
  发布分析页 `/publications/<id>/analytics`（发布页的"分析"）显示概览、漏斗（流失最大的一段以徽标、斜纹与文字标出）、过去 24 小时（按小时）/ 过去 7 天（按天）的趋势与 App 的四个窗口、每题正确率与难度（不足 k 时注明"至少 k 人作答后显示"）与导出（趋势、概览、题目的 CSV 与 Excel）。
  每个图表有名称与合计的文字、下方可展开的数据表；颜色只用 `--chart-1` / `--chart-2`；时刻与日期按任务记下的部署时区显示（不按浏览器）；每 60 秒与回到前台时重读，并注明"每 10 分钟更新，最近更新 …"。
- 条款与帮助（Q7b）：侧栏"帮助"、页脚的商家条款、隐私政策与特定商取引法表记；入驻页同意所示版本；已申请而须重新同意时整页显示同意提示。
- 消息中心（Q7c，`/messages`）：平台给商家的公告（收件箱），与本人的通知：编写（发布、纯文本、立即或定时）、保存即见问题、发送、改回草稿、撤销，统计（不足 5 人不显示），被管理员撤回的显示理由。
- AI 出题（Q8a，`sponsor/src/ai/`）：编辑器题目页上方的面板。对话框选资料（各自大小，非文章只发标题与描述，合计超过 60 KiB 即不能提交）、题数 1–20、难度、语言、是否避免重复，说明发送什么；第一次使用时显示 AI 说明并勾选同意；
  进行中每 5 秒查询、至多 5 分钟、离开即停；候选是可编辑的卡片（纯文本，不渲染 Markdown），勾选"我已校对"的才能加入；丢弃、取消；失败与过期的说明；AI 关闭时排队的作业注明它在等待、恢复后继续、可以取消（平台 16v-2）。仪表盘的 AI 用量卡显示本月 Token、次数与剩余额度。
- AI 封面与 PDF 资料（Q8b，`sponsor/src/ai/AiCoverPanel.tsx`）：编辑器"模版"页基本信息下的面板：选封面文字的语言（第一次使用时同意 AI 说明）后生成 3 张候选图，以单选选一张"使用此封面"（经模版编辑写入，基本信息随即显示新封面），或全部丢弃；进行中每 5 秒查询、至多 5 分钟。生成题目的对话框中 PDF 资料注明"发送 PDF 文件本身"（不计入文字的大小）。

### 7.3 管理员后台（`/admin/`）

- 平台通用后台：用户与资料、商家、字典、内容页、业务参数、审批与待办、审计、账本、报表都由元数据生成。
- QuizBuks 扩展页（`backend/quizbuks/admin-extension`，`plans/Q9a-admin-extension.md`）：
  - Q9a：审核（`/qb/reviews`：待审的商家入驻与发布；发布页显示全部设置、档位、商家资金与冻结版本的完整内容（含正确答案、资料、图片），入驻页显示审批绑定的内容与邮箱验证状态；
    决定用平台的 `ApprovalPanel`）与用户汇总（`/qb/users/:userId`，资料 + 答题历史 + 同意记录（Q7b），资金流水只给财务，P-01）。
  - Q7b：法律文本的草稿与 FAQ 由通用后台编辑（`QbLegalDoc` 的行操作"发布"即 `QB_LEGAL_PUBLISH`）；后台扩展的"规约类"页（`/qb/legal`）预览草稿、带草稿版本发布，并在版本列表中撤回预告版本（`QB_LEGAL_WITHDRAW`，也是 `QbLegalVersion` 的行操作）。
  - Q7c：公告与通知（`/qb/broadcasts`）：全部公告与商家通知及确切统计；编写平台公告（受众、类型、范围、三语标题与 Markdown 正文及预览、定时、有效期），保存即见问题、确认后发送；撤回任何一条（理由、二次验证）。
    "规约类"页发布时可勾选"在收件箱中公告"（`QB_LEGAL_PUBLISH` 的 `announce`）。
  - Q9c：财务（`/qb/finance/**`）：总览、转账与大额审批、充值、核对、月报。
  - Q9d：分录导出（`/qb/finance/journal`：预览下一期、导出、下载存档的 CSV、记录外部凭证号）与科目映射（`/qb/finance/journal/mapping`：只读，修改链到平台的受控变更）。
  - Q8a：AI（`/qb/ai`，`qb.admin.ai.read`）：开关与上限的现行值（开关经受控变更提出，另一位管理员发布）、按商家与月份的用量、失败率与美元估算、最近的请求与失败原因。
  - Q17：全部 Quiz（`/qb/quizzes`，含已删除）与一个 Quiz 的详情（`/qb/quizzes/:quizId`：发布、版本（按要求显示冻结内容）、当前草稿，只读，`qb.admin.quiz.read`），每个状态的全部发布（`/qb/publications`，`qb.admin.publication.read`；审核页只列待审的），链到发布页与答题记录（`plans/Q17-admin-overview.md`）。
  - Q18a：银行转账充值的设置（`/qb/finance/bank-settings`）：现行值、一次提出七个设置（`QB_BANK_SETTINGS_PROPOSE`）、他人一键发布（`plans/Q18-bank-settings-credit.md`）。

### 7.4 "Kudos"

账本与接口中的币种是 JPY；三个前端都把金额显示为 `1,234 Kudos`（格式化函数一处），参考汇率只用于"约合 X USD"。平台通用后台对管理员仍显示 JPY（管理员需要知道真实币种）。

## 8. 外部系统

| 系统 | 用途 | 接入方式 | 配置 |
|---|---|---|---|
| Stripe | 充值（Checkout）、用户收款账户（Connect Express，Q6b）、转账（Transfers，Q6b） | 出站：外部服务 `stripe`，只经平台的 JDK HTTP（D38：官方 SDK 自带的连接不受主机白名单约束，不用），应用自写 `StripeApi`（表单编码、错误分类、JSON 解析），**全部调用都是异步外部作业**（重试与幂等键由平台持久化），以 `Stripe-Version` 固定 API 版本；入站：平台入站 Webhook（D37）的提供方 `stripe`（账户端点；Q6b 另加 `stripe-connect`），验签、存档、去重后交给 `internal()` 的消费流程 | `STRIPE_API_KEY`、`STRIPE_WEBHOOK_SECRET`（环境变量）；`STRIPE_CONNECT_WEBHOOK_SECRET`（Q6b）；`quizbuks.stripe.*`（模式、API 版本、支付页有效期、结算币种、收款账户国家）、`quizbuks.links.app` |
| Claude（Anthropic） | AI 生成题目（Q8a）与封面（Q8b） | 外部服务 `anthropic`，只经平台的 JDK HTTP（D38，不用 SDK），应用自写 `ClaudeApi`（Messages API：JSON Schema 结构化输出、adaptive thinking、显式 effort、服务端拒答回退 `fallbacks: "default"`、输入 ≤ 250 KiB（Q8a 为 60 KiB）拒绝不截断、PDF 资料以文档块发送原文件、错误分类）；**全部调用都是异步外部作业** `qb.ai.questions` 与 `qb.ai.cover`（3 次尝试；封面图由服务器绘制，经平台的文件输出登记，D50；两种类型都声明 `enabledBy("qb.ai.enabled")`（平台 16v-2，D52 第 4 条）：开关不是 `true` 时平台在每个实例上不再领取，排队与等待重试的作业等待、不用掉尝试，正在运行的尝试照常结束）；只发送商家自己的模版内容，`metadata.user_id` 为 HMAC 的不透明值；`dev`、测试与 e2e 用本地桩（`tools/quizbuks/claude-stub`） | `ANTHROPIC_API_KEY`（环境变量）；业务参数 `qb.ai.*`（开关、模型清单 `claude-opus-5-5` / `claude-sonnet-5-5` / `claude-haiku-5-5`、effort、限额：受控） |
| ECB | 参考汇率 | 定时任务中的 `BlockingStep` | — |
| 邮件 | 激活、欢迎、转账结果 | 平台事务邮件（G3） | `spring.mail.*` |

所有外部密钥只来自环境变量；外部调用都有超时、重试与平台的观测（`PlatformObservations`）。

## 9. 安全与反作弊

- 正确答案只在提交后返回：答题人的数据视图与模板都不含 `correct`；结果页由提交流程的输出给出。
- 选项按会话打乱（`optionOrder`），题目顺序不打乱。打乱的种子是服务端密钥下的 HMAC-SHA256（用户 | 发布 | 题号；密钥 = HMAC(`JABIZ_INTEGRITY_KEY`, `qb.option-shuffle`)，Q10b 评审 M-a），答题人无法自行算出。答题人看到、保存、提交与结果中的选项编号都是本次显示的位置 1..k（Q10b，评审 M1），只在评分时经 `optionOrder` 换回版本的编号；`optionOrder` 是敏感字段，任何读接口（含答题人本人的数据视图）都不返回。
- 一人一次：`QbAttempt (userId, publicationId)` 唯一。
- **管理员下架之后**（Q10b，评审 M-d）："被下架"= 管理员结束的，或管理员暂停（含封禁的暂停）而没有被管理员恢复的，此后因时间或名额结束的也算（`pausedBy = ADMIN` 保留，只有管理员恢复清除它；核查修正）。被下架的发布不能复制（422 `QB_PUBLICATION_TAKEN_DOWN`）；同一 Quiz 以后的任何发布都要人工审核：
  提交时案件的事实 `autoApprove = false`、`takenDown = true`（不论免审核开关），规则仍不要求审核或会沿用以前的批准时拒绝提交（422 `QB_PUBLICATION_REVIEW_REQUIRED`）；
  后台扩展的审核页以模板 `qb.admin.quiz-takedowns` 显示被下架的发布与理由，读好之前不给出决定面板。封禁的暂停记固定的理由 `QB_ACCOUNT_SUSPENDED`（评审 L3，封禁的理由是内部的），封禁期间管理员不能恢复（评审 L9；恢复先取商家的账户锁，与封禁依次进行）。
  **残留的绕过**（已知，不在本期处理）：商家以同样内容另建新 Quiz（新的 `quizId`）发布，按普通规则处理（免审核开关打开时直接上架）；
  下架时同一 Quiz 已在进行中或商家自己暂停的其他发布照常进行（管理员需逐个下架）。可选的加强：管理员下架时一并暂停同一 Quiz 的其他进行中的发布（未实施，见计划 §7）。
- 未激活（邮箱未验证）的用户不能写入任何 QuizBuks 数据：每个写入流程声明 `requiresVerifiedEmail()`（入口检查，403 `EMAIL_NOT_VERIFIED`）；商家入口不接受未验证的登录。
  因此预占他人邮箱注册的人留不下应用数据（平台接管时不清理应用数据）。
- 封禁的不能登录（`QbSignInGuard`，登录与刷新时检查）；已签发的访问令牌在到期前仍可用，所以提交发布（`SponsorChecks`）、开始答题与提交（Q5）、转账（Q6）在流程中再查 `QbUserBan`（422 `QB_USER_BANNED`）。
- 一个 Stripe 收款账户只能属于一个用户（`externalAccountId` 唯一），减少多账号刷奖后套现。
- **Stripe 的通知**（Q6a）：验签、时间容差、去重由平台入口完成；签名不等于内容可信，消费流程逐项核对会话、引用、模式、币种与金额，
  只按服务端记录的金额、对记录的商家入账，锁名也取自记录；不成立的开待办而不入账。客户端唯一的输入是金额（受控上下限），
  返回地址由配置生成（没有开放重定向），浏览器回跳不入账。测试与正式模式、密钥前缀与开发入账由启动检查与消费流程共同隔离。
  密钥只来自环境变量，不进日志与作业的输入；原样事件可能含付款人信息，只有财务管理员能读，保留 7 年。
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
| M-10 – M-11、M-40 – M-43、M-50 – M-52 | 3.3（分析的三个实体，Q9e）、6、G11 |
| M-20 – M-28 | 3.2、5.2、G7 |
| M-30 – M-36 | 3.3、5.1、5.2 |
| M-60 – M-61、P-04 | 3.5 |
| M-70 – M-73 | G5、G3、G10 |
| P-01 – P-09 | 2、7.3、5.2、平台审批与业务参数 |
| 5.3 规则 1 – 14 | 3.2 快照（1）、5.1（2）、6（3）、3.2（4）、5.2（5、6）、4.2（7、8、9）、2（10）、G1（11）、4.1（12）、4.2（13）、5.2 转账（14） |
| F-01 – F-09 | 4、6、平台报表与单据 |
