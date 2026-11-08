# 帮助文案：主题词与"只看喜欢的主题"（F-S9）

给学者与发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。规则与运维见 `docs/quizbuks/plans/impl/F-S9.md`、`operations.md` §7.26。

- 参数（写进文案的是缺省值，改了参数时同步改这里）：一个 Quiz 或发布最多 10 个主题词（`qb.topic.max-per-quiz`，词表中的词与自定义词合计），一个自定义词最多 30 个字（`qb.topic.custom-max-length`）。
- 内部备注（不写进 FAQ）：自定义词的搜索等 F-S6 合入后接上；推送等 F-S13。在那之前，FAQ 中"自定义词用于搜索"一句可以先不发布。

## 一、给学者（FAQ 的 `audience` = `TAKER`，分类 `ANSWERING`）

### 中文 · 怎样只看我喜欢的主题？

打开"账户 → 关于我"，在"喜欢的主题"中勾选你喜欢的主题，每次勾选都会立刻保存。然后打开"只看喜欢的主题"：首页与"短视频"一排只显示带有你喜欢的主题的 Quiz，首页上方会提示"只显示你喜欢的主题"。
发到你邮箱的邀请与你和发起方的对话不受影响。想看全部时，随时可以在同一个地方关掉；关掉之前不能把喜欢的主题全部去掉。
主题是平台词表中的词。不再提供的词，如果你以前选过，会继续显示为"已不再提供"，取消勾选后就不能再选。
没有设主题词的发布（包括这项功能推出之前就已发布的）在"只看喜欢的主题"中不会出现；关掉后照常可以看到。

### 日本語 · 好きなトピックだけを表示するには？

「アカウント → わたしについて」を開き、「好きなトピック」で好きなトピックにチェックを入れてください。チェックするたびにすぐ保存されます。続けて「好きなトピックだけ表示」をオンにすると、ホームと「ショート動画」の列には好きなトピックを含むクイズだけが表示され、ホームの上部に「好きなトピックだけを表示しています」と表示されます。
あなたのメールアドレスに届いた招待と、主催者との会話は影響を受けません。すべて見たいときは、同じ場所でいつでもオフにできます。オンのままでは好きなトピックをすべて外すことはできません。
トピックはプラットフォームの語彙リストの語です。提供が終了した語は、以前に選んでいれば「提供終了」と表示されたまま残り、チェックを外すと選び直せなくなります。
トピックが設定されていない公開（この機能の開始前から公開中のものを含む）は、「好きなトピックだけ表示」では表示されません。オフにすれば通常どおり表示されます。

### English · How do I see only the topics I like?

Open "Account → About me" and tick the topics you like under "Topics you like"; each tick is saved at once. Then turn on "Show only the topics I like": the home page and the "Shorts" row show only quizzes with one of your topics, and the home page says "Showing only the topics you like."
Quizzes sent to your email address and your conversations with Sponsors are not affected. To see everything again, turn it off in the same place at any time; while it is on you cannot untick every topic.
Topics are words of the platform's list. A word that is no longer offered stays listed as "No longer offered" if you chose it before; once unticked it cannot be chosen again.
Publications without topics (including those published before this feature) do not appear while "Show only the topics I like" is on; turn it off to see them as usual.

## 二、给发起方（FAQ 的 `audience` = `SPONSOR`，分类 `PUBLISHING`）

### 中文 · 怎样给 Quiz 和发布设主题词？

在 Quiz 的编辑页（"Quiz"一栏）的"主题词"中，从平台词表里选主题词（显示为你的界面语言），没有合适的词时可以加自定义词；合计最多 10 个，自定义词最多 30 个字。每次修改都会立刻保存。保存版本时，版本会带上当时的主题词。
发布以所选版本的主题词为基础：在发布向导的第一步，可以去掉不合适的、再加别的（同样合计最多 10 个），随草稿一起保存。提交后主题词随发布固定；切换发布的版本时，按新版本的主题词加上你的增删重新计算。
打开"只看喜欢的主题"的学者，只有在发布的词表中的词与他们喜欢的主题相符时才会看到你的发布；自定义词只用于搜索，不用于这一点，所以只有自定义词的发布不会出现在他们的首页。
平台停用的词不能再选，已经选了的照常显示（标为"已不再提供"）。

### 日本語 · クイズと公開のトピックを設定するには？

クイズの編集ページ（「クイズ」タブ）の「トピック」で、プラットフォームの語彙リストからトピックを選びます（表示はあなたの画面の言語です）。合う語がなければ独自の語も追加できます。合計 10 個まで、独自の語は 30 文字までです。変更するたびにすぐ保存されます。バージョンを保存すると、そのときのトピックがバージョンに保存されます。
公開は選んだバージョンのトピックを基にします。公開ウィザードの最初のステップで、合わないものを外し、ほかを追加できます（同じく合計 10 個まで）。下書きと一緒に保存されます。提出後はトピックが公開とともに固定され、公開のバージョンを切り替えると、新しいバージョンのトピックにあなたの追加・除外を当てはめて計算し直します。
「好きなトピックだけ表示」にしているテイカーには、公開の語彙リストの語が好きなトピックと一致したときだけ表示されます。独自の語は検索だけに使い、この判定には使いません。そのため、独自の語だけの公開はそのテイカーのホームには表示されません。
プラットフォームが停止した語は新たに選べませんが、すでに選んだものはそのまま表示されます（「提供終了」と表示）。

### English · How do I set the topics of a quiz and a publication?

In the quiz editor ("Quiz" tab), under "Topics", choose topics from the platform's word list (shown in your interface language), and add words of your own if none fits; up to 10 in all, your own words up to 30 characters each. Each change is saved at once. When you save a version, it keeps the topics it has then.
A publication starts from the topics of its version: in the first step of the publication wizard, leave out those that do not fit and add others (again up to 10 in all); they are saved with the draft. Once submitted, the topics are fixed with the publication; when you switch the publication's version, your changes are applied to the new version's topics.
Takers who show only the topics they like see your publication only when one of its words of the list matches a topic they like. Your own words are for search only and do not count for this, so a publication with only your own words does not appear on their home page.
Words the platform no longer offers cannot be chosen anew; those already chosen stay shown (marked "No longer offered").
