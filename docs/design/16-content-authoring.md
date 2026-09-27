# 16 内容编辑能力

让"以内容为主"的业务（文章、介绍、多语言说明）也能只写声明就得到可用的后台（ROADMAP 阶段 13d）。
五项能力都是通用的：多语言文本类型、引用的显示与选择、流程作为实体上的操作、子实体列表、只经流程写入的字段。
约束性细则见【决策 D20】。

## 1. 多语言文本 `jabiz.i18n-text`

扩展类型（`Custom`），值为 `{语言: 文本}`，存为 `jsonb`：

```java
f.apply(I18nText.of(200))                                    // 单行，每种语言至多 200 字符
f.apply(I18nText.markdown(20_000).required("en"))            // 多行 Markdown，英语必填
```

| 参数 | 说明 |
|---|---|
| `maxLength` | 每种语言按码点计的上限 → `TOO_LONG`（参数 `lang`、`max`） |
| `multiline` | 多行（`markdown` 隐含多行） |
| `format` | `plain`（默认）或 `markdown` |
| `required(...)` | 必须有内容的语言列表 → `TRANSLATION_REQUIRED`（参数 `lang`）；参数与导出的键为 `requiredLanguages`（不与字段自身的 `required` 冲突）；字段本身的 `required` 仍表示"至少有一种语言" |

### 1.1 放在哪一层

- 语义全部在 core（`com.jabiz.entity.i18n`）：不可变的声明 `I18nText`（`of` / `markdown` / `multiline()` / `required(...)`，
  以 `f.apply(...)` 设置字段类型为 `SemanticKind.Custom("jabiz.i18n-text", params)`，与 `Rules` 工厂同一用法）与抽象基类
  `I18nTextSupport`（从 `Map` 规范化、校验、允许的运算符、导出）。core 没有 JSON 库，因此基类不解析 JSON 文本。
- runtime 的 `I18nTextKindSupport` 继承基类，只补上"从存储读出的 jsonb 文本 → `Map`"的解析，并经 `ServiceLoader` 注册（02 §1.2）。
- core 的测试（共享校验用例）注册一个继承基类的测试实现，因此前后端一致性在 core 中即可证明，不依赖 runtime。

### 1.2 取值与校验

- 语言键必须是**平台支持的语言**（core 常量，当前为 `zh`、`ja`、`en`；与消息资源同一集合）→ 否则 `INVALID_VALUE`。
  不新增 `jabiz.i18n.locales` 配置：内容语言与界面语言是同一集合，默认语言仍是 `jabiz.i18n.default-locale`。
- 值必须是对象、每个值必须是文本 → 否则 `INVALID_VALUE`。空白文本视为没有该语言（规范化时去掉）；规范化后为空对象时视为 `null`，
  由字段的 `required` 判断（`REQUIRED`）。
- 语义约束经 `CustomKindSupport.validate`（02 §1.2）报告，与其他语义类型约束一样**只报第一个**：
  先按语言顺序（`zh`、`ja`、`en`）检查超长（`TOO_LONG`，参数 `lang`、`max`），再检查必填语言（`TRANSLATION_REQUIRED`，参数 `lang`）。
- 允许的运算符：`IS_NULL` `IS_NOT_NULL`（按语言搜索用 SQL 模板，见 05；jsonb 上的 `ILIKE` 与 `pg_trgm` 索引由业务迁移建立）。
- 导出：`format`、`multiline`、`maxLength`、`requiredLanguages`、`locales`（平台支持的语言，按上面的顺序）。
- 输入必须是 JSON 对象（文本形式的 JSON 只在从存储读出时接受）。
- 与 `jabiz.labels` 的区别：`labels` 是字典项等短标签、整体比较；`i18n-text` 是内容，有长度与必填语言规则。两者不合并。
- **校验一致**：`TOO_LONG`（带 `lang`）、`TRANSLATION_REQUIRED` 是新的语义约束，先在 `spec/validation-cases.json` 加用例，前端校验器同时实现（D15 第 2 条）。
  前端对其他 `Custom` 类型仍只检查必填（12 §5.1）。

### 1.3 读取与显示

- 数据视图与模板返回完整的 `{语言: 文本}`，由前端按"界面语言 → `jabiz.i18n.default-locale`（实体导出中的 `defaultLocale`）→ 任一有内容的语言（按上面的顺序）"选择；
  显示回退语言时，前端给该元素加 `lang` 属性（无障碍：读屏软件按正确语言朗读）。
- Markdown 只在前端渲染，渲染器**不允许原始 HTML**（`react-markdown`，不启用 `rehype-raw`，并设置 `skipHtml`）；服务端只存文本，不渲染。
- 后台控件：每种语言一个标签页（必填语言带标记，有错误的语言标签页带错误标记），`markdown` 时附预览（同一渲染器）。

## 2. 引用的显示与选择

```java
eb.display("title");            // 该实体被引用时用哪个字段显示（Text 或 i18n-text）
```

- 构建期检查：显示字段存在，类型为 `Text` 或 `jabiz.i18n-text`，不是敏感字段。导出为 `display`。
  没有声明 `display` 的实体保持现状（显示主键）。
- 新增只读接口（该数据视图的**读权限**，与 `query` 同一判断；用 `PlatformObservations` 包装）：
  - `GET /api/datasets/{id}/lookup?q=&limit=` → `[{id, label}]`：`limit` 缺省与上限均为 20。
    `limit` ≤ 0 时 400；`q` 至多 200 个字符（否则 400）。`q` 为空时不筛选；否则对显示字段做不区分大小写的包含匹配（`i18n-text` 匹配任一语言），`q` 中的 `%`、`_`、`\` 按字面匹配（转义后绑定）。
    结果经该视图的范围（时态实体先取当前版本再过滤，D3）；按显示字段排序（`i18n-text` 按请求语言、再按默认语言的文本），再按主键。
  - `POST /api/datasets/{id}/labels {ids: [...]}` → `{id: label}`：批量取显示文本，`ids` 至多 200 个（超出 400 `INVALID_VALUE`）；
    主键按实体主键的类型转换，失败 400 `INVALID_VALUE`；范围外或不存在的主键不出现在结果中。
  - `label`：`Text` 为原文；`i18n-text` 为完整的 `{语言: 文本}`（与 §1.3 相同，由前端选择语言）。
  - 目标实体没有声明 `display` 时两个接口都返回 400 `DISPLAY_NOT_DECLARED`（参数 `entity`）。
- SQL 由 `QueryCompiler` 生成（`compileLookup` / `compileLabels`），列名来自元数据、值一律绑定：
  `Text` 为 `col ILIKE :p ESCAPE '\'`；`i18n-text` 为 `EXISTS (SELECT 1 FROM jsonb_each_text(col) e WHERE e.value ILIKE :p ESCAPE '\')`。
  这是范围内的全表扫描、至多返回 20 行；数据量大时由业务建 trgm 索引或改用 SQL 模板。
- 后台：`Reference` 字段用可搜索的下拉（目标实体的默认视图出现在 `/api/meta/datasets` 中、且目标实体声明了 `display` 时；否则退回文本框），
  当前值经 `labels` 回显；列表中的引用列按页批量取 `labels`，显示标签而不是主键（取不到时显示主键）。

## 3. 流程作为实体上的操作

```java
ProcessDefinition.single("CERTIFICATION_SUBMIT", 1, CertificationSubmit.class, Out.class, ...)
    .actsOn("SupplierCertification", "certificationId", a -> a.whenField("status", "DRAFT"))  // 只在 DRAFT 时显示
    .withPermissions("commerce.certification.submit");
```

- `actsOn(entity, inputComponent[, 条件])`：声明流程输入中哪个组件是该实体的主键。`ProcessDefinition` 增加组件 `actsOn`（可为空），
  复制方法（`withPermissions`、`asInternal` 等）保留它。导出到 `GET /api/meta/processes`（`actsOn: {entity, input, when: {field, values}}`，`when` 可缺省）。
- 后台在该实体的列表行与详情中显示"操作"按钮（没有写权限的用户以只读方式打开详情，同样看到操作与子实体列表）（仅列出调用方可执行的流程，即目录中的流程，与目录同一判断）；点击后打开流程表单，主键已填且只读，其余输入照常生成。
  输入只有主键时，确认后直接执行。执行成功后刷新列表与详情。
- `when` 只是显示提示（字段等于给定值之一），**不是**权限或规则：服务端的状态机、守卫与流程自身的检查照常裁决。
- 启动检查（`ProcessChecks`，`PlatformCheck`，一次性报告）：实体存在；输入是 record 且组件存在、其 Java 类型与实体主键的规范类型相符；
  `when` 的字段存在且为 `Code` 或 `Bool`，给定值属于 `Code` 的固定值（有固定值时）或为 `true` / `false`。

## 4. 子实体列表

后台的详情抽屉中，列出**以引用字段指向当前行**的其他实体：子实体的某个 `Reference` 字段指向当前实体、该字段在子实体默认视图的列表视图 `filters` 中、
且调用方可读子实体的默认视图（出现在 `/api/meta/datasets` 中）。每个"子实体 + 引用字段"一个分页列表（按该字段 `eq` 筛选），
新建时引用字段预填且只读，行上同样有 §3 的操作。不需要新的声明：由已有的 `Reference` 与列表视图推导。

## 5. 只经流程写入的字段 `f.processOnly()`

工作流字段（状态、发布时间、审核意见、由流程维护的可见性标记）应当只由流程改变，而同一实体的其他字段仍在后台直接编辑。
`processOnlyWrites()`（03 §2.6）作用于整个数据视图，粒度太粗；因此增加字段级声明：

```java
eb.field("status", f -> f.physicalColumn("status").asCode(null, "DRAFT", "SUBMITTED", "APPROVED").processOnly());
```

- 写入规则与敏感字段相同（02 §6.1），但**照常可读**：数据视图 `commit` 与通用实体流程（`ADD/UPDATE_ENTITY`）的属性中出现该字段
  （即使值为 `null`）→ 400 `PROCESS_ONLY_FIELD`（每个字段一条违规）；流程的 `ChangeSet` 可以写。
- **撤销**（只有时态实体的操作可以撤销）：被撤销的操作改过该字段时拒绝（422 `PROCESS_ONLY_FIELD`，与敏感字段在撤销中的处理相同）。这类字段由流程维护，更正也应经流程；
  否则撤销可以绕过状态机把状态改回去。
- **插入时的取值**：插入（任何途径）中没有给出该字段时由平台填值——是状态机的状态字段时取初始状态；是数据视图范围字段时取范围值（范围的自动填充照常）；
  否则为空。流程显式给出的值不受影响（照常经状态机的初始状态检查）。
- 构建期检查：不能同时是 `sensitive` 或 `generated`。启动检查（`DatasetRegistry`，一次性报告）：对每个可直接写入的数据视图
  （非只读、非 `processOnlyWrites`），必填的 `processOnly` 字段必须是状态机的状态字段或该视图的范围字段，且 `processOnly` 的状态字段只能有一个初始状态
（否则经该视图无法插入）。
- 导出 `processOnly: true`；JSON Schema 中 `readOnly: true`；后台表单把它显示为只读，新建与修改都不发送它。
- 与状态机、守卫、实体级校验的关系不变：流程写入时它们照常裁决。

## 6. 后台挂在子路径下

后台前端支持以子路径部署（`VITE_BASE`，默认 `/`；React Router 的 `basename` 随之设置）；接口路径仍是绝对的 `/api`。
应用可以把后台挂在 `/admin/`，把根路径留给公开网站（17 §3）。本项由阶段 13a 实现。

## 7. 错误码

| 错误码 | 状态 | 参数 | 何时 |
|---|---|---|---|
| `TOO_LONG` | 400 | `max`；i18n-text 另有 `lang` | 已有；i18n-text 某种语言超长 |
| `TRANSLATION_REQUIRED` | 400 | `lang` | i18n-text 缺少必填语言 |
| `PROCESS_ONLY_FIELD` | 400（撤销时 422） | — | 经数据视图 API 或通用实体流程写 `processOnly` 字段；撤销改过它的操作 |
| `DISPLAY_NOT_DECLARED` | 400 | `entity` | 对没有声明 `display` 的实体调用 `lookup` / `labels` |

全部在 `PlatformErrorCodes` 中，三种语言都有文案（启动自检）。

## 8. 测试

- core：`processOnly` 的构建期检查与导出（含 JSON Schema）；`I18nText` 规范化与校验（未知语言、非文本值、空白、超长、必填语言、全空为 null）；
  共享校验用例新增的两种错误码；`display`、`actsOn` 构建期检查；`compileLookup` / `compileLabels`（转义、范围、时态实体）。
- runtime / app 集成测试（真实 PostgreSQL，BlockHound）：`processOnly` 字段经 `commit` 与通用实体流程被拒绝、经流程可写、插入时填初始状态、撤销被拒绝
  （普通实体与时态实体各一；时态表断言无 UPDATE / DELETE）；`i18n-text` 经数据视图读写（jsonb 往返）与错误码；`lookup` / `labels` 的范围、权限、
  转义、时态实体、上限与 `DISPLAY_NOT_DECLARED`；流程目录导出 `actsOn`；启动检查一次性报告 `actsOn` 与必填 `processOnly` 字段的问题。
- 场景回放：示范实体的"新建 → 提交 → 审核通过"。
- 前端（Vitest）：多语言控件、Markdown 预览不渲染原始 HTML（`<script>`、带事件属性的 `<img>`、原始 HTML 块）、语言回退与 `lang` 属性、
  引用下拉、行操作按钮的显示条件、共享用例；Playwright：在示范实体上完成"新建（多语言、选择引用）→ 执行行操作 → 在父实体详情中看到子实体列表"。

## 9. 示范（`app`）

- `Supplier`：只增加 `eb.display("supplierName")`（它是教程对象，改动保持最小）。
- 新增时态实体 `SupplierCertification`（`com.jabiz.app.commerce`，业务迁移 `V11__supplier_certification.sql`）：
  `certificationId`（主键）、`supplierId`（`Reference Supplier`，在列表视图 `filters` 中）、`title`（`I18nText.of(200).required("en")`，`display`）、
  `body`（`I18nText.markdown(20_000)`）、`status`（`processOnly`，状态机 `DRAFT → SUBMITTED → APPROVED / REJECTED`）、`reviewComment`（`Text`，`processOnly`）。
- 流程：`CERTIFICATION_SUBMIT`（`actsOn`，`when status = DRAFT`，输入只有主键）、`CERTIFICATION_APPROVE` 与 `CERTIFICATION_REJECT`
  （`when status = SUBMITTED`，驳回带审核意见）。以上证明通用性不依赖 culture。
