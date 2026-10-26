# Q33 电子奖品：系统发放与商户发送（计划，2026-10-25 确认；Q33a 实现中）

需求方（2026-10-25）：有些奖品是电子奖品（兑换码、礼品卡号、下载链接），不需要到线下领取。选定两种做法一起做：
(1) **系统直接发放**——商户事先把一批一次性的码（或一个共用的码）交给平台，答题人合格时系统当即发给本人；
(2) **商户发送**——不能预先生成的奖品，由商户在后台逐个填入内容发送，答题人确认收到。

## 1. 决定（已确认）

| # | 决定 |
|---|---|
| D-Q33-1 | **第四种支付方式 `DIGITAL_PRIZE`**（`RewardMethod`，字典多一项，`QbSponsorRewardMethods.methods` 上限 1–4）。像线下兑换一样是商户自己的奖品：结算不取钱包、不过账、平台不收费；`amount` 是申报的等值 Kudos，受 `qb.reward.max-per-attempt` 约束（景品表示法的上限同样适用，由法务认定）。管理员按商户允许，发布按发布选一种，与 Q22 一致 |
| D-Q33-2 | **发布的发放方式 `digitalDelivery`**（只在 `DIGITAL_PRIZE` 时写，三选一）：`POOL`——一批一次性的码，每位获奖者一个；`SHARED`——一个共用的码或链接，人人相同；`SPONSOR_SENT`——商户逐个发送。另有 `digitalDescription`（≤500 字，答题人看到的"这是什么奖品、怎么用"，对应线下的 `redeemDescription`）与 `digitalSendDays`（`SPONSOR_SENT` 时商户须在几天内发送，缺省参数 `qb.reward.digital.send-days` = 3） |
| D-Q33-3 | **码是秘密**：只以 `SealedSecrets` 密封保存（密钥 `JABIZ_SEAL_KEY`，上下文为**商户编号**：共用码随发布复制、码池属于发布、奖品记录属于答题，三处同一上下文才能互相开封），字段 `sealed…` 为 `f.sensitive()`，审计与导出中只见 `***`；明文只在两处出现：获奖的答题人自己打开（流程 `QB_DIGITAL_PRIZE_OPEN`，记入 `sys_reveal_record` 的同类记录 `openedAt`），以及商户登记时。站内信与邮件**不含码**，只链到结果页。输出 record 的组件名不用 `code`/`token`/`secret`（平台按名遮蔽），用 `prizeContent` 并标 **`@Sensitive(returned = true)`**（平台 16zh）：这是答题人唯一读到明文的地方，响应中原样返回；客户端每次调用都带幂等键，输出记入 `op_process_result`，其中与事件载荷、`input_summary` 中一律遮蔽（`DigitalPrizeIT` 断言 `op_process_result` 不含明文），`toString()` 遮蔽。共用码没有 `hint`（随发布密封一次，商户自己知道） |
| D-Q33-4 | **码池**：普通表 `QbDigitalCode`（`publicationId`、`seq`、`sealedContent`、`status` `AVAILABLE`/`ISSUED`/`REVOKED`、`issuedAttemptId`、`issuedAt`、`revokedAt`）。只经 `QB_DIGITAL_CODES_LOAD`（商户，粘贴一行一个，每次 ≤1,000 行、每行 ≤200 字，去重、去空；发布在 `DRAFT`/`REJECTED`/`APPROVED`/`LIVE`/`PAUSED` 都可追加）与 `QB_DIGITAL_CODES_REVOKE`（撤回全部未发出的；发布在 `PENDING_REVIEW`/`SCHEDULED`/`LIVE`/`PAUSED`/`FUNDS_SHORT` 期间拒绝，`QB_DIGITAL_POOL_LOCKED`——运行中的承诺不能抽走；先取发布锁再取池锁，与结算同序，提交时数码不会被撤回赶超）写入；已结束的发布不再加载（`QB_DIGITAL_POOL_ENDED`）；一个池至多 `MAX_POOL_WINNERS`（10,000）个码，序号连撤回的一起算（`QB_DIGITAL_CODES_TOO_MANY`），池的读取按此分页不会漏码；商户只看到数量（可用 / 已发 / 已撤）与每条的末 4 位（`MaskStyle.LAST4` 不适用于密封字段，由模板返回登记时另存的 `hint` 列）。**就绪检查**：`POOL` 的发布提交审核与上线时，`maxWinners` 必填且 ≤ 可用码数（`QB_DIGITAL_POOL_SHORT`）；运行中码用尽即 `winners == maxWinners`，发布照常按 Q10 的规则结束；万一结算时取不到码（并发边界），记录退为 `DUE`、`sendBy` 按参数，由商户发送（Q33b），不丢奖 |
| D-Q33-5 | **结算**（`AttemptFinishing`）：合格且方式为 `DIGITAL_PRIZE` 时写时态实体 `QbDigitalPrize`（`attemptId` 唯一、`publicationId`、`sponsorId`、`userId`、`amount`、`delivery`、`status`、`sealedContent`、`hint`、`sendBy`、`deliveredAt`、`deliveredBy`（`SYSTEM`/`SPONSOR`）、`confirmBy`、`confirmedAt`、`disputedAt`、`disputeNote`、`overdueAt`、`resolvedAt`、`resolvedBy`、`resolution`、`resolveNote`、`openedAt`）：`POOL` → 取锁 `qb.digital-pool:<发布>`（在 `qb.pub` 之后、钱包锁之前，06 §4.1 的顺序表更新），取 `seq` 最小的可用码，标 `ISSUED`，记录即 `DELIVERED`；`SHARED` → 复制发布版本上的 `sealedDigitalContent`，即 `DELIVERED`；`SPONSOR_SENT` → `DUE`，`sendBy = now + digitalSendDays`。`RewardStatus` 多 `DIGITAL`（`external()` 为真），`AttemptFinishing.settle` 的两路判断改为按方式的三路，结果消息 `QB_REWARD_DIGITAL` |
| D-Q33-6 | **商户发送与确认**（`SPONSOR_SENT`）：`QB_DIGITAL_PRIZE_SEND`（商户，`qb.publication.write`，输入 `prizeContent` ≤500 字、附注；密封后 `DELIVERED`，`confirmBy = now + qb.reward.cash.confirm-days`）→ 答题人 `QB_DIGITAL_PRIZE_CONFIRM` / `QB_DIGITAL_PRIZE_DISPUTE`（`qb.me`；`POOL`/`SHARED` 的也可争议，如码无效）→ 管理员 `QB_DIGITAL_PRIZE_RESOLVE`（权限 `qb.digital.resolve`，结论 `DELIVERED`/`UNDELIVERED`/`VOID`；`UNDELIVERED` 时商户可再发一次）。定时任务 `qb.digital-prize-overdue`（每小时）：`DUE` 过了 `sendBy` → `OVERDUE`（商户待办、站内信；不禁止答题人答题——逾期的是商户）；`qb.digital-prize-remind`（每日）提醒商户。与现金一样用待办 `CreateTask` 指派给商户（`qb.digital-prize.send`）与管理员（`qb.digital-prize.dispute`） |
| D-Q33-7 | **答题人看到的**：结果页 `result-reward` 新分支 `data-status="DIGITAL"`：`DELIVERED` 时显示说明与"显示奖品"按钮（**只在点击后**才 `QB_DIGITAL_PRIZE_OPEN`，页面打开不取；明文以等宽字显示并带复制按钮；是 `https://` 链接时另给"打开"），`DUE` 时显示"商户将在 X 日前发送"；历史页 `history-digital`；站内信种类 `DIGITAL_DELIVERED`、`DIGITAL_DUE`、`DIGITAL_OVERDUE`、`DIGITAL_RESOLVED`（三语，不含码）。首页徽章与筛选多"电子奖品"（`MethodBadge` 按方式给图标：`Ticket`） |
| D-Q33-8 | **商户后台**：向导第一步多方式"电子奖品"，选中后出现发放方式单选、说明、`SPONSOR_SENT` 的天数；`POOL` 时在发布页多"码池"面板（粘贴加载、计数、撤回）；新页 `/digital-prizes`（导航"Digital prizes"，`Ticket` 图标）：待发送列表（填内容发送）、已发放、争议中，按状态筛选，模板 `qb.sponsor.digital-prizes`。概要（`shared.tsx`）显示发放方式与说明 |
| D-Q33-9 | **后台扩展**：允许方式面板多一项；新页 `/qb/digital-prizes`（全部记录、争议的裁定）。投资人指标 `prizesRedeemed` 等加入电子奖品（`queries/qb/admin/investor-metrics.sql`） |
| D-Q33-10 | **不做**：经邮件寄码（Q29 的商户邮件服务器可以发，但码进邮件即离开平台的保护；先只站内，需要时另立阶段）；码的格式校验（商户自负）；码的到期日（说明里写） |

## 2. 分两个 PR

**Q33a**（方式与系统发放）：`RewardMethod` 与字典、V28 迁移（发布版本与卡片的 `digital_delivery`、`digital_description`、`digital_send_days`、`sealed_digital_content`；`qb_digital_code`；`qb_digital_prize_version`（时态，`quizbuks_create_temporal_table`）；CHECK 约束重建：`reward_method`、`qb_attempt_reward_status_check`）、`QbDigitalPrize` 与 `QbDigitalCode`、`QB_DIGITAL_CODES_LOAD/REVOKE`、结算三路、`QB_DIGITAL_PRIZE_OPEN`、`QB_DIGITAL_PRIZE_CONFIRM/DISPUTE/RESOLVE`、站内信 `DIGITAL_DELIVERED`/`DIGITAL_RESOLVED`、模板（`qb.app.feed`/`publication`/`public.publication` 的新列、`qb.sponsor.digital-codes`、`qb.sponsor.digital-prizes`、`qb.admin.digital-prizes`）、消息三语、商户向导与码池面板、App 结果页与历史页与徽章、后台扩展面板与页、`public-queries.json` 与场景快照。
**Q33b**（商户发送）：`SPONSOR_SENT`：`QB_DIGITAL_PRIZE_SEND`、逾期与提醒任务、待办、站内信 `DIGITAL_DUE`/`DIGITAL_OVERDUE`、商户"待发送"列表、App 的"待商户发送"状态、运维手册一节。

## 3. 测试

- core/单元：`RewardMethodTest`（四种、解析顺序）、码池加载的去重与限额、`QbDigitalPrize` 状态机。
- 集成：`DigitalPrizeIT`——(a) `POOL`：加载 3 个码、`maxWinners` 4 被就绪检查拒绝、3 位答题人各得一个不同的码、第 4 位合格但 `winners == maxWinners` 的规则生效（发布结束）、码在数据集与审计中只见 `***`、本人 `OPEN` 得到明文且 `openedAt` 记录、他人 404；(b) `SHARED`：两位答题人得到同一内容；(c) `SPONSOR_SENT`（Q33b）：`DUE` → 商户发送 → 答题人确认；争议 → 裁定 `UNDELIVERED` → 商户再发；逾期任务。时态表断言无 UPDATE/DELETE。
- 场景：`scenarios/qb/digital-prize.yml`（固定时钟，快照）；`setup` 快照随新参数更新（参数插入位置会使后续编号移位，按 Q22 的经验重指）。
- 前端 Vitest：商户向导（方式、发放方式、说明、码池面板）、电子奖品页；App 结果页（显示奖品、复制、链接）、历史、徽章；后台扩展页；axe。
- e2e：`digital-prize.spec.ts`——商户加载码、答题人合格后在结果页显示奖品并复制；Q33b：商户发送、答题人确认。`reward-methods.spec.ts` 的 `Method` 类型扩展。
- `app:check`、两个 SPA 的 `src/qb` 一致、`platformCheck`、`PublicQueriesSnapshotIT`。

## 4. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 中 | 码在 `op_process.input_summary` 或事件载荷中泄露 | 输入组件 `@Sensitive`；事件只带记录编号；`InboxIT` 断言消息体不含码 |
| 中 | 结算时码池并发：两次结算取到同一个码 | 取锁 `qb.digital-pool:<发布>`（独占）后再选码；`issuedAttemptId` 唯一索引兜底 |
| 中 | `JABIZ_SEAL_KEY` 更换后旧码不可读 | 与 Q29 同：`UnreadableSecretException` → 422 `QB_DIGITAL_UNREADABLE`，提示商户重新加载；运维手册写明保留副本 |
| 低 | 平台参数新增使场景编号移位 | 按 Q22 §4 的做法重指后更新快照 |
| 低 | 共用码被转发 | 说明中由商户写明限制；平台只保证每位合格者得到一次 |
