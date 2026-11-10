# 帮助文案：发起方页面的菜单（F-F3）

给发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
Q109 的使用手册（F-C10）建立时，把下面的"帮助页"一节原样放进发起方的手册（`zh` / `ja` / `en` 各一份）。用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。

- 对象：发起方（FAQ 的 `audience` = `SPONSOR`）
- 分类：`ACCOUNT`
- 页面：发起方页面的菜单（`quizbuks-web/sponsor/src/shell/areas.ts`）
- 参数（写进文案的是缺省值，改了参数时同步改这里）：手机底部菜单 `qb.sponsor.bottom-bar` = 首页、发布、兑换、消息

## 帮助页

### 中文 · 菜单在哪里

**菜单**：左侧是"首页"和 8 个区域：Quiz、发布、奖品与兑换、客户与消息、合作权益（只在你有权益角色时出现；没有时，"发起方中心 → 概况"中的"合作权益"卡片带你去看怎样开通）、资金、资源库、发起方中心。每个区域里的页面在标题下的一排标签中切换，每个标签都有自己的地址，可以加书签。屏幕宽时，左侧菜单可以收成一列图标。

**首页的"待我处理"**：首页的问候下面列出现在要你去做的事，每一条是数目和一句话，点一下就到处理它的页面：待确认的领取（奖品与兑换 → 到店兑换）、待发送或有争议的电子奖品（奖品与兑换 → 电子奖品）、有学者新消息的对话（客户与消息 → 对话）、预算不足的发布和审核中的发布（发布）、等待充值的奖励（资金 → 学分钱包）、逾期未付的账单和平台费账户不够支付的账单（资金 → 账单）、即将到期的赠送额度（资金 → 平台费账户）、审核中的公开主页（发起方中心 → 公开主页）、审核中的共享视频和等你确认的我的视频使用台账（资源库 → 素材）、发起方中心里还没填完的部分（发起方中心 → 概况）。没有的事不显示；都处理完了会告诉你。"奖品与兑换"在菜单里的数字是待确认的领取与待发送、有争议的电子奖品之和。通过入驻审核前，首页只有问候和"待我处理"（申请的审核、还没填完的资料）。

**提交后的发布**：发布的详情页有七个标签，每个都有自己的地址，可以加书签：概况（状态、日期、入场费收入；右侧是状态与下一步：暂停、复制、预览、分享、去交易与结构图）、题目与版本（Quiz 版本与换版本）、受众（地区、受众条件、邀请）、奖励（档位、推荐、电子奖品码池）、费用（共享视频的曝光与台账）、统计、分享与二维码。草稿仍用向导打开，没有这些标签。预览页的学者页面分"说明、奖励、资料、规则"四个标签，与 App 一致。

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

**メニュー**：左側に「ホーム」と 8 つのエリアがあります：クイズ、公開、景品と引き換え、お客様とメッセージ、協賛特典（特典の権限があるときだけ表示。ないときは「主催者センター → 概要」の「協賛特典」カードから開きかたを確認できます）、資金、リソースライブラリ、主催者センター。各エリアのページは、タイトルの下に並ぶタブで切り替えます。タブにはそれぞれのアドレスがあり、ブックマークできます。画面が広いときは、左のメニューをアイコンだけの列にたためます。

**ホームの「対応が必要なこと」**：ホームのあいさつの下に、今あなたが対応することが並びます。一つひとつが件数と一文で、押すとそれを処理するページにそのまま移動します。確認待ちの受け取り（景品と引き換え → 店頭での引き換え）、送付待ちまたは異議ありのデジタル賞品（景品と引き換え → デジタル賞品）、テイカーから新しいメッセージが届いたやり取り（お客様とメッセージ → やり取り）、予算が足りない公開と審査中の公開（公開）、チャージ待ちの報酬（資金 → クドスウォレット）、支払期限を過ぎた請求書とプラットフォーム利用料アカウントでは支払えない請求書（資金 → 請求書）、まもなく期限が切れる付与クレジット（資金 → プラットフォーム利用料アカウント）、審査中の公開ページ（主催者センター → 公開ページ）、審査中の共有動画と確認待ちの自分の共有動画の利用台帳（リソースライブラリ → 素材）、主催者センターで未入力の項目（主催者センター → 概要）。対応することがないものは表示されず、すべて済んだときはそう表示されます。メニューの「景品と引き換え」の数字は、確認待ちの受け取りと、送付待ちまたは異議ありのデジタル賞品の合計です。申請が承認されるまで、ホームにはあいさつと「対応が必要なこと」（申請の審査、未入力の項目）だけが表示されます。

**提出後の公開**：公開の詳細ページには 7 つのタブがあり、それぞれ専用のアドレスがあるのでブックマークできます。概要（状態、日付、参加費の収入。右側に状態と次の操作：一時停止、コピー、プレビュー、共有、取引と知識マップへの移動）、クイズとバージョン（クイズのバージョンと切り替え）、対象（地域、対象の条件、招待）、報酬（報酬の段階、紹介、電子賞品のコード）、費用（共有動画の表示回数と台帳）、統計、共有とQRコード。下書きはこれまでどおりウィザードで開き、これらのタブはありません。プレビューのテイカー向けページも、アプリと同じ「説明・報酬・資料・ルール」の 4 つのタブに分かれます。

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

**The menu**: on the left, "Home" and eight areas: Quizzes, Publications, Prizes, Customers & messages, Sponsored benefits (only when you hold a benefit role; until then, the "Sponsored benefits" card on "Sponsor hub → Overview" shows how to open them), Money, Asset library and Sponsor hub. The pages of an area are the tabs under its title; each tab has an address of its own, so you can bookmark it. On a wide screen the menu folds into a column of icons.

**"To do" on the Home page**: under the greeting, the Home page lists what needs you now, each as a number and a line that takes you straight to the page that handles it: prize claims to confirm (Prizes → In-store prizes), digital prizes to send or in dispute (Prizes → Digital prizes), conversations with a new message from a Taker (Customers & messages → Conversations), publications short of budget and publications in review (Publications), rewards waiting for a top-up (Money → Kudos wallet), overdue invoices and open invoices the platform fee account cannot cover (Money → Invoices), granted credit about to expire (Money → Platform fee account), your public page in review (Sponsor hub → Public page), shared videos in review and uses of your shared videos waiting for your confirmation (Asset library → Media), and the parts of the Sponsor hub still to fill in (Sponsor hub → Overview). What has nothing to do is not shown; when everything is done, it says so. The number beside "Prizes" in the menu is the prize claims to confirm plus the digital prizes to send or in dispute. Before your application is approved, the Home page has only the greeting and the "To do" list (the review of your application and the parts still to fill in).

**A submitted publication**: its page has seven tabs, each with an address of its own that you can bookmark: Overview (status, dates, entry-fee income; on the right, the status and next steps: pause, copy, preview, share, and the links to transactions and the knowledge map), Quiz & version (the quiz version and switching it), Audience (regions, audience conditions, invitations), Rewards (tiers, referrals, the pool of digital prize codes), Fees (exposures and the ledger of a shared video), Statistics, and Share & QR code. A draft still opens in the wizard, without these tabs. The Taker's page in the preview is split into the App's four tabs: About, Reward, Materials and Rules.

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
