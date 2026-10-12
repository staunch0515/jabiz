# Q6 充值与支付（后端，约 10–12 天，分 Q6a / Q6b）— 实施计划

分支：从 `1.2/quizbuks` 拉出，PR 合回 `1.2/quizbuks`。只改 `.jabiz-app-paths` 中的路径（含 `tools/quizbuks/**`）。
本阶段较大，拆成两个 PR：
- **Q6a**（`1.2/quizbuks-6a-topup`）包括：
  - Stripe 接入的公共部分：外部服务 `stripe`、`StripeApi` 表单客户端、入站提供方 `stripe`、本地桩服务；
  - 充值（Checkout Session 与到账通知）、过期、丢失通知的核实、手续费与原币信息（F-04）、退款与争议的防御处理；
  - 商家与答题人的余额概览和流水模板（U-50 – U-52 中属于奖励的部分）；
  - 每日核对（F-06）；
  - 开发入账的去留。
- **Q6b**（`1.2/quizbuks-6b-payout`）包括：收款账户（Stripe Connect Express，入站提供方 `stripe-connect`）、用户钱包缓存与锁、自动转账（每日逐项任务、大额审核、经作业发出、结果、核实、撤回）、Stripe 打款到银行的记账、流水中的转账部分。
  Q6b 依赖 Q6a。正式开放转账（`qb.transfer.enabled`、正式模式）之前须先取得法务意见（`03-plan.md` 第 4 节）。代码与测试不等法务意见，因为当前只走测试流程。

本阶段只做后端，内容包括：实体、数据视图、流程、外部作业类型、入站提供方与事件订阅、定时任务、分录、SQL 模板、迁移、三语消息、测试，以及本地 Stripe 桩服务。

- **页面延后**：在平台 15a、16c 合入后另做，约 3–4 天。
  - 商家后台：充值（输入金额 → 跳到 Stripe → 返回页轮询状态）、钱包概览与充值记录（M-04）。
  - App：余额概览（U-50）、流水与筛选（U-51、U-52）、开通收款账户与状态（U-53 的替代）。
  - 管理员后台：充值、转账、核对结果的列表（元数据生成），大额转账审核（`/tasks` 审批面板）。
- **不在 Q6**：
  - 商家流水的按发布 / 用户明细与汇总、导出（M-50 – M-52），在 Q9；
  - 三方对账（F-05：与 Stripe 余额交易逐笔核对、银行流水导入），以后另做；
  - 充值收据（F-09，平台单据 `DOCUMENT_ISSUE`），在 Q9；
  - 参考汇率与"约合 X USD"（`QbFxRate`、`QB_FX_FETCH`）【待确认 Q6-19】；
  - 商家申请退款（Q14 缺省不做）；
  - 便利店、银行转账等异步支付方式【待确认 Q6-4】；
  - 跨境转账（Q18：每个部署一个 Stripe 账户，跨境不在范围内）；
  - 正式的"人工入账"（银行汇款充值，四眼）【待确认 Q6-11】；
  - 转账结果的站内信与邮件：Q7 订阅本阶段发布的事件。
- **已确认的约束**（项目早期，用户）：
  - Kudos = JPY，小数位 0；只用 Stripe；当前是测试流程（Stripe 测试模式）；会计年度到 12 月；
  - 业务代码同步；出站调用只在 `BlockingStep` 或外部作业中经声明的 `ExternalService` 进行；
  - 入站只经 `/api/inbound/{提供方}`（验签、存档、去重，以事件交给流程）；
  - 账本只经 `LEDGER_POST` / `LEDGER_REVERSE` 写入；
  - 仓库中没有自己的密钥（只用环境变量）。

## 已核实的平台事实（`origin/1.2/quizbuks`，已含 16d、16e-1、16e-2、16k、16l）

- **入站 Webhook**（D37、11 §2.5，`runtime/inbound`）：
  - 提供方是配置 `jabiz.inbound-webhooks.providers.<名>`：`scheme: stripe`；`secrets` 每个至少 32 字符，以占位符取自环境变量，可同时配置新旧两个以便换密钥；另有 `event-types` 白名单、`tolerance`（缺省 5 分钟，上限 1 小时）、`max-body-size`（缺省且最大 1 MB）、`rate-limit`。
  - 启动检查 `INBOUND`：订阅了白名单外的类型为错误；列出却无人订阅为警告。
  - `stripe` 方案：签名内容为 `t + "." + 原样正文`，任一 `v1` 与任一密钥匹配即通过；时间容差同时拒绝过旧与过于靠后的时间戳；常量时间比较。
  - 事件号与类型只取已签名正文的顶层 `id`、`type`，并记下 `livemode`。
  - 去重靠 `UNIQUE (provider, external_event_id)`：重复与白名单外的类型都答 200 且不处理；验签失败等一律 400 `INBOUND_REJECTED`，不存储。
  - Outbox 载荷只带引用 `{inboundEventId, provider, externalEventId, eventType, receivedTime}`；原样正文在 `sys_inbound_event_body`，读取需要 `inbound.read`。
  - 消费流程以 `LoadInboundEvent.of(提供方, 类型, …)` 读成 `ReceivedEvent`（`livemode`、`signedTime`、正文中的数值一律 `BigDecimal`，`at("data.object.id")` 取值）。**一个步骤只认一个类型**（平台缺口 18）。
  - 签名不等于内容可信：平台要求消费流程逐项核对，业务上不成立的事件开待办而不是让流程失败（示范 `app` 的 `CommercePayments`）。
  - 限流按客户端地址分桶。在代理之后必须配置 `jabiz.security.trusted-proxies`，否则所有发送方共用一个桶。
  - 场景步骤 `inbound: {provider, body, at?, outcome?}` 以第一个密钥签名。
- **外部服务**（D38、06 §2.3，`core/external`）：
  - 以 `ExternalService.define(名, …)` Bean 声明：基础地址、主机白名单、密钥的环境变量、超时（请求至多 2 分钟）、幂等键请求头、响应上限。
  - **只有平台的 JDK HTTP**（`ExternalServices#http`）：官方 SDK 自带的出站不受主机白名单约束，本阶段不开放。所以不用 Stripe Java SDK，应用自己做表单编码与 JSON 解析（`02-design.md` §8 须改）。
  - 不自动重试、不跟随重定向；失败为 502 `EXTERNAL_CALL_FAILED`，消息不含路径与正文。
  - `jabiz.external-services.<名>.base-url | allowed-hosts` 可按环境覆盖，测试与开发指向桩服务。
  - 密钥启动时经 `Environment` 读取，缺失时非 dev 即启动检查 `EXTERNAL` 报错，dev 只是告警。开工首日核实测试配置的属性能否充当这些环境变量。
  - ArchUnit 规则 `noDirectNetworkAccess` / `noPlatformInternals` / `handlersStayOutOfTheDatabase`。
- **提交后步骤的重试不持久化**（06 §4）：只在进程内重试，用尽只记日志。所以"必须最终发生"的 Stripe 调用不能放在 `AFTER_COMMIT`。
- **异步外部作业**（D38、11 §4a）：
  - 在流程事务内以 `StartExternalJob.of(类型, 输入).reference(…)` 登记；回滚即没有作业。
  - 执行器在任何事务之外运行处理器（同步，不访问数据库）。幂等键 `jabiz-job-<作业号>` 在各次尝试中不变；租约过期后**至少一次**，可能以同一幂等键再调用一次。
  - 按类型声明重试、退避、合计并发；`maxOpenPerRequester`（缺省 10，至多 10 000，**系统身份同样受限**，平台缺口 19）；`oneOpenPerReference()`。
  - 完成与失败流程必须是 `internal()`，以 `ExternalJobCompletion` / `ExternalJobFailure` 为输入，各只调用一次；失败流程带最后的错误码。
  - `ExternalJobException.permanent` 不重试，`retryable` 重试。
  - 场景用 `completeExternalJob`（只在 `jabiz.external-jobs.simulated-results=true` 时可用），测试用 `ExternalJobExecutor.runPending()`。
- **事件**（11 §2.3）：
  - 每个（消费者，事件）在自己的事务中只处理一次，失败按退避重试至多 10 次。
  - **不保证顺序**。
  - 新订阅会收到该类型的历史事件（Q6b 的用户钱包同步据此自动回填）。
- **D41**：事件消费者、定时任务、作业完成与失败流程一律 `internal()`；不是 `internal()` 的流程不能调用 `internal()` 流程。人发起的开发入账因此不能以子流程补发，Q5 已改为发布 `qb.sponsor.settle-pending`。
- **D42 逐项任务**：`JobDefinition.forEach`，选取模板须有 `list.defaultSort` 与 `list.key`，每项一个事务，忙即 `BUSY`，失败按项退避。选取模板的参数函数只拿到计划时刻，读不了业务参数（平台缺口 24）。
- **账本**（11 §1、D24）：
  - 子流程过账不检查调用方权限；至少 2 行、平衡、金额为正。
  - 来源单据经默认视图核对存在（普通实体也可以）。
  - 更正只能冲正：`LEDGER_REVERSE` 照原样复制行、维度、来源，一笔交易至多冲正一次。
  - 维度 `party`（位置 1，用户编号）、`publication`（位置 2）已在 Q5 声明。模板可以 `{{LedgerEntry}}` 读分录，对不可变字段（如 `dimension1`）的条件下推，按用户汇总走索引。
- **审批**（18 §3）：`ApprovalCase` 的准备人可以由以系统身份运行的流程指定。设为收款人后，平台即挡住收款人审批自己的转账（`APPROVAL_OWN_REQUEST`），不需要另写职责分离检查。
- **Q5 已有的钱包部件**：
  - `QbWallets.change` / `pays` / `lockName("SPONSOR"|"TAKER", 所有者)`。
  - `qb_wallet` 已有 `pending_out` 列与 `TAKER` 种类，各列都有 `>= 0` 的检查约束。
  - `QB_PENDING_SETTLE`（`internal()`，自己取钱包锁）由订阅 `qb.pending-settle` 接 `qb.sponsor.settle-pending`。
  - `QB_ATTEMPT_SUBMIT` / `EXPIRE` / `QB_PENDING_PAY` 发布 `qb.reward.settled`（载荷 `{attemptId, userId, publicationId, reward}`）。
  - `DevCreditCheck` 拒绝开发入账与 `sk_live_` / `rk_live_` 同时出现。
  - 迁移到 `V6`，Q6a 用 `V7`，Q6b 用 `V8`。
  - 科目 1110、1120、2110、2120、2130、4110、5110 已由 Q1 建立，不增加科目。
- **测试夹具**：runtime 的 `StubHttpServer`（按路径编排应答并记录请求）；`TestTokens`；`QbItSupport`、`PlayItSupport`。测试配置须关闭外部作业执行器，并开启 `simulated-results`。

**Stripe 事实**（开工首日对照 Stripe 文档核实，结论写进实施记录）：
- 日元是零小数位币种，金额就是日元。Checkout 的 `expires_at` 在 30 分钟到 24 小时之间。
- 卡支付成功时 `checkout.session.completed` 的 `payment_status = paid`。会话过期发 `checkout.session.expired`。
- `charge.refunded` 中的 `amount_refunded` 是累计值。
- Connect 账户的 `account.updated` 只发到 **Connect 端点**，签名密钥与账户端点不同。
- 转账是同步 API：`POST /v1/transfers` 成功即资金已进入连接账户；可以按 `transfer_group` 列出转账。
- `Idempotency-Key` 至少保留 24 小时。以 `Stripe-Version` 请求头固定 API 版本；Webhook 正文的版本由端点设置决定。

## 设计决定（待确认，见文末问题）

### D-Q6-1 Stripe 的接入方式【待确认 Q6-2】

- **外部服务 `stripe`**（`wallet.StripeService`）：
  - 基础地址 `https://api.stripe.com`，主机 `api.stripe.com`，密钥 `apiKey` ← `STRIPE_API_KEY`；
  - 幂等键请求头 `Idempotency-Key`；连接超时 5 秒、请求超时 20 秒、响应上限 256 KiB；
  - 不声明 `allowInDryRun`。
  - 部署文档建议使用**受限密钥**（`rk_test_…`），只开所需权限：Checkout Sessions 写、PaymentIntents 读、Transfers 写与读、Accounts 与 Account Links 写。
- **`StripeApi`**（纯 Java 部分 + 处理器用的薄封装）：
  - 表单编码 `StripeForm`：嵌套键如 `line_items[0][price_data][unit_amount]`，按 `application/x-www-form-urlencoded` 规则转义；
  - 请求头：`Authorization: Bearer`，固定的 `Stripe-Version`（配置 `quizbuks.stripe.api-version`）；
  - 回答解析为 `Map` / `BigDecimal`；错误分类 `StripeErrors`：4xx 中 `rate_limit` 与 409 幂等冲突为可重试，其他 4xx 为永久（例外见 D-Q6-12），5xx、429、超时、不可用为可重试；
  - 密钥只在请求头中，不进任何日志与上下文（平台的 `Request.toString` 不含请求头的值）。
- **全部 Stripe 调用都是异步外部作业**，不放在流程事务中的阻塞步骤里。理由：
  1. 不在事务中等外部调用；
  2. 重试与幂等由平台持久化；提交后步骤的重试不持久化，不能用；
  3. 场景回放以 `completeExternalJob` 写入结果，不需要网络或桩服务；
  4. 作业的请求人上限顺带限制每个商家同时打开的支付页。

  代价：创建支付页要等执行器领取并回写，约 1–3 秒，商家后台轮询充值状态后再跳转。
  另一种做法：`QB_TOPUP_START` 在阻塞步骤中同步创建会话并返回地址（Stripe 幂等键为 `qb-topup-<充值号>`）；场景则要另想办法指向桩服务【待确认 Q6-2】。
- **入站提供方**（`application.yml`，密钥只来自环境变量，无缺省值，缺少即启动检查报错）：
  - `stripe` ← `STRIPE_WEBHOOK_SECRET`，Stripe 后台的账户端点 `https://<主机>/api/inbound/stripe`；
  - `stripe-connect` ← `STRIPE_CONNECT_WEBHOOK_SECRET`，Connect 端点，Q6b 才加。

  白名单只列出各 PR 订阅的类型：
  - Q6a：`checkout.session.completed`、`checkout.session.expired`、`charge.refunded`、`charge.dispute.created`、`charge.dispute.closed`；
  - Q6b：`transfer.reversed`、`payout.paid`、`payout.failed`，以及 `stripe-connect` 的 `account.updated`、`account.application.deauthorized`。

  `application-dev.yml` 与测试配置给固定的开发与测试密钥（同 `app` 的 `demo-pay`）。
- **模式**：配置 `quizbuks.stripe.livemode`（缺省 `false`）。启动检查 `StripeModeCheck` 合并 Q5 的 `DevCreditCheck`，规则如下：
  - `livemode=false` 时 `STRIPE_API_KEY` 必须是 `sk_test_` / `rk_test_`，反之亦然；
  - `livemode=true` 时开发入账不得开启（另见 D-Q6-9）；
  - 事件的 `livemode` 与配置不同即不入账，开待办（D-Q6-3）。
- **本地桩服务** `tools/quizbuks/stripe-stub/StripeStub.java`（单文件，JDK `HttpServer`，同平台的 `AiStub`）：
  - 实现本阶段用到的端点；
  - 支付页上的"付款"按钮以开发密钥签名，把 `checkout.session.completed` 发到 `http://localhost:8080/api/inbound/stripe`。

  dev 配置把 `stripe` 指向它。真实测试模式另可用 Stripe CLI：`stripe listen --forward-to …/api/inbound/stripe`。

### D-Q6-2 充值与状态【待确认 Q6-3、Q6-4、Q6-5、Q6-20】

- **`QbTopUp` 是普通表（P）**，而设计 §3.4 写的是时态（T）【待确认 Q6-20】：
  - 通知要按 `checkoutSessionId`、`paymentIntentId` 查找，二者都是后写入的可变字段，时态表上按可变字段查找要读全部版本（D29）；
  - 状态在几分钟内连续变化；
  - 普通表的写入照常进入审计（`sys_audit_record`）。

  主键 `varchar(36)`，同 Q5 的会话与钱包。
- **状态**：
  - `OPENING`：已登记，等待作业创建 Checkout Session；
  - `OPEN`：有支付页地址；
  - 从 `OPEN` 到以下之一：`PAID`（到账）、`EXPIRED`（会话过期）、`FAILED`（会话创建失败，如作业失败）。

  退款与争议不改状态，记在 `refundedAmount`、`disputeStatus` 两列。状态只由流程在商家钱包锁内改变。
- **`QB_TOPUP_START {amount}`**（商家本人）：
  - 先查调用者：`requiresVerifiedEmail()`；`CallerChecks.refuseBanned`；`SponsorChecks`，即入驻须已通过【缺省 Q6-3】。
  - 再查金额：整数 Kudos，在受控参数 `qb.topup.min`（缺省 1 000）到 `qb.topup.max`（缺省 1 000 000）之间（`QB_TOPUP_AMOUNT_RANGE`）。
  - 本人 `OPENING` / `OPEN` 且未过期的充值至多 3 笔（`QB_TOPUP_TOO_MANY_OPEN`）。这是软检查、不取锁，并发多出的一两笔无害；另由作业类型的 `maxOpenPerRequester(3)` 兜底。
  - 插入 `OPENING`，并登记作业 `qb.stripe.checkout-create`（引用为充值号，请求人为商家）。
  - 输出 `topUpId`、`status`。支持 `Idempotency-Key`。
- **金额只信服务端记录**：会话的金额、币种、元数据、返回地址都由服务端按记录生成：
  - `line_items[0][price_data][currency]=jpy`、`[unit_amount]=记录的金额`、`quantity=1`；
  - `mode=payment`、`client_reference_id=topUpId`、`metadata[topUpId]`、`metadata[sponsorId]`、`payment_intent_data[metadata][topUpId]`；
  - `payment_method_types[]=card`【缺省 Q6-4：只用卡，同步到账】；`allow_promotion_codes=false`；
  - `expires_at = now + quizbuks.stripe.checkout-ttl`（缺省 1 小时）；
  - `success_url` / `cancel_url` 由配置 `quizbuks.links.sponsor` 拼出（`…/wallet/topups/<topUpId>`），不接受客户端给的地址，因此没有开放重定向。

  浏览器跳回只用于显示，从不据此入账。
- **外币**【缺省 Q6-5】：会话币种固定 JPY，账本照旧只有 JPY。顾客若经 Stripe 的本地货币显示（Adaptive Pricing）付外币，只把会话中的原币金额、币种与汇率记到充值行（F-04），不入外币分录。
- **完成流程 `QB_TOPUP_OPENED`**：
  - 核对结果：会话号符合 `cs_(test|live)_…`；地址以 `https://checkout.stripe.com/` 开头（桩服务的地址由 dev 配置的前缀放行）；`livemode` 与配置相同；
  - 核对通过：写 `checkoutSessionId`、`checkoutUrl`、`expiresAt`，状态 `OPENING → OPEN`；不符即 `FAILED` 并开待办。
- **失败流程 `QB_TOPUP_OPEN_FAILED`**：`OPENING → FAILED`，记错误码，商家可以重新发起。
- **支付页地址**只有本人能读（商家数据视图 `urn:jabiz:dataset:sponsor:QbTopUp`，`fromContext("sponsorId", actorId)`），只在 `OPEN` 且未过期时给出。

### D-Q6-3 到账确认：只入账一次【待确认 Q6-6、Q6-9】

**消费流程 `QB_TOPUP_CONFIRM`**（订阅 `jabiz.inbound.stripe.checkout.session.completed`，`internal()`）的步骤：
1. `LoadInboundEvent`。
2. 按 `client_reference_id`（主键）**在锁外**读充值，取得商家。
3. `HoldLock.exclusive(qb.wallet:SPONSOR:<充值的商家>)`：锁名取自服务端的记录，不取自事件中的元数据。
4. 锁内重读充值与钱包。
5. 纯 Java 的 `TopUpChecks.verify(充值, 会话, 期望模式)` 一次判断，结果为 `CREDIT`、`ALREADY`（已是 `PAID` 且同一会话），或下面的待办原因之一：

   | 原因代码 | 条件 |
   |---|---|
   | `UNKNOWN_TOPUP` | 找不到充值，或编号不符合格式 |
   | `SESSION_MISMATCH` | 会话号与记录不同、`metadata.topUpId` 与 `client_reference_id` 不一致，或 `metadata.sponsorId` 与记录不同 |
   | `NOT_PAYMENT` | `mode ≠ payment` |
   | `NOT_PAID` | `payment_status ≠ paid` |
   | `CURRENCY` | `currency ≠ jpy` |
   | `AMOUNT_MISMATCH` | `amount_total` 或 `amount_subtotal` 不等于记录的金额 |
   | `LIVEMODE` | 事件的模式与配置不同 |
   | `STATUS` | 记录已 `EXPIRED` / `FAILED`，或已以另一会话 `PAID` |

6. `CREDIT` 时（**同一事务**）：
   - 子流程 `LEDGER_POST`：借 1110 / 贷 2110（`party` = 商家），记账时间为事件的 `signedTime`，来源 `QbTopUp` / 充值号，参考 `paymentIntentId`。
   - `QbWallets.change(+金额)`。
   - 充值写入：状态 `PAID`，以及 `paidAt`、`paymentIntentId`、`livemode`、原币信息、`ledgerTransactionId`、`confirmedBy`（入站事件号）。
   - 发布事件：`qb.sponsor.settle-pending`（补发，见下）与 `qb.topup.paid`（`{topUpId, sponsorId, amount}`，供 Q7 的消息与 Q9 的收据）。
   - 登记作业 `qb.stripe.payment-fee`（D-Q6-5）。
7. 待办原因：`CreateTask` 给持有 `qb.reconcile` 的人，来源键 `stripe:<外部事件号>`，正文写明原因、金额、币种与充值（若找到）。**不入账、不让流程失败**（失败会被无谓地重试到上限）。

**"只入账一次"的四层保证**：
1. 平台按 Stripe 事件号去重；Stripe 的重发用同一事件号。
2. 每个（消费者，事件）只处理一次。
3. 同一会话的不同事件（例如以后加的 `async_payment_succeeded`），以及核实作业（D-Q6-4），都经同一套入账步骤 `TopUpCrediting.steps`：都在商家钱包锁内重读状态，只有 `OPENING` / `OPEN` 能变为 `PAID`，普通表按版本号更新。
4. 数据库兜底：`qb_topup.ledger_transaction_id` 唯一；检查约束 `status <> 'PAID' OR ledger_transaction_id IS NOT NULL`；`payment_intent_id`、`checkout_session_id` 唯一。

**补发**【缺省 Q6-6】：发布 Q5 的 `qb.sponsor.settle-pending`，由订阅 `qb.pending-settle` 在另一个事务中以系统身份运行 `QB_PENDING_SETTLE`，与开发入账同一条路。
- 好处：到账事务短；补发失败不回滚到账；只有一条代码路径。
- 补发清空队列后照 Q5 发布 `qb.sponsor.funds-restored`，`FUNDS_SHORT` 的发布回到 `LIVE`（需求 5.3-2"商家充值后恢复为进行中"）。
- 另一种做法：作为 `internal()` 子流程直接调用 `QB_PENDING_SETTLE`（D41 允许，因为根流程以系统身份运行）。

**封禁的商家照常入账**：钱已收到。封禁只挡发起充值。

### D-Q6-4 过期、丢失的通知与向 Stripe 核实【待确认 Q6-12】

- **`QB_TOPUP_EXPIRE`**（订阅 `checkout.session.expired`，`internal()`）：商家钱包锁内，`OPEN` / `OPENING` 且会话相符 → `EXPIRED`；已 `PAID` 即什么也不做，并记下"矛盾"（不应发生）。
- **丢失的通知**：Stripe 会重试 Webhook 约 3 天，但端点配置错误、密钥换错时会丢。
  - 逐项任务 `qb.topup-stale`（每小时，UTC 第 17 分）用模板 `qb.system.topups-stale` 选出以下充值：`OPEN` 且 `expiresAt + 1 小时 < 计划时刻`；或 `OPENING` 超过 1 小时。
  - 每项运行 `QB_TOPUP_CHECK`：登记作业 `qb.stripe.session-check`（`GET /v1/checkout/sessions/{id}`，`oneOpenPerReference`）。
  - 完成流程 `QB_TOPUP_CHECKED` 在商家钱包锁内处理：
    - `complete` 且 `paid` → 经同一套入账步骤入账（核对项同 D-Q6-3，来源记为核实作业）；
    - `expired` → `EXPIRED`；
    - 仍 `open` → 什么也不做。
  - 失败流程开待办。
  - `OPENING` 卡住（作业仍在重试）的不另处理：作业有自己的重试与失败流程。
- 核实与通知同时到达时，只入账一次（D-Q6-3 第 3、4 层；有测试）。

### D-Q6-5 手续费与原币信息（F-04）【待确认 Q6-10】

- 到账时登记作业 `qb.stripe.payment-fee`（请求人为系统）：`GET /v1/payment_intents/{pi}?expand[]=latest_charge.balance_transaction`，结果为 `{chargeId, balanceTransactionId, fee, net, currency}`。
- 完成流程 `QB_TOPUP_FEE_RECORD`（商家钱包锁内，只为改充值行）：
  - 核对：币种为 `jpy`，`net + fee = amount`，充值尚未记过手续费；
  - 过账：借 5110 / 贷 1110，金额为手续费，记账时间为到账时间，来源 `QbTopUp`；
  - 写 `stripeFee`、`netAmount`、`chargeId`、`balanceTransactionId`、`feeTransactionId`。
  - 手续费为 0 时不过账。
- 失败或不符：开待办，不过账。手续费的误差留给 F-05 的逐笔核对。
- 原币（F-04 的"原币金额、汇率"）取自会话的币种转换信息：`presentmentCurrency`、`presentmentAmount`（该币种的最小单位）、`fxRate`；没有时为空。

### D-Q6-6 退款与争议【待确认 Q6-7、Q6-8】

QuizBuks 不提供退款流程（Q14 缺省）。退款只可能由管理员在 Stripe 后台发起，所以这里是防御性处理：

- **`QB_TOPUP_REFUNDED`**（订阅 `charge.refunded`）的步骤：
  1. 按 `payment_intent` 找充值（唯一索引）；
  2. 商家钱包锁；
  3. 纯 Java 的 `RefundMath`：本次 = 累计 `amount_refunded` − 已记的 `refundedAmount`。不大于 0 即什么也不做（重复与乱序的事件幂等）。
  4. 商家余额 ≥ 本次：过账借 2110（商家）/ 贷 1110，钱包减少，`refundedAmount` 改为累计值。
  5. 余额不够（Kudos 已被奖励花掉，钱包不能为负）：**不过账**，记 `refundShortfall`，开待办 `REFUND_SHORTFALL`，由财务线下处理。差额会出现在 F-05 中。
- **乱序**：退款事件可能先于到账事件处理（投递不保证顺序）。找不到 `paymentIntentId` 时，事件接收不到 1 小时即让流程失败（Outbox 按退避重试），超过 1 小时开待办 `UNKNOWN_PAYMENT`。
- **争议**：
  - `charge.dispute.created` → `QB_TOPUP_DISPUTE_OPENED`：记 `disputeStatus = OPEN`，开待办。
  - `charge.dispute.closed` → `QB_TOPUP_DISPUTE_CLOSED`：
    - `lost` 时按退款同样处理（扣回争议金额，余额不够即待办）；
    - `won` 时只记状态。
  - 争议手续费留给 F-05。
- 两个类型各一个流程：`LoadInboundEvent` 一步只认一个类型（平台缺口 18），共用步骤 `TopUpClawback.steps`。

### D-Q6-7 余额概览与流水（U-50 – U-52、M-04 的商家部分）

| 模板 | 用途 | 数据 |
|---|---|---|
| `qb.sponsor.wallet` | 商家钱包概览 | 钱包缓存的余额、待支付笔数与金额；累计充值（`PAID` 的金额 − 已退款）；累计支出（按本人 `party` 汇总 2110 借方，维度索引） |
| `qb.sponsor.topups` | 本人的充值记录 | 状态、金额、时间、手续费；支付页地址只在 `OPEN` 且未过期时给出 |
| `qb.app.wallet` | U-50（权限 `qb.me`） | 见下 |
| `qb.app.ledger` | U-51 / U-52 | 见下 |

`qb.app.wallet` 的列：
- 可用余额：本人 2120 的余额（按 `dimension1` 下推，走索引；Q6b 改读钱包缓存，两者由核对保证一致）；
- 累计收入：2120 贷方合计；
- 待支付奖励：本人 `PENDING` 会话的奖励合计（尚未扣抽成，显示为预计）；
- 处理中的转账：2130 余额，加上 `REVIEW` 的转账（Q6b）；
- 收款账户状态（Q6b）。

`qb.app.ledger`：本人会话中奖励大于 0 的（类型 `REWARD`，状态 `PAID` → 完成、`PENDING` → 处理中，带来源 Quiz 的标题）与本人转账（类型 `TRANSFER`、`TRANSFER_REVERSED`，Q6b）的 `UNION ALL`。
- 筛选：关键字、类型、状态、日期区间、金额区间；按时间排序。
- 需求列出的"奖金、退款"两种类型目前不存在。
- 只读普通表（会话、转账），按用户的索引查找。
- 金额：已支付的取实收（奖励 − 抽成），待支付的取奖励。

### D-Q6-8 每日核对（F-06）

- 任务 `qb.reconcile`（单流程，每天 UTC 18:37，即东京 03:37）运行 `QB_RECONCILE_RUN {scheduledTime}`（`internal()`，不取业务锁，只读模板，最后写结果）。
- **每一项比较在一条 SQL 语句中完成**：缓存与账本在同一事务中提交，单条语句在 READ COMMITTED 下看到一致的快照，所以运行期间的结算不会造成假差异。
- 模板只返回**有差异的行**：全量的按当事人汇总超过 `jabiz.process.max-read-rows` 会被拒，平台缺口 25。

| 检查 | 期望 |
|---|---|
| `SPONSOR_WALLET` | 每个商家：缓存余额 = 账本 2110 按 `party` 的贷减借 |
| `SPONSOR_PENDING` | `pendingCount`、`pendingAmount` = `PENDING` 会话的笔数与金额 |
| `TOTAL_2110` | 2110 合计 = 全部商家钱包缓存之和 |
| `LEDGER_ZERO` | 全部可过账科目的余额之和为 0 |
| `TOPUP_LEDGER` | 每笔 `PAID` 充值恰有一笔来源为它的到账交易，金额相等；退款合计与 2110 借方（来源为该充值）相等 |
| `TAKER_WALLET`（Q6b） | 用户钱包缓存 = 2120 / 2130 按 `party` 的余额。缓存由事件同步，可能落后几秒：不符的用户发布 `qb.taker-wallet.resync`，只有连续两次运行都不符才算差异 |
| `TRANSFER_LEDGER`（Q6b） | `SENT` 的转账有发出与结算两笔交易，`FAILED` 的发出交易已冲正 |

- **结果**：只写一次的时态实体 `QbReconRun`（每次运行一行：计划时刻唯一、状态 `OK` / `DIFF`、各项计数）与 `QbReconItem`（每个差异一行：种类、当事人、期望、实际、差额）。
- 有差异时：`CreateTask` 给 `qb.reconcile`（来源键 `recon:<日期>`），并发布 `qb.reconcile.diff`。Q9 的凭证导出读最近一次运行，`DIFF` 即停止当日导出（需求 7.4"不相等则暂停该期推送"）。
- 与 Stripe 的核对在 Q6 只有两处：D-Q6-4 的过期充值、D-Q6-12 的转账核实。与 Stripe 余额交易逐笔核对是 F-05，延后【待确认 Q6-12】。

### D-Q6-9 开发入账的去留【待确认 Q6-11】

- **保留** `QB_WALLET_DEV_CREDIT`，只供开发与测试，不改行为：Q5 的测试与场景依赖它快速给商家余额，本地无 Stripe 时也能演示。
- 启动检查加严（`StripeModeCheck`）：开启它而 `quizbuks.stripe.livemode=true`，或密钥为正式密钥时，启动失败。
- 部署说明写明：生产不开；有了充值后不再需要它。
- 不做正式的"人工入账"（商家银行汇款、财务提出、他人批准）。需要时另立：新流程以审批对象绑定金额，分录同到账，来源为人工入账单。

### D-Q6-10 收款账户：Stripe Connect Express（Q6b）【待确认 Q6-13】

- **`QbPayoutAccount`（T，按设计 §3.1）**：
  - `payoutAccountId`；`userId`（不可变、唯一）；`provider = STRIPE_CONNECT`；
  - `externalAccountId`（`acct_…`，不可变、唯一，`f.masked(qb.admin.finance.read, LAST4)`）；`country`；
  - `status`（`PENDING` / `ENABLED` / `RESTRICTED` / `DEAUTHORIZED`）、`payoutsEnabled`、`detailsSubmitted`、`requirementsDue`（逗号分隔、截断）、`stripeUpdatedAt`；以上 `processOnly`。
  - 查找只按不可变字段（`userId`、`externalAccountId`），时态表适用。平台不保存银行信息。
- **`QB_PAYOUT_ONBOARD`**（`qb.payout.onboard`，答题人本人）：
  - 先查：`requiresVerifiedEmail()`、`refuseBanned`；资料国家须等于配置 `quizbuks.stripe.connect-country`（缺省 `JP`），否则 `QB_PAYOUT_COUNTRY_UNSUPPORTED`（Q18：不跨境）。
  - 登记作业 `qb.stripe.connect-onboard`（引用为用户，`oneOpenPerReference`）。处理器：
    1. 没有账户时 `POST /v1/accounts`（`type=express`、`country`、`capabilities[transfers][requested]=true`、`business_type=individual`、`metadata[userId]`），幂等键 `qb-connect-<userId>`；
    2. `POST /v1/account_links`（`type=account_onboarding`，返回与刷新地址由 `quizbuks.links.app` 拼出）。
  - 完成流程 `QB_PAYOUT_ONBOARD_READY`（`qb.account:<userId>` 锁）：
    - 没有账户行时插入 `PENDING`；已有而编号不同即开待办（不应发生）；
    - 开通链接与过期时间写进普通表 `qb_payout_onboarding`（每人一行、覆盖；短期有效，不进时态历史）。

    页面轮询后跳转。
- **`QB_PAYOUT_ACCOUNT_UPDATED`**（`stripe-connect` 的 `account.updated`，`internal()`）：
  - 账户锁内按 `externalAccountId` 读；
  - **只接受更新的状态**：事件的 `created` 早于已记的 `stripeUpdatedAt` 即忽略（事件不保证顺序）；
  - `ENABLED` ⇔ `payouts_enabled` 且 `capabilities.transfers = active`；
  - 核对 `livemode`。
- **`QB_PAYOUT_ACCOUNT_DEAUTHORIZED`**（`account.application.deauthorized`）→ `DEAUTHORIZED`。
- 一个 Stripe 账户只属于一个用户（唯一约束；设计 §9 的防刷）。

### D-Q6-11 用户钱包缓存与锁（Q6b）【待确认 Q6-16】

- 转账要从"余额达到门槛的全部用户"中选取。按账本全量汇总 2120 随数据增长变慢，所以给答题人建钱包缓存：`qb_wallet` 的 `TAKER` 行，`balance` = 2120 余额，`pendingOut` = 2130 余额。
- **不在结算事务中取用户钱包锁**：结算已持有发布锁与商家钱包锁，吞吐已是瓶颈（Q5 基线）。缓存改由事件驱动：
  - `QB_TAKER_WALLET_SYNC {userId}` 订阅 `qb.reward.settled`（Q5 已发布，含补发）、`qb.transfer.changed`、`qb.taker-wallet.resync`；
  - 在 `qb.wallet:TAKER:<userId>` 锁内**按账本重算**本人 2120 / 2130 余额（模板 `qb.system.taker-balance`，按 `dimension1` 下推），覆盖写入。
  - 重算与事件的顺序、次数无关，重复与乱序都收敛到账本，不会重复累加。
- **新订阅收到历史事件**（11 §2.3）：部署 Q6b 后，已有奖励的用户自动回填缓存。
- 转账流程同样在用户钱包锁内过账后重算并写入；读的是同一事务内自己刚写的分录。
- 用户钱包锁只由同步与转账流程持有，二者都不取其他锁。锁序见 D-Q6-15。

### D-Q6-12 自动转账（Q6b）【待确认 Q6-14、Q6-15】

**选取**：逐项任务 `qb.transfer-run`，每天 UTC 01:07，即东京 10:07。
- 模板 `qb.system.transfers-due` 以 `timeSlice` 的计划时刻连接 `SysParam`，读 `qb.transfer.enabled` 与 `qb.transfer.threshold`（逐项任务的参数函数读不了业务参数，平台缺口 24）。
- 选出同时满足以下条件的用户，按用户编号排序，至多 2 000 项：
  - `TAKER` 钱包余额 ≥ 门槛；
  - 收款账户 `ENABLED`；
  - 未被封禁；
  - 没有未结束的转账。
- 开关关闭时没有项。

**每项 `QB_TRANSFER_CREATE {userId}`**（`internal()`）：
1. 先取用户钱包锁，再重读一切：参数（`LoadParams`）、封禁、账户、未结束的转账、按账本的余额。不满足即什么也不做（输出跳过的原因）。
2. 插入 `QbTransfer`：金额为**全额余额**（U-53"金额 ≤ 可用余额"），手续费取受控参数 `qb.transfer.fee`（缺省 0，"可关闭"，需求 5.3-14）。
3. 金额 > `qb.transfer.review-above`：状态 `REVIEW`，`RequireApproval`（对象 `qb.transfer`，事实 `needsReview = true`、`amount`、`userId`，**准备人 = 收款人**），这时不过账。
4. 否则进入"发出"（与审批通过共用 `TransferSending.steps`）：
   - 过账：借 2120（用户）金额 / 贷 2130（用户）实付 / 贷 4110 手续费（为 0 时不出这一行），来源 `QbTransfer`；
   - 状态 `SENDING`，重算缓存；
   - 登记作业 `qb.stripe.transfer`（引用为转账号，请求人为系统）；
   - 发布 `qb.transfer.changed`。
5. 数据库兜底："每人至多一笔未结束的转账"由部分唯一索引 `qb_transfer (user_id) WHERE status IN ('REVIEW','SENDING','CHECK')` 保证。

**审批**：`QB_TRANSFER_APPROVAL_RESULT`（`internal()`，订阅审批的通过与驳回，只认转账自己的请求，同 Q4）。
- 批准：重查余额 ≥ 金额、账户、封禁后进入"发出"；不成立即 `REJECTED` 并开待办。
- 驳回：`REJECTED`，不过账。
- 规则 `qb.transfer.review`（`needsReview = true` 时需要 `qb.payout.review`）由 `QB_SETUP` 以受控变更提出（`QbApprovalRules`）。P-03 的"提现审批"由此实现：只审大额。

**发出**（作业处理器 `StripeTransferHandler`）：
1. 先 `GET /v1/transfers?transfer_group=qb-<transferId>&limit=1`：已存在即作为结果返回。这样作业用尽重试、租约过期、甚至以后重新登记作业，都不会重复转账。
2. 否则 `POST /v1/transfers`（`amount=实付`、`currency=jpy`、`destination=acct_…`、`transfer_group=qb-<transferId>`、`metadata[transferId]`），幂等键 `qb-transfer-<transferId>`，与作业无关、固定。
3. 错误分类：
   - `balance_insufficient`：可重试，退避长（`retry(8, 10 分钟, 6 小时)`）；
   - 其他 4xx：永久失败，码 `STRIPE_DECLINED`，附 Stripe 的错误码；
   - 5xx、429、超时：可重试。

**结果**：
- 完成 `QB_TRANSFER_SENT`（用户钱包锁）：`SENDING → SENT`，记 `stripeTransferId`；过账借 2130 / 贷 1110；重算缓存；发布 `qb.transfer.changed` 与 `qb.transfer.sent`（Q7 的消息与邮件）。
- 失败 `QB_TRANSFER_SEND_FAILED`：
  - **确定没有转账**（码为 `STRIPE_DECLINED` 或 `STRIPE_BALANCE_INSUFFICIENT`）：`LEDGER_REVERSE` 冲正发出交易（2130 与 4110 回到 2120），`FAILED`，发布 `qb.transfer.failed`。下次每日运行再试。
  - **不确定**（`ATTEMPTS_EXHAUSTED` 于超时或不可用、`LEASE_EXPIRED`、`BAD_RESULT`）：**不冲回**，状态 `CHECK`，开待办。
  - 同一用户连续 3 次 `FAILED` 后，选取时跳过（`failureStreak`），并开待办，等财务处理（例如账户被 Stripe 限制）。

**核实**：逐项任务 `qb.transfer-stale`（每小时）选出 `CHECK`，以及超过 1 小时的 `SENDING`（作业卡住）。每项 `QB_TRANSFER_CHECK` 登记作业 `qb.stripe.transfer-check`（只做按 `transfer_group` 列出），完成流程 `QB_TRANSFER_CHECKED`：
- 找到 → 同 `SENT`；
- 确定没有，且发出作业已结束 → 同"确定没有转账"冲正；
- 作业仍在运行 → 什么也不做。

**撤回**：`transfer.reversed`（账户端点）→ `QB_TRANSFER_REVERSED`：
- 按 `metadata.transferId` / `stripeTransferId` 找转账；
- 用户钱包锁内，按累计 `amount_reversed` 计本次（同退款的幂等做法）；
- 过账借 1110 / 贷 2120（钱回到平台，对用户的负债恢复；手续费不退）；
- `REVERSED`（部分撤回只记金额）；开待办；发布事件。

**封禁**：选取与每项都排除被封禁的用户（P-02"封禁后不可提现"）。已在 `SENDING` 的照常完成。

**到账时效**（需求 5.3-14）：只是显示用的说明，写在 Q7 的帮助内容中，不是参数。

### D-Q6-13 Stripe 打款到银行（Q6b，可延后）【待确认 Q6-17】

- `payout.paid` → `QB_STRIPE_PAYOUT_RECORD`：借 1120 / 贷 1110，来源为入站事件的引用（参考 `po_…`），按 `payout.id` 只记一次：普通表 `qb_stripe_payout`，`payout_id` 唯一。
- `payout.failed`：若已记过就冲正。
- 有了它，1110 才接近 Stripe 的实际余额，为以后的 F-05 打基础。不做也不影响 Q6 的其他部分。

### D-Q6-14 安全

- **验签、重放、去重**由平台入口完成（D37）：时间容差 5 分钟，事件号去重。应用不另做，也不在其他接口接收回调。
- **签名不等于内容可信**：
  - 每个消费流程逐项核对：格式、模式、币种、金额与服务端记录、单据归属；
  - 金额只取服务端记录；
  - 锁名与入账对象只取服务端记录，不取事件元数据；
  - 不成立的事件开待办，不入账。
- **不信任客户端**：充值的唯一客户端输入是金额，受受控上下限约束；返回地址由配置生成；浏览器回跳不入账；支付页与开通链接只给本人。
- **密钥**：`STRIPE_API_KEY`、`STRIPE_WEBHOOK_SECRET`、`STRIPE_CONNECT_WEBHOOK_SECRET` 只来自环境变量，`deploy/quizbuks/README.md` 列出。不进仓库；测试与 dev 用固定的假值。
  建议使用受限密钥。换签名密钥时 `secrets` 同时列出新旧两个。
- **模式隔离**：测试与正式密钥、事件模式、开发入账三者由启动检查与消费流程共同约束（D-Q6-1、D-Q6-9）。
- **个人信息**：
  - Stripe 事件的原样正文可能含付款人姓名与邮箱，只有 `QB_ADMIN_FINANCE` 有 `inbound.read`；
  - 声明 `RetentionPolicy.of("InboundEvent").keep(7 年).from("receivedTime")`【待确认 Q6-18】；
  - 不把商家邮箱传给 Checkout（Stripe 自己在支付页收集）。
- **限流**：
  - `/api/inbound/**` 由平台按地址限流。部署在代理之后必须配置 `JABIZ_TRUSTED_PROXIES`，否则 Stripe 的全部投递共用一个桶。写进部署说明。
  - 发起充值的速率由"至多 3 笔未完成"与作业请求人上限约束。按用户的接口限流仍是 Q5 的平台缺口 13。
- **出站**：只到 `api.stripe.com`（主机白名单，不跟随重定向），ArchUnit 禁止业务包直接联网。

### D-Q6-15 锁的顺序（更新 `backend/quizbuks/CLAUDE.md`）

```
qb.account:<用户> → qb.quiz:<模版>（共享）→ qb.pub:<发布> → qb.wallet:SPONSOR:<商家> ；qb.wallet:TAKER:<用户> 独立
```

| 流程 | 锁 |
|---|---|
| `QB_TOPUP_START` | 不取 |
| `QB_TOPUP_OPENED` / `OPEN_FAILED` / `CONFIRM` / `EXPIRE` / `CHECKED` / `FEE_RECORD` / `REFUNDED` / `DISPUTE_*`，`QB_PENDING_SETTLE` / `PAY`，`QB_WALLET_DEV_CREDIT` | 只取 `qb.wallet:SPONSOR:<充值的商家>` |
| `QB_PAYOUT_ONBOARD_READY` / `ACCOUNT_UPDATED` / `ACCOUNT_DEAUTHORIZED` | 只取 `qb.account:<用户>` |
| `QB_TAKER_WALLET_SYNC`，`QB_TRANSFER_*` | 只取 `qb.wallet:TAKER:<用户>` |
| `QB_RECONCILE_RUN`、选取任务 | 不取 |

每个新流程只持一把锁，不会与 Q5 的"发布 → 商家钱包"成环。转账流程读收款账户时不取账户锁；账户在发出期间被停用时，由 Stripe 拒绝后冲回。

## 要求

1. **权限**（`QbPermissions`、`QbRoles`；部署后再执行一次 `QB_SETUP`）：
   - 沿用：`qb.topup`、`qb.sponsor.finance.read`（商家）；`qb.me`、`qb.payout.onboard`（答题人）；`qb.admin.finance.read`、`qb.payout.review`、`qb.reconcile`（财务）。
   - 新增 `qb.wallet.system`、`qb.payout.system`：系统流程的声明权限，不授予任何角色，同 `qb.play.system`。
   - `QB_ADMIN_FINANCE` 增加 `inbound.read`（查看原样的 Stripe 事件）与 `external-job.read`（查看作业）。
2. **受控参数**（`QbParams.ALL` 与 `CONTROLLED`，由 `QB_SETUP` 提出）：
   - Q6a：`qb.topup.min`（1 000）、`qb.topup.max`（1 000 000）；
   - Q6b：`qb.transfer.fee`（0）。
   - 已有的 `qb.transfer.threshold`、`review-above`、`enabled` 不变。
3. **审批**：对象 `qb.transfer`（`TransferApprovals`）、规则 `qb.transfer.review`（`QbApprovalRules`，受控），Q6b。
4. **配置**（`application.yml`，非业务参数）：
   - `quizbuks.stripe.livemode`、`api-version`、`checkout-ttl`、`connect-country`；
   - `quizbuks.links.app`；
   - `jabiz.inbound-webhooks.providers.stripe`（与 Q6b 的 `stripe-connect`）；
   - `jabiz.external-services.stripe` 的环境覆盖说明。
   - 测试配置：Stripe 假密钥、提供方密钥、`jabiz.external-jobs.executor.enabled=false`、`jabiz.external-jobs.simulated-results=true`、`stripe` 的基础地址由各测试指向 `StubHttpServer`。
5. **字典**（三语）：`topup-status`、`transfer-status`、`payout-account-status`、`recon-check`。
6. **实体**：见下节。数据视图：
   - 默认视图只读给财务，`processOnlyWrites`；
   - 商家视图 `QbTopUp`（本人）；答题人视图 `QbTransfer`、`QbPayoutAccount`（本人，只读）；
   - 写权限沿用不授予任何角色的 `qb.content.view.write`。
7. **外部作业类型**：

   | 类型 | 请求人 | 引用 | 限制 | 完成 / 失败流程 |
   |---|---|---|---|---|
   | `qb.stripe.checkout-create` | 商家 | 充值号 | `maxOpenPerRequester(3)`、`oneOpenPerReference` | `QB_TOPUP_OPENED` / `QB_TOPUP_OPEN_FAILED` |
   | `qb.stripe.session-check` | 系统 | 充值号 | `maxOpenPerRequester(10000)` | `QB_TOPUP_CHECKED` / `QB_TOPUP_CHECK_FAILED` |
   | `qb.stripe.payment-fee` | 系统 | 充值号 | 同上 | `QB_TOPUP_FEE_RECORD` / `QB_TOPUP_FEE_FAILED` |
   | `qb.stripe.connect-onboard`（Q6b） | 用户 | 用户 | 每人一个未结束的 | `QB_PAYOUT_ONBOARD_READY` / `QB_PAYOUT_ONBOARD_FAILED` |
   | `qb.stripe.transfer`（Q6b） | 系统 | 转账号 | 合计并发 2 | `QB_TRANSFER_SENT` / `QB_TRANSFER_SEND_FAILED` |
   | `qb.stripe.transfer-check`（Q6b） | 系统 | 转账号 | — | `QB_TRANSFER_CHECKED` / 开待办 |

   各类型合计并发不超过 4，以免触及 Stripe 的速率限制（测试模式每秒 25 次）。
8. **定时任务**：
   - Q6a：`qb.topup-stale`（每小时，逐项）、`qb.reconcile`（每日，单流程）；
   - Q6b：`qb.transfer-run`（每日，逐项）、`qb.transfer-stale`（每小时，逐项）。
9. **订阅**：见"事件与订阅"一节。
10. **文档**：
    - `02-design.md`：
      - §3.1 `QbPayoutAccount`；
      - §3.4 `QbTopUp`、`QbTransfer` 改为普通表并补字段，`QbWallet` 的 `TAKER` 行，`QbReconRun` / `Item`；
      - §4.2 分录（退款、争议、转账手续费、失败以冲正、撤回、打款到银行）；
      - §4.3 锁；§5.2 流程；§6 模板；
      - §8 Stripe 经 JDK HTTP 与外部作业、两个入站提供方；
      - §9 安全。
    - `03-plan.md`：Q6 行拆为 Q6a / Q6b 并更新预估。
    - `backend/quizbuks/CLAUDE.md`：支付约定（只信服务端记录、入账一次的四层、锁序、全部 Stripe 调用为作业、开发入账）。
    - `deploy/quizbuks/README.md`：环境变量、Stripe 后台的两个端点与订阅的事件、API 版本须与配置一致、受限密钥的权限、可信代理、测试模式与上线前的法务意见。

## 实体与迁移

记号：T = 时态（只追加），W = 只写一次，P = 普通表。金额为 `asMonetary("JPY", 0)`，列 `numeric(19,0)`。

| 实体（表） | 类型 | 字段 |
|---|---|---|
| `QbTopUp`（`qb_topup`） | P | 见下 |
| `QbReconRun`（`qb_recon_run_version`） | W | `runId`；`scheduledTime`（唯一）；`status`（`OK` / `DIFF`）；`checkedAt`；`itemCount`；`summary`（规范 JSON：各检查的计数） |
| `QbReconItem`（`qb_recon_item_version`） | W | `itemId`；`runId`；`check`（字典）；`partyId`；`reference`；`expected`、`actual`、`difference` |
| `QbPayoutAccount`（`qb_payout_account_version`，Q6b） | T | 见 D-Q6-10 |
| `QbPayoutOnboarding`（`qb_payout_onboarding`，Q6b） | P | `onboardingId`；`userId`（唯一）；`url`（≤ 2 048）；`expiresAt`；`version` |
| `QbTransfer`（`qb_transfer`，Q6b） | P | 见下 |
| `QbStripePayout`（`qb_stripe_payout`，Q6b，可延后） | P | `payoutRowId`；`payoutId`（唯一）；`amount`；`status`；`ledgerTransactionId`；`reversalTransactionId`；`version` |

`QbTopUp` 的字段（除 `amount` 外全部 `processOnly`）：
- 标识与金额：`topUpId`、`sponsorId`、`amount`（`> 0`）、`status`、`createdAt`。
- 会话：`checkoutSessionId`、`checkoutUrl`（≤ 2 048）、`expiresAt`、`openJobId`、`failureCode`。
- 到账：`paymentIntentId`、`chargeId`、`balanceTransactionId`、`livemode`、`paidAt`、`confirmedBy`（入站事件号或核实作业号）、`ledgerTransactionId`。
- 手续费：`stripeFee`、`netAmount`、`feeTransactionId`。
- 原币：`presentmentCurrency`（3 个字母）、`presentmentAmount`（最小单位，`numeric(19,0)`）、`fxRate`（`numeric(20,10)`）。
- 退款与争议：`refundedAmount`（缺省 0，`≤ amount`）、`refundShortfall`、`disputeStatus`、`checkReason`。
- `version`。

`QbTransfer` 的字段：
- `transferId`、`userId`、`externalAccountId`；
- `amount`、`fee`、`net`（`amount = fee + net`、`net > 0`，检查约束）；
- `status`（`REVIEW` / `SENDING` / `SENT` / `FAILED` / `CHECK` / `REJECTED` / `REVERSED`）；
- `approvalRequestId`、`jobId`、`stripeTransferId`（唯一）；
- `sendTransactionId`（唯一）、`settleTransactionId`、`reversalTransactionId`；
- `reversedAmount`、`failureCode`、`failureMessage`（≤ 200）；
- `createdAt`、`sentAt`、`failedAt`、`version`。

**迁移 `V7__quizbuks_topups.sql`（Q6a）**：
- `qb_topup`：主键 `varchar(36)`。
  - 唯一：`checkout_session_id`、`payment_intent_id`、`ledger_transaction_id`。
  - 检查约束：`amount > 0`；`refunded_amount BETWEEN 0 AND amount`；`status` 取值；`status <> 'PAID' OR ledger_transaction_id IS NOT NULL`。
  - 索引：`(sponsor_id, created_at DESC)`（本人列表）；`(expires_at) WHERE status IN ('OPENING','OPEN')`（过期核实）。
- `qb_recon_run_version`、`qb_recon_item_version`：按 CLAUDE 的时态与只写一次规则建表。
  - `UNIQUE(主键, version_no)`、`(主键, effect_start_time DESC, version_no DESC)` 与 `process_seq_id` 索引；
  - 指向 `op_process` / `entity_registry` 的外键；
  - 只含主键的唯一索引（`writeOnce`）；
  - `SELECT jabiz_protect_append_only(…)`；
  - `scheduled_time`、`run_id` 上的索引。
- 不改 `qb_wallet`。

**迁移 `V8__quizbuks_payouts.sql`（Q6b）**：
- `qb_payout_account_version`：时态表全套索引与触发器，`user_id`、`external_account_id` 上的索引。
- `qb_payout_onboarding`。
- `qb_transfer`：
  - 部分唯一索引 `(user_id) WHERE status IN ('REVIEW','SENDING','CHECK')`；
  - `(user_id, created_at DESC)`；
  - `(status, created_at) WHERE status IN ('SENDING','CHECK')`；
  - 唯一的 `stripe_transfer_id`、`send_transaction_id`。
- `qb_stripe_payout`。
- `qb_wallet` 加部分索引 `(balance) WHERE kind = 'TAKER'`，供转账选取。

## 流程

共同点：
- 输入为 record，Bean Validation（400）；写入经 `ctx.changes()`；违规累积后 422。
- 人发起的流程声明 `requiresVerifiedEmail()`，最前面 `CallerChecks.refuseBanned`。
- 系统流程一律 `internal()`（D41）：先取锁、再读取、状态已不符即什么也不做（幂等）。
- 入站消费流程的写法照 `CommercePayments`：`LoadInboundEvent`；纯 Java 核对；不成立即 `CreateTask`。
- 错误码在 `wallet.WalletCodes`。

| 流程 | 权限 | 谁能启动（D41） | 输入 | 要点 | 错误代码 |
|---|---|---|---|---|---|
| `QB_TOPUP_START` | `qb.topup` | 开放（商家） | `amount` | D-Q6-2；`SponsorChecks`；登记作业；`Idempotency-Key` | `QB_TOPUP_AMOUNT_RANGE`、`QB_TOPUP_TOO_MANY_OPEN`、`QB_SPONSOR_NOT_APPROVED`、`QB_USER_BANNED`、`EXTERNAL_JOB_LIMIT` |
| `QB_TOPUP_OPENED` / `QB_TOPUP_OPEN_FAILED` | `qb.wallet.system` | `internal()`（作业完成 / 失败） | `ExternalJobCompletion` / `Failure` | 商家钱包锁；核对结果；`OPEN` / `FAILED` | — |
| `QB_TOPUP_CONFIRM` | `qb.wallet.system` | `internal()`（`checkout.session.completed`） | `inboundEventId` | D-Q6-3：核对 → 过账、钱包、`PAID`、补发事件、手续费作业；或开待办 | — |
| `QB_TOPUP_EXPIRE` | 同上 | `internal()`（`checkout.session.expired`） | `inboundEventId` | D-Q6-4 | — |
| `QB_TOPUP_CHECK` | 同上 | `internal()`（逐项任务 `qb.topup-stale` 的每项） | `topUpId` | 仍然过期未定才登记核实作业 | — |
| `QB_TOPUP_CHECKED` / `QB_TOPUP_CHECK_FAILED` | 同上 | `internal()`（作业） | 作业结果 | 与确认共用入账步骤 | — |
| `QB_TOPUP_FEE_RECORD` / `QB_TOPUP_FEE_FAILED` | 同上 | `internal()`（作业） | 作业结果 | D-Q6-5 | — |
| `QB_TOPUP_REFUNDED` | 同上 | `internal()`（`charge.refunded`） | `inboundEventId` | D-Q6-6 | — |
| `QB_TOPUP_DISPUTE_OPENED` / `QB_TOPUP_DISPUTE_CLOSED` | 同上 | `internal()`（`charge.dispute.*`） | `inboundEventId` | D-Q6-6 | — |
| `QB_RECONCILE_RUN` | `qb.wallet.system` | `internal()`（任务 `qb.reconcile`） | `scheduledTime` | D-Q6-8；同一计划时刻只一次（平台幂等键） | — |
| `QB_WALLET_DEV_CREDIT` | `qb.wallet.dev-credit` | 开放，只在开启时注册 | 不变 | D-Q6-9 | 不变 |
| `QB_PAYOUT_ONBOARD`（Q6b） | `qb.payout.onboard` | 开放（答题人） | — | D-Q6-10 | `QB_PAYOUT_COUNTRY_UNSUPPORTED`、`QB_USER_BANNED`、`EXTERNAL_JOB_LIMIT` |
| `QB_PAYOUT_ONBOARD_READY` / `_FAILED`（Q6b） | `qb.payout.system` | `internal()`（作业） | 作业结果 | 账户锁 | — |
| `QB_PAYOUT_ACCOUNT_UPDATED` / `_DEAUTHORIZED`（Q6b） | 同上 | `internal()`（`stripe-connect`） | `inboundEventId` | 按 `created` 只取更新的状态 | — |
| `QB_TAKER_WALLET_SYNC`（Q6b） | 同上 | `internal()`（三个事件） | `userId` | D-Q6-11 | — |
| `QB_TRANSFER_CREATE`（Q6b） | 同上 | `internal()`（逐项任务 `qb.transfer-run`） | `userId` | D-Q6-12 | — |
| `QB_TRANSFER_APPROVAL_RESULT`（Q6b） | 同上 | `internal()`（审批事件） | 审批事件 | 只认转账自己的请求 | — |
| `QB_TRANSFER_SENT` / `QB_TRANSFER_SEND_FAILED`（Q6b） | 同上 | `internal()`（作业） | 作业结果 | 确定与不确定的失败分开处理 | — |
| `QB_TRANSFER_CHECK` / `QB_TRANSFER_CHECKED`（Q6b） | 同上 | `internal()`（逐项任务 / 作业） | `transferId` / 结果 | 按 `transfer_group` 定论 | — |
| `QB_TRANSFER_REVERSED`（Q6b） | 同上 | `internal()`（`transfer.reversed`） | `inboundEventId` | 累计金额幂等 | — |
| `QB_STRIPE_PAYOUT_RECORD` / `_FAILED`（Q6b，可延后） | 同上 | `internal()`（`payout.*`） | `inboundEventId` | D-Q6-13 | — |

**纯 Java 部件**（无 I/O，单元测试）：
- `StripeForm`、`StripeErrors`、`StripeIds`（各种编号的格式）；
- `CheckoutRequest`：由充值记录生成表单；
- `TopUpChecks`、`TopUpStatus`、`RefundMath`；
- `WalletLimits`：未完成上限、核实宽限、批量；
- `TransferRules`：门槛、手续费拆分、是否审核、确定与不确定的失败码、连续失败；
- `ReconChecks`：差异的判定与"连续两次"规则；
- `StripeModeCheck`（启动检查）。

**分录汇总**（更新设计 §4.2）：

| 事件 | 流程 | 借 | 贷 | 来源 |
|---|---|---|---|---|
| 充值到账 | `QB_TOPUP_CONFIRM` / `CHECKED` | 1110 金额 | 2110（商家）金额 | `QbTopUp` |
| Stripe 手续费 | `QB_TOPUP_FEE_RECORD` | 5110 | 1110 | `QbTopUp` |
| 退款 / 争议败诉 | `QB_TOPUP_REFUNDED` / `DISPUTE_CLOSED` | 2110（商家） | 1110 | `QbTopUp` |
| 转账发出 | `QB_TRANSFER_CREATE` / `APPROVAL_RESULT` | 2120（用户）金额 | 2130（用户）实付；4110 手续费 | `QbTransfer` |
| 转账成功 | `QB_TRANSFER_SENT` / `CHECKED` | 2130（用户） | 1110 | `QbTransfer` |
| 转账失败 | `QB_TRANSFER_SEND_FAILED` / `CHECKED` | `LEDGER_REVERSE` 发出交易 | | 同原交易 |
| 转账被撤回 | `QB_TRANSFER_REVERSED` | 1110 | 2120（用户） | `QbTransfer` |
| 打款到银行 | `QB_STRIPE_PAYOUT_RECORD` | 1120 | 1110 | `QbStripePayout` |

## 事件与订阅

| 订阅（消费者名） | 事件 | 流程 | PR |
|---|---|---|---|
| `qb.topup-confirm` | `jabiz.inbound.stripe.checkout.session.completed` | `QB_TOPUP_CONFIRM` | Q6a |
| `qb.topup-expire` | `jabiz.inbound.stripe.checkout.session.expired` | `QB_TOPUP_EXPIRE` | Q6a |
| `qb.topup-refund` | `jabiz.inbound.stripe.charge.refunded` | `QB_TOPUP_REFUNDED` | Q6a |
| `qb.topup-dispute-open` / `-close` | `…charge.dispute.created` / `closed` | `QB_TOPUP_DISPUTE_*` | Q6a |
| `qb.payout-account` / `-deauth` | `jabiz.inbound.stripe-connect.account.updated` / `account.application.deauthorized` | `QB_PAYOUT_ACCOUNT_*` | Q6b |
| `qb.taker-wallet` | `qb.reward.settled`、`qb.transfer.changed`、`qb.taker-wallet.resync`（每个一个订阅） | `QB_TAKER_WALLET_SYNC` | Q6b |
| `qb.transfer-approval` | `jabiz.approval.approved` / `rejected` | `QB_TRANSFER_APPROVAL_RESULT` | Q6b |
| `qb.transfer-reversed` | `jabiz.inbound.stripe.transfer.reversed` | `QB_TRANSFER_REVERSED` | Q6b |
| `qb.stripe-payout` / `-failed` | `jabiz.inbound.stripe.payout.paid` / `failed` | `QB_STRIPE_PAYOUT_*` | Q6b |

本阶段发布的事件：
- Q7 订阅消息与邮件：`qb.topup.paid`、`qb.transfer.sent`、`qb.transfer.failed`；
- 内部使用：`qb.transfer.changed`、`qb.taker-wallet.resync`；
- Q9 的导出停止：`qb.reconcile.diff`；
- 沿用 Q5 的 `qb.sponsor.settle-pending`。

## SQL 模板

Q6a：
- `qb.sponsor.wallet`（`qb.sponsor.finance.read`）：D-Q6-7。
- `qb.sponsor.topups`（同上）：本人充值；筛选状态与日期，按时间倒序。
- `qb.app.wallet`（`qb.me`）：D-Q6-7。
- `qb.app.ledger`（`qb.me`）：Q6a 只有奖励，Q6b 加转账。
- `qb.admin.topups`（`qb.admin.finance.read`）：全部充值，按商家、状态、日期筛选；不给支付页地址。
- `qb.admin.wallets`：加 `TAKER` 行（Q6b）。
- `qb.admin.recon-runs`、`qb.admin.recon-items`（`qb.reconcile`）。
- 系统模板（`qb.wallet.system`）：
  - `qb.system.topups-stale`：`list.key topUpId`；
  - `qb.system.recon-sponsor-wallets`、`qb.system.recon-sponsor-pending`、`qb.system.recon-topups`、`qb.system.recon-totals`：都只返回差异行。

Q6b：
- `qb.app.payout-account`（`qb.me`）：本人账户状态，未过期的开通链接。
- `qb.admin.transfers`（`qb.admin.finance.read`，P-03：用户、金额、手续费、状态、审批、Stripe 转账号、账户末 4 位）。
- `qb.admin.payout-accounts`。
- 系统模板（`qb.payout.system`）：
  - `qb.system.transfers-due`：`timeSlice` 连接 `SysParam`；`list.key userId`；
  - `qb.system.transfers-stale`；
  - `qb.system.taker-balance`：参数 `userId`，按 `dimension1` 下推；
  - `qb.system.recon-taker-wallets`、`qb.system.recon-transfers`。

## 消息（三语，`MessagesTest` 与启动检查齐全）

- 实体与字段：`entity.QbTopUp…`、`QbTransfer`、`QbPayoutAccount`、`QbReconRun`、`QbReconItem`。
- 字典：四个。
- 流程：`process.QB_*` 共约 30 个。
- 参数：`param.qb.topup.min|max`、`param.qb.transfer.fee`。
- 错误码：
  - Q6a：`QB_TOPUP_AMOUNT_RANGE`（参数 `min`、`max`）、`QB_TOPUP_TOO_MANY_OPEN`；
  - Q6b：`QB_PAYOUT_COUNTRY_UNSUPPORTED`。
- 待办：`task.qb.topup-check`（参数含原因代码与原因的文字 `task.qb.topup-check.reason.<代码>`）、`task.qb.refund-check`、`task.qb.recon-diff`、`task.qb.transfer-check`、`task.qb.payout-account-check`。
- 审批对象：`approval.subject.qb.transfer`。
- 模板与列：`query.qb.*`。

## 测试

全部集成测试连真实 PostgreSQL，开启 BlockHound。**没有真实网络**：
- `stripe` 的基础地址指向 runtime 测试夹具 `StubHttpServer`，按路径编排应答，并断言请求的方法、表单、`Idempotency-Key`、`Stripe-Version`；
- 入站请求以测试密钥签名，经 HTTP `POST /api/inbound/stripe`，或直接交给 `InboundReceiver`；
- 作业以 `ExternalJobExecutor.runPending()` 执行，事件以 `deliverPending()` 投递。

**单元测试**：
- `StripeFormTest`（嵌套键、转义、顺序）、`CheckoutRequestTest`（金额与元数据只来自记录、返回地址只来自配置、过期时间在 30 分钟到 24 小时之间）；
- `StripeErrorsTest`（错误分类矩阵）；
- `TopUpChecksTest`（逐项不成立的矩阵，加 jqwik：任何不等于记录的金额都不会得到 `CREDIT`）；
- `RefundMathTest`（累计、重复、乱序，jqwik）；
- `TransferRulesTest`（门槛、审核边界、手续费拆分、失败码的确定与否、连续失败）；
- `ReconChecksTest`；
- `StripeModeCheckTest`（密钥前缀 × 模式 × 开发入账，含 `rk_live_`）；
- `QbRolesTest`（新授权；系统权限无人持有；`inbound.read` 只给财务）；
- `MessagesTest`。

**Q6a 的集成测试**：
- **`TopUpIT`**（M-04）：
  - 开始 → 作业对桩服务创建会话（断言表单中的金额为记录值）→ `OPEN`，有地址；
  - 签名的 `checkout.session.completed` → `PAID`：分录借 1110 / 贷 2110（商家），来源 `QbTopUp`，记账时间为签名时间；钱包增加；
  - 之前 `PENDING` 的奖励按顺序补发，`FUNDS_SHORT` → `LIVE`（5.3-2、5.3-8）；
  - 发布 `qb.topup.paid`。
- **`TopUpIdempotencyIT`**（只入账一次）：
  - 同一事件两次 → 第二次 `DUPLICATE`，一笔入账；
  - 同一会话两个不同事件号 → 一笔；
  - 通知与核实作业并发 → 一笔；
  - 4 路并发投递 → 一笔；
  - 篡改的签名、过期的时间戳、超大正文 → 400，什么也不存；
  - 逐项的不成立 → 待办、无分录、钱包不变：金额不符、币种不符、`payment_status = unpaid`、正式模式事件、未知或伪造的 `client_reference_id`、元数据中换了商家、已过期的充值；
  - 账本与钱包始终一致。
- **`TopUpExpiryIT`**：
  - `expired` 事件 → `EXPIRED`；
  - 丢失通知的充值由 `qb.topup-stale` 核实：桩服务回答已付 → 入账一次；回答已过期 → `EXPIRED`；仍然打开 → 不变；
  - 作业失败 → `FAILED`。
- **`TopUpFeeIT`**（F-04）：手续费作业 → 借 5110 / 贷 1110，写入原币与流水号；不符或失败 → 待办、不过账；重复完成不重复过账。
- **`TopUpRefundIT`**：
  - 部分与全额退款（累计值）→ 冲减商家钱包；
  - 重复与乱序的事件不重复；
  - 余额不够 → 待办、不过账、钱包不为负；
  - 退款先于到账 → 先失败重试，到账后成功；
  - 争议开立 → 待办；争议败诉 → 扣回；争议胜诉 → 只记状态。
- **`TopUpOwnerIT`**：
  - 答题人不能充值；入驻未通过、被封禁、邮箱未验证的商家被拒；
  - 未完成的充值已有 3 笔 → 422；
  - 商家只看到自己的充值与地址；
  - 经数据视图直接写充值或钱包 → 被拒；
  - 管理员直接调用系统流程 → 403 `PROCESS_INTERNAL`（`ProcessAccessIT` 的清单加入全部新流程）。
- **`ReconcileIT`**（F-06）：
  - 正常时 `OK`、无待办；
  - 以测试 SQL 改坏一个商家的缓存 → `DIFF`、差异行、待办与事件；
  - 运行期间并发结算不产生假差异；
  - 同一计划时刻只运行一次；
  - 账本与只写一次的表没有 UPDATE / DELETE。
- **启动检查**：缺少提供方密钥 → `INBOUND` 报错；订阅类型不在白名单 → 报错；模式与密钥不符 → 报错。
- `ArchitectureTest` 不变，`noDirectNetworkAccess` 覆盖 `wallet` 包。

**Q6b 的集成测试**：
- **`PayoutOnboardIT`**：
  - 开通作业建 Express 账户（幂等键固定）与开通链接 → `PENDING` 与链接；
  - 资料国家不符 → 422；
  - `account.updated` → `ENABLED`；乱序的旧事件被忽略；
  - `deauthorized` → `DEAUTHORIZED`；
  - 同一 `acct_…` 不能给第二个用户。
- **`TakerWalletSyncIT`**：
  - 结算与补发后缓存等于账本；
  - 重复、乱序、并发的同步收敛；
  - 新订阅回填历史奖励。
- **`TransferIT`**（U-53、P-03、5.3-14）：
  - 任务选出达到门槛、已开通、未封禁、无未结束转账的用户 → 发出分录 → 作业（先查 `transfer_group` 再建）→ `SENT` 分录，缓存与账本一致；
  - 手续费参数为 100 时有 4110 行；
  - 开关关闭 → 没有项；
  - 大额 → `REVIEW` 与审批 → 批准后发出；驳回 → `REJECTED`；收款人审批自己的 → `APPROVAL_OWN_REQUEST`；
  - 封禁的用户被跳过。
- **`TransferFailureIT`**：
  - 永久的 4xx → 冲正、`FAILED`，余额回到 2120；
  - `balance_insufficient` 重试后成功；
  - 超时用尽 → `CHECK`，不冲回；核实时桩服务回答"已存在" → `SENT`、只转一次；回答"不存在" → 冲正；
  - 租约过期后再次调用 → 由 `transfer_group` 查到，不重复转账；
  - 连续 3 次失败后不再选取并开待办。
- **`TransferReversedIT`**：撤回 → 借 1110 / 贷 2120，累计金额幂等。
- **`TransferConcurrencyIT`**：
  - 两次任务运行、同步、奖励入账同时进行 → 每人至多一笔未结束的转账（部分唯一索引）；
  - 转出金额不超过当时的账本余额；
  - 没有死锁。
- **`ReconcileIT` 扩展**：用户钱包"连续两次"规则；转账的分录核对。
- `ProcessAccessIT`、`SetupIT`（新参数与规则的提案，再次执行不重复）。

**场景回放**（固定时钟；`actor` 切换；系统流程经 `deliverEvents`、`runJob`、`completeExternalJob`；不调用任何外部服务）：
- `scenarios/qb/topup.yml`（Q6a）：
  1. 商家 A 上架发布，余额为 0；U1 提交后待支付，发布 `FUNDS_SHORT`；
  2. A `QB_TOPUP_START 5000` → `completeExternalJob qb.stripe.checkout-create`（结果：会话号、地址）→ `deliverEvents`；
  3. `inbound stripe checkout.session.completed`（正文引用场景的确定性充值号）→ `deliverEvents` → 到账、补发、发布回到 `LIVE`；
  4. 同一正文再送一次 → `outcome: DUPLICATE`；
  5. `completeExternalJob qb.stripe.payment-fee` → 手续费；
  6. 另一笔充值 → `inbound checkout.session.expired` → `EXPIRED`；
  7. `runJob qb.reconcile` → `OK`；
  8. 查询 `qb.sponsor.wallet`、`qb.app.wallet`、`qb.app.ledger` 与账本。
- `scenarios/qb/payout.yml`（Q6b）：
  1. 开通 → `inbound stripe-connect account.updated`；
  2. 受控参数开启转账（提出、发布）；
  3. `runJob qb.transfer-run` → `completeExternalJob qb.stripe.transfer` → `SENT`；
  4. 大额 → 审批 → 发出；
  5. 失败 → 冲正；
  6. `runJob qb.reconcile`。
- 场景中正文需要引用流程生成的编号（平台缺口 23）。不支持时，以确定性编号（`DeterministicIdGenerator`）写死。

**本地手工验证**（不进 `check`）：`StripeStub` 与 dev 配置，或 Stripe CLI 转发真实测试模式的事件。在实施记录中写下一次端到端的结果。

## 验收标准

- [x] `./gradlew :quizbuks:check` 通过（含 `platformCheck`：`INBOUND`、`EXTERNAL`、`JOB`、`EVENT`、`PROCESS`、`LEDGER` 无错误）；`tools/check-app-paths.sh` 通过。
- [x] M-04：商家以 Stripe Checkout 充值；到账只以签名的 Stripe 通知（或向 Stripe 核实的结果）为准，按服务端记录的金额入账；补发待支付奖励，发布回到进行中。
- [x] 同一笔付款无论通知重复、乱序、并发或与核实同时到达，只入账一次（测试覆盖四层保证）。
- [x] 客户端给出的金额、地址、编号都不能改变入账的金额或对象；不成立的通知只开待办、不入账。
- [x] 签名错误、过期、重放的请求被拒绝，不存储；密钥只来自环境变量，不出现在日志、操作记录与仓库中。
- [x] F-04：每笔充值记录 Stripe 流水号、手续费（借 5110 / 贷 1110）、原币金额与汇率（若有）。
- [x] 退款与争议：冲减商家钱包而不使其为负；不够时开待办；重复事件不重复冲减。
- [x] U-50 – U-52（Q6a：奖励部分；转账在 Q6b）：答题人的余额概览（可用、累计收入、待支付、处理中的转账）与流水（奖励含待支付、转账；按关键字、类型、状态、日期、金额筛选）；商家的钱包概览与充值记录。
- [x] F-06（Q6a 部分；用户钱包与转账在 Q6b）：每日核对商家钱包、待支付、2110 合计、账本平衡、每笔充值的分录（Q6b 另加用户钱包与转账）；有差异时记录、开待办、发布事件；运行期间的结算不产生假差异。
- [x] 开发入账只在 dev 与测试中存在；与正式模式或正式密钥同时开启时启动失败。
- [ ] （Q6b）U-53：用户开通 Stripe Express 收款账户（不保存银行信息），余额达到门槛后每日自动全额转账；大额经审批，收款人不能审批自己；封禁的用户不转账。
- [ ] （Q6b）转账不会重复、不会超过余额：每人至多一笔未结束的转账；不确定的结果先向 Stripe 核实再定论；失败冲正回用户余额，撤回恢复用户余额。
- [ ] 5.3-14：转账门槛、手续费、开关是受控参数（四眼）；手续费为 0 即关闭。
- [x] （Q6a 部分）全部系统流程为 `internal()`；普通表只经流程写入；时态与只写一次的表没有 UPDATE / DELETE；消息三语齐全；文档与部署说明已更新；部署后执行 `QB_SETUP` 并发布新的提案。

## 风险

- **合规**（资金決済法、分析 4.6）：平台代收商家资金并转付给用户。Q6 只在测试模式运行，正式开放转账与正式模式之前必须取得法务意见，结论可能改变充值与转账的设计。`qb.transfer.enabled` 缺省关闭，`livemode` 缺省 false。
- **Stripe 的接口与事件结构**：
  - 以 `Stripe-Version` 固定 API 版本，但 Webhook 正文的版本由 Stripe 后台的端点设置决定，二者不一致会使解析失败（开待办或重试）。部署说明要求两者一致，消费流程对缺字段的正文按"不成立"处理。
  - 不用官方 SDK，表单与解析由应用维护，覆盖面以测试为准。
- **转账的资金可用性**：Stripe 的到账要几天后才变为可用余额，测试模式要用专门的测试卡才能立即可用。可用余额不足时转账反复重试，用尽后失败冲正。上线前要定资金调度。
- **不确定的转账**：网络中断时只能事后按 `transfer_group` 核实。处于 `CHECK` 的转账在核实前占着用户的"未结束"名额，最长约 1 小时。
- **跨境**：Express 账户限于部署所在国（JP）。其他国家的答题人不能开通，奖励只能累积在平台（U-53 对他们不可用）。需求方须知悉（Q18）。
- **退款与争议的缺口**：Kudos 已被花掉时无法从商家钱包扣回，只能开待办由财务线下处理，账面上 1110 与 Stripe 实际余额出现差额，直到 F-05 才自动对出。
- **核对的规模**：按当事人汇总 2110 / 2120 的全量查询随账本增长而变慢，读时态的分录表还要取当前版本。Q10 前评估，必要时请平台提供维度余额快照（平台缺口 25）。
- **作业的请求人上限**：系统发起的作业类型以 10 000 为上限。单日转账超过 1 万笔时，超出的项失败、下一次运行再做（平台缺口 19）。
- **事件量**：每笔奖励多一次用户钱包同步。100 万用户的量级下，同步的投递量与结算相同（Q10 评估）；新订阅回填历史事件时，一次性积压所有历史奖励。
- **页面等待**：支付页地址异步就绪（约 1–3 秒），商家后台要轮询（Q6-2 的代价）。

## 预估

| 工作 | 天数 |
|---|---|
| Q6a：外部服务、`StripeApi`（表单、错误、解析）、模式检查、提供方配置、本地桩服务 | 1 |
| Q6a：充值实体与迁移、开始、创建会话的作业、完成与失败 | 1 |
| Q6a：到账确认（核对、入账步骤、待办）、过期、过期核实任务与作业 | 1.5 |
| Q6a：手续费作业、退款与争议 | 1 |
| Q6a：模板（商家钱包与充值、答题人余额与流水、管理）、每日核对与结果实体 | 1 |
| Q6a：测试、场景、文档 | 1.5 |
| **Q6a 合计** | **约 6–7** |
| Q6b：收款账户（实体、开通作业、两个账户事件） | 1 |
| Q6b：用户钱包同步 | 0.5 |
| Q6b：转账（选取、创建、审批、发出作业、结果、核实、撤回）、打款到银行 | 2 |
| Q6b：模板、核对扩展、测试、场景、文档 | 1.5 |
| **Q6b 合计** | **约 5** |
| **合计（不含页面）** | **约 11–12** |

- 页面（充值、钱包、流水、开通收款、管理列表）在 15a、16c 之后另做，约 3–4 天。
- `03-plan.md` 的 Q6 估了 7–9 天；按本计划，后端约 11–12 天，比原估多出的部分在：只入账一次与转账的不确定结果的核实、退款与争议、全部 Stripe 调用改为作业、自写 Stripe 客户端。
- 若压缩，可延后 D-Q6-13（打款到银行，约 0.25 天）与争议（约 0.25 天）。

## 平台缺口（接 Q5 的编号；按 `backend/quizbuks/CLAUDE.md` 第 2 节在 `1.2/platform` 上补）

| 编号 | 缺口 | 是否阻塞 Q6 | 本阶段的绕行 / 建议 |
|---|---|---|---|
| 18 | `LoadInboundEvent` 一个步骤只认一个事件类型 | 否 | 每个 Stripe 类型一个消费流程，共用步骤。建议 `LoadInboundEvent.ofAny(提供方, 类型集合, …)` |
| 19 | 异步外部作业的"每请求人未结束上限"同样约束系统身份（至多 10 000） | 否（单日超过 1 万笔转账时影响） | 系统发起的类型设 10 000。建议系统身份不受此限，或按类型另设系统上限 |
| 20 | 外部服务只有 JDK HTTP，不能用 Stripe 官方 SDK（主机白名单约束不了 SDK） | 否（D38 的有意设计） | 应用自写 `StripeApi`（表单编码、错误分类、解析），约 0.5 天。以后若平台提供"受白名单约束的 HTTP 客户端适配"（供 SDK 注入），可改用 SDK |
| 21 | 提交后步骤的重试不持久化 | 否 | 必须最终发生的 Stripe 调用一律用异步外部作业 |
| 22 | 入站事件与 Outbox 不保证顺序 | 否 | 应用自行处理：退款早于到账先重试；账户状态按事件的 `created` 只取更新的；累计金额幂等。建议可选的"按键顺序投递" |
| 23 | 场景回放的 `inbound` 正文能否引用前面步骤的输出（流程生成的编号）待核实 | 否 | 不支持时以确定性编号写死。建议 `inbound.body` 支持场景变量 |
| 24 | 逐项任务的选取参数函数读不了业务参数 | 否 | 选取模板以 `timeSlice` 连接 `SysParam` 读门槛与开关，每项流程再以 `LoadParams` 判断一次。建议 `JobItems.template` 可声明参数键，由平台以计划时刻读出后传入 |
| 25 | 没有按维度的余额快照，全量的按当事人汇总代价随账本增长 | 否（Q10 前评估） | 核对模板只返回差异行，用户钱包走缓存。建议平台提供"科目 × 维度"的余额快照（由过账同步维护）或按时段增量的汇总 |
| 26 | 没有"同一来源、同一用途只过账一次"的平台约束 | 否 | 应用以状态机加唯一的交易号列兜底。建议 `LEDGER_POST` 可选 `uniqueKey`，以唯一索引保证 |
| 27（小） | 入站提供方不能声明"只接受某一模式（`livemode`）" | 否 | 消费流程核对，不符即待办。可选：提供方配置 `livemode: true|false|any`，在入口即忽略另一模式的事件 |

不需要平台新能力的点：
- 入站验签、去重、存档、限流；外部服务与异步外部作业（重试、幂等键、完成回调）；逐项任务；`internal()`；
- 账本的过账、冲正、维度与来源；审批（准备人 = 收款人即挡住自审）；受控参数；待办；保留策略。

## 待确认问题

| 编号 | 问题 | 本计划的缺省 |
|---|---|---|
| Q6-1 | 拆分与顺序 | Q6a（充值、核对）先做，Q6b（收款与转账）随后；Q6b 正式开放前须有法务意见，代码不等 |
| Q6-2 | Stripe 调用的方式 | 全部经异步外部作业（不在事务中等外部调用；可靠重试；场景不需要网络）；支付页地址约 1–3 秒后就绪，页面轮询。另一种：创建支付页在阻塞步骤中同步返回 |
| Q6-3 | 充值的前提与限额 | 入驻已通过、未封禁、邮箱已验证；单笔 1 000 – 1 000 000 Kudos（受控参数）；未完成的充值至多 3 笔 |
| Q6-4 | 支付方式 | 只用卡（同步到账）；便利店、银行转账等异步方式以后加 `async_payment_succeeded` |
| Q6-5 | 外币（M-04"任意货币"） | 会话币种固定 JPY；顾客经 Stripe 本地货币显示付外币时只记录原币信息，账本只有 JPY |
| Q6-6 | 充值后的补发 | 发布 `qb.sponsor.settle-pending`，在另一个事务中补发（同开发入账）；另一种：确认流程以子流程补发 |
| Q6-7 | 退款 | 不提供商家退款；Stripe 后台发生的退款：余额够则冲减商家钱包，不够则开待办、不入账 |
| Q6-8 | 争议 | 开立时开待办；败诉时按退款处理；胜诉只记状态 |
| Q6-9 | 测试与正式模式 | 配置 `quizbuks.stripe.livemode`（缺省 false）；密钥前缀须与之相符（启动检查）；模式不符的事件不入账 |
| Q6-10 | Stripe 手续费 | 到账后由作业取 balance transaction，记 5110 / 1110；取不到即待办 |
| Q6-11 | 开发入账与人工入账 | 开发入账保留（只在 dev 与测试），正式模式或正式密钥时启动失败；正式的"人工入账"（银行汇款，四眼）不做，需要时另提 |
| Q6-12 | 核对的范围 | 每日：内部一致性（钱包、待支付、科目合计、充值与转账的分录）；与 Stripe 只核实卡住的充值与转账；逐笔的三方对账（F-05）延后；有差异即待办，Q9 的导出据此停止 |
| Q6-13 | 收款方式 | Stripe Connect Express，部署所在国（JP）的个人账户；资料国家不同即不能开通 |
| Q6-14 | 自动转账的规则 | 每日一次、全额；门槛 1 000、超过 50 000 人工审核、手续费 0（均为受控参数）；`qb.transfer.enabled` 缺省关闭；用户不能手动"立即提现" |
| Q6-15 | 转账结果不明 | 不冲回，进入 `CHECK`，由核实作业按 `transfer_group` 查询后定论；连续 3 次失败后停止并开待办 |
| Q6-16 | 用户钱包缓存 | 由事件驱动、按账本重算（不在结算事务中取用户钱包锁）；核对允许落后，只有连续两次不符才算差异 |
| Q6-17 | Stripe 打款到银行 | Q6b 记 1120 / 1110（`payout.paid`）；或延后到 F-05 |
| Q6-18 | Stripe 原样事件的保留 | 7 年（`InboundEvent` 的保留策略，与会计凭证相同）；只有财务管理员能读 |
| Q6-19 | 参考汇率（"约合 X USD"） | 延后（页面阶段或 Q9）；Q6 只显示 Kudos |
| Q6-20 | `QbTopUp`、`QbTransfer` 的存储 | 普通表（按后写入的 Stripe 编号查找、状态频繁变化；审计照常）；设计 §3.4 写的是时态，随本阶段修改 |
| Q6-21 | 页面 | 商家充值与钱包、App 余额与流水与开通收款、管理列表在 15a、16c 之后另做（约 3–4 天） |

## 确认

2026-10-12：计划与待确认问题 Q6-1 – Q6-21 全部按缺省处理：先做 Q6a（充值、退款与争议、余额与流水模板、每日核对），Q6b（收款账户与自动转账）随后；Q6b 正式开放转账与正式模式之前须取得法务意见。

## 实施记录（Q6a，2026-10-12）

分支 `1.2/quizbuks-6a-topup`（从 `1.2/quizbuks` 拉出）。Q6a 是本计划中没有标为 Q6b 的全部内容；收款账户、用户钱包、转账与打款在 Q6b。

**Stripe 事实的核实**（按 Stripe 的 API 参考与文档，开工时对照；上线前在 Stripe 的变更日志中再确认一次）：
- 日元是零小数位币种，`amount` 即日元；Checkout 的 `expires_at` 须在创建后 30 分钟到 24 小时之间——成立。配置的有效期限为 40 分钟到 23 小时（作业可能晚几分钟才创建会话）。
- 卡支付时 `checkout.session.completed` 的 `payment_status = paid`（延迟支付方式为 `unpaid`，之后才有 `async_payment_succeeded`）；过期发 `checkout.session.expired`——成立。
- `charge.refunded` 的 `amount_refunded` 是累计值——成立。争议对象有 `payment_intent`、`amount`、`status`（`won` / `lost` / `warning_closed` 等）。
- Connect 账户的事件只发到 Connect 端点、签名密钥不同；转账是同步 API、可按 `transfer_group` 列出——成立（Q6b 使用）。
- 幂等键至少保留 24 小时；`Stripe-Version` 固定请求的版本，事件正文的版本由端点决定——成立。
- **补充（影响实现）**：本地货币显示（Adaptive Pricing）下会话的结构**随 API 版本不同**：`2025-03-31.basil` 之前会话的 `currency` / `amount_total` 是顾客的币种，
  原币与汇率在 `currency_conversion`；从该版本起会话保持集成方的币种，顾客的金额在 `presentment_details`。`StripeObjects.session` 两种都读成同一组字段
  （核对用日元金额，原币金额、币种与汇率写入充值行）。API 版本缺省 `2025-03-31.basil`（`quizbuks.stripe.api-version`），部署说明要求端点版本与之相同。
- 退款与争议以扣款的币种计：顾客以外币付款时 `amount_refunded` 是外币，Q6a 不换算，开待办（`CURRENCY`）由财务处理。

**与计划的出入**（都不改需求）：
- 作业类型 `qb.stripe.checkout-create` 的幂等键用**充值自己的** `qb-checkout-<充值号>`（不随作业变化），任何作业、任何尝试都只建一个会话。作业的结果类型就是解析后的会话
  （`StripeObjects.CheckoutSession`），核实作业同样。
- 充值行不存 `openJobId`（插入时作业号尚未生成；作业可按引用 = 充值号查到）；多存 `checkRequestedAt`（6 小时内不重复向 Stripe 核实）、`disputeId`、`disputeAmount`、
  `disputeClawback`（争议败诉的扣回与累计退款分开记，累计退款的幂等算法不受争议影响）。`refundedAmount` 是**已扣回**的退款，`refundShortfall` 是未能扣回的部分。
- `TopUpChecks` 的原因多一个 `NO_PAYMENT_INTENT`；作业的不符与失败另有 `OPEN_MISMATCH`、`CHECK_FAILED`、`FEE_MISMATCH`、`FEE_FAILED`；退款与争议的原因在
  `RefundMath.REASONS`。待办标题每个原因一条消息（`task.qb.topup-check.<原因>`、`task.qb.refund-check.<原因>`），读者以自己的语言看到原因。
- 到账、退款的**记账时间是事件正文的 `created`**（付款发生的时间；晚于签名时间的不信，取签名时间），不是签名时间：Stripe 重试投递时签名时间可能晚几天。
  核实作业入账用运行时间。手续费的记账时间是到账时间。
- 过期核实只选 `OPEN` 且支付页过期超过 1 小时的充值；`OPENING` 卡住的不选（没有会话号可查，作业有自己的重试与失败流程）。为免永久占位，
  "未完成至多 3 笔"只把创建 1 小时内的 `OPENING` 算在内。
- 退款早于到账的重试窗口是 **30 分钟**（不是计划的 1 小时）：Outbox 缺省 10 次、指数退避，最后一次约在第 43 分钟，1 小时的窗口会让事件进入死信而永远不开待办。
- 每日核对的检查分为 `SPONSOR_WALLET`、`SPONSOR_PENDING_COUNT`、`SPONSOR_PENDING_AMOUNT`、`TOTAL_2110`、`LEDGER_ZERO`、`TOPUP_LEDGER`（到账金额）、
  `TOPUP_ARRIVALS`（到账笔数）、`TOPUP_REFUNDS`（扣回）、`TOPUP_FEE`（手续费）；差异项至多记 1 000 条（计数为全部）。"连续两次"的规则只用于 Q6b 的用户钱包，未实现。
- 商家的累计支出取 2110 中带发布维度的借方（奖励行带发布，扣回行不带），不连接时态的交易表。
- 答题人与商家的余额模板经新的只读数据视图 `urn:jabiz:dataset:taker:LedgerEntry` / `sponsor:LedgerEntry`（范围 `dimension1` = 调用者）读本人的分录，
  商家钱包经 `urn:jabiz:dataset:sponsor:QbWallet`。
- `QbRoles`：`inbound.read` 只给财务管理员（不经超级管理员继承：原样事件可能含付款人信息）；`external-job.read` 给财务（超级管理员随之继承）。
  `qb.payout.system` 留到 Q6b 再加。
- `DevCreditCheck` 并入 `StripeModeCheck`（同时检查密钥前缀与模式、支付页地址前缀、API 版本格式、有效期；缺少商家后台地址只告警，充值时 422 `QB_TOPUP_UNAVAILABLE`）。
- **compose 不再缺省可启动**：`STRIPE_WEBHOOK_SECRET` 与 `STRIPE_API_KEY` 须在 `deploy/quizbuks/.env` 中给出（仓库中不放任何签名密钥：知道它即可伪造到账通知）。
  `platformCheck` 在 `backend/quizbuks/build.gradle.kts` 中给一次性的签名密钥，以便检查提供方与订阅的连接。
- 场景 `scenarios/qb/topup.yml`：正文以场景变量引用流程生成的充值号（平台缺口 23 不存在：`inbound.body` 支持变量）。`expect` 步骤不能指定操作人，
  所以商家与答题人自己的模板（按调用者限定）由 `TopUpIT` 覆盖，场景读管理员的模板。其他场景因新的两个受控参数改为发布 10 个提案，确定性编号顺延，快照随之更新。

**平台缺口**（接上表，未改平台目录）：
- 28：`PlatformCheckLauncher` 为签名密钥等给一次性值，但不为入站提供方的密钥给；应用只好在自己的 `build.gradle.kts` 中设环境变量。建议启动器为配置了 `secrets` 占位符的提供方给一次性值。
- 29：场景的 `expect` 没有 `actor`，不能以某个用户读按调用者限定的模板。
- 18、19、21、22、24、25、26、27 照计划绕行（26：唯一的交易号列与状态机兜底；27：消费流程核对模式）。

**测试**（`backend/` 下，本地 PostgreSQL：`JABIZ_TEST_DB_URL=jdbc:postgresql://localhost:5432/jabiz_test`）：
- 单元：`StripeFormTest`、`CheckoutRequestTest`、`StripeErrorsTest`、`TopUpChecksTest`（逐项不成立的矩阵、两种外币结构、jqwik：不等于记录的金额从不入账）、
  `RefundMathTest`（累计、重复、乱序，jqwik）、`ReconChecksTest`、`StripeModeCheckTest`（含 `rk_live_`、锁名）、`QbRolesTest`、`MessagesTest`、`ArchitectureTest`
  （加 `noDirectNetworkAccess`、`noPlatformInternals`、`handlersStayOutOfTheDatabase`）。
- 集成（`StubHttpServer` 充当 Stripe，无真实网络）：`TopUpIT`、`TopUpIdempotencyIT`（重复事件、同一会话两个事件、通知与核实并发、4 路并发投递与并发请求、
  篡改 / 过期 / 未签名 / 超大请求、逐项不成立、数据库约束）、`TopUpExpiryIT`、`TopUpFeeIT`、`TopUpRefundIT`、`TopUpOwnerIT`、`ReconcileIT`、`StripeStartupChecksIT`、
  `ProcessAccessIT`（加入全部新的系统流程）。
- 场景回放 `scenarios/qb/topup.yml`（快照已核对：到账 5 000、补发 100、手续费 180、第二笔过期、核对 OK、钱包 4 900）。
- `./gradlew :quizbuks:check --max-workers=2`（含 `platformCheck`，0 个错误）：359 个测试中只有 `PlayConcurrencyIT.noStartGetsInAfterAPause` 失败一次
  （Jackson 首次并发序列化的 500，Q5 记录过的测试现象，与本阶段无关），单独重跑通过。`tools/check-app-paths.sh origin/1.2/platform` 通过。

**本地端到端**（2026-10-12，不进 `check`）：`StripeStub` + `bootRun`（`dev`，单独的数据库）：`QB_SETUP` 与提案发布 → 商家入驻 → `QB_TOPUP_START 3000` →
执行器约 1 秒后建好会话、充值 `OPEN` → 桩服务支付页"Pay"（303 跳回 `…/sponsor/wallet/topups/<编号>?checkout=paid`）→ 签名的 `checkout.session.completed`
（200）→ `PAID`、手续费 108（3.6 %）入账、商家钱包 3 000。日志、操作记录的 `input_summary` 与作业输入中都没有密钥。

**自查（正确性与安全）后的修正**：
- 支付页的核对另查 `mode = payment` 与 `expires_at` 的范围（超出 `Instant` 的值使流程异常而不是开待办）。
- 金额字段只接受 JSON 数字（以文本写的金额不再被当作数字）；只有汇率可以是文本。
- "未完成至多 3 笔"不再永久计入卡住的 `OPENING`（见上）。
- 测试夹具：Stripe 桩服务在全部充值测试类之间共用（它们共用缓存的应用上下文），不在某个类结束时关闭。

**已知问题**：
- 顾客以外币付款时的退款、争议（扣款以外币计）只开待办，不自动扣回。
- 余额不够扣回的退款与败诉争议留在 Stripe 余额与 1110 的差额中，直到 F-05 的逐笔对账；`refundShortfall` 记下未扣回的退款，争议的只记在待办与 `checkReason`。
- `OPENING` 卡住（作业完成而完成流程进入死信）的充值不自动处理；它一小时后不再占用"未完成"的名额。
- 每日核对的全量汇总随账本增长变慢（平台缺口 25），Q10 前评估。
- Q6a 不含页面（Q6 页面）与 Q6b。
