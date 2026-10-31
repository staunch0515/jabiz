# Q48e 多个运营商互相隔离：剩余部分（计划，2026-10-31）

需求方（2026-10-31）："现在可以做 Q48 了吗？""开始"。

## 1. 已经完成的（Q59c-2，#362）

Q48 计划 §5 要求的两项平台能力由平台 17y（入口的范围，决策 D64）提供，QuizBuks 的隔离已在 Q59c-2 实现并合并（`docs/quizbuks/plans/Q59-languages.md` §6）：

- **入口的范围**：入口 `operator` 的范围值是会话用户当前所在、正在运营的运营商（`OperatorScope`）；成员被移出或运营商暂停，下次刷新即失效。
- **数据视图**：运营商的权限读得到的缺省数据视图都以 `OperatorScopes` 约束（商户字段经 `QbSponsorOperator` 对照，只有发布编号的经卡片冻结的 `operatorId`）；
  `OperatorScopesIT` 检查每个数据视图与模板都受约束或列明理由。
- **审批与待办**：商户入驻与发布的审批请求记商户的运营商（`ApprovalCase.scope`），待办随之；别的运营商看不到、决定为 404。
- **流程**：封禁与解封、下架与恢复与结束、兑换作废、电子奖品处理以 `OperatorChecks.ownSponsor`（`RequireScope`）核对对象的商户。
- **测试**：`OperatorIsolationIT`（两个运营商互相看不到、对对方的动作 404、总部看到全部）。

所以 Q48e 原计划的"平台 4–5 天 + QuizBuks 3–4 天"大部分已经完成。本计划只列剩下的。

## 2. 盘点（2026-10-31）发现的问题

| # | 问题 | 处理 |
|---|---|---|
| G1 | `QB_REDEMPTION_VOID` 与 `QB_DIGITAL_PRIZE_RESOLVE` 只核对对象的商户（`ownSponsor`），不像运营商的其他动作那样先 `OperatorChecks.member`（在动作之内再查成员关系与运营商是否有效，共享锁 `qb.operator-member:<用户>`）：成员被移出后，访问令牌到期前（≤ 15 分钟）仍能作废与处理 | **修**：运营商会话（有范围）时先 `member`；总部照旧 |
| G2 | 现金、兑换、电子奖品三个列表模板整张联 `QbProfile`：运营商看得到自己商户的答题者的显示名 | **保留**：处理兑换与争议需要知道是哪位答题者；只限自己商户的行。写进 `OperatorScopesIT` 的理由 |
| G3 | 范围按商户**当前**的运营商：商户改派后，新运营商看得到它以前的发布；结算按卡片上线时冻结的运营商 | **保留**（照管商户的是现在的运营商；收入归上线时的运营商，Q48b §1），写进运营手册 |
| G4 | 内容与头像的文件、`QbStatsRun`、国家、参数模板不分范围 | **保留**：文件编号随机且答题者本来就能读；其余是参考数据 |
| G5 | 经开拓链接注册的商户，首次入驻的审核记的是按语言的运营商，之后的指派才改为开拓的运营商（Q59c-2 已知限制） | **改**：首次审核归开拓的运营商（§3 第 4 项，Q48e-1） |

## 3. 需求方的决定（2026-10-31）

1. **分成方案**：五种方案，每个运营商选一种（"每个运营商从这里面选一种"）；固定费按日元，页面照规矩不显示货币（显示为点数）：

   | 方案 | 每月固定费 | 平台分成 |
   |---|---|---|
   | 1 | 0 | 50% |
   | 2 | 20,000 | 40% |
   | 3 | 40,000 | 30% |
   | 4 | 60,000 | 20% |
   | 5 | 100,000 | 10% |

   需求方给的是人民币 1,000 / 2,000 / 3,000 / 5,000 元，"全部按日元算"：按 1 元约 20 日元取整，是受控参数，可以改。
2. **钱都在平台**："运营商不管钱，钱全部在平台。算好账后，平台给运营商……直接给运营商在平台的账户，需要提现，办理提现手续。"
3. 运营商自己下载结算单 PDF：**先不做**（"按建议"）。
4. 经开拓链接注册的商户的首次审核：**交给开拓的运营商**（"按建议"）。
5. 提现的最低额 10,000、手续费由平台承担（需求方没有另给，按建议；都是受控参数）。

## 4. 分期

### Q48e-1：补漏与首次审核（约 1 天）

- **G1**：`RedemptionProcesses.voidPrize`、`DigitalPrizeProcesses.resolve` 在有范围的会话中先 `OperatorChecks.member`。
- **首次审核归开拓的运营商**（G5）：商户后台记着开拓链接的键时，`QB_SPONSOR_APPLY` 的输入带上它（`prospectKey`，可选）；申请流程在指派之前按 `QB_PROSPECT_CLAIM` 的规则认领（只为没有归属的、所有者是运营商的、邮件已发出的候选），
  认领成功即指派给开拓的运营商，审批请求随之记它；否则照旧按语言。前端在申请时带上键，申请之后不再单独调用认领。锁序同 Q59c-1（账户锁 → 指派的锁）。
- **G2–G4** 写进 `OperatorScopesIT` 的理由与 `operations.md`；`backend/quizbuks/CLAUDE.md`、`03-plan.md`、`Q48-operators.md` 改为 Q48e 由 Q59c-2 与本计划完成。

### Q48e-2：分成方案、运营商账户与提现（约 4 天）

- **方案**：受控参数 `qb.operator.plan.<1..5>.fee`（日元，≥ 0）与 `qb.operator.plan.<1..5>.share`（0–1），由 `QB_SETUP` 提出上表的值（另一位管理员发布）；
  取代 `qb.operator.platform-share`（从 `QbParams.ALL` 去掉，已有安装中的行留着不再读）。
- **运营商的方案**：普通表 `QbOperatorPlan`（运营商、方案、自哪个月起、状态、审批请求），只经 `QB_OPERATOR_PLAN_SET`（总部 `qb.admin.operators.write`，提出：方案与生效月份，须晚于当月）
  与审批结果 `QB_OPERATOR_PLAN_APPROVAL_RESULT`（`internal()`）写入：审批对象 `qb.operator-plan`、规则 `qb.operator-plan.review`（由 `QB_SETUP` 提出，审批人同运营商结算的付款）。
  某月用"生效月份 ≤ 该月的最后一个已批准的"，没有即方案 1。运营商在自己的后台看到现行与待生效的方案。
- **月结算**（`QB_OPERATOR_SETTLEMENT_RUN`，照旧每月 2 日）：分成 = 抽成 ×（1 − 方案的平台分成）向下取整；应付 = 分成 − 固定费 − 获客费用 − 结转进来的；为负即 0、差额结转到下月
  （"从以后的收入里先扣"，不另收）。结算记方案、固定费（迁移给 `qb_operator_settlement` 加列）；过账：分成借 5130；应付贷 2140（运营商的账户）；收回的固定费与获客费用贷 4110。
  结算签发即记入账户（状态 `CREDITED`），不再逐张付款：`QB_OPERATOR_SETTLEMENT_PAY` 与其审批对象停用（已在 `PAYING` 的照旧可被批准或驳回，不再新建）。
- **运营商账户**：余额 = 已记入的应付之和 − 未被驳回的提现之和（V39 以来已 `PAID` 的结算算作已提现）。模板 `qb.operator.account`（运营商：自己的余额、明细：每月记入、提现）与 `qb.admin.operator-accounts`（财务：全部运营商）。
- **银行账户**：普通表 `QbOperatorBankAccount`（每个运营商一行，同答题者的规则 `BankAccounts`：全银格式、片假名户名），只经负责人的 `QB_OPERATOR_BANK_ACCOUNT_SAVE`（成员关系、二次验证、改后 `qb.operator.withdrawal.cooldown-hours` 小时内不能提现）。
- **提现**：普通表 `QbOperatorWithdrawal`，只经：
  - `QB_OPERATOR_WITHDRAWAL_REQUEST`（运营商负责人，二次验证，锁 `qb.operator:<运营商>`）：金额为整数、≥ `qb.operator.withdrawal.min`、≤ 余额，同时至多一笔未结束的；冻结银行账户的副本。
  - `QB_OPERATOR_WITHDRAWAL_PAY`（财务，`qb.operator.settlement.pay`）：登记银行付款日与流水号，请另一位管理员批准（审批对象 `qb.operator-withdrawal`）；批准后过账 2140 / 1120、`PAID`，驳回回到 `REQUESTED`。
  - `QB_OPERATOR_WITHDRAWAL_CANCEL`（运营商，付款登记之前）、`QB_OPERATOR_WITHDRAWAL_REJECT`（财务，填理由，运营商看得到）。
  手续费由平台承担：付出的就是申请的金额。
- **页面**（管理后台扩展）：运营商的"结算"页改为"账户"：余额、方案（现行与待生效）、明细、银行账户、提现申请与记录；总部的"运营商结算"页加"提现"与"方案"（提出变更、审批面板）。
  运营商的页面显示点数（不显示货币），总部的财务页照旧按 JPY。
- **月报**：`qb.admin.monthly` 的 `OPERATOR_PAID` 改为按提现的来源；固定费归入 `OPERATOR_SHARE`（同一笔过账）。`AdminFinanceIT` 的逐行期望随之改。

## 5. 测试清单

- Q48e-1：`OperatorIsolationIT`（移出的成员不能作废、不能处理电子奖品）；`ProspectClaimIT`（经开拓链接首次申请：指派与审批请求记开拓的运营商、按语言的运营商看不到；没有键或不可认领即按语言）。
- Q48e-2：`OperatorSettlementIT`（五种方案的分成与固定费、不够扣的结转、方案的四眼与按月生效、没有方案即方案 1）；
  `OperatorWithdrawalIT`（余额、最低额、超过余额、同时一笔、银行账户的冷却、付款的四眼与过账、驳回与取消、别的运营商 404、移出的成员不能申请）；`AdminFinanceIT`（月报的分类）；
  `OperatorScopesIT` 覆盖新模板；扩展的页面测试与 axe；`QbParamsTest` / `SetupIT`（新参数与规则）。

## 6. 风险

- Q48e-1 给作废与处理加一次成员关系的读取与共享锁：不在答题路径上，`AttemptStatementBudgetIT` 不变。
- Q48e-2 改变结算的付款方式：已在 `PAYING` 的旧结算照旧结束；已 `ISSUED` 未付的旧结算在新模型中算作已记入账户（迁移不改数据，余额模板这样读）。
- 首次审核的认领改变申请流程的指派顺序：沿用账户锁 → 指派的锁。
