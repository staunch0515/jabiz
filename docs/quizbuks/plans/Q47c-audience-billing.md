# Q47c 客户匹配的规则费用（计划，2026-11-03 第二稿；需求方 2026-11-03 确认 §9，Q87 合入后开发）

> **已归档（2026-11-07）**：首次上线的内容以 [`docs/quizbuks/first-launch-spec.md`](../first-launch-spec.md) 为准，本文只作存档（查当初的讨论用）；两者不一致时以规格为准。

`plans/Q47-audience.md` 的第三期（D-Q47-7）。第二稿按需求方 2026-11-03 的要求重写：

1. **规则在数据库中建记录、管理起来**；
2. **价格由每个运营商定**（直营的商户由总部定），**每次的最低使用费**与价格放在一起，由运营商设置；
3. **在发布执行期间按天收费**：没有后台按月定价的套餐，商户用了几天就按几天收，天数向上取整；
4. **提交时扣款，余额不足就不能提交**；
5. 原则同 Q87：每条路都修出来，开、关、缺省都是受控参数（四眼修改），由管理人员定，代码不决定什么可以、什么不可以。

**依赖 Q87**（`plans/Q87-fee-account.md`）：费用是 Q87 平台费的一个种类，经它的 `QB_FEE_CHARGE` 记费，预付从现金账户扣、后付记应收并受信用额约束，记在商户所属运营商名下、并入 Q48b 的月结算与分成。顺序 Q86 → Q87 → **Q47c**（Q88 可在 Q47c 之前或之后）。
**这只是计划**：确认 §9 后、Q87 合入后开发，一个 PR，工作分支 `1.3/quizbuks-47c-audience-billing`，迁移编号以合入时为准。

## 0. 为什么

- **定位**（需求方 2026-11-03）：规则是平台的一种**产品类型**，是平台技术含量的体系，要能不断补充（新增一种规则就是一次开发：种类、答题者的填写、匹配与计数），否则平台会慢慢失去价值；**运营商是销售规则产品的商人**，自己定价格。

- 平台的技术含量体现在匹配规则上（Q47 的出发点）：商户越精准地选人，花的钱越值；规则费用是平台费的第一种按用量计的费用。
- 运营商最了解自己的商户，价格由运营商定；平台只定"能不能收、缺省是什么"的开关。

## 1. 现状

- **条件的定义在代码中**：`audience.AudienceCriteria.ALL`（性别、年龄、职业、兴趣），每个带计费键 `audience.<键>`；答题者的回答项与 App 的填写页随之固定（新增一种条件要一次迁移与页面）。
- 发布的条件 `QbPublicationCriterion` 只经 `QB_PUBLICATION_AUDIENCE_SAVE` 在草稿时整体替换，提交即冻结；`INVITED` 与渠道（Shopify）的发布没有条件。
- 提交发布（`PublicationLifecycle.submit`）在发布锁内检查、再经平台审批；提交目前不动钱。
- Q87（计划）：商户的现金账户（`QbWallet` 种类 `CASH`，科目 2160）、费用记录 `QbFeeCharge`（只追加，种类在 `FeeKinds`）只经 `QB_FEE_CHARGE`；预付即扣、后付记应收（1130）月初出账单；4120 平台使用费收入、2150 应付消费税；平台费记在运营商名下、并入 Q48b 的结算与分成。

## 2. 决定（草案）

| # | 决定 |
|---|---|
| D-Q47c-1 | **规则目录建在数据库中**：每种条件一条记录 `QbAudienceRule`（键、是否开放给商户使用、是否计费、排序；名称与选项仍按键取自三语消息），由总部经 `QB_AUDIENCE_RULE_SET` 管理（审计）。代码只提供条件的**种类**（`AudienceCriteria`：怎样匹配、答题者怎样填），开放与否、收不收费由记录定；`QB_SETUP` 为代码中的四种建记录（开放、计费，即现状）。关闭的规则：商户不能再选（`QB_AUDIENCE_RULE_CLOSED`），已提交的发布照旧按冻结的条件匹配。 |
| D-Q47c-2 | **价格表按运营商**：`QbAudiencePriceList`（所有者：运营商编号，或直营的 `direct`；每次最低使用费 `minimumFee`；生效时刻）+ `QbAudiencePrice`（价目表、规则键、日价，JPY 整数）。运营商的负责人经 `QB_AUDIENCE_PRICES_SET` 整表保存自己的（入口范围给出运营商，先取运营商锁再查成员关系，理由必填，审计）；总部经同一流程保存 `direct` 的与任何运营商的。改价只影响**此后提交**的发布。某运营商没有价目表时用直营的（受控参数 `qb.audience.price.fallback`：`DIRECT`（缺省）或 `FREE`）。 |
| D-Q47c-3 | **按天计**：天数 = ⌈（发布的结束 − 开始）/ 24 小时⌉，至少 1；费用 = max（最低使用费，Σ 所用计费规则的日价 × 天数）。同一条件选几个选项都按一个规则计；地区不计费；没有用计费规则的发布不收（也不收最低使用费）。没有后台的按月套餐。 |
| D-Q47c-4 | **提交时扣款**：提交在审批步骤之后、与审批请求同一事务，以 Q87 的 `QB_FEE_CHARGE`（种类 `AUDIENCE`，来源为发布，明细：规则、日价、天数、最低费）记费。**预付**的商户可用现金不足、**后付**的未付合计加本次超过信用额，提交即被拒 422 `QB_AUDIENCE_FEE_FUNDS`（带费用、可用现金或剩余信用额），什么也不写；页面引导充值进现金账户。费用为 0 时不记费。 |
| D-Q47c-5 | **退回**：审核驳回、商户撤回审核回到草稿、审核过期撤回（`ADVANCE`）时，以一条负的冲正费用（`AUDIENCE_REFUND`，引用原费用）全额退回：预付的回到现金账户，后付的冲减应收（已出账单的进入下期账单的抵减）。是否在上线后（提前结束、预算或名额用完、被下架）按未用天数退回，是受控参数 `qb.audience.fee.refund-unused-days`（缺省关：上线后不退）。 |
| D-Q47c-6 | **记账与运营商**：照 Q87 D-Q87-8：收入 4120、消费税 2150、税率 `qb.fee.tax-rate`；记在商户所属运营商名下、并入 Q48b 的结算与分成，规则不变。不另开收据：Q87 的月度账单（预付的为已付账单）即凭证；发布页显示明细。 |
| D-Q47c-7 | **开关**（受控参数，四眼）：`qb.audience.billing.enabled`（总开关，缺省关：一切照旧免费）、`qb.audience.price.fallback`（D-Q47c-2）、`qb.audience.fee.refund-unused-days`（D-Q47c-5）。开关打开后只对此后提交的发布收费。 |
| D-Q47c-8 | **显示**：向导"客户匹配"一步在每种规则旁写该商户适用的日价，并按当前期间给出估算（含最低使用费）；检查一步与"预计最大支出"加上规则费用与可用现金 / 剩余信用额；发布页显示扣费明细与退回；运营商后台与总部后台各有价目表页。答题者一侧不变。 |

## 3. 后端（`backend/quizbuks`）

### 3.1 迁移

- `qb_audience_rule`（普通表：`rule_key` 唯一、`enabled`、`billable`、`sort_order`、`version`）。
- `qb_audience_price_list`（普通表：`owner` 唯一、`minimum_fee`、`effective_at`、`version`）与 `qb_audience_price`（普通表：`price_list_id` → 价目表、`rule_key`、`daily_price`，`(price_list_id, rule_key)` 唯一）。改价的历史在审计中（与其他普通表相同）。
- 费用记录、科目、现金账户都是 Q87 的，不在本迁移中。

### 3.2 改动

- `audience`：`QbAudienceRules`、`QbAudiencePrices`（实体与默认数据视图 `processOnlyWrites`；价目表经 `OperatorScopes` 按所有者限定）；`AudienceBilling`（纯 Java：天数、所用计费规则、价目表的选择与回落、最低费、明细）；流程 `QB_AUDIENCE_RULE_SET`（总部）、`QB_AUDIENCE_PRICES_SET`（运营商负责人与总部）；`QB_PUBLICATION_AUDIENCE_SAVE` 拒绝关闭的规则。
- `fee`（Q87）：`FeeKinds` 加 `AUDIENCE` 与 `AUDIENCE_REFUND`。
- `publishing`：提交在审批之后算费用、经 `QB_FEE_CHARGE` 记费（预付 / 后付的判断照 Q87 的 `FeeRules`）；`PublicationReadiness` 的 `maxSpend` 加费用；驳回（审批结果）、撤回（`QB_PUBLICATION_WITHDRAW`）、审核过期（`ADVANCE`）三处以同一子流程 `QB_AUDIENCE_FEE_REFUND`（`subProcessOnly`）退回；开关 `refund-unused-days` 打开时，结束（`ADVANCE`、管理员与运营商的结束）按未用天数退回同样经它。锁序照 Q87（发布锁 → 审批锁 → 钱包锁）。
- `setup`：`QbParams` 加三个受控参数；`QB_SETUP` 建四条规则记录与空的直营价目表（价格 0、最低费 0）。
- 模板：`qb.sponsor.audience-prices`（该商户适用的价目、开关）；发布列表与详情给出规则费用；`qb.operator.audience-prices`；`qb.admin.audience-rules`、`qb.admin.audience-prices`、`qb.admin.audience-fees`。
- 文案三语：规则的状态、错误码、参数名、费用种类。

## 4. 前端

- **商户后台**：向导的日价与估算、检查一步的费用与可用现金 / 信用额（不足时链到现金账户的充值）、发布页明细与退回、`QB_AUDIENCE_FEE_FUNDS` 的提示。
- **运营商后台**（管理后台的第二份构建）：价目表页（每种规则的日价、最低使用费、理由、改动记录）。
- **总部后台扩展**：规则目录页（开放、计费、排序）、各所有者的价目表、规则费用列表。
- **App**：不变。

## 5. 测试

- core：`AudienceBillingTest`（天数取整与下限、最低使用费、多选项按一个规则、不计费与关闭的规则、价目表回落 `DIRECT` / `FREE`）。
- IT：`AudienceBillingIT`（开关关着不收；运营商的商户按该运营商的价、直营按 `direct`、没有价目表按回落；提交记费、预付扣现金、后付记应收；不足或超信用额时提交被拒且什么也没写；驳回、撤回、审核过期全额退回；上线后按开关退或不退未用天数；改价只影响此后提交的；关闭的规则不能再选；运营商只能改自己的价目表、看不到别人的；运营商结算含规则费用）；`SetupIT`；场景快照随新参数与记录后移（答案按新位置改写，得分不变）。
- 前端：向导的价目与估算、检查一步的不足、价目表页；axe。
- e2e：运营商设价 → 打开开关 → 商户选条件、看到费用、提交扣款 → 驳回后退回。

## 6. 文档

- `plans/Q47-audience.md` 的 D-Q47-2（规则目录在数据库中）、D-Q47-7（价格按运营商、按天、最低费、提交时扣、经 Q87）；`02-design.md`、`backend/quizbuks/CLAUDE.md` 客户匹配一条、`operations.md`（开关、价目表、退回）、`03-plan.md`；本文件 §10 实施记录。

## 7. 估计（不含 Q87）

| 部分 | 天 |
|---|---|
| 迁移、规则目录、价目表、计费规则、参数 | 1.25 |
| 提交记费、三处退回、未用天数退回、运营商结算 | 1.25 |
| 模板与文案 | 0.5 |
| 商户后台、运营商后台、总部后台 | 1.5 |
| 测试、e2e、文档 | 1 |
| **合计** | **5.5** |

## 8. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 中 | 退回遗漏某条回到草稿或不上线的路径 | 共用一个子流程；IT 逐条覆盖；对账：已记费而发布是草稿或已驳回即差异 |
| 中 | 运营商之间的价格隔离 | 价目表经入口范围与 `OperatorScopes`；IT 覆盖 |
| 低 | 价格未设就打开开关 | 回落参数 `DIRECT` / `FREE`；直营价目表由 `QB_SETUP` 建为 0 |

## 9. 需要需求方确认的（2026-11-03 全部确认：第 1 条理解正确，新增规则当然要开发；第 2 条运营商自己定价格）

1. D-Q47c-1：规则目录建在数据库中（开放、计费、排序由总部管理）；条件的**种类**（怎样匹配、答题者怎样填）仍在代码中，新增一种条件仍要开发。这样理解"规则在数据库中建记录"对吗？
2. D-Q47c-2：价目表按运营商（直营由总部），最低使用费放在价目表里；运营商的**负责人**可改；没有价目表的运营商按直营的价（开关可改为免费）。
3. D-Q47c-3：费用 = max（最低使用费，Σ 日价 × 天数），天数向上取整；没用计费规则的发布不收最低费。
4. D-Q47c-4：提交时扣款（经 Q87 的现金账户；后付的受信用额），不足即不能提交。
5. D-Q47c-5：驳回、撤回、审核过期全额退回；上线后退不退未用天数由开关定，缺省不退。
6. D-Q47c-6：照 Q87 记在运营商名下并分成，不另开收据。
7. 顺序 Q86 → Q87 → Q47c，一个 PR，约 5.5 天（不含 Q87）。

## 10. 实施记录

Q87（现金账户、`QB_FEE_CHARGE`）尚未实现，需求方要求先开始，所以分成两步（第 7 条的"一个 PR"随之改为两个）：

- **Q47c-1（本次）**：规则目录、价目表、报价，开关缺省关、**不扣款**。
  - 迁移 V64：`qb_audience_rule`、`qb_audience_price_list`、`qb_audience_price`（没有 `effective_at`：改价即刻对此后的报价生效，历史在审计中；Q47c-2 提交时把明细冻结在费用记录上）。
  - **记录在第一次保存时建立**：没有记录的规则即开放、计费（`AudienceBilling.Rule.DEFAULT`），没有价目表的所有者即没有价（直营没有价目表 = 免费），`QB_SETUP` 因此不建规则记录与空价目表（与 D-Q47c-1、§3.2 不同：效果相同，少一种由设置流程补的数据）。
  - 流程：`QB_AUDIENCE_RULES_SET`（总部 `qb.audience.rules.write`，内容管理员：整表保存给出的规则，未知或重复的键 `QB_AUDIENCE_UNKNOWN` / `REPEATED`）；`QB_AUDIENCE_PRICES_SET`（总部 `qb.audience.prices.write`，财务：`owner` 缺省 `direct`，不存在的运营商 404）；
    `QB_OPERATOR_AUDIENCE_PRICES_SET`（运营商 `qb.operator.use`，入口范围给出运营商，`OperatorChecks.member` 后只许负责人 `QB_OPERATOR_NOT_LEAD`）；`QB_AUDIENCE_FEE_QUOTE`（商户 `qb.publication.write`，本人的发布，否则 404）给出报价与每种规则适用的日价，向导用它（不另建 `qb.sponsor.audience-prices` 模板）。都要求已验证邮箱、先查封禁。
  - 价目表整表保存：给出的规则写入、未给出的删除；锁 `qb.audience-prices:<所有者>`，规则 `qb.audience-rules`。
  - `QB_PUBLICATION_AUDIENCE_SAVE` 拒绝关闭的规则（`criteria[i].key`，`QB_AUDIENCE_RULE_CLOSED`）。
  - 参数（受控）：`qb.audience.billing.enabled`（false）、`qb.audience.price.fallback`（`DIRECT`）、`qb.audience.fee.refund-unused-days`（false，Q47c-2 才用）。
  - 模板：`qb.admin.audience-rules`、`qb.admin.audience-prices`、`qb.operator.audience-prices`（只有本运营商的）。
  - 测试：`AudienceBillingTest`、`AudiencePricingIT`。
- **Q47c-2（Q87 合入后）**：D-Q47c-4 至 D-Q47c-6：提交时经 `QB_FEE_CHARGE` 扣款、不足拒绝、三处退回、未用天数退回、运营商结算、费用列表与发布页明细，e2e 的扣款与退回。
