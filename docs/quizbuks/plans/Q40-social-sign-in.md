# Q40 答题者与商户用 Facebook、LINE 注册与登录（2026-10-27）

需求方（2026-10-27）：给答题者和商户增加 Facebook 和 LINE 的注册和登录；登录页参考淘宝（账号密码为主，下方"其他方式登录"一排图标）；
邮箱验证按方案 A（Facebook、LINE 不担保邮箱时也开户，账号未验证，事后经邮件验证）。商家入口允许未验证的账号登录（需求方 2026-10-27 确认）。

平台部分是 16zk（决策 D60），先合入 `1.2/platform` 并同步到 `1.2/quizbuks`；本阶段只改 QuizBuks 的配置、两个前端的登录页、测试与文档，不加迁移。

## 1. 决定

| # | 决定 |
|---|---|
| D-Q40-1 | **提供方**：Spring profile `social`（`application-social.yml`，代替 `google`）同时声明 Google、Facebook、LINE——Spring 把两个 profile 中的同一列表整体替换，不能各用一个 profile 叠加（实施时发现，取代原计划的 `facebook` / `line` 两个 profile）。`app` 与 `sponsor` 两个入口的回调（`QB_OIDC_REDIRECT_APP` / `QB_OIDC_REDIRECT_SPONSOR`，三者共用），管理后台没有回调。客户端编号与密钥只来自环境变量：`JABIZ_OIDC_FACEBOOK_CLIENT_ID|SECRET`、`JABIZ_OIDC_LINE_CLIENT_ID|SECRET`（LINE 的 Channel secret 至少 32 字节，平台启动检查）。`scopes`：Facebook `openid email public_profile`，LINE `openid profile email`（LINE 渠道须先获准取得邮箱） |
| D-Q40-2 | **开户**：两者都 `auto-provision: true` 与 `sign-up-unverified-email: true`（D60 第 3 条）：邮箱未被使用即开户，账号未验证、可预占，寄出验证邮件；邮箱已被使用 403 `OIDC_ACCOUNT_EXISTS`（用原来的方式登录后在账户页关联）；令牌没有邮箱（用户拒绝给邮箱、LINE 未获准）不开户，登录页说明"请用邮箱注册或换一种方式" |
| D-Q40-3 | **商家入口允许未验证的账号登录**：`entries.sponsor.require-verified-email: false`（取代 Q2 的"商家后台只接受已验证的邮箱"）。理由：经 Facebook、LINE 开的商家账号没有密码，只有在自己的会话中才能验证而保留账号（D60 第 4 条）。安全不变：QuizBuks 的每个写操作都要求已验证的邮箱；商家后台对未验证的账号显示平台的 `VerifyEmailBanner`（重发验证邮件），入驻申请等页面在提交被拒（403 `EMAIL_NOT_VERIFIED`）时说明先验证 |
| D-Q40-4 | **守护测试**：新增 `VerifiedEmailWritesIT`：遍历全部流程与数据视图，凡是 `QB_SPONSOR` 或 `QB_TAKER` 的权限能运行的非内部流程、能写的数据视图，都必须声明 `requiresVerifiedEmail()` / `policy(p -> p.requiresVerifiedEmail())`；例外逐个列出并写明原因（实施时只有 `QB_ATTEMPT_RESULT`：只读本人一次答题的结果）。以后新加的写操作漏掉即失败 |
| D-Q40-5 | **登录页（参考淘宝）**：App（`/taker/`）与商家后台（`/sponsor/`）的登录页以 `SignInForm` 的 `oidcLayout="icons"`、注册页在表单下以 `OidcButtons layout="icons"`（平台的 `SignUpForm` 不带提供方；提供方的首次登录即开户）显示 Google、Facebook、LINE 的圆形图标（品牌色，D44），在"其他方式登录"下；账号密码与邮件链接（App）在上，不变。只显示服务器为该入口列出的提供方 |
| D-Q40-6 | **账户页**：两个前端的账户页已有 `LinkedIdentities`，新提供方自动出现，不改代码；只有经身份提供方开户、没有密码的账号在账户页看到"设置密码"走找回密码（平台已有） |
| D-Q40-7 | **验证邮件的打开方式**：验证页（平台 `MailLinkPage`）在已登录的浏览器中直接验证并保留账号；在别的浏览器打开时提示"在已登录的浏览器中打开此链接"（16zk）。App 是 PWA：主屏幕 App 与浏览器不共享会话，提示文案同上；邮件正文不改 |
| D-Q40-8 | **部署**：`deploy/quizbuks` 的 `.env.example` 与 README 加上述变量与在 Meta for Developers、LINE Developers 中登记回调的步骤；上线检查清单加"用真实的 Facebook、LINE 应用各走一遍注册、登录、验证"（D60 剩余风险） |

## 2. 测试

- `SocialProfileIT`：开了 profile `social`，`app` 与 `sponsor` 两个入口按序列出 Google、Facebook、LINE（`kind` 与标签正确），`admin` 不列出。LINE 密钥过短由平台 `OidcProvidersTest` 覆盖。
- `VerifiedEmailWritesIT`（D-Q40-4）。
- `SocialSignUpIT`（代替原计划的 e2e：LINE 的存根是 Java 的 `TestOidcProvider`，在集成测试中跑真实的回调）：App 与商家后台各经 LINE 开户（未验证、可预占、入口的角色、关联）→ 已登录但写入 403 `EMAIL_NOT_VERIFIED` → 验证邮件 → 在本人会话中验证（不要密码，关联保留）→ 刷新后保存资料 / 申请入驻成功；邮箱已被使用 403 `OIDC_ACCOUNT_EXISTS`、不建关联。
- `AccountSignUpIT`：商户注册后未验证即可登录，申请入驻 403，验证后同一会话刷新即可申请。
- `QuizbuksAppIT.servesTheThreeSignInEntries`：商家入口 `requireVerifiedEmail` 改为 `false`。
- 商家后台 `App.test.tsx`：未验证的商户登录后看到验证横幅（入驻申请被拒时照常显示服务器的说明）。
- 两个前端 `App.test.tsx`：服务器列出三个提供方时登录页与注册页显示三个图标、名称为提供方标签（axe 由平台 `blocks.test.tsx` 覆盖）。
- 平台的 HS256、`client_secret_post`、未担保邮箱开户、本人会话中验证已由 16zk 覆盖，不重复。

## 3. 风险

- Facebook、LINE 的细节未能以官方页面核对（D60），上线前以真实应用核对一次。
- 商家入口放开未验证登录后，未验证的商户能读到自己的空后台；写操作由 D-Q40-4 的守护测试保证都要求已验证。
