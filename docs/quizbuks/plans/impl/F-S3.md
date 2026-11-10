# F-S3 实施计划：知识结构图的界面（T1b）

规格：`docs/quizbuks/first-launch-spec.md` F-S3（含 F-S2 的三态与不可见规则）。后端（F-S2）已合入；本项补上 F-S2 推迟的 **F-S2b3b**（学者与发起方的模板），其余全是界面。负责：S2。状态：**总控 2026-11-10 批准**（§8 的四点已定），从 F-S3a 开始。

## 0. 现状（2026-11-10 盘点）

- 后端流程齐全：`QB_TREE_CREATE` / `_COPY` / `_DRAFT_SAVE`（整体保存，带 `baseRevision`，输出全部问题）/ `_ADOPT` / `_SUBMIT` / `_WITHDRAW` / `_RETIRE` / `_LIT_REVOKE`；发布输入已有可选的 `treeId` / `treeNodeId`（`PublicationNodes.binding()` 校验：本人的图；平台学者图的节点只限直营发起方）。
- 模板只有审核侧（`qb.admin.trees` / `tree-version` / `tree-lit`、`qb.operator.*`）与 F-C4 导出用的 `qb.sponsor.package-trees` / `package-tree-nodes`。**没有**：学者的节点状态、发起方编辑器要读的图与版本、向导可选的节点、客服按人查看状态。
- 前端：发起方 `/tree` 与 App 商户主页是"即将更新"占位；`qb/TechTree.tsx`、`qb/tree.ts`、`share/levels.ts`（Q85）未被页面使用；向导 4 步，没有"绑定节点"；管理系统扩展只有列表与审核页，没有编辑器。
- Q108 框架：F-F1/F-F2 的平台组件已合入（`PageHeader` 新槽位、`PageTabs`、三种页面模板）；发起方的"区域"（F-F3，S3）与 App（F-F4）未开始。

## 1. 拆分（5 个 PR，依次）

| PR | 内容 | 迁移 |
|---|---|---|
| **F-S3a** 读模型 | F-S2b3b 的模板与客服查看；无界面 | 无 |
| **F-S3b** 整图组件 | 共用的 `qb/tree`（排版、五种状态、键盘、文字列表视图），删除 Q85 的 `TechTree` / `tree.ts` / `levels.ts` | 无 |
| **F-S3c** 看图 | App"我的图"、商户主页的图、发起方的图页（只读部分） | 无 |
| **F-S3d** 编辑与绑定 | 发起方编辑器（条件编辑器、保存 / 提交 / 撤回、驳回原因、版本历史、"有开放发布不能删"）；向导第一步"绑定节点" | 无 |
| **F-S3e** 管理系统 | 平台模板编辑器与四眼审核、运营商副本编辑器与采用模板改动、客服按人查看；e2e 闭环；帮助与手册 | 无 |
| **F-S3f** 节点人数快照 | "答过未点亮"人数的每日快照（逐项任务按图、新表）与发起方 / 管理页上的一列 | 新表一张 |

**不加迁移**（F-S3a–e）：所需数据（图、版本、节点、条件、点亮与撤销、审批与驳回理由）都在已有的表里。F-S3f 的快照要一张新表。

## 2. F-S3a 读模型（后端）

- **"能开始"不另写**：`qb.app.feed` 加参数 `tree`（图编号：只列绑定这张图的节点的发布）与结果列 `treeId` / `treeNodeId`。首页的全部条件（受众、邀请、地区、境外视频、屏蔽、答题前条件、一人一次）因此原样适用（同 F-S10 的 `shorts`、F-S13 的 `push`）；`BlockIT` 等比较测试照旧覆盖它。某节点在其中有行 = **可答**，这些行就是"链接到能开始的发布"。
- `qb.app.tree`（`qb.app.me`，参数 `treeId`）：生效版本的每个节点给出**事实**——名称、说明、条件（JSON）、顺序；本人是否点亮；`unmet`（答题前须点亮而未点亮的 `<图>/<节点>`，与 `qb.app.publication` 的 `nodeUnlocked` 同一段 SQL）；`refs`（条件引用的节点按 `QbTreeNodeRef` 解析到的图、是否点亮）；`passed`（在最后一次撤销之后合格过一个绑定本节点的发布，同 `TreeLighting`）；门槛节点所开放的功能（按 `now:SysParam` 中指向它的 `qb.tree.gate.*`）。只看生效的图：学者点亮的平台图与发起方图。
- **五种状态由纯 Java 的 `tree.TreeNodeStates` 定义**：`LIT`（点亮）→ `LOCKED`（`unmet` 不空）→ `CAN_ANSWER`（`qb.app.feed` 有行）→ `WAITING`（假定合格后条件即成立：只差答题）→ `MISSING`（否则；列出不成立的条目）。条件的判断复用 `TreeRules.holds`。发起方页面与 App 的整图组件照抄（`src/qb/tree/states.ts`，F-S3b），**同一组用例** `quizbuks-web/app/src/qb/tree/tree-state-cases.json`（两个 SPA 的 `qb/` 相同；Java 的 `TreeNodeStatesTest` 读同一文件，同 F-S13 的 `exposure-cases.json`）。
- `qb.app.trees`：本人看得到的图——生效的、学者点亮的平台图，以及本人在其中有点亮记录或答过其绑定发布的发起方图。
- `qb.sponsor.trees` / `qb.sponsor.tree`（`qb.sponsor.me`）：本人的图；版本（状态、提交、驳回理由、时间）；所选版本的节点与条件；生效版本各节点的点亮人数与等待人数（**按最小分组**：少于 `qb.stats.min-group` 不给）与进行中的绑定发布数（编辑器据此提示"不能删"）。
- `qb.sponsor.tree-bindable-nodes`：向导可选的节点，与 `PublicationNodes.binding()` 同一规则（`TreeBindableNodesIT` 比较）。
- 运营商与平台的编辑器读草稿：已有的 `qb.admin.tree-version` / `qb.operator.tree-version` 已能按版本读节点与条件，不另加。
- **客服按人查看**：流程 `QB_SUPPORT_TREE_STATE`（只读；权限 `qb.admin.tree-state.read`，给内容与客服管理员、运营商成员；运营商成员只限本运营商的发起方图与平台图，经入口范围与 `OperatorChecks`），输入用户与图，输出各节点的点亮记录、撤销与 `TreeLights.State`。读取经平台的操作记录留痕（输入摘要只有两个编号）。IT 证明越界的读不到（404）。
- **节点的人数**（需求方 2026-11-10 确认）：点亮人数实时（`qb.sponsor.tree` 的 `litCount`，最小分组）；"答过未点亮"人数（答过绑定该节点的发布、此刻没有点亮的人）不在模板中读 `qb_attempt`，由每日快照任务按节点算出（页面注明"截至前一天"，最小分组）——超过半天，**单独一个小 PR F-S3f**（F-S3e 之后，需新表与迁移，见 §1）；全体的可答、等待人数不做，单人的五种状态由客服按人查看给出。
- 商户主页的图（不登录也看，公开模板与公开数据视图）放到 F-S3c，随页面一起做。
- 测试：`TreeNodeStatesTest`（用例文件）、`TreeStatesIT`（五种状态逐一构造：经 `qb.app.tree` + `qb.app.feed?tree` 得到的状态与 Java 一致；看不到不能开始的发布）、`TreeSponsorReadIT`（版本、驳回理由、最小分组、绑定数）、`TreeBindableNodesIT`、`SupportTreeStateIT`（权限、越界 404、操作记录）；`ProcessAccessIT`、`MessagesTest`、`SetupIT`（新权限）。

## 3. F-S3b 整图组件（共用 `src/qb/tree/`，两个 SPA 相同）

- 自动排版：按最长路径分层（多起点、多终点，跨图引用画成图外的引用节点），层内按重心法减少交叉；纯函数 `layout.ts`，单测覆盖。拖动与保存坐标不做（规格）。
- 节点卡片：名称、状态（颜色只用主题 token，并有文字与图标，不只靠颜色）、"点亮后得到什么"（节点说明）、可答时链接到能开始的发布、锁定或差条件时列出缺的条件。
- 平台图与发起方图标识不同（标签与边框样式）。
- 无障碍：图是 `role="tree"` 风格的可聚焦网格，方向键在节点与依赖之间移动，Enter 打开详情；另有"文字列表"视图（同一数据的分层列表）；亮暗两种外观过 axe。
- 组件在 `src/qb/tree/`（`states.ts`、`layout.ts`、`messages.ts`、`KnowledgeMap.tsx`），以文件路径引用，与旧的 `qb/tree.ts` 不冲突。旧的 `qb/TechTree.tsx`、`qb/tree.ts`、`share/levels.ts` 及其测试（Q85，D4/D5 已确认删除）**在 F-S3c 中删除**：商户主页（`share/SponsorPage.tsx`）仍以 `levels.ts` 列出发布，随它换成看图一起删。

## 4. F-S3c 看图

- App：新页"我的图"（入口在"我的"），列出 `qb.app.trees`，点进一张图显示整图组件；商户主页把"即将更新"换成该发起方的图。
- 发起方页面：`/tree` 的占位换成本人的图（生效版本 + 各节点人数）。页面用 F-F1 的页面模板与 `PageHeader`；F-F3 落地后由 S3 把它挂到"发布"区域的"结构图"标签（与 S3 先对齐文件位置，同 F-F3a 的做法）。

## 5. F-S3d 编辑与绑定（发起方）

- 编辑器（同一组件的编辑模式）：增删节点；条件编辑器只给 `全部 / 任一 / 至少 N` 与两种对象（`节点`、`合格过一个绑定本节点的发布`），"答题前"只能在最外层"全部"中勾选、`合格` 至多一个且不能标答题前——界面直接不给非法选项，保存后再显示服务器给出的全部问题（`QB_TREE_DRAFT_SAVE` 的 `problems`，按节点定位）。
- 保存 / 提交 / 撤回；驳回时显示原因；版本历史（只读看旧版本）；有开放发布绑定的节点删除时提示并列出发布（提交时服务器 422 `QB_TREE_NODE_IN_USE` 的同一信息）。并发改动（`QB_TREE_CHANGED`）提示重新载入。
- 向导第一步"绑定哪个节点"（可不选）：读 `qb.sponsor.tree-bindable-nodes`；平台图的节点只有直营发起方看得到。已有 4 步顺延，草稿里已存的发布不受影响。
  - 实施记录：做成第一步的第一个字段（不另加一步，已有 4 步与 e2e 的步骤不变，规格"向导第一步选"照样满足）；每次保存都带 `treeId` / `treeNodeId`（此前向导不带，保存会解绑 F-C4 导入时的绑定）。条件编辑器支持最外层组与其中一层子组；已保存的更深的组与引用别的图的节点原样保留、按名称显示。

## 6. F-S3e 管理系统、e2e、文档

- 管理系统扩展不能引用 SPA 的代码，**不放第三份整图组件**（总控定）：扩展里是**文字列表编辑器**——按层级缩进列出节点，每个节点写明条件与前置，键盘操作与 axe 照常；编辑器旁说明可在发起方页面 / App 中预览整图。
- 平台模板编辑器（`qb.tree.platform.write`）与已有审核页连在一起；运营商副本编辑器与"采用模板改动"（`QB_TREE_ADOPT`，逐项勾选）；客服按人查看（§2 的流程）。
- e2e `tree-graph.spec.ts`：发起方建图 → 运营商另一位成员审核 → 发布绑定节点 → 学者答题合格 → App 中节点点亮；看不到不能开始的发布；键盘与 axe（亮暗）。
- 帮助：`docs/quizbuks/help/knowledge-map.md` 改为可发布（三语），配截图（用 e2e 的 Playwright 截取）；App 与发起方的手册 `manual-{app,sponsor}.*.md` 补"知识结构图"一节。`operations.md` 补客服查看与编辑器的说明。

## 7. 删除的功能

- Q85 的旧树组件 `TechTree` / `tree.ts` / `levels.ts`（D4、D5，需求方已确认删除；目前无页面使用）。
- 两处"即将更新"占位（被新页面取代）。
- 其他：无。

## 8. 总控的决定（2026-11-10）

1. **"点亮后得到什么"**：显示节点说明；门槛节点另加平台给的一句，三语消息，按指定该节点的受控参数（`qb.tree.gate.*`）生成，不写死。
2. **客服按人查看**：流程 `QB_SUPPORT_TREE_STATE`，只读、留操作记录；内容与客服管理员可查，运营商成员限本运营商（入口范围）；IT 证明越界查不到。
3. **管理系统扩展**：文字列表编辑器，不画图（见 §6）。
4. **发起方 `/tree`**：先按现路由做；#587（F-F3a）合入后挂"发布 → 结构图"标签，挂之前与 S3 对齐。

## 9. 风险

- **"能开始"的 SQL 与答题开始不一致** → 比较测试（同 F-S2 的 `TreeIT` 做法），状态只在模板中算，不在前端推断。
- **大图的性能**：节点上限 200（`qb.tree.max-nodes`）；排版 O(层 × 节点)；`qb.app.tree` 一条语句（不按节点逐条查）。
- **与 F-F3/F-F4 冲突**（S3 改菜单与外框）→ 新页面只用平台模板，路由与菜单的改动最小，开 PR 前合最新。
- 额度：每个 PR 本机只跑受影响的测试，整轮交给 CI；e2e 只在 F-S3e 请 S4 跑。

## 10. 粗估

a 1.5 天、b 1.5 天、c 1 天、d 1.5 天、e 1.5–2 天，共 7–7.5 天（规格 6–8 天）。
