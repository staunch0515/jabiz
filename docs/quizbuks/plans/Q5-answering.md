# Q5 答题（后端，约 9–10 天）— 实施计划

分支：`1.2/quizbuks-5-play`（从 `1.2/quizbuks` 拉出，PR 合回 `1.2/quizbuks`）。只改 `.jabiz-app-paths` 中的路径。
本阶段较大，拆成两个 PR：
- **Q5a**（`1.2/quizbuks-5a-play`）：除自动提交任务以外的全部内容；
- **Q5b**（`1.2/quizbuks-5b-expire`）：自动提交任务，以及把 Q4 的 `qb.publication-tick` 改为逐项事务。Q5b 依赖平台阶段 16k（平台缺口 6，见 D-Q5-9）。

本阶段只做后端。内容包括：实体、数据视图、流程、定时任务、事件订阅、结算分录、SQL 模板、迁移、三语消息和测试。

- **页面延后**：在平台 15a、16c 合入后另做，约 4 天。包括 App 的首页卡片与筛选（U-10 – U-14）、详情（U-20 – U-23）、一页一题与倒计时（U-30 – U-32）、结果页（U-33、U-34）、历史与继续答题（U-40 – U-42）。PWA 外壳由 16c 提供。
- **只累计余额，不收不付**：
  - 充值（Checkout 与通知）、自动转账、余额概览与流水页面（U-50 – U-53）、每日核对（F-06）都由 Q6 做。
  - Q5 只做三件事：结算时从商家钱包记到用户的应付奖励；余额不足时记为待支付；有了余额后按提交顺序补发。
  - Q5 补发的触发只有一个仅供开发与测试的入账流程（D-Q5-8）。Q6 的 `QB_TOPUP_CONFIRM` 改为以子流程调用同一个 `QB_PENDING_SETTLE`。
- **站内信不在本阶段**：U-34 的"同步生成消息"由 Q7 的站内信订阅本阶段发布的事件实现（`qb.reward.settled` / `qb.reward.pending`）。新订阅会收到历史事件（平台 11 §2.3），所以先上线的结算也会补上消息。
- **依赖 Q2（账户）**【待确认 Q5-1】：
  - U-11 的"覆盖用户所在地区"需要用户的国家（`QbProfile.countryCode`）；
  - 5.3-11 需要账户状态（正常 / 封禁）；
  - U-10、U-13、U-20 需要发布人的名称、头像与国家（`QbSponsor`、`QbProfile`）。

  平台 16a、16b 已合入，Q2 已无平台依赖。缺省顺序为 Q2 后端 → Q5。

已核实的平台事实（`origin/1.2/platform`）：
- **账本**（11 §1、D24）：
  - 过账只能以子流程 `CallProcess.of("LEDGER_POST", …)` 进行，与父流程同一事务。子流程过账不检查调用方的 `ledger.post`。
  - 至少 2 行、借贷平衡、金额为正（零奖励不能过账）、全部违规一次报告。提交时另有数据库触发器兜底（`JZ002`）。
  - 来源单据 `sourceEntity` / `sourceId` 经该实体的默认视图核对存在（普通实体也可以）。
  - 维度取值经来源实体的默认视图核对。身份字段只能是时态实体的主键。
  - `ledger_entry_version (dimension_1)` 与 `ledger_transaction_version (source_entity, source_id)` 都有索引，所以按用户、按答题会话查分录不随账本增长而变慢。
  - 交易与分录是只写一次的时态实体，交易发布变更事件。更正只能冲正。
- **余额不能由账本挡住**：平台没有"维度余额下限"（G8 已取消，设计 §3.4、§4.3）。"钱包余额不得为负"由应用在钱包锁内判断，再加 `qb_wallet.balance >= 0` 的检查约束兜底。
- **命名锁**（06 §4.1）：
  - `HoldLock` 持有到根流程事务结束。名称为 null 即不取。
  - 等待上限是全局的 `jabiz.process.lock-timeout`（30 秒），超时或死锁为 409。
  - **等待中的事务占着一个数据库连接**。
- **定时任务**（11 §4）：
  - 同一计划时刻至多执行一次，错过的时刻不补跑。
  - 流程与其 `CallProcess.forEach` 子流程同一事务，任一项失败即整批回滚（平台缺口 6）。
- **事件**（11 §2.3）：
  - 每个（消费者，事件）在自己的事务中执行，只处理一次；失败按退避重试，至多 10 次。
  - 每个消费者每轮至多 `batch-size`（缺省 20）个，轮询间隔 1 秒。
- **模板**（05、19 §2）：
  - 时态实体的 `{{Entity}}` 展开为 `DISTINCT ON` 的当前版本子查询。只有不可变字段的条件下推，连接条件不下推（04 §5.1、§5.3）。
  - 平台不做"当前快照投影表"，跨大量实例按可变字段的查询由应用维护自己的普通表（D29 第 5 条）。
  - 已认证的模板没有响应缓存（`cacheSeconds` 只用于公开模板）。
  - 模板正文取不到"现在"，只能用参数。
- **已验证邮箱**：流程声明 `requiresVerifiedEmail()`（16b-1）。未激活用户（5.3-11 "待激活用户不可答题领奖"）因此由平台挡住。
- **受控参数**（D40）：抽成比例 `qb.creator-fee-rate` 已在 `QbParams.CONTROLLED` 中，以 `LoadParams` 按业务时间读取。参数未发布时报 422 `PARAM_NOT_FOUND`。
- **`processOnly` 字段**：照常可读，数据视图 API 与通用实体流程不能写。

## 设计决定（待确认，见文末问题）

### D-Q5-1 答题会话与一人一次【待确认 Q5-7、Q5-11、Q5-14】

- `QbAttempt` 是普通表（P）：进行中频繁保存进度，结束后由流程拒绝一切修改，审计照常。
  - `(user_id, publication_id)` 唯一，即 5.3-6 的一人一次，数据库兜底。
  - 冗余存 `sponsorId`、`versionId`，供补发队列与评分使用。
- **状态**：`IN_PROGRESS` →
  - `SUBMITTED`：本人在时限内提交；
  - `EXPIRED`：过了时限，由任务按已保存的答案自动提交。

  两者都是"已完成"，都评分，奖励规则相同【待确认 Q5-11】。
- **时限**（5.3-5、U-31）：
  - `deadline = startedAt + timeLimitSec`，时间来自 `Clock`。
  - 提交时 `now > deadline + 5 秒`（`PlayLimits.SUBMIT_GRACE`，网络延迟的宽限）即 422 `QB_ATTEMPT_EXPIRED`。这次提交带来的答案作废，之后由任务按已保存的答案自动提交【待确认 Q5-7】。
  - `deadline` 不受发布 `endAt` 限制：结束前开始的会话可以在结束后完成（需求 5.3-2）。
- **答案的存放**【待确认 Q5-14】：不建 `QbAttemptAnswer` 表，改为 `qb_attempt` 上的三列规范 JSON 文本：
  - `optionOrder`：每题打乱后的选项号；
  - `answers`：每题已选的选项号；
  - `result`：提交后才写，含每题的已选、正确选项与得分。

  每次保存只写一行，提交时少写 N 行。Q9 的题目分析用 `jsonb_array_elements` 统计（普通索引不需要按题）。
- **选项打乱**【缺省 Q8】：
  - 只打乱选项，题目顺序不变。
  - 打乱结果由纯 Java 的 `OptionShuffle.order(attemptId, questions)` 以会话编号为种子确定性生成，存进 `optionOrder`。
  - 答案始终用选项号（版本中从 1 起的 `no`）表示，与显示顺序无关。
- **继续答题**（U-41）：`QB_ATTEMPT_START` 对本人未过时限的进行中会话直接返回该会话（题目、打乱顺序、已选答案、当前题号、`deadline` 与服务器时间），不取锁、不改计数。已完成的 422 `QB_ATTEMPT_ALREADY_TAKEN`（U-23 "已参加过则不可点"）。
- **不能答自己的发布**：发布的 `sponsorId` 等于操作人时 422 `QB_ATTEMPT_OWN_PUBLICATION`。同一用户可以既是商家又是答题人（G1）【待确认 Q5-15】。

### D-Q5-2 评分、档位与正确答案的保护【缺省 Q8】

- **纯 Java 的 `Scoring.score(snapshot, answers)`**：
  - 每题的已选集合等于正确集合才得该题分数，否则 0 分。不做部分得分。多个正确选项即多选题。
  - 未答的题得 0 分。
- **合格**：`score ≥ passScore`，只用于显示（5.3-7）。
- **奖励**：`TierPick.of(tiers, score)` 取包含该分数的档位。档位提交时已由 `TierCoverage` 保证覆盖 0 到满分，所以总有一档，否则 500 并告警（不应发生）。
- **正确答案只在完成后出现**（设计 §9）：
  - 答题人读不到 `QbQuizVersion`：它的默认视图要求 `qb.admin.quiz.read`。
  - 开始与继续的输出只含题干、图片、分值与选项，不含 `correct`。
  - 详情模板只从快照中取简介与资料，不取 `questions`。
  - `result` 列在提交时才写入。
  - 测试逐一扫描答题人能得到的所有输出（流程输出、模板结果、数据视图），进行中的会话里不得出现任何正确答案。

### D-Q5-3 名额、预算与"接受新的答题"【待确认 Q5-5】

计数行 `QbPublicationCounter` 只在发布锁内更新：
- `starts`：开始数；
- `completions`：完成数；
- `passes`：合格数；
- `winners`：奖励大于 0 的人数；
- `reserved`：进行中会话占用的预算；
- `spent`：已承诺的奖励，含待支付。

| 时机 | 条件 / 动作 |
|---|---|
| 开始（发布锁内） | 依次检查以下条件，任一不满足即拒绝：<ul><li>`PublicationRules.acceptsNewAttempts`（`LIVE` 且在时间内），否则 `QB_PUBLICATION_NOT_OPEN`；</li><li>预算：`spent + reserved + maxReward ≤ budgetCap`（有上限时），否则 `QB_PUBLICATION_BUDGET_FULL`；</li><li>名额：`winners + 进行中 < maxWinners`（有上限时，进行中 = `starts − completions`），否则 `QB_PUBLICATION_QUOTA_FULL`；</li><li>商家可支付（D-Q5-5）。</li></ul>全部通过后：`starts + 1`、`reserved + maxReward` |
| 完成（发布锁内） | `completions + 1`；合格则 `passes + 1`；获奖则 `winners + 1`；`reserved − maxReward`；`spent + reward` |
| 完成后：名额用完 | `maxWinners` 有值且 `winners ≥ maxWinners` → `ENDED`，`endReason = QUOTA` |
| 完成后：预算用完 | `budgetCap` 有值且 `budgetCap − spent < maxReward`（以后不可能再接受任何开始）→ `ENDED`，`endReason = BUDGET` |

- 进行中的会话**预占一个名额**【待确认 Q5-5】，所以获奖人数永远不超过 `maxWinners`，与预算的预占（每个进行中的会话占 `maxReward`）同理。
  - 代价是：接近上限时，未完成的会话会暂时挡住新的开始，直到它们完成或超时。
  - 进入 `ENDED` 时进行中的会话照常完成，不会超额：预占保证了这一点。
- `PAUSED`、`FUNDS_SHORT`、`ENDED` 都不接受新的开始，但已开始的会话照常保存与提交（5.3-2）。
- Q4 的迁移已声明 `LIVE → ENDED`，结束时写 `endedAt`、`endReason`，并把日程行的 `next_due_at` 置空。

### D-Q5-4 结算与账本【待确认 Q5-10、Q5-12】

奖励大于 0 且可以支付时，在提交的同一事务中以子流程 `LEDGER_POST` 过账。

- `bookingTime`：完成时间（`opTime`）。
- `description`：`Quiz reward`。
- `reference`：会话编号。
- `sourceEntity = "QbAttempt"`、`sourceId`：会话编号（5.3-9 "关联来源事件"）。
- 分录：

  | 行 | 科目 | 方向 | 金额 | 维度 |
  |---|---|---|---|---|
  | 1 | 2110 商家预收款 | 借 | 奖励 | `party` = 商家，`publication` = 发布 |
  | 2 | 2120 应付用户奖励 | 贷 | 奖励 − 抽成 | `party` = 用户，`publication` = 发布 |
  | 3 | 4110 平台服务收入 | 贷 | 抽成（为 0 时不出这一行） | `publication` = 发布 |

- **抽成**（5.3-13）：
  - 按**结算时**生效的受控参数 `qb.creator-fee-rate` 计算（补发时用补发时的比例）。
  - 由纯 Java 的 `RewardSplit` 计算，向下取整到 0 位小数（不足 1 Kudos 的部分归用户）。
  - 当前比例为 0，所以只有两行【待确认 Q5-10】。
- **新维度 `publication`**【待确认 Q5-12】：`LedgerDimension.define(2, "publication", d -> d.entity("QbPublication", "publicationId"))`。
  - 有了它，商家的花费（M-50 – M-52）与汇总凭证（F-02）可以直接按发布从账本汇总，不依赖应用表。
  - 主键不可变，启动检查不告警。
- **零奖励**：不过账，`rewardStatus = NONE`。
- **会话上的记录**：`ledgerTransactionId`（子流程的输出）、`rewardPaidAt`、`fee`。
- **事件**：在同一事务内 `PublishEvent` 发布 `qb.reward.settled` 或 `qb.reward.pending`。
  - 载荷 `{attemptId, userId, publicationId, reward}`，不含答案。
  - 供 Q7 的站内信使用。
- **不变式**（测试断言）：
  - 每个商家钱包的余额缓存，等于账本 2110 上该当事人的贷减借；
  - 每个用户 2120 的余额，等于他已支付奖励（扣除抽成）之和；
  - 全部可过账科目的余额之和为 0。

### D-Q5-5 钱包、待支付与"预算不足"【待确认 Q5-3、Q5-4、Q5-6、Q5-13】

- **`QbWallet` 在 Q5 只为商家建**【待确认 Q5-3】。表结构按设计 §3.4 的普通表：`walletId`；`ownerId` + `kind` 唯一；`balance`；`pendingCount` / `pendingAmount`；`pendingOut` 留给 Q6。
  - 商家余额必须有缓存：锁内按账本汇总一个大商家的全部分录太慢。
  - 用户的 2120 余额按 `dimension_1` 索引汇总本人分录即可，行数很少。
  - 用户的钱包只增不减（Q5 只入账），入账不会使余额为负，所以不需要用户钱包锁。Q6 的转账再定用户钱包的锁与缓存。
  - 钱包行在第一次写入时在钱包锁内建立：第一次入账，或第一次记待支付。没有行即余额为 0。
- **锁名** `qb.wallet:SPONSOR:<userId>`。种类写进名称，同一用户的商家钱包与将来的用户钱包不会混淆。
- **结算时判断**（钱包锁内）：
  - 可以支付，当且仅当 `pendingCount = 0` 且 `balance ≥ reward`：`balance − reward`，过账，`rewardStatus = PAID`。
  - 否则 `rewardStatus = PENDING`，`pendingCount + 1`，`pendingAmount + reward`，**不入账**（需求 7.1）。发布若是 `LIVE` 则进入 `FUNDS_SHORT`（已持有发布锁）。
  - 结果页提示"商家预算已用完，等待商户充值"（U-34；消息码 `QB_REWARD_PENDING`，三语）。
- **严格按提交顺序**【待确认 Q5-6】：队列非空时，即使余额够付这一笔，新的奖励也排在队尾记为待支付（5.3-8 "按提交顺序补发"），不插队。
- **补发** `QB_PENDING_SETTLE {sponsorId}`（内部流程，系统身份）：
  1. 只取钱包锁；
  2. 按 `submittedAt`、`attemptId` 顺序取至多 50 条待支付（`PlayLimits.SETTLE_BATCH`）；
  3. 逐条在余额够付时过账（`CallProcess.forEach("LEDGER_POST", …)`），置 `PAID` 并减少待支付计数；
  4. 遇到第一条付不起的就停止（不跳过，保持顺序）。

  后续：
  - 还有待支付且余额仍够付下一条时，发布 `qb.pending-settle.more`，由订阅 `qb.pending-settle` 再执行一次。下一批在新的事务中，不长时间占着钱包锁。
  - 队列清空时，发布 `qb.sponsor.funds-restored`。
- **恢复** `QB_PUBLICATION_FUNDS_RESTORED {sponsorId}`（订阅 `qb.funds-restored`，系统身份）：
  1. 经卡片表（D-Q5-6）按 `sponsor_id` 找出 `FUNDS_SHORT` 的发布；
  2. 按发布编号升序逐个取发布锁（与 Q4 的定时任务同样的升序，不取钱包锁，所以没有环）；
  3. 按 `PublicationRules.resumeTo` 回到 `LIVE`，或已过结束时间的直接 `ENDED`。

  "补发完全部待支付奖励时回到进行中"（设计 §5.1）由此实现。
- **为什么补发与恢复分开**：恢复要取发布锁，而补发持有钱包锁。锁序是 发布 → 钱包，持有钱包锁再取发布锁会违反锁序，所以恢复放在另一个事务里。
- **开始时的余额检查**【待确认 Q5-4】：
  - 在发布锁内**不取钱包锁**读一次商家钱包（READ COMMITTED）：`pendingCount = 0` 且 `balance ≥ maxReward`，否则 422 `QB_SPONSOR_FUNDS_SHORT`。
  - 这是软检查：不在钱包上预占，并发开始的人可能多于余额能付的，多出的人完成后记为待支付。需求允许这种情况（5.3-8）。
  - 预占要求开始也取钱包锁，会使商家锁上的流量翻倍（见 D-Q5-10）。
- **`FUNDS_SHORT` 的暂停与恢复**（Q4 留给 Q5）【待确认 Q5-13】：
  - `FUNDS_SHORT` 可以暂停（迁移已声明）；
  - 恢复时照旧按时间去 `LIVE`、`SCHEDULED` 或 `ENDED`。若仍付不起，下一次付不起的结算会再次置为 `FUNDS_SHORT`，首页按钱包条件隐藏它。
  - 已结束的发布不因补发而改变。
- **开发与测试的入账** `QB_WALLET_DEV_CREDIT`：见 D-Q5-8。

### D-Q5-6 首页、详情与"可见性"（U-10 – U-14、U-20 – U-22、5.3-3）【待确认 Q5-16】

- **卡片表** `QbPublicationCard`（P，`qb_publication_card`）：
  - 时态表上按状态、时间、地区筛选要扫全部历史，连接也不下推（已核实的事实），所以首页只读普通表。
  - 每个上架的发布一行，在 Q4 的上架处（`PublicationEditing.goingOn`）随计数行一起插入，在 `PublicationEditing.write` 中随状态同步（与日程行同一处、同一把发布锁）。
  - 列有：`sponsorId`、`status`、`regions`、`startAt`、`endAt`、`name`、`quizTitle`、`cover`、`maxReward`、`fullScore`、`passScore`、`timeLimitSec`、`questionCount`、`versionId`、`wentOnAt`。这些都在提交时冻结，只有状态会变。
  - 迁移为已上架的发布回填一行。
- **可见**（5.3-3、U-11）必须同时满足，模板与 `QB_ATTEMPT_START` 用同一组条件：
  - 卡片 `status = 'LIVE'`，且 `startAt ≤ at < endAt`；
  - 用户国家的地区与发布地区有交集：模板写 `string_to_array(…) && string_to_array(…)`，Java 写 `Regions.overlaps`；
  - 名额与预算未用完：D-Q5-3 的条件；
  - 商家可支付：`pendingCount = 0` 且 `balance ≥ maxReward`，钱包行左连接，没有行即为 0。
- **"现在"**：模板正文取不到 `Clock`（平台缺口 11），首页与详情由调用方传必填参数 `at`。
  - 可见性只是展示，开始答题在服务端按 `Clock` 再判断一次。所以客户端传错 `at`，最多是看到一张点不进去的卡片。
- **完成人数**（U-22，6.1 "可每 10 分钟更新"）【待确认 Q5-16】：
  - 已认证的模板没有缓存（平台缺口 12）。热门发布按月窗口在索引上计数，每次打开详情都要数十万行。
  - 因此由任务 `qb.publication-stats`（每 10 分钟）把上线中及 31 天内结束的发布的 1 小时、24 小时、7 天、30 天完成数写进普通表 `qb_publication_stats`。
  - 累计完成数直接取计数行，是实时的。
  - 这个任务只写自己的表，不取业务锁，整批一个事务没有平台缺口 6 的问题。
- **浏览记录** `QB_VIEW`【待确认 Q5-16】：
  - 每人每发布只记第一次（`QbPublicationView`，`(user_id, publication_id)` 唯一，重复即忽略）。
  - 为 Q9 的漏斗先积累数据，事后无法补。
- **内容文件**：把 `qb.content.file.read` 授予 `QB_TAKER`（设计 §2 已规划），答题人才能读封面、题图与资料文件。
  - 文件编号是 UUIDv7，不可猜，但持有编号即可读任意内容文件，包括草稿的。见风险。

### D-Q5-7 锁的顺序（更新 `backend/quizbuks/CLAUDE.md`）

```
qb.quiz:<quizId>（共享，只有提交发布）→ qb.pub:<publicationId>（独占）→ qb.wallet:SPONSOR:<userId>（独占）
```

| 流程 | 锁 | 持锁内做什么 |
|---|---|---|
| `QB_ATTEMPT_START`（新建） | 发布锁 | 读卡片、计数，软读钱包，插入会话，更新计数 |
| `QB_ATTEMPT_START`（继续） | 不取 | — |
| `QB_ATTEMPT_SAVE` | 不取 | 会话行的乐观版本 |
| `QB_ATTEMPT_SUBMIT` / `QB_ATTEMPT_EXPIRE` | 发布锁 → 钱包锁（奖励为 0 时不取钱包锁） | 计数、钱包、过账、会话 |
| `QB_PENDING_SETTLE`、`QB_WALLET_DEV_CREDIT` | 只取钱包锁 | — |
| `QB_PUBLICATION_FUNDS_RESTORED`、Q4 的定时任务（迁到 16k 之前） | 多把发布锁，按编号升序 | 从不取钱包锁 |

持钱包锁的流程从不再取发布锁，所以不会成环。

### D-Q5-8 开发与测试的入账【待确认 Q5-8】

Q6 之前，商家没有办法充值，但结算、待支付与补发都需要商家有余额。办法如下：
- 流程 `QB_WALLET_DEV_CREDIT {sponsorId, amount}`：
  - 只在配置 `quizbuks.wallet.dev-credit.enabled=true` 时注册：`application-dev.yml` 与测试配置开启，缺省关闭。
  - 权限 `qb.wallet.dev-credit` 不授予任何角色。
  - 钱包锁 → 过账 借 1110 / 贷 2110（模拟充值到账）→ 子流程 `QB_PENDING_SETTLE`。
- 启动检查（类别 `QUIZBUKS`）：开启它而又配置了 Stripe 正式密钥（`STRIPE_API_KEY` 以 `sk_live_` 开头）时报错。部署说明写明生产不开。
- 另一种做法是正式的"人工入账"（财务提出、另一人审批，用于银行转账充值），放在 Q6 讨论。

### D-Q5-9 自动提交任务与平台缺口 6【待确认 Q5-2】

- **任务** `qb.attempt-expire`（每分钟）：找出 `status = IN_PROGRESS` 且 `deadline + 宽限 ≤ now` 的会话（部分索引），每个执行 `QB_ATTEMPT_EXPIRE {attemptId}`，按已保存的答案评分并结算，与提交同一套步骤。
- **Q4 的"一批一个事务"在这里不能用**，原因有三：
  1. **违反锁序，会死锁**：一批中第 1 项持有发布 A 与商家 S 的钱包锁，第 2 项再取发布 B 的锁，就成了"钱包 → 发布"。此时一个正在发布 B 上提交的用户持有 B 而等 S，即死锁（`40P01`），整批回滚。Q4 的定时任务只取发布锁，所以没有这个问题。
  2. **阻塞热门发布**：整批持有的锁到最后才释放。批中每多一个热门发布，该发布上的所有开始与提交都要等完整批。
  3. **饥饿**：一项持续失败就挡住整批（Q4 已知问题）。
- **结论：自动提交必须逐项独立事务。**
  - **缺省做法**：先做平台阶段 16k"任务逐项事务"（见平台缺口 6，约 2–3 天）。Q5a 不等它，Q5b 在它合入后做。Q4 的定时任务同时改用它，解决 Q4 的已知问题。
  - **备用做法**（16k 延期时，不需要平台改动）：任务只负责扇出事件。
    - 任务对每个到期会话 `PublishEvent("qb.attempt.expire-due", {attemptId})`，并在普通表 `qb_attempt_expiry(attempt_id, queued_at)` 上记下"已排队"。10 分钟内不重复排队，避免积压时每分钟重复发布。
    - 订阅 `qb.attempt-expire` 在各自的事务中执行 `QB_ATTEMPT_EXPIRE`。
    - 代价：吞吐受投递的 `batch-size`（缺省 20，即每实例每秒 20 个）限制，另多一张表，事件表也多一份积压。
    - `QB_ATTEMPT_EXPIRE` 两种做法都一样，切换只改任务的接线。
- **规模**：按 Q11 的缺省规模（1 000 个同时进行的发布，单个发布峰值 50 次 / 秒），假定 10%–30% 的人中途放弃，峰值约每分钟数百到数千个到期会话。
  - 逐项事务每项约 10–20 毫秒，单个实例每分钟可处理 3 000 个以上。每次至多 2 000 项（`PlayLimits.EXPIRE_BATCH`），按 `deadline` 排序。
  - 超出的部分在下一分钟处理，跑超过一分钟的那次运行由集群锁挡住重入。

### D-Q5-10 吞吐（对照 Q10 的压测目标）【待确认 Q5-9】

**目标**（Q11 缺省，Q10 压测）：
- 单个发布峰值 50 次提交 / 秒，加上同样多的开始；
- 同时进行的发布 ≤ 1 000，用户 ≤ 100 万；
- 首页与详情 p95 < 300 毫秒，开始与提交 p95 < 1 秒。

**瓶颈**：

| 锁 | 流量（单个热门发布） | 每次持锁的预计时间 | 利用率 |
|---|---|---|---|
| `qb.pub:<发布>` | 开始 50/s + 提交 50/s | 开始约 3 ms；提交约 10–15 ms（含过账） | 约 65%–90% |
| `qb.wallet:SPONSOR:<商家>` | 该商家全部发布的提交 | 约 8–12 ms | 一个热门发布约 50%；同一商家两个热门发布即饱和 |

持锁时间 = 取锁之后的步骤 + 提交。

**Q5 的设计手段**（不改需求）：
1. **晚取锁**：提交先在锁外读会话、版本快照、档位、发布，完成评分与档位计算，还有 `LoadParams`。取得发布锁之后只做两件事：重新读会话状态与计数行，然后写入。
   开始也是先在锁外读卡片、地区、已有会话，锁内只重查状态与计数、插入会话。
2. 开始不取钱包锁（D-Q5-5 的软检查）；零奖励不取钱包锁。
3. 进度保存不取任何锁；答案存在一行中（D-Q5-1）。
4. 首页、详情只读普通表（卡片、计数、钱包、统计），不在请求中计数。
5. 自动提交逐项事务，不与在线提交争批量锁（D-Q5-9）。

**Q5 里先测一次**：`PlayThroughputIT` 带 `@Tag("perf")`，不进 `check`。在本地 PostgreSQL 上让 64 个并发客户端对一个发布开始并提交，记录每秒完成数与 p50 / p95，写进本计划的实施记录，作为 Q10 的基线。

**达不到目标时的退路**（按代价从小到大，都不在 Q5 做，Q10 视结果决定）：
1. 降低 `LEDGER_POST` 的成本（平台缺口 14）。
2. 按发布预留预算（设计 §4.3）：上架时把预算从商家钱包转到 `publication` 维度，结算只取发布锁。这消除同一商家多个热门发布之间的争用，但对单个热门发布没有帮助。
3. 分批过账：提交只在发布锁内写结果与"结算中"，由后台每隔约 1 秒把一个商家的若干笔奖励合成一笔过账（≤ 199 行）。扣款与入账仍在同一事务中，但结果页要等约 1 秒才显示"已入账"（U-34 的体验改变，需求方确认）。
4. 连接池：等待锁的事务占着连接。热门发布排队过长时会耗尽连接池，影响首页等其他请求（平台缺口 15）。Q10 以连接池大小与入口限流（平台缺口 13）配合，或请平台在取锁前于进程内排队。

### D-Q5-11 历史（U-40 – U-42）

- 模板 `qb.app.history`：本人的会话（答题人视图）连接卡片。
- 结果列：
  - 发布名称、标题、封面；
  - `status`：进行中 / 已完成；
  - `passed`、`score`、`fullScore`；
  - `reward`、`rewardStatus`（含待支付）；
  - `startedAt`、`submittedAt`、`deadline`：继续答题按 `deadline` 显示"剩 N 分钟"。
- 分类：已完成（合格）、不合格、进行中、已过时限待自动提交。
- 筛选：关键字、开始日期、完成日期、金额区间、状态。
- 已完成会话的结果页：读本人会话的 `result` 列（D-Q5-2），不需要另一个流程。

## 要求

1. **权限**（`QbPermissions`、`QbRoles`；部署后再执行一次 `QB_SETUP`）：
   - 已有的 `qb.play`（`QB_TAKER`）用于开始、保存、提交、浏览记录、首页与详情；已有的 `qb.me` 用于历史；
   - `QB_TAKER` 增加 `qb.content.file.read`；
   - 新增 `qb.play.system`（任务、事件与补发的系统流程，不授予任何角色，同 Q4 的 `qb.publication.system`，平台缺口 8）；
   - 新增 `qb.wallet.dev-credit`（不授予任何角色）；
   - 管理员读会话用 `qb.admin.users.read`（P-01 用户详情），读钱包用 `qb.admin.finance.read`。
2. **字典**（三语）：`urn:jabiz:dict:quizbuks:attempt-status`（`IN_PROGRESS`、`SUBMITTED`、`EXPIRED`）、`urn:jabiz:dict:quizbuks:reward-status`（`NONE`、`PAID`、`PENDING`）。
3. **账本**：新维度 `publication`（位置 2，D-Q5-4）；不增加科目。
4. **地区**：`country.Regions.overlaps(countryRegions, publicationRegions)`。
5. **实体**：包 `com.jabiz.quizbuks.play` 4 个、`com.jabiz.quizbuks.wallet` 1 个、`publishing` 1 个（卡片），见下节。
6. **数据视图**：
   - 默认视图：管理员只读，`processOnlyWrites()`，写权限沿用不授予任何角色的 `qb.content.view.write`；
   - 答题人视图 `urn:jabiz:dataset:taker:QbAttempt`：`fromContext("userId", actorId)`，只读，权限 `qb.me`；
   - 卡片、计数、统计、钱包没有答题人或商家视图，只经模板连接。
7. **流程**：10 个，见"流程"一节。
8. **定时任务**：`qb.attempt-expire`（Q5b，16k）、`qb.publication-stats`。
9. **事件订阅**：`qb.pending-settle`（`qb.pending-settle.more` → `QB_PENDING_SETTLE`）、`qb.funds-restored`（`qb.sponsor.funds-restored` → `QB_PUBLICATION_FUNDS_RESTORED`）。另发布 `qb.reward.settled`、`qb.reward.pending`，Q7 订阅。
10. **SQL 模板**：
    - 新增 `qb.app.feed`、`qb.app.publication`、`qb.app.publication-tiers`、`qb.app.history`、`qb.admin.attempts`、`qb.admin.wallets`；
    - 修改 `qb.sponsor.publications`、`qb.admin.publications`（真实计数、`fundsShort`、`pendingCount`）。
11. **Q4 衔接**：
    - `PublicationEditing.goingOn` 插入卡片；
    - `PublicationEditing.write` 同步卡片的状态；
    - `PublicationRules` 增加 `endsBy(counter, publication)`（QUOTA / BUDGET）；
    - `FUNDS_SHORT` 的暂停与恢复按 D-Q5-5；
    - Q5b 把 `QB_PUBLICATION_TICK` 改为 16k 的逐项任务，批量从 50 改为 2 000。
12. **消息**（三语，启动检查齐全）：实体与字段、字典、10 个流程、全部 `QB_*` 码、结果页提示（`QB_REWARD_PENDING`）、模板与列名、`param` 与维度名。
13. **文档**：
    - `02-design.md`：
      - §3.3：`QbAttempt` 改为 JSON 列、不建 `QbAttemptAnswer`，`QbPublicationCard`、`qb_publication_stats`；
      - §3.4：`QbWallet` 在 Q5 只建商家钱包；
      - §4.1：维度 `publication`；
      - §4.2：抽成按结算时计算；
      - §4.3：锁名、锁序、晚取锁、吞吐与退路；
      - §5.1：`FUNDS_SHORT` 的恢复路径；
      - §5.2：新流程；
      - §6：模板与统计表；
    - `03-plan.md`：Q5 行拆为 Q5a / Q5b，新增平台 16k；
    - `backend/quizbuks/CLAUDE.md`：答题约定（锁序、正确答案不出现、只经流程写、钱包只在锁内改、开发入账）；
    - `deploy/quizbuks/README.md`：开发入账在生产关闭。

## 实体与迁移

记号：T = 时态（只追加），P = 普通表。主键为 UUIDv7。

| 实体（表） | 类型 | 字段 |
|---|---|---|
| `QbAttempt`（`qb_attempt`） | P | 见下方字段说明 |
| `QbPublicationView`（`qb_publication_view`） | P | `viewId`；`userId`、`publicationId`（二者唯一）；`firstViewedAt` |
| `QbPublicationCard`（`qb_publication_card`） | P | `publicationId`（主键）；D-Q5-6 的各列；`version` |
| `QbPublicationStats`（`qb_publication_stats`） | P | `publicationId`（主键）；`last1h`、`last24h`、`last7d`、`last30d`；`refreshedAt` |
| `QbWallet`（`qb_wallet`） | P | `walletId`；`ownerId`（`asText(128)`）+ `kind`（`SPONSOR` / `TAKER`）唯一；`balance`（JPY，`CHECK >= 0`）；`pendingCount`；`pendingAmount`；`pendingOut`（Q6）；`version` |

`QbAttempt` 的字段（`asMonetary("JPY", 0)` 的金额字段为 `numeric(19,0)`）：
- **标识**：`attemptId`；`userId`、`publicationId`（二者唯一）；`sponsorId`；`versionId`。
- **时间与状态**：`startedAt`、`deadline`、`status`（字典）、`currentIndex`。
- **答案**：`optionOrder`、`answers`、`result`，都是规范 JSON 文本，长度上限由 `ContentLimits` 推出。
- **结果**：`submittedAt`、`score`、`fullScore`、`passed`、`tierId`、`reward`、`fee`。
- **结算**：`rewardStatus`（字典）、`rewardPaidAt`、`ledgerTransactionId`。
- 除 `currentIndex`、`answers`（只经 `QB_ATTEMPT_SAVE`）外全部 `processOnly`。

**迁移** `db/migration/V5__quizbuks_play.sql`：
- 五张普通表按 Q4 计数行的写法：主键引用 `entity_registry`，带 `version`；`qb_wallet` 的唯一键在 `(owner_id, kind)` 上。
- 索引：
  - `qb_attempt`：
    - `UNIQUE (user_id, publication_id)`；
    - `(user_id, started_at DESC)`：历史；
    - `(publication_id, submitted_at) WHERE submitted_at IS NOT NULL`：统计；
    - `(deadline) WHERE status = 'IN_PROGRESS'`：自动提交；
    - `(sponsor_id, submitted_at, attempt_id) WHERE reward_status = 'PENDING'`：补发队列；
  - `qb_publication_card`：`(status, end_at)`、`(sponsor_id)`；
  - `qb_publication_view`：`UNIQUE (user_id, publication_id)`。
- 检查约束：`qb_wallet.balance >= 0`、`pending_count >= 0`、`pending_amount >= 0`；`qb_attempt.reward >= 0`。
- 回填：为 `qb_publication_schedule` 中已上架（状态不是 `PENDING_REVIEW`）的发布插入卡片行。读 `qb_publication_version` 的当前版本与 `qb_quiz_version`，只在迁移中执行一次。
- 备用做法（D-Q5-9）才需要 `qb_attempt_expiry`，缺省不建。

## 流程

共同点：
- 输入为 record，Bean Validation（400）；写入经 `ctx.changes()`，违规累积后 422。
- 答题人的流程声明 `requiresVerifiedEmail()`，并经 `TakerChecks.requireActive(ctx)` 拒绝封禁的账户（Q2 接上，同 Q4 的 `SponsorChecks`）。
- 会话经答题人视图读取，不是自己的即 404。
- 锁序见 D-Q5-7。提交与自动提交共用一组步骤（`AttemptFinishing.steps`）。

| 流程 | 权限 | 输入 | 要点 | 错误代码 |
|---|---|---|---|---|
| `QB_VIEW` | `qb.play` | `publicationId` | 卡片存在且对本人可见（否则 404）；已有即忽略 | — |
| `QB_ATTEMPT_START` | `qb.play` | `publicationId` | D-Q5-1、D-Q5-3、D-Q5-5；继续时不取锁；输出 `attemptId`、`deadline`、`serverTime`、`questions[{no, stem, image, points, options[{no, text, image}]}]`（按打乱的顺序）、`answers`、`currentIndex`（U-23、U-30、U-41） | `QB_PUBLICATION_NOT_OPEN`、`QB_PUBLICATION_NOT_VISIBLE`（地区）、`QB_PUBLICATION_BUDGET_FULL`、`QB_PUBLICATION_QUOTA_FULL`、`QB_SPONSOR_FUNDS_SHORT`、`QB_ATTEMPT_ALREADY_TAKEN`、`QB_ATTEMPT_OWN_PUBLICATION`、`QB_ATTEMPT_EXPIRED` |
| `QB_ATTEMPT_SAVE` | `qb.play` | `attemptId`、`currentIndex`、`answers[{questionNo, selected[]}]`（给出的即替换这些题的答案） | 不取锁；进行中且 `now ≤ deadline`；题号与选项号按 `optionOrder` 校验，一次报告全部问题（U-41） | `QB_ATTEMPT_NOT_IN_PROGRESS`、`QB_ATTEMPT_EXPIRED`、`QB_ANSWER_INVALID` |
| `QB_ATTEMPT_SUBMIT` | `qb.play` | `attemptId`、`answers[]?`、`currentIndex?` | 支持 `Idempotency-Key`。D-Q5-2、D-Q5-3、D-Q5-4、D-Q5-5。输出 `score`、`fullScore`、`passScore`、`passed`、`reward`、`rewardStatus`、`message`、`questions[{no, selected, correct, earned}]`（U-32 – U-34、5.3-5 – 9） | 同上，以及 `QB_ATTEMPT_FINISHED` |
| `QB_ATTEMPT_EXPIRE` | `qb.play.system`，`internal()` | `attemptId` | 按已保存答案完成，状态 `EXPIRED`；已完成即什么也不做（幂等） | — |
| ~~`QB_ATTEMPT_EXPIRE_DUE`~~ | — | — | **未建（Q5b）**：16k 的逐项任务自己以模板 `qb.system.attempts-due` 选取、对每项直接运行 `QB_ATTEMPT_EXPIRE`，不需要单独的选取流程；只有备用做法（扇出事件）才需要它 | — |
| `QB_PENDING_SETTLE` | `qb.play.system`，`internal()` | `sponsorId` | D-Q5-5：只取钱包锁，至多 50 条，按顺序；还有可付的就发布事件继续；清空后发布恢复事件 | — |
| `QB_PUBLICATION_FUNDS_RESTORED` | `qb.publication.system`，`internal()` | `sponsorId` | D-Q5-5：按编号升序取发布锁，`FUNDS_SHORT` → `LIVE` / `ENDED` | — |
| `QB_PUBLICATION_STATS_REFRESH` | `qb.play.system`，`internal()` | `scheduledTime` | 任务 `qb.publication-stats`（每 10 分钟）：`RunTemplate` 计算窗口，写统计表 | — |
| `QB_WALLET_DEV_CREDIT` | `qb.wallet.dev-credit`（不授予任何角色），只在开启时注册 | `sponsorId`、`amount` | D-Q5-8 | 金额规则 |

**纯 Java 部件**（无 I/O，单元测试）：
- `PlayLimits`：宽限、批量、补发批量、JSON 长度；
- `AttemptStatus`、`RewardStatus`：常量；
- `OptionShuffle`；
- `Answers`：解析、规范化、按形状校验；
- `Scoring`；
- `TierPick`；
- `RewardSplit`；
- `AttemptRules`：开始时的预占条件、结束条件、是否过时限；
- `Regions.overlaps`；
- `TakerChecks`（Q2 之前恒为通过）。

**错误码**：字段规则违规 400；流程拒绝 422；不存在或不是自己的 404；无权限 403；邮箱未验证按平台返回。全部写在 `play.PlayCodes`。

## SQL 模板

- **`qb.app.feed`**（U-10 – U-14，权限 `qb.play`）：
  - 数据来源：卡片 ⋈ 计数 ⟕ 统计 ⟕ 商家钱包 ⋈ 本人资料（答题人视图）⋈ 国家 ⟕ 发布人（`QbSponsor`、`QbProfile`，Q2）。条件同 D-Q5-6。
  - 参数：`at`（必填）、`q`（≤ 100，按标题与名称搜索，写法同 `qb.sponsor.quizzes`）。
  - 结果列：`publicationId`、`name`、`quizTitle`、`cover`、`maxReward`、`completions`、`sponsorName`、`sponsorAvatar`、`sponsorCountry`、`startAt`、`endAt`、`timeLimitSec`、`questionCount`。
  - 筛选：发布人国家与名称、金额区间（`maxReward`）、最少完成人数、发布日期区间（`startAt`）。
  - 排序：金额、完成人数、`startAt`（最新 / 最早，默认最新）。
- **`qb.app.publication`**（U-20 – U-23）：
  - 结果为一行：卡片字段、发布人、简介、资料（快照的 `intro` 与 `materials` 部分，JSON 文本列，不含 `questions`）、四个时间窗口的完成数、累计完成数。
  - 本人会话的 `myAttemptId`、`myStatus`、`myDeadline`（按钮是否可点、继续答题）。
  - 可见条件同首页；本人已有会话时，发布不可见也能读到（看自己答过的）。
- **`qb.app.publication-tiers`**：档位列表（`fromScore`、`toScore`、`reward`），条件同上。
- **`qb.app.history`**（U-40 – U-42，权限 `qb.me`）：见 D-Q5-11。
- **`qb.admin.attempts`**（`qb.admin.users.read`）：按用户、发布、状态、奖励状态查询会话（P-01 的答题历史，以及支持查询）。
- **`qb.admin.wallets`**（`qb.admin.finance.read`）：商家钱包的余额、待支付笔数与金额。
- **`qb.system.attempts-due`**（`qb.play.system`）：到期未完成的会话，供任务选取。
- **`qb.sponsor.publications`** / **`qb.admin.publications`**（Q4）：
  - 计数从此为真实值；
  - 增加 `fundsShort`：`LIVE` 而钱包付不起 `maxReward`，或有待支付。商家后台据此显示"预算不足"，即使状态还没有因结算失败而改变；
  - 增加 `pendingCount`、`pendingAmount`（M-34 已花 / 预算）。
  - 快照随之更新。

## 测试

集成测试连本地 PostgreSQL（`JABIZ_TEST_DB_*`），BlockHound 开启。令牌经 `QbItSupport`：
- 答题人 U1、U2（国家不同，邮箱已验证），U3（未验证）；
- 商家 A、B；
- 内容管理员、财务管理员、超级管理员。

每个类的准备：执行 `QB_SETUP` 并发布提案；A 建版本并上架发布（自动通过）；`QB_WALLET_DEV_CREDIT` 给 A 充值。

- **单元**：
  - `ScoringTest`：多选全对才得分、空答、重复选项；jqwik 与逐项独立实现对照；
  - `TierPickTest`；
  - `RewardSplitTest`：比例 0、0.1、0.3333 的取整，抽成加用户所得等于奖励；
  - `OptionShuffleTest`：每题是排列、同一会话结果相同、不同会话分布合理（jqwik）；
  - `AnswersTest`：越界题号与选项号一次报告全部；
  - `AttemptRulesTest`：预占的边界（`maxReward` 恰好等于剩余预算、名额差一）、结束条件、`deadline` 与宽限前后 1 毫秒；
  - `RegionsTest`：`overlaps`；
  - `QbRolesTest`：新授权；`qb.play.system`、`qb.wallet.dev-credit` 不授予任何角色；
  - `MessagesTest`。
- **`PlayIT`**（U-23、U-30 – U-34、U-41、5.3-5、5.3-6）：
  - 开始 → 保存 → 再次开始得到同一会话，答案与题号恢复 → 提交 → 结果有逐题正误与正确答案；
  - 第二次开始 → 422 `QB_ATTEMPT_ALREADY_TAKEN`；
  - 同一 `Idempotency-Key` 重放；不同键再提交 → 422 `QB_ATTEMPT_FINISHED`；
  - 宽限内提交成功；宽限外 → 422，答案未被采用；
  - 非法答案一次报告全部；暂停、未开始、已结束、地区不符、自己的发布 → 各自的码；
  - 未验证邮箱被拒；
  - 并发两次开始只建一个会话。
- **`AnswerSecrecyIT`**（设计 §9）：进行中会话的开始输出、继续输出、详情、首页、历史、答题人视图中，都不含任何题的正确选项（与快照比对，而不只是找 `correct` 键）；完成后才出现。
- **`SettlementIT`**（5.3-7、5.3-9、5.3-13、D-Q5-4）：
  - 分录：借 2110（商家，发布）/ 贷 2120（用户，发布），来源 `QbAttempt`，记账时间为完成时间；
  - 抽成参数改为 0.1（经受控变更）→ 三行，且取整正确；
  - 零奖励不过账；
  - 钱包缓存等于账本维度余额，全部科目之和为 0；
  - 账本表与 Q3、Q4 的时态表没有 UPDATE / DELETE。
- **`PendingIT`**（5.3-8、U-34、D-Q5-5）：
  - 余额不足 → `PENDING`，不入账，发布 `FUNDS_SHORT`，首页不再显示，开始被拒；
  - 有待支付时余额够付的新提交也排队；
  - 开发入账 → 按提交顺序补发，遇到付不起的停止；
  - 超过一批（51 条）时由事件接续，且只处理一次；
  - 清空后 `FUNDS_SHORT` → `LIVE`；已过结束时间的 → `ENDED`；已结束的不变；
  - `FUNDS_SHORT` 的暂停与恢复。
- **`QuotaBudgetIT`**（D-Q5-3）：
  - 名额：进行中的会话占名额，`winners` 到上限即 `ENDED / QUOTA`，非获奖的完成会释放名额；
  - 预算：`ENDED / BUDGET`；
  - 32 个线程同时开始与提交，获奖人数与花费不超过上限，计数与会话一致。
- **`PlayConcurrencyIT`**：
  - 同一商家两个发布、余额只够 k 笔，40 个并发提交 → 恰好 k 笔 `PAID`（按完成顺序）、其余 `PENDING`，余额不为负；
  - 暂停与开始并发，暂停提交之后不再有新的开始；
  - 移除模版与开始答题并发，没有死锁（锁序）。
- **`AttemptExpireIT`**（Q5b，U-31 服务端兜底）：
  - 任务按已保存答案完成到期会话，状态 `EXPIRED`，照常结算；
  - 错过多个计划时刻后的一次运行全部补上；
  - 一项失败不影响其他项（16k）；
  - 任务与在线提交在同一商家上并发，没有死锁，在线提交不被整批阻塞；
  - 与本人提交同时发生，只完成一次。
- **`FeedIT`**（U-10 – U-14、U-20 – U-22、U-40 – U-42、5.3-3）：
  - 可见性矩阵：状态 × 时间 × 地区 × 名额 / 预算 × 商家余额；
  - 搜索、筛选、排序；详情与档位；
  - 统计任务的四个窗口（固定时钟）；
  - 历史的分类与筛选；
  - `EXPLAIN` 首页查询：不扫描时态表的全部版本（与 `TemporalScaleIT` 同样的断言方式）。
- **`PlayOwnerIT`**：
  - U2 对 U1 的会话：保存与提交 404，历史看不到；
  - 答题人或商家直接调用系统流程与开发入账 → 403；
  - 经数据视图直接写会话、钱包、卡片 → 被拒；
  - 管理员只读全部。
- **`SetupIT` 扩展**：答题人的新授权；再次执行 `QB_SETUP` 不重复。
- **场景回放** `scenarios/qb/play.yml`（固定时钟，用 `actor` 切换操作人）：
  1. A 上架三档发布，开发入账给 A 足够付一笔的余额；
  2. U1 开始、保存、提交（获奖、已入账）；
  3. U2 开始并提交（获奖、待支付、`FUNDS_SHORT`）；
  4. 开发入账（`deliverEvents: true`）→ U2 补发 → `LIVE`；
  5. U3 开始后不提交 → `runJob qb.attempt-expire`（Q5b）→ `EXPIRED`；
  6. `runJob qb.publication-stats`；
  7. 查询首页、详情、历史与账本余额。

  场景需要 `properties: jabiz.jobs.lock-at-least-for: 0s`（平台缺口 7）。
- **`PlayThroughputIT`**（`@Tag("perf")`，不进 `check`）：D-Q5-10 的基线。
- `platformCheck` 通过（`LEDGER`、`JOB`、`EVENT` 类检查）；`ArchitectureTest` 不变。

## 验收标准

- [x] `./gradlew :quizbuks:check` 通过（含 `platformCheck`）；`tools/check-app-paths.sh` 通过（Q5a；PR 上的 CI 待 PR）。
- [x] U-10 / U-11：首页只给出可参与的发布（进行中、在时间内、地区有交集、名额与预算未满、商家有余额），卡片字段齐全，查询只读普通表。
- [x] U-12 – U-14：按标题搜索；按发布人国家与名称、金额区间、最少完成人数、发布日期筛选；按金额、完成人数、时间排序。
- [x] U-20 – U-22：详情有简介、满分、题数、合格分、时限、档位、发布人、资料、四个窗口与累计的完成人数（窗口每 10 分钟更新）。
- [x] U-23 / 5.3-6：开始即建会话并计时；一人一次（唯一约束兜底）；已参加过的不能再开始。
- [x] U-30 – U-32 / 5.3-5：返回全部题目（打乱的选项、无正确答案）；时限以服务器时间为准，超时提交被拒（Q5a；`QB_ATTEMPT_EXPIRE` 已有）。
- [x] 到时由任务按已保存答案自动提交（Q5b：逐项任务 `qb.attempt-expire`、模板 `qb.system.attempts-due`、每项 `QB_ATTEMPT_EXPIRE`；`AttemptExpireIT`、场景 `qb/play.yml`）。
- [x] U-33：完成后返回得分、合格分、是否合格、逐题我的答案与正确答案；进行中的会话从任何途径都得不到正确答案。
- [x] U-34 / 5.3-7：按得分所在档位一次性发放（不及格也可能有奖励）；扣款与入账是同一笔交易、同一事务；余额不足时提示并记为待支付。
- [x] U-40 – U-42：历史分为已完成与未完成，显示状态与奖励（含待支付）；可继续答题（剩余时间）；可按关键字、日期、金额、状态筛选。
- [x] 5.3-8：钱包余额不为负；待支付不入账；有余额后按提交顺序补发；补发完后发布恢复为进行中。
- [x] 5.3-9：每笔奖励的分录关联会话（来源单据），带当事人与发布维度；金额为正。
- [x] 5.3-2（Q5 的部分）：名额或预算用完即结束（`QUOTA` / `BUDGET`）；`FUNDS_SHORT` 的进入与恢复；已开始的会话在暂停、预算不足、结束后照常完成。
- [x] 5.3-13：抽成按受控参数计提（当前为 0，不出行）。
- [x] 6.1：重复提交不重复发奖（幂等键与会话状态）；吞吐基线已记录，对照 Q10 目标。
- [x] 普通表只经流程写入；时态与账本表没有 UPDATE / DELETE；消息三语齐全；文档已更新；部署后执行 `QB_SETUP`。

## 风险

- **吞吐**（D-Q5-10）：
  - 单个热门发布的开始与提交共用一把发布锁，结算还与同一商家的其他发布共用一把钱包锁。按估算利用率在 65%–90%，接近上限。
  - Q5 只记录基线，退路由 Q10 的结果决定。其中第 3 条（分批过账）会改变结果页"已入账"的即时性，需要需求方确认。
- **等待锁占用连接**：排队的事务各占一个连接。热门发布在高峰时可能耗尽连接池、拖慢首页（平台缺口 15）。Q10 前须确定连接池大小与限流。
- **开始时的软余额检查**（D-Q5-5）：并发开始可能使待支付多于预期。需求允许，但商家体验可能差。改为预占需要开始也取钱包锁（Q5-4）。
- **名额预占**（D-Q5-3）：接近上限时，进行中或已放弃（未到时限）的会话会暂时挡住新的开始，最长一个答题时限加一分钟。
- **审计与事件的量**：
  - 每次保存进度都在 `sys_audit_record` 写一条前后值（含答案 JSON）。
  - 每笔结算另有账本交易的变更事件与审计。
  - 100 万用户的量级下，这些表与封存任务的负担要在 Q10 评估（平台缺口 16）。
- **内容文件对全体答题人可读**：持有文件编号即可读，包括草稿的文件。编号不可猜，但不是授权边界。若需求方要求更严，需要平台的"按引用授权读取文件"。
- ~~**自动提交依赖 16k**（D-Q5-9）~~：16k 已合入，Q5b 用逐项任务实现，备用做法未用。
- **Q2 未完成**（Q5-1）：若先做 Q5，地区、封禁与发布人信息都要先用占位，Q2 再接上并补测试。
- **开发入账误开**：生产误开 `quizbuks.wallet.dev-credit.enabled` 即可凭空给商家加余额。权限不授予任何角色，持有 `*` 的管理员除外。由启动检查与部署说明防范。
- **抽成取整**：向下取整对平台不利（少收不足 1 Kudos 的部分）。比例不为 0 之前需与会计确认（需求 7.6）。
- **Q2 评审的要求**（2026-10-11）：
  - **发布人头像**：答题人可读的只能是**入驻已通过**的商家的头像编号（模板连接 `QbSponsor` 时限 `reviewStatus = APPROVED`）。持有 `qb.sponsor.avatar.read` 即可按编号读取任一头像，
    待审核或被驳回的商家上传的图片不能经发布人信息暴露出去。发布人名称同样取已通过的入驻（`QbSponsor.contactName`、`company`）。
  - **封禁**：开始答题、保存、提交照 `identity.CallerChecks.refuseBanned` 先查调用者的封禁（Q2 的一切写入都已如此）。

## 预估

| 工作 | 天数 |
|---|---|
| 实体、迁移（含回填）、视图、字典、维度、卡片与 Q4 衔接 | 1 |
| 纯 Java 部件（评分、打乱、答案、档位、抽成、规则） | 1 |
| 开始、保存、提交、结算与过账 | 2–2.5 |
| 待支付、补发与续接、恢复、开发入账 | 1–1.5 |
| 模板（首页、详情、档位、历史、管理）、统计任务、Q4 模板更新 | 1 |
| 测试、场景、吞吐基线 | 2 |
| **Q5a 合计** | **约 8–9** |
| Q5b：自动提交任务、Q4 定时任务改为逐项、测试（16k 合入后） | 1 |
| **合计（不含页面）** | **约 9–10** |

- 页面（首页、详情、答题、结果、历史）在 15a、16c 之后另做，约 4 天。
- `03-plan.md` 的 Q5 估了 8–10 天（含页面）；按本计划，后端与页面合计约 13–14 天。
- 平台 16k 另计 2–3 天，可与 Q5a 并行。

## 平台缺口（按 `backend/quizbuks/CLAUDE.md` 第 2 节在 `1.2/platform` 上补）

| 编号 | 缺口 | 是否阻塞 Q5 | 本阶段的绕行 / 建议 |
|---|---|---|---|
| 6 | **定时任务里逐项独立的事务**（Q4 已提出） | **已由平台 16k 解决**（D42；Q5b 已用） | 见 D-Q5-9。建议平台阶段 **16k "任务逐项事务"**（2–3 天）：<ul><li>`JobDefinition.forEach(名称, cron, 时区, 选取模板, 每项流程, 行 -> 输入).maxItems(n)`；</li><li>先在一个只读的短事务中选取，再对每项以系统身份执行每项流程，各一个事务，幂等键 `job:<名称>:<计划时刻>:<项键>`；</li><li>失败的项追加到只追加表 `sys_job_item_failure` 并记日志，不中止其他项；</li><li>运行结果记成功、失败、跳过的数目；场景回放的 `runJob` 报告各项结果；</li><li>附带缺口 10 的逐项锁等待，例如 `itemLockTimeout(2s)`：等不到锁的项本次跳过，下次再选。</li></ul>示范：`app` 的某个按项处理的任务。备用做法：事件扇出（不需平台改动） |
| 10 | `HoldLock` 只有全局等待时限（Q4 已提出） | 否 | 合并进 16k。在线流程照用 30 秒 |
| 7 | 场景中同一任务几秒内两次运行得到 `LOCKED` | 否 | 场景加 `jabiz.jobs.lock-at-least-for: 0s` |
| 8 | `internal()` 仍照常检查权限，没有"只允许系统身份" | 否 | 用不授予任何角色的 `qb.play.system`；持有 `*` 的管理员仍可调用，但系统流程都重新读取状态、幂等，伪造调用不产生额外效果 |
| 9 | 测试基类每个测试前把时钟拨回起点 | 否 | 沿用 Q4 的 `PublicationItSupport.install` |
| 11（新，小） | **模板正文取不到运行的"现在"**（`Clock`） | 否 | 首页、详情由调用方传 `at`，服务端在开始时再判断。建议平台提供保留参数（如 `:__now`，取 `Clock`） |
| 12（新） | **已认证的模板没有缓存**（`cacheSeconds` 只用于公开模板） | 否 | U-22 用 10 分钟的统计任务与普通表。建议平台支持按模板与参数的短时缓存（不含操作人参数时） |
| 13（新） | **没有按用户的流程接口限流**（设计 §9 已预见：平台只限公开接口与上传） | 否（Q10 前需要） | Q5 不限流。开始与提交有锁与唯一约束保护正确性，但可被刷请求占用锁与连接。建议在 G1 的范围内补"按用户、按流程的令牌桶" |
| 14（新，视压测） | **`LEDGER_POST` 的单笔成本**：维度与来源经默认视图逐行核对、审计、变更事件、子操作记录 | 否 | Q5 记录基线；Q10 不达标时请平台评估更省的路径（例如同一事务内缓存维度核对结果、可选不发布交易的变更事件） |
| 15（新，视压测） | **命名锁的等待占用数据库连接** | 否 | Q10 以连接池与限流应对。必要时请平台在 `HoldLock` 前于进程内按名称排队，或给出等待者上限 |
| 16（新，视压测） | **高频普通表写入的审计量**（进度保存） | 否 | 照常审计。若 Q10 显示审计与封存成为瓶颈，请平台提供按字段或按流程降低审计粒度的声明（须新决策，因为 D27 要求全部写入经审计） |
| 17（新，小） | **测试中 BlockHound 随机把 span 导出的交接当作阻塞**（`BatchSpanProcessor$Worker.addSpan` 的 `Unsafe#park`，见"CI 的随机 500"） | 否 | ✅ 已由平台 #125 解决（`JabizBlockHoundIntegration` 加入同一条容许并附回归测试），应用中的副本已删除 |

不需要平台新能力的点：
- 账本过账、冲正、维度（16h 已支持身份字段）、来源单据；
- 受控参数（抽成）；`requiresVerifiedEmail()`；
- `HoldLock`、`CallProcess.forEach`（补发在一个钱包锁内，没有锁序问题）、`EventSubscription`（补发续接、恢复）、`PublishEvent`；
- 余额下限由应用在锁内判断（G8 已取消）。

## 待确认问题

| 编号 | 问题 | 本计划的缺省 |
|---|---|---|
| Q5-1 | 与 Q2 的先后 | 先做 Q2 后端（地区、封禁、发布人信息都来自它；平台依赖已合入）。另一种做法：Q5 先做，以 `TakerChecks` 与"无资料即只看 `GLOBAL`"占位，发布人信息先留空，Q2 接上 |
| Q5-2 | 自动提交怎样做到逐项事务 | 先做平台 16k（2–3 天，与 Q5a 并行），Q5b 在其后。备用：任务扇出事件，各在自己的事务中执行 |
| Q5-3 | Q5 的钱包范围 | 只建商家钱包（余额缓存与待支付计数）；用户余额按账本维度汇总；用户钱包与其锁由 Q6 按转账需要决定 |
| Q5-4 | 开始答题是否在商家钱包上预占 | 不预占，只做软检查（无待支付且余额 ≥ 最高档奖励）；并发超出的人记为待支付 |
| Q5-5 | 名额（最大获奖人数）是否硬上限 | 是：进行中的会话预占名额，获奖人数不超过上限；接近上限时新的开始要等进行中的结束 |
| Q5-6 | 有待支付时新的奖励能否插队 | 不能：严格按提交顺序，新奖励也记为待支付，补发时按序 |
| Q5-7 | 超时提交 | 宽限 5 秒；超出即 422，本次答案作废，由任务按已保存答案自动提交（约 1 分钟内）。另一种做法：超时提交时当场按已保存答案完成 |
| Q5-8 | Q6 之前怎样给商家余额 | 只供开发与测试的 `QB_WALLET_DEV_CREDIT`（缺省关闭，权限不授予任何角色）。另一种做法：正式的"人工入账"（四眼审批），放在 Q6 讨论 |
| Q5-9 | 吞吐目标与退路 | 目标为单发布 50 提交 / 秒 + 50 开始 / 秒，提交 p95 < 1 秒；Q5 只测基线，退路（降低过账成本 → 按发布预留 → 分批过账）由 Q10 视结果决定，分批过账需另行确认 U-34 的即时性 |
| Q5-10 | 抽成的时点与取整 | 按结算（含补发）时的比例；向下取整到整数 Kudos；为 0 时不出收入行 |
| Q5-11 | 自动提交的会话是否照常发奖 | 是，与本人提交相同（按已保存答案评分） |
| Q5-12 | 账本加"发布"维度 | 加（维度 2），分录的三行都带，供商家花费与汇总凭证按发布汇总 |
| Q5-13 | `FUNDS_SHORT` 的暂停 / 恢复与回到进行中 | 可暂停；恢复按时间；补发清空队列后自动回到 `LIVE`（已过结束时间即 `ENDED`） |
| Q5-14 | 答案存放 | 会话行上的 JSON 列，不建逐题答案表；Q9 的题目分析用 JSON 展开 |
| Q5-15 | 商家能否答自己的发布；同一用户既是商家又是答题人 | 不能答自己的；可以答其他商家的 |
| Q5-16 | 完成人数的更新方式；是否在 Q5 记录浏览 | 窗口每 10 分钟由任务更新（累计实时）；Q5 记录首次浏览（`QB_VIEW`），供 Q9 的漏斗 |
| Q5-17 | 站内信 | Q5 只发布事件，Q7 订阅生成消息（含历史事件） |

### Critical Files for Implementation
- `backend/quizbuks/src/main/java/com/jabiz/quizbuks/publishing/PublicationEditing.java`：`goingOn`、`write`、日程行；卡片随之维护。
- `backend/quizbuks/src/main/java/com/jabiz/quizbuks/publishing/PublicationLifecycle.java` 与 `PublicationRules.java`：`FUNDS_SHORT`、结束原因、定时任务改为逐项。
- `backend/quizbuks/src/main/java/com/jabiz/quizbuks/wallet/QbLedger.java` 与 `WalletConfig.java`：维度 `publication`、钱包。
- `backend/quizbuks/src/main/java/com/jabiz/quizbuks/content/QuizSnapshots.java` 与 `QuizSnapshot.java`：评分与去掉正确答案的投影。
- `backend/quizbuks/src/main/java/com/jabiz/quizbuks/setup/QbRoles.java`、`QbPermissions.java`、`QbParams.java`。
- 平台参考：
  - `backend/app` 的 `commerce`（`ORDER_SHIP` 的 `LEDGER_POST` 子流程）；
  - `docs/design/11-ledger-events-jobs.md` §1、§2.3、§4；
  - `docs/design/06-process.md` §2.1、§4.1；
  - `docs/design/04-temporal-append-only.md` §5。

（以上路径以 `origin/1.2/quizbuks` 与 `origin/1.2/platform` 为准。）

## 确认

2026-10-11（需求方）：**同一版本用于多个发布**——版本是模板，发布定义范围；用户可以参加任何满足条件的发布，不论是否参加过同一版本的其他发布。
保持现状：一人一次只按发布（`(user_id, publication_id)` 唯一）。

2026-10-11：计划与待确认问题 Q5-1 – Q5-17 全部按缺省处理：先做 Q2 后端，再做 Q5a；Q5b（自动提交任务）在平台 16k（定时任务逐项独立事务）合入后做。

## 实施记录（Q5a，2026-10-11）

分支 `1.2/quizbuks-5a-play`（从 `1.2/quizbuks` 拉出，Q2 已合入）。Q5a 是本计划除自动提交任务（`qb.attempt-expire`、`QB_ATTEMPT_EXPIRE_DUE`、模板 `qb.system.attempts-due`）
与 Q4 定时任务改写以外的全部内容；`QB_ATTEMPT_EXPIRE` 本身已实现（与提交共用步骤），选取它的任务在 Q5b。

**与计划的出入**（都不改需求）：
- 迁移是 `V6__quizbuks_play.sql`（`V5` 已被 Q2 的账户占用）。生成主键的普通表（会话、浏览、钱包）主键为 `varchar(36)`（平台为普通实体生成文本键，同 `sys_mail_message`），
  不引用 `entity_registry`；以发布为主键的卡片、统计表照计数行引用它。
- 多两个内部流程：`QB_PENDING_PAY`（补发一笔：再取同一把钱包锁，确认仍是队首且余额够付才过账，单独调用也安全）与 `QB_PUBLICATION_FUNDED`
  （`QB_PUBLICATION_FUNDS_RESTORED` 逐个以子流程调用，每个持自己的发布锁）。补发清空队列时（不只是本次清空时）发布 `qb.sponsor.funds-restored`，
  以免直接补发留下的 `FUNDS_SHORT` 无人恢复；消费者找不到 `FUNDS_SHORT` 的发布即什么也不做。
- **首页、详情、档位的"用户国家"由调用方传参数 `country`**（与 `at` 同理：只是展示，开始答题在服务端按资料再判断）。原计划连接本人资料（答题人视图），但数据视图的范围条件在
  `DISTINCT ON` 之外（平台 04 §5.1），100 万用户时每次打开首页都要读全部资料版本。模板仍连接国家与入驻两个时态表（数量很小，有界）。
- **卡片多存** `budgetCap`、`maxWinners`（首页的名额与预算条件）、`tiers`（档位的规范 JSON，`publishing.CardTiers`：档位在提交时冻结；档位模板与评分只读卡片）、
  `sponsorCountry`（上架时商家资料中的国家：入驻没有国家，U-13 的"发布人国家"只能取资料）。
- 选项打乱以"用户 | 发布"为种子（一人一次，等价于会话编号；会话编号在插入时才生成）。`currentIndex` 从 0 起。
- `TakerChecks` 未建：Q2 已合入，答题流程照 Q2 的约定用 `CallerChecks.refuseBanned`。
- 开发入账的输出 `balance` 是入账后、补发前的余额。
- 维度名没有消息：平台没有维度显示名的消息键（`party` 同样没有）。
- 单元测试 `TierPickTest`、`RewardSplitTest` 合为 `RewardRulesTest`（另含 `CardTiers`）；`DevCreditCheckTest` 覆盖启动检查与锁名。
- `FeedIT` 的"首页不读时态表全部版本"不用 `EXPLAIN` 渲染后的 SQL（应用模块拿不到平台测试夹具中的语句日志），改为比较 `pg_stat_user_tables` 的扫描计数：
  首页查询之后卡片表被扫描，而发布、档位、版本、资料、会话表一次也没有。
- 并发测试先顺序地开始并提交一次（`PlayItSupport.warmUp`）：新应用中第一次并发使用某个输入类型时 Jackson 在锁内建反序列化器，BlockHound 把等待报告为阻塞（500）。
  只是测试中的现象（生产没有 BlockHound，后来者等待即可），不是平台缺口。

**测试**（`backend/` 下，本地 PostgreSQL：`JABIZ_TEST_DB_URL=jdbc:postgresql://localhost:5432/jabiz_test`）：
- 单元：`ScoringTest`（含 jqwik 与独立实现对照）、`RewardRulesTest`（档位、抽成取整，jqwik）、`OptionShuffleTest`（jqwik）、`AnswersTest`、`AttemptRulesTest`（含 `PublicationRules.endsBy`）、
  `RegionsTest`（`overlaps`）、`QbRolesTest`、`MessagesTest`、`DevCreditCheckTest`。
- 集成：`PlayIT`、`AnswerSecrecyIT`、`SettlementIT`、`PendingIT`、`QuotaBudgetIT`、`PlayConcurrencyIT`、`FeedIT`、`PlayOwnerIT`；`SetupIT` 扩展（答题人的新授权、系统权限无人持有）。
- 场景回放 `scenarios/qb/play.yml`（U3 的超时自动提交属 Q5b，未列入）；`setup`、`accounts`、`publishing` 的快照因新授权使确定性编号顺延而更新（内容不变）。
- `./gradlew :quizbuks:check --max-workers=2`（含 `platformCheck`）通过。

**吞吐基线**（`PlayThroughputIT`，`@Tag("perf")`，`./gradlew :quizbuks:perfTest`）：64 个并发客户端对同一发布开始并提交，全部从同一商家钱包结算。
本地（同一台机器上的 PostgreSQL 16，进程内调用，BlockHound 开启，INFO 日志）：**约 7.6 次完成 / 秒；开始 p50 1 387 ms、p95 2 417 ms；提交 p50 4 593 ms、p95 5 739 ms**。
远低于 Q10 的目标（单发布 50 次 / 秒、提交 p95 < 1 秒）：发布锁与钱包锁内每次提交约 30 条语句（其中 `LEDGER_POST` 的维度与来源核对、审计、变更事件占一半左右），
在这台机器上约 120 ms。Q10 在生产规格上重测，按 D-Q5-10 的退路（降低过账成本 → 按发布预留预算 → 分批过账）决定。

**已知问题**：
- 平台的参数没有下限规则：负的抽成比例能被提出与发布，只在付款时被拒（422 `QB_FEE_RATE_INVALID`，见下）。
- 过了时限而未提交的会话在 Q5b 的任务之前一直占着名额与最高奖励的预算（只有 `QB_ATTEMPT_EXPIRE` 被调用时才释放）。**Q5b 已解决**：任务每分钟释放（至多晚一分钟加宽限）。
- 同一用户并发两次 `QB_VIEW` 时第二次可能因唯一约束失败（只影响浏览记录）。

### 评审后的修正（2026-10-11）

- **统计任务分块**：`QB_PUBLICATION_STATS_REFRESH` 按编号取出要统计的发布（至多 `PlayLimits.STATS_BATCH`），按 `quizbuks.stats.chunk-size`
  （缺省 500，至多 `STATS_CHUNK_MAX` = 1000 = 统计数据视图的 `maxWriteBatchSize`）切块，每块一个子流程 `QB_PUBLICATION_STATS_CHUNK`（模板
  `qb.system.publication-stats` 改为按 `ids` 统计），不再因一次写入超过批量而每次 422。要统计的发布：进行中、暂停、预算不足，以及**实际结束**（`QbPublicationCard.endedAt`，
  卡片随状态写入；名额或预算提前结束的也按结束时间）31 天内的。
- **统计时间**：新普通表 `qb_stats_run`（`QbStatsRun`，每个任务一行）每次运行记下运行时间；详情的 `statsAt` 取它，不再取"计数最后变化的时间"。行仍只在计数变化时写。
- **`QB_VIEW` 与详情一致**：只要求卡片存在且（进行中、在时间内、地区有交集，或本人有会话）；名额、预算、商家余额只决定能否开始。详情与档位模板同样按此显示，
  `canStart` 另加名额、预算、商家余额、本人尚无会话、**不是本人的发布**。
- **本人的发布**：新的只读实体 `QbPublicationOwner`（迁移中的视图 `qb_publication_owner`，卡片的编号与商家），数据视图 `urn:jabiz:dataset:me:QbPublicationOwner`
  限本人；首页排除本人的发布，详情的 `canStart` 为假。模板不必让调用方传自己的编号。
- **`QB_ATTEMPT_EXPIRE` 的输出**取锁内重读的会话（与提交竞争时不再报旧的 `IN_PROGRESS`）。
- **开发入账的启动检查**也拒绝受限的正式密钥（`rk_live_`）。
- **抽成比例的范围**：参数改为 `numeric(4,4)`（新安装：1 及以上在提出时即 422 `PARAM_VALUE_INVALID`）；付款时（提交、补发）比例不在 0 到 1（不含）之间即 422
  `QB_FEE_RATE_INVALID`，什么也不写。已有安装的参数种类仍是 `numeric(5,4)`（`QB_SETUP` 不改已有参数）：由付款时的检查兜底。平台缺口：参数没有上下限规则。
- 新测试：`StatsChunkIT`（块大小 2，6 个发布全部统计；提前结束的发布一个月后不再统计；无变化的运行也记下时间）、`FeedIT` 的本人发布与名额已满的详情、
  `PlayOwnerIT` 的暂停后有会话者仍可浏览、`SettlementIT` 的抽成比例（提出 1 被拒、负比例付款被拒）、单元测试的 `validRate` 与 `rk_live_`。
  `QB_ATTEMPT_EXPIRE` 与提交的竞争没有确定性的测试（输出取锁内的会话，逻辑见代码）。`./gradlew :quizbuks:check --max-workers=2` 通过（263 个测试）；
  `setup` 的快照因参数种类与说明改变而更新。

### 合并平台 16l / 16k 后的调整（D41，2026-10-11）

- 合并 `origin/1.2/quizbuks`（平台同步 #122：16l、16k、16e-2 与 QuizBuks 的 D41 适配），没有冲突。
- **谁能启动**：`QB_ATTEMPT_EXPIRE`、`QB_PENDING_SETTLE`、`QB_PENDING_PAY`、`QB_PUBLICATION_FUNDS_RESTORED`、`QB_PUBLICATION_FUNDED`、
  `QB_PUBLICATION_STATS_REFRESH`、`QB_PUBLICATION_STATS_CHUNK` 为 `internal()`（只以系统身份运行：事件、任务，或其下的子流程），`ProcessAccessIT` 的清单已加入。
  `QB_WALLET_DEV_CREDIT` 由持有 `qb.wallet.dev-credit` 的人运行，是开放流程（之前误标 `internal()`）。
- **开发入账不再以子流程调用 `QB_PENDING_SETTLE`**：在人发起的流程下调用 `internal()` 子流程会被拒（D41）。开发入账改为在同一事务中发布事件
  `qb.sponsor.settle-pending`（原 `qb.pending-settle.more` 改名：既是"钱进来了"，也是"还有可付的"），由订阅 `qb.pending-settle` 以系统身份运行补发。
  补发因此在入账提交之后、另一个事务中进行（测试的 `credit` 随即投递事件）。Q6 的 `QB_TOPUP_CONFIRM` 由入站 Webhook 的消费者以系统身份运行，
  可以照原计划以子流程调用 `QB_PENDING_SETTLE`，也可以发布同一事件；两条路都成立。
- 测试以 `PlayItSupport.systemRun`（同 `runAsSystem`，返回输出）运行 `QB_ATTEMPT_EXPIRE`；`PendingIT` 改为断言一次入账引出两次补发运行（每批一个事务）。
  场景无需以 `actor: system` 运行内部流程（补发经 `deliverEvents`，统计经 `runJob`），快照未变。`./gradlew :quizbuks:check --max-workers=2`（含 `platformCheck`，0 错误）通过。

### CI 的随机 500（PR #123，2026-10-11）

- **现象**：CI 上 `SetupScheduledIT` 的 `QB_SETUP` 得到 500，响应是 Spring 的缺省错误体（平台的 `GlobalExceptionHandler` 处理一切 `Exception`，
  所以抛出的是 `Error`）。本地单独与全量运行都通过；全量运行中另有一次 `SignInAuditIT` 同样失败，`PlayIT` 的事件投递也出现过一次（重试后成功）。
- **原因**：以临时的 BlockHound 回调打印栈（未提交）得到：事件循环线程结束一个被采样的 span 时，OpenTelemetry 的 `BatchSpanProcessor$Worker.addSpan`
  在批次已满时经容量为 1 的 `ArrayBlockingQueue`（`signal`）唤醒导出线程；导出线程等待下一批时也取这把锁。两者恰好相遇时事件循环 `Unsafe#park`，
  BlockHound 抛出 `BlockingOperationError`，请求以 500 结束。与 QuizBuks 的代码无关，任何请求都可能遇到，取决于时机（CI 的机器更慢更易遇到）。
  这只是线程间的交接，不是 I/O，生产中（没有 BlockHound）不出错。
- **修正**：测试中的 `QbBlockHoundIntegration`（`ServiceLoader` 加载，与平台的 `JabizBlockHoundIntegration` 并存）容许 `BatchSpanProcessor$Worker.addSpan`
  内的等待。回归测试 `TracingUnderBlockHoundTest` 让唤醒队列的锁被测试占住，事件循环必然等待：没有容许时稳定得到同样的 `Unsafe#park` 错误，有容许时通过；
  另一个测试确认 BlockHound 对同一线程池仍然生效。它经反射使用 OpenTelemetry 1.55 的内部字段（`worker`、`signal`、`spansNeeded`），升级时可能需要调整。
- 这一容许属于平台（见下表缺口 17），平台已在 #125 加入，应用中的这份已删除（同步平台的 PR）。

### Q5b：自动提交任务与逐项的定时上下架（2026-10-11）

分支 `1.2/quizbuks-5b-expire`（从 `1.2/quizbuks` 拉出，Q5a 与平台 16k 已合入）。

- **任务 `qb.attempt-expire`**（`play.PlayConfig`）：平台 16k 的逐项任务（决策 D42，`JobDefinition.forEach`），每分钟（UTC，`0 * * * * *`）。
  选取模板 `qb.system.attempts-due`（`IN_PROGRESS` 且 `deadline < 计划时刻 − 宽限 5 秒`，走 V6 的部分索引；`defaultSort` 截止时间、`key` 会话编号），
  项键为会话编号，每项以系统身份运行 `QB_ATTEMPT_EXPIRE {attemptId}`，各自一个事务，至多 `PlayLimits.EXPIRE_BATCH`（2 000）项（`quizbuks.attempt-expire.max-items` 可调低，测试用），其余由下一分钟接上；
  停机错过的分钟由下一次运行按截止时间补上。并发 1、项的锁等待 2 秒（平台缺省）。
- **`QB_ATTEMPT_EXPIRE`** 未改（Q5a 已与提交共用步骤）：先在锁外读会话，再取发布锁、重读会话，已提交或未到期即什么也不做，到期则按已保存答案评分并结算（钱包锁在发布锁之后）。
  它已是 `internal()`，满足逐项任务的启动检查。
- **偏离计划**：未建 `QB_ATTEMPT_EXPIRE_DUE`。计划把它列为"任务的选取"，但 16k 的逐项任务由平台以模板选取并对每项直接运行项流程，不需要另一个流程；它只在备用做法（扇出事件）中才有用。
- **Q4 的 `qb.publication-tick` 改为逐项任务**（`publishing.PublishingConfig`）：模板 `qb.system.publications-due`（`next_due_at ≤ 计划时刻`，走 V4 的部分索引）选出到期的发布，
  每项 `QB_PUBLICATION_ADVANCE`（本就在发布锁内重读并按时间迁移、`internal()`），批量由 50 改为 2 000（`PublishingLimits.TICK_BATCH`，`quizbuks.publication-tick.max-items` 可调低，测试用）。
  `QB_PUBLICATION_TICK` 与 `TickInput` / `TickOutput` 删去（消息、`ProcessAccessIT`、`PublicationOwnerIT` 同步）。Q4 的已知问题"一批的事务"随之解决。
- **竞争与幂等**（自查）：
  - 任务与提交：都先取发布锁再重读会话；提交只在 `now ≤ deadline + 宽限` 时有效，任务只处理 `now > deadline + 宽限`，二者在时间上互斥（按设计不会重叠，所以没有"按时提交与任务同时"的测试）；锁内重读另外保证同一会话的两次到期处理中后到者什么也不做。
  - 任务与保存进度：保存不取锁，但只在 `now ≤ deadline` 有效，任务至少晚 5 秒才选中；即使慢的保存在任务读取后提交，会话按版本号更新，任务一方得到版本过期，记为 `BUSY`，下一分钟按新的答案完成。
  - 同一会话在不同计划时刻（或两个实例在锁过期后）各跑一次：第二次在锁内读到已完成，什么也不做；同一计划时刻由平台的幂等键 `job:<名称>:<时刻>:<项键>` 保证至多一次。
  - 失败的项（例如数据损坏）只回滚它自己，写入 `sys_job_item_failure`，按连续次数退避重试，不挡住其他项；修好后下一次运行完成并记 `RECOVERED`。
  - 发布锁被在线请求长时间占用：该项 2 秒后 `BUSY`，不算失败、不退避，下一分钟再选中；其他发布上的项照常进行。
  - 锁序：每项只取"发布 → 商家钱包"，与提交相同；各项独立提交，不在一个事务中跨发布持锁，因此不会与在线提交形成"钱包 → 发布"的环。
- **测试**：
  - `AttemptExpireIT`（6）：到期会话按已保存答案完成并结算（未到期的不动；同一分钟重跑什么也不选；下一分钟完成后来的）；一次运行补上停机错过的分钟；`AttemptExpireBatchIT`（`quizbuks.attempt-expire.max-items = 2`）：先完成截止时间最早的（而非最先开始的）两项，`items.more`，余下的下一分钟；
    一项失败（`option_order` 被破坏）→ 运行 `PARTIAL`、该项回滚并记 `FAILED`，修好后下一次运行完成并记 `RECOVERED`；发布锁被占用（测试持有同名的 advisory 锁）→ 该项 `BUSY`、另一发布的项照常，
    下一分钟完成；任务与 12 个在线提交在同一商家的三个发布上同时进行 → 提交全部 200、任务无失败、无死锁，余额与账本一致；任务的项与另一次 `QB_ATTEMPT_EXPIRE` 同时处理同一会话 → 只结算一次（一笔过账、完成人数 1）；同时发出的本人提交得到 422，原因是已过时限与宽限，不是竞争。
  - `PublicationTickFailureIT` 改写：坏的日程行只使该项 `FAILED`（运行 `PARTIAL`），同一次运行中其他发布照常上架，第二次失败后按退避跳过（`DEFERRED`）。
  - `PublicationTickBatchIT` 改写：`max-items = 2`，三个到期发布按到期时间分两次运行（`items.more`）。
  - 场景 `qb/play.yml` 增加：U3 开始、保存一题后不提交 → `runJob qb.attempt-expire`（`items.succeeded: 1`）→ `EXPIRED`、得分 2、无奖励；再运行一次无项。快照随之更新（新增会话、计数，占位编号顺延）。
    `qb/publishing.yml` 的两次 `runJob qb.publication-tick` 加 `items.succeeded: 1`，快照未变。
  - 测试与实现同时写成（新的任务 Bean 之前无从运行），未先单独提交失败的测试。
- 未做：统计任务 `qb.publication-stats` 仍是一个根流程分块（不取锁，计算可重做，不需要逐项）。

