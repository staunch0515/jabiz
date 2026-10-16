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
| `QueryEntities.of(datasetId, ctx -> EntityQuery, targetKey)` | `List<EntityInstance>`：查询自己的 `limit` 那么多行，不按数据视图的 `maxQueryBatchSize`（浏览的一页）截断；实际行数超过 `jabiz.process.max-read-rows` 即 422 `PROCESS_READ_TOO_LARGE`（`limit` 大也照常，只读到上限多一行）【D32】 |
| `RunTemplate.of(templateId, ctx -> 参数, targetKey)` | `List<Map<列名, 值>>`（不检查模板权限，范围照常施加【D11】）；全部行，不按数据视图的 `maxQueryBatchSize`（那只限浏览的一页）截断，超过 `jabiz.process.max-read-rows`（缺省 100,000）即 422 `PROCESS_READ_TOO_LARGE`，不取一部分【D32】 |
| `SaveChanges.now()` | 已提交的状态在 `ctx.changes().saved()`；已有违规时拒绝提交 |
| `CallProcess.of(name, version, ctx -> 输入, outputKey)` / `CallProcess.latest(...)` / `CallProcess.when(条件, name, version, …)` | 子流程输出（`when` 的条件不成立时不调用）【D14】 |
| `CallProcess.forEach(name, version, ctx -> 输入列表, outputKey)` | 对列表中每个输入依次调用一次（列表为空时不调用），输出为同序的列表（阶段 13e） |
| `LoadParams.of(ctx -> 业务时间, targetKey, key…)` | `ParamValues`：各业务参数在该时间点生效的值（04 §9；缺失 → 422 `PARAM_NOT_FOUND`） |
| `AssignNumber.of(序列, [ctx -> 范围,] targetKey)` / `AssignNumber.when(条件, 序列, ctx -> 范围 或 null, targetKey)` | 该序列在该范围内的下一个号码（文本）；在流程事务内取号，回滚即归还，无缺号、无重复（18 §2、D23） |
| `RequireApproval.of(审批对象, ctx -> ApprovalCase, targetKey)` / `RequireApproval.when(条件, …)` | 该单据是否需要审批：`ApprovalOutcome`（`NOT_REQUIRED` / `PENDING` / `APPROVED`）；需要时建请求，批准绑定内容哈希（18 §3.3、D23） |
| `WithdrawApproval.of(审批对象, ctx -> 单据标识)` / `WithdrawApproval.when(ctx -> 条件, 审批对象, ctx -> 单据标识)` | 撤回该单据待审批的请求（18 §3.3）；`when` 的条件不成立时什么也不做（阶段 16k） |
| `HoldLock.shared(ctx -> 名称[, 最长等待])` / `HoldLock.exclusive(ctx -> 名称[, 最长等待])` | —；取得该名称的锁并持有到事务结束（§4.1）；名称为 null 或空白时不取；`最长等待`（`Duration`，阶段 16k）短于全局时限时以它为准 |
| `pb.admission(族名, 输入 -> 键)`（不是步骤，流程的声明） | —；根流程在借连接之前按准入键排队，为最外层的热门 `HoldLock` 声明（§4.2，D45）；子流程从不排队 |
| `PublishEvent.of(eventType, ctx -> 载荷)` / `PublishEvent.when(条件, eventType, …)` | —；在流程事务内写入 Outbox（`OutboxEventPublisher`），提交后由投递器交给订阅的消费者（11 §2）；载荷须为对象，秘密为 null |

引用（数据视图、模板、被调用的流程）在启动时检查（`CheckedStep`）。业务模块不得实现 `StepHandler`（ArchUnit）。

### 2.2 典型流程

```
LoadEntity(order) → LoadEntity(stock) → ComputeStep(扣减库存、计算金额、登记变更) → [自动保存]
```

与 golden 的"业务只修改数据、最后由框架统一保存"是同一思想。

### 2.3 外部调用【D38，阶段 16e-1 / 16e-2】

业务调用其他系统**只在 `BlockingStep` 中、只经声明的外部服务**进行（短调用）；耗时数十秒的调用用**异步外部作业**（11 §4a）：流程以平台步骤
`StartExternalJob` 登记作业后立即返回，平台在任何事务之外执行作业类型的处理器（`ExternalJobHandler`），结果经完成流程写入。
处理器与阻塞步骤同样只经声明的外部服务调用（只限其作业类型声明的服务），不访问数据库。

- **声明**（core `com.jabiz.external.ExternalService`，Bean）：
  ```java
  ExternalService.define("demo-ai", s -> s.baseUrl("https://ai.example.com/v1").allowedHosts("ai.example.com")
      .secret("apiKey", "JABIZ_DEMO_AI_KEY").connectTimeout(ofSeconds(5)).requestTimeout(ofSeconds(10))
      .idempotencyHeader("Idempotency-Key").maxResponseSize(64 * 1024).allowInDryRun())
  ```
  名字 `[a-z][a-z0-9-]{0,39}`；主机只写名字（小写，不带协议、端口）；环境变量名 `[A-Z][A-Z0-9_]*`；连接超时缺省 5 s、至多 30 s，
  请求超时缺省 30 s、至多 2 min（阻塞步骤占着流程的事务，更长的用作业）；响应上限缺省 1 MiB、至多 16 MiB。
  `jabiz.external-services.<名>.base-url | allowed-hosts` 按环境覆盖地址与主机（测试、开发指向本地桩服务），其他属性只在代码中。
- **调用**：注入 `ExternalServices`，`http(服务)` 得到同步的 `ExternalHttp`，`send(Request)` 返回 `Response`（状态、请求头、字节或文本）。
  `Request.post("/v1/moderate").header(…).json(…).timeout(…).idempotencyKey(…)`：目标是相对路径（按 RFC 3986 对基础地址解析），
  或白名单内主机上、与基础地址**协议和端口相同**的绝对地址；超时不超过服务的请求超时。请求头名不区分大小写（后写的替换先写的）；
  值只能是可见 ASCII、空格与制表符（与 JDK 客户端的规则相同，中文等须由调用方编码），由客户端自己设置的头（`Host`、`Connection`、`Content-Length`、
  `Expect`、`Upgrade`、`Transfer-Encoding`、`TE`、`Trailer`、`Keep-Alive`、`Proxy-*`）不能设置；违反时 `IllegalArgumentException`，消息不重复值。`secret(服务, 名)` 取启动时读入的密钥；`idempotencyKey(后缀)` 为 `jabiz-<processSeqId>-<后缀>`，同一流程的提交后步骤各次重试不变，
  交给提供方去重。
- **平台对每次请求的检查**（runtime `JdkExternalHttp`，每个服务一个 JDK `HttpClient`）：
  1. 在非阻塞线程上调用即 `IllegalStateException`；不在阻塞步骤中（没有 `ExternalCallScope`）同样拒绝。`ProcessExecutor` 执行 `BlockingStep` 时在其虚拟线程上设置
     `ExternalCallScope`（`processSeqId`、步骤名、是否试运行），执行完（含失败）即清除；`ExternalJobExecutor` 执行作业的处理器时设置作业的范围
     （作业号、作业类型，只允许该类型声明的服务，其他服务的调用与密钥一律拒绝；`idempotencyKey(后缀)` 为 `jabiz-job-<作业号>-<后缀>`）。
     只有平台的执行器能打开范围（`ExternalCallScope.open` / `openJob` 检查调用方），
     业务代码不能伪造"在阻塞步骤中"或"不是试运行"；共用的 ArchUnit 规则另禁止业务包依赖 `com.jabiz.runtime.external`。
  2. 试运行中拒绝，除非服务声明 `allowInDryRun()`（只读、幂等的调用）。
  3. 解析后的地址按 `OutboundAddresses`（与 Webhook 共用）检查：https（http 只限本机）、不带用户信息、主机在该服务的白名单中。`//其他主机/…` 这类相对目标解析后同样被拒。
  4. 不跟随重定向（3xx 原样返回）；整个交换（含读完响应）不超过超时，响应超过上限即失败；不自动重试。JDK 客户端自己拒绝的请求（方法、请求头）
     同样是被拒的调用（502），不带 JDK 的消息。
  5. 失败抛 `ExternalCallException`（种类 `REFUSED` / `TIMEOUT` / `TOO_LARGE` / `UNAVAILABLE` / `BAD_RESPONSE`，后者供步骤判断不可用的回答），
     API 为 502 `EXTERNAL_CALL_FAILED`（参数为服务名与种类；种类的文字在消息 `EXTERNAL_CALL_FAILED.kind.<种类>`，三语——`MessageCatalog` 的通用规则：
     参数值有消息 `<代码>.<参数>.<值>` 时显示其文字）。消息只含服务名、种类与地址的协议、主机、端口，不含路径、查询、请求头与正文，也不附带 JDK 的异常；
     `Request`、`Response`、`ExternalService` 的 `toString` 也不含请求头的值、正文、查询与第一段之后的路径。
  6. 导入（20）的预览是试运行：行的流程调用外部服务失败或被拒，是该行的问题 `EXTERNAL_CALL_FAILED`，不使整个导入失败。
- **密钥**：启动时经 `Environment` 读取，只在内存中，不进日志、`toString`、异常与流程上下文。`jabiz.external.missing-secrets` 决定缺失时怎样：
  `error`（缺省，启动检查报错）、`warning`（`dev` 的缺省，用到时才失败）、`ignore`（`platformCheck`：静态校验没有部署的环境，不检查密钥）。
- **启动检查 `EXTERNAL`**：名字合法且唯一（重复的声明也报告它自己的问题）、白名单非空且为主机名、基础地址可发送且主机在白名单中、超时与上限在范围内、
  幂等键请求头名合法且不是客户端自己设置的头、环境变量已设置、配置能读取（`base-url` 以文本读入后再转换，不是地址时报告并保留声明的地址）、
  配置中没有未声明的服务，一次报全。
- **副作用**：与 §4 相同——有副作用的调用放 `AFTER_COMMIT`，或带提供方的幂等键（`idempotencyKey`）；只读的调用可在事务内。
- **ArchUnit**：平台在 runtime 的测试夹具中提供 `PlatformArchRules`，示范 `app` 与各应用的 `ArchitectureTest` 引用它：
  `noDirectNetworkAccess(业务包)`——业务包不得使用 `java.net.http..`、`javax.net..`、Spring 的 HTTP 客户端与请求工厂（`web.client`、
  `web.reactive.function.client`、`http.client`）、Netty（含 Reactor Netty）、套接字与套接字通道、`URLConnection`、Spring 的 `UrlResource`，
  不得调用 `URL#openConnection` / `openStream` / `getContent`，不得以 XML 解析器的 `parse(String uri)` 从地址读取文档；
  `noPlatformInternals(业务包)`——业务包不得依赖 `com.jabiz.runtime.external`（作业的步骤 `StartExternalJob` 因此在 `com.jabiz.runtime.process.steps`）；
  `handlersStayOutOfTheDatabase(业务包)`——业务包中的 `ExternalJobHandler` 与 `BlockingStep` 不得依赖 JDBC、R2DBC、Spring 的数据访问、
  平台的 `storage` / `query` 包与 `DatasetEntityManager`。规则是护栏而不是沙箱：反射、方法句柄、把地址交给第三方库等看不见。
- 观测：`jabiz.external.call`（标签只有 `service` 与 `outcome`：`2xx` `3xx` `4xx` `5xx` `timeout` `refused` `error`）。
- 示范：`app` 的 `PRODUCT_RENAME` 以阻塞步骤 `ModerateText`（`POST /v1/moderate`，请求自己的超时 10 s；服务的请求超时 60 s 是给生成文案的作业的）审核新名称，被判为不当即违规 `COMMERCE_TEXT_INAPPROPRIATE`；
  长调用的示范是异步外部作业 `commerce.product-copy`（11 §4a）。
  只有这个流程审核：通用表单与数据视图 API 写入（含新建）的商品名不经审核——示范的是外部调用，不是受审核的字段（需要时字段设为 `processOnly()`，
  新建也走审核的流程）。桩服务 `tools/demo/ai-stub/AiStub.java`：手工演示 `java tools/demo/ai-stub/AiStub.java`（`dev` 指向 `http://localhost:8091/v1`）；
  Docker Compose 中是服务 `ai-stub`，与 `app` 共用网络（`http://localhost:8091/v1`）。
- 测试：runtime 测试夹具 `StubHttpServer`（JDK `HttpServer`，按路径编排应答——状态、请求头、延迟、分块——并记录请求），应用分支可直接复用。

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
- **试运行**（`ExecutionOptions.DRY_RUN`，决策 D26）：流程照常执行完，然后整个事务（含 `op_process`、变更、Outbox、单据号计数）回滚，返回输出；
  不执行提交后步骤，不接受 `Idempotency-Key`。导入的预览即如此（20 §5）。阻塞步骤（含子流程的）知道是试运行，其中的外部调用缺省被拒（§2.3）。
- **保存点**（`StorageEngine.inSavepoint`，决策 D26）：平台步骤可在事务内把一段工作放在保存点之后，失败时只撤销这一段、事务继续；
  只供平台自己的步骤使用（导入按单元执行子流程，20 §5），不对业务开放。

### 4.1 命名锁（阶段 14o）

两个流程各自读了对方要改的状态后提交（如过账读到期间开放、同时关账把它关上）时，单靠事务看不见对方未提交的写入。
业务流程以 `HoldLock` 步骤声明这类互斥：同一名称的 `exclusive` 与任何持有者互斥，`shared` 之间不互斥；锁一直持有到根流程的事务提交或回滚
（子流程取的锁同样如此），等待者随后读到持有者留下的状态（事务隔离为平台默认的 READ COMMITTED，每条语句看到已提交的数据）。
实现为 PostgreSQL 事务级咨询锁（`pg_advisory_xact_lock[_shared]`，名称加前缀 `jabiz.app-lock:` 后取 64 位哈希），不建表、不写数据；
名称与平台自己的锁键（账本科目层级、审批、导入等）不同，只可能因哈希碰撞而相遇（只影响并发，不影响正确性）。

- 在读取受保护的状态**之前**取锁；一个流程取多个锁时各处按同一顺序，否则可能互相等待。
- 等待最长 `jabiz.process.lock-timeout`（缺省 30 秒，只对取锁这一条语句生效）；超时或数据库判定死锁时流程失败，回滚，返回 409（可重试）。
  平台把这两种数据库错误（`55P03`、`40P01`）一律译为 `ConcurrentUpdateException`。持有者应短：不要在持锁的流程里做长时间的外部调用。
- **整个事务的等待上限**（阶段 16k）：每个流程事务开始时以 `SET LOCAL lock_timeout`（`set_config(…, true)`）设为 `jabiz.process.lock-timeout`；
  逐项任务的项改用执行选项 `ExecutionOptions.lockWait`（= `itemLockWait`，较短时）。因此行锁、平台自己的锁（审批、账本科目层级、编号等）、
  并发重复请求等待同一幂等键，都不会无限等待；超时同样是 409（`LockNotAvailableException`）。
  风险：以前没有上限的等待（例如长事务持有的行锁）现在在 30 秒后以 409 结束——这是想要的结果（请求可重试，不占住连接）；
  任务项的整个事务以 `itemLockWait`（缺省 2 秒）为限，取不到即 `BUSY`、下次运行再处理。
- **逐步的最长等待**（阶段 16k，D42）：`HoldLock` 取锁语句的 `lock_timeout` = min(步骤声明的等待, 执行选项的等待, `jabiz.process.lock-timeout`)，
  至少 1 毫秒；只对这一条语句生效，之后恢复为事务的设置。执行选项由平台设置（逐项任务），经 Reactor 上下文传给子流程，不对业务开放。超时仍是 409：
  请求中即"快速失败"，任务项中即 `BUSY`（跳过到下一次运行，11 §4.1）。
- 只用于事务内的步骤：提交后步骤没有事务，锁取得即释放。试运行同样取锁，在回滚时释放；导入的单元在保存点中回滚时，其中取的锁随之释放。
- 热门的锁：等待者各占一个连接。为它声明准入（§4.2），等待者在借连接之前排队。

### 4.2 取锁前的排队（准入，阶段 16o，决策 D45）

命名锁的等待者已借了连接、开了事务：一把热门的锁（许多人同时提交同一个发布、同一张订单）会让连接池的大部分连接都在等锁，无关的读取随之变慢，最后以 503 `QUERY_TIMEOUT` 零星失败（03 §1）。
流程以**准入**声明它最外层的热门锁，平台在借连接、开事务**之前**按准入键在进程内排队：

```java
ProcessDefinition.define(ORDER_CANCEL, 1, CancelInput.class, …, pb -> pb
    .permissions("commerce.order.cancel")
    .admission("commerce.order", input -> orderKey(input.orderId()))   // 族名（常量）+ 从输入算出的键；null 不排队
    .step("Lock the order", HoldLock.exclusive(ctx -> lockName(ctx.get(ORDER_ID))))   // 照旧：正确性只靠它
    …);
```

- **准入键** = `族名 + ":" + 键`（键以 `String.valueOf` 成文；null 或空白即不排队）。族名是声明时的常量（`[a-z][a-z0-9._-]{0,47}`，启动检查），也是配置与观测用的名字（D16：观测只放族名）；
  键函数在事务之前只从输入算出，不读数据。约定：族名与键取得和该流程最外层的 `HoldLock` 名称一致（示范 `commerce.order:<订单>`），由同一个辅助方法生成两者；
  `HoldLock` 取到与准入键同族而不同名的锁时每个流程记一次 warn（只记族名与流程名）。每个流程至多一个准入；同一族可被多个流程声明，它们共用队列。不声明即不排队。
- **闸门**（每键、每实例）：同时持有许可（在事务中）的至多 `concurrency` 个（缺省 2：第 2 个在持锁者提交前做完锁前的工作并在咨询锁上等，持锁者一提交它就接上，锁不空转）；
  先进先出，释放的许可直接交给队首；队列至多 `max-queue` 个（缺省 50），队满即**立即** 503 `PROCESS_BUSY`（`reason = QUEUE_FULL`），不借连接；
  在队列中至多等 `max-wait`（缺省 2 秒，不超过 `jabiz.process.lock-timeout`），到时 503 `PROCESS_BUSY`（`WAIT_TIMEOUT`）。之后在咨询锁上的等待照旧由 `lock_timeout` 约束，且至多排在 `concurrency − 1` 个之后。
  `Retry-After` = 该键排队数 × 该族最近 50 次持有时间的平均 ÷ `concurrency`，向上取整，夹在 [`retry-after`（缺省 1 秒）, 30 秒] 之间。
- **全局上限** `max-active`：所有键合计同时持有的许可，缺省 `spring.r2dbc.pool.max-size` 的一半（至少 1）；排队的流程合计至多用半个池，其余连接总是留给读取与不排队的流程。
  先取键的许可再取全局的，两处都先进先出；只有排队者等待，持有者不再等任何闸门，所以不形成环。键与排队者的总数有上限（`max-keys` 10 000、`max-waiting` 5 000），空闲的键即删除。
- **持有期**：从借连接之前到根事务真正结束（成功、422、409、500、试运行都释放）；提交后步骤不持有。客户端断开时排队者退出队列；
  **已取得许可的根流程不再随客户端断开而中止**，它自己运行到结束（受 `lock_timeout` 约束）后才归还许可：被取消的事务要等当前语句（例如在咨询锁上的等待）结束才能回滚，
  其间仍占着连接，提前归还许可会让断开的客户端占满连接池。
- **顺序**：流程接口的检查（§8）→ 幂等重放（已完成的请求重放时**不排队**）→ 准入 → 事务。并发的重复请求中后到的一个照常排队，取得许可后在幂等键上等前一个再重放。
- **多实例**：准入只是准入控制，**不是互斥**，正确性只由咨询锁保证（D45 第 2 条）。N 个实例各自排队：同一键同时在事务中的至多 N × `concurrency`，有效队列为 N × `max-queue`（按实例数调）；
  不同实例之间不保证先来先得。实例重启、键算错只影响排队的效果，不影响结果：该请求照旧在咨询锁上等待，并由流程在锁内检查。
- **事先不知道的锁不排队**：只声明最外层的锁。锁内才知道的锁（读了数据才知道的钱包锁、`ORDER_RECORD_PAYMENT` 读了支付事件才知道的订单锁）不声明；它们的等待者都已持有外层键的许可，受全局上限约束。
- **与其他部分**：

  | 情形 | 行为 |
  |---|---|
  | 子流程（`CallProcess`、`CallProcess.forEach`） | **从不排队**：子流程在根流程的事务中，已占着连接；在事务中等闸门会重新造成占连接的等待，还可能与持有该键许可、正在等本事务咨询锁的请求形成环。子流程声明的准入被忽略；`subProcessOnly()` 流程声明准入即启动错误（示范：`ORDER_EXPIRE` 的子流程 `ORDER_CANCEL`） |
  | 咨询锁的重入 | 不变（同一事务中再次取同名锁由 PostgreSQL 计数）；闸门只在根流程入口取一次 |
  | 逐项任务的项 | 根流程，照样排队，排队时限 min(`max-wait`, `itemLockWait`)；被拒即 `BUSY`（11 §4.1） |
  | 事件消费者 | 照样排队；被拒是一次失败的投递，按投递的退避重试（11 §2） |
  | 试运行（导入预览） | 照样排队（同样借连接、取咨询锁），回滚后释放 |
  | 导入 | 每行或每组以子流程运行，不排队 |
  | 流程接口的限流（§8、10 §15.10） | 在一切数据库访问之前，被限流的请求不借连接 |

- **配置**（`jabiz.process.admission.*`，`AdmissionSettings`）：`enabled`（应急开关，缺省开；关闭即全部放行）、`max-active`（0 = 池的一半）、`max-keys`、`max-waiting`、
  `defaults.{concurrency, max-queue, max-wait, retry-after}`、`families."[族名]".{…}`（族名带点时写方括号）。数值范围由启动检查报告（§9）。
- 实现：core `Admission`、`ProcessDefinitionBuilder.admission`；runtime `process.admission.ProcessAdmission`（Bean）与 `AdmissionGate`（纯 Java + Reactor，非阻塞：短 `synchronized` 临界区，
  许可在 `Schedulers.parallel()` 上交给等待者；时限用单调时钟，这是节流不是业务时间），`ProcessExecutor.run` 以 `Mono.usingWhen` 取放许可；`executeChild` 不取。观测见 13。
- **何时声明**：只有压测或运行中看到某把命名锁的等待者占满连接时才声明（指南 `new-business-object.md` 的"命名锁"一节）。其他 `HoldLock` 的使用者不需要改。

## 5. 流程组合

- `CallProcess(childDefinition, inputMapper, outputKey)`：子流程在同一事务中执行，获得自己的 `process_seq_id`，
  `parent_seq_id` 指向父流程，`op_time` 与父流程相同。
- 子流程失败 → 整个父流程失败并回滚。
- 撤销父操作时，按相反顺序一并撤销子操作（见 04 第 6 节）。
- 禁止递归调用同一流程（启动时检查调用图有无环）。
- 实现：`CallProcess` 步骤调用 `ProcessExecutor.executeChild`；子流程的变更在它结束时（同一事务内）提交，输出放入父流程上下文（`outputKey`）。
- `CallProcess.forEach` 的各次调用同样在**同一事务**中：一项失败整批回滚，各项取的锁持有到整批提交。需要逐项提交的批量工作
  （一项失败不影响其他项、不与在线请求形成锁环）用逐项任务 `JobDefinition.forEach`（11 §4.1、D42），不用 `CallProcess.forEach`。

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
- **谁能启动流程**【D41】：不经流程接口的流程按用途三选一声明，都不进目录、不能作为行操作（声明 `actsOn` 即启动失败），权限照常声明。
  执行器在启动根流程与子流程时统一检查（`ProcessExecutor.requireStartable` / 子流程同理；流程接口在转换输入之前先查），不符即 403 `PROCESS_INTERNAL`（三语），不论权限：

  | 声明 | 作为根流程 | 作为子流程（`CallProcess`） | 平台中的例子 |
  |---|---|---|---|
  | `pb.internal()` | 只有系统身份（流程接口一律拒绝） | 只在系统身份的根流程下（调用方须也是 `internal()`，启动检查） | `MAIL_SEND`、`TASK_NOTIFY`、`INBOUND_RECEIVE`、`WEBHOOK_DELIVER`、`SEC_BOOTSTRAP_ADMIN`；应用的事件消费、定时任务、回调流程（示范 `ORDER_RECORD_PAYMENT`、`CERTIFICATION_APPROVAL_RESULT`、`FREIGHT_POST_REVENUE`） |
  | `pb.entryOnly()` | 只经平台自己的入口，以真实调用者运行（入口传执行选项 `ExecutionOptions.atEntryOf(流程)` / `ProcessExecutor.executeAtEntry`） | 一律拒绝（调用它在启动检查中报错） | 登录与二次验证（`SPONSOR_SIGN_IN`、`SPONSOR_MFA_VERIFY`、`SPONSOR_OIDC_SIGN_IN`）、通用实体流程（`ADD/UPDATE/DELETE_ENTITY`，实体接口）、`FILE_REGISTER`（上传）、`IMPORT_RUN` 与导入映射（`IMPORT_MAPPING_SAVE/REMOVE`）、自助注册与找回密码（`SPONSOR_SIGN_UP*`、`SPONSOR_EMAIL_VERIFY*`、`SPONSOR_PASSWORD_RESET_*`）、`SEC_MFA_ENROLL_*`、`SEC_USER_SET_LOCALE`、`SEC_MAIL_PREFERENCE_SET` |
  | `pb.subProcessOnly()` | 一律拒绝（系统身份也不行） | 任何根流程下都可以，权限由根流程的声明保证 | `FILE_ARCHIVE`（14 §10） |

  流程接口对三种流程一律 403 `PROCESS_INTERNAL`（不是 404：流程存在，只是不由这里运行；目录已隐去它们），消息说明它如何运行（由平台在事件、计划、回调中运行 / 经它自己的接口 / 只作另一流程的一部分）。
  名字 `system`、`anonymous` 保留：开发请求头与访问令牌都不能以它们为操作人。业务代码不引用 `ProcessExecutor`、`ExecutionOptions`、`ActingUser`、`RequestContext.system`（`PlatformArchRules.noProcessMachinery`）。
  操作记录与审计中的操作人总是根流程的操作人：`entryOnly()` 流程保留真实的调用者，子流程沿用根流程的身份。
  新的平台流程按用途选择其一；应用的事件、定时任务、回调接续点用 `internal()`。
- **过载与限流**（阶段 16o，D45）：声明了准入（§4.2）的流程，队满或排队超时即 503 `PROCESS_BUSY`（`Retry-After`，`violations[0]` 的参数与 ProblemDetail 的顶层属性 `reason`
  = `QUEUE_FULL` / `WAIT_TIMEOUT`、`retryAfter`，三语消息；不记错误日志），请求未读写任何数据，可以重试；通用后台与 `@jabiz/client` 不自动重试 503，显示消息。
  流程接口可按 `jabiz.process.rate-limits.<流程>` 按用户、地址、实例限流（10 §15.10），超限 429 `RATE_LIMITED`。入口的检查顺序：
  进目录 → 权限 → 限流 → 二次验证 → 已验证邮箱 → 职责分离 → （执行器）幂等重放 → 准入 → 事务。
- `ExecutionOptions`（平台内部）：`idempotencyKey`，`beforeSteps`——在流程事务内、记录操作之后、第一个步骤之前执行的平台工作
  （事件投递用它写消费标记，与消费效果同一事务）【D14】，`dryRun`（导入预览，20 §5），以及 `entry`——平台入口声明自己是哪个 `entryOnly()` 流程的入口（`atEntryOf`）【D41】；流程接口从不传它。

## 9. 启动自检

- 每个步骤的处理器恰好有一个 Bean（现有检查保留）；计算步骤和阻塞步骤同样检查（就地写的计算步骤除外）；步骤类必须是三种之一。
- 流程调用图无环；被调用的流程和版本存在；平台步骤引用的数据视图、模板存在；`PublishEvent` 需要 `EventPublisher`，事件类型名合法（11 §2.2）。
- 调用已废弃（`pb.deprecated()`）的版本给出警告。
- 权限已声明（`dev` 下为警告）。
- 不进目录的流程（`internal()`、`entryOnly()`、`subProcessOnly()`）不能声明 `actsOn`；`CallProcess` 不能调用 `entryOnly()` 流程；
  不是 `internal()` 的流程（普通、`entryOnly()`、`subProcessOnly()`）不能调用 `internal()` 流程——静态不知道普通流程的根是否以系统身份运行，只认 `internal()` 的调用方【D41】。
  事件消费者与定时任务的流程、导入的目标流程的声明分别由 `EVENT` / `JOB`（11）与 `IMPORT`（20 §6）检查。
- 进目录的流程的输入能被表单 Schema 完整描述（否则警告：该部分以原始 JSON 输入）。
- 准入与限流（D45，`ProcessAdmissionChecks`，同为类别 `PROCESS`）：族名合法；`subProcessOnly()` 流程不能声明准入；配置了而没有流程声明的族告警；
  `concurrency` 1–16、`max-queue` 0–10 000、`max-wait` 大于 0 且不超过 `jabiz.process.lock-timeout`、`retry-after` 0–30 秒、`max-active` 0 或 1 到池大小 − 1、`max-keys` 与 `max-waiting` 为正；
  `jabiz.process.rate-limits` 中的流程须已注册且进目录、数值为正、至少一种桶。
- 实现：`ProcessChecks`（`PlatformCheck`，类别 `PROCESS`）。

## 10. 与现有代码的衔接

| 现有 | 改动 |
|---|---|
| `StepHandler<M, C>`（公开，返回 `Mono`） | 移到运行时内部包，仅平台 I/O 步骤实现 |
| `ProcessExecutor.execute` | 增加：写 `op_process`、事务包裹、自动提交 `ChangeSet`、违规累积、`AFTER_COMMIT` 阶段、子流程（阶段 6 完成；另有 `run(..., ExecutionOptions)` 返回 `ProcessResult`） |
| `ClockProcessSequence` | 替换为数据库序列实现（保留接口） |
| `SponsorSignInProcess` 的三个步骤 | 阶段 7 实现（10 §4）：认证 = 平台 `QueryEntities` 加载用户 + **阻塞步骤**校验密码哈希（BCrypt 不能占用事件循环【D12】）；角色检查 = 平台步骤加载角色 + 计算步骤；登录记录 = 登记变更 |
