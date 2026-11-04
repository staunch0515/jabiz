# Q97 通行密钥（生物识别登录）在 QuizBuks 中开启（2026-11-05 需求方确认）

需求方（2026-11-05）：平台已做好通行密钥（阶段 17zf-a / 17zf-b，决策 D71，设计 10 §15.16），但 QuizBuks 从来没有打开，所以看不到。
**答题者的 App、商家后台、管理后台、运营商后台都开启；商店 App（iOS / Android 的外壳）暂时不做。**

## 1. 决定

| # | 决定 |
|---|---|
| D-Q97-1 | 四个入口 `app`、`sponsor`、`admin`、`operator` 都以 `passkeys` 开启；`shopify`（以 Shopify 的会话令牌登录）不开 |
| D-Q97-2 | **依赖方（`rp-id`）与来源（`origins`）按部署的地址自动得出**：没有显式设置 `jabiz.security.passkeys.rp-id` / `origins`（`QB_PASSKEY_RP_ID` / `QB_PASSKEY_ORIGINS`）时，取 `JABIZ_MAIL_BASE_URL`（每个部署本来就必须设的主机根地址）的主机与来源。staging 即 `staging.<域名>`，正式环境即正式域名：两个环境的通行密钥互不通用（一个环境的通行密钥不会被另一个环境接受）。**`rp-id` 一旦有用户绑定就不能再改**（改了已有的通行密钥全部失效），更换域名前先通知用户 |
| D-Q97-3 | 开关 `QB_PASSKEYS`（缺省开）：地址得不出（没有 `JABIZ_MAIL_BASE_URL` 也没有显式设置）时自动不开，不让启动失败；`QB_PASSKEYS=false` 全部关闭；`QB_PASSKEYS=true` 而得不出地址即启动检查报错（平台 `SECURITY`）。实现为 QuizBuks 的 `EnvironmentPostProcessor`（`identity.PasskeyDefaults`），只补缺省值、不覆盖显式设置 |
| D-Q97-4 | App 与商家后台：登录页 `SignInForm` 加 `passkey`；账户页的安全部分加平台积木 `Passkeys`（入口不收时它自己不显示）；邮件确认页 `auth/passkey-confirm`（`MailLinkPage` 的 `passkey-confirm`：没有密码的人——邮件链接、Google / Facebook / LINE 登录的——绑定前经邮件确认）。管理后台与运营商后台是平台的通用后台，已有这些，只需入口开启 |
| D-Q97-5 | 规则全照平台（D71）：只是已有账号的附加凭据，不能只凭它注册；通行密钥登录算两个因素（要求二次验证的管理员角色可以用它）；QuizBuks 的登录前检查（封禁、运营商成员关系）照样适用 |

## 2. 改动

- `application.yml`：`jabiz.security.passkeys`（`rp-id`、`rp-name` 为品牌名、`origins`，可由环境变量显式给出）与四个入口的 `passkeys: ${QB_PASSKEYS:${quizbuks.passkeys.default:false}}`。
- `identity.PasskeyDefaults`（`META-INF/spring.factories`）：由 `jabiz.mail.base-url` 得出 `rp-id` 与 `origins`，并在得出（或显式设置）时给 `quizbuks.passkeys.default=true`。
- App、商家后台：`App.tsx`（登录页的 `passkey`、邮件页集合加 `/auth/passkey-confirm`）、`qb/AccountSections.tsx`（两边相同）加 `Passkeys`。
- 文档：`deploy/quizbuks/README.md` 的环境变量表、`operations.md`、本计划。

## 3. 测试

- 单元：`PasskeyDefaultsTest`（由根地址得出、带端口、显式设置优先、`QB_PASSKEYS=false`、没有根地址即不开、根地址带路径或不是 http(s) 即不开）。
- IT：`PasskeyQuizbuksIT`（平台的测试认证器 `TestAuthenticator`）：四个入口的 `GET /api/auth/entries/{名}` 说 `passkeys: true`、`shopify` 不开；答题者以当前密码绑定后在 App 入口以通行密钥登录；
  同一个通行密钥不能登录商家后台（角色不对）；被封禁的答题者以通行密钥登录被拒（`QbSignInGuard`）。
- 前端：App 与商家后台的 Vitest（登录页有通行密钥按钮、账户页有通行密钥部分、确认页的路由），lint、类型检查。

## 4. 实施记录（2026-11-05）

- `application.yml`：`jabiz.security.passkeys` 的 `rp-id` / `origins` 写成 `${QB_PASSKEY_RP_ID:${quizbuks.passkeys.rp-id:}}` 这样的两层占位，`rp-name` 缺省为品牌名；四个入口的 `passkeys` 为 `${QB_PASSKEYS:${quizbuks.passkeys.default:false}}`。
- `identity.PasskeyDefaults`（`META-INF/spring.factories`）只补最低优先级的缺省值：根地址必须是 `https://主机[:端口]`（本机可以 `http://localhost`），带路径、查询、用户信息或其他协议的一律不猜，通行密钥即不开。
- App、商家后台：`SignInForm` 加 `passkey`（入口与浏览器都支持时才显示按钮）；`qb/AccountSections.tsx`（两边相同）在二次验证之后加"通行密钥"部分（入口不收时整个部分不显示）；邮件页集合加 `/auth/passkey-confirm`。
- 管理后台与运营商后台：平台的通用后台已有，只靠入口开启。
- 未做：商店 App 的外壳（D-Q97-1，需求方暂缓）。

### 测试

- `PasskeyDefaultsTest`（3）：由根地址得出（含端口、末尾斜杠、大小写、本机 http）、显式设置优先、只给一半即不开、不合格的根地址即不开。
- `PasskeyQuizbuksIT`（3，平台的 `TestAuthenticator`）：四个入口都说 `passkeys: true`、`shopify` 不开、依赖方是根地址的主机；答题者以密码绑定后在 App 以通行密钥登录（登录记录的因素为 `PASSKEY`），在商家后台不行；被封禁的答题者以通行密钥登录被拒 `SIGN_IN_REFUSED`。
  `BanIT`、`SignInAuditIT`、`QuizbuksAppIT` 照旧通过。
- App 与商家后台的 Vitest：入口开启时登录页有通行密钥按钮、账户页有通行密钥部分，不开时没有；确认邮件的页面按邮件页打开并去掉地址中的令牌（App 623、商家后台 687 全过），lint、类型检查。
- e2e 未在本机运行（本机没有 Docker 中的 Mailpit）。e2e 的 `JABIZ_MAIL_BASE_URL` 是 `http://localhost:8080`，所以 e2e 中通行密钥同样开启：登录页多一个按钮，浏览器的自动建议在名字栏中待命；不绑定通行密钥的账号用密码登录不受影响（平台的示范门户的 e2e 即如此）。
