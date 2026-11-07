# F-S1 运营商化（非钱部分）：实施计划

依据：`docs/quizbuks/first-launch-spec.md` F-S1（以它为准）；存档 `Q92a-operators-everywhere.md` §1–§5、§7、§8 只作参考。会话 S2，分支 `1.3/quizbuks-FS1-operators-everywhere`。

## 0. 需求方 2026-11-07 的四条答复（已写回规格 F-S1）

1. **平台运营商的直营发起方**：`QB_SETUP` 建平台运营商时没有负责人；任何运营商**第一位负责人**被指定时（建运营商的 `QB_OPERATOR_SAVE` 必填负责人，或 `QB_OPERATOR_MEMBER_ADD` 以 `lead=true` 加入而该运营商还没有直营发起方），同一事务把他的账号设为直营发起方。还没有直营发起方的运营商由每日核对告警。
2. **改派时的审批请求**：平台的审批请求的范围不可变，没有"移到另一个范围"的能力 → **平台缺口，交给 S1**（平台步骤：把待审的审批请求与其待办移到另一个入口范围）。在它合入之前，改派遇到该发起方有待审的审批（入驻、发布、版本切换、主页）即 422 `QB_SPONSOR_REVIEW_PENDING`（列出待审的对象），其余照常改派。
3. **直营发起方的角色**：`QB_OPERATOR_SAVE` / `MEMBER_ADD` 只写入驻（已通过）、归属（不可改派）与运营商的 `directSponsorId`；**发起方角色由负责人本人经平台的加入入口**（`POST /api/auth/account/entries/sponsor/join`）取得，应用的流程不授予角色。运营商管理系统提示负责人加入发起方入口。
4. **现金争议**：平台不处理；学者提出争议只通知发起方；双方解决后由发起方再次标记已支付（`DISPUTED` → `MARKED_PAID`，确认期限重新起算），或由学者直接确认。
5. **删除清单全部确认与补充要求**（2026-11-07）：§6 的清单全部确认。补充：
   - 无法明确找到运营商的发起方一律归默认运营商（平台运营商，`auto:default`）。
   - 除财务以外的业务都由运营商做；财务（银行充值、手工入账、记费、结算等）仍由平台（内容管理员保留财务四眼的 `approval.decide`）。
   - **平台的管理系统中能查询到所有相关信息**：搬到运营商的功能，在平台一侧保留全部运营商的只读、可按运营商筛选的查询页（审核、发起方主页与商品、举报、兑换、数字奖品、奖励方式、获客、认证等）；F-S1b 不是去掉菜单项，而是改为只读查询页。
   - 平台运营商由 `QB_SETUP` 建立并负责其余一切；为以后扩大准备，一开始就可能建很多运营商：建运营商、加负责人与成员、改派的界面与流程要支持很多个（列表分页、选择器可搜索，F-S1b 逐处检查）。
   - 不再用"后台"一词：运营商的叫"运营商管理系统"，平台的叫"答题拼好价平台管理系统"（日、英见 glossary.md）；F-S1b 新写的文字照此。
6. **运营商的建立**（2026-11-07，取代第 1 条中"`QB_SETUP` 建平台运营商、没有负责人"）：所有运营商都在 答题拼好价平台管理系统中以 `QB_OPERATOR_SAVE` 建立，必须带负责人（即直营发起方）；第一个设为默认运营商（`defaultForNew`）。实现：`SaveInput.defaultForNew`（设为默认时在锁 `qb.operator.default` 内、同一事务取消原默认，先写原默认那一行以免撞部分唯一索引；默认的不能直接取消 `QB_OPERATOR_DEFAULT_KEEP`、不能停用 `QB_OPERATOR_DEFAULT_INACTIVE`）；`QB_SETUP` 不建运营商（`QbSetupProcesses` / `QbSetupRecords` 回到 F-S1 之前）；每日核对加 `NO_DEFAULT_OPERATOR`；IT 夹具在安装后以 `installDefaultOperator()` 建默认运营商，e2e 在 `prepareQuizbuks` 中以 `ensureDefaultOperator`，本机演示的 `seed` 以 `default_operator`（演示自己的角色 `QB_DEMO_OPERATORS`）。

## 1. 分 PR

规格估 7–9 天。照根 CLAUDE.md 第 7 节"阶段过大时拆成多个 PR"，F-S1 分两个 PR，依次合并：

| PR | 内容 |
|---|---|
| **F-S1a 后端** | 迁移、运营商的新属性、平台运营商、直营发起方、归属必填、改派、审核与待办的范围、去掉 `direct`（获客、认证）与官方账号、现金争议、角色、每日核对、站内信；后端测试、夹具、场景快照、消息 |
| **F-S1b 管理系统与文档** | 管理系统扩展的菜单拆分（`AX/index.tsx`）、平台管理系统页面收缩、运营商管理系统补齐（奖励方式、兑换与数字奖品争议、发起方主页与商品、举报、获客）、监控页的告警与审核积压、发起方页面"你的运营商"、帮助与 `operations.md`；前端测试、axe、e2e 改由运营商成员审核 |

## 2. 数据（迁移 `V78__quizbuks_operators_everywhere.sql`（S3 的 F-C1a 用了 V77），合并前按 `1.3/quizbuks` 的最大号 + 1 重新编号）

平台在测试、重置数据库，迁移不搬数据（规格 F-S1）。

| 表 | 改动 |
|---|---|
| `qb_operator`（普通表） | 加 `default_for_new boolean not null default false`（部分唯一索引：至多一行为真）、`publisher_kinds varchar(40) not null default 'SPONSOR'`、`direct_sponsor_id varchar(128)`（唯一，可空：第一位负责人之前为空，见答复 1） |
| `qb_sponsor_operator`（普通表） | 加 `fixed boolean not null default false`（直营发起方的归属，不能改派） |
| `qb_official_publisher` | 删表（官方账号 = 直营发起方） |

`qb_publication_card.operator_id` 与平台费的 `operator_id` 改为非空属于 F-M（同一批迁移的钱的部分，规格 F-S1 依赖一行）；本项只保证新卡片都有运营商（入驻一定有归属），并在 `OperatorsEverywhereIT` 中断言。

## 3. 改动

### 3.1 运营商（`operator`）
- `QbOperators`：新字段 `defaultForNew`、`publisherKinds`、`directSponsorId`；`ASSIGNMENT` 加 `fixed`；去掉 `BY_LANGUAGE` 与"没有归属即总部"的说明；新的 `assignedBy` 值 `auto:default`（自然产生）、`auto:direct`（直营）。语言只作展示与获客：去掉 `LANGUAGE_TAKEN` 与语言锁。
- `OperatorProcesses.save`：建立时必填 `leadUserId`，同一事务加负责人成员、写直营发起方（`QbSponsor` 已通过、`fixed` 归属、`directSponsorId`）。负责人必须已验证邮箱、不是别处的成员或发起方（照 `MEMBER_ADD` 的检查）。
- `MEMBER_ADD`：`lead=true` 且运营商没有直营发起方时同样写直营发起方；`MEMBER_IS_SPONSOR` 只对"不是本运营商的直营发起方"的发起方报。
- `ASSIGN`：`operatorId` 必填；直营发起方 422 `QB_DIRECT_SPONSOR_FIXED`；目标运营商必须接收 `SPONSOR`（`publisherKinds`）且在运行；有待审审批即 422 `QB_SPONSOR_REVIEW_PENDING`（答复 2）；非审批的待办（数字奖品争议、举报、费用逾期）关闭旧的、以新范围重建；改派后站内信通知发起方（新的 `QbInbox` 种类 `OPERATOR_CHANGED`）。
- `OperatorChecks.sponsorScope`、`ownSponsor`：没有归属即 404（不再当作总部）。
- `QB_SETUP`（`QbSetupProcesses`）：建平台运营商（`defaultForNew = true`，名称按 `app.name`，联系信息取 `quizbuks.operators.platform.*` 配置），只建从未存在过的（`QbSetupRecord`）。
- 每日核对：逐日任务 `qb.operator-check` / 流程 `QB_OPERATOR_CHECK_RUN`（`internal()`）以模板 `qb.system.operator-health` 查三项：没有归属的发起方、直营发起方不是恰好一个的运营商、能审核的成员（未移除、持 `QB_OPERATOR` 角色）不足两位的运营商；有问题即以 `CreateTask` 给 `qb.admin.operators.read`（来源键按日），平台管理系统经 `qb.admin.operator-health` 显示告警。

### 3.2 入驻与获客
- `SponsorProcesses.apply`：去掉 `assignByLanguage`；首次入驻一定写归属：获客的试用键 → 获客的运营商，否则 → `defaultForNew` 的运营商；没有这样的运营商即 422 `QB_OPERATOR_DEFAULT_MISSING`（安装未完成）。
- `ProspectProcesses.owner`：只看调用者的成员关系（总部不再获客，`qb.admin.prospect` 只读全部）；`QbProspects.DIRECT` 删除；`CLAIM` 不再区分语言分配（没有 `auto:language`）——认领只给 `auto:default` 的归属改派到获客的运营商。

### 3.3 认证与官方账号（F-S2 将整体删除，本项只去掉 `direct`）
- `QbCertifications.DIRECT`、`OFFICIAL` 实体、`QB_OFFICIAL_PUBLISHER_SET` 删除；轮的 `owner` 一律是运营商编号；"官方出题方"= 该运营商的直营发起方（读 `qb_operator.direct_sponsor_id`）。`PublicationRounds` 的 `owner` 取归属（一定有）。

### 3.4 审核、待办与平台优先
- `PublicationEditing.withOperator`、`DigitalPrizeProcesses`、`ReportProcesses`、`FeeProcesses`、`SponsorPageProcesses`：一律以归属的运营商为范围，去掉"没有运营商"的分支。
- `HQ_TAKEDOWN` / `HQ_BAN`：只改三语文案"总部" → "平台"，行为不变。

### 3.5 现金争议
- 删除 `qb.cash.resolve`（`QbPermissions.CASH_RESOLVE`）、`CashRewardProcesses` 的平台处理流程与消费者 `CASH_RESOLVED*`、对应的站内信种类与消息。
- 状态转移加 `DISPUTED` → `MARKED_PAID`（发起方解决后再次标记，确认期限重新起算；答复 4）。

### 3.6 角色（`QbRoles`）
- `QB_ADMIN_CONTENT` 去掉 `REVIEW_SPONSOR`、`REVIEW_PUBLICATION`、`ADMIN_REWARD_METHODS`、`CASH_RESOLVE`、`REDEMPTION_VOID`、`DIGITAL_RESOLVE`、`PROSPECT_USE`、`CERTIFICATION_MANAGE`、`approval.decide`；保留只读、抽查、全局规则（受众规则目录、AI 总开关）与应急（`ADMIN_CONTENT_WRITE` 的平台下架、`CERTIFICATION_ADMIN`）。
- `OPERATOR_PERMISSIONS` 加 `ADMIN_REWARD_METHODS`；`QbRolesTest` 的清单随之改。已有安装中的授权不收回（`QB_SETUP` 的规则），由管理员收回一次，写进 `operations.md`（F-S1b）。

## 4. 测试

- **新增** `OperatorsEverywhereIT`：自己入驻即归平台运营商；获客试用键入驻即归获客的运营商；建运营商即有直营发起方（负责人以发起方身份提交，自己不能审，另一位成员能审）；直营发起方不能改派；改派要求目标运营商、目标须接收 `SPONSOR`；有待审审批时改派被拒、审批结束后可改派；改派后待办以新范围重建、发起方收到站内信；所有新卡片都有运营商；每日核对的三项与告警待办。
- **源码检查** `NoDirectOwnerTest`：主代码与两个 SPA 的 `src/qb/` 中没有 `"direct"` 所有者常量（价目表 `QbAudiencePricing` 属于 F-M，列为白名单直到 F-M）。
- **改写**：夹具 `PublicationItSupport` / `AccountItSupport` 的审核人改为平台运营商的成员令牌（入口范围 = 平台运营商）；`OperatorIT`、`OperatorIsolationIT`、`OperatorLanguagesIT`（改为"不按语言分配"）、`OperatorScopesIT`、`ProspectIT`、`CertificationIT`、`SponsorCertificationIT`、`CashRewardIT`、`SetupIT`、`SetupRemovalsIT`、`ProcessAccessIT`、`QbRolesTest`，以及因此失败的其他类；场景快照（`setup.yml` 多一个运营商等）重新生成并逐个核对差异。
- `AttemptStatementBudgetIT` 不变（答题路径不读归属）。`platformCheck` 0 错误。

## 5. 风险

1. **测试面大**：几乎所有入驻、审核相关的 IT 依赖夹具；先改夹具，再逐类修。
2. **改派的过渡限制**：S1 的"移动审批请求"合入之前，有待审审批的发起方不能改派；运营上等审批结束再改派。合入后去掉 `QB_SPONSOR_REVIEW_PENDING`。
3. **已有安装的授权**：从 `QbRoles` 去掉的权限不会自动收回（`QB_SETUP` 只补）；测试系统重置数据库即可，`operations.md` 写明。
4. **F-S2 将删除认证**：本项对认证只做去掉 `direct` 与官方账号的最小改动，避免白做。

## 6. 删除与搬移的清单（需求方 2026-11-07 全部确认）

需求方 2026-11-07 的新规则："千万不能随意减少已经做好的功能。要有需求方明确的指示才能去掉东西；删除旧功能要多一次确认。"本节逐条列出 F-S1 删去或搬走的已有功能（删了什么｜原来谁能做什么｜之后由谁怎么做），由总控交需求方确认。

**后端（F-S1a）**
1. 官方出题方：表 `qb_official_publisher`、`QB_OFFICIAL_PUBLISHER_SET`｜内容管理员把某账号设为运营商或总部的认证官方出题方｜官方出题方固定为该运营商的直营发起方。
2. 平台裁决现金争议：`qb.cash.resolve`、`QB_CASH_REWARD_RESOLVE`、事件 `qb.cash-reward.resolved`、站内信 `CASH_RESOLVED` / `CASH_RESOLVED_SPONSOR`、管理员待办 `qb.cash-reward.dispute`｜管理员裁决，双方收到站内信｜平台不裁决；发起方再次标记已支付（`DISPUTED` → `MARKED_PAID`）或学者确认（答复 4）。
3. `QB_ADMIN_CONTENT` 去掉 `qb.review.sponsor`、`qb.review.publication`、`qb.admin.reward-methods`、`qb.redemption.void`、`qb.digital.resolve`、`qb.prospect.use`、`qb.certification.manage`｜内容管理员审核、设奖励方式、作废兑换、处理数字奖品争议、获客、管理认证｜所属运营商的成员；总部做这些的人改为平台运营商的成员。`approval.decide` 保留（财务四眼）。已有安装中的授权不被 `QB_SETUP` 收回。
4. 运营商语言唯一（`ux_qb_operator_active_language`、`QB_OPERATOR_LANGUAGE_TAKEN`、锁 `qb.operator.languages`）与按语言分配（`assignByLanguage`、`auto:language`）｜一种语言一个运营商，新发起方按语言自动归属｜语言只作展示与获客；自己入驻的归平台运营商，获客的归获客的运营商。
5. 总部的 `direct` 归属（`QbProspects.DIRECT`、`QbCertifications.DIRECT`）｜总部自己获客、开认证轮｜总部只读；运营商成员以自己的运营商进行。价目表的 `direct` 未动（F-M）。
6. 回到总部（`QB_SPONSOR_OPERATOR_ASSIGN` 不带 `operatorId`）｜总部把发起方移回直营｜只在运营商之间改派（可回平台运营商）。
7. 无归属发起方的审核与待办到总部（`withOperator`、`sponsorScope` 的分支；举报"给总部建待办"的步骤）｜到总部｜一律到所属运营商；总部的 `QB_REPORT_RESOLVE` 保留。
8. 建空运营商（`QB_OPERATOR_SAVE` 不带负责人）｜可先建后加成员｜建立时必填负责人（平台运营商例外）。
9. 无运营商也能入驻｜归总部｜没有平台运营商即 422 `QB_OPERATOR_DEFAULT_MISSING`。
10. 获客认领替换 `auto:language` 的归属｜—｜只替换 `auto:default`，有待审审批时不改派（等 S1）。
11. `HQ_TAKEDOWN` / `HQ_BAN` 文案"总部"→"平台"（改名，行为不变）。

**管理系统扩展（F-S1b，F-S1a 未动）**
12. `CashRewardsPage` 的裁决按钮与对话框、`resolveCashReward`、`ids.ts` 的 `cashResolve`（按钮现已因无权限不显示）。
13. `CertificationsPage` 的官方出题方设置、`officialSet`、`CERTIFICATION_DIRECT` 与"总部"显示（现在调用会 404）。
14. 获客页的总部 `DIRECT` 可写（`ProspectPage`、`ProspectSettingsPage`、`prospect-scope.ts`、`prospect-api.ts`）｜总部在平台管理系统中自己获客｜总部只读、按运营商筛选，获客在运营商管理系统。
15. 菜单 `AX/index.tsx` 的"总部内容"：审核、发起方主页与商品、举报、兑换、数字奖品、奖励方式、获客移到运营商管理系统（搬移）。
16. e2e 的入驻与发布审核改由运营商成员决定。

## 7. F-S1c 改派时移动待审的审批（平台 17zo，决策 D76）

答复 2 的过渡限制（有待审审批即 422 `QB_SPONSOR_REVIEW_PENDING`）在平台的 `APPROVAL_SCOPE_MOVE` 合入后去掉。

- `QB_SPONSOR_OPERATOR_ASSIGN`：在原有的一条 `QueryEntities.all` 中读该发起方作为准备人、范围名 `operator`、`PENDING` 的审批请求（至多 500 + 1）；改派之后以 `CallProcess.when` 运行 `APPROVAL_SCOPE_MOVE`（请求按（主题，对象）去重，待办随请求移动，原因即改派的理由）。已批准的请求不移动：其结果由与运营商无关的审批事件应用，过去批准的又很多。超过 500 个即 422 `QB_SPONSOR_REVIEW_PENDING`（文案改为"一次移动不了这么多"）。
- 权限：平台要求调用者另持有 `approval.scope.move`（不是受保护的权限）；`QbRoles` 把它加给持有 `qb.admin.operators.write` 的超级管理员。已有安装执行一次 `QB_SETUP` 补上。
- 举报与数字奖品争议的待办照旧经 `OperatorHandover`。获客认领（`QB_PROSPECT_CLAIM`，由发起方本人运行）的"有待审审批即不改派"不变：放宽它要把 `approval.scope.move` 给发起方角色，需要另行决定。
- 测试：`OperatorsEverywhereIT.aSponsorWaitingForAReviewIsMovedWithItAndHearsOfTheMove`（缺 `approval.scope.move` 即 403、改派后请求与待办的范围值是新运营商、新运营商的成员能决定）；`QbRolesTest`。


## 8. F-S1b 运营商管理系统与平台管理系统的拆分

两个入口用同一个管理系统扩展（`/admin/` 只接受平台的角色，`/operator/` 只接受 `QB_OPERATOR`），菜单项按权限显示，所以"拆分"就是：运营商的一切日常经营在 `qb.operator.use` 一组；平台一组只留监控、全局规则、应急与**跨运营商的只读查询**（答复 5）。F-S1b 再分两个 PR：

### F-S1b1 菜单、只读查询与删除（本 PR）
1. **菜单**（`index.tsx`）：
   - "运营商管理系统"一组（`qb.operator.use`）：首页、发起方、审核（发布、入驻、主页）、发起方主页、商品、举报、兑换、数字奖品、现金奖励、获客、认证、计费、结算、账户、价目。
   - 平台一组：Quiz、发布、用户、答题记录、法律文本、公告、AI、运营商、抽查、推荐、受众规则；跨运营商的只读查询："审核查询""发起方主页""商品""举报""兑换""数字奖品""现金奖励""获客""认证"——同一页面，按会话显示：运营商的会话可操作（权限决定按钮），平台的会话只读，多一个**运营商筛选**。平台一组的菜单项用运营商不持有的权限（`qb.admin.users.read` 等），`*` 两组都看到。
2. **按运营商筛选**：模板 `qb.admin.publications`、`qb.admin.sponsors`（待审入驻）、`qb.admin.sponsor-pages`、`qb.admin.sponsor-products`、`qb.admin.reports`、`qb.admin.redemptions`、`qb.admin.digital-prizes`、`qb.admin.cash-rewards`、`qb.admin.sponsor-certifications` 加参数 `operatorId` 与列 `operatorId` / `operatorName`（经 `QbSponsorOperator`）；获客照旧按所有者（`owner` = 运营商编号）。运营商的会话本来只读到自己的（范围），参数对它无意义、页面不显示筛选。
3. **很多运营商**：新组件 `OperatorPicker`（`Combobox` 远程搜索 `qb.admin.operators` 的 `q`，分页取 20 个）用于筛选、改派与获客的所有者；`OperatorsPage` 分页与搜索（模板加 `q`）。
4. **审核查询**（平台）：`ReviewsPage` 对不持有 `qb.review.*` 的平台会话显示只读的三个队列（发布、入驻、主页，带运营商列与筛选）；打开的审核页不显示决定面板（`ApprovalPanel` 只给持有审核权限的会话；内容管理员的 `approval.decide` 是财务四眼用的）。
5. **删除**（§6 第 12–14 条，需求方已确认）：`CashRewardsPage` 的裁决按钮与对话框、`resolveCashReward`、`PROCESS.cashResolve`、`PERMISSION.cashResolve`；`CertificationsPage` 的官方出题方一节、`officialSet`、`DATASET.officialPublisher`、`CERTIFICATION_DIRECT` 与"总部（直营）"；获客的 `DIRECT`（`prospect-api.ts`、`prospect-scope.ts`；平台的会话只读，所有者用 `OperatorPicker` 选，默认空 = 请先选择）；`OperatorPage` 的"改回总部"（后端已不接受不带运营商的改派）。
6. **文字**：新写与改动的文字不用"后台""总部"（运营商管理系统 / 平台管理系统 / 平台；日文 運営パートナー，英文 Operator Management System / the platform），扩展中现存的"总部"一并改为"平台"（`tools/quizbuks/terms`）。
7. **测试**：Vitest 各页（运营商会话可操作、平台会话只读且有筛选、删除的按钮不在）、`OperatorPicker`、axe；后端模板参数的 IT（运营商筛选、运营商会话的范围不变）。

### F-S1b2 首页、"你的运营商"、文档与 e2e（下一 PR）
- 扩展的 `home`：平台会话落到"平台首页"（每日核对 `qb.admin.operator-health` 的问题：没有默认运营商时置顶"请先建默认运营商"并链到运营商页；各运营商的审核量与积压），运营商会话落到运营商首页。
- 发起方页面（`quizbuks-web/sponsor`）显示"你的运营商"（名称与联系方式；模板 `qb.sponsor.operator`）；帮助说明。
- `operations.md`：运营商、直营发起方、改派（含审批移动）、获客、现金奖励各节；已有安装收回 `QB_ADMIN_CONTENT` 去掉的授权。
- e2e：入驻与发布审核改由运营商成员完成（`operators.ts` 的 `operatorWithLead` 与成员登录 `/operator/`）。
