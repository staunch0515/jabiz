# F-S9 主题词与"严格按喜欢的主题"：实施计划

依据：`docs/quizbuks/first-launch-spec.md` F-S9（以它为准）；`Q112-video-push.md` §0-7、§0-10、§8.2 只作参考。
会话 S3，分支 `1.3/quizbuks-fs9-topics`，叠在 F-S10（`1.3/quizbuks-fs10-shorts`）之上，F-S10 又叠在 F-S11（#505）之上：严格模式要同时过滤首页与短视频一排（都是模板 `qb.app.feed`）。

## 0. 范围

一个 PR 做完 F-S9 中不依赖 F-S6、F-S13 的部分。

- **搜索（F-S6，S2，未合入）**：规格"自定义词只用于搜索"。`origin/1.3/quizbuks` 现在只有首页的 `q`（按发布名与 Quiz 标题的文字），没有 F-S6 的搜索。本条只**保存**自定义词（Quiz、版本、发布的增删、卡片 `customTopics`），不做搜索。**F-S6 合入后接上**：搜索读卡片的 `customTopics`（JSON 数组）与词表名称（`qb_topic.name`，三语），不用改本条的任何写入。
- **推送（F-S13，未开始）**：规格"打开严格模式后……推送只出现与喜欢的主题有交集的发布"。推送还没有；F-S13 做时把 `qb.app.feed` 中标着 F-S9 的那段条件照抄进推送的选取（`TopicRules.strictAllows` 是唯一定义，照 `TopicIT` 加比较测试）。
- **"我的信息"**：App 现在没有叫"我的信息"的页；最接近的是账户的"关于我"（`/account/details`，Q47 的匹配资料）。喜欢的主题与严格模式放在那一页的下方（新的一节"喜欢的主题"），菜单的提示加"喜欢的主题"。F-F4（Q108 的框架）换页面时整节搬过去。

**删除的功能：无。** 没有打开严格模式的学者，首页与短视频一排与以前完全相同；没有主题词的 Quiz 与发布照常保存、提交与上线。

## 1. 规则（一处定义：`topic.TopicRules`）

- **词表**（`QbTopic`，普通表）：代码（主键，`[a-z0-9]+(-[a-z0-9]+)*`，2–40 字，不能改）+ 三语名称（`I18nText`，zh、ja、en 都必填）+ 是否可选 + 合并到哪个词。总部维护，新增词不改代码。**不删除**：
  - 停用：不能再选；已选的（Quiz、发布、学者）照常显示，严格模式与统计照常算它。
  - 合并：被合并的词停用并记下目标；以后在严格模式与统计中算作目标词，显示目标词的名称；以前合并到它的词一起改为目标词，所以合并总是一步（`TopicRules.resolve`）。只能合并到可选的词，合并后不再改变。
- **Quiz 的主题词**：词表中的词（代码，按选择的顺序）+ 自定义词（规范化：NFC、去掉前后空白、连续空白变一个，不分大小写去重），合计至多 `qb.topic.max-per-quiz`（10，硬上限 30）；自定义词至多 `qb.topic.custom-max-length`（30，硬上限 60）字。新选的词必须可选；已有的词停用或合并后照常保留。
- **冻结**：保存版本时复制到版本的列（`topics`、`customTopics`；不进快照，内容哈希不变；只改主题词也算新版本）。发布只存对版本的增删（`TopicRules.Changes`：`add` / `remove` / `customAdd` / `customRemove`，按版本规范化：只存版本没有的增、版本有的删），草稿时可改，提交即冻结；上线与版本切换时 `TopicRules.effective(版本的, 增删)` 写到卡片（切换版本时同样的增删作用在新版本上）。
- **严格模式**（学者自己的设置 `likedTopics`、`strictTopics`）：打开时首页与短视频一排只列卡片的词表中的词与喜欢的主题有交集的发布，两边的词都先按合并换成目标词；不在词表中的代码、自定义词、没有词的卡片都不算交集（只有自定义词的发布不进严格模式的结果）。打开需要至少一个喜欢的主题，严格模式开着时不能把喜欢的主题全部去掉；关掉随时可以。指定邮箱的邀请本来不进首页，对话不在首页，都不受影响。
- **统计**：各词的发布数（全部卡片、进行中）照给；喜欢的人数与其中打开严格模式的人数只在不少于最小分组 k（`qb.stats.min-group`，与分析的模板同一段 SQL：`greatest(3, … 5)`）时给出。合并的词的数字算在目标词上。只算词表中的词。

## 2. 改动

| 位置 | 改动 |
|---|---|
| `topic`（新包） | `QbTopics`（实体）、`TopicRules`（纯 Java 规则）、`TopicCodes`、`TopicProcesses`（总部的五个流程）、`TopicSteps`（读参数与所选的词）、`TopicCheck`（启动检查）、`TopicConfig` |
| `content.QbContent` | `QbQuiz.topics` / `customTopics`（`processOnly`）；`QbQuizVersion.topics` / `customTopics`（不可变） |
| `content.QuizTopics`（新） | `QB_QUIZ_TOPICS_SET`（锁外读参数与所选的词，再取 Quiz 锁、核对修订号，给 Quiz 新修订号） |
| `content.QuizProcesses` | 保存版本时复制主题词，"没有变化"的判断含主题词；复制 Quiz（草稿或某个版本）带上主题词 |
| `publishing.QbPublications` | 发布 `topicChanges`（`processOnly`）；卡片 `topics` / `customTopics` |
| `publishing.PublicationTopics`（新） | `QB_PUBLICATION_TOPICS_SET`（只限草稿，发布锁内、核对修订号，需要已选版本：`QB_PUBLICATION_TOPICS_NO_VERSION`）；`writeTo` 写卡片 |
| `PublicationEditing.cardOf`、`PublicationVersions.make` | 上线与版本切换时写卡片的主题词 |
| `PublicationProcesses.copy` | 复制发布带上 `topicChanges` |
| `settings.QbSettings` / `SettingsProcesses` | `likedTopics`、`strictTopics`；保存时核对新喜欢的词可选、至多 50 个、严格模式需要喜欢的主题 |
| `identity.SponsorErasure` | 设置的抹除遮蔽 `likedTopics`（设置行本来就随账户删除） |
| `setup.QbParams` | `qb.topic.max-per-quiz`（10）、`qb.topic.custom-max-length`（30），不受控，加在最后 |
| `QbPermissions` / `QbRoles` | `qb.admin.topic.read` / `.write`，给内容管理员（超级管理员经内容管理员的列表也有）；运营商没有 |
| 迁移 `V84__quizbuks_topics.sql`（临时编号：V80–V83 被其他分支占用；合并前按 `1.3/quizbuks` 的最大号 + 1 重编） | 新表 `qb_topic`；只追加表 `qb_quiz_version`、`qb_quiz_version_version`、`qb_publication_version` 只加列；卡片与 `qb_user_settings` 加列 |
| 模板 | `qb.app.feed`：读本人的设置（`me:QbUserSettings`）与词表，严格模式的条件；`qb.app.topics`（`qb.play`）、`qb.sponsor.topics`（`qb.content.write`）：词表；`qb.admin.topics`（`qb.admin.topic.read`）：词表与统计；`qb.sponsor.quiz-versions` 多返回版本的主题词 |
| 消息 | 三语：错误码、实体与字段、流程、参数、模板的列 |
| App | 账户"关于我"的"喜欢的主题"一节（`pages/LikedTopics.tsx`：词表中可选的词按界面语言、勾选即保存；以前选过、已不再提供的照常列出；严格模式开关与说明）；首页在严格模式时提示"只显示你喜欢的主题"并链接到设置；首页与短视频的查询键含设置；共用的 `qb/topics.ts`（两个 SPA 一致）与 `qb/api.ts` 的设置 |
| 发起方页面 | Quiz 编辑页"Quiz"一栏的"主题词"（`topics/QuizTopicsField.tsx`，修改即保存）；发布向导第一步的"主题词"（`topics/PublicationTopicsField.tsx`：版本的词可去掉、再用，加词表中的词或自定义词；随草稿保存，在草稿、条件、前置、认证之后最后保存）；词表按界面语言、停用与合并的词不能选 |
| 管理系统扩展 | 新页 `/qb/topics`（菜单"主题词"，平台管理系统的平台组，`qb.admin.topic.read`）：三语名称、状态、发布数、喜欢的人数（少于 k 显示"少于 k"），新增、改名、停用 / 重新启用、合并都链接到平台的流程页 |
| 文档 | 本计划；帮助 `docs/quizbuks/help/topics.md`（三语）；`operations.md` §7.26；`backend/quizbuks/CLAUDE.md` 一条；上线检查清单一行；进度表 |

锁：词的修改取 `qb.topic:<代码>`（合并按代码顺序取两个词的锁）；Quiz 与发布的主题词照内容与发布的锁序；设置照 `qb.settings:<用户>`。词表与参数在 Quiz 锁与发布锁之外读。都不在答题的锁序中。

## 3. 语句数

- **答题路径不变**（`AttemptStatementBudgetIT` 不变）：开始、提交、超时都不读主题词、不读设置；卡片多两列由上线与版本切换原有的写入带上。规格的"严格模式只作用于推送"即此意：严格模式只影响列表（首页、短视频，以后的推送），不影响能否开始答题。
- 首页模板多两个相关子查询（本人设置一行、词表按主键）；不是答题路径。
- 上线、版本切换、复制：不多语句（卡片与发布原有的写入多几列）。`QB_QUIZ_TOPICS_SET` / `QB_PUBLICATION_TOPICS_SET` / `QB_SETTINGS_SAVE`：读参数一条（`QB_SETTINGS_SAVE` 不读）、读所选的词一条（没有选词时不发语句）。

## 4. 测试

- 单元：`TopicRulesTest`（代码格式、列的读写与规范化、继承与增删 `effective` / `against`、严格模式含合并与停用、选择与上限、启动检查的问题）。
- `TopicIT`：词表的维护（新增、格式、重复、三语必填、改名、停用 / 启用、合并与连带、合并后不能改、权限、审计）；Quiz 的主题词（不可选的词、已有的停用词保留、参数上限、写进版本、只改主题词也是新版本且内容哈希不变）、发布的增删（规范化、停用词不能新加、别人的草稿 404、提交后冻结、卡片、复制、版本切换按新版本重算）、复制 Quiz 带上主题词；**严格模式的模板比较测试**（几组喜欢的主题 × 开 / 关，首页结果与 `TopicRules.strictAllows` 逐一相同；只有自定义词与没有词的发布不在严格模式的结果中；合并的词按目标词）；短视频一排同样过滤；设置的校验（需要喜欢的主题、不可选的词、停用后保留、全部去掉被拒、随时可以关掉）；统计的最小分组与合并；学者与发起方读词表；启动检查（缺少语言名称的词报错）。
- `TakerErasureIT.anErasedTakersLikedTopicsGo`：删除账号后设置行没有了，审计中看不到喜欢的主题，别人的照旧。
- `SetupIT` 的参数表、场景 `setup` 的 `paramsCreated`；场景快照按 `-Dscenario.update-snapshots=true` 更新并核对差异。
- 前端：App `qb/topics.test.ts`（与发起方页面相同）、`pages/topics.test.tsx`（可选的词、勾选即保存、严格模式需要喜欢的主题、已不再提供的词、关掉、"关于我"中的一节、首页提示、axe 亮暗）；发起方页面 `topics/topics.test.tsx`（Quiz 的主题词按界面语言、修改即保存与修订号、只能选可选的词、上限、发布的继承与增删 `effectiveTopics`、axe 亮暗）；管理系统扩展 `topics.test.tsx`（列表、三语、状态、发布数、少于 k、流程页链接、只读、axe）。

## 5. 运维与部署

见 `operations.md` §7.26。部署后运行一次 `QB_SETUP`（建两个普通参数），总部在"主题词"页建首批词。迁移之前上线的卡片没有主题词，在严格模式中不出现，直到切换版本（新上线的照常）。

## 6. 以后

- F-S6：自定义词与词表名称接进搜索（第 0 节）。
- F-S13：推送套用严格模式的条件（第 0 节）。
- F-F4：喜欢的主题一节搬到新框架的"我的信息"。
- 发起方包（`QuizFileFormat`，F-C4）与 AI 生成的 Quiz 暂不带主题词：导入或生成后在编辑页选。
- 规格"不做"：主题词的推荐算法。

## 7. 风险

- 词表很大时，App 与发起方页面一次读至多 500 个词（首次上线足够）；更多时改成按名称搜索。
- 调低 `qb.topic.max-per-quiz` 后，超过新上限的 Quiz 与草稿下次修改时要先减到上限以内（已提交的不受影响）。
- 严格模式下学者可能看到很少的发布：首页上方有提示与更改的链接，随时可以关掉。

## 8. 待确认

- 统计中"发布数"按全部卡片与进行中两种给，不受最小分组约束（发布不是个人数据）；人数受最小分组约束。需求方如要求发布数也按最小分组，改模板一处。
- 严格模式开着时不能把喜欢的主题全部去掉（要先关掉）；需求方如希望去掉最后一个时自动关掉，改 `SettingsProcesses` 一处。
