# Q30 地址重排：答题人前端 `/taker/`、招商网站 `/biz/`、裸域名转向（计划，2026-10-25 确认）

需求方（2026-10-25）：答题人登录的地址改为 `https://{host}/taker`，招商网站放在 `https://{host}/biz`，纯域名 `https://{host}` 缺省转到 `https://{host}/biz`。
确认的方案：平台先加裸域名转向与"搬走的前缀"（16zf-a），应用再搬前端、加一页招商站、保留旧链接。

## 1. 已确认的决定

| # | 决定 |
|---|---|
| D-Q30-1 | **答题人前端在 `/taker/`**：`jabizApp { spa("/taker", "../../quizbuks-web/app", entry = "app", pwa = Pwa.ON) }`，入口 `app` 的 `app-path: /taker/`（邮件链接、OIDC 回调、退订页随之），PWA 的 manifest 与 Service Worker 在 `/taker/` 之下（作用域 `/taker/`）。商家后台 `/sponsor/`、管理后台 `/admin/` 不变 |
| D-Q30-2 | **招商网站在 `/biz/`**：`quizbuks-web/biz` 是一个只有 HTML 与一份样式表的 Vite 项目（没有脚本、没有 React、不用平台包；`app:check` 只要求 `vite` 同版本，16zf-a），三页三语（`/biz/`、`/biz/zh.html`、`/biz/ja.html`，`hreflang` 互指），链接到 `/taker/`、`/sponsor/` 与 App 的条款与隐私页。平台的缺省内容安全策略。本阶段只有一页；完整的招商站（定价、案例、联系表单）另立计划 |
| D-Q30-3 | **裸域名**：`jabiz.web.root-redirect: /biz/`（可用 `JABIZ_WEB_ROOT_REDIRECT` 改），对恰好 `/` 的 `GET` / `HEAD` 302 到 `/biz/`，查询串保留（16zf-a） |
| D-Q30-4 | **旧链接继续有效**：`jabiz.web.redirects` 把 App 原来在根下的路径（`/p`、`/play`、`/result`、`/redeem`、`/auth`、`/mail`、`/login`、`/signup`、`/password-reset`、`/welcome`、`/wallet`、`/history`、`/account`、`/inbox`、`/legal`、`/help`、`/about`）301 到 `/taker/` 下的同一路径（查询串保留）：印出的二维码、此前寄出的邀请与欢迎邮件、Stripe 的返回地址都仍然可用 |
| D-Q30-5 | **登录链接的返回路径是站点路径**：平台只接受入口 `app-path` 之下的路径，所以 App 的分享页用 `hrefOf(...)` 给出 `/taker/p/<id>`，邀请邮件的 `to` 由 `jabiz.security.entries.app.app-path` 拼出（`InvitationProcesses.returnPath(appPath, id)`），不写死 `/taker` |
| D-Q30-6 | **商家后台生成的 App 链接**（分享链接、二维码、兑奖码）经 `publishing/paths.ts` 的 `APP_BASE = '/taker/'`；只此一处 |

## 2. 部署时要改的

- Google 控制台：已授权的重定向 URI 改为 `https://<主机>/taker/login/oidc`，`QB_OIDC_REDIRECT_APP` 同步；旧地址不再有效。
- `QB_APP_LINK` 改为 `https://<主机>/taker/`（Stripe 开通收款账户后的返回地址）。
- 已把 App 安装到主屏幕的答题人要从 `/taker/` 重新安装（作用域变了；原来的安装打开的是根，根转到招商站）。
- `JABIZ_MAIL_BASE_URL` 仍是主机的根地址，不变。

## 3. 改动

| 部分 | 改动 |
|---|---|
| 平台 | 16zf-a：`jabiz.web.root-redirect`、`jabiz.web.redirects`、`app:check` 对静态站点的规则 |
| 构建与配置 | `build.gradle.kts`（`/taker`、`/biz`）、`application.yml`（`app-path`、`root-redirect`、`redirects`、`web.spa` 清单加 `/biz`） |
| 后端 | `InvitationProcesses.returnPath(appPath, id)`、`InvitationConfig` 注入 `app-path` |
| App | `share/SharePage.tsx`（`to={hrefOf(here)}`）、`vite.config.ts` 与 `package.json` 的说明 |
| 商家后台 | `publishing/paths.ts`（`APP_BASE`） |
| 招商站 | `quizbuks-web/biz/`（`package.json`、`vite.config.ts`、`index.html`、`zh.html`、`ja.html`、`style.css`、`public/icon-192.png`） |
| 测试 | `NotificationMailIT`、`AccountSignUpIT`、`InvitationIT`、`ShareLinkIT`、`GoogleProfileIT`（链接在 `/taker/` 下）；商家后台 `publishing.test.tsx`；e2e 全部 App 路径改为 `/taker/…`，`pwa.spec.ts` 另验证裸域名 302、旧地址 301、`/biz/` 200 |
| 文档 | 本文、`03-plan.md`、`02-design.md` §7.5、`operations.md`、`deploy/quizbuks/README.md`、`DEPLOY.md`、`backend/quizbuks/CLAUDE.md`、`stripe-test-mode.md` |

## 4. 风险

- 招商站的全部页面都在 `/biz/` 之下，根下不再放任何页面：根下只有转向（App 的旧前缀清单固定在 `application.yml`）。`hreflang` 的替代链接写的是站内路径（搜索引擎要求完整地址；换成完整地址要在部署时知道主机名，留给完整的招商站）。
- 旧的 Service Worker（作用域 `/`）在用户的浏览器里会继续控制根下的页面，直到它检查更新时发现 `/sw.js` 不再存在（平台的 Worker 在 404 时不注销）；用户打开 `/taker/` 时由新的 Worker 接管，根的旧 Worker 只影响 `/biz/`：首次访问若被旧 Worker 的外壳接住，刷新一次即可。观察期内如有报告，再在根下放一个 `Pwa.REMOVE` 的 Worker。
