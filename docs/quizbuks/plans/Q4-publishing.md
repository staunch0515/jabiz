# Q4 发布（后端，5–6 天）— 实施计划

分支：`1.2/quizbuks-4-publishing`（从 `1.2/quizbuks` 拉出，PR 合回 `1.2/quizbuks`）。只改 `.jabiz-app-paths` 中的路径。

本阶段只做后端：实体、数据视图、状态机、审批对象与规则、流程、定时任务、规则、SQL 模板、迁移、三语消息、测试。

- **页面延后**，在平台 15a、16c 合入后另做，约 3 天：
  - 商家后台的发布向导三步（M-30）、发布列表（M-34）、暂停 / 恢复 / 复制（M-35、M-36）；
  - 管理员审核扩展页（发布策略加版本内容预览，P-09）。在此之前，审核用通用后台的 `/tasks` 审批面板，并在 `QbPublication` 管理员视图中查看。
- **不写账本**：充值、结算、待支付、预算不足由 Q5、Q6 做。本阶段只建 `QbPublicationCounter` 并在上架时写入初值 0；名额与预算的占用、"预算不足"状态的进出都由 Q5 实现。
  设计 §4.3 规定 MVP 不按发布预留预算（不从商家钱包转入"发布预算"维度），所以 Q4 不调用 `LEDGER_POST`。
- **不依赖 Q2**：M-31 的"存在出资方"（商家入驻已通过）经 `publishing.SponsorChecks.requireApproved(ctx)` 检查。Q2 之前它恒为通过，Q2 再改为读取 `QbSponsor.reviewStatus`（见【待确认 Q4-2】）。
  Q2 之前，商家账号由管理员建用户并分配 `QB_SPONSOR`；测试用 `TestTokens`。

已核实的平台事实：
- **审批规则缺失时放行**：`RequireApproval` 在审批对象没有任何成立的规则时得出 `NOT_REQUIRED`（18 §3.3）。新装系统在规则发布前因此会"自动放行"，应用必须自己挡住（D-Q4-4）。
- **审批作废与继续**：内容哈希不同的待审批或已批准请求会作废（`SUPERSEDED`）。审批事件的载荷含 `subject`、`entityId`、`status`、`preparerId`、`contentHash`、`reason`；继续原流程的方式是订阅事件、以系统身份再次调用 `RequireApproval`（带 `.preparedBy`），示范见 `app` 的 `SupplierCertifications`。
- **审批规则只经受控变更**：规则（`SysApprovalRule`：`ruleCode`、`subject`、`condition`、`levels`、`priority`、`enabled`）只经 `CONTROL_CHANGE_PROPOSE` / `PUBLISH` 修改，`QB_SETUP` 可以像提出受控参数那样提出它。
- **定时任务不补跑**：`JobDefinition` 在停机期间错过的时刻不补跑，同一计划时刻至多执行一次（幂等键 `job:<名>:<时刻>`）。因此定时任务必须按状态与时间查出"到期但未处理"的发布，而不是只处理这一分钟的。
- **锁与事务**：`HoldLock` 持有到根流程的事务结束，子流程取的锁也是如此。`CallProcess` 的子流程与父流程同一事务。
- **试运行不对外**：试运行（`DRY_RUN`）不经流程 API 开放，所以向导第三步的检查结果由 `QB_PUBLICATION_SAVE` 的输出给出（D-Q4-6）。
- **状态机**：`eb.stateTransitions` 的状态机会回到起点时（驳回 → 草稿），必须显式声明 `initial("DRAFT")`。
- **版本相关**：`QbQuizVersion` 只写一次，发布引用它不会遇到 `STILL_REFERENCED`。版本没有商家视图，流程从默认视图读版本，再经商家视图读其模版（不是自己的或已移除即 404）。

## 设计决定（待确认，见文末问题）

### D-Q4-1 状态机【待确认 Q4-3、Q4-4、Q4-8】

```
DRAFT ──提交──► PENDING_REVIEW ──批准──► SCHEDULED ──到开始时间──► LIVE ⇄ PAUSED
  ▲  └─自动通过（无需审批）─► SCHEDULED / LIVE          │          │ ▲
  └──驳回（附原因）/ 撤回 / 审核时已过结束时间──┘   SCHEDULED ⇄ PAUSED  ▼ │（Q5/Q6）
                                                            FUNDS_SHORT
SCHEDULED / LIVE / PAUSED / FUNDS_SHORT ──到结束时间（Q4）、名额或预算上限用完（Q5）──► ENDED
```

`eb.stateTransitions("status", st -> st.initial(DRAFT)…)` 声明的迁移：

| 起点 | 终点 |
|---|---|
| `DRAFT` | `PENDING_REVIEW`、`SCHEDULED`、`LIVE` |
| `PENDING_REVIEW` | `DRAFT`、`SCHEDULED`、`LIVE` |
| `SCHEDULED` | `LIVE`、`PAUSED`、`ENDED` |
| `LIVE` | `PAUSED`、`FUNDS_SHORT`、`ENDED` |
| `PAUSED` | `SCHEDULED`、`LIVE`、`ENDED` |
| `FUNDS_SHORT` | `LIVE`、`PAUSED`、`ENDED` |

非法迁移由平台以 422 拒绝，流程不重复检查。

- **状态只负责人为的开关**：暂停、预算不足、结束由状态表达；开始、结束时间由时间字段本身表达。凡是"现在接受新的答题吗"的判断，一律用纯 Java 的 `PublicationRules.acceptsNewAttempts(status, startAt, endAt, now)`：`LIVE` 且 `startAt ≤ now < endAt`。
  Q5 的 `QB_ATTEMPT_START`（在发布锁内）与首页模板用同一条件（模板写成 SQL）。定时任务晚一分钟不影响正确性：在 `endAt` 之后，状态哪怕仍是 `LIVE` 也不接受新的答题。
- **"预算不足"的解释**【待确认 Q4-8】：
  - 发布的 `budgetCap` 或 `maxWinners` 用完，即"名额用完"，进入 `ENDED`，由 Q5 实现；
  - 商家钱包余额不足，进入 `FUNDS_SHORT`，由 Q5、Q6 实现。
- **暂停**【待确认 Q4-4】：可以从 `SCHEDULED`、`LIVE` 暂停。恢复时按当前时间决定去向：`now < startAt` 回到 `SCHEDULED`，`startAt ≤ now < endAt` 回到 `LIVE`，`now ≥ endAt` 直接 `ENDED`。
  `FUNDS_SHORT` 的暂停与恢复留到 Q5 / Q6 再定（迁移已声明）。
- **字段 `pausedAt`、`endedAt`、`endReason`**（`EXPIRED` / `QUOTA` / `BUDGET`，后两者由 Q5 写入）都是 `processOnly`。

### D-Q4-2 提交即冻结【待确认 Q4-3】

- 只有 `DRAFT` 可以修改（`QB_PUBLICATION_SAVE`，否则 422 `QB_PUBLICATION_NOT_DRAFT`）。提交之后，内容（版本）与全部策略（名称、地区、时间、时限、合格分、档位、预算、名额）都不能再改；要改就复制一份（M-36）。
- 待审核的发布可以撤回（`QB_PUBLICATION_WITHDRAW`）：先 `WithdrawApproval`，再回到 `DRAFT`。
- 审批绑定的内容（`ApprovalCase` 的内容）：
  `{versionId, versionContentHash, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap, maxWinners, tiers:[{fromScore,toScore,reward}]}`。
  所以批准的正是提交时的快照与策略。驳回后再修改、再提交，会得到新的请求（旧请求作废）。
- 发布即冻结（5.3-1）：发布引用只写一次的 `QbQuizVersion`，内容来自版本快照。之后修改模版或生成新版本，都不影响已有的发布。

### D-Q4-3 档位与金额【待确认 Q4-7】

- `QbRewardTier` 是子实体，每个发布至多 20 档（`PublishingLimits.MAX_TIERS`）。得分为整数（题目分值是整数）。
- **提交时**由纯 Java 的 `TierCoverage.check(tiers, fullScore)` 检查，一次报告全部违规：
  - 按 `fromScore` 排序后，第一档从 0 开始（`QB_TIER_NOT_FROM_ZERO`）；
  - 每档 `fromScore ≤ toScore`（`QB_TIER_RANGE`）；
  - 相邻两档首尾相接，下一档的 `fromScore` = 上一档的 `toScore + 1`，否则报 `QB_TIER_GAP` 或 `QB_TIER_OVERLAP`；
  - 最后一档到满分（`QB_TIER_NOT_TO_FULL`）；
  - 奖励不小于 0，且至少有一档大于 0（`QB_TIER_NO_REWARD`）。
- **派生值**（`processOnly`）：`maxReward` = 各档奖励的最大值；预计最大支出 `maxSpend` = `min(budgetCap, maxWinners × maxReward)`，只有一项时取那一项，都没有时为空（不封顶）。
- `budgetCap` 可选，给出时不得小于 `maxReward`（`QB_BUDGET_BELOW_MAX_REWARD`），否则没有人能开始答题。`maxWinners` 可选，至少 1。
  这里的"获奖"指奖励大于 0（由 Q5 计数）。
- 金额字段为 `asMonetary("JPY", 0)`，界面显示 Kudos。

### D-Q4-4 审核与"自动通过"【待确认 Q4-1】

- **审批对象**：`ApprovalSubject.define("qb.publication", s -> s.entity("QbPublication").bool("autoApprove").number("maxReward").number("maxSpend").text("sponsorId").text("regions"))`。
  显示名写在消息 `approval.subject.qb.publication`；`ApprovalCase.reference(name)` 给出单据标识，待办标题用它。
- **规则**：`QB_SETUP` 以受控变更提出规则 `qb.publication.review`，由另一位管理员发布：
  - 条件 `{"fact":"autoApprove","op":"eq","value":false}`；
  - 层级 `[{"permission":"qb.review.publication"}]`；
  - 优先级 100。
  `QbSetupRecord` 记下 `approval-rule:qb.publication.review`，此后不再提出，管理员修改或删除后也不加回（与参数的做法相同）。
- **自动通过**：提交时以 `LoadParams.of(opTime, …, qb.review.publication.auto)` 读取受控参数（D40），作为事实 `autoApprove` 传入。每一次提交（包括自动通过）都留下平台的 `ApprovalEvaluation` 记录，管理员能查到当时的开关值。
  参数尚未发布时，平台报 422 `PARAM_NOT_FOUND`，提交被拒，也就是默认拒绝。
- **默认拒绝**：`autoApprove = false` 而结论是 `NOT_REQUIRED`（规则还没发布，或被停用），一律 422 `QB_REVIEW_NOT_CONFIGURED`，不放行（平台缺口 5）。
- **审批结论的去向**：

  | 结论 | 去向 |
  |---|---|
  | `PENDING` | `PENDING_REVIEW`，记 `approvalRequestId`、`submittedAt` |
  | `NOT_REQUIRED`（且 `autoApprove = true`） | 直接 `SCHEDULED` 或 `LIVE`（按 D-Q4-1 的时间判断），记 `approvedAt` |
  | `APPROVED`（同样内容此前已批准） | 同上 |

- **继续原流程**：`QB_PUBLICATION_APPROVAL_RESULT` 订阅 `jabiz.approval.approved` / `rejected`，按 `subject` 忽略其他审批对象的事件，以系统身份运行，取发布锁：
  - 批准，且再次 `RequireApproval`（`preparedBy` = 原准备人）得到 `APPROVED`：
    - `now < endAt`：进入 `SCHEDULED` 或 `LIVE`，建立计数行；
    - 否则：退回 `DRAFT`，`rejectCode = QB_PUBLICATION_EXPIRED_IN_REVIEW`【待确认 Q4-5】。
  - 驳回，且内容哈希与发布当前内容一致：退回 `DRAFT`，`rejectReason` 写审批人的理由（P-09"附原因"）。下次提交时清空 `rejectReason` 和 `rejectCode`。
  - 发布已不在 `PENDING_REVIEW`（例如已撤回）：什么也不做。
- **审核人看到的内容**：审批人需持有 `approval.decide` 与 `qb.review.publication`（`QB_ADMIN_CONTENT` 已有）。准备人不能审批，由平台检查。
  审核人经管理员视图查看发布与版本（版本 `content` 显示为原始文本；预览页随页面阶段做，平台缺口 1）。

### D-Q4-5 锁与模版移除【待确认 Q4-9】

- **锁的顺序**固定为 `qb.quiz:<quizId>`（共享）→ `qb.pub:<publicationId>`（独占），之后才是 Q5 的 `qb.wallet:<所有者>`。

  | 流程 | 取的锁 |
  |---|---|
  | `QB_PUBLICATION_SUBMIT` | 两把都取 |
  | `QB_PUBLICATION_SAVE`（已有的发布） | 只取发布锁 |
  | 撤回、暂停、恢复、`ADVANCE`、`APPROVAL_RESULT` | 只取发布锁 |
  | `QB_QUIZ_DELETE`（Q3） | 只取模版锁（独占） |

  提交与移除模版因此不会交错。
- **`QbPublication.quizId`**：冗余存放（草稿换版本时随之改变）。它是可变字段，时态表上按它查找用不到索引（只有不可变字段的条件能下推），所以移除模版时经普通表 `qb_publication_schedule`（按 `quiz_id` 建索引，见 D-Q4-7 的修订）找出已提交的发布。
- **移除模版**时，`QuizProcesses.filesStillNeeded(ctx)` 改为：读该模版下状态不是 `DRAFT` 的发布（在模版锁内），解析它们所引用版本的快照（`QuizSnapshots`），取出其中的文件。
  这些文件的 `QbVersionFile` 保留，其余照旧分块释放。
- **模版已移除的发布**：提交草稿时如果模版已移除，422 `QB_PUBLICATION_QUIZ_REMOVED`（文件可能已释放）；保存时同样检查。
- 退回草稿的发布不再有日程行（修订后），所以其版本文件在模版移除时照常释放；草稿本就不可再提交。

### D-Q4-6 向导三步与一次报告全部问题

- **第一步、第二步**：用 `QB_PUBLICATION_SAVE`。草稿可以不完整，只检查字段本身（长度、格式、范围、`startAt < endAt` 的实体级校验、档位数上限）。
  第一步选模版及版本，复用 Q3 已有的模板 `qb.sponsor.quizzes` 与 `qb.sponsor.quiz-versions`。
- **第三步（确认）**：保存的输出带 `problems`（提交时会报告的全部违规，含 `code`、`field`、参数）与 `fullScore`、`maxReward`、`maxSpend`。确认页据此提示，不必另设检查流程。
- **提交时的检查**：纯 Java 的 `PublicationReadiness.check(发布, 档位, 版本快照, now)`，一次返回全部违规（422）：
  - 版本已选（`QB_PUBLICATION_NO_VERSION`）；
  - 名称（必填）、地区非空；
  - 起止时间都有，`startAt < endAt`，`endAt > now`（`QB_PUBLICATION_END_PASSED`），投放期不超过 366 天（`QB_PUBLICATION_TOO_LONG`）；
  - 时限 30–7 200 秒；
  - `0 ≤ passScore ≤ fullScore`（`QB_PASS_SCORE_RANGE`）；
  - D-Q4-3 的档位与预算规则；
  - 以快照重新执行 Q3 的 `QuizCompleteness`（M-31：至少 1 题、选项合规）。这是防御：版本生成之后规则如果变严，旧版本在这里被挡下；
  - `SponsorChecks.requireApproved`（M-31：存在出资方）。

### D-Q4-7 定时上下架

- **任务**：`JobDefinition.cron("qb.publication-tick", "0 * * * * *", UTC, QB_PUBLICATION_TICK, t -> new TickInput(t))`。
- **`QB_PUBLICATION_TICK`**（内部流程，系统身份）用当前操作时间找出全部到期的发布，因此停机后也能补上：
  - `SCHEDULED` 且 `startAt ≤ now`；
  - `SCHEDULED` / `LIVE` / `PAUSED` / `FUNDS_SHORT` 且 `endAt ≤ now`。
  按 `publicationId` 排序，每次至多 200 条（`PublishingLimits.TICK_BATCH`），剩下的下一分钟处理。
  **（评审后修订）**：状态与时间都是可变字段，时态表上的这个查询每分钟扫描全部历史，`(status, start_at)` 等索引用不上。改为普通表 `qb_publication_schedule(publication_id, quiz_id, version_id, next_due_at)`，由各流程在发布锁内随状态维护（`PublicationRules.nextDue`：`SCHEDULED` 取开始时间，`LIVE` / `PAUSED` / `FUNDS_SHORT` / `PENDING_REVIEW` 取结束时间，`ENDED` 为空；回到 `DRAFT` 即删除该行），定时任务按 `next_due_at <= now` 取（有索引），每次至多 50 条。仍在待审核而已过结束时间的发布也由此处理：撤回审批、回到草稿，`rejectCode = QB_PUBLICATION_EXPIRED_IN_REVIEW`。
  对每条调用子流程 `QB_PUBLICATION_ADVANCE`：取发布锁，重新读取，按 `PublicationRules.next(status, startAt, endAt, now)` 迁移；如果状态已被别人改变（例如刚暂停），就不动。
- **事务**：一批在同一事务中执行（平台缺口 6），锁按主键顺序获取，不会与其他流程互相等待。
- **其余时间判断**：需求要求的"到开始时间自动进行中""到期结束"由此实现；暂停之外的可见性以时间字段为准（D-Q4-1）。

### D-Q4-8 复制（M-36）

`QB_PUBLICATION_COPY {publicationId, versionId?, startAt?, endAt?, name?}`：
- 源可以是本人任意状态的发布。
- 复制出新的 `DRAFT`（`copiedFrom` = 源）：全部策略与档位，可替换版本或时间；名称缺省为"源名称（副本）"，三语消息。
- 换了版本时，`fullScore` 取新版本的值；档位照搬，与新满分不符的问题由 `problems` 报告，提交时拒绝。
- 一个模版可以发布多次，同一版本也可以。

## 要求

1. **权限**（`QbPermissions`、`QbRoles`；部署后再执行一次 `QB_SETUP`）：
   - 新增 `qb.admin.publication.read`（管理员只读全部发布、档位、计数），授予 `QB_ADMIN_CONTENT` 与 `QB_ADMIN_FINANCE`；
   - 已有的 `qb.publication.write`（商家）用于商家的全部发布流程与商家视图；
   - 已有的 `qb.review.publication` 作为审批层级的权限；
   - 商家视图的写权限沿用 `qb.content.view.write`（不授予任何角色，Javadoc 改为"商家只读视图"）。
2. **字典**（静态，三语）：
   - `urn:jabiz:dict:quizbuks:publication-status`（7 个状态）；
   - `urn:jabiz:dict:quizbuks:publication-end-reason`（`EXPIRED`、`QUOTA`、`BUDGET`）。
3. **地区**：`country.Regions` 增加 `TARGETS`。这是发布地区的规范写法：非空子集，按 `ALL` 的顺序，不强制含 `GLOBAL`，只选 `GLOBAL` 即全体。还有 `Regions.targets(Collection)`；规则 `QB_PUBLICATION_REGIONS_FORMAT`。
4. **实体**（包 `com.jabiz.quizbuks.publishing`）：3 个，见下节。
5. **数据视图**：
   - 默认视图：管理员只读，`processOnlyWrites()`，权限 `qb.admin.publication.read`；
   - 商家视图 `urn:jabiz:dataset:sponsor:<实体>`：`fromContext("sponsorId", actorId)`，只读，权限 `qb.publication.write`。计数行没有商家视图，经模板连接。
6. **审批对象与规则**：D-Q4-4；`QB_SETUP` 提出规则（`QbApprovalRules`，与 `QbParams` 并列），输出增加 `rulesProposed`。
7. **流程**：10 个，见"流程"一节。
8. **定时任务**：`qb.publication-tick`。
9. **事件订阅**：`qb.publication-approved`、`qb.publication-rejected`，两者都执行 `QB_PUBLICATION_APPROVAL_RESULT`。
10. **SQL 模板**：新增 `qb.sponsor.publications`（M-34）、`qb.admin.publications`；修改 `qb.sponsor.quizzes`（M-20 的"已发布"）。
11. **Q3 衔接**：`QuizProcesses.filesStillNeeded` 按 D-Q4-5 实现；`QB_QUIZ_DELETE` 增加读取发布的步骤。
12. **消息**（三语，启动检查齐全）：实体与字段、字典、10 个流程、全部 `QB_*` 码、`approval.subject.qb.publication`、模板与列名、复制的默认名称。
13. **文档**：
    - `02-design.md` §3.3（字段：`quizId`、`versionLabel`、`rejectCode`、`pausedAt`、`endedAt`、`endReason`、`revision`、`approvalRequestId`）、§4.3（锁顺序加 `qb.quiz`）、§5.1（状态迁移与时间判断）、§5.2（新流程）；
    - `03-plan.md` 的 Q4 行；
    - `backend/quizbuks/CLAUDE.md` 加发布约定：锁顺序、冻结、`PublicationRules` 是唯一的接受判断、审批规则经 `QB_SETUP` 提出。

## 实体与迁移

记号：T = 时态（只追加），P = 普通表。主键为 UUIDv7。`sponsorId`（不可变、必填、`asText(128)`、取操作人）冗余存在子实体上。

| 实体（表） | 类型 | 字段 |
|---|---|---|
| `QbPublication`（`qb_publication_version`） | T | 见下方字段说明 |
| `QbRewardTier`（`qb_reward_tier_version`） | T | `tierId`；`publicationId`（引用，不可变）；`sponsorId`；`seq`；`fromScore`、`toScore`（`numeric(6,0)`）；`reward`（JPY，≥ 0） |
| `QbPublicationCounter`（`qb_publication_counter`） | P | `publicationId`（主键，引用）；`starts`、`completions`、`passes`、`winners`（`numeric(9,0)`）；`reserved`、`spent`（JPY）。上架时由 `QB_PUBLICATION_APPROVAL_RESULT` / `SUBMIT` 插入全 0 的一行；Q5 在发布锁内更新 |

`QbPublication` 的字段：
- **标识**：`publicationId`；`sponsorId`。
- **内容引用**：`quizId`（引用 `QbQuiz`）；`versionId`（引用 `QbQuizVersion`）；`versionLabel`（`processOnly`）。
- **投放策略**：`name`（≤ 100，非空白）；`regions`（`Regions.TARGETS`，≤ `MAX_LENGTH`）；`startAt`、`endAt`（`EVENT_TIME`）；`timeLimitSec`（30–7 200）；`passScore`（`numeric(6,0)`，≥ 0）。
- **财务策略**：`budgetCap`（`Monetary JPY 0`，≥ 1，可空）；`maxWinners`（`numeric(9,0)`，≥ 1，可空）。
- **`processOnly` 的派生值与状态**：`fullScore`、`maxReward`、`maxSpend`、`tierCount`；`status`（字典）；`rejectReason`（≤ 500）、`rejectCode`；`approvalRequestId`；`submittedAt`、`approvedAt`、`pausedAt`、`endedAt`、`endReason`；`revision`；`editedAt`。
- **来源**：`copiedFrom`（引用自身，不可变）。
- **元数据**：显示字段 `name`；状态机见 D-Q4-1；实体级校验 `QB_PUBLICATION_TIME_ORDER`（两者都有时 `startAt < endAt`）；`allowScheduled(false)`。

**迁移** `db/migration/V4__quizbuks_publishing.sql`：
- 沿用 V3 的建表函数写法：`UNIQUE(主键, version_no)`、当前版本索引、`process_seq_id` 索引、外键、`jabiz_protect_append_only`，用后删除函数。
- 另建索引：
  - `qb_publication_version` 的 `sponsor_id`、`copied_from`（不可变字段；评审后去掉了用不上的 `quiz_id`、`version_id`、`(status, start_at)`、`(status, end_at)`）；
  - 普通表 `qb_publication_schedule`：`next_due_at`（部分索引）与 `quiz_id`（D-Q4-7 的修订）；
  - `qb_reward_tier_version (publication_id)`。
- `qb_publication_counter` 按平台普通实体的表结构（参照 `backend/app` 中的普通实体迁移）。

## 流程

共同点：
- 输入为 record，Bean Validation（400）。
- 先按 D-Q4-5 的顺序取锁，再经商家视图 `LoadEntity`（不是自己的即 404）。
- 版本经默认视图读取，其模版经商家视图读取（不是自己的或已移除即 404）。
- 写入经 `ctx.changes()`；违规累积后 422。
- 输出至少有 `publicationId`、`status`、`revision`。

| 流程 | 权限 | 输入 | 要点 | 错误代码 |
|---|---|---|---|---|
| `QB_PUBLICATION_SAVE` | `qb.publication.write` | `publicationId?`、`versionId?`、`name`、`regions[]`、`startAt?`、`endAt?`、`timeLimitSec?`、`passScore?`、`budgetCap?`、`maxWinners?`、`tiers[≤20]{tierId?, fromScore, toScore, reward}?`（null 即不动，给出即完整列表）、`baseRevision?` | 没有 `publicationId` 即新建 `DRAFT`。换版本时更新 `quizId`、`fullScore`、`versionLabel`；`timeLimitSec` 缺省取版本的值。档位按数组比较新旧。有改动才写，`revision + 1`。输出加 `problems`、`maxReward`、`maxSpend`（M-30、M-32、M-33） | `QB_PUBLICATION_NOT_DRAFT`、`QB_PUBLICATION_CHANGED`、`QB_PUBLICATION_QUIZ_REMOVED`、`QB_PUBLICATION_REGIONS_FORMAT`、`QB_PUBLICATION_TIME_ORDER`、字段规则 |
| `QB_PUBLICATION_DELETE` | 同上 | `publicationId`、`baseRevision?` | 只限 `DRAFT`；先给档位写墓碑，再删发布 | `QB_PUBLICATION_NOT_DRAFT` |
| `QB_PUBLICATION_COPY` | 同上 | D-Q4-8 | `actsOn`；新 `DRAFT`（M-36） | 版本 404 |
| `QB_PUBLICATION_SUBMIT` | 同上 | `publicationId`、`baseRevision?` | `actsOn`（`DRAFT`）。依次：取两把锁；`LoadParams`；读版本快照与档位；`PublicationReadiness`；`RequireApproval`；按 D-Q4-4 迁移；上架即建计数行（M-30、M-31、P-09） | D-Q4-6 的各码、`QB_REVIEW_NOT_CONFIGURED`、`QB_SPONSOR_NOT_APPROVED`（Q2 起） |
| `QB_PUBLICATION_WITHDRAW` | 同上 | `publicationId` | `actsOn`（`PENDING_REVIEW`）；`WithdrawApproval` → `DRAFT` | 迁移 422 |
| `QB_PUBLICATION_PAUSE` | 同上 | `publicationId` | `actsOn`（`SCHEDULED`、`LIVE`）；发布锁 → `PAUSED`、`pausedAt`。之后的开始答题在同一把锁内看到 `PAUSED`，立即不可进入（M-35） | 迁移 422 |
| `QB_PUBLICATION_RESUME` | 同上 | `publicationId` | `actsOn`（`PAUSED`）；按 D-Q4-1 回到 `SCHEDULED`、`LIVE` 或进入 `ENDED` | 迁移 422 |
| `QB_PUBLICATION_APPROVAL_RESULT` | `qb.publication.system`（不授予任何角色），`internal()` | 审批事件 | D-Q4-4 | — |
| `QB_PUBLICATION_TICK` | `qb.publication.system`（不授予任何角色），`internal()` | `scheduledTime` | D-Q4-7 | — |
| `QB_PUBLICATION_ADVANCE` | 同上，`internal()` | `publicationId` | 发布锁内按时间迁移，写 `endedAt`、`endReason = EXPIRED`；待审核而已过结束时间的撤回审批、回到草稿 | — |

**纯 Java 部件**（无 I/O，单元测试）：
- `PublishingLimits`（档位数、最长投放期、批量大小）；
- `PublicationStatus`（常量）；
- `PublicationRules`（`acceptsNewAttempts`、`next`、`resumeTo`）；
- `TierCoverage`；
- `PublicationReadiness`；
- `PublicationApprovals`（事实与绑定的内容，规范化后用平台 `ContentHash`）；
- `SponsorChecks`。

**错误码**：字段规则违规 400；流程拒绝 422；不存在或不是自己的 404；无权限 403；经数据视图直接写入被拒（平台）。全部写在 `publishing.PublishingCodes`。

## SQL 模板

- **`qb.sponsor.publications`**（M-34，权限 `qb.publication.write`）：
  - 读取：商家视图的发布，连接版本（标题），左连接计数行。
  - 结果列：`publicationId`、`name`、`quizTitle`、`versionLabel`、`status`、`regions`、`starts`（参与人数）、`winners`、`maxWinners`、`spent`、`budgetCap`、`maxSpend`、`startAt`、`endAt`、`rejectReason`、`rejectCode`、`editedAt`。
  - 参数 `q`（≤ 100，按名称搜索，写法同 `qb.sponsor.quizzes` 的 `strpos(lower…)`）；筛选 `status`、`startAt`；排序 `editedAt`（默认倒序）、`startAt`、`name`。
- **`qb.admin.publications`**（权限 `qb.admin.publication.read`）：同上，再加 `sponsorId`、`approvalRequestId`、`submittedAt`；筛选 `sponsorId`、`status`。供审核与支持查询。
- **`qb.sponsor.quizzes`**（Q3）：增加 `publicationCount`（本模版下状态不是 `DRAFT` 的发布数）与 `published`（> 0），满足 M-20 的"草稿 / 已发布"。快照随之更新。

## 测试

集成测试连本地 PostgreSQL（`JABIZ_TEST_DB_*`），BlockHound 开启。令牌经 `QbItSupport`：商家 A、B，内容管理员（审核人），超级管理员（提出规则），财务管理员（发布受控变更），无权限者。
每个类的准备步骤：执行 `QB_SETUP`，由财务管理员发布全部提案（参数与规则），再生成一个版本。

- **单元**：
  - `TierCoverageTest`：每条规则的正反用例；jqwik 随机档位，与独立实现的结果一致；
  - `PublicationReadinessTest`：一次报告全部违规，带 `field` 路径；
  - `PublicationRulesTest`：迁移表穷举；时间边界 `startAt` / `endAt` 前后 1 毫秒；
  - `PublicationApprovalsTest`：键顺序不影响哈希，任一策略变化即改变哈希；
  - `RegionsTest`：`TARGETS`；
  - `QbRolesTest`：新授权；`qb.publication.system` 不授予任何角色；
  - `MessagesTest`。
- **`PublicationIT`**（M-30 – M-34、M-36、P-09）：
  - 只有名称的草稿可以保存，`problems` 列全；
  - 提交不完整的发布，一次返回全部违规；
  - 人工审核：提交 → `PENDING_REVIEW`，内容管理员有待办 → `APPROVAL_DECIDE` 批准 → 投递事件 → `SCHEDULED`，开始时间已过的进入 `LIVE`，计数行为 0；
  - 驳回附理由 → `DRAFT` 且显示理由；修改后再提交，得到新请求；
  - 撤回；
  - 自动通过（受控参数改为 true，经提出与发布）→ 直接 `LIVE`，且留有评估记录；
  - 参数未发布 → 422 `PARAM_NOT_FOUND`；规则停用而参数为 false → 422 `QB_REVIEW_NOT_CONFIGURED`；
  - 批准时已过结束时间 → `DRAFT`，原因码 `QB_PUBLICATION_EXPIRED_IN_REVIEW`；
  - 提交后保存 → 422 `QB_PUBLICATION_NOT_DRAFT`；
  - 复制：策略与档位相同；换版本后满分不符的档位出现在 `problems` 中；
  - 两个模板：搜索、筛选、计数、已发布标记；
  - `Idempotency-Key` 重复提交只执行一次。
- **`PublicationLifecycleIT`**（5.3-2、M-35、定时上下架）：
  - `JobRunner.run` 在开始时间之前什么也不做；在开始时间 → `LIVE`；在结束时间 → `ENDED`、`EXPIRED`；
  - 错过多个计划时刻之后的一次运行全部补上；
  - 暂停中到期 → `ENDED`；
  - 暂停、恢复（开始前恢复 → `SCHEDULED`，结束后恢复 → `ENDED`）；非法迁移 422；
  - 暂停与定时任务并发，以发布锁串行，结果一致；
  - 超过一批（50 条）到期时分两次处理；
  - `acceptsNewAttempts` 在暂停、到期后（状态未改）均为 false。
- **`PublicationOwnerIT`**：
  - B 对 A 的发布：视图、模板中看不到，每个流程 404；B 用 A 的 `versionId` 保存 → 404；
  - 经数据视图直接写，一律被拒；
  - 无 `qb.publication.write` → 403；
  - 商家直接调用 `TICK` / `ADVANCE` / `APPROVAL_RESULT` → 403；
  - 商家不能审批自己的请求（平台）；
  - 管理员只读全部。
- **`ContentFilesIT` 扩展（Q3 衔接）**：
  - 移除带已提交发布的模版：版本快照中的文件在 `FILE_SWEEP` 之后仍可读；
  - 只有草稿发布时照常释放；该草稿再提交 → 422 `QB_PUBLICATION_QUIZ_REMOVED`；
  - 提交与移除并发，结果只有两种：提交成功且文件保留，或提交被拒。
- **`SetupIT` 扩展**：规则只提出一次；发布后再次执行不重复提出；管理员停用规则后也不加回。
- **其他**：每个集成测试类断言 `qb_publication_version`、`qb_reward_tier_version` 上没有 UPDATE / DELETE（以及 Q3 的 7 张表）。
- **场景回放** `scenarios/qb/publishing.yml`（固定时钟，用 `actor` 切换操作人）：
  1. A 建模版并生成 v1.0；
  2. 保存发布（三档）；
  3. 提交 → 待审核；
  4. 审核人驳回 → A 修改后再提交；
  5. 审核人批准 → `deliverEvents: true` → `SCHEDULED`；
  6. `runJob` 到开始时间 → `LIVE`；
  7. 暂停 → 恢复；
  8. `runJob` 到结束时间 → `ENDED`；
  9. 复制并改时间，B 暂停 A 的发布（期望 404）。

  场景不带文件（平台缺口 2）。`scenarios/qb/setup.yml` 的快照随新授权与规则提案更新，`content.yml` 的快照随 `qb.sponsor.quizzes` 的新列更新。
- `platformCheck` 通过（`APPROVAL`、`JOB`、`EVENT` 类检查）；`ArchitectureTest` 不变。

## 验收标准

- [x] `./gradlew :quizbuks:check` 通过（含 `platformCheck`）；`tools/check-app-paths.sh` 通过。PR 上的 CI：**待 PR 验证**（本阶段按要求未开 PR）。
- [x] M-30：保存 → 提交 → 待审核 → 通过后上架，驳回时显示原因；确认页需要的问题列表与预计最大支出由保存的输出给出。
- [x] M-31：至少 1 题、选项合规（以快照重新检查）、存在出资方（Q2 起生效），不通过不得提交。
- [x] M-32 / M-33：名称、地区（多选）、起止时间、时限、满分（取自版本）、合格分；档位不重叠、覆盖 0 到满分、可从 0 分起；预算上限与最大获奖人数可选；按最高档给出预计最大支出。
- [x] M-34：发布列表有关联模版、状态、地区、参与人数、获奖人数 / 上限、已花 / 预算，可按状态过滤与搜索（计数在 Q5 之前为 0）。
- [x] M-35：暂停后 `acceptsNewAttempts` 立即为 false（Q5 在同一把锁内使用）；恢复按时间回到正确状态。
- [x] M-36：复制策略，只替换内容或时间；一个模版可以发布多次。
- [x] P-09（发布）：审核队列即平台待办；通过 / 驳回附原因；可经受控参数切换为自动通过；规则未配置时拒绝而不是放行。
- [x] 5.3-1：发布引用只写一次的版本，之后修改模版不影响它；其文件在模版移除后仍保留。
- [x] 5.3-2：状态机完整声明，非法迁移 422；到开始时间进入进行中、到期结束由定时任务完成，错过的时刻会补上。
- [x] 5.3-3（Q4 的部分）：可见性与接受新答题的时间、状态条件写在 `PublicationRules`。地区交集与余额由 Q5 的模板实现。
- [x] 时态表上没有 UPDATE / DELETE；消息三语齐全；文档已更新；部署后执行 `QB_SETUP`，并由另一位管理员发布规则提案。

## 实施记录（2026-10-11）

状态：后端完成（分支 `1.2/quizbuks-4-publishing`，未开 PR），已按评审修订（见下"评审后的修改"）。页面按计划延后。

### 与计划的差异

- **错误码**：字段规则另有代码（`QB_PUBLICATION_NAME_BLANK`、`_TIME_LIMIT_RANGE`、`_SCORE_RANGE`、`_BUDGET_RANGE`、`_MAX_WINNERS_RANGE`、`QB_TIER_REWARD_RANGE`，400）；
  提交时缺项一律 `QB_PUBLICATION_FIELD_REQUIRED`（参数 `field`，文案不显示字段名：页面按 `entity.QbPublication.<field>` 显示）；保存时给出不属于本发布的 `tierId` 为 422 `QB_TIER_NOT_IN_PUBLICATION`；
  没有档位只报 `QB_TIER_NO_REWARD`；地区代码不认识时流程 422 `QB_PUBLICATION_REGIONS_FORMAT`（存储文本不合格式由字段规则 400）；
  获奖人数 × 最高奖励超过金额上限且没有预算上限时 `QB_PUBLICATION_SPEND_RANGE`。全部在 `PublishingCodes`。
- **以快照重查内容**的违规路径加前缀 `version.`（如 `version.questions[0].options`），与发布自己的字段区分。
- **档位按键比较**：同 Q3 的选项，给出的列表是完整列表，带 `tierId` 的是原档位、不带的是新档位（保存输出给出 `tierIds`）。不带键重存同样的档位会删旧插新（`revision + 1`）。
- **`revision` 与 `editedAt`**：发布行的每一次写入（含状态变化）都加 1、记时间。回到草稿（驳回、审核中到期、撤回）一律清空 `approvalRequestId`；恢复时清空 `pausedAt`。
- **迁移由流程自己先查起点**：平台只拒绝声明之外的迁移，看不到"迁到原状态"或"声明了但不是本操作的"迁移。撤回、暂停、恢复先查起点，不符即以平台的 422 `ILLEGAL_TRANSITION` 拒绝；写入时也再查一次声明。
- **提交先读一次发布再取锁**：模版锁在前，而只有发布知道其模版；先读出 `quizId`，取模版锁（共享）与发布锁，再读一次，期间模版变了即 409。
- **系统流程**（`APPROVAL_RESULT`、`TICK`、`ADVANCE`）经默认视图读取，权限 `qb.publication.system`（不授予任何角色；计划写的是 `qb.publication.tick` 与 `qb.review.publication`）。
- **保存输出的 `problems`** 带按调用者语言的文案；复制的缺省名称为消息 `qb.publication.copy-name`（三语，过长时按字符（码点）截短源名称）。
- **`qb.sponsor.quizzes`** 增加 `publicationCount`、`published`（可筛选）。`content.yml` 的快照不含模板结果，没有变化；`setup.yml` 增加发布第 7 个提案（规则）的步骤并更新快照。
- **日程表**（评审 R2、R3）：普通表 `qb_publication_schedule`，见 D-Q4-7 的修订；`V4` 尚未合入，直接修改了 `V4`（没有另加 `V5`）。
- **数据视图的写权限**（评审 S3）：发布与内容（Q3）的默认数据视图的写权限都改为不授予任何角色的 `qb.content.view.write`（流程的写入不看它）。
- **测试结构**：`ContentFilesIT` 改为继承 `PublicationItSupport`；`PublicationReviewSettingsIT`（新装系统）、`PublicationTickBatchIT`（数全部发布）、`PublicationTickFailureIT`（植入坏的日程行）各成一类。
- **场景**需要 `properties: jabiz.jobs.lock-at-least-for: 0s`（平台缺口 7）。
- `deploy/quizbuks/README.md` 写明：规则与参数的提案发布之前，提交发布一律被拒。

### 评审后的修改（2026-10-11）

- **S1 / R1 审批结果只认自己的请求**：`QB_PUBLICATION_APPROVAL_RESULT` 的权限改为不授予任何角色的 `qb.publication.system`（内容管理员直接调用 403）。
  输入加 `requestId`（取自事件），只有等于发布的 `approvalRequestId` 才处理；批准还要 `RequireApproval` 得到的就是这个请求；驳回不信事件，读存储的请求：须为 `REJECTED`、
  同一审批对象与发布、内容哈希等于发布当前的内容。持有 `*` 的管理员仍能调用，但伪造的结果不起作用（测试 `forgedOrForeignApprovalResultsChangeNothing`）。
  撤回后不变内容再提交，旧请求迟到的驳回事件被忽略，新请求照常批准（`aStaleRejectionIsIgnoredAndTheNewRequestStillCounts`）。
- **S2 金额上限**：奖励与预算上限至多 10^12 Kudos（`PublishingLimits.MAX_MONEY`，字段规则 400）；预计最大支出存储时不超过它，超过时提交被拒（`QB_PUBLICATION_SPEND_RANGE`），不再 500。
- **S3**：见上"数据视图的写权限"。
- **R2 日程表**：定时任务与移除模版改经 `qb_publication_schedule`（`next_due_at` 部分索引、`quiz_id` 索引）；时态表上去掉了用不上的 `quiz_id`、`version_id`、`(status, start_at)`、`(status, end_at)` 索引。
  回到草稿即删除日程行，因此"已提交又被驳回的发布在模版移除时保留文件"的小泄漏也不再有。
- **R3 审核中到期**：日程对待审核的发布取结束时间；到期时 `QB_PUBLICATION_ADVANCE` 撤回审批（待办随之取消）、回到草稿，`rejectCode = QB_PUBLICATION_EXPIRED_IN_REVIEW`。
- **R4 嵌套档位**：`TierCoverage` 按"到目前为止最高的结束分"判断缺口与重叠、按最高结束分判断是否到满分；jqwik 另以逐分计数的独立实现对照报告的代码集合（1 000 例）。
- **R5 `copiedFrom`**：改为源主键的文本（不可变、有索引），不再是引用：被复制过的草稿可以删除。
- **R6 批量**：每批 50 条。平台的 `HoldLock` 只有全局等待时限（`jabiz.process.lock-timeout`，缺省 30 秒），没有逐项的短等待；见已知问题。
- **R7**：复制名称按码点截短（带表情的测试）；`QB_PUBLICATION_FIELD_REQUIRED` 文案不再显示字段名。
- **R8**：补上旧事件、其他审批对象、一批中一项失败、待审核到期的测试。时态表不被 UPDATE / DELETE 由触发器保证；测试中 `pg_stat_user_tables` 的计数只作确认，可能滞后。

### 测试

单元：`TierCoverageTest`（含 500 例覆盖判断与 1 000 例代码集合的 jqwik 对照）、`PublicationReadinessTest`、`PublicationRulesTest`、`PublicationApprovalsTest`、`CopyNameTest`、`RegionsTest`、`QbRolesTest`、`MessagesTest`。
集成：`PublicationIT`（15）、`PublicationLifecycleIT`（6，含暂停与定时任务并发）、`PublicationOwnerIT`（5）、`PublicationReviewSettingsIT`（1）、`PublicationTickBatchIT`（1，51 条分两次）、
`PublicationTickFailureIT`（1）、`ContentFilesIT` 新增 2、`SetupIT` / `SetupRemovalsIT` 扩展。场景 `qb/publishing.yml`（快照含日程表）。

### 已知问题

- **一批的事务**（平台缺口 6）：一批中一项失败，整批回滚、下一分钟重试；一项持续失败（或其发布锁被长时间占用，等满 30 秒的锁时限）会挡住同批其他发布（饥饿）。
  以批量 50 缓解。**Q5 须注意**：开始答题、提交、自动提交都取发布锁，持锁要短；`QB_ATTEMPT_EXPIRE` 有同样的批量问题，Q5 前评估平台缺口 6。
- 状态在结束时间后至多一分钟仍为 `LIVE`；判断一律以 `PublicationRules` 为准。
- `SponsorChecks` 在 Q2 之前恒为通过。
- 持有 `*` 的管理员（平台的首个管理员）仍能直接调用系统流程；结果已由请求校验约束，但调用本身只留操作记录。
- 页面未做；PR 上的 CI 未运行。

## 风险

- **审批规则未发布的窗口**：新装系统在财务管理员发布规则之前，`autoApprove = false` 的提交一律被拒（`QB_REVIEW_NOT_CONFIGURED`）。这是有意为之；需要写进部署说明（`deploy/quizbuks/README.md`）。
- **定时任务的整批事务**：一批中有一条失败，整批回滚并在下一分钟重试；持续失败的那一条会挡住同批的其他发布。用批量上限、锁内重新检查、启动检查缓解，最终靠平台缺口 6。
- **状态与时间不一致的一分钟**：`LIVE` 在 `endAt` 之后最多再显示一分钟。凡是做判断的地方都以时间为准，不影响正确性；商家后台显示状态时，应同时按时间显示（页面阶段的约定）。
- **"预算不足"的解释**【待确认 Q4-8】：如果需求方的意思是"发布预算用完即预算不足，充值后恢复"，Q5 的迁移需要调整。
- **审核人看内容**：通用后台把版本 `content` 显示为 JSON 文本，可读性差。在审核扩展页做好之前，审核效率低（平台缺口 1 与页面阶段）。
- **冻结策略过严**【待确认 Q4-3】：提交后连结束时间都不能延长，只能复制一份。如果需求方要求"延期"，可以加 `QB_PUBLICATION_EXTEND`（只许推后 `endAt`，重新审批）。
- **Q2 的出资方检查**：`SponsorChecks` 在 Q2 之前恒为通过。Q2 合入时必须接上，并补测试。

## 预估

| 工作 | 天数 |
|---|---|
| 实体、迁移、视图、字典、状态机 | 1 |
| 纯 Java 部件（档位、就绪检查、规则、审批内容） | 1 |
| 10 个流程、审批对象与订阅、定时任务、`QB_SETUP` 提出规则 | 1.5–2 |
| 模板、消息、Q3 衔接 | 0.5 |
| 测试与场景 | 1–1.5 |
| **合计（不含页面）** | **约 5–6** |

页面（向导、列表、操作、审核扩展页）在 15a、16c 之后另做，约 3 天。

## 平台缺口（都不阻塞 Q4；按 `backend/quizbuks/CLAUDE.md` 第 2 节在 `1.2/platform` 上补）

1. **（Q3 缺口 1，现在影响审核）单语言 Markdown 文本的格式标记**：审核人在通用后台看到的简介、正文是普通文本，版本 `content` 是 JSON 文本。审核扩展页（页面阶段）会自己渲染快照，但它依赖 15a。
2. **（Q3 缺口 2）场景回放没有"上传文件"步骤**：发布场景不带文件，文件保留由 `ContentFilesIT` 覆盖。
5. **（新）没有规则时审批放行**：`RequireApproval` 在没有成立的规则时得出 `NOT_REQUIRED`，新装系统上默认放行。应用以 `autoApprove` 事实加 `QB_REVIEW_NOT_CONFIGURED` 自行拒绝（D-Q4-4）。
   建议平台提供 `ApprovalSubject.define(…).requireRule()`：该对象没有启用的规则时，`RequireApproval` 报 422 `APPROVAL_RULE_MISSING`；"不需要审批"只能由显式的无层级规则表达。
6. **（新）定时任务里逐项独立的事务**：`CallProcess.forEach` 与根流程同一事务，一项失败整批回滚。建议平台提供"任务按项分事务执行子流程"（例如 `JobDefinition.forEach(查询, 流程)`，每项一次提交，失败的单独记录与重试）。Q4 以批量上限与锁内重新检查绕行；Q5 的 `QB_ATTEMPT_EXPIRE`（每分钟批量自动提交）同样受益，Q5 前评估。

7. **（新，小）场景中同一定时任务在几秒内运行两次会得到 `LOCKED`**：`runJob` 不重置集群锁，`jabiz.jobs.lock-at-least-for`（缺省 30 秒，真实时间）使第二次被挡。
   Q4 的场景以 `properties: jabiz.jobs.lock-at-least-for: 0s` 绕行。建议场景回放默认把它设为 0（或 `runJob` 前释放该任务的锁）。
8. **（新）`internal()` 仍照常检查权限，容易误用**：它只让流程不进目录。只该由平台（事件、定时任务）执行的流程若沿用业务权限（如审批人的权限），
   持有者可直接调用并伪造结果。应用以不授予任何角色的权限绕行（`qb.publication.system`），持有 `*` 的管理员仍可调用。建议平台提供"只允许系统身份执行"的声明（如 `pb.systemOnly()`）。
9. **（新，测试）平台测试基类每个测试前把时钟拨回起点，而 schema 按类共享**：同一类中后一个测试的写入可能早于前一个测试留下的版本（时态写入变基冲突），
   以同一计划时刻运行的任务被重放。应用测试以"拨到本类已有操作之后"绕行（`PublicationItSupport.install`）。建议平台只在类开始时设时钟，或提供按类的时钟。
10. **（小）`WithdrawApproval` 没有条件形式**（`when`）：`QB_PUBLICATION_ADVANCE` 以一个不存在的单据标识绕行。`HoldLock` 也没有逐步的等待时限（R6）。

不需要平台新能力的点：
- 受控参数（D40）与受控变更已支持 `SysApprovalRule`；
- `HoldLock`、`JobDefinition`、`EventSubscription`、`WithdrawApproval`、`LoadParams` 都已有；
- 试运行不对外开放，Q4 以保存输出中的 `problems` 代替；
- 账本预留在 MVP 中不需要（设计 §4.3）。

## 待确认问题

| 编号 | 问题 | 本计划的缺省 |
|---|---|---|
| Q4-1 | "自动通过"如何表达？ | 受控参数 `qb.review.publication.auto` 作为事实写进每次评估，加一条带条件的规则。另一种做法是只用规则、去掉参数 |
| Q4-2 | M-31 "存在出资方"的含义与时机 | 指商家入驻已审核通过。Q4 留 `SponsorChecks`，Q2 之前恒为通过，Q2 接上。另两种做法：等 Q2 完成，或 Q4 先建最小的商家记录 |
| Q4-3 | 提交后能否修改？ | 内容与全部策略都冻结，要改就复制；待审核的可以撤回（新增 `WITHDRAW`） |
| Q4-4 | 暂停的范围与恢复后的状态 | 可从 `SCHEDULED`、`LIVE` 暂停；恢复按时间回到 `SCHEDULED` / `LIVE`，或进入 `ENDED`；`FUNDS_SHORT` 留到 Q5/Q6 |
| Q4-5 | 审核通过时已过结束时间 | 退回草稿，原因码 `QB_PUBLICATION_EXPIRED_IN_REVIEW` |
| Q4-6 | 提交或上架时是否检查商家余额？ | 不检查、不预留，由 `FUNDS_SHORT` 处理。可选：要求余额不小于最高档奖励 |
| Q4-7 | 档位与时间的上限 | 档位 ≤ 20；奖励可为 0 但至少一档大于 0；投放期 ≤ 366 天；开始时间可以早于现在；预算上限不小于最高档奖励 |
| Q4-8 | "预算不足"指什么？ | 发布的预算上限或名额用完 → `ENDED`；商家钱包余额不足 → `FUNDS_SHORT`（沿用设计 §5.1） |
| Q4-9 | 模版移除时保留哪些文件？ | 只保留"已提交过"的发布所引用版本的文件；草稿发布随之不可提交 |
| Q4-10 | 管理员强制下架、商家提前结束 | 需求未提，Q4 不做 |

### Critical Files for Implementation
- /home/user/jabiz/backend/quizbuks/src/main/java/com/jabiz/quizbuks/content/QuizProcesses.java（`filesStillNeeded`、`QB_QUIZ_DELETE` 的锁与衔接）
- /home/user/jabiz/backend/quizbuks/src/main/java/com/jabiz/quizbuks/setup/QbSetupProcesses.java（提出审批规则）
- /home/user/jabiz/backend/quizbuks/src/main/java/com/jabiz/quizbuks/setup/QbRoles.java 与 QbPermissions.java、QbParams.java
- /home/user/jabiz/backend/app/src/main/java/com/jabiz/app/commerce/SupplierCertifications.java（`RequireApproval` 与审批事件续接的参考写法）
- /home/user/jabiz/backend/runtime/src/main/java/com/jabiz/runtime/approval/RequireApproval.java 与 docs/design/18-numbering-approvals-tasks.md §3、docs/design/11-ledger-events-jobs.md §4

（以上路径以 `origin/1.2/quizbuks` 为准，当前检出的分支可能不同。）
## 确认

2026-10-11：计划与待确认问题 Q4-1 – Q4-10 全部按上表的缺省处理。平台缺口 5（无规则时审批放行）与 6（定时任务逐项独立事务）不阻塞 Q4，按 `backend/quizbuks/CLAUDE.md` 第 2 节另行提给平台。
