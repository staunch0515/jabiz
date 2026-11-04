# Q88 商户自己的认证、只给认证的发布与付学分答题（计划，2026-11-03；需求方 2026-11-03 按建议确认 §9 全部七点）

Q87 §7 把"答题的能力"列成四项：**给学分**（已有）、**给认证**、**先持有认证**（Q86 已做）、**付学分**。本计划做剩下的两项：
（A）任何商户的发布都可以登记为**该商户自己的认证**的一轮，发布可以**只给认证、不给学分**；（B）发布可以收**报名费**（Kudos），开始答题时从答题者的学分扣。
两项各由一个受控参数控制，缺省都关（能力做出来，开关交给管理人员，同 Q87 的原则）。需求方按建议确认了 §9 的七点；分两个 PR（§7），工作分支 `1.3/quizbuks-88a-sponsor-certifications` 与 `1.3/quizbuks-88b-entry-fee`。

## 0. 为什么

- **树上的每个节点可以要什么、给什么**（Q86 D-Q86-9）：Q76 / Q86 做了"要"的一半（先合格过 X、先持有认证 Y），Q69 / Q77 做了"给认证"的平台一半（只有官方账号）。缺的是商户自己发认证（S6 的"品牌官方认证"，04 §5-E 第 3 项，Q77 的 D-Q77-8 留给 Q77b）与商户收学分。补上这两项，一个商户的树就完整了：根节点免费给学分，进阶节点要先持有自己的"入门认证"并付几个 Kudos，合格给"进阶认证"与奖励。
- **认证不是奖励方式**（D-Q69 的原则）仍然成立：商户认证是一个发布的"合格之后的意义"，兑现与结算一字不动；只放宽 D-Q69-10 早就留下的两处限制（目录的"对象"与"轮只能是官方账号的发布"），Q86 的前置与树上的认证节点直接适用（D-Q86-9）。
- **付学分答题**把学分从"商户 → 答题者"的单向流变成双向：答题者赚到的学分可以用来答更值钱的题，商户收到的报名费进现金账户、再换成学分发奖励（Q87 D-Q87-3）。这是 Q87 §7 定的落点（2120 → 2160）；本计划只定怎么在不动答题热路径预算的前提下做到。

## 1. 现状

- **认证**（Q69、Q70、Q77、Q86、Q89）：目录只在代码中（`Certifications.ALL`，七种：`SPONSOR_APPLY`、`REFERRER`、三种协议理解、两种赞助权益），`Certification(key, validity, version)`；持有按需算出（`CertificationHolding`：在该认证各轮的发布上合格、有效期内、轮的版本 = 当前版本，或人工授予，收回最大）。轮（`QbCertificationRound`，`certification` 列 `varchar(40)`）只能是**官方账号**的发布，由运营商以 `QB_CERTIFICATION_ROUND_ADD` 登记（持 `qb.certification.manage`）；记录 `QbCertificationRecord`（`PASSED` 由认领写入，`GRANTED` / `REVOKED` 由运营商）。持有即不能答它的轮（D-Q70-1）。证书（Q77）由版式 `qb.certificate` 签发，当事方"签发方"是平台（`qb.me.certificate-issuer`）。八个模板写同一段持有 SQL（目录是 SQL 里的 `VALUES`，`CertificationTemplatesTest` 核对）。
- **奖励规则**：发布至少要有一档奖励 > 0（`QB_TIER_NO_REWARD`，提交时由 `PublicationReadiness` 经 `TierCoverage.check` 检查；D-Q69-6 对认证题也不放宽）。
- **答题的钱**：开始只取发布的开始锁（Q10a4），锁外读本人的会话、卡、发布、条件、前置（一条语句），锁内再读一条（`AttemptStatementBudgetIT` 断言确切数字）；完成取发布锁与商户的钱包锁，奖励 2110 → 2120。答题者的钱包行是缓存（`TakerWallets`：2120 的余额与在途），每笔奖励、转出与重算都经本人的钱包锁更新；可转出额由 `qb.wallet.taker-transferable` 与 `RewardHolds` 给出（近期与争议中的奖励扣住，借方先用自由的钱）。
- **商户的现金账户**（Q87a）：`QbWallet` 的 `CASH` 行，余额是缓存，只在商户的钱包锁（`qb.wallet:SPONSOR:<商户>`）下更新（充值、换学分、平台费、付账单都取它）；每日核对断言 2160 按商户 = 缓存。
- **前端**：商户向导（`PublicationWizard`：内容、档位 `TiersEditor`、客户匹配与前置 `Prerequisites`、……）；App 的卡与发布页显示"最高 N Kudos"；后台扩展 `CertificationsPage` 给运营商登记官方账号与轮。

## 2. 决定（需求方 2026-11-03 按建议确认）

### A. 商户自己的认证与只给认证的发布

| # | 决定 |
|---|---|
| D-Q88-1 | **商户认证是商户的数据**：新普通表 `QbSponsorCertification`（`certificationId`、`sponsorId`、`name` 与 `description`（多语言，`I18nText`，至少一种语言；显示时按读者语言回退到商户的主语言）、`validityDays`（30–3650，缺省 365）、`version`（从 1 起）、`status`（`ACTIVE` / `RETIRED`）、建立与修改时刻），只经商户的流程 `QB_SPONSOR_CERTIFICATION_CREATE` / `_UPDATE` / `_RETIRE` 写入（权限 `qb.publication.write`，每个商户至多 20 个）。**升版**是 `_UPDATE` 的一个选项（`raiseVersion`）：规则变了、要持有人重新认证时用，老版本的合格从此不算（同目录认证升版的含义）。`RETIRED` 的认证不能再登记轮、不能再作前置，已持有的按有效期自然到期。目录（`Certifications.ALL`）不改，D-Q69-1 不变。 |
| D-Q88-2 | **键**：商户认证在轮、记录、前置、证书中的键是 `sp:<certificationId>`（38 字符，`varchar(40)` 放得下）；`Certifications.of(键)` 认目录，`SponsorCertifications.of(键)` 认商户。四张表**不加列、不迁移**；持有的规则 `CertificationHolding` 不改（`Certification` 由行构造）。八个模板的持有 SQL 把目录的 `VALUES` 与 `qb_sponsor_certification`（键、有效期、版本）`UNION` 起来，仍是同一段、`CertificationTemplatesTest` 仍核对。 |
| D-Q88-3 | **轮由商户自己设**：新流程 `QB_SPONSOR_ROUND_SET`（商户；本人的发布 + 本人的 `ACTIVE` 认证，或清空）只在发布**提交之前**（`DRAFT` / `REJECTED`）可设可改——提交后只能关闭（`QB_SPONSOR_ROUND_CLOSE`，本人的轮，含义同运营商的关闭：从此合格不算、之前的仍算）。写的是同一张 `QbCertificationRound`（`owner` 是商户所属运营商的范围，`addedBy` 是商户），所以运营商照旧能用 `QB_CERTIFICATION_ROUND_CLOSE` 关掉辖下商户的轮（监督），`QbCertificationRecord` 照旧按轮写、按 owner 归属。一个发布至多一个轮（唯一约束已有）。向导加一步"认证"（见 §4.1）；复制发布（Q4）不复制轮（新一轮要商户自己再设，同 D-Q70-2）。 |
| D-Q88-4 | **商户认证只在本商户之内**（D-Q69-10 的措辞）：只能登记本商户的发布为轮；作前置（Q86 的 `certification` 列）只能要求本商户自己的认证（`PrerequisiteRules`：目录中对象是答题者的，或本人 `ACTIVE` 的）；不做跨商户。树上它是**本商户的节点**（`nodeKind: 'certification'`，编号 `cert:sp:<id>`），点它去本商户当前一轮的发布（不是平台之树）。 |
| D-Q88-5 | **只给认证、不给学分**：提交时的 `QB_TIER_NO_REWARD`（`PublicationReadiness`）放宽：没有任何一档 > 0 时，发布须已经是一个轮（D-Q88-3 的商户轮；官方账号的轮由运营商在上架后登记，不在提交前，所以官方账号照旧至少一档 > 0——D-Q69-6 对官方账号不变）。`maxReward` / `maxSpend` 为 0 的发布：预算与赢家上限照常（`maxWinners` 仍有效），开始时的商户钱包软检查照常（0 不会待付），完成不取商户的钱包锁（`plan.fromWallet()` 已是 false），账本没有分录。卡与发布页在 `maxReward = 0` 且是轮时显示"合格授予：<认证名>"代替"最高 0 Kudos"（有奖励的轮两样都显示）；首页按奖励排序时 0 排在后面，不另加权重。 |
| D-Q88-6 | **证书与公开页**（Q77）：商户认证的证书由同一个版式 `qb.certificate` 签发，多一个事实"颁发方"（商户的 `company`，平台认证时为空、不显示）；认证名取行的名字（签发时冻结在 PDF 里）。核实页 `/taker/verify/<键>` 与公开认证页显示"<认证名> · 由 <商户> 颁发"。"我的认证"同样列出。删除商户账号（`SponsorErasure`）：认证行与轮留下（答题者的持有与证书不因此消失），`name` 不含个人信息。 |
| D-Q88-7 | **开关** `qb.certification.sponsor-rounds.enabled`（受控，缺省 `false`）：关着时建立、修改认证与设轮都 422 `QB_SPONSOR_CERTIFICATIONS_OFF`，向导不显示"认证"一步；已有的持有、前置、树与证书照常（关掉开关不能让树掉节点）。答题路径不读它。 |
| D-Q88-8 | **运营商与总部**：后台扩展的认证页加"商户的认证"一览（运营商只看辖下：认证、商户、轮数、持有人数、状态），只读；运营商关轮用现有流程。人工授予与收回商户认证（`QB_CERTIFICATION_GRANT` / `_REVOKE` 接受 `sp:` 键）**不做**。 |

### B. 付学分答题（报名费）

| # | 决定 |
|---|---|
| D-Q88-9 | **报名费是发布的一个字段** `QbPublication.entryFee`（JPY 的 Kudos，0 = 不收，上限 `PublishingLimits.MAX_ENTRY_FEE = 10000`），随卡冻结（`QbPublicationCard.entryFee`）。只有 `QUIZ` 能收（问卷不能：问卷是付钱问人，S2 的店面发布也不能：访客没有钱包）。一人一个发布只答一次，所以每人至多付一次。邀请制的发布可以收。 |
| D-Q88-10 | **什么钱能付**：只有**可转出的钱**（`qb.wallet.taker-transferable` 的规则：扣掉近期与争议中的奖励），不够即 422 `QB_ENTRY_FEE_SHORT`（带缺多少）。理由同转出：近期的奖励可能随商户的付款争议被冲掉，花在报名费上平台就垫了。报名费是一条 2120 的借方（来源 `QbAttempt`，组 = 会话），`RewardHolds` 与两个钱包模板把它当作普通的借方（先用自由的钱），`totalEarned` 不受影响。 |
| D-Q88-11 | **不进商户的现金账户、先进待入账**：开始时记 **2120 答题者 → 2170 报名费待入账**（新科目，负债，维度 `party` = 商户），**每日任务** `qb.entry-fee-settle`（`JobDefinition.forEach` 按有余额的商户，每商户一个 `internal()` 流程 `QB_ENTRY_FEE_SETTLE`：商户的钱包锁、2170 → 2160、更新现金缓存，来源是现金钱包行、引用是日期）把它入到现金账户。原因：2160 的余额是只在商户的钱包锁下更新的缓存（Q87a），开始答题若直接记 2160 就得在答题热路径上取商户的钱包锁——Q10a 把锁拆开正是为了不这样；"待入账 → 每日入账"与奖励的"待付 → 结清"、平台费的"记费 → 月初出账单"是同一类做法。商户的钱包页显示"报名费待入账 N（每日入账）"。 |
| D-Q88-12 | **答题路径**：有报名费的开始，在发布的开始锁**之前**取本人的钱包锁（`qb.wallet:TAKER:<用户>`；没有流程以相反的顺序取这两把锁：转出只取钱包锁，完成取发布锁与商户锁，重算取钱包锁），锁内读钱包行与可转出额（两条语句）、不够即拒绝，然后照常取开始锁；写入时多一个 `LEDGER_POST` 子流程与钱包行的更新（余额减）。普通发布不读、不取、不写任何多的东西：`AttemptStatementBudgetIT` 加 `aStartWithAnEntryFee`（锁的顺序也断言），**普通开始的数字不变**。开关不在答题路径上读（见 D-Q88-14）。 |
| D-Q88-13 | **不退**：开始即付。超时未交、放弃、被商户或总部下架时正在答的会话（照旧可以答完）都不退；争议由运营商以手工加币处理（`ManualCredits` 已有）。报名费不是平台收入：不进月结算与运营商分成；平台对报名费的抽成以后作为 `FeeKinds` 的一种（不在本计划）。 |
| D-Q88-14 | **开关** `qb.play.entry-fee.enabled`（受控，缺省 `false`）只管**能不能设**：关着时保存 `entryFee > 0` 即 422 `QB_ENTRY_FEE_OFF`，向导不显示该字段；已上架的发布照收（要停就由运营商下架或商户结束）。这样开关不进答题路径。 |
| D-Q88-15 | **账本与核对**：科目 2170（`qb.journal.account.2170` 要在上线时配好，`JournalMappingCheck` 否则告警）；月报（`qb.admin.monthly`）加两类"报名费"（`QbAttempt` 的 `2120,2170`）与"报名费入账"（`2160,2170`）；每日核对加：2170 按商户不为负、2120 = 答题者缓存与 2160 = 现金缓存的规则不变（两边都经锁更新）。财务总览多一张 2170 的科目卡。 |
| D-Q88-16 | **界面**：向导"档位"一步加"报名费"（开关开着时）；App 的卡显示"报名费 N"，发布页的开始按钮写"付 N Kudos 开始"并弹确认（显示可用的可转出额，不够时说明"还差 N"与去赚学分的入口），学分记录多一行"报名费：<发布名>"；商户钱包页现金账户块加"报名费待入账"，交易页的分录照常列出；商户的发布页显示已收的报名费合计（`qb.sponsor.publication` 加一列，从 2170 + 2160 的来源为该发布的会话算，不另建表）。 |

## 3. 后端（`backend/quizbuks`）

### 3.1 迁移（编号以合入时为准，现在是 V71）

- **Q88a**：`CREATE TABLE qb_sponsor_certification`（主键 `certification_id`、`sponsor_id` 索引、`status`、`version`、`validity_days`、`name jsonb`、`description jsonb`、时刻）；普通表。`QB_SETUP`：受控参数 `qb.certification.sponsor-rounds.enabled`（追加在 `QbParams.ALL` 末尾）；字典 `status` 的值与消息。
- **Q88b**：`ALTER TABLE qb_publication ADD COLUMN entry_fee numeric(7,0) NOT NULL DEFAULT 0`、`qb_publication_card` 同；账本科目 2170（同 Q87 加科目的做法，`QbLedger.ENTRY_FEES_UNSETTLED`）；`QB_SETUP`：受控参数 `qb.play.entry-fee.enabled`；定时任务 `qb.entry-fee-settle` 的 cron 配置 `QB_ENTRY_FEE_SETTLE_CRON`（缺省每日，测试 e2e 的 `app.env` 每分钟）。

### 3.2 代码（Q88a）

- `certification.QbSponsorCertifications`（实体、数据视图 `sponsor:`（本人）与 `default:`）、`SponsorCertifications`（纯 Java：键 `sp:` 的生成与识别、由行构造 `Certification`、名字按语言回退）、`SponsorCertificationProcesses`（CREATE / UPDATE / RETIRE / ROUND_SET / ROUND_CLOSE，`LoadParams.of(SPONSOR_ROUNDS_ENABLED)`，`CallerChecks::refuseBanned`，本人的发布与认证）。
- `CertificationProcesses`：`certification(key)` 改为认两种键；`CLAIM` 为 `sp:` 的轮读认证行（与记录同一条 `QueryEntities.all`）；`RoundHolders.load` / `loadHolding(s)` 同（只在轮是商户认证时多读该行，与轮在同一条语句——**语句数不变**，`AttemptStatementBudgetIT` 加一个商户轮的用例写明）。
- `publishing.PrerequisiteRules`：`certification` 可以是本人 `ACTIVE` 的 `sp:` 键；`PublicationReadiness` / `TierCoverage.check`：多一个输入"已是轮"，`QB_TIER_NO_REWARD` 据此放宽（D-Q88-5）；其他不变。
- `tree.TreeStates` / 三个树模板：`cert:sp:<id>` 的节点，名字与链接来自行。
- 证书：`QbCertificates` 的模板 `qb.me.certificate` 与版式加"颁发方"；`qb.public.certificate`、`qb.public.taker-page` 加 `sponsorName`、认证名从行取（公开模板的白名单随之，`PublicQueriesSnapshotIT` 更新）。
- 模板：`qb.app.feed`、`qb.app.publication`（`certificationName`、`certificationSponsor`，给 D-Q88-5 的显示）、八个持有 SQL 的 `UNION`、`qb.sponsor.certifications`（本人的认证与各自的轮数、持有人数）、`qb.admin.sponsor-certifications`（运营商 / 总部一览，范围照 `withinEntryScope`）。
- 消息三语：流程名、错误码（`QB_SPONSOR_CERTIFICATIONS_OFF`、`QB_SPONSOR_CERTIFICATION_RETIRED`、`QB_SPONSOR_CERTIFICATION_LIMIT`、`QB_NOT_OWN_CERTIFICATION`、`QB_ROUND_AFTER_SUBMIT`）、实体与字段名、模板列名、版式的"颁发方"。

### 3.3 代码（Q88b）

- `publishing.QbPublications` / `PublicationRules`：`entryFee`（范围、只对 `QUIZ`、店面发布为 0、开关关着时为 0）；卡的冻结加字段；`PublicationReadiness` 同样检查。
- `play.AttemptProcesses.start`：有报名费时（`CARD_BEFORE.entryFee > 0`）取钱包锁、读钱包行（`QbWallets.TAKER`）与 `RunTemplate` 的可转出额、`EntryFees.check`（纯 Java：可转出 ≥ 费、否则 `QB_ENTRY_FEE_SHORT` 带 `short`）；`start()` 里 `CallProcess.of(LEDGER_POST, EntryFees.posting(...))`（2120 借 party = 用户、2170 贷 party = 商户，`publication` 维度两边都带，来源 `QbAttempt` / 会话号、描述 "Entry fee"）与 `QbWallets.writeTaker`。
- `wallet.EntryFeeSettlements`：`QB_ENTRY_FEE_SETTLE`（`internal()`：商户的钱包锁、模板 `qb.system.entry-fees-unsettled`（2170 按商户的余额）、`LEDGER_POST` 2170 → 2160、`QbWallets.change(CASH)`），`JobDefinition.forEach`（`qb.entry-fee-settle`，列表来自同一模板）。账本来源登记（同 `QbFeeCharge` 的做法）。
- 核对（`journal.ReconcileProcesses`）：2170 不为负；月报的两类；`JournalMappingCheck` 认 2170。
- 模板：`qb.app.feed` / `qb.app.publication`（`entryFee`）、`qb.app.wallet` 与 `qb.wallet.taker-transferable`（借方已按方向识别，核对注释即可）、`qb.app.ledger`（行的说明"报名费"）、`qb.sponsor.wallet`（`entryFeesUnsettled`）、`qb.sponsor.publication`（`entryFeesCollected`）、`qb.admin.finance-summary` 的科目卡。
- 消息三语：`QB_ENTRY_FEE_SHORT`、`QB_ENTRY_FEE_OFF`、`QB_ENTRY_FEE_NOT_ALLOWED`、字段与列名、任务与流程名。

## 4. 前端

### 4.1 商户后台（`quizbuks-web/sponsor`）

- **Q88a**：新页"认证"（`/certifications`，菜单"内容"组）：本人的认证列表（名字、有效期、版本、状态、轮数、持有人数）、建立 / 修改（含"升版"带说明）/ 停用；向导加一步"认证"（开关开着时）："合格后授予"下拉（本人 `ACTIVE` 的认证，或"不授予"），提交后该步只读并可"关闭这一轮"；"客户匹配"一步的前置区块的认证下拉加本人的认证；树页的认证节点按 D-Q88-4 画。
- **Q88b**："档位"一步加"报名费（Kudos）"（开关开着时；问卷与店面发布不显示）；钱包页现金块加"报名费待入账"；发布页加"已收报名费"。
- `qb/` 共享文件（App 与商户后台各一份、`diff -r`）：`tree.ts` 的节点键 `cert:sp:<id>`。

### 4.2 App（`quizbuks-web/app`）

- 卡（`FeedCard`）与发布页（`PublicationContent`）：D-Q88-5 的"合格授予：<认证名>（<商户>）"、D-Q88-16 的报名费与确认对话框（`useStartAttempt` 处理 `QB_ENTRY_FEE_SHORT`）；"我的认证"、证书部分（`certificates/`）与公开认证页显示颁发方；商户主页的树；学分记录的"报名费"行。

### 4.3 后台扩展（`backend/quizbuks/admin-extension`）

- `CertificationsPage` 加"商户的认证"一览（只读）；财务总览的科目卡随模板（2170）。

## 5. 测试

- **纯 Java**：`SponsorCertificationsTest`（键、回退、由行构造）、`EntryFeesTest`（检查与分录）、`PrerequisiteRulesTest` / `TierCoverageTest` / `PublicationReadinessTest` 的新用例、`TreeStatesTest`。
- **集成**：`SponsorCertificationIT`（建立、升版使老合格不算、停用后不能设轮与前置、设轮只在提交前、关闭、不是本人的 404、开关关着 422、持有人不能答它的轮、运营商关辖下的轮、证书与核实页写颁发方、公开页）；`PublishingIT` / `ReadinessIT`：0 奖励只在有轮时能提交；`EntryFeeIT`（可转出不够 422 与缺多少、近期奖励不能用来付、付后钱包缓存与 2120 一致、2170 按商户、每日任务入账到 2160 与现金缓存、问卷与店面不能设、开关关着不能设但已上架的照收、账本平衡）；`AttemptStatementBudgetIT` 加商户轮与报名费两个用例、普通数字不变；`ReconcileIT` 加 2170；`ProcessAccessIT` 新流程；`PublicQueriesSnapshotIT`、`OpenApiSnapshotIT`。
- **场景**：`certification` 场景加商户认证与只给认证的发布；`play` 场景加报名费（快照含 2170 与入账任务 `runJob`）。
- **前端**：商户后台（认证页、向导两步、树）与 App（卡、确认对话框、我的认证）的 Vitest；e2e：`sponsor-certification.spec.ts`（商户建认证 → 只给认证的发布 → 答题者合格 → 我的认证与证书写颁发方）、`entry-fee.spec.ts`（答题者先赚学分再付费答；`app.env` 加 `QB_ENTRY_FEE_SETTLE_CRON`）；财务总览的科目卡数 12 → 13（`a11y.spec.ts`、`finance.spec.ts`）。

## 6. 文档

- `02-design.md`：§3.3 发布加 `entryFee`、§3 新实体 `QbSponsorCertification`、§4.1 科目 2170、§4.3 锁的顺序、§5.2 新流程；`03-plan.md` 加 Q88 一行；`04-product-direction.md` §5-D / §5-E 指到本计划；Q69 §7 与 Q77 D-Q77-8 的"以后"改为指向 Q88；`operations.md` 加 §7.19（开关、上线时配 2170 的科目映射、运营商关轮、报名费的争议处理）；`backend/quizbuks/CLAUDE.md` 的"资格"与"答题"两段各加一句（键 `sp:`、锁的顺序、2170）。

## 7. 估计

| 部分 | 天 |
|---|---|
| Q88a 后端：实体、流程、键的两种来源、前置与轮、提交规则、证书与公开页、模板 | 3 |
| Q88a 前端：认证页、向导两步、树、App 的显示、后台一览 | 2 |
| Q88b 后端：字段与卡、开始的锁与分录、待入账与每日任务、核对与月报 | 2 |
| Q88b 前端：向导、确认对话框、钱包与发布页 | 1 |
| 测试（IT、场景、e2e）与文档 | 2 |
| **合计** | **10**（两个 PR：Q88a 约 6、Q88b 约 4） |

## 8. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 中 | 键 `sp:<uuid>` 混在目录键的列里，模板与流程两处都要认 | 识别只在两个纯 Java 类（`Certifications.of` / `SponsorCertifications.of`）与一段 SQL 的 `UNION`；`CertificationTemplatesTest` 核对八个模板，`ArchUnit` 不让别处拼这个前缀 |
| 中 | 答题热路径的改动 | 普通发布不多一条语句、不多一把锁（`AttemptStatementBudgetIT` 断言）；报名费的检查与锁都在开始锁之外 |
| 中 | 2170 的余额与现金缓存之间有一天的延迟，商户问"钱去哪了" | 钱包页写明"待入账（每日入账）"；核对断言 2170 不为负；任务失败由平台的作业重试与待办 |
| 低 | 0 奖励的发布在各处显示"0 Kudos" | 只在卡、发布页、结果页三处按 D-Q88-5 换显示，`maxReward` 的语义不变 |
| 低 | 商户用认证误导（"官方认证"） | 证书与公开页写明"由 <商户> 颁发"，平台认证不带颁发方；运营商可关轮（D-Q88-3）；措辞由法律文本的"商户条款"约束，不在代码中 |

## 9. 需求方确认的（2026-11-03，全部按建议）

1. **D-Q88-3**：商户的轮只能在提交前设（之后只能关闭）。备选：上架后也能加轮（从加的时刻起的合格才算），实现多一条规则，但商户更灵活。
2. **D-Q88-5**：只给认证的发布 = 0 奖励 + 已设轮；官方账号的轮仍要至少一档 > 0（D-Q69-6 不变）。备选：也放宽官方账号（运营商在提交前登记轮做不到，得改登记的时机）。
3. **D-Q88-10**：报名费只能用可转出的钱付（近期奖励不能用）。备选：用全部余额（平台承担争议冲掉的风险）。
4. **D-Q88-11**：报名费先进 2170、每日入账到现金账户，而不是开始时直接进 2160（后者要在答题路径上取商户的钱包锁）。备选：完成时入账（完成已取商户锁，但 0 奖励的完成不取，且弃答的费永远留在 2170）。
5. **D-Q88-13**：不退（含被下架）。备选：被总部下架且会话未开始答题时退。
6. **D-Q88-14**：开关只管能不能设报名费，已上架的照收。
7. 两个 PR 的拆分（Q88a 认证、Q88b 报名费）与编号。

## 10. 实施记录（Q88a，2026-11-03，分支 `1.3/quizbuks-88a-sponsor-certifications`）

按 §2 的 A 部分实现；与计划不同的地方：

1. **迁移是 `V73__quizbuks_sponsor_certifications.sql`**，只建一张表。四张表（轮、记录、前置、证书）的 `certification` 列原来是目录的码字段（`asCode`），为了放下 `sp:<id>` 改成了文本字段（列不变、没有迁移）；目录的字典仍给前端与后台用。
2. **证书的签发方就是商户**（D-Q88-6 写的是"多一个事实'颁发方'"）：平台的单据版式把每个事实都印出来，空的也印标签，所以没有加事实，而是让当事方"签发方"在商户认证时写商户的公司名（`qb.me.certificate-issuer` 的可选参数 `issuerName`，签发流程只对 `sp:` 键给出），平台认证照旧是 `qb.issuer.name`。核实页与公开认证页另给 `issuerName`，App 显示"由 <商户> 颁发"。
3. **目录与商户的行在同一段 SQL 里**：`Certifications.sqlValues()` 现在写的是 `(SELECT * FROM (VALUES …) UNION ALL SELECT 'sp:' || id, version, days * 86400 FROM qb_sponsor_certification)`，十个模板（计划说八个：Q89 的两个权益模板也写这段）都换成它，`CertificationTemplatesTest` 照旧核对。
4. **`QB_SPONSOR_ROUND_SET` 不单独取一步**，而是向导"时间与奖励"一步档位下面的一个下拉（只在商户有启用的认证时显示；开关关着时建不出认证，所以也不显示），随每次保存在前置之后调用；没有加第五步。非草稿在发布页显示"合格授予的认证"一节与"关闭这一轮"。
5. **0 奖励的检查没有移动**：`QB_TIER_NO_REWARD` 本来就在提交时由 `PublicationReadiness` 经 `TierCoverage.check` 检查，这次只是多一个输入"已是一轮"，保存草稿的 `problems` 也按它给（向导在设轮之前会列出"没有奖励"的问题，设了就消失）。
6. **删除草稿连带删除它的轮**（计划没写）；复制发布不复制轮。停用认证时关闭它进行中的轮。
7. **`QB_MY_CERTIFICATIONS` 的读法改了**：原来读目录全部的轮再读本人的会话（≤ 500 轮），现在先读本人合格的会话，再读这些发布的轮（目录的与商户的一样），再读涉及的商户认证与商户名；输出多 `name`（多语言）与 `sponsorName`。
8. 前端的认证名字只经共用的 `qb/certifications.ts`（目录的用文案 `certification.names.<键>`，商户的用模板给的 `certificationNames`）；树的边多带 `certificationNames`，商户自己的认证在自己的树上是节点、不链到平台之树。
9. 后台扩展的"认证"页只加了一节只读的"商户自己的认证"（模板 `qb.admin.sponsor-certifications`，按公司名搜索），没有在"轮次"表里解析 `sp:` 键的名字（显示键）。
10. e2e `sponsor-certification.spec.ts`：商户定义认证 → 0 奖励的发布登记为轮并提交 → 答题者看到"合格授予"、合格、"我的认证"签发证书、核实页写颁发方 → 商户的列表计 1 人 → 总部的模板列出它。
11. **升版关闭进行中的轮**（自查发现）：轮的 `certificationVersion` 不可改、合格按轮的版本记，升版后旧轮上的合格永远不算。所以 `_UPDATE` 的 `raiseVersion` 像停用一样关闭该认证进行中的轮（含草稿的；草稿重新设轮即换成新版本的轮），商户把新版本的测验在草稿上重新登记；界面的说明写明。已关闭的轮不再让 0 奖励的草稿通过提交检查（`PublicationRounds.isRound` 只算开着的轮）。
12. **开关关着时仍可取消草稿的轮**（`QB_SPONSOR_ROUND_SET` 的 `certificationId` 为空），只有登记新轮受开关约束。

未做：D-Q88-8 的人工授予与收回商户认证；Q88b（报名费）另一个 PR。

## 11. Q88b 实施计划（报名费，2026-11-03，待确认）

按 §2 的 B 部分（D-Q88-9…16）做，分支 `1.3/quizbuks-88b-entry-fee`，一个 PR。读过代码之后与 §3.3 不同或更具体的地方用 **粗体** 标出。

### 11.1 迁移 `V74__quizbuks_entry_fee.sql`

- `qb_publication_version` 加 `entry_fee numeric(7,0) NOT NULL DEFAULT 0`（只追加表只加列）；`qb_publication_card` 同。
- **`qb_attempt` 加 `entry_fee numeric(7,0) NOT NULL DEFAULT 0`**（计划没写）：开始时付了多少记在会话上，App 的学分记录、商户发布页的"已收报名费"与月报都从它或账本读，不用从 2170 + 2160 的来源反推（D-Q88-16 的"不另建表"仍成立）。
- 科目 **2170 报名费待入账**（`QbLedger.ENTRY_FEES_UNSETTLED`，负债，行带 `party` = 商户与 `publication`）：`QB_SETUP` 再执行一次即开户（只补从未存在过的）；映射参数 `qb.journal.account.2170`（受控，首个值空，`JournalMappingCheck` 自动覆盖）；受控开关 `qb.play.entry-fee.enabled`（缺省 `false`，`QbParams.ALL` 末尾、`CONTROLLED`）。
- 任务 `qb.entry-fee-settle`：cron `quizbuks.entry-fee.settle-cron`（`QB_ENTRY_FEE_SETTLE_CRON`，缺省每日 18:07 UTC = 03:07 日本；`app.env` 每分钟）。

### 11.2 发布（`publishing`）

- `QbPublications`：发布的 `entryFee`（`asMonetary("JPY", 0)`，`Rules.range(QB_ENTRY_FEE_RANGE, 0, PublishingLimits.MAX_ENTRY_FEE = 10000)`），卡片的 `entryFee`（不可变）；`PublicationEditing` 的冻结字段表加 `entryFee`，`draftOf` → `PublicationReadiness.Draft` 加 `entryFee`；复制随发布；审批内容（`PublicationApprovals`）含它。
- `PublicationReadiness.check`：`entryFee > 0` 只允许 `QUIZ`（问卷 422 `QB_ENTRY_FEE_NOT_ALLOWED`）。店面（S2）的发布从不设它（渠道流程没有这个输入，卡片为 0）。
- `QB_PUBLICATION_SAVE`：`SaveInput.entryFee`；**开关只在保存时读**（`LoadParams.optional(qb.play.entry-fee.enabled)`，未发布即关）：关着而 `entryFee > 0` 即 422 `QB_ENTRY_FEE_OFF`。提交不再读开关（D-Q88-14：已上架的照收）。
- 商户后台知道开关：**`qb.sponsor.reward-methods` 加一列 `entryFeeEnabled`**（向导本来就读它），不另建模板。

### 11.3 答题路径（`play`）

- 新纯 Java `play.EntryFees`：`check(available, held, cacheBalance, fee)`（可转出 = 2120 余额 − 保留中；可转出 ≥ 费且缓存余额 ≥ 费，否则 `QB_ENTRY_FEE_SHORT` 带 `fee`、`available`、`short`）；`posting(...)`：2120 借（`party` = 答题者，`publication`）→ 2170 贷（`party` = 商户，`publication`），来源 `QbAttempt` / 会话号，描述 `Entry fee`。
- `QB_ATTEMPT_START`，只在 `starting && 卡片.entryFee > 0 && 发布方是 SPONSOR` 时有语句（普通发布的查询为空、锁名为 null，**语句数与锁不变**），都在开始锁**之前**：
  1. `LoadParams`（`qb.transfer.hold-days`，可转出模板需要；未发布同转账：`INVALID_PARAMS`）；
  2. `HoldLock.exclusive(qb.wallet:TAKER:<本人>)`；
  3. 一条 `QueryEntities.of` 读本人钱包行 + `RunTemplate.of(qb.wallet.taker-transferable)`（两条语句）；
  4. `compute`：`EntryFees.check`，不够即拒绝（锁内读、锁内判，之后照常取开始锁）。
  写入：会话的那条插入多 `entryFee`；`QbWallets.change(TAKER, −费)`（锁内读到的版本）；"Start the attempt" 之后一步 `CallProcess.when(有报名费, LEDGER_POST, EntryFees.posting(...))`。`ChannelPlay`（S2）走同一个 `start(...)`，卡片 `entryFee` 为 0 即什么也不做。
- **锁序的变化**：`qb.wallet:TAKER:<用户>` 原来"独立、持有它的流程不取其他锁"；现在开始会先取它、再取 `qb.pub-start:<发布>`。反向不存在：完成只取发布锁与商户钱包锁，同步与转账只取用户钱包锁。`backend/quizbuks/CLAUDE.md` 的两处锁序与 `02-design.md` §4.3 改写。
- `AttemptStatementBudgetIT.aStartWithAnEntryFee`：断言取的两把锁及其顺序，总数与锁内数按实测写死（锁内自第一把锁即钱包锁起算，所以比普通开始多：预计锁外 +1 参数、+1 钱包行、+1 模板，锁内 +1 开始锁、+ 子流程 `LEDGER_POST` 的语句与钱包行的更新）；`aStartAndAPaidSubmission` 等既有数字不变。

### 11.4 入账（`wallet.EntryFeeSettlements`）与核对

- `QB_ENTRY_FEE_SETTLE`（`internal()`，`qb.wallet.system`）：输入 `sponsorId`；商户钱包锁 → 一条语句读 `CASH` 行 + 模板 `qb.system.entry-fees-unsettled`（2170 按 `party` 的余额）；> 0 时 `LEDGER_POST` 2170 借 → 2160 贷（两行 `party` = 商户，来源 **`QbWallet` 的现金行、引用为日期**，描述 `Entry fees settled`）并 `QbWallets.change(CASH, +额)`；0 即什么也不写。
- 任务 `qb.entry-fee-settle` = `JobDefinition.forEach`（项来自模板 `qb.system.entry-fees-due`：2170 余额 > 0 的商户），每项一个事务，同 `qb.fee-*`。
- 核对：`ReconChecks.ENTRY_FEES_UNSETTLED`（模板 `qb.system.recon-entry-fees`：2170 按商户不为负），既有的 2120 = 答题者缓存、2160 = 现金缓存不变（两边都在锁内一起写）。月报 `qb.admin.monthly` 加 `ENTRY_FEE`（来源 `QbAttempt`，科目 `2120,2170`）与 `ENTRY_FEE_SETTLED`（来源 `QbWallet`，`2160,2170`），`AdminFinanceIT` 加逐行期望；`qb.admin.finance-summary` 加 `entryFeesUnsettled`（2170 合计），总览多一张卡。

### 11.5 模板与文案

- `qb.app.feed` / `qb.app.publication`：`entryFee`（卡片）。`qb.app.ledger`：多一类行 `ENTRY_FEE`（会话的 `entryFee > 0`，`occurredAt` = 开始时刻，`status` = `COMPLETED`，发布名）。`qb.app.wallet` 与 `qb.wallet.taker-transferable` 不改（借方按方向识别；注释说明报名费也是借方）。
- `qb.sponsor.wallet`：`entryFeesUnsettled`（2170 按本人）；`qb.sponsor.publications`：`entryFees`（该发布会话的 `entry_fee` 合计）。
- 三语：`QB_ENTRY_FEE_SHORT` / `_OFF` / `_NOT_ALLOWED` / `_RANGE`，字段 `entity.QbPublication.entryFee` 等，流程、任务、参数、核对项、月报类名。

### 11.6 前端

- 商户后台：向导"时间与奖励"一步加"报名费（Kudos）"（`entryFeeEnabled` 为真且是测验时显示；0 = 不收）、确认页与发布页显示；钱包页现金块加"报名费待入账 N（每日入账）"；发布列表 / 发布页显示"已收报名费"。
- App（`qb/` 两份相同）：卡片"报名费 N"；发布页的事实"报名费"，开始按钮"付 N Kudos 开始"，点击先弹确认（显示可转出 = `qb.app.wallet` 的 `available − held`；`QB_ENTRY_FEE_SHORT` 时显示"还差 N"与去赚学分的入口）；学分记录多一类"报名费：<发布名>"。
- 后台扩展：财务总览的 2170 卡、月报的两类名字。

### 11.7 测试

- `EntryFeeIT`：不够 422 与 `short`、近期奖励不能付、付后缓存与 2120 一致、2170 按商户、任务入账到 2160 与现金缓存、问卷不能设、开关关着不能设但已上架的照收、店面发布不收、账本平衡；`AttemptStatementBudgetIT.aStartWithAnEntryFee`；`ReconcileIT`（2170）；`AdminFinanceIT`；`ProcessAccessIT`（`QB_ENTRY_FEE_SETTLE` 内部）；`SetupIT`（参数与科目）；`PublishingIT`（范围、问卷、开关）；场景 `play` 加报名费（含 `runJob`）；`PublicQueriesSnapshotIT` / `OpenApiSnapshotIT` 照常。
- e2e `entry-fee.spec.ts`：开关四眼打开 → 商户设报名费并上架 → 没钱的答题者看到"还差 N" → 有奖励（过了保留期）的答题者付费开始并合格 → 学分记录有报名费行 → 商户钱包显示待入账 → 每分钟的任务入账后现金余额增加。

### 11.8 文档

`02-design.md`（§3.3 `entryFee`、§4.1 科目 2170、§4.3 锁序、§5.2 新流程与任务）、`operations.md` §7.20（开关、上线时配 2170 的映射、任务、争议以手工加币处理）、`backend/quizbuks/CLAUDE.md`（"答题"与"支付"的锁序一句、2170）、本文 §12 实施记录。

### 11.9 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 中 | 答题热路径：报名费的开始多一把锁、多四条锁外语句 | 普通开始一条不加、一把不加（`AttemptStatementBudgetIT` 既有数字不变）；报名费的检查全在开始锁之外 |
| 中 | 用户钱包锁与开始锁的新顺序 | 只有开始按"钱包锁 → 开始锁"取；反向不存在（逐个流程核对，写进 CLAUDE.md） |
| 低 | 缓存落后于账本（结算奖励不取用户锁，事后同步） | 可转出与缓存都要 ≥ 费才收；缓存在锁内按已知版本减，`qb_wallet.balance >= 0` 兜底 |
| 低 | 入账任务失败时 2170 累积 | 核对项 `ENTRY_FEES_UNSETTLED`；任务可补跑（按余额，不按日期） |

估计：后端 3 天、前端 1.5 天、测试与 e2e 1 天、文档 0.5 天。

## 12. 实施记录（Q88b，2026-11-04，分支 `1.3/quizbuks-88b-entry-fee`）

按 §11 实现；与 §11 不同或更具体的地方：

1. **迁移是 `V74__quizbuks_entry_fee.sql`**：发布（只追加表只加列）、卡片、会话三处 `entry_fee numeric(7,0) NOT NULL DEFAULT 0`。
2. **平台补了两个工厂**（`1.3/platform` 的 PR，先合入平台）：`RunTemplate.when`（读可转出额：普通开始不发这条语句）与 `SaveChanges.when`（报名费的分录以刚插入的会话作来源，`LEDGER_POST` 经数据视图核对来源存在，须先提交；普通开始不提前写出）。入账的流程同样以 `SaveChanges.when` 在过账前保存首次建立的现金钱包行。
3. **平台另修了一处**（另一个 PR）：17zf-a 的通行密钥邮件参数 `passkey` 与 QuizBuks 的 `@Sensitive passKey`（领奖通行码）小写后同名，`MailChecks` 报错，QuizBuks 的 `platformCheck` 因此失败；平台的参数改为 `passkeyName`。
4. **持有期参数不另用 `LoadParams`**：钱包行与 `qb.transfer.hold-days` 在钱包锁内一条 `QueryEntities.all` 读（经 `now:SysParam`），没有或不合格的值按 0 天（转账在这种情况下拒绝；开始不是转账）。
5. **语句数**：收报名费的开始共 17 条（普通 11 条），自第一把锁（钱包锁）起 10 条；关掉写缓冲时 22 / 15。多出的 6 条：钱包锁、钱包行与参数、可转出额、`LEDGER_POST` 的科目锁、一次读取与写出。普通开始与其他既有数字不变。
6. **可用额**：可转出额与钱包缓存余额两者都要够（`EntryFees.shortfall` 取两者的小者）；拒绝的消息与参数里的金额去掉账本的四位小数。
7. **商户的"已收报名费"**：发布列表多一项（`qb.sponsor.publications.entryFees`，会话 `entry_fee` 之和），发布页以同一模板按 `publicationId` 过滤读一行（模板的 `filters` 加了 `publicationId`）。
8. **App**：报名费的开始先读本人钱包（只在收费的发布上）；不够时不给开始按钮，只说还差多少与去答其他测验；够时按钮写"付 N 开始"并弹确认。服务端照样判断。
9. 场景回放没有加报名费的场景（`EntryFeeIT` 覆盖整条路径：拒绝、持有期、付费、缓存、分录、入账、核对、月报、账本平衡）；快照只因 `QB_SETUP` 多了参数与科目而变。

未做：无（§2 B 部分全部实现）。
