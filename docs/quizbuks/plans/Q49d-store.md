# Q49d 上架资料（2026-10-28 确认）

规划：`Q49-mobile-apps.md`。分两步：**Q49d-1 Google Play**（现在，Android 封闭测试开始前必须填完）与 **Q49d-2 App Store**（§4：隐私标签、年龄分级、审核说明、iPhone 尺寸的截图）。
分支 `1.3/quizbuks-49d-store`；只有文档、脚本与测试，不改 App 与后端。

## 1. 决定（2026-10-28）

| # | 决定 |
|---|---|
| D-Q49d-1 | 目标年龄 **18 岁以上**（不适用"家庭"政策） |
| D-Q49d-2 | 商店名称：英文 `QuizBuks`；日文 `QuizBuks – クイズで特典`、中文 `QuizBuks – 答题拼好价`（2026-10-28 改：推广期没有 Kudos 的提现，名称不带 Kudos） |
| D-Q49d-3 | 类别 **购物**（需求方问"广告宣传"：两个商店都没有这个类别；商家出资、消费者得奖励的 App 在购物类。Console 中一个下拉框，可随时改） |
| D-Q49d-4 | 截图由脚本生成，**不提交进仓库**：workflow 的产物 |
| D-Q49d-5 | 分两步（上文） |
| D-Q49d-6 | **商店资料按推广期写**（`operations.md`"阶段一"，Q28）：奖励只有门店领取的奖品，没有现金奖励、转账与提现，不申报金融功能与用户付款信息；说明中不出现现金、提现等词（测试检查）。开始现金奖励前，商店说明、数据安全表与金融功能声明一起改 |
| D-Q49d-7 | **发布一律在发布前审核**，推广期同样（`qb.review.publication.auto = false`）；**不允许酒类**（以及烟草、毒品）的问卷，审核时拒绝；内容分级的"受管制物质"答"否" |

## 2. Q49d-1 的交付物

- `docs/quizbuks/store/play-listing.{ja,zh,en}.md`：名称、简短说明、完整说明。只写答题者的 App 的推广期（D-Q49d-6）；末尾写明 Google 不是奖励的主办方（竞赛政策，Q49a 的规则页之前的做法）。
  测试 `tools/quizbuks/store/listing.test.mjs`（CI `web`）：三种语言节与段落一致、Play 的长度上限、禁用词（商家后台、充值、赚钱、排名）、18 岁与 Google 一句都在。
- `docs/quizbuks/store/play-data-safety.md`：数据安全表逐格对到隐私政策；删除账号的网址在 Q49a 之前填隐私政策的地址。
- `docs/quizbuks/store/play-content-rating.md`：IARC 问卷、目标受众（18+）、广告、金融功能等声明。
- `docs/quizbuks/store/play-review.md`：应用访问权限（审核账号只在 Console 中）、竞赛与 UGC 政策、发布前的核对。
- 截图：`quizbuks-web/e2e/store/screenshots.spec.ts`（`pnpm screenshots`，`store.config.ts`）对打包的 jar：每种语言一个商家发布三份该语言的问卷，
  奖励为门店领取的奖品（`OFFLINE_REDEEM`，推广期唯一的方式）；每种商店的手机各一个新的答题者，先以 API 答一份（历史），再在手机中答另一份：
  Android（360×640，3 倍，Chromium，`?shell=android` 即商店 App）→ `<语言>/android/` 6 张 1080×1920；iPhone 6.9 英寸（440×956，3 倍，WebKit，`?shell=ios` 与 iOS App 的 User-Agent，Q49d-2）→ `<语言>/iphone/` 6 张 1320×2868。
  都是 JPEG（介绍、首页、问卷、答题中、结果与奖品、历史）；Play 的宣传图 1024×500 在 `<语言>/feature-graphic.jpg`；高清图标即 `quizbuks-web/app/public/icon-512.png`。
  workflow `quizbuks-store.yml`：手动运行，以及改了截图脚本的 PR；启动方式同 e2e 作业，产物 `quizbuks-store-screenshots` 保留 30 天。
  问卷的内容在 `store/content.ts`（事实已核对，截图是公开的）。

## 3. 需求方要做的（Play Console，按顺序）

1. 发布隐私政策（填好【】占位，`legal/README.md`）。
2. 应用内容：按 `play-data-safety.md`、`play-content-rating.md`、`play-review.md` 填写；审核账号在 production 注册（邮箱已验证），只填在 Console 中。
3. 推广期的发布审核：`qb.review.publication.auto` 保持 `false`（`operations.md`"阶段一"），审核员按 D-Q49d-7 拒绝酒类等问卷。
4. 商店信息：三种语言的文字照 `play-listing.*.md` 粘贴；Actions → QuizBuks store → Run workflow（分支 `1.3/quizbuks`），下载产物，上传 `<语言>/android/` 的截图、宣传图与图标。类别选"购物"，联系邮箱 `quizbuks@gmail.com`。
5. 封闭测试轨道发布（`Q49b-android.md` §4 第 8 步）。

## 4. Q49d-2 App Store（2026-10-28 确认，分支 `1.3/quizbuks-49d-appstore`）

- `docs/quizbuks/store/appstore-listing.{ja,zh,en}.md`：名称（≤30）、副标题（≤30）、宣传文本（≤170）、描述（≤4000，与 Play 的完整说明相同，只把 Google 换成 Apple：审核指南 5.3.3）、关键词（≤100，逗号分隔、无空格、不重复名称）。
  `listing.test.mjs` 对两个商店检查长度、三语同构、禁用词、"不是赞助方"一句，以及两边的长说明除商店名外相同。
- `appstore-privacy.md`：App 隐私（隐私标签）逐项对到隐私政策，与 `play-data-safety.md` 一起改；推广期不申报财务信息，推送（17k）后补设备 ID。
- `appstore-review.md`：提交审核的前提（删除账号、Apple 登录与系统浏览器登录、推送、规则页、举报：Q49a、Q49c-2 与平台 17j–17l）、年龄分级（内容都答"无"，覆盖为 18+）、审核备注、销售范围（去掉中国大陆）、出口合规与截图。
- 截图：上文的 `<语言>/iphone/`。

需求方要做的（App Store Connect，**会员与 App 建好之后**，`Q49c-ios.md` §4）：

1. App 信息：三种语言的名称与副标题，类别"购物"；年龄分级按 `appstore-review.md` §2（覆盖为 18+）。
2. App 隐私：按 `appstore-privacy.md` 填写，隐私政策网址同 Play。
3. 版本信息：三种语言的宣传文本、描述、关键词照 `appstore-listing.*.md`；截图上传 `<语言>/iphone/`；支持网址可填招商站的联系页或 App 的帮助（`/taker/help`）。
4. 价格与销售范围：免费，去掉中国大陆。
5. `appstore-review.md` §1 的前提都完成之前只做 TestFlight 的内部测试。

## 5. 状态

Q49d-1 已完成（staunch0515/jabiz#300、#303）；Q49a 合入后：删除账号的网址、规则页与举报入口在 App 中可达的核对（`play-review.md` §2–§3）。Q49d-2 实现中（本分支）。
