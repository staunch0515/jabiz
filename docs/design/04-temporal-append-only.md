# 04 只追加的双时态模型

## 1. 目标

- 启用时态的实体，其业务表**只做 INSERT，从不 UPDATE / DELETE**。
- 修改 = 插入一个新版本；删除 = 插入一个墓碑版本。
- 用 `effect_start_time`（业务生效时间）决定某一时刻哪个版本生效；不预定时它等于 `created_time`（记录时间）。
- 每个版本通过 `process_seq_id` 关联到一次操作（`op_process`）；操作表记录本次操作涉及的全部数据范围（`op_process_item`）。
- 由此获得：完整历史、按时间点查询、预定生效、更正而不篡改、天然审计、按操作撤销。

业界对应概念：双时态数据（bitemporal）——`effect_start_time` 是有效时间（valid time），`created_time` 是事务时间（transaction time）。
与事件溯源不同：每个版本存完整状态，查询简单。

时态按实体选择开启：`eb.temporal()`。高频计数、纯日志类实体不开启。每个实例只记一次、以后只以新实例更正的（账本交易与分录）声明 `t.writeOnce()`（5.4【D29】）。

## 2. 表结构

### 2.1 时态实体的业务表

平台为时态实体约定以下系统字段（逻辑名固定，物理列名可在元数据中映射）：

| 逻辑字段 | 默认物理列 | 类型 | 语义 | 说明 |
|---|---|---|---|---|
| `rowId` | `row_id` | `bigint identity` | — | 每个版本一行的物理主键，不对外暴露 |
| 实体主键（如 `priceId`） | 由元数据声明 | `uuid` | `SemanticIdentity` | 业务标识，所有版本共用 |
| `versionNo` | `version_no` | `integer` | `Version` | 该实体按记录顺序的版本号 1, 2, 3 … |
| `effectStartTime` | `effect_start_time` | `timestamptz` | `Temporal(VALID_FROM)` | 业务生效时间 |
| `createdTime` | `created_time` | `timestamptz` | `Temporal(SYSTEM_RECORDED)` | 记录时间 = 本次操作的 `op_time` |
| `processSeqId` | `process_seq_id` | `bigint` | — | 外键 → `op_process` |
| `deleted` | `is_deleted` | `boolean` | `Bool` | 墓碑标记 |

约束与索引（由迁移脚本创建，启动自检检查）：

```sql
PRIMARY KEY (row_id)
UNIQUE (entity_id, version_no)                                    -- 并发控制
INDEX (entity_id, effect_start_time DESC, version_no DESC)        -- 当前版本查询
INDEX (process_seq_id)
FOREIGN KEY (process_seq_id) REFERENCES op_process (process_seq_id)
FOREIGN KEY (entity_id)      REFERENCES entity_registry (entity_id)
```

数据库层防护【决策 D5】：通用触发器函数 `jabiz_reject_mutation()`，每张时态表创建行级 `BEFORE UPDATE OR DELETE`
和语句级 `BEFORE TRUNCATE` 触发器，**拒绝一切修改和删除**。唯一例外是受控清除流程（见第 10 节）。

### 2.2 操作表（相当于一次 commit，本身也只追加【决策 D4】）

```sql
CREATE SEQUENCE op_process_seq;

CREATE TABLE op_process (
    process_seq_id   bigint      PRIMARY KEY,           -- nextval('op_process_seq')
    parent_seq_id    bigint      REFERENCES op_process,  -- 子流程指向父流程
    reverts_seq_id   bigint      REFERENCES op_process,  -- 撤销操作指向被撤销的操作
    process_name     text        NOT NULL,
    process_version  int         NOT NULL,
    actor_id         text        NOT NULL,
    tenant_id        text,
    request_id       text,
    idempotency_key  text,
    reason           text,
    input_summary    jsonb,                              -- 敏感字段遮蔽
    op_time          timestamptz NOT NULL,               -- 本次操作的统一时间（来自 Clock）
    UNIQUE (actor_id, idempotency_key)
);

CREATE TABLE op_process_item (
    process_seq_id   bigint  NOT NULL REFERENCES op_process,
    entity_type      text    NOT NULL,
    entity_id        uuid    NOT NULL,
    version_no       int     NOT NULL,
    base_version_no  int,                                -- 本次基于哪个版本修改（插入时为 null）
    action           text    NOT NULL,                   -- INSERT / UPDATE / DELETE / REBASE / REVERT / CANCEL【D9】
    effect_start_time timestamptz NOT NULL,
    changed_fields   text[]  NOT NULL,                   -- 本次变更的逻辑字段名（用于变基和撤销）
    PRIMARY KEY (process_seq_id, entity_type, entity_id, version_no)
);
CREATE INDEX ON op_process_item (entity_type, entity_id);   -- 反查"谁改过这条数据"

CREATE TABLE op_process_result (                         -- 流程结束时插入（op_process 在开始时插入，不再更新）
    process_seq_id   bigint PRIMARY KEY REFERENCES op_process,
    output           jsonb  NOT NULL                     -- 用于幂等重放；敏感字段遮蔽
);
```

- 操作表不更新状态：撤销是一个新的操作（`reverts_seq_id` 指向原操作），不是把原操作改成"已撤销"。
- `op_process`、`op_process_item`、`op_process_result`、`entity_registry` 同样受 D5 的触发器保护。
- 失败的操作随事务回滚，不会留在 `op_process` 中（失败审计如有需要，另写日志，不在本表）。
- 平台表由 runtime 迁移 `db/jabiz/V3__operations.sql` 创建；时态业务表在自己的迁移中调用
  `SELECT jabiz_protect_append_only('<表>')` 安装两个触发器（启动自检检查）。
- 阶段 6 之前，操作由数据视图 `commit`（名为 `jabiz.dataset.commit`）和通用增删改流程产生，仅当变更包含时态实体时写入；
  已在 Reactor Context 中的操作（`Operation`）被加入而不新建【D9】。
- 阶段 6 起，每次流程执行都在开始时写 `op_process`（不论是否涉及时态实体），流程内的所有写入加入该操作；
  失败的执行随事务回滚，不留记录【D11】。数据视图 `commit` 的行为不变。
- `op_process_after_commit`（`db/jabiz/V5__process_after_commit.sql`）记录提交后步骤的每次尝试，同样只追加并受触发器保护【D11】。

### 2.3 实体登记表

```sql
CREATE TABLE entity_registry (
    entity_id        uuid PRIMARY KEY,         -- UUIDv7，全局唯一
    entity_type      text NOT NULL,
    created_seq_id   bigint NOT NULL REFERENCES op_process
);
```

- 时态实体第一次插入时登记。
- 其他表引用时态实体时，外键指向 `entity_registry(entity_id)`（实体类型由平台检查）；引用值为 UUID。
  删除时态实体前，引用方的当前版本与预定版本都不能再引用它【D9】。

## 3. 写入规则

| 操作 | 行为 |
|---|---|
| 插入 | `version_no = 1`；`effect_start_time` 默认 = `op_time`；登记 `entity_registry` |
| 更新 | 调用方提供读到的 `versionNo = n`（生效时间点上生效的版本）；平台以"截至生效时间的状态 ⊕ 本次变更"构造完整新版本，插入 `version_no = 最大值 + 1`【D9】 |
| 删除 | 插入墓碑版本（`is_deleted = true`，其余字段沿用上一状态），`version_no = 最大值 + 1` |

- **并发控制**：调用方的 `n` 与生效时间点上的版本不一致时 409；两个请求同时写同一实体时都插入"最大值 + 1"，
  第二个违反唯一约束，平台转为 `ConcurrentUpdateException`（409）。这与现有非时态实体的 CAS 更新语义一致。
  （预定版本存在时 `n + 1` 可能已被占用，因此取最大值 + 1【D9】。）
- **同一操作的统一时间**：同一个 `process_seq_id` 下写入的所有行，`created_time` 都等于 `op_process.op_time`。一次操作在时间上是原子的。
- **只保存真正的变化**：与现有写入流程一致，没有变化的更新不产生新版本。
- 不可变字段、状态迁移、迁移守卫、字段规则全部照常执行（判断依据是"生效时间点上的当前状态"）。
- 每写一个版本，同一事务内写入一条 `op_process_item`。

### 3.1 生效时间

| 情况 | `effect_start_time` | 要求 |
|---|---|---|
| 普通修改 | = `op_time` | — |
| 预定修改 | > `op_time` | 实体元数据允许预定（`eb.temporal(t -> t.allowScheduled(true))`） |
| 追溯更正 | < `op_time` | 需要权限 `temporal.backdate`（否则 403），且必须填写 `reason`（否则 400 `REASON_REQUIRED`） |

预定而实体不允许时 422 `SCHEDULING_NOT_ALLOWED`；非时态实体带生效时间时 400 `NOT_TEMPORAL`。

## 4. 预定修改与变基（rebase）【决策 D1】

问题：实体有一个将在 T2 生效的预定版本 V（完整快照）。之后在 T1（T1 < T2）又做了一次修改，改了字段 A。
如果不处理，到了 T2，V 会把字段 A 恢复成旧值。

规则：
1. 写入生效时间为 T 的新版本时，平台找出所有**生效时间 > T 且尚未被覆盖**的版本，按生效时间升序逐个处理。
   既包括将来的预定版本，也包括追溯更正时位于 T 与当前之间、已经生效的版本。
2. 对每个这样的版本 V，用 `op_process_item.changed_fields` 取得 V 当初修改的字段集合 F(V)：
   - 若本次变更的字段与 F(V) **无交集**：插入 V 的变基副本 = "新的前一状态 ⊕ V 在 F(V) 上的值"，
     `effect_start_time` 与 V 相同，`version_no` 更大（因此在相同生效时间上胜出），`action = REBASE`；
     下一个待处理版本以该副本作为前一状态。
   - 若 **有交集**：拒绝本次写入（409），错误中列出冲突版本的生效时间和所属操作。调用方必须先取消或修改该预定。
3. **墓碑版本不参与字段冲突判断**：它的变基副本仍然是墓碑，不会因为前面的修改而复活。
4. 变基副本与本次写入属于同一个操作（同一个 `process_seq_id`），同样写入 `op_process_item`。

实现细则【D9】：同一生效时间的多个版本彼此叠加，F 取它们 `changed_fields` 的并集（`CANCEL` 处重新开始），
且只保留在该时间点确实改变了值的字段；删除之后若还有非墓碑版本，按冲突拒绝。纯逻辑在 core 的 `com.jabiz.temporal`
（`Timeline`、`VersionPlanner`），由 jqwik 属性测试覆盖。冲突响应为 409，`conflicts[]` 列出版本号、生效时间、操作、字段。

### 4.1 取消预定

插入一个与预定版本生效时间相同、`version_no` 更大的版本，其内容为"该生效时间点上、去掉该预定后的状态"。
取消本身也是一个操作，留有记录（`action = CANCEL`；取消预定的插入即写墓碑）。
数据视图 API 中为变更动作 `CANCEL_SCHEDULED`，`effectiveTime` 为预定的生效时间，`version` 为该预定版本的版本号；
只能取消生效时间晚于操作时间的版本（否则 422 `NOT_SCHEDULED`）。

## 5. 查询

### 5.1 当前版本（及按时间点）【决策 D3】

```sql
SELECT v.*
FROM (
    SELECT DISTINCT ON (entity_id) *
    FROM   <table>
    WHERE  effect_start_time <= :__asOf
      AND  created_time      <= :__knownAt
    ORDER  BY entity_id, effect_start_time DESC, version_no DESC
) v
WHERE NOT v.is_deleted
  AND <数据视图范围条件>          -- ★ 必须在取得当前版本之后过滤
  AND <查询条件>
```

- `:__asOf` 默认为当前时间（来自 `Clock`），`:__knownAt` 默认不限（使用 `'infinity'`）。
- **范围条件必须在外层**：若放在内层，一个已经被移出范围的实体会"退回"显示它还在范围内时的旧版本。
  外层的应用顺序固定为：墓碑排除 → 数据视图范围 → 查询条件。实体查询、计数查询、SQL 模板一律如此。
- `QueryCompiler` 对时态实体自动生成上述包装；SQL 模板中的 `{{Entity}}` 同样渲染为该子查询（见 05）。
- `__` 前缀的参数名为平台保留。
- **不可变字段的条件同时下推到内层**【D29】：查询条件中只涉及不可变字段（主键、`immutable(true)`）的合取项另外写进子查询的 `WHERE`
  （`Or` 只在每一支都可下推时下推），外层照旧保留全部条件。不可变字段在所有版本上取值相同，所以结果不变，`DISTINCT ON` 只处理相关实例的版本。
  范围条件从不下推。按引用字段、单据号等不可变字段的查询因此与表的大小无关（前提是该字段有索引）。
  前提是不可变字段在一个实例的所有版本上确实相同：把已有的可变字段改为 `immutable(true)` 之前，须确认历史中每个实例只有一个值（否则带该条件的读取会退回旧版本）。
- **只写一次的实体**（`eb.temporal(t -> t.writeOnce())`）【D29】：每个实例只有一个版本，子查询不用 `DISTINCT ON`：

```sql
SELECT v.* FROM (SELECT * FROM <table> WHERE effect_start_time <= :__asOf AND created_time <= :__knownAt [AND <不可变条件>]) v
WHERE NOT v.is_deleted AND <范围条件> AND <查询条件>
```

### 5.2 历史

- `GET .../entities/{id}/history`：按 `version_no` 返回全部版本，附带 `op_process` 的操作人、操作名、时间、原因，
  以及 `action`、`changedFields`、`baseVersionNo`。实体最新状态（含墓碑）不在数据视图范围内时 404；
  `allowTimeTravel(false)` 的视图不提供历史【D9】。
- 操作详情：`GET /api/processes/executions/{processSeqId}`（权限 `operation.read`），返回 `op_process`、`op_process_item` 与直接子操作。
- "谁改过这条数据"：`op_process_item` 按 `(entity_type, entity_id)` 查询。
- "这次操作改了什么"：`op_process_item` 按 `process_seq_id` 查询，再取各版本与其 `base_version_no` 的差异。

### 5.3 性能【D29】

- 当前版本用 `DISTINCT ON` + 复合索引 `(实体主键, effect_start_time DESC, version_no DESC)`；不可变字段的条件下推（5.1），只写一次的实体按版本读。
  按某个不可变字段查询的，给该字段建普通索引。
- 唯一性与"之后仍被引用"的检查先按值从索引取候选实例（7）。唯一约束的字段不是任何索引的前导列时，启动检查报警告。
- 不做"当前快照投影表"（D29 第 5 条）：按可变字段跨大量实例的汇总（如试算表）由应用维护自己的汇总。
- 规模测试：`TemporalScaleIT` 在约 20 万个版本上对这些语句做 `EXPLAIN`，任何计划节点预计的行数都不得超过一小部分。

### 5.4 只写一次的实体【D29】

`eb.temporal(t -> t.writeOnce())`：只能插入（数据视图提交、流程 `ctx.changes().insert`、导入）；更新、删除、取消预定、撤销该插入一律 422 `WRITE_ONCE`
（保留期删除走同一删除路径）。更正以新的实例表达（账本的冲正交易）。迁移必须建只含实体主键的唯一索引（启动检查，缺少即错误），例如
`CREATE UNIQUE INDEX <表>_once_uk ON <表> (<实体主键>)`。其他要求（`UNIQUE(实体主键, version_no)`、`process_seq_id` 索引、外键、
`jabiz_protect_append_only`）与一般时态表相同。平台账本的交易与分录、示范的入库记录 `StockReceipt` 即如此声明。

## 6. 撤销一次操作【决策 D2】

`revert(process_seq_id, reason)` 是一个新的操作（权限 `temporal.revert`，原因必填；
`POST /api/processes/executions/{processSeqId}/revert`，实现为 `RevertService`）：
1. 读取原操作 P 的全部 `op_process_item`（含其 `REBASE` 版本）。
2. 冲突判断以字段为粒度：对 P 写入的每个实体，若 P 之后（按 `process_seq_id` 顺序）有其他操作修改过 P 所修改的任一字段，
   拒绝整个撤销（409），列出阻塞的后续操作。调用方可以先按从新到旧的顺序撤销这些后续操作，再撤销 P。
3. 恢复方式（`action = REVERT`，生效时间与被撤销版本相同，需要时按 D1 变基）：
   - P 的 `UPDATE`：把 P 修改的字段恢复为 `base_version_no` 时的值，其他字段保持当前值；
   - P 的 `INSERT`：插入墓碑；此时 P 之后对该实体的**任何**修改都视为冲突；
   - P 的 `DELETE`：插入恢复版本，内容为墓碑之前的状态。
4. 新操作的 `reverts_seq_id` 指向 P。
5. P 若有子操作，按相反顺序一并撤销，全部在一个事务内完成；任一冲突则整体拒绝。
6. 撤销一个撤销操作（重做）按同样规则处理，允许。

实现细则【D9】：后续操作的 `REBASE` 条目不算修改；已被撤销的后续操作与撤销它的操作一起抵消；撤销按实体的默认数据视图定位存储，
不施加范围；恢复版本写成墓碑时同样检查"仍被引用"。
阶段 7 起撤销还需要所涉实体默认视图的写权限，并拒绝写回敏感字段的旧值（10 §5【D12】）。冲突响应为 409，`blockingOperations[]` 从新到旧列出。

## 7. 唯一性【决策 D6】

时态表上不能直接建业务唯一索引（历史版本会冲突）。对 `eb.unique(...)`：
1. 在事务内对 `(entity_type, 约束名, 规范化后的值)` 的 64 位哈希取 `pg_advisory_xact_lock(key)`；
2. **取锁之后**用新的语句查询：是否有**当前生效版本或尚未生效的预定版本**使用了相同的值（排除自身、排除墓碑）；
   候选实例先从约束字段的索引中取（任一版本用过这些值的实例），再只对它们取当前与预定版本【D29】，所以约束字段应有索引（缺少时启动检查告警）；
3. 存在则返回违规 `UNIQUE_VIOLATION`（400，与其他违规一起累积返回）。

检查对象为本次写入产生的、在操作时间当前生效或之后生效的非墓碑版本（含变基副本）；更新只在改动了约束字段时检查，
取消与撤销总是检查。

哈希冲突只会让不相干的写入多等待，不影响正确性。把预定版本纳入检查，是为了防止到期时出现重复。

## 8. 与现有代码的衔接

| 现有 | 改动 |
|---|---|
| `DatasetPolicy.temporalTracking`（预留） | 已在阶段 3 删除；由实体元数据 `eb.temporal(t -> t.allowScheduled(..).column(..))` 决定，系统字段由平台加入，主键须为 `SemanticIdentity`（UUID） |
| `TemporalRole.VALID_FROM / VALID_TO / SYSTEM_RECORDED` | 用于声明 `effectStartTime`、`createdTime` |
| `ProcessContext.processSeqId()`、`ProcessSequence` | 序列改为数据库 `op_process_seq`；流程开始时写 `op_process` |
| `DatasetEntityManager.update/delete` | 时态实体改为插入版本；非时态实体保持 CAS 更新 |
| `StorageEngine` | 版本经 `insert` 写入（唯一约束冲突 → `ConcurrentUpdateException`）；新增 `select`（平台内部 SQL，与写入同一事务）；时态逻辑在 `TemporalWriter`、`TemporalStore`、`VersionAppender`、`RevertService` |
| `EntityInstance.version` | 时态实体中即 `versionNo` |
| `MetaModelConsistencyChecker` | 检查时态表的系统列、唯一约束、索引、禁止更新的触发器 |

## 9. 业务参数

带生效时间的业务参数（费率、阈值等）直接使用时态实体实现：`sys_param(param_key, value, value_kind, ...)`，开启时态和预定。
`ParamService.get(key, asOf)` 返回 `asOf` 时刻生效的值；规则中应使用"业务发生时间"作为 `asOf`，而不是当前时间。

实现（阶段 8，【D13】）：
- 平台时态实体 `SysParam`（runtime `com.jabiz.runtime.param`，表 `sys_param_version`，迁移 `db/jabiz/V7__sys_param.sql`，允许预定）：
  `paramKey`（唯一，不可变）、`valueKind`（不可变）、`value`（规范文本）、`description`。默认数据视图 `urn:jabiz:dataset:platform:SysParam`，
  权限 `platform.param.read` / `platform.param.write`。
- `valueKind` 用 SQL 模板头 `kind:` 的写法（`{type: numeric, precision: 5, scale: 4}`），扩展语义类型 `jabiz.param-kind`（jsonb）。
  支持 `text` `numeric` `monetary` `bool` `temporal`，以及列出 `allowedValues` 的 `code`（同步校验不能查字典）；其他类型 400 `INVALID_VALUE`（数据视图与 `PARAM_CREATE` 相同）。
- 值与类型是否相符、且是否为规范文本，由实体级校验（02 §4.1）检查，因此任何写入途径都不能写入不合类型或非规范的值（422 `PARAM_VALUE_INVALID`；
  流程会先把输入规范化，数据视图的调用方须直接写规范文本）。规范文本：小数按声明的小数位（`0.1` → `0.1000`，超出精度或小数位即拒绝）、时间为 UTC ISO-8601。core 的 `ParamKinds` 负责解析、规范化与读取。
- 读取：`ParamService.get(key, asOf)` / `load(keys, asOf)`（平台代码，返回 `Mono`）；流程中用平台步骤
  `LoadParams.of(ctx -> 业务时间, 结果键, key…)` 把 core 的 `ParamValues` 放入上下文，计算步骤 `get(key, BigDecimal.class)` 读取。
  `asOf` 时没有生效值的键一次性全部列出：422 `PARAM_NOT_FOUND`（参数 `key`、`asOf`）。
- 维护流程（权限都是 `platform.param.write`：数据视图本身也能插入参数，另设“建参数”权限并不能成立）：`PARAM_CREATE`（建参数与首个值，立即生效）、`PARAM_SET`（立即生效）、
  `PARAM_SCHEDULE`（生效时间须晚于操作时间，否则 422 `EFFECTIVE_TIME_NOT_FUTURE`）、`PARAM_CANCEL_SCHEDULED`（无预定 → 422 `NOT_SCHEDULED`）。修改按 D1 变基：存在修改同一值的预定时 `PARAM_SET` 被拒绝（409），先取消预定。

### 9.1 受控的参数【D40】

费率、门槛等不应由一个人修改的参数，由应用以 Bean `ControlledParams.of(键…)` 声明为受控（键可以尚不存在；格式错误由启动检查 `PARAM` 一次报告）。
受控键的一切写入——上述四个流程、数据视图、通用实体流程、其他流程的 `ChangeSet`、撤销——一律 422 `PARAM_CONTROLLED`（检查在 `VersionAppender` 中，覆盖所有时态写入途径），
只经受控变更（18 §3.5）：`CONTROL_CHANGE_PROPOSE` 以 `targetEntity: SysParam`、`values: {paramKey, value?, description?, valueKind?（新建时）}`、可选 `effectiveTime`（预定）提出，
`delete: true` 加 `effectiveTime` 取消预定值；由他人以 `CONTROL_CHANGE_PUBLISH` 发布后才写入（普通的参数版本，历史与审计照常）。是否受控只在代码中，运行时不能解除。

## 10. 归档与个人信息

- 数据增长：按 `created_time` 分区；冷分区归档到只读存储。
- 个人信息删除权（《个人信息保护法》等）与"永不修改"冲突，处理方式二选一，并必须形成操作记录：
  - **加密粉碎**：个人敏感字段用按主体划分的密钥加密，删除时销毁密钥；
  - **受控清除**【决策 D5】：专用流程在事务内执行 `SET LOCAL jabiz.maintenance_mode = 'purge'`，
    触发器仅在该变量为 `purge` 且当前数据库角色属于维护角色 `jabiz_maintenance`（由运维创建）时放行【D9】；清除指定主体的敏感字段，并写入清除记录（本身也是一次操作）。
    普通业务数据库账号不授予维护角色。
- 以上不在 MVP 范围内，但表结构设计时要为敏感字段预留加密方案。

## 11. 测试要求（阶段 4 验收）

- 并发：两个基于同一版本的更新，只有一个成功，另一个得到 409。
- 预定：到期前查询得到旧值，到期后（推进可控时钟）得到新值，期间没有任何定时任务参与。
- 变基：预定存在时修改其他字段，到期后两者的修改都保留；修改同一字段被拒绝；多个预定版本依次变基；
  预定删除（墓碑）在其前面的修改之后仍然是墓碑；追溯更正会对其后已生效的版本变基。
- 更正：相同生效时间、更晚记录时间的版本胜出；`knownAt` 设为更正之前时，得到更正前的值。
- 撤销：撤销后状态恢复；存在后续冲突修改时撤销被拒绝；只改了无关字段的后续修改不阻塞撤销；撤销插入 = 墓碑；重做可用；子操作一并撤销。
- 唯一性：并发插入相同值只有一个成功；与预定版本中的值重复也被拒绝。
- 范围：实体被移出范围后，按当前时间查询不可见，且不会退回显示旧版本（实体查询、计数、SQL 模板三种途径都要测）。
- 幂等：重复的 `Idempotency-Key` 返回 `op_process_result` 中保存的输出。
- 断言：时态表和操作类表上 UPDATE、DELETE、TRUNCATE 均被触发器拒绝；正常流程的 SQL 日志中没有这些语句；
  受控清除模式下，非维护角色仍被拒绝。
