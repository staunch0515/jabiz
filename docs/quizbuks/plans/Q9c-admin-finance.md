# Q9c 财务后台页面与月报（计划，2026-10-16 确认）

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

## 8. 已确认（2026-10-16，全部按建议）

| 编号 | 问题 | 结论 |
|---|---|---|
| C-1 | 范围：Q9c 只做财务页面与 F-08 月报；F-01 – F-03（科目映射、汇总凭证、CSV）与 F-09（充值收据）另作 Q9d | **分开**（Q9d 涉及受控参数与凭证号，单独评审） |
| C-2 | `QbAttempt` 缺省视图的金额是否在 Q9c 收回 | **是**，另加财务视图 |
| C-3 | 核对差异是否加"已处理"的标记流程 | **不加**（差异只写一次；处理经待办与线下，F-07 以后） |
| C-4 | 月报的签发是否只限超级管理员 | **财务即可**（`REPORT_ISSUE` 需要 `report.issue`，授予 `QB_ADMIN_FINANCE`） |

## 9. 预估

约 3.5 天：模板与 IT 1.5 天，页面与 Vitest 1.5 天，e2e 与文档 0.5 天。

## 实施记录（Q9c，2026-10-16）

分支 `1.2/quizbuks-9c-admin-finance`（计划提交 f06036e 之上）。只改 `.jabiz-app-paths` 中的路径，不改平台；没有迁移。

- **后端**：模板 `qb.admin.monthly`、`qb.admin.balances`（报表，`queries/qb/admin`）、`qb.admin.finance-summary`；`qb.admin.transfers` 加筛选 `transferId`、
  列 `stale`、`held`、`releasedAt`、`releasedBy`（参数 `staleBefore`）；`qb.admin.topups` 加筛选 `topUpId`（核对页链到一笔充值）。三语的标题与列名（`MessagesTest`）。
  `QbAttempt` 的数据视图按 C-2 拆开（见偏离）；`QB_ADMIN_FINANCE` 加 `report.archive.read`（`report.issue` 已有，C-4）。不加流程。
- **前端**（扩展）：菜单"QuizBuks 财务"，`/qb/finance`（总览）、`/qb/finance/transfers`（转账）、`/qb/finance/transfers/:transferId/review`（大额审批）、
  `/qb/finance/topups`（充值）、`/qb/finance/reconciliation`（核对）、`/qb/finance/reports`（月报）。读取在 `finance-api.ts`，地址中的筛选、分组与报错在 `finance.ts`，
  文案在 `finance-messages.ts`（三语，并入 `messages.ts`）。`usePaged` 在列表（筛选）改变时回到第一页。
- **测试**：
  - IT `AdminFinanceIT` 3 个：一个月里经流程做出每一种过账（充值与 Stripe 手续费、退款、争议败诉扣回、奖励含抽成、转账发出并成功后部分被撤回、Stripe 拒绝的转账（冲正）、
    两笔打款其一失败、开发入账与测试的直接过账），月前与月后各一笔：月报 12 行逐行核对笔数、金额与七个科目的变动（每行合计 0）；余额表的期初、期末与平台
    `jabiz.ledger.account_balances` 相同，借贷逐科目核对，各科目的变动等于月报的列和，三项核对为真；`REPORT_ISSUE` 签发两次（中间在月外再过账）得到同一哈希，
    存档核对 `identical`，存档列表含两次；导出的 CSV 13 行。权限矩阵：内容管理员与商家对两个报表、总览、转账、充值、奖励模板与导出 403，财务 200；
    `finance:QbAttempt` 只给财务，缺省视图对三者都 403；内容管理员不能签发、不能读存档。总览的待审批数与按编号读转账、驳回后 `held`、放行后不再 `held`。
  - `PlayOwnerIT`、`QbRolesTest` 随 C-2、C-4 更新；场景 `topup.yml` 末尾加十月两个报表的期望（新权限多了一条授权，五个场景的快照只有生成编号的移位）。
  - Vitest `finance.test.tsx` 16 个：总览的链接与无 `ledger.read` 时不读科目；转账页的地址筛选、待核实标记、放行按权限显示并先确认；审批页的前提（收款人读取出错时不给面板）、
    非待审、非编号地址不请求；核对页的分组与链接；月报的模板参数、导出参数与签发、无权限时不给签发与存档；月份、地址筛选与分组的纯函数。
  - e2e `finance.spec.ts`（总览 → 待审批的转账 → 审批页批准 → 桩收到转账 → 本月月报、CSV 与 PDF 导出、签发）；`a11y.spec.ts` 加财务页（总览、转账、充值、核对、月报，亮、暗）。
- **结果**：`:quizbuks:check` 444 个测试全部通过（含 `AdminFinanceIT` 3 个）与 `platformCheck`（0 错误）；扩展 Vitest 47 个（新增 16 个）；`ext:check`、两个 SPA 的 `app:check` 通过；e2e 全部 39 项对打包的 jar、Mailpit 与 Stripe 桩通过（新增 `finance.spec.ts` 1 项、`a11y.spec.ts` 财务页 2 项）；`tools/check-app-paths.sh origin/1.2/platform` 通过。
- **EXPLAIN**：在 e2e 数据库的副本上加 10 万笔交易（20 万条分录，均匀分布在 12 个月）后，按平台编译后的形式（只写一次的账本表直接读、科目 `DISTINCT ON`）`EXPLAIN ANALYZE`：月报取一个月（约 8,200 笔）约 68 ms：交易按 `booking_time` 的索引取出，分录对其哈希连接，但分录表是顺序扫描（按交易编号的索引在这个比例下不划算）；余额表要期初，读月末以前的全部分录（约 10 万条）约 68 ms。规模再大十倍时仍在秒以内，暂不加索引；需要时按科目与过账时间的汇总表是平台账本的事（记为后续）。
- **偏离计划**：
  - **C-2 的做法**：平台的数据视图不能去掉字段（只有公开读取有白名单，`f.masked` 只用于文本），所以"缺省视图去掉两列"做不到。缺省视图改为不给任何角色读取
    （读权限 `qb.play.system`；流程与模板照常经它），财务读只读视图 `finance:QbAttempt`，支持人员只经不含金额的 `qb.admin.attempts`（用户汇总的答题历史）。
    影响比计划大：内容管理员在通用后台不再有 `QbAttempt` 的生成页面（不止是少两列）。平台缺口：数据视图按权限收窄字段（或数值字段的遮蔽），记在此处，不改平台。
  - **报表参数**：不是计划的 `month` 参数，而是 `from`、`to`（月初与下月初）与可选的 `knownAt`；`timeSlice` 只映射 `knownAt`（平台 19 §2.2：账本的过账时间是筛选条件，
    若以月末为生效时点，月末记账、次月才记录的分录会两头都不在）。月份按 UTC（平台报表时区 `jabiz.reports.zone` 的缺省；QuizBuks 未设）：页面按 UTC 计算月初。
  - **余额表的核对只读账本**：计划的"2110 = 商家钱包合计、2120 + 2130 = 用户钱包合计"在报表中是各科目与其各户（维度 `party`）之和的核对；钱包缓存（普通表、只有现在）
    与账本的核对是每日核对（核对页）。这样报表只读时态实体，签发后可按数据核对（`recomputable`）。
  - **月报的分类**：12 类（充值到账、退款、争议扣回、Stripe 手续费、奖励、转账发出 / 成功 / 失败冲正 / 被撤回、打款、打款失败、其他），按来源单据、是否冲正与所动科目；
    退款与争议扣回所动科目相同，只按流程写的描述 `Refund` / `Lost dispute` 区分（`AdminFinanceIT` 两者都覆盖）。开发入账与没有来源的过账归"其他"。
  - **金额**按 JPY（`formatAmount`），同 Q9a 与 `CLAUDE.md` §4（管理员看真实币种），不是计划写的 `formatKudos`。
  - **核对差异的链接**：`qb.admin.recon-items` 不改；引用的种类由检查项推出（`TOPUP_*` → 充值，`TRANSFER_*` → 转账，`partyId` → 用户汇总）。`qb.admin.topups` 加 `topUpId` 筛选。
  - **资金待办**按指派的权限（`qb.reconcile`）计数，链到平台的待办页，不按来源键。
  - **每日转账的时间可配置**（`quizbuks.transfers.run-cron`，环境变量 `QB_TRANSFER_RUN_CRON`，缺省不变）：平台没有"立即运行任务"的接口，e2e 要在测试中等到一笔大额转账进入审批，
    所以 CI 的 e2e 以每分钟运行；测试打开转账并把审核线设为 1,000（e2e 数据库中保留，其他测试的奖励都低于门槛）。
  - **e2e 以首个管理员登录**（持有全部权限），不是建一个财务角色的用户：财务角色要求二次验证（`SecRole.requireMfa`），e2e 的管理入口不做二次验证。
  - **a11y** 查五个财务页（总览、转账、充值、核对、月报）；审批页在 `finance.spec.ts` 中走过，但不做 axe（它要等一笔大额转账进入审批，约一分钟）。
- **已知限制**：
  - 导出与签发的报表中，类型与核对项是代码（`TOPUP`、`SPONSOR_WALLETS`）：平台的导出不翻译文本列；页面中照常显示三语的名称。PDF 中的中文、日文需要
    `jabiz.reports.pdf.fonts`（QuizBuks 尚未配置，否则为 `?`；CSV 与 Excel 不受影响）。
  - 核对页一次显示一次运行的前 100 项差异（更多时说明总数）。
  - 打开状态下拉框时（Radix 的 Select 把页面其余部分设为 `aria-hidden`）axe 报 `aria-hidden-focus`，是平台后台布局与 Select 的组合，财务页的 a11y 不查这个状态。
  - 报表按过账时间：奖励按结算时计入，与商家交易页（按状态的时间）对不上是预期的（计划第 7 节）。
