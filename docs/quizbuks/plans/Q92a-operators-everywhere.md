# Q92a 全面运营商化：平台运营商、Shopify 运营商、直营发起方、去掉"直营即总部"（细化计划，2026-11-04；待需求方确认）

依据：`Q92-overall-design.md` §2、§10（决定 1–20）、附录 A 的 A.1、A.2、A.3、A.5。本计划只做**组织与结算**；收费的通用化（费用规则、可售项、交易、使用权、通用价目表）在 Q93，知识结构图在 Q90。
平台在测试，**重置数据库**，迁移不搬旧数据。工作分支 `1.3/quizbuks-92a-…`（按 §9 分三个 PR）。**确认 §11 后开发。**

## 1. 目标（验收标准）

1. 系统中**没有无归属的发起方**：每个发起方、每个 Shopify 店都属于一个运营商；代码、模板、前端中不再有 `direct` 与"没有运营商即总部"的分支。
2. `QB_SETUP` 建出两个运营商：**平台运营商**与 **Shopify 运营商**；它们与其他运营商走同一套流程（成员、审核、价目表、结算），只是用方案 1（固定费 0、平台 50%）、结算记为"内部结算"。
3. **每个运营商恰好一个直营发起方**，建运营商时自动建出（已通过的入驻、不可改派）；它的发布由运营商中**提交人以外的另一位成员**审核。
4. **日常经营全部在运营商后台**（入驻、发布、主页、商品、举报、兑换与数字奖品争议、奖励方式、获客、价目表、计费方式）；**总部后台只留监控层**（运营商管理、财务、全局规则、答题者账号、抽查、应急）。
5. "平台优先"的列（平台下架、平台封号）保留，含义为**平台的应急操作**。
6. 现金奖励的争议平台不管（拿掉 `qb.cash.resolve`）。
7. Shopify 店的活动由 Shopify 运营商审核；Shopify 的应用费进入 Shopify 运营商的结算。
8. 运营商结算的基础去掉奖励抽成（4110），只留其发起方付的费用（现在即 Q87 平台费；Q93 起为费用规则中"归运营商分成"的全部）；其他运营商的方案不动；获客费照旧扣。
9. 全部测试在"先建平台运营商与审核成员"的夹具下通过；`platformCheck` 0 错误。

## 2. 关键设计

### 2.1 运营商的两个新属性（不是"种类"）

不给运营商分"平台的 / 普通的"种类（§1 原则 3：没有特例），只加两个**属性**，任何运营商都可以有：

| 属性 | 含义 | 平台运营商 | Shopify 运营商 | 其他 |
|---|---|---|---|---|
| `default_for_new` | 入驻时按语言找不到运营商，就归它；至多一个运营商为真 | 是 | 否 | 否 |
| `internal_settlement` | 结算的应付不付出去，记为内部结算 | 是 | 是 | 否 |

另有：`publisher_kinds`（它接收的发布方种类：`SPONSOR` / `SHOPIFY_SHOP`）。Shopify 运营商只接收店，其他只接收发起方。

### 2.2 直营发起方的账号（待定 §11-1，下面是建议的 A）

发起方在系统中就是一个用户（发布方编号 = 用户编号），所以"直营发起方是谁的账号"决定了"另一个人审核"怎么保证：

- **A（建议）**：直营发起方就是运营商**负责人（lead）自己的账号**，另加发起方角色（同一个人在运营商入口是成员，在发起方入口是直营发起方）。负责人以发起方身份提交发布，平台审批"准备人不能审批"自动保证由**另一位成员**审核，不用新机制。代价：换负责人时，直营发起方的数据仍属于原来的人，要由平台另做"移交"（本期不做，记为已知限制）。
- B：直营发起方是一个独立账号，由成员登录操作。系统分不出是哪位成员提交的，"另一个人审核"无法由系统保证。

### 2.3 归属

- `qb_sponsor_operator` 扩展为**发布方的归属**：加 `publisher_kind`（`SPONSOR` / `SHOPIFY_SHOP`），唯一键改为 `(publisher_kind, publisher_id)`。
- 入驻（`SponsorProcesses`）：按语言找运营商（`assignByLanguage`），找不到即 `default_for_new` 的运营商；**一定写一行归属**。Shopify 店建立（`ShopProcesses`）即归 Shopify 运营商。
- 改派（`QB_SPONSOR_OPERATOR_ASSIGN`）：只在接收该发布方种类的运营商之间；`operatorId` 必填；直营发起方不能改派（422 `QB_DIRECT_SPONSOR_FIXED`）。
- 获客认领（`ProspectProcesses`）：一律归属到获客的运营商。

### 2.4 审核与待办的去向

`OperatorScopes`、`OperatorChecks`、`OperatorScope`（`EntryScopeResolver`）、`PublicationEditing.withOperator`、`SponsorPageProcesses`、`SponsorProcesses`（入驻）、`DigitalPrizeProcesses`（争议）、`ReportProcesses`（举报）、`FeeProcesses`（逾期待办）：去掉"没有运营商"的分支，**总是**以所属运营商为范围。总部只经抽查（`QB_REVIEW_SPOT_CHECK`）看全部审核决定。

### 2.5 "平台优先"与应急

`qb_publication.takedown_operator_id`、`QbUserBan.operatorId`、发起方商品下架的运营商、`OperatorCodes.HQ_TAKEDOWN` / `HQ_BAN`：为空表示**平台**的应急操作（平台优先于运营商，D-Q48-5 照旧）。只改命名与文案（"总部" → "平台"），不改行为。

### 2.6 结算

- `OperatorSettlements.settle`：基础 = `qb.system.operator-fees`（其发起方的平台费，按费用上冻结的运营商）；**去掉** `qb.system.operator-revenue`（4110 抽成）。Q93 再把基础推广为"费用规则中归运营商分成的全部"。
- 方案不动；平台运营商与 Shopify 运营商用方案 1。获客费照旧扣、不足结转。
- `internal_settlement` 的运营商：结算单照常生成与存档，过账同其他运营商；付款一步为 `INTERNAL`（不进运营商的账户、不能提现），状态直接结束。
- Shopify 的应用费：`QbsBilling` 收到 Shopify 的付款时，按店的归属记一笔平台费（种类 `SHOPIFY_PLAN`，冻结 Shopify 运营商），进入它的结算。

### 2.7 去掉 `direct`

三个 `"direct"` 常量（`QbProspects`、`QbCertifications`、`QbAudiencePricing`）与前端副本（`AX/ids.ts`、`prospect-api.ts`）全部删除，所有者一律是运营商编号：

| 地方 | 改法 |
|---|---|
| 获客（`qb_prospect*` 的 `owner`） | 运营商编号；总部不再获客（平台运营商的成员获客） |
| 价目表（`qb_audience_price_list.owner`） | 运营商编号；删除参数 `qb.audience.price.fallback`；没有价目表的运营商 = 价格 0（建运营商时建一张空表） |
| 认证的轮、官方账号（`owner`） | 运营商编号；`QB_OFFICIAL_PUBLISHER_SET` 删除，官方账号 = 直营发起方（Q90 T1a 再整体换成挑战发布） |
| 发布卡片、平台费的 `operator_id` | `NOT NULL` |

## 3. 数据（迁移 `V74__quizbuks_operators_everywhere.sql`，编号以合入时为准）

| 表 | 改动 |
|---|---|
| `qb_operator` | 加 `default_for_new boolean`（部分唯一索引：至多一行为真）、`internal_settlement boolean`、`publisher_kinds varchar(40)`、`direct_sponsor_id varchar(128)`（唯一，非空） |
| `qb_sponsor_operator` | 加 `publisher_kind`；唯一键改为 `(publisher_kind, user_id)`（`user_id` 列即发布方编号，店为店的编号） |
| `qb_publication_card` / `qb_fee_charge_version` | `operator_id` 改为非空（新库；只追加表按 D58 不改类型，所以 `qb_fee_charge_version` 用 CHECK 约束而不是改列，见 §10-2） |
| `qb_operator_settlement` | 加 `payment_kind`（`ACCOUNT` / `INTERNAL`） |
| `qb_official_publisher` | `owner` 改为运营商编号的唯一约束（每个运营商一个）；Q90 T1a 删表 |
| 参数 | 删除 `qb.audience.price.fallback` |

`QB_SETUP`：建平台运营商（`default_for_new`、`internal_settlement`、方案 1）、Shopify 运营商（`internal_settlement`、接收店、方案 1），各自的直营发起方与空价目表；只建不存在的（同现在的 `QB_SETUP`）。

## 4. 流程

| 流程 | 改动 |
|---|---|
| `QB_OPERATOR_SAVE`（建运营商） | 同一事务建直营发起方（§2.2 A：负责人账号加发起方角色与已通过的入驻）、归属（不可改派）、空价目表、方案 1 |
| `QB_SPONSOR_OPERATOR_ASSIGN` | `operatorId` 必填；不能改派直营发起方；发布方种类须为目标运营商接收的 |
| 入驻、Shopify 店建立 | 一定写归属（§2.3） |
| 审核、待办 | 一律按所属运营商（§2.4） |
| 现金奖励 | 删除 `qb.cash.resolve` 与对应的总部处理；争议页不再有平台的处理按钮 |
| `QB_OPERATOR_SETTLEMENT_RUN` / `_PAY` | §2.6 |
| 获客、价目表、认证的流程 | 所有者 = 调用者所属的运营商；平台运营商的成员即平台运营商 |
| 渠道发布（Shopify） | 进 Shopify 运营商的审核（`ChannelPublishing` 不再直接上线） |

## 5. 角色与后台

- `QbRoles`：`QB_ADMIN_CONTENT` 去掉日常经营的权限（`qb.review.sponsor`、`qb.review.publication`、`qb.admin.reward-methods`、`qb.prospect.use`、`qb.cash.resolve`……），保留监察（`qb.spot-check`、只读）、全局规则与应急（平台下架、暂停）；`QB_OPERATOR` 接收奖励方式等。总部做日常经营的人成为平台运营商的成员（`QB_OPERATOR` 角色）。
- 后台扩展的菜单（`AX/index.tsx`）："总部内容"拆分：审核、发起方主页与商品、举报、兑换、数字奖品、奖励方式、获客、价目表移到运营商一组；总部只留运营商、抽查、用户与答题记录、法律文本、受众规则目录、AI 总开关、公告、全部财务。
- 运营商后台：新增"奖励方式"、"兑换与数字奖品争议"（已有部分沿用）。

## 6. Shopify（Q92a-3）

- Shopify 运营商与它的直营发起方（Shopify 运营商的负责人）。
- 店的归属（§2.3）、渠道发布进审核（§4）、卡片带运营商。
- 应用费记平台费（§2.6）。
- `OperatorScopes` 与数据视图、举报、封禁、抹除中"只限 `SPONSOR`"的地方（附录 A.3）：逐处扩展到 `SHOPIFY_SHOP`，范围按归属。

## 7. 测试

- **夹具**：`AccountItSupport` 等先建平台运营商（`QB_SETUP` 已建）并加一位审核成员（`QB_OPERATOR`）；入驻的发起方自动归属平台运营商；审核改由该成员。
- **改写**：`OperatorIT`（回到总部 → 改派只在运营商之间、直营发起方不能改派）、`OperatorIsolationIT`、`OperatorLanguagesIT`（找不到语言 → 平台运营商）、`OperatorScopesIT`、`OperatorSettlementIT` 与 `OperatorSettlementsTest`（基础、内部结算、方案 1）、`ProspectIT`、`AudiencePricingIT` 与 `AudienceBillingTest`（没有回落参数）、`CertificationIT`、`SponsorCertificationIT`、`UnderstandingIT`、`ReportIT`、`SponsorPageIT`、`SponsorProductIT`、`FeeIT`、`ShopifyIT`、`ShopifyPlanIT`、`SetupIT`、`SetupRemovalsIT`、`ProcessAccessIT`、`QbRolesTest`、`PublicationApprovalsTest`、`PublicationReviewSettingsIT`、`CashRewardIT`（没有平台处理）。
- **新增**：`OperatorsEverywhereIT`——入驻一定有归属；建运营商即有直营发起方（负责人以发起方身份提交，另一位成员审核，负责人自己不能审）；Shopify 店归 Shopify 运营商、活动进审核、应用费进结算；平台运营商与 Shopify 运营商内部结算；代码中没有 `direct`（ArchUnit 式的源码检查或启动检查：所有发布卡片与平台费有运营商）。
- 场景快照：`setup.yml`（参数少回落、多两个运营商）、`accounts.yml`、`publishing.yml` 等随之更新。
- 前端：`AX/audiencePricing.test.tsx`、`certifications.test.tsx`、`operator.test.tsx`、获客的测试、菜单的测试；e2e：入驻与审核改由运营商成员（`tools/quizbuks/e2e.sh` 的准备步骤加平台运营商成员）。

## 8. 文档

`02-design.md`（角色表、§4.1、运营商一节）、`operations.md`（运营商、直营、获客、价目表、结算、官方账号、现金奖励各节）、`backend/quizbuks/CLAUDE.md`（运营商一条）、Q48 计划（D-Q48-1、5、6、9 注明被 Q92 取代）、`03-plan.md`；本文件的实施记录。

## 9. 分期

| PR | 内容 | 估计 |
|---|---|---|
| **Q92a-1 后端** | 迁移、`QB_SETUP` 的两个运营商、建运营商即建直营发起方、归属必填、审核与待办去向、去掉 `direct`、结算（基础、内部结算）、现金奖励、角色权限；后端测试与夹具 | 5–7 天 |
| **Q92a-2 后台** | 后台扩展的菜单拆分、总部页面收缩、运营商后台补齐；前端测试与 e2e | 3–4 天 |
| **Q92a-3 Shopify** | Shopify 运营商、店的归属、渠道发布审核、应用费入结算、范围扩展 | 3–4 天 |

## 10. 风险

1. **测试面最大**：几乎所有 IT 的夹具都依赖"直营由总部审核"；先改夹具、再逐个修。
2. **只追加表不改类型**（D58）：`qb_fee_charge_version.operator_id` 用 CHECK 约束保证非空，不改列。
3. **§2.2 A 的已知限制**：换负责人不移交直营发起方（以后另做）。
4. **Shopify 渠道发布原本没有审核**：加审核后，店的活动不再立即上线；店的前端与说明要跟着改（`quizbuks-web/shopify`）。
5. **Q90、Q93 同时在设计**：本期只做组织与结算，不碰收费积木与知识结构图；官方账号表留给 Q90 T1a 删除。

## 11. 待需求方确认

1. **直营发起方的账号**：§2.2 的 A（负责人自己的账号兼任直营发起方，"另一个人审核"由平台自动保证；换负责人暂不移交）还是 B（独立账号，系统无法保证是另一个人审核）？
2. 入驻时**按语言找不到运营商**归平台运营商（`default_for_new`）：可以吗？
3. Shopify 运营商的**负责人与成员**由谁担任（平台的人兼任即可）？
4. 分三个 PR、依次合并：可以吗？
