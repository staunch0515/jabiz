# F-S7 抽题与题目乱序、难度（实施计划）

规格：`first-launch-spec.md` 的 F-S7（依赖 F-S6）。负责：S1（总控 2026-11-08 改派）。一个 PR，在 F-S6a 之后。删除的功能：无（"旧版本不乱序"的原建议由 A2-14 的回答取代，这是规格的改动，不是删除已有功能：现在本来就不乱序）。

## 0. 现状

- 抽题（Q81，`play.QuestionPick`）：
  - HMAC 种子（标签 `qb.question-pick`，输入 `userId|publicationId`），Fisher-Yates 取前 k 个，**再按版本题号排序**；
  - `pickCount` 在发布上，保存版本或切换时检查 `PICK_RANGE` / `PICK_SURVEY` / `PICK_UNEQUAL_POINTS` / `SWITCH_PICK`。
- `questionSet` 只在抽题时写（null 表示全部）。
- `QuizSnapshot.subset(nos)` 保持给出的顺序。学者看到的题号就是位置（`i + 1`）；`result` 记版本题号。
- 选项已经乱序（`OptionShuffle`，同一个模式）。
- 问卷：`QbQuiz.kind = SURVEY`。

## 1. 乱序

- **开关**：Quiz 的设置 `shuffleQuestions`。
  - 存储：`QbQuiz` 加列（迁移）。缺省 `true`，已有的 Quiz 迁移时也设为 `true`，即 A2-14：所有版本缺省乱序。
  - 由 `QB_QUIZ_SAVE` 写入（`QuizInput` 加组件，保留旧的构造器）。
  - 问卷：开关不显示、不起作用，问卷不乱序。
- **快照**：
  - 只在不乱序时写 `"questionOrder":"fixed"`（照 `kind` / `selection` 的"只写非缺省值"），已有版本的哈希不变；
  - 没有这一项的快照按乱序处理（A2-14：旧版本也乱序）；
  - Quiz 文件格式（导出导入）与复制带上这一设置。
- **种子与排列**：
  - 新标签 `qb.question-order`，输入同抽题（`userId|publicationId`）；
  - 先抽题，抽题照旧取 k 个；再以乱序种子对这 k 个（或全部）排列；
  - 只要乱序，无论是否抽题都把排列后的题号写进 `questionSet`；不乱序且不抽题时照旧为 null。
  - 新类 `play.QuestionOrder`（同 `OptionShuffle` 的写法，纯函数），`QuestionPick.pick` 不再排序：它返回集合，由 `QuestionOrder` 定顺序；固定顺序时按版本题号排。
- **题号**：学者看到的是位置 1..k，照旧；`result` 记版本题号，统计照旧。
- **预览**：发起方的预览注明"学者看到的顺序是随机的"（乱序时），预览自己按版本顺序显示。
- **显示**：
  - 发布详情（发起方的 `PublicationSummary`）与平台管理系统的审核页显示"题目乱序 / 固定顺序"；
  - Quiz 设置表单中是开关，附说明。
- **测试与场景**：测试与场景中用的 Quiz 显式关掉乱序（`shuffleQuestions: false`），以保持现有的快照与断言；只在专门的测试中打开。

## 2. 难度（需求方 2026-11-07 晚提出，2026-11-08 确认"按建议"）

**方案（已确认）**：

- 档位是平台的固定枚举 `QuestionDifficulty`：`EASY`（易）、`MEDIUM`（中）、`HARD`（难）。
  - 名称三语写在消息 `qb.difficulty.<档位>`，例如"易 / 中 / 难""Easy / Medium / Hard""やさしい / ふつう / むずかしい"；
  - 改档位要改代码，改名称只改消息。
- 每道题的难度可以不设，即 null。
  - 存储：`QbQuestion.difficulty`（迁移，可空）；
  - 快照：只在设了时写，旧哈希不变；
  - 导入导出与复制带上难度，AI 出题按请求的难度填上（AI 请求已有 `difficulty`）。
- **按难度抽题**：发布的抽题设置可以按档位给配额 `pickByDifficulty: {EASY: a, MEDIUM: b, HARD: c}`，代替 `pickCount`。
  - k = a + b + c；
  - 每档在该档的题中按抽题种子抽，再合起来排列；
  - 规则：
    - 每档配额 ≤ 该档题数，否则 `PICK_DIFFICULTY_SHORT`；
    - 题库中有题没设难度时不能按难度抽，否则 `PICK_DIFFICULTY_UNSET`；
    - 满分照 Q81 的同分规则（全库同分），k × 每题分；
    - 版本切换检查配额仍满足，否则 `SWITCH_PICK`。
  - 存储：发布与卡片加 `pick_by_difficulty`（JSON 文本，迁移），只在设了时进审批哈希（照 `pickCount`）。
  - 参数：配额是发布自己的数据，不需要平台参数。总控建议的"抽题配额按档位用参数"理解为配额的上限仍是 `qb.play.max-questions-per-attempt`，不另建参数。
- **名称冲突**：统计模板 `analytics-questions.sql` 已经输出按正确率算出的 `difficulty`。改名为 `observedDifficulty`，前端随之改，显示文字不变。
- 编辑器：每题一个难度下拉（不设 / 易 / 中 / 难），批量设难度（F-S6b 的批量操作中加一项），题目列表可以按难度筛选。
- 发布向导：抽题方式"整库随机 k 题 / 按难度"。按难度时显示各档题数与配额输入。

## 3. 统计

- 每题的被抽人数：`QbQuestionStat.drawn`，见 `F-S6.md` §4，本 PR 实现。
- 按难度的正确率：发起方的题目统计可以按设定的难度分组，这是现有数据的分组，不新建表。

## 4. 迁移（开 PR 时取当时最大号 + 1）

- `qb_quiz_version.shuffle_questions boolean NOT NULL DEFAULT true`；
- `qb_question_version.difficulty varchar(8)`；
- `qb_publication_version.pick_by_difficulty` 与 `qb_publication_card.pick_by_difficulty`；
- `qb_question_stat.drawn`。

## 5. 测试清单

- `QuestionOrderTest`（jqwik）：
  - 同一人、同一发布顺序不变；
  - 不同人顺序不同（统计意义上）；
  - 是全部或所抽题号的排列；
  - 问卷不乱序；
  - 关闭后按版本题号。
- `QuestionPickTest`：按难度抽题，各档个数正确且只取该档。
- `QuestionPickIT`：
  - 抽题 + 乱序一例；
  - 按难度一例；
  - 规则码 `PICK_DIFFICULTY_SHORT` / `_UNSET`；
  - 版本切换后配额不满足即 `SWITCH_PICK`。
- `QuizSnapshotsTest`：旧快照的哈希不变；固定顺序与难度只在设了时写。
- `SurveyIT`：问卷不乱序。
- `AttemptStatementBudgetIT` 不变。
- 场景：
  - 现有的 Quiz 显式关掉乱序；
  - 新场景 `qb/play-order.yml`：乱序、抽题、按难度；
  - 更新快照。
- 前端：设置开关、难度下拉与批量、向导的按难度抽题、预览提示、详情与审核页的显示（Vitest）；e2e 在 `content-bank.spec.ts` 中加一例（按难度抽题的发布，学者作答）。
- 压测：`loadTest` 加 `LOAD_DRAW`（抽题数），千题、每人 20 题重测，写进 `docs/quizbuks/perf/`（`operations.md` §… 的待办）。

## 6. 风险

- 场景快照大改：缺省乱序之后，每个创建 Quiz 的场景都要显式关掉乱序，一次改完，审查时逐个核对。
- 难度的档位与名称已确认（2026-11-08）：以后要改档位改枚举，改名称只改消息。

## 7. 实施记录（2026-11-09，分支 `1.3/quizbuks-FS7-shuffle`）

### 7.1 做了什么

- **迁移** `V99__quizbuks_question_order.sql`与 Shopify 模块的 `V100__quizbuks_shopify_shuffle.sql`（只加列；`qb_question_stat` 是普通表，已有行的 `drawn` 设为 `completions`）。
- **乱序**：
  - `QbQuiz.shuffleQuestions`（缺省 `true`）；`QB_QUIZ_SAVE` 的 `QuizInput.shuffleQuestions`（`Boolean`：不给即新 Quiz 乱序、已有的保持，旧构造器保留）。
  - `QuizSnapshot` 加组件 `fixedOrder`（旧的九参构造器保留，即乱序），`shuffled()` = 非问卷且非固定；问卷的 `fixedOrder` 一律为 false（不保存设置）。
    `QuizSnapshots` 只在固定顺序时写 `"questionOrder":"fixed"`，读到其他值即拒绝；`in` / `subset` / 复制 / 导入都带上它。
  - `play.QuestionOrder`（HMAC 标签 `qb.question-order`，输入 `userId|publicationId`，按题号排序后 Fisher-Yates，与给出的顺序无关）。
  - `QuestionPick.set(userId, publicationId, snapshot, card)`：先抽（`pickByDifficulty` 优先，再 `pickCount`），再由 `QuestionOrder` 定顺序；
    不抽且固定顺序时返回 null（`questionSet` 照旧为空）。`QuestionPick.pick` 照旧返回按题号排的集合（`QuestionPickTest` 不变）。
    `AttemptProcesses.start` 只改了抽题那几行（与 S2 约定），签名、`ChannelPlay`、`PlayConfig` 不变；语句数不变。
- **难度**：
  - `content.QuestionDifficulty`（`EASY` / `MEDIUM` / `HARD`），字典 `urn:jabiz:dict:quizbuks:question-difficulty`，名称在消息 `qb.difficulty.<档位>`（三语）。
  - `QbQuestion.difficulty`；`QB_QUESTION_SAVE` 的 `difficulty`（不给即保持，空串清除）；批量 `QB_QUESTION_DIFFICULTY_SET`（≤ 200 题，同 `QB_QUESTION_POINTS_SET`）；
    AI 出题接受时填上请求的难度；快照只在设了时写；Quiz 文件（格式版本仍为 3，见 7.2）与复制带上。
  - 发布 `pickByDifficulty`（`SaveInput` 加组件，旧构造器保留）：保存时 `pickCount` = 各档之和；规则 `PublicationReadiness.difficultyProblems`：
    `QB_PUBLICATION_PICK_DIFFICULTY_INVALID`（档位不对或负数）、`_UNSET`（有题没设难度）、`_SHORT`（某档不够）、问卷 `PICK_SURVEY`；
    审批内容只在设了时加 `pickByDifficulty`；卡片冻结它；版本切换不满足即 `SWITCH_PICK`；复制发布带上它。
- **模板**：
  - `qb.sponsor.quiz-questions` 加结果 `difficulty` 与参数 `difficulty`（`NONE` 为未设）；
  - `qb.sponsor.quiz-versions` 加 `shuffled` 与 `easyCount` / `mediumCount` / `hardCount`（向导与详情用）；
  - `qb.app.feed` 的 `q` 加卡片主题词的名称（任一语言）与自定义词；
  - `qb.sponsor.analytics.questions` 的 `difficulty` 改名 `observedDifficulty`（显示文字不变），新的 `difficulty` 是设定的难度，另加 `drawn`。
- **统计 `drawn`**：`analytics-attempts.sql` 每题加 `qDrawn`，`QuestionTotals.Count` 加 `drawn`（只在与完成数不同时写第四个数，已有的累计文本不变），
  `QbQuestionStat.drawn` 随其他数字一起按最小分组发表。
- **前端**（发起方页面）：Quiz 设置的乱序开关与说明（问卷不显示）；题目对话框的难度下拉；批量设难度；按难度筛选；行上的难度标记；
  发布向导"按难度抽题"（各档题数、配额、合计、未设难度的提示）；预览的乱序提示；发布详情与向导摘要的"按难度抽题"与"题目顺序"；
  题目统计的"设定的难度""被抽到"与按设定难度的正确率。平台管理系统的审核页显示按难度抽题与题目顺序。导出只在非缺省时写 `shuffleQuestions: false` 与 `difficulty`。
- **压测**：`loadTest` 加 `LOAD_DRAW`（每人抽题数，0 = 全部）。本 PR 没有对运行中的应用重测（需要部署的 jar），千题、每人 20 题的重测留作待办。

### 7.2 与计划的差别

- **`drawn` 的口径**：`question_set` 是敏感字段，SQL 模板不能读（设计 02 §6.1），所以 `drawn` 按读到的（完成或超时的）答题中 `result` 列出该题的次数计算：
  `result` 列出答题被给的每一题（答没答都有），所以等于"被抽到且结束"的人数，进行中的答题在结束时计入。今天它与 `completions` 相同；
  分开保存是为了让统计页明说"被抽到"，以后完成数改口径时它不变。
  **完成后才计入**（总部 2026-11-09 确认）：进行中的答题不计，结束后由下一次 `qb.analytics` 计入；题目统计在"被抽到"一栏旁注明"完成后才计入"（三语），表下另有一句说明；
  `operations.md` §7.7 与帮助底稿 `help/large-banks.md` 同样写明。
- **Quiz 文件格式不升版本**：`quiz.shuffleQuestions` 与 `questions[].difficulty` 是版本 3 中的可选键，旧的读取方跳过它们（不拒绝文件）。
- **店面（Shopify 活动）缺省乱序，店员可以关掉**（总部 2026-11-09 确认）：按 A2-14 缺省乱序；活动编辑页有与发起方 Quiz 设置相同的"题目乱序"开关（`@jabiz/ui` 的 `Switch`，三语说明）。
  `QBS_CAMPAIGN_SAVE` 加 `shuffleQuestions`（与 `QB_QUIZ_SAVE` 同名同义：不给即新草稿乱序、编辑保持），`qbs_campaign.shuffle_questions`（迁移 `V100__quizbuks_shopify_shuffle.sql`，已有活动为 true），
  活动的复制带上它；发布时经渠道合约 `Channel.PublishInput.shuffleQuestions`（旧构造器保留，即乱序）交给 `ChannelPublishing`，关掉时快照写 `"questionOrder":"fixed"`、`QbQuiz.shuffleQuestions` 为 false。
  测试：`ShopifyStorefrontIT.theStaffMayKeepTheQuestionsInTheOrderWritten`、`quizbuks-web/shopify` 的 `CampaignsPage.test.tsx`。
- **问卷的快照不写 `questionOrder`**，问卷的 `QbQuiz.shuffleQuestions` 不起作用。
- **重用的发布设置**（`publishing/settings.ts`）不带按难度抽题（照旧带 `pickCount`），因为各档题数取决于当时的题库（总部 2026-11-09 确认）：
  导入设置的对话框提示"设置中不保存按难度抽题，请在草稿中按所选版本的题目重新设定各档难度的题数"（三语，`publication-import-difficulty`，`publishing.test.tsx`）。
- **测试中的 Quiz**：共用的辅助（`ContentItSupport.newQuiz`、`PublicationItSupport`、`AiItSupport`）与各场景的 `QB_QUIZ_SAVE` 显式 `shuffleQuestions: false`；
  只有新场景 `qb/play-order.yml` 与专门的测试打开乱序。
