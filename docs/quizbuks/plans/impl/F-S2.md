# F-S2 实施计划：知识结构图——数据、版本与审核、条件、点亮（T1a）

规格：`docs/quizbuks/first-launch-spec.md` F-S2（以它为准；`Q90-trees.md` 只作背景，A2-12 / A2-13 取代其中的有效期、规则版本与"挑战发布"）。会话：S2。依赖：F-S1（已合入：审核去向、直营发起方）。

## 0. 需求方的确认（2026-11-08，经总控）

需求方答复"按建议"：§7 的删除清单 D1–D8 全部确认（多一次确认已完成），§8 的五个问题都按建议：

1. Q77 证书保留（表、版式、公开核实页、学者公开页），F-S2c 起暂停按认证签发（`QB_CERTIFICATE_ISSUE` 拒绝），F-S5 改为节点证书。
2. 协议理解答题卡（Q86）删除（同 D3）；以后在学者的平台图上建"了解条款"的节点。
3. Q85 画面：后端照常合入；删除前端画面（D4 商户主页分层、D5 三处画面）的部分推迟到 F-S3 一起做，期间这些画面读不到数据时显示"即将更新"。
4. 绑定节点的发布可以不设奖励档位。
5. 门槛的点亮主体：申请发起方、推荐人按人（学者的平台图，`USER`）；两种权益按发起方（`SPONSOR`，F-S4 起才能点亮）。

每个 PR 的描述按 D 号逐条列出它删除的项。迁移号在开 PR 时按 `1.3/quizbuks` 当时的最大号 + 1 定（V81、V82 已占）。

## 1. 拆分

| PR | 内容 | 迁移 |
|---|---|---|
| **F-S2a 图与版本** | 表、实体与数据视图；草稿、提交、撤回、审批结果（`ApprovalSubject` `qb.tree`）；平台模板与运营商副本（建立副本、逐项采用模板的新版本）；条件的三条限制与成环检查（保存时报告，提交时拒绝；启动检查 `QB_TREE`）；停用；待办与提醒；参数 | 一个（新表） |
| **F-S2b 绑定与点亮** | 发布绑定节点（草稿保存与提交的检查、复制、卡片冻结）；开始答题的答题前条件；`qb.app.feed` / `qb.app.publication` / `qb.system.audience-count` 的条件 SQL 与比较测试；合格后的事件与 `QB_TREE_EVALUATE`（连带判断、深度上限）；撤销点亮（四眼）；每日核对与补判断；统计模板；删除账号；`AttemptStatementBudgetIT` 新基线 | 一个（发布、卡片加列；点亮与撤销表） |
| **F-S2c 门槛与拆除**（需求方确认 §7 之后） | 三处门槛改为"点亮平台图上指定的节点"（受控参数，缺省空 = 关闭）；删除 §7 的全部；文档（`operations.md`、帮助、`CLAUDE.md`、词表） | 一个（删表） |

前端（编辑器、看图、向导的"绑定节点"）是 F-S3；本项只给流程与模板，并在管理系统扩展中给出审核所需的最小页面（§5）。

**实施记录（F-S2a）**：迁移 V86（四张普通表；原为 V85，F-S10 的 #532 先合入占用了 V85）。审核的提醒、读取的模板（§5）与管理系统扩展的审核页移到 F-S2b，与点亮、每日核对一起做（F-S2a 先让图与版本、审核与副本可以经流程使用并有测试）。新节点的编号由服务器给出：一次保存以平台的 `RandomBytesStep` 取 32 字节，按序号派生 UUID（点亮记录以节点编号认节点，编号不能由发起方指定，否则可以冒用平台图的节点）。被驳回的版本就是要改的草稿（再次保存与提交，版本号不变），审核中的版本一保存即撤回审核、回到草稿。启动检查 `QB_TREE` 只警告（数据问题不阻止启动，好让人修正）。

**F-S2b 再拆为两个 PR**（2026-11-08，范围大，先让点亮可用）：

- **F-S2b1 绑定与点亮**（迁移 V88：#539 先合入占用了 V87）：发布与卡片加 `tree_id` / `tree_node_id`；草稿保存与提交检查节点（本人图或学者的平台图中生效、`bindable` 的节点；点亮者为发起方的图在 F-S4 之前不能绑定）、复制随发布、卡片冻结；绑定节点的发布可以不设奖励档位（§0 第 4 条）；开始答题的答题前条件（锁外一条语句：节点行 + 本人的点亮与撤销）；`qb.app.feed` / `qb.app.publication` / `qb.system.audience-count` 的同段 SQL 与比较测试；合格且卡片绑定节点时发 `qb.tree.passed`，`QB_TREE_EVALUATE`（`internal`）点亮并以 `qb.tree.lit` 连带判断依赖它的节点（每层一个事务，深度不超过 `qb.tree.max-depth`；依赖关系是节点生效时写下的普通表 `qb_tree_node_ref`，节点行另记生效的条件 `condition`）；停用图时节点行一并失效；`AttemptStatementBudgetIT` 新基线；删除账号的登记。
- **F-S2b2 更正与运营**：撤销点亮（四眼）、每日核对与补判断、审核的待办与提醒、新版本删除有开放发布绑定的节点即拒绝、停用图时结束绑定的发布、读取的模板（§5）与管理系统扩展的审核页。
- **F-S2b3 再拆为两部分**（2026-11-08）：**F-S2b3a 审核侧**——模板 `qb.admin.trees`（全部图）、`qb.operator.trees`（运营商成员：本运营商的副本与它负责的发起方的图）、`qb.admin.tree-version`（一个版本逐节点对照生效版本：新增、修改、未变、去掉，及各节点进行中的绑定发布数）、`qb.admin.tree-lit`（点亮记录及其撤销与待审的撤销申请），运营商成员的 `qb.operator.tree-version` / `qb.operator.tree-lit`（同列，只限本运营商负责的图），与管理系统扩展的知识结构图列表与审核页（`TreesPage`、`TreeReviewPage`：读完且无误才给出审批面板，持 `qb.tree.lit.revoke` 才能申请撤销点亮）；**F-S2b3b**——学者与发起方的模板（各节点的点亮、可答与等待人数按最小分组），与消费它们的 F-S3 一起做。

**实施记录（F-S2b1）**：可绑定的是发起方自己的图，以及（运营商的直营发起方，即官方出题方，F-S1）学者点亮的平台图中生效、要求合格的节点；运营商的副本与点亮者为发起方的平台图在 F-S4 之前不能绑定。卡片冻结节点；审批内容在绑定时含节点（未绑定的哈希不变）。点亮的触发照计划用事件：写缓冲下发件箱的写入随其他写入、合格提交的语句数不变，关掉写缓冲时锁内多一条（`AttemptStatementBudgetIT` 两组数字都断言）；绑定节点的开始锁外多一条，锁内不变。连带判断每层一个事务（`qb.tree.lit` 的消费者），层数到 `qb.tree.max-depth` 为止。撤销的流程在 F-S2b2，所以本 PR 中撤销记录只有表与读取（开始、三个模板与 Java 的判断都已计入撤销）；与 Java 的比较测试覆盖未点亮、未合格、合格点亮与进行中，含撤销的比较随 F-S2b2 的撤销流程补上。

撤销记录也带主体（`subject_kind` / `subject_id`），开始答题因此能在一条语句中读出本人的点亮与撤销。点亮记录只含用户编号（同平台 D62 第 4 条，记录留下）；删除账号时 `qb_tree_lit` 以 `keep` 登记、清除点亮依据 `basis`，撤销记录清除理由 `reason`。

**F-S2c 再拆为两个 PR**（2026-11-08）：

- **F-S2c1 门槛与停用旧模型的入口**（无迁移）：
  - 四个受控参数 `qb.tree.gate.sponsor-apply-node`、`.referrer-node`、`.benefit-provider-node`、`.benefit-sponsor-node`（规格的参数表），值为 `<图编号>/<节点编号>`（节点编号只在一张图内唯一）；照 Shopify 的两个参数：列入 `ControlledParams`（只经受控变更），`QB_SETUP` 不建立，以 `LoadParams.optional` 读，**缺少即关闭**（规格"缺省为空 = 关闭"）。
  - 门槛只由 `tree.TreeGates` 判断：参数指向**点亮者为学者的平台图**中生效的节点，且调用者（`USER`，操作人）此刻点亮它（`TreeLights`：有点亮记录、没有撤销）。读取是一条 `QueryEntities.all`（节点行、图、本人的点亮与撤销），不在答题路径上。参数写错（格式不对、节点不存在、不是学者的平台图、节点已失效）即关闭，并由每日核对 `qb.tree-check` 报 `GATE_INVALID`（待办给 `qb.admin.trees.read`）。
  - 推荐链接（`QB_REFERRAL_LINK`，已生成的链接照旧有效）与两种权益（上架权益、洽谈与采购单）改用 `TreeGates`；错误码不变（`QB_REFERRAL_NOT_REFERRER`、`QB_BENEFIT_NOT_CERTIFIED`），三语文字改为"先点亮指定的节点"。模板 `qb.sponsor.benefit-access` / `-catalog` 改为同一判断（读 `now:SysParam`），发起方页面的权益页随之显示"已开放"或"需要点亮的节点"。
  - D2：去掉"持有认证即不能答该轮"（开始答题的 `RoundHolders.load/refuse`，`qb.app.feed` / `qb.app.publication` / `qb.system.audience-count` 的同段 SQL，`AttemptStatementBudgetIT.aStartOfASponsorsRound`）。
  - D8（§0 第 1 条）：`QB_CERTIFICATE_ISSUE` 一律 422 `QB_CERTIFICATE_PAUSED`；已签发的证书、公开核实页与学者公开页照旧；App 的证书页不再给出签发。
  - **发起方申请资格暂不改**：门槛是 `SignUpGuard`（`SponsorApplyGuard`，加入 `sponsor` 入口），守卫只能预读"用户编号字段 = 该用户"的行，读不到业务参数（平台 10 §15.15）。已作为平台缺口报给总控（2026-11-08，S1 补）；补上之前照旧按认证判断。
- **实施记录（F-S2c1）**：门槛只在 `tree.TreeGates`（纯 Java 的 `parse` / `open` / `names` 与读取的步骤），点亮按图与节点两者核对（节点编号只在一张图内唯一，副本沿用模板的编号）。D2 的两个测试改为现在的行为（发起方认证的一轮像普通发布一样开始，语句数不加；持有者照常答其他轮），不删。D8：`QB_CERTIFICATE_ISSUE` 只剩拒绝，签发的代码删去；测试以 `PlayItSupport.issuedBefore`（照原流程写入证书行并由持有人签发单据）代表"暂停之前签发的"，核实、公开页、下载、删除账号照旧验证。e2e 的证书用例改为"暂停签发"的画面（签发与核实由 `CertificateIT` 覆盖）。
- **F-S2c2 拆除**（迁移：删表）：发起方申请资格改用 `TreeGates`（依赖上面的平台能力）；删除 D1、D3、D4、D5、D6 的后端与 D1、D3、D6 的页面，D4、D5 的页面推迟到 F-S3（§0 第 3 条，期间读不到数据即显示"即将更新"）；文档（`operations.md`、帮助、`CLAUDE.md`、词表）。

- **F-S2c2 再拆为 a / b**（总控同意）：c2a 删除 D3–D6，迁移 V92 删除 `qb_publication_prerequisite`；c2b 删除 D1 并把发起方申请资格改为门槛节点，等平台 D80（守卫读节点与本人点亮）。
- **实施记录（F-S2c2a）**：
  - `qb_sponsor_certification` 不删、只读：已签发的证书行不存认证的名称，核实页、学者公开页、"我的认证"与持有判断都从这张表读，D8 保留已签发的证书。发起方不能再建认证或轮，已登记的轮不再记合格（领取不再为 `sp:` 记录），首页与发布页把它们当普通发布。总控定为保留只读，F-S5 改为节点证书时迁移后再删；D1 的表在 c2b 同样处理。
  - `TierCoverage.rewardOptional` 留着：F-S2b1 绑定节点的发布用它，只去掉了轮的情形。
  - D4、D5 的页面：发起方 `/tree` 与 App 商户主页的树显示"即将更新"，F-S3 换成看图；`share/levels.ts`、`qb/TechTree.tsx`、`qb/tree.ts` 留到 F-S3。结果页的"看看下一步"（D5 清单内）提前删除：它只在答卷互为前置时出现，前置删除后不会再出现。
  - 发起方的旧地址 `/certifications` 转到资源库 `/assets`。
- **实施记录（F-S2c2b）**：
  - 发起方申请资格（D7）：注册守卫 `tree.SponsorApplyGuard`（原 `certification.SponsorApplyGuard`）按平台 D80 预读：参数所指的节点与图（`SignInLoad.global(…).whereParam`）、本人在该图与节点上的点亮（`SignInLoad.of(…, "subjectId").whereParam`）、本人的撤销；判断用 `TreeGates.parse` / `open`，没有参数即关闭。只管已登录学者加入发起方入口（`SIGNED_IN_JOIN`）；运营商自己的发起方照旧放行。拒绝的原因码 `qb.certification-required` 不变，三语文字改为"先点亮……的节点"。V96 为撤销按 `subject_id` 建索引（D80 的启动检查要比较的列有以它开头的索引）。
  - D1 删除：`Certifications`、`CertificationHolding`、`CertificationProcesses`（`QB_CERTIFICATION_ROUND_ADD/_CLOSE/_GRANT/_REVOKE/_CLAIM/_OVERVIEW/_OVERVIEW_OF`）、`RoundHolders`、`CertificationCodes`、轮的实体与数据视图、认证字典、本人记录的视图、权限 `qb.certification.manage` / `admin`（及角色中的授予）、首页与发布页的 `certification` 列、草稿删除时连带删轮；管理系统扩展的「认证」页（两处菜单）；App"成为发起方"的认领一步（改为直接申请，由守卫判断并说明原因）、卡片与发布页的"合格授予"。V96 删除 `qb_certification_round`。
  - **只读留下的**（总控 2026-11-08 的规则：已签发证书要读的先保留）：`qb_certification_record`（核实页与学者公开页读之前记下的撤销）、`qb_sponsor_certification`（名称与签发方）、证书名的三语文字（后端 `qb.certification.<键>`、App 与管理系统扩展的名字）。F-S5 改为节点证书时迁移后再删。
  - "我的认证"（`QB_MY_CERTIFICATIONS`）改为列出仍有效的已签发证书（未到期、没有持有开始之后的撤销，每种认证最新的一张），不再有"持有而没有证书"的一项；暂停签发的说明放在列表下方。
  - `qb_official_publisher` 早已在 V78 删除。

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

按用户看得到的功能列出；每项之下是要删的代码（盘点于 2026-11-08，路径在 PR 中逐一列出）。`qb_official_publisher` 已在 F-S1 删除（V78），不在此列。表一律删除、不搬数据（平台在测试，重置数据库）。

| # | 功能（删除） | 涉及 |
|---|---|---|
| D1 | **平台的认证、轮与记录**（Q69/Q70）：认证目录（发起方申请、推荐人、三种协议理解、两种权益）、轮的登记与关闭、人工授予与收回、认领合格、平台与运营商的认证概况 | 包 `certification` 的 `Certifications`、`CertificationHolding`、`CertificationProcesses`（`QB_CERTIFICATION_ROUND_ADD/_CLOSE/_GRANT/_REVOKE/_CLAIM/_OVERVIEW/_OVERVIEW_OF`）、`RoundHolders`、`QbCertifications`、`CertificationCodes`；表 `qb_certification_round`、`qb_certification_record`；字典两个；权限 `qb.certification.manage`、`qb.certification.admin`；管理系统扩展的「认证」页（运营商与平台两处菜单）；App 的"成为发起方"中认领合格的一步；共用 `qb/certifications.ts` 与卡片上的认证标记 |
| D2 | **答题时"持有认证即不能答该轮"**（Q70a） | 开始答题的 `RoundHolders.load/refuse`；`qb.app.feed` / `qb.app.publication` 的同段 SQL；`AttemptStatementBudgetIT.aStartOfASponsorsRound` |
| D3 | **协议理解答题卡**（Q86）：App 首页与发起方概况的"理解条款"卡、受控参数 `qb.understanding.enabled`、启动检查 `CERTIFICATION_LEGAL` | `qb.app.understanding`、`UnderstandingCards.tsx`、`understanding.ts`、`CertificationLegalCheck`、`operations.md` §7.16、e2e `agreement-quiz.spec.ts`（见 §8 问题 2） |
| D4 | **发布的前置条件**（Q76，含 Q86 的"持有认证"前置）：向导的"前置"一步、发布页"先完成：X"、商户主页按层显示 | `QbPrerequisites`、`PublicationPrerequisites`（`QB_PUBLICATION_PREREQUISITES_SAVE`）、`PrerequisiteRules`、`PrerequisiteCheck`、`PrerequisiteRows`、`QB_PREREQUISITE_*`；表 `qb_publication_prerequisite`；三个数据视图与字典；`qb.sponsor.publication-prerequisites`；feed / publication / audience-count / sponsor-page-publications 中的前置 SQL；App `share/levels.ts`；发起方 `Prerequisites.tsx`；e2e `prerequisites.spec.ts`；`AttemptStatementBudgetIT` 两个前置用例 |
| D5 | **Q85 的科技树**：发起方页面「科技树」（`/tree`，人数、名单、一个人的树）、App 商户主页上的树与结果页"接下来"的链接、参数 `qb.tree.platform-handle` | `tree/TreeStates`（重写为新模型）；`qb.sponsor.tree`、`tree-people`、`tree-person`、`qb.app.sponsor-tree`；共用 `qb/TechTree.tsx`、`qb/tree.ts`；发起方 `src/tree/`；e2e `tech-tree.spec.ts`（见 §8 问题 3） |
| D6 | **发起方自己的认证与轮**（Q88a）：资源库的「认证」标签、发布页的"登记为认证的一轮"、0 奖励的轮、受控开关 `qb.certification.sponsor-rounds.enabled` | `QbSponsorCertifications`、`SponsorCertifications`、`SponsorCertificationProcesses`（`QB_SPONSOR_CERTIFICATION_*`）、`PublicationRounds`（`QB_SPONSOR_ROUND_SET/_CLOSE`）、`TierCoverage` 的 `rewardOptional`；表 `qb_sponsor_certification`；三个数据视图与字典；`qb.sponsor.certifications`、`qb.sponsor.publication-round`、`qb.admin.sponsor-certifications`；发起方 `src/certifications/` 与资源库标签；`operations.md` §7.19；e2e `sponsor-certification.spec.ts`（0 奖励见 §8 问题 4） |
| D7 | **以认证作门槛的三处**：申请发起方（`SponsorApplyGuard` 查 `SPONSOR_APPLY`）、生成推荐链接（`REFERRER`）、两种权益（`BENEFIT_PROVIDER` / `BENEFIT_SPONSOR`） | 不删功能，改门槛：改为"点亮受控参数指定的节点"（规格），缺省空 = 关闭 |
| D8 | **Q77 证书**（按认证签发）：本人签发证书、公开核实页、公开的学者页 | 见 §8 问题 1：建议本项不删，只停止按认证签发，F-S5 改为节点证书 |

## 8. 待需求方决定的问题（已按建议决定，见 §0）

1. **Q77 证书在 F-S5 之前**：证书现在按"认证"签发，认证删除后没有签发的依据。建议 (a)：保留证书表、版式、公开核实页与学者公开页，已签发的照常可核实；F-S2c 起暂停签发（`QB_CERTIFICATE_ISSUE` 拒绝），F-S5 改为节点证书。或 (b)：本项一并删除，F-S5 重做。
2. **协议理解答题卡（Q86）**：规格只写了删 Q86 的"发布前置"。建议删除答题卡、三种协议理解的认证与开关（同 D3）；以后在学者的平台图上建"了解条款"的节点，由 F-S3 的看图显示。或保留答题卡、改为读平台图上指定的节点（多一个受控参数）。
3. **Q85 画面与 F-S3 之间的空档**：F-S2c 删了旧的树页，新的看图在 F-S3（要等 P3）。建议：F-S2c 的后端改动照常合入，**删除前端画面（D4 的商户主页分层、D5 的三处画面）的部分推迟到 F-S3 的 PR 中一起做**，期间画面读不到数据时显示"即将更新"。或接受空档。
4. **0 奖励的发布**：Q88a 允许"认证的一轮"不设奖励；轮删除后，绑定节点的发布是否允许不设奖励档位？建议允许（规格"照常得奖励"只说有奖励时照常，平台图的入门节点常常不给奖励）。
5. **门槛由谁点亮**：申请发起方与推荐人按**人**（学者的平台图，主体 `USER`）；两种权益按**发起方**（点亮者为发起方的平台图，主体 `SPONSOR`，F-S4 起才能在发起方页面答题点亮）。在 F-S4 之前，权益门槛的节点即使设了也无人能点亮。请确认，或改为两种权益也按人。
