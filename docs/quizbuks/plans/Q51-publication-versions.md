# Q51 公开的版本管理（2026-10-29 确认）

需求方（2026-10-29）：已经开始的公开要能换用 Quiz 的新版本（例如换了封面），换版本要再审核一次。
"公开也需要版本管理。这样答题结果就对应到哪一个版本了。但是答题显示是对应公开的……这样就不会重复答题了。
但是已经答题的人，看到始终是自己答题的题目和内容，永远都不会变。""一个公开，同时只能有一个版本是活动的。"

只在线 1.3。分两期：Q51a 切换版本（本文 §3–§5），Q51b 分析按版本区分（§6）。

## 1. 核对现状（2026-10-29）

- 发布提交即冻结（D-Q4-2）：内容（版本）与全部策略都不能改，要改就复制一份。复制是另一个发布：答过的人可以再答一次。
- 每个会话（`QbAttempt`）本来就记着开始时的 `versionId`，结果页、复查与评分都读会话的版本；卡片 `QbPublicationCard` 的版本列只在上架时插入。
- 移除模版时，只保留已提交发布正在用的版本的文件（`QbPublicationSchedule.versionId`）：换过版本的发布，旧版本的文件会被放掉，而旧会话还要读它们。
- 发布的审批流程（`QB_PUBLICATION_APPROVAL_RESULT`）只认 `PENDING_REVIEW` 的发布；管理员的审核队列只按状态筛选。
- 分析：每题统计 `QbQuestionStat` 以（发布，题号）为键，换版本后题号的含义会变（Q51b）。

## 2. 决定（2026-10-29 全部同意）

| # | 决定 |
|---|---|
| D-Q51-1 | **只换版本**：切换只把发布换成同一个 Quiz 的另一个版本；名称、地区、时间、档位、预算等策略照旧不能改（要改仍复制）。同一时刻只有一个版本在用 |
| D-Q51-2 | **满分必须相同**：档位以满分为界，换成满分不同的版本会让档位失效，拒绝（`QB_PUBLICATION_SWITCH_FULL_SCORE`） |
| D-Q51-3 | **再审核**：切换按发布的审核规则审核（`qb.publication.review`，审批内容 = 发布 + 新版本，策略不变）；`qb.review.publication.auto` 开启时立即生效。审核期间发布照旧以旧版本进行；同一时刻只有一个待审核的切换（`QB_PUBLICATION_SWITCH_PENDING`），可以撤回 |
| D-Q51-4 | **进行中的会话保持版本**：已开始的会话继续用开始时的版本，答完的结果永远对应那个版本；切换之后开始的会话用新版本。答过的人不能再答（同一个发布） |
| D-Q51-5 | **分析按版本区分**（Q51b）：每题的统计按版本分开，页面可选版本 |
| D-Q51-6 | **被管理员下架的发布不能切换**（`QB_PUBLICATION_SWITCH_TAKEN_DOWN`）；只有已上架而未结束的（`SCHEDULED`、`LIVE`、`FUNDS_SHORT`、`PAUSED`）能切换（`QB_PUBLICATION_SWITCH_NOT_RUNNING`） |

## 3. 设计（Q51a）

### 3.1 数据

- `QbPublication`（时态，封存表只加列，V41）新增 `processOnly` 字段：
  `pendingVersionId`、`pendingVersionLabel`、`pendingRequestId`、`pendingSince`（待审核的切换，生效、驳回、撤回或丢弃时清空），
  `switchRejectReason`（审核人的理由）、`switchRejectCode`（切换因发布结束、被下架、商家被封禁、Quiz 被移除而没有执行时的代码）。
- `QbPublicationCard` 的 `versionId`、`quizTitle`、`cover`、`video`、`questionCount` 与 `QbPublicationSchedule.versionId` 改为可变：只由切换生效时在发布锁与开始锁内更新。
- 新的普通表 `qb_publication_past_version`（实体 `QbPublicationPastVersion`）：发布换下的每个版本一行（发布、Quiz、版本、换下的时刻），只插入。
  移除模版时，这些版本的文件与正在用的版本一样保留（`QuizProcesses.loadSubmitted`），旧会话因此始终能显示它们的题目与材料。

### 3.2 流程

- `QB_PUBLICATION_SWITCH_VERSION`（`PublicationVersions.switchVersion`，`publication.write`，需要已验证邮箱与商家合同同意）：
  锁 `qb.quiz:<Quiz>`（共享）→ `qb.pub:<发布>` → `qb.pub-start:<发布>`（生效会改变开始读到的卡片）→ 审批的 `jabiz.approval:qb.publication:<发布>`。
  检查 §2 的全部条件与版本完整性（`QuizCompleteness`），该 Quiz 曾被下架时只能经人工审核（同提交，评审 M-d）。
  审批结果：`PENDING` 记下待审核的切换；`APPROVED`（同样内容批准过）或 `NOT_REQUIRED` 且开启自动审核时立即生效。
- 生效（`PublicationVersions.make`）：发布的 `versionId`、`versionLabel`、`approvalRequestId`，卡片的版本、标题、封面、视频与题数，排程行的版本，
  并为换下的版本插入一行 `QbPublicationPastVersion`。满分与题型不变，档位与计数不动。
- `QB_PUBLICATION_SWITCH_WITHDRAW`：撤回待审核的切换（`WithdrawApproval`），发布保持原版本。
- `QB_PUBLICATION_APPROVAL_RESULT` 认出切换的请求（`pendingRequestId`）：批准时再取开始锁，若发布仍在进行、未被下架、商家未被封禁、Quiz 未被移除即生效，否则清空并记代码；
  驳回时按存档的请求核对内容哈希后清空并记理由。
- `QB_PUBLICATION_ADVANCE` 结束发布时撤回待审核的切换（`QB_PUBLICATION_SWITCH_ENDED`）。

### 3.3 答题

- `QB_ATTEMPT_START`：已有会话的人读会话的版本；新会话读开始锁之前的卡片的版本，在开始锁内再核对卡片的版本，不同（其间切换生效）即 422 `QB_ATTEMPT_VERSION_CHANGED`，App 重新开始即可。
- 答完的人再开始照旧 `QB_ATTEMPT_ALREADY_TAKEN`。

### 3.4 页面

- 商家后台：发布页的版本提示在 Quiz 有更新的、满分相同的版本且发布可切换时给出"切换到 vX"（确认框说明再审核、旧版本照旧显示、答过的人不再答）；
  审核中显示"自何时审核中"与撤回；满分不同时说明原因；被驳回或没有执行时说明理由。
- 管理后台：审核队列含切换（模板 `qb.admin.publications` 的参数 `inReview`，按 `waitingSince` 排序，显示 "v1 → v2"）；
  审核页对切换显示新版本的内容与"从 vX 切换到 vY"，审批面板用切换的请求。

## 4. 测试

- `PublicationSwitchIT`：审核中旧版本照旧（新会话用旧版本、不能再申请第二个切换、审核队列中有它）；批准后卡片与新会话用新版本，旧会话继续并按旧版本评分，答过的人不能再答，换下的版本有记录；
  同一版本、其他 Quiz、满分不同、草稿、被下架、没有待审核时撤回、他人的发布均被拒绝；撤回与驳回（理由，再申请时清空）；自动审核立即生效；结束时丢弃待审核的切换并撤回请求；移除模版保留换下版本的文件。
- 商家后台 `publishing.test.tsx`、`api.test.ts`；管理后台 `pages.test.tsx`。

## 5. 不做

- 切换时改策略、换成其他 Quiz 的版本。
- 已开始的会话改用新版本（D-Q51-4）。

## 6. Q51b 分析按版本区分（下一步）

- `QbQuestionStat` 的键加上版本（新表或新列，按版本分别统计；旧行归入发布当时的版本）；游标与 `analytics-attempts.sql`、`sponsor/analytics-questions.sql` 按会话的版本汇总，不再连到卡片当前的版本。
- 商家分析的每题页面加版本选择（缺省当前版本），概览的人数不分版本。
