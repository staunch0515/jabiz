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
- 公开路径：`POST /api/auth/login`、`/api/auth/refresh`、`/api/auth/logout`、`/api/auth/challenge/**`（第 9 节），以及 `/api` 以外的静态资源与 `/actuator/health`。
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
  `MfaAdministrationOffIT`；core `TotpTest`（RFC 6238 附录 B 的向量）、`RecoveryCodesTest`、`MfaSecretCipherTest`；runtime `MfaSettingsTest`、`JwtServiceTest`。
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

## 12. OIDC 单点登录（阶段 14g-2，待实施）

见 D28 第 6 条；实施时补充本节。

## 13. 按权限显示明文、数据期限、访问审查（阶段 14g-3，待实施）

见 D28 第 7–9 条；实施时补充本节。
