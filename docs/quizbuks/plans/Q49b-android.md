# Q49b Android App（TWA）（2026-10-28 确认）

规划：`Q49-mobile-apps.md`（Android 不依赖平台，先做；封闭测试须至少 12 名测试者、连续 14 天）。分支 `1.3/quizbuks-49b-android`，不改平台与后端、不加迁移。

## 1. 决定（2026-10-28 全部同意）

| # | 决定 |
|---|---|
| D-Q49b-1 | 包名 `com.quizbuks.app`（上架后不能改） |
| D-Q49b-2 | 只有一个 App，打开 production（`quizbuks.com`）；封闭测试的 14 天就用这个包。production 届时不能开放时，再加 staging 的变体 `com.quizbuks.app.staging`（只供自己的内部测试） |
| D-Q49b-3 | 工程取自 Bubblewrap 的模板并提交（不在 CI 中跑交互式的 Bubblewrap）；设置只在 `twa-manifest.json`，Gradle 构建读取它 |
| D-Q49b-4 | App Links 只认 `/taker/` 前缀：邮件中 App 的链接在 App 中打开，`/sponsor/`、`/biz/`、`/admin/` 留在浏览器，商家后台与充值不会进 App |
| D-Q49b-5 | 在 App 中不显示任何安装提示（首页、结果页的横幅与账户页的固定入口）；判断只在 `shell.ts`，iOS（Q49c）接同一个函数 |

## 2. 实现

- `quizbuks-mobile/android`（`README.md`）：androidbrowserhelper 2.7.4、AGP 8.9.1、Gradle 8.11.1、compile / target SDK 36、min SDK 23；
  `LauncherActivity` 给本域名的每个启动地址（启动器与 App Links）加 `shell=android`；没有通知委托（推送是平台 17k）。
- App：`src/shell.ts` 的 `nativeShell()`（`shell=android` 或 Chrome 给 TWA 首页的 referrer `android-app://com.quizbuks.app`，按标签页记在 `sessionStorage`，
  参数从地址中去掉）；`main.tsx` 在路由读取地址前调用 `rememberShell()`；`install.tsx` 的三个组件在外壳中不渲染。
- `assetlinks.json`：`deploy/quizbuks/assetlinks.sh`（包名与指纹的格式检查后以 `jq` 写出）；部署 workflow 在环境变量
  `QUIZBUKS_ANDROID_PACKAGE`、`QUIZBUKS_ANDROID_CERT_SHA256` 都设了时生成并上传到 `well-known/`，`site.caddy.tpl` 在维护页之前提供它（文件不在时交给应用，404）。
  平台 17l 合入后改用平台的，删掉这一段。
- workflow `quizbuks-mobile.yml`：PR 与推送时一致性测试、assetlinks 测试、Caddy 站点校验、未签名的 `bundleRelease` 并检查合并后的 manifest；
  手动运行（只在 `1.3/quizbuks`，Environment `play`）以上传密钥签名，存为产物或以 `scripts/play-upload.mjs`（Play Developer API 的 edits，服务账号的 JWT 由 Node 自己签，不用第三方 action）上传到 `internal` / `alpha`。
- 文档：`deploy/quizbuks/DEPLOY.md`"Android App 的 assetlinks"与"构建与上传"、`launch-checklist.md`、`backend/quizbuks/CLAUDE.md` 第 5 节。

## 3. 测试

- App：`shell.test.ts`（参数与 referrer、站内跳转后仍记得、别的 App 的 referrer 不算、存储被禁时首页仍知道）、`install.test.tsx`（外壳中三处都不显示）。
- `quizbuks-mobile/android/scripts/*.test.mjs`：外壳与 App 一致；上传脚本的参数检查与 JWT 签名。
- `tools/quizbuks/test/assetlinks.test.sh`：两个指纹、大小写、错误的包名与指纹不输出。
- CI：Caddy 站点 `caddy validate`；构建并检查 manifest（`pathPrefix`、没有通知服务）。

## 4. 需求方在 Google 后台要做的（按顺序）

1. **Play Console 开发者账号**（个人，$25）：注册并完成身份验证（可能要几天）。
2. **找至少 12 名封闭测试者**：收集他们的 Google 账号邮箱，或建一个 Google 群组。
3. **新建 App**：名称 QuizBuks、应用、免费；Play App Signing 保持开启（缺省）。
4. **上传密钥**：在自己的电脑上按 `quizbuks-mobile/android/README.md`"上传密钥"生成；GitHub → Settings → Environments 新建 `play`，填入四个 `ANDROID_UPLOAD_*` secrets（`DEPLOY.md`"Android App 的构建与上传"）。
5. **第一个包**：Actions → QuizBuks mobile → Run workflow（分支 `1.3/quizbuks`，track `none`），下载产物中的 `.aab`，在 Play Console"测试 → 内部测试"手动建版本上传（新 App 的第一次上传 API 做不了）。
6. **指纹**：Play Console → 应用完整性 → 应用签名，复制"应用签名密钥证书"与"上传密钥证书"的 SHA-256；在 GitHub 的 Environment `production` 设变量
   `QUIZBUKS_ANDROID_PACKAGE=com.quizbuks.app` 与 `QUIZBUKS_ANDROID_CERT_SHA256=<两个指纹，逗号分隔>`，部署 production 一次（部署切到 1.3 之后，规划第 0 步），按 `DEPLOY.md` 确认。
7. **自动上传**：Google Cloud 建项目与服务账号、生成 JSON 密钥（启用 Google Play Android Developer API）；Play Console"用户和权限"邀请该服务账号，只授予本 App 的发布权限；JSON 放入 secret `PLAY_SERVICE_ACCOUNT_JSON`。
   之后 Run workflow 选 `internal` 或 `alpha`（App 还从未发布过时 status 选 `draft`，在 Console 中发布）。
8. **封闭测试**：建"封闭测试"轨道（`alpha`）、加入测试者；填完开始测试所必需的商店信息（隐私政策网址、内容分级、数据安全表、目标受众；完整资料在 Q49d）；发布后开始计 14 天，期间测试者须保持加入。
9. 在真机上按 `launch-checklist.md`"Android App（Q49b）"确认。

## 5. 状态

实现中（本分支）。17l 合入后：`assetlinks.json` 改由平台提供，安装提示的隐藏改用平台的判断（若平台提供）。
