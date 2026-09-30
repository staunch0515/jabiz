# 20 导入

应用需要把外部文件整批导入：主数据、期初余额、日记账、银行对账单、工资、汇率。导入的正确性就是业务流程的正确性：
平台把文件读成行，把每行（或每组行）交给手工录入所用的同一个业务流程，负责读取、转换、事务、去重与报告；业务规则仍在流程中。
决策见 D26。阶段 14e-1 实施 §1–§4、§5 的试运行（预览）、§6 的查看与映射接口、§7；14e-2 实施提交、去重记录、导入记录与报告导出、后台页面。

## 1. 文件

导入文件先经 `/api/files?policy=…` 上传（14），策略只允许导入专用类型：

| 类型 | 判定 | 说明 |
|---|---|---|
| `TEXT` | 开头 4 KB 没有 NUL 与制表、换行、换页、回车以外的控制字符，且不以 `<` 开头 | CSV、定宽、BAI2 等行格式；解析器由导入定义决定，不看扩展名 |
| `XLSX` | ZIP 中有 `xl/workbook.xml`，`[Content_Types].xml` 声明无宏工作簿，没有 `xl/vbaProject.bin` | 须看整个文件（ZIP 目录在文件末尾）；`.xlsm`、`.xls`、`.docx` 不接受 |
| `XML` | 可选 BOM 与空白之后是 `<?xml` 或一个元素 | HTML、SVG、含 `<script` 的拒绝；以注释或 DTD 开头的也不接受 |

```java
@Bean FilePolicy appImport() {
    return FilePolicy.define("app.import")
        .allow(MediaTypes.TEXT, MediaTypes.XLSX, MediaTypes.XML)   // 不能与图片、PDF、音频混用
        .maxBytes(5 * FilePolicy.MB)
        .permissions("app.import.upload", "app.import.read")
        .build();
}
```

导入文件原样保存（哈希即原文件的哈希），下载一律 `Content-Disposition: attachment` 与 `CSP: sandbox`，公开接口（15）永远不提供。
读取时先把存储中的内容复制到本地临时文件并核对上传时记录的 SHA-256：导入的正是上传的文件。

## 2. 版式与解析（core，纯 Java）

`ImportFormat` 描述文件版式；解析器同步执行（运行时在 `boundedElastic` 上调用），产出记录（`RawRecord`：序号、位置、列名到文本）：

| 版式 | 说明 | 映射可调整 |
|---|---|---|
| `ImportFormat.csv()` | RFC 4180：引号内可含分隔符、成对引号与换行；CRLF 或 LF；空行跳过；未加引号的单元格去掉首尾空白。缺省逗号、有表头、UTF-8（去掉 BOM） | 分隔符、是否有表头、跳过行数、字符集（UTF-8、windows-1252、ISO-8859-1） |
| `ImportFormat.fixedWidth(...)` | 按位置截取的列，值去掉首尾空白；短行在末尾补空 | 否 |
| `ImportFormat.xlsx()` | 一个工作表（缺省第一个）；表头行（缺省第 1 行）之前的行忽略 | 工作表、是否有表头、表头行 |
| `ImportFormat.xml(记录路径, 列, 路径, …)` | 元素名（不含命名空间）以 `/` 连接；属性写 `@名`；记录内同一路径取第一次出现的值；记录之外的值可声明为文件头（如对账单余额） | 否 |
| `ImportFormat.custom(名, 解析器)` | 应用自己的 `ImportParser`（BAI2、camt.053、OFX） | 否 |

- 无表头时列名按位置（CSV `1`、`2`…；XLSX 按列字母 `A`、`B`…）。表头重名（不分大小写）拒收。
- 单元格多于列数的记录标为问题（`IMPORT_EXTRA_CELLS`），不丢弃数据。
- **XLSX 的值**：共享字符串与内联字符串原样；数字取 15 位有效数字（Excel 的精度，`0.1+0.2` 读作 `0.3`）；
  格式为日期的数字读作 ISO 日期或日期时间（1900 与 1904 日期系统）；布尔为 `TRUE`/`FALSE`；公式取 Excel 保存的计算结果，并标记"来自公式"；
  错误值（`#N/A`）使该行成为问题行。平台自己读取 XLSX（JDK 的 ZIP 与 StAX），不引入第三方读取器。
- **安全与上限**（`ParseLimits`，任何一项超出都拒收整个文件，不截断）：记录数（`jabiz.imports.max-rows`，缺省 20000；定义可更低）、每条记录的列数、
  单元格长度、XLSX 每个部件的实际解压字节数（按读取计数，不信任 ZIP 头）。XML 与 XLSX 部件禁止 DTD、外部实体与实体引用；嵌套过深拒收。
  文本按声明的字符集严格解码，无法解码即拒收；文本中的控制字符拒收。

## 3. 导入定义（`ImportDefinition` Bean）

```java
@Bean ImportDefinition<OpeningParams> openingBalances() {
    return ImportDefinition.define("ledger.opening", 1, OpeningParams.class)   // 参数：每次导入给一次，表单由其 JSON Schema 生成
        .file("app.import", ImportFormat.csv())
        .field("entry",   new SemanticKind.Text(100, false), true, "Entry")        // 字段：名、语义类型、是否必填、缺省列名（不分大小写，取文件中第一个存在的）
        .field("account", new SemanticKind.Text(100, false), true, "Account")
        .field("debit",   new SemanticKind.Monetary("USD", 2), false, "Debit")
        .field("credit",  new SemanticKind.Monetary("USD", 2), false, "Credit")
        .perGroup(row -> row.text("entry"), "LEDGER_POST", 1, (rows, params) -> …)  // 或 perRow(流程, 版本, (row, params) -> 输入)
        .fileCheck((content, issues) -> …)                                          // 整个文件的检查：借贷相等
        .totals("debit", "credit")                                                  // 控制合计
        .externalRef(row -> …, OnDuplicate.SKIP)                                    // 外部引用：SKIP 跳过并列出，REJECT 报错
        .zone(ZoneId.of("America/New_York")).datePatterns("MM/dd/yyyy")
        .permissions("ledger.opening.import")                                       // 导入权限（映射权限缺省相同，可另给）
        .build();
}
```

- **读取单元格**（`ImportValues`）：空白为无值；文本检查长度；代码检查允许值；金额与数值接受千分位、货币符号（`$ € £ ¥`）、括号负数与末尾负号，
  小数点只能是 `.`，小数位多于币种（或数值定义）即报错（`MONETARY_SCALE` / `NUMERIC_PRECISION`），不舍入；
  时间接受带偏移的 ISO 时刻，不带偏移的日期时间与日期按导入的时区（日期取当天开始），以及定义的日期格式；布尔接受 true/false/yes/no/y/n/1/0。
- 输入构造函数抛出的异常（例如值无法映射）记为该行的问题，信息即异常信息。
- 分组按组键第一次出现的顺序；重复外部引用的行在分组之前去掉。
- 整个文件的检查只在全部行都读取成功后执行（否则缺行会造成误报）。

## 4. 映射

一次导入可以给出映射（`ImportMapping`）：字段到列、字段的常量（按单元格读取）、CSV/XLSX 的版式调整。映射中未知的字段或列、必填字段既无列也无常量，都是文件级问题。
映射可按名称保存（`PUT /api/imports/{id}/mappings/{名}`），存为时态平台实体 `SysImportMapping`（表 `sys_import_mapping_version`，只追加，同一导入内名称唯一），
只经内部流程 `IMPORT_MAPPING_SAVE` / `IMPORT_MAPPING_REMOVE` 写入，需要导入的映射权限。值的映射（如供应商代码到科目、部门）属于业务数据，由行流程查询。

## 5. 执行

平台流程 `IMPORT_RUN`（内部流程）的步骤 `RunImport`：

1. 检查导入权限、行流程的权限与文件策略的读取权限（入口已检查；内部流程仍可被直接请求执行，所以流程自己再查一次）。
2. 复制并核对文件，读取、转换（§2、§3），查出重复，执行整个文件的检查，构造各单元（行或组）的输入。
3. 在**同一个事务**中依次执行每个单元：先设保存点，再以子流程执行目标流程（输入先按其约束校验，与请求体相同）；
   失败（4xx 类错误）回滚到保存点，把全部违规记为该单元第一行的问题，该单元的各行标为错误，继续下一个单元；5xx 类错误（缺陷）使整个导入失败。
   后面的单元看得到前面成功单元的写入，所以预览与提交的结论一致。
4. 输出导入报告（`ImportReport`）：记录数、将导入的行数、成功与计划的单元数、重复数、各字段取自哪一列、常量、控制合计、每条记录的状态（`ok` / `duplicate` / `error`）与值
  （读取失败的行给出原文）、全部问题。

- **预览**（`POST /api/imports/{id}/preview`）：`IMPORT_RUN` 的试运行（`ExecutionOptions.DRY_RUN`）：流程完成后整个事务回滚，操作记录、业务数据、Outbox、
  单据号计数都不留下，也不执行提交后步骤。
- **提交**（14e-2）：有任何问题即整体回滚（422 `IMPORT_REJECTED`），另起事务记录被拒的导入；没有问题则一起提交数据、导入记录、外部引用与事件 `jabiz.import.committed`。
  各行的 `op_process.parent_seq_id` 指向导入的操作。
- 保存点由存储引擎提供（`StorageEngine.inSavepoint`，只在事务内使用）。

## 6. 接口

| 接口 | 说明 |
|---|---|
| `GET /api/meta/imports` | 当前用户可运行的导入：标题（消息 `import.<id>`）、字段（标签 `import.<id>.<字段>`、语义类型、必填、缺省列）、版式与可调整项、可接受的类型与扩展名、参数 Schema、控制合计、外部引用、能否保存映射 |
| `POST /api/imports/{id}/inspect` | `{fileId, options}` → 列、文件头、前 20 条记录、记录数、不给映射时各字段取自的列、文件级问题 |
| `POST /api/imports/{id}/preview` | `{fileId, mapping, params}` → 导入报告（问题信息按请求语言） |
| `GET /api/imports/{id}/mappings`、`PUT`/`DELETE …/mappings/{名}` | 保存的映射 |

- 权限（默认拒绝）：导入权限、行流程的全部权限、文件策略的读取权限，缺一即 403；保存映射另需映射权限。未知导入与文件 404；文件不是按导入的策略上传的 400 `IMPORT_WRONG_FILE`；参数不合约束 400。
- 错误码（多语言消息）：`IMPORT_FILE_INVALID`（带 `{detail}`）、`IMPORT_TOO_MANY_ROWS`、`IMPORT_COLUMN_MISSING`、`IMPORT_UNKNOWN_COLUMN`、`IMPORT_UNKNOWN_FIELD`、
  `IMPORT_EXTRA_CELLS`、`IMPORT_VALUE_INVALID`、`IMPORT_DUPLICATE_REF`、`IMPORT_EMPTY`、`IMPORT_WRONG_FILE`、`IMPORT_ROW_FAILED`、`IMPORT_REJECTED`；
  行流程的违规保留其自身的错误码。
- 观测：`jabiz.import.run`，标签只有导入名与模式（`preview` / `commit`）。

## 7. 启动检查（`IMPORT`、`MESSAGES`）

导入 id 唯一；文件策略存在、只允许导入类型、允许版式所读的类型（CSV/定宽需要 `TEXT`，XLSX 需要 `XLSX`，XML 需要 `XML`）；目标流程与版本已注册；
参数表单能描述参数类型（否则警告）；导入与每个字段在应用的每种语言下都有文本。

## 8. 测试

- core：类型判定（含伪装的 xlsm/docx、带 NUL 的文本、HTML/SVG）、各解析器、XXE 与实体膨胀、解压上限、单元格读取、映射、重复、分组、整个文件的检查、控制合计。
- 集成（`ImportIT`）：预览执行全部行并报告全部问题，且不留下任何数据与操作记录；保存点只撤销失败的单元；按组导入与不平衡文件；XLSX 与 XML；
  查看文件与映射、保存的映射只追加；默认拒绝（缺导入权限、缺行流程权限、直接执行内部流程、别的策略的文件）。
