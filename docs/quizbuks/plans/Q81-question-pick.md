# Q81 发布从题库抽题：每人随机 k 题（计划，2026-11-02，需求方已确认；已实现）

`05-console-requests.md` 第二项（"允许发布通过题库中的题目选择几个，这样可以提高考试难度"）；需求方定下的顺序是 Q78 → Q79 → 17zc / Q80 → **Q81** → Q82。编号 Q81 是建议（下一个空号），确认时可改。
**这只是计划**：确认 §9 的决定后开发，一个 PR，工作分支 `1.3/quizbuks-81-question-pick`。

## 0. 为什么

- 现在一个版本的全部题目按同一顺序出给每个人，只有选项顺序不同（Q10b）。相邻两人对一下答案就能照抄，题库再大也没用。
- **抽题**让每个人答到的是题库的一个随机子集：题不同、选项顺序也不同，答案无法照抄；题库越大、k 越小越难猜。配合 Q75（看完视频）与 Q76（前置条件）形成"学—考"链。
- 它是**发布层的设置**，不改题库（版本）本身：同一版本可以一个发布全考、另一个抽 5 题；版本快照、翻译、题目统计的键都不变。

## 1. 现状

- 版本快照 `QuizSnapshot` 是全部题目按序，题号 1..n 连续；满分 `fullScore()` = 分数之和。保存草稿时发布的 `fullScore` 取版本的（`PublicationProcesses` 第 404 行附近），`passScore ≤ fullScore`，档位覆盖 0..满分（`TierCoverage`），都在 `PublicationReadiness.check`。版本切换（Q51，`PublicationVersions`）要求满分相同。
- 卡片冻结 `questionCount`、`fullScore`，`qb.app.feed` / `qb.app.publication` / `qb.public.*` / `qb.app.history` 读卡片；App 详情页显示"n 题"与"及格 / 满分"。
- 答题的一切都是**按位置**的：开始时 `OptionShuffle.order(user, pub, snapshot.questions())` 得到每题的选项顺序（列表下标 = 题的位置），`answers` 同样按位置；
  `Answers.check` 把输入的 `questionNo` 当作位置 1..order.size()（App 发的是 `QuestionView.no` = 版本题号，两者在全考时相同）；
  `Scoring.score` 遍历 `snapshot.questions()`，结果行的 `no` 是版本题号，写进 `result`；`AttemptResults` 把 `questions`（按会话的选项顺序）与 `result` 一起给结果页，App 按 `no` 对齐。
- 题目统计：`qb.system.analytics-attempts` 从每个会话的 `result` 按 `no` 数每题的完成数与答对数（`qCompletions`、`qCorrect`），`QuestionTotals` 按版本累计，`AnalyticsJob.showQuestions` 在版本新增 ≥ k 个完成时整版发表（`basedOn` = 版本完成数），`qb.sponsor.analytics.questions` 以版本快照的题目连接、每题 `completions ≥ k` 才显示。
- 店面（S2）的开始走同一个 `AttemptProcesses.start`（`ChannelPlay`），活动没有发布的设置。

## 2. 决定（需求方 2026-11-02 确认）

| # | 决定 |
|---|---|
| D-Q81-1 | **抽题是发布的设置** `pickCount`（k）：`1 ≤ k < n`（n = 版本题数）；空 = 全考（现状不变）。k = n 不接受（`QB_PUBLICATION_PICK_RANGE`：全考就留空）。草稿可改，提交即冻结，复制随发布复制，审批内容含它。 |
| D-Q81-2 | **只允许同分的题库**：抽题的发布要求版本全部题目分数相同（`QB_PUBLICATION_PICK_UNEQUAL_POINTS`），**满分 = k × 每题分**（`PublicationEditing.effectiveFullScore`），保存时写进发布的 `fullScore`、上架时冻结到卡片；及格线、档位、`SaveOutput.fullScore`（向导的提示）都按它。问卷不抽题（`QB_PUBLICATION_PICK_SURVEY`）。 |
| D-Q81-3 | **每次开始抽一组**，纯 Java 的 `play.QuestionPick`：种子同选项打乱（HMAC(`JABIZ_INTEGRITY_KEY` 派生, `qb.question-pick`) 对 用户 \| 发布），从 n 题中取 k 题、**保留版本的题序**；抽到的版本题号记在会话 `questionSet`（JSON，不可变、`processOnly`，不进答题人视图），全考的会话为空。一人一次，所以一人只有一组。 |
| D-Q81-4 | **会话的快照 = 版本快照先按语言投影（`QuizSnapshots.in`，译文键是版本题号）再取子集**（`QuizSnapshot.subset(nos)`，题目保留版本题号）：`PlaySteps.forAttempt(ctx, attempt)` 一处给出，开始、继续、提交、超时、结果都用它。选项打乱、`answers`、`Scoring`、`Answers.incomplete` 因此**不改**（它们本来就按子集的位置工作）。 |
| D-Q81-5 | **答题人看到的题号是位置 1..k**（与选项的编号规则一致，Q10b M1）：`AttemptProcesses.questions` 的 `QuestionView.no` 改为位置；`QB_ATTEMPT_RESULT` 的 `result[i].no` 同样给位置（结果行按子集顺序，位置 = 下标 + 1）。全考时位置 = 版本题号，输出逐字不变。**存储的 `result` 行仍记版本题号**（统计的键）。App 与 e2e 不改（它们发回 `question.no`）。 |
| D-Q81-6 | **题目统计的分母是每题被抽到的完成数**：`analytics-attempts` 已按 `result` 行数每题（抽题后 = 被抽到且完成的人数），`QbQuestionStat.completions` 的含义自然成立；改的是**发表节奏**：`showQuestions` 逐题判断 `MinGroup.publish(该题完成数, 该行已发表的完成数, k)`（全考时每题与版本同步增长，行为与今天相同）。`basedOn` 仍是版本完成数；商户的题目分析页注明"抽题的发布：每题的人数是被抽到的人数"。 |
| D-Q81-7 | **版本切换**（Q51）：新版本须同样全部同分且题数 > k，`k × p` 等于发布的满分（`QB_PUBLICATION_SWITCH_PICK`，取代满分比较中的一部分；不抽题的发布照旧比较满分）；切换时卡片的 `questionCount` 随新版本、`pickCount` 不变。 |
| D-Q81-8 | **店面**（S2）第一步不支持抽题：活动没有这个设置，`ChannelPublishing` 的卡片 `pickCount` 为空，`ChannelPlay` 经同一个开始、全考；以后再接。 |
| D-Q81-9 | **卡片的 `questionCount` 仍是版本题数 n**，另加 `pickCount`；首页、详情、公开主页、分享页显示"从 n 题中随机 k 题"；`fullScore` 是 k × p。答题人的历史（`qb.app.history`）读卡片的满分，与会话的一致。 |
| D-Q81-10 | 不加业务参数、不加权限；语句数不变（抽题在内存中，`pickCount` 在已读的卡片上；`AttemptStatementBudgetIT` 的四个数不变）。 |

## 3. 后端（`backend/quizbuks`）

### 3.1 迁移 V62 `quizbuks_question_pick`（Q76 用了 V60，Q77 用了 V61）

- `qb_publication_version ADD COLUMN pick_count integer`（只追加表，只加列，D58；`SealedColumnsHistory` 照常）。
- `qb_publication_card ADD COLUMN pick_count integer`（普通表）。
- `qb_attempt ADD COLUMN question_set varchar(2000)`（普通表；`PlayLimits.QUESTION_SET_LENGTH`，足够 `MAX_QUESTIONS` 个题号）。
- 不加索引（没有按它查询的地方）。

### 3.2 发布（`publishing`）

- `QbPublications`：发布 `pickCount`（`asNumeric(4, 0)`，`Rules.range(PICK_RANGE, 1, MAX_QUESTIONS)`）；卡片 `pickCount`（`immutable`）。
- `PublicationProcesses.SaveInput` 加 `Integer pickCount`；保存时 `fullScore = PublicationEditing.effectiveFullScore(snapshot, pickCount)`（同分且 k 合法时 k × p，否则版本满分：问题由 `PublicationReadiness` 报）。`PublicationReadiness.Draft` 加 `pickCount`，`check` 新增三条（§2 D-1、D-2），`TierCoverage` 与及格线用有效满分。
- `PublicationEditing.write`（卡片）：`pickCount` 随 `fullScore` 一起冻结；复制（`copy`）带上；`PublicationApprovals.content` 加 `pickCount`。
- `PublicationVersions`：D-Q81-7 的检查，切换时卡片 `questionCount` 取新版本。
- `ChannelPublishing`：卡片 `pickCount` 空（不改输入）。
- `PublishingCodes`：`PICK_RANGE`、`PICK_UNEQUAL_POINTS`、`PICK_SURVEY`、`SWITCH_PICK`。

### 3.3 答题（`play`）

- `QuestionPick`（纯 Java，照 `OptionShuffle`：`of(IntegrityKey)`，`LABEL = "qb.question-pick"`，`pick(userId, publicationId, questions, k)` 返回保留顺序的题号列表；Fisher-Yates 取前 k 再排序）。`PlayConfig` 提供 Bean，`AttemptProcesses.start(…)` 多一个参数。
- `QuizSnapshot.subset(List<Integer> nos)`（core 的 record 方法，题目按给出的顺序、保留题号；不存在的题号抛 `IllegalArgumentException`）。
- `QbPlay.ATTEMPT`：`questionSet`（`immutable`、`processOnly`、`asText`）；答题人视图 `taker:QbAttempt` 不列它。
- `PlaySteps.forAttempt(ctx, attempt)` = `subset(inLanguage(snapshot, attempt), questionSet)`；开始时 `pickCount` 来自卡片：`nos = pick.pick(user, pub, inLanguage(snapshot).questions(), k)`，`subset` 后再 `shuffle.order(...)`、`answers = none(k)`、`questionSet = Answers.toJson(nos)`（复用 `Answers` 的 JSON）。
- `AttemptProcesses.questions`：`QuestionView.no` = 位置；`AttemptResults.read`：`QuestionOutput.no` = 下标 + 1；`AttemptFinishing`、超时同样经 `forAttempt`。
- `AttemptStatementBudgetIT` 不变（断言）。

### 3.4 统计（`stats`）

- `AnalyticsJob.showQuestions`：逐题 `MinGroup.publish(count.completions(), 已发表行的 completions, k)`，`basedOn` 照旧版本完成数；`reads` 不改。
- `qb.sponsor.analytics.questions`：不改 SQL（`completions ≥ k` 的条件已是每题的）；结果加 `pickCount`（卡片）供页面注明。

### 3.5 模板

- 卡片的 `pickCount` 加进 `qb.app.feed`、`qb.app.publication`、`qb.public.publication`、`qb.public.sponsor-page-publications`、`qb.sponsor.publications`（列表）、`qb.sponsor.publication`（详情）、`qb.admin.publication`（审核页）。`PublicQueriesSnapshotIT` 快照随之更新。

### 3.6 文案

- 规则代码三语：`QB_PUBLICATION_PICK_RANGE`（"抽题数须在 1 与题数之间；全考请留空"）、`PICK_UNEQUAL_POINTS`（"抽题要求每题分数相同"）、`PICK_SURVEY`（"问卷不能抽题"）、`SWITCH_PICK`。
- `entity.QbPublication.pickCount`、`entity.QbPublicationCard.pickCount`、`entity.QbAttempt.questionSet` 三语。

## 4. 前端

### 4.1 商户后台（`quizbuks-web/sponsor`）

- 向导第二步（规则）：及格线之前加"每人抽题数"（`publication-pick-count`，可空；提示"题库 n 题，每题 p 分；抽 k 题满分 k × p"，n、p 来自所选版本的快照（向导已读版本列表的 `questionCount`，每题分需要 `content/api.ts` 读版本题目的分数：已有版本详情则取之，否则由 `SaveOutput.fullScore` 回报）；不同分的题库显示保存返回的问题，不前端重算）。`api.ts` 的 `Draft` / `SaveInput` / `SaveOutput` 加 `pickCount`。
- 发布详情（非草稿）与列表卡片显示"随机 k / n 题"；分析的题目页注明分母。
- 文案 `publishing.field.pickCount*` 三语。

### 4.2 App（`quizbuks-web/app`）

- `qb/api.ts` 的发布类型加 `pickCount`；详情页的"n 题"事实改为"从 n 题中随机 k 题"（有 k 时），`SharePage` 同；答题页、结果页**不改**（D-Q81-5）。
- 文案 `detail.pickCount` 三语。

### 4.3 后台扩展

- 审核页显示"抽 k / n 题"（`publication.pickCount`），审批内容已含。

## 5. 测试

- core：`QuestionPickTest`（同一人同一发布同一组、不同人不同组、保留题序、k 的边界、不同密钥不同组）、`QuizSnapshotTest.subset`、`PublicationReadinessTest`（三条新规则、有效满分进入及格线与档位）。
- IT：`QuestionPickIT`（抽题的发布：开始的 `questions` 为 k 题、题号 1..k、`questionSet` 不在答题人视图；保存与提交按位置；评分只对子集、满分 k × p；结果页的 `no` 是位置、与 `questions` 对齐；两个人的题集不同——以固定密钥断言确切的组）；`AnswerSecrecyIT` 照旧；`PublicationVersionsIT` 加切换的 D-Q81-7；`AnalyticsIT` 加抽题发布的每题完成数与逐题发表；`AttemptStatementBudgetIT` 四个数不变（断言）。
- 场景：`play-pick.yml`（固定时钟与密钥，快照含两个答题人各自的 k 题与得分）。
- 前端：向导的新字段与问题显示；App 详情的"随机 k / n 题"；e2e：`publishing.spec.ts` 加一个抽题的发布并由一位答题人答完（`submitRight` 已按 `q.no` 发回，k 题的位置就是 1..k）。

## 6. 文档

- `02-design.md` §7（答题）一段：抽题的定义、位置编号、统计分母；`backend/quizbuks/CLAUDE.md` 第 4 节"答题"加一条（会话的快照只经 `PlaySteps.forAttempt`，题号是位置，`result` 记版本题号；抽题的发布要求同分）；`05-console-requests.md` 第二项与 `03-plan.md` 的 Q81 行；本文件 §10 实施记录。

## 7. 估计

| 部分 | 天 |
|---|---|
| 迁移、发布的字段、就绪检查、满分、卡片、复制、审批、版本切换 | 1 |
| `QuestionPick`、`subset`、`forAttempt`、位置编号、结果 | 1 |
| 统计的逐题发表与测试 | 0.5 |
| 模板与两端前端、后台扩展 | 1 |
| 测试（IT、场景、e2e）与文档 | 1 |
| **合计** | **4.5** |

## 8. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 中 | 题号从版本题号改为位置：全考时数值相同，但任何按"版本题号"理解 `QuestionView.no` 的地方（译文键、统计）都要走版本题号 | 子集保留版本题号，只在两个输出层换成位置；`QuestionPickIT` 断言全考的输出逐字不变 |
| 中 | 题目统计的隐私：每题的人数少于版本的 | 逐题的最小分组（D-Q81-6），`AnalyticsIT` 覆盖"版本够 k、某题不够 k 不显示" |
| 低 | 不同分的题库不能抽题，商户要先改分 | 向导的提示与保存的问题说明；题库同分是常态 |
| 低 | 版本切换的新规则 | 只对抽题的发布生效，全考照旧 |
| 低 | 店面不支持 | 卡片 `pickCount` 空即全考，无需改 Shopify 模块 |

## 9. 需要需求方确认的

1. D-Q81-1 / D-Q81-2：抽题是发布的设置；**只允许同分的题库**，满分 = k × 每题分；问卷不抽题。
2. D-Q81-3 / D-Q81-5：每人一组、保留题序；答题人看到的题号是位置 1..k（不是题库的题号）。
3. D-Q81-6：题目统计的分母是被抽到的完成数，逐题最小分组。
4. D-Q81-7：版本切换对抽题发布的新规则。
5. D-Q81-8：店面第一步不支持。
6. 编号 Q81，一个 PR，4.5 天。

## 10. 实施记录（2026-11-02，分支 `1.3/quizbuks-81-question-pick`）

需求方 2026-11-02 确认 §9 的六项（“同意 Q81 的决定，开始开发”）。按计划实施，与计划的差别与细节：

- 迁移 V62（计划写 V61；Q77 先合入用了它）：`qb_publication_version.pick_count`、`qb_publication_card.pick_count`、`qb_attempt.question_set varchar(500)`。
- 发布：`QbPublications` 两个 `pickCount`；`PublicationReadiness.Draft` 加 `pickCount`（旧构造器保留），`pickProblems` / `fullScore` / `commonPoints` 是公开的纯 Java；`PublicationEditing.fullScoreOf`
  由 `PublicationProcesses.putVersion` 调用（保存与复制都先放 `pickCount` 再放版本的部分）；卡片与复制的字段清单加 `pickCount`；`PublicationApprovals.content` 只在有抽题时写 `pickCount`（旧哈希不变）；
  `PublicationVersions` 先查 `SWITCH_PICK` 再比较满分（抽题的满分按新版本算）。`SaveInput` 加最后一个组件 `pickCount`（旧构造器保留）。
- 答题：`QuestionPick`（照 `OptionShuffle`，`toJson` / `fromJson` 复用 `Answers` 的 mapper）；`QuizSnapshot.subset`；`QbPlay.ATTEMPT.questionSet` **标 `sensitive()`**（计划写 `processOnly`、不进答题人视图：敏感字段任何读接口都不返回，与 `optionOrder` 同一规则，更直接）；
  `PlaySteps.subset` / `forAttempt`；`AttemptProcesses.start(shuffle, pick)`（`ChannelPlay.start` 同），开始时 `pickCount` 取自卡片；`questions()` 的 `no` = 位置；`AttemptFinishing` 的提交输出与 `AttemptResults` 的结果行都按位置编号（计划只写了结果页，提交输出同理，否则 App 的结果页对不上）。
- 统计：`AnalyticsJob.showQuestions` 多一个 `k`，逐题 `MinGroup.publish(该题完成数, 已发表行的 completions, k)`；模板不改。
- 模板：`qb.app.feed`、`qb.app.publication`、`qb.public.publication`、`qb.public.sponsor-page-publications` 加 `pickCount`；`PublishingConfig` 的公开白名单加它；`public-queries.json` 快照更新。
  列表模板（`qb.sponsor.publications`、`qb.admin.publications`）不带分数字段，未加；商户详情与审核页经数据视图读发布，自然带 `pickCount`。
- 文案：四个规则代码与三个实体字段三语；商户后台 `publishing.field.pickCount*`、`publishing.summary.pickCountOf|pickCountOnly`（`pick` 与现有键重名，改名）；App `detail.pickCount`；后台扩展 `publication.pickCount|pickCountValue`。
- 前端：向导第二步“每人抽题数”（`publication-pick-count`，提示给题数与每题分；满分只在版本同分、k 合法时由页面预估，否则取保存返回的）；摘要行 `summary-pick`（详情页由版本给出 n）；App 详情与分享页“从 n 题中随机 k 题”；审核页一行 `review-pick-count`。答题页、结果页不改。
- 测试：`QuestionPickTest`、`QuizSnapshotsTest.aSubsetKeepsTheQuestionsNumbersAndOrder`、`PublicationReadinessTest` 两个；`QuestionPickIT`（三题 1..3、题集在 1..5 内递增、继续相同、答题人视图无 `questionSet` / `optionOrder`、第四题 422、满分 3 与顶档、存储记版本题号、结果页位置；十二个人的题集不止一组；保存 / 复制 / 切换的规则）、`AnalyticsPickIT`（每题按被抽到的完成数、少于 k 的题没有行）；
  现有 `AttemptOptionIdsIT`、`AttemptResultIT`、`PublicationSwitchIT`、`AnalyticsVersionsIT`、`AttemptStatementBudgetIT`（四个数不变）、`AnswerSecrecyIT` 通过。**没有加场景 `play-pick.yml`**（计划 §5）：场景要先发布 72 个受控提案，`QuestionPickIT` 以测试的固定密钥断言确切的行为已够。
  前端：商户向导的 Q81 测试（发送 `pickCount: 3`、及格线提示 (3)、摘要“3 of 4 at random”）、App 详情的 Q81 测试；e2e `transactions.spec.ts` 加抽 3 / 5 题的答题（本机未运行，CI 的 e2e 作业运行）。
- 未做：店面（D-Q81-8）；后台扩展只显示。
