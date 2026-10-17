# 03 数据视图（Dataset）

数据视图是对某个目标实体存储的一种**带策略、带范围**的访问方式。所有读写都经过数据视图，没有"绕过视图"的通用入口。

## 1. 现有能力（保留）

`DatasetDefinition(resourceId, targetEntityType, storage, policy, defaultPartitionFilter)`：
- 策略：只读、逻辑删除、单次查询行数上限、单次写入条数上限、查询超时。
  查询超时由数据库执行（阶段 14q）：流程事务之外的读取（查询与数据视图接口、导出）在自己的事务中以 `SET LOCAL statement_timeout` 执行
  （每次读取多三个往返：开始、设置、提交；每个绑定的事务操作器须属于它自己的连接工厂），
  超时即由数据库中止，不再在调用方放弃后继续运行、占用连接；应用一侧的时限晚 1 秒，只作兜底。流程事务中的读取只有应用一侧的时限（回滚时等待该语句结束）。
  超时的请求返回 503 `QUERY_TIMEOUT`（可缩小范围或稍后重试），不是 500；通用后台不自动重试 503。
  流程事务中的读取在数据库中没有时限，超时后回滚要等该语句结束（后续可按 `HoldLock` 的方式为单条语句设置）。
  **事务外读取的时限包括等池中连接的时间**（阶段 16o，决策 D45 第 6 点的确认）：应用一侧的时限从订阅算起，借不到连接时同样在时限加 1 秒后 503 `QUERY_TIMEOUT`。
  连接池被耗尽时（热门命名锁的等待者各占一个连接），流程接口上第一个超时的是入口的职责分离规则读取（`SodGuard` → `SodService.rules`，调用者不持有 `*` 时每次都读，
  数据视图缺省时限 5 秒，约 6 秒后 503）；幂等重放的查询（`OperationRecorder.findByIdempotencyKey`）没有时限，池耗尽时一直等待，不会 503。
  测试 `QueryTimeoutCauseIT` 以 2 个连接的池确认了这两点。防止的办法是准入（06 §4.2）：等锁的请求在借连接之前排队，池不再被耗尽；
  职责分离规则的缓存、重放查询的时限与 `spring.r2dbc.pool.max-acquire-time` 的缺省未改（各有语义影响，另行决定）。压测时可看 r2dbc-pool 的指标 `r2dbc.pool.acquired` / `.pending`。
- 存储路由：主库、读库（`readReplicaRef`）、物理表覆盖。
- 分区过滤：读取时自动附加；**写入时强制检查**（写入范围外的值被拒绝，缺失时自动填充）——这一点必须保持。
- SQL 模板中的每个 `{{Entity}}` 都按一个数据视图（指定的或默认的）渲染为带范围的子查询，手写 SQL 也绕不过范围【决策 D10】。

## 2. 改动

### 2.1 一个实体可以有多个视图

- 取消 `DatasetRegistry` 中"一个实体只能被一个视图覆盖"的限制。
- 每个实体必须且只能有一个**默认视图**（`d.asDefault()`，`DatasetDefinition.isDefault()`），用于通用查找
  （资源解析、引用检查、`/api/entities/{type}`）；没有或有多个默认视图时启动失败。
- 典型用法：
  - `urn:jabiz:dataset:admin:Order` —— 后台，全部订单；
  - `urn:jabiz:dataset:member:Order` —— 会员，只能看到自己的订单（范围取自请求上下文）；
  - `urn:jabiz:dataset:report:Order` —— 报表，只读，走读库。

### 2.2 动态范围

`defaultPartitionFilter` 从"固定值"扩展为"表达式"：

```java
d.scope(s -> s
    .fixed("region", "JP")                                  // 固定值
    .fromContext("tenantId", RequestContext::tenantId)      // 取自请求上下文
    .fromContext("ownerId",  RequestContext::actorId));
```

- `fromContext` 取值为 null（或空白）时：**拒绝请求**（默认拒绝），不能退化为"不过滤"：
  `ScopeUnavailableException` → 403，违规 `SCOPE_UNAVAILABLE`（参数 `source`，即上下文属性名，可用
  `fromContext(field, source, fn)` 指定）。读、写、SQL 模板一律如此。
- 范围值由运行时从 `RequestContext` 解析（`DatasetScope.resolve`）后传给 `QueryCompiler`，按字段的语义类型转换后绑定（`QueryCompiler.fieldValue`，
  与查询条件相同：主键与对时态实体的引用为 UUID，文本字段为文本）：`actorId` 等文本与 `uuid` 列比较时按 UUID，读、写、SQL 模板一律如此；
  转换不了 → 400 `INVALID_VALUE`。固定范围值不符合字段类型是启动错误。引用存在性检查使用目标实体默认视图的范围；删除前的"仍被引用"检查**不**使用范围（范围外的引用同样阻止删除）。
- 范围同样作用于写入：插入时自动填充，更新时不允许把数据移出范围。
- 范围作用于时态实体时，**在取得当前版本之后再过滤**【决策 D3】（见 04 第 5.1 节），否则会读到已经移出范围的旧版本。
- **数据期限**【决策 D28 第 8 条，10 §13.2】：`withinDataPeriod("bookingTime")`（目标实体的时态字段）或
  `withinDataPeriod("transactionId", "bookingTime")`（引用字段 + 被引用实体的不可变时态字段）把读取限定在调用者的
  `RequestContext.dataPeriod` 之内（`[from, to)`），期限外的写入被拒（`OUT_OF_SCOPE`）。与 `fromContext` **相反**：
  调用者**没有期限 = 不受限制**，所以只有明确声明的数据视图才受期限限制；它不用于插入时的自动填充，公开数据视图不能使用它。

### 2.3 逻辑删除引用逻辑字段

- `DatasetPolicy.softDeleteColumn` / `softDeleteTimeColumn`（物理列名）改为 `softDeleteField` / `softDeleteTimeField`（逻辑字段名）。
- 启动时校验：字段存在，前者是 `Bool`，后者是 `Temporal(SYSTEM_RECORDED)`。
- 这两个字段只由删除维护：写入时把标记设为 true 被拒绝（`IMMUTABLE_FIELD`）；写入 false / 不填是允许的；
  插入时不为删除时间字段盖记录时间。
- **时态实体不使用这两个字段**：删除即插入墓碑版本（见 04）。

### 2.4 权限

- 每个数据视图声明读、写权限码：`d.permissions("order.read", "order.write")`。
- 未声明权限的视图在非开发环境下启动失败（默认拒绝）。
- 权限检查在运行时层进行，读取 `RequestContext.permissions`（启动自检在 `dev` profile 下为警告）。自阶段 7 起：读、查询、历史需要读权限，
  `commit` 需要写权限，否则 403 `PERMISSION_DENIED`；未声明权限的视图在请求时同样被拒绝（`dev` 除外）（10 §5）。
  流程中的平台步骤经视图读写时不检查视图权限（权限在流程入口检查，D11）。

### 2.5 时态相关参数

对时态实体，视图的查询接受 `asOf` 和 `knownAt`（默认：`asOf = 当前时间`，`knownAt = 不限`），见 04。
可以在策略中禁止外部传入 `asOf`（例如会员视图只能看当前）：`policy.allowTimeTravel(false)`，此时 `asOf`、`knownAt`
与历史接口都被拒绝（400 `TIME_TRAVEL_NOT_ALLOWED`）【D9】。非时态实体传入它们时 400 `NOT_TEMPORAL`。
时态实体的数据视图不能开启逻辑删除（启动失败）：删除即写墓碑。

### 2.6 只经流程写入【D14】

`policy(p -> p.processOnlyWrites())`：该视图只接受流程（`ChangeSet`）的写入；数据视图 API 的 `commit` 与通用实体流程一律拒绝
（422 `PROCESS_ONLY_DATASET`，在检查写权限之后）。用于跨多个实例的规则（例如账本交易的借贷平衡，11 §1）不能被绕过的数据。
以这类视图为默认视图的实体，其操作不能撤销（422 `REVERT_NOT_ALLOWED`）。不能与 `readOnly` 同时使用。
只需保护个别字段（状态、审核意见）时用字段级的 `f.processOnly()`（02 §6.2、16 §5【D20】），其余字段仍可经视图直接编辑。

### 2.7 公开读取【D17】

`publicRead(p -> p.fields(...))`：匿名访客可经公开模板读取该视图范围内的行、白名单内的字段（15 §2）。
自动 `readOnly` 且不可时间旅行；不能是默认视图；范围只能是固定值（没有范围须写 `allRows()`）；白名单不含敏感字段；
`maxQueryBatchSize` 不超过 `jabiz.public.max-limit`。模板渲染时对它做列投影。已认证用户仍按 `permissions` 经数据视图 API 读取（后台预览）。

## 3. 数据视图 API（运行时）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/datasets/{resourceId}/entities/{id}` | 按主键读取（可带 `asOf`、`knownAt`） |
| POST | `/api/datasets/{resourceId}/query` | 查询：筛选（白名单）、排序（白名单）、分页 |
| POST | `/api/datasets/{resourceId}/commit` | 批量变更（INSERT / UPDATE / DELETE），一个事务，返回快照 |
| GET | `/api/datasets/{resourceId}/entities/{id}/history` | 时态实体的版本历史（含 `process_seq_id`） |
| GET | `/api/datasets/{resourceId}/lookup?q=&limit=` | 按显示字段搜索（至多 20 条）→ `[{id, label}]`（16 §2【D20】） |
| POST | `/api/datasets/{resourceId}/labels` | 按主键批量取显示文本（至多 200 个）→ `{id: label}`（16 §2） |

请求与响应（阶段 3 实现前三个接口，阶段 4 加入时态参数与历史接口）：
- `query` 请求体：`{filters: [{field, op, value | values | from, to}], sorts: [{field, asc}], offset, limit}`；
  `op` 为 `eq ne gt gte lt lte in like isNull isNotNull between`，多个条件为 AND；字段须在列表视图白名单中，运算符按语义类型检查。
  响应：`{items, total, offset, limit}`（`limit` 为实际生效值，不超过 `maxQueryBatchSize`）。
  一页未取满（且不是越过末尾的空页）时，`total` 就是 `offset` 加本页行数，不再另执行计数查询（阶段 14q）；
  满页时在取得本页之后再计数（不再与本页同时执行：少占一个连接，但这一页的耗时为两者之和）。
  时态实体另可带 `asOf`、`knownAt`（ISO-8601）。
- `commit` 请求体：`{changes: [{action: INSERT | UPDATE | DELETE, id, version, attributes}]}`，返回插入/更新后的快照。
  **只接受该视图的目标实体**（变更的实体类型一律取视图目标），否则会绕过其他实体自身视图的范围；主键为生成字段时由平台生成（UUIDv7）。
  时态实体：每个变更可带 `effectiveTime`（缺省为操作时间；更晚为预定，更早为追溯更正），动作另有 `CANCEL_SCHEDULED`
  （取消 `effectiveTime` 上的预定版本，`version` 为该版本号）；请求体可带 `reason`（追溯更正必填），记入 `op_process`。
  `version` 为生效时间点上生效的版本号（见 04 §3）。
- `history` 响应：版本数组，每项含 `versionNo`、`effectStartTime`、`createdTime`、`deleted`、`action`、`baseVersionNo`、
  `changedFields`、`processSeqId`、`actorId`、`processName`、`opTime`、`reason`、`attributes`。

`lookup` / `labels` 需要读权限，经视图范围；目标实体没有声明 `display` 时 400 `DISPLAY_NOT_DECLARED`。
`commit` 中出现 `processOnly` 字段时 400 `PROCESS_ONLY_FIELD`（与敏感字段的 `SENSITIVE_FIELD` 并列）。

说明：复杂业务写入应走流程（06），`commit` 接口主要服务于元数据生成的通用增删改页面。

### 3.1 一次读取多个查询、事务中已知的实例（阶段 16p-1，决策 D46）

- `DatasetEntityManager.queryMany(List<EntityRead>)`：多个查询，每个都照 `queryAll` 读取（各自视图的范围、数据期限、逻辑删除、时态实体的当前版本、自己的排序与上限），
  同一存储引擎上的合成一条语句（每个查询的列各自取别名、保留数据库的类型，按查询编号连接，不经 JSON 转换）；结果按查询的顺序。读各视图的主引擎（不读副本），流程中的读取因此看得到本事务的写入。流程中以平台步骤 `QueryEntities.all(…)` 使用（06 §2.1）。
- 条件恒假的查询（`IN` 空列表、由这样的条件组成的 `OR`、含这样条件的 `AND`）不发语句，结果为空、计数为 0（T8）。
- 事务中的读集与写集：事务中经数据视图读到的当前实例（`findById`、`query`/`queryAll`、`queryMany`、引用检查找到的目标；不含指定 `asOf`/`knownAt` 的读取、读副本的读取）
  与平台插入的实例记在该事务的 `TransactionState` 中。**引用检查**的目标若是本事务插入的（值在目标默认视图的范围内）或经目标的默认视图在同一范围读到的，不再查；
  其余的合成一条语句，一次提交中两个以上未知的目标先一起查。默认视图的范围含经引用的数据期限时一律查。今天的引用检查同样不锁目标，保证"目标在本事务的某条语句中存在且在范围内"，所以等价。
- **普通实体的更新**：实例在本事务中经同一张表的数据视图读到完整状态、且值在写入视图的范围内时，以读到的状态为当前，不再读；`UPDATE … WHERE version = :v` 与审计、事件是一条语句，
  审计与事件只在更新了行时写入；更新 0 行时再读一次，已删除或在范围外 404，版本不同 409。时态实体照旧读全部版本。任何平台写入使该实例在读集与写集中的记录失效，回滚到保存点时恢复。
- **事务内写缓冲**（阶段 16p-2，决策 D46）：流程事务中，提交的插入与更新留在事务的缓冲中，在读写其表的语句之前单独写出（06 §3.1）；
  数据视图的读取有时限（§1），从不携带缓冲的写入：缓冲非空时先单独写出一条，读取的时限与 503 因此照旧只属于读取。
  读取因此看到的与逐条执行时相同；判断按语句文本中的表名与视图名（启动检查记下 schema 中视图读的表），模板一律先写出。
  更新 0 行时，事务（或保存点）回滚之后再读一次，回答与上一条相同。

## 4. 启动自检

- 每个视图的目标实体已注册；存储引擎已注册；默认视图唯一且每个实体都有；权限已声明（`dev` 下为警告）。
- 范围字段在目标实体中存在，且语义类型允许 `=` 比较；逻辑删除字段类型正确；显式指定的列表视图存在。
- 全部问题一次性报告（`DatasetRegistry`）。
- 可直接写入的视图（非只读、非 `processOnlyWrites`）中，必填的 `processOnly` 字段必须是状态字段或该视图的范围字段（16 §5）。
- `physicalTableOverride` 指向的表存在，并包含目标实体的全部物理列。
