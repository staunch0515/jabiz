# 11 账本、事件、审计、定时任务

ROADMAP 阶段 9 的四个通用业务基础模块。约束性细则见【决策 D14】。四者都建立在已有机制之上：
账本是时态实体 + 流程；事件是流程事务内写入的 Outbox；审计是对操作表的查询；定时任务只调用流程。

## 1. 复式记账

### 1.1 实体（平台时态实体，runtime `com.jabiz.runtime.ledger`，迁移 `db/jabiz/V8__ledger.sql`）

| 实体 | 表 | 要点 | 数据视图（读 / 写权限） |
|---|---|---|---|
| `LedgerAccount` | `ledger_account_version` | `accountCode`（唯一、不可变）、`accountName`、`accountType`（`ASSET` `LIABILITY` `EQUITY` `REVENUE` `EXPENSE`，不可变）、`enabled`、`parentId`（上级科目）、`summary`（汇总科目，空 = 可过账）（1.4）；可预定 | `ledger.account.read` / `ledger.account.write` |
| `LedgerTransaction` | `ledger_transaction_version` | `bookingTime`（业务时间）、`description`、`reference`、`sourceEntity` / `sourceId`（来源单据，1.6）、`reversesTransactionId`（→ `LedgerTransaction`，唯一：一笔交易至多被冲正一次，D6）；全部字段不可变；发布变更事件 | `ledger.read` / `ledger.post`，**`processOnlyWrites`** |
| `LedgerEntry` | `ledger_entry_version` | `transactionId`、`accountId`、`lineNo`、`direction`（`DEBIT` / `CREDIT`）、`amount`（`Monetary`，正数，记账本位币）、`currency` / `transactionAmount` / `exchangeRate`（外币分录，1.8）、`memo`、`dimension1`–`dimension4`（1.5）；全部字段不可变 | 同上，**`processOnlyWrites`** |

- 记账本位币：`Monetary` 在字段上声明币种与小数位，因此账本的金额按配置 `jabiz.ledger.currency`（默认 `JPY`）、`jabiz.ledger.scale`（默认 0，至多 4）构建；列为 `numeric(19,4)`。多币种不在本阶段。
- 交易与分录只经账本流程写入：数据视图 API 的 `commit` 与通用实体流程一律拒绝（422 `PROCESS_ONLY_DATASET`），
  撤销（D2）也拒绝（422 `REVERT_NOT_ALLOWED`）——**更正只能是冲正交易**。

### 1.2 流程

| 流程 | 权限 | 作用 |
|---|---|---|
| `LEDGER_ACCOUNT_OPEN` | `ledger.account.write` | 开立科目（科目也可经其数据视图维护） |
| `LEDGER_POST` | `ledger.post` | 过账：`{bookingTime?, description, reference?, sourceEntity?, sourceId?, entries: [{accountCode, direction, amount?, memo?, dimensions?, currency?, transactionAmount?, exchangeRate?}]}` |
| `LEDGER_REVERSE` | `ledger.reverse` | 冲正：`{transactionId, reason, bookingTime?}`，生成借贷互换的新交易（行备注、维度、来源单据、外币与汇率照原样），`reversesTransactionId` 指向原交易 |

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

### 1.4 科目层级（14c-1，决策 D24）

- `parentId` 指向上级科目；`summary = true` 的是**汇总科目**：只用于归组，不能过账（422 `LEDGER_ACCOUNT_NOT_POSTABLE`）。字段为空即可过账（旧数据如此）。
- 写入检查（`LedgerAccountCheck`，全部写入途径）：上级必须是汇总科目（`LEDGER_PARENT_NOT_SUMMARY`）、不能是自己或自己的下级（`LEDGER_ACCOUNT_CYCLE`）；
  已有分录的科目不能改为汇总（`LEDGER_SUMMARY_HAS_ENTRIES`）；有下级的汇总科目不能改为可过账（`LEDGER_ACCOUNT_HAS_CHILDREN`）。按每个科目最新的版本判断（含预定的）。
- 层级的修改持有账本科目的事务级 advisory lock（独占），过账持有同一把锁（共享）：科目改为汇总与向它过账不会相互越过。

### 1.5 分析维度（14c-1，决策 D24）

分录有 4 个维度列 `dimension1`–`dimension4`（文本，至多 100 字符）。应用以 Bean 声明用哪几个、在过账输入中的名称与取值来源（core `LedgerDimension`）：

```java
@Bean LedgerDimension department() { return LedgerDimension.define(1, "department", d -> d.dictionary("urn:jabiz:dict:fin:department")); }
@Bean LedgerDimension location()   { return LedgerDimension.define(2, "location", d -> d.entity("FinLocation", "locationCode")); }
```

- 过账输入的每行 `dimensions: {"department": "SALES"}`：未声明的名称 `LEDGER_DIMENSION_UNKNOWN`；取值不在字典的启用代码中、或不是该实体当前实例的字段值（经其默认视图读取）
  `LEDGER_DIMENSION_INVALID`；全部一次报告。维度可选，没有取值即为空。
- 实体来源的字段可以是文本、代码、身份或引用字段（16h）。身份字段只能是来源实体的主键（经主键查找，时态实体走当前版本索引），
  如 `d.entity("SecUser", "userId")` 以用户编号为维度；引用字段的取值是当前实例引用的编号。这两种来源的编号必须是 UUID，
  即来源实体（身份）或被引用的实体（引用）是时态实体。取值写成完整的 UUID（去掉首尾空白、大小写不限，分录上存小写的规范形式，
  校验与存储用同一个 `LedgerDimension.canonicalId`）；其他写法（如 `1-2-3-4-5`）或不存在的编号即 `LEDGER_DIMENSION_INVALID`。
- 启动检查 `LEDGER`（错误）：位置或名称重复；实体来源的实体、默认视图不存在，或字段不是文本、代码、身份、引用字段；身份字段不是主键；
  身份或引用的编号不是 UUID（实体不是时态实体）。告警：时态实体的来源字段可变（任何种类；只有按不可变字段的条件能缩小读取的版本，
  决策 D29，否则要读全部当前实例）；非时态实体不告警。字典可能由数据库提供，不在启动时检查。

### 1.6 行备注与来源单据（14c-1，决策 D24）

- 每行可带 `memo`（至多 500 字符）。
- 交易可带 `sourceEntity` + `sourceId`：过账的单据（发票、日记账……）。实体须已注册且该实例存在（经其默认视图读取），否则 422 `LEDGER_SOURCE_NOT_FOUND`。
  来源在交易上，每行继承；报表据此钻取到单据。

### 1.7 余额与明细

SQL 模板（runtime 资源 `queries/jabiz/ledger/`，权限 `ledger.read`；金额为记账本位币）：

| 模板 | 参数 | 结果 |
|---|---|---|
| `jabiz.ledger.account_balances` | `asOf`（必填，按 `bookingTime`）、`from`（可选，区间起点）、`knownAt`（可选：只计记录时间不晚于它的交易） | 每个科目的 `debitTotal`、`creditTotal`、`balance`（= 借方 − 贷方），以及 `parentCode`、`summary`、`level`；**汇总科目为其全部下级之和** |
| `jabiz.ledger.dimension_balances` | `dimension`（位置 1–4）、`asOf`、`from?`、`knownAt?` | 按科目与维度取值（无取值为空串）的借贷合计与余额 |
| `jabiz.ledger.account_activity` | `account`（科目代码）、`from`、`asOf`、`knownAt?` | 期初余额（`OPENING`）、区间内每条分录（交易、摘要、参考、来源单据、行号、备注、维度、借、贷、滚动余额，`ENTRY`）、期末（`CLOSING`）；按 `seq` 排序 |

- "按当时所知"（`knownAt`）：交易与分录只追加、从不修改，因此以交易的记录时间（`createdTime`）筛选即可重现当时的余额；更正是冲正交易，有自己的记录时间。
- 借贷平衡的账本上**全部可过账科目的余额之和为零**（汇总科目重复其下级，不计入）。core 的 `LedgerBalances` 是同一口径的内存模型（属性测试用）。
| `jabiz.ledger.currency_balances` | `asOf`、`knownAt?` | 按科目与交易币种（本位币分录为空串）的交易币种余额 `transactionBalance` 与本位币余额 `balance`：重估所需的外币未结金额 |

`account_activity` 的分录行另有 `currency`、`transactionAmount`、`exchangeRate`。

- `ledger.post` 可以不授予任何角色：业务流程以子流程过账不检查调用方的权限（06 §5），于是控制科目只能由其子账的流程过账。

### 1.8 多币种（14c-2，决策 D24 第 5 条）

- `amount` 始终是**记账本位币**金额（`jabiz.ledger.currency` / `jabiz.ledger.scale`）。外币分录另记 `currency`（ISO 4217，不能是本位币）、
  `transactionAmount`（正数，不超过该币种的标准小数位，如 JPY 0、USD 2、KWD 3）与 `exchangeRate`（每单位外币折合的本位币，正数，至多 10 位小数）。
  三者全空即本位币分录（含旧数据）。
- 本位币金额 = `transactionAmount × exchangeRate`，按账本小数位**四舍五入（远离零）**：输入省略 `amount` 时由平台算出；给出时必须与之相等
  （`LEDGER_FX_AMOUNT_MISMATCH`，附应有的金额）。分行换算的尾差、结算的汇兑损益由调用方另加本位币行（如收款：借银行 EUR@收款汇率、
  贷应收 EUR@原汇率、差额记汇兑损益）。
- 平衡：**每个外币**的借贷各自相等（`LEDGER_UNBALANCED_IN_CURRENCY`，附币种与差额），本位币合计借贷相等（`LEDGER_UNBALANCED`，附差额）。
  数据库的提交时触发器同样检查每个外币（SQLSTATE `JZ002`）。
- 其他拒绝：未知或等于本位币的币种（`LEDGER_CURRENCY_INVALID`；币种写成本位币时按本位币分录处理）、缺交易金额或汇率（`REQUIRED`）、
  汇率非正或超过 10 位小数（`LEDGER_RATE_INVALID`）。
- 冲正照原样复制外币与汇率：原交易与冲正在每个币种上合计为零。汇率的来源（汇率表、取价）属于应用（如 finance）。

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
- 每个消费者的一轮在其第一次失败处结束（失败的事件等待退避，其余的由下一轮取出），一个持续失败的消费者不拖住其他消费者。
- 失败重试：每次失败在独立事务中追加 `sys_outbox_attempt`；第 n 次失败后等待 `initial-backoff × 2^(n-1)`（默认 5 s 起，上限 `max-backoff` 1 h）；
  `max-attempts`（默认 10）次后不再重试，记 error 日志，由运维处理。
- 不保证顺序；新增的订阅会收到该类型的历史事件（按 `event_seq`）。
- 启动检查（类别 `EVENT`）：消费者名唯一；订阅的流程是已注册的 Bean；流程应为 `internal()`（D41，06 §8）：`entryOnly()` / `subProcessOnly()` 为错误，普通流程为警告，
  除非订阅声明 `manualToo()`（该流程也供人经流程接口运行）。

### 2.4 Webhook：把事件送给其他系统【D33】

- **订阅是配置**（`jabiz.webhooks.subscriptions[i]`：`name`、`event-type`、`url`、`secret`），不是数据。签名密钥（≥ 32 字符）只来自环境变量，
  在配置中写占位符（`secret: ${ERP_WEBHOOK_SECRET}`；Spring 的列表只从一个配置源绑定，不能只用环境变量补其中一项），
  不写入数据库、日志与 `toString()`；消息与日志中的地址只显示协议、主机与端口（接收方的路径常含令牌）。
- 每个订阅是一个独立的消费者 `jabiz.webhook.<name>`（2.3 的全部规则照用）：对该类型的每个事件执行内部流程 `WEBHOOK_DELIVER`
  （输入只有订阅名与事件 ID，权限 `webhook.deliver`），其步骤 `SendWebhook`：
  - 只在平台的投递（系统身份）中执行，其他调用者（包括持有全部权限的管理员）得到 403；事件从 `sys_outbox_event` 读取，类型须是订阅的类型，
    因此没有人能让平台签发别的内容；
  - 以 JDK `HttpClient` 异步 POST（不占线程），正文 `{"eventId","eventType","createdTime","payload"}`，载荷即 Outbox 中存的 JSON 原样（发布时已去掉秘密）；
  - 请求头：`X-Jabiz-Event-Id`、`X-Jabiz-Event-Type`、`X-Jabiz-Timestamp`（秒）、`X-Jabiz-Signature: v1=<hex HMAC-SHA256(secret, 时间戳 + "." + 正文)>`
    （core `WebhookSignature`；接收方以同样方式计算、常量时间比较，并拒绝过旧的时间戳以防重放）；
  - 在 `jabiz.webhooks.timeout`（默认 5 s，含读完响应）内的 2xx 即送达（消费标记随流程提交）；其他状态、超时、被拒的地址都使流程失败，
    由 Outbox 记录尝试并按退避重试；不跟随重定向。
- **至少一次**：接收方按 `X-Jabiz-Event-Id` 去重（送达之后、提交之前崩溃会再送一次）。各订阅互不影响：一个接收方失败不使另一个重复收到，
  也只结束它自己的这一轮（2.3）。
- **目标地址白名单**：只发往 `jabiz.webhooks.allowed-hosts` 中的主机（精确匹配，缺省为空即不发任何地方）；必须 https，http 只允许本机
  （localhost、127.0.0.1、::1）；地址不得带用户信息（规则在 `web/OutboundAddresses`，与外部服务共用，06 §2.3）。启动检查（类别 `WEBHOOK`）一次报告名字、事件类型、密钥、地址的全部问题，发送时再检查一次地址。
- **载荷中的内容由发布者负责**：`f.masked(...)` 的字段（税号、账号）不会被自动遮蔽，有 webhook 订阅的事件不要放入这类值。
- 新加的订阅会收到该类型的全部历史事件（2.3）；只想要今后的事件时，在订阅之前不发布该类型，或接收方按 `createdTime` 丢弃。
- 观测：`jabiz.webhook.delivery`（标签 `subscription`，即订阅名）。

### 2.5 入站 Webhook：接收其他系统的回调【D37】

- **提供方是配置**（`jabiz.inbound-webhooks.providers.<名>`，名字 `[a-z][a-z0-9-]{0,39}`）：`scheme`（`stripe` 或 `hmac-sha256`）、`secrets`
  （每个至少 32 字符，以占位符取自环境变量；可同时配置新旧两个以换密钥）、`event-types`（接受的外部事件类型白名单）、`tolerance`（缺省 5 分钟，上限 1 小时）、
  `max-body-size`（缺省且最大 1 MB，只能调低）、`rate-limit.per-minute`（缺省每地址每分钟 120）/ `max-clients`。缺省没有提供方，即所有入站请求 404。
  启动检查（类别 `INBOUND`）一次报告名字、方案、密钥、白名单（及拼出的事件类型是否合法、不超过 100 字符）、容差、大小与限流的全部问题；有问题的提供方即使不阻止启动也不提供服务。
  `toString` 只显示密钥个数。
- **签名方案**在 core `com.jabiz.inbound`（同步纯函数，`InboundSignatureScheme.verify(请求头, 原样正文, 密钥列表, 时刻, 容差)`）：
  - `stripe`：`Stripe-Signature: t=<秒>,v1=<hex>[,v1=…]`，`v1` = HMAC-SHA256(密钥, `t + "." + 正文`)；任一 `v1` 与任一密钥匹配即通过，`v0` 与未知方案忽略；
    `t` 缺失、重复、不是数字，头重复或多于 16 项即拒绝。
  - `hmac-sha256`：即 D33 的出站格式（`X-Jabiz-Timestamp`、`X-Jabiz-Signature: v1=…`，core `WebhookSignature.matchesAny`），jabiz 实例之间可以互连。
  - 一律常量时间比较；先验签再看时间，时间只对验签通过的请求有意义；`|Clock − t| ≤ tolerance`，同时拒绝过旧与过于靠后的时间戳（时间来自注入的 `Clock`）。
- **接收** `POST /api/inbound/{提供方}`（匿名，签名即凭证；不看 `Authorization` 头，带过期令牌也不会 401）：
  1. `InboundWebhookWebFilter`（安全过滤链之前）：路径不是一段已提供服务的提供方 → 404（任何方法）；非 `POST` → 405 `Allow: POST`；
     按客户端地址（`ClientAddresses`，10 §15.5）的进程内令牌桶，每个提供方一个，超限 → 429 `RATE_LIMITED` + `Retry-After`；不受 `jabiz.public.enabled` 影响。
     **部署注意**：应用在反向代理或负载均衡之后、却没有把它列入 `jabiz.security.trusted-proxies` 时，所有请求的客户端地址都是代理的地址，
     该提供方的所有发送方共用一个桶（一个发送方的突发会使其他发送方也得到 429）；在代理之后部署时必须配置可信代理（同 15 §5 的公开接口）。
  2. 控制器读原始字节（不经任何解码器）：声明的 `Content-Length` 超限即拒绝、不读正文；分块传输在超过上限时中止读取。
  3. `InboundReceiver`：验签 → 正文必须是严格的 UTF-8 且是一个 JSON 对象（不允许重复键与尾随内容，数值的十进制指数绝对值不超过 1000，
     超过即拒绝，避免消费者对 `1e999999999` 之类的精确小数做运算时耗尽资源）→ 取事件号与类型（只取已签名的正文）：
     `stripe` 取顶层 `id`、`type` 与 `livemode`；`hmac-sha256` 取顶层 `eventId`、`eventType`，其请求头 `X-Jabiz-Event-Id` / `X-Jabiz-Event-Type` 不在签名范围内，
     若出现必须与正文一致。事件号为 1–255 个可见 ASCII 字符。
  4. 类型不在白名单 → 200，不存储（`result=ignored`）；同一提供方的事件号已存在 → 200（`duplicate`）。
  5. 否则以系统身份执行内部流程 `INBOUND_RECEIVE`（权限 `inbound.receive`，不授予任何人；其步骤 `RecordInboundEvent` 只在系统身份下执行，管理员经流程接口调用得到 403）：
     在一个事务中直接插入只追加的 `sys_inbound_event`（元数据与 `body_sha256`）与 `sys_inbound_event_body`（原样正文 `text`，单独一张表，
     列表、导出与到期报告读事件时都不会带上每行至多 1 MB 的正文；都不经变更集、不进审计，同 `MailAttempt`）并经 Outbox 发布
     `jabiz.inbound.<提供方>.<外部类型>`；提交后答 200 `{"received":true}`。并发的同一事件在唯一键 `(provider, external_event_id)` 上等待，之后失败、整体回滚，答 200。
  - 签名错、过期或过于靠后、缺头、超过大小、不是 UTF-8 或 JSON、缺少事件号或类型：一律 400，同一个 problem `INBOUND_REJECTED`（响应正文相同，`Cache-Control: no-store`）；
    原因只进日志（info，只有提供方与原因）与观测标签 `reason`，不存储，不写 `op_process`。
  - 流程输入的正文组件 `rawBody` 标 `@Sensitive`：`op_process.input_summary` 中为 `***`；正文不进日志、观测与 Outbox。
- **事件载荷只带引用**：`{inboundEventId, provider, externalEventId, eventType, receivedTime}`（`InboundEvents.ref(event)`）。正文不复制进 `sys_outbox_event`
  （那里不受 `inbound.read` 保护，还可能被出站订阅转发）。
- **处理**是应用的普通事件订阅（2.3 的全部规则：系统身份、只处理一次、失败退避重试；请求中不执行业务流程）：
  ```java
  EventSubscription.of("shop.payment-received", InboundEvents.type("pay", "payment_intent.succeeded"), PROCESS,
      event -> new Input(InboundEvents.ref(event).inboundEventId()));
  // 流程声明 internal()：只由投递以系统身份运行，其他执行者（含持有全部权限的管理员）在启动时即 403 PROCESS_INTERNAL（06 §8，D41）：
  .step("Load the payment", LoadInboundEvent.of("pay", "payment_intent.succeeded",
      ctx -> ctx.get(INPUT, Input.class).inboundEventId(), "payment"))
  ```
  平台步骤 `LoadInboundEvent` 必须声明它处理的提供方与事件类型：事件属于其他提供方或类型时步骤失败（为一个发送方写的流程不会处理另一个发送方的事件）。
  它把事件读成 `ReceivedEvent`（正文解析为 `Map` / `List`，数值一律 `BigDecimal`，指数超限即失败；`at("data.object.id")` 取路径上的值；`toString` 不含正文），
  只允许系统身份或持有 `inbound.read` 的执行者（否则 403，未知编号 404）。
  **签名只说明是谁发的，不说明内容可信**：消费流程要逐项核对（格式与长度、正式还是测试模式、币种、金额与单据），
  业务上不成立的事件（找不到单据、金额不符、币种不对）登记写明原因的待办而不是让流程失败，否则消费者会无谓地重试到上限。
- 启动检查（类别 `INBOUND`）还比较订阅与白名单：订阅了已配置提供方的 `jabiz.inbound.<提供方>.<类型>`、但该提供方未列出此类型 → 错误
  （这类事件会被答 200 并丢弃）；列出的类型没有任何订阅 → 警告。未在本环境配置的提供方不比较。
- **读取与保留**：平台实体 `InboundEvent`（只读数据视图 `urn:jabiz:dataset:platform:InboundEvent`，权限 `inbound.read`）列表与详情都不含正文，只有 `bodySha256`；
  正文（`sys_inbound_event_body`）以 `GET /api/inbound-events/{id}/body`（`inbound.read`，`Cache-Control: no-store`）逐个读取。应用可声明 `RetentionPolicy.of("InboundEvent")…from("receivedTime")`，
  进入到期报告；表只追加，物理删除不在本阶段。
- 观测：`jabiz.inbound.receive`（标签 `provider`、`result` = `ACCEPTED` / `DUPLICATE` / `IGNORED` / `REJECTED` / `FAILED`（与 `jabiz.outbox.delivery` 同样大写）、
  `reason` = `none`、拒绝原因或意外错误时的 `error`，以及平台观测共有的 `outcome` / `status`）、
  `jabiz.inbound.rate_limited`（无标签）。未知提供方不产生观测；不记录客户端地址。
- 场景回放步骤 `inbound: {provider, body, at?, outcome?}`（07 §3.1）以配置的第一个密钥签名后交给 `InboundReceiver`，之后用 `deliverEvents: true` 运行消费者。
- 示范：`app` 的提供方 `demo-pay`（`stripe`，只在 dev 与测试配置中）→ 消费者 `commerce.payment-received` → `ORDER_RECORD_PAYMENT`（`CommercePayments`：
  只由投递运行；付款编号与订单号的格式、模式（`commerce.payments.accept-test-mode`，示范接受测试模式）、币种 `jpy`、金额（Stripe 以最小货币单位表示，日元即元）、
  订单状态都核对，不成立时待办写明金额、币种与原因代码，指向订单（找到时），来源键 `payment:<提供方>:<外部事件号>`），
  手工演示 `tools/demo/inbound-demo.sh`。

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
- 每项的 `auditRecords` 是该操作留下的审计记录数；字段的前后值见审计记录 `GET /api/audit/records`（21 §1），它也覆盖普通实体。

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
- `GET /api/jobs`（权限 `job.read`）：任务、种类（`SINGLE` / `PER_ITEM`）、cron、时区、流程、下一次计划时刻、最近 10 次执行
  （逐项任务的执行带各结果的项数）；逐项任务另有"连续失败中的项"（项键、连续次数、最近错误（至多 200 字符）、最近失败时刻、下次可重试时刻，至多 50 条）。
- 启动检查（类别 `JOB`）：名称唯一；cron 合法；流程是已注册的 Bean；流程的声明同事件消费者（`internal()`，或普通流程加 `JobDefinition…manualToo()`，D41）；
  逐项任务另见 §4.1（每项流程只能是 `internal()`，没有 `manualToo()`）。
  平台的 `INTEGRITY_SEAL` / `INTEGRITY_VERIFY`、`FILE_PURGE_ORPHANS` 也可手工运行，任务声明 `manualToo()`。
- 场景回放：步骤 `runJob: {job, at, outcome, items}`（`at` 缺省为当前时钟，`outcome` 缺省 `SUCCEEDED`；`items` 见 §4.1）与 `deliverEvents: true`（07 §3.1）。
  场景回放缺省 `jabiz.jobs.lock-at-least-for=0s`，同一任务在一个场景中连续运行不会因自己的锁而 `LOCKED`。

### 4.1 逐项任务（16k，决策 D42）

一批多项的工作（到期自动取消、自动提交、上下架）**不写成根流程 + `CallProcess.forEach`**：子流程与根流程同一事务（06 §5），一项失败整批回滚，
锁持有到整批提交，与在线请求按不同顺序取锁即可能死锁。改用逐项任务：

```java
JobDefinition.forEach("commerce.unpaid-order-expire", "0 10 3 * * *", ZoneId.of("Asia/Tokyo"),
        JobItems.template("commerce.job.unpaid-orders",                 // 选取模板（须有 list.defaultSort 与 list.key）
            scheduledTime -> Map.of("cutoff", scheduledTime.minus(ttl)), // 模板参数（同步、无 I/O）
            "orderId"),                                                 // 项键所在的结果列
        ORDER_EXPIRE,                                                   // 每项流程（internal()）
        (scheduledTime, row) -> new ExpireInput(Rows.uuid(row.get("orderId"))))
    .maxItems(500)                        // 每次至多处理的项数，缺省 500，上限 10 000
    .parallelism(1)                       // 实例内同时执行的项数，缺省 1，上限 8
    .itemLockWait(Duration.ofSeconds(2))  // 每项中 HoldLock 的最长等待，缺省 2 s，不超过 jabiz.process.lock-timeout
    .itemRetry(Duration.ofMinutes(1), Duration.ofHours(1))  // 连续失败的退避：起点、上限
    .maxRunTime(Duration.ofMinutes(5))    // 之后不再开始新项，缺省 lockAtMostFor 的一半，必须小于 lockAtMostFor
    .lockAtMostFor(Duration.ofMinutes(10));
```

- **选取**：以系统身份在只读短事务中运行 SQL 模板（不产生操作记录；遮蔽字段一律遮蔽，选取不留显示记录），按模板的 `defaultSort` + `key` 排序，
  `LIMIT` = `maxItems` + 退避中的项数（至多 `jabiz.jobs.max-in-backoff`，缺省 1 000；只读最近"最长退避"时段内的失败行）+ 1；
  再按**每个选中项自己的**失败记录（不受上述上限影响）滤掉退避中的项，取前 `maxItems` 项；还有更多（或选取已满）记为 `items_more`。
  退避中的项超过上限时，后面的项可能要等到它们退避期满才被选中（不会被提前重试）。选取失败 → 运行 `FAILED`，不处理任何项。
  项键为空、不符合 `[A-Za-z0-9._:-]{1,36}` 或同一次选取中重复 → 该项记为 `FAILED`（`JOB_ITEM_KEY_INVALID`，error 日志；没有可记的键，不写失败行），其余照常；
  全部项都无效时运行为 `PARTIAL`。**项键必须是代理键**（实体主键、UUID 之类不变、不含个人信息的标识）：它进入幂等键、失败表、日志与 `GET /api/jobs`，
  不能用邮箱、姓名、单据号等业务值；选取以遮蔽方式读取遮蔽字段，项键列来自遮蔽字段即启动检查错误，模板中出现遮蔽字段为告警。
  业务参数由每项流程自己读（`LoadParams`）。
- **逐项执行**：每项以系统身份作为**根流程**、各自一个事务与 `op_process`；幂等键 `job:<名称>:<计划时刻 epoch 毫秒>:<项键>`（至多 119 字符），
  请求标识 `job-<名称>-<毫秒>-<项键>`；执行选项 `lockWait = itemLockWait`：项的整个事务（行锁、平台的锁、幂等键）与 `HoldLock` 都以它为等待上限（06 §4.1）。并发至多 `parallelism`（每项占一个连接；超过连接池一半时启动检查告警）。
- **每项的结果**（只影响该项）：

  | 结果 | 条件 | 记录 |
  |---|---|---|
  | `SUCCEEDED` | 提交 | 计数；该项此前有未恢复的失败时追加一行 `RECOVERED` |
  | `REPLAYED` | 同一计划时刻已处理过（幂等键重放） | 计数 |
  | `BUSY` | 只有"忙"的冲突（`ConcurrentUpdateException.busy()`）：锁等待超时（`55P03`）与死锁（`40P01`）译为 `LockNotAvailableException`，版本过期为 `ConcurrentUpdateException.stale`；以及每项流程声明了准入（06 §4.2）而未被准入（`ProcessBusyException`：队满，或在队列中等过 min(族的 `max-wait`, `itemLockWait`)，D45 第 4 条） | 计数，不算失败、不退避；info 日志，同一项连续 5 次起 warn（实例内计数）；仍然到期的项由下次运行重新选取 |
  | `FAILED` | 其他异常（422、404、500 …），包括不是"忙"的冲突（实体已存在、撤销与变基冲突等）；事务已回滚，不留任何效果 | 追加 `sys_job_item_failure`（`FAILED`，`attempt` = 连续次数）；warn 日志，连续第 5 次起 error |
  | `DEFERRED` | 退避中（选取后滤掉），或超过 `maxRunTime`、停机后未开始；停机开始后才出错的项 | 计数 |

- **运行结果**：`sys_job_run` 一行，带 `items_selected`（各结果之和）、`items_succeeded` / `replayed` / `busy` / `failed` / `deferred` 与 `items_more`；
  没有 `FAILED` 项 → `SUCCEEDED`，有 → `PARTIAL`，选取失败 → `FAILED`（`REPLAYED` 只属于单流程任务）。`JobRunner.execute(job, 时刻)` 返回结果与计数，`run` 只返回结果。
- **失败与退避**（逐项、持久化，同 Outbox）：不在一次运行中重试。连续失败次数 = 该项最近一次 `RECOVERED` 之后的 `FAILED` 行数（即最近一行的 `attempt`，按 `entry_no` 排序）；
  第 1 次失败后下一次运行即可重试，第 n 次（n ≥ 2）后等 `min(起点 × 2^(n−2), 上限)`。从不放弃：封顶后按上限重试并写 error 日志。
  退避中的项另外放宽选取上限，"毒项"不会占满 `maxItems` 使后面的项饿死。
- **集群**：一次运行（选取 + 全部项）在该任务的 ShedLock 下，同一时刻只有一个实例运行；项不在实例间分发。`maxRunTime` < `lockAtMostFor`，到时不再开始新项，
  进行中的项照常结束。即使锁过期、另一实例开始同一计划时刻，幂等键保证同一项在同一计划时刻至多执行一次（并发时一方等待唯一约束后重放）。
  运行超过 cron 间隔时下一时刻 `LOCKED`；逐项任务按状态选取，下一次运行自然接上。停机（`JobScheduler` 的 `@PreDestroy`）时不再开始新项，
  等待进行中的项至多 `jabiz.jobs.scheduler.shutdown-grace`（缺省 30 秒）后中断其余；停机开始后出错的项记为 `DEFERRED`，不写失败行。
  `maxRunTime` 与锁时间一样是真实时间（基础设施时间）；退避与失败记录的时刻来自注入的 `Clock`。
- **项的语义由应用保证**：平台只保证"同一计划时刻同一项至多一次"。选取与执行之间状态可能已变，跨计划时刻也可能再次选中已处理的项：
  每项流程须**先取锁、再读取、已不到期即什么也不做**（示范 `ORDER_EXPIRE`）。同一次运行中两项争同一把锁时（`parallelism > 1`），后者可能 `BUSY`，下次运行再处理。
  每项流程可以声明准入（与在线请求同一族，06 §4.2）：它是根流程，照样排队；被拒即 `BUSY`。它调用的子流程从不排队。
  事件消费者的流程同样排队，被拒是一次失败的投递（`sys_outbox_attempt`），按投递的退避重试（§2）。单流程任务被拒即该次运行 `FAILED`（这类任务通常不声明准入）。
- **D41**：每项流程必须是 `internal()`（启动检查 `JOB`：`entryOnly()`、`subProcessOnly()` 与普通流程一律报错，逐项任务没有 `manualToo()`）：
  其输入来自系统的选取，人不应能直接调用；执行时 `ProcessExecutor.requireStartable` 只允许系统身份启动它（403 `PROCESS_INTERNAL`）。
  单流程任务按 §4 的规则（`internal()`，或普通流程加 `manualToo()`）。
- **启动检查 `JOB`**（一次报全）：选取模板存在、不是公开模板、声明 `list.defaultSort` 与 `list.key`、必填参数都由参数函数给出且没有未声明的参数；
  项键列是模板的结果列；每项流程已注册且 `internal()`；`itemLockWait` 不超过 `jabiz.process.lock-timeout`；`parallelism` 超过 R2DBC 连接池（`spring.r2dbc.pool.max-size`）一半时告警。
  `maxItems`、`parallelism`、`maxRunTime < lockAtMostFor` 等范围在声明时即检查。
- **"连续失败中的项"**（`GET /api/jobs`）只列最近 7 天内失败过的项（更早的多半已不再被选中）。
- **表**：`sys_job_item_failure`（只追加，平台迁移 `V36`，索引 `(job_name, item_key, entry_no DESC)` 与 `(job_name, recorded_time)`）：`failure_id`、`entry_no`（自增，定序）、`job_name`、`item_key`、`kind`（`FAILED` / `RECOVERED`）、`run_id`、
  `scheduled_time`、`attempt`、`error`（至多 2 000 字符）、`process_seq_id`（`RECOVERED` 行的成功操作）、`recorded_time`。`sys_job_run` 加列不执行 UPDATE，封存按各块记下的列清单校验（21 §2）。
- **观测**：每项 `jabiz.job.item`（标签只有 `job` 与 `result`），每项流程的 `jabiz.process` 嵌在其下；运行 `jabiz.job.run` 的结果增加 `PARTIAL`；选取记为 `jabiz.query.template`（13）。
- **场景**：`runJob: {job, at, outcome, items: {succeeded, replayed, busy, failed, deferred}}`，`outcome` 缺省 `SUCCEEDED`（可写 `PARTIAL`），`items` 只比较写出的键。
- **示范**：`commerce.unpaid-order-expire`（每天 03:10 东京时间，模板 `commerce.job.unpaid-orders`：`PLACED`、未付款、下单早于 `计划时刻 − app.commerce.unpaid-order-ttl`（缺省 14 天））
  的每项流程 `ORDER_EXPIRE {orderId}`：以 `internal()` 只由系统启动（D41：其他执行者 403 `PROCESS_INTERNAL`）、取订单锁 `commerce.order:<id>`、重新读取，仍未付款且已超过期限才以子流程 `ORDER_CANCEL` 取消。`ORDER_RECORD_PAYMENT` 找到订单后取同一把锁并重新读取，
  "刚付款"与"过期取消"不交错。场景 `scenarios/commerce/unpaid_order_expiry.yml`。

## 4a. 异步外部作业【D38 第 3–5 条，阶段 16e-2】

耗时数十秒的外部调用（生成文案、图片）不放在流程事务中：流程登记作业后立即返回，平台在任何事务之外执行，结果经完成流程写入。
不引入队列系统：领取、租约、重试、取消都记在只追加的表中，多实例与重启都安全。

- **作业类型**（core `ExternalJobType`，Bean）：
  ```java
  ExternalJobType.define("commerce.product-copy", ProductCopyInput.class, ProductCopyResult.class, t -> t
      .handler(ProductCopyHandler.class).services("demo-ai").timeout(ofSeconds(90))
      .retry(3, ofSeconds(10), ofMinutes(10)).maxConcurrency(2).oneOpenPerReference()
      .onCompleted("PRODUCT_COPY_APPLY").onFailed("PRODUCT_COPY_FAILED"))
  ```
  名字 `[a-z][a-z0-9._-]{0,99}`；输入与结果是 record，都不含 `@Sensitive` 组件（作业保存它们）；超时至多 1 小时（缺省 1 分钟），
  且须长于所用服务的请求超时；
  `retry(次数, 初始退避, 最长退避)` 的次数含第一次（缺省 3 次、10 s、10 min），第 n 次失败后等待 `初始 × 2^(n−1)`，不超过最长退避；
  `maxConcurrency` 为全部实例合计（缺省 4，Q11）。
  **开始的限制**：`maxOpenPerRequester(n)`（缺省 10）——同一请求人此类型未结束（无最终结果、未取消）的作业至多 n 个；`oneOpenPerReference()`——
  同一引用至多一个未结束的作业（此时开始必须给引用）。超过即 422 `EXTERNAL_JOB_LIMIT`（三语，参数 `jobType`、`limit`、`scope`）。
  计数在类型与请求人、类型与引用各自的 advisory 锁下进行（持有到流程事务结束；先取引用的锁再取请求人的锁），并发的开始也不会同时通过。
- **处理器**（core `ExternalJobHandler<I, R>`，同步，Bean）：`ExternalJobResult<R> run(I input, ExternalJobContext ctx)`。在执行器的虚拟线程上运行，
  不持有任何事务或连接，不访问数据库（共用 ArchUnit 规则 `handlersStayOutOfTheDatabase`）。`ctx`：`jobId`、`attempt`、`idempotencyKey()`
  （`jabiz-job-<作业号>`，各次尝试相同）、`deadline`、`http(服务)` 与 `secret(服务, 名)`（只限类型声明的服务）、`recordUsage(名, 数量)`（失败的尝试也保留）。
  返回 `ExternalJobResult.of(值).usage("promptTokens", 812)`：用量名 `[a-zA-Z][A-Za-z0-9._-]{0,63}`，数量为非负整数，同名相加。
  失败：`ExternalJobException.permanent(码, 消息)` 不重试，`retryable(码, 消息)` 与其他异常、超时按可重试处理；平台自己的码：`TIMEOUT`、`LEASE_EXPIRED`、
  `EXTERNAL_CALL_FAILED`（外部调用失败，被拒的调用不重试）、`HANDLER_ERROR`（其他异常，只记异常的类名，不记消息：消息可能重复请求或密钥）、
  `RESULT_TOO_LARGE`（结果 JSON 超过 256 KiB，不重试，Q16）、`BAD_RESULT`（不是声明的结果类型，或其 JSON 读不回该类型，不重试）、
  `NO_RESULT`（处理器返回 null，不重试）、`BAD_INPUT`（保存的输入读不成输入类型，或找不到处理器，不重试）、`ATTEMPTS_EXHAUSTED`、`TYPE_REMOVED`（见下）。
- **登记**：平台步骤 `StartExternalJob.of(类型, ctx -> 输入).reference(ctx -> …).outputKey("jobId")`（可加 `.when(…)`；在 `com.jabiz.runtime.process.steps`）
  在流程事务内插入 `sys_external_job`（请求人为请求的操作人、`process_seq_id`、引用至多 200 字），作业号写入上下文。回滚或试运行即没有作业；
  带 `Idempotency-Key` 的重放返回同一作业号（重放的是输出）。输入中 `@Sensitive` 组件的非空值（按声明反射查找，含嵌套 record、集合、Map、数组与
  `Object` 类型的值）→ 422 `EXTERNAL_JOB_INPUT_SECRET`（参数为字段路径，不含值）；只看声明、不看名字，`maxTokens` 之类不受影响。
  遮蔽字段（`f.masked`）的值不按名字查找：输入是自己的 record，组件不是实体字段，按名字匹配会误拒；输入不经任何接口返回，个人信息的保留见 21 §3.1。
  JSON 超过 64 KiB → 422 `EXTERNAL_JOB_INPUT_TOO_LARGE`。
  启动检查（`CheckedStep`）：引用的类型已声明。
- **执行器** `ExternalJobExecutor`（`jabiz.external-jobs.executor.enabled`（测试关闭）、`poll-interval` 1 s、每实例 `max-in-flight` 8、`instance-id`
  缺省为主机名加随机串、`shutdown-grace` 30 s、`watermark-refresh` 1 min；测试与场景调用 `runPending()`，领取后执行并等全部记录完）：
  1. **领取**（每个类型一个短事务，**每次轮询对每个类型都做**，实例已满也做前两项）：`pg_advisory_xact_lock(hashtextextended('jabiz.external-job:'||类型))`，
     取得锁之后才读时钟；把租约已到期而没有结果的领取补记为尝试 `LEASE_EXPIRED`（可重试；已取消的作业随即为 `CANCELLED`，次数用尽的为 `FAILED`）；
     没有未记录的领取而不能再尝试的作业给出最终结果——已取消的为 `CANCELLED`，尝试次数已达类型现在的上限（类型改小了次数）的为 `FAILED` `ATTEMPTS_EXHAUSTED`；
     实例有空位时，统计有效领取数（有领取、无尝试、租约未到）得到剩余名额，
     选出到期的作业——无最终结果、未取消、无未记录的领取、尝试次数未满、首次或已过退避——`FOR UPDATE OF j SKIP LOCKED LIMIT 名额`。
     退避由 Java 按 `ExternalJobType.backoffAfter` 算成每个失败次数的等待（毫秒数组）交给 SQL，属性测试覆盖的即是生产所用的计算；时间取 `Clock`。
     为每个插入 `sys_external_job_claim`（尝试号 = 已有尝试数 + 1，租约 = 现在 + 超时 + 30 s），`ON CONFLICT DO NOTHING`，
     并且插入语句自己再看一次取消（与取消之间的竞争）。真正的保证是主键 (作业, 尝试号)：竞争失败者什么也没插入，跳过。
     **轮流**：每次轮询从上次取得名额的类型的下一个开始，一个忙碌的类型占不满实例的每次轮询。
     **水位线**：每个类型记住最小的无最终结果的 `job_seq`（没有则为最大加一），上面的查询只看水位线以上的作业；每隔 `watermark-refresh`
     一次轮询是**全量扫描**（不用水位线，并刷新水位线），以找到晚提交而编号较小的作业（它最多晚一个刷新间隔被领取），
     同时把已不再声明的类型的未结束作业记为 `FAILED` `TYPE_REMOVED`（其完成不调用任何流程）。`sys_external_job_claim (lease_until)` 有索引。
  2. **执行**：处理器在 `boundedElastic`（虚拟线程）上、作业的 `ExternalCallScope` 中运行，`Mono.timeout(超时)`（中断线程，尽力而为）；
     此时没有任何事务或连接被占用。
  3. **记录**（独立事务，先 `FOR UPDATE` 锁作业行，与取消串行；数据库失败时退避重试 3 次）：插入尝试（`SUCCEEDED` / `FAILED` / `TIMED_OUT`、是否可重试、码、说明（至多 2000 字）、耗时）
     ——`ON CONFLICT DO NOTHING`：租约过期后迟到的实例发现尝试已被记为 `LEASE_EXPIRED`，丢弃自己的结果（`DISCARDED`）；再插入用量。
     已取消 → 最终结果 `CANCELLED`（丢弃结果）；成功 → `SUCCEEDED` 与结果 JSON；不可重试或次数用尽 → `FAILED` 与最后的码和说明；否则等待重试。
     最终结果 `sys_external_job_outcome`（主键 `job_id`，`ON CONFLICT DO NOTHING`：只有一个）与 Outbox 事件
     `jabiz.external-job.finished`（`{jobId, jobType, status}`，不含结果）同一事务。用量计数器在提交之后才增加。
  4. **停止**：执行器是 `SmartLifecycle`（在数据库连接关闭之前停止）：不再领取，等本实例正在执行的尝试记录完，至多 `shutdown-grace`；
     没等到的在租约到期后由其他实例重新领取。
  - 锁的顺序：领取先取类型的 advisory 锁再取作业行；记录与取消只取作业行。持有作业行的事务从不等待 advisory 锁，所以不会死锁。
- **完成**：平台的消费者 `jabiz.external-jobs` 接收 `jabiz.external-job.finished`，以系统身份运行内部流程 `EXTERNAL_JOB_COMPLETE`。
  其步骤 `RunExternalJobCallback` 读取作业、结果与用量合计，以 `executeChild` 调用类型声明的流程：成功为 `ExternalJobCompletion(jobId, jobType,
  requestedBy, reference, result（按声明的结果类型反序列化，`result(类型)` 取出）, usage, attempts, finishedTime)`，失败为
  `ExternalJobFailure(…, errorCode, errorMessage)`；`CANCELLED` 不调用（Q13）。保存的结果读不回声明的类型（类型改了）时，
  改为以 `RESULT_UNREADABLE` 调用失败流程，而不是让事件反复重试；类型已不再声明的作业不调用任何流程。
  两个流程的操作记录（`op_process.input_summary`）不含结果与处理器的消息：结果只记字节数 `resultBytes`，消息只记长度 `errorMessageLength`
  （`SensitiveDataMasker` 对这两种输入的特例）；`ExternalJobFailure.toString` 同样遮蔽消息。消费记录与子流程同一事务：只调用一次；失败按 Outbox 重试（D14）。
  完成与失败流程必须是 `internal()`（启动检查），并由它们以 `CreateTask` / `SendMail` 通知请求人。
  **在 D41 决定之前**，`internal()` 只是不进目录，持有权限的人仍能经 `/api/processes` 调用：`EXTERNAL_JOB_COMPLETE` 的步骤与示范的
  `PRODUCT_COPY_APPLY` / `PRODUCT_COPY_FAILED` 都在步骤中拒绝系统以外的操作人（403，同 16d 的 `RecordInboundEvent` 与示范消费者），应用的回调流程须照做。
- **取消**：`POST /api/external-jobs/{id}/cancel` 运行 `EXTERNAL_JOB_CANCEL`（`entryOnly()`，只由该入口以调用人身份运行，D41；步骤检查请求人本人或持有 `external-job.cancel` 者）。
  请求人本人或持有 `external-job.cancel` 的人，其他人 404（与不存在相同）——先在不加锁的行上判断，无权的人不会取得或等待行锁；
  然后 `FOR UPDATE` 锁作业行；已有最终结果或已取消 → 422 `EXTERNAL_JOB_FINISHED`。
  插入 `sys_external_job_cancel`；没有正在执行的尝试则同时写最终结果 `CANCELLED`（含事件，不调用流程）；执行中的，执行器记录尝试后写 `CANCELLED` 并丢弃结果。
  回答为取消后的状态。
- **查看**：`GET /api/external-jobs/{id}`（请求人本人或 `external-job.read`，其他人 404，`Cache-Control: no-store`）：作业号、类型、引用、请求人、
  状态（`QUEUED` 从未领取 / `RUNNING` 有效领取中 / `WAITING_RETRY` 等待重试（含租约过期待补记）/ `SUCCEEDED` / `FAILED` / `CANCELLED`
  （已取消而尝试仍在执行的也是，其结果必被丢弃））、尝试次数、请求 / 首次领取 / 结束时间、最近的错误码、用量合计；不含输入与结果（Q14）。
  只读平台实体 `ExternalJob`（不含输入）、`ExternalJobAttempt`、`ExternalJobUsage` 的数据视图（`external-job.read`）供后台查看；
  报表模板 `jabiz.external_job_usage`：按类型、用量名、尝试结束的月份（UTC）汇总数量与尝试数（参数 `from` / `to` / `jobType`）。平台不计费。
- **表**（`db/jabiz/V37__external_jobs.sql`，全部只追加并有主键，D27 封存）：`sys_external_job`（`job_id` 为 `varchar(36)`，与其他平台实体的标识一致）、
  `sys_external_job_claim`、`sys_external_job_attempt`（另有唯一的 `attempt_id` 作实体主键）、`sys_external_job_usage`（另有 `usage_id`）、
  `sys_external_job_cancel`、`sys_external_job_outcome`。`SELECT … FOR UPDATE` 只加行锁，不触发只追加的 UPDATE 触发器。
- **观测**：`jabiz.external.job`（每次尝试，从处理器开始到记录完；标签 `job_type`、`outcome`：`SUCCEEDED` / `WAITING_RETRY` / `FAILED` /
  `CANCELLED` / `DISCARDED` / `error`）；计数器 `jabiz.external.usage`（标签 `job_type`、`metric`，增加记录的数量）。
- **启动检查 `EXTERNAL`**：类型名唯一且合法、处理器恰好一个 Bean、服务已声明且其请求超时短于作业的超时、完成与失败流程存在且为 `internal()` 并分别以
  `ExternalJobCompletion` / `ExternalJobFailure` 为输入、超时 / 重试 / 并发 / 开始限制在范围内、输入与结果是 record、两者都无 `@Sensitive` 组件（含嵌套 record）。
- **场景回放**：`runExternalJobs: true`；`completeExternalJob: {jobType, reference?, result, usage?, status?, errorCode?, errorMessage?}`
  不调用处理器，经同一领取与记录路径写入结果（`ExternalJobExecutor.completeWithoutHandler`，Q17），07 §3.1。
  `completeWithoutHandler` 只在 `jabiz.external-jobs.simulated-results=true`（只有测试配置设置）时可用，否则 `IllegalStateException`。
- **风险与限制**：超时不能强行停止卡住的处理器（中断只是尽力而为，靠请求超时与租约兜底；超时后旧线程若仍在运行，同一类型可能短暂超出并发上限）；
  至少一次——执行完、记录前崩溃，会以同一幂等键再调用一次，不支持幂等键的提供方可能计费两次（用量按尝试记录，可统计）；
  迟到实例被丢弃的尝试不记录用量；每次轮询每个类型一个领取事务（查询限于水位线以上，全量扫描每个刷新间隔一次）；
  晚提交的作业最多晚一个刷新间隔；完成流程持续失败时 Outbox 重试用尽后由运维处理。
  输入与结果可能含个人信息：保留策略与删除见 21 §3.1。
- 示范：`app` 的 `PRODUCT_COPY_REQUEST`（`actsOn Product`，`commerce.product.write`）登记 `commerce.product-copy`；`ProductCopyHandler` 调用
  `POST /v1/generate`（`{text, usage:{promptTokens, completionTokens}}`，不绑定提供方；4xx 为永久失败，429 / 5xx 重试）；
  每个商品同时只有一个未结束的作业（`oneOpenPerReference`）；`PRODUCT_COPY_APPLY` 写 `Product.marketingCopy`（`processOnly`）并给请求人待办，`PRODUCT_COPY_FAILED` 告知失败。测试 `ExternalJobIT`，
  场景 `scenarios/commerce/product_copy.yml`。

## 5. 示范（`app` 的 `FreightBilling`）

- 任务 `logistics.freight-month-close`：每月 1 日 00:05（东京时间）关闭上个月（`FREIGHT_MONTH_CLOSE`）。
- 关闭成功时发布 `logistics.freight-month-closed`（载荷为关闭结果）；消费者 `logistics.freight-revenue` 执行 `FREIGHT_POST_REVENUE`，
  以子流程 `LEDGER_POST` 过账：借 应收账款 `1130` / 贷 运费收入 `4110`，记账时间为该月最后一刻；无运费的月份不过账（`CallProcess.when`）。
- 场景 `scenarios/freight/monthly_close.yml` 覆盖：开立科目、任务关账、投递事件、按时间点查询余额。

## 5a. 示范：订单与库存（阶段 11，`app` 的 `com.jabiz.app.commerce`）

- 时态实体 `Product`、`Warehouse`、`StockLevel`、`SalesOrder`、`SalesOrderLine`；库存与订单的视图 `processOnlyWrites`【D16】。
- 流程 `STOCK_RECEIVE`、`PRODUCT_REPRICE`（预定调价）、`ORDER_PLACE`（按下单时价格计价、预留库存，全部违规一次返回）、`ORDER_CANCEL`（释放预留）、
  `ORDER_SHIP`（出库，可关联物流示例的运单，并以子流程 `LEDGER_POST` 过账：借 应收账款 `1130` / 贷 销售收入 `4120`，记账时间为发货时间）。
- SQL 模板 `commerce.stock_availability`、`commerce.order_summary`；订单发布实体变更事件。
- 测试：`CommerceIT`（生命周期、违规累积、只经流程写入、权限、并发下单不超卖、只追加）、`CommerceProcessesTest`、场景 `scenarios/commerce/order_lifecycle.yml`。

## 6. 测试（阶段 9 验收）

| 验收标准 | 测试 |
|---|---|
| jqwik 属性测试：任意交易序列后每笔交易借贷平衡、全部科目余额之和为零 | core `LedgerProperties`（`@Property`，纯逻辑）；app `LedgerPropertyIT`（同样的 jqwik 生成器、固定种子，经流程写入真实数据库后按原始表与余额模板断言） |
| 两个实例同时运行时，同一任务只执行一次 | `JobIT`：两个"实例"各有连接池、锁提供者与实例 id，同时触发同一计划时刻 → 一个 `SUCCEEDED`、一个 `LOCKED`；锁过期时幂等键仍保证一次 |
| 事务回滚时不产生事件；投递失败会重试，消费端不会重复处理 | `OutboxIT`：流程回滚、数据视图提交失败均无事件；消费者前两次失败后按退避重试成功且只留一次效果；重复投递与 4 路并发投递都只处理一次 |

另有 `LedgerIT`（过账、余额、冲正、只能经流程写入、撤销被拒、数据库兜底、只追加）、`AuditIT`、`JobChecksTest`、场景回放。

逐项任务（16k，§4.1）：

| 验收标准 | 测试 |
|---|---|
| 每项以系统身份、各自一个事务执行；一项失败只回滚该项，运行 `PARTIAL` 并有计数 | `JobItemsIT.oneFailingItemAffectsNoOther`：5 项中第 3 项 422 → 4 项提交、1 行失败、失败项无 `op_process` 与写入 |
| 同一计划时刻重跑、两个实例在锁过期后各跑一次，同一项只产生一次效果 | `rerunsOfAScheduledTimeReplayAndFailingItemsRecover`；`itemsRunInParallelOnceEachAndTwoInstancesRunATimeOnce`（`parallelism = 4`、200 项、两个实例一个 `LOCKED`，锁释放后另一实例全部 `REPLAYED`）；`concurrentInstancesAfterAnExpiredLockRunEachItemOnce`（运行中锁过期，两个实例并行跑同一时刻） |
| 失败按连续次数退避、成功后 `RECOVERED`、持续失败的项不使其他项饿死 | `aFailingItemBacksOffWithoutStarvingTheOthers`（1、2、4 分钟，`maxItems = 2` 时其他项照常）；`retriesAreCappedAndNeverGivenUp` |
| 任务项取不到锁即 `BUSY`、不算失败；请求中超时 409 | `anItemWhoseLockIsHeldIsBusyAndRunsNextTime`；`HoldLockIT.aStepsOwnWaitAndTheExecutionsWaitAreShorterThanTheTimeout`；`HoldLockTimeoutTest` |
| Q5 的锁形状下无死锁、在线请求不等整批 | `itemsDoNotDeadlockWithRequests`；对照 `theOldBatchInOneTransactionDeadlocksWithRequests`（`CallProcess.forEach` 同一事务，数据库报死锁） |
| `maxRunTime` | `afterMaxRunTimeNoItemStarts` |
| 只追加、封存 | `itemRecordsAreAppendOnly`（无 UPDATE / DELETE、触发器拒绝、封存与校验通过） |
| 接口、启动检查、场景 | `JobIT.aPerItemJobIsListedWithItsCountsAndFailingItems`；`JobChecksTest`；`ScenarioParserTest`；场景 `commerce/unpaid_order_expiry.yml` |
