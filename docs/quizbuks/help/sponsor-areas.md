# 帮助文案：发起方页面的菜单（F-F3）

给发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
Q109 的使用手册（F-C10）建立时，把下面的"帮助页"一节原样放进发起方的手册（`zh` / `ja` / `en` 各一份）。用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。

- 对象：发起方（FAQ 的 `audience` = `SPONSOR`）
- 分类：`ACCOUNT`
- 页面：发起方页面的菜单（`quizbuks-web/sponsor/src/shell/areas.ts`）
- 参数（写进文案的是缺省值，改了参数时同步改这里）：手机底部菜单 `qb.sponsor.bottom-bar` = 首页、发布、兑换、消息

## 帮助页

### 中文 · 菜单在哪里

**菜单**：左侧是"首页"和 8 个区域：Quiz、发布、奖品与兑换、客户与消息、合作权益（只在你有权益角色时出现）、资金、资源库、发起方中心。每个区域里的页面在标题下的一排标签中切换，每个标签都有自己的地址，可以加书签。屏幕宽时，左侧菜单可以收成一列图标。

**手机上**：底部有首页、发布、兑换、消息四项，"更多"打开完整的菜单。

**新建**：菜单最上方的"＋ 新建"在任何页面都能用：新建 Quiz、新建发布、发通知、导入发布设置、在资源库中新建。

**右上角的头像**：账户与安全、外观（浅色、深色、跟随设备）、语言、帮助、退出。

**原来的页面在哪里**：
- 概览 → 首页
- 树 → 发布 → 知识结构图
- 现金奖励、电子奖品、到店兑换 → 奖品与兑换
- 钱包 → 资金 → 学分钱包（现金账户在"平台费账户"，平台的账单在"账单"）
- 交易 → 资金 → 交易
- 消息中心 → 客户与消息（对话、通知与公告）
- 发起方资料 → 发起方中心
- 统计（主页的访问与商品点击）→ 发起方中心 → 公开主页 → 统计
- 账户、帮助 → 右上角的头像

原来的地址和书签照常可用，会自动转到新的位置。

### 日本語 · メニューの場所

**メニュー**：左側に「ホーム」と 8 つのエリアがあります：クイズ、公開、景品と引き換え、お客様とメッセージ、協賛特典（特典の権限があるときだけ表示）、資金、リソースライブラリ、主催者センター。各エリアのページは、タイトルの下に並ぶタブで切り替えます。タブにはそれぞれのアドレスがあり、ブックマークできます。画面が広いときは、左のメニューをアイコンだけの列にたためます。

**スマートフォンでは**：下部にホーム、公開、引き換え、メッセージの 4 つがあり、「その他」でメニュー全体を開きます。

**新規作成**：メニューのいちばん上の「＋ 新規作成」はどのページからでも使えます：新しいクイズ、新しい公開、お知らせを送る、公開の設定を読み込む、ライブラリに新規作成。

**右上のアバター**：アカウントとセキュリティ、表示（ライト、ダーク、端末の設定）、言語、ヘルプ、ログアウト。

**以前のページの場所**：
- 概要 → ホーム
- ツリー → 公開 → 知識マップ
- 現金報酬、デジタル賞品、賞品の引き換え → 景品と引き換え
- ウォレット → 資金 → クドスウォレット（現金口座は「プラットフォーム利用料アカウント」、プラットフォームの請求書は「請求書」）
- 取引 → 資金 → 取引
- メッセージセンター → お客様とメッセージ（やり取り、お知らせ）
- 主催者情報 → 主催者センター
- 統計（ページの訪問と商品のクリック）→ 主催者センター → 公開ページ → 統計
- アカウント、ヘルプ → 右上のアバター

以前のアドレスやブックマークもそのまま使えます。新しい場所に自動で移動します。

### English · Where things are

**The menu**: on the left, "Home" and eight areas: Quizzes, Publications, Prizes, Customers & messages, Sponsored benefits (only when you hold a benefit role), Money, Asset library and Sponsor hub. The pages of an area are the tabs under its title; each tab has an address of its own, so you can bookmark it. On a wide screen the menu folds into a column of icons.

**On a phone**: the bottom menu has Home, Publications, Redeem and Messages; "More" opens the whole menu.

**New**: "+ New" at the top of the menu works from any page: a new quiz, a new publication, sending a notice, importing publication settings, a new asset in the library.

**The avatar at the top right**: account & security, appearance (light, dark, as the device), language, help and signing out.

**Where the pages you knew went**:
- Dashboard → Home
- Tree → Publications → Knowledge maps
- Cash rewards, Digital prizes, Prizes → Prizes
- Wallet → Money → Kudos wallet (the cash account is on "Platform fee account", the platform's invoices on "Invoices")
- Transactions → Money → Transactions
- Message centre → Customers & messages (Conversations, Notices & announcements)
- Sponsor profile → Sponsor hub
- Page statistics (the page's visits and the products' clicks) → Sponsor hub → Public page → Statistics
- Account, Help → the avatar at the top right

Your old addresses and bookmarks keep working: they take you to the new place.
