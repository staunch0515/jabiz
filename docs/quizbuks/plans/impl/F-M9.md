# F-M9 发起方的 AI 额度与 AI 费：实施计划（S2，2026-11-12，草稿，已按审查与总控的决定修订（§12），待总控批准）

状态：已批准实施（总控 2026-11-13）。

规格 `docs/quizbuks/first-launch-spec.md` F-M9（规则、参数、运营闭合、验收）；上下文是 F-M1、F-M5、F-M6 已合入的"账户 + 预付款 + 记费"，以及 F-C7（官网生成）、F-C10（帮助 AI）。格式照 `F-M.md` §8、§9。分支 `1.3/quizbuks-FM9-ai-credit`；分三个 PR：**a-1 后端**（数据、计价、请求时的预留与作业结束时的结算；`qb.ai.fee.enabled` 仍关着），**a-2 后端**（购买、退回、到期、月度记费、核对与月报、运营商分成的 AI 种类），**b 页面**（发起方 AI 额度页与各 AI 面板、总部 AI 页、帮助、`operations.md`）。迁移号 **V125**（总控 2026-11-13；V121–V124 已由 F-M8、F-M12a、F-M12b 使用），只在 a-1，开 PR 前再与总控确认。

## 1 现状与范围

**现状（读代码得到的事实）**
- 五种外部作业：`qb.ai.questions`（题目，种类 `QUESTIONS`）、`qb.ai.translate`（`TRANSLATION`、`PUBLICATION_TEXTS`）、`qb.ai.site-profile`（官网生成，`SITE_PROFILE`）、`qb.ai.translation-check`（审核人对照翻译，`TRANSLATION_CHECK`，**审核人的、计在平台名下**，`AiProcesses.checkRequest`，不是发起方的）、`qb.ai.cover`（Q98a 撤销，只用来让旧作业结束）。**五种都声明了 `onCancelled`**，都指向 `QB_AI_JOB_CANCELLED`（`AiQuestionsJob.java:151`、`AiTranslateJob.java:102`、`SiteProfileJob.java:153`、`AiCheckJob.java:79`、`AiCoverJob.java:44`）。帮助 AI（`qb.ai.help`，`help.HelpJob`）用量独立（`QbHelpUsage`），不经 `QbAiJob`。
- 发起方自己取消（`QB_AI_CANCEL`，`AiProcesses.java:1436`，只能取消排队中的作业）当即把作业标为已取消并取消平台的作业，但**用量要等 `QB_AI_JOB_CANCELLED` 才结算**；运行中的作业在当前尝试结束后才收尾。所以"已结束"以结局流程写下的 `finishedAt` 为准，不以 `status`、`closedAt` 为准。
- 用量：作业 `QbAiJob`（`estimatedTokens`、`inputTokens`、`outputTokens`、`costUsd`）与按月的 `QbAiUsage`（发起方 × 月、全体一行 `*`），只由 `QB_AI_JOB_COMPLETED` / `FAILED` / `QB_AI_JOB_CANCELLED`（五种作业的 `onCancelled`）在 `qb.ai:<发起方>` → `qb.ai:*` 锁内累加。限额 `AiLimits`（同时 2 个、每日次数、每月 Token、全体每月 Token）已有。
- **"没有记下用量"现在有两种估计，混在同一组输入 / 输出指标里**：
  1. 调用超时或没有回答：处理器自己按 `ClaudeApi.unanswered` 记（`ClaudeCalls.java:84`、`ClaudeWebResearch.java:151`），记进与真实用量相同的键，分不开；
  2. 实例死掉（尝试没有回报）：平台给出 `unrecordedAttempts`（D52 第 5 条），由 `AiProcesses.charged`（`AiProcesses.java:1110`）按 `unrecordedAttempt`（`:1130`）补一份估计。
  两者现在都进 `costUsd` 与 Token 限额（有意偏高，D52 第 1 条）。
- `costUsd` 来自 `AiCost`（各模型标价、回退模型、长提示价），**只是估算，不向任何人收费**（Q8 A-8）。**它只按 Token 算，不含网页搜索**：`AiCost.of`（`AiProcesses.java:948` 等调用处）没有搜索项；`ClaudeWebResearch.SEARCH_PRICE_USD`（`:45`）与 `searchCost`（`:129`）现在只有 `ProspectProcesses.java:1184` 用，官网生成的处理器只把搜索次数记成指标 `SEARCHES`（`:269`），所以给 `AiCost` 加搜索项不会算两次（见 §3）。
- 开关：`qb.ai.enabled`（作业 `enabledBy`）；`qb.ai.fee.enabled` 只在 `FirstLaunchSwitches` 里是 `WAITING("F-M9")`，键还不存在；`qb.ai.site-profile.enabled` 键已存在（受控、缺省关），在 `KEY_BEFORE_MEANING` 中，等本项接上收费。
- 钱的底座已就绪：`QbPrepayments.AI` 是合法用途，`Prepayments.FreezeInput` 允许用途 `AI`、发布为空，`QbPrepayment` 注释写明"AI 额度，F-M9"；`FeeAllocation`（赠送先、现金后）、`Prepayments.held`（按笔、赠送最早到期先）、`QB_PREPAYMENT_USE`、2180 与维度 `purpose` 都有。缺：费用种类 `AI`（`FeeRules.KINDS`、`ChargeInput.kind` 的正则、`qb_fee_charge_version` 的 CHECK）、额度的购买 / 退回 / 到期、请求时的预留、月度记费。
- 运营商分成的基数（F-M12a #687 起）：每笔费用在结算中写一行 `CHARGE`，种类在受控清单 `qb.operator.share.kinds` 中的才进基数（取 `amount − shareExcluded`），不在的 `excluded = amount`；清单缺省**不含 `AI`**。`FeeProcesses.ChargeInput` 末尾的 `shareExcluded`（缺省 0，夹在 [0, amount]，存 `qb_fee_charge_version.share_excluded`）由记费的调用方给出（F-M12 计划 §2、§3.2 的 `AI` 一行）。原来的 `operator-fees.sql` 已由 #687 删除。
- 页面：发起方 `/money/ai`（`AI_CREDIT_PATH`，`wallet/WalletPage.tsx` 的 `AiCreditPage`）现在只显示 Token 用量（`ai/AiUsageMetric.tsx`）；总部 `/qb/ai`（`AiPage.tsx`，`qb.admin.ai-usage`、`qb.admin.ai-jobs`）。

**本项做**：额度的购买 / 退回 / 赠送部分到期；请求时按最高费用预留、作业结束时按实际结算；每月按（发起方，作业发生时的运营商）记 `AI` 费；**给 AI 费填 `shareExcluded`（成本部分）并补 AI 种类的运营商结算 IT**（AI 是否进分成基数由 F-M12 的清单决定，缺省不进，见 §3）；开关、参数、核对、月报、页面、帮助、`operations.md`、`launch-checklist.md` 一项。AI 出题、翻译、官网生成三类（发起方的作业）收费。

**不做**：运营商的分成怎样算（F-M12，本项只保证费用带正确的运营商，并先不把 AI 费分出去）；账单 PDF 的分列（F-M13 余下）；封禁、注销时对额度的处理（F-M15；本项提供可被调用的退回 / 作废子流程）；按模型不同的倍数、套餐价、运营商定价（规格"不做"；AI 费不设运营商价目表，倍数与汇率是总部的受控参数）；审核人的对照检查与帮助 AI 一律不收（**F-M10 的边界**：测试断言它们不改发起方的额度，帮助的用量与阈值告警仍是 F-M10）。

## 2 数据（一个迁移）

- **`qb_ai_job` 加列**（`QbAi.JOB_ENTITY`，都 `processOnly`）：
  - `feeMode`（`NONE` / `CREDIT`，字典 `urn:jabiz:dict:quizbuks:ai-job-fee-mode`；旧行 `NONE`）：请求时开关是否开着，**冻结在作业上**，之后开关怎么变都不影响这个作业；
  - `feeOperatorId`：请求时发起方所属的运营商（F-M11 的"作业发生时"，取作业**发生**时即请求时）。**可以为空**：入驻前（官网生成）请求时发起方还没有运营商，空就是"无运营商"，记费时用明确的写法传下去（M1，见 §4 第 3 条）；
  - `feeMarkup`、`feeUsdJpy`：请求时的倍数与汇率（冻结，供解释与核对）；
  - `reservedYen`（含税，作业结束清零）、`billableUsd`（**只含"有回答"的调用的实际用量**）、`unrecordedUsd`（两种估计的合计：处理器的估计（调用没有回答）与平台的估计（实例死掉），**不向发起方收**，见 §3 与问题 2；恒有 `costUsd = billableUsd + unrecordedUsd`）、`feeYen`（税前费用，结束时写；因额度不够被减少时见 §3）、`absorbedYen`（税前，因额度不够而由平台承担的部分）、`feeChargeId`、`feePeriod`（**结局流程那一刻所在的月**，用 `FeeConfig` 的时区，即 `jabiz.fiscal-year-zone`，缺省 UTC；`finishedAt` 本来就在结局流程里写，二者同一刻）；
  - 部分索引 `(sponsor_id) WHERE fee_mode = 'CREDIT' AND fee_charge_id IS NULL`：预留中与未记费的作业，请求时的读取与月度取项都靠它。
- **处理器的估计另记一组指标**（H1）：调用没有回答时，`ClaudeCalls.java:84`、`ClaudeWebResearch.java:151` 不再记进真实用量的键，而是记进并行的一组 `estimatedInputTokens`、`estimatedOutputTokens`（长提示的输入 / 输出各一，形状同 `AiQuestionsJob` 现有的那几个键）。`AiProcesses.usage(metrics)` 因此只含有回答的用量；`charged` 把三者相加得到 Token 与 `costUsd`（**Token 限额与 `QbAiUsage` 的口径不变**），另算只含有回答的 `billableUsd`，`unrecordedUsd = costUsd − billableUsd`。本项合入时 `qb.ai.fee.enabled` 为 false，打开收费开关在发布完成之后，不会有旧实例把估计记进旧键后被当作真实用量收费。
- **`qb_prepayment`：每个发起方一笔长期的 AI 额度，`amount` 保持不变**（H4 与取舍一，总控决定）：
  - 部分唯一索引 `(sponsor_id) WHERE purpose = 'AI' AND status = 'OPEN'`。购买是往这一笔上加（`remaining` 增加，赠送先用与到期顺序因此跨购买一致）；额度为 0 时仍是 `OPEN`，不关闭（注销由 F-M15 关闭）。
  - **不解除 `amount` 的 `immutable`**（字段级解除会让配送、曝光、受众这几种预付款也失去保护）。`amount` 记开立时的首笔购买额并保持不变，字段注释写明"AI 用途不读它"；**累计购入 = `QbAiCreditMovement` 中 `BUY` 的求和**。
  - **AI 额度的 `held` 改为按笔汇总读取**：读取用一个新的 SQL 模板（`qb.system.ai-credit-held`），按"原笔"（赠送笔 `grantId` 或现金笔 `cashLotId`）对该预付款的分摊做 `GROUP BY`，净额 = 它自己的分摊（正为冻结、负为退回）减去费用取走的正分摊，同 `Prepayments.held` 的算法，`HAVING` 净额 > 0；`QB_PREPAYMENT_USE` 对 AI 额度用同一读取（`UseInput` 加一个由 AI 调用方给出的标志，其余用途照旧）。这样读取的行数是仍有余额的笔数，不随分摊行数增长；仍有余额的笔数超过 `MAX_PARTS` 时照旧抛错。原因：现在 `QB_PREPAYMENT_USE` 只读前 1001 行分摊（`Prepayments.java:286`、`:289`，`MAX_PARTS = 1000` 在 `:73`），`planRelease` 超过 1000 行即抛错（`:537`）；AI 这一笔永不关闭，每次购买、月度记费、退回、到期都加行，常买的发起方两三年后会失败或算错。
  - **逐处确认清单**（实现时逐条核对并在 PR 描述里回答）：
    1. 读 `QbPrepayment.amount` 的只有 `qb/sponsor/publication-prepayments.sql`（按发布筛选，AI 额度没有发布，不会出现）；
    2. 核对（`PREPAYMENTS`、`PREPAYMENT_PARTS`、`DISTRIBUTION_DEPOSITS`）、`wallet.sql`、`monthly.sql`、`PrepaymentRules`、`held`、释放都只读 `remaining` 与分摊，不读 `amount`；F-M7 读的是费用与凭证的 `amount`；
    3. `PREPAYMENT_STALE` 与 `prepayments-due` 要求发布编号非空，AI 这一笔不会被它们拿走；`ReleaseInput.publicationId` 必填，退回必须另写（§4 第 4 条）；
    4. 后付信用额只算 `POSTPAID`，AI 额度恒为 `PREPAID`；`QB_FEE_CHARGE` 带预付款时不要求入驻通过；
    5. `SponsorErasure` 现在不处理 `QbPrepayment`：**F-M15 必须关闭这笔 `OPEN` 的预付款**（写进 §8 依赖）。
- **`QbAiCreditMovement`**（新，时态 `t.writeOnce()`，迁移照时态实体的约定建索引、外键、`jabiz_protect_append_only` 与只含主键的唯一索引）：发起方、种类 `BUY` / `RETURN` / `CHARGE` / `EXPIRE`、金额（进为正、出为负）、其中赠送与现金、变动后的额度、运营商（`CHARGE`）、引用（费用编号 / 赠送编号 / 购买的幂等键）、时刻。它是页面"购买、退回、用量明细"、累计购入与核对的依据；封存列只增不改（`SealedColumnsHistory`）。**数据视图声明 `processOnlyWrites()`**（只能经流程写入）；运营商页新增的视图与模板声明 `withinEntryScope`（D64，§7）。
- 费用种类 `AI`：`FeeRules.KINDS`、`FeeProcesses.ChargeInput.kind` 的正则（`SettleInput.kind` 不加：AI 费从不后付）、CHECK、字典、月报。
- 参数（§6）加进 `QbParams.ALL` / `CONTROLLED`；`FirstLaunchSwitches`：`qb.ai.fee.enabled` 与 `qb.ai.site-profile.enabled` 两行由 `WAITING` 改 `ACTIVE`，后者从 `KEY_BEFORE_MEANING` 去掉。
- `PersonalDataEraser`：`QbAiCreditMovement` **不登记**：它只有发起方编号与金额，没有个人字段，`ErasedEntity` 不接受空的字段列表（`ErasedEntity.java:60-63`），也不需要（F-M15 审查，总控 2026-11-13）。关闭 AI 预付款的部分随 F-M15。
- 无新科目：用 2180（用途 `AI`）、2190、2160、1130、4120、2150、5140；分录映射 `qb.journal.account.*` 已有，不加键。分录平衡；平台承担的部分不入账（不收就没有收入与应收）。

## 3 规则（纯 Java：新 `AiFeeRules` / `AiCreditRules`）

- **三种情况，分别记、分别决定**（H1，总控决定）：

  | 情况 | 记在哪里 | 向发起方收费 |
  |---|---|---|
  | 有回答的调用 | 真实用量指标 → `billableUsd` | 按实际用量收 |
  | 调用没有回答（超时等），处理器的估计 | 并行的 `estimated…` 指标 → `unrecordedUsd` | 不收，平台承担 |
  | 实例死掉，平台的估计（`unrecordedAttempts`） | `charged` 补的估计 → `unrecordedUsd` | 不收，平台承担 |

  后两种仍计入 `costUsd` 与 Token 限额。滥用面（反复触发超时来白用）只靠每日次数（`qb.ai.sponsor.daily-jobs`）与每月 Token（`qb.ai.sponsor.monthly-tokens`、`qb.ai.global.monthly-tokens`）两道限额兜底，并计入告警（`absorbed-alert-yen` 把 `unrecordedUsd` 一并算上，§7）。**这是有意偏离 D52 第 1 条**：D52 讲的是平台自己记账时估计偏高，不是向客户收费；向客户只收有回答的实际用量（问题 2 请需求方确认）。**平台最多承担多少**：每个发起方每月不超过 `qb.ai.sponsor.monthly-tokens` 的 Token 价值——按缺省值（200 万 Token）和最贵的输出价（回退模型 25 美元 / 百万 Token）算最坏约 50 美元（汇率 150 约 7 500 日元）；全体合计不超过 `qb.ai.global.monthly-tokens`（缺省 5 000 万，最坏约 1 250 美元）。实际远低于此（超时的只是少数尝试）。
- **费用**：`feeYen = ⌈billableUsd × feeMarkup × feeUsdJpy⌉`（`BigDecimal`，`UP` 到 0 位；有用量就至少 1 日元，没有用量为 0）；税 = 每次记费的税前合计 × `qb.fee.tax-rate` 向下取整（`FeeRules.tax`，同其他平台费）；额度的金额含税。官网生成另加网页搜索：`AiCost` 加一项 `searches × 搜索单价`，**单价用已有的 `ClaudeWebResearch.SEARCH_PRICE_USD`（不另写一份）、次数取指标 `SEARCHES`**；目前 `costUsd` 不含搜索（见 §1），所以只加这一处就不会算两次。**官网生成现在只读指定网站、不用网页搜索（`searches = 0`），所以这一项目前为 0**，结构先备好，以后作业用到搜索即自动计入。价格不明（`AiCost` 对不认识的模型又没有设 `qb.ai.price.*` 时返回空）时，开关开着的请求被拒（`QB_AI_UNAVAILABLE`），启动检查对此给警告。
- **请求时的最高费用**（预留，M7）：与现有 Token 预估同一依据——每次尝试的预留输入 + 所要的全部输出（`AiProcesses.unrecordedAttempt` 已按作业种类算出），乘尝试数（`AiQuestionsJob.ATTEMPTS`=3，官网生成 2），**按所选模型与它的回退模型中较贵的标价算**（回退到更贵的模型是已知情况，如 Sonnet 回退到每百万 Token 5 / 25 美元；长提示价、搜索费都含），再 × 倍数 × 汇率，加税。新增 `AiCost.maxOf(...)`，请求时算、结束时不再算。预留因此一般不会不够；代价是回退价高于所选模型时，请求时占住的额度多于最终实际收的，作业结束即放回（问题 11）。
- **可用额** = 额度剩余 − 此刻已过期而未作废的赠送中**不被占用**的部分 − 未记费的费用（含税）− 预留中的最高费用。已用掉但还没记费的赠送，到期日也不作废（见 §4 第 5 条），所以可用额只是保守，不会因此少给发起方。
- **请求**：开关开、种类为发起方的三类 → 可用额 ≥ 最高费用才通过，否则 422 `QB_AI_CREDIT_SHORT`（`AiCodes` 新，带 `needed`、`available`、`minPurchase`，页面据此给"去购买"）。开关关 → `feeMode = NONE`，不预留不收费，与现在完全相同。**`QB_SITE_PROFILE_QUIZ`（M4）不是新的 AI 请求**：它用已读好的官网草稿直接写一个 `READY` 的 `QUESTIONS` 作业（`SiteQuizProcesses.java:195` 起，没有外部作业也没有结局流程），**恒为 `feeMode = NONE`、预留 0**，不动额度；测试断言。
- **作业结束**（完成、失败、取消三个结局流程，同一处结算函数）：
  - 费用只按**有回答的调用**的实际用量（`billableUsd`）；没有运行过的取消 = 0；失败的作业有回答的用量照收、没有则为 0（含平台自己因解析失败而重试的尝试，只要有回答的用量；问题 13）；
  - **额度够不够，按（运营商，`feePeriod`）累计算含税增量**（H2 (a)，同 `DistributionRules` 的累计取整）：同一组此前已结束、尚未记费的作业的税前合计为 S，本作业的税前费用为 x，含税增量 = (S+x) + ⌊(S+x)×税率⌋ − (S + ⌊S×税率⌋)。这样各作业的增量加起来恰好等于整组一次记费时的含税合计（向下取整的差异不会逐笔累积）；
  - 增量超过"此作业的预留 + 其余可用额"时，**由含税倒推税前**：取使含税增量不超过可扣额的最大税前金额 x'（`AiFeeRules` 里的纯函数，单元测试），`feeYen = x'`，`absorbedYen = x − x'`（税前）；打日志与指标（`quizbuks.ai.fee.absorbed`，**不带发起方标签**，D16）。`absorbedYen` 以这个含税增量算出的为准；
  - 释放预留、写 `feeYen` 与 `feePeriod`，全部在 `qb.ai:<发起方>` → `qb.ai:*` 锁内，**只改 `QbAiJob`，不取钱包锁**；`QbAiUsage` 与 Token 限额照旧。
- **迟到的用量**（M6）：作业结算后才到的实例用量（D52 第 5 条：迟到实例的用量由平台照记）**不回写 `QbAiJob`、`QbAiUsage`，不向发起方收**——作业的 `feeYen` 在结算时就定了。它只留在平台自己的尝试记录里；它在每月与提供方账单对照时表现为"账单大于总部页合计"的差额（`operations.md` 写明）。
- 审核人的 `TRANSLATION_CHECK`、旧的 `COVER`、帮助 AI、AI 获客（Q48d，运营商承担）：`feeMode` 恒为 `NONE`，在开关开着时也不动发起方的额度（测试）。**"帮助 AI 不动发起方额度"的断言由本项的实施 PR 加**（F-M10 审查 L7，总控 2026-11-13）；F-M10 只保留"不改发起方用量表"的回归测试。
- **运营商分成基数**（M2，L5；按 F-M12a #687 改写，总控 2026-11-13）：**AI 不进运营商的分成基数，直到 F-M12 问题 4 决定**。这由 F-M12 的清单 `qb.operator.share.kinds`（缺省不含 `AI`，结算对 AI 费写 `excluded = amount`）保证，本项不另加筛选。本项的月度记费给每笔 `AI` 费填 `shareExcluded` = 该组作业的 Σ⌈`billableUsd` × `feeUsdJpy`⌉（成本部分，按冻结在作业上的汇率，不乘倍数；不超过该笔税前金额；平台承担而减少的作业按减少后的比例），使清单加入 `AI` 后基数只取加价部分（F-M12 §3.2）。**合并条件**（总控）：本项补 AI 种类的运营商结算 IT——清单不含 `AI` 时 `excluded = amount`；含 `AI` 时基数为 `amount − shareExcluded`；`ai-cost-in-base` 打开时为全额。AI 费的结算就绪条件（该月没有未记费的 AI 作业）属于 F-M12b，谁后合并谁加。**打开 `qb.ai.fee.enabled` 的前提**写进 `launch-checklist.md`（"AI"一节新增一项：财务已确认 AI 费是否参与分成，即 F-M12 问题 4）。

## 4 流程

1. **请求**（改 `AiProcesses.requestProcess` / `limited` / `record`、`SiteProfileProcesses`；`SiteQuizProcesses` 只确认不收费）：在已有的"Load where the sponsor stands"那一条 `QueryEntities.all` 里并入三项读取（发起方的 AI 额度行、其预留 / 未记费的作业、额度中赠送各笔的到期、所属运营商），**不多发语句**；`AiCreditRules` 判断；`record` 写作业时带 `feeMode`、`reservedYen`、运营商、倍数、汇率。锁序不变：`qb.quiz:<模版>` → `qb.ai:<发起方>` → `qb.ai:*`。
   - 报价（页面用）：只读流程 `QB_AI_FEE_QUOTE`（新，同一段 `prepared` Java，不取锁、不写入，同 `QB_PUBLICATION_FUNDS_CHECK` 的做法；权限 `qb.ai.use`）。
2. **购买 `QB_AI_CREDIT_BUY`**（新，`qb.ai.use` + 已验证邮箱 + 未封禁；开关关时 422 `QB_AI_FEE_OFF`；带幂等键）：只取 `qb.wallet:SPONSOR:<发起方>`。金额 ≥ `qb.ai.credit.min-purchase`（未设不限）。账户可用额（赠送未到期 + 现金，含运营商转入的现金）足够才做，按 `FeeAllocation`（赠送先、现金后）取，往唯一的 AI 额度行加（首次购买时创建它）；一次 `LEDGER_POST`：借 2190 / 2160、贷 2180（发起方、用途 `AI`），写 `QbFeePayment`（对象为该预付款）与 `QbAiCreditMovement(BUY)`。**这不是费用**，没有收入、没有税。**入驻是否通过不影响购买**（问题 15：入驻未通过的发起方也能充值、购买；`QB_FEE_CHARGE` 带预付款时本来就不要求入驻通过）。
3. **每月记费**：任务 `qb.ai-fee-monthly`（新，`JobDefinition.forEach`、逐项、`CRON_TZ`，每月 1 日 00:27，cron 写进 `FeeConfig` 的 `quizbuks.ai-fee.monthly-cron`；可经 `JOB_RUN_REQUEST` 补跑），取项模板 `qb.system.ai-fee-due`：有"已结束（`finishedAt` 非空）、`CREDIT`、`feeYen > 0`、`feeChargeId` 为空、`feePeriod` 早于本月（`FeeConfig` 的时区）"的作业的发起方；**每项最多处理 200 个作业**（常量，对 `qb_ai_job` 的逐个回写设上限），作业多于一批的发起方由模板按批给出多项（每项取该发起方最早的 200 个未记费作业，前一项记完后后一项才取到下一批）。**`feePeriod` 是结局流程那一刻所在的月**（M5）：10 月 31 日结束的作业若 11 月 1 日 00:27 之后才跑结局，它属于 11 月、12 月 1 日记费——不会漏记，也不会被核对误报（核对与"晚于 N 小时"都以 `feePeriod` 为准，不以作业的实际结束时刻）。每项流程 `QB_AI_FEE_SETTLE`（`internal()`）：锁 `qb.ai:<发起方>` → `qb.wallet:SPONSOR:<发起方>`；一条读取；按（运营商，`feePeriod`）分组，每组一次 `QB_FEE_CHARGE`（种类 `AI`、**`period` = 该组的 `feePeriod`，即用量所在的月，也就是作业被结算（结局流程运行）的那个月，不是记费的月份**（M5）、`operatorId` = 作业上冻结的、`prepaymentId` = AI 额度行、`sourceKey` = `ai:<月>:<运营商>`，**发起方已在费用本身上，不放进键里，键最长不超过 100 字符**，`QbFees.SOURCE_LENGTH`；测试断言长度）——它自己完成"记应收 → 从预付款付（赠送先）→ 开已付的账单"；回写作业的 `feeChargeId`，写 `QbAiCreditMovement(CHARGE)`。
   - **绝不落到普通账户**（H2 (b)）：调用 `QB_FEE_CHARGE` 前再核"含税合计 ≤ 额度剩余"（税率在记费时才读 `qb.fee.tax-rate`，不在作业上冻结；`QB_FEE_CHARGE` 带预付款时，不够的部分会走"Pay the rest from the account"，`FeeProcesses.java:288`，所以必须在这里拦住）。超出时减少该组的税前金额（从该组最后结束的作业起，减 `feeYen`、加 `absorbedYen`），直到含税合计不超过额度剩余；减少的部分是平台承担，不入账。
   - **无运营商的作业**（M1）：`FeeProcesses.operatorOf`（`FeeProcesses.java:366`）在调用方给空时回退到发起方"此刻"的运营商，会把入驻前请求的作业记到后来的运营商名下。`ChargeInput` 加一个明确的"无运营商"写法（实现时定是常量还是单独的成分；要求：写法明确、不会被空值误触发、`operatorOf` 见到它返回空而不回退，其他费用种类的行为不变），月度记费对 `feeOperatorId` 为空的作业用它；测试：入驻前请求、之后分配运营商，费用仍记为无运营商。
   - AI 额度的预付款方式是 `PREPAID`，所以不论发起方是预付还是后付的计费方式，这笔费用当即付清，**不进后付月账单**，账单的先后（F-M6 §9.6）因此不受影响。再运行一次是空操作（作业已有 `feeChargeId`）。**同一个 `sourceKey` 可能有两笔费用**（作业多于一批、补跑、晚提交的结局）：费用表的 `sourceKey` 没有唯一约束，幂等只靠作业上的 `feeChargeId`。
   - 收入在次月 1 日才入账（4120 的过账日是记费日），月末与年末的截止因此差一个月，与配送费一致；写进给财务的清单（`operations.md` 新的一节）。
4. **退回 `QB_AI_CREDIT_RETURN`**（新，本人；可输入金额或"全部可退"；权限 `qb.ai.use`，**封禁中的发起方不能退**（与购买一致，总控 2026-11-13 同意）。**封禁不没收这笔钱**：封禁期间额度冻结（不能用、不能退），解封后照常；注销或抹除时剩余额度怎么处理由 F-M15 决定（问题 16），不要求已验证邮箱）：锁 `qb.ai:<发起方>` → `qb.wallet:SPONSOR:<发起方>`。最多退"可用额"（预留与未记费的不能退）。退的顺序：现金先、再赠送（到期最晚的先）；赠送退回**原笔**（原笔已到期或作废则当即作废：借 2180 / 贷 5140）；借 2180、贷 2160 / 2190，负的 `QbFeePayment`，`QbAiCreditMovement(RETURN)`。开关关闭时仍可退。供 F-M15 注销时调用同一段 Java。
5. **赠送部分到期**：每日任务 `qb.ai-credit-expire`（新，逐项，每日 03:47，`quizbuks.ai-credit-expire.cron`），取项模板 `qb.system.ai-credit-expire-due`；项流程 `QB_AI_CREDIT_EXPIRE_ITEM`（`internal()`）锁序同退回。作废 = 到期的赠送笔在额度中的剩余，**减去被"未记费的费用 + 预留"占住的部分**（这部分按月度记费时先用赠送的顺序，已经算是用掉的，月度记费会照常用它，记费后下一次再作废剩余）。
   - **到期同样写负的分摊**（H3）：`held` 与核对以"对象为预付款的分摊（正 = 冻结，负 = 退回）− 费用取走的"算余额，到期只写 `voidedAmount` 与 `QbAiCreditMovement` 的话，已作废的赠送仍被 `held` 当作在额度里，`PREPAYMENT_PARTS` 每天报差异。所以到期写一条**负的 `QbFeePayment`**（对象为该预付款，`grantId` 为原笔），并**直接复用 `Prepayments.giveBack` 里"退回原笔，已到期即再作废"的那一段**（`Prepayments.java:600` 起：更新赠送笔的 `voidedAmount` / `remaining` / `voidedAt`，处理"过期但每日任务还没作废"的剩余），不另写一份。
   - 分录一笔：借 2180、贷 5140，**来源写 `QbFeeCreditGrant` + 原笔编号**（`promotion_ledger` 只按这个来源取 5140），运营商取赠送发放时的；累加那一笔赠送的 `voidedAmount`，写 `QbAiCreditMovement(EXPIRE)`。现金部分永不过期。测试：到期后 `PREPAYMENT_PARTS` 对这一笔为 0 行差异；F-M2 的每日到期任务与本任务谁先跑都不会重复作废。
6. **开关关闭时**：不能买（`QB_AI_FEE_OFF`）、可以退；新请求免费（现状）；已冻结 `CREDIT` 的作业照常结算，月度任务与到期任务照常运行。**不要用关这个开关来止损**（会让 AI 对所有人免费）：事故时关 `qb.ai.enabled` 或撤提供方密钥（`operations.md` §7.8）。官网生成需要收费才能用：`qb.ai.fee.enabled` 关着而 `qb.ai.site-profile.enabled` 开着时请求被拒（`QB_AI_UNAVAILABLE`），启动检查对这个组合给警告（问题 6）。
7. **并发与锁（取舍二，总控决定）**：
   - **完整的锁顺序**：`qb.account` → `qb.quiz` → `qb.ai:<发起方>` → `qb.ai:*` → `qb.wallet`。**持有 `qb.ai:*` 时不取钱包锁**。写进 `backend/quizbuks/CLAUDE.md` 与 `operations.md` 新的一节，并加测试（AI 锁内不取钱包锁，钱包锁内不取 AI 锁）。
   - 请求与三个结局流程只取 AI 的两把锁（不变）；购买只取钱包锁；退回、到期、月度记费取 `qb.ai:<发起方>` → `qb.wallet:SPONSOR:<发起方>`（**新增这一条**；这三个流程不取 `qb.ai:*`，所以不违反上面的顺序）。月度记费在 AI 锁内做，**加语句预算**（§9）。
   - **已核对（现在就核，读了代码）**：
     - 现有路径里没有"先钱包后 AI"的：取钱包锁的流程（F-M5 冻结与释放 `Prepayments.java:704`、F-M6 `DistributionSettlements.java:122`、F-M2 / F-M3 赠送 `FeeCredits.java:247/340/395`、`LightGrants.java:111`、账单 `FeeProcesses.java:494/524/622/708/878`、`FeeRequests.java:294`、运营商转入 `OperatorTransfers.java:138`、充值 `TopUpCrediting.java:89` 等）都不取 AI 锁；`qb.ai:*` 只在 `content/ai` 里取（`AiProcesses` 的五处），这些流程不取钱包锁；F-M7 新增的只有钱包锁与凭证锁。
     - **`QB_AI_ACCEPT`**（`AiProcesses.java:1264`）：先 `qb.quiz`（`QuizEditing.lockAndLoad`）再 `qb.ai:<发起方>`；流程里**没有调用任何子流程**，不取 `qb.account`。
     - **`QB_SITE_PROFILE_QUIZ`**（`SiteQuizProcesses.java:68`）：只取 `qb.ai:<发起方>`，在其内调用子流程 `QB_QUIZ_IMPORT`（`QuizTransferProcesses.java:134`）。`QB_QUIZ_IMPORT` 对新草稿本身不取锁（`QuizProcesses.loadForReplace` 只在替换已有测验时取 `qb.quiz`）；它的子流程 `QB_QUIZ_PRODUCTS_SET`（`SponsorProductProcesses.java:295`，经 `QbSponsorProducts.lockName` = `QbAccounts.lockName`）**会取 `qb.account`**，但只在导入的文件带产品、或替换已有内容时才被调用（`QuizTransferProcesses.showsProducts`，`:176`）。官网生成写的文件不带产品、也不是替换（`document` 里只有 `materials` 与空的 `questions`，`SiteQuizProcesses.java:152` 起，`intoQuizId` 为空），所以**现在不会在 AI 锁内取 `qb.account`**。这是靠"文件不带产品"保持的，不是结构上的保证：加一个测试断言 `QB_SITE_PROFILE_QUIZ` 不触发 `QB_QUIZ_PRODUCTS_SET`，并在 `SiteQuizProcesses` 的注释里写明。
     - `SiteProfileProcesses.closeReview`（`QB_SITE_PROFILE_REVIEW_CLOSE`，被 `AiProcesses.java:1417`、`:1524` 与 `AiConfig.java:262` 调用）不取任何锁。
   - **F-M15 的约束**：封禁、注销要退回或关闭 AI 额度时，F-M15 将来要在中间取 `qb.ai:<发起方>`：先取 `qb.account`（及需要时的 `qb.quiz`），再取 `qb.ai:<发起方>`，最后取钱包锁；它在 AI 锁内调用的子流程**不能再取 `qb.account`**（`QB_AI_ACCEPT`、`QB_SITE_PROFILE_QUIZ` 在 AI 锁内调用的子流程同理，见上）。写进 §8 的依赖。

## 5 账本科目与分录

| 事件 | 分录（`LEDGER_POST`，引用） | 月报类 |
|---|---|---|
| 购买 | 借 2190（赠送部分）/ 2160（现金部分），贷 2180（发起方、用途 `AI`）；`prepay_ai`，来源 `QbPrepayment` | `AI_CREDIT_BOUGHT`（新） |
| 记费 | 借 1130，贷 4120 + 2150；`fee_ai_<月>`，来源 `QbFeeCharge` | `AI_CHARGED`（新，同 `DISTRIBUTION_CHARGED` 的做法按引用前缀区分） |
| 从额度付 | 借 2180，贷 1130（`QB_PREPAYMENT_USE`，赠送先） | `AI_CREDIT_USED`（新，按用途引用与 `PREPAYMENT_USED` 分开） |
| 退回 | 借 2180，贷 2160 / 2190；已到期的赠送部分借 2180、贷 5140 | `AI_CREDIT_RETURNED`（新） |
| 赠送到期 | 借 2180，贷 5140；来源 `QbFeeCreditGrant` + 原笔编号；同时写负的分摊（§4 第 5 条） | `AI_CREDIT_VOIDED`（新） |

均不落 `OTHER`；`AdminFinanceIT` 逐行加一例（全程：买 → 请求 → 记费 → 退回 → 到期）。平台承担的部分（`absorbedYen`、`unrecordedUsd`）不入账。AI 不进运营商的分成基数，直到 F-M12 问题 4 决定（§3 末条：F-M12 的清单缺省不含 `AI`；本项填 `shareExcluded`）。

## 6 参数（`QbParams`，缺省由 `QB_SETUP` 提出；钱与开关列入 `CONTROLLED`）

| 键 | 类型 / 缺省 / 范围 | 受控 |
|---|---|---|
| `qb.ai.fee.enabled` | 布尔，false（`FirstLaunchSwitches` 首次上线值 true；**打开的前提见 `launch-checklist.md`**） | 是 |
| `qb.ai.fee.markup` | 数，1.5，1 至 10，两位小数 | 是 |
| `qb.ai.fee.usd-jpy` | 数，150，50 至 500，两位小数 | 是 |
| `qb.ai.credit.min-purchase` | 日元整数，**未设置即不限**（同 `qb.fee-credit.manual.max`，不由 `QB_SETUP` 建立），设时 100 至 1 000 000 | 是 |
| `qb.ai.credit.low-amount` | 日元整数，500，0 至 100 000（0 = 不提示） | 否 |
| `qb.ai.fee.unbilled-after-hours` | 整数，30，1 至 720（核对：以 `feePeriod` 的下月初起算，月度记费晚于此仍有未记费） | 是 |
| `qb.ai.fee.absorbed-alert-yen` | 日元整数，1 000，0 至 10 000 000（一个月内平台承担的合计超过即给财务待办；**合计 = `absorbedYen` + `unrecordedUsd` 按冻结汇率折成日元**，两者口径不同——前者含倍数、后者是成本，页面分开列） | 否 |

沿用：`qb.fee.tax-rate`、`qb.ai.price.*`（不认识的模型）、`qb.ai.sponsor.*`、`qb.ai.global.monthly-tokens`、`qb.ai.enabled`。倍数、汇率的改动只影响此后的请求（冻结在作业上）；税率在记费时才读（§4 第 3 条的再核保证不会超出额度）。

## 7 页面与运营闭合

- **发起方**（b）：
  - `/money/ai` `AiCreditPage`：额度合计（赠送 / 现金，每笔赠送的到期日）、可用额、本月未记费金额、"购买"（金额、最低购买额、预览"先用赠送，余下用现金"）、"退回"、用量明细（模板 `qb.sponsor.ai-credit` 新：作业的日期、种类、费用 / "预留中"、状态；变动记录来自 `QbAiCreditMovement`）。
  - 钱包页 `FeeAccount` 的预付款块把 AI 额度分开显示（`qb.sponsor.wallet` 的 `prepaymentHeld` 排除用途 `AI`，另加 `aiCreditHeld`）。
  - 三个 AI 面板（题目、翻译、官网生成）：显示可用额、"本次最多约 ¥X"（`QB_AI_FEE_QUOTE`）与"购买"；额度不足的 422 直接给入口。首页"待我处理"加"AI 额度不足"（可用额低于 `qb.ai.credit.low-amount` 且近 30 天用过 AI 时）。
  - 费用不显示美元，只显示日元；帮助 `docs/quizbuks/help/ai-credit.md`（新，三语）写清"按大模型收费的 150%、汇率、向上取整、赠送先用、赠送到期、可退回、超时或没有回答的调用不收费（失败的作业有回答的用量照收，含平台自己的重试，以问题 13 的答案为准）"，并更新 `manual-sponsor.*` 与官网生成的帮助 `site-profile.md`。
- **运营商管理系统**：发起方详情加 AI 额度余额与本月 AI 费（只读，限自己的发起方）；AI 费出现在现有的费用列表（种类 `AI`）。**新增的视图 / 模板声明 `withinEntryScope`**（D64，按运营商限定，同现有运营商页的写法），并测试别的运营商看不到。
- **平台管理系统**（`/qb/ai`）：`qb.admin.ai-jobs` 加费用列（美元成本、日元费用、平台承担、运营商）；新模板 `qb.admin.ai-fees`（按月、发起方、运营商：成本美元、收费日元、**承担（`absorbedYen` 与 `unrecordedUsd` 分列并合计）**、未记费、额度余额；可导出）；倍数 / 汇率 / 最低购买额 / 开关的受控变更入口沿用参数页。
- **任务与自动收尾**：`qb.ai-fee-monthly`、`qb.ai-credit-expire`（均可补跑）；预留随作业结束释放（发起方自己取消的，预留留到 `QB_AI_JOB_CANCELLED` 才释放）；到期提醒复用 F-M2 的赠送到期提醒（赠送还在 AI 额度中时也提醒，实现时确认 `qb.fee-credit-remind` 的取项含这部分）。
- **告警**：平台承担的合计（见 §6）超过阈值、核对差异的待办按 F-M14 的负责权限规则（缺省 `qb.reconcile`，财务持有；F-M14 审查 M2）；每月把 Anthropic 控制台的账单与总部页的"成本美元合计"对照（`operations.md`）。
- **`operations.md`** 新的一节（编号取当时最大的小节号 + 1；F-M7 也会加一节，排在它后面）：开关与参数、价格 / 汇率的受控变更、两个任务的时刻与补跑、**完整的锁顺序**、核对项的处理、与提供方账单对照（含"迟到的用量只表现为差额"）、"不要用关收费开关止损"、关开关后的行为、官网生成的前提、**给财务：AI 费的收入在次月 1 日才入账，月末与年末的截止差一个月（与配送费一致）**、平台承担的三种情况与上限。
- **`launch-checklist.md`**（本 PR 改，只加一项，在"AI"一节）：**AI 费的分成**——打开 `qb.ai.fee.enabled` 之前，财务已确认 AI 费是否参与运营商分成（F-M12 问题 4；缺省清单不含 `AI`，即完全不分成；若只让加价参与，把 `AI` 加进 `qb.operator.share.kinds`，成本部分由 `shareExcluded` 扣出）。

## 8 对账、月报与依赖

- **核对**（`qb.reconcile`，`recon-fee-account.sql` 加段，`ReconChecks` 加名）：
  - `AI_CREDIT`：按发起方，2180 用途 `AI` = 该发起方 AI 预付款剩余 = `QbAiCreditMovement` 之和（规格：余额 = 购买 − 已记费 − 退回 − 作废）；`PREPAYMENT_PARTS` 对这一笔同样适用（到期的负分摊使它不再每日报差异，§4 第 5 条）；
  - `AI_FEE_MONTH`：按发起方与 `feePeriod`，作业 `feeYen` 之和 = 该月 `AI` 费用的税前金额（`feePeriod` 的下月初之后 `unbilled-after-hours` 才比较）；
  - `AI_FEE_UNBILLED`：超时仍有未记费的作业；`AI_FEE_RESERVED`：已结束的作业仍有预留；`AI_CREDIT_EXPIRED_HELD`：到期超过一天的赠送仍在额度中且没被占用；
  - 提示（不算差异）`AI_FEE_ABSORBED`：平台承担的作业（`absorbedYen` 与 `unrecordedUsd`），供总部查看；`FEE_CREDIT_PROMOTION` 的 5140 比较要包含 AI 额度中的作废（来源 `QbFeeCreditGrant`，与 §4 第 5 条一致，实现时确认它按来源筛选的方式）。
- **月报**：§5 的五类；`FEE_CHARGED` 下 `AI` 种类分列。
- **依赖 F-M7（未合并，分支 `origin/1.3/quizbuks-FM7-audience`）**——逻辑上不依赖它的贷项凭证，但会改同一批文件，须在它合入后拉取并合并：
  1. 迁移号：F-M7 已合并（V119）；本项用 V125（总控 2026-11-13）。
  2. `FeeProcesses` / `QbPrepayments`（它给预付款加 `refundDecidedAt`、`refundOutcome`）/ `FeeConfig` / `QbParams` / `FirstLaunchSwitches` / `FeeRequests`：同文件的并行修改，冲突在合并时解决。
  3. `wallet.sql`（它加 `creditNoteCredit`，改"未付"的算法）、`recon-fee-account.sql`、`monthly.sql`、`recon-fee-receivable.sql`：本项在其上追加。
  4. 贷项凭证可以对任意费用开手工凭证：对种类 `AI` 的费用，按 F-M7 的规则退到账户（不是 AI 额度）。**本项不依赖它，但保留一个测试确认对 AI 费开贷项凭证不出错**（待它合入后补，列为 b 的测试）。
  5. 前端 `sponsor/src/wallet/*`（`FeeAccount.tsx`、`api.ts`、`messages.ts`）与 `admin-extension`（`fee-api.ts`、`fee-messages.ts`）同样被它改动。
  6. `operations.md`（F-M7 新加的小节）、`F-M.md` §10、`first-launch-progress.md`、`backend/quizbuks/CLAUDE.md`：文档合并。
- 其他已合并的依赖：F-M1（`FeeAllocation`、`QbFeePayment`）、F-M5（`Prepayments`、2180）、F-M11（`QbFeeCharge.operatorId` 由调用方给）、F-C7（官网生成作业）、F-C10（帮助独立）；**官网生成的收费依赖本项**（`qb.ai.site-profile.enabled` 由此改 `ACTIVE`）。
- **对后续项的约束**：
  - **F-M15 必须关闭每个注销发起方那一笔 `OPEN` 的 AI 预付款**（`SponsorErasure` 现在不处理 `QbPrepayment`），并决定注销或抹除时剩余额度的去处（推荐按 F-M15 的规则退回；封禁不没收，问题 16）；封禁、注销要在 AI 锁内调用本项的退回 / 作废 Java，锁序见 §4 第 7 条，其间调用的子流程不能取 `qb.account`。
  - **F-M12**：AI 种类是否进分成基数由 F-M12 的清单决定（缺省不含）；本项填 `shareExcluded`（成本部分）并补 AI 种类的结算 IT；AI 费的结算就绪条件在 F-M12b，谁后合并谁加。
  - F-M13：账单 PDF 的分列。

## 9 测试清单

- 单元：`AiFeeRulesTest`（向上取整、很小的用量为 1 日元、无用量为 0、倍数汇率、税向下取整、预留含税、可用额含"已过期被占用的赠送"、超出可扣额时由含税倒推税前与承担、**累计含税增量与整组一次记费的合计相等**）；`AiCostTest` 加 `maxOf`（尝试数、长提示价、搜索、**所选模型与回退模型取较贵者**）；`billableUsd + unrecordedUsd = costUsd`。
- `AiCreditIT`（新）：开关关 = 完全免费、语句数与现在相同；无额度被拒 `QB_AI_CREDIT_SHORT`；购买（赠送先、现金后、最低购买额、封禁 / 未验证邮箱被拒、**入驻未通过也能买**、幂等键重放）；请求预留、完成结算、释放；失败有回答的用量收、无用量不收；没有运行过的取消不收，运行中取消按有回答的用量收；**三种情况各一例**（有回答的收；调用没有回答的只进 `unrecordedUsd`、不收；实例死掉的只进 `unrecordedUsd`、不收；三者都进 `costUsd` 与 Token 用量）；实际超过预留时扣到 0、承担与告警；官网生成收费、审核人对照检查不收、**发起方自己问帮助 AI 与 AI 获客（Q48d，运营商承担）在开关开着时都不动发起方额度**；**`QB_SITE_PROFILE_QUIZ` 写的作业恒为 `NONE`、预留 0（M4）**；两个请求同时只有一个通过（栅栏）；月度记费按（运营商，月）分组、赠送先用、再运行不重复；**无运营商的作业记为无运营商（入驻前请求、之后分配运营商）**；月中改派运营商后旧作业仍归请求时的运营商；`feePeriod` 取结局那一刻所在的月（10 月 31 日结束、11 月 1 日 00:27 之后才跑结局的作业属于 11 月）；退回（现金先，原笔已到期则作废；封禁中不能退）；到期不作废已用未记费的赠送、记费后下一次作废剩余；开着请求 → 关开关 → 仍结算；**购买与请求并发、运行中的作业期间退回**；锁序（AI 锁内不取钱包锁，钱包锁内不取 AI 锁）；`sourceKey` 长度不超过 100；同一 `sourceKey` 两笔费用时幂等仍以 `feeChargeId` 为准。
- **H2 三例**：同月多个 1 日元的作业（含税累计取整，不超过额度）；税率在作业结束与记费之间改变；记费后**普通账户与 1130 都没有变化**（额度恰好用到 0，不落到普通账户，也不生成未付账单）。
- **H3**：赠送到期后 `PREPAYMENT_PARTS` 对这一笔为 0 行差异；`FEE_CREDIT_PROMOTION` 的 5140 含 AI 额度的作废；F-M2 的每日到期任务与本任务谁先跑都不重复作废。
- **H4**：超过 1000 行分摊的 AI 额度（多次购买、记费、退回、到期累积），请求、`QB_PREPAYMENT_USE`、月度记费仍正确且读取的行数按笔、不按分摊行数；`planRelease` 对其他用途的上限不变。
- `AiCreditStatementBudgetIT`（新，`SqlStatementLog`）：开关开 / 关两种请求的语句数相同（读取并入同一条）；写入只多作业列；**`QB_AI_FEE_SETTLE` 的语句预算断言确切值：固定部分 + 每个作业一次回写，取 1 个与 200 个作业各一例**（上限 200）；购买、退回、到期各断言确切值。**AI 的现有预算测试不变**。
- 只追加：`QbAiCreditMovement` 与 `qb_fee_charge_version` 没有 UPDATE / DELETE；`SealedColumnsHistory`；`QbAiCreditMovement` 的数据视图 `processOnlyWrites()`（经通用接口写被拒）。
- 范围（D64）：运营商页新增的视图 / 模板声明 `withinEntryScope`，别的运营商看不到（测试）。
- 核对与月报：`ReconChecksTest` 每个新项各一例；`AdminFinanceIT` 五类逐行；`FirstLaunchSwitchesCheckTest` / `FirstLaunchSetupIT` 两行 `ACTIVE`；`SetupParamsCheck`；`MessagesTest` 三语；`ProcessAccessIT` 新流程的权限与 `internal()`；ArchUnit；AI 种类的运营商结算 IT（清单不含 `AI` 时 `excluded = amount`；含 `AI` 时基数为 `amount − shareExcluded`；`ai-cost-in-base` 打开时为全额；`shareExcluded` 的取值与上限）。
- F-M7 合入后：对 AI 费开贷项凭证的测试保留（§8 依赖 4）。
- 场景：`ai-credit.yml`（买 → 请求 → 完成 → 月度记费 → 退回，固定时钟，`completeExternalJob` / `runJob`）及快照。
- 前端 Vitest：`AiCreditPage`、购买 / 退回对话框、面板的额度提示与"不足"入口、总部 AI 页新列；axe 过（亮暗两种外观）。
- e2e：`ai-credit.spec.ts`（新，用 `tools/quizbuks/claude-stub`）：买额度 → 生成题目 → 看到费用 → 月度补跑 → 余额；更新 `ai.spec.ts` 在开关开着时先买额度。

## 10 风险

- **提供方账单与我们的收费对不上**：我们的 `costUsd` 是标价估算（偏高，不含缓存折扣）；重试可能被提供方重复计费；没有回答的调用与实例死掉的尝试平台不向发起方收（承担）；作业结算后才到的迟到用量不回写。缓解：每月对照控制台账单与总部页合计，提供方设支出上限，承担部分计入告警（含 `unrecordedUsd`）。
- **重复收费**：同一作业只在结局流程里结算一次（`finishedAt` 已有即跳过），月度记费以 `feeChargeId` 为准，再运行为空操作；测试覆盖。
- **预留与可用额的一致性**：额度的退回 / 到期 / 记费都在 AI 锁之内改，请求读取与它们互斥；新增锁序（AI → 钱包）若与未来流程相反会死锁，因此写进 `CLAUDE.md` 并加测试；F-M15 的约束见 §8。
- **到期保护的复杂度**：已用未记费的赠送要在到期日留到月度记费，规则与测试较多，是本项最容易出错的一处。
- **预留偏大**：按全部尝试的最高用量、且按所选与回退模型中较贵的预留，可用额会暂时被占住；释放在作业结束时，作业卡住时靠 `external-job` 的租约与超时收尾。
- **一笔长期的预付款**：靠按笔汇总读取避免 1000 行上限；若日后仍有读取分摊行的路径，会在长期使用的发起方身上失败，因此 §2 的逐处确认清单要在 PR 描述里逐条回答。
- 官网生成目前没有搜索费用，若以后启用搜索而 `AiCost` 的搜索项没有跟上，会少收（承担）；测试保证搜索指标计入。
- 与 F-M7 的并行修改（见 §8）。

## 11 要问需求方的问题（每条带建议）

先解释三个词，下面的问题会用到：**预留**＝发起方提交 AI 请求时，先按这次最多可能花的钱，把额度里的一部分暂时占住，作业结束后按实际用量结算，多占的放回；**承担**＝本来可以向发起方收、但决定不收，由平台自己出的钱；**未记费**＝作业已经结束、用量也已算出，但要到下个月 1 日才从额度里扣的那部分。

1. **税**：倍数后的 AI 费要不要另加消费税？**建议：要**，与其他平台费一样（税前费用 = 成本 × 1.5 × 汇率，向上取整，另加税），额度的金额含税。
   - **答复**：需求方 2026-11-13：按推荐。
2. **用量没有算清的三种情况，各按什么收？** 下面三种分别回答：
   - （甲）调用有回答：**建议：按实际用量收**。
   - （乙）调用超时、没有回答（平台不知道提供方有没有收钱，系统按"最多可能用了多少"估计）：**建议：平台承担，不向发起方收**。
   - （丙）运行作业的服务器中途死掉、没来得及报告用量：**建议：平台承担，不向发起方收**。
   
   （乙）（丙）的承担受发起方的每日次数和每月 Token 上限约束，最多不超过这个上限对应的成本：按目前的缺省值，一个发起方每月最坏约 50 美元（约 7 500 日元），全体发起方合计最坏约 1 250 美元；实际远低于此。承担的金额会计入总部页和给财务的告警。注意：这与平台内部记账时"没有回答就按估计计费"的做法（D52 第 1 条，那是平台自己记成本）有意不同——这里讲的是向发起方收费。
   - **答复**：需求方 2026-11-13：按推荐。
3. **入驻前能不能用"从官网生成"？** 规格里一处写"不设免费特例"，另一处问过要不要小额启动额度。**建议：入驻前必须先充值，再购买 AI 额度；不给启动额度。** 入驻赠送要等入驻通过才发，所以入驻前没有赠送额度可用。
   - **答复**：需求方 2026-11-13：按推荐。
4. **审核人用 AI 核对翻译，这笔费用由平台出，不从发起方的额度扣，对吗？**（仍计入每日次数和每月 Token 限额。）**建议：对。**
   - **答复**：需求方 2026-11-13：按推荐。
5. **退回额度**：可输入金额、先退现金再退赠送，还是只能整笔退？**建议：可输入金额，现金先退**。
   - **答复**：需求方 2026-11-13：按推荐。
6. **收费开关关着时的官网生成**：**建议：不允许**（它是新功能，不应由平台免费承担），并在"收费开关关着、官网生成开关开着"这种不一致时在启动时给警告。
   - **答复**：需求方 2026-11-13：按推荐。
7. **后付计费方式的发起方**（有信用额）也必须先买 AI 额度吗？**建议：是**（AI 费难预测，规格也写"先购买"）。
   - **答复**：需求方 2026-11-13：按推荐。
8. **运营商对 AI 费的分成**：AI 费是成本的 150%，其中 100% 是平台付给 AI 提供方的成本，若像其他平台费那样整笔分给运营商，分成超过三分之一平台就亏。**建议：本项先不把 AI 费分给运营商（AI 费不进分成基数）；打开 AI 收费开关之前，请财务确认分成比例，或等 F-M12 把成本部分扣出分成基数。** 这一条会写进上线检查清单。
   - **答复**：需求方 2026-11-13：按推荐。
9. **最低购买额**：规格是"未设置即不限"。**建议：上线前设为 1 000 日元**，避免大量小额购买记录。
   - **答复**：需求方 2026-11-13：按推荐。
10. **提示阈值**：额度低于 500 日元且近 30 天用过 AI 时提示"AI 额度不足"；平台承担合计超过 1 000 日元（每月）给财务待办。**建议：先用这两个值**，上线后调。
    - **答复**：需求方 2026-11-13：按推荐。
11. **预留按哪个模型的价格算**：提供方可能把请求转到更贵的备用模型（例如从 Sonnet 转到价格约 2.5 倍的模型）。**建议：预留按所选模型和备用模型中较贵的那个算**，这样预留一般不会不够；代价是请求时占住的额度比最终实际收的多，作业结束就放回。万一仍超过（例如提供方中途改价、税率改变），超出部分由平台承担，不向发起方多收。是否接受？
    - **答复**：需求方 2026-11-13：按推荐。
12. **赠送额度的到期**：已经用掉、还没到月度记费的赠送，到期日不作废。例：11 月 5 日用了 100 日元赠送，11 月 10 日这笔赠送到期，12 月 1 日记费时仍用这 100 日元的赠送，不改收现金。**建议：这样做**（否则发起方明明在有效期内用了，却被收现金）。
    - **答复**：需求方 2026-11-13：按推荐。
13. **失败的作业收不收费？** 作业失败（例如 AI 的回答不合格），但提供方已经回答过、用了 Token，包括平台自己在失败后自动重试的那几次：**建议：有回答的用量照收，没有回答的不收**（与问题 2 一致）。收了费的失败作业，帮助里会写明。
    - **答复**：需求方 2026-11-13：按推荐。
14. **平台承担的上限**：问题 2 的承担，除了每日次数和每月 Token 两道上限，要不要再加一个"平台每月承担的总额上限"，到了就暂停全部 AI 请求？**建议：不加**，只用告警（超过 1 000 日元给财务待办，问题 10），因为每月 Token 上限已经封顶，再加一道会让发起方莫名其妙被拒。
    - **答复**：需求方 2026-11-13：按推荐。
15. **入驻还没通过的发起方，能不能充值、能不能购买 AI 额度？** **建议：能**（入驻前要用"从官网生成"就必须先充值并购买；充值本身不需要入驻通过）。
    - **答复**：需求方 2026-11-13：按推荐。
16. **被封禁的发起方，AI 额度里剩下的钱怎么办？** 封禁期间发起方不能用也不能退这笔钱。**建议：封禁期间冻结，解封后照常使用；如果最后注销账号，按注销的规则（另一项 F-M15 定）退回，不没收。**
    - **答复**：需求方 2026-11-13：按推荐。

## 12 审查（Fable）与总控的决定（2026-11-12）

**F-M12a（#687）合并后的修订（2026-11-13）**：§3 末条原写"`operator-fees.sql` 加一处筛选"，该模板已由 #687 删除，分成基数改为按清单 `qb.operator.share.kinds`（缺省不含 `AI`）；本项改为给 AI 费填 `shareExcluded`（成本部分）并补 AI 种类的结算 IT（总控：合并条件）。下文 M2 一条中的"`operator-fees.sql` 一处筛选"以此为准。迁移号 V125，拆成 a-1、a-2、b 三个 PR（§0 开头）。

审查对象 `56528a85d` 的计划；决定已写进上文各节，这里列去处。
- **高**：
  - H1 三种情况分别记、分别决定，处理器的估计另记一组指标，`billableUsd` 不含它，`unrecordedUsd` 含两种，写明偏离 D52 第 1 条与承担上限（§1 现状、§2 数据、§3 第一条、§10、§11 问 2）；
  - H2 (a) 按（运营商，月）累计算含税增量，(b) 记费前再核额度、绝不落到普通账户，(c) 三个测试（§3 作业结束、§4 第 3 条、§9 H2 三例）；
  - H3 到期也写负的分摊，复用 `Prepayments.giveBack` 那一段，分录来源 `QbFeeCreditGrant` + 原笔编号（§4 第 5 条、§5、§8、§9 H3）；
  - H4 与取舍一：保留每个发起方一笔，不解除 `amount` 的 `immutable`，累计购入由 `BUY` 求和，AI 的 `held` 按笔汇总读取，`USE` 同一读取，超过 1000 行分摊的测试，逐处确认清单，F-M15 关闭这笔预付款（§2、§8 对后续项的约束、§9 H4、§10）。
- **取舍二**：完整锁顺序 `qb.account` → `qb.quiz` → `qb.ai:<发起方>` → `qb.ai:*` → `qb.wallet`，持有 `qb.ai:*` 时不取钱包锁，`QB_AI_ACCEPT` 与 `QB_SITE_PROFILE_QUIZ` 已读代码核对（前者不调用子流程；后者的子流程 `QB_QUIZ_IMPORT` 对新草稿不取锁，`QB_QUIZ_PRODUCTS_SET` 会取 `qb.account` 但官网生成的文件不带产品所以不触发，加测试），月度记费语句预算（§4 第 7 条、§8、§9）。
- **中**：
  - M1 `ChargeInput` 的"无运营商"写法与测试（§2 `feeOperatorId`、§4 第 3 条、§9）；
  - M2 本项把 AI 种类排除在分成基数之外（`operator-fees.sql` 一处筛选），`launch-checklist.md` 加一项，问 8 改写（§1、§3 末条、§5、§7、§8、§11 问 8）；
  - M3 问 3 直说"入驻前必须先充值再购买，不给启动额度"，新增问 15（入驻未通过能否充值与购买）（§4 第 2 条、§11 问 3、问 15）；
  - M4 `QB_SITE_PROFILE_QUIZ` 恒为 `feeMode = NONE`、预留 0，加测试（§3 请求、§9）；
  - M5 `feePeriod` 取结局流程那一刻所在的月，时区用 `FeeConfig` 的，同一 `sourceKey` 可能有两笔（§2、§4 第 3 条、§8、§9）；
  - M6 告警与总部页的"承担"都计入 `unrecordedUsd`，作业结算后才到的用量不回写（§3 迟到的用量、§6、§7、§10）；
  - M7 预留按所选与回退模型中较贵的算，问 11 改写（§3、§9、§11 问 11）；
  - M8 测试全部加上：范围 `withinEntryScope`、`processOnlyWrites`、`ErasureIT` 覆盖新表、帮助 AI 与 AI 获客不动额度、并发、语句预算与作业数上限（每项 200 个，按批给出多项）、F-M7 合入后的贷项凭证测试保留（§2、§3、§4 第 3 条、§9）。
- **低**（全部采纳）：
  - "五种作业的 `onCancelled`"，发起方自己取消时预留留到 `QB_AI_JOB_CANCELLED` 才释放（§1、§7）；
  - `ClaudeWebResearch.SEARCH_PRICE_USD` 已有且 `costUsd` 目前不含搜索，加搜索项时用已有单价、不会算两次（§1、§3 费用）；
  - `sourceKey` 改为 `ai:<月>:<运营商>`（发起方已在费用上）并加长度断言，写明费用表的 `sourceKey` 没有唯一约束、幂等靠 `feeChargeId`、同一键可有两笔（§4 第 3 条、§9）；
  - 封禁中的发起方不能退回，`QB_AI_CREDIT_RETURN` 与 `QB_AI_FEE_QUOTE` 的权限写明（§4 第 1、4 条）；总控 2026-11-13：两者都同意，封禁不没收，注销时的去处归 F-M15（依赖），加问题 16；
  - 收入次月 1 日入账、月末与年末截止差一个月，写进给财务的清单（§4 第 3 条、§7 `operations.md`）；
  - 失败作业"有用量照收"含平台自己的重试，加为问需求方的问题 13，帮助里写明（§3、§7、§11 问 13）；
  - 不新增科目站得住，承担的部分不入账（§2、§5）。
- **L5（F-M12 审查，总控定）**：所有"F-M12 之前照现有方案分成"的说法改为"AI 不进运营商的分成基数，直到 F-M12 决定怎么算"（§1、§3 末条、§5）；月度 AI 费的 `period` 明确为用量月（结算作业的月，§4 第 3 条）。
- **问题（第 11 节）**：问 2 拆成三种情况并说明平台最多承担多少（§11 问 2）；问 3 见 M3；问 4 用 Fable 的简单问法；问 8 见 M2；问 11 见 M7；问 12 附例子；补三个遗漏的问题（问 13 失败作业是否收费、问 14 平台承担的上限、问 15 入驻未通过能否充值与购买）；去掉代码名，"预留""承担""未记费"各用一句话解释一次。
- **本次计划修订中新发现、需总控留意的两点**：(1) 月度记费的取项改为按批给出多项（每项最多 200 个作业），以满足语句预算的上限，月度任务本身仍是每月 1 日一次；(2) `sourceKey` 去掉发起方编号，因为发起方编号最长 128 字符，加上月与运营商会超过费用表 `sourceKey` 的 100 字符上限。

删除的功能：无
