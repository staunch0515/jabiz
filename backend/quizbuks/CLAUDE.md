# CLAUDE.md — QuizBuks 应用规则

应用 QuizBuks（商家出资发布 Quiz、用户答题得 Kudos 奖励）的后端（模块 `backend/quizbuks`）。根目录 `CLAUDE.md` 的全部规则照常适用；本文件只写本应用额外的约定。
需求分析：`docs/quizbuks/01-requirements-analysis.md`；设计：`docs/quizbuks/02-design.md`；计划：`docs/quizbuks/03-plan.md`；各阶段的实施计划：`docs/quizbuks/plans/`。

## 1. 需求副本只读

- `docs/quizbuks-requirements/` 是需求方文档的原样副本，**永远不修改**。需求有问题时记在 `docs/quizbuks/`（分析或设计的待确认问题），由需求方改版后整体重新复制。

## 2. 边界

- 分支按平台版本线（平台决策 D21、`docs/guide/version-lines.md`）：`<线>/quizbuks`，工作分支 `<线>/quizbuks-<N>-<名>`（Shopify 集成的阶段为 `<线>/quizbuks-S<N>-<名>`）。现在是线 1.3（`1.3/quizbuks`，Shopify 集成，`docs/quizbuks/shopify/00-overview.md`）；`1.2/quizbuks` 只作备份（2026-10-28）：不再部署（手动运行须确认）、不再向前合并，修复只做在 1.3；1.3 迁移过的数据库不能再运行 1.2。
- 本分支只改 `.jabiz-app-paths` 中的路径（`tools/check-app-paths.sh`，基准为本线的平台分支），不改 `.jabiz-platform-line`。平台缺能力时，
  先在 `1.3/platform` 的工作分支上补（带平台自己的测试与 `app` 中的示范，不提 QuizBuks），合入后再合并到 `1.3/quizbuks`。计划中的平台阶段见 `docs/quizbuks/03-plan.md` 第 1 节与 `docs/quizbuks/shopify/00-overview.md` §4。

## 3. 命名

| 对象 | 约定 | 例 |
|---|---|---|
| 包 | `com.jabiz.quizbuks.<模块>`（`identity`、`content`、`publishing`、`play`、`wallet`、`journal`、`messaging`、`stats`；公用的在 `setup`、`country`） | `com.jabiz.quizbuks.wallet` |
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

- **金额**：账本与接口一律 JPY、小数位 0（`jabiz.ledger.currency: JPY`、`scale: 0`；字段 `asMonetary("JPY", 0)`）。三个应用前端把金额显示为点数（`1,234 Kudos`，中文"学分"、日文"クドス"，Q61，一处格式化函数）；
  平台通用后台对管理员仍显示 JPY。不写点数以外的对外币种名；例外只有商户商品的标价（Q60b：商店自己的价格，平台不经手，按其币种显示，`qb/products.ts` 的 `formatPrice`）。站内信的金额经 `MessageTexts.kudos`（消息 `qb.kudos.unit`），邮件的金额参数只是数字（`MailValues.amount`）、单位写在各语言的模板中。
- **会计年度**为日历年（`jabiz.fiscal-year-end: 12`）。
- **语言**：消息资源 `messages_{en,zh,ja}.properties` 三语齐全（`jabizApp { languages("en", "zh", "ja") }`，启动检查）；实体与字段 `entity.<实体>[.<字段>]`、流程 `process.<名>`、参数 `param.<键>`。
  产品显示名只写在 `app.name`（按语言：品牌档案的 `names`，Q61）。
- **初始数据只经 `QB_SETUP`**：角色、账本科目、国家、业务参数由它建立；它可重复执行，只补**从未存在过**的（`QbSetupRecord` 记下它建过或见过的每一项），不改已有的，也不加回管理员删除的角色、收回的权限。新阶段的新权限、科目、参数加到
  `QbRoles` / `QbLedger` / `QbParams`，部署后再执行一次 `QB_SETUP`。从代码中去掉的授权不会被收回：由管理员在已有安装中收回一次。
- **受控参数**（平台决策 D40）：金额与免审核开关列在 `QbParams.CONTROLLED`（`ControlledParams` Bean），只经受控变更修改；`QB_SETUP` 提出它们的首个值，由另一位管理员发布。
  不把 `platform.param.write` 授予任何角色。Q9d 的科目映射 `qb.journal.account.<科目>` 与收据开具方 `qb.issuer.*` 同样受控（首个值为空）：应用不能自定义受控变更的目标
  （`ControlTarget` 是平台的响应式接口），所以"只经受控变更修改的数据"一律写成受控参数，不另建实体。
- **账本**只经流程的子流程 `LEDGER_POST` / `LEDGER_REVERSE` 写入；`ledger.post`、`ledger.reverse`、`ledger.account.write` 不授予任何角色。
  月报 `qb.admin.monthly`（Q9c）只在来源单据（`sourceEntity`）、是否冲正、所动科目的集合与各科目的方向全都符合时才归入一类（退款与争议扣回所动科目相同，按引用的 Stripe 对象区分：`ch_` / `py_` 与 `dp_` / `du_`，`StripeIds`），其余一律 `OTHER`：新增一种过账时同步改它的分类与 `AdminFinanceIT` 的逐行期望。
- **国家数据**在 `quizbuks/countries.txt`（`code|en|zh|ja|regions`，无引号）；文件问题由启动检查（类别 `QUIZBUKS`）一次报告。
- **地区**：国家的 `regions` 是按 `country.Regions.ALL` 顺序、以逗号连接的地区代码，总含 `GLOBAL`（平台没有多值字段）；规则 `QB_COUNTRY_REGIONS_FORMAT` 只接受这一种写法。
- **内容（模版、题目、资料、版本）**（`content`，`docs/quizbuks/plans/Q3-content.md`）：写入只经 `QB_QUIZ_*` / `QB_QUESTION_*` / `QB_MATERIAL_*` 流程
  （默认数据视图 `processOnlyWrites`，商家视图只读）。写一个模版的流程先取锁 `HoldLock.exclusive("qb.quiz:" + quizId)` 再读取，经商家视图 `LoadEntity`（不是自己的或已移除即 404），
  子项经默认视图整体读取；每次内容改动给模版 `revision + 1`。上限与长度只写在 `content.ContentLimits`；错误码在 `content.ContentCodes`。
  版本由"保存版本"显式生成，快照是 `QuizSnapshots` 的规范 JSON（哈希用平台 `ContentHash`），快照引用的文件另登记 `QbVersionFile`。业务流程不调用 `FILE_DELETE`：不再引用的文件由平台清扫。
  新放入内容的文件必须由模版所有者上传（`QuizEditing.requireOwnFile`，经平台文件数据视图读 `uploadedBy`）。子项列表（选项、图片）给出即完整列表，不给（null）即不动。
  版本与版本文件没有商家数据视图（版本含正确答案）：商家只经连接本人未移除模版的模板读取。商家视图的写权限 `qb.content.view.write` 不授予任何角色。
- **发布方**（S0，`publishing.PublisherKind`，`docs/quizbuks/shopify/S0-publisher.md`）：引擎里"谁发布的"是一对不可变、必填的字段 `publisherKind`（`SPONSOR` / `SHOPIFY_SHOP`，字典 `urn:jabiz:dict:quizbuks:publisher-kind`）+ `publisherId`（`SPONSOR` 的编号就是商家的用户编号），
  以 `PublisherKind.declare(eb, processOnly)` 声明；S0 之前就有行被封存的五张只追加表（发布、档位、现金、兑换、电子奖品的 `*_version`）用 `declareSealed`：列仍叫 `sponsor_id`（V30，被完整性封存的列只能增加、不能改名，平台 21 §2.1，`SealedColumnsMigrationIT`）；其他表，包括以后新建的只追加表，用 `declare`，列是 `publisher_id`。**一条规则定范围**：凡是"发布的所有者的副本"（发布、档位、卡片、`QbPublicationOwner`、会话、分析的三张表、现金 / 兑换 / 电子奖品与码池、邀请）带这一对；
  商家账户自己的记录（充值、收据、邮件服务器、邀请配额、领奖码、AI 作业与用量、`QbSponsorRewardMethods.userId`、钱包、账本维度）保持 `sponsorId` / `userId`。新写入处必须显式给出类型（表上没有缺省，商家后台的流程写 `PublisherKind.SPONSOR` + 操作人）；
  商家的数据视图一律 `fromContext("publisherId", actorId)` **且** `fixed("publisherKind", SPONSOR)`（`RewardConfig.ownDataset` 按字段名自动加）；答题人的首页、详情与公开卡片视图只看 `SPONSOR` 的发布（店面流量在 S2 走 Shopify 模块自己的模板）。
  审批事实是 `publisherKind` + `publisherId`；事件 `qb.publication.status` 的载荷同样是这一对，消费者 `QB_MAIL_PUBLICATION` 只对 `SPONSOR` 发邮件、对没有发布方的事件什么也不做；钱包事件（`qb.sponsor.*`）的 `sponsorId` 不变。
  内容的 `ownerId` 与 AI 的 `sponsorId` 记的是**操作人**，不是发布方，S0 不改（店铺怎样成为操作人由 S1 决定）。界面文字仍叫"商家"，后台扩展以 `PublisherLink` 按类型显示（`SPONSOR` 链到用户页，其他显示编号）；地址参数仍是 `?sponsor=`。
- **QuizBuks for Shopify**（S1，模块 `backend/quizbuks-shopify`，包 `com.jabiz.quizbuks.shopify`，`docs/quizbuks/shopify/S1-install.md`）：Java 库，打进同一个 jar，**只依赖平台**与渠道合约 `backend/quizbuks-channel`（不引用引擎的类，S2 起以合约的记录调用引擎的 `QB_CHANNEL_*` 子流程；不碰钱包与普通商户表）；表 `qbs_*`、实体 `Qbs*`、流程 `QBS_*`、权限 `qbs.*`，迁移在本模块的 `db/migration` 中、与引擎共用版本序列（V31 起），文案是自己的消息包 `jabiz/quizbuks/shopify/messages_*`（`jabiz.i18n.bundles`）。
  登录只经入口 `shopify` 与平台的外部令牌（D59：提供方 `shopify`，`SHOPIFY_API_KEY` / `SHOPIFY_API_SECRET` 只取自环境变量），身份是店铺中的一个店员，角色 `QBS_SHOP` 由 `QB_SETUP` 建立；
  店铺与成员只经 `QBS_SHOP_JOIN`（每次打开内嵌页，第一位成员是主管理员，重装时恢复店铺；它的输出就是页面所示的概况，没有给店员的模板）与 `QBS_SHOP_ADMIN_TRANSFER` 写入；Shopify 的访问令牌按需交换（`QBS_SHOP_CONNECT`，`expiring=1`）、密封保存，只在锁 `qbs.shop-token:<店铺>` 内刷新（`QBS_SHOP_CHECK`），锁序 `qbs.shop:<店铺>` → `qbs.shop-token:<店铺>`；
  流程中核对会话令牌只用平台步骤 `VerifyExternalToken`。Webhook 只经入站提供方 `shopify`（`app/uninstalled`、三个合规主题与 S3 的 `app_subscriptions/update`），消费者以签名的正文核对不签名的 `X-Shopify-Shop-Domain`（`app_subscriptions/update` 的正文没有店铺地址：只取请求头的店铺、只向 Shopify 重读它的订阅，不写入正文中的任何内容）；卸载保留数据（会话在下次刷新时由 `ShopSignInGuard` 结束），`shop/redact` 才删除（已重装的不删）。
  对 Shopify 的调用只经 `ShopifyApi`（外部服务 `shopify`，主机 `*.myshopify.com`）。内嵌页是第三个 SPA `quizbuks-web/shopify`（`/shopify/`，`embedding: shopify`，App Bridge 取自 Shopify 的 CDN）。
  店面（S2，`docs/quizbuks/shopify/S2-storefront.md`、`S2b-ui.md`）：活动、码池与店面流程（`QBS_SF_*`，只经应用代理 `shopify`，平台 D60）在本模块；引擎只判分（奖励方式 `CHANNEL`）。顾客编号与访客键不进引擎（`QbsStorefront.customerTaker` / `visitorTaker`）。
  上架（S4，`docs/quizbuks/shopify/S4-launch.md`）：公开页面（落地页、隐私政策、支持）是静态站点 `quizbuks-web/shopify-site`（`/shopify-app/`，与 `biz` 分开）；隐私政策按代码实际保存的数据写，改了数据的保存或删除就同步改它。内嵌页的 Client ID 与支持邮箱在启动时写入（平台 17g 的 `index-values`），不在构建时写入。
  收费（S3，`docs/quizbuks/shopify/S3-billing.md`）：套餐由 Shopify App Pricing 出售，本模块**只读**订阅（`ShopifyApi.activeSubscriptions`，不调 `appSubscriptionCreate`），存于 `QbsShopPlan`；店面完成的答题只在 `QBS_SF_SUBMIT` 中、锁 `qbs.shop-usage:<店铺>` 内计入 `QbsUsage`，到套餐上限（受控参数 `qbs.plan.<套餐>.monthly-attempts`）即不能开始新的答题。
  店面组件是 `quizbuks-web/storefront`：原生 Web Component，**不用 React 与 `@jabiz/ui`**（只限这个组件：它跑在商户的主题里），打包成两个脚本放进 `tools/quizbuks/shopify/extensions/quizbuks-quiz/assets/`：区块的 `quiz.js` 只显示按钮，原始大小 ≤ 10 000 字节（Shopify 的上限），点开时才加载 `quiz-player.js`（S4c）；构建的最后一步 `scripts/extension.mjs` 按 Shopify 的规则检查扩展（`assets/` 的文件类型、区块脚本大小、`locales/*.schema.json` 的翻译）。产物不提交，`deploy-extension.sh` 先构建再 `shopify app deploy`；无障碍测试照样用 `@jabiz/ui/testing`。
- **发布**（`publishing`，`docs/quizbuks/plans/Q4-publishing.md`）：写入只经 `QB_PUBLICATION_*` 流程（默认数据视图 `processOnlyWrites`，商家视图只读，
  写权限同样是不授予任何角色的 `qb.content.view.write`）。**锁的顺序固定**：（`qb.account:<商家>`，封禁、发布的审批结果与管理员恢复取，Q10b 评审 L1）→ `qb.quiz:<quizId>`（只有提交时取，共享）→ 按发布编号升序 [`qb.pub:<publicationId>`（独占）→ `qb.pub-start:<publicationId>`（开始锁，Q10a4）] → 平台审批的 `jabiz.approval:*` → Q5 的 `qb.wallet:<种类>:<所有者>`；
  写一个发布的流程先取发布锁再读取；**改变已上架发布状态的流程**（暂停、恢复、`ADVANCE`、`FUNDED`）在发布锁之后、读取之前再取开始锁（`PublicationEditing.lockStarts` / `lockStatesAndLoad`），其他发布流程不取。**提交即冻结**：只有 `DRAFT` 可改（`QB_PUBLICATION_NOT_DRAFT`），要改就复制。唯一的例外是版本切换（Q51，`publishing.PublicationVersions`，`docs/quizbuks/plans/Q51-publication-versions.md`）：已上架未结束、未被下架的发布经再审核换成同一 Quiz 的满分相同的版本（同一时刻一个待审核，`pending*` 字段），在发布锁、开始锁之后取审批锁，生效时更新卡片与排程行的版本并为换下的版本记 `QbPublicationPastVersion`（移除模版时保留其文件）；会话保持开始时的版本，开始在开始锁内核对卡片的版本（`QB_ATTEMPT_VERSION_CHANGED`）。
  **管理员下架**（Q10b，评审 M2）：`QB_PUBLICATION_ADMIN_PAUSE` / `_RESUME` / `_END`（`publishing.PublicationAdmin`，`qb.admin.content.write`，总是要求二次验证，必填理由 `adminReason`，商家可见）照状态流程取发布锁与开始锁、经缺省数据视图读取；
  暂停记 `pausedBy`（`SPONSOR` / `ADMIN`），管理员暂停的商家不能恢复（`QB_PUBLICATION_ADMIN_PAUSED`）。封禁商家时 `QB_USER_BAN` 在同一事务中按发布编号升序以子流程 `QB_PUBLICATION_BAN_PAUSE`（`subProcessOnly`）逐个暂停其进行中的发布（账户锁在前），
  理由记固定的代码 `QB_ACCOUNT_SUSPENDED`（前端按语言显示；封禁的理由是内部的，评审 L3），其间已结束或回到草稿的什么也不做（评审 L2）；解封不恢复，封禁期间管理员也不能恢复（`QB_PUBLICATION_SPONSOR_BANNED`，评审 L9）。
  发布的审批结果与管理员恢复先在锁外读发布、取商家的账户锁再取发布锁（`PublicationEditing.lockSponsorAccount`），封禁与获批、恢复因此依次进行。**下架之后**（评审 M-d，`PublicationAdmin.takenDown`：管理员结束，或 `pausedBy = ADMIN`，含此后因时间或名额结束的；只有管理员恢复清除它）：
  不能复制（`QB_PUBLICATION_TAKEN_DOWN`）；同一 Quiz 以后的发布一律人工审核（案件事实 `autoApprove = false`、`takenDown = true`，不要求审核或沿用旧批准时拒绝 `QB_PUBLICATION_REVIEW_REQUIRED`），
  后台扩展的审核页经 `qb.admin.quiz-takedowns` 显示下架与理由，读好之前不给出决定面板。
  **"接受新的答题"只由 `PublicationRules.acceptsNewAttempts` 判断**（`LIVE` 且 `startAt ≤ now < endAt`），模板写同样的条件；状态只表达人与钱的开关，时间由时间字段表达。
  到期上下架只由逐项任务 `qb.publication-tick` 做（模板 `qb.system.publications-due` 按普通表 `qb_publication_schedule` 的 `next_due_at` 选出全部到期的，可补跑；每项 `QB_PUBLICATION_ADVANCE` 一个事务（平台决策 D42）；各流程在发布锁内随状态维护该行）。系统流程（定时任务、审批结果）声明 `internal()`（平台决策 D41：只允许系统身份运行，其他人一律 403 `PROCESS_INTERNAL`），权限 `qb.publication.system` 照常声明、不授予任何角色；审批结果只认发布自己的请求。时态表上只按不可变字段查找（可变字段的条件用不到索引）。档位、提交检查、审批内容是纯 Java 的 `TierCoverage`、`PublicationReadiness`、`PublicationApprovals`；
  上限在 `PublishingLimits`，错误码在 `PublishingCodes`。**审批规则只经 `QB_SETUP` 以受控变更提出**（`setup.QbApprovalRules`，与 `QbParams` 并列），由另一位管理员发布；
  "无规则即放行"由应用挡住（`QB_REVIEW_NOT_CONFIGURED`）。移除模版时，已提交过的发布所用版本的文件保留（`QuizProcesses.filesStillNeeded`）。
  上架时随计数的两行（完成一侧 `QbPublicationCounter`、开始一侧 `QbPublicationStartCounter`，同一事务）插入**卡片** `QbPublicationCard`（普通表：除状态与版本切换（Q51）外全部在提交时冻结，含档位的 JSON `CardTiers`），`PublicationEditing.write` 在发布锁内随状态同步它；卡片的封面先取发布自己的 `cover`（Q64，草稿中设、随审批冻结），否则版本的（`PublicationEditing.cardCover`，上架与版本切换都经它）；
  答题人的首页、详情、档位与答题流程只读卡片，不读时态的发布与档位。答题只经 `publishing.PublicationMoves` 改变发布（结束、预算不足）。
- **答题**（`play`，`docs/quizbuks/plans/Q5-answering.md`）：会话 `QbAttempt`、首次浏览、完成人数都是普通表，只经 `QB_VIEW` / `QB_ATTEMPT_*` /
  `QB_PENDING_*` / `QB_PUBLICATION_STATS_REFRESH` 写入（默认数据视图 `processOnlyWrites`，答题人视图只读、限本人）。会话带奖励与抽成，而数据视图不能去掉字段：`QbAttempt` 的默认数据视图不给任何角色读取（读权限 `qb.play.system`），财务读只读视图 `finance:QbAttempt`，支持人员只经不含金额的 `qb.admin.attempts`（Q9c，C-2）。**一人一次**由 `(user_id, publication_id)` 唯一约束兜底，
  开始在开始锁内再读本人会话。**两把锁**（Q10a4，`docs/quizbuks/plans/Q10-lock-split.md`）：开始只取开始锁 `qb.pub-start:<发布>`、只写计数的开始一侧（`starts`、`reservedTotal`）；
  完成（提交、超时与完成时的移动子流程）只取发布锁 `qb.pub:<发布>`、只写完成一侧（`completions`、`passes`、`winners`、`released`、`spent`），**不取开始锁、不读开始一侧**。
  进行中 = `starts − completions`、预占 = `reservedTotal − released`，凡是读这两个派生量的地方都**在一条语句中读两侧**（开始的锁内重读、四个模板），不要分两条语句读。
  完成移动不取开始锁依赖两点（设计 4.3）：限额的代数（读到较旧的完成一侧只会更严格）与"预算不足是软检查"；改变其中任何一点都要重新论证。
  **锁的顺序**：`qb.pub:<发布>` →（只有状态流程）`qb.pub-start:<发布>` →（只有 `ADVANCE` 撤回过期的审核时）平台审批的 `jabiz.approval:*` → `qb.wallet:<种类>:<所有者>`（如 `qb.wallet:SPONSOR:<用户>`）；
  开始只持开始锁，状态流程在开始锁之后只可能再取审批锁（`ADVANCE` 以 `WithdrawApproval.when` 只在审核过期时取；那时发布还没上架，没有开始在等）；持钱包锁的流程（补发、开发入账）从不再取发布锁或开始锁，
  恢复预算不足的发布（`QB_PUBLICATION_FUNDS_RESTORED`）只取发布锁与开始锁、不取钱包锁。**晚取锁**：版本、参数在锁外读，锁内只重读会话、计数、发布与钱包再写入；继续答题与保存进度不取锁。
  **取锁前排队**（平台决策 D45，Q10a2；Q10a4 两族）：开始以 `.admission(PublicationMoves.START_LOCK_FAMILY, 输入 -> 发布编号)`、提交与超时的逐项任务以 `.admission(PublicationMoves.LOCK_FAMILY, …)` 声明准入，
  族名与键只经 `PublicationMoves`（与 `startLockName` / `lockName` 同一段文字，否则平台告警；新族名不要写成 `qb.pub:…`，平台按 `族名:` 前缀归族）；
  提交的键是输入的 `publicationId`（App 带上），在锁前核对与会话的发布一致（`QB_ATTEMPT_PUBLICATION_MISMATCH`），**必填**（`@NotNull`，缺即 400：没有绕过队列、在连接上等锁的提交）；超时任务的键取自选取模板的发布列。
  队列的参数在 `application.yml` 的 `jabiz.process.admission.families[qb.pub]` 与 `[qb.pub-start]`，开始与提交的限流在 `jabiz.process.rate-limits`（每人每分钟 60 次）。新的取这两把锁的热门流程照此声明；正确性只靠锁。
  **锁内的语句数**（Q10a3，平台决策 D46）：锁内的每次往返约 0.8 ms，热门发布的容量由较长的一段（提交在发布锁内）决定。锁内互不依赖的重读写成一个 `QueryEntities.all`（一条语句：开始为本人会话、卡片与计数的两侧，提交为会话、完成一侧与卡片）；
  开始在锁外与会话、卡片同一条语句读发布（默认数据视图），新会话对发布的引用检查因此复用本事务的读取、不在锁内再查；普通实体的更新照平台按已知版本写出（重读即为此）。
  语句数由 `AttemptStatementBudgetIT` 断言确切值与所取的锁（平台 16p-2 的事务内写缓冲之后：开始共 10、开始锁内 3；付奖的提交共 13、发布锁内 7；使发布结束的完成共 18、锁内 12；暂停共 10、锁内 7；锁内自事务的第一把锁之后的一条起到 `COMMIT`；应急开关 `jabiz.storage.write-batching.enabled=false` 时为 16p-1 的 12 / 5、20 / 14、29 / 23、12 / 9，`AttemptStatementBudgetWithoutHeldWritesIT`），改答题流程须同时改预算并在压测报告中说明；不在锁内增加读取。
  **超时自动提交**只由逐项任务 `qb.attempt-expire`（每分钟，模板 `qb.system.attempts-due`）对每个会话运行 `QB_ATTEMPT_EXPIRE`。**逐项取锁或可能逐项失败的批量系统工作写成逐项任务**
  （`JobDefinition.forEach`，平台决策 D42），不写成根流程 + `CallProcess.forEach`（一批一个事务会违反锁序、阻塞热门发布、一项失败拖住整批；不取锁的统计任务除外）；每项流程是 `internal()`，先取锁、再读取、已不到期即什么也不做。
  **正确答案只在完成后出现**：开始与继续的输出不含选项的正误，详情只取快照的简介与资料，`result` 列在完成时才写；答题人读不到版本（`AnswerSecrecyIT`）。
  **选项按位置编号**（Q10b，评审 M1）：开始、保存、提交、`answers` 与 `result` 中的选项编号都是本次显示的位置 1..k，只有评分经 `optionOrder` 换回版本的编号（`Scoring.scoreShown`、`Answers.inVersion`）；
  `optionOrder` 是敏感字段，任何读接口都不返回。
  **单选与多选**（D-Q5-2，2026-10-29 需求方要求在前后台都说明）：问卷题按自己的 `selection`；计分题由正确选项的个数决定，恰好 1 个即单选、多个即多选，只经 `QuizSnapshot.Question.choice()` 判断。
  开始与继续的输出以 `selection` 给出它（只说"几个"中的单选或多选，不说是哪个），App 对单选题用单选按钮、多选题用复选框，商家后台的编辑、版本与预览和后台的版本预览都标出单选或多选（商家端为 `content/api.ts` 的 `choiceOf`）。打乱的种子是服务端密钥下的 HMAC（`OptionShuffle.of(IntegrityKey)`：HMAC(`JABIZ_INTEGRITY_KEY`, `qb.option-shuffle`) 再对用户 | 发布 | 题号，评审 M-a），测试的固定密钥使场景的顺序不变。测试中答案按版本的编号写（巴黎是 1），由 `PlayItSupport.submission` / `shown` 换成本次的位置（`AttemptOptionIdsIT`）。
  **钱包余额只在钱包锁内改**（`wallet.QbWallets.change`），与同一事务中的 `LEDGER_POST` 一起；判断可支付一律经 `QbWallets.pays`（无待支付且可用余额 `QbWallets.available` = 余额 − 争议冻结 `held` 足够；SQL 模板同样用 `balance - held`，核对仍比较 `balance`）：待支付不入账、按提交顺序补发、不插队；
  `qb_wallet.balance >= 0` 由检查约束兜底。评分、档位、抽成、打乱、时限与开始条件是纯 Java 的 `Scoring`、`TierPick`、`RewardSplit`、`OptionShuffle`、`AttemptRules`；
  上限在 `PlayLimits`，错误码在 `PlayCodes`。系统流程的权限 `qb.play.system` 不授予任何角色。**开发入账** `QB_WALLET_DEV_CREDIT` 只在
  `quizbuks.wallet.dev-credit.enabled=true`（`dev` 与测试）时注册，权限 `qb.wallet.dev-credit` 不授予任何角色，生产不开（启动检查 `StripeModeCheck` 拒绝它与正式模式或 Stripe 正式密钥同时出现）。它是开放流程，补发经事件 `qb.sponsor.settle-pending` 由系统运行（`QB_PENDING_SETTLE` 等答题的系统流程都是 `internal()`，D41）。
- **地址**（`docs/quizbuks/plans/Q30-paths.md`）：App 在 `/taker/`（入口 `app` 的 `app-path`）、商家后台 `/sponsor/`、管理后台 `/admin/`、招商站 `/biz/`（`quizbuks-web/biz`，Q31：`scripts/build.mjs` 从 `src/templates` 与 `src/content/{en,zh,ja}.mjs` 渲染的静态页面，三语结构相同由测试保证；首页的试答链接与二维码在 `src/content/try-quizzes.json`；只有 `src/assets/site.js` 一个脚本：语言转向与注册按钮）；裸域名由 `jabiz.web.root-redirect` 转到 `/biz/`，App 搬走前的根下路径由 `jabiz.web.redirects` 301 到 `/taker/`（清单在 `application.yml`，招商站新页面不得与之重名）。商家后台生成的 App 链接只经 `publishing/paths.ts` 的 `APP_BASE`；登录链接的返回路径是站点路径（App 用 `hrefOf`，邀请邮件用入口的 `app-path`），不写死 `/taker`。
- **指定邮箱回答的发布**（`publishing.Audience`、包 `invitation`，`docs/quizbuks/plans/Q25-invitations.md`）：受众是发布的字段 `audience`（`PUBLIC` / `INVITED`，上架时冻结到卡片），
  `INVITED` 的发布不要求地区（`PublicationReadiness`）、不进首页与公开模板、`qb.app.publication` 只对有邀请行的账号返回。清单 `QbInvitation` 只经 `QB_INVITATION_ADD` / `REMOVE` / `SEND`
  （都先 `CallerChecks::refuseBanned`，都在列表锁 `qb.invitation-list:<发布>` 内，发送再取配额锁 `qb.invitation-quota:<商家>`；上限与每日配额在配置 `quizbuks.invitations.*`，不是业务参数；寄送只在 `LIVE` 时）与内部 `QB_INVITATION_MAIL`（任务 `qb.invitation-mail`）写入；邀请邮件是模板
  `qb.invitation`（`signInLink`，平台决策 D55；收件人 `MailRecipient.address`）。答题流程读调用者的邀请只经 `Invitations.loadForTaker` / `mine`（公开的卡片一条语句也不读，预算不变）：
  `QB_VIEW` 认领、开始写 `STARTED` 与 `attemptId`、完成写 `ANSWERED` 与分数、奖励；不另写认领接口或事件消费者。读卡片地区的其他地方（通告、档位模板）要先排除 `INVITED`（其卡片的地区是占位的 `GLOBAL`）；管理员只看人数，地址只给商家。
- **客户匹配**（Q47，包 `audience`、`publishing.PublicationAudience`，`docs/quizbuks/plans/Q47-audience.md`）：条件种类只在 `audience.AudienceCriteria.ALL`（键、选项、范围、计费键 `audience.<键>`；前端 `src/qb/audience.ts` 照抄，新增种类先改这两处与三语文案 `qb/messages.ts` 的 `audience.*`，不问 APPI 的需要特别注意的个人信息）。
  答题者自己的回答 `QbProfileAttribute`（每个选项或数值一行）只经 `QB_PROFILE_ATTRIBUTES_SAVE` 整体替换（账户锁内，全部可选）；发布的条件 `QbPublicationCriterion`（带发布方那一对）只经 `QB_PUBLICATION_AUDIENCE_SAVE` 在草稿时整体替换（发布锁内，`INVITED` 不能有条件，`QB_AUDIENCE_INVITED`），提交即冻结，复制时随发布复制，删除草稿时一起删除。
  **匹配的定义只有一个**：`AudienceMatch`（同一条件内任一选项即可，各条件都须满足，没有回答即不满足，年龄 = 当前 UTC 年 − 出生年）；模板 `qb.app.feed`（不列出）、`qb.app.publication`（`matched`，进入 `canStart`；页面照样显示，以便请人完善资料）与 `qb.system.audience-count` 写同一段 SQL（改一处即改四处，`AudienceIT` 比较模板与 Java），开始答题在锁外既有的 `QueryEntities.all` 中读条件与本人的回答（语句数不变）、`INVITED` 以外不满足即 422 `QB_AUDIENCE_NOT_MATCHED`。
  **原始回答谁也读不到**：默认数据视图的读权限 `qb.audience.system` 不授予任何角色（`*` 照平台规则例外，只有首个管理员），答题者只经 `me:` 读自己的；商家只经 `QB_AUDIENCE_COUNT`（本人的发布，不是本人的 404）得到取整的人数（`AudienceCount`：少于 20 只说"少于 20"，否则向下取整到 10），计数模板只由该流程运行。以后换成专用的匹配服务器时，替换的是这几个模板与开始的读取，不是 `AudienceMatch` 的含义。
  按规则计费（Q47c：按发布、按所用条件种类、按有效期折算到天，从 Kudos 钱包扣）尚未实现，计费键已在条件种类上。
- **商家自己的邮件服务器**（包 `mailserver`，`docs/quizbuks/plans/Q29-merchant-mail-server.md`，平台 D56）：实体 `QbSponsorMailServer`（每个商家一行）只经 `QB_MAIL_SERVER_SET`（平台步骤 `SealSecret` 密封密码，阻塞步骤 `TestMailServer` 用平台的 `MailSenders` 检查设置并给商家自己的地址发测试邮件，结果写 `verifiedAt` / `lastError`）与 `QB_MAIL_SERVER_REMOVE` 写入；
  平台的 `MailSenderSource` Bean（`MailServerConfig`）只交出验证过的行。邀请只经 `SendMail.via(商家编号)` 发出（`QB_INVITATION_SEND` 没有验证过的服务器即 422 `QB_INVITATION_NO_MAIL_SERVER`；`QB_INVITATION_MAIL` 发现服务器不可用即退回 `ADDED`），绝不回退到平台的服务器；
  平台自己的邮件照旧。模板 `qb.sponsor.mail-server` 从不返回密码。`JABIZ_SEAL_KEY` 是部署必需（入口脚本检查、本机演示自己生成）；`jabiz.mail.senders.trust-all-for-tests` 只在测试配置与 e2e 中开。
- **运营商**（包 `operator`，`docs/quizbuks/plans/Q48-operators.md`）：运营商 `QbOperator`、成员 `QbOperatorMember`、商户归属 `QbSponsorOperator`、抽查 `QbReviewSpotCheck` 都是普通表，只经 `QB_OPERATOR_*` / `QB_SPONSOR_OPERATOR_ASSIGN` / `QB_REVIEW_SPOT_CHECK` 写入（默认数据视图 `processOnlyWrites`，读权限 `qb.admin.operators.read`）。**直营没有记录**：没有归属行的商户即总部的。
  **成员关系与角色分开**：角色 `QB_OPERATOR` 含 `approval.decide`，是受保护的授权，只经平台的角色分配与四眼（不在流程中授予）；成员关系由总部添加、总部或负责人移除（移除只写 `removedAt`，抽查按决定时的成员关系判断）。入口 `operator`（`/operator/`，管理后台前端的第二份构建）由 `QbSignInGuard` 另查当前成员关系与 `operatorActive`；
  **运营商的每个动作**先 `OperatorChecks.member`（共享锁 `qb.operator-member:<用户>`，加入与移除取排他锁）再取账户、发布等锁，锁序 `qb.operator:<运营商>` → `qb.operator-member:<用户>` → 其余。运营商的下架与封禁是自己的流程（`PublicationAdmin.operator*`、`BanProcesses.operator*`，权限 `qb.operator.use`），记 `takedownOperatorId` / `operatorId`：运营商只恢复与结束自己下架的、只解除自己的封禁、只封禁有入驻的商户；总部再次暂停或封禁即变成总部的（D-Q48-5）。
  **运营商不读用户资料**（没有 `qb.admin.users.read`）：只经 `qb.operator.*` 模板（商户、概况，只有合计）与只读流程 `QB_OPERATOR_TEAM`；扩展的页面按权限选模板（审核页、商户链接）。新增运营商可做的事时，权限加进 `QbRoles` 的 `OPERATOR_PERMISSIONS` 并在 `QbRolesTest` 的清单中写明，不给运营商用户资料、财务、法律文本、参数与角色的权限。
  多个运营商互相隔离（Q59c-2，平台 17y 的入口范围，决策 D64）：运营商的权限读得到的数据视图以 `OperatorScopes` 约束、对象以 `OperatorChecks.ownSponsor`（`RequireScope`）核对，总部也运行的运营商动作（作废兑换、电子奖品处理）以 `OperatorChecks.memberWhenScoped` 在动作之内再查成员关系（Q48e-1）；
  首次入驻带开拓链接的键（`QB_SPONSOR_APPLY` 的 `trialKey`）即归开拓的运营商、审核随之（`docs/quizbuks/plans/Q48e-operator-isolation.md`）。
- **运营商结算**（`operator.OperatorSettlements`，`docs/quizbuks/plans/Q48-operators.md` §7 Q48b）：收入的归属是发布卡片上线时冻结的 `operatorId`（不是账本维度：答题过账不多读），月结算按 4110 的发布维度连到卡片；`QbOperatorSettlement` 只经 `QB_OPERATOR_SETTLEMENT_RUN`（逐项任务，`internal()`）/ `PAY` / `APPROVAL_RESULT` 写入，过账 5130 / 2140（来源 `QbOperatorSettlement`，月报的 `OPERATOR_SHARE`）。锁 `qb.operator:<运营商>`。
  **方案与账户**（Q48e-2，`OperatorPlans`、`OperatorWithdrawals`，`docs/quizbuks/plans/Q48e-operator-isolation.md`）：比例与固定费取运营商当月的方案（受控参数 `qb.operator.plan.<n>.fee|share`；方案变更 `QbOperatorPlan` 只经 `QB_OPERATOR_PLAN_SET` 与审批结果写入，四眼、从以后的月份起）；结算记入运营商的账户（`CREDITED`，余额 = 记入的应付 − 未驳回未取消的提现，模板 `qb.system.operator-balance`），钱都在平台；提现 `QbOperatorWithdrawal` 只经负责人的 `QB_OPERATOR_WITHDRAWAL_REQUEST` / `CANCEL`（入口范围给出运营商，先取运营商锁再查成员关系）与财务的 `PAY` / `REJECT`、审批结果写入，批准后过账 2140 / 1120（月报 `OPERATOR_PAID`）；银行账户 `QbOperatorBankAccount` 只经 `QB_OPERATOR_BANK_ACCOUNT_SAVE`。此前签发（`ISSUED`）的结算照旧逐张 `PAY`。
- **AI 获客**（包 `prospect`，`docs/quizbuks/plans/Q48-operators.md` §3 与 §7 Q48c）：所有者 `owner` 是运营商编号或总部的 `direct`，只由 `ProspectProcesses.owner` 决定（调用者的当前成员关系，否则持有 `qb.admin.prospect` 即总部，否则拒绝），不取自输入；五张普通表（设置、方向、候选、不发名单、月用量）只经 `QB_PROSPECT_*` 写入（默认数据视图 `processOnlyWrites`，读权限 `qb.admin.prospect`；运营商只经 `qb.operator.prospect*` 模板读自己的）。
  Claude 的调用只在两种外部作业（`ClaudeWebResearch`：网页搜索与读取，不用结构化输出、以末尾的 JSON 对象作答并由 `ProspectAnswers` 逐项核对，`pause_turn` 续发），处理器不访问数据库，作业类型 `enabledBy(qb.prospect.enabled)`；用量（tokens 与搜索次数）只由作业的结局流程在所有者锁内计入 `QbProspectUsage`（没有回答与没有记下用量的尝试按预留计入），当月达到上限即不开新的找与研究。
  **候选从不自动发送**：研究只写草稿（`DRAFTED`），人经 `QB_PROSPECT_APPROVE`（改过的邮件与问卷照 Claude 的同样核对：纯文本、无链接，5 题 4 选 1）批准，逐项任务 `qb.prospect-send` 在工作时间内、每日上限之内经 `SendMail.via("prospect:<所有者>")` 从所有者验证过的服务器发出（`QB_PROSPECT_MAIL_SERVER_SET`，与商户邮件服务器同表），绝不经平台的服务器；邮件带发件人名称、住所与联系方式（《特定電子メール法》）与平台的地址退订链接。
  **不发名单**（地址或域名，全平台生效）在找、研究与发送前都查；退订、拒收声明与"屏蔽"的丢弃都记进去。锁序 `qb.prospect-owner:<所有者>` → `qb.prospect:<候选>` →（只有用量）`qb.prospect-usage:<所有者>`；体验问卷不是发布，只经公开模板 `qb.public.prospect-trial`（只给已发出的，按随机键 `trialKey`，不叫 token）在 `/sponsor/try/<键>` 显示。
  Q48d：注册归属只经 `QB_PROSPECT_CLAIM`（商家后台记住 `?src=` 的键、申请之后调用一次；只为没有归属、邮件发出后才通过审核的商户写 `QbSponsorOperator`），跟进只经 `qb.prospect-follow-up`（7 天后一次，与首封共用检查与每日上限），自动发送只按所有者的 `autoApproveScore`；运营商的月度上限另受受控参数 `qb.prospect.operator-monthly-cap-usd` 约束，结算按 `qb.prospect.usd-jpy` 扣除当月费用（被扣回的计入 4110，扣不完的结转）。
- **业务参数的存在**由启动检查 `setup.SetupParamsCheck` 保证（Q26）：新增参数只加进 `QbParams.ALL`（受控的还加进 `CONTROLLED`），部署后执行 `QB_SETUP`；缺少即启动警告，不在流程里自己给缺省值。
- **投资人指标**（`queries/qb/admin/investor-metrics.sql`、`investor-sponsors.sql`，`docs/quizbuks/plans/Q27-investor-metrics.md`）：只是两个带 `report: period` 的财务模板（UTC 月，各数字按发生时间归月，读时态实体的当前版本）与后台扩展的页面 `InvestorMetricsPage`（导出 / 签发 / 存档复用 `ReportsPage` 的 `ReportActions` / `Issued`）；不存数字、不建表、不建任务。新加一列先改模板与 `InvestorMetricsIT`，再改页面与三语消息。
- **奖励的支付方式**（`publishing.RewardMethod`、`reward`，`docs/quizbuks/plans/Q22-reward-methods.md`）：`PLATFORM`（平台经账本付）、`SPONSOR_CASH`（商家付现金）、
  `OFFLINE_REDEEM`（商家处兑换）。商家允许的方式只经 `QB_SPONSOR_REWARD_METHODS_SET` 写 `QbSponsorRewardMethods`（没有记录 = 受控参数 `qb.reward.default-methods`，声明值 `PLATFORM`，推广期发布 `OFFLINE_REDEEM`；每次答题的最高奖励 `qb.reward.max-per-attempt`，0 为不限；`plans/Q28-phase1-defaults.md`。商家后台经模板 `qb.sponsor.reward-methods` 读当前允许的方式，不自己猜缺省）；发布的 `rewardMethod` 在保存、复制、提交时都经
  `PublicationEditing.methodProblems` 核对，兑换须有 `redeemDescription`。不是 `PLATFORM` 的结算**不取钱包锁、不读钱包、不过账**（`AttemptFinishing`、`AttemptRules`），只插入 `QbCashReward` / `QbRedemption`
  （都是时态实体，状态在 `QbRewards.TRANSITIONS`，流程在现金记录锁 `qb.cash:<记录>` 内）并记计数；现金的状态只经 `QB_CASH_REWARD_*` 流程改变，逾期禁止开始只在 `CashRewardChecks.refuseOverdue`。
  期限 `qb.reward.cash.confirm-days` / `cash.pay-days` / `redeem-days` / `digital.send-days` 是普通参数（`LoadParams` 读，不受控）。
  电子奖品（Q33，`plans/Q33-digital-prize.md`，`reward.QbDigitalPrizes`、`DigitalCodeProcesses`、`DigitalPrizeProcesses`）：第四种方式 `DIGITAL_PRIZE`，发布上 `digitalDelivery`（`POOL`/`SHARED`/`SPONSOR_SENT`）、
  `digitalDescription`、`digitalSendDays`，共用码 `sealedDigitalContent` 经 `SealSecret` 以**商户编号**为上下文密封（码池 `QbDigitalCode.sealedContent`、奖品 `QbDigitalPrize.sealedContent` 同一上下文），
  字段 `sensitive()`（不再加 `processOnly()`：敏感已含）；明文只在 `QB_DIGITAL_PRIZE_OPEN` 的输出 `prizeContent` 中（标 `@Sensitive(returned = true)`：响应中返回，`op_process_result`、事件与摘要中遮蔽），输出 record 的 `toString()` 遮蔽；
  码池只经 `QB_DIGITAL_CODES_LOAD`/`REVOKE` 写、结算在池锁 `qb.digital-pool:<发布>` 内按 `seq` 取码；`POOL` 的提交在 `PublicationLifecycle.submit` 数可用码（`PublicationReadiness.pool`）。
  列表与结果只给 `hint`（末 4 位，共用码没有）。
  到店领取（Q22b，`reward.RedemptionProcesses`）：商家的码只经 `QB_REDEEM_CODE_ISSUE`（商家本人）与 `QB_STAFF_REDEEM_CODE_ISSUE`（店员，只有轮换码，Q39）签发（随机数经平台 `RandomBytesStep`，值只在输出 `redeemKey` 中；实体字段 `tokenValue` 敏感且含 token，
  平台按名遮蔽，输入组件叫 `scannedKey` 并标 `@Sensitive`——不要给输出组件起含 password / secret / token / credential 的名字，否则接口返回被遮蔽）；
  领取只经 `QB_REDEEM_TOKEN_CHECK` / `QB_REDEMPTION_CLAIM`（轮换码即时 `REDEEMED`，经手人 `redeemedBy` 为码的签发人 `issuedBy`；固定码 `CLAIMED` 由 `QB_REDEMPTION_CONFIRM` / `DECLINE`
  或店员的 `QB_STAFF_REDEMPTION_CONFIRM` / `DECLINE` 结束，确认业务规则共用 `RedemptionProcesses.confirm` / `decline`）；码是链接 `/redeem/<key>`，手机相机即可打开，App 另有自己的"扫一扫"（Q52，`/scan`，`app/src/scan/`：只打开本站的发布与领奖链接（`scanTarget`），画面不离开手机，取景用浏览器的 `BarcodeDetector`、没有时按需加载 jsQR；改码的链接格式时同时改 `scanTarget`）。
  店员（Q39，`reward.StaffProcesses`，`docs/quizbuks/plans/Q39-sponsor-staff.md`）：普通表 `qb_sponsor_staff`（一人至多属于一家），只经商家的 `QB_SPONSOR_STAFF_ADD` / `REMOVE` 与店员的
  `QB_STAFF_LEAVE` 写入；店员不另有角色或权限，店员的流程用 `qb.me`、每次经共享锁 `qb.staff:<用户>` 读本人的店员关系（加入与移除取排他锁），商家被封禁即拒绝
  （`QB_STAFF_SPONSOR_BANNED`）；锁的顺序 `qb.sponsor-staff:<商家>` → `qb.staff:<用户>` → `qb.redeem-token:<商家>`。店员只看待处理的申请与本人交付的（`qb.app.staff-redemptions`），不看商家的历史。
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
  事务邮件模板在 `identity.AccountMails`（账户）与 `messaging.NotificationMails`（Q7a：转账、收款账户、充值、发布）（文案 `mail.<名>.subject|body|name`，`name` 是偏好中显示的名字，平台 D49）；链接到某个前端用平台的 `{entryUrl.<入口>}`（商家邮件为 `[…]({entryUrl.sponsor})`，D43 第 4 条），不用参数传地址。`quizbuks.links.sponsor` / `quizbuks.links.app` 只给 Stripe 的返回地址。
  **邮件只由事件的消费者发**（`messaging.MailProcesses`，`internal()`，权限 `qb.messaging.system` 不授予任何角色）：业务流程只在自己的事务中发布事件（发布的状态变化为 `qb.publication.status`，由 `publishing.PublicationNotices` 判定哪些迁移值得通知），
  消费者再读记录本身（不信事件中的数字；如转账邮件只在转账仍是 `SENT` / `FAILED` 时发，结束邮件在下架后或商家被封禁时不发）、跳过没有邮箱的用户与过时的事件（通知 3 天、事务邮件 14 天，`NotificationMails.MAX_AGE` / `TRANSACTIONAL_MAX_AGE`），以平台的 `SendMail` 记下邮件；答题与支付的锁内不发信。可退订的（`NOTIFICATION`）只有商家的两种，声明 `.entries("sponsor")`（欢迎邮件 `.entries("app")`）；新增可退订的模板时同样声明入口与三语的 `mail.<名>.name`。退订链接按入口指向各 SPA 自己的退订页（`jabiz.mail.unsubscribe-paths.sponsor` / `.app` 为 `mail/unsubscribe`，两个 SPA 在该路径挂载 `@jabiz/ui` 的 `MailUnsubscribe`，平台 D52 第 3 条）：不要求 `/admin/` 对外可达；没有声明入口的模板仍用后台的页。
  会被反复触发的通知按收件人与种类在 `QbMailNotice` 中节流（每 24 小时至多一封，锁 `qb.mail-notice:<收件人>`，不取发布锁或钱包锁）：预算不足一封列出全部预算不足的发布，窗口内的再次不足只记 `pending`、不丢弃，由逐项任务 `qb.mail-funds-short-due`（每小时，模板 `qb.system.mail-funds-short-due`，每项 `QB_MAIL_FUNDS_SHORT_DUE`）在窗口过后重读卡片、仍不足才发；收款账户受限的邮件同样每人每 24 小时至多一封。邮件中的时刻一律按 `jabiz.fiscal-year-zone`（日本时间）并标时区简称（`messaging.MailValues`），不写死时区。
- **支付**（`wallet`，`docs/quizbuks/plans/Q6-payments.md`）：
  - **全部 Stripe 调用都是异步外部作业**（`wallet.TopUpJobs`，处理器只经外部服务 `stripe` 与 `StripeApi`：自写表单编码与解析，不用 SDK；以 `Stripe-Version` 固定版本），
    不在事务中等外部调用，不放在提交后步骤（其重试不持久化）。作业的完成与失败流程、入站事件的消费流程一律 `internal()`，权限 `qb.wallet.system` 不授予任何角色。
  - **只信服务端记录**：Checkout 的金额、币种、引用与返回地址只由 `CheckoutRequest` 按记录与配置生成；入账的金额、对象与锁名只取自记录，不取自 Stripe 的元数据；
    签名的事件同样逐项核对（`TopUpChecks`、`RefundMath`），不成立的开待办（`TopUpTasks`，给 `qb.reconcile`）、不入账、不让流程失败。浏览器跳回不入账。
  - **只入账一次的四层**：平台按事件号去重；每个（消费者，事件）只处理一次；同一会话的各种报告（通知、核实）走同一套步骤 `TopUpCrediting`，在商家钱包锁内重读，
    只有 `OPENING` / `OPEN` 能变为 `PAID`；数据库兜底（会话、付款、交易号唯一，`PAID` 必有分录）。新的入账来源照此接入，不另写入账步骤。
  - **锁序**：`qb.account:<用户>` → `qb.quiz:<模版>`（共享）→ `qb.pub:<发布>` → `qb.pub-start:<发布>`（Q10a4，只有状态流程）→ `qb.wallet:SPONSOR:<商家>`；`qb.wallet:TAKER:<用户>` 独立（Q6b：只由同步与转账持有，
    持有它的流程不取其他锁；重算多人时按用户编号排序依次取）。
    充值、到账、过期、核实、手续费、退款与争议只取商家钱包锁（先在锁外按编号或付款找到充值，锁内重读）；每日核对不取业务锁。
    充值开始（Q10b）单独取 `qb.topup:<商家>`：**新商家上限**（`wallet.NewSponsorLimit`）在首次充值成功前与之后 `qb.topup.new-sponsor.days` 天内，把已付（含退款的）与未结的充值（创建中的直到其作业结束或失败，评审 L4；开立的直到过期）连同本次限制在
    `qb.topup.new-sponsor.max-total` 之内（422 `QB_TOPUP_NEW_SPONSOR_LIMIT`）；商家页经 `qb.sponsor.topup-limit` 提前显示（它与 `qb.app.wallet` 经不允许时间旅行的参数视图 `now:SysParam` 读参数，按服务端时钟，评审 L9）。保留期与上限的三个参数都是受控参数，未发布时转账 `INVALID_PARAMS`、充值 `QB_TOPUP_UNAVAILABLE`。
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
    转账的一切流程只持 `qb.wallet:TAKER:<用户>`，锁内从账本读余额；金额只取自账本与记录。**只转未保留的部分**（Q10b，评审 M3）：模板 `qb.wallet.taker-transferable` 给出保留中的奖励
    （最近 `qb.transfer.hold-days` 天入账的，与有未结争议的商家在被争议付款之后入账的，奖励即来源为 `QbAttempt` 的 2120 贷方行，商家经发布维度与卡片找到；
    只计尚在余额中的部分：转出先取自由的、再取按时间保留的、最后取争议冻结的，只取此前入账的，保留不超过余额；转账退回的钱（冲正、撤回）与该转账的借方相抵；模型 `wallet.RewardHolds`、设计 4.4，评审 M-b），`TransferRules` 按 `余额 − 保留` 判断门槛与金额，
    审核通过后同样再查；App 的 `qb.app.wallet` 按同一规则给出保留中与预计可转时刻（改规则时两处一起改，`TransferHoldIT` 比较两者）。每人至多一笔未结束的转账（部分唯一索引）；
    发出作业先按 `transfer_group` 查询再以转账自己的幂等键创建；**只有 Stripe 拒绝创建（`TransferRules.certainFailure`）才冲正**，其他结果进入 `CHECK`、
    向 Stripe 核实后定论：查询"没有"只有在晚于发送结束（`sendEndedAt`）至少 10 分钟时才是定论。Stripe 把执行过的请求的回答（含错误）保存在幂等键下，
    同一键重试余额不足或 5xx 永远得到同样的回答：这两者立即结束作业（`StripeTransferHandler.kept`），次日的新转账用新键。
    被驳回或被撤回（含部分）的转账使用户暂停，直到财务以 `QB_TRANSFER_HOLD_RELEASE` 放行；审核人的意见不写进用户可读的字段。
    开通链接只由本人的数据视图读取（默认数据视图的读权限是不授予任何角色的 `qb.payout.system`）。大额审批的准备人是收款人（平台挡住自审）。撤回按累计金额只记一次。新增 Stripe 交互照 `PayoutJobs` / `StripeTransferHandler` 的写法。
- **分录导出与收据**（`journal`、`wallet.TopUpReceipts`，`docs/quizbuks/plans/Q9d-journal-receipts.md`）：
  - 平台账本本身是复式总账，导出只把平台科目映射到会计系统的科目代码（`QbParams.JOURNAL_ACCOUNTS`，每个 `QbLedger` 科目一个，启动检查 `JournalMappingCheck`）；新增科目时同时加映射参数。
  - 一期 = 一个**日本日期**的月（`JournalPeriods`，D-1；Q9c 的月报仍按 UTC），按顺序导出（`QB_JOURNAL_EXPORT`，命名锁 `qb.journal`，`(periodFrom, granularity)` 唯一），
    第一期为最早过账的月份。每笔分录恰在一次导出中：本次取"过账于期末之前、记录于截止时刻之前、且不在以前的导出中"的（模板 `qb.system.journal-*`；上一期末取导出记录的 `periodEnd`；截止 = 导出时刻 − `JournalPeriods.SETTLE`，
    使导出时尚未提交的过账进入下一次；同一数据库另有事务已打开超过 `quizbuks.journal.max-open-transaction`（≤ `SETTLE` 的一半）即拒绝，`QB_JOURNAL_TRANSACTION_OPEN`）。迟到的分录进入下一次导出，已导出的凭证从不改变；冲正照常是新分录。
  - **规模**（评审 H1）：凭证行由数据库汇总（`qb.system.journal-summary`）；明细按交易区间分段（`qb.system.journal-parts`，每段在 `jabiz.process.max-read-rows` 与 `jabiz.files.generated.max-bytes` 的一半之内，`JournalLimits`），
    每段由子流程 `QB_JOURNAL_DETAIL_PART`（`subProcessOnly`）读一次、存一个文件 `<凭证号>-detail-<n>.csv`，记在 `QbJournalExportPart`。读取上限是按次的，不是按流程累计；一笔交易超过半段即 `QB_JOURNAL_TOO_LARGE`。不在导出中一次读全部分录。
  - 导出前一次报告全部问题：月未结束、月末之后的最近一次核对不是 `OK`、所动科目未映射或代码不合格（`[A-Za-z0-9][A-Za-z0-9._-]{0,19}`）、借贷不等、超过上限、有打开已久的事务；凭证号只在这些检查之后取（`AssignNumber`，`QBJ-<年>-00001`）。
    凭证 CSV（只含汇总行，供导入）与明细 CSV（时刻按日本时间）只经 `FILE_ARCHIVE` 原样存档（读取要 `qb.admin.finance.read`），单元格全部加引号、以 `= + - @`、制表、回车开头的加 `'`，UTF-8 带 BOM（`JournalLines`）。
    预览 `QB_JOURNAL_PREVIEW` 是同一套读取与汇总、不写入（平台的流程接口没有试运行，见计划的实施记录），同样先查封禁，并给出明细的行数、文件数与是否接近上限。外部凭证号只记一次（`QB_JOURNAL_EXPORT_CONFIRM`）。
  - 收据是平台单据（版式 `qb.topup_receipt`）：`QB_TOPUP_RECEIPT_ISSUE` 只给本人已到账、未退款、无未结或已败争议的充值（`QB_RECEIPT_REFUNDED`）、开具方名称已设时签发，每笔至多一张（`QbTopUpReceipt`，`topUpId` 唯一），再次请求返回同一张；
    按签发时刻读取，语言为商家所选（en / zh / ja）。中文、日文需要 `jabiz.reports.pdf.fonts`（镜像已设）。收据是收款凭证，收款金额写日元（JPY，"Kudos 以外不写币种名"
    的唯一例外，同一张上另写存入钱包的 Kudos 与卡片实际扣款的币种和金额）。收据不声称是适格请求书；登记号与税的表述待财务、法务确认。
  - **商家持有 `document.archive.read`**（为读自己的收据）：平台按版式的权限与对象决定能读哪些单据，所以以后新增的单据版式必须把对象限定在本商家（如 `subject` 经商家数据视图），
    或者其权限不能是商家权限的子集，否则商家能读到别人的单据。
  - **保留期**（Q10b）：导出（`periodTo`）、明细段与外部凭证号（`createdTime`）、充值（`paidAt`）、收据（`issuedAt`，迁移 V11）、入站 Stripe 事件、导出存档的文件（平台的 `GeneratedFile`，`createdTime`：QuizBuks 唯一的服务器生成文件）与收据的 PDF 存档（平台的 `DocumentRun`，`issuedTime`：QuizBuks 唯一的单据；两者平台 16v-1）都以 `RetentionPolicy` 保留 10 年，自会计年度末起算（`RetentionIT`）。
- **商家分析**（`stats`，`docs/quizbuks/plans/Q9e-analytics.md`）：**答题路径不写任何分析数据**（开始、提交、超时与 `QB_VIEW` 不变，`AttemptStatementBudgetIT` 不变，`qb_attempt` 不加索引），分析只由逐项任务 `qb.analytics`（每 10 分钟，`JobDefinition.forEach`，模板 `qb.system.analytics-items` 给出项：按发布编号的块、结束后的整体重算、最后一项运行记录；每项一个事务的 `QB_ANALYTICS_ITEM` `internal()`，权限 `qb.stats.system` 不授予任何角色；不取业务锁、只写自己的三张表；一项失败不拖住其他项，Q9e1 评审 M3）聚合：
  每块**一条语句读两侧计数**、游标、新增浏览，把增量加进一个按小时（UTC）的桶（`AnalyticsBuckets`：跨过整点后的第一次运行加进上一次运行的小时，停机的增量记在一个小时并标 `gap`）；已完成的会话按水位线 `(submittedAt, attemptId)`、滞后 5 分钟（`AnalyticsLimits.LAG`）从现有索引读、每块至多 `quizbuks.analytics.max-attempts-per-chunk` 个，结束 30 分钟且没有进行中的会话时整体重算一次并定稿（结束一天后不论是否还有进行中的都定稿；完成多于 `quizbuks.analytics.max-recount` 的不重算，读完即定稿，使重算不超过读取时限）。只写变化了的行（审计照常；游标的题目合计 `auditMasked`）。
  **最小分组 k**（`qb.stats.min-group`，缺省 5、下限 3，`MinGroup`）：可推出个人作答的数字（每题正确率与难度、平均用时、超时人数）只在至少 k 个完成时给出，且公开值只在**新增至少 k 个完成后**才变化（含结束后的重算），两次查看之差推不出一个人；人数不受 k 约束（E-4）。k 的读法在每个模板中写同一段 SQL（`greatest(3, … 5)`），改规则时全部一起改并由 `AnalyticsIT` 覆盖。
  **题目按版本统计**（Q51b）：每个会话计入它开始时的版本（游标的 `questions` 按版本存放，`QuestionTotals`；`QbQuestionStat` 以（发布、版本、题号）为键），一个版本的题目数字只在该版本新增至少 k 个完成后变化；`qb.sponsor.analytics.questions` 的 `versionId` 只接受该发布用过的版本（卡片的与 `QbPublicationPastVersion` 的）。
  商家的模板 `qb.sponsor.dashboard` / `qb.sponsor.analytics.*`（`qb.stats.read`，只授予 `QB_SPONSOR`）一律经限本人的卡片视图 `sponsor:QbPublicationCard` 连接（普通表按 `sponsor_id` 读，不扫时态的发布，评审 M2），只给汇总、不读 `qb_attempt` 与资料；单个发布的概览的人数取任务的快照（游标），不随每次答题变化（评审 M1）；三张分析表没有商家数据视图（游标中有未公开的合计）。按日的日界是部署时区 `jabiz.fiscal-year-zone`（`Asia/Tokyo`），由任务记进 `qb_stats_run.zone` 供模板读取，不在模板中写死时区；它只能是 `UTC` 或地区名（偏移写法在 PostgreSQL 中方向相反，启动检查 `AnalyticsZoneCheck` 拒绝）。
- **条款、隐私与帮助**（`messaging`，`docs/quizbuks/plans/Q7-messages-legal.md` Q7b）：法律文本的草稿 `QbLegalDoc`（每种一份，`QB_SETUP` 建立明确标注 `[TEST PLACEHOLDER]` 的占位，不发布）与 FAQ `QbFaqEntry` 由内容管理员在通用后台直接编辑；
  **法律文本只经 `QB_LEGAL_PUBLISH` 发布**（每次二次验证，版本 `QbLegalVersion` 只写一次；必须带页面读到的草稿版本 `baseRevision`；生效时间至多 `qb.legal.max-announce-days`（受控参数，未发布时 180）天后），
  **预告错了只经 `QB_LEGAL_WITHDRAW` 撤回**（生效前、带理由，`QbLegalWithdrawal` 只写一次；撤回的版本永不生效、不要求同意，版本号不再使用）；两者先读草稿或版本（种类不可变）再取命名锁 `qb.legal:<种类>`。
  规则只写在纯 Java 的 `LegalRules`（当前版本 = 生效时间已到、未撤回的最大版本号，没有状态与任务；须同意版本 = 其中须同意的最大版本号，第一版总是），
  模板 `qb.system.consent-state`、`qb.me.consents` 与公开的 `qb.public.legal*` 写同样的规则（改规则时一起改）。**同意按版本记录**（`QbConsent`，只插入，`ConsentChecks.record`）：欢迎页（`QB_PROFILE_SAVE` 第一次完成资料）、入驻申请（`QB_SPONSOR_APPLY`，审批内容含 `termsVersionNo`）
  与登录后的重新同意（`QB_CONSENT_ACCEPT`，只接受本入口的种类与当前版本）。**资金与合约类写入**（充值、提交发布、开通收款；以后新增的同类流程照此）以 `.steps(ConsentChecks.sponsor())` / `.steps(ConsentChecks.taker())` 在锁外再查（422 `QB_CONSENT_REQUIRED`），开始答题不查（Q7-2）。
  没有已生效版本时不要求同意（开发、测试与新装）。匿名读取只经公开模板 `qb.public.*`（公开视图：法律文本的已发布版本、已发布的 FAQ；草稿没有公开视图，公开视图不放发布人与哈希），`JABIZ_PUBLIC_ENABLED` 缺省开。
  QB_SETUP 新建的行使场景的 ID 序列后移：答题场景中的答案按显示位置写，随之按新位置改写（得分不变）。
- **站内信与公告**（`messaging`，`docs/quizbuks/plans/Q7-messages-legal.md` Q7c）：**个人消息 `QbMessage` 只由事件的消费者写**（`QB_MESSAGE_FROM_EVENT`，`internal()`；收款提醒为逐项任务 `qb.payout-reminder`），
  从不在答题、支付的锁内写（`AttemptStatementBudgetIT` 不变）；消费者重读记录、仍成立才写，`sourceKey` 唯一使同一事件只成一条，7 天以前的事件跳过（重放不补发）；
  标题与正文创建时按 `qb.msg.<种类>.title|body` 以三种语言渲染（`MessageTexts`，Q7-15），不含他人的数据。新的消息种类：加事件订阅、种类常量、三语文案与 `InboxIT` 的用例。
  公告与商家通知**不按人复制**：发送时写一行冻结的 `QbBroadcastFeed`，收件箱读时匹配。匹配的条件写在 `qb.app.inbox`、`qb.app.inbox-unread` 与 `qb.system.broadcast-audience` 三处（改一处即改三处，`InboxMatchIT` 的真值表检查三者）。
  **收件箱只读 `live` 的行**（评审 H1）：过期的与发布已结束的通知由每分钟的逐项任务 `qb.broadcast-expire`（`QB_BROADCAST_EXPIRE`）关掉，部分索引 `(audience, expires_at) WHERE live` 因此只有当前的行；模板不收页面的时间（评审 L1，`at` 不是参数）。
  未读徽标从读者的 `QbInboxState.readThrough` 之后数起（`QB_MESSAGE_MARK` 在写入已读之后以 `qb.app.inbox-unread` 的 `oldestUnread` 推进它，至多落后 10 分钟，从不后退；它不是阅读，列表与统计不用它）。
  个人消息一律是纯文本（评审 H2：参数中有商家写的发布名）；只有平台的公告按 Markdown 显示。商家通知在其发布暂停或预算不足时隐藏（卡片 `SCHEDULED` / `LIVE` 才显示，评审 M1）。
  商家的统计只读每日快照 `QbBroadcastStats`（任务 `qb.broadcast-stats`，评审 M2）：到达人数向下取整（不足 10 不显示），阅读不足 5 不显示，到达不足 `qb.broadcast.stats.min-audience`（50）时不显示点击与开始答题；后台为确切的实时人数。
  链接检查（`BroadcastRules.hasAddress`，评审 M3）先做 NFKC、去掉不可见字符、把 `[.]`、` dot `、`。` 等写法还原为点，再按形式（任意 `label.label` 加两个以上字母的顶级域、IDN、IPv4、`LINE ID`、`@handle`、十位以上的数字）判断，不靠域名清单。
  撤回记录只记谁撤回的种类（`SENDER` / `ADMIN` / `SYSTEM`，评审 L3），是哪位管理员看审计；撤回法律文本的预告版本时同时撤回它的公告（`sourceKey` = `legal:<版本>`，评审 L2，锁序 `qb.legal` → `qb.broadcast`）。
  规则只写在纯 Java 的 `BroadcastRules`（上限、长度、链接、时刻），保存即给出发送会遇到的全部问题；商家通知只给本人进行中的发布、纯文本无链接、条数与间隔是普通参数；不预审，管理员撤回（理由商家可见），封禁商家时 `QB_USER_BAN` 以子流程 `QB_BROADCAST_BAN_WITHDRAW` 撤回。
  锁的顺序：`qb.account:<商家>` → `qb.broadcast:<编号>`；已读另取 `qb.inbox:<用户>`。任何统计都不给出是谁。推广只能退订（`QbInboxState.promoOff`，Q7-11）。
- **AI 出题**（`content.ai`，`docs/quizbuks/plans/Q8-ai.md` Q8a）：提供方是 Claude（Anthropic API，A-1），外部服务 `anthropic`，只经平台的 JDK HTTP 与手写的 `ClaudeApi`（不用 SDK）；全部调用都是异步外部作业 `qb.ai.questions`，处理器不访问数据库，作业、流程与页面只认 `QuestionWriter`（换提供方只改它的实现与外部服务）。
  **候选从不自动入库**：`READY` 的候选只在 `QbAiJob.candidates`，7 天后由 `qb.ai-expire` 从作业中移除、不能再加入（提供方的原始回答仍在平台只追加的作业结果中，`candidates` 列 `auditMasked`）；只有 `QB_AI_ACCEPT` 把商家校对后的内容（每题 `reviewed = true`，缺即 400）按内容规则（`AiCandidates`，与版本的规则与列长相同）写成题目，题目记 `aiJobId`、模版 `aiGenerated = true`。
  **只发送商家自己的模版内容**（标题、简介、所选资料：文章发正文，其他种类只发标题与描述；已有题干），以 JSON 数据放在 `<quiz_data>` 中（`<` 转义），系统提示固定；不发送答题人的任何数据与商家的身份信息，`metadata.user_id` 是 `AiUserKeys`（HMAC，密钥由 `JABIZ_INTEGRITY_KEY` 以 `qb.ai-user` 派生）。输入 ≤ 250 KiB（`ClaudeApi.MAX_INPUT_BYTES`，Q8b 起；Q8a 为 60 KiB）：超出即 422 `QB_AI_INPUT_TOO_LARGE`，从不截断。
  **同意**：新的法律文本种类 `AI_NOTICE`（不在 `SPONSOR_KINDS` 中：登录后的同意门不问它）；请求时没有已生效的版本即 `QB_AI_NOTICE_MISSING`，须同意而请求没带当前版本号即 `QB_AI_NOTICE_REQUIRED`，带了即记同意（来源 `AI_REQUEST`）。
  **限额在 `qb.ai:<商家>` → `qb.ai:*` 锁内**（`AiLimits`）：同时未结束 ≤ 2（取消的在提供方结束前仍算）、每模版 1 个、每日次数、每月 Token（已记录的用量 + 未结束作业的预估）、全体每月 Token（全体行 + 所有商家未结束作业的预估，`qb.system.ai-open`）。**预估覆盖全部 3 次尝试**，每次是完整的输入与所要的全部输出（`ClaudeApi.maxTokens`：随题数，至多 16 000；effort `high` 时一次至多 10 题，`QB_AI_COUNT_TOO_HIGH`）；**没有得到回答的尝试按预估计入**（`ClaudeApi.unanswered`：作业为一次尝试预留的输入 `reservedInputTokens`（带文档的请求为提供方的计数）加 `maxTokens`），**没有记下用量的尝试**（实例死掉、租约过期，平台的 `unrecordedAttempts`，D52 第 5 条）在完成、失败与取消的流程中按同一依据计入（`AiProcesses.charged`：取自作业保存的参数，美元经 `AiCost`），不再有计为零的尝试。用量 `QbAiUsage` 只由完成、失败与取消（两种作业类型的 `onCancelled` `QB_AI_JOB_CANCELLED`，平台 D52 第 2 条：按平台记下的实际用量，没有尝试运行过的不计费；取消不计为失败）的流程在 `qb.ai:<商家>` → `qb.ai:*` 内累加（重试与拒答也计；不另订阅 `jabiz.external-job.finished`，否则重复计费），美元估算 `costUsd` 按各模型的标价（`AiCost`，回退模型的部分按回退模型）。
  **作业处理器读不到 `qb.ai.enabled`**（不访问数据库）：两种作业类型声明 `.enabledBy(QbParams.AI_ENABLED)`（平台 16v-2，D52 第 4 条；键在 `QbParams.CONTROLLED` 中），开关不是 `true` 时平台不领取它们的作业（排队与等待重试的等待、不用掉尝试，正在运行的尝试照常结束），请求流程另以 `QB_AI_UNAVAILABLE` 拒绝新请求；处理器中不另写开关。事故时按快慢：Anthropic Console 吊销密钥 → 四眼关闭 `qb.ai.enabled` → 平台的 `EXTERNAL_JOB_CANCEL` 逐个取消（`operations.md` §7.8）。新的 AI 作业类型同样声明 `enabledBy`；参数缺失（新安装发布 `QB_SETUP` 的提案之前）时启动检查 `EXTERNAL` 警告，这是预期的。商家的取消 `QB_AI_CANCEL` 以一个平台步骤 `CancelExternalJob.of(List.of(AiQuestionsJob.TYPE, AiCoverJob.TYPE), …).ifOpen()`（平台 16w-1，一个步骤声明两种类型；缺省只取消本人开始的作业，商家即请求人）在同一事务中取消平台的作业；本地作业的 `externalJobId` 即平台的作业号（唯一）。商家的两个数据视图只供模板限定范围，本身不可读（读权限 `qb.ai.system`）：新的商家可见列写进模板，不开放数据视图。**锁序** `qb.quiz:<模版>` → `qb.ai:<商家>` → `qb.ai:*`，不与发布、开始、钱包锁同时持有；答题路径不受影响。
  **封面与 PDF 资料**（Q8b）：作业 `qb.ai.cover`（作业、流程与页面只认 `CoverDesigner`）：Claude 只从固定图库（`CoverDesigns`：配色、版式、图形与 ≤ 40 字的文字）选设计、不画图，服务器以 `CoverRenderer`（Java2D，字体为平台的 Noto Sans 与 `jabiz.reports.pdf.fonts`，画不出的字不画；所请求语言的样例文字（`AiCoverHandler.SAMPLES`，en / ja / zh）或标题画不出即 `QB_AI_COVER_FONT` 且不调用）画成 1200 × 630 的 PNG，经平台的文件输出（D50）以商家为上传者按 `qb.content.image` 登记；候选图在 `READY` 期间由 `QbAiJob.cover1..3` 引用（清扫因此保留），选定只经 `QB_AI_COVER_ACCEPT`（模版锁 → AI 锁，经 `QuizEditing.touch` 写 `cover` 与 `aiGenerated`），作业关闭（选定、丢弃、过期）即清空三列，未选的图由平台清扫删除，应用不删文件。
  PDF 资料作为作业的文件输入（`StartExternalJob…files`，平台按读取权限检查）以文档块发送原文件，不进作业输入；每个与合计的大小在请求时查（`QB_AI_PDF_TOO_LARGE`）；**服务器不解析 PDF**（Q8b 评审 H1/H2：解析器可被构造的文件耗尽内存，页数可以谎报；不引入 PDF 库，ArchUnit `quizbuksParsesNoPdf`），请求按页数上限预留月 Token（`AiLimits.reservedInput` / `estimate(…, pdfPages)`，每页 `ClaudeApi.TOKENS_PER_PDF_PAGE`，预留记在作业输入与参数 `reservedInputTokens` 中），处理器在付费调用前以提供方免费的 `count_tokens` 数完全相同的请求（`ClaudeCalls.counted`）：超过预留即 `QB_AI_PDF_TOO_LONG`，提供方说请求过长（上下文或页数）同样、说 PDF 读不了即 `QB_AI_PDF_UNREADABLE`（都永久、不计费；以字段路径开头等其他 400 一律 `QB_AI_REQUEST_INVALID`，`ClaudeErrors`），计数失败照常重试（用掉一次尝试）；没有回答的尝试按这个数计入。**带 PDF 的请求不带提供方的回退**（复审 M-a），付费调用因此正好是计过数的请求。页数上限须放得进上下文窗口与提供方的页数（启动检查 `AiPdfLimitsCheck`）。本地桩同样实现 `count_tokens`。作业输入 ≤ 256 KiB（`AiQuestionsJob.MAX_JOB_INPUT_BYTES`，文字 ≤ `ClaudeApi.MAX_INPUT_BYTES` = 250 KiB）。题目与封面共用限额，每模版每种类同时一个；处理器只经上下文读写文件（ArchUnit `handlersReadFilesThroughTheirContext`）。
  参数 `qb.ai.enabled`（缺省 false）、`qb.ai.model`（只能是 `claude-opus-5-5` / `claude-sonnet-5-5` / `claude-haiku-5-5`：参数的种类与每次请求都查）、`qb.ai.effort`、三个限额都是**受控参数**；任何一个缺少或不合格即 `QB_AI_UNAVAILABLE`。作业的完成、失败与过期流程 `internal()`，权限 `qb.ai.system` 不授予任何角色；后台的 `qb.admin.ai.read` 给内容与超级管理员。`dev`、测试与 e2e 的提供方是本地桩（`tools/quizbuks/claude-stub`、IT 的 `AiItSupport`），从不访问网络。
- **外部密钥**（`STRIPE_*`、`ANTHROPIC_API_KEY`）只来自环境变量（`deploy/quizbuks/README.md`）；测试与 `dev` 用固定的假值，仓库中没有真实密钥。
- **部署的安全设置**（Q10b，`deploy/quizbuks/README.md`"密钥与部署"）：镜像入口脚本在平台密钥或首个管理员密码缺少时拒绝启动（只有 `QB_LOCAL_SECRETS=true` 的本机才生成，compose 缺省 `false`，正式模式一律不生成，管理员密码不打印；生成时导出 `QB_SECRETS_GENERATED=true`，评审 L5）；
  启动检查 `wallet.DeploySafetyCheck`（类别 `QUIZBUKS`）：可信代理为空时正式模式报错（除非 `quizbuks.deploy.direct-exposure=true`）、过宽的网段一律报错（IPv4 短于 /8、IPv6 短于 /32、整个 IPv4 的映射，前缀照平台读法，评审 L6），正式模式下关闭管理二次验证或 `QB_SECRETS_GENERATED=true` 报错，`dev` 只在回环地址与本机邮件地址下启动；
  数据库密码没有缺省值。职责分离规则由 `QB_SETUP` 提出（`setup.QbSodRules`），`QbRoles` 的角色不得违反它们（`QbRolesTest`）；超级管理员不做业务审批（不持有 `approval.decide`）。
  **受保护的授权**（平台 16u，D51）：管理员按 `security.user-role.write` / `security.role.write` 计，即超级管理员；两位之后建立期结束，受保护的变更由一位超级管理员申请、另一位以 `security.protected-change.decide` 审批（`docs/quizbuks/operations.md` §3）。
  `QB_SETUP` 直接写入角色的权限，建立期结束后给已有持有人的角色加受保护的权限会被拒（先经 `SEC_ROLE_PERMISSION_ADD` 审批）；测试以 `AccountItSupport.assignRole` 照此分配（第二位超级管理员之后由另一位批准）。

## 5. 前端（`quizbuks-web/`，`docs/quizbuks/plans/QP-pages.md`）

- **两个 SPA，各是独立的 pnpm 工程**（自己的 `package.json` 与 `pnpm-lock.yaml`，经 `link:../../frontend/packages/{client,ui}` 用平台的包；不用 pnpm 工作区）：
  `app`（答题 App，`/`，入口 `app`，PWA）与 `sponsor`（商家后台，`/sponsor/`，入口 `sponsor`）；`build.gradle.kts` 的 `spa(...)` 构建它们。`application.yml` 写出完整的 `jabiz.web.spa`（Q19：App 自己的内容安全策略多 `frame-src` 两个视频播放器），三个前端的路径、入口与 PWA 须与 `build.gradle.kts` 一致（启动检查 `WEB` 告警）。
  共享依赖的版本必须与平台前端相同（`pnpm app:check`，Gradle 中 `spaCheckRoot` / `spaCheckSponsor`，在 `check` 中）。指南：平台 `docs/guide/app-spa.md`。
- **品牌档案**（`docs/quizbuks/plans/Q54-brand-profile.md`）：名称、标志、图标、配色只在品牌档案 `quizbuks-web/brands/<品牌>/` 中（构建时以 `QB_BRAND` 选择，缺省 `quizbuks`）；页面与消息以该语言的名字 `BRAND.names.<语言>`（Q61，`docs/quizbuks/plans/Q61-local-names.md`；SPA 的 `src/qb/brand.ts`、Shopify 内嵌页的 `src/brand.ts`、静态站的 `src/brand.mjs`）称呼产品，`BRAND.name`（QuizBuks）只用于只能有一个名字的地方（PWA 清单与安装名、发件人、版权行），不写死名称（`brand-profile.test.ts` 与 `brands/brand.test.mjs` 检查，含中文、日文名），静态站的品牌色在档案的 `site.css`；后端消息以记号 `@QB_BRAND_NAME@` 称呼产品（`processResources` 按文件的语言替换），客服邮箱与站点地址的缺省取 `setup.Brand`；管理后台扩展以 `BRAND_NAMES.<语言>`（`src/brand.ts`，档案经 `brands/admin.mjs` 生成的 `brands.generated.ts`，改档案后重新生成）；新品牌复制 `brands/sample`（Q54 计划 §7）。
- **品牌与插画**（`docs/quizbuks/plans/Q32-visual-refresh.md`）：颜色只在品牌档案的 `brand.css` 中以覆盖 token 表达（`--primary` 等与品牌 token `--qb-*`，亮暗两套，对比度按平台 12 §11，`brand.test.ts` 检查每一份档案），页面只用 token 与 `qb-*` 颜色类，不写死色值；
  插画是共用的 `src/qb/Illustrations.tsx` 的内联 SVG（`aria-hidden`，颜色取 token），不引入外部图片或照片；没有封面的发布用 `CoverPlaceholder`。欢迎轮播（`pages/IntroPage.tsx`）只在未登录首次打开 `/` 时显示，`localStorage` 记住。
- **共用代码只在 `src/qb/`**，两个 SPA 中各一份、**完全相同**（CI `diff -r`）：改一处即复制到另一处。流程、模板、数据视图、字典与文件策略的名字只写在 `qb/ids.ts`；
  流程的输入输出对照 Java record 手写在 `qb/api.ts`（答题的在 `qb/play.ts`；QuizBuks 没有自己的接口，因此没有 OpenAPI 类型），改了 record 就改这里并由 e2e 覆盖。
  **只有一个 SPA 用到的功能**（商家后台的内容工坊）在该 SPA 自己的功能目录中照此各写一份：名字在 `<功能>/ids.ts`、手写类型与调用在 `<功能>/api.ts`（如 `sponsor/src/content/`），不进 `qb/`。
- **内容工坊**（`sponsor/src/content/`，QP3a）：每次编辑带上页面所见的 `revision` 作 `baseRevision`；`QB_QUIZ_CHANGED` / `QB_ORDER_MISMATCH` 或读到别人的新 revision 即提示重新加载，
  重新加载之前不再提交。上传只经 `uploadFile(内容策略)`（`qb.content.image|pdf|audio`），文件名用中性的 `<种类>.<扩展名>`（`content/api.ts` 的 `neutralName`，Q10b：答题人能读内容文件的元数据；商家头像同样以 `avatar.<扩展名>` 上传，内容与头像的策略都声明 `privateMetadata()`），页面的类型与大小检查只为省一次往返；图片按文件自己的变体显示（小图没有变体，`ContentImage`）。
  Markdown 只经 `MarkdownView`，外链只放 http(s) 并在新标签页打开。e2e 的数据准备（文件、完整的 Quiz 与版本）在 `e2e/tests/content.ts`，发布的测试从它开始。
- **发布页**（`sponsor/src/publishing/`，QP3b）：向导每一步都以 `QB_PUBLICATION_SAVE` 保存**整个**草稿（档位给出即完整列表，带上次保存得到的 `tierId`；带 `baseRevision`），
  提交前的问题与预计最大支出**只取保存的输出**（`problems`、`maxSpend`），页面不重算规则；`QB_PUBLICATION_CHANGED` / `QB_PUBLICATION_NOT_DRAFT` 即提示重新加载。
  非草稿只显示、不编辑（提交即冻结），操作按状态给出且都先确认。e2e 的数据准备（已通过的商家、审核的决定、上架的发布）在 `e2e/tests/publishing.ts`。
- **答题页**（App，QP2）：倒计时只按服务器时间（`deadline` 与 `serverTime` 的偏移，`play/countdown.ts`）；保存进度只经 `play/saver.ts`（防抖、一次一个、失败保留重发）；
  结果页一律读 `QB_ATTEMPT_RESULT`（Q20：已完成会话的题目按会话顺序、所选与正确选项都来自服务端，历史、其他设备、关闭标签页后一样显示）；`sessionStorage` 只留开始的输出供答题页接续与提交输出中"奖励待付"的提示（本标签页），退出登录时清除。
  开始与提交只经 `play/busy.ts` 的 `retryWhenBusy`：503 `PROCESS_BUSY` 时显示"人多，正在重试"，按 `Retry-After` 与加倍的退避自动重试，**每次开始、每次提交各一个 `Idempotency-Key`，重试用同一个**；
  提交带上会话的 `publicationId`（服务端排队的键）。重试在页面离开时停止（`signal`，开始的晚到成功不再把人带回答题页），
  等待合计至多 60 秒，提交过了截止加宽限（5 秒）即不再重试、去结果页（服务端以已保存的答案结束）；提交前先经 `saver.settle()` 保存未存的答案（保存不排队），
  截止前 2 秒再保存一次：排队使提交晚于宽限而 EXPIRED 时，超时任务以全部已保存的答案结束（只差截止前最后不到一秒的改动）。
- **支付页**（QP4，`sponsor/src/wallet/`、`app/src/wallet/`）：页面只读记录、从不入账；Stripe 的跳回（`?checkout=`、`?onboarding=`）只决定显示，结果一律等服务端的记录。
  轮询只经 `wallet/poll.ts`（有界、退避、离开即停）；跳往 Checkout 或开通页只经 `wallet/stripePage.ts` 检查过的地址（https，或页面在本机时的本机桩）；
  刚发起的充值只自动跳转一次，其他时候给按钮；开通只跟随本次请求生成的新链接（Stripe 的链接只能用一次）。
- **收据**（Q9d，`sponsor/src/wallet/`）：钱包页已到账的充值行有"收据"：`QB_TOPUP_RECEIPT_ISSUE`（当前界面语言，只在第一次签发时起作用）后下载存档的 PDF（`GET /api/documents/runs/{id}/pdf`）。
- **分析页**（Q9e2，`sponsor/src/analytics/`，名字在 `ids.ts`、手写类型与调用在 `api.ts`）：页面只显示模板给出的值（不自己汇总、不判断 k、不算流失最大的一段），不足 k 时照模板的 `suppressed` 注明"至少 k 人作答后显示"；
  日期与时刻一律按任务记下的部署时区（`qb.sponsor.dashboard` 的 `zone`）显示，不按浏览器；"现在"取 `qb/legal.ts` 的 `minuteNow()`（按服务端时钟）。图表只经 `@jabiz/ui` 的 `ChartContainer`、颜色只用 `--chart-*`，每个图表有 `aria-label` 与合计的文字，并有可展开的数据表（`charts.tsx` 的 `DataDisclosure`）；
  两种量不共用一张图（上下两个小图）。导出只经平台的 `POST /api/queries/{id}/export`（趋势带页面所示期间的同一组参数）。e2e 以 `QB_ANALYTICS_RUN_CRON` 每分钟运行分析任务。**分析任务的时间是隐私控制**（概览是任务的快照，频率决定能把一个答题人的作答定位到多细，评审 M1）：
  缺省以外的值只在 e2e 的开关 `quizbuks.e2e.enabled`（`QB_E2E_ENABLED`，正式模式下拒绝）打开时接受，比每分钟更频繁的一律拒绝（`TransferScheduleCheck`）。
  合计（趋势的总数）由模板给出（`qb.sponsor.analytics.trend-totals`），页面不自己加；时刻只在知道部署时区（模板的 `zone`）之后显示，并注明时区。
- **交易页**（Q9b，`sponsor/src/transactions/`）：列表、汇总与导出用同一组模板参数（`transactions/api.ts` 的 `paramsOf`；筛选写成模板参数而不是列表筛选，汇总模板才能用同样的条件），
  导出只经平台的 `POST /api/queries/{id}/export`（`sessionFetch`，CSV 与 XLSX）。答题人只以资料的显示名出现，不给邮箱，也不给用户编号："只看此用户"按模板给出的 `userKey` 筛选，页面不显示它。`userKey` = md5(商家编号 : 用户编号)：不是用户编号，同一答题人在每个商家处的键不同；它不是秘密，知道两个编号的人都能算出。
- **条款、帮助与关于**（Q7b，共用的在 `qb/legal.ts`、`qb/LegalPage.tsx`、`qb/HelpPage.tsx`、`qb/ConsentGate.tsx`、`qb/TermsAgreement.tsx`、`qb/LegalLinks.tsx`）：`/help`、`/legal/<terms|sponsor-terms|privacy|commerce>`（App 另有 `/about`）不需登录，经 `qb/legal.ts` 的 `publicQuery`（无令牌）读公开模板，时刻取整到分钟；
  法律文本按界面语言显示、日文为准（其他语言注明并可切换日文，中文缺失时显示英文并注明，`lang` 属性），Markdown 只经 `MarkdownView`。`qb.me.consents` 按服务端的时间读（`qb/legal.ts` 由其应答的 `Date` 头得知设备时钟的偏差并改正，不按设备时钟判断是否已生效）；欢迎页与入驻页以 `TermsAgreement` 同意它给出的当前版本并发送版本号（`QB_TERMS_OUTDATED` 即重读）；
  `ConsentGate` 在资料完整（App）或已申请（商家）且有须同意的版本时整页替换页面（未验证邮箱只显示提示条），帮助与条款页不受它拦；它只是页面，服务端照样拦资金与合约类写入。
  任何调用收到 `QB_CONSENT_REQUIRED` 即重读同意状况、提示随之出现：两个 SPA 的 QueryClient 只用 `newQbQueryClient()`（查询与变更出错时都检查），不经 React Query 的调用自己调 `noticeConsentProblem`。
- **收件箱与消息中心**（Q7c，App `src/inbox/`、商家后台 `src/notices/`）：未读徽标经 `useUnread`（进入、回到前台、每 5 分钟重读，离开即停）；打开即 `QB_MESSAGE_MARK`（已读），按钮另记点击；
  收件箱的模板不收页面的时间（服务端的现在）；徽标的查询不随窗口获得焦点再问一次。商家通知的编辑器只显示保存给出的问题，不重算规则，只有一个可选发布时预先选中它。
- **AI 出题页**（Q8a，`sponsor/src/ai/`，名字在 `ids.ts`、手写类型与调用在 `api.ts`）：编辑器题目页上方的面板与对话框、候选校对、仪表盘的用量卡（`AiUsageMetric`，在 Q9e2 的卡片位）；Q8b 的封面面板（`AiCoverPanel`，模版页基本信息之下，作业模板按 `kind` 读）选定后经 `editing.edited` 记下新的 revision，`HeadingForm` 随读到的 `cover` 更新（其他未保存的改动保留）。候选只以纯文本显示（输入框与文本框，不经 `MarkdownView`）；进行中每 5 秒查询、至多 5 分钟（按服务端时钟），离开即停；对话框只在合计不超过上限时可提交，判断仍在服务端。
- **认证与账户只用平台的积木**（`@jabiz/ui` 的 `SignInForm`、`SignUpForm`、`MailLinkPage`、`OidcCallback`、`StepUpProvider`、`ChangePasswordForm`、`MfaSettings`、`SessionList`、
  `LinkedIdentities`、横幅），QuizBuks 不写认证代码。邮件链接页在各自的 `auth/verify-email|join|password-reset`。路由是 `qb/router.tsx` 的小路由。
- **金额只经 `qb/kudos.ts` 的 `formatKudos`**（`1,234 Kudos` / `1,234 学分` / `1,234 クドス`，随界面语言；单位单独出现时用 `kudosName()`），不写 JPY 或其他币种名。服务端的 `QB_*` 文案已按请求的语言翻译，页面直接显示（`qb/problems.ts`）。
- **文案**：SPA 自己的在 `src/messages.ts`（命名空间 `app` / `sponsor`），共用的在 `qb/messages.ts`（`qb`），三语齐全，测试检查键一致且非空。
- **界面**只用 `@jabiz/ui` 的组件与主题 token（不写颜色值）；亮、暗两种外观都过 axe（e2e `a11y.spec.ts`）。检查只用平台导出的辅助函数（Vitest `@jabiz/ui/testing` 的 `expectAccessible()`，e2e `@jabiz/ui/testing/playwright`，经 `e2e/tests/support.ts`），不另写；
  后台扩展的测试用 `@jabiz/admin/testing` 的 `expectAccessible()`（平台 16w-1；只在测试中，扩展的 lint 拒绝页面中引用它与 `@jabiz/ui*`）。App 按 ≤ 430 px 设计，也检查 1280 px。
- **检查**（每个 SPA 目录下）：`pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test`；平台前端目录下 `pnpm app:check ../quizbuks-web/app`（`sponsor` 同）。
- **后台扩展**（`backend/quizbuks/admin-extension`，Q9a，平台 12 §9 与决策 D22）：只经 `@jabiz/admin` 引用平台，没有 `package.json`；路由在 `/qb/**`；名字在 `ids.ts`，
  手写类型与读取在 `api.ts`（快照类型 `snapshot.ts` 抄自商家后台，随 `QuizSnapshots` 一起改）。只读默认数据视图与 `qb.admin.*` 模板，决定只经平台的 `ApprovalPanel`；
  **公告与通知页**（Q7c，`/qb/broadcasts`，`broadcasts-api.ts`、`broadcasts-messages.ts`）：保存后才可发送且只发送未再修改的已保存草稿；撤回填理由；
  **规约类页**（Q7b，`/qb/legal`，`legal-api.ts`）预览各种法律文本的草稿，以读到的草稿版本运行 `QB_LEGAL_PUBLISH`，并在版本列表（`qb.admin.legal-versions`）中对未生效的预告版本填理由运行 `QB_LEGAL_WITHDRAW`；页面上的状态只是显示，生效与否由服务端判断；
  发布的决定面板只在发布、档位与版本（审批绑定的三者）都为当前 `approvalRequestId` 读好、无错误、不在重读时给出。资金部分只在 `qb.admin.finance.read` 时显示与请求，
  内容管理员读的模板也不带金额（金额在 `qb.admin.publication-funds`、`qb.admin.attempt-rewards` 等财务模板中）；列表按页读取；金额按 JPY（`formatAmount`），用户编号用 `UserName`。
  **财务页**（Q9c，`/qb/finance/**`，菜单"QuizBuks 财务"，`docs/quizbuks/plans/Q9c-admin-finance.md`）：名字与读取在 `finance-api.ts`、非组件的辅助（地址中的筛选、分组、报错）在 `finance.ts`、文案在 `finance-messages.ts`。
  列表的筛选写在地址中（总览与核对页链到筛选后的列表），只认编号、状态与日期；大额转账的决定面板只在转账与收款人的全部读取都为当前 `approvalRequestId` 读好、无错误、不在重读时给出（同发布审核）。
  月报只经平台：模板 `qb.admin.monthly` / `qb.admin.balances`（`from`、`to` 为 UTC 的月初与下月初，`timeSlice` 只映射 `knownAt`）、导出 `POST /api/queries/{id}/export`、签发 `REPORT_ISSUE`（同一期间再签发即取代该期最近的一次）、存档 `GET /api/reports/runs`；月份在地址中（`?month=`）；页面不自己汇总或保存报表。核对差异只读（C-3）。
  核对结果与资金待办只给 `qb.reconcile`（模板 `qb.admin.recon-summary`，另要 `task.read`），不放进只要 `qb.admin.finance.read` 的模板。
  **分录导出页**（Q9d，`/qb/finance/journal`、`/qb/finance/journal/mapping`）：名字与读取在 `journal-api.ts`；页面只显示预览给出的行与问题、从不自己汇总或编号，导出只在预览 `ready` 时给出并先确认；
  下载只经 `GET /api/generated-files/{id}`（有 `file.generated.read` 时给出）；科目映射页只读，修改链到平台的受控变更（提出、发布）。
  **AI 页**（Q8a，`/qb/ai`，`ai-api.ts`、`ai-messages.ts`）：AI 参数的现行值、按商家与月份的用量与估算、最近的请求（不含候选）；开关只经受控变更提出（另一位管理员发布）。
  检查：平台前端目录下 `JABIZ_ADMIN_EXTENSION=../backend/quizbuks/admin-extension pnpm ext:check`（CI `web`）。
- **端到端**（`quizbuks-web/e2e`，Playwright）：对打包的 jar（`./gradlew :quizbuks:bootJar`），邮件由 Mailpit 接收（`E2E_MAILPIT_URL`），Stripe 由本地桩回答；
  服务端以 `JABIZ_SECURITY_MFA_ADMINISTRATION=false`、放宽的登录限流 `JABIZ_SECURITY_SIGN_IN_RATE_LIMIT_PER_USER_ADDRESS=6000` 与 `JABIZ_SECURITY_SIGN_IN_RATE_LIMIT_USER_BURST=1000`
  （平台 16q：共用的管理员从本机登录数十次；只给 e2e，生产保持缺省）与含 `QB_E2E_PUBLISHER`、`QB_E2E_FINANCE` 的 `QB_ADMIN_ROLES` 启动（测试自己建一个只有 `control.publish` 的角色，发布 `QB_SETUP` 的提案；
  Q9d 的导出要已验证的邮箱，首个管理员没有，测试另建一个已验证的用户并给它不要求二次验证的测试角色 `QB_E2E_FINANCE`），并以 `QB_RECONCILE_RUN_CRON='30 * * * * *'` 每分钟核对（导出等月末之后的一次干净的核对），
  并以 `QB_TRANSFER_RUN_CRON='0 * * * * *'` 每分钟运行每日转账（Q9c：财务的测试打开转账、把审核线设为 1,000 并等待大额转账进入审批；其他测试的奖励都低于转账门槛 1,000）。
  这个变量只给 e2e：正式模式或正式密钥下设成每日以外的值即启动失败（`wallet.TransferScheduleCheck`，同开发入账）。
  **条款**（Q7b）：`prepareQuizbuks` 在没有已生效的三种文本时发布第一版（同上线前），发布人是测试自己建的、带已验证邮箱与测试角色 `QB_E2E_CONTENT`（`QB_ADMIN_ROLES` 中同样加入）、经 API 绑定 TOTP 并 step-up 的内容管理员（`tests/legal.ts`）；
  `support.ts` 的 `run` 像页面一样替 `QB_PROFILE_SAVE` / `QB_SPONSOR_APPLY` 带上当前版本，并在充值、提交发布、开通收款之前先接受到期的新版本（不测同意的测试因此不受其他测试发布的新版影响）；经页面完成欢迎页的测试勾选 `profile-terms`。
  **站内信与公告**（Q7c）：后台扩展的写入要已验证的邮箱（首个管理员没有），测试以 `contentAdmin`（`tests/legal.ts`，角色 `QB_E2E_CONTENT`、不绑定二次验证）登录后台；每次要求二次验证的撤回经 API 以 `legalAdmin` 运行。e2e 的公告用推广（只到发送前注册的人），不让以后测试新建的答题人收到。
  启动方式与环境变量照 `.github/workflows/quizbuks.yml` 的 `e2e` 作业；然后 `E2E_BASE_URL=… E2E_ADMIN_USER=… E2E_ADMIN_PASSWORD=… pnpm e2e`。测试只增数据，可重复运行。

- **商店 App 的外壳**（Q49，`quizbuks-mobile/`，`docs/quizbuks/plans/Q49b-android.md`）：只上架答题者的 App（`/taker/`），商家后台与充值不进 App。
  Android 是 TWA（`quizbuks-mobile/android`，设置只在 `twa-manifest.json`，构建由它读取；App Links 只认 `/taker/` 前缀），启动地址与 App Links 都带 `shell=android`；
  App 中只以 `src/shell.ts` 的 `nativeShell()` 判断是否在商店 App 中（参数或 Chrome 的 `android-app://` referrer，按标签页记住），在外壳中不显示任何安装提示（`install.tsx`）。
  `assetlinks.json` 暂由 Caddy 站点提供（`deploy/quizbuks/assetlinks.sh`，环境变量 `QUIZBUKS_ANDROID_PACKAGE` / `QUIZBUKS_ANDROID_CERT_SHA256`），平台 17l 合入后改用平台的。
  上传密钥与 Play 服务账号只在 GitHub Environment `play` 的 secrets 中（workflow `quizbuks-mobile.yml`）。改了 App 的前缀、包名或颜色时两边一起改（`scripts/consistency.test.mjs`）。
  iOS 是 Capacitor（`quizbuks-mobile/ios`，设置只在 `capacitor.config.json`，Swift 的 `QuizBuksScope` 让网页只停在 `/taker/`、其他地址交给 Safari；User-Agent 带 `QuizBuksApp/ios`），`shell.ts` 同样认它；`apple-app-site-association` 暂由 Caddy 提供（`deploy/quizbuks/apple-app-site-association.sh`，变量 `QUIZBUKS_IOS_APP_ID`）；签名只在 workflow `quizbuks-ios.yml` 中以 App Store Connect API 密钥进行（Environment `appstore`）。
  商店资料（Q49d，`docs/quizbuks/store/`）：说明文字三语同构并由 `tools/quizbuks/store/listing.test.mjs` 检查；数据安全表按隐私政策逐格写，政策或 App 收集的数据改了就同步；截图只由 `quizbuks-web/e2e/store`（workflow `quizbuks-store.yml`）生成，不提交。

## 6. 测试与命令（在 `backend/` 下）

- `./gradlew :quizbuks:check`（测试 + `platformCheck`）；单个类：`./gradlew :quizbuks:test --tests '*SetupIT'`。
- 集成测试连接真实 PostgreSQL（同平台：Testcontainers 或 `JABIZ_TEST_DB_*`），测试类放在 `com.jabiz.quizbuks.*` 下。
- 场景回放：`src/test/resources/scenarios/qb/**.yml`，`./gradlew :quizbuks:test --tests '*ScenarioTest'`；确认变化正确后加 `-Dscenario.update-snapshots=true`。
- 性能（不在 `check` 中）：进程内基线 `./gradlew :quizbuks:perfTest`（`PlayThroughputIT`；分析任务 `--tests '*AnalyticsPerfIT'`，`docs/quizbuks/perf/Q9e-analytics.md`）；对运行中的 jar 压测单个热门发布 `./gradlew :quizbuks:loadTest`（参数 `LOAD_*`，含客户端时限 `LOAD_TIMEOUT` 与照 App 重试忙的 `LOAD_RETRY_BUSY`；方法与结果见 `docs/quizbuks/perf/Q10a-load-test.md`，准入之后的重测在其 §8、16p-1 之后 §9、锁拆分之后 §10、16p-2 之后 §11）。答题锁内的时间取决于语句条数（每条约 0.8 ms），改答题流程时不要在开始锁或发布锁内增加读取；语句数的确切值在 `AttemptStatementBudgetIT`。
  `LOAD_METRICS=yes` 时从只绑定回环地址的独立管理端口读取指标（jar 以 `-Dmanagement.server.address=127.0.0.1 -Dmanagement.server.port=8081 -Dmanagement.endpoints.web.exposure.include=health,info,metrics` 启动，工具读 `LOAD_METRICS_URL`，缺省 `http://127.0.0.1:8081`，不是回环地址即拒绝，`LOAD_ALLOW_REMOTE` 也不放行；指标绝不在应用端口上暴露：那里不要认证，根 CLAUDE.md 与设计 13 不允许匿名的指标端点；也不写进仓库的配置），报告每次完成的许可持有时间与每次运行的语句数（`jabiz.process.statements`），并报告压测期间本机的 CPU；16p-1 之后的重测在报告 §9。

## 7. 运行

- 本地：`QB_LOCAL_SECRETS=true docker compose -f deploy/quizbuks/compose.dev.yaml up -d --build`（compose 缺省不生成密钥，Q10b L5；数据库端口 5440、应用 8080：App 在 `/taker/`、商家后台在 `/sponsor/`、后台在 `/admin/`、招商站在 `/biz/`，裸域名转到 `/biz/`）；
  或只起数据库（`… up -d db`）后在 `backend/` 下 `./gradlew :quizbuks:bootRun --args='--spring.profiles.active=dev'`，前端开发时在 `quizbuks-web/app` 或 `sponsor` 下 `pnpm dev`
  （端口 5175 / 5176，`/api` 代理到 8080）。
  compose 没有 `.env` 时 Stripe 未配置：镜像的入口脚本 `deploy/quizbuks/entrypoint.sh`（再交给平台的 `docker/app-entrypoint.sh`，不改平台脚本）只在测试模式、
  `QB_STRIPE_PLACEHOLDERS=true`（compose 缺省）时为未设置的 Stripe 密钥填占位并告警；改它时同步改 `tools/quizbuks/test/entrypoint.test.sh`（CI `deploy`）。
- 首次启动后，管理员登录 `/admin/` 执行一次 `QB_SETUP`（流程页面）。
- **服务器部署**（共用的 Docker 服务器，Caddy 为唯一入口，共用的 PostgreSQL）：`deploy/quizbuks/DEPLOY.md`。两个环境：推送 `1.3/quizbuks` 部署 **staging**（`APP_NAME=quizbuks-staging`、`staging.<基础域名>`、库 `quizbuks_staging`），
  推送 `1.3/quizbuks-release`（只由 `1.3/quizbuks` 合并进来）部署 **production**（`APP_NAME=quizbuks`、基础域名 `vars.QUIZBUKS_BASE_DOMAIN`、库 `quizbuks`）；`.github/workflows/quizbuks-deploy.yml` 构建镜像 `ghcr.io/<仓库>-quizbuks`（不再测试：合并前 PR 已检查，`quizbuks.yml` 与平台的 `ci.yml` 只对 PR 运行）、按分支（GitHub Environment `production` / `staging`）渲染站点并以服务器的 `deploy-app.sh <APP_NAME> <SHA>` 部署。
  服务器用的只有 `deploy/quizbuks/server/compose.yaml`（项目名与容器名只取 `.env` 的 `${APP_NAME}`，不写死；无端口、无数据库、外部网络 `proxy` 与 `db`）、模板 `site.caddy.tpl`（`__DOMAIN__`、`__APP_NAME__`、`__APP_DIR__`）与维护页 `maintenance.html`（维护模式只以服务器上的锁文件开关，DEPLOY.md）；环境的差异只在服务器上各自的 `.env` 中，应用配置中不写按环境的域名或密钥；`compose.dev.yaml` 只在本机。客户端地址照旧只经 `JABIZ_TRUSTED_PROXIES`（Caddy 在 `proxy` 网络中的地址，不是整个网段：网络上还有其他应用），不用 `server.forward-headers-strategy`。健康检查不含邮件服务器（`management.health.mail.enabled: false`）。
- 本机试用：`tools/quizbuks/local-demo.sh start --seed`（构建、启动、首次设置与演示数据，只用于本机；`deploy/quizbuks/README.md` "本机演示"）。改了设置流程、入口或演示所用的流程输入时同步改它（CI `local-demo`）。
- **生产运维**：`docs/quizbuks/operations.md`（密钥清单、备份与恢复演练、首次启动、切换正式模式、监控、容量、常见故障）与上线检查清单 `docs/quizbuks/launch-checklist.md`；
  新增环境变量、启动检查、任务或告警时同步改 `operations.md` 的对应表格。
- **Stripe 测试模式全流程**：`tools/quizbuks/stripe-test-mode/run.sh`（手册 `docs/quizbuks/stripe-test-mode.md`）用真实测试密钥驱动并核对整条资金流程；只接受 `sk_test_`、缺省只驱动本机、`production-hosts` 中的主机永远拒绝，从不打印密钥。
  改了它所用的流程输入、数据视图、分录的描述或科目时同步改 `stm.py`，并以 `serve --stub` + `run --stub` 干跑；它的离线测试是 `tools/quizbuks/test/stripe-test-mode.test.sh`（CI 作业 `stripe-test-mode` 只在手动触发且有 secret 时运行）。
