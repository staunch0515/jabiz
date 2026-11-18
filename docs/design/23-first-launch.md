# 23 首发配置：一个配置文件，启动即完成首发初始化

在从未有过用户的数据库上，平台按运维给出的**首发配置**一次完成初始化（ROADMAP 17zz23）：
- 应用的设置；
- 受控参数；
- 账号；
- 应用的内容（QuizBuks：运营商、直营发起方、Quiz、发布等）。

完成之前不对外服务；完成后寄出邀请，等账号的本人接受邀请、绑定二次验证后授予角色。界面上没有按钮，人只做"接受邀请 + 绑定二次验证"。

计划与审查：QuizBuks 应用分支的 `docs/quizbuks/plans/impl/F-LAUNCH.md`。决策：D82（17zz23b）。

| 能力 | 做什么 | 阶段 |
|---|---|---|
| 运行器与运行记录 | 触发、整份校验、就绪门、`FIRST_LAUNCH_STEP`、失败即退出 | 17zz23a |
| 参数初值发布、首发审批、首发代行 | 内容阶段的三项能力，`CONTENT_DONE` 后永久关闭 | 17zz23b |
| 账号与等人阶段 | 账号、待命角色、邀请、授予、改邮箱的补救、期限、报告 | 17zz23c |

## 1. 两个阶段

| 阶段 | 做什么 | 对外服务 | 出错时 |
|---|---|---|---|
| **内容阶段** | 平台的步骤（账号）与应用的步骤，启动时一口气完成 | 不服务（第 3 节就绪门） | 记录原因，应用退出（非 0）；之后每次启动同样失败，直到数据库被清空。不续做 |
| **等人阶段** | 邀请、授予、报告（17zz23c） | 服务 | 不清库；补救见 17zz23c |

## 2. 配置

- 环境变量 `JABIZ_FIRST_LAUNCH_CONFIG`（`jabiz.first-launch.config`）是主文件的路径；主文件所在的目录是配置的目录，应用的其他文件（如发起方包）放在其中，以相对路径引用（`FirstLaunchConfig.files()`：不出目录、不经符号链接）。
- 主文件是 YAML。平台读的段：
  - `version`：现为 1；
  - `site.language`：日志与报告的语言；
  - `allowedEmailDomains`；
  - `accounts`：`key`、`email`、`name`、`language`、`roles`、`report`。
  其余段由应用的 `FirstLaunchHandler` 读取。
- 配置不含秘密：主文件中形似秘密的键（含 `password`、`secret`、`token`、`apiKey`、`privateKey`、`credential`、`codes`）一律是问题。
- **配置哈希**：目录中每个文件按相对路径排序，每个文件贡献"相对路径、零字节、该文件的 SHA-256"，再整体做 SHA-256。它记入运行记录、日志与审计。
- **上限**（参数）：
  - 单个文件 `jabiz.first-launch.max-file-bytes`（缺省 20 MiB）；
  - 总大小 `max-total-bytes`（200 MiB）；
  - 文件数 `max-files`（2000）。

## 3. 启动时的判断

运行器 `FirstLaunchRunner` 在所有单例建好之后、服务器接受请求之前判断。运行记录优先于配置：
- 失败的运行使每次启动都失败；
- 内容阶段中的运行使每个实例都关门等待，没有配置的实例也一样；
- 只有开始新的运行才要求有配置。

| 情形 | 做什么 |
|---|---|
| 没有配置 | 什么也不做 |
| 核对模式（`jabiz.ops.verify-restore.enabled`） | 忽略配置 |
| 试运行（`jabiz.first-launch.dry-run`） | 整份校验；有问题即启动失败；否则应用就绪后打印步骤计划并退出（0），不写任何东西 |
| 没有运行记录，`sec_user_version` 有行（包括墓碑） | 日志 WARN：已有用户，不执行 |
| 没有运行记录，从未有过用户 | 整份校验（有问题即启动失败，一次列出全部问题）；关闭就绪门；应用就绪后在后台执行内容阶段 |
| `RUNNING` | 另一个实例正在执行：关闭就绪门，等待；超过 `content-timeout` 未完成即视为失败 |
| `FAILED` | 启动失败，说明失败的步骤、原因与"修改后清空数据库与文件目录，重新启动" |
| `CONTENT_DONE` | 照常服务（等人阶段，17zz23c） |
| `DONE` / `EXPIRED` | 不执行；配置仍在即 WARN，提醒撤下 |

**多个实例**：首发期间（直到 `CONTENT_DONE`）各实例应带同一份配置与同一个 `content-timeout`。没有配置的实例按自己的 `content-timeout`（缺省 10 分钟）判断另一实例的运行是否已过时，比执行它的实例设得短就可能提前判为失败；因此首发期间不要启动设置不同的实例。

**整份校验**的内容：
- 平台的段（第 2 节）；
- 与首发不相容的设置，同时设置即为问题：
  - 引导管理员（`JABIZ_BOOTSTRAP_ADMIN_USER`）；
  - `jabiz.security.protected-grants.always=true`；
- 不止一个 `FirstLaunchHandler`；
- 平台各部分（`FirstLaunchPlatformSteps`）与应用的 `validate`。

**就绪门**（`FirstLaunchGate`、`FirstLaunchGateWebFilter`，最先运行的过滤器）：关闭时，对每个请求都回答 503 `FIRST_LAUNCH_IN_PROGRESS`，带 `Retry-After: 30`；只有 `/actuator/health` 照常。
健康指示 `firstLaunch` 在关闭时为 DOWN。要让编排系统的就绪检查也等首发，就把它加进就绪组：`management.endpoint.health.group.readiness.include=readinessState,firstLaunch`。内部的事件投递与任务照常运行，内容阶段等待的审批结果正是由它们产生的。

## 4. 内容阶段

1. **开始**：`FIRST_LAUNCH_START`（`internal()`）在一个事务中确认从未有过用户，插入运行记录。
   - `sys_first_launch_run.run_slot` 恒为 1，所以至多一次运行；
   - 两个实例同时开始时，输掉插入的一方等待另一方。
2. **步骤**：先是平台各部分的步骤（`FirstLaunchPlatformSteps`，按顺序），再是应用的步骤（`FirstLaunchHandler.plan`）。
   - 每一步是一个根流程 `FIRST_LAUNCH_STEP`（`entryOnly()`，只由运行器启动），在自己的事务中先锁住运行记录、确认仍在内容阶段，再以子流程调用该步骤的流程（最新版本，输入须是其输入类型）。
   - 以系统身份运行的步骤可调用 `OPEN`、`SUB_PROCESS_ONLY` 与 `INTERNAL` 的流程。
   - 请求编号为 `first-launch-<运行>-<步骤键>`，审计与 `op_process` 据此区分首发的操作。
   - 步骤的输入由前面步骤的输出算出（`FirstLaunchResults`），只在内存中保存。
   - 每一步开始前也检查 `content-timeout`，超过即失败（与另一实例判断"过时"用同一期限）。
   - 等待的步骤（`FirstLaunchStep.await`）反复运行，每次一个新事务，直到流程返回 `true`。用于等事件投递的结果（如审批的结果），以 `jabiz.first-launch.await-interval` 为间隔，以 `content-timeout` 为限。
3. **完成**：`FIRST_LAUNCH_RECORD` 追加 `CONTENT_DONE` 事件（平台各部分可附 `contentDoneDetail`：用户编号与位置、哈希，不写账号的代号——代号即登录名），然后打开就绪门。
4. **失败**：任一步失败（含超时）时：
   - 该步的事务回滚；
   - 追加 `FAILED` 事件（步骤键、错误类别、原因）；
   - 日志 ERROR 说明怎么办；
   - 应用以退出码 1 退出（`FirstLaunchExit`，测试中替换）。

**运行记录**（V67，只追加，都有主键，由完整性封存覆盖）：
- `sys_first_launch_run`：运行编号、配置哈希、开始时刻；
- `sys_first_launch_event`：`CONTENT_DONE`、`DONE`、`FAILED`、`EXPIRED`、`REVISED`。
  - 当前状态是最新一条非 `REVISED` 的事件；没有事件即 `RUNNING`。
  - 状态机由 `FIRST_LAUNCH_RECORD` 在锁住运行记录后检查：`RUNNING` → `CONTENT_DONE` 或 `FAILED`；`CONTENT_DONE` → `DONE`、`EXPIRED` 或 `REVISED`；结束后不再有事件。
- 两张表都不存邮箱、姓名与账号的代号（代号即登录名，是个人信息），只存哈希、步骤键与用户编号；账号的步骤键用位置（`account:#n`，§8）。V67 中 `detail` 的注释说 `REVISED` 以代号称呼账号，已由 V68 的说明取代：`REVISED` 只写用户编号（D83）。

## 5. 扩展点

| 类型 | 给谁 | 内容 |
|---|---|---|
| `FirstLaunchHandler`（core） | 应用，至多一个 | `validate`、`plan`；`actingProcesses`、`publishableControlKeys`（17zz23b）；`remaining`（17zz23c） |
| `FirstLaunchPlatformSteps`（runtime） | 平台各部分 | `validate`、`plan`、`contentDoneDetail`、`detailKey`；步骤排在应用之前 |
| `FirstLaunchStep`（core） | — | `run` / `await`，`.asAccount(代号)`（首发代行，17zz23b），`.describedAs(说明)`；`FirstLaunchStep.accountStepKey(代号)` 是在结果中找到账号步骤输出的键（输出为用户编号，或带 `userId()` 的 record）；账号的步骤本身以位置为键（`accountStepKeyAt(位置)` = `account:#n`），结果在内存中把 `account:<代号>` 映射到它，所以代号不进请求编号与 `op_process` |
| `FirstLaunchRuns` | 平台各部分 | `current()`、`usersEver()`、`detail(运行, 类别)` |

## 6. 内容阶段的三项能力（决策 D82）

内容阶段中，首发有三项人所没有的能力。三者都由 `FirstLaunchPowers` 按 `op_process` 的血缘检查：
- 沿 `parent_seq_id` 到顶层操作，它是本运行的 `FIRST_LAUNCH_STEP`（`input_summary.runId`），而这个流程只由运行器启动；
- 运行仍在内容阶段（`RUNNING`）；
- 对象是本运行做的：该实例第一个版本的操作以同样的血缘回到本运行的步骤。

请求编号 `first-launch-<运行>-<步骤>` 只用于区分，不作依据；客户端给出的以 `first-launch` 开头的请求编号一律被替换。运行记下 `CONTENT_DONE` 或 `FAILED` 之后，三项能力永久关闭；不满足条件时 403 `PERMISSION_DENIED`。

启动检查 `FIRST_LAUNCH`：
- 代行的流程须存在，且是 `OPEN` 或 `SUB_PROCESS_ONLY`；
- 可发布的控制须有键；
- 至多一个处理器。

代行的上下文只由 `FirstLaunchActing` 建立（架构测试）。

| 能力 | 用法 | 额外的限制 |
|---|---|---|
| 首发代行 | `FirstLaunchStep.asAccount(代号)`：步骤以该账号的用户编号运行（无角色、无权限、无会话） | 流程须在 `FirstLaunchHandler.actingProcesses()` 中；用户是本运行建的、从未有过凭据（密码、二次验证、通行密钥、关联的外部账号、会话、成功的登录） |
| 参数初值发布 | 系统步骤先 `CONTROL_CHANGE_PROPOSE`，再 `CONTROL_CHANGE_FIRST_LAUNCH_PUBLISH`（`subProcessOnly()`） | 只限受控参数与审批规则（职责分离规则、审批人限额不行）；提议人须是系统；目标键须在 `publishableControlKeys()` 中；发布人 `first-launch`；不检查 `CONTROL_SAME_PERSON` |
| 首发审批 | 系统步骤 `APPROVAL_FIRST_LAUNCH_DECIDE`（`subProcessOnly()`，输入审批请求编号） | 批准其余各级，每级一条决定（`first-launch:<级>`，理由为配置哈希），照常发 `jabiz.approval.approved`；带限额的级拒绝。决定人不是用户编号：显示决定人的地方把 `first-launch:` 开头的当作首发 |

**文件步骤**（17zz23d）：`FirstLaunchStep.file(键, new FileSource(策略, 相对路径, 取出字节, 名称)).asAccount(代号)`。文件步骤必须代行（没有账号即启动校验失败）。
- **来源**：字节只来自配置的目录，经 `FirstLaunchConfig.Files` 读取。读取时：
  - 路径规范化后不得出目录、不经符号链接（逐段检查，以 `NOFOLLOW_LINKS` 打开）；
  - 文件须是读取配置时就在目录中的，且内容的 SHA-256 与那时记下的相同：启动后新增或改写的文件一律拒绝；
  - 最多读 `jabiz.first-launch.max-file-bytes` + 1 个字节，超出即拒绝。
  处理器的函数只从读到的字节中取出要存的部分（如发起方包中 base64 的图片）；返回 `null` 即该步骤失败。读取与取出在 `boundedElastic` 上进行。
- **大小的界限**：解码之前只有 `jabiz.first-launch.max-file-bytes`（单个文件）与 `max-total-bytes`（整个目录）；策略的 `maxBytes` 作用于取出（解码）后的字节，在进入流水线之前检查（`storeGenerated` 的第一步），超出即 413。
- **存储与登记**：先经平台完整的上传流水线（`FileUploadService.storeGenerated`）存储字节：按内容判定类型、图片重新编码并去掉元数据、策略的大小与类型。随后在 `FIRST_LAUNCH_STEP` 之下以子流程 `FIRST_LAUNCH_FILE_REGISTER`（`subProcessOnly()`，复用文件登记）登记为该账号的上传，所以照旧持有运行的行锁、检查内容阶段（按流程的谱系）与代行条件；登记失败即删除已存的对象。内容流程"只接受操作人自己上传的文件"照常成立。
- **启动校验与试运行**：每个文件步骤的账号、策略已声明、文件存在且不过大，一次报全。
- **步骤的输出**：文件编号（`privateMetadata()` 策略为随机编号）。文件名不进 `op_process.input_summary`。
- **唯一放宽的是上传权限**：首发建立的账号此时还没有任何权限。代替它的是处理器在 `filePolicies()` 中声明的策略（启动检查 `FIRST_LAUNCH` 要求策略存在、名称不空白），加上代行的条件（本运行建立、从未有过凭据、运行仍在内容阶段）。
- 只有运行器使用 `FirstLaunchFiles`（架构测试）。

审批结果由事件投递运行，所以送审之后要用等待的步骤（`FirstLaunchStep.await`）等结果流程完成，再做依赖它的步骤（如知识结构图生效后再送审绑定它的发布）。

## 8. 账号与等人阶段（17zz23c，决策 D83）

**内容阶段的账号**（平台的步骤，`FirstLaunchAccountSteps`，排在应用的步骤之前）：
- `waiting-role`：`FIRST_LAUNCH_WAITING_ROLE` 建**待命角色** `FIRST_LAUNCH_PENDING`（没有任何权限，`requireMfa = true`）；
- 每个账号一步，键为位置 `account:#n`（不是代号：步骤键进请求编号与 `FIRST_LAUNCH_STEP` 的输入摘要，而代号即登录名；`results.userId(代号)` 与 `output(accountStepKey(代号))` 照常可用）：`FIRST_LAUNCH_ACCOUNT_CREATE` 建用户（登录名为代号，地址去掉首尾空白），**不设密码、不寄邀请**，分配待命角色，
  并在 `sys_first_launch_account`（V68，只追加：运行、用户编号、在配置中的位置、配置的角色代码、是否收报告；**不存代号**——代号即登录名，是个人信息）记下一行；输出 `{userId}`。
- 两个流程都是 `internal()`、只由系统身份运行，并自己确认运行处于内容阶段（`FirstLaunchContentPhase`）。
- **校验**（有账号时）：邀请链接的入口 `admin` 须接受待命角色（否则被邀请的人登录不了、绑不了二次验证）；邮件须开启；账号的 `roles` 不能写待命角色。角色是否存在留到等人阶段（应用的步骤才建角色）。
- `CONTENT_DONE` 的 `detail.accounts.shape`：配置的**形状**（`ConfigShape`：主文件去掉各账号 `email` 后的规范文本，加上目录中其他每个文件的哈希），供改邮箱的补救判断"只差地址"。

**待命角色**（`WaitingRoleWriteCheck`，所有时态写入路径）：
- 只有首发的两个流程（系统身份、带 `FirstLaunchGrant`）能分配它、启用它、改它的 `requireMfa`；它的权限一律拒绝。其他写入 422 `FIRST_LAUNCH_ROLE_RESERVED`。停用它、从某人收回它，照常（有写角色权限的人）。
- 只有待命角色的账号能在 `admin` 入口登录，被要求绑定二次验证（`MFA_ENROLLMENT_REQUIRED`），之后得到一个零权限、菜单为空的会话。
- 运行结束（`DONE` / `EXPIRED`）时由首发停用，此后谁也不能再启用或分配（上一条）。仍只持有它的账号因此登录不了。

**等人阶段**：任务 `jabiz.first-launch`（`JobDefinition.forEach`，缺省每分钟，`jabiz.first-launch.cron`；核对模式下不注册）。选项模板 `jabiz.first_launch_items` 只在运行已 `CONTENT_DONE`、尚未结束时给出选项；
每个选项一个事务（`FIRST_LAUNCH_ADVANCE`，`internal()`），按现状判断（`GrantPlan`），重复运行无害：

| 选项 | 做什么 |
|---|---|
| 账号（键：用户编号） | 从未邀请即邀请（`SEC_USER_INVITE`，入口 `admin`）；超过 `jabiz.first-launch.remind-after`（缺省 3 天）仍未接受即再邀请一次；已接受邀请**并**已绑定二次验证时，授予配置中不使账号成为管理员的角色（`SEC_USER_ROLE_ASSIGN`，理由"First launch configuration <哈希>"）；有配置角色已生效时撤下待命角色 |
| 管理员（键：`x` + 用户编号的 32 位十六进制） | 使账号成为管理员（D51：持有 `*` 或管理员权限，含已排期的版本）的角色，另须运维确认指纹：未确认时日志 WARN（每小时一次）给出账号的位置（`#n`）、用户编号、接受邀请与绑定二次验证的时刻与**指纹**；确认后授予 |
| 结束（键：`end`） | 全部配置角色已 `APPLIED`、已移交（`PENDING`，由已有的管理员照常审批）或已由管理员决定（`DECIDED`：被拒的申请、给过又收回的角色，首发不再申请）且该撤的待命角色已撤 → `DONE`（运行中被删除的账号不再等）；自 `CONTENT_DONE` 起超过 `jabiz.first-launch.deadline`（缺省 14 天）→ `EXPIRED`。二者都停用待命角色、经 `FIRST_LAUNCH_REPORT` 给收报告的账号寄报告（模板 `jabiz.first-launch.report`，收件人的语言）并写日志；事件的 `detail` 只含代号、用户编号与各角色的状态。未结束时每小时一行日志说明在等谁 |

- 管理员的角色排在每一轮的最后：建立期内其他人的授予当即生效；第二位管理员得到角色后建立期结束，之后的授予照常为 `PENDING`、由两位管理员审批，首发视为已移交、列入报告。
- **指纹**：运行编号、用户编号与当前二次验证的确认时刻的 SHA-256 前 12 位十六进制（`GrantPlan.fingerprint`）。运维与本人核对后把它加入 `JABIZ_FIRST_LAUNCH_CONFIRM_ADMINS`（`jabiz.first-launch.confirm-admins`，逗号分隔，不分大小写）并重启。本人重新绑定二次验证即换指纹，旧的确认失效。不做页面。
- 账号的状态全部从现有的表读出（`FirstLaunchAccountStore`）：邀请 = `sys_mail_message` 中模板 `jabiz.auth.invitation`、寄到当前地址的记录；接受 = 当前地址等于已证地址；二次验证 = 已确认的 `sec_user_mfa` 的确认时刻；角色 = 当前的 `sec_user_role`；移交 = 待审批的 `sec_protected_change`（`USER_ROLE`）；已决定 = 曾经分配过、或其受保护变更已有结论的角色。
- 选项键：账号为 36 位的用户编号，管理员为 `x`（非十六进制）加 32 位十六进制。一个账号的授予在同一事务中：其中一项被拒（如职责分离冲突）时整项回滚并按任务的退避重试，直到期限（配置错误，由人照常处理）。
- **停用与他人改址**：账号被停用（发现异常即停用），或尚未接受邀请而地址被他人改过（邀请不能寄出，D63），首发即不再为它做任何事（不邀请、不授予），列入报告的剩下事项。
- **个人信息**：日志、报告、运行事件、请求编号与步骤的输入摘要只以位置（`#n`）与用户编号称呼账号，不写代号、姓名与邮箱（`FIRST_LAUNCH_ACCOUNT_CREATE` 的输入照常含登录名与地址，同 `SEC_USER_CREATE`，随账号的抹除清除；邀请邮件的审计记录亦然）；报告正文由 `FIRST_LAUNCH_REPORT` 在流程中按状态写出（输入只有用户编号与结束方式，不进 `input_summary`）。
- **报告**：结束方式、各账号的角色与状态（`APPLIED` / `PENDING` / `DECIDED` / `NONE`）及接受邀请的时刻、建立期是否仍开着及其含义、待审批的授予、管理员已决定的授予、首发不再处理的账号、没有任何配置角色生效的账号、应用的 `FirstLaunchHandler.remaining`（启动时读入的配置）、撤下首发配置与 `JABIZ_FIRST_LAUNCH_*`。

**改邮箱的补救**（D83，`FirstLaunchRevisions`）：等人阶段的实例启动时，配置的哈希与最近一次应用的（最近的 `REVISED` 的 `hash`，或运行的）不同：
- 形状与 `CONTENT_DONE` 记下的相同、且有地址不同的账号都**尚未接受邀请** → 对每个以 `FIRST_LAUNCH_EMAIL_REVISE` 改地址（流程自己核对用户在 `sys_first_launch_account` 中、运行处于等人阶段），追加 `REVISED`（`detail`：`previousHash`、`hash`、`accounts`）；
  任务随后邀请新地址（邀请按当前地址计数，所以改址后中断也不会漏邀），旧链接因地址改变而作废；没有要改的即不记 `REVISED`（另一实例已改，或只差注释）；
- 其他任何差异（形状不同、已接受邀请的账号的地址、账号增减）→ 什么都不做，日志 WARN 说明并请在管理系统中照常办理。用户已被删除的位置不参与比较。
- 改回原地址（A→B→A）：同样是一次更正（地址又变了，B 的链接作废，任务邀请 A），条件照旧（尚未接受邀请、从未被他人改过）。
- 地址仍须在 `allowedEmailDomains` 之下（配置读取时校验）。

**测试**：`GrantPlanTest`、`EmailRevisionTest`、`ConfigShapeTest`、`CredentialChainTest`（D83 的窄例外）；`FirstLaunchAccountsIT`（只有待命角色的账号登录与二次验证、零权限与空菜单、待命角色只属于首发、改地址与重寄、管理员改的地址仍不能邀请）、
`FirstLaunchWaitingIT`（内容阶段之后才邀请、接受且绑定后才授予、管理员须指纹确认、撤下待命角色、管理员收回的角色不再授予、`DONE` 与报告、改邮箱的补救、两个实例同时补救、改址后由任务邀请、其他差异被拒）、`FirstLaunchExpiryIT`（期限、停用后登录不了）、
`FirstLaunchHandoverIT`（首发账号抹除并清除后全库搜不到其代号、姓名与邮箱；请求编号与首发流程的输入摘要不含代号；地址被他人改过的账号过了提醒时间也不邀请、任务不失败；两位管理员后建立期结束、之后的受保护授予 `PENDING` 移交、被拒绝后不再申请、停用的账号不再处理、报告不含代号姓名邮箱、首发写的表与时态表没有 UPDATE / DELETE）。

**未做**（17zz23c 之后）：邀请邮件中写明将得到的角色与运营商（F-LAUNCH L2）——需要首发自己的邀请模板或给 `SEC_USER_INVITE` 加可选的说明，另议。
