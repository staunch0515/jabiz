# F-M9 发起方的 AI 额度与 AI 费：实施计划（S2，2026-11-12，草稿，待总控批准）

规格 `docs/quizbuks/first-launch-spec.md` F-M9（规则、参数、运营闭合、验收）；上下文是 F-M1、F-M5、F-M6 已合入的"账户 + 预付款 + 记费"，以及 F-C7（官网生成）、F-C10（帮助 AI）。格式照 `F-M.md` §8、§9。分支 `1.3/quizbuks-FM9-ai-credit`；建议分两个 PR：**a 后端**（数据、计价、预留、购买 / 退回 / 月度记费 / 到期、核对与月报），**b 页面**（发起方 AI 额度页与各 AI 面板、总部 AI 页、帮助、`operations.md`）。迁移号：开 PR 时取当时的下一个空号（`main` 现在最大 V118；**F-M7 的分支已占 V119，未合并**，见 §8）。

## 1 现状与范围

**现状（读代码得到的事实）**
- 五种外部作业：`qb.ai.questions`（题目，种类 `QUESTIONS`）、`qb.ai.translate`（`TRANSLATION`、`PUBLICATION_TEXTS`）、`qb.ai.site-profile`（官网生成，`SITE_PROFILE`）、`qb.ai.check`（审核人对照翻译，`TRANSLATION_CHECK`，**审核人的、计在平台名下**，`AiProcesses.checkRequest`，不是发起方的）、`qb.ai.cover`（Q98a 撤销，只用来让旧作业结束）。帮助 AI（`qb.ai.help`，`help.HelpJob`）用量独立（`QbHelpUsage`），不经 `QbAiJob`。
- 用量：作业 `QbAiJob`（`estimatedTokens`、`inputTokens`、`outputTokens`、`costUsd`）与按月的 `QbAiUsage`（发起方 × 月、全体一行 `*`），只由 `QB_AI_JOB_COMPLETED` / `FAILED` / `QB_AI_JOB_CANCELLED`（两种作业的 `onCancelled`）在 `qb.ai:<发起方>` → `qb.ai:*` 锁内累加。限额 `AiLimits`（同时 2 个、每日次数、每月 Token、全体每月 Token）已有。
- `costUsd` 来自 `AiCost`（各模型标价、回退模型、长提示价），**只是估算，不向任何人收费**（Q8 A-8）。没有记下用量的尝试按预留计入（D52 第 5 条，`AiProcesses.charged`），有意偏高。
- 开关：`qb.ai.enabled`（作业 `enabledBy`）；`qb.ai.fee.enabled` 只在 `FirstLaunchSwitches` 里是 `WAITING("F-M9")`，键还不存在；`qb.ai.site-profile.enabled` 键已存在（受控、缺省关），在 `KEY_BEFORE_MEANING` 中，等本项接上收费。
- 钱的底座已就绪：`QbPrepayments.AI` 是合法用途，`Prepayments.FreezeInput` 允许用途 `AI`、发布为空，`QbPrepayment` 注释写明"AI 额度，F-M9"；`FeeAllocation`（赠送先、现金后）、`Prepayments.held`（按笔、赠送最早到期先）、`QB_PREPAYMENT_USE`、2180 与维度 `purpose` 都有。缺：费用种类 `AI`（`FeeRules.KINDS`、`ChargeInput.kind` 的正则、`qb_fee_charge_version` 的 CHECK）、额度的购买 / 退回 / 到期、请求时的预留、月度记费。
- 页面：发起方 `/money/ai`（`AI_CREDIT_PATH`，`wallet/WalletPage.tsx` 的 `AiCreditPage`）现在只显示 Token 用量（`ai/AiUsageMetric.tsx`）；总部 `/qb/ai`（`AiPage.tsx`，`qb.admin.ai-usage`、`qb.admin.ai-jobs`）。

**本项做**：额度的购买 / 退回 / 赠送部分到期；请求时按最高费用预留、作业结束时按实际结算；每月一次按（发起方，作业发生时的运营商）记 `AI` 费；开关、参数、核对、月报、页面、帮助、`operations.md`。AI 出题、翻译、官网生成三类（发起方的作业）收费。

**不做**：运营商的分成怎样算（F-M12，本项只保证费用带正确的运营商）；账单 PDF 的分列（F-M13 余下）；封禁、注销时对额度的处理（F-M15；本项提供可被调用的退回 / 作废子流程）；按模型不同的倍数、套餐价、运营商定价（规格"不做"；AI 费不设运营商价目表，倍数与汇率是总部的受控参数）；审核人的对照检查与帮助 AI 一律不收（**F-M10 的边界**：测试断言它们不改发起方的额度，帮助的用量与阈值告警仍是 F-M10）。

## 2 数据（一个迁移）

- **`qb_ai_job` 加列**（`QbAi.JOB_ENTITY`，都 `processOnly`）：
  - `feeMode`（`NONE` / `CREDIT`，字典 `urn:jabiz:dict:quizbuks:ai-job-fee-mode`；旧行 `NONE`）：请求时开关是否开着，**冻结在作业上**，之后开关怎么变都不影响这个作业；
  - `feeOperatorId`：请求时发起方所属的运营商（F-M11 的"作业发生时"，取作业**发生**时即请求时）；
  - `feeMarkup`、`feeUsdJpy`：请求时的倍数与汇率（冻结，供解释与核对）；
  - `reservedYen`（含税，作业结束清零）、`billableUsd`（只含记下了用量的部分）、`unrecordedUsd`（没记下用量的尝试的估计，**不向发起方收**，见问题 2）、`feeYen`（税前费用，结束时写）、`absorbedYen`（超出可扣额而由平台承担的部分）、`feeChargeId`、`feePeriod`（作业结束所在月）；
  - 部分索引 `(sponsor_id) WHERE fee_mode = 'CREDIT' AND fee_charge_id IS NULL`：预留中与未记费的作业，请求时的读取与月度取项都靠它。
- **`qb_prepayment`**：部分唯一索引 `(sponsor_id) WHERE purpose = 'AI' AND status = 'OPEN'`——**每个发起方一笔长期的 AI 额度**（购买是往这一笔上加，赠送优先与到期顺序因此跨购买一致）。对用途 `AI`，`amount` 记**累计购入**，`QbPrepayments` 的 `amount` 字段不再 `immutable`（其他用途照旧只写一次；核对与规则不读 `amount`，实现时逐处确认）。额度为 0 时仍是 `OPEN`，不关闭（注销由 F-M15 关闭）。
- **`QbAiCreditMovement`**（新，时态 `t.writeOnce()`，迁移照时态实体的约定建索引、外键、`jabiz_protect_append_only` 与只含主键的唯一索引）：发起方、种类 `BUY` / `RETURN` / `CHARGE` / `EXPIRE`、金额（进为正、出为负）、其中赠送与现金、变动后的额度、运营商（`CHARGE`）、引用（费用编号 / 赠送编号 / 购买的幂等键）、时刻。它是页面"购买、退回、用量明细"与核对的依据；封存列只增不改（`SealedColumnsHistory`）。
- 费用种类 `AI`：`FeeRules.KINDS`、`FeeProcesses.ChargeInput.kind` 的正则（`SettleInput.kind` 不加：AI 费从不后付）、CHECK、字典、月报。
- 参数（§5）加进 `QbParams.ALL` / `CONTROLLED`；`FirstLaunchSwitches`：`qb.ai.fee.enabled` 与 `qb.ai.site-profile.enabled` 两行由 `WAITING` 改 `ACTIVE`，后者从 `KEY_BEFORE_MEANING` 去掉。
- `PersonalDataEraser`：`QbAiCreditMovement` 登记（随 F-M15 收尾，`ErasureIT` 的全表搜索覆盖）。
- 无新科目：用 2180（用途 `AI`）、2190、2160、1130、4120、2150、5140；分录映射 `qb.journal.account.*` 已有，不加键。

## 3 规则（纯 Java：新 `AiFeeRules` / `AiCreditRules`）

- **费用**：`feeYen = ⌈billableUsd × feeMarkup × feeUsdJpy⌉`（`BigDecimal`，`UP` 到 0 位；有用量就至少 1 日元，没有用量为 0）；税 = 每次记费的税前合计 × `qb.fee.tax-rate` 向下取整（`FeeRules.tax`，同其他平台费）；额度的金额含税。官网生成另加网页搜索：`AiCost` 加一项 `searches × 搜索单价`（提供方标价，与 token 价放在一处）；**官网生成现在只读指定网站、不用网页搜索（`ClaudeWebResearch` 的 `searches = 0`），所以这一项目前为 0**，结构先备好，以后作业用到搜索即自动计入。
- **请求时的最高费用**（预留）：与现有 Token 预估同一依据——每次尝试的预留输入 + 所要的全部输出（`AiProcesses.unrecordedAttempt` 已按作业种类算出），乘尝试数（`AiQuestionsJob.ATTEMPTS`，官网生成 2），按所请求模型标价（长提示价、搜索费都含）算成美元，再 × 倍数 × 汇率，加税。新增 `AiCost.maxOf(...)`，请求时算、结束时不再算。
- **可用额** = 额度剩余 − 此刻已过期而未作废的赠送中**不被占用**的部分 − 未记费的费用（含税）− 预留中的最高费用。已用掉但还没记费的赠送，到期日也不作废（见 §4.5），所以可用额只是保守，不会因此少给发起方。
- **请求**：开关开、种类为发起方的三类 → 可用额 ≥ 最高费用才通过，否则 422 `QB_AI_CREDIT_SHORT`（`AiCodes` 新，带 `needed`、`available`、`minPurchase`，页面据此给"去购买"）。开关关 → `feeMode = NONE`，不预留不收费，与现在完全相同。
- **作业结束**（完成、失败、取消三个结局流程，同一处结算函数）：
  - 费用只按**记下了用量**的尝试（`billableUsd`）；没有运行过的取消 = 0；失败有用量照收、没有用量为 0；
  - 超出"此作业的预留 + 其余可用额"的部分，只扣到额度为 0，其余记 `absorbedYen`（平台承担），打日志与指标（`quizbuks.ai.fee.absorbed`，**不带发起方标签**，D16）；
  - 释放预留、写 `feeYen`，全部在 `qb.ai:<发起方>` → `qb.ai:*` 锁内，**只改 `QbAiJob`，不取钱包锁**；`QbAiUsage` 与 Token 限额照旧。
- 审核人的 `TRANSLATION_CHECK`、旧的 `COVER`：`feeMode` 恒为 `NONE`。

## 4 流程

1. **请求**（改 `AiProcesses.requestProcess` / `limited` / `record`、`SiteProfileProcesses`、`SiteQuizProcesses`）：在已有的"Load where the sponsor stands"那一条 `QueryEntities.all` 里并入三项读取（发起方的 AI 额度行、其预留 / 未记费的作业、额度中赠送各笔的到期、所属运营商），**不多发语句**；`AiCreditRules` 判断；`record` 写作业时带 `feeMode`、`reservedYen`、运营商、倍数、汇率。锁序不变：`qb.quiz:<模版>` → `qb.ai:<发起方>` → `qb.ai:*`。
   - 报价（页面用）：只读流程 `QB_AI_FEE_QUOTE`（新，同一段 `prepared` Java，不取锁、不写入，同 `QB_PUBLICATION_FUNDS_CHECK` 的做法）。
2. **购买 `QB_AI_CREDIT_BUY`**（新，`qb.ai.use` + 已验证邮箱 + 未封禁；开关关时 422 `QB_AI_FEE_OFF`；带幂等键）：只取 `qb.wallet:SPONSOR:<发起方>`。金额 ≥ `qb.ai.credit.min-purchase`（未设不限）。账户可用额（赠送未到期 + 现金，含运营商转入的现金）足够才做，按 `FeeAllocation`（赠送先、现金后）取，往唯一的 AI 额度行加；一次 `LEDGER_POST`：借 2190 / 2160、贷 2180（发起方、用途 `AI`），写 `QbFeePayment`（对象为该预付款）与 `QbAiCreditMovement(BUY)`。**这不是费用**，没有收入、没有税。
3. **每月记费**：任务 `qb.ai-fee-monthly`（新，`JobDefinition.forEach`、逐项、`CRON_TZ`，每月 1 日 00:27，cron 写进 `FeeConfig` 的 `quizbuks.ai-fee.monthly-cron`；可经 `JOB_RUN_REQUEST` 补跑），取项模板 `qb.system.ai-fee-due`：有"已结束、`CREDIT`、`feeYen > 0`、`feeChargeId` 为空、结束早于本月初"的作业的发起方。每项流程 `QB_AI_FEE_SETTLE`（`internal()`）：锁 `qb.ai:<发起方>` → `qb.wallet:SPONSOR:<发起方>`；一条读取；按（运营商，作业结束所在月）分组，每组一次 `QB_FEE_CHARGE`（种类 `AI`、`operatorId` = 作业上冻结的、`prepaymentId` = AI 额度行、`sourceKey` = `ai:<发起方>:<月>:<运营商>`）——它自己完成"记应收 → 从预付款付（赠送先）→ 不够再从账户付 → 开已付的账单"；回写作业的 `feeChargeId`，写 `QbAiCreditMovement(CHARGE)`。AI 额度的预付款方式是 `PREPAID`，所以不论发起方是预付还是后付的计费方式，这笔费用当即付清，**不进后付月账单**，账单的先后（F-M6 §9.6）因此不受影响。再运行一次是空操作（作业已有 `feeChargeId`）。
4. **退回 `QB_AI_CREDIT_RETURN`**（新，本人；可输入金额或"全部可退"）：锁 `qb.ai:<发起方>` → `qb.wallet:SPONSOR:<发起方>`。最多退"可用额"（预留与未记费的不能退）。退的顺序：现金先、再赠送（到期最晚的先）；赠送退回**原笔**（原笔已到期或作废则当即作废：借 2180 / 贷 5140）；借 2180、贷 2160 / 2190，负的 `QbFeePayment`，`QbAiCreditMovement(RETURN)`。开关关闭时仍可退。供 F-M15 注销时调用同一段 Java。
5. **赠送部分到期**：每日任务 `qb.ai-credit-expire`（新，逐项，每日 03:47，`quizbuks.ai-credit-expire.cron`），取项模板 `qb.system.ai-credit-expire-due`；项流程 `QB_AI_CREDIT_EXPIRE_ITEM`（`internal()`）锁序同退回。作废 = 到期的赠送笔在额度中的剩余，**减去被"未记费的费用 + 预留"占住的部分**（这部分按月度记费时先用赠送的顺序，已经算是用掉的，月度记费会照常用它，记费后下一次再作废剩余）；借 2180 / 贷 5140（同到期作废的科目与运营商：取赠送发放时的运营商），累加那一笔赠送的 `voidedAmount`，`QbAiCreditMovement(EXPIRE)`。现金部分永不过期。
6. **开关关闭时**：不能买（`QB_AI_FEE_OFF`）、可以退；新请求免费（现状）；已冻结 `CREDIT` 的作业照常结算，月度任务与到期任务照常运行。**不要用关这个开关来止损**（会让 AI 对所有人免费）：事故时关 `qb.ai.enabled` 或撤提供方密钥（`operations.md` §7.8）。官网生成需要收费才能用：`qb.ai.fee.enabled` 关着而 `qb.ai.site-profile.enabled` 开着时请求被拒（`QB_AI_UNAVAILABLE`），启动检查对这个组合给警告（问题 6）。
7. **并发与锁**：请求与三个结局流程只取 AI 的两把锁（不变）；购买只取钱包锁；退回、到期、月度记费取 `qb.ai:<发起方>` → `qb.wallet:SPONSOR:<发起方>`（**新增这一条锁序**，写进 `backend/quizbuks/CLAUDE.md` 与 `operations.md`）。现有路径里没有"先钱包后 AI"的（实现时再对封禁、注销、`qb.account` 的流程逐个核对）。

## 5 账本科目与分录

| 事件 | 分录（`LEDGER_POST`，引用） | 月报类 |
|---|---|---|
| 购买 | 借 2190（赠送部分）/ 2160（现金部分），贷 2180（发起方、用途 `AI`）；`prepay_ai`，来源 `QbPrepayment` | `AI_CREDIT_BOUGHT`（新） |
| 记费 | 借 1130，贷 4120 + 2150；`fee_ai_<月>`，来源 `QbFeeCharge` | `AI_CHARGED`（新，同 `DISTRIBUTION_CHARGED` 的做法按引用前缀区分） |
| 从额度付 | 借 2180，贷 1130（`QB_PREPAYMENT_USE`，赠送先） | `AI_CREDIT_USED`（新，按用途引用与 `PREPAYMENT_USED` 分开） |
| 退回 | 借 2180，贷 2160 / 2190；已到期的赠送部分借 2180、贷 5140 | `AI_CREDIT_RETURNED`（新） |
| 赠送到期 | 借 2180，贷 5140 | `AI_CREDIT_VOIDED`（新） |

均不落 `OTHER`；`AdminFinanceIT` 逐行加一例（全程：买 → 请求 → 记费 → 退回 → 到期）。F-M12 之前，AI 费照现有月结算的基数规则分给运营商（规格 F-M4），不在本项改。

## 6 参数（`QbParams`，缺省由 `QB_SETUP` 提出；钱与开关列入 `CONTROLLED`）

| 键 | 类型 / 缺省 / 范围 | 受控 |
|---|---|---|
| `qb.ai.fee.enabled` | 布尔，false（`FirstLaunchSwitches` 首次上线值 true） | 是 |
| `qb.ai.fee.markup` | 数，1.5，1 至 10，两位小数 | 是 |
| `qb.ai.fee.usd-jpy` | 数，150，50 至 500，两位小数 | 是 |
| `qb.ai.credit.min-purchase` | 日元整数，**未设置即不限**（同 `qb.fee-credit.manual.max`，不由 `QB_SETUP` 建立），设时 100 至 1 000 000 | 是 |
| `qb.ai.credit.low-amount` | 日元整数，500，0 至 100 000（0 = 不提示） | 否 |
| `qb.ai.fee.unbilled-after-hours` | 整数，30，1 至 720（核对：月度记费晚于此仍有未记费） | 是 |
| `qb.ai.fee.absorbed-alert-yen` | 日元整数，1 000，0 至 10 000 000（一个月内平台承担的合计超过即给财务待办） | 否 |

沿用：`qb.fee.tax-rate`、`qb.ai.price.*`（不认识的模型）、`qb.ai.sponsor.*`、`qb.ai.global.monthly-tokens`、`qb.ai.enabled`。倍数、汇率的改动只影响此后的请求（冻结在作业上）。

## 7 页面与运营闭合

- **发起方**（b）：
  - `/money/ai` `AiCreditPage`：额度合计（赠送 / 现金，每笔赠送的到期日）、可用额、本月未记费金额、"购买"（金额、最低购买额、预览"先用赠送，余下用现金"）、"退回"、用量明细（模板 `qb.sponsor.ai-credit` 新：作业的日期、种类、费用 / "预留中"、状态；变动记录来自 `QbAiCreditMovement`）。
  - 钱包页 `FeeAccount` 的预付款块把 AI 额度分开显示（`qb.sponsor.wallet` 的 `prepaymentHeld` 排除用途 `AI`，另加 `aiCreditHeld`）。
  - 三个 AI 面板（题目、翻译、官网生成）：显示可用额、"本次最多约 ¥X"（`QB_AI_FEE_QUOTE`）与"购买"；额度不足的 422 直接给入口。首页"待我处理"加"AI 额度不足"（可用额低于 `qb.ai.credit.low-amount` 且近 30 天用过 AI 时）。
  - 费用不显示美元，只显示日元；帮助 `docs/quizbuks/help/ai-credit.md`（新，三语）写清"按大模型收费的 150%、汇率、向上取整、赠送先用、赠送到期、可退回"，并更新 `manual-sponsor.*` 与官网生成的帮助 `site-profile.md`。
- **运营商管理系统**：发起方详情加 AI 额度余额与本月 AI 费（只读，限自己的发起方）；AI 费出现在现有的费用列表（种类 `AI`）。
- **平台管理系统**（`/qb/ai`）：`qb.admin.ai-jobs` 加费用列（美元成本、日元费用、平台承担、运营商）；新模板 `qb.admin.ai-fees`（按月、发起方、运营商：成本美元、收费日元、承担、未记费、额度余额；可导出）；倍数 / 汇率 / 最低购买额 / 开关的受控变更入口沿用参数页。
- **任务与自动收尾**：`qb.ai-fee-monthly`、`qb.ai-credit-expire`（均可补跑）；预留随作业结束释放；到期提醒复用 F-M2 的赠送到期提醒（赠送还在 AI 额度中时也提醒，实现时确认 `qb.fee-credit-remind` 的取项含这部分）。
- **告警**：平台承担的合计超过阈值、核对差异均给总部财务待办；每月把 Anthropic 控制台的账单与总部页的"成本美元合计"对照（`operations.md`）。
- **`operations.md`** 新小节（编号取当时最大的小节号 + 1；F-M7 也会加一节，排在它后面）：开关与参数、价格 / 汇率的受控变更、两个任务的时刻与补跑、锁序、核对项的处理、与提供方账单对照、"不要用关收费开关止损"、关开关后的行为、官网生成的前提。

## 8 对账、月报与依赖

- **核对**（`qb.reconcile`，`recon-fee-account.sql` 加段，`ReconChecks` 加名）：
  - `AI_CREDIT`：按发起方，2180 用途 `AI` = 该发起方 AI 预付款剩余 = `QbAiCreditMovement` 之和（规格：余额 = 购买 − 已记费 − 退回 − 作废）；`PREPAYMENT_PARTS` 对这一笔同样适用；
  - `AI_FEE_MONTH`：按发起方与月，作业 `feeYen` 之和 = 该月 `AI` 费用的税前金额（月初之后 `unbilled-after-hours` 才比较）；
  - `AI_FEE_UNBILLED`：超时仍有未记费的作业；`AI_FEE_RESERVED`：已结束的作业仍有预留；`AI_CREDIT_EXPIRED_HELD`：到期超过一天的赠送仍在额度中且没被占用；
  - 提示（不算差异）`AI_FEE_ABSORBED`：平台承担的作业，供总部查看；`FEE_CREDIT_PROMOTION` 的 5140 比较要包含 AI 额度中的作废（实现时确认它按来源筛选的方式）。
- **月报**：§5 的五类；`FEE_CHARGED` 下 `AI` 种类分列。
- **依赖 F-M7（未合并，分支 `origin/1.3/quizbuks-FM7-audience`）**——逻辑上不依赖它的贷项凭证，但会改同一批文件，须在它合入后拉取并合并：
  1. 迁移号：它用 V119；本项取"开 PR 时的下一个空号"（预计 V120 或更大）。
  2. `FeeProcesses` / `QbPrepayments`（它给预付款加 `refundDecidedAt`、`refundOutcome`）/ `FeeConfig` / `QbParams` / `FirstLaunchSwitches` / `FeeRequests`：同文件的并行修改，冲突在合并时解决。
  3. `wallet.sql`（它加 `creditNoteCredit`，改"未付"的算法）、`recon-fee-account.sql`、`monthly.sql`、`recon-fee-receivable.sql`：本项在其上追加。
  4. 贷项凭证可以对任意费用开手工凭证：对种类 `AI` 的费用，按 F-M7 的规则退到账户（不是 AI 额度）。**本项不依赖它，但加一个测试确认不出错**（待它合入后补，列为 b 的测试）。
  5. 前端 `sponsor/src/wallet/*`（`FeeAccount.tsx`、`api.ts`、`messages.ts`）与 `admin-extension`（`fee-api.ts`、`fee-messages.ts`）同样被它改动。
  6. `operations.md`（F-M7 新加的小节）、`F-M.md` §10、`first-launch-progress.md`、`backend/quizbuks/CLAUDE.md`：文档合并。
- 其他已合并的依赖：F-M1（`FeeAllocation`、`QbFeePayment`）、F-M5（`Prepayments`、2180）、F-M11（`QbFeeCharge.operatorId` 由调用方给）、F-C7（官网生成作业）、F-C10（帮助独立）；**官网生成的收费依赖本项**（`qb.ai.site-profile.enabled` 由此改 `ACTIVE`）。F-M12（分成基数）、F-M13（账单分列）、F-M15（封禁 / 注销）在本项之后。

## 9 测试清单

- 单元：`AiFeeRulesTest`（向上取整、很小的用量为 1 日元、无用量为 0、倍数汇率、税向下取整、预留含税、可用额含"已过期被占用的赠送"、超出可扣额时的承担）；`AiCostTest` 加 `maxOf`（尝试数、长提示价、搜索）。
- `AiCreditIT`（新）：开关关 = 完全免费、语句数与现在相同；无额度被拒 `QB_AI_CREDIT_SHORT`；购买（赠送先、现金后、最低购买额、封禁 / 未验证邮箱被拒、幂等键重放）；请求预留、完成结算、释放；失败有用量收、无用量不收；没有运行过的取消不收，运行中取消按记下的用量收；没记下用量的尝试不向发起方收但进 `costUsd` 与 Token 用量；实际超过预留时扣到 0、承担与告警；官网生成收费、审核人对照检查不收、帮助 AI 不动额度；两个请求同时只有一个通过（栅栏）；月度记费按（运营商，月）分组、赠送先用、再运行不重复；月中改派运营商后旧作业仍归请求时的运营商；退回（现金先，原笔已到期则作废）；到期不作废已用未记费的赠送、记费后下一次作废剩余；开着请求 → 关开关 → 仍结算；锁序（AI 锁内不取钱包锁，钱包锁内不取 AI 锁）。
- `AiCreditStatementBudgetIT`（新，`SqlStatementLog`）：开关开 / 关两种请求的语句数相同（读取并入同一条）；写入只多作业列。**AI 的现有预算测试不变**。
- 只追加：`QbAiCreditMovement` 与 `qb_fee_charge_version` 没有 UPDATE / DELETE；`SealedColumnsHistory`。
- 核对与月报：`ReconChecksTest` 每个新项各一例；`AdminFinanceIT` 五类逐行；`FirstLaunchSwitchesCheckTest` / `FirstLaunchSetupIT` 两行 `ACTIVE`；`SetupParamsCheck`；`MessagesTest` 三语；`ProcessAccessIT` 新流程的权限与 `internal()`；ArchUnit。
- 场景：`ai-credit.yml`（买 → 请求 → 完成 → 月度记费 → 退回，固定时钟，`completeExternalJob` / `runJob`）及快照。
- 前端 Vitest：`AiCreditPage`、购买 / 退回对话框、面板的额度提示与"不足"入口、总部 AI 页新列；axe 过（亮暗两种外观）。
- e2e：`ai-credit.spec.ts`（新，用 `tools/quizbuks/claude-stub`）：买额度 → 生成题目 → 看到费用 → 月度补跑 → 余额；更新 `ai.spec.ts` 在开关开着时先买额度。

## 10 风险

- **提供方账单与我们的收费对不上**：我们的 `costUsd` 是标价估算（偏高，不含缓存折扣）；重试可能被提供方重复计费、没有回答的尝试平台不向发起方收。缓解：每月对照控制台账单与总部页合计，提供方设支出上限，承担部分告警。
- **重复收费**：同一作业只在结局流程里结算一次（`finishedAt` 已有即跳过），月度记费以 `feeChargeId` 为准，再运行为空操作；测试覆盖。
- **预留与可用额的一致性**：额度的退回 / 到期 / 记费都在 AI 锁之内改，请求读取与它们互斥；新增锁序（AI → 钱包）若与未来流程相反会死锁，因此写进 `CLAUDE.md` 并加测试。
- **到期保护的复杂度**：已用未记费的赠送要在到期日留到月度记费，规则与测试较多，是本项最容易出错的一处。
- **预留偏大**：按全部尝试的最高用量预留，可用额会暂时被占住；释放在作业结束时，作业卡住时靠 `external-job` 的租约与超时收尾。
- 官网生成目前没有搜索费用，若以后启用搜索而 `AiCost` 的搜索项没有跟上，会少收（承担）；测试保证搜索指标计入。
- 与 F-M7 的并行修改（见 §8）。

## 11 要问需求方的问题（每条带建议）

1. **税**：倍数后的 AI 费要不要另加消费税？**建议：要**，与其他平台费一样（税前费用 = ⌈成本 × 1.5 × 汇率⌉，另加税），额度的金额含税。
2. **实例崩溃、超时、没有回答的尝试**（平台不知道提供方有没有收钱）：要不要按估计收给发起方？**建议：不收**，只按记下的实际用量收，估计的部分平台承担并在总部页可见。
3. **入驻前的"从官网生成"**：规格里一处说不设免费特例，另一处（未决 2）问要不要小额启动额度。**建议：不设特例**，发起方入驻赠送的金额由财务设得够做一次生成，赠送额度可以用来买 AI 额度。
4. **"校对"**：我理解为审核人对翻译的对照检查，由平台承担、不从发起方额度扣（仍计入 Token 限额）。**建议：确认**。
5. **退回额度**：可输入金额、先退现金再退赠送，还是只能整笔退？**建议：可输入金额，现金先退**。
6. **关着 `qb.ai.fee.enabled` 时的官网生成**：**建议：不允许**（它是新功能，不应由平台免费承担），并在两个开关不一致时启动警告。
7. **后付计费方式的发起方**（有信用额）也必须先买 AI 额度吗？**建议：是**（AI 费难预测，规格也写"先购买"）。
8. **运营商分成**：AI 费的 150% 里 100% 是平台的成本，若按现有方案把整笔分给运营商，毛利可能为负。**建议：请财务确认比例；本项先按规格让 AI 费照现有方案分成**，需要时在 F-M12 把成本部分扣出分成基数。
9. **最低购买额**：规格是"未设置即不限"。**建议：上线前设为 1 000 日元**，避免大量小额购买记录。
10. **提示阈值**：额度低于 500 日元且近 30 天用过 AI 时提示"AI 额度不足"；平台承担合计超过 1 000 日元（每月）给财务待办。**建议：先用这两个值**，上线后调。
11. **超出预留的承担**：回退到更贵的模型时，实际可能超过按所选模型预留的最高费用，超出部分由平台承担。**建议：接受**。
12. **赠送部分的到期**：已经用掉、还没到月度记费的赠送，到期日不作废。**建议：这样做**（否则发起方用了也被收现金）。

删除的功能：无
