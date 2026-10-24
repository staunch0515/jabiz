# Q18 银行转账充值的设置与管理员手工加币

需求方 2026-10-23："admin的手工充值功能在什么地方，没有看见"；银行转账充值的对话框显示"业务参数 qb.topup.bank.min … 没有生效的值"。
确认（2026-10-23）："两个都要"（银行转账审核与管理员手工加币，第二人审批）；"简化配置页面和报错改文案，都做"；
手工加币的记账两种都要（每次由管理员选择）：系统外收款（借 1120 银行 / 贷 2110，有收据）与赠送 / 推广（借新科目 5120 推广费用 / 贷 2110，无收据）。
顺序：Q17 → 平台 16zc → 同步 → Q19 → Q18。分两部分：Q18a（设置页与文案）与 Q18b（手工加币）。

## Q18a 银行转账充值的设置与文案

| 部分 | 内容 |
|---|---|
| 报错 | `QB_TOPUP_BANK_START` 以 `LoadParams.optional`（平台 16zc）读取七个设置：没有生效值即"暂未开放"（`QB_TOPUP_BANK_UNAVAILABLE`，文案改为"银行转账充值暂未开放。请使用银行卡充值，或联系我们。"），不再列出参数 |
| 商家后台 | 收款账户或金额范围未设置时，充值对话框中的"银行转账"不可选，并说明暂未开放（以前可以选，提交后才报错） |
| `QB_BANK_SETTINGS_PROPOSE` | 一次提出七个设置（银行、支店、账户类型、账号、名义、最小与最大金额，加理由；权限 `control.propose`）：只为与现行值不同的项各提出一个受控变更，尚未声明的参数带上类型（与 `QB_SETUP` 相同）；本人对这些参数的未处理提案（包括安装时的初始提案）先撤回；他人的未处理提案、最小大于最大、全部未变各有业务码（`QB_BANK_SETTINGS_OPEN` / `RANGE` / `UNCHANGED`） |
| 模板 `qb.admin.bank-settings` | 七个参数的现行值与是否存在（`qb.admin.finance.read`） |
| `QB_BANK_SETTINGS_PUBLISH` | 一次发布他人对这七个设置的全部待发布提案（权限 `control.publish`，二次验证）：在一个事务中逐项执行平台的 `CONTROL_CHANGE_PUBLISH`，全部成功或全部不发布（评审 Q18a：逐个发布时中途失败会让收款账户一半新一半旧）；发布后最小金额大于最大金额即拒绝，没有可发布的提案为 `QB_BANK_SETTINGS_NOTHING`。提出与发布按名称 `qb.bank-settings` 轮流（锁），一次最多读 500 个待发布的参数提案，更多即拒绝 |
| 后台 `/qb/finance/bank-settings` | 现行值与是否已可用；待发布的提案（与现行值并列，提出人与理由）；发布人一键发布他人提出的全部提案；提出人的表单从现行值开始。提出也需二次验证 |

测试：`BankTopUpSettingsIT`（未发布任何设置时只说暂未开放；他人的未处理提案、范围、未变；本人的初始提案被撤回；他人发布后商家读到；只改一项即一个提案）、
`BankTopUpIT`（原有）、商家 Vitest（未设置时不可选）、扩展 Vitest `settings.test.tsx`、e2e `a11y.spec.ts`（设置页过 axe）。

## Q18b 管理员手工加币（2026-10-24 确认，已完成，待评审）

财务管理员选择商家、金额、种类与理由，另一位管理员审批（准备人不能审批，平台的审批流程保证），批准后入账、加到钱包。沿用银行转账充值的"登记 → 审批 → 结果"流程，没有冲正（需求方 2026-10-24：暂不做）：

| 部分 | 内容 |
|---|---|
| 数据 | `QbTopUp` 的新方式 `MANUAL`，新字段 `manualKind`（`PAID_OUTSIDE` / `GRANT`）、`manualReason` 与 `sponsorVisible`（商家数据集按它过滤：手工加币只在 `PAID` 后可见）；迁移 V23 改约束（方式各守各的状态与字段；`PAID_OUTSIDE` 须有收款日与流水号，`GRANT` 不得有；审核中的手工加币先写入再取得审批请求，同一事务）。编号取自转账码序列（`transferCode`），分录参照为 `mn_` + 编号 |
| 流程 | `QB_TOPUP_MANUAL_RECORD`（新权限 `qb.topup.manual.record`，财务角色）：商家须已通过审核、不是本人；`PAID_OUTSIDE` 的收款日不得在未来、流水号未被任何充值用过（含银行转账充值）；建记录为 `REVIEW` 并请求审批（新审批对象 `qb.topup-manual`，规则 `qb.topup-manual.review` 由 `QB_SETUP` 提出，审批人须有 `qb.topup.bank.approve`）；没有规则即拒绝（`QB_TOPUP_MANUAL_NO_REVIEW`）。`QB_TOPUP_MANUAL_APPROVAL_RESULT`（审批事件，`internal()`）：批准即记账（`PAID_OUTSIDE` 借 1120，`GRANT` 借 5120，均贷 2110）、加钱包、结算待付奖励、发 `qb.topup.paid`，`PAID` 并对商家可见；驳回或商家本人批准即 `CANCELLED`（流水号释放，可重新登记；本人批准另给财务待办） |
| 科目 | 新科目 5120 推广费用（`QB_SETUP` 开设）与映射参数 `qb.journal.account.5120`（受控）。**升级时**：重新运行 `QB_SETUP` 后，先经四眼发布 `qb.journal.account.5120` 的映射，再批准第一笔赠送，否则该月的分录导出因未映射而被拒 |
| 收据与邮件 | 只有 `PAID_OUTSIDE` 有收据（`QB_RECEIPT_GRANT` 拒绝赠送的；收据模板也不返回赠送）；`GRANT` 发邮件 `qb.topup.granted`（"赠送"文案），`PAID_OUTSIDE` 照常 `qb.topup.paid` |
| 商家看到 | 审批通过后才出现在充值记录与交易中，标"系统外收款"或"QuizBuks 赠送"及编号；收据按钮只给 `PAID_OUTSIDE` |
| 报表与核对 | 月报新增 `MANUAL_TOPUP`（按 `mn_` 参照与 `BANK_TOPUP` 区分）与 `GRANT` 两类及 5120 一列（16 行）；余额表多一行 5120；核对（充值分录）照常适用 |
| 后台 | `/qb/finance/manual-credits`（`qb.topup.bank.approve` 可读）：列表（状态筛选）、"新建加币"（`qb.topup.manual.record`：已通过审核的商家下拉、种类、金额、理由，系统外收款再填收款日与流水号）、详情页用平台的 `ApprovalPanel` 审批 |

测试：`ManualCreditIT`（系统外收款经审批入账、收据、月报与核对；赠送记 5120、无收据；驳回取消并可重登同一流水号；校验（非商家、种类与流水号、未来日期、本人、权限）；商家本人批准不入账；读取权限；数据库约束）、
`AdminFinanceIT` / `SetupIT` / `JournalExportIT` / `ProcessAccessIT` 与场景（新参数与规则）、扩展 Vitest `manual.test.tsx`、商家 Vitest（手工加币行与收据按钮）、e2e `a11y.spec.ts`（手工加币页与对话框过 axe）。
