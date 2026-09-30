# 18 编号、审批、职责分离、任务与通知

业务应用常用的四项通用能力（ROADMAP 阶段 14b，决策 D23）。本文件随子阶段逐步补全：第 2 节已实施（14b-1），第 3、4 节已实施（14b-2）；
第 5 节（14b-3）是已确认的计划，实施时细化。

## 1. 总览

| 能力 | 做什么 | 阶段 |
|---|---|---|
| 编号 | 单据号无缺号、无重复，按范围（年度、来源）计数 | 14b-1 |
| 审批 | 按有版本的规则判定是否需要审批；批准绑定内容；准备人不能审批自己；多级与限额 | 14b-2 |
| 职责分离 | 互斥的权限不能落在同一用户身上；冲突报告 | 14b-2 |
| 任务与通知 | 待办列表；邮件通知 | 14b-3 |

## 2. 编号（14b-1）

### 2.1 声明

序列是 Bean（core `com.jabiz.numbering.NumberSequence`）：

```java
@Bean NumberSequence journalNumbers() {
    return NumberSequence.define("fin.journal", s -> s.format("JE-{n:4}").scoped());   // 每个范围各自从 1 起
}
@Bean NumberSequence invoiceNumbers() {
    return NumberSequence.define("fin.invoice", s -> s.format("INV-{n}").startAt(1004)); // 全局，从 1004 起
}
```

| 项 | 规定 |
|---|---|
| 名称 | 1–100 个小写字母、数字、`. _ -`，以字母开头；全局唯一（启动检查 `NUMBERING`） |
| 格式 | 字面文字（字母、数字、`. _ / # -`）加恰好一个 `{n}` 或 `{n:宽度}`（补零，1–19 位，超出宽度时照写全部数字），可选 `{scope}`（只用于按范围计数的序列）；声明时校验，一次报告全部问题 |
| 范围 | `scoped()` 的序列每个范围各自计数；范围是 1–100 个字母、数字、`. _ / -`（如 `2026`、`2026/MAN`）。未按范围的序列只有一个计数 |
| 起始值 | `startAt(n)`，缺省 1；每个范围的第一个号码 |

### 2.2 取号

流程步骤 `AssignNumber`（runtime `com.jabiz.runtime.numbering`）：

```java
.step("Number the entry", AssignNumber.of("fin.journal", ctx -> fiscalYear(ctx) + "/MAN", "journalNo"))
.step("Number the invoice", AssignNumber.of("fin.invoice", "invoiceNo"))
.step("Number the order", AssignNumber.when(ctx -> 需要号码(ctx), "commerce.order", ctx -> 年份(ctx), "orderNo"))
```

- 一条语句 `INSERT … ON CONFLICT DO UPDATE SET last_value = last_value + 1 RETURNING last_value` 对 `sys_number_counter`（序列、范围）一行取下一个值，
  行锁持有到流程事务结束：
  - 流程之后失败（违规 422、异常）→ 事务回滚，计数随之回滚，号码被"归还"；
  - 同一范围的并发流程在这一行上排队，依次取到下一个号码，不会重复；不同范围互不影响。
- 号码的文本放入上下文 `targetKey`；同时向 `sys_number_assignment` 写一行（序列、范围、值、号码、`process_seq_id`、时间）。
- **把取号放在可能拒绝流程的检查之后**：行锁使同一范围的流程排队到事务结束。
- `when(条件, …)` 的条件不成立时不取号：取出的号码都会保留，不需要的号码不要取。
- 引用未声明的序列、按范围的序列不给范围、未按范围的序列给了范围，都在启动时报告（`CheckedStep`）。

### 2.3 表

| 表 | 性质 | 内容 |
|---|---|---|
| `sys_number_counter` | 基础设施表，可更新（同 `jabiz_shedlock`，不受 D5 保护） | (sequence_name, scope_key) → last_value |
| `sys_number_assignment` | 只追加（D5 触发器）；平台实体 `NumberAssignment`，数据视图 `urn:jabiz:dataset:platform:NumberAssignment`（读 `numbering.read`，只经取号写入） | 每个发出的号码一行；唯一 (sequence_name, scope_key, value_no) |

完整性核对：某序列、某范围的 `value_no` 恰为起始值 … 当前值，没有空缺与重复（唯一约束保证不重复，计数的回滚保证不空缺）。

### 2.4 示范与测试

- 示范：`ORDER_PLACE` 不带订单号时取 `commerce.order` 的下一个号码（`SO-2026-000001`，按下单年份计数）；因库存不足被拒的订单不取号。
- 测试：core `NumberSequenceTest`（格式、补零、范围、声明错误，属性测试：同宽补零的号码按值排序）；runtime `NumberingChecksTest`（重复名称、步骤引用）；
  app `NumberingIT`（按范围连续、回滚归还、条件不成立不取号、20 路并发无缺无重、未按范围的序列与起始值、记录取号的操作、号码表上没有 UPDATE / DELETE 且触发器拒绝）、
  `CommerceIT`（示范）。

## 3. 审批（14b-2）

### 3.1 审批对象

审批对象是代码声明的 Bean（core `com.jabiz.approval.ApprovalSubject`），列出规则可以检验的**事实**及其类型：

```java
@Bean ApprovalSubject journalApprovals() {
    return ApprovalSubject.define("fin.journal", s -> s.number("amount").text("source").bool("manual"));
}
```

| 项 | 规定 |
|---|---|
| 名称 | 同序列名（小写字母开头，1–100 个 `a-z 0-9 . _ -`）；全局唯一（启动检查 `APPROVAL`） |
| 事实 | `NUMBER`（按 `BigDecimal` 比较，金额即此类）、`TEXT`、`BOOLEAN`；名称为字母开头的字母、数字、`_` |

### 3.2 规则与层级

规则是时态平台实体 `SysApprovalRule`（可预定生效），字段：代码、审批对象、条件、层级、优先级、启用、说明。条件与层级以 JSON 保存：

```json
{"all": [{"fact": "amount", "op": "gte", "value": 10000},
         {"any": [{"fact": "source", "op": "in", "value": ["MANUAL", "IMPORT"]},
                  {"fact": "manual", "op": "eq", "value": true}]}]}
```
```json
[{"permission": "fin.journal.approve", "limitFact": "amount"}, {"permission": "fin.cfo.approve"}]
```

- 条件：`all` / `any` 嵌套（最深 5 层、最多 100 个比较），比较 `{fact, op, value}`；`eq` `ne` 适用于全部类型，`gt` `gte` `lt` `lte` 只用于数字，
  `in` `notIn`（值为列表）用于数字与文本。空对象总是成立；案件没给的事实（或为 null）使任何比较为假。
- 层级：0–5 层，依次审批；`permission` 是该层审批人需持有的权限（不能是 `*`）；`limitFact` 是审批对象的一个数字事实，给出时审批人须有覆盖其值的限额。
  **没有层级的规则**表示"不需要审批"（用于在低优先级规则之前豁免）。
- 解析错误一次报告全部（core `ApprovalCondition.parse`、`ApprovalLevel.parse`）。

审批人限额是时态平台实体 `SysApprovalLimit`：用户、审批对象、最大值（`numeric(19,4)`）。

### 3.3 取得结论：步骤 `RequireApproval`

```java
.step("Ask for approval", RequireApproval.of("fin.journal",
    ctx -> ApprovalCase.of(entry.id(), Map.of("amount", total, "source", source), content(entry)).at(bookingTime),
    "approval"))
```

`ApprovalCase`：单据标识（1–100 字符）、事实、**内容**（批准所绑定的内容）、业务时间（缺省为操作时间）、准备人（缺省为操作人；
以系统身份继续的流程传入原请求的准备人）。步骤把 `ApprovalOutcome(status, requestId)` 放入上下文：

| 情形 | 结论 |
|---|---|
| 该单据有内容哈希相同的**已批准**请求 | `APPROVED`（流程可以继续做需要审批的事） |
| 有内容哈希相同的**待审批**请求 | `PENDING`（同一请求） |
| 否则按业务时间取该审批对象启用的规则，按优先级（再按代码）找第一条条件成立的规则 | 无规则成立或规则无层级：`NOT_REQUIRED`；否则新建请求 `SysApprovalRequest` 并发布 `jabiz.approval.requested`：`PENDING` |

- 内容哈希：core `ContentHash`，内容的规范文本（键排序、列表按序、数字去掉末尾的零、日期时间按其 UTC 瞬间）的 SHA-256。键顺序、数字小数位与时区写法不影响哈希，其他任何变化都会。
- 该单据内容哈希**不同**的待审批或已批准请求改为 `SUPERSEDED`：单据改了，原来的审批不再有效。
- 请求记录准备人、规则与其版本、内容哈希、层级、事实、当前层级。每次评估写一行只追加的 `ApprovalEvaluation`（结论、所评估的全部规则版本 `规则ID:版本号`、
  成立的规则、事实、内容哈希、业务时间、操作）。
- 同一单据的评估以事务级 advisory lock 串行，一个单据不会出现两个待审批请求。
- `WithdrawApproval.of(对象, ctx -> 单据标识)`：撤回单据的待审批请求（`WITHDRAWN`，如单据作废）；已批准的请求保留（它绑定内容）。
- 引用未声明的审批对象，启动时报告（`CheckedStep`）。

### 3.4 审批：流程 `APPROVAL_DECIDE`

输入 `{requestId, decision: APPROVE | REJECT, reason}`，流程权限 `approval.decide`，作为请求的行操作显示（`actsOn`，待审批时）。

| 检查 | 不满足时 |
|---|---|
| 请求处于 `PENDING` | 422 `APPROVAL_NOT_PENDING` |
| 操作人持有当前层级的权限 | 403 `PERMISSION_DENIED` |
| 操作人不是准备人 | 422 `APPROVAL_OWN_REQUEST` |
| 操作人未判断过本请求的其他层级（`sys_approval_decision` 上 `UNIQUE(request_id, approver_id)` 兜底） | 422 `APPROVAL_ALREADY_DECIDED` |
| 批准有 `limitFact` 的层级：操作人对该审批对象的限额 ≥ 事实值（没有限额即不满足） | 422 `APPROVAL_LIMIT_EXCEEDED` |
| 驳回写明理由 | 422 `REASON_REQUIRED` |

每个判断写一行只追加的 `ApprovalDecision`（`UNIQUE(request_id, level_no)`：同一层级的并发判断只有一个成功）。批准最后一层：请求 `APPROVED`，发布
`jabiz.approval.approved`；驳回：请求 `REJECTED`，发布 `jabiz.approval.rejected`；负载含请求、审批对象、单据、准备人、内容哈希、状态、判断人、理由。

**继续原流程**：应用订阅这两个事件（事件属于所有审批对象，按 `subject` 忽略别人的），以系统身份运行自己的流程，其中再调用 `RequireApproval`
（传入原准备人）：内容未变得到 `APPROVED` 即继续；内容已变则新建请求，单据继续等待。

### 3.5 规则的修改：提出与发布（四眼）

审批规则、审批人限额、职责分离规则（第 4 节）的数据视图都是 `processOnlyWrites`，只经受控变更 `SysControlChange`（时态实体）修改：

| 流程 | 权限 | 做什么 |
|---|---|---|
| `CONTROL_CHANGE_PROPOSE` | `control.propose` | `{targetEntity, targetId?, delete?, values, effectiveTime?, reason}`：新建（无 `targetId`）、修改部分字段或删除；可预定生效。立即检查：可写字段、审批对象已声明、条件与层级符合对象的事实、职责分离的两组权限有效；错误一次报告（422 `CONTROL_CHANGE_INVALID`） |
| `CONTROL_CHANGE_PUBLISH` | `control.publish` | 由**提出人以外**的人发布（422 `CONTROL_SAME_PERSON`）；按目标的当前状态再检查一次，以提出的生效时间写入；只能发布一次（422 `CONTROL_CHANGE_NOT_PROPOSED`） |
| `CONTROL_CHANGE_WITHDRAW` | `control.propose` | 提出人撤回自己的提案 |

规则按业务时间生效：预定在将来生效的修改，只影响业务时间在其后的案件。

### 3.6 影响预览

`POST /api/approvals/preview`（权限 `approval.read`）：`{subject, ruleId?, draft?: {ruleCode, condition, levels, priority, enabled}, limit?}`。
取该审批对象每个单据**最近一次评估记录的事实**（最多 5000 个），分别用当前生效的规则、和以草案代替 `ruleId`（无草案即预览删除该规则）后的规则评估，
返回评估数、是否截断、以及**结论不同**（是否需要审批、或层级不同）的单据与前后结论。草案无效时 400 `CONTROL_CHANGE_INVALID`。

### 3.7 表与示范

| 表 | 性质 |
|---|---|
| `sys_approval_rule_version`、`sys_approval_limit_version`、`sys_sod_rule_version`、`sys_control_change_version`、`sys_approval_request_version` | 时态（D5），平台实体 `SysApprovalRule`、`SysApprovalLimit`、`SysSodRule`、`SysControlChange`、`SysApprovalRequest` |
| `sys_approval_decision`、`sys_approval_evaluation` | 只追加（D5 触发器），平台实体 `ApprovalDecision`、`ApprovalEvaluation`，只读视图 |

读权限：`approval.read`（职责分离规则为 `sod.read`）。

示范（`app`）：审批对象 `commerce.certification`（事实：供应商、标题语言数、是否有正文；内容：供应商、标题、正文）。`CERTIFICATION_SUBMIT` 调用
`RequireApproval`；等待审批时手工审核 `CERTIFICATION_APPROVE` 被拒（`CERTIFICATION_AWAITING_APPROVAL`）；订阅审批事件的
`CERTIFICATION_APPROVAL_RESULT` 在批准（内容未变）后通过、驳回后退回。场景 `scenarios/commerce/certification_approval.yml`。

## 4. 职责分离（14b-2）

### 4.1 规则

`SysSodRule`（时态，可预定生效）：代码、左组权限、右组权限（逗号分隔，各 1–50 个，不能含 `*`，两组不相交）、启用、说明。同一人不能同时持有两组中各一个权限。
规则只经第 3.5 节的受控变更修改。core `com.jabiz.security.SodRule`。

### 4.2 预防：写入检查

`SodWriteCheck`（`FieldWriteCheck`，数据视图 API、通用实体流程、流程变更集的全部写入途径）：

- 新的用户角色（`SecUserRole`）：用户现有权限 + 新角色的权限；
- 新的角色权限（`SecRolePermission`）：持有该角色的每个用户的现有权限 + 新权限；

违反任一启用的规则即拒绝（422 `SOD_CONFLICT`，附规则与用户）。"现有权限"按每行最新版本计算（预定生效的角色分配已算在内），只计启用的角色。
检查前取事务级 advisory lock，两个并发的授权不会各自通过。`*` 不参与检查。启用一个停用的角色不在此检查（由 4.3 与 4.4 覆盖）。

### 4.3 运行时兜底

流程 API（`POST /api/processes/{name}/{version}`）在权限检查之后：操作人（令牌中的权限）同时持有某条规则两组的权限、且流程需要其中任一组的权限时，拒绝
（403 `SOD_CONFLICT`）。这覆盖规则发布前已存在的冲突。持有 `*` 的操作人不拦。以系统身份运行的事件消费者与定时任务不经此入口。

### 4.4 冲突报告

`GET /api/sod/conflicts`（权限 `sod.read`）：每个冲突一行——用户、用户名、规则、所持左组与右组的权限、授予这些权限的角色；持有 `*` 的用户对每条规则都列出（`wildcard: true`）。

### 4.5 测试

core `ApprovalConditionTest`、`ApprovalEvaluationTest`、`ContentHashTest`（属性测试：键顺序与小数位不影响哈希，任一值变化即变化）、`SodRuleTest`；
runtime `ApprovalChecksTest`；app `ApprovalIT`（四眼修改规则、结论与评估记录、绑定内容、层级/权限/限额/准备人、驳回、按业务时间、并发判断、只追加、影响预览）、
`SodIT`（两条写入途径、`*`、运行时兜底、冲突报告）、场景 `certification_approval`（场景步骤可用 `actor` 指定另一个操作人，07 §3）。

## 5. 任务与通知（14b-3，计划）

- 待办 `SysTask`（时态实体）：类型、标题（文案键与参数）、指派对象（用户或权限）、关联实体、链接、状态、截止时间；步骤 `CreateTask` / `CloseTasks`；审批请求自动建待办。
- `GET /api/tasks/mine`；平台页面 `/tasks`，页头显示待办数；`ApprovalPanel` 经 `@jabiz/admin` 导出。
- 邮件：`SecUser.email`（可选）；任务事件的消费流程记录通知（只追加）并在提交后发送，失败重试；`jabiz.mail.enabled` 缺省关闭；测试用 GreenMail。
