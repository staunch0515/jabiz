# F-LAUNCH 首发配置：一个配置文件，启动即完成首发初始化：实施计划（S1，2026-11-18；第三稿：按 Fable 复审与需求方"出错即清库重来"的决定修改；待需求方确认）

状态：计划，未写代码。分支 `1.3/quizbuks-launch-wizard-plan`（只改 `docs/**`）。本文取代同一 PR 上较早的"首发向导页"计划（已撤回，不送审）。审查意见的处理见第 11 节。

**需求方的要求**（总控 2026-11-18 转述，需求方补充为准）：
- 不要页面，界面上零按钮。运维提供一个不含秘密的首发配置文件，路径由环境变量给出。
- 启动时如果用户表一个用户都没有（全新库），执行首发初始化；有任何用户即跳过，并在日志中说明。
- 系统自动完成以下各项：
  - `QB_SETUP`；
  - 受控参数直接以配置值生效；
  - 运营商（可多个，含默认）及其直营发起方；
  - 发起方的资料、问题、Quiz、素材、知识结构图与发布，发布直接上线；
  - 邀请账号；
  - 账号接受邀请并绑定二次验证后授予角色；
  - 三条职责分离规则与等前提的开关留待；
  - 完成后在日志与给 A 的邮件中报告"系统已正式运行"和剩下的人工事项。
- 人只做一件事：每个账号的本人从邀请邮件设定密码，并绑定二次验证。运维另做一件事：确认超级管理员（第 8 节；待需求方确认，先按保留写）。
- **中途出错**（需求方 2026-11-18 的决定）：修改数据或程序、清空数据库、重新启动，不续做。

**依据**：代码 `origin/1.3/quizbuks`。核对的要点：
- 每个内容流程都以操作人为所有者，没有 `sponsorId` 输入：
  - `QuizProcesses:274`；
  - `PublicationProcesses:597`、`PublicationEditing:490`；
  - `TreeProcesses:228`；
  - 内容数据视图按 `ownerId = actorId` 限定（`ContentConfig:56-69`）。
- 发起方包（§7.33，`PackageImportProcesses`）：一次事务做一步；计划由浏览器做（`package.ts`），服务器不读包。
- `QB_OPERATOR_SAVE` 要求负责人邮箱已验证（`MEMBER_UNVERIFIED`，`OperatorProcesses:454-467`）。`DirectSponsors.write` 直接写出已批准的直营发起方，不记同意。
- `ControlledParamGuard` 对系统身份也不例外。`CONTROL_CHANGE_PUBLISH` 只拒绝提议人本人（`ControlChanges:179-183`）。
- `APPROVAL_DECIDE` 要求决定人持有该级权限、且不是申请人（`ApprovalProcesses:145-152`）。
- 受保护的授权：
  - 建立期内当即生效，建立期在出现两位管理员（D51 意义上）时结束；
  - `.always` 没有建立期（`ProtectedGrants:342-362`）。
- 登录：
  - 没有任何该入口接受的角色的账号，在角色检查即被拒（`NO_ROLE`，`SponsorSignInProcess.accessSteps:78-99`）；
  - 强制绑定二次验证（`MFA_ENROLLMENT_REQUIRED`）在角色检查之后，由角色的 `requireMfa` 触发（`AuthController:185`）。
- QuizBuks 的入口：
  - 管理系统入口 `admin` 只接受 `QB_ADMIN_ROLES`（`application.yml:210`，缺省 `ADMIN,QB_ADMIN_SUPER,QB_ADMIN_CONTENT,QB_ADMIN_FINANCE`）；
  - 运营商入口只接受 `QB_OPERATOR`；
  - 邀请链接的入口缺省为 `admin`（`InvitationProcesses.DEFAULT_ENTRY`）。
- 流程的权限、二次验证与已验证邮箱只在 HTTP 入口检查（`ProcessController:100-110`）。在进程内执行时，只有流程体内自己的检查仍有效。
- `MfaController` 以 `ActingUser.as` + `executeAtEntry` 代用户运行入口流程（代行的先例）。
- `FileUploadService.storeGenerated`；`QuizEditing.requireOwnFile` / `FILE_NOT_OWN`（文件须由 Quiz 的所有者上传）。
- `FirstLaunchSwitches`、`PublicationReadiness:208-245`、`PublicationPrepayments`、`PublicationEditing:307-321`（未写奖励方式即按 PLATFORM，首发时被拒）。
- `ConsentChecks`：只在有生效的法律文本时询问，`QB_SETUP` 只建草稿。

## 0. 结论先行

1. **能做到"启动即上线"**：全部内容在启动时建好并上线，然后才寄出邀请。人只做"接受邀请 + 绑定二次验证"，运维再确认一次超级管理员。之后的角色授予由系统自动完成。
2. **两个阶段**：
   - **内容阶段**：启动时一口气完成，期间不对外服务。任一步失败即记录原因、应用退出，日志说明"修改后清空数据库重启"。
   - **等人阶段**：已对外服务，只做邀请、授予与报告。唯一的补救是改尚未接受邀请的账号的邮箱后重启。
3. **平台必须改，只新增四样东西**（新决策 D82）：
   - 运行记录与运行器；
   - 参数初值发布；
   - 首发审批；
   - 首发代行。
   后三样在内容阶段结束时永久关闭。不改平台就必须在 QuizBuks 里直接写参数表或审批表，这违反 CLAUDE.md（第 7 节）。
4. **角色授予不改平台规则**：
   - 系统照常运行 `SEC_USER_ROLE_ASSIGN`。建立期内当即生效，建立期结束后照常为 `PENDING`，交给已有的两位管理员审批。
   - D48 / D51 不修订。
5. **QuizBuks 的改动**：
   - 首发处理器；
   - 发起方包的服务器端计划（把 `package.ts` 移植到 Java）；
   - 首发的内容阶段中，负责人可以尚未验证邮箱；
   - 管理系统入口缺省接受首发待命角色；
   - `FirstLaunchSwitches` 标出等前提的行与可设的参数。
   - 不需要迁移（V134 还给总控）。
6. **审核**：配置中的内容视为已审，由首发审批批准。审批记录照常绑定内容哈希，决定人记为 `first-launch` 与配置哈希。
7. **钱**：首发不产生账本交易与预留。奖励只允许线下兑换；资金类参数固定为首发值，金额不可设；测试断言相关表零行。

## 1. 配置的结构

### 1.1 文件与目录

```
first-launch/
  first-launch.yml          主文件：账号、运营商、发起方、参数、奖励方式、发布的上线时间等（可写中文注释）
  sponsors/
    hq/package.json         发起方包（§7.33 的格式 version 2，与发起方页面"导出"的文件相同）
    kansai/package.json
```

**环境变量**：
- `JABIZ_FIRST_LAUNCH_CONFIG=/etc/quizbuks/first-launch/first-launch.yml`（平台读取；目录即主文件所在目录）；
- `JABIZ_FIRST_LAUNCH_DRY_RUN=true`：只校验，并打印步骤计划，不写任何东西，随即退出（第 6.4 节）；
- `JABIZ_FIRST_LAUNCH_CONFIRM_ADMINS=<指纹>[,<指纹>…]`：运维确认超级管理员（第 8 节）。

**文件内容**：
- 不含秘密：没有密码、没有兑换码、没有发信服务器的密码。校验发现形似秘密的键（`password`、`secret`、`codes` 等）即拒绝。
- 主文件的 SHA-256 与内容目录的清单哈希（按相对路径排序后逐个文件的哈希再哈希）合成"配置哈希"，写入运行记录、日志与审计。

### 1.2 复用现有的导入格式

| 数据 | 格式 | 复用的程度 |
|---|---|---|
| 问题、Quiz、素材、图片 | 发起方包 `quizzes[].versions[].file`，即 `quizbuks.quiz` 文件（`QuizFileFormat`），图片以 base64 内嵌在 `files` 中 | 完全复用：服务器早已能读 Quiz 文件（`QB_QUIZ_CHECK` / `QB_QUIZ_IMPORT`） |
| 知识结构图 | 发起方包 `trees[]`（节点、条件） | 完全复用。平台没有单独的结构图导入，结构图只是包的一步（`QB_TREE_CREATE` + `QB_TREE_DRAFT_SAVE`） |
| 发起方简介与页面 | 发起方包 `profile`，即资料包 `quizbuks-profile`（`ProfilePackage`） | 完全复用（`QB_PROFILE_IMPORT`） |
| 发布（设置、主题、受众、结构图节点） | 发起方包 `publications[]`（`settings` 即 `QB_PUBLICATION_SAVE` 的输入） | 复用；上线时间与奖励方式在主文件中补充（包里没有） |
| 账号、运营商、参数、奖励方式 | 主文件 | 包不带这些（§7.33：状态、审核、钱、人员、运营商归属都不带） |

**为什么用包**：
- 内容可以先在测试系统上由真实的发起方做好、试答，再从发起方页面导出。导出的就是首发要用的文件，不用另学一种格式。
- `PackageFixtures` 与 `SampleRestoreIT` 已有的样例可以直接当首发的测试数据。

**代价**：包的计划（哪一步调用哪个流程）目前只在浏览器里（`quizbuks-web/sponsor/src/profile/package.ts`），要移植到 Java（`PackagePlan`）。前后端用同一组包样例对照，计划须逐步一致（同 `spec/validation-cases.json` 的做法）。

**图片怎么放**：
- Quiz 的图片在 Quiz 文件的 `files` 中（导出的文件本来就是这样）；
- 资料包的图片在其 `files` 中；
- 不接受 `{url}` 形式的图片：首发不从网上取内容，校验即拒绝，并说明"请先在测试系统上传后再导出"；
- 单个文件与总大小有上限（参数 `jabiz.first-launch.max-file-bytes`、`max-total-bytes`）。

文件经平台的文件流水线保存：按内容判定类型、重新编码图片、按 Quiz 的文件策略。上传者记为该发起方的用户，所以 `FILE_NOT_OWN` 的检查照常成立。

### 1.3 完整示例（两个运营商，各一个直营发起方）

```yaml
# 首发配置：只在全新的库上执行一次。完成后请撤下本文件（之后每次启动都会提醒）。
# 不要写任何密码或兑换码：本文件会随部署保存，哈希会记入审计。
# 内容阶段出错：按日志修改本文件或程序，清空数据库，重新启动。
version: 1

# 站点：报告邮件与日志的语言
site:
  language: zh

# 账号的邮箱只能在这些域名之下（校验强制；防止笔误把邀请寄到别处）
allowedEmailDomains: [example.co.jp]

# 账号：内容就位后才寄邀请，密码由本人从邀请邮件设定；接受邀请并绑定二次验证后授予角色。
# 超级管理员（D51 意义上的管理员）另须运维确认：从日志读出指纹，设 JABIZ_FIRST_LAUNCH_CONFIRM_ADMINS 后重启。
# 已对外服务后发现某账号的邮箱写错：只要本人尚未接受邀请，改这里的 email 后重启即重发邀请。
accounts:
  - key: B                       # 配置内的代号，下面用它引用
    email: b.tanaka@example.co.jp
    name: 田中 美咲
    language: ja
    roles: [QB_ADMIN_FINANCE, QB_ADMIN_CONTENT, QB_OPERATOR]
  - key: C
    email: c.yamada@example.co.jp
    name: 山田 健
    language: ja
    roles: [QB_OPERATOR]
  - key: A
    email: a.suzuki@example.co.jp
    name: 鈴木 一郎
    language: ja
    roles: [QB_ADMIN_SUPER, QB_OPERATOR]
    report: true                 # 首发完成的报告寄给这个账号（至少一个）

# 运营商：default: true 的是新发起方的默认运营商（恰好一个）。
# lead 即负责人，也是该运营商的直营发起方的账号。members 在本人接受邀请后加入。
# 一个账号只能属于一个运营商（作负责人或成员，校验强制）。
operators:
  - key: tokyo
    name: 东京运营
    default: true
    lead: B
    members: [A]
  - key: kansai
    name: 关西运营
    lead: C

# 直营发起方：每个运营商一个，内容在 sponsors/<key>/package.json。
sponsors:
  - operator: tokyo
    package: sponsors/hq/package.json
    # 发起方可用的奖励方式（QB_SPONSOR_REWARD_METHODS_SET）；首发只允许线下兑换。
    rewardMethods: [OFFLINE_REDEEM]
    # 包中各发布的补充，按包内发布的名称（settings.name）对应；包中每个发布都要写，名称不能重复。
    publications:
      春季答题:
        rewardMethod: OFFLINE_REDEEM
        redeemDescription: 在东京店的收银台出示答题结果页即可领取纪念徽章。
        startAt: launch          # launch = 首发运行的时刻；也可写 2026-12-01T09:00+09:00
        endAt: 2027-03-31T23:59+09:00
  - operator: kansai
    package: sponsors/kansai/package.json
    rewardMethods: [OFFLINE_REDEEM]
    publications:
      关西历史:
        rewardMethod: OFFLINE_REDEEM
        redeemDescription: 在大阪店出示结果页领取明信片。
        startAt: launch
        endAt: 2027-01-31T23:59+09:00

# 受控参数：只能写 QuizBuks 声明为"首发可设"的键（启动检查列出这份清单），以配置值直接生效。
# 资金类的开关固定为 FirstLaunchSwitches 的首发值，金额类不能设；等前提的行一律只提议、不生效。
params:
  qb.ai.sponsor.daily-jobs: 5
```

下面是发起方包（节选，JSON，格式即发起方页面导出的 version 2；包里不能写注释，说明写在这里）：
- `trees`：一个结构图、两个节点；
- `quizzes`：一个 Quiz、一个版本、三道题，图片内嵌；
- `publications`：一个发布，绑定到节点 `basics`。

```json
{
  "format": "quizbuks.sponsor-package",
  "version": 2,
  "profile": { "format": "quizbuks-profile", "basics": { "companyName": "东京运营直营", "intro": "…" }, "page": { "…": "…" } },
  "trees": [
    { "key": "spring", "name": "春季知识", "nodes": [
      { "key": "basics", "name": "入门", "condition": null },
      { "key": "advanced", "name": "进阶", "condition": { "after": ["basics"] } } ] }
  ],
  "quizzes": [
    { "key": "spring", "current": 1, "topics": ["seasonal"], "versions": [
      { "no": 1, "file": {
        "format": "quizbuks.quiz",
        "quiz": { "title": "春天的花", "language": "zh" },
        "materials": [ { "key": "m1", "kind": "TEXT", "text": "樱花在三月下旬开放……" } ],
        "questions": [
          { "key": "q1", "kind": "SINGLE", "text": "东京的樱花一般几月开？", "options": ["二月", "三月下旬", "五月"], "answer": [1] },
          { "key": "q2", "kind": "SINGLE", "text": "下图是什么花？", "image": "img1", "options": ["梅", "樱", "桃"], "answer": [1] },
          { "key": "q3", "kind": "MULTIPLE", "text": "哪些是春天的花？", "options": ["樱", "向日葵", "郁金香"], "answer": [0, 2] } ],
        "files": { "img1": { "type": "image/jpeg", "data": "/9j/4AAQ…" } } } } ] }
  ],
  "publications": [
    { "quiz": "spring", "version": 1,
      "settings": { "name": "春季答题", "timeLimitSec": 300, "passScore": 2, "budgetCap": 0, "maxWinners": 100,
                    "tiers": [ { "minScore": 2, "reward": 0 } ], "regions": ["JP"] },
      "node": { "tree": "spring", "node": "basics" }, "topics": ["seasonal"] }
  ]
}
```

（字段名以 `package.ts` 与 `QuizFileFormat` 为准，这里只为说明结构。写代码时，示例改为测试资源 `first-launch/example/`，并由测试保证可以执行。）

## 2. 阶段、步骤与每类数据走哪个现有流程

### 2.1 两个阶段

| 阶段 | 做什么 | 对外服务 | 出错时 |
|---|---|---|---|
| **内容阶段** | 第 2.3 节表中的 1–12 步，启动时一口气完成 | 不服务（就绪门：对外请求 503） | 记录原因，应用退出；日志："修改配置或程序后，清空数据库，重新启动" |
| **等人阶段** | 13–17 步：邀请、授予、加入成员、报告 | 服务（学者已可答题） | 不清库。补救只有一种：尚未接受邀请的账号邮箱写错时，改配置中的 `email` 后重启，即以新地址重发（第 6.3 节） |

- 内容阶段完成时，运行记录追加 `CONTENT_DONE`。此后参数初值发布、首发审批、首发代行永久关闭，任何调用都被拒。
- 平台以运行记录判断，不是看配置或参数。
- **硬期限**：参数 `jabiz.first-launch.deadline`，缺省 14 天，自 `CONTENT_DONE` 起算。到期仍未完成即追加 `EXPIRED`（终态），剩下的授予由人照常办理，报告列出未完成的账号。

### 2.2 谁来运行

- **系统**：全局性的步骤由系统身份运行。
- **首发代行**：属于某个发起方的步骤，以该发起方的用户编号运行（第 7.1 节 ④）。
  - 只在内容阶段；
  - 只对本运行创建、尚无任何凭据的账号有效：还没有设定密码、没有二次验证、没有通行密钥、没有关联外部账号。
  - 邀请在 `CONTENT_DONE` 之后才寄出，所以内容阶段中这些账号必然尚无凭据。这一条检查是第二道防线：违反即视为失败（应用退出）。

### 2.3 步骤与流程

| # | 数据 | 现有流程 | 谁来运行 | 改动 |
|---|---|---|---|---|
| 1 | 角色、权限、账本科目、参数声明、审批规则与职责分离规则的提议、法律文本草稿 | `QB_SETUP` | 系统 | 无（流程体内没有依赖人的检查） |
| 2 | 受控参数（首发可设的键与首发值）、审批规则：生效 | `CONTROL_CHANGE_PROPOSE` 之后，由平台子流程 `CONTROL_CHANGE_FIRST_LAUNCH_PUBLISH` 发布 | 系统 | 平台（第 7.1 节 ②） |
| 3 | 职责分离规则、等前提的开关 | 只由 `QB_SETUP` 提议，不发布 | — | `FirstLaunchSwitches` 加"等前提"标记（第 7.2 节） |
| 4 | 账号（**只建，不邀请**），加首发待命角色 | `SEC_USER_CREATE`（不带 `invite`）；平台建一个没有任何权限、要求二次验证的角色 `FIRST_LAUNCH_PENDING`，分配给每个首发账号（第 5.2 节） | 系统 | 平台 |
| 5 | 运营商与直营发起方 | `QB_OPERATOR_SAVE`（含 `DirectSponsors.write`），默认运营商带 `defaultForNew` | 系统 | QuizBuks：首发的内容阶段中负责人可以尚未验证邮箱（第 5 节） |
| 6 | 发起方的资料、结构图草稿、Quiz、问题、素材、版本、发布草稿 | `QB_PACKAGE_IMPORT_START` / `_STEP`（内部依次是 `QB_PROFILE_IMPORT`、`QB_TREE_CREATE`、`QB_TREE_DRAFT_SAVE`、`QB_QUIZ_IMPORT`、`QuestionImportProcesses.IMPORT`、`QB_QUIZ_PUBLISH_VERSION`、`QB_PUBLICATION_SAVE`、`PublicationAudience.SAVE`、`PublicationTopics.SET` 等） | 首发代行 | QuizBuks：计划移植到 Java；文件由平台登记为该发起方上传 |
| 7 | 发布的补充（奖励方式、兑换说明、上线与结束时间） | `QB_PUBLICATION_SAVE`（再存一次） | 首发代行 | 无 |
| 8 | 发起方的奖励方式 | `QB_SPONSOR_REWARD_METHODS_SET`（带 `userId`） | 系统 | 无 |
| 9 | 发起方页面送审 | `QB_SPONSOR_PAGE_SUBMIT` | 首发代行 | 无 |
| 10 | 结构图送审；之后发布送审 | `QB_TREE_SUBMIT`；`QB_PUBLICATION_SUBMIT`（预付款检查照常：运营商无价格，受众与他人视频已排除，不预留） | 首发代行 | 无 |
| 11 | 第 9–10 步开出的审批 | 平台子流程 `APPROVAL_FIRST_LAUNCH_DECIDE`，之后照常经事件 `jabiz.approval.approved` 运行各自的 `*_APPROVAL_RESULT` | 系统 | 平台（第 7.1 节 ③） |
| 12 | 确认内容就位：结构图 ACTIVE，发布 LIVE 或 SCHEDULED；追加 `CONTENT_DONE`，开放服务 | 只读 | 系统 | — |
| 13 | 寄出邀请 | `SEC_USER_INVITE`（入口 `admin`） | 系统 | 平台（邀请模板加首发的说明） |
| 14 | 账号接受邀请并绑定二次验证后：授予配置中的角色 | `SEC_USER_ROLE_ASSIGN`；超级管理员另须运维确认（第 8 节） | 系统（任务） | 无平台规则的改动 |
| 15 | 撤下待命角色 | 只在该账号至少一项配置角色 `APPLIED` 之后 | 系统（任务） | — |
| 16 | 运营商的其他成员 | `QB_OPERATOR_MEMBER_ADD`（本人接受邀请后，邮箱已验证） | 系统（任务） | 无 |
| 17 | 报告 | 日志与邮件模板 `jabiz.first-launch.report`（`SendMail`） | 系统（任务） | 平台；QuizBuks 给出剩下的事项 |

**推进**：
- 运行器在应用就绪（事件投递与任务调度已启动）之后才开始内容阶段，不阻塞 Spring 的启动。就绪门在此之前就已关闭对外服务。
- 结构图的审批结果（节点可绑定）到了之后才送审发布；审批结果由事件投递运行，运行器等待它，时限为参数 `jabiz.first-launch.content-timeout`（缺省 10 分钟），超时即失败。
- 发布在审批通过后照常由 `PublicationRules.resumeTo` 进入 LIVE；开始时间在未来的先 SCHEDULED，由 `qb.publication-tick` 上线。
- 等人阶段由平台的任务 `jabiz.first-launch` 每分钟推进。每一步都按现状判断（角色是否已有、是否已是成员），所以重复推进无害。

**审批的级别与限额**（L1）：
- 首发审批对一个审批的每一级都作出批准，每级各记一条决定。
- 首发的内容不涉及金额，审批规则中的限额条件不会出现。万一出现带限额的审批，首发审批拒绝，即内容阶段失败。

## 3. 审核

### 3.1 原则

部署渠道（与引导管理员的环境变量同等可信）给出的内容视为已经审过。

- 审核照常开出审批（`RequireApproval`，绑定内容哈希），由首发审批作出"批准"：
  - 决定人记为 `first-launch`；
  - 理由记"首发配置 <配置哈希>"。
- 之后的流程与平时完全相同：事件、结果流程、LIVE。
- 这样审批记录、审计、以后同一内容的复用（`APPROVED` 的复用）都照常成立。QuizBuks 的审核代码一行不改。

**不选的办法**：
- 临时把 `qb.review.publication.auto` 设为 true：这是受控参数，来回改要两次发布，与其他请求有竞态，审计上也看不出是首发。
- 结构图根本没有自动通过的开关。
- 直接写 APPROVED 状态：绕过审批记录，违反 D23。

### 3.2 要改的规则

| 规则 | 现状 | 改为 | 归属 |
|---|---|---|---|
| 审批须由持有该级权限的人决定、不能是申请人（D23、`APPROVAL_OWN_REQUEST`） | 无例外 | 内容阶段中，首发审批批准本运行的步骤开出的审批（按审批的 `process_seq_id` 属于本运行判断） | 平台，D82 |
| §7.49 / F-F11"没有免审的特例" | QuizBuks 的运维约定 | 首发配置是唯一的例外；`operations.md` §7.49 同步说明 | QuizBuks 文档 |

### 3.3 公司认证：不由首发声明

- 首发不做公司认证，由负责人以后照常认证（寄到域名的链接 + 运营商审核）。
- 原因：认证状态会触发其他步骤（会补发），由首发声明要另造一条认证路径。
- 报告把它列为剩下的事项。

## 4. 钱：首发不产生账本交易与预留

| 项目 | 处理 | 理由 |
|---|---|---|
| 奖励方式 | 只允许线下兑换 `OFFLINE_REDEEM`，须写 `redeemDescription`；其余一律拒绝：<br>• Kudos（`PLATFORM`）<br>• 数字奖品（`DIGITAL_PRIZE`）<br>• 发起方现金（`SPONSOR_CASH`）| 未写奖励方式按 PLATFORM，送审时被 `REWARD_KUDOS_DISABLED` 拒绝（`PublicationEditing:307-321`），所以每个发布都必须写 `rewardMethod`。数字奖品排除：码是秘密，"由发起方发送"的方式也要发起方在场 |
| 档位奖励额与 `budgetCap` | 只允许 0 | 非 0 只对 Kudos 有意义 |
| 预付款（受众费、分发费、曝光费，F-M5） | 运营商不设价格（分发费无价即为 0）；拒绝要付费的受众规则与他人视频（`sharedVideo`） | 不产生预留 |
| 报名费、加值、赠送额度（`QB_FEE_CREDIT_ONBOARDING`）、运营商价格、银行设定 | 不在配置中 | 以后由人照常办理（报告列出） |
| 参数 | 白名单：只能写 QuizBuks 声明为"首发可设"的键。资金组的开关固定为 `FirstLaunchSwitches` 的首发值；金额类（价格、上限、费率）不能设，校验即拒绝 | 钱的参数只经四眼 |

测试断言首发运行之后（运行 `DONE`、学者已作答之前）以下各表零行：账本交易、钱包余额变动、预留。学者作答的线下兑换不动钱。

## 5. 账号

### 5.1 未接受邀请前，内容能否建在其名下

**结论：能，只需改一处 QuizBuks 的检查。**（需求方的确认结果出来之前，按"同意"写。）

1. **内容流程**：所有者都是操作人，流程体内没有"已验证邮箱"或"已接受邀请"的检查（`requiresVerifiedEmail` 只在 HTTP 入口检查）。以负责人的用户编号运行（首发代行），内容即属于他，与本人做的没有区别：
   - Quiz、问题、素材、版本、结构图、发布；
   - 文件上传者也记为他，`FILE_NOT_OWN` 成立。
2. **送审与上线的前提**：
   - 已批准的发起方：`DirectSponsors.write` 写出 APPROVED；
   - 运营商归属：`DirectSponsors.write` 写出固定归属。
3. **唯一的阻碍**：`QB_OPERATOR_SAVE` 要求负责人的邮箱已验证（`MEMBER_UNVERIFIED`）。这条规则是为了确保负责人是联系得到的真人。
   - 首发的内容阶段中改为：负责人可以尚未验证，但必须是本运行创建的账号。之后邀请寄到这个地址，接受邀请即验证。
   - 判断依据是运行记录，不是请求参数。
4. **"加入"发起方入口**（D66）：
   - 这是本人对条款的同意，不能代为点击；
   - 负责人以后在发起方页面管理内容时，自己点"加入"；
   - 在此之前，内容照常上线，不受影响；
   - 报告把它列为剩下的事项。
5. **如果需求方不同意**：代行只对尚无凭据的账号有效，所以不能等负责人接受后再代行。只能请负责人本人导入包、送审，首发只建运营商与账号。

### 5.2 邀请、密码、二次验证与待命角色（L3、B1、M2）

- 密码只由本人从邀请邮件设定（`SEC_USER_INVITE`，D63），平台不生成、不知道任何密码；所有权链（D48）因此成立。
- **代码核对**：没有任何该入口接受的角色的账号，在每个入口都会在角色检查时被拒（`NO_ROLE`）。所以账号要先有一个角色才能登录、绑定二次验证。
- **待命角色**：平台在首发中建角色 `FIRST_LAUNCH_PENDING`，不含任何权限，`requireMfa = true`，分配给每个首发账号。
  - 它不是受保护的授予（没有权限），授予它不受建立期影响。
  - **QuizBuks 的管理系统入口须接受它**（B1）：`QB_ADMIN_ROLES` 的缺省值加 `FIRST_LAUNCH_PENDING`，同步以下各处：
    - `application.yml:210`；
    - `deploy/quizbuks/README.md:320`；
    - `deploy/quizbuks/compose.dev.yaml:66`；
    - `.github/workflows/quizbuks-store.yml:86`。
  - **启动校验**：邀请链接的入口（`InvitationProcesses.DEFAULT_ENTRY = admin`）须接受待命角色，否则启动失败，并说明"在 `QB_ADMIN_ROLES` 中加入 `FIRST_LAUNCH_PENDING`"。
  - 本人接受邀请后在 `/admin/` 登录。因为角色要求二次验证，登录时即被要求绑定（`MFA_ENROLLMENT_REQUIRED`）。绑定后进入一个没有任何菜单的管理系统。
  - **不做等待页**（需求方不要页面）。零权限的账号登录管理系统的表现要实测：不报错，只是没有菜单（第 9 节第 5 项）；有问题即在平台的管理系统中修正。
  - **撤下**：只在该账号至少一项配置角色 `APPLIED` 之后撤下（M2）。
  - **运行结束**（`DONE` / `EXPIRED`）时：
    - 该角色停用（`enabled = false`）；
    - 平台以写入检查拒绝再启用或再分配它（只有首发运行器可以）；
    - 仍持有它的账号因此登录不了（已无意义），报告列出这些账号。
- **邀请邮件**（L2，平台的邀请模板）说明：
  - 登录的地址（`/admin/`）；
  - 将得到的角色与运营商；
  - "如果这不是你，请不要接受"。
- **成员的加入**：运营商的其他成员（示例中的 A、B）照常要求邮箱已验证，由任务在本人接受邀请之后加入。

### 5.3 授予与建立期（H3、M1）

- **互斥**：首发配置与以下设置互斥，同时设置即启动失败：
  - 引导管理员的环境变量（`JABIZ_BOOTSTRAP_ADMIN_USER` / `_PASSWORD`）：所以没有引导管理员，平台本来也没有停用用户的流程，不需要停用；
  - `jabiz.security.protected-grants.always=true`：没有建立期，首发授予不了。
- **授予**：每个账号一旦满足前提（已接受邀请、已绑定二次验证；超级管理员另加运维确认），系统即运行 `SEC_USER_ROLE_ASSIGN`。不为了顺序互相等待，不设闸（M1 的 (b)）。
  - 建立期内：当即生效（`APPLIED`）。
  - 建立期已结束（第二位 D51 意义上的管理员已授予）后的授予：`SEC_USER_ROLE_ASSIGN` 照常给出 `PENDING`，由已有的两位管理员照常审批。首发把 `PENDING` 视为已移交，不再跟进，列入剩下的事项。
- 只有一位超级管理员时（`two-account-setup` 的常见情形），建立期一直开着，全部授予当即生效。报告写明"建立期仍开着"及其含义（`launch-checklist` 中有意未满足的一项）。

## 6. 失败与报告

### 6.1 触发（M1）

**执行首发**须同时满足：
- `sec_user_version` 零行，包括墓碑：删过的用户也算"用过的库"；
- 没有运行记录。运行记录是单例、只追加，唯一约束保证至多一次运行。

**并发**：两个实例同时启动时，输掉唯一约束的一方识别为"另一实例正在执行或已完成首发"，不执行，等待运行记录到 `CONTENT_DONE`（期间同样不对外服务）。若运行记录变为 `FAILED`，它也退出。

**不运行**：核对模式（`jabiz.ops.verify-restore.enabled`）下，首发运行器与任务整体关闭。

### 6.2 先整份校验（M2）

启动时先读取并校验整份配置，一次报告全部问题（同 `PlatformCheck`），不写任何东西。有任何问题即**启动失败**，退出码非 0，日志逐条用人话说明（位置 | 问题 | 怎么改）。

**校验的内容**：
- 格式与引用：
  - 代号存在；
  - 恰一个默认运营商；
  - 每个运营商恰一个直营发起方；
  - 负责人是配置中的账号；
- 邮箱都在 `allowedEmailDomains` 之下；
- 至少一个 `report` 账号；
- 没有秘密形状的键；
- 包：
  - 能被 `PackagePlan` 读出；
  - 包中 Quiz 文件经 `QB_QUIZ_CHECK` 同样的检查；
  - 没有 `{url}` 图片；
- 第 4 节的钱的规则；
- 参数在白名单内且值合法；
- 发布的时间；
- 邮件已开启（`jabiz.mail.enabled`）；
- 邀请的入口接受待命角色；
- 与引导管理员、`.always` 不同时设置；
- 大小与数量上限（运营商数、每个包的 Quiz 数、总大小，均为参数）。

**就绪门**：`CONTENT_DONE` 之前，应用对外不服务：
- 就绪检查不通过，对外请求 503（`FIRST_LAUNCH_IN_PROGRESS`，带 `Retry-After`）；
- 内部的事件投递与任务照常运行。

### 6.3 出错

**内容阶段**（需求方的决定：不续做）：
- 每一步是平台的根流程 `FIRST_LAUNCH_STEP`（`entryOnly()`，只由首发运行器以 `executeAtEntry` 启动，同 `MfaController` 的做法），以子流程调用该步骤的业务流程。
  - 系统步骤可调 `OPEN`、`SUB_PROCESS_ONLY`、`INTERNAL`；
  - 代行步骤只能调 `OPEN`、`SUB_PROCESS_ONLY`，并且必须在处理器声明的清单中（M5）。
- 任一步失败：
  - 该步的事务回滚；
  - 运行记录追加 `FAILED`（失败的步骤、错误码、人话的原因）；
  - 日志 ERROR："首发在 <步骤>（<对象>）失败：<原因>。请修改配置或程序，清空数据库，重新启动。"
  - 应用以非 0 退出码退出。
- 之后的启动见到 `FAILED` 的运行记录，即以同样的说明再次启动失败，直到数据库被清空。
- 不留步骤表，不续做；每一步的写入照常有 `op_process` 与审计，以请求编号前缀区分（M3）。
- 已保存的文件字节随清空的数据库与文件目录一起清除（`operations.md` 的新节写明：清空数据库时也清空 `JABIZ_FILES_LOCAL_ROOT`）。

**等人阶段**（已对外服务，不清库）：
- 唯一的补救：某个账号尚未接受邀请，而配置中的邮箱写错了。改配置中该账号的 `email`（仍须在 `allowedEmailDomains` 之下），重启。
- 启动时比较配置与运行记录：
  - 差异只在尚未接受邀请的账号的 `email` 时：
    - 以平台现有的改邮箱路径，由系统更新该账号的地址（写代码时核对：持有受保护权限者的邮箱只经 `SEC_USER_EMAIL_SET`；待命账号不持有）；
    - 旧邀请随地址的改变作废，以 `SEC_USER_INVITE` 寄到新地址；
    - 运行记录追加一行修订：新旧配置哈希、账号代号。
  - 其他任何差异（含已接受邀请的账号的邮箱）：不执行，日志 WARN 说明"首发的内容阶段已完成，配置只接受改尚未接受邀请的账号的邮箱；其余请在管理系统中照常办理"。应用照常服务。
- 授予与成员加入每一步都按现状判断，失败（例如发信失败）在下一次任务时重试，不需要人处理；同一件事连续失败时日志 ERROR 并写入报告。

**运行记录**（`sys_first_launch_run` 单例 + `sys_first_launch_event` 只追加的状态事件）：
- 状态：`RUNNING` → `CONTENT_DONE` → `DONE`；另有 `FAILED`、`EXPIRED`（终态）；修订事件。
- 只存配置哈希、配置内代号与用户编号，不存邮箱与姓名。
- 账号的授予计划（代号、用户编号、配置的角色、是否 D51 意义上的管理员）在 `CONTENT_DONE` 时记入。等人阶段只按记录推进，配置文件只用来比较邮箱。

**启动时的判断**：

| 用户版本表 | 运行记录 | 做什么 |
|---|---|---|
| 零行 | 无 | 写运行记录，开始内容阶段 |
| 任意 | `RUNNING`（本实例之外） | 另一实例正在执行：等待（不对外服务） |
| 任意 | `FAILED` | 启动失败，说明"请修改后清空数据库重启" |
| 任意 | `CONTENT_DONE` | 等人阶段：比较邮箱（上面的补救），照常服务 |
| 不空 | 无 | 日志 WARN："已有 N 个用户，首发配置不执行。配置哈希 …。如需首发，请用全新的数据库。" |
| 任意 | `DONE` / `EXPIRED` | 不执行；配置仍在即每次启动 WARN"首发已于 … 结束，请撤下首发配置（`JABIZ_FIRST_LAUNCH_CONFIG`）"，并列入剩下的事项（M4） |

内容阶段中实例被强行停止（运行记录停在 `RUNNING` 而没有实例在执行）：下一次启动见到 `RUNNING` 且超过 `content-timeout`，即视为 `FAILED`，追加记录并按同样的说明启动失败。

### 6.4 试运行

`JABIZ_FIRST_LAUNCH_DRY_RUN=true`：只做整份校验，并打印步骤计划（每一步：谁来运行、调用哪个流程、对象是什么），不写任何东西，应用随即退出。运维先在测试系统上试运行，再正式启动。

### 6.5 报告（人话）

**日志**：每一步一行 INFO（"已建运营商 东京运营（默认），负责人 田中 美咲"），等人时每小时一行汇总（"等待：鈴木 一郎 尚未接受邀请（已寄出 3 小时）"）。

**超级管理员的指纹**（M3：不做页面）：账号已接受邀请并绑定二次验证、只等运维确认时，日志 WARN 给出：
- 账号的代号、姓名、邮箱、接受邀请与绑定二次验证的时刻；
- 指纹：运行编号、用户编号、二次验证绑定时刻的哈希的前 12 位。

运维与本人核对（例如电话确认"你是在 <时刻> 接受的邀请"）后，设置 `JABIZ_FIRST_LAUNCH_CONFIRM_ADMINS=<指纹>` 并重启。指纹绑定绑定时刻，所以本人重新绑定二次验证后，旧指纹即失效。

**完成**：全部账号的授予已 `APPLIED` 或已移交（`PENDING`），全部发布已 LIVE 或 SCHEDULED。日志写"系统已正式运行"，并寄邮件给 `report` 账号。邮件内容：
- 已建的运营商、发起方、发布（含页面链接）、各账号的角色与接受邀请的时刻；
- 建立期的状态：只有一位超级管理员时建立期仍开着，说明含义。报告由平台自己读 `ProtectedGrants` 写出，这代替"平台缺少建立期的只读视图"，不需要页面；
- **剩下的人工事项**（QuizBuks 给出清单）：
  1. 发布法律文本（`QB_LEGAL_PUBLISH`，正式文本来自业主，现在是标明的测试占位）；
  2. 各负责人在发起方页面点"加入"（D66）；
  3. 发布三条职责分离规则（`qb.sod.*`）；
  4. 发布等前提的开关（逐项列出前提：AI 说明发布后、分发费下限确认后等）；
  5. 待审批的授予（`PENDING`）；第二位超级管理员；
  6. 公司认证、发起方的发信服务器密码、运营商价格、银行设定、赠送额度、其他奖励方式；
  7. 撤下首发配置文件与 `JABIZ_FIRST_LAUNCH_*` 环境变量。

**等人太久**：超过参数 `jabiz.first-launch.remind-after`（缺省 3 天）仍未接受邀请的账号：
- 照常重发邀请（`SEC_USER_INVITE`），每个账号只重发一次；
- 日志与报告账号的邮件中提醒（报告账号自己未接受时，只写日志）。

到期限（第 2.1 节）即 `EXPIRED`。

## 7. 要改的规则与归属

### 7.1 平台：只新增四样东西（新决策 D82）

**为什么不能只在 QuizBuks 里做**：

| 要做的事 | 平台现状 | 只在 QuizBuks 里做的话 |
|---|---|---|
| 受控参数生效 | `ControlledParamGuard` 只认已批准变更的发布，系统身份也不例外 | 只能直接写参数表，违反 D40 |
| 审批的批准 | 须持有权限、且不是申请人 | 只能直接写审批表，违反 D23 |
| 以尚无会话的用户身份运行流程 | `ActingUser` 只在平台的控制器中使用 | 业务模块自己造 `RequestContext`，越过分层 |
| 运行记录、触发、就绪门、步骤根流程 | 没有 | 每个应用各写一份 |

角色授予不在其中：它用现有的建立期与受保护变更的审批（第 5.3 节）。

**① 运行记录与运行器**：
- 读 `JABIZ_FIRST_LAUNCH_CONFIG`、`_DRY_RUN`、`_CONFIRM_ADMINS`；
- 触发（第 6.1 节）、整份校验与启动失败、就绪门；
- `FIRST_LAUNCH_STEP` 步骤根流程；
- 任务 `jabiz.first-launch`（`JobDefinition`，推进流程为 `internal()`）；
- 首发待命角色及其写入检查；
- 报告邮件模板 `jabiz.first-launch.report`，邀请模板加首发的说明（L2）；
- 平台迁移（平台线上的下一个号，写代码时确定）：`sys_first_launch_run`、`sys_first_launch_event`。
  - 都有主键，只追加，以 `SealedColumnsHistory` 检查；
  - 只存用户编号与配置内代号，不存邮箱与姓名；`ErasureIT` 的全表搜索覆盖它们（M4）。
- 应用的扩展点 `FirstLaunchHandler`（core，同步），给出以下各项：
  - 读本应用的配置段；
  - 校验问题；
  - 步骤计划；
  - 首发可设的参数；
  - 代行可调用的流程清单；
  - 剩下的事项。

**② 参数初值发布**：子流程 `CONTROL_CHANGE_FIRST_LAUNCH_PUBLISH`（`subProcessOnly()`）。
- 只在内容阶段、只在 `FIRST_LAUNCH_STEP` 中可调用；
- 只发布本运行由系统提议、且在首发可设清单或 `FirstLaunchSwitches` 首发值中的变更；
- 发布人记为 `first-launch`，依据记配置哈希；
- `ControlledParamGuard` 认这种发布的写入授权。

**③ 首发审批**：子流程 `APPROVAL_FIRST_LAUNCH_DECIDE`。
- 只在内容阶段、只批准本运行的步骤开出的审批；
- 对每一级各记一条决定，决定人为 `first-launch`；
- 带限额的审批拒绝（L1）；
- 之后照常发出 `jabiz.approval.approved`。

**④ 首发代行**：`FirstLaunchActing`（平台的首发包内可见，ArchUnit 检查只有该包使用它；M5）。
- 只在内容阶段；
- 对象只能是本运行创建、尚无任何凭据的账号；
- 只能启动 `FIRST_LAUNCH_STEP`，而步骤只能调用处理器声明的清单中的流程。启动检查列出这份清单；
- 请求编号以 `first-launch-<运行编号>-<步骤键>` 开头，审计与 `op_process` 可以区分（M3）；
- 只带该步骤的平台权限，不带任何角色；
- 不发令牌、不建会话。

**取代与修订的决策**：在 `09-decisions.md` 新增 D82，不改原文。D82 逐条列出例外：

| 决策 | 原来 | D82 的例外 |
|---|---|---|
| D40 受控的业务参数 | 只经四眼修改 | 首发运行的内容阶段中，首发可设的键与 `FirstLaunchSwitches` 的首发值由参数初值发布生效；部署渠道 + 配置哈希算作第二个人。资金组只用首发值，金额类不可设 |
| D23 审批与职责分离 | 决定人须持权限、不是申请人 | 首发运行的内容阶段中，本运行的步骤开出的审批由首发审批批准 |
| D48 / D51 受保护授权与建立期 | — | 不修订。首发与引导管理员、`.always` 互斥 |
| D63 邀请 | — | 不修订；邀请模板加首发的说明 |
| D66 加入入口 | — | 不修订；首发不代为加入 |

**须同步的文档**（M6）：
- `docs/design/`：
  - `09-decisions.md`（D82）；
  - `10-security.md`（首发待命角色、建立期与首发、首发代行）；
  - `18-numbering-approvals-tasks.md`（首发审批）；
  - `04-temporal-append-only.md` §9 受控参数（参数初值发布）；
  - `11-ledger-events-jobs.md`（任务 `jabiz.first-launch`）；
  - `21-audit-retention.md`（审计中的首发标记、抹除范围）；
  - `13-observability-ops.md`（核对模式关闭首发、就绪门）。
- `docs/guide/`：新指南 `first-launch.md`。
- `CLAUDE.md`：
  - §4"审批与职责分离"（例外）；
  - "安全"（首发配置与引导管理员互斥）；
  - "启动即失败"（首发配置的校验）；
  - §6 构建与运行（环境变量）。

**平台先行**：按 CLAUDE.md 第 8 节，平台的改动先在 `1.3/platform` 上完成，带平台自己的测试与示范（示范应用 `app` 的一份小配置），再合并到 `1.3/quizbuks`。

### 7.2 QuizBuks

- `QbFirstLaunch`（`FirstLaunchHandler`）：读配置的 QuizBuks 段，产出第 2 节的步骤计划，声明首发可设的参数与代行可调用的流程清单。
- `PackagePlan`：发起方包计划的 Java 版，与 `package.ts` 用同一组样例对照。
- `OperatorProcesses.checkNewMember`：首发的内容阶段中，负责人可以是本运行创建、尚未验证的账号（第 5 节）。
- **管理系统入口**（B1）：`QB_ADMIN_ROLES` 的缺省值加 `FIRST_LAUNCH_PENDING`，同步以下各处：
  - `application.yml:210` 与其上方的注释；
  - `deploy/quizbuks/README.md`（:165 的说明与 :320 的表）；
  - `deploy/quizbuks/compose.dev.yaml:66`；
  - `.github/workflows/quizbuks-store.yml:86`。
- `FirstLaunchSwitches.Row` 加 `prerequisite`（等前提的行只提议），标出：
  - `qb.help.enabled`、`qb.ai.enabled`：AI 说明发布之后；
  - `qb.distribution.billing.enabled`：分发费下限确认之后；
  - `qb.exposure.billing.enabled`：价格范围确认之后。
  其余行以首发值直接生效。
- 首发可设的参数清单（非资金、非金额，如 AI 的每日次数、审核开关 `qb.review.sponsor.auto`）。
- 不需要迁移（V134 还给总控）。
- 消息（三种语言）：报告的剩下事项、校验问题。
- 文档：
  - `operations.md` 新节"首发配置"（含"出错即清空数据库与文件目录后重启"与等人阶段的改邮箱）；
  - §7.49 的例外；
  - `two-account-setup.md` 指向首发配置（两账号仍是人数与角色的约定）；
  - `launch-checklist.md`、`first-launch-progress.md`。

## 8. 安全分析

**配置被篡改**：
- 只在从未有过用户的库上生效（第 6.1 节），之后的启动只警告、不执行。所以篡改已经在运行的系统的配置没有作用。
- 等人阶段只接受一种改动：尚未接受邀请的账号的邮箱。它仍受域名白名单约束，并记入运行事件（新旧哈希）。
  - 能改配置的人可以借此把一个尚未接受的账号改寄到同域名的另一地址；
  - 但该账号若是超级管理员，仍须运维以指纹确认；
  - 而能改部署配置的人本来就能改环境变量，威胁等同，不更差。
- 运行记录、每一步的 `op_process`（请求编号前缀）与审计都标出"首发配置"与配置哈希：
  - 参数的发布人、审批的决定人都是 `first-launch`；
  - 事后可以用存档的配置核对哈希。
- 配置不含秘密，泄露只暴露邮箱与内容。

**写错邮箱，使别人得到受保护的角色**：
- 邮箱只能在 `allowedEmailDomains` 之下，笔误寄到外部域名在校验时即被拒。
- 邀请在内容就位后才寄，授予只给接受了邀请（只有该地址的收件人能设定密码）并绑定了二次验证的账号，从不给一个没人控制的账号。
- 超级管理员的授予另须部署渠道的确认：运维从日志读出指纹，与本人核对后设置环境变量并重启。错的收件人即使接受了邀请，也拿不到管理权限。（是否保留此步待需求方确认，先按保留写。）
- 邀请邮件写明将得到的角色与运营商，并说明"如果这不是你，请不要接受"。
- 试运行打印每个账号的邮箱，部署前由人核对。
- 报告列出每个账号接受邀请的时刻。
- **剩余风险**：同一域名内写错了非管理员的邮箱，而错误的收件人接受了邀请。他会得到该账号的角色（运营商、财务、内容），负责人账号还会带着已上线的直营发起方。缓解：
  - 他要点"加入"才能管理发起方的内容；
  - 超级管理员看到报告后可以撤下（`SEC_USER_ROLE_ASSIGN`、`SEC_USER_SESSIONS_REVOKE`）。
  这与现在的人工流程中输错邮箱的风险相同。
- 不做"首次登录确认"的勾选，改为域名白名单与超级管理员的部署确认（总控同意）。

**首发特有的能力的窗口**：
- 参数初值发布、首发审批、首发代行只在内容阶段有效；
- 内容阶段在启动时一口气完成，期间不对外服务；`CONTENT_DONE` 之后永久关闭；
- 代行只对尚无凭据的账号，而邀请在 `CONTENT_DONE` 之后才寄出；
- 待命角色在运行结束后停用，平台拒绝再启用或再分配。

**首发代行（M3、M5）**：
- 只在平台的首发包中，ArchUnit 检查；
- 对象与可运行的流程由平台强制，启动检查列出；
- 请求编号可区分，测试断言；
- 不发令牌，所以外部无法得到这种上下文。

**职责分离规则**：
- 不由首发发布。它们由系统提议，所以以后任何一位持有 `control.publish` 的人都能发布：`CONTROL_SAME_PERSON` 只排除提议人。
- 内容由配置决定、发布由一个人确认，相当于两方，可以接受。报告中写明这一点。

**个人信息（M4）**：首发的表只存用户编号与代号；配置文件含邮箱，完成后每次启动提醒撤下。

需要做 `/security-review`：涉及认证、权限、审批。

## 9. 测试清单

**平台**（`1.3/platform`，集成测试连真实 PostgreSQL）：
1. **空库与示范配置**：
   - 启动后，就绪门在 `CONTENT_DONE` 前返回 503，之后通过；
   - 运行记录与事件齐全；参数已发布（发布人 `first-launch`）；
   - 邀请在 `CONTENT_DONE` 之后才进入 Outbox。
2. **触发**：
   - `sec_user_version` 有行（包括只有墓碑）且无运行记录：不执行，日志 WARN；
   - `DONE` 后再启动：不执行，配置仍在时 WARN；
   - 核对模式下：运行器与任务都不运行。
3. **并发**：两个实例同时开始，只有一个运行记录；另一个识别为"另一实例正在执行"，等到 `CONTENT_DONE` 才对外服务。
4. **内容阶段失败**：
   - 制造一步失败：事务回滚、运行 `FAILED`、日志说明、退出码非 0；
   - 再次启动仍失败，说明相同；
   - 停在 `RUNNING` 且超时：视为 `FAILED`。
5. **登录与二次验证**（L3、M3）：
   - 只有待命角色的账号能在 `/admin/` 登录（入口接受待命角色）、被要求绑定二次验证、绑定后取得令牌；
   - 以这个令牌打开管理系统的页面不报错、没有菜单；
   - 入口不接受待命角色时启动失败（B1）。
6. **授予**：
   - 未接受邀请、或已接受但未绑定二次验证：不授予；
   - 两者都满足：建立期内当即生效（`APPLIED`）；
   - 超级管理员在指纹确认前不授予，确认后授予；本人重新绑定后，旧指纹失效；
   - 配置中有两位超级管理员（M1）：第二位授予后建立期结束；之后才满足前提的账号得到 `PENDING`，列入剩下的事项，由两位管理员批准后生效；
   - 与 `.always`、引导管理员同时设置：启动失败。
7. **待命角色**（M2）：
   - 配置角色 `APPLIED` 之前不撤下（`PENDING` 时仍保留）；
   - 运行结束后停用，再启用或再分配被拒。
8. **首发审批**：
   - 只在内容阶段、只批准本运行步骤的审批；
   - 多级审批每级各一条决定；带限额的拒绝；
   - `CONTENT_DONE` 后调用被拒；
   - 申请人的检查在运行外照旧。
9. **参数初值发布**：
   - 只发布本运行、白名单内的提议；
   - `CONTENT_DONE` 后被拒；
   - `ControlledParamGuard` 对其他写入照旧拒绝。
10. **首发代行**：
    - 对象已有凭据时步骤失败；
    - 不在清单中的流程被拒；
    - 请求编号带 `first-launch-` 前缀；
    - ArchUnit：只有首发包使用代行；
    - `CONTENT_DONE` 后被拒。
11. **校验**：一次报告全部问题，有问题即启动失败。试运行只打印计划，数据库无变化，应用退出。
12. **等人阶段改邮箱**：
    - 改尚未接受邀请的账号的邮箱后重启：旧邀请作废、新邀请寄出、修订事件记新旧哈希；
    - 改了别的（含已接受账号的邮箱）：不执行、WARN、照常服务。
13. **期限**：到期 `EXPIRED`，待命角色停用，报告列出未完成的账号。
14. **个人信息与封存**：首发的表不含邮箱与姓名；`ErasureIT` 覆盖；`SealedColumnsHistory`。
15. **语句数**：`StatementBudgetIT` 不受影响（首发不在标准写入路径上）。

**QuizBuks**（`1.3/quizbuks`）：
16. `PackagePlan` 与 `package.ts` 对 `PackageFixtures` 的全部样例给出相同的步骤（前后端各一个测试，共用样例文件）。
17. **端到端的集成测试 `FirstLaunchIT`**（空库 → 启动 → 模拟接受邀请 → 任务完成 → 全部就位 → 学者可答题）：
    - 以第 1.3 节的示例（测试资源）在空库上启动；
    - 内容阶段完成：
      - 两个运营商（东京为默认）、两个直营发起方（APPROVED、固定归属）；
      - 资料、结构图 ACTIVE、Quiz、三道题、图片（上传者为负责人）、发布 LIVE；
      - 审批的决定人为 `first-launch`、内容哈希相符；
      - 运行 `CONTENT_DONE`；邀请此时才寄出；
    - 模拟 B、C 接受邀请（`MailTokens` 的令牌），以待命角色在 `/admin/` 登录并绑定二次验证 → B、C 得到角色、待命角色撤下；A 只接受邀请 → 没有角色；
    - A 绑定后：先得到 `QB_OPERATOR` 并加入运营商，超级管理员等确认；设置确认的指纹后得到 `QB_ADMIN_SUPER`；
    - 运行 `DONE`：报告邮件寄给 A，含剩下的事项与建立期仍开着的说明；日志有"系统已正式运行"；
    - 账本交易、钱包变动、预留：零行；
    - 一位学者自助注册、找到该发布、作答并通过（得到线下兑换的结果页）；
    - 再次启动：不重做，日志 WARN 提醒撤下配置。
18. 校验：
    - 下列各项分别被拒：
      - 非线下兑换的奖励方式、非 0 档位；
      - 要付费的受众规则、他人视频；
      - `{url}` 图片、秘密形状的键；
      - 白名单外的参数、金额类参数；
      - 域名外的邮箱、两个默认运营商、负责人不在账号中；
    - 一次报告全部问题。
19. 内容阶段某一步失败（制造一个包中的 Quiz 在 `QB_QUIZ_IMPORT` 被拒）：运行 `FAILED`、启动失败、日志写明失败的发起方与 Quiz，以及"修改后清空数据库重启"。
20. `MEMBER_UNVERIFIED`：运行外照旧拒绝；内容阶段中只对本运行创建的负责人放行；`CONTENT_DONE` 后照旧拒绝。
21. 等前提的开关：只提议、不生效；职责分离规则：只提议。
22. 场景回放：首发的一份小配置，快照覆盖运行记录、事件与报告。

**Playwright**（e2e，CI 的 `e2e` 作业）：
- 以示例配置启动的应用上，学者打开发布并答题；
- 负责人从邀请邮件设定密码，在 `/admin/` 登录并绑定二次验证，得到角色后在 `/operator/` 看到自己的运营商；
- 邮件取自测试的邮件服务器。

## 10. 风险与未决事项

- **需求方确认**（结果出来之前按"都同意"写）：
  1. 负责人在接受邀请前即任运营商负责人，内容在其名下建好并上线（第 5.1 节）。不同意的后果见第 5.1 节第 5 条。
  2. 运维确认超级管理员（核对指纹后设一个环境变量并重启）。
  3. 首发只允许线下兑换。
- **已定**：
  - 公司认证不由首发声明；
  - 不做"首次登录确认"的勾选，不做等待页；
  - 出错即修改、清库、重启，不续做（需求方）。
- 零权限账号登录管理系统的表现要以集成测试实测（第 9 节第 5 项），有问题在平台的管理系统中修正。
- **`PackagePlan` 的移植**：这是工作量最大的一项。两份实现以共用样例对照，包的格式变化时两边都要改（在 `package.ts` 与 Java 中互相注明）。
- **内容在测试系统上先做好**：首发配置依赖测试系统可用来制作并导出包。导出的包中图片已内嵌，不需要另外的文件目录。

## 11. 审查意见的处理

**第一稿的审查**（Fable）：

| 编号 | 意见 | 处理 |
|---|---|---|
| H1 | 受限的配置修订；超级管理员的部署确认；邮箱域名 | 需求方决定不续做：内容阶段的修订取消（出错即清库重来）；等人阶段只允许改尚未接受邀请的账号的邮箱（第 6.3 节）。部署确认见第 6.5 节、第 8 节；域名见第 1.3 节、第 6.2 节 |
| H2 | 内容阶段与等人阶段；硬期限；代行只对尚无凭据的账号 | 第 2.1 节、第 2.2 节、第 7.1 节 ②–④ |
| H3 | 删去首发授权与对 D48 / D51 的修订 | 第 5.3 节 |
| H4 | 参数白名单、资金组固定、金额不可设；"不产生账本交易与预留"并断言；只允许线下兑换；不做公司认证 | 第 4 节、第 3.3 节、第 9 节第 17 项 |
| H5 | 步骤根流程、互斥、`PAUSED` / `STOPPED`、试运行只校验 + 计划 | 需求方决定不续做：保留 `FIRST_LAUNCH_STEP`（区分与限制代行），删去步骤表与 `PAUSED` / `STOPPED`，改为 `FAILED` 即启动失败；互斥靠单例运行记录（第 6.1 节、第 6.3 节）；试运行见第 6.4 节 |
| M1 | 触发以 `sec_user_version` 零行 + 无运行记录；核对模式关闭 | 第 6.1 节 |
| M2 | 校验失败即启动失败；就绪门 | 第 6.2 节 |
| M3 | 代行可区分 | 第 7.1 节 ④，第 9 节第 10 项 |
| M4 | 个人信息与封存；完成后提醒撤下配置 | 第 7.1 节 ①、第 6.3 节的判断表 |
| M5 | 平台强制代行的对象与流程 | 第 7.1 节 ④、第 6.3 节 |
| M6 | 须同步的文档；D82 逐条例外 | 第 7.1 节 |
| L1 | 多级审批与限额 | 第 2.3 节、第 7.1 节 ③ |
| L2 | 邀请模板改动列入平台 | 第 5.2 节、第 7.1 节 ① |
| L3 | 无角色账号能否登录并绑定二次验证 | 代码核对：不能（`NO_ROLE`）。方案：首发待命角色（第 5.2 节），集成测试实测（第 9 节第 5 项） |
| L4 | 版本线与迁移无问题：1.3 是最新线，兼容新增留在 1.3；平台现为 V66、QuizBuks 现为 V133，V134 可用 | 平台的迁移用 1.3 平台线的下一个号（现为 V67，写代码时确认）。QuizBuks 已不需要迁移，V134 收回 |
| L5 | 并入期限 | 第 2.1 节 |

**第二稿的复审**（Fable）与需求方的决定：

| 编号 | 意见 | 处理 |
|---|---|---|
| B1 | 管理系统入口只收四个角色，须接受待命角色 | 第 5.2 节、第 7.2 节（`QB_ADMIN_ROLES` 与各处同步）；第 6.2 节（入口不接受即启动失败） |
| B2 | 第 4 步只建账号，`CONTENT_DONE` 后再邀请；"本运行创建的账号" | 第 2.3 节第 4、13 步；第 2.2 节、第 5.1 节、第 7.1 节 ④；第 9 节第 1、17、20 项 |
| M1 | 两位以上管理员：不设闸，`PENDING` 交已有两位管理员审批，算已移交 | 第 5.3 节、第 6.5 节；第 9 节第 6 项 |
| M2 | 待命角色只在配置角色 `APPLIED` 后撤下；运行结束后不可再启用或分配；删去备选 | 第 5.2 节；第 9 节第 7 项 |
| M3 | 不做等待页；运维从日志读指纹；零权限登录的表现实测 | 第 5.2 节、第 6.5 节；第 9 节第 5 项 |
| M4 | 对照表补 L4 | 见上表 L4 行 |
| 补充 | 首发的表只存用户编号与代号；并发输掉的一方识别为另一实例；运行器不在事件投递启动前阻塞 | 第 6.3 节（运行记录）、第 6.1 节（并发）、第 2.3 节（推进） |
| 需求方 | 出错即修改、清库、重启，不续做；等人阶段的最简补救 | 第 2.1 节、第 6.3 节 |

## 12. 实施拆分与顺序

**前提**：需求方确认第 10 节的三项之后才开工。
- 工作量以"一个会话的工作日"估计，不含 Fable 审查与 CI 等待；
- 每个 PR 都带自己的测试；
- 平台的 PR 照常在完成后做 `/code-review` 与 `/security-review`。

### 12.1 平台（`1.3/platform`，先合并）

| PR | 分支 | 内容 | 依赖 | 工作量 | 建议 |
|---|---|---|---|---|---|
| P1 | `1.3/phase-17zz23a-first-launch-runner` | 见下 | — | 3 天 | S1 |
| P2 | `1.3/phase-17zz23b-first-launch-content` | 见下 | P1 | 3 天 | S1 |
| P3 | `1.3/phase-17zz23c-first-launch-accounts` | 见下 | P1（可与 P2 并行） | 3–4 天 | S1，或交另一会话与 P2 并行 |
| P4 | 同步 `1.3/platform` → `1.3/quizbuks` | 合并与适配 | P1–P3 合并 | 0.5 天 | S1 |

**P1 运行器**：
- 运行记录与事件（平台迁移 V67）；
- `FirstLaunchHandler` 扩展点、配置的读取与哈希；
- 触发与并发、整份校验与启动失败、试运行；
- 就绪门；
- `FIRST_LAUNCH_STEP`、`FAILED` 与超时；
- 核对模式关闭；
- 示范应用 `app` 的一份小配置。

**P2 内容阶段的三项能力**：
- 参数初值发布；
- 首发审批（多级、限额）；
- 首发代行（对象与流程清单、请求编号前缀、ArchUnit）；
- `CONTENT_DONE` 后关闭；
- D82 与第 7.1 节列出的设计文档、`CLAUDE.md` 的同步。

**P3 等人阶段**：
- 账号的创建；
- 待命角色与其写入检查、入口须接受待命角色的校验；
- `CONTENT_DONE` 后的邀请与邀请模板；
- 任务 `jabiz.first-launch`：授予、`PENDING` 移交、超级管理员的指纹确认、改邮箱的补救、期限；
- 报告邮件；
- 零权限账号登录管理系统的实测（第 9 节第 5 项）。

P2 与 P3 改的文件基本不重叠：P2 是控制变更与审批，P3 是安全与任务。两者都只依赖 P1，可以由两个会话并行。

### 12.2 QuizBuks（`1.3/quizbuks`）

| PR | 内容 | 依赖 | 工作量 | 建议 |
|---|---|---|---|---|
| Q1 | `PackagePlan`：发起方包计划的 Java 版，与 `package.ts` 共用 `PackageFixtures` 样例对照的前后端测试 | 无（可立即开始，与平台并行） | 3 天 | S2 或 S5（熟悉发起方包 F-C4 的会话） |
| Q2 | 小改动：<br>• `FirstLaunchSwitches` 的 `prerequisite` 标记与首发可设的参数清单；<br>• `QB_ADMIN_ROLES` 缺省加待命角色及各处同步 | 无（`FIRST_LAUNCH_PENDING` 的名字先定为常量；在 P3 合并前不影响现状） | 0.5 天 | 与 Q1 同一会话，或 S1 |
| Q3 | `QbFirstLaunch` 处理器：<br>• 配置的 QuizBuks 段；<br>• 校验（钱、白名单、包、图片）；<br>• 步骤计划；<br>• 代行流程清单；<br>• `MEMBER_UNVERIFIED` 的首发例外；<br>• 报告的剩下事项与消息（三种语言） | P4、Q1、Q2 | 3–4 天 | S1 |
| Q4 | 测试与文档：<br>• `FirstLaunchIT`（第 9 节第 17–22 项）、场景回放、Playwright；<br>• `operations.md` 新节、§7.49、`two-account-setup.md`、`launch-checklist.md`、`first-launch-progress.md` | Q3 | 2–3 天 | `FirstLaunchIT` 由 S1 与 Q3 一起写；Playwright 与文档可交另一会话并行 |

### 12.3 顺序与关键路径

```
P1 ──┬── P2 ──┐
     └── P3 ──┴── P4 ── Q3 ── Q4
Q1、Q2（与平台并行）────────┘
```

- **关键路径**：P1 → P2/P3（并行）→ P4 → Q3 → Q4。
  - 约 3 + 4 + 0.5 + 4 + 3 ≈ 14.5 个工作日；
  - P2、P3 由一个会话先后做，则约 17.5 个工作日。
- Q1 与 Q2 不依赖平台，确认后即可交出，在 P4 之前完成。
- 每个平台 PR 合并后照常同步到 `1.3/quizbuks`。P4 是最后一次同步，之后才开始 Q3。

### 12.4 Q1 的实施记录（`PackagePlan`）

- **位置**：包 `com.jabiz.quizbuks.sponsorpackage`，纯 Java、同步、不读数据库、不上传文件（只给出要上传什么）：
  - `PackagePlan`：`read`（即 `readPackage`：第 1、2 版，问题 `NOT_PACKAGE` / `NEWER_VERSION` / `SHAPE` 带位置）与 `plan`（即 `planImport`：步骤与检查页的各项清单）；
  - `QuizFileCut`：Quiz 文件的切分（`firstBatch`、`partsOf`、`planContent`、`planBatches`、`splitQuestions`、`usedFiles`）；
  - `PublicationSettingsFile`：发布设置文件的读取（`parseSettings`）与新草稿的 `QB_PUBLICATION_SAVE` 输入（`formOfSettings` + `inputOf`，`saveInput`）；
  - `PackageStepInputs`：每一步的 `QB_PACKAGE_IMPORT_STEP` 输入（即页面的 `inputOfStep`）。调用方先按 `QuizFileCut.usedFiles` 上传内容的文件（资料的图片同样），把结果交给它；发布的开始时间由调用方给出。
  - `PackageJson`：按 JavaScript 的写法计请求的字节（`JSON.stringify` 的数字与转义、孤立的代理项、`trim` 的空白），按字节切分因此两边一致。
- **页面只拆了一处**：`package.ts` 的 `stepInput` 拆成纯函数 `inputOfStep` 与上传两部分，行为不变。
- **对照**（第 9 节第 16 项）：
  - 输入 `quizbuks-web/sponsor/src/profile/package-plan-cases.json` 由 `PackagePlanCasesWriterTest` 从 `PackageFixtures` 写出（`PACKAGE_PLAN_CASES_WRITE=1`，平时不运行）：14 例，含大题库的多步清空、按字节切、放不进任何请求的题与资料、超过上限的 Quiz、孤立的发布、未共享的视频、已提交过图的账户（按节点键、再按名称绑定）、只导入资料、第 1 版、三种读错、数字与转义，以及 JavaScript 自己的读法（数字样的键先列、任意长的题号、`.` 匹配 U+0085、空键不算、名称是数字）。Java 读包时先把对象的键排成 JavaScript 的顺序（`PackageJson.jsOrdered`）。两个大样例只写一次（`packages`，用例以 `packageRef` 引用）。
  - 期望 `package-plan-expected.json` 由页面的代码写出（`PACKAGE_PLAN_CASES_UPDATE=1 pnpm vitest run package.plan.cases`），页面是参照；Java 的 `PackagePlanCasesTest` 与页面的 `package.plan.cases.test.ts` 都与它比较。摘要：每一步的种类、键与各字段，文档为题数、字节数与摘要（键排序后的 JSON 的 SHA-256），每一步的输入（发布的写全、其他为摘要，前面步骤的结果以步骤键命名）。
  - `quizbuks` 的 `test` 任务把两个文件声明为输入，改了即重跑。
- **空的版本键**（总控 2026-11-18 决定为缺陷）：版本的键为空字符串时，页面原先把它当作当前内容，两个内容步骤同键（`quiz:q1:c:content`），导入记录把第二个当作已做而静默跳过。现在页面的 `readPackage` 与 Java 的 `PackagePlan.read` 都把它报为格式问题（`shape`，位置 `quizzes[i].versions[j]`，沿用三语的"发起方包在这些地方有损坏"），共用样例"a version with an empty key"先让两边失败再修好；首发的校验因此自然拒绝。
- **每步的结果**（给 Q3）：`PackageStepInputs.inputOf` 的 `results` 是各步 `QB_PACKAGE_IMPORT_STEP` 输出的 `result`（继续的导入取 `QB_PACKAGE_IMPORT_START` 的 `done`），按步骤键；读的键：内容步骤的 `quizId`、版本步骤的 `versionId`、图步骤的 `treeId` 与 `nodeIds`（包中节点键 → 节点编号）。
- **改动的规则**：改 `package.ts`、`content/bank.ts` 的切分、`publishing/settings.ts` 的 `parseSettings` / `formOfSettings` 或 `publishing/api.ts` 的 `inputOf` 时，同一个 PR 里改对应的 Java，重写期望并让两边都通过；包或 Quiz 文件的新字段若影响切分或输入，先在 `PackagePlanCasesWriterTest` 加一例。
