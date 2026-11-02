# Q80：商户后台的列表在手机上成卡片（接入平台 17zc）

来源：需求方 2026-11-02 的八项要求中的第三项（`docs/quizbuks/05-console-requests.md`）："商户的所有列表页面增加 Card 模式，这样在手机显示比较友好。"
平台一侧是 **17zc**（`@jabiz/ui` 的 `DataTable` 卡片模式，PR #418）；本阶段只在 QuizBuks 一侧给列表加标注。**前提：17zc 合入 `1.3/platform` 并合并到 `1.3/quizbuks`**（同步分支 `1.3/quizbuks-sync-17zc`，照 Q73 之后的同步做法）。

## 0. 为什么

商户后台的列表都是 `DataTable`，手机上横向滚动、列多的页面（交易、发布、现金奖励）难用。17zc 之后，列表只要给列标注在卡片上的位置（`meta.card`），窄屏就自动成卡片，用户也可切换；排序、分页、展开、加载与空态都是组件的。

## 1. 现状

| 列表 | 文件 | 列（`id`） | 现有的行属性 |
|---|---|---|---|
| 模版 | `content/QuizListPage.tsx` | cover、title、version、status、questionCount、materialCount、editedAt、actions | 已有自己的**画廊卡片**（Q34b：`ListView.tsx` 的 `ViewSwitch` / `CardGrid` / `Pager`，缺省卡片，记在 `qb.sponsor.view.quizzes`） |
| 发布 | `publishing/PublicationListPage.tsx` | name、quiz、status、regions、starts、winners、spent、startAt、editedAt | 同上（`qb.sponsor.view.publications`） |
| 版本 | `content/VersionsPanel.tsx` | label、title、languages、questionCount、fullScore、versionedAt、actions | `version-row` |
| 现金奖励 | `publishing/CashRewardsPage.tsx` | code、taker、publication、amount、status、dueAt、actions | `cash-row`、`data-status` |
| 电子奖品 | `publishing/DigitalPrizesPage.tsx` | taker、publication、prize、amount、delivery、status、dueAt | `digital-row`、`data-status` |
| 码池 | `publishing/DigitalPool.tsx` | seq、hint、status、loadedAt、issuedAt、revokedAt | `digital-code-row` |
| 邀请清单 | `publishing/Invitations.tsx` | email、status、mail、answer、actions | `invitation-row` |
| 兑换：经手人 / 历史 | `publishing/RedeemPage.tsx` | name、today、week、month、total ／ code、taker、publication、prize、amount、status、handler、… | `redeem-handler-row` ／ `redemption-row` |
| 店员 | `publishing/StaffPage.tsx` | name、id、addedAt、actions | `staff-row` |
| 交易 | `transactions/TransactionsPage.tsx` | occurredAt、type、user、publication、score、amount、status、actions | `transaction-row` |
| 充值 | `wallet/WalletPage.tsx` | createdAt、amount、status、paidAt、actions | `topup-row` |
| 通告（原生 `Table`） | `notices/MessagesPage.tsx`（`notices-table`） | — | — |
| 主页统计（原生 `Table` ×2） | `stats/StatsPage.tsx`（`stats-day`、`stats-product`） | — | — |
| 分析的两张表、推荐的表 | `analytics/PublicationAnalyticsPage.tsx`、`publishing/ReferralsSection.tsx` | 数字对照表 | — |

## 2. 决定（需求方 2026-11-02 确认；开发等 17zc 合入 `1.3/platform` 并同步后开始）

| # | 决定 |
|---|---|
| D-Q80-1 | **模版与发布两个列表保留 Q34b 的画廊卡片**（有封面的大卡片、缺省卡片、自己的切换），它们的表格视图**不再加**第二种卡片（不给 `meta.card`，照旧只有表格）；`ListView.tsx` 不动。 |
| D-Q80-2 | 其余九个 `DataTable`（版本、现金奖励、电子奖品、码池、邀请清单、兑换的两张、店员、交易、充值）给 `meta.card` 与 `storageKey`（`qb.sponsor.<列表>`）：缺省按宽度（手机卡片、桌面表格），用户可切换。标注见 §3。 |
| D-Q80-3 | **通告页与主页统计页的原生 `Table` 改为 `DataTable`**（它们是菜单里的列表页），同样给标注；行的 testid（`stats-day`、`stats-product` 等）经 `rowProps` 保留。 |
| D-Q80-4 | 分析页的语言表与题目表、推荐的表是数字对照表（列少、要横向比较），**保持表格**；后台扩展（管理员用桌面）不在本阶段。 |
| D-Q80-5 | 不加新文案：卡片的标签就是列头，切换与排序的文字是平台的（三语已有）。 |

## 3. 标注

| 列表 | title | subtitle | badge | actions | hidden | 其余（标签：值） |
|---|---|---|---|---|---|---|
| 版本 | label | title | languages | actions | — | questionCount、fullScore、versionedAt |
| 现金奖励 | taker | code | status | actions | — | publication、amount、dueAt |
| 电子奖品 | taker | publication | status | — | — | prize、amount、delivery、dueAt |
| 码池 | hint | — | status | — | seq | loadedAt、issuedAt、revokedAt |
| 邀请清单 | email | — | status | actions | — | mail、answer |
| 兑换·经手人 | name | — | — | — | — | today、week、month、total |
| 兑换·历史 | taker | code | status | （现有操作列） | — | publication、prize、amount、handler、时间列 |
| 店员 | name | id | — | actions | — | addedAt |
| 交易 | type | occurredAt | status | actions | — | user、publication、score、amount |
| 充值 | amount | createdAt | status | actions | — | paidAt |
| 通告 | 标题列 | 发送时间 | 状态 | 操作 | — | 受众、到达 / 阅读 |
| 主页统计·按日 | day | — | — | — | — | views、clicks… |
| 主页统计·商品 | 商品名 | — | — | — | — | clicks |

金额列照旧右对齐（`meta.className` 不变，只在表格里起作用）。

## 4. 改动

- 17zc 的同步：`1.3/quizbuks-sync-17zc`（合并 `origin/1.3/platform`，跑 `pnpm app:check` 两个 SPA、商户后台与 App 的测试）。
- 九个 `DataTable`：列定义加 `meta: { ...meta, card }`，`<DataTable storageKey="qb.sponsor.…">`。
- `notices/MessagesPage.tsx`、`stats/StatsPage.tsx`：原生 `Table` → `DataTable`（列定义、`rowProps` 保留 testid；没有分页的照旧不给 `pagination`）。
- 文档：`02-design.md` §7 商户后台一行、`backend/quizbuks/CLAUDE.md` 第 5 节一行（新列表都给 `meta.card` 与 `storageKey`；模版与发布的画廊卡片是例外）、`05-console-requests.md` 第三项与 `03-plan.md` 的 Q80 行。

## 5. 测试

- 各页现有的 Vitest：加一段"切到卡片"：点 `data-table-view` 的"卡片"，断言行的 testid 仍在（`cash-row` 等现为卡片）、标题列的值在 `data-slot="data-table-card-title"` 中、操作按钮在卡片上可点（如店员的移除、充值的收据）；两种外观 axe（`expectAccessible`）各一页即可（组件本身在平台已过）。
- 通告页与统计页的测试按 `DataTable` 的结构改（行 testid 不变）。
- e2e：`sponsor.spec.ts`（或 `a11y.spec.ts`）以 390px 视口打开交易页，断言 `[data-slot="data-table"][data-view="cards"]` 并过 axe；恢复视口后为表格。本机不运行，CI 跑。
- `pnpm lint`、`pnpm typecheck`、`pnpm test`、`pnpm app:check ../quizbuks-web/sponsor`。

## 6. 估计

| 部分 | 天 |
|---|---|
| 17zc 同步与检查 | 0.25 |
| 九个列表的标注与测试 | 0.5 |
| 通告页、统计页改 `DataTable` 与测试 | 0.5 |
| e2e、文档 | 0.25 |
| **合计** | **1.5** |

## 7. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 低 | 手机上的 e2e：商户后台的 e2e 都是桌面视口，缺省表格，不受影响 | 新加的手机视口检查只针对一页 |
| 低 | 两种卡片并存（模版 / 发布的画廊卡片与 `DataTable` 的卡片） | D-Q80-1：两个列表不加第二种；文档写明 |
| 低 | 原生 `Table` 改 `DataTable` 后测试按结构找元素 | 行 testid 经 `rowProps` 保留 |

## 8. 需要需求方确认的

1. D-Q80-1：模版与发布保留现有的画廊卡片，不再加第二种卡片。
2. D-Q80-3 / D-Q80-4：通告页与主页统计页改为 `DataTable` 并成卡片；分析页与推荐的数字对照表保持表格。
3. §3 的标注（哪一列作标题、徽章）。
4. 编号 Q80，前提是 17zc 合入并同步。
