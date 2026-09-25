# 02 元模型

元模型用 **Java DSL** 声明（`EntityDefinition.define(...)`），不用 YAML：规则可以直接写 lambda，编译期检查，方便重构。
元模型可导出为 JSON（给前端）和 JSON Schema（给校验工具与 AI）。

## 1. 语义类型（SemanticKind）

字段声明的是**业务语义**，而不只是存储类型。同一份语义驱动：值转换、校验、查询约束、前端渲染、导出。

### 1.1 核心类型（`jabiz-core`，sealed）

| 类型 | 参数 | 规范 Java 类型 | 说明 |
|---|---|---|---|
| `SemanticIdentity` | `urn` | `String` / `UUID` | 业务标识，默认不可变、必填 |
| `Monetary` | `currency`、`scale` | `BigDecimal` | 金额，禁止 double |
| `Temporal` | `role`：`EVENT_TIME` / `SYSTEM_RECORDED` / `VALID_FROM` / `VALID_TO` | `Instant` | 时间及其角色 |
| `Code` | `dictUrn`、`allowedValues`（可为空，表示由字典注册表提供） | `String` | 字典编码；禁止范围比较 |
| `Version` | — | `Long` | 乐观锁版本（普通实体）或 `version_no`（时态实体） |
| `Text` | `maxLength`、`multiline` | `String` | 普通文本（新增） |
| `Numeric` | `precision`、`scale` | `BigDecimal` | 非金额数值（新增） |
| `Bool` | — | `Boolean` | 布尔（新增） |
| `Reference` | `targetEntity` | 目标主键类型 | 指向另一实体（新增） |
| `Custom` | `kindId`、`params` | 由 SPI 决定 | 扩展点 |
| `None` | — | 原样 | 未声明语义（仅用于过渡，新代码不应使用） |

### 1.2 扩展类型

- `PhysicalQuantity`、`SpatialH3` 移到 `backend/ext-geo`，以 `Custom` + SPI 实现。
- SPI：

```java
public interface CustomKindSupport {
    String kindId();                                   // 例如 "geo.h3"
    Object coerce(Map<String, Object> params, Object raw, boolean forInput);
    Class<?> javaType(Map<String, Object> params);
    Set<String> allowedOperators(Map<String, Object> params);  // 查询时允许的比较
    Map<String, Object> export(Map<String, Object> params);    // 导出给前端
}
```

- `FieldValueCoercer`、`QueryCompiler`、`MetaModelExporter` 对 `Custom` 委托给注册的 `CustomKindSupport`；
  启动时若有字段使用了未注册的 `kindId`，启动失败。

### 1.3 查询约束

每种语义类型声明允许的查询运算符（例如 `Code` 只允许 `=`、`<>`、`IN`；`Text` 允许 `LIKE`）。
`QueryCompiler` 和 SQL 模板外层筛选都按此约束检查。

## 2. 逻辑名与物理名分离

- API、查询、规则、模板中一律使用**逻辑名**；物理列名只出现在元数据中（`FieldBuilder.physicalColumn`）。
- 物理表名、列名都经过 `SqlIdentifiers.require` 校验。
- 物理列从不导出给前端（`MetaModelExporter` 已遵守）。
- 同一物理列不得被两个字段映射（`EntityBuilder.validateUniqueColumns` 已实现）。
- 用途：对接遗留表而不改表结构；物理重命名时业务代码不变。

## 3. 规则只定义一次

现有 `FieldBuilder.rule(code, kind, params, predicate)` 已同时登记：
- `RuleSpec(code, kind, params)`：纯数据，导出给前端，用于客户端校验；
- `FieldRule(code, predicate)`：服务端实现。

约定：
- 可导出的规则种类（`kind`）必须是前端认识的有限集合：`RANGE`、`SCALE`、`LENGTH`、`PATTERN`、`NOT_FUTURE`、`REQUIRED`。
  新增种类必须同时实现前端校验器。
- 依赖运行时服务（时钟、数据库、外部查询）的规则使用"仅服务端"重载，不导出。
- 规则代码（`code`）同时是错误码，必须能在多语言资源中找到文案（启动自检检查缺失）。

### 3.1 错误累积与多语言

- 校验阶段已累积全部违规（`EntityValidator`）；**业务规则阶段也改为累积**：同一个变更（一次 INSERT / UPDATE / DELETE）中的
  范围违规、不可变字段违规、非法状态迁移、初始状态违规、迁移守卫失败，收集后一次抛出。
  前一个变更失败时不再执行后续变更（它们可能依赖前者的结果）。
- `Violation(field, ruleCode, message, params)`：`field` 可为 null（与字段无关的违规，如只读视图）；`params` 是文案占位参数。
- 异常与状态码：`ValidationException`（400）与 `BusinessRuleViolationException`（422）都携带 `violations`，
  `ProblemDetail` 中的 `violations[]` 为 `{field, ruleCode, message}`。
- `message` 按 `RequestContext.locale` 从资源 `messages_{zh,ja,en}.properties` 解析（core 的 `MessageCatalog`，UTF-8）：
  - 平台错误码在 `jabiz/messages_*.properties`（jabiz-core 提供）；业务错误码在业务模块的 `messages_*.properties`；
  - 占位符为命名形式 `{name}`，取值来自 `Violation.params`（规则违规时即 `RuleSpec.params`），`{field}` 为字段逻辑名；
  - 找不到文案时使用 `ruleCode` 本身。
- 启动自检：平台错误码和所有已注册实体的规则代码，在 zh、ja、en 三种语言中都必须有文案，缺失时一次性列出并拒绝启动。

## 4. 状态机与迁移守卫

现有能力（保留）：
- `eb.stateTransitions(statusField, st -> st.from(A).to(B, C) ...)`；
- 状态字段必须是 `Code`，状态值必须属于字典（构建时校验）；
- 初始状态自动推导（有出边无入边）；插入时校验初始状态；更新时校验迁移合法；状态不能被清空。

改动：`SpatialGuardRule` 泛化为 **迁移守卫**：

```java
@FunctionalInterface
public interface TransitionGuard {
    /** 返回违规列表；空表示通过。current 为迁移前的值，incoming 为本次变更。 */
    List<Violation> check(String from, String to,
                          Map<String, Object> current, Map<String, Object> incoming,
                          ValidationContext ctx);
}

eb.guard("guardCode", fromOrAny, to, guard);   // from 可以是 "*"
```

- 空间守卫改为 `ext-geo` 提供的一个 `TransitionGuard` 实现。
- 守卫是同步的；需要数据库数据的判断应在流程中先加载，再放入上下文，守卫只读上下文。

## 5. 字典注册表

- `Code.dictUrn` 由字典注册表解析：

```java
public interface DictionaryProvider {
    boolean supports(String dictUrn);
    List<DictItem> items(String dictUrn, Locale locale);   // DictItem(code, label, sortOrder, enabled)
}
```

- 内置提供者：代码中的静态字典（`allowedValues` 非空时）、数据库字典表 `sys_dict_item`、SQL 字典（SQL 模板返回 code/label）。
- 数据库字典本身是时态实体（见 04），因此字典项也有生效时间和历史。
- 输入校验时 `Code` 值必须属于字典的有效项；读取已存储的值时不校验（历史值可能已停用）。
- 注册表带缓存；字典变更时通过 PostgreSQL `LISTEN/NOTIFY` 失效缓存（多实例）。

## 6. 唯一性声明

```java
eb.unique("uk_user_name", "userName");            // 可多字段
```

- 普通实体：建数据库唯一索引，启动自检检查索引存在。
- 时态实体：见 04 第 7 节（咨询锁 + 当前版本检查）。

## 7. 列表视图元数据

为前端和 SQL 模板外层筛选提供统一描述：

```java
eb.listView("default", lv -> lv
    .columns("waybillId", "status", "freightCharge", "shippedTime")
    .filters("status", "shippedTime", "freightCharge")
    .sorts("shippedTime", "freightCharge")
    .defaultSort("shippedTime", false));
```

- 只有列在 `filters` / `sorts` 中的字段允许被筛选、排序（白名单）。
- 未指定排序时，按主键升序（保证分页稳定，现有 `QueryCompiler` 已实现）。

## 8. 导出

`GET /api/meta/entities/{name}` 返回：实体名、主键、字段（逻辑名、语义类型及参数、必填、不可变、可导出规则）、
状态机、列表视图、是否时态、字典引用。另提供 `GET /api/meta/schema/{name}` 返回 JSON Schema。
