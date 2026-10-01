# 22 单据：版式、确定性 PDF、签发存档与发送

发票、订单确认书、付款通知、1099 收件人副本这类**单据**（ROADMAP 阶段 14j，决策 D30）：一张单据由几个区块组成（发出方与收件方、
单号与日期等键值、明细表、合计、说明文字），数据来自普通 SQL 模板（05）。平台在一个时点读取这些模板，排成 PDF，**保存 PDF 原样字节**，
以后打印和发送的都是这份字节（14j-1）；以附件发给外部收件人并记录每次投递（14j-2）。报表（19）是一张表，单据是版式；两者共用模板、
时点、规范化与内容哈希、数据范围的实现，存档各自独立。

## 1. 总览

| 能力 | 做什么 | 阶段 |
|---|---|---|
| 版式 | 代码中声明的 `DocumentLayout`：区块与所读的模板、单号、对象；启动检查 `DOCUMENTS` | 14j-1 |
| 签发 | 流程 `DOCUMENT_ISSUE`：按一个时点读取全部模板、排版、保存 PDF 字节与内容 | 14j-1 |
| 重印与核对 | 原样字节下载；核对 PDF 哈希与按存档时点重读的数据 | 14j-1 |
| 预览 | 按现在排版、每页标"预览"、不保存 | 14j-1 |
| 发送 | 流程 `DOCUMENT_SEND`：附件为存档字节，每个收件人一封，记录投递 | 14j-2 |

## 2. 版式（14j-1）

### 2.1 声明

```java
@Bean
DocumentLayout orderConfirmation() {
    return DocumentLayout.define("commerce.order_confirmation", d -> d
        .permissions("commerce.order.read")
        .subject("SalesOrder", "orderId")                    // 单据的对象：按它列出已签发的单据
        .number("commerce.order_document_header", "orderNo") // 单号：文件名、列表、页脚
        .recipients("commerce.order_document_header", "contactEmail") // 缺省收件地址（14j-2，第 5 节）
        .party("customer", "commerce.order_document_header", "customerCode")
        .facts("commerce.order_document_header", "orderNo", "orderedTime", "warehouseName")
        .table("commerce.order_document_lines", "lineNo", "sku", "productName", "quantity", "unitPrice", "lineAmount")
        .totals("commerce.order_document_header", "totalAmount")
        .note("thanks"));
}
```

| 区块 | 数据 | 排版 |
|---|---|---|
| `party(键, 模板, 列…)` | 单行模板 | 标签 `document.<id>.<键>` 下的多行（空值跳过，值中的换行保留），如名称与地址 |
| `facts(模板, 列…)` | 单行模板 | "标签 值"两栏；标签至多占区块一半宽 |
| `table(模板, 列…)` | 多行模板（按模板的 `defaultSort`） | 全宽表格，跨页时重复表头；金额、数字、日期不折行并保持宽度，文本列分剩余宽度并折行 |
| `totals(模板, 列…)` | 单行模板 | 右半页，标签与金额；最后一行（有两行以上时）加粗，上方一条线 |
| `text(键, 模板, 列)` | 单行模板 | 标题 `document.<id>.<键>` 与折行的段落，如汇款说明 |
| `note(键)` | 消息 `document.<id>.<键>` | 段落；签发时的文字随单据存档 |

- 连续的 `party` / `facts` 并排（至多 3 个，等宽），其余区块从上到下。第一页顶部为标题 `document.<id>`（左）与单号（右）；
  每页页脚：标题与单号（预览时加"预览"）、"第 n 页，共 m 页"（消息 `document.page`、`document.preview`）。
- **不截断**：单据不能丢字，长文本一律折行（先按换行、再按词、过长的词按字符）；表格行在页中放不下就移到下一页。
- 列标签：`document.<id>.<列>`，没有时用模板自己的列文案（`query.<模板>.<列>`，再退到字段显示名，19 §3.1）。
- 一个模板可以服务多个单行区块与单号（只读一次）；同一模板不能既是单行又是表格。
- `subject(实体, 参数)`：单据的对象。签发时记录该参数的值（`subject_id`），列表与 `DocumentPanel` 按它筛选。
- 模板只收自己声明的参数；版式的参数是全部模板参数的并集，同名参数在各模板中必须取同样的值（JSON Schema 形式相同；
  例如实体的标识与指向它的引用都是 uuid）。不属于任何模板的参数 → 400 `UNKNOWN_FIELD`。
- 版本 `DocumentLayout.version()`：规范描述（id、权限、对象、单号、区块与列）的 SHA-256；签发时连同描述存档。

### 2.2 启动检查 `DOCUMENTS`

一次报告全部问题（`DocumentLayoutProblems`，核心层纯 Java；运行时 `DocumentLayoutRegistry`）：
版式 id 重复；未声明权限；模板不存在、是公开模板（单据按签发人的权限读取）、声明了 `timeSlice`（单据的全部模板按同一时点读取）；
显示的列不在模板结果中；同一模板既单行又表格；同名参数不一致；对象参数不在任何模板中；对象实体不存在；
收件地址列不是文本（14j-2）；应用所选语言缺少标题、`party`/`text`/`note` 的文字（类别 `MESSAGES`）；`jabiz.documents.page-size` 不是 `A4` / `LETTER`。

## 3. 读取与排版（14j-1）

### 3.1 读取

- 时点：调用方给出的 `asOf` / `knownAt`，没给出的部分**固定在签发时刻**（`At.pin`，同 19 §5.1）。全部模板按同一时点读取，
  存档 `read_at`、`known_at`，核对时原样重用。单据通常以业务日期为生效时点：例如订单确认书按下单时刻读取，之后改名的商品、
  仓库仍显示当时的名称；发票按发票日读取客户地址（FIN-AR-001 验收 2）。日期换算为时刻由调用流程负责（应用的时区）。
- 遮蔽字段一律遮蔽（10 §13.1）：单据会被别人阅读、重印与核对。
- 单行区块的模板必须恰好返回一行，否则 422 `DOCUMENT_NOT_SINGLE`（参数 `template`、`rows`），什么都不保存；
  每个模板至多 `jabiz.documents.max-rows` 行（缺省 2000），PDF 至多 `jabiz.documents.max-bytes`（缺省 10 MB），超过 422 `DOCUMENT_TOO_LARGE`。
- 存档的是每个模板的**全部结果列**（不只显示的列），值按 19 §5.1 规范化；标签按单据语言解析后随内容存档。
- 内容哈希：`ContentHash` 覆盖版式 id 与每个模板的列名和行（标签与排版不计入：核对比较的是数据）。

### 3.2 语言、格式与纸张

- 语言：输入 `language`（必须是应用所选的语言之一，否则 400），缺省为调用方的语言。
- 金额、日期、时间按应用区域的写法（`jabiz.region`，与报表相同，19 §4），负数带括号；字体同报表（Noto Sans；中文、日文由 `jabiz.reports.pdf.fonts` 提供）。
- 纸张 `jabiz.documents.page-size`：`A4` 或 `LETTER`；缺省按区域——美国、加拿大、墨西哥、菲律宾为 Letter，其余 A4。报表的纸张不变（A4）。
- 公司名（PDF 的作者）为 `jabiz.reports.company`。单据上显示的发出方信息（地址、税号）应来自数据（模板），而不是配置——这样它随单据存档。

### 3.3 PDF

`PdfDocumentWriter`（PDFBox，沿用报表的字体与区域格式）：**确定性**——文档时间与 ID 取签发时刻，字体子集只由用到的字符决定，
同一内容逐字节相同（`PdfDocumentWriterTest`）。但重印**不**依赖它：重印取存档字节（第 4 节），平台升级改变排版也不影响已签发的单据。

## 4. 签发、存档与重印（14j-1）

### 4.1 流程 `DOCUMENT_ISSUE`

权限 `document.issue`，步骤中再要求版式与其全部模板的权限（`Permissions.requireAll`）。
输入 `{layoutId, params, asOf, knownAt, language}`，输出 `{runId, documentNo, contentHash, pdfHash, pages, recomputable}`；
发布事件 `jabiz.document.issued`（`runId`、`layoutId`、`documentNo`、`contentHash`、`pdfHash`）。

业务流程以子流程调用：`CallProcess.of(DocumentProcesses.ISSUE, 1, ctx -> new IssueInput(…), "issued")`——由业务流程决定时点（单据日期）、
检查状态（例如只有已过账的发票才能签发），并声明自己的权限；子流程的 `document.issue` 不另检查（06 §5），版式与模板的权限仍按调用人检查。

### 4.2 表 `sys_document_run`（迁移 V27，只追加，D5 的触发器保护，14f-2 的封存自动覆盖）

`run_id`（UUIDv7）、`layout_id`、`layout_version`、`layout_source`（规范描述）、`template_versions`（各模板版本）、`permissions`（签发时版式与模板的权限）、
`scope`（调用方相关的数据范围取值，19 §5.3）、`subject_entity`、`subject_id`、`document_no`、`title`、`language`、`page_size`、`params`、
`as_of`、`read_at`、`known_at`、`content`（text 中的规范 JSON：标题、单号、标签、各模板的列与行）、`content_hash`、`recomputable`、
**`pdf`（bytea，原样字节）**、`pdf_hash`（SHA-256）、`pdf_size`、`page_count`、`issued_by`、`issued_time`、`process_seq_id`。

单据从不修改；更正是另一张单据（例如贷项通知单），同一对象可以签发多次，全部保留。

### 4.3 接口

读取都需要 `document.archive.read`、签发时的全部权限，以及与签发人**相同的数据范围**（同 19 §5.3）；不可读的一律 404，列表中也不出现。

| 接口 | 说明 |
|---|---|
| `GET /api/meta/documents` | 有 `document.issue` 且有版式与模板全部权限的版式：标题、对象、版本、参数的 JSON Schema（各模板参数的并集） |
| `POST /api/documents/{layoutId}/preview` | 请求体 `{params, asOf, knownAt, language}`；按现在（或给出的时点）排版，每页标"预览"，**不保存**；权限同签发 |
| `GET /api/documents/runs?layout=&subject=&limit=` | 可读的最近单据（先过滤再取条数；缺省 50，至多 200），不含内容与 PDF |
| `GET /api/documents/runs/{id}` | 一张单据：摘要、参数、模板版本、各模板的列与行 |
| `GET /api/documents/runs/{id}/pdf` | **存档原样字节**，先以存档的哈希核对（不符 → 500，不交出被改的字节）；响应头 `X-Jabiz-Pdf-Hash`、`X-Jabiz-Content-Hash`，文件名 `<单号>.pdf` |
| `POST /api/documents/runs/{id}/verify` | `copyIntact`：存档字节的哈希仍是签发时的；`verdict`：版式版本不同 → `layout_changed`，某模板版本不同 → `template_changed`（都不重读），否则按存档的参数与时点重读、比较内容哈希：`identical` / `differs` |

### 4.4 设置

| 配置 | 缺省 | 说明 |
|---|---|---|
| `jabiz.documents.page-size` | 按区域 | `A4` / `LETTER` |
| `jabiz.documents.max-rows` | 2000 | 每个模板的行数上限 |
| `jabiz.documents.max-bytes` | 10485760 | PDF 大小上限 |

观测 `jabiz.document.render`（标签只有版式名与结果，13），签发本身在流程的观测 `jabiz.process` 中。

## 5. 发送（14j-2）

### 5.1 收件人

- 版式以 `recipients(模板, 列)` 声明单据缺省发往的地址：单行模板的文本列，一个或几个地址（以逗号、分号或换行分隔，不区分大小写去重）。
  签发时读出的地址随单据存档（`sys_document_run.recipients`，JSON 数组），以后发送不重新查询——发出的单据写给谁，在签发时就定了。
- 只接受**纯地址**（`name@example.com`：点原子形式，至多 320 字符，不带显示名、不含换行），因此地址之外的东西到不了邮件头（core `DocumentRecipients`）。
- 发往单据数据中没有的地址需要单独授予的 `document.send.any`（缺省不授予任何角色），防止把单据发往任意地址；数据中的地址不区分大小写。

### 5.2 流程 `DOCUMENT_SEND`

权限 `document.send`；输入 `{runId, to}`（`to` 为空即发往单据数据中的地址），输出 `{deliveryIds, addresses}`。检查依次为：

| 检查 | 不满足时 |
|---|---|
| 邮件开启（`jabiz.mail.enabled`） | 422 `MAIL_DISABLED`（不静默记录） |
| 单据存在且调用方看得见（签发时的全部权限与相同的数据范围，同 4.3；不需要 `document.archive.read`） | 404 |
| 每个地址都是纯地址；至多 10 个 | 400（字段 `to[i]` / `to`） |
| 至少一个地址 | 422 `DOCUMENT_NO_RECIPIENT` |
| 数据以外的地址需要 `document.send.any` | 422 `DOCUMENT_RECIPIENT_NOT_ALLOWED`（参数 `address`） |

- 在流程事务内为每个地址写一条投递（`sys_document_delivery`）：地址、主题、正文、请求人、时间。**提交后**的步骤逐条发送，每封只有一个收件人
  （收件人之间互不可见），附件为存档的 PDF 原样字节（文件名同下载），发送前以存档的哈希核对——被改动的副本不会发出，该次尝试记为失败。
- 每次尝试写入 `sys_document_delivery_attempt`（`SENT` / `FAILED` 与错误），已发送的不再发送，有失败即按重试策略（5 次，1 秒起加倍）重试整个步骤——
  与待办通知（18 §5.4）同一发送器 `NotificationSender`（新增带附件的 `send(MailMessage)`，`SmtpNotificationSender` 以 MIME 多部分发送）。
- 主题与正文：消息 `document.<版式>.mail.subject` / `.body`，没有时用平台的 `document.mail.subject` / `.body`；参数 `{title}`、`{number}`、`{company}`；
  语言为单据的语言。主题中的换行一律换成空格。
- 业务流程以子流程调用（`CallProcess.of("DOCUMENT_SEND", 1, …)`），例如签发后立即发送；与 `DOCUMENT_ISSUE` 同一事务，邮件在提交后发出。

> 计划中投递记在 `sys_notification`（D30 第 6 条的原文）；实施时细化为单据自己的两张表：`Notification` 是受审计的平台实体、主键为文本、只指向待办，
> 无法以外键指向不是实体的单据存档；发送机制（提交后发送、尝试只追加、重试、同一发送器）不变。

### 5.3 表（迁移 V28，只追加，D5 的触发器保护）

| 表 | 内容 |
|---|---|
| `sys_document_run.recipients` | 新列：签发时数据中的地址（JSON 数组；V28 之前签发的单据为空） |
| `sys_document_delivery` | `delivery_id`（UUIDv7）、`run_id` → `sys_document_run`、`address`、`subject`、`body`、`requested_by`、`created_time`、`process_seq_id` |
| `sys_document_delivery_attempt` | `delivery_id`、`attempt_no`（主键）、`outcome`、`error`、`attempted_time` |

`GET /api/documents/runs/{id}` 附 `deliveries`：每个投递的地址、主题、请求人与时间、状态（`PENDING` 未尝试、`SENT`、`FAILED` 最近一次失败、平台在重试）、
尝试次数、最近一次尝试的时间与错误；摘要附 `recipients`。

## 6. 后台

- `/documents`（菜单"单据"，有 `document.archive.read` 时显示）：已签发的单据（标题与单号、对象、签发时间与人、页数、PDF 哈希），
  可"下载"原件与"核对"（显示 `copyIntact` 与结论）；`?layout=`、`?subject=` 筛选。
- `@jabiz/admin` 导出 `DocumentPanel({layoutId, params, subjectId, issue, onIssued})`：该对象已签发的单据（下载原件）、预览、签发
  （缺省经 `DOCUMENT_ISSUE`，`issue` 给出应用自己的流程与输入时经它，例如需要按单据日期读取时）。应用扩展页面（如财务的发票页）直接使用。
- 业务流程声明 `actsOn(实体, 参数)` 时，通用列表的行操作即可签发（示范：订单的"开具订单确认书"）。
- 发送（14j-2）：单据页与 `DocumentPanel` 的"发送"（有 `document.send` 时）打开对话框，缺省为单据中的地址，可增删；服务端的拒绝原样显示。
  单据页每行展开为投递记录（地址、状态、尝试次数、请求人与时间，失败时悬停显示最近的错误）。

## 7. 示范（`app`）

版式 `commerce.order_confirmation`（`OrderConfirmations`）：模板 `commerce.order_document_header`（订单号、客户、下单时间、仓库、合计）与
`commerce.order_document_lines`（行、SKU、品名、数量、单价、金额）；流程 `ORDER_CONFIRMATION_ISSUE`（`actsOn(SalesOrder)`，权限 `commerce.order.confirm`）
按下单时刻经子流程 `DOCUMENT_ISSUE` 签发。订单头模板另给出客户的联系地址（示范没有客户主数据，地址由客户代码拼成），
即版式的 `recipients`；流程 `ORDER_CONFIRMATION_SEND`（同样 `actsOn(SalesOrder)`）签发后以子流程 `DOCUMENT_SEND` 发给该地址。

## 8. 测试

- core：`DocumentLayoutTest`（模板、单行、列、所需消息、版本、错误声明、内容）、`DocumentLayoutProblemsTest`（全部问题一次报告、参数一致、合并的参数 schema）。
- runtime：`PdfDocumentWriterTest`（区块顺序与格式、Letter / A4、长表跨页重复表头、每页页脚、折行不截断、预览标记、逐字节相同、列宽、纸张设置）。
- app：`DocumentIssueIT`（签发后改名，重印逐字节相同、核对 `identical`；再次签发仍按下单时刻读取；预览按现在且不保存；改动存档字节 → `copyIntact=false` 且下载被拒；
  绕过平台改数据 → `differs`；模板 / 版式版本不同 → `template_changed` / `layout_changed`；单行模板无行 → 422 且不保存；未知参数 400、未知版式 404、未知语言 400；
  权限：签发需版式权限（经业务流程也一样），读取需存档权限，缺版式权限 404 且不列出；只追加；目录）、`PlatformCheckIT`（`DOCUMENTS` 全部问题一次报告）、`OpenApiSnapshotIT`。
- 发送（14j-2）：core `DocumentRecipientsTest`、版式与检查的收件人部分；app `DocumentSendIT`（GreenMail：发往数据中的地址、每个地址一封、附件哈希等于存档、
  主题含单号；再次发送；数据以外的地址需要 `document.send.any`、大小写不同算同一地址；非纯地址与超过 10 个 400；权限与看不见的单据 404；
  失败的尝试记录后重试成功、已发送的不再发；被改动的副本不发出；只追加）、`DocumentIssueIT`（邮件关闭时 422 `MAIL_DISABLED` 且不记录；签发时记下地址）、`NotificationIT` 照常。
- 前端：`DocumentsPage.test.tsx`、`DocumentPanel.test.tsx`（发送对话框、服务端的拒绝、投递记录）；e2e `documents.spec.ts`（订单行操作签发 → 单据页 → 下载原件 → 核对；
  发送对话框给出单据中的地址并显示服务端的拒绝——e2e 的应用不开邮件）。
