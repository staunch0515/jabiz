# Q27 投资人指标月报（计划，2026-10-25 确认）

需求方（2026-10-25）：第一阶段（约半年，只开线下兑奖、商户免费）要向投资人证明系统可行；平台要能按月给出活跃商户、留存、
答题人、答题与兑奖等数字。确认的方案：**一个 SQL 模板、一个后台页面、不加表不加任务**。

## 1. 已确认的决定

| # | 决定 |
|---|---|
| D-Q27-1 | **按月的模板** `qb.admin.investor-metrics`（财务权限 `qb.admin.finance.read`，`report: period`，可导出、签发与存档）：参数 `from` / `to`（UTC 月的边界，同 `qb.admin.monthly`；至多 36 个月），每个 UTC 月一行（没有发生的月份为零）：新增商户（审核通过的时间）、活跃商户（当月其发布被作答）、留存商户（当月活跃且三个月前也活跃）、上线的发布（`wentOnAt`）、新增答题人（资料建立；商户自己的资料不计）、活跃答题人（开始作答）、答题（开始）、完成与通过（提交）、获得 / 申领 / 领取的奖品（`QbRedemption` 的 `dueAt` / `claimedAt`（仍然成立或已完成的申领；被拒绝或失效的申领已撤回，不计）/ `redeemedAt`）、寄出与应答的邀请（`lastSentAt` / `answeredAt`）、充值笔数与金额（`PAID` 的 `paidAt`）、已付的 Kudos 奖励与佣金（`rewardStatus = PAID` 的 `rewardPaidAt`）。通过率与领取率在页面上算 |
| D-Q27-2 | **按商户的模板** `qb.admin.investor-sponsors`（同权限、同参数）：期间内有答题、奖品或邀请的每个商户一行（公司名、答题人、答题、完成、通过、获得与领取的奖品、寄出与应答的邀请；商户编号只作键放在最后，页面不显示），答题多的在前；公司名取商户记录的当前版本 |
| D-Q27-3 | **后台页面** `/qb/finance/investor-metrics`（财务菜单"投资人指标"）：起止月在地址（`?from=YYYY-MM&to=YYYY-MM`，缺省最近六个月），按月表格加合计行（活跃与留存的人数不合计：同一人跨月只是一人），商户表格；导出 / 签发 / 存档复用月报页的 `ReportActions` / `Issued`（`REPORT_ISSUE`，平台存档）。不存数字、不建任务 |
| D-Q27-4 | 口径：**各数字按事情发生的时间归月**（UTC 月，与 `qb.admin.monthly` 一致；分录导出按日本月份，另有说明）；读各时态实体的当前版本。现金与奖品奖励是商户自己的钱，不计入资金列，只以答题与奖品的个数体现 |

## 2. 改动

| 部分 | 改动 |
|---|---|
| 模板 | `queries/qb/admin/investor-metrics.sql`、`investor-sponsors.sql`；消息 `query.qb.admin.investor-metrics[.列]`、`query.qb.admin.investor-sponsors[.列]`（三语） |
| 后台扩展 | `ids.ts`（`TEMPLATE.investorMetrics` / `investorSponsors`、`REPORTS`、`PATH.investorMetrics`）、`finance-api.ts`（`monthRange`、`readInvestorMetrics`、`readInvestorSponsors`）、`InvestorMetricsPage.tsx`、`ReportsPage.tsx`（导出 `ReportActions` / `Issued`）、`index.tsx`（路由与菜单）、`finance-messages.ts`（`finance.investor.*`、`menu.investorMetrics`，三语） |
| 测试 | `InvestorMetricsIT`（两个商户、两个答题人跨四个月：新增 / 活跃 / 留存、上线的发布、完成与通过、奖品获得与领取、邀请寄出、充值、奖励与佣金；按商户的顺序；商家读被拒 403）；`finance.test.tsx`（页面、地址中的期间、无权限时无签发、`monthRange`） |
| 文档 | 本文、`03-plan.md`、`02-design.md` §6、`operations.md` |

## 3. 风险

- 模板对每个月做十几个相关子查询（比较的是转换后的边界，索引可用）：几十个月 × 几十万答题时可能慢；本阶段的量很小。慢了再改为一遍分组。超过 36 个月的直接调用只给前 36 个月（页面拒绝这样的期间）。
- "活跃"按答题归属的商户（`QbAttempt.sponsorId`），不含只上线而没人作答的商户；"上线的发布"一列补足。
