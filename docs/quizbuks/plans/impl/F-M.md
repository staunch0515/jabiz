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
- **迁移**：表、钱包种类、账单 `PARTIAL` 与 `paid_amount`（已付记全额），每个发起方 2160 余额一笔 `OTHER`（`source_entity = 'MIGRATION'`，迁移的 `now()` 只定它在顺序中的位置，注释写明）。定为 `V103`（V99 是 #571，V100、V101 是 S1 的 #573，V102 是 S3 的 #581；总控定）；`FeeAccountMigrationIT` 按版本号前一个迁移取起点，改号时同时改它的 `MIGRATION` 常量。
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
- **与计划不同**（总控 2026-11-09 同意）：
  1. 追加赠送没有单独的 `QB_FEE_CREDIT_MANUAL_REQUEST` 与审批对象 `qb.fee-credit`，而是**平台费申请的第三种 `CREDIT`**（同一 `QB_FEE_REQUEST` / `QB_FEE_REQUEST_APPROVAL_RESULT`、审批对象 `qb.fee-request`、规则 `qb.fee-request.review`、同一申请页与审批人）。理由：四眼、自己的账号、另一位批准、拒绝、内容哈希的处理与手工记费完全相同，单立一套是重复；`CREDIT` 的审批内容多 `validDays`（只在有值时放入，已有申请的内容哈希不变）。上限在申请与批准时各查一次，批准时超限即拒绝。
  2. 提醒是**另一个每日任务** `qb.fee-credit-remind`（计划只写了提醒本身）：按发起方逐项，每笔记 `reminded_at`（按笔去重），距上一封不足 24 小时不发；"按提醒日去重"由每日一次与每笔一次合起来实现。
  3. `qb.fee-credit.manual.max` 与 `TREE_GATES` 一样列入 `ControlledParams`（`SetupConfig`），不在 `QbParams.CONTROLLED`（那里是 `QB_SETUP` 要提案的），以 `LoadParams.optional` 读。
  4. 每日核对的 `FEE_CREDIT_PROMOTION` 一行的 `sponsorId` 列放运营商（`-` 为无）：核对项的键只有这一列。
- **认证**：F-M3 之前所有发起方按未认证（`FeeCreditRules.onboardingAmount(false, …)`，代码注明）；钱包页"认证后补到 10,000"的提示与 `CERTIFIED_TOP_UP` 随 M3。
- **迁移**：`V106`（V102 是 S3 的 #581，V103 是 M1 #578，V104、V105 是 S1 的 #582、#584；总控定）：`qb_fee_credit_grant` 加 `voided_amount`、`voided_at`、`reminded_at`（约束：作废时剩余为 0）与到期索引；`qb_fee_request` 加种类 `CREDIT`、`valid_days`、`grant_id`。科目 5140 与参数由 `QB_SETUP` 建。
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

**答复（总控 2026-11-09）**：删除一条（受众规则费的 `direct` 价目表与 `qb.audience.price.fallback`）由总控问需求方，M4 之前答复；其余改变照做，开关缺省关闭。**需求方 2026-11-09 确认删除**，随 F-M5（§8.8 第 2 条）。

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

## 5. 进度与下一步（S2，2026-11-09，按总控降速暂停）

| 项 | 状态 |
|---|---|
| M1（F-M1、F-M13 的付款） | #578 已合（V103） |
| M2（F-M2，不含 `LIGHT`） | 草稿 #585（V106，head 0a012282）：已合最新（#584），本机全部场景、受影响测试与 `platformCheck` 通过；e2e（S4）整轮 120/122，失败的 `topup.spec.ts` 已改，重跑 3/3 通过。等 CI 绿后报总控转 ready |
| M3–M9 | 未开始（总控：额度重置前不开新项） |

下一步（额度重置之后）：

1. #585：CI 若红，按失败原因修；合入后删工作树 `/workspace/fm2`。
2. M3 企业实名认证（F-M3）：按本文件 §M3 与 S1 的邮件令牌答复（`MailRecipient.user(userId, address, null)`、用途 `company-email`、`MailTokens.consume` 返回 `Consumed(messageId, userId, address)`；流程核对 userId 与规范化地址，地址先校验，400）。随 M3 补 M2 的 `CERTIFIED_TOP_UP` 与钱包页"认证后补到 10,000"的提示，入驻赠送改为按认证读 `grant.certified`。
3. M4 起按 §1 的顺序；M4 含需求方确认的删除（`direct` 价目表与 `qb.audience.price.fallback`）。
4. 平台的既有问题（转 S1，非本线）：`platformCheck` 结束时 `JobScheduler.scheduleNext` 记两条 `TaskRejectedException: ExecutorService in shutdown state`（关闭时的竞争，只是 ERROR 日志）。

## 6. M3 企业实名认证（F-M3）的细化（S2，2026-11-10，待总控批准）

规格 F-M3（规则、参数、运营闭合、验收）与 Q105 §3.5、D-Q105-6、D-Q105-11；平台的邮件令牌按 S1 的答复（§5 第 2 条）。分支 `1.3/quizbuks-FM3-verification`，迁移取开 PR 时的下一个号（现在最大 V110）。

### 6.1 数据
- **`QbSponsorVerification`**（普通表，经 `DatasetEntityManager` 写入、平台审计；默认数据视图 `processOnlyWrites`，读权限给总部与运营商、登记 `OperatorScopes`（按 `sponsorId`）；`me:` 视图只读本人）：一次提交一行，保留历史。字段：`sponsorId`、`companyName`（≤ 200）、`domain`（规范化后，≤ 253）、`confirmAddress`（发确认链接的地址，`auditMasked`，删除账号时清空）、`status`（`LINK_SENT` → `REVIEW` → `APPROVED` / `REJECTED`；`APPROVED` → `REVOKED` / `SUPERSEDED`；`LINK_SENT` / `REVIEW` → `WITHDRAWN`，即同一发起方再次提交）、`linkMessageId`（只认最新一封的链接）、`linkSentAt`、`approvalRequestId`、`decidedAt`、`rejectReason`、`revokedAt` / `revokedBy` / `revokeReason`、`verifiedAt`。
- **唯一**：部分唯一索引 `(domain) WHERE status = 'APPROVED'`（同一域名只认证一个发起方）与 `(sponsor_id) WHERE status IN ('LINK_SENT','REVIEW','APPROVED')`（每个发起方至多一个进行中或生效的）。
- **不改 `QbSponsor`**（入驻行是时态表、被审批绑定）：Q105 说"记在入驻行上"，改为"此刻已认证 = 本人有一行 `APPROVED`"，只此一个定义（`verification.VerificationRules.verified`），公开主页、入驻赠送与钱包页的提示都读它。
- 公共邮箱域名清单在代码 `verification.PublicMailDomains`（gmail.com、qq.com、163.com、outlook.com、yahoo、icloud 等，含子域；补充即改代码，测试列出）。

### 6.2 流程（都 `requiresVerifiedEmail()`、先 `CallerChecks::refuseBanned`；锁 `qb.sponsor-verification:<发起方>`，批准时另取 `qb.sponsor-verification-domain:<域名>`，锁序：发起方锁 → 域名锁 → 审批 → `qb.wallet:SPONSOR:<发起方>`）
- `QB_SPONSOR_VERIFICATION_SUBMIT {companyName, domain, confirmAddress?}`（`qb.sponsor.verification`，给 `QB_SPONSOR`）：入驻已通过；域名规范化（小写、去掉 `www.`、只收 ASCII 标签或 punycode，不经 URL 解析器）与格式检查；公共域名拒绝（422 `QB_VERIFICATION_PUBLIC_DOMAIN`）；该域名已被别的发起方认证即拒绝（422 `QB_VERIFICATION_DOMAIN_TAKEN`，只说"已被认证"，不说是谁）。账户的已验证邮箱的域名是该域名或其子域 → 直接 `REVIEW` 并开审批；否则 `confirmAddress` 必须在该域名（或子域）下 → `LINK_SENT` 并以 `SendMail` 寄确认链接（模板 `qb.sponsor-verification.confirm`，事务邮件；`MailRecipient.user(发起方, 地址, null)`，令牌用途 `company-email`，有效期 `qb.sponsor-verification.link-hours` 小时）。同一发起方再次提交：进行中的那行 `WITHDRAWN`（撤回其审批），生效的那行 `SUPERSEDED`——**名称或域名改变即失去认证**，直到新的通过；内容不变的再次提交只用于"重发链接"（只换 `linkMessageId`，旧链接失效）。
- `QB_SPONSOR_VERIFICATION_CONFIRM {token}`（同权限，本人登录后打开链接）：`MailTokens.consume("company-email")`，核对令牌的用户是本人、地址等于该行的 `confirmAddress`、`messageId` 等于该行最新的一封、行仍是 `LINK_SENT`；过期或用过即 422 `QB_VERIFICATION_LINK_INVALID`。通过 → `REVIEW` 并开审批。
- **审批** `qb.sponsor-verification`（`ApprovalSubject`，内容哈希绑定 `companyName` + `domain`，事实 `operatorId`；`ApprovalCase.scope` 为发起方所属运营商；审批人持 `qb.review.sponsor`；规则 `qb.sponsor-verification.review` 由 `QB_SETUP` 以受控变更提出，没有规则即拒绝 `QB_REVIEW_NOT_CONFIGURED`）。准备人是发起方，平台挡住自审；直营发起方由本运营商的另一位成员审。改派运营商时随 `APPROVAL_SCOPE_MOVE` 一起移动（`QB_SPONSOR_OPERATOR_ASSIGN` 已移动发起方作为准备人的全部请求，不另写）。
- `QB_SPONSOR_VERIFICATION_APPROVAL_RESULT`（`internal()`，订阅 `jabiz.approval.approved` / `rejected`，只认本行的请求）：批准 → 在域名锁内再查唯一（被别家先认证即改为 `REJECTED`，理由代码 `QB_VERIFICATION_DOMAIN_TAKEN`）、`APPROVED`、`verifiedAt`；然后**认证补发**（6.3）；发事件 `qb.sponsor-verification.decided` → 站内信（种类 `VERIFICATION_DECIDED`，动作到"企业认证"）。驳回 → `REJECTED` 与审批人的理由，同一站内信。
- `QB_SPONSOR_VERIFICATION_REVOKE {sponsorId, reason}`（`qb.sponsor-verification.revoke`：总部内容管理员与运营商成员；运营商经 `OperatorChecks.memberWhenScoped` + `ownSponsor`，别人的 404；总是要求二次验证；理由必填，发起方可见）：`APPROVED` → `REVOKED`，**不收回已发的额度**；站内信。

### 6.3 认证补发（`CERTIFIED_TOP_UP`）与入驻金额
- 入驻赠送 `QB_FEE_CREDIT_ONBOARDING`：`certified` 改为读 `VerificationRules.verified`（M2 留下的 `boolean certified = false`）。
- 批准后（自己的事务、钱包锁内，见 6.8）：本人已领开户赠送且其金额 < 开户时记下的认证金额（不超过现在的 `qb.fee-credit.grant.certified`，见 6.8 第 1 条）→ 以 `QB_FEE_CREDIT_GRANT`（来源 `CERTIFIED_TOP_UP`、来源键 `certified`，终身一笔）发差额；没领开户的什么也不发（以后确认资料完整时直接发认证金额）。撤销后再次认证不再补（来源键已用）。条件见 6.6 第 1 条。
- 钱包页（平台费账户的赠送块）加 M2 留下的提示"企业实名认证后补到 {certified}"：只在已领开户、未认证、差额 > 0 时显示，链到"企业认证"。

### 6.4 读取与页面
- 模板：`qb.sponsor.verification`（本人最新一行、链接是否过期——按 `linkSentAt` + 参数与服务端时钟、`verified`、补发金额）；`qb.admin.sponsor-verifications` 与 `qb.operator.sponsor-verifications`（列表与筛选，运营商经成员数据视图限定本运营商的发起方；不给出 `confirmAddress` 以外的个人信息）；公开主页 `qb.public.sponsor-page` 加 `verified` 与 `verifiedDomain`（公开视图 `QbSponsorVerification` 只含 `APPROVED` 行的 `sponsorId`、`domain`、`verifiedAt`，`public-queries.json` 快照随之）；App 已登录的商户主页模板同样加两列。
- 发起方页面：发起方中心加标签"企业认证"（`profile/verification`，只加标签）：提交表单（域名缺省取主页"网站"的域名）、状态、重发链接、驳回理由与改正再提交、撤销理由；确认链接的落地页 `profile/verification/confirm?token=…`（未登录先登录再回来，`hrefOf`）。公开主页与 App 的商户主页显示"已实名认证 · 域名"标记（共用组件放 `qb/`）。
- 管理系统扩展：`/qb/sponsor-verifications`（列表、详情；决定只经平台的 `ApprovalPanel`，读好名称、域名、确认方式、账户邮箱的域名后才给出；撤销对话框要求理由）；发起方详情页加"企业认证"一栏。
- 文案三语；词表加"企业实名认证 / 企業認証 / company verification"。

### 6.5 运营闭合、测试与文档
- 链接过期即失效、可重发；驳回可改正再提交；撤销；审批待办由平台给审核人；**每日核对**（并入 `qb.operator-check` 的模板 `qb.admin.operator-health`）：`REVIEW` 超过 `qb.sponsor-verification.review-remind-days`（普通参数，7）未决的给运营商一个待办，`APPROVED` 的域名重复（索引兜底，报告即事故）。
- 删除账号：`SponsorErasure` 清空 `confirmAddress`、`companyName` 照留（企业名不是个人信息；如需清除随 `PersonalDataEraser` 的声明），认证行改 `REVOKED`。
- 测试：`VerificationRulesTest`（规范化、公共域名、子域、`verified`）；`SponsorVerificationIT`（公共域名拒绝；账户邮箱同域直接审核；确认链接一次性、过期、旧链接在重发后失效、别人打开无效；另一位成员审核、本人不能审；同域名第二家被拒（提交时与批准时两处）；名称或域名改变即失去认证；撤销后标记消失、额度不收回；入驻前后两种补发：先领 1,000 后认证补 9,000、先认证后确认直接 10,000、补发只一次；改派运营商后审批随之移动）；公开模板快照；`InboxIT` 新种类；场景快照（`QB_SETUP` 新参数、规则、权限）；前端两个 SPA 与扩展的测试与 axe；e2e `sponsor-verification.spec.ts`（经 Mailpit 打开确认链接、运营商成员批准、公开主页出现标记、钱包页补发）——本机先跑再提。
- 文档：`operations.md` §7.42（判断方法：官网与公司名一致、WHOIS 不作为依据；撤销；排查链接收不到）、帮助底稿三语、发起方手册三语初稿、`backend/quizbuks/CLAUDE.md` 一条、`troubleshooting.md` 视需要。
- 删除的功能：无。行为改变：入驻赠送的金额按认证读（原来一律未认证）。

### 6.6 总控的决定（2026-11-10，计划批准）
1. **补发要看赠送开关** `qb.fee-credit.grant.enabled`（与入驻赠送、节点赠送一致；总开关不能被某一来源绕过）。开关关闭时记为待补，开启后由每日任务补发（同 `LIGHT`）；封禁中不补。实现：补发只在一个逐项流程 `QB_FEE_CREDIT_CERTIFIED_TOP_UP`（`internal()`，钱包锁内重读：已认证、已领开户、少于认证金额、没有 `certified` 一笔、开关开、入驻通过、未封禁，否则什么也不做）；批准结果提交后由事件 `qb.sponsor-verification.decided` 的消费者 `qb.fee-credit-certified` 在自己的事务中运行它（6.8 第 2 条；原为同一事务中的子流程），每日逐项任务 `qb.fee-credit-certified-due`（模板 `qb.system.fee-credit-certified-due` 选出待补的）补上开关关着、封禁中错过的。6.3 的"不看开关、以后也不自动补"作废。
2. 改名称或域名当场失去认证（照 6.2）。
3. 账户邮箱的子域算同域（公共域名清单仍先拒）。
4. 总部内容管理员与运营商任一成员可撤销，二次验证、理由必填、不收回额度。
- `CERTIFIED_TOP_UP` 是钱：写完先报分支名、不开 PR，由总控安排额外审查。

### 6.7 实施记录（S2，2026-11-10）
- 分支 `1.3/quizbuks-FM3-verification`，迁移 V111。照 6.1–6.6 做，与计划不同的几处：
  - **确认链接不记 `linkMessageId`**：平台的 `SendMail` 不把消息编号交给流程；平台的令牌本来就"只认该用途、该用户的最新一封"（`MailTokens`），重新发送即令旧链接失效，效果相同。令牌用途为 `qb.companyEmail`（平台的用途名不允许连字符）。
  - **链接有效期**：邮件模板的令牌有效期是定义时的固定值，模板定为上限 168 小时，参数 `qb.sponsor-verification.link-hours` 在确认时按 `linkSentAt` 检查（页面显示的到期时刻按同一参数算）。
  - **不另设审核提醒**（6.5 原写"审核超 7 天给运营商待办"与参数 `qb.sponsor-verification.review-remind-days`）：规格没有要求，平台的审批本来就给审核人待办；参数因此没有加。
  - **补发**：（6.8 第 2 条起）批准结果提交后，消费者 `qb.fee-credit-certified`（事件 `qb.sponsor-verification.decided`）在自己的事务中运行 `QB_FEE_CREDIT_CERTIFIED_TOP_UP`；开户赠送在钱包锁内读认证（与补发同一把锁，不会两边都按未认证处理）。
  - 公开主页的标记由公开模板 `qb.public.sponsor-page` 的新列 `verifiedDomain` 给出（公开视图只含 `APPROVED` 行），学者 App 的发起方主页与发起方页面共用 `qb/VerifiedMark`。
- 测试：`SponsorVerificationIT`（8 例：公共域名与格式、未通过入驻、账户邮箱同域与子域、链接只认最新一封与本人、过期、一次性、本人不能审、同域名第二家（提交时与批准时）、改名当场失去、撤销与站内信、补发一次与先认证后开户、开关关闭时由每日任务补发）、`VerificationRulesTest`、`FeeCreditRulesTest` 的补发规则；场景快照（新参数、规则、权限）与 `SetupIT`；公开模板快照；前端两个 SPA、扩展（`ext:check`）与 e2e `sponsor-verification.spec.ts`。


### 6.8 额外审查的修改（2026-11-10，审查 @4a73ba7）
1. **调高认证金额不对存量补差**（中 1，按 D-Q105-6 的本意）：开户赠送的一笔记下发放时的认证金额（`qb_fee_credit_grant.certified_amount`，V111；按认证发的记下它自己的金额，永不补），补发只补到这个金额，且不超过现在的参数（调低之后只补到调低后的）；调高 `qb.fee-credit.grant.certified` 对已发的开户赠送一律不补。补发的一笔记下它补到的合计（开户 + 补发）。V111 之前发的开户赠送没有记录，按补发时的参数（上线前没有生产数据）。规则 `FeeCreditRules.certifiedTopUp`，每日的模板与发起方页面的"认证后补到"同一算法。
2. **补发失败不挡认证**（低 2）：补发不再是批准结果中的子流程；批准结果提交后，事件 `qb.sponsor-verification.decided` 的消费者 `qb.fee-credit-certified` 在自己的事务中运行 `QB_FEE_CREDIT_CERTIFIED_TOP_UP`（驳回、撤销的事件同样运行，什么也不做）。补发的配置或过账失败只留下事件的重试，认证照常 `APPROVED`，每日任务补上。
3. **到期模板**（低 3）：参数先以正则判断（不是整数日元即不选人，不报错），开关关着时不选人。
4. **核对项 `CERTIFIED_GRANTS`**（`qb.system.recon-fee-account`）：有 `CERTIFIED_TOP_UP` 一笔而该发起方从未有过 `APPROVED` 的认证（`verified_at` 只由批准写入，撤销与改名后仍在；删除账号的发起方不查）；开户 + 补发超过补发时记下的认证金额。

## 7. F-M4 运营商的账与直营发起方（S2，2026-11-10，待总控批准）

规格 F-M4；`F-M.md` §1 M8 的前一半（转给直营发起方）。M8 的后一半（结算基数改为平台费、扣回赠送，F-M12）要等 M4–M7 的费用有了才有数可算，另作一项。

### 7.1 数据（迁移：当时的下一个空号）
- 只追加、只记一次的 `QbOperatorTransfer`（`qb_operator_transfer_version`，`writeOnce`，照 `QbFeeCharge`）：`transferId`、`operatorId`、`sponsorId`（直营发起方，取自运营商行，不取自输入）、`amount`（JPY 0 位）、`note`（可选，负责人写给自己的说明）、`requestedBy`、`createdAt`。保留期照财务记录（`RetentionPolicy`，10 年）。
- 现金一笔：已有的来源 `OPERATOR`（F-M1 已预留），`sourceEntity = QbOperatorTransfer`。

### 7.2 流程 `QB_OPERATOR_TRANSFER_TO_DIRECT`
- 运营商负责人（与提现同一判断：入口范围给出运营商，先取运营商锁再查成员关系与负责人）；`requiresVerifiedEmail()`、`refuseBanned`、**每次二次验证**；支持幂等键。
- 锁序 `qb.operator:<运营商>` →（成员的共享锁）→ `qb.wallet:SPONSOR:<直营发起方>`。锁内读运营商的账户（`qb.system.operator-balance` 加上已转出的合计：余额 = 记入 − 提现（未驳回未取消）− 转出），金额 ≤ 余额，否则 422（如 `QB_OPERATOR_TRANSFER_EXCEEDS`）；运营商停用、没有直营发起方或其入驻未通过即 422。
- 写转入记录 → `SaveChanges.now` → `LEDGER_POST` 借 2140 / 贷 2160（维度：直营发起方）→ 现金钱包加、`CashLots.add(OPERATOR)`。转出之后提现可用额随之减少（同一模板）。
- 不可撤回：钱进的是负责人自己的直营发起方，没有"转错人"；更正就是不再转。

### 7.3 "只能付平台费，不能转出或提现"
- 平台费照 F-M1 的顺序用（赠送先、再按进账先后用现金，`OPERATOR` 笔与其他现金同等）。
- 2160 的其他出口：**买学分 `QB_KUDOS_BUY`** 会把转入的钱变成 Kudos（再付给学者），等于转出。计划：买学分的可用额不含 `OPERATOR` 笔的剩余（`KudosExchanges.problems` 与页面的可用额一起改，`CashLots.take` 跳过 `OPERATOR` 笔）。发起方没有现金提现；充值退款只扣该充值自己的那一笔，不受影响。

### 7.4 核对、月报与页面
- 每日核对新项 `OPERATOR_ACCOUNT`（`qb.system.recon-fee-account` 或运营商核对模板）：按运营商比较 2140（经来源单据：结算、提现、转入连到运营商）与"记入的结算 − 已付的提现 − 转入"；`CASH_LOTS` 照旧覆盖 2160 与各笔（含 `OPERATOR`）。
- 月报新类 `OPERATOR_TRANSFER_TO_DIRECT`（来源 `QbOperatorTransfer`，借 2140 / 贷 2160），`AdminFinanceIT` 逐行期望。
- 运营商管理系统的"账户"页：余额旁的"转给直营发起方"（金额、说明、确认、二次验证）与转入记录列表（模板 `qb.operator.transfers`，运营商范围）；总部的财务页可读全部转入。
- 发起方的平台费账户页：现金按来源已分列，`OPERATOR` 显示为"运营商转入"（三语字典已在，核对文字）；买学分处说明"运营商转入的部分不能换学分"。
- 文档：`operations.md` 新小节（规则、核对差异的处理），帮助底稿与使用手册（发起方、运营商）三语。

### 7.5 测试
- 单元：余额与可转额的规则、买学分的可用额（不含 `OPERATOR`）。
- IT：只能负责人、只到本运营商的直营发起方（别的运营商 404）；超额 422 且什么也没写；与提现并发时合计不超过余额；分录 2140/2160、现金笔、钱包；平台费先用赠送再用转入；买学分拒绝动用转入；核对 0 行、月报一行；二次验证。
- e2e：负责人转一笔，直营发起方在平台费账户看到"运营商转入"。

### 7.6 请总控确认
1. 范围只做 F-M4（转入）；F-M12（结算基数、扣回赠送）在 M4–M7 之后另作。
2. 买学分不能动用运营商转入的钱（§7.3）。
3. 直营发起方被封禁时：拒绝转入（建议），还是照转。
4. 不设四眼（负责人转给自己的直营发起方，钱不离开平台），只要二次验证；提现照旧四眼。
- 删除的功能：无。钱：写完先报分支名、不开 PR。

### 7.7 总控的决定（2026-11-10，计划批准）
1. 只做 F-M4；F-M12（结算基数、扣回赠送）待 M4–M7 之后另作。
2. 买学分不能动用 `OPERATOR` 来源的钱（加 IT）。
3. 直营发起方被封禁时拒绝转入（422，什么也不写）。
4. 不设四眼：每次二次验证 + 幂等键。写完先报分支名，额外审查后再开 PR。

### 7.8 实施记录（S2，2026-11-10）
- 迁移 `V113`：只追加、只记一次的 `qb_operator_transfer_version`（外键到 `qb_operator`）。
- `operator.OperatorTransfers`：`QB_OPERATOR_TRANSFER_TO_DIRECT`（`qb.operator.use`、负责人（`OperatorWithdrawals.lead`）、`requiresVerifiedEmail`、每次二次验证、`refuseBanned`）；锁内一条 `QueryEntities.all` 读直营发起方的现金钱包、入驻与封禁，再读余额模板；`OperatorRules.transferable`（整数、> 0、≤ 余额）。错误码 `QB_OPERATOR_TRANSFER_AMOUNT` / `_NO_DIRECT` / `_DIRECT_BANNED`。负责人本人就是直营发起方时，封禁先由 `refuseBanned` 拒绝（`QB_USER_BANNED`）；另一位负责人转给被封禁的直营发起方即 `_DIRECT_BANNED`。
- 余额：`qb.system.operator-balance` 与 `qb.operator.account` 都减去转出（另给出 `transferred`、`hasDirectSponsor`）；新模板 `qb.operator.transfers`。
- 买学分：`KudosExchanges.problems(..., operatorCash)` 的可用额减去 `OPERATOR` 笔的剩余，`CashLots.takeExcept` 不取它们；`qb.sponsor.wallet` 给出 `cashFromOperator`，页面的买入上限为 `buyableCash`。充值退款与争议的扣回见 §7.9。
- 核对 `OPERATOR_ACCOUNT`（`recon-fee-account`）；月报 `OPERATOR_TRANSFER_TO_DIRECT`（管理系统扩展同时补上 F-M2 漏掉的 `FEE_CREDIT_GRANTED` / `FEE_CREDIT_VOIDED` 的文字）。
- 页面：运营商「账户」页的"转给直营发起方"（金额、说明、确认、二次验证由平台询问）与转入记录；发起方平台费账户页的"运营商转入"一行。
- 测试：`OperatorTransferIT`（5 例：负责人、他人、超额与非整数、幂等键、分录与现金笔、模板、月报、核对；封禁；买学分与平台费；并发两笔；核对找出没有分录的转入）、`OperatorRulesTest`、`KudosExchangesTest`、扩展与发起方页面的 Vitest。e2e 没有新用例：运营商账户要有结算记入才有余额，e2e 中没有结算的途径（与提现相同），页面由扩展的测试覆盖。

### 7.9 总控额外审查后的修改（S2，2026-11-10，审查 @9180dac18）
- **【中 1】扣回不先取运营商的钱**：`TopUpClawback` 改用 `CashLots.takeLast`（`FeeAllocation.takeCash(..., first, last)`）：该充值自己的那一笔 → 非 `OPERATOR` 各笔（按进账先后）→ 最后才取 `OPERATOR`。取到 `OPERATOR` 时 `CreateTask`（`TopUpTasks.operatorCash`，权限 `qb.reconcile`，来源 `stripe-operator-cash:<事件>`，消息 `task.qb.refund-check.OPERATOR_CASH` 三种语言）。operations §7.43 改写。测试：`OperatorCashClawbackIT`（卡充值 A → 买学分用完 A → 运营商转入 C → 银行充值 B → A 部分退款：取 B、`OPERATOR` 不动、无待办、核对无差异；只剩 `OPERATOR` 时取它并生成待办）、`FeeAllocationTest.cashTakenBackLeavesTheNamedLastLotsToTheEnd`。
- **【低 2】补测试**（`OperatorTransferIT`）：转入 × 提现并发（一成一拒，合计 ≤ 余额）；`REQUESTED` 的提现减少可转额、转入减少可提现额；缺近期二次验证即 403 `MFA_REQUIRED`。
- **【低 3】幂等键必填**：平台不能在流程上声明"必须有幂等键"，流程也读不到请求头，所以输入加必填的 `requestKey`（`@NotBlank`，≤ 128，缺少即 400）并记在转入上（列 `request_key`，V113 中 `UNIQUE (operator_id, request_key)`；锁内先查，重复即 422 `QB_OPERATOR_TRANSFER_REPEATED`）。页面每次确认生成新键，同时作为输入与 `Idempotency-Key`（重发同一次提交由平台回放第一次的回答）。测试：IT 缺键 400、同键换幂等键 422、别的运营商可用同一键；扩展 Vitest 断言键随输入与请求头、下一次提交换新键。

## 8. F-M5 提交发布时的资金检查与预付款（S2，2026-11-10，待总控批准）

规格 F-M5（规则、运营闭合、验收）；§1 的 M4 把 F-M5、F-M11、F-M7、F-M13 贷项放在一起，总控 2026-11-10 定为先做 F-M5。分支 `1.3/quizbuks-FM5-funds-check`，迁移取开 PR 时的下一个号（现在最大 V113）。

### 8.1 现状与范围
- 提交（`PublicationLifecycle` 的 `QB_PUBLICATION_SUBMIT`）只有 `FeeGate`（有未付账单即拦）；`QB_DISTRIBUTION_FUNDS`、`QB_AUDIENCE_FEE_FUNDS` 不存在。配送费（F-M6）、受众规则费的扣款（F-M7）、曝光费（F-M8）都还没有，**提交时可算的金额现在都是 0**。
- 本项做：检查、冻结（2180）、释放、对账与页面这套"提交时的钱"，以及 F-M11 的冻结运营商；各费用作为"提交时的项"（`SubmitCost`：用途、金额、明细）接入，F-M6/F-M7/F-M8 各加一项。第一项见 8.7 问题 1。

### 8.2 数据（一个迁移）
- 科目 **2180 预收平台费**（负债；维度 1 `party`、2 `publication`、3 `purpose`），维度字典 `purpose`：`DISTRIBUTION`、`EXPOSURE`、`AUDIENCE`、`AI`；分录导出映射 `qb.journal.account.2180`（受控）。
- `QbPrepayment`（普通表，按版本更新）：发起方、发布（AI 时为空）、用途、方式（`PREPAID` 冻结了钱 / `POSTPAID` 只占信用额、不动钱）、金额、剩余、冻结时的运营商、状态 `OPEN` / `RELEASED`、时刻。分摊照旧记 `QbFeePayment`（对象为预付款）。
- 发布（版本）加 `billingOperatorId`：提交时冻结（F-M11）；之后与发布相关的 `QbFeeCharge.operatorId` 一律由调用方给出。

### 8.3 提交
- 锁序：测验（共享）→ 权益购买 → `qb.wallet:SPONSOR:<发起方>`。锁内**一条** `QueryEntities.all`：发起方行（付费方式、信用额）、未付账单（`FeeGate` 原有）、未到期的赠送各笔、现金各笔、两个钱包、本发起方 `OPEN` 的预付款；`StatementBudgetIT` 的提交预算随之改（多一把锁；读取仍一条），PR 中说明。
- 各项相加为合计。预付的：可用额（赠送 + 现金）≥ 合计，否则 422 `QB_PUBLICATION_FUNDS`（各项金额、合计、可用额、差额），**什么也不写**；后付的：1130 余额（含未开账单的费用）+ `OPEN` 的 `POSTPAID` 预付款剩余 + 本次 ≤ 信用额，否则同一个码。`FeeGate` 的拦截照旧（沿用 `qb.fee.overdue.block-submit`）。
- 合计 > 0 时冻结：子流程 `QB_PREPAYMENT_FREEZE`（`subProcessOnly`）按 F-M1 的顺序（赠送最早到期先用，再现金按进账、`OPERATOR` 照 F-M4 可付平台费）分摊，一次 `LEDGER_POST`：借 2190 / 2160、贷 2180（维度：发起方、发布、用途），写 `QbFeePayment` 与各笔剩余；后付的只写一行 `POSTPAID` 预付款，不过账。合计为 0 不写预付款。

### 8.4 释放（一个子流程 `QB_PREPAYMENT_RELEASE`，`subProcessOnly`）
- 按原分摊退回：赠送退回原笔（原笔已到期即作废：借 2180 / 贷 5140，与到期作废同科目），现金退回原笔；借 2180、贷 2190 / 2160；`POSTPAID` 只关闭。
- 调用处：撤回（`QB_PUBLICATION_WITHDRAW`）、驳回（`QB_PUBLICATION_APPROVAL_RESULT` 的驳回）、审核过期（`QB_PUBLICATION_ADVANCE` 撤下过期的审核）——三处全额释放。
- 发布结束（到期、配额、预算、曝光用完、管理员下架、树退役、封禁……结束的写法有七八处）**不在各处改**：每日任务 `qb.prepayment-release`（`JobDefinition.forEach`，每项一个事务）取"已结束、还有 `OPEN` 预付款"的发布逐个释放剩余；F-M6 加上"该发布最后一次结算已写"的条件（以数据为条件，同 §2.a 的答复）。

### 8.5 读取与页面
- 向导检查一步：`QB_PUBLICATION_FUNDS_CHECK`（只读，与提交同一段 Java：各项、合计、可用额、差额），显示明细，不足时"去充值"链接到平台费账户页；提交被 422 拒绝时显示同一明细。
- 发起方的发布页：预付款余额（模板 `qb.sponsor.publication-prepayments`）；平台费账户页"冻结在预付款中"一行；运营商与总部的发起方页只读同样分解。帮助（三种语言）写明"预付款不是费用，结束后退回"。

### 8.6 对账、月报与测试
- 核对 `PREPAYMENTS`：2180 按发起方 = `OPEN` 预付款剩余之和；草稿、已驳回或已结束且释放任务已跑过的发布仍有 `OPEN` 预付款 → 差异。月报 `PREPAYMENT_FROZEN` / `PREPAYMENT_RELEASED`（`AdminFinanceIT` 逐行、场景快照）。
- IT：只有赠送额度的新发起方能提交；不足 422 什么也没写；冻结后撤回、驳回、审核过期三处释放，赠送退回原笔、原笔已到期即作废；结束后任务释放；后付的信用额含未开账单与已占用的；两次提交并发不超用；`StatementBudgetIT`。单元：检查规则。发起方页面 Vitest。e2e：`launch-loop.spec.ts` 第 2 步插入 2′（不足 → 422 明细 → 补足 → 提交通过；赠送额度先用），照 F-F12 §1。
- `PersonalDataEraser`：`QbPrepayment` 照现有钱表登记（注销收尾仍随 F-M15）。

### 8.7 请总控确认
1. **现在让检查有金额的第一项**：各费用都未做，只做框架则检查恒为 0、e2e 2′ 无法真跑。建议本项同时接入**受众规则费的冻结**：提交时按发布冻结的运营商价目（Q47c-1 的 `QB_AUDIENCE_FEE_QUOTE` 同一算法）冻结到 2180（用途 `AUDIENCE`），发布开始时从预付款记费（种类 `AUDIENCE`，归属冻结的运营商），驳回、撤回、过期全额释放；没有结束时间的续扣、欠费结束、贷项凭证与 `direct` 价目的删除仍随 F-M7。或者：本项只做框架，e2e 2′ 随 F-M7 / F-M6 补。
2. **`direct` 价目与 `qb.audience.price.fallback` 的删除**（§2.d，需求方 M4 前答复）：答复到了吗？没到之前本项照现有的价目选择，不删。
3. F-M11（冻结 `billingOperatorId`、调用方给 `operatorId`）随本项，同 §1。
4. 结束后的释放用每日任务（8.4），不改七八处结束的写法。
5. 共用文件：`PublicationLifecycle`（S1、S3 改过）只加提交、撤回、驳回、过期四处的步骤；发起方向导的检查一步与 F-F3 的新框架——开工前把要改的行发给 S1、S3。

### 8.8 总控的决定（2026-11-10，计划批准）
1. 本项接入受众规则费的冻结：提交时冻结、开始时从预付款记费、驳回 / 撤回 / 过期全额释放；续扣、欠费结束、贷项凭证随 F-M7。e2e 2′ 随本项补。
2. 需求方 2026-11-09 确认删除：`direct` 受众规则价目与 `qb.audience.price.fallback` 随本项删除（规格与 §2.d 注明，PR 的"删除的功能"写明）。
3. F-M11 随本项。
4. 结束后的释放用每日任务；帮助与发布页写"结束后一天内退回"。
5. 共用文件只需与 S3 对齐（S3 在做 F-F10）。钱的部分写完先报分支名，总控安排额外审查后再开 PR。

### 8.9 进度（S2，2026-11-11，需求方决定暂停开发，WIP）
分支 `1.3/quizbuks-FM5-funds-check`（迁移 `V115`：原定 V114，S4 的 #630 先全绿取了 V114），未开 PR。

**已做**：
- 后端：`QbPrepayment`（V115）、科目 2180 与维度 3 `purpose`、`PrepaymentRules`、`Prepayments`（`QB_PREPAYMENT_FREEZE` / `USE` / `CHARGE` / `RELEASE` / `RELEASE_ITEM`，每日 `qb.prepayment-release`）、`PublicationPrepayments`（提交时在钱包锁内检查与冻结、`QB_PUBLICATION_FUNDS_CHECK`、`PublicationEditing.write` 的 `note` 与提交 / 撤回 / 审批结果 / `ADVANCE` 末尾的 `after`）、`QB_FEE_CHARGE` 的 `operatorId` / `prepaymentId`（F-M11）、费用种类 `AUDIENCE`、核对 `PREPAYMENTS` / `PREPAYMENT_STALE`、月报三类与列 `feePrepayments`、模板 `qb.system.fee-receivable` / `prepayments-due`、`qb.sponsor.publication-prepayments`、`qb.sponsor.wallet` 的 `prepaymentHeld`、三语消息。
- 删除（需求方 2026-11-09 确认）：`direct` 受众价目与 `qb.audience.price.fallback`（后端、管理系统扩展的价目页、测试；页面删去的项见扩展的 `audiencePricing` 改动）。
- 页面：发起方向导确认步的"资金"一栏与"去充值"、发布页"预付款"、平台费账户"冻结在预付款中"；管理系统扩展的月报标签与 2180 列。
- 测试：`PrepaymentIT`（5 例通过）、`PrepaymentRulesTest`；`AudiencePricingIT`、`AudienceBillingTest`、`NoDirectOwnerTest`、`SetupIT`、`ProcessAccessIT`、`AdminFinanceIT` 与 setup / publishing / payout / topup 场景快照已随之改（单独运行通过）；发起方页面 Vitest 1245、扩展 502 通过；`platformCheck` 无错误。
- 文档：`operations.md` §7.46（原 §7.44，合并 F-C9 后顺延）、发起方手册底稿三语、`backend/quizbuks/CLAUDE.md`、规格与 §2.d 的删除说明、走查清单第 2′ 步；e2e `launch-loop.spec.ts` 第 2′ 步与 `wallet.ts` 的 `fund`（**未在本机运行**）。

**下一步（恢复后）**：
1. 合并最新 `1.3/quizbuks`；跑模块全部测试与 `platformCheck`（暂停时全量运行被中止，未得结果）。
2. 本机 e2e：`tools/quizbuks/e2e.sh run --build --fresh -- tests/launch-loop.spec.ts`，并检查 `sponsor.spec.ts` 是否要随向导的"资金"一栏改。
3. 补：提交的语句数（多一把钱包锁、一条合并读取，有受众条件时多两条价目读取，后付多一条 1130 读取，冻结子流程）写进 PR；`QbPrepayment` 的 `PersonalDataEraser` 照钱表随 F-M15。
4. 报总控分支名，额外审查后开 PR（描述逐条对照验收、删除的功能逐项列出）；F-F12 的 2′ 随本项验收。

### 8.10 Fable 的代码审查（@2a30c7ea，2026-11-12）与修改

**复核（@4c72191a）**：15 条都已修好，M5 的变通也认可。另有两件：
- N1（高）：恢复到 LIVE 的路径漏了记费。修改：发起方的 `QB_PUBLICATION_RESUME`、总部与运营商的恢复（`PublicationAdmin` 的全部流程）末尾都加 `PublicationPrepayments.after`，锁序是 发布 → 钱包。
  - 进入 LIVE 的路径已逐一核对：提交、审批结果、`ADVANCE`、三个恢复都已挂上。渠道的发布不冻结；`FUNDS_SHORT` → LIVE 只在开始之后，已记过费。
  - 新核对项 `PREPAYMENT_UNCHARGED`：LIVE、`FUNDS_SHORT` 或已有开始的发布，其 `AUDIENCE` 预付款还没有记费即算差异。
  - 测试：开始前暂停、开始后恢复（发起方与总部各一例）记费一次；已记费后暂停再恢复，不重复记。
  - 已记过费的发布每次恢复多一把钱包锁和一次读取，不记费。
- N2：迁移不在平台表上建索引，见下面的 L7。

结论"修后再审"，高 1、中 7、低 7，全部改了：

- **高 H1**：作废从预付款付的账单后，`QB_FEE_REFUND` 对带 `prepaymentId` 的分摊另过一笔借 2180、贷 2190 / 2160（来源为该预付款，月报 `PREPAYMENT_RELEASED`），负的分摊行带上 `prepaymentId`。`held()` 和核对只算费用取走的正数部分。付款冲正（1130 / 2180）在月报算 `FEE_VOIDED`。
- **中**：
  - M1：预付款不够付时，余额按账户顺序 `QB_FEE_SETTLE`，两段合成一张即开账单。
  - M2：怎样付按预付款的 `mode`（读入 `QB_FEE_CHARGE` 的合并读取），两种切换各一例，月账单不重复。
  - M3：`PREPAYMENT_STALE` 按发起方汇总，加上"结束超过 `qb.prepayment.release.overdue-days`"与"发布不存在"两种；新核对项 `PREPAYMENT_PARTS`。
  - M4：检查与冻结都用 `FeeAllocation` 算可付额（读现金各笔），现金各笔与钱包不一致时提交得到 422 而不是 500。
  - M5、M6：`PrepaymentIT` 在一个只有本例的月份里逐行断言 `PREPAYMENT_FROZEN`（赠送 + 现金）、`PREPAYMENT_USED`、`PREPAYMENT_RELEASED`、`FEE_CREDIT_VOIDED`、`FEE_CHARGED`；另补齐每日任务经 `JobRunner` 跑两次、未结束的发布跳过、"什么也没写"的全面检查、只追加表没有 UPDATE / DELETE 等用例。提交的语句数由新的 `SubmitStatementBudgetIT` 断言：什么也不冻结 31（原 29）、预付 38、后付 36。`AdminFinanceIT` 的月份没有平台费的活动，仍为 0，逐行的非零期望放在 `PrepaymentIT`。
  - M7：开始时（进入 `LIVE`）记费，不在批准时；批准后、开始前就结束的不记费，由每日任务全额退回。
- **低**：
  - L1：发起方行放进锁内那一条读取。
  - L2：赠送按到期日先用。
  - L3：分摊少于剩余即报错。
  - L4：`QB_PUBLICATION_FUNDS` 三语写明后付的是信用额。
  - L5：不相关的发布不发读取的 SQL；后付预付款超过上限即报错。
  - L6：索引改名为与列一致；每日任务的 cron 与上限是部署设置 `QB_PREPAYMENT_RELEASE_CRON` / `_MAX_ITEMS`。
  - L7：按发起方读 1130 现在用平台的 `dimension_1` 索引。更合适的 `(account_id, dimension_1)` 索引**依赖平台，由 S1 在平台分支补**（总控 2026-11-12：应用的迁移不在平台表上建索引）。现在数据量小，不影响正确性。

## 9. F-M6 配送费（S2，2026-11-11；第三稿 2026-11-12，总控 2026-11-12 批准）

规格 F-M6（规则、参数、运营闭合、验收）；§1 M5。在 F-M5 之上（提交时的项、2180、`QbPrepayment`、F-M11 的 `billingOperatorId`）。分支 `1.3/quizbuks-FM6-distribution`（从 F-M5 合入后的 `1.3/quizbuks` 拉出），迁移取开 PR 时的下一个空号。

### 9.1 现状与范围
- 开始答题已写开始一侧的计数 `QbPublicationStartCounter.starts`（开始锁内，Q10a4），**累计、只增**；本项只读它，不改答题路径（`AttemptStatementBudgetIT` 不变，不在 `qb_attempt` 上加索引）。
- 本项做：运营商价目表与总部范围、提交时冻结单价与预付（`PublicationPrepayments.items` 加一项 `DISTRIBUTION`）、按月结算任务与结算记录、结束后的结清与释放、页面、核对与月报。曝光费（F-M8）以后加进同一结算任务（种类 `EXPOSURE`），本项留好按种类的结构，不做曝光。

- 不做：封禁、注销时的最后一次结算与释放（随 F-M15）；曝光费（F-M8）。

### 9.2 数据（一个迁移）
- `QbDistributionPrice`（普通表，每个运营商至多一行，按版本更新）：运营商、比例 `rate`（四位小数）、单价下限 `unitFloor`、单价上限 `unitCeiling`、最低预付 `minimumDeposit`（JPY 0 位）、修改人、理由（必填）、时刻；平台审计。没有行 = 该运营商不收配送费（同受众规则"没有价目表 = 0"）。
- `QbDistributionSettlement`（**时态 `t.writeOnce()`**，同 `QbFeePayment`；迁移照时态实体的约定建索引、外键、`jabiz_protect_append_only`，另建只含主键的唯一索引）：发布、期间 `period`（`YYYY-MM`，日本时间）、上次累计 `fromStarts`、本次累计 `toStarts`、人数、单价、**累计应收金额** `cumulativeAmount`（不含税，见 9.3 取整）、本次金额（= 本次累计应收 − 上次累计应收）、税、合计、`final`、费用编号（金额为 0 时为空）、时刻。
  - 唯一索引 `(publication_id, period)`：同一项并发两次、月度结算与每日 `final` 交叠时只成一行，后到的一方读到已有行即什么也不写（流程内先读、唯一约束兜底，违反即该项失败、下次运行看见已有行而跳过）。
  - 部分唯一索引 `(publication_id) WHERE final`：每个发布至多一行 `final`。
- 发布（版本）加 `distributionUnit numeric(19,4)`（提交时冻结，草稿清空，同 `prepaymentTotal`）；卡片不加（学者读不到）。
- 费用种类 `DISTRIBUTION`：`FeeRules.KINDS`、`qb_fee_charge_version` 的 CHECK、`FeeProcesses.ChargeInput.kind` 与 `SettleInput.kind` 的正则（后者现在是 `MONTHLY|MANUAL`，`FeeProcesses.java:166`）、月报。`QbFeeCharge.period` 已有且必填，不改。
- `QbFeeCharge` 表照旧不执行 UPDATE / DELETE（`DistributionIT` 断言）。

### 9.3 规则、价目表与总部范围
- 规则只在纯 Java 的 `DistributionRules`：
  - 单价 `unit(rate, maxReward, floor, ceiling)` = clamp(rate × maxReward, floor, ceiling)，四位小数 `HALF_UP`。`maxReward` 一律取档位金额的最大值（`TierCoverage.maxReward`）；为 0 或没有档位（只绑定节点）即按下限（总控决定 1）。
  - **按累计取整**（总控决定 6）：累计应收 = ⌈累计人数 × 单价⌉（日元）；本次金额 = 累计应收 − 该发布此前各结算行的金额之和；税 = 本次金额 × `qb.fee.tax-rate` 向下取整（`FeeRules` 现有的 `FLOOR`）。
  - 预付 `deposit(maxWinners, unit, minimumDeposit)` = `maxWinners` 有值时 ⌈maxWinners × unit⌉ + 税，否则 `minimumDeposit`。
  - `priceProblems`：各值 ≥ 0、下限 ≤ 上限、都在总部范围内。
- `QB_OPERATOR_DISTRIBUTION_PRICE_SET`（运营商负责人，`OperatorChecks.member` 且是负责人，锁 `qb.operator:<运营商>`）与 `QB_DISTRIBUTION_PRICE_SET`（总部 `qb.fee.billing`，为某个运营商设）。理由必填；只影响此后提交的发布（单价在提交时冻结）。
- 总部范围是受控参数（四眼）：`qb.distribution.rate.min|max`、`unit-floor.min|max`、`unit-ceiling.max`、`minimum-deposit.max`。
  - 保存价目时按当时的范围检查。范围改窄后已有价目不自动改，每日核对报 `DISTRIBUTION_PRICE_OUT_OF_RANGE`，给 `qb.admin.finance.read` 待办。
- 规格补写定价规则（随本项提交）：单价按档位金额的最大值算，为 0 或没有档位即按下限。
- **档位金额的单位（总控决定 1 的补充，2026-11-12）**：所有奖励方式的档位金额都是 **Kudos 数**（`RewardMethod`：非平台付奖的是奖品价值的 Kudos 数）。单价 = clamp(`rate` × 档位最大的 Kudos 数, 下限, 上限)，所有方式同一算法，不折算日元。`rate` 的单位是**日元 / Kudos**（每 1 Kudos 奖励收多少日元），不是百分比：规格 F-M6 的规则行、运营商价目页、总部范围 `qb.distribution.rate.min|max` 的参数说明与帮助三语都这样写。
- **下限防自报偏低**：非平台付奖的档位金额是发起方自报的，报低即落到下限。所以 `qb.distribution.unit-floor.min` 的缺省值 > 0：先取占位值（`1.0000` 日元 / 人），参数说明写"打开 `qb.distribution.billing.enabled` 之前由需求方确认"，并登记到 F-F10 的上线开关清单（`FirstLaunchSwitches` 中 F-M6 那一行的前提）。结算行记 `atFloor`（单价 = 下限），结算列表 `qb.admin.distribution-settlements` 与运营商的结算页标出"按下限计费"，核对项 `DISTRIBUTION_AT_FLOOR`（只列出，不算差异）供运营商与总部查看。
- **单价在提交时冻结，之后不变**：`PublicationVersions`（发布切换题目版本）只换题目版本，不改档位与 `distributionUnit`；`DistributionIT` 加一例：切换版本后单价与档位最大值不变，结算仍用冻结的单价。
- **上限**：提交时 ⌈`maxWinners` × 单价⌉ + 税不超过 `FeeProcesses.MAX_AMOUNT`，超过即 422 `QB_DISTRIBUTION_DEPOSIT_RANGE`（带 `maxWinners`、单价、上限），不交给冻结时才失败（N5）。

### 9.4 提交（`PublicationPrepayments.items` 加一项）
- 开关 `qb.distribution.billing.enabled`（受控，缺省 false）**只在提交时看**（总控决定 9）。
  - 关时：这一项为 0，什么也不冻结，`distributionUnit` 为空。结算任务只取单价不为空的发布。
  - 已经提交、冻结了单价的发布照常结算到结束。关掉开关不追溯，不影响它们。写进 `operations.md`，`DistributionIT` 测"开着提交 → 关掉 → 照常结算并释放"。
- 开时：
  - 在钱包锁内已有的读取之后，按 `billingOperatorId` 读该运营商的价目，并入 F-M5 已有的价目读取那一条 `QueryEntities.all`。
  - 没有受众条件的发布原来没有那条价目读取，所以**开关打开后每次提交多一条价目读取**（总控中 7）。本应用没有提交路径的 `StatementBudgetIT`：本项在 `DistributionIT` 中用 `SqlStatementLog` 断言提交的确切语句数（开关关 / 开、有无受众条件四种），PR 中逐项说明。
  - 算出单价与预付，作为项 `DISTRIBUTION`（`amount` = 预付，`chargeAmount` = 0：上架时不记费，结算时才记）。
- 挑战（`forSponsors`）、没有价目表的运营商：0。店面（`publisherKind` 不是 `SPONSOR`）不收：F-M5 只对发起方的发布冻结，结算任务的取项同样只取 `SPONSOR` 的发布。
- 直营发起方照付（需求方 2026-11-12 确认，总控决定 3）。
- 预付不足：F-M5 同一个 422 `QB_PUBLICATION_FUNDS`，明细多一项。

### 9.5 结算 `QB_DISTRIBUTION_SETTLE`
**"该结算"的定义**（总控中 4）：发布 P 的期间 M 该结算，当且仅当
- P 有 `distributionUnit`、`publisherKind` = `SPONSOR`、上架过（`startAt` 早于 M 的结束）；
- P 在 M 开始之前没有结束；
- P 没有 `final` 行。

有 `final` 行即视为此前各月都已结算：`final` 行的累计包括结束前的全部开始，此前漏掉的月份由它一并结清，不再要求补那些月的行。

**两个任务**（都是 `JobDefinition.forEach`、每项一个事务、项流程 `internal()`，可经 `JOB_RUN_REQUEST` 补跑；cron 都写进 `FeeConfig`，`CRON_TZ=Asia/Tokyo`）：
- 月度 `qb.distribution-fee`：每月 2 日 00:37，取项模板 `qb.system.distribution-due`。取上月该结算、还没有上月结算行的发布，期间 = 上月。
- 每日 `qb.distribution-final`：每天 03:07，在 03:23 的 `qb.prepayment-release` 之前。取 `endedAt ≤ 运行时刻 − qb.distribution.final.end-grace-sec`、还没有 `final` 行的发布，期间 = 结束所在的月，`final` = true。
  - 宽限 `qb.distribution.final.end-grace-sec` 为受控参数，照 `qb.exposure.reconcile.end-grace-sec` 的做法（缺省 3600，0 到一天）。它让结束前已在开始锁内的开始都已提交。

**每项流程**：
- 只取 `qb.wallet:SPONSOR:<发布方>`，不取发布锁、开始锁。锁序不变：钱包锁之后不再取任何锁。累计只增，读到较旧的值只少算本次、下次按累计补上，`final` 由宽限保证读到最终值。
- 锁内一条 `QueryEntities.all`，读：
  - 开始计数；
  - 该发布的全部结算行（已记金额之和、是否已有本期或 `final` 行）；
  - 该发布 `DISTRIBUTION` 的 `OPEN` 预付款。
- 已有 `final` 行：什么也不写。已有本期的普通行（月度任务写的）而本次是 `final`：见下条"两个任务都能写 `final`"。
- **两个任务都能写 `final`**（N1）：月度任务的取项遇到已结束且 `endedAt ≤ 运行时刻 − 宽限` 的发布，这一行直接写成 `final` 并释放（期间 = 上月，累计取到结束）；每日 `final` 任务取"已结束过宽限、还没有 `final` 行"的发布，期间 = 结束所在的月。谁先运行谁写 `final`，另一个看到已有 `final` 即跳过。每日任务写 `final` 的期间：**取结束所在的月；该月已有行，就取其后第一个没有行的月**（唯一约束 `(publication_id, period)` 不冲突）。测试：发布在月末结束，月度任务在宽限满足之前运行、写了该月的普通行，宽限满足之后每日任务写 `final`（期间为下一个月）；以及反过来的先后。两种都只成一行 `final`、预付款释放一次。
- **写入顺序**（N4）：结算事务中先写结算行（`final` 时含 `final`）并 `SaveChanges.now` 写出，再记费，再调 `RELEASE`（`RELEASE` 在锁内重读 `final` 行，看得到本事务刚写的）。
- 否则写结算行。本次金额为 0（人数为 0，或累计取整后没有增加）时不记费（总控低 12）；否则 `QB_FEE_CHARGE`（种类 `DISTRIBUTION`、`period` 见 9.6、`operatorId` = `billingOperatorId`、`prepaymentId`）。
- `final` 时在同一事务中释放该发布 `DISTRIBUTION` 用途的预付款剩余（9.7）。

**扣款顺序**（规格"先从该发布的预付款扣，不够按 F-M1 顺序从账户扣，再不够成未付或应收"；总控决定 5、中 8）：
- 预付的发起方：先 `QB_PREPAYMENT_USE` 从预付款付，余额交给 `QB_FEE_SETTLE` 按账户顺序付。仍不够，即账单 `PARTIAL` / `OPEN`。
  - 两次付款合成**一张即开账单**：`paidAmount`、状态、`paidNow` 都按两段的合计；`QbFeePayment` 两段都写（预付款段 `prepaymentId`、账户段各笔）。
- 后付的发起方：费用记应收（1130），从 `POSTPAID` 预付款减去本次金额（信用额不重复占用：此后 1130 余额含这笔，`POSTPAID` 剩余相应减少），不动账户。
- `AUDIENCE` 的费用金额恒等于其预付，路径不变；保留"`AUDIENCE` 行为不变"的回归测试（`PrepaymentIT` 现有三例照跑）。

欠费时进行中的发布照常结算（只拦新提交，`FeeGate` 已有）。

### 9.6 与账单的先后（`qb.fee-invoice`；总控高 1、高 2，决定 2）
- **运行日**：账单任务改为每天 01:17（日本时间），但不在每月 1 日运行。cron 写进 `FeeConfig`：`CRON_TZ=Asia/Tokyo 0 17 1 2-31 * *`。1 日不开，月度结算（2 日 00:37）一定先于第一次开账单。
- **取项**（取代 `previousPeriod`）：所有（发起方, 月份 M）满足以下三条：
  - M 早于本月；
  - 该发起方在 M 有还没有账单的后付费用；
  - 该发起方在 M 该结算的发布（9.5 的定义）都已有 M 的结算行或 `final` 行。

  两个月后才补上的结算，补上后的下一次运行即开出那个月的账单。
- **迟到的费用**（高 2b）：费用的 `period` 一律由记费时决定，规则只有一条：期间 M 的账单（`chargeId` 为空的那张）已经开出后，再记的期间 M 的费用，`period` 记为记费时的当前月，进入当前月的账单。
  - 实现：`QB_FEE_CHARGE` 在钱包锁内读该发起方 M 的账单，有即改用当前月（同一锁，账单的开出也在该锁内，不会交错）。这条读取并入 `QB_FEE_CHARGE` 已有的那条 `QueryEntities.all`（发起方、运营商、预付款），只在后付时有条件（预付的费用开即开账单，不需要；条件为空不发 SQL），不多一条语句（N3）。`DistributionIT` 用 `SqlStatementLog` 断言月费、手工费、`AUDIENCE` 记费的语句数与改前相同。
  - 结算行仍记 M（它是"哪个月的人数"），费用记 `period` 实际进入的月。
- **测试**：
  - 账单开出后补跑 M 的结算 → 费用进入当前月，没有无账单的应收（`AdminFinanceIT` / 核对 `FEE_RECEIVABLE` 无差异）；
  - 结算拖到两个月后 → 补上的下一次运行开出 M 的账单；
  - 1 日不开账单；
  - 日本时间月界：3 月 31 日 23:59 JST 与 4 月 1 日 00:00 JST 开始的人数分属两期。
- **`final` 的人数记到结算的月份**（N2，接受）：结束后的 `final` 行把结束所在月（或上月）最后一段的人数结在写 `final` 的那天；费用的 `period` 照 9.6 的规则（那个月的账单已开出即记当前月），所以可能晚一个月收。规格 F-M6 与 `operations.md` 写明这点；运营商结算（F-M12）按费用的 `period` 与归属运营商分成，所以这部分分成也在下一个月的结算里，不是差异。对账时以结算行的 `period`（人数的月份）与费用的 `period`（账单的月份）区分。
- 每日核对报 `DISTRIBUTION_UNSETTLED` 给财务待办：月初之后超过 `qb.distribution.unsettled-after-hours`（受控，缺省 30，即 2 日 06:00）仍有该结算、没有行的（发布, 月份）。核对随日常对账任务（`quizbuks.reconcile.run-cron`）运行，晚于 2 日 00:37 的结算。

### 9.7 释放按用途（总控高 3）
- 现在 `QB_PREPAYMENT_RELEASE` / `RELEASE_ITEM` 读一个发布的全部 `OPEN` 预付款一起释放（`Prepayments.java:431-434`）。F-M5 的四个调用处（撤回、驳回、审核过期、结束后）都要全部释放，所以 F-M5 本身没有问题；F-M6 起一个发布有两种用途，结束后的释放必须分开。
- 改为：`ReleaseInput` 加可选的 `purpose`（为空 = 全部用途，F-M5 的撤回、驳回、过期照旧）。条件写在 `RELEASE` 流程的读取里，锁内重读。`final` 时再读该发布的 `final` 行，没有即不释放 `DISTRIBUTION`，防止并发的结算还没写完就放掉。
- 结算流程调用 `RELEASE`（带用途），不调用 `RELEASE_ITEM`（后者是每日任务的项，`internal()`，自己取锁）。
- 每日 `qb.prepayment-release` 的取项与 `RELEASE_ITEM` 都改为按（发布, 用途），取项模板的键是这两列（N4）：
  - `DISTRIBUTION` 用途只在有 `final` 行之后取；
  - `AUDIENCE` 等其他用途照 F-M5（结束后）。
- 已核对：`AUDIENCE` 在开始时用完后仍是 `OPEN`、剩余 0，到结束后由每日任务关闭（`PrepaymentIT` 的 `goingOnCharges…` 断言）。按用途释放后，它不会因为 `DISTRIBUTION` 还没有 `final` 而被拖住，也不会提前放掉 `DISTRIBUTION`。
- 测试：另一用途还是 `OPEN` 时不提前释放；`final` 与每日释放交叠时只释放一次。

### 9.8 读取与页面
- **发起方**：
  - 向导确认步"资金"一栏多一行配送费：单价、预付（`QB_PUBLICATION_FUNDS_CHECK` 同一段 Java）。
  - 发布页"配送费"（模板 `qb.sponsor.publication-distribution`）：单价、**上次结算以来开始的人数**与预计金额（总控低 13；人数读开始计数，是发起方本来就看得到的数）、历次结算。
  - 平台费账户的费用列表按种类显示。
- **学者**：读到的内容不变。不在 feed、发布、卡片模板里加任何东西；`DistributionIT` 比较开关前后 `qb.app.feed` / `qb.app.publication` 的列。
- **运营商管理系统**：配送费价目页（负责人可改、成员只读）。
- **平台管理系统扩展**：
  - 各运营商价目与总部范围（范围的修改链到受控变更）；
  - 全部结算列表（`qb.admin.distribution-settlements`，`qb.admin.finance.read`，可导出）。
- **帮助三语**（发起方手册底稿）："学者得到的奖励就是发起方设定的全部；配送费另由平台按开始答题的人数收取"。
- **`operations.md`** 新小节（当时的最大号 + 1）：
  - 价目与范围；
  - 两个结算任务与账单任务的时刻与先后（00:37 月度、01:17 账单（1 日除外）、03:07 结束结算、03:23 释放）；
  - 补跑；
  - 关掉开关不追溯；
  - 核对项的处理。

### 9.9 对账、月报与测试
- **核对**（时长都是受控参数）：
  - `DISTRIBUTION_DEPOSITS`：2180 用途 `DISTRIBUTION` 按发布 = `OPEN` 预付款剩余；
  - `DISTRIBUTION_UNSETTLED`（9.6）；
  - `DISTRIBUTION_FINAL_MISSING`：结束超过 `qb.distribution.final-missing-after-hours`（缺省 48）仍没有 `final`；
  - `DISTRIBUTION_PRICE_OUT_OF_RANGE`（9.3）；
  - `DISTRIBUTION_AT_FLOOR`：只列出按下限计费的发布（不算差异），供运营商与总部查看（9.3）。
- **月报**：
  - `PREPAYMENT_FROZEN` / `RELEASED` 按用途分出 `DISTRIBUTION_DEPOSIT` / `DISTRIBUTION_RELEASED`（规格的名字）；
  - `FEE_CHARGED` 按种类分列（`DISTRIBUTION`）；
  - `AdminFinanceIT` 逐行。
- **IT（`DistributionIT`）**：
  - 开关：关不冻结不收；开着提交 → 关掉 → 照常结算并释放；提交的语句数（9.4）。
  - 人数与金额：0 人只写结算行不记费；金额为 0 不记费；1 人收 ⌈单价⌉ + 税（FLOOR）；未答完、不合格都计；跨两个月与多个月按累计取整（如单价 0.4：1 人、再 1 人、再 1 人 → 1、0、1）。
  - 并发与交叠：同一项并发两次只成一行；月度结算与每日 `final` 交叠只成一行。
  - 扣款与账单（F-F11 要求的验收）：**赠送额度先于现金**支付配送费（冻结时赠送先，余额从账户扣时也是赠送先，一例）；先扣预付、不够从账户（赠送先、再现金）、再不够成 `PARTIAL` / `OPEN`；三个来源的分摊与即开账单的状态、`paidAmount` 按合计。
  - 后付：`POSTPAID` 随结算减少、信用额不重复占用。
  - 释放：结束后 `final` 结算并按用途释放剩余；另一用途 `OPEN` 时不提前释放。
  - 账单的先后：9.6 的四例。
  - 不收或照付：直营发起方照付；挑战不收；店面不收。
  - 归属与价目：改派运营商后仍按冻结的运营商；价目只影响此后提交的。
  - 不变的部分：学者模板开关前后相同；`qb_fee_charge_version` 与结算表没有 UPDATE / DELETE；切换题目版本后单价不变。
  - N1：发布在月末结束、月度任务在宽限满足之前运行（写了普通行）、之后每日任务写 `final`，以及反过来，都只成一行 `final`、释放一次。
  - N3：月费、手工费、`AUDIENCE` 记费的语句数不变；N5：`maxWinners` × 单价超过上限即 422。
- **单元 `DistributionRulesTest`**：clamp、四位小数、累计取整、税、无 `maxWinners`、无档位、范围。
- **其他**：场景快照、发起方页面 Vitest、扩展测试。
- **e2e**：`launch-loop.spec.ts` 第 2′ 步的合计含配送费预付（开关打开）；新增一步"答题后结算"（以 `JOB_RUN_REQUEST` 补跑结算任务）。
- `PersonalDataEraser`：两张表照钱表登记（随 F-M15 收尾）。

### 9.10 原来的问题（§9.9 第一稿，已由总控决定，见 9.11）
1. 不是平台付奖的发布的 `maxReward`。2. 账单任务每天运行。3. 验收行"直营发起方不收"。4. 结束后当即结算。5. 预付款不够转账户扣。6. 取整。7. 共用文件：`PublicationPrepayments`、`FeeProcesses`、`FeeConfig`（本人的）；`QbParams` 与 S3 的 F-F10a 同改，后合者合并基础并重生成快照；`FirstLaunchSwitches` 的 `WAITING(F-M6)` 行随本项改为 ACTIVE。

### 9.11 总控的决定（2026-11-12，Fable 审查计划后）
1. 同意一律按档位金额算，为 0 或没有档位即按下限；定价规则写进规格（9.3）。单位（2026-11-12 补充）：档位金额是 Kudos 数，单价 = clamp(比例 × 档位最大的 Kudos 数, 下限, 上限)，比例是"日元 / Kudos"，不折算日元；规格规则行、价目页与帮助写清单位。
2. 同意账单任务每天运行，以高 1、高 2 改好为前提（9.6）。
3. 需求方 2026-11-12 确认：直营发起方照付。规格 F-M6 验收行改为"直营发起方照付（需求方 2026-11-12 确认）"，随本项提交。
4. 同意结束后由每日任务当即结算并释放，以中 4、中 5 改好为前提（9.5）。
5. 同意：预付的先 `USE`、余额再 `SETTLE`；后付的照旧记应收；保留 `AUDIENCE` 行为不变的回归测试（9.5）。
6. 单价四位小数 `HALF_UP`，先取整到日元再算税，取整按累计（9.3）。
7. 同意；正则、`KINDS`、CHECK、月报一并放开（9.2）。
8. 开关只在提交时看，关掉不追溯（9.4）。
9. 语句数在 `DistributionIT` 中用 `SqlStatementLog` 断言，不另建测试类（9.4）。
10. 第二次复核（@115b56e）：N1 两个任务都能写 `final`、谁先谁写；N2 `final` 的人数晚一个月收，接受并写进规格与 `operations.md`（含 F-M12 的影响）；N3、N4、N5 照改；下限的缺省 > 0 并登记上线开关清单；切换版本不改单价（9.3、9.5–9.9）。
11. 规格 F-M6 的改动（验收行"直营发起方照付"、规则行的单位与"按下限"、N2 的说明）随本项的代码提交。
12. **总控 2026-11-12 批准**（Fable 第三次复核 @644a38b："可以批准"），采纳两条小建议：N1 的期间规则写成确定的一句；"月度先于每日"的测试造成"月度写了普通行之后才满足宽限"。顺序：F-M5 合格并开出草稿 PR 之后，从 F-M5 的分支拉出 `1.3/quizbuks-FM6-distribution`；迁移暂定 `V116`（#630 取了 V114，F-M5 顺延为 V115），取开 PR 时的下一个空号。
