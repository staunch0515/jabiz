# 帮助文案：短视频（F-S10）

给学者与发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。规则与运维见 `docs/quizbuks/plans/impl/F-S10.md`、`operations.md` §7.25。

- 参数（写进文案的是缺省值，改了参数时同步改这里）：首页在第 3 张与第 12 张卡之后各放一排短视频（`qb.feed.shorts.positions`），每排至多 10 个（`qb.feed.shorts.per-shelf`）。

## 一、给学者（FAQ 的 `audience` = `TAKER`，分类 `ANSWERING`）

### 中文 · 首页的"短视频"是什么？

首页在几张卡之后会出现一排竖着的小卡，标题是"短视频"：这些 Quiz 的视频是竖屏的（例如 YouTube 的 Shorts）。左右滑动可以看这一排，点一张就进入全屏的竖屏模式。
在全屏模式中，往上滑看下一个、往下滑看上一个；也可以用右上角的上一个、下一个按钮，或键盘的方向键。点"去答题"进入这个 Quiz 的开始页；点左上角的返回（或按 Esc、系统的返回）回到首页原来的位置。
YouTube 与 Vimeo 的视频点一下"开声音"后，之后的视频会继续有声音；其他网站的视频要点一下播放器才开声音。系统设为"减少动态效果"时视频不会自动播放，点"播放"再看。
在全屏模式中看的视频同样算进"看完即可答题"：看完后点"去答题"就可以直接开始。没有竖屏视频的 Quiz 时，首页不会出现这一排。

### 日本語 · ホームの「ショート動画」とは何ですか？

ホームでは、何枚かのカードの後に「ショート動画」という縦長の小さなカードの列が表示されます。動画が縦型（YouTube ショートなど）のクイズです。列は左右にスクロールでき、カードをタップすると全画面の縦型モードになります。
全画面のモードでは、上にスワイプすると次の動画、下にスワイプすると前の動画です。右上の「前の動画」「次の動画」ボタンやキーボードの矢印キーでも移動できます。「回答する」でそのクイズの開始ページへ、左上の戻るボタン（または Esc キー、端末の戻る操作）でホームの元の位置に戻ります。
YouTube と Vimeo の動画は「音声をオンにする」を一度タップすると、次の動画でも音声が出ます。ほかのサイトの動画は、プレーヤーをタップすると音声がオンになります。システムで「減少動態効果（視差効果を減らす、動きを減らす）」を設定していると動画は自動では再生されません。「再生」をタップしてください。
全画面のモードで見た動画も「動画を見て回答」の視聴に含まれます。見終わったら「回答する」からそのまま始められます。縦型動画のクイズがないときは、この列は表示されません。

### English · What are the "Shorts" on the home page?

After a few cards, the home page shows a row of small upright cards titled "Shorts": quizzes whose video is vertical (such as YouTube Shorts). Scroll the row sideways and tap a card to open the full-screen vertical mode.
In full screen, swipe up for the next video and down for the one before; the previous and next buttons at the top right and the arrow keys work too. "Answer" opens the quiz's start page; the back button at the top left (or Esc, or your device's back) returns to where you were on the home page.
For YouTube and Vimeo videos, tap "Turn the sound on" once and the next videos keep their sound; for other sites, tap the player to turn its sound on. If your system is set to reduce motion, videos do not start by themselves: tap "Play".
Watching a video in full screen counts towards "Watch to answer": when you have watched it, tap "Answer" and start right away. When there are no quizzes with a vertical video, the row is not shown.

## 二、给发起方（FAQ 的 `audience` = `SPONSOR`，分类 `PUBLISHING`）

### 中文 · 怎样让我的发布出现在学者首页的"短视频"中？

发布的视频是竖屏的，就会出现在 App 首页的"短视频"一排与全屏的竖屏模式中（同时照常出现在首页的卡片中）。两种情况算竖屏：
- 资料的视频链接是 YouTube 的 Shorts 链接（`youtube.com/shorts/…`），不用另外设置；
- 其他竖着拍的视频（9:16），在编辑视频资料时勾选"竖屏视频"。
用作卡片视频的是"看完即可答题"的视频，没有时是第一个能播放的视频；它是竖屏的，发布才算竖屏。竖屏与否在发布上线时随卡片固定，切换发布的版本时按新版本更新。审核页也会显示"竖屏"。
短视频的一排与首页用同样的条件：地区、客户匹配、中国大陆的境外视频规则等，不符合的学者看不到。

### 日本語 · 自分の公開をテイカーのホームの「ショート動画」に表示するには？

公開の動画が縦型であれば、アプリのホームの「ショート動画」の列と全画面の縦型モードに表示されます（ホームの通常のカードにもこれまでどおり表示されます）。縦型として扱われるのは次の 2 つです。
- 資料の動画リンクが YouTube ショートのリンク（`youtube.com/shorts/…`）のとき。設定は不要です。
- そのほかの縦向きに撮影した動画（9:16）は、動画の資料を編集するときに「縦型動画」をチェックします。
カードの動画は「動画を見て回答」の動画、なければ最初に再生できる動画です。その動画が縦型のとき、公開が縦型になります。縦型かどうかは公開の開始時にカードとともに固定され、公開のバージョンを切り替えると新しいバージョンに合わせて更新されます。審査のページにも「縦型」と表示されます。
ショート動画の列は、地域、顧客マッチング、中国本土の海外動画の規則など、ホームと同じ条件で表示されます。条件に合わないテイカーには表示されません。

### English · How does my publication appear among the "Shorts" on Takers' home page?

When the video of your publication is vertical, the app shows it in the "Shorts" row of its home page and in its full-screen vertical mode (it also stays among the home page's usual cards). A video is vertical when:
- the material's video link is a YouTube Shorts link (`youtube.com/shorts/…`), with nothing more to set;
- for any other video filmed upright (9:16), you tick "Vertical video" when editing the video material.
The card's video is the "Watch to answer" video, or else the first video that plays; the publication is vertical when that video is. It is fixed with the card when the publication goes live and follows a switch of the publication's version. The review page says "Vertical" too.
The Shorts row follows the same conditions as the home page (regions, audience matching, the rule on foreign videos in mainland China): Takers who do not meet them do not see it.
