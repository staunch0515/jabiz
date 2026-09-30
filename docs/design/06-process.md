# 06 流程引擎

## 1. 目标

- 所有有业务含义的写入都通过流程完成：流程是"一次操作"的单位，对应 `op_process` 的一行。
- 流程强类型（输入、输出、上下文）、带版本、可组合。
- **平台负责 I/O，业务只写同步计算**：业务开发者不接触 `Mono` / `Flux`。
- 默认一个流程一个事务。

现有 `ProcessDefinition` / `ProcessDefinitionBuilder` / `StepDefinition` / `ProcessExecutor` / `ProcessRegistry` 保留并扩展。

## 2. 三种步骤

| 步骤 | 接口 | 谁来写 | 执行方式 |
|---|---|---|---|
| **计算步骤** | `ComputeStep<M, C>#compute(M metadata, C ctx)` | 业务开发者 | 同步，在响应式线程上执行，**禁止任何 I/O** |
| **平台 I/O 步骤** | 内部 `StepHandler<M, C>`（返回 `Mono`） | 平台 | 响应式 |
| **阻塞步骤** | `BlockingStep<M, C>#run(M metadata, C ctx)` | 业务开发者 | 平台用 `subscribeOn(boundedElastic)` 执行（虚拟线程），**禁止访问本平台数据库** |

### 2.1 平台提供的 I/O 步骤

| 步骤 | 作用 |
|---|---|
| `LoadEntity` | 按主键（取自上下文的键）经指定数据视图加载实体，放入上下文 |
| `QueryEntities` / `RunTemplate` | 执行实体查询或 SQL 模板，结果放入上下文 |
| `SaveChanges` | 立即提交上下文中已登记的变更（通常不需要，见 3.1） |
| `CallProcess` | 调用另一个流程（同一事务，见第 5 节） |
| `PublishEvent` | 写入 Outbox（事务提交后由投递器发送） |

实现（`com.jabiz.runtime.process.steps`）：每个平台步骤提供返回 `StepSpec` 的静态工厂，业务代码直接写
`.step("加载订单", LoadEntity.by(数据视图, 上下文中主键的键, 结果键))`，不必写类型参数：

| 工厂 | 放入上下文的结果 |
|---|---|
| `LoadEntity.by(datasetId, idKey, targetKey)` / `LoadEntity.optional(...)` | `EntityInstance`（`by` 找不到时 404） |
| `QueryEntities.of(datasetId, ctx -> EntityQuery, targetKey)` | `List<EntityInstance>` |
| `RunTemplate.of(templateId, ctx -> 参数, targetKey)` | `List<Map<列名, 值>>`（不检查模板权限，范围照常施加【D11】） |
| `SaveChanges.now()` | 已提交的状态在 `ctx.changes().saved()`；已有违规时拒绝提交 |
| `CallProcess.of(name, version, ctx -> 输入, outputKey)` / `CallProcess.latest(...)` / `CallProcess.when(条件, name, version, …)` | 子流程输出（`when` 的条件不成立时不调用）【D14】 |
| `CallProcess.forEach(name, version, ctx -> 输入列表, outputKey)` | 对列表中每个输入依次调用一次（列表为空时不调用），输出为同序的列表（阶段 13e） |
| `LoadParams.of(ctx -> 业务时间, targetKey, key…)` | `ParamValues`：各业务参数在该时间点生效的值（04 §9；缺失 → 422 `PARAM_NOT_FOUND`） |
| `AssignNumber.of(序列, [ctx -> 范围,] targetKey)` / `AssignNumber.when(条件, 序列, ctx -> 范围 或 null, targetKey)` | 该序列在该范围内的下一个号码（文本）；在流程事务内取号，回滚即归还，无缺号、无重复（18 §2、D23） |
| `RequireApproval.of(审批对象, ctx -> ApprovalCase, targetKey)` / `RequireApproval.when(条件, …)` | 该单据是否需要审批：`ApprovalOutcome`（`NOT_REQUIRED` / `PENDING` / `APPROVED`）；需要时建请求，批准绑定内容哈希（18 §3.3、D23） |
| `WithdrawApproval.of(审批对象, ctx -> 单据标识)` | 撤回该单据待审批的请求（18 §3.3） |
| `PublishEvent.of(eventType, ctx -> 载荷)` / `PublishEvent.when(条件, eventType, …)` | —；在流程事务内写入 Outbox（`OutboxEventPublisher`），提交后由投递器交给订阅的消费者（11 §2）；载荷须为对象，秘密为 null |

引用（数据视图、模板、被调用的流程）在启动时检查（`CheckedStep`）。业务模块不得实现 `StepHandler`（ArchUnit）。

### 2.2 典型流程

```
LoadEntity(order) → LoadEntity(stock) → ComputeStep(扣减库存、计算金额、登记变更) → [自动保存]
```

与 golden 的"业务只修改数据、最后由框架统一保存"是同一思想。

## 3. 上下文与变更集

`ProcessContext` 扩展：

```java
public class ProcessContext {
    long processSeqId();
    Instant opTime();                 // 本次操作统一时间
    RequestContext request();         // 操作人、租户、语言……
    ChangeSet changes();              // 登记的 EntityChange（有序）
    List<Violation> violations();     // 业务规则违规累积
    // 现有 put/get(key, type) 保留；具体流程继续用子类提供类型化访问（如 LoginContext）
}
```

- 上下文由 `ContextFactory.create(ProcessStart start, I input)` 创建，`ProcessStart(processSeqId, opTime, request, ids)` 由平台提供，
  子类调用 `super(start)`。`ctx.reject(violation)` 等同于 `violations().add(...)`。
- `ChangeSet`（core）：`insert(实体, 属性)` 返回主键（属性中给出的，或主键为生成字段时平台生成的 UUIDv7）；`update(实体, id, version, 属性)`、
  `delete(实体, id, version)`；`in(数据视图)` 改用指定视图（缺省为实体的默认视图），`effectiveAt(时间)` 指定时态实体的生效时间，
  `cancelScheduled` 取消预定。提交后的状态在 `saved()`。

### 3.1 统一保存（工作单元）

- 计算步骤通过 `ctx.changes().insert/update/delete(...)` 登记变更，不直接写库。
- 流程最后一步执行完后，平台自动把 `ChangeSet` 交给 `DatasetEntityManager.commitBatch` 提交（时态实体按 04 插入版本）。
- 任一步骤向 `ctx.violations()` 添加了违规：流程结束时整体失败（422，附全部违规），不提交任何变更。

## 4. 事务与阶段

- 默认：**整个流程（含子流程）在一个数据库事务中**执行，由 `TransactionalOperator` 包裹。
- `op_process` 行在流程开始时（同一事务内）写入，之后不再更新；带 `Idempotency-Key` 时，输出在流程结束时写入 `op_process_result`【决策 D4、D11】。事务回滚时一并消失。
- 步骤可以声明阶段：
  - `IN_TX`（默认）：在事务内执行；
  - `AFTER_COMMIT`：事务成功提交后执行（用于调用外部系统、发送通知）；失败不影响已提交的数据，由平台记录并按策略重试。
- `AFTER_COMMIT` 的实现【D11】：声明为 `.afterCommit(名称, 步骤类, 元数据[, RetryPolicy])`；在根流程提交后按顺序执行（子流程的推迟到根流程提交后），
  独立于响应运行（响应不等待，调用方断开也不取消），按 `RetryPolicy`（默认 3 次、200 ms 起指数退避）在进程内重试，每次尝试追加到只追加表 `op_process_after_commit`，用尽后记 error 日志；
  不做持久化重试（可靠投递用 `PublishEvent`）。
- 步骤的执行方式：`ComputeStep` 在当前（非阻塞）线程同步执行，BlockHound 会拦下其中的阻塞调用；`BlockingStep` 在 `boundedElastic`（虚拟线程）上执行，
  之后切回非阻塞线程继续。
- **有外部副作用的 `BlockingStep` 必须使用 `AFTER_COMMIT`，或者该外部调用本身是幂等的**（避免事务回滚后外部状态已改变）。
  优先使用 `PublishEvent`（Outbox）代替在流程内直接调用外部系统。

## 5. 流程组合

- `CallProcess(childDefinition, inputMapper, outputKey)`：子流程在同一事务中执行，获得自己的 `process_seq_id`，
  `parent_seq_id` 指向父流程，`op_time` 与父流程相同。
- 子流程失败 → 整个父流程失败并回滚。
- 撤销父操作时，按相反顺序一并撤销子操作（见 04 第 6 节）。
- 禁止递归调用同一流程（启动时检查调用图有无环）。
- 实现：`CallProcess` 步骤调用 `ProcessExecutor.executeChild`；子流程的变更在它结束时（同一事务内）提交，输出放入父流程上下文（`outputKey`）。

## 6. 版本化

- 流程以 `(name, version)` 唯一标识；同一流程的多个版本可以共存（`ProcessRegistry` 已支持）。
- 调用方可指定版本或 `latest`；`op_process` 记录实际执行的版本。
- 修改业务规则的推荐方式：新增版本，而不是修改旧版本；旧版本可标记 `deprecated`，启动时对仍被引用的废弃版本给出警告。

## 7. 简单用例

绝大多数简单写入只有一个计算步骤。提供简写：

```java
ProcessDefinition<In, Out, Ctx> p = ProcessDefinition.single("ORDER_CANCEL", 1, In.class, Out.class,
    (in, ctx) -> { ... ctx.changes().update(...); return new Out(...); });
```

它就是只有一个 `ComputeStep` 的流程，享有同样的事务、审计和撤销能力。上下文类型为 `ProcessContext`；权限用
`.withPermissions(...)` 声明。构建器中也可以用 `.compute(名称, (元数据, ctx) -> ...)` 在流程里就地写计算步骤（不需要 Bean）。

## 8. 对外接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/processes/{name}/{version或latest}` | 请求体为输入 JSON，反序列化为输入 record 并做 Bean Validation；返回输出 JSON 与 `processSeqId` |
| GET | `/api/processes/executions/{processSeqId}` | 操作详情（`op_process` + `op_process_item`） |
| POST | `/api/processes/executions/{processSeqId}/revert` | 撤销（需要权限，必须填写原因） |

操作详情（权限 `operation.read`）与撤销（权限 `temporal.revert`，请求体 `{reason}`）已在阶段 4 实现（`OperationController`、
`RevertService`）；执行接口与 `Idempotency-Key` 在阶段 6 接入，存储与查找（`OperationRecorder.recordResult / findResult`）已就绪。

- 执行接口：请求体经 JSON 转换为输入 record（不符合时 400 `INVALID_VALUE`），再做 Bean Validation（`NotNull` / `NotBlank` / `NotEmpty` → `REQUIRED`，
  其他约束 → `INVALID_VALUE`，全部一起返回）；响应 `{processSeqId, output}`。未知的流程或版本 404。
- 支持 `Idempotency-Key` 请求头：同一操作人、同一键的重复请求返回第一次的结果，不重复执行（并发请求、键的格式、键被其他流程使用时的 409 见【D11】；
  重放的响应带 `Idempotency-Replayed: true`）。
  实现【决策 D4】：`op_process` 上 `(actor_id, idempotency_key)` 唯一；流程输出在结束时写入 `op_process_result`（同一事务），重放时从中读取。
- 每个流程声明执行权限码（`pb.permissions(...)`，全部具备才可执行）；未声明 → 非开发环境启动失败。权限由流程 API 检查，执行器本身不检查【D11】。
  通用实体流程（`ADD_ENTITY` 等）声明 `entity.write`；`SPONSOR_SIGN_IN` 声明 `auth.sign-in`，只经公开的 `POST /api/auth/login` 执行（10 §3、§4）。
- 流程输入以遮蔽后的 JSON 记入 `op_process.input_summary`，秘密（敏感字段、`@Sensitive` 组件）在流程 API 响应与 `op_process_result` 中为 `null`【D12】。
- 同一流程执行器也被定时任务、事件消费者和场景测试调用（传输方式无关，11 §2.3、§4）。
- 流程目录 `GET /api/meta/processes` 列出调用方可执行的流程及其输入的 JSON Schema（`ProcessInputSchemas`），前端据此生成表单（12 §2）。
  只经专用入口或由平台执行的流程声明 `pb.internal()`（`SPONSOR_SIGN_IN`、`SEC_BOOTSTRAP_ADMIN`、通用实体流程），不进目录，权限检查不变【D15】。
- `ExecutionOptions`（平台内部）：`idempotencyKey`，以及 `beforeSteps`——在流程事务内、记录操作之后、第一个步骤之前执行的平台工作
  （事件投递用它写消费标记，与消费效果同一事务）【D14】。

## 9. 启动自检

- 每个步骤的处理器恰好有一个 Bean（现有检查保留）；计算步骤和阻塞步骤同样检查（就地写的计算步骤除外）；步骤类必须是三种之一。
- 流程调用图无环；被调用的流程和版本存在；平台步骤引用的数据视图、模板存在；`PublishEvent` 需要 `EventPublisher`，事件类型名合法（11 §2.2）。
- 调用已废弃（`pb.deprecated()`）的版本给出警告。
- 权限已声明（`dev` 下为警告）。
- 非内部流程的输入能被表单 Schema 完整描述（否则警告：该部分以原始 JSON 输入）。
- 实现：`ProcessChecks`（`PlatformCheck`，类别 `PROCESS`）。

## 10. 与现有代码的衔接

| 现有 | 改动 |
|---|---|
| `StepHandler<M, C>`（公开，返回 `Mono`） | 移到运行时内部包，仅平台 I/O 步骤实现 |
| `ProcessExecutor.execute` | 增加：写 `op_process`、事务包裹、自动提交 `ChangeSet`、违规累积、`AFTER_COMMIT` 阶段、子流程（阶段 6 完成；另有 `run(..., ExecutionOptions)` 返回 `ProcessResult`） |
| `ClockProcessSequence` | 替换为数据库序列实现（保留接口） |
| `SponsorSignInProcess` 的三个步骤 | 阶段 7 实现（10 §4）：认证 = 平台 `QueryEntities` 加载用户 + **阻塞步骤**校验密码哈希（BCrypt 不能占用事件循环【D12】）；角色检查 = 平台步骤加载角色 + 计算步骤；登录记录 = 登记变更 |
