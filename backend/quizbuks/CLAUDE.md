# CLAUDE.md — QuizBuks 应用规则

应用 QuizBuks（商家出资发布 Quiz、用户答题得 Kudos 奖励）的后端（模块 `backend/quizbuks`）。根目录 `CLAUDE.md` 的全部规则照常适用；本文件只写本应用额外的约定。
需求分析：`docs/quizbuks/01-requirements-analysis.md`；设计：`docs/quizbuks/02-design.md`；计划：`docs/quizbuks/03-plan.md`；各阶段的实施计划：`docs/quizbuks/plans/`。

## 1. 需求副本只读

- `docs/quizbuks-requirements/` 是需求方文档的原样副本，**永远不修改**。需求有问题时记在 `docs/quizbuks/`（分析或设计的待确认问题），由需求方改版后整体重新复制。

## 2. 边界

- 分支按平台版本线（平台决策 D21、`docs/guide/version-lines.md`）：`<线>/quizbuks`，工作分支 `<线>/quizbuks-<N>-<名>`。现在是线 1.2（`1.2/quizbuks`）。
- 本分支只改 `.jabiz-app-paths` 中的路径（`tools/check-app-paths.sh`，基准为本线的平台分支），不改 `.jabiz-platform-line`。平台缺能力时，
  先在 `1.2/platform` 的工作分支上补（带平台自己的测试与 `app` 中的示范，不提 QuizBuks），合入后再合并到 `1.2/quizbuks`。计划中的平台阶段见 `docs/quizbuks/03-plan.md` 第 1 节。

## 3. 命名

| 对象 | 约定 | 例 |
|---|---|---|
| 包 | `com.jabiz.quizbuks.<模块>`（`identity`、`content`、`publishing`、`play`、`wallet`、`messaging`、`stats`；公用的在 `setup`、`country`） | `com.jabiz.quizbuks.wallet` |
| 启动类 | `QuizbuksApp` | |
| 表 | 前缀 `qb_`，时态表 `qb_<名>_version` | `qb_country_version` |
| 实体 | 前缀 `Qb` | `QbCountry` |
| 权限 | `qb.<模块>.<动作>`，常量在 `QbPermissions`；只经 `setup.QbRoles` 授予（`QB_SETUP`） | `qb.content.write` |
| 流程 | `QB_*` | `QB_SETUP` |
| 错误 / 规则代码 | `QB_*`，三语文案 | `QB_COUNTRY_CODE_FORMAT` |
| 业务参数 | `qb.<模块>.<名>`，缺省值在 `setup.QbParams`，由 `QB_SETUP` 建立 | `qb.transfer.threshold` |
| 迁移 | `db/migration/V<n>__quizbuks_<名>.sql`，自 V1 起（应用迁移有自己的历史表，与平台的 `db/jabiz` 互不冲突） | `V1__quizbuks_countries.sql` |
| SQL 模板 | `queries/qb/**`，id 前缀 `qb.` | `qb.app.feed` |
| 字典 | `urn:jabiz:dict:quizbuks:<名>` | `urn:jabiz:dict:quizbuks:region` |

## 4. 应用约定

- **金额**：账本与接口一律 JPY、小数位 0（`jabiz.ledger.currency: JPY`、`scale: 0`；字段 `asMonetary("JPY", 0)`）。三个应用前端把金额显示为 "Kudos"（`1,234 Kudos`，一处格式化函数）；
  平台通用后台对管理员仍显示 JPY。不写"Kudos"以外的对外币种名。
- **会计年度**为日历年（`jabiz.fiscal-year-end: 12`）。
- **语言**：消息资源 `messages_{en,zh,ja}.properties` 三语齐全（`jabizApp { languages("en", "zh", "ja") }`，启动检查）；实体与字段 `entity.<实体>[.<字段>]`、流程 `process.<名>`、参数 `param.<键>`。
  产品显示名只写在 `app.name`。
- **初始数据只经 `QB_SETUP`**：角色、账本科目、国家、业务参数由它建立；它可重复执行，只补**从未存在过**的（`QbSetupRecord` 记下它建过或见过的每一项），不改已有的，也不加回管理员删除的角色、收回的权限。新阶段的新权限、科目、参数加到
  `QbRoles` / `QbLedger` / `QbParams`，部署后再执行一次 `QB_SETUP`。从代码中去掉的授权不会被收回：由管理员在已有安装中收回一次。
- **受控参数**（平台决策 D40）：金额与免审核开关列在 `QbParams.CONTROLLED`（`ControlledParams` Bean），只经受控变更修改；`QB_SETUP` 提出它们的首个值，由另一位管理员发布。
  不把 `platform.param.write` 授予任何角色。
- **账本**只经流程的子流程 `LEDGER_POST` / `LEDGER_REVERSE` 写入；`ledger.post`、`ledger.reverse`、`ledger.account.write` 不授予任何角色。
- **国家数据**在 `quizbuks/countries.txt`（`code|en|zh|ja|regions`，无引号）；文件问题由启动检查（类别 `QUIZBUKS`）一次报告。
- **地区**：国家的 `regions` 是按 `country.Regions.ALL` 顺序、以逗号连接的地区代码，总含 `GLOBAL`（平台没有多值字段）；规则 `QB_COUNTRY_REGIONS_FORMAT` 只接受这一种写法。
- **内容（模版、题目、资料、版本）**（`content`，`docs/quizbuks/plans/Q3-content.md`）：写入只经 `QB_QUIZ_*` / `QB_QUESTION_*` / `QB_MATERIAL_*` 流程
  （默认数据视图 `processOnlyWrites`，商家视图只读）。写一个模版的流程先取锁 `HoldLock.exclusive("qb.quiz:" + quizId)` 再读取，经商家视图 `LoadEntity`（不是自己的或已移除即 404），
  子项经默认视图整体读取；每次内容改动给模版 `revision + 1`。上限与长度只写在 `content.ContentLimits`；错误码在 `content.ContentCodes`。
  版本由"保存版本"显式生成，快照是 `QuizSnapshots` 的规范 JSON（哈希用平台 `ContentHash`），快照引用的文件另登记 `QbVersionFile`。业务流程不调用 `FILE_DELETE`：不再引用的文件由平台清扫。
  新放入内容的文件必须由模版所有者上传（`QuizEditing.requireOwnFile`，经平台文件数据视图读 `uploadedBy`）。子项列表（选项、图片）给出即完整列表，不给（null）即不动。
  版本与版本文件没有商家数据视图（版本含正确答案）：商家只经连接本人未移除模版的模板读取。商家视图的写权限 `qb.content.view.write` 不授予任何角色。
- **发布**（`publishing`，`docs/quizbuks/plans/Q4-publishing.md`）：写入只经 `QB_PUBLICATION_*` 流程（默认数据视图 `processOnlyWrites`，商家视图只读，
  写权限同样是不授予任何角色的 `qb.content.view.write`）。**锁的顺序固定**：`qb.quiz:<quizId>`（只有提交时取，共享）→ `qb.pub:<publicationId>`（独占）→ Q5 的 `qb.wallet:<种类>:<所有者>`；
  写一个发布的流程先取发布锁再读取。**提交即冻结**：只有 `DRAFT` 可改（`QB_PUBLICATION_NOT_DRAFT`），要改就复制。
  **"接受新的答题"只由 `PublicationRules.acceptsNewAttempts` 判断**（`LIVE` 且 `startAt ≤ now < endAt`），模板写同样的条件；状态只表达人与钱的开关，时间由时间字段表达。
  到期上下架只由逐项任务 `qb.publication-tick` 做（模板 `qb.system.publications-due` 按普通表 `qb_publication_schedule` 的 `next_due_at` 选出全部到期的，可补跑；每项 `QB_PUBLICATION_ADVANCE` 一个事务（平台决策 D42）；各流程在发布锁内随状态维护该行）。系统流程（定时任务、审批结果）声明 `internal()`（平台决策 D41：只允许系统身份运行，其他人一律 403 `PROCESS_INTERNAL`），权限 `qb.publication.system` 照常声明、不授予任何角色；审批结果只认发布自己的请求。时态表上只按不可变字段查找（可变字段的条件用不到索引）。档位、提交检查、审批内容是纯 Java 的 `TierCoverage`、`PublicationReadiness`、`PublicationApprovals`；
  上限在 `PublishingLimits`，错误码在 `PublishingCodes`。**审批规则只经 `QB_SETUP` 以受控变更提出**（`setup.QbApprovalRules`，与 `QbParams` 并列），由另一位管理员发布；
  "无规则即放行"由应用挡住（`QB_REVIEW_NOT_CONFIGURED`）。移除模版时，已提交过的发布所用版本的文件保留（`QuizProcesses.filesStillNeeded`）。
  上架时随计数行插入**卡片** `QbPublicationCard`（普通表：除状态外全部在提交时冻结，含档位的 JSON `CardTiers`），`PublicationEditing.write` 在发布锁内随状态同步它；
  答题人的首页、详情、档位与答题流程只读卡片，不读时态的发布与档位。答题只经 `publishing.PublicationMoves` 改变发布（结束、预算不足）。
- **答题**（`play`，`docs/quizbuks/plans/Q5-answering.md`）：会话 `QbAttempt`、首次浏览、完成人数都是普通表，只经 `QB_VIEW` / `QB_ATTEMPT_*` /
  `QB_PENDING_*` / `QB_PUBLICATION_STATS_REFRESH` 写入（默认数据视图 `processOnlyWrites`，答题人视图只读、限本人）。**一人一次**由 `(user_id, publication_id)` 唯一约束兜底，
  开始在发布锁内再读本人会话。**锁的顺序**：`qb.pub:<发布>` → `qb.wallet:<种类>:<所有者>`（如 `qb.wallet:SPONSOR:<用户>`）；持钱包锁的流程（补发、开发入账）从不再取发布锁，
  恢复预算不足的发布（`QB_PUBLICATION_FUNDS_RESTORED`）只取发布锁、不取钱包锁。**晚取锁**：版本、参数在锁外读，锁内只重读会话、计数、发布与钱包再写入；继续答题与保存进度不取锁。
  **超时自动提交**只由逐项任务 `qb.attempt-expire`（每分钟，模板 `qb.system.attempts-due`）对每个会话运行 `QB_ATTEMPT_EXPIRE`。**逐项取锁或可能逐项失败的批量系统工作写成逐项任务**
  （`JobDefinition.forEach`，平台决策 D42），不写成根流程 + `CallProcess.forEach`（一批一个事务会违反锁序、阻塞热门发布、一项失败拖住整批；不取锁的统计任务除外）；每项流程是 `internal()`，先取锁、再读取、已不到期即什么也不做。
  **正确答案只在完成后出现**：开始与继续的输出不含选项的正误，详情只取快照的简介与资料，`result` 列在完成时才写；答题人读不到版本（`AnswerSecrecyIT`）。
  **钱包余额只在钱包锁内改**（`wallet.QbWallets.change`），与同一事务中的 `LEDGER_POST` 一起；判断可支付一律经 `QbWallets.pays`（无待支付且可用余额 `QbWallets.available` = 余额 − 争议冻结 `held` 足够；SQL 模板同样用 `balance - held`，核对仍比较 `balance`）：待支付不入账、按提交顺序补发、不插队；
  `qb_wallet.balance >= 0` 由检查约束兜底。评分、档位、抽成、打乱、时限与开始条件是纯 Java 的 `Scoring`、`TierPick`、`RewardSplit`、`OptionShuffle`、`AttemptRules`；
  上限在 `PlayLimits`，错误码在 `PlayCodes`。系统流程的权限 `qb.play.system` 不授予任何角色。**开发入账** `QB_WALLET_DEV_CREDIT` 只在
  `quizbuks.wallet.dev-credit.enabled=true`（`dev` 与测试）时注册，权限 `qb.wallet.dev-credit` 不授予任何角色，生产不开（启动检查 `StripeModeCheck` 拒绝它与正式模式或 Stripe 正式密钥同时出现）。它是开放流程，补发经事件 `qb.sponsor.settle-pending` 由系统运行（`QB_PENDING_SETTLE` 等答题的系统流程都是 `internal()`，D41）。
- **账户**（`identity`，`docs/quizbuks/plans/Q2-accounts.md`）：三个登录入口 `admin` / `app` / `sponsor` 写在 `application.yml`（平台 10 §15）；`app` 允许未验证的用户登录浏览，`sponsor` 要求已验证。
  **QuizBuks 的一切写入流程声明 `requiresVerifiedEmail()`**（未验证即"待激活"；预占他人邮箱的人因此留不下应用数据），以后的阶段照此。
  资料、入驻、封禁各是一人一行的时态实体（主键是生成的 UUID，`userId` 不可变、唯一；资料与入驻的 `userId` 是操作人编号的文本，封禁的是平台用户的引用），只经 `QB_PROFILE_*` / `QB_SPONSOR_*` / `QB_USER_*BAN` 写入；
  写一个用户账户的流程先取 `HoldLock.exclusive("qb.account:" + userId)` 再读取（锁的顺序：账户锁在模版锁、发布锁、钱包锁之前；目前不同时持有）。本人流程的用户取自操作人，不取自输入。
  **封禁在每个写入中再查**（已签发的访问令牌到期前仍可用）：一切由人发起的 QuizBuks 写入流程在最前面加 `.steps(CallerChecks::refuseBanned)`（读 `QbUserBan`，422 `QB_USER_BANNED`，立即拒绝）；
  提交发布另经 `publishing.SponsorChecks`（同时要求入驻已通过，一次报告全部问题）。Q5 的答题、Q6 的转账照此。封禁与解封的管理员同样要求已验证的邮箱；"管理员"即后台入口接受的角色（`jabiz.security.entries.admin.accepted-roles`），不能被封禁。
  入驻的审批对象 `qb.sponsor`、规则 `qb.sponsor.review` 同发布：**审批规则只经 `QB_SETUP` 以受控变更提出**，"无规则即放行"由应用挡住（`setup.ReviewCodes`）。审批绑定的内容只在入驻行上（名字 `contactName`、公司、简介、头像、同意条款的时间），
  资料的名字另由本人修改、后写者为准，不影响审批；批准须为入驻自己的、绑定当前内容的请求，驳回只须是入驻自己的、已驳回的请求，理由取自审批人的决定。
  **商家头像被替换或去掉时由流程释放旧文件**（先清引用并 `SaveChanges.now`，再子流程 `FILE_DELETE`），这是"业务流程不调用 `FILE_DELETE`"的唯一例外：头像只有本人的一行引用，等不到清扫。
  **只能由子流程运行的流程**（如 `QB_QUIZ_RELEASE_FILES`）声明 `subProcessOnly()`（D41），权限不授予任何角色（`qb.content.system`），流程内照样再查自己的前提。
  **谁能启动流程**（D41）：QuizBuks 的事件消费者、定时任务、回调接续点与审批结果（发布与入驻）一律 `internal()`，只作子流程的用 `subProcessOnly()`，由人发起的照常开放；
  事件与定时任务只运行 `internal()` 流程（启动检查），不用 `.manualToo()`，除非该开放流程确实也由人手工运行。测试中以系统身份运行内部流程用 `ContentItSupport.runAsSystem`，场景中用 `actor: {id: system}`。
  事务邮件模板在 `identity.AccountMails`（文案 `mail.<名>.subject|body`）；链接到某个前端用平台的 `{entryUrl.<入口>}`（商家邮件为 `[…]({entryUrl.sponsor})`，D43 第 4 条），不用参数传地址。`quizbuks.links.sponsor` / `quizbuks.links.app` 只给 Stripe 的返回地址。
- **支付**（`wallet`，`docs/quizbuks/plans/Q6-payments.md`）：
  - **全部 Stripe 调用都是异步外部作业**（`wallet.TopUpJobs`，处理器只经外部服务 `stripe` 与 `StripeApi`：自写表单编码与解析，不用 SDK；以 `Stripe-Version` 固定版本），
    不在事务中等外部调用，不放在提交后步骤（其重试不持久化）。作业的完成与失败流程、入站事件的消费流程一律 `internal()`，权限 `qb.wallet.system` 不授予任何角色。
  - **只信服务端记录**：Checkout 的金额、币种、引用与返回地址只由 `CheckoutRequest` 按记录与配置生成；入账的金额、对象与锁名只取自记录，不取自 Stripe 的元数据；
    签名的事件同样逐项核对（`TopUpChecks`、`RefundMath`），不成立的开待办（`TopUpTasks`，给 `qb.reconcile`）、不入账、不让流程失败。浏览器跳回不入账。
  - **只入账一次的四层**：平台按事件号去重；每个（消费者，事件）只处理一次；同一会话的各种报告（通知、核实）走同一套步骤 `TopUpCrediting`，在商家钱包锁内重读，
    只有 `OPENING` / `OPEN` 能变为 `PAID`；数据库兜底（会话、付款、交易号唯一，`PAID` 必有分录）。新的入账来源照此接入，不另写入账步骤。
  - **锁序**：`qb.account:<用户>` → `qb.quiz:<模版>`（共享）→ `qb.pub:<发布>` → `qb.wallet:SPONSOR:<商家>`；`qb.wallet:TAKER:<用户>` 独立（Q6b：只由同步与转账持有，
    持有它的流程不取其他锁；重算多人时按用户编号排序依次取）。
    充值、到账、过期、核实、手续费、退款与争议只取商家钱包锁（先在锁外按编号或付款找到充值，锁内重读）；每日核对不取业务锁。
  - **模式**：`quizbuks.stripe.livemode`（缺省 false）、密钥前缀、开发入账由 `StripeModeCheck` 一起检查；事件模式不同即待办。
    支付页与开通页的地址前缀（`checkout-url-prefix` / `connect-url-prefix`）在正式模式只能是 Stripe 自己的地址；测试模式只经 `wallet.StripeUrls.prefixProblem`：
    以 `/` 结尾的 `https://主机[:端口]/…/`，`http` 只给本机桩（`localhost`、`127.0.0.1`、`[::1]`），不含用户信息、查询与片段（否则 `https://checkout.stripe.com`
    会放过 `https://checkout.stripe.com.evil.example/`）。Stripe 给出的地址不得含空白或控制字符（`StripeUrls.plain`，充值与开通都查）。
    **开发入账** `QB_WALLET_DEV_CREDIT` 只留在 `dev` 与测试中，与正式模式或正式密钥同时开启即启动失败。
  - 入站提供方 `stripe` 的密钥只来自 `STRIPE_WEBHOOK_SECRET`（`platformCheck` 用一次性的值，见 `build.gradle.kts`）；白名单只列各阶段订阅的类型。
  - 核对模板（`qb.system.recon-*`）每项比较是一条语句、只返回差异行；结果 `QbReconRun` / `QbReconItem` 只写一次。
  - **收款与转账（Q6b）**：收款账户 `QbPayoutAccount` 是普通表，`userId` 与 Stripe 账户号都唯一（一个 Stripe 账户永远只属于一个用户），只由开通作业的结果与
    `stripe-connect` 提供方（密钥只来自 `STRIPE_CONNECT_WEBHOOK_SECRET`）的通知写入，在 `qb.account:<用户>` 锁内、只取更新的报告。
    用户钱包缓存（`QbWallet` 的 `TAKER` 行）只经 `TakerWallets`（`QB_TAKER_WALLET_SYNC` 与转账流程）**按账本重算后覆盖**，不做加减；结算奖励不取用户钱包锁。
    转账的一切流程只持 `qb.wallet:TAKER:<用户>`，锁内从账本读余额；金额只取自账本与记录。每人至多一笔未结束的转账（部分唯一索引）；
    发出作业先按 `transfer_group` 查询再以转账自己的幂等键创建；**只有 Stripe 拒绝创建（`TransferRules.certainFailure`）才冲正**，其他结果进入 `CHECK`、
    向 Stripe 核实后定论：查询"没有"只有在晚于发送结束（`sendEndedAt`）至少 10 分钟时才是定论。Stripe 把执行过的请求的回答（含错误）保存在幂等键下，
    同一键重试余额不足或 5xx 永远得到同样的回答：这两者立即结束作业（`StripeTransferHandler.kept`），次日的新转账用新键。
    被驳回或被撤回（含部分）的转账使用户暂停，直到财务以 `QB_TRANSFER_HOLD_RELEASE` 放行；审核人的意见不写进用户可读的字段。
    开通链接只由本人的数据视图读取（默认数据视图的读权限是不授予任何角色的 `qb.payout.system`）。大额审批的准备人是收款人（平台挡住自审）。撤回按累计金额只记一次。新增 Stripe 交互照 `PayoutJobs` / `StripeTransferHandler` 的写法。
- **外部密钥**（`STRIPE_*`、`OPENAI_API_KEY`）只来自环境变量（`deploy/quizbuks/README.md`）；测试与 `dev` 用固定的假值，仓库中没有真实密钥。

## 5. 前端（`quizbuks-web/`，`docs/quizbuks/plans/QP-pages.md`）

- **两个 SPA，各是独立的 pnpm 工程**（自己的 `package.json` 与 `pnpm-lock.yaml`，经 `link:../../frontend/packages/{client,ui}` 用平台的包；不用 pnpm 工作区）：
  `app`（答题 App，`/`，入口 `app`，PWA）与 `sponsor`（商家后台，`/sponsor/`，入口 `sponsor`）；`build.gradle.kts` 的 `spa(...)` 构建它们，`application.yml` 不写 `jabiz.web.spa`。
  共享依赖的版本必须与平台前端相同（`pnpm app:check`，Gradle 中 `spaCheckRoot` / `spaCheckSponsor`，在 `check` 中）。指南：平台 `docs/guide/app-spa.md`。
- **共用代码只在 `src/qb/`**，两个 SPA 中各一份、**完全相同**（CI `diff -r`）：改一处即复制到另一处。流程、模板、数据视图、字典与文件策略的名字只写在 `qb/ids.ts`；
  流程的输入输出对照 Java record 手写在 `qb/api.ts`（答题的在 `qb/play.ts`；QuizBuks 没有自己的接口，因此没有 OpenAPI 类型），改了 record 就改这里并由 e2e 覆盖。
  **只有一个 SPA 用到的功能**（商家后台的内容工坊）在该 SPA 自己的功能目录中照此各写一份：名字在 `<功能>/ids.ts`、手写类型与调用在 `<功能>/api.ts`（如 `sponsor/src/content/`），不进 `qb/`。
- **内容工坊**（`sponsor/src/content/`，QP3a）：每次编辑带上页面所见的 `revision` 作 `baseRevision`；`QB_QUIZ_CHANGED` / `QB_ORDER_MISMATCH` 或读到别人的新 revision 即提示重新加载，
  重新加载之前不再提交。上传只经 `uploadFile(内容策略)`（`qb.content.image|pdf|audio`），页面的类型与大小检查只为省一次往返；图片按文件自己的变体显示（小图没有变体，`ContentImage`）。
  Markdown 只经 `MarkdownView`，外链只放 http(s) 并在新标签页打开。e2e 的数据准备（文件、完整的 Quiz 与版本）在 `e2e/tests/content.ts`，发布的测试从它开始。
- **发布页**（`sponsor/src/publishing/`，QP3b）：向导每一步都以 `QB_PUBLICATION_SAVE` 保存**整个**草稿（档位给出即完整列表，带上次保存得到的 `tierId`；带 `baseRevision`），
  提交前的问题与预计最大支出**只取保存的输出**（`problems`、`maxSpend`），页面不重算规则；`QB_PUBLICATION_CHANGED` / `QB_PUBLICATION_NOT_DRAFT` 即提示重新加载。
  非草稿只显示、不编辑（提交即冻结），操作按状态给出且都先确认。e2e 的数据准备（已通过的商家、审核的决定、上架的发布）在 `e2e/tests/publishing.ts`。
- **答题页**（App，QP2）：倒计时只按服务器时间（`deadline` 与 `serverTime` 的偏移，`play/countdown.ts`）；保存进度只经 `play/saver.ts`（防抖、一次一个、失败保留重发）；
  开始的输出（不含正误）存在 `sessionStorage`，只供刚完成的结果页显示题目文字（计划 A-3），之后只显示分数与逐题对错；退出登录时清除。
- **支付页**（QP4，`sponsor/src/wallet/`、`app/src/wallet/`）：页面只读记录、从不入账；Stripe 的跳回（`?checkout=`、`?onboarding=`）只决定显示，结果一律等服务端的记录。
  轮询只经 `wallet/poll.ts`（有界、退避、离开即停）；跳往 Checkout 或开通页只经 `wallet/stripePage.ts` 检查过的地址（https，或页面在本机时的本机桩）；
  刚发起的充值只自动跳转一次，其他时候给按钮；开通只跟随本次请求生成的新链接（Stripe 的链接只能用一次）。
- **认证与账户只用平台的积木**（`@jabiz/ui` 的 `SignInForm`、`SignUpForm`、`MailLinkPage`、`OidcCallback`、`StepUpProvider`、`ChangePasswordForm`、`MfaSettings`、`SessionList`、
  `LinkedIdentities`、横幅），QuizBuks 不写认证代码。邮件链接页在各自的 `auth/verify-email|join|password-reset`。路由是 `qb/router.tsx` 的小路由。
- **金额只经 `qb/kudos.ts` 的 `formatKudos`**（`1,234 Kudos`），不写 JPY 或其他币种名。服务端的 `QB_*` 文案已按请求的语言翻译，页面直接显示（`qb/problems.ts`）。
- **文案**：SPA 自己的在 `src/messages.ts`（命名空间 `app` / `sponsor`），共用的在 `qb/messages.ts`（`qb`），三语齐全，测试检查键一致且非空。
- **界面**只用 `@jabiz/ui` 的组件与主题 token（不写颜色值）；亮、暗两种外观都过 axe（e2e `a11y.spec.ts`）。App 按 ≤ 430 px 设计，也检查 1280 px。
- **检查**（每个 SPA 目录下）：`pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test`；平台前端目录下 `pnpm app:check ../quizbuks-web/app`（`sponsor` 同）。
- **端到端**（`quizbuks-web/e2e`，Playwright）：对打包的 jar（`./gradlew :quizbuks:bootJar`），邮件由 Mailpit 接收（`E2E_MAILPIT_URL`），Stripe 由本地桩回答；
  服务端以 `JABIZ_SECURITY_MFA_ADMINISTRATION=false` 与含 `QB_E2E_PUBLISHER` 的 `QB_ADMIN_ROLES` 启动（测试自己建一个只有 `control.publish` 的角色，发布 `QB_SETUP` 的提案）。
  启动方式与环境变量照 `.github/workflows/quizbuks.yml` 的 `e2e` 作业；然后 `E2E_BASE_URL=… E2E_ADMIN_USER=… E2E_ADMIN_PASSWORD=… pnpm e2e`。测试只增数据，可重复运行。

## 6. 测试与命令（在 `backend/` 下）

- `./gradlew :quizbuks:check`（测试 + `platformCheck`）；单个类：`./gradlew :quizbuks:test --tests '*SetupIT'`。
- 集成测试连接真实 PostgreSQL（同平台：Testcontainers 或 `JABIZ_TEST_DB_*`），测试类放在 `com.jabiz.quizbuks.*` 下。
- 场景回放：`src/test/resources/scenarios/qb/**.yml`，`./gradlew :quizbuks:test --tests '*ScenarioTest'`；确认变化正确后加 `-Dscenario.update-snapshots=true`。

## 7. 运行

- 本地：`docker compose -f deploy/quizbuks/docker-compose.yml up -d --build`（数据库端口 5440、应用 8080：App 在 `/`、商家后台在 `/sponsor/`、后台在 `/admin/`）；
  或只起数据库（`… up -d db`）后在 `backend/` 下 `./gradlew :quizbuks:bootRun --args='--spring.profiles.active=dev'`，前端开发时在 `quizbuks-web/app` 或 `sponsor` 下 `pnpm dev`
  （端口 5175 / 5176，`/api` 代理到 8080）。
  compose 没有 `.env` 时 Stripe 未配置：镜像的入口脚本 `deploy/quizbuks/entrypoint.sh`（再交给平台的 `docker/app-entrypoint.sh`，不改平台脚本）只在测试模式、
  `QB_STRIPE_PLACEHOLDERS=true`（compose 缺省）时为未设置的 Stripe 密钥填占位并告警；改它时同步改 `tools/quizbuks/test/entrypoint.test.sh`（CI `deploy`）。
- 首次启动后，管理员登录 `/admin/` 执行一次 `QB_SETUP`（流程页面）。
- 本机试用：`tools/quizbuks/local-demo.sh start --seed`（构建、启动、首次设置与演示数据，只用于本机；`deploy/quizbuks/README.md` "本机演示"）。改了设置流程、入口或演示所用的流程输入时同步改它（CI `local-demo`）。
