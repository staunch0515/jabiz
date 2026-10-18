# Q9e 商家分析：仪表盘、漏斗、题目分析、趋势（计划，2026-10-18 确认）

依据：`03-plan.md`（Q9：M-10 – M-11、M-40 – M-43；Q9a – Q9d 已完成，余下即本阶段，"15a（图表）"）、需求原文 4.2、4.5、6.1（"商家分析页注明每 10 分钟更新"）、
`01-requirements-analysis.md` 2.3（U-22 / M-43）、2.6（G11 图表）、`02-design.md` 3.3、4.3、6、7.2，以及 `backend/quizbuks/CLAUDE.md` 的锁序与语句预算。
基于 `origin/1.2/quizbuks-7-plan` @ c4a5cf7（= `1.2/quizbuks` + 已确认的 Q7 计划）。只改 `.jabiz-app-paths` 中的路径；**不需要平台新能力**（第 3 节）。

## 1. 目标与范围

| 需求 | 优先级 | 内容 | Q9e 的做法 | 子阶段 |
|---|---|---|---|---|
| M-10 关键指标 | P1 | 进行中的发布数、今日支出、实时答题人数、AI 用量（Token）、预算剩余 | 仪表盘一行指标：进行中 / 暂停 / 预算不足的发布数、今日奖励支出、正在答题的人数（实时，取计数）、可用余额与待支付、有上限的发布的剩余预算合计；**AI 用量**的卡片随 Q8a 加入（本阶段留位置，不显示） | Q9e1 模板、Q9e2 页面 |
| M-11 趋势与排行 | P2 | 一周参与趋势、一周支出、热门发布 Top | 近 7 天按日的完成人数与支出（图表）；近 7 天完成人数前 5 的发布 | 同上 |
| M-40 概览（按发布） | P1 | 总参与人数、合格人数、平均用时、完成率 | 发布分析页的指标：浏览、开始、完成、合格、获奖、支出、完成率 = 完成 / 开始、平均用时（只计按时提交的，见 E-6） | 同上 |
| M-41 转化漏斗 | P1 | 浏览 → 开始 → 完成 → 合格；标出流失最大的环节 | 四段漏斗，每段的人数与相对上一段的比率，流失最大的一段由模板标出（页面高亮并以文字说明） | 同上 |
| M-42 题目分析 | P2 | 每题正确率与难度 | 每题：作答数、正确率、难度（易 / 中 / 难，E-5）；少于最小分组人数时不显示（E-4） | 同上 |
| M-43 实时趋势 | P2 | 过去 24 小时按小时、过去一周按天；与 App 的 1h / 24h / 周 / 月统计口径一致 | 按小时的桶（每 10 分钟刷新当前小时）；页面另显示 App 所用的 `qb_publication_stats` 四个窗口（同一来源，口径自然一致，D-Q9e-1） | 同上 |
| （补充）导出 | — | 需求未写 | 平台导出 `POST /api/queries/{id}/export?format=csv|xlsx`：趋势、概览、题目分析；只有汇总，没有逐人数据（E-7） | Q9e2 |

**不做**：逐个答题人的分析（商家能看到的逐人数据仍只有 Q9b 的交易：显示名、得分、奖励）；按国家 / 地区 / 年龄的细分（E-11）；App 首页卡片的曝光数（E-8）；
管理员的平台级分析页（E-10）；PDF 导出与报表签发（`REPORT_ISSUE`）；实时推送（页面轮询）。

## 2. 现状（`origin/1.2/quizbuks-7-plan`）

- **商家后台**：`DashboardPage.tsx` 只显示入驻状态（注释"analytics (M-10, M-11) come with Q9"）；发布页 `publishing/PublicationPage.tsx` 有计数（开始、完成、合格、获奖、已花）但没有图表；
  `quizbuks-web/sponsor/package.json` 已依赖 `recharts@3.10.1`，`@jabiz/ui` 导出 shadcn 的 `ChartContainer` / `ChartTooltip` / `ChartLegend`（15a，G11 已完成）。
- **可用的数据**：
  - 计数（普通表，每个上架的发布一行）：开始一侧 `qb_publication_start_counter`（`starts`、`reservedTotal`，开始锁内写）、完成一侧 `qb_publication_counter`
    （`completions`、`passes`、`winners`、`released`、`spent`，发布锁内写）；都是只增不减的累计值。`qb.sponsor.publications` 已在一条语句中读两侧。
  - `qb_publication_view`（`QB_VIEW`：每人每发布第一次打开详情时一行）：唯一键 `(user_id, publication_id)`，**没有按发布的索引**。
  - `qb_attempt`：`startedAt`、`submittedAt`、`status`（`SUBMITTED` / `EXPIRED`）、`score`、`passed`、`result`（规范 JSON：每题 `no`、`selected`、`correct`、`points`、`earned`，完成时写）；
    索引 `qb_attempt_completed_idx (publication_id, submitted_at) WHERE submitted_at IS NOT NULL`。没有逐题答案表（设计 3.3："题目分析用 `jsonb_array_elements` 展开"）。
  - `qb_publication_stats`：任务 `qb.publication-stats` 每 10 分钟写的近 1 小时 / 24 小时 / 7 天 / 30 天完成数（App 详情页的 U-22），`qb_stats_run` 记运行时刻。
- **事件**：`qb.reward.settled`、`qb.reward.pending`、`qb.topup.paid`、`qb.transfer.*`、`qb.sponsor.settle-pending`、`qb.sponsor.funds-restored`；**没有**开始、浏览、完成（无奖励）的事件。
  分析不依赖事件（见 D-Q9e-2）。
- **权限**：设计 6 写"`qb.sponsor.analytics.*` 各自权限"，`QbPermissions` 中尚无分析权限。包 `stats` 已在命名约定中预留，尚无代码。
- **迁移**：Q7b 用 V12、Q7a 用 V13；Q7c 的计划也写 V14。本阶段写作 **V14**，号码按合并顺序取下一个（Q7c 先合并则本阶段为 V15，Q8a 顺延）。

## 3. 平台缺口

无必需的平台工作：图表（`@jabiz/ui` 的 Recharts 封装）、主题 token 与 axe、模板与导出、逐项或分块的定时任务、`now:SysParam` 参数视图都已具备。

| # | 观察 | 处理 |
|---|---|---|
| P1 | 模板没有"最小分组"的声明（k-匿名之类），只能在 SQL 中以 `CASE WHEN n >= :k` 实现，由测试保证 | 不做平台工作：应用内一处 SQL 片段（模板不能互相引用，复制并由 IT 对每个模板断言）。若以后其他应用也要，再提平台 16t 以外的新阶段与决策（D50 之后） |
| P2 | Q9b 记下的"模板的占位符不把不可变字段的条件推进内层"（资料连接全表 `DISTINCT ON`） | 本阶段不连接资料与时态的发布历史（只经 `sponsor:QbPublication` 限定，每个商家的发布不多），不受影响 |

## 4. 拆分、分支与顺序

| 子阶段 | 分支 | 内容 | 迁移 | 预估 | 依赖 |
|---|---|---|---|---|---|
| **Q9e1** 分析数据 | `1.2/quizbuks-9e1-analytics-data` | 口径、按小时的桶、浏览计数、题目统计、任务 `qb.analytics`、6 个模板、权限、IT、场景、性能测量 | V14 | 2–2.5 天 | 无 |
| **Q9e2** 分析页面 | `1.2/quizbuks-9e2-analytics-pages` | 商家仪表盘、发布分析页（概览、漏斗、趋势、题目）、导出、Vitest、e2e、axe | 无 | 2.5–3 天 | Q9e1 |

合计 4.5–5.5 天（`03-plan.md` 中整个 Q9 原估 5–6 天，已由 Q9a – Q9d 用去大部分；本估算只含余下的分析）。建议在 Q8 之前做：不依赖平台，且 Q8a 的 AI 用量卡片放进本阶段的仪表盘。

## 5. Q9e1 分析数据

### D-Q9e-1 口径（E-1）

| 指标 | 定义 | 来源 |
|---|---|---|
| 浏览 | 打开过发布详情的不同用户数（`QB_VIEW`，每人每发布一次） | `qb_publication_view` |
| 开始 | 开始答题的人数（一人一次，含之后超时的） | 计数 `starts` |
| 完成 | 按时提交（`SUBMITTED`）或超时自动提交（`EXPIRED`）的人数，按完成时刻 | 计数 `completions`（与 App 的 `qb_publication_stats` 同一事件：会话结束） |
| 合格 | 完成且得分 ≥ 合格分 | 计数 `passes` |
| 获奖 | 完成且奖励 > 0（含待支付） | 计数 `winners` |
| 支出 | 奖励金额之和（含待支付；不含充值、退款）；商家的钱包另见 Q9b 与钱包页 | 计数 `spent` |
| 正在答题 | `starts − completions`（**一条语句读两侧**，`backend/quizbuks/CLAUDE.md`） | 两侧计数，实时 |
| 完成率 | 完成 / 开始 | 计数 |
| 平均用时 | `SUBMITTED` 的 `submittedAt − startedAt` 的平均（`EXPIRED` 的用时恒为时限，另报超时人数，E-6） | `qb_attempt`，增量累计 |
| 正确率（每题） | 该题 `earned > 0` 的完成数 / 该题的完成数（多选题全对才算对，与评分相同） | `qb_attempt.result`，增量累计 |

- 漏斗按上表的四段：浏览 → 开始 → 完成 → 合格；比率为相对上一段。理论上开始可能多于浏览（直接以地址进入、浏览记录写入失败），此时浏览一段按 `max(浏览, 开始)` 显示并注明。
- "与 App 一致"（M-43）：App 的 1h / 24h / 7d / 30d 是滚动窗口，来自 `qb_publication_stats`；分析页把这四个数**原样**显示在趋势图旁（同一来源），
  图表用按小时的桶（桶的边界是整点，与滚动窗口不完全重合，页面注明）。

### D-Q9e-2 聚合方式：累计计数的整点快照（不碰答题路径）

- **不在开始、提交或超时中写任何分析数据**，也不订阅事件：答题锁内的语句数与 `AttemptStatementBudgetIT` 不变，答题路径不增加索引维护（`qb_attempt` 不加索引）。
- 新任务 `qb.analytics`（每 10 分钟，`JobDefinition`，根流程 `QB_ANALYTICS_REFRESH`，`internal()`，权限 `qb.stats.system` 不授予任何角色）：
  选出进行中、暂停、预算不足与最近 1 天内结束的发布（同 `qb.publication-stats` 的选取，按编号分块），每块一个子流程 `QB_ANALYTICS_CHUNK`。
  只写自己的表、不取任何业务锁，所以按 `backend/quizbuks/CLAUDE.md` 的例外写成根流程 + 分块（不是逐项任务）。
- 每块**一条语句**读：两侧计数、游标（上一次整点的基准值）与该块各发布自上次运行以来新增的浏览数（新索引 `qb_publication_view (publication_id, first_viewed_at)`）。
- **按小时的桶** `QbAnalyticsHour`：游标 `QbAnalyticsCursor` 记当前小时的起点与该时刻的累计值（浏览、开始、完成、合格、获奖、支出）。
  每次运行：当前小时的行 = 现在的累计 − 基准（覆盖写）；跨过整点时先以本次读到的值关闭上一小时的行，再把基准移到本次。
  因此桶的边界是"整点后第一次运行读取计数的时刻"（任务按整点后的 :00 调度，偏差为秒级）。任务停了一段时间时，补上的增量全部记在停机前的那一小时并标 `gap = true`（页面注明），不平均分摊。
  累计值只增不减，增量恒 ≥ 0（不成立即记告警日志并跳过该行，不写负值）。
- **题目统计与平均用时** `QbQuestionStat` / 游标中的水位线：每块按现有索引 `qb_attempt_completed_idx` 读水位线 `(submittedAt, attemptId)` 之后、
  且 `submittedAt ≤ 运行时刻 − 5 分钟` 的已完成会话（滞后 5 分钟：提交的 `opTime` 早于提交事务的 `COMMIT`，排队与锁等待合计远小于此），
  由 SQL 以 `jsonb_array_elements(result)` 按题汇总为增量（每题完成数、答对数）以及用时之和、按时提交数、超时数，连同新水位线同一事务写入。
  每块每次至多读 `quizbuks.analytics.max-attempts-per-chunk`（缺省 20 000）个会话，读不完的下次继续（热门发布的追赶）。
- **结束后的复核**：发布结束 30 分钟后的那次运行对它**整体重算一次**题目统计与用时（同一 SQL，不带水位线），结果覆盖增量值并记 `finalizedAt`；此后不再计算该发布。
  这样即使增量有遗漏（理论上不会），最终数字也是精确的。
- **显示的题目统计按"至少新增 k 个完成"才更新**（E-4）：表中另存一组"已公开"的值与其完成数，只有当前完成数 − 已公开的完成数 ≥ k（或已结束复核）时才把当前值复制过去。
  商家因此不能以前后两次的差得出某一个人的逐题作答。

### D-Q9e-3 日界与时区（E-2）

- 小时桶以 UTC 整点存放；按日汇总在读取时按**部署的时区**（平台 `jabiz.fiscal-year-zone`，D48；日本部署 `Asia/Tokyo`）的日界分组。整小时偏移的时区精确，
  非整小时偏移（如 `Asia/Kolkata`）的日界偏 30 分钟（部署时区不会是它们，注明即可）。
- "今日支出" = 今日已关闭的小时桶 + 当前小时（现在的 `spent` − 游标基准）：取计数，实时。

### D-Q9e-4 最小分组与隐私（E-4、E-11）

- 参数 `qb.stats.min-group`（缺省 5，普通参数，超级管理员可改，下限 3）。
- **按人可推出的数字**才受它约束：每题正确率与难度、平均用时、超时人数——完成数 < k 时模板返回空值与 `suppressed = true`，页面显示"人数不足 k，暂不显示"；
  题目统计按 D-Q9e-2 的"至少新增 k 个完成"更新。
- **人数本身**（浏览、开始、完成、合格、获奖、支出）不受 k 约束：商家本来就能在 Q9b 的交易中看到获奖者的显示名、得分与奖励，隐藏总数没有保护作用。
- 模板只读计数、桶、题目统计与本人的发布；**不读** `qb_attempt` 的逐行数据（只有任务读），不读资料、邮箱与用户编号；导出同此。
- 不做按国家、地区、时间段以外维度的细分（E-11）；以后若加，按地区、k ≥ 10，并同样采用"至少新增 k 个"的更新规则。

### 实体与迁移（V14）

| 实体 | 类型 | 字段 | 说明 |
|---|---|---|---|
| `QbAnalyticsHour` | P | `publicationId`、`sponsorId`、`hourStart`（UTC 整点）、`views`、`starts`、`completions`、`passes`、`winners`、`spent`、`closed`、`gap` | 唯一 `(publication_id, hour_start)`；索引 `(sponsor_id, hour_start)`；只由任务写；没有变化的小时不写行（读取时补零） |
| `QbAnalyticsCursor` | P | `publicationId`（主键）、`sponsorId`、`hourStart`、六个基准值、`views`（累计浏览）、`attemptWatermarkAt`、`attemptWatermarkId`、`submittedCount`、`expiredCount`、`durationSumSec`、`finalizedAt`、`lastRunAt` | 每个被统计过的发布一行 |
| `QbQuestionStat` | P | `publicationId`、`questionNo`、`completions`、`correct`、`shownCompletions`、`shownCorrect`、`shownAt` | 唯一 `(publication_id, question_no)`；题号取自版本快照（1 起） |

- `qb_publication_view` 加索引 `(publication_id, first_viewed_at)`（`QB_VIEW` 的插入多一个索引；它不取发布锁或开始锁，不在语句预算之内）。
- 三张表都是普通表，经 `DatasetEntityManager` 写（审计照常；只有任务写，量为每 10 分钟每个发布至多 1–2 行）。
  审计行的量：1 000 个进行中的发布 × 每 10 分钟 ≈ 每天 14 万行审计。若评审认为太多，桶与游标可改为平台不审计的写入方式——平台没有这种方式（D27），所以备选是把刷新改为每 10 分钟只写**变化了的**行（已如此）并接受。见风险。
- 保留：`QbAnalyticsHour` 保留 400 天（任务每日删除更早的行；普通表、无保留策略冲突）。

### 流程、模板与任务

| 名称 | 类型 | 权限 | 说明 |
|---|---|---|---|
| `QB_ANALYTICS_REFRESH` / `QB_ANALYTICS_CHUNK` | 流程（`internal()` / `subProcessOnly()`） | `qb.stats.system` | 见 D-Q9e-2；块大小 `quizbuks.analytics.chunk-size`（缺省 100） |
| `qb.analytics` | 任务 | — | 每 10 分钟（`0,10,20,30,40,50 * * * *`）；每日一次另删 400 天前的小时桶 |
| `qb.sponsor.dashboard` | 模板 | `qb.stats.read` | M-10：一行。各状态的发布数、正在答题（两侧计数一条语句）、今日支出、可用余额（`balance − held`）与待支付（`QbWallet`）、有上限的进行中发布的剩余预算合计（`budgetCap − spent − (reservedTotal − released)`）、最近一次统计时刻 |
| `qb.sponsor.analytics.trend` | 模板 | `qb.stats.read` | M-11 / M-43：参数 `publicationId?`（空 = 本人全部发布）、`from`、`to`、`bucket`（`HOUR` / `DAY`）；按桶给出六个人数与支出，缺的桶补零（`generate_series`）；每次至多 400 个桶 |
| `qb.sponsor.analytics.top` | 模板 | `qb.stats.read` | M-11：近 7 天完成数前 `limit`（≤ 10）的本人发布 |
| `qb.sponsor.analytics.overview` | 模板 | `qb.stats.read` | M-40 / M-41：一个发布的累计人数、完成率、平均用时（< k 时为空）、超时人数（< k 时为空）、漏斗四段与 `largestDrop`（`VIEW_START` / `START_COMPLETE` / `COMPLETE_PASS`）、App 的四个窗口（`qb_publication_stats`）、统计时刻 |
| `qb.sponsor.analytics.questions` | 模板 | `qb.stats.read` | M-42：每题的题号、题干（版本快照中）、分值、已公开的完成数与正确率、难度、`suppressed` |

- 全部模板以限本人的 `urn:jabiz:dataset:sponsor:QbPublication`（范围 `sponsorId` = 调用者）连接，别人的发布编号查不到任何行（同 Q9b）。
- 新权限 `qb.stats.read`（`QB_SPONSOR`；管理员角色不授予，E-10）、`qb.stats.system`（不授予）；`QB_SETUP` 补授予与参数 `qb.stats.min-group`。
- 消息：`query.qb.sponsor.analytics.*[.<列>]`、`query.qb.sponsor.dashboard[.<列>]` 三语（导出的列名）；金额列 "Amount (Kudos)"。
- 观测：任务照平台 `PlatformObservations`（流程名、结果）；不加带发布编号的标签。

### Q9e1 的测试

- **单元**：`AnalyticsBucketsTest`（整点关闭、同一小时多次运行、停机跨多小时记为 `gap`、增量为负即跳过）、`MinGroupTest`（k 的边界、"至少新增 k 个"的公开规则、结束复核后总是公开）、`FunnelTest`（流失最大的一段、浏览少于开始时的处理）。
- **IT** `AnalyticsIT`（固定时钟、真实 PostgreSQL）：脚本化的浏览、开始、提交、超时跨过整点 → 桶的值等于计数之差、累计等于计数；浏览只计第一次；
  题目统计的增量结果等于整体重算（含多选题、超时会话以已保存答案评分）；滞后 5 分钟内的会话不计入、下次计入；块上限与追赶；结束 30 分钟后复核并定稿；
  k 以下返回空与 `suppressed`；两个商家互相查不到（含传入对方的发布编号）；权限矩阵（答题人、内容 / 财务 / 超级管理员 403，未登录 401）；
  导出 CSV 只有汇总列、列名已翻译。
- **语句预算不变**：`AttemptStatementBudgetIT` 与 `AttemptStatementBudgetWithoutHeldWritesIT` 不改（并在 IT 中断言答题流程的锁与语句数在 V14 之后不变）。
- **场景** `scenarios/qb/analytics.yml`：上架 → 三人浏览、两人开始、一人合格、一人超时 → `runJob qb.analytics` 跨整点 → 期望（安装者读不到别人的发布，同 Q9b 的场景限制；商家本人的数字由 IT 覆盖）。
- **性能**（不在 `check` 中，结果写入 `perf/Q10a-load-test.md` 新的一节或单独的 `perf/Q9e-analytics.md`）：1 000 个进行中的发布、100 万会话（其中一个热门发布 20 万）下，
  一次任务运行的时间与语句数、各模板的 `EXPLAIN ANALYZE`；以及在 `loadTest` 的热门发布压测期间运行任务，提交 p95 与吞吐不变（任务不取锁，只读计数）。

## 6. Q9e2 分析页面（`quizbuks-web/sponsor`）

- **仪表盘**（`pages/DashboardPage.tsx`，入驻通过后）：指标卡（进行中 / 暂停 / 预算不足的发布、正在答题、今日支出、可用余额与待支付、剩余预算；AI 用量卡由 Q8a 加入）；
  近 7 天的趋势图（完成人数折线、支出柱，双轴改为上下两个小图，避免双 Y 轴）；热门发布前 5（表格，链到发布分析页）；"每 10 分钟更新，最近更新 hh:mm"。
  未入驻通过时保持现有的入驻提示。指标每 60 秒、回到前台时刷新（TanStack Query `refetchInterval`）。
- **发布分析页** `/publications/:id/analytics`（发布页加"分析"入口）：概览指标；漏斗（横向条形，每段人数与比率，流失最大的一段以文字与图形两种方式标出，不只靠颜色）；
  趋势（"过去 24 小时（按小时）/ 过去 7 天（按天）"切换；App 的四个窗口并列显示）；题目分析表（题号、题干、正确率条、难度标签；不足 k 时的说明）；导出按钮（CSV、Excel：趋势、概览、题目）。
- **组件与主题**：只用 `@jabiz/ui`（`ChartContainer`、`ChartTooltip`、`Card`、`Table`、`Tabs`、`Badge`、`PageState`）；颜色只用主题 token（`--chart-1` … `--chart-5`），亮暗两种外观；
  每个图表下方有可展开的数据表（屏幕阅读器与键盘可用），图表容器有 `aria-label` 与摘要文字。金额只经 `formatKudos`。
- **文案**：命名空间 `sponsor` 三语；`messages.test.ts` 的键一致测试。
- 新目录 `sponsor/src/analytics/`（`ids.ts`、`api.ts`、`messages.ts`、`DashboardCharts.tsx`、`PublicationAnalyticsPage.tsx`、`Funnel.tsx`）；`src/qb/` 不改（两份仍相同）。

### Q9e2 的测试

- **Vitest**：`analytics/api.test.ts`（参数、日界、补零、`suppressed` 的转换）；`analytics.test.tsx`（指标卡、漏斗的最大流失标注与文字、24 小时 / 7 天切换、不足 k 的说明、导出带参数、数据表的替代视图）；仪表盘的未入驻状态不变。
- **e2e** `analytics.spec.ts`：商家上架 → 两名答题人答题 → 运行任务（e2e 的任务时刻配置同 Q9d）→ 仪表盘与分析页的数字、漏斗、导出的 CSV 行数；另一商家看不到。
- **axe**：`a11y.spec.ts` 加仪表盘与分析页（亮、暗；1280 px 与 390 px）。

## 7. 风险

- **性能（100 万用户、1 000 个进行中的发布）**：任务每 10 分钟读 1 000 行两侧计数（一条语句 / 块）与浏览索引的一个范围；题目统计只读上次以来的完成（热门发布每秒 50 次 × 600 秒 = 3 万个会话、每个至多 100 题的 JSON 展开），
  所以设块上限、分多次追赶；结束复核对 20 万个会话的整体重算约数秒（性能测量中确认，必要时对超大发布分块重算）。模板只读桶（每个发布每天至多 24 行）与计数，商家的发布数有限。
- **对答题路径的影响**：没有。不写、不锁、不加 `qb_attempt` 的索引；任务只以 MVCC 读计数。唯一的写放大是 `qb_publication_view` 的新索引（`QB_VIEW` 不在锁内）。
- **审计量**：每 10 分钟的覆盖写会产生审计行（约每天 14 万行，见第 5 节）；它们是统计数据，不是业务数据。若要避免，须提平台能力（"不审计的派生表"），属于新的平台决策，本计划不建议。
- **隐私**：答题人的逐题作答只经 k 与"至少新增 k 个"的规则以汇总出现；商家已有的逐人数据（Q9b 的显示名、得分、奖励）不变。小发布（< k 人）的题目分析一直不可用（可接受，E-4）。
- **口径的误解**：桶与 App 的滚动窗口不完全重合；支出含待支付；浏览只计详情页。都在页面与文档中注明（E-1、E-8）。
- **停机与补跑**：停机期间的增量记在一个小时上（`gap`）；累计数字始终正确。

## 8. 文档

`02-design.md` 3.3（新实体）、6（模板与口径）、7.2（页面）、11；`backend/quizbuks/CLAUDE.md` 加"分析"一条（不在答题路径写分析数据、只经任务聚合、一条语句读两侧计数、k 的规则）；
`operations.md`（任务、参数、补跑）；`03-plan.md` 状态。

## 9. 已确认（2026-10-18）

需求方接受全部建议（E-1 – E-12）。
迁移编号（2026-10-18 统一）：Q7b V12、Q7a V13、Q9e1 V14、Q7c V15、Q8a V16（按此顺序合并；若顺序变化，后合并者顺延）。

以下为原问题与建议（即结论）：

| 编号 | 问题 | 选项 | 建议 |
|---|---|---|---|
| E-1 | 指标与口径 | 如 D-Q9e-1：完成含超时自动提交；获奖与支出含待支付；完成率 = 完成 / 开始；正在答题 = 开始 − 完成 | **如左** |
| E-2 | 按日的日界 | A 部署时区（`jabiz.fiscal-year-zone`，日本 `Asia/Tokyo`）；B 浏览器时区（小时桶可按整小时偏移重组，非整小时偏移不精确）；C UTC | **A**（与 Q9d 的会计期间一致） |
| E-3 | 实时还是定时汇总 | A 仪表盘的发布数、正在答题、今日支出、余额取计数（实时），趋势、浏览、题目与用时每 10 分钟；B 全部每 10 分钟；C 每日汇总 | **A**（需求 6.1 允许 10 分钟；实时的几项只读计数，开销很小） |
| E-4 | 最小分组人数 k | A k = 5，只约束可推出个人作答的数字（每题正确率、难度、平均用时、超时人数），且题目统计只在新增 ≥ k 个完成时更新；B k = 10；C 不设 | **A**（参数 `qb.stats.min-group`，下限 3） |
| E-5 | 题目难度怎样表示 | A 正确率 + 标签：≥ 80 % 易、50 – 80 % 中、< 50 % 难；B 只显示正确率；C 另算区分度（高分组与低分组的正确率差） | **A**（区分度以后可加，同一张表即可计算） |
| E-6 | 平均用时 | A 只计按时提交的，超时人数另列；B 全部完成（超时按时限计）；C 中位数（需要保存分布，增量难做） | **A** |
| E-7 | 导出 | A 趋势、概览、题目分析的 CSV 与 XLSX（平台导出，只有汇总）；B 再加 PDF；C 不导出 | **A** |
| E-8 | "浏览"的定义 | A 打开详情的不同用户（现有 `QB_VIEW`）；B 首页卡片的曝光（每次渲染都要记录，百万用户下写入量大，需要新的批量记录） | **A** |
| E-9 | 小时桶保留多久 | A 400 天（约每年 900 万行，可做月度与同比）；B 90 天；C 永久 | **A** |
| E-10 | 管理员是否也要看分析 | A 不做（管理员已有发布列表的计数）；B 后台扩展加同样的发布分析页（模板另以缺省视图提供，+0.5 天） | **A** |
| E-11 | 按国家 / 地区细分 | A 不做；B 按地区、k ≥ 10（需要在任务中连接资料，+1 天） | **A** |
| E-12 | 子阶段与顺序 | Q9e1（数据）→ Q9e2（页面）；整个 Q9e 在 Q8 之前；Q8a 在仪表盘加 AI 用量卡 | **如左** |
