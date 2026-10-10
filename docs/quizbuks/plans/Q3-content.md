# Q3 内容工坊（后端，5–6 天）— 实施计划

分支：`1.2/quizbuks-3-content`（从 `1.2/quizbuks` 拉出，PR 合回 `1.2/quizbuks`）。只改 `.jabiz-app-paths` 中的路径。

本阶段只做后端：实体、数据视图、文件策略、流程、规则、SQL 模板、迁移、三语消息、测试。

- 商家后台页面（`quizbuks-web/sponsor` 的内容工坊）在平台 16c 合入后另做（约 3 天）。
- AI（`QbAiJob`、`QB_AI_*`，M-25、M-26）在 Q8 做；本阶段只留 `aiGenerated` 字段，恒为 false。
- 不依赖 Q2：编辑内容只要求 `qb.content.write`（设计 §1："入驻已通过才能发布"）。商家是否已入驻（M-31"存在出资方"）由 Q4 的 `QB_PUBLICATION_SUBMIT` 检查。Q2 之前，商家账号由管理员在后台建用户并分配 `QB_SPONSOR`；测试用 `TestTokens` 签发令牌。

已核实的平台事实：
- 文件清扫（`FileReferences`）只看当前版本并排除墓碑：文件只要在某实体的当前行中被引用就受保护，墓碑会把它放出。
- `LoadEntity` 走数据视图的范围读取，读别人的模版得到 404。
- 一个文件策略只能声明一个读取权限（`FilePolicy.readPermission`）。
- 被引用的实例不能删除（`DatasetEntityManager.ensureNotReferenced`，`STILL_REFERENCED`）。

## 设计决定（已确认，用户 2026-10-10；`02-design.md` §2、§3.2、§5.2 已同步）

### D-Q3-1 版本是显式"保存版本"，不是每次保存都生成【已确认 Q3-1】

需求 M-27 原文是"每次保存生成新版本"，但 M-22 允许草稿不完整，而版本必须完整（M-31 的内容规则在生成版本时检查，见 D-Q3-5）。因此草稿的保存（`QB_QUIZ_SAVE`、`QB_QUESTION_SAVE` 等）不生成版本；版本由 `QB_QUIZ_PUBLISH_VERSION`（界面上叫"保存版本"）生成。

### D-Q3-2 快照是一个只写一次的整体文档，不拆子行

`QbQuizVersion.content` 存一个规范 JSON 文本，含完整的题目、选项、资料、图片引用；不建"版本题目 / 版本选项"子实体。理由：
- **原子**：一个版本一行，只写一次（`writeOnce`），无法部分更新。
- **代价小**：Q5 开始答题只读一行；子行方案每个版本约写 900 行。
- **哈希**：`contentHash = ContentHash.of(内容)`，用平台审批所用的规范哈希（与键顺序无关），Q4 的审批可直接绑定它。
- **存 `text` 不存 `jsonb`**：平台没有通用的 JSON 语义类型（`I18nText` 有自己的语义）；平台存档的报表与单据也用规范文本。模板需要时可写 `content::jsonb`。
- **题目与选项按序号引用**：快照中用 1 起的 `no`，不用实体主键；Q5 的答案用题号、选项号直接对应，快照不可变，序号稳定。
- **文件另行登记**：平台看不到 JSON 里的文件，快照引用的文件另写 `QbVersionFile`（D-Q3-6），清扫才知道它们仍被引用。

快照格式（`schema: 1`，所有键都写出，空值为 null）：

```json
{"schema":1,"title":"…","intro":"…","cover":"<fileId>","timeLimitSec":300,"aiGenerated":false,
 "materials":[{"no":1,"kind":"ARTICLE","title":"…","description":"…","body":"…","url":null,"pdf":null,"audio":null,
               "images":[{"no":1,"image":"<fileId>","caption":"…"}]}],
 "questions":[{"no":1,"stem":"…","image":null,"points":2,
               "options":[{"no":1,"text":"…","image":null,"correct":true}]}]}
```

### D-Q3-3 版本号

`versionNo` 为 1、2、3…（同一模版内唯一）；显示名 `label` 在生成时写入，规则 `"v1." + (versionNo − 1)`（v1.0、v1.1 … v1.10），三个前端不各自推算。模版上同时保存最新的 `latestVersionNo` 与 `versionLabel`，列表不必 join。

### D-Q3-4 编辑的粒度、排序与并发

- **粒度**：按"模版头 / 一道题（连同选项）/ 一条资料（连同图片组）"各自保存，每个保存流程只写真正改变的行（时态表只追加：整份重写一次就要追加上千行）。
- **排序**：`seq` 只表示顺序，显示的题号就是位置；新增取 `max(seq) + 1`；删除不重排；`QB_QUESTION_REORDER` / `QB_MATERIAL_REORDER` 接收完整的有序主键列表，必须与当前集合完全相同（否则 422 `QB_ORDER_MISMATCH`，防止过期页面），重新赋 1..n，只更新改变了的行。选项、图片组的顺序就是输入数组的顺序。`seq` 不建唯一约束（交换两项时逐条检查会在中间状态冲突）。
- **并发**：写模版的流程先取 `HoldLock.exclusive("qb.quiz:" + quizId)` 再读取；题数上限、seq 分配、快照一致性由锁保证。模版有 `revision`（每次内容改动加 1），保存可带 `baseRevision`，与当前不同即 422 `QB_QUIZ_CHANGED`（另一个窗口改过）；不带时后写者胜。子项保存也更新模版行（`revision`、`editedAt`、`changedSinceVersion`、计数），"最后修改时间"包括子项的改动。

### D-Q3-5 两类校验：草稿保存时与生成版本时

**草稿保存时**（草稿可以不完整）：

| 对象 | 规则 |
|---|---|
| 模版 | `title` 必填、非空白、≤ 100；`intro` ≤ 5 000（Markdown）；`timeLimitSec` 可空，30–7 200 |
| 题目 | `stem` 可空、≤ 1 000；`points` 必填 1–1 000（缺省 1）；选项 0–6 个 |
| 选项 | `text` ≤ 300；`correct` 必填（缺省 false） |
| 资料 | `kind` 必填且不可改；不属于该类型的字段必须为空（`QB_MATERIAL_FIELD_NOT_ALLOWED`）；`title` ≤ 100、`description` ≤ 500、`body` ≤ 20 000；`url` 只接受 `https?://` 且无空白、≤ 2 000（`QB_MATERIAL_URL_FORMAT`，挡住 `javascript:`）；图片组 ≤ 20 张，`caption` ≤ 200 |
| 上限（锁内） | 每个模版题目 ≤ 100（`QB_QUIZ_TOO_MANY_QUESTIONS`）、资料 ≤ 10（`QB_QUIZ_TOO_MANY_MATERIALS`） |
| 文件 | 平台检查文件存在且策略相符 |

**生成版本时**（`QB_QUIZ_PUBLISH_VERSION`，M-31 中属于内容的部分；一次报告全部违规，422，带 `field` 路径如 `questions[3].options` 与题号 / 选项号 / 资料号参数）：至少 1 道题（`QB_QUIZ_NO_QUESTIONS`）；题干非空（`QB_QUESTION_STEM_REQUIRED`）；选项 2–6 个（`QB_QUESTION_OPTION_COUNT`）；至少 1 个正确（`QB_QUESTION_NO_CORRECT`）；选项有文字或图片（`QB_OPTION_EMPTY`）；资料有标题（`QB_MATERIAL_TITLE_REQUIRED`）且有该类型的内容（`QB_MATERIAL_CONTENT_REQUIRED`；图片组 1–20 张，`QB_MATERIAL_NO_IMAGES`）；与最新版本哈希相同即 `QB_QUIZ_VERSION_UNCHANGED`；规范文本超过 2 MB 即 `QB_QUIZ_TOO_LARGE`。资料的 `description` 不强制【已确认 Q3-3】。存在出资方、档位等其余 M-31 规则属于发布（Q4）。完整性规则写成纯 Java 的 `QuizCompleteness.check(草稿)`。

### D-Q3-6 删除与文件【已确认 Q3-2】

**"删除"模版就是移除，不写墓碑**：`QbQuizVersion.quizId` 引用模版，而只写一次的版本不能删除，按平台规则（`STILL_REFERENCED`）模版一旦有版本就永远删不掉。因此 `QB_QUIZ_DELETE`：置 `removed = true`、清空 `cover`；给全部资料、图片、题目、选项写墓碑（先子后父）；给不再需要的 `QbVersionFile` 写墓碑。商家视图以 `removed = false` 限定，移除后商家看不到、各流程对它 404；管理员视图仍可看到；版本保留（Q4 的发布还引用它们）。

**文件**：
- 草稿中替换或移除的图片成为孤儿，由平台每日 `FILE_SWEEP` 在 24 小时后清扫，业务流程不调用 `FILE_DELETE`。
- `QbVersionFile` 是**普通时态实体**（不是设计 §3.2 写的只写一次——那样已移除模版的文件永不释放），每个"模版 + 文件"一行；生成版本时补登快照中尚未登记的文件。
- 移除模版时保留仍被发布引用的版本的文件，其余写墓碑。Q3 没有发布，保留集合为空；代码留 `filesStillNeeded(quizId)`，Q4 改为"被发布引用的版本快照中的文件"。
- 克隆共用文件（同一 `fileId`，不复制字节），删除原模版不影响克隆。

### D-Q3-7 单一语言

按【缺省 Q16】内容为单一语言：标题、简介、题干等用文本类型而非 `I18nText`；简介与文章正文是 Markdown，存为多行文本，只在前端渲染、不允许原始 HTML。不加"内容语言"字段。

## 要求

1. **权限**（`QbPermissions`、`QbRoles`；部署后再执行一次 `QB_SETUP` 补授权）：新增 `qb.admin.quiz.read`（管理员只读全部模版与版本，授予 `QB_ADMIN_CONTENT`）与 `qb.content.file.read`（读内容文件，授予 `QB_SPONSOR`、`QB_ADMIN_CONTENT`；Q5 再授予 `QB_TAKER`）。已有的 `qb.content.write` 用于全部内容流程、内容文件上传与商家视图读取。
2. **字典**（静态，三语）：`urn:jabiz:dict:quizbuks:quiz-status`（`DRAFT`、`VERSIONED`）、`urn:jabiz:dict:quizbuks:material-kind`（`ARTICLE`、`LINK`、`VIDEO_LINK`、`PDF`、`IMAGES`、`AUDIO`）。
3. **文件策略**（上传 `qb.content.write`，读取 `qb.content.file.read`）：`qb.content.image`（JPEG、PNG，10 MB，`maxPixels` 4 000 万，变体 320 / 640 / 1280，平台重新编码去 EXIF / GPS）、`qb.content.pdf`（20 MB）、`qb.content.audio`（MP3、M4A、OGG，50 MB）。
4. **实体**（包 `com.jabiz.quizbuks.content`）：7 个，全部时态、不允许预定，见下节。
5. **数据视图**（每个实体两个）：默认视图 `urn:jabiz:dataset:default:<实体>`（管理员只读，`processOnlyWrites()`，平台的引用检查与流程写入经过它）；商家视图 `urn:jabiz:dataset:sponsor:<实体>`（`fromContext("ownerId", actorId)`，`QbQuiz` 另加 `removed = false`；只读、不回看；权限 `qb.content.write`）。写入只经流程。默认视图的批量上限放到克隆与移除所需（选项 600、图片 200、题目 100、版本文件 5 000）。
6. **流程**：模版 `QB_QUIZ_SAVE`、`QB_QUIZ_DELETE`、`QB_QUIZ_CLONE`、`QB_QUIZ_PUBLISH_VERSION`；题目 `QB_QUESTION_SAVE`、`QB_QUESTION_DELETE`、`QB_QUESTION_REORDER`；资料 `QB_MATERIAL_SAVE`、`QB_MATERIAL_DELETE`、`QB_MATERIAL_REORDER`。
7. **SQL 模板**：`qb.sponsor.quizzes`（M-20）、`qb.sponsor.quiz-versions`（M-27 历史）。
8. **消息**（三语，启动检查齐全）：实体与字段、10 个流程、全部 `QB_*` 规则与错误码、模板与列名。
9. **文档**：`02-design.md` §2、§3.2、§5.2；`03-plan.md` 的 Q3 行；`backend/quizbuks/CLAUDE.md` 加内容约定（锁 `qb.quiz:<id>`、上限常量在 `content.ContentLimits`、写入只经流程）。
10. **OpenAPI 快照**（`quizbuks/src/test/resources/openapi.json`）随新流程更新。

## 实体与迁移

记号：T = 时态（只追加），W = 只写一次。主键都是 UUIDv7 标识；`ownerId`（不可变、必填，取操作人）冗余存在每个子实体上，使商家视图能直接按范围限定；指向父实体的引用一律不可变（条件可下推到索引，04 §5.1）。

| 实体（表） | 类型 | 字段 |
|---|---|---|
| `QbQuiz`（`qb_quiz_version`） | T | `quizId`；`ownerId`；`title`（≤ 100，必填非空白）；`intro`（≤ 5 000，多行，Markdown）；`cover`（`qb.content.image`）；`timeLimitSec`（30–7 200）；`status`（字典，`processOnly`）；`removed`、`aiGenerated`（`processOnly`）；`latestVersionNo`、`versionLabel`、`revision`、`editedAt`、`changedSinceVersion`、`questionCount`、`materialCount`（`processOnly`）；`clonedFrom`（引用 `QbQuiz`，不可变）。显示字段 `title` |
| `QbMaterial`（`qb_material_version`） | T | `materialId`；`quizId`；`ownerId`；`seq`；`kind`（不可变）；`title`；`description`；`body`；`url`；`pdf`（`qb.content.pdf`）；`audio`（`qb.content.audio`）。显示字段 `title` |
| `QbMaterialImage`（`qb_material_image_version`） | T | `imageId`；`materialId`；`quizId`；`ownerId`；`seq`；`image`（必填）；`caption`（替代文本） |
| `QbQuestion`（`qb_question_version`） | T | `questionId`；`quizId`；`ownerId`；`seq`；`stem`；`image`；`points` |
| `QbOption`（`qb_option_version`） | T | `optionId`；`questionId`；`quizId`（一次取全模版的选项）；`ownerId`；`seq`；`text`；`image`；`correct` |
| `QbQuizVersion`（`qb_quiz_version_version`） | W | `versionId`；`quizId`；`ownerId`；`versionNo`；`label`；`title`（复制，供列表）；`timeLimitSec`；`questionCount`、`materialCount`；`fullScore`（分值之和）；`content`（规范 JSON，D-Q3-2）；`contentHash`；`versionedAt`。唯一（`quizId`, `versionNo`）。**没有文件字段**（否则版本把文件永远钉住） |
| `QbVersionFile`（`qb_version_file_version`） | T | `versionFileId`；`quizId`；`ownerId`；`firstVersionNo`；`image` / `pdf` / `audio` 三个文件字段（一个字段只能对应一个策略），恰好一个非空（流程保证）。只由生成版本插入、由移除写墓碑 |

**迁移** `db/migration/V3__quizbuks_content.sql`：沿用 Q1 的建表函数写法（含 `UNIQUE(主键, version_no)`、当前版本索引、`process_seq_id` 索引、外键、`jabiz_protect_append_only`，用后删除函数）。另建索引：各表的父引用（`quiz_id`、`material_id`、`question_id`）、`owner_id`、**每个文件列**（清扫的引用检查对每个文件字段执行 `= ANY(:ids)`，没有索引就是每天全表扫描）；`qb_quiz_version_version` 另有只写一次所需的 `UNIQUE (version_id)` 与唯一约束的支撑索引 `(quiz_id, version_no)`。`content` 为 `text`，`content_hash` 为 `char(64)`。

## 流程

共同点：权限 `qb.content.write`；输入为 record，Bean Validation（400）；先 `HoldLock.exclusive("qb.quiz:" + quizId)`（新建时不取锁），再经商家视图 `LoadEntity`（不是自己的或已移除的即 404，不透露是否存在）；子项经默认视图读全部（不截断）；写入经 `ctx.changes()`，`ownerId` 取自已载入的模版；可选 `baseRevision`；违规累积后 422；输出至少有 `quizId`、`revision`、`editedAt`。

| 流程 | 输入 | 要点 | 错误代码 |
|---|---|---|---|
| `QB_QUIZ_SAVE` | `quizId?`、`title`、`intro`、`cover`、`timeLimitSec`、`baseRevision?` | 无 `quizId` 即新建（`DRAFT`、`revision 1`、计数 0）；有改动才写，`revision + 1`（M-21、M-22） | `QB_QUIZ_CHANGED`；字段规则 |
| `QB_QUIZ_DELETE` | `quizId`、`baseRevision?` | `actsOn`；按 D-Q3-6 移除 | `QB_QUIZ_CHANGED` |
| `QB_QUIZ_CLONE` | `quizId`、`versionNo?`、`title?` | `actsOn`；从草稿或指定版本快照（即"恢复旧版本"）复制全部内容（新主键、同一 `fileId`、`seq` 从 1 起）；新模版 `DRAFT`、`clonedFrom = 源`；输出新 `quizId`（M-28） | `QB_QUIZ_VERSION_NOT_FOUND` |
| `QB_QUIZ_PUBLISH_VERSION` | `quizId`、`baseRevision?` | `actsOn`；完整性检查（D-Q3-5）→ 组装快照 → 哈希 → 与最新版本比较 → 插入版本（`versionNo = latest + 1`、`label`）→ 补登 `QbVersionFile` → 模版 `VERSIONED`、`latestVersionNo`、`versionLabel`、`changedSinceVersion = false`。输出 `versionId`、`versionNo`、`label`、`questionCount`、`fullScore`、`contentHash`（M-27） | D-Q3-5 的各码、`QB_QUIZ_CHANGED` |
| `QB_QUESTION_SAVE` | `quizId`、`questionId?`、`stem`、`image`、`points`、`options[≤6]{optionId?, text, image, correct}`、`baseRevision?` | 新题追加到末尾（锁内查 ≤ 100）；选项按数组比较新旧（没出现的删、改了的更新、新的插入）；`questionId` / `optionId` 必须属于该模版 / 该题（M-24） | `QB_CONTENT_NOT_IN_QUIZ`、`QB_QUIZ_TOO_MANY_QUESTIONS`、`QB_QUIZ_CHANGED` |
| `QB_QUESTION_DELETE` | `quizId`、`questionId`、`baseRevision?` | 先删选项再删题；`questionCount − 1` | `QB_CONTENT_NOT_IN_QUIZ` |
| `QB_QUESTION_REORDER` | `quizId`、`questionIds[≤100]`、`baseRevision?` | D-Q3-4 | `QB_ORDER_MISMATCH` |
| `QB_MATERIAL_SAVE` | `quizId`、`materialId?`、`kind`、`title`、`description`、`body`、`url`、`pdf`、`audio`、`images[≤20]{imageId?, image, caption}`、`baseRevision?` | 资料 ≤ 10；类型不可改；不属于该类型的字段必须为空；图片组按数组比较新旧（M-23） | `QB_MATERIAL_KIND_FIXED`、`QB_MATERIAL_FIELD_NOT_ALLOWED`、`QB_QUIZ_TOO_MANY_MATERIALS`、`QB_CONTENT_NOT_IN_QUIZ` |
| `QB_MATERIAL_DELETE` / `QB_MATERIAL_REORDER` | 同题目 | 同题目 | 同题目 |

**纯 Java 部件**（无 I/O，单元测试）：`ContentLimits`（全部上限）、`QuizSnapshot` / `QuizSnapshots`（快照对象、`Map`、规范 JSON 互转，哈希用 `ContentHash.of`）、`QuizCompleteness`、`Reorder`、`VersionLabels`。各流程的计算步骤只组合这些部件。

**错误码**：字段规则违规 400（`QB_QUIZ_TITLE_BLANK`、`QB_QUIZ_TIME_LIMIT_RANGE`、`QB_QUESTION_POINTS_RANGE`、`QB_MATERIAL_URL_FORMAT`）；流程拒绝 422；不存在或不是自己的 404；无权限 403；经数据视图直接写入被拒；版本的更新与删除 422 `WRITE_ONCE`（平台）。

## SQL 模板

- **`qb.sponsor.quizzes`**（`queries/qb/sponsor/quizzes.sql`，M-20）：数据视图用商家视图（本人的、未移除的）；权限 `qb.content.write`；参数 `q`（≤ 100）按标题不区分大小写搜索，`%`、`_`、`\` 在 SQL 中转义使输入按字面匹配，值照常参数绑定；结果列：`quizId`、`title`、`cover`、`versionLabel`、`latestVersionNo`、`questionCount`、`materialCount`、`status`、`changedSinceVersion`、`aiGenerated`、`timeLimitSec`、`editedAt`；筛选 `status`、`aiGenerated`、`editedAt`，排序 `editedAt`、`title`、`questionCount`，默认 `editedAt` 倒序。Q4 把状态扩展为"已发布"。
- **`qb.sponsor.quiz-versions`**（M-27 历史）：参数 `quizId`；结果列 `versionId`、`versionNo`、`label`、`title`、`questionCount`、`fullScore`、`versionedAt`、`contentHash`（不含 `content`，单个版本经商家视图按主键读取）；默认 `versionNo` 倒序。

## 测试

集成测试连本地 PostgreSQL（`JABIZ_TEST_DB_*`），BlockHound 开启；令牌经 `QbItSupport`：商家 A、B，管理员，无权限者。

- **单元**：`QuizSnapshotsTest`（往返、哈希与键顺序无关、空值写法）；`QuizCompletenessTest`（每条规则正反用例，jqwik 随机草稿与独立实现一致）；`ReorderTest`；`VersionLabelsTest`（1 → v1.0、11 → v1.10）；`QbRolesTest`（新授权）。
- **`ContentIT`**（M-20 – M-24、M-27）：只有标题的草稿；六类资料；带选项的题目；调整顺序；不完整的草稿可保存；生成 v1.0 与快照相符，编辑后 v1.1，不改动再生成被拒；不完整时一次返回全部违规与路径；上限（第 101 道题、第 11 条资料 422；7 个选项、21 张图 400）；类型不符的字段 422；`javascript:` 链接 400；过期的 `baseRevision`；同一模版并发保存两道题都成功且计数正确；两个模板的搜索（大小写、`%` 按字面）、排序、计数、版本历史。
- **`ContentOwnerIT`**（两个商家隔离）：B 对 A 的模版在视图中看不到、模板中看不到、每个流程都 404，把 A 的 `questionId` 放进 B 的模版得 `QB_CONTENT_NOT_IN_QUIZ`；经数据视图直接写一律被拒；无 `qb.content.write` 403；管理员看到全部、只读。
- **`VersionSnapshotIT`**（快照不可变）：生成 v1.0 后修改、删除题目、移除模版，v1.0 的内容与哈希不变，且从存储文本重算的哈希相等；经数据视图修改、删除版本被拒；克隆 v1.0 得到相同内容。
- **`ContentFilesIT`**（文件与清扫）：经 `/api/files?policy=` 上传三类文件；错误类型、策略不符被拒；无 `qb.content.file.read` 不能读；以 `FILE_SWEEP`（两天后）清扫：只在草稿中的保留、生成版本后在草稿中被替换的保留、从未使用的清除、克隆共用的在删除原模版后保留、移除模版（无发布）后清除。
- 每个集成测试类断言 7 张时态表上没有 UPDATE / DELETE。
- **场景回放** `scenarios/qb/content.yml`（固定时钟）：A 建模版、两条资料、三道题、调整顺序、生成 v1.0、改一题生成 v1.1、不改动再生成（期望错误）、克隆 v1.0、B 删除（期望 404）、A 移除、模板只剩克隆。场景不带文件（平台缺口 2），文件由 `ContentFilesIT` 覆盖。`scenarios/qb/setup.yml` 的快照随新授权更新。
- `platformCheck` 通过；`ArchitectureTest` 不变；OpenAPI 快照已更新。

## 验收标准

- [x] `./gradlew :quizbuks:check` 通过（含 `platformCheck`）；`tools/check-app-paths.sh` 通过。PR 上 `ci.yml` 与 `quizbuks.yml` 全绿：**待 PR 验证**（本阶段按要求未开 PR）。
- [x] M-20：`qb.sponsor.quizzes` 给出封面、标题、版本号、题数、状态、最后修改时间、AI 标记，可按标题搜索，只含本人未移除的模版。
- [x] M-21 / M-22：新建、编辑、删除（移除）模版；草稿可以不完整，总有所有者；只有所有者能改。
- [x] M-23：六类资料（视频只存链接），每条有标题与描述；PDF、音频、图片组经各自的文件策略，图片重新编码。
- [x] M-24：题干可带图，分值 ≥ 1，2–6 个选项（可带图）、至少 1 个正确（生成版本时检查）；题目与资料可调整顺序。
- [x] M-27：生成 v1.0、v1.1… 的只写一次快照，有历史，之后的编辑不改变已有版本。
- [x] M-28：从草稿或指定版本完整克隆。
- [x] 快照引用的文件不被清扫，不再被引用的文件被清扫。
- [x] 7 张时态表上没有 UPDATE / DELETE。
- [x] 消息三语齐全；`02-design.md`、`03-plan.md`、`backend/quizbuks/CLAUDE.md` 已更新；部署后执行 `QB_SETUP` 补上两个新权限。

## 实施记录

### 与计划的差异

- **版本号字段名**：`versionNo` 是平台给每个时态实体的行版本（保留名），所以 `QbQuizVersion` 的版本号字段为 `versionNumber`（列 `quiz_version_no`）。
  流程输入输出与模板 `qb.sponsor.quiz-versions` 的结果列仍叫 `versionNo`。
- **按标题搜索**：`qb.sponsor.quizzes` 用 `strpos(lower(title), lower(:q)) > 0`，输入本来就按字面匹配，`%`、`_`、`\` 不必转义（效果同计划，`ContentIT` 验证）。
- **链接规则**：平台的 `PATTERN` 只接受前后端一致的正则，`\S` 不可用；`QB_MATERIAL_URL_FORMAT` 为 `https?://[^\p{Z}\p{Cc}]+`（不含任何 Unicode 空白与控制字符）。
- **路径**：完整性检查的 `field` 路径下标自 0 起（`questions[0].stem`），参数中的题号、选项号、资料号自 1 起。
- **"保存版本"不改 `revision` 与 `editedAt`**：它们只记录内容的改动；版本只把 `changedSinceVersion` 置为 false。移除模版照常给 `revision + 1`。
- **新模版的 `latestVersionNo` 为 0**（必填，比空值好比较），`versionLabel` 为空。
- **OpenAPI 快照没有变化，未加 `OpenApiSnapshotIT`**：平台的接口文档按通用入口（`/api/processes/{name}/…`）描述流程，新流程不改变文档。
  另发现平台缺口 4（见下）：`jabizApp.openApiSnapshot` 的设置不生效，应用的快照测试会写到平台的 `frontend/openapi/openapi.json`。
- **随本分支一起完成的 Q1 后续**（平台 16i，决策 D40）：受控参数、`QB_SETUP` 以受控变更提出首个值、`QB_ADMIN_SUPER` 不再有 `platform.param.write`（见 `Q1-skeleton.md` 已知问题）。

### 测试

单元：`QuizSnapshotsTest`、`QuizCompletenessTest`（含 300 例 jqwik 与独立实现对照）、`ReorderTest`、`VersionLabelsTest`、`QbRolesTest`、`MessagesTest`。
集成：`ContentIT`（10）、`ContentOwnerIT`（4）、`VersionSnapshotIT`、`ContentFilesIT`（2），每个测试后检查 7 张表没有 UPDATE / DELETE；场景 `qb/content.yml`，`qb/setup.yml` 快照随新授权与受控参数更新。

### 已知问题

- 场景不带文件（平台缺口 2），文件只在 `ContentFilesIT` 中验证。
- `intro`、`body` 在通用后台显示为普通多行文本（平台缺口 1）。
- 持有 `qb.content.file.read` 的人知道 `fileId` 就能读任何内容文件（平台缺口 3，Q5 前评估）。
- 商家后台页面未做（16c 之后）。
- PR 上的 CI 未运行（本阶段不开 PR）。

## 风险

- **M-27 的理解**（已确认 Q3-1：显式"保存版本"）：若坚持"每次保存即新版本"，草稿就必须完整，与 M-22 矛盾。
- **时态写入量**：每次子项保存追加 1 行模版 + 改变的行；页面应在离开题目时保存，不要逐键保存（页面阶段的约定）。
- **"删除"是移除**（已确认 Q3-2）：模版文字与版本快照留在库中，文件会被清扫。若需求要求彻底删除文字（个人数据），需要平台的受控清除，或让版本不引用模版。
- **文件读取不按行授权**：持有 `qb.content.file.read` 的人知道 `fileId` 就能读该文件（UUIDv7 难以猜中）；Q5 起答题人也持有该权限，Q5 前评估平台缺口 3。
- **快照大小**：按上限约 1 MB 以内，2 MB 为硬限制；版本插入进入审计记录（全文），随版本数增长。
- **Q4 的衔接**：移除时保留被发布引用的版本的文件；列表状态加"已发布"；发布引用只写一次的版本（不会有 `STILL_REFERENCED` 问题）。
- **上限与描述是否必填**（已确认 Q3-3）：题 100、选项 6、资料 10、图片 20、各长度、时限 30–7 200 秒，需求未给，为本计划的取值。

## 预估

实体、迁移与视图 1 天；文件策略与快照部件 1 天；10 个流程 2 天；模板与消息 0.5 天；测试与场景 1–1.5 天。合计约 5–6 天（不含商家后台页面）。

## 平台缺口（都不阻塞 Q3；按 `backend/quizbuks/CLAUDE.md` 第 2 节在 `1.2/platform` 上补）

1. **单语言 Markdown 文本的格式标记**：文本类型没有 `format`，只有 `I18nText` 有 Markdown；后台把 `intro`、`body` 显示为普通多行文本。需要 `asMarkdown(maxLength)`（Text 增加 `format: plain|markdown`），元数据导出 `format`，后台用与 `I18nText.markdown` 相同的渲染器（不允许原始 HTML，附预览）。Q3 先按多行文本声明；Q4 若在通用后台审核内容就需要它。
2. **场景回放没有"上传文件"步骤**：文件字段要求文件已存在，场景只能为导入保存文件。需要步骤 `upload: {policy, file}`（以场景的操作人经平台上传服务执行，图片照常重新编码，`fileId` 由场景的确定性生成器给出，文件存入该次回放的临时目录）。在此之前带文件的流程只在集成测试中验证。
3. **（Q5 前评估）文件读取按引用行授权**：文件的读取只看策略的一个权限码；需要可选声明"能读到引用该文件的行（经某数据视图的范围）才可读文件"。Q3 不需要。
4. **（新发现）应用的快照路径设置不生效**：`BootAppPlugin.configureTests` 在应用构建脚本的 `jabizApp { … }` 之前就以 `.get()` 读取 `openApiSnapshot` / `publicQueriesSnapshot`，
   得到的是缺省值（平台的 `frontend/openapi/…`）。应用若写快照测试，`-Dopenapi.update-snapshot=true` 会改写平台文件。需要在平台上改为惰性传递（如 `jvmArgumentProviders` 或 `doFirst`）。
