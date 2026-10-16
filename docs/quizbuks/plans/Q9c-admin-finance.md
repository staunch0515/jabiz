# Q9c 财务后台页面与月报（计划，待确认）

分支 `1.2/quizbuks-9c-admin-finance`（从 `1.2/quizbuks` @ Q9a 合并后拉出）。只改 `.jabiz-app-paths` 中的路径，不改平台。

## 1. 目标与范围

财务支持（`QB_ADMIN_FINANCE`）与超级管理员在后台扩展（Q9a 的 `backend/quizbuks/admin-extension/`）中看到并处理资金：

| 需求 | 内容 | 现状 | Q9c |
|---|---|---|---|
| P-03 提现审批 | 列表含账户、手续费、状态；批准 / 拒绝 | 后端已有（Q6b：大额以审批规则 `qb.transfer.review`、`qb.payout.review`；`QB_TRANSFER_HOLD_RELEASE`）；页面只有通用后台的模板与审批收件箱 | 转账监控页与大额审批页（审批面板绑定转账内容） |
| F-06 余额校验 | 每日核对的结果与差异 | 后端已有（`QB_RECONCILE_RUN`、`qb.admin.recon-runs` / `recon-items`）；只有通用模板页 | 核对页：每次运行与其差异、跳到相关充值 / 转账 / 用户 |
| F-04（显示） | 充值的原币、手续费、流水号 | 数据已有（`qb.admin.topups`） | 充值监控页（含退款、争议与差额） |
| F-08 财务报表 | 月度汇总与期末余额表，可导出 | 设计中列了 `qb.admin.monthly`、`qb.admin.balances`，**尚未实现** | 两个报表模板（平台报表，`REPORT_ISSUE` 签发、平台导出）与月报页 |
| 资金总览 | 余额、待支付、转账中、Stripe 余额科目 | `qb.admin.wallets` 与平台账本模板已有 | 财务首页（各科目余额与当日要处理的事项数） |

**不在 Q9c**（建议作为 Q9d，见问题 C-1）：F-01 科目映射、F-02 汇总凭证、F-03 CSV 导出（设计 Q13：先做通用 CSV）、F-09 充值收据（平台单据）。F-05 三方对账、F-07 期间锁定照旧延后。

## 2. 页面（扩展，路由 `/qb/finance/**`，菜单"财务"，全部要求 `qb.admin.finance.read`）

1. **总览** `/qb/finance`：2110 / 2120 / 2130 / 1110 / 1120 / 4110 / 5110 的余额（平台 `jabiz.ledger.account_balances`，`asOf` = 现在）；商家钱包合计与待支付；
   待处理数：最近一次核对的差异数、`CHECK` 中的转账、待审批的大额转账、`REFUND_SHORTFALL` 等资金待办（平台待办，按来源键筛选）。每个数字链到下面的页面。
2. **转账** `/qb/finance/transfers`：`qb.admin.transfers` 分页（状态、日期、用户筛选；账户只显示末 4 位）；`CHECK` / `SENDING` 超时的高亮。
   行内：用户（`UserName`，链到 Q9a 的用户汇总）、Stripe 转账号（只显示，不链外部）。暂停的用户显示"放行"（`QB_TRANSFER_HOLD_RELEASE`，要求 `qb.payout.review`，按钮按权限显示，服务端照常检查）。
3. **大额转账审批** `/qb/finance/transfers/:transferId/review`：转账、用户汇总（余额、近期奖励、封禁、账户状态）与平台 `ApprovalPanel`。
   与 Q9a 相同的前提：转账与用户数据都按当前 `approvalRequestId` 读完且无错误才显示面板。
4. **充值** `/qb/finance/topups`：`qb.admin.topups` 分页（商家、状态、日期）；列出手续费、净额、原币、退款、争议与差额（`refundShortfall`、`disputeShortfall`）。
5. **核对** `/qb/finance/reconciliation`：`qb.admin.recon-runs`（`qb.reconcile`）列表 → 一次运行的差异 `qb.admin.recon-items`；差异按 `check` 分组，`reference` 链到充值、转账或用户。
   只读：差异的处理是线下的或经现有流程，Q9c 不加"标记已处理"（问题 C-3）。
6. **月报** `/qb/finance/reports`：选月份 → 月度汇总与期末余额表；导出（平台 `POST /api/queries/{id}/export`，CSV / XLSX / PDF）；
   "签发"（平台 `REPORT_ISSUE`，存档只追加、带内容哈希）与已签发的列表（平台报表存档的接口）。

金额只经 `formatKudos`；颜色只用主题 token；表格只用平台 `DataTable`（分页）。

## 3. 模板

| 模板 | 新 / 改 | 内容 | 权限 |
|---|---|---|---|
| `qb.admin.monthly`（报表） | 新 | 一个月（参数 `month`，`timeSlice` 以月末为时点）按交易类型的笔数与金额：充值到账、退款与争议扣回、奖励结算（奖励、抽成）、转账发出 / 成功 / 失败冲正 / 撤回、手续费、打款到银行；只从账本汇总（`LedgerTransaction` 的来源与科目），不读应用表 | `qb.admin.finance.read` |
| `qb.admin.balances`（报表） | 新 | 期末余额表：各科目期初、本期借、本期贷、期末；另列 2110 = 商家钱包合计、2120 + 2130 = 用户钱包合计的核对 | `qb.admin.finance.read` |
| `qb.admin.finance-summary` | 新 | 总览的待处理数（一条语句） | `qb.admin.finance.read` |
| `qb.admin.transfers` | 改 | 加 `transferId` 筛选（审批页按编号读）、`stale` 标记 | 不变 |
| `qb.admin.recon-items` | 改（如需要） | 加关联对象的种类，供链接 | 不变 |

报表的标题与列名写在消息 `query.qb.admin.monthly[.<列>]` 等，三语。`timeSlice` 与 `asOf` 按平台报表的规则（19 §2）；不自己拼"当前版本"。

## 4. 流程

不加新流程。页面只调用已有的：`QB_TRANSFER_HOLD_RELEASE`、平台 `APPROVAL_DECIDE`、平台 `REPORT_ISSUE`。

## 5. 顺带（问题 C-2）

`QbAttempt` 的缺省数据视图（`play/PlayConfig.qbAttemptDataset`，`qb.admin.users.read`）仍给内容管理员 `reward`、`fee`。
`f.masked` 只用于文本字段，不适用于金额，所以建议：缺省视图去掉这两列，另加只给财务的视图 `finance:QbAttempt`（`qb.admin.finance.read`）。
影响 Q5 的生成页面：内容管理员的会话列表不再有金额。

## 6. 测试

- **IT** `AdminFinanceIT`：
  - 两个报表在一组固定分录（充值、退款、奖励、转账成功 / 失败冲正 / 撤回、打款）上的每一行；期末余额 = 平台 `account_balances`；核对列为真；
  - 内容管理员、商家对新模板与新视图 403；财务 200；`QbAttempt` 缺省视图不含金额；
  - `REPORT_ISSUE` 签发月报、重复签发同一期得到同一哈希。
- **场景**：`topup.yml` 或新场景末尾加月报的期望（快照随提交）。
- **Vitest**（扩展）：总览的链接；转账页的放行按权限显示；审批页的前提（数据不全不显示面板）；核对页的分组与链接；月报的导出参数。
- **e2e**：`finance.spec.ts`（财务登录 → 总览 → 转账 → 审批大额转账（Stripe 桩）→ 月报导出与签发）；`a11y.spec.ts` 加四个财务页（亮、暗）。
- `:quizbuks:check`、`ext:check`、两个 SPA 的 `app:check`、`tools/check-app-paths.sh`。

## 7. 风险

- **报表口径**：月报按账本的过账时间，与商家交易页（Q9b，奖励按状态的时间）对不上是预期的；在报表说明中写明。
- **大量数据**：月报按科目与来源聚合，按 `bookingTime` 的索引；在 e2e 数据与 10 万条分录上 `EXPLAIN`。
- **审批绑定**：大额转账的审批内容由 Q6b 的 `ApprovalSubject` 定义；页面只显示，不改内容哈希。
- **平台缺口**：若平台报表存档没有列表接口，签发列表只链到通用后台（不改平台）。

## 8. 待确认

| 编号 | 问题 | 建议 |
|---|---|---|
| C-1 | 范围：Q9c 只做财务页面与 F-08 月报；F-01 – F-03（科目映射、汇总凭证、CSV）与 F-09（充值收据）另作 Q9d | **分开**（Q9d 涉及受控参数与凭证号，单独评审） |
| C-2 | `QbAttempt` 缺省视图的金额是否在 Q9c 收回 | **是**，另加财务视图 |
| C-3 | 核对差异是否加"已处理"的标记流程 | **不加**（差异只写一次；处理经待办与线下，F-07 以后） |
| C-4 | 月报的签发是否只限超级管理员 | **财务即可**（`REPORT_ISSUE` 需要 `report.issue`，授予 `QB_ADMIN_FINANCE`） |

## 9. 预估

约 3.5 天：模板与 IT 1.5 天，页面与 Vitest 1.5 天，e2e 与文档 0.5 天。
