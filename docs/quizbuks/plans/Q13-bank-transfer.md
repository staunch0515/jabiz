# Q13 银行转账：充值与提现（计划，2026-10-22 确认）

需求方（2026-10-23）：增加一种充值方式——商家按平台提供的银行账户转账，后台的管理员与财务管理员确认后才真正增加 Kudos；
答题人同样可以提出取款请求并提供银行账户，之后由平台直接转到其银行账户。

Stripe（卡支付充值、Connect 自动转账）保持不变，银行转账是**另一种方式**，两者并存。

## 1. 已确认的决定（需求方 2026-10-22，四项都选了建议的做法）

| # | 问题 | 决定 |
|---|---|---|
| B-1 | 充值由谁确认 | **四眼**：财务管理员对照银行流水"登记到账"（实收金额、到账日、银行流水号），另一位管理员（内容或财务管理员，不能是登记人）审批后才入账。平台审批已强制"准备人不能审批" |
| B-2 | 实收与申请金额不同 | 按**实收**入账（审批内容中两者都显示）；申请金额只用于对账参考 |
| B-3 | 提现与 Stripe 自动转账的关系 | 答题人**选一种收款方式**：Stripe 或银行。选银行的人不参加每日自动转账，由本人提出请求；每人仍至多一笔未结束的转账（沿用现有的部分唯一索引） |
| B-4 | 提现手续费 | 受控参数 `qb.withdrawal.bank.fee`（固定 Kudos，从提现额中扣，`amount = fee + net`，与 Stripe 转账同一模型）；最低额沿用转账门槛 `qb.transfer.threshold` |
| B-5 | 提现审批 | **每笔都审批**（财务管理员，不能是本人）；批准后由财务在网银中打款，再在后台记"已打款"（银行流水号）或"退回"（组戻し，冲正） |
| B-6 | 打款方式 | 本阶段**逐笔人工打款**；全银协格式的批量付款文件（`FILE_ARCHIVE`）留作以后的 Q13c |
| B-7 | 范围 | 只支持**日本国内银行、日元**（与 Stripe Connect 的 JP 一致） |

## 2. 充值（Q13a）

### 2.1 流程

1. 商家在"充值"页选择"银行转账"，填金额（`qb.topup.bank.min` – `max`，受控参数）→ 流程 `QB_TOPUP_BANK_START`：
   建 `QbTopUp`（`method = BANK`，状态 `AWAITING`），由 `NumberSequence` 给出 7 位**汇款识别码**。
   页面显示平台的收款账户（受控参数 `qb.bank.receiving.*`：银行、支店、种类、账号、户名——改它走四眼，防止被改成别人的账户）、金额与识别码，
   并提示"汇款人名称前加上识别码"。
2. 商家汇款后点"已汇款"，填汇款人名称（カナ）与汇款日 → `QB_TOPUP_BANK_REPORT`：状态 `REPORTED`，财务得到一个待办（`qb.topup.bank.record`）。
   不报告也可以：财务凭识别码同样能登记。汇款凭证的上传（文件策略的读取权限只能声明一个，商家之间不能互相读到）留到 Q13c。
3. 财务管理员在后台"银行充值"列表中对照银行流水，点"登记到账"，填实收金额、到账日、流水号 → `QB_TOPUP_BANK_RECORD`：
   状态 `REVIEW`，`RequireApproval`（审批对象 `qb.topup-bank`，准备人 = 登记人，`ApprovalCase.reference` = 识别码与商家名）。
4. 另一位管理员审批：
   - 批准 → `QB_TOPUP_BANK_APPROVAL_RESULT`（商家钱包锁）：过账、钱包增加、`PAID`，发布 `qb.topup.paid`。
     之后与卡支付相同：补发待付奖励、恢复预算不足的发布、可开收据。
   - 驳回 → 回到 `REPORTED`（或 `AWAITING`），清空登记，可重新登记；驳回意见只给后台看。
5. 商家可取消未到账的申请（`CANCELLED`）；30 天既未报告也未登记的申请由每日任务置为 `EXPIRED`。
   过期之后钱才到的，财务仍可登记（`EXPIRED` 也可登记）。

### 2.2 模型与账本

- `QbTopUp` 加：`method`（`STRIPE` 缺省 | `BANK`，不可变）、`transferCode`（7 位，`NumberSequence`，唯一）、`requestedAmount`（商家申请的金额，不可变）、
  `payerName`、`transferDate`、`reportedAt`、`receivedOn`（日期）、`bankReference`、`recordedBy`、`recordedAt`、`reviewFrom`（驳回时回到的状态）、`approvalRequestId`。
  `amount` 不再不可变：银行转账登记时改为实收金额（B-2），驳回时恢复为申请金额；卡支付的从不改变。收据、对账、交易列表因此都按 `amount` 照旧。
- 状态加 `AWAITING`、`REPORTED`、`REVIEW`、`CANCELLED`（Stripe 的状态不变；数据库检查 `qb_topup_method` 让两种方式各用各的状态）。
- `(receivedOn, bankReference)` 唯一（部分唯一索引）：银行流水的同一笔只能登记到一笔充值。
- 分录：借 1120 银行存款 / 贷 2110 商家预收款（商家），来源 `QbTopUp`，参考号为识别码。记账时间为**批准的时刻**（到账日记在充值上）：
  审批晚了也不会记入已导出的期间。没有手续费分录。
- 每日对账不用改：既有的"充值分录"（每笔 PAID 的充值恰有一笔 2110 贷方、金额为 `amount`）同样适用于银行转账。
- 新商家累计上限（`NewSponsorLimit`）**不适用**：它防的是卡支付的拒付，银行转账没有拒付。
- 银行充值的退款不在本阶段：到账前有四眼审批，错误的入账留待以后的冲正流程。

### 2.3 界面

- 商家后台：充值对话框可选"银行卡 / 银行转账"；银行转账的页面 `/wallet/bank/<编号>`（收款账户、金额、识别码、"已汇款"表单、取消、状态）；列表中显示方式与识别码。
- 后台扩展：财务菜单"银行转账充值"（按状态与识别码筛选、登记到账的对话框）与每笔的页面（商家的申请与报告、财务的登记、审批面板）。
  审批人（内容管理员）看不到财务的充值列表，所以两个页面读专用模板 `qb.admin.bank-topups`（权限 `qb.topup.bank.approve`，只含银行转账需要的字段）。
- 通知：到账后沿用卡支付的 `qb.topup.paid`（邮件与收据）；被驳回不通知商家（财务会重新登记）。

## 3. 提现（Q13b）

### 3.1 收款银行账户

- 新实体 `QbBankAccount`（P，每人一行）：银行代码（4 位）、银行名、支店代码（3 位）、支店名、种类（普通 / 当座 / 储蓄）、账号（7 位）、户名（全角カナ）、`changedAt`。
  - 账号以 `f.masked(qb.payout.bank.reveal, LAST4)` 保存：读接口、历史、审计中一律遮蔽。财务打款时经平台的 `reveal` 逐值显示，平台记入 `sys_reveal_record`。
  - 户名不遮蔽，审核人要对照。
- 本人经 `QB_BANK_ACCOUNT_SAVE` 填写或修改，要求已验证邮箱。修改后：
  - 给本人发通知邮件；
  - **72 小时内不能对新账户提现**（防账号被盗后改账户），受控参数 `qb.withdrawal.bank.cooldown-hours`。
- 同一个银行账户不能属于两个用户：422 `QB_BANK_ACCOUNT_IN_USE`，同时给财务一个待办（分析 1 §197 的"提现前银行账户唯一性检查"）。
- 收款方式：`QbPayoutAccount` 旁记 `payoutMethod`（`STRIPE` | `BANK`），本人在 App 中切换；有未结束的转账时不能切换。

### 3.2 流程（沿用 `QbTransfer`，加 `method = BANK`）

1. 答题人在 App 的钱包页点"提现到银行"，填金额（门槛 ≤ 金额 ≤ 可转余额，`TransferRules.transferable` 与奖励保留 4.4 照旧）
   → `QB_WITHDRAWAL_REQUEST`（用户钱包锁）：检查开关、封禁、暂停、冷却期，以及没有未结束的转账；
   建 `QbTransfer`（`REVIEW`，`fee` 与 `net` 按参数算出，冻结银行账户的快照），审批对象沿用 `qb.transfer`（准备人 = 本人）。
2. 财务审批：
   - 批准 → `QB_TRANSFER_APPROVAL_RESULT`（已有）：重查后过账（借 2120 / 贷 2130 实付、4110 手续费），状态 `SENDING`，含义为"待打款"。
   - 驳回 → `REJECTED`（与现在相同，暂停该用户，直到财务放行）。
3. 财务在网银中打款，然后在后台：
   - 点"已打款"，填流水号与日期 → `QB_WITHDRAWAL_PAID`：借 2130 / 贷 1120，状态 `SENT`；
   - 或者点"退回"（账号错误等），填原因 → `QB_WITHDRAWAL_RETURNED`：冲正发出的交易，状态 `FAILED`。
4. 消息（Q7c 已有的 `qb.transfer.sent` / `failed` 消息）、App 的流水、用户钱包同步（`QB_TAKER_WALLET_SYNC`）、对账（2130 与转账状态相符）都沿用。
   对账另加"`BANK` 转账的结算在 1120"。
5. 每日自动转账 `QB_TRANSFER_CREATE` 跳过收款方式为 `BANK` 的用户。

### 3.3 界面

- App：
  - 钱包页的"收款方式"（Stripe / 银行）；
  - 银行账户表单（银行与支店代码按位数校验，户名校验全角カナ）；
  - 提现表单（显示手续费与实付）；
  - 提现的状态与历史。
- 后台扩展：财务菜单"银行提现"。
  - 待审批的转去平台审批页。
  - 待打款的列表显示户名、银行、支店、种类，以及遮蔽的账号；"显示账号"走平台的 `reveal`。
  - 列表上有"已打款 / 退回"表单。

### 3.4 Q13b 实施记录

- **迁移 V21**：`qb_bank_account`（每人一行，`(银行, 支店, 种类, 账号)` 唯一）；`qb_transfer` 加 `method`、账户快照、`paid_on` / `paid_reference`（同一条流水只付一笔：部分唯一索引）、`paid_by`、`returned_by`；
  `SENT` 的约束改为"有 Stripe 转账，或（BANK）有流水与打款人"，BANK 不进入 `CHECK` / `REVERSED`。
- **收款方式**记在 `QbBankAccount.payoutMethod`（没有银行账户即 Stripe），不放在 `QbPayoutAccount`：只用银行的答题人没有 Stripe 账户。
- **答题人的流程沿用 `qb.payout.onboard`**（收款设置的权限），不另加答题人权限；财务新加 `qb.payout.bank.reveal`、`qb.withdrawal.pay`。
- **账户已被他人使用**：不抛错（抛错会回滚待办），返回 `saved=false`、`reason=QB_BANK_ACCOUNT_IN_USE` 并给财务待办。
- **审批内容**：银行、支店、种类、户名与平台遮蔽后的账号（7 位账号全部遮蔽为 `****`：后四位会泄露大半；待办与邮件同样）；转账上的账户快照不可变，审批即绑定它。
- **批准后**：与 Stripe 相同的过账；不开作业，给 `qb.withdrawal.pay` 一个"请打款"待办（`withdrawal:<转账>:pay`），记已打款或退回时关闭。
- **每日转账**跳过收款方式为银行的答题人（`transfers-due` 与 `planOf` 的 `BANK_METHOD`），每小时的核实跳过 BANK（模板与 `inDoubt`）。
- **账号的显示**：平台对 7 位账号的 `LAST4` 遮蔽给出 `****`；财务打款时在"银行提现"页逐条显示（平台的 `reveal`，记入 `sys_reveal_record`）。
- **独立评审的修正**：批准该笔的人不能再记已打款或退回（`QB_WITHDRAWAL_APPROVER`，四眼到钱离开为止）；月报加 `WITHDRAWAL_PAID`（借 2130 / 贷 1120，原来落入 `OTHER`）；
  `qb.admin.transfers` 加 `method`（银行提现的 `externalAccountId` 是银行账户编号）；审核规则不要求审核时 422 `WITHDRAWAL_UNAVAILABLE`（`NO_REVIEW`）而不是 500；App 的最低额与服务器相同（门槛与手续费 + 1 取大）。
- **已知并接受**：保存账户时能得知该账户是否已被他人登记（`QB_BANK_ACCOUNT_IN_USE`）；每次都给财务待办，并以 `jabiz.process.rate-limits` 限制每人每小时 10 次。
- **未做**：场景 `bank-withdrawal.yml` 与 e2e（集成测试 `BankWithdrawalIT` 覆盖流程）；退回后不暂停答题人（多为账号错误，请其改账户）。

## 4. 权限（`QbRoles`）

| 权限 | 角色 |
|---|---|
| `qb.topup`（已有：商家发起、报告、取消银行充值，与卡支付相同） | `QB_SPONSOR` |
| `qb.topup.bank.record`（登记到账） | `QB_ADMIN_FINANCE` |
| `qb.topup.bank.approve`（审批规则要求的权限，再加上平台的 `approval.decide`） | `QB_ADMIN_CONTENT`、`QB_ADMIN_FINANCE` |
| `qb.payout.bank.reveal`（Q13b：看账号明文） | `QB_ADMIN_FINANCE` |
| `qb.withdrawal.pay`（Q13b：记已打款 / 退回） | `QB_ADMIN_FINANCE` |

审批规则、限额与收款账户都是受控参数，只经 `CONTROL_CHANGE_*`（超级管理员提议、财务发布）。新权限在既有安装上要运行一次 `QB_SETUP`。

## 5. 安全要点

- 钱只在两人确认后才进入商家钱包：登记与审批不能是同一人，由平台审批强制，测试覆盖。
- 平台收款账户是受控参数：不能由一个人改成别人的账户。
- 答题人的账号是遮蔽字段：明文只有财务在打款时逐值显示，每次留记录。银行账户修改有冷却期并邮件通知。
- 所有新流程：
  - 声明权限、要求已验证邮箱；
  - 金额为整数 Kudos，检查 `> 0`；
  - 在对应的钱包锁内重读状态；
  - 不改变现有的锁序（只取 `qb.wallet:SPONSOR:<商家>` 或 `qb.wallet:TAKER:<用户>`）。
- 完成后进行安全评审（涉及钱、权限、敏感数据）。

### 5.1 Q13a 评审后的补充（实施记录）

- **商家不能批准自己的充值**：平台只拒绝准备人（登记人）本人的审批。一位管理员若同时有商家账户，可以批准自己的充值。
  因此审批结果读取该请求的审批记录，审批人中有该商家即不入账：登记退回，并给财务一个待办（`task.qb.topup-bank.approved-by-sponsor`），由别人重新登记、审批。
- **流水号规范化**：去掉空白、全角转半角、转大写后保存与比较（`BankTransfers.reference`），同一笔流水换种写法也只能登记一次。
- **每次登记各是一个审批案件**：审批内容含登记时刻（到毫秒）。驳回后以相同金额与流水重新登记，也会重新请求审批，而不是沿用已有的决定。
- **退回时给财务待办**：驳回与上一条的退回都建待办（来源键为充值与请求），财务不会漏掉。
- 数据库另加检查：`review_from` 只能是可登记的四个状态；`REVIEW` 必有审批请求。
- **已知问题（低）**：商家本人数据视图的实体接口（`GET /api/datasets/…/entities/{id}`）没有字段白名单，会返回财务的流水号、登记人编号等字段（模板 `qb.sponsor.topups` 不返回）。
  不影响入账；以后可改为遮蔽字段（`f.masked`）。

## 6. 阶段、迁移与测试

| 阶段 | 分支 | 内容 |
|---|---|---|
| Q13a 银行充值 | `1.2/quizbuks-13a-bank-topup` | 迁移 V20（`qb_topup` 的新列与状态、识别码序列、凭证文件策略），流程、模板、商家后台、后台扩展、对账一项 |
| Q13b 银行提现 | `1.2/quizbuks-13b-bank-withdrawal` | 迁移 V21（`qb_bank_account`、`qb_transfer.method` 与账户快照、收款方式），流程、App、后台扩展、对账一项 |
| （以后）Q13c | — | 全银协批量付款文件、银行流水导入与自动匹配（F-05） |

- 测试：
  - 集成测试 `BankTopUpIT`：四眼、本人不能审批、实收不同、过期后登记、取消、对账；
  - 集成测试 `BankWithdrawalIT`：门槛、保留、冷却期、账户唯一、批准后打款与退回、驳回暂停、自动转账跳过；
  - 场景 `bank-topup.yml` 与 `bank-withdrawal.yml`；
  - 前端 Vitest、e2e 各一条。
- 预计不需要改平台（遮蔽字段、审批、文件策略、单据号、受控参数都已有）；实施中若发现需要，先在平台分支做。
- Q12b（问卷结果）尚未开始。谁先做，谁用 V20。

## 7. 风险

- **法规**：用 Kudos 兑现金、代收代付可能涉及资金决済法（前払式支払手段 / 资金移动业）或收款代理的要件，**需要法务确认**；本计划只做技术实现。
- **人工操作出错**：打款金额与账号以后台显示为准，"已打款"要填流水号；全银协文件（Q13c）可减少手工输入。
- **欺诈**：银行提现没有 Stripe 的身份核实（KYC）。现有的保护有：
  - 已验证邮箱、一人一次、奖励保留期；
  - 每笔审批、冷却期、账户唯一。

  如需要，可加每月提现上限（受控参数）。
