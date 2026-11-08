# 帮助文案：含境外视频的发布与中国大陆（F-S11）

给学者与发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。规则与运维见 `docs/quizbuks/plans/impl/F-S11.md`、`operations.md` §7.24。

- 参数（写进文案的网站是缺省值，改了受控参数 `qb.video.mainland-accessible-sites` 时同步改这里）：中国大陆可以播放哔哩哔哩、优酷、腾讯视频。

## 一、给学者（FAQ 的 `audience` = `TAKER`，分类 `ANSWERING`）

### 中文 · 为什么在中国大陆看不到某些 Quiz，或打开时说"该内容在你所在的地区不可用"？

有些 Quiz 用了在中国大陆无法播放的视频（例如 YouTube）。从中国大陆访问时，这些 Quiz 不会出现在首页和发起方的主页上；从通知或链接打开时，页面会说明"该内容在你所在的地区不可用"，不能开始作答。
所在地区按你访问时的网络（IP 地址）判断，与"关于我"里填写的国家无关。离开中国大陆后再打开就可以正常作答。已经答完的 Quiz 照常可以查看结果。
IP 地址的地区判断使用 DB-IP（https://db-ip.com）的数据，CC BY 4.0（也列在"关于本 App"中）。

### 日本語 · 中国本土で一部のクイズが見えない、または「このコンテンツは、いまいる地域ではご利用いただけません」と表示されるのはなぜですか？

一部のクイズには、中国本土では再生できない動画（YouTube など）が使われています。中国本土からアクセスすると、これらのクイズはホームや主催者のページに表示されません。お知らせやリンクから開いた場合は「このコンテンツは、いまいる地域ではご利用いただけません」と表示され、回答を始めることはできません。
地域はアクセスしたときのネットワーク（IP アドレス）で判定し、「プロフィール」に入力した国とは関係ありません。中国本土の外から開けば通常どおり回答できます。回答済みのクイズの結果はいつでも見られます。
IP アドレスによる地域の判定には DB-IP（https://db-ip.com）のデータ（CC BY 4.0）を使用しています（「このアプリについて」にも記載）。

### English · Why can't I see some quizzes in mainland China, or why does a page say "This content is not available in your region"?

Some quizzes use a video that cannot be played in mainland China (such as YouTube). From mainland China, these quizzes are not listed on the home page or on a Sponsor's page; opened from a notice or a link, the page says "This content is not available in your region" and the quiz cannot be started.
Your region is judged by the network you are using (your IP address), not by the country in your profile. Open the quiz again from outside mainland China and you can answer it as usual. Results of quizzes you have answered stay available.
The region of an IP address is determined with data from DB-IP (https://db-ip.com), licensed under CC BY 4.0 (also listed under "About the app").

## 二、给发起方（FAQ 的 `audience` = `SPONSOR`，分类 `PUBLISHING`）

### 中文 · "中国大陆看不到"是什么意思？

Quiz 的资料里有在中国大陆无法播放的视频（YouTube、Vimeo、niconico、Dailymotion，或认不出来的视频网站）时，中国大陆的学者看不到、也不能作答这个发布。中国大陆可以播放的是哔哩哔哩、优酷、腾讯视频；Quiz 其他语言的资料里的视频同样算。
发布的受众包括中国大陆（地区选了"全球""亚洲"或"中国"，或者是邀请制）时，向导的受众一步、提交前的检查和发布页会提示"中国大陆看不到"。这只是提示，仍然可以提交，其他地区照常作答。
想让中国大陆的学者也能作答，把视频换成哔哩哔哩、优酷或腾讯视频的视频，保存新版本后切换发布的版本即可。确有需要的，可以联系负责你的运营商或平台说明理由，由他们设置例外（可以设到期日）。

### 日本語 · 「中国本土では表示されません」とはどういう意味ですか？

クイズの資料に中国本土で再生できない動画（YouTube、Vimeo、niconico、Dailymotion、または判別できない動画サイト）が含まれていると、中国本土のテイカーはその公開を見ることも回答することもできません。中国本土で再生できるのは bilibili、Youku、Tencent Video です。クイズのほかの言語の資料に含まれる動画も同じ扱いです。
公開の対象に中国本土が含まれる場合（地域で「グローバル」「アジア」「中国」を選んだ場合、または招待制の場合）、公開の作成画面の対象のステップ、申請前の確認、公開のページに「中国本土では表示されません」と表示されます。お知らせだけで、そのまま申請でき、ほかの地域では通常どおり回答できます。
中国本土のテイカーにも回答してもらいたいときは、動画を bilibili、Youku、Tencent Video のものに替えて新しいバージョンを保存し、公開のバージョンを切り替えてください。特別な事情があるときは、理由を添えて担当の運営パートナーまたはプラットフォームにご相談ください。例外を設定できます（最終日を決めることもできます）。

### English · What does "mainland China cannot see this" mean?

When a quiz's materials include a video that cannot be played in mainland China (YouTube, Vimeo, niconico, Dailymotion, or a video site that is not recognised), Takers in mainland China can neither see nor answer the publication. Mainland China plays Bilibili, Youku and Tencent Video; videos in the quiz's other languages count too.
When a publication reaches mainland China (the regions include Global, Asia or China, or it is by invitation), the audience step of the wizard, the check before submitting and the publication's page say so. It is only a notice: you can still submit, and Takers elsewhere answer as usual.
To let Takers in mainland China answer, use a video of Bilibili, Youku or Tencent Video instead, save a new version and switch the publication to it. If there is a special reason, contact your operator or the platform and explain it: they can make an exception (perhaps until a given day).
