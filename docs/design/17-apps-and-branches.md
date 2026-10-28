# 17 平台与应用：分支、模块、部署

一个仓库里同时有**平台**（可复用的公共部分）和若干**应用**（具体项目，例如 `culture`）。本文件规定二者怎样分开开发、
怎样构建成各自的可部署产物（ROADMAP 阶段 13a），以及平台与应用怎样按版本线协同演进（阶段 13f）。约束性细则见【决策 D19】【决策 D21】。

## 1. 分支模型

平台的每个不兼容版本是一条**版本线**；每条线有一个平台分支，用到这条线的应用各有一个应用分支【决策 D21】：

```
                 1.0/platform ──●──────●(fix)───────────────►      线 1.0
                   │  \ merge    \         \ 向前合并
                   │   1.0/culture ─●───────●───────────────►
                   │   1.0/finance ─●──┐（升级后冻结）
                   │                   │
                   └► 1.1/platform ─────●───●(1.0 的 fix)────►     线 1.1（不兼容的改动，如阶段 14）
                         \ merge        \ merge
                          1.1/finance ◄─┘（从 1.0/finance 拉出，再合并 1.1/platform）
工作分支：
  平台：<线>/phase-<N><x>-<名>   从 <线>/platform 拉出，PR 合回 <线>/platform
  应用：<线>/<应用>-<N>-<名>      从 <线>/<应用> 拉出，PR 合回 <线>/<应用>
```

- **平台分支 `<线>/platform`**：长期分支。内容：`backend/core`、`backend/runtime`、`backend/ext-geo`、`backend/app`（示范应用）、
  `backend/build-logic`、`frontend/`（通用后台）、`spec/`、`docs/design/`、`docs/guide/`、`tools/`（`tools/<应用>/` 除外）、根目录的构建与 CI 文件，
  以及线号文件 `.jabiz-platform-line`。
- **应用分支 `<线>/<应用>`**（如 `1.0/culture`）：只**增加**应用专有目录（第 2 节），不修改平台目录。
- **线内的合并方向只有一个**：`<线>/platform` → `<线>/<应用>`（合并提交，不变基、不拣选）。应用分支从不合回平台。
- 做应用时发现平台缺能力或有缺陷：先在该线的平台工作分支上实现（带平台自己的测试与示范，不提应用），合入 `<线>/platform`，再合并进应用分支。
- **开新线**只为不兼容的改动：从上一条线的平台分支拉出 `<新线>/platform`，第一个提交把 `.jabiz-platform-line` 改为新线号。兼容的新增与修复留在当前线。
- **修复向前合并**：修复做在最旧的受影响线上，再合并到更新的线（`1.0/platform` → `1.1/platform`），各线再合并到自己的应用。
- **应用升级**：`<新线>/<应用>` 从 `<旧线>/<应用>` 拉出，合并 `<新线>/platform` 并适配；此后应用只在新线上开发，旧线上的应用分支冻结。
- **迁移**：不是最新的线不增加迁移（平台与应用都不加），需要改表结构的修复只做在最新线上。
- **发布**：标签 `platform-v<主>.<次>.<修订>` 打在对应线的平台分支上。
- 各项操作的命令见 `docs/guide/version-lines.md`。
- 过渡：原有的 `platform`、`culture`、`finance` 即线 1.0（由它们建立 `1.0/*`），之后不再使用；`main`、`alpha` 与 `phase-*-baseline` 保持现状，不再作为开发基线。

## 2. 应用专有目录与检查

应用分支在仓库根目录放一个 `.jabiz-app-paths`，列出它拥有的路径（glob），例如 culture：

```
.jabiz-app-paths
backend/culture/**
site/**
docs/culture/**
deploy/culture/**
tools/culture/**
.github/workflows/culture.yml
```

- 平台提供 `tools/check-app-paths.sh`：在应用分支上计算 `git diff --name-only --no-renames origin/<线>/platform...HEAD`（自共同祖先以来应用分支的改动，
  改名按删除 + 新增计），任何不匹配 `.jabiz-app-paths` 的路径都报错。合并平台分支带来的改动不计入（它们在共同祖先之后同时出现在两边）。
  - 基准：参数，否则 `$APP_PATHS_BASE`，否则由 `.jabiz-platform-line` 得到 `origin/<线>/platform`；没有该文件的旧分支用 `origin/platform`。
  - 线的检查（对所有分支，包括平台分支）：以线命名的分支（`1.1/…`）必须与 `.jabiz-platform-line` 一致，否则失败——应用漏了合并新线的平台，
    或新线的平台分支忘了改文件。分支名取 `$APP_PATHS_BRANCH`（CI 中 HEAD 是游离的），否则取当前分支。`.jabiz-platform-line` 是平台文件，
    应用改它按越界处理。
  - 模式：`#` 起注释；`**` 可跨目录，`*`、`?` 只在一段路径内；`.jabiz-app-paths` 本身总是允许。
  - 模式不得覆盖平台分支（共同祖先）上已有的文件（例如 `backend/**`），否则同样报错。
  - 平台自己的目录（`backend/core` `backend/runtime` `backend/ext-geo` `backend/app` `backend/build-logic` `frontend` `spec`
    `docs/design` `docs/guide`）下的任何改动（包括新文件）都算越界，不论 `.jabiz-app-paths` 如何写。
  - 检查用的脚本与工作流来自被检查的分支本身，因此只有在分支保护把 `app-paths` 设为必需检查、且对这两个文件的修改经过评审时才有约束力。
  - 退出码：0 通过（或没有 `.jabiz-app-paths`，不是应用分支）；1 有越界或线不一致；2 无法检查（找不到平台分支或共同祖先、线号格式不对），不放行。
- 平台 CI（`ci.yml`）对推送到长期分支（各线的平台分支与应用分支）和所有 PR 运行（应用分支不能改 `ci.yml`）；工作分支（`<线>/phase-…`、`<线>/<应用>-<N>-…`）只经 PR 运行，同一提交不重复检查；PR 的新推送取消其进行中的运行；`e2e` 与 `compose` 在 `build`、`frontend` 通过后运行，草稿 PR 不运行（标为就绪时补跑）；也可手动运行（`workflow_dispatch`）。其 `app-paths` 作业取完整历史，存在 `.jabiz-app-paths` 时取该线的平台分支，
  在每个分支上运行检查（线的检查对平台分支同样有效）与脚本自己的测试 `tools/test/check-app-paths.test.sh`。
- 应用自己的开发规则写在应用目录内的 `CLAUDE.md`（如 `backend/culture/CLAUDE.md`、`site/CLAUDE.md`）与 `docs/<应用>/` 中；
  根目录的 `CLAUDE.md` 只属于平台，应用分支不改它（避免合并冲突）。

## 3. 多个可部署应用

### 3.1 构建

- `backend/settings.gradle.kts` 自动包含 `backend/` 下每个带 `build.gradle.kts` 的直接子目录（平台模块照常显式列出，其余自动发现），
  应用分支增加模块不必修改 settings。
- 约定插件 `jabiz.boot-app`（`backend/build-logic`）承担现在根构建脚本中 `project(":app")` 的全部配置：Spring Boot、`bootRun` 的虚拟线程属性、
  `platformCheck` 任务、场景快照与 OpenAPI 快照的测试属性，以及前端打包：

```kotlin
plugins { id("jabiz.boot-app") }
jabizApp {
    mainClass = "com.jabiz.culture.CultureApp"
    spa("/", "../../site")                  // 公开网站 → static/
    spa("/admin", "../../frontend")         // 通用后台，以 VITE_BASE=/admin/ 构建 → static/admin/
}
```

- `app` 改用同一插件（`spa("/", "../../frontend")`），行为不变；这是本阶段对现有构建的唯一改动，由现有测试与 CI 证明。
- 应用自己的后台页面（D22）：`spa("/", "../../frontend", extension = "<目录>")` 把应用目录中的扩展编入通用后台（`JABIZ_ADMIN_EXTENSION`，
  扩展目录是构建输入）；见 12 §9。`app` 以此编入示范扩展 `admin-extension/`。
- 界面语言与区域（D22 第 7 条）：`languages("en")`、`region = "en-US"`，插件同时传给前端构建并写入 jar 的 `META-INF/jabiz-app.properties`（12 §10）。
- 应用自己的 SPA（阶段 16c-2，决策 D43；§4.1、指南 `docs/guide/app-spa.md`）：`spa("/portal", "portal", entry = "portal", pwa = Pwa.ON)`
  （`import com.jabiz.gradle.Pwa`）。`entry` 是它登录的入口，`pwa` 为 `OFF`（缺省）、`ON` 或 `REMOVE`。构建变量 `VITE_BASE`、`VITE_JABIZ_ENTRY`、
  `VITE_JABIZ_PWA` 与 `VITE_JABIZ_SPA_PREFIXES`（同一 jar 中其他 SPA 的前缀，以逗号分隔，如 `/,/sponsor/`）。插件把各 SPA 的路径、入口与 `pwa`
  写入 `jabiz-app.properties`：`jabiz.web.spa[i].*` 是服务端最低优先级的缺省，`jabiz.build.spa[i].*` 记下构建了什么（§3.2 的检查用）。
- 构建所在的提交（16z）：环境变量 `JABIZ_BUILD_COMMIT`（镜像构建以 `--build-arg JABIZ_BUILD_COMMIT=<提交>` 给出，仓库不进构建上下文）由插件作为
  `VITE_JABIZ_BUILD_COMMIT` 传给每个 SPA 的构建（也是构建输入）；通用后台的登录页在表单下以小字显示前 12 位（"Build 4dc1aa14d922"），没有即不显示（本机开发）。
  页面与服务端在同一个 jar 中，所以它就是正在运行的版本。`@jabiz/ui` 的 `SignInForm` / `AuthFrame` 为此有可选的 `footer`。
- `sourceDir` 不是平台前端时（经 `link:` 引用平台的两个包）：先安装平台前端（`pnpmInstallPlatformFrontend`，同一模块内只注册一次，
  以平台前端构建的 SPA 也用它），平台的 `frontend/packages/**` 是其构建输入；`spaCheck<名>` 在平台前端中运行 `pnpm app:check <目录>`，
  是 `spaBuild<名>` 的前置并挂在 `check` 上，版本不一致即构建失败。

### 3.2 多个单页应用（SPA）

- `SpaFallbackFilter` 改为按配置工作：`jabiz.web.spa[i].path`（前缀）与 `.index`（该前缀的 `index.html`），最长前缀优先；
  缺省只有 `/` → `/index.html`，与现在相同。`/api`、`/actuator` 与带扩展名的路径不回退（不变）。
- 前缀按路径段匹配（`/admin` 匹配 `/admin` 与 `/admin/…`，不匹配 `/administrator`）；`index` 缺省为 `<前缀>/index.html`。
- 每个 SPA 可配置内容安全策略 `jabiz.web.spa[i].content-security-policy`，写入该前缀下全部响应（`/api`、`/actuator` 除外）的响应头；
  未配置时用平台的缺省值（`JabizWebProperties.DEFAULT_CONTENT_SECURITY_POLICY`，阶段 16c-2 对所有 SPA 收紧，决策 D43 第 7 条）
  `default-src 'self'; img-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'self'; object-src 'none'; form-action 'self'; worker-src 'self'; manifest-src 'self'`
  （Radix 的定位、滚动锁与通知仍需要内联样式，所以 `'unsafe-inline'` 保留在 `style-src`；脚本一律不允许内联）。公开网站在应用中配置自己的策略（例如允许嵌入视频的 `frame-src`）。
- 被另一个平台内嵌的 SPA 声明 `jabiz.web.spa[i].embedding: <外部令牌提供方>`（决策 D59，10 §15.12）：请求带提供方的租户参数（如 `?shop=`）且是它的租户时，该响应的 `frame-ancestors`
  换成该租户的来源与提供方的 `frame-ancestors`，并去掉 `X-Frame-Options`；其他请求照旧不能被内嵌。该 SPA 的入口须以同一提供方登录（启动检查）。
- **入口页的部署值**（17g，`SpaIndexes`）：一份构建要用在几个环境、而入口页需要随环境而变的公开值（如另一个平台的脚本从 `<meta>` 读取的应用 key）时，
  入口页写 `content="{{jabiz:<名>}}"`，部署配置 `jabiz.web.spa[i].index-values.<名>`（值可取自环境变量）。服务器在启动时读入口页、把值以 HTML 转义写进去，以后对该 SPA 的客户端路由与入口页本身（Service Worker 预存它）都回这份页面
  （响应头与其他入口页相同：页面策略、`no-cache`；带强 ETag，重新验证回 304）。占位只能是标签中一个双引号属性的**完整的值**（`="{{jabiz:名}}"`），其他写法（正文、`script` / `style` 的内容、单引号或无引号的属性、值的一部分）与没有值的占位都是启动错误，
  配置了而没用到的值、jar 中没有入口页都是告警（后者照原样提供）。只用于公开值：入口页谁都能读，秘密绝不放这里。静态站点的目录页与入口页之外的文件不处理。17f（按路由与数据写标题与预览信息）可沿用同一插入方式。
- **根地址的转向**（16zf-a）：`jabiz.web.root-redirect`（环境变量 `JABIZ_WEB_ROOT_REDIRECT`）设了本主机的一个路径（如 `/biz/`）时，对恰好 `/` 的 `GET` / `HEAD`
  在查找 SPA 之前即 302 到该路径（查询串保留；`Cache-Control: no-cache`，不带页面策略）；只能是本主机的绝对路径（不能是 `/` 自身、带协议或主机的地址、`//…`、`/api` 或 `/actuator` 之下），
  `SpaConfigCheck` 检查（目标不在任何 SPA 之下时告警：须另有东西应答它）。前端都挂在前缀下（`/taker/`、`/biz/`）的主机用它决定裸域名打开哪一个；不设时根照旧由 `/` 上的 SPA（若有）提供。`/index.html` 等其他路径不转向。
- **静态站点的多页**（16zf-b，`StaticPages`）：没有登录入口的 SPA 可以每个目录一页（`static/biz/zh/index.html`、`static/biz/zh/pricing/index.html`，由 Vite 的多页构建产生）；
  启动时扫描 jar 中该前缀下的 `**/index.html` 一次，请求命中这样的目录（带不带尾部斜杠都行）即交出该目录的页面而不是站点首页，其他客户端路由照旧回退到首页；
  这些页面与首页一样 `Cache-Control: no-cache`。带入口的 SPA 不受影响（路由全部回退到它的 `index.html`）。
- **搬走的前缀**（16zf-a）：`jabiz.web.redirects[i].from` / `.to`：对 `from`（如 `/p`）或其下路径的 `GET` / `HEAD` 在查找 SPA 之前 301 到 `to`（如 `/taker/p`）之下的同一路径（查询串保留；
  `no-cache`），最长的 `from` 优先。用于前端搬到新前缀后，此前印出或寄出的链接仍然有效；不是通用的改写机制。`from` 与 `to` 都是本主机的前缀（不能是 `/`、不带尾部斜杠、不在 `/api` 与 `/actuator` 之下），
  `to` 不能在自己的 `from` 之下（会循环），同一 `from` 只能出现一次；`to` 不在任何 SPA 之下时告警。
- `jabiz.web.spa[i].entry` 是该 SPA 登录的入口，`.pwa` 为 `off` / `on` / `remove`（16c-2）。jar 中由 `jabizApp` 写入的 SPA 是缺省；
  应用配置中的 `jabiz.web.spa` 整体替换它（Spring 的列表覆盖规则）。
- **缓存与类型**（16c-2，`SpaFallbackFilter`）：成功的 `index.html`（含客户端路由的回退）、`<前缀>/sw.js`、`<前缀>/manifest.webmanifest` 为
  `Cache-Control: no-cache`，`<前缀>/assets/*`（带内容哈希）为 `public, max-age=31536000, immutable`；它们替换 Spring Security 写在每个响应上的
  `no-cache, no-store…`（并去掉 `Pragma`、`Expires`；不这样做，每次打开页面都要重新下载全部文件）。其他文件与非 200 的响应保持 Spring Security 的头。
  `.webmanifest` 为 `application/manifest+json`（Spring 的类型表中没有它）。响应从不带 `Service-Worker-Allowed`，Service Worker 的作用域不能大于它所在的目录。
- **Service Worker 守卫**（16c-2，`ServiceWorkerScriptGuard`，最先执行的过滤器）：带 `Service-Worker: script` 请求头的请求，只有 `pwa` 为 `on` 或 `remove`
  的 SPA 的 `<前缀>/sw.js` 才回答，其余（包括 `/api/**`、其他 SPA、同一 SPA 的其他文件）一律 404：同源上被上传或注入的脚本不能被注册为 Service Worker。
- 配置由 `SpaConfigCheck`（启动检查，类别 `WEB`）一次性报告：前缀格式、重复、落在 `/api` `/actuator` 下，`index` 格式，CSP 为空或多行，
  `entry` 格式（错误）与不是可用的登录入口（错误）；与 jar 中构建的 SPA 不一致（配置了但没有构建、构建了但没有配置、入口或 `pwa` 不同）为警告。
- 同源的多个 SPA 共享 `localStorage` / `sessionStorage`：公开前端不存令牌（第 4 节），也不得读写后台的会话键。
- **测试系统的标志**（16zd）：`jabiz.web.environment`（环境变量 `JABIZ_WEB_ENVIRONMENT`）是运行时设置，因为测试与正式环境运行同一镜像：`production` 为正式系统，
  其他名字为测试系统，缺省 `test`（没有声明自己是正式的系统永远不会看起来像正式的）；名字只允许字母、数字、空格、连字符，至多 32 字符（`SpaConfigCheck`）。
  `GET /api/auth/entries/{name}` 返回它（`environment`），前端登录前就读到（`@jabiz/client` 的 `useEnvironment()`）。`@jabiz/ui` 的 `EnvironmentBanner` 是警示色的通栏横条
  （环境名大写 + "测试环境，不是正式版"，三种语言；文档标题前加 `[TEST]`；正式系统与尚未读到时什么也不显示），`AppShell` 把它与顶栏一起固定在页面顶部、`AuthFrame` 放在卡片之上，
  所以通用后台与建在这两者上的前端每一页都有；自带外框的前端（如手机式的 App）把它放在自己外框的顶部。部署时只有正式环境设置 `JABIZ_WEB_ENVIRONMENT=production`。
- 构建：插件为每个 `spa(...)` 注册安装任务（平台前端为 `pnpmInstallPlatformFrontend`，其他目录为 `pnpmInstall<名>`）与 `spaBuild<名>`
  （`VITE_BASE=<前缀>/`，输出到本模块的 `build/spa/<名>`，因此两个应用可用不同的 base 构建同一个 `frontend/`；全部 pnpm 任务串行），
  应用自己的 SPA 另有 `spaCheck<名>`（§3.1），`bootJar` 把输出放到 `static/<前缀>`。

### 3.3 部署与 CI

- 每个应用一个 compose 文件（`deploy/<应用>/docker-compose.yml`）与一个 CI 工作流（`.github/workflows/<应用>.yml`），都在应用目录中。
- 平台的 `ci.yml` 运行 `./gradlew check`：在应用分支上它自动覆盖应用模块（自动包含），因此平台改动合并到应用分支后，应用的测试会立即执行。

## 4. 公开前端

公开网站（面向匿名访客、以内容与视觉为主）与通用后台是不同的产品：

- 工具链与后台相同：pnpm、React 19、TypeScript、Vite、React Router、TanStack Query、i18next、Vitest、Playwright。
- **不用后台的页面外观**：视觉由应用自己设计（可用 `@jabiz/ui` 的组件与主题 token，或 CSS Modules + CSS 自定义属性），以减小体积并满足编辑风格的设计要求。
- 数据只来自公开接口（15），类型由公开模板目录快照生成（15 §7）；不登录、不存令牌。
- 无障碍（WCAG 2.2 AA）与移动端适配是公开前端的验收项（axe 自动检查纳入 Playwright）。

### 4.1 需登录的应用前端与 PWA（阶段 16c-2，决策 D43）

需要登录的应用前端（商家后台、答题 App、客户门户）与公开网站不同，它用平台的界面与会话：

- **形态**：应用目录中的 pnpm 工程（自己的 `package.json` 与 `pnpm-lock.yaml`），以 `link:` 引用 `@jabiz/client` 与 `@jabiz/ui`，
  在 `jabizApp` 中声明 `spa("/<前缀>", "<目录>", entry = "<入口>", pwa = …)`。示范：`backend/app/portal/`（`/portal/`，入口 `portal`，`pwa = ON`）。
- **会话与积木**：`@jabiz/client` 以构建的入口登录、刷新与发起 OIDC，会话按入口分开（12 §4）；页面用 `@jabiz/ui` 的登录、注册、邮件链接、找回密码、
  二次验证、step-up、改密码与横幅积木（12 §12），不依赖路由。入口的 `app-path` 即该前端的根（`/portal/`）：邮件链接（`{entryUrl}`）与 OIDC 回调
  （`redirect-uris.<入口>`）都回到它自己的页面。
- **构建预设** `@jabiz/client/vite`（`jabizSpa({ pwa })`）：取 `jabizApp` 给出的构建变量并校验，统一 React 等共享包的副本（`resolve.dedupe`；Vitest 中另把
  平台前端的依赖交给 Vite 处理并指向 SPA 自己的副本），PWA 时生成 `manifest.webmanifest`（缺少 192 / 512 或 maskable 的 PNG 图标即构建失败）
  与 `sw.js`。
- **PWA**（D43 第 6、10 条）：Service Worker 只预缓存应用外壳（`index.html`、带哈希的 JS / CSS、`public/` 中的文件），缓存名 `jabiz-shell-<SPA名>-<产物哈希>`；
  页面导航网络优先、离线时回退到缓存的外壳，导航的响应从不写入缓存（带令牌的邮件链接与 OIDC 回调不会被缓存）；不拦截 `/api`、`/actuator`、
  同一 jar 中其他 SPA 的前缀、跨源与非 GET 请求，不缓存任何接口响应；激活时只删除自己前缀的旧缓存（Cache Storage 由同源的 SPA 共用）。
  新版本安装后等待，`PwaUpdatePrompt` 提示，用户点"刷新"后才生效并重新加载；激活时保留上一个版本的缓存（还在运行它的标签页仍能取到按需加载的文件），
  更早的删除。撤下 PWA 用 `pwa = REMOVE`：发布一个注销自己、删除自己缓存的 `sw.js`，至少保留一个发布周期后才改为 `OFF`
  （直接删去 `sw.js` 不行：404 不会注销已安装的 Service Worker）。服务端的配合见 §3.2。
- **只支持同源**：不开 CORS；令牌只经 `Authorization` 头，不用 Cookie，所以 CSRF 防护照旧关闭（D12、D15）。
- **检查**：`pnpm app:check <目录>`（版本与平台一致，Gradle 中的 `spaCheck<名>`），以及该工程自己的 `lint` / `typecheck` / `test`（平台 CI 对示范门户运行）。

## 5. 测试

- `check-app-paths.sh`（`tools/test/check-app-paths.test.sh`）：在临时仓库中构造"只改应用目录"与"改了平台目录"两种分支，断言前者通过、后者失败；
  另有非 ASCII 与含空格的文件名、平台目录下的新文件、删除与移动平台文件、`*` 不跨目录、合并平台之后、模式覆盖平台文件、找不到平台分支。
  版本线（13f）：基准取自 `.jabiz-platform-line`；线的工作分支；应用自己改线号；以新线命名但未合并新线的平台；升级到新线后按新线检查；
  旧线的修复经向前合并到达新线的应用；新线的平台工作分支未改线号；线号格式不对、新线的平台分支未取得（退出码 2）；`APP_PATHS_BRANCH` 与游离的 HEAD；
  没有线号文件的旧分支以 `origin/platform` 为基准。
- 构建：`app` 改用约定插件后 `./gradlew check`、`:app:bootJar`、端到端与 compose 作业全部照常通过。
- `SpaFallbackFilter`（`SpaFallbackFilterTest`、`SpaConfigCheckTest`）：多个前缀的回退、最长前缀优先、路径段边界、CSP 头；缺省配置与现在行为相同；
  `MultiSpaIT`（app）经完整的 WebFlux 与安全过滤链验证 `/` 与 `/admin/` 两个 SPA。
