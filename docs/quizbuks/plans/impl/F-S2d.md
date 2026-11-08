# F-S2d 节点的三态与不可见：实施计划

规格：`first-launch-spec.md` F-S2 的"节点对一个人的三态"、"未满足前置时不可见"、公开页、收件箱、"撤销不连带下游"。规格已改（`7157fbfd`），需求方 2026-11-08 确认六点"按建议"。来源是 S2 的节点状态核对（报总控）：
- 没有统一的三态定义；
- 未满足前置的绑定发布在详情、档位、`QB_VIEW`、公开页、收件箱仍然可见；
- `TreeLights` 只按节点编号匹配点亮记录。

建在 F-S2c2a（`1.3/quizbuks-FS2c2-removal`）之上，同一批模板 c2a 刚改过。c2a 合入后先合入 `1.3/quizbuks`，再开草稿 PR。

## 0. 先后
c2a → F-S2d → c2b（等平台 D80）。

## 1. 三态的定义（一份 Java）
在 `tree.TreeLights` 中加 `State { UNMET, OPEN, LIT }` 与 `state(node, beforeAnswer, litNow)`：
- 已点亮 = LIT；
- 答题前须点亮的节点都已点亮 = OPEN（可挑战，含挑战中）；
- 否则 UNMET。

F-S3 的五态在 OPEN 内细分，F-S3 自己做。

## 2. 按"图与节点"匹配（副本问题）
- 节点编号只在一张图内唯一：副本沿用模板的编号，点亮者为发起方的平台图有模板与每个运营商的副本。
- 点亮的集合改为 `Set<NodeRef(treeId, nodeId)>`：`TreeLights.litNow`、`TreeLighting` 的连带判断、`TreeGate`。
- 条件中的 `node:<编号>` 仍按编号写，提交时解析到唯一的一张图：
  1. 本图优先；
  2. 否则在点亮者相同、可引用的生效图中找；
  3. 运营商副本先找同一运营商的副本，再找模板。

  找到多张即拒绝（新问题码 `AMBIGUOUS_NODE`，三语），启动检查 `QB_TREE` 同样报告。
- 节点行的 `beforeAnswer` 改存解析后的 `<图>/<节点>`。
- 生效版本中已有的行由迁移按上面的规则改写，改写不了的由每日核对报告（`BEFORE_ANSWER_UNRESOLVED`）。迁移号在开 PR 时取最大号 + 1。
- 模板（`feed`、`publication`、`audience-count` 等）按图与节点两者匹配 `qb_tree_lit_version`。

## 3. 不可见（状态 UNMET）
一个条件"可见 = 没绑定节点，或绑定的节点对本人不是 UNMET，或本人在这个发布已有答题记录"，写在下面每一处：

| 位置 | 改动 |
|---|---|
| `qb.app.feed` | 已有开始的条件中拆出可见条件：UNMET 的不列出（现在已不列出，改为共用同一段） |
| `qb.app.publication`（详情） | 不可见即 0 行（前端已有"不存在"的画面） |
| `qb.app.publication-tiers` | 同上 |
| `QB_VIEW` | 不可见即 404，与不存在相同（不是 422） |
| 开始答题 `TreeGate.refuse` | 改为 404，与不存在相同；已有答题记录的照旧由"只答一次"拒绝 |
| `qb.public.publication`、`qb.public.sponsor-page-publications` | 不登录：绑定的节点有答题前条件即不列出、不返回；没绑定的照旧 |
| `qb.app.inbox`、`inbox-unread` | 发起方关于某个发布的通知，不可见即隐藏，也不计入未读 |

本人的结果页、答题记录、钱包照旧（规格："已有答题记录的仍能看自己的结果"）。

## 4. 撤销不连带
撤销照旧只写一条撤销记录，不碰下游的点亮。测试中断言：下游的点亮记录不变，前置不再满足的发布对这个人按第 3 节不可见。

## 5. 测试
- `TreeLightsTest`：三态；按图与节点匹配（同编号、不同图的点亮不算）。
- `TreeRulesTest`：`AMBIGUOUS_NODE`；解析的先后顺序。
- `TreeLightsIT`：Java 与每个模板的比较扩展到三态与可见，包括副本的同编号。
- 新 `TreeVisibilityIT`：
  - 未满足前置时首页、详情、档位、`QB_VIEW`、开始、公开页、收件箱都看不到，开始与查看为 404；
  - 满足后出现，点亮后为 LIT；
  - 已有答题记录的、撤销之后仍能看自己的结果；
  - 撤销不连带下游。
- 迁移：`beforeAnswer` 改写的 IT（旧格式的行写入后迁移、核对）。
- `AttemptStatementBudgetIT`：语句数应不变，变了说明原因。
- e2e `tree-visibility.spec.ts`：未满足看不到 → 点亮前置节点后出现 → 合格后显示已点亮。本机跑不了，由总控安排代跑。
- 前端：App 的详情页把 404 当作不存在（现有）；收件箱的测试。

## 6. 删除的功能
无。不可见是规格新定的行为。原来的 422 `QB_TREE_NOT_UNLOCKED` 改为 404：错误码在消息中保留给旧客户端的提示，页面不再出现。

## 7. 风险
- 改写 `beforeAnswer` 的迁移：只在生效版本的节点行上，按本图优先解析，几乎都是本图的编号。
- 公开页少列一部分发布：是规格的要求。
- 收件箱条件多一段子查询：用已有的索引 `qb_tree_lit_version (subject_kind, subject_id)`，一个人的点亮至多一张图的节点数。

## 8. 实施记录
- **解析的范围**：图可引用的范围照 `TreeProcesses` 的 `loadRules`：发起方的图引用点亮者为学者的平台图；平台模板引用同一点亮者的其他平台模板；运营商的副本引用同一运营商的其他副本（不引用模板）。在这些范围内，节点编号由服务器生成，副本只沿用自己模板的编号，所以现有数据不会出现"两张图都有"；`QB_TREE_AMBIGUOUS_NODE` 是范围以后放宽时的防线（`TreeRulesTest` 以构造的图验证）。计划第 2 节"运营商副本先找同一运营商的副本，再找模板"因此不需要：副本从不引用模板。
- **生效时解析**：解析在版本生效时做（`activate`），审批结果的流程 `QB_TREE_APPROVAL_RESULT` 因此也读可引用的生效图（两条查询，与提交相同；没有图时不发语句）。
- **点亮的连带判断**：事件 `qb.tree.lit` 的 `nodeIds` 改为 `<图>/<节点>`；本 PR 之前写出、尚未投递的事件仍是裸编号，按编号匹配（旧行为）。候选节点的引用与其图在同一条语句中读取（`QueryEntities.all`），语句数不变。
- **"已点亮"的显示**：看图在 F-S3，现在没有页面显示节点的状态；为 e2e 的第三点，学者的发布页加一行"知识结构图的节点：<名称> · 合格即点亮 / 已点亮"（`qb.app.publication` 的 `nodeName`、`nodeLit`）。
- **公开页**：模板经新的公开视图 `QbTrees.PUBLIC_NODES`（白名单 `treeId`、`nodeId`、`beforeAnswer`，只经公开模板读）判断节点有没有答题前条件；公开发布卡片的白名单加 `treeId`、`treeNodeId`（模板不返回）。
