# F-S6 题库：千题、分页、搜索、分批导入（实施计划）

规格：`first-launch-spec.md` 的 F-S6（与 F-S7 一起交付）。负责：S1（总控 2026-11-08 由 S2 改派）。
分三个 PR：**F-S6a1 上限与容量**（§1、§2、每次答题的硬上限）、**F-S6a2 编辑与导入的后端**（§3.1–§3.4）、**F-S6b 编辑器**（§3.5）；F-S7 另见 `F-S7.md`。删除的功能：无（排序由"提交全部题号"改为"移动一题"，旧接口保留到前端不再使用后照旧可用，见 §3.3）。

## 0. 现状（2026-11-08 调查）

- 题数上限只有一个常数 `ContentLimits.MAX_QUESTIONS = 100`，它决定：
  - 数据视图批量：`ContentConfig` 的 QUESTION / OPTION / TRANSLATION；
  - `@Size`：`ReorderInput`、翻译、答案；
  - 导入与 AI 接受的检查；
  - `pickCount` 的范围；
  - 答题列宽：`PlayLimits.ANSWERS_LENGTH` 1504、`RESULT_LENGTH` 12004、`QUESTION_SET_LENGTH` 502，但列是 `varchar(500)`，宽度不一致；
  - 统计数据视图的批量（`AnalyticsConfig`）；
  - 前端 `LIMITS.questions`。
- 版本文件的登记一次写入，上限 `RELEASE_CHUNK` 3000；1000 题 × 7 个文件 × 3 种语言约 2.2 万，超出。
- 题目的保存、删除、排序与 AI 接受，每次都读全部题目。编辑器在前端读全部题目、选项与翻译，没有分页与搜索。
- 导入（Q16）只建新的 Quiz，一个事务，不能导入到已有的 Quiz，没有批次。AI 接受的题记了 `aiJobId`。
- 开始、继续、提交、结果每次都解析快照（`PlaySteps.snapshot`），没有缓存。
- 题目没有主题词，主题词在 Quiz 上（F-S9）。

## 1. 两种上限（F-S6a）

| 名称 | 种类 | 缺省 | 用处 |
|---|---|---|---|
| `qb.content.max-questions` | 普通参数（不受控） | 1000 | 题库题数：加题、导入、AI 接受、复制 |
| `qb.play.max-questions-per-attempt` | 普通参数（不受控） | 100 | 每次答题的题数：题库超过它时发布必须抽题（`PICK_REQUIRED`），抽题数 k ≤ 它 |
| `qb.content.import.batch-size` | 普通参数（不受控） | 200 | 每批导入的题数上限 |
| `ContentLimits.MAX_QUESTIONS_TECH` | 常数 | 2000 | 技术上限：`@Size`、列宽、数据视图批量；参数不能超过它（启动检查与 `QB_SETUP`） |
| `PlayLimits.MAX_PER_ATTEMPT_TECH` | 常数 | 200 | 答题列宽：`answers`、`result`、`question_set`、`option_order` |

- **实施（F-S6a1）**：
  - 常数改名为 `ContentLimits.MAX_QUESTIONS_TECH`，改名迫使每个用处都重新判断。
  - 业务判断经 `content.QuizBankLimits`：以 `LoadParams.optional` 读这三个参数；还没有建立的参数（部署后尚未重跑 `QB_SETUP`）按声明的缺省值；不超过技术上限（取较小者，不另设启动检查）。
  - 加题（`QB_QUESTION_SAVE`）、AI 接受（`QB_AI_ACCEPT`）、导入新 Quiz（`QB_QUIZ_IMPORT_CHECK` / `QB_QUIZ_IMPORT`，`QuizFileChecks.check(file, 上限)`）按 `qb.content.max-questions`。
  - **每次答题**：F-S6a1 只按技术上限 200 判断：
    - 版本超过它而不抽题：`PICK_REQUIRED`；
    - 抽题数超过它：`PICK_TOO_MANY`；
    - 切换到超过它的版本而不抽题：`PICK_REQUIRED`。
    按参数 `qb.play.max-questions-per-attempt` 判断（发布的保存、提交、切换要读参数）随 F-S7 一起做：那几个流程正被 S2 的 F-S2c2 改动，等它合入后再动。
- 代码中不再以 `MAX_QUESTIONS` 判断业务上限。流程以 `LoadParams` 读参数，模板以 `now:SysParam` 读同一个值，照 F-C11 的商品上限的写法。
- 参数调低后，已有的大题库照常读取、修改、删除，只是不能再加题（`TOO_MANY_QUESTIONS` 带上限）。
- 列宽：迁移 V(N)：
  - `qb_attempt.option_order` / `answers` 加宽到按 200 题计算的长度；
  - `result` 加宽，或改为 `text`（只追加表只能加宽，D58）；
  - `question_set` 改为 `varchar(1002)`，即 2 + 200 × 5。
  `PlayLimits` 的常数按 `MAX_PER_ATTEMPT_TECH` 计算，与列一致，由测试 `PlayLimitsTest` 检查。
- `pickCount` 的范围改为 1..`MAX_PER_ATTEMPT_TECH`，具体上限由参数判断（`PICK_RANGE` 带上限）。`qb_question_version.seq` 与 `points` 是 `numeric(4,0)`，够用。
- `QB_SETUP` 建这三个参数（照 `qb.products.max`），平台管理系统的参数页可以调。
- `operations.md` 增加"题库上限"一节。

## 2. 版本与快照（F-S6a）

- **版本文件的登记**：数据视图的写入上限是按提交算的，在同一个事务中分段写入不起作用。所以把版本文件数据视图的上限改为 `MAX_FILES_PER_VERSION`（按技术上限重算：一个版本最多用到的文件数），版本照旧在它的一次提交中登记全部文件；删除时照旧按 `RELEASE_CHUNK` 分段释放。实际的题库多半只有少量图片。
- **快照大小**：1000 题 × 6 个选项 × 三语约 1–2 MB，在 `MAX_SNAPSHOT_BYTES`（6 MB）之内。新加测试 `LargeBankIT`：
  - 生成千题题库并保存版本；
  - 计时开始与提交，结果写进测试报告 `docs/quizbuks/perf/F-S6-large-bank.md`。
- **快照缓存**：先实测。开始或提交的耗时比百题时明显变长时（判断标准：解析超过 20 ms），在 `PlaySteps` 加一个按版本编号缓存解析结果的缓存。版本不可变，缓存是进程内、有上限的 LRU（`qb.play.snapshot-cache` 个数，缺省 32），不跨实例、不改语句数。`AttemptStatementBudgetIT` 的数目不变。
- **每日核对**：`QB_RECONCILE_RUN` 增加一项 `SNAPSHOT_NEAR_LIMIT`，报告快照大于上限 80% 的版本；告警沿用每日核对的告警。

## 3. 题目的编辑（后端 F-S6a，页面 F-S6b）

### 3.1 按页读取

- 新模板 `qb.sponsor.quiz-questions`：
  - 参数：`quizId`、`q`（题干文字，`strpos` 不分大小写）、`no`（编号）、`page` / `size`（≤ 100）；
  - 返回：题目、选项个数、分数、翻译完成度，以及总数（答案见模板的 `total`）。
- 编辑器只读当前页；题目的详情（选项与翻译）在打开一题时读（已有的数据视图查询加上 `questionId` 条件）。
- 翻译页同样按页读，用新模板 `qb.sponsor.quiz-translations` 加 `page`。
- 题目统计已经是按发布和版本读的（`analytics-questions.sql`），加上分页参数。

### 3.2 搜索

- 按题干与编号搜索：见 §3.1。
- **按主题词**（需求方 2026-11-08 确认"按建议"）：题目没有主题词，主题词是 Quiz 的。
  - 编辑器的题目搜索：题干与编号；
  - Quiz 列表（发起方的 Quiz 页、首页的 `q`）：加上 Quiz 的主题词（词表名称的三语）与自定义词。这也接上了 F-S9 留下的"自定义词的搜索等 F-S6"。

### 3.3 移动一题与批量操作

- `QB_QUESTION_SAVE` / `_DELETE` 照旧读本 Quiz 的全部题目：一条语句，语句数不随题数增长；千题时多读的行在实测中可以接受（`LargeBankIT`），所以不改写这两个流程，减少风险。
- **移动一题** `QB_QUESTION_MOVE`：
  - 输入：`quizId`、`questionId`、`beforeQuestionId?`（null 表示移到末尾）、`baseRevision`；
  - 用 `Reorder.next` 在两邻居之间取 `seq`；没有空隙时重新编号该 Quiz 的全部题目，这是唯一一次全量写，按 `seq` 批量。
- `QB_QUESTION_REORDER` 保留（行为不变，`@Size` 改为技术上限）。编辑器改用移动之后，它仍可用，例如导入工具与测试。
- **批量删除** `QB_QUESTION_DELETE_MANY`（`questionIds` ≤ 200、`baseRevision`）。
- **批量改分** `QB_QUESTION_POINTS_SET`（`questionIds` ≤ 200、`points`）。
- 两者都在一个事务中，题目的翻译与选项随之删除。

### 3.4 分批导入与撤销

- **导入到已有的 Quiz** `QB_QUIZ_IMPORT_QUESTIONS`：
  - 输入：`quizId`、`document`（同 Q16 的文件格式，只取题目部分）、`files`；
  - 每批 ≤ `qb.content.import.batch-size`；
  - 累计后的题数 ≤ `qb.content.max-questions`，否则整批拒绝并给出上限；
  - 每批一个事务：一批失败不影响已导入的批次；报告列出出错的行（沿用 `QuizFileChecks` 的问题格式）；
  - `QB_QUIZ_IMPORT_CHECK` 加上 `quizId` 时做同样的检查。
- **批次记录**：新的普通表 `QbQuestionImport`（`importId`、`quizId`、`sponsorId`、`count`、`at`、`undoneAt`），`QbQuestion` 加不可变的 `importId`（迁移）。
- **撤销** `QB_QUESTION_IMPORT_UNDO`：删除本批新增、此后没有改过的题；改过的保留并列出。之后 `undoneAt` 写上，不能再撤。
- AI 接受同样已有 `aiJobId`。"撤销本次 AI 出题"不在规格内，不做。
- AI 出题：`QB_AI_QUESTIONS_REQUEST` 的个数照旧（`AiQuestionsJob.MAX_COUNT`），`QB_AI_ACCEPT` 的累计检查改用参数。规格中的 `qb.ai.questions.batch-size` 即沿用的现有限额，不新建参数。
- 新建 Quiz 的导入（`QB_QUIZ_IMPORT`）题数也按参数，超过时按批：先建 Quiz 与第一批，其余批次由前端接着以 `QB_QUIZ_IMPORT_QUESTIONS` 导入。

### 3.4a F-S6a2 的实现与计划的差别（2026-11-08）

- **撤销判断"没有改过"**：每道导入的题记下本批加入时的内容哈希（`importHash`：题干、图片、分数、单选多选与各选项的文字、图片、对错，`ImportHash`），撤销时与现在的内容比较，相同才删除。
  移动与翻译不算修改。不按版本号判断：移动一题、重新编号都会写新版本。
- **导入到已有的 Quiz 只收题目**：文件中有资料（或翻译中有资料）即拒绝（`QB_IMPORT_QUESTIONS_ONLY`）；题目的种类（测验 / 问卷）与 Quiz 不同即 `QB_QUIZ_KIND_FIXED`；文件的基础语言与 Quiz 不同即 `QB_IMPORT_LANGUAGE`。
  翻译只导入 Quiz 已有的语言，其余语言在答复的 `skippedLanguages` 中列出。
- **`QB_QUIZ_IMPORT`（新建 Quiz）照旧一次可以导入到题库上限**，不改为必须分批，以免减少已有的功能；前端可以先以它建 Quiz 与第一批，再以 `QB_QUIZ_IMPORT_QUESTIONS` 接着导入。
- **`QB_QUIZ_IMPORT_CHECK` 不加 `quizId`**：累计题数由每批导入自己检查（超过即整批拒绝并给出上限），前端对每批照旧先调用检查。
- **翻译页的分页模板**（`qb.sponsor.quiz-translations`）随 F-S6b 的翻译页一起做。
- **首页的主题词搜索**（`feed.sql`）等 S2 的 c2a 与 F-S2d 合入后再做；发起方的 Quiz 列表（`qb.sponsor.quizzes`）本 PR 已按主题词名称（三语）与自定义词搜索。

### 3.5 编辑器（F-S6b）

- 题目列表：
  - 分页（每页 50）、搜索框（题干、编号）、显示总数与上限；
  - 每行一个"移动到…"（输入目标位置，或上移、下移），用 `QB_QUESTION_MOVE`；
  - 多选后批量删除（确认对话框）与批量改分。
- 导入：
  - 在 Quiz 编辑器中"导入题目"，分批进度，出错的行列表；
  - 已导入的批次可以撤销（列出批次、时间、题数）。
- 翻译页按页。
- `LIMITS.questions` 不再写死，改读模板 `qb.sponsor.quiz-limits`（题库上限、每次答题上限、每批导入题数）。
- 帮助"大题库与抽题"：`docs/quizbuks/help/` 与发起方页面的帮助链接。
- 测试：
  - Vitest：分页、搜索、移动、批量、导入进度与撤销；
  - e2e `content-bank.spec.ts`：导入两批，撤销一批，搜索，移动一题，批量改分；
  - axe 亮暗两种外观。

### 3.5a F-S6b 的实现与计划的差别（2026-11-08）

- **草稿照旧整份读取**：编辑器的其他部分（翻译页、商品、AI 面板、摘要行的满分与单选多选计数）仍用 `readDraft`；题目列表本身只读当前页（模板），打开或编辑一题时，草稿中没有这一题就单独读它与它的选项（`readQuestion`）。去掉整份读取留给以后（风险大，涉及翻译页）。
- **翻译页按页没有做**：需要新模板 `qb.sponsor.quiz-translations`（后端），本 PR 不改后端；翻译页照旧整份读取。→ 已在 **F-S6c** 做完，见 §3.5b。**首次上线必做（总控 2026-11-09）**。
- **"移到…"**：题数不超过 100 时照旧是列出全部位置的下拉，超过时改为输入位置的对话框；两者与上移、下移都用 `QB_QUESTION_MOVE`（目标位置上的题不在当前页时以模板的 `no` 查出它）。
- **导入题目**：先以 `QB_QUIZ_IMPORT_CHECK` 检查整个文件（一次列出全部问题），再按 `importBatch` 分批，每批是只含这些题的 Quiz 文件（题号与译文的键 `q<N>.…` 从 1 重新编号，没有资料、商品与封面，只带这批用到的文件），每批照旧先检查、上传文件、再导入；文件中的资料与商品不导入，页面在开始前说明。一批失败即停，说明第几批与原因，之前的批次保留。页面在最后一批之后才重新读取 Quiz。
- **帮助**：`docs/quizbuks/help/large-banks.md`（三语底稿）；题目页的说明链接到帮助中心 `/help`。

### 3.5b F-S6c 翻译页按页（2026-11-09，首次上线必做（总控 2026-11-09））

- **模板** `qb.sponsor.quiz-translations`（`qb.content.write`，商家数据视图）：参数 `quizId`、`language`（必填）、`q`、`untranslated`；`list:` 按题号分页（页面每页 50 题，`offset` / `limit`）。
  每行一题：题号（Quiz 的顺序，在搜索与筛选之前编号）、题干与图片、该语言的题干译文、状态与作者、该语言自己的图片，选项（JSON 列表：编号、文字、图片、译文、状态、作者、自己的图片），以及待处理的文字数 `pending`。
  - 状态即 `QuizLanguages` 的规则：基准没有文字为 `none`，没有译文为 `missing`，译文的 `sourceHash` 不等于现在基准文字的哈希为 `stale`，否则 `done`。哈希在 SQL 中照 `ContentLanguages.sourceHash` 计算（NFC、UTF-8、SHA-256 的前 16 个十六进制字符），`QuizTranslationsTemplateIT` 用流程写入的哈希核对（含需要 NFC 的文字）。
  - `q` 搜题干或任一选项的文字（`strpos`，不分大小写）；`untranslated` 只列 `pending > 0` 的题。
- **页面**（`content/TranslationsPanel.tsx`）：
  - 标题与简介、资料、封面照旧取自草稿（数量少）。
  - 语言切换上"还差几项"的数目：文字部分来自新模板 `qb.sponsor.quiz-translation-progress`（每种语言整份 Quiz 的缺少与过时的文字数，状态规则同上，`QuizTranslationsTemplateIT` 核对它等于按页模板的 `pending` 之和加上标题与简介），资料部分照旧按草稿中的语言版本计算。
  - 题目部分按页读模板：上一页 / 下一页、搜索框、"只看需要处理的"（同一个勾选框也筛选标题与简介），本页题目与选项自己的图片列在本页之下。
  - 保存照旧是 `QB_TRANSLATION_SAVE`（同样的 `entries` 与 `baseRevision`），AI 的建议照旧逐条"采用"并确认、经 `QB_AI_TRANSLATION_ACCEPT` 写入；"全部采用"改为分两处：标题与简介一处，本页一处（"采用本页全部 AI 建议"），各自保存。
  - 一页有未保存的修改时，翻页、搜索与筛选都停用并提示先保存（不在页之间保留未保存的输入）。
- **草稿不再读全部译文**：编辑器的草稿（`readDraft`）只按键读 Quiz 自己的译文（`quiz.title`、`quiz.intro`、`quiz.cover`；商家译文数据视图因此可按 `elementKey` 筛选）。千题、六个选项、三种语言约 4.2 万行译文，以前每次打开编辑器约 420 次请求，现在一次。
  需要全部译文的只在用户要求时读取（`readDraft(quizId, 'all')`）：导出在按下"导出"时，预览在打开预览页时（缓存键与编辑器的草稿分开），行为不变。题目与选项仍整份读取（§3.5a）。

### 3.5c F-S6d 打开编辑器的请求数与题数无关（2026-11-09，首次上线必做（总控 2026-11-09））

- **草稿不再读题目与选项**：编辑器的草稿（`readDraft(quizId)`，范围 `editor`）只读 Quiz、资料与资料图片（各有固定上限）和 Quiz 自己的译文（按键，F-S6c）。题目列表照旧按页读 `qb.sponsor.quiz-questions`，展开或编辑一题时单独读它（`readQuestion`）。
- **摘要行**：新模板 `qb.sponsor.quiz-question-summary`（`qb.content.write`）一行给出题数、满分、单选与多选的个数（按正确选项的个数，同 `QuizSnapshot.Question.choice`）、没有正确选项的题数，以及其中第一题的编号与题号（"没有正确选项"的链接跳到它所在的页并展开）。以前的摘要也只跳到第一题，所以不返回更多的题号。
- **每行的标记**：`qb.sponsor.quiz-questions` 增加 `correctCount`（正确选项的个数：单选、多选与"未标正确答案"的标记）与 `optionImages`（有没有选项图片）。以前不在草稿中的题只显示分数，现在每行都显示单选或多选。
- **商品页的题目商品**：题目按页列出（每页 50 题，上一页 / 下一页），各页的选择都保留，一次保存整份（`QB_QUIZ_PRODUCTS_SET` 不变）；超过题数的旧链接照旧列出以便去掉。
- **其他**：保存版本的检查只在服务端（页面只显示服务端的理由），不需要题目；AI 出题面板、素材库与翻译页不用草稿的题目；页签上的题数取 Quiz 行的 `questionCount`。
- **按需读整份**：导出与预览照旧以 `readDraft(quizId, 'all')` 读全部题目、选项与译文，只在按下导出或打开预览时。
- **测试**：`QuizQuestionsTemplateIT`（摘要与标记）；Vitest（打开编辑器时 120 题与 2 题发出同样的请求、没有题目与选项的数据视图查询，摘要来自模板，跳到第一道未标正确答案的题，商品页按页）；e2e `content-bank.spec.ts`：从打开编辑器到题目页显示完毕的 `/api/` 请求，1000 题与 3 题都是 19 次（断言 ≤ 25 且两者相同）。

## 4. 统计

- 每题的被抽人数（Q81 D-6）：`QbQuestionStat` 加列 `drawn`（迁移），由 `analytics-attempts.sql` 从开始了的答题的 `question_set` 计算。没有抽题的发布等于该版本的开始人数，照最小分组公布。
- 发起方的题目统计页显示"被抽到"。这一项与 F-S7 有关，放在 F-S7 的 PR 里，与抽题与乱序一起改统计。

## 5. 迁移（开 PR 时取当时最大号 + 1）

- **F-S6a1**：`qb_attempt` 的列加宽（参数由 `QB_SETUP` 建立，不在迁移中）。
- **F-S6a2**：
  - `qb_question_version.import_id`；
  - `qb_question_import` 表（只经流程写入，普通表）。
- **F-S6b**：无。

## 6. 测试清单（F-S6a1 / F-S6a2）

- F-S6a1：
  - `PlayLimitsTest`：列宽与迁移一致，最大的 `questionSet` 放得下；
  - `PublicationReadinessTest`：`PICK_REQUIRED` / `PICK_TOO_MANY`；
  - `ContentIT`：参数为 3 时第 4 题被拒，调低到 2 后已有的题照常修改与删除；
  - `AiCostControlIT`：AI 接受按参数；
  - `QuizFileTest`：导入的题数按上限；
  - `ContentHealthIT`：每日核对；
  - `LargeBankIT`：千题题库（6 个选项、三语）导入、保存版本、要求抽题、抽 100 题开始与提交，计时；
  - `AttemptStatementBudgetIT` 不变。
- F-S6a2 及以后：

- `ContentLimitsTest` / `PlayLimitsTest`：常数与列宽一致，参数不能超过技术上限。
- `ContentIT`：
  - 参数调低后不能再加题，已有的照常修改与删除；
  - 移动一题（中间、末尾、需要重新编号）；
  - 批量删除与批量改分；
  - 保存与删除的语句数不随题数增长（断言确切值）。
- `QuestionImportIT`：
  - 分批导入；
  - 超过上限的那一批整批拒绝；
  - 出错的行；
  - 撤销本批，改过的题保留；
  - 不能撤销两次。
- `LargeBankIT`：千题题库保存版本（文件登记分批）、翻译、开始与提交计时；`AttemptStatementBudgetIT` 不变。
- `ReconcileIT`：`SNAPSHOT_NEAR_LIMIT`。
- 模板分页与搜索：`QuizQuestionsTemplateIT`。
- Quiz 列表按主题词与自定义词搜索：`FeedIT` 与发起方 Quiz 列表。
- 场景：`qb/content.yml` 加一批导入与撤销，更新快照。

## 7. 风险

- 只追加表（`qb_attempt`）只能加宽列：D58 允许加宽，而 `text` 是否算"改类型"要按封存的核对规则确认，所以优先用加宽的 `varchar`。
- 与 S2 的 F-S2c2 同改 Quiz 与发布的保存：开工前已问 S2，按回复约定先后。
- 场景快照：F-S6a 只在 `content.yml` 增加步骤，不改其他场景。
