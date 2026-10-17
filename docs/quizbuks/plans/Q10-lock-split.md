# Q10a4 锁拆分：开始与完成各一把锁（计划，已确认）

需求方 2026-10-17 决定（P-10）：**先做锁拆分，再做平台 16p-2，每一步之后重测**。本计划只是锁拆分（应用内，不改平台、不改体验）。
**已确认**（需求方，2026-10-17）：第 10 节的建议全部接受（LS-1 至 LS-8，见该节"决定"列）；实施见文末的实施记录。
分支 `1.2/quizbuks-10a4-lock-split`，从 `1.2/quizbuks-10a3-ledger-cost` 拉出（后者尚未合并，但带着 16p-1 与最新的 QuizBuks 代码；它合并后本分支随之合并 `1.2/quizbuks`）。
只改 `.jabiz-app-paths` 中的路径。依据：`perf/Q10a-load-test.md` §6.5（提议与评审补充的条件）与 §9（现在的数字）；设计 `02-design.md` §3.3、§4.3；`Q5-answering.md` D-Q5-3、D-Q5-5、D-Q5-7；`Q4-publishing.md` D-Q4-5。

## 1. 目标与范围

- 现在开始与完成（提交、超时自动提交）用同一把发布锁 `qb.pub:<发布>`，单个发布的容量是 1 /（开始锁内 + 提交锁内）≈ 1 /（7.5 + 18.5 ms）≈ 38–40 次 / 秒（§9.2、§9.4）。
- 拆分后开始只取开始锁，完成只取发布锁，两段并行：容量变为 1 / max(开始锁内, 提交锁内) ≈ 1 / 18.5 ms，上限约 54 次 / 秒（§9.4 第 5 条）。
- 两者只在一行计数上相遇，所以计数拆成"开始的"与"完成的"两张表。下面证明每一项检查在读到另一侧的旧值时只会更严格。
- **保证不变**：一人一次、名额与预算从不超出、`ENDED` 只发生一次、每个奖励只付一次、钱包与账本同一事务、暂停之后没有新的开始。
- **不在本阶段**：平台 16p-2（之后另做）、R4、改变语句预算之外的锁内工作、App 与页面（模板的输出列不变）。

## 2. 设计

### 2.1 两把锁与命名

| 锁（`HoldLock.exclusive`） | 谁持有 | 保护什么 |
|---|---|---|
| `qb.pub-start:<发布>`（新，**开始锁**） | `QB_ATTEMPT_START`（新的开始，含被拒绝的）；改变已上架发布状态的流程（2.4） | 开始计数 `QbPublicationStartCounter`；"暂停等状态改变之后没有新的开始" |
| `qb.pub:<发布>`（不改名，**发布锁**） | `QB_ATTEMPT_SUBMIT`、`QB_ATTEMPT_EXPIRE`、`QB_PUBLICATION_COMPLETION_MOVE`；发布的全部状态与编辑流程（照旧） | 完成计数 `QbPublicationCounter`；发布、卡片、排程行与状态 |

命名的理由：
- **发布锁不改名**。它照旧是"这个发布"的锁：状态、编辑、审批结果、定时上下架、预算恢复与完成都在它下面，锁序文档、`PublicationEditing`、准入配置 `[qb.pub]`、测试与压测报告都按这个名字写。从热门路径上拿走的只是开始，所以只给开始一个新名字，改动最小。
- **`qb.pub-start` 用连字符，不用 `qb.pub:start:<发布>`**。平台按"锁名以 `族名:` 开头"判断锁与准入族的关系（`ProcessAdmission.checkLock`）：`qb.pub:start:…` 会被当成族 `qb.pub` 中另一个名字的锁（提交的准入键是 `qb.pub:<发布>`，开始若以 `qb.pub` 排队就与提交挤同一个队列，拆分就白做了）。
  `qb.pub-start` 是独立的族名（符合 `[a-z][a-z0-9._-]{0,47}`），`"qb.pub-start:…".startsWith("qb.pub:")` 为假，两族互不混淆。
- 常量只在一处：`PublicationMoves.START_LOCK_FAMILY = "qb.pub-start"`、`startLockName(发布)`（与现有 `LOCK_FAMILY`、`lockName` 并列），准入键与锁名由同一个方法生成（D45 第 1 条与实现说明⑥）。

### 2.2 计数拆成两张表

| 实体 / 表 | 字段 | 谁写（在哪把锁内） |
|---|---|---|
| `QbPublicationStartCounter` / `qb_publication_start_counter`（新） | `publicationId`（主键）、`starts`、`reservedTotal`（历来每个开始预占的最高奖励之和）、`version` | 只有开始（开始锁内）：`starts + 1`、`reservedTotal + maxReward` |
| `QbPublicationCounter` / `qb_publication_counter`（保留，成为完成一侧） | `publicationId`、`completions`、`passes`、`winners`、`released`（新：历来每个完成释放的预占之和）、`spent`、`version`；**删去** `starts`、`reserved` | 只有完成（发布锁内）：`completions + 1`、`passes`、`winners`、`released + maxReward`、`spent + reward` |

派生量（只能由两侧一起算）：**进行中** `n = starts − completions`；**预占** `reserved = reservedTotal − released`。

- 上架时（`PublicationEditing.goingOn`，发布锁内、与卡片同一事务）两行一起插入，全 0。
- 拆成两张表而不是一张表的两行：平台的普通实体是单一主键，各自的 `version` 做按已知版本更新的 CAS（D46 第 4 条）；两张表各自只有一个写者，审计、数据视图、权限都照普通实体写，不需要复合主键或"行种类"列。
- 预占用累计的两列而不是由 `n × maxReward` 推出：不把"每个开始预占的恰好是卡片的 `maxReward`"写死在读者里，两列本身也可核对（2.3 的不变式 I4，测试断言）。见问题 LS-3。

### 2.3 代数：读到旧的另一侧只会更严格

记卡片冻结的最高奖励为 `m`（`maxReward`，等于档位的最高奖励，所以每个奖励 `0 ≤ r ≤ m`），名额上限 `W`（`maxWinners`），预算上限 `B̂`（`budgetCap`）。
开始一侧 `S = (s, R)`，完成一侧 `C = (c, p, w, L, X)`（完成、合格、中奖、释放、已花）。开始用到的两个量：

- 名额量 `Q(S, C) = w + (s − c)`（中奖 + 进行中）；
- 预算量 `B(S, C) = X + (R − L)`（已花 + 预占）。

开始的检查（`AttemptRules.limitRefusal`，不变）：有上限时放行当且仅当 `Q < W` 且 `B + m ≤ B̂`。

| 事件 | `S` / `C` 的变化 | `Q` 的变化 | `B` 的变化 |
|---|---|---|---|
| 开始（放行） | `s + 1`，`R + m` | `+1` | `+m` |
| 完成（奖励 `r`，`δ = 1` 当 `r > 0`，否则 0） | `c + 1`，`w + δ`，`L + m`，`X + r` | `δ − 1 ≤ 0` | `r − m ≤ 0` |
| 状态流程、统计、核对、模板 | 不变 | 0 | 0 |

**引理 1（完成只会让两个量变小）**：每次完成 `ΔQ = δ − 1 ≤ 0`、`ΔB = r − m ≤ 0`。所以对同一个 `S`，若 `C₁` 早于 `C₂`（`C₂` 是 `C₁` 再加若干次完成），则 `Q(S, C₁) ≥ Q(S, C₂)`、`B(S, C₁) ≥ B(S, C₂)`：
**用旧的完成一侧算出的两个量只大不小，检查只会更严格。**

**引理 2（开始读到的开始一侧是确切的）**：开始在开始锁内读 `S`，所有改 `S` 的事务（只有开始）都持这把锁，所以从读到提交之间 `S` 不变；更新按读到的版本（CAS 兜底）。

**引理 3（任一快照中 `c ≤ s`、`L ≤ R`）**：完成会话 a 的事务读到了 a 的行，a 的行与 `S` 的 `+1` 由同一个开始事务原子地提交；能看见这次完成的快照（晚于它的提交）也看见那个开始（PostgreSQL 的快照：完成事务读到 a 时开始事务已提交并已离开活动事务表）。
所以**在一条语句（一个快照）中**读两侧，`n ≥ 0`、`reserved ≥ 0`。分两条语句读则不保证（可能先读到旧的 `S`、再读到含新完成的 `C`），见 2.4。

**定理（不超额）**：在每个提交点，有上限时 `Q ≤ W`、`B ≤ B̂`；因此中奖人数 `w ≤ Q ≤ W`（`n ≥ 0`），已花 `X ≤ B ≤ B̂`（`reserved ≥ 0`）。

证明（对提交归纳）：
- 开始：它在开始锁内读到确切的 `S`（引理 2）与某个快照的 `C_t`，放行即 `Q(S, C_t) < W`、`B(S, C_t) + m ≤ B̂`。到它提交时完成一侧是 `C*`（`C_t` 之后可能又有完成），由引理 1：
  提交后 `Q(S+1, C*) = Q(S, C*) + 1 ≤ Q(S, C_t) + 1 ≤ W`，`B(S+1, C*) = B(S, C*) + m ≤ B(S, C_t) + m ≤ B̂`。其间没有别的开始（开始锁）。
- 完成：`ΔQ ≤ 0`、`ΔB ≤ 0`，保持不等式。其他流程不改两侧。∎

与拆分前相比，只有"读到的 `C_t` 可能缺少此刻正在提交的完成"：拆分前开始要等这些完成放锁，看得见它们；现在看不见，所以**接近上限时可能多拒一个刚好能进的开始**（问题 LS-8），从不多放。读到的 `C_t` 只会早于它语句开始时已提交的状态，这一点与开始在哪一刻读无关，所以开始**不依赖** `QueryEntities.all` 把重读合成一条语句（平台只说"能合成时"），合成只为少往返。

**结束（`ENDED`）的判断**不读开始一侧：完成在发布锁内读确切的 `C`（完成之间由发布锁串行），`PublicationRules.endsBy(w, X)` 与 `PublicationMoves.moveAfter` 照旧，状态只能从 `RUNNING` 迁到 `ENDED` 一次。

**结束之后没有新的开始（不需要开始锁）**：设结束的那次完成之后完成一侧为 `C_e`（`w_e`、`X_e`），任何开始读到的 `C_t` 要么就是 `C_e` 或更晚，要么早于它：
- 名额（`QUOTA`，`w_e ≥ W`）：`Q(S, C_t) ≥ Q(S, C_e) = w_e + (s − c_e) ≥ w_e ≥ W`（引理 1、引理 3；`C_t` 更晚时 `w` 只增、仍 `≥ W`），拒绝；
- 预算（`BUDGET`，`B̂ − X_e < m`）：`B(S, C_t) + m ≥ B(S, C_e) + m ≥ X_e + m > B̂`，拒绝。

所以完成使发布结束时，即使开始读到的卡片仍是 `LIVE`，限额检查也必然拒绝它。**预算不足（`FUNDS_SHORT`）**本来就是软检查（D-Q5-5、Q10a R1：开始在锁外读钱包，并发多出的人完成后记为待支付）：
与这次完成同时的开始若读到 `LIVE` 而被放行，其奖励在完成时成为待支付——与"开始之后、提交之前余额用完"的现有情形相同，账与待支付队列照旧一致。
因此**完成时的移动子流程不取开始锁**——这是与 §6.5 的条件不同的一处（2.7），去掉了"每个发布一次、持着发布锁与钱包锁排在开始队列后面"的停顿，也让锁序简单（2.5）。

### 2.4 谁一条语句读两侧、谁持两把锁

| 读者 / 写者 | 读什么 | 怎样保证一致 |
|---|---|---|
| `QB_ATTEMPT_START`（锁内的重读） | 本人会话、卡片、`S`、`C` | 持开始锁：`S` 确切；`C` 为任一快照即可（2.3）。仍写成一个 `QueryEntities.all`（一条语句，往返最少） |
| `QB_ATTEMPT_SUBMIT` / `QB_ATTEMPT_EXPIRE`（锁内的重读） | 会话、`C`、卡片 | 持发布锁：`C` 确切；**不读 `S`**（结束与预算不足只看 `w`、`X` 与钱包） |
| `QB_PUBLICATION_COMPLETION_MOVE` | 发布、排程行、卡片 | 持发布锁（重入）；不读计数（`winners`、`spent` 由输入给出） |
| 模板 `qb.app.feed`、`qb.app.publication`（`canStart`）、`qb.sponsor.publications`、`qb.admin.publications` | `S` 与 `C`（派生 `n`、`reserved`，或分别显示 `starts`、`completions`） | **一条 SQL 语句**中 JOIN 两表（引理 3：不出现负的进行中，`starts ≥ completions`）；不取锁 |
| 通用后台的默认数据视图（两个实体各一个，只读） | 各自一侧的原始列 | 只显示列，不算派生量；不需要一致 |

**两把锁都取的**（发布锁 → 开始锁）：已上架的发布改变状态的流程，使"状态改变提交之后没有新的开始、之前开始的都已计入"：
`QB_PUBLICATION_PAUSE`、`QB_PUBLICATION_RESUME`、`QB_PUBLICATION_ADVANCE`（逐项任务的每项）、`QB_PUBLICATION_FUNDED`（`QB_PUBLICATION_FUNDS_RESTORED` 在一个事务中逐个调用它）。
严格需要的只有暂停（`LIVE / SCHEDULED / FUNDS_SHORT → PAUSED`；`PlayConcurrencyIT.noStartGetsInAfterAPause` 断言）：到期结束由开始自己按时间判断，放回 `LIVE` 的移动最多让同时的开始被拒一次。
其余三个也取，好让规则只有一句——"改变已上架发布状态的流程持两把锁，只有完成一侧的移动例外（2.3 证明）"；它们少而短（每次 +1 条语句，至多等一个开始的锁内时间），见问题 LS-4。
不取开始锁的：编辑、删除、提交审核、撤回、审批结果（草稿与待审核的发布没有卡片，开始一律 404；审批结果插入卡片与两行计数同一事务）、提交、超时、完成移动。

### 2.5 锁序与无死锁

新的锁序（全局的全序）：

```
qb.account:<用户> → qb.quiz:<模版>（共享）→ 按发布编号升序：[ qb.pub:<发布> → qb.pub-start:<发布> ] → jabiz.approval:* → qb.wallet:SPONSOR:<商家>
qb.wallet:TAKER:<用户>、qb.journal、qb.receipt:…、payout 锁：照旧独立（不与以上任何一把同时持有）
```

逐个流程（只列与发布、开始、钱包锁有关的；其余不变）：

| 流程 | 依次取的锁 | 是否符合全序 |
|---|---|---|
| `QB_ATTEMPT_START` | `qb.pub-start:p` | 只持一把 |
| `QB_ATTEMPT_SUBMIT` / `QB_ATTEMPT_EXPIRE` | `qb.pub:p` →（奖励 > 0）`qb.wallet:SPONSOR:s` →（结束或预算不足）`qb.pub:p`（重入，不等待） | 是；**不取开始锁** |
| `QB_PUBLICATION_COMPLETION_MOVE` | `qb.pub:p`（重入） | 是 |
| `QB_PUBLICATION_PAUSE` / `RESUME` | `qb.pub:p` → `qb.pub-start:p` | 是 |
| `QB_PUBLICATION_ADVANCE`（逐项任务，每项一个事务） | `qb.pub:p` → `qb.pub-start:p` →（只在审核过期时，`WithdrawApproval.when`）`jabiz.approval:qb.publication:p` | 是（评审后补：实施时 `WithdrawApproval.of` 每项都取审批锁，已改为只在需要时取） |
| `QB_PUBLICATION_FUNDS_RESTORED`（一个事务，按编号升序逐个 `FUNDED`） | `qb.pub:p₁` → `qb.pub-start:p₁` → `qb.pub:p₂` → `qb.pub-start:p₂` → … | 是（编号升序） |
| `QB_PUBLICATION_SUBMIT` | `qb.quiz:q`（共享）→ `qb.pub:p` | 是 |
| `QB_PUBLICATION_SAVE` / `DELETE` / `WITHDRAW` / `APPROVAL_RESULT` | `qb.pub:p` | 是 |
| `QB_PENDING_SETTLE` / `QB_PENDING_PAY`、充值、开发入账 | `qb.wallet:SPONSOR:s` | 只持一把（从不再取发布或开始锁） |
| 转账、用户钱包同步（Q6b） | `qb.wallet:TAKER:u`（重算多个用户时按编号升序） | 独立 |
| 统计刷新 `qb.publication-stats`、每日核对 `qb.reconcile`、后台与财务模板、Q9c / Q9d 报表与导出（导出取 `qb.journal`） | 不取发布、开始、钱包锁 | 只读（或只写自己的表），不参与 |

**无死锁的论证**：
1. 所有取多把锁的流程都按上面的全序取（表中逐个核对）；按全序取锁的事务之间不会成环（经典的有序加锁）。
2. 开始锁之后只可能再取全序中更后的锁：开始只持它；状态流程在 `qb.pub-start:p` 之后只读写，唯一的例外是 `ADVANCE` 撤回过期的审核时取平台审批的案件锁 `jabiz.approval:*`（在全序上它排在这一对之后；那时发布仍待审核、没有卡片，也就没有开始在等开始锁；持审批锁的流程——提交审核、撤回、审批结果——从不再取开始锁）；`FUNDS_RESTORED` 之后取的是编号更大的发布的锁，仍在全序上升。
3. 持钱包锁之后再取的只有同一发布的发布锁（完成移动），而那把锁本事务已经持有（重入，不等待）；没有流程持钱包锁去等开始锁——这正是完成移动不取开始锁的另一个好处（若要取，就违反 `qb.pub-start → qb.wallet` 的顺序）。
4. 行锁：开始只更新 `S` 行与插入会话，完成只更新 `C` 行、自己的会话、钱包与（移动时）发布、卡片、排程行；状态流程更新发布、卡片、排程行（都在发布锁内，与完成串行）；保存答案只按版本更新自己的会话、不等任何咨询锁。
   没有事务持着行锁去等一把被"等这行的事务"持有的咨询锁，所以行锁与咨询锁也不成环。账本的平台锁（`LEDGER_POST` 的共享锁、科目锁）照旧在钱包锁之后，与开始锁无关。
5. 测试：2.6 的并发 IT 混合开始、提交、超时、暂停 / 恢复、定时上下架与预算恢复，断言没有死锁（PostgreSQL 的 `40P01` 会成为 500）与等锁超时。

### 2.6 准入（D45）

一把锁一个族，族名与 `HoldLock` 的名称由同一个方法生成：

| 族 | 键（= 锁名） | 声明者 | 配置（`application.yml`） |
|---|---|---|---|
| `qb.pub-start`（新） | `qb.pub-start:<发布>` | `QB_ATTEMPT_START`（继续答题也排队，16o A-7 照旧） | `"[qb.pub-start]": { concurrency: 2, max-queue: 50, max-wait: 2s }` |
| `qb.pub` | `qb.pub:<发布>` | `QB_ATTEMPT_SUBMIT`、`QB_ATTEMPT_EXPIRE`（照旧） | 照旧 |

- 平台的告警（`HoldLock` 取到与准入键同族而不同名的锁）不会出现：开始在族 `qb.pub-start` 中只取同名的锁；提交与完成移动只取 `qb.pub:<发布>`。状态流程照旧不声明准入。
- **全局许可**：所有键合计至多 `max-active`（缺省半个连接池 = 5）。拆分后一个热门发布至多同时占 4 个许可（开始 2 + 完成 2），其他发布的答题只剩 1 个（原来 3 个）。见问题 LS-7 与风险。
- `Retry-After` 按族估计，开始与提交各用各的持有时间；App 不需要改（503 `PROCESS_BUSY` 的处理与键无关）。

### 2.7 与 §6.5 条件的对照

| §6.5 的条件 | 本计划 |
|---|---|
| 开始在开始锁内读自己的一行，并以一条语句读完成的一行 | 前半照做；后半不是正确性所需（2.3：任一快照的 `C` 都只更严格），仍合成一条只为少往返 |
| 其他读计数的地方一条语句读两侧或持两把锁 | 照做（2.4）；全部读者见第 4 节 |
| 改变发布状态的流程同时持两把锁（锁序 `qb.pub` → `qb.pub-start`） | 照做（2.4、2.5），只对已上架发布的状态流程 |
| **使发布移动的那次完成也取开始锁** | **不取**：结束由限额代数保证没有新的开始，预算不足本是软检查（2.3）。省掉每个发布一次的停顿，且不必在持钱包锁时取开始锁（2.5 第 3 条）。问题 LS-5 |
| 被拒绝的开始与超时自动提交也取锁 | 照旧：被拒的开始取开始锁，超时取发布锁，它们的时间计入各自的锁 |

## 3. 迁移

`backend/quizbuks/src/main/resources/db/migration/V10__quizbuks_counter_split.sql`（应用迁移，线 1.2 是最新的线，允许新增迁移）：

```sql
CREATE TABLE qb_publication_start_counter (
    publication_id uuid           PRIMARY KEY REFERENCES entity_registry (entity_id),
    starts         numeric(9, 0)  NOT NULL CHECK (starts >= 0),
    reserved_total numeric(19, 0) NOT NULL CHECK (reserved_total >= 0),
    version        bigint         NOT NULL
);
ALTER TABLE qb_publication_counter ADD COLUMN released numeric(19, 0) NOT NULL DEFAULT 0 CHECK (released >= 0);
-- 现有的行：卡片的 max_reward 即每个开始预占、每个完成释放的数额
INSERT INTO qb_publication_start_counter (publication_id, starts, reserved_total, version)
SELECT c.publication_id, c.starts, c.reserved + c.completions * k.max_reward, 1
FROM qb_publication_counter c JOIN qb_publication_card k ON k.publication_id = c.publication_id;
UPDATE qb_publication_counter c SET released = c.completions * k.max_reward
FROM qb_publication_card k WHERE k.publication_id = c.publication_id;
-- 核对后删去旧列（DO 块：任一不符即 RAISE EXCEPTION，整个迁移回滚）
ALTER TABLE qb_publication_counter DROP COLUMN starts, DROP COLUMN reserved, ALTER COLUMN released DROP DEFAULT;
```

- **数据**：迁移后每个发布 `reservedTotal − released` 恰好等于旧的 `reserved`（按构造），`starts` 原样搬过去，其余列不动。
  迁移中的 DO 块先核对旧数据的不变式——每个计数行都有卡片、`starts ≥ completions`、`reserved = (starts − completions) × max_reward`——任一不符即失败（整个迁移在一个事务中回滚，库保持 V9）。
  不符说明已有计数的缺陷，应先查清而不是带进新表（问题 LS-6）。
- **索引**：两表都以 `publication_id` 为主键；读者都按发布编号连接（模板 JOIN 卡片或发布、流程按主键读），不需要其他索引。
- **只追加 / 时态**：两张都是普通表（不进时态，同原计数行的理由：每次答题一个版本太多），不需要 `jabiz_protect_append_only`、封存或 `INTEGRITY` 检查；
  外键指向 `entity_registry`（同原表）。迁移不写审计（与其他迁移相同：是表结构的重组，值逐个保持）；`sys_audit_record` 中旧的 `QbPublicationCounter` 记录保留原样（只追加，含 `starts`、`reserved` 的旧值）。
- **向后**：PostgreSQL 的 DDL 在 Flyway 的事务中，失败即整体回滚。QuizBuks 未上线、单实例部署：停旧版 → 迁移 → 起新版；旧 jar 不能对新表结构运行（它会写已删的列），新 jar 也不能对旧结构运行（启动检查报列缺失）。
  若要不停机的先扩后收（V10 只加、V11 再删），见问题 LS-6；本计划建议一步完成。线 1.2 上没有其他应用分支依赖这两张表。
- 元数据：`QbPublications.START_COUNTER_ENTITY`（`physicalTable("qb_publication_start_counter")`，主键 `publicationId`，`asReference(PUBLICATION)`）、`COUNTER_ENTITY` 改列；
  默认数据视图各一个（`PublishingConfig`，与现在的计数视图相同的权限与只读策略）；消息 `entity.QbPublicationStartCounter[.<字段>]` 与 `entity.QbPublicationCounter.released` 三语，删去 `entity.QbPublicationCounter.starts|reserved`。

## 4. 读计数的地方（全部，`grep -rn "QbPublicationCounter|qb_publication_counter|PlaySteps.counter|reserved"`）

| 位置 | 现在读 | 改为 |
|---|---|---|
| `play/AttemptProcesses.start`（锁内的 `QueryEntities.all`） | 会话、卡片、计数 | 会话、卡片、`S`、`C`（同一个 `all`）；在开始锁内；只更新 `S` |
| `play/AttemptFinishing.steps`（锁内的 `QueryEntities.all`）与 `record` | 会话、计数、卡片；更新 `completions`…`reserved`、`spent` | 会话、`C`、卡片；更新 `completions`、`passes`、`winners`、`released + m`、`spent`（不再改 `reserved`） |
| `play/PlaySteps.counter` / `counts` | 一行 | `startCounter(…)` 与 `counter(…)` 两个查询；`counts(S, C)` 算出 `AttemptRules.Counts`（`reserved = reservedTotal − released`），`Counts` 与 `limitRefusal` 不变 |
| `publishing/PublicationEditing.goingOn` | 插入一行 | 插入两行 |
| `queries/qb/app/feed.sql` | `n.spent + n.reserved`、`n.winners + n.starts − n.completions` | JOIN `QbPublicationStartCounter s`：`n.spent + s.reservedTotal − n.released + maxReward ≤ budgetCap`、`n.winners + s.starts − n.completions < maxWinners`；`completions` 照旧 |
| `queries/qb/app/publication.sql`（`canStart`、`completions`） | 同上 | 同上（一条语句） |
| `queries/qb/sponsor/publications.sql`（商家后台列表） | `starts`、`completions`、`passes`、`winners`、`spent` | `starts` 取自 `S`（LEFT JOIN，草稿为 0），其余照旧 |
| `queries/qb/admin/publications.sql`（管理员后台：审核与支持，后台扩展 `admin-extension` 经它读） | 同上 | 同上 |
| `PublishingConfig` 的默认数据视图（通用后台） | 计数一个视图 | 两个视图 |

**确认不读计数、不需要改的**（grep 无命中，逐个看过）：统计刷新 `qb.system.publication-stats` 与 `play/PublicationStats`（读会话的提交时间）；每日核对 `qb.system.recon-*`（钱包、账本、充值、转账）；
Q9c 的 `qb.admin.monthly`、`balances`、`finance-summary`、`publication-funds`、`attempt-rewards`；Q9d 的 `qb.system.journal-*` 与导出（只读账本）；`qb.system.attempts-due`、`publications-due`；
三个前端（只用上面模板的输出列，列名与类型不变：商家后台 `publishing`、后台扩展的发布页、App 的首页与详情），OpenAPI 与公开模板快照不变。

## 5. 代码改动

| 文件 | 改动 |
|---|---|
| `publishing/PublicationMoves.java` | `START_LOCK_FAMILY`、`startLockName`；Javadoc 写明完成移动不取开始锁（2.3） |
| `publishing/PublicationEditing.java` | `startLockName` 的实现（与 `lockName` 并列）；`lockAndLoad` 的状态流程变体与 `lockStarts` 步骤（发布锁之后、读取之前，D46 第 5 条）；`goingOn` 插入两行 |
| `publishing/PublicationLifecycle.java` | 暂停、恢复、`ADVANCE` 取开始锁 |
| `publishing/PublicationFunds.java` | `FUNDED` 取开始锁 |
| `publishing/QbPublications.java`、`PublishingConfig.java` | 新实体与默认数据视图，`COUNTER_ENTITY` 改列 |
| `play/AttemptProcesses.java` | 准入族 `START_LOCK_FAMILY`、开始锁、重读 `S` 与 `C`、只更新 `S` |
| `play/AttemptFinishing.java`、`play/PlaySteps.java` | 见第 4 节 |
| `resources/application.yml` | `[qb.pub-start]` 的准入配置 |
| `resources/db/migration/V10__quizbuks_counter_split.sql` | 第 3 节 |
| 4 个模板、`messages_{en,ja,zh}.properties` | 第 4 节、第 3 节 |
| 文档 | `02-design.md` §3.3（两张计数表）、§4.3（锁表、锁序、准入、锁内语句）；`backend/quizbuks/CLAUDE.md` 的锁序与准入两段；`03-plan.md` Q10 行；压测报告新增 §10；本计划的实施记录 |

## 6. 测试

### 6.1 语句预算（`AttemptStatementBudgetIT`）

- 开始：共 12、**开始锁内** 5（锁内一段从取 `qb.pub-start` 之后到 `COMMIT`；重读 1 条仍含 `S`、`C`、卡片、会话）；断言开始的语句中没有 `qb.pub:` 的锁。
- 付奖的提交：共 20、发布锁内 14（不变）；断言其中没有 `qb.pub-start` 的锁。
- 新增：**结束发布的那次完成**的确切语句数（现在未断言；预计 14 + 约 7，子流程不取开始锁）；**暂停**：比现在多 1 条（开始锁）。
- 预算的数字若与上面不同，以实测为准写入并在实施记录中说明。

### 6.2 并发 IT（新 `LockSplitIT`，连接真实 PostgreSQL；BlockHound 照常开）

1. **两段真的并行**：测试的连接以 `pg_advisory_xact_lock` 持住 `qb.pub:<p>`（同 `PlayConcurrencyIT` 的写法），开始仍 200 且计入 `S`；反过来持住 `qb.pub-start:<p>`，提交仍 200；两者都持住时暂停等待，放开后完成。
2. **同时开始与提交，名额恰好**：`maxWinners` K = 20，全部答对；64 个答题人，一半先开始、之后与另一半的开始交错提交，被拒 `QUOTA_FULL` 的人稍后重试直到发布 `ENDED`。
   断言：中奖恰好 K、放行的开始恰好 K（每个进行中的会话占一个名额且都会中奖）、`ENDED`（`QUOTA`）恰好一次（发布版本中 `ENDED` 一次、移动子流程的操作记录一次）、之后的开始 422。
3. **预算恰好**：同上，以 `budgetCap` = K × m（无名额上限），断言 `spent` = K × m ≤ `budgetCap`、`ENDED`（`BUDGET`）一次。
4. **混合的奖励**：档位使一部分人得 0、一部分得低于 m 的奖励，名额与预算都设，200 人随机开始、提交、放弃（由超时任务 `JobRunner.run` 结束）；每一轮之后断言不变式：
   I1 `w ≤ W`；I2 `X ≤ B̂`；I3（一条语句读）`s ≥ c`；I4 `R − L = (s − c) × m`；I5 `s` = 会话数、`c` = 已结束的会话数、`w` = 奖励 > 0 的会话数、`X` = 奖励之和；钱包与账本逐笔一致（`assertBooksBalance`）。
5. **状态流程不死锁**：在 4 的负载中并发地暂停 / 恢复（商家）、运行定时上下架的项、补发待支付触发预算恢复（`deliverPending`）；断言没有 500（死锁 `40P01`）、没有等锁超时，暂停提交之后没有放行的开始（以开始的 `startedAt` 与暂停的操作时间比较，并重放 `noStartGetsInAfterAPause`）。
6. **结束时的开始**：一个开始在测试持住 `qb.pub-start` 时排在锁上，另一事务中最后一个名额的完成使发布结束；放开后这个开始以 `QUOTA_FULL` 被拒（读到的卡片可能仍是 `LIVE`，限额拒绝它）。
7. 原有的改动：`PlayConcurrencyIT.aStartAdmittedOnABalanceSpentWhileItWaitsEndsPending` 持开始锁（原来持发布锁）；`PlayAdmissionIT` 断言开始的键等于 `qb.pub-start:<p>`、提交与超时的键等于 `qb.pub:<p>`、两族的配置都绑定，
   排队的用例按族分别持锁（持发布锁时开始不再排在提交后面）；`QuotaBudgetIT`、`PlayIT`、`PublicationIT`、`PublicationReviewSettingsIT`、`FeedIT` 的计数断言改读两张表（`PlayItSupport.counter` 返回合并的视图：一条语句 JOIN）。

### 6.3 代数的性质测试（jqwik，`play/CounterSplitProperties`，纯 Java、不连库）

- **单调**：任意 `S`、`C`、上限与一次完成（`0 ≤ r ≤ m`），`limitRefusal(S, C 之后) != null ⇒ limitRefusal(S, C 之前) != null`（旧的完成一侧只更严格）。
- **交错的模型**：随机生成事件序列（开始在某个过去的 `C` 版本上做检查、完成按随机奖励提交、放弃），以 `AttemptRules.limitRefusal` 与 `PublicationRules.endsBy` 驱动，断言每一步 I1–I4 与"结束之后不再放行"。
- `AttemptRulesTest` 照旧（`Counts` 不变）。

### 6.4 迁移

- `CounterSplitMigrationIT`：在独立 schema 中以 Flyway 迁到 V9，插入几种计数行（未开始、进行中、已结束、预算用完），再迁到 V10，断言两张表的值与 `reservedTotal − released` = 旧的 `reserved`；
  一行不符合旧不变式时迁移失败、库仍是 V9。

### 6.5 场景回放

- `play.yml`、`publishing.yml` 的 `entities` 加上 `QbPublicationStartCounter`。快照的变化只在计数：`QbPublicationCounter` 不再有 `starts`、`reserved`，多出 `released`，`version` 只随完成增加；
  新增 `QbPublicationStartCounter` 的行（`starts`、`reservedTotal`、`version`）。流程的输出、会话、钱包、账本、事件不变（编号 `#uuid:n` 不变：新行的主键是发布编号）。以 `-Dscenario.update-snapshots=true` 更新并逐行核对差异。

### 6.6 其他

- `./gradlew :quizbuks:check`（含 `platformCheck`）、`PlayThroughputIT`（`perfTest`，进程内对照）、两个 SPA 与扩展的检查（不应有变化）、对 jar 的 e2e 全部、`tools/check-app-paths.sh origin/1.2/platform`。

## 7. 重测（报告 `perf/Q10a-load-test.md` 新增 §10）

- 设置照 §9.1（同类机器、连接池 10、新库、暖机实例、读者限速 24 / 85 与连续、客户端时限 5 秒、照 App 重试忙、`LOAD_METRICS=yes`），另加**同日的对照**（`1.2/quizbuks-10a3-ledger-cost` 的头）。
- 闭环 64；开环每秒 30、40、50、60 人。报告：稳定吞吐、提交与开始的 p95（每次调用 / 答题人看到的）、首页倍数、两族各自的许可持有、等咨询锁的连接（按锁分开）、503（按族）、语句数、CPU。
- 锁内时间的分解（开语句日志）分开开始锁与发布锁；压测后核对两张计数表与会话一致（工具新增，`LOAD_DB_URL` 时）。
- 另跑一次连接池 16（`max-active` 8）对照问题 LS-7。

## 8. 预期收益

- 按 §9.4 第 5 条：开始的约 7.5 ms 离开提交的锁，**上限约 54 次 / 秒**（1 / 18.5 ms）；两段并行使 CPU 从约 50 % 升到约 65–75 %，读计数多一个 JOIN，**本机预估约 45–50 次 / 秒**（每秒 50 人仍无余量）。
- 与 §6.5 相比少了"结束时持锁排在开始队列后"的停顿（每个发布一次，影响小，但锁序更简单）。
- 之后的 16p-2（提交锁内约 18.5 → 12–13 ms）与本拆分一起：上限约 75–80，**本机预估约 60–70 次 / 秒**，每秒 50 次约 1.2–1.4 倍的余量（§9.4 第 6 条）。

## 9. 风险

| 风险 | 影响 | 对策 |
|---|---|---|
| 接近上限时多拒（2.3：看不见正在提交的完成） | 少数答题人看到"名额 / 预算已满"，稍后又能开始 | 首页本来就有同样的时差；App 文案不变；6.2 的 2、3 断言最终恰好用满（问题 LS-8） |
| 热门发布占 4 个全局许可 | 其他发布同时只剩 1 个许可，排队或 503 | 重测时另跑连接池 16；容量规则写入报告（问题 LS-7） |
| CPU 成为限制 | 预估只到 45–50 次 / 秒 | 照 §8.3 第 4 条的容量规则测；本来就计划之后做 16p-2 |
| 完成移动不取开始锁所依赖的两点（限额代数、预算不足是软检查）将来被改变 | 若预算不足改成硬检查（如开始时在钱包上预占），结束 / 预算不足之后可能有开始 | 设计 4.3 与 `PublicationMoves` 的 Javadoc 写明前提；6.2 的 6 与性质测试守住；改动时须重新论证（且不能在持钱包锁时取开始锁） |
| 迁移遇到不符合不变式的旧数据 | 启动失败 | 迁移在事务中整体回滚、报出发布编号；开发与压测库可重建 |
| 两行不同时存在（缺陷） | 开始或完成 500（现有的"上架的发布有计数"的断言） | 两行在同一事务插入；`PublicationIT` 断言 |
| 读者漏改，分两条语句读两侧 | 显示负的进行中 | 第 4 节逐个列出；模板都是一条语句 |

## 10. 待确认问题（已确认）

| 编号 | 问题 | 建议 | 决定（需求方 2026-10-17） |
|---|---|---|---|
| LS-1 | 锁名 | 发布锁 `qb.pub:<发布>` 不改名（完成与状态）；新开始锁 `qb.pub-start:<发布>`（连字符，独立的族，2.1） | 接受 |
| LS-2 | 两张表还是一张表两行 | 两张表（单一主键、各自的版本 CAS、无复合键） | 接受 |
| LS-3 | 预占的表示 | 两列累计（`reservedTotal`、`released`），不由 `n × maxReward` 推出；测试断言两者相等 | 接受 |
| LS-4 | 哪些状态流程取开始锁 | 暂停、恢复、定时上下架、预算恢复都取（一句规则，成本可忽略）；最少的做法只有暂停 | 接受 |
| LS-5 | 完成移动（结束、预算不足）是否取开始锁（§6.5 原说要取） | **不取**（2.3 的证明；省掉停顿、保持锁序简单） | 接受 |
| LS-6 | 迁移一步完成还是先扩后收；旧数据不符合不变式时 | 一步完成（未上线、单实例）；不符即迁移失败，先查清 | 接受 |
| LS-7 | 热门发布占 4 个全局许可 | 先用现有配置测（可比），另跑连接池 16 的对照，按结果在报告中给出建议的池与 `max-active`；开始族的 `concurrency` 保持 2 | 接受 |
| LS-8 | 接受接近上限时偶尔多拒一个开始 | 接受（从不多放；首页已有同样的时差） | 接受 |

## 11. 预估

约 **3 天**：迁移、实体与流程 0.75 天；模板、消息与文档 0.5 天；测试（并发 IT、性质测试、迁移 IT、预算、场景）1 天；重测与报告 0.75 天。

## Critical Files for Implementation

- `backend/quizbuks/src/main/java/com/jabiz/quizbuks/play/AttemptProcesses.java`、`AttemptFinishing.java`、`PlaySteps.java`、`AttemptRules.java`
- `backend/quizbuks/src/main/java/com/jabiz/quizbuks/publishing/PublicationMoves.java`、`PublicationEditing.java`、`PublicationLifecycle.java`、`PublicationFunds.java`、`QbPublications.java`、`PublishingConfig.java`
- `backend/quizbuks/src/main/resources/queries/qb/app/feed.sql`、`app/publication.sql`、`sponsor/publications.sql`、`admin/publications.sql`
- `backend/quizbuks/src/main/resources/application.yml`、`db/migration/V10__quizbuks_counter_split.sql`（新）
- 测试：`it/AttemptStatementBudgetIT.java`、`it/QuotaBudgetIT.java`、`it/PlayConcurrencyIT.java`、`it/PlayAdmissionIT.java`、`it/PlayItSupport.java`、`resources/scenarios/qb/play.yml`、`publishing.yml`
- 平台参考：`backend/runtime/.../process/admission/ProcessAdmission.java`（`checkLock`）、`AdmissionGate.java`（全局许可）、`.../process/steps/QueryEntities.java`（`all`）、决策 D45、D46

## 实施记录（Q10a4，2026-10-17，分支 `1.2/quizbuks-10a4-lock-split`）

计划已由需求方确认（LS-1 至 LS-8 全部按建议）。先合并 `1.2/quizbuks-10a3-ledger-cost`（评审修正到 `506e1b0`），其 PR #166 合入 `1.2/quizbuks` 后再合并 `origin/1.2/quizbuks`（普通合并提交）。只改 `.jabiz-app-paths` 中的路径。

- **代码**（照第 5 节）：`PublicationMoves.START_LOCK_FAMILY` / `startLockName`（实现在 `PublicationEditing`，与 `lockName` 并列）；`PublicationEditing.lockStarts`（发布锁之后、读取之前）与 `lockStatesAndLoad`（暂停、恢复）；
  `ADVANCE`、`FUNDED` 在发布锁之后取开始锁；`goingOn` 在同一事务插入两行计数；`QbPublications.START_COUNTER_ENTITY` 与默认数据视图（与完成一侧相同的权限与 `processOnlyWrites`），`COUNTER_ENTITY` 去掉 `starts`、`reserved`，加 `released`；
  `QB_ATTEMPT_START` 以 `qb.pub-start` 排队、取开始锁、锁内一个 `QueryEntities.all` 读会话、卡片与两侧计数，只更新开始一侧；`AttemptFinishing.record` 写 `released + maxReward`（不再改 `reserved`），不读开始一侧；
  `AttemptRules.counts(…)`（纯 Java，由两侧算出不变的 `Counts`）；`application.yml` 的 `[qb.pub-start]`；四个模板在一条 SQL 中 JOIN 两表（输出列与类型不变，OpenAPI 与公开模板快照不变）；三语消息。
  `PublicationMoves` 的 Javadoc 写明完成移动不取开始锁所依赖的两点（2.3）。
- **迁移** `V10__quizbuks_counter_split.sql`：先以 DO 块核对旧行（有卡片、`starts ≥ completions`、`reserved = (starts − completions) × max_reward`），任一不符即 `RAISE EXCEPTION`、整体回滚；
  `reserved_total` 写为 `starts × max_reward`、`released` 为 `completions × max_reward`（核对之后与计划中 `reserved + completions × max_reward` 相等，写法更直接）。
- **测试**（第 6 节全部）：
  - `AttemptStatementBudgetIT`：开始共 12 / 开始锁内 5、付奖的提交 20 / 发布锁内 14（不变）；新增**使发布结束的完成** 29 / 23（计划预估 14 + 约 7：移动子流程实为 9 条——操作记录、重取发布锁、发布、排程行、卡片、发布的当前版本、新版本与审计、排程行、卡片）与**暂停** 12 / 9（含开始锁 1 条）。
    `SqlStatementLog` 另记录每条锁语句绑定的锁名，测试断言开始只取 `qb.pub-start:<p>`、提交取 `qb.pub:<p>` 与钱包锁、结束的完成另重取 `qb.pub:<p>`（从不取开始锁）、暂停依次取 `qb.pub:<p>`、`qb.pub-start:<p>`。
  - `LockSplitIT`（7 个）：两段并行（持发布锁时开始照常、提交等待；持开始锁时提交照常、开始等待；持开始锁时暂停持着发布锁等待，提交排在暂停之后）；名额 20 与预算 20 × 100 恰好用满（64 人，开始与提交交错，被拒的重试到结束），`ENDED` 一次、移动一次；
    混合奖励（0 / 10 / 100）四轮各 50 人（提交或放弃给超时任务），每轮之后 I1–I5 与账；状态流程在负载下（暂停 / 恢复、`ADVANCE`、`FUNDED`、充值触发补发与预算恢复）没有 409 / 500、`pg_stat_database.deadlocks` 不增，暂停返回之后、下一次恢复开始之前发出并得到回答的开始没有一个被放行；
    结束时排在开始锁上的开始被拒；以及计划 2.3 的情形本身：最后一个名额的完成持发布锁停在钱包锁上（测试持钱包锁）时，开始读到卡片仍是 `LIVE`，被限额以 `QUOTA_FULL` 拒绝。
  - `CounterSplitProperties`（jqwik）：单调性（任意一次完成后被拒 ⇒ 完成前也被拒，且 `Q`、`B` 不增）与交错模型（开始读任意旧版本的完成一侧，I1–I4、结束一次、结束后不放行）。
  - `CounterSplitMigrationIT`：独立 schema 以 Flyway 迁到 V9、插入五种计数行、迁到 V10 后逐列核对；三种坏数据（预占不符、完成多于开始、没有卡片）迁移失败、库仍是 V9（`flyway-core` 只加在测试依赖）。
  - 原有：`PlayItSupport.counter` 以一条 JOIN 返回合并的一行（含派生的 `reserved`、`in_progress`）；`PlayConcurrencyIT` 的"等锁时余额被花光"改持开始锁；`PlayAdmissionIT` 断言两族的键与配置、按族持锁（2 个提交等发布锁、2 个开始等开始锁、1 个排队），
    超时被准入拒绝的用例改以提交填满 `qb.pub` 的队列（开始不再与它同队）；`PublicationIT`、`PublicationReviewSettingsIT` 断言两行同时插入 / 都不插入。
  - 场景快照：`play.yml`、`publishing.yml` 的 `entities` 加 `QbPublicationStartCounter`；差异只在计数——`QbPublicationCounter` 去掉 `starts`、`reserved`，多出 `released`（play：300 = 3 次完成 × 100），`version` 只随完成增加（play：7 → 4）；新增开始一侧的行（play：`starts` 3、`reservedTotal` 300、`version` 4；publishing：全 0、`version` 1）。会话、钱包、账本、事件与编号不变。
- **压测工具**：等咨询锁的连接按两把锁分开；许可持有按族并给出两族并行时的容量；有 `LOAD_DB_URL` 时压测后核对两侧计数与会话（不符即非 0 退出）。
- **重测**（报告 `perf/Q10a-load-test.md` §10，同日对照 `506e1b0`，另跑连接池 16 / `max-active` 8）：稳定吞吐约 41–45 次 / 秒（对照 35–39），每秒 40 人时提交 p95 61 ms（对照 271 ms），每秒 50 人仍过载（稳定 44.8）。
  **与预估 45–50 相比在下端或略低**：提交在发布锁内的一段因与开始并行争 CPU 而变慢（开日志时 22.4 → 24.9 ms）。池 16 没有一致的收益，配置不改（LS-7 的建议见报告 §10.3 第 4 条）。
- 验证：`./gradlew :quizbuks:check`（含 `platformCheck`）、两个 SPA 的 lint / typecheck / Vitest 与 `pnpm app:check`、`ext:check`、对 jar 的 e2e 全部（41 个）、`tools/check-app-paths.sh origin/1.2/platform`。
- 与计划不同之处：结束的完成的语句数（见上）；6.2 第 5 条的"暂停之后没有放行的开始"以客户端的时间窗判断（测试时钟固定，`startedAt` 都相同），并直接运行 `FUNDED`（恢复可能先把发布放回 `LIVE`，事件的 `FUNDS_RESTORED` 就不再运行它）；6.2 第 6 条分成两个用例（排在锁上的开始在重读时已看到 `ENDED`，以 `NOT_OPEN` 被拒；看到 `LIVE` 卡片被限额拒绝的情形由持钱包锁的用例确定地复现）。
- 已知问题：本机未达每秒 50 次（下一步平台 16p-2）；数字来自共用一台 4 vCPU 的测量，上线前在目标规格上重测。
  **过载时 503 从开始移到了提交**（报告 §10.3 第 3 条）：每秒 50–60 人时提交 503 有 150–1345 次、答题人看到的提交 p95 3.3–18.8 秒，截止前不久的提交可能以 EXPIRED 结束；记为上线风险（Q10 计划）。
  可选的后续缓解：开始一侧的背压（`qb.pub` 队列已深时拒绝新的开始），现在不改代码。
- **评审修正**（2026-10-17）：
  - `ADVANCE` 的撤回审核原以 `WithdrawApproval.of`（对不在审核中的发布传一个不存在的编号）每项都取平台审批的案件锁 `jabiz.approval:*`，即开始锁之后还会再取一把锁、开始锁不是叶子；
    改为 `WithdrawApproval.when(过期的审核, …)`，只在审核过期时取（那时发布还在待审核，没有卡片、没有开始在等），锁序（2.5、设计 4.3、`CLAUDE.md`、`PublicationEditing.lockStarts` 的 Javadoc）补上 `… → [qb.pub → qb.pub-start] → jabiz.approval:* → qb.wallet`。
  - `CounterSplitProperties` 的"结束一次"原由模型自己的标志守住、不可能失败；改为每次完成都以生产的 `PublicationMoves.moveAfter`（桩卡片带模型的状态与上限）决定是否结束，并断言"结束 ⇔ 仍在进行中且用满"、结束至多一次。
  - `LockSplitIT` 的"暂停之后没有放行的开始"原可能没有开始落在窗口内而空过：现在每次暂停成功后、恢复之前另由一个答题人开始一次，断言每个窗口都有这样的开始且以 `NOT_OPEN` 被拒；
    `pg_stat_database.deadlocks`（整个库、到得晚）只作后备，先 `pg_stat_force_next_flush()` 并稍等，真正的信号是调用的 409 / 500。
  - 报告 §1 与 §10.3 第 1 条的提高幅度改为饱和的各行 +16–30 %；§10.3 第 3 条与 Q10 计划写明过载落在提交上的风险与可选的缓解。
  - LS-7 的"开始族 `concurrency` 1"另测一次，见报告 §10.3 第 4 条。
