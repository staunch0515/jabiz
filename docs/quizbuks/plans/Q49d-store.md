# Q49d 上架资料（2026-10-28 确认）

规划：`Q49-mobile-apps.md`。分两步：**Q49d-1 Google Play**（现在，Android 封闭测试开始前必须填完）与 **Q49d-2 App Store**（Q49c 之后：隐私标签、年龄分级、审核说明、iPhone 尺寸的截图）。
分支 `1.3/quizbuks-49d-store`；只有文档、脚本与测试，不改 App 与后端。

## 1. 决定（2026-10-28）

| # | 决定 |
|---|---|
| D-Q49d-1 | 目标年龄 **18 岁以上**（不适用"家庭"政策） |
| D-Q49d-2 | 商店名称：英文 `QuizBuks`；日文 `QuizBuks – クイズでKudos`、中文 `QuizBuks – 答题赢 Kudos` |
| D-Q49d-3 | 类别 **购物**（需求方问"广告宣传"：两个商店都没有这个类别；商家出资、消费者得奖励的 App 在购物类。Console 中一个下拉框，可随时改） |
| D-Q49d-4 | 截图由脚本生成，**不提交进仓库**：workflow 的产物 |
| D-Q49d-5 | 分两步（上文） |

## 2. Q49d-1 的交付物

- `docs/quizbuks/store/play-listing.{ja,zh,en}.md`：名称、简短说明、完整说明。只写答题者的 App；末尾写明 Google 不是奖励的主办方（竞赛政策，Q49a 的规则页之前的做法）。
  测试 `tools/quizbuks/store/listing.test.mjs`（CI `web`）：三种语言节与段落一致、Play 的长度上限、禁用词（商家后台、充值、赚钱、排名）、18 岁与 Google 一句都在。
- `docs/quizbuks/store/play-data-safety.md`：数据安全表逐格对到隐私政策；删除账号的网址在 Q49a 之前填隐私政策的地址。
- `docs/quizbuks/store/play-content-rating.md`：IARC 问卷、目标受众（18+）、广告、金融功能等声明。
- `docs/quizbuks/store/play-review.md`：应用访问权限（审核账号只在 Console 中）、竞赛与 UGC 政策、发布前的核对。
- 截图：`quizbuks-web/e2e/store/screenshots.spec.ts`（`pnpm screenshots`，`store.config.ts`）对打包的 jar：每种语言一个商家发布三份该语言的问卷，
  答题者先以 API 答一份（历史与 Kudos），再在手机（360×640，3 倍，Android 的 Chrome，`?shell=android` 即商店 App）中答另一份。
  每种语言 7 张 1080×1920 的 JPEG（介绍、首页、问卷、答题中、结果、历史、钱包）与宣传图 1024×500；高清图标即 `quizbuks-web/app/public/icon-512.png`。
  workflow `quizbuks-store.yml`：手动运行，以及改了截图脚本的 PR；启动方式同 e2e 作业，产物 `quizbuks-store-screenshots` 保留 30 天。
  问卷的内容在 `store/content.ts`（事实已核对，截图是公开的）。

## 3. 需求方要做的（Play Console，按顺序）

1. 发布隐私政策（填好【】占位，`legal/README.md`）。
2. 应用内容：按 `play-data-safety.md`、`play-content-rating.md`、`play-review.md` 填写；审核账号在 production 注册（邮箱已验证），只填在 Console 中。
3. 决定 `play-content-rating.md` 中的两个注：酒类问卷是否允许；金融功能是否申报。
4. 商店信息：三种语言的文字照 `play-listing.*.md` 粘贴；Actions → QuizBuks store → Run workflow（分支 `1.3/quizbuks`），下载产物，上传截图、宣传图与图标。类别选"购物"，联系邮箱 `quizbuks@gmail.com`。
5. 封闭测试轨道发布（`Q49b-android.md` §4 第 8 步）。

## 4. 状态

Q49d-1 实现中（本分支）。Q49a 合入后：删除账号的网址、规则页与举报入口在 App 中可达的核对（`play-review.md` §2–§3）。Q49d-2 在 Q49c 之后另写计划。
