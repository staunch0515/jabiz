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
