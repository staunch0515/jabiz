# 16 内容编辑能力

让"以内容为主"的业务（文章、介绍、多语言说明）也能只写声明就得到可用的后台（ROADMAP 阶段 13d）。
五项能力都是通用的：多语言文本类型、引用的显示与选择、流程作为实体上的操作、子实体列表、只经流程写入的字段。

## 1. 多语言文本 `jabiz.i18n-text`

扩展类型（`Custom`，runtime 提供 `I18nTextKindSupport`），值为 `{语言: 文本}`，存为 `jsonb`：

```java
f.custom(I18nText.of(200))                                   // 单行，每种语言至多 200 字符
f.custom(I18nText.markdown(20_000).required("en"))           // 多行 Markdown，英语必填
```

| 参数 | 说明 |
|---|---|
| `maxLength` | 每种语言按码点计的上限 → `TOO_LONG`（参数 `lang`、`max`） |
| `multiline` | 多行（`markdown` 隐含多行） |
| `format` | `plain`（默认）或 `markdown` |
| `required` | 必须有内容的语言列表 → `TRANSLATION_REQUIRED`（参数 `lang`）；字段本身的 `required` 仍表示"至少有一种语言" |

- 语言键必须属于 `jabiz.i18n.locales`（默认 `zh,ja,en`）→ 否则 `INVALID_VALUE`；空白文本视为没有该语言（规范化时去掉）。
- 允许的运算符：`IS_NULL` `IS_NOT_NULL`（按语言搜索用 SQL 模板，见 05；jsonb 上的 `ILIKE` 与 `pg_trgm` 索引由业务迁移建立）。
- 与 `jabiz.labels` 的区别：`labels` 是字典项等短标签、整体比较；`i18n-text` 是内容，有长度与必填语言规则。两者不合并。
- **校验一致**：`TOO_LONG`、`TRANSLATION_REQUIRED` 是新的语义约束，先在 `spec/validation-cases.json` 加用例，前端校验器同时实现（D15 第 2 条）。
- 读取：数据视图与模板返回完整的 `{语言: 文本}`，由前端按"界面语言 → `jabiz.i18n.default-locale` → 任一有内容的语言"选择；
  显示回退语言时，前端给该元素加 `lang` 属性（无障碍：读屏软件按正确语言朗读）。
- Markdown 只在前端渲染，渲染器**不允许原始 HTML**；服务端只存文本，不渲染。
- 后台控件：每种语言一个标签页（必填语言带标记），`markdown` 时附预览（同一渲染器）。

## 2. 引用的显示与选择

```java
eb.display("title");            // 该实体被引用时用哪个字段显示（Text 或 i18n-text）
```

- 新增只读接口（数据视图读权限）：
  - `GET /api/datasets/{id}/lookup?q=&limit=` → `[{id, label}]`（至多 20 条）：对显示字段做不区分大小写的包含匹配（`i18n-text` 匹配任一语言），
    经该视图的范围；按显示字段排序。
  - `POST /api/datasets/{id}/labels {ids: [...]}` → `{id: label}`：批量取显示文本（至多 200 个），用于列表中的引用列。
- 后台：`Reference` 字段用可搜索的下拉（目标实体默认视图可读时；否则退回文本框），列表中的引用列显示标签而不是主键。
- 启动自检：显示字段存在且类型为 `Text` 或 `jabiz.i18n-text`，不是敏感字段。没有声明 `display` 的实体保持现状（显示主键）。

## 3. 流程作为实体上的操作

```java
ProcessDefinition.single("CULTURE_STORY_SUBMIT", 1, StorySubmit.class, Out.class, ...)
    .actsOn("Story", "storyId", a -> a.whenField("status", "DRAFT"))   // 输入组件 storyId 取当前行主键；只在 DRAFT 时显示
    .withPermissions("culture.story.submit");
```

- `actsOn(entity, inputComponent[, 条件])`：声明流程输入中哪个组件是该实体的主键。导出到 `GET /api/meta/processes`（`actsOn: {entity, input, when}`）。
- 后台在该实体的列表行与详情中显示"操作"按钮（仅列出调用方可执行的流程，与目录同一判断）；点击后打开流程表单，主键已填且只读，其余输入照常生成。
  输入只有主键时直接确认执行。
- `when` 只是显示提示（字段等于给定值之一），**不是**权限或规则：服务端的状态机、守卫与流程自身的检查照常裁决。
- 启动检查：实体存在；输入组件存在且类型与实体主键相符；`when` 的字段存在且为 `Code` 或 `Bool`。

## 4. 子实体列表

后台的详情抽屉中，列出**以引用字段指向当前行**的其他实体（该引用字段在子实体的列表视图 `filters` 中、调用方可读子实体的默认视图时），
每个子实体一个分页列表，新建时引用字段预填。不需要新的声明：由已有的 `Reference` 与列表视图推导。

## 5. 只经流程写入的字段 `f.processOnly()`

工作流字段（状态、发布时间、审核意见、由流程维护的可见性标记）应当只由流程改变，而同一实体的其他字段仍在后台直接编辑。
`processOnlyWrites()`（03 §2.6）作用于整个数据视图，粒度太粗；因此增加字段级声明：

```java
eb.field("status", f -> f.asCode(null, "DRAFT", "IN_REVIEW", "PUBLISHED").processOnly());
```

- 写入规则与敏感字段相同（02 §6.1），但**照常可读**：数据视图 `commit` 与通用实体流程的属性中出现该字段 → 400 `PROCESS_ONLY_FIELD`；
  流程的 `ChangeSet` 可以写。
- 经数据视图插入时由平台填值：状态机的状态字段取初始状态；是数据视图范围字段时取范围值（范围的自动填充照常）；否则为空（字段不得为必填）。
- 导出 `processOnly: true`，后台表单把它显示为只读；启动检查：不能同时是 `sensitive`、`generated`。
- 与状态机、守卫、实体级校验的关系不变：流程写入时它们照常裁决。

## 6. 后台挂在子路径下

后台前端支持以子路径部署（`VITE_BASE`，默认 `/`；React Router 的 `basename` 随之设置）；接口路径仍是绝对的 `/api`。
应用可以把后台挂在 `/admin/`，把根路径留给公开网站（17 §3）。

## 7. 测试

- core：`processOnly` 的构建期检查与导出；`I18nText` 转换与校验（未知语言、空白、超长、必填语言）；共享校验用例新增的两种错误码；`display`、`actsOn` 构建期检查。
- runtime 集成测试：`processOnly` 字段经 `commit` 与通用实体流程被拒绝、经流程可写、插入时填初始状态；`i18n-text` 经数据视图读写（jsonb 往返）；`lookup` / `labels` 的范围与权限；流程目录导出 `actsOn`。
- 前端（Vitest）：多语言控件、Markdown 预览不渲染原始 HTML、语言回退与 `lang` 属性、引用下拉、行操作按钮的显示条件；
  Playwright：在示范实体上完成"新建 → 选择引用 → 执行行操作"。
- 示范：在 `app` 中给 `Supplier`（或新增一个小实体）加一个多语言说明字段与一个行操作，证明通用性不依赖 culture。
