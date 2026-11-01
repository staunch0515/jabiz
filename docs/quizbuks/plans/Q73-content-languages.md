# Q73 内容的多语言（2026-11-01，第一稿，待确认）

需求方（2026-11-01）："现在有的重大问题，就是内容的多语言化。比如，问题模版的多语言化，发布内容的多语言化，迷你网站的多语言化。"
讨论后确认（2026-11-01）：采用方案 A（一份内容、几种语言的文字），先写本设计，Q71 放在后面。
需求方（2026-11-01，第一稿之后）："资料文件不能共用，需要分开。有一种语言，其他语言可以缺省。因为有时需要三种语言的视频，文字，否则怎么学习？"：资料按语言各有一份，没有的语言回落到基准的资料（§2.2b，D-Q73-5）。

## 0. 问题与方案

**现状**（Q59a、Q60a）：一个 Quiz、一个版本、一张卡片、一个主页都只有一种语言。商户要三语，就要建三份 Quiz、发三次布：
预算与名额分成三份不能互相挪用，改一题要改三处、审三次，统计分在三处，迷你网站只能选一种语言。

**方案 A**：一份内容带几种语言的文字。

- **结构只有一套**：题目、选项、正确答案、分数、时限、预算、名额、档位都只有一份。
- **题目的文字按语言分开**，每种提供的语言都必须译全。
- **资料按语言各有一份**（视频、PDF、音频、图片、文章都可以不同），没有的语言回落到基准的那份。
- **一个发布可以同时提供几种语言**，共用预算与名额。
- **每位答题者只看到一种语言**（Q59 的"不同语言不显示在一起"照样成立）。

```
Quiz「ラーメン通？」 基准：日本語   翻译：中文 ✓  English ⚠ 2 处需要更新
  资料 1 视频：日本語版、中文版、English 版（各自的视频）
  资料 2 PDF：只有日本語版（中文、English 的答题者也看日本語版，注明语言）
  └ 版本 v3：日本語、中文（English 不完整，不进版本）
      └ 发布：预算 30,000、限 300 人，提供 日本語 + 中文
            田中（日文）看到「ラーメン通？」，小王（中文）看到「你是拉面通吗？」，同一个发布、同一组名额
```

不做翻译的商户什么都不变：Quiz 只有基准语言，用法与现在相同。已有的数据都是只有基准语言的内容，不需要迁移含义。

| 期 | 内容 |
|---|---|
| **Q73a** | 模版多语言：翻译的数据与流程、编辑器的翻译界面、过时标记、版本的语言、商家后台与管理后台的预览 |
| **Q73b** | 发布多语言：发布的文字、提供的语言、卡片与首页、答题时的语言、站内信与邮件中的发布名、分享链接、审核 |
| **Q73d** | AI 翻译（候选，校对后接受，计入 AI 限额） |
| **Q73c** | 迷你网站多语言：主页标语、入驻简介、商品 |
| Q73e | 按语言分开的统计、Shopify 店面（以后） |

（顺序是 a → b → d → c：模版和发布做好以后，商户最大的负担就是翻译。）

## 1. 概念

| 词 | 含义 |
|---|---|
| 基准语言 | Quiz 现有的 `language`。只有它的界面能改结构（加题、删题、正确答案、分数、文件）。意思不一致时以它为准（D-Q73-3） |
| 翻译语言 | Quiz 另外加的语言（平台语言 en / zh / ja 中基准以外的，至多 2 种）。只能填文字 |
| 可译的文字 | 见 §2.1 的表：Quiz 的标题与简介、题干、选项。分数、正确与否、题目与选项的图片所有语言共用 |
| 资料的语言版本 | 一份资料在某种语言下的整份替换（§2.2b）：自己的标题、描述、正文、视频链接、PDF、音频、图片与说明。可以没有，没有即回落到基准的那份 |
| 译文的来源 | 每段译文记下它所译的基准文字的哈希 `sourceHash` |
| 过时 | 基准文字改了，`sourceHash` 对不上，界面上标"需要更新" |
| 完整的语言 | §2.1 中每段非空的基准文字都有译文，且没有一段过时；资料不算在内（可以回落）。基准语言总是完整的 |
| 版本的语言 | 生成版本时完整的那些语言（基准在前），冻结在版本上 |
| 提供的语言 | 发布从版本的语言中勾选的，冻结在提交时 |
| 答题语言 | 一次答题所用的语言，开始时选定，记在会话上 |

## 2. 模版（Q73a）

### 2.1 可译的文字

| 对象 | 文字 | 键（草稿中按编号，见 §2.2） |
|---|---|---|
| Quiz | 标题、简介 | `quiz.title`、`quiz.intro` |
| 题目 | 题干 | `question:<questionId>.stem` |
| 选项 | 文字 | `option:<optionId>.text` |

- **资料不在这张表中**：资料按语言是整份的语言版本（§2.2b），不是逐段翻译。
- **共用的**：封面、题目与选项的图片、分数、正确答案、单选或多选、时限。
- 长度上限与基准相同（`ContentLimits`）。Markdown 与基准一样只在前端渲染。

### 2.2 数据

**`QbQuiz.translationLanguages`**（新列 `translation_languages`，逗号连接的语言代码，空即只有基准）：
- 只经 `QB_QUIZ_LANGUAGES_SET` 加入或去掉。
- 去掉一种语言时，它的译文一起删除。
- 不能加基准语言本身。

**`QbContentTranslation`**（新的普通表 `qb_content_translation`，历史由平台审计记录）：
- 一段译文一行：`quizId`、`ownerId`、`language`、`elementKey`（§2.1 的键，按 `questionId` / `optionId` 等不可变编号，题目改了顺序也对得上）、`text`、`sourceHash`、`translatedBy`（`SPONSOR` / `AI`，`AI` 时另记 `aiJobId`，见 §5）、`updatedAt`。
- 唯一约束：`(quiz_id, language, element_key)`。
- 默认数据视图 `processOnlyWrites`，读权限不授予任何角色。商家只经连接本人模版的模板读。
- 写入只经流程：
  - `QB_TRANSLATION_SAVE`（一次保存一种语言的若干段：给出即覆盖，空文本即删除该段）；
  - `QB_QUIZ_LANGUAGES_SET`；
  - 删除题目、选项时，同一流程删除它们的译文。
  
  这些流程都照现有规则：先取 `qb.quiz:<quizId>` 再读取，带 `baseRevision`，成功即 `revision + 1`、`changedSinceVersion = true`。

**`sourceHash`** = 基准文字（NFC 后）的 SHA-256 前 16 个十六进制字符。一个元素的状态由三样决定：基准文字为空即不需要译文；没有译文即缺少；哈希对不上即过时。
判断写在纯 Java 的 `TranslationStatus` 中，它给出每种语言的完整性与缺少、过时的键。模板 `qb.sponsor.quiz-translations` 返回原文、译文和状态，同样的判断写在 SQL 中，由测试比较两者。

**为什么不把字段改成 `I18nText`**：
- 内容的五张表是时态表，已被完整性封存。迁移只能加列，不能改类型（平台 D58），只能为每个字段另加一列 jsonb，逐表重写读写。
- 一个独立的表还有这些好处：
  - 改译文不碰基准行（基准的审计与历史不变）；
  - 可以按语言整体删除；
  - 过时与否用一条 SQL 就能查出。

### 2.2b 资料的语言版本

- **数据**：语言版本仍是一行 `QbMaterial`（及其 `QbMaterialImage`），多两列：
  - `variant_of`：基准资料的 `materialId`（不可变）；
  - `language`：语言（不可变）。
  
  基准资料这两列为空。这样资料的种类、长度、文件的所有者检查（`QuizEditing.requireOwnFile`）、图片、版本文件的登记与清扫都照现有的写法。
- **规则**：
  - 一份基准资料每种翻译语言至多一个版本（流程在模版锁内检查，`QB_MATERIAL_VARIANT_EXISTS`）；
  - 语言版本与基准**种类相同**（视频对视频、PDF 对 PDF；`QB_MATERIAL_VARIANT_KIND`）；
  - 不占顺序（跟随基准的位置）；
  - "看完视频再答"跟随基准：基准要求看完，各语言的视频同样要求。
  - 语言只能是 Quiz 的翻译语言之一。去掉一种翻译语言时，它的资料版本一起删除。删除基准资料时，它的语言版本一起删除。
- **流程**：照现有的资料流程。
  - `QB_MATERIAL_SAVE` 加可选的 `variantOf` + `language` 即新建或修改语言版本；
  - `QB_MATERIAL_DELETE` 删除即回到回落；
  - 都在模版锁内，`revision + 1`。
  - 上限：基准资料仍至多 `MAX_MATERIALS`（10），每份另可有每种翻译语言一个版本。`MAX_FILES_PER_VERSION` 随之加大。
- **回落**：某语言没有某份资料的版本，即显示基准的那份，页面注明它的语言（如"日本語"），`lang` 属性随之。
  - 资料不影响该语言是否完整（D-Q73-5）。
  - 生成版本的对话框按语言列出"用基准回落的资料"，供商家确认。

### 2.3 版本

- **快照**：`QuizSnapshot` 增加两样：
  - `translations`：`{语言: {键: 文字}}`。快照里的键按**版本的编号**写（`title`、`intro`、`q3.stem`、`q3.o2.text`），与答案引用题目的方式一致；
  - `materialVariants`：`{语言: [资料]}`，每项是一份完整的 `Material`，`no` 为它所替换的基准资料的编号。
  
  语言版本的文件同样登记 `QbVersionFile`。
  - 只放**完整的语言**的全部译文与资料版本。不完整的语言不进版本，生成版本的输出给出警告"English 不完整，未包含"，版本照常生成。
  - **哈希不变的承诺**：没有翻译的快照不写 `translations` 与 `materialVariants` 键，`SCHEMA` 照 Q12 的做法只在有翻译时写 `2`。已有版本与单语的新版本，其文本与哈希和现在相同。
- **`QbQuizVersion.languages`**（新列，逗号连接，基准在前）：旧行为空，意思是只有 `language`。
- **只改了译文也算有变化**，可以生成新版本（同 Q59a"只改了语言也算有变化"）。
- **复制 Quiz**：译文、资料版本与 `translationLanguages` 一起复制，`sourceHash` 照抄。
- **内容包的导出与导入**（`content.transfer`，Q36 上线内容用它）：格式加 `translations` 与资料版本（格式版本加一，旧格式照常导入）。上线的内容因此可以从第一天起就是多语的。
- **Shopify 的 Quiz** 照旧只有基准。

### 2.4 基准语言的更改

- 有翻译时**不能改基准语言**（`QB_QUIZ_BASE_HAS_TRANSLATIONS`：先去掉翻译语言）。
- "把中文译文升为基准"不在 Q73 中，以后有需要再做"交换基准"（D-Q73-6）。

### 2.5 读取

快照的读法只经 `QuizSnapshots.in(快照, 语言)`：
- 返回把文字换成该语言的同一个快照：结构、编号、题目的文件不变；资料按该语言的版本，没有即基准的那份。
- 每份资料带 `language`（它实际的语言），页面据此注明回落。
- 给出的语言不在快照中即抛异常（调用处在此之前已核对）。

答题、结果、预览、AI 出题的输入都经它。开始答题与继续的输出只含所选语言的文字，从不含正误。

### 2.6 页面（商家后台）

- **编辑器顶部的语言标签**：`[日本語（基准）] [中文 ✓] [English ⚠ 2]`，再加一个"＋ 添加语言"。
  - 基准的标签与现在完全相同。
  - **翻译的标签**分两块：
    - **资料**：每份基准资料一行，显示"此语言的版本"或"使用日本語版"。可以新建此语言的版本：编辑器与基准的资料编辑器相同，可以上传自己的视频链接、PDF、音频、图片，写自己的文章；也可以删除版本，回到回落。
    - **题目**：左右对照，左边是基准原文（只读，以 Markdown 显示），右边是译文框；过时的和缺少的标出来，可以筛选"只看需要更新的"。
  - 题目的结构控件（加题、删题、正确答案、分数、题目的图片）在翻译标签中不出现。
- 保存照现有的方式：防抖、带 `revision`、`QB_QUIZ_CHANGED` 即提示重新加载。
- **生成版本的对话框**列出每种语言的状态，不完整的注明"不会包含在此版本中"。
- **版本预览**有语言切换。管理后台扩展的版本预览同样有。

### 2.7 AI 出题（Q8）

只按基准语言出题、写进基准，与现在相同。出题的输入只发基准的文字。

## 3. 发布（Q73b）

### 3.1 发布的文字

可译的发布文字有：发布名称 `name`、兑换说明 `redeemDescription`、电子奖品说明 `digitalDescription`、推荐说明（`ReferralPolicy.description`）。档位没有文字，不变。

- **`QbPublication.translations`**（新列，JSON `{语言: {字段: {text, sourceHash}}}`，同模版的过时规则，`PublicationTranslations` 读写与核对）：
  - 随 `QB_PUBLICATION_SAVE` 整体保存。给出即完整，不给（null）即不动。
  - 提交即冻结，复制时一起复制。
- **`QbPublication.languages`**（新列，提供的语言，逗号连接，第一个是首选）：
  - 空即只有版本的基准，旧的发布都是这样。
  - 只能从所用版本的语言中勾选，至少一种。
- **提交检查**（`PublicationReadiness`，一次报告全部问题）：
  - `QB_PUBLICATION_LANGUAGE_NOT_IN_VERSION`：勾选的语言不在版本中；
  - `QB_PUBLICATION_TEXT_MISSING`：某语言缺少发布名称等必需的文字，或已过时（字段为 `translations.<语言>.<字段>`）。
- **基准的发布文字**仍在原来的列中：发布的基准语言 = 版本的基准语言。

### 3.2 卡片与首页

- **`QbPublicationCard.languages`**（新列）与 **`translations`**（新列，发布文字各语言的冻结副本）：上架时写入。`language` 保留为首选语言，旧的读法照常。
- **`qb_publication_card_language`**（新的普通表，`(publication_id, language)` 主键，`(language, publication_id)` 索引）：
  - 上架时与卡片一起插入，之后不变（提供的语言提交即冻结；版本切换见 §3.4）。
  - 它只供首页与公开模板按语言筛选时用索引。
- **首页 `qb.app.feed`**：`languages` 参数改为"按优先顺序"。
  - 只要卡片提供的语言与参数**有交集**就列出（经上表 `EXISTS`）。
  - **显示语言**是参数中最靠前、卡片也提供的那一个。名称、说明按它取，并返回 `displayLanguage`。
  - 一张卡片只出现一次。
- **详情 `qb.app.publication`**：返回 `languages`（全部提供的语言）与按读者的语言选出的文字。
  - 由链接打开（分享、邀请、二维码）照旧不受开关限制：`?lang=` 在提供的语言中时按它，否则按首选。
- 公开卡片、收件箱中商家通知的语言筛选（Q59b）改为同样的"有交集"。

### 3.3 答题语言

- **`QbAttempt.language`**（新列；旧行为空，读作卡片的 `language`）。
- **`QB_ATTEMPT_START`** 的输入加可选的 `language`：
  - 不给，按 App 给的优先顺序（同首页）取第一个提供的；
  - 给了却不在卡片的语言中，422 `QB_ATTEMPT_LANGUAGE`。
  - 判断用开始时已在锁外读到的卡片（卡片带 `languages`），**语句数不变**（`AttemptStatementBudgetIT` 不改预算，只加断言）。
- **答题中不能换语言**：继续、保存、提交、结果都按会话的语言。结果页、答题记录、站内信中的发布名也按它。
- **App 的开始页**：卡片提供不止一种语言时，在"开始"上方显示语言选择（缺省为显示语言），提示"开始后不能更换"。
- 选项的打乱、计分、档位、奖励与语言无关（按编号）。

### 3.4 版本切换（Q51）

- 换上的版本必须包含发布提供的**全部**语言，否则 422 `QB_PUBLICATION_VERSION_LANGUAGES`。
- 提供的语言在切换中不变。
- 进行中的会话保持自己的版本与语言。

### 3.5 站内信、邮件与分享

- **站内信**：标题、正文创建时已三语渲染（Q7-15）。其中商家写的发布名按每种渲染语言取：
  1. 该语言有就用它；
  2. 否则用首选语言；
  3. 再否则用基准。
- **邮件**中的发布名，按收件人的邮件语言照此回落。
- **邀请邮件**（Q25）：语言与链接的 `lang` 取发布的首选语言。按收件人选语言不在 Q73 中。
- **分享链接与二维码**（Q59b）：商家后台的分享面板多一个语言选择（提供的语言），`shareLink(发布, 语言)`。海报的文字与二维码随之。

### 3.6 审核

- **审批绑定的内容**（`PublicationApprovals`）加入 `languages` 与 `translations`。版本的内容哈希已覆盖全部译文。
- **管理后台与运营商后台的审核页**：
  - 按语言分页显示版本与发布文字；
  - 每种提供的语言都要勾选"已看过此语言"，才给出决定面板（D-Q73-4）。这只是页面的门槛，决定仍由平台的 `ApprovalPanel` 记录。
  - 审核人不懂某种语言时，可以用 Q73d 的"对照检查"作为辅助（见 §5.3）。
- **免审核**（Q28）与"下架之后一律人工审核"的规则不变。

## 4. 迷你网站（Q73c）

- **`QbSponsorPage`**：
  - `languages`（新列，提供的语言，第一个是首选；空即只有 `language`）；
  - `translations`（新列，`{语言: {headline}}`，过时规则同上）。
- **入驻简介**（Q65b 起主页显示入驻的简介）：入驻行加 `introTranslations`。它属于入驻审批的内容，改动的规则与现在改简介相同（随入驻资料一起审批）。
- **`QbSponsorProduct`**：`translations`（新列，`{语言: {name, description}}`）。主页提供的语言中，缺少译文的商品按首选语言显示，并注明语言。商品不是必需翻译的。
- **公开页 `/s/<handle>`**：`?lang=` 在提供的语言中即按它，否则按访客的界面语言，再否则按首选。页面右上角有语言切换。
  公开模板 `qb.public.sponsor-page` 返回所选语言的文字与 `languages`。
- **主页的审核**（Q60a）：审批内容加入各语言的文字。已发布的主页改动照旧直接生效。

## 5. AI 翻译（Q73d）

### 5.1 作业

- **外部作业 `qb.ai.translate`**（只认接口 `ContentTranslator`，实现为 Claude，同 Q8 的 `QuestionWriter`）。
  - 输入：一个对象（模版、发布或主页）的基准文字与目标语言，按 §2.1 的键。
  - **不发送正确答案、分数与文件**：译者只需要文字，选项的正误一律不发。
  - 输出：每个键一段候选译文。
- 作业声明 `enabledBy(qb.ai.enabled)`。
- 同意规则同 Q8：须同意 `AI_NOTICE`，AI 说明的正式文本要写明译文的用途。
- 限额计入现有的 AI 限额（`AiLimits`：同时进行数、每日次数、每月 Token），用量记入 `QbAiUsage`。

### 5.2 候选从不自动入库

- 候选在 `QbAiJob.candidates` 中保存 7 天，过期即移除（同 Q8）。
- 编辑器的翻译标签中，候选显示在译文框下方，可以"采用"或"改后采用"。只有 `QB_TRANSLATION_SAVE` 把校对后的文字写进 `QbContentTranslation`，记 `translatedBy = AI` 与 `aiJobId`。
- 可以"一键采用全部"，但每段都要商家确认过（每段 `reviewed = true`，缺即 400，同 `QB_AI_ACCEPT`）。

### 5.3 对照检查（审核的辅助）

- **作业 `qb.ai.translation-check`**：给出基准与译文，回答每段"意思是否一致、有什么出入"。
- 审核人在审核页按需运行，结果只作参考、不影响决定。
- 计入平台（总部或运营商）的 AI 用量，不计商家的。

## 6. 决定（草案）

| 编号 | 决定 |
|---|---|
| D-Q73-1 | 方案 A：一份内容带几种语言的文字，结构只有一套；一个发布可以提供几种语言，共用预算与名额。这取代 Q59a"一个 Quiz 一种语言"的约定（Quiz 的 `language` 改称基准语言），Q59 的"不同语言不显示在一起"与语言开关照旧 |
| D-Q73-2 | 翻译不能改题目的结构：翻译语言的题目只有文字，正确答案、分数、题目的图片、题目的个数与顺序都跟着基准 |
| D-Q73-3 | 以基准语言为准：意思不一致时以基准为准，在发布详情与条款中注明（文案待需求方确认） |
| D-Q73-4 | 审核时每种提供的语言都要人看过（页面门槛）；AI 对照检查只作参考 |
| D-Q73-5 | 资料按语言分开（需求方 2026-11-01）：每份资料每种语言可以有自己的整份版本（视频、PDF、音频、图片、文章），种类与基准相同；没有的语言回落到基准的那份并注明语言；资料不影响语言是否完整。题目与选项的图片共用 |
| D-Q73-6 | 有翻译时不能改基准语言；交换基准以后再做 |
| D-Q73-7 | 版本只包含完整的语言；不完整的不进版本、只给警告，版本照常生成 |
| D-Q73-8 | 答题语言开始时选定，答题中不能换；计分与奖励与语言无关 |
| D-Q73-9 | AI 翻译只给候选，商家逐段校对后才写入；不发送正确答案；计入现有的 AI 限额 |
| D-Q73-10 | 不翻译的商户什么都不变；单语快照的文本与哈希不变 |

## 7. 数据（迁移 V54 起）

| 表 | 改动 |
|---|---|
| `qb_quiz_version`（`QbQuiz` 的时态表） | 加 `translation_languages` |
| `qb_content_translation`（新，普通表） | §2.2；唯一 `(quiz_id, language, element_key)`，索引 `(quiz_id, language)` |
| `qb_material_version`（`QbMaterial` 的时态表） | 加 `variant_of`、`language`（§2.2b）与 `variant_of` 的索引；"每种语言至多一个"由流程在模版锁内检查（时态表每个版本一行，不能用唯一约束） |
| `qb_quiz_version_version`（`QbQuizVersion`，只写一次） | 加 `languages` |
| `qb_publication_version`（只追加） | 加 `languages`、`translations` |
| `qb_publication_card`（普通表） | 加 `languages`、`translations` |
| `qb_publication_card_language`（新，普通表） | §3.2 |
| `qb_attempt`（普通表） | 加 `language` |
| `qb_sponsor_page`、`qb_sponsor_product`、入驻 `qb_sponsor_version`（`QbSponsor`） | 加 `languages`、`translations`、`intro_translations`（Q73c） |

只追加表只加列，照 D58 以 `SealedColumnsHistory` 检查。新表的个人信息：译文是商家的内容，不是个人信息；账号删除（Q72）照"内容保留"处理，`ownerId` 同内容表。

## 8. 流程、模板（新增与改动）

| 名字 | 说明 |
|---|---|
| `QB_QUIZ_LANGUAGES_SET` | 加入、去掉翻译语言（去掉即删除其译文） |
| `QB_TRANSLATION_SAVE` | 保存一种语言的若干段译文（模版锁内，带 `baseRevision`） |
| `QB_QUIZ_VERSION_CREATE`（改） | 快照带完整语言的译文，输出给出不完整的语言 |
| `QB_PUBLICATION_SAVE`（改） | `languages`、`translations` |
| `QB_ATTEMPT_START`（改） | 可选的 `language` |
| `qb.sponsor.quiz-translations` | 编辑器翻译标签：原文、译文、状态 |
| `qb.app.feed`、`qb.app.publication`、`qb.public.*`（改） | 有交集的筛选、按读者的语言取文字 |
| `qb.ai.translate`、`qb.ai.translation-check`（Q73d） | §5 |

## 9. 测试清单

- **`TranslationStatusTest`**（core 风格的纯 Java）：缺少、过时、空基准不需要译文、完整性；与模板 `qb.sponsor.quiz-translations` 比较（`ContentTranslationIT`）。
- **`QuizSnapshotsTest`**：
  - 单语快照的文本与哈希与改动前**逐字相同**（固定的旧快照样例）；
  - 带翻译的快照 `SCHEMA = 2`；
  - `in(快照, 语言)` 只换文字、不动结构。
- **`ContentTranslationIT`**：
  - 保存、覆盖、删除；
  - 删除题目、选项连带删除译文；
  - 改基准即过时；
  - 版本只含完整语言；
  - 复制带译文；
  - 有翻译时不能改基准语言；
  - 不是本人的模版 404；
  - 封禁即拒绝；
  - `revision` 冲突。
- **`MaterialVariantIT`**：
  - 新建、修改、删除语言版本；
  - 种类须与基准相同；
  - 每种语言至多一个；
  - 语言须是翻译语言；
  - 删除基准或去掉语言连带删除；
  - 文件须为本人上传；
  - 版本登记语言版本的文件；
  - `in(快照, 语言)` 按版本或回落，并带实际语言；
  - 内容包导出再导入后相同。
- **`PublicationLanguagesIT`**：
  - 提交检查的全部问题；
  - 上架写卡片与语言表；
  - 首页有交集即列出、显示语言按顺序；
  - 链接的 `lang`；
  - 版本切换须覆盖全部语言；
  - 审批内容含译文。
- **`AttemptLanguageIT`**：
  - 开始按顺序取语言；
  - 不提供的语言 422；
  - 继续与结果按会话的语言；
  - 输出不含正误（`AnswerSecrecyIT` 加中文的用例）；
  - **`AttemptStatementBudgetIT` 的确切值不变**。
- **`InboxIT`**、邮件：发布名按渲染语言回落。
- **`AdminReviewIT` / 扩展测试**：每种语言勾选后才给出决定面板。
- **场景**：
  - 一个三语 Quiz 的建立、翻译、版本、发布与两种语言的答题者各答一次（`content-languages.yml`）；
  - 已有场景的快照不变（单语不变的回归）。
- **前端**：
  - 翻译标签的左右对照、过时筛选；
  - 版本对话框的警告；
  - 开始页的语言选择；
  - 分享的语言选择；
  - 亮暗两种外观都过 axe。
- **e2e**：商家加中文翻译 → 生成版本 → 发布两种语言 → 中文答题者在首页看到中文并完成。
- **Q73d**：
  - AI 的输入不含 `correct`（`AiTranslateInputTest`）；
  - 候选须逐段 `reviewed`；
  - 限额与用量；
  - 本地桩。

## 10. 风险

- **首页性能**：从"一列等于"改成"语言表 `EXISTS`"，要在 `qb.app.feed` 的现有性能测试中比较；必要时把首选语言仍按 `(language, status)` 索引走、再并上其他语言。
- **审核负担**：三种语言的内容审核人未必都能读。页面门槛只能保证"看过"，不能保证"看懂"；对照检查是辅助，需求方要决定运营商审核其他语言时的做法。
- **译文质量与责任**：以基准为准（D-Q73-3）需要在条款中写明，正式文本由需求方或律师给出。
- **编辑器的复杂度**：翻译标签是新的一大块页面，题目多（至多 100 题 × 6 个选项）时要虚拟化或分页。
- **与 Q72（商户删除）、Q71 的关系**：两者在 Q73 之后做，按多语的模型处理（译文随内容保留或删除）。

## 11. 需求方的确认（待）

1. D-Q73-1：一个发布同时提供几种语言、共用预算与名额（改变 Q59a 的约定）。
2. D-Q73-3：以基准语言为准，并在页面与条款中注明。
3. D-Q73-4：审核时每种语言都要人看过；运营商审核看不懂的语言时怎么办（转给总部，或用 AI 对照检查作参考）。
4. D-Q73-9：AI 翻译计入商家现有的 AI 限额。
5. ~~D-Q73-5：资料文件先共用。~~ 已确认（2026-11-01）：资料按语言分开，没有的语言回落到基准（§2.2b）。
6. 题目与选项的图片是否也要按语言分开（图中有文字时），还是先共用。
7. 题目的文字是否必须每种提供的语言都译全（本稿：是），还是也可以回落到基准。

## 12. 实施记录

### Q73a-1、Q73a-2（2026-11-01）：模版多语言（后端与商家后台）

**数据**（迁移 V54）：
- `qb_quiz_version` 加 `translation_languages`；
- `qb_material_version` 加 `variant_of`、`language`；
- `qb_quiz_version_version` 加 `languages`；
- 新的时态表 `qb_content_translation_version`（实体 `QbContentTranslation`，与其他内容表一样只追加；"每个（模版、语言、元素）一行"由流程在模版锁内保证）。

**规则与流程**：
- 纯 Java 的规则：`content.ContentLanguages`（键、`sourceHash`、语言列表）、`content.QuizLanguages`（草稿在各语言下缺什么、版本含哪些语言）。
- 新流程 `QB_QUIZ_LANGUAGES_SET`、`QB_TRANSLATION_SAVE`。
- 资料的语言版本走现有的 `QB_MATERIAL_SAVE` / `_DELETE`（加 `variantOf`、`language`）。
- 删除选项、题目、基准资料、语言或整个模版时，连带删除对应的译文与资料版本。
- 复制草稿时，原样保留各语言（包括不完整的、过时的）；复制版本时保留版本中的语言。
- AI 出题只读基准资料。

**快照**：
- 有翻译时 `schema = 2`，加 `translations`：`{语言: {texts, images, materials}}`，键按版本的编号（`q3.stem`、`q3.o2.text`、`q3.image`）。
- 没有翻译的快照文本与哈希不变（`QuizSnapshotLanguagesTest`）。
- `QuizSnapshots.in(快照, 语言)` 按语言读出，结构、编号、正误不变。
- 一个版本的文件上限与快照大小随语言数放大：`MAX_FILES_PER_VERSION` 2701，释放块 `RELEASE_CHUNK` 3000，快照 6 MiB。

**版本**：
- `QB_QUIZ_PUBLISH_VERSION` 只收完整的语言。
- 输出多 `languages` 与 `incomplete`，后者列出未完整的语言及其缺少、需更新的键与不完整的资料编号。
- "未改变"的判断同时比较 `languages`。

**商家后台**：
- 编辑器的"语言"标签页（`content/TranslationsPanel.tsx`）：
  - 勾选其他语言，去掉时先确认；
  - 按语言列出资料，可新建、编辑、删除此语言的版本（`MaterialDialog` 的 `variant` 模式，种类固定为基准资料的种类，没有"看完再答"）；
  - 文字左右对照，标出缺少、需要更新、已完成，可以"只看需要处理的"，一次保存全部改动；
  - 题目与选项的图片可按语言替换。
- 状态由 `content/languages.ts` 在页面上计算，它与 `ContentLanguages` 同一个哈希（两边测试用同一组样例）；以服务端的判断为准。
- 有其他语言时，基准语言的选择不可改。
- 保存版本后，警告列出未包含的语言。
- 版本列表显示语言；预览可切换语言（`preview/model.ts` 的 `draftIn` / `snapshotIn`）。

**管理后台扩展**：
- `parseSnapshot` 接受 schema 1 和 2（此前遇到 2 会显示"无法读取"）。
- 版本预览可切换语言（`snapshotIn`）。
- 草稿预览只列基准资料。

**测试**：
- `ContentLanguagesTest`、`QuizSnapshotLanguagesTest`、`ContentLanguagesIT`（7 个用例：完整与不完整、过时、资料版本、图片与文件所有者、拒绝、连带删除与复制、复制版本的哈希）；
- `ContentItSupport.TABLES` 加入译文表（只插入）；
- 前端 `languages.test.ts`、`content.test.tsx` 的三个用例（含无障碍检查）、管理后台 `snapshot.test.ts`。

**未在 Q73a-1、Q73a-2 中**：内容包的导出与导入（Q73a-3）。发布、首页、答题仍只用基准语言（Q73b）。

### Q73a-3（2026-11-01）：内容包带上其他语言

**Quiz 文件格式版本 2**（`content.transfer.QuizFileFormat.CURRENT = 2`）：
- 新增 `quiz.language`（基准语言）与 `translations`：`{语言: {texts, images, materials, hashes}}`。
  - 键与版本的快照相同（`title`、`intro`、`q3.stem`、`q3.o2.text`、`q3.image`、`q3.o2.image`）；
  - 图片的值是文件的键；
  - 资料带 `no`，即它所对应的基准资料的编号；
  - `hashes` 是各段译文所译基准文字的哈希。导入后过时的译文仍是过时的；没有给出的，按导入时的基准计算。
- **版本 1 的文件**照常导入（升级 1→2 不改内容），是只有基准语言的 Quiz。

**检查**（`QuizFileChecks`，一次报告全部问题）：
- 带 `translations` 的文件必须写明 `quiz.language`；
- 语言是平台的语言、不是基准，至多两种；
- 每段译文对应基准中存在且非空的文字，长度不超过基准字段的上限；
- 图片对应存在的题目或选项；
- 资料对应存在的基准资料，每份至多一个，种类相同，没有"看完再答"，照资料的规则检查；
- 哈希是 16 位十六进制；
- 其他语言用到的文件与基准的文件一样列出、检查并上传。

**导入**：
- 基准语言取文件写明的（以前一律取导入者的界面语言）。
- 译文、按语言的图片与资料版本经 `QuizProcesses.insertContent` 与复制共用同一段写入。
- `QB_QUIZ_IMPORT_CHECK` 的输出多 `language` 与 `languages`，导入对话框显示文件的语言。

**导出**（商家后台 `content/transfer.ts`）：
- 写版本 2，带基准语言。
- 有其他语言时写 `translations`：草稿中有的译文、图片与资料版本，完整与否都写，并带哈希。

**测试**：
- `QuizFileTest`：版本 2 的读取、版本 1 经升级读取、其他语言的检查与转换、全部拒绝的位置；
- `QuizTransferIT`：带其他语言的文件导入后，语言、译文、资料版本都在，文件中的哈希使一段译文过时，因而版本不收中文；没有基准语言即拒绝；
- 前端 `transfer.test.tsx`：导出其他语言。
- e2e 的导出期望改为版本 2。

### Q73b-1、Q73b-2（2026-11-01）：发布多语言（后端与三个前端）

**数据**（迁移 V56）：
- `qb_publication_version` 与 `qb_publication_card` 加 `languages`、`translations`；旧卡片的 `languages` 填为其 `language`；
- `qb_attempt` 加 `language`（不可变、只由流程写）。

**与 §3 的不同**（实现时的取舍）：
- 没有建 `qb_publication_card_language` 表：首页与收件箱按卡片 `languages` 列做"有交集"的判断，卡片数量下不需要单独的索引表。卡片多到需要时再加。
- 过时与缺少分成两个代码：`QB_PUBLICATION_TEXT_MISSING`、`QB_PUBLICATION_TEXT_STALE`；超长即时拒绝 `QB_PUBLICATION_TEXT_TOO_LONG`，语言不是平台的或重复 `QB_PUBLICATION_LANGUAGES_INVALID`。
- 卡片的 `translations` 只存文字 `{语言: {name, redeemDescription, digitalDescription, referralDescription, quizTitle}}`（卡片冻结，没有过时可言），`quizTitle` 取版本该语言的标题。

**规则与流程**（`publishing.PublicationLanguages`，纯 Java）：
- 保存时，文字与已存的相同就保留原来的哈希，所以基准改了而译文没改，它就过时；不在提供语言中的、基准为空的字段的译文丢弃。
- `PublicationEditing.languageProblems` 进入保存的 `problems` 与提交检查；审批内容只在有语言时加入 `languages`、`translations`（旧发布的审批内容不变）。
- 版本切换：新版本必须含全部提供的语言，且其基准不能是发布的译文语言（`QB_PUBLICATION_VERSION_LANGUAGES`）。
- 答题：`QB_ATTEMPT_START` 的 `language` 用锁外已读的卡片核对（语句数不变），不给即卡片的首选；继续、结果、答题记录经 `PlaySteps.inLanguage` 按会话的语言读快照；结果的发布名经 `cardText`。

**模板**：`qb.app.feed`（显示语言 `dl`、按交集筛选、返回 `languages`、`displayLanguage`）、`qb.app.publication`（显示语言 = 会话的 > 参数中第一个提供的 > 首选；简介与资料按版本的译文，资料版本带 `language`）、`qb.app.inbox`、`inbox-unread`（交集）。

**商家后台**：发布向导第二步的"语言"区（`publishing/LanguagesFields.tsx`）：版本只有一种语言时不显示；勾选版本的语言、选首选、按语言填写译文（旁边显示原文），问题按 `translations.<语言>.<字段>` 显示在字段下。

**App**：开始页按"测验语言"读，提供不止一种时在"开始"上方选择答题语言（页面随之换语言，提示开始后不能更换），开始时把它交给 `QB_ATTEMPT_START`；推荐说明用发布的译文。

**管理后台扩展**：审核页的"语言"区列出提供的语言、每种非基准语言的发布文字与原文对照和"已阅读"勾选；全部勾选后才给出决定面板（D-Q73-4），按审批请求记，换请求即重新勾选。版本预览本已可切换语言。

**测试**：`PublicationLanguagesIT`（两个用例：缺少、不在版本、过时；卡片；三种语言组合的首页；详情；答题语言的拒绝、选定、继续不变、结果、缺省首选、基准；只有基准的发布照旧），商家后台向导用例、App 开始页用例、后台审核页用例。

**未在 Q73b-1、Q73b-2 中**：见 Q73b-3。

### Q73b-3（2026-11-01）：站内信、邮件、答题记录与分享链接的语言

- **发布名的回落**（`PublicationLanguages.textIn`，读卡片的文字与发布的带哈希文字两种形式）：读者的语言有译文就用它，否则用首选语言的，再否则用基准。
- **站内信**：五种以卡片为对象的消息（奖励、现金奖励、兑换、电子奖品等）以 `MessageTexts.publicationName(卡片)` 逐语言渲染发布名。
- **邮件**：给商家的发布通知与"余额不足"按收件人的语言取名（`MailProcesses.readerLanguage`）；邀请邮件的名称与 Quiz 标题用首选语言（与链接的 `?lang=` 一致）。
- **答题记录** `qb.app.history`：名称与 Quiz 标题按会话的语言，搜索也查该语言的名称。
- **分享**：商家后台的链接带发布的首选语言；二维码对话框可在提供的语言间选择，链接、图片说明、文件名与海报上的名称随之。
- **App 的 `?lang=`**：照旧只对新用户改界面语言；另外记下它打开的页面，开始页把它作为第一选择（`linkLanguageOf`），所以链接总是以它的语言打开发布（提供时）。
- **测试**：`PublicationLanguagesTest`（回落的三种情形、两种存储形式、`publicationName`）、`PublicationLanguagesIT` 的答题记录、商家后台二维码语言用例、App `linkLanguage.test.ts`。

### Q73d-1（2026-11-01）：AI 翻译模版的文字（需求方 2026-11-01 确认：计入商家现有的 AI 限额，分两步做）

**数据**（迁移 V57）：`qb_ai_job.kind` 的检查约束加 `TRANSLATION`。译文表的 `translated_by`、`ai_job_id` 在 V54 已有。

**作业 `qb.ai.translate`**（`content.ai.AiTranslateJob`，接口 `ContentTranslator`，实现 `ClaudeContentTranslator`）：
- 输入是按阅读顺序编号的文字段（标题、简介、每题的题干和它的选项），由 `AiTranslations.segments` 选出。缺省只选缺少或过时的；`all` 时全部。
- **只发文字**：哪个选项正确、分数、图片、文件和元素键都不发（`AiTranslateInputTest`、`AiTranslateIT` 检查发出的内容）。元素键与原文哈希留在作业输入中，由处理器带回结果。
- 每批约 8,000 个字符，每批调用一次（effort 固定为 `low`），所有批次算一次尝试。每次尝试最长 30 分钟，最多 3 次。
- `enabledBy(qb.ai.enabled)`、`onCancelled`、输入上限 1 MiB，用量逐次 `recordUsage`。
- 预估与没有回答的尝试按批数计：每批的输入上限加 16,000 个输出 token。

**流程**：
- `QB_AI_TRANSLATE_REQUEST`：与出题、封面同一套检查、锁与限额（D-Q73-9：计入商家现有的 AI 限额，一个模版同时只有一个同类作业）。语言须是模版的其他语言；没有要翻译的文字时拒绝（`QB_AI_NOTHING_TO_TRANSLATE`）。
- 完成时再检查候选：元素存在、是文字、不超过字段长度、每个元素一条。之后存为 `READY`，候选保存 7 天，过期由现有任务清除。作业本身从不写入模版。
- `QB_AI_TRANSLATION_ACCEPT`：每段 `reviewed = true`（否则 400）。写入走 `QB_TRANSLATION_SAVE` 的同一段代码（`TranslationProcesses.apply`），记 `translatedBy = AI` 与作业编号。
  - 哈希取 AI 拿到的原文的，所以请求之后基准改了，译文写入后就是过时的。
  - 可以分几次采用；采用完全部候选后作业为 `ACCEPTED`。
  - **与 §5.2 的不同**：没有给 `QB_TRANSLATION_SAVE` 加 `aiJobId`。采用需要商家的 AI 锁和作业，所以单独成一个流程，写入规则与保存共用。

**商家后台**（"语言"标签页）：
- `ai/AiTranslatePanel.tsx`：发起前选择"只翻译未翻译或过时的"或"全部"，说明发送的内容；首次使用时显示 AI 说明。之后显示进行中（可停止）、失败、过期、就绪（可放弃），其他语言的作业另行说明。
- 每段文字下方显示 AI 建议，可"采用"，或"采用全部 AI 建议"。采用的每段都要勾选"已确认"（可以先改）才能保存。
- 保存时，已确认的 AI 段经 `QB_AI_TRANSLATION_ACCEPT` 写入，手工改的经 `QB_TRANSLATION_SAVE` 写入。来自 AI 的译文标"AI"。
- 本地与 e2e 的 Claude 桩（`tools/quizbuks/claude-stub`）对翻译请求逐段回答"译文 n"。

**测试**：
- `AiTranslateInputTest`：阅读顺序，不含正误、分数与键，只选缺少或过时的，候选的再检查，分批，回答解析。
- `AiTranslateIT`：发出的内容、一个模版一个作业、未确认的拒绝、未知候选、别人的作业、部分采用后编号不变、基准改动后过时、全部采用后关闭、下次只译过时的、`all`、语言不在模版中、没有要翻译的。
- 商家后台：建议、采用全部、逐段确认、两种写入，以及发起与说明（含无障碍检查）。

**需求方待办**：AI 说明（`AI_NOTICE`）的正式文本应加上"AI 生成译文建议，由你确认后保存"的用途，新版本须在后台发布。


### Q73d-2（2026-11-01）：发布文字的 AI 翻译与审核的对照检查

**数据**：V57 的作业种类再加 `PUBLICATION_TEXTS`、`TRANSLATION_CHECK`（与 Q73d-1 同一个迁移，尚未合并）。

**发布文字**（`QB_AI_PUBLICATION_TRANSLATE_REQUEST`）：
- 与模版的翻译用同一个作业 `qb.ai.translate`，种类为 `PUBLICATION_TEXTS`，引用为 `publication:<编号>`。模版的翻译与发布的翻译可以同时进行。
- 文字段为发布的名称、兑换说明、电子奖品说明、推荐说明，键为 `publication.<字段>`。缺省只译缺少或过时的。
- 语言须是发布提供的、不是基准的。限额与 AI 说明同其他请求。
- 完成时按字段的长度上限再检查。
- 向导"语言"区：
  - 已保存的草稿每种语言有"用 AI 翻译"按钮。
  - 建议显示在对应文字框下，"确认并采用"一段一段填入表单，随发布一起由 `QB_PUBLICATION_SAVE` 保存（发布自己的规则，含过时判断）。
  - 全部采用后以 `QB_AI_PUBLICATION_TEXTS_ACCEPT` 把作业记为已采用。这个流程不写任何内容。

**对照检查**（`QB_AI_TRANSLATION_CHECK_REQUEST`，作业 `qb.ai.translation-check`，§5.3）：
- 发起人是发布的审核人（`qb.review.publication`）。运营商的成员经其数据范围只能检查自己商户的发布。
- 比较的内容：版本中该语言的标题、简介、题干、选项文字，加上发布自己的译文，每段与原文配对。不发正误、分数与键。
- 结果为每段一个结论（意思是否一致）和简短说明，保存 7 天。模板 `qb.admin.ai-translation-check` 经发布的数据集读取，只给看得到该发布的审核人。
- 用量计在发起的审核人名下（平台的用量），不计商家的。不需要 AI 说明（审核人不是商家）。
- 审核页每种语言有"用 AI 对照检查"按钮，只列出意思不一致的段落（原文、译文、说明）。**仅供参考**：决定面板仍只等待人工勾选"已阅读"（D-Q73-4）。

**测试**：
- `AiPublicationTranslateIT`：发布文字的候选、与模版翻译同时进行、语言不在发布中、别人的作业、采用后关闭、没有要翻译的；审核人的对照检查（商家不能发起、语言不在发布中、只发文字、找出不一致的段落、用量计在审核人名下而不在商家名下、审核人能读到结果）。
- 向导用例（建议、确认并采用、作业关闭、随保存发送）；审核页用例（列出不一致之处、发起检查、决定仍等待人工勾选）。

### Q73c（2026-11-01）：迷你网站多语言（主页标语、入驻简介、商品）

**数据**（迁移 V58）：`qb_sponsor_page` 加 `languages`、`translations`；`qb_sponsor_version`（入驻）加 `intro_translations`；`qb_sponsor_product` 加 `translations`。都存为 `{语言: {字段: {text, hash}}}`。

**规则**（`content.TextTranslations`，纯 Java）：
- 每段译文记所译原文的哈希。保存时文字与已存的相同就保留原哈希，所以原文改了而译文没改，它就过时。
- 过时的保存被拒（`QB_TEXT_STALE`）。商家可以重新翻译，或在 `confirmed` 中确认"对新的原文仍然正确"（`<语言>.<字段>`），这时取现在原文的哈希。
- 其他代码：`QB_TEXT_MISSING`、`QB_TEXT_TOO_LONG`、`QB_TEXT_LANGUAGES_INVALID`。

**与 §4 的不同与补充**：
- 主页已发布时，改动当即生效，所以保存时就检查：提供的每种其他语言都要有标语且不过时，而不是等到提交时。
- 简介没有自己的基准语言：译文只靠哈希判断过时，没有译文的语言显示原文。可译成任意平台语言。
- 商品不必翻译。过时的译文同样被拒。

**审批**：
- 主页提供其他语言时，审批内容加入 `languages` 与 `translations`。只有一种语言的主页哈希不变。
- 入驻的审批内容在有译文时加入 `introTranslations`。

**公开页**：
- `qb.public.sponsor-page` 与 `-products` 的参数 `languages`（按优先顺序）。显示语言 = 参数中第一个提供的，否则为主要语言（提供的第一个）。
- 返回 `languages`、`displayLanguage`；商品返回 `textLanguage`（没有译文的商品用原文的语言）。
- App 开始页的商家简介随显示语言。

**商家后台**：
- 共用组件 `content/TextTranslationsFields.tsx`：原文在旁，标出过时，并可勾选"对新的原文仍然正确"。
- 主页编辑器的"其他语言"区：勾选语言、选主要语言、填写各语言的标语。
- 基本资料可选简介的其他语言。
- 商品对话框按主页提供的其他语言填写名称与说明。

**App**：`/s/<handle>` 有语言切换，链接的 `?lang=` 优先，然后是界面语言。标语、简介、商品的 `lang` 按实际语言标注。

**管理后台扩展**：
- 主页审核页列出提供的语言与各语言的标语。
- 入驻审核页（总部与运营商）显示简介的各语言版本。

**测试**：
- `SiteLanguagesIT`：缺少、语言须含主页自己的语言、过时被拒与确认、访客语言与主要语言、商品的有译文与无译文、商品过时。
- `SponsorPageIT`、`SponsorProductIT` 的公开字段白名单加入新列；公开模板快照已更新。
- 前端：商家后台的语言区与简介、App 的语言切换、后台审核页的标语。
