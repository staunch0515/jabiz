# 03 数据视图（Dataset）

数据视图是对某个目标实体存储的一种**带策略、带范围**的访问方式。所有读写都经过数据视图，没有"绕过视图"的通用入口。

## 1. 现有能力（保留）

`DatasetDefinition(resourceId, targetEntityType, storage, policy, defaultPartitionFilter)`：
- 策略：只读、逻辑删除、单次查询行数上限、单次写入条数上限、查询超时。
- 存储路由：主库、读库（`readReplicaRef`）、物理表覆盖。
- 分区过滤：读取时自动附加；**写入时强制检查**（写入范围外的值被拒绝，缺失时自动填充）——这一点必须保持。
- SQL 模板中的 `{{Entity}}` 渲染为带范围的子查询，手写 SQL 也绕不过范围。

## 2. 改动

### 2.1 一个实体可以有多个视图

- 取消 `DatasetRegistry` 中"一个实体只能被一个视图覆盖"的限制。
- 每个实体必须且只能有一个**默认视图**（`DatasetDefinition.isDefault = true`），用于通用查找（如资源解析）。
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

- `fromContext` 取值为 null 时：**拒绝请求**（默认拒绝），不能退化为"不过滤"。
- 范围同样作用于写入：插入时自动填充，更新时不允许把数据移出范围。
- 范围作用于时态实体时，**在取得当前版本之后再过滤**【决策 D3】（见 04 第 5.1 节），否则会读到已经移出范围的旧版本。

### 2.3 逻辑删除引用逻辑字段

- `DatasetPolicy.softDeleteColumn` / `softDeleteTimeColumn`（物理列名）改为 `softDeleteField` / `softDeleteTimeField`（逻辑字段名）。
- 构建时校验：字段存在，前者是 `Bool`，后者是 `Temporal(SYSTEM_RECORDED)`。
- **时态实体不使用这两个字段**：删除即插入墓碑版本（见 04）。

### 2.4 权限

- 每个数据视图声明读、写权限码：`d.permissions("order.read", "order.write")`。
- 未声明权限的视图在非开发环境下启动失败（默认拒绝）。
- 权限检查在运行时层进行，读取 `RequestContext.permissions`。

### 2.5 时态相关参数

对时态实体，视图的查询接受 `asOf` 和 `knownAt`（默认：`asOf = 当前时间`，`knownAt = 不限`），见 04。
可以在策略中禁止外部传入 `asOf`（例如会员视图只能看当前）：`policy.allowTimeTravel(false)`。

## 3. 数据视图 API（运行时）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/datasets/{resourceId}/entities/{id}` | 按主键读取（可带 `asOf`、`knownAt`） |
| POST | `/api/datasets/{resourceId}/query` | 查询：筛选（白名单）、排序（白名单）、分页 |
| POST | `/api/datasets/{resourceId}/commit` | 批量变更（INSERT / UPDATE / DELETE），一个事务，返回快照 |
| GET | `/api/datasets/{resourceId}/entities/{id}/history` | 时态实体的版本历史（含 `process_seq_id`） |

说明：复杂业务写入应走流程（06），`commit` 接口主要服务于元数据生成的通用增删改页面。

## 4. 启动自检

- 每个视图的目标实体已注册；存储引擎已注册；默认视图唯一；权限已声明。
- 范围字段在目标实体中存在，且语义类型允许 `=` 比较。
- `physicalTableOverride` 指向的表存在，并包含目标实体的全部物理列。
