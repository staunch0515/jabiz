# F-S1 运营商化（非钱部分）：实施计划

依据：`docs/quizbuks/first-launch-spec.md` F-S1（以它为准）；存档 `Q92a-operators-everywhere.md` §1–§5、§7、§8 只作参考。会话 S2，分支 `1.3/quizbuks-FS1-operators-everywhere`。

## 0. 需求方 2026-11-07 的四条答复（已写回规格 F-S1）

1. **平台运营商的直营发起方**：`QB_SETUP` 建平台运营商时没有负责人；任何运营商**第一位负责人**被指定时（建运营商的 `QB_OPERATOR_SAVE` 必填负责人，或 `QB_OPERATOR_MEMBER_ADD` 以 `lead=true` 加入而该运营商还没有直营发起方），同一事务把他的账号设为直营发起方。还没有直营发起方的运营商由每日核对告警。
2. **改派时的审批请求**：平台的审批请求的范围不可变，没有"移到另一个范围"的能力 → **平台缺口，交给 S1**（平台步骤：把待审的审批请求与其待办移到另一个入口范围）。在它合入之前，改派遇到该发起方有待审的审批（入驻、发布、版本切换、主页）即 422 `QB_SPONSOR_REVIEW_PENDING`（列出待审的对象），其余照常改派。
3. **直营发起方的角色**：`QB_OPERATOR_SAVE` / `MEMBER_ADD` 只写入驻（已通过）、归属（不可改派）与运营商的 `directSponsorId`；**发起方角色由负责人本人经平台的加入入口**（`POST /api/auth/account/entries/sponsor/join`）取得，应用的流程不授予角色。运营商后台提示负责人加入发起方入口。
4. **现金争议**：平台不处理；学者提出争议只通知发起方；双方解决后由发起方再次标记已支付（`DISPUTED` → `MARKED_PAID`，确认期限重新起算），或由学者直接确认。

## 1. 分 PR

规格估 7–9 天。照根 CLAUDE.md 第 7 节"阶段过大时拆成多个 PR"，F-S1 分两个 PR，依次合并：

| PR | 内容 |
|---|---|
| **F-S1a 后端** | 迁移、运营商的新属性、平台运营商、直营发起方、归属必填、改派、审核与待办的范围、去掉 `direct`（获客、认证）与官方账号、现金争议、角色、每日核对、站内信；后端测试、夹具、场景快照、消息 |
| **F-S1b 后台与文档** | 后台扩展的菜单拆分（`AX/index.tsx`）、总部页面收缩、运营商后台补齐（奖励方式、兑换与数字奖品争议、发起方主页与商品、举报、获客）、监控页的告警与审核积压、发起方后台"你的运营商"、帮助与 `operations.md`；前端测试、axe、e2e 改由运营商成员审核 |

## 2. 数据（迁移 `V77__quizbuks_operators_everywhere.sql`，合并前按 `1.3/quizbuks` 的最大号 + 1 重新编号）

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
- 每日核对：逐日任务 `qb.operator-check` / 流程 `QB_OPERATOR_CHECK_RUN`（`internal()`）以模板 `qb.system.operator-health` 查三项：没有归属的发起方、直营发起方不是恰好一个的运营商、能审核的成员（未移除、持 `QB_OPERATOR` 角色）不足两位的运营商；有问题即以 `CreateTask` 给 `qb.admin.operators.read`（来源键按日），总部后台经 `qb.admin.operator-health` 显示告警。

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

## 6. 删除与搬移的清单（待需求方再次确认，确认之前不合并）

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

**后台扩展（F-S1b，F-S1a 未动）**
12. `CashRewardsPage` 的裁决按钮与对话框、`resolveCashReward`、`ids.ts` 的 `cashResolve`（按钮现已因无权限不显示）。
13. `CertificationsPage` 的官方出题方设置、`officialSet`、`CERTIFICATION_DIRECT` 与"总部"显示（现在调用会 404）。
14. 获客页的总部 `DIRECT` 可写（`ProspectPage`、`ProspectSettingsPage`、`prospect-scope.ts`、`prospect-api.ts`）｜总部在后台自己获客｜总部只读、按运营商筛选，获客在运营商后台。
15. 菜单 `AX/index.tsx` 的"总部内容"：审核、发起方主页与商品、举报、兑换、数字奖品、奖励方式、获客移到运营商后台（搬移）。
16. e2e 的入驻与发布审核改由运营商成员决定。
