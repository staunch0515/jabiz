# Q9b 商家交易明细、汇总与导出（M-50 – M-52）— 实施计划与记录

分支：`1.2/quizbuks-9b-sponsor-transactions`，从 `1.2/quizbuks` 拉出，PR 合回 `1.2/quizbuks`。只改 `.jabiz-app-paths` 中的路径。
本阶段是 Q9 的一部分（QP-pages 缺口 A-4）；Q9a（管理员扩展页）在另一分支并行。计划已确认（2026-10-15，全部取缺省；另确认商家看得到答题人资料的显示名）。

## 1. 需求

| 编号 | 优先级 | 内容 | 本阶段 |
|---|---|---|---|
| M-50 | P0 | 交易列表：用户、发布、金额、得分、状态、时间；可按发布或用户搜索 | `qb.sponsor.transactions` + 页面 `/transactions` |
| M-51 | P1 | 汇总：总笔数；已完成、处理中、失败的笔数与金额，全部以 Kudos 显示 | `qb.sponsor.transaction-summary`（同样的参数） |
| M-52 | P1 | 导出当前筛选的结果 | 只经平台 `POST /api/queries/{id}/export?format=csv|xlsx` |

## 2. 缺省（已确认）

- **交易**是两种行：本人发布的**奖励**（已完成的答题、奖励 > 0；已支付 = 已完成（时间为支付时间），待支付 = 处理中（时间为提交时间））与本人的**充值**
  （`PAID` = 已完成；`OPENING` / `OPEN` = 处理中；`FAILED` / `EXPIRED` = 失败，未扣款）。"失败"只有充值会有；类型筛选把两者分开。完成但没有奖励的答题不是交易。
  奖励的金额是商家付出的整笔奖励（抽成由答题人一侧承担，不在商家的金额中）；充值另给已退款或因争议扣回的部分（`refunded`）。
- **用户**以答题人资料的显示名出现（`QbProfile.displayName`，用户确认），从不给邮箱。
- **金额只有 Kudos**：没有币种列，页面经 `formatKudos`，导出的金额列名为 "Amount (Kudos)"；不出现其他币种名。
- **导出**：CSV 与 XLSX（平台端点也有 PDF，页面不提供）。
- **页面**：新的功能目录 `sponsor/src/transactions/` 与路由 `/transactions`（不放进 `wallet/`），左侧导航在"钱包"之后。

## 3. 后端

- `queries/qb/sponsor/transactions.sql`（`qb.sponsor.transactions`，权限 `qb.sponsor.finance.read`）：`UNION ALL`
  - 奖励：默认数据视图的 `QbAttempt` 连接**限本人**的 `urn:jabiz:dataset:sponsor:QbPublication`（范围 `sponsorId` = 调用者），再左连默认的 `QbProfile` 取显示名；
    只取 `submittedAt` 非空、`rewardStatus` 为 `PAID` / `PENDING`、`reward > 0` 的行；不读 `answers`、`result`。
  - 充值：限本人的 `urn:jabiz:dataset:sponsor:QbTopUp`。
  - 联合两边的列都显式转换类型（同 `qb.app.ledger`）；外层一次套用全部条件。
  - **全部筛选都是参数**（`type`、`status`、`publicationId`、`userKey`、`from`、`to`、`q`），不是列表筛选：汇总模板因此能用完全相同的条件，列表、汇总与导出一致。
    `q` 在发布名与答题人显示名中按原文查找（`strpos`）；`from` 含、`to` 不含。排序 `occurredAt`、`amount`，缺省按时间倒序；键 `entryId`（答题或充值的主键）。
- `queries/qb/sponsor/transaction-summary.sql`（`qb.sponsor.transaction-summary`）：同样的参数、内层查询与条件（复制：模板不能互相引用），外层聚合为一行：
  `totalCount` 与按类型分开的笔数与金额（奖励：已支付、待支付；充值：已到账（扣除退款）、退款或扣回、处理中、失败；评审后修正，见记录）。
- 消息：`query.qb.sponsor.transactions[.<列>]`、`query.qb.sponsor.transaction-summary[.<列>]` 三语（金额列 "Amount (Kudos)" / "金额（Kudos）" / "金額（Kudos）"），
  导出的列名即这些消息；`MessagesTest` 加上它们。
- 不加迁移：奖励一侧经发布（每个商家的发布不多）按 `qb_attempt_completed_idx (publication_id, submitted_at)` 找到会话；充值有 `qb_topup_sponsor_idx`；资料有 `qb_profile_version_user_idx`。

## 4. 前端（`quizbuks-web/sponsor/src/transactions/`；`src/qb/` 不改）

- `ids.ts`（两个模板名；发布的选项用 `publishing/ids.ts` 的 `qb.sponsor.publications`）、`api.ts`（手写类型；`paramsOf` 把筛选变为模板参数，期间按浏览器时区的整日、末日含在内；
  `exportTransactions(format, params, sorts)` 经 `sessionFetch`，失败经 `toApiError`，文件名取 `fileNameOf(Content-Disposition)`）、`messages.ts`（命名空间 `sponsor`）、`TransactionsPage.tsx`。
- 页面：汇总（总笔数；奖励与充值分组的金额与笔数）；平台的 `FilterBar`（类型、状态、期间、发布（草稿不列出）、"发布或用户"关键字）；
  行操作"只看此用户"设定用户筛选，显示为可移除的标签；服务端分页与排序的 `DataTable`；"导出 CSV" / "导出 Excel" 带当前的参数与排序；
  拒绝（如 `REPORT_TOO_LARGE`）的服务端文案经 `qb/problems.ts` 显示。金额只经 `formatKudos`。
- 入口：左侧导航"交易"（钱包之后）；钱包页"查看全部交易"；已提交的发布页"此发布的交易"（`/transactions?publication=<id>`，地址只接受这一个参数）。

## 5. 测试

- IT `it/SponsorTransactionsIT`：两个商家；对方的行无论怎样筛选都不出现；状态映射（`PAID` / `PENDING` 的奖励，`PAID` / `OPEN` / `EXPIRED` / `FAILED` 的充值）；无奖励的完成不出现；
  每个筛选（类型、状态、发布、用户、关键字、期间、组合）与按金额排序；汇总等于同样参数下列表的合计；经 `/api/queries/qb.sponsor.transactions/export?format=csv`
  只导出本人的行、列名已翻译（英、中），XLSX 可用，导出带同样的参数；答题人与无此权限的管理员（财务、超级、内容）403，未登录 401；没有作答、结果与邮箱。
- 场景：`scenarios/qb/topup.yml` 末尾对两个模板的 `query` 期望（见第 7 节的偏离）。快照不变（只含实体）。
- Vitest：`transactions/api.test.ts`（参数、期间、地址、行的转换、三语键一致且不出现其他币种名）与 `transactions/transactions.test.tsx`
  （汇总与行、筛选改变列表与汇总的请求、"只看此用户"的标签与移除、`?publication=` 的深链接、导出经会话、带同样的参数与排序、只有 CSV 与 Excel、拒绝的文案）。
- e2e `transactions.spec.ts`（充值 1,000 → 一人得 600 → 另一人待支付；列表与汇总的计数与金额；按发布筛选；只看此用户；下载的 CSV 行数与金额列；没有另一商家的数据；钱包页与发布页的入口），
  `a11y.spec.ts` 加交易页（亮、暗；状态选择展开、只看一个用户；390 px）。

## 6. 验收

- M-50 / M-51 / M-52 如上；`:quizbuks:check`、两个 SPA 的检查、`diff -r src/qb`、e2e 与 axe 通过；没有迁移；`tools/check-app-paths.sh` 通过。

## 实施记录（Q9b，2026-10-15）

分支 `1.2/quizbuks-9b-sponsor-transactions`（从 `1.2/quizbuks` @ 51acdfc 拉出）。只改 `.jabiz-app-paths` 中的路径；`src/qb/` 未改（两份仍相同）；没有迁移。

- **后端**：两个模板与三语消息照第 3 节。奖励一侧只经限本人的发布视图连接会话，充值只经限本人的充值视图，因此任何参数都只能缩小本人的行
  （另一商家的发布、用户键或名字都查不到任何行）。管理员即使持有 `*` 也只读到自己（作为商家）的行。
- **前端**：照第 4 节。筛选栏用平台的 `FilterBar`（类型、状态、期间、发布、关键字）；"只看此用户"是行操作与可移除的标签；导出两个按钮带当前的参数与排序。
- **测试**：IT `SponsorTransactionsIT` 6 个（`:quizbuks:check` 合计 434 个测试，全部通过，含 `platformCheck`）；商家后台 Vitest 新增 11 个（合计 103 个），App 85 个不变；
  e2e 新增 `transactions.spec.ts` 1 项与 `a11y.spec.ts` 的交易页 2 项（亮、暗），全部 33 项对打包的 jar、Mailpit 与 Stripe 桩通过。
- **偏离计划**：
  - **用户筛选键不是用户编号**：计划写"`userId` 只作不透明的筛选键"。实现时改为模板计算的 `userKey` = `md5(商家编号 : 用户编号)`：同样只作筛选键、页面不显示，
    不是用户编号（导出中也只有这个键），同一答题人在每个商家处的键不同；它不是秘密，知道两个编号的人都能算出（没有密钥，不加迁移）。参数与列因此叫 `userKey`。
  - **场景只能检查调用者自己的范围**：场景的期望以场景的操作人运行（`ScenarioRunner` 不支持期望的 `actor`，平台目录不在本分支修改），而两个模板都限调用者本人，
    `topup.yml` 中的操作人是安装者，所以末尾的期望是"安装者读不到 A 的任何交易、汇总为 0"（同该场景对 `qb.sponsor.wallet` 的说明）；A 自己的行由 `SponsorTransactionsIT` 覆盖。快照不变。
  - 发布名取自限本人的发布视图（与卡片上冻结的名字相同，少连一张表），不连卡片。
- **已知限制**：发布筛选的选项最多取 200 个发布（按名称）；更多时其余的发布仍可经关键字或发布页的链接筛选。
  奖励的时间随状态而变：待支付时是提交时间，补发后是支付时间，所以按期间筛选时，一笔奖励可能在支付后移到另一个期间。

### 评审后的修正（Q9b，2026-10-15）

独立评审没有严重或高的问题；改了以下各项：
- **`userKey` 的说明**（中）：不再说"商家之间对不上"；改为"不是用户编号，同一答题人在每个商家处的键不同；它不是秘密，知道两个编号的人都能算出"（02-design、CLAUDE.md、本记录、模板注释）。键本身不变，不加盐、不加迁移。
- **汇总按类型分开**（低）：原来"全部类型"下把充值（收入）与奖励（支出）的金额相加，且已退款的充值按全额计。现在 `qb.sponsor.transaction-summary` 给出
  总笔数，奖励的已支付 / 待支付（笔数与金额），充值的已到账（扣除退款的净额）、退款或扣回、处理中、失败（笔数与金额）；页面分"奖励（支出）""充值（收入）"两组显示，
  类型筛选时只显示该组，不再有混合的金额总计。IT 按类型、按状态比较汇总与列表（已到账的充值比较净额与退款）。
- **地址变化时重新开始**（低）：页面只在挂载时读 `?publication=`；经导航、后退、前进到另一地址时保留旧的筛选。`App.tsx` 以 `key={search}` 使交易页随地址重新开始（Vitest）。
- **只接受 UUID 的发布**（低）：`?publication=` 不是 UUID 即忽略（Vitest）。
- **资料连接的性能**（低）：以 10 万个资料版本 `EXPLAIN ANALYZE` 奖励一侧：`{{QbProfile}}` 是对全部资料的 `DISTINCT ON (profile_id)` 子查询，
  `user_id` 的条件不下推（PostgreSQL 不把不在 `DISTINCT ON` 键上的条件推进这种子查询），每次调用要读全部当前资料（约 120–185 ms）。
  改为 `LEFT JOIN LATERAL (… WHERE user_id = m.user_id)` 或相关子查询同样不下推（计划相同：仍是全部资料的 `Unique`，LATERAL 约 129 ms、子查询约 120 ms），
  所以模板保持普通的 `LEFT JOIN`。真正的改进需要平台让模板的占位符把不可变字段的条件推进内层（D29 已对实体查询这样做，模板没有），或在会话上保存答题时的名字（需迁移）；
  记为平台缺口，商家规模大时再做。
- 未改：类型与状态参数是自由文本（不合法的值只是查不到行）；奖励的时间在支付后变化（见上，已知限制）。
