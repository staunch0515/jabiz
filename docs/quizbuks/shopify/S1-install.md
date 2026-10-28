# S1：Shopify 的安装、登录与内嵌后台（实施说明）

计划已由需求方确认（2026-10-27，"17b + S1 修订计划"及两个补充问题：安装后第一个打开应用的人成为主管理员；所有成员都可设置，只有转交管理员与断开连接限主管理员）。
平台能力来自 17b（决策 D59）与 17a（D57）。本文记下确认的计划之外、实现时定下的细节；与计划不同之处单独标出。

## 1. 模块

- `backend/quizbuks-shopify/`：Java 库模块（依赖 `:runtime`），包 `com.jabiz.quizbuks.shopify`，由 `:quizbuks` 引入同一个 jar（同一个 `QuizbuksApp` 扫描到它）。
  **不依赖引擎模块**（`:quizbuks`）：S2 起以流程名调用引擎的流程；不碰钱包与普通商户表由编译期依赖本身保证。
  总体方案提到的"独立部署单元"不在 S1：需要时再把它包成自己的 `jabizApp`。
- 迁移在本模块的 `db/migration` 中，与引擎共用同一个版本序列与历史表（`V31__quizbuks_shopify_shops.sql` 起；版本重复时 Flyway 启动即失败）。表前缀 `qbs_`，实体前缀 `Qbs`，流程 `QBS_*`，权限 `qbs.*`。
- SPA `quizbuks-web/shopify`（`/shopify/`，入口 `shopify`，`embedding: shopify`），独立的 pnpm 工程，与另两个 SPA 同样经 `link:` 用平台的包。

## 2. 登录与身份（17b）

- 提供方 `jabiz.security.external-tokens.shopify`：密钥 `SHOPIFY_API_SECRET`、`audience` 为 `SHOPIFY_API_KEY`（都只取自环境变量），租户 `[a-z0-9][a-z0-9-]*\.myshopify\.com`，`auto-provision: true`，`frame-ancestors: https://admin.shopify.com`。
- 入口 `shopify`：接受并注册角色 `QBS_SHOP`（由 `QB_SETUP` 建立，`setup.QbRoles`）；外部令牌即登录方式，不要求邮箱。
- 身份是店铺中的一个店员：`SecUserIdentity(ext:shopify, <店铺>/<店员编号>)`；同一个人在两家店是两个用户。

## 3. 商户与成员

- `QbsShop`（普通表）：`shopDomain` 唯一、不可变；`status` `ACTIVE` / `UNINSTALLED`；`installedAt`、`uninstalledAt`、`shopName`（连接检查时取得）、`connection` `NONE` / `CONNECTED` / `RECONNECT`。
- `QbsShopMember`（普通表）：店铺、平台用户（唯一：一个用户只属于一家店）、店员编号、`role` `ADMIN` / `MEMBER`、加入时刻。每家店恰好一个 `ADMIN`（部分唯一索引兜底）。
- `QBS_SHOP_JOIN`（每次打开内嵌页时调用，重复无副作用）：`VerifyExternalToken` 核对店员递来的会话令牌属于操作人 → 取锁 `qbs.shop:<店铺>` → 没有店铺即建（`ACTIVE`）、已卸载即恢复为 `ACTIVE`（重装，§5；只认卸载之后签发的令牌：`iat` 晚于卸载时刻，卸载前一刻签发、仍在一分钟有效期内的令牌不算）→ 没有成员关系即加入，店铺没有主管理员时成为 `ADMIN`，否则 `MEMBER`。
- 每家店至多 `ShopProcesses.MAX_MEMBERS`（100）位成员（`QBS_SHOP_FULL`）：卸载、删除等一次写全店的流程都在数据视图的一批之内；Shopify 各套餐的员工账号远少于此。
- `QBS_SHOP_ADMIN_TRANSFER`：主管理员把 `ADMIN` 交给本店另一位成员（同一锁内两行一起改）。
- 读取：`QBS_SHOP_JOIN` 的输出就是页面所示的概况（店铺、连接状态、成员，主管理员在前）。不另写模板：模板中一个实体只能对应一个数据视图，
  "操作人自己的成员关系"与"同店的全部成员"要读同一张表的两种范围，平台的范围也表达不了"与我同店"。全部数据视图只给系统读（`qbs.system`），写入只经流程。

## 4. Shopify 的访问令牌（按需交换）

- 外部服务 `shopify`：`allowed-hosts: "*.myshopify.com"`（17b 的主机模式），密钥 `SHOPIFY_API_KEY` / `SHOPIFY_API_SECRET`。
- `QBS_SHOP_CONNECT`（任何成员）：核对会话令牌 → 只取锁 `qbs.shop-token:<店铺>`（不取店铺锁：每次打开内嵌页的 `QBS_SHOP_JOIN` 都要取它，不能等外部调用；店铺行按读到的版本更新）→ 阻塞步骤以令牌交换（`grant_type=urn:ietf:params:oauth:grant-type:token-exchange`、`requested_token_type=…offline-access-token`、`expiring=1`）取得离线访问令牌与刷新令牌 →
  以 `SealSecret`（上下文为店铺域名）密封后写 `QbsShopToken`（每店一行）→ 以访问令牌查询店铺名（Admin GraphQL `shop { name }`）写 `QbsShop.shopName`，`connection = CONNECTED`。
- 刷新：要用访问令牌的流程（S1 只有连接检查 `QBS_SHOP_CHECK`）在同一锁内，访问令牌 5 分钟内到期即以刷新令牌换新的一对（旧的随之作废，所以只在锁内刷新）；刷新被拒即 `connection = RECONNECT` 并删除令牌，下次有人打开内嵌页时重新交换。
  刷新成功后读店铺名失败，照样保存新的一对（旧的已不能用）；只有刷新之后提交失败才会丢失它（Shopify 的令牌接口不接受幂等键），之后的检查即得到 `RECONNECT`，重新连接即可。
- 外部调用在事务中、锁内同步进行（一次约一秒，只在连接与刷新时发生）：交换出的令牌只在提交后才存在，提交失败时下一次再交换即可。

## 5. Webhook 与生命周期

提供方 `shopify`（17a 的 `body-hmac-sha256`，密钥同为 `SHOPIFY_API_SECRET`，`reject-status: 401`，元数据头 `X-Shopify-Shop-Domain`）。每个消费者（`internal()`）先核对正文中的店铺与元数据头一致，不一致即什么也不做并记录。

| 主题 | 处理 |
|---|---|
| `app/uninstalled` | 店铺与成员行上的店铺状态 `UNINSTALLED`、记卸载时刻；删除访问令牌；成员的会话在下一次刷新时结束（`ShopSignInGuard`）。**数据保留** |
| `customers/data_request`、`customers/redact` | S1 没有顾客数据：登记 `QbsComplianceRequest`（主题、店铺、接收时刻、入站事件、结论 `NO_DATA`） |
| `shop/redact` | 店铺仍是 `UNINSTALLED` 时才删除：本店的令牌、成员、店铺行。店铺已重装（`ACTIVE`）即不删、登记。成员的平台账户不动（见下） |

- **重装**：`shop/redact` 之前重装，下一次有人打开内嵌页时 `QBS_SHOP_JOIN` 把店铺恢复为 `ACTIVE`，成员与主管理员照旧，访问令牌按需重新交换；`shop/redact` 之后重装即新店（同一店员仍以原来的平台账户登录，加入新店，第一位成为主管理员）。
- **与计划不同（平台账户）**：计划 S1-6 写 `shop/redact` 时"停用这些平台账户（解除外部账号关联）"。平台规则是外部账号只经平台的关联与解除（根 `CLAUDE.md`），应用不自己写 `SecUserIdentity`；
  只停用而不解除，重装后的店员又会因账户被停用而进不来。所以 S1 不动平台账户：它们只有用户名 `<店铺>/<店员编号>`、没有邮箱与密码，记录是只追加的（真正抹去要平台的账户删除能力，S1 之外，待需求方决定是否开平台阶段）。
- **与计划不同**：计划 S1-6 写"`SignInGuard` 拒绝已卸载店铺的成员登录"。照写会挡住重装：卸载之后再装，店员打开内嵌页时店铺仍是 `UNINSTALLED`，登录被拒就到不了把店铺恢复为 `ACTIVE` 的 `QBS_SHOP_JOIN`。
  Shopify 只给已安装的应用签发会话令牌，所以能以新令牌登录本身就说明已重装。因此守卫只拒绝**刷新**（`SignInAttempt.Factor.REFRESH`）：已卸载店铺的成员的会话在访问令牌到期时结束（同封禁），新的令牌登录照常放行；
  平台没有给应用直接吊销会话的步骤（`SEC_USER_SESSIONS_REVOKE` 是管理员的、要二次验证）。已卸载店铺的写入流程（转交、检查连接）照样拒绝（`QBS_SHOP_UNINSTALLED`）。

## 6. 前端（`quizbuks-web/shopify`）

- `index.html` 第一个脚本是 Shopify CDN 上的 App Bridge（Shopify 要求同步加载、不能打包）；API key 以 `<meta name="shopify-api-key">` 给出：S1 在构建时取 `VITE_SHOPIFY_API_KEY`，S4a 起改为服务器启动时由 `SHOPIFY_API_KEY` 写入（平台 17g）。
  CSP：`script-src 'self' https://cdn.shopify.com`，`connect-src 'self'`；`frame-ancestors` 由平台按店铺写（`embedding`）。
- 流程：`shopify.idToken()` → `signInWithExternalToken` → `QBS_SHOP_JOIN`（再取一个新令牌）→ 概况页（店铺、状态、成员、连接状态；主管理员可转交；"连接"按钮运行 `QBS_SHOP_CONNECT`）。界面只用 `@jabiz/ui`，Polaris 风格在 S4。
- 已知问题（S4a 已解决，平台 17g）：API key 在构建时写入，staging 与正式各用自己的 Shopify 应用时要各自构建。

## 7. 测试

- 集成测试（真实 PostgreSQL）：登录开户 → 加入（第一个成为管理员，第二个是成员，重复加入无副作用）→ 转交（只有管理员、只给本店成员）→ 连接（本地桩回答令牌交换与 GraphQL，令牌密封存储、读接口不返回）→ 刷新（到期前、被拒即需要重连）
  → 卸载（会话吊销、令牌删除、数据保留）→ 重装（恢复、成员照旧）→ `shop/redact`（删除、账户停用、关联解除；已重装的不删）→ 合规两主题登记；店铺与元数据头不一致的什么也不做；验签失败 401。
- 场景回放 `scenarios/qbs/shopify-privacy.yml`：合规主题、未知店铺的删除、正文与请求头不一致。场景执行器不能以会话令牌登录，所以加入、连接、卸载、重装与删除的整个生命周期在 `ShopifyIT`。
- `QB_SETUP` 多建一个角色使场景的编号序列后移：答题场景中的答案按新的显示位置改写（得分不变），快照随之更新。
- 前端：Vitest（加入与概况、转交、连接的状态），`pnpm app:check`。

## 8. 联调前需要需求方准备

Shopify Partner 账号、开发店铺、应用的 API key 与密钥（环境变量 `SHOPIFY_API_KEY`、`SHOPIFY_API_SECRET`，不进仓库）、公网可达的测试地址（应用地址 `https://<地址>/shopify/`，合规与卸载 webhook 指向 `https://<地址>/api/inbound/shopify`）。
`tools/quizbuks/shopify/shopify.app.toml` 是应用配置的模板（地址与 key 待填）。
