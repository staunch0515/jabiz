# 帮助文案：屏蔽与解除（F-S8）

给学者与发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。规则与运维见 `docs/quizbuks/plans/impl/F-S8.md`、`operations.md` §7.30。

- 参数（写进文案的是缺省值，改了参数时同步改这里）：一个学者最多屏蔽 1000 项（`qb.block.max-per-user`）；发起方看到的人数少于 20 人时只显示"少于 20 人"（`qb.block.count-min-group`），否则向下取整到十位。
- 平台原则（写进文案）：屏蔽是硬屏蔽，发起方付多少钱都越不过。
- 内部备注（不写进 FAQ）：F-S6 的完整搜索与 F-S13 的推送做时套用同一条件；在那之前"搜索"指首页的搜索。

## 一、给学者（FAQ 的 `audience` = `TAKER`，分类 `ANSWERING`）

### 中文 · 怎样屏蔽不想看的内容？怎样解除？

在首页的卡片、"短视频"的全屏画面或 Quiz 的页面上点 ⋮，可以选：
- **不想看**：只屏蔽这一个 Quiz；
- **屏蔽这个 Quiz 的所有期**：包括以后新发的各期；
- **屏蔽发起方**：这个发起方的所有 Quiz；
- **屏蔽视频提供方**：Quiz 用了其他发起方共享的视频时才有，凡是用了这个提供方的视频的 Quiz 都不再显示。

屏蔽后，提示中可以马上"撤销"。被屏蔽的内容不会出现在首页、"短视频"、搜索与收件箱的推广中，也不能开始答题。这是硬屏蔽：发起方付多少钱都越不过。
打开被屏蔽的 Quiz 的链接时，页面会显示"你已屏蔽"，可以在那里解除。已经开始或答过的 Quiz 仍可以继续答完、查看结果。
在"账户 → App 设置 → 屏蔽"中可以看到你屏蔽的全部内容，逐个解除。最多可以屏蔽 1000 项。
屏蔽只属于你自己：发起方看不到是谁屏蔽了他们，只能看到大约有多少人。删除账号时，你的屏蔽一起删除。

### 日本語 · 見たくないものをブロックするには？解除するには？

ホームのカード、「ショート動画」の全画面、またはクイズのページで ⋮ を押すと、次から選べます。
- **興味なし**：このクイズだけをブロックします。
- **このクイズのすべての回をブロック**：今後公開される回も含みます。
- **主催者をブロック**：この主催者のすべてのクイズです。
- **動画の提供者をブロック**：ほかの主催者が共有した動画を使っているクイズにだけ表示されます。この提供者の動画を使ったクイズはすべて表示されなくなります。

ブロックした直後は、表示されるメッセージから「元に戻す」ことができます。ブロックしたものは、ホーム・「ショート動画」・検索・受信箱のおすすめに表示されず、回答も始められません。これは強いブロックで、主催者がいくら支払っても越えられません。
ブロックしたクイズのリンクを開くと「ブロックしています」と表示され、その場で解除できます。すでに始めた、または回答したクイズは、最後まで回答したり結果を見たりできます。
「アカウント → アプリの設定 → ブロック」で、ブロックしたものをすべて確認し、1 つずつ解除できます。最大 1000 件までブロックできます。
ブロックはあなただけのものです。主催者には誰がブロックしたかは表示されず、おおよその人数だけが表示されます。アカウントを削除すると、ブロックも一緒に削除されます。

### English · How do I block what I don't want to see, and unblock it?

Tap ⋮ on a card on the home page, on the full-screen "Shorts" view or on a quiz's page, and choose:
- **Not interested**: blocks this quiz only;
- **Block every round of this quiz**: including rounds published later;
- **Block the Sponsor**: every quiz of this Sponsor;
- **Block the video provider**: shown only when the quiz uses a video another Sponsor shared; no quiz with this provider's videos is shown any more.

Right after blocking, the message offers "Undo". What you block does not appear on your home page, in "Shorts", in search or in the promotions in your inbox, and cannot be started. It is a hard block: no Sponsor can pay to get past it.
Opening the link of a quiz you blocked shows "You blocked this", where you can unblock it. A quiz you already started or answered can still be finished, and its result seen.
"Account → App settings → Blocked" lists everything you blocked; unblock each one there. You can block up to 1000 things.
Your blocks are yours alone: Sponsors never see who blocked them, only roughly how many people did. Deleting your account deletes your blocks too.

## 二、给发起方（FAQ 的 `audience` = `SPONSOR`，分类 `PUBLISHING`）

### 中文 · "屏蔽你的学者"是什么？

学者可以屏蔽你的某个 Quiz、你的全部 Quiz，或者你作为视频提供方共享的视频。被屏蔽后，你的内容不会再出现在这些学者的首页、短视频、搜索与收件箱中，他们也不能答题。这是平台的原则：付多少钱（奖励、推广、受众规则）都越不过学者的屏蔽。
发起方页面首页的"屏蔽你的学者"显示大约有多少位学者屏蔽了你、你的 Quiz 或你作为视频提供方：向下取整到十位，少于 20 人时只显示"少于 20 人"。为保护学者，平台不告诉你是谁、屏蔽了什么，客服也不能透露。
人数多时，请检查 Quiz 的内容与推广是否让学者感到困扰。

### 日本語 · 「あなたをブロックしたテイカー」とは？

テイカーは、あなたのクイズ、あなたのすべてのクイズ、または動画の提供者としてあなたが共有した動画をブロックできます。ブロックされると、そのテイカーのホーム・ショート動画・検索・受信箱にはあなたのコンテンツが表示されず、回答もできなくなります。これはプラットフォームの原則で、いくら支払っても（報酬、おすすめ、対象者のルール）テイカーのブロックは越えられません。
主催者ページのダッシュボードの「あなたをブロックしたテイカー」には、あなた、あなたのクイズ、または動画の提供者としてのあなたをブロックしたテイカーのおおよその人数が表示されます。10 の位に切り捨て、20 人未満のときは「20 人未満」とだけ表示します。テイカーを守るため、誰が何をブロックしたかはお知らせできません。サポートでもお答えできません。
人数が多いときは、クイズの内容やおすすめがテイカーの迷惑になっていないか確認してください。

### English · What is "Takers who blocked you"?

Takers can block one of your quizzes, all your quizzes, or the videos you share as a video provider. Once blocked, your content no longer appears on those takers' home page, in Shorts, in search or in their inbox, and they cannot answer it. This is a principle of the platform: no amount paid (rewards, promotion, audience rules) gets past a taker's block.
"Takers who blocked you" on your dashboard shows roughly how many takers blocked you, one of your quizzes, or you as a video provider: rounded down to tens, and "Fewer than 20" below 20. To protect takers, the platform never tells you who blocked you or what, and support cannot either.
If the number is high, check whether your quizzes or promotions bother takers.
