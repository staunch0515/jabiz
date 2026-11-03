# Q89 赞助权益：商户为答题者购买其他商户的商品、服务与数字产品（计划，2026-11-03；需求方同日确认 §9，开发中）

需求方 2026-11-03 的需求文档《QuizBuks 双模式广告互动与价值权益系统》（模块 A：广告答题解锁优质视频；模块 B：广告赞助实物及线下服务权益），
经三轮对照现状后定下的范围（本文 §0）。编号 Q89：Q88 已由 Q86、Q87 §7 留给"答题付学分与商户认证"。
需求方 2026-11-03 确认 §9：两项能力分开（"能分就分"）、不新增奖励方式、一位合格者一份、提交时划拨与结束时退回、通行码对所有到店兑换开放；"同意，开始"。按 §7 分期开发。

## 0. 需求方的决定（2026-11-03）

1. **模块 A 与 B 是同一件事**：视频制作人把视频播放权包装成**数字商品**；广告商向它购买，作为答题奖励发出。平台只发放链接或码，外部视频的访问控制、链接是否失效平台**不负责**。
2. **广告商、商品提供商都是商户**，身份上不区分；能做什么由**科技树**（认证）控制。
3. **商户之间直接磋商**，平台只提供渠道；磋商中**允许交换联系方式与收款信息**（与 Q82 的答题者对话不同）。
4. **采购确认由平台"凑合"实现**：平台统一记录采购数量、已付金额与凭证文件，提供方确认；**不需要运营商单独审核**，采购的内容作为**发布的一部分**由运营商在发布审核中看到。
5. **一张采购单可以给几个发布共用**。
6. **所有持有能力的商户都能看到**权益目录。
7. **一个币种**：金额一律用系统币种（`JPY`，小数位 0），不做多币种。平台不经手采购款、不承诺退款。
8. **核销**按"本人持有 + 现场在场 + 短时一次 + 服务器判定"设计（§2 D-Q89-9、D-Q89-10）。
9. 不做：一个活动绑定多个目标、一个目标挂多个活动时自动挑选、外链检查、播放验证（Q75 已有）、多币种。题库抽题（Q81）、科技树（Q76、Q85、Q86）照旧可用。

## 1. 现状（可直接复用的）

| 部分 | 现在 |
|---|---|
| 发布与答题 | `QbPublication` 引用 Quiz 版本，合格分、档位、`maxWinners`、审核、暂停与结束；Q75 看完视频才能开始，Q81 每人随机 k 题，Q76 前置条件 |
| 名额的并发 | `AttemptRules`：开始时 `winners + inProgress ≥ maxWinners` 即 `QUOTA_FULL`；在开始锁 `qb.pub-start` 内重读，完成在发布锁 `qb.pub` 内计数；开始即占一份，不合格或超时释放；语句数由 `AttemptStatementBudgetIT` 断言 |
| 到店兑换 | `OFFLINE_REDEEM`（Q22）：结算写 `QbRedemption`（`DUE` → `CLAIMED` / `REDEEMED` / `EXPIRED` / `VOID`），答题者扫**发布方**的轮换码或固定码领取（`QB_REDEEM_TOKEN_CHECK` 核对码属于发布方，`QB_REDEEM_TOKEN_SPONSOR`）；店员（Q39、Q74）；App 内扫一扫（Q52）；有效期是全局参数 `qb.reward.redeem-days` |
| 电子奖品 | `DIGITAL_PRIZE`（Q33）：码池 `POOL`、共用码 `SHARED`、商户发送 `SPONSOR_SENT`；码只以 `SealedSecrets` 密封，本人经 `QB_DIGITAL_PRIZE_OPEN` 打开；都由**发布方**加载与发送 |
| 认证 | Q69、Q86：目录在代码中（`Certifications`），官方账号出题、运营商登记为认证的一轮（`QB_CERTIFICATION_ROUND_ADD`），合格持有一年；`SponsorApplyGuard` 是"持有才能用某功能"的写法 |
| 商品 | Q60b `QbSponsorProduct`：名称、简介、图片、价格，公开在商户主页 |
| 对话 | Q82 `QbConversation`：只限答题者与发布之间，禁止联系方式（`BroadcastRules.hasAddress`） |
| 审核 | 发布审核（`PublicationApprovals`，审批内容哈希），运营商终审（Q48） |

缺的：提供方与出资方是两个商户的关系；权益目录；商户之间的对话；采购单；名额来自采购单；由提供方核销与发放；店员扫答题者的码；按采购单的异常与统计。

## 2. 决定（草案）

| # | 决定 |
|---|---|
| D-Q89-1 | **两项能力是两个认证**：`BENEFIT_PROVIDER`（可提供赞助权益）与 `BENEFIT_SPONSOR`（可赞助他人权益），加入 `Certifications` 目录（一年有效，版本 1）。题由官方账号出，运营商照 Q69 登记轮次，Q85 和 Q86 的平台之树上自然出现这两个节点。上架权益要持有前者，发起磋商、建采购单、发布引用采购单要持有后者（新的守卫 `BenefitGuard`，写法同 `SponsorApplyGuard`；422 `QB_BENEFIT_NOT_CERTIFIED`）。认证到期后已生效的采购单与发布照常运行，只是不能新建 |
| D-Q89-2 | **权益目录 `QbBenefitOffer`**（普通表，历史见审计），由提供方上架：种类 `kind`（`GOODS` / `SERVICE` / `DIGITAL`，字典 `benefit-kind`）、名称、简介（纯文本 ≤ 2000）、图片（≤ 4，策略 `qb.benefit-image`）、单份市场价值 `unitValue`（`Monetary JPY 0`）、领取地址 `pickupAddress`、领取时间 `pickupHours`、使用条件 `terms`（实物与服务必填前两项；数字不填）、状态 `LISTED` / `UNLISTED` / `TAKEN_DOWN`。**另建表而不扩展 Q60 的商品**：商品公开在主页，目录只给持有能力的商户看，字段与可见范围都不同；上架时可以从一件商品带入名称、简介与图片 |
| D-Q89-3 | **目录的可见范围**：持有 `BENEFIT_SPONSOR` 或 `BENEFIT_PROVIDER` 的商户都能看到全部 `LISTED` 的权益（模板 `qb.sponsor.benefit-catalog`，按种类、名称、地区（提供方入驻资料的国家）筛选）；答题者看不到目录，只在发布页看到发布引用的权益。总部与运营商可下架（运营商只下架自己的商户的，`OperatorChecks.ownSponsor`） |
| D-Q89-4 | **商户之间的对话 `QbBenefitConversation` + `QbBenefitMessage`**：一个出资方与一件权益之间至多一个对话（唯一 `(offerId, sponsorId)`），只由持有 `BENEFIT_SPONSOR` 的出资方开启，提供方回复。纯文本 ≤ 2000；**允许联系方式与收款信息**（不套 `hasAddress`），对话页常驻一句提示："付款在你们之间进行，QuizBuks 不经手、不担保"。每人每对话每分钟、每天的条数受 `jabiz.process.rate-limits` 限制；事件 `qb.benefit.message` 写站内信（每对话每小时至多一条）。总部与双方的运营商可读（处理纠纷）。不能附文件（凭证文件只在采购单上） |
| D-Q89-5 | **采购单 `QbBenefitPurchase`**（时态）：出资方、提供方、权益，**权益的快照**（种类、名称、单份价值、领取地址、时间、条件：确认时冻结，权益以后改了也不影响已确认的单），数量 `quantity`、已付金额 `paidAmount`（JPY）、付款日期 `paidOn`（`asDate`）、凭证文件（子实体 `QbBenefitPurchaseFile`，≤ 10，策略 `qb.benefit-proof`，`privateMetadata`，只有双方、发布审核人与运营商可读）、出资方附注、状态 `DRAFT` → `PENDING_PROVIDER` → `CONFIRMED` → `CLOSED`，以及 `CANCELLED`、`SUSPENDED`。流程：出资方 `QB_BENEFIT_PURCHASE_SAVE` / `_SUBMIT`（交提供方确认，提供方得到待办）；提供方 `QB_BENEFIT_PURCHASE_CONFIRM`（声明"已收到所填金额，承诺提供所填数量"，记确认人与时刻）/ `_DECLINE`（附理由，回到 `DRAFT`）；确认后改数量、金额、凭证都回到 `PENDING_PROVIDER` 重新确认，数量不能少于已划拨与已用的合计（422 `QB_BENEFIT_QUANTITY_BELOW_USED`）。**不需要运营商审核**。平台只记录、不判断付款真假 |
| D-Q89-6 | **发布引用采购单**：发布新增 `benefitPurchaseId`（可空）。只有 `OFFLINE_REDEEM`（种类 `GOODS` / `SERVICE`）与 `DIGITAL_PRIZE`（种类 `DIGITAL`）可以引用，**不新增奖励方式**：首页筛选、徽章、历史、结算的分支都沿用。引用时：采购单须为 `CONFIRMED` 且出资方是发布方本人（422 `QB_BENEFIT_PURCHASE_NOT_USABLE`）；兑换说明与等值金额取自快照（`amount` = 单份价值，仍受 `qb.reward.max-per-attempt` 约束）；**一位合格者一份**（档位只能有一档奖励大于 0，就绪检查 `QB_BENEFIT_ONE_TIER`）；`maxWinners` 必填，即本发布划拨的份数 |
| D-Q89-7 | **名额分配**（一张采购单给几个发布共用）：普通表 `QbBenefitAllocation`（`purchaseId`、`publicationId` 唯一、`quantity`、`used`、`status` `OPEN` / `RELEASED`）。**提交审核时**（`QB_PUBLICATION_SUBMIT`）在锁 `qb.benefit-purchase:<编号>` 内划拨：`Σ OPEN 的 quantity + Σ RELEASED 的 used + 本次 ≤ 采购数量`，否则 422 `QB_BENEFIT_STOCK_SHORT`；商户撤回（退回草稿）在撤回的流程中退回，审核被拒与**发布结束**经已有的发布状态事件 `qb.publication.status`（`to = DRAFT` / `ENDED`，Q7a D-Q7-2）由消费者 `qb.benefit-allocation` → `internal()` 流程 `QB_BENEFIT_ALLOCATION_RELEASE`记 `used = winners`、置 `RELEASED`，未用的份数回到采购单。**答题路径一条语句不加**：名额照旧是发布的 `maxWinners`，第 101 人照旧 `QUOTA_FULL`。第一步不改进行中发布的名额（要收回就暂停并结束，结束即退回） |
| D-Q89-8 | **审核看到采购**：发布审批内容加入采购单的快照、数量、本次划拨、已付金额、付款日期与凭证文件编号；改了采购单（重新确认）或换了采购单，内容哈希变化，按现有规则重审。审核页显示"赞助权益"区块（提供方公司名、权益、数量、金额、凭证文件的下载）。发布方的运营商审核发布；提供方属于别的运营商时，那个运营商在自己的采购单列表中看得到（只读） |
| D-Q89-9 | **由提供方履约**：`QbRedemption` 与 `QbDigitalPrize` 新增 `fulfillerId`（`processOnly`，没有采购单时等于发布方，旧数据回填为发布方）；上线时把 `fulfillerId` 冻结到卡片，结算不多读。到店领取时核对码属于 **`fulfillerId`**（原来是发布方）；提供方本人与其店员（Q39）领取、确认、拒绝；提供方后台的"奖品领取"按 `fulfillerId` 列出，出资方在发布上只读。数字权益：码池与共用码由**提供方**加载（`QB_DIGITAL_CODES_LOAD` 接受 `fulfillerId` 本人），`SPONSOR_SENT` 由提供方发送；密封的上下文改为 `fulfillerId`（有采购单时）。凭证有效期：发布新增可选 `redeemDays`，空则用参数 `qb.reward.redeem-days` |
| D-Q89-10 | **店员扫答题者的码**（新增的第二条路，**对所有到店兑换都可用**，不限赞助权益）：答题者在"我的权益"打开一张 `DUE` 的凭证时，`QB_REDEMPTION_PASS_ISSUE`（`qb.me`）签发**通行码**（普通表 `QbRedemptionPass`：令牌只存哈希、`f.sensitive()`；60 秒有效、只能用一次；同一凭证再签发即作废前一个），返回二维码内容 `<站点>/pass/<令牌>` 与一个 6 位数字码（`@Sensitive(returned = true)`）。店员或提供方在 App 扫一扫（`scanTarget` 加 `/pass/`，非店员打开时提示"这是顾客的领奖码"）或在商户后台输入数字码 → `QB_REDEMPTION_PASS_CHECK`（显示权益名称、领取条件、答题者显示名）→ 交付后 `QB_REDEMPTION_PASS_REDEEM`（`claimKind = PASS`，`REDEEMED`，`redeemedBy` = 经手人）。数字码只在本履约方未过期的通行码中查找；`CHECK` 按经手人限流（每分钟 10 次），防止猜码 |
| D-Q89-11 | **两条路共用一个终点**：扫店码、扫答题者码、确认固定码的申请，都在凭证锁内把 `DUE` / `CLAIMED` 改为 `REDEEMED`，所以只能核销一次。`C-xxxxxxxx` 照旧只用于口头核对，任何流程都不接受它核销 |
| D-Q89-12 | **异常**：`QB_BENEFIT_PURCHASE_SUSPEND`（提供方、出资方、总部、双方的运营商；附理由）→ 采购单 `SUSPENDED`，同一事务暂停全部引用它的进行中发布（`pausedBy` 按操作人）；可选"同时作废未使用的凭证"（`DUE` / `CLAIMED` → `VOID`，附理由，现有事件通知答题者）。`_RESUME` 恢复采购单（发布由各自的暂停者恢复）。异常记录 `QbBenefitIncident`（只插入：采购单、可选凭证、种类 `NOT_FULFILLED` / `CLOSED_BUSINESS` / `QUANTITY_MISMATCH` / `QUALITY` / `COMPLAINT` / `OTHER`、说明、记录人、处理结果）由双方、总部与运营商记录。答题者的投诉照旧经 Q82 的对话找**发布方**、经 Q49a 的举报找平台；平台不退款，退款写在条款中由合同双方处理 |
| D-Q89-13 | **统计**：模板 `qb.sponsor.benefit-purchases`（出资方与提供方都可读，各看自己是一方的）按采购单给出：采购数量、已确认数量、已划拨、已发凭证（`QbRedemption` / `QbDigitalPrize` 按发布汇总）、已核销、过期、作废、剩余、领取转化率（已核销 ÷ 已发）；按发布的漏斗沿用 Q9e。总部 `qb.admin.benefit-purchases`，运营商按范围 |
| D-Q89-14 | **权限**：`qb.benefit.offer.write`、`qb.benefit.purchase.write`、`qb.benefit.read`、`qb.benefit.conversation`（`QB_SPONSOR`），`qb.admin.benefit.read` / `.write`（内容管理员）；能不能用仍由 D-Q89-1 的认证决定（权限给全体商户，守卫按认证拒绝）。运营商的范围：采购单、对话、异常有两个商户字段，按"出资方或提供方属于本运营商"限定（§8 风险 1） |
| D-Q89-15 | **删除账号**（`SponsorErasure`）：采购单、分配、异常是商户之间的业务记录，`keep`；对话消息 `keep(senderUserId, body)`（文字清空、留行，同 Q82）；凭证文件随采购单保留。未结束的采购单（`PENDING_PROVIDER` / `CONFIRMED` 有未用份数）在申请删除前以 `notices` 提示 |

## 3. 后端（`backend/quizbuks`，新包 `benefit`）

- **迁移**（编号开发时取下一个空号，Q83、Q87a 的分支也在用号）：`qb_benefit_offer`、`qb_benefit_conversation`、`qb_benefit_message`（只插入，有主键）、`qb_benefit_purchase_version` 与 `qb_benefit_purchase_file_version`（时态，`quizbuks_create_temporal_table`）、`qb_benefit_allocation`（`publication_id` 唯一、`purchase_id` 索引）、`qb_benefit_incident`、`qb_redemption_pass`（`token_hash` 唯一、`(fulfiller_id, short_code_hash, expires_at)` 索引）；发布版本与卡片加 `benefit_purchase_id`、`fulfiller_id`、`redeem_days`；`qb_redemption_version`、`qb_digital_prize_version` 加 `fulfiller_id`（回填为发布方）；`qb_redemption` 的 `claim_kind` 检查加 `PASS`。只追加表只加列（D58，`SealedColumnsHistory`）。
- **类**：`QbBenefits`（实体与数据视图）、`BenefitConfig`、`BenefitCodes`、`BenefitGuard`、`OfferProcesses`、`BenefitConversationProcesses`（`ConversationRules` 的长度与空白规则，去掉联系方式检查）、`PurchaseProcesses`、`PurchaseStatus`（纯 Java 状态机）、`Allocations`（纯 Java：可划拨数）、`AllocationProcesses`、`IncidentProcesses`；`reward` 包的 `RedemptionPassProcesses`、`RedemptionPasses`（令牌与数字码，`BlockingStep` 中 `SecureRandom`）。
- **改动**：`Certifications`（两项）；`PublicationEditing` / `PublicationReadiness` / `PublicationApprovals`（引用、就绪、审批内容）；`PublicationProcesses` 的提交与退回（划拨与退回）；`PublicationEditing.card`（冻结 `fulfillerId`）；`AttemptFinishing`（写 `fulfillerId`、取快照的说明与有效期，**不加语句**）；`RedemptionProcesses`、`StaffProcesses`、`DigitalCodeProcesses`、`DigitalPrizeProcesses`（按 `fulfillerId`）；`SponsorErasure`；`QbRoles`、`QbPermissions`、`QbParams`（无新受控参数）；文件策略两个。
- **模板**：`qb.sponsor.benefit-catalog`、`qb.sponsor.benefit-offers`（本人上架的）、`qb.sponsor.benefit-conversations` / `-conversation`、`qb.sponsor.benefit-purchases` / `-purchase`、`qb.sponsor.benefit-redemptions`（提供方的待核销与已完成）；`qb.app.publication` 与 `qb.app.feed` 加提供方公司名、领取地址与时间；`qb.app.vouchers`（我的权益）；`qb.admin.benefit-*`。
- **文案**：错误码、站内信种类（`BENEFIT_PURCHASE_SUBMITTED` / `_CONFIRMED` / `_DECLINED` / `_SUSPENDED`、`BENEFIT_MESSAGE`）、字典三语。

## 4. 前端

- **商户后台**（`quizbuks-web/sponsor`）：菜单组"赞助权益"——权益目录（浏览、搜索、开启磋商）；我的权益（提供方上架，可从商品带入）；磋商（对话列表与对话页，常驻付款提示）；采购单（新建、提交、提供方确认或拒绝、凭证上传、划拨与使用情况）；领取（提供方：待核销、已完成、输入数字码核销）；异常。发布向导：奖励方式为到店兑换或电子奖品时可选"使用采购单"（只列出可用的，显示剩余份数），选中后说明与价值从快照带入、档位只能一档。持有认证前，这些菜单显示"先通过认证"并链到平台之树。
- **App**（`quizbuks-web/app`）：发布页显示"由 A 赞助，在 B 领取"、领取地址与时间、剩余份数（`maxWinners − winners`）；"我的权益"（凭证列表、详情、"出示领奖码"打开 60 秒的通行码二维码与数字码、倒计时、过期重取）；扫一扫认得 `/pass/`；店员的领奖台加"扫顾客的码"。
- **后台扩展**（总部与运营商）：采购单列表与详情（含凭证文件）、权益目录与下架、对话只读页、异常；审核页的"赞助权益"区块。

## 5. 测试

- 单元：`PurchaseStatusTest`、`AllocationsTest`（jqwik：任何划拨与退回的顺序都不超过数量）、`RedemptionPassesTest`（令牌长度与熵、数字码范围）、`BenefitGuardTest`、`CertificationsTest`（两项）。
- 集成：
  - `BenefitPurchaseIT`：没有认证即 422；提交 → 提供方确认 → 改数量回到待确认；别的商户 404；凭证文件只有双方与审核人读得到。
  - `BenefitAllocationIT`：两个发布共用一张 100 份的单（60 + 40 可以，60 + 41 返回 `QB_BENEFIT_STOCK_SHORT`）；并发提交两个发布只能成功一个；发布结束后退回未用的份数；审核被拒即退回。
  - `SponsoredBenefitIT`（**验收场景 B**）：面馆 B 上架牛肉面；冰箱商 A 磋商后建 100 份、3000 日元的单，B 确认；A 用自己的广告视频发布 5 题、合格 4 分的 Quiz；101 位答题者依次合格，前 100 位得到凭证，第 101 位开始即 `QUOTA_FULL`；凭证只能在 B 处领取（A 的码返回 `QB_REDEEM_TOKEN_SPONSOR`）；B 的店员扫答题者的通行码核销后变为已核销。
  - `RedemptionPassIT`：60 秒过期、只能用一次、再签发即作废前一个；别的商户的店员 404；店码与通行码同时核销同一凭证只成功一次；数字码猜测被限流；旧的 `C-` 码不被任何流程接受。
  - `SponsoredDigitalIT`（**验收场景 A**）：视频制作人上架 50 份播放权（共用链接），广告商采购、发布，答题者合格后经 `QB_DIGITAL_PRIZE_OPEN` 得到链接；码池由提供方加载，出资方不能加载、也看不到明文。
  - `BenefitSuspendIT`：暂停采购单即暂停全部引用它的进行中发布，可选作废未用的凭证，答题者收到通知。
  - `BenefitScopeIT`：出资方与提供方属于两个运营商时，各自的运营商都看得到采购单，别的运营商看不到。
  - `SponsorErasureIT`、`InboxIT`（对话的通知）、`AttemptStatementBudgetIT`（**四个数不变**）、`PublicQueriesSnapshotIT`。
- 场景：`scenarios/qb/sponsored-benefit.yml`（固定时钟、快照；数量取 3，表示第 4 位被拒）。
- 前端 Vitest 与 axe：商户后台各页、向导的采购单选择、App 的"我的权益"与通行码、扫一扫的 `/pass/`、后台扩展各页；e2e `sponsored-benefit.spec.ts`（商户 A 与 B 两个账号走完场景 B 的界面路径，店员以相机画面扫通行码，同 Q52 的做法）。

## 6. 文档

`02-design.md`（实体与流程）、`01-requirements-analysis.md` 增加本需求的对照、`operations.md`（采购单与异常的处理、商户之间的纠纷不由平台裁定）、商户条款草稿加一条"商户之间的采购与付款"（正式文本由需求方确认后在后台发布新版本）、`03-plan.md`。

## 7. 分期与估计

| 期 | 内容 | 估计 |
|---|---|---|
| Q89a | 认证两项、权益目录、商户之间的对话、采购单与凭证文件（后端 + 商户后台） | 5 天 |
| Q89b | 发布引用采购单、名额划拨与退回、审核内容、提供方履约（实物与数字）、App 的显示与"我的权益" | 6 天 |
| Q89c | 店员扫答题者的通行码（对所有到店兑换） | 3 天 |
| Q89d | 异常与暂停、统计、总部与运营商后台、e2e、文档 | 4 天 |

合计约 18 天，四个 PR，按 a → b → c → d 的顺序。c 只依赖 Q22b，可以提前。

## 8. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 中 | 运营商的范围要按"出资方**或**提供方"限定，平台的 `withinEntryScope` 一次只按一个字段 | 两个数据视图（`asSponsor:` / `asProvider:`）各按一个字段限定，模板 `UNION`；不够时先在 `1.3/platform` 补"任一字段在范围内"，带平台自己的测试与示范 |
| 中 | 一张采购单的多个发布同时提交，导致超额划拨 | 划拨在 `qb.benefit-purchase:<编号>` 锁内重读；`AllocationsTest` 用 jqwik 测性质，集成测试测并发 |
| 中 | 结束事件丢失或迟到，份数长期停在已结束的发布上 | 事件经 Outbox 投递；另有逐项任务每天把已结束而仍为 `OPEN` 的分配退回（同一流程，幂等） |
| 中 | 6 位数字码被猜中 | 只在本履约方 60 秒内的通行码中查找，经手人限流，失败次数进审计；二维码的令牌是 128 位 |
| 中 | 法务：第三方出资的奖品怎样归类（景品表示法），商户之间的付款是否涉及平台 | 申报价值照旧受上限约束；条款写明平台不经手采购款；正式上线前交法务 |
| 低 | 改 `fulfillerId` 影响 Q22、Q33、Q39、Q74 的现有路径 | 没有采购单时 `fulfillerId` = 发布方，现有测试与场景应原样通过（快照只多一列） |
| 低 | 商户在对话中留联系方式，被用来绕过平台交易 | 需求方的决定（§0 第 3 条）；对话可被举报，总部与运营商可读 |

## 9. 需求方的确认（2026-11-03）

1. 两项能力用**两个认证**（D-Q89-1）：能分就分。
2. **不新增奖励方式**，挂在到店兑换与电子奖品上（D-Q89-6）。
3. **一位合格者一份**，只能一档奖励大于 0（D-Q89-6）。
4. 名额**提交时划拨、结束时退回**，第一期不改进行中发布的名额（D-Q89-7）。
5. 通行码（店员扫答题者）**对所有到店兑换开放**（D-Q89-10）。
6. 答题者有问题找发布方（D-Q89-12）：按建议。
7. 编号 Q89，四期（§7）。

## 10. 实施记录

### Q89a（2026-11-03，分支 `1.3/quizbuks-89a-benefit-purchases`）

做了什么：

- **认证**：`Certifications` 加 `BENEFIT_PROVIDER`、`BENEFIT_SPONSOR`（一年、版本 1，`BENEFITS` 列表；不在 `FOR_TAKERS` 中：是商户的能力，不是答题的前置）。字典、三语证书名 `qb.certification.<键>`、两个 SPA 的 `qb/ids.ts` 与 `certification.names`、App 的证书名、后台扩展的 `ids.ts` 与名字、八个模板的 `VALUES` 目录与 `CertificationTemplatesTest` 一起改。
- **权限**：`qb.benefit.use`（`QB_SPONSOR`）、`qb.admin.benefit.read`（内容管理员，超级管理员随之）、`qb.benefit.system`（不授予任何角色，两侧的自有数据视图）。部署后执行一次 `QB_SETUP`。
- **包 `benefit`**：`QbBenefits`（四个实体）、`BenefitCodes`、`BenefitRules` 与 `PurchaseStatus`（纯 Java）、`BenefitSteps`（共用步骤：权限、已验证邮箱、封禁、认证、对方的封禁、文件归属）、`OfferProcesses`、`BenefitConversationProcesses`、`PurchaseProcesses`、`BenefitConfig`。
  - 持有认证只经 `RoundHolders.loadHoldings` / `heldKeys` 判断（含尚未认领的合格），模板 `qb.sponsor.benefit-access` / `-catalog` 写同一段 SQL。
  - 新建权益与重新上架要 `BENEFIT_PROVIDER`；隐藏与修改不要（认证到期不妨碍下架）。开启洽谈与新建采购单要 `BENEFIT_SPONSOR`；已有的洽谈与采购单照常。
  - 采购单：保存即从权益复制快照；`CONFIRMED` 或 `PENDING_PROVIDER` 的修改回到 `PENDING_PROVIDER`；提交至少一个凭证；提供方确认与退回都带 `baseRevision`（确认必填）；待办 `qb.benefit.confirm`（提供方）与 `qb.benefit.resubmit`（赞助方，退回时）。
- **迁移 V67**：`qb_benefit_offer`、`qb_benefit_conversation`（`(offer_id, sponsor_id)` 唯一）、`qb_benefit_message`、`qb_benefit_purchase_version`（时态，只插入）。
- **模板**（`queries/qb/sponsor/benefit-*.sql`，权限 `qb.benefit.use`）：`access`、`catalog`、`offers`、按侧分开的 `conversations` / `inquiries`、`messages` / `inquiry-messages`、`purchases` / `orders`。
- **删除账号**（`SponsorErasure`）：消息 `keep(senderUserId, body)`；权益 `clear` 图片（目录只显示入驻仍在的提供方）；采购单两侧各 `keep` 自己写的自由文字（`note` / `declineReason`）。
- **商户后台**（`quizbuks-web/sponsor/src/benefits/`）：菜单组"赞助权益"：权益目录（认证卡、搜索、种类、联系提供方、登记采购）、我的权益（列表与编辑对话框，图片至多 4 张）、洽谈（我发起的 / 收到的）、采购单（我购买的 / 向我购买的；赞助方编辑、提交、撤回，提供方确认或退回）。
- **测试**：`BenefitRulesTest`（7）、`BenefitIT`（4：目录与认证、洽谈允许联系方式、确认与退回的修订号与只插入、自己的与未上架的不能买）、`CertificationTemplatesTest`、`QbRolesTest`、`PublicQueriesSnapshotIT` 快照（认证的取值多两项）；前端 `benefits.test.tsx`（5，含 axe）。

与计划不同之处（有理由，均在 Q89a 内定下）：

1. **凭证是采购单上的五个文件字段**（`proof1`–`proof5`），不是子实体：数量小、随采购单的版本一起保存，比时态子表简单（D-Q89-5 的"≤ 10"改为 ≤ 5）。
2. **模板按侧分开**：平台的模板一个实体只对应一个数据视图，不能把"我是赞助方"与"我是提供方"的行合在一张表里；页面本来就分两个标签。
3. **目录也列出自己的权益**（标"您自己的权益"）：模板无法排除调用者自己的行；买自己的由流程拒绝（`QB_BENEFIT_OWN_OFFER`）。
4. **洽谈每日条数沿用 `qb.conversation.daily-cap`**，不加新参数（新参数会使场景的编号移位）；需要分开时再加。
5. **洽谈与采购单的站内信、运营商的读取与下架**放到 Q89d（Q89a 只有待办）；默认数据视图已登记 `OperatorScopes`（按赞助方），运营商的权限还没有它们。

### Q89b-1（2026-11-03，分支 `1.3/quizbuks-89b-benefit-publications`）：实物与服务

需求方 2026-11-03 同意把 Q89b 分成 Q89b-1（实物与服务）与 Q89b-2（数字产品）。

做了什么：

- **发布**：`QbPublication` 加 `benefitPurchaseId`、`benefitSnapshot`（`BenefitPublishing.Snapshot` 的规范 JSON：提供方、权益、数量、已付、付款日、凭证、修订号）、`fulfillerId`；保存时（只在 `OFFLINE_REDEEM`）只认本人已确认的实物或服务采购单（别人的 404），写快照与履约方，没写说明时以权益的名称、领取地址与时间、条件作说明；复制随发布带上。
- **审核**：审批内容加 `benefit`（即快照），采购单改了要重存草稿、内容哈希随之变，按现有规则重审（D-Q89-8）。
- **提交**：在发布锁之后取 `qb.benefit-purchase:<采购单>`（锁序：发布锁 → 采购单锁 → 审批锁；没有采购单的发布不取、不多读一条），重读采购单与全部分配，`BenefitPublishing.problems`：已确认、是本人的、修订号与草稿所见一致（否则 `QB_BENEFIT_PURCHASE_CHANGED`）、方式是到店兑换、恰一档有奖且等于单份价值（`QB_BENEFIT_ONE_TIER`）、有 `maxWinners`（`QB_BENEFIT_QUOTA_REQUIRED`）且不超过剩余（`QB_BENEFIT_STOCK_SHORT`）；通过即写分配（同一发布再提交复用同一行）。
- **退回**：逐项任务 `qb.benefit-allocation-release`（每 10 分钟，模板 `qb.system.benefit-allocations-due`，`internal()` 流程 `QB_BENEFIT_ALLOCATION_RELEASE`）：回到草稿的删除分配，结束的记 `used = winners` 并置 `RELEASED`；在采购单锁内重读发布，提交与退回因此不会交错。**与计划不同**：不订阅 `qb.publication.status`（它不覆盖审核超时、封禁、删除账号等路径），任务覆盖全部路径；代价是退回最多晚 10 分钟。
- **卡片与结算**：卡片冻结 `benefitPurchaseId` 与 `fulfillerId`；结算写 `QbRedemption.fulfillerId`（不多读）。答题路径的语句数不变。
- **履约**：`QbRewards.handlerOf`（履约方，没有即发布方）用于扫码核对（`QB_REDEEM_TOKEN_CHECK` 列出本商户交付的奖品，`QB_REDEMPTION_CLAIM` 核对码属于履约方）、商户的确认与拒绝（改经默认数据视图，不是履约方即 404）、店员的确认与拒绝；App 的三个店员模板按履约方；商户自己的兑换页与经手人统计排除别人赞助的奖品，提供方另有 `qb.sponsor.benefit-redemptions`（数据视图 `fulfiller:QbRedemption`）。
- **迁移 V69**（Q87b 先占了 V68）：上述列（只追加表只加列）与 `qb_benefit_allocation`（`publication_id` 唯一）。
- **前端**：商户后台向导"奖品来自权益采购单"（只列已确认的实物与服务采购单）、兑换页的"您为其他商户提供的权益"待确认；App 发布页"由发布方赞助，在 X 领取"（`qb.app.publication` 的 `fulfillerCompany`）。
- **测试**：`BenefitPublicationIT`（4：**验收场景 B**——100 碗牛肉面，100 位合格者都在面馆领取、第 101 位不能开始、发布方的码被拒、面馆列出 100 个、结束后分配记用掉 100；提交只能取剩余、撤回经任务退回；只认本人已确认的采购单、一档等值、采购单改后须重存；提供方的店员确认固定码的申请、发布方不能确认）。

### Q89b-2（2026-11-03，分支 `1.3/quizbuks-89b2-benefit-digital`）：数字产品

需求方 2026-11-03 同意的方案：数字产品只经码池（`POOL`）由提供方装码；共用码（`SHARED`）与发布方手工发送（`SPONSOR_SENT`）一律拒绝（`QB_BENEFIT_DIGITAL_DELIVERY`）。

做了什么：

- **发布**：`DIGITAL_PRIZE` 可引用本人已确认的数字产品采购单（实物与服务仍只配 `OFFLINE_REDEEM`，方式不配即 `QB_BENEFIT_PURCHASE_NOT_USABLE`）；没写说明时以产品的名称与条件作说明；提交时 `BenefitPublishing.problems` 另查 `digitalDelivery = POOL`，码池的可用数照 Q33 由 `PublicationReadiness.pool` 查。
- **码池是提供方的**：`QB_DIGITAL_CODES_LOAD` / `REVOKE` 经默认数据视图读发布，`DigitalCodeProcesses.requireHandler`：有履约方的只认履约方，没有的只认发布方（`SPONSOR`），其他人 404。码以装码人的编号为上下文密封，`QB_DIGITAL_PRIZE_OPEN` 以 `QbRewards.handlerOf` 打开，两者因此一致。码行仍带发布方那一对：发布方经原有的 `qb.sponsor.digital-pool-summary` / `digital-codes` 看得到计数与末四位，但不能装或收回。
- **结算**：`QbDigitalPrize.fulfillerId`（迁移 V70，只追加表只加列）由卡片写入，不多读；答题路径的语句数不变。
- **提供方的页面**：模板 `qb.sponsor.benefit-publications`（数据视图 `fulfiller:QbPublication`：用到本人权益的发布，含草稿，按采购单筛选，带码池的可用与已发数）、`qb.sponsor.benefit-digital-pool-summary` 与 `benefit-digital-codes`（经同一数据视图限定）；商户后台的采购单页（提供方一侧）列出这些发布，数字产品的可展开码池（`DigitalPoolSection` 的 `provided` 模式）；发布方的发布页对这种码池只显示计数（`watch` 模式）。向导在 `DIGITAL_PRIZE` 时只列数字产品的采购单。
- **测试**：`BenefitDigitalIT`（2：电影通行证——咖啡店不能装码、提供方装码与模板、陌生商户读不到、上架、获奖者打开提供方的码；共用码与手工发送被拒、数字产品不能做到店兑换）；前端 `benefits.test.tsx` 的提供方装码。

未做（记下）：**提供方手工发送**要 Q33b 的发送功能（Q33 计划中商户发送的那一步从未实现），做 Q33b 时再开放 `SPONSOR_SENT`。

### Q89c（2026-11-03，分支 `1.3/quizbuks-89c-redemption-pass`）：答题者的通行码

D-Q89-10 的第二条领取路线，对所有到店兑换开放（不限赞助权益）。

做了什么：

- **通行码** `QbRedemptionPass`（普通表，迁移 V71）：每个奖品一行，再签发即更新该行（前一个随即失效）；只存二维码密钥与 6 位数字的 SHA-256 哈希（数字与履约方编号一起哈希，同样的数字在别的柜台是另一个哈希），60 秒有效（`QbRewards.PASS_LIFE`）、用一次（`usedAt`）。迁移同时让兑换的 `claim_kind` 接受 `PASS`。
- **流程**（`reward.PassProcesses`）：答题者 `QB_REDEMPTION_PASS_ISSUE`（`qb.me`，奖品锁内，只给本人 `DUE` / `CLAIMED` 的奖品，输出 `passKey` 与 `passDigits`，标 `@Sensitive(returned = true)`）；商户 `QB_REDEMPTION_PASS_CHECK` / `_REDEEM` 与店员 `QB_STAFF_REDEMPTION_PASS_CHECK` / `_REDEEM`（店员照 Q39 经共享的店员锁）。查询只认本柜台（`QbRewards.handlerOf`）：按密钥找到别的柜台的即 404，按数字只在本柜台未用、未过期的通行码中找（两个相同即 `QB_REDEMPTION_PASS_AMBIGUOUS`，请扫码）；交付在奖品锁内重读奖品与通行码，`REDEEMED`、`claimKind = PASS`、`redeemedBy` 为操作人，店员不交付自己的奖品。两个查询流程每人每分钟至多 10 次（`jabiz.process.rate-limits`）。
- **与计划不同**：查询的输入名是 `typedDigits` 而不是 `passDigits`：平台按名字在整个 JSON 中遮蔽，签发的输出 `passDigits` 是"返回给调用者"的，若输入也叫它，较严的规则使输出也被遮蔽。
- **删除账号**：答题者的通行码与商户柜台的通行码随之删除（`TakerErasure`、`SponsorErasure`）。
- **App**：结果页的奖品有"出示领奖码"（`pass/PassDialog.tsx`：二维码、6 位数字、按收到回答起算的 60 秒倒计时、过期即"换一个新码"；出示期间每 3 秒重读奖品，交付后对话框说"已交付"）；扫一扫认得 `/pass/<密钥>`（`scan/target.ts`），在店员手机上打开交付页（`pass/PassPage.tsx`），在别人手机上只说"这是顾客的领奖码"；店员柜台加"顾客的奖品"（扫码链接与输入数字）。
- **商户后台**：奖品领取页加"顾客的领奖码"（输入数字、查询、交付）。
- **测试**：`RedemptionPassIT`（4：按二维码与数字读取并交付、只存哈希、别的柜台 404、用过与再签发失效、60 秒过期、店员交付与店码同时领取只成功一次、店员不交付自己的、猜数字被限流）；App `pass.test.tsx`（4，含 axe）、商户后台 `publishing.test.tsx` 的一例。

### Q89d-1（2026-11-03，分支 `1.3/quizbuks-89d-benefit-incidents`）：异常、暂停与统计

Q89d 分成两期：d-1（本期：暂停与恢复、作废未用的奖品、异常记录、按采购单的统计与商户后台）与 d-2（站内信通知、总部与运营商的后台页面、审核页的"赞助权益"区块、场景 B 的 e2e、文档）。

做了什么：

- **暂停**（`benefit.IncidentProcesses`）：`QB_BENEFIT_PURCHASE_SUSPEND`（任何一方，附理由，可选 `voidUnused`）只对 `CONFIRMED` 的采购单。同一事务中先按发布编号升序以子流程 `QB_PUBLICATION_BENEFIT_PAUSE`（`subProcessOnly`，`pausedBy = SPONSOR`）暂停用到它的进行中的发布，再取采购单锁、重读采购单与分配，写 `SUSPENDED`。锁序与提交相同（发布锁 → 采购单锁）；其间若有发布刚取得份额而没被暂停，即 `QB_BENEFIT_PURCHASE_CHANGED` 请重试。
- **作废未用的奖品**：逐项任务 `qb.benefit-void`（每 10 分钟，模板 `qb.system.benefit-void-due`），每项 `QB_REDEMPTION_SUSPEND_VOID`（`internal()`，奖品锁内重读：采购单仍暂停且要求作废、奖品仍未领取才作废，`voidNote` 为暂停理由），沿用作废事件通知答题者。没有放在暂停的事务里，以免一次取上千把奖品锁。
- **恢复**：`QB_BENEFIT_PURCHASE_RESUME` 回到 `CONFIRMED`、停止作废；发布照旧暂停，由发布方自己恢复（`QB_PUBLICATION_RESUME` 在采购单暂停时拒绝 `QB_BENEFIT_PURCHASE_SUSPENDED`；没有采购单的发布不多读一条）。**与计划不同**：暂停与恢复只改状态、不加修订号，用到它的草稿不必重存。
- **异常**：普通表 `QbBenefitIncident`（迁移 V72），`QB_BENEFIT_INCIDENT_RECORD`（任何一方，可指一个本采购单的奖品，别的即 `QB_BENEFIT_INCIDENT_PRIZE`）与 `_RESOLVE`（任何一方写处理结果，一次，`QB_BENEFIT_INCIDENT_RESOLVED`）；两侧经 `qb.sponsor.benefit-incidents` / `-order-incidents` 读，记录人只显示为一方，不给编号。删除账号保留记录、清空该商户写的文字。
- **统计**（D-Q89-13）：两个采购单列表模板加 `allocated`（进行中的发布占用）、`given`（已结束的发布发出）、`issued`、`collected`、`expired`、`voided`（到店兑换与电子奖品按卡片的 `benefitPurchaseId` 合计）与暂停的字段。
- **商户后台**：采购单页对已确认、已暂停、已完成的单显示"使用情况"、暂停（理由、是否作废）与恢复、异常记录（记录、写处理结果）。
- **测试**：`BenefitSuspendIT`（2：暂停两个进行中的发布、不能恢复发布、不能用于新草稿、任务作废奖品、统计、恢复后修订号不变；任何一方记录与处理异常、别人的 404、别的采购单的奖品被拒、只处理一次）；`ProcessAccessIT` 加两个流程；商户后台 `benefits.test.tsx` 的一例。
