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
- `f.auditMasked()`：值照常可读、可写，但不进审计，与敏感字段一样记为 `***`（仍记下"改过"）。用于读得到、却不应留在不可删除的审计中的值；
  平台的 `SysFile.originalName`（上传时的文件名，常含人名）即如此。
- 文件字段只有文件 id；文件名（`SysFile.originalName`，见上条）、内容不进审计（与 `input_summary` 相同）。

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

触发器（D5）挡住应用与普通数据库用户，挡不住能关掉触发器的数据库管理员。封存让这类修改**可被发现**：
每行的摘要进入只追加的块，块由带密钥的哈希链相连，没有密钥就无法改了数据再重算整条链。

### 2.1 范围与行摘要

- 范围：当前 schema 中所有带 `jabiz_reject_mutation()` 行触发器的表（平台表、应用的时态表……），每次运行从目录（`pg_trigger`）发现，不用声明。
  封存表自己（`sys_integrity_*`）不作为行封存：它们由链与 Merkle 根保护（封存它们会无穷无尽）。
- 行标识：主键，以 JSON 数组的文本保存（`jsonb_build_array(主键列…)::text`，如 `[42]`）。只追加表必须有主键；
  没有主键或名字不是普通标识符的表由启动检查 `INTEGRITY` 报告（不能封存，其他表照常封存）。
- 行摘要：`SHA-256(to_jsonb(行)::text)`，在数据库中计算：包含封存时表的每一列，键按 jsonb 的顺序，numeric 保留小数位；
  事务内固定 `TimeZone=UTC`、`IntervalStyle=iso_8601`、`extra_float_digits=1`，因此同样的内容总得到同样的摘要。
- **表结构的演进**：每个块为其中每张表记下封存时的列（`sys_integrity_seal_table`），校验时只按这些列计算摘要（`to_jsonb` 中只留这些键）。
  迁移新增的列（即使给旧行填了缺省值）不影响已封存的行；已封存的列被改、删或改名则报告为被改。
  列清单的 SHA-256（core `SealedColumns`）是块签名的一部分，改清单来掩盖修改会使块报告为 `SEAL_ALTERED`。
  列只会增加，所以清单与当前列数相同时就是全部列，直接用整行的文本（快 4–6 倍）。

### 2.2 封存

- 流程 `INTEGRITY_SEAL`（`integrity.seal`），由定时任务 `jabiz.integrity-seal`（`jabiz.integrity.seal.cron`，缺省每 5 分钟，UTC）以系统身份运行，也可手动运行。
- 一次封存：取事务锁（一次只有一个封存）→ 找出尚无条目的行（按表、主键，至多 `jabiz.integrity.seal.max-rows`，缺省 100000，其余下次）→
  写 `sys_integrity_item`（表、行标识、摘要、块号）与块 `sys_integrity_seal`：
  - Merkle 根：叶按表、行标识排序，叶 = SHA-256(0x00 ‖ 表 ‖ 0 ‖ 行标识 ‖ 0 ‖ 摘要)，节点 = SHA-256(0x01 ‖ 左 ‖ 右)，奇数个时末尾直接上移（core `MerkleRoot`）；
  - 块哈希 = HMAC-SHA256(密钥, "jabiz-seal-v1" ‖ 块号 ‖ 封存时间 ‖ 行数 ‖ Merkle 根 ‖ 列清单哈希 ‖ 上一块哈希 ‖ 密钥标识)（core `SealBlock`），第一块的"上一块哈希"为 64 个 0。
  - 先读行、后读列：读行的查询持有表锁直到提交，其间不会有列加进来，列清单与摘要一致。
- **不需要宽限期**：尚未提交的行对封存事务不可见，它们没有条目，下一次封存就会取到（按"有没有条目"而不是按时间找行），因此不会漏掉晚提交的行。
- 没有新行时不生成块。

### 2.3 校验

- 流程 `INTEGRITY_VERIFY`（`integrity.verify`；定时任务 `jabiz.integrity-verify`，`jabiz.integrity.verify.cron`，缺省每天 03:30 UTC），输入 `fromSeal`（缺省 1：从头）。
  从中间开始时，前一块按其保存的哈希信任。
- 检查并报告（`IntegrityProblem` 的种类）：

| 种类 | 含义 |
|---|---|
| `CHAIN_BROKEN` | 块号不连续、没有链到前一块、或块哈希不是密钥的签名（块被改、删、插） |
| `SEAL_ALTERED` | 块的条目不再合出它的 Merkle 根或行数（条目被改、增、删） |
| `MODIFIED` | 行的当前摘要不同于封存时 |
| `MISSING` | 封存过的行（或整张表）不在了 |
| `UNPROTECTED` | 有封存行的表不再在普通会话中拒绝修改：只追加触发器（行级 BEFORE UPDATE、DELETE 与语句级 BEFORE TRUNCATE）被关闭、删除或改为只在复制时触发（`ENABLE REPLICA TRIGGER`） |
| `OTHER_KEY` | 块由另一把密钥签名，无法用当前密钥检查 |

- 另报"尚未封存的行数"（下次封存会取到；持续很多说明封存没有运行）。
- 结果写入只追加的 `sys_integrity_check`：前 `jabiz.integrity.verify.max-problems`（缺省 1000）个问题全文，全部计数。
- **不能发现的**：两次封存之间、由管理员在应用之外**插入**的行——它会像其他新行一样被封存。封存证明的是"封存之后没有被改、没有被删"；
  需要约束插入的，看操作记录（每次写入都有 `op_process`）与审计记录（§1）。把最新块哈希留存在系统外，可以限定"链从某一刻起未被整条重写"。

- 成本（PostgreSQL 16，本机，每 100 万行）：封存时找未封存行约 0.65 秒（每次运行扫描每张只追加表）；整行摘要约 1.6 秒；按列清单的摘要约 9–11 秒
  （只在校验跨过加列的迁移时用到）。表很大时，把校验的定时任务放在低峰，或以 `fromSeal` 只校验新近的块；封存间隔（缺省 5 分钟）远大于一次运行。
  每次封存运行本身写操作记录，因此几乎每次运行都会生成一个小块。

### 2.4 密钥、接口与页面

- 密钥：`jabiz.integrity.key`，只来自环境变量 `JABIZ_INTEGRITY_KEY`（Base64，至少 32 字节，如 `openssl rand -base64 48`）；非 dev 环境缺少即启动失败（默认拒绝）；
  dev 环境使用公开的开发密钥（其封存不证明任何东西）。块中保存密钥标识（密钥的 HMAC 的前 16 个十六进制字符），不保存密钥。
  **密钥要备份**：没有它，已有的块无法校验；换密钥后，旧块报告为 `OTHER_KEY`。
- 接口（`integrity.read`）：`GET /api/integrity/head`（最新块：块号、时间、行数、哈希、密钥标识，以及当前密钥标识——留存在系统外作为锚点）、
  `GET /api/integrity/seals`、`GET /api/integrity/checks`、`GET /api/integrity/checks/{checkNo}`（含问题）。
  封存与校验就是上述两个流程，经 `/api/processes/{名}/latest` 运行，不另设接口。
- 页面 `/integrity`：最新块、运行校验（`integrity.verify`）、最近的校验及其问题。

## 3. 保留期与法律保全（14f-3）

- `RetentionPolicy` Bean（core）：实体、保留期、起算字段，可按会计年度末（`jabiz.fiscal-year-end`，缺省 12 月）起算。
- 删除拦截：时态实体的逻辑删除（墓碑）、普通实体的物理删除、`FILE_DELETE` / `FILE_PURGE_ORPHANS` 在保留期内或受法律保全时拒绝（422 `RETENTION_ACTIVE` / `LEGAL_HOLD`；
  文件清理跳过并报告）。
- 法律保全：时态平台实体 `SysLegalHold`（实体类型、主键列表或"字段 = 值"），只经 `LEGAL_HOLD_PLACE` / `LEGAL_HOLD_RELEASE`（`legal.hold.write`，解除需原因）。
- 到期报告：报表模板列出已过保留期的记录（标出受保全者）。到期后的物理删除与匿名化不在 14f（需另立决策，与 D5 冲突）。

## 4. 开放格式导出（14f-3）

`POST /api/exports/data`（`data.export`）：以流返回 ZIP——每个实体一个 CSV（UTF-8、表头、小数位原样、UTC 时间、按主键排序）、`schema.json`（字段、语义类型、币种与小数位、引用）、
`manifest.json`（各文件 SHA-256 与行数、参数、平台版本、最新封存块哈希）与范围内已签发报表的 PDF；按视图的读权限与数据范围过滤，记操作（`DATA_EXPORT`），不设行数上限。
