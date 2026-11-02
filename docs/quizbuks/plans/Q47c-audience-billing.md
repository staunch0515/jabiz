# Q47c 客户匹配的按条件计费（计划，2026-11-02，待需求方确认 §9）

`plans/Q47-audience.md` 的第三期（D-Q47-7："规则由发布使用；费用 = 该发布所用各计费项的日价之和 × 有效期天数（按天折算）；从商户 Kudos 钱包扣、记账并出收据；地区为免费的基础项；日价为受控的业务参数（四眼）；收费开关缺省关闭"）。
Q47a 已在每个条件定义上带计费键（`audience.gender` / `audience.age` / `audience.occupation` / `audience.interest`），发布的条件与期间在提交时固定，费用可以精确计算。
**这只是计划**：确认 §9 后开发，一个 PR，工作分支 `1.3/quizbuks-47c-audience-billing`，迁移 **V64**（Q83 若在其后则顺延为 V65）。

## 0. 为什么

- 平台的技术含量体现在匹配规则上（Q47 的出发点）：商户越精准地选人，花的钱越值，平台按所用的规则收费，与"答题的抽成"并列成为第二种收入。
- Q47a 起商户已经可以免费使用条件；开关缺省关闭，开发后照常免费，定好价格、需求方决定开收时以四眼打开。

## 1. 现状

- 发布的条件 `QbPublicationCriterion` 只经 `QB_PUBLICATION_AUDIENCE_SAVE` 在草稿时整体替换，提交即冻结；复制随发布复制；`INVITED` 与渠道（Shopify）的发布没有条件。
- 提交发布（`PublicationLifecycle.submit`）在发布锁内检查、再经平台审批（自动或人工）；**目前提交不动钱**：平台 Kudos 的奖励在答题完成时从钱包付（`QbWallets.pays`：无待支付且可用余额足够；待支付按顺序补发、不插队）。
- 钱包只在钱包锁 `qb.wallet:SPONSOR:<商家>` 内改（`QbWallets.change`），与同一事务中的 `LEDGER_POST` 一起；锁序 `qb.account` → `qb.quiz` → `qb.pub` → `qb.pub-start` → `jabiz.approval:*` → `qb.wallet:*`，提交时在发布锁、审批锁之后取钱包锁合乎锁序。
- 科目：2110 商家预付款（钱包）、4110 服务收入（答题抽成，运营商的分成按 4110 的发布维度算，Q48b）。月报 `qb.admin.monthly` 只在来源、科目与方向全部符合时归类，新的过账要加分类（`AdminFinanceIT`）；分录导出要每个科目一个映射参数（`JournalMappingCheck`）。
- Q28：不允许平台 Kudos（`PLATFORM`）的商户在后台看不到钱包与充值。

## 2. 决定（草案）

| # | 决定 |
|---|---|
| D-Q47c-1 | **价格**：每个计费键一个受控参数 `qb.audience.price.<键>`（每天的 Kudos，整数，可为 0），地区永远免费。费用 = Σ（发布所用条件的日价）× 天数；**天数** = ⌈（结束 − 开始）/ 24 小时⌉，至少 1。同一条件选了几个选项仍按一个条件计（价格按"用了这种规则"，不按选项数）。 |
| D-Q47c-2 | **开关**：受控参数 `qb.audience.billing.enabled`（缺省 false）。关着时一切照旧免费；打开后只对**此后提交**的发布收费，已提交的不补收。 |
| D-Q47c-3 | **何时扣**：**提交时**扣（发布锁 → 审批锁 → 钱包锁内，与审批请求同一事务），写一条扣费记录并过账（借 2110 / 贷 4120）。钱包付不出（`QbWallets.pays` 为假：余额不足或有待支付的奖励）即提交被拒 422 `QB_AUDIENCE_FEE_FUNDS`（带费用与可用余额），页面引导充值。费用为 0 时不写记录、不过账。 |
| D-Q47c-4 | **退款**：审核驳回、商户撤回审核回到草稿、审核过期撤回（`ADVANCE`）时**全额退回**（`LEDGER_REVERSE` + 钱包加回，记录变为 `REFUNDED`）。**上线之后不退**（商户提前结束、预算用完、名额用完都不退）；**管理员或运营商下架**：同样不退（违规下架）。复制发布不带扣费，复制出的发布提交时另算。 |
| D-Q47c-5 | **新科目 4120"匹配服务收入"**（不并入 4110）：月报分类 `AUDIENCE_FEE` / `AUDIENCE_FEE_REFUND`，分录导出的映射参数 `qb.journal.account.4120`（受控，首个值为空，与其他映射一样须设好才能导出）。**运营商不分成**（运营商结算只看 4110，D-Q48b 不变）。 |
| D-Q47c-6 | **不另开收据**：充值时已开收据（收款凭证）；规则费用是预付 Kudos 的消费，在发布页（明细：条件、日价、天数、合计、状态）、钱包的流水与交易导出中可见。D-Q47-7 的"出收据"改为此（需求方确认）。 |
| D-Q47c-7 | **不用平台 Kudos 的商户**（Q28）：开关打开、且所选条件的日价大于 0 时，需要 Kudos 钱包：后台在这种情况下照样显示钱包与充值（只为付规则费用；奖励方式不变）。开关关着时与现在相同。 |
| D-Q47c-8 | **显示**：向导"客户匹配"一步在每种条件旁写日价（开关打开时），并给出按当前期间估算的费用；检查一步与提交前的"预计最大支出"（`maxSpend`）加上规则费用；发布页显示扣费明细与退款。答题者一侧不变。 |

## 3. 后端（`backend/quizbuks`）

### 3.1 迁移 V64 `quizbuks_audience_billing`

- `qb_audience_charge`（普通表）：`charge_id`、`publication_id`（唯一，引用 `entity_registry`）、`publisher_kind` / `publisher_id`、`days`、`amount`（JPY 小数 0）、`items`（jsonb：键、日价）、`status`（`CHARGED` / `REFUNDED`）、`charged_at`、`refunded_at`、`ledger_transaction_id`、`refund_transaction_id`、`version`；检查约束（`REFUNDED` 必有退款的时间与交易）。
- 新科目 4120 由 `QB_SETUP` 建（`QbLedger`），不在迁移中。

### 3.2 改动

- `audience`：`AudienceBilling`（纯 Java：天数、按计费键的日价合计、明细；价格缺少或不合格即视为不可用 `QB_AUDIENCE_FEE_UNAVAILABLE`，开关打开时才查）；`QbAudienceCharges`（实体、默认数据视图 `processOnlyWrites`，读权限财务与内容管理员，经 `OperatorScopes`）。
- `publishing`：提交（`PublicationLifecycle.submit`）在审批步骤之后取钱包锁、读钱包、按冻结的条件与期间算费用、扣款、过账（来源 `QbAudienceCharge`）；`PublicationReadiness` 的 `maxSpend` 加上费用；退款在驳回（审批结果）、撤回（`QB_PUBLICATION_WITHDRAW`）与审核过期（`ADVANCE`）三处以同一个子流程 `QB_AUDIENCE_FEE_REFUND`（`subProcessOnly`，在各自已持的锁之后取钱包锁）。
- `wallet`：`QbLedger` 加 4120；`QbWallets.audienceFeePosting`；月报分类与 `AdminFinanceIT`；`JournalMappingCheck` 认 4120。
- `setup`：`QbParams` 加 `qb.audience.billing.enabled` 与四个 `qb.audience.price.<键>`（都受控，`CONTROLLED`），`qb.journal.account.4120`。
- 模板：`qb.sponsor.audience-prices`（开关与各条件的日价，供向导）；`qb.sponsor.publications` / 发布详情给出扣费（金额、天数、状态）；`qb.sponsor.reward-methods` 给出"需要钱包"（D-Q47c-7）；后台 `qb.admin.audience-charges`（财务）。
- 文案三语：错误码、科目名、参数名、月报分类。

## 4. 前端

- **商户后台**：向导"客户匹配"一步的日价与估算、检查一步的费用与可用余额（不足时链到充值）；发布页的扣费明细与退款；钱包的显示条件（D-Q47c-7）；提交被拒 `QB_AUDIENCE_FEE_FUNDS` 时的提示。
- **后台扩展**：财务页加"规则费用"列表（发布、商家、金额、天数、状态、过账）；发布审核页显示将收的费用。
- **App**：不变。

## 5. 测试

- core：`AudienceBillingTest`（天数的取整与下限、按条件不按选项、0 价、价格缺少、开关关着）。
- IT：`AudienceBillingIT`（开关关着不收；打开后提交扣款并过账 2110 / 4120、钱包与账本一致；余额不足或有待支付时提交被拒且什么也没写；驳回、撤回、审核过期全额退回；上线后结束不退；复制的发布另算；`INVITED` 与渠道的发布没有费用；运营商看不到别的运营商的扣费）；`AdminFinanceIT` 的月报分类；`JournalMappingCheck`；`SetupIT` 参数与科目；对账（`ReconcileIT` 的商家钱包与 2110 一致）。场景快照随新参数与科目后移（答案按新位置改写，得分不变）。
- 前端：向导的价格与估算、检查一步的余额不足、发布页明细；axe。
- e2e：打开开关（e2e 的受控参数发布）→ 商家选条件、看到费用、提交扣款 → 驳回后退回。

## 6. 文档

- `plans/Q47-audience.md` 的 D-Q47-7 注明"收据"改为明细（若确认）；`02-design.md` 账本一节（4120）、`backend/quizbuks/CLAUDE.md` 客户匹配一条、`operations.md`（参数、科目映射、月报分类）、`03-plan.md`；本文件 §10 实施记录。
- **价格与税**：日价由需求方定；消费税的处理（Kudos 预付的消费是否另计税、收入科目的税区分）由财务确认后再打开开关。

## 7. 估计

| 部分 | 天 |
|---|---|
| 迁移、实体、计费规则、参数、科目 | 0.75 |
| 提交扣款、三处退款、月报与分录导出、对账 | 1.5 |
| 模板与文案 | 0.5 |
| 商户后台、后台扩展 | 1 |
| 测试、e2e、文档 | 0.75 |
| **合计** | **4.5** |

## 8. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 中 | 提交多了一把钱包锁，与答题付奖争用 | 提交不在答题路径上；钱包锁只在扣款的几条语句内持有；`AttemptStatementBudgetIT` 不变 |
| 中 | 退款遗漏某条回到草稿或不上线的路径 | 三处退款共用一个子流程；IT 逐条覆盖；扣费记录的状态与发布状态由对账模板核对（已扣、发布却仍是草稿或已驳回即差异） |
| 低 | 价格未定就打开开关 | 开关与价格都是受控参数（四眼）；价格缺少即提交被拒而不是免费 |
| 低 | 消费税 | 开关打开前由财务确认 |

## 9. 需要需求方确认的

1. D-Q47c-1：按"用了哪种规则"计价（不按选项数）；天数向上取整到整天。各条件的日价是多少（可先不定，开关关着）？
2. D-Q47c-3：**提交时扣款**，余额不足即不能提交。
3. D-Q47c-4：驳回、撤回、审核过期全额退回；**上线之后一律不退**（含提前结束与被下架）。
4. D-Q47c-5：新科目 4120，**运营商不分成**。
5. D-Q47c-6：不另开收据，以发布页明细与钱包流水代替（D-Q47-7 的"出收据"随之修改）。
6. D-Q47c-7：开关打开后，不用平台 Kudos 的商户也显示钱包，以付规则费用。
7. 编号 Q47c，一个 PR，约 4.5 天，迁移 V64。
