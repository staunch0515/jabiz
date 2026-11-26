# 帮助文案：学者 App 的底部菜单、"我的"与 Quiz 页的标签（F-F4；Q113a-2 起为底部四项、扫一扫按钮与侧边菜单）

给学者看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新发版。
用词照 `docs/quizbuks/glossary.md`（"学者 App 的区域与条目"）；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。结构与理由见 `docs/quizbuks/plans/impl/F-F4.md`。

- 名字（写进文案的是现在的叫法，改界面文字时同步改这里）：底部四项（只有图标）首页 / 搜索 / 已答 / 通知（日文 ホーム / 検索 / 回答済み / お知らせ；英文 Home / Search / Answered / Notifications）；右下的圆形按钮 扫一扫 / スキャン / Scan；左上头像打开的侧边菜单中第一项 我的 / マイページ / Me；学者在柜台出示的码：领奖码 / 引き換えパス / pass。
- 平台原则（写进文案）：所有旧的链接、书签与邮件里的地址照常可用；没有功能被删除。
- 名字（Quiz 页的标签，Q113b-2 起两个）：资料 / 规则（日文 資料 / ルール；英文 Materials / Rules），奖励在"规则"之下；作者行的按钮：主页 / ページ / Page；底部固定的按钮：开始答题 / Start the quiz。
- 内部备注（不写进 FAQ）：宽屏时底部菜单变为左侧一列（底部四项加"我的"，扫一扫是其中的黑色按钮），右侧另有搜索与待领奖品；"证书"一个数字是此刻有效的证书个数（F-S5b：节点证书与仍有效的旧证书，撤销了的不算；列表里撤销的照旧列出并标"已收回"），点开是"证书与你的公开页"：每张证书可下载 PDF（第一次下载时签发）、复制核实链接、停用与恢复分享。怎样核实证书见 `knowledge-map.md` 第三部分。

## 一、给学者（FAQ 的 `audience` = `TAKER`，分类 `ANSWERING`）

### 中文 · 底部的四个图标、右下的圆形按钮和左上的头像各做什么？

- **首页**：可以马上答的 Quiz，右上角可以筛选；
- **搜索**：按名称、发起方或说明中的词搜索 Quiz；
- **已答**：你答过的 Quiz 和结果；旁边的"待领奖"标签列出还没领取、还要确认的奖品，有数字时表示有奖品在等你；
- **通知**：收件箱，以及和发起方的对话（有未读时显示数字）；
- 右下的圆形按钮是**扫一扫**：扫发起方店里的码；到店领奖时，点"出示领奖码"给店员看；
- 左上的**头像**打开侧边菜单：**我的主页**（学分、待领奖品、证书三个数字，你拥有的东西和设置）、学分、奖品、证书、知识结构图、推荐朋友、设置，下面是发起方账号（是发起方时）、店员柜台（是店员时）、帮助和退出登录。

### 中文 · 到店领奖时，怎样出示领奖码？

点右下的圆形按钮"扫一扫"（在首页、搜索、已答、通知的页面上都有）。如果只有一个奖品等你到店领取，页面上方的"出示领奖码（1）"按钮一点就会显示它的领奖码（二维码和六位数字），请店员扫码或输入数字。有几个奖品时，会打开"出示领奖码"标签，里面每个奖品都有自己的领奖码。不需要相机，也不需要先扫任何码。领奖码一分钟有效，过期后点"换一个新码"。

### 中文 · 待领奖的奖品在哪里看？

在"已答"的"待领奖"标签里，包括到店领取的、发起方用现金支付的和电子奖品。点一行可以打开那次答题的结果，在那里确认收到、打开电子奖品或报告问题。"我的"最上面的"待领奖品"数字、侧边菜单里的数字和底部"已答"上的数字是同一个数。

### 中文 · 在哪里退出登录、切换浅色与深色外观？旧的链接还能用吗？

退出登录在侧边菜单（点左上的头像）和"我的"页的最下方。外观在"我的 → 设置 → App 设置 → 外观"，可以选浅色、深色或跟随系统。"扫一扫"是右下的圆形按钮，"我的推荐"在侧边菜单和"我的 → 我拥有的 → 推荐"。你保存的书签、旧邮件里的链接都照常可用。

### 中文 · Quiz 页上有什么？"资料"和"规则"各是什么？"开始答题"在哪里？

打开一个 Quiz，最上面是发起方（点"主页"去发起方的主页），下面是 Quiz 的名称和介绍、视频或封面，然后一行写着发布了多久、多少人已答和截止日期，再下面一行是奖励、题数、时限和合格线。再往下是两个标签：**资料**（发起方提供的资料、商品和发起方的主页）、**规则**（题数、合格线、时限、参加费、截止时间；其下是**奖励**：奖励怎么发、各分数档能得多少、推荐奖励；最后是比赛规则、举报和屏蔽）。"开始答题"始终固定在屏幕下方（手机上在底部菜单之上），换哪个标签都在，不会盖住内容。分享给别人的链接照旧；想直接给别人看规则与奖励，把地址末尾加上 `?tab=rules` 即可（以前的 `?tab=reward` 也照样打开这里）。

### 日本語 · 下の 4 つのアイコン、右下の丸いボタン、左上のアイコンは、それぞれ何をするところ？

- **ホーム**：今すぐ答えられるクイズです。右上で絞り込みができます。
- **検索**：名前、主催者、説明の言葉でクイズを検索します。
- **回答済み**：答えたクイズとその結果です。隣の「受け取り待ち」タブには、まだ受け取っていない景品や確認が必要な景品が並びます。数字があるときは、景品があなたを待っています。
- **お知らせ**：受信箱と、主催者との会話です。未読があると数字が出ます。
- 右下の丸いボタンは**スキャン**：主催者のお店のコードを読み取ります。お店で景品を受け取るときは「引き換えパスを表示」を押してスタッフに見せます。
- 左上の**アイコン**でサイドメニューが開きます：**マイページ**（クドス・受け取り待ちの景品・証明書の 3 つの数字と、持っているもの、設定）、クドス、景品、証明書、知識マップ、友だちを紹介、設定。その下に主催者アカウント（主催者のとき）、スタッフカウンター（スタッフのとき）、ヘルプ、ログアウトがあります。

### 日本語 · お店で景品を受け取るとき、引き換えパスはどう見せる？

右下の丸いボタン「スキャン」を押します（ホーム・検索・回答済み・お知らせのページにあります）。お店で受け取る景品が 1 つなら、上の「引き換えパスを表示（1）」ボタンを押すだけで引き換えパス（QR コードと 6 桁の数字）が出ます。スタッフに読み取ってもらうか、数字を入力してもらってください。景品が複数あるときは「引き換えパスを表示」タブが開き、景品ごとに引き換えパスが出ます。カメラは使わず、先に何かを読み取る必要もありません。引き換えパスは 1 分間有効で、切れたら「新しいパスを表示」を押します。

### 日本語 · 受け取り待ちの景品はどこで見られる？

「回答済み」の「受け取り待ち」タブです。お店で受け取る景品、主催者から現金で支払われる景品、デジタル景品が並びます。行を押すとその回答の結果が開き、受領の確認、デジタル景品を開く、問題の報告ができます。「マイページ」の上の「受け取り待ちの景品」の数字、サイドメニューの数字、下の「回答済み」の数字は同じものです。

### 日本語 · ログアウトや、ライト／ダークの切り替えはどこ？ 古いリンクは使える？

ログアウトはサイドメニュー（左上のアイコンを押します）と「マイページ」の一番下です。外観は「マイページ → 設定 → アプリの設定 → 外観」で、ライト・ダーク・端末に合わせるから選べます。「スキャン」は右下の丸いボタン、「紹介の一覧」はサイドメニューと「マイページ → 持っているもの → 紹介」にあります。保存したブックマークや過去のメールのリンクも、そのまま使えます。

### 日本語 · クイズのページには何がある？「資料」と「ルール」は何？「クイズを始める」はどこ？

クイズを開くと、一番上に主催者（「ページ」を押すと主催者のページへ）、その下にクイズの名前と紹介、動画または表紙、続いて公開からの時間・回答した人数・締め切りの行、さらに報酬・問題数・制限時間・合格点の行があります。その下は 2 つのタブです。**資料**（主催者の資料・商品・主催者のページ）、**ルール**（問題数・合格点・制限時間・参加費・締め切り。その下に**報酬**：報酬の支払い方、得点ごとの金額、紹介報酬。最後に公式ルール・通報・ブロック）。「クイズを始める」は画面の下に固定され（スマートフォンでは下のメニューの上）、どのタブでも見え、内容を隠しません。共有されたリンクはそのまま使えます。ルールと報酬を直接見せたいときは、アドレスの最後に `?tab=rules` を付けます（以前の `?tab=reward` もここを開きます）。

### English · What do the four icons at the bottom, the round button and the picture at the top left do?

- **Home**: the quizzes you can answer now, with filters at the top right;
- **Search**: finds quizzes by their name, their Sponsor or a word of their description;
- **Answered**: the quizzes you answered and their results; the "To collect" tab beside it lists the prizes you have not collected or still have to confirm, and a number means a prize is waiting for you;
- **Notifications**: your inbox and your conversations with Sponsors (a number shows what is unread);
- the round button at the bottom right is **Scan**: it scans a Sponsor's code at their place; to collect a prize in the shop, tap "Show my pass" and show it to the staff;
- your **picture** at the top left opens the side menu: **My page** (three numbers: Kudos, prizes to collect, certificates; what you have and your settings), Kudos, Prizes, Certificates, Knowledge maps, Refer a friend and Settings, then Sponsor account (for a Sponsor), Staff counter (for staff), Help and Sign out.

### English · How do I show my pass when I collect a prize in a shop?

Tap the round "Scan" button at the bottom right (on Home, Search, Answered and Notifications). When one prize is waiting at a Sponsor's place, the "Show my pass (1)" button at the top shows its pass (a QR code and six digits) with one tap; the staff scan it or type the digits. With several prizes, the "Show my pass" tab opens and lists a pass for each. You need no camera, and you do not have to scan anything first. A pass is good for a minute; tap "Show a new one" when it runs out.

### English · Where do I see the prizes waiting for me?

In the "To collect" tab of "Answered": prizes to collect at a Sponsor's place, prizes a Sponsor pays in cash, and digital prizes. Tap a row to open the result of that attempt, where you confirm that you received it, open a digital prize or report a problem. The "Prizes to collect" number at the top of "Me", the one in the side menu and the number on "Answered" at the bottom are the same.

### English · Where do I sign out or switch between light and dark? Do my old links still work?

Signing out is at the bottom of the side menu (tap your picture at the top left) and of "Me". The appearance is in "Me → Settings → App settings → Appearance": light, dark or following your system. "Scan" is the round button at the bottom right, and "My referrals" is in the side menu and in "Me → What I have → Referrals". Your bookmarks and the links in old mails still work.

### English · What is on a quiz page? What are "Materials" and "Rules"? Where is "Start the quiz"?

When you open a quiz, the Sponsor is at the top (tap "Page" for their page), then the quiz's name and introduction, its video or cover, a line with how long ago it went up, how many answered and when it ends, and a row with the reward, the number of questions, the time limit and the pass mark. Below are two tabs: **Materials** (the Sponsor's materials, products and page) and **Rules** (the number of questions, pass mark, time limit, entry fee and deadline; under them the **Reward**: how it is paid, what each score earns and any referral reward; last the official rules, reporting and blocking). "Start the quiz" stays at the bottom of the screen (above the bottom menu on a phone) on every tab and never covers the content. Shared links work as before; to point someone straight to the rules and the reward, add `?tab=rules` to the address (the old `?tab=reward` opens it too).
