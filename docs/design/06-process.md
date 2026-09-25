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

### 3.1 统一保存（工作单元）

- 计算步骤通过 `ctx.changes().insert/update/delete(...)` 登记变更，不直接写库。
- 流程最后一步执行完后，平台自动把 `ChangeSet` 交给 `DatasetEntityManager.commitBatch` 提交（时态实体按 04 插入版本）。
- 任一步骤向 `ctx.violations()` 添加了违规：流程结束时整体失败（422，附全部违规），不提交任何变更。

## 4. 事务与阶段

- 默认：**整个流程（含子流程）在一个数据库事务中**执行，由 `TransactionalOperator` 包裹。
- `op_process` 行在流程开始时（同一事务内）写入，之后不再更新；输出在流程结束时写入 `op_process_result`【决策 D4】。事务回滚时一并消失。
- 步骤可以声明阶段：
  - `IN_TX`（默认）：在事务内执行；
  - `AFTER_COMMIT`：事务成功提交后执行（用于调用外部系统、发送通知）；失败不影响已提交的数据，由平台记录并按策略重试。
- **有外部副作用的 `BlockingStep` 必须使用 `AFTER_COMMIT`，或者该外部调用本身是幂等的**（避免事务回滚后外部状态已改变）。
  优先使用 `PublishEvent`（Outbox）代替在流程内直接调用外部系统。

## 5. 流程组合

- `CallProcess(childDefinition, inputMapper, outputKey)`：子流程在同一事务中执行，获得自己的 `process_seq_id`，
  `parent_seq_id` 指向父流程，`op_time` 与父流程相同。
- 子流程失败 → 整个父流程失败并回滚。
- 撤销父操作时，按相反顺序一并撤销子操作（见 04 第 6 节）。
- 禁止递归调用同一流程（启动时检查调用图有无环）。

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

它就是只有一个 `ComputeStep` 的流程，享有同样的事务、审计和撤销能力。

## 8. 对外接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/processes/{name}/{version或latest}` | 请求体为输入 JSON，反序列化为输入 record 并做 Bean Validation；返回输出 JSON 与 `processSeqId` |
| GET | `/api/processes/executions/{processSeqId}` | 操作详情（`op_process` + `op_process_item`） |
| POST | `/api/processes/executions/{processSeqId}/revert` | 撤销（需要权限，必须填写原因） |

操作详情（权限 `operation.read`）与撤销（权限 `temporal.revert`，请求体 `{reason}`）已在阶段 4 实现（`OperationController`、
`RevertService`）；执行接口与 `Idempotency-Key` 在阶段 6 接入，存储与查找（`OperationRecorder.recordResult / findResult`）已就绪。

- 支持 `Idempotency-Key` 请求头：同一操作人、同一键的重复请求返回第一次的结果，不重复执行。
  实现【决策 D4】：`op_process` 上 `(actor_id, idempotency_key)` 唯一；流程输出在结束时写入 `op_process_result`（同一事务），重放时从中读取。
- 每个流程声明执行权限码；未声明 → 非开发环境启动失败。
- 同一流程执行器也被定时任务和场景测试调用（传输方式无关）。

## 9. 启动自检

- 每个步骤的处理器恰好有一个 Bean（现有检查保留）；计算步骤和阻塞步骤同样检查。
- 流程调用图无环；被调用的流程和版本存在。
- 权限已声明。

## 10. 与现有代码的衔接

| 现有 | 改动 |
|---|---|
| `StepHandler<M, C>`（公开，返回 `Mono`） | 移到运行时内部包，仅平台 I/O 步骤实现 |
| `ProcessExecutor.execute` | 增加：写 `op_process`、事务包裹、自动提交 `ChangeSet`、违规累积、`AFTER_COMMIT` 阶段、子流程 |
| `ClockProcessSequence` | 替换为数据库序列实现（保留接口） |
| `SponsorSignInProcess` 的三个步骤 | 在安全阶段（ROADMAP 阶段 7）真正实现：认证 = 平台 `LoadEntity` + 计算步骤校验密码哈希；登录记录 = 登记变更 |
