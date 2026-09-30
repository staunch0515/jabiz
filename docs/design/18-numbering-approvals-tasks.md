# 18 编号、审批、职责分离、任务与通知

业务应用常用的四项通用能力（ROADMAP 阶段 14b，决策 D23）。本文件随子阶段逐步补全：第 2 节已实施（14b-1）；第 3、4 节（14b-2）与第 5 节（14b-3）
是已确认的计划，实施时细化。

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

## 3. 审批（14b-2，计划）

- 审批对象（代码声明）：可用于条件的事实、参与内容哈希的内容、可选的历史事实模板（影响预览）。
- 规则 `SysApprovalRule`、审批人限额 `SysApprovalLimit`：时态实体，只经流程改变（提出、由另一人发布），规则可预定生效。
- 步骤 `RequireApproval`：按业务时间评估，结论为不需审批（记录所评估的规则版本）、需审批（建请求：准备人、规则版本、内容哈希、层级）或已批准（内容哈希一致）。
- 流程 `APPROVAL_DECIDE`：审批人持有层级权限、在限额内、不是准备人、未审批过本请求的其他层级；最后一层批准后发事件 `jabiz.approval.approved`，应用以订阅继续原流程。
- 影响预览：以规则草案对历史事实重新评估，列出结论不同的单据与评估总数。

## 4. 职责分离（14b-2，计划）

- 规则 `SysSodRule`（两组互斥权限），修改由另一人发布。
- 预防：`SecUserRole`、`SecRolePermission` 的全部写入途径上的写入检查（`FieldWriteCheck`），造成冲突即拒绝（`SOD_CONFLICT`）。
- 运行时兜底：流程入口检查操作人是否同时持有互斥权限；`*` 不拦但列入冲突报告。
- 冲突报告：用户、规则、来源角色。

## 5. 任务与通知（14b-3，计划）

- 待办 `SysTask`（时态实体）：类型、标题（文案键与参数）、指派对象（用户或权限）、关联实体、链接、状态、截止时间；步骤 `CreateTask` / `CloseTasks`；审批请求自动建待办。
- `GET /api/tasks/mine`；平台页面 `/tasks`，页头显示待办数；`ApprovalPanel` 经 `@jabiz/admin` 导出。
- 邮件：`SecUser.email`（可选）；任务事件的消费流程记录通知（只追加）并在提交后发送，失败重试；`jabiz.mail.enabled` 缺省关闭；测试用 GreenMail。
