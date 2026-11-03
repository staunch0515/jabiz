# Q87 商户的现金账户与学分的来源：路都修好，开关交给管理人员（计划，2026-11-03 第三稿，待需求方确认 §10）

需求方 2026-11-03 的要求（三次，以最后一次为准）：

1. **抽成功能保留、比例默认 0**。
2. 给商户建**真正的现金账户**收平台费，与学分分开；**预付与后付都支持，由运营商按商户决定**。
3. **学分有两种来源**：从现金账户换，和现在的直接支付变成学分；**两种都保留**。代码不决定什么可以、什么不可以，**都做出来，由管理人员用开关控制**。
4. 账户资金换学分的好处：没有支付通道的抽成损失，账面清晰。
5. 运营商的收费与分成**按 Q48 已定的来**（收费全部记在运营商名下，平台按比例分成），不另立规则。
6. 以后：答题既可以给答题者学分和某种认证，也可以要求答题者付学分、持有某种认证才能答；平台要有这些能力，何时开放由管理人员定。

编号 Q87 是建议。**这只是计划**：确认 §10 后开发，一个 PR，工作分支 `1.3/quizbuks-87-cash-account`。

## 0. 原则

- **能力与开关分开**：每条资金的路（直接充值成学分、充值进现金账户、现金换学分、预付扣费、后付账单）都做出来；哪条开、哪条关、默认走哪条，是**受控参数**（四眼修改，D40），不写死在代码里。
- **学分钱包只付奖励**；**现金账户**是商户在平台的钱，用途在换出或扣费时才定。两边分开记账，账面清晰（需求方第 4 条）。
- 运营商的收费与分成一切照 Q48。

## 1. 现状

- 抽成有两处、都已是参数：**结算抽成** `qb.creator-fee-rate`（受控，比例，结算每笔奖励时从奖励里扣，`RewardSplit`，分录 2110 → 2120 + 4110；代码已支持 0）；**转账手续费** `qb.transfer.fee`（受控，**每笔固定的 Kudos 数**，提现时扣，缺省已是 0）。
- 充值：Stripe Checkout（`QB_TOPUP_*`，Q6a）与银行转账登记（Q13a），到账直接成为学分（1110 / 1120 → 2110），收据单据 `qb.topup-receipt`；管理员手工加币（Q18b）。
- 学分钱包 `QbWallet(kind = SPONSOR)`：余额缓存 + 账本为真相、每日核对（F-06）。
- 运营商：商户归属运营商；抽成按运营商记账、月结算单、分成比例受控（Q48b）。
- 官方账号（Q69）出认证题也要给奖励（发布至少一档 > 0，D-Q69-6）。

## 2. 决定（草案）

| # | 决定 |
|---|---|
| D-Q87-0 | **抽成**：两处都保留、都默认 0：`qb.creator-fee-rate` 上线时以 `CONTROL_CHANGE` 设为 0（代码不改，补用例：0% 时答题者得全额、没有 4110 行、运营商结算为 0）；`qb.transfer.fee` 已是 0。另加 **提现按比例的抽成** `qb.transfer.fee-rate`（受控，缺省 0，与固定手续费相加，向下取整），作为"从答题者提现扣一定比例"的能力。 |
| D-Q87-1 | **现金账户**：每个商户一个（`QbWallet` 加种类 `CASH`，复用余额缓存与每日核对；账本新科目 **2160 商户现金账户**，负债，维度：商户）。 |
| D-Q87-2 | **充值两条路都保留**：充值时选目标 `QbTopUp.target`（`KUDOS` 直接成为学分，1110 / 1120 → 2110，即现状；`CASH` 进现金账户，1110 / 1120 → 2160）。受控参数 `qb.topup.targets`（可选的目标：缺省 `KUDOS,CASH` 两个都给）与 `qb.topup.default-target`（缺省 `KUDOS`，现状不变）。只给一个目标时页面不显示选择。银行转账登记同样带目标。 |
| D-Q87-3 | **现金换学分** `QB_KUDOS_BUY`（商户；1 : 1；取 `qb.wallet:SPONSOR:<商户>` 一把锁，现金与学分是同一把锁下的两个种类；2160 → 2110；可用现金 ≥ 金额）。受控参数 `qb.kudos.buy.enabled`（缺省开）。**学分换回现金** `QB_KUDOS_SELL`（2110 → 2160，只能换可用余额，不动待支付与冻结）也做出来，受控参数 `qb.kudos.sell.enabled`（**缺省关**）。 |
| D-Q87-4 | **平台费 = 费用记录 + 账单**：`QbFeeCharge`（只追加：商户、发布方那一对、`kind`、`period`、`amount`、`taxRate`、`tax`、来源单据、`invoiceId`），只经 `QB_FEE_CHARGE`（`subProcessOnly()`）写入；种类在代码中（`FeeKinds`）：第一步 `MONTHLY`（月费，受控参数 `qb.fee.monthly`，缺省 0 = 不收）与 `MANUAL`（总部手工记费，四眼）；以后的规则费、CPK、AI 超额只是加种类。 |
| D-Q87-5 | **预付 / 后付由运营商按商户定**：`QbSponsor` 加 `billingMode`（`PREPAID` / `POSTPAID`）与 `creditLimit`（后付的信用额，JPY），只经运营商（或总部）的流程 `QB_OPERATOR_BILLING_SET` 修改（范围核对、理由必填、审计）。**全平台的缺省**是受控参数 `qb.fee.default-billing-mode`（缺省 `PREPAID`）。<br>**预付**：记费时立即从现金账户扣（2160 → 4120 + 2150，同一事务）；可用现金不足即费用照记、形成未付账单，并且不能换学分、不能提交新发布，直到付清。<br>**后付**：记应收（1130 → 4120 + 2150），每月 2 日合成账单（`QbFeeInvoice`，适格请求书的版式，`invoiceNo` 由 `NumberSequence`，到期 `qb.fee.due-days` 天）；未付合计 > 信用额即不能换学分、不能提交新发布。预付的商户每月也出一张已付的账单作凭证。 |
| D-Q87-6 | **付账单**只从现金账户付（`QB_FEE_INVOICE_PAY`：2160 → 1130）；充值进现金即可付，不另做账单的在线支付。总部可作废（`QB_FEE_INVOICE_VOID`，四眼，冲正）。 |
| D-Q87-7 | **逾期**：每日任务 `qb.fee-overdue` 记 OVERDUE、提醒邮件（不可退订，7 天一封至多三封）、待办给运营商与总部财务；不自动停用商户；拦的只有换学分与提交新发布（422 `QB_FEE_OVERDUE`，页面写明并链到账单）。拦截本身也是受控参数 `qb.fee.overdue.block-submit`（缺省开）。 |
| D-Q87-8 | **账本与运营商**：科目 2160、1130 应收平台费（维度：商户）、4120 平台使用费收入、2150 应付消费税；税率受控 `qb.fee.tax-rate`（缺省 0.10）。每日核对加：2160 按商户 = 现金余额；1130 = 未付账单。**平台费记在商户所属运营商名下，并入 Q48b 的月结算与分成，规则照 Q48 不变**（结算单的"收费合计"从"抽成"扩成"抽成 + 平台费"）。 |
| D-Q87-9 | **官方账号的学分**：与普通商户同一条路（直接充值成学分、或充值进现金再换、或手工加币），不另设来源。 |
| D-Q87-10 | **界面**：商户后台"钱包"分三块——现金账户（余额、充值进现金、换学分、按开关显示换回、账单与支付、计费方式与信用额）、学分（现页；充值时按 `qb.topup.targets` 显示目标）、账单；概览加"现金余额""待付账单"。运营商后台：商户的计费方式与信用额。管理后台扩展：费用、账单、手工记费、作废、逾期待办；受控参数页已有，新参数按 `ControlledParams` 登记即出现。 |

## 3. 后端（`backend/quizbuks`，包 `fee` 与 `wallet`）

- 迁移（编号以合入时为准）：`qb_wallet` 的种类加 `CASH`；`qb_topup` 加 `target`（缺省 `KUDOS`，老数据即现状）；`qb_sponsor` 加 `billing_mode`、`credit_limit`；`qb_fee_charge`（只追加，`jabiz_protect_append_only`）；`qb_fee_invoice`（普通表）。科目参数 `qb.journal.account.2160 / 1130 / 4120 / 2150`（Q9d 的做法）。
- 钱包：`QbWallets` 加 `CASH` 与两方向的过账；`TopUpProcesses` 按 `target` 选对方科目（`TopUpChecks` 不变）；`QB_KUDOS_BUY` / `QB_KUDOS_SELL`；转账的 `qb.transfer.fee-rate`（`TransferFees` 纯 Java：固定 + 比例，向下取整）；每日核对的两项。`StatementBudgetIT`：到账（两种目标）、换学分各一个预算；答题路径不变。
- 费用：`QbFees`、`FeeKinds`、`FeeRules`（纯 Java：税、取整、预付 / 后付的 `canBuyKudos` / `canSubmit` 与原因）；流程 `QB_FEE_CHARGE`、`QB_FEE_CHARGE_MANUAL`、`QB_FEE_MONTHLY_ITEM`（逐项任务 `qb.fee-monthly`）、`QB_FEE_INVOICE_ISSUE_ITEM`（逐项任务 `qb.fee-invoice`：`AssignNumber` + `DOCUMENT_ISSUE` + `SendMail`）、`QB_FEE_INVOICE_PAY`、`QB_FEE_INVOICE_VOID`、`QB_FEE_OVERDUE_ITEM`（逐项任务 `qb.fee-overdue`）；`PublicationRules` 一条检查。运营商结算（`OperatorSettlements`）的收费合计加平台费。
- 运营商：`QB_OPERATOR_BILLING_SET`（`RequireScope`），字典 `billing-mode`。
- 单据：`DocumentLayout` `qb.fee-invoice`（开具方 `qb.issuer.*`、登録番号、按税率小计、消费税、合计、计费方式），文案三语；保留期同收据。
- 模板：`qb.sponsor.cash`、`qb.sponsor.fee-charges`、`qb.sponsor.fee-invoices`、`qb.operator.sponsor-billing`、`qb.admin.fee-*`；`qb.sponsor.wallet` 加现金余额与待付账单；`qb.sponsor.topup-settings`（页面读 `qb.topup.targets` 等开关）。
- 受控参数（`ControlledParams`）：`qb.topup.targets`、`qb.topup.default-target`、`qb.kudos.buy.enabled`、`qb.kudos.sell.enabled`、`qb.transfer.fee-rate`、`qb.fee.monthly`、`qb.fee.tax-rate`、`qb.fee.default-billing-mode`、`qb.fee.overdue.block-submit`；普通参数 `qb.fee.due-days`。

## 4. 前端

- 商户后台：钱包页三块（`sponsor/src/wallet/`：`CashAccount.tsx`、`BuyKudos.tsx`、`Invoices.tsx`），充值对话框的目标选择（按开关），概览两张卡，向导与提交处的提示。
- 运营商后台扩展：商户的计费方式与信用额（理由必填）。
- 管理后台扩展：`FeesPage.tsx`。
- 邮件模板三语：账单已出、逾期提醒。

## 5. 测试

- `RewardSplitTest` 与 `AttemptIT` 的 0% 用例；`TransferFeesTest` 与 `TransferIT`（固定 + 比例）；`FeeRulesTest`、`FeeKindsTest`；`CashAccountIT`（两种目标的充值、换学分、换回按开关、锁与余额缓存、核对）；`FeeIT`（预付：记费即扣、不足时拦；后付：账单、信用额、从现金付、作废冲正、逾期与提交被拒、拦截开关；运营商改计费方式只能在范围内；运营商结算含平台费）；`JournalExportIT` 的新科目；`StatementBudgetIT`。
- 场景 `cash.yml`；`setup.yml` 快照随新科目与参数。
- 前端 Vitest 与 e2e `cash-account.spec.ts`。

## 6. 文档

`02-design.md` §3.1、§3.4、§4.1 / §4.2、§5.2、§7.2 / §7.3；`backend/quizbuks/CLAUDE.md` 第 4 节"钱包"改写（两种充值目标、学分的两种来源、费用只经 `QB_FEE_CHARGE`、开关都是受控参数）；`04-product-direction.md` §6.10；`operations.md`（抽成 0%、各开关的含义与缺省、登録番号与税率、计费方式）；`launch-checklist.md`；`03-plan.md`；本文件 §11。

## 7. 答题的能力（需求方第 6 条，另立计划，本计划只定原则与落点）

| 能力 | 现状 | 要做的 | 开关 |
|---|---|---|---|
| 答题给学分 | 有 | — | — |
| 答题给认证 | 只有官方账号的发布能登记为轮（Q69） | 放宽为任何商户的发布可登记为**该商户自己的认证**的轮（商户自己的认证目录，D-Q69-10），发布可以"只给认证、不给学分"（放宽 D-Q69-6） | `qb.certification.sponsor-rounds.enabled`（缺省关） |
| 答题要先持有认证 | 无 | Q86 的"持有认证"作前置 | 随 Q86 |
| 答题要付学分 | 无 | 发布的"报名费"（Kudos）：开始答题时从答题者学分钱包扣到商户的**现金账户**（2120 → 2160），不足即不能开始；奖励照旧 | `qb.play.entry-fee.enabled`（缺省关） |

这四项与 Q86 一起构成"树上的每个节点可以要什么、给什么"。付学分与商户认证的实施计划在 Q86、Q87 之后出（建议编号 Q88）。

## 8. 估计

| 部分 | 天 |
|---|---|
| 抽成的用例、`qb.transfer.fee-rate`、运营步骤 | 1 |
| 现金账户：钱包种类、充值目标、换学分 / 换回、核对、预算 | 2.5 |
| 费用：实体、迁移、科目、规则、记费与账单任务、预付 / 后付、单据、逾期、运营商结算 | 3.5 |
| 运营商的计费方式 | 0.5 |
| 商户后台、运营商后台、管理后台 | 2.5 |
| 测试（IT、场景、e2e）与文档 | 1.5 |
| **合计** | **11.5** |

## 9. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 中 | 开关多，组合多 | 开关都在 `ControlledParams` 一处，缺省 = 现状（直接充值成学分、预付、不换回、月费 0）；`FeeRules` / `TransferFees` 纯 Java 给出每个判断与原因，IT 按组合覆盖 |
| 中 | 适格请求书的格式、税率、取整 | 都是参数与版式，`qb.fee.monthly` 缺省 0，上线不出任何账单 |
| 低 | 逾期拦提交误伤 | 只拦新提交与换学分、开关可关、页面写明、总部可作废 |

## 10. 需要需求方确认的

1. D-Q87-0：结算抽成 `qb.creator-fee-rate` 设为 0 保留功能；转账固定手续费已是 0；新加提现按比例的抽成参数，缺省 0。
2. D-Q87-2：充值两条路都保留，目标与缺省由开关定，缺省仍是"直接成为学分"（现状不变）。
3. D-Q87-3：现金换学分缺省开；学分换回现金做出来、缺省关。
4. D-Q87-5：预付 / 后付与信用额由运营商按商户设，全平台缺省预付（受控参数）。
5. D-Q87-6：账单只从现金账户付。
6. D-Q87-8：平台费记在运营商名下、并入 Q48b 的结算与分成，规则不变。
7. §7：答题给认证、答题要付学分两项另立计划（Q88），本计划只留落点（商户认证的轮、报名费进现金账户）。
8. 编号 Q87，一个 PR，约 11.5 天。建议顺序：Q86 → Q87 → Q88。

## 11. 实施记录（2026-11-03，分支 `1.3/quizbuks-87-cash-account`，一个 PR）

按 §2 的决定实现；与 §3 / §4 写法不同的地方：

| 计划 | 实施 | 原因 |
|---|---|---|
| `QbFeeCharge` 带发布方那一对与 `invoiceId` | `QbFeeCharge` 记 `sponsorId` + `operatorId`（记费时的归属），没有 `invoiceId` / 交易号；账单 `QbFeeInvoice.chargeId` 指向记费（预付每条一张），分录按来源 `QbFeeCharge` 找（`qb.system.fee-invoice-transactions`） | 平台费是商户账户自己的记录（CLAUDE.md"商家账户自己的记录保持 `sponsorId`"）；只追加表上不回写账单号，作废由冲正表达 |
| 预付商户每月出一张已付账单作凭证 | 预付：**每条记费一张账单**，现金够即 `PAID`、不够即 `OPEN`；后付：每月一张 | 预付不够时账单就是未付的凭证，与"每月一张"合不到一起；手工记费也自然各有一张 |
| `qb.fee-invoice` 任务 `AssignNumber` + `DOCUMENT_ISSUE` + `SendMail` | 任务只出账单行并发布 `qb.fee.invoice-issued`；邮件由消费者 `QB_MAIL_FEE_INVOICE`（`internal()`）发；PDF 由商户按需签发一次（`QB_FEE_INVOICE_DOCUMENT`，账单号在记费 / 合成时取） | 系统身份不能签发平台单据（版式的权限），邮件只由事件的消费者发（现有约定） |
| `QB_FEE_CHARGE_MANUAL`、`QB_FEE_INVOICE_VOID` 两个四眼流程 | 一个 `QB_FEE_REQUEST`（`kind = MANUAL / VOID`）+ `QbFeeRequest` + 审批结果 `QB_FEE_REQUEST_APPROVAL_RESULT`（照手工加币） | 审批绑定的内容要有一行可读回（Q18b 的做法），两种申请一张表 |
| `FeeKinds` 类 | 种类是 `FeeRules` 的常量 | 两个种类不值一个类 |
| 422 `QB_FEE_OVERDUE` | 422 `QB_FEE_UNPAID`（预付有未付、后付超信用额都用它） | 拦的条件不只是逾期 |
| 模板 `qb.sponsor.cash`、`qb.operator.sponsor-billing` | `qb.sponsor.wallet` 加现金与账单的列；`qb.operator.sponsors` 加 `billingMode` / `creditLimit`；另有 `qb.sponsor.topup-settings`、`qb.sponsor.kudos-exchanges`、`qb.sponsor.fee-charges` / `fee-invoices` / `fee-invoice-lines`、`qb.admin.fee-charges` / `fee-invoices` / `fee-requests` | 少两次读取 |
| `TransferFees` | `TransferRules.fee(amount, fixed, rate)`（`Params` 多一个 `feeRate`） | 已有 `TransferRules` |
| 科目参数 | `qb.journal.account.2160 / 1130 / 4120 / 2150` 四个受控参数（`QB_SETUP` 提出），场景的提案因此 86 个 | Q9d 的做法 |
| 迁移 | `V66__quizbuks_cash_account.sql`（Q86 用 V65） | 合入顺序 |

- 提交发布多一条语句（`FeeGate`：入驻行 + 未付账单，`QueryEntities.all`）；答题路径不变（`AttemptStatementBudgetIT` 不变）。
- 月报 `qb.admin.monthly` 多五类：`KUDOS_BOUGHT`、`KUDOS_SOLD`、`FEE_CHARGED`、`FEE_PAID`、`FEE_VOIDED`（`AdminFinanceIT` 逐行）。
- 任务：`qb.fee-monthly`（每月 2 日 01:07 日本时间）、`qb.fee-invoice`（01:17）、`qb.fee-overdue`（每日 02:37）。
- 未做：换学分与进现金到账的 `StatementBudgetIT` 预算（§3）；账单的在线支付（D-Q87-6 本来就不做）。
