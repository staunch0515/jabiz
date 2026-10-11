# Q2 账户（后端，5–6 天）— 实施计划

分支：`1.2/quizbuks-2-accounts`（从 `1.2/quizbuks` 拉出，PR 合回 `1.2/quizbuks`）。只改 `.jabiz-app-paths` 中的路径。

本阶段只做后端：登录入口配置、资料与商家入驻的实体、数据视图、审批对象与规则、流程、登录前检查（封禁）、事务邮件模板、SQL 模板、迁移、三语消息、测试，以及 Q4 留下的 `SponsorChecks` 的接线。

- **页面延后**，在平台 16c（应用 SPA 的接入：`@jabiz/client` 的登录入口、注册与验证页面积木、PWA）合入后另做，约 3–4 天：
  - App（`/`）：欢迎页（U-01）、注册（第一步，平台接口）、补充资料（第二步，U-02 / U-03 / U-04）、设置中的资料（U-70）、修改密码（U-71，见平台缺口 1）、登录记录；
  - 商家后台（`/sponsor/`）：注册、入驻申请与状态提示（M-01）、商家资料（M-03）、登录记录（M-02）；
  - 管理员后台：用户列表与详情的扩展页（P-01）、封禁 / 解封的行操作（P-02，元数据生成即可）。入驻审核在此之前用通用后台的 `/tasks` 审批面板，并在 `QbSponsor` 管理员视图中查看。
- **社交登录（U-02 的 Google / Apple）延后**到平台 16f（D39）合入之后，约 0.5–1 天的追加（见 D-Q2-9）。本阶段只做邮箱注册。
- **不建钱包**：M-01 的"创建用户 + 钱包"中的钱包由 Q5 / Q6 在首次记账时建立（设计 §3.4 `QbWallet` 是余额缓存，真相在账本；账本维度不需要事先开户）【待确认 Q2-7】。
- **不做银行账户**：U-05 / U-70 的银行账户已由设计 §3.1 改为 Stripe Connect 收款账户（`QbPayoutAccount`，Q6），平台不保存银行账号【待确认 Q2-8】。

已核实的平台事实（`origin/1.2/quizbuks` 已含 16a、16b-1、16b-2；16d 尚未合并，Q2 不需要）：
- **登录入口**（10 §15.1）：`jabiz.security.entries.<名>` 的 `accepted-roles`、`self-registration`、`registration-roles`、`require-verified-email`、`app-path`。
  令牌只含该入口接受角色的权限；入口不接受其角色 → 401 `LOGIN_FAILED` 并记 `NO_ROLE`。**任何入口允许注册时，`admin` 必须显式配置且不得含 `*` 或注册角色**（启动错误）。
  注册角色在当前版本中不存在只是警告：`QB_SETUP` 之前注册一律 422 `SIGN_UP_DISABLED`。`QuizbuksApp` 目前**没有任何入口配置**（只有缺省的 `admin`，接受全部角色）。
- **自助注册与验证**（10 §15.7）：`POST /api/auth/register` → `SPONSOR_SIGN_UP`（202，对任何邮箱相同）；已被使用的邮箱收到"你已有账号"，点击加入链接后才追加注册角色（身份合并）。
  未验证即"待激活"（不另设状态）；用户名即小写邮箱，未验证的用户能以它登录**不要求验证的入口**。平台流程没有给应用的事件或挂点（注册、验证、接管都不通知应用）。
- **可预占的账号**（10 §15.7、D36 实现说明）：自助注册、从未验证的账号可被邮箱的真正主人接管（设新密码、删二次验证、吊销会话），但**接管不清理应用数据**。
  因此 QuizBuks 的一切写入都要求已验证的邮箱（D-Q2-1），未验证的人留不下应用数据。
- **`requiresVerifiedEmail`**：流程 `pb.requiresVerifiedEmail()`、数据视图 `policy(p -> p.requiresVerifiedEmail())`（只管写），依据访问令牌的 `email_verified`；验证之后客户端须刷新令牌（刷新时重读）。
  系统身份、子流程、事件消费者、场景回放不检查。`TestTokens.bearer` 为已验证，`TestTokens.unverified` 为未验证。
- **`SignInGuard`**（10 §15.3）：core 同步接口，`loads()` 声明经数据视图按用户编号预读（每项至多 100 行），`check(SignInAttempt)` 无 I/O；三条登录路径、二次验证与刷新都调用。
  拒绝 → 403 `SIGN_IN_REFUSED`，写登录记录 `REFUSED`、不计失败；刷新时拒绝 → 401 并吊销该令牌族。**已签发的访问令牌在到期前（缺省 15 分钟）仍可用**。
- **登录记录带来源**：`SecLoginRecord` 已有 `entry`、`clientIp`、`userAgent`（M-02 的时间、IP、设备）；IP 只在 `jabiz.security.trusted-proxies` 配置时取转发头。没有大致位置（设计【缺省 Q9】不做）。
  平台的登录记录数据视图需要 `security.login-record.read`（只有 `QB_ADMIN_SUPER` 有）；**没有"本人的登录记录"接口**。
- **事务邮件**（D35）：`MailTemplate` Bean + 消息 `mail.<名>.subject|body`；流程中 `SendMail.of / when`，经 Outbox 投递；`jabiz.mail.enabled` 缺省关闭（关闭时记 `SKIPPED`）。
  应用模板不得用 `jabiz.` 前缀。链接只有全局 `{baseUrl}`；平台的验证 / 找回密码链接是 `{baseUrl}/auth/<页>?entry=…&token=…`，由通用后台的落地页处理后跳到入口的 `app-path`。
- **没有本人改密码**：平台只有管理员的 `SEC_USER_SET_PASSWORD` 与匿名的找回密码（平台缺口 1）。密码规则至少 10 个字符（`jabiz.security.password.min-length`）。
- **会话时长是全局的**：访问令牌 15 分钟、闲置 `jabiz.security.session.idle-timeout`、刷新令牌 8 小时，不能按入口设置（平台缺口 2）。
- **审批**（Q4 已核实，同样适用）：没有成立的规则时 `RequireApproval` 得出 `NOT_REQUIRED`（应用自己挡住）；内容哈希变了的请求作废；`RequireApproval` 自己建审批待办（18 §5.2）；
  继续原流程靠订阅 `jabiz.approval.approved` / `rejected`（每个订阅收到所有审批对象的事件，按 `subject` 过滤）；规则只经受控变更，由 `QB_SETUP` 提出。
- **`internal()` 仍照常检查权限**：D41（"只允许系统身份执行"）待批准；批准之前，系统流程沿用 Q4 的做法：权限不授予任何角色（D-Q2-8）。

## 设计决定（待确认，见文末问题）

### D-Q2-1 三个入口与"待激活"【待确认 Q2-1、Q2-13】

`application.yml`（`backend/quizbuks`）：

| 入口 | `accepted-roles` | `self-registration` | `registration-roles` | `require-verified-email` | `app-path` |
|---|---|---|---|---|---|
| `admin` | `${QB_ADMIN_ROLES:ADMIN,QB_ADMIN_SUPER,QB_ADMIN_CONTENT,QB_ADMIN_FINANCE}`（`ADMIN` 是首个管理员的平台角色） | 否 | — | 否 | `/admin/` |
| `app` | `QB_TAKER` | `${QB_APP_SELF_REGISTRATION:true}` | `QB_TAKER` | **否** | `/` |
| `sponsor` | `QB_SPONSOR` | `${QB_SPONSOR_SELF_REGISTRATION:true}` | `QB_SPONSOR` | **是** | `/sponsor/` |

- **待激活 = 邮箱未验证**（需求 U-06、5.3-11）。答题人在 App 中未验证也能登录（浏览首页、看欢迎页、重发验证邮件），但 QuizBuks 的**每一个写入流程**都声明 `requiresVerifiedEmail()`：
  本阶段的 `QB_PROFILE_SAVE`、`QB_SPONSOR_APPLY`、`QB_SPONSOR_PROFILE_SAVE`；之后的 Q5（答题、领奖）、Q6（收款账户）照此。这同时满足"待激活用户不可答题领奖"，并使可预占的账号上留不下应用数据（平台缺口 5）。
- 商家入口要求已验证才能登录：商家后台没有不写数据的用途，内容工坊的写入（Q3、Q4 流程）不必逐个补 `requiresVerifiedEmail`。
  Q3、Q4 的流程仍补上 `requiresVerifiedEmail()`，作为防御（管理员改了商家的邮箱后，入口检查在下一次刷新时才生效）。
- 同一邮箱既是答题人又是商家：在另一个入口注册即收到"你已有账号"，点击后追加角色（平台）；App 的令牌拿不到商家权限，反之亦然。
- 邮件：`jabiz.mail.enabled`、`jabiz.mail.base-url` 与 SMTP 设置来自环境变量（`deploy/quizbuks/README.md`）。在 16c 提供应用自己的验证页之前，`base-url` 取 `https://<主机>/admin`，
  平台的落地页在通用后台中处理链接，完成后跳到入口的 `app-path`（`/`、`/sponsor/` 的页面随 16c 加入；平台缺口 6）。
- 部署：在反向代理之后运行时设置 `jabiz.security.trusted-proxies`，否则登录记录的 IP 都是代理的地址。
- 测试配置（`src/test/resources/config/application.properties`）：两个注册入口关闭注册（各 IT 用 `TestTokens`），注册相关的 IT 与场景以 `properties` 打开（同平台示范）。

### D-Q2-2 资料 `QbProfile`【待确认 Q2-12、Q2-15】

- 主键即用户编号 `userId`（不生成，取自操作人，`asText(128)`，同 Q4 的 `sponsorId`）：每人至多一份，`LoadEntity.by` 直接按操作人读取。
- 字段：`displayName`（必填，≤ 100，非空白）、`countryCode`（≤ 2，`[A-Z]{2}`；流程按 `QbCountry.code`（不可变，有索引）检查存在，否则 422 `QB_COUNTRY_UNKNOWN`）、`city`（≤ 100，可选）、
  `preferredCurrency`（静态字典 `urn:jabiz:dict:quizbuks:currency`：USD / EUR / GBP / JPY / CNY / INR，只用于显示参考，U-04）；`processOnly` 的 `createdAt`、`editedAt`、`revision`。
- `countryCode`、`preferredCurrency` 在实体上可空：商家也有资料（只用 `displayName`，由入驻流程写入）。**答题人的 `QB_PROFILE_SAVE` 要求两者都有**（U-03 用于地区投放）；Q5 的开始答题在没有资料或没有国家时拒绝（`QB_PROFILE_REQUIRED`）。
- 邮箱不在资料中，QuizBuks 不提供修改邮箱（U-70"邮箱不可修改"；只有平台管理员能改）。`SecUser.displayName` 只在注册时写入，之后应用显示的名字以 `QbProfile.displayName` 为准（平台缺口 10）。
- U-03 自动定位【待确认 Q2-9】：前端按浏览器的语言区域与时区预填国家（不调用外部反向地理编码，不请求定位权限），城市手填；后端只保存结果。
- P-01 的"注册时间"取 `QbProfile.createdAt`（第一次保存资料，即注册第二步）；没有补资料的用户在按注册时间筛选时不出现【待确认 Q2-15】。

### D-Q2-3 商家入驻与审核【待确认 Q2-4、Q2-5、Q2-6、Q2-14】

```
（未申请：只有 QB_SPONSOR 角色，没有 QbSponsor）
   └─ QB_SPONSOR_APPLY ─► PENDING ──批准──► APPROVED（终态；资料可改，不再审核）
                │   ▲         └──驳回（附原因）──► REJECTED ── QB_SPONSOR_APPLY ──┘
                │   └─ 待审核时再次申请：新请求取代旧请求（内容哈希变化，平台作废旧的）
                └─ 自动通过（受控参数 qb.review.sponsor.auto 为真）─► APPROVED
```

- **实体 `QbSponsor`**（主键 `userId`，即 Q4 的 `sponsorId`）：`company`（必填，≤ 100）、`intro`（Markdown，≤ 2 000，单一语言，同 Q3 D-Q3-7）、`avatar`（`FileKind.of("qb.sponsor-avatar")`，可选）；
  `processOnly`：`reviewStatus`（字典 `urn:jabiz:dict:quizbuks:sponsor-review-status`）、`termsAcceptedAt`、`approvalRequestId`、`submittedAt`、`approvedAt`、`rejectReason`（≤ 500）、`rejectedAt`、`revision`、`editedAt`。
  显示字段 `company`。状态机 `eb.stateTransitions("reviewStatus", st -> st.initial(PENDING)…)`：`PENDING → APPROVED / REJECTED`，`REJECTED → PENDING`。
- **商家的名字**（M-01、M-03 的"姓名"）写在 `QbProfile.displayName`，由入驻流程一并写入（没有资料即新建，只填名字）。
- **条款**：输入 `acceptTerms` 必须为真（否则 422 `QB_SPONSOR_TERMS_REQUIRED`），记下 `termsAcceptedAt`。条款文本与版本随 Q7（`QbContentPage` 的 `terms`）；届时加 `termsVersion`【待确认 Q2-6】。
- **头像**：文件策略 `qb.sponsor-avatar`（JPEG / PNG / WebP，≤ 2 MB，重新编码去 EXIF；上传权限 `qb.sponsor.me`，读取权限 `qb.sponsor.me`、`qb.play`（Q5 的"发布人"）、`qb.admin.users.read`）。
  必须由本人上传（沿用 Q3 `QuizEditing.requireOwnFile` 的做法，经平台文件数据视图读 `uploadedBy`），否则 422 `QB_FILE_NOT_OWN`。
- **审批对象**：`ApprovalSubject.define("qb.sponsor", s -> s.entity("QbSponsor").bool("autoApprove"))`；显示名 `approval.subject.qb.sponsor`；`ApprovalCase.reference(company)`。
  绑定的内容：`{displayName, company, intro, avatar, termsAcceptedAt}`（`identity.SponsorApprovals.content`，规范化后用平台 `ContentHash`）。
- **规则**：`QB_SETUP` 以受控变更提出 `qb.sponsor.review`：条件 `{"fact":"autoApprove","op":"eq","value":false}`、层级 `[{"permission":"qb.review.sponsor"}]`、优先级 100（`QbApprovalRules.SPONSOR_REVIEW`，记 `approval-rule:qb.sponsor.review`，只提一次）。
- **自动通过**：提交时 `LoadParams.of(opTime, …, qb.review.sponsor.auto)` 作为事实 `autoApprove`（受控参数，Q1 已建）；参数未发布 → 422 `PARAM_NOT_FOUND`。
  `autoApprove = false` 而结论为 `NOT_REQUIRED`（规则未发布或已停用）→ 422 `QB_REVIEW_NOT_CONFIGURED`（沿用 Q4 的码与文案，常量移到共用的 `setup.ReviewCodes`）。
- **审批结论**：

  | 结论 | 去向 |
  |---|---|
  | `PENDING` | `PENDING`，记 `approvalRequestId`、`submittedAt`，清空 `rejectReason` |
  | `NOT_REQUIRED`（且 `autoApprove = true`）或 `APPROVED`（同样内容此前已批准） | `APPROVED`，记 `approvedAt`，发 `qb.sponsor.approved` 邮件 |

- **继续原流程** `QB_SPONSOR_APPROVAL_RESULT`（订阅 `qb.sponsor-approved` / `qb.sponsor-rejected`，按 `subject` 忽略其他对象；照 Q4 评审 S1 / R1 的做法）：
  - 输入带 `requestId`，只有等于 `QbSponsor.approvalRequestId` 且状态为 `PENDING` 才处理；
  - 批准：再次 `RequireApproval`（`preparedBy` = 原准备人）得到的就是这个请求且为 `APPROVED` → `APPROVED`、`approvedAt`，发 `qb.sponsor.approved`；
  - 驳回：不信事件，读存储的请求（须为 `REJECTED`、同一对象与商家、内容哈希等于当前内容）→ `REJECTED`、`rejectReason` = 审批人的理由，发 `qb.sponsor.rejected`（带理由），
    并 `CreateTask` 给该商家本人：`TaskSpec.forUser("qb.sponsor.resubmit", "task.qb.sponsor.resubmit", …, userId)`，来源键 `qb.sponsor:<userId>`；
  - 其他情况（已再次申请、已批准）什么也不做。
  `QB_SPONSOR_APPLY` 在提交时 `CloseTasks` 关闭来源键 `qb.sponsor:<userId>` 的待办（驳回后的"请修改后重新提交"）。
- **批准之后**改资料用 `QB_SPONSOR_PROFILE_SAVE`，**不再审核**，历史由时态记录与审计保留【待确认 Q2-4】；`QB_SPONSOR_APPLY` 对已批准的商家 422 `QB_SPONSOR_ALREADY_APPROVED`。
  未批准时改资料就是再次申请（`QB_SPONSOR_PROFILE_SAVE` 对未批准的 422 `QB_SPONSOR_NOT_APPROVED`）。
- **不做**：撤回申请、撤销已批准的入驻（需要时以封禁处理，D-Q2-4）、商家注册时的审批之外的人工资料核验【待确认 Q2-5】。
- 审核人需持有 `approval.decide` 与 `qb.review.sponsor`（`QB_ADMIN_CONTENT`、`QB_ADMIN_SUPER` 已有）；准备人不能审批自己的请求（平台）。

### D-Q2-4 封禁【待确认 Q2-2、Q2-3】

- **单独的时态实体 `QbUserBan`**（主键 `userId`），而不是设计 §3.1 的 `QbProfile.status`：封禁不依赖用户是否补过资料（没有资料的人、只是商家的人都能封禁），
  资料的字段也不必因此变成可空；时态历史即封禁 / 解封的记录。字段：`banned`（`processOnly`）、`reason`（≤ 500，封禁时必填）、`bannedAt`、`bannedBy`、`liftedAt`、`liftedBy`、`liftNote`（≤ 500）、`revision`。
  设计 §3.1 随之修改（删去 `QbProfile.status` / `banReason`）。
- **`QbSignInGuard`**（`SignInGuard` Bean）：`loads()` = `SignInLoad.of("ban", "urn:jabiz:dataset:default:QbUserBan", "userId")`；有一行且 `banned = true` → `SignInDecision.refuse("banned")`，否则放行。
  适用于全部入口（管理员不会被封禁，见下）。登录得 403 `SIGN_IN_REFUSED`、记 `REFUSED`、不计失败；下一次刷新 401 并吊销令牌族。
- **生效时间**：已签发的访问令牌在到期前（缺省 15 分钟）仍可用（平台缺口 3）。所以凡是有价值的动作在流程中**再查一次**：Q4 的提交发布（经 `SponsorChecks`，本阶段接上）、
  Q5 的开始答题与提交、Q6 的转账（"封禁后不可提现"），都读 `QbUserBan` 并以 422 `QB_USER_BANNED` 拒绝。
- **流程** `QB_USER_BAN` / `QB_USER_UNBAN`（`qb.admin.users.ban`，`actsOn("SecUser", "userId")` 与 `actsOn("QbProfile", "userId")`）：
  不能封禁自己（`QB_BAN_SELF`）、不能封禁持有管理员角色（`ADMIN`、`QB_ADMIN_*`，经平台用户角色数据视图读取）的用户（`QB_BAN_ADMIN`：停用管理员用平台的 `enabled`）；
  已封禁再封禁、未封禁就解封 → 422 `QB_BAN_STATE`。用户不存在 → 404。
- 封禁**不**暂停该商家的发布、不冲正余额（需求只写"不可登录、不可提现"）；需要时由管理员另行暂停【待确认 Q2-3】。封禁与解封不发邮件【待确认 Q2-3】。

### D-Q2-5 登录审计（M-02）

- 记录由平台完成（入口、时间、结果、IP、User-Agent）；QuizBuks 不另建表。"设备"由页面从 User-Agent 解析显示；"大致位置"不做（【缺省 Q9】）。
- **管理员**：平台登录记录数据视图（`security.login-record.read`，`QB_ADMIN_SUPER`）。
- **本人**：应用数据视图 `urn:jabiz:dataset:me:SecLoginRecord`（目标实体 `SecLoginRecord`，`fromContext("userId", actorId)`，只读，权限 `qb.account.read`）与模板 `qb.me.sign-ins`（最近的记录，按时间倒序）。
  开工第一步先核实"应用能否在平台实体上声明自己的数据视图"；不能则改为平台缺口 7，本人查看随 16g（会话列表）一起做，Q2 只交付管理员查看。

### D-Q2-6 `SponsorChecks` 的接线（Q4 遗留）

- `publishing.SponsorChecks` 改为纯函数 `List<Violation> check(Map<String, Object> sponsor, Map<String, Object> ban)`：没有 `QbSponsor` 或 `reviewStatus ≠ APPROVED` → `QB_SPONSOR_NOT_APPROVED`；
  `QbUserBan.banned` → `QB_USER_BANNED`。由 `PublicationReadiness` 一并报告（一次报告全部违规）。
- `QB_PUBLICATION_SUBMIT` 在取锁之后加两步 `QueryEntities`：读本人的 `QbSponsor` 与 `QbUserBan`（默认视图，按主键 = 操作人）。不取账户锁：Q2 中"已批准"不会撤销，封禁的竞争由下一次动作再查兜住。
- `QB_PUBLICATION_SAVE` / `COPY` 的 `problems` 同样带上这两条，确认页能提示"入驻未通过"。
- Q4 的测试夹具（`PublicationItSupport`、`ContentItSupport` 中的商家 A、B）改为先经 `QB_SPONSOR_APPLY` 并由内容管理员批准（或把 `qb.review.sponsor.auto` 发布为真）；场景 `publishing.yml` 前加入驻步骤并更新快照。
- 2026-10 以前由管理员手工建的商家账号（Q4 说明中的临时做法）没有 `QbSponsor`，Q2 之后须走一次入驻【待确认 Q2-17】。

### D-Q2-7 邮件【待确认 Q2-11】

| 模板（`MailTemplate`） | 类别 | 参数 | 何时 |
|---|---|---|---|
| `qb.welcome` | `NOTIFICATION` | `name` | 答题人第一次保存资料（注册第二步）时，由 `QB_PROFILE_SAVE` 发出（平台验证邮箱时没有给应用的挂点，平台缺口 5） |
| `qb.sponsor.approved` | `TRANSACTIONAL` | `company`、`link` | 入驻通过（人工或自动） |
| `qb.sponsor.rejected` | `TRANSACTIONAL` | `company`、`reason`、`link` | 入驻被驳回 |

- 标题与正文三语（`mail.qb.<名>.subject|body`），正文 Markdown。`{baseUrl}` 指向后台（D-Q2-1），所以商家后台的地址作为参数 `link` 传入，取自应用配置 `quizbuks.links.sponsor`（缺省 `/sponsor/` 拼在 `jabiz.mail.base-url` 的主机上）。
- 激活邮件、"你已有账号"、找回密码都是平台的（`jabiz.auth.*`），QuizBuks 不另写；P-08 的"邮件队列"即平台 Outbox。

### D-Q2-8 系统流程与 D41

- `QB_SPONSOR_APPROVAL_RESULT` 只由审批事件以系统身份运行：`internal()`，权限 `qb.sponsor.system`（不授予任何角色），同 Q4 的 `qb.publication.system`。
- D41 获批后（平台提供"只允许系统身份执行"的声明），`qb.publication.system` 与 `qb.sponsor.system` 一起改用它并删去这两个权限（另一个小 PR）。在此之前，持有 `*` 的平台管理员仍能直接调用，伪造的结果因"只认自己的请求"而不起作用。

### D-Q2-9 社交登录（16f 之后）

- 平台 16f（D39）合入后：`jabiz.security.oidc.providers[google|apple]`（客户端密钥来自环境变量）、`auto-provision: true`，入口 `app` 允许注册即可自动开户（邮箱视为已验证、授予 `QB_TAKER`）；
  商家入口是否开放社交登录【待确认 Q2-16，缺省只开放 App】。
- 应用侧无新流程：自动开户的用户第一次进入 App 时同样缺资料，页面引导到补充资料（第二步）。已有邮箱的账号不自动关联，用户在设置中自行关联（平台 `SEC_IDENTITY_LINK_SELF`）。
- 追加内容：配置、部署说明、一个 IT（伪提供方自动开户后 `QB_PROFILE_SAVE` 可用、封禁守卫照样生效），约 0.5–1 天。

### D-Q2-10 锁

- 写同一用户的账户数据（资料、入驻、封禁）的流程先取 `HoldLock.exclusive("qb.account:" + userId)`，再读取。
- 账户锁不与发布锁、钱包锁同时持有（`SponsorChecks` 只读不锁）。以后若需要同时持有，顺序为 `qb.account` → `qb.quiz` → `qb.pub` → `qb.wallet`，写入 `backend/quizbuks/CLAUDE.md`。

## 要求

1. **入口配置**（D-Q2-1）：`application.yml` 三个入口；测试配置关闭注册；`deploy/quizbuks/README.md` 增加 `QB_ADMIN_ROLES`、`QB_APP_SELF_REGISTRATION`、`QB_SPONSOR_SELF_REGISTRATION`、
   邮件（`JABIZ_MAIL_*` / `SPRING_MAIL_*`、`base-url`）、`trusted-proxies`；`docker-compose.yml` 传入这些变量（缺省邮件关闭，注册的用户因此无法验证——启动告警，README 写明）。
2. **权限**（`QbPermissions`、`QbRoles`；部署后再执行一次 `QB_SETUP`）：
   - 新增 `qb.account.read`（本人的资料与登录记录），授予 `QB_TAKER`、`QB_SPONSOR`；
   - 新增 `qb.sponsor.system`（`QB_SPONSOR_APPROVAL_RESULT`），不授予任何角色；
   - 已有的 `qb.me`（`QB_PROFILE_SAVE`）、`qb.sponsor.me`（入驻与商家资料）、`qb.admin.users.read`（管理员只读用户、资料、商家、封禁）、`qb.admin.users.ban`、`qb.review.sponsor`（审批层级）照设计 §2 使用；
   - 默认数据视图的写权限沿用不授予任何角色的 `qb.content.view.write`。
3. **字典**（静态，三语）：`urn:jabiz:dict:quizbuks:currency`（6 种）、`urn:jabiz:dict:quizbuks:sponsor-review-status`（3 种）。
4. **实体**（包 `com.jabiz.quizbuks.identity`）：3 个，见下节。
5. **数据视图**：
   - 默认视图：管理员只读（`qb.admin.users.read`），`processOnlyWrites()`；
   - 本人视图：`urn:jabiz:dataset:me:QbProfile`（`qb.account.read`）、`urn:jabiz:dataset:sponsor:QbSponsor`（`qb.sponsor.me`）、`urn:jabiz:dataset:me:SecLoginRecord`（`qb.account.read`，D-Q2-5），都 `fromContext("userId", actorId)`、只读、不带历史；
   - `QbUserBan` 没有本人视图（被封禁的人在登录时得知）。
6. **文件策略** `qb.sponsor-avatar`（D-Q2-3）。
7. **审批对象与规则**：D-Q2-3；`QbApprovalRules.ALL` 加 `SPONSOR_REVIEW`，`QB_SETUP` 的 `rulesProposed` 随之为 2。
8. **流程**：6 个，见"流程"一节；`SignInGuard`：`QbSignInGuard`。
9. **事件订阅**：`qb.sponsor-approved`、`qb.sponsor-rejected` → `QB_SPONSOR_APPROVAL_RESULT`。
10. **邮件模板**：3 个（D-Q2-7）；待办标题消息 `task.qb.sponsor.resubmit`。
11. **SQL 模板**：`qb.me.sign-ins`、`qb.admin.users`（P-01）、`qb.admin.sponsors`（P-09 入驻队列的查询），见"SQL 模板"一节。
12. **Q4 衔接**：D-Q2-6；Q3、Q4 的商家流程补 `requiresVerifiedEmail()`（D-Q2-1）。
13. **消息**（三语，启动检查齐全）：实体与字段、字典、6 个流程、全部新 `QB_*` 码、`approval.subject.qb.sponsor`、邮件、待办标题、模板与列名、`param.qb.review.sponsor.auto`（已有则不变）。
14. **文档**：
    - `02-design.md` §1（入口表加"注册 / 要求验证"两列）、§2（新权限）、§3.1（`QbProfile` 主键与可空字段、`QbSponsor` 字段、新 `QbUserBan`，删去 `QbProfile.status`）、§4.3（账户锁）、§5.2（新流程）、§9（未激活与封禁的检查位置）、§10（G1–G4 已完成，新缺口）；
    - `03-plan.md` 的 Q2 行；
    - `backend/quizbuks/CLAUDE.md` 加账户约定：入口、"一切写入要求已验证"、账户锁、封禁在动作中再查、审批规则经 `QB_SETUP` 提出；
    - `deploy/quizbuks/README.md`（要求 1）。

## 实体与迁移

记号：T = 时态（只追加）。主键都是用户编号 `userId`（`asText(128)`，不可变，不生成，取自操作人或流程输入），一人至多一行；引用平台用户（`asReference("SecUser")`，同 Q1 账本维度的写法）。

| 实体（表） | 类型 | 字段 |
|---|---|---|
| `QbProfile`（`qb_profile_version`） | T | `userId`；`displayName`；`countryCode`；`city`；`preferredCurrency`；`processOnly`：`createdAt`、`editedAt`、`revision`。显示字段 `displayName` |
| `QbSponsor`（`qb_sponsor_version`） | T | `userId`；`company`；`intro`；`avatar`（文件，列 `uuid`）；`processOnly`：`reviewStatus`、`termsAcceptedAt`、`approvalRequestId`、`submittedAt`、`approvedAt`、`rejectReason`、`rejectedAt`、`revision`、`editedAt`。显示字段 `company`；状态机见 D-Q2-3 |
| `QbUserBan`（`qb_user_ban_version`） | T | `userId`；`processOnly`：`banned`、`reason`、`bannedAt`、`bannedBy`、`liftedAt`、`liftedBy`、`liftNote`、`revision` |

**迁移** `db/migration/V5__quizbuks_accounts.sql`：
- 沿用 V3 / V4 的建表函数写法：`UNIQUE(user_id, version_no)`、`(user_id, effect_start_time DESC, version_no DESC)`、`process_seq_id` 索引、指向 `op_process` / `entity_registry` 的外键、`jabiz_protect_append_only`，用后删除函数。
- 不为可变字段建索引（国家、状态、公司名的筛选在管理员模板中按当前版本扫描，见风险）。
- `QbProfile.userId` 等的外键指向平台用户表（同 `sec_user_role` 的写法），确认平台允许应用迁移引用平台表；不允许则只在实体上声明引用。

## 流程

共同点：
- 输入为 record，Bean Validation（400）；写入经 `ctx.changes()`；违规累积后 422；输出至少有 `userId`、`revision`（入驻另有 `reviewStatus`）。
- 本人的流程以操作人为 `userId`（不接受输入中的用户编号），先取 `qb.account:<userId>` 锁再读取。
- 写入流程都 `requiresVerifiedEmail()`（D-Q2-1）。

| 流程 | 权限 | 输入 | 要点 | 错误代码 |
|---|---|---|---|---|
| `QB_PROFILE_SAVE` | `qb.me`，`requiresVerifiedEmail` | `displayName`、`countryCode`、`city?`、`preferredCurrency`、`baseRevision?` | 没有即新建（`createdAt`）并发 `qb.welcome`；国家按 `QbCountry.code` 检查；有改动才写，`revision + 1`（U-02 第二步、U-03、U-04、U-70） | `QB_COUNTRY_UNKNOWN`、`QB_PROFILE_CHANGED`、字段规则 |
| `QB_SPONSOR_APPLY` | `qb.sponsor.me`，`requiresVerifiedEmail` | `displayName`、`company`、`intro?`、`avatar?`、`acceptTerms`、`baseRevision?` | 账户锁 → 读商家与资料 → 写资料名字与 `QbSponsor` → `LoadParams` → `RequireApproval` → 按 D-Q2-3 定状态 → `CloseTasks(qb.sponsor:<userId>)` → 自动通过时 `SendMail(qb.sponsor.approved)`（M-01、P-09） | `QB_SPONSOR_ALREADY_APPROVED`、`QB_SPONSOR_TERMS_REQUIRED`、`QB_FILE_NOT_OWN`、`QB_REVIEW_NOT_CONFIGURED`、`PARAM_NOT_FOUND`、`QB_SPONSOR_CHANGED` |
| `QB_SPONSOR_PROFILE_SAVE` | `qb.sponsor.me`，`requiresVerifiedEmail` | `displayName`、`company`、`intro?`、`avatar?`、`baseRevision?` | 只限 `APPROVED`；不审核（M-03） | `QB_SPONSOR_NOT_APPROVED`、`QB_FILE_NOT_OWN`、`QB_SPONSOR_CHANGED` |
| `QB_SPONSOR_APPROVAL_RESULT` | `qb.sponsor.system`（不授予任何角色），`internal()` | 审批事件（`subject`、`entityId`、`status`、`requestId`、`preparerId`、`contentHash`、`reason`） | D-Q2-3：只认自己的请求；批准 / 驳回、邮件、驳回时 `CreateTask` | — |
| `QB_USER_BAN` | `qb.admin.users.ban` | `userId`、`reason` | `actsOn(SecUser)`、`actsOn(QbProfile)`；账户锁；读用户与角色分配；写 `QbUserBan`（P-02） | `QB_BAN_SELF`、`QB_BAN_ADMIN`、`QB_BAN_STATE`、用户 404 |
| `QB_USER_UNBAN` | 同上 | `userId`、`note?` | `actsOn`（`banned = true` 时）；写 `liftedAt` 等 | `QB_BAN_STATE` |

**`SignInGuard`**：`identity.QbSignInGuard`（D-Q2-4），判断逻辑是纯 Java 的 `identity.BanRules.refuses(rows)`（单元测试）。

**纯 Java 部件**（无 I/O，单元测试）：`AccountLimits`（长度上限）、`SponsorStatus`（常量）、`SponsorApprovals`（事实与绑定的内容）、`SponsorChecks`（D-Q2-6）、`BanRules`、`AccountCodes`（错误码）。

**错误码**：字段规则 400；流程拒绝 422；用户不存在 404；无权限 403；未验证 403 `EMAIL_NOT_VERIFIED`（平台）；经数据视图直接写入被拒（平台）。全部在 `identity.AccountCodes`；`QB_REVIEW_NOT_CONFIGURED` 移到 `setup.ReviewCodes` 由两处共用。

## SQL 模板

- **`qb.me.sign-ins`**（M-02 本人，权限 `qb.account.read`）：本人视图 `urn:jabiz:dataset:me:SecLoginRecord`；列 `attemptTime`、`outcome`、`entry`、`factor`、`clientIp`、`userAgent`；筛选 `entry`、`outcome`、`attemptTime`；默认按 `attemptTime` 倒序。
- **`qb.admin.users`**（P-01，权限 `qb.admin.users.read`）：`SecUser` 左连接 `QbProfile`、`QbSponsor`、`QbUserBan`、`QbCountry`；
  列 `userId`、`userName`、`email`、`emailVerified`（`lower(verifiedEmail) = lower(email)`）、`enabled`、`displayName`、`countryCode`、`countryName`、`city`、`preferredCurrency`、`registeredAt`（= `QbProfile.createdAt`）、
  `sponsorStatus`、`company`、`banned`；参数 `q`（≤ 100，按用户名、邮箱、名字、公司搜索，写法同 `qb.sponsor.quizzes` 的 `strpos(lower…)`）、`region`（按 `QbCountry.regions` 含该地区）；
  筛选 `countryCode`、`registeredAt`、`sponsorStatus`、`banned`、`emailVerified`。答题历史、资金流水（P-01 详情）随 Q5、Q6 加到扩展页。
  模板读取平台实体须核实：模板的权限之外，平台实体的数据视图权限（`security.user.read`）是否另行检查；若检查，`qb.admin.users.read` 的持有者另授 `security.user.read`（只读）。
- **`qb.admin.sponsors`**（P-09，权限 `qb.admin.users.read`）：`QbSponsor` 连接 `QbProfile`、`SecUser`；列 `userId`、`company`、`displayName`、`email`、`reviewStatus`、`submittedAt`、`approvedAt`、`rejectReason`、`approvalRequestId`；
  筛选 `reviewStatus`、`submittedAt`；默认按 `submittedAt` 倒序。审核动作仍在 `/tasks` 的审批面板中。

## 测试

集成测试连本地 PostgreSQL（`JABIZ_TEST_DB_*`），BlockHound 开启。注册与邮件的测试用 GreenMail（`testImplementation("com.icegreen:greenmail-junit5:2.1.3")`，同平台 `app`），从收到的邮件中取链接令牌。
令牌经 `QbItSupport`：答题人、商家 A / B（已验证）、未验证的答题人与商家（`TestTokens.unverified`）、内容管理员（审核人）、超级管理员（封禁、提出规则）、财务管理员（发布受控变更）、无权限者。
每个类的准备步骤：执行 `QB_SETUP`，由财务管理员发布全部提案（参数与两条规则）。

- **单元**：
  - `SponsorApprovalsTest`：键顺序不影响哈希，任一字段变化即改变哈希；
  - `SponsorChecksTest`：无记录、`PENDING`、`REJECTED`、`APPROVED`、已封禁，一次报告两条；
  - `BanRulesTest`：无行、未封禁、已封禁、已解封、多行；
  - `QbRolesTest`：新授权；`qb.sponsor.system` 不授予任何角色；
  - `MessagesTest`（新消息三语）；邮件模板的占位符由平台 `MailChecks` 在启动时检查。
- **`AccountSignUpIT`**（U-02、U-06、U-07、M-01、M-02，GreenMail，打开两个入口的注册）：
  - 在 `app` 注册 → 用户有 `QB_TAKER`、未验证，收到验证邮件；未验证可登录 `app`，但 `QB_PROFILE_SAVE` 403 `EMAIL_NOT_VERIFIED`；点击链接验证 → 刷新后令牌 `email_verified = true` → 保存资料成功并收到 `qb.welcome`；
  - 在 `sponsor` 注册 → 未验证登录 403 `EMAIL_NOT_VERIFIED`；验证后登录成功；
  - 入口隔离：答题人登录 `sponsor` / `admin` 得 401 并记 `NO_ROLE`；同一邮箱在 `sponsor` 再注册 → "你已有账号" → 加入后兼有两个角色，`app` 的令牌调用商家流程 403，`sponsor` 的令牌调用 `QB_PROFILE_SAVE` 403；
  - `admin` 入口不接受注册（422 `SIGN_UP_DISABLED`）；`QB_SETUP` 之前注册 422 `SIGN_UP_DISABLED`；
  - 可预占的账号：攻击者先以受害人邮箱注册，留不下任何 QuizBuks 数据（写入一律 403），受害人加入后接管（平台），`QbProfile` / `QbSponsor` 中没有攻击者的数据。
- **`ProfileIT`**（U-70、U-03、U-04）：新建与修改、`revision`、`baseRevision` 过期 422、未知国家 422、币种字典、名字空白 400；本人视图只看到自己；管理员只读全部；经数据视图直接写一律被拒；商家没有 `qb.me` → 403。
- **`SponsorOnboardingIT`**（M-01、M-03、P-09）：
  - 申请 → `PENDING`，内容管理员有审批待办 → `APPROVAL_DECIDE` 批准 → 投递事件 → `APPROVED`，收到 `qb.sponsor.approved`；
  - 驳回附理由 → `REJECTED`、理由可见、收到 `qb.sponsor.rejected`、商家有"重新提交"待办 → 修改后再申请：待办关闭、得到新请求；
  - 待审核时再次申请：旧请求作废、旧待办取消、新请求照常审批；旧请求迟到的驳回事件被忽略；
  - 自动通过（受控参数改为真，经提出与发布）→ 直接 `APPROVED`，留有评估记录；参数未发布 → 422 `PARAM_NOT_FOUND`；规则停用而参数为假 → 422 `QB_REVIEW_NOT_CONFIGURED`；
  - 未同意条款 422；头像不是本人上传的 422；已批准再申请 422；未批准改资料 422；批准后改资料不改变状态；
  - 伪造或别人的审批结果（持有 `*` 的管理员直接调用）什么也不改变；内容管理员直接调用 `QB_SPONSOR_APPROVAL_RESULT` 403；
  - 商家不能审批自己的请求（平台）。
- **`SponsorChecksIT`（Q4 衔接）**：未申请、待审核、被驳回的商家提交发布 → 422 `QB_SPONSOR_NOT_APPROVED`，保存的 `problems` 中有该条；批准后提交成功；被封禁的商家提交 → 422 `QB_USER_BANNED`。
  Q4 原有的各发布 IT 在新夹具下（商家已入驻）照常通过。
- **`BanIT`**（P-02、5.3-11）：
  - 封禁 → 三个入口登录都 403 `SIGN_IN_REFUSED`、记 `REFUSED`、失败计数不变；已有会话下一次刷新 401 且令牌族被吊销；解封后可登录；
  - 不能封禁自己、管理员；重复封禁 / 未封禁就解封 422；无 `qb.admin.users.ban` 403；
  - 封禁与解封的历史可在 `QbUserBan` 的历史中查到，审计有记录。
- **`SignInAuditIT`**（M-02）：登录记录带 `entry`、`clientIp`、`userAgent`（配置可信代理时取 `X-Forwarded-For`）；`qb.me.sign-ins` 只返回本人的记录；管理员经平台视图看全部。
- **模板**：`qb.admin.users` 的搜索（用户名、邮箱、名字、公司，不区分大小写，`%` `_` 按字面）、按国家、地区、注册时间、封禁、入驻状态筛选；`qb.admin.sponsors` 的筛选与排序。
- **`SetupIT` 扩展**：第二条规则只提出一次；发布后再次执行不重复提出；停用后不加回；新权限授予。
- **其他**：每个集成测试类断言 `qb_profile_version`、`qb_sponsor_version`、`qb_user_ban_version` 上没有 UPDATE / DELETE。`QuizbuksAppIT` 检查三个入口已加载、启动检查无 `SECURITY` 错误。
- **场景回放** `scenarios/qb/accounts.yml`（固定时钟；`properties` 打开两个入口的注册并限定后台角色；邮件关闭，消息为 `SKIPPED`）：
  1. 执行 `QB_SETUP`，财务管理员发布提案；
  2. `SPONSOR_SIGN_UP` 在 `app` 与 `sponsor` 各注册一人（快照含用户、角色分配、验证邮件消息）；
  3. 以新用户为操作人（回放的操作人视为已验证；平台缺口 12）：答题人保存资料（`qb.welcome` 消息）；商家申请入驻；
  4. 内容管理员驳回 → 投递事件 → `REJECTED` 与待办；商家修改后再申请；内容管理员批准 → `APPROVED`；
  5. 超级管理员封禁答题人 → 解封；
  6. 期望错误：商家调用 `QB_USER_BAN`（403）、封禁自己（422）。
  `setup.yml` 的快照随新授权与第二条规则提案更新；`publishing.yml` 前加入驻步骤并更新快照。
- `platformCheck` 通过（`SECURITY`、`MAIL`、`APPROVAL`、`EVENT` 类检查）；`ArchitectureTest` 不变。

## 验收标准

- [ ] `./gradlew :quizbuks:check` 通过（含 `platformCheck`）；`tools/check-app-paths.sh` 通过。
- [ ] U-02（邮箱部分）：在 `app` 注册（平台接口）后补充资料两步完成；Google / Apple 在 16f 后追加（D-Q2-9）。
- [ ] U-03 / U-04 / U-70：资料含名字、国家（字典中存在）、城市、偏好币种；邮箱不能经 QuizBuks 修改。定位预填属于页面阶段。
- [ ] U-06 / 5.3-11：未验证即待激活；未验证的用户不能写入任何 QuizBuks 数据（Q5 起不能答题领奖），验证后即正常。
- [ ] U-07 / M-02：三个入口分开登录，可用用户名或已验证的邮箱；只持有其他入口角色的用户被拒并记录；登录记录带时间、入口、IP、设备（UA），本人与管理员可查看（大致位置不做）。
- [ ] U-71：**依赖平台缺口 1**；在此之前以找回密码代替（验收时注明）。
- [ ] M-01：商家注册（平台接口）+ 入驻申请（名字、公司、简介、头像、同意条款）→ 待审核，通过后才能提交发布（`SponsorChecks` 生效）；可配置为自动通过。钱包随 Q5 / Q6。
- [ ] M-03：批准后可改名字、公司、简介、头像；公开部分供 Q5 的"发布人"使用。
- [ ] P-01（Q2 的部分）：按关键字、注册时间、国家 / 地区查用户，看到资料、入驻与封禁状态；银行（收款账户）、答题历史、资金流水随 Q5、Q6。
- [ ] P-02：封禁后不能登录（三个入口，刷新即失效），提交发布被拒；Q6 的转账照此检查；解封恢复。
- [ ] P-09（入驻）：审核队列即平台待办；通过 / 驳回附原因，商家收到邮件，驳回后有"重新提交"待办；可经受控参数切换为自动通过；规则未配置时拒绝而不是放行。
- [ ] P-08（部分）：激活与欢迎邮件经平台事务邮件投递（邮件服务器的配置在部署）。
- [ ] 时态表上没有 UPDATE / DELETE；消息三语齐全；文档已更新；部署后执行 `QB_SETUP`，并由另一位管理员发布新的规则提案。

## 风险

- **邮件关闭即无法激活**：`jabiz.mail.enabled` 缺省关闭，部署忘记配置时注册的用户永远是待激活（平台启动告警）。README 与部署检查清单写明；`QB_SETUP` 的输出提示邮件未开启。
- **规则未发布的窗口**：新装系统在财务管理员发布 `qb.sponsor.review` 之前，人工审核的申请一律被拒（`QB_REVIEW_NOT_CONFIGURED`），同 Q4，写进部署说明。
- **封禁的 15 分钟**：访问令牌到期前被封禁的人仍可调用接口。以"有价值的动作在流程内再查"兜住（发布、答题、转账）；彻底解决依赖平台缺口 3。
- **App 的会话时长**：全局的闲置锁定与 8 小时刷新令牌对手机用户不友好（设计【缺省 Q12】要求 App 不闲置锁定、刷新令牌 30 天），只有等平台缺口 2；在 App 页面上线（16c 之后）之前须解决。
- **管理员模板扫描当前版本**：`qb.admin.users` 的筛选条件都在可变字段上，用户到百万级时较慢（受查询时限保护，超时 503）。先按需求做，Q10 压测时评估；需要时把常用筛选列放到普通表。
- **平台实体上的应用数据视图与模板**：本人登录记录（D-Q2-5）与 `qb.admin.users` 依赖"应用可以在平台实体上声明数据视图 / 在模板中读取平台实体"，开工第一天核实；不行时登录记录的本人查看移到平台缺口 7，用户模板改为两步（模板只读 QuizBuks 实体，用户信息由页面经平台接口补全）。
- **Q4 测试的改动面**：`SponsorChecks` 接上后，Q4 的全部发布测试与场景都要先让商家入驻；工作量计入预估。
- **入口配置的升级**：已有安装（开发与测试环境）中只有 `admin` 入口时，配置三个入口后，`admin` 不再接受 `QB_TAKER` / `QB_SPONSOR`；此前在后台登录的测试商家须改用 `sponsor` 入口（页面在 16c 之前没有，开发时用 API）。

## 预估

| 工作 | 天数 |
|---|---|
| 入口配置、实体、迁移、视图、字典、文件策略 | 0.5–1 |
| 纯 Java 部件、`SignInGuard`、邮件模板 | 0.5 |
| 6 个流程、审批对象与订阅、`QB_SETUP` 提出规则、待办 | 1.5 |
| `SponsorChecks` 接线与 Q3 / Q4 测试夹具、场景的改动 | 0.5–1 |
| 模板、消息、文档、部署说明 | 0.5 |
| 测试与场景（GreenMail） | 1.5 |
| **合计（不含页面）** | **约 5–6** |

页面（App 注册与资料、商家入驻与资料、管理员用户扩展页）在 16c 之后另做，约 3–4 天；社交登录在 16f 之后追加，约 0.5–1 天。

## 平台缺口（按 `backend/quizbuks/CLAUDE.md` 第 2 节在 `1.2/platform` 上补）

**没有一项阻塞 Q2 的后端**：16a、16b 已合入 `1.2/quizbuks`，入口、注册、验证、找回密码、守卫、登录来源都可直接使用。下表的"阻塞"列说明各项影响到哪里。

| 编号 | 缺口 | 影响 | 阻塞 Q2？ | 建议 |
|---|---|---|---|---|
| 1 | **本人修改密码**（当前密码 + 新密码）：平台只有管理员重设与找回密码 | U-71、M-70 | 不阻塞后端；U-71 在补上前以找回密码代替 | 平台小阶段：`POST /api/auth/account/password` → 流程（`CurrentPasswordStep` 已有）、吊销其他令牌族、写登录记录；约 0.5–1 天。在 App 页面之前完成 |
| 2 | **按入口的会话时长**（闲置、刷新令牌有效期）：现在是全局的 | 设计【缺省 Q12】：App 不闲置锁定、30 天；后台 30 分钟闲置 | 不阻塞后端；阻塞 App 页面上线的体验 | 入口配置加 `session.idle-timeout`、`refresh-token-ttl`；并入 16c 或 16g |
| 3 | **业务流程可用的"吊销某用户的全部会话"步骤**：封禁只在下一次刷新时生效 | P-02 的即时性 | 不阻塞（动作内再查兜住） | 随 16g（会话列表与吊销）提供 `RevokeUserSessions` 之类的平台步骤 |
| 4 | **只允许系统身份执行的流程**（D41，待批准） | `QB_SPONSOR_APPROVAL_RESULT`、Q4 的三个系统流程 | 不阻塞（权限不授予任何角色绕行） | D41 获批后实现，应用随即改用 |
| 5 | **注册、验证、接管不通知应用**（没有事件或挂点） | 欢迎邮件的时机；接管可预占的账号时不清理应用数据 | 不阻塞（欢迎邮件在补资料时发；一切写入要求已验证，可预占的账号上没有应用数据） | 可选：平台在验证与接管时发布 `jabiz.user.email-verified` / `jabiz.user.claimed` 事件 |
| 6 | **邮件链接按入口**：`{baseUrl}/auth/<页>` 只有一个基址，落地页在通用后台；应用模板也只有全局 `{baseUrl}` | 激活链接经后台页面再跳回 App；商家邮件的链接要自己传参数 | 不阻塞 | 16c：入口配置 `link-base`（链接到应用 SPA 自己的页面），`{entryUrl}` 占位符 |
| 7 | **本人的登录记录**：平台没有本人读取的接口 | M-02 的本人查看 | 不阻塞（先核实应用数据视图能否覆盖，D-Q2-5） | 若需要，随 16g 提供 `GET /api/auth/sign-ins` |
| 8 | **按审批对象订阅审批事件**：每个订阅收到全部对象的事件 | 每次审批都运行两个结果流程（各自忽略不属于自己的） | 不阻塞 | `EventSubscription` 支持按载荷字段过滤，或审批事件名带对象 |
| 9 | **（Q4 缺口 5）没有规则时审批放行** | 入驻同样需要 `QB_REVIEW_NOT_CONFIGURED` | 不阻塞（同 Q4 绕行） | `ApprovalSubject.requireRule()` |
| 10 | **本人修改显示名**：`SecUser.displayName` 只有管理员能改 | 后台的 `UserName` 显示注册时的名字，与 `QbProfile.displayName` 可能不同 | 不阻塞 | 可选：`POST /api/auth/account/display-name`；或平台的 `UserName` 允许应用提供名字来源 |
| 11 | **OIDC 自动开户**（16f，D39） | U-02 的 Google / Apple | 不阻塞邮箱注册 | 已排期，D-Q2-9 |
| 12 | **场景回放走不了验证链接**：邮件关闭时不生成令牌 | 场景中的验证步骤 | 不阻塞（回放操作人视为已验证；链接由 GreenMail 的 IT 覆盖） | 可选：场景步骤 `verifyEmail`（直接写验证字段） |
| 13 | **应用 SPA 的接入**（16c） | 全部页面 | 不阻塞后端 | 已排期 |
| 14 | **`SecUser` 没有注册时间字段** | P-01 按注册时间搜索 | 不阻塞（取 `QbProfile.createdAt`） | 可选：`SecUser.createdAt`（`processOnly`） |

不需要平台新能力的点：入口、自助注册、验证、找回密码、身份合并、`SignInGuard`、`requiresVerifiedEmail`、登录来源（16b）；事务邮件与退订（16a）；审批、待办、受控参数与受控变更（14b、16i）；文件策略（13b）。

## 待确认问题

| 编号 | 问题 | 本计划的缺省 |
|---|---|---|
| Q2-1 | 未激活（未验证）的答题人能否登录 App？ | 能登录、只能浏览；QuizBuks 的一切写入要求已验证。商家入口要求已验证才能登录。另一种做法：App 入口也要求已验证才能登录 |
| Q2-2 | 封禁状态放在哪里？ | 单独的时态实体 `QbUserBan`（不依赖资料是否存在），设计 §3.1 的 `QbProfile.status` 删去 |
| Q2-3 | 封禁的范围与副作用 | 三个入口都不能登录，下一次刷新即失效；发布、答题、转账在流程中再查；不能封禁自己与管理员；不暂停该商家的发布、不发邮件 |
| Q2-4 | 入驻通过后修改商家资料要不要重新审核？ | 不审核，历史与审计保留 |
| Q2-5 | 入驻的其他规则 | 待审核时可再次申请（取代旧请求）；驳回后可无限次再申请；不做撤回申请、撤销已通过的入驻 |
| Q2-6 | 条款同意记什么？ | 只记同意时间；条款文本与版本随 Q7，届时加 `termsVersion` |
| Q2-7 | M-01 的"创建钱包" | Q2 不建；钱包在 Q5 / Q6 首次记账时建立 |
| Q2-8 | U-05 / U-70 的银行账户 | 不做，由 Q6 的 Stripe Connect 收款账户取代（设计 §3.1 已定） |
| Q2-9 | U-03 自动定位怎么做？ | 前端按浏览器语言区域与时区预填国家，城市手填；不用定位权限与外部反向地理编码；登录审计不记大致位置（【缺省 Q9】） |
| Q2-10 | 密码最短长度：需求写"至少 8 位"，平台缺省 10 | 保持平台的 10（满足"至少 8"）；也可把 `jabiz.security.password.min-length` 设为 8 |
| Q2-11 | 欢迎邮件何时发、各邮件的类别 | 答题人第一次保存资料时发 `qb.welcome`（可退订）；入驻通过 / 驳回为不可退订的事务邮件；封禁不发信 |
| Q2-12 | 商家的"姓名"放在哪里？ | `QbProfile.displayName`（与答题人共用一份资料）；`SecUser.displayName` 只在注册时写 |
| Q2-13 | 后台入口接受哪些角色？ | `ADMIN`（首个管理员）与三个 `QB_ADMIN_*`，可用 `QB_ADMIN_ROLES` 覆盖 |
| Q2-14 | 驳回后给商家建"重新提交"待办（`CreateTask`）？ | 建，指派给商家本人，再次申请时关闭 |
| Q2-15 | P-01 的"注册时间"取什么？ | `QbProfile.createdAt`（第一次保存资料）；没补资料的用户不参与该筛选 |
| Q2-16 | 社交登录（16f 之后）开放给哪些入口？ | 只开放 App；商家用邮箱注册 |
| Q2-17 | Q2 之前由管理员手工建的商家账号 | 不保留特例，须走一次入驻申请（目前只有开发与测试环境） |
| Q2-18 | 平台缺口 1（本人改密码）与 2（按入口的会话时长）何时补？ | 不阻塞 Q2；在 App 页面（16c 之后）之前在 `1.2/platform` 上补，2 可并入 16c 或 16g |

## 确认

2026-10-11：计划与待确认问题 Q2-1 – Q2-18 全部按缺省处理。D41（内部流程只允许系统身份运行）已于同日确认，平台实现合入后，`QB_SPONSOR_APPROVAL_RESULT` 与 Q4 的内部流程改用 D41（无人持有的权限可保留）。
