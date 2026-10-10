# 18 编号、审批、职责分离、任务与通知

业务应用常用的四项通用能力（ROADMAP 阶段 14b，决策 D23）：第 2 节（14b-1）、第 3、4 节（14b-2）与第 5 节（14b-3）均已实施。

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
    return ApprovalSubject.define("fin.journal", s -> s.entity("FinJournal").number("amount").text("source").bool("manual"));
}
```

| 项 | 规定 |
|---|---|
| 名称 | 同序列名（小写字母开头，1–100 个 `a-z 0-9 . _ -`）；全局唯一（启动检查 `APPROVAL`） |
| 事实 | `NUMBER`（按 `BigDecimal` 比较，金额即此类）、`TEXT`、`BOOLEAN`；名称为字母开头的字母、数字、`_` |
| 实体（可选） | `entity(实体类型)`：案例的 id 即该实体的主键。声明后，该实体记录的审计轨迹一并列出其审批（21 §1.3）；实体必须已声明（启动检查 `APPROVAL`） |

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

审批规则、审批人限额、职责分离规则（第 4 节）的数据视图都是 `processOnlyWrites`，只经受控变更 `SysControlChange`（时态实体）修改；
应用声明为受控的业务参数（04 §9.1，【D40】）同样只经受控变更修改：

| 流程 | 权限 | 做什么 |
|---|---|---|
| `CONTROL_CHANGE_PROPOSE` | `control.propose` | `{targetEntity, targetId?, delete?, values, effectiveTime?, reason}`：新建（无 `targetId`）、修改部分字段或删除；可预定生效。立即检查：可写字段、审批对象已声明、条件与层级符合对象的事实、职责分离的两组权限有效；错误一次报告（422 `CONTROL_CHANGE_INVALID`） |
| `CONTROL_CHANGE_PUBLISH` | `control.publish` | 由**提出人以外**的人发布（422 `CONTROL_SAME_PERSON`）；按目标的当前状态再检查一次，以提出的生效时间写入；只能发布一次（422 `CONTROL_CHANGE_NOT_PROPOSED`） |
| `CONTROL_CHANGE_WITHDRAW` | `control.propose` | 提出人撤回自己的提案 |

规则按业务时间生效：预定在将来生效的修改，只影响业务时间在其后的案件。

**业务参数**（【D40】）：`targetEntity: SysParam`，以 `values.paramKey` 指定参数（不给 `targetId`）；`values` 可有 `value`、`description`，参数尚不存在时另给 `valueKind` 即新建；
`effectiveTime` 须晚于当时（422 `EFFECTIVE_TIME_NOT_FUTURE`，发布时再检查一次）；`delete: true` 加 `effectiveTime` 取消该时刻的预定值（没有预定值即 422 `NOT_SCHEDULED`，提出与发布时都查）。
提出与发布时都按参数类型检查并规范化值（422 `PARAM_VALUE_INVALID`），并做写入时同样的字段校验；只能用于受控的键。提案记下所基于的参数（新建则无），
发布时参数已不是那个即 422 `CONTROL_TARGET_CHANGED`。发布以参数在生效时刻的版本为基础写入新版本，这一次写入带平台生成的许可，是受控参数唯一能通过的写入。

实现：每类目标是一个 `ControlTarget` Bean（runtime `approval`：加载所基于的状态、检查提案、检查并登记发布的写入）；审批规则、限额、职责分离规则为 `ApprovalControlTarget`，参数为 `ParamControlTarget`。

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

## 5. 任务与通知（14b-3）

### 5.1 待办

`SysTask`（时态平台实体，数据视图只经流程写入，读权限 `task.read`）：类型、标题（文案键 + 参数，按读者的语言显示）、指派对象（**一个用户或一个权限**，二者择一）、
关联实体与标识、链接（后台中的路径）、状态（`OPEN` / `DONE` / `CANCELLED`）、截止时间、来源键、关闭人。

```java
.step("Ask for the invoice", CreateTask.of(ctx -> TaskSpec.forUser("fin.invoice", "task.invoice", Map.of("order", no), owner)
    .about("Order", id).link("/orders/" + id).due(dueTime).source("invoice:" + id)))
.step("The invoice is in", CloseTasks.done(ctx -> "invoice:" + id))          // CloseTasks.cancel(...)：不再需要
```

- `TaskSpec.forPermission(…)`：该权限的每个持有人都能看到，谁先做完即关闭（按来源键）。类型为小写的点分名称。
- 两个步骤都在流程事务内写入（runtime `TaskWriter`）；新建的待办发布事件 `jabiz.task.created`（邮件通知由此触发，5.4）。
- 标题的文案键放在平台或应用的消息资源中（平台的审批待办：`task.approval`）。

### 5.2 审批待办

每个待审批的请求有一个待办：类型 `approval`、指派给当前层级的权限、关联 `SysApprovalRequest`、链接 `/tasks`、来源键 `approval:<请求>`。
标题为"审批 {对象} {单据}（第 n 级）"：对象显示消息 `approval.subject.<对象名>`（没有时为对象名），单据显示 `ApprovalCase.reference(…)` 给出的、人认得的标识
（如日记账号，存于请求的 `reference`，各层级的待办都用它；没有时为主键）。标识不在内容哈希中：内容相同的再次提交沿用待审批的请求与其原标识。【阶段 14r】
`RequireApproval` 建请求时同时建第一层的待办；`APPROVAL_DECIDE` 关闭当前层级的待办（`DONE`），还有下一层时建下一层的待办；请求被作废（内容变化）或撤回时待办取消。

### 5.3 我的待办与页面

- `GET /api/tasks/mine?limit=`（只要求已认证）：指派给本人、或指派给本人持有的权限的开放待办（持有 `*` 的用户看到全部按权限指派的待办），
  按截止时间（无截止的在后）、建立时间排序；返回总数与至多 200 条，标题按请求的语言。
- 通用后台页面 `/tasks`（平台路径，扩展不能占用）：列出我的待办；审批待办就地显示 `ApprovalPanel`（批准 / 驳回，驳回需理由；能否判断只由服务端决定），
  其他待办链接到其页面。页头显示开放待办数（每分钟刷新），点击进入 `/tasks`。
- `@jabiz/admin` 导出 `ApprovalPanel`（`{requestId, onDecided}`）与 `useMyTasks`，应用扩展可在自己的页面中使用（12 §9）。
- 已知限制：准备人若也持有审批权限，会看到自己请求的待办（判断时被拒，`APPROVAL_OWN_REQUEST`）。

### 5.4 邮件通知

- `SecUser.email`（可选，`SEC_USER_CREATE` 可带）。
- `jabiz.mail.enabled=true`（缺省关闭）时，消费者 `jabiz.task-notifications` 对每个 `jabiz.task.created` 以系统身份运行 `TASK_NOTIFY`：
  收件人为指派的用户，或按启用的角色**明确**持有该权限的每个启用用户（`*` 不算，免得管理员收到所有待办）；只发给有邮箱的人。
  在流程事务内为每个收件人写一条只追加的 `Notification`（标题按平台缺省语言，正文附链接 `jabiz.mail.base-url` + 待办链接），
  **提交后**的步骤逐条发送：每次尝试写入只追加的 `sys_notification_attempt`（`SENT` / `FAILED`），已发送的不再发送，有失败即按重试策略
  （5 次，1 秒起加倍）重试整个步骤。
- 发送经 `NotificationSender`（缺省 `SmtpNotificationSender`，Spring 的 `JavaMailSender`，`spring.mail.*`，发件人 `jabiz.mail.from`）。
  单据（22 §5）以同一发送器发出，带附件的消息为 `send(MailMessage)`（不能附件的发送器拒绝，而不是丢掉附件后发出）。
  开启邮件而缺少邮件服务器或发件人：启动检查 `MAIL` 报错。

### 5.5 表与测试

| 表 | 性质 |
|---|---|
| `sys_task_version` | 时态（D5），平台实体 `SysTask` |
| `sys_notification`、`sys_notification_attempt` | 只追加（D5 触发器）；前者为平台实体 `Notification`（只读视图） |

测试：runtime `TaskSpecTest`、`MailChecksTest`；app `TaskIT`（按用户 / 权限 / `*` 可见、开关待办与事件、审批待办逐层传递与关闭、作废与撤回取消、只追加）、
`NotificationIT`（GreenMail：指派用户收一封、按权限只发给启用且明确持有的有邮箱用户、失败记录后重试成功）；前端 `ApprovalPanel.test`、`TasksPage.test`、
e2e `tasks.spec`。

### 5.6 事务邮件（16a，决策 D35）

流程中发送的通用邮件：验证邮箱、找回密码（D36）、"已发货"等通知。与 §5.4 的待办通知不同，它经 Outbox 投递（持久的退避重试），按收件人的语言渲染，
带一次性令牌与退订。

**模板**：`MailTemplate` Bean，`MailTemplate.define(名, t -> t.category(TRANSACTIONAL | NOTIFICATION).param("…").token("verify", Duration.ofHours(24)))`。

- 名称规则同事件名；参数与令牌用途为不含下划线的标识符（`[a-z][A-Za-z0-9]*`，免得 Markdown 把 `_` 当作强调），不能与内置占位符同名，不能重复。
- `TRANSACTIONAL`（验证、重置、安全通知）不能退订；`NOTIFICATION` 可按用户与模板退订，其正文必须使用 `{unsubscribeUrl}`。
- 标题与正文在消息 `mail.<名>.subject` / `mail.<名>.body`（应用所选的每种语言）；正文为 Markdown。占位符：声明的参数、令牌（以用途为名，值为令牌原文）、
  `{baseUrl}`（`jabiz.mail.base-url`）、`{unsubscribeUrl}`（仅 NOTIFICATION）。

**渲染**（core `MailRenderer`，commonmark-java）：先解析 Markdown，再把值放进解析结果（文本、代码、链接目标与标题）——值从不被当作 Markdown 或 HTML。
HTML 部分转义全部文本与正文中的原始 HTML，纯文本部分为原值；链接只允许 `http`、`https`、`mailto` 或相对地址（包括由值拼出的地址，其他一律去掉）；
链接目标中的参数做百分号编码，`{baseUrl}`、`{unsubscribeUrl}` 与令牌原样代入；标题为纯文本、去换行、截断 300。不做模板样式，只用 commonmark 的基本元素。

**发送**：步骤 `SendMail.of(模板, 收件人, Map<参数名, 取值函数>)` / `SendMail.when(条件, …)`（平台 I/O 步骤）。

- 收件人 `MailRecipient.user(用户)`（取其 `email` 与 `locale`）、`user(用户, 地址, 语言)`（显式给出）、`address(地址, 语言)`。NOTIFICATION 只发给用户（退订按用户）。
- 用户没有地址：登记违规 `MAIL_NO_ADDRESS`（422）；流程可用 `when` 跳过。
- 语言：显式给出 → 用户的 `SecUser.locale` → 平台缺省语言；不在应用语言中时取缺省。
- 参数值以文本**原样**保存（`BigDecimal` 为普通写法；日期、金额的格式由流程决定），邮件即由它渲染；`op_process.input_summary` 照旧遮蔽。
  邮件中唯一的秘密是发送时才生成的令牌，因此秘密不能作为参数：名称像秘密或遮蔽字段的参数（`SensitiveDataMasker.hidesByName`）在启动检查中报错，
  `@Sensitive` 的值在 `SendMail` 中以 422 `MAIL_PARAM_SECRET` 拒绝；参数的 JSON 超过 16 KiB（`SendMail.MAX_PARAMS`）以 422 `MAIL_PARAMS_TOO_LARGE` 拒绝，不截断。
- 在流程事务内经变更集写 `MailMessage`（`sys_mail_message`，经审计），并发布 `jabiz.mail.queued`（`{messageId}`）。流程回滚即无消息、不发信。
- 启动检查（`CheckedStep`）：模板是已声明的 Bean、参数名与声明一致。

**投递**：平台消费者 `jabiz.mail`（始终注册）对每个 `jabiz.mail.queued` 以系统身份运行 `MAIL_SEND`（权限 `mail.send`，内部流程）：

1. 已有 `SENT` 或 `SKIPPED` 尝试 → 不再处理。
2. `jabiz.mail.enabled=false`（缺省）→ 记 `SKIPPED`（`MAIL_DISABLED`）；NOTIFICATION 且收件用户已退订 → `SKIPPED`（`UNSUBSCRIBED`）。
3. 否则在 `boundedElastic` 上取随机数，为每个令牌用途生成 32 字节（URL 安全 Base64）的令牌，在**独立事务**中把 SHA-256 写入 `sys_mail_token`
   （`StorageEngine.inNewTransaction`，`PROPAGATION_REQUIRES_NEW`），然后按消息的语言渲染，经 `NotificationSender.send(MailMessage)` 发送
   （`MailMessage` 增加 `htmlBody`，SMTP 发 `multipart/alternative`）；服务器收下后立即在独立事务中记 `SENT`（时间为发送之后）。
   之后再失败（例如投递事务提交失败）既不会重发（重试见到 `SENT`），也不会丢掉已发出邮件中的令牌。
4. 发送前或发送中失败：在独立事务中记 `FAILED`（同 `OutboxDeliverer` 记录失败的做法），再使流程失败，由 Outbox 的退避重试（D14，`jabiz.events.delivery.*`）重发；
   每次重试生成新令牌，使失败那次已入库的令牌失效（若重试次数用尽，收件人此前邮件中的同用途令牌也已被这次未发出的令牌取代）。

风险与限制：SMTP 在投递事务中调用，慢服务器会占用数据库连接；`OutboxDeliverer` 依次处理各消费者，慢的邮件服务器因此也推迟其他消费者
（Webhook、业务订阅）的投递。以 `jabiz.mail.timeout`（缺省 10 秒，在 `JavaMailSender` Bean 创建时设置 `mail.smtp(s).connectiontimeout` / `timeout` /
`writetimeout`（`MailSenderTimeouts`），`spring.mail.properties` 可覆盖）与 `jabiz.events.delivery.batch-size` 控制，本阶段不改投递器。令牌与尝试的独立事务各取一个连接。
服务器收下了邮件却报告失败（应答丢失）时会再发一次（至少一次），前一封中的令牌已被取代。

**一次性令牌的使用**：步骤 `MailTokens.consume(用途, 取令牌的函数, 目标键)`（平台 I/O 步骤；D36 的验证邮箱与找回密码用它）。按 SHA-256 查找，要求：用途一致；
操作时间早于过期；是同一收件人（用户；无用户时为地址）同一用途的**最新**令牌（`issue_seq`，后发的邮件与重试都使先前的失效）；未用过
（`INSERT … ON CONFLICT DO NOTHING` 到 `sys_mail_token_use`，主键保证只用一次，冲突不中止事务）。成功时目标键为 `MailTokens.Consumed(messageId, userId, address)`；
否则一律 422 `TOKEN_INVALID`（不告诉调用方原因）。启动检查：用途须由某个模板声明。

**退订与语言**（D35 第 4–5 条）：

- `SecUser.locale`（可选，`zh` / `ja` / `en`）；用户本人经 `POST /api/auth/account/locale {locale}`（流程 `SEC_USER_SET_LOCALE`，权限 `auth.account` 不授予角色，
  只能改自己的）修改，管理员经 `SecUser` 数据视图修改。
- `SecUserMailPreference`（时态，用户 × 模板 → `subscribed`；无记录即接收）只经 `SEC_MAIL_PREFERENCE_SET` 写入（权限 `auth.mail-preference` 不授予角色，
  只能由该用户本人运行；只接受 NOTIFICATION 模板）。
- `{unsubscribeUrl}` = `jabiz.mail.base-url` + `jabiz.mail.unsubscribe-path`（缺省 `/mail/unsubscribe`，通用后台的 `MailUnsubscribePage`，无需登录）+ `?token=`；令牌为 `JwtService` 的
  `jabiz-unsub+jwt`（用户 + 模板，签名，无过期；轮换 `JABIZ_JWT_SECRET` 使旧链接失效），不能代替访问令牌或挑战令牌，反之亦然。
- `POST /api/auth/mail/unsubscribe {token}`：匿名（令牌即凭证）、幂等，204；篡改或其他类型的令牌、非 NOTIFICATION 模板 → 422 `TOKEN_INVALID`。
- 登录用户：`GET /api/auth/mail/preferences`（NOTIFICATION 模板及是否接收）、`POST /api/auth/mail/preferences {template, subscribed}`。后台页面在 15c 或 16c 加入。

**查询**：平台实体 `MailMessage`（`sys_mail_message`，`mail.read`，`processOnlyWrites()`，经变更集写入并审计）与 `MailAttempt`
（`sys_mail_attempt`，每次尝试一条；最新一条即消息的状态；数据视图 `readOnly`）。尝试与令牌是投递日志，由平台直接插入，不进审计（21 §1），靠封存防篡改。实体不能连接其他表，所以"最近一次尝试的结果"是 `MailAttempt` 中按 `messageId` 筛选的最新一行；场景回放把两者列入快照即可看到。

**启动检查 `MAIL`**（`com.jabiz.runtime.mail.MailChecks`，含 §5.4 的检查）：模板名唯一；每种语言都有标题与正文；占位符与声明一致（未声明的占位符、未使用的参数或令牌、
NOTIFICATION 缺 `{unsubscribeUrl}`）；开启邮件时需 `jabiz.mail.from`、邮件服务器，以及有模板链接到应用（`{baseUrl}`、NOTIFICATION）时的 `jabiz.mail.base-url`。

**不变**：`TASK_NOTIFY` 与单据发送（22 §5）仍是 `AFTER_COMMIT` 的进程内重试、纯文本，以后另议。

| 表（`V31__mail.sql`） | 性质 |
|---|---|
| `sec_user_version.locale` | 新列 |
| `sys_mail_message` | 只追加；平台实体 `MailMessage` |
| `sys_mail_attempt` | 只追加；主键（消息, 次序）；`attempt_id` 为平台实体 `MailAttempt` 的键 |
| `sys_mail_token` | 只追加；主键 `token_hash`（SHA-256），`issue_seq` 定先后 |
| `sys_mail_token_use` | 只追加；主键 `token_hash` |
| `sec_user_mail_preference_version` | 时态，平台实体 `SecUserMailPreference`，唯一（用户, 模板） |

示范：`backend/app` 的 `ORDER_SHIP` 给客户主数据 `Customer`（管理员维护，可选 `userId`）中有账号且有地址的客户发 NOTIFICATION `commerce.order-shipped`（三语）——收件人来自主数据，不来自下单输入；场景
`commerce/order_shipped_mail`。测试：core `MailTemplateTest`、`MailRendererTest`；runtime `MailChecksTest`、`MailSenderTimeoutsTest`、`JwtServiceTest`；app `MailIT`、`MailDisabledIT`；前端 `MailUnsubscribePage.test`。
