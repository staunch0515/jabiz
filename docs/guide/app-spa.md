# 应用自有的前端与 PWA

需要登录的应用前端（答题 App、商家后台、客户门户……）如何建在平台上：一个应用目录中的 pnpm 工程，经 `link:` 使用平台的 `@jabiz/client` 与 `@jabiz/ui`，
由 `jabizApp` 构建进同一个 jar。依据：决策 D34 第 5 条、D43；设计 12 §4 / §12、17 §3 / §4.1、10 §12 / §15。完整的示范是 `backend/app/portal/`（`/portal/`）。

公开网站（不登录、只读公开接口）另见 17 §4 与 15；通用后台里的应用页面见 `admin-extension.md`。

## 1. 目录与依赖

```
backend/<应用>/
  build.gradle.kts            jabizApp { spa("/app", "web", entry = "app", pwa = Pwa.ON) }
  web/
    package.json              自己的依赖，"@jabiz/client": "link:../../../frontend/packages/client"（ui 同理）
    pnpm-lock.yaml            自己的 lockfile
    vite.config.ts            jabizSpa({ pwa: { … } })
    tsconfig*.json            见第 4 节
    index.html  public/  src/
```

- **共享的包版本必须与平台前端一致**：`react`、`react-dom`、`tailwindcss`、`@tanstack/react-query`、`i18next`、`react-i18next`、`vite`，以及两个包的运行时依赖
  （`dayjs`、`openapi-fetch`、`radix-ui`、`react-hook-form`、`lucide-react`、`qrcode`、`react-markdown`（16n 起）等）都写成平台前端 lockfile 中的确切版本。
  `pnpm app:check <目录>`（在 `frontend/` 下）逐项比对；Gradle 构建中它是 `spaCheck<名>`，挂在 `check` 上，不一致即构建失败。
  没有 React、也不用平台包的静态站点（只有 HTML 与样式，16zf-a）只须声明的构建工具（`vite`）与平台同版本，不必声明其他共用包。
- 平台前端须先 `pnpm install`（被 `link:` 的包从它们自己的 `node_modules` 解析依赖）；Gradle 构建自动安排（`pnpmInstallPlatformFrontend`）。
- 应用分支上，应用目录列入 `.jabiz-app-paths`；`node_modules/` 不提交。

## 2. 构建（`jabizApp`）

```kotlin
import com.jabiz.gradle.Pwa

jabizApp {
    mainClass = "com.example.quiz.QuizApp"
    spa("/", "web", entry = "app", pwa = Pwa.ON)            // 答题 App
    spa("/sponsor", "sponsor-web", entry = "sponsor")       // 商家后台，不是 PWA
    spa("/admin", "../../frontend", entry = "admin")        // 通用后台
}
```

- 构建变量：`VITE_BASE`（`/sponsor/`）、`VITE_JABIZ_ENTRY`、`VITE_JABIZ_PWA`（`on` / `off` / `remove`）、`VITE_JABIZ_SPA_PREFIXES`（其他 SPA 的前缀）。
- 插件把各 SPA 的路径、入口与 `pwa` 写入 jar（`META-INF/jabiz-app.properties`），作为服务端 `jabiz.web.spa` 的缺省：通常不必在 `application.yml` 里再写。
  写了就整体替换它（例如要给公开网站配置自己的内容安全策略时，要把全部 SPA 都写上）；与 jar 中构建的不一致时启动检查 `WEB` 告警。
- `spa` 的 `build` 脚本要能接受 `--outDir`：`"build": "tsc -b && vite build"`。

## 3. Vite 预设 `@jabiz/client/vite`

```ts
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { jabizSpa } from '@jabiz/client/vite'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [
    jabizSpa({
      pwa: {
        name: 'Quiz', shortName: 'Quiz', themeColor: '#0f172a', backgroundColor: '#ffffff',
        icons: [
          { src: 'icon-192.png', sizes: '192x192' },
          { src: 'icon-512.png', sizes: '512x512' },
          { src: 'icon-maskable-512.png', sizes: '512x512', purpose: 'maskable' },
        ],
      },
    }),
    tailwindcss(),
    react(),
  ],
  test: { environment: 'jsdom', setupFiles: ['./src/test/setup.ts'] },
})
```

预设做的事：

- 取并校验构建变量，设 `base`（不要再自己写 `base`）。
- 统一共享包的副本：`resolve.dedupe` 让经 `link:` 的包与 SPA 用同一个 React、i18next、TanStack Query。Vitest 中另把平台前端的依赖交给 Vite 处理、取其
  ES 模块版本并指向 SPA 自己的副本（否则测试中会出现两个 React，报 "Invalid hook call"）。
- PWA（`VITE_JABIZ_PWA=on`）：生成 `manifest.webmanifest` 并在页面中链接它；编译平台的 Service Worker 为 `sw.js`，内含预缓存清单（`index.html`、带哈希的
  JS / CSS、`public/` 中的文件）、缓存名 `jabiz-shell-<SPA名>-<产物哈希>` 与要放过的其他 SPA 前缀。**图标**必须是 `public/` 中的 PNG，尺寸与 `sizes`
  一致，且至少有 192×192、512×512 与一个 `maskable`（内容在中间 80% 的安全区内）的，否则构建失败。
- `pwa = REMOVE`：只生成注销自己、删除自己缓存的 `sw.js`，不再有 manifest。**撤下 PWA 时先发布至少一个 `REMOVE` 的版本，再改为 `OFF`**：
  直接从 `ON` 改为 `OFF` 时服务器不再有 `sw.js`，浏览器的更新检查得到 404 却保留已安装的 Service Worker，它会一直按旧的外壳运行。
  `REMOVE` 的版本要留到用户大都打开过一次之后（例如一个发布周期）。
- 新版本激活时删除自己更早的缓存，但保留上一个版本的：还在运行上一版的标签页仍能取到它的按需加载的文件（服务器已换成新构建）。

## 4. TypeScript、样式与测试

- `tsconfig.app.json`：`"types": ["vite/client"]`、`"jsx": "react-jsx"`、`"moduleResolution": "bundler"`；被 `link:` 的包以源码形式参与类型检查，
  它们的 `react` 类型会从平台前端解析，与 SPA 自己的副本"同名不同型"。用 `paths` 统一到 SPA 的副本：

  ```json
  "paths": {
    "react": ["./node_modules/@types/react"], "react/*": ["./node_modules/@types/react/*"],
    "react-dom": ["./node_modules/@types/react-dom"], "react-dom/*": ["./node_modules/@types/react-dom/*"],
    "i18next": ["./node_modules/i18next"], "react-i18next": ["./node_modules/react-i18next"],
    "@tanstack/react-query": ["./node_modules/@tanstack/react-query"]
  }
  ```
- `tsconfig.node.json` 检查 `vite.config.ts`（`"module": "nodenext"`、`"types": ["node"]`）。预设是纯 JavaScript（带类型声明），任何 Node 版本都能加载。
- 样式表：`@import '@jabiz/ui/theme.css';`（它已 `@source` 了 `@jabiz/ui` 的源码；Tailwind 自动扫描本工程）。Tailwind 的 preflight 是全局的（阶段 15d 起）：
  页面上的每个元素都没有浏览器的默认样式（标题、列表、链接、按钮），样式全用工具类写；`body` 的背景与文字取主题 token。颜色只用主题 token（D34）。
  15d 之前写的应用页面若依赖浏览器的默认样式（未写工具类的标题、列表、链接），合并后请自查；`UI_SCOPE` 已不需要。
- 测试：Vitest + Testing Library，`setup.ts` 补上 jsdom 缺少的 `matchMedia`、`ResizeObserver` 与指针捕获（示范门户的 `src/test/setup.ts`）。
  示范门户的 CI：`pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test`，以及 `pnpm app:check ../backend/app/portal`。
- 无障碍检查（16v-1）：不要复制平台的 axe 辅助函数，从 `@jabiz/ui` 引用。组件与页面测试：

  ```ts
  import { expectAccessible } from '@jabiz/ui/testing'
  // render(…)；然后检查亮、暗两种外观（jsdom 不算样式，不查颜色对比度）
  await expectAccessible()
  ```
  端到端（Playwright）：

  ```ts
  import { expect, test } from '@playwright/test'
  import { expectAccessible, setAppearance } from '@jabiz/ui/testing/playwright'
  await setAppearance(page, 'dark')          // 在第一次导航之前
  await page.goto('/app/login')
  await expectAccessible(page, 'sign-in (dark)', { expect })   // 等动画结束，含颜色对比度；违规为 soft 失败
  ```
  它们分别需要 `vitest` + `vitest-axe` 与 `@playwright/test` + `@axe-core/playwright`（`@jabiz/ui` 的可选 peer）：作为 SPA 自己的 devDependencies，
  版本与平台前端相同（`app:check` 检查）。辅助函数不加载 `@playwright/test`，用的是你交给它的 `expect`；只要违规列表时用 `accessibilityViolations(page)`。
  Vitest 中预设把 `vitest`、`vitest-axe` 指向 SPA 自己的副本。示范门户：`src/a11y.test.tsx`（Vitest）与 `e2e/a11y.spec.ts`（Playwright 示例，以 `tsconfig.e2e.json`
  做类型检查；Vitest 的 `include` 只取 `src/**/*.test.*`，不运行它）。

## 5. 入口、会话与页面

- **测试系统的标志**（17 §3.2）：用 `AppShell` / `SignInForm` 等平台外框的页面自动在顶部显示测试系统的横条；自己写外框的前端在外框顶部放 `<EnvironmentBanner />`（`@jabiz/ui`），
  正式环境以 `JABIZ_WEB_ENVIRONMENT=production` 关掉它，其他环境什么都不用设。

- **入口**（10 §15.1）：

  ```yaml
  jabiz.security.entries:
    app:
      accepted-roles: QUIZ_TAKER
      self-registration: true
      registration-roles: QUIZ_TAKER
      app-path: /                 # 该入口前端的根：邮件链接在它之下
      session:
        persistence: device       # 关闭 App 后再打开仍登录（admin 不能用）
        refresh-token-ttl: P30D
        idle-timeout: 0           # 不按闲置结束
  ```
  `app-path` 不在该入口的 SPA 之下时启动检查告警。
- **客户端**：`configureClient({ entry: 'app' })`（缺省取 `VITE_JABIZ_ENTRY`；本地 `pnpm dev` 时没有它，所以示范门户在 `main.tsx` 中写明），
  `AuthProvider`、`QueryClientProvider`、`AppearanceProvider` 包住页面，`registerServiceWorker()` 在启动时调用一次。
- **页面积木**（`@jabiz/ui`，不依赖路由，导航经 `onNavigate` / `hrefOf`）：`SignInForm`（`signUpPath`、`passwordResetPath`）、`SignUpForm`、
  `MailLinkPage`（路径 `auth/verify-email`、`auth/join`、`auth/password-reset`；不要传 `search`，它自己读一次地址并立即去掉令牌）、`PasswordResetRequest`、
  `OidcCallback`（路径 `login/oidc`；登录与本人关联共用，关联完成后调用 `onLinked`）、`LinkedIdentities`（本人的外部账号：列表、关联、解除，16f-1）、`StepUpProvider`、`MfaEnrollment`、`ChangePasswordForm`、`SessionList`（本人在该入口的会话，结束本机会话后调用 `onSignedOut`，10 §15.9）、
  `MailPreferences`（本人的邮件：该入口的可退订通知与邮件语言，16s；应用的模板以 `.entries("app")` 声明给该入口、显示名写在 `mail.<名>.name`）、
  `VerifyEmailBanner`、`OfflineBanner`、`PwaUpdatePrompt`。
  示范门户用一个几十行的小路由（`src/router.ts`）；也可以用任何版本的 react-router。
  自己的语言切换在 `changeLanguage(lang)` 之后调用 `offerMailLanguage(lang)`：邮件用的是另一种语言时询问是否一起改，用户接受才修改（决策 D49 第 3 条）。
- **内容显示积木**（`@jabiz/ui`，16n）：`MarkdownView`（`text`、可选 `lang`；原始 HTML 一律不渲染，`javascript:` 等链接被去掉，样式随 `theme.css`）、
  `FilePreview`（`fileId`、`field`（`{ image, variants }`）、`width`：以会话取文件，图片为缩略图、可放大，其余为下载按钮），以及自己画图片时用的
  `useFileUrl(fileId, variant)`（对象 URL，卸载时释放；变体用 `@jabiz/client` 的 `previewVariant`）与 `downloadFile(fileId)`。不要从 `@jabiz/admin` 引用（它只给后台扩展）。
  示范门户的下单页以 `MarkdownView` 显示文案中的下单说明。
- **邮件链接**：平台的邮件链接是 `{entryUrl}auth/<页>?entry=…&token=…`，即入口自己的前端；应用自己的邮件模板可以写 `{entryUrl.<入口>}`（18 §5.6）。
- **退订页**（16v-1，D52 第 3 条）：在 SPA 中挂载 `@jabiz/ui` 的 `MailUnsubscribe`（`brand`，有路由时传 `search`），并把它的路径配置给入口：

  ```yaml
  jabiz.mail.unsubscribe-paths:
    app: mail/unsubscribe        # 相对该入口的 app-path：链接为 {entryUrl.app}mail/unsubscribe?token=…
  ```
  以 `.entries("app")` 声明给该入口的通知，退订链接就指向这一页，用户不必能打开管理后台；没有声明入口的通知仍用后台的 `/mail/unsubscribe`
  （`jabiz.mail.unsubscribe-path`），之前发出的链接照常有效。路径不在本应用提供的 SPA 之下、入口不可用即启动错误（`MAIL`）。
  升级说明：曾把 `jabiz.mail.base-url` 设为后台子路径（如 `https://<主机>/admin`）以绕行的应用，改回 `https://<主机>`；已发出的旧链接经后台的 `/auth/*` 照常可用。
- **单点登录**：提供方配置 `redirect-uris.<入口>`（该前端的 `login/oidc` 的绝对地址，在身份提供方登记）；没有的提供方不对该入口提供（10 §12）。
  本人关联的往返也回到这个地址（`OidcCallback` 按 `startIdentityLink` 记下的用途完成关联），不需要另外登记。
- **Google 登录与自动开户**（决策 D39，阶段 16f-1）：在 Google Cloud 控制台建 OAuth 客户端（Web 应用），把每个入口的 `login/oidc` 登记为已授权的重定向 URI，然后：

  ```yaml
  jabiz:
    security:
      oidc:
        providers:
          - id: google
            kind: google                    # issuer 缺省 https://accounts.google.com；按钮显示 Google 的标志
            client-id: ${JABIZ_OIDC_GOOGLE_CLIENT_ID}
            client-secret: ${JABIZ_OIDC_GOOGLE_CLIENT_SECRET}   # 只来自环境变量
            redirect-uris:
              portal: https://shop.example.com/portal/login/oidc
            labels: { en: Google, zh: Google, ja: Google }
            auto-provision: true            # 缺省 false；只在允许注册的入口开户
  ```

  开户只在入口 `self-registration: true` 时发生，邮箱须是 Google 担保的（`@gmail.com`、`@googlemail.com` 或 Workspace 账号）且没有被使用；已有账号的邮箱答 403
  `OIDC_ACCOUNT_EXISTS`，`OidcCallback` 提示用原来的方式登录、再在账户页用 `LinkedIdentities` 关联。账户页放 `<LinkedIdentities returnTo="/account" />`，
  并在 `OidcCallback` 的 `onLinked` 中回到它（示范门户的 `AccountPage`）。Apple 登录在 16f-2（推后）。
- **接口类型**：平台接口用 `api`；应用自己的接口以 `createApiClient<paths>()`（与 `api` 共用令牌、刷新与 step-up）配合应用自己快照生成的类型：

  ```json
  "gen:api": "openapi-typescript ../src/test/resources/openapi.json -o src/api/schema.d.ts",
  "check:api": "openapi-typescript ../src/test/resources/openapi.json -o node_modules/.tmp/schema.d.ts && diff -q node_modules/.tmp/schema.d.ts src/api/schema.d.ts"
  ```
  流程与模板照常经 `runProcess` / `runQuery`。

## 6. 服务端为 SPA 做的事（17 §3.2）

- 客户端路由回退到该 SPA 的 `index.html`（没有入口的静态站点例外：命中有自己 `index.html` 的目录即交出该页，17 §3.2，16zf-b）；`index.html`、`sw.js`、`manifest.webmanifest` 为 `Cache-Control: no-cache`，`assets/*` 一年且 `immutable`；
  manifest 的类型为 `application/manifest+json`。
- 只为声明了 PWA 的 SPA 的 `<前缀>/sw.js` 回答 Service Worker 脚本请求，其他一律 404；从不发送 `Service-Worker-Allowed`。
- 缺省的内容安全策略不允许内联脚本、插件、外部表单目标与外部 base；`worker-src` / `manifest-src` 只允许本源。
- 入口页里随部署而变的**公开**值（如别的平台脚本从 `<meta>` 读的应用 key）不在构建时写入：`index.html` 写
  `<meta name="…" content="{{jabiz:名}}">`，部署配置 `jabiz.web.spa[i].index-values.名: ${环境变量}`，服务器启动时写进去（HTML 转义；17g）。
  写在应用配置中该 SPA 的条目里（与 `path` 等同处；Spring 的列表按整体替换，不能只在环境里单补一项），值以 `${环境变量}` 取自部署。
  占位只能是标签中双引号属性的完整值；没有值、写法不对时启动失败。Vite 的开发服务器不替换它（开发时照原样）。秘密不放入口页。
- 只支持同源：不开 CORS，令牌只经 `Authorization` 头。

## 7. 已知限制

- `device` 的刷新令牌存在设备上（`localStorage` 与 IndexedDB）：XSS 能取走它。缓解：轮换与重用即吊销、不允许内联脚本、`admin` 不能用、只按入口开启。
- 没有 Web Locks 的浏览器（Safari 15.4 之前）一律按 `tab`。"复制标签页"会复制 `sessionStorage`（`tab` 下原有问题）。
- iOS 上安装到主屏幕的 PWA 与 Safari 的存储分开：在 Safari 中登录不会让 PWA 也登录。
- 提示安装用 `@jabiz/ui` 的 `PwaInstallBanner`（12 §PWA，16zj），在用户做完一件有价值的事之后渲染；账户页放一个 `dismissible={false}` 的作为固定入口。不自己监听 `beforeinstallprompt` 或判断 iOS。
- 不做推送、后台同步与数据离线；离线时只能打开外壳并提示离线。
