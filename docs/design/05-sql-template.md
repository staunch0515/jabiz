# 05 SQL 模板

复杂查询仍然手写 SQL（保留"配置驱动、快速修改上百条 SQL"的能力），但表名和列名经元数据解析、
数据视图范围和时态处理自动附加、参数和结果带语义类型，并在启动时和 CI 中预编译校验。

## 1. 两种声明方式，同一个定义

- **`.sql` 文件（推荐，大多数查询）**：放在 `src/main/resources/queries/**/*.sql`，一个文件一个查询
  （位置可用 `jabiz.sql-templates.locations` 修改，逗号分隔的资源模式）。
- **Java DSL**：`AdvancedQueryDefinition.define(...)`（声明为 Bean 即注册），用于需要在代码中组合的少数情况。

两者都编译为同一个 `AdvancedQueryDefinition`（`SqlTemplateFile.compile` 与 DSL 的等价性由单元测试保证），
由同一个渲染器（core 的 `SqlTemplateRenderer`）和执行器（runtime 的 `AdvancedQueryExecutor`）处理；
注册表 `SqlTemplateRegistry` 按 `id` 收集两者，填入 `like` / `from` 继承的语义类型，并做不需要数据库的检查。

## 2. 文件格式

文件以 YAML 头开始，头部包在 `/*--- ... ---*/` 注释中（对 SQL 工具透明，也便于用 JSON Schema 校验）：

```sql
/*---
id: logistics.tokyo_port_audit
description: 东京港已清关运单的重量、关税和位置
entities: [WaybillTracking, CustomsDeclaration]
params:
  minFreight:      { like: WaybillTracking.freightCharge, required: true }
  allowedStatuses: { like: WaybillTracking.status, list: true, required: true }
  shippedAfter:    { kind: { type: temporal, role: EVENT_TIME } }
results:
  waybillSn:       { from: WaybillTracking.waybillId }
  finalFreight:    { from: WaybillTracking.freightCharge }
  declarationNo:   { from: CustomsDeclaration.declarationId }
  dutyPaid:        { from: CustomsDeclaration.dutyAmount }
  dutyRatio:       { kind: { type: numeric, precision: 9, scale: 4 } }
list:
  filters: [finalFreight, dutyPaid]
  sorts:   [finalFreight, waybillSn]
  defaultSort: { field: waybillSn, asc: true }
permissions: [logistics.report.read]
timeoutMs: 3000
---*/
-- 注释可以写在这里；模板正文从 ---*/ 所在的行开始计行号
SELECT
    w.{{WaybillTracking.waybillId}}        AS waybillSn,
    w.{{WaybillTracking.freightCharge}}    AS finalFreight,
    c.{{CustomsDeclaration.declarationId}} AS declarationNo,
    c.{{CustomsDeclaration.dutyAmount}}    AS dutyPaid,
    c.{{CustomsDeclaration.dutyAmount}} / NULLIF(w.{{WaybillTracking.freightCharge}}, 0) AS dutyRatio
FROM {{WaybillTracking}} w
JOIN {{CustomsDeclaration}} c
  ON w.{{WaybillTracking.waybillId}} = c.{{CustomsDeclaration.waybillRef}}
WHERE w.{{WaybillTracking.freightCharge}} >= :minFreight
  AND w.{{WaybillTracking.status}} = ANY(:allowedStatuses)
  AND (CAST(:shippedAfter AS timestamptz) IS NULL
       OR w.{{WaybillTracking.shippedTime}} >= :shippedAfter)
```

### 2.1 头部字段

| 字段 | 说明 |
|---|---|
| `id` | 全局唯一；建议 `领域.名称` |
| `description` | 可选说明 |
| `entities` | 模板中允许引用的实体；引用未列出的实体 → 启动失败 |
| `datasets` | 可选，`{实体: 数据视图 id}`：该实体经哪个视图读取；未列出的实体用其默认视图【决策 D10】 |
| `params` | 参数：`like: 实体.字段` 继承该字段的语义类型，或 `kind:` 显式声明；`list: true` 表示数组参数；`required`、`default`、`description` 可选 |
| `results` | 结果列：`from: 实体.字段` 继承语义类型并记录血缘，或 `kind:` 显式声明（两者都写时以 `kind` 为准）。**结果列必须全部声明** |
| `list` | 外层筛选 `filters`、排序 `sorts` 白名单，默认排序 `defaultSort: {field, asc}`，稳定排序键 `key`（只能引用 `results` 中的名字） |
| `permissions` | 执行所需权限（全部具备才可执行）；未声明 → 非开发环境启动失败（`dev` 下为警告） |
| `timeoutMs` | 可选，只能比数据视图的超时更短 |
| `access` | 可选，只能是 `public`：公开模板，代替 `permissions`（两者都写 → 启动失败），只能读公开数据视图（15 §3【D17】） |
| `cacheSeconds` | 可选，0–3600，只用于公开模板：公开响应的 `max-age`（默认 `jabiz.public.default-cache-seconds`） |
| `timeSlice` | 可选，`{asOf: 参数, knownAt: 参数}`：由这些参数给出模板中时态实体的读取时点（19 §2.2【D25】）；公开模板不能声明 |
| `report` | 可选，`{period: {from: 参数, to: 参数}, landscape}`：模板是报表，列在后台"报表"页面，期间用于导出的页眉（19 §3.1【D25】） |

- 头部按 JSON Schema（runtime 资源 `jabiz/schema/sql-template-header.schema.json`，draft 2020-12）校验，未知键即报错。
- `kind:` 的写法与元模型导出（02 §8）相同：`{type: monetary, currency: JPY, scale: 0}`、`{type: temporal, role: EVENT_TIME}` 等，
  类型为 `semanticIdentity` `monetary` `temporal` `code` `version` `text` `numeric` `bool` `reference` `custom`（参数在 `params` 下）。
- **结果列名不区分大小写**：PostgreSQL 把未加引号的别名折叠为小写，因此模板中的别名**不得加双引号**（预编译检查会报出）；
  外层筛选与排序以小写名引用结果列。

## 3. 占位符

| 占位符 | 渲染结果 |
|---|---|
| `{{Entity}}` | 该实体在其数据视图（头部 `datasets` 或默认视图【决策 D10】）中的表达式：无范围、无逻辑删除时为表名，否则为 `(SELECT * FROM 表 WHERE 范围条件 AND 未删除)`；时态实体为 04 第 5.1 节的"当前版本"子查询（墓碑排除与范围条件在外层【决策 D3】） |
| `{{Entity.field}}` | 该逻辑字段的物理列名（不带表别名），经 `SqlIdentifiers` 校验 |

- 模板中必须给 `{{Entity}}` 起别名。
- 禁止在模板中直接写物理表名、物理列名（`platformCheck` 会检查模板中出现的裸标识符是否与已知物理名冲突并给出警告）。
- 字符串、带引号的标识符、`$$` 正文和注释中的内容不视为占位符或参数。
- 范围值取自调用方的 `RequestContext`，取不到时拒绝（403 `SCOPE_UNAVAILABLE`）。时态实体按运行时点读取：头部 `timeSlice` 映射的参数、请求中的 `asOf` / `knownAt`，或缺省为 `Clock` 的现在、不限记录时间（19 §2【D25】）。

## 4. 参数

- 使用命名参数 `:name`；值按参数的语义类型转换和校验（现有 `FieldValueCoercer`），`Code` 类型会校验字典。
- **列表参数统一写 `= ANY(:name)`**（取反写 `<> ALL(:name)`），头部声明 `list: true`【决策 D7】。
  平台按语义类型把列表转为对应数组（`String[]`、`UUID[]`、`BigDecimal[]`、`Instant[]` 等）后绑定。
  禁止 `IN (:name)`，`platformCheck` 发现即报错；列表参数至少有一处位于 `ANY(...)` / `ALL(...)` 中，
  其他用法（如可选列表的 `CAST(:ids AS uuid[]) IS NULL`）另外允许。空数组时 `= ANY` 为假、`<> ALL` 为真，平台不做特殊处理。
- 时态实体的主键、以及指向时态实体的引用，按 UUID 绑定（`like` 该主键或 `reference` 类型的参数）；其他标识按文本绑定。
- 缺少必填参数 → 400 `REQUIRED`；值不符合语义类型（含字典）→ 400 `INVALID_VALUE`；传入未声明的参数 → 400 `UNKNOWN_FIELD`；全部一起返回。
- 平台保留的参数：`scope_` 前缀（数据视图范围）、`__` 前缀（时态：`__asOf`、`__knownAt`；分页 `__limit`、`__offset`；外层筛选 `__f*`）。业务参数不得使用。

## 5. 外层分页、筛选、排序

执行列表查询时，平台把渲染后的模板作为子查询：

```sql
SELECT * FROM ( <渲染后的模板> ) q
WHERE <白名单内的筛选条件，基于 results 的语义类型检查运算符>
ORDER BY <白名单内的排序，缺省为 defaultSort，最后追加稳定排序键>
LIMIT :__limit OFFSET :__offset
```

- 另生成 `SELECT count(*) FROM ( <带筛选的外层查询> ) c` 用于总数（可由调用方关闭）。
- 模板**不得**在最外层包含 `LIMIT` / `OFFSET` / `FETCH`（静态检查报错）；最外层 `ORDER BY` 没有意义（外层总会重新排序），不要写（静态检查给出警告），
  顺序用 `list.defaultSort` 表达。
- 排序：请求的排序（白名单）或 `defaultSort`，最后追加稳定排序键 `list.key`；未声明 `key` 时追加全部结果列（JSON 类型的列除外）。
- 筛选：字段必须在 `list.filters` 中（否则 400 `FILTER_NOT_ALLOWED`），运算符按结果列的语义类型检查（`OPERATOR_NOT_ALLOWED`，02 §1.3），
  值按语义类型转换；`in` 绑定为一个数组参数（`= ANY`）。标识类结果列（可能是 `uuid`）按文本比较。
  排序字段不在 `list.sorts` 中 → 400 `SORT_NOT_ALLOWED`；不存在的结果列 → 400 `UNKNOWN_FIELD`。
- 行数上限取 `min(请求值, 各数据视图 maxQueryBatchSize)`。

### 5.1 执行接口

`POST /api/queries/{id}`，请求体 `{params, asOf, knownAt, filters, sorts, offset, limit, count}`（`asOf` / `knownAt` 见 19 §2.1）（`filters`/`sorts` 与数据视图查询接口相同，03 §3；
`limit` 缺省 50，`count` 缺省 true），响应 `{items, total, offset, limit}`（`items` 为按 `results` 顺序的列名 → 值）。
调用方必须具备模板声明的**全部**权限（否则 403 `PERMISSION_DENIED`）；模板不存在 404。只有注册的模板（`.sql` 与 Bean）可以经此接口执行，
`SqlDictionary` 的查询不能。
当前用户可以运行的模板及其参数 schema、结果列、版本见目录接口 `GET /api/meta/queries`（19 §3.2）。

## 6. 预编译校验（`platformCheck` 与启动自检）

对每个模板：
1. 解析头部（按 JSON Schema 校验）；检查 `entities`、`datasets`、`like`、`from`、`list` 引用都存在；
   占位符的实体与字段存在；参数都已声明且都被使用、不用保留前缀；无 `IN (:name)`；无外层 `LIMIT`；视图同一存储、`timeoutMs` 更短；
   权限已声明；不读取敏感字段（占位符与 `from` 都不行，10 §6）（`SqlTemplateRegistry`，core 的 `TemplateChecks`）。
2. 用每个相关数据视图的范围（取自请求的范围用取样值）渲染 SQL。
3. 将 `:name` 转为 `?`，通过 **JDBC** `PreparedStatement.getMetaData()` 与 `getParameterMetaData()` 在真实数据库上预编译（不执行）
   （`SqlTemplatePrecompileCheck`，连接参数取 `spring.flyway.*`；平台外层分页查询同样预编译一次）。
4. 检查：结果列名与 `results` 完全一致（未加引号）；结果列的数据库类型与声明的语义类型兼容；参数个数与类型兼容，列表参数为数组。
   兼容表（core 的 `SqlTypeCompatibility`）按语义类型的规范 Java 类型定义，因此 `Custom` 类型经其 SPI 声明的类型覆盖：

   | Java 类型 | 结果列允许的类型 | 参数允许的类型 |
   |---|---|---|
   | `String` | `text` `varchar` `bpchar` `name` `citext` `uuid` | 同左但不含 `uuid`（文本不会隐式转为 uuid） |
   | `BigDecimal` | `numeric` `int2` `int4` `int8` | 同左 |
   | `Long` / `Integer` | `int2` `int4` `int8`（`Integer` 不含 `int8`） | 另含 `numeric` |
   | `Instant` | `timestamptz` `timestamp` | 同左 |
   | `Boolean` | `bool` | 同左 |
   | `UUID` | `uuid` | 同左 |
   | `Map`（JSON） | `jsonb` `json` | 同左 |

5. 所有问题汇总后一次性报告，格式为 07 §2 的 `类别 | 定位 | 描述`，定位为 `文件路径:行号`（行号可得时；头部问题只有文件，
   数据库报错按其位置换算回模板行号）。例如 `SQL_TEMPLATE | queries/logistics/tokyo_port_audit.sql:32 | unknown field WaybillTracking.freigtCharge`。

运行时机：
- CI：`platformCheck` 任务连接迁移后的测试数据库执行。
- 启动：同样的检查在应用启动时执行（`jabiz.sql-templates.precompile-check.enabled=false` 可关闭预编译部分，生产默认开启）。

## 7. 与现有代码的衔接

| 现有 | 改动 |
|---|---|
| `AdvancedQueryExecutor.renderTemplate` | 渲染逻辑移到 core 的 `SqlTemplateRenderer`；增加时态包装和外层分页筛选（`OuterQueryCompiler`）（阶段 5 完成） |
| `RawQueryPlan`、`R2dbcStorageEngine.executeRawQuery` | 由外层包装负责 LIMIT/OFFSET；`RawQueryPlan` 不再有 `limit`，SQL 原样执行（阶段 5 完成） |
| `AdvancedQueryDefinition` | 增加 `list`（白名单）、`permissions`、`datasets`、`source`；`results` 支持 `from` 继承；参数支持 `list`、`like`（阶段 5 完成） |
| `LogisticsAnalyticsQueries` | 改写为 `queries/logistics/tokyo_port_audit.sql`（阶段 5 完成） |
