# 05 SQL 模板

复杂查询仍然手写 SQL（保留"配置驱动、快速修改上百条 SQL"的能力），但表名和列名经元数据解析、
数据视图范围和时态处理自动附加、参数和结果带语义类型，并在启动时和 CI 中预编译校验。

## 1. 两种声明方式，同一个定义

- **`.sql` 文件（推荐，大多数查询）**：放在 `src/main/resources/queries/**/*.sql`，一个文件一个查询。
- **Java DSL**：现有 `AdvancedQueryDefinition.define(...)`，用于需要在代码中组合的少数情况。

两者都编译为同一个 `AdvancedQueryDefinition`，由同一个渲染器和执行器处理。

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
| `entities` | 模板中允许引用的实体；引用未列出的实体 → 启动失败 |
| `params` | 参数：`like: 实体.字段` 继承该字段的语义类型，或 `kind:` 显式声明；`list: true` 表示数组参数 |
| `results` | 结果列：`from: 实体.字段` 继承语义类型并记录血缘，或 `kind:` 显式声明。**结果列必须全部声明** |
| `list` | 外层筛选、排序白名单与默认排序（只能引用 `results` 中的名字） |
| `permissions` | 执行所需权限；未声明 → 非开发环境启动失败 |
| `timeoutMs` | 可选，只能比数据视图的超时更短 |

## 3. 占位符

| 占位符 | 渲染结果 |
|---|---|
| `{{Entity}}` | 该实体在当前数据视图中的表达式：普通实体为表名，或 `(SELECT * FROM 表 WHERE 范围条件)`；时态实体为 04 第 5.1 节的"当前版本"子查询（范围条件在外层【决策 D3】） |
| `{{Entity.field}}` | 该逻辑字段的物理列名（不带表别名），经 `SqlIdentifiers` 校验 |

- 模板中必须给 `{{Entity}}` 起别名。
- 禁止在模板中直接写物理表名、物理列名（`platformCheck` 会检查模板中出现的裸标识符是否与已知物理名冲突并给出警告）。

## 4. 参数

- 使用命名参数 `:name`；值按参数的语义类型转换和校验（现有 `FieldValueCoercer`），`Code` 类型会校验字典。
- **列表参数统一写 `= ANY(:name)`**（取反写 `<> ALL(:name)`），头部声明 `list: true`【决策 D7】。
  平台按语义类型把列表转为对应数组（`String[]`、`UUID[]`、`BigDecimal[]`、`Instant[]` 等）后绑定。
  禁止 `IN (:name)`，`platformCheck` 发现即报错。空数组时 `= ANY` 为假、`<> ALL` 为真，平台不做特殊处理。
- 平台保留的参数：`scope_` 前缀（数据视图范围）、`__` 前缀（时态：`__asOf`、`__knownAt`；分页）。业务参数不得使用。

## 5. 外层分页、筛选、排序

执行列表查询时，平台把渲染后的模板作为子查询：

```sql
SELECT * FROM ( <渲染后的模板> ) q
WHERE <白名单内的筛选条件，基于 results 的语义类型检查运算符>
ORDER BY <白名单内的排序，缺省为 defaultSort，最后追加稳定排序键>
LIMIT :__limit OFFSET :__offset
```

- 另生成 `SELECT count(*) FROM ( <渲染后的模板> ) q WHERE ...` 用于总数（可由调用方关闭）。
- 模板**不得**在最外层包含 `LIMIT` / `OFFSET`；最外层 `ORDER BY` 在有外层排序时无意义，建议不写。
- 行数上限取 `min(请求值, 数据视图 maxQueryBatchSize)`。

## 6. 预编译校验（`platformCheck` 与启动自检）

对每个模板：
1. 解析头部（按 JSON Schema 校验）；检查 `entities`、`like`、`from`、`list` 引用都存在。
2. 用每个相关数据视图的范围（取样值）渲染 SQL。
3. 将 `:name` 转为 `?`，通过 **JDBC** `PreparedStatement.getMetaData()` 与 `getParameterMetaData()` 在真实数据库上预编译（不执行）。
4. 检查：结果列名与 `results` 完全一致；结果列的数据库类型与声明的语义类型兼容（兼容表由平台维护，如 `Monetary` ↔ `numeric`）；
   参数个数与类型兼容。
5. 所有问题汇总后一次性报告，格式：`文件路径: 行号(若可得): 问题描述`。

运行时机：
- CI：`platformCheck` 任务连接迁移后的测试数据库执行。
- 启动：同样的检查在应用启动时执行（可配置关闭，生产默认开启）。

## 7. 与现有代码的衔接

| 现有 | 改动 |
|---|---|
| `AdvancedQueryExecutor.renderTemplate` | 渲染逻辑移到 core 的 `SqlTemplateRenderer`；增加时态包装和外层分页筛选 |
| `RawQueryPlan`、`R2dbcStorageEngine.executeRawQuery` | 由外层包装负责 LIMIT/OFFSET；移除"在末尾追加 LIMIT"的做法 |
| `AdvancedQueryDefinition` | 增加 `list`（白名单）、`permissions`；`results` 支持 `from` 继承 |
| `LogisticsAnalyticsQueries` | 改写为 `.sql` 文件，作为示例 |
