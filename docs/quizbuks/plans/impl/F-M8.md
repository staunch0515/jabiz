# F-M8 曝光费（钱的部分）：实施计划（草稿，待总控与需求方确认）

状态：已批准实施（总控 2026-11-13）。

依据：`docs/quizbuks/first-launch-spec.md` F-M8（以它为准）与 F-S13、F-M 的钱总则；格式与做法照 `docs/quizbuks/plans/impl/F-M.md` §9（F-M6 配送费，最近的同类）。读过的代码：`fee/`（`Prepayments`、`DistributionSettlements`、`DistributionPrices`、`DistributionRules`、`QbPrepayments`、`FeeConfig`）、`exposure/`、`publishing/`（`PublicationPrepayments`、`PublicationExposure`）、`wallet/ReconChecks`、`setup/QbParams`、`setup/FirstLaunchSwitches`、迁移 V97、V116、V117。
分支 `1.3/quizbuks-FM8-exposure-fee`（暂定名），迁移取"开 PR 时取下一个编号"（V118 已被 F-F11a 用，F-M7 暂定 V119，见第 8 节）。

**依赖 F-M7（未合并，分支 `origin/1.3/quizbuks-FM7-audience`）**：见第 8 节"与 F-M7 的关系"一览。建议 F-M7 合入后再从最新的 `1.3/quizbuks` 拉出实现，否则共用文件冲突很多。

## 1. 现状与范围

**F-S13 已做（已合并，不改）**：
- 计数：表 `qb_exposure`（每个发布、每位学者一行，`counted_at` 不为空即一次成功），规则 `exposure/ExposureRules`（`billable` = min（成功，已购）、`overshoot`）。
- 已购次数：发布与卡片的 `exposurePurchased`（空即不限），只经子流程 `QB_EXPOSURE_PURCHASED_SET {publicationId, purchased}`（`PublicationExposure.PURCHASED_SET`，`subProcessOnly`，取发布锁，草稿与进行中可设，已结束 422 `QB_EXPOSURE_PUBLICATION_ENDED`）。本项**不改它**，只调用。
- 用完即停：分钟任务 `qb.exposure-ends` 以 `QB_PUBLICATION_EXPOSURE_END` 结束发布（原因 `EXPOSURE`），每日核对 `qb.exposure-check`，发起方页面 `qb.sponsor.exposure`（已购、成功、计费、超出）。
- F-M5 已做的钱：预付款 `QbPrepayment`（用途字典已含 `EXPOSURE`，`QbPrepayments.EXPOSURE`）、账户顺序（赠送额度先、现金后）、资金检查 `QB_PUBLICATION_FUNDS`、释放 `QB_PREPAYMENT_RELEASE`。F-M6 已做：价目表、结算任务、`final` 与按用途释放、账单等待。
- 现在没有：`qb.exposure.billing.enabled`（只有 `FirstLaunchSwitches` 中一行 `waiting(…, "F-M8")`，参数本身不存在）、曝光的价目、购买、预付、结算、费用种类 `EXPOSURE`（`FeeRules.KINDS` 只有 `MONTHLY, MANUAL, AUDIENCE, DISTRIBUTION`）。今天所有用他人视频的发布都是"不限、免费"。

**本项做**（规格 F-M8）：
- 只在**用了他人共享视频**（卡片的提供方不是发布方，`ExposureRules.othersVideo`）的发布上收；用自己的视频不收；配送费不变；直营发起方照付；挑战（`forSponsors`）与店面（`publisherKind` 不是 `SPONSOR`）不收（同 F-M6）。
- 运营商价目、总部范围；提交时购买次数并预付，单价提交时冻结；追加购买；按月结算成功次数（≤ 已购）；发布结束后结清并退回未用的预付；剩余低于一成时提醒；页面、核对、月报、文档。

**不做**：按千次的竞价；提供方与使用方之间的钱（线下，平台只记台账，F-S14）；计数与推送（F-S13 已做）；封禁、注销时的最后一次结算与释放（随 F-M15，同 F-M6）；"不设结束时间"相关（F-M7 已拆出）。

## 2. 数据（一个迁移，"开 PR 时取下一个编号"）

- **`QbExposurePrice`**（普通表，每个运营商至多一行，按版本更新，同 `QbDistributionPrice`）：运营商、单价 `unit`（四位小数，日元 / 次）、修改人、理由（必填）、时刻；平台审计。没有行 = 该运营商不收曝光费（见问题 6）。
- **`QbExposureSettlement`**（**时态 `t.writeOnce()`**，同 `QbDistributionSettlement`；建表迁移照时态实体约定：`UNIQUE(主键, version_no)`、`(主键, effect_start_time DESC, version_no DESC)` 与 `process_seq_id` 索引、指向 `op_process` / `entity_registry` 的外键、`jabiz_protect_append_only`、另建只含主键的唯一索引）：发布、期间 `period`（`YYYY-MM`，日本时间）、上次累计 `fromCount`、本次累计 `toCount`、次数、单价、累计应收（不含税）、本次金额、税、合计、`final`、费用编号（金额为 0 时为空）、时刻。
  - 唯一索引 `(publication_id, period)`；部分唯一索引 `(publication_id) WHERE final`（同 F-M6）。
- **发布（版本）加两列**（只追加表，只加列，不改旧列）：`exposureRequested numeric(9,0)`（草稿上发起方填的购买次数，`QB_PUBLICATION_SAVE` 可写，复制时带上）与 `exposureUnit numeric(19,4)`（提交时冻结的单价，`processOnly`，草稿清空，同 `distributionUnit`）。卡片不加（学者读不到价格）。`exposurePurchased` 已有，不动。
- **`QbExposureNotice`**（普通表，每个发布一行）：`notifiedFor`（提醒时的已购次数）、时刻。用于"同一档已购只提醒一次，追加购买后可再提醒"。
- **费用种类 `EXPOSURE`**：`FeeRules.KINDS`、`qb_fee_charge_version` 的 CHECK、`FeeProcesses.ChargeInput.kind` 与 `SettleInput.kind` 的正则、月报、`QbFees` 的字典与消息。`QbFeeCharge.period` 已有，不改。
- **`qb_exposure` 加一个部分索引** `(publication_id, counted_at) WHERE counted_at IS NOT NULL`（普通表，可加索引；先看 V97 已有的索引，缺才加）：结算按"月末之前的成功次数"数，不扫整个发布。
- 迁移只给只追加表加列；`SealedColumnsHistory` 夹具照常检查；`PersonalDataEraser`：新表只含发布编号与金额，照 F-M6 的做法登记，随 F-M15 收尾。

## 3. 流程

### 3.1 规则（纯 Java，新类 `fee/ExposureFeeRules`，不引用 reactor 等）
- 单价检查 `priceProblems`：单价 **> 0**（0 或负数被拒；不收曝光费就不设价目），且在总部范围内（审查 M6）。
- 预付 `deposit(purchased, unit, rate)` = ⌈purchased × unit⌉ + ⌊⌈purchased × unit⌉ × 税率⌋；上限：预付超过 `FeeProcesses.MAX_AMOUNT` 即 422 `QB_EXPOSURE_DEPOSIT_RANGE`（新码，同 N5）。
- **追加的预付按累计算**（审查 H1）：新的预付总额 = `deposit(旧已购 + 追加, 单价, 税率)`，本次冻结 = 新总额 − 此前各次冻结之和（`ExposureFeeRules.topUpDeposit`）。这样无论分几次买，总预付都与一次买够相同，没有逐次取整的误差。页面显示的追加金额用同一个函数。例（单价 1、税 10%）：先买 105，预付 105 + ⌊10.5⌋ = 115；逐次算时追加 5 只冻结 5 + ⌊0.5⌋ = 5，合计 120，用完时应收 110 + ⌊11⌋ = 121，差 1 日元。按累计算，追加时冻结 deposit(110) − 115 = 121 − 115 = **6**。
- **税按累计算**（审查 H2，总控选 (a)，只用于曝光费，配送费不变）：累计税 = ⌊累计应收 × 税率⌋，本次税 = max(0, 累计税 − 此前已收的税)。用完时累计应收 = ⌈已购 × 单价⌉，累计税与预付里的税相同，预付**恰好扣光**（规格的验收"释放为 0"）。例（单价 1、买 100、预付 110）：第一个月 5 次，税 ⌊0.5⌋ = 0；逐次算时 `final` 95 次税 9，合计收 109、退 1；按累计算，`final` 的税 = ⌊100 × 0.1⌋ − 0 = 10，合计 110，退 0。税率在两次结算之间被改：此后的累计税按新税率算，本次税 = max(0, 新累计税 − 已收税)，税率降低时不退已收的税，结束时预付里多出的税随未用预付退回；这两种情况都有测试。
- 累计取整共用纯函数（新类 `UnitFeeRules`，`DistributionRules.cumulative/settle` 改为调用它，行为不变，`DistributionRulesTest` 照跑）：累计应收 = ⌈累计次数 × 单价⌉，本次 = 累计应收 − 此前已收。税：配送费照旧按本次向下取整；曝光费按上一条累计计。
- **计费次数** = min（成功次数，已购次数）（与 `ExposureRules.billable` 同一算式，模板也写同一算式）。月度结算的成功次数取"该月末之前 `counted_at` 的行数"（有时间戳，所以月界是准的，不像配送费要到次月 2 日 00:37 才读计数器；`final` 取全部）。
- **每期的累计次数**（审查 H3，原文）："本次累计次数 = max(已有结算行的最大 toCount, min(月末前成功数, 已购))；该发布已有更晚期间的行时，本期写 0 次、不记费。" 为什么需要它（审查原文）："如果 1 月的项失败、2 月的项成功（2 月行的累计已含 1 月的次数），之后经 JOB_RUN_REQUEST 补跑 1 月：1 月末之前的次数 < 已结累计，累计应收 − 此前已收 < 0。" 月度用的"已购"是**运行时的值而不是月末的值**：追加购买发生在次月 1 日到 2 日 00:47 之间时，上月的超出部分会记进上月（按 min 一并收，总数不变）。

### 3.2 价目与范围
- `QB_OPERATOR_EXPOSURE_PRICE_SET`（运营商负责人，`OperatorChecks.member` 且是负责人）与 `QB_EXPOSURE_PRICE_SET`（总部财务 `qb.fee.manage`，为某运营商设）。锁用自己的 `qb.exposure-price:<运营商>`（不取 `qb.operator:<运营商>` 之后的成员共享锁，理由同 F-M6 实施记录第 5 条）。理由必填；只影响此后提交的发布。
- 总部范围为受控参数（见第 5 节）；保存时按当时范围检查；范围改窄后已有价目不自动改，每日核对报 `EXPOSURE_PRICE_RANGE`。

### 3.3 提交（`PublicationPrepayments.items` 加一项 `EXPOSURE`）
- 开关 `qb.exposure.billing.enabled` **只在提交时看**，关掉不追溯（同 F-M6 总控决定 8）。
- 关时：不买、不预付，`exposureUnit` 为空，`exposurePurchased` 保持空（现状：不限、免费）。
- 开时，且发布用了他人的共享视频（草稿的 `sharedVideoId` 的提供方 ≠ 发布方；`QB_PUBLICATION_SUBMIT` 已经读共享视频：`PublicationSharedVideos.load`，不另读）：
  - 读该运营商的价目，并入 F-M5/F-M6 已有的那一条 `QueryEntities.all`（不多一条语句；条件为空不发 SQL）。运营商没有价目 = 不收、不限（问题 6）。
  - 草稿的 `exposureRequested` 必须有值且 ≥ `qb.exposure.min-purchase`，否则提交被拒 422 `QB_EXPOSURE_PURCHASE_REQUIRED`（新码，带最少次数；与其他提交问题一起报）。
  - 算出单价与预付，作为项 `EXPOSURE`（`amount` = 预付，`chargeAmount` = 0：上架时不记费，结算时才记）。资金不足走 F-M5 同一个 422 `QB_PUBLICATION_FUNDS`，明细多一项。
  - 预付成功后（审查 M1）：**先**写提交的发布版本（`exposureUnit` 随之冻结），**再**在发布锁内以 `CallProcess.of(QB_EXPOSURE_PURCHASED_SET)` 把 `exposurePurchased` 设为购买次数；提交的输出取子流程输出的 `revision`（原因：子流程会再把 revision + 1、改 `editedAt`，若输出仍是提交写入时的 revision，就比库里少 1，向导拿它做下一次的 `baseRevision` 会撞 `QB_PUBLICATION_CHANGED`）。子流程再次取同一把发布锁 `HoldLock`：实施前先确认平台对同一事务内重入的处理（应为已持有即通过），写进锁序说明；语句预算按"待审"与"免审上架"两种分别断言。
- **提交时不买的情况都把已购设为空**（审查 M2）：开关关、运营商没有价目、用自己的视频（或草稿换成了自己的，`exposureRequested` 被忽略并清空）、挑战与店面。
- 驳回、撤回、审核过期：F-M5 的释放全额退回（含 `EXPOSURE` 预付款）；同时 `exposurePurchased` 回到空（草稿上不留已购）。
- **决定 A 放宽**（审查 M2，总控 2026-11-12）：`exposurePurchased` 只经 `PublicationExposure` 的**一个写入辅助方法**写入；`QB_EXPOSURE_PURCHASED_SET` 与回到草稿（`DRAFT` 分支）时的清空都调用它，别处不写。新核对项 `EXPOSURE_UNIT_MISMATCH`：有已购却没有冻结单价，或有单价却没有已购，都算差异。

### 3.4 追加购买 `QB_EXPOSURE_TOP_UP`（新，发起方）
- 输入 `{publicationId, expectedPurchased, additional}`（审查 M3）：`expectedPurchased` 是页面看到的旧已购，与当前不符即 409（`QB_EXPOSURE_PURCHASED_CHANGED`，新码），所以重发同一个请求只买一次；`additional` ≥ 1，总数 ≤ `PublicationExposure.MAX_PURCHASED`，单次追加 ≤ `qb.exposure.top-up.max`（新参数，见第 5 节；保证分摊段数不超过 `MAX_PARTS`，审查 M5）。权限 `qb.publication.write`（同发布编辑），`requiresVerifiedEmail()`，先 `CallerChecks::refuseBanned`，并做 `ConsentChecks.sponsor()`（审查 L）；只能操作自己的发布（`actsOn`）；只在发起方的入口，学者的 App 拿不到（`ProcessAccessIT` 断言）。
- 条件：发布已购（`exposurePurchased` 不为空且有冻结的 `exposureUnit`）；状态在 `SCHEDULED / LIVE / PAUSED / FUNDS_SHORT`（审核中请先撤回；已结束 422，用完前才能买）；`additional` × 冻结单价的预付含税不超过上限。
- **按冻结的单价**（问题 5）；**不审核**（问题 4：共享视频已审核过，只是数字变大）。
- 锁序：发布锁 `qb.pub:<发布>` → 钱包锁 `qb.wallet:SPONSOR:<发起方>`（钱包锁之后不再取锁）。不取开始锁（不改状态）。
- 步骤：钱包锁内一条 `QueryEntities.all` 读账户与该发布 `EXPOSURE` 的预付款；预付款已经不是 `OPEN`（已结清或已释放）即明确的 422 `QB_EXPOSURE_PREPAYMENT_CLOSED`（新码，审查 M6）；资金检查（预付的：可用额 ≥ 追加预付；后付的：未付 + 已预留 + 本次 ≤ 信用额，同 `PrepaymentRules.check`，不足 422 `QB_PUBLICATION_FUNDS`）；子流程 **`QB_PREPAYMENT_TOP_UP`**（新，`subProcessOnly`，`FEE_SYSTEM`，与 `QB_PREPAYMENT_FREEZE` 共用分摊与过账代码）把钱按账户顺序（赠送先、现金后）转入 2180 并**加到已有的那笔预付款**（`amount`、`remaining` 按 H1 的本次冻结增加，新增的分摊 `QbFeePayment` 指向同一笔）；再 `QB_EXPOSURE_PURCHASED_SET` 把总数设为旧值 + `additional`。
- 为什么加到已有那笔，而不是新开一笔：V116 有部分唯一索引 `qb_prepayment_open_purpose_idx (publication_id, purpose) WHERE status = 'OPEN'`，同一发布同一用途至多一笔未关闭的预付款；加到原笔也让结算与释放不用改。
- 追加的预付在下一次结算时与原来的预付一起用；追加购买之前已超出旧已购次数的成功（并发时照记不收的那几次）此后落在新购买的次数内、按 min 一并收（问题 9）。

### 3.5 结算 `QB_EXPOSURE_SETTLE`（新，`internal()`；与配送费"同一结算流程"= 同一套步骤与规则，另立流程与表，理由见第 7 节风险 1）
- 两个任务（`JobDefinition.forEach`，每项一个事务，可经 `JOB_RUN_REQUEST` 补跑，cron 写进 `FeeConfig`，`CRON_TZ=Asia/Tokyo`，部署设置 `QB_EXPOSURE_MONTHLY_CRON` / `QB_EXPOSURE_FINAL_CRON`）：
  - 月度 `qb.exposure-fee`：每月 2 日 00:47（在配送费 00:37 之后，账单任务 01:17 之前）；取项模板 `qb.system.exposure-due`：有 `exposureUnit`、`publisherKind` = `SPONSOR`、上过架、月初之前没有结束、没有 `final` 行、还没有上月行的发布；已结束过宽限的直接写成 `final`（同 F-M6 的 N1，两个任务谁先谁写，另一个看到 `final` 即跳过）。
  - 结束 `qb.exposure-final`：每天 03:12（在配送费 03:07 之后、03:23 释放之前）；取 `endedAt ≤ 运行时刻 − qb.exposure.final.end-grace-sec`、没有 `final` 行的发布，期间 = 结束所在月（该月已有行则取其后第一个没有行的月）。
- **起点**（审查 M4，同 F-M6）：开始计费的时刻 = greatest(`startAt`, `approvedAt`)；取项模板、结算流程、核对项三处用同一个表达式，并加条件测试（审核晚于开始、早于开始各一例）。期间逐月列出（不跳月）；发起方注销之后仍能做 `final` 并退回。
- 每项只取 `qb.wallet:SPONSOR:<发布方>`，**不取发布锁、开始锁**；锁内一条 `QueryEntities.all` 读：该发布各结算行、成功次数（月度：`counted_at` < 下月 1 日 0 时的行数；`final`：全部）、已购（卡片）、`EXPOSURE` 的 `OPEN` 预付款。读到较旧的值只会少算本次，下次按累计补上；`final` 由宽限保证读到最终值。
- 次数 = min（成功，已购）；已有 `final` 行：什么也不写；本次金额为 0 不记费；否则 `QB_FEE_CHARGE`（种类 `EXPOSURE`、`period`、`operatorId` = 提交时冻结的 `billingOperatorId`、`prepaymentId`）。扣款顺序同 F-M6：预付的先 `QB_PREPAYMENT_USE` 从该发布的预付款付，余额 `QB_FEE_SETTLE` 按账户顺序（赠送先、现金后）付，仍不够成未付（账单 `PARTIAL` / `OPEN`），两段合成一张即开账单；后付的记应收（1130），预付款的剩余按本次金额减少。
- **写入顺序**同 F-M6 实施记录第 1 条：先记费，再写结算行（带费用编号），`final` 时 `SaveChanges.now` 之后才调 `QB_PREPAYMENT_RELEASE`（按用途 `EXPOSURE`，在锁内重读 `final` 行）。
- 欠费时进行中的发布照常结算（只拦新提交，`FeeGate` 已有）。
- **与账单的先后**：`qb.system.fee-invoices-due` 的"该月应结算的发布都已有结算行"一条，加上曝光（`QbExposureSettlement`）；迟到的费用规则（账单开出后补记的费用进入记费当月）与 F-M6 的 9.6 相同，已由 `QB_FEE_CHARGE` 做，不改。

### 3.6 释放与结束的各种情况
- **按用途释放**：每日 `qb.prepayment-release` 的取项与 `RELEASE_ITEM` 对 `EXPOSURE` 与 `DISTRIBUTION` 一样——只在有该用途的 `final` 行之后取（发布结束时已有 `final` 才放）。预付款剩余的退回顺序**以代码为准**（审查 M5）：`planRelease` 按 `held` 的顺序退回各段分摊（原冻结与各次追加的分摊都在其中；赠送原笔已到期即作废），同 F-M5。`OperatorSettlementsIT` 加"原冻结用现金、追加用赠送"一例，断言退回的去向。
- **用完结束**（F-S13 的任务）：成功 ≥ 已购即结束；`final` 在宽限后结算全部已购次数（= 预付款扣光，剩余只可能是税的零头 0），释放为 0。
- **提前结束**（发起方自己结束、名额用完、预算用完、违规下架 `ADMIN`、知识结构图退役、共享视频被下架、被暂停后到期等一切原因）：已计数的次数照收，**未用的预付一律退回**（预付款不是费用，与结束原因无关；不需要贷项凭证）。
- **到了结束时间仍没用完**：同样退回未用的预付（问题 7）。
- **暂停 / 预算不足**：卡片不在进行中时不计数，预付款不动。
- **共享视频被下架**（`SHARED_VIDEO` 结束）：已用的收，未用的退。
- **已购次数不能减少**（没有"退购"页面）；唯一减少的方式是结束，所以累计 min（成功，已购）单调不减，累计取整成立。
- **作废之后预付款剩余**（审查 L5）：整张账单作废只冲正费用，不动预付款；预付款剩余照旧在结束后按上面的顺序退回。`operations.md` 新小节的争议一段写清这一点，免得财务以为要手工补回预付款。
- **争议**（发起方说次数不对，`operations.md` §7.31 的排查）：已收的费用只能更正：整张账单作废（四眼 `QB_FEE_REQUEST` `VOID`）或手工贷项凭证（F-M7 的 `CREDIT_NOTE`，适用于任何种类的费用）。**依赖 F-M7（未合并）**；F-M7 合入前只有整张作废。

## 4. 账本科目与分录

都用已有科目，**不新增科目**；映射参数 `qb.journal.account.1130|2150|2160|2180|2190|4120|5140` 已有（`QbParams.JOURNAL_ACCOUNT_*`），不新增映射。2180 的维度：发起方（`party`）、发布、用途 `EXPOSURE`（第 3 维度）。账本只经 `LEDGER_POST` / `LEDGER_REVERSE`（`QB_PREPAYMENT_*` 与 `QB_FEE_*` 已调用，本项不直接过账）。

| 事件 | 过账（来源，参照） | 借 | 贷 |
|---|---|---|---|
| 提交时冻结预付（预付的发起方） | `QbPrepayment`，`prepay_exposure` | 2190（赠送部分）、2160（现金部分） | 2180（发起方、发布、`EXPOSURE`） |
| 同上，后付的发起方 | 不过账，只占信用额 | — | — |
| 追加购买 | 同上（加到已有预付款） | 2190、2160 | 2180 |
| 结算记费 | `QbFeeCharge`，`fee_exposure_<月>` | 1130（合计） | 4120（金额）、2150（税） |
| 从预付款付 | `QbFeeCharge`，`QB_PREPAYMENT_USE` | 2180 | 1130 |
| 预付款不够，从账户付 | `QB_FEE_SETTLE` | 2190、2160 | 1130 |
| 后付：开账单后付款 | 照 F-M1 | 2190、2160 | 1130 |
| 结束后退回未用预付 | `QbPrepayment`，`prepay_exposure`（`QB_PREPAYMENT_RELEASE`） | 2180 | 2160（现金部分）、2190（赠送部分） |
| 退回到已到期的赠送 | 当即作废（同 F-M5） | 2190 | 5140 |
| 驳回 / 撤回 / 审核过期 | 同上全额退回 | 2180 | 2160、2190 |
| 争议更正（依赖 F-M7） | `QbFeeCreditNote` | 4120、2150 | 1130，再按 F-M7 的规则退回或抵欠款 |
| 作废整张账单 | 冲正，`LEDGER_REVERSE` | 同 F-M5 | 同 F-M5 |

- 运营商的分成：**曝光费进运营商的分成基数**（与 F-M12 计划的种类清单一致，审查 F-M12 L5）。费用的 `operatorId` 为提交时冻结的运营商。现在的 `qb.system.operator-fees` 按费用求和，`EXPOSURE` 随之计入（`OperatorSettlementsIT` 加一例确认）；F-M12 改为按费用的期间与逐笔明细之后，曝光费照它的规则计入，本项不另做。
- 提供方与使用方之间的钱不在账本里（线下）。

## 5. 参数（都在 `QbParams`，缺省由 `QB_SETUP` 提出；金额、免审开关列入 `CONTROLLED`）

| 键 | 类型 | 缺省 | 范围 | 受控 | 说明 |
|---|---|---|---|---|---|
| `qb.exposure.billing.enabled` | 布尔 | false | — | 是 | 只在提交时读；`FirstLaunchSwitches` 那一行由 `waiting` 改为 `active(…, true, 原因)`，上线前由需求方确认下面两个占位值 |
| `qb.exposure.unit.min` | 数 4 位小数 | 1.0000（占位，日元 / 次） | ≥ 0 | 是 | 总部范围：运营商单价的下限；占位值打开开关前由需求方确认（同 `qb.distribution.unit-floor.min`），用 `keep` 行登记 |
| `qb.exposure.unit.max` | 数 4 位小数 | 100.0000（占位） | ≥ min | 是 | 总部范围：单价上限 |
| `qb.exposure.min-purchase` | 整数 | 100（占位，次） | 1 – 999,999,999 | 是 | 提交时最少购买次数；追加购买不设最少（问题 3） |
| `qb.exposure.final.end-grace-sec` | 整数 | 3600 | 60 – 86400 | 是 | 结束后多久才做最后一次结算（让途中的报告落定）。**与 F-S13 已有的 `qb.exposure.reconcile.end-grace-sec`（不受控，60，每日核对用）名字相近、含义不同**，两边的参数说明各写一句；下界 60 同 F-M6 的 L1。宽限够用的理由：引用 F-S13 的计数规则（结束之后到达的曝光上报还算不算），实施时在说明中写明 |
| `qb.exposure.unsettled-after-hours` | 整数 | 30 | 1 – 720 | 是 | 月初后多久仍没有结算行算差异（同配送费） |
| `qb.exposure.final-missing-after-hours` | 整数 | 48 | 1 – 720 | 是 | 结束后多久仍没有 `final` 行算差异 |
| `qb.exposure.low-ratio` | 比例 | 0.1 | 0 – 0.5 | 否 | 剩余 ≤ 已购 × 比例时提醒；0 即不提醒 |
| `qb.exposure.top-up.max` | 整数 | 1,000,000（占位） | 1 – `PublicationExposure.MAX_PURCHASED` | 是 | 单次追加的上限，保证分摊段数不超过 `MAX_PARTS`（审查 M5） |

- 每个参数在 `QbParams` 中写明上下界与说明（审查 L1）；总部范围没设（`unit.min` / `unit.max` 缺）时，设价目与提交都返回 422（不按 0 处理）；新参数并进已有的 `LoadParams`，不另读。

- 部署设置（不是业务参数，在 `FeeConfig`，同配送费）：`QB_EXPOSURE_MONTHLY_CRON`、`QB_EXPOSURE_FINAL_CRON`、`QB_EXPOSURE_LOW_CRON`（缺省每 10 分钟）。
- 单价是运营商价目表里的数据，不是参数；税率沿用 `qb.fee.tax-rate`；每笔上限沿用 `FeeProcesses.MAX_AMOUNT`；最大购买数沿用 `PublicationExposure.MAX_PURCHASED`。代码里没有写死的数字。
- 规格里的"运营商价目 `unit`、`unit-floor`、`unit-ceiling`"这里读作：运营商定一个单价 `unit`，总部定允许的下限与上限（问题 2）。

## 6. 页面与运营闭合

- **发起方页面**（`quizbuks-web/sponsor`）：
  - 发布向导：选了他人的共享视频后，"资金"一步（`publishing/FundsPanel.tsx`）多一行曝光费——输入购买次数（显示最少次数、单价、预付含税、账户可用额与"去充值"）；数据来自 `QB_PUBLICATION_FUNDS_CHECK` 的输出（`CheckItem` 加购买次数与最少次数，OpenAPI 快照若受影响则更新）。
  - 发布页"共享视频的曝光"一节（`exposure/ExposureCounts.tsx`）加：已购 / 已用 / 剩余与进度条、"追加购买"对话框（单价、追加金额含税、账户余额、确认）、剩余不足一成的横幅、历次结算（新模板 `qb.sponsor.publication-exposure`，仿 `qb.sponsor.publication-distribution`：单价、上次结算以来的成功次数与预计金额）。
  - 平台费账户的费用列表按种类显示"曝光费"（消息字典）；账单行描述用已有的种类名。
  - 帮助 `docs/quizbuks/help/exposure.md` 与 `fee-account.md` 三语：预付款不是费用、用多少收多少、超出已购的不收、结束后退回未用、追加购买、争议怎么办。
- **运营商管理系统**（`admin-extension`，仿 `DistributionPages.tsx`）：曝光费价目页（负责人可改、成员只读，理由必填）、本运营商发起方的结算列表（`qb.operator.exposure-settlements`，运营商范围，登记 `OperatorScopes`）。
- **平台管理系统**：各运营商价目与总部范围（范围链到受控变更）、全部结算列表（`qb.admin.exposure-settlements`，`qb.admin.finance.read`，可导出）、上线开关页（`fund-switch-messages.ts`）的曝光行。
- **"待我处理"加"曝光即将用完"**（审查 L）：剩余 ≤ 比例的进行中发布出现在发起方的"待我处理"。
- **提醒**：新任务 `qb.exposure-low`（`JobDefinition.forEach`，逐项流程 `QB_EXPOSURE_LOW_NOTICE_ITEM`，`internal()`，取项模板 `qb.system.exposure-low-due`）：进行中、有已购、剩余 ≤ 已购 × `qb.exposure.low-ratio`、`QbExposureNotice.notifiedFor` ≠ 当前已购的发布，给发起方发站内信与邮件（沿用 `PublicationNotices` 的发送方式；新三语文字：三语 `mail.<名>.name`、每个参数的 `.sample(…)`、`.entries("sponsor")`、`t.protectedText()`，并加进 `MailTemplatesCoverageTest.PROTECTED`（审查 L3）；邮件文字可在线修改的规则照 `MailText` 的做法登记），并写 `QbExposureNotice`。追加购买后已购变了，再次可提醒。
- **每日核对**（随 `quizbuks.reconcile.run-cron` 的日常对账，`ReconChecks` 加项，时长都是受控参数）：
  - `EXPOSURE_DEPOSITS`：2180 用途 `EXPOSURE` 按发布 = `OPEN` 预付款剩余；
  - `EXPOSURE_SETTLED_OVER`：某发布各结算行的次数之和 > 已购次数（结算次数 ≤ 已购，规格）；
  - `EXPOSURE_UNSETTLED`、`EXPOSURE_FINAL_MISSING`：同配送费（用 `qb.exposure.unsettled-after-hours`、`.final-missing-after-hours`）；
  - `EXPOSURE_PRICE_RANGE`：运营商价目超出总部范围；
  - `EXPOSURE_UNPURCHASED`（只提示，不算差异，放 `ReconChecks.NOTES`）：进行中、用他人视频、却没有已购的发布（开关打开前提交的，或运营商没有价目的），供运营商与总部查看。
  - F-S13 的 `qb.exposure-check`（成功 ≤ 已购 + 容差）不变。
- **月报**（`qb.admin.monthly`，`monthly.sql`，分类按参照 `prepay_exposure` / `fee_exposure_<月>` 分出，仿 `DISTRIBUTION_*`）：`EXPOSURE_DEPOSIT`（冻结与追加）、`EXPOSURE_RELEASED`、`EXPOSURE_CHARGED`；`PREPAYMENT_FROZEN / RELEASED` 不再含这些；`AdminFinanceIT` 逐行。
- **`operations.md` 新小节**（编号在开 PR 时取当时最大的小节号 + 1；F-M7 也会加一节，排在它后面）：价目与范围、开关只在提交时读、关掉不追溯、购买与追加、两个结算任务与账单任务的先后（00:37 配送月度、00:47 曝光月度、01:17 账单（1 日除外）、03:07 配送结束、03:12 曝光结束、03:23 释放）、补跑、月界按 `counted_at`、提醒、核对项处理、争议；**先后顺序靠数据条件保证，不靠时刻**（审查 M7：月度与结束两个任务、配送费与曝光费、账单任务，每个都先读条件再动，时刻只是排开）；§7.31 的"F-M8 的争议在那里处理"一句改为指向它。
- **`manual-sponsor` 初稿**（审查 L）：三语的"曝光费"一节（购买、追加、用完、退回）。
- **迁移的 CHECK 约束**（审查 L）：`qb_fee_charge_version` 的种类 CHECK 加 `EXPOSURE` 要替换约束；说明它不改封存过的列（`SealedColumnsHistory` 只看列），PR 中写明。
- **其他文档**：规格 F-M8 改写（读作的范围与追加购买不审核、未用的一律退回、月界按 `counted_at`、直营照付）；`first-launch-progress.md`、`F-F10.md` 的开关表（第 18、92 行）、上线检查清单一行；`backend/quizbuks/CLAUDE.md` 加一条；用词按 `glossary.md`（发起方、学者、运营商、视频提供方、平台管理系统，不写"后台""商户"）。

## 7. 测试清单

- **单元**：`ExposureFeeRulesTest`（单价范围、预付含税与上限、计费次数 min、累计取整如单价 0.4：1、1、1 次 → 1、0、1、税向下取整、无价目）；`UnitFeeRulesTest` 与 `DistributionRulesTest` 照跑（抽出后行为不变）；`FirstLaunchSwitchesCheckTest` 随该行改为 `active`。
- **`ExposureFeeIT`**（新，仿 `DistributionIT`；不用 H2，真实 PostgreSQL，BlockHound 开）：
  - 开关：关时不买不收、`exposurePurchased` 为空；开着提交 → 关掉 → 照常结算并释放；
  - 用自己的视频不收、挑战 / 店面不收、直营发起方照付；运营商没有价目 = 不收不限；
  - 提交：次数 < 最少被拒（422 `QB_EXPOSURE_PURCHASE_REQUIRED`）；预付 = ⌈次数 × 单价⌉ + 税；资金不足一个错误码什么也不写；预付、后付各一例；草稿换成自己的视频后不买；驳回 / 撤回 / 审核过期全额退回且已购清空；
  - 单价冻结：运营商改价后已提交的不变；改派运营商后仍归提交时的运营商；
  - 结算：0 次不记费；按累计取整；月界按 `counted_at`（月末前 / 后各一次，补跑结果相同）；超出已购不收（成功 3、已购 2 → 收 2）；**赠送额度先于现金**（冻结时与从账户付时各一例）；先扣预付、不够从账户、再不够 `PARTIAL` / `OPEN`；后付记应收、预付款剩余减少；并发两次只成一行；月度与每日 `final` 交叠只成一行（两种先后）；
  - 释放：用完结束（F-S13 任务）后 `final` 结清、释放为 0；提前结束（各原因）退回未用，赠送退回原笔、已到期作废；到期仍没用完也退回；另一用途 `OPEN` 时不提前释放；
  - 追加购买：预付 / 后付 / 资金不足 / 已结束 422 / 审核中被拒 / 未购买的被拒 / 别人的发布 404；预付款是**同一笔**加额（唯一索引不冲突），分摊多一段，释放按全部分摊；追加后之前超出的几次按 min 一并收；
  - 提醒：剩余不足一成只提醒一次，追加后再提醒；`low-ratio` 为 0 不提醒；
  - 账单先后：曝光的结算未写之前该发起方该月不开后付账单；账单开出后补跑的费用进入当前月；
  - 不变：学者的 feed / 发布模板开关前后相同；`qb_fee_charge_version` 与结算表没有 UPDATE / DELETE；`PrepaymentIT` 的 `AUDIENCE` 三例与 `DistributionIT` 全部照跑。
- **审查补的测试**（总控 2026-11-12；Fable 的清单）：
  - 追加购买与用完结束任务并发（成功数已 ≥ 旧已购、分钟任务正在结束）：两种先后各一例，断言不会出现"钱已冻结、发布已结束"却没人退款（由 `final` 加释放退回）；
  - 追加购买与月度结算并发（一方持发布锁 → 钱包锁，一方只持钱包锁）：只成一行，金额与串行时相同；
  - 免审直接上架的提交：卡片上的 `exposurePurchased` 正确，语句数另计；
  - 时态表：`qb_publication_version` 在提交事务中是两条 INSERT，没有 UPDATE；
  - `PublicationPrepaymentsTest`：挑战、店面、自己的视频都不生成 `EXPOSURE` 项；
  - e2e 的"追加购买"在本机跑，并覆盖 422 资金不足的明细；
  - M4 用 `DistributionDueConditionTest` 式的条件测试（取项、账单等待、核对三处同一表达式）；
  - H1 追加按累计："105 + 5"的例子，一次买 110 与先买 105 再追加 5 的总预付相同；页面显示的金额同函数；
  - H2 累计税：用完时恰好扣光；两次结算之间改税率（升、降各一例）；
  - H3 漏一个月 → 下月有成功 → 补跑漏掉的那个月：补跑的月写 0 次、不记费，总数不重复；
  - M2 各种不买的提交都使已购为空（"残留已购"）；核对项 `EXPOSURE_UNIT_MISMATCH` 正反各一例（核对项的码存在 `varchar(30)` 列中，原名 31 个字符会被截断而丢掉，所以改短；新码都 ≤ 30 个字符，由 S1 加的守护测试检查，总控 2026-11-13）；
  - M3 追加重发只买一次（第二次 409）；
  - M4 起点 greatest(startAt, approvedAt) 的两种先后；注销后仍能 `final` 并退回；
  - M5 原冻结用现金、追加用赠送的退回去向；单次追加上限；
  - M6 价目单价为 0 被拒；预付款不是 `OPEN` 时追加 422；
  - L：追加的同意检查；App 入口拿不到；范围没设 422；提醒邮件的三语。
- **`ExposureFeeStatementBudgetIT`**（仿 `DistributionStatementBudgetIT`，用 `SqlStatementLog`）：提交的确切语句数——开关关 / 开 × 用他人视频与否 × 有无受众条件 × "待审" / "免审上架"（审查 M1），说明每一项；**不用他人视频的提交语句数与 `SubmitStatementBudgetIT` 现值相同**（价目读取并入已有那一条，调用 `QB_EXPOSURE_PURCHASED_SET` 只在用他人视频时发生）；追加购买与结算的语句数各断言一次；月费、手工费、`AUDIENCE` / `DISTRIBUTION` 记费的语句数与改前相同（N3）。`AttemptStatementBudgetIT` 不变（答题路径不碰曝光费）。
- **IT 其他**：`AdminFinanceIT`（月报逐行、`EXPOSURE_*` 分类）、`WalletReconciliationIT` 或现有对账 IT（每个新核对项正反各一例）、`OperatorSettlementsIT`（曝光费计入分成）、`SetupIT` / `FirstLaunchSetupIT`（参数表）、`ProcessAccessIT`（新流程的权限与 `internal()` / `subProcessOnly` 声明）、`RetentionIT` / `ErasureIT`（新表）、`SealedColumnsHistory`（迁移）、`ArchitectureTest`（不引用 reactor）。
- **场景快照**：`setup` 场景的 `paramsCreated`；一个新场景"共享视频的发布购买曝光 → 学者成功若干 → 结算 → 结束退回"，`-Dscenario.update-snapshots=true` 更新并核对差异。
- **前端**：发起方页面 Vitest（向导的曝光行、追加购买对话框、剩余提醒横幅、历次结算；亮暗两种外观 axe）；`admin-extension` 的 `exposureFee.test.tsx`（价目页、结算列表、空与权限、axe）；`pnpm app:check` 类检查照常。
- **端到端**（`quizbuks-web/e2e/tests/launch-loop.spec.ts` 第 2′ 步的合计含曝光预付，开关打开；新增一步"学者看够、`JOB_RUN_REQUEST` 补跑结算、发起方页面看到已用与结算"；`exposure.spec.ts` 加"用完后结束并退回未用"与"追加购买"）。

## 8. 与 F-M7 的关系（"依赖 F-M7（未合并）"一览）

读 `origin/1.3/quizbuks-FM7-audience`（草稿：受众规则费的上架后退回、贷项凭证 `QbFeeCreditNote`，迁移 V119）得到：
1. **争议更正用手工贷项凭证**（`QB_FEE_REQUEST` 新种类 `CREDIT_NOTE`，规格是"适用于任何种类的费用"）：**依赖 F-M7（未合并）**；本项要加：`EXPOSURE` 费用可开凭证的测试（`CreditNoteIT` 加一例）、`FEE_CREDIT_NOTE` 月报分类对曝光费的反向分录。F-M7 不合入时，曝光费只能整张账单作废。
2. **共用文件会冲突**（F-M7 已改）：`FeeConfig`、`FeeProcesses`（`QB_FEE_CHARGE` 加了"先用未用贷项"）、`FeeCodes`、`FeeRules`（`KINDS`）、`FeeSettlement`、`QbParams`（末尾追加）、`FirstLaunchSwitches`（`KEY_BEFORE_MEANING` 缩小、受众行改 `active`）、`monthly.sql`、`recon-fee-account.sql`、`recon-fee-receivable.sql`、`operator-fees.sql`、`sponsor/wallet.sql`、`ReconChecks`、`Reconciliation`、三语消息、`setup.snapshot.json`、`operations.md`（F-M7 新加的小节）、`first-launch-spec.md`、`F-M.md`。**依赖 F-M7（未合并）**：本项在它合入后的 `1.3/quizbuks` 上做；`setup.snapshot.json` 后合者合并基础并重生成。
3. **"欠款扣掉未用贷项"的统一读法**（F-M7 的 `FeeOwedIT`：所有读"欠多少"的地方口径一致）：本项后付发起方追加购买时的信用额检查读 `qb.system.fee-receivable`，须用 F-M7 合入后的口径。**依赖 F-M7（未合并）**。
4. **迁移号**：V118 已被 F-F11a 用，F-M7 暂定 V119；本项在开 PR 时取当时的下一个空号，不预占。

## 9. 风险

1. **另立流程与表**（总控 2026-11-12 同意，审查 M7）：配送费已上线的逻辑（含 N1–N5 的交叠与顺序）不动，回归风险最小；代价是两处相似的代码，所以抽出 `UnitFeeRules` 并让两边共用步骤的辅助方法。规格 F-M8 与 `F-M.md` §9.1 的写法已在本 PR 改为"同一套规则与步骤、各自的流程与表"。
2. **提交时调用 `QB_EXPOSURE_PURCHASED_SET`**（审查 M1、M2 已定）：先写提交的版本，再调子流程，输出取子流程的 `revision`；子流程重入发布锁的行为实施前确认。已购只经 `PublicationExposure` 的一个写入辅助方法。
3. **月界按 `counted_at`**：与配送费的"读计数器的时刻"不同。对 F-M12（运营商按费用的 `period` 分成）更准；但要加部分索引，并在 `operations.md` 写清两种费用月界的差别，免得对账时混淆。
4. **成功次数的计数**：一个发布最多 999,999,999 次，月度按索引数行；`final` 数全部。先看 V97 的索引，必要时才加；单发布的行数大时关注结算耗时（任务逐项、每项一个事务，不影响他人）。
5. **并发超出与追加购买**：超出已购的照记不收，追加后按 min 补收，可能让发起方意外；页面与帮助写明（问题 9）。
6. **已购不能减少**：没有退购，只有结束退回；若需求方以后要退购，累计取整的单调性要重新论证。
7. **开关打开后每次提交多一条价目读取**：已并入现有读取，用语句数测试证明不多于现有；`SubmitStatementBudgetIT` 的数字应不变。
8. **占位值**：单价范围与最少购买次数是占位，打开开关前必须由需求方确认（同配送费下限），登记在 `FirstLaunchSwitches`。
9. **与 F-M15 的接口**：封禁、注销时的最后一次结算与释放本项不做；这期间仍有进行中的曝光预付款，核对 `EXPOSURE_FINAL_MISSING` 会报，处理方式同配送费。

## 10. 要问需求方的问题（简单说法，每条附推荐答案）

1. **一个价格还是两个？** 竖屏模式里的曝光和开始答题页的曝光，是按同一个价格收，还是竖屏一个价、横屏一个价？推荐：**同一个价**（首次上线简单，以后可以分）。
   - **答复**：需求方 2026-11-13：按推荐。
2. **价格怎么定？** 规格写了"单价、下限、上限"。我们的理解是：每个运营商定一个单价，总部规定单价不能低于多少、不能高于多少。对吗？推荐：**对**。
   - **答复**：需求方 2026-11-13：按推荐。
3. **最少买多少次？** 提交发布时至少要买多少次曝光（暂定 100 次，只是占位）？追加购买要不要也有最少次数？推荐：**100 次作占位，上线前您定数；追加购买不设最少**。
   - **答复**：需求方 2026-11-13：按推荐（100 次作占位，上线前由需求方定数，已记入 `launch-checklist.md`"资金开关"；追加购买不设最少）。
4. **追加购买要不要审核？（这一条要改规格）** 规格现在写的是"追加购买按发布修改的审核规则"，也就是要审核。我们建议改掉这一句：共享视频早已审核过，追加只是把次数加大，没有新内容。推荐：**不审核，付了钱就生效**；您同意的话，规格里这一句随本项改写。
   - **答复**：需求方 2026-11-13：按推荐。
5. **追加购买用什么价格？** 提交时已经定了单价。追加时按当时定的价，还是按运营商现在的新价？推荐：**按提交时定的价**（同一个发布的价格不变，账单好懂）。
   - **答复**：需求方 2026-11-13：按推荐。
6. **运营商没设曝光价格时怎么办？** 用共享视频的发布，是照常提交、不收曝光费也不限次数（和配送费"没有价目就不收"一致），还是不让提交？推荐：**照常提交、不收、不限**，每日核对里列出来给运营商和总部看。**后果**：开关打开后，没设价格的运营商下面的发起方用别人的视频完全免费，而且不限次数，平台和视频提供方都拿不到这部分钱，直到运营商补上价格（补上后只对之后提交的发布生效）。所以上线清单加了一项"打开曝光费的开关之前，各运营商都已设好价格"。
   - **答复**：需求方 2026-11-13：按推荐。
7. **到了结束时间还没用完的次数，退钱吗？** 推荐：**退回没用的预付**（和配送费的预付一致，提前结束也一样退）。
   - **答复**：需求方 2026-11-13：按推荐。
8. **违规下架的发布，没用的预付退吗？** 推荐：**退**（预付不是费用，已经用掉的次数照收）。
   - **答复**：需求方 2026-11-13：按推荐。
9. **多出的几次算不算？** 买的 100 次用完时，系统来不及停，多出了 3 次没收费。之后又追加了 50 次，这 3 次算不算在新买的 50 次里？推荐：**算**（按"成功次数和已购次数取小"统一计算，规则最简单）。
   - **答复**：需求方 2026-11-13：按推荐。
10. **剩余不足多少时提醒？怎么提醒？** 推荐：**剩余不到一成（可调的参数）时，发站内信和邮件各一封，同一个已购次数只提醒一次，追加购买后可再提醒**。
    - **答复**：需求方 2026-11-13：按推荐。
11. **发起方说次数不对时怎么更正？** 推荐：**由财务开一张"更正凭证"把多收的钱退回，已收的费用记录不删**；在这种凭证上线之前，只能把整张账单作废后重开。
    - **答复**：需求方 2026-11-13：按推荐。

12. **开关打开之前已经提交的发布怎么办？** 这些发布现在用别人的视频是"不限次数、免费"。开关打开后，它们照旧免费（不追溯），只在每日核对里提示出来。推荐：**照旧免费，不追溯**，等它们自然结束。
    - **答复**：需求方 2026-11-13：按推荐。
13. **用完并已结束的发布，想继续只能复制一份重新提交，对吗？** 推荐：**对**（结束的发布不能再追加购买）。
    - **答复**：需求方 2026-11-13：按推荐。
14. **后付的发起方买曝光：只占信用额，月底进账单，不先扣钱，对吗？** 推荐：**对**（与配送费的后付相同）。
    - **答复**：需求方 2026-11-13：按推荐。
15. **快用完的提醒发给谁？** 只发给发起方本人，还是也发给他的运营商？推荐：**只发给发起方本人**；运营商在自己的结算列表里能看到剩余。
    - **答复**：需求方 2026-11-13：按推荐。
16. **购买次数要不要设一个上限？** 现在只有技术上限 999,999,999 次。推荐：**加一个业务上限参数**（缺省 1,000,000 次，以后可调），防止手误买得太多。
    - **答复**：需求方 2026-11-13：按推荐。

另告诉需求方（总控已定，不用问）：曝光费的预付与按月收费的取整方式保证**用完时预付恰好扣光**，不多不少。

**删除的功能：无。** 开关不打开，行为与现在完全相同（用他人视频的发布仍然不限次数、免费）；对现有流程只是加项（提交的资金项、结算、释放的用途判断），`QB_EXPOSURE_PURCHASED_SET` 与 F-S13 的计数、结束、核对都不改。

## 11. 审查（Fable）与总控的决定（2026-11-12）

审查对象 #660 的 `88c1920b`；决定已写进上文各节，这里只列去处。
- **高**：H1 追加的预付按累计算（§3.1、§3.4、§7）；H2 曝光费的税按累计算，配送费不变，税率改动写明并测试（§3.1、§7）；H3 每期累计次数的规则原文与"漏一个月再补跑"的测试，月度用运行时的已购（§3.1、§7）。
- **中**：M1 先写提交的版本再设已购，输出取子流程的 `revision`，重入的锁实施前确认，语句预算分两种（§3.3、§7、§9）；M2 决定 A 放宽为一个写入辅助方法，不买时已购为空，新核对项（§3.3、§6、§7）；M3 追加带期望的旧已购，不符 409（§3.4、§7）；M4 起点 greatest(startAt, approvedAt)，逐月，注销后仍能结清（§3.5、§7）；M5 退回顺序以代码为准，单次追加上限参数（§3.6、§5、§7）；M6 单价 > 0、预付款不是 `OPEN` 时 422（§3.1、§3.4）；M7 另立流程与表，规格与 `F-M.md` §9.1 的写法随本 PR 改，先后靠数据条件（§6、§9）。
- **低**：L1–L5 全部采纳（§3.4、§3.6、§5、§6、§7）。
- **问需求方**：问 4 直说是改规格，问 6 写出后果并加清单项，问 9 用 Fable 的说法，问 11 不出现 F-M7；Fable 的新问题中第 2 条（追加后的零头）已由总控定为按累计算，不问，只告诉需求方"用完时会恰好扣光"；其余 5 条为问 12–16。
- **其余原文要点**：M1 第 3 点（输出的 revision）、L1（两个宽限参数的区别与下界 60）、L3（邮件模板的四项与覆盖测试）、L5（作废后预付款剩余不变，写进 `operations.md`）都已写进上文。
