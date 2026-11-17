# F-INIT：`QB_SETUP` 接受一段首发用户的 JSON，建号并分配角色：实施计划（S1，2026-11-17，待 Fable 审查与总控批准）

状态：计划，未写代码。分支 `1.3/quizbuks-init-plan`（只改 `docs/**`）。

**需求方的决定**（总控 2026-11-17 转述，以此为准）："改动最小，先把用户和用户角色加进去"。

- `QB_SETUP` 的输入加一个**可空的 JSON 文本**。内容是首发用户：每人的邮箱、姓名 / 用户名、语言、角色列表。
- 执行 `QB_SETUP` 时按 JSON 建号（邀请，不设密码），再分配角色。
- 按邮箱认人：已有的跳过，并在输出中报告。可以重复执行。
- JSON 为空时，与现在的 `QB_SETUP` 完全一样。

**不在本计划内**：运营商与成员（仍在页面上建）、参数、问答、法律文本、导入定义、专用页面。

依据：
- `operations.md` §3（首次启动的顺序）与 §7.13；平台 `10-security.md` §15–§16；决策 D36、D48、D51、D63。
- 代码（`origin/1.3/quizbuks`）：`QbSetupProcesses`、`QbRoles`、`UserProcesses`（`SEC_USER_CREATE`）、`InvitationProcesses`、`ProtectedChangeProcesses`、`ProtectedGrants`、`ProtectedGrantWriteCheck`、`ProcessExecutor`、`CallProcess`、`VerifiedEmailPolicy`、`BootstrapAdmin`。

## 1. JSON 的格式

输入 `QB_SETUP` 的新字段 `users`（文本，可空）。示例如下（首发的 9 个账号）：

```json
{
  "users": [
    { "email": "finance@example.co.jp", "displayName": "山田 花子", "locale": "ja", "roles": ["QB_ADMIN_FINANCE"] },
    { "email": "content@example.co.jp", "displayName": "佐藤 次郎", "locale": "ja", "roles": ["QB_ADMIN_CONTENT"] },
    { "email": "super1@example.co.jp",  "displayName": "鈴木 一郎", "locale": "ja", "roles": ["QB_ADMIN_SUPER"] },
    { "email": "super2@example.co.jp",  "displayName": "高橋 三郎", "locale": "ja", "roles": ["QB_ADMIN_SUPER"] },
    { "email": "lead@example.co.jp",    "displayName": "田中 四郎", "locale": "ja", "roles": [] },
    { "email": "member1@example.co.jp", "displayName": "伊藤 五郎", "locale": "ja", "roles": ["QB_OPERATOR"] },
    { "email": "member2@example.co.jp", "displayName": "渡辺 六子", "locale": "zh", "userName": "watanabe", "roles": ["QB_OPERATOR"] },
    { "email": "ops3@example.co.jp",    "displayName": "中村 七子", "locale": "en", "roles": ["QB_OPERATOR"] },
    { "email": "ops4@example.co.jp",    "displayName": "小林 八郎", "locale": "ja", "roles": ["QB_OPERATOR"] }
  ]
}
```

| 字段 | 必填 | 规则 |
|---|---|---|
| `email` | 是 | 认人的键，不分大小写；同一份 JSON 中不能重复 |
| `displayName` | 是 | 姓名 |
| `userName` | 否 | 登录名；缺省为邮箱 |
| `locale` | 否 | `ja` / `zh` / `en`，缺省 `ja` |
| `roles` | 是（可为空数组） | 只能是 `QbRoles` 中 `QB_SETUP` 管理的角色码。未知角色、同一人兼 `QB_ADMIN_CONTENT` 与 `QB_ADMIN_FINANCE`（职责分离规则 `qb.sod.control-changes`）都拒绝 |

- 任何其他字段一律拒绝，特别是 `password` 之类的秘密：JSON 中不能有任何秘密，密码只由本人经邀请设定（D63）。
- 人数上限是一个命名常量（缺省 50），超过即拒绝。
- 运营商的负责人写成没有角色的人：负责人的发起方角色由本人在发起方页面"加入"取得，流程不授予。
- 格式错误的 JSON、违反上表规则的内容，都以 **400** 拒绝整个 `QB_SETUP`，违规带位置（如 `users[3].roles[0]`），不做任何写入。

## 2. 执行

`QB_SETUP` 现有的全部步骤（角色、科目、国家、参数、提案、草稿）**先照常执行**。JSON 非空时，再在**同一个事务**中追加两步：

1. **建号**：按顺序处理每个人。
   - 按邮箱（不分大小写）找已有用户：
     - 找到一个：跳过，记"已存在"；
     - 找到多个：记问题"同一邮箱有多个账号"，不处理此人；
     - 找不到：以子流程 `SEC_USER_CREATE` 建号，`invite: true`，不设密码。
   - `SEC_USER_CREATE` 自己检查邮件已开启（`MAIL_DISABLED`）、有邮箱、启用状态。
   - 邀请邮件在提交后寄出（平台的提交后步骤）。
   - 用户名已被别人占用：记问题"用户名已被占用"，跳过此人，不改别人的账号。
2. **分配角色**：顺序为财务、内容、运营商等，最后是超级管理员（见第 4 节）。
   - 已持有该角色：跳过。
   - 已有该用户、该角色的待审批请求：跳过，记"待审批"。
   - 否则以子流程 `SEC_USER_ROLE_ASSIGN` 分配，原因写 `QB_SETUP 首发用户`。

**输出** `SetupOutput` 加一个字段 `users`：每人一行，写行号（JSON 中的序号）、角色码与结果，**不写邮箱与姓名**（第 5 节）。结果取下列之一：

| 结果 | 含义 |
|---|---|
| `CREATED_INVITED` | 新建并寄出邀请 |
| `EXISTS` | 账号已存在 |
| `ROLE_APPLIED` | 角色当即生效 |
| `ROLE_ALREADY` | 已持有该角色 |
| `ROLE_PENDING_APPROVAL` | 待另一位超级管理员在"待办"中批准 |
| `DEFERRED_SECOND_SUPER` | 第二位超级管理员留待下一次执行（见第 4 节） |
| `WAIT_INVITE` | 本人尚未接受邀请，接受后再执行一次 |
| `BOOTSTRAP_RETIRED` | 首个管理员已退役，由超级管理员执行 |
| `NAME_TAKEN` / `EMAIL_AMBIGUOUS` | 见上 |

JSON 为空时不追加任何步骤，输出的 `users` 为空列表，其余输出与现在完全相同。

## 3. 子流程与权限：用代码核对过的事实

**能否以 `CallProcess` 调用**：`SEC_USER_CREATE`（`UserProcesses` :80-142）与 `SEC_USER_ROLE_ASSIGN`（`ProtectedChangeProcesses` :324-371）都是普通（OPEN）流程。子流程只要求目标是 OPEN 或 `subProcessOnly()`（`ProcessExecutor.requireCallable` :254-263），所以都可以调用。`QB_SETUP` 已用同样的方式调用 `LEDGER_OPEN_ACCOUNT`、`PARAM_CREATE`、`CONTROL_CHANGE_PROPOSE`。

**子流程不查权限、二次验证与邮箱**：
- 平台只在入口检查这三样（`VerifiedEmailPolicy` 的说明、`MfaRequirement`；`CallProcess` :132-144 只调用 `executeChild`）。
- 所以 `QB_SETUP` 的入口检查必须覆盖它代为调用的流程：

| | `QB_SETUP` 现在 | 被调用的流程 | 结论 |
|---|---|---|---|
| 二次验证 | `requiresMfa(ADMINISTRATION)` | 两者都是 `requiresMfa(ADMINISTRATION)` | 已覆盖 |
| 已验证邮箱 | 不要求 | 两者都不要求 | 一致。首个管理员没有邮箱，必须能执行 |
| 权限 | `qb.setup` | `security.user.create`；`security.user-role.write` | **不能只靠 `qb.setup`**：今天只有超级管理员与首个管理员（`*`）持有它，而这两种人恰好也持有另外两项（`QbRoles.SUPER` :183-185），但角色可以改。JSON 非空时，`QB_SETUP` 在第一步之前**显式要求调用者同时持有** `security.user.create` 与 `security.user-role.write`（`Permissions.require(ctx.request(), …)`），缺一即 403 并整体不做。JSON 为空时不要求，行为与现在相同 |

**受保护的授权仍由平台把关，不依赖入口检查**：

- **写入路径**：`ProtectedGrantWriteCheck`（平台的 `TemporalWriteCheck`，每一次时态写入都经过它，即 `VersionAppender` 的写入路径）检查每一次让角色分配"生效"的写入。
  - 判断用的是这次操作的 `operation.actorId()` 与 `processSeqId()`（:64-87）。子流程的写入同样经过它，调用者即执行 `QB_SETUP` 的人。
  - 建立期内放行；建立期结束后，不是已批准变更的写入即 422 `PROTECTED_CHANGE_REQUIRES_APPROVAL`；首个管理员在已有其他管理员时 403。
  - 它还在每次安全实体的写入时记下建立期结束与首个管理员退役，永不回退。
- **流程体内**：`SEC_USER_ROLE_ASSIGN` 自己用 `ctx.request()` 评估：
  - 建立期内直接写入（`APPLIED`）；
  - 建立期后建待审批的变更与审批请求（`PENDING`）；
  - 已退役的首个管理员 403 `BOOTSTRAP_ADMIN_RETIRED`（`ProtectedChangeProcesses.defer` :161-166）。
- **批准后应用时**：`TARGET_CREDENTIALS_NOT_OWN`（`LoadProtectedChange` :207），即批准之后、写入之前，被授予者的密码须是本人设定的。
- **结论**：`QB_SETUP` 不能比页面上手工分配多做任何事：同一个人、同样的检查、同样的审批。

**输出是"报告"，不是"失败"**：子流程的业务错误会使整个 `QB_SETUP` 回滚。所以第 2 步在调用之前先用与平台相同的状态判断"会不会被拒"；会被拒的记为报告项（第 2 节的结果），不调用。这样其余的人和 `QB_SETUP` 本身仍然完成。读的状态有：
- 建立期是否结束（`sec_protected_grants_armed`）；
- 首个管理员是否退役（`sec_bootstrap_retired`）；
- 被授予者是否已接受邀请。

预判与平台不一致时，平台的检查仍然生效：调用失败，整个 `QB_SETUP` 回滚，返回平台的错误码，不会越权。测试覆盖每种预判（第 6 节）。

"被授予者已接受邀请"的判断须与 `TARGET_CREDENTIALS_NOT_OWN` 用**同一个谓词**：
- 平台已公开即直接用；
- 若它仍是 `LoadProtectedChange` 的内部方法，先在 `1.3/platform` 上把它公开为一个只读的查询，再同步过来（小 PR，带平台自己的测试），QuizBuks 不复制它的逻辑。

## 4. 建立期与两位超级管理员的顺序

平台的规则（`ProtectedGrants` :345-391，`operations.md` §3 第 4–5 步）：
- 首个管理员之外出现**一位**管理员（持 `security.user-role.write` 等，即 `QB_ADMIN_SUPER`），首个管理员即退役；
- 出现**两位**，建立期即结束，永不回退。
- 职责分离规则（`qb.sod.security-administration` 等，`QB_SETUP` 的提案）必须在建立期结束**之前**由财务管理员发布：
  - 否则以后任命内容、财务管理员与批准 `QB_OPERATOR` 都无法批准（D51 修订 H1）。
  - 而发布要等财务管理员接受邀请、绑好二次验证，不可能与第一次 `QB_SETUP` 同时发生。
- `QB_SETUP` 必须在第二位之前执行过（`security.protected-change.decide` 由它加给超级管理员的角色）。

因此 JSON 中的超级管理员按下面的规则处理：

| 情况 | 处理 |
|---|---|
| 尚无超级管理员 | 本次只授予 JSON 中**第一位** `QB_ADMIN_SUPER`，排在全部角色的最后（授予后首个管理员退役，后面不能再有受保护的分配）。其余的超级管理员记 `DEFERRED_SECOND_SUPER`，说明"财务管理员发布提案（含职责分离规则）之后，由这位超级管理员再执行一次 `QB_SETUP`" |
| 已有一位超级管理员，三条职责分离规则**未**全部生效 | 不授予，记 `DEFERRED_SECOND_SUPER`，同上 |
| 已有一位，三条规则已生效，执行人是那一位超级管理员 | 授予第二位（建立期内，当即生效；之后建立期结束） |
| 首个管理员执行、且已退役 | 一切受保护的角色记 `BOOTSTRAP_RETIRED`；不受保护的照做 |
| 建立期已结束 | 受保护的角色以 `SEC_USER_ROLE_ASSIGN` 申请（`ROLE_PENDING_APPROVAL`），先查被授予者已接受邀请，否则记 `WAIT_INVITE`、不申请 |

财务、内容管理员与运营商成员（`QB_OPERATOR`）的角色在第一次执行时（建立期内）当即生效：
- 它们不持有 `security.user-role.write` / `security.role.write`，平台不把它们算作管理员，不会结束建立期（D51）；
- 建立期内平台不查本人凭据。

这与今天在页面上逐个分配完全相同。

不选"JSON 中有两位就整体拒绝"：需求方会把全部人写进同一份 JSON，拒绝只会让他删掉一位、以后再加，步骤更多。而本方案中，第二次执行用的是**同一份 JSON**，别的都跳过。

## 5. 个人信息与秘密

- JSON 不含任何秘密：第 1 节拒绝未知字段。
- **输入的摘要**：
  - 新字段 `users` 标 `@Sensitive`，在 `op_process.input_summary`、日志、`toString()` 与事件中遮蔽为 `***`（10 §6）。
  - 人数不放进输入（输入不能信调用者填的数），由流程在解析后写进输出。
  - 这样操作记录的输入摘要只有 `users=***`，输出只有人数与按行号的结果，都没有邮箱与姓名。
- **输出**只写行号、角色码与结果，不写邮箱与姓名。流程页显示输出时，需求方对照自己手里的 JSON 的行号即可。
- **子流程的操作记录**：`SEC_USER_CREATE` 的输入摘要与页面上手工建号时相同（它的输入组件按平台现有的遮蔽规则处理）。本计划不增加也不减少它的记录。

## 6. 测试清单

**集成（真实 PostgreSQL，`QbSetupUsersIT`）**
- **空库 + JSON**：首个管理员执行 `QB_SETUP`，JSON 为第 1 节的 9 人。
  - 9 个账号都建好并处于"已邀请"，提交后寄出 9 封邀请；
  - 财务、内容、4 位运营商成员的角色当即生效；
  - 第一位超级管理员生效，首个管理员已退役，建立期未结束；
  - 第二位为 `DEFERRED_SECOND_SUPER`；
  - 负责人无角色；
  - `QB_SETUP` 原有的全部产物与 JSON 为空时相同。
- **第二次执行**：用测试夹具模拟财务管理员接受邀请并发布职责分离规则；第一位超级管理员以**同一份 JSON** 执行。
  - 第二位超级管理员生效，建立期结束；
  - 其余每人 `EXISTS` / `ROLE_ALREADY`；
  - 没有新的写入、没有邮件。
- **幂等**：同一 JSON 第三次执行，全部跳过，安全实体的行数不变。
- **已有一半**（需求方的现状）：先用页面的流程建好几个用户（其中一个邮箱大小写不同、一个设了临时密码），再执行。
  - 按邮箱认出，`EXISTS`；
  - 建立期内照常分配；
  - 建立期结束后临时密码的那位记 `WAIT_INVITE`，不申请。
- **建立期结束后新增一人**：超级管理员执行，受保护的角色为 `ROLE_PENDING_APPROVAL`。另一位超级管理员在待办中批准后生效；被授予者若未接受邀请，平台在应用时拒绝（`TARGET_CREDENTIALS_NOT_OWN`）——本计划的预判应已先记 `WAIT_INVITE`。
- **JSON 为空**：与现在逐字段相同（现有 `QbSetup*` 测试全部不改即通过）。
- **非法输入 → 400，且没有任何写入**：
  - 不是 JSON；
  - 缺 `email` 或 `displayName`；
  - 邮箱重复（仅大小写不同也算）；
  - 未知角色；
  - 同一人兼内容与财务；
  - 含 `password` 等未知字段；
  - 超过人数上限。
- **安全**：
  - 持有 `qb.setup` 但缺 `security.user.create` 或 `security.user-role.write` 的调用者（测试中另建的角色）带 JSON 执行 → 403，没有任何写入；不带 JSON → 照常。
  - 首个管理员在已退役后带 JSON 执行 → 受保护的角色 `BOOTSTRAP_RETIRED`，`QB_SETUP` 其余部分照常完成。
  - 预判被绕过时（测试中强制跳过预判），平台的 `ProtectedGrantWriteCheck` 仍拒绝，整个 `QB_SETUP` 回滚。这是"检查不依赖入口"的证明。
  - 邮件未开启时带 JSON 执行 → `MAIL_DISABLED`（平台的码），整个 `QB_SETUP` 回滚，提示先开邮件。
- **个人信息**：`op_process.input_summary` 与 `QB_SETUP` 的输出中不含任何邮箱或姓名（全文搜索断言）。

**场景**：`scenarios/setup/first-users.yml`（固定时钟与快照）。首个管理员带 JSON 执行，再模拟接受邀请与发布，第一位超级管理员再执行。快照为角色与报告。

**前端**：流程页的表单由输入的 JSON Schema 生成。`users` 是长文本，应显示为多行文本框（实施时在流程页核对；若平台的表单对长文本不显示多行，在 `1.3/platform` 上加长文本的多行显示，而不是给 QuizBuks 另做页面）。

## 7. 需求方剩下的手工步骤（一页）

（实施时并入 `operations.md` §3 第 3–5 步。下文是草稿。）

> 1. **运维**：`.env` 中开启邮件（`JABIZ_MAIL_ENABLED=true`），启动。
> 2. **首个管理员**：登录 `/admin/`，在"安全设置"绑二次验证。打开流程 `QB_SETUP`，在 `users` 中粘贴首发人员的 JSON（第 1 节的格式，全部 8–9 人一次写上），执行。看结果：除第二位超级管理员为"待下一次执行"外，应全部是"已邀请""已生效"。之后运维删去首个管理员的两行环境变量。
> 3. **每个人**：点邀请邮件的链接（72 小时内）设密码 → 登录 → 绑二次验证。
> 4. **财务管理员**：在"数据 → SysControlChange"按 `operations.md` §3 第 5 步的顺序发布 `QB_SETUP` 的提案，**包括三条职责分离规则**（这一步本计划不改）。
> 5. **第一位超级管理员**：打开流程 `QB_SETUP`，粘贴**同一份 JSON**，执行一次。第二位超级管理员即生效，其余全部"已存在"。
> 6. **第一位超级管理员**（邮箱已验证，经邀请设定密码即已验证；**验证后重新登录过**）：在运营商页面建默认运营商、选负责人；加成员（运营商页面，今天要粘贴用户编号，见总控转来的 #729 盘点）。
> 7. **负责人**：登录发起方页面点"加入"。**运营商成员**：登录运营商管理系统，绑二次验证（角色已在第 2 步生效）。
>
> 以后加人：任一位超级管理员以新的 JSON 执行 `QB_SETUP`。受保护的角色成为"待审批"，另一位超级管理员在"待办"中批准；被授予者须先接受邀请。

与今天相比，第 2、5 步代替了：每人一次建号表单（手填）、每人每个角色一次 `SEC_USER_ROLE_ASSIGN` 表单（手填两个 UUID），共约 20 次手填编号的操作。第 4、6 步不在本计划内。

## 8. 实现

| 位置 | 改动 |
|---|---|
| `quizbuks/setup/QbSetupProcesses` | `SetupInput` 加 `@Sensitive String users`（可空，长度上限一个命名常量）。新增步骤"解析首发用户"（解析、校验、400）、"建号"、"分配角色"，排在现有步骤之后。`SetupOutput` 加 `users` 与人数 |
| `quizbuks/setup/SetupUsers`（新） | JSON 的解析与校验（`record`，Jackson 严格模式：拒绝未知字段）；按邮箱认人；预判（建立期、退役、本人凭据、职责分离规则是否生效）。只读，经存储引擎查询 |
| `quizbuks/setup/QbSetupCodes` | 报告的结果码与 400 的违规码，三语消息 |
| 平台（若需要，`1.3/platform` 小 PR） | 把"被授予者的凭据是否本人的"判断公开为只读查询（第 3 节末）；流程表单的长文本多行显示（第 6 节"前端"） |
| 文档 | `operations.md` §3 第 3–5 步改写（第 7 节）；`launch-checklist.md` 2.2 的首次启动一项改为指向它 |

**迁移：无**（不新建表；写入都经现有流程）。

**拆分**：
- PR a：第 9 节的小修，可以先做；
- PR b：本功能；若需要，平台 PR 在 b 之前。

## 9. 顺带的两处小修（PR a）

1. **`EMAIL_NOT_VERIFIED` 的提示指明是当前登录的账号**。
   - 这个码只针对调用者本人：`VerifiedEmailPolicy` 读调用者令牌中的声明。今天的文字"请先验证您的电子邮箱。"在建运营商时让人以为说的是负责人。
   - 消息在平台（`backend/core/src/main/resources/jabiz/messages_*.properties:36`），所以**先在 `1.3/platform` 上改**（小 PR，带消息测试的断言），再同步：
     - 中：`当前登录的账号的邮箱尚未验证：请先验证邮箱，并重新登录后再试。`
     - 日：`ログイン中のアカウントのメールアドレスが未確認です。確認後、ログインし直してから再度お試しください。`
     - 英：`The e-mail address of the account you are signed in with is not verified yet. Verify it, sign in again, and retry.`
   - QuizBuks 内同时把负责人未验证的 `QB_OPERATOR_MEMBER_UNVERIFIED` 文字改为"所选负责人（或成员）的邮箱尚未验证：请他先接受邀请"，把两种情况区分开。
2. **文档**：
   - `operations.md` §3 第 5 步末（默认运营商）与 §7.13"第一个运营商"第 2 步补："**操作的管理员本人**的邮箱须已验证（经邀请设定密码的账号即已验证），验证后须**重新登录**（验证状态随登录令牌）。首个管理员没有邮箱，不能建运营商，由超级管理员建。"
   - `launch-checklist.md` 2.2"第一个运营商"一项补同一句；`troubleshooting.md` 加一条建运营商时 `EMAIL_NOT_VERIFIED` 的排查。

## 10. 风险

- **R1 预判与平台不一致**。若预判说"可以"而平台拒绝，整个 `QB_SETUP` 回滚、返回平台的错误码：不会越权，只是这一次没做成。测试逐一覆盖每种预判，以平台的同一谓词为准（第 3 节）。
- **R2 角色可改**。`qb.setup` 与 `security.user-*` 今天由同一批人持有，以后可能分开。第 3 节的显式检查保证 JSON 的部分不靠这个巧合。
- **R3 第二次执行由谁做**。第二位超级管理员必须由第一位执行（首个管理员已退役），这是平台规则（`operations.md` §3），不是本计划新加的步骤。输出与第 7 节写明了。
- **R4 邀请的有效期**（72 小时）。过期的人在页面用"发送邀请"重发。本计划不自动重发：重复执行不应每次都发信。

## 11. 待决定

1. JSON 中出现两位超级管理员时：本次只授第一位、第二位留待下次（推荐，第 4 节），还是整体拒绝。
2. 第 3 节末的平台小改（公开"本人凭据"的判断）是否接受；或者改为不预判、让平台拒绝并回滚（更简单，但一人未接受邀请就使整次 `QB_SETUP` 失败）。
3. 第 9 节第 1 条走 `1.3/platform` 的小 PR（推荐）。
