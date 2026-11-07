# CLAUDE.md — QuizBuks 应用规则

应用 QuizBuks（商家出资发布 Quiz、用户答题得 Kudos 奖励）的后端（模块 `backend/quizbuks`）。根目录 `CLAUDE.md` 的全部规则照常适用；本文件只写本应用额外的约定。
需求分析：`docs/quizbuks/01-requirements-analysis.md`；设计：`docs/quizbuks/02-design.md`；计划：`docs/quizbuks/03-plan.md`；各阶段的实施计划：`docs/quizbuks/plans/`；产品方向与未来规划（2026-11-01 的讨论，候选阶段待确认）：`docs/quizbuks/04-product-direction.md`。2026-11-02 的八项需求（发起方页面的整理、抽题、通信、付费视频等，候选 Q78–Q84 待确认）：`docs/quizbuks/05-console-requests.md`。

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
  平台管理系统的通用页面对管理员仍显示 JPY。不写点数以外的对外币种名；例外只有商户商品的标价（Q60b：商店自己的价格，平台不经手，按其币种显示，`qb/products.ts` 的 `formatPrice`）。站内信的金额经 `MessageTexts.kudos`（消息 `qb.kudos.unit`），邮件的金额参数只是数字（`MailValues.amount`）、单位写在各语言的模板中。
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
- **内容的多语言**（Q73，`content.ContentLanguages`、`QuizLanguages`、`TranslationProcesses`，`docs/quizbuks/plans/Q73-content-languages.md`）：
  - Quiz 的 `language` 是**基准语言**，`translationLanguages` 是其他语言（至多两种，只经 `QB_QUIZ_LANGUAGES_SET`；有其他语言时不能改基准，`QB_QUIZ_BASE_HAS_TRANSLATIONS`）。
  - **结构只有基准一套**：题目、选项、正确答案、分数都只在基准里。其他语言只有：
    - 译文 `QbContentTranslation`（标题、简介、题干、选项文字，与按语言替换的题目或选项图片），只经 `QB_TRANSLATION_SAVE`，每段记所译基准文字的 `sourceHash`；
    - 资料的语言版本：`QbMaterial` 的 `variantOf` + `language`，种类同基准，没有"看完再答"，不占位置、不计入 `materialCount`。
  - 读资料的地方一律先用 `QuizLanguages.bases(...)` 排除语言版本（快照、计数、排序、AI 的输入）。删除元素、资料、语言或模版时连带删除其译文与资料版本，复制时一起复制。
  - 版本只收**完整的语言**：译全且没有过时的；资料可以回落到基准，不影响完整（D-Q73-5、D-Q73-7）。有翻译的快照 `schema = 2`（`translations`），没有的与以前逐字相同。
  - **发布的语言**（Q73b，`publishing.PublicationLanguages`）：发布的 `languages` 是版本语言的一部分（第一个是首选，空即只有基准），名称与说明的译文在 `translations`（带所译原文的哈希，过时即提交被拒）；卡片冻结 `languages` 与只含文字的 `translations`。
    首页、收件箱按卡片 `languages` 与读者语言**有交集**筛选，文字按显示语言取；答题语言开始时定下（`QbAttempt.language`），之后一律经 `PlaySteps.inLanguage` 读快照、经 `PublicationLanguages.cardText` 取发布文字。
  - **AI 翻译**（Q73d-1，`content.ai.AiTranslateJob`、`AiTranslations`）：只发文字（正误、分数、图片、文件、元素键都不发），候选只在作业中保存，写入只经 `QB_AI_TRANSLATION_ACCEPT`（每段 `reviewed`，规则即 `TranslationProcesses.apply`），哈希取 AI 拿到的原文的；计入商家现有的 AI 限额。
    发布文字的翻译（`PUBLICATION_TEXTS`）只给候选，由向导填入、随 `QB_PUBLICATION_SAVE` 保存；审核人的对照检查（`QB_AI_TRANSLATION_CHECK_REQUEST`）只作参考、计在审核人名下，决定不等待它。
  - **商家的短文字**（Q73c，`content.TextTranslations`）：主页标语、入驻简介、商品的名称与说明的译文都带所译原文的哈希，过时的保存被拒，除非经 `confirmed` 确认；主页提供的语言（第一个为主要语言）中，其他语言的标语必填，简介与商品可选。公开页以 `languages` 选显示语言。
  - 按语言读快照只经 `QuizSnapshots.in`；前端照抄的是发起方页面的 `content/languages.ts`、`preview/model.ts` 和管理系统扩展的 `snapshot.ts`，改规则时几处一起改。
- **发布方**（S0，`publishing.PublisherKind`，`docs/quizbuks/shopify/S0-publisher.md`）：引擎里"谁发布的"是一对不可变、必填的字段 `publisherKind`（`SPONSOR` / `SHOPIFY_SHOP`，字典 `urn:jabiz:dict:quizbuks:publisher-kind`）+ `publisherId`（`SPONSOR` 的编号就是商家的用户编号），
  以 `PublisherKind.declare(eb, processOnly)` 声明；S0 之前就有行被封存的五张只追加表（发布、档位、现金、兑换、电子奖品的 `*_version`）用 `declareSealed`：列仍叫 `sponsor_id`（V30，被完整性封存的列只能增加、不能改名，平台 21 §2.1，`SealedColumnsMigrationIT`）；其他表，包括以后新建的只追加表，用 `declare`，列是 `publisher_id`。**一条规则定范围**：凡是"发布的所有者的副本"（发布、档位、卡片、`QbPublicationOwner`、会话、分析的三张表、现金 / 兑换 / 电子奖品与码池、邀请）带这一对；
  商家账户自己的记录（充值、收据、邮件服务器、邀请配额、领奖码、AI 作业与用量、`QbSponsorRewardMethods.userId`、钱包、账本维度）保持 `sponsorId` / `userId`。新写入处必须显式给出类型（表上没有缺省，发起方页面的流程写 `PublisherKind.SPONSOR` + 操作人）；
  商家的数据视图一律 `fromContext("publisherId", actorId)` **且** `fixed("publisherKind", SPONSOR)`（`RewardConfig.ownDataset` 按字段名自动加）；答题人的首页、详情与公开卡片视图只看 `SPONSOR` 的发布（店面流量在 S2 走 Shopify 模块自己的模板）。
  审批事实是 `publisherKind` + `publisherId`；事件 `qb.publication.status` 的载荷同样是这一对，消费者 `QB_MAIL_PUBLICATION` 只对 `SPONSOR` 发邮件、对没有发布方的事件什么也不做；钱包事件（`qb.sponsor.*`）的 `sponsorId` 不变。
  内容的 `ownerId` 与 AI 的 `sponsorId` 记的是**操作人**，不是发布方，S0 不改（店铺怎样成为操作人由 S1 决定）。界面文字仍叫"商家"，管理系统扩展以 `PublisherLink` 按类型显示（`SPONSOR` 链到用户页，其他显示编号）；地址参数仍是 `?sponsor=`。
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
  管理系统扩展的审核页经 `qb.admin.quiz-takedowns` 显示下架与理由，读好之前不给出决定面板。
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
  **看完视频才能开始**（Q75，`docs/quizbuks/plans/Q75-video-playback.md`）：卡片上线与版本切换都经 `PublicationEditing.cardVideo` 冻结 `video`、`watchRequired`、`videoDurationSec`（商户在资料上填的 `durationSec`，"看完即可"的视频没有它就不能保存版本，`QuizCompleteness`）；开始的输入 `playback` 是 App 从播放器接口读到的报告，规则只在 `play.WatchRules`（App 的 `video/playback.ts` 同一规则、同一组用例），不满足即 422 `QB_ATTEMPT_WATCH_REQUIRED`，`UNKNOWN` 放行；报告写进会话的那条插入（`watchedSec`、`videoEnded`、`playbackStatus`），不读新的行，`AttemptStatementBudgetIT` 不变。发起方页面的资料对话框以播放器预览读时长，因此 `/sponsor` 的内容安全策略同 App 多 `frame-src` 两个播放器。**所有网站的视频一视同仁**（Q99b，`docs/quizbuks/plans/Q99-more-video-sites.md` §5）：首页中间的卡片与开始页一样静音自动播放（各网站自己的参数在 `qb/video.ts` 的 `autoplaying`）；唯一的区别是只有能读进度的 YouTube / Vimeo（`VideoEmbeds.trackable`）可以设为看完才能答题，并经共用播放器开声音，其他网站（哔哩哔哩、niconico、Dailymotion、优酷、腾讯视频）用卡片与页面自己的播放器。哔哩哔哩的短链接 `b23.tv` 由发起方页面经 `QB_VIDEO_LINK_RESOLVE`（外部服务 `video-short-links`）换成完整地址后保存。新增网站时同时改 `VideoEmbeds`、`qb/video.ts`（同一组用例，含 `autoplaying`）、两个 `frame-src` 与隐私政策。
  **正确答案只在完成后出现**：开始与继续的输出不含选项的正误，详情只取快照的简介与资料，`result` 列在完成时才写；答题人读不到版本（`AnswerSecrecyIT`）。
  **选项按位置编号**（Q10b，评审 M1）：开始、保存、提交、`answers` 与 `result` 中的选项编号都是本次显示的位置 1..k，只有评分经 `optionOrder` 换回版本的编号（`Scoring.scoreShown`、`Answers.inVersion`）；
  `optionOrder` 是敏感字段，任何读接口都不返回。
  **单选与多选**（D-Q5-2，2026-10-29 需求方要求在 App 与发起方页面都说明）：问卷题按自己的 `selection`；计分题由正确选项的个数决定，恰好 1 个即单选、多个即多选，只经 `QuizSnapshot.Question.choice()` 判断。
  开始与继续的输出以 `selection` 给出它（只说"几个"中的单选或多选，不说是哪个），App 对单选题用单选按钮、多选题用复选框，发起方页面的编辑、版本与预览和平台管理系统的版本预览都标出单选或多选（商家端为 `content/api.ts` 的 `choiceOf`）。打乱的种子是服务端密钥下的 HMAC（`OptionShuffle.of(IntegrityKey)`：HMAC(`JABIZ_INTEGRITY_KEY`, `qb.option-shuffle`) 再对用户 | 发布 | 题号，评审 M-a），测试的固定密钥使场景的顺序不变。测试中答案按版本的编号写（巴黎是 1），由 `PlayItSupport.submission` / `shown` 换成本次的位置（`AttemptOptionIdsIT`）。
  **钱包余额只在钱包锁内改**（`wallet.QbWallets.change`），与同一事务中的 `LEDGER_POST` 一起；判断可支付一律经 `QbWallets.pays`（无待支付且可用余额 `QbWallets.available` = 余额 − 争议冻结 `held` 足够；SQL 模板同样用 `balance - held`，核对仍比较 `balance`）：待支付不入账、按提交顺序补发、不插队；
  `qb_wallet.balance >= 0` 由检查约束兜底。评分、档位、抽成、打乱、时限与开始条件是纯 Java 的 `Scoring`、`TierPick`、`RewardSplit`、`OptionShuffle`、`AttemptRules`；
  上限在 `PlayLimits`，错误码在 `PlayCodes`。系统流程的权限 `qb.play.system` 不授予任何角色。**开发入账** `QB_WALLET_DEV_CREDIT` 只在
  `quizbuks.wallet.dev-credit.enabled=true`（`dev` 与测试）时注册，权限 `qb.wallet.dev-credit` 不授予任何角色，生产不开（启动检查 `StripeModeCheck` 拒绝它与正式模式或 Stripe 正式密钥同时出现）。它是开放流程，补发经事件 `qb.sponsor.settle-pending` 由系统运行（`QB_PENDING_SETTLE` 等答题的系统流程都是 `internal()`，D41）。
- **地址**（`docs/quizbuks/plans/Q30-paths.md`）：App 在 `/taker/`（入口 `app` 的 `app-path`）、发起方页面 `/sponsor/`、平台管理系统 `/admin/`、招商站 `/biz/`（`quizbuks-web/biz`，Q31：`scripts/build.mjs` 从 `src/templates` 与 `src/content/{en,zh,ja}.mjs` 渲染的静态页面，三语结构相同由测试保证；首页的试答链接与二维码在 `src/content/try-quizzes.json`；只有 `src/assets/site.js` 一个脚本：语言转向与注册按钮）；裸域名由 `jabiz.web.root-redirect` 转到 `/biz/`，App 搬走前的根下路径由 `jabiz.web.redirects` 301 到 `/taker/`（清单在 `application.yml`，招商站新页面不得与之重名）。发起方页面生成的 App 链接只经 `publishing/paths.ts` 的 `APP_BASE`；登录链接的返回路径是站点路径（App 用 `hrefOf`，邀请邮件用入口的 `app-path`），不写死 `/taker`。
- **指定邮箱回答的发布**（`publishing.Audience`、包 `invitation`，`docs/quizbuks/plans/Q25-invitations.md`）：受众是发布的字段 `audience`（`PUBLIC` / `INVITED`，上架时冻结到卡片），
  `INVITED` 的发布不要求地区（`PublicationReadiness`）、不进首页与公开模板、`qb.app.publication` 只对有邀请行的账号返回。清单 `QbInvitation` 只经 `QB_INVITATION_ADD` / `REMOVE` / `SEND`
  （都先 `CallerChecks::refuseBanned`，都在列表锁 `qb.invitation-list:<发布>` 内，发送再取配额锁 `qb.invitation-quota:<商家>`；上限与每日配额在配置 `quizbuks.invitations.*`，不是业务参数；寄送只在 `LIVE` 时）与内部 `QB_INVITATION_MAIL`（任务 `qb.invitation-mail`）写入；邀请邮件是模板
  `qb.invitation`（`signInLink`，平台决策 D55；收件人 `MailRecipient.address`）。答题流程读调用者的邀请只经 `Invitations.loadForTaker` / `mine`（公开的卡片一条语句也不读，预算不变）：
  `QB_VIEW` 认领、开始写 `STARTED` 与 `attemptId`、完成写 `ANSWERED` 与分数、奖励；不另写认领接口或事件消费者。读卡片地区的其他地方（通告、档位模板）要先排除 `INVITED`（其卡片的地区是占位的 `GLOBAL`）；管理员只看人数，地址只给商家。
- **客户匹配**（Q47，包 `audience`、`publishing.PublicationAudience`，`docs/quizbuks/plans/Q47-audience.md`）：条件种类只在 `audience.AudienceCriteria.ALL`（键、选项、范围、计费键 `audience.<键>`；前端 `src/qb/audience.ts` 照抄，新增种类先改这两处与三语文案 `qb/messages.ts` 的 `audience.*`，不问 APPI 的需要特别注意的个人信息）。
  答题者自己的回答 `QbProfileAttribute`（每个选项或数值一行）只经 `QB_PROFILE_ATTRIBUTES_SAVE` 整体替换（账户锁内，全部可选）；发布的条件 `QbPublicationCriterion`（带发布方那一对）只经 `QB_PUBLICATION_AUDIENCE_SAVE` 在草稿时整体替换（发布锁内，`INVITED` 不能有条件，`QB_AUDIENCE_INVITED`），提交即冻结，复制时随发布复制，删除草稿时一起删除。
  **匹配的定义只有一个**：`AudienceMatch`（同一条件内任一选项即可，各条件都须满足，没有回答即不满足，年龄 = 当前 UTC 年 − 出生年）；模板 `qb.app.feed`（不列出）、`qb.app.publication`（`matched`，进入 `canStart`；页面照样显示，以便请人完善资料）与 `qb.system.audience-count` 写同一段 SQL（改一处即改四处，`AudienceIT` 比较模板与 Java），开始答题在锁外既有的 `QueryEntities.all` 中读条件与本人的回答（语句数不变）、`INVITED` 以外不满足即 422 `QB_AUDIENCE_NOT_MATCHED`。
  **原始回答谁也读不到**：默认数据视图的读权限 `qb.audience.system` 不授予任何角色（`*` 照平台规则例外，只有首个管理员），答题者只经 `me:` 读自己的；商家只经 `QB_AUDIENCE_COUNT`（本人的发布，不是本人的 404）得到取整的人数（`AudienceCount`：少于 20 只说"少于 20"，否则向下取整到 10），计数模板只由该流程运行。以后换成专用的匹配服务器时，替换的是这几个模板与开始的读取，不是 `AudienceMatch` 的含义。
  **按规则计费**（Q47c，`audience.AudienceBilling` / `QbAudiencePricing` / `AudiencePricingProcesses`，`docs/quizbuks/plans/Q47c-audience-billing.md`）：规则是平台的产品线，代码只给条件的**种类**；开放与否、收不收费在 `QbAudienceRule`（只经 `QB_AUDIENCE_RULES_SET`，没有记录即开放、计费），价目表 `QbAudiencePriceList` + `QbAudiencePrice`（所有者为运营商或 `direct`，日价与每次最低使用费）只经 `QB_AUDIENCE_PRICES_SET`（总部）与 `QB_OPERATOR_AUDIENCE_PRICES_SET`（运营商负责人）。
  费用只由 `AudienceBilling.quote` 算（天数向上取整、同一规则按一个计、没用计费规则不收最低费、运营商没有价目表按受控参数 `qb.audience.price.fallback`），页面只显示 `QB_AUDIENCE_FEE_QUOTE` 的输出；总开关 `qb.audience.billing.enabled` 缺省关。Q47c-1 只报价、不扣款；提交时经 Q87 的现金账户扣款与退回是 Q47c-2。
- **前置条件**（Q76，`publishing.QbPrerequisites` / `PublicationPrerequisites` / `PrerequisiteRules`、`play.PrerequisiteCheck` / `PrerequisiteRows`，`docs/quizbuks/plans/Q76-prerequisites.md`）：一个发布可以要求答题者先在**本商户的另一个发布**上合格过（`PASSED`）或答过（`ANSWERED`），至多 5 条、全部满足才算。
  普通表 `QbPublicationPrerequisite`（带发布方那一对）只经 `QB_PUBLICATION_PREREQUISITES_SAVE` 在草稿时整体替换（发布锁内，一条 `QueryEntities.all` 读本商户的整条链与所选发布：不是本商户的、草稿、自己、重复、成环（深度 20）都在 `prerequisites[i]` 上报错），提交即冻结，复制时随发布复制，删除草稿时一起删除。
  **满足的定义只有一个**：`PrerequisiteCheck`（在前置发布上有已完成的会话，进行中不算；`PASSED` 另要求合格；学过就算，前置发布结束了也算）；`qb.app.feed`（不列出）、`qb.app.publication`（`prerequisitesMet` 进入 `canStart`，`prerequisites` JSON 供页面显示"先完成：X"）与 `qb.system.audience-count` 写同一段 SQL（改一处即改四处，`PrerequisiteIT` 比较）。
  开始答题在锁外既有的 `QueryEntities.all` 中读前置行，**只在有前置时**另读一条本人在前置发布上的会话（`taker:QbAttempt`，像 `RoundHolders.load`），不满足即 422 `QB_PREREQUISITE_NOT_MET`；普通开始的语句数不变，有前置的多一条锁外语句（`AttemptStatementBudgetIT.aStartWithPrerequisites`）。店面的开始不查前置。
  商户主页经公开模板 `qb.public.sponsor-page-publications` 的 `prerequisites`（只列两端都在榜上的）按层显示（App `share/levels.ts`）；前端的名字在 `qb/ids.ts` 的 `PREREQUISITES`，要求的文案在 `qb/messages.ts` 的 `prerequisite.*`。新的默认数据视图别忘了登记 `OperatorScopes`（缺少即启动失败）。
  **第二种前置是"持有认证"**（Q86，`docs/quizbuks/plans/Q86-agreement-quiz-tree.md`）：同一张表的 `certification`（与 `requiredPublicationId` 二选一，CHECK + `(publicationId, certification)` 唯一；只能是 `Certifications.FOR_TAKERS`，要求存为 `PASSED`），
  满足 = 此刻持有，SQL 是 feed 中 `heldUntil` 那段（`VALUES` 目录 + 记录 ∪ 未认领的合格，`CertificationTemplatesTest` 核对八个模板）；开始时 `PrerequisiteRows.loadHoldings` 只在有认证前置时锁外多一条语句（`RoundHolders.loadHoldings`），`PrerequisiteCheck.unmet` 多一个输入：持有的键集合。
- **抽题**（Q81，`publishing.PublicationReadiness.pickProblems` / `fullScore`、`play.QuestionPick`、`QuizSnapshot.subset`，`docs/quizbuks/plans/Q81-question-pick.md`）：发布的 `pickCount` 让每个答题者只答版本的 k 题（1 ≤ k < n，空 = 全考；只允许每题同分的版本，满分 = k × 每题分，问卷不抽；复制随发布、审批内容含它、卡片冻结它）。
  开始时 `QuestionPick.pick`（HMAC 种子同选项打乱，`qb.question-pick`）取 k 题、保留题序，记在会话 `questionSet`（敏感）；**会话的快照只经 `PlaySteps.forAttempt` / `subset`**（先 `QuizSnapshots.in` 再 `subset`：译文键是版本题号），开始、继续、提交、超时、结果都用它，不自己按 `questionSet` 过滤。
  **答题人看到的题号是位置 1..k**（`QuestionView.no`、提交与结果输出的 `no`；App 发回的 `questionNo` 就是位置），存储的 `result` 行记版本题号（统计的键）。题目统计每题按被抽到的完成数计，`AnalyticsJob.showQuestions` 逐题以最小分组发表。版本切换的新版本须能承接 k（`QB_PUBLICATION_SWITCH_PICK`）。店面的活动没有这个设置，卡片 `pickCount` 为空即全考。语句数不变。
- **商家自己的邮件服务器**（包 `mailserver`，`docs/quizbuks/plans/Q29-merchant-mail-server.md`，平台 D56）：实体 `QbSponsorMailServer`（每个商家一行）只经 `QB_MAIL_SERVER_SET`（平台步骤 `SealSecret` 密封密码，阻塞步骤 `TestMailServer` 用平台的 `MailSenders` 检查设置并给商家自己的地址发测试邮件，结果写 `verifiedAt` / `lastError`）与 `QB_MAIL_SERVER_REMOVE` 写入；
  平台的 `MailSenderSource` Bean（`MailServerConfig`）只交出验证过的行。邀请只经 `SendMail.via(商家编号)` 发出（`QB_INVITATION_SEND` 没有验证过的服务器即 422 `QB_INVITATION_NO_MAIL_SERVER`；`QB_INVITATION_MAIL` 发现服务器不可用即退回 `ADDED`），绝不回退到平台的服务器；
  平台自己的邮件照旧。模板 `qb.sponsor.mail-server` 从不返回密码。`JABIZ_SEAL_KEY` 是部署必需（入口脚本检查、本机演示自己生成）；`jabiz.mail.senders.trust-all-for-tests` 只在测试配置与 e2e 中开。
- **运营商**（包 `operator`，`docs/quizbuks/plans/Q48-operators.md`）：运营商 `QbOperator`、成员 `QbOperatorMember`、商户归属 `QbSponsorOperator`、抽查 `QbReviewSpotCheck` 都是普通表，只经 `QB_OPERATOR_*` / `QB_SPONSOR_OPERATOR_ASSIGN` / `QB_REVIEW_SPOT_CHECK` 写入（默认数据视图 `processOnlyWrites`，读权限 `qb.admin.operators.read`）。**每个发起方都属于一个运营商**（F-S1，`docs/quizbuks/plans/impl/F-S1.md`）：自己入驻的归默认运营商（在 答题拼好价平台管理系统中以 `QB_OPERATOR_SAVE` 建立并设为默认 `defaultForNew`，至多一个，设另一个即取代，不能停用；`QB_SETUP` 不建运营商；不按语言分配，运营商的语言只作展示与获客），获客试用键入驻的归获客的运营商，入驻时一定写归属（没有默认运营商即 422 `QB_OPERATOR_DEFAULT_MISSING`，每日核对报 `NO_DEFAULT_OPERATOR`）；代码与模板中没有 `direct` 与"没有运营商即总部"的分支（`NoDirectOwnerTest`，价目表的 `direct` 属于 F-M）。
  **直营发起方**：每个运营商建立时必填负责人（`QB_OPERATOR_SAVE` 的 `leadUserId`），负责人在同一事务中成为它的直营发起方（`identity.DirectSponsors`：已通过的入驻、`fixed` 的归属、`qb_operator.direct_sponsor_id`）；发起方角色由本人经平台的加入入口取得（`SponsorApplyGuard` 放行直营发起方），流程不授予角色；直营发起方的发布由另一位成员审核（平台审批的"准备人不能审批"）；它就是该运营商认证轮的官方出题方（`qb_official_publisher` 已删除）。
  **改派** `QB_SPONSOR_OPERATOR_ASSIGN`：`operatorId` 必填，目标须在运行、接收 `SPONSOR`（`publisherKinds`）；直营发起方 422 `QB_DIRECT_SPONSOR_FIXED`；发起方（作为准备人）待审的审批请求及其待办经平台的子流程 `APPROVAL_SCOPE_MOVE` 一起移到新运营商（平台决策 D76；调用者另需 `approval.scope.move`，`QbRoles` 给超级管理员；一次至多为平台的单次上限 `jabiz.approval.scope-move.max-items`（`ApprovalScopeMoveSettings.maxItems()`，缺省 500），超过即 422 `QB_SPONSOR_REVIEW_PENDING`，不分批）；举报与数字奖品争议的待办经 `OperatorHandover` 在旧范围取消、在新范围重建，费用提醒随账单冻结的运营商（F-M）；发起方收到站内信 `OPERATOR_CHANGED`。
  **每日核对** `qb.operator-check`（`QB_OPERATOR_CHECK_RUN`，模板 `qb.admin.operator-health`）：没有默认运营商、没有归属的发起方、直营发起方不是恰好一个的运营商、能审核的成员不足两位的运营商；有问题即给 `qb.admin.operators.read` 一个待办（每天一个）。
  **成员关系与角色分开**：角色 `QB_OPERATOR` 含 `approval.decide`，是受保护的授权，只经平台的角色分配与四眼（不在流程中授予）；成员关系由总部添加、总部或负责人移除（移除只写 `removedAt`，抽查按决定时的成员关系判断）。入口 `operator`（`/operator/`，平台管理系统前端的第二份构建）由 `QbSignInGuard` 另查当前成员关系与 `operatorActive`；
  **运营商的每个动作**先 `OperatorChecks.member`（共享锁 `qb.operator-member:<用户>`，加入与移除取排他锁）再取账户、发布等锁，锁序 `qb.operator:<运营商>` → `qb.operator-member:<用户>` → 其余。运营商的下架与封禁是自己的流程（`PublicationAdmin.operator*`、`BanProcesses.operator*`，权限 `qb.operator.use`），记 `takedownOperatorId` / `operatorId`：运营商只恢复与结束自己下架的、只解除自己的封禁、只封禁有入驻的商户；总部再次暂停或封禁即变成总部的（D-Q48-5）。
  **运营商不读用户资料**（没有 `qb.admin.users.read`）：只经 `qb.operator.*` 模板（商户、概况，只有合计）与只读流程 `QB_OPERATOR_TEAM`；扩展的页面按权限选模板（审核页、商户链接）。新增运营商可做的事时，权限加进 `QbRoles` 的 `OPERATOR_PERMISSIONS` 并在 `QbRolesTest` 的清单中写明，不给运营商用户资料、财务、法律文本、参数与角色的权限。
  多个运营商互相隔离（Q59c-2，平台 17y 的入口范围，决策 D64）：运营商的权限读得到的数据视图以 `OperatorScopes` 约束、对象以 `OperatorChecks.ownSponsor`（`RequireScope`）核对，总部也运行的运营商动作（作废兑换、电子奖品处理）以 `OperatorChecks.memberWhenScoped` 在动作之内再查成员关系（Q48e-1）；
  首次入驻带开拓链接的键（`QB_SPONSOR_APPLY` 的 `trialKey`）即归开拓的运营商、审核随之（`docs/quizbuks/plans/Q48e-operator-isolation.md`）。
- **运营商结算**（`operator.OperatorSettlements`，`docs/quizbuks/plans/Q48-operators.md` §7 Q48b）：收入的归属是发布卡片上线时冻结的 `operatorId`（不是账本维度：答题过账不多读），月结算按 4110 的发布维度连到卡片；`QbOperatorSettlement` 只经 `QB_OPERATOR_SETTLEMENT_RUN`（逐项任务，`internal()`）/ `PAY` / `APPROVAL_RESULT` 写入，过账 5130 / 2140（来源 `QbOperatorSettlement`，月报的 `OPERATOR_SHARE`）。锁 `qb.operator:<运营商>`。
  **方案与账户**（Q48e-2，`OperatorPlans`、`OperatorWithdrawals`，`docs/quizbuks/plans/Q48e-operator-isolation.md`）：比例与固定费取运营商当月的方案（受控参数 `qb.operator.plan.<n>.fee|share`；方案变更 `QbOperatorPlan` 只经 `QB_OPERATOR_PLAN_SET` 与审批结果写入，四眼、从以后的月份起）；结算记入运营商的账户（`CREDITED`，余额 = 记入的应付 − 未驳回未取消的提现，模板 `qb.system.operator-balance`），钱都在平台；提现 `QbOperatorWithdrawal` 只经负责人的 `QB_OPERATOR_WITHDRAWAL_REQUEST` / `CANCEL`（入口范围给出运营商，先取运营商锁再查成员关系）与财务的 `PAY` / `REJECT`、审批结果写入，批准后过账 2140 / 1120（月报 `OPERATOR_PAID`）；银行账户 `QbOperatorBankAccount` 只经 `QB_OPERATOR_BANK_ACCOUNT_SAVE`。此前签发（`ISSUED`）的结算照旧逐张 `PAY`。
- **AI 获客**（包 `prospect`，`docs/quizbuks/plans/Q48-operators.md` §3 与 §7 Q48c）：所有者 `owner` 是运营商编号（F-S1：总部不获客，平台运营商的成员获客），只由 `ProspectProcesses.owner` 决定（调用者的当前成员关系，否则拒绝；`qb.admin.prospect` 只读全部），不取自输入；五张普通表（设置、方向、候选、不发名单、月用量）只经 `QB_PROSPECT_*` 写入（默认数据视图 `processOnlyWrites`，读权限 `qb.admin.prospect`；运营商只经 `qb.operator.prospect*` 模板读自己的）。
  Claude 的调用只在两种外部作业（`ClaudeWebResearch`：网页搜索与读取，不用结构化输出、以末尾的 JSON 对象作答并由 `ProspectAnswers` 逐项核对，`pause_turn` 续发），处理器不访问数据库，作业类型 `enabledBy(qb.prospect.enabled)`；用量（tokens 与搜索次数）只由作业的结局流程在所有者锁内计入 `QbProspectUsage`（没有回答与没有记下用量的尝试按预留计入），当月达到上限即不开新的找与研究。
  **候选从不自动发送**：研究只写草稿（`DRAFTED`），人经 `QB_PROSPECT_APPROVE`（改过的邮件与问卷照 Claude 的同样核对：纯文本、无链接，5 题 4 选 1）批准，逐项任务 `qb.prospect-send` 在工作时间内、每日上限之内经 `SendMail.via("prospect:<所有者>")` 从所有者验证过的服务器发出（`QB_PROSPECT_MAIL_SERVER_SET`，与商户邮件服务器同表），绝不经平台的服务器；邮件带发件人名称、住所与联系方式（《特定電子メール法》）与平台的地址退订链接。
  **不发名单**（地址或域名，全平台生效）在找、研究与发送前都查；退订、拒收声明与"屏蔽"的丢弃都记进去。锁序 `qb.prospect-owner:<所有者>` → `qb.prospect:<候选>` →（只有用量）`qb.prospect-usage:<所有者>`；体验问卷不是发布，只经公开模板 `qb.public.prospect-trial`（只给已发出的，按随机键 `trialKey`，不叫 token）在 `/sponsor/try/<键>` 显示。
  Q48d：注册归属只经 `QB_PROSPECT_CLAIM`（发起方页面记住 `?src=` 的键、申请之后调用一次；只为没有归属、邮件发出后才通过审核的商户写 `QbSponsorOperator`），跟进只经 `qb.prospect-follow-up`（7 天后一次，与首封共用检查与每日上限），自动发送只按所有者的 `autoApproveScore`；运营商的月度上限另受受控参数 `qb.prospect.operator-monthly-cap-usd` 约束，结算按 `qb.prospect.usd-jpy` 扣除当月费用（被扣回的计入 4110，扣不完的结转）。
- **资源库**（F-C1，包 `asset`，`docs/quizbuks/plans/impl/F-C1.md`）：发起方自己的资源 `QbAsset`（种类 `AssetKind`，不可变）与"用在哪里" `QbAssetUse`（使用处的种类与编号唯一，记复制时资源的 `revision`）两张普通表，只经 `QB_ASSET_SAVE` / `QB_ASSET_DELETE` / `QB_ASSET_COLLECT` 写入（默认数据视图 `processOnlyWrites`，客服读、登记 `OperatorScopes`；`me:` 视图只读本人）。所有人就是发起方本人的 `sponsorId`（不用发布方那一对，也不用 `direct`）。
  规则只在 `AssetRules`（纯 Java：每种只有自己的内容字段、长度沿用目标字段的上限、标签、视频网站与时长、奖品组合、译文、内容哈希与 SQL 的 `encode(sha256(convert_to(…,'UTF8')),'hex')` 相同）；三个流程都取锁 `QbAssets.lockName`（`qb.assets:<发起方>`）并在锁内读本人全部资源，数量上限 `qb.assets.max-count` 与去重都在锁内；带 `baseRevision`（不是当前的即 422 `QB_ASSET_CHANGED`），别人的资源 404，新放入的文件须本人上传（`QB_ASSET_FILE_NOT_OWN`）。文件按种类分三列（`image` / `pdf` / `audio`，各自 `FileKind`，平台清扫才不会删）。
  **选了就复制**：资源只是目录，使用处复制内容、引用同一文件编号，之后各管各的，资源改过只提示"旧内容"；删除资源不影响使用处。收集的候选只经模板 `qb.sponsor.asset-candidates`（本人点了才读，文件按编号、文字按哈希去掉已有的），一次至多 `AssetRules.MAX_COLLECT` 项。删除账号时两张表随 `SponsorErasure` 删除。答题路径不读资源。
  客服与运营商只读：平台管理系统扩展的发起方详情页有"资源库"一栏（`SponsorAssets.tsx`，默认数据视图，没有写入的操作）；帮助页与 FAQ 的三语底稿在 `docs/quizbuks/help/asset-library.md`（内容管理员发布，用词测试扫描该目录），运维步骤在 `operations.md` §7.21。
- **用量**（F-C2，包 `usage`，`docs/quizbuks/plans/impl/F-C2.md`）：计量项只在 `UsageMeters` 一处列出（快照模板 `qb.system.usage-measure` 与它不一致即启动失败，`UsageMetersCheck`；新加一项两处一起改，字典 `usage-meter` 由它生成）。每日快照只由逐项任务 `qb.usage-snapshot`（每个发起方一项 `QB_USAGE_SNAPSHOT_ITEM`，`internal()`，权限 `qb.usage.system` 不授予任何角色，**不取业务锁**）写进普通表 `QbUsageDay`（**每个发起方每天一行**，全部计量项在 `meters` 的 JSON 对象中、键恰好是 `UsageMeters`，不按计量项分列或分行：审计 D27 不能关，一行一天即每人每天一条；当天已有的行覆盖、数值没变不写，超过 `qb.usage.retention-days` 的删掉，删除账号随 `SponsorErasure`；默认数据视图 `processOnlyWrites`、客服读、登记 `OperatorScopes`；`me:` 只读本人）。
  快照时刻是**部署配置** `quizbuks.usage.snapshot-time`（`QB_USAGE_SNAPSHOT_TIME`，`HH:mm`，按 `jabiz.fiscal-year-zone`，改后重启；需求方 2026-11-07），不是业务参数，也不写成 UTC 的日本时间。文件按上传者（`sys_file.uploadedBy`）与原文件大小计（不含变体），一个文件只算一次；`ai.tokens` 只读 `QbAiUsage`（AI 问题解答的用量不进它）。
  **首次上线只计量、显示与提示，从不拒绝上传**：免费额度 `qb.usage.storage.free-bytes` 是受控参数，只用于资源库顶部的用量卡、横幅与第一次越过时的一条站内信（事件 `qb.usage.free-exceeded` → `QB_MESSAGE_FROM_EVENT`，种类 `USAGE_OVER_FREE`，`sourceKey` 每次越过一个）。管理系统的用量页 `qb.admin.usage`（`UsagePage`，可导出）；帮助底稿 `docs/quizbuks/help/storage-usage.md`，运维与补跑在 `operations.md` §7.22。答题、发布与上传的路径不读也不写用量。
- **称呼：答题的一方叫"学者"**（需求方 2026-11-07）：中文"学者"（取代界面中的"答题者""答题人"），日文"テイカー"（取代"回答者"），英文 "Taker"（代码中同样叫 taker）。学者答题、发起方出题，两者位置平等。界面文案、邮件、帮助、法律文本、商店资料已在 F-F5 统一改（与 Q91"商户"→"发起方"同一批）。发布的一方：中文"发起方"、日文"主催者"、英文 "Sponsor"。英文两个称呼首字母大写（"the Sponsor"、"a Taker"）。三语词表见 `docs/quizbuks/glossary.md`；用词测试 `tools/quizbuks/terms/terms.test.mjs`（CI）扫描人读到的文字，停用词（商户、商家、答题者、答题人、回答者、スポンサー、加盟店、merchant、quiz taker）出现即失败；新写的文字照词表，例外写进测试并说明原因。各部分的名字（需求方 2026-11-07，F-F5b）：人读到的文字中不用"后台"（日文"管理画面"、英文 "console" / "Administration"）；发起方一侧作名称为 `<品牌>发起方` / `<ブランド> 主催者` / `<brand> for Sponsors`，句子中为"发起方页面 / 主催者ページ / the Sponsor app"；运营商一侧为"运营商管理系统 / 運営パートナー管理システム / Operator Management System"；平台管理为 `<品牌>平台管理系统` / `<ブランド> 管理システム` / `<brand> Management System`（品牌名照 Q61，见词表）；Shopify 等其他公司产品的叫法照旧。
- **首次上线以规格为准**（需求方 2026-11-07）：首次上线要做的全部内容在 `docs/quizbuks/first-launch-spec.md`（54 项，每项含规则、参数、运营闭合、依赖、验收）；开发只依照它，Q94 与各计划只作存档。需求变化时先改规格再改代码；规格中的"待需求方决定"未定的项不开工。规格写清楚的项目已一次授权：实施计划写在 `docs/quizbuks/plans/impl/<项目>.md` 与 PR 描述中，写好即开工（会话分工与规则见 `docs/quizbuks/first-launch-dev-plan.md`）。
- **功能做到闭合、完整，正式使用后少维护**（需求方 2026-11-07）：范围靠砍整块（商店 App、Shopify 等）来压缩，**不砍规则与运作**。每个功能交付时同时具备：用户自己能完成的路径（含出错、撤回、更正）、管理与运营商的处理页面与权限、定时任务与自动的收尾（过期、退回、通知）、每日核对或告警、帮助文案与 `operations.md` 的处理步骤、审计与统计；不留"上线后由管理员手工处理"的缺口。计划中为省工夫而推迟运营工具的简化，按此重新判断。
- **不随意减少已经做好的功能**（需求方 2026-11-07，立即生效）：去掉任何已有功能（页面、流程、选项、提示、测试覆盖的行为）都要有需求方明确的指示；规格或计划中写了"去掉""迁出""取消"的，动手前仍要先把要删的逐项列给需求方、确认后才删（多一次确认）。改名、移动位置、换写法而行为不变的不算删除；拿不准的按删除处理、先问。每个 PR 的描述写明"删除的功能：无"或逐项列出并注明需求方的确认。
- **需求中的具体数字不写死**（需求方 2026-11-05）：讨论中定下的金额、比例、天数、倍数、汇率、名额等一律是业务参数（钱与免审的列入 `QbParams.CONTROLLED`，四眼修改），代码只有规则；缺省值写在 `QbParams`，由 `QB_SETUP` 提出。
- **业务参数的存在**由启动检查 `setup.SetupParamsCheck` 保证（Q26）：新增参数只加进 `QbParams.ALL`（受控的还加进 `CONTROLLED`），部署后执行 `QB_SETUP`；缺少即启动警告，不在流程里自己给缺省值。
- **投资人指标**（`queries/qb/admin/investor-metrics.sql`、`investor-sponsors.sql`，`docs/quizbuks/plans/Q27-investor-metrics.md`）：只是两个带 `report: period` 的财务模板（UTC 月，各数字按发生时间归月，读时态实体的当前版本）与管理系统扩展的页面 `InvestorMetricsPage`（导出 / 签发 / 存档复用 `ReportsPage` 的 `ReportActions` / `Issued`）；不存数字、不建表、不建任务。新加一列先改模板与 `InvestorMetricsIT`，再改页面与三语消息。
- **奖励的支付方式**（`publishing.RewardMethod`、`reward`，`docs/quizbuks/plans/Q22-reward-methods.md`）：`PLATFORM`（平台经账本付）、`SPONSOR_CASH`（商家付现金）、
  `OFFLINE_REDEEM`（商家处兑换）。商家允许的方式只经 `QB_SPONSOR_REWARD_METHODS_SET` 写 `QbSponsorRewardMethods`（没有记录 = 受控参数 `qb.reward.default-methods`，声明值 `PLATFORM`，推广期发布 `OFFLINE_REDEEM`；每次答题的最高奖励 `qb.reward.max-per-attempt`，0 为不限；`plans/Q28-phase1-defaults.md`。发起方页面经模板 `qb.sponsor.reward-methods` 读当前允许的方式，不自己猜缺省）；发布的 `rewardMethod` 在保存、复制、提交时都经
  `PublicationEditing.methodProblems` 核对，兑换须有 `redeemDescription`。不是 `PLATFORM` 的结算**不取钱包锁、不读钱包、不过账**（`AttemptFinishing`、`AttemptRules`），只插入 `QbCashReward` / `QbRedemption`
  （都是时态实体，状态在 `QbRewards.TRANSITIONS`，流程在现金记录锁 `qb.cash:<记录>` 内）并记计数；现金的状态只经 `QB_CASH_REWARD_*` 流程改变，逾期禁止开始只在 `CashRewardChecks.refuseOverdue`。**现金争议平台不管**（F-S1）：没有 `qb.cash.resolve` 与平台的处理，学者提出争议只通知发起方；发起方与学者解决后再次标记已支付（`DISPUTED` → `MARKED_PAID`，确认期限重新起算），或学者直接确认。
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
  （`QB_STAFF_SPONSOR_BANNED`）；锁的顺序 `qb.sponsor-staff:<商家>` → `qb.staff:<用户>` → `qb.redeem-token:<商家>`。店员只看待处理的申请与本人交付的（`qb.app.staff-redemptions`），不看商家的历史。Q74（`docs/quizbuks/plans/Q74-staff-handled.md`）：店员的"我的交付"（`qb.app.staff-handled` 与 `-totals`）同样只经本人当前的店员关系读取，离开即看不到；商户按经手人看统计（`qb.sponsor.redemption-handlers`，含已移除的店员）与筛选（`qb.sponsor.redemptions` 的 `handledBy`）；期间按部署时区的日界（`qb_stats_run.zone`），"现在"由页面按服务端时钟给出。
- **账户**（`identity`，`docs/quizbuks/plans/Q2-accounts.md`）：三个登录入口 `admin` / `app` / `sponsor` 写在 `application.yml`（平台 10 §15）；`app` 允许未验证的用户登录浏览，`sponsor` 要求已验证。
  **通行密钥**（Q97，`docs/quizbuks/plans/Q97-passkeys.md`，平台 D71）：`app`、`sponsor`、`admin`、`operator` 以 `QB_PASSKEYS`（缺省开）开启，`shopify` 不开；依赖方与来源只由 `identity.PasskeyDefaults` 从 `jabiz.mail.base-url` 得出（或 `QB_PASSKEY_RP_ID` / `QB_PASSKEY_ORIGINS` 显式给出），不在代码中写域名。App 与发起方页面只用平台的 `SignInForm` 的 `passkey`、积木 `Passkeys` 与 `MailLinkPage` 的 `passkey-confirm`，不自写 WebAuthn。
  **QuizBuks 的一切写入流程声明 `requiresVerifiedEmail()`**（未验证即"待激活"；预占他人邮箱的人因此留不下应用数据），以后的阶段照此。
  资料、入驻、封禁各是一人一行的时态实体（主键是生成的 UUID，`userId` 不可变、唯一；资料与入驻的 `userId` 是操作人编号的文本，封禁的是平台用户的引用），只经 `QB_PROFILE_*` / `QB_SPONSOR_*` / `QB_USER_*BAN` 写入；
  写一个用户账户的流程先取 `HoldLock.exclusive("qb.account:" + userId)` 再读取（锁的顺序：账户锁在模版锁、发布锁、钱包锁之前；目前不同时持有）。本人流程的用户取自操作人，不取自输入。
  **封禁在每个写入中再查**（已签发的访问令牌到期前仍可用）：一切由人发起的 QuizBuks 写入流程在最前面加 `.steps(CallerChecks::refuseBanned)`（读 `QbUserBan`，422 `QB_USER_BANNED`，立即拒绝）；
  提交发布另经 `publishing.SponsorChecks`（同时要求入驻已通过，一次报告全部问题）。Q5 的答题、Q6 的转账照此。封禁与解封的管理员同样要求已验证的邮箱；"管理员"即平台管理系统的入口接受的角色（`jabiz.security.entries.admin.accepted-roles`），不能被封禁。
  入驻的审批对象 `qb.sponsor`、规则 `qb.sponsor.review` 同发布：**审批规则只经 `QB_SETUP` 以受控变更提出**，"无规则即放行"由应用挡住（`setup.ReviewCodes`）。审批绑定的内容只在入驻行上（名字 `contactName`、公司、简介、头像、同意条款的时间），
  资料的名字另由本人修改、后写者为准，不影响审批；批准须为入驻自己的、绑定当前内容的请求，驳回只须是入驻自己的、已驳回的请求，理由取自审批人的决定。
  **商家头像被替换或去掉时由流程释放旧文件**（先清引用并 `SaveChanges.now`，再子流程 `FILE_DELETE`），这是"业务流程不调用 `FILE_DELETE`"的唯一例外：头像只有本人的一行引用，等不到清扫。
  **只能由子流程运行的流程**（如 `QB_QUIZ_RELEASE_FILES`）声明 `subProcessOnly()`（D41），权限不授予任何角色（`qb.content.system`），流程内照样再查自己的前提。
  **谁能启动流程**（D41）：QuizBuks 的事件消费者、定时任务、回调接续点与审批结果（发布与入驻）一律 `internal()`，只作子流程的用 `subProcessOnly()`，由人发起的照常开放；
  事件与定时任务只运行 `internal()` 流程（启动检查），不用 `.manualToo()`，除非该开放流程确实也由人手工运行。测试中以系统身份运行内部流程用 `ContentItSupport.runAsSystem`，场景中用 `actor: {id: system}`。
  事务邮件模板在 `identity.AccountMails`（账户）与 `messaging.NotificationMails`（Q7a：转账、收款账户、充值、发布）（文案 `mail.<名>.subject|body|name`，`name` 是偏好中显示的名字，平台 D49）；链接到某个前端用平台的 `{entryUrl.<入口>}`（商家邮件为 `[…]({entryUrl.sponsor})`，D43 第 4 条），不用参数传地址。`quizbuks.links.sponsor` / `quizbuks.links.app` 只给 Stripe 的返回地址。
  **邮件只由事件的消费者发**（`messaging.MailProcesses`，`internal()`，权限 `qb.messaging.system` 不授予任何角色）：业务流程只在自己的事务中发布事件（发布的状态变化为 `qb.publication.status`，由 `publishing.PublicationNotices` 判定哪些迁移值得通知），
  消费者再读记录本身（不信事件中的数字；如转账邮件只在转账仍是 `SENT` / `FAILED` 时发，结束邮件在下架后或商家被封禁时不发）、跳过没有邮箱的用户与过时的事件（通知 3 天、事务邮件 14 天，`NotificationMails.MAX_AGE` / `TRANSACTIONAL_MAX_AGE`），以平台的 `SendMail` 记下邮件；答题与支付的锁内不发信。可退订的（`NOTIFICATION`）只有商家的两种，声明 `.entries("sponsor")`（欢迎邮件 `.entries("app")`）；新增可退订的模板时同样声明入口与三语的 `mail.<名>.name`。退订链接按入口指向各 SPA 自己的退订页（`jabiz.mail.unsubscribe-paths.sponsor` / `.app` 为 `mail/unsubscribe`，两个 SPA 在该路径挂载 `@jabiz/ui` 的 `MailUnsubscribe`，平台 D52 第 3 条）：不要求 `/admin/` 对外可达；没有声明入口的模板仍用平台管理系统的页。
  会被反复触发的通知按收件人与种类在 `QbMailNotice` 中节流（每 24 小时至多一封，锁 `qb.mail-notice:<收件人>`，不取发布锁或钱包锁）：预算不足一封列出全部预算不足的发布，窗口内的再次不足只记 `pending`、不丢弃，由逐项任务 `qb.mail-funds-short-due`（每小时，模板 `qb.system.mail-funds-short-due`，每项 `QB_MAIL_FUNDS_SHORT_DUE`）在窗口过后重读卡片、仍不足才发；收款账户受限的邮件同样每人每 24 小时至多一封。邮件中的时刻一律按 `jabiz.fiscal-year-zone`（日本时间）并标时区简称（`messaging.MailValues`），不写死时区。
- **支付**（`wallet`，`docs/quizbuks/plans/Q6-payments.md`）：
  - **全部 Stripe 调用都是异步外部作业**（`wallet.TopUpJobs`，处理器只经外部服务 `stripe` 与 `StripeApi`：自写表单编码与解析，不用 SDK；以 `Stripe-Version` 固定版本），
    不在事务中等外部调用，不放在提交后步骤（其重试不持久化）。作业的完成与失败流程、入站事件的消费流程一律 `internal()`，权限 `qb.wallet.system` 不授予任何角色。
  - **只信服务端记录**：Checkout 的金额、币种、引用与返回地址只由 `CheckoutRequest` 按记录与配置生成；入账的金额、对象与锁名只取自记录，不取自 Stripe 的元数据；
    签名的事件同样逐项核对（`TopUpChecks`、`RefundMath`），不成立的开待办（`TopUpTasks`，给 `qb.reconcile`）、不入账、不让流程失败。浏览器跳回不入账。
  - **只入账一次的四层**：平台按事件号去重；每个（消费者，事件）只处理一次；同一会话的各种报告（通知、核实）走同一套步骤 `TopUpCrediting`，在商家钱包锁内重读，
    只有 `OPENING` / `OPEN` 能变为 `PAID`；数据库兜底（会话、付款、交易号唯一，`PAID` 必有分录）。新的入账来源照此接入，不另写入账步骤。
  - **锁序**：`qb.account:<用户>` → `qb.quiz:<模版>`（共享）→ `qb.pub:<发布>` → `qb.pub-start:<发布>`（Q10a4，只有状态流程）→ `qb.wallet:SPONSOR:<商家>`；`qb.wallet:TAKER:<用户>` 独立（Q88b 的例外：收报名费的开始在开始锁**之前**取它，锁序 `qb.wallet:TAKER:<用户>` → `qb.pub-start:<发布>`，没有流程反过来取；Q6b：其余只由同步与转账持有，
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
  - **现金账户与学分的两种来源**（Q87a，`wallet.TopUpTargets`、`KudosExchanges`，`docs/quizbuks/plans/Q87-fee-account.md`）：每个商户除学分钱包（`QbWallet` 的 `SPONSOR` 行，科目 2110）外还有现金账户（同一所有者的 `CASH` 行，科目 2160，维度为商户），两者都在 `qb.wallet:SPONSOR:<商户>` 一把锁内改。
    充值（卡与银行转账）的去向 `QbTopUp.target`（`KUDOS` / `CASH`，开始时定、不可变）只由受控参数 `qb.topup.targets`、`qb.topup.default-target` 决定（未发布时同 Q87 之前：只进学分钱包）；入账、退款、争议都按充值的去向取科目与钱包（`TopUpTargets.account` / `walletKind`），进现金的不触发补发待支付奖励；手工加币只进学分。
    现金与学分的兑换只经 `QB_KUDOS_BUY` / `QB_KUDOS_SELL`（一比一，规则只在 `KudosExchanges.problems`：开关 `qb.kudos.buy.enabled` 缺省开、`qb.kudos.sell.enabled` 缺省关，未发布即关；只换可用的、换回不越过待支付的奖励），记录 `QbKudosExchange` 是过账的来源（月报 `KUDOS_BOUGHT` / `KUDOS_SOLD`），先 `SaveChanges.now` 再 `LEDGER_POST`。
    每日核对另比 2160 与现金账户（`CASH_ACCOUNT`、`TOTAL_2160`）；分录导出按商户列 2160 的行；月报加列 `sponsorCash` 与现金的充值、退款、争议各类。转账手续费 = 固定 `qb.transfer.fee` + 比例 `qb.transfer.fee-rate`（受控，缺省 0，向下取整，`TransferRules.fee`）。平台费、预付与后付、账单是 Q87b。
  - **收款与转账（Q6b）**：收款账户 `QbPayoutAccount` 是普通表，`userId` 与 Stripe 账户号都唯一（一个 Stripe 账户永远只属于一个用户），只由开通作业的结果与
    `stripe-connect` 提供方（密钥只来自 `STRIPE_CONNECT_WEBHOOK_SECRET`）的通知写入，在 `qb.account:<用户>` 锁内、只取更新的报告。
    用户钱包缓存（`QbWallet` 的 `TAKER` 行）只经 `TakerWallets`（`QB_TAKER_WALLET_SYNC` 与转账流程）**按账本重算后覆盖**，不做加减；结算奖励不取用户钱包锁。
    转账的一切流程只持 `qb.wallet:TAKER:<用户>`，锁内从账本读余额；金额只取自账本与记录。**只转未保留的部分**（Q10b，评审 M3）：模板 `qb.wallet.taker-transferable` 给出保留中的奖励
    （最近 `qb.transfer.hold-days` 天入账的，与有未结争议的商家在被争议付款之后入账的，奖励即来源为 `QbAttempt` 或（Q70）`QbReferral` 的 2120 贷方行，商家经发布维度与卡片找到；
    只计尚在余额中的部分：转出先取自由的、再取按时间保留的、最后取争议冻结的，只取此前入账的，保留不超过余额；转账退回的钱（冲正、撤回）与该转账的借方相抵；模型 `wallet.RewardHolds`、设计 4.4，评审 M-b），`TransferRules` 按 `余额 − 保留` 判断门槛与金额，
    审核通过后同样再查；App 的 `qb.app.wallet` 按同一规则给出保留中与预计可转时刻（改规则时两处一起改，`TransferHoldIT` 比较两者）。每人至多一笔未结束的转账（部分唯一索引）；
    发出作业先按 `transfer_group` 查询再以转账自己的幂等键创建；**只有 Stripe 拒绝创建（`TransferRules.certainFailure`）才冲正**，其他结果进入 `CHECK`、
    向 Stripe 核实后定论：查询"没有"只有在晚于发送结束（`sendEndedAt`）至少 10 分钟时才是定论。Stripe 把执行过的请求的回答（含错误）保存在幂等键下，
    同一键重试余额不足或 5xx 永远得到同样的回答：这两者立即结束作业（`StripeTransferHandler.kept`），次日的新转账用新键。
    被驳回或被撤回（含部分）的转账使用户暂停，直到财务以 `QB_TRANSFER_HOLD_RELEASE` 放行；审核人的意见不写进用户可读的字段。
    开通链接只由本人的数据视图读取（默认数据视图的读权限是不授予任何角色的 `qb.payout.system`）。大额审批的准备人是收款人（平台挡住自审）。撤回按累计金额只记一次。新增 Stripe 交互照 `PayoutJobs` / `StripeTransferHandler` 的写法。
- **报名费**（Q88b，`play.EntryFees`、`wallet.EntryFeeSettlements`，`docs/quizbuks/plans/Q88-sponsor-certifications-entry-fee.md` §11）：发布的 `entryFee`（0 = 不收，只有测验，随卡片冻结），受控开关 `qb.play.entry-fee.enabled` 只在保存草稿时读（`PublicationEditing.entryFeeEnabled`），不在答题路径上。
  开始时（`starting` 且卡片收费）在开始锁之前取用户钱包锁、一条语句读钱包行与持有期参数、`RunTemplate.when` 读可转出额（`qb.wallet.taker-transferable`），`EntryFees.shortfall` 不够即 422 `QB_ENTRY_FEE_SHORT`；写入时会话记 `entryFee`、钱包缓存按读到的版本减、`SaveChanges.when` 后 `LEDGER_POST` 2120 → 2170（来源 `QbAttempt`）；普通开始的语句数与锁不变（`AttemptStatementBudgetIT.aStartWithAnEntryFee`）。
  入账只由逐项任务 `qb.entry-fee-settle`（`QB_ENTRY_FEE_SETTLE`，`internal()`，商户钱包锁内 2170 → 2160，来源是现金钱包行）；不退。月报 `ENTRY_FEE` / `ENTRY_FEE_SETTLED`，核对 `ENTRY_FEES_UNSETTLED`。
- **平台费、预付 / 后付与账单**（Q87b，包 `fee`，在 Q87a 的现金账户之上，`docs/quizbuks/plans/Q87-fee-account.md`）：
  - **平台费只经 `QB_FEE_CHARGE`**（`subProcessOnly()`，权限 `qb.fee.system` 不授予任何角色）记入只追加的 `QbFeeCharge`（`qb_fee_charge_version`，`writeOnce`），新的收费种类只是加一个 `kind`（`FeeRules`），不另写记账；
    预付（`FeeRules.PREPAID`）即扣、不够即 `OPEN` 的账单，后付（`POSTPAID`）记应收、月初由 `qb.fee-invoice` 合成账单；判断"能否换学分 / 提交"只在 `fee.FeeGate`（一条 `QueryEntities.all` 读入驻行与未付账单，`FeeRules.Standing.blocked()`：预付有未付即拦，后付未付合计 > 信用额即拦，开关 `qb.fee.overdue.block-submit`），
    提交发布与换学分都调它（422 `QB_FEE_UNPAID`），不在别处自己判断。账单只从现金账户付（`QB_FEE_INVOICE_PAY`）；后付的费用在月底账单之前付清只经 `QB_FEE_PAY_EARLY`（每笔费用各开一张自己的 `PAID` 账单，子流程 `QB_FEE_SETTLE_ITEM`；月初的账单与所有按月份找费用的模板都排除已有自己账单的费用），不另建付款表；账单的 PDF（版式 `qb.fee_invoice`，开具方 `qb.issuer.*`）由商户按需签发（`QB_FEE_INVOICE_DOCUMENT`，任务不能签发单据：系统身份没有权限）。
  - 手工记费与作废只经 `QB_FEE_REQUEST`（`QbFeeRequest`，四眼：审批对象 `qb.fee-request`、规则 `qb.fee-request.review` 由 `QB_SETUP` 提出）与审批结果 `QB_FEE_REQUEST_APPROVAL_RESULT`（`internal()`，只认申请自己的审批请求，驳回按存储的请求与内容哈希判断）；作废 = 冲正该账单的全部分录（模板 `qb.system.fee-invoice-transactions` 按来源找）+ `VOID`，已付的退回现金。
  - 计费方式与信用额在商户入驻行上（`QbSponsor.billingMode` / `creditLimit`，`processOnly`），只经 `QB_OPERATOR_BILLING_SET`（`qb.fee.billing`，运营商以 `OperatorChecks.ownSponsor` 核对范围，理由必填）；缺省为受控参数 `qb.fee.default-billing-mode`。平台费记在记费时商户所属运营商名下（`QbFeeCharge.operatorId`），
    月结算的分成基数 = 抽成 + 平台费（`qb.system.operator-fees`，`QbOperatorSettlement.platformFees`）。新增一种过账时同步改月报 `qb.admin.monthly` 的分类（`FEE_CHARGED` / `FEE_PAID` / `FEE_VOIDED`）与 `AdminFinanceIT`。
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
  - **保留期**（Q10b）：导出（`periodTo`）、明细段与外部凭证号（`createdTime`）、充值（`paidAt`）、收据（`issuedAt`，迁移 V11）、入站 Stripe 事件、导出存档的文件（平台的 `GeneratedFile`，`createdTime`：QuizBuks 唯一的服务器生成文件）与收据、运营商结算单的 PDF 存档（平台的 `DocumentRun`，`issuedTime`，**只限这两种版式**：`only("layoutId", …)`，平台 17zd；证书 `qb.certificate` 不保留，删除账号时随之清除；新增单据版式时决定它是否加进这个范围；两者平台 16v-1）都以 `RetentionPolicy` 保留 10 年，自会计年度末起算（`RetentionIT`）。
- **商家分析**（`stats`，`docs/quizbuks/plans/Q9e-analytics.md`）：**答题路径不写任何分析数据**（开始、提交、超时与 `QB_VIEW` 不变，`AttemptStatementBudgetIT` 不变，`qb_attempt` 不加索引），分析只由逐项任务 `qb.analytics`（每 10 分钟，`JobDefinition.forEach`，模板 `qb.system.analytics-items` 给出项：按发布编号的块、结束后的整体重算、最后一项运行记录；每项一个事务的 `QB_ANALYTICS_ITEM` `internal()`，权限 `qb.stats.system` 不授予任何角色；不取业务锁、只写自己的三张表；一项失败不拖住其他项，Q9e1 评审 M3）聚合：
  每块**一条语句读两侧计数**、游标、新增浏览，把增量加进一个按小时（UTC）的桶（`AnalyticsBuckets`：跨过整点后的第一次运行加进上一次运行的小时，停机的增量记在一个小时并标 `gap`）；已完成的会话按水位线 `(submittedAt, attemptId)`、滞后 5 分钟（`AnalyticsLimits.LAG`）从现有索引读、每块至多 `quizbuks.analytics.max-attempts-per-chunk` 个，结束 30 分钟且没有进行中的会话时整体重算一次并定稿（结束一天后不论是否还有进行中的都定稿；完成多于 `quizbuks.analytics.max-recount` 的不重算，读完即定稿，使重算不超过读取时限）。只写变化了的行（审计照常；游标的题目合计 `auditMasked`）。
  **最小分组 k**（`qb.stats.min-group`，缺省 5、下限 3，`MinGroup`）：可推出个人作答的数字（每题正确率与难度、平均用时、超时人数）只在至少 k 个完成时给出，且公开值只在**新增至少 k 个完成后**才变化（含结束后的重算），两次查看之差推不出一个人；人数不受 k 约束（E-4）。k 的读法在每个模板中写同一段 SQL（`greatest(3, … 5)`），改规则时全部一起改并由 `AnalyticsIT` 覆盖。
  **题目按版本统计**（Q51b）：每个会话计入它开始时的版本（游标的 `questions` 按版本存放，`QuestionTotals`；`QbQuestionStat` 以（发布、版本、题号）为键），一个版本的题目数字只在该版本新增至少 k 个完成后变化；`qb.sponsor.analytics.questions` 的 `versionId` 只接受该发布用过的版本（卡片的与 `QbPublicationPastVersion` 的）。
  商家的模板 `qb.sponsor.dashboard` / `qb.sponsor.analytics.*`（`qb.stats.read`，只授予 `QB_SPONSOR`）一律经限本人的卡片视图 `sponsor:QbPublicationCard` 连接（普通表按 `sponsor_id` 读，不扫时态的发布，评审 M2），只给汇总、不读 `qb_attempt` 与资料；单个发布的概览的人数取任务的快照（游标），不随每次答题变化（评审 M1）；三张分析表没有商家数据视图（游标中有未公开的合计）。按日的日界是部署时区 `jabiz.fiscal-year-zone`（`Asia/Tokyo`），由任务记进 `qb_stats_run.zone` 供模板读取，不在模板中写死时区；它只能是 `UTC` 或地区名（偏移写法在 PostgreSQL 中方向相反，启动检查 `AnalyticsZoneCheck` 拒绝）。
- **App 中的商户**（Q68，`docs/quizbuks/plans/Q68-sponsor-in-app.md`）：App 的登录只接受 `QB_TAKER`，不加商户角色；同时是商户的人在 App 中只经权限 `qb.sponsor.app`（答题人与商户都持有）读本人的商户数据（`qb.app.my-sponsor`、`qb.app.sponsor-publications`、共用的 `qb.sponsor.dashboard` / `qb.sponsor.analytics.trend`），隔离只靠商户数据视图的范围，唯一的写入是 `QB_PUBLICATION_PAUSE` / `_RESUME`。动 Kudos 的（充值、提交发布、现金与兑换的处理）不开放给 App；给这个权限加东西前先确认它不动 Kudos、可撤回，并在 `SponsorInAppIT` 中覆盖。
- **资格**（包 `certification`，`docs/quizbuks/plans/Q69-certifications.md`、`Q70-referrals-and-rounds.md`）：资格不是兑现方式，种类只在 `Certifications.ALL`（`SPONSOR_APPLY`、`REFERRER`，以及 Q86 的三种协议理解 `TERMS_APP_UNDERSTOOD` / `CONTEST_RULES_UNDERSTOOD` / `TERMS_SPONSOR_UNDERSTOOD`，`legalKinds()` 写它们对应的法律文本种类，启动检查 `CertificationLegalCheck` 在目录版本落后于须同意的文本版本时**警告**；新增时同时改字典、管理系统扩展 `ids.ts` 与三语名字、两个前端 `qb/ids.ts` 的 `CERTIFICATIONS` 与 `certification.names`，以及八个模板中的 `VALUES` 目录：`CertificationTemplatesTest`）。
  **商户自己的认证**（Q88a，`certification.QbSponsorCertifications` / `SponsorCertifications` / `SponsorCertificationProcesses`、`publishing.PublicationRounds`，`docs/quizbuks/plans/Q88-sponsor-certifications-entry-fee.md`）：普通表 `QbSponsorCertification`（名字多语言、有效期、商户自己升的版本、停用），只经 `QB_SPONSOR_CERTIFICATION_*` 写入；
  在轮、记录、前置、证书四张表中的键是 `sp:<certificationId>`（这四列是文本，不是目录的码；`SponsorCertifications.resolve` 认两种键，`RoundHolders` 与认领在读记录与轮的**同一条语句**里读商户的行，答题路径的语句数不变、`AttemptStatementBudgetIT.aStartOfASponsorsRound`）；
  十个模板的持有 SQL 以 `Certifications.sqlValues()` 写目录 `UNION ALL` 商户的行（`CertificationTemplatesTest`）。商户只能把**草稿**登记为自己认证的轮（`QB_SPONSOR_ROUND_SET`，`owner` 为商户的运营商，提交后只能 `QB_SPONSOR_ROUND_CLOSE`），
  0 奖励的发布只在已是一轮时能提交（`TierCoverage.check(…, rewardOptional)`，提交与保存都读轮）；商户的前置只能要求自己 `ACTIVE` 的认证；证书的签发方是商户（`qb.me.certificate-issuer` 的参数 `issuerName`），公开模板给出 `certificationNames` 与 `issuerName`。
  受控开关 `qb.certification.sponsor-rounds.enabled`（缺省关）只在建立、修改与设轮的流程中读（`LoadParams.optional`），不在答题路径上；不做人工授予与收回。前端的名字只经 `qb/certifications.ts` 的 `certificationName`（目录的用文案、商户的用行）。
  协议答题（Q86）不是门槛：同意的流程与拦截一字不改，卡只由模板 `qb.app.understanding` 与受控参数 `qb.understanding.enabled` 决定显示；轮次照 Q69 / Q70 由官方账号出。是否持有只由 `CertificationHolding` 判断，记录 `QbCertificationRecord` 只插入、由认领 `QB_CERTIFICATION_CLAIM` 按需补记合格；**持有即不能答该资格的轮次**（Q70a，方案 B）：开始在锁外经 `RoundHolders` 判断（未认领的合格同样算，`CertificationProcesses.unrecordedPasses`，只读），两个模板写同样的规则；普通发布不多一条语句（`AttemptStatementBudgetIT` 不变）。
- **推荐广告**（包 `referral`，`docs/quizbuks/plans/Q70-referrals-and-rounds.md`）：发布上的"欢迎推荐"是一列 JSON `referral`（`publishing.ReferralPolicy`：名额、四种方式之一的一次性推荐奖励、说明、推荐人条件、被推荐人规则、期限），随政策在草稿中保存、提交即冻结、复制时一起复制、上架时写到卡片，审批内容与最大支出都算上它（不计入 `budgetCap` 与答题计数）。被推荐人的范围**只由商户自选的规则**（`publishing.RefereeRules`，含"不是熟客"）决定，平台不另加；推荐本身的成立条件（在答题之前、不是本人、不是商户与店员、已验证邮箱）与每位推荐人的上限（参数 `qb.referral.per-referrer-*`）在 `referral.ReferralRules`。
  **证书**（Q77，`plans/Q77-certificates.md`）：`QbCertificate` 只经 `QB_CERTIFICATE_ISSUE` 插入（持有由 `CertificationHolding.holding` 判断，同一段持有一张，锁同认领的 `qb.certification:<用户>`），PDF 是单据 `qb.certificate`（版式的权限 `qb.me`、数据只取本人的证书视图）；公开的核实页与认证页只经公开模板 `qb.public.certificate`（按核实键）、`qb.public.taker-page`（按公开名，设置中开启），不返回用户编号、不显示分数；新的认证种类要同时加三语的证书名 `qb.certification.<键>`（后端）与 App 的 `certificates.names.<键>`。
  推荐链接 `QbReferralLink`、推荐 `QbReferral` 只经 `QB_REFERRAL_LINK`（要持有资格 `REFERRER`，已生成的链接之后照常有效）/ `_CLAIM` 与内部的 `_REWARD`（逐项任务 `qb.referral-reward`，每 5 分钟）/ `_EXPIRE`（`qb.referral-expire`，每小时）写入，都先取 `qb.referral:<发布>`，之后只可能再取码池锁、商户钱包锁，从不取发布锁或开始锁：**答题路径一条语句也不加**，合格由任务事后发现。平台 Kudos 的推荐奖励来源是 `QbReferral`（`QbWallets.referralPosting`，不收抽成；月报 `REFERRAL_REWARD`，转账保留期与答题奖励一样算），商户自己结算的三种写现金、兑换或电子奖品的记录，**来源列 `sourceKind` 为 `REFERRAL`、`attemptId` 中存推荐的编号**（`reward.RewardSources`）：按 `attemptId` 连到 `QbAttempt` 或链到结果页的地方都要先看来源（站内信的去处为 `REFERRALS`）。
- **对话**（Q82，包 `conversation`，`docs/quizbuks/plans/Q82-conversations.md`）：一个答题者与一个发布至多一个对话 `QbConversation`（普通表，`(taker_id, publication_id)` 唯一，带发布方那一对），消息 `QbConversationMessage` 只插入，屏蔽 `QbConversationBlock`；都只经 `QB_CONVERSATION_*` 写入（默认数据视图 `processOnlyWrites`，读权限 `qb.conversation.read`，经 `OperatorScopes`）。**只有答题者能开启**（`OPEN`：完成过该发布或是它的推荐人、`SPONSOR` 的发布、没有屏蔽、商户未被封禁；再次开启即写进已有的、已关闭的随之重开），两侧以同一权限 `qb.conversation.use` 发送、关闭、已读，流程按 `ConversationRules.sideOf` 认出调用者是哪一侧（都不是即 404）；规则只在纯 Java 的 `ConversationRules`（纯文本 ≤ 1000、`BroadcastRules.hasAddress`、每侧每对话 24 小时 `qb.conversation.daily-cap` 条、`qb.conversation.close-days` 天无消息由逐项任务 `qb.conversation-expire` 关闭），模板的未读写同样的条件。锁只有 `qb.conversation:<答题者>:<发布>`（屏蔽另取 `qb.account:<答题者>`），不与发布、开始、钱包锁同时持有，答题路径不加语句。商户的回复发布 `qb.conversation.message`，由 `QB_MESSAGE_FROM_EVENT` 写站内信（`CONVERSATION_REPLY`，每对话每 UTC 小时一条）并推送 `qb.conversation-reply`；答题者的消息不发站内信或邮件。两侧只经各自的模板读（`qb.app.conversation*`、`qb.sponsor.conversation*`），模板从不返回对方的用户编号，答题者看公司名，商户看资料名。举报以理由 `MESSAGE` 带 `conversationId`（只接受本人的）。删除账号清空本人写的消息正文、删除屏蔽，对话行保留。
- **赞助权益**（Q89，包 `benefit`，`docs/quizbuks/plans/Q89-sponsored-benefits.md`）：一个商户为答题者购买另一个商户的商品、服务或数字产品（视频播放权）。身份上都是商户，能做什么只由两个认证决定：`BENEFIT_PROVIDER`（上架权益）、`BENEFIT_SPONSOR`（洽谈、建采购单）；持有只经 `BenefitSteps.holds`（`RoundHolders.loadHoldings`），模板 `qb.sponsor.benefit-access` / `-catalog` 写同一段 SQL（列入 `CertificationTemplatesTest`）。
  权益 `QbBenefitOffer`、洽谈 `QbBenefitConversation`（`(offer, sponsor)` 唯一）、消息 `QbBenefitMessage`（只插入）是普通表，采购单 `QbBenefitPurchase` 是时态表；都只经 `QB_BENEFIT_*` 写入（默认数据视图 `processOnlyWrites`，读权限 `qb.admin.benefit.read`），两侧只经按侧分开的模板读（`sponsor:` / `provider:` 数据视图，读权限 `qb.benefit.system` 不授予任何角色）。
  **洽谈允许联系方式与收款信息**（不套 `BroadcastRules.hasAddress`，需求方 2026-11-03）；**平台不经手采购款**：采购单只记录赞助方所说的数量、金额、付款日与凭证（`qb.benefit-proof`），由提供方确认它读到的修订号（`baseRevision`），确认之后的修改回到 `PENDING_PROVIDER`。锁：`qb.benefit-offers:<提供方>`、`qb.benefit-conversation:<权益>:<赞助方>`、`qb.benefit-purchase:<采购单>`，互不同时持有，答题路径不加语句。
  **发布用采购单**（Q89b-1）：只有 `OFFLINE_REDEEM` 可引用本人已确认的实物或服务采购单；草稿存采购单的快照（审批内容的 `benefit`）与履约方 `fulfillerId`，提交在发布锁之后取采购单锁、按 `BenefitPublishing.problems` 核对并写分配 `QbBenefitAllocation`，回到草稿或结束的由逐项任务 `qb.benefit-allocation-release` 退回（不订阅发布事件）。奖品的交付方只经 `QbRewards.handlerOf`（履约方，没有即发布方）判断：扫码、确认、店员与模板一律按它，不再直接比较 `publisherId`。
  **数字产品**（Q89b-2）：只配 `DIGITAL_PRIZE` 且只经码池（`POOL`）；码池由履约方装与收回（`DigitalCodeProcesses.requireHandler`，以装码人的编号密封、以 `handlerOf` 打开），发布方只看计数；提供方经 `fulfiller:QbPublication` 的模板（`qb.sponsor.benefit-publications`、`benefit-digital-*`）读。共用码与手工发送在 Q33b 之前拒绝（`QB_BENEFIT_DIGITAL_DELIVERY`）。
  **答题者的通行码**（Q89c，`reward.PassProcesses`，对所有到店兑换）：`QbRedemptionPass` 每个奖品一行，只存密钥与数字（连同履约方）的哈希，60 秒、一次；签发与交付在奖品锁内，查询只认本柜台（`handlerOf`）、按数字只在本柜台的有效通行码中找、每人每分钟 10 次；查询的输入名不能与签发的输出同名（平台按名遮蔽，较严者胜）。App 的链接是 `/pass/<密钥>`（`scanTarget` 认得它）。
  **暂停与异常**（Q89d-1，`benefit.IncidentProcesses`）：暂停采购单先以子流程 `QB_PUBLICATION_BENEFIT_PAUSE` 按发布编号暂停用到它的发布、再取采购单锁（锁序同提交）；作废未用的奖品只由逐项任务 `qb.benefit-void` 做；采购单暂停时发布方不能恢复发布；暂停与恢复不改修订号。异常 `QbBenefitIncident` 两侧都能记录与写结果，模板只按"哪一方"显示。
- **科技树**（Q85，包 `tree`，`docs/quizbuks/plans/Q85-tech-tree.md`）：**树不建模、只读**——节点是商户提交过的发布，边是 Q76 的前置条件；一个人在一个节点上的六态（`LIT` / `ANSWERED` / `IN_PROGRESS` / `UNLOCKED` / `LOCKED` / `CLOSED`，按此顺序取第一个成立的）
  只在纯 Java `tree.TreeStates` 与模板两处写（`qb.sponsor.tree` 的人数、`qb.sponsor.tree-people` 的名单、`qb.sponsor.tree-person` 一个人的树、`qb.app.sponsor-tree` 答题者自己的树），`TreeIT` 对账；没有新表、流程、迁移，答题路径不加语句。
  商户看客户的范围同交易页：显示名与 `userKey`（`md5(商户:用户)`），不给用户编号、分数与缺哪条前置；"锁定"的名单只含本商户的客户（在本商户任一发布上有已完成会话的人）。画面是两端共用的内联 SVG（`qb/TechTree.tsx`、`qb/tree.ts`），不引入布局库。
  认证前置（Q86）在树上是**平台节点**（`nodeKind: 'certification'`，编号 `cert:<键>`，`TreeStates.of` 多一个输入：持有的键集合）：不计人数、不列名单、不计入进度；平台之树就是总部官方账号的主页树，普通参数 `qb.tree.platform-handle` 指向它，没有新机制。
- **条款、隐私与帮助**（`messaging`，`docs/quizbuks/plans/Q7-messages-legal.md` Q7b）：法律文本的草稿 `QbLegalDoc`（每种一份，`QB_SETUP` 建立明确标注 `[TEST PLACEHOLDER]` 的占位，不发布）与 FAQ `QbFaqEntry` 由内容管理员在管理系统的通用页面直接编辑；
  **法律文本只经 `QB_LEGAL_PUBLISH` 发布**（每次二次验证，版本 `QbLegalVersion` 只写一次；必须带页面读到的草稿版本 `baseRevision`；生效时间至多 `qb.legal.max-announce-days`（受控参数，未发布时 180）天后），
  **预告错了只经 `QB_LEGAL_WITHDRAW` 撤回**（生效前、带理由，`QbLegalWithdrawal` 只写一次；撤回的版本永不生效、不要求同意，版本号不再使用）；两者先读草稿或版本（种类不可变）再取命名锁 `qb.legal:<种类>`。
  规则只写在纯 Java 的 `LegalRules`（当前版本 = 生效时间已到、未撤回的最大版本号，没有状态与任务；须同意版本 = 其中须同意的最大版本号，第一版总是），
  模板 `qb.system.consent-state`、`qb.me.consents` 与公开的 `qb.public.legal*` 写同样的规则（改规则时一起改）。**同意按版本记录**（`QbConsent`，只插入，`ConsentChecks.record`）：欢迎页（`QB_PROFILE_SAVE` 第一次完成资料）、入驻申请（`QB_SPONSOR_APPLY`，审批内容含 `termsVersionNo`）
  与登录后的重新同意（`QB_CONSENT_ACCEPT`，只接受本入口的种类与当前版本）。**资金与合约类写入**（充值、提交发布、开通收款；以后新增的同类流程照此）以 `.steps(ConsentChecks.sponsor())` / `.steps(ConsentChecks.taker())` 在锁外再查（422 `QB_CONSENT_REQUIRED`），开始答题不查（Q7-2）。
  没有已生效版本时不要求同意（开发、测试与新装）。匿名读取只经公开模板 `qb.public.*`（公开视图：法律文本的已发布版本、已发布的 FAQ；草稿没有公开视图，公开视图不放发布人与哈希），`JABIZ_PUBLIC_ENABLED` 缺省开。
  场景的主键按实体分计数（平台 07 §3.2）：`QB_SETUP` 多建一个参数、规则或角色不再移动用户、发布与尝试的键，答题场景的答案（按显示位置写）因此不随之改写；只有同一实体在场景更早处多插一行时才要重排。
- **有奖活动规则与举报**（Q49a，`docs/quizbuks/plans/Q49a-contest-report.md`）：有奖活动规则是法律文本的种类 `CONTEST_RULES`（不在 `APP_KINDS`：不要求同意），有奖励档位的测验页面与 App 账户页链接 `/legal/contest-rules`；正文写明 Apple / Google 不是主办方。
  举报只经 `QB_REPORT_SUBMIT`（答题者）与 `QB_REPORT_RESOLVE` / `QB_OPERATOR_REPORT_RESOLVE`（总部与运营商，二次验证，按测验一次关闭全部待处理的举报并关闭待办）；`QbReport` 是普通表，数据视图经 `OperatorScopes` 限定；举报人只给审核者看，不给商家。下架测验仍用已有的管理员与运营商流程。
- **站内信与公告**（`messaging`，`docs/quizbuks/plans/Q7-messages-legal.md` Q7c）：**个人消息 `QbMessage` 只由事件的消费者写**（`QB_MESSAGE_FROM_EVENT`，`internal()`；收款提醒为逐项任务 `qb.payout-reminder`），
  从不在答题、支付的锁内写（`AttemptStatementBudgetIT` 不变）；消费者重读记录、仍成立才写，`sourceKey` 唯一使同一事件只成一条，7 天以前的事件跳过（重放不补发）；
  标题与正文创建时按 `qb.msg.<种类>.title|body` 以三种语言渲染（`MessageTexts`，Q7-15），不含他人的数据。新的消息种类：加事件订阅、种类常量、三语文案与 `InboxIT` 的用例。
  公告与商家通知**不按人复制**：发送时写一行冻结的 `QbBroadcastFeed`，收件箱读时匹配。匹配的条件写在 `qb.app.inbox`、`qb.app.inbox-unread` 与 `qb.system.broadcast-audience` 三处（改一处即改三处，`InboxMatchIT` 的真值表检查三者）。
  **收件箱只读 `live` 的行**（评审 H1）：过期的与发布已结束的通知由每分钟的逐项任务 `qb.broadcast-expire`（`QB_BROADCAST_EXPIRE`）关掉，部分索引 `(audience, expires_at) WHERE live` 因此只有当前的行；模板不收页面的时间（评审 L1，`at` 不是参数）。
  未读徽标从读者的 `QbInboxState.readThrough` 之后数起（`QB_MESSAGE_MARK` 在写入已读之后以 `qb.app.inbox-unread` 的 `oldestUnread` 推进它，至多落后 10 分钟，从不后退；它不是阅读，列表与统计不用它）。
  个人消息一律是纯文本（评审 H2：参数中有商家写的发布名）；只有平台的公告按 Markdown 显示。商家通知在其发布暂停或预算不足时隐藏（卡片 `SCHEDULED` / `LIVE` 才显示，评审 M1）。
  商家的统计只读每日快照 `QbBroadcastStats`（任务 `qb.broadcast-stats`，评审 M2）：到达人数向下取整（不足 10 不显示），阅读不足 5 不显示，到达不足 `qb.broadcast.stats.min-audience`（50）时不显示点击与开始答题；平台管理系统中为确切的实时人数。
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
  **封面**：AI 生成封面已撤销（Q98a，`docs/quizbuks/plans/Q98-covers.md`）；作业类型 `qb.ai.cover` 只为让撤销前排队的作业结束而保留（`AiCoverWithdrawnHandler` 立即以 `QB_AI_COVER_WITHDRAWN` 失败、不调用提供方），`QbAiJob` 的封面列与种类 `COVER` 留作历史，丢弃与过期照常清空封面列。不再加回 AI 画图。
  **PDF 资料**（Q8b）：PDF 资料作为作业的文件输入（`StartExternalJob…files`，平台按读取权限检查）以文档块发送原文件，不进作业输入；每个与合计的大小在请求时查（`QB_AI_PDF_TOO_LARGE`）；**服务器不解析 PDF**（Q8b 评审 H1/H2：解析器可被构造的文件耗尽内存，页数可以谎报；不引入 PDF 库，ArchUnit `quizbuksParsesNoPdf`），请求按页数上限预留月 Token（`AiLimits.reservedInput` / `estimate(…, pdfPages)`，每页 `ClaudeApi.TOKENS_PER_PDF_PAGE`，预留记在作业输入与参数 `reservedInputTokens` 中），处理器在付费调用前以提供方免费的 `count_tokens` 数完全相同的请求（`ClaudeCalls.counted`）：超过预留即 `QB_AI_PDF_TOO_LONG`，提供方说请求过长（上下文或页数）同样、说 PDF 读不了即 `QB_AI_PDF_UNREADABLE`（都永久、不计费；以字段路径开头等其他 400 一律 `QB_AI_REQUEST_INVALID`，`ClaudeErrors`），计数失败照常重试（用掉一次尝试）；没有回答的尝试按这个数计入。**带 PDF 的请求不带提供方的回退**（复审 M-a），付费调用因此正好是计过数的请求。页数上限须放得进上下文窗口与提供方的页数（启动检查 `AiPdfLimitsCheck`）。本地桩同样实现 `count_tokens`。作业输入 ≤ 256 KiB（`AiQuestionsJob.MAX_JOB_INPUT_BYTES`，文字 ≤ `ClaudeApi.MAX_INPUT_BYTES` = 250 KiB）。题目与封面共用限额，每模版每种类同时一个；处理器只经上下文读写文件（ArchUnit `handlersReadFilesThroughTheirContext`）。
  参数 `qb.ai.enabled`（缺省 false）、`qb.ai.model`（只能是 `claude-opus-5-5` / `claude-sonnet-5-5` / `claude-haiku-5-5`：参数的种类与每次请求都查）、`qb.ai.effort`、三个限额都是**受控参数**；任何一个缺少或不合格即 `QB_AI_UNAVAILABLE`。作业的完成、失败与过期流程 `internal()`，权限 `qb.ai.system` 不授予任何角色；平台管理系统的 `qb.admin.ai.read` 给内容与超级管理员。`dev`、测试与 e2e 的提供方是本地桩（`tools/quizbuks/claude-stub`、IT 的 `AiItSupport`），从不访问网络。
- **外部密钥**（`STRIPE_*`、`ANTHROPIC_API_KEY`）只来自环境变量（`deploy/quizbuks/README.md`）；测试与 `dev` 用固定的假值，仓库中没有真实密钥。
- **部署的安全设置**（Q10b，`deploy/quizbuks/README.md`"密钥与部署"）：镜像入口脚本在平台密钥或首个管理员密码缺少时拒绝启动（只有 `QB_LOCAL_SECRETS=true` 的本机才生成，compose 缺省 `false`，正式模式一律不生成，管理员密码不打印；生成时导出 `QB_SECRETS_GENERATED=true`，评审 L5）；
  启动检查 `wallet.DeploySafetyCheck`（类别 `QUIZBUKS`）：可信代理为空时正式模式报错（除非 `quizbuks.deploy.direct-exposure=true`）、过宽的网段一律报错（IPv4 短于 /8、IPv6 短于 /32、整个 IPv4 的映射，前缀照平台读法，评审 L6），正式模式下关闭管理二次验证或 `QB_SECRETS_GENERATED=true` 报错，`dev` 只在回环地址与本机邮件地址下启动；
  数据库密码没有缺省值。职责分离规则由 `QB_SETUP` 提出（`setup.QbSodRules`），`QbRoles` 的角色不得违反它们（`QbRolesTest`）；超级管理员不做业务审批（不持有 `approval.decide`）。
  **受保护的授权**（平台 16u，D51）：管理员按 `security.user-role.write` / `security.role.write` 计，即超级管理员；两位之后建立期结束，受保护的变更由一位超级管理员申请、另一位以 `security.protected-change.decide` 审批（`docs/quizbuks/operations.md` §3）。
  `QB_SETUP` 直接写入角色的权限，建立期结束后给已有持有人的角色加受保护的权限会被拒（先经 `SEC_ROLE_PERMISSION_ADD` 审批）；测试以 `AccountItSupport.assignRole` 照此分配（第二位超级管理员之后由另一位批准）。

## 5. 前端（`quizbuks-web/`，`docs/quizbuks/plans/QP-pages.md`）

- **两个 SPA，各是独立的 pnpm 工程**（自己的 `package.json` 与 `pnpm-lock.yaml`，经 `link:../../frontend/packages/{client,ui}` 用平台的包；不用 pnpm 工作区）：
  `app`（答题 App，`/`，入口 `app`，PWA）与 `sponsor`（发起方页面，`/sponsor/`，入口 `sponsor`）；`build.gradle.kts` 的 `spa(...)` 构建它们。`application.yml` 写出完整的 `jabiz.web.spa`（Q19、Q99：App 自己的内容安全策略多 `frame-src` 七个视频网站的播放器），三个前端的路径、入口与 PWA 须与 `build.gradle.kts` 一致（启动检查 `WEB` 告警）。
  共享依赖的版本必须与平台前端相同（`pnpm app:check`，Gradle 中 `spaCheckRoot` / `spaCheckSponsor`，在 `check` 中）。指南：平台 `docs/guide/app-spa.md`。
- **品牌档案**（`docs/quizbuks/plans/Q54-brand-profile.md`）：名称、标志、图标、配色只在品牌档案 `quizbuks-web/brands/<品牌>/` 中（构建时以 `QB_BRAND` 选择，缺省 `quizbuks`）；页面与消息以该语言的名字 `BRAND.names.<语言>`（Q61，`docs/quizbuks/plans/Q61-local-names.md`；SPA 的 `src/qb/brand.ts`、Shopify 内嵌页的 `src/brand.ts`、静态站的 `src/brand.mjs`）称呼产品，`BRAND.name`（QuizBuks）只用于只能有一个名字的地方（PWA 清单与安装名、发件人、版权行），不写死名称（`brand-profile.test.ts` 与 `brands/brand.test.mjs` 检查，含中文、日文名），静态站的品牌色在档案的 `site.css`；后端消息以记号 `@QB_BRAND_NAME@` 称呼产品（`processResources` 按文件的语言替换），客服邮箱与站点地址的缺省取 `setup.Brand`；平台管理系统扩展以 `BRAND_NAMES.<语言>`（`src/brand.ts`，档案经 `brands/admin.mjs` 生成的 `brands.generated.ts`，改档案后重新生成）；新品牌复制 `brands/sample`（Q54 计划 §7）。
- **品牌与插画**（`docs/quizbuks/plans/Q32-visual-refresh.md`）：颜色只在品牌档案的 `brand.css` 中以覆盖 token 表达（`--primary` 等与品牌 token `--qb-*`，亮暗两套，对比度按平台 12 §11，`brand.test.ts` 检查每一份档案），页面只用 token 与 `qb-*` 颜色类，不写死色值；
  插画是共用的 `src/qb/Illustrations.tsx` 的内联 SVG（`aria-hidden`，颜色取 token），不引入外部图片或照片；没有封面的发布用 `CoverPlaceholder`。欢迎轮播（`pages/IntroPage.tsx`）只在未登录首次打开 `/` 时显示，`localStorage` 记住。
- **共用代码只在 `src/qb/`**，两个 SPA 中各一份、**完全相同**（CI `diff -r`）：改一处即复制到另一处。流程、模板、数据视图、字典与文件策略的名字只写在 `qb/ids.ts`；
  流程的输入输出对照 Java record 手写在 `qb/api.ts`（答题的在 `qb/play.ts`；QuizBuks 没有自己的接口，因此没有 OpenAPI 类型），改了 record 就改这里并由 e2e 覆盖。
  **只有一个 SPA 用到的功能**（发起方页面的内容工坊）在该 SPA 自己的功能目录中照此各写一份：名字在 `<功能>/ids.ts`、手写类型与调用在 `<功能>/api.ts`（如 `sponsor/src/content/`），不进 `qb/`。
- **商户资料包**（Q100，`page.ProfilePackage` / `ProfileTransferProcesses`、发起方页面 `profile/transfer.ts`，`docs/quizbuks/plans/Q100-profile-package.md`）：导入只经 `QB_PROFILE_IMPORT_CHECK` / `QB_PROFILE_IMPORT`，写入只以子流程运行商户中心各页本来的流程（规则与手填相同、一个事务）；**导入从不改变审核**：入驻未通过时基本信息只填进申请表，入驻未通过或主页审核中时不写主页；商品整体替换，先按商品编号（`code`，商户自定、可选、同一商户内唯一，Q100e）再按 `productId` 认商品，删除关联着 Quiz 的商品前商户须确认；邮件服务器的密码从不进资料包。Quiz 文件（版本 3）以商品编号（没有即 `productId`）写出 Quiz 的商品，导入时只关联导入者自己的、找不到的列出。商户中心各页增减字段时同时改资料包的读写（`ProfilePackage`、`exportProfile`）与测试。**商品上限**是普通参数 `qb.products.max`（缺省 50，F-C11，`docs/quizbuks/plans/impl/F-C11.md`）：`QB_PRODUCT_SAVE` 与资料包检查都以 `LoadParams` 读、经 `SponsorProductProcesses.maxProducts` 取值（纯 Java 的 `ProfilePackage.read(document, 上限)` 由流程给出上限），发起方页面的商品页经模板 `qb.sponsor.product-limit` 读同一个值（读不到即不限制、由保存判断）；读本人商品的查询不按上限截断（调低上限后已有的照常修改与删除）；代码中不再有商品个数的常数。
- **商户中心**（Q79，`sponsor/src/profile/`，`docs/quizbuks/plans/Q79-sponsor-hub.md`）：商户自己的设置页都是页组 `/profile` 的节（概况、基本信息、公开主页、店员、邮件服务器；商品与认证自 F-C1c 起是资源库 `/assets` 的标签，旧地址经 `MOVED_PATHS` 转向），由 `ProfileLayout` 给出页头与子导航，节内只用 h2；新的商户设置页加为一个节（`profile/paths.ts` 的 `PROFILE_SECTIONS` 与文案 `profile.nav.*` / `navHint.*`），不另加菜单项；旧地址在 `LEGACY_PROFILE_PATHS` 中转向。概况的完成度清单是纯函数 `profile/checklist.ts`，只读各节已有的读取。
- **内容工坊**（`sponsor/src/content/`，QP3a）：每次编辑带上页面所见的 `revision` 作 `baseRevision`；`QB_QUIZ_CHANGED` / `QB_ORDER_MISMATCH` 或读到别人的新 revision 即提示重新加载，
  重新加载之前不再提交。上传只经 `uploadFile(内容策略)`（`qb.content.image|pdf|audio`），文件名用中性的 `<种类>.<扩展名>`（`content/api.ts` 的 `neutralName`，Q10b：答题人能读内容文件的元数据；商家头像同样以 `avatar.<扩展名>` 上传，内容与头像的策略都声明 `privateMetadata()`），页面的类型与大小检查只为省一次往返；图片按文件自己的变体显示（小图没有变体，`ContentImage`）。
  Markdown 只经 `MarkdownView`，外链只放 http(s) 并在新标签页打开。e2e 的数据准备（文件、完整的 Quiz 与版本）在 `e2e/tests/content.ts`，发布的测试从它开始。
  题目列表是只读的折叠行（Q78，`content/Disclosure.tsx`：一次展开一题，操作在行上，排序经"移到…"下拉发出整表顺序；上方摘要行）；AI 出题面板只在有作业时展开成卡片；页头一次只显示一条告警。新的列表项照 `Disclosure` 写。
- **发起方页面的列表**（Q80）：新的 `DataTable` 都给 `meta.card`（一列 `title`，按需 `subtitle` / `badge` / `actions` / `hidden`）与 `storageKey="qb.sponsor.<列表>"`，手机上缺省卡片、桌面表格（平台 17zc，不自写卡片）；行的 testid 经 `rowProps` 给出，卡片照样带着。菜单里的列表页不用原生 `Table`（图表的数据表、数字对照表除外）。例外：模版与发布保留 Q34b 的画廊卡片（`ListView.tsx`），不加第二种。
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
- **收件箱与消息中心**（Q7c，App `src/inbox/`、发起方页面 `src/notices/`）：未读徽标经 `useUnread`（进入、回到前台、每 5 分钟重读，离开即停）；打开即 `QB_MESSAGE_MARK`（已读），按钮另记点击；
  收件箱的模板不收页面的时间（服务端的现在）；徽标的查询不随窗口获得焦点再问一次。商家通知的编辑器只显示保存给出的问题，不重算规则，只有一个可选发布时预先选中它。
- **AI 出题页**（Q8a，`sponsor/src/ai/`，名字在 `ids.ts`、手写类型与调用在 `api.ts`）：编辑器题目页上方的面板与对话框、候选校对、仪表盘的用量卡（`AiUsageMetric`，在 Q9e2 的卡片位）；Q8b 的封面面板（`AiCoverPanel`，模版页基本信息之下，作业模板按 `kind` 读）选定后经 `editing.edited` 记下新的 revision，`HeadingForm` 随读到的 `cover` 更新（其他未保存的改动保留）。候选只以纯文本显示（输入框与文本框，不经 `MarkdownView`）；进行中每 5 秒查询、至多 5 分钟（按服务端时钟），离开即停；对话框只在合计不超过上限时可提交，判断仍在服务端。
- **认证与账户只用平台的积木**（`@jabiz/ui` 的 `SignInForm`、`SignUpForm`、`MailLinkPage`、`OidcCallback`、`StepUpProvider`、`ChangePasswordForm`、`MfaSettings`、`SessionList`、
  `LinkedIdentities`、横幅），QuizBuks 不写认证代码。邮件链接页在各自的 `auth/verify-email|join|password-reset`。路由是 `qb/router.tsx` 的小路由。
  **入口页**（平台 17zk，P4）：两个 SPA 的登录、注册、第二步、找回密码、第三方回调、全部邮件链接页（`MAIL_LINK_PAGES`）与退订都包在各自 `App.tsx` 的 `EntryPages`（`EntryLayoutProvider`：品牌、三语左栏 `entry.*`、提示、页脚 `EntryLegalLinks`、学者 ↔ 发起方）中；登录方式的顺序只在 `SignInForm` 的 `order`（学者 `signin.ts` 的 `signInOrder` 按语言），新提示加进 `notices`，不在模板外另放；发起方的名称只取消息 `brand`，用户看得到的文字不写“后台”“管理画面”“console”。
- **金额只经 `qb/kudos.ts` 的 `formatKudos`**（`1,234 Kudos` / `1,234 学分` / `1,234 クドス`，随界面语言；单位单独出现时用 `kudosName()`），不写 JPY 或其他币种名。服务端的 `QB_*` 文案已按请求的语言翻译，页面直接显示（`qb/problems.ts`）。
- **文案**：SPA 自己的在 `src/messages.ts`（命名空间 `app` / `sponsor`），共用的在 `qb/messages.ts`（`qb`），三语齐全，测试检查键一致且非空。
- **界面**只用 `@jabiz/ui` 的组件与主题 token（不写颜色值）；亮、暗两种外观都过 axe（e2e `a11y.spec.ts`）。检查只用平台导出的辅助函数（Vitest `@jabiz/ui/testing` 的 `expectAccessible()`，e2e `@jabiz/ui/testing/playwright`，经 `e2e/tests/support.ts`），不另写；
  管理系统扩展的测试用 `@jabiz/admin/testing` 的 `expectAccessible()`（平台 16w-1；只在测试中，扩展的 lint 拒绝页面中引用它与 `@jabiz/ui*`）。App 按 ≤ 430 px 设计，也检查 1280 px。
- **检查**（每个 SPA 目录下）：`pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test`；平台前端目录下 `pnpm app:check ../quizbuks-web/app`（`sponsor` 同）。
- **管理系统扩展**（`backend/quizbuks/admin-extension`，Q9a，平台 12 §9 与决策 D22）：只经 `@jabiz/admin` 引用平台，没有 `package.json`；路由在 `/qb/**`；名字在 `ids.ts`，
  手写类型与读取在 `api.ts`（快照类型 `snapshot.ts` 抄自发起方页面，随 `QuizSnapshots` 一起改）。只读默认数据视图与 `qb.admin.*` 模板，决定只经平台的 `ApprovalPanel`；
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
  **站内信与公告**（Q7c）：管理系统扩展的写入要已验证的邮箱（首个管理员没有），测试以 `contentAdmin`（`tests/legal.ts`，角色 `QB_E2E_CONTENT`、不绑定二次验证）登录平台管理系统；每次要求二次验证的撤回经 API 以 `legalAdmin` 运行。e2e 的公告用推广（只到发送前注册的人），不让以后测试新建的答题人收到。
  启动方式与环境变量照 `.github/workflows/quizbuks.yml` 的 `e2e` 作业；然后 `E2E_BASE_URL=… E2E_ADMIN_USER=… E2E_ADMIN_PASSWORD=… pnpm e2e`。测试只增数据，可重复运行。

- **Google AdSense**（需求方 2026-11-02，`deploy/quizbuks/DEPLOY.md`"Google AdSense"）：只经 `qb/ads.ts` 的 `loadAds`（App 中 `skip: nativeShell() !== null`，商店外壳与自动化浏览器中不加载）与招商网站 `site.js` 的 `loadAds`；
  页面头部的 `google-adsense-account` 标记、三个前端 CSP 中的广告域名（`QuizbuksAppIT` 检查）、Caddy 的 `/ads.txt` 一起改。不在 Shopify 嵌入页、店面、`/shopify-app/` 与维护页加广告。
  品牌自己的站点（品牌档案 `siteUrl` 的主机及 `www.`）不显示测试系统的横条：两个 SPA 以 `qb/environment.ts` 的 `siteEnvironment()` 经 `configureClient({ environment })`（平台 17ze）改写，登录页与发起方页面的外框随之；上线前 quizbuks.com 由 staging 回答（`deploy/quizbuks/DEPLOY.md`），staging 自己的地址照旧显示横条。
- **商店 App 的外壳**（Q49，`quizbuks-mobile/`，`docs/quizbuks/plans/Q49b-android.md`）：只上架答题者的 App（`/taker/`），发起方页面与充值不进 App。
  Android 是 TWA（`quizbuks-mobile/android`，设置只在 `twa-manifest.json`，构建由它读取；App Links 只认 `/taker/` 前缀），启动地址与 App Links 都带 `shell=android`；
  App 中只以 `src/shell.ts` 的 `nativeShell()` 判断是否在商店 App 中（参数或 Chrome 的 `android-app://` referrer，按标签页记住），在外壳中不显示任何安装提示（`install.tsx`）。
  `assetlinks.json` 由 Caddy 站点提供（`deploy/quizbuks/assetlinks.sh`，环境变量 `QUIZBUKS_ANDROID_PACKAGE` / `QUIZBUKS_ANDROID_CERT_SHA256`）：维护模式中也能取到；需求方 2026-11-02 决定不改用平台 17l 的 `jabiz.web.native-apps`。
  上传密钥与 Play 服务账号只在 GitHub Environment `play` 的 secrets 中（workflow `quizbuks-mobile.yml`）。改了 App 的前缀、包名或颜色时两边一起改（`scripts/consistency.test.mjs`）。
  iOS 是 Capacitor（`quizbuks-mobile/ios`，设置只在 `capacitor.config.json`，Swift 的 `QuizBuksScope` 让网页只停在 `/taker/`、其他地址交给 Safari；User-Agent 带 `QuizBuksApp/ios`），`shell.ts` 同样认它；`apple-app-site-association` 同样由 Caddy 提供（`deploy/quizbuks/apple-app-site-association.sh`，变量 `QUIZBUKS_IOS_APP_ID`）；签名只在 workflow `quizbuks-ios.yml` 中以 App Store Connect API 密钥进行（Environment `appstore`）。
  推送通知（平台 17k，D69）：profile `push`（`application-push.yml`，VAPID 密钥只来自环境变量）；只有中奖通知 `MessageProcesses.PRIZE_WON`（`qb.prize-won`，入口 `app`），由 `QB_MESSAGE_FROM_EVENT` 在写下"中奖"的那条消息时以 `SendPush` 发出，同一奖品只推一次（`prizeWon`）；答题者在"账户 → 通知"以平台积木 `PushPreferences` 开关。新的推送先加 `PushTemplate` 与三语消息 `push.qb.<名>.*`，不自己调用推送服务。 iOS App（平台 17k-b）：profile `apns`（`application-apns.yml`，与 `push` 并用，APNs 的 `.p8` 只来自环境变量），外壳经 `configureClient({ nativePush })` 交出设备令牌（Q49c-2）。
  商店资料（Q49d，`docs/quizbuks/store/`）：说明文字三语同构并由 `tools/quizbuks/store/listing.test.mjs` 检查；数据安全表按隐私政策逐格写，政策或 App 收集的数据改了就同步；截图只由 `quizbuks-web/e2e/store`（workflow `quizbuks-store.yml`）生成，不提交。

## 6. 测试与命令（在 `backend/` 下）

- `./gradlew :quizbuks:check`（测试 + `platformCheck`）；单个类：`./gradlew :quizbuks:test --tests '*SetupIT'`。
- 集成测试连接真实 PostgreSQL（同平台：Testcontainers 或 `JABIZ_TEST_DB_*`），测试类放在 `com.jabiz.quizbuks.*` 下。
- 场景回放：`src/test/resources/scenarios/qb/**.yml`，`./gradlew :quizbuks:test --tests '*ScenarioTest'`；确认变化正确后加 `-Dscenario.update-snapshots=true`。
- **端到端在本机跑**（仓库根目录）：`tools/quizbuks/e2e.sh run -- tests/<文件>.spec.ts`（全部：`run`；`deploy/quizbuks/README.md` "本机 e2e"）。它以 CI 同一个 `quizbuks-web/e2e/app.env` 启动 jar、数据库、两个 Mailpit 与两个桩。
  **改了页面、流程的输入输出或 e2e 本身的 PR，先在本机跑相关的用例再提**，"本地只做了类型检查、由 CI 运行"不算完成；CI 红的不合（`DEPLOY.md` "合并前的门槛"）。
  应用在 e2e 中的环境变量只改 `app.env`（两边同源），不在 workflow 里另写。
- 性能（不在 `check` 中）：进程内基线 `./gradlew :quizbuks:perfTest`（`PlayThroughputIT`；分析任务 `--tests '*AnalyticsPerfIT'`，`docs/quizbuks/perf/Q9e-analytics.md`）；对运行中的 jar 压测单个热门发布 `./gradlew :quizbuks:loadTest`（参数 `LOAD_*`，含客户端时限 `LOAD_TIMEOUT` 与照 App 重试忙的 `LOAD_RETRY_BUSY`；方法与结果见 `docs/quizbuks/perf/Q10a-load-test.md`，准入之后的重测在其 §8、16p-1 之后 §9、锁拆分之后 §10、16p-2 之后 §11）。答题锁内的时间取决于语句条数（每条约 0.8 ms），改答题流程时不要在开始锁或发布锁内增加读取；语句数的确切值在 `AttemptStatementBudgetIT`。
  `LOAD_METRICS=yes` 时从只绑定回环地址的独立管理端口读取指标（jar 以 `-Dmanagement.server.address=127.0.0.1 -Dmanagement.server.port=8081 -Dmanagement.endpoints.web.exposure.include=health,info,metrics` 启动，工具读 `LOAD_METRICS_URL`，缺省 `http://127.0.0.1:8081`，不是回环地址即拒绝，`LOAD_ALLOW_REMOTE` 也不放行；指标绝不在应用端口上暴露：那里不要认证，根 CLAUDE.md 与设计 13 不允许匿名的指标端点；也不写进仓库的配置），报告每次完成的许可持有时间与每次运行的语句数（`jabiz.process.statements`），并报告压测期间本机的 CPU；16p-1 之后的重测在报告 §9。

## 7. 运行

- 本地：`QB_LOCAL_SECRETS=true docker compose -f deploy/quizbuks/compose.dev.yaml up -d --build`（compose 缺省不生成密钥，Q10b L5；数据库端口 5440、应用 8080：App 在 `/taker/`、发起方页面在 `/sponsor/`、平台管理系统在 `/admin/`、招商站在 `/biz/`，裸域名转到 `/biz/`）；
  或只起数据库（`… up -d db`）后在 `backend/` 下 `./gradlew :quizbuks:bootRun --args='--spring.profiles.active=dev'`，前端开发时在 `quizbuks-web/app` 或 `sponsor` 下 `pnpm dev`
  （端口 5175 / 5176，`/api` 代理到 8080）。
  compose 没有 `.env` 时 Stripe 未配置：镜像的入口脚本 `deploy/quizbuks/entrypoint.sh`（再交给平台的 `docker/app-entrypoint.sh`，不改平台脚本）只在测试模式、
  `QB_STRIPE_PLACEHOLDERS=true`（compose 缺省）时为未设置的 Stripe 密钥填占位并告警；改它时同步改 `tools/quizbuks/test/entrypoint.test.sh`（CI `deploy`）。
- 首次启动后，管理员登录 `/admin/` 执行一次 `QB_SETUP`（流程页面）。
- **服务器部署**（共用的 Docker 服务器，Caddy 为唯一入口，共用的 PostgreSQL）：`deploy/quizbuks/DEPLOY.md`。两个环境：推送 `1.3/quizbuks` 部署 **staging**（`APP_NAME=quizbuks-staging`、`staging.<基础域名>`、库 `quizbuks_staging`），
  推送 `1.3/quizbuks-release`（只由 `1.3/quizbuks` 合并进来）部署 **production**（`APP_NAME=quizbuks`、基础域名 `vars.QUIZBUKS_BASE_DOMAIN`、库 `quizbuks`）；`.github/workflows/quizbuks-deploy.yml` 构建镜像 `ghcr.io/<仓库>-quizbuks`（不再测试：合并前 PR 已检查，`quizbuks.yml` 与平台的 `ci.yml` 只对 PR 运行）、按分支（GitHub Environment `production` / `staging`）渲染站点并以服务器的 `deploy-app.sh <APP_NAME> <SHA>` 部署。
  服务器用的只有 `deploy/quizbuks/server/compose.yaml`（项目名与容器名只取 `.env` 的 `${APP_NAME}`，不写死；无端口、无数据库、外部网络 `proxy` 与 `db`）、模板 `site.caddy.tpl`（`__DOMAIN__`、`__APP_NAME__`、`__APP_DIR__`）与维护页 `maintenance.html`（维护模式只以服务器上的锁文件开关，DEPLOY.md）；环境的差异只在服务器上各自的 `.env` 中，应用配置中不写按环境的域名或密钥；`compose.dev.yaml` 只在本机。客户端地址照旧只经 `JABIZ_TRUSTED_PROXIES`（Caddy 在 `proxy` 网络中的地址，不是整个网段：网络上还有其他应用），不用 `server.forward-headers-strategy`。健康检查不含邮件服务器（`management.health.mail.enabled: false`）。
- 本机试用：`tools/quizbuks/local-demo.sh start --seed`（构建、启动、首次设置与演示数据，只用于本机；`deploy/quizbuks/README.md` "本机演示"）。改了设置流程、入口或演示所用的流程输入时同步改它（CI `local-demo`）。
- **使用中的问题**：使用者遇到的每个问题（现象、原因、使用者的解决办法、状态）先记进 `docs/quizbuks/troubleshooting.md`（编号 `T<n>`），给使用者看的写成三语的帮助页文案（FAQ 的 `audience` / `category`），由内容管理员发布为 FAQ（App 与发起方页面的 `/help`）；修复后改状态、不删条目。修复问题或改变行为时同步改相应条目。
- **生产运维**：`docs/quizbuks/operations.md`（密钥清单、备份与恢复演练、首次启动、切换正式模式、监控、容量、常见故障）与上线检查清单 `docs/quizbuks/launch-checklist.md`；
  新增环境变量、启动检查、任务或告警时同步改 `operations.md` 的对应表格。
- **Stripe 测试模式全流程**：`tools/quizbuks/stripe-test-mode/run.sh`（手册 `docs/quizbuks/stripe-test-mode.md`）用真实测试密钥驱动并核对整条资金流程；只接受 `sk_test_`、缺省只驱动本机、`production-hosts` 中的主机永远拒绝，从不打印密钥。
  改了它所用的流程输入、数据视图、分录的描述或科目时同步改 `stm.py`，并以 `serve --stub` + `run --stub` 干跑；它的离线测试是 `tools/quizbuks/test/stripe-test-mode.test.sh`（CI 作业 `stripe-test-mode` 只在手动触发且有 secret 时运行）。
