# F-ban-coverage：封禁检查覆盖全部由人发起的流程（实施计划）

状态：计划，待总控确认后由 S3 实施。依赖：#671（F-M15 a0，分支 `origin/1.3/quizbuks-FM15a0-ban-gaps`，未合并）。这不是钱的部分。
来源：Fable 对 #671 的审查——运营商一侧的角色还持有一些权限，其流程不查调用者是否被封禁，令牌未到期的被封禁成员仍可使用。

## 1. 范围

"封禁"只落在能登录的非管理员账号上：学者（`QB_TAKER`）、发起方（`QB_SPONSOR`）、运营商成员与负责人（`QB_OPERATOR`），以及 Shopify 店员（另一模块，不在首次上线，不在本计划）。
- 总部不在范围内：总部角色是平台管理系统入口接受的角色（`application.yml:183`），封禁流程拒绝封禁它们（`identity/BanProcesses.java:215-216`，"Administrators are disabled, not banned"），被封禁的人也登录不了（`identity/QbSignInGuard.java:33-35`）。只有总部持有的权限，其流程不需要检查。
- 运营商成员是可以被封禁的：`QB_OPERATOR` 不在管理入口的 `accepted-roles` 中，总部的 `QB_USER_BAN` 可以封禁运营商成员。
- 范围 = 权限被这三个角色之一持有（`setup/QbRoles.java:87-141` 运营商、`:229-` 学者与发起方）、不是 `internal()` / `subProcessOnly()` 的全部流程，所有包。
- 约定来自 `backend/quizbuks/CLAUDE.md:232`："一切由人发起的 QuizBuks 写入流程在最前面加 `.steps(CallerChecks::refuseBanned)`"（`identity/CallerChecks.java:27-38`，422 `QB_USER_BANNED`）。

## 2. 把 `BanCoverageTest` 扩到所有包

#671 的做法（`it/BanCoverageTest.java`）：遍历 `processes.all()`，按"输出映射所在类的包"筛出 `operator`、`fee`、`verification` 三个包，不是 `INTERNAL`、`SUB_PROCESS_ONLY` 的必须带步骤 `Load the caller's ban`（`CallerChecks.java:28`），否则列入 `EXEMPT`（理由"总部专属"或"只读"）；已带检查却在豁免表里的也失败。

**判定规则**（已核对 #671 分支 `BanCoverageTest.java`）：两条都在。`:99` 用 `anyMatch` 判断一个流程有没有封禁检查；`:81` 对有检查的流程要求它是**第一步**（`steps().getFirst().stepName()` 等于 `"Load the caller's ban"`）。本计划沿用这两条，只去掉包的过滤，并加上下面的 `BLOCKED_BY_HELPER` 与 `EXEMPT`。

泛化：
1. 去掉 `PACKAGES` 过滤，改成"权限是否被可封禁的角色持有"：由 `ProcessDefinition.permissions()` 对照 `QbRoles.of(TAKER|SPONSOR|OPERATOR).permissions()`（`AccountItSupport.java:72` 已有这种用法）。权限只在总部角色里的流程自动豁免，不再逐个写进 `EXEMPT`（现在的 `HQ` 条目由此推出；`EXEMPT` 只留"只读"和"撤回自己的东西"等需要理由的）。`alternativePermissions` 也要计入。
2. 按包分组报告失败，包名取输出映射所在类的包（#671 的注释已指出：运营商对发布与封禁的流程写在 `publishing`、`identity`，所以不能按文件夹猜）。
3. 流程的清单：`processes.all()` 来自注册表，只要 Bean 注册就在里面；**风险**：两种流程它看不到——在测试中才注册的流程、条件装配的流程（`@ConditionalOnProperty`）；另外 `permissions()` 为空或为变量（如 `CertificateProcesses.java:382`、`MailServerProcesses.java:101` 的 `permission` 参数）在注册后才有值，所以必须读注册后的定义，不能静态扫源码。静态扫描只用来写本计划的表，测试以注册表为准。
4. 只读流程：不能反射判断"只读"，进 `EXEMPT` 并写明理由"只读"（`QB_OPERATOR_TEAM` 已是）；每条豁免要在测试里断言"该流程存在"（#671 已有）。

**只被共享助手挡住、不以第一步 `Load the caller's ban` 开头的流程**（第一步规则下不通过）。处理：
- 经 `CallerChecks::refuseBanned` 的助手（`VideoUseProcesses.common`，`videouse/VideoUseProcesses.java:100-112`；`DistributionPrices.common`，`:125`；`AudiencePricingProcesses`、`ConversationProcesses`、`BenefitSteps` 的 `common`；`StaffProcesses.staffPlace`；`PassProcesses.counter`；`HelpProcesses.user`；`AssetProcesses.lockAndLoad`，`:339`）：在那个助手里 `refuseBanned` 若不是第一个步骤，**移到标准的第一步**（把助手里的调用提到最前，一行）；已是第一步的不用动。
- 经 `SponsorChecks`（发起方"已入驻且未封禁"，`publishing/SponsorChecks.java:27-31,42-52`，步骤名 `Load the sponsor's onboarding` / `Load the sponsor's ban`，`PublicationEditing.java:192-202`）：`BroadcastProcesses.sponsorReads`（`:199-204`，被 `QB_BROADCAST_SAVE`、`QB_BROADCAST_SEND` 使用）、`AiProcesses.java:296`、`SiteProfileProcesses.java:109`、`TopUpProcesses.java:120`、`KudosExchanges.java:157`、`BankTopUps.java:160`、发布提交（`PublicationLifecycle.java:126` → `PublicationEditing::loadSponsor`）、`SponsorPlay.java:135`。这些同时要求入驻通过，一次报告全部问题（`CLAUDE.md:233`），不换成 `refuseBanned`。**列入 `BLOCKED_BY_HELPER` 表，写明助手与 `file:line`**，测试对这张表断言：(a) 流程存在；(b) 步骤中有 `Load the sponsor's onboarding`（`SponsorChecks` 专有，`loadSponsorBan` 那一个只读发布的发起方，不算）；(c) 各有一条已有的 IT 断言 `QB_USER_BANNED`（实施时逐条核对，没有的补）。注意：同名步骤 `Load the sponsor's ban` 在 `PublicationEditing.java:198`（调用者）与 `:378`（发布的发起方，运营商下架用）都出现，不能按名字判定。

## 3. 清点表

方法：对 `backend/quizbuks/src/main/java` 全部包的 `ProcessDefinition.define(` 写了脚本，筛出不是 `internal()` / `subProcessOnly()` / `entryOnly()` 的，对照角色权限，再逐个读代码。下表的"现状"按"第一步是 `refuseBanned`"判。表一是运营商角色持有权限的全部流程（范围的核心）；表二是发起方与学者一侧未挡住的。"#671"表示 #671 已处理（其 `CLOSED` 集合），本计划只在泛化测试里一并固定。

### 3.1 运营商角色持有的权限

| 流程 | file:line | 权限与角色 | 现在挡吗 | 处理 | 绑定测试 |
|---|---|---|---|---|---|
| `QB_REDEMPTION_VOID` | `reward/RedemptionProcesses.java:492` | `REDEMPTION_VOID`：运营商、（总部无） | 否：只有 `memberWhenScoped`（`:502`） | 加 | `BanOperatorActsIT` |
| `QB_DIGITAL_PRIZE_RESOLVE` | `reward/DigitalPrizeProcesses.java:223` | `DIGITAL_RESOLVE`：运营商 | 否（`:235` 只有 `memberWhenScoped`） | 加 | 同上 |
| `QB_SPONSOR_REWARD_METHODS_SET` | `publishing/RewardMethodProcesses.java:52` | `ADMIN_REWARD_METHODS`：运营商 | 否：没有成员检查，也没有封禁检查 | 加 | 同上 |
| `QB_PROSPECT_SETTINGS_SAVE` / `_DIRECTION_SAVE` / `_APPROVE` / `_DISCARD` / `_BLOCK_ADD` | `prospect/ProspectProcesses.java:251 / 308 / 382 / 461 / 500` | `PROSPECT_USE`：运营商 | 否 | 加，在 `owner(pb)` 里一处（`:198-206`，只被这五个与下两个使用，已核对） | 同上 |
| `QB_PROSPECT_MAIL_SERVER_SET` / `_REMOVE` | `prospect/ProspectProcesses.java:235 / 242`（经 `mailserver/MailServerProcesses.java:98 / 134`） | `PROSPECT_USE`：运营商 | 否（前置步骤是 `owner`） | 同上，`owner(pb)` 覆盖 | 同上 |
| `QB_REGION_EXCEPTION_ADD` | `region/RegionExceptions.java:79` | `ADMIN_REGION_WRITE`：运营商 | 否（`:93` `memberWhenScoped`） | 加 | 同上 |
| `QB_REGION_EXCEPTION_EXPIRY_SET` / `_REVOKE` | `region/RegionExceptions.java:106 / 127`（共用 `changing`，`:154-162`） | 同上 | 否 | 加，在 `changing` 一处 | 同上 |
| `QB_SHARED_VIDEO_TAKEDOWN` | `sharedvideo/SharedVideoProcesses.java:465` | `ADMIN_SHARED_VIDEO_WRITE`：运营商 | 否（`:476`） | 加 | 同上 |
| `QB_HELP_REPLY` | `help/HelpProcesses.java:432` | `ADMIN_HELP`：运营商 | 否（只有 `RequireScope`，`:447`） | 加 | 同上 |
| `QB_AI_TRANSLATION_CHECK_REQUEST` | `content/ai/AiProcesses.java:705` | `REVIEW_PUBLICATION`：运营商（启动一个计费的 AI 作业） | 否 | 加 | 同上 |
| `QB_OPERATOR_PUBLICATION_PAUSE` / `_RESUME` / `_END` | `publishing/PublicationAdmin.java:117`（共用 `define`，`operator` 为真时是运营商的） | `OPERATOR_USE` | 否：`readBan` 读的是发布的发起方的封禁（`:137-141`），不是调用者的 | 加：`operator` 为真时在 `OperatorChecks.member` 之前 | 同上 |
| `QB_OPERATOR_SPONSOR_PAGE_TAKEDOWN` | `page/SponsorPageProcesses.java:530` | `OPERATOR_USE` | 否 | 同上（`operator` 分支，`:540`） | 同上 |
| `QB_OPERATOR_PRODUCT_TAKEDOWN` / `_RESTORE` | `page/SponsorProductProcesses.java:403 / 436` | `OPERATOR_USE` | 否 | 同上（`lockAndLoad` 的 `operator` 分支，`:476`） | 同上 |
| `QB_OPERATOR_REPORT_RESOLVE` | `report/ReportProcesses.java:231` | `OPERATOR_USE` | 否 | 同上（`:246`） | 同上 |
| `QB_OPERATOR_SPONSOR_BAN` / `_UNBAN` | `identity/BanProcesses.java:100 / 256` | `OPERATOR_USE` | 否 | 同上：被封禁的成员不能封禁或解封别人 | 同上 |
| `QB_TREE_COPY` | `tree/TreeProcesses.java:282` | `TREE_EDIT`：运营商、发起方 | 否（`:289` `OperatorChecks.member`） | 加 | 同上 |
| `QB_TREE_ADOPT` | `tree/TreeProcesses.java:838` | 同上 | 否（`loadGraph` → `memberWhenScoped`，`:385-386`；对发起方不是范围内会话，同样没有检查） | 加 | 同上 |
| `QB_TREE_RETIRE` | `tree/TreeProcesses.java:1160` | 同上 | 否；结束绑定的发布（`:1200`），有后果 | 加 | 同上 |
| `QB_TREE_WITHDRAW` | `tree/TreeProcesses.java:1127` | 同上 | 否 | **豁免**："撤回自己的评审申请"，只收回、不产生新效果 | 豁免表 |
| `QB_TREE_CREATE` / `_SAVE` / `_SUBMIT` | `tree/TreeProcesses.java:199 / 493 / 918` | 同上 | 是，`refuseBanned`（`:206 / 500 / 925`） | 不改 | 泛化测试 |
| `QB_TREE_LIT_REVOKE` | `tree/TreeRevocations.java:87` | `TREE_LIT_REVOKE`：运营商 | 是，`refuseBanned`（`:98`） | 不改 | 泛化测试 |
| `QB_OPERATOR_TRANSFER_TO_DIRECT` | `operator/OperatorTransfers.java:121` | `OPERATOR_USE` | 是，`refuseBanned`（`:128`，在 `lead` 之前） | 不改 | 泛化测试 |
| `QB_OPERATOR_DISTRIBUTION_PRICE_SET`、`QB_OPERATOR_AUDIENCE_PRICES_SET` | `fee/DistributionPrices.java:100`（`common`，`:125`）、`audience/AudiencePricingProcesses.java:254`（`common`） | `OPERATOR_USE` | 是，经助手 `common`；`DistributionPrices.java:125` 的 `refuseBanned` 在 `common` 末尾，**不是第一步** | 把 `refuseBanned` 提到 `common` 最前（见 §2）；`AudiencePricingProcesses.common` 同样核对 | 泛化测试 |
| `QB_FEE_CREDIT_ONBOARDING`、`QB_OPERATOR_BILLING_SET`、`QB_OPERATOR_BANK_ACCOUNT_SAVE`、`QB_OPERATOR_WITHDRAWAL_REQUEST`、`QB_OPERATOR_WITHDRAWAL_CANCEL`、`QB_OPERATOR_MEMBER_DROP`、`QB_SPONSOR_VERIFICATION_REVOKE` | `fee/FeeCredits.java:233`、`fee/FeeProcesses.java:1051`、`operator/OperatorWithdrawals.java:228 / 283 / 368`、`operator/OperatorProcesses.java:527`、`verification/VerificationProcesses.java:572` | 运营商 | 否（现在）；**#671 加** | 不在本计划做 | `BanMoneyIT`（#671） |
| `QB_OPERATOR_TEAM` | `operator/OperatorProcesses.java:314` | `OPERATOR_USE` | 否 | **豁免**：只读（已在 #671 的豁免表） | 豁免表 |
| `QB_VIDEO_USE_*`（保存、提交、确认、驳回） | `videouse/VideoUseProcesses.java:118 / 191 / 226 / 252` | `PUBLICATION_WRITE` / `CONTENT_WRITE`：发起方 | 是，经 `common`（`:100-112`，`CallerChecks.refuseBanned`）。脚本曾提示"可能没挡"：**不对**，不加修补 | 只核对 `refuseBanned` 是否是 `common` 的第一步 | 泛化测试 |

总部专属（`OPERATORS_WRITE`、`SPOT_CHECK`、`OPERATOR_SETTLEMENT_PAY`、`FEE_MANAGE`、`ADMIN_CONTENT_WRITE` 的总部变体等）由角色推出，不进表。`QB_REVIEW_*` 的审批决定走平台 `APPROVAL_DECIDE`，见 §6。

### 3.2 发起方与学者一侧未挡住的（扫包所得）

| 流程 | file:line | 权限与角色 | 现在挡吗 | 处理 |
|---|---|---|---|---|
| `QB_EXPOSURE_REPORT` | `exposure/ExposureProcesses.java:94` | `PLAY`：学者 | 否 | 加：写曝光，影响发起方的曝光费与异常监测 |
| `QB_SETTINGS_SAVE` | `settings/SettingsProcesses.java:80` | `ACCOUNT_READ`：学者、发起方 | 否 | 豁免："本人偏好"，不影响他人（实施时若发现含对外显示的名字再改为加） |
| `QB_BLOCK_ADD` / `_REMOVE` | `block/BlockProcesses.java:78 / 104` | `PLAY`：学者 | 否 | 豁免：本人屏蔽清单 |
| `QB_INBOX_MARK` / `_READ_ALL` / `_SETTINGS` | `messaging/InboxProcesses.java:89 / 238 / 252` | `ACCOUNT_READ` | 否 | 豁免：本人收件箱的已读与偏好 |
| `QB_BROADCAST_CANCEL` / `_WITHDRAW` | `messaging/BroadcastProcesses.java:647 / 723` | `BROADCAST_WRITE`：发起方 | 否（锁账户，无检查） | 豁免：撤回自己的通知，封禁本身就这样做（`QB_BROADCAST_BAN_WITHDRAW`，`BroadcastProcesses.java:856`）；`_SAVE`、`_SEND` 经 `sponsorReads` 挡住（§2） |
| `QB_SPONSOR_PAGE_UNPUBLISH` | `page/SponsorPageProcesses.java:481` | `SPONSOR_ME` | 否 | 豁免：收回自己的页面，封禁本身会下线（`:561-590`） |
| `QB_SHARED_VIDEO_WITHDRAW` | `sharedvideo/SharedVideoProcesses.java:368` | `CONTENT_WRITE` | 否 | 豁免：收回自己的共享，封禁本身会撤回 |
| `QB_TOPUP_BANK_CANCEL` | `wallet/BankTopUps.java:278` | `TOPUP`：发起方 | 否 | 豁免，见 §5(b) |
| `QB_CERTIFICATE_SHARE_SET`、`QB_SPONSOR_CERTIFICATE_SHARE_SET` | `certification/CertificateProcesses.java:382`（共用 `shareProcess`） | `ME` / `SPONSOR_ME` | 否 | 豁免：本人证书核实页的开关；若总控认为被封禁者的证书不应继续公开，则改为加 |
| `QB_CERTIFICATE_MINE`、`SPONSOR_MINE`、`QB_REFERRAL_VIEW`、`AttemptResults`/`SponsorPlay` 的结果读取、`QB_AUDIENCE_QUOTE`、发布预付款检查（`PublicationPrepayments.java:482`） | 各自文件 | 各自 | 否 | 豁免：只读 |

以上 3.2 是静态阅读的结果；**泛化测试首次运行给出的清单是准的**，S3 以它为准修正本表（有出入的在 PR 描述里逐条说明）。表中其余发起方与学者的写入流程我核对到的都已挡住（直接 `refuseBanned`，或经 §2 的助手）。

## 4. 修补

每条先写测试，**修补前必须失败**：被封禁的成员持尚未到期的令牌调用该流程，期望 422 `QB_USER_BANNED`（`AccountCodes.USER_BANNED`，`CallerChecks.java:58`），且什么也没写。

- 新增 `it/BanOperatorActsIT`（仿 #671 的 `BanMoneyIT.aBannedLeadMovesNoMoney`：`newOperatorWithLead`、`memberSession(...)`、总部 `QB_USER_BAN`、`refused(...)`、`codes(...)`，解封后同一调用成功）。一个测试方法一组：奖品作废与电子奖品裁决、奖励方式、潜在客户的七个、区域例外三个、共享视频下架、帮助回复、AI 翻译检查、运营商的发布 / 页面 / 商品 / 举报 / 封禁各流程、树的三个。每个"拒绝后什么也没写"都用数据库断言。
- `BanProcesses.operatorBan` / `operatorUnban`、`PublicationAdmin.define`、`SponsorPageProcesses`、`SponsorProductProcesses`、`ReportProcesses` 等共用定义，**只在 `operator` 为真时**加（总部变体不加，也不能被封禁）。
- 位置：在 `OperatorChecks.member` / `memberWhenScoped` **之前**，如 `TreeRevocations.java:98-99`。
- 不采用"在 `OperatorChecks.member` 里统一加"：它漏掉不调用 `member` 的（潜在客户、帮助回复、奖励方式、AI 检查、成员移除），对发起方的树流程无效（`memberWhenScoped` 在非运营商会话是空操作），并且会让已有 `refuseBanned` 的流程读两次封禁。
- 泛化后的 `BanCoverageTest` 在所有修补之后必须通过；`CLOSED` 集合并入本计划新增的流程名。

## 5. 两点评估

### (a) `QbTopUp.bankReference` 是否登记为抹除对象

**建议：不登记。**理由：
1. 前提需更正：该列不是 NOT NULL，是 `varchar(64)` 可空（`V20__quizbuks_bank_topups.sql:15`）。`ErasureValues.replacement` 对可空列写 NULL，不是 `***`（`runtime/erasure/ErasureValues.java`，可空即 `NULL`）。问题出在检查约束：V20（`:29`、`:37`）与 V23（`:30` 的 `qb_topup_manual_kind`、`:38` 的 `qb_topup_paid_posted`、`:46` 的 `qb_topup_review`）要求已入账、审核中的银行与手工入账（外部收款）的 `bank_reference IS NOT NULL`。清空会违反约束，抹除整个失败——这与 V120 是同一类问题（V120：V23 的 `qb_topup_method` 要求手工入账的 `manual_reason` 非空，抹除把它置 NULL 而失败，V120 放宽了该约束，见 `FM15a0` 分支的 `V120__quizbuks_topup_manual_reason_erasable.sql`）。
2. 即使放宽约束，唯一索引 `uk_qb_topup_bank_reference (received_on, bank_reference)`（`V20:45`）是"银行流水的一笔只记一次"的数据库保障，清空后这一保障对已抹除的行消失，旧流水可再被记到另一笔充值上（重复入账的风险）。写 `***` 也不行：同一天两笔被抹除的行会撞唯一索引。
3. 审批内容的哈希：`bankReference` 在银行入账审批的内容里（`wallet/BankTopUps.java:467`，手工入账 `ManualCredits.java:263`），批准绑定内容哈希。登记抹除后，处于审核中的充值（状态 `REVIEW`）的行被改，哈希不再相符，待批准的审批作废。这一条另外要求：即使将来要登记，也必须像 F-M15 a0 对手工入账那样，先结束或等待在途的审核（计划 §5.2 第 0 步）。
4. 它是银行流水的参考号（`BankTransfers.reference` 折叠成大写去空格，`BankTransfers.java:33-36`，由总部财务录入，不是发起方输入），付款人的名字在 `payerName`，已由 #671 登记（`SponsorErasure.java:152`）。它与对账、账本一起保存十年。
5. 读取：批准、对账之后只有显示（`queries/qb/admin/topups.sql:86`、`bank-topups.sql:48`、`manual-credits.sql:43`）和"是否已被用过"的查重（`BankTopUps.java:354`、`ManualCredits.java:141`）读它；查重对 NULL 不命中，对 `***` 不命中真实参考号，所以抹除不会让对账出错——但会失去上面第 2 点的保障。
若总控仍要登记：先新迁移放宽三个约束（对已抹除的行）、解决唯一索引，并先处理在途审核；这要 +1 天，且是钱的部分，不并入本工作。

### (b) `QB_TOPUP_BANK_CANCEL`

**无害，列入豁免表，理由"撤回自己的、尚无资金的申请"。**`wallet/BankTopUps.java:278-297`：只改自己的一笔，`loadOwn` 经发起方自己的数据视图（别人的 404，`:304-309`），`waiting` 要求方式为银行且状态是 `AWAITING` 或 `REPORTED`（`:314-320`，`TopUpStatus.BANK_WAITING`），只把状态写成 `CANCELLED`、关掉财务的任务；不过账、不动余额。总部之后仍可对 `CANCELLED` 记账（`BANK_RECORDABLE` 含 `CANCELLED`，`TopUpStatus.java:32`），所以不会丢钱。不加检查，也不会让被封禁者做出任何新的事。

## 6. 风险

- **`APPROVAL_DECIDE` 不能带应用层的封禁检查。**运营商持有 `approval.decide`（`QbRoles.java:111`）与 `REVIEW_SPONSOR` / `REVIEW_PUBLICATION`，被封禁的成员持有效令牌仍可批准或驳回发起方入驻与发布的评审。缓解：(1) 窗口有限，是访问令牌的剩余寿命，缺省 15 分钟（`runtime/security/SecurityConfig.java:131`，`jabiz.security.jwt.access-token-ttl`，`SessionChecks.java:36` 对过长告警；`application.yml` 未改）；(2) 评审是两人的（运营商另一位成员，`CLAUDE.md:292`），单个被封禁者批不通过，只能多一次驳回；(3) 运营商入口每次动作要二次验证（`QbRoles.java` 的运营商 `requireMfa = true`，且封禁后无法重新验证）。仍留的风险写进 `operations.md`；真正的修补需要平台的"决定者检查"钩子，作为平台请求提给总控，不在本工作里做。（另一种应用侧办法——在结果流程 `*_APPROVAL_RESULT` 里再核对决定者是否被封禁——会改变审批结果的含义，不建议在这里做。）
- **封禁不吊销会话。**`QB_USER_BAN` 不调用 `SEC_USER_SESSIONS_REVOKE`（`BanProcesses` 全文无此调用）；刷新令牌在下一次刷新时被 `QbSignInGuard` 拒绝（`:33-35`），所以刷新令牌（运营商入口会话另配，`application.yml:57`）不是缺口；吊销会话也缩短不了已签发的访问令牌（`docs/design/10-security.md:788`）。结论：窗口由访问令牌寿命决定，本工作不改；把令牌寿命单独缩短（平台全局设置）是平台请求，不在这里。
- **新增流程忘加检查**：由泛化后的测试防住，但"只读"与"本人偏好"这类豁免要靠评审把关；每条豁免要有理由。
- **静态清点会漏**：条件装配或测试里注册的流程，见 §2 第 3 点。
- 另见（不属于封禁，顺带记下）：`RewardMethodProcesses.java:52-73` 不调用 `OperatorChecks.member`，运营商成员被移除或运营商被暂停后，令牌到期前仍可调用；范围由数据视图（`OperatorScopes.java:59`）限制。建议另开一条跟进。
- 与 #671 的冲突：同改 `BanCoverageTest` 与 `backend/quizbuks/CLAUDE.md:232` 附近；本工作等 #671 合并后在其上做。

## 7. 测试、估算、依赖

- 测试：泛化的 `BanCoverageTest`（含 `getFirst` 规则、`BLOCKED_BY_HELPER`、`EXEMPT`、由 `QbRoles` 推出的总部豁免）；`BanOperatorActsIT`（§4）；学者 `QB_EXPOSURE_REPORT` 一条；助手中 `refuseBanned` 提前后原有 IT 全绿；场景快照若有流程步骤顺序变化则更新（`-Dscenario.update-snapshots=true`，逐个核对差异）。
- 文档：`backend/quizbuks/CLAUDE.md:232` 附近改写成"由角色推出范围、第一步规则、豁免表与理由"；`docs/quizbuks/operations.md` 的封禁一节写明令牌窗口与 `approval.decide` 的残留风险。
- 估算：1.5 天（测试泛化 0.5 天，修补约 30 个定义多为一行、IT 0.7 天，文档与回归 0.3 天）。
- 依赖：#671 合并（`BanCoverageTest` 来自它）。不是钱的部分，由 S3 实施。

删除的功能：无
