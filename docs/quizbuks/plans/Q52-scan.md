# Q52 App 内扫码（2026-10-30 确认，已实施）

需求方（2026-10-30）：冷启动阶段主要是现场扫码。在答题人的 App 里加"扫一扫"，范围包括商家屏幕或海报上的**领奖码**，以及店内海报上的**发布二维码**；
iOS 可以引入解码依赖；一定要把扫码领奖做好。

## 1. 为什么

Q22b 的设计是"App 中不写扫码代码，手机相机打开链接"。但系统相机扫码打开的是**浏览器**：

- iPhone 上加到主屏幕的 App 与 Safari 不共享登录，答题人在 Safari 中是未登录状态（2026-10-29 修过的"在 Safari 登录后落在首页"就在这条路上）；
- 商店 App（Q49）只在 App Links / Universal Links 被系统认可时才接住链接，否则同样落到浏览器。

App 内扫码使扫到的链接在**当前已登录的 App 中**打开。系统相机照样可用（码的内容不变）。

## 2. 决定

| # | 决定 |
|---|---|
| D-Q52-1 | 页 `/scan`（`app/src/scan/ScanPage.tsx`）；入口是页头的"扫一扫"按钮（除答题中、欢迎页与扫码页本身外的每一页）；领奖页的"码已失效"与"领取失败"另给"再扫一次" |
| D-Q52-2 | **只打开本站的发布与领奖链接**（`scan/target.ts` 的 `scanTarget`）：同源、App 路径（`/taker/`）下或旧的根路径下（Q30 之前印的码）的 `/p/<编号>` 与 `/redeem/<键>`；其他（别的网站、App 的其他页、纯文字）一律提示"这不是 QuizBuks 的码"并继续取景，从不跳转或打开外部网址（防二维码钓鱼） |
| D-Q52-3 | 解码：浏览器有 `BarcodeDetector` 且支持 `qr_code`（Android 的 Chrome，即 Android App）用它；否则按需动态加载 **jsQR 1.4.0**（Apache-2.0，无依赖；iOS 的 Safari 与 iOS App、桌面浏览器），不扫码的人不下载它。画面缩到 640 宽、明暗两种都试（屏幕上的码常是反色） |
| D-Q52-4 | 相机只在进入扫码页时请求（后置摄像头、无声音），离开页面或扫到码即停止全部轨道；画面只在本机识别，不保存、不上传。被拒绝、没有相机、浏览器不能用相机时，提示改用系统相机扫码；被拒绝或失败可"重试" |
| D-Q52-5 | 扫到即以 `replace` 打开目标页（扫码页不留在历史中），振动一下；领奖之后的流程照旧（Q22b、Q39） |
| D-Q52-6 | iOS 外壳加 `NSCameraUsageDescription`（缺少时 iOS 结束请求相机的 App，`consistency.test.mjs` 检查）；Android 外壳是 TWA，相机权限由 Chrome 按网站询问，不声明权限 |
| D-Q52-7 | 隐私政策草稿第2条第3项末尾加一句（相机画面只在设备上读码、不保存不发送），App Store 隐私标签与 Play 数据安全表注明不申报（不是收集的数据）；正式文本由需求方确认后在后台发布新版本 |

## 3. 实施记录（2026-10-30）

- `app/src/scan/`：`target.ts`（`scanTarget`、`targetPath`）、`decoder.ts`（`createDecoder`）、`ScanPage.tsx`、`messages.ts`（三语，命名空间 `app`）；`App.tsx` 的路由与页头按钮；
  `pages/RedeemPage.tsx` 的"再扫一次"；`redeem.scanHint` 三语改为指向"扫一扫"。依赖 `jsqr@1.4.0`（只在 App 的 `package.json`；平台没有，`app:check` 通过）。
- 测试：`scan/scan.test.tsx`（三语齐全；可打开与不可打开的链接；页头入口、外来码的提示、扫到领奖码即打开且停相机、扫到发布码、权限被拒与重试、不能用相机与关闭、离开即停相机；axe）、
  `scan/decoder.test.ts`（`BarcodeDetector` 优先、jsQR 的缩放与反色、读不到画面时不解码）、`Play.test.tsx`（失效码与领取失败的"再扫一次"）；
  e2e `reward-methods.spec.ts`："以 App 的扫一扫扫描商家屏幕领奖"：把商家后台实际显示的轮换码截图，作为答题人手机相机的画面（画布的视频流），App 解码、打开领奖页、一键领取。
  本地另在无头 Chromium 中验证了"二维码 → 相机流 → 画布 → jsQR"（Linux 的 Chromium 没有 `BarcodeDetector`，走的正是 iOS 的路径）。
- iOS：`Info.plist` 的 `NSCameraUsageDescription`（英文；本地化的 `InfoPlist.strings` 要在 Xcode 中加入工程，留待下次在 Xcode 中构建时补）。

## 4. 未做 / 已知

- 闪光灯、相册中的图片、手动输入码：不做（店内扫屏幕或海报，光线由商家负责；码很长，不适合手输）。
- iOS 的真机行为（WKWebView 中的相机权限提示）只能在 TestFlight 上确认；Capacitor 的 WebView 按网站询问权限。
- 离线时打开扫码页：jsQR 的分包不在 Service Worker 的外壳缓存中，离线的 iPhone 不能扫（扫到也打不开领奖页，没有影响）。
