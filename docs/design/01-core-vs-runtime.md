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

| 现有类（`com.jabiz.*`） | 归属 |
|---|---|
| `entity.*`（EntityDefinition、SemanticKind、FieldValueCoercer、EntityValidator、FieldRule、RuleSpec、StateTransition*、Builder 类、MetaModelExporter、Violation、ValidationException …） | core |
| `entity.GenericRowMapper`（依赖 `io.r2dbc.spi.Row`） | runtime（或改为接收 `Map` 后留在 core） |
| `entity.MetaModelConsistencyChecker`、`entity.EntityDefinitionRegistry`（Spring 组件） | runtime（注册表的纯逻辑可留在 core） |
| `dataset.DatasetDefinition` / `DatasetPolicy` / `StorageRouting` | core |
| `dataset.DatasetRegistry`、`DatasetsConfig` | runtime |
| `query.QueryCompiler`、`QueryPredicate`、`EntityQuery`、`PhysicalQueryPlan`、`RawQueryPlan`、`BoundValue`、`SqlIdentifiers` | core |
| `query.custom.AdvancedQueryDefinition`、`QueryParameter`、`ProjectedField`、`SemanticRow/Value` | core |
| `query.custom.AdvancedQueryExecutor` | runtime（模板渲染逻辑抽到 core 的 `SqlTemplateRenderer`） |
| `runtime.*`、`storage.*` | runtime |
| `process.ProcessDefinition`、`ProcessDefinitionBuilder`、`StepDefinition`、`ProcessContext` | core |
| `process.ProcessExecutor`、`ProcessRegistry`、`ProcessSequence` 实现、`StepHandler` | runtime |
| `web.*`、`MetaModelController`、`resource.*` | runtime |

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
