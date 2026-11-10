# F-S4 实施计划：发起方在发起方页面答题点亮与好处

规格：`docs/quizbuks/first-launch-spec.md` F-S4（需求方 2026-11-07 晚：新发起方缺省没有奖励方式，点亮"电子奖品"/"线下兑奖"节点即开通）；来源 `Q90-trees.md` §0-20、§0-21、§2.8，F-M 的 `LIGHT` 来源（`plans/impl/F-M.md` §M2 与问题 4）。负责：S2。状态：**总控 2026-11-10 批准**（§8 六点已定），按 F-S4a → b → c。

## 0. 现状（2026-11-10 盘点）

- **图**：点亮者为发起方的图（`subjectKind = SPONSOR`）已能建立：平台模板（`PLATFORM`）与运营商的副本（`OPERATOR`，`QB_TREE_COPY`，沿用模板的节点编号，`QB_TREE_ADOPT` 逐项采用模板改动，F-S3e1 有编辑器）。按 Q90 §0-21，**发起方的平台图分运营商**（副本），"发起方只答所属运营商的官方账号出的挑战"；学者的平台图不分运营商。
- **绑定**：`PublicationNodes` 拒绝点亮者为发起方的图（`QB_PUBLICATION_NODE_SPONSOR_LIT`，"F-S4 之前不能绑定"）。
- **答题**：`QB_ATTEMPT_*` 要 `qb.play`（学者角色）、资料；`qb_attempt` 以 `(user_id, publication_id)` 唯一；合格且卡片绑定节点即发 `qb.tree.passed`，`QB_TREE_EVALUATE` 只认主体 `USER`（`TreeLighting` 第 144、156、302、332 行写死 `USER`）。另一条入口的先例是 Shopify 渠道的 `ChannelPlay`（`subProcessOnly`，复用同一套步骤）。
- **点亮主体**：`QbTreeLit.subjectKind` 的字典已有 `USER` / `SPONSOR`；撤销流程 `QB_TREE_LIT_REVOKE` 按点亮记录，不限主体。
- **门槛**：`TreeGates` 只认**学者点亮的平台图**（`names()`），两种权益的门槛也按人（F-S2c 已定），本项不改。
- **奖励方式**：`QbSponsorRewardMethods`（`QB_SPONSOR_REWARD_METHODS_SET`，管理员）；没有记录 = 受控参数 `qb.reward.default-methods`（现缺省 `PLATFORM`，F-F10 改为空）；`PublicationEditing.methodProblems` 核对。
- **赠送**：F-M2 的 `QB_FEE_CREDIT_GRANT`（`subProcessOnly`，调用方持钱包锁，`(sponsor, sourceKey)` 唯一，再调用返回原笔），来源 `LIGHT` 已在字典与校验中预留、没有调用方；作废只有到期任务。
- **答题的界面**只在 App（`pages/PlayPage.tsx` 374 行、`ResultPage.tsx` 665 行、`play/` 的倒计时、保存、忙重试、存储）；共用的 `qb/play.ts` 只有类型与调用。

## 1. 拆分（3 个 PR，依次）

| PR | 内容 | 迁移 |
|---|---|---|
| **F-S4a** 后端：挑战发布与发起方答题 | 绑定副本节点、卡片冻结"给发起方"、学者侧排除、发起方的答题流程、`on_behalf_of`、按 `SPONSOR` 点亮、语句数 | 一个（`qb_attempt.on_behalf_of`、卡片 `for_sponsors`） |
| **F-S4b** 后端：好处 | 奖励方式按节点开通、节点的点亮赠送（定义、四眼、发放、重试、撤销扣回、核对）、管理侧的读取 | 一个（节点与点亮记录的赠送额） |
| **F-S4c** 界面、e2e 与文档 | 答题组件移到共用的 `qb/`、发起方的"挑战"标签与答题 / 结果页、向导去掉奖励一步、扩展的赠送字段与节点人数、e2e 闭环第 6 步、帮助与手册、`operations.md` | 无 |

迁移号按先绿先合：F-S4a 取当时最大 + 1（#607 合后为 V109），F-S4b 再 + 1。

## 2. F-S4a 挑战发布与发起方答题（后端）

- **能绑定什么**（`PublicationNodes`）：运营商的直营发起方（`QbOperator.directSponsorId`，即官方账号）可以绑定**本运营商的副本**（`OPERATOR`、`SPONSOR` 点亮、生效）中生效、要求合格的节点；其他人仍拒绝。学者点亮的平台图、发起方自己的图照旧。`qb.sponsor.tree-bindable-nodes` 同一规则（`TreeBindableNodesIT` 比较）。
- **挑战发布的形状**（`PublicationReadiness`，保存给出、提交拒绝）：绑定发起方点亮的节点即"挑战发布"：不能有奖励档位、受众条件、邀请（`INVITED`）、地区以外的定向、推荐、报名费、共享视频与付费曝光（新规则代码 `QB_PUBLICATION_CHALLENGE_*`）；地区固定 `GLOBAL`、不扣钱包、不收配送费（F-M 的费用在提交时按"挑战"跳过，与 F-M 负责人确认：S2 自己）。审批照常（直营发起方的发布由另一位成员审）。
- **卡片冻结 `forSponsors`**（上线时由节点所在图的 `subjectKind` 得出）。学者侧排除：`qb.app.feed` / `qb.app.publication` / `qb.app.publication-tiers` / `qb.app.inbox` / `inbox-unread` / `qb.system.audience-count` / 公开模板写同一段条件（`NOT for_sponsors`，与 `BlockIT`、`TreeLightingIT` 的比较测试一起改）；`QB_ATTEMPT_START` 对这类卡片 422 `QB_PUBLICATION_FOR_SPONSORS`（规格写 422；不在列表中，所以学者只有拿到链接才会遇到）。
- **发起方的答题流程**（照 `ChannelPlay` 复用 `PlaySteps`，学者的流程一行不改）：`QB_SPONSOR_ATTEMPT_START` / `_SAVE` / `_SUBMIT` / `_RESULT`（权限新 `qb.sponsor.challenge`，只给 `QB_SPONSOR`；`requiresVerifiedEmail`、`CallerChecks::refuseBanned`、入驻已通过）。只接受 `forSponsors` 的卡片，且发布方是**调用者所属运营商**的直营发起方（`QbSponsorOperator`），其他一律 404（不暴露别的运营商的挑战）；不读资料、受众、邀请、地区、屏蔽；开始锁、发布锁、语句的写法与学者相同。超时由已有的 `qb.attempt-expire` 结束（结算按卡片：没有档位即不付）。店员不能答（流程按操作人本人；店员没有 `qb.sponsor.challenge`）。
- **`qb_attempt.on_behalf_of`**（可空，发起方编号；学者的为空）：发起方本人答，`user_id` = `on_behalf_of` = 发起方，"一个发布只答一次"由已有的 `qb_attempt_once` 保证（以后成员代答时再改唯一约束，本期不做）。不计入：分析任务 `qb.analytics` 跳过 `forSponsors` 的发布、`qb.admin.attempts` 标出"代表发起方"、受众人数本来就不含（挑战发布没有受众）。
- **点亮**：完成时卡片 `forSponsors` 且合格 → `qb.tree.passed` 的载荷带 `subjectKind = SPONSOR`、`subjectId = on_behalf_of`；`TreeLighting` 把写死的 `USER` 改为取自输入（主体只能是 `USER` / `SPONSOR`，`SPONSOR` 只点亮点亮者为发起方的图；锁 `qb.tree-lit:SPONSOR:<发起方>`）；连带判断、撤销、每日核对（`UNLIT_PASS`）与 `qb.tree-recheck` 同样按主体。`TreeLights` 的"此刻点亮"规则不变。
- **语句数**：`AttemptStatementBudgetIT` 加"代表发起方的开始与提交"并断言确切数与所取的锁；学者路径的数不变（两组：写缓冲开与关）。
- **测试**：`SponsorChallengeIT`（本人能答；店员、别的运营商的发起方、学者 404 或 422；不动任何钱包与账本；合格点亮 `SPONSOR` 主体；一次）、`TreeBindableNodesIT`、`TreeLightingIT`（主体）、`BlockIT` 等比较测试加挑战卡片、`AnalyticsIT`、`ProcessAccessIT`、`SetupIT` / `QbRolesTest`（新权限）、`MessagesTest`（新代码）、场景快照（若受影响）。

## 3. F-S4b 好处（后端）

- **奖励方式按节点开通**（需求方 2026-11-07 晚）：两个受控参数 `qb.tree.gate.digital-prize-node`、`qb.tree.gate.offline-redeem-node`，值是**平台模板**的 `<图>/<节点>`（点亮者为发起方）。"开通" = 本人在该模板或其**任一副本**中点亮了同一节点编号、没有撤销（副本沿用模板的节点编号，改派后照样算数，Q90 §0-21）。规则在 `TreeGates` 加一种"发起方门槛"（`names()` 按主体分两种，`GATE_INVALID` 同样核对），`PublicationEditing.methodProblems` 与 `qb.sponsor.reward-methods` 取"记录或缺省 ∪ 已开通"。不写 `QbSponsorRewardMethods`，也不删它与缺省参数（§8 第 1 点）：可用 = 缺省 ∪ 单独设定 ∪ 已开通。
- **节点的点亮赠送**：节点定义加 `grantAmount`（`Monetary` JPY 0 位，可空）。只能设在**平台模板**中点亮者为发起方的图上；不超过受控参数 `qb.tree.grant.max-per-node`（缺省空 = 不能设，`TreeRules` 给出 `QB_TREE_GRANT_NOT_ALLOWED` / `_TOO_LARGE`）；随版本经已有的四眼审批（审批内容含它）。副本不能改它（复制与采用照抄；`QB_TREE_DRAFT_SAVE` 对副本给出 `QB_TREE_GRANT_COPY`），点亮副本的节点时按**模板生效版本**的金额。
- **发放**：点亮时把金额冻结在点亮记录上（`QbTreeLit.grantAmount`，只追加表加列）。事件 `qb.tree.lit` 的新消费者 `QB_TREE_LIT_GRANT`（`internal()`）只取钱包锁 `qb.wallet:SPONSOR:<发起方>`，以子流程 `QB_FEE_CREDIT_GRANT` 发放（来源 `LIGHT`，来源键 `lit:<点亮记录>`：重复事件只返回原笔）；赠送功能的总开关照 F-M2 的 `qb.fee-credit.grant.enabled`。**不在答题提交中做**。失败的由每日逐项任务 `qb.tree-grant-retry`（模板 `qb.system.tree-grants-due`：有金额、没有对应赠送、没有撤销的点亮）补发。
- **撤销扣回**：`qb.tree.lit-revoked` 的消费者 `QB_TREE_LIT_GRANT_VOID`（`internal()`，钱包锁内）作废该笔赠送**剩余未用的部分**（借 2190 / 贷 5140，同 F-M2 的到期作废；已用掉的不追回，§8 第 4 点），写作废记录。
- **核对与管理**：`qb.system.recon-fee-account` 加 `LIGHT_GRANTS`（每条有金额的未撤销点亮恰好一笔 `LIGHT` 赠送，反之亦然）；总部财务页的赠送列表能按来源 `LIGHT` 筛选、看到"待发放"（模板 `qb.admin.tree-grants-pending`）；月报沿用 `FEE_CREDIT_GRANTED` / `_VOIDED`。
- **节点人数**：F-S3f 的每日快照与 `qb.operator.tree-version` 扩到点亮者为发起方的副本（点亮人数、答过未点亮人数，最小分组）。
- **测试**：`TreeGrantIT`（四眼、上限、副本不能改、点亮恰好发一次、重复事件、失败补发、撤销作废剩余、核对项）、`RewardMethodGateIT`（缺省空时没有方式；点亮电子奖品节点即可保存 `DIGITAL_PRIZE`；撤销后关闭；副本节点算数；`qb.sponsor.reward-methods` 与 Java 一致）、`TreeRulesTest`、`AdminFinanceIT`、`ReconIT`。

## 4. F-S4c 界面、e2e 与文档

- **答题组件移到共用的 `qb/answer/`**（规格"复用 App 的答题组件"）：`PlayPage` / `ResultPage` 的主体、`play/` 的倒计时、保存、忙重试、存储改为接收"流程名与路径"的组件，App 与发起方页面各自包一层（App 行为不变，App 的测试照旧通过；两个 SPA 的 `qb/` 相同）。这是本项最大的前端改动，单独的提交、先过 App 的全部测试与 e2e 的答题用例。
- **发起方的"挑战"标签**：发起方中心区域加标签"挑战"（`shell/areas.ts`，只加标签，不加菜单项）：按节点列出本运营商副本中可答的节点、状态与可开始的发布（模板 `qb.sponsor.challenges`，与发起方答题流程同一条可答规则，比较测试）；开始 → 答题页 → 结果页说明得到了什么（开通的奖励方式、赠送额度与到期日、或"等下一场"）。
- **向导**：绑定发起方点亮的节点时不显示奖励一步，档位与受众相关字段不出现（服务器照样检查）。
- **管理系统扩展**：模板编辑器的节点加"点亮赠送额度"（只有平台模板、点亮者为发起方时给出；副本只读显示）；审核页显示金额；运营商的副本页显示各节点点亮与等待人数。
- **e2e**：`sponsor-challenge.spec.ts`（规格的闭环第 6 步）：平台建发起方模板（含电子奖品节点与赠送）、运营商复制并生效、直营发起方出挑战、另一位发起方在"挑战"页答题合格 → 节点点亮、可以保存电子奖品的发布、平台费账户出现赠送；不合格显示"等下一场"；学者打开链接 422；键盘与 axe（亮暗）。
- **文档**：`operations.md` §7.27"给节点设赠送"与"开通奖励方式的节点"、告警表；帮助 `help/knowledge-map.md` 的发起方一节（挑战、开通、赠送）三语；发起方手册；`backend/quizbuks/CLAUDE.md`；进度表。

## 5. 不做

- 发起方的成员代答（规格"不做"）；挑战给学分。
- 门槛之外的"平台功能目录"（Q90 附录 A 的 T3）：本项只做两种奖励方式与赠送；其他平台功能照 F-S2c 的门槛参数（学者点亮）。
- 两种权益的门槛改由发起方点亮（F-S2c 已定按人；要改另开一项）。

## 6. 删除的功能

- 无。`qb.reward.default-methods` 改为空属于 F-F10（S3）；本项只让"空"时可以按节点开通。

## 7. 风险

- **答题组件移到 `qb/`**：改动面大（约 1 000 行页面与 4 个辅助模块），App 的答题是最重要的路径。缓解：只搬不改行为、先单独提交、App 测试与 e2e 答题用例全过再接发起方页面。
- **学者侧排除写在多处 SQL**：漏一处即学者看到挑战。缓解：同 F-S2d 的做法，比较测试逐个模板断言挑战卡片不出现。
- **语句预算**：发起方的流程是新路径，学者的数不变由 `AttemptStatementBudgetIT` 保证。
- **F-M 的提交费用**：挑战发布不应收配送费；要在 F-M 的计费点上明确跳过（S2 自己的代码）。
- **主体从写死改为输入**：`TreeLighting` 的锁名、查询与事件载荷都要按主体；IT 覆盖两种主体。

## 8. 总控与需求方的决定（2026-11-10）

1. **奖励方式用门槛参数开通**：`qb.tree.gate.digital-prize-node` / `qb.tree.gate.offline-redeem-node`（受控，缺省空，撤销点亮即失效）。**不删除**现有的 `qb.reward.default-methods` 与对单个发起方的设定（`QbSponsorRewardMethods` / `QB_SPONSOR_REWARD_METHODS_SET`）：彩排与阶段一要用。可用的奖励方式 = 缺省参数 ∪ 单独设定 ∪ 已点亮门槛开通的（需求方 2026-11-10 确认；规格已改）。
2. 挑战只绑**本运营商的副本**，平台模板不直接用于挑战。
3. 挑战发布不收配送费、不扣预算。
4. 撤销点亮只作废赠送中**没用完的部分**，已用掉的不追回（需求方 2026-11-10 确认；规格已改）。
7. **点亮赠送对每个发起方、每个平台模板节点终身一次**（需求方 2026-11-10 确认；规格已改，额外审查第 1 点）：撤销后再答合格、改派后在另一个运营商的副本中点亮同一节点，都不再发。
5. "挑战"标签在发起方中心 `/profile/challenges`（与 F-F3 的外框对齐）。
6. 学者打开挑战的链接照规格 422。

F-S4b 涉及赠送额度（钱）：开 PR 前总控安排一次额外的审查。

## 10. 实施记录

**F-S4a**（分支 `1.3/quizbuks-FS4a-challenges`，迁移 `V109`）与 §2 的差别：

- **档位**：挑战保留档位，**所有档位为 0**（不是"不能有档位"）：结算代码不改，完成照常按档位结算出 0；保存与提交拒绝大于 0 的档位（`QB_PUBLICATION_CHALLENGE_REWARD`）。F-S4c 的向导只发一档 0。
- **形状的检查**（`publishing.PublicationChallenges`）只做奖励、邀请、推荐、参加费、共享视频五项；受众条件与地区**不拒绝、不读**（发起方的开始不读资料与地区，所给的条件对挑战没有作用）；付费曝光不在本期。
- **"本运营商的挑战"**按卡片冻结的 `operatorId`（发布方即该运营商的直营发起方，绑定时已检查）与调用者的运营商比较，不再读 `QbOperator`（开始的第一条语句里多读入驻与运营商两行，语句数不增加）。
- **学者继续挑战**也 422（不只是新的开始）：同一个人既是学者又是发起方时，学者的流程不接手发起方的答题。
- `qb.system.audience-count` 未改（挑战没有受众，发布页不显示人数）；`BlockIT` 的比较未加挑战卡片（屏蔽不作用于挑战：发起方的开始不读屏蔽）。
- 语句数：发起方开始挑战共 8、锁内 3（学者 11 / 3），挑战合格的提交共 9、锁内 3（不付奖：没有钱包锁与过账）；写缓冲关时 10 / 5、12 / 6。

**F-S4b**（分支 `1.3/quizbuks-FS4b-benefits`，迁移 `V110`）与 §3 的差别：

- 奖励方式的门槛另建 `tree.SponsorGates`（不改 `TreeGates.names` 的学者门槛）：参数与奖励参数同一次读取，加两条语句（门槛节点、本人的点亮与撤销；点亮所在的图）；点亮按发起方主体的 `me-sponsor:` 视图读。
- 并集按规格字面：单独设定再也不能比缺省更窄（`RewardMethodsIT` 改了期望）；模板 `qb.sponsor.reward-methods` 去掉认不出的名字（以前留给页面去掉）。
- 缺省参数（总控 2026-11-10 选 A）：发布的值空白 = 没有任何方式，声明值改为空（`QB_SETUP` 提出空）；参数不存在（`QB_SETUP` 之前）仍按平台 Kudos，已发布 `PLATFORM` 的安装不受影响。IT 的安装、场景、e2e 的准备、本机演示与 Stripe 测试模式在发布提案之后另发布 `PLATFORM`（它们以平台 Kudos 出题）；`RewardMethodsIT.anEmptyDefaultGivesNoMethod` 断言空值。
- 赠送的发放不订阅 `qb.tree.lit`（它只在有下游节点时发出），而是点亮时另发 `qb.tree.grant-due`；`QB_TREE_LIT_GRANT` 与补发任务都按发起方处理其全部待发的点亮（每次至多 50 笔，其余下次）。流程放在 `fee` 包（钱包锁与记账同 F-M2）。
- 节点行 `QbTreeNode` 也记 `grantAmount`（生效版本写入），点亮时读模板的行（副本沿用模板的节点编号，按节点编号读到的行里就有）。
- 副本的保存：给出与现有不同的金额即 `QB_TREE_GRANT_COPY`，存的仍是现有的；采用模板的改动时取模板的金额。管理系统扩展的编辑器还不发 `grantAmount`：在 F-S4c 加字段之前，经扩展保存平台模板会清掉它的赠送。
- 核对 `LIGHT_GRANTS` 在开关关闭时不计未发的点亮。节点人数扩到发起方的副本（§3 最后一条）未做，留给 F-S4c。

**F-S4b 额外审查之后**（同一分支）：

- **来源键** `light:<模板>/<节点>`（不再是点亮记录）：`QB_FEE_CREDIT_GRANT` 对同一键只返回原笔，撤销后再点亮、改派后在另一个副本点亮都不再发（§8 第 7 点）。
- **结算**：`QB_TREE_LIT_GRANT` 改为按发起方**两个方向**结算（取代原来的发放与 `QB_TREE_LIT_GRANT_VOID`）：节点"仍被点亮"（有未撤销、有金额的点亮记录）而没有赠送的发放（开关开、入驻通过、未封禁，每次至多 `quizbuks.tree-grants.max-per-run`，缺省 50）；有赠送而节点不再被点亮、还有剩余的作废剩余（借 2190 / 贷 5140）。由 `qb.tree.grant-due`、`qb.tree.lit-revoked` 与每日任务 `qb.tree-grant-retry`（模板 `qb.system.tree-grants-due` 两个方向都列）触发；撤销的事件失败了每日任务补作废。
- **作废累加**：`FeeCredits.voidLot` 的 `voidedAmount` 改为累加。账单作废退回到**已作废**的赠送（到期作废或撤销作废）时，退回的部分当即再作废（`QB_FEE_REFUND` 加一步过账，借 2190 / 贷 5140；以前这样的退回违反该笔的检查约束、整个作废不成功——F-M 已有的缺陷）。
- **核对**：`LIGHT_GRANTS`（仍被点亮、开关开而没有赠送）与新增 `LIGHT_VOIDS`（不再被点亮而有剩余）。
- **封禁与入驻**：结算同 `QB_FEE_CREDIT_ONBOARDING`，封禁或入驻未通过的不发；待发列表照列（总部可见）。
- **扩展**：模板编辑器保存时原样带回读到的 `grantAmount`（不加编辑字段），不再清掉赠送。
- **测试**：`TreeGrantIT`（一次终身、撤销前未发、改派、封禁、版本冻结、部分用掉后撤销与账单作废、漏掉的作废）、`TreeGrantRunLimitIT`（每次上限与下次补发）、`FeeAccountIT`（到期作废后的退回）、扩展的 `tree-draft.test.ts`。

## 9. 粗估

F-S4a 2 天、F-S4b 1.5–2 天、F-S4c 2–2.5 天（其中搬答题组件约 1 天），合计 5.5–6.5 天（规格粗估 3–4 天未含搬组件与副本）。
