# 帮助文案：共享视频的曝光（F-S13）

给学者与发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。规则与运维见 `docs/quizbuks/plans/impl/F-S13.md`、`operations.md` §7.31。

- 参数（写进文案的是缺省值，改了参数时同步改这里）：连续看 10 秒算一次（`qb.exposure.seconds`）；竖屏模式中每 5 屏一个推送（`qb.exposure.push.every`）；每位学者每天（日本时间）至多计 200 次（`qb.exposure.daily-cap-per-user`）。
- 内部备注（不写进 FAQ）：购买曝光、追加购买与用完前的提醒随 F-M8；在那之前发起方页面只显示"不限"与已成功次数。

## 一、给学者（FAQ 的 `audience` = `TAKER`，分类 `ANSWERING`）

### 中文 · 竖屏模式里为什么会出现我没点的 Quiz？

在"短视频"的全屏竖屏模式中，每滑几屏会出现一个用了其他发起方共享视频的 Quiz（推送），几家发起方轮流出现。打开过一次的推送不会再推给你。你屏蔽的内容（发布、Quiz、发起方、视频制作方）和打开"只看喜欢的主题"后不符合的 Quiz 都不会被推送：付多少钱都越不过你的设置。首页的内容不变。

### 日本語 · 縦型モードに、自分で選んでいないクイズが出てくるのはなぜですか？

「ショート動画」の全画面の縦型モードでは、何画面かごとに、ほかの主催者が共有した動画を使っているクイズ（プッシュ）が表示されます。複数の主催者が順番に表示されます。一度開いたプッシュは、あなたにはもう表示されません。あなたがブロックしたもの（公開・クイズ・主催者・動画の提供者）や、「好きなトピックだけ表示」をオンにしたときに合わないクイズは、プッシュされません。いくら支払ってもあなたの設定は越えられません。ホームの内容は変わりません。

### English · Why does the vertical mode show quizzes I did not tap?

In the full-screen vertical mode of "Shorts", every few screens a quiz using a video another Sponsor shared appears (a push), several Sponsors taking turns. A push you opened once is not shown to you again. Nothing you blocked (a quiz, every round of a quiz, a Sponsor, a video provider) and, with "Only topics I like" on, no quiz outside your topics is pushed: no payment gets past your settings. Your home page stays as it is.

## 二、给发起方（FAQ 的 `audience` = `SPONSOR`，分类 `PUBLISHING`）

### 中文 · 用了共享视频的发布，曝光怎么计？

你的发布用了其他发起方共享的视频时，学者看得够久即计一次曝光：
- **竖视频**：在 App 的竖屏模式中连续播放 10 秒（视频不到 10 秒时看完即算）。你的发布在那里作为推送出现，和其他发起方轮流；首页一排的预览不计。
- **横视频**：在开始答题页，视频在画面上且 App 在前台连续 10 秒。首页的自动播放不计。

每位学者每个发布至多计一次；你自己、视频的提供方和你们的店员看不计；每位学者每天有上限。用你自己的视频（自己的资料）不计曝光。
发布页"共享视频的曝光"一节显示已购次数（没有购买时为"不限"）、已成功、计费、推送被打开与去答题的人数。买的次数用完后，发布会在一分钟内自动结束（结束原因"曝光已用完"），已经开始的答题照常完成，你会收到邮件；同时可能多计几次，超出已购的不收费。

### 日本語 · 共有動画を使った公開の表示回数はどう数えますか？

ほかの主催者が共有した動画をあなたの公開で使うと、テイカーが十分な時間見たときに 1 回の表示としてカウントします。
- **縦型の動画**：アプリの縦型モードで 10 秒続けて再生されたとき（10 秒未満の動画は最後まで再生されたとき）。あなたの公開はそこでプッシュとして、ほかの主催者と順番に表示されます。ホームの一列のプレビューはカウントしません。
- **横型の動画**：クイズの開始ページで、動画が画面に表示され、アプリが前面にある状態で 10 秒続いたとき。ホームの自動再生はカウントしません。

テイカー 1 人につき 1 つの公開で 1 回までです。あなた自身、動画の提供者、それぞれのスタッフの視聴はカウントしません。テイカーごとに 1 日の上限があります。あなた自身の動画（自分の資料）は表示回数をカウントしません。
公開のページの「共有動画の表示回数」に、購入した回数（購入していないときは「上限なし」）、カウント済み、課金の対象、開かれたプッシュ、回答に進んだ人数を表示します。購入した回数を使い切ると、公開は 1 分以内に自動で終了し（終了の理由「表示回数を使い切り」）、始まっている回答は通常どおり最後までできます。メールでお知らせします。終了までの間に少し多くカウントされることがありますが、購入した回数を超えた分は課金されません。

### English · How are exposures counted for a publication using a shared video?

When your publication uses a video another Sponsor shared, each Taker who watches it long enough counts one exposure:
- **Vertical videos**: 10 seconds of play without a break in the App's vertical mode (a video shorter than 10 seconds: played to the end). Your publication appears there as a push, taking turns with other Sponsors; the preview on the home page's shelf does not count.
- **Horizontal videos**: 10 seconds on the quiz's start page with the video on the screen and the App in front. Autoplay on the home page does not count.

At most once per Taker and publication; your own views, the video provider's and your staff's are not counted; each Taker has a daily limit. Your own videos (your own materials) do not count exposures.
The "Exposures of the shared video" section of your publication's page shows the exposures bought ("Unlimited" when none were bought), counted, charged, the pushes opened and the Takers who went on to answer. Once the exposures bought are used up, the publication ends within a minute (the reason: "Exposures used up"); attempts already started finish as usual, and you get an email. A few more may be counted meanwhile: what goes beyond what you bought is not charged.
