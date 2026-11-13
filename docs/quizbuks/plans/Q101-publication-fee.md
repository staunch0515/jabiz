# Q101 发布的配送费：按开始答题的人数、单价随奖励、运营商定价、按月结算（计划，2026-11-05；需求方 2026-11-05 确认 §1 全部）

> **已归档（2026-11-07）**：首次上线的内容以 [`docs/quizbuks/first-launch-spec.md`](../first-launch-spec.md) 为准，本文只作存档（查当初的讨论用）；两者不一致时以规格为准。

需求方 2026-11-05 的讨论（六稿，以最后一次为准）定下的规则见 §1。编号 Q101：Q100 之后的第一个空号。
**这只是计划**：按 CLAUDE.md 第 7 节，开发前再给细化的实施计划（Q101a 后端、Q101b 三端页面）。
按 Q94，首次上线平台不收钱：开关缺省关，开发排在首次上线之后。

## 0. 为什么

- 发布把题目送到答题者面前，这是运营商（代表平台）提供的**服务**；有人开始答题，服务就已经给出，不论答完与否、合格与否。
- 奖励越高的发布越吸引人，配送的价值越高：单价随发布的最高奖励成比例。
- **配送费不是抽成**：抽成以合格（发奖）为前提、从奖励中扣；配送费以开始答题为准、由发起方另付，答题者的所得一分不少，而且**答题者完全看不到**。

## 1. 决定（需求方 2026-11-05 确认）

| # | 决定 |
|---|---|
| D-Q101-1 | **按人计**：建立了答题会话（`QbAttempt`）即计一人，不论是否答完、是否合格。一人一次（用户 + 发布唯一）保证每人只计一次；自己的发布本来不能开始 |
| D-Q101-2 | **配送单价** = clamp（比例 × `maxReward`，下限，上限）。`maxReward` 是发布的最高档位（Kudos，提交时冻结）；线下兑奖与电子奖品按审核过的 Kudos 价计入，与学分奖励没有区别（Q22）。Kudos 与日元一比一（Q87a） |
| D-Q101-3 | **配送费** = max（最低配送费，round（该期开始答题的人数 × 配送单价）），另加消费税（`qb.fee.tax-rate`）。单价保留小数，金额在结算时取整到日元 |
| D-Q101-4 | **配送费只有这一项**：不包含受众规则的任何加价。**规则是工具，按 Q47c 自己的方式收费**（按天、最低使用费、提交时扣），两种费用各记各的 |
| D-Q101-5 | **配送费是服务费，不是抽成**：费用种类 `DISTRIBUTION`，与奖励结算、科目 4110 无关；计划、界面与文档一律叫"配送费"，不用"抽成""费率"等词 |
| D-Q101-6 | **价格由运营商定**：比例、下限、上限、最低配送费、最低预付款写在运营商自己的价目表中，负责人修改（理由必填、审计）；改价只影响此后提交的发布。**总部给出可设的范围**（受控参数，四眼），超出即拒绝保存 |
| D-Q101-7 | **收入归运营商**：配送费记在发起方所属运营商名下，每月按该运营商的结算方案（Q48e）与平台分成，并入 Q48b 的月结算（Q87b 的平台费已在其中）。Q92 §3 因此加一类"服务（运营商定价）"（见 §6） |
| D-Q101-8 | **不设配送人数上限**：由人工审核把关；答题路径不改 |
| D-Q101-9 | **按月结算**：每月对上一个月开始答题的人数记费，有多少人收多少 |
| D-Q101-10 | **发布设定的人数是合格人数**（`maxWinners`），不作配送人数，只作**预付款**的依据：预付的发起方提交时预付 `maxWinners` × 配送单价（含税）；没有设 `maxWinners` 的发布按运营商价目表的**最低预付款**。后付的发起方不预付，只按同一金额检查信用额 |
| D-Q101-11 | **欠费时进行中的发布照常进行**（答题者看不到配送费，答题路径不改）；只拦新的提交（现有的 `FeeGate`），由运营商按逾期流程（Q87b）处理 |
| D-Q101-12 | **答题者完全看不到**（§4） |
| D-Q101-13 | **对发起方的说明**："答题者获得的奖励就是发起方设定的全部，平台不从中扣取任何部分。" 上线后告知发起方（`operations.md` §7.21、`launch-checklist.md`），并写在发起方后台的配送费说明中 |
| D-Q101-14 | **不收的发布**：点亮者为发起方的挑战发布（Q90 §2.8，没有答题者，代码中判定）；官方账号的发布（受控参数 `qb.distribution.official-exempt`，缺省是）；Shopify 店的发布第一期不收（Shopify 的收费走它的应用计费，S3） |

## 2. 钱怎么走

| 时点 | 预付 | 后付 |
|---|---|---|
| **提交**（与审批请求同一事务，在发布锁内） | 冻结配送条件（单价及其依据）；预付款 2160 → **2180 预收配送费**（负债，维度：发起方）；可用现金不足即 422 `QB_DISTRIBUTION_FUNDS`，什么也不写 | 冻结配送条件；未付合计 + 同一金额超过信用额即 422 `QB_DISTRIBUTION_FUNDS` |
| **驳回、撤回、审核过期** | 预付款全额 2180 → 2160 | — |
| **每月 2 日结算**（上个月的人数） | `QB_FEE_CHARGE`（种类 `DISTRIBUTION`）：先从预付款扣（2180 → 4120 + 2150），不够的从现金扣（2160 → 4120 + 2150）；现金也不够即形成未付账单（Q87b 预付的做法） | 记应收（1130 → 4120 + 2150），进入当月账单 |
| **发布结束后的那次结算** | 结清最后一段，剩余预付款 2180 → 2160 | 结清最后一段 |

- 版本切换（Q51）：满分与档位不变，单价不变。暂停：不影响（按人计，暂停期间没有人开始）。复制：新发布提交时另行冻结与预付。
- 开关打开之前提交的发布不收（没有冻结的配送条件）。

## 3. 后端（`backend/quizbuks`，包 `fee` 与 `publishing`）

### 3.1 迁移（编号以合入时为准，现在最新为 V76）

- `qb_distribution_price`（普通表）：`operator_id` 唯一、`rate`（比例）、`unit_floor`、`unit_ceiling`（可空）、`minimum_fee`、`minimum_deposit`、`version`。没有价目表的运营商即不收。
- `qb_publication_distribution`（普通表，提交时插入，主键 `publication_id`）：`sponsor_id`、`operator_id`（提交时的归属）、`billing_mode`、冻结的 `max_reward`、`rate`、`unit_floor`、`unit_ceiling`、`unit_price`、`minimum_fee`、`deposit`、`deposit_used`、`deposit_released`、`settled_through`（已结算到的月份）、`closed_at`。
  单价以 `numeric(19, 4)` 存（不是金额字段）；金额字段是日元整数。
- `qb_distribution_settlement`（只写一次，`t.writeOnce()`，`jabiz_protect_append_only`）：`publication_id`、`month`（二者唯一）、`starts`、`amount`、`from_deposit`、`from_cash_or_receivable`、`fee_charge_id`。
- 索引 `qb_attempt (publication_id, started_at)`：按月数人。
- 科目 2180 由 `QB_SETUP` 开立，映射参数 `qb.journal.account.2180`（受控，Q9d 的做法；`JournalMappingCheck` 随之）。

### 3.2 代码

- `DistributionPricing`（纯 Java）：单价（比例 × 最高奖励、上下限）、预付款（`maxWinners` 或最低预付款、含税）、月费用（最低配送费、取整）、总部范围的检查与原因。
- 价目表：实体 `QbDistributionPrice`（`processOnlyWrites`，经 `OperatorScopes` 限定）；流程 `QB_OPERATOR_DISTRIBUTION_PRICES_SET`（运营商负责人，`RequireScope`，理由必填）与 `QB_DISTRIBUTION_PRICES_SET`（总部财务，任何运营商），都检查总部范围（422 `QB_DISTRIBUTION_PRICE_OFF_RANGE`）。
- 提交：`PublicationLifecycle.submit` 加一步——读价目表与现有 `FeeGate` 的读取合并成一条 `QueryEntities.all`；冻结条件、预付或检查信用额。驳回、撤回、审核过期三处释放预付款。`StatementBudgetIT` 的提交预算随之改，并说明原因。
- 结算：逐项任务 `qb.distribution-fee`（`JobDefinition.forEach`，每月 2 日 01:12，在 `qb.fee-invoice` 01:17 之前），每项流程 `QB_DISTRIBUTION_SETTLE_ITEM`（`internal()`）：取 `qb.wallet:SPONSOR:<发起方>` 锁，数上个月的开始人数，写结算记录并经 `QB_FEE_CHARGE` 记费；发布已结束的同时释放剩余预付款并 `closed_at`。月份与平台费同一时区。
- 对账：每日核对加 `DISTRIBUTION_DEPOSITS`（2180 按发起方 = 未关闭行的 `deposit − deposit_used − deposit_released`）；月报 `qb.admin.monthly` 加 `DISTRIBUTION_DEPOSIT`、`DISTRIBUTION_CHARGED`、`DISTRIBUTION_RELEASED` 与 2180 一列。
- 运营商结算（`OperatorSettlements`）：配送费作为平台费的一种，已在"收费合计"中，不另改公式；结算单的明细按种类分出"配送费"。
- 受控参数（`ControlledParams`）：`qb.distribution.billing.enabled`（缺省 false）、`qb.distribution.official-exempt`（true）、总部范围 `qb.distribution.rate.min` / `.max`、`qb.distribution.unit-floor.min` / `.max`、`qb.distribution.unit-ceiling.max`、`qb.distribution.minimum-fee.max`、`qb.distribution.minimum-deposit.max`。

### 3.3 模板

`qb.sponsor.distribution`（发起方：本月已开始人数（取 `QbAnalyticsHour.starts`，不加计数）、单价、本月预计配送费、预付款余额）、`qb.sponsor.distribution-settlements`、`qb.operator.distribution-prices`、`qb.admin.distribution-prices`、`qb.admin.distribution-settlements`；`qb.sponsor.publication` 加配送条件；报价 `QB_PUBLICATION_FEE_QUOTE`（向导用：单价、预付款、可用现金或剩余信用额）。

## 4. 答题者看不到（D-Q101-12）

| 地方 | 约束 |
|---|---|
| 奖励 | 答题者看到与得到的就是审核过的档位；配送费不影响档位、名额与预算 |
| App 与公开页 | `QbPublicationCard`、`qb.app.*`、`qb.public.*` 不含配送条件与费用 |
| 排序 | 首页排序不用配送费或单价（付费置顶若要做，是另一种可售项） |
| 权限 | 价目表、配送条件、结算只给发起方本人、所属运营商与总部财务；`qb.me` / `qb.play` 读不到 |
| 通知 | 给答题者的邮件与站内信不提配送费 |

测试：`PublicQueriesSnapshotIT` 与 App 模板的列不含费用字段；`DistributionFeeIT` 断言开关打开前后答题者读到的内容相同。

## 5. 前端

- 发起方后台：向导的奖励一步在改最高档时显示配送单价；检查一步给出预付款与可用现金或剩余信用额；发布页的配送条件、本月预计与历次结算；概览加"本月配送费"卡；钱包的预付款余额；配送费说明（含 D-Q101-13 的那句话）。
- 运营商后台：配送价目表（范围内可设，显示总部的范围）。
- 管理后台扩展：各运营商的价目表、结算列表。
- 文案三语。

## 6. 对其他文档的修改

- `Q92-overall-design.md` §3：加一类"服务（运营商定价）"：运营商提供、运营商定价、收入归运营商、按结算方案与平台分成；例子：发布的配送费。
- `Q90-trees.md` 附录 A：加 S42 `sponsor.distribution`（配送费说明与价目，核心：提交发布必经）；付费功能的入门节点（§0-19）建议配"配送与计费须知"。
- `Q47c-audience-billing.md`：不改（规则费照旧）；`02-design.md` §3.1 / §4.2 / §5.2、`backend/quizbuks/CLAUDE.md` 钱包一条、`operations.md` §7.21、`launch-checklist.md`、`03-plan.md`：实施时随代码改。

## 7. 测试

- `DistributionPricingTest`：比例、上下限、空上限、最低配送费、取整、预付款两种依据、总部范围。
- `DistributionFeeIT`：开关关着不收；预付提交即预付、不足被拒且什么也没写；后付超信用额被拒；三处释放；月结按开始人数（未答完、不合格的都算）；先扣预付款再扣现金、现金不足成未付账单且新提交被拒、进行中的发布照常；结束后释放剩余；每月只结一次；官方账号与发起方挑战不收；运营商只能改自己的价目表且受总部范围约束；运营商结算含配送费；答题者读到的内容不变。
- `StatementBudgetIT`（提交）；`AttemptStatementBudgetIT` 不变；每日核对与月报的新项。
- 场景 `distribution-fee.yml`（固定时钟跨月）；前端 Vitest；e2e `distribution-fee.spec.ts`（运营商设价 → 发起方提交看到预付 → 后台结算列表，axe）。

## 8. 估计与风险

约 8–10 天（后端 5–6、三端页面 2–3、测试与文档 1）。

| 等级 | 风险 | 对策 |
|---|---|---|
| 中 | 不设人数上限，预付的发起方可能累积欠费 | 审核把关；`FeeGate` 拦新提交；逾期流程；概览实时显示本月预计 |
| 中 | 被理解为抽成 | 名称、种类、科目都分开；答题者看不到；D-Q101-13 的说明 |
| 低 | 月初数人的语句量 | 按 `(publication_id, started_at)` 索引，每个发布一项 |
