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
  启动时若有字段使用了未注册的 `kindId`，启动失败（`SemanticKindChecker`）。
- 注册方式：`java.util.ServiceLoader`（`META-INF/services/com.jabiz.entity.CustomKindSupport`），由 core 的 `CustomKinds`
  发现；`CustomKinds.register(...)` 供测试等场合手动注册。同一 `kindId` 只能由一个实现注册。
- `ext-geo` 提供 `geo.quantity`（`BigDecimal`，参数 `dimension`、`unit`）与 `geo.h3`（`Long`，参数 `resolution`）、
  字段模式 `GeoFields`、迁移守卫 `H3AreaGuard`，错误文案在 `jabiz/ext/geo/messages_*.properties`（应用在 `jabiz.i18n.bundles` 中加入）。
  core 与 runtime 不依赖 ext-geo（ArchUnit 检查）；移除 ext-geo 后二者仍可编译并通过测试。

### 1.3 查询约束

每种语义类型声明允许的查询运算符（`SemanticKinds.allowedOperators`，运算符为 `QueryOperator`）。
`QueryCompiler` 和 SQL 模板外层筛选都按此约束检查；不允许时报 `OPERATOR_NOT_ALLOWED`（400，参数 `operator`）。

| 类型 | 允许的运算符 |
|---|---|
| `SemanticIdentity`、`Reference`、`Code` | `EQ` `NE` `IN` `IS_NULL` `IS_NOT_NULL` |
| `Text` | 以上 + `LIKE` |
| `Monetary`、`Numeric`、`Temporal`、`Version` | 除 `LIKE` 外全部（含 `GT` `GTE` `LT` `LTE` `BETWEEN`） |
| `Bool` | `EQ` `NE` `IS_NULL` `IS_NOT_NULL` |
| `Custom` | 由 `CustomKindSupport.allowedOperators` 决定 |
| `None`（过渡） | 全部 |

语义类型本身带来的输入约束由 `EntityValidator` 检查：`Text.maxLength`（按字符计）→ `TOO_LONG`；
`Numeric(precision, scale)` → `NUMERIC_PRECISION`；字典编码 → `NOT_IN_DICTIONARY`（见第 5 节）。

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
  实体不存在（404）和版本冲突（409）先于业务规则判断：对过期或不存在的数据不报告规则违规。
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

- 插入时以 `from = null` 评估 `from = "*"` 且 `to = 初始状态` 的守卫；更新时只在状态改变时评估。
- 守卫返回的违规与同一变更的其他业务规则违规一起累积（422）；守卫抛出异常时记为 `GUARD_EVALUATION_FAILED`（参数 `guard`）。
- 构建期校验：必须有状态机；`from`（非 `*`）与 `to` 属于状态字典；同一实体内守卫代码唯一。

- 空间守卫改为 `ext-geo` 提供的一个 `TransitionGuard` 实现。
- 守卫是同步的；需要数据库数据的判断应在流程中先加载，再放入上下文，守卫只读上下文。

### 4.1 实体级校验（跨字段规则）【D13】

字段规则只看一个字段，迁移守卫只在状态改变时评估。"一个字段的值是否合法取决于另一个字段"这类规则用实体级校验：

```java
@FunctionalInterface
public interface EntityCheck {
    /** state 为本次写入后将要存储的完整状态（只读）；返回违规列表，空表示通过。 */
    List<Violation> check(Map<String, Object> state, ValidationContext ctx);
}

eb.check("PARAM_VALUE_INVALID", (state, ctx) -> ...);   // 代码在实体内唯一
```

- 每次插入、以及每次确实改变了值的更新，在完整候选状态（存储状态 ⊕ 本次变更）上评估；时态实体用生效时间点上的状态。
  所有写入途径（数据视图 API、流程、通用实体流程）都经过它；违规与同一变更的其他业务规则一起累积（422）。
- 校验抛出异常时记为 `CHECK_EVALUATION_FAILED`（参数 `check`）。同步、禁止 I/O（与守卫相同）。
- 校验代码按约定即其违规的 `ruleCode`，启动自检要求它在三种语言中都有文案；元模型导出 `checks`（仅代码）。
- 时态实体的变基副本（D1）与撤销写入的版本（D2）组合了不同写入的值，同样逐一评估；违反时整个写入被拒绝（422）。

## 5. 字典注册表

- `Code.dictUrn` 由字典注册表解析：

```java
public interface DictionaryProvider {
    boolean supports(String dictUrn);
    List<DictItem> items(String dictUrn, Locale locale);   // DictItem(code, label, sortOrder, enabled)
}
```

- 来源（按此顺序匹配）：业务 `DictionaryProvider` Bean（含 `StaticDictionary`：代码中声明、带 zh/ja/en 标签）→
  `Code.allowedValues` 隐含的静态字典（标签即编码）→ `SqlDictionary` Bean（SQL 模板返回 `code`、`label`，可选 `sortOrder`、`enabled`；
  声明参数 `locale` 时按语言分别执行）→ 数据库字典：平台时态实体 `SysDictItem`，表 `sys_dict_item_version(dict_urn, item_code,
  labels jsonb, sort_order, enabled + 时态系统列)`，按 `Clock` 的当前时间读取【D9】。
- 标签：请求语言 → 默认语言 → 编码本身。
- 数据库字典本身是时态实体（见 04），因此字典项也有生效时间和历史，可以预定（经数据视图 `urn:jabiz:dataset:platform:SysDictItem`
  修改，唯一约束 `(dictUrn, itemCode)`）。`labels` 的语义类型为扩展类型 `jabiz.labels`（runtime 提供，`Map<语言, 标签>`，存为 jsonb）。
- `sys_dict_item` 是当前字典项的视图（供人工查看与迁移种子）：`INSERT` 经 `jabiz_dict_put(...)` 写入**基础数据**
  （生效时间 1970-01-01，同一事务的写入为一个操作 `jabiz.sql`），不接受 UPDATE / DELETE。
- 输入校验时 `Code` 值必须属于字典的**启用**项（`NOT_IN_DICTIONARY`，400）；读取已存储的值时不校验（历史值可能已停用）；
  更新时只校验本次**改变**的编码，已存储的停用编码不妨碍修改其他字段。
  校验保持同步：运行时先加载所需字典，再以 `DictionaryLookup` 传给 `EntityValidator`。
- 注册表按字典缓存全部语言；`sys_dict_item_version` 的触发器在插入时 `pg_notify('jabiz_dict_changed', dict_urn)`，
  `DictionaryChangeListener` 独占一个连接 `LISTEN` 并失效缓存（多实例）；断线后按退避重连，重连后清空全部缓存。
  其他来源的变更可自行 `NOTIFY jabiz_dict_changed, '<urn>'`（`*` 表示全部）。SQL 字典另有 TTL（默认 5 分钟）；
  数据库字典的缓存在该字典下一个预定版本生效时（按 `Clock`）自动过期。
  只有数据库表能提供、且表中没有条目的 URN 不缓存（URN 可能直接来自请求）。
- 启动自检：`Code` 字段引用的字典必须有来源；`SqlDictionary` 引用的数据视图、实体必须存在。
- API：`GET /api/dictionaries/{urn}`，按 `Accept-Language` 返回 `[{code, label, sortOrder, enabled}]`。

## 6. 唯一性声明

```java
eb.unique("uk_user_name", "userName");            // 可多字段
```

- 普通实体：建与约束**同名**的数据库唯一索引，启动自检检查索引存在且列集合一致（非部分索引、非表达式索引）。
  写入违反该索引时返回 `UNIQUE_VIOLATION`（400，字段为约束的第一个字段）；违反未声明的唯一索引（如重复主键）返回 409。
- 时态实体：见 04 第 7 节（咨询锁 + 当前版本检查）。

### 6.1 敏感字段

`f.sensitive()` 标记秘密（如密码哈希）：读接口不返回，数据视图 API 与通用实体流程不接受写入（400 `SENSITIVE_FIELD`，只有专用流程能写），
列表视图不得显示、筛选、排序它，SQL 模板不得读取它；导出为 `sensitive: true`，JSON Schema 中 `writeOnly: true`。
日志与操作记录中按名字遮蔽（10 §6）。

## 7. 列表视图元数据

为前端和 SQL 模板外层筛选提供统一描述：

```java
eb.listView("default", lv -> lv
    .columns("waybillId", "status", "freightCharge", "shippedTime")
    .filters("status", "shippedTime", "freightCharge")
    .sorts("shippedTime", "freightCharge")
    .defaultSort("shippedTime", false));
```

- 只有列在 `filters` / `sorts` 中的字段允许被筛选、排序（白名单；违反时 `FILTER_NOT_ALLOWED` / `SORT_NOT_ALLOWED`，400）。
- 未指定排序时用 `defaultSort`；`QueryCompiler` 总是在最后追加主键升序（已按主键排序时除外），保证分页稳定。
- 数据视图用 `d.listView(name)` 选择列表视图，缺省为 `default`；实体没有该列表视图时，查询不允许任何筛选和排序。

## 8. 导出

`GET /api/meta/entities/{name}` 返回：实体名、主键、是否时态（`temporal`；时态实体另有 `allowScheduled`，系统字段标记为系统维护）、字段（逻辑名、
语义类型及参数、必填、不可变、系统维护、允许的运算符、可导出规则）、引用、状态机、守卫（仅 code/from/to）、唯一约束、
列表视图、字典引用（`dictionaries`）。

另提供 `GET /api/meta/schema/{name}` 返回实例属性的 JSON Schema（draft 2020-12，`JsonSchemaExporter`）：
系统维护与生成的字段 `readOnly`；必填（非生成、非系统）字段列入 `required`；非必填字段允许 null；
`additionalProperties: false`；平台扩展关键字 `x-jabiz-dictionary`、`x-jabiz-reference`、`x-jabiz-kind`。
