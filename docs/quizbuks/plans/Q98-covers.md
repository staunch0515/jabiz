# Q98 封面：撤销 AI 封面（Q98a）、封面按语言提供（Q98b）（2026-11-05 需求方确认）

需求方（2026-11-05）：
1. 去掉 AI 生成 Quiz 封面的功能，因为图片太差（Q8b 的图库设计与 Q96 的 SVG 插画都不要了）。
2. 模版的封面是发布没有封面时的备用；不管是不是备用，封面都应按语言提供三种语言的。需求方选择**模版与发布都要**按语言提供，没有某种语言的就退回基准语言的那张。

分两个 PR：先 Q98a，再 Q98b。

## Q98a 撤销 AI 封面

| # | 决定 |
|---|---|
| D-Q98-1 | 删除 `QB_AI_COVER_REQUEST` / `QB_AI_COVER_ACCEPT` 及其页面、提示、绘制代码（`CoverSvg`、`CoverRenderer`、`CoverDesigns`、`AiCoverPrompt`、`CoverDesigner` 及其实现）、错误码 `QB_AI_COVER_FONT` / `QB_AI_COVER_TEXT` |
| D-Q98-2 | 作业类型 `qb.ai.cover` 暂时保留登记：部署时可能还有排队的封面作业，处理器 `AiCoverWithdrawnHandler` 立即以永久失败 `QB_AI_COVER_WITHDRAWN` 结束它们（不调用 Claude、不计费），结局流程照旧关闭并清空封面列。下一个版本确认没有这类作业后删去 |
| D-Q98-3 | 不改表：`QbAiJob` 的 `cover1..3` 与种类 `COVER` 留作历史；丢弃与过期照常清空封面列，未用的图由平台清扫删除 |

实施（2026-11-05）：上述删除；`AiCancelIT`、`AiPdfIT`、`AiCostControlIT` 中原来用封面请求的地方改为出题请求（同样检验限额与开关）；删除 `AiCoverIT`、`AiCoversTest`、`CoverSvgTest`；
商家后台删除 `AiCoverPanel` 与相关接口、文案、测试；e2e `ai-cover.spec.ts` 改为只测 PDF 出题的 `ai-pdf.spec.ts`；本地 Claude 桩不再回答封面请求。

## Q98b 封面按语言提供

### 1. 决定

| # | 决定 |
|---|---|
| D-Q98-4 | **模版**：基准语言的封面照旧是 `QbQuiz.cover`；每种其他语言可以另有一张，存为该语言的译文行，元素键 `quiz.cover`（`QbContentTranslation.image`，与题目、选项的按语言图片同一机制，Q73）。只经 `QB_TRANSLATION_SAVE` 写入，去掉语言时随之删除，复制模版时一起复制 |
| D-Q98-5 | **版本**：快照的 `translations.<语言>.images` 多一个键 `cover`（与 `q1.image` 并列）；`QuizSnapshots.in(语言)` 按它替换封面；快照引用的文件照旧登记 `QbVersionFile`（文件不被清扫） |
| D-Q98-6 | **发布**：`QbPublication` 加三个可选文件字段 `coverJa`、`coverZh`、`coverEn`（迁移给 `qb_publication_version` 加列：只追加表只能加列）；只能为发布提供的语言设置（`QB_PUBLICATION_SAVE`，草稿中设、随审批冻结，审批内容含它们；复制时一起复制） |
| D-Q98-7 | **卡片**存三种语言已经选好的封面：`QbPublicationCard` 加 `coverJa` / `coverZh` / `coverEn`（普通表加列），上架与版本切换时按顺序取第一张有的：①发布自己这种语言的 ②发布自己的基准封面 ③版本里这种语言的 ④版本里的基准封面。`cover` 列仍是基准的结果（旧的读取不变） |
| D-Q98-8 | **显示**：首页、详情、历史、商家主页按显示语言（模板里已有的 `dl.lang`）取该语言的列，没有即 `cover`；答题页按答题语言。公开模板的白名单加这三列，使公开的封面文件可以匿名读取 |
| D-Q98-9 | **不强制**：没有某种语言的封面即退回基准；不影响语言是否"完整"（同资料，D-Q73-5）。以前的模版、版本、发布与卡片什么都不用改 |
| D-Q98-10 | **导入导出**：模版文件的 `translations.<语言>.images.cover` 带上该语言的封面；导入照现有的按语言图片处理 |

### 2. 页面

- 商家后台的模版编辑器"语言"页：每种其他语言一个"封面"上传框（显示基准封面作为缺省）；预览按语言显示。
- 发布向导：在封面一栏为发布提供的每种语言各一个上传框（可空）。
- 后台审核页：按语言列出发布的封面（没有即注明"使用基准封面"）。
- 答题者 App：不需改页面，模板给出的就是该语言的封面。

### 3. 测试

- 单元：快照的按语言封面（`QuizSnapshotLanguagesTest`）、卡片封面的选择顺序（`PublicationEditing.cardCover` 的各种组合）、导入文件的检查。
- IT：`PublicationCoverIT` 扩展（发布与版本各有或没有某语言封面时，首页、详情、历史、商家主页按显示语言给出的封面；审批哈希；复制；版本切换）、`ContentLanguagesIT`（保存、去掉语言、复制）、公开读取。
- 前端：商家后台的语言页与发布向导、后台扩展的审核页（Vitest，axe）。
- 场景快照中卡片多三列（值为空），随之更新。

### 4. 风险

- 卡片与发布各加三列，模板读取多一个 `CASE`：语句数不变（同一条语句），`AttemptStatementBudgetIT` 不受影响（答题路径不读卡片的封面）。
- 公开文件的白名单漏掉一处，封面在公开页上即无法显示：IT 覆盖每个公开模板。

### 5. 实施记录（2026-11-05）

- 模版：`ContentLanguages.COVER`（`quiz.cover`，`Part.COVER`，图片）；`TranslationProcesses.sources`、`QuizLanguages`（快照键 `cover`）、`QuizSnapshots.in`（按语言替换封面）、`QuizProcesses`（复制与导入时把快照键换回草稿键）、`QuizFileChecks`（导入文件接受 `images.cover`）。版本文件照旧经 `QuizSnapshots.files` 登记各语言的图片。
- 发布：`QbPublication.coverJa/coverZh/coverEn`（文件）、`QbPublicationCard.coverJa/coverZh/coverEn`（文本），迁移 `V75__quizbuks_covers_per_language.sql`；`PublicationCovers`（字段、校验 `QB_PUBLICATION_COVER_LANGUAGE`、卡片的选择）；`QB_PUBLICATION_SAVE` 的 `covers`（检查是商家自己的文件）、复制、审批内容（有才写 `covers`，以前的审批哈希不变）、上架与版本切换写卡片。
- 模板：`qb.app.feed`、`qb.app.publication` 按显示语言、`qb.app.history` 按答题语言取卡片该语言的封面，没有即 `cover`。商家后台的发布列表与 App 的商家主页仍显示基准封面。
- 页面：商家后台"语言"页的图片列表第一项是该语言的封面（基准没有封面时注明）；发布向导中每种其他语言多一个封面上传框；预览按语言显示；后台审核页按语言列出发布自己的封面；导出文件带上 `images.cover`。
- 测试：`CoverLanguagesIT`（3：版本的按语言封面在首页与详情页按语言显示；发布自己的封面优先的各种组合与复制；不提供的语言、基准语言、别人的文件被拒）、`QuizSnapshotLanguagesTest`、`QuizFileTest`、`ContentLanguagesTest` / `ContentLanguagesIT`（原先把 `quiz.cover` 当作不存在的键的例子改为 `quiz.banner`）；`PublicationCoverIT`、`PublicationLanguagesIT`、`PublicationIT`、`AttemptStatementBudgetIT`、场景回放照旧通过，`platformCheck` 0 个错误。
  商家后台 Vitest（语言页的封面项、发布向导按语言的封面随保存发送，680 个全过）、后台扩展的检查（229 个全过）。e2e 未在本机运行。

