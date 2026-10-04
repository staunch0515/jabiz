# 10 安全

认证、授权、菜单与敏感数据遮蔽（ROADMAP 阶段 7）。原则只有一条：**默认拒绝**——没有认证就是匿名，没有声明的权限就是没有权限，
没有配置的密钥就不启动。约束性细则见【决策 D12】；二次验证、按操作要求二次验证、闲置锁定、单点登录、明文显示、数据期限与访问审查见【决策 D28】（第 9–13 节）。

## 1. 总体结构

```
请求 ─► RequestContextWebFilter（最先：requestId、语言、匿名上下文）
     ─► Spring Security 过滤链（AuthenticationWebFilter：Bearer 访问令牌 → ActorAuthentication；/api/** 必须已认证 → 否则 401）
     ─► AuthenticatedRequestContextWebFilter（用认证出的操作人替换 RequestContext，保留 requestId 与语言）
     ─► 控制器：按元数据声明的权限码检查（数据视图、SQL 模板、流程、操作）→ 否则 403
```

- Spring Security（WebFlux）只负责"是谁"和"`/api/**` 必须已认证"；"能做什么"由各入口按元数据声明检查（D11 第 2 条的位置不变）。
- 无状态：不建会话、不存安全上下文、不保存请求；不用 Cookie，因此关闭 CSRF。保留 Spring Security 默认的安全响应头。
- 公开路径：`POST /api/auth/login`、`/api/auth/refresh`、`/api/auth/logout`、`/api/auth/challenge/**`（第 9 节）、`/api/auth/oidc/**`（第 12 节），以及 `/api` 以外的静态资源与 `/actuator/health`。
  认证过滤器不处理这三个会话接口：客户端随手带上的过期访问令牌不会妨碍刷新与登录。
- 401、403 与控制器的错误一样是 `ProblemDetail`，带按 `Accept-Language` 本地化的 `violations`（`UNAUTHENTICATED`、`PERMISSION_DENIED`），
  401 带 `WWW-Authenticate: Bearer`。
- 开发用请求头（01 §5）：仅当 `dev` profile 且 `jabiz.dev.actor-headers=true` 时，**没有 `Authorization` 头**的请求可以用
  `X-Jabiz-*` 头指定操作人（作为另一种认证方式进入同一过滤链）；格式错误 → 401。非 `dev` 下开启该属性 → 启动失败（不变）。

## 2. 令牌与会话【D12】

| | 访问令牌 | 刷新令牌 |
|---|---|---|
| 形式 | JWT，HS256 签名（Nimbus），`iss=jabiz`、`sub`、`tenant`、`roles`、`perms`、`iat`、`exp` | 256 位随机串（Base64URL），不透明 |
| 有效期 | 默认 15 分钟（`jabiz.security.jwt.access-token-ttl`） | 默认 8 小时（`jabiz.security.refresh-token-ttl`） |
| 存储 | 不存储 | 库中只存 SHA-256（`sec_refresh_token`） |
| 校验 | 只接受 HS256 与本服务的密钥；过期按注入的 `Clock` 判断 | 未过期、未使用、所属族未吊销 |

- 签名密钥 `jabiz.security.jwt.secret`（环境变量 `JABIZ_JWT_SECRET`，Base64，至少 32 字节）。缺失、非法或过短 → **启动失败**；
  只有 `dev` profile 可以缺省（启动时随机生成并告警，重启后旧令牌全部失效）。`platformCheck` 不接收请求，启动器传入一次性随机密钥。
- **刷新即轮换**：`POST /api/auth/refresh {refreshToken}` 在**一个事务**中消费该令牌（插入 `sec_refresh_token_use`，主键保证只能用一次）、
  重新读取用户（存在、启用、未锁定、至少一个生效角色）与权限、签发同族的下一个刷新令牌；用户检查不通过时什么也不消费。之后签发新的访问令牌。
  同一刷新令牌第二次出现 = 被盗用或重放 → 吊销整个令牌族（`sec_refresh_family_revocation`，原因 `REUSE`），返回 401 `INVALID_REFRESH_TOKEN`。
- `POST /api/auth/logout {refreshToken}` 吊销该令牌所在的族（原因 `LOGOUT`），204；未知令牌同样 204。
- 改密码（`SEC_USER_SET_PASSWORD`）在同一事务中吊销该用户的全部令牌族（原因 `PASSWORD`）：旧密码建立的会话不能再刷新。
- 三张令牌表**只插入**，与操作表一样由 `jabiz_protect_append_only` 触发器保护（D5）：重用检测与吊销依赖这些行不被改动；
  过期数据的清理经受控清除（尚未实现：受控清除流程本身不在阶段 9 范围内，届时由定时任务调用）。
- 已知限制：访问令牌无状态，已签发的访问令牌在到期前（最长为其有效期）仍然有效；禁用用户、收回权限在下一次刷新时生效。
  刷新的响应在传输中丢失后，客户端用同一令牌重试会被视为重用而吊销会话（轮换方案的固有代价），需要重新登录。

## 3. 用户、角色、权限、菜单（平台实体）

全部是**时态实体**（04），由 runtime 提供（`com.jabiz.runtime.security.SecurityEntities`），迁移 `db/jabiz/V6__security.sql`；
每个实体一个默认数据视图，照常经数据视图 API 读写、受权限约束、留有历史与操作记录。

| 实体 | 表 | 要点 | 数据视图权限（读 / 写） |
|---|---|---|---|
| `SecUser` | `sec_user_version` | `userName`（唯一）、`displayName`、`email`（可选，通知用，18 §5.4）、`tenantId`、`enabled`、`passwordHash`（**敏感**） | `security.user.read` / `security.user.write` |
| `SecRole` | `sec_role_version` | `roleCode`（唯一）、`labels`（`jabiz.labels`）、`enabled`；可预定 | `security.role.read` / `security.role.write` |
| `SecRolePermission` | `sec_role_permission_version` | `roleId` → `SecRole`、`permission`；唯一 `(roleId, permission)` | 同 `SecRole` |
| `SecUserRole` | `sec_user_role_version` | `userId`、`roleId`；唯一；**可预定**（角色从生效时间起才算数） | `security.user-role.read` / `.write` |
| `SecMenu` | `sec_menu_version` | `menuCode`（唯一）、`parentCode`、`labels`、`path`、`icon`、`sortOrder`、`permission`（必填）、`enabled` | `security.menu.read` / `.write` |
| `SecLoginRecord` | `sec_login_record_version` | 每次登录尝试一条，见第 4 节 | `security.login-record.read` / `.write` |

- **权限码**就是数据视图、SQL 模板、流程、平台操作声明的字符串；角色通过 `SecRolePermission` 授予。`*` 表示全部权限
  （`RequestContext.hasPermission`；供管理员角色与场景回放使用）。
- 用户的有效权限 = 其**当前生效**的角色分配中、**启用**的角色所授予的权限之并集（`Rbac`，登录与刷新共用）。没有这样的角色则不能登录。
  这些查询读取全部匹配行（上限 `Rbac.MAX_ROWS` = 5000，达到即报错），绝不从截断的列表计算权限；菜单同样。
- 菜单：`GET /api/auth/menus` 返回启用、且当前操作人具备其 `permission` 的菜单项，按 `sortOrder` 组成树，标签按请求语言（→ 英语 → 编码）。
  父项不可见时子项也不可见（默认拒绝）。菜单只影响导航，接口本身仍各自检查权限。
- `GET /api/auth/me` 返回当前操作人的 `userId`、`tenantId`、`roles`、`permissions`。
- 设置密码只能经专用流程（第 6 节），其他字段照常经数据视图修改（例如 `enabled`）。

| 流程 | 权限 | 作用 |
|---|---|---|
| `SPONSOR_SIGN_IN` | `auth.sign-in` | 登录（第 4 节）；只经公开的 `POST /api/auth/login` 执行，权限码使它不能经流程 API 调用 |
| `SEC_USER_CREATE` | `security.user.create` | 建用户并设置密码 |
| `SEC_USER_SET_PASSWORD` | `security.user.password` | 重设密码 |
| `SEC_USER_UNLOCK` | `security.user.unlock` | 解除锁定 |
| `SEC_BOOTSTRAP_ADMIN` | `security.bootstrap` | 只由平台在启动时执行（第 7 节） |

## 4. 登录流程与锁定

`POST /api/auth/login {userName, password}` 以匿名上下文执行 `SPONSOR_SIGN_IN`（06 §10），它是一次普通的操作（`op_process`，操作人 `anonymous`）：

1. **认证与加载用户**：`QueryEntities` 按 `userName` 取用户、按用户取最新一条登录记录；**`BlockingStep`**（`AuthenticationStep`）比对 BCrypt 哈希。
   BCrypt 每次耗时数十到数百毫秒 CPU，放在事件循环上会阻塞其他请求，因此用阻塞步骤（虚拟线程）【D12 调整 06 §10 的"计算步骤"】。
   用户不存在时也对一个假哈希做同样的比对，使响应时间不泄露用户名是否存在；比对后密码立即从上下文中清除。
2. **角色与访问检查**：`QueryEntities` 取当前生效的角色分配、角色、角色权限；计算步骤要求至少一个启用的角色，得出权限集合。
3. **登录记录**：计算步骤用 `ctx.changes().insert(SecLoginRecord, …)` 登记，平台在流程结束时统一提交。

- **被拒绝的登录不是失败的流程**：否则登录记录随事务回滚，失败次数永远不会累积。流程正常结束，输出 `outcome`：
  `SUCCESS`、`BAD_CREDENTIALS`、`LOCKED`、`DISABLED`、`NO_ROLE`（另有管理员解锁写入的 `UNLOCKED`）。
- 登录接口对 `SUCCESS` 以外的一切结果统一返回 401 `LOGIN_FAILED`（不区分原因，防止探测账号；原因只在登录记录与 info 日志里）。
  不存在的用户名不写登录记录（没有可锁定的账号）。
- **锁定**（`LoginAttemptPolicy`，core 纯逻辑）：每条登录记录携带其后生效的 `attemptNo`、`failureCount`、`lockedUntil`，下一次尝试只需读最新一条。
  密码错误 → 计数 +1；达到阈值（默认 5，`jabiz.security.login.max-failures`）→ 锁定到 `当前时间 + 锁定时长`（默认 15 分钟，`…login.lock-duration`）。
  锁定期间一律 `LOCKED`（不论密码对错，也不延长锁定）；锁定到期后重新计数；成功登录或管理员解锁清零。
  密码正确但被禁用 / 无角色：不增加也不清零。
- **并发猜密码不能绕过计数**：登录记录对 `(userId, attemptNo)` 声明唯一约束（时态实体，D6 咨询锁 + 检查）。并发的两次尝试基于同一条最新记录时，
  后者在唯一性上失败，事务回滚，同样返回 401——记录下来的尝试总是一条连续的序列。

## 5. 权限检查的位置（要求 4）

| 入口 | 检查 |
|---|---|
| 数据视图 API 读、查询、历史 | 视图的读权限 |
| 数据视图 API `commit` | 视图的写权限 |
| `/api/entities/{type}` 列表 | 实体默认视图的读权限 |
| `/api/entities/{type}` 增、改、删 | 通用流程的权限（`entity.write`）+ 默认视图的写权限 |
| 通用实体流程 `ADD_ENTITY` / `UPDATE_ENTITY` / `DELETE_ENTITY`（任何入口） | 流程内再查一次默认视图的写权限：这些流程写入输入所指定的实体，流程自身的权限说明不了能改哪些数据 |
| `POST /api/queries/{id}` | 模板声明的全部权限 |
| `POST /api/processes/{name}/{version}` | 流程声明的全部权限 |
| 操作详情 / 撤销 / 追溯更正 | `operation.read` / `temporal.revert` / `temporal.backdate`（D9 第 6 条） |
| 撤销（另加） | 被撤销操作涉及的每个实体的默认视图写权限；撤销不得写回敏感字段的旧值（`UPDATE` 改过敏感字段的操作不能撤销，422 `SENSITIVE_FIELD`） |
| 业务参数（数据视图读 / 写；`PARAM_CREATE` / `PARAM_SET` / `PARAM_SCHEDULE` / `PARAM_CANCEL_SCHEDULED`） | `platform.param.read` / `platform.param.write`；`platform.param.write`（04 §9） |
| 账本（科目数据视图；交易、分录数据视图；`LEDGER_ACCOUNT_OPEN` / `LEDGER_POST` / `LEDGER_REVERSE`；余额模板） | `ledger.account.read` / `.write`；`ledger.read`（写入只经流程）；`ledger.account.write` / `ledger.post` / `ledger.reverse`；`ledger.read`（11 §1） |
| 审计视图 `GET /api/audit/operations` / 任务列表 `GET /api/jobs` | `audit.read` / `job.read`（11 §3、§4） |
| 审批（`APPROVAL_DECIDE`；审批规则、限额、请求、判断、评估的数据视图；影响预览 `POST /api/approvals/preview`） | `approval.decide` + 当前层级的权限；`approval.read`（写入只经流程）；`approval.read`（18 §3） |
| 受控变更 `CONTROL_CHANGE_PROPOSE` / `CONTROL_CHANGE_WITHDRAW` / `CONTROL_CHANGE_PUBLISH` | `control.propose` / `control.propose` / `control.publish`，且发布人不是提出人（18 §3.5） |
| 职责分离规则的数据视图、冲突报告 `GET /api/sod/conflicts` | `sod.read`（18 §4） |
| 我的待办 `GET /api/tasks/mine` / 待办与通知的数据视图 / `TASK_NOTIFY` | 只要求已认证（只列本人与本人所持权限的待办）/ `task.read`（写入只经流程）/ `task.notify`（由系统身份的事件消费者运行）（18 §5） |
| 职责分离（另加） | 授予角色或权限造成冲突即拒绝（422 `SOD_CONFLICT`）；流程 API 入口拒绝同时持有互斥两组权限的操作人使用其中任一组（403 `SOD_CONFLICT`，`*` 除外）（18 §4） |
| 字典、元模型导出、`/api/auth/me`、`/api/auth/menus`、OpenAPI 文档 `/api/meta/openapi` | 只要求已认证 |
| 用户显示名 `GET /api/users/names`（§14） | 只要求已认证：只返回显示名（不返回登录名等其他字段），不判断调用方能否读到该编号；多租户时只含本租户与无租户的用户 |
| 本人的二次验证：`/api/auth/mfa`、`/api/auth/mfa/enroll[/confirm]`、`/api/auth/step-up`（第 9、10 节） | 只要求已认证：只作用于调用者本人（绑定流程声明的 `auth.mfa-enroll` 不授予任何角色，流程内再确认绑定的是调用者本人，因此持有 `*` 也不能经流程 API 替别人绑定） |
| 元数据目录 `/api/meta/datasets` / `/api/meta/processes` | 已认证；只列出具备读权限的视图 / 具备全部权限的非内部流程（D15） |

- 未声明权限的数据视图、模板、流程：非 `dev` 下启动失败（`DatasetRegistry`、`SqlTemplateRegistry`、`ProcessChecks`，`dev` 下为警告）；
  即使启动检查被关闭，请求时也按"未声明 = 拒绝"处理（`dev` 除外）。菜单的 `permission` 是必填字段。
- 流程内部的平台 I/O 步骤不再检查权限（D11 第 2 条），因此登录流程能以匿名身份读取用户表。
- 公共实现：`com.jabiz.runtime.security.Permissions`。

## 6. 敏感数据

- **敏感字段**：`FieldBuilder.sensitive()`（例如 `SecUser.passwordHash`）。
  - 读接口（数据视图读 / 查询 / `commit` 返回 / 历史，`/api/entities` 列表与写入返回）不返回它；历史的 `changedFields` 仍列出字段名（说明"改过"，不说明"改成什么"）。
  - 数据视图 `commit` 与通用实体流程拒绝写入它（400 `SENSITIVE_FIELD`）；只有专用流程经 `ChangeSet` 写入。
  - 列表视图不得显示、筛选、排序它（构建期报错）；`/api/entities/{type}?sort=` 不能按它排序（400 `SORT_NOT_ALLOWED`）；
    SQL 模板不得用占位符读取它、不得 `from` 它（启动检查报错）。
  - 元模型导出 `sensitive: true`，JSON Schema 为 `writeOnly: true`。
- **`@Sensitive`**（core `com.jabiz.security.Sensitive`）：标在流程输入 / 输出 record 的组件上（如登录的 `password`）；这些 record 的 `toString()` 也自行遮蔽。
- **遮蔽器**（`SensitiveDataMasker`）按**属性名**遮蔽 JSON 的任意深度：敏感字段名、`@Sensitive` 组件名、以及包含配置片段的名字
  （`jabiz.security.sensitive-name-fragments`，默认 `password,passwd,secret,token,credential`，不区分大小写）。
  - `op_process.input_summary`：流程输入的 JSON，秘密替换为 `"***"`，超过 16 KB 只记长度；数据视图 `commit` 只记动作、实体、主键与字段**名**，不记值。
  - `op_process_result.output`（幂等重放）与流程 API 的响应：秘密替换为 `null`。
  - 日志：`EntityInstance`、`ChangeSet.Change / Saved` 的 `toString()` 只输出字段名；平台不记录请求体。
- 密码规则：至少 10 个字符（`jabiz.security.password.min-length`），至多 72 字节（BCrypt 只看前 72 字节）：`PASSWORD_TOO_SHORT` / `PASSWORD_TOO_LONG`（400）。
  BCrypt 强度默认 12（`…password.bcrypt-strength`；测试用 4）。

## 7. 首个管理员

设置了 `jabiz.security.bootstrap-admin.user-name` 与 `.password`（环境变量 `JABIZ_BOOTSTRAP_ADMIN_USER` / `…_PASSWORD`，不写入仓库）、
库中还没有任何用户、也没有 `ADMIN` 角色时，启动后以系统身份执行 `SEC_BOOTSTRAP_ADMIN`：建角色 `ADMIN`（权限 `*`）、该用户与角色分配。
否则什么也不做。多个实例同时启动时，后来者在唯一约束上失败，记 info 日志后照常启动。

## 8. 测试

- 集成测试不使用 `dev` profile 时，用 runtime testFixtures 的 `TestTokens.bearer(jwtService, actor, permissions…)` 签发真实的访问令牌；
  测试配置（`config/application.properties`）给出固定的测试密钥与 BCrypt 强度 4。
- 二次验证（14g-1）：`MfaIT`（绑定与登录、码错误计入锁定、码不能重用、恢复码只能用一次、新登录使旧挑战失效、角色要求时先绑定、刷新时角色新要求二次验证、
  管理操作与 `requiresMfa` 在各入口的检查与 step-up、管理员重置、绑定流程只经专用入口、密钥不能挪给别的用户、闲置会话不能刷新、操作记录中没有码、表只插入）、
  `MfaAdministrationOffIT`；单点登录（14g-2）：`OidcIT`（对 testFixtures 的 `TestOidcProvider`：正常登录、未关联、state 只能用一次与过期、
  nonce / aud / azp / iss / exp / iat / sub 不符、HS256 与 `none`、别的密钥签名、密钥轮换只重读一次、PKCE、`amr` 视同二次验证与转入 TOTP、角色要求时先绑定、
  锁定与禁用、关联是管理操作且一个主体只对应一个用户、表只插入且操作记录中没有 state 与授权码），runtime `OidcProvidersTest`；core `TotpTest`（RFC 6238 附录 B 的向量）、`RecoveryCodesTest`、`MfaSecretCipherTest`；runtime `MfaSettingsTest`、`JwtServiceTest`。
- 14g-3：`MaskedFieldIT`（读接口、历史、审计、操作记录、通用实体流程结果为遮蔽形式；显示明文需要权限并留记录；写入、筛选、排序需要权限，遮蔽形式不能写回；
  模板在 SQL 中遮蔽、持有权限者的运行与导出留记录，签发的报表一律遮蔽；数据导出；记录表只插入）、`DataPeriodIT`（审计师只看到期间内的交易、分录与报表；
  审计记录按记录时间；期限外写入被拒；期限来自角色分配、刷新保持、外包与不限期）、`AccessReviewIT`（签发、变更、冲突、签核与存储；权限、二次验证、期间与报表的检查；表只插入）；
  core `MaskedFieldTest`、`DataPeriodTest`、`AccessControlCompilerTest`。
- 验收测试：`AccessControlIT`（401 / 403 覆盖数据视图、模板、流程、实体 API、操作）、`SignInIT`（登录、锁定、并发、角色生效、刷新、菜单、安全表只插入）、
  `SensitiveDataIT`（日志、`input_summary`、`op_process_result`、读接口中不出现密码与哈希）、`BootstrapAdminIT`。

## 9. 二次验证（TOTP）【D28 第 1–3 条，阶段 14g-1】

| 项 | 做法 |
|---|---|
| 算法 | core `com.jabiz.security.Totp`：RFC 6238，HMAC-SHA1、6 位、30 秒一步，容许前后各 1 步；时间来自注入的 `Clock`。密钥 20 字节随机数（Base32 交给认证器应用，`otpauth://totp/<发行者>:<用户名>?secret=…&issuer=…`，发行者 `jabiz.security.mfa.issuer`，默认 `jabiz`） |
| 恢复码 | core `RecoveryCodes`：10 个 `XXXXX-XXXXX`（Base32 字母），只显示一次；库中只存 SHA-256（去掉连字符、大写后），用过即删 |
| 绑定信息 | 时态实体 `SecUserMfa`（`sec_user_mfa_version`，`db/jabiz/V23__mfa.sql`）：`userId`（唯一）、`secret`（加密，**敏感**）、`confirmed`、`confirmedTime`、`confirmedStep`（确认所用的时间步，登录时不再接受）、`recoveryCodes`（哈希列表，**敏感**）。数据视图只读（`security.user.read`），写入只经下列流程 |
| 加密 | AES-256-GCM，附加数据为用户主键（密文不能挪给别的用户）；存放格式 `v1:<密钥标识>:<IV>:<密文>`。密钥 `jabiz.security.mfa.key`（环境变量 `JABIZ_MFA_KEY`，Base64，≥ 32 字节，经 HMAC-SHA256 导出 AES 密钥与密钥标识）；非 dev 缺少即启动失败，dev 缺省时随机生成并告警（重启后已绑定的用户无法验证）；`platformCheck` 用一次性随机密钥 |
| 登录记录 | `SecLoginRecord` 增加 `factor`（`PASSWORD` / `TOTP` / `RECOVERY_CODE`）与 `mfaStep`（已接受的最后一个 TOTP 时间步，每条记录向后传递）；结果增加 `MFA_REQUIRED`、`MFA_ENROLLMENT_REQUIRED`、`MFA_FAILED` |
| 角色 | `SecRole.requireMfa`（缺省否）：持有这类角色的会话必须经过二次验证 |

**流程**

| 流程 | 权限 | 入口 | 作用 |
|---|---|---|---|
| `SPONSOR_SIGN_IN`（改） | `auth.sign-in` | `POST /api/auth/login` | 密码正确且已绑定 → `MFA_REQUIRED`；未绑定而持有要求二次验证的角色 → `MFA_ENROLLMENT_REQUIRED`；两者都不重置也不增加失败计数 |
| `SPONSOR_MFA_VERIFY` | `auth.sign-in`（内部） | `POST /api/auth/challenge/verify`、`POST /api/auth/step-up` | 核对 TOTP 或恢复码：锁定中 → `LOCKED`；码错误 → `MFA_FAILED`（与密码错误同一计数）；正确 → 重新检查启用与角色后 `SUCCESS`；写登录记录，恢复码用过即删 |
| `SEC_MFA_ENROLL_BEGIN` | `auth.mfa-enroll`（内部，不授予角色） | `POST /api/auth/mfa/enroll`、`POST /api/auth/challenge/enroll` | 生成待确认的密钥（已确认的绑定不能覆盖：422 `MFA_ALREADY_ENROLLED`），返回密钥与 `otpauth` 地址 |
| `SEC_MFA_ENROLL_CONFIRM` | `auth.mfa-enroll`（内部） | `POST /api/auth/mfa/enroll/confirm`、`POST /api/auth/challenge/enroll/confirm` | 以一个码确认（错误：422 `MFA_CODE_INVALID`），生成并返回恢复码 |
| `SEC_MFA_RESET` | `security.user.mfa-reset`，要求二次验证（管理级） | 流程 API | 删除某用户的绑定（须填原因），该用户下次登录重新绑定 |

**接口**

| 方法与路径 | 认证 | 说明 |
|---|---|---|
| `POST /api/auth/login` | 公开 | 响应 `status`：`SIGNED_IN`（带令牌，同前）/ `MFA_REQUIRED` / `MFA_ENROLLMENT_REQUIRED`（带 `challenge` 与到期时间，无令牌）；其他一律 401 `LOGIN_FAILED` |
| `POST /api/auth/challenge/verify {challenge, code}` | 公开（挑战令牌） | 成功时签发令牌（`status: SIGNED_IN`）；其他一律 401 `LOGIN_FAILED` |
| `POST /api/auth/challenge/enroll {challenge}`、`…/enroll/confirm {challenge, code}` | 公开（用于绑定的挑战令牌） | 同下；确认后重新登录 |
| `GET /api/auth/mfa` | 已认证 | 本人的状态：`enrolled`、`pending`、剩余恢复码数 |
| `POST /api/auth/mfa/enroll`、`…/enroll/confirm {code}` | 已认证 | 本人绑定 |
| `POST /api/auth/step-up {code}` | 已认证 | 新的访问令牌（`mfa_at` = 现在）；码错误或锁定 422 `MFA_CODE_INVALID`，未绑定 422 `MFA_NOT_ENROLLED` |

- **挑战令牌**：与访问令牌同一密钥，JWT 类型 `jabiz-mfa+jwt`，`purpose` 为 `verify` 或 `enroll`，5 分钟到期（`jabiz.security.mfa.challenge-ttl`），
  携带其登录记录的 `attemptNo`。访问令牌的校验只接受类型 `JWT`，所以挑战令牌不能当访问令牌用，反之亦然。
  同一用户之后若有新的密码登录（或已成功验证），旧挑战即失效；码错误不使挑战失效，但计入锁定。
- **不能重复用码**：TOTP 的时间步必须大于登录记录中传下来的 `mfaStep`；并发的两次验证基于同一条最新登录记录，后者在 `(userId, attemptNo)` 的唯一性上失败（与密码登录相同）。
- 访问令牌增加 `mfa_at`（最近一次二次验证的时刻，未验证则没有）；刷新令牌行记下登录时的 `mfa_at`，刷新时带到新访问令牌中。
  会话未经二次验证、而用户现在持有要求二次验证的角色时，刷新被拒（401 `INVALID_REFRESH_TOKEN`），需要重新登录。
- `GET /api/auth/me` 增加 `mfaAt` 与 `idleTimeoutSeconds`。

## 10. 按操作要求二次验证（step-up）【D28 第 4 条】

- 声明：流程 `pb.requiresMfa(MfaRequirement.ALWAYS)`，数据视图 `d.writeRequiresMfa(MfaRequirement.ALWAYS)`；平台的管理操作声明 `ADMINISTRATION`：
  安全实体（`SecUser` … `SecMenu`、`SecUserMfa`）数据视图的写入、`SEC_USER_CREATE` / `SEC_USER_SET_PASSWORD` / `SEC_USER_UNLOCK` / `SEC_MFA_RESET`、
  `CONTROL_CHANGE_PUBLISH`、`LEGAL_HOLD_PLACE` / `LEGAL_HOLD_RELEASE`。`ADMINISTRATION` 只在 `jabiz.security.mfa.administration=true`（默认）时生效。
- 检查（`MfaPolicy`，与 `Permissions` 同在入口）：流程 API、数据视图 `commit`、实体 API 的增改删、导入（行流程要求时）、撤销（所涉数据视图要求时）；
  通用实体流程与权限一样在流程内再查一次所写数据视图的要求（写入的实体由输入决定，D12 第 2 条）。
  访问令牌的 `mfa_at` 早于"现在 − `jabiz.security.mfa.step-up-max-age`（默认 10 分钟）"或没有 → 403 `MFA_REQUIRED`。
  系统身份、子流程、场景回放、事件消费者与定时任务不检查（与权限相同，D11 第 2 条）。
- 目录：`/api/meta/processes` 与 `/api/meta/datasets` 导出 `requiresMfa` / `writeRequiresMfa`（已按配置折算为布尔值），前端据此提示。
- 开发用请求头（`dev`）认证的操作人视为刚完成二次验证；`TestTokens.bearer(…)` 同样带 `mfa_at`（`TestTokens.withoutMfa(…)` 不带）。

## 11. 闲置锁定【D28 第 5 条】

- 服务端：刷新令牌只在其签发后"访问令牌有效期 + `jabiz.security.session.idle-timeout`（默认 15 分钟）"以内可用（且不超过其本身的有效期），
  否则 401 `INVALID_REFRESH_TOKEN`（不消费、不吊销）。前端平时只在访问令牌失效后才刷新；用户有键盘、指针活动而没有请求时（例如填写长表单），
  外框每三分之一闲置时长至多刷新一次，使服务端也把会话算作活动。
  访问令牌有效期长于闲置时长 → 启动失败（类别 `SECURITY`）。
- 前端：按键盘、指针、滚动与触摸活动计时（同时按上条保持服务端会话）（`idleTimeoutSeconds` 来自 `/api/auth/me`），到时登出（吊销令牌族）并回到登录页，提示因闲置而锁定，用户名已填好。

## 12. OIDC 单点登录【D28 第 6 条，阶段 14g-2】

授权码 + PKCE（S256）+ nonce；平台自己校验 ID 令牌，然后照常签发自己的访问令牌与刷新令牌（第 2 节不变）。不用 Spring 的 oauth2-client（它依赖服务端会话），
只用已有的 Nimbus 与 JDK 的异步 HTTP 客户端。不做 SAML、不自动开户、不做单点登出。

**配置**（`jabiz.security.oidc.providers[i]`）

| 项 | 说明 |
|---|---|
| `id` | 小写字母、数字与连字符，唯一；出现在接口路径与登录记录中 |
| `issuer` | 发现文档 `{issuer}/.well-known/openid-configuration` 的 `issuer` 必须与之完全相同 |
| `client-id`、`client-secret` | 密钥只来自环境变量（如 `JABIZ_OIDC_<ID>_CLIENT_SECRET` 经 Spring 的松散绑定），不写入仓库 |
| `redirect-uri` | 在身份提供方登记的绝对地址，指向前端的 `/login/oidc`（挂在子路径时带上子路径） |
| `scopes` | 缺省 `openid profile email`，必须含 `openid` |
| `labels` | 登录按钮的文字（`{语言: 文本}`），缺省为 `id` |
| `mfa-amr` | ID 令牌的 `amr` 含其中任一值时视同平台的二次验证；缺省为空（不信任，照常走第 9 节） |

启动检查（类别 `SECURITY`）一次报告全部问题：缺项、`id` 格式或重复、`scopes` 无 `openid`、`issuer` / `redirect-uri` 不是绝对的 `https` 地址（只有
`localhost` / `127.0.0.1` 可用 `http`，供开发与测试）。启动时不访问身份提供方。

**接口**（公开，认证过滤器不处理）

| 方法与路径 | 说明 |
|---|---|
| `GET /api/auth/oidc/providers` | 已配置的提供方：`id` 与按请求语言的 `label`，只此而已 |
| `POST /api/auth/oidc/{id}/start` | 生成 state、nonce、PKCE verifier 与浏览器绑定值 binder（各 32 字节随机数），写一行 `sec_oidc_state`（state、nonce、binder 只存 SHA-256；verifier 原样，换令牌时要用；10 分钟有效），返回 `{authorizationUrl, binder}`；前端把 binder 存进 `sessionStorage`（它不出现在任何 URL 中）后整页跳转 |
| `POST /api/auth/oidc/callback {state, code, binder}` | 见下；结果与 `POST /api/auth/login` 相同（`SIGNED_IN` / `MFA_REQUIRED` / `MFA_ENROLLMENT_REQUIRED`），其他一律 401 `LOGIN_FAILED` |

**回调**

1. 消费 state：在 `sec_oidc_state_use` 插入一行（主键保证只能用一次，与刷新令牌相同）；未知、过期、用过、binder 不符 → 401。
   binder 把回调绑定到发起登录的那个浏览器：攻击者停在回调之前、把自己的 state 与授权码发给别人时，别人的页面没有对应的 binder，不会以攻击者身份登录（login CSRF）。
2. 以 `client_secret_basic` 与 `code_verifier` 在令牌端点换取令牌（5 秒超时）。
3. 校验 ID 令牌：签名只接受 RS256 / ES256（拒绝 `none` 与 HS*，防止以公钥作 HMAC 密钥），按 `kid` 取 JWKS 中的公钥；`iss` 等于配置；`aud` 含 `client-id`，
   多个受众时 `azp` 必须是 `client-id`；`exp` 未过、`iat` 不在将来（按注入的 `Clock`，容许 60 秒偏差）；`nonce` 的 SHA-256 与记录的一致；`sub` 非空。
4. 以 `(提供方, sub)` 执行流程 `SPONSOR_OIDC_SIGN_IN`；成功时刷新令牌记下所经的关联（`sec_refresh_token.identity_id`），每次刷新都要求该关联仍在，
   因此解除关联即结束其会话（最迟在下一次刷新）。

发现文档与 JWKS 在首次使用时读取并缓存 1 小时；遇到未知的 `kid` 时重新读取 JWKS（至多每分钟一次）；端点必须是 `https`（同上，`localhost` 除外）。
对身份提供方的调用用 JDK 的异步 HTTP 客户端（不跟随重定向、不带链路头，也不在请求线程上阻塞）。观测 `jabiz.auth.oidc`，标签只有 `provider` 与结果（`outcome`）。

**账号关联与登录流程**

- 时态实体 `SecUserIdentity`（`sec_user_identity_version`，`V24__oidc.sql`）：`userId`、`provider`、`subject`，`(provider, subject)` 唯一；
  管理员经其数据视图维护（读 `security.user.read`，写 `security.user.identity.write`：关联让对方以该用户登录，是独立的凭证，不随修改用户的权限一并授予；
  写入为管理级二次验证）。**不自动开户**：没有关联即拒绝。
- `SPONSOR_OIDC_SIGN_IN`（`auth.sign-in`，内部）与密码登录一样写登录记录（`factor = OIDC`）：没有关联 → 不写记录；锁定中 → `LOCKED`；禁用 → `DISABLED`；
  无角色 → `NO_ROLE`；然后二次验证：`amr` 符合 `mfa-amr` 且 ID 令牌带 `auth_time` → 登录，会话的 `mfa_at` 取 `auth_time`（不是现在：
  提供方很久以前的二次验证不算第 10 节的"最近"；没有 `auth_time` 则不视同）；否则已绑定 TOTP → `MFA_REQUIRED`（第 9 节的第二步）；
  否则角色要求 → `MFA_ENROLLMENT_REQUIRED`。外部登录不猜密码，失败不计入锁定次数，但已有的锁定照样生效。
- 只用单点登录的用户：`SEC_USER_CREATE` 的密码可以不填；没有密码哈希的用户用密码登录总是失败（照样比对假哈希），而且与不存在的用户名一样**不写登录记录**，
  否则任何人都能以错误的密码锁住这类账号（它们没有能对的密码）。
- 谁都可以发起登录，所以 `sec_oidc_state` / `sec_oidc_state_use` **不是**只追加表：每次发起时删除过期的请求，表中至多是最近 10 分钟的请求；它们也不被封存（21 §2）。
- 发现文档、JWKS 与令牌响应至多读 256 KB，超出即停止读取。没有 `kid` 的 ID 令牌逐一尝试同类的全部公钥（密钥轮换期间常有两把）。

## 13. 按权限显示明文、数据期限、访问审查【D28 第 7–9 条，阶段 14g-3】

### 13.1 遮蔽字段

```java
eb.field("bankAccount", f -> f.physicalColumn("bank_account").asText(34)
    .masked("logistics.carrier.bank-account", MaskStyle.LAST4));
```

- **声明**：`f.masked(权限, 样式)`，只用于文本字段；不能同时是 `sensitive()` 或 `generated`，不能是主键、显示字段（`eb.display`，引用处显示）或列表视图的默认排序（任何人都按它排序）。
  元数据导出 `masked: {permission, style}`；遮蔽字段不得出现在 `publicRead` 的白名单中（启动检查，D17）。
- **样式**（`MaskStyle`，Java 与 SQL 形式相同）：`LAST4` = `****` + 末 4 个字符（值短于 8 个字符时整体为 `****`，否则末 4 位就暴露了大半）；`ALL` = `****`；`TAX_ID`（14k）= 美国纳税人号码的习惯写法，EIN `12-3456789` → `**-***6789`，SSN / ITIN（9 位，可带连字符）→ `***-**-6789`，其他形式整体为 `****`（不是这两种形式的值不按位暴露）。空值仍为空。三种样式的 Java 与 SQL 形式由 `MaskedFieldIT` 逐值比对。
- **一律遮蔽**：读接口（数据视图、实体 API、通用实体流程的结果）、历史、审计记录（21 §1，`AuditDiff` 存遮蔽形式）、导入报告（行值与常量按字段名遮蔽，报告会保存并给别人看）
  中都是遮蔽形式——**持有权限者也一样**，这样列表读取不需要留记录（放弃的方案⑥）。操作记录的 `input_summary` 中与遮蔽字段同名的属性记为 `***`。
- **显示明文**：`POST /api/datasets/{id}/reveal {id, field}` → `{value}`。需要数据视图的读权限与字段的权限；按数据视图范围读取当前状态（范围外 404）；
  返回之前在只追加表 `sys_reveal_record` 写一条记录（`kind = VALUE`：时间、操作人、请求号、数据视图、实体、主键、字段），写不成则不返回。
  前端只对持有权限者在列表中显示"显示"按钮，明文只留在该单元格的状态中。
- **SQL 模板**：`{{Entity}}` 展开时，调用者没有权限的遮蔽列在子查询中就投影为遮蔽形式（`QueryCompiler.templateExpression` 列出全部列，遮蔽列为 `CASE … END AS 列`），
  所以模板中的条件、排序、连接与外层筛选都只能看到遮蔽形式，不能借此猜值。持有权限者得到明文，模板正文以占位符 `{{Entity.field}}` 引用了明文遮蔽字段时，
  每次运行（查询、报表导出）按实体写一条记录（`kind = QUERY`，模板 id、字段、行数）。流程中的 `RunTemplate` 按流程的发起人判断（系统身份没有权限，一律遮蔽）。
- **签发的报表**（`REPORT_ISSUE`，19 §5）**一律遮蔽**，无论签发人是否持有权限：存档会被别人阅读、重现与校验，校验必须得到相同的行。
- **数据导出**（`POST /api/exports/data`，21 §4）：无权限者导出遮蔽形式；持有权限者导出明文，每个数据视图写一条记录（`kind = EXPORT`，行数）。
- **写入与筛选**：数据视图 `commit` 与通用实体流程中写遮蔽字段需要其权限（403）；值以 `****` 开头即拒绝（400 `MASKED_VALUE`），遮蔽形式不能原样写回
  （前端只提交改过的字段，没有权限时该字段只读）。业务流程照常经 `ctx.changes()` 写入（权限只在入口检查，D11 第 2 条）。
  没有权限者不能按遮蔽字段筛选、排序（数据视图 400 `FILTER_NOT_ALLOWED` / `SORT_NOT_ALLOWED`，实体 API 同样），列表视图可以把它列入白名单供持有权限者使用。
- **查阅**：`GET /api/audit/reveals`（`audit.read`，按操作人、实体、主键、时间筛选，最新在前）；后台审计页的"明文显示"页签。`sys_reveal_record` 由 21 §2 封存。
- 业务流程自己的输入输出中出现遮蔽字段的值时，由该流程负责（输出不经遮蔽；需要隐藏的组件用 `@Sensitive`）。

### 13.2 数据期限

- **分配**：`SecUserRole` 增加 `dataFrom` / `dataTo`（时态字段，`[dataFrom, dataTo)`，任一端可空；两端都空 = 不限；`dataTo` 必须晚于 `dataFrom`，422 `DATA_PERIOD_ORDER`）。
- **计算**（登录、刷新与 `Rbac.access` 相同，`DataPeriod.hull`）：只算启用角色的分配；**有一个分配不限期即不限期**；否则取所有期限的外包（中间的空档也包括在内）。
  权限不按分配各自的期限分开计算（已知限制）：只应看某一期间的用户（审计师）应只持有带该期限的角色。
- **传递**：访问令牌的 `data_from` / `data_to`（ISO-8601），`RequestContext.dataPeriod`，`/api/auth/me` 的 `dataFrom` / `dataTo`（后台在页头显示）；
  开发请求头 `X-Jabiz-Data-From` / `X-Jabiz-Data-To`；场景的 `actor.dataFrom` / `dataTo`；测试 `TestTokens.withinPeriod(…)`。
- **声明**（数据视图范围，03 §2.2）：`scope(s -> s.withinDataPeriod("bookingTime"))`（目标实体的时态字段），或
  `withinDataPeriod("transactionId", "bookingTime")`（引用字段 + 被引用实体的**不可变**时态字段，例如账本分录按其交易的过账时间；启动检查）。
  **没有期限 = 不受限制**，这与 `fromContext` 取不到值即拒绝不同，所以必须明确声明才生效。
- **读取**：范围条件为 `列 >= :from AND 列 < :to`（经引用时为 `引用列 IN (SELECT 主键 FROM 被引用表 WHERE …)`，不可变字段任何版本都一样），
  在取得当前版本之后应用（D3）；数据视图读取期限外的实体 = 不存在（404），SQL 模板经 D10 同样生效（例如试算表只含期间内的交易）。
  经引用声明的数据视图，受期限限制的调用者看不到历史、不能更新（无法仅凭数据判断，默认拒绝）。
- **写入**：插入与更新所设的时间必须在期限内，插入必须给出时间（否则 422 `OUT_OF_SCOPE`）；经引用的期限在时间所在的实体上检查（分录随其交易）。
- **流程中的检查**同样只看得到期限内的数据，所以需要看全部数据的规则要由数据库保证：账本"一笔交易只冲正一次"另有唯一索引
  （V25，`reverses_transaction_id`），期限外已被冲正的交易再冲正时 400 `UNIQUE_VIOLATION`。
- **平台的使用**：账本交易（`bookingTime`）与分录（经 `transactionId`）数据视图；审计记录与操作记录的查询（`/api/audit/records`、`/operations`、`/reveals`）按记录时间截取在期限内，
  单条审计记录在期限外为 404。
- **签发的报表**：数据期限作为范围值记入运行（19 §5.3），只有期限相同的读者能读；声明期限之前签发的运行没有这项记录，受期限限制的读者不能读。

### 13.3 访问审查

- **访问权限报表**：平台模板 `jabiz.security.access_review`（报表，`timeSlice: {asOf: asOf}`，权限 `security.access-review.read`）：
  每个用户 × 分配的角色（及其数据期限）× 角色的权限，以及到该时点为止最近一次成功登录。按期末时点以 `REPORT_ISSUE` 签发（D25，不另存报表），存档中可重现与校验。
- **期间内的变更**：审计记录（D27）中安全实体（`SecUser`、`SecRole`、`SecRolePermission`、`SecUserRole`、`SecUserIdentity`、`SecUserMfa`）在 `[from, to)` 记录的条目，
  按记录号排序；哈希为其 JSON 的 SHA-256。`GET /api/security/access-reviews/changes?from&to`。
- **职责分离冲突**：18 §4.4 的冲突报告（`SodService.conflicts`，按用户名与规则排序），`GET /api/security/access-reviews/conflicts`。
- **签核**：流程 `ACCESS_REVIEW_SIGN_OFF`（权限 `security.access-review.sign`，总要求二次验证）输入期间、报表的 run id 与意见（必填，≤ 2000 字）。
  期间必须已结束（`periodTo` 不晚于操作时间，422 `ACCESS_REVIEW_PERIOD`）；报表必须是访问权限模板、读取时点等于 `periodTo`、记录时点不早于 `periodTo`（期末之后签发：期末之前"按期末"签发的报表缺少其间的变更）、
  未被取代且内容未被改动（422 `ACCESS_REVIEW_REPORT`）。
  只追加表 `sys_access_review` 保存：期间、报表 run id 与内容哈希、变更条数与哈希、签核时的冲突（JSON）与哈希、审查人、意见、时间与操作号。
  列表 `GET /api/security/access-reviews`（`security.access-review.read`）。后台页面 `/access-review`：选择期间、签发报表、查看变更与冲突、签核、已签核列表。
- 变更与冲突不另存明细：审计记录只追加且被封存，同一期间随时可以重算并比对哈希；冲突是签核时的状态，所以连同 JSON 一起保存。

## 14. 显示名【阶段 14r】

数据只存操作人的用户编号（准备人、审批人、操作记录、审计、历史）；手写的页面与应用扩展显示其显示名，不显示编号。

- `GET /api/auth/me` 返回本人的 `displayName`（没有显示名时为本人的用户名；开发用请求头的操作人不是用户，为空）；页头显示它。
- `GET /api/users/names?ids=…&ids=…` → `{names: {编号: 显示名}}`，只要求已认证（与 `/api/auth/me`、`/api/tasks/mine` 同类）：任何登录用户都可以查任何编号
  （编号是 UUIDv7，不能枚举），得到的只有显示名。**不返回别人的登录名**（它是登录凭据的一半，知道它就能故意输错密码锁定对方，§5），没有显示名的用户因此显示编号；
  不是用户的编号（系统操作人、无效编号）没有条目；调用方有租户时只含本租户与无租户的用户；一次至多 200 个（多出的忽略）。
- 前端以 `UserName` / `useUserName`（`@jabiz/admin`）显示：同一时刻要显示的编号合并为一次请求（超过 200 个分批），取不到名字时显示编号、下次显示时再取。
  导出、PDF、签发的报表仍是编号（存档内容不随人改名而变）；元数据生成的数据页面中的用户编号字段也仍显示编号。
