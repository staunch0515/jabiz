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

## 实施记录（Q9e1，2026-10-18）

分支 `1.2/quizbuks-9e1-analytics-data`（`origin/1.2/quizbuks-7-plan` 之上）。只改 `.jabiz-app-paths` 中的路径；迁移 **V14**（统一的顺序：Q7b V12、Q7a V13、Q9e1 V14、Q7c V15、Q8a V16）。

### 平台合并

- 先以合并提交合入 `origin/1.2/platform`（16q、16r 受保护授权与 `jabiz.fiscal-year-zone`、16s 收件偏好），没有冲突；合并后 `:quizbuks:check` 原样通过。
- `application.yml` 设 `jabiz.fiscal-year-zone: Asia/Tokyo`（与 Q7a 相同的写法与位置，以后合并只有同一行）：保留期按日本日期（`RetentionIT` 照常通过），分析按它分日。
- 16r 的建立期：QuizBuks 的 `QB_SETUP`、e2e 与本机演示建的角色（`QB_E2E_PUBLISHER` 的 `control.publish` 等）都在建立期内（首个管理员之外没有两位持有 `*` 的管理员），受保护的授权当即生效；e2e 与本机演示照旧通过（见"验证"）。

### 实现

- **包 `stats`**：`QbAnalytics`（实体 `QbAnalyticsHour`、`QbAnalyticsCursor`、`QbQuestionStat`，都是普通表）、`AnalyticsBuckets`（增量加进哪个小时、`gap`）、`MinGroup`（k 与公开规则）、`QuestionTotals`（游标中各题合计的规范 JSON）、
  `AnalyticsLimits`、`AnalyticsRefresh`（`QB_ANALYTICS_REFRESH` `internal()`、`QB_ANALYTICS_CHUNK` `subProcessOnly()`，权限 `qb.stats.system`）、`AnalyticsConfig`（数据视图、任务 `qb.analytics`，`0 */10 * * * *`）。
- **任务**：根流程读上次运行、选出要读的发布（`qb.system.analytics-due`：进行中 / 暂停 / 预算不足，1 天内结束的，结束后游标未定稿的；三段索引范围的 UNION），按编号分块（缺省 100），每块一个子流程；
  然后删除 400 天前的小时桶（每次至多 2 000 行）并记下运行时刻与部署时区（`qb_stats_run` 新列 `day_zone`）。计划时刻不晚于上次运行的重复运行什么也不做（平台对同一计划时刻本来就重放结果）。
  每块：一条语句（`qb.system.analytics-state`）读卡片、**两侧计数**、游标、自 `viewsUntil` 以来的浏览（新索引）、要加的小时桶与 k；按计划决定哪些整体重算；
  `qb.system.analytics-attempts` 读水位线之后、滞后 5 分钟的已完成会话（每块至多 `max-attempts-per-chunk`，按 `(submittedAt, attemptId)` 全块排序后截断，每个发布读到的总是自己顺序的前缀），按题与按发布汇总；
  同一模板以 `full` 整体重算结束的发布；`qb.system.analytics-question-stats` 读公开的题目统计；然后只写变化了的行。
- **模板**（`qb.stats.read`，只授予 `QB_SPONSOR`）：`qb.sponsor.dashboard`、`qb.sponsor.analytics.trend` / `.top` / `.overview` / `.questions`，全部经 `sponsor:QbPublication`；k 的读法与公开规则见 D-Q9e-4 与下面的偏离 3、4。
  消息 `query.<id>[.<列>]` 三语（导出的列名，金额 "Kudos"）；实体、流程、参数的显示名三语。
- **参数** `qb.stats.min-group`（普通参数，缺省 5，`QB_SETUP` 建立；SQL 与 `MinGroup` 都按 `greatest(3, …)`）。
- **迁移 V14**：三张表（小时桶唯一 `(publication_id, hour_start)`、整点检查、`(sponsor_id, hour_start)` 与 `(hour_start)` 索引；游标的未定稿部分索引；题目统计唯一 `(publication_id, question_no)`）、
  `qb_stats_run.day_zone`、`qb_publication_view (publication_id, first_viewed_at)`。`qb_attempt` 不加索引。
- 任务自己的只读数据视图 `urn:jabiz:dataset:stats:QbAttempt|QbPublicationCard|QbPublicationView`（读权限 `qb.stats.system`，不授予任何角色）：查询时限 2 分钟（整体重算），不放宽其他读取的 5 秒。
- 文档：`02-design.md`（2、3.3、6、11）、`backend/quizbuks/CLAUDE.md`（"商家分析"一条）、`operations.md`（§5 告警、§7.7）、`launch-checklist.md`（压测中运行任务）、`perf/Q9e-analytics.md`、`03-plan.md`。

### 与计划的偏离（均为实现细节或隐私上的收紧，需求方的决定不变）

1. **桶的写法**：游标记"上次变化时的累计值"，每次把增量**加**进一个小时（本次运行的小时；跨过整点后的第一次运行加进上一次运行的小时），而不是"当前小时 = 现在 − 整点基准、覆盖写"。
   这样没有变化的发布、没有变化的小时都不写（计划的覆盖写在每个整点要给每个发布写一次）；`closed` 不存，由桶的小时早于最近一次运行的小时推出。
   `gap` = 桶早于本次运行的小时、且本次运行晚于那之后的整点 5 分钟以上（漏跑了），或发布的第一个桶之前已有历史；同一小时内漏跑不算。
2. **当前的各题合计放在游标的一行 JSON 中**，`QbQuestionStat` 只存商家看到的值（只在公开值变化时写）：计划的"每题一行、每次更新当前值"会让每次运行每题写一行审计。
3. **结束后的重算同样遵守"新增至少 k 个"**（计划写"或已结束复核"即公开）：否则最后不足 k 个完成的作答可由定稿前后之差推出。定稿后显示的是最近一次公开的值（`basedOn` 注明人数），重算只保证内部合计精确。
4. **平均用时只在新增至少 k 个按时提交后变化**（与每题统计的"新增 k 个完成"分开）：k 个新完成中只有一个按时提交时，前后两个平均之差就是那个人的用时。
5. **按日的日界**：模板不能读配置，由任务把 `jabiz.fiscal-year-zone` 记进 `qb_stats_run.day_zone`，模板以 `AT TIME ZONE` 分日；仪表盘的"今天"以调用方给的 `at` 在该时区的日期计（同 `qb.sponsor.topup-limit` 的 `at`）。
6. **块上限缺省 5 万**（计划 2 万）：每秒 50 次的热门发布 10 分钟 3 万个，2 万会使它永远积压；实测 3 万个一次约 1.2 秒（`perf/Q9e-analytics.md`）。
7. **定稿的条件**：结束 30 分钟后、且没有进行中的会话（两侧计数相等）、且重算的会话数等于完成数（滞后 5 分钟内的超时会让重算少一个，下次再算）；结束一天后不一致也定稿并记警告日志。
8. **删除旧桶**每次运行至多 2 000 行（不是每日一次）：漏跑的那天不会留下。
9. `qb.sponsor.analytics.top` 没有 `limit` 参数：模板不写外层 `LIMIT`（根 CLAUDE.md），页面以列表的 `limit: 5` 与缺省排序（近 7 天完成数降序）取前 5。

### 测试

- 单元：`AnalyticsBucketsTest`（整点、同一小时多次、停机为 `gap`、首次的历史、增量为负）、`MinGroupTest`（k 的读法与下限、公开规则、题目合计的 JSON 与长度）。漏斗的判断在 SQL 中，由 IT 覆盖（计划的 `FunnelTest`）。
- `AnalyticsIT`（7 个）：口径（E-1）——浏览只计第一次、开始、完成含超时、合格、获奖与支出含待支付、正在答题、完成率、平均用时只计按时提交、超时人数；**实时与汇总**——任务运行前计数已变而桶与浏览未变、运行后一致，
  仪表盘的今日支出实时；漏斗与流失最大的一段（含浏览少于开始、没有数据）；**东京的日界**（UTC 14:30 与 15:30 的两个完成落在东京的两天，按小时的日期，仪表盘的今天，至多 400 个桶）；
  **k 与差分攻击**——不足 k 时每题、平均用时、超时为空，第 k 个完成后公开，再多 1–4 个（含一个全对且很慢的）公开值不变、第 2k 个时一起变，k 个新完成中 4 个超时只 1 个按时提交时平均用时不变，k 调大即隐藏、调到 1 按 3；
  **只读本人的**——他人以本商家的发布编号读概览、题目、趋势、排行、仪表盘、导出都读不到，持有 `*` 的管理员也读不到；权限矩阵（答题人、内容 / 财务 / 超级管理员 403，未登录 401，`QB_ANALYTICS_REFRESH` 对调用者 403 `PROCESS_INTERNAL`）；
  **导出**——趋势、概览、题目的 CSV（英、中列名，金额 "Kudos"，只有汇总、没有答题人与选项）、XLSX。
- `AnalyticsJobIT`（块 2 个发布、每块 3 个会话）：积压分次读完且等于整体重算；重放与不晚于上次的运行不写任何东西（审计数与版本不变）；没有新数据不写；停机 2.5 小时的增量记在停机前的小时并标 `gap`；
  结束后有进行中的会话时不定稿、超时任务在滞后之内结束时也不定稿、之后定稿且合计等于重算、公开值按 k 不变；定稿后不再读、一天后不再选出；任务不写答题的任何表；400 天前的桶被删除、399 天的保留。
- 场景 `scenarios/qb/analytics.yml`：浏览、开始、合格、超时，任务跨过整点；快照含三张分析表与运行记录；安装者读不到商家的分析。
- `QB_SETUP` 多建一个参数，使场景中之后生成的编号顺延、各会话的选项打乱随之改变：`play.yml`、`payout.yml`、`topup.yml` 的作答位置按新的顺序改写（意图不变：同样的人答对同样的题），快照随之更新；`setup.yml` 与 `SetupIT` 加该参数；`ProcessAccessIT` 加两个流程。
- `AttemptStatementBudgetIT` 与 `AttemptStatementBudgetWithoutHeldWritesIT` **未改动并通过**：答题流程的语句数与所取的锁不变。
- 性能 `AnalyticsPerfIT`（标签 `perf`）：结果见 `perf/Q9e-analytics.md`（热门发布 10 分钟 3 万个：一次 1.2 秒、13 行审计；1 000 个发布没有新数据：0.42 秒；结束后重算 23 万个：6.0 秒）。

### 自查（隐私与性能）

- **k**：可推出个人作答的四项（每题正确率、难度、平均用时、超时人数）只来自公开值，公开值只在新增 ≥ k（平均用时：按时提交 ≥ k）时变化，模板再按当前 k 隐藏不足 k 的；人数（浏览、开始、完成、合格、获奖、支出、按小时的人数）按 E-4 不受约束。
  游标中的当前合计没有商家数据视图（缺省视图只给 `qb.admin.publication.read`），任务的 `stats:*` 视图只给不授予任何角色的权限。
- **残余风险（未处理，记录）**：①商家用自己控制的 k − 1 个已验证账号与一个目标答题人凑成一组新完成，可减去已知的作答得到目标的逐题对错——k-匿名对主动的女巫攻击无效；缓解办法（加噪、提高 k、按不同来源计数）属于新的需求决定。
  ②按小时的合格人数：某小时只有一个完成时可知那个人是否合格（E-4 接受人数不受约束；获奖者本来就在交易中可见）。③商家本来能在 Q9b 的交易中看到获奖者的得分，两题的 Quiz 由得分即可推出逐题对错，与本阶段无关。
- **范围**：五个模板都只经限本人的 `sponsor:QbPublication`；小时桶另按 `sponsor_id` 索引筛选再与本人的发布连接；`AnalyticsIT` 以他人的编号与持有 `*` 的管理员验证。
- **锁与热点**：任务不取命名锁，只以 MVCC 读两侧计数（同一语句），只写自己的三张表与 `qb_stats_run` 中自己的一行（另一统计任务是另一行）；任务由平台的集群锁保证单实例。答题路径的语句、锁与索引维护不变（`QB_VIEW` 多一个索引，不在锁内）。
- **索引**：选取走卡片的状态索引、结束索引与游标的部分索引；浏览、会话按 `(publication_id, 时间)` 的范围读；模板按 `(sponsor_id, hour_start)` 读桶。
- **规模**：最坏情况（很多发布同时积压）下，每块的候选在截断前至多为 块大小 × 上限 行（只含编号与时间），再连接结果 JSON；实际只有热门发布接近上限。整体重算一个 23 万的发布约 6 秒，超大发布（几百万）需要分块重算（计划 §7）。
  任务一次运行是一个事务（与 `qb.publication-stats` 相同），只读与写自己的表，期间不阻塞答题；长事务会推迟清理，热门发布积压时一次约 2–4 秒。

### 验证

- `./gradlew :quizbuks:check`（真实 PostgreSQL 16，本机实例）：566 个测试全部通过，`platformCheck` 0 个错误、59 个警告（与合并前相同，本阶段的模板没有新增警告）。
- 前端未改（Q9e2 做页面），没有运行 web 检查。
- e2e（打包的 jar，独立端口与数据库，Mailpit 与 Stripe 桩）：41 个中 40 个通过；`a11y.spec.ts` 的"商家后台发布页 axe（亮）"一次失败——Quiz 选择器在搜索结果刷新的瞬间，Radix 的列表框里有一个
  `role=status`（axe `aria-required-children`），单独重跑亮、暗两个都通过，与本阶段无关（前端未改；本机负载 7–14 时的时序问题），记为已知的不稳定。
- 本机演示 `tools/quizbuks/local-demo.sh start --seed`（独立端口与数据库）：首次设置（27 个提案由演示发布者发布）与演示数据全部成功；之后任务 `qb.analytics` 在 :20 照常运行并记下 `Asia/Tokyo`。

### 未完成与已知问题

- Q9e2（页面、Vitest、e2e 的分析页、axe）未开始。
- 计划 §5 要求的"`loadTest` 热门发布压测期间运行任务、提交 p95 与吞吐不变"未在本机做（共用 4 vCPU 上负载 7–14，不可比），列入 `launch-checklist.md`。
- 部署之前就已结束一天以上的发布没有游标，不会被分析（QuizBuks 尚未上线，没有这种数据）。
- e2e 的 `a11y.spec.ts` 发布页（亮）在高负载下偶发失败（见"验证"），属于 QP3b 的选择器，未在本阶段处理。
- 残余风险①②见"自查"。

## 评审修复（Q9e1，2026-10-18）

评审没有阻塞的缺陷，k 的设计成立；以下各项先写复现的测试再修复。实施记录中上文的描述以本节为准。

- **M1 概览随每次答题变化**：单个发布的概览（开始、完成、进行中等人数）改为取任务的快照（游标中的合计），只随任务每 10 分钟变化；仪表盘的合计"正在答题"仍是实时的。
  测试 `AnalyticsIT.aPublicationsFiguresMoveOnlyWithTheJobNotWithEachAttempt`（任务运行前全为 0，答题后、任务运行前不变），首个测试在运行前断言全零。
  **残余④（已知、接受，记入 02-design §6）**：商家只有一个进行中的发布时，仪表盘的实时"正在答题"与今日支出两次查看之差就是该发布的变化；Q4 的 `qb.sponsor.publications` 也实时给出每个发布的计数。都只是人数与金额（E-4），推不出逐题作答。
- **M2 商家模板扫描全部发布版本**：新增限本人、只读的卡片视图 `urn:jabiz:dataset:sponsor:QbPublicationCard`（普通表 `qb_publication_card`，范围 `sponsorId` = 调用者，读权限 `qb.stats.system` 与不授予任何角色的 `qb.content.view.write`；模板只认模板的权限），五个模板全部改经它。
  `AnalyticsPerfIT` 另造 1 000 个商家的 10 万个已结束发布，断言每个模板 < 150 ms（实测 28–54 ms，修复前排行约 365 ms）。
- **M3 一次运行一个事务**：任务改为逐项任务 `JobDefinition.forEach`（平台决策 D42）：模板 `qb.system.analytics-items` 给出项——按发布编号的块（`c000001` …，`firstId` / `lastId`）、每个到期的整体重算一项（键为发布编号）、最后一项 `zz-run`（运行记录与删除旧桶）；
  每项一个事务的 `QB_ANALYTICS_ITEM`（`internal()`），取代 `QB_ANALYTICS_REFRESH` / `QB_ANALYTICS_CHUNK`。一项失败时其他项照常提交（运行 `PARTIAL`），失败项由平台退避重试；重算的完成多于 `quizbuks.analytics.max-recount`（缺省 100 万，`AnalyticsLimits.RECOUNT_DEFAULT`）即不重算，逐块读完（分析数 = 完成数）或结束一天后定稿，使重算不超过 `QUERY_TIMEOUT`。
  改正 `AnalyticsLimits` 中"一块一个子流程、同一事务"的 Javadoc。测试 `AnalyticsRobustnessIT`（毒丸：以检查约束拒绝某个块或某个重算的写入，断言 `PARTIAL`、其他项已提交、去掉约束后下次补上；超过重算上限的发布照样定稿）、`AnalyticsJobIT.overlappingRunsCountEveryIncrementOnce`（两个线程同时运行：一个 `SUCCEEDED`、一个 `LOCKED`，增量只计一次）。
- **L1 今日支出**：没有游标（任务尚未见过）的发布不再把一生的支出算进今日：有游标时取计数 − 游标，没有时只有今天上架的计入；任务第一次见到时把此前的历史记在上架的那一小时并标 `gap`，不进今天的桶。测试 `todaysSpendingLeavesOutWhatAPublicationTheJobHasNotSeenSpentBefore`。
- **L2 偏移写法的时区**：`AnalyticsZoneCheck`（`PlatformCheck`，类别 `QUIZBUKS`）只接受 `UTC` 与地区名，拒绝 `+09:00`、`Z`、`UTC+9`、`GMT+9`、`Etc/GMT-9` 等（PostgreSQL 的 `AT TIME ZONE` 把 POSIX 偏移读反）；无效的名字留给平台的保留期检查。测试 `AnalyticsZoneCheckTest`。
- **L3 开始数从不等于完成数**：结束一天后不论是否还有进行中的会话都重算并定稿（同重算不一致时的兜底）。测试见 `AnalyticsRobustnessIT` 的第二个测试（一个永不完成的会话）。
- **L4 合并 `origin/1.2/quizbuks`（Q7b）**：合并提交 69256e0；`QB_SETUP` 多建的行使编号顺延，`play.yml`、`payout.yml`、`topup.yml` 与 `analytics.yml` 的作答位置按新顺序改写（得分不变），逐个检查快照差异后更新；`application.yml` 只留一处 `jabiz.fiscal-year-zone: Asia/Tokyo` 并加一行注释；迁移 V12、V13、V14 依次。
- **`min-group` 的写法**：模板与 `MinGroup` 只认 1–9 位的整数（`numeric(9,0)`），其他（小数、文字、更长的）按缺省 5；参数本身是 `numeric(3,0)`，`PARAM_SET` 拒绝 `abc` / `1000` / `2.5`（422 `PARAM_VALUE_INVALID`），以其他方式存入的 `1000` 按 1000 读、全部隐藏。测试 `MinGroupTest`、`AnalyticsIT.aMinimumGroupThatIsNotAWholeNumberOrTooLargeIsRefusedOrHoldsEverythingBack`。
- **水位线在上限处并列**：`AnalyticsJobIT` 断言五个会话的提交时刻相同、上限 3 切在并列之中，按 `(submittedAt, attemptId)` 每个恰读一次。
- **Info 审计字节**：游标的 `questions` 标为 `auditMasked`；`AnalyticsPerfIT` 打印审计字节：每行约 180–330 字节，最坏的每天约 170 万行约 0.3–0.6 GB / 天（评审按未遮蔽估约 0.7 GB / 天），见 `perf/Q9e-analytics.md` §3。
- 结果 JSON 的读取加了 `IS JSON ARRAY` 与 `jsonb_typeof` 的防护：一行坏的 `result` 不使整块失败。

## 实施记录（Q9e2，2026-10-18）

分支 `1.2/quizbuks-9e2-analytics-pages`，自 `origin/1.2/quizbuks-9e1-analytics-data`（35d97d3）。只改 `.jabiz-app-paths` 中的路径；没有新迁移。

### 合并

- 先以合并提交合入 `origin/1.2/quizbuks`（Q7b #176）。冲突都是"两边各加一节"：应用规则、设计、参数（`qb.stats.min-group` 与 `qb.support.email` 并列）与三语消息两边都保留。
  `QB_SETUP` 两边都多建了行，场景中之后生成的编号与各会话的选项打乱再次改变：`play.yml`、`payout.yml`、`topup.yml` 的作答位置按新的顺序改写（意图不变），`setup.yml` 的 `paramsCreated` 含两个参数，快照更新；九个场景全部通过。
- **迁移编号照统一的顺序**：Q7b V12、Q7a V13、Q9e1 V14。Q7a 尚未合入，所以本分支上 V14 之前没有 V13；这没有问题——Flyway 只在部署时要求顺序（Q7a 必须在 Q9e 部署之前合入，否则 V13 会成为"已应用版本之前的新迁移"，部署时需 `outOfOrder` 或调整），记入此处供合并 Q7a 时检查。
- Q9e1 的评审修订（48478c4：概览的人数取任务的快照；五个模板改经卡片视图 `sponsor:QbPublicationCard`；任务改为逐项的 `QB_ANALYTICS_ITEM`）以合并提交合入（见下面"评审修订的合并"）。

### 实现（`quizbuks-web/sponsor/src/analytics/`）

- `ids.ts`（五个模板名）、`api.ts`（手写类型与转换、`trendParams`、读取、导出、按部署时区的标签）、`hooks.ts`（TanStack Query：每 60 秒与回到前台时重读，"现在"取 `qb/legal.ts` 的 `minuteNow()`，按服务端时钟）、
  `charts.tsx`（趋势的两张小图与 `DataDisclosure`）、`Funnel.tsx`、`Metric.tsx`、`DashboardCharts.tsx`、`PublicationAnalyticsPage.tsx`、`ExportButtons.tsx`、`messages.ts`（三语）。
- **仪表盘**（`pages/DashboardPage.tsx`，入驻通过后；未通过时的提示不变）：发布数（进行中 / 暂停 / 预算不足）、正在答题（实时）、今日支出（"自 00:00（Asia/Tokyo）起"）、可用余额、待支付、剩余预算；AI 用量的位置留空（注释，Q8a 加入）；
  近 7 天的完成人数折线与支出柱（上下两张图，不用双 Y 轴）；近 7 天热门发布前 5（表格，名称链到分析页）；"每 10 分钟更新，最近更新 …（时区）"或"尚未统计"。
- **发布分析页** `/publications/<id>/analytics`（发布页在交易链接旁加"分析"）：概览十项、漏斗、趋势（"过去 24 小时（按小时）/ 过去 7 天（按天）"，`SegmentedControl`）与 App 的四个窗口并列、题目表、导出。别人的发布或不存在即"不存在或不是你的"。
- **k 的显示**：页面不判断 k，只照模板的 `suppressed` 与 `minGroup`：平均用时与超时显示"暂不显示"，并注明"至少 k 人完成后显示（目前 n 人）"；题目全部隐藏时一条说明（含"此后每再新增至少 k 人才更新"），每行"至少 k 人作答后显示"；
  转换函数对 `suppressed` 的行一律丢弃数字（即使服务端误带了值）。
- **漏斗**：四段横条（对第一段的比例），每段人数与相对上一段的比率；流失最大的一段（模板的 `largestDrop`）以三种方式标出——带图标的徽标、斜纹的条、"最大的流失是从'开始'到'完成'：50% 没有继续"的句子——不只靠颜色；浏览少于开始时注明。
- **图表与无障碍**：只经 `@jabiz/ui` 的 `ChartContainer` / `ChartTooltip`（Recharts 3.10.1），颜色只用 `--chart-1`（完成人数、漏斗、正确率条）与 `--chart-2`（支出）；每张图 `role="img"` 与 `aria-label`（名称与合计，如"按天的完成人数：合计 21"），
  图下是原生 `<details>` 的数据表（键盘与读屏可用）；漏斗的数据表同样；题目的正确率条是表格中的装饰（`aria-hidden`），数值是文字。dataviz 的校验：两色在亮色下全部通过；暗色下 `--chart-2`（#95de64）高于亮度带，
  但两者各自单独成图（不需区分）、对背景的对比度通过，属于平台主题，未改。
- **时区**：小时与日期一律按任务记下的 `zone`（`qb.sponsor.dashboard`；任务未运行时为 UTC，与模板一致），以 `Intl.DateTimeFormat` 的 `timeZone` 显示；按天的标签用模板给出的 `day`（部署时区的日期）；浏览器的时区从不使用。
- **期间**：24 小时 = `from` 为 23 小时前、7 天 = 6 天前，`to` 为现在之后一分钟（使"现在"的桶总是最后一个，含整点时）；导出趋势带图表读到的同一组参数。
- **导出**：趋势、概览、题目的 CSV 与 Excel，经平台的 `POST /api/queries/{id}/export`（`sessionFetch`），失败显示服务端的原因。
- **e2e 的任务时刻**：任务原为固定的每 10 分钟；新增 `quizbuks.analytics.run-cron`（`QB_ANALYTICS_RUN_CRON`，缺省不变），CI 的 e2e 设为每分钟（第 15 秒）；正式模式或正式密钥下设成其他值即启动失败（`TransferScheduleCheck`，同 Q9c / Q9d 的两个开关，`TransferScheduleCheckTest`）。
  `deploy/quizbuks/README.md` 与 `operations.md` §1.4 同步。

### 与计划的偏离

1. 漏斗用 HTML 的横条而不是 Recharts（每段的文字本身就是数据，条只是图形）；趋势用 Recharts。
2. 概览的全部数字都是任务的快照（Q9e1 评审 M1），在概览标题旁注明一次"截至 {任务最近一次运行}"（模板的 `refreshedAt`）。仪表盘的"正在答题"与今日支出是实时的，标"实时"。
3. 仪表盘对已通过的商家不再显示入驻的提示框，只留一行说明（`dashboard-status` 照旧，供既有 e2e）。
4. 两个 SPA 的测试设置都加了 `configure({ asyncUtilTimeout: 5000 })`（App 的 `Play.test.tsx` 在高负载下以缺省的 1 秒超时失败过一次）。

### 测试

- Vitest：`analytics/api.test.ts`（期间参数、整点、东京的时与日、未知时区退回 UTC、补零、`suppressed` 丢弃数字、漏斗、地址）；`analytics/analytics.test.tsx`（仪表盘的指标卡、东京时间、图表名称与合计、数据表的展开与 7 行与暂停标记、
  热门发布到分析页；尚未统计与什么也没发生；读不到时重试；分析页的概览、"截至"、不足 k 的隐藏与说明、漏斗的最大流失（徽标、句子、表格）、题目的隐藏与正确率和难度、浏览少于开始、24 小时 / 7 天切换与参数、导出三种与参数、导出失败、他人的发布、概览读不到）；
  `analytics/messages.test.ts`（三语的键与占位符一致、非空、不写其他币种）。
- e2e `analytics.spec.ts`：两人答对后等任务统计（每分钟），仪表盘的发布数、正在答题、今日支出、余额、时区、7 天、热门发布；分析页的人数、完成率、k 的隐藏、漏斗、题目、24 小时 / 7 天与数据表；导出的 CSV（8 行，完成合计 2，不含答题人）；
  从发布页进入；另一商家以编号读概览与排行都读不到。
- e2e `a11y.spec.ts`：仪表盘与发布分析页（含展开的数据表、按天），亮、暗，1280 与 390 px；axe 之前先等各模板的应答。

### 验证

- `./gradlew :quizbuks:check`（真实 PostgreSQL 16，本机实例、独立数据库）：597 个测试全部通过（含合并后的九个场景与 `TransferScheduleCheckTest`），`platformCheck` 0 个错误、72 个警告（Q7b 的合并带来的增量，本阶段没有新模板）。
- 商家后台：`pnpm lint`、`pnpm typecheck`、`pnpm test`（23 个文件 149 个测试）；App：同上（21 个文件 133 个测试）；`pnpm app:check` 两个 SPA；`JABIZ_ADMIN_EXTENSION=../backend/quizbuks/admin-extension pnpm ext:check`（70 个测试）；两份 `src/qb/` 相同。
- e2e（打包的 jar，独立端口与数据库，Mailpit 与 Stripe 桩，`QB_ANALYTICS_RUN_CRON` 每分钟）：50 个全部通过，含 `analytics.spec.ts` 与 `a11y.spec.ts` 的分析页（亮、暗）。
  第一次运行时 axe 在 390 px 报 `scrollable-region-focusable`（窄屏上横向滚动的表格），已改为可聚焦的命名区域后通过。
- 之后合并了 `origin/1.2/quizbuks-9e1-analytics-data` 上新的合并提交（其合并 1.2/quizbuks；`analytics.yml` 多发布一个提案），场景再次全部通过。

### 未完成与已知问题

- （已解决）Q9e1 的评审修订已合并，见"评审修订的合并"。
- AI 用量卡由 Q8a 加入。

### 评审修订的合并（Q9e1 48478c4）

- 冲突：`AnalyticsConfig` 的任务定义取 Q9e1 的 `JobDefinition.forEach`（`qb.system.analytics-items` 的项、每项 `QB_ANALYTICS_ITEM`），本分支的 `quizbuks.analytics.run-cron`（`QB_ANALYTICS_RUN_CRON`）照旧作为它的 cron；设计 §6 取 Q9e1 的残余说明（只留一处"完成人数"）；计划把 Q9e1 的"评审修复"一节放在本记录之前。
- 五个模板的列不变（只有 `minGroup` 的精度 3 → 9 位，页面按数读取，不受影响）；概览的"截至"改为整个概览一处（所有人数都是快照），Vitest 随之改；e2e 本来就先等任务统计再读概览。
- 合并后重新运行（同样的独立数据库与端口）：`:quizbuks:check` 606 个测试全部通过、`platformCheck` 0 个错误 72 个警告；商家后台 149 个、App 133 个 Vitest 测试与 lint、typecheck；`app:check` 两个 SPA；`ext:check` 70 个；
  e2e 50 个全部通过（`QB_ANALYTICS_RUN_CRON` 每分钟驱动新的逐项任务，`analytics.spec.ts` 与分析页的 axe 亮、暗都通过）。

### 评审修订（Q9e2，独立评审 dc1c8d9）

评审没有阻塞的问题；以下各项都已改正并有测试。

- **M1 分析任务的时间是隐私控制**（概览是任务的快照，频率决定能把一个答题人的作答定位到多细）：`TransferScheduleCheck` 对 `quizbuks.analytics.run-cron` 的检查改为——缺省（每 10 分钟）以外的值**在任何环境**都要同时打开新的 e2e 开关 `quizbuks.e2e.enabled`（`QB_E2E_ENABLED`，缺省 `false`；正式模式或正式密钥下打开即启动失败），
  **比每分钟更频繁的一律拒绝**（以 Spring 的 `CronExpression` 在两天内求两次运行的最短间隔），无效的 cron 也报错；正式模式下其他值照旧拒绝。CI 的 e2e 设 `QB_E2E_ENABLED=true`。
  测试 `TransferScheduleCheckTest`（缺省总接受；每分钟只在开关打开且非正式模式时接受；每秒、每 30 秒、0 与 1 秒、10 与 40 秒都拒绝；最短间隔的计算；开关在正式模式下被拒）。`operations.md` §1.4 与 `deploy/quizbuks/README.md` 同步。
- **M2 时刻可能按 UTC 显示而不注明**：概览与趋势的模板加 `zone` 列（任务记下的部署时区，任务未运行时为 UTC）；分析页不再读仪表盘取时区（也就不再每分钟重读整个仪表盘），时刻只按模板给出的时区显示，"截至 …""统计于 …""最近更新 …"都注明时区。`AnalyticsIT` 断言趋势、合计与概览的 `zone` 为 `Asia/Tokyo`。
- **L1 页面自己相加**：新模板 `qb.sponsor.analytics.trend-totals`（同样的参数与同样的桶，写法同趋势、改时一起改）给出合计，图表的名称与数据表的合计行都取它；`AnalyticsIT` 断言它等于各桶之和、他人读到全零、权限矩阵同其他模板。
  仪表盘的 7 天图（今天与之前 6 天的日历日）与热门发布（近 7 × 24 小时）窗口不同，两处各自注明。漏斗的流失比例 `1 - rate` 是页面唯一自己算的数（取自模板的比率），在 `funnelOf` 注明。
- **L2** `toOverview` 在 `suppressed` 时丢弃平均用时与超时人数（同 `toQuestion`），`api.test.ts` 覆盖。
- **L3** 完成数已到 k、但按时提交不足 k 时，平均用时的说明为"至少 k 人按时提交后显示"。
- **L4** 分析页的"正在答题"改为"进行中"，并注明截至时刻（仪表盘的合计仍为实时的"正在答题"）。
- **L5 文案**：日文的获奖者一律"当選者"（页面与后端消息）；"予算上限を設定した公開中の公開が対象です"、漏斗句"…「{from}」と「{to}」の間で、…"、App 窗口的说明改写；"预算不足 / Short of funds"与状态徽标一致为"钱包余额不足 / Wallet too low / ウォレット残高不足"（页面与后端消息）；
  英文的"基于 n 个完成"用 i18next 的复数（`_one` / `_other`，中、日两键同文），数字另以 `n` 传入。`messages.test.ts` 另断言日文没有"受賞"、余额不足与发布页的用词相同。
- **L6** `TransferScheduleCheck` 的 Javadoc 改为三个开关与分析的缺省。
- **L7** Vitest 断言他人的发布既不读题目也不读趋势与合计；e2e `analytics.spec.ts` 另以另一商家打开同一地址，页面"不存在"，且除概览外不请求任何分析模板。
- **小处**：24 小时的数据表中，每天的第一行带日期（跨过午夜的那一行）；趋势的合计行；支出的提示保留系列名（`formatter` 会替换整行，故自己写名称与金额），图例名改为按语言的名称；宽屏布局改为 `xl` 才分栏、App 窗口两列。
- **验证**（独立数据库 `jabiz_test_q9e2` / `quizbuks_e2e_q9e2`、独立端口）：`:quizbuks:check` 608 个测试全部通过，`platformCheck` 0 个错误、72 个警告（没有新的警告）；商家后台 Vitest 152 个、App 133 个，两者 lint 与 typecheck；`app:check` 两个 SPA；`ext:check` 70 个；
  e2e 50 个全部通过（`QB_ANALYTICS_RUN_CRON` 每分钟并 `QB_E2E_ENABLED=true`；含另一商家打开分析页的检查）。
