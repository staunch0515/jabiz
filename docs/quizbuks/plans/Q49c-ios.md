# Q49c iOS App（Capacitor）（2026-10-28 确认）

规划：`Q49-mobile-apps.md`。分两步：**Q49c-1 外壳**（不依赖平台，现在做，分支 `1.3/quizbuks-49c-ios`）与 **Q49c-2 接平台**（17j、17k、17l 合入后：系统浏览器登录、Apple 登录、推送、AASA 改由平台提供）。

## 1. 决定（2026-10-28）

| # | 决定 |
|---|---|
| D-Q49c-1 | Apple Developer Program 以**个人**注册（商店上的卖家为本名）；需要会员时由开发方提前告知（Q49c-1 的签名上传、17j 的 Apple 登录与 17k 的 APNs 都要） |
| D-Q49c-2 | Bundle ID `com.quizbuks.app`（与 Android 包名相同） |
| D-Q49c-3 | 外壳直接打开 `https://quizbuks.com/taker/`（Capacitor `server.url`），页面的来源仍是本域名：平台不开 CORS、不用 Cookie，打包进 App 的网页无法调用接口 |
| D-Q49c-4 | 只支持 iPhone，iOS 16 及以上，竖屏 |
| D-Q49c-5 | 销售地区：除中国大陆外的全部地区 |

## 2. Q49c-1 的实现

- `quizbuks-mobile/ios`（`README.md`）：Capacitor 8.5.3（Swift Package Manager），`capacitor.config.json` 为设置的唯一来源；Xcode 工程在 `xcode/App`，`pnpm sync`（`cap sync ios`）生成的文件不提交。
  - User-Agent 末尾加 `QuizBuksApp/ios`，起始地址带 `shell=ios`；
  - `SceneDelegate.swift`：`ScopePlugin` 让主框架只停在 `https://quizbuks.com/taker/`，其他地址（商家后台与充值、招商站、管理后台、其他网站）交给 Safari；嵌入的视频（子框架）不受影响；
    Universal Link（邮件中 App 的链接）在 App 的网页中打开（冷启动时等视图出现后），并加上 `shell=ios`；
  - 图标由 App 的 maskable 图标（铺满、不透明）放大到 1024，启动画面为白底居中的图标；`ITSAppUsesNonExemptEncryption = false`（只用 HTTPS）。
- App：`shell.ts` 认 `ios`（参数、User-Agent），`install.tsx` 在 iOS 外壳中同样不显示安装提示（WKWebView 不是 standalone，否则平台会显示 Safari 的"添加到主屏幕"指引）。
- Universal Links：`deploy/quizbuks/apple-app-site-association.sh`（只认 `/taker/*`），部署 workflow 在环境变量 `QUIZBUKS_IOS_APP_ID`（`<Team ID>.com.quizbuks.app`）设了时生成并上传到 `well-known/`，`site.caddy.tpl` 以 JSON、不转向地提供。17l 合入后改用平台的。
- workflow `quizbuks-ios.yml`（macOS 运行器，取镜像上最新的正式版 Xcode）：PR 与推送时一致性测试、AASA 测试、`cap sync` 与不签名的构建；
  手动运行（只在 `1.3/quizbuks`，Environment `appstore`）以 App Store Connect API 密钥自动签名、`archive` 并以 `ExportOptions.plist`（`destination: upload`）直接上传，构建号为运行编号加 100。

## 3. 测试

- App：`shell.test.ts`（iOS 的参数与 User-Agent、未知的外壳不算）、`install.test.tsx`（iOS 外壳中三处都不显示）。
- `quizbuks-mobile/ios/scripts/consistency.test.mjs`：起始地址与 Android 的主机、前缀相同；Swift 的常量、User-Agent、Bundle ID、关联域名与 AASA 的路径一致；只 iPhone、iOS 16、竖屏。
- `tools/quizbuks/test/apple-app-site-association.test.sh`；CI 在 macOS 上构建成功。
- 真机（TestFlight 内部测试，`launch-checklist.md`）：打开即 App 的首页、没有安装提示；邮件中的验证链接打开 App；`/sponsor/` 的链接在 Safari 打开；嵌入的视频能播放。**第三方登录在 Q49c-2 之前不可用**（Google 拒绝 App 内的网页登录），邮箱与邮件链接登录可用。

## 4. 需求方要做的（需要开发者会员时再做，届时告知）

1. 以个人注册 Apple Developer Program（每年 $99）。
2. Certificates, Identifiers & Profiles → Identifiers → App ID `com.quizbuks.app`，勾选 Associated Domains、Push Notifications、Sign in with Apple。
3. App Store Connect → App → 新建：名称同 Play（日 `QuizBuks – クイズで特典`、中 `QuizBuks – 答题拼好价`、英 `QuizBuks`），主要语言日文，Bundle ID 选上一步的。
4. 用户和访问 → 集成 → App Store Connect API：新建"App 管理"角色的密钥。GitHub → Settings → Environments 新建 `appstore`（建议 Deployment branches 只允许 `1.3/quizbuks`）：
   secrets `APP_STORE_CONNECT_KEY_ID`、`APP_STORE_CONNECT_ISSUER_ID`、`APP_STORE_CONNECT_KEY_P8`（`.p8` 文件的全文，只能下载一次，自己另存一份），变量 `APPLE_TEAM_ID`（Membership 页的 Team ID）。
5. GitHub 的 Environment `production` 设变量 `QUIZBUKS_IOS_APP_ID` = `<Team ID>.com.quizbuks.app`，部署 production 一次（`DEPLOY.md`"iOS App 的 apple-app-site-association"）。
6. Actions → QuizBuks iOS → Run workflow（分支 `1.3/quizbuks`）；处理完后在 TestFlight 中把自己加为内部测试员，装到 iPhone 上。
7. 价格与销售范围：免费，去掉"中国大陆"。

## 5. 状态

Q49c-1 实现中（本分支）。Q49c-2 在 17j、17k、17l 合入并同步到 `1.3/quizbuks` 后另写计划。
