# F-C4 发起方包（Q103，格式第 2 版）：实施计划

依据：`docs/quizbuks/first-launch-spec.md` F-C4（以它为准）；`Q103-sponsor-package.md`（已归档，只作参考：§2 的格式即"第 1 版"）、`Q100-profile-package.md`、`Q102-publication-copy-transfer.md`、`Q16-quiz-transfer.md`。
会话 S3，分支 `1.3/quizbuks-fc4-sponsor-package`（自 `1.3/quizbuks` 拉出：已含 F-S2 的图与节点绑定、F-S6 题库与分批导入、F-S8、F-S9 主题词、F-S12 共享视频、F-S13、F-C3）。

**删除的功能：无。** 资料包（Q100）、Quiz 文件（Q16）、发布设置文件（Q102）、分批导入（F-S6）的页面与流程照旧；`QB_QUIZ_IMPORT` 不给 `intoQuizId` 时与以前逐字相同。

## 0. 范围与缺少的依赖

- 一个 PR：格式第 2 版的写出与读入（读第 1 版）、检查页、逐步导入、导入记录 `QbPackageImport`（继续 / 放弃）、"提交全部草稿"、`QB_QUIZ_IMPORT` 的 `intoQuizId`、保留期任务、删除账号、管理系统的只读记录、文档。
- **第 1 版从未实现过**：Q103 只到计划（代码中没有发起方包）。"第 1 版照旧能读"按 Q103 §2 的格式实现读法（没有图、主题词、共享视频），`SponsorPackageTest` / `package.test.ts` 各有第 1 版的样例。
- **题目乱序（F-S7，#573 已合入）**：Quiz 文件第 3 版的 `quiz.shuffleQuestions`（只写固定顺序）与题目的 `difficulty` 由包里的 Quiz 文件原样带上；本项的两条新路径也照做：`exportVersion` 由版本快照的 `questionOrder` 与题目的难度写出，`intoQuizId`（`QuizProcesses.replaceContent`）把文件的顺序设定写进被替换的 Quiz。
- **发起方读本人知识结构图的模板不存在**（F-S2b3b"随 F-S3"，F-S3 未开始）：导出与按节点绑定都需要读，本项加两个只读模板 `qb.sponsor.package-trees` / `qb.sponsor.package-tree-nodes`（只读本人的图，F-S3 可以直接用或另写自己的）。

## 1. 文件格式（`format: quizbuks.sponsor-package`，`version: 2`，一个 JSON）

```json
{
  "format": "quizbuks.sponsor-package", "version": 2, "exportedAt": "…",
  "profile": { …Q100 资料包，原样（含图片）… },
  "trees": [
    { "key": "<源环境的 treeId>", "name": {"ja": …}, "description": {…} | null,
      "nodes": [ { "key": "<源环境的 nodeId>", "name": {…}, "description": {…} | null, "condition": {…条件，节点以键指称…} } ] }
  ],
  "quizzes": [
    { "key": "q1",
      "versions": [ { "key": "q1v2", "label": "v2", "file": { …Q16 Quiz 文件（由版本快照写出）… },
                      "topics": ["…"], "customTopics": ["…"] } ],
      "current": { …Q16 Quiz 文件（当前草稿）… } | null,
      "topics": [ … ], "customTopics": [ … ] }
  ],
  "publications": [
    { "quiz": "q1", "version": "q1v2", "settings": { …Q102 发布设置… },
      "node": { "tree": "<图的键>", "node": "<节点的键>", "name": {…节点名，按名找的后备…} } | null,
      "topics": { "add": [], "remove": [], "customAdd": [], "customRemove": [] } | null,
      "sharedVideo": "<共享视频编号>" | null }
  ]
}
```

- **第 2 版比第 1 版多**：`trees`（节点与条件）、发布的 `node`、Quiz 版本与当前内容的 `topics` / `customTopics`、发布的 `topics`（增删）、发布的 `sharedVideo`。没有乱序开关（§0）。
- **只带内容与设置**：不带状态与审核结果、记录、钱与账本、店员、邮件服务器的密码（资料包本来就不带）、电子奖品的码与共用码、领奖码、邀请名单、资源库、发布的封面与福利购买（Q102 的决定：账户自己的文件与记录）。
- **Quiz**：未移除的 Quiz 都带当前内容；没有结束的发布用到的每个版本也带上（`versions` 按版本号先后）。当前内容与最后一个带上的版本相同（`changedSinceVersion` 为假且最后一个带上的就是最新版本）时 `current` 为 `null`，不再替换。
- **发布**：只带没有结束的（`DRAFT`、`PENDING_REVIEW`、`SCHEDULED`、`LIVE`、`PAUSED`、`FUNDS_SHORT`）。
- **图**：本人未退役的图，带生效的版本（没有生效的带草稿）。节点与图的键是源环境的编号（导入时由服务器发新编号，`QB_TREE_DRAFT_SAVE` 的 `key`）。
- **共享视频**：只带编号（目录中的 `sharedVideoId`）。
- 读包（`package.ts` 的 `readPackage`）只认 `format` 与 `version` 1、2；其他文件、更新的版本、形状不对一律拒绝并说明原因。Quiz 文件、资料包的内容由服务器的检查流程逐个检查（`QB_QUIZ_IMPORT_CHECK`、`QB_PROFILE_IMPORT_CHECK`）。
- 超过 `qb.package.confirm-bytes`（缺省 100 MB）的导出先确认再下载（同 Q16）。

## 2. 规则

- **可导入的账户**：从未提交过发布（本人的发布没有一个 `submittedAt` 有值或状态不是 `DRAFT`）的账户可以全部导入；已有的 Quiz 草稿不阻止（标题相同的在检查页中标出）。提交过发布的账户只能"只导入资料部分"（Q100 的导入，同一套记录）。服务器在 `QB_PACKAGE_IMPORT_START` 中检查（`QB_PACKAGE_SUBMITTED`）。
- **图**：账户有已提交过的图（本人的图有版本生效过或提交过）时不导入图，发布按节点的键绑定到本人图中生效的同一节点；键找不到时按节点的名称（基准语言，完全相同且唯一）找；都找不到的不绑定，列出来。没有已提交的图时，包里的每个图建成新图（`QB_TREE_CREATE`）并保存草稿（`QB_TREE_DRAFT_SAVE`），超过 `qb.tree.sponsor.max-trees` 的不导入、列出，绑定它的发布不绑定。图照常由运营商另一位成员审核（"提交全部草稿"先提交从未生效的图）。
- **Quiz 版本按先后重建**（D-Q103-3）：同一个 Quiz 先以第一个带上的版本的内容新建（`QB_QUIZ_IMPORT`），设主题词（`QB_QUIZ_TOPICS_SET`），保存版本（`QB_QUIZ_PUBLISH_VERSION`）；再以 `intoQuizId` 整体换成下一个版本的内容……最后换成当前内容。发布挂到"内容相同"的那个新版本。
- **大题库分批**：一份内容的题目多于 `qb.content.import.batch-size` 时，第一步只带资料与前一批题目，其余各批经 `QB_QUIZ_IMPORT_QUESTIONS` 依次加入（F-S6 的分批），每批是一步；总数仍受 `qb.content.max-questions` 限制（检查页列出）。
- **发布**：导入成草稿（`QB_PUBLICATION_SAVE`），开始时间为导入时的下一个整点、天数同原来；再写受众条件（`QB_PUBLICATION_AUDIENCE_SAVE`）、主题词的增删（`QB_PUBLICATION_TOPICS_SET`）、共享视频（`QB_PUBLICATION_SHARED_VIDEO_SET`，只在本环境目录中有这个编号且已共享时；否则列为"请重新选择"）。一个发布的这几步在一个事务中。
- **导入以后一切照常审核**：入驻、主页、图、发布都没有免审的特例；导入只用各页本来的流程（作为子流程），规则与手填相同，审计由平台照常记录。
- **导入记录 `QbPackageImport`**（普通表）：发起方、包的哈希（SHA-256，前端算）、格式版本、方式（`FULL` / `PROFILE`）、状态（`RUNNING` / `DONE` / `ABANDONED`）、总步数、已完成步数、已完成的步骤与结果（JSON：步骤键 → 生成的编号）、开始 / 更新 / 结束时间。
  - 每个发起方同时至多一个 `RUNNING`（部分唯一索引兜底）。用同一个文件再开始即**继续**：返回已完成的步骤与结果，前端跳过它们；别的文件被拒（`QB_PACKAGE_IMPORT_OPEN`），须先继续完或放弃。
  - **每一步在 `QB_PACKAGE_IMPORT_STEP` 中以子流程执行，与记录在同一个事务中**：步骤做了就一定记上，没记上就一定没做，所以继续不会生成两份（连接在提交后断开也一样）；已记的步骤再送来直接返回记下的结果。最后一步记上即 `DONE`。
  - **放弃** `QB_PACKAGE_IMPORT_ABANDON`：以现有流程删除这次导入生成的草稿：图退役（`QB_TREE_RETIRE`，图没有删除流程）、仍是草稿的发布删除（`QB_PUBLICATION_DELETE`）、Quiz 移除（`QB_QUIZ_DELETE`；已被提交的发布用着的不移除、列出）；资料部分不还原（它不是草稿，Q100 的导入与手填一样即时生效），检查页事先说明。记录留下（`ABANDONED`），到保留期删除。
- **"提交全部草稿"**（发布列表与导入完成的对话框）：先逐个提交本人从未生效的图的草稿（`QB_TREE_SUBMIT`），再逐个提交本人的草稿发布（`QB_PUBLICATION_SUBMIT`），有问题的跳过并列出原因（服务器的原文，包括资金不足等错误码）；审核照常逐个进行。

## 3. 改动

| 位置 | 改动 |
|---|---|
| `content.transfer.QuizTransferProcesses` | `ImportInput` 加可选的 `intoQuizId`：取 Quiz 锁、经商家视图读（别人的、已移除的 404）、有任何发布（含草稿）即 422 `QB_QUIZ_IMPORT_HAS_PUBLICATIONS`、种类不同 422 `QB_QUIZ_KIND_FIXED`；整体替换（题目、选项、资料、图片、译文删除后按文件插入，标题与简介等换成文件的，`revision + 1`，资源库的使用记录随删除的部分一起忘掉，同移除）。不给时与以前相同 |
| `content.QuizProcesses` | 把移除时删除各部分的代码抽成 `clearContent`（移除与替换共用），加 `loadForReplace` / `replaceContent` |
| `tree.TreeConfig` / `QbTrees` | 数据视图 `urn:jabiz:dataset:sponsor:QbTree`（本人的图，只读），供发起方包的模板经它读本人的版本与节点 |
| `application.yml` | 限流：`QB_PACKAGE_IMPORT_START` 30/时、`_STEP` 3000/时、`_ABANDON` 30/时；`QB_QUIZ_IMPORT_CHECK` 60 → 300/时（检查页逐个检查包里的 Quiz 文件） |
| `sponsorpackage`（新包） | `QbPackageImports`（实体、数据视图名、锁名）、`PackageImportRules`（纯 Java：哈希与步骤键的格式、结果的读写、可否全部导入、保留期）、`PackageImportCodes`、`PackageImportProcesses`（`QB_PACKAGE_IMPORT_START` / `_STEP` / `_ABANDON` / `_EXPIRE`）、`PackageImportConfig`（实体、两个数据视图、流程、保留期任务 `qb.package-import-expire`） |
| 迁移 `V102__quizbuks_package_import.sql`（`1.3/quizbuks` 最大 V101 之后，先绿先合） | 新表 `qb_package_import`（普通表），部分唯一索引（每个发起方一个 `RUNNING`），按发起方与开始时间、按更新时间的索引 |
| 模板 | `qb.sponsor.package-state`（是否提交过发布、是否有提交过的图、确认的字节数、图的上限、未结束的导入）、`qb.sponsor.package-trees` / `qb.sponsor.package-tree-nodes`（本人的图与节点）、`qb.system.package-imports-expired`（保留期任务的项） |
| `setup.QbParams` | 加在最后（普通参数）：`qb.package.confirm-bytes`（104857600）、`qb.package.import-retention-days`（90） |
| `QbPermissions` | `qb.package.system`（保留期任务，不授予任何角色）；发起方的三个流程用 `qb.content.write`（子流程各自照常检查自己的权限） |
| `operator.OperatorScopes` | `QbPackageImport` 按 `sponsorId` |
| `identity.SponsorErasure` | 删除账号时删除本人的导入记录 |
| 消息 | 三语：错误码、实体与字段、流程、参数、模板的列 |
| 发起方页面 | `profile/package.ts`（写包、读包、导入的计划、逐步导入、继续、放弃、提交全部草稿）、`profile/SponsorPackage.tsx`（概况页的卡片：导出、检查页、进度、继续 / 放弃）、`publishing/SubmitAllDrafts.tsx`（发布列表的按钮与结果）、`content/transfer.ts` 加 `exportVersion`（版本快照 → Quiz 文件）、`profile/transfer.ts` 的导入可经导入记录执行；三语文案 |
| 管理系统扩展 | 发起方详情（`SponsorReviewPage`）加只读的"发起方包的导入记录"（`SponsorPackageImports.tsx`，`qb.admin.publication.read`，运营商只看到自己的发起方） |
| Quiz 文件的导出（`content/QuizTransfer.tsx`、`content/transfer.ts` 的 `readConfirmBytes`） | 超过 `qb.package.confirm-bytes` 先确认（以前是固定的 100 MB，缺省值相同） |
| 文档 | 本计划；`operations.md` §7.33"发起方包与测试期的导回"；帮助 `docs/quizbuks/help/sponsor-package.md`（三语）；`backend/quizbuks/CLAUDE.md` 一条；进度表 |

## 4. 语句数

- 答题与发布的路径不变（`AttemptStatementBudgetIT`、`StatementBudgetIT` 不变）。
- `QB_PACKAGE_IMPORT_STEP`：取本人的导入锁、读记录（一条），然后是子流程本来的语句，最后更新记录（缓冲，提交前写出）。不是热门路径（一个发起方一次导入）。
- `intoQuizId`：比新建多读 Quiz 与它的各部分（与移除相同的读法）和本 Quiz 的发布（一条，`quiz_id` 有索引）。

## 5. 测试

- 单元：`SponsorPackageRulesTest`（步骤键与哈希的格式、结果的读写与合并、保留期的读法、可否全部导入）。
- `PackageImportIT`（连接 PostgreSQL）：
  - 开始：从未提交过发布、有 Quiz 草稿的账户可以全部导入；提交过发布的只能 `PROFILE`（422 `QB_PACKAGE_SUBMITTED`）；同一个哈希再开始即继续（返回已完成的步骤），另一个哈希被拒；
  - 步骤：资料、图（新建与保存草稿，节点得到新编号）、Quiz 内容 → 主题词 → 保存版本 → `intoQuizId` 换成下一版本 → 分批加题 → 当前内容、发布（草稿、条件、主题词、绑定到导入的节点）按顺序；同一步再送来不再执行（不生成两份）；最后一步记上即 `DONE`；
  - 图导入为草稿并经"提交"进入运营商的审核；已有提交过的图时不建新图；
  - 放弃：图退役、草稿发布删除、Quiz 移除，记录为 `ABANDONED`；别人的导入 404；
  - `intoQuizId`：本人的、没有发布的可以（内容整体替换、版本照旧）；有发布的 422；别人的 404；
  - 保留期任务删除过期的记录、留下未过期的；删除账号删除记录；运营商只看到自己发起方的记录。
- `SetupIT` 的参数表、场景 `setup` 的 `paramsCreated`，场景快照按 `-Dscenario.update-snapshots=true` 更新并核对差异。
- 前端（发起方页面 Vitest）：`profile/package.test.ts`（写出只带没结束的发布与它们用到的版本、不带状态与秘密；读回第 1 版与第 2 版；拒绝其他文件与更新的版本；导入的步骤顺序：版本先后、当前与最后版本相同时不再替换、大题库分批、发布在最后；按节点键与名称绑定、绑定不上的列出；共享视频找不到的列出；继续时跳过已完成的步骤）、`profile/SponsorPackage.test.tsx`（检查页、进度、继续与放弃、只导入资料部分，axe 亮暗两种外观）、`publishing` 的"提交全部草稿"（跳过并列出原因，axe）。
- 管理系统扩展：`packageImports.test.tsx`（记录列表，axe）。
- 端到端 `quizbuks-web/e2e/tests/sponsor-package.spec.ts`：一个发起方（资料、图、Quiz 两个版本、两个发布其一绑定节点）导出 → 新发起方导入 → 入驻申请已填、图（草稿）、Quiz 与两个版本、发布草稿与绑定都在 → 提交全部草稿：图与未绑定的发布进入审核，绑定的发布列出原因（图尚未生效）。

## 6. 运维

见 `operations.md` §7.33。部署后运行一次 `QB_SETUP`（建两个普通参数）。任务 `qb.package-import-expire`（每天 UTC 18:53，逐项删除）。没有新的环境变量。

## 7. 决定（需转达总控 / 需求方，可改）

1. **第 1 版按 Q103 §2 实现读法**：第 1 版从未有代码写出，读法照计划的格式；第 2 版是唯一写出的版本。
2. **乱序开关随 Quiz 文件**：F-S7 合入以后，包的格式不变（第 2 版），顺序设定与难度在 Quiz 文件中；导出版本与替换内容两条路径都带上。
3. **主题词放在包里，不放进 Quiz 文件**：版本的主题词随版本、当前的随当前内容，导入时以 `QB_QUIZ_TOPICS_SET` 设上，Quiz 文件仍是第 3 版。
4. **节点的键是源环境的节点编号**；已有图时先按键、再按基准语言的节点名（完全相同且唯一）找生效的节点；都找不到的不绑定、列出。
5. **导出图的生效版本**（没有生效的导出草稿），退役的图不导出。
6. **"提交过的图"** = 本人的图有版本生效过或提交过（`currentVersionId` 有值，或任何版本的 `submittedAt` 有值）。
7. **图进入审核经"提交全部草稿"**：它先提交本人从未生效的图的草稿，再提交发布；绑定在尚未生效的图上的发布照常被拒（跳过并列出），图生效后再按一次即可。
8. **每一步与导入记录在同一个事务中**（`QB_PACKAGE_IMPORT_STEP` 以子流程执行现有流程），继续从不重做；每个发起方同时一个未结束的导入。
9. **放弃不还原资料部分**，图以退役代替删除，已被提交的发布用着的 Quiz 不移除。
10. **"只导入资料部分"也记一条导入记录**（方式 `PROFILE`，一步）。
11. **保留期从最后一次更新起算**（未结束的也一样）：过期即删除记录，已生成的草稿留下，成为普通草稿。
12. **"提交过发布"** = 本人有发布的 `submittedAt` 有值或状态不是 `DRAFT`。
13. **发布不带封面与福利购买**（照 Q102）；开始时间为导入时的下一个整点、天数同原来（D-Q103-4）。
14. **共享视频按编号认**：目标环境的共享目录中有这个编号且已共享才挂上。
15. **发起方的流程用 `qb.content.write`**，不新加授予给角色的权限；保留期任务用新权限 `qb.package.system`（不授予任何角色）。
16. **`intoQuizId` 有任何发布（含草稿）即拒**：规格"没有发布的 Quiz"；导入时 Quiz 的版本都在发布之前生成，所以不受影响。
17. **确认的字节数是一个参数，两处共用**：发起方包与 Quiz 文件（Q16）的导出都读 `qb.package.confirm-bytes`（经模板 `qb.sponsor.package-state`；缺省 100 MB 与以前相同，读不到时用缺省值），代码中不再有这个数字的常数（只留作读不到时的缺省）。
18. **一次导入记下每批题数**：继续时沿用开始时的批大小（参数中途改了也不会把题目分错批）。
19. **检查页逐个检查每个 Quiz 文件**（`QB_QUIZ_IMPORT_CHECK`，它的限流放宽到每人每小时 300 次），步骤中的上传按检查的结果只传这一批用到的文件。
20. **新建的图的条件中指向平台模版节点的编号**按原样带（跨环境多半对不上）：保存草稿时由规则列为图的问题，提交前由发起方改正；直营发起方绑定在平台图上的发布不按键绑定（列为"不绑定"）。

21. **资料部分中以链接给出的图片（F-C6 合入后）**：检查页列出这些链接；导入的资料一步先由服务器逐张下载（`downloadLink`，D75），下载不了的保留原来的图片（检查页已说明，发起方导入后在页面上设置），不让整个导入失败。系统导出的包从不含链接，只有手写或其他 AI 生成的资料部分才有。

## 8. 以后（需要别人补的与之后的事）

1. F-S3（知识结构图的界面）可以直接用 `qb.sponsor.package-trees` / `-tree-nodes`，或换成自己的模板（届时一并改 `package.ts`）。
2. 不做：一次导入多个发起方（D-Q103-7）；带记录的完整备份。
