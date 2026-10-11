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
  到期上下架只由定时任务 `qb.publication-tick` 做（按普通表 `qb_publication_schedule` 的 `next_due_at` 查出全部到期的，可补跑；各流程在发布锁内随状态维护该行）。系统流程（定时任务、审批结果）声明 `internal()`（平台决策 D41：只允许系统身份运行，其他人一律 403 `PROCESS_INTERNAL`），权限 `qb.publication.system` 照常声明、不授予任何角色；审批结果只认发布自己的请求。时态表上只按不可变字段查找（可变字段的条件用不到索引）。档位、提交检查、审批内容是纯 Java 的 `TierCoverage`、`PublicationReadiness`、`PublicationApprovals`；
  上限在 `PublishingLimits`，错误码在 `PublishingCodes`。**审批规则只经 `QB_SETUP` 以受控变更提出**（`setup.QbApprovalRules`，与 `QbParams` 并列），由另一位管理员发布；
  "无规则即放行"由应用挡住（`QB_REVIEW_NOT_CONFIGURED`）。移除模版时，已提交过的发布所用版本的文件保留（`QuizProcesses.filesStillNeeded`）。
  上架时随计数行插入**卡片** `QbPublicationCard`（普通表：除状态外全部在提交时冻结，含档位的 JSON `CardTiers`），`PublicationEditing.write` 在发布锁内随状态同步它；
  答题人的首页、详情、档位与答题流程只读卡片，不读时态的发布与档位。答题只经 `publishing.PublicationMoves` 改变发布（结束、预算不足）。
- **答题**（`play`，`docs/quizbuks/plans/Q5-answering.md`）：会话 `QbAttempt`、首次浏览、完成人数都是普通表，只经 `QB_VIEW` / `QB_ATTEMPT_*` /
  `QB_PENDING_*` / `QB_PUBLICATION_STATS_REFRESH` 写入（默认数据视图 `processOnlyWrites`，答题人视图只读、限本人）。**一人一次**由 `(user_id, publication_id)` 唯一约束兜底，
  开始在发布锁内再读本人会话。**锁的顺序**：`qb.pub:<发布>` → `qb.wallet:<种类>:<所有者>`（如 `qb.wallet:SPONSOR:<用户>`）；持钱包锁的流程（补发、开发入账）从不再取发布锁，
  恢复预算不足的发布（`QB_PUBLICATION_FUNDS_RESTORED`）只取发布锁、不取钱包锁。**晚取锁**：版本、参数在锁外读，锁内只重读会话、计数、发布与钱包再写入；继续答题与保存进度不取锁。
  **正确答案只在完成后出现**：开始与继续的输出不含选项的正误，详情只取快照的简介与资料，`result` 列在完成时才写；答题人读不到版本（`AnswerSecrecyIT`）。
  **钱包余额只在钱包锁内改**（`wallet.QbWallets.change`），与同一事务中的 `LEDGER_POST` 一起；判断可支付一律经 `QbWallets.pays`（无待支付且余额足够）：待支付不入账、按提交顺序补发、不插队；
  `qb_wallet.balance >= 0` 由检查约束兜底。评分、档位、抽成、打乱、时限与开始条件是纯 Java 的 `Scoring`、`TierPick`、`RewardSplit`、`OptionShuffle`、`AttemptRules`；
  上限在 `PlayLimits`，错误码在 `PlayCodes`。系统流程的权限 `qb.play.system` 不授予任何角色。**开发入账** `QB_WALLET_DEV_CREDIT` 只在
  `quizbuks.wallet.dev-credit.enabled=true`（`dev` 与测试）时注册，权限 `qb.wallet.dev-credit` 不授予任何角色，生产不开（启动检查拒绝它与 Stripe 正式密钥同时出现）。
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
  事务邮件模板在 `identity.AccountMails`（文案 `mail.<名>.subject|body`）；商家后台的地址是参数 `link`（`quizbuks.links.sponsor`），写在正文里而不是 Markdown 链接中（平台对链接中的参数做百分号编码）。
- **外部密钥**（`STRIPE_*`、`OPENAI_API_KEY`）只来自环境变量（`deploy/quizbuks/README.md`）。

## 5. 测试与命令（在 `backend/` 下）

- `./gradlew :quizbuks:check`（测试 + `platformCheck`）；单个类：`./gradlew :quizbuks:test --tests '*SetupIT'`。
- 集成测试连接真实 PostgreSQL（同平台：Testcontainers 或 `JABIZ_TEST_DB_*`），测试类放在 `com.jabiz.quizbuks.*` 下。
- 场景回放：`src/test/resources/scenarios/qb/**.yml`，`./gradlew :quizbuks:test --tests '*ScenarioTest'`；确认变化正确后加 `-Dscenario.update-snapshots=true`。

## 6. 运行

- 本地：`docker compose -f deploy/quizbuks/docker-compose.yml up -d --build`（数据库端口 5440、应用 8080，后台在 `/admin/`）；
  或只起数据库（`… up -d db`）后在 `backend/` 下 `./gradlew :quizbuks:bootRun --args='--spring.profiles.active=dev'`。
- 首次启动后，管理员登录 `/admin/` 执行一次 `QB_SETUP`（流程页面）。
