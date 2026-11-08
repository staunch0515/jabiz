# 帮助文案：任意网址的视频（F-S15）

给发起方与学者看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。规则与运维见 `docs/quizbuks/plans/impl/F-S15.md`、`operations.md` §7.28，常见问题见 `troubleshooting.md` T15、T16。

- 参数（写进文案的网站是缺省值，改了受控参数 `qb.video.mainland-accessible-sites` 时同步改这里与 `mainland-china.md`）：中国大陆可以播放哔哩哔哩、优酷、腾讯视频，以及总部登记过的主机。

## 一、给发起方（FAQ 的 `audience` = `SPONSOR`，分类 `PUBLISHING`）

### 中文 · 视频可以放在哪里？

**视频可以放在任何网站**，在资料中填 https 地址即可，App 的首页与开始页都会播放：

- **YouTube、Vimeo、哔哩哔哩、niconico、Dailymotion、优酷、腾讯视频**：填单个视频的地址，用各自的播放器。
- **直接的视频文件**（地址以 `.mp4`、`.webm`、`.mov` 结尾，例如平台视频服务器上的文件，可以直接用平台视频服务器的链接）：**效果最好**，App 用自己的播放器，首页静音自动播放，**可以设为"看完才能答题"**。最好用 `.mp4`（H.264 + AAC，faststart）；`.webm` 在部分 iPhone 上放不了。
- **其他网页**：原样嵌入显示，但看不出学者看了多少，不能设为"看完才能答题"。很多网站不允许被嵌入，这时预览是一片空白：请改用该网站的嵌入（embed）地址（"分享 → 嵌入"中的那个地址）。

保存资料前，对话框会当场显示预览：视频文件读到时长才能设为"看完才能答题"，打不开会提示"这个视频文件打不开"。其他网站的网页在审核时会被特别检查。

**中国大陆**：只有中国大陆能访问的视频，在中国大陆的学者才能答题。要让他们看到，请把视频放在中国大陆可访问的网站（哔哩哔哩、优酷、腾讯视频，或总部登记过的服务器），并联系你的运营商：放在其他服务器上的，由运营商转请总部把这个服务器登记为"中国大陆可访问"（需要两位管理员确认，登记后新上线的发布才生效）；个别发布或发起方也可以由运营商或总部设例外。详见"含境外视频的发布与中国大陆"。

### 日本語 · 動画はどこに置けますか？

**動画はどのサイトにも置けます**。資料に https のアドレスを入力すれば、アプリのホームと開始ページで再生されます。

- **YouTube、Vimeo、bilibili、ニコニコ動画、Dailymotion、Youku、Tencent Video**：1 本の動画のアドレスを入力すると、各サイトのプレーヤーで再生されます。
- **動画ファイルそのもの**（アドレスが `.mp4`、`.webm`、`.mov` で終わるもの。プラットフォームの動画サーバーのリンクもそのまま使えます）：**いちばんきれいに再生され**、アプリのプレーヤーでホームでは音声なしで自動再生、**「見終わってから回答」にもできます**。おすすめは `.mp4`（H.264 + AAC、faststart）です。`.webm` は一部の iPhone で再生できません。
- **そのほかのページ**：そのまま埋め込んで表示しますが、どこまで見たかがわからないため「見終わってから回答」にはできません。多くのサイトは埋め込みを許可しておらず、プレビューが真っ白になります。その場合はサイトの埋め込み用（embed）のアドレス（「共有 → 埋め込む」に出てくるアドレス）を使ってください。

資料を保存する前に、ダイアログでその場でプレビューできます。動画ファイルは長さを読み取れたときだけ「見終わってから回答」にでき、開けないときは「この動画ファイルを開けません」と表示されます。ほかのサイトのページは審査で特に確認されます。

**中国本土**：中国本土からアクセスできる動画でなければ、中国本土のテイカーは回答できません。見てもらうには、中国本土からアクセスできるサイト（bilibili、Youku、Tencent Video、または本部が登録したサーバー）に置き、担当の運営パートナーに連絡してください。ほかのサーバーに置く場合は、運営パートナーを通じて本部がそのサーバーを「中国本土からアクセス可能」として登録します（管理者 2 人の確認が必要で、登録後に公開を始めたものから有効です）。個別の公開や主催者ごとに、運営パートナーまたは本部が例外を設定することもできます。詳しくは「海外の動画を含む公開と中国本土」をご覧ください。

### English · Where can my videos be?

**Your videos can be on any website**: enter an https address in the material, and the app plays it on the home page and the start page.

- **YouTube, Vimeo, Bilibili, Niconico, Dailymotion, Youku, Tencent Video**: the address of one video plays in the site's own player.
- **A video file itself** (an address ending in `.mp4`, `.webm` or `.mov`; links to the platform's video server work as they are): **plays best**, in the app's own player, muted and by itself on the home page, and **can be the video to watch before answering**. Use `.mp4` (H.264 + AAC, faststart) where you can; `.webm` does not play on some iPhones.
- **Any other page**: shown embedded as it is, but how much was watched cannot be told, so it cannot be the video to watch. Many websites do not allow embedding, and the preview stays blank: use the site's embed address instead (the one under "Share → Embed").

The material dialog previews the video before you save: a video file can be the video to watch only once its length was read, and one that cannot be opened is pointed out at once. Pages of other websites are checked closely in the review.

**Mainland China**: Takers in mainland China can answer only when the videos can be reached from there. Put the video on a site mainland China reaches (Bilibili, Youku, Tencent Video, or a server headquarters has registered) and contact your Operator: for any other server, the Operator asks headquarters to register it as reachable from mainland China (two administrators confirm it; it applies to publications that go live afterwards). The Operator or headquarters can also make an exception for a publication or a Sponsor. See "Publications with foreign videos and mainland China".

## 二、给学者（FAQ 的 `audience` = `TAKER`，分类 `ANSWERING`）

### 中文 · 视频放不了或是一片空白怎么办？

视频文件放不了时，卡片只显示封面和"打开视频"，点它可以在新标签页中直接打开视频。嵌入的其他网页是一片空白时，说明那个网站不允许嵌入，你仍然可以看资料中的其他内容；需要看完才能答题的视频总是能在 App 中播放。仍有问题请用"举报"告诉我们。

### 日本語 · 動画が再生できない、真っ白なときは？

動画ファイルが再生できないときは、カードに表紙と「動画を開く」だけが表示されます。タップすると新しいタブで動画を直接開けます。埋め込まれたほかのページが真っ白なときは、そのサイトが埋め込みを許可していないためです。資料のほかの内容はそのまま見られます。「見終わってから回答」の動画はいつもアプリで再生できます。解決しないときは「通報」でお知らせください。

### English · A video does not play, or shows nothing

When a video file does not play, the card shows its cover and "Open the video", which opens the file itself in a new tab. When an embedded page of another website stays blank, that website does not allow embedding; the rest of the materials are there as usual, and a video you must watch before answering always plays in the app. If it still does not work, tell us with "Report".
