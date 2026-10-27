# Q37 商户用 Google 注册与登录（2026-10-26，需求方要求直接实施）

需求方（2026-10-26）：商户也要和答题者一样，可以用 Google 账户注册。取代 Q2-16 / Q-6 的"社交登录只给 App"。

1.2 已冻结只修 bug；这是需求方要求的上线前改动，只改配置、商家后台的一句提示、测试与文档，不改平台、不加迁移。

## 1. 决定

| # | 决定 |
|---|---|
| D-Q37-1 | **同一个 Google 客户端**：profile `google` 的提供方 `google` 加商家入口的回调 `redirect-uris.sponsor: ${QB_OIDC_REDIRECT_SPONSOR}`（`https://<主机>/sponsor/login/oidc`）。Google 控制台中同一客户端登记两个重定向 URI。管理后台仍没有回调，不提供 Google |
| D-Q37-2 | **开户**：`auto-provision` 照旧（D39）。在商家后台第一次用 Google 登录、邮箱未被使用时，按商家入口的注册角色开户（`QB_SPONSOR`，邮箱由 Google 担保即已验证），随后照常申请入驻（条款在申请时同意，Q7b）。邮箱已被使用即 `OIDC_ACCOUNT_EXISTS`，绝不自动关联 |
| D-Q37-3 | **已是答题人的人**：Google 登录属于账号而不是入口；没有商家角色的账号在商家后台被拒（`NO_ROLE`）。成为商家的路照旧：在商家后台用同一邮箱注册，打开"你已有账号"邮件中的链接（已验证的账号只追加角色，不需要密码，10 §15）；之后在商家后台也能用 Google 或密码登录。商家后台的登录页下方加一句说明（三语 `public.takerAccount`） |
| D-Q37-4 | **前端不需要新页面**：商家后台已有 `/sponsor/login/oidc`（`OidcCallback`）与账户页的"关联账号"（`LinkedIdentities`）；登录页按服务器给该入口列出的提供方显示按钮 |
| D-Q37-5 | **部署**：新环境变量 `QB_OIDC_REDIRECT_SPONSOR`。已开着 Google 的部署升级时必须设它并在控制台登记第二个地址，否则启动失败（缺少占位符） |

## 2. 测试

- `GoogleProfileIT`：开了 profile `google`，`app` 与 `sponsor` 两个入口都列出 Google，`admin` 不列出。
- 商家后台 `App.test.tsx`：服务器为商家入口列出 Google 时登录页显示 Google 按钮；登录页有给答题人的说明。
- 平台的 OIDC 开户、`NO_ROLE`、`OIDC_ACCOUNT_EXISTS` 与"加入"已由平台测试覆盖（16f），不重复。
