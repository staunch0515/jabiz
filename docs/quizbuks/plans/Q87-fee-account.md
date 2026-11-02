# Q87 商户的费用账户：平台费与学分分开，抽成先设为 0%（计划，2026-11-03，待需求方确认 §9）

需求方 2026-11-03 的要求：在法律问题（01 §4.6 資金決済法）处理好之前先开通 Kudos 奖励；答题者奖励的抽成**功能保留、设为 0%**；另给商户建**真正的现金账户**收平台费，与学分分开，便于处理与收费。
编号 Q87 是建议，确认时可改。**这只是计划**：确认 §9 后开发，一个 PR，工作分支 `1.3/quizbuks-87-fee-account`。

## 0. 为什么

- 学分（Kudos）钱包是商户**预付、替答题者保管、最终付给第三方**的钱（账本 2110 商家预收款），正是資金決済法要看的那一块；平台费是平台**自己的服务收入**，要开适格请求书、算消费税。混在一个余额里，"存款"与"费用"说不清，监管面与税务面都大。
- 分开之后：Kudos 钱包只做一件事——付奖励，抽成设为 0% 即"奖励全额归答题者"，平台在这一侧不取任何收入；平台的收入全部在费用账户，以**账单**而不是余额的形式存在，平台不替商户持有任何现金。这比"再建一个预付的现金余额"更干净：预付给平台自己的钱同样是前払式支払手段（自家型）的范围。
- 04 §6.10 说平台使用费"要一次规划"：费用账户就是那一次规划的容器，各种费（月费、规则费、CPK、AI 超额）以后只是加一个种类。

## 1. 现状

- 抽成：受控参数 `qb.creator-fee-rate`（四眼修改），结算每笔奖励时从奖励里扣（`RewardSplit`，向下取整），分录 2110 → 2120 + 4110；**代码已支持 0**（`RewardSplit.validRate` 接受 0；`fee = 0` 时不出 4110 行，`QbWallets` 的过账只在 `fee > 0` 时加那一行；运营商分成 = 抽成 × 比例 = 0）。
- 充值：Stripe Checkout（`QB_TOPUP_*`，Q6a）与银行转账登记（Q13a），到账过账 1110/1120 → 2110，收据单据 `qb.topup-receipt`（Q9d，适格请求书的开具方 `qb.issuer.*`）。
- 运营商：抽成按运营商记账、按月结算单、分成比例受控参数（Q48b）；"直接收款"以后（D-Q48-8）。
- 没有平台使用费、规则费（Q47c 以后）、CPK；AI 用量只有配额没有收费（Q8a）。

## 2. 决定（草案）

| # | 决定 |
|---|---|
| D-Q87-0 | **抽成设为 0% 是运营步骤，不改代码**：上线时以 `CONTROL_CHANGE_PROPOSE` / `_PUBLISH`（四眼）把 `qb.creator-fee-rate` 改为 0；功能、参数、报表、月结算都保留（为 0 的行不出）。本计划只补一个 IT 用例（`RewardSplitTest` 与 `AttemptIT` 的"0% 时答题者得全额、账本没有 4110 行、运营商结算为 0"）与 `operations.md` 的步骤。 |
| D-Q87-1 | **费用账户 = 应收账单，不是余额**：平台记"费用"、按月出账单、商户付账单；平台不替商户持有费用的预付款。学分钱包不动。 |
| D-Q87-2 | **费用** `QbFeeCharge`（只追加：商户、发布方那一对、`kind`、`period`（日本时间的自然月）、`amount`（JPY，整数）、`taxRate`、`tax`、`sourceEntity` / `sourceId`、`createdAt`、`invoiceId`（出账单时填）），只经 `QB_FEE_CHARGE`（`internal()`，由产生费用的定时任务或流程以子流程调用）写入。**种类在代码中**（`FeeKinds`）：第一步 `MONTHLY`（平台月费，受控参数 `qb.fee.monthly`，缺省 0 = 不收，每月 1 日的逐项任务 `qb.fee-monthly` 对已入驻的商户记一条，为 0 时不记）、`MANUAL`（总部手工记一笔，`QB_FEE_CHARGE_MANUAL`，必须填理由，四眼：经平台审批）；以后的规则费（Q47c）、CPK、AI 超额只是加种类。 |
| D-Q87-3 | **账单** `QbFeeInvoice`（普通表、`processOnlyWrites`：商户、`period`、`invoiceNo`（`NumberSequence`，`QBF-<年>-00001`）、`subtotal`、`tax`、`total`、`status` ISSUED / PAID / OVERDUE / VOID、`dueAt`（出单后 30 天）、`documentRunId`（单据 PDF）、`paidAt`、`paymentId`）：每月 2 日的逐项任务 `qb.fee-invoice` 把上月未出单的费用合成一张（没有费用即不出单），以子流程 `DOCUMENT_ISSUE` 签发版式 `qb.fee-invoice`（**适格请求书**：开具方 `qb.issuer.*` 与登録番号、按税率的小计、消费税、合计、支付方式与期限；语言按商户的邮件语言），事务邮件通知（`mail.qb.fee-invoice`，不可退订）。 |
| D-Q87-4 | **支付**：两种，复用充值的机器。① Stripe：`QB_FEE_PAYMENT_START` 为账单开一个 Checkout（金额 = 合计，同 `QB_TOPUP_START` 的外部服务与核对 `TopUpChecks` 的写法），`checkout.session.completed` 经同一入站 Webhook 路由到 `QB_FEE_PAYMENT_CONFIRM`（`internal()`）：核对后过账并记 PAID；Stripe 手续费照 5110。② 银行转账：`QB_FEE_BANK_REPORT` / `_RECORD`（照 Q13a：商户报告已汇、财务登记实收、差额另记）。**不做**绑卡自动扣（off-session），留第二步。 |
| D-Q87-5 | **账本**：科目 1130 应收平台费（资产，维度：商户）、4120 平台使用费收入（收入）、2150 应付消费税（负债）。出账单：1130 → 4120 + 2150；收到：1110 / 1120 → 1130。作废（`QB_FEE_INVOICE_VOID`，总部，四眼，必须填理由）：冲正。每日核对（F-06）加"应收 = 未付账单合计"。运营商：**平台费第一步不分成**（D-Q48-7 的分成只对抽成），运营商月结算单不含它；以后要分成只加一个比例参数。 |
| D-Q87-6 | **逾期**：每日任务 `qb.fee-overdue` 把过期未付的记 OVERDUE、发一封提醒（不可退订，7 天一封至多三封）、写待办给总部财务；**不自动停用商户**。逾期 ≥ 30 天的商户不能提交新发布（`PublicationRules` 一条检查，422 `QB_FEE_OVERDUE`，发布页与向导显示原因与账单链接）；已上架的照常运行。 |
| D-Q87-7 | **界面**：商户后台"钱包"分两页签——"学分"（现页）与"费用账户"（本月已记的费用、账单列表与状态、支付按钮、下载 PDF、银行转账的报告）；概览的指标卡加"待付账单"。管理后台扩展：费用与账单的列表（按商户、状态、期间）、手工记费、作废、银行实收登记、逾期待办。 |
| D-Q87-8 | **不做**：费用的预付余额、绑卡自动扣、运营商直收（D-Q48-8）、抽成搬到账单（奖励照旧由 Kudos 钱包结算，0% 即不扣）、多币种。 |

## 3. 后端（`backend/quizbuks`，包 `fee`）

- 迁移（编号以合入时为准）：`qb_fee_charge`（只追加：主键、索引 `(sponsor_id, period)`、`invoice_id`；`jabiz_protect_append_only`）、`qb_fee_invoice`（普通表：`invoice_no` 唯一、`(sponsor_id, period)` 唯一、索引 `status, due_at`）、`qb_fee_payment`（普通表：账单、方式、Checkout 的编号与状态，同 `qb_topup` 的那几列）；账本科目 1130 / 4120 / 2150 与 `qb.journal.account.<科目>` 的受控参数（Q9d）。
- 实体与字典：`QbFees`（三个实体、字典 `fee-kind`、`fee-invoice-status`）；`FeeKinds`（纯 Java 目录）；`FeeRules`（纯 Java：税率、取整、逾期、能否提交）。
- 流程：`QB_FEE_CHARGE`（`subProcessOnly()`）、`QB_FEE_CHARGE_MANUAL`（总部，`requiresMfa`，`RequireApproval`）、`QB_FEE_MONTHLY_ITEM`（`internal()`，逐项任务 `qb.fee-monthly`）、`QB_FEE_INVOICE_ISSUE_ITEM`（`internal()`，逐项任务 `qb.fee-invoice`，`AssignNumber` + `DOCUMENT_ISSUE` + `LEDGER_POST` + `SendMail`）、`QB_FEE_PAYMENT_START`（商户，`qb.wallet.topup` 的权限或新权限 `qb.fee.pay`）、`QB_FEE_PAYMENT_CONFIRM`（`internal()`）、`QB_FEE_BANK_REPORT` / `QB_FEE_BANK_RECORD`、`QB_FEE_INVOICE_VOID`（总部，四眼）、`QB_FEE_OVERDUE_ITEM`（`internal()`，逐项任务 `qb.fee-overdue`）。锁：`qb.fee:<商户>`，不与钱包锁同时持有（费用账户与学分互不相干）。
- 单据：`DocumentLayout` `qb.fee-invoice`（模板 `qb.document.fee-invoice`），文案 `document.qb.fee-invoice.*`（三语）；保留期 `RetentionPolicy.of("DocumentRun").only(layout)` 与收据相同（Q77 的做法）。
- 模板：`qb.sponsor.fee-charges`、`qb.sponsor.fee-invoices`、`qb.admin.fee-invoices`、`qb.admin.fee-charges`；每日核对加一项。
- 参数：`qb.fee.monthly`（受控，缺省 0）、`qb.fee.tax-rate`（受控，缺省 0.10）、`qb.fee.due-days`（普通，缺省 30）。

## 4. 前端

- 商户后台：钱包页的两个页签（`sponsor/src/wallet/FeeAccount.tsx`）、概览卡、发布向导与发布页的逾期提示。
- 管理后台扩展：`FeesPage.tsx`（账单与费用、手工记费、作废、银行登记）。
- 邮件模板三语：账单已出、逾期提醒。

## 5. 测试

- `FeeRulesTest`、`FeeKindsTest`；`RewardSplitTest` 与 `AttemptIT` 的 0% 用例（D-Q87-0）；`FeeIT`（月费记费、出账单与单据、Stripe 支付的核对与过账、银行登记、作废冲正、逾期与提交被拒、每日核对）；`JournalExportIT` 的新科目；`StatementBudgetIT` 不涉及（答题路径不变）。
- 场景 `fees.yml`（固定时钟：记费 → 月初出单 → 支付 → 逾期）；快照随新科目与参数更新。
- 前端 Vitest 与 e2e `fees.spec.ts`（商户看到账单、用 Checkout 存根付款、状态变 PAID）。

## 6. 文档

`02-design.md` §3.4（费用账户三实体）、§4.1（新科目）、§4.2（分录）、§5.2（流程）、§7.2 / §7.3；`backend/quizbuks/CLAUDE.md` 第 4 节"费用账户"一条（费用只经 `QB_FEE_CHARGE`、账单只经任务出、学分钱包不碰费用）；`04-product-direction.md` §6.10 指向本计划；`operations.md`：抽成设 0%、登録番号与税率、逾期处理；`launch-checklist.md`；`03-plan.md` 的 Q87 行；本文件 §10。

## 7. 估计

| 部分 | 天 |
|---|---|
| 0% 抽成的用例与运营步骤 | 0.5 |
| 实体、迁移、科目、规则、记费与账单任务、单据 | 2.5 |
| 支付：Checkout 与 Webhook 路由、银行转账、作废、逾期 | 2 |
| 商户后台与管理后台扩展 | 2 |
| 测试（IT、场景、e2e）与文档 | 1.5 |
| **合计** | **8.5** |

## 8. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 高 | 适格请求书的格式、税率、取整要会计师确认；登録番号未取得就不能开 | 版式与税率都是参数；`qb.fee.monthly` 缺省 0，上线不出任何账单，确认后再开 |
| 中 | Kudos 钱包的資金決済法问题分开后变小但没消失 | 法务意见仍要；抽成 0% 使平台在资金流中不取利，陈述更简单 |
| 中 | 同一入站 Webhook 路由两种 Checkout（充值与账单） | 以 Checkout 的 `metadata.kind` 区分，`TopUpChecks` 核对两边都要，`FeeIT` 覆盖串线 |
| 低 | 逾期停提交误伤 | 只在 ≥ 30 天、只拦新提交、页面写明、总部可作废 |

## 9. 需要需求方确认的

1. D-Q87-0：抽成以受控参数设为 0%，代码不改（只补用例与运营步骤）。
2. D-Q87-1：费用账户是**后付的账单**，不是预付余额；平台不持有费用的预付款。
3. D-Q87-2：第一步只有两种费用——月费（缺省 0，不出单）与总部手工记费（四眼）；规则费、CPK、AI 超额以后加种类。
4. D-Q87-4 / D-Q87-6：支付用 Stripe Checkout 与银行转账，不绑卡自动扣；逾期 30 天只拦新提交。
5. D-Q87-5：平台费第一步不与运营商分成。
6. 会计师 / 法务：适格请求书与税率；Kudos 钱包与資金決済法（旧问题）。
7. 编号 Q87，一个 PR，约 8.5 天，三张表的迁移。建议 Q86 先做（纯增量），Q87 等会计师的答复后做。
