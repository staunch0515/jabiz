# 10 安全

认证、授权、菜单与敏感数据遮蔽（ROADMAP 阶段 7）。原则只有一条：**默认拒绝**——没有认证就是匿名，没有声明的权限就是没有权限，
没有配置的密钥就不启动。约束性细则见【决策 D12】；二次验证、按操作要求二次验证、闲置锁定、单点登录、明文显示、数据期限与访问审查见【决策 D28】（第 9–13 节）；
登录入口、登录前的应用检查、邮箱验证与登录来源见【决策 D36】（第 15 节）；受保护授权的四眼见【决策 D48】（第 16 节）。

## 1. 总体结构

```
请求 ─► RequestContextWebFilter（最先：requestId、语言、匿名上下文）
     ─► Spring Security 过滤链（AuthenticationWebFilter：Bearer 访问令牌 → ActorAuthentication；/api/** 必须已认证 → 否则 401）
     ─► AuthenticatedRequestContextWebFilter（用认证出的操作人替换 RequestContext，保留 requestId 与语言）
     ─► 控制器：按元数据声明的权限码检查（数据视图、SQL 模板、流程、操作）→ 否则 403
```

- Spring Security（WebFlux）只负责"是谁"和"`/api/**` 必须已认证"；"能做什么"由各入口按元数据声明检查（D11 第 2 条的位置不变）。
- 无状态：不建会话、不存安全上下文、不保存请求；不用 Cookie，因此关闭 CSRF。保留 Spring Security 默认的安全响应头。
- 公开路径：`POST /api/auth/login`、`/api/auth/refresh`、`/api/auth/logout`、`/api/auth/challenge/**`（第 9 节）、`/api/auth/oidc/**`（第 12 节）、
  `POST /api/auth/mail/unsubscribe`（通知邮件的退订链接，签名令牌即凭证，18 §5.6），
  `POST /api/inbound/{提供方}`（入站 Webhook，提供方的签名即凭证，验签前已由 `InboundWebhookWebFilter` 限定提供方、方法与按地址的频率，11 §2.5、决策 D37），
  以及 `/api` 以外的静态资源与 `/actuator/health`。
  认证过滤器不处理这三个会话接口：客户端随手带上的过期访问令牌不会妨碍刷新与登录。
- 401、403 与控制器的错误一样是 `ProblemDetail`，带按 `Accept-Language` 本地化的 `violations`（`UNAUTHENTICATED`、`PERMISSION_DENIED`），
  401 带 `WWW-Authenticate: Bearer`。
- 开发用请求头（01 §5）：仅当 `dev` profile 且 `jabiz.dev.actor-headers=true` 时，**没有 `Authorization` 头**的请求可以用
  `X-Jabiz-*` 头指定操作人（作为另一种认证方式进入同一过滤链）；格式错误 → 401。非 `dev` 下开启该属性 → 启动失败（不变）。

## 2. 令牌与会话【D12】

| | 访问令牌 | 刷新令牌 |
|---|---|---|
| 形式 | JWT，HS256 签名（Nimbus），`iss=jabiz`、`sub`、`tenant`、`roles`、`perms`、`iat`、`exp`；另有 `mfa_at`（§9）、`data_from` / `data_to`（§13.2）、`entry` 与 `email_verified`（§15）、`sid`（会话编号，即刷新令牌族，§15.9） | 256 位随机串（Base64URL），不透明；行记下令牌族的 `mfa_at`、`identity_id` 与 `entry`，以及签发这一个令牌时的 `client_ip` 与 `user_agent`（§15.9） |
| 有效期 | 默认 15 分钟（`jabiz.security.jwt.access-token-ttl`） | 默认 8 小时（`jabiz.security.refresh-token-ttl`）；入口可另设（§15.1，上限 90 天）。每次刷新从当时起算（滑动） |
| 存储 | 不存储 | 库中只存 SHA-256（`sec_refresh_token`） |
| 校验 | 只接受 HS256 与本服务的密钥；过期按注入的 `Clock` 判断 | 未过期、未使用、所属族未吊销 |

- 签名密钥 `jabiz.security.jwt.secret`（环境变量 `JABIZ_JWT_SECRET`，Base64，至少 32 字节）。缺失、非法或过短 → **启动失败**；
  只有 `dev` profile 可以缺省（启动时随机生成并告警，重启后旧令牌全部失效）。`platformCheck` 不接收请求，启动器传入一次性随机密钥。
- **刷新即轮换**：`POST /api/auth/refresh {refreshToken}` 在**一个事务**中消费该令牌（插入 `sec_refresh_token_use`，主键保证只能用一次）、
  重新读取用户（存在、启用、未锁定、至少一个生效角色）与权限、签发同族的下一个刷新令牌；用户检查不通过时什么也不消费。之后签发新的访问令牌。
  同一刷新令牌第二次出现 = 被盗用或重放 → 吊销整个令牌族（`sec_refresh_family_revocation`，原因 `REUSE`），返回 401 `INVALID_REFRESH_TOKEN`。
- `POST /api/auth/logout {refreshToken}` 吊销该令牌所在的族（原因 `LOGOUT`），204；未知令牌同样 204。
- 改密码（`SEC_USER_SET_PASSWORD`）在同一事务中吊销该用户的全部令牌族（原因 `PASSWORD`）：旧密码建立的会话不能再刷新。
  本人改密码（§15.8）吊销本人的其他令牌族（原因 `PASSWORD_CHANGE`），请求中给出的、本人当前入口的有效刷新令牌所在的族保留。
  "全部令牌族"指**尚未结束**的族：未吊销、族内有未过期的令牌（经索引 `(user_id, expires_at)` 查找，留 5 分钟的时钟偏差余量，`CLOCK_SKEW`），闲置的也算——
  入口的闲置时长以后放宽时它们不能复活；早已过期的族本来就不能刷新，不再补写吊销行（16g）。
- 会话的列出与吊销（§15.9）：本人吊销记原因 `SELF_REVOKE`，管理员吊销记 `ADMIN_REVOKE`。由流程吊销时（这两种与改密码、找回密码、加入、接管）
  吊销行带 `process_seq_id`，指向那次操作；登出、重用、守卫拒绝不经流程，为空。同一族并发吊销时第一条为准（流程中的插入在保存点内进行，后来者不影响所在事务）。
- 有效期与闲置窗口按令牌族的入口（`sec_refresh_token.entry`）取（§15.1，决策 D43 第 2 条）：签发时以入口的有效期写入 `expires_at`，
  使用时以入口的闲置时长判断（§11）。改了入口的设置后，已签发的令牌保持签发时的到期时刻，下一次刷新起按新设置。
- 三张令牌表**只插入**，与操作表一样由 `jabiz_protect_append_only` 触发器保护（D5）：重用检测与吊销依赖这些行不被改动；
  过期数据的清理经受控清除（尚未实现：受控清除流程本身不在阶段 9 范围内，届时由定时任务调用）。
- 已知限制：访问令牌无状态，已签发的访问令牌在到期前（最长为其有效期）仍然有效；禁用用户、收回权限、吊销会话（§15.9）在下一次刷新时生效。
  刷新的响应在传输中丢失后，客户端用同一令牌重试会被视为重用而吊销会话（轮换方案的固有代价），需要重新登录。

## 3. 用户、角色、权限、菜单（平台实体）

全部是**时态实体**（04），由 runtime 提供（`com.jabiz.runtime.security.SecurityEntities`），迁移 `db/jabiz/V6__security.sql`；
每个实体一个默认数据视图，照常经数据视图 API 读写、受权限约束、留有历史与操作记录。

| 实体 | 表 | 要点 | 数据视图权限（读 / 写） |
|---|---|---|---|
| `SecUser` | `sec_user_version` | `userName`（唯一）、`displayName`、`email`（可选，通知用，18 §5.4；非空时不区分大小写唯一，§15）、`locale`（可选，邮件语言，18 §5.6）、`tenantId`、`enabled`、`passwordHash`（**敏感**）、`verifiedEmail` / `emailVerifiedAt`（只由流程写，§15） | `security.user.read` / `security.user.write` |
| `SecUserMailPreference` | `sec_user_mail_preference_version` | `userId`、`template`、`subscribed`；唯一（用户, 模板）；只经 `SEC_MAIL_PREFERENCE_SET`（用户本人）写入，18 §5.6 | 同 `SecUser`（写入只经流程） |
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
| `SEC_USER_SET_PASSWORD` | `security.user.password` | 重设密码；持有受保护权限的用户须另一位管理员审批（§16） |
| `SEC_USER_ROLE_ASSIGN` / `SEC_ROLE_PERMISSION_ADD` / `SEC_USER_IDENTITY_LINK` | `security.user-role.write` / `security.role.write` / `security.user.identity.write` | 分配角色、角色增加权限、关联外部账号；受保护的须另一位管理员审批，其余当即写入（§16） |
| `SEC_USER_UNLOCK` | `security.user.unlock` | 解除锁定 |
| `SEC_BOOTSTRAP_ADMIN` | `security.bootstrap` | 只由平台在启动时执行（第 7 节） |
| `SEC_USER_SET_LOCALE` | `auth.account`（不授予角色） | 本人设置邮件语言；只经 `POST /api/auth/account/locale` 执行（18 §5.6） |
| `SEC_MAIL_PREFERENCE_SET` | `auth.mail-preference`（不授予角色） | 本人开关通知邮件；只经退订链接与 `/api/auth/mail/preferences` 执行（18 §5.6） |
| `SEC_ACCOUNT_SESSION_REVOKE` / `SEC_ACCOUNT_SESSIONS_REVOKE_OTHERS` | `auth.account`（不授予角色） | 本人结束自己当前入口的一个会话 / 其他全部会话；只经 `/api/auth/account/sessions/**` 执行（§15.9） |
| `SEC_USER_SESSIONS_REVOKE` | `security.user.session.revoke`，近期二次验证 | 管理员结束某用户的一个或全部会话（全部入口），须填写原因；`SecUser` 上的行操作（§15.9） |

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
  不存在的用户名不写登录记录（没有可锁定的账号）。例外只有密码正确之后的两种（§15）：守卫拒绝 403 `SIGN_IN_REFUSED`（结果 `REFUSED`）、
  入口要求已验证的邮箱 403 `EMAIL_NOT_VERIFIED`（结果 `EMAIL_NOT_VERIFIED`）；两者都写记录、不计失败。另有找回密码写入的 `PASSWORD_RESET`（清零并解锁，16b-2）。
- **锁定**（`LoginAttemptPolicy`，core 纯逻辑）：每条登录记录携带其后生效的 `attemptNo`、`failureCount`、`lockedUntil`，下一次尝试只需读最新一条。
  密码错误 → 计数 +1；达到阈值（默认 5，`jabiz.security.login.max-failures`）→ 锁定到 `当前时间 + 锁定时长`（默认 15 分钟，`…login.lock-duration`）。
  锁定期间一律 `LOCKED`（不论密码对错，也不延长锁定）；锁定到期后重新计数；成功登录或管理员解锁清零。
  密码正确但被禁用 / 无角色：不增加也不清零。
- **并发猜密码不能绕过计数**：登录记录对 `(userId, attemptNo)` 声明唯一约束（时态实体，D6 咨询锁 + 检查）。并发的两次尝试基于同一条最新记录时，
  后者在唯一性上失败，事务回滚，同样返回 401——记录下来的尝试总是一条连续的序列。

### 4.1 登录类接口的限流【决策 D47 第 1 条，阶段 16q，评审 M2、M3 后修订】

按账号的锁定挡不住一个地址对许多账号各试几次。登录类接口因此另有限流，缺省开启，在读取任何数据之前：

| 种类 | 接口 | 计数的桶 |
|---|---|---|
| 口令登录 | `POST /api/auth/login` | 地址 + 提交的用户名、地址、IPv6 站点、实例 |
| 二次验证 | `POST /api/auth/challenge/verify`（登录第二步）、`POST /api/auth/step-up` | 地址 + 挑战的用户（或已登录的用户）、地址、IPv6 站点、实例 |
| OIDC 回调 | `POST /api/auth/oidc/callback` | 地址、IPv6 站点、实例 |
| 刷新 | `POST /api/auth/refresh` | 地址、IPv6 站点、实例 |

| 桶 | 键 | 缺省（每 `period`，缺省 1 分钟） | 配置 `jabiz.security.sign-in.rate-limit.*` |
|---|---|---|---|
| 地址 + 名字 | 客户端地址与名字 | 补充 20，突发 10 | `per-user-address`、`user-burst` |
| 地址 | 客户端地址（IPv6 为其 /64） | 补充 300，突发 100 | `per-address`、`burst` |
| IPv6 站点 | IPv6 地址的 /48 | 补充 1 000，突发 300 | `per-network`、`network-burst` |
| 实例 | 每个种类一个 | 每秒 200（突发同） | `global-per-second` |

- **名字**：登录用提交的用户名（去掉首尾空白、转为小写，至多 256 个字符；邮箱查找不区分大小写，大小写不同的写法因此算同一个名字——只会更严，不会更松），
  不查数据库；二次验证用挑战令牌中的用户（只验签名，不读数据），挑战无效时只按地址计；step-up 用已登录的用户。没有名字（空白）时只按地址计。
  这样一个地址猜一个账号很快停下，而一个地址后面的许多人（同一 NAT 的办公室）仍各自能登录。
- 刷新与 OIDC 回调在验证之前不知道是谁，只按地址（与站点、实例）计，所以地址的缺省比较宽（突发 100）。
- 地址来自 `ClientAddresses`（转发头只在来自可信代理时采用，§15.5）；IPv6 按 /64（一个用户通常有整个 /64），另按 /48 计一个更宽的站点桶，轮换一个站点内的 /64 不能成倍增加预算。
  带作用域的链路本地地址（`fe80::1%eth0`）去掉作用域后同样按 /64。没有地址的请求（只在测试与配错的代理之后）共用键 `unknown`。
- 一次请求从计它的每个桶各取一个令牌；任何一个拒绝时都不取（429 `RATE_LIMITED` + `Retry-After`，取拒绝的桶中最长的）。不写登录记录、不计入账号的失败次数，对存在与不存在的账号、对的与错的口令一样。
- 每个桶至多 100 000 个键（最近最少使用的先忘掉）。**表满时新出现的键（包括被挤掉后再来的）只有 1 个令牌，没有突发**：用大量键把自己的键挤掉换不来新的突发。
- `.enabled=false` 关闭（例如前面的网关已经限流）。数值不为正是启动错误（`SECURITY`）。进程内、按实例计数（同 §15.6 的已知限制）；拒绝记观测 `jabiz.auth.rate_limited`（标签只有 `kind`，13）。
- 与按账号的锁定并存，锁定的规则不变（`SignInRateLimitIT` 确认）。

### 4.2 口令哈希的并发上限【决策 D47 第 2 条，阶段 16q】

BCrypt 有意很慢（每次数十到数百毫秒 CPU）。登录洪峰时若不设上限，所有核都在算哈希，其他请求一起变慢；等待中的登录又各占着一个连接。

- 同时计算的哈希至多 `jabiz.security.password-hashing.max-concurrent`（缺省为处理器数）个，其余按到达顺序等待，至多 `.max-wait`（缺省 2 秒），之后 503 `AUTH_BUSY` + `Retry-After`（三种语言的消息）。
- **在事务之前排队**：含口令步骤（`AuthenticationStep`、`HashPasswordStep`、`CurrentPasswordStep`，以 `PasswordHashingLimit.HashingStep` 标记）的根流程——登录、注册、改密码、重设密码、建用户——
  以及经 `CallProcess` 调用这类流程的根流程（按名字在注册表中静态解析，递归），在借连接、开事务之前取许可（与 06 §4.2 的准入同一种进程内队列），等待中不占连接、不占线程。
  因为调用关系是静态解析并直接排队的，不需要另加"只经子流程才哈希"的启动告警。
- **取得许可后与调用方分离**（评审 M1）：调用方断开不会中止这次运行，许可在事务真正结束（提交、回滚、失败）时才归还（同 06 §4.2 的准入）；否则断开的请求一放回许可，
  新的运行就会进来、借了连接，在计算的信号量上等待，而那次 BCrypt 还在算。
- 计算本身照旧在 `BlockingStep` 的虚拟线程上，另经同样大小的信号量：约束按运行时才知道的名字调用的子流程、直接调用 `PasswordHasher` 的代码；经过事务前队列的运行不会在这里等待。
  在这里超时的请求已在事务中：照样 503 `AUTH_BUSY`，事务回滚，不写登录记录、不计失败（`PasswordHashingLimitIT`）。
- 被拒绝的请求没有写任何记录：登录不计入失败次数，可以稍后重试。拒绝记观测 `jabiz.auth.hashing.busy`（13）。

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
| 审批（`APPROVAL_DECIDE`；审批规则、限额、请求、判断、评估的数据视图；影响预览 `POST /api/approvals/preview`） | `approval.decide` + 当前层级的权限（受保护的变更改为 `security.protected-change.decide`，§16.1，D51）；`approval.read`（写入只经流程）；`approval.read`（18 §3） |
| 受控变更 `CONTROL_CHANGE_PROPOSE` / `CONTROL_CHANGE_WITHDRAW` / `CONTROL_CHANGE_PUBLISH` | `control.propose` / `control.propose` / `control.publish`，且发布人不是提出人（18 §3.5） |
| 职责分离规则的数据视图、冲突报告 `GET /api/sod/conflicts` | `sod.read`（18 §4） |
| 我的待办 `GET /api/tasks/mine` / 待办与通知的数据视图 / `TASK_NOTIFY` | 只要求已认证（只列本人与本人所持权限的待办）/ `task.read`（写入只经流程）/ `task.notify`（由系统身份的事件消费者运行）（18 §5） |
| 受保护的授权（另加，§16） | 造成获得受保护权限的分配与授权、对受保护持有人的重设密码 / 二次验证 / 关联外部账号须另一位管理员审批（422 `PROTECTED_CHANGE_REQUIRES_APPROVAL`；首个管理员退役后 403 `BOOTSTRAP_ADMIN_RETIRED`） |
| 职责分离（另加） | 授予角色或权限造成冲突即拒绝（422 `SOD_CONFLICT`）；流程 API 入口拒绝同时持有互斥两组权限的操作人使用其中任一组（403 `SOD_CONFLICT`，`*` 除外）（18 §4） |
| 字典、元模型导出、`/api/auth/me`、`/api/auth/menus`、OpenAPI 文档 `/api/meta/openapi` | 只要求已认证 |
| 用户显示名 `GET /api/users/names`（§14） | 只要求已认证：只返回显示名（不返回登录名等其他字段），不判断调用方能否读到该编号；多租户时只含本租户与无租户的用户 |
| 本人的二次验证：`/api/auth/mfa`、`/api/auth/mfa/enroll[/confirm]`、`/api/auth/step-up`（第 9、10 节） | 只要求已认证：只作用于调用者本人（绑定流程声明的 `auth.mfa-enroll` 不授予任何角色，流程内再确认绑定的是调用者本人，因此持有 `*` 也不能经流程 API 替别人绑定） |
| 元数据目录 `/api/meta/datasets` / `/api/meta/processes` | 已认证；只列出具备读权限的视图 / 具备全部权限的非内部流程（D15） |
| 本人的会话 `GET /api/auth/account/sessions`、`POST …/{id}/revoke`、`POST …/revoke-others`（§15.9） | 只要求已认证的平台用户：只看、只吊销本人在**当前会话入口**的会话（流程声明的 `auth.account` 不授予任何角色） |
| 某用户的会话 `GET /api/security/sessions?userId=`（§15.9） | `security.user.session.read`（含地址与 UA，单独授予；管理员经 `*` 获得） |

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
  遮蔽按组件**名**在整个 JSON 中进行，名字要有辨识度（`code`、`lines` 这类常用词会把别的流程的输出一并遮掉）。
  **只给调用者、不留记录**的秘密（为本人生成的一次性秘密、获奖者打开的奖品码）标 `@Sensitive(returned = true)`：
  `input_summary`、`op_process_result`、事件载荷中仍遮蔽，流程 API 的响应中原样返回；幂等重放得到 `null`。同名组件在别处标为普通 `@Sensitive` 时从严。
- **遮蔽器**（`SensitiveDataMasker`）按**属性名**遮蔽 JSON 的任意深度：敏感字段名、`@Sensitive` 组件名、以及包含配置片段的名字
  （`jabiz.security.sensitive-name-fragments`，默认 `password,passwd,secret,token,credential`，不区分大小写）。
  - `op_process.input_summary`：流程输入的 JSON，秘密替换为 `"***"`，超过 16 KB 只记长度；数据视图 `commit` 只记动作、实体、主键与字段**名**，不记值。
  - `op_process_result.output`（幂等重放）与流程 API 的响应：秘密替换为 `null`。
  - 日志：`EntityInstance`、`ChangeSet.Change / Saved` 的 `toString()` 只输出字段名；平台不记录请求体。
- **应用保存的秘密**（16zg，决策 D56）：应用要保存第三方凭据（商家邮件服务器的密码）时，只以核心 `SealedSecrets` 密封保存（AES-256-GCM，附加数据为所属行的键，密钥 `jabiz.security.seal.key` /
  `JABIZ_SEAL_KEY`，与 `JABIZ_MFA_KEY` 同样的要求、各自导出），流程中用步骤 `SealSecret`，打开只在平台用到它的地方（邮件投递）；字段照常 `sensitive()`（应用自己声明，平台不检查）。不存秘密的应用不需要密钥；
  声明了 `MailSenderSource` 而没有密钥是启动错误，其他用 `SealSecret` 的流程没有密钥时在运行时失败。
- 密码规则：至少 10 个字符（`jabiz.security.password.min-length`），至多 72 字节（BCrypt 只看前 72 字节）：`PASSWORD_TOO_SHORT` / `PASSWORD_TOO_LONG`（400）。
  BCrypt 强度默认 12（`…password.bcrypt-strength`；测试用 4）。

## 7. 首个管理员

设置了 `jabiz.security.bootstrap-admin.user-name` 与 `.password`（环境变量 `JABIZ_BOOTSTRAP_ADMIN_USER` / `…_PASSWORD`，不写入仓库）、
库中还没有任何用户、也没有 `ADMIN` 角色时，启动后以系统身份执行 `SEC_BOOTSTRAP_ADMIN`：建角色 `ADMIN`（权限 `*`）、该用户与角色分配。
否则什么也不做。多个实例同时启动时，后来者在唯一约束上失败，记 info 日志后照常启动。
有了另一位管理员之后，这个账号不能再提出、写入或审批受保护的变更（§16.3）。

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
- 登录入口（16b-1，§15）：`SignInEntryIT`（不带入口与现在相同；`portal` 只有其角色的权限、后台操作 403、无其角色 401 记 `NO_ROLE`；刷新保持入口，入口不符 401 且不消费；
  挑战只换得其入口的会话；要求二次验证的角色只在接受它的入口生效；守卫拒绝 403 记 `REFUSED` 不锁定、异常即拒绝、预读数据到达守卫、刷新时拒绝吊销族；
  未验证者在流程 API、数据视图写、实体 API、导入四处 403，目录可见，系统身份照常；入口要求已验证；按已验证邮箱登录；邮箱不区分大小写唯一（含并发）与迁移遇重复即失败；表只插入）、
  `ClientAddressIT`（可信代理链、伪造与格式错误的转发头、UA 截断、公开接口限流同一地址）、`OidcEntryIT`（入口存于 state、回调不能改写、守卫）；
  runtime `SignInEntriesTest`、`ClientAddressesTest`、`SignInGuardsTest`、`JwtServiceTest`、`RbacTest`；core `SignInGuardTypesTest`、`LoginAttemptPolicyTest`。
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

- 服务端：刷新令牌只在其签发后"访问令牌有效期 + 闲置时长"以内可用（且不超过其本身的有效期），否则 401 `INVALID_REFRESH_TOKEN`（不消费、不吊销）。
  闲置时长是令牌族入口的 `session.idle-timeout`，未设即 `jabiz.security.session.idle-timeout`（默认 15 分钟）；入口设为 `0` 时不按闲置结束，只受有效期限制（§15.1）。前端平时只在访问令牌失效后才刷新；用户有键盘、指针活动而没有请求时（例如填写长表单），
  外框每三分之一闲置时长至多刷新一次，使服务端也把会话算作活动。
  访问令牌有效期长于闲置时长 → 启动失败（类别 `SECURITY`）；全局设置与每个闲置时长不为 `0` 的入口都检查，一次报全（`SessionChecks`）。
- 前端：按键盘、指针、滚动与触摸活动计时（同时按上条保持服务端会话）（`idleTimeoutSeconds` 来自 `/api/auth/me`，即会话入口的闲置时长；`0` 表示不锁定），到时登出（吊销令牌族）并回到登录页，提示因闲置而锁定，用户名已填好。

## 12. OIDC 单点登录【D28 第 6 条，阶段 14g-2】

授权码 + PKCE（S256）+ nonce；平台自己校验 ID 令牌，然后照常签发自己的访问令牌与刷新令牌（第 2 节不变）。不用 Spring 的 oauth2-client（它依赖服务端会话），
只用已有的 Nimbus 与 JDK 的异步 HTTP 客户端。不做 SAML、不做单点登出；缺省不自动开户，按提供方开启（【决策 D39】，阶段 16f-1，见下文"自动开户"）；
已登录的用户可自行关联与解除外部账号（见下文"本人关联"）。Apple（`kind: apple`、form_post 回调、以 JWT 作客户端密钥）在 16f-2，推后。

**配置**（`jabiz.security.oidc.providers[i]`）

| 项 | 说明 |
|---|---|
| `id` | 小写字母、数字与连字符，唯一；出现在接口路径与登录记录中 |
| `issuer` | 发现文档 `{issuer}/.well-known/openid-configuration` 的 `issuer` 必须与之完全相同 |
| `client-id`、`client-secret` | 密钥只来自环境变量（如 `JABIZ_OIDC_<ID>_CLIENT_SECRET` 经 Spring 的松散绑定），不写入仓库 |
| `redirect-uri` | 在身份提供方登记的绝对地址，指向后台的 `/login/oidc`（挂在子路径时带上子路径）：入口 `admin` 的回调 |
| `redirect-uris.<入口>` | 其他入口的回调（阶段 16c-2，决策 D43 第 5 条）：该入口前端的 `login/oidc`，如 `https://shop.example.com/portal/login/oidc`；也可写 `admin`（与 `redirect-uri` 二选一）。没有某入口的回调，该提供方就不对该入口提供 |
| `scopes` | 缺省 `openid profile email`，必须含 `openid` |
| `labels` | 登录按钮的文字（`{语言: 文本}`），缺省为 `id` |
| `mfa-amr` | ID 令牌的 `amr` 含其中任一值时视同平台的二次验证；缺省为空（不信任，照常走第 9 节） |
| `kind` | `generic`（缺省）、`google`（16f-1）、`facebook` 或 `line`（16zk，决策 D61 第 1 条）：Google 的 `issuer` 缺省为 `https://accounts.google.com`，邮箱是否可信另有规则（见"自动开户"）；Facebook 缺省 `https://www.facebook.com`、LINE 缺省 `https://access.line.me`（见下文"Facebook 与 LINE"）；其他值启动报错（`apple` 在 16f-2） |
| `auto-provision` | 缺省 `false`：未关联的外部账号可否开户（D39，见"自动开户"） |
| `sign-up-unverified-email` | 缺省 `false`，须同时开启 `auto-provision`（否则启动报错）：提供方不担保的邮箱也开户，账号未验证、可预占（决策 D61 第 3 条，见"自动开户"） |

启动检查（类别 `SECURITY`）一次报告全部问题：缺项（`redirect-uri` 与 `redirect-uris` 至少有一个）、`id` 格式或重复、`scopes` 无 `openid`、
`issuer` / `redirect-uri` / `redirect-uris.<入口>` 不是绝对的 `https` 地址（只有 `localhost` / `127.0.0.1` 可用 `http`，供开发与测试）、回调带片段（`#`）、
`redirect-uris` 的键不是入口名、`redirect-uri` 与 `redirect-uris.admin` 都写了却不同、`kind` 未知、`auto-provision` 而 `scopes` 无 `email`、
`sign-up-unverified-email` 而无 `auto-provision`、`kind: line` 的 `client-secret` 不足 32 字节（UTF-8）；
`auto-provision` 而它服务的入口（有回调的）都不允许注册为**警告**。启动时不访问身份提供方。

**接口**（公开，认证过滤器不处理）

| 方法与路径 | 说明 |
|---|---|
| `GET /api/auth/oidc/providers?entry=` | 对该入口（缺省 `admin`）提供的提供方，即有该入口回调的：`id`、按请求语言的 `label` 与 `kind`（前端据此画按钮），只此而已；未知入口为空列表 |
| `POST /api/auth/oidc/{id}/start?entry=` | 按客户端地址限流（`jabiz.security.oidc.rate-limit.per-address`，缺省每小时 60 次，超限 429 `RATE_LIMITED`：每次发起都写一行）。提供方没有该入口的回调时与未知入口一样答 401 `LOGIN_FAILED`。生成 state、nonce、PKCE verifier 与浏览器绑定值 binder（各 32 字节随机数），写一行 `sec_oidc_state`（state、nonce、binder 只存 SHA-256；verifier 原样，换令牌时要用；10 分钟有效），返回 `{authorizationUrl, binder}`；前端把 binder 存进 `sessionStorage`（它不出现在任何 URL 中）后整页跳转 |
| `POST /api/auth/oidc/callback {state, code, binder}` | 见下；结果与 `POST /api/auth/login` 相同（`SIGNED_IN` / `MFA_REQUIRED` / `MFA_ENROLLMENT_REQUIRED`），其他一律 401 `LOGIN_FAILED`；例外：守卫拒绝、入口要求已验证（403，同 §15.2）、开户时邮箱已被使用（403 `OIDC_ACCOUNT_EXISTS`）、开户超限（429） |

**回调**

1. 消费 state：在 `sec_oidc_state_use` 插入一行（主键保证只能用一次，与刷新令牌相同）；未知、过期、用过、binder 不符、用途不是登录（`purpose = LINK`，本人关联的状态）→ 401。
   binder 把回调绑定到发起登录的那个浏览器：攻击者停在回调之前、把自己的 state 与授权码发给别人时，别人的页面没有对应的 binder，不会以攻击者身份登录（login CSRF）。
2. 以 `client_secret_basic`（`kind: facebook` / `line` 为 `client_secret_post`：`client_id` 与 `client_secret` 在表单中，不带 `Authorization`）与 `code_verifier` 在令牌端点换取令牌（5 秒超时）；`redirect_uri` 是 state 所记入口的回调（与授权请求相同，
   身份提供方据此核对），回调请求里的任何入口都不算；配置中已没有该入口的回调则拒绝。
3. 校验 ID 令牌：签名只接受 RS256 / ES256（拒绝 `none` 与 HS*，防止以公钥作 HMAC 密钥），按 `kid` 取 JWKS 中的公钥；唯一的例外是 `kind: line` 的 HS256，
   密钥是该提供方的 `client-secret`（UTF-8 字节，至少 32 字节，不取 JWKS；决策 D61 第 2 条）——LINE 网页登录的 ID 令牌如此签名，这把密钥只有 LINE 与平台知道，任何 JWKS 中的公钥都不作 HMAC 密钥；`iss` 等于配置；`aud` 含 `client-id`，
   多个受众时 `azp` 必须是 `client-id`；`exp` 未过、`iat` 不在将来（按注入的 `Clock`，容许 60 秒偏差）；`nonce` 的 SHA-256 与记录的一致；`sub` 非空。
4. 以 `(提供方, sub)` 执行流程 `SPONSOR_OIDC_SIGN_IN`（没有关联时先看能否开户，见"自动开户"）；成功时刷新令牌记下所经的关联（`sec_refresh_token.identity_id`），每次刷新都要求该关联仍在，
   因此解除关联即结束其会话（最迟在下一次刷新）。

**Facebook 与 LINE**【决策 D61 第 1–2 条，阶段 16zk】：Facebook 的发现文档在 `{issuer}/.well-known/openid-configuration/`（带结尾的 `/`），
它不写令牌端点时用 `https://graph.facebook.com/oauth/access_token`；Facebook 与 LINE 的 `email_verified` 通常没有，邮箱因此不受担保（`trustedEmail` 为空），
要为这类邮箱开户须开启 `sign-up-unverified-email`（见"自动开户"）。LINE 只有在渠道获准取得邮箱、用户同意时才给 `email`。
公开的说明页以外的细节（端点、签名）由存根测试覆盖，正式上线前要以真实的应用核对一次（D61 剩余风险）。

发现文档与 JWKS 在首次使用时读取并缓存 1 小时；遇到未知的 `kid` 时重新读取 JWKS（至多每分钟一次）；端点必须是 `https`（同上，`localhost` 除外）。
对身份提供方的调用用 JDK 的异步 HTTP 客户端（不跟随重定向、不带链路头，也不在请求线程上阻塞）。观测 `jabiz.auth.oidc`，标签只有 `provider` 与结果（`outcome`）。

**账号关联与登录流程**

- 时态实体 `SecUserIdentity`（`sec_user_identity_version`，`V24__oidc.sql`）：`userId`、`provider`、`subject`，`(provider, subject)` 唯一；`email`（`V39`，`processOnly`，
  开户与本人关联时记下提供方给的邮箱，只用于显示，从不用于登录）。管理员经其数据视图维护（读 `security.user.read`，写 `security.user.identity.write`：关联让对方以该用户登录，
  是独立的凭证，不随修改用户的权限一并授予；写入为管理级二次验证）；用户本人经下文的接口关联与解除；提供方开户时由 `SPONSOR_OIDC_SIGN_UP` 建立。
  **缺省不自动开户**：没有关联即拒绝，除非提供方开启了 `auto-provision`（【决策 D39】；自助注册见【决策 D36】）。
- `SPONSOR_OIDC_SIGN_IN`（`auth.sign-in`，内部）与密码登录一样写登录记录（`factor = OIDC`）：没有关联 → 不写记录；锁定中 → `LOCKED`；禁用 → `DISABLED`；
  无角色 → `NO_ROLE`；然后二次验证：`amr` 符合 `mfa-amr` 且 ID 令牌带 `auth_time` → 登录，会话的 `mfa_at` 取 `auth_time`（不是现在：
  提供方很久以前的二次验证不算第 10 节的"最近"；没有 `auth_time` 则不视同）；否则已绑定 TOTP → `MFA_REQUIRED`（第 9 节的第二步）；
  否则角色要求 → `MFA_ENROLLMENT_REQUIRED`。外部登录不猜密码，失败不计入锁定次数，但已有的锁定照样生效。
- 只用单点登录的用户：`SEC_USER_CREATE` 的密码可以不填；没有密码哈希的用户用密码登录总是失败（照样比对假哈希），而且与不存在的用户名一样**不写登录记录**，
  否则任何人都能以错误的密码锁住这类账号（它们没有能对的密码）。
- 谁都可以发起登录，所以 `sec_oidc_state` / `sec_oidc_state_use` **不是**只追加表：每次发起时删除过期的请求，表中至多是最近 10 分钟的请求；它们也不被封存（21 §2）。
- 发现文档、JWKS 与令牌响应至多读 256 KB，超出即停止读取。没有 `kid` 的 ID 令牌逐一尝试同类的全部公钥（密钥轮换期间常有两把）。
- `sec_oidc_state` 记下用途（`purpose`：`SIGN_IN` 或 `LINK`，`V39`）与关联的用户（`link_user_id`，只有 `LINK`）：登录的回调不接受关联的状态，关联的回调只接受调用者本人、本入口的关联状态。

**自动开户**【决策 D39 第 1、4 条，阶段 16f-1】

- **条件**（同时满足才开户）：提供方 `auto-provision: true`；状态记录的入口 `self-registration: true`（`admin` 入口不能允许注册，§15.7，所以后台从不开户）；
  提供方担保的邮箱（`IdTokenValidator.Identity.trustedEmail`）：ID 令牌有像样的 `email`（至多 320 字符）且 `email_verified` 为真（布尔，或不分大小写的文本 `"true"`）；
  `kind: google` 还要求邮箱是 `@gmail.com` / `@googlemail.com`，或令牌带 `hd`（Workspace 域）——Google 对其他域的邮箱只在建账号时验证过一次，账号的主人未必仍拥有该邮箱；
  这条规则按 `kind` **或** issuer 适用：issuer 为 `https://accounts.google.com` 而 `kind` 不是 `google` 的提供方同样按它判断，启动检查另给警告（建议写 `kind: google`）；
  只在其 `email_verified` 可靠（提供方确实验证并持续拥有邮箱，如企业自己的目录）的通用提供方上开启 `auto-provision`；
  该邮箱（不分大小写）没有被任何用户使用。
- **提供方不担保的邮箱**【决策 D61 第 3 条，阶段 16zk】：提供方同时开启 `sign-up-unverified-email` 时，ID 令牌中像样的 `email` 即使未经担保也开户，但账号**未验证、可预占**：
  写 `email` 与 `registeredEmail`，不写 `verifiedEmail` / `emailVerifiedAt`，没有密码，与自助注册（§15.7）一样发验证邮件 `jabiz.auth.verify-email`；
  入口 `require-verified-email` 时随后的登录照常 403 `EMAIL_NOT_VERIFIED`。邮箱已被使用照常 403 `OIDC_ACCOUNT_EXISTS`；令牌没有邮箱则不开户（401）。
  邮箱的主人可以经验证或找回密码接管这个账号（删除关联，§15.7）；账号本人在**自己的会话中**验证则保留账号与关联（§15.7"验证"）。
- **后台从不开户**：除启动检查外，回调本身也不在 `admin` 入口开户（`OidcController.opensAccounts`）。
- **开户**（流程 `SPONSOR_OIDC_SIGN_UP`，`entryOnly`，权限 `auth.sign-up`，只经回调运行）：用户名同注册（小写邮箱，被占用时加 `-` 与 6 位随机字符）；写 `email`、
  `verifiedEmail`、`emailVerifiedAt`，**不写 `registeredEmail`**（永不可预占；提供方不担保的邮箱例外，见上条）；没有密码；`displayName` 取 ID 令牌的 `name`（去掉控制与格式字符、合并空白、至多 200 字符）；
  `locale` 取请求的语言（平台语言之一，否则不写）；授予入口的注册角色（缺少或停用即不开户）；建 `SecUserIdentity`（带邮箱）。之后照常运行 `SPONSOR_OIDC_SIGN_IN`：
  写登录记录，受入口、`SignInGuard`、锁定与二次验证规则约束（角色要求二次验证即 `MFA_ENROLLMENT_REQUIRED`）。邮箱受担保时开户不发邮件（用户刚刚亲自登录）。
- **邮箱已被使用**（任何用户，含可预占的账号与禁用的账号）：不开户、不关联，403 `OIDC_ACCOUNT_EXISTS`（"请用原来的方式登录，再在账户设置中关联"）。提供方刚向请求者证明了邮箱，
  只对他区分这种情况（同 D36 实现说明第 2 条）；可预占账号的主人照常经找回密码接管。其他不满足的情况一律 401 `LOGIN_FAILED`，不建用户、不写记录，原因只记日志。
- **限流**：开户按客户端地址（`SignUpRateLimits` 的 `OIDC_SIGN_UP`，沿用 `jabiz.security.sign-up.rate-limit.per-address`，缺省每小时 20 次），超限 429，不建用户。
- **并发**：同一 subject 或同一邮箱同时开户时，`(provider, subject)` 与邮箱的唯一约束保证只建一个用户；输的一方再问一次（这时得到已关联或邮箱已被使用）。
  同一用户两个登录同时写登录记录时，后者再登录一次（OIDC 登录不猜密码，重试不影响锁定计数）。
- 只信任经令牌端点换来的 ID 令牌；重放与绑定照旧（state 只能用一次、nonce、PKCE S256、binder、10 分钟）。

**本人关联**【决策 D39 第 2 条，阶段 16f-1】

已登录的平台用户在**当前会话的入口**关联与解除外部账号；只提供对该入口提供的提供方（有该入口的回调）。会话已结束的访问令牌（§15.9）一律 401。

| 方法与路径 | 说明 |
|---|---|
| `GET /api/auth/account/identities` | 本人的关联：`[{id, provider, label, kind, email, linkedAt}]`（`linkedAt` 为关联第一个版本的生效时间；提供方已不在配置中时 `label` 为其 `id`、`kind` 为空） |
| `POST /api/auth/account/identities/start {provider, currentPassword?}` | 按用户限流（同改密码的 `jabiz.security.account.rate-limit.per-user`，各自计数；解除与关联共用这个计数）；提供方未知或不对该入口提供 404。访问令牌的 `mfa_at` 在 `step-up-max-age` 之内即开始；否则须有当前密码（流程 `SEC_IDENTITY_LINK_BEGIN`）：错误 422 `PASSWORD_INCORRECT`，两者都没有 422 `REAUTHENTICATION_REQUIRED`；账号没有密码（如提供方开的账号）时 422 `REAUTHENTICATION_UNAVAILABLE`——先开启两步验证并以它登录或 step-up，或经找回密码设一个密码。写状态记录（`purpose = LINK`、`link_user_id`），回答 `{authorizationUrl, binder}`（同登录的 `start`） |
| `POST /api/auth/account/identities/callback {state, code, binder}` | 消费状态（须是调用者本人、本入口的关联状态），换取并校验 ID 令牌，运行 `SEC_IDENTITY_LINK_SELF`：`{status: LINKED | ALREADY_LINKED, provider}`。往返本身的任何失败（状态、换令牌、ID 令牌）答 422 `IDENTITY_LINK_FAILED`（不是 401：调用者的会话没有问题，401 会让客户端登出） |
| `DELETE /api/auth/account/identities/{id}` | `SEC_IDENTITY_UNLINK_SELF`，204（与关联共用按用户的限流）；不是本人的关联 404；解除后既无密码又无其他还能登录的关联（其提供方仍在配置中且对某个入口提供）时 422 `LAST_SIGN_IN_METHOD`。经该关联登录的会话在下一次刷新时结束（每次刷新都要求关联仍在） |

- 流程都是 `entryOnly()`、权限 `auth.account`（不授予任何角色），经流程接口调用即 403 `PROCESS_INTERNAL`；流程内再确认操作的是调用者本人、账号已启用。
- `SEC_IDENTITY_LINK_BEGIN`：与本人改密码（§15.8）共用同一把按用户的排他锁；锁定期间不比对密码（记 `LOCKED`），错误记 `BAD_CREDENTIALS`（计入失败次数与锁定），
  正确时不写记录；没有密码的账号 422 `PASSWORD_NOT_SET`（这类用户用近期二次验证，或先经找回密码设一个密码）。
- `SEC_IDENTITY_LINK_SELF`：按用户的排他锁逐个进行（关联与解除共用）；可预占的账号（§15.7）422 `IDENTITY_LINK_UNAVAILABLE`——邮箱的主人仍可接管它，预占者不能先留下外部登录的后门；
  外部账号已关联到别人 422 `IDENTITY_IN_USE`（并发时唯一约束同样答这个）；已关联到本人即幂等（`ALREADY_LINKED`，不发邮件）；本人已关联该提供方的另一个外部账号 422 `IDENTITY_PROVIDER_LINKED`
  （每个提供方一个）。成功时写关联（带 ID 令牌的邮箱）；`jabiz.mail.enabled` 开启且用户有邮箱时发 `TRANSACTIONAL` 邮件 `jabiz.auth.identity-linked`（三语，参数为入口与提供方的标签，不带令牌，
  正文给出不带令牌的找回密码页）。
- 秘密不进操作记录：`currentPassword` 标 `@Sensitive`；请求与 `Identity` 的 `toString` 遮蔽。
- 前端：`@jabiz/client` 的 `startIdentityLink(provider, {currentPassword?}, returnTo)`（在 `sessionStorage` 记下用途 `LINK`，每次发起都重写）、`finishIdentityLink`、
  `listIdentities`、`unlinkIdentity`；`@jabiz/ui` 的 `OidcCallback` 按记下的用途走登录或关联（`onLinked`），`OIDC_ACCOUNT_EXISTS` 时说明怎样继续；积木 `LinkedIdentities`
  （列表、需要时先问当前密码、解除）；`OidcButtons` 按 `kind` 画提供方的标志（Google 的四色"G"、Facebook 的蓝色"f"、LINE 的绿色气泡，各按其品牌指南的颜色，D44），`layout="icons"` 时为"其他方式登录"下的一排圆形图标按钮（以提供方的标签为名，D61 第 5 条）。示范门户的账户页与后台安全设置页（`/account/security` 的一节，只在后台入口提供了身份提供方时显示）使用它。

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

## 15. 登录入口、登录前的应用检查、邮箱验证、登录来源、自助注册与找回密码【决策 D36，阶段 16b】

一个平台上可以有几个前端（后台、应用的 App、商家后台），各自只接受部分角色；签发的令牌只含该入口所接受角色的权限，令牌泄露不会越出它所属的前端。
自助注册、验证邮件与找回密码（D36 第 2、3、5 条，阶段 16b-2）见 §15.7。

### 15.1 入口配置

```properties
jabiz.security.entries.portal.accepted-roles=CUSTOMER        # 角色码，* 为全部
jabiz.security.entries.portal.self-registration=true          # 缺省 false（16b-2）
jabiz.security.entries.portal.registration-roles=CUSTOMER     # 注册时授予，须在 accepted-roles 中
jabiz.security.entries.portal.require-verified-email=true     # 缺省 false
jabiz.security.entries.portal.app-path=/portal/               # 缺省 /；该入口前端的根，邮件链接在它之下（16c-2）
jabiz.security.entries.portal.session.refresh-token-ttl=30d   # 缺省为 jabiz.security.refresh-token-ttl；上限 90 天（16m）
jabiz.security.entries.portal.session.idle-timeout=0          # 缺省为 jabiz.security.session.idle-timeout；0 表示不按闲置结束
jabiz.security.entries.portal.session.persistence=device      # tab（缺省）或 device；admin 入口不能用 device
```

- 名字 `[a-z][a-z0-9-]{0,39}`。缺省入口 `admin` **总是存在**：未配置时接受全部角色（与引入入口之前相同），显式配置则以配置为准。
  请求不带 `entry` 即 `admin`，现有后台前端无需改动。
- 启动检查（类别 `SECURITY`）一次报告全部问题：名字非法、`accepted-roles` 为空、角色码格式、注册角色为 `*` 或不在接受的角色中、允许注册却无注册角色、
  `app-path` 不是绝对路径（含 `.` / `..` 段）、`session.refresh-token-ttl` 不为正或（入口自己设置时）超过 90 天、`session.idle-timeout` 为负、
  `session.persistence` 不是 `tab` / `device`、`admin` 入口用 `device` 或 `session.idle-timeout` 为 `0`、长期会话的入口（`device` 或闲置时长 `0`）接受 `*`；
  允许注册而 `jabiz.mail.enabled` 未开启、角色码（当前版本中）不存在、`app-path` 不在任何已配置 SPA 之下（或有 SPA 声明了该入口
  （`jabiz.web.spa[i].entry`）而 `app-path` 不在它之下，16c-2）为**警告**。
  有错误的入口不提供（请求它即 401）。访问令牌有效期长于某入口的闲置时长另由 `SessionChecks` 报告（§11）。
- **会话设置**（决策 D43 第 2 条，阶段 16m）：有效期与闲置时长见 §2、§11；`persistence` 告诉前端把刷新令牌放在哪里（`tab` 为 `sessionStorage`，
  `device` 为 `localStorage`，关闭后再打开仍保持登录），由 `GET /api/auth/entries/{name}` 的 `sessionPersistence` 给出；服务端对两者一视同仁（轮换、重用即吊销照常）。
  长期会话不设绝对上限（滑动；以后可加 `session.max-lifetime`）。后台（`admin`）的会话只能在标签页内，且按闲置结束。
- **长期会话不含管理权限**（D43 第 2 条）：`device` 或闲置时长 `0` 的入口，启动检查拒绝 `accepted-roles=*`；角色与其权限是可随时修改的数据，启动时无从确定，
  所以在每次登录（三条路径）与刷新时（`Rbac.withoutAdministration`），被接受的角色中授予 `*` 或任何 `security.*` 权限的**不计入**该会话
  （其权限、二次验证要求与数据期限都不算）；只剩这类角色的用户在该入口即 `NO_ROLE`（401）。
- 已不存在的入口（例如升级前签发的挑战或刷新令牌所属的入口被删除）不接受任何角色：默认拒绝。

### 15.2 登录、令牌与刷新

- `POST /api/auth/login {userName, password, entry?}`：未知入口 401 `LOGIN_FAILED`（不写记录）。`userName` 也可以是**已验证**的邮箱（不区分大小写）：
  先按用户名精确匹配，没有时（且含 `@`）再按邮箱找，只有地址已验证的用户才算；未验证的地址与不存在的名字一样（401、无记录）。登录记录的 `userName` 是用户的名字。
- 三条登录路径（密码、二次验证的第二步与 step-up、OIDC）共用 `accessSteps`：先解析入口（`SignInEntryStep`），**只取该入口接受的角色**，再由这些角色计算权限、
  `mfaRequired`（§9）与数据期限（§13.2）。因此"要求二次验证的角色"只在接受它的入口要求二次验证（有意的语义变化）。没有被接受的启用角色 → `NO_ROLE`，401，记录带入口。
- 角色检查之后、签发之前（`entrySteps`）：入口要求已验证而用户未验证 → `EMAIL_NOT_VERIFIED`（403 `EMAIL_NOT_VERIFIED`）；然后是应用的守卫（§15.3），
  拒绝 → `REFUSED`（403 `SIGN_IN_REFUSED`）。两者都写登录记录、不计失败（`LoginAttemptPolicy`）；只在密码（或身份提供方、第二因素）已证明之后才区分，
  不泄露账号是否存在。之后才是 §9 的第二步。
- **访问令牌**加 `entry`（升级前签发、没有它的令牌按 `admin` 处理）与 `email_verified`；**挑战令牌**加 `entry`，`SPONSOR_MFA_VERIFY` 的入口取自挑战令牌而非请求；
  step-up 留在调用者会话的入口。`Actor`、`RequestContext` 加 `entry`、`emailVerified`（旧构造器保留：无入口、未验证）。`GET /api/auth/me` 加 `entry`、`email`、`emailVerified`。
- **刷新**：`sec_refresh_token.entry` 随令牌族沿用（旧行为空，读作 `admin`）。`POST /api/auth/refresh {refreshToken, entry?}` 的入口与令牌的不符 →
  401 `INVALID_REFRESH_TOKEN`，**不消费**、不吊销。刷新时 `RbacService.renew` 按入口过滤角色、重读 `emailVerified`（入口要求已验证而现在未验证 → 401，
  例如管理员改了邮箱）并调用守卫。
- **OIDC**：`POST /api/auth/oidc/{id}/start?entry=…` 把入口存入 `sec_oidc_state.entry`（未知入口、或提供方没有该入口的回调，401），回调用存下的值
  （包括换取令牌时的回调地址，§12）；回调请求中的任何入口都不算。
- 开发用请求头的操作人属于 `admin`、视为已验证（同二次验证）；`TestTokens.bearer(…)` 签发的令牌属于 `admin`、已验证，`TestTokens.unverified(…)` 未验证。

### 15.3 登录前的应用检查 `SignInGuard`

```java
@Component
class PortalSignInGuard implements SignInGuard {
    // 平台预读：数据视图中"用户编号字段 = 该用户"的行，每项至多 SignInGuard.MAX_ROWS（100）行
    public List<SignInLoad> loads() {
        return List.of(SignInLoad.of("customers", "urn:jabiz:dataset:default:Customer", "userId"));
    }

    // 同步、无 I/O
    public SignInDecision check(SignInAttempt attempt) {
        return attempt.rows("customers").stream().anyMatch(c -> Boolean.TRUE.equals(c.get("blocked")))
            ? SignInDecision.refuse("blocked") : SignInDecision.ALLOW;
    }
}
```

- `SignInAttempt`：用户编号与名字、入口、**过滤后的**角色码、`emailVerified`、因素（`PASSWORD` / `OIDC` / `TOTP` / `RECOVERY_CODE` / `REFRESH`）、时间、预读数据。
- 平台步骤 `SignInGuardStep` 在三条登录路径与第二步中、角色检查之后签发之前运行；刷新时 `RbacService` 同样调用。多个守卫全部放行才放行；
  守卫抛异常、返回 null、预读失败或超过行数上限即拒绝（fail closed）；拒绝原因只记日志，不给调用者。
- **刷新时被拒绝**：不写登录记录（刷新不是流程），记 info 日志与指标 `jabiz.auth.refresh.refused`（无标签），事务回滚（令牌不消费）后吊销该令牌族（原因 `REFUSED`），401。
  因此封禁在下一次刷新时生效（访问令牌在到期前仍有效，同 §2 的已知限制）。
- 启动检查：预读的数据视图或字段不存在、字段放不下用户编号（UUID，如数值、时间字段）、同名的预读含义不同、`loads()` 抛异常 → 错误。预读经数据视图（其范围适用，权限不适用，同流程内的平台 I/O 步骤）。
  用户编号以文本交给查询，按字段的语义类型转换（引用 `SecUser` 的字段比较 UUID，文本字段比较文本），同其他查询条件（03 §2.2）。

### 15.4 邮箱验证状态与 `requiresVerifiedEmail`

- `SecUser.verifiedEmail` / `emailVerifiedAt`（`processOnly`，只由验证类流程写，16b-2）。**已验证 = `lower(verifiedEmail) = lower(email)`**：
  管理员改邮箱即自动失效，不需要清除标记。不新增"待验证"状态：未验证即待验证，由入口的 `require-verified-email` 决定能否登录。
- `SecUser.email` 非空时**不区分大小写唯一**（`eb.uniqueIgnoreCase(…)`，core `UniqueConstraint.ignoreCase`）：时态唯一检查（D6）按数据库 `lower()` 折叠后的值取锁与查找
  （先 `SELECT lower(:v)` 得到锁键所用的值，再 `col IS NOT NULL AND lower(col) = lower(:v)`；两边都由数据库折叠，与索引一致——Java 对 `İ` 等字母的折叠不同，不能用），走部分索引 `sec_user_version_email_idx ON (lower(email)) WHERE email IS NOT NULL`）；普通实体的同类约束是 `lower(列)` 上的唯一索引。
  `V32__sign_in_entries.sql` 遇到已有的不区分大小写的重复（各用户当前版本、未删除）即 `RAISE EXCEPTION` 并列出地址：**升级前须由管理员处理**。
- 声明：流程 `pb.requiresVerifiedEmail()`（或 `ProcessDefinition.withVerifiedEmail(true)`），数据视图 `policy(p -> p.requiresVerifiedEmail())`（**只管写**，读取的限制以后另议）。
- 检查（`VerifiedEmailPolicy`，与 `Permissions`、`MfaPolicy` 同在六个入口）：流程 API、数据视图 `commit`、实体 API 的增改删（流程或默认视图要求时）、导入（行流程要求时）、
  撤销（所涉数据视图要求时）、通用实体流程在流程内再查一次。依据是访问令牌的 `email_verified`；未验证 → 403 `EMAIL_NOT_VERIFIED`。
  系统身份、子流程、场景回放（回放的操作人视为已验证）、事件消费者与定时任务不检查。
- 目录：`/api/meta/processes` 的 `requiresVerifiedEmail`、`/api/meta/datasets` 的 `writeRequiresVerifiedEmail`。

### 15.5 登录来源

- 登录记录增加 `entry`、`clientIp`、`userAgent`（去掉控制与格式字符后截断为 256 个字符；防火墙拒绝的头不记，登录照常）。
- `ClientAddresses`（登录记录与公开接口限流共用，15 §5）：`jabiz.security.trusted-proxies`（地址或 CIDR 列表）。连接来自可信代理时才读 `X-Forwarded-For`
  （多个头按顺序合并），从右向左跳过可信地址，取第一个不可信地址；全链可信取最左。只解析走到的段：不可信地址左边客户端自己写的内容不看（写 `junk` 抹不掉自己的地址）；
  走到格式错误的段（非 IP 字面量）即停止，取已走过的最后一个有效段（没有则为连接地址）；至多走 20 个可信段，之后取其中最后一个。
  段可带端口（`192.0.2.1:443`、`[2001:db8::1]:443`，端口去掉；1–65535），所以给每一跳加端口的代理不会把所有人并成一个限流桶。
  不配置可信代理时取连接地址；与 `server.forward-headers-strategy` 同时配置 → 启动检查报错（转发头会被处理两次而可伪造）。只配置 `server.forward-headers-strategy`（不是 `none`）也是启动错误（`dev` 下为警告，D47 第 4 条）：Spring 相信每个连接的转发头，任何客户端都能自报地址、逃过按地址的限流；代理应写在 `jabiz.security.trusted-proxies` 中。地址只进登录记录，不进日志与观测标签。

### 15.6 已知限制

- 升级前签发的访问令牌在有效期内按 `admin` 处理，且 `email_verified` 为假（要求已验证的操作需重新登录）。
- 守卫在刷新时才再次生效；已签发的访问令牌在到期前仍可用。
- 按邮箱登录时，名字不匹配（且含 `@`）才多一次按邮箱的查询（`UserByEmailStep`；按名字登录不查），响应时间与"名字存在"略有差别（不比现有的不存在名字更多泄露）。
- step-up 被守卫拒绝或入口要求已验证时与登录相同：403 `SIGN_IN_REFUSED` / `EMAIL_NOT_VERIFIED`；码错误、锁定仍为 422 `MFA_CODE_INVALID`。step-up 的登录记录同样带地址与 UA。
- 刷新时的检查顺序：已吊销（照常 401，族已结束）→ 已用过（重用：吊销整个令牌族，无论入口，也无论令牌是否已过期或闲置）→ 过期 → 闲置 → 入口不符（只对未用过的有效令牌：不消费、不吊销）。
  空白的 `entry`（登录、刷新、OIDC 的 `start`）与不带一样，是 `admin`。
- 匿名注册每次计算 BCrypt，可被用来消耗 CPU：靠按地址与邮箱的限流和阻塞线程池的上限；限流按实例（多实例时各自计数）。
- 找回密码与重发的申请，两种情况仍有极小的耗时差（多写一条邮件消息与 Outbox 事件）；注册的新邮箱一方多写用户与角色分配。
- 邮件关闭时注册的用户收不到验证邮件，无法验证（启动告警）。
- 接管可预占的账号时删除其二次验证、吊销其会话；预占者在此之前签发的访问令牌在有效期内仍可用（同 §2）。
- 管理员设置的邮箱在本人验证之前不能找回密码（升级前的账号都如此）；忘记密码又没验证过邮箱的用户由管理员重设密码。
- 持有 `security.user.write` 的人可以把受害人的邮箱改成自己的；受害人若随后在登录状态下要求验证邮件，攻击者就得到一个已验证的邮箱，可以找回密码（二次验证在重置后保留）。计划的缓解：发送前向本人显示目标地址。
- 管理员改邮箱时 `verifiedEmail` 不清除：改回以前验证过的地址即重新算作已验证。
- 接管可预占的账号时删除其全部 `SecUserIdentity` 关联（16f-1；可预占的账号自己不能关联，但管理员可能关联过）；预占者经该关联已签发的访问令牌在有效期内仍可用（同 §2）。
- 验证时输错当前密码（`CurrentPasswordStep`）只按客户端地址限流，不计入账号的失败次数与锁定（持有链接的人本来就能设新密码）；本人改密码（§15.8）输错则计入。

### 15.7 自助注册、邮箱验证与找回密码（16b-2）

流程（`SignUpProcesses`，都是 `internal`、权限 `auth.sign-up` 不授予任何角色，只经下列接口运行）与接口（`SignUpController`）：

| 接口 | 流程 | 回答 |
|---|---|---|
| `POST /api/auth/register {entry, email, password, displayName?, locale?}`（匿名） | `SPONSOR_SIGN_UP` | 202（无正文），对任何邮箱相同；入口不存在或不允许注册 422 `SIGN_UP_DISABLED` |
| `POST /api/auth/join {token, password?}`（匿名） | `SPONSOR_SIGN_UP_JOIN` | 204；链接无效 422 `TOKEN_INVALID`；可预占的账号没给新密码 422 `REQUIRED`（`field: password`） |
| `POST /api/auth/verify-email/check {token}`（匿名） | `SPONSOR_EMAIL_VERIFY_CHECK`（试运行，不使用令牌） | `{passwordRequired}`；链接无效 422 `TOKEN_INVALID` |
| `POST /api/auth/verify-email {token, password?, newPassword?}`（匿名） | `SPONSOR_EMAIL_VERIFY` | 204；链接无效 422 `TOKEN_INVALID`；可预占的账号缺密码 422 `REQUIRED`、密码错 422 `PASSWORD_INCORRECT`（`field: password`） |
| `POST /api/auth/account/verify-email/check {token}`、`POST /api/auth/account/verify-email/confirm {token, password?, newPassword?}`（已登录，D61 第 4 条） | 同上两行 | 同上；链接所属的账号就是会话的用户时，可预占的账号不需要密码（见"验证"） |
| `POST /api/auth/verify-email/resend {email, entry}`（匿名） | `SPONSOR_EMAIL_VERIFY_SEND` | 202，对任何邮箱相同；只给启用、可预占的账号发信 |
| `POST /api/auth/account/verify-email`（已登录） | 同上（本人、会话的入口） | 202；给启用、邮箱未验证的本人发信 |
| `POST /api/auth/password-reset/request {email, entry?}`（匿名） | `SPONSOR_PASSWORD_RESET_REQUEST` | 202，对任何邮箱相同；只给启用、邮箱可信的用户发信 |
| `POST /api/auth/password-reset/confirm {token, password}`（匿名） | `SPONSOR_PASSWORD_RESET_CONFIRM` | 204，不自动登录；链接无效 422 `TOKEN_INVALID` |
| `GET /api/auth/entries/{name}`（匿名） | — | `{name, selfRegistration, requireVerifiedEmail, appPath, sessionPersistence, emailLink, environment}`（`tab` / `device`，§15.1；`environment` 为 `production` 或测试系统的名字，17 §3.2）；未知 404 |

`SIGN_UP_DISABLED` 一律是 422（入口的配置不是秘密）：入口不存在或不允许注册，或注册角色不存在、已停用；被拒的加入不消费令牌。

**两类账号**（决定哪些邮件可以发、链接能做什么；不另设状态）：

- **可预占的账号**：自助注册而来（`registeredEmail`，`processOnly`，只由注册写入）、**从未**验证过（`emailVerifiedAt` 为空）、当前邮箱（不分大小写）仍是注册时的邮箱。
  注册它的人未必拥有这个邮箱，所以证明邮箱所有权（加入、验证、重置）的人可以**接管**它：设新密码、删除二次验证、删除外部账号的关联（16f-1）、吊销全部令牌族（`claim`）。
  可预占的账号不能自行关联外部账号（`SEC_IDENTITY_LINK_SELF` 答 422 `IDENTITY_LINK_UNAVAILABLE`，§12）。
- **可信的邮箱**：已验证，或属于可预占的账号。只有这类邮箱收得到找回密码与"你已有账号"的邮件，这两种链接也只对它有效。
  **管理员设置或修改的邮箱从不可信**（持有 `security.user.write` 的人改了别人的邮箱，不能借找回密码接管账号）：其用户登录后用 `POST /api/auth/account/verify-email` 验证一次。
  建号时填写、之后无人改过的邮箱另可由管理员寄**邀请**（§15.14，决策 D63）：本人凭邀请的链接设定密码，同时证明邮箱，不必先登录。
  因此 16b-2 之前创建的账号在验证邮箱之前不能找回密码（升级说明）。

- **邮件**（D35，`TRANSACTIONAL`，三语消息 `mail.jabiz.auth.<名>.subject|body`，唯一的参数是入口）：`jabiz.auth.verify-email`（令牌 `jabiz.verify`，
  `jabiz.security.sign-up.verify-ttl` 缺省 24 小时）、`jabiz.auth.account-exists`（令牌 `jabiz.join`，`jabiz.security.sign-up.join-ttl` 缺省 24 小时）、
  `jabiz.auth.password-reset`（令牌 `jabiz.reset`，`jabiz.security.password-reset.ttl` 缺省 1 小时）。令牌用途在平台的命名空间 `jabiz.` 中，
  应用的模板不能使用这个前缀（名字或令牌用途，启动检查 `MAIL` 报错），因此应用的邮件令牌不会被这些流程接受。
  链接 `{entryUrl}auth/<页>?entry=<入口>&token=<令牌>`（阶段 16c-2，决策 D43 第 4 条）：`{entryUrl}` 是 `{baseUrl}` 加入口的 `app-path`（以 `/` 结尾），
  即该入口自己的前端（后台为 `{baseUrl}/auth/…`，示范门户为 `{baseUrl}/portal/auth/…`），由 `@jabiz/ui` 的 `MailLinkPage` 处理（12 §12）；
  后台的落地页 `/auth/*` 保留并照常接受任何入口，16c-2 之前发出的链接（`{baseUrl}/auth/…?entry=portal`）仍可用；完成后到入口的 `app-path`。
  落地页读出令牌后即用 `history.replaceState`
  从地址栏去掉它，打开期间 `<meta name="referrer" content="no-referrer">`，并且只在点击后才提交（邮件扫描器打开链接不改变任何东西）。
  令牌只存 SHA-256，同一用户同一用途只有最新的有效（重发即作废旧的），用一次即失效。
- **不可枚举**：注册、重发、找回密码先检查请求的每个值（邮箱格式与长度、密码规则、显示名、语言、入口），再计数限流，最后运行流程；
  因此值错误对任何邮箱都一样（400），回答是 202 与相同的响应头（`Content-Length: 0`）、空正文。注册的两种情况**都计算 BCrypt**（`HashPasswordStep`）；
  已被使用的邮箱不建用户、不用提交的密码，邮箱可信时给其用户发"你已有账号"（禁用的、邮箱不可信的不发）。并发注册同一新邮箱时，后到的那个遇到邮箱的唯一冲突，
  再运行一次（这次按已被使用处理，其用户收到"你已有账号"），同样答 202。验证链接的 `check` 只从令牌本身回答，不涉及其他邮箱。
- **注册**：用户名取邮箱小写（至多 100 字符），已被占用时截短并加 `-` 与 6 位随机字母数字；邮箱按提交的保存，同时写 `registeredEmail`；授予入口的注册角色；
  验证邮件发到提交的地址、用提交的语言（没有则用请求的语言）。未验证的用户能否登录由入口的 `require-verified-email` 决定（细则 7）；
  按邮箱登录只认已验证的邮箱（§15.2），用户名即小写邮箱，按它登录照常。
- **链接的有效性**：令牌须是该用途的、未过期、最新、未用过（`MailTokens`）；其用户须仍存在且启用、当前邮箱（不分大小写）仍是发信时的地址；
  加入与重置的链接还须邮箱可信。在计算任何密码哈希之前检查；任何一项不符都是同一个 422 `TOKEN_INVALID`。
  三处证明了邮箱所有权，都写 `verifiedEmail` 与 `emailVerifiedAt`（细则 5）：验证、加入、重置。
- **加入**（细则 6）：入口取自那封邮件的参数（`MailMessageParams`），须仍允许注册；追加尚未持有的注册角色、写验证字段。**可预占的账号**还必须同时设新密码并被接管
  （吊销原因 `JOIN`）；其他账号（已验证的）只追加角色，密码不变。注册请求中的密码从不用于已有账号。
- **验证**：可预占的账号需要其**当前密码**（注册人自己，保留账号）或**新密码**（邮箱的主人，接管账号，吊销原因 `CLAIM`）；其他账号（已登录的本人要求的验证）只凭链接。
  **账号本人的会话**代替当前密码（决策 D61 第 4 条，16zk）：在已登录的接口 `/api/auth/account/verify-email/check|confirm` 上、会话的用户就是链接所属的用户时，
  不需要密码、不接管，账号、关联与会话都保留——经身份提供方开的、没有密码的账号由此验证；别人的会话不算（照常要求密码）。公开接口从不认证访问令牌，所以这是另外两个接口。
  落地页先用 `check` 问清是否需要密码；有会话时先在会话中问（`@jabiz/client` 的 `checkVerificationLink` / `confirmVerificationLink`，会话已不被接受（401）时改为匿名），
  需要密码时提示"在已登录的浏览器中打开此链接"。
- **找回密码**：设置新密码（只用 OIDC 的用户也可）、写验证字段、吊销全部令牌族（原因 `PASSWORD_RESET`）、写登录记录 `PASSWORD_RESET`
  （清零失败计数并解锁，带入口、地址与 UA）。**二次验证保留**：重置证明的是邮箱，不是认证器，下次登录照常要求第二步；只有可预占的账号被接管时才删除。
  不自动登录。密码不合规则（400）时令牌不消费。
- **限流**（`SignUpRateLimits`，按实例在内存中，同 15 §6）：每个客户端地址（`ClientAddresses`，转发头只在来自可信代理时采用）每类请求每小时 20 次
  （注册、重发、找回、使用链接各自计数）；每个邮箱（小写）每类请求每小时 3 次，对存在与不存在的邮箱一样计数；已登录的重发按用户计。超限 429 `RATE_LIMITED`，带 `Retry-After`。
  配置：`jabiz.security.sign-up.rate-limit.per-address|per-email|period`。
- **后台入口**：注册得到的角色不能登录后台。`admin` 入口自己允许注册（在 `admin` 或不带入口注册的人会直接进入后台），或任何入口允许注册时 `admin` 入口接受 `*`（未配置即如此，细则 9）或某个注册角色，启动检查 `SECURITY` 都报**错误**；
  允许注册的应用须显式配置 `jabiz.security.entries.admin.accepted-roles`，只列后台的角色。
- **启动检查**：上述后台入口的错误；允许注册而 `jabiz.mail.enabled` 未开启、注册角色（当前版本中）不存在为警告（§15.1）；开启邮件而缺少 `jabiz.mail.base-url` 为错误（`MAIL`，D35）；
  应用的邮件模板使用 `jabiz.` 前缀为错误（`MAIL`）。
- **升级**：迁移 `V33__registered_email.sql` 只加列；之前的账号没有 `registeredEmail`，不会被当作可预占的账号；其邮箱在验证之前不收找回密码与"你已有账号"的邮件，
  匿名的重发也不发（由本人登录后在 `/api/auth/account/verify-email` 验证）。
- 示范（`backend/app`）：入口 `admin`（`JABIZ_ADMIN_ROLES`，缺省 `ADMIN`）与 `portal`（接受并授予 `CUSTOMER`，允许注册（`JABIZ_PORTAL_SELF_REGISTRATION`，缺省开），要求已验证）；
  `PortalSignInGuard` 拒绝被封禁（`Customer.blocked`）的客户；流程 `PORTAL_ORDER_PLACE`（`requiresVerifiedEmail()`，客户取自调用者的账号而非输入，子流程 `ORDER_PLACE`）。

### 15.8 本人改密码（阶段 16m，决策 D43 第 3 条）

`POST /api/auth/account/password {currentPassword, newPassword, refreshToken?}`（已登录）→ 流程 `SEC_ACCOUNT_PASSWORD_CHANGE`
（`AccountPasswordProcess`；`entryOnly()`，权限 `auth.account` 不授予任何角色，经流程接口调用即 403 `PROCESS_INTERNAL`；只改调用者本人的账号）。

- **顺序**：按客户端地址（`ClientAddresses`）与按用户限流（`jabiz.security.account.rate-limit.per-user`，缺省每小时 10 次；地址同注册的 20 次），超限 429 `RATE_LIMITED`；
  再检查请求：缺 `currentPassword` 400 `REQUIRED`，`newPassword` 不合密码规则 400（`PASSWORD_TOO_SHORT` 等）——这两种都不算一次尝试；然后运行流程。
- **流程**：读用户（须是调用者本人、已启用，否则 403）；没有密码（只用 OIDC）→ 422 `PASSWORD_NOT_SET`（不写记录，引导走找回密码，§15.7）；
  先取该用户的排他锁（`HoldLock`，同一用户的改密码尝试逐个进行：并发的尝试否则都基于同一条最新登录记录，除一条外都在唯一约束上失败而不留记录，猜测不被计数），
  再读最新登录记录，**锁定期间不比对密码**（记 `LOCKED`）；否则 `CurrentPasswordStep`（阻塞步骤，BCrypt）比对当前密码，错误记 `BAD_CREDENTIALS`
  （**计入失败次数与锁定**，同登录 §4），两者都答 422 `PASSWORD_INCORRECT`（`field: currentPassword`），流程正常结束使登录记录留下。
  比对了当前密码（无论对错）就计算新密码的哈希（`HashPasswordStep`），两种结果做同样的工作，耗时不泄露猜测是否正确；正确时写入新哈希，记登录记录 `PASSWORD_CHANGED`（不计失败，计数也不清零：没有完成一次完整的登录），
  吊销本人的其他令牌族（原因 `PASSWORD_CHANGE`）：`refreshToken` 是本人在**当前会话入口**的有效刷新令牌（未用过、未过期、族未吊销）时保留其族，
  否则（没给、别人的、别的入口的、已用过的）全部吊销，客户端重新登录。204。
- **登录记录**带入口、地址与 UA（§15.5），因素 `PASSWORD`。
- **邮件**：`jabiz.mail.enabled` 开启且用户有邮箱时发 `TRANSACTIONAL` 邮件 `jabiz.auth.password-changed`（三语消息 `mail.jabiz.auth.password-changed.subject|body`，
  唯一的参数是入口，**没有令牌**；正文给出不带令牌的找回密码页 `{entryUrl}auth/password-reset?entry=<入口>`，即该入口前端的）。
- **不要求近期的二次验证**（当前密码已足够；16m-2）。已签发的其他会话的访问令牌在到期前仍可用（§2 的已知限制）。
- 秘密不进操作记录：输入的 `currentPassword`、`newPassword`、`refreshToken` 都标 `@Sensitive` 并按名字遮蔽；拒绝只记 info 日志（用户编号）。

### 15.9 会话列表与吊销（阶段 16g）

**会话**即一个刷新令牌族（`family_id`）：一次登录（密码、二次验证或 OIDC）在某个入口、某个设备或标签页上产生的会话（入口持久化为 `device` 时同一设备的标签页共用一族，§15.1）。
**有效的会话**：族未吊销，族内最新的令牌未用过、未过期，且按族所属入口的闲置窗口未闲置（§11；入口不闲置结束时只看有效期）——与刷新时的判断相同。
已过期、已闲置的族本来就不能刷新，不列出，也不再吊销。会话编号就是 `family_id`：它不是凭证（凭证是令牌本身），可以返回给客户端。

- **数据**：不新建表。迁移 `V38__session_list.sql` 给 `sec_refresh_token` 加 `client_ip`、`user_agent`（登录与每次刷新签发令牌时的客户端地址与 UA，清理与截断同登录记录 §15.5；
  地址经 `ClientAddresses`）、索引 `(user_id, expires_at)`，给 `sec_refresh_family_revocation` 加 `process_seq_id`（§2）。表仍只追加；加列不影响已封存的行（21 §2）。
  会话的字段都由族内的行推出（`RefreshTokenStore.liveSessions`）：`createdAt` = 族内最早的签发时刻，`lastUsedAt` = 最新令牌的签发时刻（每次刷新即一次使用），
  `expiresAt`、`clientIp`、`userAgent` 取最新令牌的，`mfa` / `oidc` 由 `mfa_at` / `identity_id` 推出。
- **设备**：core 的 `UserAgentLabels.of(ua)`（纯 Java，不引入库）把 UA 归为浏览器（`EDGE` `OPERA` `SAMSUNG` `WECHAT` `FIREFOX` `CHROME` `SAFARI` `OTHER`）、
  系统（`WINDOWS` `IOS` `ANDROID` `CHROME_OS` `MACOS` `LINUX` `OTHER`）与设备类型（`DESKTOP` `MOBILE` `TABLET` `OTHER`），前端按码翻译；接口同时返回原样的 UA。
  UA 是客户端自己的说法，只帮人分辨会话，不据此作任何判断。不做按地址的大致位置，也不让客户端给设备起名。
- **访问令牌带会话编号**：`sid` 声明（`Actor.sessionId`）。登录（密码、二次验证、OIDC）先签发刷新令牌，再以其族签发访问令牌；刷新沿用族；step-up 沿用调用者的 `sid`。
  `/api/auth/me` 返回 `sessionId`。`RequestContext` 不变：平台的会话接口经安全上下文（`CurrentSession`）取得它。升级前签发的访问令牌没有 `sid`，在有效期内照常可用。
- **本人**（已登录的平台用户；只看、只吊销**当前会话入口**的会话，决策 D36：门户的长期令牌看不到也吊销不了后台的会话。改密码仍结束全部入口的会话，跨入口由管理员处理）：

| 接口 | 作用 |
|---|---|
| `GET /api/auth/account/sessions` | `{sessions: [{id, entry, current, createdAt, lastUsedAt, expiresAt, clientIp, userAgent, device: {browser, os, deviceType}, mfa, oidc}], truncated, effectiveWithinSeconds}`；按 `lastUsedAt` 倒序，最多 50 条，更多时 `truncated` 为真；`current` 标出调用者自己的会话（令牌的 `sid`） |
| `POST /api/auth/account/sessions/{id}/revoke` | 流程 `SEC_ACCOUNT_SESSION_REVOKE`，204。不是本人的、不在当前入口的、已失效的、不存在的、格式不对的编号一律 404 `SESSION_NOT_FOUND`，不泄露会话是否存在。可以吊销当前会话，等于登出：客户端随即清空本地令牌 |
| `POST /api/auth/account/sessions/revoke-others` | 流程 `SEC_ACCOUNT_SESSIONS_REVOKE_OTHERS`：吊销本人在当前入口除当前会话外**全部尚未结束的族**（未吊销、有未过期的令牌，含列表不显示的闲置会话，§2），`{revoked, effectiveWithinSeconds}`。令牌没有 `sid`（升级前签发）时 422 `SESSION_UNKNOWN`：重新登录后再试 |

  两个流程都是 `entryOnly()`、权限 `auth.account`（不授予任何角色），经流程接口调用即 403 `PROCESS_INTERNAL`；要保留的会话由控制器从令牌的 `sid` 填入，调用者伪造不了；
  流程内再确认操作的是调用者本人。不要求当前密码或二次验证（吊销只缩小访问范围）。吊销按用户限流（`jabiz.security.account.rate-limit.per-user`，与改密码同一上限、各自计数），超限 429；列表不限流。
- **管理员**：`GET /api/security/sessions?userId=`（`security.user.session.read`）列出该用户在**全部入口**的有效会话，字段同上，`current` 恒为假。
  流程 `SEC_USER_SESSIONS_REVOKE {userId, sessionId?, reason}`（`security.user.session.revoke`，`requiresMfa(ADMINISTRATION)`，`actsOn(SecUser, userId)`：用户列表上自动出现行操作）：
  给了 `sessionId` 只吊销这一个（不是该用户的有效会话即 404 `SESSION_NOT_FOUND`），否则吊销全部尚未结束的族（含闲置的，§2）；`reason` 必填，最多 500 字，随输入记入操作记录。不发邮件。
  读权限单独设立（列表含地址与 UA），不复用 `security.login-record.read`；管理员角色经 `*` 获得。
- **流程中的吊销**：步骤 `RevokeSessionStep`（平台 I/O 步骤）在流程事务内：吊销单个时确认该族属于该用户且是有效会话（本人流程还要求在当前入口）；吊销全部时取该用户（本人流程限当前入口）全部尚未结束的族（`RefreshTokenStore.revokeAll`，同 §2 改密码）。
  吊销行带原因（`SELF_REVOKE` / `ADMIN_REVOKE`）与 `process_seq_id`。
- **会话已结束的访问令牌**：step-up 与本人的会话接口（列出、吊销单个、吊销其他）先确认令牌的 `sid` 所指的族尚未结束（属于调用者、在调用者的入口、未吊销、有未过期的令牌），
  否则 401 `UNAUTHENTICATED`：被吊销会话的访问令牌不能靠反复 step-up 续命，也不能在别处把用户登出。没有 `sid` 的令牌照旧。其他接口仍按访问令牌本身判断（D12）。
- **生效**：被吊销的会话下一次刷新得到 401 `INVALID_REFRESH_TOKEN`，客户端回到登录页；已签发的访问令牌在到期前仍然有效，最长 `jabiz.security.jwt.access-token-ttl`（缺省 15 分钟）。
  不做拒绝名单（每个请求查库是 D12 放弃的做法；内存中轮询吊销表在多实例间不一致），这与禁用用户、改密码、守卫拒绝的生效窗口相同。接口给出 `effectiveWithinSeconds`（访问令牌有效期），
  页面据此提示"其他设备将在 N 分钟内退出"。访问令牌已带 `sid`，以后要做拒绝名单时不必再改令牌格式。
- **前端**：`@jabiz/client` 的 `listSessions()`、`revokeSession(id, current)`（吊销当前会话时清空本地会话）、`revokeOtherSessions()`；`@jabiz/ui` 的积木 `SessionList`（12 §12），
  后台账户安全页 `/account/security` 与示范门户的账户页使用它；后台管理页 `/security/sessions`（12 §5）。管理页按用户名搜索用户
  （`SecUser` 默认数据视图的查询，`userName` 的 `LIKE` 筛选，因此还需要 `security.user.read`）：`SecUser` 不声明显示字段（登录名不作为引用的显示名外露，§14），引用选择器的查找用不上。
- **审计与观测**：每次吊销都有 `op_process`（操作人、流程、遮蔽后的输入：会话编号、用户编号、原因），吊销行经 `process_seq_id` 追溯到它。吊销表不是实体，不进 `sys_audit_record`（D27 只覆盖实体写入）。
  流程照常由 `PlatformObservations` 按流程名与结果记录；标签与日志不放地址与 UA（info 日志只记用户编号与会话编号）。
- **已知限制**：吊销与轮换并发时（轮换先读到族未吊销，吊销随后提交），已签发的新刷新令牌在下一次刷新时被拒，只有其访问令牌可能多活到期满。
  每次刷新都记下地址与 UA，个人数据随令牌行累积，令牌表的受控清除尚未实现（§2）。列表的设备名称来自客户端自报的 UA。

### 15.10 流程接口的限流（阶段 16o，决策 D45 第 5 条）

`POST /api/processes/{码}/…`（行操作也经它）可按流程限流，只在配置中声明（运维可调，不需要重新构建）：

```yaml
jabiz:
  process:
    rate-limits:
      "[PORTAL_ORDER_PLACE]": { per-user: 20, period: 1m }      # 流程名作映射键时写方括号，保留大小写
      "[QB_ATTEMPT_SUBMIT]":  { per-user: 30, per-address: 300, period: 1m }
```

- 三种桶，各自可选：`per-user`（按操作人）、`per-address`（按 `ClientAddresses` 的客户端地址，转发头只在来自可信代理时采用，§15.5）、`per-instance`（该流程在本实例的总量）；`period` 缺省 1 分钟。
  桶是 `TokenBucketLimiter`（进程内、每桶至多 100 000 个键、单调时钟），超限 429 `RATE_LIMITED` + `Retry-After`，按实例计数（同 15 §6 与 §15.6 的已知限制）。
- 先查请求涉及的全部桶，都有令牌才各取一个：一个桶拒绝时不消耗其他桶。幂等键的重放同样计数：区分重放要读数据库，而被限流的请求不访问数据库。
- 顺序：认证 → 权限 → **限流**（`ProcessRateLimits`）→ 二次验证 → 已验证邮箱 → 职责分离 → 幂等重放 → 准入（06 §4.2）→ 事务。没有权限的请求本来就 403，不消耗他人的桶；
  被限流的请求不读数据库、不借连接（`ProcessAdmissionIT` 在连接全被占用时确认）。
- 只作用于流程接口：子流程、定时任务、事件消费者与平台自己的入口（登录、实体接口、上传等）不经它。启动检查（`PROCESS`）：配置的流程须已注册且进目录（不是 `internal()` / `entryOnly()` / `subProcessOnly()`），
  数值为正，至少声明一种桶。每次拒绝记一次观测 `jabiz.process.rate_limited`（瞬时的观测，作计数用；标签只有 `process`、`scope`，13）。
- 与 `SignUpRateLimits`（§15.7）、`SignInRateLimits`（§4.1）的关系：后两者限的是平台自己的匿名账户接口、本人账户操作与登录类接口（种类写死在代码中），不变；三者共用 `TokenBucketLimiter` 与 `ClientAddresses`。
  限流不是准入：限流挡住单个用户或地址刷请求，准入挡住所有人一起挤一把锁（503 `PROCESS_BUSY`，06 §4.2）。

### 15.11 以邮件中的链接登录（阶段 16zb，决策 D54）

开启了 `email-link` 的入口（不能是 `admin`，也不能接受 `*`）可以只凭邮箱登录：页面寄出一次性链接，打开链接即登录，不需要密码。

```yaml
jabiz:
  security:
    entries:
      app:
        accepted-roles: QB_TAKER
        self-registration: true      # 无人使用的地址可经链接开户
        email-link: true
    email-link:
      ttl: PT15M                     # 链接的有效期
      max-ttl: P30D                  # 应用邮件中的登录链接（D55）最长的有效期
```

| 接口 | 作用 |
|---|---|
| `POST /api/auth/email-link` | `{entry, email, to?, locale?}`，任何地址都答 202（入口没有开启即 422 `SIGN_UP_DISABLED`，`to` 等不合格即 400）；可信地址上该入口会接受的用户、或（开放注册时）无人使用的地址收到链接 |
| `POST /api/auth/email-link/confirm` | `{token}`，答复同登录（`SIGNED_IN` 或第二步）；无效即 422 `TOKEN_INVALID` |

- 前端：`EmailLinkForm` 放在应用自己的页面上；登录页可用 `SignInForm` 的 `emailLink`，把它放在密码之上（入口开启时才显示；17m，见 12"登录与账户的积木"）。
- 链接只寄往可信的地址：管理员设而本人没有验证过的地址收不到（同 §15.7 的验证链接）。
- 打开链接：登录该用户；接管可预占的账号（清除密码等，同 §15.7）；或为无人使用的地址开已验证、无密码的账号（入口的注册角色）。
  之后照常是登录流程 `SPONSOR_EMAIL_LINK_SIGN_IN`：登录记录（因素 `EMAIL_LINK`）、`SignInGuard`、入口角色与二次验证都照旧。
- 开启链接登录的入口上，授予 `*` 或 `security.*` 的角色不计（每次登录与刷新，同长会话，§15.1 与 D43 第 2 条）；入口不接受的用户收不到链接，打开时不再接受的在改变任何东西之前拒绝。
- `to` 是自站点根起、在入口 `app-path` 之内的路径（服务端与 `EmailLinkPanel` 都检查，解码后的路径段不能是 `.`、`..`）；登录后回到它，否则回到入口的 `app-path`。
- 链接只用一次；同时寄出的几封中先用的生效，之后寄出的照常有效（`MailTokens.consumeAnyUnused`，18 §5.6）。
- 限流：请求按客户端地址与邮箱（`EMAIL_LINK`），确认同其他链接（`LINK`）。
- **应用邮件中的登录链接**（16ze，决策 D55）：应用模板以 `signInLink(有效期)` 声明后，自己的流程可把登录链接寄给任何地址（`SendMail`，收件人为地址或用户）；
  有效期至多 `jabiz.security.email-link.max-ttl`（缺省 30 天）。发送时检查入口开启了 `email-link`、`to` 在该入口前端之内（422 `MAIL_SIGN_IN_LINK`）；
  打开链接与上面完全相同（`SPONSOR_EMAIL_LINK_OPEN` 再 `SPONSOR_EMAIL_LINK_SIGN_IN`）。

### 15.12 凭外部平台签发的令牌登录（阶段 17b，决策 D59）

内嵌在另一个平台后台中的前端（如 Shopify 应用的内嵌页面）以该平台签发的短时令牌换取本平台的会话，不另设密码。

```yaml
jabiz:
  security:
    external-tokens:
      shop:                                         # 提供方名：身份的提供方为 ext:shop
        secrets: [ "${SHOP_APP_SECRET:}" ]           # 只取自环境变量（写空缺省值：缺失即启动失败）；更换时写两个
        audience: ${SHOP_APP_KEY}
        tenant-pattern: '[a-z0-9][a-z0-9-]*\.myshopify\.com'
        issuer-format: https://{tenant}/admin
        destination-format: https://{tenant}          # 租户的源（不带路径），内嵌时即 frame-ancestors
        auto-provision: true                        # 无人关联的身份首次登录即开户（入口须允许注册）
        frame-ancestors: [ https://admin.shopify.com ]
        tenant-parameter: shop                      # 平台打开页面时带的租户参数
    entries:
      shop:
        accepted-roles: MERCHANT
        self-registration: true
        registration-roles: MERCHANT
        external-token: shop
```

| 接口 | 作用 |
|---|---|
| `POST /api/auth/external-token` | `{token, entry}`，答复同登录（`SIGNED_IN` 或第二步）；令牌与身份的一切拒绝都是 401 `LOGIN_FAILED`（守卫 403、开户过多 429）；服务器故障照常 5xx（17e） |

- 验证（`ExternalTokenRules`）：HS256、任一密钥的签名（常量时间）、`exp` / `nbf`（容差）、`aud`、`iss` 与 `dest` 指向同一个匹配 `tenant-pattern` 的租户、`sub` 与 `jti`；`jti` 只用一次（`sec_external_token_use`；登记时已过最后时刻即拒绝，免得清理删掉第一次使用的记录，17e）。启动检查拒绝未解析的占位符密钥与带路径的 `destination-format`（17e）。
- 身份是租户中的一个人：`SecUserIdentity(ext:<提供方>, <租户>/<sub>)`。之后照常是写登录记录的流程 `SPONSOR_EXTERNAL_TOKEN_SIGN_IN`（因素 `EXTERNAL_TOKEN`）：`SignInGuard`、入口角色、二次验证照旧。
- 开户（`SPONSOR_EXTERNAL_TOKEN_SIGN_UP`）：提供方 `auto-provision` 且入口允许注册时，为无人关联的身份建无邮箱、无密码的用户（用户名即身份主体）与入口的注册角色；不能是 `admin` 或接受 `*` 的入口，也不能要求已验证的邮箱。
- 业务流程以步骤 `VerifyExternalToken.of(提供方, ctx -> 令牌, 键)` 核对店员递来的令牌属于操作人（403 不说明原因：说明一律为 "External token refused"，原因只记日志），把租户、主体与令牌放进上下文，供阻塞步骤交给签发它的平台；不消耗 `jti`。
- 页面：`@jabiz/client` 的 `signInWithExternalToken(令牌)`（向平台的脚本每次取新令牌）；SPA 声明 `embedding`（17 §3.2）才能被该租户内嵌。

### 15.13 应用代理：另一个平台转发的访客请求（阶段 17d，决策 D60）

商店平台把店面上的请求（店铺域名下的 `/apps/<子路径>/...`）转发给应用，并在查询中加上租户、已登录顾客的编号、时间与签名。平台以**应用代理**承接：
验签之后以代理的调用者同步运行一个流程，答复即流程的输出。店面上的顾客匿名，与平台的用户无关。

```yaml
jabiz:
  app-proxies:
    shop:                                           # 代理名：路径 /api/proxy/shop/<流程>
      secrets: [ "${SHOP_APP_SECRET:}" ]            # 即应用密钥，只取自环境变量（写空缺省值：缺失即启动失败）；更换时写两个
      tenant-pattern: '[a-z0-9][a-z0-9-]*\.myshopify\.com'
      # tenant-parameter: shop                      # 缺省值
      # customer-parameter: logged_in_customer_id   # 缺省值；空即匿名访客
      # visitor-parameter: visitor                  # 缺省值；店面自己生成的访客键，只用于限流
      # max-age: 90s                                # 签名时间与现在之差，缺省 90 秒，至多 10 分钟
  process:
    rate-limits:
      "[SHOP_QUIZ_ANSWER]": { per-tenant: 600, per-visitor: 10, period: 1m }
```

- 接口 `GET|POST /api/proxy/{代理}/{流程}`：未配置（或配置有问题）的代理 404，其他方法 405（`AppProxyWebFilter`，在安全链之前）；带来的凭据一概不看。
- 签名（`AppProxySignature`，与 Shopify 自己的库相同）：查询中除 `signature` 外的全部参数（访客自己的也在内）按名排序，写成 `名=值`（同名多值以 `,` 相连），
  不加分隔地连起来，以应用密钥作 HMAC-SHA256，小写十六进制，常量时间比较、每个密钥都试。时间 `timestamp` 须在 `max-age` 内；租户参数恰好一个且匹配 `tenant-pattern`；
  顾客编号为空或是 `[A-Za-z0-9_-]{1,40}`。任一不符即 401 `APP_PROXY_REFUSED`，说明相同，原因只记观测 `jabiz.app_proxy.refused`（标签 `proxy`、`reason`）与 debug 日志（谁都能发未签名的请求，日志不应被灌满）。
  验签之前不按地址限流：请求都来自商店平台的出口地址，按地址限流会挡住所有正常的顾客；验签本身不访问数据库。
  查询按表单解码（`+` 是空格），与签名方一致。
- 流程须是 `appProxy(代理)` 声明的最新版本（否则 404，不透露别的流程）；以 `AppProxyCaller` 运行：操作人 `app-proxy:<代理>[:<顾客>]`、
  `RequestContext.tenantId` 为租户、权限只有 `app-proxy.<代理>`；流程以 `AppProxyCaller.of(ctx.request())` 读取。代理名至多 30 字符、顾客编号至多 40，操作人编号因此放得进各操作人列（100）。
  输出若是实体实例，与读接口一样按权限遮蔽（`f.masked` 的字段对匿名访客总是遮蔽）。不写操作记录之外的东西，不签发会话。
  操作人编号不含租户：所有店铺的匿名访客是同一个操作人。代理流程因此不用按操作人区分的平台功能（`StartExternalJob` / `CancelExternalJob` 的"本人开始的作业"、幂等键），
  归属一律以租户与业务数据（访客键、领取记录）判断。
- 正文（POST 的 JSON）是流程的输入，**不在签名之内**：它与访客发来的任何东西一样不可信，由流程校验。签名过的地址在 `max-age` 内可被重放：代理流程本来就面向匿名访客，
  伤害受限流与业务上的"一人一次"约束。
- 限流（`jabiz.process.rate-limits`）：`per-tenant`（按租户）与 `per-visitor`（租户内按访客键；键由访客自己给出，不证明什么，只用于分摊租户的额度；
  不是一个 `[A-Za-z0-9_-]{1,40}` 的键按"无键"计）只用于代理流程；代理流程不能用 `per-user`（所有店铺的匿名访客是同一个操作人，启动检查报错）；
  `per-address` 计 `ClientAddresses` 得出的地址（经商店平台转发时多为其出口地址）。超限 429 `RATE_LIMITED`。
- 场景回放（07 §3）：`appProxy()` 流程的步骤代表代理入口，步骤的操作人即代理的调用者：`{id: app-proxy:<代理>[:<顾客>], tenant: <租户>, permissions: [app-proxy.<代理>]}`。
- 答复不缓存（`Cache-Control: no-store`），不读不写 Cookie，不开 CORS（请求由商店平台从它自己的域名发出）。
- 启动检查（`AppProxies`，类别 `APP_PROXY`）：代理的名字、密钥（至少 32 字符、不是未解析的占位符）、租户形式、参数名、`max-age`；`appProxy()` 流程只持代理的权限、
  所指的代理已配置、不要求二次验证、已验证邮箱或替代权限（调用者不是本平台的用户）。

### 15.14 管理员建号的邀请（阶段 17o，决策 D63）

管理员建的账号没有本人的密码，邮箱也未经证明（§15.7：管理员填写的邮箱不可信，收不到找回密码的链接）。邀请把"证明邮箱"与"设定本人的密码"合成一封邮件：

| 接口 / 流程 | 说明 |
|---|---|
| `SEC_USER_INVITE {userId, entry?}`（流程接口，`security.user.create`，二次验证，`actsOn(SecUser)`） | 给用户寄邀请（模板 `jabiz.auth.invitation`，令牌 `jabiz.invite`，`jabiz.security.invitation.ttl` 缺省 72 小时）；链接 `{entryUrl}auth/invitation?entry=…&token=…`，入口缺省 `admin`，未知入口 422 `INVALID_VALUE`；答复 `{userId, status: SENT}` |
| `SEC_USER_CREATE {…, invite: true}` | 建号的同一事务中寄出（入口 `admin`）；须有格式正确的邮箱（422 `MAIL_NO_ADDRESS` / `INVALID_VALUE`）、不能同时给密码（422 `INVALID_VALUE`）、不能停用（422 `INVITATION_USER_DISABLED`） |
| `POST /api/auth/invitation/confirm {token, password}`（匿名，`SPONSOR_INVITATION_ACCEPT`） | 204；与找回密码相同：设定密码、证明邮箱、吊销全部令牌族（`PASSWORD_RESET`）、写登录记录 `PASSWORD_RESET`、二次验证保留、不自动登录；任何不好的链接 422 `TOKEN_INVALID` |

- **只寄给需要的人**：用户启用（否则 422 `INVITATION_USER_DISABLED`）、有邮箱（`MAIL_NO_ADDRESS`）、邮箱未验证（已验证的由本人找回密码，422 `INVITATION_NOT_NEEDED`）。
- **只寄到建号时的地址**：建号之后改过或补填的地址（任何人改的，包括以"本人"会话改的）422 `INVITATION_ADDRESS_CHANGED`（`AddressVerifiable.unchanged`，按所有权链的 `addressAsCreated`）：
  不论账号是否干净、受保护的授权是否开启——请求的是管理员而不是本人，改地址正是接管账号的手法。地址填错的账号重新建立。建号时的地址去掉首尾空白后保存。
- **邮件未开启**（`jabiz.mail.enabled`）即 422 `MAIL_DISABLED`：不记下一封永远不会寄出的邀请。
- **链接**：只认最新的一封（新的邀请使之前的失效，`MailTokens.consume`）；邮箱已验证（本人已接受某一封）、用户停用或邮箱已改即作废。
- **所有权链**（§16.2）：`SPONSOR_INVITATION_ACCEPT` 与找回、加入、验证同列为"寄到本人邮箱的一次性链接"，所以建号时的地址经接受后算本人的，所设密码是本人的。
- **不需要审批，但按 §16 评估**：邀请即设定密码，所以对持有受保护权限（或有待审批的变更将给他）的用户与 `SEC_USER_SET_PASSWORD` 一样评估：建立期内照常寄出，
  建立期结束后 422 `PROTECTED_CHANGE_REQUIRES_APPROVAL`（退役的首个管理员 403）——填写地址的管理员可能读得到那个邮箱。先收回受保护的权限、邀请、本人接受后再申请授权，
  审批人在事实中看得到地址、由谁填写与何时经邮件证明。
- **前端**：`@jabiz/client` 的 `acceptInvitation`、`@jabiz/ui` `MailLinkPage` 的 `invitation` 页（`InvitationPanel`：两次输入新密码；失效时说明并请联系管理员，不能自助重寄）；
  通用后台的路由 `/auth/invitation`。应用把邀请指向自己的入口时，其前端也要挂载该页（同其他邮件链接页）。
- **测试**：`InvitationIT`（建号即邀请、接受后邮箱已验证并能以邮箱登录、链接只能用一次、建立期之后受保护的授权 `APPLIED`、替换管理员知道的密码、新邀请使旧链接失效并按入口生成链接、
  地址改后链接作废、建号后改过或补填的地址被拒、已验证 / 停用 / 无邮箱 / 未知入口被拒、建号时带密码 / 无邮箱 / 格式不对 / 停用被拒且不建号、首尾空白、
  建立期之后受保护权限的持有人被拒、权限）、`InvitationWithoutMailIT`（邮件未开启）、`CredentialChainTest`（接受邀请算邮件证明，补填的地址不算；`addressAsCreated`）、
  前端 `AuthPages.test.tsx`、`blocks.test.tsx`（axe）与示范门户的 `App.test.tsx`。

## 16. 受保护的授权：四眼【决策 D48 第 1 条，阶段 16r】

一个管理员不能独自让某人（包括自己或另一个账号）得到能改权限、能审批、能记账的权限，也不能独自接管持有这类权限的账号。
这类变更先登记为待审批，由**另一位管理员**审批后才生效；待审批的变更没有任何效果（默认拒绝）。

### 16.1 受保护的权限与受保护的变更

**受保护的权限**（`jabiz.security.protected-grants.permissions`，逗号分隔；缺省如下；`*` 与 `security.protected-change.decide` 无论怎样配置都受保护）：

| 权限 | 为什么 |
|---|---|
| `*` | 持有全部权限 |
| `security.user-role.write`、`security.role.write` | 改权限的权限：持有者能把任何权限给任何人（包括自己） |
| `approval.decide`、`control.publish` | 别的四眼的另一双眼：审批单据、发布审批规则、限额、职责分离规则与受控参数（18 §3） |
| `security.protected-change.decide` | 这道四眼自己的另一双眼：审批本节的受保护变更（D51）；总是受保护 |
| `ledger.post`、`ledger.reverse` | 写账（冲正即反向过账，11 §1） |
| `platform.param.write` | 独自修改未受控的业务参数（04 §9） |

不在缺省中的：`security.user.password` / `security.user.mfa-reset` / `security.user.identity.write`（对受保护持有人使用时本身就要审批，对其他人是日常的账号支持）、
`temporal.revert` / `temporal.backdate`（写入仍经过本节的检查，`processOnlyWrites` 的数据视图不能撤销）、只读权限、`security.user.write`（不改权限；管理员设置的邮箱从不可信，§15.7）。
应用可以在配置中改写这份清单（例如加上自己的付款权限）。

**受保护的变更**（`ProtectedGrants`）：

| 种类 | 什么时候受保护 | 请求的流程 | 审批层级的权限 |
|---|---|---|---|
| `USER_ROLE` 分配角色 | 角色授予用户尚未持有的受保护权限 | `SEC_USER_ROLE_ASSIGN {userId, roleId, dataFrom?, dataTo?, effectiveTime?, reason}` | `security.user-role.write` |
| `ROLE_PERMISSION` 角色增加权限 | 权限受保护，且角色有用户尚未持有它 | `SEC_ROLE_PERMISSION_ADD {roleId, permission, effectiveTime?, reason}` | `security.role.write` |
| `PASSWORD` 重设密码 | 用户持有受保护权限 | `SEC_USER_SET_PASSWORD`（不变） | `security.user-role.write` |
| `MFA_RESET` 重置二次验证 | 同上 | `SEC_MFA_RESET`（不变） | `security.user-role.write` |
| `IDENTITY_LINK` 关联外部账号 | 同上，且不是用户本人关联（§12 的本人关联不受影响） | `SEC_USER_IDENTITY_LINK {userId, provider, subject, reason}` | `security.user-role.write` |
| `EMAIL` 更改邮箱 | 用户持有（或有待审批的变更将给他）受保护权限（评审 M-b：找回密码的链接寄往邮箱） | `SEC_USER_EMAIL_SET {userId, email, reason}` | `security.user-role.write` |

- **审批的权限**（D51）：`APPROVAL_DECIDE` 对这类变更要求 `security.protected-change.decide`，**代替** `approval.decide`——后者不能审批受保护的变更，前者也不能审批其他任何审批
  （`*` 两者都能）。流程的入口接受两者之一（备选权限 `orPermissions`），取得请求后按对象要求其中之一（`ApprovalDecisionCheck.decidePermission`，不论状态，缺少即 403）。
  所以把安全管理与业务审批分开的职责分离规则（例如禁止同时持有安全管理的权限与 `approval.decide`）不妨碍安全管理员审批受保护的变更。
- **职责分离禁止的不要求**（D51 修订 H1）：一条已发布的职责分离规则禁止审批人在已持有的权限之外再持有的受保护权限，审批人不必持有（`*` 除外）；
  审批时与生效时各按当时的规则判断，事实 `sodExempt` 写明。职责分离按审批实际使用的权限检查（对象所需的审批权限与层级的权限，`CheckApprovalDecision`）。
- **审批人**（评审 H2）：除层级的权限与 `security.protected-change.decide` 外，须持有 `*`，或持有管理员的权限（`jabiz.security.protected-grants.administrator-permissions`，
  缺省 `security.user-role.write` / `security.role.write`，§16.3）**并且**持有这次变更给出的
  全部受保护权限（重设凭据时为目标用户持有的全部受保护权限）：谁也不能批准比自己持有的更多的东西；能重设密码的人（`security.user.password`）不能批准接管管理员的账号。
  审批时按访问令牌判断，生效时按审批人在库中当时实际持有的（启用的用户、启用的角色、当时生效的版本）再判断一次。
- **持有**按"可能持有"计：每个分配、角色与授权的最新版本未删除即算，不论角色、用户是否启用、分配是否预定（同职责分离 18 §4.2）。所以启用一个停用的角色或用户
  从不带来检查没有看见的权限，启用不必另查；已持有 `*` 的用户再得到什么都不算获得。
- **待审批的变更也算**（评审 H1）：判断一个授权时，待审批的分配与角色权限视同已生效——角色给出它待审批的权限，角色的用户包括待审批的分配；
  因此一个请求待审批期间，给同一角色加受保护的权限、或把加着受保护权限的角色分配给别人，同样要审批，不能借对方把权限"带进去"。
  减去"已持有"时只算真正已有的，待审批的不会让一次授权显得什么也没给。
- 修改分配的数据期限（`dataFrom` / `dataTo`）不是获得权限（期限只限数据，§13.2）。
- 三个请求流程都要求管理级二次验证；不受保护的变更（或在 §16.3 的建立期内）当即写入，与经数据视图相同，回答 `status: APPLIED`；
  受保护时回答 `status: PENDING`、`changeId`、`approval: PENDING`、`approvalRequestId`，什么也没有写入。`SEC_USER_SET_PASSWORD` 与 `SEC_MFA_RESET` 的输出
  增加同样的字段（`CredentialResetOutput`，兼容）。

### 16.2 流程

```
请求（管理员甲）──► SecProtectedChange（PENDING，内容不可变）+ 审批请求（对象 jabiz.security.protected-change，一层）+ 待办
审批（管理员乙，/tasks 的 ApprovalPanel 或 APPROVAL_DECIDE）──► jabiz.approval.approved / rejected
SEC_PROTECTED_CHANGE_APPLY（internal，事件消费者）──► 再经 RequireApproval 确认内容哈希相同 ──► 带许可写入 ──► APPLIED
```

- **待审批的变更** `SecProtectedChange`（时态，`sec_protected_change_version`，`V41`）：种类、用户、角色、权限、数据期限、生效时间、提供方与外部账号、新密码的哈希（**敏感**，
  与 `SecUser.passwordHash` 同等保存，读接口不返回）、原因、申请人；状态（`PENDING` → `APPLIED` / `REJECTED` / `FAILED`）、审批人、结果编号、失败原因只由流程写。
  数据视图 `processOnlyWrites`，读权限 `approval.read`。
- **审批对象** `jabiz.security.protected-change`（实体 `SecProtectedChange`，事实 `kind`、`user`、`role`、`permission`、`gained`（给出的受保护权限）、`affected`（所针对的用户名）、
  `credentials`（他们的密码最后由谁、何时设置，以及凭据是否本人的）、`address`（当前邮箱：谁、何时填写或更改，是否经邮件链接证明；
  配置了 `jabiz.security.protected-grants.trusted-email-domains` 时标出不在这些域（及其子域）中的地址）、`newAddress`（更改邮箱时的新地址）、
  `history`（他们凭据的**全部历史**：谁建立了账号，每一次密码、邮箱、二次验证与外部关联的变更，各带操作人、时间、流程与是否本人的判断；
  过长时**总是保留第一行（建立账号及其地址）**，省略中间、保留最近的部分，评审 M-a））：**层级由平台固定**（`RequireApproval.fixed`，18 §3.3），一层，权限见上表，不读任何审批规则——规则是数据，不能让这类变更"不需要审批"。
  审批内容（`ContentHash`）是变更的全部内容（含新密码的哈希、要删除的二次验证的编号），**并包括请求时给出的受保护权限与所针对的用户**（评审 H1），
  单据标识（待办标题）如 `alice ← POSTER (ledger.post)`、`POSTER + ledger.post (3)`、`alice: PASSWORD`。
- **职责分离**（`APPROVAL_DECIDE`，18 §3.4）：申请人不能审批（`APPROVAL_OWN_REQUEST`）；**变更所针对的用户**不能批准（422 `APPROVAL_TARGET_USER`，按审批时的情况判断：
  角色增加权限时为该角色中因此获得它的每个用户）；审批需要近期二次验证（`ADMINISTRATION`）；首个管理员见 §16.3。这些检查经 18 §3.4 的 `ApprovalDecisionCheck`。
- **生效**：消费者以系统身份运行 `SEC_PROTECTED_CHANGE_APPLY`。事件须是**这个变更自己的审批请求**的（事件中的请求编号须属于对象 `jabiz.security.protected-change`、
  该变更编号的请求）；指向别的请求的事件——任何流程都可能发出一个——不是它的，什么也不做（评审 L2 复核）。之后先取得与职责分离相同的锁，再**按此时的数据重新判断**，依次：
  会给某个用户比批准时更多的受保护权限（`CHANGE_GREW`，评审 H1：例如角色在批准后又在审批之外得到了 `*`）——此时只计**在它之前请求**的待审批变更，
  而某个"用户 × 权限"超出本次批准时，若另一个已生效的变更的批准正好包括它（那个变更请求时把本变更算作待审批，所以它的审批人看见了这一项），也不算增长（评审 H1 活性，见 §16.4）；
  重设凭据则不能有任何增长；审批人（取自本请求在 `sys_approval_decision` 中的批准，不取事件中的值，评审 L2）
  是申请人、所针对的用户、已退役的首个管理员，或此时不再持有 `security.protected-change.decide`（D51）、层级的权限与上述覆盖（`APPROVER_NOT_ALLOWED`，评审 H2 复核）；获得权限的用户的凭据
  不是本人的（`TARGET_CREDENTIALS_NOT_OWN`，评审 M3，见下）；目标已存在或已不在（`ALREADY_ASSIGNED`、`ALREADY_GRANTED`、`IDENTITY_IN_USE`、`USER_NOT_FOUND`；
  二次验证已不是批准时的那一个 `MFA_CHANGED`，未绑定 `MFA_NOT_ENROLLED`）——任何一项都记 `FAILED`（失败原因），不写入。
  都满足且 `RequireApproval` 对同一内容答 `APPROVED` 才以 `ProtectedChangeGrant`（只有本包能生成，绑定实体与操作）写入。生效时间已过的按批准时生效。
  授予（分配、角色权限）同时**吊销获得者的全部会话**（原因 `PROTECTED_GRANT`），重设密码同时吊销该用户的全部会话（同 §2），更改邮箱同时向**旧地址**发一封通知
  （`jabiz.auth.email-changed`）。驳回按请求的记录判断（请求状态为驳回、内容哈希相同、驳回人取自 `sys_approval_decision`，不取事件中的值，评审 L-d）后记 `REJECTED`。
  事件重复投递时第二次什么也不做。
  另一个已生效变更的批准只在它写入的分配或授权**仍然有效**、数据期限**至少同样宽**、且生效**不晚于**本变更时才算覆盖（评审 L-c）。
- **获得者的凭据须是自己的**（评审 M3 及其复核）：否则管理员甲可以先建（或重设）用户 U、知道 U 的密码，再申请给 U 管理员的权限，管理员乙批准后甲就控制了第二个管理员；
  只看"最后一次是谁改的"不够——甲以 U 的身份登录、"本人"改一次密码即可。平台按账号的全部历史、按操作顺序判断一条**所有权链**（`CredentialChain`，不另存）：
  一个凭据只有在建立时**没有别人可能知道一个可用的凭据**才是本人的。
  - **密码**是本人的：来自自助注册或经提供方开户；或账号"干净"时由本人修改（`SEC_ACCOUNT_PASSWORD_CHANGE`，原密码本人的）；或由寄到**本人的邮箱**的一次性链接设置
    （找回密码、加入、验证可预占账号时设新密码、接受邀请）。其他一律不是：管理员建立或设置的密码（`SEC_USER_CREATE`、`SEC_USER_SET_PASSWORD`、批准后生效的重设、首个管理员），
    以及由这样的密码"本人"改出来的密码——知道旧密码的管理员同样能改它——直到经邮件链接重设为止。
  - **邮箱**是本人的：注册时的地址；或账号干净时由本人设置。管理员建立账号时填写的地址在一次邮件链接证明它之后算本人的——此后的一切取决于谁读那个邮箱，
    审批人在事实中看得见这个地址，与自助注册的账号相同；管理员**之后改过**的地址不是（这正是接管账号的方式），它被验证（因而能收到找回密码的链接）时账号不干净。
  - **外部关联**本人的：经提供方开户，或账号干净时本人关联；**二次验证**本人的：账号干净时本人设置其密钥——按最近一个改变密钥或确认状态的版本判断
    （重新开始绑定会在同一行写入新的密钥，评审 L-a；只确认不能使别人看见过的密钥变成本人的），只消耗恢复码的版本（`SPONSOR_MFA_VERIFY`）不算。
  - 同一行的版本须与其操作的先后一致；两个事务提交顺序与操作顺序相反时，什么也不算本人的（评审 L-b）。
  - **干净**：密码不是别人的、每个外部关联都是本人的、没有已验证的别人设置的邮箱；凭据是本人的 = 干净且每个二次验证都是本人的。没有任何凭据的账号（没有人能以它登录）是本人的。
  - 因此管理员建立的账号要得到受保护的权限，须先由本人经邮件设定密码——接受邀请（§15.14，D63）或找回密码（或在没有任何凭据时授权，之后的重设密码本身要审批，§16.1）；自助注册与经提供方开户的账号照常。
  - **邮箱不能被换掉**（评审 M-b）：持有受保护权限、或有待审批的变更将给他受保护权限的用户，其邮箱只经 `EMAIL` 变更（审批人须覆盖他持有的受保护权限）；
    直接写入（数据视图、实体 API 等）一律 422 `PROTECTED_CHANGE_REQUIRES_APPROVAL`。其他用户的邮箱照旧直接修改（`SEC_USER_EMAIL_SET` 当即生效）。
    别人在建立账号**之后**改过的地址，在别人可能知道该账号的登录方式（账号不干净）时不能由已登录的用户申请验证（422 `EMAIL_SET_BY_ANOTHER`）：
    申请的人可能不是用户，验证链接会寄到改地址的人选的邮箱；建立账号时填写的地址照旧由本人登录后验证。
  - **剩余风险**：平台判断不了谁读一个邮箱、谁拿着一部手机或提供方的账号——能控制用户邮箱（邮件服务器的管理员）、设备或提供方账号的管理员仍能冒充用户。
    这一点只能靠检测性控制：审批人核对事实中的地址与历史，访问审查（§13.3）、审计（21 §1）与登录记录（§3）事后发现。
  - 审批的事实随请求写入审批记录并逐行封存（21 §2）：其中用户过去的地址与历史因此**永久留存**，不随用户的删除或保留期而去（21 §3）。
- **并发**（评审 M2）：判断授权的每一处（请求流程、写入检查、审批、生效）先取职责分离的锁（`jabiz.sod`，事务级咨询锁），再读数据：
  两个同时进行的权限变更——包括撤销与取消预定，它们不经过引用检查——不能各自通过检查。职责分离的写入检查同样移到这个必经之处，撤销与取消预定也受它约束（以前不受）。
- **其他写入途径一律拒绝**（`ProtectedGrantWriteCheck`，在 `VersionAppender` 中，即一切时态写入的必经之处，18 §3.5 的受控参数同理）：使 `SecUserRole`、`SecRolePermission`、
  他人的 `SecUserIdentity` "活过来"的写入——插入（含预定）、撤销其删除、取消其预定的删除——若造成上述获得，没有许可即 422 `PROTECTED_CHANGE_REQUIRES_APPROVAL`
  （参数 `process`：应当使用的请求流程）。数据视图 API、实体 API、通用实体流程、导入、业务流程的 `ChangeSet`、撤销都经过这里。
  密码哈希与二次验证只有平台流程能写（敏感字段、只读数据视图），所以那两种在 `SEC_USER_SET_PASSWORD` / `SEC_MFA_RESET` 中检查；应用自己的流程写这两者属于应用代码的责任（同 D11）。

### 16.3 建立期、首个管理员与开关

- **管理员**（本节所说）：经启用的角色持有 `*` **或管理员的权限之一**的启用用户（D51）。管理员的权限为 `jabiz.security.protected-grants.administrator-permissions`
  （逗号分隔，缺省 `security.user-role.write`、`security.role.write`——能改权限的人；`*` 总是算；某项不受保护时启动警告）；用户、分配、角色、授权各自在现在或之后某个预定版本中有效即算（评审 L1：一年后才生效的删除不会让人现在就不算管理员）。**首个管理员**：`SEC_BOOTSTRAP_ADMIN`（§7）建立的**每一个**用户（按数据判断，与配置是否仍在无关；通常只有一个——删除了全部用户与角色 `ADMIN` 后启动会再建一个，
  它同样是首个管理员，不算普通管理员，评审 L2 复核）。
- **建立期**：新装的系统只有首个管理员，没有第二个人可以审批。在**首个管理员之外有两位管理员**之前，受保护的变更当即生效（`APPLIED`），开发、端到端测试、示范与快速开始照旧可用。
  首次出现这种情况时（在之后第一次写安全实体或检查受保护的变更时）平台在只追加表 `sec_protected_grants_armed` 中记一行，**从此不再回到建立期**：之后停用或撤下管理员也不能重新打开它。
  `jabiz.security.protected-grants.always=true` 则没有建立期。用细分权限管理的部署同样在出现两位持有管理员权限的用户时结束建立期（D51；16u 之前只按 `*` 计，见 ROADMAP 16u 的升级说明）。
- **首个管理员退役**：存在**另一位管理员**时，首个管理员不能再提出、直接写入或审批受保护的变更（403 `BOOTSTRAP_ADMIN_RETIRED`），其他操作照旧。
  首次出现时记入只追加表 `sec_bootstrap_retired`，**从此永久退役**（评审 L1）：停用其他所有管理员也不能让它复职；这一行使所有首个管理员退役，包括之后再建的。
  建立期结束后（或 `always=true` 时）再建首个管理员会被拒绝（平台不再无人审批地给出 `*`）：启动记一条错误日志说明原因与出路（从备份恢复管理员，或以 `enabled=false` 启动一次），
  而不是启动失败。
- **`always=true` 的首次启动**（评审 M1）：没有建立期时也没有人能批准第一位管理员，因此 `SEC_BOOTSTRAP_ADMIN` 的写入带平台的许可 `BootstrapGrant`，
  只在该流程自己的、以系统身份运行的操作中，且库中没有其他操作建立的用户时有效。
  建立的顺序因此是：首个管理员建第二位管理员 → 第二位建第三位（仍在建立期）→ 此后的受保护变更由一位申请、另一位审批。
- **开关**：`jabiz.security.protected-grants.enabled`（缺省 `true`）。`false` 时与 16r 之前完全相同（仅用于明确不需要四眼的部署）。
  建立期已结束（有记录）而以 `false` 启动时，启动记警告日志并运行一次内部流程 `SEC_PROTECTED_GRANTS_OFF`（系统身份，不写数据），在操作记录中留下关闭的时间（评审 L2）。
- **启动检查**（`SECURITY`，警告，评审 L2）：入口的注册角色（自助注册与 OIDC 开户授予的）持有受保护权限。
  只剩一位管理员又需要授权时（其他管理员离职），这是唯一的出路：由运维修改配置并重新部署（改配置本身留在部署的审计中），之后改回。
- 缺省开启的理由：没有建立期的方案会使单管理员的安装无法建出第二位管理员；缺省关闭则多管理员的生产部署在不知情时没有保护。建立期只在只有一个人能做事的时候存在，
  一旦有了两个人就关上，而且关上后不会因为数据变化重新打开。

### 16.4 已知限制

- （16u 之前：管理员只按 `*` 计，完全用细分权限管理的部署不会自动结束建立期。D51 起按可配置的管理员权限计。）
- 细分权限的部署须让安全管理员持有 `security.protected-change.decide` 才能审批：建立期结束后给出它本身要审批，所以在建立期内（或升级前）给好。
- 建立期内第二、第三位管理员由前一位独自建立：谁是最初的管理员需要在访问审查（§13.3）中确认。
- 审批时按访问令牌判断审批人（D12）；生效时按库中当时的持有再判断一次，被收回权限的审批人的批准因此不会生效（`APPROVER_NOT_ALLOWED`）。
- 长期不处理的待审批变更一直算作"可能持有"，只会使相关的授权也需要审批（更严，不会更松）；需要时由审批人驳回。
- 待审批的变更之间（评审 H1 活性）：生效时只计在它之前请求的待审批变更；之后请求的变更在请求时已把它算作待审批，它们的审批覆盖它给它们的用户的部分，
  所以两个相关的变更按任一顺序批准都会各自生效。对 H1 仍然安全：给某个用户的每一项受保护权限，要么在本次批准中，要么在另一个已生效变更的批准中（审批人看见了它）。
  只有审批之外的增长（例如四眼关闭期间或运维直接写库）才使变更 `CHANGE_GREW`。
- 注册角色上有待审批的受保护权限时，在它被决定之前自助注册与 OIDC 开户会被拒绝（422：把注册角色给新用户就会给出这项待审批的权限）；尽快批准或驳回。
- 申请人不能撤回请求（由审批人驳回）；以后需要时加撤回流程。
- 可预占的账号（§15.7）被接管时删除其二次验证与外部关联不经审批：接管者证明了邮箱，这是自助注册的规则；管理员不应给可预占的账号受保护的权限。
- 自助注册与 OIDC 开户授予的注册角色若含受保护权限，建立期结束后注册会被拒绝（422）：注册角色不应含这类权限。

### 16.5 测试

`ProtectedGrantIT`（分配、角色增加权限、重设密码、重置二次验证、关联外部账号各自待审批直到批准；申请人、所针对的用户、持有不足的管理员与能重设密码的人不能批准；
审批需要二次验证；驳回无效果；角色在请求之后增长时两个审批按任一顺序各自生效、在审批之外增长时 `CHANGE_GREW`、之后请求的分配不使之前待审批的权限失败、
待审批的权限使分配同样待审批；凭据由别人设置时、管理员以用户身份"本人"改过密码时 `TARGET_CREDENTIALS_NOT_OWN`（事实中有全部历史）；
指向别的请求的事件不生效、审批人取自本请求；审批人在生效前失去 `security.protected-change.decide` 时 `APPROVER_NOT_ALLOWED`、`approval.decide` 单独不能审批（D51）；
同一授权请求两次时第二次 `ALREADY_ASSIGNED`；数据期限与生效时间的往返；审批人在生效前失去权限时 `APPROVER_NOT_ALLOWED`；外部账号被抢先关联时 `IDENTITY_IN_USE`；
数据视图、实体 API、`ADD_ENTITY`（只经实体 API）、业务流程的同一变更集（两种顺序）、预定的分配、导入、撤销删除、取消预定的删除都被拒绝；
请求与撤销在另一个权限变更未提交时等待并看到它；建立期记录不能删除；变更只经流程；应用流程是内部的；不受保护的授权与之前相同；已持有不算获得）、
`ProtectedGrantIT` 另有（评审 verify3）：持有人的邮箱只经审批更改并通知旧地址、待审批授权的用户的邮箱同样受保护、所覆盖的授权已撤销时 `CHANGE_GREW`、
伪造的驳回事件不生效；`ProtectedGrantCredentialsIT`（自助注册并本人改过的密码生效并结束会话；管理员填写的邮箱经找回密码证明后生效、之前不生效；管理员改过的邮箱上的找回密码不生效；
以恢复码登录后二次验证仍是本人的；别人改过的地址在账号不干净时不能申请验证；事实中的地址与可信域标记）、`ProtectedGrantRebootstrapIT`（再建的首个管理员同样退役）、`ProtectedGrantAlwaysRebootstrapIT`（`always=true` 时不再建首个管理员并记错误日志）、
`ProtectedGrantFineAdminsIT`（D51：持有 `security.user-role.write` 等的安全管理员使首个管理员退役并结束建立期；以 `security.protected-change.decide` 审批另一位申请的授权；
申请人、目标、只有 `approval.decide`、没有二次验证的都不能；覆盖照旧，含生效时；给出 `security.protected-change.decide` 本身要审批）、
`ProtectedGrantSetupIT`（首个管理员在建立期内直接授权、有了另一位管理员后永久退役而其他操作照旧、预定一年后删除的管理员现在仍算、第三位之后需要审批、
停用管理员不重开建立期也不让首个管理员复职）、`ProtectedGrantAlwaysIT`（`always=true` 的首次启动与之后的审批）、`ProtectedGrantsOffIT`（关闭时与之前相同；
建立期结束后关闭留有操作记录）；`SodIT`（撤销与取消预定同样受职责分离约束）；core `ProtectedPermissionsTest`、runtime `ApproverCoverageTest`（含生效时的 `mayDecide`）、`CredentialChainTest`（所有权链）、`PublishEventReservedTest`（`jabiz.*` 事件只由平台发出）。

## 17. 账号删除与个人信息抹除【决策 D62，阶段 17i】

两家应用商店要求 App 内能删除账号，并有一个不装 App 也能申请的网页。删除分三段：**申请**（本人在 App 中或凭邮件确认的网页、管理员代为安排，冷静期，17.5）、
**抹除**（`ACCOUNT_ERASE`，普通事务）与**清除**（`ERASURE_PURGE`，维护连接上的独立事务）。抹除与清除在 17i-a 实施，申请在 17i-b。

### 17.1 抹除：`ACCOUNT_ERASE`

`internal()`（权限 `security.user.erase`，只由系统运行；到期的申请由定时任务 `jabiz.account-deletion` 以子流程运行，17.5），输入用户编号与安排人：

1. 用户已抹除即 422 `ACCOUNT_ERASED`；用户受法律保全或在保留期内（`DeletionGuard`）即拒绝（`LEGAL_HOLD` / `RETENTION_ACTIVE`），申请等待。
2. 以新版本停用用户、记下 `erasedAt`、清空显示名、邮箱、已验证与注册的邮箱、语言与密码（同一邮箱可立即重新注册）；用户名不可改，由清除覆盖为 `erased-<编号>`。
3. 墓碑外部账号的关联、二次验证、角色分配与收件偏好；删除用户当前地址的邮件退订（普通表；只在该地址已验证时：任何人都能用别人的地址注册再删除账号）；结束全部会话（原因 `ERASED`）。
4. 按声明的顺序运行应用的 `PersonalDataEraser`：`delete` 的行墓碑（普通实体删除），`clear` 的行清空所列字段，`keep` 的行此时不动。
   读取经实体的缺省数据视图，以系统身份，不受数据范围限制；一类行超过 10 万即拒绝（不只处理一部分）。删除照常经过引用检查与保全、保留检查。
5. 写 `sys_erasure`（只追加：抹除编号、用户、时间、安排人、操作），发布事件 `jabiz.account.erased`（`erasureId`、`userId`）。

### 17.2 清除：`ERASURE_PURGE`

`jabiz.account.erased` 的消费者（`jabiz.erasure-purge`）运行 `ERASURE_PURGE`（`internal()`），失败随事件重试；已清除的抹除什么都不做。

- **维护连接**：`jabiz.erasure.datasource.url`（R2DBC，应用的库与 schema）、`.username`、`.password`，只来自环境变量（`JABIZ_ERASURE_DATASOURCE_*`）。
  这个登录属于运维建立的角色 `jabiz_maintenance`，并能读写应用的表；应用自己的登录不属于它（D5 第 4 条）。没有配置时清除 422 `ERASURE_PURGE_UNAVAILABLE`，抹除照常完成。
- 事务以空的上下文开在维护连接上（与运行它的操作无关），`set_config('jabiz.maintenance_mode', 'purge', true)`；先确认连接看得到这次抹除（URL 的 schema 不对即失败，不会"什么都没找到"就算完成），
  固定封存与校验所用的会话设置（时区 UTC 等，行的文本因而相同），找出用户的地址、名字、行与操作，再取封存的事务锁（21 §2.5）后逐表覆盖。
- **地址与名字只算这个用户的**：另一个用户有过或正在用的地址与名字不在其内，别人的邮件、登录记录不会被覆盖。
- **重试**：事件的投递用尽后，定时任务 `jabiz.erasure-purge`（`jabiz.erasure.purge.cron`，缺省每天 03:23 UTC）对尚未清除的抹除（模板 `jabiz.erasure_pending`，实体 `Erasure`、`ErasurePurge`，只读，`audit.read`）逐项再运行。
- 覆盖的范围（可空的置空，不可空的文本 `***`、JSON `{"erased": true}`，二进制为空）：

| 表 | 哪些行 | 覆盖的列 |
|---|---|---|
| `sec_user_version` | 用户的全部版本 | 用户名（`erased-<编号>`）、显示名、邮箱、语言、密码、已验证与注册的邮箱 |
| `sec_user_identity_version`、`sec_user_mfa_version` | 用户的 | 主体、邮箱；密钥、恢复码 |
| `sec_login_record_version`、`sec_refresh_token` | 用户的 | 用户名副本、地址、浏览器 |
| `sec_protected_change_version` | 针对用户的 | 邮箱、主体、密码 |
| `sys_mail_message`、`sys_mail_token` | 用户的，或不属于任何用户、寄往用户地址的 | 地址、参数 |
| `sys_notification` | 发给用户的 | 地址、标题、正文 |
| `sys_document_delivery` | 寄往用户地址的 | 地址、标题、正文 |
| `sys_external_job`、`sys_external_job_outcome` | 用户请求的 | 输入、结果 |
| `op_process`、`op_process_result` | 用户运行的、写过用户的行的、匿名而输入以 `userName` 或 `email` 指名用户的（登录、注册与找回密码，不论成败；抹除与清除自己除外） | 输入摘要、输出 |
| `sys_audit_record` | 用户、其关联、登录记录、二次验证、针对用户的受保护变更与应用声明实体的行的；用户地址的邮件退订的（按记录中的地址找到，退订已在抹除中删除） | 所列字段的前后值改为 `***`（原来为空的仍为空） |
| 应用声明的实体 | 用户字段指向用户的全部版本 | 声明的字段 |
| `sys_document_run` | 应用声明的版式、以用户的行为对象，且不在保留期内、不受保全的 | PDF、内容、参数、收件人（编号与标题保留） |

- 保留：主键、用户编号、金额、编号、时间。账本行只覆盖应用声明的字段。普通表（文件的原名等）不经清除：要去掉用户上传的文件，应用的抹除声明删除引用它们的行，孤立文件随之由清理删除。保留期内的单据原样保留，到期后的清除不在 17i 范围（21 §3.4 的到期报告列出它们）。
- 每改一行写一条 `sys_erasure_item`（21 §2.5），最后写 `sys_erasure_purge`（行数、时间、操作，以及以封存密钥对全部条目的签名）。第三方（Stripe、邮件服务商、Shopify）处的副本不在平台范围内。

### 17.3 应用的声明：`PersonalDataEraser`

core `com.jabiz.security.erasure`，同步、声明式：

```java
@Component
class CustomerPersonalData implements PersonalDataEraser {
    public List<ErasedEntity> entities() {
        // 订单引用客户，客户保留，名字在每个版本中覆盖
        return List.of(ErasedEntity.keep("Customer", "userId", "customerName"));
    }
    public List<ErasureLoad> loads() { return List.of(ErasureLoad.of("customers", CUSTOMER_DATASET, "userId")); }
    public List<ErasureNotice> notices(ErasureSubject subject) {   // 申请前提示（17i-b）
        return subject.rows("customers").isEmpty() ? List.of() : List.of(ErasureNotice.of("commerce.orders-kept"));
    }
}
```

- `ErasedEntity.delete(实体, 用户字段, 个人字段…)`、`clear(…)`、`keep(…)`，`.documents(版式…)`；提示的文案在消息 `erasure.notice.<代码>`。
- 启动检查（类别 `ERASURE`）：实体只声明一次且存在；用户字段与个人字段是它的字段，个人字段不是主键；`clear` 的字段可空且可改；
  不可空的个人字段须是能写下 `***` 的文本（或 JSON）；版式存在。

### 17.4 测试

`ErasureIT`（注册、登录、成为客户、封存、抹除：停用、清空、会话结束、不能再登录；清除后在当前 schema 的全部文本与 JSON 列中找不到用户名、邮箱、显示名与客户名；
校验完好且有 `ERASED`，绕过守卫再改一行即 `MODIFIED`，再插入连到它的伪造条目仍是 `MODIFIED`；再次清除什么都不做，再次抹除 `ACCOUNT_ERASED`，同一邮箱可重新注册；法律保全时拒绝；启动检查的各种错误）、
`ErasureWithoutMaintenanceIT`（没有维护连接时抹除完成、清除 `ERASURE_PURGE_UNAVAILABLE`、历史仍在）、core `ErasedEntityTest`。
`AccountDeletionIT`（17.5：门户中申请，身份确认、提示、冷静期、到期由定时任务抹除并清除；撤回后不执行；凭邮件链接申请，未验证的地址收不到链接，提示未确认时链接仍有效；
管理员安排与撤回，受保护权限的持有人被拒；法律保全时申请保持待执行；没有维护连接时启动失败、加载声明有误的启动检查）、`SignInEntriesTest`（`admin` 与接受 `*` 的入口不能开启）。

### 17.5 申请：冷静期与定时任务【阶段 17i-b】

入口开启 `jabiz.security.entries.<名>.account-deletion` 后（`admin` 与接受 `*` 的入口不能开启），该入口的用户可以申请删除自己的账号。申请记在只追加的
`sys_account_deletion`（申请编号、用户、时间、到期时间、途径 `APP` / `WEB` / `ADMIN`、申请人、操作），结束记在 `sys_account_deletion_end`
（`CANCELLED` 或 `ERASED` 与抹除编号）；没有结束的申请即待执行。两张表只有编号与时间，不含个人信息。

| 途径 | 接口与流程 | 说明 |
|---|---|---|
| 本人，已登录（`APP`） | `GET /api/auth/account/deletion`（状态与提示）、`POST /api/auth/account/deletion`（`SEC_ACCOUNT_DELETION_REQUEST`）、`POST …/cancel`（`SEC_ACCOUNT_DELETION_CANCEL`） | 申请前再次确认身份：近期的二次验证（`jabiz.security.mfa.step-up-max-age`），或当前密码（`SEC_ACCOUNT_REAUTHENTICATE`，错误的密码记入登录记录并计入锁定，同关联外部账号，`Reauthentication`）；撤回不需要 |
| 本人，不装 App（`WEB`） | `POST /api/auth/account-deletion`（`SPONSOR_ACCOUNT_DELETION_MAIL`）、`POST /api/auth/account-deletion/confirm`（`SPONSOR_ACCOUNT_DELETION_CONFIRM`） | 只填入口与邮箱，任何地址都答 202；只有启用的、地址已验证的用户收到一次性链接（`jabiz.auth.account-deletion`，令牌 `jabiz.accountDeletion`，`jabiz.security.account-deletion.link-ttl` 缺省 1 小时，链接到 `{entryUrl}auth/account-deletion`）；用链接即申请 |
| 管理员（`ADMIN`） | `SEC_USER_DELETION_SCHEDULE` / `SEC_USER_DELETION_CANCEL`（`security.user.erase`，`SecUser` 的行操作） | 不看提示；记下由谁安排 |
| 执行 | 定时任务 `jabiz.account-deletion`（`jabiz.security.account-deletion.cron`，缺省每天 03:41 UTC，模板 `jabiz.account_deletion_due`）逐项运行 `SEC_ACCOUNT_DELETION_EXECUTE`（`internal()`） | 到期且未结束的申请：以子流程运行 `ACCOUNT_ERASE`，记 `ERASED`；用户已被别的途径抹除时记那次抹除 |

- **冷静期** `jabiz.security.account-deletion.cooling-off`（缺省 `P14D`）：到期时间 = 申请时间 + 冷静期。期间账号照常可用，本人登录后撤回（不论哪种途径的申请），管理员也可撤回。
  申请后寄 `jabiz.auth.account-deletion-requested`（执行日，UTC）；执行后不再寄信（地址已抹除）。
- **提示**：本人申请时须列出已读的提示代码（`acknowledgedNotices`）。应用的提示（`PersonalDataEraser.notices`，按 `loads()` 读数据，`ErasureNotices`）
  有未确认的即 422 `ACCOUNT_DELETION_NOTICES`，其后每条提示是一个违规（代码 `erasure.notice.<代码>`，文字即提示）；邮件链接此时仍然有效（拒绝即回滚，令牌未用）。
- **拒绝**：持有受保护权限（含已安排生效的，同重设凭据，D48）的用户每条途径都 422 `ACCOUNT_DELETION_PROTECTED`，须先经四眼撤销这些权限；已有待执行的申请 422
  `ACCOUNT_DELETION_PENDING`；入口未开启 422 `ACCOUNT_DELETION_UNAVAILABLE`；没有可撤回的申请 422 `ACCOUNT_DELETION_NONE`。
- **法律保全与保留期**：到期时 `ACCOUNT_ERASE` 被 `DeletionGuard` 拒绝，该项失败（记入定时任务的失败记录），申请保持待执行，之后每天再试。
- **一次一件**：同一用户的申请、撤回与执行以命名锁 `jabiz.platform.account-deletion:<用户>` 依次进行；执行在锁内再读一次申请。
- **限流**：已登录按用户；邮件申请按客户端地址与邮箱；用链接按客户端地址（`SignUpRateLimits`）。
- **启动检查**（类别 `ERASURE`）：有入口开启而没有维护连接（`jabiz.erasure.datasource.url`）即错误（`dev` 下为警告）：账号会被抹除而个人信息永不清除；
  应用 `loads()` 的数据视图或字段不存在、同名加载含义不同也报告。
- **前端**：`@jabiz/ui` 的 `AccountDeletion`（账户页：状态、提示、确认身份、申请与撤回）、`AccountDeletionRequest`（不装 App 的网页：填邮箱）与
  `MailLinkPage` 的 `account-deletion` 页（用链接，必要时先确认提示）；示范门户挂在账户页与 `/portal/auth/account-deletion`。

