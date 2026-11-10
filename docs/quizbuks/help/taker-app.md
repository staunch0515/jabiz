# 帮助文案：学者 App 的底部菜单与"我的"（F-F4）

给学者看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新发版。
用词照 `docs/quizbuks/glossary.md`（"学者 App 的区域与条目"）；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。结构与理由见 `docs/quizbuks/plans/impl/F-F4.md`。

- 名字（写进文案的是现在的叫法，改界面文字时同步改这里）：底部五项 首页 / 已答 / 扫一扫 / 消息 / 我的（日文 ホーム / 回答済み / スキャン / メッセージ / マイページ；英文 Home / Answered / Scan / Messages / Me）；学者在柜台出示的码：领奖码 / 引き換えパス / pass。
- 平台原则（写进文案）：所有旧的链接、书签与邮件里的地址照常可用；没有功能被删除。
- 内部备注（不写进 FAQ）：宽屏时底部菜单变为左侧一列，条目相同；"证书"一个数字待 F-S5 合并节点证书后改文案。

## 一、给学者（FAQ 的 `audience` = `TAKER`，分类 `ANSWERING`）

### 中文 · 底部的五个入口各做什么？

- **首页**：可以马上答的 Quiz，也可以搜索、筛选；
- **已答**：你答过的 Quiz 和结果；旁边的"待领奖"标签列出还没领取、还要确认的奖品，有数字时表示有奖品在等你；
- **扫一扫**（正中间）：扫发起方店里的码；到店领奖时，点"出示领奖码"给店员看；
- **消息**：收件箱，以及和发起方的对话（有未读时显示数字）；
- **我的**：学分、待领奖品、证书三个数字，你拥有的东西（奖品、证书、知识结构图、推荐、学分明细）和设置。

### 中文 · 到店领奖时，怎样出示领奖码？

打开底部正中间的"扫一扫"。如果只有一个奖品等你到店领取，页面上方的"出示领奖码（1）"按钮一点就会显示它的领奖码（二维码和六位数字），请店员扫码或输入数字。有几个奖品时，会打开"出示领奖码"标签，里面每个奖品都有自己的领奖码。不需要相机，也不需要先扫任何码。领奖码一分钟有效，过期后点"换一个新码"。

### 中文 · 待领奖的奖品在哪里看？

在"已答"的"待领奖"标签里，包括到店领取的、发起方用现金支付的和电子奖品。点一行可以打开那次答题的结果，在那里确认收到、打开电子奖品或报告问题。"我的"最上面的"待领奖品"数字和底部"已答"上的数字是同一个数。

### 中文 · 在哪里退出登录、切换浅色与深色外观？旧的链接还能用吗？

退出登录在"我的"页的最下方。外观在"我的 → 设置 → App 设置 → 外观"，可以选浅色、深色或跟随系统。原来右上角菜单里的"扫一扫"和"我的推荐"分别在底部菜单和"我的 → 我拥有的 → 推荐"。你保存的书签、旧邮件里的链接都照常可用。

### 日本語 · 下のメニューの 5 つは、それぞれ何をするところ？

- **ホーム**：今すぐ答えられるクイズです。検索や絞り込みもできます。
- **回答済み**：答えたクイズとその結果です。隣の「受け取り待ち」タブには、まだ受け取っていない景品や確認が必要な景品が並びます。数字があるときは、景品があなたを待っています。
- **スキャン**（まん中）：主催者のお店のコードを読み取ります。お店で景品を受け取るときは「引き換えパスを表示」を押してスタッフに見せます。
- **メッセージ**：受信箱と、主催者との会話です。未読があると数字が出ます。
- **マイページ**：クドス・受け取り待ちの景品・証明書の 3 つの数字と、持っているもの（景品、証明書、知識マップ、紹介、クドスの詳細）、設定があります。

### 日本語 · お店で景品を受け取るとき、引き換えパスはどう見せる？

下のまん中の「スキャン」を開きます。お店で受け取る景品が 1 つなら、上の「引き換えパスを表示（1）」ボタンを押すだけで引き換えパス（QR コードと 6 桁の数字）が出ます。スタッフに読み取ってもらうか、数字を入力してもらってください。景品が複数あるときは「引き換えパスを表示」タブが開き、景品ごとに引き換えパスが出ます。カメラは使わず、先に何かを読み取る必要もありません。引き換えパスは 1 分間有効で、切れたら「新しいパスを表示」を押します。

### 日本語 · 受け取り待ちの景品はどこで見られる？

「回答済み」の「受け取り待ち」タブです。お店で受け取る景品、主催者から現金で支払われる景品、デジタル景品が並びます。行を押すとその回答の結果が開き、受領の確認、デジタル景品を開く、問題の報告ができます。「マイページ」の上の「受け取り待ちの景品」の数字と、下の「回答済み」の数字は同じものです。

### 日本語 · ログアウトや、ライト／ダークの切り替えはどこ？ 古いリンクは使える？

ログアウトは「マイページ」の一番下です。外観は「マイページ → 設定 → アプリの設定 → 外観」で、ライト・ダーク・端末に合わせるから選べます。以前、右上のメニューにあった「スキャン」は下のメニューに、「紹介の一覧」は「マイページ → 持っているもの → 紹介」にあります。保存したブックマークや過去のメールのリンクも、そのまま使えます。

### English · What do the five entries at the bottom do?

- **Home**: the quizzes you can answer now, with search and filters;
- **Answered**: the quizzes you answered and their results; the "To collect" tab beside it lists the prizes you have not collected or still have to confirm, and a number means a prize is waiting for you;
- **Scan** (in the middle): scans a Sponsor's code at their place; to collect a prize in the shop, tap "Show my pass" and show it to the staff;
- **Messages**: your inbox and your conversations with Sponsors (a number shows what is unread);
- **Me**: three numbers (Kudos, prizes to collect, certificates), what you have (prizes, certificates, knowledge maps, referrals, Kudos details) and your settings.

### English · How do I show my pass when I collect a prize in a shop?

Open "Scan" in the middle of the bottom menu. When one prize is waiting at a Sponsor's place, the "Show my pass (1)" button at the top shows its pass (a QR code and six digits) with one tap; the staff scan it or type the digits. With several prizes, the "Show my pass" tab opens and lists a pass for each. You need no camera, and you do not have to scan anything first. A pass is good for a minute; tap "Show a new one" when it runs out.

### English · Where do I see the prizes waiting for me?

In the "To collect" tab of "Answered": prizes to collect at a Sponsor's place, prizes a Sponsor pays in cash, and digital prizes. Tap a row to open the result of that attempt, where you confirm that you received it, open a digital prize or report a problem. The "Prizes to collect" number at the top of "Me" and the number on "Answered" at the bottom are the same.

### English · Where do I sign out or switch between light and dark? Do my old links still work?

Signing out is at the bottom of "Me". The appearance is in "Me → Settings → App settings → Appearance": light, dark or following your system. "Scan" and "My referrals", which were in the menu at the top right, are now in the bottom menu and in "Me → What I have → Referrals". Your bookmarks and the links in old mails still work.
