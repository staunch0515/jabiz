# 帮助文案：短视频与全屏的视频模式（F-S10；Q113c 起所有视频）

给学者与发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。规则与运维见 `docs/quizbuks/plans/impl/F-S10.md`、`operations.md` §7.25。

- 2026-11-28 起首页没有"短视频"一排（照 X）：全屏的视频模式只从首页帖子的视频进入。

## 一、给学者（FAQ 的 `audience` = `TAKER`，分类 `ANSWERING`）

### 中文 · 首页的视频怎么全屏看？

点首页帖子上的视频（或首页中间正在播放的视频），就进入全屏的视频模式：黑底，一屏一个视频，从你点的那一个开始，按首页的顺序往下，没有视频的 Quiz 会跳过。
在全屏模式中，往上滑看下一个、往下滑看上一个；也可以用右上角的上一个、下一个按钮，或键盘的方向键。横屏的视频在屏幕中间，竖屏的占满高度。视频下方有发起方、Quiz 名称和奖励，以及两个按钮：点"详情"进入这个 Quiz 的开始页（看资料和规则）；点"开始答题"直接开始。有进行中的答题时按钮是"继续答题"；有参加费时会先问你。点左上角的 ×（或按 Esc、系统的返回）回到首页原来的位置。
从首页点进来时声音已经打开，往下滑的视频也有声音（直接打开分享的链接时先点一下"开声音"）；其他网站的视频要点一下播放器才开声音。系统设为"减少动态效果"时视频不会自动播放，点"播放"再看。
在全屏模式中看的视频同样算进"看完即可答题"：还没看够时"开始答题"会显示还差几秒，看够了就可以直接开始。

### 日本語 · ホームの動画を全画面で見るには？

ホームの投稿の動画（またはホームの真ん中で再生中の動画）をタップすると、全画面の動画モードになります。黒い背景に 1 画面 1 本の動画で、タップした動画からホームの順に続きます。動画のないクイズは飛ばします。
全画面のモードでは、上にスワイプすると次の動画、下にスワイプすると前の動画です。右上の「前の動画」「次の動画」ボタンやキーボードの矢印キーでも移動できます。横向きの動画は画面の中央に、縦型の動画は画面の高さいっぱいに表示されます。動画の下に主催者、クイズの名前と報酬、そして 2 つのボタンがあります。「詳細」でそのクイズの開始ページ（資料とルール）へ、「クイズを始める」ですぐに回答を始めます。回答中のクイズは「クイズの続き」になり、参加費があるときは先に確認します。左上の ×（または Esc キー、端末の戻る操作）でホームの元の位置に戻ります。
ホームからタップして開くと音声はオンになっていて、スワイプした次の動画も音声が出ます（共有リンクを直接開いたときは、まず「音声をオンにする」をタップします）。ほかのサイトの動画は、プレーヤーをタップすると音声がオンになります。端末で「視差効果を減らす（動きを減らす）」を設定していると動画は自動では再生されません。「再生」をタップしてください。
全画面のモードで見た動画も「動画を見て回答」の視聴に含まれます。まだ足りないときは「クイズを始める」にあと何秒かが表示され、見終わればそのまま始められます。

### English · How do I watch the home page's videos in full screen?

Tap the video of a post on the home page (or the video playing in the middle of it) to open the full-screen video mode: black, one video a screen, starting from the one you tapped and going on in the home page's order; quizzes without a video are skipped.
In full screen, swipe up for the next video and down for the one before; the previous and next buttons at the top right and the arrow keys work too. A landscape video is in the middle of the screen, a vertical one fills its height. Under the video are the Sponsor, the quiz's name and reward, and two buttons: "Details" opens the quiz's start page (its materials and rules), and "Start the quiz" starts it right away. With an attempt in progress it reads "Continue the quiz"; with an entry fee it asks first. The × at the top left (or Esc, or your device's back) returns to where you were on the home page.
Opened from the home page, the sound is on, and the videos you swipe to keep it (a shared link opened directly asks for one tap on "Turn the sound on" first); for other sites, tap the player to turn its sound on. If your device is set to reduce motion, videos do not start by themselves: tap "Play".
Watching a video in full screen counts towards "Watch to answer": until you have watched enough, "Start the quiz" says how many seconds are left, then it starts right away.

## 二、给发起方（FAQ 的 `audience` = `SPONSOR`，分类 `PUBLISHING`）

### 中文 · 竖屏视频在学者的 App 中怎样显示？

发布的视频是竖屏的，学者首页的帖子上显示为竖着的 9:16 视频框，点开后的全屏模式中铺满整个屏幕。两种情况算竖屏：
- 资料的视频链接是 YouTube 的 Shorts 链接（`youtube.com/shorts/…`），不用另外设置；
- 其他竖着拍的视频（9:16），在编辑视频资料时勾选"竖屏视频"。
用作卡片视频的是"看完即可答题"的视频，没有时是第一个能播放的视频；它是竖屏的，发布才算竖屏。竖屏与否在发布上线时随卡片固定，切换发布的版本时按新版本更新。审核页也会显示"竖屏"。
首页的条件照旧：地区、客户匹配、中国大陆的境外视频规则等，不符合的学者看不到。

### 日本語 · 縦型の動画はテイカーのアプリでどう表示されますか？

公開の動画が縦型であれば、テイカーのホームの投稿では縦長の 9:16 の枠で表示され、タップして開く全画面のモードでは画面いっぱいに表示されます。縦型として扱われるのは次の 2 つです。
- 資料の動画リンクが YouTube ショートのリンク（`youtube.com/shorts/…`）のとき。設定は不要です。
- そのほかの縦向きに撮影した動画（9:16）は、動画の資料を編集するときに「縦型動画」をチェックします。
カードの動画は「動画を見て回答」の動画、なければ最初に再生できる動画です。その動画が縦型のとき、公開が縦型になります。縦型かどうかは公開の開始時にカードとともに固定され、公開のバージョンを切り替えると新しいバージョンに合わせて更新されます。審査のページにも「縦型」と表示されます。
ホームの条件はこれまでどおりです。地域、顧客マッチング、中国本土の海外動画の規則などに合わないテイカーには表示されません。

### English · How is a vertical video shown in the Takers' app?

When the video of your publication is vertical, the post on the Takers' home page shows it in an upright 9:16 box, and the full-screen mode it opens fills the whole screen with it. A video is vertical when:
- the material's video link is a YouTube Shorts link (`youtube.com/shorts/…`), with nothing more to set;
- for any other video filmed upright (9:16), you tick "Vertical video" when editing the video material.
The card's video is the "Watch to answer" video, or else the first video that plays; the publication is vertical when that video is. It is fixed with the card when the publication goes live and follows a switch of the publication's version. The review page says "Vertical" too.
The home page's conditions are as before (regions, audience matching, the rule on foreign videos in mainland China): Takers who do not meet them do not see it.
