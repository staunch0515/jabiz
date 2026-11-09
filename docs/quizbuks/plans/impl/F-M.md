# F-M 钱（首次上线）：实施计划

规格：`docs/quizbuks/first-launch-spec.md` 的"钱（首次上线）"（总览与 F-M1～F-M15）、第 4 部分"已定"（需求方 2026-11-07 晚第 1、2 条；由 Claude 先定的第 5～9 条，已答复"未决"1～5），以及 F-S13 与 F-M8 的接口（总控 2026-11-09 决定 A）。本计划只写拆分、现状与要点；每个 PR 开工前在本文件补该 PR 的细化与实施记录（照 `F-S2.md`）。

## 0. 每项要点

| 项 | 要点 | 现状 |
|---|---|---|
| F-M1 | 一个"可支付平台费的账户"：赠送额度（新钱包种类 `FEE_CREDIT`，新科目 2190，按笔 `QbFeeCreditGrant`，最早到期先用）+ 现金（2160，按进账先后）；一切平台费与预付都经同一个"付款分摊"（`QbFeePayment`，只追加、`writeOnce`）；记费 借 1130 / 贷 4120 + 2150，付款 借 2190 / 2160 / 2180、贷 1130 | 现金账户 2160（Q87a，`QbWallets.CASH`）、`QB_FEE_CHARGE` / `QB_FEE_INVOICE_PAY` / `QB_FEE_PAY_EARLY`（Q87b，`fee/FeeProcesses`）只从现金付；没有赠送额度、没有分摊记录、没有"按进账先后" |
| F-M2 | 赠送只经 `QB_FEE_CREDIT_GRANT`（`subProcessOnly`）；来源 `ONBOARDING`（运营商负责人确认资料完整）、`CERTIFIED_TOP_UP`、`MANUAL`（四眼、追加上限）、`LIGHT`（点亮赠送）；每笔 90 天到期，每日作废剩余（借 2190 / 贷 5140）；到期提醒 | 没有 |
| F-M3 | 企业实名认证（公司名 + 域名、确认链接、运营商另一位成员审核、同域名唯一、撤销）；通过即 `CERTIFIED_TOP_UP` | 没有（代码中没有任何发起方认证） |
| F-M4 | 运营商账户照旧；负责人把运营商账户的收入转给**本运营商的直营发起方**（`QB_OPERATOR_TRANSFER_TO_DIRECT`，借 2140 / 贷 2160，现金来源"运营商转入"） | 运营商账户与提现（Q48e-2，`operator/OperatorWithdrawals`）已有；没有转入 |
| F-M5 | 提交时一次资金检查（422 `QB_PUBLICATION_FUNDS`，带各项金额），按整个账户；预付冻结到 2180（用途维度）；驳回、撤回、审核过期、结束后释放（一个子流程，按原分摊退回，赠送退回原笔） | 只有 `FeeGate`（有未付账单即拦，`qb.fee.overdue.block-submit`）；规格说取代的 `QB_DISTRIBUTION_FUNDS`、`QB_AUDIENCE_FEE_FUNDS` 在代码中**不存在**（Q101、Q47c-2 都没做） |
| F-M6 | 配送费：按开始答题的人数 × 单价（clamp（比例 × 最高奖励, 下限, 上限），提交时冻结，四位小数），⌈⌉ 到日元另加税；预付 `maxWinners` × 单价；每月 2 日按开始计数器的差额结算；运营商价目表、总部范围 | 没有（Q101 未实现）；开始计数器 `QbPublicationStartCounter` 已有 |
| F-M7 | 受众规则费：提交时扣；没有结束时间的扣到月底、每月最后一天续扣下月；不足即未付、宽限 7 天后结束；退回（预付作废、后付贷项凭证） | Q47c-1 只有价目与报价（`audience/AudienceBilling`、`AudiencePricingProcesses`），不扣款；还有 `direct` 价目表与 `qb.audience.price.fallback` |
| F-M8 | 曝光费：提交时购买次数 ≥ 最少、预付；与配送费同一结算（次数 = min（成功, 已购））；追加购买；用完结束由 F-S13 的 `qb.exposure-ends` 做 | 计数、结束、核对已由 F-S13（#569）做好；写已购只经 `QB_EXPOSURE_PURCHASED_SET`；没有价目、预付、结算 |
| F-M9 | AI 额度：从账户买（2180 用途 `AI`）；请求时预留最高费用，结束按实际；每月按（发起方, 作业时的运营商）汇总记一次 `AI` 费；赠送部分保留原到期；可退回 | `content/ai/AiCost` 只有估算（不计费）、`qb.ai.price.*`、锁 `qb.ai:<发起方>`（`QbAi`）；没有额度与记费 |
| F-M10 | 学者与帮助的 AI 由平台承担，用量独立（`QbHelpUsage`），不进 AI 额度 | Q109a（F-C10，S3）**还没开始**，没有 `QbHelpUsage` |
| F-M11 | 归属：发布相关的费用按提交时冻结的运营商；AI 按作业时；赠送按发放时；扣回跟着费用；`QbFeeCharge.operatorId` 由调用方给出 | `QbFeeCharge.operatorId` 现在是记费时现查 |
| F-M12 | 结算基数 = 以现金、预付的现金部分、应收支付的平台费；不再含 4110 抽成；扣回用掉的赠送（贷 5140），扣不完结转；任务按"配送与曝光结算、AI 月度记费 → 后付账单 → 运营商结算"的完成先后 | `operator/OperatorSettlements`（Q48b、Q87b）：基数含 4110 抽成与"平台费"一行；任务靠时刻错开 |
| F-M13 | 账单 `OPEN`/`PARTIAL`/`PAID`/`VOID`（`OVERDUE` 照旧）；PDF 写明各来源；到账自动付最早的未付账单；贷项凭证；作废按原分摊退回 | Q87b 账单与 PDF（`qb.fee_invoice` 版式、`qb.fee-invoice` 编号）已有；没有部分付款与贷项 |
| F-M14 | 月报新类、新列 2180 / 2190 / 5140、费用种类分列；每日核对新项；差异给财务待办 | `qb.admin.monthly`、`qb.reconcile`（`wallet/Reconciliation`，`recon-*.sql`）已有 |
| F-M15 | 封禁：不能提交、买 AI 额度、领赠送；结束的发布照常结算释放。注销：先结清未付、作废全部赠送（含冻结部分）、现金照现有处理；新表登记 `PersonalDataEraser` | 封禁与注销（10 §17、`QB_SPONSOR_BAN` 等）已有，钱的收尾没有新表 |

## 1. 拆分（依赖顺序）

规格的顺序是 F-M1 → F-M2/3/4 → F-M5 → F-M6～10 → F-M11/12 → F-M13～15。为了每个 PR 都能独立上线（开关缺省关），做两处调整：**F-M11 提前到 F-M5**（费用记费时就要给出冻结的运营商，后补等于改两遍），**F-M13 拆开**（付款顺序必须随 F-M1，否则账单还从现金付；贷项凭证随第一个需要退回的 F-M7）。**F-M14 不单列**：每个 PR 把自己的新过账加进月报与核对（"每一种新过账都进月报与核对"），最后一个 PR 做总扫与 `AdminFinanceIT` 逐行。

| PR | 内容 | 迁移 | 粗估 |
|---|---|---|---|
| **M1** | F-M1 + F-M13 的付款部分 | 是 | 6–7 天 |
| **M2** | F-M2（`ONBOARDING`、`MANUAL`、到期与提醒；不含 `LIGHT`） | 是 | 3–4 天 |
| **M3** | F-M3 + `CERTIFIED_TOP_UP` | 是 | 3 天 |
| **M4** | F-M5 + F-M11 + F-M7（受众规则费）+ F-M13 的贷项凭证 | 是 | 7–8 天 |
| **M5** | F-M6 配送费 | 是 | 5–6 天 |
| **M6** | F-M8 曝光费 | 是 | 3 天 |
| **M7** | F-M9 AI 额度与 AI 费 | 是 | 4–5 天 |
| **M8** | F-M4 + F-M12（转给直营发起方、结算基数、扣回、任务先后） | 是 | 4 天 |
| **M9** | F-M13 余下（PDF 分列、自动付）+ F-M14 总扫 + F-M15 | 是 | 3–4 天 |
| — | F-M2 的 `LIGHT` 来源：随 F-S4（发起方在发起方页面答题点亮） | 随 F-S4 | — |
| — | F-M10：随 F-C10（Q109a，S3）；建议由 S3 在 F-C10 中按 F-M10 的规则做，或 F-C10 合入后我补 | — | 0.5 天 |

迁移号一律在开 PR 时取当时最大号 + 1。

### M1 账户与付款分摊（F-M1，F-M13 的付款）
- 钱包种类 `FEE_CREDIT`（`QbWallets`、种类字典）；科目 2190"平台费 Kudos"（负债，维度 `party`）。
- 实体：`QbFeeCreditGrant`（按笔：来源、来源键、金额、剩余、到期、发放时的运营商；`(sponsor, 来源键)` 唯一）；`QbCashLot`（现金按进账一笔一行：来源 `CARD` / `BANK` / `OPERATOR` / `OTHER`、金额、剩余、进账时刻），"按进账先后"与"按来源可查"都靠它，2160 不加来源维度（见 §2.b）；`QbFeePayment`（只追加、`writeOnce`：费用或预付、子账户、赠送或现金的那一笔、金额）。
- 一个纯 Java 的分摊（`fee/FeeAllocation`：给账户的各笔与金额，返回各笔用多少；最早到期先用、同日按发放；之后现金按进账）；一个付款子流程（`QB_FEE_SETTLE`，`subProcessOnly`）在钱包锁内：读各笔、分摊、写 `QbFeePayment`、改剩余、一次 `LEDGER_POST`（借 2190 / 2160 / 2180、贷 1130，`sourceEntity` = 费用或账单）。
- 现有进 2160 的路径（卡充值、银行转账、报名费结算、学分换回、手工加现金）一律写 `QbCashLot`；迁移把每个发起方现有的 2160 余额写成一笔 `OTHER`（进账时刻 = 迁移时）。
- `QB_FEE_CHARGE`、`QB_FEE_INVOICE_PAY`、`QB_FEE_PAY_EARLY` 改走付款子流程；账单多 `PARTIAL`。
- 钱包页（发起方页面）"可支付平台费的账户"块：合计、各子账户、每笔赠送到期、付款分摊；运营商与总部的发起方页只读同样分解；总部财务页按子账户汇总。
- 月报 `FEE_PAID` 按来源分列；核对 `FEE_CREDIT`（2190 = 各笔剩余）、`CASH_LOTS`（2160 = 现金各笔剩余）、`FEE_PAYMENTS`（分摊 = 付款分录）。
- 测试：`FeeAllocationTest`（顺序、到期不用、部分付）、`FeeAccountIT`（2 笔赠送 + 现金付清；`PARTIAL`；余额不为负；学分钱包不变）、语句预算（`QB_FEE_CHARGE` 有变化就说明）、`AdminFinanceIT`、`ReconciliationIT`。

#### M1 实施记录（`1.3/quizbuks-FM1-account`）

- **做法**：`fee.FeeAllocation`（纯 Java）定使用顺序；`QB_FEE_SETTLE`（`subProcessOnly`）在调用方的钱包锁内一条 `QueryEntities.all` 读未到期的赠送、现金笔与两个钱包，分摊、一次 `LEDGER_POST`、写分摊与各笔剩余；`QB_FEE_REFUND`（`subProcessOnly`）在作废的冲正之后按原分摊退回。`QB_FEE_CHARGE` 的预付即扣、`QB_FEE_INVOICE_PAY`、`QB_FEE_SETTLE_ITEM`（提前付清）都调 `QB_FEE_SETTLE`；子流程自己读锁内的行，调用方之后不再写这两个钱包（避免按旧版本更新）。
- **行为的改变**（§2.d 之内）：预付的费用与账单先用赠送额度、再用现金，账户不够时付一部分（`PARTIAL`），只在一分也付不了时 `QB_FEE_CASH_SHORT`；提前付清仍要求付得起所选的全部（是选择，不是欠款）；`InvoiceOutput` 多 `paidAmount`、`paidNow`、`creditPaid`、`cashPaid`；催款邮件的金额是还欠的部分；文案"现金账户"在付款按钮上改为"平台费账户"。删除的功能：无。
- **现金笔**：卡充值 `CARD`、银行转账 `BANK`（来源记录为充值）、报名费入账 `OTHER`（来源 `QbEntryFeeSettlement` + 日期）、学分换回 `OTHER`（来源为兑换记录）；买学分按进账先后取，扣回（退款、争议）先取该充值自己的一笔。取用时各笔不够也不拒绝（不因内部记账拒绝学分购买或退款），差异由 `CASH_LOTS` 报出。
- **迁移**：表、钱包种类、账单 `PARTIAL` 与 `paid_amount`（已付记全额），每个发起方 2160 余额一笔 `OTHER`（`source_entity = 'MIGRATION'`，迁移的 `now()` 只定它在顺序中的位置，注释写明）。定为 `V102`（V99 是 #571，V100、V101 是 S1 的 #573；#578 先绿，总控定）；`FeeAccountMigrationIT` 按版本号前一个迁移取起点，改号时同时改它的 `MIGRATION` 常量。
- **核对与月报**：`qb.system.recon-fee-account`（`FEE_CREDIT`、`CASH_LOTS`、`FEE_PAYMENTS`；F-M1 之前没有分摊的付款及其冲正不核）；`FEES_RECEIVABLE` 按还欠的部分；月报 `FEE_PAID` 认 1130 与 2160 / 2190 的组合，新列 `feeCredit`；余额表、过账检查、分录导出含 2190。
- **语句数**：`QB_FEE_CHARGE` 的预付多一次读（子流程的 `QueryEntities.all`）与分摊的写入；本应用没有平台费的语句预算测试（`AttemptStatementBudgetIT` 只管答题，答题路径不碰 2160 / 2190，不变）。
- **页面**：发起方钱包页的"平台费账户"块与账单的"已付"列、部分付款；平台管理系统发起方页的只读分解（财务；运营商以 `qb.operator.fee-*` 模板只读自己的发起方）；财务总览按子账户汇总。
- **平台缺口**：无。

### M2 赠送（F-M2，不含 `LIGHT`）
- `QB_FEE_CREDIT_GRANT`（`subProcessOnly`，借 5140 / 贷 2190）；`QB_FEE_CREDIT_ONBOARDING`（运营商负责人，权限 `qb.fee-credit.onboarding.confirm`）；`QB_FEE_CREDIT_MANUAL_REQUEST` + 审批（`ApprovalSubject` `qb.fee-credit`，另一位批准，受 `qb.fee-credit.manual.max` 约束，未设置即不限）；到期 `qb.fee-credit-expire`（`JobDefinition.forEach`，每日，借 2190 / 贷 5140）；到期前提醒（`MailTemplate` `NOTIFICATION`，可退订，按笔与提醒日去重，每人 24 小时至多一封）。
- 参数（受控）：`qb.fee-credit.grant.enabled`（false）、`.grant.basic`（1000）、`.grant.certified`（10000）、`qb.fee-credit.valid-days`（90）、`qb.fee-credit.manual.max`（空）；普通 `qb.fee-credit.expiry-notice-days`（14）。
- 冻结在预付中的赠送部分：本 PR 只有账户中的笔；冻结部分的作废规则随 M4 实现。
- 月报 `FEE_CREDIT_GRANTED` / `FEE_CREDIT_VOIDED`；核对"推广费 = 发放 − 作废（按运营商）"。

#### M2 实施记录（`1.3/quizbuks-FM2-grants`，叠在 M1 上）

- **做法**：`fee.FeeCredits` 四个流程 + 纯 Java 的 `FeeCreditRules`（入驻金额、有效期、上限、可作废额、是否该提醒、24 小时）。`QB_FEE_CREDIT_GRANT`（`subProcessOnly`）在调用方的钱包锁内一条 `QueryEntities.all` 读同来源键的笔、发起方的运营商与 `FEE_CREDIT` 钱包；已有即返回原笔（`granted = false`），否则写笔（记 `operatorId`）、改钱包、`SaveChanges.now` 后 `LEDGER_POST`（借 5140 / 贷 2190，两行都带 `party`，来源为该笔）。`QB_FEE_CREDIT_ONBOARDING`：`OperatorChecks.member` + `ownSponsor`（别的运营商的发起方 404），负责人与开关在取锁前查，取锁后查通过、封禁与已领。到期作废与提醒各一个 `forEach` 任务（`jabiz.fiscal-year-zone` 的 02:47 与 10:13），逐项在钱包锁内重读。
- **与计划不同**（请总控确认）：
  1. 追加赠送没有单独的 `QB_FEE_CREDIT_MANUAL_REQUEST` 与审批对象 `qb.fee-credit`，而是**平台费申请的第三种 `CREDIT`**（同一 `QB_FEE_REQUEST` / `QB_FEE_REQUEST_APPROVAL_RESULT`、审批对象 `qb.fee-request`、规则 `qb.fee-request.review`、同一申请页与审批人）。理由：四眼、自己的账号、另一位批准、拒绝、内容哈希的处理与手工记费完全相同，单立一套是重复；`CREDIT` 的审批内容多 `validDays`（只在有值时放入，已有申请的内容哈希不变）。上限在申请与批准时各查一次，批准时超限即拒绝。
  2. 提醒是**另一个每日任务** `qb.fee-credit-remind`（计划只写了提醒本身）：按发起方逐项，每笔记 `reminded_at`（按笔去重），距上一封不足 24 小时不发；"按提醒日去重"由每日一次与每笔一次合起来实现。
  3. `qb.fee-credit.manual.max` 与 `TREE_GATES` 一样列入 `ControlledParams`（`SetupConfig`），不在 `QbParams.CONTROLLED`（那里是 `QB_SETUP` 要提案的），以 `LoadParams.optional` 读。
  4. 每日核对的 `FEE_CREDIT_PROMOTION` 一行的 `sponsorId` 列放运营商（`-` 为无）：核对项的键只有这一列。
- **认证**：F-M3 之前所有发起方按未认证（`FeeCreditRules.onboardingAmount(false, …)`，代码注明）；钱包页"认证后补到 10,000"的提示与 `CERTIFIED_TOP_UP` 随 M3。
- **迁移**：`V104`（定号于开 PR 时；V102 是 M1，V103 留给 S3 的 F-C4）：`qb_fee_credit_grant` 加 `voided_amount`、`voided_at`、`reminded_at`（约束：作废时剩余为 0）与到期索引；`qb_fee_request` 加种类 `CREDIT`、`valid_days`、`grant_id`。科目 5140 与参数由 `QB_SETUP` 建。
- **页面**：发起方钱包页各笔多"已用"；运营商的发起方页"入驻赠送"块与确认按钮（`qb.fee-credit.onboarding.confirm`）；平台管理系统"平台费"页的"追加赠送"按钮（`FEE_MANAGE`）与"平台费 Kudos"标签（按运营商 / 发起方 / 来源汇总与逐笔，财务只读）。
- **平台缺口**：无。

### M3 企业实名认证（F-M3）
- `QbSponsorVerification`（名称、域名、状态、审核、撤销；生效中的域名唯一）；`QB_SPONSOR_VERIFICATION_SUBMIT`（账户邮箱已是该域名即直接进审核，否则寄确认链接）；`…_CONFIRM`（`MailTokens.consume`）；审批 `qb.sponsor-verification`（绑定名称与域名，运营商另一位成员）；`…_REVOKE`（理由）；通过即 `CERTIFIED_TOP_UP`（补差额）。
- 公共邮箱域名清单在代码中（`PublicMailDomains`）；`qb.sponsor-verification.link-hours`（48，普通）。
- 公开主页"已实名认证"标记（公开视图白名单加一个布尔字段，`public-queries.json` 快照）。
- **待核对的平台能力**：确认链接要寄到"该域名的一个地址"，它可能不是任何账户的邮箱（见 §2.a 第 2 条）。

### M4 提交时的资金检查、预付、归属与受众规则费（F-M5、F-M11、F-M7、F-M13 贷项）
- 发布（版本）上冻结：运营商 `billingOperatorId`、受众规则费的明细、（以后的）配送单价与曝光单价；`QbFeeCharge.operatorId` 一律由调用方给（F-M11）。
- 一次读取（与 `FeeGate` 合成一条 `QueryEntities.all`）、一个 422 `QB_PUBLICATION_FUNDS`（各项金额、可用额）；预付的：可用额（赠送 + 现金）≥ 合计；后付的：未付 + 未开账单 + 已提交未结算的预付等额 + 本次 ≤ 信用额。
- 科目 2180"预收平台费"（负债，维度 `party` + `publication` + 新维度 `purpose`，见 §2.b）；`QbPrepayment`（发布或 AI、用途、金额、剩余，及其分摊）；释放子流程 `QB_PREPAYMENT_RELEASE`（驳回、撤回、审核过期、结束后最后一次结算后；按原分摊退回，赠送退回原笔，已到期即作废）。
- F-M7：费用种类 `AUDIENCE`；有结束时间一次扣全期；没有结束时间扣到当月月底、每月最后一天续扣下月（`qb.audience-renewal`，`JobDefinition.forEach`，cron `0 7 0 L * *`，`jabiz.fiscal-year-zone`）；不足即未付并通知、`qb.audience-unpaid-end`（每日）在宽限后以平台的结束流程结束（原因 `AUDIENCE_FEE_UNPAID`）；退回：预付的作废原费用、后付的已出账单开**贷项凭证**（`QbFeeCreditNote` + 版式 `qb.fee_credit_note` + 编号 `qb.fee-credit-note`），抵下一张账单。
- 价目改为按发布冻结的运营商；**去掉 `direct` 价目表与 `qb.audience.price.fallback`**（规格："Q92a 后没有 direct 与 fallback；没有价目表 = 0"），直营发起方按平台运营商的价目（见 §2.d）。
- 参数：`qb.audience.renewal.notice-days`（7，普通）、`qb.audience.renewal.grace-days`（7，受控）；沿用 `qb.audience.billing.enabled`、`qb.audience.fee.refund-unused-days`。
- 语句预算：提交多一条读取，`StatementBudgetIT` 的提交预算随之改并说明；`AttemptStatementBudgetIT` 不变。

### M5 配送费（F-M6）
- 运营商价目表 `QbDistributionPrice`（比例、下限、上限、最低预付；负责人改，理由必填，只影响此后提交的）；总部范围（受控 `qb.distribution.rate.min|max`、`unit-floor.min|max`、`unit-ceiling.max`、`minimum-deposit.max`）；开关 `qb.distribution.billing.enabled`（false，受控）。
- 提交时冻结单价（四位小数）与预付（`maxWinners` × 单价含税；没有 `maxWinners` 用价目表的最低预付）。
- 结算 `qb.distribution-fee`（`JobDefinition.forEach`，每月 2 日，日本时间）：读开始计数器累计值、按差额记费（种类 `DISTRIBUTION`，写 `period`），先扣该发布的预付、再按账户顺序、再不够成未付或应收；发布结束后的那次结清并释放。`QbDistributionSettlement`（发布、月份、累计值、人数、金额；`(发布, 月份)` 唯一，可补跑）。
- 学者读到的内容不变（不在 feed 或发布模板里加任何东西）。
- 月报 `DISTRIBUTION_DEPOSIT` / `DISTRIBUTION_RELEASED`、`FEE_CHARGED` 按种类分列；核对 `DISTRIBUTION_DEPOSITS`。

### M6 曝光费（F-M8）
- 运营商价目（单价、下限、上限）与总部范围、`qb.exposure.min-purchase`、`qb.exposure.billing.enabled`（受控）；向导的购买与预付；追加购买（发布修改的审核规则，追加部分另行预付）；都以 `CallProcess.of(QB_EXPOSURE_PURCHASED_SET)` 写已购（S3 的接口，不改它）。
- 与配送费同一结算任务，种类 `EXPOSURE`，次数 = min（成功, 已购）；用完结束由 F-S13 的任务做，本项只在结束后的结算中释放；剩余低于 `qb.exposure.low-ratio`（0.1，普通）提醒。

### M7 AI 额度与 AI 费（F-M9）
- 购买：`QB_AI_CREDIT_BUY`（按账户顺序转入 2180 用途 `AI`）；退回 `QB_AI_CREDIT_RETURN`；最少购买 `qb.ai.credit.min-purchase`（空即不限）。
- 请求：额度可用部分 ≥ 本作业最高费用（现有估算 × 尝试数 × 倍数 × 汇率），否则 422 `QB_AI_CREDIT_SHORT`；预留与释放在 `qb.ai:<发起方>` 锁内只改 `QbAiCreditUse`，不取钱包锁。
- 费用 = ⌈`costUsd` × `qb.ai.fee.markup`（1.5）× `qb.ai.fee.usd-jpy`（150）⌉；`qb.ai-fee-monthly`（月初，在后付账单之前）按（发起方, 作业时的运营商）汇总记一次 `AI` 费，从 AI 额度的预付扣；超过预留的部分只扣到 0，其余由平台承担并告警。
- 开关 `qb.ai.fee.enabled`（false，受控）：关时照旧免费。
- 注意：S1 的 F-S6 改过 `content/ai/AiProcesses`；本项改请求与完成两处，开工前与 S1 对行（见 §3）。

### M8 运营商：转给直营发起方与结算（F-M4、F-M12）
- `QB_OPERATOR_TRANSFER_TO_DIRECT`（负责人；锁序 `qb.operator:<运营商>` → `qb.wallet:SPONSOR:<直营发起方>`；借 2140 / 贷 2160，现金一笔来源 `OPERATOR`；不能再转出、提现）。
- 结算基数改为"以现金、预付的现金部分、应收支付的平台费"，**不再含 4110 抽成**（规格："基数中不再有 4110 抽成"）；扣回当月用掉的赠送（按费用的归属）、贷 5140，扣不完结转；结算单两行"赠送支付（不分成）""推广费扣回"与结转。
- 任务先后：见 §2.a 第 1 条（平台能力）。
- 月报 `OPERATOR_TRANSFER_TO_DIRECT`、`FEE_CREDIT_RECOVERED`，`OPERATOR_SHARE` 的科目组合随之改；核对"扣回累计 + 结转 = 被用的赠送"。

### M9 账单收尾、月报总扫与注销（F-M13 余下、F-M14、F-M15）
- 账单与 PDF 写明"其中以平台费 Kudos 支付 X、预付款 Y、现金 Z、未付 W"；`qb.fee.auto-pay.enabled`（true，受控）：充值或赠送到账（事件）后自动付最早的未付账单。
- 月报：所有新类不落 `OTHER`（`AdminFinanceIT` 逐行）；核对全部新项与"发布状态与费用的一致"；差异给财务待办。
- F-M15：封禁时拦提交、买 AI 额度、领赠送；注销前结清、作废全部赠送（含冻结部分）、现金照现有；新表登记 `PersonalDataEraser`（各 PR 加表时即登记，本 PR 让 `ErasureIT` 覆盖全部）；`erasure.notice.*` 提示余额与赠送将作废。

## 2. 单独标出

### a. 需要的平台能力（转 S1）

**答复（总控 2026-11-09）**：第 1 条不加平台能力，用替代做法"以数据为条件"：后一任务只处理前一任务已为该月写下记录的发起方，没轮到的下一次运行补上，每日核对报告超时未处理的。第 2 条由 S1 核实，M3 之前答复。

1. **任务按另一任务的完成先后运行**（F-M12、F-M9、F-M13）：规格要求"配送与曝光结算、AI 月度记费全部完成后才开后付账单，账单完成后才跑运营商结算（以前一任务的完成为条件，不靠时间差）"。平台的任务只有 cron（11 §4），没有"在任务 X 的同一计划时刻全部完成之后才开始"。需要：`JobDefinition…after(任务, 同一周期)` 一类的声明，或"计划时刻的所有项都已完成"的可查询状态。**替代做法**（若平台不做）：应用以数据为条件——后一任务的取项模板只选前一任务已为该月写下记录（结算行、记费）的发起方，否则跳过、下一次运行再取；请 S1 判断哪种。
2. **寄到"不是账户邮箱"的地址的一次性确认链接**（F-M3）：确认链接寄到发起方填写的、公司域名下的一个地址，它不一定是任何账户的邮箱。需要确认 `MailTemplate.token(…)` / `MailTokens.consume` 是否允许收件地址不是本人的已登记邮箱、令牌绑定到"发起方 + 地址"；不允许即是缺口。
3. （确认即可）cron 的"每月最后一天"（`L`）与 `CRON_TZ`：平台用 Spring 的 `CronExpression`，`L` 可用；时区按 `JobDefinition` 的时区参数（`jabiz.fiscal-year-zone`）。不需要新能力。

### b. 新科目、维度与分录导出代码
- **2180 预收平台费**（负债；维度 `party`、`publication`、`purpose`）。
- **2190 平台费 Kudos**（负债；维度 `party`）。
- **5140 推广费（平台费 Kudos）**（费用）：赠送 借 5140 / 贷 2190、作废 借 2190 / 贷 5140、扣回 贷 5140。与现有 5120（学分的推广费）分开。
- **新维度位置 3 `purpose`**（字典：`DISTRIBUTION`、`EXPOSURE`、`AUDIENCE`、`AI`）：平台的分录只有 4 个维度列（11 §1.5），现用 1 `party`、2 `publication`；本项用第 3 个，第 4 个留空。
- **2160 不加"来源"维度**：规格允许"2160 加来源维度或分行钱包"。取应用表 `QbCashLot`，因为"按进账先后"本来就要按笔记，维度做不到先后，也省下第 4 个维度。
- 映射参数：`qb.journal.account.2180|2190|5140`（受控，`JournalMappingCheck`，不设即分录导出被拒）。
- 费用种类字典多 `DISTRIBUTION`、`AUDIENCE`、`EXPOSURE`、`AI`（`MONTHLY`、`MANUAL` 照旧）；账单状态多 `PARTIAL`。

### c. 请需求方确认

**答复（总控 2026-11-09）**：1 按需求方的决定 2（直营也付配送费），改验收那一句；2 按正文做；3 照迁移方案；4 照建议的时机；5 留在上线准备清单。

1. **规格自相矛盾**：F-M6 的"规则"写"直营发起方照付"（需求方 2026-11-07 晚第 2 条），"验收"却写"直营发起方不收"。按需求方的决定实现"照付"，并把验收一句改掉。请确认。
2. **F-M4 的范围**：总表写"运营商与账：不单独设账，0.5 天"，正文是"转给直营发起方"的新流程（1.5–2 天，需求方 2026-11-07 晚）。按正文做。请确认。
3. **现有现金余额的"进账先后"**：上线时每个发起方已有的 2160 余额没有逐笔的进账记录，按一笔"其他"（进账时刻 = 上线迁移时）处理，即排在此后的进账之前。请确认。
4. **点亮赠送（`LIGHT`）与 F-M10 的时间**：`LIGHT` 依赖 F-S4（发起方在发起方页面答题点亮），F-M10 依赖 F-C10（Q109a，S3）；两者都还没开始。建议 `LIGHT` 随 F-S4、F-M10 随 F-C10，F-M2 先上线另外三种来源。请确认（或由总控安排）。
5. （已列入上线准备清单，不阻塞开发）"未决"1：用赠送额度付的部分的消费税，照现有做法记，上线前请财务确认。

### d. 现有行为的改变与删除的功能

**答复（总控 2026-11-09）**：删除一条（受众规则费的 `direct` 价目表与 `qb.audience.price.fallback`）由总控问需求方，M4 之前答复；其余改变照做，开关缺省关闭。

- **删除**：受众规则费的 `direct` 价目表与受控参数 `qb.audience.price.fallback`（规格 F-M7）；直营发起方按平台运营商的价目表，没有价目表即免费。管理系统"受众规则价目"页去掉"直营"一栏，迁移不搬数据，`direct` 的价目表保留只读到下一次清理。
- **改变**：运营商结算的基数不再含 4110 抽成，且扣掉赠送支付的部分（规格 F-M12）。
- **改变**：账单与提前付清先用赠送额度、再用现金（原来只用现金），可以部分付（`PARTIAL`）。
- **改变**：`QbFeeCharge.operatorId` 由调用方给出（原来记费时现查），月中改派运营商后，旧发布的费用仍归原运营商。
- **新的拒绝**：提交发布时资金不足 422 `QB_PUBLICATION_FUNDS`（各费用开关打开后才出现）；AI 额度不足 422 `QB_AI_CREDIT_SHORT`（`qb.ai.fee.enabled` 打开后）。
- 所有费用缺省关闭：开关不开，行为与现在相同。

## 3. 与 S1、S3 共用的文件

| 文件 | 本计划 | 别的会话 | 先后 |
|---|---|---|---|
| `publishing/PublicationProcesses`（提交、撤回、驳回、结束） | M4 加资金检查、冻结、释放 | S1 的 F-S6/F-S7 改抽题与题库（不在提交步骤），S3 的 F-S13/F-S12 改过结束原因 | M4 开工前把要改的行发给 S1、S3 |
| 发起方 `publishing/PublicationWizard.tsx`、`settings.ts` | M4–M6 检查一步与预付、购买曝光 | S3 的 F-F3 要整体搬到新框架 | 与 S3 约定：F-F3 先搬，或我改后 S3 一起搬 |
| 发起方钱包页（账户、赠送、AI 额度块） | M1、M2、M7 | F-F3 | 同上 |
| `content/ai/AiProcesses` | M7 请求与完成 | S1 的 F-S6 改过 | M7 开工前对行 |
| `exposure/*`、`QB_EXPOSURE_PURCHASED_SET` | M6 只调用 | S3 的 F-S13 | 不改 S3 的文件 |
| `fee/*`、`wallet/*`、`operator/OperatorSettlements`、`queries/qb/admin/monthly.sql`、`recon-*.sql` | 全部 | 无 | — |
| `setup/QbParams`、`setup.yml`、setup 场景快照、`messages_*.properties`、管理系统扩展的 `messages.ts` / 菜单 | 每个 PR | 每个会话 | 后合的一方合并 |
| F-C10（Q109a）的 `QbHelpUsage` | F-M10 | S3 | 见 §2.c 第 4 条 |

## 4. 风险
- **语句预算**：提交多一条读取（M4）、付款子流程一次 `LEDGER_POST` 加分摊（M1）。预算变化在 PR 中逐项说明。
- **锁序**：发布锁 → 钱包锁（提交时冻结预付）；运营商锁 → 钱包锁（M8）；AI 锁不取钱包锁（M7）。写进 `backend/quizbuks/CLAUDE.md` 的锁序一条。
- **现金按笔**：`QbCashLot` 行数随进账增长；分摊只读有剩余的笔（部分索引 `remaining > 0`）。
- **迁移已有数据**：现有 2160 余额一次写成一笔（§2.c 第 3 条）；现有账单状态不变；现有已付账单不补分摊记录（分摊从上线起），核对 `FEE_PAYMENTS` 只核上线之后的。
- **退回与到期交织**：预付退回时原笔已到期即作废；有专门的测试（跨到期日的释放）。
- **上线顺序**：各开关缺省关；打开顺序写进 `operations.md`（先价目表、再开关），与 F-F10（资金开关，S3）对齐。
