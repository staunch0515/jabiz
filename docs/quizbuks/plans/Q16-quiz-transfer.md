# Q16 Quiz 的导出与导入（带格式版本的 Quiz 文件）

需求方 2026-10-23："给商家的 Quizzes 页面的每个 quiz 增加一个导出按钮。可以把每个 quiz 导出成一个 Json 文件，quiz 中用到的图片也要导出为一个
base64 的数据。在页面增加一个导入按钮，可以从一个满足格式的 json 文件中导入一个 quiz 到系统中。给这个 quiz 文件设定系统版本。系统可以判断这个 json
文件是否还能被导入，以及被导入时应该用哪一版的导入来支持。"确认（2026-10-23）：图片、PDF 与音频都嵌入；导出草稿的当前内容；先 Q15、再 Q16。

## 1. 文件格式（版本 1）

```json
{
  "format": "quizbuks.quiz",
  "formatVersion": 1,
  "exportedAt": "2026-10-23T09:00:00Z",
  "quiz": { "kind": "QUIZ", "title": "…", "intro": "…", "timeLimitSec": 300, "cover": "f1" },
  "materials": [ { "kind": "IMAGES", "title": "…", "description": null, "body": null, "url": null,
                   "pdf": null, "audio": null, "images": [ { "image": "f2", "caption": "…" } ] } ],
  "questions": [ { "stem": "…", "image": "f2", "points": 2, "selection": null,
                   "options": [ { "text": "…", "image": null, "correct": true } ] } ],
  "files": { "f1": { "name": "image.jpg", "type": "image/jpeg", "size": 12345, "data": "<base64>" } }
}
```

- 顺序即数组的顺序；文件以 `files` 的键（`f1`、`f2`…，按首次使用的顺序）引用，同一文件只存一次。文件名是中性名（`image.jpg`），不带商家的原名。
- 不含本系统的编号、所有者、状态、版本、修订号与 AI 标记：导入总是新草稿。
- 商家的 `kind` 为 `QUIZ` 或 `SURVEY`（问卷的题有 `selection`，没有正确选项）。

## 2. 版本

- `QuizFileFormat`：`CURRENT`（导出写的版本，也是读取的目标）与 `OLDEST`（还能读的最旧版本），目前都是 1。
- 读取：`format` 不是 `quizbuks.quiz` → "不是 Quiz 文件"；版本高于 `CURRENT` → "来自更新的 QuizBuks"；低于 `OLDEST` → "太旧"；
  否则从文件的版本起依次经过每个后续版本的升级（`UPGRADES`：从版本 n 到 n+1 的文档变换），再按当前版本读取。结果给出经过的版本（`readers`，如 `[1, 2, 3]`）。
- 改格式时：`CURRENT` 加一，在 `UPGRADES` 中加上从上一版本的升级（只改文档的 map），同时改 `readCurrent` 与导出；只有永久放弃一个版本时才提高 `OLDEST`。
  升级链的机制由单元测试以假设的版本 2、3 验证（`QuizFileTest`）。

## 3. 做了什么

| 部分 | 内容 |
|---|---|
| 导出（商家后台） | Quiz 列表每行"导出"：读草稿（与编辑器相同的数据视图），逐个取文件原件，base64 嵌入，浏览器下载 `<标题>.quizbuks.json`。显示进度；超过 100 MB 先询问。服务器不参与（没有新的接口） |
| `QB_QUIZ_IMPORT_CHECK` | `qb.content.write`，要求已验证、未封禁。输入文件（浏览器去掉了 `data`）。读取并检查：版本；数量与长度（`ContentLimits`）、时限与分值范围、资料只用其种类的内容字段、问卷的选择方式与无正确选项；每个文件都在 `files` 中、类型与大小为其字段的策略所接受。成功时说明格式版本、经过的版本、Quiz 概要与需要上传的文件（键、策略）；否则 422 一次列出全部问题。不写任何业务数据 |
| `QB_QUIZ_IMPORT` | 同样的权限与检查；输入文件与每个键上传后的文件编号。每个文件须由调用者上传、属于该字段的策略（`QB_CONTENT_FILE_NOT_OWN`、`QB_QUIZ_FILE_TYPE`、`QB_QUIZ_FILE_NOT_UPLOADED`）。在一个事务中写出整个草稿（与复制共用 `QuizProcesses.insertContent`），有任何问题即什么都不写 |
| 导入（商家后台） | 列表页"导入"：选择文件 → `QB_QUIZ_IMPORT_CHECK` → 显示概要与格式版本（或全部问题）→ 确认 → 逐个经 `/api/files?policy=…` 上传（图片照常重新编码；上传限流 429 时等待后重试）→ `QB_QUIZ_IMPORT` → 打开新草稿 |
| 限流 | `QB_QUIZ_IMPORT_CHECK` 每人每小时 60 次，`QB_QUIZ_IMPORT` 20 次 |

没有迁移。

## 4. 决定 D-Q16-1：不用平台的导入（`ImportDefinition`）

平台规则（CLAUDE.md、决策 D26）：批量导入只经 `ImportDefinition`（`TEXT` / `XLSX` / `XML` 的行），每行交给手工录入的流程。
Quiz 文件不是行的批量导入：它是一个 Quiz，其中嵌有文件，而平台的导入不能从嵌入的数据创建文件（文件只经上传入口，D18）。
所以 Quiz 文件的导入是 QuizBuks 自己的流程，同复制（`QB_QUIZ_CLONE`）一样一次写出整个草稿；规则仍只有一份（同编辑的限制，`QuizFileChecks` 复用 `ContentLimits`），
文件仍只经上传入口与内容策略，服务器从不读文件的数据。平台没有变化。

## 5. 测试

- `QuizFileTest`（单元）：当前版本的读取与检查、更新与更旧的版本、不是 Quiz 文件、格式错误、升级链（假设的版本 2、3）、各种问题的位置、文件过大、问卷、转成草稿内容。
- `QuizTransferIT`：检查的结果与需要的文件；不支持的版本与全部问题（422）；导入写出整个草稿（题、选项、资料、图片引用新文件）并能保存版本；别人的文件、错策略的文件、缺少的文件与有问题的文件都什么也不写；问卷。
- 商家 Vitest `content/transfer.test.tsx`：导出（键、去重、无本系统编号）、base64 往返、去掉数据、文件名、导入的上传与限流重试、缺数据、列表页的导出下载、导入流程、拒绝时列出原因。
- e2e `transfer.spec.ts`：从列表导出含封面与题图的 Quiz，再导入下载的文件，得到内容相同、文件为新的新草稿。

## 6. 风险与限制

- 文件可能很大（音频 50 MB，base64 增加约三分之一）：浏览器在内存中处理；超过 100 MB 时先询问。
- 上传限流（每人每分钟 30 次）：文件多时导入需要几分钟，页面显示进度。
- 导入中途失败时已上传的文件没有被引用，由平台的孤立文件清理删除。
