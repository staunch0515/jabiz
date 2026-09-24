## Base Query

查询的本质是一个“逻辑语义编译与上下文合并”的流水线：

1. **客户端/调用方**提交面向逻辑字段名（如 `status == "IN_TRANSIT" && freightCharge > 5000`）的**通用查询描述符（Criteria / AST）**。
2. **编译器**结合 `DatasetDefinition`（注入租户隔离、只读分片路由、最大分页安全边界）和 `EntityDefinition`（将逻辑字段编译为底层物理列名、验证操作符是否符合 `SemanticKind`）。
3. **存储引擎**执行查询并由管理器完成数据水化（Hydration）。

### 关键架构收益

1. **自动防注入与物理脱敏：** 无论上层传入什么条件，`QueryCompiler` 会自动把 `status` 转换为 `f_status_code`，把 `freightCharge` 转换为 `f_charge_amt`，全量参数化绑定。

2. **强制上下文防护：** `DatasetDefinition` 中预设的租户隔离、只读关区约束（`defaultPartitionFilter`）和分页截断（`maxQueryBatchSize`）被强制注入编译结果中，上层调用者无法通过写特权查询绕过底线安全策略。

3. **读写分离透明化：** 如果 `DatasetDefinition` 配备了 `readReplicaRef`，查询流量会自动流向只读节点，无需在上层业务代码里显式切换数据源。

   

```java
// 1. 构建完全面向领域的逻辑查询：
// 查找：状态为 IN_TRANSIT 且运费大于 50000 日元的运单，按系统记录时间倒序，分页取前 20 条
EntityQuery query = EntityQuery.builder()
    .where(new QueryPredicate.And(List.of(
        new QueryPredicate.Eq("status", "IN_TRANSIT"),
        new QueryPredicate.Gt("freightCharge", 50000.0)
    )))
    .orderBy("recordedTime", false)
    .limit(20)
    .build();

// 2. 交付管理器执行
List<EntityInstance> results = entityManager.query(waybillDataset, waybillDefinition, query);

for (EntityInstance waybill : results) {
    System.out.printf("Waybill ID: %s, Freight: %s, Current Location: %s%n",
        waybill.id(),
        waybill.get("freightCharge"),
        waybill.get("currentLocation")
    );
}
```



## Custom Query

此时，**自定义高级查询定义（View / Query Definition）本身也应该作为系统的一级元数据资源**。

这个设计的核心思想是：

1. **入参声明（Parameter Spec）**：显式声明 SQL 可以接收哪些具名参数，限定其 `SemanticKind`、默认值及是否必填，杜绝未授权注入。
2. **SQL 模板与占位符（SQL Template）**：SQL 中的表名采用逻辑实体占位符（如 `{{WaybillTracking}}`），在运行时由 `DatasetDefinition` 动态替换为底层实际分表，保持多租户与分片透明。
3. **输出投影（Projection Spec）**：结果集的每一个字段都赋予明确的 `SemanticKind`，让上层业务消费、前端动态渲染以及通用安全脱敏具备元数据上下文。

### 方案设计优势

1. **语义不丢失**：即使经过复杂的 SQL 聚合、JOIN 投影，出参依旧保留量纲（`PhysicalQuantity`）、货币（`Monetary`）及空间坐标（`SpatialH3`）等元数据，前端或下游服务直接知道如何做单位换算和地图打点。
2. **多租户分表解耦**：SQL 模板中不写死表名，而是使用 `{{WaybillTracking}}` 逻辑标识。不同年份、不同关区或不同租户在切换 `DatasetDefinition` 时，无需改动 SQL 逻辑。
3. **入参强制校验防腐**：利用 `SemanticKind` 强校验入参是否在字典内、是否符合数值与时间语义，将非法参数阻断在物理 SQL 执行之前

```java
// 1. 初始化执行器
AdvancedQueryExecutor queryExecutor = new AdvancedQueryExecutor(storageRegistry);

// 2. 准备实体定义表（通过解析器拿到涉及的两个 EntityDefinition）
Map<String, EntityDefinition> entityMap = Map.of(
    "WaybillTracking", WaybillEntityDefinitions.WAYBILL,
    "CustomsDeclaration", CustomsEntityDefinitions.CUSTOMS_DECLARATION
);

// 3. 传入强类型业务参数
Map<String, Object> params = Map.of(
    "minFreight", 30000.0,
    "allowedStatuses", List.of("CUSTOMS_CLEARED")
);

// 4. 执行高级多实体查询
List<SemanticRow> rows = queryExecutor.execute(
    WaybillDatasetDefinitions.TOKYO_PORT_WAYBILLS_2026,
    LogisticsAnalyticsQueries.TOKYO_PORT_WAYBILL_CUSTOMS_AUDIT,
    entityMap,
    params
);

// 5. 消费具备语义上下文的结果
for (SemanticRow row : rows) {
    SemanticValue waybillSn = row.get("waybillSn");
    SemanticValue freight = row.get("finalFreight");
    SemanticValue h3Cell = row.get("customsPortCell");

    System.out.printf("运单: %s, 运费: %s (%s), 关区 H3 单元: 0x%016x%n",
        waybillSn.value(),
        freight.value(),
        ((SemanticKind.Monetary) freight.kind()).currency(), // JPY
        ((Number) h3Cell.value()).longValue()
    );
}
```

## Real-world invocation scenarios involving cross-entity, heterogeneous batch submissions.


```java

// 1. 初始化并注册所有领域实体元定义
EntityDefinitionRegistry entityRegistry = new EntityDefinitionRegistry();
entityRegistry.register(WaybillEntityDefinitions.WAYBILL);                      // WaybillTracking
entityRegistry.register(CustomsLogEntityDefinitions.CUSTOMS_LOG);              // CustomsClearanceLog
entityRegistry.register(CustomsDeclarationEntityDefinitions.CUSTOMS_DECLARATION);// CustomsDeclaration
entityRegistry.register(HoldLockEntityDefinitions.HOLD_LOCK);                  // CustomsHoldLock

DatasetEntityManager entityManager = new DatasetEntityManager(storageRegistry, entityRegistry);

// 2. 构造一次业务事件触发的多实体异构变更集合 (Heterogeneous ChangeSet)
List<EntityInstance> businessTransaction = List.of(
    // A. 更新运单：状态由 IN_TRANSIT 跃迁到 CUSTOMS_CLEARED（触发运单的状态机与 H3 空间守卫）
    new EntityInstance("WB-2026-0001", "WaybillTracking", 3, "IN_TRANSIT", Map.of(
        "action", "UPDATE",
        "status", "CUSTOMS_CLEARED",
        "currentLocation", 0x882f516a23ffffL
    )),

    // B. 新增一条清关审计日志实体
    new EntityInstance(UUID.randomUUID().toString(), "CustomsClearanceLog", 0, null, Map.of(
        "action", "INSERT",
        "waybillId", "WB-2026-0001",
        "inspectorId", "OFFICER_9981",
        "clearedAtH3", 0x882f516a23ffffL
    )),

    // C. 更新关联的申报单：标记完税放行状态
    new EntityInstance("DECL-JP-8802", "CustomsDeclaration", 1, "PENDING", Map.of(
        "action", "UPDATE",
        "declarationStatus", "RELEASED",
        "dutyPaid", 15000.0
    )),

    // D. 物理/逻辑删除通关前的临时安全锁实体
    new EntityInstance("LOCK-WB-2026-0001", "CustomsHoldLock", 1, "LOCKED", Map.of(
        "action", "DELETE"
    ))
);

// 3. 一次性提交到东京关区数据集
List<EntityInstance> committed = entityManager.commitBatch(tokyoPortDataset, businessTransaction);
```

