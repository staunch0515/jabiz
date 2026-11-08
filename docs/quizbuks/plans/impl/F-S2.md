# F-S2 实施计划：知识结构图——数据、版本与审核、条件、点亮（T1a）

规格：`docs/quizbuks/first-launch-spec.md` F-S2（以它为准；`Q90-trees.md` 只作背景，A2-12 / A2-13 取代其中的有效期、规则版本与"挑战发布"）。会话：S2。依赖：F-S1（已合入：审核去向、直营发起方）。

## 0. 先要需求方确认的（动手删除之前）

1. **删除清单**（§7）：规格写了"旧模型全部拿掉"，按 `backend/quizbuks/CLAUDE.md`"不随意减少已经做好的功能"，删之前逐项列给需求方、确认后才删（多一次确认）。确认之前只做新增的部分（F-S2a、F-S2b），删除与门槛的切换放在最后一个 PR（F-S2c）。
2. **待定的问题**（§8）：证书、协议答题、Q85 画面在 F-S3 之前的空档、权益门槛的主体。规格没有写清，按 F-S2 的授权不自己决定。

## 1. 拆分

| PR | 内容 | 迁移 |
|---|---|---|
| **F-S2a 图与版本** | 表、实体与数据视图；草稿、提交、撤回、审批结果（`ApprovalSubject` `qb.tree`）；平台模板与运营商副本（建立副本、逐项采用模板的新版本）；条件的三条限制与成环检查（保存时报告，提交时拒绝；启动检查 `QB_TREE`）；停用；待办与提醒；参数 | 一个（新表） |
| **F-S2b 绑定与点亮** | 发布绑定节点（草稿保存与提交的检查、复制、卡片冻结）；开始答题的答题前条件；`qb.app.feed` / `qb.app.publication` / `qb.system.audience-count` 的条件 SQL 与比较测试；合格后的事件与 `QB_TREE_EVALUATE`（连带判断、深度上限）；撤销点亮（四眼）；每日核对与补判断；统计模板；删除账号；`AttemptStatementBudgetIT` 新基线 | 一个（发布、卡片加列；点亮与撤销表） |
| **F-S2c 门槛与拆除**（需求方确认 §7 之后） | 三处门槛改为"点亮平台图上指定的节点"（受控参数，缺省空 = 关闭）；删除 §7 的全部；文档（`operations.md`、帮助、`CLAUDE.md`、词表） | 一个（删表） |

前端（编辑器、看图、向导的"绑定节点"）是 F-S3；本项只给流程与模板，并在管理系统扩展中给出审核所需的最小页面（§5）。

## 2. 数据（F-S2a、F-S2b）

| 表 | 种类 | 主要列 |
|---|---|---|
| `qb_tree` | 普通 | `tree_id`、`owner_kind`（`PLATFORM` / `OPERATOR` / `SPONSOR`）、`owner_id`（运营商编号或发起方用户编号；平台模板为空）、`subject_kind`（`TAKER` / `SPONSOR`，谁点亮；发起方图固定 `TAKER`）、`template_tree_id` 与 `template_version_no`（运营商副本所基于的模板与版本）、`current_version_id`、`status`（`ACTIVE` / `RETIRED`）、`created_at`、`revision` |
| `qb_tree_version` | 普通（生效后不改） | `version_id`、`tree_id`、`version_no`、`name` / `description`（多语言 jsonb）、`status`（`DRAFT` / `REVIEW` / `ACTIVE` / `SUPERSEDED` / `REJECTED`）、`content_hash`、`approval_request_id`、`submitted_at` / `decided_at`、`reject_reason` |
| `qb_tree_node` | 普通 | `node_id`（稳定的编号：模板的节点在副本中沿用；副本自加的另起）、`tree_id`、`created_in_version`、`active`（在生效版本中）、`preconditions`（生效版本中最外层"全部"里标"答题前"的节点编号，jsonb 数组：开始答题时锁外读它） |
| `qb_tree_node_def` | 普通（随版本） | `version_id`、`node_id`、`name` / `description`、`condition`（jsonb：`all` / `any` / `atLeast n` + `node:<编号>` / `passed`，每项可标 `beforeAnswer`）、`seq` |
| `qb_tree_lit` | **只追加**（`writeOnce`） | `lit_id`、`subject_kind`（`USER` / `SPONSOR`）、`subject_id`、`node_id`、`tree_id`、`lit_at`、`basis`（jsonb：所依据的会话，或连带的节点） |
| `qb_tree_lit_revocation` | **只追加**（`writeOnce`） | `revocation_id`、`lit_id`、`reason`、`requested_by`、`approval_request_id`、`revoked_at` |
| `qb_publication_version`、`qb_publication_card` | 加列 | `tree_node_id`、`tree_id`（可空；卡片冻结） |

- 永久点亮（A2-12）：没有有效期与规则版本；"此刻点亮" = 有点亮记录且没有撤销记录。
- 一个节点只有一条有效的点亮（同一主体再次合格不写新记录：规格"已点亮的人仍可以答……不再写新的点亮记录"）；撤销后再次满足会写新的一条。
- 节点编号：`UUIDv7`；副本建立时复制模板生效版本的节点（同编号）。模板的新版本不覆盖副本：副本的页面列出"模板有、你还没采用的改动"（新增、改了条件、删除的节点），运营商逐项采用，采用即写进副本的草稿。
- 平台模板的点亮者为学者时只有平台的一份（不建副本：`QB_TREE_COPY` 只接受点亮者为发起方的模板）。

## 3. 规则（纯 Java，`tree.TreeRules` / `TreeStates`）

- 条件只用 `all` / `any` / `atLeast(n)`；对象只有 `node:<编号>` 与 `passed`（合格过一个绑定本节点的发布）。三条限制：`beforeAnswer` 只能在最外层的 `all`；每个节点至多一个 `passed`，且不能标 `beforeAnswer`；不成环（同图与跨图，按生效版本加草稿）。平台图不能引用发起方图；发起方图可以引用平台图（点亮者为学者的那份）。
- 上限：`qb.tree.max-nodes`、`qb.tree.max-conditions-per-node`、`qb.tree.max-depth`、`qb.tree.sponsor.max-trees`（普通参数，`LoadParams`）。
- 点亮 = 条件此刻成立（`passed` 看主体在绑定本节点的发布上有合格的已完成会话）。
- 节点对一个人的状态（F-S3 显示）：已点亮 / 可答（答题前条件满足、未点亮、有他此刻能开始的开放发布）/ 等待（答题前条件满足、没有可开始的）/ 差条件 / 锁定。"能开始"按受众、邀请、地区、境外视频（F-S11）、屏蔽（F-S8）、"一个发布只答一次"判断，与 `qb.app.publication` 的 `canStart` 同一段 SQL。
- Java 一份定义；三个模板（`qb.app.feed`、`qb.app.publication`、`qb.system.audience-count`）各一份答题前条件的 SQL，`TreeIT` 比较（照 `PrerequisiteIT`）。

## 4. 流程

| 流程 | 作用 | 锁 |
|---|---|---|
| `QB_TREE_CREATE` | 发起方建自己的图（至多 `qb.tree.sponsor.max-trees`）；平台建模板（`qb.tree.platform.write`） | `qb.tree-owner:<所有者>` |
| `QB_TREE_COPY` | 运营商负责人基于模板某版本建副本（点亮者为发起方的模板） | 同上 |
| `QB_TREE_DRAFT_SAVE` | 整体保存草稿（带 `baseRevision`），输出全部问题 | `qb.tree:<图>` |
| `QB_TREE_ADOPT` | 副本逐项采用模板的改动 | `qb.tree:<图>` |
| `QB_TREE_SUBMIT` / `_WITHDRAW` | 提交审核（`RequireApproval`，内容哈希）/ 撤回；新版本删除仍有开放发布绑定的节点即 422 并列出 | `qb.tree:<图>` |
| `QB_TREE_APPROVAL_RESULT`（`internal`） | 生效（写节点行的 `preconditions`、旧版本 `SUPERSEDED`）或驳回（附理由） | `qb.tree:<图>` |
| `QB_TREE_RETIRE` | 停用整张图；绑定其节点的发布经平台的结束流程（`QB_PUBLICATION_*` 的结束子流程）结束并通知发起方 | `qb.tree:<图>` → 发布锁（按编号升序） |
| `QB_TREE_EVALUATE`（`internal`） | 对一个主体的一个节点判断，点亮即写记录并连带判断依赖它的节点（深度 ≤ `qb.tree.max-depth`） | `qb.tree-lit:<主体>` |
| `QB_TREE_LIT_REVOKE` + 审批结果 | 撤销点亮（运营商成员或平台，理由必填，另一位成员批准）；写撤销记录、通知本人 | `qb.tree-lit:<主体>` |
| `QB_TREE_RECHECK_ITEM`（逐项任务 `qb.tree-recheck`） | 每日：合格了、条件满足却没有点亮记录的，重新判断并计入告警 | `qb.tree-lit:<主体>` |

- **审核**：审批对象 `qb.tree`；发起方图的范围是所属运营商（入口范围 `operator`，F-S1 的改派随之移动），由另一位成员审核；平台模板由另一位管理员审核（规则 `qb.tree.platform.review`，由 `QB_SETUP` 提出）；运营商副本由同一运营商的另一位成员审核。待审的版本以 `CreateTask` 指派给审核方，超过 `qb.tree.review.remind-after`（普通参数，缺省 3 天）由逐项任务提醒一次。
- **锁序**（写进 `backend/quizbuks/CLAUDE.md`）：`qb.tree-owner:<所有者>` → `qb.tree:<图>` → 发布锁 → …；`qb.tree-lit:<主体>` 独立，不与发布、开始、钱包锁同时持有。
- **点亮的触发**：完成答题时，卡片绑定节点且合格，以 `PublishEvent.when` 发 `qb.tree.passed`（普通发布什么也不发）；消费者运行 `QB_TREE_EVALUATE`。**风险**：若这条发件箱插入在发布锁内多出一条语句（`AttemptStatementBudgetIT`），改为推荐奖励的做法（逐项任务事后发现合格，答题路径一条也不加），在 PR 中说明。
- **开始答题**：卡片冻结 `treeNodeId` / `treeId`；只有绑定节点的发布在锁外多一条语句：`QueryEntities.all`（节点行 + 本人在本图与平台学者图上的点亮与撤销记录），Java 判断答题前条件，不满足即 422 `QB_TREE_NOT_UNLOCKED`（问题中给出缺的节点）。普通发布不变。

## 5. 模板与页面（本项）

- 模板：`qb.sponsor.trees` / `qb.sponsor.tree`（本人的图、版本、节点、各节点的点亮人数与等待人数，人数按最小分组）、`qb.operator.trees`（运营商的副本与辖下发起方的图）、`qb.admin.trees`（平台模板、全部的图）、`qb.app.trees` / `qb.app.tree`（学者：平台图与参与过的发起方图，节点与本人的状态）、`qb.admin.tree-lit`（按主体或节点查点亮与撤销）。
- 管理系统扩展：审核页（版本的节点与条件的只读列表、与生效版本的差异、`ApprovalPanel`），撤销点亮的对话框。编辑器与看图是 F-S3。

## 6. 测试

- `TreeRulesTest`（纯 Java）：三条限制、成环（同图、跨图）、上限、`atLeast`、连带深度。
- `TreeIT`：草稿 → 提交 → 生效 / 驳回；撤回；删除有开放发布绑定的节点被拒；模板与副本（节点编号沿用，模板升级不覆盖，逐项采用）；点亮与连带；已点亮者再答不写新记录；只有能开始的人"可答"；撤销点亮（四眼）；停用图结束发布；启动检查 `QB_TREE`；Java 与三个模板的比较。
- `AttemptStatementBudgetIT`：普通发布的开始与提交不变；绑定节点的开始锁外多 1 条、锁内不变；绑定节点的合格提交（见 §4 的风险）。
- 场景回放、`ErasureIT`（`qb_tree_lit` 登记到 `PersonalDataEraser`）、`OperatorScopes` 的登记、审批的范围移动（改派时移动发起方图的待审版本）。

## 7. 删除清单（待需求方确认，F-S2c）

（盘点中）

## 8. 待需求方决定的问题

（见 §7 之后）
