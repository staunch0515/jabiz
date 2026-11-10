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
- 买学分：`KudosExchanges.problems(..., operatorCash)` 的可用额减去 `OPERATOR` 笔的剩余，`CashLots.takeExcept` 不取它们；`qb.sponsor.wallet` 给出 `cashFromOperator`，页面的买入上限为 `buyableCash`。充值退款与争议的扣回照旧可以取任何笔（2160 与各笔必须一致），写在 operations §7.43。
- 核对 `OPERATOR_ACCOUNT`（`recon-fee-account`）；月报 `OPERATOR_TRANSFER_TO_DIRECT`（管理系统扩展同时补上 F-M2 漏掉的 `FEE_CREDIT_GRANTED` / `FEE_CREDIT_VOIDED` 的文字）。
- 页面：运营商「账户」页的"转给直营发起方"（金额、说明、确认、二次验证由平台询问）与转入记录；发起方平台费账户页的"运营商转入"一行。
- 测试：`OperatorTransferIT`（5 例：负责人、他人、超额与非整数、幂等键、分录与现金笔、模板、月报、核对；封禁；买学分与平台费；并发两笔；核对找出没有分录的转入）、`OperatorRulesTest`、`KudosExchangesTest`、扩展与发起方页面的 Vitest。e2e 没有新用例：运营商账户要有结算记入才有余额，e2e 中没有结算的途径（与提现相同），页面由扩展的测试覆盖。
