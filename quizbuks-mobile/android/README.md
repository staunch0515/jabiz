# QuizBuks Android（TWA）

Trusted Web Activity：用户的 Chrome 以全屏打开 `https://quizbuks.com/taker/`，没有 WebView，登录、Cookie 与存储都是 Chrome 的
（`docs/quizbuks/plans/Q49b-android.md`）。工程取自 Bubblewrap 的模板（`@bubblewrap/core` 1.27.0，androidbrowserhelper 2.7.4），
只留用到的部分；没有通知委托（推送是平台 17k）、快捷方式与分享目标。

## 设置只在 `twa-manifest.json`

包名、域名、起始地址、范围、名称与颜色都写在 `twa-manifest.json`（Bubblewrap 的格式），`app/build.gradle.kts` 构建时读取，不在别处重复。
`scripts/consistency.test.mjs` 检查它与 App 一致：范围是 `backend/quizbuks/build.gradle.kts` 中 App 的前缀、起始地址带 `shell=android`、
包名与 `quizbuks-web/app/src/shell.ts` 的 `ANDROID_PACKAGE` 相同、名称与颜色与 App 的 PWA manifest 相同（都取自品牌档案 `quizbuks-web/brands/quizbuks/brand.json`，Q54）。

- **包名**上架后不能改。
- **App Links** 只认 `/taker/` 前缀（`AndroidManifest.xml` 的 `pathPrefix`）：商家后台、招商站与管理后台的链接在浏览器中打开。
- `LauncherActivity` 给本域名的每个启动地址（含邮件链接）加 `shell=android`，App 据此不显示安装提示。
- 图标与启动画面由 `quizbuks-web/brands/quizbuks/public/icon-512.png`、`icon-maskable-512.png`（品牌档案，Q54） 缩放而成（尺寸同 Bubblewrap）；App 的图标改了就重新生成。

## 构建

需要 JDK 17 与 Android SDK（`ANDROID_HOME`）。

```bash
cd quizbuks-mobile/android
node --test scripts/*.test.mjs            # 与 App 一致、上传脚本
./gradlew bundleRelease                   # 未签名的 app/build/outputs/bundle/release/app-release.aab
```

签名只在 workflow 中进行（`.github/workflows/quizbuks-mobile.yml`），上传密钥只来自环境变量 `QB_ANDROID_KEYSTORE`（文件路径）、
`QB_ANDROID_KEYSTORE_PASSWORD`、`QB_ANDROID_KEY_ALIAS`、`QB_ANDROID_KEY_PASSWORD`；版本号 `QB_ANDROID_VERSION_CODE`（缺省 1）。
仓库中没有密钥库（`.gitignore`）。secrets 与上传见 `deploy/quizbuks/DEPLOY.md`"Android App 的构建与上传"。

## 上传密钥（需求方在自己的电脑上做一次）

```bash
keytool -genkeypair -v -keystore upload.keystore -alias upload -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=QuizBuks"
base64 -w0 upload.keystore     # macOS：base64 -i upload.keystore
```

密钥库与密码只放进 GitHub Environment `play` 的 secrets，并另存一份到密码管理器；不发给任何人，不提交。
