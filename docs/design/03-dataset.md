# 03 数据视图（Dataset）

数据视图是对某个目标实体存储的一种**带策略、带范围**的访问方式。所有读写都经过数据视图，没有"绕过视图"的通用入口。

## 1. 现有能力（保留）

`DatasetDefinition(resourceId, targetEntityType, storage, policy, defaultPartitionFilter)`：
- 策略：只读、逻辑删除、单次查询行数上限、单次写入条数上限、查询超时。
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
- 范围值由运行时从 `RequestContext` 解析（`DatasetScope.resolve`）后传给 `QueryCompiler`；
  引用存在性检查使用目标实体默认视图的范围；删除前的"仍被引用"检查**不**使用范围（范围外的引用同样阻止删除）。
- 范围同样作用于写入：插入时自动填充，更新时不允许把数据移出范围。
- 范围作用于时态实体时，**在取得当前版本之后再过滤**【决策 D3】（见 04 第 5.1 节），否则会读到已经移出范围的旧版本。

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

## 3. 数据视图 API（运行时）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/datasets/{resourceId}/entities/{id}` | 按主键读取（可带 `asOf`、`knownAt`） |
| POST | `/api/datasets/{resourceId}/query` | 查询：筛选（白名单）、排序（白名单）、分页 |
| POST | `/api/datasets/{resourceId}/commit` | 批量变更（INSERT / UPDATE / DELETE），一个事务，返回快照 |
| GET | `/api/datasets/{resourceId}/entities/{id}/history` | 时态实体的版本历史（含 `process_seq_id`） |

请求与响应（阶段 3 实现前三个接口，阶段 4 加入时态参数与历史接口）：
- `query` 请求体：`{filters: [{field, op, value | values | from, to}], sorts: [{field, asc}], offset, limit}`；
  `op` 为 `eq ne gt gte lt lte in like isNull isNotNull between`，多个条件为 AND；字段须在列表视图白名单中，运算符按语义类型检查。
  响应：`{items, total, offset, limit}`（`limit` 为实际生效值，不超过 `maxQueryBatchSize`）。
  时态实体另可带 `asOf`、`knownAt`（ISO-8601）。
- `commit` 请求体：`{changes: [{action: INSERT | UPDATE | DELETE, id, version, attributes}]}`，返回插入/更新后的快照。
  **只接受该视图的目标实体**（变更的实体类型一律取视图目标），否则会绕过其他实体自身视图的范围；主键为生成字段时由平台生成（UUIDv7）。
  时态实体：每个变更可带 `effectiveTime`（缺省为操作时间；更晚为预定，更早为追溯更正），动作另有 `CANCEL_SCHEDULED`
  （取消 `effectiveTime` 上的预定版本，`version` 为该版本号）；请求体可带 `reason`（追溯更正必填），记入 `op_process`。
  `version` 为生效时间点上生效的版本号（见 04 §3）。
- `history` 响应：版本数组，每项含 `versionNo`、`effectStartTime`、`createdTime`、`deleted`、`action`、`baseVersionNo`、
  `changedFields`、`processSeqId`、`actorId`、`processName`、`opTime`、`reason`、`attributes`。

说明：复杂业务写入应走流程（06），`commit` 接口主要服务于元数据生成的通用增删改页面。

## 4. 启动自检

- 每个视图的目标实体已注册；存储引擎已注册；默认视图唯一且每个实体都有；权限已声明（`dev` 下为警告）。
- 范围字段在目标实体中存在，且语义类型允许 `=` 比较；逻辑删除字段类型正确；显式指定的列表视图存在。
- 全部问题一次性报告（`DatasetRegistry`）。
- `physicalTableOverride` 指向的表存在，并包含目标实体的全部物理列。
