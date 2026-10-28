# QuizBuks iOS（Capacitor）

WKWebView 打开 `https://quizbuks.com/taker/`（`docs/quizbuks/plans/Q49c-ios.md`）。Capacitor 8.5.3，依赖经 Swift Package Manager；没有 CocoaPods。

- **设置**只在 `capacitor.config.json`：Bundle ID、起始地址（带 `shell=ios`）、User-Agent 的标记 `QuizBuksApp/ios`。
  `SceneDelegate.swift` 中的 `QuizBuksScope` 写着同一个主机与前缀；`scripts/consistency.test.mjs` 检查它们、关联域名（`App.entitlements`）与 Android 的一致。
- **生成的文件不提交**：`pnpm install && pnpm sync`（`cap sync ios`）写出 `xcode/App/App/public`、`capacitor.config.json`、`config.xml` 与 Swift 包的清单。
- **构建**只在 macOS 上（GitHub Actions 的 `quizbuks-ios.yml`）；本地没有 Mac 也能改：工程文件、Swift 与设置的检查都在 CI 中。

```bash
cd quizbuks-mobile/ios
pnpm install --frozen-lockfile
pnpm test        # 与 App、Android 的一致性
pnpm sync        # 生成 Xcode 需要的文件
# macOS：xcodebuild -project xcode/App/App.xcodeproj -scheme App -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

签名与上传只在 workflow 的手动运行中（App Store Connect API 密钥自动签名，`ExportOptions.plist` 直接上传到 TestFlight）；仓库中没有证书、描述文件与密钥。
