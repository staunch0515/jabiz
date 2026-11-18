# F-LAUNCH 首发配置：一个配置文件，启动即完成首发初始化：实施计划（S1，2026-11-18，待 Fable 审查与需求方确认）

状态：计划，未写代码。分支 `1.3/quizbuks-launch-wizard-plan`（只改 `docs/**`）。本文取代同一 PR 上较早的"首发向导页"计划（已撤回，不送审）。

**需求方的要求**（总控 2026-11-18 转述，需求方补充为准）：
- 不要页面，界面上零按钮。运维提供一个不含秘密的首发配置文件，路径由环境变量给出。
- 启动时如果用户表一个用户都没有（全新库），执行首发初始化；有任何用户即跳过，并在日志中说明。
- 系统自动完成以下各项：
  - `QB_SETUP`；
  - 受控参数直接以配置值生效；
  - 邀请账号；
  - 账号接受邀请并绑定二次验证后，按顺序授予角色；
  - 运营商（可多个，含默认）及其直营发起方；
  - 发起方的资料、问题、Quiz、素材、知识结构图与发布，发布直接上线；
  - 三条职责分离规则与等前提的开关留待；
  - 完成后在日志与给 A 的邮件中报告"系统已正式运行"和剩下的人工事项。
- 人只做一件事：每个账号的本人从邀请邮件设定密码，并绑定二次验证。

**依据**：代码 `origin/1.3/quizbuks`。核对的要点：
- 每个内容流程都以操作人为所有者，没有 `sponsorId` 输入：
  - `QuizProcesses:274`；
  - `PublicationProcesses:597`、`PublicationEditing:490`；
  - `TreeProcesses:228`；
  - 内容数据视图按 `ownerId = actorId` 限定（`ContentConfig:56-69`）。
- 发起方包（§7.33，`PackageImportProcesses`）：一次事务做一步、按发起方记录进度；计划由浏览器做（`package.ts`），服务器不读包。
- `QB_OPERATOR_SAVE` 要求负责人邮箱已验证（`MEMBER_UNVERIFIED`，`OperatorProcesses:454-467`）。`DirectSponsors.write` 直接写出已批准的直营发起方，不记同意。
- `ControlledParamGuard` 对系统身份也不例外。`CONTROL_CHANGE_PUBLISH` 只拒绝提议人本人（`ControlChanges:179-183`）。
- `APPROVAL_DECIDE` 要求决定人持有该级权限、且不是申请人（`ApprovalProcesses:145-152`）。
- `ProtectedGrantWriteCheck` 与 `BootstrapGrant` 的先例（`BootstrapAdmin`、`ProtectedGrants:345-375`）。
- 流程的权限、二次验证与已验证邮箱只在 HTTP 入口检查（`ProcessController:100-110`）。在进程内执行时，只有流程体内自己的检查仍有效。
- `FileUploadService.storeGenerated`；`QuizEditing.requireOwnFile` / `FILE_NOT_OWN`（文件须由 Quiz 的所有者上传）。
- `FirstLaunchSwitches`、`PublicationReadiness:208-245`、`PublicationPrepayments`、`PublicationEditing:307-321`（未写奖励方式即按 PLATFORM，首发时被拒）。
- `ConsentChecks`：只在有生效的法律文本时询问，`QB_SETUP` 只建草稿。

## 0. 结论先行

1. **能做到"启动即上线"**：全部内容都在首个负责人接受邀请之前建好并上线。人只做"接受邀请 + 绑定二次验证"。之后的角色授予由系统自动完成。
2. **平台必须改，收窄为一项能力"首发配置"**（新决策 D82）。不改平台就必须在 QuizBuks 里直接写参数表或审批表，这违反 CLAUDE.md。理由在第 7 节。
3. **QuizBuks 的改动**：
   - 首发处理器；
   - 发起方包的服务器端读取（把 `package.ts` 的计划移植到 Java）；
   - 首发期间负责人可以尚未验证邮箱；
   - `FirstLaunchSwitches` 标出等前提的行；
   - 迁移 V134。
4. **审核**：配置中的内容视为已审，由平台的"首发审批"作出批准。审批记录照常绑定内容哈希，决定人记为"首发配置"与文件哈希。
5. **钱**：首发配置一概不涉及资金。
   - 发布只能用不动钱的奖励方式：线下兑换，或不带码池的数字奖品；
   - 不能用要付费的受众规则或他人视频；
   - 运营商不设价格；
   - 不加值、不赠送额度。
6. **失败**：先整份校验（不写任何东西），再分步执行。每步一个事务、记录进度、可续做。配置在中途被改了即停下，用人话说明。新系统出了问题，最省事的恢复是清空库后重来（第 6 节）。

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
- `JABIZ_FIRST_LAUNCH_DRY_RUN=true`：只校验并试运行，不留下任何东西（第 6.3 节）。

**文件内容**：
- 不含秘密：没有密码、没有数字奖品的码、没有发信服务器的密码。校验发现形似秘密的键（`password`、`secret`、`codes` 等）即拒绝。
- 主文件的 SHA-256 与内容目录的清单哈希（按相对路径排序后逐个文件的哈希再哈希）合成"配置哈希"，写入运行记录、日志与审计（第 8 节）。

### 1.2 复用现有的导入格式

| 数据 | 格式 | 复用的程度 |
|---|---|---|
| 问题、Quiz、素材、图片 | 发起方包 `quizzes[].versions[].file`，即 `quizbuks.quiz` 文件（`QuizFileFormat`），图片以 base64 内嵌在 `files` 中 | 完全复用：服务器早已能读 Quiz 文件（`QB_QUIZ_CHECK` / `QB_QUIZ_IMPORT`） |
| 知识结构图 | 发起方包 `trees[]`（节点、条件） | 完全复用。平台没有单独的结构图导入，结构图只是包的一步（`QB_TREE_CREATE` + `QB_TREE_DRAFT_SAVE`） |
| 发起方简介与页面 | 发起方包 `profile`，即资料包 `quizbuks-profile`（`ProfilePackage`） | 完全复用（`QB_PROFILE_IMPORT`） |
| 发布（设置、主题、受众、结构图节点） | 发起方包 `publications[]`（`settings` 即 `QB_PUBLICATION_SAVE` 的输入） | 复用；上线时间与奖励方式在主文件中补充（包里没有） |
| 账号、运营商、参数、奖励方式、发起方的公司认证 | 主文件 | 包不带这些（§7.33：状态、审核、钱、人员、运营商归属都不带） |

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
# 首发配置：只在全新的库（用户表为空）上执行一次；之后启动会忽略本文件并在日志中警告。
# 不要写任何密码或兑换码：本文件会随部署保存，哈希会记入审计。
version: 1

# 站点：报告邮件与日志的语言
site:
  language: zh

# 账号：只发邀请，密码由本人从邀请邮件设定；接受邀请并绑定二次验证后才授予角色。
# grantOrder 越小越先授予；超级管理员必须最后（校验强制）。
accounts:
  - key: B                       # 配置内的代号，下面用它引用
    email: b.tanaka@example.co.jp
    name: 田中 美咲
    language: ja
    roles: [QB_ADMIN_FINANCE, QB_ADMIN_CONTENT, QB_OPERATOR]
    grantOrder: 1
  - key: C
    email: c.yamada@example.co.jp
    name: 山田 健
    language: ja
    roles: [QB_OPERATOR]
    grantOrder: 2
  - key: A
    email: a.suzuki@example.co.jp
    name: 鈴木 一郎
    language: ja
    roles: [QB_ADMIN_SUPER, QB_OPERATOR]
    grantOrder: 9
    report: true                 # 首发完成的报告寄给这个账号（至少一个）

# 运营商：第一个 default: true 的是新发起方的默认运营商（只能有一个）。
# lead 即负责人，也是该运营商的直营发起方的账号。members 在本人接受邀请后加入。
operators:
  - key: tokyo
    name: 东京运营
    default: true
    lead: B
    members: [A]
  - key: kansai
    name: 关西运营
    lead: C
    members: [B]

# 直营发起方：每个运营商一个，内容在 sponsors/<key>/package.json。
sponsors:
  - operator: tokyo
    package: sponsors/hq/package.json
    # 发起方可用的奖励方式（QB_SPONSOR_REWARD_METHODS_SET）；首发时 Kudos 关闭，PLATFORM 不能写。
    rewardMethods: [OFFLINE_REDEEM]
    # 公司认证：由部署方声明（见第 3.3 节，待需求方确认），不写即不认证。
    verification:
      domain: example.co.jp
    # 包中各发布的补充，按包内的发布 key 对应。
    publications:
      spring-quiz:
        rewardMethod: OFFLINE_REDEEM
        redeemDescription: 在东京店的收银台出示答题结果页即可领取纪念徽章。
        startAt: launch          # launch = 首发运行的时刻；也可写 2026-12-01T09:00+09:00
        endAt: 2027-03-31T23:59+09:00
  - operator: kansai
    package: sponsors/kansai/package.json
    rewardMethods: [OFFLINE_REDEEM]
    publications:
      kansai-history:
        rewardMethod: OFFLINE_REDEEM
        redeemDescription: 在大阪店出示结果页领取明信片。
        startAt: launch
        endAt: 2027-01-31T23:59+09:00

# 受控参数：以配置值直接生效（首发配置的哈希记为发布依据）。
# 不写的参数取 FirstLaunchSwitches 的首发值或声明的缺省值；FirstLaunchSwitches 中标为"等前提"的行
# （AI 说明、分发费下限等）一律只提议、不生效，写在这里也一样（校验时提示）。
params:
  qb.review.sponsor.auto: true
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
    { "key": "spring-quiz", "quiz": "spring", "version": 1,
      "settings": { "name": "春季答题", "timeLimitSec": 300, "passScore": 2, "budgetCap": 0, "maxWinners": 100,
                    "tiers": [ { "minScore": 2, "reward": 0 } ], "regions": ["JP"] },
      "node": { "tree": "spring", "node": "basics" }, "topics": ["seasonal"] }
  ]
}
```

（字段名以 `package.ts` 与 `QuizFileFormat` 为准，这里只为说明结构。写代码时，示例改为测试资源 `first-launch/example/`，并由测试保证可以执行。）

## 2. 每类数据走哪个现有流程

**谁来运行**：
- **系统**：全局性的步骤由系统身份运行。
- **"首发代行"**：属于某个发起方的步骤，以该发起方的用户编号运行。平台的首发能力为此给出请求上下文（第 7.1 节 ④），与 `ActingUser.as` 同形：
  - 只在首发的运行中给出；
  - 只带该步骤的平台权限，不带任何角色。

| # | 数据 | 现有流程 | 谁来运行 | 改动 |
|---|---|---|---|---|
| 1 | 角色、权限、账本科目、参数声明、审批规则与职责分离规则的提议、法律文本草稿 | `QB_SETUP` | 系统 | 无（流程体内没有依赖人的检查） |
| 2 | 受控参数、审批规则：生效 | `CONTROL_CHANGE_PROPOSE` 之后，由新的平台子流程 `CONTROL_CHANGE_FIRST_LAUNCH_PUBLISH` 发布 | 系统 | 平台（第 7.1 节 ①） |
| 3 | 职责分离规则、等前提的开关 | 只由 `QB_SETUP` 提议，不发布 | — | `FirstLaunchSwitches` 加"等前提"标记（第 7.2 节） |
| 4 | 账号 | `SEC_USER_CREATE`（`invite`），邀请邮件提交后寄出 | 系统 | 无；要求 `jabiz.mail.enabled=true`，否则校验失败 |
| 5 | 运营商与直营发起方 | `QB_OPERATOR_SAVE`（含 `DirectSponsors.write`），默认运营商带 `defaultForNew` | 系统 | QuizBuks：首发运行中负责人可以尚未验证邮箱（第 5 节） |
| 6 | 发起方的资料、结构图草稿、Quiz、问题、素材、版本、发布草稿 | `QB_PACKAGE_IMPORT_START` / `_STEP`（内部依次是 `QB_PROFILE_IMPORT`、`QB_TREE_CREATE`、`QB_TREE_DRAFT_SAVE`、`QB_QUIZ_IMPORT`、`QuestionImportProcesses.IMPORT`、`QB_QUIZ_PUBLISH_VERSION`、`QB_PUBLICATION_SAVE`、`PublicationAudience.SAVE`、`PublicationTopics.SET` 等） | 首发代行（发起方） | QuizBuks：计划移植到 Java；首发运行中文件由平台登记为该发起方上传 |
| 7 | 发布的补充（奖励方式、兑换说明、上线与结束时间） | `QB_PUBLICATION_SAVE`（再存一次） | 首发代行 | 无 |
| 8 | 发起方的奖励方式 | `QB_SPONSOR_REWARD_METHODS_SET`（带 `userId`） | 系统 | 无 |
| 9 | 发起方页面送审 | `QB_SPONSOR_PAGE_SUBMIT` | 首发代行 | 无 |
| 10 | 公司认证（若需求方同意） | 新的 `QB_SPONSOR_VERIFICATION_FIRST_LAUNCH`（`internal()`，方式记为 `FIRST_LAUNCH`） | 系统 | QuizBuks（第 3.3 节） |
| 11 | 结构图送审 | `QB_TREE_SUBMIT` | 首发代行 | 无 |
| 12 | 发布送审 | `QB_PUBLICATION_SUBMIT`（预付款检查照常：首发不设价格，不预留） | 首发代行 | 无 |
| 13 | 第 9、11、12 步开出的审批 | 新的平台子流程 `APPROVAL_FIRST_LAUNCH_DECIDE`，之后照常经事件 `jabiz.approval.approved` 运行各自的 `*_APPROVAL_RESULT` | 系统 | 平台（第 7.1 节 ②） |
| 14 | 账号接受邀请并绑定二次验证后：授予角色 | `SEC_USER_ROLE_ASSIGN`，带首发授权 | 系统（任务） | 平台（第 7.1 节 ③） |
| 15 | 运营商的其他成员 | `QB_OPERATOR_MEMBER_ADD`（本人接受邀请后，邮箱已验证） | 系统（任务） | 无 |
| 16 | 报告 | 日志与邮件模板 `jabiz.first-launch.report`（`SendMail`） | 系统（任务） | 平台；QuizBuks 给出剩下的事项 |

**顺序与等待**：

1. 第 1–8 步是启动时立即执行的。
2. 第 9–13 步按依赖推进：
   - 结构图的审批结果（节点可绑定）到了之后才送审发布；
   - 审批结果由事件投递运行，所以要等到投递之后。
   - 推进由平台的首发任务 `jabiz.first-launch` 负责：启动时连续推进，之后每分钟一次，直到完成。
3. 发布在审批通过后照常由 `PublicationRules.resumeTo` 进入 LIVE；开始时间在未来的先 SCHEDULED，由 `qb.publication-tick` 上线。
4. 第 14–15 步等人。

## 3. 审核

### 3.1 原则

部署渠道（与引导管理员的环境变量同等可信）给出的内容视为已经审过。

- 审核照常开出审批（`RequireApproval`，绑定内容哈希），由平台的首发审批作出"批准"：
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
| 审批须由持有该级权限的人决定、不能是申请人（D23、`APPROVAL_OWN_REQUEST`） | 无例外 | 首发运行中，由平台子流程以 `first-launch` 批准该运行中开出的审批（只限这些审批：按审批的 `process_seq_id` 属于该运行判断） | 平台，D82 |
| §7.49 / F-F11"没有免审的特例" | QuizBuks 的运维约定 | 首发配置是唯一的例外；`operations.md` §7.49 同步说明 | QuizBuks 文档 |

### 3.3 公司认证（待需求方确认）

- 现有的认证要有人点击寄到该域名的链接，再经运营商审核，没有可以代为完成的现成流程。
- 计划给出 `QB_SPONSOR_VERIFICATION_FIRST_LAUNCH`：
  - 记认证方式 `FIRST_LAUNCH` 与配置哈希；
  - 运营商的页面显示"首发声明"；
  - 撤销照常（`_REVOKE`）。
- 理由：直营发起方属于平台方自己，域名由部署方声明，可信度与审核人相同。
- **如果需求方不同意**：从配置中去掉 `verification`，由负责人以后照常认证。

## 4. 钱

首发配置**一概不涉及资金**，校验时逐项拒绝。

| 项目 | 处理 | 理由 |
|---|---|---|
| Kudos 奖励（`PLATFORM`） | 拒绝：首发值 `qb.reward.kudos.enabled=false` | 未写奖励方式按 PLATFORM，送审时被 `REWARD_KUDOS_DISABLED` 拒绝（`PublicationEditing:307-321`），所以每个发布都必须写 `rewardMethod` |
| 线下兑换 `OFFLINE_REDEEM` | 允许，须写 `redeemDescription` | 不动钱 |
| 数字奖品 `DIGITAL_PRIZE` | 只允许不用码池的；码池（`POOL`）拒绝 | 码是秘密，不进配置；由负责人以后录入 |
| 发起方现金 `SPONSOR_CASH` | 拒绝 | 动钱 |
| 档位奖励额与 `budgetCap` | 只允许 0 | 非 0 只对 Kudos 有意义 |
| 预付款（受众费、分发费、曝光费，F-M5） | 不预留：运营商不设价格（分发费无价即为 0）；拒绝要付费的受众规则与他人视频（`sharedVideo`） | 新钱包没有钱，预留不足即 `QB_PUBLICATION_FUNDS` |
| 报名费 | 拒绝（首发值关闭） | — |
| 加值、赠送额度（`QB_FEE_CREDIT_ONBOARDING`）、运营商价格、银行设定 | 不在配置中 | 以后由人照常办理（报告列出） |

## 5. 账号：未接受邀请前，内容能否建在其名下

**结论：能，只需改一处 QuizBuks 的检查。**

1. **内容流程**：所有者都是操作人，流程体内没有"已验证邮箱"或"已接受邀请"的检查（`requiresVerifiedEmail` 只在 HTTP 入口检查）。以负责人的用户编号运行（首发代行），内容即属于他，与本人做的没有区别：
   - Quiz、问题、素材、版本、结构图、发布；
   - 文件上传者也记为他，`FILE_NOT_OWN` 成立。
2. **送审与上线的前提**：
   - 已批准的发起方：`DirectSponsors.write` 写出 APPROVED；
   - 运营商归属：`DirectSponsors.write` 写出固定归属。
3. **唯一的阻碍**：`QB_OPERATOR_SAVE` 要求负责人的邮箱已验证（`MEMBER_UNVERIFIED`）。这条规则是为了确保负责人是联系得到的真人。
   - 首发运行中改为：负责人可以尚未验证，但必须是本次运行邀请的账号。邀请寄到的正是这个地址，接受邀请即验证。
   - 判断依据是运行记录中的账号表，不是请求参数。
4. **账号本身**：
   - 密码只由本人从邀请邮件设定（`SEC_USER_CREATE` 的 `invite`，D63），平台不生成、不知道任何密码；
   - 所有权链（D48）因此成立。
5. **"加入"发起方入口**（D66）：
   - 这是本人对条款的同意，不能代为点击；
   - 负责人以后在发起方页面管理内容时，自己点"加入"；
   - 在此之前，内容照常上线，不受影响；
   - 报告把它列为剩下的事项。
6. **对比：等负责人接受后再建**。这不需要改 `MEMBER_UNVERIFIED`，但上线要等到人接受邀请，与"启动即上线"不符。所以不选。如果需求方更看重不改这条规则，只要把第 5 步移到任务中、等待负责人接受即可，其余不变。

**成员的加入**：运营商的其他成员（示例中的 A、B）照常要求邮箱已验证，由任务在本人接受邀请之后加入。

**登录**：
- 负责人在接受邀请前就是运营商的成员，所以接受后可以在 `/operator/` 登录（`QbSignInGuard`）并绑定二次验证。
- A 在成为成员之前只能在 `/admin/` 登录并绑定。
- 邀请邮件说明登录的地址。

## 6. 失败、幂等与报告

### 6.1 先整份校验

启动时先读取并校验整份配置，一次报告全部问题（同 `PlatformCheck` 的做法），不写任何东西。有任何问题即：
- 不执行；
- 日志 ERROR 逐条用人话说明（位置 | 问题 | 怎么改）；
- **应用照常启动**，不让一个配置错误挡住整个系统。

**校验的内容**：
- 格式与引用：代号存在、恰一个默认运营商、每个运营商恰一个直营发起方、负责人是配置中的账号；
- 超级管理员的 `grantOrder` 最大；至少一个 `report` 账号；
- 没有秘密形状的键；
- 包能被 `PackagePlan` 读出，包中 Quiz 文件经 `QB_QUIZ_CHECK` 同样的检查；
- 第 4 节的钱的规则；
- 发布的时间；
- 参数的值合法（参数声明的规则）；
- 邮件已开启；
- 大小上限。

### 6.2 执行：分步，每步一个事务，可续做

**为什么不做整体回滚**：
- 发起方的步骤以不同的用户编号运行，平台的事务按根流程划分；
- 审批结果要经事件投递；
- 所以"全部在一个事务中"做不到。改为：

**做法**：
- **运行记录**（平台表，见第 7.1 节）：配置哈希、状态（`RUNNING` / `WAITING_FOR_PEOPLE` / `DONE` / `STOPPED`）、开始与结束时刻。
- **步骤记录**（QuizBuks 表 V134 `qb_first_launch_item`，平台的账号与角色步骤记在平台表中）：
  - 每一项（账号、运营商、包的每一步、每个审批、每个授予）按配置中的键记一行：完成时刻与得到的编号。
  - 每一步先查这一行，已完成即跳过。这就是幂等性：重复启动、两个实例同时推进、任务与启动同时推进，都只做一次。
  - 推进时取命名锁 `first-launch`（`HoldLock.exclusive`）。
- **启动时的判断**：

| 用户表 | 运行记录 | 做什么 |
|---|---|---|
| 空 | 无 | 写运行记录（唯一约束防止两个实例同时开始；输掉的实例记"另一实例正在执行首发"），开始执行 |
| 不空 | 有，未完成，哈希相同 | 续做 |
| 不空 | 有，未完成，哈希不同 | 停下（`STOPPED`），日志 ERROR："首发进行到 <步骤> 时配置已被修改。请恢复原来的配置（哈希 …）后重新启动；或者清空数据库后用新配置重新开始。" |
| 不空 | 无 | 日志 WARN："已有 N 个用户，首发配置不执行。配置哈希 …。如需首发，请用全新的数据库。" |
| 不空 | 已完成 | 日志 INFO："首发已于 … 完成，本次启动忽略首发配置（哈希 …）。" 哈希不同时为 WARN |

- **某一步失败**：
  - 该步的事务回滚；
  - 运行记录写入失败的步骤与原因（错误码与人话），日志 ERROR；
  - 下一次任务（一分钟后）再试，因为暂时的失败（发信服务器、超时）会自己恢复；
  - 同一步连续失败到参数 `jabiz.first-launch.max-attempts`（缺省 5）次即 `STOPPED`，等人处理。
  - 处理方法写在日志与 `operations.md` 的新节中：
    - 改配置后清空库重来（新系统，没有任何别的数据）；
    - 或修正环境（如发信服务器）后重启，此时自动续做。
- **文件**：
  - 保存在其所属步骤之内（`storeGenerated` 的路径，先存字节，再在步骤的事务中登记）；
  - 步骤回滚后留下的字节与未引用的登记，照常由 `FILE_SWEEP` / `FILE_PURGE_ORPHANS` 清除。

### 6.3 试运行

`JABIZ_FIRST_LAUNCH_DRY_RUN=true`：
- 校验之后在一个事务中执行全部"启动时立即执行"的步骤，然后回滚，日志打印将要做的每一步；
- 使用 `ExecutionOptions.DRY_RUN`，与导入的预览一致，不另写一套逻辑；
- 邮件不寄出（在 Outbox 中，随回滚消失）。

运维先在测试系统上试运行，再正式启动。

### 6.4 报告（人话）

**日志**：每一步一行 INFO（"已建运营商 东京运营（默认），负责人 田中 美咲，邀请已寄出"），等人时每小时一行汇总（"等待：鈴木 一郎 尚未接受邀请（已寄出 3 小时）"）。

**完成**：全部账号授予完毕、全部发布已 LIVE 或 SCHEDULED。日志写"系统已正式运行"，并寄邮件给 `report` 账号。邮件内容：
- 已建的运营商、发起方、发布（含页面链接）、各账号的角色；
- 保护授权的状态：建立期是否结束、引导管理员（无）。这一项代替"平台缺少建立期的只读视图"：报告由平台自己读 `ProtectedGrants` 写出，不需要页面；
- **剩下的人工事项**（QuizBuks 给出清单）：
  1. 发布法律文本（`QB_LEGAL_PUBLISH`，正式文本来自业主，现在是标明的测试占位）；
  2. 各负责人在发起方页面点"加入"（D66）；
  3. 发布三条职责分离规则（`qb.sod.*`）；
  4. 发布等前提的开关（逐项列出前提：AI 说明发布后、分发费下限确认后等）；
  5. 第二位超级管理员（`launch-checklist` 中有意未满足的一项）；
  6. 发起方的发信服务器密码、数字奖品的码、运营商价格、银行设定、赠送额度。

**等人太久**：超过参数 `jabiz.first-launch.remind-after`（缺省 3 天）仍未接受邀请的账号：
- 照常重发邀请（`SEC_USER_INVITE`），每个账号只重发一次；
- 日志与报告账号的邮件中提醒（报告账号自己未接受时，只写日志）。

## 7. 要改的规则与归属

### 7.1 平台：一项能力"首发配置"（新决策 D82）

**为什么不能只在 QuizBuks 里做**：

| 要做的事 | 平台现状 | 只在 QuizBuks 里做的话 |
|---|---|---|
| 受控参数生效 | `ControlledParamGuard` 只认已批准变更的发布，系统身份也不例外 | 只能直接写参数表，违反 D40 |
| 系统给人授予受保护的角色 | 建立期结束后须另一位管理员批准 | 绕不过 `ProtectedGrantWriteCheck` |
| 审批的批准 | 须持有权限、且不是申请人 | 只能直接写审批表，违反 D23 |
| 以尚无会话的用户身份运行流程 | `ActingUser` 只在平台的控制器中使用 | 业务模块自己造 `RequestContext`，越过分层 |

所以平台必须改，但收窄为一项能力，每一部分都只在"首发运行"中有效。运行由平台的运行记录界定：只在空库上开始，完成或停止后永远不再开。

**① 首发的参数发布**：子流程 `CONTROL_CHANGE_FIRST_LAUNCH_PUBLISH`（`subProcessOnly()`）。
- 只能在首发运行的根流程中调用；
- 发布系统在本运行中提议的受控变更：参数与审批规则；
- 发布人记为 `first-launch`，依据记配置哈希；
- `ControlledParamGuard` 认这种发布的写入授权。

**② 首发的审批**：子流程 `APPROVAL_FIRST_LAUNCH_DECIDE`。
- 只批准本运行开出的审批；
- 决定人记为 `first-launch`；
- 之后照常发出 `jabiz.approval.approved`。

**③ 首发的授权**：`FirstLaunchGrant`（同 `BootstrapGrant` 的做法），`ProtectedGrantWriteCheck` 只在同时满足下列条件时接受：
- 运行未结束；
- 角色是配置中写给该账号的；
- 账号由本运行邀请，且已经：
  - 接受邀请（邮箱已验证，密码由本人设定：所有权链成立）；
  - 绑定二次验证（`SecUserMfa` 已确认）。

**④ 首发代行的上下文**：`FirstLaunchActing.as(userId, permissions)`。
- 只在运行的推进中使用，只用于本运行邀请的账号；
- 不发令牌、不建会话。

**⑤ 运行器与运行记录**：
- 读 `JABIZ_FIRST_LAUNCH_CONFIG`；
- 平台迁移（平台线上的下一个号，写代码时确定）：`sys_first_launch_run`（只追加的状态行）与 `sys_first_launch_step`（平台的账号与授权步骤）；
- 任务 `jabiz.first-launch`（`JobDefinition`，推进流程为 `internal()`）；
- 报告邮件模板 `jabiz.first-launch.report`。

**⑥ 应用的扩展点**：`FirstLaunchHandler` Bean（core，同步）：
- 读本应用的配置段；
- 给出校验问题；
- 给出步骤；
- 给出剩下的事项。

平台的部分（账号、参数、角色、报告）对所有应用相同，QuizBuks 只实现自己的那一段。

**⑦ 与引导管理员**：首发配置与 `JABIZ_BOOTSTRAP_ADMIN_USER` / `_PASSWORD` 互斥，两者都设即启动失败，并说明原因。
- 所以首发不建引导管理员，也就不需要"停用引导管理员"。平台本来也没有停用用户的流程，不新增。
- 破窗的做法（`operations.md`）不变。

**取代与修订的决策**（在 `09-decisions.md` 新增 D82，不改原文）：

| 决策 | 原来 | D82 的变化 |
|---|---|---|
| D40 受控的业务参数 | 只经四眼修改 | 首发运行中，配置给出的初始值由首发发布生效（部署渠道 + 配置哈希 = 第二个人） |
| D23 审批与职责分离 | 决定人须持权限、不是申请人 | 首发运行中开出的审批由首发批准 |
| D48 / D51 受保护授权与建立期、引导管理员的退出 | 系统授予须在建立期内，之后须另一位管理员批准 | 首发授权只授予本运行邀请、已接受邀请且已绑定二次验证的账号，与建立期无关；首发与引导管理员互斥 |
| D63 邀请 | 不变 | — |
| D66 加入入口 | 不变 | 首发不代为加入 |

**平台先行**：按第 8 节，平台的改动先在 `1.3/platform` 上完成，带平台自己的测试与示范（示范应用 `app` 的一份小配置），再合并到 `1.3/quizbuks`。

### 7.2 QuizBuks

- `QbFirstLaunch`（`FirstLaunchHandler`）：读配置的 QuizBuks 段，产出第 2 节的步骤。
- `PackagePlan`：发起方包计划的 Java 版，与 `package.ts` 用同一组样例对照。
- `OperatorProcesses.checkNewMember`：首发运行中，负责人可以是本运行邀请、尚未验证的账号（第 5 节）。
- `QB_SPONSOR_VERIFICATION_FIRST_LAUNCH`（第 3.3 节，待确认）。
- `FirstLaunchSwitches.Row` 加 `prerequisite`（等前提的行只提议），标出：
  - `qb.help.enabled`、`qb.ai.enabled`：AI 说明发布之后；
  - `qb.distribution.billing.enabled`：分发费下限确认之后；
  - `qb.exposure.billing.enabled`：价格范围确认之后。
  其余行以首发值直接生效。
- 迁移 V134：`qb_first_launch_item`（运行编号、键、编号、完成时刻；只追加）。
- 消息（三种语言）：报告的剩下事项、校验问题。
- 文档：
  - `operations.md` 新节"首发配置"（含恢复）；
  - §7.49 的例外；
  - `two-account-setup.md` 指向首发配置（两账号仍是人数与角色的约定）；
  - `launch-checklist.md`、`first-launch-progress.md`。

## 8. 安全分析

**配置被篡改**：
- 只在空库上生效，之后的启动只警告、不执行。所以篡改已经在运行的系统的配置没有作用。
- 运行记录、每一步的 `op_process.input_summary` 与审计都记"首发配置"与配置哈希：
  - 参数的发布人、审批的决定人都是 `first-launch`；
  - 事后可以用存档的配置核对哈希。
- 能篡改首次启动时的配置的人，同样能篡改引导管理员的环境变量，威胁等同，不更差。
- 配置不含秘密，泄露只暴露邮箱与内容。

**写错邮箱，使别人得到受保护的角色**：
- 授予只给接受了邀请（只有该地址的收件人能设定密码）并绑定了二次验证的账号，从不给一个没人控制的账号。
- 邀请邮件写明将得到的角色与运营商，并说明"如果这不是你，请不要接受，并联系 …"（联系方式来自配置的 `report` 账号的名字，不是地址）。
- **试运行与启动日志**列出每个账号的邮箱，部署前由人核对。这是防止写错的主要手段。
- 报告列出每个账号接受邀请的时刻，报告账号会看到意料之外的接受。
- 超级管理员最后授予：错的人即使得到运营商或财务角色，也还没有管理权限的角色，正确的超级管理员可以撤下（`SEC_USER_ROLE_ASSIGN`、`SEC_USER_SESSIONS_REVOKE`）。
- **"首次登录确认"**（需求方提到）。可以在邀请页加一句由本人勾选的"我是 <姓名>，接受以下角色"，但它防不住真正拿到邮件的错误收件人，只是让本人知情。
  - 计划的做法：邀请邮件写明角色，不另加勾选；
  - 如果需求方仍要，作为平台邀请页的一个可选项（`MailLinkPage` 的 `invitation`）另做。
- **剩余风险**：负责人的邮箱写错而错误的收件人接受了邀请，他会成为该直营发起方（内容已上线）的账号。缓解：
  - 他要点"加入"才能管理内容；
  - 报告与超级管理员可以发现并处理。
  这与现在的人工流程中输错邮箱的风险相同。

**首发代行**：
- 只在运行的推进中、只对本运行邀请的账号、只带步骤所需的平台权限；
- 不发令牌，所以外部无法得到这种上下文；
- ArchUnit 检查只有平台的首发包使用它。

**首发审批与首发发布**：
- 只对本运行中开出的审批与提议有效，按 `process_seq_id` 属于该运行判断；
- 运行结束后永远无效（运行记录只追加，`DONE` / `STOPPED` 不能回到 `RUNNING`）。

**职责分离规则**：
- 不由首发发布。它们由系统提议，所以以后任何一位持有 `control.publish` 的人都能发布：`CONTROL_SAME_PERSON` 只排除提议人。
- 内容由配置决定、发布由一个人确认，相当于两方，可以接受。报告中写明这一点。

需要做 `/security-review`：涉及认证、权限、审批。

## 9. 测试清单

**平台**（`1.3/platform`，集成测试连真实 PostgreSQL）：
1. **空库与示范配置**：启动后，运行记录与步骤齐全；参数已发布（发布人 `first-launch`）；邀请在 Outbox 中。
2. 用户表不空且没有运行记录：不执行，日志 WARN；已完成后再启动：INFO，不重做。
3. 两个实例同时开始：只有一个运行记录。
4. 哈希不同时续做被拒：`STOPPED`，日志说明。
5. 受保护授权：
   - 未接受邀请、或已接受但未绑定二次验证：不授予；
   - 两者都满足：授予；
   - 运行结束后同样的授权被拒；
   - 配置以外的角色被拒；
   - 超级管理员最后授予。
6. 首发审批只批准本运行的审批；运行外调用被拒；申请人的检查在运行外照旧。
7. 首发参数发布只发布本运行的提议；运行外被拒；`ControlledParamGuard` 对其他写入照旧拒绝。
8. 与引导管理员的环境变量同时设置：启动失败。
9. 试运行：数据库在运行前后一致，没有邮件。
10. 校验一次报告全部问题；有问题时应用照常启动、不执行。
11. 审计：每条写入带首发的流程名，`input_summary` 带配置哈希且不含邮箱以外的个人信息。
12. 语句数：`StatementBudgetIT` 不受影响（首发不在标准写入路径上）。

**QuizBuks**（`1.3/quizbuks`）：
13. `PackagePlan` 与 `package.ts` 对 `PackageFixtures` 的全部样例给出相同的步骤（前后端各一个测试，共用样例文件）。
14. **端到端的集成测试 `FirstLaunchIT`**（空库 → 启动 → 模拟接受邀请 → 任务完成 → 全部就位 → 学者可答题）：
    - 以第 1.3 节的示例（测试资源）在空库上启动；
    - 立即执行的部分完成：两个运营商（东京为默认）、两个直营发起方（APPROVED、固定归属）、资料、结构图、Quiz、三道题、图片（上传者为负责人）、发布；
    - 推进事件与任务（`deliverPending`、`JobRunner.run`）：结构图 ACTIVE、发布 LIVE；审批记录的决定人为 `first-launch`、内容哈希相符；
    - 模拟 B、C 接受邀请（`MailTokens` 的令牌）并绑定二次验证，A 只接受邀请：B、C 得到角色，A 没有；A 绑定后得到 `QB_OPERATOR` 并加入运营商，最后得到 `QB_ADMIN_SUPER`；
    - 运行 `DONE`：报告邮件寄给 A，含剩下的事项；日志有"系统已正式运行"；
    - 一位学者自助注册、找到该发布、作答并通过（得到线下兑换的结果页）；
    - 再次启动：不重做，日志 INFO。
15. 校验：
    - Kudos 奖励、码池、非 0 档位、要付费的受众规则、他人视频、`{url}` 图片、秘密形状的键、两个默认运营商、负责人不在账号中、超级管理员不在最后：各被拒；
    - 一次报告全部问题。
16. 某一步失败（制造一个包中的 Quiz 在 `QB_QUIZ_IMPORT` 被拒）：该步回滚、运行记录写原因、下一次任务重试、达到上限后 `STOPPED`。
17. `MEMBER_UNVERIFIED`：运行外照旧拒绝；运行中只对本运行邀请的负责人放行。
18. 等前提的开关：只提议、不生效；职责分离规则：只提议。
19. 场景回放：首发的一份小配置，快照覆盖运行记录、步骤与报告。

**Playwright**（e2e，CI 的 `e2e` 作业）：
- 以示例配置启动的应用上，学者打开发布并答题；
- 负责人从邀请邮件设定密码、绑定二次验证后，在 `/operator/` 看到自己的运营商；
- 邮件取自测试的邮件服务器。

## 10. 风险与未决事项

- **需求方确认**：
  1. 公司认证由首发声明（第 3.3 节）；
  2. 负责人在接受邀请前即成为运营商负责人（第 5 节；不同意则发布要等负责人接受后才上线）；
  3. "首次登录确认"不另做勾选（第 8 节）。
- **平台改动的范围**：五项都在一个"首发配置"能力中。如果 Fable 认为参数发布与审批不宜放进同一个能力，可拆成两条决策，内容不变。
- **`PackagePlan` 的移植**：这是工作量最大的一项。两份实现以共用样例对照，包的格式变化时两边都要改（在 `package.ts` 与 Java 中互相注明）。
- **内容在测试系统上先做好**：首发配置依赖测试系统可用来制作并导出包。导出的包中图片已内嵌，不需要另外的文件目录。
- **规模**：首发的内容量以参数限定（运营商数、每个包的 Quiz 数、总大小），超过即在校验时拒绝，避免启动时长时间推进。
