# 11 账本、事件、审计、定时任务

ROADMAP 阶段 9 的四个通用业务基础模块。约束性细则见【决策 D14】。四者都建立在已有机制之上：
账本是时态实体 + 流程；事件是流程事务内写入的 Outbox；审计是对操作表的查询；定时任务只调用流程。

## 1. 复式记账

### 1.1 实体（平台时态实体，runtime `com.jabiz.runtime.ledger`，迁移 `db/jabiz/V8__ledger.sql`）

| 实体 | 表 | 要点 | 数据视图（读 / 写权限） |
|---|---|---|---|
| `LedgerAccount` | `ledger_account_version` | `accountCode`（唯一、不可变）、`accountName`、`accountType`（`ASSET` `LIABILITY` `EQUITY` `REVENUE` `EXPENSE`，不可变）、`enabled`；可预定 | `ledger.account.read` / `ledger.account.write` |
| `LedgerTransaction` | `ledger_transaction_version` | `bookingTime`（业务时间）、`description`、`reference`、`reversesTransactionId`（→ `LedgerTransaction`，唯一：一笔交易至多被冲正一次，D6）；全部字段不可变；发布变更事件 | `ledger.read` / `ledger.post`，**`processOnlyWrites`** |
| `LedgerEntry` | `ledger_entry_version` | `transactionId`、`accountId`、`lineNo`、`direction`（`DEBIT` / `CREDIT`）、`amount`（`Monetary`，正数）；全部字段不可变 | 同上，**`processOnlyWrites`** |

- 单一币种：`Monetary` 在字段上声明币种与小数位，因此账本的金额按配置 `jabiz.ledger.currency`（默认 `JPY`）、`jabiz.ledger.scale`（默认 0，至多 4）构建；列为 `numeric(19,4)`。多币种不在本阶段。
- 交易与分录只经账本流程写入：数据视图 API 的 `commit` 与通用实体流程一律拒绝（422 `PROCESS_ONLY_DATASET`），
  撤销（D2）也拒绝（422 `REVERT_NOT_ALLOWED`）——**更正只能是冲正交易**。

### 1.2 流程

| 流程 | 权限 | 作用 |
|---|---|---|
| `LEDGER_ACCOUNT_OPEN` | `ledger.account.write` | 开立科目（科目也可经其数据视图维护） |
| `LEDGER_POST` | `ledger.post` | 过账：`{bookingTime?, description, reference?, entries: [{accountCode, direction, amount}]}` |
| `LEDGER_REVERSE` | `ledger.reverse` | 冲正：`{transactionId, reason, bookingTime?}`，生成借贷互换的新交易，`reversesTransactionId` 指向原交易 |

- 写入前校验（core `com.jabiz.ledger.LedgerPosting`，纯逻辑）：至少 2 条、至多 200 条分录；金额为正（`LEDGER_AMOUNT_NOT_POSITIVE`）且不超过账本小数位
  （`LEDGER_AMOUNT_SCALE`）；借贷相等（`LEDGER_UNBALANCED`）；科目存在（`LEDGER_ACCOUNT_NOT_FOUND`）且启用（`LEDGER_ACCOUNT_DISABLED`）。
  全部违规一次返回（422），不写入任何数据。
- 冲正：已被冲正的交易（`LEDGER_ALREADY_REVERSED`）与冲正交易本身（`LEDGER_REVERSAL_NOT_REVERSIBLE`）不能再冲正；
  并发冲正由 `reversesTransactionId` 的唯一性（D6）拦下（400 `UNIQUE_VIOLATION`）。原交易保持不变。
- `bookingTime` 缺省为操作时间；业务流程应传入业务发生时间（与 04 §9 的参数读取一致）。
- 业务流程以子流程方式过账：`CallProcess.of("LEDGER_POST", 1, ctx -> new PostInput(...), 结果键)`（同一事务，06 §5）。

### 1.3 数据库兜底

`ledger_entry_version` 上有 `DEFERRABLE INITIALLY DEFERRED` 的约束触发器：数据库事务提交时，本事务写入过分录的每笔交易，
其当前分录必须借贷相等，否则提交失败（SQLSTATE `JZ002`）。这是写入前校验之外的第二道防线（手写 SQL、将来的代码缺陷）。

### 1.4 余额

SQL 模板 `jabiz.ledger.account_balances`（runtime 资源 `queries/jabiz/ledger/account_balances.sql`，权限 `ledger.read`）：
参数 `asOf`（按 `bookingTime`），每个科目返回 `debitTotal`、`creditTotal`、`balance`（= 借方 − 贷方）。
因此借贷平衡的账本上**全部科目余额之和为零**。core 的 `LedgerBalances` 是同一口径的内存模型（属性测试用）。

## 2. 实体变更事件与 Outbox

### 2.1 为什么自己实现

Spring Modulith 的事件发布注册表只支持 JPA、JDBC、MongoDB、Neo4j，且依赖 `@TransactionalEventListener`（响应式事务下不触发），
不支持 R2DBC。因此自行实现事务性 Outbox（迁移 `db/jabiz/V9__outbox.sql`，三张表都只追加并受 D5 触发器保护）。

| 表 | 内容 |
|---|---|
| `sys_outbox_event` | `event_id`（UUIDv7）、`event_seq`、`event_type`、`entity_type` / `entity_id`（实体变更事件）、`payload`（jsonb，秘密为 null）、`process_seq_id`、`created_time`（操作时间） |
| `sys_event_consumption` | `(consumer, event_id)` 主键、`process_seq_id`、`consumed_time`：某消费者已处理某事件 |
| `sys_outbox_attempt` | `(consumer, event_id, attempt)`、`error`、`attempted_at`：每次失败的投递 |

### 2.2 发布

- **`PublishEvent` 步骤**：`PublishEvent.of(类型, ctx -> 载荷)`（或 `PublishEvent.when(条件, 类型, ctx -> 载荷)`），由 `OutboxEventPublisher` 在流程事务内写入 Outbox。
  载荷必须是对象（record 或 Map），经 `SensitiveDataMasker` 去掉秘密（null）。事务回滚 → 没有事件。
- **实体变更事件**：实体声明 `eb.publishChanges()`（元模型导出 `publishesChanges`）后，每次提交的写入都在同一事务中追加事件
  `jabiz.entity-changed.<实体>`，载荷 `{entityType, entityId, action, version, effectiveTime?, changedFields}`：
  - 所有写入途径都产生：数据视图 `commit`、流程的 `ChangeSet`、撤销（`action = REVERT`）；普通实体在 insert / update / delete 处，时态实体在写入的主版本处（变基副本不另发）。
  - **只含字段名，不含值**（值可能是个人信息或秘密）；消费者需要值时自行读取实体。
  - 预定版本到期时没有写入，也不另发事件；消费者可按 `effectiveTime` 自行处理。
- 事件类型名：字母、数字、`. _ : -`，至多 100 字符（启动检查）。

### 2.3 投递与消费

- 消费者是 **`EventSubscription`**（core，声明为 Bean）：`EventSubscription.of(消费者名, 事件类型, 流程, event -> 输入)`。**消费者只调用流程**，以系统身份执行。
- `OutboxDeliverer` 轮询（`jabiz.events.delivery.poll-interval`，默认 1 s；`enabled=false` 时由调用方显式调用 `deliverPending()`），
  按订阅取出未消费且已到期的事件（每个消费者每轮至多 `batch-size`，默认 20），逐个投递。
- **至少一次投递 + 消费端幂等**：每个 (消费者, 事件) 在消费者流程自己的事务中执行，事务的第一件事（记录操作之后、第一个步骤之前，
  `ExecutionOptions.beforeSteps`）是插入 `sys_event_consumption` 行：
  - 流程失败 → 标记随之回滚，之后重试（至少一次）；
  - 并发或重复的投递（另一个实例、另一轮轮询、崩溃后重来）在该主键上等待，第一个提交后失败回滚，**不产生任何效果**（只处理一次）；
    之后的轮询按标记跳过它。
- 失败重试：每次失败在独立事务中追加 `sys_outbox_attempt`；第 n 次失败后等待 `initial-backoff × 2^(n-1)`（默认 5 s 起，上限 `max-backoff` 1 h）；
  `max-attempts`（默认 10）次后不再重试，记 error 日志，由运维处理。
- 不保证顺序；新增的订阅会收到该类型的历史事件（按 `event_seq`）。
- 启动检查（类别 `EVENT`）：消费者名唯一；订阅的流程是已注册的 Bean。

## 3. 审计视图

`GET /api/audit/operations`（权限 `audit.read`）：基于 `op_process` / `op_process_item` 的只读查询。

| 参数 | 含义 |
|---|---|
| `actorId` | 按人 |
| `from` / `to` | 按时间：`op_time ∈ [from, to)`（ISO-8601） |
| `processName` | 按流程（数据视图提交为 `jabiz.dataset.commit`） |
| `entityType` / `entityId` | 按实体：写过该实体（类型、实例）版本的操作 |
| `offset` / `limit` | 分页，`limit` 默认 50、至多 500 |

- 响应 `{items, total, offset, limit}`，按 `op_time`、`process_seq_id` 倒序；每项为操作（与操作详情相同的字段）及其写入的版本
  （实体类型、主键、版本号、动作、生效时间、`changedFields`）——**只有名称与标识，没有字段值**。
- 非法参数一次性返回全部违规（400 `INVALID_VALUE`）。所有值参数绑定，SQL 为固定文本。
- 按实体查询只覆盖记入 `op_process_item` 的写入，即时态实体；普通实体的写入没有条目（04 §2.2）。

## 4. 定时任务

- 声明：`JobDefinition.cron(名称, cron, 时区, 流程, scheduledTime -> 输入)`（core，Bean）。cron 为 Spring 六字段格式（秒 分 时 日 月 周）；
  名称 1–64 个字母、数字、`. _ -`（同时是集群锁名）；`lockAtMostFor` 默认 10 分钟。**任务只负责调用流程**。
- 触发：`JobScheduler` 用 Spring 的 `ThreadPoolTaskScheduler`（与 `@Scheduled` 同一套调度机制，时钟为注入的 `Clock`）按 cron 触发，
  把 cron 指定的时刻作为"计划时刻"交给 `JobRunner`。实例停机期间错过的时刻不补跑。`jabiz.jobs.scheduler.enabled=false` 时不自动触发
  （测试、场景回放以显式调用代替）。选择声明式而不是在业务 Bean 上写 `@Scheduled` 方法：任务是元数据，可以启动检查、列出、在场景中调用【D14】。
- 执行（`JobRunner.run(job, scheduledTime)`，阻塞，只在调度线程或测试线程上调用）：
  1. ShedLock（`shedlock-provider-r2dbc`，表 `jabiz_shedlock`）取该任务的集群锁：别的实例持有时什么也不做（`LOCKED`）；
     锁在运行结束后至少保持 `jabiz.jobs.lock-at-least-for`（默认 30 s），时钟略慢的实例不会再跑同一时刻。
  2. 以系统身份执行流程，幂等键 `job:<名称>:<计划时刻>`（D4/D11）：即使锁已过期或各实例时钟不同，**同一计划时刻至多执行一次**，
     重复的执行重放第一次的结果（`REPLAYED`）。
  3. 写执行记录 `sys_job_run`（只追加）：计划时刻、实例、结果 `SUCCEEDED` / `REPLAYED` / `FAILED`、`process_seq_id`、错误、起止时间（取自 `Clock`）。
- 锁的时间用真实时间（基础设施时间，不是业务时间）；计划时刻与流程的 `opTime` 来自注入的 `Clock`。`jabiz_shedlock` 表由 ShedLock 原地更新，属基础设施表，不受 D5 保护。
- `GET /api/jobs`（权限 `job.read`）：任务、cron、时区、流程、下一次计划时刻、最近 10 次执行。
- 启动检查（类别 `JOB`）：名称唯一；cron 合法；流程是已注册的 Bean。
- 场景回放：步骤 `runJob: {job, at, outcome}`（`at` 缺省为当前时钟，`outcome` 缺省 `SUCCEEDED`）与 `deliverEvents: true`（07 §3.1）。

## 5. 示范（`app` 的 `FreightBilling`）

- 任务 `logistics.freight-month-close`：每月 1 日 00:05（东京时间）关闭上个月（`FREIGHT_MONTH_CLOSE`）。
- 关闭成功时发布 `logistics.freight-month-closed`（载荷为关闭结果）；消费者 `logistics.freight-revenue` 执行 `FREIGHT_POST_REVENUE`，
  以子流程 `LEDGER_POST` 过账：借 应收账款 `1130` / 贷 运费收入 `4110`，记账时间为该月最后一刻；无运费的月份不过账（`CallProcess.when`）。
- 场景 `scenarios/freight/monthly_close.yml` 覆盖：开立科目、任务关账、投递事件、按时间点查询余额。

## 6. 测试（阶段 9 验收）

| 验收标准 | 测试 |
|---|---|
| jqwik 属性测试：任意交易序列后每笔交易借贷平衡、全部科目余额之和为零 | core `LedgerProperties`（`@Property`，纯逻辑）；app `LedgerPropertyIT`（同样的 jqwik 生成器、固定种子，经流程写入真实数据库后按原始表与余额模板断言） |
| 两个实例同时运行时，同一任务只执行一次 | `JobIT`：两个"实例"各有连接池、锁提供者与实例 id，同时触发同一计划时刻 → 一个 `SUCCEEDED`、一个 `LOCKED`；锁过期时幂等键仍保证一次 |
| 事务回滚时不产生事件；投递失败会重试，消费端不会重复处理 | `OutboxIT`：流程回滚、数据视图提交失败均无事件；消费者前两次失败后按退避重试成功且只留一次效果；重复投递与 4 路并发投递都只处理一次 |

另有 `LedgerIT`（过账、余额、冲正、只能经流程写入、撤销被拒、数据库兜底、只追加）、`AuditIT`、`JobChecksTest`、场景回放。
