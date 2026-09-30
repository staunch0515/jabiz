# 21 审计、防篡改与保留

决策见 D27。本文件是约定；按阶段实施：14f-1（§1 审计记录）、14f-2（§2 防篡改）、14f-3（§3 保留与法律保全、§4 开放格式导出）。
§2–§4 是已确认的设计，实施时细化；与实现冲突时先改本文件。

## 1. 审计记录（14f-1）

### 1.1 记录什么

每次实体写入（插入、更新、删除；时态实体的每个新版本，包括墓碑、冲正、取消计划版本）在**同一事务**内写一行 `sys_audit_record`：

| 列 | 含义 |
|---|---|
| `record_no` | 序号（数据库标识列；不用 `EntityIdGenerator`，场景回放的主键因此不受影响） |
| `process_seq_id` | 写入所属的操作；普通实体的批量提交不记操作（04 §2.2），此时为空 |
| `entity_type` / `entity_id` | 实体与主键 |
| `action` | `INSERT` / `UPDATE` / `DELETE`；时态实体为版本动作（`REBASE`、`REVERT` …） |
| `version_no` / `effect_start_time` | 写入产生的版本（时态实体、带版本列的普通实体）与生效时间（时态实体） |
| `changes` | JSON `{字段: [前, 后]}`：插入只有"后"，删除只有"前"，更新只记值变化的字段 |
| `changed_fields` | 变化的字段名（按字段筛选用） |
| `actor_id` / `recorded_time` | 操作人与时间：操作的操作人与 `op_time`；没有操作时取请求的操作人与注入的 `Clock` |
| `reason` | 操作的原因（不限长度，与 `op_process.reason` 相同）；普通实体批量提交的原因（`CommitRequest.reason`）直接记在这里 |

- 什么都没变的写入不记录：同值更新，以及因更正而重排的墓碑（前后都为空）。
- 表只追加（`jabiz_protect_append_only`），管理员经应用也改不了；14f-2 将其纳入封存，绕过触发器的修改可被发现。
- 写入位置：`DatasetEntityManager` 的插入、更新、删除（普通实体）与 `VersionAppender`（时态实体，所有时态写入都经过它）。
  数据视图 API、通用实体流程、业务流程的 `SaveChanges`、平台流程（账本、审批、导入……）都经过这两处，因此不需要各自记录。
- "前"：普通实体取更新或删除前读到的当前行（CAS 本来就要读）；时态实体取基础版本的状态（同一次写入中先写的版本优先），基础版本是墓碑时为空。

### 1.2 值与遮蔽

- 值按规范形式保存（`AuditDiff.canonical`，core）：`BigDecimal` 保留小数位的纯文本，时间为 UTC ISO-8601，整数为长整数，`Map` 按键排序，
  集合为列表，其他为 `toString()`。比较也按规范形式，因此 `1.50` 与 `1.5` 是不同的值（小数位是数据的一部分）。
- `f.sensitive()` 字段的前后值一律写成 `***`（没有值时为空），但照样记下"改过"：审计能说明密码哈希何时被改，但不保存它（SC-004）。
  审计**只保存遮蔽后的值**，不另存可解密的原值（D27 第 2 条）。14g 的字段遮蔽接入同一处。
- 文件字段只有文件 id；文件名、内容不进审计（与 `input_summary` 相同）。

### 1.3 查询

`GET /api/audit/records`（权限 `audit.read`），按 `recorded_time`、`record_no` 倒序：

| 参数 | 含义 |
|---|---|
| `entityType` / `entityId` | 按实体 |
| `withApprovals` | 与 `entityType`、`entityId` 同用（缺一则 400）：同时列出关于该记录的审批请求（`SysApprovalRequest`）与审批决定（`ApprovalDecision`）的审计行，即审批人、结论与理由。只算声明了该实体类型的审批对象（`ApprovalSubject` 的 `entity(…)`，18 §3.1），因此别的实体的同名主键不会混入 |
| `actorId` | 按人 |
| `from` / `to` | 按时间：`recorded_time ∈ [from, to)` |
| `processName` | 按流程 |
| `field` | 改过该字段的记录 |
| `offset` / `limit` | 分页，与 11 §3 相同 |

- 响应 `{items, total, offset, limit}`；每项含上表各列、流程名与 `changes`（`{字段: {before, after}}`）。
- `GET /api/audit/records/{recordNo}`：一条记录，不存在 404。
- 11 §3 的 `GET /api/audit/operations` 每项增加 `auditRecords`（该操作留下的审计行数）。
- 非法参数一次返回全部违规（400）；SQL 为固定文本，值全部绑定。

### 1.4 后台

页面 `/audit`（菜单只对有 `audit.read` 的人显示）：筛选、列表、展开看每个字段的前后值，敏感值显示为 `***`。
实体历史页有"审计记录"入口（`/audit?entityType=…&entityId=…`）。

## 2. 防篡改：摘要与封存（14f-2）

- 范围：装了 `jabiz_protect_append_only` 的全部表（平台表与应用的时态表），启动时从数据库发现；装了触发器却无法按行标识的表由启动检查报告。
- 行摘要：规范序列化后的 SHA-256（列按名称排序，numeric 保留小数位，时间 UTC ISO，jsonb 按键排序）。
- 封存：定时任务 `jabiz.integrity-seal`（缺省每 5 分钟，宽限期 2 分钟，可配置）运行流程 `INTEGRITY_SEAL`，把尚未封存且早于"现在减宽限期"的行的摘要写入
  `sys_integrity_item`，生成块 `sys_integrity_seal`：本块哈希 = HMAC-SHA256(密钥, 上一块哈希 ‖ 条目 Merkle 根 ‖ 元数据)。
  迟提交的行由下一次封存补上。密钥只来自 `JABIZ_INTEGRITY_KEY`，非 dev 环境缺少即启动失败。`GET /api/integrity/head` 给出最新块哈希，供系统外留存。
- 校验：流程 `INTEGRITY_VERIFY`（定时或手动，`integrity.verify`）检查链、Merkle 根与当前数据，报告被改、缺失、可疑（超过两个周期仍未封存）的行与断链，
  写入 `sys_integrity_check`；页面 `/integrity`。

## 3. 保留期与法律保全（14f-3）

- `RetentionPolicy` Bean（core）：实体、保留期、起算字段，可按会计年度末（`jabiz.fiscal-year-end`，缺省 12 月）起算。
- 删除拦截：时态实体的逻辑删除（墓碑）、普通实体的物理删除、`FILE_DELETE` / `FILE_PURGE_ORPHANS` 在保留期内或受法律保全时拒绝（422 `RETENTION_ACTIVE` / `LEGAL_HOLD`；
  文件清理跳过并报告）。
- 法律保全：时态平台实体 `SysLegalHold`（实体类型、主键列表或"字段 = 值"），只经 `LEGAL_HOLD_PLACE` / `LEGAL_HOLD_RELEASE`（`legal.hold.write`，解除需原因）。
- 到期报告：报表模板列出已过保留期的记录（标出受保全者）。到期后的物理删除与匿名化不在 14f（需另立决策，与 D5 冲突）。

## 4. 开放格式导出（14f-3）

`POST /api/exports/data`（`data.export`）：以流返回 ZIP——每个实体一个 CSV（UTF-8、表头、小数位原样、UTC 时间、按主键排序）、`schema.json`（字段、语义类型、币种与小数位、引用）、
`manifest.json`（各文件 SHA-256 与行数、参数、平台版本、最新封存块哈希）与范围内已签发报表的 PDF；按视图的读权限与数据范围过滤，记操作（`DATA_EXPORT`），不设行数上限。
