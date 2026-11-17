# F-SOLO 单人模式：一个人完成初始化与日常运营：实施计划（S1，2026-11-17，待 Fable 审查与总控批准）

状态：计划，未写代码。分支 `1.3/quizbuks-init-plan`（只改 `docs/**`；前一稿 F-INIT 已由需求方的决定作废，本文件取代它）。

## 需求方的决定（总控 2026-11-17 转述）

- 首发期只有需求方一人。他用**一个账号**完成全部初始化与日常运营；最多再加一个运营商负责人账号。
- 以后有了第二个人，能切回两人把关。

本计划给出：
- 第 1 节：盘点平台与 QuizBuks 中所有"必须两个不同的人"的地方，逐条写在哪、怎么拦、单人模式下怎么处理；
- 第 2 节：按改动的代价分类；
- 第 3 节：一个统一的开关；
- 第 4 节：风险与缓解；
- 第 5 节：需求方的单人初始化步骤；
- 第 6 节：新决策的草稿；
- 第 7 节：测试清单；
- 第 9 节：两处小修照旧。

依据：
- 代码 `origin/1.3/quizbuks`（`97ebabf95`）：
  - 平台：`ProtectedGrants*`、`ControlChanges`、`ApprovalProcesses`、`RequireApproval`、`SodWriteCheck`、`SodGuard`、`ApproveReenrollment`、`AuditRecorder`；
  - QuizBuks：`QbApprovalRules`、`QbSodRules`、`QbRoles`、`QbParams`、`OperatorHealth`、`BankWithdrawals`、`TreeRevocations` 等；
  - 脚本 `tools/quizbuks/ops/launch-day-check.sh`。
- 决策 D23、D40、D48、D51；设计 `10-security.md` §16、`18-numbering-approvals-tasks.md` §3–§4；`operations.md` §3。

**结论先说**：

- **受保护的授权**（D48、D51）**不必改**。"建立期"在首个管理员之外出现**两位**管理员时才结束（`ProtectedGrants` :345-361），一人在职时它永远不结束：
  - 唯一的超级管理员给自己或负责人账号分配任何角色都当即生效；
  - 首个管理员在这位出现时退役，这正是我们要的；
  - 有了第二位超级管理员，建立期自动结束，受保护授权的四眼自动恢复。
- **三条职责分离规则**只在**已发布**后生效（`SodService` :111）。单人模式下不发布它们（第 3.3 节），一人即可同时持有超级、财务、内容三个角色。这只是数据，不改代码。
- **真正挡住一个人的是两道写死的检查，都在平台，没有任何配置能放宽**：
  - 审批"准备人不能审批"：`APPROVAL_OWN_REQUEST`，`ApprovalProcesses` :149-152；
  - 受控变更"提出人不能发布"：`CONTROL_SAME_PERSON`，`ControlChanges` :179-183。
- 本计划只在平台上改这两处（加 `ApproveReenrollment` 的同类检查，见 1.1 的 P5），**规则是"开关打开、并且没有别人能决定时，才允许本人决定"**：
  - 有了第二个能决定的人，四眼当场自动恢复，不必记得关开关；
  - QuizBuks 再改几处小地方（第 2 节 B 类）。
- **钱出去或凭空记入余额的审批不放宽**：转账、提现、手工入账、银行充值确认、运营商提现。它们首发时本来就关着或用不到，以后打开它们之前必须先有第二个人（第 3.4 节）。

## 1. 盘点："必须两个不同的人"的每一处

### 1.1 平台

| # | 在哪（文件:行） | 现在怎么拦（码） | 现有配置能放宽吗 | 单人模式下 |
|---|---|---|---|---|
| P1 | 受保护的授权：`ProtectedGrantWriteCheck` :50-90；`ProtectedGrants.assess` :168-180、`armed()` :345-361、`retiredBootstrap()` :374-391；`ProtectedChangeProcesses` :164-207 | 建立期后要另一位管理员审批（422 `PROTECTED_CHANGE_REQUIRES_APPROVAL`、`APPROVAL_TARGET_USER`、`TARGET_CREDENTIALS_NOT_OWN`）；首个管理员在有别的管理员后 403 `BOOTSTRAP_ADMIN_RETIRED` | `jabiz.security.protected-grants.enabled=false` 关闭全部（`ProtectedGrantsOff`） | **不改，也不关**。一位超级管理员时建立期不会结束，授权当即生效；首个管理员退役。**不建议**设 `enabled=false`：它连同"第二位出现后自动恢复四眼"一起关掉，上线清单 2.3 也要求它不设 |
| P2 | 受控变更的发布：`ControlChanges` :179-183 | 422 `CONTROL_SAME_PERSON`（提出人不能发布自己的），无条件，`*` 也不能 | 无 | **平台改 C1**：开关打开、并且调用者是唯一持有 `control.publish` 的人时，允许发布自己的提案，并留记录（第 3.2 节） |
| P3 | 业务审批：`ApprovalProcesses` :149-152 | 422 `APPROVAL_OWN_REQUEST`（准备人不能审批），无条件；审批规则与层级（`ApprovalLevel` 只有 `permission`、`limitFact`）没有"准备人可审"的选项 | 无（停用规则会使 QuizBuks 的流程拒绝，见 Q1） | **平台改 C2**：开关打开、并且在该审批的范围内没有别人持有该层级的权限时，准备人可以决定，留记录；应用声明为"始终四眼"的审批对象除外（第 3.4 节） |
| P4 | 职责分离：`SodWriteCheck` :46-110（422 `SOD_CONFLICT`）、`SodGuard` :26-44（403） | 已发布的规则才生效；停用规则本身是受控变更 | 不发布即不生效 | **数据**：单人模式下 QuizBuks 的三条规则保持"待发布"（第 3.3 节），不改代码 |
| P5 | 全员重新绑定二次验证的批准：`ApproveReenrollment` :54-58 | 自己的请求不能批准 | 无 | **平台改 C3**：同 C2 的条件（开关 + 唯一持有人）。这是密钥与库一同泄露时的应急流程，一人时没有别人能批，不改即无法执行 |
| P6 | 审计 | `sys_audit_record` 记操作人与操作的原因（`AuditRecorder` :61-95）；审批的决定记审批人与可选的理由；受控变更记 `proposedBy` / `publishedBy` | — | 自决的记录见第 3.2 节 |

### 1.2 QuizBuks

| # | 在哪 | 现在怎么拦 | 单人模式下 |
|---|---|---|---|
| Q1 | 审批规则 `QbApprovalRules` :41-174（16 条），由 `QB_SETUP` 提出；没有适用规则时流程**拒绝**，如 `QB_REVIEW_NOT_CONFIGURED`、`QB_*_NO_REVIEW`（`SponsorProcesses` :302 等，盘点见附录 A） | 规则发布前一律拒绝；发布后由持有层级权限的另一个人批 | 规则**照常发布**（需求方本人发布，P2）；审批由 C2 允许本人决定。不停用规则：停用会使这些流程拒绝，而不是放行 |
| Q2 | 跳过审核的三个受控参数 `qb.review.sponsor.auto`、`qb.review.publication.auto`、`qb.transfer.review-above`（`QbParams` :778-781） | 改它们本身是受控变更 | 首发值不变：入驻自动；发布照常审核，需求方以内容管理员的身份自己审，C2；转账关着 |
| Q3 | 运营商的直营发起方：负责人准备的发布要同一运营商的**另一位**成员审（`DirectSponsors` :11-18，`APPROVAL_OWN_REQUEST`） | 只有负责人一人时没人能审 | C2 覆盖：开关打开、该运营商没有别的成员持 `qb.review.publication` 时，负责人可以审自己的发布，留记录 |
| Q4 | 每日核对 `qb.operator-check`（`OperatorHealth`，SQL `operator-health.sql`）：`FEW_REVIEWERS`（能审核的成员 `< 2`，写死） | 每天给平台一个待办 | **QuizBuks 改 B1**：单人模式下这一项改报"单人模式仍开启"的提醒（同一个待办，换说明），不再要求第二位成员；关掉开关即恢复原样 |
| Q5 | 提现的"批准的人不能付款"`QB_WITHDRAWAL_APPROVER`（`BankWithdrawals` :497-500）；撤销点亮的"申请人不能是自己"`QB_TREE_REVOKE_OWN`（`TreeRevocations` :167-171） | 第二个人 | 提现：**不放宽**（钱出去，第 3.4 节；首发 `qb.withdrawal.bank.enabled=false`）。撤销点亮：**QuizBuks 改 B2**，同 C2 的条件下放行，留记录 |
| Q6 | 对自己账户的操作：`QB_WITHDRAWAL_OWN`、`QB_TOPUP_BANK_OWN`、`QB_TOPUP_MANUAL_OWN`、`QB_FEE_OWN`、`QB_TRANSFER_NOW_OWN`、`QB_TRANSFER_RELEASE_OWN`、`QB_BAN_SELF` | 自己给自己的钱或账号做操作 | **不改**：这是利益冲突，不是"第二个人"，单人也不应给自己的发起方账户入账 |
| Q7 | 职责分离规则 `QbSodRules`：`qb.sod.control-changes`（`control.propose` ↔ `control.publish`）、`qb.sod.security-administration`（改权限 ↔ `control.publish`、`approval.decide`）、`qb.sod.mail-texts`（改权限 ↔ `mail.text.write`） | 发布后一人不能兼任 | 保持待发布（P4，第 3.3 节）；切回两人时先发布 |
| Q8 | 银行设置的批量发布 `QB_BANK_SETTINGS_PUBLISH`（`BankSettings` :163-168：只发别人的提案） | 第二个人 | 不改（银行收款首发不用；用时属钱的设置，第 3.4 节） |
| Q9 | 邮件文字与信纸：带令牌或登录链接的、`protectedText()` 的、信纸，都是受控变更（`MailTextControlTarget`、`MailLayoutControlTarget`） | 同 P2 | C1 覆盖：本人提出、本人发布，留记录 |
| Q10 | 不需要两个人的：法律文本发布、公告、显示明文、退款、封禁、问答、帮助、抽查 | — | 不变 |

### 1.3 运维的检查与文档

| # | 在哪 | 现在 | 单人模式下 |
|---|---|---|---|
| O1 | `tools/quizbuks/ops/launch-day-check.sh` :171-175（至少两位超级管理员，否则 FAIL）、:177-181（建立期已结束，否则 FAIL）、:86-91（待发布的受控变更 WARN） | 一人即 FAIL | **B3**：`.env` 中单人模式打开时，这两项改为 `WARN | 单人模式`（写明到期日）；三条职责分离规则待发布是预期的，不再计入 WARN |
| O2 | `operations.md` §3 第 4–5 步、§3 末"受保护的授权"；`launch-checklist.md` 2.3（两位超级管理员、建立期已结束、`JABIZ_SECURITY_PROTECTEDGRANTS_ENABLED` 不设） | 要求两人 | 新增 §3a"单人模式"；清单 2.3 加"单人模式（需求方书面决定）"的替代条件，写进"带着未完成项上线的决定" |
| O3 | 启动检查 `SetupParamsCheck`、`FirstLaunchSwitchesCheck` | 有提案未发布时警告 | 不变（单人模式下需求方自己发布，警告即消失） |

### 1.4 测试中断言"两个人"的地方（开关缺省关闭，全部保持不变）

- 平台：`ApprovalIT`、`SodIT`、`ControlledParamIT`、`MailTextIT`、`MailLayoutIT`、`MfaReenrollmentIT`、`ProtectedGrant*IT`（9 个）、`BootstrapAdminIT`、`InvitationIT`。
- QuizBuks：`QbRolesTest`（:117、:136、:153 断言**角色**层面的分离；单人模式不改角色，只让一个人持有几个角色，所以不受影响）、`OperatorsEverywhereIT` :139、:358、`PublicationOwnerIT` :107-113、`KnowledgeMapIT` :135、`SponsorOnboardingIT` :291、`BankWithdrawalIT`、`ManualCreditIT`、`TransferIT`、`SetupIT` 等。
- `launch-day-check.test.sh` :49。

单人模式的新测试另列（第 7 节），现有测试一律不改。

## 2. 分类

| 类 | 内容 | 改动 |
|---|---|---|
| **A 只需配置或数据** | P1 受保护授权：不改，一人时建立期不结束；P4 / Q7 职责分离：三条规则不发布；Q2 跳过审核的参数：用首发值；Q6、Q10 不变 | 无代码 |
| **B QuizBuks 改** | B1 每日核对的 `FEW_REVIEWERS` 在单人模式下换成提醒；B2 撤销点亮的 `QB_TREE_REVOKE_OWN` 同 C2 的条件放行；B3 `launch-day-check.sh` 识别单人模式；B4 "始终四眼"的审批对象与受控参数的声明（第 3.4 节）；文档 | 小 |
| **C 平台改**（`1.3/platform`，新决策 D82） | C1 受控变更：唯一的发布人可以发布自己的提案；C2 业务审批：范围内唯一的审批人可以决定自己准备的请求；C3 全员重新绑定二次验证的批准，同 C2；开关本身、启动时的提示与记录（第 3 节） | 中，集中在 `ControlChanges`、`ApprovalProcesses`、`ApproveReenrollment` 三处与一个新的判断 |

不做的 C（尽量少动平台）：
- 不改受保护的授权（D48、D51 原样）；
- 不改职责分离引擎；
- 不给审批规则加"准备人可审"的字段（那会在多人时也生效）；
- 不改 `*` 的语义。

**零代码的备选（A0）**：需求方本人用**两个**管理员账号（两个邮箱、各自二次验证）：
- 一个持超级与内容管理员（提出、审核），一个持财务管理员（发布），再加负责人账号；
- 平台的检查看到的是两个账号，全部照常通过，今天就能做。
- 代价：他要在两个账号间切换，四眼只是形式；上线清单仍要求写进"带着未完成项上线的决定"。
- 若审查认为 C 类不宜在首发前做，A0 是退路。

## 3. 统一的开关

### 3.1 开关与条件

**部署配置**（平台）`jabiz.security.single-person.*`：

| 键 | 缺省 | 含义 |
|---|---|---|
| `enabled` | `false` | 打开单人模式 |
| `until` | 无 | 到期日（ISO 日期）。`enabled=true` 时必填（缺少即启动错误 `SECURITY`），且不得晚于启动日起 `max-days` 天 |
| `max-days` | `180` | `until` 的上限 |
| `reason` | 无 | 理由。`enabled=true` 时必填，写进启动记录 |

环境变量为 `JABIZ_SECURITY_SINGLE_PERSON_ENABLED`、`…_UNTIL`、`…_REASON`。

为什么是部署配置而不是受控参数：
- 受控参数要另一个人发布，一人时打不开（先有鸡还是先有蛋）；
- 打开它是运维与需求方的书面决定，应该在 `.env` 与变更记录里，而不是应用里；
- 应用中的人（包括盗用了账号的人）不能自己打开或延长它。

**放行的条件**（C1、C2、C3、B2 共用一个平台的判断 `SinglePerson.mayDecideOwn(调用者, 权限, 范围)`）。下面四条**全部**成立才放行，否则照旧拒绝，返回原来的码：

1. 开关打开，且今天不晚于 `until`；
2. 调用者是这一决定所需权限（`control.publish`；审批层级的权限；`security.protected-change.decide` 等）的持有人；
3. **除调用者外，没有别的启用用户持有它**：
   - 经启用的角色计，范围内计（入口范围的审批，如运营商，只数同一范围中的人，用平台给待办选人的同一查询）；
   - 首个管理员（`BootstrapAdmin` 建立的用户）不计，因为它在单人模式下已退役、应已从环境中删去；
4. 调用者有近期的二次验证（管理级）。不论这个决定平时是否要求，自决一律要求。

条件 3 让开关"自动失效"：有了第二个能决定的人，即便开关忘了关，自决也立即不再可能。开关只是"允许"，条件才决定。

**到期**：
- 过了 `until`，条件 1 不成立，自决停止。审批与发布照常可以由别人做；一人时只能等运维续期。
- 启动不失败，以免锁住系统：日志 WARN，每日给超级管理员一个"单人模式已到期"的待办。
- 续期要运维改 `.env` 并写理由，是一次有意识的决定。

### 3.2 留下的记录（审计里标明"单人模式下自决"）

- **业务审批的决定**（C2）：
  - 平台把决定的理由写成固定前缀 `[单人模式自决]` 加上用户填的理由（不能省略、不能改写）；
  - 同时在审计记录（`sys_audit_record`）中这次操作带原因 `SINGLE_PERSON_SELF_DECISION`。今天流程的操作 reason 为空，需要平台加一个由流程设置操作原因的入口，只给平台的这几处用；
  - **不新建表、不加列**。
- **受控变更的发布**（C1）：`SysControlChange` 的 `proposedBy == publishedBy` 本身就是标记；操作原因同上。
- **全员重新绑定二次验证**（C3）：同 C2。
- **开关本身**：每次启动时若打开，记一条平台操作 `SEC_SINGLE_PERSON_ON`（带 `until` 与 `reason`），做法同 `ProtectedGrantsOff` :50-67 记 `SEC_PROTECTED_GRANTS_OFF`；日志 WARN。
- **通知**：每次自决后给调用者账号的邮箱寄一封通知（"你以单人模式自己批准了 …"），走平台的通知（D23 第 4 条）。账号被盗而邮箱未被盗时，本人能马上发现。
- **可查**：平台管理系统"审计"按原因 `SINGLE_PERSON_SELF_DECISION` 筛选。季度访问审查（`ACCESS_REVIEW_SIGN_OFF`）的报告加一行"单人模式下的自决次数"。

### 3.3 职责分离规则在单人模式下

- `QB_SETUP` 照常提出三条规则，但单人模式下**不发布**，它们留在"待发布"。只有已发布的规则才生效，所以一人可以同时持有超级、财务、内容三个角色。
- 不改 `QB_SETUP`，不撤回提案：提案留着，切回两人时直接发布。
- 不建议让 `QB_SETUP` 在单人模式下不提这三条规则：那样切回时要另想办法提出。

### 3.4 始终四眼的东西（单人模式不放宽）

应用可以声明某些审批对象与受控参数**始终四眼**。C2 与 C1 对它们不放行，条件 3 成立也不行：
- 平台：`ApprovalSubject` 与 `ControlledParams` 各加一个声明 `.alwaysFourEyes()`；
- QuizBuks：B4 声明。

QuizBuks 推荐声明为始终四眼的（请 Fable 与需求方确认）：

| 类别 | 审批对象 / 参数 | 理由 |
|---|---|---|
| 钱出去 | 转账（`qb.transfer.review`）、银行提现（`BankWithdrawals`）、运营商提现（`qb.operator-withdrawal.review`） | 不可撤回。首发 `qb.transfer.enabled`、`qb.withdrawal.bank.enabled` 为 `false`，运营商提现首发用不到 |
| 凭空记入余额 | 手工入账（`qb.topup-manual.review`）、银行充值的确认（`qb.topup-bank.review`） | 一人可以给任何发起方记入现金。首发充值走 Stripe，用不到 |
| 打开钱的开关 | 受控参数 `qb.transfer.enabled`、`qb.withdrawal.bank.enabled`、`qb.kudos.buy.enabled`、`qb.kudos.sell.enabled`、`qb.reward.kudos.enabled`、银行收款的设置 | 要打开它们，先有第二个人 |

其余放行：入驻、发布、发起方主页、知识结构图（含平台图、复制）、撤销点亮、共享视频、企业实名认证的审核；费用请求（作废、贷项凭证，`qb.fee-request.review`）；运营商的分成方案与结算（`qb.operator-plan.review`、`qb.operator-settlement.review`）。后两类涉及钱但不出平台，放行与否请审查定。

### 3.5 关掉（切回两人把关）

前提与步骤（写进 `operations.md` §3a）：

1. 新人用邀请建号、接受邀请、绑二次验证（今天的流程）。
2. 需求方把**财务管理员**的角色给新人（仍在建立期，当即生效），然后**收回自己的财务管理员角色**。此后需求方只持超级与内容管理员的角色，新人持财务管理员的角色。
3. 由新人（财务管理员）发布三条职责分离规则（需求方提出的，提出人与发布人不同）。
4. 需求方建第二位超级管理员（可以是新人之外的第三人；只有两人时，按 `operations.md` §3 的要求另找一人，或在决策中写明）。建立期随即结束，受保护授权的四眼恢复。
5. 运维从 `.env` 删去 `JABIZ_SECURITY_SINGLE_PERSON_*`，重启。
   - 即便晚删，第 3.1 节的条件 3 也已使自决不再可能：新人持有 `control.publish`，需求方不再是唯一的持有人。
   - 运营商的审核同理：第二位成员加入后，负责人不再能审自己的发布。

`launch-day-check.sh` 在开关关掉后照旧要求两位超级管理员与建立期已结束（B3 只在开关打开时放宽）。

## 4. 风险与缓解

| 风险 | 后果 | 缓解 |
|---|---|---|
| **一人可做任何事**（除第 3.4 节） | 内容、规则、参数没有第二双眼；错误没有人在发布前看到 | 需求方书面决定（上线清单"带着未完成项上线的决定"）；到期日 `until` 至多 180 天；每次自决寄通知；审计可按原因筛选；每日核对的提醒（B1）；关掉的步骤（第 3.5 节） |
| **账号被盗** | 盗用者可以发布受控变更、批准审核、给自己的账号授任何角色（建立期内） | 管理员角色要求二次验证（已有，`SecRole.requireMfa`）；**推荐通行密钥**（平台 D71，用户验证的通行密钥算两个因素）；自决一律要求近期二次验证（第 3.1 节条件 4）；每次自决寄通知到账号的邮箱；钱出去与记入余额始终四眼（第 3.4 节），盗用者拿不走钱；审计与登录记录照常 |
| **开关被别人打开** | 多人系统被降级 | 只能改 `.env` 并重启（运维权限）；每次启动记 `SEC_SINGLE_PERSON_ON` 并 WARN；条件 3 让它在多人时无效 |
| **到期锁住** | 一人时过了 `until` 就没人能批 | 启动不失败；到期前 14 天起每日给待办提醒续期；续期是运维改 `.env` |
| **建立期内一人给自己任何角色** | 这本来就是平台的建立期规则（D48），单人模式没有扩大它 | 不变；第 3.5 节的第 2–4 步结束它 |
| **平台改动影响其他应用** | C1–C3 改的是平台的共同检查 | 开关缺省关闭，条件 3 在多人时永不成立；现有测试全部不改即通过（第 1.4 节） |

## 5. 需求方的初始化（单人模式），一页

（实施时写进 `operations.md` §3a。下文是草稿。）

> **准备**：运维在 `.env` 中设：
> - `JABIZ_MAIL_ENABLED=true`
> - `JABIZ_SECURITY_SINGLE_PERSON_ENABLED=true`
> - `JABIZ_SECURITY_SINGLE_PERSON_UNTIL=<至多 180 天后的日期>`
> - `JABIZ_SECURITY_SINGLE_PERSON_REASON=首发期只有一人（需求方 YYYY-MM-DD 书面决定）`
>
> 以及首个管理员的两行，然后启动。
>
> 1. **首个管理员**：登录 `/admin/`，绑二次验证，执行 `QB_SETUP`。在"用户"中为**需求方本人**建账号（填邮箱、勾选 `invite`、不填密码），给他分配 `QB_ADMIN_SUPER`（当即生效；首个管理员随即退役）。运维删去首个管理员的两行。
> 2. **需求方**：点邀请邮件设密码，登录，绑二次验证（**推荐再登记一个通行密钥**）。在"用户与角色"中给自己再加 `QB_ADMIN_FINANCE` 与 `QB_ADMIN_CONTENT`（建立期内当即生效），**重新登录**。
> 3. **需求方**：在"数据 → SysControlChange"发布 `QB_SETUP` 的提案，顺序同 `operations.md` §3 第 5 步：
>    - **跳过三条职责分离规则**（留待切回两人时发布）；
>    - 跳过"首次上线的开关"中要等前提的那几条；
>    - 提出并发布开具方、科目映射等（§3 第 6 步）。
>
>    每次发布寄一封"单人模式自决"的通知到自己的邮箱。
> 4. **需求方**：在"用户"中为**负责人账号**（他的第二个邮箱）建号（`invite`），本人接受邀请；在"运营商"新建默认运营商、选这个负责人；给负责人分配 `QB_OPERATOR`（当即生效）；负责人账号登录发起方页面点"加入"。
> 5. 日常：发起方的入驻自动；发布的审核由需求方在平台管理系统或运营商管理系统中自己批（单人模式自决，寄通知）；每日核对给一条"单人模式仍开启"的提醒。
>
> 第 3 步若嫌逐行发布太多，可选的小功能 B5（待决定 4）：`QB_SETUP_PUBLISH` 在单人模式下按 §3 第 5 步的顺序一次发布 `QB_SETUP` 待发布的提案，跳过三条职责分离规则与"以后发布"的开关。它逐条调用 `CONTROL_CHANGE_PUBLISH`，先例是 `QB_BANK_SETTINGS_PUBLISH`。

与今天相比：
- 不需要第二、第三位管理员；
- 不需要等别人接受邀请、发布、审批；
- 账号从至少 5 个（首个管理员之外：财务、内容、两位超级管理员、负责人与第二位成员）减为 2 个（需求方、负责人）。

## 6. 新决策的草稿

### D82（平台，`docs/design/09-decisions.md`）单人模式：唯一可决定者可以决定自己的请求

**背景**：
- 一些应用的首发期只有一个人。审批（D23 第 2 条"审批人不能是准备人"）与受控变更（D23 第 2 条、D40"由提出人以外的人发布"）在一人时无人能决定，只能用第二个账号走形式。
- 受保护的授权（D48、D51）在一人时处于建立期，不受影响。

本条**修改 D23 第 2 条与 D40 的"四眼"**：在下面的条件下允许本人决定。不修改 D48、D51，不改职责分离（D23 第 3 条）。

**决策**：
1. 部署配置 `jabiz.security.single-person.enabled`（缺省 `false`），必填 `until`（不晚于启动日起 `max-days`，缺省 180 天）与 `reason`，否则启动错误。
   - 打开时每次启动记平台操作 `SEC_SINGLE_PERSON_ON` 并告警；
   - 过了 `until` 不再放行，启动不失败，每日给待办。
2. **放行的条件**（全部成立）：
   - 开关打开且未到期；
   - 调用者持有该决定所需的权限；
   - 除调用者外没有别的启用用户持有它（经启用的角色计，入口范围的审批只数同一范围中的人，首个管理员不计）；
   - 调用者有近期的管理级二次验证。
3. **适用于**：
   - `CONTROL_CHANGE_PUBLISH`（发布自己的提案）；
   - `APPROVAL_DECIDE`（决定自己准备的请求，受保护变更的审批除外：它仍按 D48、D51，申请人与对象不能批）；
   - `SEC_MFA_REENROLL_ALL_APPROVE`；
   - 应用可以调用同一判断（QuizBuks 的 `QB_TREE_REVOKE_OWN`）。
4. 应用以 `ApprovalSubject…alwaysFourEyes()`、`ControlledParams…alwaysFourEyes()` 声明不放行的对象。
5. **记录**：
   - 自决的审批理由带平台加的固定前缀；
   - 这次操作在审计中的原因为 `SINGLE_PERSON_SELF_DECISION`；
   - 每次自决给调用者寄通知；
   - 访问审查的报告列出自决次数。

**理由**：
- 用"唯一可决定者"而不是"开关"本身来放行：有了第二个人，四眼当场恢复，不靠记得关开关。
- 用部署配置而不是业务参数：应用里的人（包括盗用了账号的人）不能自己打开或延长它。
- 不放宽受保护的授权：一人时它本来就在建立期；它是所有其他四眼的根（D48 的理由）。

**放弃的方案**：
- ① `jabiz.security.protected-grants.enabled=false`：关掉的是授权的四眼，挡人的两道检查仍在，而且关掉后有了第二个人也不恢复。
- ② 给审批规则加"准备人可审"：多人时也生效。
- ③ 停用审批规则：QuizBuks 的流程按"无规则即拒绝"设计。
- ④ 第二个账号走形式（保留为零代码的备选，见 F-SOLO §2 A0）。

### QuizBuks 的说明（`docs/quizbuks/` 的决策记录，与 D82 同时）

- 单人模式下三条职责分离规则不发布；第 3.4 节的对象与参数始终四眼；
- 每日核对的 `FEW_REVIEWERS` 在单人模式下改为提醒；
- `launch-day-check.sh` 识别单人模式；
- 上线清单"带着未完成项上线的决定"写入需求方的书面决定与到期日。

## 7. 测试清单

**平台（`1.3/platform`，app 模块的集成测试）**
- `SinglePersonIT`：
  - 开关关闭：`APPROVAL_OWN_REQUEST`、`CONTROL_SAME_PERSON` 与今天相同（现有 `ApprovalIT`、`ControlledParamIT` 不改即通过）。
  - 开关打开、调用者是唯一持有人：
    - 发布自己的提案成功，`proposedBy == publishedBy`，审计原因为 `SINGLE_PERSON_SELF_DECISION`，寄出通知；
    - 决定自己准备的请求成功，理由带前缀。
  - 开关打开、另有一位启用用户持有该权限 → 照旧拒绝（原码）。那位被停用后 → 放行。
  - 入口范围的审批：同一范围有第二位持有人 → 拒绝；只在别的范围有 → 放行。
  - 首个管理员持 `*` 不计入"别人"。
  - 没有近期二次验证 → 403（即便平时不要求）。
  - `alwaysFourEyes()` 的对象与参数 → 照旧拒绝。
  - 受保护变更的审批 → 照旧（申请人不能批）。
  - `until` 已过 → 拒绝，启动不失败、记告警；缺 `until` 或 `reason`、`until` 超过 `max-days` → 启动错误 `SECURITY`。
  - 启动记 `SEC_SINGLE_PERSON_ON`。
- `MfaReenrollmentIT` 加一例：单人模式下本人批准。

**QuizBuks**
- `SinglePersonSetupIT`：**空库到可开始入驻，只用两个账号**。
  - 首个管理员执行 `QB_SETUP`、建需求方账号并授超级管理员；需求方接受邀请、给自己加财务与内容的角色；
  - 发布全部提案，三条职责分离规则除外；
  - 建负责人账号与默认运营商，授 `QB_OPERATOR`；
  - 自助注册的发起方入驻成功（自动）；
  - 负责人准备的发布由负责人自己批准（运营商只有他一人）；
  - 断言：建立期未结束、首个管理员已退役、三条规则待发布、每次自决的审计原因与通知。
- 同一测试中：手工入账、转账、提现、打开钱的开关 → 照旧拒绝（始终四眼）。
- 第二位成员加入运营商后，负责人自批被拒（`APPROVAL_OWN_REQUEST`）。
- `SinglePersonSwitchBackIT`：按第 3.5 节的步骤切回。新人持财务管理员后需求方不能再自己发布；发布职责分离规则；第二位超级管理员出现后建立期结束。
- `OperatorHealthIT`：单人模式下 `FEW_REVIEWERS` 换成提醒；关掉后恢复。
- `TreeRevocations`：`QB_TREE_REVOKE_OWN` 在单人模式且唯一持有人时放行并留记录；否则照旧（同时补上盘点发现的缺口：今天没有按码断言它的测试）。
- `launch-day-check.test.sh`：`.env` 中单人模式打开时，"两位超级管理员""建立期已结束"为 `WARN | 单人模式`（带到期日），关闭时照旧 FAIL。
- 现有测试全部不改（第 1.4 节）。

## 8. 实现与拆分

| PR | 分支 | 内容 | 依赖 |
|---|---|---|---|
| a | `1.3/quizbuks-…` 与 `1.3/platform` 各一个小 PR | 第 9 节的小修 | 无，可以先做 |
| p | `1.3/platform`（阶段号由总控给） | D82；`SinglePersonSettings`、`SinglePerson.mayDecideOwn`；`ControlChanges`、`ApprovalProcesses`、`ApproveReenrollment` 三处；`alwaysFourEyes()` 的两个声明；操作原因的入口；启动检查与 `SEC_SINGLE_PERSON_ON`；通知；`10-security.md`、`18` 的文字；`SinglePersonIT` | D82 获确认 |
| s | `1.3/platform` → `1.3/quizbuks` 的同步 | 合并 | p |
| q | `1.3/quizbuks-solo` | B1–B4（B5 待决定）；`operations.md` §3a；上线清单；`SinglePersonSetupIT` 等 | s |

**迁移**：
- 平台：无（不新建表、不加列；记录用审批的理由、操作原因与审计）。若审查要求给决定加一个布尔列，按 D58 给只追加表加列，平台的迁移号由平台线给。
- QuizBuks：无。

## 9. 顺带的两处小修（照旧）

1. **`EMAIL_NOT_VERIFIED` 的提示指明是当前登录的账号**。
   - 这个码只针对调用者本人：`VerifiedEmailPolicy` 读调用者令牌中的声明。今天的文字"请先验证您的电子邮箱。"在建运营商时让人以为说的是负责人。
   - 消息在平台（`backend/core/src/main/resources/jabiz/messages_*.properties:36`），先在 `1.3/platform` 上改（小 PR，带消息测试的断言），再同步：
     - 中：`当前登录的账号的邮箱尚未验证：请先验证邮箱，并重新登录后再试。`
     - 日：`ログイン中のアカウントのメールアドレスが未確認です。確認後、ログインし直してから再度お試しください。`
     - 英：`The e-mail address of the account you are signed in with is not verified yet. Verify it, sign in again, and retry.`
   - QuizBuks 内同时把 `QB_OPERATOR_MEMBER_UNVERIFIED` 的文字改为"所选负责人（或成员）的邮箱尚未验证：请他先接受邀请"。
2. **文档**：
   - `operations.md` §3 第 5 步末与 §7.13"第一个运营商"第 2 步补："**操作的管理员本人**的邮箱须已验证（经邀请设定密码的账号即已验证），验证后须**重新登录**（验证状态随登录令牌）。首个管理员没有邮箱，不能建运营商。"
   - `launch-checklist.md` 2.2 同；`troubleshooting.md` 加一条排查。

## 10. 待决定

1. **D82**：是否接受"开关 + 唯一可决定者"的设计（推荐），还是零代码的 A0（两个账号）。
2. **第 3.4 节**"始终四眼"的清单：特别是费用请求、运营商的分成与结算放不放行。
3. **到期**的上限 `max-days`（推荐 180）与到期后的行为（推荐：停止自决、不使启动失败）。
4. **B5** 一次发布 `QB_SETUP` 的提案：做不做。
5. 第 9 节第 1 条走 `1.3/platform` 的小 PR（推荐）。

## 附录 A：QuizBuks 的审批规则与无规则时的拒绝

| 规则 | 审批权限 | 无规则时 | 单人模式 |
|---|---|---|---|
| `qb.sponsor.review` / `qb.sponsor-page.review` | `qb.review.sponsor` | `QB_REVIEW_NOT_CONFIGURED`（`SponsorProcesses` :302、`SponsorPageProcesses` :329） | 放行（首发入驻自动） |
| `qb.publication.review` | `qb.review.publication` | 同上（`PublicationVersions` :269、`PublicationLifecycle` :328） | 放行 |
| `qb.sponsor-verification.review` | `qb.review.sponsor` | 同上（`VerificationProcesses` :340） | 放行 |
| `qb.tree.review` / `.copy.review` / `.platform.review` / `.lit-revoke.review` | `qb.review.tree` / `qb.tree.platform.write` / `qb.tree.lit.revoke` | 同上（`TreeProcesses` :1016、`TreeRevocations` :181） | 放行 |
| `qb.shared-video.review` | `qb.review.publication` | 同上（`SharedVideoProcesses` :244） | 放行 |
| `qb.fee-request.review` | `qb.topup.bank.approve` | `QB_FEE_NO_REVIEW`（`FeeRequests` :300） | 待决定 2 |
| `qb.operator-plan.review` / `qb.operator-settlement.review` | `qb.topup.bank.approve` | `QB_OPERATOR_PLAN_NO_REVIEW`、`QB_OPERATOR_SETTLEMENT_NO_REVIEW` | 待决定 2 |
| `qb.transfer.review` | `qb.payout.review` | 跳过（`TransferRules` :205） | 始终四眼 |
| 银行提现 | — | `NO_REVIEW`（`BankAccounts` :121、`BankWithdrawals` :330） | 始终四眼 |
| `qb.operator-withdrawal.review` | `qb.topup.bank.approve` | `QB_OPERATOR_WITHDRAWAL_NO_REVIEW` | 始终四眼 |
| `qb.topup-bank.review` / `qb.topup-manual.review` | `qb.topup.bank.approve` | `QB_TOPUP_BANK_NO_REVIEW`、`QB_TOPUP_MANUAL_NO_REVIEW` | 始终四眼 |
