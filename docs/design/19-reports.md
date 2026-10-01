# 19 报表：时点运行、导出与存档

> 发票、确认书这类多区块的**单据**不是报表：见 22（决策 D30）。单据共用本文的时点固定、规范化、内容哈希与数据范围，但存档 PDF 原样字节，重印不重新排版。

SQL 模板（05）之上的三项通用能力（ROADMAP 阶段 14d，决策 D25）：按指定时点运行模板（14d-1）、把结果导出为 CSV / XLSX / PDF（14d-2）、
签发报表并在以后原样重现（14d-3）。任何应用的"报表"都是声明了 `report` 的 SQL 模板，平台不另有报表定义。

## 1. 总览

| 能力 | 做什么 | 阶段 |
|---|---|---|
| 时点运行 | 模板中的时态实体按指定的生效时点（`asOf`）与记录时点（`knownAt`）读取；模板版本 | 14d-1 |
| 模板目录与"报表"页面 | `GET /api/meta/queries`；后台 `/reports` 运行报表 | 14d-1 |
| 导出 | CSV（防公式注入）、XLSX（数值单元格）、PDF（页眉、页码、确定性输出） | 14d-2 |
| 存档 | 签发（参数、时点、模板版本、结果、内容哈希）、原样重现、按数据核对、取代 | 14d-3 |

## 2. 时点运行（14d-1）

### 2.1 运行时点

- 模板中每个时态实体的占位符 `{{Entity}}` 展开为 04 §5.1 的子查询：`effect_start_time <= :__asOf`，给出记录时点时另加
  `created_time <= :__knownAt`，按主键取最新版本、排除墓碑（`QueryCompiler.templateExpression`，决策 D3、D10）。
  非时态实体不受时点影响。
- 时点的来源（只取其一）：
  1. **模板头部 `timeSlice`**（2.2）：由已声明的参数给出；
  2. **请求字段** `asOf`、`knownAt`（`POST /api/queries/{id}`，与数据视图查询接口 03 §2.5 相同）；
  3. 都没有：生效时点为 `Clock` 的现在，记录时点不限（即此前的行为）。
- 给出了时点（任一个）时，模板的每个**时态**实体的数据视图都必须允许时间旅行（`allowTimeTravel`，默认允许），否则 400 `TIME_TRAVEL_NOT_ALLOWED`。
- 声明了 `timeSlice` 的模板再在请求中给出 `asOf` / `knownAt` → 400 `INVALID_VALUE`（时点只能有一个来源）。
- 公开模板（15）不接受时点：公开接口没有这两个参数，公开模板也不能声明 `timeSlice`（启动检查）。
- 流程中：`RunTemplate.at(模板, 参数, asOf, knownAt, 目标键)`，时点由上下文计算（可为 null）。场景的 `query` 期望可写 `asOf`、`knownAt`。

### 2.2 头部 `timeSlice`

```yaml
params:
  asOf:    { like: LedgerTransaction.bookingTime, required: true }
  knownAt: { like: LedgerTransaction.bookingTime }
timeSlice: { knownAt: knownAt }
```

- `timeSlice: {asOf: <参数>, knownAt: <参数>}`，两项都可省略其一。映射的参数必须已声明、不是列表、语义类型为 `temporal`（启动检查）。
- 参数值即运行时点：`asOf` 参数为空时取现在，`knownAt` 参数为空时不限。模板正文仍可照常使用这些参数（例如账本按过账时间筛选）。
- 用途：业务参数本身就是"当时所知"的时点时，让模板中的**所有**时态实体（包括主数据）按同一时点读取，重现当时的报表。

**账本模板只映射 `knownAt`**：账本交易的生效时间就是它的记录时间（过账时写入，不设 `effectiveAt`），过账日期是另一列 `bookingTime`。
如果把 `asOf`（过账日期）映射为生效时点，"2 月 3 日过账、2 月 20 日记录"的倒签分录在 `asOf` 2 月 5 日时会被隐藏，这与"按当时所知"的含义相反。
因此账本的 `asOf` 只作为过账日期的筛选条件，`knownAt` 同时决定交易、分录与科目（名称、上下级）按哪个时点所知读取。

### 2.3 模板版本

- `AdvancedQueryDefinition.version()`：`.sql` 文件为整个文件（头部 + 正文，去掉 BOM、换行统一为 `\n`）的 SHA-256 十六进制；
  Java DSL 定义为其规范描述（id、实体、视图、参数、结果、列表、权限、正文）的 SHA-256。
- 模板的任何修改（包括说明文字）都产生新版本。存档（第 5 节）记录版本与当时的全文。

## 3. 模板目录与"报表"页面（14d-1）

### 3.1 头部 `report`

```yaml
report:
  period: { from: from, to: asOf }
  landscape: true
```

- 声明了 `report` 的模板是"报表"：出现在后台"报表"页面，导出与签发时用于页眉。未声明的模板照常可以运行与导出，只是不列在页面上。
- `period`：哪两个参数构成报表期间（两项都可省略其一，必须是已声明的 `temporal` 或 `date` 参数；`timeSlice` 只能是 `temporal`）；用于页眉的"期间"。
- `landscape`：PDF 用横向版面（14d-2）。
- 标题与列名：消息资源 `query.<id>`（标题）、`query.<id>.<结果列>`（列名）；列没有自己的文案且有 `from: 实体.字段` 时用字段的显示名，
  都没有时用列名。报表模板缺少所选语言的标题 → 启动检查 `MESSAGES` 报错（`ReportMessagesCheck`，只查应用所选的语言，12 §10）。

### 3.2 目录接口

`GET /api/meta/queries`：当前用户有权运行的模板（公开模板除外，它们有自己的目录 15 §7）。每项包括：

| 字段 | 说明 |
|---|---|
| `id`、`title`、`description` | 标题按请求语言，缺省为 id |
| `version` | 模板版本（2.3） |
| `params` | 参数的 JSON Schema（与流程输入的 schema 同一生成方式：类型、格式、枚举、必填） |
| `results` | 结果列：名称、显示名、语义类型（02 §8 的导出形式） |
| `filters`、`sorts`、`defaultSort` | 外层筛选与排序的白名单 |
| `timeSlice` | 映射的参数；没有映射时为 null |
| `timeTravel` | 是否接受请求中的 `asOf` / `knownAt`（有时态实体、全部允许时间旅行、未声明 `timeSlice`） |
| `report` | 头部 `report`，未声明时为 null |

权限判断与执行接口相同（`Permissions.requireAll`）；目录只是便利，执行时服务端照样检查。

### 3.3 后台页面

- `/reports`：列出目录中声明了 `report` 的模板，按 id 的第一段分组。菜单项"报表"只在有这样的模板时显示。
- `/reports/run?id=<模板>`（模板 id 含点号，服务端会把路径中的点号当作文件名，故 id 放在查询参数中）：参数表单（由参数 schema 生成，与流程表单相同）；模板接受请求时点时显示"生效时点 / 记录时点"选择；
  结果表格（分页、按白名单筛选与排序，金额按区域格式、负数带括号，12 §10）。

## 4. 导出（14d-2）

- `POST /api/queries/{id}/export?format=csv|xlsx|pdf`，请求体与运行相同（参数、时点、筛选、排序），分页字段被忽略；权限与运行相同；
  格式不认识 → 400 `INVALID_VALUE`（字段 `format`）。响应带 `Content-Disposition: attachment`，文件名 `<模板 id>-<yyyyMMdd-HHmmss>.<扩展名>`
  （运行时间按报表时区；id 中字母、数字、`.`、`-`、`_` 以外的字符换成 `_`）。后台报表页的"导出"按当前参数、时点、筛选与排序导出全部行。
- 行数上限 `jabiz.reports.export.max-rows`（默认 100000）：平台多取一行判断，超过 → 422 `REPORT_TOO_LARGE`（参数 `limit`），不截断。
  它代替数据视图的 `maxQueryBatchSize`（后者只限分页浏览，`AdvancedQueryExecutor.all`）。结果在内存中整体写出（有上限），写出在 `boundedElastic` 上进行。
- 中间模型 core `com.jabiz.report.ReportDocument`（模板与版本、标题、公司、期间、参数、运行时间、记录时点、横向、列、行）：
  标题与列名同目录接口（`QueryTexts`），期间取 `report.period` 映射的参数，记录时点为这次运行实际的时点（未给出时即运行时间）；
  请求或 `timeSlice` 给出了生效时点时，页眉写明"生效时点"（否则与当前报表无法区分）。
  页眉页脚的固定文字在消息 `report.period`、`report.runAt`、`report.asOf`、`report.knownAt`、`report.version`、`report.page`，语言为请求的语言。
- 观测 `jabiz.query.export`，标签只有模板名、格式与结果（13）。

| 格式 | 写出器 | 内容 |
|---|---|---|
| CSV | core `CsvReportWriter` | RFC 4180，UTF-8 带 BOM，CRLF；首行为列名，无页眉；金额按列的小数位写出（`300` → `300.00`，多余的位数保留、不舍入），不分组；时间为 ISO-8601（UTC）。**防公式注入**：以 `=` `+` `-` `@`、制表符、回车开头的文本前加 `'`（数值不受影响） |
| XLSX | runtime `XlsxReportWriter`（fastexcel） | 前几行为标题、公司、期间、运行时间、记录时点、参数、版本；列名行加粗并冻结；`monetary` / `numeric` 为数值单元格（`#,##0.00;(#,##0.00)`，小数位按类型），时间为报表时区的日期单元格；文本一律为文本单元格，不会成为公式 |
| PDF | runtime `PdfReportWriter`（Apache PDFBox） | A4（`report.landscape` 时横向）；每页页眉：公司、运行时间、标题、期间、生效时点（给出时）、参数；每页重复列名；页脚：记录时点、模板版本前 12 位、"第 n 页，共 m 页"；金额右对齐、括号表示负数；列宽按内容，放不下时窄列（金额、日期、代码）保持原宽，最宽的列平分剩余宽度，过长的文本以"…"截断；列多到连最小宽度都放不下时一律按比例缩小，不越出页面 |

- **确定性**：PDF 的创建与修改时间、文档 ID 取自运行时间，嵌入的字体子集只取决于用到的字符，因此同一文档逐字节相同（14d-3 的重现依赖这一点，
  `PdfReportWriterTest`、`ReportExportIT`）。XLSX 的文档属性含生成时间，不保证逐字节相同。
- **字体**：平台带 Noto Sans Regular / Bold（拉丁、希腊、西里尔字母；SIL Open Font License，`runtime` 资源 `jabiz/fonts/`，许可见同目录 `OFL.txt`）。
  中文、日文等字符由应用以 `jabiz.reports.pdf.fonts`（TrueType 字体文件路径，逗号分隔，按顺序尝试）提供；哪种字体都没有的字符显示为 `?`，不使报表失败。
  配置的字体文件读不到 → 启动检查报错 `REPORTS | jabiz.reports.pdf.fonts`。提供中文或日文界面的应用部署时应配置这项（否则其 PDF 中这些文字为 `?`；CSV 与 Excel 不受影响）。
- **格式化**：公司名 `jabiz.reports.company`（缺省 `spring.application.name`）；时区 `jabiz.reports.zone`（缺省 UTC）；
  区域 `jabiz.region` 由 `jabizApp { region }` 写入 jar 的 `META-INF/jabiz-app.properties`，数字与时间的写法与后台一致（12 §10），无区域时为
  `1,234.50`、`2026-01-31 14:05:09`（core `ReportFormat`）。

## 5. 存档（14d-3）

### 5.1 签发

流程 `REPORT_ISSUE`（权限 `report.issue`，步骤中再要求模板自己的全部权限）：输入 `{templateId, params, asOf, knownAt, supersedes}`，
输出 `{runId, templateVersion, contentHash, rowCount, recomputable}`，并发布事件 `jabiz.report.issued`（`runId`、`templateId`、`contentHash`、`supersedes`）。

- **时点**：没有人给出的部分——输入中没有、模板的 `timeSlice` 参数也没有——固定（pin）在签发时刻（操作时间）并存档：生效时点记为 `read_at`，
  记录时点记为 `known_at`，于是以后按同一时点重跑读到的是同样的版本（包括签发时已排定、之后才生效的变更）。固定的时点是平台自己的运行，
  不算调用方要求的时间旅行：不受 `timeSlice` 与数据视图 `allowTimeTravel` 的限制（`AdvancedQueryExecutor.At.pinned`）。
  输入给出了生效时点时它也记为 `as_of`，并写在页眉。
- **内容**：整份结果（不带筛选），行数上限同导出（`jabiz.reports.export.max-rows`，超过 422 `REPORT_TOO_LARGE`）。
  值按列的类型规范化（金额为精确小数、时间为时刻、多语言文本为按键排序的映射），以 core `ContentHash`（18 §3.3）对列名与行计算内容哈希
  （小数的尾零不影响哈希）。
- **遮蔽字段**（10 §13.1【D28】）：签发的报表一律为遮蔽形式，无论签发人是否持有权限——存档会被别人阅读、重现与核对，核对必须得到相同的行。
- **访问审查**（10 §13.3）：平台模板 `jabiz.security.access_review` 按期末时点签发，签核流程 `ACCESS_REVIEW_SIGN_OFF` 引用该运行与内容哈希。
- **可重算**：模板只读时态实体时 `recomputable` 为真；读取了会被原地修改的数据时只能按存档重现，不能按数据核对。
- **取代**：`supersedes` 给出同一模板更早的一次运行；不同模板 → 422 `REPORT_SUPERSEDE_MISMATCH`，已被取代 → 422 `REPORT_ALREADY_SUPERSEDED`，
  不存在 → 404。一次运行至多被取代一次（主键）。

### 5.2 表（迁移 V17，均只追加，D5 的触发器保护）

| 表 | 内容 |
|---|---|
| `sys_report_run` | `run_id`（UUIDv7）、`template_id`、`template_version`、`template_source`（签发时模板文件的全文；Java 声明的模板为其版本所依据的规范描述）、`permissions`（签发时模板的权限，读取时据此检查）、`scope`（取自调用方的数据视图范围的签发人取值，按视图 id）、`title`、`company`、`period`、`language`、`params`（运行所用参数，JSON）、`parameters`（页眉显示的参数）、`as_of`、`read_at`、`known_at`、`landscape`、`columns`（名称、标签、类型）、`rows`、`row_count`、`content_hash`、`recomputable`、`issued_by`、`issued_time`、`process_seq_id` |
| `sys_report_run_supersede` | `run_id` → `superseded_by`、`superseded_time`、`process_seq_id` |

列与行存为 **text 中的规范 JSON** 而不是 `jsonb`：`jsonb` 会重排键、改变数字的写法，而存档要保存计算哈希时的原样（小数、时刻都写成字符串，不经过浮点）。

### 5.3 读取、重现与核对

均需 `report.archive.read`、该次运行签发时模板的全部权限，以及——模板的数据视图范围取自调用方时（如按当前用户、租户）——与签发人**相同的范围值**：
存档的行是签发人的范围内所见，范围不同的读者即使有权限也不能读（签发时范围值记入 `sys_report_run.scope`，读取时按当前的视图定义为读者解析并比较；
视图已不存在或读者的范围无法解析，一律视为不同；模板现在的某个视图范围取自调用方而运行中没有记下它——签发时它还不取自调用方，
例如后来声明的数据期限（10 §13.2）——而读者在其中受限时，同样视为不同）。不可读的运行一律 404（不暴露存在与否），列表中也不出现。

| 接口 | 说明 |
|---|---|
| `GET /api/reports/runs?template=&limit=` | 调用方可读的最近的运行（先按权限过滤再取条数；默认 50，至多 200），不含行；`supersededBy` 为取代它的运行 |
| `GET /api/reports/runs/{id}` | 一次运行：页眉（公司、期间、参数、语言）、所用参数、列 |
| `GET /api/reports/runs/{id}/export?format=csv\|xlsx\|pdf` | 由存档的行与签发时的页眉、语言生成文件，**不重新查询**；先以存档的哈希核对存档的行；响应头 `X-Jabiz-Content-Hash`；PDF 每次逐字节相同 |
| `POST /api/reports/runs/{id}/verify` | 当前模板版本与签发时相同时，按存档的参数、固定在 `read_at`、`known_at` 的时点重新执行并比较哈希：`identical` / `differs`；版本不同 → `template_changed`（不重算） |

### 5.4 后台

- 报表运行页：有 `report.issue` 时显示"签发"（确认后按当前的参数与时点签发全部行），完成后转到该报表的存档。
- `/reports/archive[?template=]`：已签发的运行（标题、期间、签发时间与人、行数、内容哈希、已被取代、仅存档），可"按签发原样保存"（PDF / Excel / CSV）与"核对"。
  报表目录页在有 `report.archive.read` 时链接到这里。

## 6. 测试

- core：模板版本（换行统一、头部变化即新版本）、`timeSlice` 与 `report` 的检查。
- 集成：`TemplateTimeSliceIT`（时点取值、请求与头部两种来源、冲突、时间旅行禁止、公开模板）、`QueryCatalogIT`（目录内容与权限）。
- 场景：`ledger/as_known_on`（倒签更正前后按两个记录时点运行试算表，只在更正的两个科目上相差）。
- 前端：报表列表与运行页（Vitest）、运行报表（Playwright）。
- 导出（14d-2）：core `CsvReportWriterTest`、`ReportFormatTest`；runtime `XlsxReportWriterTest`、`PdfReportWriterTest`（读回、页眉页脚、列宽、确定性、字体）；
  `ReportExportIT`（三种格式读回、Excel 单元格之和、同一次运行的 PDF 字节相同、记录时点、超限 422、权限与格式）；前端 `ReportPage.test.tsx`、Playwright 下载。
- 存档（14d-3）：runtime `ArchivedValuesTest`（存入读回值与哈希不变）；`ReportArchiveIT`（签发后继续过账，重现的 PDF 与 CSV 逐字节相同、核对 `identical`；
  模板版本不同 `template_changed`；非时态数据被原地修改 `differs`；签发时点固定（含 `timeSlice` 模板与禁止时间旅行的视图，其后生效的排定变更不计入）；取代一次、同模板、不存在；读取与签发的权限；范围不同的读者读不到；只追加）；前端 `ReportPage.test.tsx`（签发）、
  `ReportArchivePage.test.tsx`、Playwright（签发 → 存档 → 核对）。

