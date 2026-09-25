# 01 分层：纯 Java 核心层 与 响应式运行时层

## 1. 目标

- 平台的"大脑"（元模型、校验、规则、查询编译、模板渲染）与执行方式无关，可单独测试、可复用。
- 响应式的复杂度**集中在平台内部**，业务开发者只面对同步接口。
- 保留选择余地：将来若需要阻塞式（Spring MVC）版本，只需补写一个薄的运行时层。

## 2. 分层与依赖方向

```
业务模块  ──►  jabiz-core（声明、同步扩展点）
   │
   └──(仅通过 Spring 装配)──►  jabiz-runtime  ──►  jabiz-core
```

- `jabiz-core` 不依赖 `jabiz-runtime`。
- 业务模块只依赖 `jabiz-core` 的公开 API；运行时由 Spring 自动装配提供。
- `jabiz-runtime` 不包含任何业务概念。

## 3. 现有类的归属

包结构约定（阶段 2 落实）：core 的类保留原包名 `com.jabiz.<子包>`；**runtime 的类一律位于 `com.jabiz.runtime..`**；
业务模块位于自己的包（示范应用为 `com.jabiz.app..`）。同一个包不跨模块（无 split package），ArchUnit 按包判断层。

| 现有类（`com.jabiz.*`） | 归属 |
|---|---|
| `entity.*`（EntityDefinition、SemanticKind、FieldValueCoercer、EntityValidator、FieldRule、RuleSpec、StateTransition*、Builder 类、MetaModelExporter、Violation、ValidationException …） | core |
| `entity.GenericRowMapper`（依赖 `io.r2dbc.spi.Row`） | runtime（`runtime.entity`） |
| `entity.MetaModelConsistencyChecker`、`entity.EntityDefinitionRegistry`（Spring 组件） | runtime（`runtime.entity`） |
| `entity.WaybillEntityDefinitions`、`CustomsDeclarationEntityDefinitions`、`PriceEntityDefinitions`、`EntityDefinitionsConfig` | 业务模块（示范应用 `app`） |
| `dataset.DatasetDefinition` / `DatasetPolicy` / `StorageRouting` | core |
| `dataset.DatasetRegistry` | runtime（`runtime.dataset`） |
| `dataset.DatasetsConfig`（示范实体的数据视图） | 业务模块（`app`） |
| `query.QueryCompiler`、`QueryPredicate`、`EntityQuery`、`PhysicalQueryPlan`、`RawQueryPlan`、`BoundValue`、`SqlIdentifiers` | core（`QueryCompiler` 由 runtime 的自动配置注册为 Bean） |
| `query.custom.AdvancedQueryDefinition`、`QueryParameter`、`ProjectedField`、`SemanticRow/Value` | core |
| `query.custom.LogisticsAnalyticsQueries` | 业务模块（`app`） |
| `query.custom.AdvancedQueryExecutor` | runtime（`runtime.query`；模板渲染逻辑在阶段 5 抽到 core 的 `SqlTemplateRenderer`） |
| `runtime.*`（`DatasetEntityManager`、领域异常、`EntityInstance/EntityChange/EntityAction`）、`storage.*` | runtime（`runtime`、`runtime.storage`） |
| `process.ProcessDefinition`、`ProcessDefinitionBuilder`、`StepDefinition`、`ProcessContext`、`NoMetadata`；新增 `StepImplementation` | core |
| `process.ProcessExecutor`、`ProcessRegistry`、`ProcessSequence` 及其实现、`StepHandler` | runtime（`runtime.process`） |
| `process.entity.*`、`process.sponsor.*`（其步骤实现是 `StepHandler`） | runtime（`runtime.process.entity`、`runtime.process.sponsor`；登录流程在阶段 7 重写） |
| `resource.ResourceId`、`Resource`、`GenericResource`、`ProcessResource`、资源异常 | core |
| `resource.*Resolver`、`ResourceRegistry`（返回 `Mono`） | runtime（`runtime.resource`） |
| `web.*`、`MetaModelController`、`app.SpaFallbackFilter`、`config.ClockConfig` | runtime（`runtime.web`、`runtime.config`） |

runtime 通过 Spring Boot 自动配置（`JabizRuntimeAutoConfiguration`）进入应用；业务模块只扫描自己的包，
并用 `JabizApplication.run(...)` 启动（它在 Reactor 加载前设置第 6 节的虚拟线程属性）。

`ProcessContext` 目前使用 `ConcurrentHashMap`，保留即可（步骤可能在不同线程完成）。

## 4. 业务扩展点（全部同步）

| 扩展点 | 形式 | 说明 |
|---|---|---|
| 字段规则 | `RulePredicate#test(value, ValidationContext)` | 已是同步 |
| 迁移守卫 | `TransitionGuard#check(from, to, current, incoming, ValidationContext)` | 取代 `SpatialGuardRule` |
| 计算步骤 | `ComputeStep<M, C>#compute(M, C)` | 纯计算，读写上下文、登记变更 |
| 阻塞步骤 | `BlockingStep<M, C>#run(M, C)` | 调用只有阻塞 SDK 的外部服务，由平台在虚拟线程上执行 |
| 字典提供者 | `DictionaryProvider#values(dictUrn, locale)` | 同步；平台负责缓存 |
| 语义类型扩展 | `SemanticKind.Custom` + `CustomKindSupport` SPI | 同步的转换、校验、导出 |

平台内部的 `StepHandler<M, C>`（返回 `Mono<Void>`）只用于平台提供的 I/O 步骤，**不作为业务扩展点公开**。

`StepDefinition` 通过 core 中的标记接口 `StepImplementation<M, C>`（无方法）引用步骤实现类，因此流程定义留在 core 而不依赖 Reactor。
`StepHandler`、`ComputeStep`、`BlockingStep` 都扩展它，各自声明执行方法；`ProcessExecutor` 在启动时检查每个步骤类都有对应的执行方式。

## 5. 请求上下文

`RequestContext`（core 中的纯 record）：

```java
public record RequestContext(
    String actorId,          // 操作人；系统任务使用固定的系统身份
    String tenantId,         // 可为 null
    Locale locale,           // 错误文案、字典标签的语言
    String requestId,        // 链路追踪
    Set<String> roles,
    Set<String> permissions
) {}
```

- 运行时在 Web 过滤器中构造，放入 Reactor Context；平台代码通过 `Mono.deferContextual` 取用。
- 传给同步扩展点时，放入 `ValidationContext`（扩展为 `ValidationContext(Clock clock, RequestContext request)`）和 `ProcessContext`。
- 用于：审计字段、`op_process.actor_id`、数据视图的动态范围、权限检查、错误文案语言。
- 打开 Micrometer 的上下文传播（`Hooks.enableAutomaticContextPropagation()`），保证日志 MDC 中有 `requestId`。
- **缺少 `RequestContext` 即报错（默认拒绝）**：平台代码取不到上下文时不猜测身份；定时任务等非请求调用必须显式写入
  `RequestContext.system(...)`。
- 构造规则（`RequestContextWebFilter`）：
  - `requestId`：请求头 `X-Request-Id` 合法（`[A-Za-z0-9._-]{1,64}`）时沿用，否则生成；写回响应头 `X-Request-Id`。
  - `locale`：按 `Accept-Language` 在支持的语言（zh、ja、en）中匹配，匹配不到用 `jabiz.i18n.default-locale`（默认 `en`）。
  - 操作人（阶段 7 之前）：默认 `anonymous`，无角色、无权限。开发环境可用请求头 `X-Jabiz-Actor`、`X-Jabiz-Tenant`、
    `X-Jabiz-Roles`、`X-Jabiz-Permissions`（逗号分隔）指定，**仅当** `dev` profile 激活且 `jabiz.dev.actor-headers=true`；
    该属性在非 `dev` profile 下为 true 时启动失败。
- `ProcessContext.request()` 随流程引擎（06 §3，阶段 6）加入。

## 6. 阻塞调用

- 业务的 `BlockingStep` 由平台包装为 `Mono.fromRunnable(...).subscribeOn(Schedulers.boundedElastic())`。
- 配置 `reactor.schedulers.defaultBoundedElasticOnVirtualThreads=true`（Reactor 3.6+），让 `boundedElastic` 运行在虚拟线程上。
- `BlockingStep` **不得访问本平台数据库**；它的结果写回上下文，由平台 I/O 步骤持久化。

## 7. 强制手段

- **ArchUnit**：
  - `jabiz-core` 中任何类不得依赖 `reactor..`、`io.r2dbc..`、`org.springframework.r2dbc..`、`org.springframework.web..`。
  - 业务模块不得依赖 `reactor..`。
  - `jabiz-core` 不得依赖 `jabiz-runtime`。
- **BlockHound**：测试环境安装，任何在非阻塞线程上的阻塞调用使测试失败。
- **代码审查**：PR 中新增的公开扩展点若返回 `Mono`/`Flux`，视为违反本文档。
- **构建**：`jabiz-core` 的主代码没有任何依赖（无 Spring、Reactor、R2DBC），违反分层在编译期即失败。

## 8. 表结构迁移

- 平台自己的表、序列、函数由 runtime 提供迁移脚本：`classpath:db/jabiz/V<n>__*.sql`，历史表 `jabiz_schema_history`。
- 业务表由业务模块提供：`classpath:db/migration`（`spring.flyway.*` 配置），历史表为 Flyway 默认的 `flyway_schema_history`。
- 两套脚本各自编号，互不占用版本号；启动时**先平台、后业务**（`PlatformSchemaMigration`），因此业务表可以引用平台表。
- 双方都允许在"非空 schema"上以版本 0 建立基线，因此已有数据库和全新数据库都能正确迁移（所有真实脚本版本 ≥ 1）。
- Flyway 使用 JDBC，只在启动时运行，不在请求路径上。
